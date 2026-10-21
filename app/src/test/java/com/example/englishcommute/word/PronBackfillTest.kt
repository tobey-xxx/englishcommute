package com.example.englishcommute.word

import com.example.englishcommute.checkin.DayKey
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * `PronBackfill`：在网络可用的时候，把生词本里缺读音的词补上。
 *
 * ## 这个测试为什么值得写
 *
 * 补漏的三条规则（**先补到期的** / **一轮限量** / **已确认服务端没有的不占名额**）
 * 错了都**不会报错、不会崩**：表现只是「有些词一直没声音」，
 * 而那正是用户报上来的形态 —— 无法从现象反推是哪条规则错了。
 *
 * ## 怎么做到不碰真实网络
 *
 * `PronDownloader` 的 `OkHttpClient` 是可以注入的，这里塞一个**拦截器**代替服务器：
 * 它只回答「这个路径返回什么码」，请求一个字节都不出本机。
 *
 * 另外用 `perRun = 1` 这种极小值把「限量」的边界压到 1 个词 ——
 * 用 8 去测边界，就等于在测「8 个之内」这个几乎不会出错的情况。
 */
class PronBackfillTest {

    private lateinit var dir: File
    private lateinit var audioDir: File

    /** 本地时间今天中午 —— 离当天末尾足够远，「今天到期」和「明天到期」才分得开 */
    private var now: Long = 0

    /** 本轮服务端对每个路径的回答。默认全 200 */
    private var routes: (String) -> Int = { 200 }

    /** 拦下来的请求路径（按发生顺序） */
    private val requested = mutableListOf<String>()

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("pronbackfill-test").toFile()
        // ⚠️ **故意不建 audioDir**（3.0.3 改）。
        //
        //    原来这里写的是 `File(dir, "audio").apply { mkdirs() }` ——
        //    等于**测试替被测对象做了它该做的事**，于是那个真 bug
        //    （音频目录从来没被创建过）在单测里**永远看不见**。
        //
        //    测试的责任是「像真实环境一样」：App 第一次装好时，`words/` 下
        //    只有同步建出来的目录，`audio/` 是不存在的。
        audioDir = File(dir, "audio")
        val today = DayKey.of(System.currentTimeMillis())
        now = DayKey.startOfDayMillis(today) + 12 * 3_600_000L
        requested.clear()
        routes = { 200 }
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    // ---------------------------------------------------------------------
    // 搭建
    // ---------------------------------------------------------------------

    private fun store() = WordStore(
        dir = dir,
        scheduler = FsrsScheduler(enableFuzzing = false),
        clock = { now },
    )

    private fun downloader(): PronDownloader {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                val path = req.url.encodedPath
                requested += path
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(routes(path))
                    .message("test")
                    // 内容必须 > 0 字节，否则 `ensure` 会判成「下载到 0 字节」
                    .body(PROBE_AUDIO.toResponseBody("audio/mpeg".toMediaType()))
                    .build()
            }
            .build()
        return PronDownloader(audioDir, client)
    }

    private fun lookup(lemma: String) = WordLookup(
        entry = WordEntry(lemma, "ipa", 100, 0, listOf("释义占位")),
        surface = lemma,
        examples = emptyList(),
    )

    /** 加一个词。`reviewed = true` 表示复习过一次 —— 它因此**不再是今天就到期** */
    private suspend fun WordStore.seed(lemma: String, reviewed: Boolean = false) {
        add(lookup(lemma), null)
        if (reviewed) review(lemma, FsrsScheduler.RATING_GOOD)
    }

    // =====================================================================
    // 一、基本行为
    // =====================================================================

    /**
     * ⚠️ 这条守的是一个**真发生过的 bug**（3.0.0 埋、3.0.3 修）。
     *
     * `PronDownloader` 的 `audioDir` 是 `filesDir/words/audio`，
     * 而创建 `words/` 的是**另一个类**（`VocabAddSync`）——
     * 这个子目录从来没有人创建过。后果：写文件抛 `FileNotFoundException`，
     * 而它是 `IOException` 的子类 ⇒ 被兜底翻译成「网络不通」，
     * 用户按提示去查网络（网络明明是好的）。真机表现就是
     * 「**生词本里，复习显示读音没下来，其他功能正常**」。
     *
     * ⚠️ 注意下面第一句 `assertFalse`：**它是这条测试有效的前提**。
     *    少了它，如果哪天有人又把目录建在 setUp 里，这条测试就会退化成恒真
     *    —— 而恒真的测试比没有测试更糟（它让人以为测过了）。
     */
    @Test
    fun `音频目录不存在时会自己建，不需要别人替它建`() = runBlocking {
        assertFalse("前提：这个目录现在必须是不存在的", audioDir.exists())

        val s = store().apply { load(); seed("sign") }
        assertEquals(1, PronBackfill(s, downloader()).run())

        assertTrue("目录应该被自己建出来了", audioDir.isDirectory)
        assertTrue(
            "文件也应该在里面",
            File(audioDir, PronDownloader.pronFileName("sign")).length() > 0,
        )
    }

    @Test
    fun `缺的词都补上，返回的是真下成功的个数`() = runBlocking {
        val s = store().apply { load(); seed("sign"); seed("clash"); seed("value") }
        val backfill = PronBackfill(s, downloader())

        assertEquals("3 个都缺，应该补 3 个", 3, backfill.run())
        assertEquals("每个词正好一个请求", 3, requested.size)
        for (w in listOf("sign", "clash", "value")) {
            assertTrue("$w 的读音应该在本地了", File(audioDir, PronDownloader.pronFileName(w)).length() > 0)
        }
    }

    @Test
    fun `已在本地的词不发请求也不计入`() = runBlocking {
        val s = store().apply { load(); seed("sign"); seed("clash") }
        // 预置 sign 的读音。
        // ⚠️ 这条测试**要自己建目录** —— 它模拟的是「文件已经在了」这个既成事实，
        //    属于测试的**准备工作**，不是被测对象的行为。
        //    （`音频目录不存在时会自己建` 那条的前提断言不受影响：每条测试独立。）
        audioDir.mkdirs()
        File(audioDir, PronDownloader.pronFileName("sign")).writeText("已有")

        val backfill = PronBackfill(s, downloader())
        assertEquals("只有 clash 需要补", 1, backfill.run())
        assertEquals("请求里不该出现 sign", listOf("/pron/clash.mp3"), requested)
    }

    @Test
    fun `一个词都没有时返回 0 且不发任何请求`() = runBlocking {
        val s = store().apply { load() }
        assertEquals(0, PronBackfill(s, downloader()).run())
        assertTrue("不该有任何请求", requested.isEmpty())
    }

    // =====================================================================
    // 二、限量（每轮名额是有限的，这是这个类存在的形式）
    // =====================================================================

    @Test
    fun `一轮不超过 perRun`() = runBlocking {
        val s = store().apply { load(); repeat(5) { seed("w$it") } }
        val backfill = PronBackfill(s, downloader(), perRun = 2)

        assertEquals("只补 2 个", 2, backfill.run())
        assertEquals("请求数等于名额，不是词数", 2, requested.size)
    }

    @Test
    fun `剩下的下一轮接着补，不会永远补不上`() = runBlocking {
        val s = store().apply { load(); repeat(5) { seed("w$it") } }
        val backfill = PronBackfill(s, downloader(), perRun = 2)

        assertEquals(2, backfill.run())
        assertEquals(2, backfill.run())
        assertEquals("第三轮只剩 1 个，返回 1（不是 2）", 1, backfill.run())
        assertEquals("全补完了", 0, backfill.run())
        assertEquals("总共 5 个请求", 5, requested.size)
    }

    // =====================================================================
    // 三、排序（名额有限时，先给谁 —— 这是它有没有用的分水岭）
    // =====================================================================

    @Test
    fun `今天要复习的排在前面`() = runBlocking {
        val s = store().apply {
            load()
            // 先复习过、下次在两天后的：今天不用复习
            seed("later", reviewed = true)
            // 刚加、还没复习的：今天要复习
            seed("due")
        }
        val backfill = PronBackfill(s, downloader(), perRun = 1)

        assertEquals(1, backfill.run())
        assertEquals(
            "只补 1 个时，补的必须是今天要复习的那个 —— 复习卡上才有『听读音』按钮",
            listOf("/pron/due.mp3"),
            requested,
        )
    }

    @Test
    fun `同一天到期时，最近加的词优先`() = runBlocking {
        val s = store().apply { load() }
        // `addedAt` 取自 `WordStore` 的 clock（可以注入），所以这里把它拉开 1 秒 ——
        // 顺序是**构造出来的**，不是靠真实时钟碰运气。
        now -= 1_000
        s.seed("old")
        now += 1_000
        s.seed("new")
        assertTrue("两个都还在同一天", s.dueCount() == 2)

        PronBackfill(s, downloader(), perRun = 1).run()

        // ⚠️ 判据必须点名一个具体的词。写成「两次结果一致」之类的比较，
        //    在任何情况下都能通过 —— 那是一条恒真的废断言（本项目踩过）。
        assertEquals(
            "只补 1 个时，补的应该是后加的那个 —— 刚加的词最可能还没下成",
            listOf("/pron/new.mp3"),
            requested,
        )
    }

    /**
     * ⚠️ **这条测试不是「到期优先」那一档的独立判据** —— 别把它当成证据。
     *
     * 实测：把 `PronBackfill` 里 `compareByDescending { it.lemma in dueLemmas }`
     * 整档删掉，这条测试**照样通过**。原因是 `WordStore` 在 `add()` 和 `load()`
     * 两处都按 `dueAtMillis` 升序存卡片，而「到期」= `dueAt < 今天 24:00`，
     * 所以到期的天然排在前面 —— 排序逻辑被存储顺序掩盖了。
     *
     * 它仍然值得留着，因为它断言的是一个**用户可感知的性质**：
     * 「复习卡上点得到声音的那个词，不会被更旧的词挤掉名额」。
     * 而「排序真的按 addedAt 生效」由 `同一天到期时，最近加的词优先` 守
     * （那条经过坏样本验证，删掉排序会红）。
     */
    @Test
    fun `到期的排在不到期的前面，即使它更旧`() = runBlocking {
        val s = store().apply {
            load()
            seed("oldDue")                       // 很旧，但没复习过 → 今天到期
            seed("freshLater", reviewed = true)  // 刚加但已复习过 → 今天不到期
        }
        PronBackfill(s, downloader(), perRun = 1).run()
        assertEquals(
            "『到不到期』这一档必须压过『新旧』—— 否则复习卡打开时没有声音",
            listOf("/pron/olddue.mp3"),
            requested,
        )
    }

    // =====================================================================
    // 四、失败路径
    // =====================================================================

    @Test
    fun `服务端没有（404）的词不占名额`() = runBlocking {
        val s = store().apply { load(); seed("missing"); seed("sign") }
        // missing 排前面（都到期、addedAt 相近；用 perRun=1 逼出名额给谁）
        routes = { path -> if (path.contains("missing")) 404 else 200 }

        val pron = downloader()
        // 先把 missing 问一遍，让它进 notPublished
        pron.ensure("missing")
        requested.clear()

        val backfill = PronBackfill(s, pron, perRun = 1)
        assertEquals("名额应该给 sign，而不是又问一遍 missing", 1, backfill.run())
        assertEquals(listOf("/pron/sign.mp3"), requested)
    }

    @Test
    fun `一个词失败不中断整批`() = runBlocking {
        val s = store().apply { load(); seed("a"); seed("b"); seed("c") }
        // a 失败，b/c 正常
        routes = { path -> if (path.contains("/a.")) 500 else 200 }

        val backfill = PronBackfill(s, downloader())
        assertEquals("3 个里补上 2 个", 2, backfill.run())
        assertEquals("三个都试过了", 3, requested.size)
        assertFalse("a 没有文件", File(audioDir, PronDownloader.pronFileName("a")).exists())
        assertTrue("b 补上了", File(audioDir, PronDownloader.pronFileName("b")).length() > 0)
    }

    @Test
    fun `全失败时返回 0，不抛异常`() = runBlocking {
        val s = store().apply { load(); seed("a"); seed("b") }
        routes = { 500 }
        assertEquals(0, PronBackfill(s, downloader()).run())
    }

    private companion object {
        /** 够长就行 —— `ensure` 判的是「> 0 字节」 */
        const val PROBE_AUDIO = "ID3-FAKE-AUDIO-BYTES"
    }
}
