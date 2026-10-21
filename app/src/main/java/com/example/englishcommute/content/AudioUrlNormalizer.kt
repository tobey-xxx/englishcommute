package com.example.englishcommute.content

/**
 * 音频地址归一化：把播客统计跳转链展开成「直连候选」列表。
 *
 * ## 为什么需要它
 *
 * 播客的 `enclosure` 几乎都不是真实地址，而是一串**统计跳转**。实测遇到的：
 *
 * ```
 * CBC      https://mgln.ai/e/12/cbc.mc.tritondigital.com/CBC_HOURLYNEWS_P/media/...
 * Up First https://prfx.byspotify.com/e/play.podtrac.com/npr-510318/npr.simplecastaudio.com/...
 * TED      https://dts.podtrac.com/redirect.mp3/pdst.fm/e/pfx.vpixl.com/6qj4J/pscrb.fm/rss/p/nyt.simplecastaudio.com/...
 * ```
 *
 * 每多一层跳转就多一次握手（移动网络下每个 TLS 握手 200–500 ms），
 * 而且中间域一旦被运营商干扰或限速，整条内容就下不动 —— 实测 `mgln.ai`
 * 偶尔会超时，而**同一期内容直连 tritondigital CDN 反而稳定且快 2 倍**。
 *
 * ## 算法
 *
 * 在路径段里从前往后找「第一个看起来像主机名的段」，把它前面的所有段都丢掉，
 * 得到新地址；重复这个过程直到剥不动为止。
 *
 * ```
 * mgln.ai/e/12/cbc.mc.tritondigital.com/CBC_HOURLYNEWS_P/...
 *                 └── 从这里开始 ──┘
 * ```
 *
 * ## 两条硬约束（都是踩过坑的）
 *
 * 1. **query 必须原样保留。** simplecast 系 CDN 靠 `?awEpisodeId=...` 这类参数定位内容，
 *    丢掉 query 后请求会一直挂着直到超时（实测 URL 少 query 就是 TimeoutError）。
 * 2. **只有「后面还有路径段」的段才算主机名。** 否则 `.../audio/128/default.mp3`
 *    里的 `default.mp3` 会被当成主机名，剥出 `https://default.mp3` 这种垃圾地址。
 *
 * ## 安全性
 *
 * 调用方应当**始终把原始 URL 作为最后一个候选**兜底 —— 这个算法只做「更优尝试」，
 * 不承担「找到了正确地址」的责任。剥错了最多白费一次请求。
 */
object AudioUrlNormalizer {

    /** 最多剥几层。实测最深的是 TED（4 层），留点余量 */
    private const val MAX_DEPTH = 6

    /** 主机名形态：至少两段、字母数字与连字符、不以连字符开头结尾 */
    private val HOST_RE = Regex(
        "^[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?(\\.[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?)+$"
    )

    /** 文件扩展名不可能是顶级域名，用来排除 `default.mp3` 这类误判 */
    private val FILE_EXT = setOf(
        "mp3", "m4a", "m4b", "aac", "mp4", "wav", "ogg", "oga", "opus", "flac",
        "html", "htm", "xml", "json", "txt", "css", "js", "jpg", "jpeg", "png", "gif", "webp",
    )

    /**
     * 产出下载候选地址，**按优先级从高到低**：最直连的排最前，原始地址排最后。
     *
     * 任何情况下都至少返回 1 个元素（原始地址），所以调用方可以无条件遍历。
     */
    fun candidates(raw: String): List<String> {
        if (!raw.startsWith("http")) return listOf(raw)

        val chain = ArrayList<String>()
        chain.add(raw)
        var current = raw

        for (i in 0 until MAX_DEPTH) {
            val next = stripOne(current) ?: break
            if (next == current || chain.contains(next)) break
            chain.add(next)
            current = next
        }

        // chain 是「原链 → 逐层剥到最内层」，倒过来让直连地址优先
        return chain.asReversed()
    }

    /** 剥掉一层。剥不动返回 null */
    private fun stripOne(url: String): String? {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd <= 0) return null
        val scheme = url.substring(0, schemeEnd)
        val rest = url.substring(schemeEnd + 3)

        val slash = rest.indexOf('/')
        if (slash < 0) return null
        var path = rest.substring(slash + 1)

        // query 单独摘出来，最后原样拼回
        var query = ""
        val q = path.indexOf('?')
        if (q >= 0) {
            query = path.substring(q)
            path = path.substring(0, q)
        }

        val segs = path.split('/')
        // 从第 2 段开始扫：第 1 段通常是跳转动词（e / redirect.mp3）或版本号
        for (i in 1 until segs.size) {
            if (looksLikeHost(segs[i]) && i < segs.size - 1) {
                val rebuilt = "$scheme://" + segs.subList(i, segs.size).joinToString("/") + query
                if (rebuilt != url) return rebuilt
            }
        }
        return null
    }

    private fun looksLikeHost(seg: String): Boolean {
        if (seg.isEmpty() || !HOST_RE.matches(seg)) return false
        val tld = seg.substringAfterLast('.').lowercase()
        if (tld in FILE_EXT) return false
        return tld.length in 2..6
    }
}
