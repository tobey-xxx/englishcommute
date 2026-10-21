package com.example.englishcommute.sync

import android.content.Context
import com.example.englishcommute.content.ArtworkStore
import com.example.englishcommute.content.ContentPolicy
import com.example.englishcommute.content.ContentRepository
import com.example.englishcommute.content.Episode
import com.example.englishcommute.content.EpisodeStatus
import com.example.englishcommute.content.SourceResult
import com.example.englishcommute.diag.DiagLog
import com.example.englishcommute.diag.span
import com.example.englishcommute.word.VocabAddResult
import com.example.englishcommute.word.VocabAddSync
import com.example.englishcommute.word.WordTableLoader
import com.example.englishcommute.net.ServerCatalog
import com.example.englishcommute.player.NowPlaying
import com.example.englishcommute.settings.Favorites
import com.example.englishcommute.settings.PlaybackPositions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/** 同步阶段，UI 与通知栏共用一份文案依据 */
sealed interface SyncPhase {
    data object Idle : SyncPhase

    data object FetchingFeeds : SyncPhase

    /**
     * [episodeId] 不是可选的装饰 —— 列表要靠它把「这一条正在处理」认出来，
     * 从而把进度条画在**那条自己**下面。只用 [title] 匹配会在同名条目上出错
     * （新闻源的标题重复率比想象中高），而且标题是被截断过用于显示的。
     */
    data class Downloading(
        val episodeId: String,
        val title: String,
        val index: Int,
        val total: Int,
        val fraction: Float,
    ) : SyncPhase

    /**
     * 正在向服务器取这一条的字幕（2.1.0）。
     *
     * ## 为什么没有 fraction
     *
     * 它原来是 `Transcribing`，带一个 0f..1f 的进度 —— 那是给端侧识别准备的：
     * 一集 13 分钟的音频要跑好几块，每块完成推进一次。现在取字幕是一两个
     * HTTP 请求、几百毫秒的事，**没有可以填进那根进度条的中间状态**。
     * 留一个永远停在 0% 或永远在 100% 的进度条，比没有进度条更糟：
     * 它会让用户以为卡住了。
     *
     * 所以这里只报「第几条 / 共几条」，通知栏显示「取字幕 2/5」。
     */
    data class FetchingSubs(
        val episodeId: String,
        val title: String,
        val index: Int,
        val total: Int,
    ) : SyncPhase

    data object Cleaning : SyncPhase

    data class Done(
        val fetched: Int,
        val downloaded: Int,
        /**
         * 这一轮**拿到字幕**的条数（2.1.0 起）。
         *
         * ⚠️ 它原来是 `transcribed`。改名的理由不是洁癖：字幕现在**全部来自服务器**，
         * 叫「转写」会让「手机上是不是跑了识别」这个问题永远说不清 ——
         * 而 2.0 的真机反馈里就出现过这个误解。数字本身没变（都是「这一轮有几条有了字幕」），
         * 变的只是它敢不敢说自己是什么。
         */
        val subtitled: Int,
        /** 有音频、但字幕还没齐的条数（2.1.0 起叫 pendingSubs） */
        val pendingSubs: Int,
        /**
         * 本轮因保留期到期而清掉的条数（0 = 没有到期的）。
         *
         * 要报到界面上，是因为「内容自己消失了」在用户侧是最需要解释的一件事：
         * 不说的话，用户只会记得「我昨天还看到这一条」。
         * 有了这个数字，列表底部就能写「已按保留期清理 2 条」。
         */
        val expired: Int = 0,
        /**
         * 这一轮是什么时候跑完的（1.2.2 补）。
         *
         * 界面上那句「上次更新」原来**只有标题没有值** —— 用户看到的就是
         * 四个字后面空着。而 Done 里当时确实没有任何时间信息，
         * 所以不是渲染漏了，是数据里压根没有。
         *
         * 默认取当前时刻：两个构造点（完整同步收尾、补字幕收尾）都是
         * 「刚刚发生」，不需要各自显式传。
         */
        val at: Long = System.currentTimeMillis(),
    ) : SyncPhase {
        /**
         * 这一轮到底干成了什么。
         *
         * ## 为什么值得单独一个属性（1.2.5）
         *
         * 通知层要回答「这一轮值不值得报一条通知」。补字幕那一轮**不拉源**，
         * `fetched` 恒为 0 —— 拿它当判据的话，每次后台把字幕补齐都会发一条
         * 「更新完成 · 共 0 条可用」，而那是假话，用户连着报了三个版本。
         *
         * 判据只在这里写一次，界面与通知都从它派生。
         */
        val produced: Boolean
            get() = fetched > 0 || downloaded > 0 || subtitled > 0 || expired > 0
    }

    data class Failed(val message: String) : SyncPhase

    /**
     * 「只检查源」的结果 —— 不下载、不取字幕，只汇报每个源能不能拉到。
     *
     * 单独一个状态而不是复用 [Done]：两者语义差得远，
     * 复用会让界面显示「下载 0 条、取字幕 0 条」，看着像失败了。
     */
    data class SourceChecked(
        val ok: Int,
        val failed: Int,
        val totalItems: Int,
    ) : SyncPhase
}

data class SyncState(
    val running: Boolean = false,
    val phase: SyncPhase = SyncPhase.Idle,
    val sourceResults: List<SourceResult> = emptyList(),
)

/**
 * 内容同步引擎：拉内容目录 → 下载音频 → 取字幕 → 清理缓存。
 *
 * 被 [ContentSyncService]（后台/通知栏）和素材库界面（前台）共用，
 * 这样「用户点按钮」和「每日自动跑」走的是同一条代码路径，不会出现行为不一致。
 *
 * 并发保护用 [AtomicBoolean] 而不是 Mutex：重复触发时应当**立即返回**，
 * 而不是让调用方排队等上几分钟。
 *
 * ⚠️ 2.1.9 补了一句：立即返回**不等于**丢掉 —— 见 [rerunRequested]。
 *    因为现在多了一条不走前台服务的占用者（回到前台的静默预取），
 *    而「用户的明确动作不许静默丢弃」是铁律。
 *
 * ## 2.1.0：这个类瘦了一半
 *
 * 拆掉端侧识别 / 翻译之后，下面这些东西**整块消失**（连同它们带进来的复杂度）：
 *
 * | 删掉的 | 它当年为什么存在 |
 * |---|---|
 * | `AsrEngine` + `TranscribePipeline`（切块 / 静音点 / 断点续跑） | 在本机把音频转成字幕 |
 * | `keepModelFor` / `MODEL_IDLE_KEEP_MS` / `RETRY_RELEASE_MS` | 630 MB 识别模型的延迟释放与重试 |
 * | `modelReady` 门禁（三处） | 「没模型就别下音频 / 别插队 / 别自动转写」 |
 * | `TranscribeBudget`（条数 + 音频秒数双上限） | 端侧 2–3 倍实时，一轮只能做几条，得限流 |
 * | `AudioDecoder` / `StreamingResampler` | 解码与重采样 |
 *
 * 换来的是一句话：**「这条有没有字幕」现在等于「服务器有没有给它字幕」**，
 * 而这件事的失败原因只剩两种（还没轮到 / 连不上），见 [SubtitleOutcome]。
 */
class SyncEngine(
    private val appContext: Context,
    private val repository: ContentRepository,
    private val artworkStore: ArtworkStore,
    /**
     * 收藏。保留期清理要知道「哪些是用户明确说要留的」——
     * 这是整个删除逻辑里唯一一条「用户意图」依据，不能靠猜（比如推断星标状态）。
     */
    private val favorites: Favorites,
    /**
     * 播放进度。懒加载（2.1.9）需要它回答一件事：**「接下来 N 条」窗口往哪儿滑**。
     *
     * 判据是「已听完」而不是「听到第几秒」—— 后者每 250 ms 变一次，
     * 拿它当下载依据会让窗口在自己脚下动。
     */
    private val playbackPositions: PlaybackPositions,
    /**
     * 词表增量（3.0.1）。
     *
     * ⚠️ 它**不参与**「这一轮同步成功还是失败」的判断 —— 拉不到就拉不到，
     *    本地那份保持不变。见 [VocabAddResult] 三种情况各自的语义。
     */
    private val vocabAddSync: VocabAddSync,
    /**
     * 词表缓存。拉到新的增量后要让它**失效** —— 否则内存里还是旧表，
     * 日志说「+141 个新词」而用户查词的结果一个字节都没变。
     */
    private val wordTable: WordTableLoader,
    /**
     * 补一批缺读音的词（3.0.2）。返回真的补成功了几个。
     *
     * ## 为什么是回调而不是直接依赖
     *
     * `PronBackfill` 要 `WordStore` + `PronDownloader`，而这两个在容器里
     * 声明得比 `syncEngine` **晚**（属性按声明顺序初始化）。用一个 lambda
     * 延迟求值就绕开了这个顺序问题 —— 构造时只是把 lambda 存下来，
     * 真正读那两个属性发生在**调用时**，那时容器早就建完了。
     *
     * 这也是本项目已有的做法（`WordStore` 的 `onAudioOrphaned` 就是）。
     *
     * ## 为什么是「同步收尾」而不是「单独一个定时器」
     *
     * 补读音需要的是**网络可用**这一个条件，而同步收尾正是「刚才网络是通的」
     * 的权威时刻 —— 别处再判断一次网络只会造出第二份判据。
     *
     * 默认 `{ 0 }`：单测 / 其它构造点不需要关心它。
     */
    private val backfillPron: suspend () -> Int = { 0 },
) {

    private val busy = AtomicBoolean(false)

    private val _state = MutableStateFlow(SyncState())
    val state: StateFlow<SyncState> = _state.asStateFlow()

    val isRunning: Boolean get() = busy.get()

    /**
     * 干「不该挡住主流程」的活：补齐封面、以及任何将来的后台小任务。
     *
     * 2.1.0 之前它还兼职「延迟释放 630 MB 识别模型」（[keepModelFor]）——
     * 那个机制连着模型一起删掉了，因为已经没有任何东西要释放。
     */
    private val idleScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 用户此刻就要这一条。
     *
     * 与 [fetchPendingSubtitles] 的区别是**它不启动新一轮，只影响正在进行的那一轮**：
     * 跑批途中每处理完一条就查一次，命中就把这条换到下一个位置。
     *
     * 为什么需要这个「中途换人」的机制：打开 App 会自动起一轮预热取字幕，
     * 用户随即点开某条准备听 —— 那一刻引擎正好是忙的。如果只是简单地把请求
     * 丢掉或排到整轮之后，用户会盯着一条没有字幕的内容等上一整轮，
     * 而他真正要听的那条其实只要几百毫秒。
     */
    @Volatile
    private var pendingBoost: String? = null

    /** 点播放 / 点「补字幕」时调用。不阻塞，不保证立刻执行 */
    fun requestPriority(episodeId: String) {
        if (episodeId.isNotEmpty()) pendingBoost = episodeId
    }

    /**
     * 用户此刻就要这一条 —— 而它**连音频都还没下下来**（2.0.3）。
     *
     * ## 为什么必须有它
     *
     * 列表里点了没反应，是 2.0.3 真机上报的那个问题。音频没落地的条目
     * 过去在 `LibraryScreen` 里被 `if (canPlay) onPlay()` 静默丢掉：
     * 不进播放页、不弹提示、日志里也没有任何记录 —— 用户只能得出
     * 「这条音频无法播放」这个结论（而他连「其实是在下载」都看不到）。
     *
     * 更糟的是下载本身还有一道「更新窗口」（只补最近 [ContentRepository.DOWNLOAD_WINDOW_DAYS]
     * 天）：被窗口跳过的那条**永远不会**被下载，于是那一行是**永久**死的。
     * 而窗口的意图是「别一次下几十条陈内容」，不是「用户点了也不给下」。
     *
     * ## 与 [requestPriority] 的分工
     *
     * [requestPriority] 管「已经在跑的那一轮，把这一条换到前面」；
     * 这里管「下一轮下载，必须包含这一条，而且排在第一个」。
     * 两个都是**登记**，不启动任何东西 —— 启动由调用方（[ContentSyncService.start]）负责，
     * 因为那需要前台服务。
     */
    @Volatile
    private var pendingDownload: String? = null

    /** 点了一条「还不能播」的内容时调用。不阻塞，不保证立刻执行 */
    fun requestDownload(episodeId: String) {
        if (episodeId.isNotEmpty()) pendingDownload = episodeId
    }

    /**
     * 跑一次完整同步。正在跑时直接返回。
     *
     * **判定「要不要处理」以文件是否存在为准，而不是看数据库里的状态位** ——
     * 状态位可能因为进程被杀而停在 `Downloading`，文件系统才是真相。
     */
    /**
     * ★ 2.1.9：这一轮的**收尾后补做**请求。
     *
     * ## 为什么必须有它（一个真实的吞请求路径）
     *
     * 2.1.9 起「回到前台」会**静默**跑一轮预取（直接调本方法，不走前台服务）。
     * 于是有了这条很窄但真实的时序：
     *
     * ```
     *   打开 App → 静默预取开跑（引擎忙）→ 用户立刻点「更新今日内容」
     *            → 服务起来 → engine.run() 撞上 busy → 原来会直接 return
     *            → 用户点了没反应，而界面上是「正在获取今日内容」转一下就没了
     * ```
     *
     * 原来这个「忙就丢掉」是可以的：那时占用引擎的**只有**前台服务自己，
     * 而服务有 `queuedSync` 负责补做（见 ContentSyncService）。现在多了一条
     * **不走服务**的占用者，`queuedSync` 那条路覆盖不到它。
     *
     * 所以把「补做」上移到引擎这一层：**谁占了都无所谓，跑完自然会再跑一遍**。
     * 这比在调用方加等待循环干净得多 —— 也不用管有几种占用者。
     *
     * ⚠️ 只置位、不启动任何东西；置位后一定会在当前这一轮结束时被消费。
     */
    @Volatile
    private var rerunRequested = false

    suspend fun run() {
        if (!busy.compareAndSet(false, true)) {
            rerunRequested = true
            DiagLog.i("Sync", "已有同步在跑，本次请求登记为「收尾后补做」")
            return
        }

        val sp = span("Sync", "完整同步")
        DiagLog.i("Sync", "▶ 开始")
        try {
            // 整个流程跑在 IO 上。调用方是前台服务，它的 scope 挂在 Dispatchers.Main.immediate，
            // 而这里要做文件存在性检查、索引 JSON 落盘、状态更新 —— 留在主线程会卡 UI。
            do {
                rerunRequested = false
                withContext(Dispatchers.IO) {
                    runInternal()
                }
                if (rerunRequested) DiagLog.i("Sync", "补做被登记的同步（跑这一轮期间有人请求过）")
            } while (rerunRequested)
        } catch (e: Exception) {
            sp.fail(e)
            _state.update {
                it.copy(
                    running = false,
                    phase = SyncPhase.Failed(e.message ?: "同步失败"),
                )
            }
        } finally {
            busy.set(false)
        }
    }

    /**
     * 只跑「拉内容目录」这一步：不下载音频、不取字幕。
     *
     * 加这个入口的原因很具体 —— 上一轮排查「CBC 为什么拿不到」时，
     * 要看一次源的真实报错必须跑完整同步，而下载要几分钟；
     * 排查网络问题根本不需要等它。现在用户换网络 / 换 DNS 之后可以立刻复验，
     * 配合 [ContentRepository] 里带 IP 与异常类型的错误文案，截一张图就能定位。
     *
     * 副作用与完整同步一致：会刷新索引（新条目入库、失效源的历史条目清掉）。
     */
    suspend fun checkSources() {
        if (!busy.compareAndSet(false, true)) return
        try {
            _state.update { it.copy(running = true, phase = SyncPhase.FetchingFeeds) }
            val results = withContext(Dispatchers.IO) { repository.refreshFeeds() }
            _state.update {
                it.copy(
                    running = false,
                    sourceResults = results,
                    phase = SyncPhase.SourceChecked(
                        ok = results.count { it.error == null },
                        failed = results.count { it.error != null },
                        totalItems = results.sumOf { r -> r.found },
                    ),
                )
            }
        } catch (e: Exception) {
            _state.update {
                // ⚠️ 不把 `e.message` 甩给用户：那是「Unable to resolve host …」
                //    这类英文串，而它出现在**界面上**（FailedBlock 直接渲染这句话）。
                //    原始异常在 fetchFeed 的 span 里已经记过了，这里只说人话。
                it.copy(
                    running = false,
                    phase = SyncPhase.Failed("检查来源时出错了，稍后再试一次"),
                )
            }
        } finally {
            busy.set(false)
        }
    }

    private suspend fun runInternal() {
        _state.update { it.copy(running = true, phase = SyncPhase.FetchingFeeds) }

        val feedSpan = span("Sync", "拉取内容目录")
        val results = repository.refreshFeeds()
        feedSpan.done(
            results.joinToString(" · ") { r ->
                "${r.source.name}${if (r.error != null) "✗" else "${r.found}条"}"
            },
        )
        _state.update { it.copy(sourceResults = results) }

        // 顺手补齐缺失的封面（每源一张，11 个源合计约 2 MB）。
        //
        // 放在这里而不是界面里，是因为「确定有网」的时刻只有这里 ——
        // 界面可能在飞行模式下被打开。失败也不往上报：封面是装饰，
        // 没有它就显示占位块，界面上没有任何功能依赖它。
        //
        // ⚠️ **不能 await，这一点是修出来的。** 图床（`media.npr.org` /
        //    `image.simplecastcdn.com`）与 feed **不是同一个域**，而选源时
        //    验证可达性的只有 feed 的域。图床在大陆可能是黑洞（丢包而非拒绝），
        //    串行等下去的话，「一键更新」会在最前面白卡一两分钟才轮到下载 ——
        //    用户看到的就是「点了没反应」。封面是装饰，让它自己在后台慢慢下。
        idleScope.launch {
            runCatching { artworkStore.fetchMissing(repository.episodes.value) }
                .onFailure { DiagLog.w("Art", "封面补齐异常（不影响内容）：${it.message}") }
        }

        // 全部源都挂了就没有必要往下走（音频也拿不到）
        if (results.all { it.error != null }) {
            val detail = results.firstNotNullOfOrNull { it.error } ?: "未知错误"
            _state.update {
                it.copy(running = false, phase = SyncPhase.Failed("所有源都拉取失败：$detail"))
            }
            return
        }

        val all = repository.episodes.value.sortedByDescending { it.publishedAt }

        // 1) 缺音频的 → 按**懒加载**策略挑该预取的（2.1.9）。
        //
        //    挑的是**候选**而不是「索引里所有缺音频的条目」：索引最多留 40 条，
        //    其中没下过的会累积。旧规则是「最近 2 天内发布的都要」——
        //    实测每天新增素材约 320 MB，而真正会被听的只有几条，
        //    于是 **90% 的下载是白下的**（懒加载之前，服务端已经改成托管音频，
        //    所以每一份白下的都从我们自己的流量包里出）。
        //
        //    新规则见 ContentPolicy.selectPrefetch：接下来 N 条（未听完的）
        //    ∪ 收藏 ∪ 正在播的 ∪ 每源保底最新一条。
        //    ⚠️「每源保底」不能省 —— 它同时是**正确性**要求：周更源一旦不预取，
        //    就没有音频，而 purgeStaleIndex 会把「没音频且发布满 3 天」的条目
        //    清出索引 → 那个源整个消失在列表里。
        val candidates = all.filter { !repository.audioStore.hasAudio(it) }
        // ★ 2.0.3：用户在列表里**明确点过**的那一条，优先级高于更新窗口。
        //
        //   窗口的意图是「别一次下几十条陈内容」，不是「用户点了也不给下」——
        //   被窗口跳过的那条会**永远**下不下来（下一轮的年龄只会更大），
        //   于是它在列表里是一行永久点不动的死条目。
        //   铁律第一条：用户的明确动作不许静默丢弃。
        val askedDownload = pendingDownload.also { pendingDownload = null }
        val (forced, rest) = candidates.partition { it.id == askedDownload }
        if (askedDownload != null) {
            DiagLog.i(
                "Sync",
                if (forced.isEmpty()) {
                    "插队下载：点开的那条已经有音频了，不用再下"
                } else {
                    "插队下载：${forced.first().title.take(26)}（你点了它，越过预取范围）"
                },
            )
        }
        // ★ 2.2.0：**正在播的那一条还没落盘时，本轮只下它。**
        //
        //   边下边播之后多了一种新状态：「用户此刻在听的音频正在走网络」。
        //   这时若后台还照常预取另外三条，两者抢的是**同一条跨境链路** ——
        //   慢链路上表现就是播放卡顿（音频流被自己的下载挤掉）。
        //
        //   判据落在**文件**上（`hasAudio`），不落在「播放器是不是在流式」这种代理量上：
        //     ① 文件是事实，播放器状态是代理（本工程为此栽过四次，见纪律）；
        //     ② 它**自愈** —— 它下完之后判据自动为假，下一轮就恢复正常预取。
        //        拿「正在流式」当判据会死锁：那个 MediaItem 的 URI 在整个播放期间
        //        都不会变，于是「永远只下它、永远不下别的」。
        val playing = NowPlaying.episodeId?.let { id -> all.firstOrNull { it.id == id } }
        val playingNotLocal = playing != null && !repository.audioStore.hasAudio(playing)

        val plan = if (playingNotLocal) {
            DiagLog.i(
                "Sync",
                "正在播的这条还没落盘（边听边下）：本轮只下它，其余预取让路 · " +
                    playing.title.take(24),
            )
            // 只选它自己：`selectPrefetch` 那套（接下来 N 条 / 收藏 / 保底）本轮全部让路
            ContentPolicy.PrefetchSelection(selected = listOf(playing), skipped = rest)
        } else {
            ContentPolicy.selectPrefetch(
                candidates = rest,
                now = System.currentTimeMillis(),
                favoriteIds = favorites.ids.value,
                playingId = NowPlaying.episodeId,
                doneIds = playbackPositions.done.value,
            )
        }
        // distinctBy：上面那条「只下正在播的」可能与 forced（用户插队）重合
        val needDownload = (forced + plan.selected).distinctBy { it.id }
        if (!playingNotLocal) {
            DiagLog.i(
                "Sync",
                "懒加载预取：${plan.selected.size} 条（接下来 ${ContentRepository.PREFETCH_COUNT} 条未听完的" +
                    " + 收藏 + 正在播的 + 每源保底各一条），" +
                    "其余 ${plan.skipped.size} 条留着按需下载（缺音频的共 ${candidates.size} 条）",
            )
        }

        // 2) ★★ 2.2.0：**先把字幕取回来，再下音频。**（顺序和 2.1.0 是反的）
        //
        //    ## 为什么反过来
        //
        //    原来（2.1.0）是「下一条音频、紧跟着取那一条的字幕」。它在当时是对的：
        //    字幕只能跟着音频走，而且端侧那会儿还要转写几分钟。
        //    但今天有两个事实把它推翻了：
        //
        //      · 取字幕是**几十 KB 的 HTTP**（几毫秒到几百毫秒），音频是 **9–38 MB**。
        //        让便宜的东西排在贵的东西后面，等于让「最该先有的」等「最慢的」。
        //      · 音频改成**边下边播**之后，用户可以在音频还没落盘时点开任何一条 ——
        //        那一刻他要的是**文稿**，而文稿压根还没开始取。
        //
        //    所以：先一次性把**所有**缺字幕的条目补齐，再去下音频。
        //    用户点完更新一秒内，整个列表都能看全文；音频该怎么慢还怎么慢，
        //    但它已经不影响「能不能立刻开始听」了。
        //
        //    ⚠️ 全量（而不是只取本轮要下的那几条）：字幕便宜到可以这么干
        //       （实测全部 49 条约 2–4 MB），换来的是「点任何一条都有文稿」——
        //       包括那些没被预取、要靠边下边播的。
        //
        //    ⚠️★ 这里必须调**不加锁**的 `transcribeQueue`。上一版调的是
        //       `fetchPendingSubtitles`（加锁版），而 `run()` 全程持锁 ——
        //       于是它每轮都在第一行 `compareAndSet` 失败、打一句「引擎占用中」
        //       就返回，这段逻辑**从来没有执行过**。见 transcribeQueue 的说明。
        //
        //    ⚠️ 阶段状态不在这里设：`SyncPhase.FetchingSubs` 是**逐条**的
        //       （带 episodeId / title / index / total），由 `fetchSubtitlesFor`
        //       自己设。在这一层设一个「批」级别的话类型对不上，而且界面
        //       那行「加载字幕 3/5」会失去每一条的名字。
        val subsFetched = runCatching { transcribeQueue(NowPlaying.episodeId) }
            .getOrElse { e ->
                DiagLog.w("Sync", "字幕先行失败（不影响音频）：${e.message}")
                0
            }
        var subtitled = subsFetched
        if (subsFetched > 0) {
            DiagLog.i("Sync", "字幕先行：本轮补上 $subsFetched 条（音频随后再下）")
        }

        // 3) 预取音频。字幕刚才已经全量取过，所以下面这个 `fetchSubtitlesFor`
        //    绝大多数情况会直接命中「都齐了」而**不发请求**；
        //    留着它只是兜底 —— 音频下完的那一刻，服务器可能刚好把这一期的字幕也做好了。
        var downloaded = 0
        // 服务器一旦不可达，后面每一条都会撞同一堵墙（每次要等完 connectTimeout）——
        // 记下来，把剩下的下载完就好，字幕留给下一轮
        var serverDown = false
        val dlTotal = needDownload.size

        needDownload.forEachIndexed { i, ep ->
            val done = downloadOne(ep, i + 1, dlTotal) ?: return@forEachIndexed
            if (!repository.audioStore.hasAudio(done)) return@forEachIndexed
            downloaded++

            // ── 音频一到位就把服务器字幕取回来 ──────────────────────────────
            // 取到了这一条就**完事**。取不到（服务器还没处理这一期 / 网络抖）
            // 就留在「待补」里 —— 下一轮自愈，见 SubtitleOutcome.NotYet。
            if (serverDown) return@forEachIndexed
            when (fetchSubtitlesFor(done, downloaded, dlTotal)) {
                is SubtitleOutcome.Got -> subtitled++
                SubtitleOutcome.ServerDown -> serverDown = true
                else -> Unit
            }
        }
        if (serverDown) {
            DiagLog.w("Sync", "服务器不可达，本轮余下条目不再试取字幕（音频照下）")
        }

        // 4) 清理。
        //
        //    顺序不能反：先按保留期删音频（它需要知道谁在播、谁被收藏），
        //    再跑容量兜底。反过来也能跑，但日志里会分不清「是保留期清的
        //    还是容量清的」—— 而这两个数字的增长趋势是完全不同的信号。
        _state.update { it.copy(phase = SyncPhase.Cleaning) }
        val expired = repository.expireOldAudio(
            now = System.currentTimeMillis(),
            keepIds = retentionKeepIds(),
        )
        // 删完必须修剪播放队列：ExoPlayer 手里那份队列是**点开某一条时快照**下来的，
        // 里面排着刚被删掉的文件。不摘掉的话，自动连播到它就是
        // 「播放失败：文件不存在」—— 而且发生在一个用户完全没操作的时刻。
        NowPlaying.prunePlaylist(expired.ids)

        // 收藏要传进去（1.2.0 修）：容量兜底原先不认收藏，于是缓存超过 1 GB 时
        // 用户收藏的音频会被 LRU 删掉 —— 而紧挨着的保留期那条路径
        // （retentionKeepIds）一直是保护收藏的。同一个问题两条路径答案相反
        repository.enforceCacheLimit(favorites.ids.value)

        // 「待补字幕」直接问文件系统，不从本轮计数推。本轮没排上的、
        // 上一轮留下的，它都算得对
        val pendingSubs = repository.pendingSubtitleCount()
        val total = repository.episodes.value.size

        // 词表增量（3.0.1）。
        //
        // 放在**最后**：它不影响素材本身，晚十几毫秒用户毫无感知；
        // 而放在中间会拖慢「更新完成」那一刻。
        val vocabNote = syncVocabAdd()

        // 读音补漏（3.0.2）。
        //
        // 放在**同步的最后、日志之前**：用户加完词就退出的那种情况，
        // 下一次同步完成时读音就已经在本地了 —— 而不是等他某天点开生词页
        // 才发现「这个词一直没声音」。
        //
        // ⚠️ 它是**尽力而为**的：内部对每个词都吞掉失败（见 `PronBackfill.run`），
        //    所以这里不需要 try —— 而**不该**给它套 try 再记一条「补读音失败」：
        //    缺读音是常态（服务端还在补），每次都记会变成噪音。
        val pronNote = backfillPron().let { if (it > 0) " · 读音补下 $it 个" else "" }

        DiagLog.i(
            "Sync",
            "◀ 结束 · 下载 $downloaded/${needDownload.size}" +
                " · 有字幕 $subtitled 条（含先行那 $subsFetched 条）" +
                " · 仍有 $pendingSubs 条待补字幕" +
                " · 保留期清理 ${expired.count} 条（${expired.bytes / 1048576} MB）" +
                " · 共 $total 条" + vocabNote + pronNote,
        )
        _state.update {
            it.copy(
                running = false,
                phase = SyncPhase.Done(
                    fetched = total,
                    downloaded = downloaded,
                    subtitled = subtitled,
                    pendingSubs = pendingSubs,
                    expired = expired.count,
                ),
            )
        }
    }

    /**
     * 拉一次词表增量。返回一句给日志用的后缀（没有变化时是空串）。
     *
     * ## 三种结果各自怎么处理
     *
     * | 结果 | 做什么 | 日志 |
     * |---|---|---|
     * | `Updated` | **让词表缓存失效** + 报「+N 个新词」 | act（用户可见的动作） |
     * | `UpToDate` | 什么都不做 | 空（不吵） |
     * | `Unreachable` | 什么都不做 —— **本地那份保持不变** | 空（[VocabAddSync] 内部已经记过 warning） |
     *
     * ⚠️ `Unreachable` 时**在界面上什么都不说**是刻意的：
     *    拉不到增量不影响用户此刻的任何操作（内置表照常工作），
     *    而每次同步都弹一句「词表没更新」只会变成噪音。
     *    它进日志就够 —— 真出问题时那是唯一能查的地方。
     */
    private suspend fun syncVocabAdd(): String = when (val r = vocabAddSync.sync()) {
        is VocabAddResult.Updated -> {
            // ⚠️ 这一行是**必需**的，不是优化。
            //    不失效的话，内存里那份 WordTable 还是旧表 ——
            //    日志说「+141 个新词」，而用户查一个刚下发的词仍然显示「暂未收录」。
            //    那种错看起来像「同步没生效」，会让人去查网络。
            wordTable.invalidate()
            // ⚠️ 这里**不能写「词表」** —— `verify-copy-audit.py` 会拦。
            //    它的边界是「DiagLog 调用行内」，而这个字符串是从辅助函数
            //    **返回**回去再拼进日志的，所以它看起来像一句普通的界面文案。
            //    这不是审计错了，是它有意保守：漏过一句诚实的文案，
            //    比放过一句「词表」安全。所以改措辞，而不是放宽断言。
            " · 新词 +${r.newWords} 个"
        }
        is VocabAddResult.UpToDate -> ""
        is VocabAddResult.Unreachable -> ""
    }

    /**
     * 保留期清理不能碰的条目。
     *
     * 三类，缺一类都会出事：
     *
     * | 类别 | 漏了会怎样 |
     * |---|---|
     * | 收藏 | 用户明确说过要留的东西被删 —— 这是唯一不可原谅的一类 |
     * | 正在播 | 文件句柄还开着，ExoPlayer 当场报错（听一半自己断了） |
     * | 进行中 | 正在下载的条目，文件被抽走会让流水线崩在半路 |
     *
     * 不含「已听完」：听过的内容恰恰是最该在三天后清掉的，
     * 用户要留会自己点星标。
     */
    private fun retentionKeepIds(): Set<String> = buildSet {
        addAll(favorites.ids.value)
        NowPlaying.episodeId?.let { add(it) }
        repository.episodes.value.filter { it.status.isBusy }.forEach { add(it.id) }
    }

    /**
     * 把「有音频、字幕没齐」的条目补一轮。**返回本轮是否真的补上了至少一条。**
     *
     * ## 「没齐」是两种情况，一句话说就是「服务器有的、本地还没有」
     *
     * | 情况 | 判据 | 这一轮做什么 |
     * |---|---|---|
     * | 连英文字幕都没有 | `!hasSrt` | 取下 SRT（顺便把中文也取回来） |
     * | 有英文、没有中文 | `hasSrt && !hasTranslation && remoteZh != null` | 再取一次（**中文比英文晚，这是常态**） |
     *
     * ⚠️ 第二种是 2.1.0 **必须补上**的一条，不是锦上添花。
     *
     *    服务器的 `zh` 比 `srt` 晚一轮（它先转写、下一轮才翻译）。而客户端
     *    原来的写法是「有 srt 就不再取」—— 于是**在服务器翻译完成之前点开过
     *    这一期的用户，永远只有英文**。2.1.0 之前这条空洞被端侧翻译兜住了
     *    （播放页发现没有译文就自己翻），拆掉端侧之后**兜底就不存在了**。
     *    少了这条判据，表现是「中文对照时有时无」，而且看不出规律。
     *
     * ## 服务器连不上就立刻收手
     *
     * 一轮里有十几条待补时，逐条去撞同一个连不上的地址没有意义 ——
     * 每次都要等完 connectTimeout，用户看到的是「更新跑了好几分钟什么都没干」。
     * 所以第一条报 [SubtitleOutcome.ServerDown] 就中断整轮
     * （对外表现是「本轮无进展」，接力循环据此停止）。
     *
     * @param priorityId 用户的明确意图（正在播的 / 刚点那条），插到队首。
     */
    suspend fun fetchPendingSubtitles(priorityId: String? = null): Int {
        if (!busy.compareAndSet(false, true)) {
            // 已经有一轮在跑。**不能打断**，但也不能把这次请求丢掉 ——
            // 记成中途插队，让正在跑的那一轮处理完当前这条就把用户要的换到前面
            if (priorityId != null) requestPriority(priorityId)
            DiagLog.i("Sync", "引擎占用中，已登记中途插队")
            return 0
        }
        try {
            // 用户是否**明确点了**这一条（而不是「回到前台顺手补一轮」）。
            // 区别只在于插到队首 —— 2.1.0 之后没有时长上限这回事了，
            // 服务器对每一期都会转写，长的短的都一样
            val asked = priorityId ?: pendingBoost.also { pendingBoost = null }

            return withContext(Dispatchers.IO) {
                runCatching { transcribeQueue(asked) }
                    .getOrElse { e ->
                        DiagLog.w("Sync", "取字幕跑批失败：${e.message}")
                        0
                    }
            }
        } finally {
            busy.set(false)
        }
    }

    /**
     * 待补字幕队列跑一轮。
     *
     * ## 「待补」的判据是文件，不是状态位
     *
     * 条件是「有音频文件、且字幕不齐」（见 [ContentRepository.pendingSubtitleCount]）。
     * 用它而不是 `status == Downloaded`，是因为状态位会撒谎：进程被杀时它会停在
     * `Downloading`，而文件系统不会。用文件判定还有个好处 —— 崩过一次之后
     * **自动就恢复了**，不需要额外的对账步骤（`reconcileInterruptedState`
     * 只负责让界面显示正确，这里才是让它真的被重做的那个地方）。
     *
     * ## 2.1.0 删掉了额度机制
     *
     * 它原来有「一轮最多 3 条 / 15 分钟音频」两个上限（[TranscribeBudget]）。
     * 那两个数字是**被端侧识别的速度逼出来的**：一集 13 分钟的音频要跑 4–6 分钟，
     * 不限流的话一轮就跑不完、前台服务的可靠窗口也撑不住。
     *
     * 现在取一条字幕是几百毫秒的 HTTP 请求，十几条也就十几秒 ——
     * 留着一个「一轮只做 3 条」的刹车，只会让用户明明点了更新却还有 7 条没字幕。
     * **需求是「点完就该有」，而现在做得到。**
     *
     * ## ⚠️★ 2.2.0：它**不加锁**，而且**不许**改成加锁
     *
     * 唯一的加锁入口是 [fetchPendingSubtitles]。这一条踩过坑：`runInternal`
     * （在 `run()` 持锁期间跑）调用的是 **加锁版**，于是每轮都在第一行就
     * `compareAndSet` 失败、打一句「引擎占用中」直接返回 ——
     * 结果就是 `runInternal` 里那段「补齐之前留下的字幕」**从来没有执行过**，
     * 而日志里那句看起来还像是正常的并发保护。
     *
     * 所以分工写死：**持锁期间调 [transcribeQueue]，不持锁才调
     * [fetchPendingSubtitles]**。（返回值也顺带从 `Boolean` 改成条数 ——
     * 「补了几条」才是调用方真正要报给用户的东西。）
     */
    private suspend fun transcribeQueue(priorityId: String?): Int {
        val all = repository.episodes.value.sortedByDescending { it.publishedAt }
        val missing = all.filter { repository.needsSubtitles(it) }
        if (missing.isEmpty()) return 0

        // 排序：用户明确点的那条第一，其余按发布时间新 → 旧。
        // 用「新 → 旧」而不是索引顺序，是因为列表就是这么排的 ——
        // 用户最先看到的那条，也是最可能马上要听的那条
        val queue = ArrayList(
            missing.sortedWith(
                compareByDescending<Episode> { it.id == priorityId }
                    .thenByDescending { it.publishedAt }
            )
        )

        val hit = priorityId != null && queue.any { it.id == priorityId }
        DiagLog.i(
            "Sync",
            "补字幕队列 · 待补 ${queue.size} 条" +
                if (priorityId != null) " · 插队${if (hit) "命中" else "未命中"}" else "",
        )

        var done = 0
        var i = 0
        while (i < queue.size) {
            // 中途插队：只在「还没轮到」的时候换（j > i），已经处理过的不能再退回去
            val boost = pendingBoost
            if (boost != null && boost != queue[i].id) {
                val j = queue.indexOfFirst { it.id == boost }
                if (j > i) {
                    DiagLog.i("Sync", "中途插队：${queue[j].title.take(24)} 换到第 ${i + 1} 位")
                    val tmp = queue[i]
                    queue[i] = queue[j]
                    queue[j] = tmp
                }
                pendingBoost = null
            }

            val ep = queue[i]
            val fresh = repository.find(ep.id) ?: ep
            // ★ 2.2.0：**这里原来有一条「音频不在就跳过」的判断，已删。**
            //
            //   它的前提是「字幕只在音频下完之后才取」。而现在：
            //     · 音频可以**边下边播**（用户能在没落盘时点开任何一条）；
            //     · 取字幕是几十 KB 的 HTTP，跟音频的 9–38 MB 不是一个量级。
            //   留着它的后果很具体：一条没预取到的内容，用户点开后音频流式播起来了，
            //   而文稿永远是空的 —— 因为「补字幕队列」会绕开它。
            //
            //   `needsSubtitles` 里那句 `hasAudio` 前置条件也已经一起去掉了，
            //   两处必须同进同退（只改一处 = 队列里排了它、判据说它不缺）。
            when (fetchSubtitlesFor(fresh, i + 1, queue.size)) {
                is SubtitleOutcome.Got -> done++
                // 服务器连不上 → 后面每一条都会一样，收手。
                // 剩下的留给下一轮（服务器恢复后自然补上）
                SubtitleOutcome.ServerDown -> {
                    DiagLog.w("Sync", "中止余下取字幕：服务器连不上")
                    break
                }
                // 还没轮到 / 这一条取失败：都不是「停手」的理由，
                // 后面几条很可能服务器已经处理好了
                else -> Unit
            }
            i++
        }
        return done
    }

    private suspend fun downloadOne(episode: Episode, index: Int, total: Int): Episode? {
        repository.update(episode.copy(status = EpisodeStatus.Downloading, error = null))
        _state.update {
            it.copy(
                phase = SyncPhase.Downloading(
                    episodeId = episode.id,
                    title = episode.title,
                    index = index,
                    total = total,
                    fraction = 0f,
                )
            )
        }

        val dlSpan = span("Sync", "下载 [${index}/${total}]")
        val result = repository.downloadEpisode(episode) { p ->
            _state.update {
                it.copy(
                    phase = SyncPhase.Downloading(
                        episodeId = episode.id,
                        title = episode.title,
                        index = index,
                        total = total,
                        fraction = p.fraction,
                    )
                )
            }
        }
        return if (repository.audioStore.hasAudio(result)) {
            dlSpan.done("${repository.audioStore.audioFile(result).length() / 1048576} MB")
            result
        } else {
            dlSpan.fail(null, result.error ?: "落盘后仍未找到文件")
            null
        }
    }

    /**
     * 给一条内容把字幕弄到位。四种结果见 [SubtitleOutcome]。
     *
     * ## 它为什么是「取」而不是「转写」
     *
     * 2.1.0 起字幕**全部来自服务器**（它每 15 分钟一轮，对每个源先转写好再发布）。
     * 所以这个函数做的是「把服务器已经做好的东西拿回来」，一个请求、几百毫秒。
     * 端侧识别那条路已经整条拆掉了 —— 那 630 MB 的模型在这台手机上
     * **从来没有装过**（每轮日志都写着「本机未装识别模型」），也就是说
     * 那条兜底路径从来没有真正生效过一次。
     *
     * ## 为什么两种「拿不到」必须分开说
     *
     * [SubtitleOutcome.NotYet] 与 [SubtitleOutcome.ServerDown] 的**下一步完全相反**：
     * 前者用户什么都不用做（NPR News Now 是每小时一集，而服务器按定时任务跑，
     * 「最新一期暂时没字幕」是结构性的、每次都会有），后者要么等服务器恢复、
     * 要么检查网络。把它们说成一句，用户就会被指去做一件没用的事 ——
     * 2.0.1 真机上报的正是这个。
     */
    /**
     * 「字幕文件在盘上、但模型里没记下来」时，把模型对齐到磁盘。
     *
     * 实现只有一处 —— [ContentRepository.syncSubtitleState]；这里加一层只是为了**日志**，
     * 好让「列表为什么从『字幕加载中』变回『字幕 N 句』」在真机日志里有一条凭据。
     * （模型的变化本身是**静默**的，界面上看不出是谁改的。）
     *
     * ⚠️ 调用点必须是**已经确认文件真的落盘**的地方。别在函数入口随手加：
     *    那样在「服务器换了新一版、但这次下载失败了」的情况下会把本地旧文件
     *    当成最新的，从此再也不会被重取。
     */
    private fun alignSubtitleModel(episode: Episode) {
        if (repository.syncSubtitleState(episode.id)) {
            DiagLog.i(
                "Sync",
                "字幕状态已对齐（文件在盘上、模型漏记）· ${episode.title.take(24)}",
            )
        }
    }

    private suspend fun fetchSubtitlesFor(
        episode: Episode,
        index: Int,
        total: Int,
    ): SubtitleOutcome {
        val store = repository.audioStore
        val hadSrt = store.hasSrt(episode)
        // ★★ 2.2.1：服务器换过一版字幕（重新切分过，`srtVer` +1）⇒ 本地那份**必须重下**。
        //
        //   这条判据本身是 2.1.1 加的，但它**一直没有真正生效过** ——
        //   下面那两个「已经有了就不动」的分支排在它前面，`hadSrt && hasTranslation`
        //   会先返回，于是「服务端重切分」对老用户等于没发生过（本地永远停在旧版）。
        //   ⚠️ 顺序就是实现：判据写在后面 = 判据不存在。
        val srtStale = episode.remoteSrtVer > episode.srtVerOnDisk

        // 目录里没有这一期的字幕路径 ⇒ 服务器还没处理到它。
        //
        // ⚠️ 这条判据同时覆盖了「走了 RSS 退路」的情况（服务端不可达时索引里的
        //    条目没有 remoteSrt）—— 那种情况的正确说法也是「稍后」，
        //    因为下一轮服务器恢复之后它自然就有了。
        if (episode.remoteSrt == null) {
            DiagLog.w(
                "Sync",
                "☁ 服务器还没处理这一期（太新），字幕稍后自动补 · ${episode.title.take(24)}",
            )
            markPending(episode, serverDown = false)
            return SubtitleOutcome.NotYet
        }
        // 已经有英文、而服务器也还没生成中文 ⇒ 无事可做，下一轮再来看。
        // 单独一条日志：它和「连英文字幕都没有」在排查时是完全不同的两件事
        if (hadSrt && !srtStale && episode.remoteZh == null && !store.hasTranslation(episode)) {
            alignSubtitleModel(episode)
            DiagLog.i("Sync", "☁ 译文还没生成，下一轮再补 · ${episode.title.take(24)}")
            return SubtitleOutcome.NotYet
        }
        // 都齐了、而且不是过期的版本 ⇒ **不下载**，但要把模型对齐。
        //
        // ★★ 2.2.1：这里原来直接 `return`，于是留下一个**两边都不管**的死角 ——
        //   字幕有两条写文件的路径（这里，以及**播放页**用户点开时当场取一次），
        //   而「模型里的 srtFileName」只在成功下载那一条上被更新。
        //   播放页写过文件之后，本函数的判据（看**文件**）说「不缺」、直接返回，
        //   而列表看的是**模型**（srtFileName == null）→ 一直显示「字幕加载中」。
        //   真机上用户的原话：「都显示转写未加载，但实际上我已经拿到字幕了」。
        if (hadSrt && srtStale) {
            // 这一行是「服务端重切过一次字幕」在真机上唯一的凭据。
            // 2.1.1 加了版本号机制，但下面那两个早退排在前面把它吞掉了 ——
            // 于是这件事从来没有真正发生过（老用户的字幕永远停在旧版）。
            DiagLog.i("Sync", "☁ 服务器换过一版字幕，重取 · ${episode.title.take(24)}")
        }
        if (hadSrt && !srtStale && store.hasTranslation(episode)) {
            alignSubtitleModel(episode)
            return SubtitleOutcome.NotYet
        }

        _state.update {
            it.copy(phase = SyncPhase.FetchingSubs(episode.id, episode.title, index, total))
        }

        val subs = runCatching { repository.serverCatalog.fetchSubtitles(episode) }
            .getOrElse { e ->
                // 取字幕是「把已经做好的东西拿回来」，它失败**不该**让整轮同步判失败：
                // 用户看到的会是一条「更新失败」，而实际上他只是暂时少一条字幕。
                // 所以这里绝不让异常冒出去，只记一行。
                DiagLog.w("Sync", "☁ 取字幕异常（${e::class.simpleName}）：${episode.title.take(24)}")
                null
            }

        if (subs == null) {
            // 分岔判据：目录这一层拿没拿到过。
            // ⚠️ 只有「连目录都没拿到」才是「服务器不可达」—— 目录拿到了、
            //    只是这个文件 404，属于服务端状态不一致，下一轮同样会自愈，
            //    说成「服务器连不上」是错的。
            val down = !repository.serverCatalog.lastCatalogOk
            DiagLog.w(
                "Sync",
                if (down) {
                    "☁ 服务器不可达，字幕本轮拿不到 · ${episode.title.take(24)}"
                } else {
                    "☁ 服务器有这一期、但字幕没取到，下一轮重试 · ${episode.title.take(24)}"
                },
            )
            markPending(episode, serverDown = down)
            return if (down) SubtitleOutcome.ServerDown else SubtitleOutcome.Failed("字幕没取到")
        }

        DiagLog.i(
            "Sync",
            "☁ 取字幕 ${subs.segmentCount} 句${if (subs.hasZh) " + 中文" else "（中文待补）"}" +
                " · ${episode.title.take(24)}",
        )
        repository.update(
            episode.copy(
                status = EpisodeStatus.Ready,
                srtFileName = subs.srtFileName,
                segmentCount = subs.segmentCount,
                // ★ 记下「本地这份是服务器哪一版」（2.1.1）。
                //   它是 needsSubtitles 里那条版本判据的另一半 ——
                //   不写这一行，本地版本永远停在 0，服务端每抬一次版本
                //   就会让这一条被反复重下，而且永远「刚下完就显示还要下」。
                srtVerOnDisk = episode.remoteSrtVer,
                error = null,
            )
        )
        return SubtitleOutcome.Got(
            segmentCount = subs.segmentCount,
            withZh = subs.hasZh,
            zhOnly = hadSrt,
        )
    }

    /**
     * 字幕暂时没齐时给索引留的痕迹。
     *
     * ★★ 2.0.3 修过这里的 **[EpisodeStatus.Failed]** —— 那不是一个显示问题，
     * 而是**整行点不动**：列表那一行的点击判据是「能不能播」，
     * 而当时的判据里含 `status.canPlay`（Failed 不在其中）。
     * 真机上报的就是这个：最新一期显示「字幕暂缺」，点下去进不了播放页、
     * 不弹提示、日志里连一行都没有 —— 用户的原话是「这个音频我也无法播放」。
     *
     * 音频在盘上就是**能听**，字幕欠着只是不完整。所以现在：
     *  - 有音频 → `Downloaded`（可播）
     *  - 没音频 → `Failed`（真的播不了，而且下载路径会重试）
     *
     * [error] 只留给「服务器连不上」：那是用户需要知道、且暂时无解的一件事。
     * 「等下一轮自愈」是正常流程的一部分，不占用红色错误位
     * （卡片上的「字幕待补」胶囊会说这件事，而且是中性色）。
     */
    private fun markPending(episode: Episode, serverDown: Boolean) {
        // 已经有英文字幕了就别动它的状态：那一条是「能看能听、只差中文」，
        // 回落到 Downloaded 会让界面上的「字幕 128 句」突然消失
        if (repository.audioStore.hasSrt(episode)) return
        repository.update(
            episode.copy(
                status = if (repository.audioStore.hasAudio(episode)) {
                    EpisodeStatus.Downloaded
                } else {
                    EpisodeStatus.Failed
                },
                error = if (serverDown) SERVER_DOWN_HINT else null,
            ),
        )
    }

    /** 供 UI 在用户手动删除后刷新 */
    fun reload() = repository.reload()

    companion object {
        /**
         * 服务器连不上时，卡片上显示的那句话。
         *
         * ⚠️ 措辞必须给出**后果**，而不是内部行话。
         *
         * 演进过三版，每一版都踩在同一个坑上：**说了下一步，但那一步是错的**。
         * - 2.0.0：「这条音频读不出来，换一条试试」—— 那是给文件损坏准备的话，
         *   而音频完好能播。用户照着「换一条」做，只是把同一件事经历十遍。
         * - 2.0.1：「字幕暂缺 · 装离线识别后自动补上」—— 让用户花 630 MB
         *   去解决一个「等服务器下一轮就好」的问题（真机上报的原话）。
         * - 2.0.3：只在**服务器真的连不上**时才出现这句。
         * - 2.1.0：端侧识别已经不存在，「装离线识别」这条路连按钮都没有了，
         *   所以这句话现在是唯一诚实的说法 —— 字幕只有服务器能给。
         */
        const val SERVER_DOWN_HINT = "服务器连不上，字幕等它恢复后自动补"
    }
}
