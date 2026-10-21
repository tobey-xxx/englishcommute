package com.example.englishcommute.sync

import com.example.englishcommute.account.AccountStore
import com.example.englishcommute.checkin.CheckInStore
import com.example.englishcommute.checkin.DayKey
import com.example.englishcommute.diag.DiagLog
import com.example.englishcommute.net.ApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 打卡记录的云端同步（4.0.0）。
 *
 * ## 一次往返，先收后回
 *
 * ```
 * 上行：{ device, days: {我这一天共听了多少}, goals: {目标时间轴}, since }
 * 下行：{ days: {全设备合计}, goals: {...}, devices, serverDay }
 * ```
 *
 * 上行的是**本机那一列**（只有本机写），下行的是**所有设备的总和** ——
 * 于是「A 听了 25 分钟、切到 B 看到 25、再听 15 就达标」这件事，靠两个
 * 各自只会增的计数相加就成立了（推导见 [CheckInStore.applyServerTotals]）。
 *
 * ## 为什么不做成 pull / push 两个接口
 *
 * 两个接口就有「先 pull 还是先 push」的顺序问题，而任何一次顺序错了
 * （或中间断网）都会留下一个**本地与服务端不一致、且没人知道**的状态。
 * 合一次：幂等、可重试、没有中间态。
 *
 * ## 什么时候同步（**不新增后台任务**）
 *
 * 延续 3.5.0 刚定下的方向：挂在这三条已有路径上 ——
 *
 * | 触发 | 为什么是它 |
 * | --- | --- |
 * | 播放中每追加一段时长（内部节流 2 分钟） | ★ 这条最关键：**边听边传**，用户切到另一台设备时数据已经到了 |
 * | 回到前台 | 那是「用户主动来了」的信号，顺手对齐一次 |
 * | 用户点「立即同步」 | 他说了算 |
 *
 * ⚠️ 刻意**不用** WorkManager：一是 3.5.0 刚拆掉那条几乎不执行的每日任务；
 * 二是同步的前提本来就是「这台设备刚听过」或「用户来了」，
 * 而这两件事在后台定时里都不成立。
 */
class CheckInSync(
    private val checkIn: CheckInStore,
    private val account: AccountStore,
    private val api: ApiClient,
    private val scope: CoroutineScope,
) {

    enum class Phase { SIGNED_OUT, IDLE, SYNCING, ERROR }

    data class Status(
        val phase: Phase = Phase.SIGNED_OUT,
        /** 本机有改动还没成功上传 —— 界面要说出来，不能让用户以为已经同步了 */
        val pending: Boolean = false,
        val lastSyncAt: Long = 0L,
        /** 出错时给用户的一句话；必须能回答「我接下来做什么」 */
        val message: String? = null,
    )

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    /** 同一时刻只允许一次同步（计时器那条路会频繁触发） */
    private val running = AtomicBoolean(false)
    private var lastAttemptAt = 0L

    /** 两次自动同步之间的最小间隔。手动点不受它限制。 */
    private val minIntervalMs = 2 * 60_000L

    /** 上传 / 下行的天数上限：两年足够，也保证载荷有界。 */
    private val horizonDays = 730L

    // ------------------------------------------------------------------
    // 触发
    // ------------------------------------------------------------------

    /** 本机刚追加了时长（由 [com.example.englishcommute.checkin.ListeningClock] 调用） */
    fun onLocalChange() {
        scope.launch {
            account.markLocalChange()
            refreshStatus()
        }
        maybeSync()
    }

    /**
     * 「顺手同步一次」。**不阻塞、不抛异常** —— 调用方全是热路径
     * （每 5 秒一次的计时回调、每次回到前台）。
     */
    fun maybeSync(force: Boolean = false) {
        scope.launch { sync(force = force) }
    }

    /** 登录之后必须强制来一次：那一次就是「认领本机已有的记录」。 */
    fun syncAfterLogin() {
        scope.launch {
            account.markLocalChange()
            sync(force = true)
        }
    }

    private suspend fun refreshStatus(pending: Boolean? = null, message: String? = null) {
        val signedIn = account.signedIn.first()
        _status.value = Status(
            phase = when {
                !signedIn -> Phase.SIGNED_OUT
                message != null -> Phase.ERROR
                else -> Phase.IDLE
            },
            pending = pending ?: account.needsSync(),
            lastSyncAt = account.lastSyncAt.first(),
            message = message,
        )
    }

    // ------------------------------------------------------------------
    // 同步本体
    // ------------------------------------------------------------------

    suspend fun sync(force: Boolean = false): Boolean {
        if (!account.signedIn.first()) {
            refreshStatus()
            return false
        }
        val now = System.currentTimeMillis()
        if (!force && now - lastAttemptAt < minIntervalMs) return false
        if (!running.compareAndSet(false, true)) return false
        lastAttemptAt = now

        val token = account.token.first()
        if (token.isNullOrEmpty()) {
            running.set(false)
            refreshStatus()
            return false
        }

        _status.value = _status.value.copy(phase = Phase.SYNCING, message = null)
        try {
            val device = account.deviceId()
            val today = DayKey.today()
            val horizon = today - horizonDays
            val mineAll = checkIn.mineSnapshot()
            val goalsAll = checkIn.goalSnapshot()

            // 只上传 horizon 之内的天数：载荷有界，而两年之前的记录
            // 对「今天听了多久」没有任何影响（连续天数会用到，但那种场景下
            // 服务端本来就有更早的数据 —— 我们只是不重复上传）
            val mine = mineAll.filterKeys { it >= horizon }
            // since 取「客户端知道的最早那天」与 horizon 的较小者：
            // 下行必须覆盖客户端关心的全部区间，否则本机某些天的 others 会被抹成 0
            val knownMin = (mineAll.keys + goalsAll.keys).minOrNull() ?: horizon
            val since = minOf(horizon, knownMin)

            val payload = JSONObject().apply {
                put("device", device)
                put("since", since)
                put("days", JSONObject().apply {
                    mine.forEach { (day, sec) -> put(day.toString(), sec) }
                })
                put("goals", JSONObject().apply {
                    goalsAll.forEach { (day, sec) -> put(day.toString(), sec) }
                })
            }

            when (val res = api.post("/sync/checkin", payload, token)) {
                is ApiClient.Res.Ok -> {
                    val totals = readDayMap(res.json.optJSONObject("days"))
                    val goals = readDayMap(res.json.optJSONObject("goals"))
                    val devices = res.json.optInt("devices", 1)
                    checkIn.applyServerTotals(totals)
                    checkIn.applyServerGoals(goals)
                    account.markSynced()
                    DiagLog.i(
                        "Sync",
                        "同步完成 · 本机 ${mine.size} 天 · 合计 ${totals.size} 天 · " +
                            "共 $devices 台设备",
                    )
                    refreshStatus(pending = false)
                    return true
                }

                is ApiClient.Res.Err -> {
                    if (res.code == "unauthorized") {
                        // 令牌真的没用了。**这不叫登出** —— 界面上要请用户重新登录。
                        account.tokenRejected()
                        refreshStatus(message = "登录已失效，请重新登录")
                    } else {
                        DiagLog.w("Sync", "同步失败：${res.code}（${res.http}）")
                        refreshStatus(message = res.message)
                    }
                    return false
                }
            }
        } catch (e: Exception) {
            DiagLog.w("Sync", "同步异常：${e.javaClass.simpleName}")
            refreshStatus(message = "同步失败，稍后会自动再试")
            return false
        } finally {
            running.set(false)
        }
    }

    /**
     * 清空记录时**连服务端一起清**。
     *
     * ⚠️ 少了这一步，「重置打卡记录」会变成一个骗人的按钮：本地清完，
     *    下一次同步把服务端那几行原样搬回来 —— 用户看到的是
     *    「清空之后过一会儿又自己回来了」。
     *
     * 未登录 / 服务器不通时**不阻断本地的清空**：那是用户明确要求的动作，
     * 而它在本机的效果是确定的；下次同步时若服务端还有数据会再合并回来，
     * 所以这里失败要**说出来**（返回值），不能静默。
     */
    suspend fun wipeRemote(): Boolean {
        val token = account.token.first() ?: return true          // 没登录 = 没什么要清的
        return when (val res = api.post("/sync/wipe", null, token)) {
            is ApiClient.Res.Ok -> {
                DiagLog.act("同步：已清空服务端记录（${res.json.optInt("removed")} 行）")
                true
            }
            is ApiClient.Res.Err -> {
                DiagLog.w("Sync", "清空服务端失败：${res.code}")
                false
            }
        }
    }

    private fun readDayMap(o: JSONObject?): Map<Long, Int> {
        if (o == null) return emptyMap()
        val out = HashMap<Long, Int>()
        val keys = o.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val day = k.toLongOrNull() ?: continue
            out[day] = o.optInt(k, 0)
        }
        return out
    }
}
