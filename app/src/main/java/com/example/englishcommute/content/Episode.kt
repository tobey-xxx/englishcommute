package com.example.englishcommute.content

import org.json.JSONObject

/** 一期内容的处理状态 */
enum class EpisodeStatus {
    /** 已从 RSS 发现，音频还没下 */
    Discovered,

    /** 音频下载中 */
    Downloading,

    /** 音频已就绪，等待转写 */
    Downloaded,

    /** 正在转写字幕 */
    Transcribing,

    /** 音频 + 字幕都好了，可以正常播放 */
    Ready,

    /**
     * 处理失败，[Episode.error] 里有人话说明。
     *
     * ⚠️ **只用于「音频本身不可用」**（下载失败、文件不存在）。
     * 「字幕还欠着」不是失败 —— 音频在盘上就照样能听，标成 Failed 会让这一行
     * 变得完全点不动（见 [Episode.isPlayable] 那段血泪）。
     */
    Failed,
    ;

    /**
     * ⚠️ 这里曾经有一个 `val canPlay = this == Ready || this == Downloaded`，
     * 已经删掉了 —— 它就是 2.0.1「点不动」那个 bug 的判据。
     *
     * 想判断能不能播，用 [Episode.isPlayable]。留一个同名的 status 属性在这里，
     * 下一个人（包括我自己）会本能地去用它，而它答的是另一个问题。
     */
    val isBusy: Boolean get() = this == Downloading || this == Transcribing
}

/**
 * 一期可播放内容。
 *
 * [id] 用 guid（没有则用音频 URL）的哈希，保证同一期内容反复拉取不会产生重复条目。
 */
data class Episode(
    val id: String,
    val sourceId: String,
    val sourceName: String,
    val title: String,
    val publishedAt: Long,
    val audioUrl: String,
    val durationSec: Int,
    val status: EpisodeStatus = EpisodeStatus.Discovered,
    /** 音频文件名（相对音频目录），未下载为 null */
    val audioFileName: String? = null,
    /** 字幕文件名（相对字幕目录），未转写为 null */
    val srtFileName: String? = null,
    val segmentCount: Int = 0,
    val error: String? = null,
    /**
     * 音频落盘的时刻（毫秒）。**保留策略的唯一判据**，0 表示还没下载过。
     *
     * ## 为什么不能用文件的 `lastModified`
     *
     * [com.example.englishcommute.content.AudioStore.touch] 在每次播放时都会刷新
     * 文件的修改时间（那是 LRU 淘汰需要的「最近使用时间」）。用它当保留期基准，
     * 结果是「听过一次的音频永远不会过期」—— 而用户要的恰恰相反：
     * 三天前听过的同样要清掉，留下的是他**手动收藏**的那些。
     *
     * ## 为什么必须在 [toJson] 里落盘
     *
     * 保留期是跨进程的：今天下载、明天重启、后天该删。只放在内存里，
     * 一次重启就把所有判据清零，表现是「说过三天删，其实永远不删」。
     */
    val downloadedAt: Long = 0L,
    /** RSS 里的简介，用于列表副标题 */
    val summary: String = "",
    /**
     * 封面图地址。**条目自带优先，回退频道封面**（两者都来自 RSS 的 `itunes:image`）。
     *
     * 空串表示源站没给图，界面按要求退化成占位块 —— 不去猜、不去第三方接口找，
     * 因为「猜出来的封面」和「真实封面」在视觉上无法区分，一旦猜错就是错的品牌信息。
     */
    val artworkUrl: String = "",
    /**
     * 服务器上这份字幕的相对路径（2.0）。null = 这一期的字幕不是从服务器来的
     * （走了 RSS 退路，或者本来就是端侧转写的）。
     *
     * ## 为什么要落盘
     *
     * 取字幕这一步发生在「音频下完之后」，而那可能晚于内容刷新（今天没网、
     * 后天后台补做）。只放在内存里的话，冷启之后就不记得要去哪儿取，
     * 那一期会永远停在「有音频没字幕」。
     *
     * ## 为什么客户端不靠 guid 现算
     *
     * 服务端的文件名用的是它自己的 id（`sha1(guid)[:16] + guid 尾部`），
     * 与客户端算出来的 id 不是一回事。让服务端如实转达路径，
     * 比在客户端复刻一套路径算法可靠得多。
     */
    val remoteSrt: String? = null,
    /** 服务器上中文译文的相对路径，null = 没有（就只能端侧翻译了） */
    val remoteZh: String? = null,
    /**
     * 服务器那份字幕的**版本**（2.1.1）。服务端每把字幕重新切分一次就 +1。
     *
     * 为什么要版本号：服务端在 2.1.1 把字幕从「每 2–4 秒一条」改成了
     * 「一句一条」（原来跨行的句子只有第一行有中文，用户看到的是
     * 「有些段落只有英文没有中文」）。**内容变了，但文件名没变** ——
     * 没有这个数字，手机认出「本地已经有一份字幕了」就永远不再取，
     * 那份旧的细粒度字幕会一直配着新的粗粒度译文，句数对不上，
     * 译文被整份丢弃（这是客户端的硬契约），表现是「中文全没了」。
     */
    val remoteSrtVer: Int = 0,
    /**
     * 本地那份字幕是从哪一版下载的（2.1.1）。
     *
     * 它是**本地字段**：刷新索引时必须沿用旧值（见 ContentRepository 的合并），
     * 否则每次刷新 RSS 都会以为「本地没有字幕」，把几 KB 的字幕重下一遍。
     */
    val srtVerOnDisk: Int = 0,
) {
    /**
     * 这一条**现在能不能播**。全工程唯一的判断入口。
     *
     * ## 判据是「音频文件名在」，不是 [status]
     *
     * ★ 这是 2.0.1 真机一个真 bug 的修复点，记录在这里免得再犯。
     *
     * 当时 `transcribeOne` 在「服务器还没转写好这一期 + 本机没装识别模型」时
     * 把状态标成了 [EpisodeStatus.Failed]（本意是「这条的字幕欠着」），
     * 而 `Failed.canPlay == false` —— 于是列表里那一行**整行点不动**：
     * 不进播放页、不弹提示、日志里连一行记录都没有。
     * 而它恰恰是列表最上面、用户最想点的那一条（刚发布的新一期）。
     *
     * 音频在不在盘上是**文件系统**的事实；[status] 只是**流程走到哪一步**的标签。
     * 拿流程标签去回答「这个文件能不能开」，迟早会对不上。真正的不存在由播放页
     * 做最后检查 —— 那里能给出「音频文件不在了，请回素材库重新更新内容」这种说法。
     *
     * 顺带修正了另一个后果：转写中的条目（[EpisodeStatus.Transcribing]，此时音频
     * 早在盘上、只是字幕还在写）过去也被 `!status.isBusy` 挡在门外，点了没反应。
     */
    val isPlayable: Boolean get() = audioFileName != null

    /** 时长的人话表示，未知时返回 null 交给 UI 处理 */
    val durationLabel: String?
        get() = if (durationSec > 0) {
            val m = durationSec / 60
            val s = durationSec % 60
            "%d:%02d".format(m, s)
        } else null

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("sourceId", sourceId)
        put("sourceName", sourceName)
        put("title", title)
        put("publishedAt", publishedAt)
        put("audioUrl", audioUrl)
        put("durationSec", durationSec)
        put("status", status.name)
        put("audioFileName", audioFileName ?: JSONObject.NULL)
        put("srtFileName", srtFileName ?: JSONObject.NULL)
        put("segmentCount", segmentCount)
        put("downloadedAt", downloadedAt)
        put("error", error ?: JSONObject.NULL)
        put("summary", summary.take(300))
        put("artworkUrl", artworkUrl)
        put("remoteSrt", remoteSrt ?: JSONObject.NULL)
        put("remoteZh", remoteZh ?: JSONObject.NULL)
        put("remoteSrtVer", remoteSrtVer)
        put("srtVerOnDisk", srtVerOnDisk)
    }

    companion object {
        fun fromJson(o: JSONObject): Episode = Episode(
            id = o.getString("id"),
            sourceId = o.optString("sourceId"),
            sourceName = o.optString("sourceName"),
            title = o.optString("title"),
            publishedAt = o.optLong("publishedAt"),
            audioUrl = o.optString("audioUrl"),
            durationSec = o.optInt("durationSec"),
            status = runCatching {
                EpisodeStatus.valueOf(o.optString("status", EpisodeStatus.Discovered.name))
            }.getOrDefault(EpisodeStatus.Discovered),
            audioFileName = o.optStringOrNull("audioFileName"),
            srtFileName = o.optStringOrNull("srtFileName"),
            segmentCount = o.optInt("segmentCount"),
            // 老索引里没有这个字段 → 0。**不能在这里就地补成 now()**：
            // 迁移策略要能区分「刚迁移」与「刚下载」，所以由
            // ContentRepository.migrateRetention() 在 load 之后统一处理
            downloadedAt = o.optLong("downloadedAt"),
            error = o.optStringOrNull("error"),
            summary = o.optString("summary"),
            artworkUrl = o.optString("artworkUrl"),
            remoteSrt = o.optStringOrNull("remoteSrt"),
            remoteZh = o.optStringOrNull("remoteZh"),
            // 老索引里没有这两个 → 0。服务端那边第一版切分（原始细粒度）也是 0，
            // 所以「本地 0 配服务端 0」是自洽的，不会凭空触发一次重下
            remoteSrtVer = o.optInt("remoteSrtVer"),
            srtVerOnDisk = o.optInt("srtVerOnDisk"),
        )

        private fun JSONObject.optStringOrNull(key: String): String? =
            if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }
    }
}
