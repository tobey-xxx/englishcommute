package com.example.englishcommute.settings

import kotlin.math.roundToInt

/**
 * 播放倍速的取值域与换算。**纯 Kotlin，不依赖任何 Android API。**
 *
 * 抽成独立对象的理由和 [com.example.englishcommute.content.ContentPolicy] 一样：
 * 这里的规则全是边界数学（对齐、夹取、格式化、精听降速），
 * 而它们出错时**界面上看不出异常** —— 只是某一格调不到、某个数字显示成
 * `1.3500001x`、或者精听反而比正常播放更快。这类 bug 在真机上极难倒查，
 * 放到桌面用 Python 1:1 复刻跑一遍边界是最划算的（见 `tools/verify-speed.py`）。
 *
 * ## 为什么真相是「整数百分数」而不是 Float
 *
 * 精度要求到 0.05 之后，浮点数就开始咬人了：
 *
 * ```
 * 0.5f + 0.05f * 17   →  1.3500001f      // 直接显示就是 "1.3500001x"
 * 0.75f - 0.05f       →  0.70000005f     // 和 0.7f 做 == 比较会漏
 * ```
 *
 * 更要命的是滑杆：`valueRange` 是浮点区间，系统回传的值可能是 `54.999996`
 * 而不是 `55` —— 直接 `roundToInt()` 到 54，再对齐就落到 50，
 * 于是「拖到 0.55 停住，松手跳回 0.5」。
 *
 * 所以内部一律用**整百分数**（[Int]，[PERCENT_MIN]..[PERCENT_MAX]，5 的倍数）
 * 传递、比较、保存。Float 只出现在两个边界上：
 *   - 交给 ExoPlayer 的 [androidx.media3.common.Player.setPlaybackSpeed]
 *   - 画滑杆位置（Compose 的 Slider 只认 Float）
 *
 * 这样 0.05 的步子永远精确，`==` 也能安全使用。
 */
object SpeedPolicy {

    /** 最慢 0.50x。再慢会失真到听不出原音（连读、弱读全糊在一起） */
    const val PERCENT_MIN = 50

    /** 最快 2.00x。再往上新闻播报就完全无法跟读了 */
    const val PERCENT_MAX = 200

    /** 0.05x 一格 */
    const val PERCENT_STEP = 5

    /** 常速。也是 UI 上「一键恢复」的目标值 */
    const val PERCENT_DEFAULT = 100

    /** 精听用的慢速档：0.70x —— 听清连读的最低损失档 */
    const val PERCENT_SLOW = 70

    /** 档位总数：0.50 / 0.55 / … / 2.00，共 31 档 */
    const val STEP_COUNT = (PERCENT_MAX - PERCENT_MIN) / PERCENT_STEP + 1

    /**
     * Compose `Slider` 的 `steps` 参数。
     *
     * ⚠️ **语义是「中间插入的离散点数量」，不是档位数。**
     *    31 个取值点之间只有 30 段，两端各一个点不需要"插入"，
     *    所以 `steps = 31 - 2 = 29`。写成 `STEP_COUNT - 1` 会得到 30，
     *    滑杆会多出一格，且最后一格永远取不到 2.00x。
     */
    const val SLIDER_STEPS = STEP_COUNT - 2

    /**
     * 夹取到合法区间，并对齐到最近的步长。
     *
     * 对齐用「加半步再整除」而不是向下取整：这样 77 落到 75、
     * 78 落到 80，用户拖到哪里就停在哪一格的**最近处**，
     * 而不是永远往小的那边吸。
     *
     * `PERCENT_STEP / 2` 是整数除法（5 / 2 = 2）。对本常量取值正确；
     * 若将来把步长改成偶数需一并复核。
     */
    fun normalizePercent(percent: Int): Int {
        val clamped = percent.coerceIn(PERCENT_MIN, PERCENT_MAX)
        val offset = clamped - PERCENT_MIN
        val aligned = ((offset + PERCENT_STEP / 2) / PERCENT_STEP) * PERCENT_STEP
        return (PERCENT_MIN + aligned).coerceIn(PERCENT_MIN, PERCENT_MAX)
    }

    /**
     * 任意 Float → 合法百分数。
     *
     * 用途有两个：读老版本存下的设置（那时只可能是 0.5/0.75/1.0/… 这几个固定档，
     * 归一化后原样保留），以及同步播放器的真实倍速（精听时它是 0.7f）。
     *
     * 非有限值一律回落到常速 —— `NaN * 100` 取整会得到 0，比不动更糟。
     */
    fun percentOf(speed: Float): Int =
        if (speed.isFinite()) normalizePercent((speed * 100f).roundToInt()) else PERCENT_DEFAULT

    /** 合法百分数 → 交给 ExoPlayer 的 Float */
    fun speedOf(percent: Int): Float = normalizePercent(percent) / 100f

    /** 上/下一档。到边界就停住，**不循环** —— 从 2.0x 再按「+」绕回 0.5x 是惊悚体验 */
    fun step(percent: Int, delta: Int): Int =
        normalizePercent(normalizePercent(percent) + delta * PERCENT_STEP)

    fun isDefault(percent: Int): Boolean = normalizePercent(percent) == PERCENT_DEFAULT

    /** 已经是最慢/最快档，用于把 ± 按钮置灰 */
    fun isAtMin(percent: Int): Boolean = normalizePercent(percent) == PERCENT_MIN

    fun isAtMax(percent: Int): Boolean = normalizePercent(percent) == PERCENT_MAX

    /**
     * 精听（慢速重听本句）该用的倍速。
     *
     * ## 为什么不能直接返回 [PERCENT_SLOW]
     *
     * 原先精听硬编码 0.7x。用户如果把正常倍速调到 0.5x，点「重听本句」
     * 反而**变快了** —— 而按钮叫「慢速重听」，这是自相矛盾的。
     *
     * 规则改成「取用户设定与 0.7x 中更慢的那个」：
     *   - 用户在 1.5x 听力 → 精听降到 0.7x（跨度大，但"听不懂就慢下来"正是意图）
     *   - 用户在 0.5x 精听全文 → 精听仍是 0.5x（绝不加快）
     */
    fun slowPercentFor(userPercent: Int): Int =
        minOf(normalizePercent(userPercent), PERCENT_SLOW)

    /**
     * `0.5x` / `0.75x` / `1.0x` / `1.05x` / `2.0x`。
     *
     * 整十的百分数显示一位小数（1.0x 而不是 1.00x），带五的显示两位
     * （1.05x 而不是 1.5x —— 后者是另一个档位，会撞车）。
     *
     * 整档也**必须带小数位**：否则 `1x` 和最慢档 `0.5x` 并排时
     * 看起来像两种不同的东西。
     */
    fun format(percent: Int): String {
        val p = normalizePercent(percent)
        val whole = p / 100
        val frac = p % 100
        return if (frac % 10 == 0) {
            "$whole.${frac / 10}x"
        } else {
            // 必须补零：frac = 5 的档位（1.05x）不补零会显示成 "1.5x"，
            // 而 1.50x 是真实存在的另一个档位 —— 两个档位显示成同一个字符串
            "$whole.${frac.toString().padStart(2, '0')}x"
        }
    }
}
