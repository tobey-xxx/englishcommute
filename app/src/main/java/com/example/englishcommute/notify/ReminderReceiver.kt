package com.example.englishcommute.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.englishcommute.EnglishCommuteApp
import com.example.englishcommute.diag.DiagLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 23:30 到了。
 *
 * ## 两件事，顺序不能反
 *
 * ① **先看今天达没达标**（达标就不打扰，见 [DailyReminder.notifyNow]）；
 * ② ★ **再排下一次** —— `setExactAndAllowWhileIdle` 是**一次性**的闹钟，
 *    不重排就再也没有明天了。
 *
 * ⚠️ 用 `goAsync()` + 协程：读设置是挂起操作，而 `onReceive` 一返回
 *    系统就可能回收进程。`finish()` 必须在 finally 里 ——
 *    漏掉它，系统会记一次「广播超时」，而那种错误在日志里很难认出来。
 */
class ReminderReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_FIRE = "com.example.englishcommute.action.DAILY_REMIND"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FIRE) return
        val app = context.applicationContext as? EnglishCommuteApp ?: return
        val pending = goAsync()
        scope.launch {
            try {
                val enabled = app.container.settings.reminderEnabled.first()
                if (!enabled) {
                    DiagLog.i("Reminder", "到点了但开关是关的（可能是刚关掉、闹钟还没撤）")
                    return@launch
                }
                DailyReminder.notifyNow(context)
                // ★ 一次性闹钟：响完必须重排，否则明天不会响
                DailyReminder.scheduleNext(context)
            } catch (e: Exception) {
                DiagLog.w("Reminder", "处理提醒时异常：${e.javaClass.simpleName}")
            } finally {
                pending.finish()
            }
        }
    }
}

/**
 * 开机 / 应用更新之后**重新排一次**闹钟。
 *
 * ## 为什么这两个都要接
 *
 * - **开机**：闹钟在重启后全部丢失（系统不保存），这是 Android 的规定；
 * - **应用更新**：安装新版本会把本应用的闹钟一起清掉。
 *
 * ⚠️ 少了后者，**每次装新版本之后提醒就悄悄消失了**，直到用户想起
 *    打开一次 App —— 而这正是「静默消失」里最难发现的一种
 *    （3.5.0 拆那条 WorkManager 任务时确认过同一个坑）。
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private const val TAG = "Reminder"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }
        val app = context.applicationContext as? EnglishCommuteApp ?: return
        val pending = goAsync()
        scope.launch {
            try {
                val enabled = app.container.settings.reminderEnabled.first()
                DailyReminder.sync(context, enabled)
                DiagLog.i(TAG, "启动事件（$action）之后重新对齐了每日提醒：$enabled")
            } catch (e: Exception) {
                DiagLog.w(TAG, "启动后对齐提醒失败：${e.javaClass.simpleName}")
            } finally {
                pending.finish()
            }
        }
    }
}
