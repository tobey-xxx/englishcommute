package com.example.englishcommute.word

import com.example.englishcommute.diag.DiagLog
import com.example.englishcommute.net.ServerConfig
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** 读音就绪状态。**三种情况界面上的文案完全不同**，所以不能用 Boolean 表示 */
sealed interface PronState {
    /** 还没问过（或者正在下）。界面显示一个**有点击反馈但不假装能播**的状态 */
    data object Loading : PronState

    /** 文件在本地，可以直接播 */
    data class Ready(val file: File) : PronState

    /**
     * 服务端还没有这个词的读音。
     *
     * ⚠️ 这**不是错误**：读音是分批预生成的（P0.4 要跑几个小时），
     * 而词表里有 2 万多个词形 —— 任何时刻都可能有词还没轮到。
     * 界面要如实说「还在补」，而不是说「失败了」让用户去重试一个必然失败的动作。
     */
    data object NotPublished : PronState

    /** 网络之类的原因没下成。这个**可以重试** */
    data class Failed(val reason: String) : PronState
}

/**
 * 生词读音：从服务器按需下载那一个词（约 11 KB），存进生词本自己的目录。
 *
 * ## 为什么"按需"而不是"提前全下"
 *
 * 全量约 2 万个词、每个 11 KB ≈ **220 MB**。而用户一个月的生词量通常是几十个。
 * 按需下载的总量是几十个 × 11 KB ≈ **不到 1 MB**。
 *
 * ## 为什么读音不截原素材音频
 *
 * 2.3 之前的设计是从那期节目里截 0.5 秒 —— 数据都是现成的（有词级时间戳），
 * 听起来也"最自然"。**用户否掉了它**，理由是对的：新闻播报里连读、弱读、
 * 吞音极其常见，截出来的片段不适合用来学发音。
 * 而且同一件事出现两种音色（有时原声、有时 TTS）**会干扰记忆锚点**。
 *
 * 所以只有**一个来源**：预生成的标准美式读音。
 *
 * ## 文件名的映射规则（**与服务端必须逐字符一致**）
 *
 * ```
 * safe(w)：白名单 = ASCII 字母 + 数字 + '.' + '-'，其余一律换成 '_'
 *   don't      -> don_t.mp3
 *   well-known -> well-known.mp3     （连字符在 URL/shell/文件系统里都安全）
 *   a.m        -> a.m.mp3
 *   sign       -> sign.mp3           （绝大多数词不受影响）
 * ```
 *
 * ⚠️ 服务端那份在 `server/bin/build_pron_v2.py` 的 `safe()`。
 *
 * ## ⚠️ 这条规则是被一个真 bug 逼出来的
 *
 * 第一版服务端用的是「只留字母、转小写」：`don't` → `dont.mp3`。
 * 两个后果：
 *
 * 1. **客户端下不到**：客户端的 lemma 是 `don't`，去下 `don't.mp3` → 404。
 *    实测 **168 个词形**卡在这个缝里。
 * 2. **更严重：读音是错的**。`he'll` 被当成 `hell` 送进 TTS（读 /hel/ 而不是 /hiːl/）、
 *    `we'll` → `well`、`i'll` → `ill`、`i'd` → `id`。
 *    文件存在、能播、大小正常 —— **只是读的是另一个词的音**。
 *
 * 教训：**「TTS 收到的文本」和「客户端要的文件名」是两件事**，
 * 把词形归一化成"安全"的字符串时，不能顺手把它当成读音的输入。
 */
class PronDownloader(
    private val audioDir: File,
    private val client: OkHttpClient = defaultClient(),
) {

    /**
     * 串行化：下载是用一次点一次触发的，并发不高；而并发写同一个 `.part`
     * 会互相截断（用户连点两次「重试」就够了）。用一个全局锁换取「绝不会互相踩」。
     */
    private val lock = Mutex()

    /** 已经确认「服务端没有」的词。**本次进程内不再重复请求** */
    private val notPublished = HashSet<String>()

    /**
     * 确保 [lemma] 的读音在本地。已在本地时**不发任何请求**。
     *
     * @return 见 [PronState]
     */
    suspend fun ensure(lemma: String): PronState = lock.withLock {
        val target = file(lemma)
        if (target.exists() && target.length() > 0) return@withLock PronState.Ready(target)
        if (lemma in notPublished) return@withLock PronState.NotPublished

        val url = ServerConfig.abs("pron/${pronFileName(lemma)}")
        val tmp = File(audioDir, pronFileName(lemma) + ".part")

        withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder().url(url).build()
                client.newCall(req).execute().use { resp ->
                    if (resp.code == 404) {
                        // ⚠️ 404 与「网络失败」必须分开：前者重试一百次也是 404，
                        //    后者重试有意义。把它们混成一个「失败」会让用户
                        //    对着一个永远不会成功的按钮反复点。
                        DiagLog.i("Word", "读音尚未发布：$lemma（服务端还没生成到）")
                        notPublished += lemma
                        return@withContext PronState.NotPublished
                    }
                    if (!resp.isSuccessful) {
                        return@withContext PronState.Failed("服务器返回 ${resp.code}")
                    }
                    val body = resp.body ?: return@withContext PronState.Failed("响应内容为空")

                    // ⚠️ 写之前先建目录。**这是 3.0.0 埋的一个真 bug**（3.0.3 修）：
                    //    `audioDir` 是 `filesDir/words/audio`，而创建 `words/` 的是
                    //    **另一个类**（`VocabAddSync` 在落盘词表增量时建的）——
                    //    这个子目录从来没有人创建过。
                    //
                    //    后果：下面那句 `tmp.outputStream()` 抛 `FileNotFoundException`，
                    //    而它是 `IOException` 的子类 ⇒ 被下面的兜底翻译成**「网络不通」**。
                    //    用户按提示去查网络（网络明明是好的），真因在本地目录。
                    //    这正是项目纪律里最贵的那条：**错误文案必须指向真实原因。**
                    //
                    //    ⚠️ `mkdirs()` 是幂等的、几乎免费，**每个写自己文件的人都该自己做**，
                    //    不要依赖「别人应该已经建过了」—— 那个假设没有任何东西守着。
                    audioDir.mkdirs()

                    // 写盘单独包一层：**「存不下」和「下不来」是两件事**，
                    // 用户的下一步动作不同（一个清空间/查权限，一个换网络）。
                    // 混成一个「网络不通」会让他查错方向。
                    val writeError = runCatching {
                        // tmp + rename：中途被杀不会留下一个「存在但播不了」的半截文件
                        // （`hasAudio` 只看存在与长度，半截文件会被当成可用）
                        tmp.outputStream().use { out -> body.byteStream().copyTo(out) }
                    }.exceptionOrNull()
                    if (writeError != null) {
                        tmp.delete()
                        val why = when (writeError) {
                            is FileNotFoundException -> "本地存不下（存储空间或权限问题）"
                            is IOException -> "写入本地文件失败"
                            else -> writeError.javaClass.simpleName
                        }
                        DiagLog.w("Word", "读音写入失败：$lemma（$why）")
                        return@withContext PronState.Failed(why)
                    }
                    if (tmp.length() <= 0L) {
                        tmp.delete()
                        return@withContext PronState.Failed("下载到 0 字节")
                    }
                    if (!tmp.renameTo(target)) {
                        tmp.copyTo(target, overwrite = true)
                        tmp.delete()
                    }
                    PronState.Ready(target)
                }
            }.getOrElse { e ->
                tmp.delete()
                val msg = when (e) {
                    is IOException -> "网络不通"
                    else -> e.javaClass.simpleName
                }
                DiagLog.w("Word", "读音下载失败：$lemma（$msg）")
                PronState.Failed(msg)
            }
        }.also { state ->
            if (state is PronState.Ready) {
                DiagLog.act("读音已下载：$lemma（${state.file.length()} 字节）")
            }
        }
    }

    /** 读音文件路径（可能还不存在）。判「能不能播」要用 [has] */
    fun file(lemma: String): File = File(audioDir, pronFileName(lemma))

    fun has(lemma: String): Boolean = file(lemma).let { it.exists() && it.length() > 0 }

    /**
     * 本进程内**已经确认服务端还没有**这个词的读音（收到过 404，[notPublished]）。
     *
     * ⚠️ 存在的唯一理由是**让「补漏」不浪费名额**：
     *    [PronBackfill] 每轮只补固定几个，如果候选里混着这些「问了也是 404」的词，
     *    它们会白占名额，而真正值得重试的（网络失败那些）排在后面轮不到。
     *
     * ⚠️ 它是**进程内**的、重启即失效 —— 这一点对补漏很重要：
     *    服务端的读音是分批补的，所以「今天没有」不等于「明天没有」。
     *    进程内的记性刚好够用：同一次打开 App 里不反复打 404，
     *    而下次打开就会重新问一遍。
     */
    fun isKnownMissing(lemma: String): Boolean = lemma in notPublished

    /** 删掉一个词的读音（移出生词本时调用，别把磁盘上没人用的音频留着） */
    fun delete(lemma: String) {
        runCatching { file(lemma).delete() }
    }

    companion object {
        /**
         * lemma → 文件名。**必须与 `server/bin/build_pron_v2.py` 的 `safe()` 一致**。
         *
         * 不一致的症状是「有些词的读音一直下不下来」，而日志里只有一个 404 ——
         * 那种错很难从现象反推到「一个字符的映射规则不同」。
         * 所以这个函数在服务端脚本的 docstring 里被点名，两边互相指着。
         *
         * ⚠️ 用显式的字符范围判断，**不要用 `Char.isLetterOrDigit()`** ——
         *    它会放行日文、西里尔字母等 Unicode 字母（语料里出现过 `ә` 这种），
         *    而 Python 侧的 `'a' <= c <= 'z'` 不会。两边必须逐字符一致。
         */
        fun pronFileName(lemma: String): String = buildString(lemma.length + 4) {
            for (c in lemma.lowercase()) {
                append(
                    if (c in 'a'..'z' || c in '0'..'9' || c == '.' || c == '-') c else '_'
                )
            }
            append(".mp3")
        }

        /**
         * 文件名映射的**契约样例**。
         *
         * ## 它为什么存在
         *
         * 这个映射在**两个地方**各有一份实现：这里，和服务端的
         * `server/bin/build_pron_v2.py` 的 `safe()`。
         * 两份实现分叉过一次，代价是 168 个词形（见类注释）。
         *
         * 所以把「输入 → 期望输出」固化成**数据**，让两边都能对着它断言：
         *
         * | 谁 | 怎么用 |
         * |---|---|
         * | `PronNameTest`（Kotlin 单测） | 断言本类实现给出这些结果 |
         * | `tools/verify-pron-names.py` | 用 Python 复刻服务端的 `safe()`，跑同一张表 |
         *
         * ⚠️ **这张表是两份实现唯一的共同真相**。加规则、改规则，都要先动这里，
         *    然后让两边的断言同时变红 —— 那才是「改一处」而不是「改一处漏一处」。
         *
         * 样例的选取覆盖了服务端实测出的那 168 个词形里的每一类。
         */
        val NAME_CASES: List<Pair<String, String>> = listOf(
            // 绝大多数词：完全不受影响
            "sign" to "sign.mp3",
            "signed" to "signed.mp3",
            "greenland" to "greenland.mp3",
            // 缩写：撇号换下划线。**这一类是第一版读错音的重灾区**
            "don't" to "don_t.mp3",
            "can't" to "can_t.mp3",
            "i'm" to "i_m.mp3",
            "aren't" to "aren_t.mp3",
            "alzheimer's" to "alzheimer_s.mp3",
            // 缩写与同形的真实词**必须分开**（v1 把它们合成一个文件，于是 he'll 读成 hell）
            "he'll" to "he_ll.mp3",
            "hell" to "hell.mp3",
            "we'll" to "we_ll.mp3",
            "well" to "well.mp3",
            // 连字符保留（它在 URL / shell / 文件系统里都不需要转义）
            "well-known" to "well-known.mp3",
            "back-and-forth" to "back-and-forth.mp3",
            "anti-trust" to "anti-trust.mp3",
            // 点也保留 —— `a.m` 与 `a&m` 靠这一点区分（第一版把两者都变成 a_m 而撞名）
            "a.m" to "a.m.mp3",
            // 数字开头：v1 的「只留字母」会把 1st 变成 st
            "1st" to "1st.mp3",
            "4th" to "4th.mp3",
            // 大写：映射前统一转小写
            "SIGN" to "sign.mp3",
            // 非 ASCII 字母（语料里真的出现过西里尔字母）：一律换成下划线，
            // ⚠️ 特意**不用** Char.isLetterOrDigit() —— 它会放行西里尔/日文，
            //    而 Python 侧的 `'a' <= c <= 'z'` 不会，两边立刻分叉
            "caf\u00e9" to "caf_.mp3",
        )

        /**
         * 读音文件都很小（约 11 KB），但跨境链路偶尔会很慢 ——
         * 读超时给 20 秒，够下完一个 11 KB 的文件很多遍。
         */
        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }
}
