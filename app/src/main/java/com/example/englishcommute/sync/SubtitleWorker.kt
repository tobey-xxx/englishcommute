package com.example.englishcommute.sync

import android.content.Context
import android.os.SystemClock
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.englishcommute.EnglishCommuteApp
import com.example.englishcommute.diag.DiagLog
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/**
 * 后台补字幕。**这是「字幕经常不来」的正面修复。**
 *
 * ## 为什么它和「每日更新」不是一个 Worker
 *
 * （那条 24 小时的任务 3.5.0 已经拆掉了，见 `RetiredWork`。这一段留着，
 * 因为它解释的是**这个 Worker 为什么长成现在这样** ——
 * 而这个理由今天依然成立。）
 *
 * 它的约束是 `UNMETERED + 电量不低 + 24 小时周期` —— 那是**下载音频**该有的约束
 * （一集 mp3 有 5–12 MB，默认烧用户流量不可接受）。但**加载字幕几乎不花流量**：
 * 一份 SRT 加一份译文 JSON 合计几十 KB。
 *
 * 把两件事绑在同一个 Worker 上，等于让「字幕」白等一个「等 WiFi」的条件：
 *
 *   · 早上 6 点在地铁上用流量 → `UNMETERED` 不满足 → 整个 Worker 不跑，字幕也陪葬
 *   · 白天不打开 App → 前台的自动加载也不跑
 *
 * 于是哪怕音频**早就下好了**，字幕也一直不来。用户看到的就是
 * 「字幕经常不来」。这里把它拆出来。
 *
 * ## 2.1.0 换了个身份
 *
 * 它原来是 `AutoTranscribeWorker`：**端侧转写**的补做。
 *   · 约束里带 `requiresBatteryNotLow` —— 630 MB 模型常驻 + 多核推理，很吃电
 *   · 周期 3 小时 —— 一轮转写要几分钟，太频繁会一直唤醒 CPU
 *   · 一轮要「接力跑 8 分钟」—— 端侧 2–3 倍实时，一轮额度做不完
 *
 * 现在它做的是**向服务器取字幕**：一条几百毫秒、十几条也就十几秒。
 * 所以三样一起改：周期 **3 小时 → 1 小时**（成本可忽略，而 NPR News Now
 * 是每小时一集，这个频率让最新一期的字幕来得很及时）、
 * 去掉电量约束（一条 HTTP 请求不需要为此被挡）、不再需要接力窗口。
 *
 * ⚠️ **类名换了，所以 [SubtitleScheduler] 必须顺手撤掉旧名字的周期任务** ——
 *    否则升级上来的人机器里会留一条指向已删除类的任务，每 3 小时失败一次，
 *    而用户和我们都看不到任何提示。详见那里的说明。
 *
 * ## 真机验证时别误判
 *
 * 系统在 Doze 里会把这些唤醒**攒到一起延迟执行**，所以「没准点跑」是预期行为。
 * 判断它有没有生效看日志里的 `Worker` 标签，判据是：
 *   `后台补字幕 ▶ 待补 N 条`
 *
 * ⚠️ 本 Worker **直接调引擎，不经过 [ContentSyncService]**。
 *    从后台 `startForegroundService()` 在 Android 12+ 会抛
 *    `ForegroundServiceStartNotAllowedException`（后台启动前台服务的限制），
 *    而 WorkManager 本身已经替我们持有唤醒锁，不需要再套一层前台服务。
 */
class SubtitleWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? EnglishCommuteApp ?: return Result.failure()
        val engine = app.container.syncEngine

        // 前台正好在跑（用户正在看）就让它跑完 —— 两处抢同一把引擎锁只会互相打断，
        // 而前台那一轮有唤醒锁、有通知、有进度条，优先级本来就应该更高
        if (engine.isRunning) {
            DiagLog.i("Worker", "后台补字幕：前台正在跑，本轮跳过")
            return Result.success()
        }

        // 判据是**文件**（有音频、字幕没齐），不是状态位 ——
        // 上次被杀留下的半成品天然会被重新捡起来，不需要用户做任何事。
        //
        // ⚠️ 2.1.0 之前这里用的是「**可自动转写**」的条数，而不是待办总数（1.2.4）。
        //    那个区分来自端侧的限制：超过 30 分钟的音频永远不会被自动转写，
        //    把它们算进来的话，这个 Worker 会每 3 小时醒一次、发现「有活」、
        //    跑一轮、什么都没做成 —— 而在 Doze 下为了这一次空跑，
        //    系统会把它从深睡里拉出来。
        //    现在服务器对每一期都转写，长的短的都一样，所以那个区分连同
        //    `MAX_AUTO_TRANSCRIBE_SEC` 一起删了。
        val pending = runCatching { app.container.repository.pendingSubtitleCount() }
            .getOrDefault(0)
        if (pending <= 0) {
            DiagLog.i("Worker", "后台补字幕：没有待补的内容")
            return Result.success()
        }

        DiagLog.i("Worker", "后台补字幕 ▶ 待补 $pending 条（不要求 WiFi）")
        return try {
            // 在 Worker 的执行窗口内**接力跑到没活为止**，而不是只跑一轮。
            //
            // 2.1.0 之后这个循环通常只转一圈（十几条字幕十几秒就跑完了），
            // 留着它是为了两种边界：待补很多（攒了一周没开 App）、
            // 或者服务器慢（每条都要等几秒超时）。
            //
            // 与 [ContentSyncService] 用**同一个判据**（本轮是否有进展）：
            // 零进展 = 有活但一条都没补上（最可能是服务器连不上），
            // 继续循环只是空转刷日志。
            val deadline = SystemClock.elapsedRealtime() + WORK_BUDGET_MS
            var rounds = 0
            while (true) {
                rounds++
                // ⚠️ 判据是「本轮补了几条」。2.2.0 起它返回**条数**而不是布尔 ——
                //    「一条都没补上」才是停手的理由，而 0 与 false 语义相同，
                //    写成 `== 0` 是因为 Int 上没有 `!`。
                if (engine.fetchPendingSubtitles() == 0) break
                val left = runCatching { app.container.repository.pendingSubtitleCount() }
                    .getOrDefault(0)
                if (left <= 0) break
                if (SystemClock.elapsedRealtime() >= deadline) {
                    DiagLog.i("Worker", "后台补字幕：已达执行窗口上限（仍有 $left 条）")
                    break
                }
            }
            DiagLog.i("Worker", "后台补字幕 ◀（$rounds 轮）")
            Result.success()
        } catch (e: CancellationException) {
            // 系统收回时间片（Doze / 用户划掉）时必须原样抛出，
            // 否则协程会被当成「正常跑完」，而 `finally` 里的收尾逻辑会以为已完成。
            DiagLog.w("Worker", "后台补字幕被系统中断")
            throw e
        } catch (t: Throwable) {
            // 这里**必须 catch Throwable**。它跑在用户看不见的地方，
            // 而 Error 不是 Exception 的子类 —— 漏出去就是**整个进程死**，
            // 用户侧看到的是一次没有任何前因的闪退，日志里也没有现场。
            // 对「后台任务」来说，这个代价完全不可接受：宁可这一轮跳过。
            DiagLog.e("Worker", "后台补字幕异常（已降级为「本轮跳过」）", t)
            if (runAttemptCount < 2) Result.retry() else Result.success()
        }
    }

    private companion object {
        /**
         * Worker 内部接力跑多久。
         *
         * WorkManager 对 CoroutineWorker 的执行窗口是 10 分钟，超时会被
         * `stopWork` —— 留 2 分钟给收尾与调度开销。
         *
         * ⚠️ 2.1.0 之前这个值是 8 分钟（端侧转写一轮跑不完，得在窗口里多跑几轮）。
         *    现在取字幕一圈十几秒，正常永远碰不到这个上限 —— 它只在
         *    「服务器慢 / 待补特别多」时起作用，是一道防呆。
         */
        const val WORK_BUDGET_MS = 8 * 60 * 1000L
    }
}

/**
 * 登记 / 取消后台补字幕。
 *
 * ## ⚠️ 2.1.0：类名换了，所以这里必须**顺手撤掉旧名字**
 *
 * 上一版的周期任务叫 `auto_transcribe`，指向的类是 `AutoTranscribeWorker` ——
 * 那个类已经删了。而 WorkManager 的周期任务**存在系统里**，不会因为
 * 我们改了代码就消失。
 *
 * 如果只按新名字登记，升级上来的机器里会**同时**有两条：
 *   · 旧的那条：每 3 小时被系统唤醒一次，试图实例化一个不存在的类 → 失败
 *   · 新的那条：正常工作
 *
 * 症状是「没有任何症状」：用户看不到，日志里也不会出现我们认识的行 ——
 * 只有电池统计里多出一些说不清的唤醒。所以 [LEGACY_NAME] 必须每次都被撤掉。
 * 幂等，没有残留时什么都不做（在全新安装上就是一次空调用）。
 */
object SubtitleScheduler {

    private const val UNIQUE_NAME = "subtitles"

    /** 2.1.0 之前的名字。**永远不要删掉这一行** —— 见上面的说明 */
    private const val LEGACY_NAME = "auto_transcribe"

    /**
     * 1 小时。
     *
     * 2.1.0 之前是 3 小时，当时的理由是「每 15 分钟唤醒一次 CPU 去跑几分钟的
     * 端侧识别是耗电上的自杀」。现在一轮工作是十几个 HTTP 请求、
     * 一两秒就结束 —— 那条理由不存在了。
     *
     * 选 1 小时而不是 15 分钟（WorkManager 的最小值）：NPR News Now 是
     * **每小时一集**，而服务器也是每小时至少跑 4 轮，所以这个频率已经足以
     * 让「最新一期的字幕」在用户下次打开 App 之前就位。
     * 再密就只是徒增唤醒次数。
     */
    private const val INTERVAL_HOURS = 1L

    /**
     * 登记 / 取消。
     *
     * ⚠️ 用 [ExistingPeriodicWorkPolicy.KEEP] 而**不是** `UPDATE`，原因很关键：
     *
     * 本方法在**每次进程启动**时都会被调用（见 `EnglishCommuteApp`）。如果用 `UPDATE`，
     * 每打开一次 App 就会把周期计时器重置一次 —— 天天开 App 的用户，
     * 这个任务可能**永远不会到点**，那就白加了。
     *
     * （3.5.0 之前这里写着「和 `DailySyncScheduler` 相反」：那个用 `UPDATE`，
     *  因为它**不是**每次启动都登记、只在用户拨开关时登记。那条任务已经拆掉了，
     *  但这条对照留下来有用 —— 它说明 `UPDATE` / `KEEP` 的选择取决于
     *  **登记的时机**，不取决于任务本身。）
     *
     * 代价是改了约束不会自动生效。**调整约束时必须先
     * `WorkManager.getInstance(ctx).cancelUniqueWork(UNIQUE_NAME)` 再登记。**
     * （2.1.0 换约束时正是这么做的：名字也换了，所以走的是「撤旧的 + 登新的」。）
     */
    fun apply(context: Context, enabled: Boolean) {
        val wm = WorkManager.getInstance(context)
        // 先撤旧名字（原因见类注释）。**无论 enabled 是什么都要撤** ——
        // 用户关掉后台同步时，旧任务更不能留在系统里
        wm.cancelUniqueWork(LEGACY_NAME)
        if (!enabled) {
            wm.cancelUniqueWork(UNIQUE_NAME)
            return
        }

        val request = PeriodicWorkRequestBuilder<SubtitleWorker>(
            INTERVAL_HOURS,
            TimeUnit.HOURS,
        ).setConstraints(
            Constraints.Builder()
                // ★ 本任务的核心价值就在这一行：不要求任何网络类型。
                //   默认值本来也是 NOT_REQUIRED，但显式写出来是为了让
                //   「为什么它和「下载音频」那种任务不一样」在代码里自解释 ——
                //   后人很容易「顺手统一」成 UNMETERED，那就把修复退回去了。
                //
                //   ⚠️ 2.1.0 把 `setRequiresBatteryNotLow(true)` 也删了：
                //      那条约束是给 630 MB 常驻 + 多核推理设的，现在一轮工作
                //      是十几个小请求 —— 用「电量低」挡掉它，只会让用户在
                //      低电量时连字幕都看不上，而省不下什么。
                .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                .build()
        ).build()

        wm.enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }
}
