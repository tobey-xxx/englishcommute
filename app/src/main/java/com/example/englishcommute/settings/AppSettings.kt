package com.example.englishcommute.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * 外观模式。
 *
 * 默认 [System] 而不是 [Dark]：日间模式下系统通知栏/键盘/分享面板全都是浅色的，
 * App 如果硬撑深色，从系统面板切回来的那一下会有很明显的割裂感。跟随系统是
 * 唯一不会「跟手机其它部分打架」的默认值。
 */
enum class ThemeMode { System, Light, Dark }

/**
 * 用户设置。
 *
 * 两项是「设一次要长期记住」的：
 *  - 外观模式（跟随系统 / 日间 / 夜间）
 *  - 播放倍速（新闻听力 0.8x 是常用档，每次重开都回到 1.0x 会很烦）
 *
 * 外加一个「允许后台干活吗」的开关，见 [backgroundWorkEnabled]。
 *
 * 用 DataStore 而不是 SharedPreferences：读写都是挂起函数、天然在 IO 线程，
 * 不会像 `apply()` 那样在启动路径上塞一次磁盘写入。
 */
class AppSettings(context: Context) {

    private val store = context.applicationContext.settingsStore

    /**
     * 「允许后台干活」总开关。默认开 —— 这个 App 的意义就是「打开就有新内容」。
     *
     * ## 它原来是「每日自动更新」的开关（3.5.0 改了用途，**key 没改**）
     *
     * 那条 24 小时的每日任务已经拆掉了（见 `sync/RetiredWork`）。但这个开关
     * **不能跟着一起删**，因为还有两处在读它：
     *
     *  · `SubtitleScheduler`：每小时一次的后台字幕加载；
     *  · `ContentSyncService`：用户点了更新之后，服务里那个「接着加载字幕」的循环。
     *
     * 删掉它 = 曾经把它关掉的人会被**静默地**重新打开，而且再也找不到关的地方。
     * 所以留着开关，只把名字和文案改成它现在真正管的事。
     *
     * ## 为什么磁盘上的 key 仍然叫 `daily_sync_enabled`
     *
     * 换 key 意味着老用户存下的 `false` 读不出来、静默回到默认的 `true` ——
     * 也就是上面那个后果。**为了名字好看去丢用户设置是不划算的。**
     * （同一个理由写在 [playbackSpeedPercent] 的注释里：那条是「归一化能解决的
     * 兼容问题，不要用数据迁移去碰」。）
     *
     * 这里是它的另一个形态：**改名只要一层映射，改名 + 迁移才需要碰数据。**
     * 而这一处连映射都不需要 —— 语义根本没变（「允许后台干活吗」），
     * 变的只是「后台在干什么」。
     */
    val backgroundWorkEnabled: Flow<Boolean> = store.data.map { it[KEY_DAILY_SYNC] ?: true }

    /**
     * 每晚 23:30 的提醒（4.2.0）。默认**开**。
     *
     * 默认开是有意的：它是这个 App 的「明日再来」钩子，而关掉之后
     * 用户几乎不会再想起来打开开关。但它**不是暗推**：
     * 系统通知权限没给就什么都不发生，设置页那个开关也一直摆在最上面。
     */
    val reminderEnabled: Flow<Boolean> = store.data.map { it[KEY_REMINDER] ?: true }

    suspend fun setReminderEnabled(enabled: Boolean) {
        store.edit { it[KEY_REMINDER] = enabled }
    }

    /**
     * 播放倍速，**整百分数**（50..200，5 的倍数）。规则与换算见 [SpeedPolicy]。
     *
     * ## 为什么对外是 Int 而不是 Float
     *
     * 磁盘上仍然是 Float（沿用 `KEY_SPEED`，不动老用户的存量数据），
     * 但**读写都过一遍归一化**，出了这个类就是合法档位。
     *
     * 为什么不干脆换一个 Int 类型的 key：换 key 意味着老用户存下的 0.75
     * 读不出来、静默回到 1.0x。为了类型好看去丢用户设置是不划算的 ——
     * 归一化一行就能解决的兼容问题，不该用数据迁移去碰。
     *
     * 同一个理由也解释了为什么读的时候也要归一化：老版本可能存过 0.7
     * （精听档曾被写进去过），它是合法档位；但万一存过 0.72 这类
     * 非档位值，读出来不归一化会让滑杆停在一个不存在的位置。
     */
    val playbackSpeedPercent: Flow<Int> = store.data.map { prefs ->
        SpeedPolicy.percentOf(prefs[KEY_SPEED] ?: 1.0f)
    }

    /**
     * 外观模式。
     *
     * 存枚举名而不是序数：序数一旦调整枚举顺序，老用户存下的值含义就变了，
     * 而这种 bug 只会在升级后的老设备上出现，最难发现。解析失败一律回落
     * 到 [ThemeMode.System]，不抛异常。
     */
    val themeMode: Flow<ThemeMode> = store.data.map { prefs ->
        prefs[KEY_THEME_MODE]
            ?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
            ?: ThemeMode.System
    }

    suspend fun setBackgroundWorkEnabled(enabled: Boolean) {
        store.edit { it[KEY_DAILY_SYNC] = enabled }
    }

    /**
     * 写倍速。
     *
     * ⚠️ 必须先归一化再落盘。直接把 UI 传来的值写进去，等于让
     *    「磁盘上有没有非法档位」取决于调用方有没有自觉 ——
     *    而读的那一侧虽然也归一化，两边都不管的时候就会出现
     *    「设置页显示 0.77x、滑杆却停在 0.75x」这种撕裂。
     */
    suspend fun setPlaybackSpeedPercent(percent: Int) {
        store.edit { it[KEY_SPEED] = SpeedPolicy.speedOf(percent) }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        store.edit { it[KEY_THEME_MODE] = mode.name }
    }

    companion object {
        /**
         * 「允许后台干活」的 key。
         *
         * ⚠️ 名字来自 3.5.0 拆掉的「每日自动更新」—— 那是它当初的用途。
         *    **不要因为名字过时就去改它**：换了 key，老用户存下的 `false`
         *    读不出来、会静默回到默认的 `true`。理由写在 [backgroundWorkEnabled]。
         */
        private val KEY_DAILY_SYNC = booleanPreferencesKey("daily_sync_enabled")
        private val KEY_REMINDER = booleanPreferencesKey("daily_reminder_enabled")
        private val KEY_SPEED = floatPreferencesKey("playback_speed")
        private val KEY_THEME_MODE = stringPreferencesKey("theme_mode")

        // ⚠️ 3.5.0 拆掉了三个随「每日自动更新」一起进来的东西：
        //    `KEY_SYNC_TIME` / `DEFAULT_SYNC_TIME` / `SYNC_TIME_STEPS` / `parseTime()`。
        //    它们只服务于「用户挑一个触发时刻」，而那条任务已经不存在了。
        //    ★ 拆功能时要**连它专有的常量与工具函数一起拆**：留着它们，
        //      下一个读代码的人会以为「时刻」这件事还有人管。
        //      （`parseTime` 当年还配了一份逐行对齐的 Python 镜像，
        //       见 `testdata/verify_listening.py` —— 那一边也要一起拆，
        //       否则会留下一个「验着已经不存在的代码」的脚本。）

        /**
         * 可选倍速档位过去在这里（`SPEED_STEPS`，固定 6 档）。
         *
         * 现在倍速是可连续调节的（0.05 一格，见 [SpeedPolicy]），
         * 由滑杆 + ± 微调承担，不再需要一份「档位列表」。
         * 精听用的慢速档同理挪到了 [SpeedPolicy.PERCENT_SLOW] ——
         * 它现在还要跟用户当前倍速比一次大小，不是一个孤立常量。
         */
    }
}
