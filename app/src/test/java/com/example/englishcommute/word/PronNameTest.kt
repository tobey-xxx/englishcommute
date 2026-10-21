package com.example.englishcommute.word

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * 读音文件名的映射。
 *
 * ## 这个测试守的是什么
 *
 * `PronDownloader.pronFileName` 与服务端 `build_pron_v2.py` 的 `safe()`
 * 是**同一份契约的两个副本**。它们分叉过一次，代价很具体：
 *
 * | 症状 | 数量 |
 * |---|---|
 * | 客户端拿 lemma 去下 `<lemma>.mp3`，服务端没有那个文件（404） | 168 个词形 |
 * | **更糟：文件存在、能播，但读的是另一个词** | `he'll` → `hell`、`we'll` → `well`、`i'll` → `ill` |
 *
 * 第二种最难受：它看起来完全正常，只有耳朵能发现。
 *
 * ## 判据是「数据」而不是「这段代码」
 *
 * 所以断言不写 `assertTrue(文件名里没有撇号)` 这种**结构性**描述，
 * 而是拿 [PronDownloader.NAME_CASES] 这张**输入→输出对照表**逐条比。
 * 那张表同时也是 `tools/verify-pron-names.py` 的输入 ——
 * 于是「改一处、两边都红」变成了机制，而不是纪律。
 */
class PronNameTest {

    @Test
    fun `契约样例逐条对上`() {
        for ((input, expected) in PronDownloader.NAME_CASES) {
            assertEquals("pronFileName(\"$input\") 与服务端 safe() 不一致", expected,
                PronDownloader.pronFileName(input))
        }
    }

    @Test
    fun `缩写与同形的真实词必须落到不同文件`() {
        // 这是那个「读音读成另一个词」bug 的最小复现。
        // 只要这条不成立，`he'll` 就会拿到 `hell` 的音频。
        assertFalse(
            PronDownloader.pronFileName("he'll") == PronDownloader.pronFileName("hell"),
        )
        assertFalse(
            PronDownloader.pronFileName("we'll") == PronDownloader.pronFileName("well"),
        )
        assertFalse(
            PronDownloader.pronFileName("i'll") == PronDownloader.pronFileName("ill"),
        )
        assertFalse(
            PronDownloader.pronFileName("don't") == PronDownloader.pronFileName("dont"),
        )
    }

    @Test
    fun `数字开头的词不会被削掉数字`() {
        // 服务端 v1 用的是「只留字母」——`1st` 会变成 `st.mp3`，
        // 而 `2nd`/`3rd`/`4th` 全都变成 `nd`/`rd`/`th`，互相覆盖。
        assertEquals("1st.mp3", PronDownloader.pronFileName("1st"))
        assertEquals("4th.mp3", PronDownloader.pronFileName("4th"))
        assertFalse(
            PronDownloader.pronFileName("1st") == PronDownloader.pronFileName("2nd"),
        )
    }

    @Test
    fun `输出里只有文件系统与 URL 都安全的字符`() {
        val risky = "'\"\u2019\u2018\u02bc/\\?#%& \t\n:;=+$@!*()[]{}<>|,~^`"
        for ((input, _) in PronDownloader.NAME_CASES) {
            val name = PronDownloader.pronFileName(input)
            for (c in risky) {
                assertFalse("\"$input\" 的结果里出现了需要转义的字符 '$c'：$name", c in name)
            }
        }
    }

    @Test
    fun `非 ASCII 字母一律换成下划线 —— 不能放行 Unicode 字母`() {
        // 语料里真的出现过西里尔字母 `ә`（ECDICT 的音标字段把它和 IPA 的 ə 混着用）。
        // ⚠️ 这条守的是「不能用 Char.isLetterOrDigit()」：
        //    它会把 ә / 日文 / 希腊字母判成字母并原样写进文件名，
        //    而 Python 侧的 `'a' <= c <= 'z'` 不会 —— 两边立刻分叉。
        assertEquals("_.mp3", PronDownloader.pronFileName("\u04d9"))
        assertEquals("caf_.mp3", PronDownloader.pronFileName("caf\u00e9"))
        assertEquals("na_ve.mp3", PronDownloader.pronFileName("na\u00efve"))
    }

    @Test
    fun `空输入只留下后缀 —— 调用方保证非空`() {
        // 记录这个行为而不是「假装它不会发生」：
        // lemma 为空在 WordStore 那一层就被挡住了（它按 lemma 索引），
        // 所以这里不做特殊处理，只把这个事实写成断言，将来谁来改都能看到。
        assertEquals(".mp3", PronDownloader.pronFileName(""))
    }

    @Test
    fun `大写输入也被转成小写文件名`() {
        assertEquals("signed.mp3", PronDownloader.pronFileName("SIGNED"))
        assertEquals(PronDownloader.pronFileName("don't"), PronDownloader.pronFileName("DON'T"))
    }
}
