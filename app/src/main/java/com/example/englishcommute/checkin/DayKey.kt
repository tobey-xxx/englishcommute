package com.example.englishcommute.checkin

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 「哪一天」的算法。打卡的全部数据都以它为主键。
 *
 * ## 为什么不用 `java.time.LocalDate`
 *
 * `minSdk = 26`，`java.time` 其实是可用的。不用它有两个具体理由：
 *
 * 1. **要在桌面端 1:1 复刻验证。** 这套逻辑的错误是静默的 —— 跨天错一格
 *    只会让「今天听了 40 分钟」记到昨天，界面上看不出任何异常。
 *    而 `LocalDate` 的行为依赖 Android 的 tzdata 版本，Python 里没法逐行对齐；
 *    「UTC 偏移 + 整除」这两行则可以精确复刻（见 `tools/verify-checkin.py`）。
 * 2. **这里的语义就是「本地日历日」**，用 [TimeZone.getOffset] 拿到**那一刻**的
 *    真实偏移即可 —— 这正是 `java.time` 内部算 epochDay 的做法，
 *    夏令时切换当天也不会算错。
 *
 * ## 为什么不存 `yyyy-MM-dd` 字符串
 *
 * 存字符串看起来更可读，但排序要按字典序、跨月跨年要拼日期、去重还得解析。
 * 存天数（自 1970-01-01 起的本地日序）之后，「前一天」就是 `-1`，
 * 「连续几天」就是查一个整数集合的连通段 —— 全是整数运算，没有格式问题。
 */
object DayKey {

    const val DAY_MS = 86_400_000L

    /**
     * 某一时刻属于哪个本地日。
     *
     * `floorDiv` 而不是 `/`：1970 年之前的时刻用整数除法会朝 0 取整，
     * 于是一天里的前几小时会被算到后一天上。真机上遇到这种情况的概率极低，
     * 但改用 `floorDiv` 是零成本的，而这种偏差一旦发生就没人查得出来。
     */
    fun of(millis: Long, zone: TimeZone = TimeZone.getDefault()): Long {
        val local = millis + zone.getOffset(millis)
        return Math.floorDiv(local, DAY_MS)
    }

    fun today(now: Long = System.currentTimeMillis()): Long = of(now)

    /**
     * 某一天的本地零点时刻。
     *
     * 只用于**显示**（格式化日期），不参与任何判据 —— 所以两次取偏移就够了，
     * 不需要处理「当天发生过夏令时切换」这种极端情形。
     */
    fun startOfDayMillis(day: Long, zone: TimeZone = TimeZone.getDefault()): Long {
        val naive = day * DAY_MS
        val firstPass = naive - zone.getOffset(naive)
        return naive - zone.getOffset(firstPass)
    }

    /** 「9月20日」。用 [Locale.CHINA] 固定，避免某些系统区域下变成英文月名 */
    fun dateLabel(day: Long): String =
        SimpleDateFormat("M月d日", Locale.CHINA).format(Date(startOfDayMillis(day)))

    /** 「周六」 */
    fun weekdayLabel(day: Long): String =
        SimpleDateFormat("EEEE", Locale.CHINA).format(Date(startOfDayMillis(day)))

    /** 「今天 / 昨天 / 前天 / 9月17日」 */
    fun relativeLabel(day: Long, today: Long = today()): String = when (today - day) {
        0L -> "今天"
        1L -> "昨天"
        2L -> "前天"
        else -> dateLabel(day)
    }

    /** 「42 分钟」。不足一分钟显示秒，否则「0 分钟」看起来像没记录 */
    fun minutesLabel(seconds: Int): String = when {
        seconds <= 0 -> "0 分钟"
        seconds < 60 -> "$seconds 秒"
        else -> "${seconds / 60} 分钟"
    }

    /** `MM:SS`，用于「还差多少」 */
    fun clockLabel(seconds: Int): String {
        val s = seconds.coerceAtLeast(0)
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60)
    }
}
