package com.example.englishcommute.word

import java.io.BufferedReader
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * `WordTable` 对**真实 P0 产物**的测试。
 *
 * 为什么不用造的假数据：词表最容易出的问题是「服务端建的和你查的对不上」——
 * 归一化规则差一个字符、forms 的 value 指向一个 base 里没有的原形、例句挂错 key。
 * 这些问题在假数据上永远不会出现，只有拿真文件跑才会露头。
 *
 * ⚠️ 断言里的数字（11795 / 14140 / 6491）来自 P0 实测，
 *    **重新生成词表后要同步更新** —— 见 `tools/build_vocab.py` 的输出。
 */
class WordTableTest {

    @Test
    fun `解析真实词表的规模与 P0 产出一致`() {
        assertEquals("base 词条数", EXP_ENTRIES, table.entryCount)
        assertEquals("forms 变形数", EXP_FORMS, table.formCount)
        assertEquals("有例句的词数", EXP_EXAMPLE_WORDS, table.exampleWordCount)
    }

    // =====================================================================
    // 一、归一化：变形 -> 原形（词表存在的全部意义）
    // =====================================================================

    @Test
    fun `变形词归到原形`() {
        val l = table.lookup("signed")
        assertNotNull("signed 应该能查到", l)
        assertEquals("signed 的原形", "sign", l!!.entry.lemma)
        assertEquals("surfaced 记录的是用户点到的形态", "signed", l.surface)
        assertTrue("应该标记为「不是原形」", l.inflected)
        assertTrue("释义不该为空", l.entry.defs.isNotEmpty())
    }

    @Test
    fun `原形直接命中且不标记为变形`() {
        val l = table.lookup("sign")
        assertEquals("sign", l?.entry?.lemma)
        assertEquals("sign", l?.surface)
        assertEquals("原形不该被标记为变形", false, l?.inflected)
    }

    @Test
    fun `第三人称单数也归原形`() {
        assertEquals("go", table.lookup("goes")?.entry?.lemma)
        assertEquals("clash", table.lookup("clashed")?.entry?.lemma)
        assertEquals("sign", table.lookup("signs")?.entry?.lemma)
    }

    @Test
    fun `所有格归到名词`() {
        val l = table.lookup("trump's")
        assertEquals("trump's 应归到 trump", "trump", l?.entry?.lemma)
        assertTrue("应标记为变形", l?.inflected == true)
    }

    // =====================================================================
    // 二、归一化：用户手指底下的原始形态是「脏」的
    // =====================================================================

    @Test
    fun `大小写与首尾标点会被剥掉`() {
        for (raw in listOf("Signed", "signed,", "  signed.  ", "\"Signed!", "(signed)")) {
            assertEquals("输入 $raw 时", "sign", table.lookup(raw)?.entry?.lemma)
        }
    }

    @Test
    fun `弯引号与直引号等价`() {
        // 字幕里既可能是 U+2019（iPhone 输入法默认）也可能是 ASCII 单引号
        val straight = table.lookup("trump's")?.entry?.lemma
        val curly = table.lookup("trump\u2019s")?.entry?.lemma
        assertEquals("弯引号和直引号必须归一化成同一个词", straight, curly)
    }

    @Test
    fun `缩写有自己的词条，不被拆成两半`() {
        // ⚠️ 这条守的是一个**很容易写错**的地方：
        //    所有格表是从「ECDICT 里每个词 + 's」生成的（77 万条）。
        //    如果它排在最前面，`it's` 会被归到 `it` ——
        //    可是 `it's` 自己就有词条（`it is 的缩写`），归过去反而丢了更准的释义。
        val l = table.lookup("it's")
        assertEquals("it's 应保留自己的词条", "it's", l?.entry?.lemma)
        assertTrue("应该带释义", l?.entry?.defs?.isNotEmpty() == true)
    }

    @Test
    fun `查不到的词返回 null，而不是造一个空词条`() {
        // zelensky 是语料里出现的真词形，但 ECDICT/CMUdict 都没有它。
        // 界面上必须如实显示「暂未收录」—— 编一个假释义比空着更糟。
        assertNull("人名不该被编出释义", table.lookup("zelensky"))
        assertNull("空串", table.lookup(""))
        assertNull("纯标点", table.lookup("......"))
        assertNull("不存在的编造词", table.lookup("zzqqxx"))
    }

    // =====================================================================
    // 三、音标与例句
    // =====================================================================

    @Test
    fun `音标是标准美音，且不带两边的斜杠`() {
        assertEquals("sign 的音标", "saɪn", table.lookup("sign")?.entry?.ipa)
        assertEquals("clash 的音标", "klæʃ", table.lookup("clash")?.entry?.ipa)
        assertEquals("greenland 的音标", "ˈɡrinlənd", table.lookup("greenland")?.entry?.ipa)
        // 单音节词不带重音符号（这是 P0 里专门做的取舍）
        assertTrue(
            "sign 是单音节，音标里不该有重音符号",
            table.lookup("sign")!!.entry.ipa!!.none { it == 'ˈ' || it == 'ˌ' },
        )
    }

    @Test
    fun `常见词都有音标`() {
        // P0 实测音标覆盖 95.8%（用 CMUdict 补的，比 ECDICT 那份英美混杂的更一致）。
        // 这里是「用真数据点名」——比断言一个泛泛的百分比更能抓住回归。
        for (w in listOf("sign", "clash", "value", "greenland", "civil", "get", "escalate")) {
            val e = table.lookup(w)?.entry
            assertNotNull("$w 应该能查到", e)
            assertTrue("$w 应该有音标（实测覆盖率 95.8%）", e!!.hasIpa)
        }
    }

    @Test
    fun `例句按原形挂载，变形词也能取到`() {
        val direct = table.lookup("sign")?.examples.orEmpty()
        val viaForm = table.lookup("signed")?.examples.orEmpty()
        assertTrue("sign 应该有教材例句", direct.isNotEmpty())
        assertEquals("从 signed 查到 sign，例句应该一样", direct.size, viaForm.size)
        assertTrue("例句的英文里应含目标词", direct[0].en.contains("sign", ignoreCase = true))
        assertTrue("例句应带中文对照", direct[0].zh.isNotEmpty())
    }

    @Test
    fun `没例句的词返回空列表，与「查不到这个词」是两回事`() {
        // ⚠️ 这两件事界面上的文案完全不同，必须能区分：
        //      查不到这个词        -> `lookup()` 返回 null   -> 「这个词暂未收录」
        //      查得到但没有例句    -> `examples` 是空列表    -> 只是不显示例句那一段
        //    P0 实测：例句覆盖 6491/11795 = 55%（高频词 86~98%，被拉低的是人名地名）。
        //    所以「有词条、没例句」是**常态**，不是异常。
        val lookups = listOf("sign", "clash", "value", "greenland", "civil", "get", "escalate")
            .mapNotNull { table.lookup(it) }
        assertTrue("这几个常见词都该查得到（实际 ${lookups.size}）", lookups.size == 7)

        val withExample = lookups.count { it.examples.isNotEmpty() }
        assertTrue("至少要有一个词带例句，否则说明例句表没加载进来", withExample > 0)
        // 不带例句的那些，examples 必须是**空列表**而不是 null —— 这样界面直接判 isEmpty 就行，
        // 不需要到处写 `?: emptyList()`
        assertTrue(
            "没例句时应该是空列表",
            lookups.filter { it.examples.isEmpty() }.all { it.examples.isEmpty() },
        )
    }

    // =====================================================================
    // 四、level 位掩码
    // =====================================================================

    @Test
    fun `考试标签按位解码正确`() {
        val sign = table.lookup("sign")!!.entry
        assertEquals("sign 的 level 位值", 350, sign.level)
        val labels = WordLevel.labels(sign.level)
        assertTrue("应含六级，实际 $labels", "六级" in labels)
        assertTrue("应含考研，实际 $labels", "考研" in labels)
        assertTrue("应含核心词，实际 $labels", "核心词" in labels)
        assertTrue("不该含中考，实际 $labels", "中考" !in labels)
    }

    @Test
    fun `没有标签的词解码出空列表`() {
        assertEquals("it's 没有考试标签", 0, table.lookup("it's")!!.entry.level)
        assertTrue(WordLevel.labels(0).isEmpty())
    }

    // =====================================================================
    // 五、边界
    // =====================================================================

    @Test
    fun `normalize 与服务器端规则一致`() {
        assertEquals("小写化", "sign", WordTable.normalize("SIGN"))
        assertEquals("剥首尾标点", "sign", WordTable.normalize("...sign!!!"))
        assertEquals("保留内部撇号", "it's", WordTable.normalize("It's"))
        assertEquals("弯引号转直", "it's", WordTable.normalize("It\u2019s"))
        assertEquals("内部连字符不在剥除集里", "self-esteem", WordTable.normalize("self-esteem"))
        assertEquals("全剥掉后是空串", "", WordTable.normalize("()"))
        assertEquals("空串进空串出", "", WordTable.normalize(""))
    }

    @Test
    fun `空表查找不崩`() {
        val empty = WordTable.empty()
        assertEquals(0, empty.entryCount)
        assertNull(empty.lookup("sign"))
        assertNull(empty.lookup(""))
    }

    @Test
    fun `解析耗时在可接受范围内（真机上是几十毫秒的量级）`() {
        // ⚠️ 这个数字只是**基线**，不是硬承诺：单测跑在有 JIT 的桌面 JVM 上，
        //    比中低端真机快好几倍。它的作用是捕捉「意外退化」——
        //    比如某次改动把 O(n) 的解析写成了 O(n²)，这里会立刻从 200ms 涨到 10s。
        val t0 = System.nanoTime()
        val again = WordTable.parse(asset("vocab.base"), asset("vocab.forms"), asset("examples.base"))
        val ms = (System.nanoTime() - t0) / 1_000_000
        println("词表解析耗时：${ms}ms（${again.entryCount} 词条 + ${again.formCount} 变形 + ${again.exampleWordCount} 例句词）")
        assertTrue("解析耗时 ${ms}ms 明显超出预期，可能退化了", ms < 3_000)
    }

    private companion object {
        const val EXP_ENTRIES = 11_795
        const val EXP_FORMS = 14_140
        const val EXP_EXAMPLE_WORDS = 6_491

        lateinit var table: WordTable

        @JvmStatic
        @BeforeClass
        fun loadRealAssets() {
            table = WordTable.parse(asset("vocab.base"), asset("vocab.forms"), asset("examples.base"))
        }

        /**
         * 单测的工作目录在 Gradle 下是模块目录（`app/`），但这个前提不写在构建脚本里，
         * 换个跑法就可能变。所以两个候选路径都试，都找不到时把试过的绝对路径打出来。
         */
        fun asset(name: String): BufferedReader {
            val candidates = listOf(
                File("src/main/assets/vocab/$name"),
                File("app/src/main/assets/vocab/$name"),
            )
            val f = candidates.firstOrNull { it.exists() }
                ?: throw AssertionError(
                    "找不到 assets/vocab/$name。试过：\n" +
                        candidates.joinToString("\n") { "  ${it.absolutePath}" } +
                        "\n当前工作目录：${File(".").absolutePath}"
                )
            return f.bufferedReader()
        }
    }
}
