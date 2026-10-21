package com.example.englishcommute.word

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 角标文字。这四条判据对应 [DueBadge] 类注释里的四段理由 ——
 * 改实现之前先读那四段，尤其「0 为什么不是 "0"」。
 */
class DueBadgeTest {

    @Test
    fun `0 和负数都不显示角标`() {
        // 「0 个待复习」的正确表达是**没有角标**，而不是挂一个 "0"。
        // 负数理论上到不了这里（dueCount 是 size），但代价为零就一起挡上 ——
        // 万一哪天上游换成一个算差值的地方，-1 会写出 "-1" 这种角标。
        assertEquals(null, DueBadge.text(0))
        assertEquals(null, DueBadge.text(-1))
        assertEquals(null, DueBadge.text(Int.MIN_VALUE))
    }

    @Test
    fun `1 到 99 原样显示`() {
        assertEquals("1", DueBadge.text(1))
        assertEquals("3", DueBadge.text(3))
        assertEquals("47", DueBadge.text(47))
        assertEquals("99", DueBadge.text(99))
    }

    @Test
    fun `超过 99 封顶成 99 加号`() {
        // 断更之后到期会堆起来 —— 四位数会把底栏那一格撑变形。
        // 100 是边界：`> CAP` 而不是 `>= CAP`，写反的话 99 会显示成 "99+"
        // （少一格信息，而且看不出错）。
        assertEquals("99+", DueBadge.text(100))
        assertEquals("99+", DueBadge.text(1000))
        assertEquals("99+", DueBadge.text(Int.MAX_VALUE))
    }

    @Test
    fun `封顶值是 99 而不是随手一个数`() {
        // 断言常量本身：把 CAP 改成 9 会让「12 个待复习」显示成 "9+"，
        // 那不是封顶，那是丢信息。这条守的是「三位数以内原样显示」。
        assertEquals(99, DueBadge.CAP)
    }
}
