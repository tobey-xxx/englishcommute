package com.example.englishcommute.checkin

import android.content.Context
import androidx.media3.common.Player
import com.example.englishcommute.diag.DiagLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 收听时长计时器。
 *
 * ## 为什么挂在 ExoPlayer 上，而不是界面或 ViewModel 上
 *
 * 通勤场景的真实用法是「点播放 → 锁屏揣兜」。计时如果挂在
 * [com.example.englishcommute.ui.PlayerViewModel] 上，锁屏后 Activity 被回收、
 * ViewModel 被清理 —— **整段通勤一分钟都不会被记上**，而这恰恰是用户
 * 唯一会听内容的时间。挂在 [com.example.englishcommute.player.PlaybackService]
 * 里的播放器上，才和「声音到底有没有在响」严格同步。
 *
 * ## 计时口径：墙钟时间，不是音频内容时间
 *
 * 1 倍速听 10 分钟 = 10 分钟；2 倍速听 10 分钟**也是 10 分钟**（而不是 20 分钟）。
 *
 * 选墙钟是因为这个功能的语义是「你今天花了多少时间在听英语」——
 * 那是习惯，不是吞吐量。按内容时间算的话，2 倍速用户 20 分钟就能打卡，
 * 而他要的显然不是「快点把卡打上」。
 *
 * ## 计时的边界
 *
 * | 播放器状态 | 计不计 |
 * |---|---|
 * | `isPlaying && STATE_READY` | ✅ |
 * | 暂停 | ❌ |
 * | 缓冲中（`STATE_BUFFERING`） | ❌ 没有声音在响 |
 * | 播完停在结尾 | ❌ `isPlaying` 已是 false |
 *
 * 跨午夜自动分账：每一段累加前先算它属于哪个本地日，日期一变就先把
 * 上一段结清 —— 半夜听到两点不会把两天的时长混在一起。
 *
 * ## 落盘节奏
 *
 * 内存里攒够 [FLUSH_SECONDS] 秒才写一次 prefs。代价是进程被杀时会丢
 * 最多 5 秒 —— 相对 40 分钟的目标，5 秒的误差无所谓，而每秒写一次
 * 是几百次全量写盘。
 *
 * ## ⚠️ 调度器必须是 Main（0.12.0 真机闪退的根因）
 *
 * `player.isPlaying` / `player.playbackState` 是**只能在主线程读**的 ExoPlayer API，
 * 在别的线程读会抛 `IllegalStateException: Player is accessed on the wrong thread`。
 *
 * 这里一开始写的是 `Dispatchers.Default`，代价是**一播放就闪退**：
 * 协程里未捕获的异常会交给进程的默认异常处理器 —— 那是**进程级崩溃**，
 * 不是外面套个 try/catch 能兜住的小错。计时器每秒 tick 一次，
 * 等于每秒判一次死刑。真机日志（v0.12.0）里是 `attach` 之后**恰好 1.000 秒**
 * 抛出的，两次启动、两次都在同一行崩。
 */
object ListeningClock {

    /** 计时器：读播放状态 + 记账。必须在主线程，理由见上 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * 海报渲染专用：Canvas 绘制 + PNG 压缩是几十到几百毫秒的 CPU 活，
     * 放主线程会卡 UI。与计时器分开，是因为它恰恰**不该**在主线程跑。
     */
    private val workScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var job: Job? = null

    /** 海报生成需要它写文件（缓存目录），所以连 Context 一起装进来 */
    private var appContext: Context? = null
    private var store: CheckInStore? = null

    /**
     * 由 [com.example.englishcommute.EnglishCommuteApp] 在容器构造之后调用一次。
     *
     * 必须和容器里那份 [CheckInStore] 是**同一个实例** —— 它持有界面订阅的
     * StateFlow，另建一份的话界面上的今日进度永远不动。
     */
    fun install(context: Context, checkIn: CheckInStore, onTicked: () -> Unit = {}) {
        appContext = context.applicationContext
        store = checkIn
        this.onTicked = onTicked
    }

    /**
     * 每次把攒下的秒数写进 [CheckInStore] 之后调一次。
     *
     * ★ 同步挂在这里（而不是只在「回到前台」或后台定时）是多设备能不能
     *   真的「接得上」的关键：**边听边传**，用户从 A 切到 B 时数据已经在了。
     *   回调本身必须极轻（内部有 2 分钟节流），否则每 5 秒一次的计时回路上
     *   会多出一次网络判断。
     */
    private var onTicked: () -> Unit = {}

    @Volatile
    private var lastTickAt = 0L

    /** 上一次把秒数写进 prefs 的时刻，设置页用来判断「计时器还活着吗」 */
    val lastFlushAt: Long get() = lastTickAt

    fun attach(player: Player) {
        job?.cancel()
        job = scope.launch { runTimer(player) }
        DiagLog.i("Check", "收听计时已挂上播放器")
    }

    fun detach() {
        job?.cancel()
        job = null
    }

    private suspend fun runTimer(player: Player) {
        val checkIn = store
        if (checkIn == null) {
            // 只在异常启动顺序下发生（服务比 Application 先到）。宁可静默不计时，
            // 也不要在这里新建一份 store —— 那会让界面读到一份永远不动的进度
            DiagLog.w("Check", "计时器未安装 store，本轮不计时")
            return
        }

        var pending = 0
        var pendingDay = DayKey.today()
        var mark = System.currentTimeMillis()

        // 用 `while (true)` + `finally` 而不是靠 isActive 判断：
        // delay 本身就是取消点，"取消" 这件事只在下一行 delay 处**抛出**，
        // 所以循环体不需要额外的条件；写 `while (isActive)` 反而会漏掉
        // 「刚好在 delay 之后、累加之前被取消」的那几秒。
        // finally 里的 flush 是同步函数，在已取消的协程里照样能跑完 ——
        // 这正是"服务销毁时兜住最后几秒"要的效果。
        try {
            while (true) {
                delay(TICK_MS)
                val now = System.currentTimeMillis()
                val elapsedSec = ((now - mark) / 1000).toInt()
                mark = now

                // 时钟跳变 / 进程被冻结：这一段的真实情况已经无从判断，直接丢弃。
                // 记上它意味着「手机在兜里被冻结了 10 分钟，打卡凭空多 10 分钟」
                if (elapsedSec <= 0 || elapsedSec > MAX_CREDIT_SECONDS) continue

                // 读不到播放状态就收工：可能是「服务销毁时播放器已被释放」，
                // 也可能是将来又有人把调度器改错。无论哪种，异常都**不能**逃到
                // 协程的未捕获处理器 —— 那是进程级崩溃，用户看到的就是闪退。
                val counting = try {
                    player.isPlaying && player.playbackState == Player.STATE_READY
                } catch (t: Throwable) {
                    DiagLog.w("Check", "读播放状态失败，计时器退出：${t.javaClass.simpleName}")
                    return
                }
                if (!counting) {
                    pending = flush(checkIn, pendingDay, pending)
                    continue
                }

                val day = DayKey.today(now)
                if (day != pendingDay) {
                    pending = flush(checkIn, pendingDay, pending)
                    pendingDay = day
                }
                pending += elapsedSec
                if (pending >= FLUSH_SECONDS) pending = flush(checkIn, pendingDay, pending)
            }
        } finally {
            // 协程被取消（服务销毁）时兜住最后几秒
            flush(checkIn, pendingDay, pending)
        }
    }

    /** 把攒下的秒数交给 [CheckInStore]，跨过目标线时顺手生成海报 */
    private fun flush(checkIn: CheckInStore, day: Long, pending: Int): Int {
        if (pending <= 0) return 0
        val tick = checkIn.addSeconds(day, pending)
        lastTickAt = System.currentTimeMillis()
        // 先跨线庆祝（本地、即时），再通知同步（异步、可失败）
        if (tick.justCompleted) celebrate(day, tick.streak)
        runCatching { onTicked() }
        return 0
    }

    /**
     * 打卡成功 → **自动生成海报**。
     *
     * 放在这里（跨过目标线的那一刻）而不是「用户打开打卡页时」：
     * 需求是「每日打卡成功，自动生成一个海报」。等他打开页面才生成的话，
     * 一旦那天没打开，这张海报就永远不会存在 —— 而连续天数是会天天变的，
     * 事后补画出来的是**今天的**数字，不是打卡当天的数字。
     *
     * 失败不往上抛：海报是锦上添花，生不出图不该影响计时。
     */
    private fun celebrate(day: Long, streak: Int) {
        val ctx = appContext ?: return
        val checkIn = store ?: return
        // 用 workScope 而不是 scope：渲染海报（Canvas + PNG 压缩）是 CPU 活，
        // 而 scope 现在是主线程调度器，放上去会卡住 UI
        workScope.launch {
            // ★ 走 CheckInPoster.ensureFile，与庆祝页、打卡页**同一条路径**（1.2.7）。
            //
            //   这里原本直接调 render()，而庆祝页那边只写了「等文件」——
            //   三个入口各写一份的结果是：庆祝页等 4 秒等不到就空着，
            //   而用户退到打卡页图立刻就有。同一件事有多个答案，
            //   分歧不会被任何一处发现。
            //
            //   ensureFile 内部是「有就返回、没有才渲染」+ 串行锁，
            //   所以这一次调用与庆祝页那次几乎同时发生时只有一个人真干活
            runCatching { CheckInPoster.ensureFile(ctx, checkIn, day) }
                .onSuccess { f -> if (f != null) DiagLog.i("Check", "打卡海报已生成：${f.name}（连续 $streak 天）") }
                .onFailure { DiagLog.w("Check", "海报生成失败（不影响打卡）：${it.message}") }
        }
    }

    private const val TICK_MS = 1_000L

    /** 攒够这么多秒才落一次盘 */
    private const val FLUSH_SECONDS = 5

    /**
     * 单次最多认几秒。
     *
     * 正常 tick 是 1 秒，这里给到 5 秒的富余；超过就说明进程被冻结过或时钟跳过，
     * 那一整段都不认 —— 宁可少记，也不要凭空多出一段「听过的」时间。
     */
    private const val MAX_CREDIT_SECONDS = 5
}
