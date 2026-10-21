package com.example.englishcommute.checkin

/**
 * 连续打卡的计算。
 *
 * 单独一个文件、纯整数集合运算，是因为**这里的错误是错得最没有痕迹的一类**：
 * 数字算错一格，界面上只表现为「连续 4 天」而不是「连续 5 天」——
 * 没有报错、没有异常、用户只会觉得「我明明打了卡」。
 * 所以它必须能被桌面脚本穷举验证（见 `tools/verify-checkin.py`）。
 */
object Streak {

    /**
     * 当前连续天数。
     *
     * ## 「今天还没达标」不算断
     *
     * 这是有意的，也是多邻国的行为：早上打开 App 时昨天连了 7 天，
     * 今天还一分钟没听 —— 这时应当显示 **7 天**（今天还有机会），
     * 显示 0 会让用户以为记录被清空了。
     *
     * 于是规则是：
     * ```
     * 今天已达标        → 从今天往前数
     * 今天未达标、昨天达标 → 从昨天往前数（不断裂）
     * 昨天也未达标      → 0
     * ```
     *
     * 换句话说：**连续段必须含「今天或昨天」才算数**，这样「上周连了 5 天、
     * 这周一直没听」不会永远显示 5。
     *
     * @param doneDays 已达标的日（epochDay 集合）
     */
    fun current(doneDays: Set<Long>, today: Long): Int {
        var cursor = if (today in doneDays) today else today - 1
        var n = 0
        while (cursor in doneDays) {
            n++
            cursor--
        }
        return n
    }

    /**
     * 历史最长连续天数。
     *
     * 与 [current] 的区别：它看的是整个历史上最长的连通段，
     * 断了也不会归零 —— 这是「别让用户觉得白干了」的那一半激励。
     *
     * 实现用「排序后找连续段」而不是「从每个点各自往回数」：后者是
     * O(n²)，而这里是 O(n log n)（排序）。打卡数据最多几年、几千条，
     * 两者都够快，但前者的边界更好写 —— 不需要处理重复日期。
     */
    fun best(doneDays: Set<Long>): Int {
        if (doneDays.isEmpty()) return 0
        val sorted = doneDays.sorted()
        var best = 0
        var run = 0
        var prev: Long? = null
        for (day in sorted) {
            run = if (prev != null && day == prev + 1L) run + 1 else 1
            if (run > best) best = run
            prev = day
        }
        return best
    }
}
