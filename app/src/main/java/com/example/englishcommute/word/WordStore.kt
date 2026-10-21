package com.example.englishcommute.word

import com.example.englishcommute.checkin.DayKey
import com.example.englishcommute.diag.DiagLog
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** 一次复习记录。**别删** —— P3 要做 FSRS 参数拟合，那时需要完整历史重放 */
/**
 * 一条复习记录。
 *
 * ⚠️ 4.1.0 加了 [device]：它让每一条复习在**跨设备**时有唯一身份
 * （`(设备, 时刻)`），于是两台设备各自离线复习同一天时，两条都留得住 ——
 * 而不是「谁后写谁赢、丢一条」。默认空串是为了让**这个版本之前**存下的
 * 老数据照常解析（那些必然是本机产生的，上传时会被补上本机标识）。
 */
data class ReviewLog(val at: Long, val rating: Int, val device: String = "")

/** 一条「你听到的」出处：那句话、它的中文、以及它在这期音频里的位置 */
data class WordContext(
    val episodeId: String,
    val episodeTitle: String,
    val lineStartMs: Long,
    val lineEndMs: Long,
    val sentence: String,
    val translation: String?,
)

/**
 * 一张生词卡。
 *
 * [surface] 是用户**当初点到的形态**（`signed`），[lemma] 是它归到原形（`sign`）。
 * 两者都留着：卡片按 [lemma] 建（不然 `signed`/`signs`/`signing` 会变成三张卡），
 * 但界面上要能说清「你点的是 signed，这是它的原形」。
 */
data class WordCard(
    val lemma: String,
    val surface: String,
    val addedAt: Long,
    val contexts: List<WordContext>,
    val fsrs: FsrsCard,
    val reps: Int,
    val lapses: Int,
    val reviews: List<ReviewLog>,
) {
    /** 从没复习过 */
    val isNew: Boolean get() = reps == 0

    /**
     * 「已掌握」。
     *
     * ⚠️ **这是产品判断，不是 FSRS 的定义。** FSRS 只给 stability（记忆稳定性，单位天），
     * 从不判定「掌握」—— 那是要拿去考的分级，不是算法能给的。
     *
     * 取 30 天：`stability = 30` 的含义是「30 天后仍记得的概率约 90%」。
     * 一个词能撑到一个月不忘，在「通勤听新闻」这个场景里就算学会了。
     */
    val isMastered: Boolean get() = (fsrs.stability ?: 0.0) >= MASTERED_STABILITY

    /** 复习过但还没到「已掌握」 */
    val isLearning: Boolean get() = !isNew && !isMastered

    companion object {
        const val MASTERED_STABILITY = 30.0
    }
}

/** 生词本的筛选档。四档，与设计稿一致 */
enum class WordFilter(val label: String) {
    DUE("待复习"),
    ALL("全部"),
    LEARNING("学习中"),
    MASTERED("已掌握"),
}

/**
 * 生词本的数据层：落盘、添加、复习排程。
 *
 * ## 为什么用文件 JSON，而不是像打卡那样用 SharedPreferences
 *
 * `CheckInStore` 用 SharedPreferences 是对的 —— 那里的数据是
 * 「一天一个整数」（`sec:20391 → 2340`），扁平、值拷贝、`apply()` 异步落盘正好。
 *
 * 生词卡不是那个形状：每张卡是**一条嵌套记录**（出处列表 + FSRS 状态 + 复习历史）。
 * 塞进 SharedPreferences 只能把整条记录序列化成一个字符串，那就已经是在用文件了，
 * 却失去了 `tmp + rename` 的原子性、也没法直接 `adb pull` 出来看。
 *
 * ## 为什么复习**不做落盘防抖**
 *
 * 项目里索引落盘是「高频防抖」的。这里相反，两个理由：
 *
 * 1. **频率根本不是一类东西。** 复习是用户点一下按钮触发一次，一天上限几百次；
 *    索引是同步引擎每个内容项都要写一次。几百次几百 KB 的写在现代手机上不到 10ms。
 * 2. **这是最不能丢的数据。** 用户攒了一个月的生词和复习进度，
 *    防抖意味着「刚复习完、还没到落盘窗口、进程被杀」= 白复习一次。
 *    而这份代价换来的收益只是省几毫秒 IO。
 *
 * ## 一条容易写错的产品判据：[dueCards] 用的是「今天结束前到期」
 *
 * ⚠️ **不是「此刻已到期」。** FSRS 的间隔是整天数，所以 `dueAt` 会是
 * 「上次复习的同一时刻」—— 比如昨晚 20:30 复习的，下次到期是 3 天后 20:30。
 * 那用户**今早 9 点**打开 App 想看今天要复习什么，按「此刻已到期」算出来是 **0 张**，
 * 而实际上今天有 12 张要复习。等到晚上 20:30 它们才「到期」，
 * 可那时候用户在别处 —— 这个功能就这么废了。
 *
 * 所以判据取**今天 24:00 之前到期**。
 *
 * @param dir 生词本目录（`filesDir/words`）。读音文件在它的 `audio/` 子目录下
 * @param scheduler FSRS 调度器
 * @param clock 当前时刻。抽成参数是为了单测能控制时间
 * @param onAudioOrphaned 移出某个词时清理它的附属文件（读音）。
 *        抽成回调而不是在这里直接删，是因为**读音文件名的映射规则只有
 *        [PronDownloader] 一份** —— 本类再写一次 `"$lemma.mp3"` 就是两处真相源，
 *        而它们一定会分叉（第一版服务端正是这么错的，168 个词形受害）。
 * @param celebratedDayReader/Writer「今天是否已经庆祝过」的读写。
 *        默认不持久化 —— 单测和「首次运行」都会走这个默认实现，
 *        它们的行为都是「还没庆祝过」，这正是想要的。
 */
class WordStore(
    private val dir: File,
    private val scheduler: FsrsScheduler = FsrsScheduler(),
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val onAudioOrphaned: (String) -> Unit = {},
    private val celebratedDayReader: () -> Long = { 0L },
    private val celebratedDayWriter: (Long) -> Unit = {},
    /**
     * 本机设备标识（4.1.0）。不登录时返回空串 —— 与「不登录也能用」一致：
     * 它只影响「复习记录的署名」，不影响任何本地功能。
     */
    private val deviceId: () -> String = { "" },
) {

    private val file = File(dir, "words.json")

    /**
     * 墓碑：`lemma → 删除时刻`。
     *
     * ## 为什么要单独存一份墓碑
     *
     * 删除是**唯一一个「两台设备必须达成一致」的破坏性动作**：只删本地的话，
     * 下一次同步会把它从另一台设备原样搬回来 —— 用户看到的是
     * 「删掉的词自己回来了」。
     *
     * ⚠️ 刻意**不放进 `words.json`**（那张表里只有「活着的卡」）：
     *    · 塞进 WordCard 会让 `cards` 里混进已删除的条目，而 `dueCount` /
     *      底栏徽标 / 列表 / 导出**十几处**都要跟着加过滤，漏一处就是
     *      「删了还在计数」；
     *    · 放在独立文件里，所有既有调用点**一个字都不用改**。
     *
     * 存的是 `lemma → 时刻`：存在判据是 `addedAt > deletedAt`（见 [WordMerge.isAlive]），
     * 所以「删了再加」只要给 addedAt 盖一个新时间戳就自洽。
     */
    private val deletedFile = File(dir, "words-deleted.json")

    private val _deleted = MutableStateFlow(loadDeleted())
    val deleted: StateFlow<Map<String, Long>> = _deleted.asStateFlow()

    /** 读写在协程里串行化：复习是用户连点按钮触发的，两次落盘并发会互相覆盖 */
    private val writeLock = Mutex()

    private val _cards = MutableStateFlow<List<WordCard>>(emptyList())

    /**
     * 本机刚改过生词本（加词 / 复习 / 删除）。
     *
     * ⚠️ 存在的理由：同步器**不能**靠「`cards` 变了」判断要不要上传 ——
     *    合并远端结果也会改 `cards`，那会变成「同步完再同步」的循环
     *    （靠节流压住，但它会永远每 2 分钟跑一次）。
     *    这个信号只由**本机的写操作**发出，两件事从此不会混淆。
     */
    private val _localChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    val localChanges: SharedFlow<Unit> = _localChanges.asSharedFlow()

    /** 全部生词卡，按到期时间升序（最该复习的在前） */
    val cards: StateFlow<List<WordCard>> = _cards.asStateFlow()

    /** 上次加载的结果，给诊断页/设置页看 */
    var lastLoadSkipped: Int = 0
        private set

    // =====================================================================
    // 加载
    // =====================================================================

    /**
     * 从磁盘加载。**必须在后台线程调用**（`Dispatchers.IO`）。
     *
     * 单条记录解析失败只跳过它、不让整个生词本消失 —— 但也**不静默**：
     * 失败条数与原因都进日志。如果这里悄悄吞掉，
     * 用户看到的是「我的生词怎么少了几个」，而没有任何线索。
     *
     * @return 加载到的卡片数；-1 表示文件不存在（首次使用，不是错误）
     */
    suspend fun load(): Int = withContext(Dispatchers.IO) {
        if (!file.exists()) {
            _cards.value = emptyList()
            return@withContext -1
        }
        val parsed = runCatching { JSONArray(file.readText(Charsets.UTF_8)) }
            .getOrElse { e ->
                DiagLog.w("Word", "生词本文件读不出来（${e.message}），本次按空处理：${file.name}")
                _cards.value = emptyList()
                return@withContext -2
            }

        val ok = ArrayList<WordCard>(parsed.length())
        var skipped = 0
        for (i in 0 until parsed.length()) {
            val card = runCatching { parsed.getJSONObject(i).toWordCard() }.getOrNull()
            if (card == null) skipped++ else ok += card
        }
        lastLoadSkipped = skipped
        if (skipped > 0) {
            DiagLog.w("Word", "生词本有 $skipped 条记录解析失败已跳过（共 ${parsed.length()} 条）")
        }
        _cards.value = ok.sortedBy { it.fsrs.dueAtMillis }
        DiagLog.i("Word", "生词本已加载 ${ok.size} 个词（待复习 ${dueCount()}）")
        ok.size
    }

    // =====================================================================
    // 增删改
    // =====================================================================

    /** [add] 的结果 */
    sealed interface AddOutcome {
        /** 新加了一张卡 */
        data class Added(val card: WordCard) : AddOutcome

        /** 这个词已经在生词本里了 —— 只把新的出处并进去 */
        data class Merged(val card: WordCard, val newContext: Boolean) : AddOutcome
    }

    /**
     * 加入生词本。已在里面时不建第二张卡，而是把 [context] 并进去。
     *
     * 合并而不是拒绝，是因为用户真实的行为就是「这个词我又遇到一次，想记下这一句」。
     * 拒绝他只会得到一个「这个词已在生词本里」的提示，而他本来也没想加第二遍 ——
     * 他想要的是**多一条出处**。
     */
    suspend fun add(lookup: WordLookup, context: WordContext?): AddOutcome =
        writeLock.withLock {
            val now = clock()
            // 4.1.0：这个词之前删过吗？现在又加回来 ⇒ 把墓碑清掉。
            // 存在判据是 addedAt > deletedAt（见 WordMerge.isAlive），新卡的
            // addedAt=now 本来就晚于旧墓碑；本地清一下是为了少留一份状态。
            if (_deleted.value.containsKey(lookup.entry.lemma)) {
                _deleted.value = _deleted.value - lookup.entry.lemma
                persistDeleted()
            }
            val existing = _cards.value.firstOrNull { it.lemma == lookup.entry.lemma }

            if (existing != null) {
                val contexts = mergeContext(existing.contexts, context)
                val merged = existing.copy(
                    surface = lookup.surface,     // 最近一次点到的形态更贴近用户当下的印象
                    contexts = contexts,
                )
                replace(merged)
                persist()
                return@withLock AddOutcome.Merged(merged, contexts.size != existing.contexts.size)
            }

            val card = WordCard(
                lemma = lookup.entry.lemma,
                surface = lookup.surface,
                addedAt = now,
                contexts = listOfNotNull(context),
                // 新卡：phase=Learning、stability/difficulty 都空（FSRS 等第一次评分才初始化）、
                // dueAt = 现在 —— 也就是**立刻进「今天要复习」**
                fsrs = FsrsCard(dueAtMillis = now),
                reps = 0,
                lapses = 0,
                reviews = emptyList(),
            )
            _cards.value = (_cards.value + card).sortedBy { it.fsrs.dueAtMillis }
            persist()
            _localChanges.tryEmit(Unit)
            DiagLog.act("加入生词本：${card.lemma}（生词本共 ${_cards.value.size} 个词）")
            AddOutcome.Added(card)
        }

    /** 复习一次。返回更新后的卡片；不在生词本里时返回 null */
    suspend fun review(lemma: String, rating: Int): WordCard? = writeLock.withLock {
        val current = _cards.value.firstOrNull { it.lemma == lemma } ?: return@withLock null
        val now = clock()
        val nextFsrs = scheduler.review(current.fsrs, rating, now)
        // 「忘记」的计数只在**已经学过**的词上算 —— 新卡第一次就点 Again
        // 不算 lapse（那只是还没记住，不是忘了）
        val isLapse = rating == FsrsScheduler.RATING_AGAIN && current.reps > 0

        val updated = current.copy(
            fsrs = nextFsrs,
            reps = current.reps + 1,
            lapses = current.lapses + if (isLapse) 1 else 0,
            reviews = current.reviews + ReviewLog(now, rating, deviceId()),
        )
        replace(updated)
        persist()
        _localChanges.tryEmit(Unit)

        val days = ((nextFsrs.dueAtMillis - now) / FsrsScheduler.MILLIS_PER_DAY).toInt()
        DiagLog.act("复习 ${current.lemma}：评分 $rating → 下次 ${days} 天后（第 ${updated.reps} 次）")
        updated
    }

    /**
     * 从生词本移除。
     *
     * ⚠️ 这是**删除类**操作，按项目约定立刻落盘、不防抖（本类本来就不防抖）。
     * 读音文件一并删掉，否则删掉的词会把音频文件永久留在磁盘上。
     */
    suspend fun remove(lemma: String): Boolean = writeLock.withLock {
        if (_cards.value.none { it.lemma == lemma }) return@withLock false
        _cards.value = _cards.value.filterNot { it.lemma == lemma }
        // ★ 4.1.0：留下墓碑。没有它，下一次同步会把这个词从另一台设备
        //    原样搬回来 —— 用户看到的是「删掉的词自己回来了」。
        _deleted.value = _deleted.value + (lemma to clock())
        persist()
        persistDeleted()
        _localChanges.tryEmit(Unit)
        withContext(Dispatchers.IO) { runCatching { onAudioOrphaned(lemma) } }
        DiagLog.act("移出生词本：$lemma")
        true
    }

    // =====================================================================
    // 跨设备同步（4.1.0）
    // =====================================================================

    /** 要上传的三样东西。规则见 [WordMerge] 的类注释 */
    data class SyncPayload(
        val cards: List<WordCard>,
        val deleted: Map<String, Long>,
        /** `lemma → 本机产生的那些复习`（别的设备那些我们已经有了，不重复上传） */
        val reviews: Map<String, List<ReviewLog>>,
    )

    /**
     * 攒出要上传的东西，顺手把**老数据的署名补上**。
     *
     * ⚠️ 为什么要在这里补：4.1.0 之前存下的复习记录没有 `device` 字段，
     *    而它们**必然是本机产生的**（那时还没有同步）—— 不补的话，它们要么
     *    被当成「别人的」永远不上传，要么在去重时和另一台设备的空署名记录
     *    撞车（丢掉一条真实的复习）。
     */
    suspend fun syncPayload(myDevice: String): SyncPayload = writeLock.withLock {
        var stamped = false
        val cards = _cards.value.map { card ->
            if (card.reviews.none { it.device.isEmpty() }) return@map card
            stamped = true
            card.copy(
                reviews = card.reviews.map {
                    if (it.device.isEmpty()) it.copy(device = myDevice) else it
                },
            )
        }
        if (stamped) {
            _cards.value = cards
            persist()
            DiagLog.i("Word", "词本同步：给老复习记录补上本机标识")
        }
        SyncPayload(
            cards = cards,
            deleted = _deleted.value,
            reviews = cards
                .associate { it.lemma to it.reviews.filter { r -> r.device == myDevice } }
                .filterValues { it.isNotEmpty() },
        )
    }

    /**
     * 收下服务端合并后的结果。
     *
     * 三件事，顺序不能反：
     * ① 远端卡片并进来（语境取并集、复习取并集、字段按规则取）；
     * ② 远端复习事件并进来（可能属于我早就有的卡）；
     * ③ ★ **按墓碑把该消失的卡拿掉** —— 包括「远端删了、我这还有」的那些。
     *
     * 每一张**复习集合变过的卡**都要重放一遍 FSRS（见 [WordMerge.replay]）：
     * 状态是事件的投影，不是独立的一份真相。
     *
     * @return 变更了几处（0 = 什么都没变，界面不用动）
     */
    suspend fun mergeRemote(
        remoteCards: List<WordCard>,
        remoteDeleted: Map<String, Long>,
        remoteReviews: Map<String, List<ReviewLog>>,
    ): Int = writeLock.withLock {
        val tomb = WordMerge.mergeTombstones(_deleted.value, remoteDeleted)
        var changed = 0
        val byLemma = _cards.value.associateBy { it.lemma }.toMutableMap()

        for (rc in remoteCards) {
            if (!WordMerge.isAlive(rc.addedAt, tomb[rc.lemma])) continue
            val local = byLemma[rc.lemma]
            val merged = if (local == null) {
                rc
            } else {
                val reviews = WordMerge.unionReviews(local.reviews, rc.reviews)
                val addedAt = maxOf(local.addedAt, rc.addedAt)
                val replayed = WordMerge.replay(addedAt, reviews, scheduler)
                local.copy(
                    surface = WordMerge.pickSurface(
                        local.surface, local.addedAt, rc.surface, rc.addedAt,
                    ),
                    addedAt = addedAt,
                    contexts = WordMerge.mergeContexts(local.contexts, rc.contexts, MAX_CONTEXTS),
                    fsrs = replayed.fsrs,
                    reps = replayed.reps,
                    lapses = replayed.lapses,
                    reviews = reviews,
                )
            }
            if (merged != local) {
                byLemma[rc.lemma] = merged
                changed++
            }
        }

        for ((lemma, events) in remoteReviews) {
            val local = byLemma[lemma] ?: continue
            val reviews = WordMerge.unionReviews(local.reviews, events)
            if (reviews.size == local.reviews.size) continue
            val replayed = WordMerge.replay(local.addedAt, reviews, scheduler)
            byLemma[lemma] = local.copy(
                reviews = reviews,
                fsrs = replayed.fsrs,
                reps = replayed.reps,
                lapses = replayed.lapses,
            )
            changed++
        }

        val alive = byLemma.values.filter { WordMerge.isAlive(it.addedAt, tomb[it.lemma]) }
        changed += byLemma.size - alive.size

        if (changed == 0 && tomb == _deleted.value) return@withLock 0

        _deleted.value = tomb
        _cards.value = alive.sortedBy { it.fsrs.dueAtMillis }
        persist()
        persistDeleted()
        DiagLog.i(
            "Word",
            "词本同步：收到 ${remoteCards.size} 张卡 / " +
                "${remoteReviews.values.sumOf { it.size }} 条复习 ⇒ 变更 $changed 处，" +
                "现存 ${alive.size} 张 / 墓碑 ${tomb.size} 个",
        )
        changed
    }

    // =====================================================================
    // 查询
    // =====================================================================

    fun has(lemma: String): Boolean = _cards.value.any { it.lemma == lemma }

    fun card(lemma: String): WordCard? = _cards.value.firstOrNull { it.lemma == lemma }

    /**
     * 今天要复习的（**今天 24:00 之前到期**，理由见类注释）。
     * 包括从没复习过的新词 —— 用户刚加的词本来就应该当天过一遍。
     */
    fun dueCards(now: Long = clock()): List<WordCard> {
        val endOfToday = DayKey.startOfDayMillis(DayKey.of(now)) + DayKey.DAY_MS
        return _cards.value
            .filter { it.fsrs.dueAtMillis < endOfToday }
            .sortedWith(compareBy({ it.reps }, { it.fsrs.dueAtMillis }))
    }

    fun dueCount(now: Long = clock()): Int = dueCards(now).size

    /** 按筛选档取列表 */
    fun filtered(filter: WordFilter, now: Long = clock()): List<WordCard> = when (filter) {
        WordFilter.DUE -> dueCards(now)
        WordFilter.ALL -> _cards.value.sortedByDescending { it.addedAt }
        WordFilter.LEARNING -> _cards.value.filter { it.isLearning }.sortedByDescending { it.addedAt }
        WordFilter.MASTERED -> _cards.value.filter { it.isMastered }.sortedByDescending { it.addedAt }
    }

    fun counts(now: Long = clock()): Map<WordFilter, Int> = mapOf(
        WordFilter.DUE to dueCount(now),
        WordFilter.ALL to _cards.value.size,
        WordFilter.LEARNING to _cards.value.count { it.isLearning },
        WordFilter.MASTERED to _cards.value.count { it.isMastered },
    )

    /** 复习卡的预测间隔：四个按钮各显示「几天后」 */
    fun previewIntervals(card: WordCard, now: Long = clock()): Map<Int, Int> =
        (FsrsScheduler.RATING_AGAIN..FsrsScheduler.RATING_EASY).associateWith { rating ->
            scheduler.previewIntervalDays(card.fsrs, rating, now)
        }

    // =====================================================================
    // 「今天复习完成了」—— 弹窗的判据
    // =====================================================================

    /**
     * 今天是否已经庆祝过。
     *
     * ⚠️ **必须落盘**（默认实现不落盘，只用于单测）。不落盘的后果很具体：
     * 用户早上复习完看到弹窗，中午切回生词页 —— 判据重算一次又成立，弹窗再来一遍。
     * 同一个成就感被重复发两次，第二次就变成噪音了。
     */
    val celebratedToday: Boolean get() = celebratedDayReader() == DayKey.startOfDayMillis(DayKey.of(clock()))

    /** 记下「今天庆祝过了」。**在弹窗真正显示出来之后**才调，不要提前 */
    fun markCelebrated() {
        celebratedDayWriter(DayKey.startOfDayMillis(DayKey.of(clock())))
    }

    // =====================================================================
    // 内部
    // =====================================================================

    /**
     * 读墓碑。**解析失败一律当作「没有墓碑」**。
     *
     * ⚠️ 方向很重要：文件坏了时如果当成「全都删过」，用户会看到词本被清空；
     *    当成「没有墓碑」，最坏只是某个删掉的词又回来（可见、可再删）。
     *    缺信息时的默认方向要选「别毁数据」那一侧。
     */
    private fun loadDeleted(): Map<String, Long> = runCatching {
        if (!deletedFile.isFile) return emptyMap()
        val o = JSONObject(deletedFile.readText(Charsets.UTF_8))
        val out = HashMap<String, Long>(o.length())
        val keys = o.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val v = o.optLong(k, 0L)
            if (v > 0) out[k] = v
        }
        out
    }.getOrDefault(emptyMap())

    private suspend fun persistDeleted() = withContext(Dispatchers.IO) {
        runCatching {
            val o = JSONObject()
            _deleted.value.forEach { (lemma, at) -> o.put(lemma, at) }
            val tmp = File(deletedFile.parentFile, "words-deleted.json.tmp")
            tmp.writeText(o.toString(), Charsets.UTF_8)
            if (!tmp.renameTo(deletedFile)) {
                deletedFile.writeText(o.toString(), Charsets.UTF_8)
                tmp.delete()
            }
        }
    }

    private fun replace(card: WordCard) {
        _cards.value = _cards.value
            .map { if (it.lemma == card.lemma) card else it }
            .sortedBy { it.fsrs.dueAtMillis }
    }

    /** 出处最多留 3 条，最近的在最前面。同一个句子只留一条 */
    private fun mergeContext(existing: List<WordContext>, incoming: WordContext?): List<WordContext> {
        if (incoming == null) return existing
        val sameSentence = existing.any {
            it.episodeId == incoming.episodeId && it.lineStartMs == incoming.lineStartMs
        }
        if (sameSentence) return existing
        return (listOf(incoming) + existing).take(MAX_CONTEXTS)
    }

    private suspend fun persist() {
        val snapshot = _cards.value
        val ok = withContext(Dispatchers.IO) {
            runCatching {
                val arr = JSONArray()
                snapshot.forEach { arr.put(it.toJson()) }
                // tmp + rename：进程在 writeText 中途被杀不会留下半截 JSON。
                // 半截 JSON 的后果是**整个生词本读不出来**（外层 readText+JSONArray 直接抛），
                // 而 `load()` 只能按空处理 —— 用户看到的就是「我的生词全没了」。
                // ⚠️ 先建目录，**不要依赖「同步已经建过了」**（3.0.3 修）。
                //    这条路径在最需要它的时候最脆弱：**首装 + 没联网就加词** ——
                //    那时 `VocabAddSync` 一次都没跑过，`words/` 还不存在，
                //    而 `writeText` 不会创建父目录 ⇒ 加的词一个都没落盘，
                //    用户第二天打开发现生词本空了（而当时界面上一切正常）。
                //
                //    `mkdirs()` 幂等且几乎免费；**每个写自己文件的人都自己做**，
                //    这样谁都不需要知道别人的执行顺序。
                dir.mkdirs()
                val tmp = File(dir, file.name + ".tmp")
                tmp.writeText(arr.toString(), Charsets.UTF_8)
                if (!tmp.renameTo(file)) {
                    tmp.copyTo(file, overwrite = true)
                    tmp.delete()
                }
                true
            }.getOrElse { e ->
                DiagLog.w("Word", "生词本落盘失败：${e.message}（内存里的数据没丢，下次操作会重试）")
                false
            }
        }
        if (!ok) lastPersistFailed = true else lastPersistFailed = false
    }

    /** 上次落盘是否失败。界面据此提示「改动可能没保存」 */
    var lastPersistFailed: Boolean = false
        private set

    companion object {
        /** 每个词最多记 3 条出处（设计稿定的）。再多对复习没有增量价值 */
        private const val MAX_CONTEXTS = 3

        /**
         * 要不要弹「今天的复习做完了」。
         *
         * ## 判据是「这次会话把今天该做的做完了」，**不是**「复习了几张」
         *
         * 三个条件缺一不可：
         *
         * | 条件 | 为什么 |
         * |---|---|
         * | `before > 0` | 会话开始时确实有事可做。今天本来就没有待复习的词时，**「复习完了」这件事不成立** —— 那不是完成，那是没事干。庆祝一个没发生的事是在骗用户 |
         * | `after == 0` | 现在一件不剩 |
         * | `!alreadyToday` | 同一天只庆祝一次。切走再回来会重新触发这个判断 |
         *
         * ⚠️ 写成「复习了至少一张就弹」是错的：用户今天有 12 张待复习，
         *    复习 1 张就退出 —— 这时候弹「今天的复习做完了」是在撒谎，
         *    而他明天会发现自己还欠着 11 张。
         *
         * ⚠️ 写成「`after == 0` 就弹」也是错的：一个词都没有的新用户
         *    一进页面就会被告知「复习完成」。
         */
        fun shouldCelebrate(before: Int, after: Int, alreadyToday: Boolean): Boolean =
            before > 0 && after == 0 && !alreadyToday
    }
}

// =========================================================================
// JSON 编解码
// =========================================================================

private fun WordCard.toJson(): JSONObject = JSONObject().apply {
    put("lemma", lemma)
    put("surface", surface)
    put("addedAt", addedAt)
    put("reps", reps)
    put("lapses", lapses)

    val ctx = JSONArray()
    contexts.forEach { c ->
        ctx.put(JSONObject().apply {
            put("episodeId", c.episodeId)
            put("episodeTitle", c.episodeTitle)
            put("lineStartMs", c.lineStartMs)
            put("lineEndMs", c.lineEndMs)
            put("sentence", c.sentence)
            put("translation", c.translation ?: JSONObject.NULL)
        })
    }
    put("contexts", ctx)

    // FSRS 状态摊平存 —— 嵌套一层只是为了「一眼看出哪些字段是算法的」
    put("phase", fsrs.phase)
    fsrs.step?.let { put("step", it) } ?: put("step", JSONObject.NULL)
    fsrs.stability?.let { put("stability", it) } ?: put("stability", JSONObject.NULL)
    fsrs.difficulty?.let { put("difficulty", it) } ?: put("difficulty", JSONObject.NULL)
    put("dueAt", fsrs.dueAtMillis)
    fsrs.lastReviewAtMillis?.let { put("lastReviewAt", it) } ?: put("lastReviewAt", JSONObject.NULL)

    val rev = JSONArray()
    reviews.forEach { r ->
        rev.put(JSONObject().apply {
            put("at", r.at)
            put("rating", r.rating)
            // 4.1.0：跨设备时它是「谁做的这次复习」—— 空串表示这条来自旧版本，
            // 上传时会被补成本机标识（它必然是本机产生的）
            put("device", r.device)
        })
    }
    put("reviews", rev)
}

/**
 * 反序列化。
 *
 * ⚠️ 只有 `lemma` 是硬要求，其余字段缺失时给默认值 —— 这样**以后加字段**
 * （P2 的「原声待补」、P3 的个人参数）不会让老数据整条解析失败。
 * 缺字段是正常演进，不是损坏。
 */
private fun JSONObject.toWordCard(): WordCard {
    val lemma = optString("lemma").takeIf { it.isNotEmpty() } ?: error("lemma 为空")

    val ctxArr = optJSONArray("contexts") ?: JSONArray()
    val contexts = (0 until ctxArr.length()).mapNotNull { i ->
        val o = ctxArr.optJSONObject(i) ?: return@mapNotNull null
        WordContext(
            episodeId = o.optString("episodeId"),
            episodeTitle = o.optString("episodeTitle"),
            lineStartMs = o.optLong("lineStartMs"),
            lineEndMs = o.optLong("lineEndMs"),
            sentence = o.optString("sentence"),
            translation = if (o.isNull("translation")) null else o.optString("translation"),
        )
    }

    val revArr = optJSONArray("reviews") ?: JSONArray()
    val reviews = (0 until revArr.length()).mapNotNull { i ->
        val o = revArr.optJSONObject(i) ?: return@mapNotNull null
        ReviewLog(
            at = o.optLong("at"),
            rating = o.optInt("rating"),
            // 老数据没有这个字段 ⇒ 空串（它必然是本机产生的，同步时补上标识）
            device = o.optString("device"),
        )
    }

    return WordCard(
        lemma = lemma,
        surface = optString("surface").ifEmpty { lemma },
        addedAt = optLong("addedAt"),
        contexts = contexts,
        fsrs = FsrsCard(
            phase = optInt("phase", FsrsScheduler.PHASE_LEARNING),
            step = if (isNull("step")) null else optInt("step"),
            stability = if (isNull("stability")) null else optDouble("stability"),
            difficulty = if (isNull("difficulty")) null else optDouble("difficulty"),
            dueAtMillis = optLong("dueAt"),
            lastReviewAtMillis = if (isNull("lastReviewAt")) null else optLong("lastReviewAt"),
        ),
        reps = optInt("reps"),
        lapses = optInt("lapses"),
        reviews = reviews,
    )
}
