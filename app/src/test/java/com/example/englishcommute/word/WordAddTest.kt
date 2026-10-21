package com.example.englishcommute.word

import java.io.StringReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 词表**增量**（`add.json`）与内置表的合并。
 *
 * ## 这一节守的是「同步成功了但查词没变」
 *
 * 增量是一条**容易看着成功、实际没生效**的链路：
 *
 * ```
 *   服务端算出 141 个新词 → 客户端下载 → 落盘 → 解析 → 合并 → 查词
 * ```
 *
 * 中间任何一步断掉，用户看到的都是同一件事：「这个词还是查不到」。
 * 而日志里可能写着「词表增量已更新：+141 个新词」—— 那是骗人的。
 *
 * 所以这里把**合并本身**（纯函数、不涉及网络）钉住：
 * 只要合并对，剩下的问题就只可能在网络或缓存失效上，可查。
 *
 * ## 格式（与 `server/bin/build_vocab_add.py` 的产出一致）
 *
 * ```
 *   base 行：lemma \t ipa \t freqRank \t level \t defs(|分隔)
 *   form 行：surface \t lemma
 * ```
 */
class WordAddTest {

    private val baseBuiltin = """
        sign	saɪn	1200	4	签名；签署
        value	vælju	800	4	价值；重视
    """.trimIndent()

    private val formsBuiltin = """
        signed	sign
        signing	sign
    """.trimIndent()

    private val examplesBuiltin = """
        sign	Nod if you agree.	如果你同意就点头。
    """.trimIndent()

    private fun table(
        extraBase: List<String> = emptyList(),
        extraForms: List<String> = emptyList(),
    ): WordTable = WordTable.parse(
        StringReader(baseBuiltin).buffered(),
        StringReader(formsBuiltin).buffered(),
        StringReader(examplesBuiltin).buffered(),
        extraBase,
        extraForms,
    )

    @Test
    fun `没有增量时就是内置表本来的样子`() {
        val t = table()
        assertEquals(2, t.entryCount)
        assertEquals(2, t.formCount)
        assertNotNull(t.lookup("sign"))
        assertNull("内置表里没有的词，当然查不到", t.lookup("airtime"))
    }

    @Test
    fun `增量里的新词查得到`() {
        val t = table(
            extraBase = listOf(
                "airtime	ˈertaɪm	0	0	播出时间；广播时长",
                "amnesia	æmˈniʒə	0	32	健忘症",
            ),
        )
        // ⚠️ entryCount 也要跟着变 —— 只让 lookup 查得到、而计数不动，
        //    说明有人写了一条「绕过表结构」的特殊路径（那种迟早会分叉）
        assertEquals(4, t.entryCount)

        val e = t.lookup("airtime")
        assertNotNull("增量里的新词必须能查到", e)
        assertEquals("airtime", e!!.entry.lemma)
        assertEquals("ˈertaɪm", e.entry.ipa)
        assertEquals(listOf("播出时间；广播时长"), e.entry.defs)
    }

    @Test
    fun `增量里的新变形也查得到，并且能归到原形`() {
        val t = table(
            extraBase = listOf("zelensky	zəˈlɛnski	0	0	泽连斯基"),
            extraForms = listOf("zelensky's	zelensky"),
        )
        assertEquals(3, t.formCount)
        val e = t.lookup("Zelensky's")        // 大小写 + 撇号都走归一化
        assertNotNull(e)
        assertEquals("zelensky", e!!.entry.lemma)
        assertTrue("要能说清「点的是变形」", e.inflected)
    }

    @Test
    fun `同名时**增量覆盖内置**`() {
        // 这一条是有意为之的语义：增量里出现一个内置表里已经有的词，
        // 意味着**内置那份已经过时了**（服务端重新生成过释义/音标）。
        // 反过来的话（内置优先），服务端就永远没法修正一个错词条 ——
        // 唯一的办法是发新 APK，而那是 18 MB。
        val t = table(extraBase = listOf("sign	saɪn	1200	4	迹象；标志（改）"))

        val e = t.lookup("sign")!!
        assertEquals(listOf("迹象；标志（改）"), e.entry.defs)
        assertEquals("覆盖不该改变条目总数", 2, t.entryCount)
    }

    @Test
    fun `增量里的坏行被跳过，不影响好行`() {
        val t = table(
            extraBase = listOf(
                "",                       // 空行
                "只有两列	xx",            // 列数不够
                "nodefs	xxx	0	0	",     // 有 5 列但释义为空 —— 词表里不该有这种条目
                "good	ɡʊd	0	0	好的",  // 唯一一条合法的
            ),
        )
        assertEquals("只该多出一个词", 3, t.entryCount)
        assertNotNull(t.lookup("good"))
        assertNull(t.lookup("nodefs"))
        assertNull(t.lookup("只有两列"))
    }

    @Test
    fun `增量里的坏变形行也被跳过`() {
        val t = table(extraForms = listOf("没有制表符", "\t空surface", "okay	ok"))
        // 前两条不合法，最后一条合法 —— 但它指向的 lemma 不在表里，
        // 合并阶段**不做**这种校验（那是服务端的责任，见 build_vocab_add.py
        // 里那句「forms 只留目标 lemma 在最新全量里的」）。
        // 这里只确认「不合法的不进表、也不崩」。
        assertNull(t.lookup("没有制表符"))
    }

    @Test
    fun `内置表的条目不会被增量误删`() {
        // 增量是**只增不改结构**的：合并完之后，内置表里原有的词一个都不能少。
        val t = table(extraBase = listOf("airtime	ˈertaɪm	0	0	播出时间"))
        for (w in listOf("sign", "value", "signed", "signing")) {
            assertNotNull("$w 不该因为合并而消失", t.lookup(w))
        }
    }
}
