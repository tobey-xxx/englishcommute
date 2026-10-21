package com.example.englishcommute.word

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * `Fsrs.kt` 与官方参照实现（`tools/fsrs-reference`，py-fsrs 6.3.2）的**逐值比对**。
 *
 * ⚠️ 这是移植类工作唯一站得住的验证方式。肉眼比对公式看不出来 ——
 *    一个下标写错（`parameters[9]` 写成 `parameters[10]`）在代码里是完全对称、
 *    看着很对的一行，只有跑数值才会露馅。
 *
 * 向量的来源与重新生成方式见 `tools/gen-fsrs-vectors.py` 的头部注释。
 * 跑法：`./gradlew :app:testDebugUnitTest`
 */
class FsrsTest {

    /** 单测一律关抖动 —— 否则每跑一次结果都不一样，断言就没了意义 */
    private val plain = FsrsScheduler(enableFuzzing = false)

    private fun check(tag: String, want: Double, got: Double) {
        if (abs(want - got) > EPS) {
            throw AssertionError(
                "$tag\n    期望 %.15f\n    实际 %.15f\n    差   %.3e".format(want, got, abs(want - got))
            )
        }
    }

    private fun checkInt(tag: String, want: Int?, got: Int?) {
        if (want != got) throw AssertionError("$tag\n    期望 $want\n    实际 $got")
    }

    // =====================================================================
    // 一、与官方参照实现逐值一致
    // =====================================================================

    @Test
    fun `每个用例的每一步都与官方实现一致`() {
        assertTrue("向量文件是空的，先跑 tools/gen-fsrs-vectors.py", FSRS_VECTORS.isNotEmpty())

        for (case in FSRS_VECTORS) {
            var card = FsrsCard(dueAtMillis = FSRS_VECTOR_T0_MILLIS)
            var now = FSRS_VECTOR_T0_MILLIS

            case.steps.forEachIndexed { i, step ->
                now += step.gapDays.toLong() * FsrsScheduler.MILLIS_PER_DAY
                val want = case.trace[i]
                val tag = "[${case.name}] 第 ${i + 1} 步（rating=${step.rating}, gap=${step.gapDays}天）"

                // 复习**之前**的可提取度
                check("$tag · retrievabilityBefore", want.retrievabilityBefore, plain.retrievability(card, now))

                card = plain.review(card, step.rating, now, Random(1))

                checkInt("$tag · phase", want.phase, card.phase)
                checkInt("$tag · step", want.step, card.step)
                check("$tag · stability", want.stability, card.stability ?: Double.NaN)
                check("$tag · difficulty", want.difficulty, card.difficulty ?: Double.NaN)
                check(
                    "$tag · dueDays",
                    want.dueDays,
                    (card.dueAtMillis - FSRS_VECTOR_T0_MILLIS).toDouble() / FsrsScheduler.MILLIS_PER_DAY,
                )
            }
        }
    }

    // =====================================================================
    // 二、界面承诺：四个按钮各自的下次间隔
    // =====================================================================

    @Test
    fun `四种评分的首次间隔与官方一致`() {
        // 新卡第一次评分的 S0 分别是 w0..w3，取整后应落在 1/1/2/8 天
        val expect = mapOf(
            FsrsScheduler.RATING_AGAIN to 1,
            FsrsScheduler.RATING_HARD to 1,
            FsrsScheduler.RATING_GOOD to 2,
            FsrsScheduler.RATING_EASY to 8,
        )
        for ((rating, days) in expect) {
            val card = FsrsCard(dueAtMillis = T0)
            checkInt(
                "新卡 + rating=$rating 的首次间隔",
                days,
                plain.previewIntervalDays(card, rating, T0),
            )
        }
    }

    @Test
    fun `previewIntervalDays 与实际 review 的结果一致（不抖动时）`() {
        var card = FsrsCard(dueAtMillis = T0)
        var now = T0
        // 先走两步，造出一个非新卡状态
        card = plain.review(card, FsrsScheduler.RATING_GOOD, now, Random(1)); now += 2 * DAY
        card = plain.review(card, FsrsScheduler.RATING_GOOD, now, Random(1)); now += 7 * DAY

        for (rating in FsrsScheduler.RATING_AGAIN..FsrsScheduler.RATING_EASY) {
            val previewed = plain.previewIntervalDays(card, rating, now)
            val actual = plain.review(card, rating, now, Random(1))
            val actualDays = ((actual.dueAtMillis - now) / FsrsScheduler.MILLIS_PER_DAY).toInt()
            checkInt("rating=$rating 的预览 vs 实际", actualDays, previewed)
        }
    }

    // =====================================================================
    // 三、★ 两条验收标准（用向量里的真实数据断言，而不是「我觉得应该」）
    // =====================================================================

    @Test
    fun `连点 Again 时下次间隔不增长`() {
        val intervals = replay("again-x3")
        assertTrue("至少要有两次 Again 才谈得上「不增长」，实际 ${intervals.size} 次", intervals.size >= 3)
        for (i in 1 until intervals.size) {
            assertTrue(
                "第 ${i + 1} 次的间隔 ${intervals[i]} 天比上一次 ${intervals[i - 1]} 天更长 —— " +
                    "「Again」意味着没记住，间隔绝不该变长（完整序列 $intervals）",
                intervals[i] <= intervals[i - 1],
            )
        }
    }

    @Test
    fun `连点 Easy 时间隔逐次变长`() {
        val intervals = replay("easy-x3")
        for (i in 1 until intervals.size) {
            assertTrue(
                "第 ${i + 1} 次的间隔 ${intervals[i]} 天没有比上一次 ${intervals[i - 1]} 天更长（完整序列 $intervals）",
                intervals[i] > intervals[i - 1],
            )
        }
    }

    /**
     * 重放一个用例，返回**每次复习给出的间隔**（不是「相对 T0 的到期日」）。
     *
     * ⚠️ 这两个量很容易混：`again-x3` 的到期日是 2→2→3→4 天，看着在涨，
     *    但**间隔**是 1→1→1→1，一次都没涨。验收标准必须说「间隔」。
     */
    private fun replay(name: String): List<Int> {
        val case = FSRS_VECTORS.firstOrNull { it.name == name }
            ?: throw AssertionError("向量里没有用例 $name")
        var card = FsrsCard(dueAtMillis = FSRS_VECTOR_T0_MILLIS)
        var now = FSRS_VECTOR_T0_MILLIS
        return case.steps.map { step ->
            now += step.gapDays.toLong() * FsrsScheduler.MILLIS_PER_DAY
            card = plain.review(card, step.rating, now, Random(1))
            ((card.dueAtMillis - now) / FsrsScheduler.MILLIS_PER_DAY).toInt()
        }
    }

    // =====================================================================
    // 四、抖动（生产开着、单测关着）
    // =====================================================================

    @Test
    fun `开启抖动后结果会变，但始终落在合法范围内`() {
        val fuzzed = FsrsScheduler(enableFuzzing = true)
        // 造一张「下次大约 30 天」的卡
        var card = FsrsCard(dueAtMillis = T0)
        var now = T0
        card = fuzzed.review(card, FsrsScheduler.RATING_EASY, now, Random(1)); now += 8 * DAY

        val plainDays = fuzzed.previewIntervalDays(card, FsrsScheduler.RATING_EASY, now)
        val seen = mutableSetOf<Int>()
        repeat(80) { i ->
            val next = fuzzed.review(card, FsrsScheduler.RATING_EASY, now, Random(i))
            val days = ((next.dueAtMillis - now) / FsrsScheduler.MILLIS_PER_DAY).toInt()
            assertTrue("抖动后的间隔 $days 天必须 >= 1", days >= 1)
            assertTrue("抖动后的间隔 $days 天超过了上限 ${fuzzed.maximumIntervalDays}", days <= fuzzed.maximumIntervalDays)
            assertTrue(
                "抖动偏离过大：基准 $plainDays 天，抖到了 $days 天",
                abs(days - plainDays) <= maxOf(3, (plainDays * 0.25).toInt()),
            )
            seen += days
        }
        assertTrue("基准间隔 $plainDays 天时抖动应当产生不止一个值，实际只有 $seen", seen.size > 1)
    }

    @Test
    fun `短间隔不抖动（2 天以下）`() {
        val fuzzed = FsrsScheduler(enableFuzzing = true)
        val card = FsrsCard(dueAtMillis = T0)
        repeat(30) { i ->
            val next = fuzzed.review(card, FsrsScheduler.RATING_GOOD, T0, Random(i))
            // 新卡 Good 的间隔是 2 天，< 2.5 所以不抖，必须恒为 2
            checkInt(
                "新卡 Good 的间隔（2 天，低于抖动门槛 2.5）",
                2,
                ((next.dueAtMillis - T0) / FsrsScheduler.MILLIS_PER_DAY).toInt(),
            )
        }
    }

    // =====================================================================
    // 五、边界与防御
    // =====================================================================

    @Test
    fun `参数个数不是 21 时立刻报错`() {
        assertThrows(IllegalArgumentException::class.java) {
            FsrsScheduler(parameters = DoubleArray(20))
        }
    }

    @Test
    fun `目标保持率越界时立刻报错`() {
        assertThrows(IllegalArgumentException::class.java) { FsrsScheduler(desiredRetention = 0.0) }
        assertThrows(IllegalArgumentException::class.java) { FsrsScheduler(desiredRetention = 1.0) }
    }

    @Test
    fun `未知评分被拒绝`() {
        assertThrows(IllegalArgumentException::class.java) {
            plain.review(FsrsCard(dueAtMillis = T0), 5, T0, Random(1))
        }
    }

    @Test
    fun `新卡的可提取度是 0（没有复习记录就谈不上还记得）`() {
        check("新卡的 R", 0.0, plain.retrievability(FsrsCard(dueAtMillis = T0), T0))
    }

    @Test
    fun `稳定性永不低于下限`() {
        var card = FsrsCard(dueAtMillis = T0)
        var now = T0
        // 反复忘记：稳定性会被压得很低，但绝不能到 0 或负数
        repeat(10) {
            now += DAY
            card = plain.review(card, FsrsScheduler.RATING_AGAIN, now, Random(1))
            assertTrue(
                "第 ${it + 1} 次 Again 后 stability=${card.stability}，必须 >= ${FsrsScheduler.STABILITY_MIN}",
                (card.stability ?: 0.0) >= FsrsScheduler.STABILITY_MIN,
            )
        }
    }

    @Test
    fun `难度始终夹在 1 到 10 之间`() {
        var card = FsrsCard(dueAtMillis = T0)
        var now = T0
        val ratings = listOf(
            FsrsScheduler.RATING_AGAIN, FsrsScheduler.RATING_EASY, FsrsScheduler.RATING_HARD,
            FsrsScheduler.RATING_GOOD, FsrsScheduler.RATING_AGAIN, FsrsScheduler.RATING_EASY,
        )
        ratings.forEach { r ->
            now += DAY
            card = plain.review(card, r, now, Random(1))
            val d = card.difficulty ?: 0.0
            assertTrue(
                "rating=$r 后 difficulty=$d，必须夹在 [1,10]",
                d in FsrsScheduler.MIN_DIFFICULTY..FsrsScheduler.MAX_DIFFICULTY,
            )
        }
    }

    private companion object {
        const val EPS = 1e-9
        const val DAY = FsrsScheduler.MILLIS_PER_DAY
        val T0 = FSRS_VECTOR_T0_MILLIS
    }
}
