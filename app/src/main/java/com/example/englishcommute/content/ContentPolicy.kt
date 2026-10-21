package com.example.englishcommute.content

/**
 * 内容生命周期策略：**下载窗口**与**保留期**。
 *
 * ## 为什么单独抽一个文件
 *
 * 这两条规则有一个共同点：**错了是静默的**。
 *
 * | 规则 | 错了会怎样 |
 * |---|---|
 * | 下载窗口 | 多下一堆陈内容，用户只觉得「更新很慢」 |
 * | 保留期 | **删掉用户还想听的东西**，或者一条都不删、缓存悄悄涨到几个 G |
 * | 每源保底 | 周更源永远没有内容，看起来像「这个源坏了」 |
 *
 * 没有任何一条会报错、会崩溃、会在界面上留下明确痕迹。所以它们必须是
 * **纯函数**（不碰文件、不碰 Flow、不读时钟），这样桌面端能用 Python
 * 1:1 复刻跑边界 —— 见 `tools/verify-retention.py`。
 *
 * ## 两条规则是一对，不能单独改
 *
 * ```
 * 下载窗口 2 天  <  保留期 3 天
 * ```
 *
 * 窗口必须**严格短于**保留期。相等时边界上会出现「刚下载完、同一轮就被保留期
 * 清掉」；窗口更长则更糟 —— 用户会看到更新列表里明明写「下载了 3 条」，
 * 下一轮那 3 条全都不见了。
 */
object ContentPolicy {

    /**
     * 天龄。**用整除而不是浮点**：判据是「满 3 天」，边界上不能有歧义 ——
     * 71 小时是 2 天（留），72 小时整是 3 天（删）。
     *
     * `then <= 0`（没有时间戳）返回 [Long.MAX_VALUE]：调用方一律会得到
     * 「远超期限」的结论，所以**不要在没时间戳的条目上直接用它做删除判据** ——
     * 先确认时间戳是否存在（[shouldExpire] 就是这么做的）。
     */
    fun ageDays(now: Long, then: Long): Long =
        if (then <= 0L) Long.MAX_VALUE else (now - then) / ContentRepository.DAY_MS

    /**
     * 该不该因为保留期到期而被清掉。
     *
     * `downloadedAt <= 0`（从没下载过）返回 false —— 只有真正落过盘的文件
     * 才有「保留期」这个概念。少了这一句，索引里所有「还没下载」的条目
     * 都会被判成过期。
     */
    fun shouldExpire(
        downloadedAt: Long,
        now: Long,
        retainDays: Long = ContentRepository.RETAIN_DAYS,
    ): Boolean = downloadedAt > 0L && ageDays(now, downloadedAt) >= retainDays

    /**
     * 距离自动删除还剩几天（含今天）。null = 这条没有音频，不给倒计时。
     *
     * 返回 0 表示**今天就会被清掉**。界面上要如实写「今天到期」，
     * 而不是「剩 0 天」—— 后者看起来像已经删了。
     */
    fun daysLeft(
        downloadedAt: Long,
        now: Long,
        retainDays: Long = ContentRepository.RETAIN_DAYS,
    ): Long? {
        if (downloadedAt <= 0L) return null
        return (retainDays - ageDays(now, downloadedAt)).coerceAtLeast(0L)
    }

    /**
     * 该不该因为「内容已过时效」而整个剔出索引。
     *
     * 只对**盘上没有音频**的条目成立：有音频的条目归保留期管
     * （它的删除条件是 `downloadedAt` 满 3 天，和内容发布多久无关）。
     * 两者分工不清的话，会出现「昨天刚下载的内容今天整个消失」——
     * 因为它的发布时间已经很旧了。
     */
    fun shouldPurge(publishedAt: Long, hasAudio: Boolean, now: Long): Boolean =
        !hasAudio && publishedAt > 0L && ageDays(now, publishedAt) >= ContentRepository.RETAIN_DAYS

    /** 预取选择的结果 */
    data class PrefetchSelection(
        /** 本轮该下载的 */
        val selected: List<Episode>,
        /** 这一轮不下的（只用于日志；用户点开时仍会按需下载） */
        val skipped: List<Episode>,
    )

    /**
     * 从候选里挑出本轮该下载的 —— **懒加载策略**（2.1.9）。
     *
     * @param candidates 需要下载的条目（无音频），**必须已按 `publishedAt` 倒序**
     * @param favoriteIds 收藏。用户明确说过要留的东西，永远优先
     * @param playingId 正在播的那条（换集时不能没有它）
     * @param doneIds 已听完的
     * @param prefetchCount 「接下来几条」
     *
     * ## 为什么换掉旧规则（`selectByWindow`，2.1.9 已删除）
     *
     * 旧规则是「**最近 2 天内发布的全部** + 每源保底」。问题不在规则本身，
     * 而在它的隐含前提：**下载是免费的**。而 2.1.9 起音频由服务器托管，
     * 每一次下载都从我们自己的流量包里出（实测每天新增素材约 **320 MB**，
     * 而真正会被听的只有几条）。于是「全量预下载」= 为不听的 90% 付费。
     *
     * ## 新规则（四条并集，顺序无关）
     *
     * | 类别 | 为什么留着 |
     * |---|---|
     * | **接下来 N 条**（按发布时间倒序，取前 N 条**未听完**的） | 这才是通勤真正会听的：打开就是最新的几条。听完一条，窗口自动往下滑一条 |
     * | **收藏** | 用户明确动作，永远最高优先级 |
     * | **正在播 / 正在下** | 现在就要用，删了就是「听一半断了」 |
     * | **每源保底最新一条** | ⚠️ **不能省**：周更源在窗口内一条都没有，不保底它就整块消失在列表里。详见下面 |
     *
     * ## ⚠️「每源保底」为什么不只是体验问题，而是**正确性**问题
     *
     * [ContentRepository.purgeStaleIndex] 会清掉「**没有音频** 且发布满 [ContentRepository.RETAIN_DAYS] 天」的条目。
     * 周更源（Code Switch / Listening Time）的最新一条往往已经发布 3–7 天 ——
     * 一旦不再预下载它，它就**没有音频 → 被索引清掉 → 这个源整个消失**，
     * 而用户侧的观感是「这个源坏了」。
     *
     * 保底集合只有 ≤ 11 条（每源一条），而且它**跨天是稳定的**：
     * 源不发新内容就不会变，不会反复下载。所以这一条的净成本接近零。
     *
     * ## 不会造成「点了也得下」的糟糕体验
     *
     * 没被预取到的条目仍然可以点 —— `LibraryViewModel` 会走
     * [SyncEngine.requestDownload] 的插队路径（2.0.3），只是要等一次下载。
     * 「懒加载」省的正是**没被点的那些**。
     */
    fun selectPrefetch(
        candidates: List<Episode>,
        now: Long,
        favoriteIds: Set<String> = emptySet(),
        playingId: String? = null,
        doneIds: Set<String> = emptySet(),
        prefetchCount: Int = ContentRepository.PREFETCH_COUNT,
    ): PrefetchSelection {
        if (candidates.isEmpty()) return PrefetchSelection(emptyList(), emptyList())

        val picked = LinkedHashSet<Episode>()

        // ① 接下来 N 条：按发布时间倒序（candidates 已倒序），跳过已听完的
        var taken = 0
        for (ep in candidates) {
            if (taken >= prefetchCount) break
            if (ep.id in doneIds) continue
            picked += ep
            taken++
        }

        // ② 收藏 / ③ 正在用
        for (ep in candidates) {
            if (ep.id in favoriteIds || ep.id == playingId) picked += ep
        }

        // ④ 每源保底最新一条（候选已倒序 → 每个源第一次出现的就是它最新的）
        val seen = HashSet<String>()
        for (ep in candidates) {
            if (!seen.add(ep.sourceId)) continue
            picked += ep
        }

        val selected = picked.toList()
        val selectedIds = selected.mapTo(HashSet()) { it.id }
        return PrefetchSelection(selected, candidates.filter { it.id !in selectedIds })
    }

    /**
     * 保留期裁剪的目标选择：**纯函数版**，真正删文件的那一步在
     * [ContentRepository.expireOldAudio]。
     *
     * [keepIds] 由调用方给出，至少包含收藏、正在播的那条、以及
     * 状态为进行中的条目（见 expireOldAudio 的注释）。
     */
    fun selectExpired(
        episodes: List<Episode>,
        now: Long,
        hasAudio: (Episode) -> Boolean,
        keepIds: Set<String>,
    ): List<Episode> = episodes.filter { ep ->
        ep.id !in keepIds && !ep.status.isBusy && hasAudio(ep) && shouldExpire(ep.downloadedAt, now)
    }
}
