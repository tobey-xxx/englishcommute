package com.example.englishcommute.net

import android.content.Context
import com.example.englishcommute.content.AudioStore
import com.example.englishcommute.content.Episode
import com.example.englishcommute.content.RssParser
import com.example.englishcommute.diag.DiagLog
import com.example.englishcommute.player.SrtTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.time.OffsetDateTime
import java.util.concurrent.TimeUnit

/**
 * 服务器目录客户端（2.0）。
 *
 * ## 两件事
 *
 * 1. [fetchCatalog]：拉 `/index.json`，转成 App 的 [Episode] 列表 —— **这就是素材库的内容来源**
 * 2. [fetchSubtitles]：把某一期的英文 SRT 与中文译文取回来，落到 App 本来就在用的位置上
 *
 * ## 关键设计：字幕落盘后，App 的其余部分**完全不需要知道它从哪来**
 *
 * 拉回来的文件写进 `AudioStore` 的 `srt/` 与 `zh/`，文件名用 App 自己的 id，
 * 格式与端侧转写/翻译产出的**逐字节同构**：
 *
 * ```
 *   srt/<id>.srt        标准 SRT（SrtTrack.parse 直接吃）
 *   zh/<id>.zh.json     JSON 数组，长度 == 句段数，"" = 这行不需要译文，null = 未翻
 * ```
 *
 * 所以播放页、字幕高亮、译文显示全都一行不改 —— 它们读的还是同两个文件。
 * 这也是为什么 2.0 敢动：**改动面被压缩到「谁生产这两个文件」**。
 *
 * ⚠️ 2.1.0 之后「谁生产」这个问题有了唯一的答案：**只有服务器**。
 *    端侧识别与翻译整条拆掉了，所以这两个文件里不会再出现
 *    「本机转的」「本机翻的」两种来源。
 *
 * ## id 必须与 1.0 对齐
 *
 * 用的是 [RssParser.stableId]（`sha1(guid)[:10]` 的十六进制），种子取服务器发布的
 * **原始 guid**。这样升级上来的用户：
 * - 同一个 id → 素材库里不会出现「同一期两条」
 * - 收藏、播放进度、已下载的音频、已翻好的译文，全部继续有效
 *
 * ⚠️ 服务端**不**复用自己那套 id（`sha1(guid)[:16] + guid 尾部`）。算法只保留在
 *    客户端一处；服务端只负责如实转达 guid。两处各写一份实现 = 两处可能不一致，
 *    而这种不一致的表现是「素材库悄悄多出一倍的条目」。
 */
class ServerCatalog(context: Context) {

    private val appContext = context.applicationContext
    private val audioStore = AudioStore(appContext)

    /** 取字幕的结果 */
    data class Subtitles(
        val srtFileName: String,
        val segmentCount: Int,
        /** 中文译文是否也拿到了（没有也不影响播放，只是没有中文对照） */
        val hasZh: Boolean,
    )

    // 目录与字幕都是小文件（20 KB / 5 KB 级），超时给短一点：
    // 拿不到就立刻退回端侧路径，不该让用户对着进度条等一个连不上的地址。
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    // ------------------------------------------------------------------
    // 目录
    // ------------------------------------------------------------------

    /**
     * 拉服务器目录。
     *
     * 返回 **null 表示「服务器不可用」**（没网、超时、解析失败）——
     * 调用方据此退回 1.0 的 RSS 路径，而不是把素材库清空。
     * 返回空列表是**有意义的另一个结果**：服务器在，但它暂时没有内容。
     */
    /**
     * 最近一次拿目录是否**真的成功**。
     *
     * 用来区分「这条没有服务器字幕」的两种原因 —— 它们的下一步完全相反：
     * - `true`  —— 目录拿到过，只是这一期服务器还没处理（刚发布）→ **用户什么都不用做**
     * - `false` —— 服务器不可达 → **等它恢复**（2.1.0 起没有第二条路了，
     *   端侧识别已经拆掉，「装离线识别」这个选项不存在）
     *
     * ⚠️ 只能放在这一层：`fetchCatalog()` 的调用方拿到的都是 `null`，
     * 而 `null` 同时代表「目录里没有这一条」与「根本拿不到目录」。
     * 在调用方去猜，就一定会把其中一种说错 —— 真机上就是把「等下一轮」
     * 说成了「去装 630 MB 的离线识别」。
     */
    @Volatile
    var lastCatalogOk: Boolean = false
        private set

    suspend fun fetchCatalog(): List<Episode>? = withContext(Dispatchers.IO) {
        if (!ServerConfig.ENABLED) return@withContext null
        val t0 = System.currentTimeMillis()
        val body = runCatching {
            client.newCall(
                Request.Builder()
                    .url(ServerConfig.abs(ServerConfig.CATALOG_PATH))
                    .header("Accept", "application/json")
                    .build()
            ).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                resp.body?.string() ?: error("空响应")
            }
        }.getOrElse { e ->
            lastCatalogOk = false
            DiagLog.w("Server", "目录不可用（${e::class.simpleName}）：退回本机源")
            return@withContext null
        }

        val parsed = runCatching { parse(body) }.getOrElse { e ->
            // 解析失败与「连不上」在这里是一回事：都不能拿来当内容来源。
            // 不抛出去，是为了让上层只有一个判据（null）
            lastCatalogOk = false
            DiagLog.w("Server", "目录解析失败（${e::class.simpleName}）：退回本机源")
            return@withContext null
        }
        // 到这里才算「目录真的可用」—— 上面两条路径都提前返回了
        lastCatalogOk = true
        DiagLog.i(
            "Server",
            "目录 ${parsed.size} 条 · ${System.currentTimeMillis() - t0} ms",
        )
        parsed
    }

    private fun parse(body: String): List<Episode> {
        val root = JSONObject(body)
        val arr: JSONArray = root.optJSONArray("episodes") ?: JSONArray()

        // ★ 2.2.0：**发布闸门的客户端一侧。**
        //
        // 用户的诉求：「确保服务器端字幕都准备好才在 app 端更新，如果字幕未更新好，
        // 不在 app 端刷新出这个音频素材。」服务端那一侧已经在 `cycle.publish` 里
        // 拦了一道（缺字幕/缺中文的条目根本不发布），这里是**第二道**。
        //
        // 为什么客户端还要拦：服务端闸门的判据是「文件在不在」，而网络传输、
        // 部署错版、手工改过索引都可能让它漏过去。漏过去的后果很具体 ——
        // 这一条在列表里长着正常的样子，点开是**一段没有文稿的音频**，
        // 而 2.1.0 已经把端侧的兜底（自己转写/自己翻）整条拆掉了。
        //
        // ⚠️★ `anySrt` 这个前置判断不能省，它是**熔断**：
        //    如果整个目录**一条** `srt` 字段都没有，那不是「漏了几条」，
        //    而是对面根本不发这个字段（老版本 / 协议换了）。
        //    照着字面过滤会让列表**整个变空** —— 那是比「有几条没字幕」
        //    严重得多的故障，而且现象上完全看不出原因。
        val anySrt = (0 until arr.length()).any { i ->
            (arr.optJSONObject(i)?.optString("srt") ?: "").isNotBlank()
        }

        val out = ArrayList<Episode>(arr.length())
        var noGuid = 0
        var noSrt = 0
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val guid = o.optString("guid").takeIf { it.isNotBlank() }
            val audioUrl = o.optString("audioUrl")
            if (audioUrl.isBlank()) continue
            val srt = o.optString("srt").takeIf { it.isNotBlank() }
            if (anySrt && srt == null) {
                noSrt++
                continue
            }
            // ★ 2.0.3：没有 guid 时**不再退回服务端 id**，改用音频地址算。
            //
            //   服务端的 id 是它自己那套算法（`sha1(guid)[:16] + guid 尾部`），
            //   与客户端算出来的**必然不同** —— 用它当 id，同一期内容就会
            //   在素材库里**并存两条**：一条有音频没字幕、一条有字幕没音频，
            //   而后者还没下载过，点下去毫无反应。
            //
            //   退回音频地址与 [RssParser.stableId] 在「源站就没给 guid」时的
            //   行为完全一致（1.0 就是这么做的），所以至少是同一套口径。
            //   真机上出现的是服务端**自己丢了** guid 的情况（条目滚出 RSS 窗口后
            //   反查不回来），那种情况下两个 id 仍然对不上 —— 兜底在
            //   [ContentRepository.refreshFeeds] 里按音频地址合并（改一处别忘了那里）。
            val id = RssParser.stableId(guid, audioUrl)
            if (guid == null) noGuid++
            out.add(
                Episode(
                    id = id,
                    sourceId = o.optString("feedId"),
                    sourceName = o.optString("feedTitle"),
                    title = o.optString("title").ifBlank { "未命名内容" },
                    publishedAt = parseTime(o.optString("publishedAt")),
                    audioUrl = audioUrl,
                    durationSec = o.optInt("durationSec"),
                    // 与 RssParser 一致：列表副标题只要前 160 字
                    summary = o.optString("description").take(160),
                    artworkUrl = o.optString("artworkUrl"),
                    remoteSrt = srt,
                    remoteZh = o.optString("zh").takeIf { it.isNotBlank() },
                    // 字幕的版本（2.1.1）。服务端重新切分过一次就 +1，
                    // 客户端靠它发现本地那份已经过期 —— 见 Episode.remoteSrtVer
                    remoteSrtVer = o.optInt("srtVer"),
                )
            )
        }
        if (noGuid > 0) {
            DiagLog.w("Server", "目录里有 $noGuid 条没有 guid，按音频地址算 id")
        }
        if (noSrt > 0) {
            DiagLog.w(
                "Server",
                "目录里有 $noSrt 条**字幕还没备好**，已跳过（服务端发布闸门漏了）—— " +
                    "它们会在服务端补好之后自己出现",
            )
        }
        return out
    }

    /** 服务器发布的是 ISO-8601 带偏移（`2026-09-21T12:11:07+00:00`）。解析不出来当 0 */
    private fun parseTime(raw: String): Long =
        runCatching { OffsetDateTime.parse(raw).toInstant().toEpochMilli() }.getOrDefault(0L)

    // ------------------------------------------------------------------
    // 字幕
    // ------------------------------------------------------------------

    /**
     * 取这一期的字幕并落盘。拿不到返回 null。
     *
     * ## 它是**幂等**的，而且会被反复调用 —— 这一点很重要
     *
     * 服务器的 `zh` **比 `srt` 晚一轮发布**（它先转写、下一轮才翻译）。
     * 所以「英文字幕已经在盘上、中文还没有」是一个**常态**，
     * 而调用方（`SyncEngine.fetchSubtitlesFor`）会为这种情况**再调一次本函数**：
     * 重下一遍几 KB 的 SRT、顺便把中文取回来。
     *
     * ⚠️ 少了这个「重复调用是安全的」性质，就会出现一个很难发现的空洞：
     *    在服务器翻译完成之前点开过这一期的用户，**永远只有英文** ——
     *    2.1.0 之前这条空洞由端侧翻译兜住，拆掉端侧之后它就没有兜底了。
     *    所以「重下 SRT」这点浪费是刻意的：它换来的是**只有一条代码路径**，
     *    而两条路径（只取中文 / 取两个）迟早会有一条被漏掉。
     *
     * ## 顺序与原子性
     *
     * 先 SRT 后中文，各自**原子落盘**（tmp + rename）。SRT 是「能不能看」的前提，
     * 中文只是增强 —— 所以中文失败**不**让整件事失败。
     *
     * ## 为什么中文要校验长度
     *
     * App 侧有一条硬契约：译文数组的长度必须等于句段数，否则**整份丢弃**
     * （错位的译文比没有译文更糟，它会张冠李戴）。服务器理应对得上，
     * 但这是跨进程、跨机器的约定，客户端不能假设。
     * ⚠️ 2.1.0 起「对不上就不写」之后**没有别的补救**了（以前还能退回端侧翻译），
     *    所以这一条从「慎重的取舍」变成了**唯一防错线** —— 别放宽它。
     *    句数对不上说明两边对同一份音频切出了不同的句段，宁可不写：
     *    下一轮重新取一次，两份是同时出的，对上的概率很大。
     */
    suspend fun fetchSubtitles(episode: Episode): Subtitles? = withContext(Dispatchers.IO) {
        val srtPath = episode.remoteSrt ?: return@withContext null
        val t0 = System.currentTimeMillis()
        val srtText = get(ServerConfig.abs(srtPath)) ?: run {
            DiagLog.w("Server", "取字幕失败（${episode.title.take(24)}）")
            return@withContext null
        }
        val lines = runCatching { SrtTrack.parse(srtText) }.getOrNull()
        if (lines.isNullOrEmpty()) {
            DiagLog.w("Server", "取回的字幕解析不出句段，弃用：${episode.title.take(24)}")
            return@withContext null
        }

        val name = episode.srtFileName ?: "${episode.id}.srt"
        audioStore.writeSrt(episode.copy(srtFileName = name), srtText)

        // 中文：尽力而为
        var hasZh = false
        episode.remoteZh?.let { zhPath ->
            val zhText = get(ServerConfig.abs(zhPath))
            val zh = zhText?.let { parseZhArray(it) }
            if (zh != null && zh.size == lines.size) {
                audioStore.writeTranslation(episode, zh)
                hasZh = true
            } else if (zh != null) {
                // 宁可不写：长度对不上会被 App 整份丢弃，留着只会误导排查
                DiagLog.w(
                    "Server",
                    "译文句数 ${zh.size} ≠ 字幕 ${lines.size}，不采用（${episode.title.take(20)}）",
                )
            }
        }
        DiagLog.i(
            "Server",
            "☁ 取字幕 ${lines.size} 句${if (hasZh) " + 中文" else ""} · " +
                "${System.currentTimeMillis() - t0} ms · ${episode.title.take(24)}",
        )
        Subtitles(srtFileName = name, segmentCount = lines.size, hasZh = hasZh)
    }

    private fun parseZhArray(text: String): List<String?>? = runCatching {
        val arr = JSONArray(text)
        (0 until arr.length()).map { i -> if (arr.isNull(i)) null else arr.getString(i) }
    }.getOrNull()

    /** 取一个 URL 的正文。失败返回 null（这里不抛 —— 调用方的判据统一是 null） */
    private fun get(url: String): String? = runCatching {
        client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) return null
            resp.body?.string()
        }
    }.getOrNull()
}
