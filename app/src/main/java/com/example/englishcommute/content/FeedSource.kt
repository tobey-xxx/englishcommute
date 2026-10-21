package com.example.englishcommute.content

/**
 * 一个内容源。
 *
 * @param maxEpisodes 单次只取最新几条。**这个不能省**：
 *   NPR Up First 的 feed 有 500 条历史、TED Talks Daily 有 2978 条，
 *   全量解析既慢又白耗内存，通勤场景也只需要当天的内容。
 */
data class FeedSource(
    val id: String,
    val name: String,
    val url: String,
    val maxEpisodes: Int,
    val note: String = "",
)

/**
 * 经过实测筛选的 11 个活跃源（2026-09-17 由 4 个扩展）。**不要随意加源**，
 * 下面每个都是踩过坑留下的：
 *
 * - BBC 6 Minute English / Learning English：SSL 层直接连接失败
 * - VOA Learning English：feed 能访问、31 条音频能下，但**最新内容停在 2025-02-20**
 *   —— 典型的「HTTP 200 但已经死了」，只看状态码会被骗
 * - NBC 不通、ABC 无音频、多数 `feeds.megaphone.fm` 404
 *
 * ## 选源的硬约束：**服务器**能抓到（4.3.0 起；此前写的是「手机直连可达」）
 *
 * ⚠️ ★ 这条判据 2026-10-18 **改了前提**，原因很具体：2.0 之后**音频与字幕
 * 都由服务器托管**（`/audio/` `/subs/`），手机只跟 `YOUR_DOMAIN` 说话。
 * 所以「源站被墙」不再是否决条件 —— 只要**香港的服务器能抓到**，手机上就能听。
 *
 * 于是下面这些当年被否掉的源**要按新判据重新评估**，别直接照抄旧结论：
 * BBC 6 Minute English（当年 SSL 直连失败）现在可用，已在 4.3.0 加入。
 * CBC 的 `www.cbc.ca` 当年被判 DNS 污染，现在也只剩「服务器能不能抓」这一问
 * —— 实测能抓，已加入 CBC World Report。
 *
 * ⚠️ 仍然成立的那一半：**别在 Mac 上 curl 通就当结论** —— 开发机挂着 TUN 代理，
 * 测出来的是代理的可达性。判断方法：`ifconfig | grep utun` 看有没有虚拟网卡，
 * `netstat -rn -f inet` 看默认路由是不是被切成 `1/2`、`2/7` 这种半区段。
 * 开发机（macOS）通常挂着 TUN 模式代理，`--noproxy` / `ProxyHandler({})`
 * 只绕开环境变量，流量照样被 utun 接走，测出来的全是代理的可达性。
 * 判断方法：`ifconfig | grep utun` 看有没有虚拟网卡，
 * `netstat -rn -f inet` 看默认路由是不是被切成 `1/2`、`2/7`、`4/6`、`8/5` 这种半区段。
 *
 * 现在这批源的宿主（`feeds.npr.org` / `feeds.simplecast.com`）
 * 都是**在用户手机上实测拉通过**的，不是我在 Mac 上试出来的。
 *
 * ## 已确认不可用（不要加回来）
 *
 * | 源 | 现象 | 证据 |
 * |---|---|---|
 * | ~~CBC The World This Hour~~ | ~~手机直连 reset~~ | ⚠️ **4.3.0 已按新判据推翻**：服务器实测能抓 `www.cbc.ca/podcasting/includes/wr.xml`（545 条、全部带音频、最新一期 0.4 天前）。当年的 DNS 污染只影响**手机直连**，不影响服务器托管 |
 * | **Scientific American 60-Second** | **HTTP 200 但内容停在 2015 年** | 4.3.0 实测：feed 能抓、334 条都带音频，但最新一期是 **2015-02-14**。这是「只看状态码会被骗」的又一个实例，别加 |
 */
object FeedSources {

    val NPR_NEWS_NOW = FeedSource(
        id = "npr-news-now",
        name = "NPR News Now",
        url = "https://feeds.npr.org/500005/podcast.xml",
        maxEpisodes = 2,
        note = "每小时更新 · 约 4:40 · feed 8.6 KB / 4 条 · duration 是纯秒数 (280)",
    )

    val NPR_UP_FIRST = FeedSource(
        id = "npr-up-first",
        name = "NPR Up First",
        url = "https://feeds.npr.org/510318/podcast.xml",
        maxEpisodes = 1,
        note = "每日更新 · 约 13:10 · feed 1.9 MB / 500 条（必须限条数）",
    )

    /**
     * 2026-09-17 顶替 CBC The World This Hour 的位置。
     *
     * 选它的理由：CBC 在大陆被封（见上），需要一个「日更 + 十几分钟」的新闻位。
     * 而它跟 NPR News Now / Up First 同宿主，等于**复用已经验证过的网络路径**，
     * 不引入新的可达性风险。实测字段也干净：`pubDate` 用标准 `+0000`、
     * `duration` 是纯秒数（476 / 653 / 794），不用特殊解析分支。
     */
    val NPR_CONSIDER_THIS = FeedSource(
        id = "npr-consider-this",
        name = "NPR Consider This",
        url = "https://feeds.npr.org/510355/podcast.xml",
        maxEpisodes = 1,
        note = "每日更新 · 约 8–13 分钟 · feed 7.0 MB / 1750 条（必须限条数）",
    )

    val TED_TALKS_DAILY = FeedSource(
        id = "ted-talks-daily",
        name = "TED Talks Daily",
        url = "https://feeds.simplecast.com/54nAGcIl",
        maxEpisodes = 1,
        note = "每日更新 · **12–40 分钟**（实测最新两条为 23:52 / 36:46）· feed 19.7 MB / 2979 条",
    )

    // ------------------------------------------------------------------
    // 2026-09-17 扩展：从 4 个源加到 11 个
    // ------------------------------------------------------------------
    //
    // ## 选源策略：只在「已经证明可达的宿主」上加
    //
    // 上一轮踩过的坑是「开发机 curl 通 ≠ 手机通」（见上面的宿主说明），
    // 而验证可达性必须靠真机、一轮就是一次完整的装机测试。所以这一批
    // **全部落在 `feeds.npr.org` 与 `feeds.simplecast.com`** —— 这两个宿主
    // 上已有的 4 个源天天在用，路径是确定的。换宿主（transistor / acast /
    // fireside / dw.com 等）能拿到更多好节目，但那要另开一轮真机验证，
    // 不能和这次混在一起：一旦某个源不通，你会分不清是「新源的问题」
    // 还是「原有源坏了」。
    //
    // ## 单条时长是选源的一等条件
    //
    // 这个 App 的场景是通勤路上听 + 看字幕，所以「单集时长」和「更新频率」
    // 一样重要。全部候选都实测了最新两条的 `itunes:duration`：
    //
    // | 源 | 单集 | 更新 |
    // |---|---|---|
    // | The Indicator | 8–9 分钟 | 工作日 |
    // | Short Wave | 13 分钟 | 工作日 |
    // | NPR Politics | 18 分钟 | 每日 |
    // | Life Kit | 17 分钟 | 每周 2 次 |
    // | Planet Money | 18–29 分钟 | 每周 2 次 |
    // | Code Switch | 36 分钟 | 每周 |
    // | Listening Time | 24 分钟 | 每周 |
    //
    // **被主动排除的**：StoryCorps（最新一集 47 分钟，且更新停在 9 天前）、
    // Throughline（50 分钟）。两者内容都好，但都太长了 —— 通勤场景里
    // 一条 47 分钟的音频听完就已经到站两次，加进来只会挤掉别的源。
    //
    // ⚠️ Code Switch 实测 36 分钟。留着是因为它的选题（社会文化）
    //    在这一批里没有替代品。2.1.0 起它**不再有「转写较慢」这个代价**：
    //    字幕由服务器批量做（与客户端时长无关），手机上只是取回来。
    //    换句话说，选源现在只看「内容值不值得听」，不再看「转不转得起」。

    /** 科学短节目。工作日更新，节奏快、信息密度高，是最适合通勤的一档 */
    val NPR_SHORT_WAVE = FeedSource(
        id = "npr-short-wave",
        name = "NPR Short Wave",
        url = "https://feeds.npr.org/510351/podcast.xml",
        maxEpisodes = 1,
        note = "工作日更新 · 约 13:29 · 科学 · feed 5.9 MB / 1559 条（靠流式截断，实测读入 16 KB）",
    )

    val NPR_LIFE_KIT = FeedSource(
        id = "npr-life-kit",
        name = "NPR Life Kit",
        url = "https://feeds.npr.org/510338/podcast.xml",
        maxEpisodes = 1,
        note = "每周 2 次 · 约 17 分钟 · 生活实用建议 · feed 3.3 MB / 1171 条",
    )

    val NPR_PLANET_MONEY = FeedSource(
        id = "npr-planet-money",
        name = "Planet Money",
        url = "https://feeds.npr.org/510289/podcast.xml",
        maxEpisodes = 1,
        note = "每周 2 次 · 18–29 分钟 · 经济故事 · feed 2.2 MB / 355 条",
    )

    /**
     * Planet Money 的日更短版。**这一批里最适合通勤的一条**：
     * 8–9 分钟，正好一段地铁；工作日更新，等于每个工作日都有新的。
     */
    val NPR_INDICATOR = FeedSource(
        id = "npr-indicator",
        name = "The Indicator",
        url = "https://feeds.npr.org/510325/podcast.xml",
        maxEpisodes = 1,
        note = "工作日更新 · 约 8–9 分钟 · 经济短评 · feed 1.6 MB / 300 条",
    )

    val NPR_POLITICS = FeedSource(
        id = "npr-politics",
        name = "NPR Politics",
        url = "https://feeds.npr.org/510310/podcast.xml",
        maxEpisodes = 1,
        note = "每日更新 · 约 18 分钟 · 美国政治 · feed 7.5 MB / 1750 条",
    )

    val NPR_CODE_SWITCH = FeedSource(
        id = "npr-code-switch",
        name = "NPR Code Switch",
        url = "https://feeds.npr.org/510312/podcast.xml",
        maxEpisodes = 1,
        note = "每周 · 约 36 分钟 · 社会文化 · feed 1.8 MB / 648 条",
    )

    /**
     * 唯一的「英语教学」源，也是唯一不在 NPR 系里的新源。
     *
     * 选它的理由：这个 App 的其他源全是**给母语者听的新闻**，语速快、
     * 专有名词多。加一条「慢速、面向学习者」的内容，才是真正的分层 ——
     * 状态不好或时间碎的时候有东西可听。
     */
    val LISTENING_TIME = FeedSource(
        id = "listening-time",
        name = "Listening Time",
        url = "https://feeds.simplecast.com/obaHfEr1",
        maxEpisodes = 1,
        note = "每周 · 约 24 分钟 · 面向英语学习者 · feed 1.2 MB / 367 条 · 宿主同 TED",
    )

    // ------------------------------------------------------------------
    // 4.3.0：5 个非 NPR 源（2026-10-18 在**香港服务器上**实测通过）
    // ------------------------------------------------------------------
    // 判据：HTTP 200 + 条目都带音频 + **最新一期够新**（< 7 天）。
    // ⚠️ 最后一条不能省 —— Scientific American 那个 feed 就是「200 但死透了」。

    /** 唯一的「慢速教学向」日更/周更源，也是唯一的英音教学节目 */
    val BBC_6MIN = FeedSource(
        id = "bbc-6min",
        name = "BBC 6 Minute English",
        url = "https://podcasts.files.bbci.co.uk/p02pc9tn.rss",
        maxEpisodes = 1,
        note = "每周四 · 约 7 分钟 · 慢速对话、面向学习者 · 345 条 · 服务器可达",
    )

    /** 英音旗舰。长（28 分钟），适合整段通勤 */
    val BBC_GLOBAL_NEWS = FeedSource(
        id = "bbc-global",
        name = "BBC Global News",
        url = "https://podcasts.files.bbci.co.uk/p02nq0gn.rss",
        maxEpisodes = 1,
        note = "每日 · 约 28 分钟 · 英音新闻旗舰 · 285 条 · 服务器可达",
    )

    /** 最短的商业新闻：5 分半，碎片时间能听完 */
    val WSJ_WHATS_NEWS = FeedSource(
        id = "wsj-whatsnew",
        name = "WSJ What's News",
        url = "https://video-api.shdsvc.dowjones.io/api/podcasts/feed/the%20wall%20street%20journal%20whats%20news",
        maxEpisodes = 2,
        note = "每日 · 约 5.5 分钟 · 商业 · 600 条 · 宿主 dowjones（服务器可达）",
    )

    /** 加拿大口音 + 10 分钟日更，补上「北美但非美国」的位置 */
    val CBC_WORLD_REPORT = FeedSource(
        id = "cbc-world",
        name = "CBC World Report",
        url = "https://www.cbc.ca/podcasting/includes/wr.xml",
        maxEpisodes = 2,
        note = "每日 · 约 10 分钟 · 加拿大音 · 545 条 · 服务器可达（手机直连当年不通）",
    )

    /** 国际视角（非欧美媒体），6 分钟周更 */
    val AJ_THE_TAKE = FeedSource(
        id = "aj-take",
        name = "Al Jazeera The Take",
        url = "https://www.omnycontent.com/d/playlist/9c074afa-3313-47e8-b802-a9f900789975/09af2160-238f-48b2-b20b-ad4b00ebd8e7/b86dddc1-67a5-41c2-a13c-ad4b00ebd8f5/podcast.rss",
        maxEpisodes = 1,
        note = "每周 · 约 6 分钟 · 国际视角 · 750 条 · 宿主 omnycontent",
    )

    val ALL = listOf(
        NPR_NEWS_NOW,
        NPR_UP_FIRST,
        NPR_CONSIDER_THIS,
        NPR_INDICATOR,
        NPR_SHORT_WAVE,
        NPR_POLITICS,
        NPR_LIFE_KIT,
        NPR_PLANET_MONEY,
        NPR_CODE_SWITCH,
        TED_TALKS_DAILY,
        LISTENING_TIME,
        // 4.3.0：非 NPR 的补充（英音 / 加拿大 / 商业短讯 / 国际视角 / 教学慢速）
        BBC_6MIN,
        BBC_GLOBAL_NEWS,
        WSJ_WHATS_NEWS,
        CBC_WORLD_REPORT,
        AJ_THE_TAKE,
    )

    /**
     * 单条超过这个时长就完全跳过（连音频都不下），避免误撞上超长特辑。
     *
     * 早期设的是 35 分钟，结果 TED 偶尔放出的 36–40 分钟单集会整条消失
     * （feed 里有、列表里没有，很容易被误判成「源挂了」）。
     * 现在放宽到 2 小时，只用来拦「明显不是单集」的异常值（比如整场直播回放）。
     */
    const val MAX_SINGLE_EPISODE_SEC = 2 * 60 * 60

    fun byId(id: String): FeedSource? = ALL.firstOrNull { it.id == id }
}
