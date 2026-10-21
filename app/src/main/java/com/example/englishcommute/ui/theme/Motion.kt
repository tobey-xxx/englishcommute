package com.example.englishcommute.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.IntOffset

/**
 * 动效规范。**这是全应用唯一的时长与曲线来源。**
 *
 * ## 为什么需要这个文件（1.2.0 新增）
 *
 * 在此之前，全工程**只有一处真正的属性动画**：打卡庆祝页的烟花。
 * 其余所有状态变化 —— 三个覆盖层的进出、进度条、列表增删、选中态、
 * 空态与内容的切换 —— 全是瞬变。
 *
 * 界面因此有一种特别的生硬感：每一帧都正确，但两帧之间什么都没发生。
 *
 * ## 三档时长，不要第四档
 *
 * 档位一多就会出现「这里用 180 还是 200」这种无意义的选择，
 * 而不一致的时长恰恰是界面显得廉价的原因之一。
 *
 * | 档 | 时长 | 用在哪 |
 * |---|---|---|
 * | [Quick] | 120 ms | 颜色、透明度、小图标切换、**按下反馈** —— 只是「确认你点到了」 |
 * | [Standard] | 220 ms | 绝大多数：进度条、布局变化、选中态、列表增删 |
 * | [Large] | 320 ms | 整屏覆盖层进出 —— 位移距离大，太快会显得突兀 |
 *
 * ## ⚠️ 1.2.8：两条曲线的实现与注释是反的（已修）
 *
 * 曲线对不对不能靠看，要**算起手斜率 `y₁/x₁`**（`CubicBezierEasing(p1x, p1y, p2x, p2y)`）。
 * 算完才发现三条里有两条名不副实，而它们的注释恰好写反了：
 *
 * | 曲线 | 修前 | 起手斜率 | 实际是 | 修后 |
 * |---|---|---|---|---|
 * | [EasingStandard] | `(0.2, 0, 0, 1)` | **0** | 两端都慢的 ease-in-out | `(0.32, 0.72, 0, 1)` → 斜率 2.25 |
 * | [EasingEnter] | `(0.05, 0.7, 0.1, 1)` | 14.0 | 强 ease-out ✓ 一直是对的 | 不动 |
 * | [EasingExit] | `(0.3, 0, 0.8, 0.15)` | **0** | 纯 ease-in（慢起快收） | `(0.2, 0.85, 0.3, 1)` → 斜率 4.25 |
 *
 * 后果不是审美问题：[EasingStandard] 是全工程用得最多的一条（59 个可点元素、
 * 绝大多数状态变化都走它），起手斜率 0 意味着**每一次交互的前 40 ms 几乎看不到动**。
 * 这正是 Apple《Designing Fluid Interfaces》第一条要说的：延迟一出现，
 * 直接感就掉下悬崖。
 *
 * **判据**：`tools/verify-motion-128.py` 会把三条曲线的斜率算一遍并断言区间，
 * 改这里之前先看那个脚本 —— 「看起来差不多」判断不了这件事。
 *
 * ## 减少动效（Apple 那三个信号的第一个）
 *
 * [reduceMotion] 由 `AndroidMotionSetting` 在 Activity resume 时写入
 * （读 `ValueAnimator.areAnimatorsEnabled()`，也就是系统「移除动画」开关）。
 *
 * 口径是**减弱，不是清零**：位移与缩放去掉，透明度与颜色的变化保留。
 * 所以这里只做三件事：
 *  - [overlayEnter] / [overlayExit] 的**位移**换成纯淡入淡出
 *  - [resize] / [flick] 这类弹簧换成 `snap`
 *  - 按下反馈的缩放交给调用方判断（见 `Modifier.pressFeedback`）
 *
 * 时长本身不缩短 —— 一个 220 ms 的淡入不会引起不适，而把它压到 0
 * 反而会让「点了没反应」和「没点」看起来一样。
 *
 * ## ⚠️ 1.2.9：这两个开关为什么从 `@Volatile` 改成 Compose 状态
 *
 * 1.2.8 用的是 `@Volatile var`，注释写的理由是「它的变化频率是用户去系统设置里
 * 改一次，不值得为它铺一条状态流；而界面在 Activity 恢复时会重组一遍」。
 *
 * **后半句是错的**：`onStart` 里改写这个变量**不会**触发任何重组 ——
 * Activity 从后台回来时，Compose 树本来就在，不会因为 `onStart` 自动重跑。
 * 也就是说 1.2.8 的「减少动效」要等到下一次**因为别的原因**重组才生效，
 * 生效时机是随机的。
 *
 * 改成 `mutableStateOf` 之后它天然正确：写值 → 读到它的组合重跑。
 * 代价是这一个 object 多了一份快照状态登记，可以忽略。
 */
object Motion {

    /**
     * 系统的「移除动画」开关。
     *
     * 由 [com.example.englishcommute.ui.AndroidMotionSetting.refresh] 在界面
     * ON_START 时写入，见上面那段说明。
     *
     * setter 是 `internal`：这个值只有那一个地方有权改 ——
     * 公开可写会让人在别处顺手改它来「临时关掉动画」。
     */
    var reduceMotion: Boolean by mutableStateOf(false)
        internal set

    /**
     * 系统的「高对比文字」开关（Apple §14 的第二个信号）。
     *
     * 和 [reduceMotion] 是**两件事**，各自有对应做法：那个管「动不动」，
     * 这个管「看不看得清」。落地在配色上（见 `AppPalette.contrastBoost`）：
     * 最弱的两个灰阶提亮、描边加重。
     */
    var highContrast: Boolean by mutableStateOf(false)
        internal set

    // ------------------------------------------------------------------
    // 时长
    // ------------------------------------------------------------------

    /** 只是「确认你点到了」：颜色、透明度、小图标、按下反馈 */
    const val Quick = 120

    /** 绝大多数场合 */
    const val Standard = 220

    /** 整屏覆盖层进出 */
    const val Large = 320

    // ------------------------------------------------------------------
    // 曲线
    // ------------------------------------------------------------------

    /**
     * 标准曲线。**真正的「快出慢入」**（起手斜率 2.25、收尾斜率 0）。
     *
     * 1.2.8 之前是 `(0.2, 0, 0, 1)` —— 那是一条两端都慢的 ease-in-out，
     * 起手斜率 0，与「起步偏快让界面显得跟手」这句注释正好相反。
     * 现在这条的形状就是注释描述的样子，也是 Apple `ease-drawer` 的取值。
     */
    val EasingStandard: Easing = CubicBezierEasing(0.32f, 0.72f, 0f, 1f)

    /**
     * 入场曲线。比标准曲线收得更慢 —— 新东西进来时多给一点减速，
     * 眼睛才追得上它最终停在哪。
     *
     * ⚠️ 这条**本来就是对的**（起手斜率 14），1.2.8 没动它。
     * 把它留在这里是因为它给了一组有用的参照：强 ease-out 的手感长什么样。
     */
    val EasingEnter: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

    /**
     * 退场曲线。**比标准曲线更果断**（起手斜率 4.25 vs 2.25）。
     *
     * 1.2.8 之前是 `(0.3, 0, 0.8, 0.15)` —— 纯 ease-in，也就是**慢起**。
     * 退场是用户刚点下某个东西触发的，慢起等于「点完先愣一下再走」，
     * 与这句注释「前段就快」正好相反。
     *
     * 为什么比 [EasingStandard] 还陡：要走的东西不值得占用注意力，
     * 前 20 ms 就要动起来；收尾慢一点无所谓（那时它已经快淡没了）。
     */
    val EasingExit: Easing = CubicBezierEasing(0.2f, 0.85f, 0.3f, 1f)

    // ------------------------------------------------------------------
    // 现成的 spec
    // ------------------------------------------------------------------

    fun <T> quick(): FiniteAnimationSpec<T> =
        tween(durationMillis = Quick, easing = EasingStandard)

    fun <T> standard(): FiniteAnimationSpec<T> =
        tween(durationMillis = Standard, easing = EasingStandard)

    fun <T> enter(): FiniteAnimationSpec<T> =
        tween(durationMillis = Large, easing = EasingEnter)

    fun <T> exit(): FiniteAnimationSpec<T> =
        tween(durationMillis = Standard, easing = EasingExit)

    /**
     * 布局尺寸变化（`animateContentSize`）专用。
     *
     * 用 spring 而不是 tween：布局变化的幅度是不确定的（一个状态块
     * 可能长出 20dp 也可能长出 200dp），而 tween 的固定时长会让
     * 大幅变化显得拖沓、小幅变化显得抽搐。spring 按距离自适应。
     *
     * `DampingRatioNoBouncy` —— 尺寸变化不带惯性，不该回弹。
     */
    fun <T> resize(): FiniteAnimationSpec<T> =
        if (reduceMotion) snap() else spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow,
        )

    /**
     * 明暗主题之间那段配色过渡（1.2.9，§14「明暗切换要缓变」）。
     *
     * ## 为什么它**不**受 [reduceMotion] 影响
     *
     * §14 的口径是「减弱，不是清零」：要去掉的是**位移与缩放**这类会让人不适的
     * 运动，而颜色过渡恰恰相反 —— 开着「移除动画」的用户如果看到整屏颜色
     * **瞬间**翻转，那一下反而更刺眼。所以这里和透明度一样，属于「保留」的那一类。
     *
     * ## 为什么是 [Standard] 而不是 [Quick]
     *
     * 它变的是**整棵树的颜色**，不是某个小控件的状态。整屏一起变的时候，
     * 120 ms 会有「闪一下」的感觉；220 ms 才读得出「在过渡」。
     *
     * 曲线仍用 [EasingStandard]（起手斜率 2.25）：前 30 ms 就能看出在变。
     */
    fun themeMix(): FiniteAnimationSpec<Float> =
        tween(durationMillis = Standard, easing = EasingStandard)

    /**
     * **带回弹的释放**（Apple 第 4 节）。全工程只有一处调用：播放页下拉关闭。
     *
     * ## 为什么必须与 [resize] 分开
     *
     * Apple 第 4 节的原话是「卡片**被你甩出去**的时候回弹是对的，菜单淡入时回弹是错的」。
     * 决定该不该回弹的从来不是「这是个动画」，而是**这个动作里有没有用户给的动量**：
     *
     * - [resize] 的输入是「内容换了」—— 没有动量，用 `DampingRatioNoBouncy`
     * - [flick] 的输入是「手指以某个速度甩开了」—— 有动量，欠阻尼才对
     *
     * 所以回弹**不是装饰**：它承接的是手指离开那一刻的速度，负责把这个速度表达完。
     * 凭空出现的过冲才是装饰，而那种是错的。
     *
     * ## 阻尼比为什么是 0.8
     *
     * 大约「过冲一次、看得见但不过分」。再低（0.6 以下）会来回弹两三下，
     * 在整屏位移的尺度上就是「页面在屏幕外抖动」；再高（0.95 以上）等于没有。
     *
     * ## ⚠️ 1.2.8 有意留空，1.2.9 才补上
     *
     * 1.2.8 的注释写着「不加没有调用点的函数，否则下一个人会以为这里已经处理过了」——
     * 那条纪律是对的，所以当时留了空。这一轮下拉关闭落地，它才有了调用点。
     * （那条纪律的来历是一次真实事故：`transcribeManually` 的注释写着「允许越限」，
     * 全工程没有调用点，于是界面上写着「转写（较慢）」、点下去什么都不发生。）
     */
    fun <T> flick(): FiniteAnimationSpec<T> =
        if (reduceMotion) snap() else spring(
            dampingRatio = 0.8f,
            stiffness = Spring.StiffnessMediumLow,
        )

    // ⚠️ 这里以前还有一个 `settle()`（拖动松手后的归位弹簧）。
    //    1.2.8 清掉了：它从 1.2.0 建这个文件起就没有任何调用点。
    //    现在「拖动松手后归位」走 [flick] —— 两者本来就是同一条弹簧的两个名字，
    //    所以没有让它回来：一个场景留一个名字，不要再长出第二个同义词。

    // ------------------------------------------------------------------
    // 覆盖层转场
    // ------------------------------------------------------------------

    /**
     * 整屏覆盖层的进入：**从下往上推**，同时淡入。
     *
     * 为什么是从下往上：播放页、诊断页、庆祝页都是从当前页面「叠上来」的
     * 东西，不是「旁边的一页」。上推是 Android 上「模态层」的通用语汇，
     * 用户不需要学。
     *
     * 只推 1/8 屏高而不是整屏：整屏位移会让人觉得它「飞了很远」，
     * 而它其实一直就在这一层。小位移 + 淡入的组合足以表达「它来了」。
     *
     * ## 为什么是 `val ... get()` 而不是普通常量
     *
     * 开了「移除动画」之后，位移要去掉、淡入保留（减弱不清零）。
     * 1.2.9 起 [reduceMotion] 是 Compose 状态，所以这里的 `get()` 在组合中
     * 读到它时**会自动订阅** —— 用户在系统设置里改完切回来，这一处会跟着变。
     * （1.2.8 用的是 `@Volatile`，那次改动不会触发重组，见类注释。）
     */
    val overlayEnter: EnterTransition
        get() = if (reduceMotion) {
            fadeIn(animationSpec = enter())
        } else {
            slideInVertically(animationSpec = enter()) { full -> full / 8 } +
                fadeIn(animationSpec = enter())
        }

    /**
     * 整屏覆盖层的退出：原路下沉 + 淡出。
     *
     * 退出比进入短（[Standard] vs [Large]）—— 要走的东西不该让人等。
     * 同样地，「移除动画」时只留淡出。
     */
    val overlayExit: ExitTransition
        get() = if (reduceMotion) {
            fadeOut(animationSpec = exit())
        } else {
            slideOutVertically(animationSpec = exit()) { full -> full / 8 } +
                fadeOut(animationSpec = exit())
        }

    /**
     * **形变开启时**的覆盖层进出：只淡入淡出，不做整页位移。
     *
     * ## 为什么必须分开（1.2.10 修，一个真机确认的卡顿）
     *
     * 用户的原话是「那个图片从下移动到最少的时候，明显的掉帧卡顿」。
     * 根因是**两套位移同时作用在同一屏上**：
     *
     * | 谁在动 | 用什么 | 代价 |
     * |---|---|---|
     * | 整页推入（`slideInVertically`） | **布局层**的位移 | 每帧重新布局整棵子树（含 8–12 行字幕） |
     * | 封面形变（`sharedElement`） | 绘制层的 bounds 补间 | overlay 每帧在**正在移动的父层**里重算位置 |
     *
     * 两者叠起来不只是慢，观感也不对：封面会「斜着飞」——
     * 因为它自己的轨迹叠在整页的轨迹上。
     *
     * ## 为什么去掉位移是对的，不是妥协
     *
     * §7「空间连续性」要的是**同一个东西从它的来源长过来**，而不是「整页从下面推上来」。
     * 有了共享元素，那条空间线索已经由封面本身提供了；再加一层整页位移是重复表达，
     * 而且和第二套位移打架。
     *
     * （关掉 `SHARED_MORPH` 时会退回带位移的 [overlayEnter] —— 那时没有共享元素，
     * 推入就是唯一能表达「它从下面叠上来」的手段。）
     *
     * ⚠️ 这两个**不受 [reduceMotion] 影响**：它们本来就只有淡入淡出，
     * 而淡入淡出是「减弱」之后**要保留**的那一类。
     */
    val overlayEnterMorph: EnterTransition get() = fadeIn(animationSpec = enter())

    val overlayExitMorph: ExitTransition get() = fadeOut(animationSpec = exit())

    /**
     * 区块级的出现/消失（状态块、提示条、可展开的详情）。
     *
     * 不做位移，只淡入淡出 —— 这类东西是**原地**长出来的，
     * 让它从别处飞进来反而会让人去追它的来路。
     * 也正因为没有位移，「移除动画」时它不受影响。
     */
    val blockEnter: EnterTransition get() = fadeIn(animationSpec = standard())
    val blockExit: ExitTransition get() = fadeOut(animationSpec = quick())

    /** 给需要手写 IntOffset 动画的地方用 */
    fun offsetSpec(): FiniteAnimationSpec<IntOffset> = standard()
}
