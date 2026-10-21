package com.example.englishcommute.word

import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * FSRS 卡片的内存状态。**纯数据类，不依赖任何 Android API** ——
 * 这样它能在 JVM 单元测试（`src/test/`）里直接跑，跟官方参照实现逐值比对。
 *
 * 字段与 py-fsrs 的 `Card` 一一对应：
 * [phase]↔state · [step]↔step · [stability]↔stability · [difficulty]↔difficulty
 * · [dueAtMillis]↔due · [lastReviewAtMillis]↔last_review
 */
data class FsrsCard(
    val phase: Int = FsrsScheduler.PHASE_LEARNING,
    val step: Int? = 0,
    val stability: Double? = null,
    val difficulty: Double? = null,
    val dueAtMillis: Long,
    val lastReviewAtMillis: Long? = null,
)

/**
 * FSRS-6 调度器 —— 从官方实现 `py-fsrs 6.3.2` **移植**的纯 Kotlin 版。
 *
 * ## 为什么要有这个类，而不是直接依赖某个库
 *
 * 官方列出的 Android 库 `android-fsrs` 只是一个 Android Studio 示例工程，
 * **没有 Maven 发布**，用不了。另一个 `FSRS-Kotlin` 耦合了作者自己 App 的包名、
 * `android.util.Log` 和 Compose 的 `mutableStateListOf`（`Grade` 枚举里甚至有
 * `R.color.grad_blue` 这种 UI 资源）—— 算法和界面混在一起，没法当依赖。
 *
 * ## 移植的正确性怎么保证
 *
 * **不靠肉眼比对公式，靠数值比对。** 完整链路：
 *
 * ```
 * tools/fsrs-reference/         ← py-fsrs 6.3.2 的只读副本（**不要改**）
 *         ↓ tools/gen-fsrs-vectors.py
 * app/src/test/.../FsrsVectors.kt   ← 17 个用例的期望值（生成物，不要手改）
 *         ↓ FsrsTest.kt
 *         逐值比对本文件（容差 1e-9）
 * ```
 *
 * 跑法：`./gradlew :app:testDebugUnitTest`
 *
 * ## 三处与官方默认配置**故意不同**的地方
 *
 * 1. **`learningSteps` / `relearningSteps` 都是空的。**
 *    官方默认 `[1min, 10min]` / `[10min]` —— 那是 Anki 式「当天重复到记住」的流程：
 *    新卡要连续评两次 Good 才进 Review。我们的场景是**通勤路上加词、当场复习一次**，
 *    用户不会举着手机等 10 分钟。传空之后，新词第一次评分就直接进 Review、按天排
 *    （S0：Again 0.21 天 / Hard 1.29 / Good 2.31 / Easy 8.30，取整后是 1/1/2/8 天）。
 *
 * 2. **`maximumIntervalDays = 365`**（官方默认 36500 天 = 100 年）。
 *    一年足够，而且不会让一个词从复习队列里彻底消失。
 *
 * 3. **`enableFuzzing = true`**（与官方默认一致）。给间隔加小幅随机抖动，
 *    避免「同一天加的词永远同一天到期」——那会让某天的复习量爆掉。
 *    单测里关掉它（否则不可复现），另测「抖动后仍落在允许区间内」。
 *
 * ## 两个从参照实现里读出来的、**文档里没写**的细节
 *
 * - **失败后的稳定性有个上限**（`_next_forget_stability` 的最后一行）：
 *   `S' = min(长期公式, S / e^(w17·w18))`。官方 wiki 的公式页只写了前者。
 * - **同日的稳定性增量有下限**：非 `Again` 的评分下 `inc = max(inc, 1.0)`，
 *   也就是「同一天里再评一次不会让稳定性倒退」。
 *
 * @param parameters 21 个 FSRS-6 参数，默认即官方默认值
 * @param desiredRetention 目标记忆保持率，0.9 表示「到期时你还有 90% 概率记得」
 * @param maximumIntervalDays 调度间隔上限（天）
 * @param enableFuzzing 是否给间隔加随机抖动
 */
class FsrsScheduler(
    val parameters: DoubleArray = DEFAULT_PARAMETERS,
    val desiredRetention: Double = 0.9,
    val maximumIntervalDays: Int = 365,
    val enableFuzzing: Boolean = true,
) {

    init {
        require(parameters.size == 21) {
            "FSRS-6 需要 21 个参数，收到 ${parameters.size} 个"
        }
        require(desiredRetention > 0.0 && desiredRetention < 1.0) {
            "目标保持率必须在 (0,1) 之间，收到 $desiredRetention"
        }
    }

    // =====================================================================
    // 派生量（对照 scheduler.py `__init__` 末尾两行）
    // =====================================================================

    /** `self._DECAY = -self.parameters[20]` */
    val decay: Double = -parameters[20]

    /**
     * `self._FACTOR = 0.9 ** (1 / self._DECAY) - 1`
     *
     * 这个常数的作用是让遗忘曲线**在 t = S 时恰好等于 0.9**
     * —— 也就是「稳定性的定义：可提取度掉到 90% 所需的天数」。
     * 默认参数下 `decay = -0.1542`、`factor ≈ 0.980346`。
     */
    val factor: Double = Math.pow(0.9, 1.0 / decay) - 1.0

    // =====================================================================
    // 公开 API
    // =====================================================================

    /**
     * 当前可提取度（还记得的概率），对照 `get_card_retrievability`。
     *
     * `R(t,S) = (1 + FACTOR · t / S) ^ DECAY`
     *
     * 没有复习记录（新卡）时返回 0 —— 与参照实现一致。
     */
    fun retrievability(card: FsrsCard, atMillis: Long): Double {
        val last = card.lastReviewAtMillis ?: return 0.0
        val s = card.stability ?: return 0.0
        // 参照实现用 timedelta.days（正数向下取整）；负数夹到 0
        val elapsedDays = (atMillis - last).coerceAtLeast(0L) / MILLIS_PER_DAY
        return Math.pow(1.0 + factor * elapsedDays / s, decay)
    }

    /**
     * 复习一次，返回新状态。对照 `review_card`。
     *
     * ## ★ 4.1.0：抖动源改成「由这次复习自己派生」——它现在是**纯函数**
     *
     * 原来是 `Random.Default`，于是同一个 `(卡片, 评分, 时刻)` 每次算出来的间隔
     * 都不一样（抖动 ±几 %）。那对单机无所谓，但**多设备同步做不到**：
     *
     *     设备 A 复习 → 得到 due = D1
     *     设备 B 拿到同一条复习记录重放 → 得到 due = D2（≠ D1）
     *
     * 两台设备的「下次到期」就永远对不上，而且**没有任何办法判断谁对**。
     *
     * 现在种子由这次复习本身派生（时刻 + 评分 + 卡片哈希）：
     * · **同一个输入必然同一个结果** ⇒ 重放别人的复习记录能得到与他完全一致的
     *   状态（生词本的跨设备同步就靠这一条）；
     * · 抖动**照旧存在**（不同卡片、不同天得到的种子不同），
     *   它本来就是为了「把到期日打散、别全挤在同一天」，这个性质没变。
     *
     * @param rating [RATING_AGAIN] / [RATING_HARD] / [RATING_GOOD] / [RATING_EASY]
     * @param atMillis 复习时刻（epoch 毫秒）
     * @param random 单测可以显式传 `Random(seed)`；默认走上面那套确定性派生
     */
    fun review(
        card: FsrsCard,
        rating: Int,
        atMillis: Long,
        random: Random = fuzzFor(card, rating, atMillis),
    ): FsrsCard = reviewInternal(card, rating, atMillis, random, fuzz = enableFuzzing)

    /**
     * 由「这次复习」派生抖动种子。
     *
     * ⚠️ 用 `card.hashCode()` 而不是某个字段：卡片是一个 data class，
     *    它的哈希涵盖了当前状态（阶段/稳定度/难度/到期时刻）——
     *    也就是说**同一张卡在不同状态下抖动不同**，这正是不希望所有卡片
     *    挤在同一天所需要的性质。
     */
    private fun fuzzFor(card: FsrsCard, rating: Int, atMillis: Long): Random =
        Random(atMillis * 31 + rating * 1_000_003L + card.hashCode())

    /**
     * 「如果现在点这个按钮，下次是几天后」—— 复习卡上四个按钮旁边要显示的数字。
     *
     * ⚠️ **刻意不带抖动**：抖动是为了打散到期日，而预览是个承诺
     * （用户点了 Good，看到的「3 天」就该是 3 天）。Anki 也是这么做的。
     */
    fun previewIntervalDays(card: FsrsCard, rating: Int, atMillis: Long): Int {
        val next = reviewInternal(card, rating, atMillis, Random(0), fuzz = false)
        return ((next.dueAtMillis - atMillis) / MILLIS_PER_DAY).toInt()
    }

    // =====================================================================
    // 主流程（对照 review_card）
    // =====================================================================

    private fun reviewInternal(
        card: FsrsCard,
        rating: Int,
        atMillis: Long,
        random: Random,
        fuzz: Boolean,
    ): FsrsCard {
        require(rating in RATING_AGAIN..RATING_EASY) { "未知评分：$rating" }

        // days_since_last_review：没有上次复习记录时是 None
        val daysSinceLast = card.lastReviewAtMillis?.let { (atMillis - it) / MILLIS_PER_DAY }
        val sameDay = daysSinceLast != null && daysSinceLast < 1

        var phase = card.phase
        var step = card.step
        var stability = card.stability
        var difficulty = card.difficulty
        var intervalDays: Int

        when (phase) {
            PHASE_LEARNING -> {
                // 参照实现里这里有 assert card.step is not None
                if (step == null) step = 0

                if (stability == null || difficulty == null) {
                    // 第一次复习：S0 / D0 初始化
                    stability = initialStability(rating)
                    difficulty = initialDifficulty(rating, clamp = true)
                } else if (sameDay) {
                    stability = shortTermStability(stability, rating)
                    difficulty = nextDifficulty(difficulty, rating)
                } else {
                    stability = nextStability(difficulty, stability, retrievability(
                        FsrsCard(phase, step, stability, difficulty, card.dueAtMillis, card.lastReviewAtMillis),
                        atMillis,
                    ), rating)
                    difficulty = nextDifficulty(difficulty, rating)
                }

                // ⚠️ 我们的 learningSteps 为空，所以**永远**满足下面第一个条件，
                //    直接进 Review。（另一支是「按分钟重复」的流程，我们用不到。）
                if (learningSteps.isEmpty() ||
                    (step >= learningSteps.size && rating != RATING_AGAIN)
                ) {
                    phase = PHASE_REVIEW
                    step = null
                    intervalDays = nextIntervalDays(stability)
                } else {
                    val r = applyLearningSteps(step, rating, learningSteps)
                    step = r.first
                    intervalDays = r.second
                    if (r.third) {
                        phase = PHASE_REVIEW
                        step = null
                    }
                }
            }

            PHASE_REVIEW -> {
                val s = stability ?: error("Review 状态的卡片必须有 stability")
                val d = difficulty ?: error("Review 状态的卡片必须有 difficulty")

                if (sameDay) {
                    stability = shortTermStability(s, rating)
                } else {
                    stability = nextStability(
                        d, s,
                        retrievability(
                            FsrsCard(phase, step, s, d, card.dueAtMillis, card.lastReviewAtMillis),
                            atMillis,
                        ),
                        rating,
                    )
                }
                difficulty = nextDifficulty(d, rating)

                if (rating == RATING_AGAIN && relearningSteps.isNotEmpty()) {
                    // ⚠️ 我们的 relearningSteps 为空，这一支走不到。
                    //    保留它是为了与参照实现同构 —— 将来若要加「重学步骤」不用重写。
                    val r = applyLearningSteps(0, rating, relearningSteps)
                    phase = PHASE_RELEARNING
                    step = r.first
                    intervalDays = r.second
                    if (r.third) {
                        phase = PHASE_REVIEW
                        step = null
                    }
                } else {
                    intervalDays = nextIntervalDays(stability)
                }
            }

            PHASE_RELEARNING -> {
                val s = stability ?: error("Relearning 状态的卡片必须有 stability")
                val d = difficulty ?: error("Relearning 状态的卡片必须有 difficulty")
                val st = step ?: 0

                if (sameDay) {
                    stability = shortTermStability(s, rating)
                    difficulty = nextDifficulty(d, rating)
                } else {
                    stability = nextStability(
                        d, s,
                        retrievability(
                            FsrsCard(phase, st, s, d, card.dueAtMillis, card.lastReviewAtMillis),
                            atMillis,
                        ),
                        rating,
                    )
                    difficulty = nextDifficulty(d, rating)
                }

                if (relearningSteps.isEmpty() ||
                    (st >= relearningSteps.size && rating != RATING_AGAIN)
                ) {
                    phase = PHASE_REVIEW
                    step = null
                    intervalDays = nextIntervalDays(stability)
                } else {
                    val r = applyLearningSteps(st, rating, relearningSteps)
                    step = r.first
                    intervalDays = r.second
                    if (r.third) {
                        phase = PHASE_REVIEW
                        step = null
                    }
                }
            }

            else -> error("未知卡片状态：$phase")
        }

        // 抖动只在 Review 状态生效（对照参照实现最后那段 if）
        if (fuzz && phase == PHASE_REVIEW) {
            intervalDays = fuzzedInterval(intervalDays, random)
        }

        return FsrsCard(
            phase = phase,
            step = step,
            stability = stability,
            difficulty = difficulty,
            dueAtMillis = atMillis + intervalDays.toLong() * MILLIS_PER_DAY,
            lastReviewAtMillis = atMillis,
        )
    }

    // =====================================================================
    // 各条公式（每条都标了对应的参照实现方法名）
    // =====================================================================

    /** `_initial_stability`: `S0 = w[rating-1]`，再夹到 >= 0.001 */
    private fun initialStability(rating: Int): Double =
        max(parameters[rating - 1], STABILITY_MIN)

    /**
     * `_initial_difficulty`: `D0 = w4 - e^(w5 · (rating-1)) + 1`
     *
     * [clamp]=false 只在 `_next_difficulty` 里用（那里需要**未夹取**的 D0(Easy)
     * 作为均值回归的目标）。
     */
    private fun initialDifficulty(rating: Int, clamp: Boolean): Double {
        val d = parameters[4] - Math.exp(parameters[5] * (rating - 1)) + 1.0
        return if (clamp) d.coerceIn(MIN_DIFFICULTY, MAX_DIFFICULTY) else d
    }

    /**
     * `_next_difficulty`：
     * ```
     * delta  = -(w6 · (G - 3))
     * D_arg2 = D + (10 - D) · delta / 9        ← 线性阻尼
     * D_arg1 = D0(Easy)（不夹取）
     * D'     = w7 · D_arg1 + (1 - w7) · D_arg2 ← 均值回归，防「ease hell」
     * ```
     */
    private fun nextDifficulty(difficulty: Double, rating: Int): Double {
        val arg1 = initialDifficulty(RATING_EASY, clamp = false)
        val delta = -(parameters[6] * (rating - 3))
        val arg2 = difficulty + (10.0 - difficulty) * delta / 9.0
        val d = parameters[7] * arg1 + (1 - parameters[7]) * arg2
        return d.coerceIn(MIN_DIFFICULTY, MAX_DIFFICULTY)
    }

    /**
     * 同一天内的稳定性更新（`_short_term_stability`）—— **FSRS-6 改过这里**：
     * ```
     * inc = e^(w17 · (G - 3 + w18)) · S^(-w19)     ← S^(-w19) 是 FSRS-6 新增的
     * 若 G != Again：inc = max(inc, 1.0)           ← 同日再评不会倒退
     * S'  = S · inc
     * ```
     * 与 FSRS-5 的差别就是那个 `S^(-w19)`（wiki 里写了）。
     */
    private fun shortTermStability(stability: Double, rating: Int): Double {
        var inc = Math.exp(parameters[17] * (rating - 3 + parameters[18])) *
                Math.pow(stability, -parameters[19])
        if (rating != RATING_AGAIN) inc = max(inc, 1.0)
        return max(stability * inc, STABILITY_MIN)
    }

    /**
     * 跨日复习的稳定性（`_next_stability`）：Again 走遗忘公式，其余走回忆公式。
     */
    private fun nextStability(
        difficulty: Double,
        stability: Double,
        retrievability: Double,
        rating: Int,
    ): Double {
        val s = if (rating == RATING_AGAIN) {
            forgetStability(difficulty, stability, retrievability)
        } else {
            recallStability(difficulty, stability, retrievability, rating)
        }
        return max(s, STABILITY_MIN)
    }

    /**
     * 失败后的稳定性（`_next_forget_stability`）：
     * ```
     * 长期参数 = w11 · D^(-w12) · ((S+1)^w13 - 1) · e^((1-R) · w14)
     * 短期参数 = S / e^(w17 · w18)
     * S'      = min(长期, 短期)          ← ⚠️ 官方 wiki 的公式页**没写**这个 min
     * ```
     * 那个 `min` 是上限：忘了之后稳定性不可能比原来还高。
     */
    private fun forgetStability(
        difficulty: Double,
        stability: Double,
        retrievability: Double,
    ): Double {
        val longTerm = parameters[11] *
                Math.pow(difficulty, -parameters[12]) *
                (Math.pow(stability + 1.0, parameters[13]) - 1.0) *
                Math.exp((1.0 - retrievability) * parameters[14])
        val shortTerm = stability / Math.exp(parameters[17] * parameters[18])
        return min(longTerm, shortTerm)
    }

    /**
     * 成功复习后的稳定性（`_next_recall_stability`）：
     * ```
     * S' = S · (1 + e^w8 · (11 - D) · S^(-w9) · (e^((1-R) · w10) - 1) · hardPenalty · easyBonus)
     * ```
     * `hardPenalty = w15`（仅 Hard）· `easyBonus = w16`（仅 Easy）。
     */
    private fun recallStability(
        difficulty: Double,
        stability: Double,
        retrievability: Double,
        rating: Int,
    ): Double {
        val hardPenalty = if (rating == RATING_HARD) parameters[15] else 1.0
        val easyBonus = if (rating == RATING_EASY) parameters[16] else 1.0
        return stability * (1.0 +
                Math.exp(parameters[8]) *
                (11.0 - difficulty) *
                Math.pow(stability, -parameters[9]) *
                (Math.exp((1.0 - retrievability) * parameters[10]) - 1.0) *
                hardPenalty * easyBonus)
    }

    /**
     * 由稳定性反解间隔（`_next_interval`）：
     * `I = (S / FACTOR) · (desiredRetention^(1/DECAY) - 1)`，取整并夹到 [1, 上限]。
     *
     * ⚠️ **取整用 `Math.rint` 而不是 `Math.round`。**
     *    参照实现是 Python 的 `round()`，它是**银行家舍入**（.5 进到偶数）——
     *    `round(0.5) = 0`、`round(2.5) = 2`；而 Kotlin 的 `Math.round` 是 half-up
     *    （`1`、`3`）。`Math.rint` 才是同样的行为。
     */
    private fun nextIntervalDays(stability: Double): Int {
        val raw = (stability / factor) * (Math.pow(desiredRetention, 1.0 / decay) - 1.0)
        var days = pyRound(raw)
        days = max(days, 1)
        days = min(days, maximumIntervalDays)
        return days
    }

    /**
     * `_get_fuzzed_interval`：给间隔加小幅抖动。
     * 间隔 < 2.5 天不抖（本来就短，抖了没意义还影响体验）。
     */
    private fun fuzzedInterval(intervalDays: Int, random: Random): Int {
        if (intervalDays < 2.5) return intervalDays

        var delta = 1.0
        for ((start, end, f) in FUZZ_RANGES) {
            delta += f * max(min(intervalDays.toDouble(), end) - start, 0.0)
        }
        var lo = pyRound(intervalDays - delta)
        var hi = pyRound(intervalDays + delta)
        lo = max(2, lo)
        hi = min(hi, maximumIntervalDays)
        lo = min(lo, hi)

        val picked = random.nextDouble() * (hi - lo + 1) + lo
        return min(pyRound(picked), maximumIntervalDays)
    }

    /**
     * 学习/重学步骤的推进（对照 `review_card` 里那两段 `match rating`）。
     *
     * ⚠️ **我们两个步骤数组都是空的，这个方法走不到** —— 保留它是为了与参照实现
     * 同构（将来要加「当天重复」流程时不用重写主流程）。返回
     * `(新 step, 间隔天数, 是否进入 Review)`。
     */
    private fun applyLearningSteps(
        step: Int,
        rating: Int,
        steps: IntArray,
    ): Triple<Int, Int, Boolean> {
        check(steps.isNotEmpty()) { "没有步骤时不该调用这里" }
        return when (rating) {
            RATING_AGAIN -> Triple(0, steps[0], false)
            RATING_HARD -> {
                val d = when {
                    step == 0 && steps.size == 1 -> (steps[0] * 1.5).toInt()
                    step == 0 && steps.size >= 2 -> (steps[0] + steps[1]) / 2
                    else -> steps[step.coerceAtMost(steps.size - 1)]
                }
                Triple(step, d, false)
            }
            RATING_GOOD -> {
                if (step + 1 == steps.size) Triple(step, 0, true)
                else Triple(step + 1, steps[step + 1], false)
            }
            else -> Triple(step, 0, true)   // Easy：直接毕业
        }
    }

    /** Python `round()` 的行为：四舍六入五成双（banker's rounding）。 */
    private fun pyRound(v: Double): Int = Math.rint(v).toInt()

    companion object {
        // ---- 状态与评分（值必须与 py-fsrs 的 State / Rating 一致）----
        const val PHASE_LEARNING = 1
        const val PHASE_REVIEW = 2
        const val PHASE_RELEARNING = 3

        const val RATING_AGAIN = 1
        const val RATING_HARD = 2
        const val RATING_GOOD = 3
        const val RATING_EASY = 4

        // ---- 边界（对照 scheduler.py 顶部的常量）----
        const val STABILITY_MIN = 0.001
        const val MIN_DIFFICULTY = 1.0
        const val MAX_DIFFICULTY = 10.0
        const val MILLIS_PER_DAY = 86_400_000L

        /** FSRS-6 官方默认参数（21 个），逐字来自 py-fsrs 6.3.2。 */
        val DEFAULT_PARAMETERS: DoubleArray = doubleArrayOf(
            0.212, 1.2931, 2.3065, 8.2956, 6.4133, 0.8334, 3.0194, 0.001,
            1.8722, 0.1666, 0.796, 1.4835, 0.0614, 0.2629, 1.6483, 0.6014,
            1.8729, 0.5425, 0.0912, 0.0658, 0.1542,
        )

        /** 抖动区间：(起点天数, 终点天数, 抖动系数)。对照 `FUZZ_RANGES`。 */
        private val FUZZ_RANGES: Array<Triple<Double, Double, Double>> = arrayOf(
            Triple(2.5, 7.0, 0.15),
            Triple(7.0, 20.0, 0.10),
            Triple(20.0, Double.POSITIVE_INFINITY, 0.05),
        )

        // ⚠️ 学习步骤「空」是**有意的**，理由写在类注释第 1 条。
        //    如果哪天真要开「当天重复」，改这里，**同时**要改 gen-fsrs-vectors.py
        //    里的 SCHED 配置并重新生成向量 —— 否则单测比对的就是另一套配置了。
        private val learningSteps: IntArray = IntArray(0)
        private val relearningSteps: IntArray = IntArray(0)
    }
}
