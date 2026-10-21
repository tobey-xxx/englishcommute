package com.example.englishcommute.word

import android.content.Context
import com.example.englishcommute.diag.DiagLog
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 词表的加载与缓存。
 *
 * ## 它是**两层来源叠加**（3.3.0 起第一层可以整份被替换）
 *
 * ```
 *   ① 主表   assets/vocab/ 下那三个   发版时定下来的，只读
 *            或 filesDir/words/table/<v>/ 下那三个   用户点「更新词库」下来的整包 ← 优先
 *   ② 增量   filesDir/words/vocab.add.json     每次同步时更新（实测 141 词 / 13 KB）
 * ```
 *
 * ⚠️ 上面这几行路径**刻意不写通配符**。Kotlin 的块注释是**可以嵌套**的，
 *    而「斜杠 + 星号」会在这里开出一层新的注释层 —— 后面没有对应的收尾，
 *    于是编译器报 `Unclosed comment`，报的**行号是文件末尾**，
 *    和真正出问题的那一行隔着几十行，排查方向一开始完全是错的。
 *    （本工程在 XML 那边被同一条形状的规则绊过三次：注释里不许出现连续两个减号。）
 *
 * ⚠️ ① 的判断必须是「**下载的整包优先、没有才用内置**」，而不是「两个都查」：
 *    整包就是内置那份的超集（同一个构建脚本、更新的语料），合并两份只是白花
 *    内存和加载时间（解析各是 2 MB 级别）。判据落在**文件**上（三个文件都在
 *    才算装好），见 [VocabTableSync.tableDir]。
 *
 * 合并规则：**增量在后，同名覆盖**。合并在 [WordTable.parse] 里一次做完，
 * 不是「查两次」—— 后者会让「先查哪层」变成散落在各调用点的隐式约定。
 *
 * ## 为什么不放在 `AppContainer` 的构造里
 *
 * 容器是在 `Application.onCreate` 里同步构造的，那条路径上任何几十毫秒的东西
 * 都会变成**启动变慢**。而词表只在用到它的两处地方需要：
 * 加词时查释义、复习卡上取例句。用户可能整个会话都不进生词本，
 * 那就一次都不该付这个代价。
 *
 * 所以做成**首次用到才加载、加载后常驻**。
 *
 * ## 为什么失败时**不缓存**空表
 *
 * 如果失败也把 `WordTable.empty()` 存进缓存，那一次偶发的失败
 * （比如加载时恰好内存紧张）就会变成**本次会话永久失效** ——
 * 用户看到的是「词表暂不可用」，而明明重试一次就好了。
 * 失败时返回空表给调用方（界面能显示得体面），但**不写入 [cached]**，
 * 下一次调用会重新尝试。
 */
class WordTableLoader(
    private val context: Context,
    /**
     * 增量。**注入而不是自己 new** —— 「增量文件在哪」这件事只该有一处
     * （[VocabAddSync] 里），否则 loader 和 sync 会各自拼一个路径出来，
     * 而那种分叉的表现是「同步明明成功了，查词却没有新词」。
     */
    private val vocabAdd: VocabAddSync,
    /**
     * 词表**整包**（3.3.0）。同上：路径只该有一处。
     *
     * 留了默认值 `null`（= 这一份构建没有整包能力，永远用内置表）——
     * 于是既有的调用点与单测都不用改，而「有没有整包」这件事在构造处就能看出来。
     */
    private val vocabTable: VocabTableSync? = null,
) {

    @Volatile
    private var cached: WordTable? = null

    /** 避免两处同时触发加载时把 2 MB 文件解析两遍 */
    private val lock = Mutex()

    @Volatile
    var lastError: String? = null
        private set

    /** 词表是否已经就绪（界面据此决定要不要显示「正在准备」） */
    val ready: Boolean get() = cached != null

    /**
     * 让缓存失效。**增量更新之后必须调它** —— 否则内存里还是旧表，
     * 用户点了「更新素材」、日志也说「词表增量已更新」，而查词结果没变。
     *
     * 代价是下次 [get] 会重新解析 2 MB assets（几十到几百毫秒）。
     * 但这件事只在**版本真的变了**时发生（一天几次），所以可以接受 ——
     * 换成「只合并增量、不动主表」需要 WordTable 可变，
     * 而不可变正是它现在唯一的并发保证。
     */
    fun invalidate() {
        cached = null
    }

    /**
     * 取词表。首次调用会解析三份 assets（真机上约几十到几百毫秒），
     * 所以**必须在协程里调**。
     */
    suspend fun get(): WordTable {
        cached?.let { return it }
        return lock.withLock {
            cached?.let { return@withLock it }
            val loaded = loadOnce()
            if (loaded != null) cached = loaded
            loaded ?: WordTable.empty()
        }
    }

    /**
     * 主表从哪儿来：`(目录, 日志里报的来源)`。
     *
     * 目录为 `null` = 用内置 assets。**两件事必须一起算出来** ——
     * 分两处各算一次的话，「读的是哪个目录」和「日志说读的是哪个」会分叉，
     * 而那种日志比没有日志更坏（它会把人引到错的那一边）。
     */
    private fun sourceDir(): Pair<File?, String> {
        val sync = vocabTable ?: return null to "内置"
        val dir = sync.tableDir() ?: return null to "内置"
        return dir to "整包 ${sync.localVersion()}"
    }

    private suspend fun loadOnce(): WordTable? = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()
        val (dir, source) = sourceDir()
        runCatching {
            val (extraBase, extraForms) = vocabAdd.localLines()
            // ① 有用户自己更新过的整包就用它（内容更全），没有才用内置 assets
            if (dir != null) {
                WordTable.parse(
                    File(dir, "vocab.base").bufferedReader(),
                    File(dir, "vocab.forms").bufferedReader(),
                    File(dir, "examples.base").bufferedReader(),
                    extraBase,
                    extraForms,
                )
            } else {
                val assets = context.assets
                assets.open(FILE_BASE).bufferedReader().use { base ->
                    assets.open(FILE_FORMS).bufferedReader().use { forms ->
                        assets.open(FILE_EXAMPLES).bufferedReader().use { examples ->
                            WordTable.parse(base, forms, examples, extraBase, extraForms)
                        }
                    }
                }
            }
        }.onSuccess { loaded ->
            // ⚠️ 报出**来源**，不只是数量：3.3.0 起同一份词表有两个可能的出处
            //    （内置 assets / 用户更新过的整包），而「查不到某个词」这个现象的
            //    下一步完全取决于现在是哪一份在生效。只报数量的话，
            //    装了整包和没装整包的日志会一模一样。
            DiagLog.i(
                "Word",
                "词表已加载（$source）：${loaded.entryCount} 词条 · ${loaded.formCount} 变形 · " +
                    "${loaded.exampleWordCount} 个词有例句（${System.currentTimeMillis() - t0}ms）" +
                    // 增量单独报一行数：它和主表是两个来源，混在一起看不出问题出在哪
                    " · 含增量 ${vocabAdd.localVersion() ?: "无"}",
            )
        }.onFailure { e ->
            lastError = e.message
            DiagLog.w("Word", "词表加载失败（下次会重试）：${e.message}")
        }.getOrNull()
    }

    private companion object {
        const val FILE_BASE = "vocab/vocab.base"
        const val FILE_FORMS = "vocab/vocab.forms"
        const val FILE_EXAMPLES = "vocab/examples.base"
    }
}
