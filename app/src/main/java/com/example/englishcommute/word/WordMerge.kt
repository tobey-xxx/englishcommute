package com.example.englishcommute.word

/**
 * 生词本的合并规则 —— **纯函数，不碰存储、不碰网络**。
 *
 * ## 为什么生词本不能用打卡那套「求和」
 *
 * 打卡是**只增的计数**（`今天 = Σ 各设备`），而一张卡片是**可变状态**：
 * 复习会改 FSRS 的到期时间/稳定度，删除会让它消失，再添加又是另一回事。
 * 对可变状态用「后写赢」就会出现经典的两难：
 *
 * | 做法 | 后果 |
 * | --- | --- |
 * | 谁的 `updatedAt` 晚听谁的 | 离线期间另一台的复习**整个丢掉**（状态和日志一起） |
 * | 按 `reps` 多的赢 | 「今天在 A 复习、昨天在 B 复习」两边都有用，硬要挑一个必须丢一份 |
 *
 * ## 正解：**复习事件是只增的，状态由事件重放算出来**
 *
 * ```
 * 一张卡片 = 不可变字段(lemma/surface/addedAt) + 语境集合 + **复习事件集合**
 * 合并     = 集合并集（复习按 (设备, 时刻) 去重；语境按所属那一行去重）
 * 状态     = 把并集按 (时刻, 设备) 排好后**重放**一遍 FSRS
 * ```
 *
 * 三条性质，全都来自「集合并集 + 纯函数重放」：
 *
 * 1. **不丢**：两台设备各自离线复习同一天，两条事件都在 —— 而「谁赢」这种问题
 *    根本不会出现（打卡那一版用 `MAX` 求和，这里是并集，同一个思路换了对象）；
 * 2. **确定**：并集与顺序无关，重放又只依赖事件本身 ⇒ 任何两台设备合并后
 *    **逐字节一致**（这正是 4.1.0 把 FSRS 的抖动改成确定性派生的原因）；
 * 3. **可重放**：状态不再是「权威」，只是事件的投影 —— 于是日志和状态
 *    永远不会各说各话（本工程最怕的两个真相源）。
 *
 * ⚠️ 代价：**状态要靠重放算**。一张复习过 50 次的卡，每次合并都要重算 50 步
 *    （每步是几十次浮点运算）。个人词本的量级（几百张卡）完全不是问题，
 *    而换来的是「永远不用做冲突处理」。
 *
 * ## 删除：两个只增的时间戳
 *
 * ```
 * 存在 ⟺ addedAt > deletedAt        （两个都取合并各方里的 MAX）
 * ```
 * 用 MAX 而不是「谁晚听谁的」，是为了让**删除后再添加**也自洽：
 * 重新添加会给 `addedAt` 盖一个新时间戳（晚于旧墓碑）⇒ 卡片又出现，
 * 而它的复习记录**还在**（墓碑只影响「是否显示」，不删事件）。
 */
object WordMerge {

    /** 重放的结果：派生出的三样东西 */
    data class Replayed(val fsrs: FsrsCard, val reps: Int, val lapses: Int)

    /** 复习事件的身份：**谁在这台设备上、什么时候**。时间戳相同但设备不同 = 两条 */
    fun reviewKey(r: ReviewLog): String = "${r.device}\u0000${r.at}"

    /**
     * 两个复习日志取并集。
     *
     * 按 [reviewKey] 去重（**幂等**：同一份日志并两次不会变长），
     * 再按 `(时刻, 设备)` 排序 —— 这是重放的**唯一**依据，也是两台设备
     * 能得到相同结果的前提（顺序必须只由事件本身决定，不能由到达顺序决定）。
     */
    fun unionReviews(a: List<ReviewLog>, b: List<ReviewLog>): List<ReviewLog> {
        if (a.isEmpty()) return sorted(b)
        if (b.isEmpty()) return sorted(a)
        val seen = HashSet<String>(a.size + b.size)
        val out = ArrayList<ReviewLog>(a.size + b.size)
        for (r in a) if (seen.add(reviewKey(r))) out.add(r)
        for (r in b) if (seen.add(reviewKey(r))) out.add(r)
        return out.sortedWith(compareBy({ it.at }, { it.device }))
    }

    private fun sorted(xs: List<ReviewLog>): List<ReviewLog> =
        if (xs.size < 2) xs else xs.sortedWith(compareBy({ it.at }, { it.device }))

    /**
     * 从「加入时刻 + 复习日志」重放出一张卡的 FSRS 状态。
     *
     * ⚠️ 初始状态必须与 [WordStore.add] 造新卡时**完全一致**
     *   （`FsrsCard(dueAtMillis = addedAt)`，phase=Learning、stability/difficulty 为空）。
     *   差一个字都会让「重放结果 ≠ 增量结果」，而那种偏差只在有第二台设备时才显形。
     *
     * ⚠️ lapse 的判据与增量路径同一条：`rating == AGAIN && 之前已复习过`。
     *   新卡第一次点「忘记」不算 lapse（那是还没记住，不是忘了）。
     */
    fun replay(
        addedAt: Long,
        reviews: List<ReviewLog>,
        scheduler: FsrsScheduler = FsrsScheduler(),
    ): Replayed {
        var fsrs = FsrsCard(dueAtMillis = addedAt)
        var reps = 0
        var lapses = 0
        for (r in sorted(reviews)) {
            val isLapse = r.rating == FsrsScheduler.RATING_AGAIN && reps > 0
            fsrs = scheduler.review(fsrs, r.rating, r.at)
            reps += 1
            if (isLapse) lapses += 1
        }
        return Replayed(fsrs, reps, lapses)
    }

    /**
     * 语境取并集：**本机已有的顺序保留在前面**，服务端独有的追加在后面。
     *
     * ⚠️ 这里**刻意不做全序归一化**（比如统一按 episodeId 排序）：
     *    卡片上只显示 `contexts.firstOrNull()`，而把顺序重排会让用户
     *    **已经看惯了的那句例句换掉** —— 那是一次没必要的界面变化。
     *
     *    代价：两台设备的例句**可能不同**（各自先看到自己那句）。
     *    这是有意的取舍，而且只影响「用哪一句当例句」——
     *    语境集合本身（决定卡片内容完整性）是两台一致的，计数与复习结果都不受影响。
     */
    fun mergeContexts(
        local: List<WordContext>,
        remote: List<WordContext>,
        max: Int = 3,
    ): List<WordContext> {
        if (remote.isEmpty()) return local
        val seen = local.mapTo(HashSet(local.size)) { it.episodeId + "\u0000" + it.lineStartMs }
        val out = ArrayList<WordContext>(local.size + remote.size)
        out.addAll(local)
        for (c in remote) {
            if (seen.add(c.episodeId + "\u0000" + c.lineStartMs)) out.add(c)
        }
        return if (out.size <= max) out else out.take(max)
    }

    /** `surface` 取「加入时间更晚的那一份」；时间相同则取字典序小的（保证可交换） */
    fun pickSurface(
        localSurface: String,
        localAddedAt: Long,
        remoteSurface: String,
        remoteAddedAt: Long,
    ): String = when {
        remoteAddedAt > localAddedAt -> remoteSurface
        remoteAddedAt < localAddedAt -> localSurface
        remoteSurface < localSurface -> remoteSurface
        else -> localSurface
    }

    /** 墓碑同样取 MAX（只增）：这样「删除」在两端汇合后一定是删掉的 */
    fun mergeTombstones(local: Map<String, Long>, remote: Map<String, Long>): Map<String, Long> {
        if (remote.isEmpty()) return local
        if (local.isEmpty()) return remote
        val out = HashMap<String, Long>(local)
        for ((lemma, at) in remote) {
            val cur = out[lemma]
            if (cur == null || at > cur) out[lemma] = at
        }
        return out
    }

    /** 存在的判据。见类注释里「删除：两个只增的时间戳」 */
    fun isAlive(addedAt: Long, deletedAt: Long?): Boolean = deletedAt == null || addedAt > deletedAt
}
