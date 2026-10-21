package com.example.englishcommute.ui

import com.example.englishcommute.update.UpdateState

/**
 * 底栏页签右上角的角标。
 *
 * ## 为什么是一个显式类型，而不是 `String?`
 *
 * 3.0.5 做生词角标时，`badges` 是 `List<String?>` —— 因为那时只有一种角标：
 * 一个数字。而**圆点和数字不是同一个东西的两种写法**：
 *
 * | | 回答的问题 | 需要几个字 |
 * |---|---|---|
 * | [Count] | 「有**几个**」 | 至少一个（「3」「99+」） |
 * | [Dot] | 「**有没有**」 | 一个都不需要 |
 *
 * 把圆点硬塞进字符串只有两条路，两条都错：
 *   · `" "`（一个空格）—— 渲染出一个空心的胶囊，看起来像渲染坏了；
 *   · `"•"`（一个圆点字符）—— 依赖那台设备的字体里有这个字形，
 *     而且它和外层 `Amber` 胶囊叠起来会得到一个「点里有点」的怪东西。
 *
 * 用类型表达之后，渲染端 `when (badge)` 是**穷尽**的：以后再加一种形态
 * （比如一个感叹号）编译器会把所有需要处理它的地方标出来。
 *
 * ## 圆点为什么不做成「数字 1」
 *
 * 「有新版本」这件事只有一个，写「1」在语义上是错的（用户会去找那「另外的」）。
 * 而且数字角标会让人以为「点掉就少一个」—— 它不是计数，是一个**待办**。
 */
sealed interface TabBadge {

    /**
     * 数字角标。用 [com.example.englishcommute.word.DueBadge.text] 生成 ——
     * 「0 不显示」「99 封顶」两条界面语义都在那里，别在调用点再判一次。
     */
    data class Count(val text: String) : TabBadge

    /** 圆点：只说「这里有东西」，不说是几个 */
    data object Dot : TabBadge
}

/**
 * 「设置」页签该不该挂圆点 —— 由更新状态决定。
 *
 * ## 为什么是一个纯函数
 *
 * 它是这一版里唯一需要**想清楚**的判断（下面那张表），而它又想错了不会报错：
 * 漏一种状态，用户就永远看不到那个提示，而界面上一切正常。
 * 放在这里就能被 JVM 单测逐态点名，不需要真机、也不需要一条更新可用。
 *
 * ## 判据是「这个页面上有没有活要干」，不是「服务器上有没有新版本」
 *
 * | 状态 | 圆点 | 为什么 |
 * |---|---|---|
 * | [UpdateState.Unknown] | 无 | 还没查到。**不能因为「不知道」就报警** —— 断网时它恒为这个值 |
 * | [UpdateState.UpToDate] | 无 | 服务器上和本机一样新 |
 * | [UpdateState.Available] | **有** | 有新版本可下 |
 * | [UpdateState.Downloading] | **有** | 正在下 —— 中途离开 App 再回来，这是唯一的痕迹 |
 * | [UpdateState.Ready] | **有** | 已经下好了，**就差用户点一下安装** |
 * | [UpdateState.Failed] | **有** | 下失败了。用户很可能不知道（他是点完就走了的），没有圆点就永远不知道 |
 *
 * ⚠️ 最后两行是这张表里唯一需要想一下的地方，也是「有新版本才显示」这个
 *    直觉写法会漏掉的两态：
 *
 *      `Ready` 时服务器上**有**新版本 —— 但用户已经下完了，
 *      他需要的不再是「知道有新版」，而是「知道该点安装了」。
 *      `Failed` 时也是同样的处境。两者都属于「**这件事没做完**」。
 *
 *    换句话说，圆点的语义是「**这个页面上有未完成的事**」，
 *    而不是「服务器上有新版本」。前者把后者包含在内，而多出来的那两态
 *    恰恰是用户最需要被提醒的时候（他已经在做了，只差最后一步）。
 *
 * ⚠️ 这里不写 `else ->`：新加一种 [UpdateState] 时，编译器会直接报错，
 *    逼着做一次上面的判断。写了 `else` 的话它会静默落进某一边。
 */
fun updateBadge(state: UpdateState): TabBadge? = when (state) {
    is UpdateState.Unknown,
    is UpdateState.UpToDate -> null

    is UpdateState.Available,
    is UpdateState.Downloading,
    is UpdateState.Ready,
    is UpdateState.Failed -> TabBadge.Dot
}
