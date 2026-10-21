package com.example.englishcommute.sync

/**
 * 「给这一条弄到字幕」的结果。
 *
 * ## 为什么只有这四种（2.1.0）
 *
 * 1.0 的字幕来源是**本机识别**，2.0 改成**服务器**，2.1.0 把端侧那条兜底拆掉了。
 * 于是「拿不到字幕」的原因从三类（服务器没处理 / 服务器连不上 / 本机没装模型）
 * 收敛成两类（服务器没处理 / 服务器连不上）——**这正是拆掉兜底的收益之一**：
 * 少了「本机没装模型」这个分支，界面就不用再回答「要不要去下 630 MB」这个问题。
 *
 * ⚠️ 分支不能省。2.0.1 曾经把两种原因合成一句话说，结果「刚发布的整点新闻」
 * 被说成「去装离线识别」——而 NPR News Now 是**每小时一集**，服务器按定时任务跑，
 * 这种「暂时没轮到」的情况**天天都有**，用户每次都被指去做一件没用的事。
 *
 * 判据是 [com.example.englishcommute.net.ServerCatalog.lastCatalogOk]，
 * 不是「这条在不在目录里」——见 [ServerDown] 的说明。
 */
sealed interface SubtitleOutcome {

    /**
     * 拿到了。[withZh] 表示这一条**同时也拿到了中文对照**。
     *
     * [withZh] = false 有两种情况，对用户是同一件事（英文有了、中文还没有）：
     *  - 服务器那轮的翻译还没跑（它每 15 分钟一轮，srt 先于 zh 发布）
     *  - 译文句数与字幕对不上，客户端宁可整份丢弃（错位的译文比没有译文更糟）
     * 两种情况下一轮都会再试一次 —— 见 `SyncEngine.fetchSubtitlesIfNeeded`
     * 里「有 srt 没 zh 也进队列」那条判据。
     */
    /**
     * @param zhOnly 这一轮**只补了中文**，英文早就在盘上。
     *   日志与计数要区分这两种，否则通知里会把「补了一句译文」说成「取到字幕」——
     *   而用户读到那句话会以为整条字幕是新来的。
     */
    data class Got(
        val segmentCount: Int,
        val withZh: Boolean,
        val zhOnly: Boolean = false,
    ) : SubtitleOutcome

    /**
     * 服务器目录可用，只是**这一期还没轮到**（刚发布、服务器那轮定时任务还没跑）。
     *
     * **这是正常流程，不是故障。** 用户什么都不用做，下一轮自愈。
     * 所以它不该占用红色错误位（卡片上是中性色的「字幕待补」胶囊）。
     */
    data object NotYet : SubtitleOutcome

    /**
     * 服务器不可达（没网、超时、DNS、HTTP 5xx、目录解析失败）。
     *
     * ⚠️ 与 [NotYet] 必须分开：两者的**下一步完全相反**。
     * 这一种是用户需要知道、且暂时无解的（等服务器恢复），
     * 所以它是唯一会写 [com.example.englishcommute.content.Episode.error] 的分支。
     *
     * 判据为什么是 `lastCatalogOk` 而不是「这条在不在目录里」：
     * 后者在**字幕文件下载失败**时也会是 false，而那种情况下一轮同样会自愈，
     * 说「稍后」也是对的 —— 说「服务器连不上」反而是错的。
     */
    data object ServerDown : SubtitleOutcome

    /**
     * 目录拿到了、也真的去取了，但这一条失败了（404 / 空响应 / 解析不出句段）。
     *
     * 与 [NotYet] 的区别是「服务器说它有，实际没拿到」—— 属于服务端状态不一致，
     * 下一轮会重试，但值得单独记一行日志，因为它和「还没轮到」的排查方向完全不同。
     */
    data class Failed(val reason: String) : SubtitleOutcome
}
