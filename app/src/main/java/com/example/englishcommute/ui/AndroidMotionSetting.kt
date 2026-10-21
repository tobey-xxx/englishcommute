package com.example.englishcommute.ui

import android.animation.ValueAnimator
import android.content.Context
import android.view.accessibility.AccessibilityManager
import com.example.englishcommute.diag.DiagLog
import com.example.englishcommute.ui.theme.Motion

/**
 * 两个系统级无障碍开关 → [Motion]（Apple §14）。
 *
 * ## 为什么是**两个**，而不是一个「无障碍模式」
 *
 * §14 说的是「三个独立信号，各自有对应做法」。落到 Android 上能被 App 读到的
 * 主要有两个，它们的**做法完全不同**，所以不能合并成一个开关：
 *
 * | 信号 | 读什么 | 做什么 |
 * |---|---|---|
 * | 移除动画 | `ValueAnimator.areAnimatorsEnabled()` | **去掉位移与缩放**，保留淡入淡出 |
 * | 高对比文字 | `AccessibilityManager.isHighContrastTextEnabled()` | **改配色**：最弱灰阶提亮、描边加重 |
 *
 * 合并的后果很具体：开了「移除动画」的用户不会因此需要更粗的描边，
 * 而开了「高对比」的用户也不希望界面变成瞬变。
 *
 * ## ⚠️ 高对比为什么不走 `UiModeManager.getContrast()`
 *
 * Android 14 起官方把「高对比文字」的读取指向 `UiModeManager.getContrast()`
 * （`isHighContrastTextEnabled` 被弃用），但实测那份 API 与本工程不合：
 *
 * · 它返回的是 **float**（0.0–1.0 的连续量），**没有** `CONTRAST_HIGH`
 *   这类档位常量可比较 —— 判据只能自己编一个阈值，而阈值是多少没有依据
 * · 它要求 API 34，而本工程 minSdk 是 26，得写版本分支
 * · `isHighContrastTextEnabled()` 在 API 36 的 `android.jar` 里**依然存在**
 *   且是正式 API（还配了 `addHighContrastTextStateChangeListener`），
 *   也就是说它读的仍然是同一个用户设置
 *
 * 所以这里直接用它：一个布尔、无版本分支。**判据的可靠性比 API 的新旧重要。**
 *
 * ## 为什么要传入 Context、而且由界面调
 *
 * 这两个值都可以在 App 运行期间被改（用户切出去改设置再切回来），
 * 所以不能在 `Motion` 的初始化里读一次就算完。现在由界面的 ON_START 触发
 * （见 `MainActivity` 里的 `LifecycleEventEffect`），正好覆盖「切回来」这个时机。
 *
 * ## 失败了按「正常」处理
 *
 * 两个都读不到就当没开 —— 这些开关是**为了照顾一部分用户**而存在的，
 * 读失败时宁可多给一点动效与余量，也不要因为一个次要开关把界面变成瞬变或糊掉。
 */
object AndroidMotionSetting {

    fun refresh(context: Context) {
        val reduced = runCatching { !ValueAnimator.areAnimatorsEnabled() }.getOrDefault(false)
        val contrast = runCatching { readHighContrast(context) }.getOrDefault(false)

        if (reduced != Motion.reduceMotion) {
            Motion.reduceMotion = reduced
            DiagLog.i(
                "UI",
                if (reduced) "系统要求减少动效 → 去掉位移与缩放，保留淡入淡出"
                else "系统允许动效 → 恢复位移与缩放",
            )
        }
        if (contrast != Motion.highContrast) {
            Motion.highContrast = contrast
            DiagLog.i(
                "UI",
                if (contrast) "系统要求高对比文字 → 提亮最弱灰阶、加重描边"
                else "系统未要求高对比 → 恢复常规配色",
            )
        }
    }

    /**
     * 高对比文字。
     *
     * ⚠️ 方法名是 `isHighContrast**Text**Enabled`，不是 `isHigh**TextContrast**Enabled` ——
     * 这两个词序在直觉上完全对等，写反了编译器只会说「找不到引用」，
     * 而它的表现是「这个开关永远读不到」（不报错、只是永远 false）。
     * 判据在这里是唯一的：改它之前先 `javap` 一遍 `android.jar` 看真名。
     */
    private fun readHighContrast(context: Context): Boolean =
        (context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager)
            ?.isHighContrastTextEnabled
            ?: false
}
