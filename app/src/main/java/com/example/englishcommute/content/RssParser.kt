package com.example.englishcommute.content

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale

/** 一个 feed 的解析结果 */
data class ParsedFeed(
    val items: List<Episode>,
    /**
     * 频道级封面（`<itunes:image href="...">`，在 `<channel>` 里、第一个 `<item>` 之前）。
     *
     * 单独回传而不是塞进每个 [Episode]：它是**源的品牌图**，同一源所有条目共用一张。
     * 界面按来源缓存一张就够，不必为 40 条内容存 40 份（其中大量重复）。
     * 条目自带封面时 [Episode.artworkUrl] 优先，这里只作回退。
     */
    val artworkUrl: String? = null,
)

/**
 * RSS 2.0 解析器（含 iTunes 播客扩展）。
 *
 * **设计原则：容错优先，解析失败返回空列表而不是抛异常。**
 * 播客源站改格式是常态（字段增删、日期格式换写法、命名空间前缀变化），
 * 这个 App 每周都要跑一遍全部源，任何一个源出问题都不能让整次更新崩掉。
 *
 * 用 [XmlPullParser] 而不是 DOM：TED Talks Daily 的 feed 有 2978 条历史、
 * 20 MB 量级，DOM 会整棵加载进内存。
 *
 * ## 输入是流，而且会被主动掐断
 *
 * 调用方给的是网络流，**解析够 [parse] 的 `limit` 条就会停止读取**，
 * 剩下的字节由调用方掐掉连接丢弃（见 `ContentRepository.fetchFeed`）。
 * 所以这里的循环条件 `items.size < limit` 是**成本控制的一部分**，
 * 不只是「少解析几条」——改宽它会让每次更新多下几十 MB。
 */
class RssParser {

    /**
     * 解析一个 feed。
     *
     * @param limit 最多返回几条（[FeedSource.maxEpisodes]）。
     *   解析到足够条数就**提前退出循环**，不会读完整个 feed ——
     *   这既是 2978 条历史源能秒开的关键，也是流式截断能生效的前提。
     */
    fun parse(input: InputStream, source: FeedSource, limit: Int = source.maxEpisodes): ParsedFeed {
        val items = mutableListOf<Episode>()
        var channelArtwork: String? = null
        try {
            val parser = Xml.newPullParser()
            // 关掉命名空间处理：这样 itunes:duration 的 name 会带前缀，
            // 比记 iTunes DTD 的 URL 更省事，也不怕源站换前缀写法
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(input, null)

            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT && items.size < limit) {
                if (event == XmlPullParser.START_TAG) {
                    if (parser.name.equals("item", true)) {
                        runCatching { parseItem(parser, source, channelArtwork) }
                            .getOrNull()
                            ?.let { items.add(it) }
                    } else if (channelArtwork == null && items.isEmpty() &&
                        parser.name.equals("itunes:image", true)
                    ) {
                        // 只在第一个 item 之前收：item 内部也有同名的标签，
                        // 不设这个界会把「第二集的节目封面」当成频道封面
                        channelArtwork = parser.getAttributeValue(null, "href")
                            ?.takeIf { it.startsWith("http") }
                    }
                }
                event = parser.next()
            }
        } catch (_: Exception) {
            // 整个 feed 都读不动（HTML 错误页、连接中断、XML 截断）—— 返回已解出的部分。
            // ⚠️ 主动掐断连接时这里也可能被走到，那是正常路径，不是错误
        }
        return ParsedFeed(items, channelArtwork)
    }

    /** 进入时 parser 停在 `<item>` 的 START_TAG，返回时停在 `</item>` */
    private fun parseItem(
        parser: XmlPullParser,
        source: FeedSource,
        channelArtwork: String?,
    ): Episode? {
        var guid: String? = null
        var title: String? = null
        var pubDate: String? = null
        var audioUrl: String? = null
        var durationRaw: String? = null
        var summary: String? = null
        var artwork: String? = null

        while (true) {
            when (parser.next()) {
                XmlPullParser.END_DOCUMENT -> return null
                XmlPullParser.END_TAG ->
                    if (parser.name.equals("item", true)) break
                XmlPullParser.START_TAG -> when (parser.name.lowercase(Locale.US)) {
                    // 都用「取第一次」的方式：item 里可能混着 <image><title> 这类嵌套标签，
                    // 但它们一定排在真正的条目标题后面
                    "title" -> if (title == null) title = readText(parser)
                    "guid" -> if (guid == null) guid = readText(parser)
                    "pubdate" -> if (pubDate == null) pubDate = readText(parser)
                    "itunes:duration", "duration" ->
                        if (durationRaw == null) durationRaw = readText(parser)
                    "itunes:summary", "itunes:subtitle", "summary", "description" ->
                        if (summary == null) summary = readText(parser)
                    "enclosure" -> audioUrl = parser.getAttributeValue(null, "url")
                    // 自闭合标签，**只读属性不调 readText**：readText 会推进解析器，
                    // 而对 `<itunes:image href="..."/>` 这种空元素，推进就直接跳过了
                    "itunes:image" -> if (artwork == null) {
                        artwork = parser.getAttributeValue(null, "href")
                            ?.takeIf { it.startsWith("http") }
                    }
                }
            }
        }

        val url = audioUrl?.takeIf { it.startsWith("http") } ?: return null
        val cleanTitle = (title ?: "").ifBlank { "未命名内容" }

        return Episode(
            id = stableId(guid, url),
            sourceId = source.id,
            sourceName = source.name,
            title = cleanTitle,
            publishedAt = parseDate(pubDate),
            audioUrl = url,
            durationSec = parseDurationSec(durationRaw),
            summary = stripHtml(summary).take(160),
            // 条目封面优先，回退频道封面。实测两种情况都存在：
            // NPR News Now / Listening Time 的 item 里没有图，只能靠频道封面
            artworkUrl = artwork ?: channelArtwork ?: "",
        )
    }

    /** 读取当前标签的文本。返回前会把 `<tag>text</tag>` 的 END_TAG 消费掉。 */
    private fun readText(parser: XmlPullParser): String {
        return if (parser.next() == XmlPullParser.TEXT) {
            val text = parser.text ?: ""
            parser.next() // 吃掉 END_TAG
            text.trim()
        } else {
            ""
        }
    }

    companion object {

        /**
         * 稳定 ID。优先用 guid —— 同一期内容在多次拉取中必须得到同一个 ID，
         * 否则每次更新都会重复入库。没有 guid 的源（CBC 就是）退回用音频 URL。
         */
        fun stableId(guid: String?, audioUrl: String): String {
            val seed = guid?.takeIf { it.isNotBlank() } ?: audioUrl
            val digest = MessageDigest.getInstance("SHA-1").digest(seed.toByteArray())
            return digest.take(10).joinToString("") { "%02x".format(it) }
        }

        /** 支持 RFC-822 的常见变体；解析不出来返回 0，交给 UI 显示「时长未知」 */
        fun parseDate(raw: String?): Long {
            if (raw.isNullOrBlank()) return 0L
            val s = raw.trim()
            for (pattern in DATE_PATTERNS) {
                val parsed = runCatching {
                    SimpleDateFormat(pattern, Locale.US).apply { isLenient = true }.parse(s)?.time
                }.getOrNull()
                if (parsed != null && parsed > 0) return parsed
            }
            return 0L
        }

        /**
         * `itunes:duration` 实测有三种写法，都得认：
         *  - `305`（纯秒数，NPR 在用）
         *  - `5:05`（分:秒，CBC 在用）
         *  - `00:05:05`（时:分:秒，TED 在用）
         */
        fun parseDurationSec(raw: String?): Int {
            if (raw.isNullOrBlank()) return 0
            val s = raw.trim()
            s.toIntOrNull()?.let { return it.coerceAtLeast(0) }

            val parts = s.split(":")
            val nums = parts.map { it.trim().toIntOrNull() ?: return 0 }
            return when (nums.size) {
                2 -> nums[0] * 60 + nums[1]
                3 -> nums[0] * 3600 + nums[1] * 60 + nums[2]
                else -> 0
            }
        }

        /** 简介里常带 HTML，显示前先剥掉标签与实体 */
        fun stripHtml(raw: String?): String {
            if (raw.isNullOrBlank()) return ""
            return raw
                .replace(Regex("<[^>]+>"), " ")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace(Regex("\\s+"), " ")
                .trim()
        }

        private val DATE_PATTERNS = listOf(
            "EEE, dd MMM yyyy HH:mm:ss Z",
            "EEE, dd MMM yyyy HH:mm:ss z",
            "EEE, dd MMM yyyy HH:mm Z",
            "EEE, dd MMM yyyy HH:mm z",
            "dd MMM yyyy HH:mm:ss Z",
            "EEE, dd MMM yyyy HH:mm:ss",
            "yyyy-MM-dd'T'HH:mm:ssZ",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
        )
    }
}
