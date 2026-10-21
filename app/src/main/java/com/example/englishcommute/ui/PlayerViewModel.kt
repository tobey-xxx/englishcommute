package com.example.englishcommute.ui

import android.app.Application
import android.os.SystemClock
import android.content.ComponentName
import android.net.Uri
import androidx.compose.runtime.Stable
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.example.englishcommute.EnglishCommuteApp
import com.example.englishcommute.content.Episode
import com.example.englishcommute.diag.DiagLog
import com.example.englishcommute.player.PlaybackQueue
import com.example.englishcommute.player.PlaybackService
import com.example.englishcommute.player.SrtTrack
import com.example.englishcommute.player.SubtitleLine
import com.example.englishcommute.settings.AppSettings
import com.example.englishcommute.settings.SpeedPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 播放页的全部可变状态。
 *
 * 刻意做成**一个** StateFlow 而不是拆成一堆：播放位置每 250 ms 变一次，
 * 拆开反而要维护多份订阅的一致性。页面上真正依赖高频变化的部分
 * 只有进度条、时间文本和「当前句」三处，重组代价可控。
 */
data class PlayerUiState(
    /**
     * 当前这一期的 id。
     *
     * 迷你播放条需要它来「点一下回到播放页」—— 迷你条只拿到一个 [PlayerUiState]，
     * 没有别的地方能问出「现在播的是哪一条」。用标题反查是不行的：不同源的
     * 两条内容完全可能同名。
     */
    val episodeId: String = "",
    val loading: Boolean = true,
    val title: String = "",
    val sourceName: String = "",
    /**
     * 封面用。迷你播放条和播放页都靠它画出「正在播的是哪一档」。
     *
     * 只带 [sourceId] 是不够的：ArtworkStore 在磁盘上按源缓存图，
     * 但**首次下载**需要 URL，而它不持有索引 —— 所以 URL 必须跟着状态一起带过来。
     */
    val sourceId: String = "",
    val artworkUrl: String = "",
    val playing: Boolean = false,
    val buffering: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    /**
     * **用户选择的**倍速，不是播放器的实际倍速。
     *
     * 精听时实际速度会被临时压慢（规则见 [SpeedPolicy.slowPercentFor]），
     * 如果这里跟着变，下方滑杆会当场滑走 —— 而用户并没有碰过它。
     * 「当前正在慢速重听」由 [repeating] 单独表达。
     *
     * 用 Int 百分数而不是 Float，理由见 [SpeedPolicy]：0.05 的步子下，
     * 浮点会带来 `1.3500001x` 这类显示问题，以及「滑杆松手跳档」。
     */
    val speedPercent: Int = SpeedPolicy.PERCENT_DEFAULT,
    /** 当前句下标；-1 表示没有字幕或还没开始 */
    val currentIndex: Int = -1,
    /** 是否跟随播放自动滚动。用户手动滚动后置 false，点「跟随」恢复 */
    val following: Boolean = true,
    /** 正在慢速重听本句 */
    val repeating: Boolean = false,
    val favorite: Boolean = false,
    /** 队列里还有上一集 / 下一集，用来决定按钮是否可点 */
    val hasPrevious: Boolean = false,
    val hasNext: Boolean = false,
    /** 队列位置，形如「第 3/12 集」。0 表示队列还没建好 */
    val queueIndex: Int = 0,
    val queueSize: Int = 0,
    val error: String? = null,
)

/**
 * 中文对照的等待状态。
 *
 * ## 2.1.0：从四个态收敛成两个态
 *
 * 它原来叫「准备状态」，四个态全部围绕**端侧翻译**的形态：
 * `Preparing`（铺 105 MB 模型 + 建会话，十几秒）、
 * `Running(progress)`（逐句翻，一集 1–3 分钟）、`Failed`。
 *
 * 那两个带进度的态之所以必须存在，是因为**等待本身很长**（按分钟计），
 * 不给反馈用户会以为坏了。
 *
 * 现在中文对照是服务器给的、一次 HTTP 取回来（几十 KB），所以：
 *  - 没有「准备」这一步 —— 没有模型要铺
 *  - 没有中间进度 —— 请求发出去到落盘是一个动作，没有可以填进进度条的中间量
 *  - 「失败」也不该报 —— 取不到的最正常成因是**服务器还没翻译好**
 *    （它先出英文、晚一轮出中文），下一轮自动补字幕会捡起来。
 *    报一句红字只会让用户以为要自己想办法，而其实什么都不用做
 *
 * 留下的两个态回答的正是用户唯一的两个处境：**有译文 / 还没有**。
 */
sealed interface TranslateUiStatus {
    /** 不需要提示：要么已就绪，要么这期根本没字幕 */
    data object Idle : TranslateUiStatus

    /**
     * 英文字幕已经在，中文还没到。
     *
     * 界面显示一句「中文对照在来的路上…」，同时 [PlayerViewModel.refreshTranslation]
     * 在后台每几秒回读一次盘 —— 服务器把那一条翻好之后，它**自己会长出来**，
     * 用户不需要退出重进。
     */
    data object Waiting : TranslateUiStatus
}

/**
 * 播放页状态机。
 *
 * 界面**不直接持有 ExoPlayer**，而是通过 [MediaController] 连到
 * [PlaybackService] 里的播放器。这样退出播放页音频不会中断，
 * 重新进入时接上的还是同一个播放位置。
 *
 * 播放器持有的不是「一条音频」而是**整个可播队列**（`setMediaItems`）：
 * 通勤场景下听完一集正好到站或还没到站，自动接上下一集是刚需，
 * 而且队列交给 ExoPlayer 之后，播完自动推进、通知栏的上一集/下一集、
 * 蓝牙耳机的双击切歌全都免费获得，不需要自己写一套「播完了然后呢」。
 *
 * 队列的**顺序**由 [PlaybackQueue] 决定：未听完的排在已听完的前面。
 * 于是「下一条」自然等于「下一条还没听完的」—— 这正是把它抽成纯函数的理由，
 * 顺序规则和播放器本身没有关系，放在这里只是被使用。
 *
 * 注意 [PlayerUiState.queueIndex] / [PlayerUiState.queueSize] 说的是**这个队列里的位置**，
 * 不是「全库第几集」。两者在加了排序规则之后不再等价。
 *
 * 字幕全文一次性解析好放在 [lines] 里，播放位置由 250 ms 轮询 + 二分查找映射成当前句
 * （为什么不用 Media3 的 `onCues(CueGroup)`：它只给「此刻该显示的那一条」，
 * 拿不到句序号，撑不起整篇文稿的高亮定位 —— 见 [SrtTrack] 的说明）。
 */
/** [Stable] 的理由同 [LibraryViewModel]：让 `PlayerScreen` 可被跳过 */
@Stable
class PlayerViewModel(app: Application) : AndroidViewModel(app) {

    private val app: EnglishCommuteApp get() = getApplication()

    private val container = (app as EnglishCommuteApp).container

    private val _ui = MutableStateFlow(PlayerUiState())
    val ui: StateFlow<PlayerUiState> = _ui.asStateFlow()

    /**
     * 只有「正在播哪一条」这一个字段，**换集才会变**。
     *
     * ## 为什么要单独开一条（1.2.0）
     *
     * [ui] 每 250 ms 发一个新值（播放位置在走）。谁读它，谁就每秒重组 4 次。
     * 而 `MainActivity.Root()` 原来就在顶层读它 —— 于是整棵界面树
     * （含三个页签全部内容，因为 pager 预排了相邻页）每秒重跑 4 遍。
     *
     * 素材库其实只需要知道「哪一条要标成琥珀色」，这个信息一集只变一次。
     * 拆出来之后，列表从「每秒 4 次」降到「换集时 1 次」。
     *
     * `distinctUntilChanged` 是关键：没有它，上游每 250 ms 的发射
     * 仍然会穿透过来，拆了等于没拆。
     */
    val playingEpisodeId: StateFlow<String> = _ui
        .map { it.episodeId }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    /**
     * 「现在**到底在不在播**」（4.6.3）。
     *
     * ★ 为什么需要单独一条流：列表的播放键要显示「播放 ▷ / 暂停 ⏸」，
     *   而 `playingEpisodeId` 只说「是哪一条」，不说「在不在播」。
     *   用户点了暂停之后图标不变 —— 看起来像「点了没生效」。
     *
     * ⚠️ `distinctUntilChanged` 是硬要求：`ui` 每 250 ms 发一次（进度在变），
     *   直接把它接进列表会让整页 4 次/秒重组 —— 这正是 `playingEpisodeId`
     *   那条注释里讲过的坑。
     */
    val isPlayingNow: StateFlow<Boolean> = _ui
        .map { it.playing }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /**
     * 当前高亮的字幕行号。**换句才变**（几秒一次），不是每 250 ms。
     *
     * 播放页的字幕列表用它决定哪一行要高亮。直接读 [ui] 的话，
     * 所有可见的 8–12 行 `SubtitleRow` 每秒都要重组 4 次 ——
     * 而实际需要变的只有两行：离开的那行和进入的那行。
     */
    val currentLineIndex: StateFlow<Int> = _ui
        .map { it.currentIndex }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, -1)

    private val _lines = MutableStateFlow<List<SubtitleLine>>(emptyList())
    val lines: StateFlow<List<SubtitleLine>> = _lines.asStateFlow()

    /**
     * 中文译文，下标与 [lines] 严格一一对应。
     *
     * `null` = 这一句还没翻（正在翻，或上次没翻完）。
     * `""` = 这一句不需要翻（纯符号、纯数字），UI 直接不显示第二行。
     * 两种空值的含义不同，所以不能用 `List<String>` 抹平。
     */
    private val _translations = MutableStateFlow<List<String?>>(emptyList())
    val translations: StateFlow<List<String?>> = _translations.asStateFlow()

    private val _translateStatus = MutableStateFlow<TranslateUiStatus>(TranslateUiStatus.Idle)
    val translateStatus: StateFlow<TranslateUiStatus> = _translateStatus.asStateFlow()

    /** 等中文对照的那条协程。换集 / 重入时先取消，避免两轮一起回读 */
    private var waitJob: Job? = null

    private var controller: MediaController? = null
    private var connectRequested = false

    /** 界面上「正在听」的条目。它和播放器里的当前媒体项在正常情况下是一致的 */
    private var currentEpisodeId: String? = null

    /**
     * 播放器里**已经由我们主动设过**的媒体项 id。
     *
     * 用来区分 [onMediaItemTransition] 的两种来源：
     *  - `id == appliedId`：是我们自己 `setMediaItems` 触发的，别重复处理
     *  - `id != appliedId`：播放器自己推进到了下一条（自动连播），这时要换字幕、接断点
     *
     * ⚠️ 赋值必须发生在 `setMediaItems` **之前**。Media3 的回调在部分版本上是同步派发的，
     *    顺序反了会把我们自己的设置误判成自动连播，导致刚点进去就被 seek 两次。
     */
    private var appliedId: String? = null

    /** 每次「打开某一期」自增，用来丢弃过期的异步结果（快速连点两条时） */
    private var applyToken = 0

    /** 上次把播放位置写盘的时刻，用于节流 */
    private var lastPersistAt = 0L

    /** 精听：慢速重听本句的结束位置（毫秒）。0 表示不在精听中 */
    private var repeatUntilMs = 0L

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) = refresh()
        override fun onIsPlayingChanged(isPlaying: Boolean) = refresh()

        /**
         * 当前媒体项变化。两种来源：我们自己设置的，或播放器自动连播到下一集。
         *
         * 自动连播这一路必须自己处理三件事，否则体验会明显断裂：
         *  1. 换标题 / 换字幕（不然屏幕上还是上一集的文稿）
         *  2. 接断点（上一集播完的位置不能带到新一集）
         *  3. 恢复自动滚动（用户可能在上一条上手动翻过文稿）
         */
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val id = mediaItem?.mediaId ?: return
            refresh()
            if (id == appliedId) return

            appliedId = id
            currentEpisodeId = id
            cancelRepeat()
            container.repository.find(id)?.let { container.repository.audioStore.touch(it) }
            loadEpisode(id)

            controller?.let { c ->
                c.setPlaybackSpeed(SpeedPolicy.speedOf(_ui.value.speedPercent))
                val startAt = container.playbackPositions.get(id)
                if (startAt > RESTORE_MIN_MS) c.seekTo(startAt)
            }
            _ui.update { it.copy(following = true) }
        }

        override fun onPlayerError(error: PlaybackException) {
            _ui.update {
                it.copy(
                    playing = false,
                    loading = false,
                    error = "播放失败，请稍后重试",
                )
            }
        }
    }

    init {
        // 250 ms 轮询。选这个间隔的依据：人眼对字幕跳变的容忍度约 200–300 ms，
        // 再密只是白耗电；再稀会出现「上一句还亮着、下一句已经念完」的滞后感
        viewModelScope.launch {
            while (isActive) {
                refresh()
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    // ------------------------------------------------------------------
    // 对外操作
    // ------------------------------------------------------------------

    /**
     * 打开某一期。
     *
     * 已经在播同一条时不重设媒体项 —— 用户从播放页退出去看列表、再进来，
     * 不应该从头开始（这是最容易做错、也最容易被用户骂的一处）。
     */
    fun start(episodeId: String) {
        DiagLog.act(
            "进入播放页 · ${container.repository.find(episodeId)?.title?.take(24) ?: episodeId}"
        )
        // 已经在播同一条且没有错误态：只把状态刷一遍。
        // 带 error 判断是为了让「上次播放失败 → 重新进入」能真的重试一次，
        // 否则用户会卡在一个只能看不能动的错误页上
        val c = controller
        if (currentEpisodeId == episodeId &&
            _ui.value.error == null &&
            c?.currentMediaItem?.mediaId == episodeId
        ) {
            refresh()
            return
        }
        currentEpisodeId = episodeId
        loadEpisode(episodeId)
        if (c != null) applyEpisode(c, episodeId) else ensureController()
    }

    fun togglePlay() {
        val c = controller ?: return
        DiagLog.act(if (c.isPlaying) "点暂停" else "点播放")
        if (c.isPlaying) {
            c.pause()
        } else {
            // 已经播到结尾时再按播放：从头来，否则会出现「点了没反应」
            if (c.duration != C.TIME_UNSET && c.currentPosition >= c.duration - 800) {
                c.seekTo(0)
            }
            c.play()
        }
    }

    fun seekTo(positionMs: Long) {
        cancelRepeat()
        controller?.seekTo(positionMs.coerceAtLeast(0L))
        refresh()
    }

    fun skipBy(deltaMs: Long) {
        val c = controller ?: return
        DiagLog.act("${if (deltaMs >= 0) "快进" else "快退"} ${kotlin.math.abs(deltaMs) / 1000}s")
        cancelRepeat()
        val dur = if (c.duration == C.TIME_UNSET) Long.MAX_VALUE else c.duration
        c.seekTo((c.currentPosition + deltaMs).coerceIn(0L, dur))
        refresh()
    }

    /** 点某一句话 → 跳到那句的开头。停着的时候点，跳过去仍然保持暂停 */
    fun seekToLine(index: Int) {
        val line = _lines.value.getOrNull(index) ?: return
        DiagLog.act("点字幕第 ${index + 1} 句")
        seekTo(line.startMs)
    }

    /** 上一句 / 下一句 */
    fun stepLine(delta: Int) {
        val lines = _lines.value
        if (lines.isEmpty()) return
        val cur = _ui.value.currentIndex
        val target = (if (cur < 0) 0 else cur + delta).coerceIn(0, lines.size - 1)
        DiagLog.act("${if (delta > 0) "下一句" else "上一句"} → 第 ${target + 1} 句")
        seekToLine(target)
    }

    /**
     * 精听：把当前这句用慢速重放一遍。
     *
     * 这是这个 App 最「学习向」的动作 —— 新闻里听不清的往往是某个连读或某个专有名词，
     * 需要的是「这一句再慢一点放一次」，而不是回去开场重听 30 秒。
     *
     * 慢速只在**这一句的区间内**生效，到句尾自动回到用户选的倍速，
     * 而不是停在句尾：通勤时手不方便再点一次播放，停住等于打断收听。
     */
    fun repeatCurrentLine() {
        val lines = _lines.value
        if (lines.isEmpty()) return
        val c = controller ?: return
        val index = _ui.value.currentIndex.coerceIn(0, lines.size - 1)
        val line = lines[index]
        DiagLog.act("精听本句 · 第 ${index + 1} 句")

        repeatUntilMs = line.endMs + REPEAT_TAIL_MS
        // 「慢速重听」必须真的比当前更慢。硬编码 0.7x 在用户已经把
        // 正常倍速调到 0.5x 时会**反而加快** —— 而按钮叫「慢速重听」。
        // 取用户设定与 0.7x 中更慢的那个，见 SpeedPolicy.slowPercentFor
        c.setPlaybackSpeed(SpeedPolicy.speedOf(SpeedPolicy.slowPercentFor(_ui.value.speedPercent)))
        // 直接操作播放器而不是走 seekTo()：seekTo 会先 cancelRepeat，
        // 正好把这里刚设好的精听状态清掉
        c.seekTo(line.startMs)
        if (!c.isPlaying) c.play()
        _ui.update { it.copy(repeating = true) }
        refresh()
    }

    /** 下一集 / 上一集。队列由 ExoPlayer 持有，这里只是驱动它 */
    fun nextEpisode() {
        val c = controller ?: return
        DiagLog.act("点下一集")
        cancelRepeat()
        if (c.hasNextMediaItem()) c.seekToNextMediaItem()
        refresh()
    }

    fun previousEpisode() {
        val c = controller ?: return
        DiagLog.act("点上一集")
        cancelRepeat()
        if (c.hasPreviousMediaItem()) c.seekToPreviousMediaItem()
        refresh()
    }

    /**
     * 倍速落盘的防抖任务。
     *
     * 拖动滑杆会连续经过十几格，每格都写一次 DataStore 是无意义的磁盘开销 ——
     * 只有最后停下的那个值需要被记住。
     * **播放器同步不防抖**（用户要即时听到速度变化，那正是拖动的意义），
     * 防抖只加在落盘这一侧。
     */
    private var speedPersistJob: Job? = null

    /** 松手后多久落盘。太长会在「拖完立刻杀掉 App」时丢设置，太短等于没防抖 */
    private val speedPersistDelayMs = 400L

    /**
     * 调倍速。入参是[整百分数][SpeedPolicy]，不在档位上的值会被归一化。
     *
     * 归一是**必须**的，不能指望调用方自觉：滑杆回传的是浮点数
     * （`54.999996` 这种），`roundToInt()` 之后仍可能落在档位之间。
     */
    fun setSpeedPercent(percent: Int) = applySpeed(percent, immediate = false)

    /**
     * 一键回到常速 1.0x。
     *
     * 单独一个方法而不是让界面直接传 [SpeedPolicy.PERCENT_DEFAULT]：
     * 这个动作的语义是「回到参照点」，不是「调到一个恰好的值」。
     * 把意图写进方法名，界面上那个按钮就不会被人顺手改成别的数字。
     *
     * **立刻落盘，不走防抖** —— 它是单击、没有连续回调；而且用户按它
     * 往往正是要「结束这次调节」，此刻把设置丢掉是最不该发生的。
     */
    fun resetSpeed() = applySpeed(SpeedPolicy.PERCENT_DEFAULT, immediate = true)

    private fun applySpeed(percent: Int, immediate: Boolean) {
        // 手动调倍速意味着「我要按自己的节奏听」，精听状态让位
        repeatUntilMs = 0L
        val normalized = SpeedPolicy.normalizePercent(percent)
        _ui.update { it.copy(speedPercent = normalized, repeating = false) }
        DiagLog.act("倍速 → ${SpeedPolicy.format(normalized)}")
        controller?.setPlaybackSpeed(SpeedPolicy.speedOf(normalized))

        speedPersistJob?.cancel()
        speedPersistJob = viewModelScope.launch {
            if (!immediate) delay(speedPersistDelayMs)
            container.settings.setPlaybackSpeedPercent(normalized)
        }
    }

    fun setFollowing(following: Boolean) {
        DiagLog.act("跟读朗读 → ${if (following) "开" else "关"}")
        _ui.update { it.copy(following = following) }
    }

    fun toggleFavorite() {
        val id = currentEpisodeId ?: return
        val now = container.favorites.toggle(id)
        DiagLog.act(if (now) "收藏这一集" else "取消收藏这一集")
        _ui.update { it.copy(favorite = now) }
    }

    /** 这一期在不在收藏里 —— 素材库也用它渲染星标，所以暴露出去 */
    fun isFavorite(episodeId: String): Boolean = episodeId in container.favorites.ids.value

    // ------------------------------------------------------------------
    // 中文对照
    // ------------------------------------------------------------------

    /**
     * 读一次中文对照；还没有的话起一个「等它到」的循环。**这是 2.1.0 重写的。**
     *
     * ## 改之前是什么样
     *
     * 它原来叫 `translateCurrent()`：在**本机**逐句翻译，一集 300 句要 1–3 分钟，
     * 而且那 2–3 分钟**全部计入用户盯着屏幕的时间**（字幕一到就开始翻，
     * 用户看着译文一行行长出来）。
     *
     * ## 现在为什么不需要「翻译」这个词了
     *
     * 服务器在发布英文 SRT 的**下一轮**会把中文一起发出来，客户端只做
     * 「取回来、校验句数、落盘」。所以这里的工作是**读盘 + 等**，不是算。
     *
     * ## 「等」为什么必须有，而且必须自己重试
     *
     * 用户点开一条时很可能正卡在那个时间差里（英文刚到、中文还没翻）。
     * 2.1.0 之前这段空窗由端侧翻译填上（发现没译文就自己翻）——
     * 那条路拆掉之后，如果这里只是「读一次、没有就算了」，
     * 表现就是**「中文对照时有时无」**：取决于用户点得快不快，
     * 而他退出重进一次可能就有了 —— 这种「灵异」现象最难被描述清楚。
     *
     * 所以要等：请求插队补一次，然后每 [TRANSLATION_POLL_MS] 回读一次盘，
     * 最长等 [TRANSLATION_WAIT_MS]。正常一两分钟内就会有。
     *
     * ## 等不到就安静收手，**不写失败**
     *
     * 超时说明服务器那边还没轮上。它是正常流程（与「字幕待补」同一类），
     * 下一轮自动补字幕会捡起来。弹一句红字「中文对照暂时不可用」，
     * 用户唯一能做的事是「等」——而他已经等了，那句话只是让他更焦虑。
     * 状态回到 Idle（界面上那一行消失），音频照常听。
     */
    fun refreshTranslation() {
        val id = currentEpisodeId ?: return
        val lineCount = _lines.value.size
        if (lineCount == 0) return

        waitJob?.cancel()
        waitJob = viewModelScope.launch {
            val episode = container.repository.find(id) ?: return@launch
            val store = container.repository.audioStore

            fun readFresh(): List<String?>? =
                runCatching { store.readTranslation(episode) }.getOrNull()
                    // ⚠️ 句数必须与字幕一致才采用。**重新取过一次字幕之后旧译文就会
                    //    整体错位**，而错位的译文比没有译文更糟：它看起来是对的，
                    //    只是每一句都对错了地方。这条校验从 1.2.x 起就在，别删。
                    ?.takeIf { it.size == lineCount }

            readFresh()?.let {
                _translations.value = it
                _translateStatus.value = TranslateUiStatus.Idle
                return@launch
            }

            // 没有译文。这条根本没字幕（或不需要译文）时不该显示任何等待提示
            _translations.value = List(lineCount) { null }
            if (!container.repository.needsSubtitles(episode)) {
                _translateStatus.value = TranslateUiStatus.Idle
                return@launch
            }

            // ★ 2.2.0：**先当场取一次**，再谈等待。
            //
            //   原来是「插队 + 每 4 秒回读一次盘」，但那条路要先等后台服务被拉起来、
            //   再跑完一轮同步 —— 用户看到的是「中文对照在来的路上…」挂在那儿一两分钟，
            //   而服务器那边其实早就做好了，差的就是一个 GET。
            //
            //   顺序是「直接取 → 读盘」，只有取不到才落到下面的等待循环。
            withContext(Dispatchers.IO) {
                runCatching { container.repository.serverCatalog.fetchSubtitles(episode) }
            }
            if (currentEpisodeId != id) return@launch
            readFresh()?.let {
                _translations.value = it
                _translateStatus.value = TranslateUiStatus.Idle
                return@launch
            }

            _translateStatus.value = TranslateUiStatus.Waiting
            // 插队：这一条是用户**此刻正在看**的，没理由排在队列后面
            runCatching { app.boostSubtitles(id) }

            val deadline = SystemClock.elapsedRealtime() + TRANSLATION_WAIT_MS
            while (isActive && SystemClock.elapsedRealtime() < deadline) {
                delay(TRANSLATION_POLL_MS)
                // 期间可能已经切集：换了就收手，别把别人的译文灌到这一屏上
                if (currentEpisodeId != id) return@launch
                readFresh()?.let {
                    _translations.value = it
                    _translateStatus.value = TranslateUiStatus.Idle
                    return@launch
                }
            }
            if (currentEpisodeId == id) _translateStatus.value = TranslateUiStatus.Idle
        }
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private fun loadEpisode(episodeId: String) {
        viewModelScope.launch {
            val episode = container.repository.find(episodeId)
            if (episode == null) {
                _ui.update { it.copy(loading = false, error = "这条内容已经不在素材库里了") }
                return@launch
            }

            _ui.update {
                it.copy(
                    episodeId = episodeId,
                    title = episode.title,
                    sourceName = episode.sourceName,
                    sourceId = episode.sourceId,
                    artworkUrl = episode.artworkUrl,
                    durationMs = episode.durationSec * 1000L,
                    favorite = isFavorite(episodeId),
                )
            }

            // 字幕解析放 IO：一集 150+ 句，正则匹配在 13 分钟的字幕上要几十毫秒，
            // 留在主线程会在进入播放页的瞬间掉一帧
            fun readLines(): List<SubtitleLine> {
                val raw = container.repository.audioStore.readSrt(episode)
                return if (raw.isNullOrBlank()) emptyList() else SrtTrack.parse(raw)
            }

            var parsed = withContext(Dispatchers.IO) { readLines() }

            // ★ 2.2.0：英文字幕在本地没有时**当场去服务器取一次**，不等后台那一轮。
            //
            //   为什么现在必须补这一步：音频改成「边下边播」之后，用户可以在
            //   **音频还没落盘**的时候点开任何一条 —— 而字幕原来是跟着音频一起
            //   在后台批处理里取回来的。于是出现一个新的空洞：
            //   「能立刻听了，但文稿是空的」，而播放页**只读一次盘**
            //   （下面的 `_lines.value = parsed`），它不会自己回来看第二眼。
            //
            //   这是一个请求、几百毫秒（SRT 只有几十 KB）。比等下一轮（半小时）强得多。
            //   ⚠️ 只在「缺」的时候发：`needsSubtitles` 是那个判据（版本号过期也算缺），
            //      取不到就安静放弃 —— 音频不需要字幕也能播，红字留给真正的问题。
            if (parsed.isEmpty() && container.repository.needsSubtitles(episode)) {
                val fetched = withContext(Dispatchers.IO) {
                    runCatching {
                        container.repository.serverCatalog.fetchSubtitles(episode)
                    }.getOrNull()
                }
                // ★★ 2.2.1：**取完之后必须把模型对齐**。
                //
                //   这一条是这轮真机 bug 的根：上面那句只写了**文件**，而列表那一行
                //   判的是**模型**里的 `srtFileName`。模型不更新，列表就一直写
                //   「字幕加载中」；而同步队列的判据看的是**文件**（不缺），
                //   于是谁也修不了它 —— 用户的原话是「都显示转写未加载，
                //   但实际上我已经拿到字幕了」（播放页里中英文都在）。
                //
                //   `syncSubtitleState` 是更新这件事的**唯一实现**，同步引擎那边
                //   也调它（见 `SyncEngine.alignSubtitleModel`）。
                //
                //   ⚠️ `markAsCurrentServerVersion` 只在**真的取到了**才敢传 true：
                //      取失败时盘上那份可能是旧版，盖章会让它永远不会被重取。
                withContext(Dispatchers.IO) {
                    container.repository.syncSubtitleState(
                        episodeId,
                        markAsCurrentServerVersion = fetched != null,
                    )
                }
                // 期间用户可能已经切到别的条目了
                if (currentEpisodeId != episodeId) return@launch
                parsed = withContext(Dispatchers.IO) { readLines() }
            }
            if (currentEpisodeId != episodeId) return@launch
            _lines.value = parsed

            // 顺带把已存的译文读出来。**句数必须与字幕一致才采用** ——
            // 重新取过一次字幕之后旧译文就会整体错位，而错位的译文比没有译文更糟：
            // 它看起来是对的，只是每一句都对错了地方。
            //
            // ⚠️ 2.1.0：读盘这一句从 `translateManager` 换成了 `audioStore`。
            //    译文文件的**位置和格式一个字都没变**（还是 zh/<id>.zh.json），
            //    变的是谁写它 —— 以前是端侧翻译逐句追加，现在是服务器整份发布。
            //    播放页这一侧本来就读的是文件，所以这里只是把「读盘」这件事
            //    直接暴露出来，不再借道一个已经不存在的中间层。
            val cached = runCatching { container.repository.audioStore.readTranslation(episode) }
                .getOrNull()
            _translations.value = if (cached != null && cached.size == parsed.size) {
                cached
            } else {
                List(parsed.size) { null }
            }
            // 一进来先按「没有中文」处理，随后 refreshTranslation 会决定
            // 是「已经有了」还是「正在等」
            _translateStatus.value = TranslateUiStatus.Idle

            // 沿用上次的倍速
            val percent = container.settings.playbackSpeedPercent.first()
            _ui.update { it.copy(speedPercent = percent) }
            controller?.setPlaybackSpeed(SpeedPolicy.speedOf(percent))
        }
    }

    private fun ensureController() {
        if (controller != null || connectRequested) return
        connectRequested = true

        val ctx = getApplication<Application>()
        val token = SessionToken(ctx, ComponentName(ctx, PlaybackService::class.java))
        val future = MediaController.Builder(ctx, token).buildAsync()

        future.addListener({
            connectRequested = false
            val c = runCatching { future.get() }.getOrElse { e ->
                _ui.update {
                    it.copy(loading = false, error = "暂时无法播放，重开 App 试试")
                }
                return@addListener
            }
            controller = c
            c.addListener(listener)
            // 连接建好后补做「打开某一期」—— 服务是异步起来的，
            // start() 里那次 ensureController() 只登记了意图
            currentEpisodeId?.let { applyEpisode(c, it) }
        }, ContextCompat.getMainExecutor(ctx))
    }

    private fun applyEpisode(c: MediaController, episodeId: String) {
        // 已经在播这一条：什么都别动，只把位置读回来
        if (c.currentMediaItem?.mediaId == episodeId) {
            _ui.update { it.copy(loading = false) }
            refresh()
            return
        }

        val token = ++applyToken
        viewModelScope.launch {
            // 队列要逐个检查音频文件是否真的在盘上 —— 索引里的状态位可能因为
            // 进程被杀/缓存清理而和文件系统不符，把不存在的音频塞进队列，
            // ExoPlayer 会在播到那一条时才报错，用户看到的是「莫名其妙跳了一下」
            //
            // ★ 2.2.0：把**正在打开的这一条**放行（本地没有就边下边播）。
            //   否则它会因为「不在队列里」直接报「音频文件不在了」——
            //   而那正是用户最常点的那些（懒加载没预取到的）。
            val queue = withContext(Dispatchers.IO) { buildPlayableQueue(episodeId) }
            if (token != applyToken) return@launch

            val index = queue.indexOfFirst { it.id == episodeId }
            if (index < 0) {
                _ui.update {
                    it.copy(loading = false, error = "这条内容暂时打不开，请回列表重新更新")
                }
                return@launch
            }

            // 顺序不能反：先登记 appliedId，setMediaItems 里同步派发的回调才不会误判
            appliedId = episodeId
            c.setMediaItems(queue.map { toMediaItem(it) }, index, C.TIME_UNSET)
            c.setPlaybackSpeed(SpeedPolicy.speedOf(_ui.value.speedPercent))
            c.prepare()

            // 断点续播。
            //
            // ⚠️ 这里踩过一个 API 坑：media3 早期版本可以在 `MediaItem.Builder` 上写
            //    `setStartPositionMs(startAt)`，但 1.11.1 已经把它移除了（编译期
            //    Unresolved reference），只剩下 `setClipStartPositionMs` —— 那是**裁剪**语义，
            //    会把音频前面的部分真的切掉，总时长随之变短，进度条显示就错了。
            //    正确做法是 prepare 之后单独 seekTo：媒体总时长保持完整。
            val startAt = container.playbackPositions.get(episodeId)
            if (startAt > RESTORE_MIN_MS) c.seekTo(startAt)
            c.play()

            _ui.update {
                it.copy(
                    loading = false,
                    error = null,
                    queueSize = queue.size,
                    queueIndex = index + 1,
                )
            }
            refresh()
        }
    }

    /**
     * 可播队列 = 索引里「音频真的在盘上」的那些，按 [PlaybackQueue] 的规则排序。
     *
     * 两步是分开的，别合并：
     *
     *  1. **筛**：两条判据，缺一不可。[Episode.isPlayable] 是索引里的字段
     *     （「这条曾经下载成功过」），`audioFile().isFile` 是文件系统的回答。
     *     只信前者，状态位可能因为进程被杀、缓存被清而和文件系统不符；
     *     只信后者，会在每次建队列时对几十个条目做 `isFile`（这里是 IO 线程，
     *     可以接受，但没必要）。把不存在的音频塞进队列，ExoPlayer 会在
     *     播到那一条时才报错 —— 用户看到的是「莫名其妙跳了一下」。
     *  2. **排**：交给 [PlaybackQueue]（未听完的在前）。这一条决定了
     *     「播完这一条之后自动放什么」，是用户这次提的那个问题。
     *
     * 排序时读的是 `PlaybackPositions.done` 的**当前快照**。队列只在
     * 「打开某一期」时建一次，所以播放过程中某条听完不会让队列当场重排。
     *
     * ## ★ 2.2.0：`allowStreamId` —— 只放正在打开的那一条进来
     *
     * 本地没有音频的条目**默认不进队列**（自动连播只在本地内容之间走，
     * 免得「听着一条、后台把整个列表都拉下来」）。但**用户此刻点开的那一条**
     * 必须能播 —— 它由 `allowStreamId` 单独放行，`toMediaItem` 会给它远程地址，
     * 于是「边下边播」生效。
     *
     * ⚠️ 只放行**这一条**，不放行「所有没下载的」：那等于把懒加载取消，
     *    而且会在用户没要求的时候偷偷跑流量。
     */
    private fun buildPlayableQueue(allowStreamId: String?): List<Episode> {
        val store = container.repository.audioStore
        val all = container.repository.episodes.value
        val local = all
            .filter { it.isPlayable }
            .filter { ep -> store.audioFile(ep).let { it.isFile && it.length() > 0L } }
        val localIds = local.mapTo(HashSet()) { it.id }
        val streaming = all.firstOrNull { it.id == allowStreamId && it.id !in localIds }
        val playable = if (streaming != null) local + streaming else local
        val done = container.playbackPositions.done.value
        val ordered = PlaybackQueue.order(playable, done)

        // 这一行是「自动连播放的是不是我还没听过的」在真机上**唯一的自证手段**：
        // 不用真的听上二十分钟，看一眼队列首条对不对就够了。
        // 队列只在「打开某一期」时排一次，所以它一天也就出现几十次，不吵。
        DiagLog.i(
            "Play",
            "连播队列：未听完 ${ordered.count { it.id !in done }} 条 · " +
                "已听完 ${ordered.count { it.id in done }} 条 · 共 ${ordered.size} 条" +
                if (streaming != null) " · 其中 1 条要边下边播" else "",
        )
        return ordered
    }

    /**
     * 取这一条的播放地址：**本地优先，否则直接给远程地址（边下边播）**。
     *
     * ## 为什么需要流式（2.2.0）
     *
     * 单集音频 **9–38 MB**（实测 22 条平均 9.5 MB，最大 37.6 MB），而跨境链路上
     * 手机侧实测远低于服务端峰值带宽。原来是「必须整个下完才能播」——
     * 一条 37 MB 的节目在慢链路上要等好几分钟，用户看到的是「点了没反应」。
     *
     * 交给 Media3 之后它是**流式**的：按 HTTP Range 边播边下。
     * 关键的数字对比：128 kbps 的 mp3 只要持续 **~16 KB/s** 就能流畅播放，
     * 而「下完再播」要求的是「在可接受的时间内下完 37 MB」——
     * 前者比后者低两个数量级。所以这一条改动直接把「慢」从「听不了」
     * 降级成「能听，只是不占本地缓存」。
     *
     * 拖动进度条也是 Range 请求，不会重新下。
     *
     * ## 判据必须落在**文件**上，不能落在状态位或 URL 上
     *
     * 用「文件存在且非空」而不是 `audioFileName != null`：`AudioStore` 只在
     * **整份下完**之后才把 `.part` 改名成正式文件（见 `AudioStore.download`），
     * 所以「文件在」就等于「这份是完整的」。拿状态位当判据的话，
     * 下到一半被杀留下的残档会被当成本地音频去播 —— 那是「能播、但中途断」。
     *
     * ⚠️ 反过来，判断「要不要流式」也不能只看 `audioUrl` 是不是 http：
     *    托管音频本来就是 http，而它**大多数时候已经在本地了** ——
     *    那就该走本地文件（离线可播、零流量）。
     */
    private fun toMediaItem(episode: Episode): MediaItem {
        val local = container.repository.audioStore.audioFile(episode)
        val uri = if (local.isFile && local.length() > 0L) {
            Uri.fromFile(local)
        } else {
            Uri.parse(episode.audioUrl)
        }
        return MediaItem.Builder()
            .setMediaId(episode.id)
            .setUri(uri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(episode.title)
                    .setArtist(episode.sourceName)
                    .setIsPlayable(true)
                    .build()
            )
            .build()
    }

    /** 把控制器的真实状态搬到 UI 状态里 */
    private fun refresh() {
        val c = controller ?: return
        val position = c.currentPosition.coerceAtLeast(0L)
        val rawDuration = c.duration
        val duration = if (rawDuration == C.TIME_UNSET || rawDuration < 0) 0L else rawDuration
        val lines = _lines.value

        tickRepeat(position)

        _ui.update {
            it.copy(
                loading = false,
                playing = c.isPlaying,
                buffering = c.playbackState == Player.STATE_BUFFERING,
                positionMs = position,
                // 时长优先信播放器；播放器还没解出来时先用 RSS 里给的时长垫着，
                // 否则进度条在头几秒是空的，看着像坏了
                durationMs = if (duration > 0) duration else it.durationMs,
                // 精听中**不动它**：那一刻播放器的真实速度是「压慢后」的值，
                // 写进来会让滑杆跟着滑走，而用户并没有碰过滑杆
                speedPercent = if (repeatUntilMs > 0L) {
                    it.speedPercent
                } else {
                    SpeedPolicy.percentOf(c.playbackParameters.speed)
                },
                currentIndex = SrtTrack.indexAt(lines, position),
                hasPrevious = c.hasPreviousMediaItem(),
                hasNext = c.hasNextMediaItem(),
                queueIndex = c.currentMediaItemIndex + 1,
                queueSize = c.mediaItemCount,
            )
        }

        persistPosition(position, duration)
    }

    /** 精听到句尾就自动退出慢速。放在轮询里做，不需要额外的定时器 */
    private fun tickRepeat(position: Long) {
        if (repeatUntilMs <= 0L) return
        if (position < repeatUntilMs) return
        repeatUntilMs = 0L
        controller?.setPlaybackSpeed(SpeedPolicy.speedOf(_ui.value.speedPercent))
        _ui.update { it.copy(repeating = false) }
    }

    private fun cancelRepeat() {
        if (repeatUntilMs <= 0L) return
        repeatUntilMs = 0L
        controller?.setPlaybackSpeed(SpeedPolicy.speedOf(_ui.value.speedPercent))
        _ui.update { it.copy(repeating = false) }
    }

    /**
     * 每 5 秒落一次盘。
     *
     * 播到结尾时清掉断点、改用「听完」标记 —— 下次进来从头开始（符合预期），
     * 但列表上还能看出这条听过了。
     */
    private fun persistPosition(position: Long, duration: Long) {
        val id = currentEpisodeId ?: return
        val now = System.currentTimeMillis()

        if (duration > 0 && position >= duration - 2000L) {
            if (!container.playbackPositions.isDone(id)) container.playbackPositions.markDone(id)
            lastPersistAt = now
            return
        }
        if (now - lastPersistAt < PERSIST_INTERVAL_MS) return
        lastPersistAt = now
        if (position > 3000L) container.playbackPositions.put(id, position)
    }

    override fun onCleared() {
        // 只断开控制器，**不销毁播放器** —— 播放器活在 Service 里，
        // 释放它会导致退出播放页音乐就断
        controller?.removeListener(listener)
        controller?.release()
        controller = null
        super.onCleared()
    }

    companion object {
        private const val POLL_INTERVAL_MS = 250L
        private const val PERSIST_INTERVAL_MS = 5_000L

        /** 小于这个位置不值得「续播」—— 通常只是刚开头听了几秒，直接从头更符合预期 */
        private const val RESTORE_MIN_MS = 5_000L

        /** 精听句尾的余量：留一点尾巴，避免最后一两个字被切掉听不完整 */
        private const val REPEAT_TAIL_MS = 500L

        /**
         * 等中文对照时，每隔多久回读一次盘（2.1.0）。
         *
         * 4 秒是「够快」与「别白读」之间的取舍：译文文件只有几十 KB，
         * 回读一次的代价可以忽略，但也不必更密 —— 服务器的翻译是整份发布的，
         * 不存在「一行行长出来」这回事，回读只可能有两种结果（还没有 / 全有了）。
         */
        private const val TRANSLATION_POLL_MS = 4_000L

        /**
         * 等中文对照最多等多久（2.1.0）。
         *
         * 服务器先发布英文、**下一轮**才发布中文（一轮 15 分钟），所以
         * 「等不到」是常有的事。5 分钟是按「用户在这一页停留多久」定的：
         * 通勤路上听一集就是十几分钟，5 分钟之内多半会出现；
         * 再长就没意义了 —— 继续轮询只是白耗电，而下一轮自动补字幕
         * 会在用户看不见的时候把它补上，下次点开就是现成的。
         */
        private const val TRANSLATION_WAIT_MS = 5 * 60 * 1000L
    }
}
