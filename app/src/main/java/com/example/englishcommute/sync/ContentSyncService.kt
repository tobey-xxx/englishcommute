package com.example.englishcommute.sync

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.englishcommute.EnglishCommuteApp
import com.example.englishcommute.MainActivity
import com.example.englishcommute.R
import com.example.englishcommute.content.FeedSources
import com.example.englishcommute.diag.DiagLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 把内容同步放到前台服务里跑。
 *
 * **为什么必须是前台服务**：一次更新要下几条音频（每条 5–12 MB），
 * 用户点完按钮就会锁屏揣兜里（通勤场景就是这样），普通后台协程会被系统
 * 直接冻结或杀掉，下到一半的文件虽然能续传，但**没有任何东西会记得再启动它**。
 *
 * ## 2.1.0：这个服务瘦了一大圈
 *
 * 拆掉端侧识别 / 翻译之后，它不再需要管三件事 —— 而这三件当年各自都带了一整套
 * 让位、预算、电量刹车的复杂度：
 *
 * | 拆掉的 | 它当年为什么在这里 |
 * |---|---|
 * | 首次部署（`MODE_DEPLOY`） | 铺 108 MB 翻译模型 + 下 622 MB 识别模型，跑几十分钟，必须前台 |
 * | 转写接力（40 分钟会话预算） | 端侧 2–3 倍实时，一轮跑不完十几条，得一轮轮接着跑 |
 * | 中文对照预取（15 分钟预算 + 一轮 3 条） | 端侧翻译一集要 2–3 分钟，是唯一没后台化的耗时链路 |
 *
 * 现在剩下的是：**下载音频 + 向服务器取字幕**。取一条字幕是几百毫秒的事，
 * 所以「一轮做不完要接力」这个前提也不成立了 —— 接力循环保留，但它现在
 * 只在「这一轮之后又有新的待补」时才转第二圈。
 *
 * 服务本身不含业务逻辑，只负责「保命 + 显示进度」，
 * 真正的流程在 [SyncEngine]，和界面里手动触发走同一条路径。
 */
class ContentSyncService : Service() {

    /**
     * 服务的工作域。
     *
     * ⚠️ 必须允许**重建**（1.2.1 改成 var）。
     *
     * 每轮工作收尾时都会 `scope.cancel()`，而 `stopSelf()` 是异步的 ——
     * 服务实例不保证已经销毁。如果同一个实例再收到一次 `startService`
     * （系统的销毁与重建之间、或厂商 ROM 的复用行为），`scope.launch` 会拿到一个
     * **已取消**的协程：body 一行都不执行，于是「点了没反应」——
     * 而且日志里只有一条 onStartCommand，看不出任何异常。
     */
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var work: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    /**
     * 上一次写进日志的通知阶段（用于节流）。
     *
     * 进度通知是「每块推一次」的（一份 10 MB 音频几十次），全记会把日志淹掉；
     * 而排查时真正想看的是「什么时候从下载切到了取字幕」。所以只在换阶段时记。
     * 置 null 表示「下一条无论是什么都记」—— 每次退出前台的收尾都要留下一笔。
     */
    private var lastNotifyKey: String? = null

    /**
     * 用户在服务正忙的时候点了「更新今日内容」。
     *
     * ## 这是 1.2.1 修的那个 bug（「点了更新没反应」）
     *
     * 当时服务会在**引擎完全空闲**的状态下多占十几分钟（在跑中文对照预取），
     * 于是「更新今日内容」撞上 `work.isActive` 就被丢掉了 ——
     * 现象是「点更新毫无反应，点只检查源却能拉到新内容」，读起来像两个按钮接反了。
     *
     * 2.1.0 之后这个窗口短了很多（没有预取、没有漫长接力），但**判据一条都不能删**：
     * 下载一条 12 MB 的音频在弱网下就是几十秒，「用户此刻点了更新」必须被记住。
     *
     * 用**布尔量**而不是队列：用户在服务忙碌期间点十次，需要的仍然只是
     * 「忙完做一次更新」。
     */
    @Volatile
    private var queuedSync = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 系统重启服务时 intent 是 null。此时最该做的是「把上次没做完的字幕补上」——
        // 拉源 / 下音频下次打开 App 或每日任务都会重来，不需要在这里补
        val mode = intent?.getStringExtra(EXTRA_MODE) ?: MODE_SUBTITLES
        val priority = intent?.getStringExtra(EXTRA_PRIORITY)
        DiagLog.i("Svc", "onStartCommand（flags=$flags startId=$startId mode=$mode）")

        val app = application as EnglishCommuteApp
        val engine = app.container.syncEngine
        val alreadyWorking = work?.isActive == true

        // 必须在 5 秒内进前台，否则 ANR / 被杀。
        //
        // ⚠️ 通知内容要反映**当前真实状态**，不能一律用 initialState()。
        //    「已有任务在跑」那条路径紧接着就会 return，之后再没有任何人更新
        //    这条通知 —— 用 initialState() 就会在通知栏留下一条永远停在
        //    「正在获取今日内容」的僵尸进度条，用户读到的是「点了更新，一直卡在更新」。
        startForegroundCompat(
            if (alreadyWorking) {
                val s = engine.state.value
                val (done, total, indeterminate) = progressOf(s)
                buildNotification(s, done, total, indeterminate, mode)
            } else {
                buildNotification(initialState(mode), 0, 0, true, mode)
            }
        )
        DiagLog.i("Svc", "已进入前台服务 · $mode")

        acquireWakeLock()

        if (alreadyWorking) {
            when {
                mode == MODE_SYNC -> {
                    queuedSync = true
                    DiagLog.i("Svc", "已有任务在跑 → 登记一次完整同步，本轮收尾后立即执行")
                }

                priority != null -> {
                    // 「点播放」触发的取字幕请求会以中途插队的形式被正在跑的那一轮吸收
                    engine.requestPriority(priority)
                    DiagLog.i("Svc", "已有任务在跑，本次请求登记为插队")
                }

                else -> DiagLog.i("Svc", "已有任务在跑，本次启动忽略")
            }
            return START_STICKY
        }

        // 走到这里说明服务是空闲的，本次请求会被正常执行。
        // 别让上一轮残留的待办位在跑完之后再补一次更新 ——
        // 它只可能来自「置位恰好发生在主循环 break 检查之后」这个极窄的窗口，
        // 而那一次的意图就是本次请求本身
        if (mode == MODE_SYNC) queuedSync = false

        // ⚠️ 工作域可能已经在上一轮收尾时被 cancel 掉了（见 [scope] 的说明）。
        //    此时直接 launch 会得到一个已取消的协程 —— body 一行都不执行，
        //    症状与「请求被静默丢弃」一模一样，而且日志里干干净净。
        if (!scope.isActive) {
            DiagLog.w("Svc", "上一轮的工作域已关闭（服务实例被复用）→ 重建")
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        }

        work = scope.launch {
            val notifier = launch {
                engine.state.collect { state -> pushNotification(state, mode) }
            }
            try {
                // ★ 一轮跑完 ≠ 活干完了，两个理由：
                //   ① 完整更新跑完还会有一批「补字幕」（更新期间没轮到的那几条）
                //   ② 用户在服务忙时点过「更新今日内容」（见 [queuedSync]）——
                //      跑完这一轮立刻补做，而不是等到下一次回前台
                var current = mode
                while (true) {
                    if (current == MODE_SYNC) {
                        engine.run()
                        // 完整同步跑完接着补一次字幕（理由见上①）
                        drainSubtitles(engine, priority = null)
                    } else {
                        drainSubtitles(engine, priority)
                    }

                    // 这一轮收尾了。用户在这期间点过更新没有？
                    if (!queuedSync) break
                    queuedSync = false
                    DiagLog.i("Svc", "补做被登记的完整同步")
                    current = MODE_SYNC
                }
            } finally {
                notifier.cancel()
                pushFinalNotification(engine.state.value, mode)
                stopForegroundCompat()
                releaseWakeLock()
                scope.cancel()
                stopSelf()
                DiagLog.i("Svc", "任务收尾，服务已请求停止 · $mode")
            }
        }
        // START_STICKY 而不是 NOT_STICKY：下载要跑几十秒到几分钟，而这期间
        // **屏幕一熄灭、进程就可能被系统回收**。NOT_STICKY 意味着「死了就别再起来」，
        // 于是没下完的内容没有任何东西会在之后接着下完。
        return START_STICKY
    }

    /**
     * 拿一把 PARTIAL_WAKE_LOCK。
     *
     * 前台服务只保证**进程**不被杀，**不保证 CPU 不睡**。
     * 用户点完更新就把屏幕按掉揣兜里（通勤场景就是这样），设备随后进入
     * Doze / suspend，CPU 停转 —— 协程再怎么写也不会前进。
     * 表现是「锁屏十分钟，回来一看进度条一格没动」。
     *
     * ⚠️ 带超时地拿：万一某条路径漏了释放，系统也会在超时后自动收回去。
     *    没有超时的唤醒锁泄漏 = 用户手机一夜不掉电才怪。
     */
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        runCatching {
            val pm = getSystemService(POWER_SERVICE) as? PowerManager ?: return
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_TIMEOUT_MS)
            }
            DiagLog.i("Svc", "已持唤醒锁（CPU 不会随息屏挂起）")
        }.onFailure { DiagLog.w("Svc", "拿唤醒锁失败：${it.message}") }
    }

    private fun releaseWakeLock() {
        val w = wakeLock ?: return
        wakeLock = null
        runCatching {
            if (w.isHeld) w.release()
            DiagLog.i("Svc", "已释放唤醒锁")
        }
    }

    /**
     * 给唤醒锁重新计时。
     *
     * ⚠️ 接力跑多轮时**必须**续期。[acquireWakeLock] 拿的是一次性计时，
     *    超时后系统会自动收回去 —— 而接力可能还在跑。症状是「前几轮正常、
     *    某一轮开始进度不动」，日志里没有任何异常，只能靠对时间戳才发现
     *    唤醒锁的释放时刻早于最后一轮的开始。
     */
    private fun renewWakeLock() {
        val w = wakeLock
        if (w == null) {
            acquireWakeLock()
            return
        }
        runCatching {
            // setReferenceCounted(false) 已经设过，release 之后可以再 acquire
            if (w.isHeld) w.release()
            w.acquire(WAKE_LOCK_TIMEOUT_MS)
        }.onFailure { DiagLog.w("Svc", "续唤醒锁失败：${it.message}") }
    }

    /**
     * 补字幕：把「有音频、没字幕（或没中文）」的条目一轮轮取完。
     *
     * ## 2.1.0 之前的它是另一个东西
     *
     * 它原来是「转写接力」——因为端侧识别是实时的 2–3 倍，一轮 3 条 / 15 分钟
     * 的额度跑不完一天的存量，只好一轮接一轮，还要 40 分钟的会话预算兜底。
     * 现在每次取字幕只是几个 HTTP 请求，所以：
     *
     * - **额度机制整个删掉了**（`AUTO_MAX_EPISODES` / `AUTO_MAX_AUDIO_SEC`）
     * - 会话预算从 40 分钟降到 [SESSION_BUDGET_MS]（防呆，不是常态）
     * - 保留接力循环，但它现在只在「这一轮之后又冒出新待补」时才转第二圈
     *
     * ## 停止条件
     *
     *   · **队列空了** —— 正常完成
     *   · **本轮零进展** —— 有活、但一条都没取成。最可能是服务器连不上
     *     （引擎那边遇到 [SubtitleOutcome.ServerDown] 会中止整轮，
     *     见 `SyncEngine.fetchPendingSubtitles`），继续接力只是把同一件事重放 N 遍
     *   · **超过单次会话预算** —— 见 [SESSION_BUDGET_MS]
     *   · **用户关了后台同步 / 电量低** —— 尊重设置与电池
     *
     * 判据用**文件**（`repository.pendingSubtitleCount`：有音频、缺字幕或中文）
     * 而不是状态位，所以上一轮被系统杀掉留下的半成品天然会被接着做。
     */
    private suspend fun drainSubtitles(engine: SyncEngine, priority: String?) {
        val app = application as? EnglishCommuteApp ?: return
        val deadline = SystemClock.elapsedRealtime() + SESSION_BUDGET_MS
        var nextPriority = priority
        var rounds = 0

        DiagLog.i("Svc", "补字幕接力 ▶")
        while (true) {
            // 用户在服务忙的时候点了「更新今日内容」→ 让位（1.2.1）。
            //
            // 粒度 = 一轮。这比「跑到队列空再让位」短得多，
            // 而那正是「点了更新要等十几分钟」的来源。
            if (queuedSync) {
                DiagLog.i("Svc", "补字幕接力：用户点了更新，让位（已跑 $rounds 轮）")
                return
            }
            rounds++
            // ⚠️ 2.2.0：返回**条数**（原来只有布尔）。判据不变 —— 零进展就收手。
            val progressed = engine.fetchPendingSubtitles(nextPriority) > 0
            // 插队请求只对第一轮有意义：它要的是「接下来做这一条」，
            // 而不是「每一轮都重做这一条」
            nextPriority = null

            val pending = runCatching { app.container.repository.pendingSubtitleCount() }
                .getOrDefault(0)
            if (pending <= 0) {
                DiagLog.i("Svc", "补字幕接力：队列已清空（$rounds 轮）")
                return
            }
            if (!progressed) {
                // 不写成「失败」：最正常的成因是「服务器还没处理这一期」，
                // 下一轮（回前台 / 3 小时后的 Worker）自愈
                DiagLog.w("Svc", "补字幕接力：本轮无进展，停止（仍有 $pending 条待补）")
                return
            }
            if (SystemClock.elapsedRealtime() >= deadline) {
                DiagLog.i("Svc", "补字幕接力：已达单次会话上限，停止（仍有 $pending 条待补）")
                return
            }
            // 尊重用户设置与电池。判据一条都不能省 —— 「后台同步」关掉的意思
            // 就是「别背着我干活」，而低电量时这些电用户自己更需要
            if (!backgroundSyncEnabled(app)) {
                DiagLog.i("Svc", "补字幕接力：用户已关闭后台同步，停止（仍有 $pending 条）")
                return
            }
            if (shouldStopForBattery()) {
                DiagLog.i("Svc", "补字幕接力：电量低，停止（仍有 $pending 条待补）")
                return
            }
            // ⚠️ 续期：服务里没有别的耗时环节了，但接力仍可能跨过唤醒锁的超时点
            renewWakeLock()
        }
    }

    /**
     * 「允许后台干活」总开关（3.5.0 之前叫「后台同步」，语义没变）。
     *
     * 读失败按「开着」处理：用户没表态的时候，不该由我们替他做决定；
     * 而且这个开关默认就是开的（见 [AppSettings.backgroundWorkEnabled]）。
     */
    private suspend fun backgroundSyncEnabled(app: EnglishCommuteApp): Boolean =
        runCatching { app.container.settings.backgroundWorkEnabled.first() }.getOrDefault(true)

    /**
     * 电量太低就别接下去了。
     *
     * 2.1.0 起这条的紧迫性降低了（取字幕耗电可忽略，下载本身也跑不久），
     * 但**判据要留着**：一次更新可能下十几条音频，而弱网下那是几分钟的射频功耗。
     * 阈值 15%：低于它时用户自己更需要那些电。充电中不受限。
     *
     * 取不到电量信息时返回 false（继续跑）—— 缺信息的默认方向应该是
     * 「完成用户的事」，而不是「替他省电」。
     */
    private fun shouldStopForBattery(): Boolean {
        val bm = getSystemService(BATTERY_SERVICE) as? BatteryManager ?: return false
        if (bm.isCharging) return false
        val percent = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return percent in 1 until BATTERY_STOP_PERCENT
    }

    override fun onDestroy() {
        // 「销毁时工作还在跑」是异常信号：说明服务被系统提前回收了，
        // 索引里可能留下 Downloading 残留
        // （由 ContentRepository.reconcileInterruptedState 在下次启动时收拾）
        val stillWorking = work?.isActive == true
        DiagLog.i("Svc", "onDestroy · 工作${if (stillWorking) "仍在进行，将被取消" else "已结束"}")
        // 兜最后一次：服务被系统提前回收时 finally 可能没跑到，
        // 那条 ongoing 的进度通知就会永远留在通知栏（1.2.2 那个僵尸条的镜像场景）
        runCatching { NotificationManagerCompat.from(this).cancel(PROGRESS_NOTIFICATION_ID) }
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------------

    private fun pushNotification(state: SyncState, mode: String) {
        // ★ 终态不进进度通知（1.2.5）。
        //
        // 进度通知是 ongoing 的 —— **用户划不掉**，只能由服务自己撤。
        // 「完成/失败」属于收尾通知（[pushFinalNotification]，autoCancel 可划掉），
        // 那条才是留给用户看的。
        if (!state.running) return
        val (done, total, indeterminate) = progressOf(state)
        notify(buildNotification(state, done, total, indeterminate, mode), phaseKey(state.phase))
    }

    private fun pushFinalNotification(state: SyncState, mode: String) {
        val p = state.phase
        // 什么活都没干的轮次**不发收尾通知**（1.2.5）。
        //
        // 「回到前台」「Worker 每 3 小时」触发的补字幕轮次经常是空转（队列本来就空）。
        // 一条「更新完成」正是从这种空转轮次漏到通知栏的。
        //
        // 用户主动点的那一轮（MODE_SYNC）例外：他点了就应该有回音，
        // 哪怕回音是「今天没有新内容」。
        if (p is SyncPhase.Done && !p.produced && mode != MODE_SYNC) {
            DiagLog.i("Notify", "收尾通知跳过 · 本轮没有任何产出（$mode）")
            return
        }
        val text = when (p) {
            is SyncPhase.Done -> doneSummary(state, mode)
            is SyncPhase.Failed -> p.message
            else -> "已结束"
        }
        val n = NotificationCompat.Builder(this, EnglishCommuteApp.CHANNEL_DOWNLOAD)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle("通勤英语 · ${actionWord(mode)}完成")
            .setContentText(text)
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        runCatching { NotificationManagerCompat.from(this).notify(FINAL_NOTIFICATION_ID, n) }
        DiagLog.i("Notify", "收尾通知 #$FINAL_NOTIFICATION_ID · ${actionWord(mode)}完成 · $text")
    }

    /**
     * 收尾通知的正文：**只报这一轮真正做了什么**。
     *
     * ⚠️ 不要写回「共 N 条可用」（1.2.5 删掉的说法）。那个数字是 `fetched` ——
     * 补字幕的那一轮根本不拉源，它恒为 0，于是每次后台把字幕补齐，
     * 通知栏都会来一句「更新完成 · 共 0 条可用」。这个谎话用户报了三个版本。
     *
     * ⚠️ 「取字幕 N 条」这个词也是 2.1.0 才敢用的：在此之前文案统一写「转写」，
     * 而字幕其实是**从服务器取回来的** —— 说「转写完成」会让用户以为手机上
     * 跑了识别（真机反馈里就出现过这个误解）。现在端侧识别已经不存在了，
     * 「取」是唯一诚实的说法。
     */
    private fun doneSummary(state: SyncState, mode: String): String {
        val p = state.phase as? SyncPhase.Done
        val parts = ArrayList<String>(5)
        if (mode == MODE_SYNC && p != null && p.fetched > 0) parts += "新增 ${p.fetched} 条"
        if (p != null && p.downloaded > 0) parts += "下载 ${p.downloaded} 条"
        // 只报名词，不加动词：这一串是逗号连起来的清单（「新增 2 条，下载 3 条，…」），
        // 中间插一个「加载字幕 9 条」会让它读起来像两个不同的动作
        if (p != null && p.subtitled > 0) parts += "字幕 ${p.subtitled} 条"
        if (p != null && p.pendingSubs > 0) parts += "${p.pendingSubs} 条字幕未到"
        if (p != null && p.expired > 0) parts += "清理 ${p.expired} 条"
        if (parts.isNotEmpty()) return parts.joinToString("，")
        val failedSources = state.sourceResults.count { it.error != null }
        return when {
            failedSources > 0 -> "$failedSources 个来源没连上，今天没取到新内容"
            mode == MODE_SYNC -> "今天没有新内容"
            else -> "字幕都已加载"
        }
    }

    /**
     * 这一轮在做什么 —— 决定通知的**措辞**。
     *
     * 「更新」有明确的用户意图（他点了按钮），而「字幕补齐」是后台自己补的活。
     * 两者用同一个词，就会出现「我只点过一次更新，怎么一直在更新」的困惑。
     */
    private fun actionWord(mode: String): String = when (mode) {
        MODE_SYNC -> "更新"
        // 「加载字幕」而不是「字幕补齐/取字幕」：「补」暗示之前缺了（用户并不知道
        // 缺过什么），「取」是工程师视角（从哪儿取）。「加载」是界面里的通用词，
        // 而且它与 App 内其他几处用同一个词 —— 同一个动作在通知栏和列表里
        // 叫两个名字，用户会以为是两件事。
        else -> "加载字幕"
    }

    /**
     * 阶段标识，两个用途：日志里一眼看出「走到哪一步了」，以及**给日志节流**。
     *
     * 下载进度是每块推一次的（一份 10 MB 的音频几十次），全记进日志会把
     * 真正有用的上下文淹掉。同一阶段内只记第一条。
     */
    private fun phaseKey(p: SyncPhase): String = when (p) {
        is SyncPhase.Downloading -> "下载 ${p.index}/${p.total}"
        is SyncPhase.FetchingSubs -> "加载字幕 ${p.index}/${p.total}"
        is SyncPhase.FetchingFeeds -> "拉源"
        is SyncPhase.Cleaning -> "清理"
        is SyncPhase.SourceChecked -> "来源检查"
        is SyncPhase.Done -> "完成"
        is SyncPhase.Failed -> "失败 ${p.message.take(30)}"
        SyncPhase.Idle -> "空闲"
    }

    /**
     * 刚开始那一帧的通知内容。
     *
     * ⚠️ 必须按模式分岔：补字幕那一轮**不联网取内容**，
     * 给它写「拉取 11 个源…」是假话（1.2.5 修过一次同类问题）。
     */
    private fun initialState(mode: String): SyncState = SyncState(
        running = true,
        phase = if (mode == MODE_SYNC) SyncPhase.FetchingFeeds else SyncPhase.Cleaning,
    )

    private fun buildNotification(
        state: SyncState,
        done: Int,
        total: Int,
        indeterminate: Boolean,
        mode: String,
    ): Notification {
        val title: String
        val text: String
        when (val p = state.phase) {
            is SyncPhase.FetchingFeeds -> {
                title = "${actionWord(mode)}中"
                // 写死数字会随着加源悄悄过期（上一版就停在了「4 个源」）
                text = "正在获取今天的内容…"
            }

            is SyncPhase.Downloading -> {
                title = "下载音频 ${p.index}/${p.total}"
                text = "${p.title} · ${(p.fraction * 100).toInt()}%"
            }

            is SyncPhase.FetchingSubs -> {
                title = "加载字幕 ${p.index}/${p.total}"
                // 「从服务器取回，不用等」两句都是在解释**我们怎么做的**，
                // 而通知栏那点位置应该留给用户此刻想确认的东西：是哪一条。
                text = p.title
            }

            is SyncPhase.Cleaning -> {
                title = "清理旧内容"
                text = "清理最早的内容，腾出空间"
            }

            is SyncPhase.Done -> {
                title = "${actionWord(mode)}完成"
                text = doneSummary(state, mode)
            }

            is SyncPhase.Failed -> {
                title = "${actionWord(mode)}失败"
                text = p.message
            }

            is SyncPhase.SourceChecked -> {
                title = if (p.failed == 0) "来源检查通过" else "来源检查：${p.failed} 个失败"
                text = "${p.ok}/${p.ok + p.failed} 个来源可用，共 ${p.totalItems} 条内容"
            }

            SyncPhase.Idle -> {
                title = "通勤英语"
                text = "准备中…"
            }
        }

        return NotificationCompat.Builder(this, EnglishCommuteApp.CHANNEL_DOWNLOAD)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setProgress(100, if (total > 0) done * 100 / total else 0, indeterminate)
            .build()
    }

    /** 下载有真实进度；取字幕是几百毫秒一条，只报「第几条」 */
    private fun progressOf(state: SyncState): Triple<Int, Int, Boolean> = when (val p = state.phase) {
        is SyncPhase.Downloading -> Triple(
            (p.index - 1) * 100 + (p.fraction * 100).toInt(),
            p.total * 100,
            false,
        )

        is SyncPhase.FetchingSubs -> Triple(
            (p.index - 1) * 100,
            p.total * 100,
            false,
        )

        is SyncPhase.FetchingFeeds -> Triple(0, 0, true)
        is SyncPhase.Cleaning -> Triple(0, 0, true)
        else -> Triple(0, 0, false)
    }

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * 推一条进度通知。
     *
     * [key] 是**日志节流用的阶段标识**（见 [phaseKey]）：同一阶段内的进度刷新
     * （下载是每块一次、一份音频几十次）不再重复记，否则日志会被进度数字淹掉，
     * 而真正想看的是「什么时候从下载切到取字幕了」。
     */
    private fun notify(n: Notification, key: String) {
        runCatching { NotificationManagerCompat.from(this).notify(PROGRESS_NOTIFICATION_ID, n) }
        if (key != lastNotifyKey) {
            lastNotifyKey = key
            DiagLog.i("Notify", "进度通知 #$PROGRESS_NOTIFICATION_ID · $key")
        }
    }

    private fun startForegroundCompat(n: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(PROGRESS_NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(PROGRESS_NOTIFICATION_ID, n)
        }
    }

    /**
     * 退出前台。**必须把那条进度通知撤掉**（1.2.2 修）。
     *
     * ## 这是一个真实的僵尸通知 bug
     *
     * 进前台时那条通知带 `.setOngoing(true)` —— 用户**划不掉**。
     * 而原来的写法是 `STOP_FOREGROUND_DETACH`，它的语义恰好是
     * 「退出前台、但把通知留在通知栏」。两件事一叠，服务跑完之后
     * 通知栏里就永远挂着最后那一帧：
     *
     * ```
     * 通勤英语
     * 更新完成
     * 共 0 条可用          ← 来自补转写收尾时推的 Done(fetched = 0)
     * ```
     *
     * 而且它**关掉 App 重开也不会消失** —— 通知属于系统通知栏，
     * 服务不主动撤它，谁也没办法。用户的手也划不动它。
     *
     * 收尾那条通知是另一个 ID（[FINAL_NOTIFICATION_ID]），
     * 带 autoCancel、可以正常划掉，不受这次改动影响。
     */
    private fun stopForegroundCompat() {
        // minSdk 26 > N(24)，所以不需要版本判断
        stopForeground(STOP_FOREGROUND_REMOVE)
        // 双保险：个别 ROM 在服务被系统提前回收时会吞掉上面那次调用
        runCatching { NotificationManagerCompat.from(this).cancel(PROGRESS_NOTIFICATION_ID) }
        lastNotifyKey = null
        DiagLog.i("Notify", "撤掉进度通知 #$PROGRESS_NOTIFICATION_ID（退出前台）")
    }

    companion object {
        /**
         * 进度通知 ID。
         *
         * ## ⚠️ 它与播放通知**曾经都是 1001**（1.2.5 修的真 bug）
         *
         * 通知的唯一标识是「包名 + tag + id」—— **id 相同就是同一条通知**。
         * 而 media3 的 `DefaultMediaNotificationProvider` 默认值正好是 1001。
         * 后果是一串看似无关的症状：正在听的时候一更新，播放控件就被顶掉；
         * 同步收尾的 cancel 顺手把播放通知也撤了。
         *
         * 现在三个 ID 都定义在 [EnglishCommuteApp] 里（互不相同），
         * 并且 `tools/verify-library-ux.py` 断言它们两两不等 —— 加新通知时
         * 再撞上会在构建期被拦下，而不是等用户报「播放控件没了」。
         */
        private const val PROGRESS_NOTIFICATION_ID = EnglishCommuteApp.NOTIFY_ID_SYNC_PROGRESS

        /** 收尾通知用**另一个** ID（它才该留在通知栏，且带 autoCancel 能划掉） */
        private const val FINAL_NOTIFICATION_ID = EnglishCommuteApp.NOTIFY_ID_SYNC_FINAL

        private const val WAKE_LOCK_TAG = "EnglishCommute:sync"

        /** 唤醒锁的兜底超时：任何路径漏了释放，系统也会自己收回去 */
        private const val WAKE_LOCK_TIMEOUT_MS = 30 * 60 * 1000L

        /**
         * 一次「补字幕会话」的最长时间。**防呆，不是常态。**
         *
         * 2.1.0 之前这里是 40 分钟 —— 那是给端侧识别留的（2–3 倍实时，
         * 一天十几条要跑二十多分钟）。现在每条字幕是几百毫秒的 HTTP 请求，
         * 十几条也就十几秒，所以这个预算只有一种情况下会用满：
         * **服务器慢或在反复超时**。那种情况下继续接力没有意义。
         *
         * 之所以不删掉它：没有上限的循环 + 前台服务 = 「App 在后台无限占用 CPU」，
         * 而这类 bug 一旦发生，日志里看不出任何异常。
         */
        private const val SESSION_BUDGET_MS = 5 * 60 * 1000L

        /**
         * 低于这个电量就不再接力。
         *
         * 只影响**后续轮次**：正在跑的那一轮不会被打断。
         */
        private const val BATTERY_STOP_PERCENT = 15

        /** 拉源 + 下音频 + 取字幕（用户点「一键更新」） */
        const val MODE_SYNC = "sync"

        /**
         * 只补字幕，不拉源、不下音频。
         *
         * 单独一个模式的意义：字幕的输入（服务器目录 + 音频）要么已在本地、
         * 要么只需要几个小请求，所以它**不会因为某个源挂了而连带失败**。
         * 把它和完整更新绑在一起，等于让「有没有字幕」这件事受制于
         * 「十一个源今天是不是都通」—— 那是完全无关的两个问题。
         */
        const val MODE_SUBTITLES = "subtitles"

        private const val EXTRA_MODE = "mode"
        private const val EXTRA_PRIORITY = "priority"

        /**
         * 撤掉可能残留的进度通知。
         *
         * 1.2.2 之前装的版本会留下一条划不掉的「更新完成」常驻通知
         * （成因见 [stopForegroundCompat]）。而通知属于**系统通知栏** ——
         * App 重开不会自动清，用户自己也划不动，只有显式 cancel 才能让它消失。
         *
         * 所以新版每次冷启兜一次：升级上来的人不用再被那条通知恶心到。
         * 幂等，没有残留时什么都不做。
         */
        fun clearStaleNotification(context: Context) {
            runCatching {
                NotificationManagerCompat.from(context).cancel(PROGRESS_NOTIFICATION_ID)
            }
            DiagLog.i("Notify", "冷启兜底：撤掉可能残留的进度通知 #$PROGRESS_NOTIFICATION_ID")
        }

        /**
         * 启动完整同步。**必须用 startForegroundService** —— Android 8 之后
         * 直接 `startService` 会在应用退到后台时抛 IllegalStateException。
         */
        fun start(context: Context) {
            val intent = Intent(context, ContentSyncService::class.java)
                .putExtra(EXTRA_MODE, MODE_SYNC)
            ContextCompat.startForegroundService(context, intent)
        }

        /**
         * 只补字幕。
         *
         * @param priorityId 用户此刻就要的那一条（正在播的 / 刚点「补字幕」的）。
         *   传 null 表示「把队列里最该补的几条顺手做掉」——
         *   回到前台时用这个，让「打开 App」本身就成为一次触发。
         */
        fun startSubtitles(context: Context, priorityId: String? = null) {
            val intent = Intent(context, ContentSyncService::class.java)
                .putExtra(EXTRA_MODE, MODE_SUBTITLES)
                .putExtra(EXTRA_PRIORITY, priorityId)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
