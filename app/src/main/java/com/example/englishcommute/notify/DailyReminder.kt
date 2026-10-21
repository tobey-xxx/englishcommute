package com.example.englishcommute.notify

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.englishcommute.MainActivity
import com.example.englishcommute.R
import com.example.englishcommute.checkin.CheckInStore
import com.example.englishcommute.checkin.DayKey
import com.example.englishcommute.diag.DiagLog

/**
 * 每晚 23:30 的「别忘了听英语」提醒（4.2.0）。
 *
 * ## ★ 为什么这**不是**推送，也不需要推送
 *
 * 用户的判断是对的：**FCM（安卓自带的推送）在中国大陆基本不可用** ——
 * 大陆没有 GMS，进程被杀之后没有任何长连接能收到消息。
 *
 * 但「每天固定时刻提醒我」这件事，**本来就不需要推送**：
 * 它不需要服务器算任何东西，也不需要「即使从没打开过 App 也能收到」。
 * 本地闹钟（[AlarmManager]）就能做到，而且**比推送更可靠**：
 *
 * | | 本地闹钟（本文件） | 厂商推送（小米/OPPO/vivo/荣耀） |
 * | --- | --- | --- |
 * | 进程被杀 | ✅ 照常触发（系统按时刻拉起 receiver） | ✅ 系统长连接投递 |
 * | 飞行模式 / 断网 | ✅ 照常 | ❌ 收不到 |
 * | 需要服务器 | ❌ 不需要 | ✅ 需要（四家各一套鉴权 + 接口） |
 * | 需要企业资质 | ❌ 不需要 | ✅ **四家都要企业开发者认证** |
 * | 需要上架应用商店 | ❌ 不需要 | ✅ 小米 / OPPO / vivo 都要求（荣耀不要求上架，但要企业号） |
 * | 包体增加 | 0 | 约 4~6 MB（四个 SDK） |
 * | 用户能自己关掉 | ✅ 系统通知设置里就能关 | ✅ 同上 |
 *
 * 详细的四家接口调研见 `推送与每日提醒-技术调研.md`。
 *
 * ⚠️ **两种情况下它也会失效，而这两种情况推送同样救不了**：
 * ① 用户在系统设置里「强行停止」这个 App —— Android 会清掉它的所有闹钟，
 *    直到用户手动再打开一次（这是 Android 的规定，任何 App 都绕不过）；
 * ② 国产 ROM 的「后台限制 / 省电策略」把它冻住了。各家设置项名字不同
 *    （小米：自启动 + 省电策略「无限制」；OPPO：允许后台运行；
 *    vivo：后台高耗电；荣耀：应用启动管理），**用户手动放行之后才稳**。
 *    这也是为什么这个提醒不追求「100% 精确到秒」。
 */
object DailyReminder {

    /** 提醒时刻：每晚 23:30（本地时区） */
    const val HOUR = 23
    const val MINUTE = 30

    private const val CHANNEL = "reminder"
    private const val REQUEST_CODE = 4301
    private const val NOTIFICATION_ID = 4301

    /** 建通知频道。**必须幂等** —— 每次 App 启动都会调 */
    fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "每日提醒", NotificationManager.IMPORTANCE_DEFAULT).apply {
                // 提醒类：要能响一声、但不用横幅砸脸。用户关掉也不影响任何功能
                description = "每晚提醒你还没听的英语"
                setShowBadge(true)
            },
        )
    }

    /**
     * 按开关状态对齐闹钟。
     *
     * ⚠️ 每次 App 启动都要调一次：闹钟在「清除数据 / 强行停止 / 系统清理」之后
     *    会被丢掉，而用户感知不到（3.5.0 那条 WorkManager 的后台任务就是
     *    死在这里）。`setExactAndAllowWhileIdle` 是**一次性**的，
     *    所以 receiver 里每响一次都要重新排下一次 —— 见 [ReminderReceiver]。
     */
    fun sync(ctx: Context, enabled: Boolean) {
        if (!enabled) {
            cancel(ctx)
            return
        }
        ensureChannel(ctx)
        scheduleNext(ctx)
    }

    fun cancel(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        am.cancel(pendingIntent(ctx))
        DiagLog.i("Reminder", "每日提醒已关闭（闹钟撤掉）")
    }

    /** 排下一次 23:30 */
    /**
     * 排下一次 23:30。返回**真的用上了哪种策略**（`failed` = 全都没成）。
     *
     * ## ★★ 4.2.1 修了两个「静默失败」，它们是 4.2.0 没响的直接嫌疑
     *
     * ① **精确那条失败时没有退路**：`setExactAndAllowWhileIdle` 抛异常
     *    （厂商限制、权限被撤、系统内部错误）时，4.2.0 只记一行就当结束了 ——
     *    闹钟**根本没排上**，而调用方以为排好了。
     * ② ★ **「下次提醒 …」那行日志在成功之前就打**：`runCatching{...}` 失败
     *    之后那行 `DiagLog.i("下次提醒 …")` 照样输出 ⇒ **日志在撒谎**。
     *    用户（和我）看到那行会以为「闹钟排上了」，于是把问题往别处找。
     *
     * 现在的顺序（可靠性从高到低，每种都试）：
     * 1. `setAlarmClock` —— 唯一**不受 Doze 推迟**的接口，也是厂商清理时
     *    最容易被放行的一种（系统把它当"闹钟"看待）。代价：状态栏会出现一个
     *    闹钟图标（这反而是好事：用户能看见"提醒已设"）。
     * 2. `setExactAndAllowWhileIdle` —— 精确，但 Doze 下可能被推迟。
     * 3. `setAndAllowWhileIdle` —— 不精确，系统在附近叫醒；**不需要任何权限**。
     *
     * 只有真的成功才写「下次提醒」，并且写明是哪种策略 —— 于是这行日志
     * 以后是**可判断的证据**，不是自我安慰。
     */
    fun scheduleNext(ctx: Context): String {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: run {
            DiagLog.w("Reminder", "没有 AlarmManager，无法排提醒")
            return "failed"
        }
        val at = nextTriggerAt()
        val pi = pendingIntent(ctx)

        val attempts: List<Pair<String, () -> Unit>> = buildList {
            if (canExact(am)) {
                add("alarmClock" to { am.setAlarmClock(AlarmManager.AlarmClockInfo(at, openApp(ctx)), pi) })
                add("exact" to { am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) })
            }
            add("inexact" to { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) })
        }

        for ((name, fire) in attempts) {
            val ok = runCatching { fire() }.isSuccess
            if (ok) {
                remember(ctx, at, name)
                DiagLog.i(
                    "Reminder",
                    "下次提醒 ${DayKey.dateLabel(at / 86_400_000)} " +
                        "%02d:%02d（$name）".format(HOUR, MINUTE),
                )
                return name
            }
            DiagLog.w("Reminder", "排闹钟用 $name 失败，换下一种")
        }
        remember(ctx, 0L, "failed")
        DiagLog.w("Reminder", "★ 三种方式都没排上 —— 今晚不会响（这台机器的限制，需要看系统设置）")
        return "failed"
    }

    // ------------------------------------------------------------------
    // 排上了什么：给界面一句真话（4.2.1）
    // ------------------------------------------------------------------

    data class Scheduled(val at: Long, val strategy: String)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("reminder_state", Context.MODE_PRIVATE)

    private fun remember(ctx: Context, at: Long, strategy: String) {
        runCatching {
            prefs(ctx).edit().putLong("at", at).putString("strategy", strategy).apply()
        }
    }

    /** 上一次真的排上的闹钟；从没排过返回 null */
    fun lastScheduled(ctx: Context): Scheduled? {
        val p = runCatching { prefs(ctx) }.getOrNull() ?: return null
        val at = p.getLong("at", 0L)
        if (at <= 0L) return null
        return Scheduled(at, p.getString("strategy", "").orEmpty())
    }

    /** 系统允不允许精确闹钟（不允许时我们只能不精确，可能晚几分钟到十几分钟） */
    fun exactAllowed(ctx: Context): Boolean =
        ctx.getSystemService(AlarmManager::class.java)?.let { canExact(it) } ?: false

    fun notificationsAllowed(ctx: Context): Boolean =
        NotificationManagerCompat.from(ctx).areNotificationsEnabled()

    /** 下一次 23:30 的时间戳（毫秒）。今天过了 23:30 就排到明天 */
    fun nextTriggerAt(now: Long = System.currentTimeMillis()): Long {
        val cal = java.util.Calendar.getInstance().apply {
            timeInMillis = now
            set(java.util.Calendar.HOUR_OF_DAY, HOUR)
            set(java.util.Calendar.MINUTE, MINUTE)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
            if (timeInMillis <= now) add(java.util.Calendar.DAY_OF_YEAR, 1)
        }
        return cal.timeInMillis
    }

    private fun canExact(am: AlarmManager): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()

    private fun pendingIntent(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx,
        REQUEST_CODE,
        Intent(ctx, ReminderReceiver::class.java).setAction(ReminderReceiver.ACTION_FIRE),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /**
     * 到点了：**今天还没达标才提醒**。
     *
     * ★ 这条判据很重要：如果用户今天已经听够了，再来一句「别忘了听英语」
     *   就是纯噪音，而噪音会让人把这个提醒整个关掉。
     */
    fun notifyNow(ctx: Context, force: Boolean = false) {
        // 短命 receiver 里临时建一个实例读数据是安全的（只是读同一份 prefs）；
        // ⚠️ 不要在这里写数据 —— 那份唯一实例在容器里，两份同时写会互相覆盖
        val store = CheckInStore(ctx)
        val today = DayKey.today()
        val sec = store.todaySeconds()
        val goal = store.goalSecondsOn(today)
        if (sec >= goal && !force) {
            DiagLog.i("Reminder", "今天已达标（${sec / 60}/${goal / 60} 分钟），不打扰")
            return
        }
        if (!NotificationManagerCompat.from(ctx).areNotificationsEnabled()) {
            DiagLog.w("Reminder", "系统里通知被关掉了，这一条发不出去")
            return
        }

        val left = ((goal - sec).coerceAtLeast(0)) / 60
        val text = if (sec <= 0) {
            "今天还没开始听，还有 $left 分钟就达标了"
        } else {
            "已听 ${sec / 60} 分钟，还差 $left 分钟"
        }
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle(if (force) "测试提醒（点开看能不能收到）" else "别忘了今天的英语")
            .setContentText(text)
            .setContentIntent(openApp(ctx))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        runCatching {
            NotificationManagerCompat.from(ctx).notify(NOTIFICATION_ID, n)
        }.onFailure { DiagLog.w("Reminder", "发通知失败：${it.javaClass.simpleName}") }
        DiagLog.i("Reminder", "每日提醒已发 · $text")
    }

    private fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx,
        REQUEST_CODE,
        Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
