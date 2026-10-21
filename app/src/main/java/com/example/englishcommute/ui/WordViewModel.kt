package com.example.englishcommute.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.englishcommute.EnglishCommuteApp
import com.example.englishcommute.checkin.DayKey
import com.example.englishcommute.diag.DiagLog
import com.example.englishcommute.word.PronState
import com.example.englishcommute.word.WordCard
import com.example.englishcommute.word.WordContext
import com.example.englishcommute.word.WordEntry
import com.example.englishcommute.word.WordFilter
import com.example.englishcommute.word.WordLookup
import com.example.englishcommute.word.WordStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 一次复习会话。
 *
 * ## 队列是**快照**
 *
 * 进入复习时把「今天到期的那些」固定下来。中途不重排 —— 我们的 FSRS
 * `learningSteps` 是空的（见 `FsrsScheduler` 的注释），所以复习一张后它
 * 至少 1 天后才到期，**不会当场再回到队列里**。固定的队列因此是准确的。
 *
 * 用动态队列（每评一次重算「还欠哪些」）反而会出问题：用户点了 Again 之后
 * 那张卡如果因为某种原因仍算「到期」，它会在本轮里**再出现一次**，
 * 而用户刚刚才说过「没印象」——那是算法在惩罚他，不是在学习。
 */
data class ReviewState(
    val queue: List<WordCard>,
    val index: Int,
    val revealed: Boolean,
) {
    val current: WordCard? get() = queue.getOrNull(index)
    val total: Int get() = queue.size

    /** 已评分的张数（进度显示用。**不含当前这张**，因为还没评） */
    val rated: Int get() = index

    val done: Boolean get() = index >= queue.size
}

/**
 * 生词页的状态。
 *
 * ## 为什么词条要单独缓存一份（[entries]）
 *
 * 列表每行要显示音标和释义，而 `WordCard` 里**只有 lemma**（生词本存的是用户的
 * 学习记录，不是词典的副本 —— 词典会随版本更新，生词记录不该跟着变）。
 * 所以每一行都要拿 lemma 去词表里查一次。
 *
 * 在 Composable 里逐行查会怎样：`lookup()` 是普通（同步）调用，看着没什么，
 * 但它每次都走一遍「归一化 → 查 forms → 查 base → 查 examples」，
 * 列表滚动时每帧几十行 × 4 个映射查找 = 白烧 CPU。
 *
 * 所以在这里**一次性把用到的词条查出来**，并且是**增量**的：
 * 只补列表里新增的那些 lemma，已查过的直接复用。
 *
 * ## 生命周期
 *
 * 挂在 Activity 作用域（和 `PlayerViewModel` 一样），切走再切回来是同一个实例 ——
 * 这是「今天要复习的数字不闪」的必要条件。
 */
class WordViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as EnglishCommuteApp).container
    private val store: WordStore = container.wordStore

    private val _filter = MutableStateFlow(WordFilter.DUE)
    val filter: StateFlow<WordFilter> = _filter.asStateFlow()

    /** 首次加载中。只在第一次进页面时为 true，之后一直 false */
    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    /** 词表是否已就绪。没就绪时查词会慢一点，界面据此决定要不要提示 */
    private val _tableReady = MutableStateFlow(false)
    val tableReady: StateFlow<Boolean> = _tableReady.asStateFlow()

    /** lemma → 词条。增量补齐，见类注释 */
    private val _entries = MutableStateFlow<Map<String, WordEntry>>(emptyMap())
    val entries: StateFlow<Map<String, WordEntry>> = _entries.asStateFlow()

    /**
     * 「现在几点」的刷新信号。见 [refreshToday]。
     *
     * ⚠️ 它存在的唯一理由是**跨天**：下面三个流全都按 `clock()` 现算，
     *    而它们的源是 `store.cards` —— 跨天时那张列表**一个字节都没变**
     *    （用户没动过任何一个词），光靠它不会触发重算。
     *    于是隔夜打开 App 会看到昨天的数字：该复习的显示成 0 个。
     *
     *    值本身没有意义（就是个时刻），只要求**每次进前台都变**。
     */
    private val dayTick = MutableStateFlow(0L)

    val cards: StateFlow<List<WordCard>> =
        combine(store.cards, _filter, dayTick) { _, f, _ -> store.filtered(f) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val counts: StateFlow<Map<WordFilter, Int>> =
        combine(store.cards, dayTick) { _, _ -> store.counts() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val dueCount: StateFlow<Int> =
        combine(store.cards, dayTick) { _, _ -> store.dueCount() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /**
     * 生词本里全部 lemma。
     *
     * ⚠️ 判「这个词已经加过了吗」**不能用 [cards]** —— 那个流受当前筛选档影响。
     * 用户停在「已掌握」那一档时，一个待复习的词会被算成「还没加」，
     * 词卡上就会显示成可点状态。而重复加同一个词是这个功能里最不该出的错：
     * 它会变成两张卡，复习进度被拆成两半。
     */
    val allLemmas: StateFlow<Set<String>> =
        store.cards.map { list -> list.map { it.lemma }.toSet() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    // =====================================================================
    // 复习会话（P1.6）
    // =====================================================================

    /** null = 不在复习中。非 null 时复习页整屏覆盖 */
    private val _review = MutableStateFlow<ReviewState?>(null)
    val review: StateFlow<ReviewState?> = _review.asStateFlow()

    /**
     * 会话开始时「今天欠多少张」。**必须在这里存一份** ——
     * 结束时的判据要用它跟结束后的数字比（见 [WordStore.shouldCelebrate]）。
     *
     * ⚠️ 不能事后用「复习了几张」代替：用户可能中途关掉又进来，
     *    两次会话的 `rated` 加起来才是他做完的量，而 `before/after` 的比较
     *    对「跨会话」也是对的。
     */
    private var dueAtSessionStart = 0

    /**
     * 非 null = 显示庆祝弹窗，值是**这次真正过完的词数**。
     *
     * ⚠️ 带上数量而不是一个 Boolean：弹窗里要写「刚刚过完 N 个词」，
     *    而 `dueAtSessionStart` 在收尾时会被清零 —— 界面拿不到它。
     *    让状态自己带着数据，比在界面层再维护一份「上次数量」可靠得多。
     */
    private val _celebrate = MutableStateFlow<Int?>(null)

    /** 「今天的复习做完了」弹窗要不要显示。非 null 时它的值就是本次过完的词数 */
    val celebrate: StateFlow<Int?> = _celebrate.asStateFlow()

    /** lemma → 读音状态。复习卡和列表都可能用 */
    private val _pron = MutableStateFlow<Map<String, PronState>>(emptyMap())
    val pron: StateFlow<Map<String, PronState>> = _pron.asStateFlow()

    private var storeLoaded = false
    private var started = false

    /**
     * 只读生词本。**幂等**，且只要几毫秒（一个小 JSON，几十条记录）。
     *
     * ## 为什么它必须能独立于 [start] 调用（3.0.5）
     *
     * 底栏「生词」页签上的角标**在任何页都要显示**，而 [start] 只在
     * 生词页**第一次被创建**时才跑 —— 首页是素材库，pager 只预排相邻页，
     * 所以刚打开 App 时生词页根本还没被创建过。
     *
     * 后果很具体：角标在「刚打开 App」那一刻永远是 0，**而那正是它唯一有用的时刻**
     * （打开之后用户就进生词页了，那里本来就写着「今天要复习 N 个」）。
     * 不拆出来的话，这个功能等于没做 —— 而且它看起来完全正常。
     *
     * ⚠️ **词表预热不在这里**（见 [start]）：那件事要几十到几百毫秒，
     *    而底栏角标不该等它。
     */
    fun loadStore() {
        if (storeLoaded) return
        storeLoaded = true
        viewModelScope.launch {
            store.load()
            _loading.value = false
        }
    }

    /**
     * 每回到前台调一次（[com.example.englishcommute.MainActivity] 的 ON_START）。
     *
     * ## 为什么「每隔一会儿重算一次」不够
     *
     * 到期判据是「**今天 24:00 前**到期」（`WordStore.dueCards`），所以跨过一次
     * 零点，欠的数量会整体跳一档。而 App 完全可能在后台待一整夜 ——
     * 手机没重启、进程没被杀、用户第二天早上从最近任务里点回来，
     * 那时**没有任何东西会重算**（`store.cards` 没变）。
     *
     * 打卡那边没这个问题：它的判据在用户每次收听时现算。
     * 生词本这边是「显示出来的一个数字」，所以必须有人负责刷新它。
     *
     * ⚠️ 每次进前台都**无条件**推一次信号（不是「跨天了才推」）——
     *    `stateIn` 用 equals 去重，内容真没变就不会有多余的重组；
     *    而「跨天了才推」多一个状态要维护，且判错时表现是「数字不动」，
     *    与「本来就没有变化」长得一模一样。
     */
    fun refreshToday() {
        val today = DayKey.today()
        // Long.MIN_VALUE = 还没算过。首次不算「跨天」—— 否则每次冷启动都会打一行
        if (lastTodayKey != Long.MIN_VALUE && lastTodayKey != today) {
            DiagLog.i("Word", "跨天重算：待复习 ${store.dueCount()} 个")
        }
        lastTodayKey = today
        dayTick.value = System.currentTimeMillis()
    }

    /** 上一次重算时所在的本地日（[DayKey] 的天序号）。[Long.MIN_VALUE] = 还没算过 */
    private var lastTodayKey = Long.MIN_VALUE

    /**
     * 进页面时调一次。**幂等** —— Compose 重组会反复触发 `LaunchedEffect`，
     * 每次重新读盘（几十毫秒 IO + 解析）是不必要的。
     */
    fun start() {
        loadStore()
        if (started) return
        started = true
        viewModelScope.launch {
            // 词表预热：用户接下来大概率要加词/看释义，提前把 2 MB assets 解析掉
            container.wordTable.get()
            _tableReady.value = true
        }
        // 增量补齐词条
        viewModelScope.launch {
            store.cards.collect { list ->
                val known = _entries.value
                val missing = list.map { it.lemma }.filter { it !in known }
                if (missing.isEmpty()) return@collect
                val table = container.wordTable.get()
                val added = missing.mapNotNull { lemma ->
                    table.lookup(lemma)?.entry?.let { lemma to it }
                }
                if (added.isNotEmpty()) _entries.value = known + added
            }
        }
    }

    fun select(f: WordFilter) {
        _filter.value = f
    }

    /** 查一个词形。词表没加载完时这里的第一次调用会等一会儿 */
    suspend fun lookup(surface: String): WordLookup? = container.wordTable.get().lookup(surface)

    fun add(lookup: WordLookup, context: WordContext?, onDone: (WordStore.AddOutcome) -> Unit = {}) {
        viewModelScope.launch { onDone(store.add(lookup, context)) }
    }

    fun review(lemma: String, rating: Int) {
        viewModelScope.launch { store.review(lemma, rating) }
    }

    fun remove(lemma: String) {
        viewModelScope.launch { store.remove(lemma) }
    }

    /** 复习卡上四个按钮的「几天后」 */
    fun previewIntervals(card: WordCard): Map<Int, Int> = store.previewIntervals(card)

    /** 读音文件是否已下好 */
    fun hasAudio(lemma: String): Boolean = container.pronDownloader.has(lemma)

    // ---------------------------------------------------------------------
    // 复习会话
    // ---------------------------------------------------------------------

    /**
     * 开始复习。
     *
     * ⚠️ 队列为空时**什么都不做**（返回 false）。界面上那个按钮也应该是不可点的，
     *    但两道防线都要有 —— 按钮的可点状态可能因为一次重组而短暂不同步，
     *    而「点进去看到一张空卡」是这个功能最糟糕的形态。
     */
    fun startReview(): Boolean {
        val due = store.dueCards()
        if (due.isEmpty()) return false
        dueAtSessionStart = due.size
        _review.value = ReviewState(queue = due, index = 0, revealed = false)
        DiagLogAct("开始复习：${due.size} 个词待复习")
        preloadPron(due.firstOrNull()?.lemma)
        return true
    }

    /** 翻到答案面 */
    fun reveal() {
        _review.value = _review.value?.copy(revealed = true)
    }

    /**
     * 给当前这张评分，然后前进一张。评分完成整轮时收尾。
     *
     * ⚠️ `store.review` 是**挂起**的（要落盘）—— 必须先 await 再前进。
     *    提前前进会让「下一张已经显示」和「上一张还没存」重叠，
     *    而在那种情况下杀进程会丢掉最后一次评分。
     */
    fun rate(rating: Int) {
        val s = _review.value ?: return
        val card = s.current ?: return
        viewModelScope.launch {
            store.review(card.lemma, rating)
            val next = s.copy(index = s.index + 1, revealed = false)
            _review.value = next
            if (next.done) finishSession() else preloadPron(next.current?.lemma)
        }
    }

    /** 用户点 ✕ 中途退出。**已评分的都已经落盘了**，这里只收界面状态 */
    fun endReview() {
        if (_review.value == null) return
        _review.value = null
        container.pronPlayer.release()
        // ⚠️ 中途退出**不判断庆祝** —— 判据是「做完了」，不是「进过复习页」。
        //    用户复习了 3 张就退出去，他明天还欠 9 张，这时候弹「做完了」是撒谎。
    }

    /**
     * 一轮走完。
     *
     * 收尾做两件事：关掉复习页 + 判断要不要弹庆祝。
     * 判断用 [WordStore.shouldCelebrate]，三个条件是「真做完了」的必要条件。
     */
    private fun finishSession() {
        val after = store.dueCount()
        val before = dueAtSessionStart
        _review.value = null
        dueAtSessionStart = 0
        container.pronPlayer.release()

        if (WordStore.shouldCelebrate(before, after, store.celebratedToday)) {
            _celebrate.value = before
            store.markCelebrated()
            DiagLogAct("今日复习完成：$before 个（生词本共 ${store.counts()[WordFilter.ALL] ?: 0} 个）")
        }
    }

    fun dismissCelebrate() {
        _celebrate.value = null
    }

    // ---------------------------------------------------------------------
    // 读音
    // ---------------------------------------------------------------------

    /**
     * 提前把读音下下来，让用户点的时候大概率已经就绪。
     *
     * **失败什么都不做** —— 这是一个后台的优化，不是一条链路。
     * 真正的状态由 [ensurePron] 里的 `_pron` 反映，界面据此显示。
     */
    private fun preloadPron(lemma: String?) {
        if (lemma == null) return
        if (container.pronDownloader.has(lemma)) return
        viewModelScope.launch { ensurePron(lemma) }
    }

    /**
     * 确保 [lemma] 的读音就绪，并把状态写进 [pron]。
     *
     * 幂等：已就绪时 `PronDownloader` 不发请求（见它的 `ensure`）。
     */
    private suspend fun ensurePron(lemma: String) {
        if (_pron.value[lemma] is PronState.Ready) return
        _pron.value = _pron.value + (lemma to PronState.Loading)
        val state = container.pronDownloader.ensure(lemma)
        _pron.value = _pron.value + (lemma to state)
    }

    /** 用户点了「听读音」。返回是否真的开始播了（false 时界面要给反馈） */
    fun playPron(lemma: String): Boolean {
        val state = _pron.value[lemma]
        val file = when (state) {
            is PronState.Ready -> state.file
            else -> container.pronDownloader.file(lemma).takeIf { it.exists() && it.length() > 0 }
        } ?: return false
        return container.pronPlayer.play(file)
    }

    /** 界面显式重试（用在「读音没下下来」那个状态下） */
    fun retryPron(lemma: String) {
        viewModelScope.launch { ensurePron(lemma) }
    }

    /**
     * 用户点了「听这一句」：从那期音频里播 [ctx] 记录的那一小段（3.0.4）。
     *
     * 返回是否**真的开始播了** —— **三种失败界面都要说出来**，不能静默：
     *
     * | 情形 | 为什么 |
     * |---|---|
     * | 那一期不在本地索引里 | 极少见（生词记的是 episodeId，那期可能已被保留期整条清掉） |
     * | 音频文件不在盘上 | **最常见**：3 天保留期清掉了。界面要说「那期音频已经清理」，而不是给一个点了没反应的按钮 |
     * | 区间播不出来 | 时间戳与音频对不上（理论上不该发生，但返回 false 比假装播了诚实） |
     *
     * ⚠️ 每次都**重新问**「音频在不在」（不缓存）：保留期清理在后台发生，
     *    同一张卡在不同时刻的答案可能不一样。
     */
    fun playSentence(ctx: WordContext): Boolean {
        val file = container.repository.localAudioFile(ctx.episodeId) ?: return false
        return container.sentencePlayer.play(file, ctx.lineStartMs, ctx.lineEndMs)
    }

    /** 退出复习页时停掉原句播放（[container] 里的播放器是单例，不会自己停） */
    fun stopSentence() {
        container.sentencePlayer.stop()
    }

    /**
     * 「听这一句」那个按钮**该不该出现**。
     *
     * ⚠️ 用状态而不是让界面直接问 —— 因为答案要**读磁盘**（`File.isFile`）。
     *    Compose 的每次重组都去 stat 一次是没必要的 IO；
     *    而这张卡上它的答案在一次停留里不会变（保留期清理是后台发生的）。
     */
    private val _sentenceReady = MutableStateFlow(false)

    /** 这一张卡的音频在不在盘上。由 [refreshSentenceAudio] 在换卡时算一次 */
    val sentenceReady: StateFlow<Boolean> = _sentenceReady.asStateFlow()

    /**
     * 换到新的一张时调一次：问一下那期的音频还在不在。
     *
     * ⚠️ 在 IO 线程上问（要 stat 文件）。**不能在重组里同步问** —— 那样
     *    每帧一次系统调用，而它答的是同一件事。
     */
    fun refreshSentenceAudio(ctx: WordContext?) {
        if (ctx == null) {
            _sentenceReady.value = false
            return
        }
        viewModelScope.launch {
            val file = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                container.repository.localAudioFile(ctx.episodeId)
            }
            _sentenceReady.value = file != null
        }
    }

    /** 复习页打开时调一次：把当前这张的读音准备好 */
    fun preparePron(lemma: String?) {
        preloadPron(lemma)
    }

    /** 日志：与项目里其它的动作日志同一条通道 */
    private fun DiagLogAct(message: String) {
        com.example.englishcommute.diag.DiagLog.act(message)
    }
}
