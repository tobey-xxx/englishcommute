package com.example.englishcommute.word

import java.io.BufferedReader

/**
 * P0 产物（`assets/vocab/vocab.base`）里的一个词条。
 * 五列一一对应：`lemma \t ipa \t freqRank \t level \t defs`。
 */
data class WordEntry(
    /** 原形（小写）。生词本按它建卡 —— `signed` / `signing` / `signs` 是**同一张卡** */
    val lemma: String,
    /** 美式音标，不含两边的斜杠（`saɪn` 而不是 `/saɪn/`）。缺音标时为 null */
    val ipa: String?,
    /** BNC 词频排名，越小越常用；0 表示数据源里没有 */
    val freqRank: Int,
    /** 考试标签位掩码，见 [WordLevel] */
    val level: Int,
    /** 中文释义，最多 3 条（P0 已截断）。永不为空 —— 没释义的词不进词表 */
    val defs: List<String>,
) {
    /** 有没有音标 —— 95.8% 的词有，剩下的界面不该留空位 */
    val hasIpa: Boolean get() = !ipa.isNullOrEmpty()
}

/** 一条教材例句（Tatoeba 中英句对，两侧都是人工过了一遍的） */
data class WordExample(val en: String, val zh: String)

/**
 * 查词结果。
 *
 * [surface] 是用户**点到的那个形态**（可能是 `signed`），[entry] 是它的**原形**（`sign`）。
 * 两者不同时界面要能说清这层关系 —— 用户看到的是 `signed`，卡片标题却是 `sign`，
 * 不解释一句就是「你点的和我给的不是一回事」。
 */
data class WordLookup(
    val entry: WordEntry,
    val surface: String,
    val examples: List<WordExample>,
) {
    /** 用户点到的形态不是原形（`signed` → `sign`） */
    val inflected: Boolean get() = surface != entry.lemma
}

/**
 * 考试标签位掩码（列 4）。**当前界面不用它** —— 数据留着，将来做
 * 「只收六级词」这类筛选时直接用。
 *
 * ⚠️ 位值必须与 `tools/build_vocab.py` 里的 `LEVEL_BITS` 一致，改一处就要改两处。
 */
object WordLevel {
    const val ZHONGKAO = 1 shl 0      // 中考 zk
    const val GAOKAO = 1 shl 1        // 高考 gk
    const val CET4 = 1 shl 2
    const val CET6 = 1 shl 3
    const val POSTGRAD = 1 shl 4      // 考研 ky
    const val TOEFL = 1 shl 5
    const val IELTS = 1 shl 6
    const val GRE = 1 shl 7
    const val OXFORD_3000 = 1 shl 8   // 牛津核心 3000

    private val NAMES = listOf(
        ZHONGKAO to "中考", GAOKAO to "高考", CET4 to "四级", CET6 to "六级",
        POSTGRAD to "考研", TOEFL to "托福", IELTS to "雅思", GRE to "GRE",
        OXFORD_3000 to "核心词",
    )

    fun labels(level: Int): List<String> = NAMES.filter { level and it.first != 0 }.map { it.second }
}

/**
 * 内置词表：**运行时零外部依赖**。
 *
 * ## 为什么放在 APK 里，而不是服务端按需查
 *
 * 决定性理由不是体积，是**使用场景**：用户是在**没信号的地铁里**听到一个词、
 * 然后想把它加进生词本。「加入生词本」这个动作必须当场完成 ——
 * 按需查接口会让它变成「释义待补」，功能是通的、体验是断的。
 *
 * 体积代价：三份文件合计 **2.2 MB**（base 0.90 + forms 0.22 + examples 1.05）。
 *
 * ## 三份文件各自的角色
 *
 * | 文件 | 内容 | 查询顺序 |
 * |---|---|---|
 * | `vocab.forms` | 变形 → 原形（14140 条） | **先查它**，把 `signed` 归到 `sign` |
 * | `vocab.base` | 原形 → 音标/词频/标签/释义（11795 条） | 再查它拿词条 |
 * | `examples.base` | 原形 → 教材例句（6491 个词有） | 最后按**原形**取例句 |
 *
 * ## 为什么 base 里只有原形
 *
 * 如果 `signed` / `signing` / `signs` 各建一条，用户会把同一个词加进生词本三次，
 * FSRS 也会为它建三张卡 —— 一个词的记忆强度被拆成三份，间隔全算错。
 * 所以查询一律先过 [forms] 归一化，base 里只留原形。
 *
 * ## 覆盖率（P0 实测，66 期语料）
 *
 * - 词形命中 98.7%（按出现次数加权）
 * - 但这是「当前语料」。未来的新语料里仍会有查不到的词
 *   （模拟：拿一半语料建表测另一半，词频覆盖 93.3%；补 BNC 前 1 万高频词后 97.0%）。
 *   **根治是服务端按期下发增量词**（规划里的 P2.3），现在还没做。
 */
class WordTable private constructor(
    private val entries: Map<String, WordEntry>,
    private val forms: Map<String, String>,
    private val examples: Map<String, List<WordExample>>,
) {

    val entryCount: Int get() = entries.size
    val formCount: Int get() = forms.size
    val exampleWordCount: Int get() = examples.size

    /**
     * 查一个词形。返回 null 表示**词表里没有** ——
     * 界面上要如实说「这个词暂未收录」，而不是显示一个空卡片。
     *
     * ⚠️ 传进来的可以是带标点/大写的原始形态（`Signed,`），这里会先归一化。
     */
    fun lookup(raw: String): WordLookup? {
        val surface = normalize(raw)
        if (surface.isEmpty()) return null
        val lemma = forms[surface] ?: surface
        val entry = entries[lemma] ?: entries[surface] ?: return null
        return WordLookup(entry, surface, examples[entry.lemma].orEmpty())
    }

    companion object {
        /**
         * 归一化。规则：弯引号统一成直引号 → 剥掉首尾标点与空白 → 转小写。
         *
         * ## 与服务器端的关系（`tools/build_vocab.py` 的 `normalize`）
         *
         * 前两步必须**逐字符一致** —— 不一致会让「服务端建词表时算进去的词形」
         * 在客户端查不到，而那种错看起来像词表缺数据，很难查。
         *
         * ⚠️ **只有一处故意不同：这里多剥一层空白。**
         *
         * 服务器端不需要剥空白 —— 它的输入是 `srt` 用 `split()` 切出来的 token，
         * 本来就不含首尾空白（Python 的 `strip(chars)` 同样不处理空白，也不影响它）。
         * 而客户端的输入是**手指落点的字符范围**，从 `TextLayoutResult` 取边界时
         * 可能带上空白。多剥这一层是安全的：它不会让任何真实词形查不到。
         *
         * （这个差异是被单测逼出来的：`"  signed.  "` 在只剥标点的版本下查不到。）
         */
        private val APOS_IN = arrayOf('\u2019' to '\'', '\u2018' to '\'', '\u02bc' to '\'')
        private val TRIM_CHARS = ".,!?;:()[]{}\"“”'‘’–—-".toSet()

        fun normalize(raw: String): String {
            var s = raw
            for ((a, b) in APOS_IN) s = s.replace(a, b)
            s = s.trim { it in TRIM_CHARS || it.isWhitespace() }
            return s.lowercase()
        }

        /**
         * 解析三份文件。
         *
         * ⚠️ **这是同步的、几十毫秒级的 IO + 解析**（三份共 3.7 万行），
         *    调用方必须在后台线程（`Dispatchers.IO`）里跑 —— 在 `onCreate` 里
         *    直接调会卡启动。
         *
         * 格式（见 `server/bin/build_vocab.py` 头部）：
         * ```
         * vocab.base     lemma \t ipa \t freqRank \t level \t defs(|分隔)
         * vocab.forms    surface \t lemma
         * examples.base  lemma \t en \t zh
         * ```
         *
         * @param extraBase **增量**（服务端下发的 `add.json`，同 base 的格式）。
         *        每项是一行原文。它在内置表**之后**合并，所以同名时增量覆盖内置 ——
         *        这正是想要的：增量里出现的词就是「内置表还没有的」，或者
         *        「内置表那份已经过时了的」。
         * @param extraForms 增量的变形行（`surface \t lemma`）
         */
        fun parse(
            base: BufferedReader,
            forms: BufferedReader,
            examples: BufferedReader,
            extraBase: List<String> = emptyList(),
            extraForms: List<String> = emptyList(),
        ): WordTable {
            val entryMap = HashMap<String, WordEntry>(20_000)
            base.forEachLine { line ->
                if (line.isEmpty()) return@forEachLine
                val f = line.split('\t')
                if (f.size < 5 || f[0].isEmpty()) return@forEachLine
                val defs = if (f[4].isEmpty()) emptyList() else f[4].split('|')
                if (defs.isEmpty()) return@forEachLine     // 没释义的条目不该存在
                entryMap[f[0]] = WordEntry(
                    lemma = f[0],
                    ipa = f[1].ifEmpty { null },
                    freqRank = f[2].toIntOrNull() ?: 0,
                    level = f[3].toIntOrNull() ?: 0,
                    defs = defs,
                )
            }

            // ---- 增量（在 assets 之后合并，同名覆盖）----
            extraBase.forEach { line ->
                val f = line.split('\t')
                if (f.size < 5 || f[0].isEmpty()) return@forEach
                val defs = if (f[4].isEmpty()) emptyList() else f[4].split('|')
                if (defs.isEmpty()) return@forEach
                entryMap[f[0]] = WordEntry(
                    lemma = f[0],
                    ipa = f[1].ifEmpty { null },
                    freqRank = f[2].toIntOrNull() ?: 0,
                    level = f[3].toIntOrNull() ?: 0,
                    defs = defs,
                )
            }

            val formMap = HashMap<String, String>(20_000)
            forms.forEachLine { line ->
                val i = line.indexOf('\t')
                if (i <= 0) return@forEachLine
                val lemma = line.substring(i + 1)
                if (lemma.isNotEmpty()) formMap[line.substring(0, i)] = lemma
            }
            extraForms.forEach { line ->
                val i = line.indexOf('\t')
                if (i <= 0) return@forEach
                val lemma = line.substring(i + 1)
                if (lemma.isNotEmpty()) formMap[line.substring(0, i)] = lemma
            }

            val exMap = HashMap<String, MutableList<WordExample>>(8_000)
            examples.forEachLine { line ->
                val a = line.indexOf('\t')
                if (a <= 0) return@forEachLine
                val b = line.indexOf('\t', a + 1)
                if (b <= a) return@forEachLine
                val en = line.substring(a + 1, b)
                val zh = line.substring(b + 1)
                if (en.isEmpty() || zh.isEmpty()) return@forEachLine
                exMap.getOrPut(line.substring(0, a)) { ArrayList(2) }.add(WordExample(en, zh))
            }

            return WordTable(entryMap, formMap, exMap)
        }

        /** 空表：加载失败时的兜底，让界面能显示「词表暂不可用」而不是崩 */
        fun empty(): WordTable = WordTable(emptyMap(), emptyMap(), emptyMap())
    }
}
