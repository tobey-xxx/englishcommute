package com.example.englishcommute.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.englishcommute.EnglishCommuteApp
import com.example.englishcommute.content.FeedSources
import com.example.englishcommute.content.StorageBreakdown
import com.example.englishcommute.diag.CrashGuard
import com.example.englishcommute.diag.DiagLog
import com.example.englishcommute.settings.AppSettings
import com.example.englishcommute.settings.ThemeMode
import com.example.englishcommute.notify.DailyReminder
import com.example.englishcommute.sync.SubtitleScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置页展示的存储占用。
 *
 * ⚠️ 2.1.0 删掉了 `modelBytes`（「语音模型占了多少」）与由它算出来的 `totalBytes`。
 *    那个数字存在的意义是「让用户知道 700 MB 里 630 MB 是不能删的模型」——
 *    端侧模型已经拆掉，它现在恒为 0。**一个恒为 0 的统计项比没有更糟**，
 *    用户会去查「为什么是 0、是不是没装好」。
 *    剩下的三项都是真的：音频能删、字幕跟着音频走、条数是用户自己的内容。
 */
data class StorageInfo(
    val breakdown: StorageBreakdown,
    /** 已听完、可以安全删掉的条数 */
    val doneCount: Int,
)

/**
 * 设置页展示的诊断摘要。
 *
 * [lastExit] 必须在这里露出，不能只藏在日志文件里：用户来反馈「闪退」时，
 * 我们需要的第一条信息就是**上次进程是怎么死的** ——
 * 「内存不足被系统回收」和「Native 层崩溃」在用户眼里长得一模一样，
 * 但一个是资源问题、一个是代码问题。让用户截一张设置页的图就能定性。
 */
data class DiagInfo(
    val sizeBytes: Long = 0L,
    val lastExit: String? = null,
    val lastExitAbnormal: Boolean = false,
    val jvmCrashes: Int = 0,
    /**
     * 封面：磁盘上已就绪几个源，以及最近一次补齐的结果。
     *
     * 必须露出。封面来自**另一个域**的图床（`media.npr.org` /
     * `image.simplecastcdn.com`），它的可达性和 feed 完全是两回事，
     * 而失败时界面只表现为「占位块」—— 不把「下没下下来、卡在哪个域」
     * 摆出来，就只能靠猜。
     */
    val artwork: String = "…",
    val artworkBad: Boolean = false,
    /**
     * 待补字幕条数（有音频、字幕没齐）。
     *
     * 这个数字是「字幕是不是真的在补齐」的唯一客观依据：
     * 它单调不增说明在正常消化，长期不变说明服务器那边一直没轮上
     * （或者根本连不上）。2.1.0 之前它叫「待转写」，还带一个
     * 「几条有断点可续」的附注 —— 断点那套机制随端侧识别一起拆掉了。
     */
    val pendingSubs: Int = 0,
)

/**
 * 设置页状态。
 *
 * 拨开关时**同时**做两件事：写设置 + 重新登记 WorkManager 任务。
 * 两件事必须一起做 —— 只写设置不登记的话，界面上显示「已开启」但系统按旧配置跑，
 * 这是最难排查的一类 bug（UI 说一套、系统做一套）。
 *
 * ⚠️ 3.5.0 拆掉「每日自动更新」之后，这个开关管的后台任务只剩**一个**
 *    （每小时的字幕加载），但开关本身必须留着 —— 见 [AppSettings.backgroundWorkEnabled]。
 */
class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as EnglishCommuteApp).container

    /**
     * 允许后台干活吗。设置页那个开关读它。
     *
     * ⚠️ 3.5.0 之前它叫 `dailySyncEnabled`（那是它原来的用途）。
     *    改名只碰代码，**磁盘上的 key 一个字没动** —— 理由在
     *    [AppSettings.backgroundWorkEnabled] 的注释里。
     */
    val backgroundWorkEnabled: StateFlow<Boolean> = container.settings.backgroundWorkEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    /** 每晚 23:30 的提醒（4.2.0） */
    val reminderEnabled: StateFlow<Boolean> = container.settings.reminderEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    /**
     * 拨提醒开关：写设置 + **立刻对齐闹钟**。
     *
     * ⚠️ 两件事必须一起做 —— 只写设置不排闹钟的话，界面上显示「已开启」，
     *    而系统里根本没有那个闹钟。这正是本工程最怕的一类（UI 说一套、系统做一套）。
     */
    fun setReminderEnabled(enabled: Boolean) {
        viewModelScope.launch {
            container.settings.setReminderEnabled(enabled)
            DailyReminder.sync(getApplication(), enabled)
        }
    }

    /**
     * 外观模式。
     *
     * 主界面要读它来定 [com.example.englishcommute.ui.theme.EnglishCommuteTheme] 的 dark 值，
     * 所以初值必须与 AppSettings 的默认值一致（System）。给 Dark 的话，
     * 冷启动那一帧会先按暗色画一遍再跳到亮色，闪一下。
     */
    val themeMode: StateFlow<ThemeMode> = container.settings.themeMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, ThemeMode.System)

    private val _storage = MutableStateFlow<StorageInfo?>(null)
    val storage: StateFlow<StorageInfo?> = _storage.asStateFlow()

    private val _diag = MutableStateFlow(DiagInfo())
    val diag: StateFlow<DiagInfo> = _diag.asStateFlow()

    init {
        refreshStorage()
        refreshDiag()
    }

    /** 诊断摘要。与存储统计同路：文件大小统计要走 IO 线程 */
    fun refreshDiag() {
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            val info = withContext(Dispatchers.IO) {
                val art = container.artwork
                val cached = art.cachedCount()
                val report = art.lastReport
                DiagInfo(
                    sizeBytes = DiagLog.totalBytes(),
                    lastExit = CrashGuard.lastExitSummary(ctx),
                    lastExitAbnormal = CrashGuard.isLastExitAbnormal(ctx),
                    jvmCrashes = CrashGuard.jvmCrashCount(ctx),
                    artwork = buildString {
                        append("已就绪 $cached/${FeedSources.ALL.size} 源")
                        val bad = report?.failures.orEmpty()
                        if (bad.isNotEmpty()) {
                            append(" · 失败 ")
                            append(bad.joinToString("、") { it.first })
                        }
                    },
                    artworkBad = report?.failures?.isNotEmpty() == true,
                    pendingSubs = container.repository.pendingSubtitleCount(),
                )
            }
            _diag.value = info
        }
    }

    /**
     * 手动记一次当前状态。
     *
     * 用户准备反馈问题前点一下，「当时有哪几条内容、卡在哪一步、内存多少」
     * 就都写进日志了 —— 这些信息隔着屏幕问是问不清楚的。
     */
    fun captureDiagState() {
        DiagLog.dumpState("用户手动记录")
        refreshDiag()
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { container.settings.setThemeMode(mode) }
    }

    /**
     * 拨「允许后台干活」那个开关。
     *
     * ⚠️ 只登记 [SubtitleScheduler] —— 3.5.0 之前这里还要一起重排「每日自动更新」，
     *    那条任务已经拆掉了。**但退役任务不在这里撤**：它的撤销必须无条件执行
     *    （理由见 `RetiredWork` 与 `EnglishCommuteApp.scheduleBackgroundWork`），
     *    而这里只有用户主动拨开关时才会走到。
     */
    fun setBackgroundWorkEnabled(enabled: Boolean) {
        viewModelScope.launch {
            container.settings.setBackgroundWorkEnabled(enabled)
            SubtitleScheduler.apply(getApplication(), enabled)
        }
    }

    /**
     * 重新统计占用。
     *
     * 仍然走 IO 线程：这几项都要 `listFiles + length` 扫目录，
     * 虽然不读内容，在主线程做依然是一次不必要的磁盘往返。
     * （2.1.0 之前这里还要单独统计 622 MB 的模型目录，现在只剩素材库那三个目录。）
     */
    fun refreshStorage() {
        viewModelScope.launch {
            val info = withContext(Dispatchers.IO) {
                StorageInfo(
                    breakdown = container.repository.audioStore.breakdown(),
                    doneCount = container.playbackPositions.done.value.size,
                )
            }
            _storage.value = info
        }
    }

    /** 清理已听完的音频。听过的内容通常不会再听，但今天刚下的必须留着 */
    fun clearPlayedAudio() {
        viewModelScope.launch {
            val done = container.playbackPositions.done.value
            val targets = container.repository.episodes.value.filter { it.id in done }
            withContext(Dispatchers.IO) {
                container.repository.deleteLocalFiles(targets)
                targets.forEach { container.playbackPositions.clearDone(it.id) }
            }
            refreshStorage()
        }
    }
}
