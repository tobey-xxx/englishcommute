package com.example.englishcommute.checkin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 多设备合并规则（[SyncMerge]）。
 *
 * ## 为什么这几条必须在单测里
 *
 * 用户举的那个例子是：
 *
 * > 当天在设备 A 上听了 25 分钟，切到 B 能看到今日进度，
 * > 再听 15 分钟就能完成打卡。
 *
 * 它成立的全部依据是一步算术：**服务端总量 − 本机 = 其它设备**，
 * 界面再把两者相加。写错一步就会：
 *
 * | 错法 | 症状 |
 * | --- | --- |
 * | 把服务端总量直接当 others | 本机算两遍 ⇒ 数字莫名其妙变大（看起来像「终于同步上了」） |
 * | 在 merge 里用 max 而不是相加 | 两台设备各听一半 ⇒ 永远到不了目标 |
 * | 忘了 clamp 到 0 | 另一台设备清空记录后，本机出现**负的**剩余时间 |
 *
 * 三条都**不会报错**，而且只在真机上、只在有第二台设备时才显形。
 * 所以它们在这里被钉死 —— 桌面能跑、每次构建都会跑。
 */
class SyncMergeTest {

    private val d1 = 20_500L
    private val d2 = 20_501L

    // ------------------------------------------------------------------
    // merge：两笔独立的账，只能相加
    // ------------------------------------------------------------------

    @Test
    fun `本机 25 分钟 + 另一台 15 分钟 = 40 分钟`() {
        val mine = mapOf(d1 to 25 * 60)
        val others = mapOf(d1 to 15 * 60)
        assertEquals(40 * 60, SyncMerge.merge(mine, others)[d1])
    }

    @Test
    fun `只有一边有那一天时，就是它自己（不是 0，也不是相加出错）`() {
        val mine = mapOf(d1 to 600)
        val others = mapOf(d2 to 900)
        val out = SyncMerge.merge(mine, others)
        assertEquals(600, out[d1])
        assertEquals(900, out[d2])
        assertEquals(2, out.size)
    }

    @Test
    fun `两边都空 → 空（未登录用户走的就是这一支）`() {
        assertTrue(SyncMerge.merge(emptyMap(), emptyMap()).isEmpty())
    }

    @Test
    fun `未登录（others 为空）时行为与拆分之前完全一致`() {
        val mine = mapOf(d1 to 1500, d2 to 300)
        assertEquals(mine, SyncMerge.merge(mine, emptyMap()))
    }

    @Test
    fun `★ 不是取最大：两台各听一半也必须相加`() {
        // 用 max 的话这里会得到 1500（永远到不了 2400 的目标）
        val out = SyncMerge.merge(mapOf(d1 to 1500), mapOf(d1 to 900))
        assertEquals(2400, out[d1])
    }

    // ------------------------------------------------------------------
    // othersFrom：那一步减法
    // ------------------------------------------------------------------

    @Test
    fun `反算：总量含本机，所以要先减掉本机那一份`() {
        // 服务端说今天 2400（本机 1500 + 另一台 900）
        val others = SyncMerge.othersFrom(mapOf(d1 to 2400), mapOf(d1 to 1500))
        assertEquals(900, others[d1])
    }

    @Test
    fun `★ 这条是防「本机算两遍」的：反算之后再加回去，必须回到总量`() {
        val mine = mapOf(d1 to 1500, d2 to 0)
        val totals = mapOf(d1 to 2400, d2 to 600)
        val others = SyncMerge.othersFrom(totals, mine)
        // ★ 这就是客户端侧的合并与服务端求和的「同一个算术」
        assertEquals(totals[d1]!! - 0, SyncMerge.merge(mine, others)[d1])
        assertEquals(600, SyncMerge.merge(mine, others)[d2])
    }

    @Test
    fun `只有本机听过时，其它设备那一份是空的`() {
        val others = SyncMerge.othersFrom(mapOf(d1 to 1500), mapOf(d1 to 1500))
        assertTrue(others.isEmpty())
    }

    @Test
    fun `★ 总量小于本机时归零（另一台设备清了记录，本机还没清）`() {
        val others = SyncMerge.othersFrom(mapOf(d1 to 100), mapOf(d1 to 1500))
        assertTrue("不能出现负数：界面会算出负的剩余时间", others.isEmpty())
    }

    @Test
    fun `服务端没有这一天时，它不出现在 others 里`() {
        val others = SyncMerge.othersFrom(mapOf(d1 to 900), mapOf(d1 to 900, d2 to 300))
        assertTrue(others.isEmpty())
    }

    // ------------------------------------------------------------------
    // 反复同步 / 乱序：结果必须稳定
    // ------------------------------------------------------------------

    @Test
    fun `同样的输入同步两次，结果完全相同（幂等）`() {
        val mine = mapOf(d1 to 1500)
        val totals = mapOf(d1 to 2400)
        val a = SyncMerge.othersFrom(totals, mine)
        val b = SyncMerge.othersFrom(totals, mine)
        assertEquals(a, b)
        assertEquals(SyncMerge.merge(mine, a), SyncMerge.merge(mine, b))
    }

    @Test
    fun `★ 乱序到达不影响最终显示：先收到小的、再收到大的，结果一样`() {
        val mine = mapOf(d1 to 1500)
        // 中间态：另一台刚到 300
        val mid = SyncMerge.othersFrom(mapOf(d1 to 1800), mine)
        assertEquals(300, mid[d1])
        // 之后：另一台又听了 600
        val end = SyncMerge.othersFrom(mapOf(d1 to 2400), mine)
        assertEquals(900, end[d1])
        assertEquals(2400, SyncMerge.merge(mine, end)[d1])
    }

    // ------------------------------------------------------------------
    // ★★ 4.1.1 修的那个 bug：累加只能进本机那一列
    // ------------------------------------------------------------------
    // 症状（用户录屏）：另一台设备有 25 分钟、本机放着音频，
    // **8 秒内数字从 617 分钟涨到 667 分钟**，而且会一直涨。
    // 根因：累加的起点取了「合并值」，终点却写进「本机那一列」——
    // 每累加一次就把别的设备那部分并进本机一次。

    @Test
    fun `★ 累加只进本机那一列：其它设备那部分不会被吞进去`() {
        val cap = 24 * 3600
        val once = SyncMerge.accumulate(mineNow = 0, others = 1500, delta = 5, cap = cap, goal = 2400)
        assertEquals("本机那一列只该涨自己的 5 秒", 5, once.mine)
        assertEquals("合计 = 本机 + 其它设备", 1505, once.total)
    }

    @Test
    fun `★★ 连听 60 次（模拟 5 分钟）合计只涨 300 秒，不会爆炸`() {
        val cap = 24 * 3600
        var mine = 0
        var total = 0
        repeat(60) {
            val acc = SyncMerge.accumulate(mineNow = mine, others = 1500, delta = 5, cap = cap, goal = 2400)
            mine = acc.mine
            total = acc.total
        }
        assertEquals(300, mine)
        // 修之前这里是 300 + 60×1500 = 90300 秒（约 25 小时），会一路涨到封顶
        assertEquals(1800, total)
    }

    @Test
    fun `★ 跨线判据看合计，但增长的仍是本机那一列`() {
        val cap = 24 * 3600
        val acc = SyncMerge.accumulate(mineNow = 990, others = 1400, delta = 20, cap = cap, goal = 2400)
        assertEquals(1010, acc.mine)
        assertEquals(2410, acc.total)
        assertTrue("合计跨过目标线才算达标", acc.justCompleted)
    }

    @Test
    fun `没跨线时不乱报庆祝`() {
        val cap = 24 * 3600
        val acc = SyncMerge.accumulate(mineNow = 0, others = 0, delta = 5, cap = cap, goal = 2400)
        assertFalse(acc.justCompleted)
    }

    @Test
    fun `封顶只封本机那一列（防时钟跳变），不封别的设备`() {
        val cap = 100
        val acc = SyncMerge.accumulate(mineNow = 90, others = 50, delta = 60, cap = cap, goal = 2400)
        assertEquals(100, acc.mine)
        assertEquals(150, acc.total)
    }

    @Test
    fun `本机继续听时，显示是「两边相加」（另一台那部分不动）`() {
        val mine0 = mapOf(d1 to 1500)
        val others = SyncMerge.othersFrom(mapOf(d1 to 2400), mine0)
        // 本机又听了 5 分钟 ⇒ mine 变 1800，others 仍是 900
        val mine1 = mapOf(d1 to 1800)
        assertEquals(2700, SyncMerge.merge(mine1, others)[d1])
    }
}
