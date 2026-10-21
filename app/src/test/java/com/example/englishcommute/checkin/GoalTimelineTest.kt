package com.example.englishcommute.checkin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 每日目标时间轴（[GoalTimeline]）。
 *
 * ## 为什么这件事必须逐天验一遍
 *
 * 3.3.0 的语义是「达标按**当前**目标现算」，于是改目标会重算全部历史。
 * 用户反馈「要第二天生效，不要动历史」之后，改法是把「目标」从一个数
 * 变成一条**按天的时间轴**。这个改动最危险的地方不在新代码能不能跑，
 * 而在**它有没有在某个角落偷偷改到过去**：
 *
 *   · 表现是「我昨天明明打上卡了，今天变成没打」—— 用户会觉得记录丢了
 *   · 而它不会报错、不会崩，单看任何一行代码都完全正确
 *
 * 所以核心那一项（[改目标不动历史]）是**逐天扫一遍**的，
 * 不是抽查几个点 —— 抽查恰好会漏掉「只有某一天不对」这种情况。
 *
 * ## 与 `tools/verify-checkin-goal.py` 的分工
 *
 * 这里钉的是**算法**（纯函数，可以随便构造极端输入）。
 * 那边钉的是**接线**（数据层有没有真的按天取、界面有没有用当前设置当判据、
 * 海报取的是不是那一天）。两边都要有：算法对了但接线错了，用户看到的还是错的。
 */
class GoalTimelineTest {

    private val min = 60          // 一分钟
    private val default = 40 * min
    private val today = 20_000L   // 随便一个 epochDay

    // ------------------------------------------------------------------
    // 基本求值
    // ------------------------------------------------------------------

    @Test
    fun `没有改动时，任何一天都是默认目标`() {
        assertEquals(default, GoalTimeline.secondsOn(emptyMap(), today))
        assertEquals(default, GoalTimeline.secondsOn(emptyMap(), today - 9999))
        assertEquals(default, GoalTimeline.secondsOn(emptyMap(), today + 9999))
    }

    @Test
    fun `取的是「不大于那天的最大条目」`() {
        val changes = mapOf(100L to 30 * min, 200L to 60 * min)
        assertEquals(40 * min, GoalTimeline.secondsOn(changes, 99))
        assertEquals(30 * min, GoalTimeline.secondsOn(changes, 100))   // 当天生效
        assertEquals(30 * min, GoalTimeline.secondsOn(changes, 199))
        assertEquals(60 * min, GoalTimeline.secondsOn(changes, 200))
        assertEquals(60 * min, GoalTimeline.secondsOn(changes, 99_999))
    }

    @Test
    fun `未来条目不会影响今天`() {
        val changes = mapOf((today + 1) to 30 * min)
        assertEquals(default, GoalTimeline.secondsOn(changes, today))
        assertEquals(30 * min, GoalTimeline.secondsOn(changes, today + 1))
    }

    @Test
    fun `乱序构造的 map 结果一致（求值不许依赖插入顺序）`() {
        val a = linkedMapOf(100L to 30 * min, 200L to 60 * min, 300L to 45 * min)
        val b = linkedMapOf(300L to 45 * min, 100L to 30 * min, 200L to 60 * min)
        for (day in listOf(0L, 99L, 100L, 199L, 200L, 299L, 300L, 99_999L)) {
            assertEquals(GoalTimeline.secondsOn(a, day), GoalTimeline.secondsOn(b, day))
        }
    }

    // ------------------------------------------------------------------
    // 写入：只能写「明天」
    // ------------------------------------------------------------------

    @Test
    fun `改目标只写「明天起」那一条`() {
        val next = GoalTimeline.withChange(emptyMap(), today, 30)
        assertEquals(mapOf((today + 1) to 30 * min), next)
    }

    @Test
    fun `★ 改目标不动历史（逐天扫一遍）`() {
        // 先造一段「历史」：过去真的改过一次
        val before = GoalTimeline.withChange(emptyMap(), today - 10, 30)
        val after = GoalTimeline.withChange(before, today, 60)

        // 从很早一直扫到今天（含）：**每一天**的判据都必须一模一样。
        // 只抽查几个点的话，「只有某一天变了」这种情况会被漏掉。
        for (day in (today - 400)..today) {
            assertEquals(
                "第 $day 天的目标被这次改动改动了 —— 用户会看到「昨天的卡不见了」",
                GoalTimeline.secondsOn(before, day),
                GoalTimeline.secondsOn(after, day),
            )
        }
        // 而明天起真的变了
        assertEquals(60 * min, GoalTimeline.secondsOn(after, today + 1))
    }

    @Test
    fun `今天仍按旧目标算`() {
        val before = GoalTimeline.withChange(emptyMap(), today - 1, 30)
        // 昨天（= 今天-1）改成 30，所以今天按 30 算；今天再改成 60 → 只影响明天
        assertEquals(30 * min, GoalTimeline.secondsOn(before, today))
        val after = GoalTimeline.withChange(before, today, 60)
        assertEquals(30 * min, GoalTimeline.secondsOn(after, today))
        assertEquals(60 * min, GoalTimeline.secondsOn(after, today + 1))
    }

    @Test
    fun `同一天改两次，第二次覆盖第一次（不会留下两条明天）`() {
        val once = GoalTimeline.withChange(emptyMap(), today, 30)
        val twice = GoalTimeline.withChange(once, today, 60)
        assertEquals(mapOf((today + 1) to 60 * min), twice)
    }

    @Test
    fun `改回原值等于取消这次改动`() {
        // 今天本来是 40（默认）。先改成 30（明天起），又改回 40 → 什么都不该剩
        val once = GoalTimeline.withChange(emptyMap(), today, 30)
        assertEquals(mapOf((today + 1) to 30 * min), once)
        val back = GoalTimeline.withChange(once, today, 40)
        assertEquals(emptyMap<Long, Int>(), back)
        assertNull(GoalTimeline.pendingMinutes(back, today))
    }

    @Test
    fun `越界的值会被夹到可选范围内`() {
        val lo = GoalTimeline.withChange(emptyMap(), today, 1)
        assertEquals(GoalTimeline.MIN_MINUTES * min, lo[today + 1])
        val hi = GoalTimeline.withChange(emptyMap(), today, 9999)
        assertEquals(GoalTimeline.MAX_MINUTES * min, hi[today + 1])
    }

    // ------------------------------------------------------------------
    // pending：「我刚才那一下改到了什么」
    // ------------------------------------------------------------------

    @Test
    fun `pending 只在明天与今天不同时才不是 null`() {
        assertNull(GoalTimeline.pendingMinutes(emptyMap(), today))
        // 明天与今天相同（等于默认）→ 没有待生效的改动
        assertNull(GoalTimeline.pendingMinutes(mapOf((today + 1) to default), today))
        assertEquals(30, GoalTimeline.pendingMinutes(GoalTimeline.withChange(emptyMap(), today, 30), today))
    }

    @Test
    fun `pending 报的是明天那个值，不是今天这个`() {
        val changes = GoalTimeline.withChange(emptyMap(), today, 30)
        assertEquals(30, GoalTimeline.pendingMinutes(changes, today))
        // 明天之后，同一个 map 里「今天」就变成 30 了，于是不再有 pending
        assertNull(GoalTimeline.pendingMinutes(changes, today + 1))
    }

    // ------------------------------------------------------------------
    // 归一化
    // ------------------------------------------------------------------

    @Test
    fun `normalize 去掉被盖住的冗余条目`() {
        // 200 那一条与它生效前的值（30 分钟）相同 —— 有它没它求值一样
        val raw = linkedMapOf(100L to 30 * min, 200L to 30 * min, 300L to 60 * min)
        assertEquals(
            mapOf(100L to 30 * min, 300L to 60 * min),
            GoalTimeline.normalize(raw),
        )
    }

    @Test
    fun `normalize 不会把「改回默认值」当成冗余（那是真的改动）`() {
        // ⚠️ 这一条是上面那条的边界，我第一版把这两个弄混了：
        //    「值等于默认值」≠「冗余」。冗余的定义是**与它生效前的值相同**。
        //    100→30 分钟、200→40 分钟是两次真的改动，中间那条必须留下 ——
        //    删掉它，200 以后就一直是 30 分钟了。
        val raw = linkedMapOf(100L to 30 * min, 200L to default)
        assertEquals(raw, GoalTimeline.normalize(raw))
        assertEquals(default, GoalTimeline.secondsOn(GoalTimeline.normalize(raw), 200))
    }

    @Test
    fun `normalize 不改变任何一天的求值结果`() {
        val raw = mapOf(100L to 30 * min, 200L to default, 300L to 60 * min, 400L to 60 * min)
        val clean = GoalTimeline.normalize(raw)
        for (day in 0L..500L) {
            assertEquals(GoalTimeline.secondsOn(raw, day), GoalTimeline.secondsOn(clean, day))
        }
    }

    @Test
    fun `normalize 是幂等的`() {
        val raw = mapOf(100L to 30 * min, 200L to default, 300L to 60 * min)
        assertEquals(GoalTimeline.normalize(raw), GoalTimeline.normalize(GoalTimeline.normalize(raw)))
    }

    // ------------------------------------------------------------------
    // 迁移（3.3.0 那一个没有日期的「当前目标」）
    // ------------------------------------------------------------------

    @Test
    fun `迁移：老值落在最早那一天，于是覆盖全部历史`() {
        val migrated = GoalTimeline.normalize(
            mapOf(GoalTimeline.SINCE_ALWAYS to 30 * min)
        )
        // 升级那一刻：过去、今天、将来全都还是 30 —— 一天都没变
        for (day in listOf(today - 9999, today - 1, today, today + 1, today + 9999)) {
            assertEquals(30 * min, GoalTimeline.secondsOn(migrated, day))
        }
        assertNull(GoalTimeline.pendingMinutes(migrated, today))
    }

    @Test
    fun `迁移之后的老值上，再改一次仍然只影响明天`() {
        val migrated = GoalTimeline.normalize(
            mapOf(GoalTimeline.SINCE_ALWAYS to 30 * min)
        )
        val after = GoalTimeline.withChange(migrated, today, 60)
        assertEquals(30 * min, GoalTimeline.secondsOn(after, today))
        assertEquals(60 * min, GoalTimeline.secondsOn(after, today + 1))
        assertEquals(30 * min, GoalTimeline.secondsOn(after, today - 50))
    }

    @Test
    fun `哨兵值本身是「从最早生效」，所以它必须小于任何真实日期`() {
        // 真实 epochDay 从 1970-01-01 起是 0，所以只要小于 0 就够；
        // 用 Long.MIN_VALUE 是为了让「有人不小心用它做减法」也一眼看得出来
        assertTrue(GoalTimeline.SINCE_ALWAYS < -1_000_000L)
        assertEquals(
            30 * min,
            GoalTimeline.secondsOn(mapOf(GoalTimeline.SINCE_ALWAYS to 30 * min), 0),
        )
    }
}
