package com.example.englishcommute.checkin

/**
 * 多设备打卡记录的合并规则 —— **纯函数，不碰存储、不碰网络**。
 *
 * ## 它守的那条不变式
 *
 * ```
 * 界面显示 = 本机(mine) + 其它设备(others)
 * 服务端总量 = Σ 所有设备（含本机）
 * ⇒ others = 总量 − mine           ← 反算，见 [othersFrom]
 * ```
 *
 * ★★ **这个文件存在的唯一理由是那一步减法。**
 * 多设备同步最贵的错法不是「同步失败」，而是**把服务端返回的总量直接当成
 * 「其它设备」存下来**：界面显示会变成 `mine + (本机 + 别的设备)`，
 * 也就是本机的时长被算了两遍。症状是「数字莫名其妙变大」——
 * 而它看起来像「同步终于生效了」，最容易被当成好事。
 *
 * 所以：规则抽在这里、配 [`SyncMergeTest`] 里那条
 * `merge(mine, othersFrom(total, mine)) == total` —— 客户端侧的合并
 * 与服务端的求和必须是同一个算术。
 *
 * ## 为什么不是「后写赢」
 *
 * 因为「今天听了多少」是个**只增的量**：每台设备只写自己那一列
 * （服务端的 `(用户, 天, 设备)` 主键保证了这一点），
 * 于是「相加」是唯一的正确答案，而且顺序无关、可重试、可离线。
 * 后写赢在这里一定会丢时长 —— 而丢时长比算多严重得多（见类注释末尾）。
 */
object SyncMerge {

    /**
     * 合并两份计数：同一天相加，只有一边有就是它。
     *
     * ⚠️ **不要**在这里做 `max`。看到「同步」两个字就想到 max 是很自然的
     *   （「同一个东西取最新」），但这两个 map 的语义**不同**：
     *   `mine` 是「本机听的总量」，`others` 是「别的设备听的总量」——
     *   它们是两笔独立的账，只能相加。
     */
    fun merge(mine: Map<Long, Int>, others: Map<Long, Int>): Map<Long, Int> {
        if (others.isEmpty()) return mine
        if (mine.isEmpty()) return others
        val out = HashMap<Long, Int>(mine.size + others.size)
        out.putAll(others)
        for ((day, sec) in mine) out[day] = sec + (others[day] ?: 0)
        return out
    }

    /**
     * 由「服务端总量」与「本机那一份」反算出「其它设备那一份」。
     *
     * `coerceAtLeast(0)` 是防御性的、且**必须留着**：
     * 用户在另一台设备上点了「清空记录」之后，服务端的总量会小于本机的 mine
     * （本机这边的清空还没发生）。那一刻「其它设备」应该是 0，
     * 而不是一个负数 —— 负数会让界面上的进度条归零、还可能出现负的剩余时间。
     */
    fun othersFrom(totals: Map<Long, Int>, mine: Map<Long, Int>): Map<Long, Int> {
        val out = HashMap<Long, Int>(totals.size)
        for ((day, total) in totals) {
            val other = total - (mine[day] ?: 0)
            if (other > 0) out[day] = other
        }
        return out
    }

    /** 一次累加的结果 */
    data class Accumulated(
        /** 本机那一列的新值（**只加上这次的真实增量**） */
        val mine: Int,
        /** 合并后的合计（界面显示的就是它） */
        val total: Int,
        /** 这次是否**刚刚跨过目标线**（庆祝与海报的唯一触发条件） */
        val justCompleted: Boolean,
    )

    /**
     * 把一段真实收听时长累加进**本机那一列**。
     *
     * ## ★★ 这个函数存在的唯一理由：一个 4.0.0 引入、两次发版都没发现的 bug
     *
     * 原来 `addSeconds` 里写的是：
     *
     * ```kotlin
     * val before = secondsOn(day)      // ← 这是**合并值**（mine + others）
     * val after  = before + seconds
     * persistSeconds(day, after)       // ← 而 persistSeconds 写的是 mine！
     * ```
     *
     * 于是每累加一次，**「别的设备那部分」就被并进 mine 一次** ——
     * 而计时器每 5 秒调一次。实测（用户录屏）：另一台设备有 25 分钟，
     * 本机放着音频，**8 秒内数字从 617 分钟涨到 667 分钟**，而且会一直涨。
     *
     * ★ 纪律：**「显示用的合并值」和「本机自己那一列」是两个不同的东西**，
     *   而它们类型都是 `Int`、在日常说法里都叫「今天多少秒」——
     *   混起来编译器一句话都不会说。所以两者在这里分开算，
     *   并且判据写在这个**纯函数**上（见 `SyncMergeTest`）。
     *
     * @param mineNow 本机那一列当前值（**不是**合并值）
     * @param others 其它设备的合计
     * @param delta 这次的墙钟增量（秒）
     * @param cap 单机单日封顶（防时钟跳变）
     * @param goal 那一天的目标（秒），用于跨线判据
     */
    fun accumulate(
        mineNow: Int,
        others: Int,
        delta: Int,
        cap: Int,
        goal: Int,
    ): Accumulated {
        val beforeTotal = mineNow + others
        val mine = (mineNow + delta).coerceAtMost(cap)
        val total = mine + others
        return Accumulated(
            mine = mine,
            total = total,
            // ⚠️ 跨线判据必须看**合计**（目标是冲着「今天一共听了多久」定的），
            //    但增长的只有**本机那一列** —— 这两件事分开算，正是这个函数的意义
            justCompleted = beforeTotal < goal && total >= goal,
        )
    }
}
