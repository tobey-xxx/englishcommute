package com.example.englishcommute.ui

/**
 * 列表滚动位置的**锚点**。
 *
 * ## 它回答的唯一问题：筛选签那一栏，此刻贴在哪儿
 *
 * 真机反馈的原话是「一会儿高一会低好烦的」。所以判据收窄成一条：
 * 切档时**筛选签栏在屏幕上的像素位置必须不变**。
 *
 * ## ⚠️★ 而那一栏有**三种**位置，不是两种（2.1.6 才把这件想清楚）
 *
 * | 状态 | 首项是谁 | 长什么样 |
 * |---|---|---|
 * | [LABEL_BELOW] | 刊头 / 状态 | 刊头可见，筛选签跟着列表走（在屏幕中部偏下） |
 * | [LABEL_AT_ORIGIN] | **筛选签自己** | 筛选签在屏幕顶上 —— 但它其实**比吸顶位低一档**：低一整个列表顶部内边距（`Space.section` = 30dp） |
 * | [LABEL_PINNED] | 已经滚过的某条内容 | 筛选签**吸**在屏幕最上面 |
 *
 * 「贴顶」被当成**一件事**、落点一律写 `(headerItems - 1, 0)`，恰好落在中间那一格上。
 * 于是从一个「吸顶」的档切到一个「贴顶位」的档时，整条筛选签栏会**往下沉一档**
 * （30dp）—— 用户 2.1.4 / 2.1.5 的录屏里一直是这个。
 *
 * 为什么 `(headerItems - 1, 0)` 会低一档：`contentPadding.top = Space.section` 让
 * 「下标 + 偏移」的原点落在**内容区起点**，而吸顶线在它**上面**一整个内边距的位置
 * （`LazyList` 的 sticky header 贴的是列表顶部，不受顶部内边距影响）。
 * 「下标 = headerItems、偏移 = 0」（= 第一条内容顶在原点）才会把筛选签**推**进吸顶位。
 * 真机日志印证过：同一个档，落 `顶部 4/43`（下标 3）时筛选签是 `偏 -98px`（已吸顶），
 * 落 `顶部 3/5`（下标 2）时是 `偏 0px`（贴顶位，低一档）。
 *
 * ## 规则：落点由「**切走时那一档**的位置」决定，而不是「目标档自己上次的位置」
 *
 * 这是 2.0.4 那版真正的毛病，也是「一会儿高一会低」的来源。那一版每档各记一份锚，
 * 切档时**优先用目标档自己的**。于是：
 *
 * ```
 * 「全部」停在顶部（刊头可见）  →  切到「收藏」
 * 「收藏」上次滚到第 5 条（吸顶）→  落点 = 第 5 条  →  筛选签「啪」地吸到顶上
 * ```
 *
 * 用户什么都没做，只是换了个筛选签，那一栏就窜上去了 —— 切回来又掉下来。
 * **只要「用目标档自己的锚」，这个跳就一定会发生**：两档的历史位置本来就不同。
 *
 * 所以现在的规则是：**看现在在哪儿，就落到哪儿**。
 *
 * | 现在 | 新档 |
 * |---|---|
 * | 首项还在**头部区**（刊头 / 状态 / 筛选签自己） | **原样搬那一对（下标, 偏移）** —— 三档的头部 item 完全相同，所以这一搬是**像素级**精确的 |
 * | 筛选签**已经吸顶**（首项滚进内容区） | 目标档若有「上次看到的那一条」就落到它身上（那条必然在内容区 → 仍然吸顶）；否则落到**第一条内容**上 |
 *
 * ## 不跳的全部理由，就是这一条不变式
 *
 * ```
 * 落点 = 要么「头部区的原样搬运」（像素级精确）
 *        要么「首项下标 >= headerItems」（筛选签必然被推进吸顶位）
 * ```
 *
 * **绝不允许落在 `headerItems - 1`（筛选签自己）那一格上** —— 它是「贴顶位」，
 * 与「吸顶」差一档，两种视觉状态它都不是（见 [resolveSpot]）。
 *
 * ## 为什么内容锚还要留着
 *
 * 「吸顶 → 吸顶」这一格里，如果新档只能从第一条开始看，那「切走再切回来」就丢位置。
 * 留着 `episodeId` 就能两者兼得：**筛选签的位置由「现在」决定，看哪一条由「目标档自己的历史」决定**。
 * 这正是「记住浏览位置」和「不跳」唯一能共存的写法。
 */

/**
 * 「筛选签贴在哪儿」的三种说法，**全工程只在这里定义一次**。
 *
 * 真机上的验收办法是拿两行日志逐字比对（`切筛选 → … · 从 X` 与
 * `筛选落点 · … · X`）。两处各写一份字面量的话，改一处漏一处 ——
 * 而**两行对不上会被读成「又跳了」**，成了一个自己造出来的假故障。
 *
 * ⚠️ 2.1.6 从两种变三种：[LABEL_AT_ORIGIN] 那一格以前被并进 [LABEL_PINNED]，
 *    于是「吸顶」与「贴顶位」在日志里长得一模一样 —— 而它们在屏幕上差 30dp。
 *    **两件事共用一个名字，就不可能靠日志发现差异。**
 */
private const val LABEL_PINNED = "筛选签吸在顶"
private const val LABEL_AT_ORIGIN = "筛选签贴顶位(未吸顶)"
private const val LABEL_BELOW = "筛选签在刊头下"

data class ListAnchor(
    /**
     * 停在头部区时，头部内的下标（0 = 刊头）。
     *
     * 只在 [episodeId] 为 null 且有 [atOrigin] / 无 [noContentId] 时有意义。
     * 三档筛选的头部是同一批 item（刊头 / 状态 / 筛选签），所以这个下标**跨档通用**。
     */
    val headerIndex: Int = 0,
    /** 头部区内的像素偏移。意义同上 */
    val headerOffsetPx: Int = 0,
    /** 内容锚点：切走时屏幕上第一条可见素材的 id。null = 当时停在头部区（筛选签还在刊头下面） */
    val episodeId: String? = null,
    /** 那条素材偏出视口顶部多少像素（0 = 它的顶边正好在视口顶） */
    val offsetPx: Int = 0,
    /**
     * 首项**正好是筛选签自己**（`index == headerItems - 1`）—— 也就是「贴在内容区起点」。
     *
     * ## ⚠️★ 它**不算吸顶**（2.1.6 改的就是这一条）
     *
     * 它比吸顶位**低一整个列表顶部内边距**（`Space.section` = 30dp）。真机录屏里
     * 量得很清楚：吸顶的档筛选签在 y=86，这一格的档在 y=116，差 30px。
     *
     * 2.1.4 曾经把它算成「吸顶」（[pinned]），理由是「它的首项下标已经进了贴顶区」——
     * 那是**拿下标当坐标**：下标只说明「首项是谁」，不说明「它顶在屏幕的哪一条线上」。
     * 后果正是本轮要修的那个「往下沉一档」。
     *
     * 现在它走「头部区原样搬运」那一支（[resolveSpot] 的第 ① 条），
     * 把 `(headerItems - 1, 0)` 原样搬到新档 → 画面一个像素都不动。
     *
     * ## 为什么不能靠 `headerIndex == headerItems - 1` 判它
     *
     * 这个 data class 不知道 `headerItems`；而名字要说人话。所以存一个布尔量。
     * 它**只影响日志措辞**，不参与 [pinned] —— 那是刻意的。
     */
    val atOrigin: Boolean = false,
    /**
     * 首项**已经滚进内容区**（所以筛选签是真吸顶），但这一档**取不到条目 id**。
     *
     * 只有一种情形：这一档一条内容都没有（空档）。空状态块自己撑满一屏，所以列表
     * 照样滚得动、照样能吸顶，但 `ids` 是空的 —— 没有内容锚可指。
     *
     * ⚠️ 它**算吸顶**（2.1.5 修的那一半）：拿不到内容锚 ≠ 没吸顶。旧代码在这一格
     * 返回 [LibraryAnchor.TOP]（= 「在刊头下」），而这个错误判断会**传播**到下一次切档
     * → 整页掉回顶部。用户在录屏里看到的正是「在收藏里滚到吸顶、切回全部，页面往下沉」。
     */
    val noContentId: Boolean = false,
) {
    /**
     * 筛选签此刻**吸在屏幕最上面** —— 只有这一种形态算（见 [atOrigin]）。
     * 它决定切档时走哪一支：吸顶 → 新档也必须吸顶。
     */
    val pinned: Boolean get() = episodeId != null || noContentId

    /**
     * 日志用的人话形式。
     *
     * 刻意**只报这一件事**：`切筛选 → … · 从 X` 与 `筛选落点 · … · X`
     * 两行相同，就是「那一栏没动」。
     * 报下标的话用户得先在脑子里换算成「那到底是高还是低」，等于没报。
     */
    fun label(): String = when {
        pinned -> LABEL_PINNED
        atOrigin -> LABEL_AT_ORIGIN
        else -> LABEL_BELOW
    }
}

/** 可以直接喂给 `LazyListState(构造参数)` 的落点 */
data class ListSpot(val index: Int, val offsetPx: Int)

object LibraryAnchor {

    /** 顶部（刊头可见）。没访问过任何一档、也没有可继承的锚点时用它 */
    val TOP = ListAnchor()

    /**
     * 把一帧的滚动状态记成锚点。
     *
     * @param index `LazyListState.firstVisibleItemIndex`（**不受吸顶干扰**的那个下标）
     * @param offsetPx `LazyListState.firstVisibleItemScrollOffset`
     * @param ids 当前这一档的条目 id（**显示顺序**，即交错排序之后）
     * @param headerItems 列表头部有几个非条目 item（刊头 / 状态 / 筛选签）
     */
    fun capture(index: Int, offsetPx: Int, ids: List<String>, headerItems: Int): ListAnchor {
        val safeOffset = offsetPx.coerceAtLeast(0)
        // ⚠️★ 判据是 `index < headerItems`，**含** `index == headerItems - 1`（筛选签自己）。
        //
        //    为什么「筛选签自己」要归到头部区、而不是归到「吸顶」：
        //    `(下标, 偏移)` 这一对**只在头部 item 上可以跨档搬运** —— 三档的头部
        //    item 完全相同，所以搬过去是**像素级**精确的。而它在屏幕上确实比吸顶位
        //    低一档（`Space.section`），归进「吸顶」就会让落点差 30dp（见 [atOrigin]）。
        if (index < headerItems) {
            return ListAnchor(
                headerIndex = index.coerceAtLeast(0),
                headerOffsetPx = safeOffset,
                atOrigin = index == headerItems - 1,
            )
        }
        // 首项已经滚进内容区 —— 筛选签必然吸顶。
        // 能取到条目就记内容锚（下次切回来还能回到那一条）；
        // 取不到（空档）就只记「吸顶但没有内容锚」。
        val id = ids.getOrNull(index - headerItems)
        if (id != null) {
            return ListAnchor(episodeId = id, offsetPx = safeOffset)
        }
        return ListAnchor(noContentId = true)
    }

    /**
     * 把锚点解析成这一档里的落点。
     *
     * @param ids 目标档的条目 id（显示顺序）
     * @param target **目标档自己**上次的位置。只在「现在吸顶」时用得上
     * @param current **切走那一刻**当前档的位置。它决定新档是「吸顶」还是「原样搬头部」
     */
    fun resolve(
        ids: List<String>,
        target: ListAnchor?,
        current: ListAnchor?,
        headerItems: Int,
    ): ListSpot {
        val cur = current ?: TOP
        // ① 现在停在**头部区**（筛选签还没吸顶）→ 新档原样搬那一对（下标, 偏移）。
        //    三档头部 item 完全相同，所以这是像素级精确的搬运 —— 顺带也解决了
        //    「首次进入某一档」：它天然就是「现在在哪儿就停在哪儿」。
        //    ⚠️ 这一支也管「在贴顶位（未吸顶）」那一格：搬过去两个档就都在那一格，
        //       依然一个像素都不动（那是这一格里唯一像素级精确的答案）。
        if (!cur.pinned) {
            return ListSpot(
                index = cur.headerIndex.coerceIn(0, headerItems - 1),
                offsetPx = cur.headerOffsetPx.coerceAtLeast(0),
            )
        }
        // ② 现在吸顶 → 新档也必须吸顶。
        //    ⚠️ 这一条是「不跳」的另一半：少了它，切到一个内容很少的档会落回头部区，
        //       筛选签就当着用户的面掉回刊头下面。
        val t = target
        // ⚠️ 判据是 `episodeId != null`，而**不是** `t.pinned`：
        //    pinned 还包含 [ListAnchor.noContentId] 那种「吸顶但没有内容锚」的锚，
        //    拿 null 去 indexOf 会去查一个不存在的 id，白走一趟。没有内容锚时
        //    直接落到第 ③ 条的确定值，结果**一样**，但意图清楚得多。
        if (t != null && t.episodeId != null) {
            val i = ids.indexOf(t.episodeId)
            if (i >= 0) return ListSpot(headerItems + i, t.offsetPx)
        }
        // ③ 确定值：**第一条内容**，不是筛选签自己。
        return resolveSpot(headerItems)
    }

    /**
     * 「筛选签吸顶」的确定落点 = **第一条内容**（`headerItems`），偏移 0。
     *
     * ## ⚠️★ 为什么不是 `headerItems - 1`（2.1.6 改的那一行）
     *
     * 写 `headerItems - 1`（筛选签自己）时，落点落在「内容区起点」上 —— 而吸顶线
     * 比它**高一整个列表顶部内边距**（`Space.section` = 30dp，因为 sticky header
     * 贴的是列表顶部，不受顶部内边距影响）。真机上的表现就是：
     *
     * ```
     * 「全部」滚了几屏（吸顶，筛选签 y=86）→ 点「收藏」
     * 落点 (2, 0) → 筛选签 y=116          ← 整条栏往下沉 30dp
     * ```
     *
     * 落到**第一条内容**上则相反：那条内容顶在原点，筛选签就被**推**到原点上方 →
     * 吸顶位（y=86），与「全部」那一档**逐像素相同**。
     *
     * 这一格在真机日志里有过直接证据（同一个档、两次落点）：
     *
     * ```
     * 顶部 4/43 条 · 筛选签 偏 -98px   ← 下标 3（内容），已吸顶
     * 顶部  3/5 条 · 筛选签 偏   0px   ← 下标 2（筛选签自己），低一档
     * ```
     *
     * ## 为什么它是**确定值**而不是交给 `LazyColumn` 去钳
     *
     * 每次筛选都有 item 兜底：非空档是第一条素材，空档是那个撑满一屏的空状态块
     * （`fillParentMaxHeight(1f)`）。所以这个下标永远存在。
     * 交给 `LazyColumn` 钳一个越界下标，会钳到列表末尾 —— 那才是「跳到最底下」。
     *
     * 反过来说：**列表短到滚不动也没关系**。那时钳到的是最大滚动量，
     * 而最大滚动量的位置上筛选签本来就已经吸顶了（它早就滚到上面去了）。
     */
    private fun resolveSpot(headerItems: Int): ListSpot = ListSpot(headerItems, 0)

    /**
     * 把一个滚动位置说成人话（用于「筛选落点」那行日志）。
     *
     * ⚠️ 判据必须与 [capture] **逐字一致**（三分支，且 `headerItems - 1` 归「贴顶位」）——
     *    两处各写一份的话，日志会开始报一个与落点规则无关的结论，而那时它比不报还糟。
     *
     *    ⚠️★ 这里原来写的是 `index < headerItems`，比 [capture] **多吞了一个下标**：
     *    首项正好是筛选签（`headerItems - 1`）时，落点是对的、日志却说「在刊头下」。
     *    真机上表现为「切时：贴顶 / 落点：在刊头下」这对自相矛盾的行，
     *    把排查一路带偏到落点算法上去。**判据分叉的代价就是这么具体。**
     */
    fun labelOf(index: Int, headerItems: Int): String = when {
        index >= headerItems -> LABEL_PINNED
        index == headerItems - 1 -> LABEL_AT_ORIGIN
        else -> LABEL_BELOW
    }
}
