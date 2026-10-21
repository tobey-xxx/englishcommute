package com.example.englishcommute.checkin

/**
 * 每日目标的**时间轴** —— 纯函数，不碰存储、不碰界面。
 *
 * ## 为什么需要它（3.4.0 用户的第二次反馈）
 *
 * 3.3.0 把「每日目标」从一个常量做成了设置项。当时的取舍写在 [CheckInStore]
 * 的类注释里：达标与否**按当前目标现算**，于是「改目标会重算全部历史」。
 * 那段注释还为这个取舍辩护过 —— 「另一条路是每天记下当天的目标，那要为一个
 * 几乎是单调的量建一张按日的表」。
 *
 * 用户用了两天，反馈是：**要改成第二天才生效，不要动历史记录**。
 * 也就是说，我当初判断「不值得建那张表」的那个前提（用户不会在意历史被重算）
 * 是错的 —— 而这类判断的代价从来不在写代码那一刻，在用户真的用起来之后。
 *
 * ## 它到底解决什么
 *
 * 一个数字（40 分钟）和一个数字的**历史**（某天算达标时用的是多少）是两件事。
 * 混在一起就必然二选一：要么改标准就重算历史，要么历史永远改不了标准。
 * 拆开之后两个都能要：
 *
 * ```
 * changes = { 2026-09-20: 1800, 2026-10-02: 2400 }     ← 只记「哪天起改成什么」
 *
 * goalOn(2026-09-19) = 40 分钟（默认）
 * goalOn(2026-09-25) = 30 分钟
 * goalOn(2026-11-01) = 40 分钟
 * ```
 *
 * | 性质 | 由谁保证 |
 * |---|---|
 * | 改目标**只影响明天起** | [withChange] 只往 `today + 1` 写 |
 * | 已打过的卡**一个都不会变** | 判据永远是 `goalOn(那一天)`，而它只由更早的条目决定 |
 * | 连续天数不会因为改目标而掉 | 上一条的推论（连续天数 = 达标日的集合） |
 *
 * ## 两个刻意的设计
 *
 * ① **[SINCE_ALWAYS] 这个哨兵值**：3.3.0 只存了「当前目标」一个数，
 *    既没有日期、也就无从知道它从哪天开始生效。升级到这一版时必须把它当成
 *    **从最早就生效**，否则「昨天按 30 分钟算达标的」会在升级那一刻全变成未达标 ——
 *    那正是用户这次要求修的东西，不能让升级自己制造一次。
 *
 * ② **[normalize] 去掉冗余条目**：连点两下同一个值、或者改完又改回去，
 *    都会留下「值与生效前相同的日期」。它们不影响任何一次求值，但会让
 *    存储和日志里堆出一串没有意义的日期，下一次排查时读不出来。
 *    归一化是幂等的（`normalize(normalize(x)) == normalize(x)`）。
 *
 * ⚠️ 全部是纯函数，所以 `GoalTimelineTest` 能直接按天推演 ——
 *    「改目标不动历史」这条不变式就是在那儿逐条钉住的，不靠界面。
 */
object GoalTimeline {

    /** 一道「从最早生效」的哨兵：迁移老数据时用，正常写入永远不会产生它 */
    const val SINCE_ALWAYS: Long = Long.MIN_VALUE

    /** 默认目标：40 分钟。需求原文就是「每日收听累计时长达到 40 分钟」 */
    const val DEFAULT_MINUTES = 40

    /** 目标的可选范围。下限防「1 分钟就算打卡」，上限防「16 小时」（那等于没有目标） */
    const val MIN_MINUTES = 5
    const val MAX_MINUTES = 180

    /**
     * 给用户点的几档。
     *
     * 为什么是这几档：10 / 20 / 30 是「通勤单程到双程」的常见长度，
     * 40（默认）和 60 是「一天两趟」，90 是「刻意多听」。
     * 档位之间的间隔不追求等距 —— 一个等距的滑杆（5…180）会让大部分人
     * 落在 45、55 这种没有意义的数上，而「今天该听多久」是个粗判断。
     */
    val PRESETS: List<Int> = listOf(10, 20, 30, 40, 60, 90)

    private val DEFAULT_SECONDS get() = DEFAULT_MINUTES * 60

    /**
     * [day] 那一天生效的目标（秒）。
     *
     * 取**不大于 [day] 的最大条目** —— 也就是「最后一次改动里，那一天已经生效的那条」。
     * 一条都没有（或全在 [day] 之后）就是默认值。
     */
    fun secondsOn(changes: Map<Long, Int>, day: Long): Int {
        var best: Long? = null
        for (key in changes.keys) {
            val b = best
            if (key <= day && (b == null || key > b)) best = key
        }
        val raw = best?.let { changes.getValue(it) } ?: DEFAULT_SECONDS
        // 夹一下：老数据、或被手改过的 prefs 都不该让界面上的数字失去意义
        return raw.coerceIn(MIN_MINUTES * 60, MAX_MINUTES * 60)
    }

    /** 同 [secondsOn]，取分钟。界面与海报上都只用到分钟 */
    fun minutesOn(changes: Map<Long, Int>, day: Long): Int = secondsOn(changes, day) / 60

    /**
     * **明天起**的目标（分钟）。它与今天相同 ⇒ 没有待生效的改动，返回 `null`。
     *
     * 界面靠这个值回答「我刚才那一下改到了什么」—— 它必须区分
     * 「没有改动」和「改了，明天生效」，否则用户点完只能看到一句什么都没变的提示。
     */
    fun pendingMinutes(changes: Map<Long, Int>, today: Long): Int? {
        val tomorrow = secondsOn(changes, today + 1)
        return if (tomorrow == secondsOn(changes, today)) null else tomorrow / 60
    }

    /**
     * 写下「从 [today] 的第二天起改成 [minutes]」。**这是唯一的写入口。**
     *
     * ⚠️ 它刻意**不接受**「哪天生效」这个参数：一旦能指定日期，就有人会传 `today`
     *    （那正是这一版要修掉的旧行为）。生效日只能是 `today + 1`，
     *    把这条规则收在函数里，比散在调用点上靠自觉可靠。
     */
    fun withChange(changes: Map<Long, Int>, today: Long, minutes: Int): Map<Long, Int> {
        val sec = minutes.coerceIn(MIN_MINUTES, MAX_MINUTES) * 60
        return normalize(changes + ((today + 1) to sec))
    }

    /**
     * 去掉「值与生效前那一刻相同」的条目。见类注释 ②。
     *
     * 注意它**不看日期**，只看值 —— 因为冗余的定义就是「有它没它，求值结果一样」。
     */
    fun normalize(changes: Map<Long, Int>): Map<Long, Int> {
        val out = LinkedHashMap<Long, Int>()
        var effective = DEFAULT_SECONDS
        for (day in changes.keys.sorted()) {
            val v = changes.getValue(day)
            if (v != effective) out[day] = v
            effective = v
        }
        return out
    }
}
