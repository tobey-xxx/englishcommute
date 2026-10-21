package com.example.englishcommute.word

import com.example.englishcommute.checkin.DayKey
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * `WordStore` 的落盘 / 添加 / 复习排程。
 *
 * ⚠️ 这个测试能跑起来靠两处配置（`app/build.gradle.kts` 的 `testOptions` 和
 * `testImplementation(libs.json)`）：Android 的 `android.util.Log` 与 `org.json`
 * 在 JVM 上都是**空壳**，不处理的话第一句日志就抛 `Stub!`。
 */
class WordStoreTest {

    private lateinit var dir: File

    /**
     * 可控时钟。取**本地时间今天中午 12:00** —— 这个选择是刻意的：
     * 后面要验「今天 23:30 到期的词也算今天要复习」，必须让 now 离当天末尾足够远。
     */
    private var now: Long = 0

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("wordstore-test").toFile()
        val today = DayKey.of(System.currentTimeMillis())
        now = DayKey.startOfDayMillis(today) + 12 * 3_600_000L
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    /** 每次调用都造一个新实例，用来模拟「杀进程重开」 */
    private fun store() = WordStore(
        dir = dir,
        scheduler = FsrsScheduler(enableFuzzing = false),
        clock = { now },
    )

    private fun storeWithFuzzing() = WordStore(
        dir = dir,
        scheduler = FsrsScheduler(enableFuzzing = true),
        clock = { now },
    )

    private fun lookup(lemma: String, surface: String = lemma) = WordLookup(
        entry = WordEntry(lemma, "ipa", 100, 0, listOf("释义占位")),
        surface = surface,
        examples = emptyList(),
    )

    private fun ctx(id: String, sentence: String, at: Long = 1_000L) = WordContext(
        episodeId = id,
        episodeTitle = "第 $id 期",
        lineStartMs = at,
        lineEndMs = at + 2_000,
        sentence = sentence,
        translation = "译文占位",
    )

    // =====================================================================
    // 一、落盘与重开
    // =====================================================================

    /**
     * 路径最脆弱的时候是「**首装 + 没联网就加词**」：那时 `VocabAddSync` 一次都没跑过，
     * `words/` 还不存在 —— 而 `writeText` 不会创建父目录。
     *
     * ⚠️ 这条和 `PronBackfillTest` 里那条是**同一类**：三个往 `words/` 下写的类
     * 互相以为别人会建目录。`WordStoreTest` 的 `setUp` 用的是
     * `Files.createTempDirectory(...)`（目录**存在**），所以原来测不到这一条 ——
     * 这里显式造一个「父目录都不存在」的情形。
     */
    @Test
    fun `目录不存在时也能落盘（首装未联网就加词）`() = runBlocking {
        val deep = File(dir, "not-yet/created")
        assertFalse("前提：这个目录现在不存在", deep.exists())

        WordStore(dir = deep, scheduler = FsrsScheduler(enableFuzzing = false), clock = { now })
            .apply { load(); add(lookup("sign"), ctx("ep1", "signed it.")) }

        assertTrue("目录应该被自己建出来", deep.isDirectory)
        assertTrue("words.json 应该在里面", File(deep, "words.json").length() > 0)
    }

    @Test
    fun `新增的词会立刻落盘，重开还在`() = runBlocking {
        store().apply {
            load()
            add(lookup("sign", "signed"), ctx("ep1", "Greenland and Denmark signed it."))
        }

        val reopened = store()
        assertEquals("重开后应该有 1 个词", 1, reopened.load())
        val card = reopened.cards.value.single()
        assertEquals("sign", card.lemma)
        assertEquals("signed", card.surface)
        assertEquals(1, card.contexts.size)
        assertEquals("Greenland and Denmark signed it.", card.contexts[0].sentence)
    }

    @Test
    fun `落盘后不留 tmp 文件`() = runBlocking {
        store().apply { load(); add(lookup("sign"), null) }
        assertFalse("tmp 文件必须被 rename 掉，不能留在磁盘上", File(dir, "words.json.tmp").exists())
        assertTrue("正式文件应该在", File(dir, "words.json").isFile)
    }

    @Test
    fun `首次使用（文件还不存在）返回 -1，不是错误`() = runBlocking {
        assertEquals(-1, store().load())
        assertTrue(store().cards.value.isEmpty())
    }

    // =====================================================================
    // 二、添加的合并语义
    // =====================================================================

    @Test
    fun `重复添加不建第二张卡，而是把新出处并进去`() = runBlocking {
        val s = store()
        s.load()
        s.add(lookup("sign", "signed"), ctx("ep1", "第一句"))
        val second = s.add(lookup("sign", "signing"), ctx("ep2", "第二句"))

        assertTrue("第二次应该是合并而不是新增", second is WordStore.AddOutcome.Merged)
        assertEquals("仍然只有一张卡", 1, s.cards.value.size)
        val card = s.cards.value.single()
        assertEquals("两条出处都在", 2, card.contexts.size)
        assertEquals("最近一次点到的形态在最前", "signing", card.surface)
        assertEquals("最新的出处排在最前", "第二句", card.contexts[0].sentence)
    }

    @Test
    fun `同一个句子里点两次不会记两条出处`() = runBlocking {
        val s = store()
        s.load()
        s.add(lookup("sign"), ctx("ep1", "同一句"))
        s.add(lookup("sign"), ctx("ep1", "同一句"))
        assertEquals(1, s.cards.value.single().contexts.size)
    }

    @Test
    fun `出处最多留 3 条`() = runBlocking {
        val s = store()
        s.load()
        repeat(5) { i -> s.add(lookup("sign"), ctx("ep$i", "第 $i 句")) }
        assertEquals("超出上限时丢最旧的", 3, s.cards.value.single().contexts.size)
        assertEquals("第 4 句", s.cards.value.single().contexts[0].sentence)
    }

    // =====================================================================
    // 三、★ 「今天要复习」的判据
    // =====================================================================

    @Test
    fun `新加的词当天就该过一遍`() = runBlocking {
        val s = store()
        s.load()
        s.add(lookup("sign"), null)
        assertEquals("新词的 dueAt 是「现在」，所以立刻进待复习", 1, s.dueCount(now))
        assertEquals(0, s.cards.value.single().reps)
    }

    @Test
    fun `复习之后的词排到未来，不在今天的待复习里`() = runBlocking {
        val s = store()
        s.load()
        s.add(lookup("sign"), null)
        s.add(lookup("clash"), null)

        val updated = s.review("sign", FsrsScheduler.RATING_GOOD)
        assertEquals("reps 应该 +1", 1, updated?.reps)
        assertTrue("下次到期必须在未来", updated!!.fsrs.dueAtMillis > now)

        assertEquals("只剩 clash 待复习", 1, s.dueCount(now))
        assertEquals("clash", s.dueCards(now).single().lemma)
    }

    /**
     * ★★ 这条守的是那个**最容易写错**的判断：待复习 = 「**今天 24:00 前**到期」，
     * 而不是「**此刻**已到期」。
     *
     * 为什么必须这样：FSRS 的间隔是整天数，`dueAt` 落在「上次复习的同一时刻」。
     * 昨晚 20:30 复习的、3 天期的词，到期时刻是第 3 天的 20:30 ——
     * 用户**今早 9 点**打开 App，按「此刻已到期」算出来是 0 张，
     * 他会以为今天没事干；等到晚上 20:30 才有 —— 而那时他在别处。
     */
    @Test
    fun `今天晚些时候才到期的词，今早就应该算「今天要复习」`() = runBlocking {
        val todayStart = DayKey.startOfDayMillis(DayKey.of(now))
        writeRawCards(
            listOf(
                Triple("overdue", todayStart + 30 * 60_000L, 1),        // 今天 00:30（早于 now）
                Triple("later", todayStart + 23 * 3_600_000L + 1_800_000L, 1), // 今天 23:30（晚于 now）
                Triple("tomorrow", todayStart + DayKey.DAY_MS + 60_000L, 1),   // 明天
            )
        )

        val s = store()
        s.load()
        val due = s.dueCards(now).map { it.lemma }
        assertEquals("应该是「今天到期」的两张，不含明天的", listOf("later", "overdue"), due.sorted())
    }

    @Test
    fun `待复习里新词排在老词前面`() = runBlocking {
        val todayStart = DayKey.startOfDayMillis(DayKey.of(now))
        writeRawCards(
            listOf(
                Triple("old", todayStart + 60_000L, 5),      // 复习过 5 次，今天很早就到期
                Triple("fresh", todayStart + 60_000L, 0),    // 一次都没复习过
            )
        )
        val s = store()
        s.load()
        assertEquals("新词优先", "fresh", s.dueCards(now).first().lemma)
    }

    // =====================================================================
    // 四、复习与统计
    // =====================================================================

    @Test
    fun `复习历史会累积，供以后做参数拟合`() = runBlocking {
        val s = store()
        s.load()
        s.add(lookup("sign"), null)
        val ratings = listOf(
            FsrsScheduler.RATING_GOOD, FsrsScheduler.RATING_AGAIN,
            FsrsScheduler.RATING_HARD, FsrsScheduler.RATING_EASY,
        )
        ratings.forEach { s.review("sign", it) }

        val card = s.cards.value.single()
        assertEquals(4, card.reps)
        assertEquals(ratings, card.reviews.map { it.rating })
        assertEquals("时间戳应该跟着时钟走", now, card.reviews.last().at)
    }

    @Test
    fun `复习历史会落盘`() = runBlocking {
        store().apply {
            load(); add(lookup("sign"), null)
            review("sign", FsrsScheduler.RATING_GOOD)
        }
        val reopened = store()
        reopened.load()
        assertEquals(1, reopened.cards.value.single().reviews.size)
        assertEquals(1, reopened.cards.value.single().reps)
    }

    @Test
    fun `新卡第一次就点 Again 不算遗忘`() = runBlocking {
        val s = store()
        s.load()
        s.add(lookup("sign"), null)
        s.review("sign", FsrsScheduler.RATING_AGAIN)
        assertEquals("新卡第一次没记住只是「还没学会」，不是「忘了」", 0, s.cards.value.single().lapses)
    }

    @Test
    fun `学过之后再点 Again 才算遗忘`() = runBlocking {
        val s = store()
        s.load()
        s.add(lookup("sign"), null)
        s.review("sign", FsrsScheduler.RATING_GOOD)     // reps 1
        s.review("sign", FsrsScheduler.RATING_AGAIN)    // 这时才是真的忘了
        assertEquals(1, s.cards.value.single().lapses)
    }

    @Test
    fun `不在生词本里的词复习时返回 null 而不是崩`() = runBlocking {
        val s = store()
        s.load()
        assertNull(s.review("nothing", FsrsScheduler.RATING_GOOD))
    }

    // =====================================================================
    // 五、四档筛选与「已掌握」
    // =====================================================================

    @Test
    fun `已掌握的判据是 stability 到 30 天`() {
        fun card(stability: Double?, reps: Int = 1) = WordCard(
            lemma = "w", surface = "w", addedAt = 0, contexts = emptyList(),
            fsrs = FsrsCard(stability = stability, dueAtMillis = 0),
            reps = reps, lapses = 0, reviews = emptyList(),
        )
        assertFalse("29.9 天还不算", card(29.9).isMastered)
        assertTrue("30 天整算掌握", card(30.0).isMastered)
        assertFalse("从没复习过（stability 为空）当然不算", card(null).isMastered)
        assertTrue("新卡的判据是 reps=0", card(null, reps = 0).isNew)
        assertFalse("新卡不算「学习中」—— 它有自己的筛选档", card(null, reps = 0).isLearning)
        assertTrue("复习过但没到 30 天算「学习中」", card(5.0).isLearning)
    }

    @Test
    fun `四档筛选各自给出正确的集合`() = runBlocking {
        val todayStart = DayKey.startOfDayMillis(DayKey.of(now))
        writeRawCards(
            listOf(
                Triple("fresh", todayStart, 0),          // 新词
                Triple("learning", todayStart, 3),       // 学习中（stability 由 raw 里给 2.0）
                Triple("mastered", todayStart + 10 * DayKey.DAY_MS, 9), // stability 由 raw 里给 60
            ),
            stability = mapOf("mastered" to 60.0, "learning" to 2.0, "fresh" to null),
        )
        val s = store()
        s.load()

        assertEquals("全部 3 个", 3, s.filtered(WordFilter.ALL).size)
        assertEquals("待复习：前两个今天到期", 2, s.filtered(WordFilter.DUE).size)
        assertEquals("学习中 1 个", listOf("learning"), s.filtered(WordFilter.LEARNING).map { it.lemma })
        assertEquals("已掌握 1 个", listOf("mastered"), s.filtered(WordFilter.MASTERED).map { it.lemma })

        val counts = s.counts(now)
        assertEquals(2, counts[WordFilter.DUE])
        assertEquals(3, counts[WordFilter.ALL])
    }

    @Test
    fun `复习卡能给出四个按钮各自的预计间隔`() = runBlocking {
        val s = store()
        s.load()
        s.add(lookup("sign"), null)
        val intervals = s.previewIntervals(s.cards.value.single(), now)

        assertEquals("四个档位都要有预测", 4, intervals.size)
        assertTrue("Again 的间隔最短", intervals.getValue(FsrsScheduler.RATING_AGAIN)
                <= intervals.getValue(FsrsScheduler.RATING_GOOD))
        assertTrue("Easy 的间隔最长", intervals.getValue(FsrsScheduler.RATING_EASY)
                > intervals.getValue(FsrsScheduler.RATING_GOOD))
    }

    // =====================================================================
    // 六、移除
    // =====================================================================

    @Test
    fun `移出生词本要落盘`() = runBlocking {
        // ⚠️ 2.3 起这条不再验证「读音文件被删」——
        //    读音的**文件名映射**已经全部收到 PronDownloader 里，
        //    WordStore 只负责调 `onAudioOrphaned` 回调（那条在下面单测）。
        //    在这里保留 `audioFile` / `hasAudio` 只会造出**第二份命名规则**，
        //    而它们已经分叉过一次（168 个词形受害）。
        val s = store()
        s.load()
        s.add(lookup("sign"), null)
        assertTrue(s.remove("sign"))
        assertTrue("卡片没了", s.cards.value.isEmpty())

        val reopened = store()
        assertEquals("移除也要落盘", 0, reopened.load())
    }

    @Test
    fun `移除不存在的词返回 false`() = runBlocking {
        val s = store()
        s.load()
        assertFalse(s.remove("nothing"))
    }

    // =====================================================================
    // 七、损坏数据
    // =====================================================================

    @Test
    fun `单条记录损坏时只跳过它，不丢整个生词本`() = runBlocking {
        val arr = JSONArray()
        arr.put(validCardJson("sign", 0L, 3))
        arr.put(JSONObject().put("noLemmaField", true))     // 缺 lemma -> 该条无效
        arr.put(validCardJson("clash", 0L, 3))
        File(dir, "words.json").writeText(arr.toString())

        val s = store()
        assertEquals("好的两条要留下", 2, s.load())
        assertEquals("坏的那条要计数", 1, s.lastLoadSkipped)
        assertTrue(s.has("sign"))
        assertTrue(s.has("clash"))
    }

    @Test
    fun `整个文件坏掉时按空处理，而不是崩在启动路径上`() = runBlocking {
        File(dir, "words.json").writeText("{ 这不是一个 JSON 数组")
        val s = store()
        assertEquals(-2, s.load())
        assertTrue(s.cards.value.isEmpty())
        // 而且还能继续正常用 —— 下一次写入会覆盖那份坏文件
        s.add(lookup("sign"), null)
        assertEquals(1, s.cards.value.size)
        assertEquals(1, store().load())
    }

    @Test
    fun `老数据缺字段时给默认值，不当作损坏`() = runBlocking {
        // 模拟「以后加了字段、用户手里的还是老文件」：只有最少的几个字段
        val arr = JSONArray()
        arr.put(JSONObject().apply {
            put("lemma", "sign")
            put("dueAt", now)
        })
        File(dir, "words.json").writeText(arr.toString())

        val s = store()
        assertEquals("缺字段是正常演进，不是损坏", 1, s.load())
        assertEquals(0, s.lastLoadSkipped)
        val card = s.cards.value.single()
        assertEquals("surface 缺失时退回 lemma", "sign", card.surface)
        assertEquals(FsrsScheduler.PHASE_LEARNING, card.fsrs.phase)
        assertNull(card.fsrs.stability)
        assertTrue(card.contexts.isEmpty())
    }

    // =====================================================================
    // 八、抖动开着也不影响落盘（生产配置）
    // =====================================================================

    @Test
    fun `开启抖动时落盘与重开依然一致`() = runBlocking {
        val s = storeWithFuzzing()
        s.load()
        s.add(lookup("sign"), null)
        s.review("sign", FsrsScheduler.RATING_EASY)
        val expected = s.cards.value.single().fsrs.dueAtMillis

        val reopened = storeWithFuzzing()
        reopened.load()
        assertEquals("落盘的是抖动之后的结果，重开必须一模一样", expected, reopened.cards.value.single().fsrs.dueAtMillis)
    }

    // =====================================================================
    // 辅助
    // =====================================================================

    private fun validCardJson(lemma: String, addedAt: Long, reps: Int): JSONObject = JSONObject().apply {
        put("lemma", lemma)
        put("surface", lemma)
        put("addedAt", addedAt)
        put("reps", reps)
        put("lapses", 0)
        put("contexts", JSONArray())
        put("reviews", JSONArray())
        put("phase", 2)
        put("step", JSONObject.NULL)
        put("stability", 2.0)
        put("difficulty", 5.0)
        put("dueAt", now)
        put("lastReviewAt", JSONObject.NULL)
    }

    /**
     * 直接写一份原始 words.json，用来精确控制 `dueAt` / `stability` ——
     * 走 `add()` + `review()` 是够不到那些特定状态的。
     */
    private fun writeRawCards(
        cards: List<Triple<String, Long, Int>>,
        stability: Map<String, Double?> = emptyMap(),
    ) {
        val arr = JSONArray()
        cards.forEach { (lemma, dueAt, reps) ->
            val s = stability[lemma] ?: if (stability.containsKey(lemma)) null else 2.0
            arr.put(JSONObject().apply {
                put("lemma", lemma)
                put("surface", lemma)
                put("addedAt", now - 10 * DayKey.DAY_MS)
                put("reps", reps)
                put("lapses", 0)
                put("contexts", JSONArray())
                put("reviews", JSONArray())
                put("phase", if (reps == 0) FsrsScheduler.PHASE_LEARNING else FsrsScheduler.PHASE_REVIEW)
                put("step", if (reps == 0) 0 else JSONObject.NULL)
                put("stability", s ?: JSONObject.NULL)
                put("difficulty", 5.0)
                put("dueAt", dueAt)
                put("lastReviewAt", JSONObject.NULL)
            })
        }
        File(dir, "words.json").writeText(arr.toString())
    }

    // =====================================================================
    // 复习完成的判据（P2.2 的弹窗就靠它）
    // =====================================================================

    /** 「今天庆祝过没有」的假存储。0 = 从来没庆祝过 */
    private var celebratedDay = 0L

    private fun storeWithCelebration() = WordStore(
        dir = dir,
        scheduler = FsrsScheduler(enableFuzzing = false),
        clock = { now },
        celebratedDayReader = { celebratedDay },
        celebratedDayWriter = { celebratedDay = it },
    )

    @Test
    fun `真做完了才庆祝`() {
        assertTrue(
            "开始时有事可做、结束时不剩、今天还没庆祝过",
            WordStore.shouldCelebrate(before = 12, after = 0, alreadyToday = false),
        )
    }

    @Test
    fun `开始时就没事可做 —— 不庆祝`() {
        // ⚠️ 这条最容易被漏掉。「复习完了」这件事在「本来就没有要复习的」
        //    情况下**不成立** —— 那不是完成，那是没事干。
        //    庆祝一个没发生的事是在骗用户，而且会让弹窗变成「一进页面就弹」的噪音。
        assertFalse(WordStore.shouldCelebrate(before = 0, after = 0, alreadyToday = false))
    }

    @Test
    fun `没做完 —— 不庆祝`() {
        // 用户今天有 12 张，复习 3 张就退出去。这时候弹「今天的复习做完了」
        // 是在撒谎，而他明天会发现自己还欠 9 张。
        assertFalse(WordStore.shouldCelebrate(before = 12, after = 9, alreadyToday = false))
        assertFalse(WordStore.shouldCelebrate(before = 1, after = 1, alreadyToday = false))
    }

    @Test
    fun `同一天只庆祝一次`() {
        assertFalse(WordStore.shouldCelebrate(before = 12, after = 0, alreadyToday = true))
    }

    @Test
    fun `庆祝标记按天记账 —— 第二天自动失效`() {
        val s = storeWithCelebration()
        assertFalse("还没庆祝过", s.celebratedToday)
        s.markCelebrated()
        assertTrue(s.celebratedToday)

        // ⚠️ 存的是「哪一天」而不是 Boolean。存 Boolean 的话第二天要有人负责重置，
        //    而「谁重置」永远是最容易漏的一环；存日期则天然自愈。
        now += DayKey.DAY_MS
        assertFalse("到了第二天，应该又能庆祝了", s.celebratedToday)
    }

    @Test
    fun `默认实现不持久化（单测与首次运行走的就是这条）`() {
        // 默认 reader 恒返回 0，所以无论标记多少次都还是「没庆祝过」。
        // 这不是缺陷 —— 它让 WordStore 在没有任何存储设施时仍然能工作。
        val s = store()
        s.markCelebrated()
        assertFalse(s.celebratedToday)
    }

    @Test
    fun `真的走一遍：把今天到期的全复习完，判据就成立`() = runBlocking {
        // 这是一条**端到端**的判据 —— 前面几条测的是纯函数，
        // 这条测的是「dueCards 真的会减少到 0」这个前提是否成立。
        // 它同时会抓住一类回归：如果哪天 FSRS 的最小间隔变成 0 天
        // （复习完立刻又过期），dueCount 永远不会归零，弹窗就永远不弹。
        val s = storeWithCelebration()
        s.load()
        s.add(lookup("alpha"), null)
        s.add(lookup("beta"), null)
        s.add(lookup("gamma"), null)

        val before = s.dueCount()
        assertEquals("刚加的三个词都该在今天到期", 3, before)

        for (card in s.dueCards()) {
            s.review(card.lemma, FsrsScheduler.RATING_GOOD)
        }

        assertEquals("复习完就不该再有今天到期的", 0, s.dueCount())
        assertTrue(WordStore.shouldCelebrate(before, s.dueCount(), s.celebratedToday))
    }

    @Test
    fun `点了「没印象」的词也不该留在今天`() = runBlocking {
        // Again 的间隔是**最小 1 天**（官方 `_next_interval` 里有 `max(..., 1)`）。
        // 如果哪天有人把它改成允许 0 天，那张卡会在本轮里反复出现，
        // 而 dueCount 永远不归零 —— 用户会觉得「这个词怎么都过不掉」。
        val s = storeWithCelebration()
        s.load()
        s.add(lookup("alpha"), null)
        val before = s.dueCount()

        s.review("alpha", FsrsScheduler.RATING_AGAIN)

        assertEquals(0, s.dueCount())
        assertTrue(WordStore.shouldCelebrate(before, s.dueCount(), s.celebratedToday))
    }

    // =====================================================================
    // 读音清理回调
    // =====================================================================

    @Test
    fun `移出生词本时通知清理读音文件`() = runBlocking {
        // ⚠️ 判据是「回调被调用了」，而不是「文件被删了」——
        //    文件名映射规则在 PronDownloader 里，WordStore 不该知道它
        //    （第一版两处各写一份 `"$lemma.mp3"`，然后它们分叉了）。
        val orphaned = mutableListOf<String>()
        val s = WordStore(
            dir = dir,
            scheduler = FsrsScheduler(enableFuzzing = false),
            clock = { now },
            onAudioOrphaned = { orphaned += it },
        )
        s.load()
        s.add(lookup("sign"), null)
        assertTrue(s.remove("sign"))
        assertEquals(listOf("sign"), orphaned)
    }
}
