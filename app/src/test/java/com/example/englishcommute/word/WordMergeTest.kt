package com.example.englishcommute.word

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 生词本的合并规则（[WordMerge]）。
 *
 * ## 为什么这几条必须在单测里
 *
 * 用户要的是「在 A 上加的词 / 复习的进度，换到 B 也能看到」。
 * 而生词本**不是**打卡那种「只增的计数」：卡片会被复习（状态变）、会被删除
 * （消失）。对可变状态用「谁后写谁赢」必然丢东西，所以改成
 * **复习事件取并集 + 状态由事件重放算出来**。
 *
 * 这套做法成立与否只取决于两条，而两条都**不会报错**、只在有第二台设备时才显形：
 *
 * 1. **并集是不是真的不丢**（顺序无关、幂等）；
 * 2. ★ **重放出来的状态，与当初一条条复习出来的状态是不是完全一样**
 *    （这条依赖 FSRS 的抖动必须是确定性的 —— 4.1.0 为此改了 `FsrsScheduler.review`）。
 *
 * 第 2 条如果错了，症状是「两台设备的到期日差几天」，而且**没有哪一边是错的**。
 */
class WordMergeTest {

    private val scheduler = FsrsScheduler()
    private val t0 = 1_800_000_000_000L
    private val day = FsrsScheduler.MILLIS_PER_DAY

    private fun log(at: Long, rating: Int, device: String) = ReviewLog(at, rating, device)

    // ------------------------------------------------------------------
    // 并集：不丢、幂等、顺序无关
    // ------------------------------------------------------------------

    @Test
    fun `两台设备各自复习同一天，两条都留得住`() {
        val a = listOf(log(t0, FsrsScheduler.RATING_GOOD, "phoneA"))
        val b = listOf(log(t0 + 1, FsrsScheduler.RATING_GOOD, "tabletB"))
        assertEquals(2, WordMerge.unionReviews(a, b).size)
    }

    @Test
    fun `同一份日志并两次不会变长（幂等）`() {
        val a = listOf(log(t0, 3, "phoneA"), log(t0 + 1000, 1, "phoneA"))
        assertEquals(a, WordMerge.unionReviews(a, a))
    }

    @Test
    fun `同一时刻、不同设备＝两条（身份是「设备 + 时刻」）`() {
        val merged = WordMerge.unionReviews(
            listOf(log(t0, 3, "phoneA")),
            listOf(log(t0, 3, "tabletB")),
        )
        assertEquals(2, merged.size)
    }

    @Test
    fun `★ 交换两边参数，结果逐字节相同（顺序无关）`() {
        val a = listOf(log(t0 + 5, 3, "phoneA"), log(t0 + 1, 1, "phoneA"))
        val b = listOf(log(t0 + 2, 3, "tabletB"))
        assertEquals(WordMerge.unionReviews(a, b), WordMerge.unionReviews(b, a))
    }

    @Test
    fun `并集按 (时刻, 设备) 排序 —— 重放只认这个顺序`() {
        val merged = WordMerge.unionReviews(
            listOf(log(t0 + 100, 3, "phoneA")),
            listOf(log(t0 + 50, 3, "tabletB"), log(t0 + 50, 3, "phoneA")),
        )
        assertEquals(listOf(t0 + 50, t0 + 50, t0 + 100), merged.map { it.at })
        assertEquals(listOf("phoneA", "tabletB", "phoneA"), merged.map { it.device })
    }

    // ------------------------------------------------------------------
    // ★★ 重放 == 增量
    // ------------------------------------------------------------------

    @Test
    fun `★★ 重放出来的状态与一条条复习出来的完全一样`() {
        // ① 增量路径：就是 App 里真正发生的事（WordStore.review 逐步更新）
        var card = FsrsCard(dueAtMillis = t0)
        var reps = 0
        var lapses = 0
        val events = mutableListOf<ReviewLog>()
        val script = listOf(
            t0 + 1 * day to FsrsScheduler.RATING_GOOD,
            t0 + 3 * day to FsrsScheduler.RATING_GOOD,
            t0 + 9 * day to FsrsScheduler.RATING_AGAIN,
            t0 + 9 * day + 60_000 to FsrsScheduler.RATING_GOOD,
            t0 + 30 * day to FsrsScheduler.RATING_EASY,
        )
        for ((at, rating) in script) {
            val isLapse = rating == FsrsScheduler.RATING_AGAIN && reps > 0
            card = scheduler.review(card, rating, at)
            reps += 1
            if (isLapse) lapses += 1
            events.add(log(at, rating, "phoneA"))
        }

        // ② 重放路径：另一台设备拿到同一串事件之后要算出的东西
        val replayed = WordMerge.replay(t0, events, scheduler)

        assertEquals("到期时间必须一致", card.dueAtMillis, replayed.fsrs.dueAtMillis)
        assertEquals("阶段必须一致", card.phase, replayed.fsrs.phase)
        assertEquals("稳定度必须一致", card.stability, replayed.fsrs.stability)
        assertEquals("难度必须一致", card.difficulty, replayed.fsrs.difficulty)
        assertEquals("复习次数必须一致", reps, replayed.reps)
        assertEquals("遗忘次数必须一致", lapses, replayed.lapses)
    }

    @Test
    fun `★ 重放对顺序不敏感：并集怎么来的都一样`() {
        val events = listOf(
            log(t0 + day, 3, "phoneA"),
            log(t0 + 2 * day, 3, "tabletB"),
            log(t0 + 5 * day, 1, "phoneA"),
        )
        val r1 = WordMerge.replay(t0, events, scheduler)
        val r2 = WordMerge.replay(t0, events.reversed(), scheduler)
        assertEquals(r1.fsrs, r2.fsrs)
        assertEquals(r1.reps, r2.reps)
    }

    @Test
    fun `新卡第一次点「忘记」不算 lapse（与增量路径同一条判据）`() {
        val r = WordMerge.replay(t0, listOf(log(t0 + 1, FsrsScheduler.RATING_AGAIN, "d")), scheduler)
        assertEquals(1, r.reps)
        assertEquals(0, r.lapses)
    }

    @Test
    fun `没有复习记录时，重放出来的就是一张新卡`() {
        val r = WordMerge.replay(t0, emptyList(), scheduler)
        assertEquals(0, r.reps)
        assertEquals(FsrsScheduler.PHASE_LEARNING, r.fsrs.phase)
        assertEquals(t0, r.fsrs.dueAtMillis)
    }

    // ------------------------------------------------------------------
    // 删除：两个只增的时间戳
    // ------------------------------------------------------------------

    @Test
    fun `墓碑取 MAX：删了就删了`() {
        val merged = WordMerge.mergeTombstones(mapOf("a" to 100L), mapOf("a" to 200L))
        assertEquals(200L, merged["a"])
        assertFalse(WordMerge.isAlive(addedAt = 50, deletedAt = merged["a"]))
    }

    @Test
    fun `★ 删了再加：新的 addedAt 晚于墓碑 ⇒ 卡片回来`() {
        assertTrue(WordMerge.isAlive(addedAt = 300, deletedAt = 200))
    }

    @Test
    fun `没有墓碑就是活着`() {
        assertTrue(WordMerge.isAlive(addedAt = 1, deletedAt = null))
    }

    // ------------------------------------------------------------------
    // 语境与形态
    // ------------------------------------------------------------------

    private fun ctx(id: String, start: Long) =
        WordContext(id, "T", start, start + 1000, "sentence", null)

    @Test
    fun `语境取并集：本机顺序在前，服务端独有的追加在后面`() {
        val merged = WordMerge.mergeContexts(listOf(ctx("ep1", 1)), listOf(ctx("ep2", 2)))
        assertEquals(listOf("ep1", "ep2"), merged.map { it.episodeId })
    }

    @Test
    fun `语境去重按「哪一集的哪一行」`() {
        val merged = WordMerge.mergeContexts(listOf(ctx("ep1", 1)), listOf(ctx("ep1", 1)))
        assertEquals(1, merged.size)
    }

    @Test
    fun `语境最多留 3 条（与本地 MAX_CONTEXTS 一致）`() {
        val local = listOf(ctx("ep1", 1), ctx("ep2", 2), ctx("ep3", 3))
        val merged = WordMerge.mergeContexts(local, listOf(ctx("ep4", 4)))
        assertEquals(3, merged.size)
        assertEquals(listOf("ep1", "ep2", "ep3"), merged.map { it.episodeId })
    }

    @Test
    fun `形态取「加入更晚」的那一份；时间相同取字典序小的（可交换）`() {
        assertEquals("b", WordMerge.pickSurface("a", 100, "b", 200))
        assertEquals("a", WordMerge.pickSurface("a", 200, "b", 100))
        assertEquals("a", WordMerge.pickSurface("a", 100, "b", 100))
        assertEquals(
            WordMerge.pickSurface("a", 100, "b", 100),
            WordMerge.pickSurface("b", 100, "a", 100),
        )
    }
}
