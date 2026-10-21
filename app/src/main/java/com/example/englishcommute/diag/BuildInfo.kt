package com.example.englishcommute.diag

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 这台机器上**装的是哪个包、什么时候装的**。
 *
 * ## 为什么值得单独做一个 object
 *
 * 0.11.0 之前交付过一轮（0.9.1 / 0.10.0），用户反馈「转写体验很差、自动转写经常触发不了、
 * 被意外打断不会恢复、App 后台不转写」—— 排查一整圈之后的结论是：
 * **他手机上跑的是 0.9.0**，而 0.9.0 的转写是**必然崩**的
 * （原生库 ELF 符号版本对不上，详见 `tools/check-so-versions.py`）。
 *
 * 症状全是真的，但 0.9.1 起已经修掉了三条独立缺陷，一行代码都不该再改 ——
 * 该做的是让他**真正装上**修复版。
 *
 * 这件事之所以能发生，是因为当时**没有任何手段能一眼看出「机器上的是哪一版、什么时候装的」**：
 * 设置页只有版本号，而版本号只有主动去系统「应用信息」里翻才看得到；
 * 日志头也只有版本号，区分不了「刚装的」和「三天前装的」。
 *
 * ## 为什么安装时间比版本号更有用
 *
 * **版本号可以统一，安装时间不会。**
 * 「我发的是 0.11.0」和「你装的是 0.11.0」是两件事，
 * 而时间戳是唯一能戳破前者的东西 —— 时间对不上，就是没装上，不用再往下查代码。
 */
object BuildInfo {

    /** APK 最后一次被安装/更新的时刻（epoch 毫秒）；读不到返回 0 */
    fun installTimeMillis(context: Context): Long = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
    }.getOrDefault(0L)

    /** `09-18 14:40`；读不到返回「未知」 */
    fun shortLabel(millis: Long): String {
        if (millis <= 0L) return "未知"
        return SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(millis))
    }

    /**
     * `3 小时前` / `刚刚` / `12 天前`。
     *
     * 相对时间比绝对时间更能暴露「装了没重启」和「根本没装」——
     * 用户看到「3 天前」立刻就明白自己手里不是刚出的那个包。
     */
    fun relativeLabel(millis: Long, now: Long = System.currentTimeMillis()): String {
        if (millis <= 0L) return "未知"
        val delta = now - millis
        // 设备时钟被回拨（换时区、手动改时间）时 delta 会是负的，别显示成「-5 分钟前」
        if (delta < 0L) return "刚刚"
        val minutes = delta / 60_000L
        return when {
            minutes < 1L -> "刚刚"
            minutes < 60L -> "$minutes 分钟前"
            minutes < 60L * 24L -> "${minutes / 60L} 小时前"
            else -> "${minutes / (60L * 24L)} 天前"
        }
    }

    /** 日志头用：紧凑，不带相对时间（日志每行都要短） */
    fun installStamp(context: Context): String = shortLabel(installTimeMillis(context))

    /** 设置页用：`09-18 14:40（3 小时前）` */
    fun installDetail(context: Context): String {
        val t = installTimeMillis(context)
        if (t <= 0L) return "未知"
        return "${shortLabel(t)}（${relativeLabel(t)}）"
    }
}
