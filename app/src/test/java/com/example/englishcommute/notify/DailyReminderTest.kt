package com.example.englishcommute.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * 下一次提醒时刻（[DailyReminder.nextTriggerAt]）。
 *
 * ## 为什么这一条必须有单测
 *
 * 「每天 23:30」这句需求里藏着一个**跨天边界**：23:30 整点那一刻该算今天还是明天？
 * 算错的表现是「某一天没提醒」或者「一天提醒两次」，而这两种都**只在真机上
 * 到了那个时刻**才显形 —— 手动测试时你会验「现在排的对不对」，
 * 而漏掉的恰恰是「23:29:59 与 23:30:00 这一秒之差」。
 */
class DailyReminderTest {

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int = 0): Long =
        Calendar.getInstance().apply {
            clear()
            set(y, mo - 1, d, h, mi, s)
        }.timeInMillis

    private fun parts(ms: Long): List<Int> =
        Calendar.getInstance().apply { timeInMillis = ms }.let {
            listOf(
                it.get(Calendar.YEAR), it.get(Calendar.MONTH) + 1, it.get(Calendar.DAY_OF_MONTH),
                it.get(Calendar.HOUR_OF_DAY), it.get(Calendar.MINUTE),
            )
        }

    @Test
    fun `23点之前 → 今天 23_30`() {
        assertEquals(listOf(2026, 10, 17, 23, 30), parts(DailyReminder.nextTriggerAt(at(2026, 10, 17, 9, 0))))
    }

    @Test
    fun `★ 正好 23_30 → 排到明天（不能立刻响一次再排明天）`() {
        assertEquals(listOf(2026, 10, 18, 23, 30), parts(DailyReminder.nextTriggerAt(at(2026, 10, 17, 23, 30, 0))))
    }

    @Test
    fun `23_30 之后 → 明天`() {
        assertEquals(listOf(2026, 10, 18, 23, 30), parts(DailyReminder.nextTriggerAt(at(2026, 10, 17, 23, 45))))
    }

    @Test
    fun `23_29_59 仍是今天（差一秒不能跳到明天）`() {
        assertEquals(listOf(2026, 10, 17, 23, 30), parts(DailyReminder.nextTriggerAt(at(2026, 10, 17, 23, 29, 59))))
    }

    @Test
    fun `★ 跨月：10 月 31 日 23_30 之后 → 11 月 1 日`() {
        assertEquals(listOf(2026, 11, 1, 23, 30), parts(DailyReminder.nextTriggerAt(at(2026, 10, 31, 23, 31))))
    }

    @Test
    fun `★ 跨年：12 月 31 日 23_30 之后 → 明年 1 月 1 日`() {
        assertEquals(listOf(2027, 1, 1, 23, 30), parts(DailyReminder.nextTriggerAt(at(2026, 12, 31, 23, 31))))
    }

    @Test
    fun `排出来的时刻一定在未来`() {
        val now = at(2026, 10, 17, 23, 30, 1)
        assertTrue(DailyReminder.nextTriggerAt(now) > now)
    }
}
