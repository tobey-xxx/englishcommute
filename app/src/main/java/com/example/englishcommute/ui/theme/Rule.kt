package com.example.englishcommute.ui.theme

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 1px 分割线，可带进度。
 *
 * `progress` 为 null 时是普通分割线；非 null 时**同一条线**变成进度条 ——
 * 这是这套视觉里最省的一笔：进度不需要额外的轨道和填充色差，
 * 底下的暗线本来就在那儿。
 *
 * 放在 theme 包里而不是各自私有：素材库的条目之间、设置页的区块之间、
 * 播放页的进度条都用它。三处各写一份的话，「分割线该多亮」这个决定
 * 就会有三个版本，而它恰好是整套视觉里最敏感的一个值（见 [Hairline]）。
 * 日间模式下更要注意 —— 浅底上线的深浅是唯一的分组线索，一旦不一致，
 * 整页会立刻显出拼接感。
 */
@Composable
fun Rule(
    progress: Float? = null,
    height: Dp = 1.dp,
    color: Color = Amber,
    /**
     * 是否给进度做补间。默认开。
     *
     * 只有一种情况该关掉：进度**本身就是连续的**（比如播放位置每 250 ms
     * 更新一次）。那种场合再叠一层动画，会让进度条永远滞后于真实位置 ——
     * 用户拖动进度条时尤其明显，松手后线还在慢慢爬。
     */
    animated: Boolean = true,
) {
    // 1.2.0：进度改为补间。
    //
    // 改之前 `fillMaxWidth(progress)` 直接跟着数值跳。而这些进度的来源
    // 大多是**回调间隔很粗**的后台任务：转写按块回调（一块 60–120 秒）、
    // 下载按缓冲区回调、部署按文件回调。于是进度条的实际表现是
    // 「不动 → 猛跳一格 → 又不动」，看起来像卡住了而不是在推进。
    //
    // 补间之后，同样的回调频率看到的是一条持续在走的线。
    val target = (progress ?: 0f).coerceIn(0f, 1f)
    val shown by animateFloatAsState(
        targetValue = target,
        animationSpec = Motion.standard(),
        label = "ruleProgress",
    )
    val fraction = if (animated) shown else target

    Box(
        Modifier
            .fillMaxWidth()
            .height(height)
            .background(Hairline)
    ) {
        if (progress != null) {
            Box(
                Modifier
                    .fillMaxWidth(fraction)
                    .height(height)
                    .background(color)
            )
        }
    }
}
