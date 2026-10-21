package com.example.englishcommute.sync

import android.content.Context
import androidx.work.WorkManager
import com.example.englishcommute.diag.DiagLog
import kotlinx.coroutines.flow.first

/**
 * **已经拆掉的后台任务** —— 每次进程启动都要把它们从系统里撤掉。
 *
 * ## 为什么删了代码还要留在代码里
 *
 * WorkManager 的任务**活在系统的库里，不在我们的代码里**。用户机器上那条
 * 「每天唤醒一次」的记录是上一版登记进去的，它不会因为我们删了一个 `.kt`
 * 文件就消失。于是升级之后会变成：
 *
 * ```
 * 系统按点唤醒 → 试图实例化**已拆掉的** DailySyncWorker → 类不存在 → 失败
 * ```
 *
 * ⚠️ 症状是**「没有任何症状」**：用户看不到，我们的日志里也不会出现任何
 * 认识的行 —— 只有电池统计里多出一些说不清的唤醒。
 * 这一类「拆完还留着一只手」的形态，本工程在 [SubtitleScheduler] 的
 * `LEGACY_NAME` 上已经写过一次：**改了名字或拆了功能，旧任务都必须每次
 * 启动时被撤掉**。幂等，全新安装上就是几次空查询。
 *
 * ## 为什么先查再撤，而不是无脑撤
 *
 * `cancelUniqueWork` 是幂等的、无脑撤也不会出错。区别在**留痕**：
 *
 * | 做法 | 后果 |
 * |---|---|
 * | 每次启动都撤 + 打一行「已撤销」 | 那行日志**永远出现** ⇒ 它证明不了任何事，还会把真信号淹掉 |
 * | 先问「它在不在」，真有残留才撤 + 留痕 | 这行日志一出现，就等于「**这台机器是升级上来的，而且那一刻确实还挂着一条**」 |
 *
 * ★ 判据落在**事实**上：日志只在真的撤掉了东西时出现。
 *
 * ## 查不到的时候也撤
 *
 * 查询本身失败（库损坏、系统抽风）时**照样撤**：撤一个不存在的东西是无害的，
 * 而漏撤会留下一条永远失败的周期任务。缺信息时的默认方向应该是「把事做完」，
 * 这也是 [`com.example.englishcommute.sync.ContentSyncService`] 判电量时的同一个口径。
 */
object RetiredWork {

    /**
     * 3.5.0 拆掉的「每日自动更新」：24 小时周期、`UNMETERED + 电量不低` 约束，
     * 一跑就是完整的一轮 `SyncEngine.run()`（含下载音频）。
     *
     * 拆掉的理由：它只在「App 长时间待在后台」时才可能被系统唤醒，
     * 而这个 App 的用户不会那样用 —— 于是它实际上几乎从不执行，
     * 却在每次登记时多要一条开机自启权限、多留一个说不清的唤醒来源。
     * 而「打开 App 时自动更新」这条路径一直都在（见 `kickForegroundRefresh`），
     * 它才是用户真正感知到的那一次。
     *
     * ⚠️ **永远不要删掉这一行**：它是唯一能让老机器上那条任务被撤掉的东西。
     */
    private const val DAILY_CONTENT_SYNC = "daily_content_sync"

    private val NAMES = listOf(DAILY_CONTENT_SYNC)

    suspend fun cancelAll(context: Context) {
        val wm = WorkManager.getInstance(context)
        for (name in NAMES) {
            val present = runCatching {
                wm.getWorkInfosForUniqueWorkFlow(name).first()
            }.getOrNull()

            // 查得到、而且是空的 → 干净，什么都不用做（全新安装走的就是这一支）
            if (present != null && present.isEmpty()) continue

            wm.cancelUniqueWork(name)
            if (present != null) {
                // ⚠️ 这一行**只在真有残留时**出现，所以它是可判断的证据，
                //    不是每次启动都刷一遍的背景噪音
                DiagLog.i("Work", "撤掉已退役的后台任务：$name（${present.size} 条）")
            } else {
                // 查不出来（库异常）也撤了，但**不敢报「撤掉了 N 条」** ——
                // 那是我们没验证过的话
                DiagLog.w("Work", "撤掉已退役的后台任务：$name（查询失败，按有残留处理）")
            }
        }
    }
}
