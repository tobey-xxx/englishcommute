package com.example.englishcommute.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.englishcommute.checkin.DayKey
import com.example.englishcommute.ui.theme.Amber
import com.example.englishcommute.ui.theme.AppText
import com.example.englishcommute.ui.theme.Bg
import com.example.englishcommute.ui.theme.BgRaised
import com.example.englishcommute.ui.theme.Crimson
import com.example.englishcommute.ui.theme.Glyph
import com.example.englishcommute.ui.theme.GlyphIcon
import com.example.englishcommute.ui.theme.InkDim
import com.example.englishcommute.ui.theme.InkMid
import com.example.englishcommute.ui.theme.Olive
import com.example.englishcommute.ui.theme.Space
import com.example.englishcommute.ui.theme.pressFeedback
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.delay

/**
 * 打卡成功庆祝页 —— 跨过每日目标那一秒自动弹出来。
 *
 * ## 三个必须有的东西，缺一个这个页面就不成立
 *
 * 1. **恭喜**。它是这个页面存在的唯一理由。用刊头那支衬线大字，
 *    整个 App 只有两处用得到它（另一个是「今日素材」），所以这里不需要
 *    额外的装饰去「显得隆重」—— 字号本身已经拉开了。
 * 2. **海报**。用户真正想发出去的是那张图，所以它必须在这里可见 ——
 *    只给一个「分享」按钮，等于让他在没看过图的情况下把它发给朋友。
 * 3. **一个关掉的入口**。打卡是天天发生的事，总有不想分享的时候。
 *    ⚠️ 这个叉不是「取消」的意思，而是「知道了」。少了它，
 *    每天达标都会被一个必须分享的动作拦住，那就变成了骚扰。
 *
 * ## 为什么是全屏覆盖层，不是 Material 的 Dialog
 *
 * 和播放页、诊断页同一套做法，理由有两个：
 *
 *  - `Dialog` 是**独立的 Window**，默认不会铺到状态栏下面。想让它铺满，
 *    要么处理 `WindowInsets`，要么给窗口设 flags；而放在 Activity 的
 *    组合树里就天然是 edge-to-edge 的，`statusBarsPadding()` 一加就对 ——
 *    与其它覆盖层完全一致，没有第二套写法。
 *  - 它要盖在播放页**之上**。播放页本身就是覆盖层，覆盖层里再开一个
 *    独立窗口，层级关系会变成「窗口 vs 组合树」，很难推理。
 *
 * 系统返回键由 [BackHandler] 接住，行为和点叉一致。
 *
 * ## 烟花为什么是「编辑器配色」而不是五彩
 *
 * 只取三个强调色（琥珀 / 橄榄 / 绯红）。这套视觉从头到尾只有这几个颜色，
 * 突然撒一把青紫粉，这个页面就会像从别的 App 里借来的。
 * 而且三色在明暗两套主题下都已经配平过对比度，不用再另调一套。
 */
@Composable
fun CheckInCelebrationDialog(
    poster: android.graphics.Bitmap?,
    busy: Boolean,
    streak: Int,
    seconds: Int,
    onShare: () -> Unit,
    onDismiss: () -> Unit,
) {
    BackHandler { onDismiss() }

    // 数字取「弹出来那一刻」的值并记住 —— 用户盯着看的时候秒数还在涨，
    // 让它跟着走会让「今天听了 40 分钟」这句话失去确定性
    val stats = remember { streak to seconds }

    // 达标这一刻是一天里唯一值得用触觉标记的时刻（Apple 第 13 节：
    // 触觉只在**有意义的那一刻**给，且必须与视觉同帧）。
    // 全应用只有三处触觉：达标、分享、删除 —— 其余一律不给，
    // 因为「每个点击都震一下」会训练用户把所有反馈都忽略掉
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(Unit) { haptic.performHapticFeedback(HapticFeedbackType.LongPress) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Bg)
    ) {
        FireworksBackdrop(Modifier.fillMaxSize())

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = Space.pageH),
        ) {
            // ---- 关闭 ----
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Spacer(Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .pressFeedback(onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) {
                    GlyphIcon(Glyph.Close, size = 20.dp, tint = InkDim, strokeWidth = 1.8f)
                }
            }

            // ---- 恭喜 ----
            Column(Modifier.padding(top = Space.row)) {
                Text("恭喜", style = AppText.label, color = Amber)
                Spacer(Modifier.height(Space.tight))
                Text("今日打卡成功", style = AppText.display)
                Spacer(Modifier.height(Space.tight))
                Text(
                    text = "连续 ${stats.first} 天 · 今天听了 ${DayKey.minutesLabel(stats.second)}",
                    style = AppText.caption,
                    color = InkDim,
                )
            }

            // ---- 海报 ----
            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(vertical = Space.block),
                contentAlignment = Alignment.Center,
            ) {
                val w = posterWidth(maxWidth, maxHeight)
                if (poster != null) {
                    Image(
                        bitmap = poster.asImageBitmap(),
                        contentDescription = "今日打卡海报",
                        modifier = Modifier.width(w).aspectRatio(POSTER_RATIO),
                    )
                } else {
                    PosterFallback(width = w, busy = busy)
                }
            }

            // ---- 分享 ----
            // 海报还没取到时这个按钮是点不动的（点了也没有图可发）。
            // 但**不能只让它点不动**：满亮的琥珀色按钮按下去毫无反应，
            // 用户的判断是「App 卡了」，而不是「还要等一下」。
            // 所以未就绪时整体降到 45% —— 一眼看出它现在还不顶用。
            //
            // ⚠️ 「就绪」的判据是**图在不在**，不是「没在忙」（1.2.7）。
            //    原来写的是 `!busy`：取图那一小段忙完之后按钮就变亮，
            //    而图可能仍然是 null（庆祝页当时只等文件、等不到就算了）——
            //    用户按下去只换回一句「海报还在生成，稍等一下」。
            //    一个满亮的按钮说着「等一下」，正是他报的那条症状。
            val ready = poster != null
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .alpha(if (ready) 1f else 0.45f)
                    .background(Amber)
                    .pressFeedback(enabled = ready) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onShare()
                    }
                    .padding(vertical = 15.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("分享到微信", style = AppText.title, color = Bg)
            }
            Spacer(Modifier.height(26.dp))
        }
    }
}

/**
 * 海报尺寸：3:4，同时受宽度与可用高度约束。
 *
 * 只按宽度算的话，矮屏（或者分屏、横屏）上它会把下面的分享按钮顶出屏幕 ——
 * 而那个按钮正是这个页面存在的意义。
 *
 * 再乘一个 0.82 的宽度上限：留出两侧和上下的背景，烟花才有地方炸。
 * 不设这个上限时海报会占满整屏，全屏烟花就只剩顶部那一条缝可见。
 */
private fun posterWidth(maxW: Dp, maxH: Dp): Dp {
    val cap = maxW * POSTER_WIDTH_CAP
    val byHeight = maxH * POSTER_RATIO
    return if (byHeight < cap) byHeight else cap
}

/**
 * 海报还没取到时的占位。
 *
 * 会走到这里只有两种情况：几百毫秒的等待窗口，或者海报生成失败。
 * 前者给个转圈，后者给一句「已打卡」—— **不写「生成中…」**：
 * 万一渲染真的失败了，那句「生成中」会一直挂着，看起来像卡死。
 */
@Composable
private fun PosterFallback(width: Dp, busy: Boolean) {
    Box(
        modifier = Modifier
            .width(width)
            .aspectRatio(POSTER_RATIO)
            .background(BgRaised),
        contentAlignment = Alignment.Center,
    ) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 1.6.dp,
                color = Amber,
            )
        } else {
            Text("已打卡", style = AppText.section, color = Amber)
        }
    }
}

/**
 * 一次性提示（分享没找到应用之类）。贴在底部，自动消失。
 *
 * 不复用打卡页里那个 `HintBar`（它是 private）—— 两处的定位基准不同：
 * 这里的提示要压在分享按钮**下面**，而打卡页那条是贴在整屏底部。
 */
@Composable
fun CelebrationHint(message: String, onDismiss: () -> Unit) {
    LaunchedEffect(message) {
        delay(2600)
        onDismiss()
    }
    Box(
        Modifier
            .fillMaxSize()
            .navigationBarsPadding(),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(BgRaised)
                .pressFeedback(onClick = onDismiss)
                .padding(horizontal = Space.pageH, vertical = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(message, style = AppText.caption, color = InkMid)
        }
    }
}

// ---------------------------------------------------------------------------
// 烟花
// ---------------------------------------------------------------------------

/**
 * 一轮循环的时长。9 个爆点错开铺在里面，平均每 0.5 秒就有一朵在炸，
 * 视觉上是连续的，但不会有「每次都一样」的机械感。
 */
private const val FIREWORK_CYCLE_MS = 4_600

/** 从这一刻开始淡出（生命周期比例）。前半程保持全亮，是「炸开」的观感来源 */
private const val FADE_FROM = 0.42f

/** 海报宽度上限（占可用宽度的比例）。留白给烟花，理由见 [posterWidth] */
private const val POSTER_WIDTH_CAP = 0.82f

/** 海报宽 : 高的倒数 —— 海报是 1080×1440 */
private const val POSTER_RATIO = 3f / 4f

/**
 * 一个爆点。
 *
 * 全是「一轮循环里的比例」而不是绝对坐标，所以任意屏幕尺寸都能用同一份数据。
 */
private data class Burst(
    /** 一轮里什么时候开始炸，0..1 */
    val start: Float,
    /** 炸开后存活多久，0..1（相对一轮） */
    val span: Float,
    /** 爆心水平位置，屏宽的比例 */
    val fx: Float,
    /** 爆心垂直位置，屏高的比例 */
    val fy: Float,
    /** 半径上限，取屏**短边**的比例 */
    val reach: Float,
    /** 用哪个强调色（0 琥珀 / 1 橄榄 / 2 绯红） */
    val hue: Int,
    /** 花瓣数 */
    val petals: Int,
)

/**
 * 爆点分布。
 *
 * ⚠️ `fy` 刻意只落在 **0.06–0.26 与 0.76–0.96** 两段 —— 中间那块被海报占着，
 * 画在那里等于没画（烟花层在海报下面）。这两段加起来占屏幕一半以上，
 * 足够铺开，而且上下各有一次「此起彼伏」的节奏，比均匀撒开更像烟花。
 */
private val BURSTS = listOf(
    Burst(0.00f, 0.42f, 0.24f, 0.16f, 0.20f, 0, 14),
    Burst(0.11f, 0.40f, 0.78f, 0.10f, 0.16f, 1, 12),
    Burst(0.24f, 0.44f, 0.52f, 0.24f, 0.23f, 2, 16),
    Burst(0.36f, 0.36f, 0.13f, 0.20f, 0.14f, 0, 10),
    Burst(0.47f, 0.44f, 0.88f, 0.24f, 0.18f, 2, 12),
    Burst(0.58f, 0.40f, 0.34f, 0.08f, 0.15f, 1, 12),
    Burst(0.70f, 0.42f, 0.72f, 0.90f, 0.20f, 0, 14),
    Burst(0.80f, 0.38f, 0.18f, 0.94f, 0.15f, 2, 10),
    Burst(0.90f, 0.44f, 0.55f, 0.84f, 0.22f, 1, 14),
)

/**
 * 烟花背景。
 *
 * 只用一个循环时钟驱动全部爆点：每朵根据 [Burst.start] 从时钟里取自己那一段。
 * 相比「每朵各起一个动画」，这样只有一个 state 在每帧变化，
 * 重组和绘制都是一次 —— 而这个页面底下的海报是一张 1080×1440 的位图，
 * 帧率本来就要省着用。
 *
 * 颜色在**组合期**取好再传进 [Canvas]：绘制作用域里读不到主题色
 * （`Amber` 这些是 `@Composable` 属性）。这条坑在 `ActionChip` 那边也记过一次。
 */
@Composable
private fun FireworksBackdrop(modifier: Modifier = Modifier) {
    val colors = listOf(Amber, Olive, Crimson)

    val transition = rememberInfiniteTransition(label = "fireworks")
    val clock by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(FIREWORK_CYCLE_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "firework-clock",
    )

    Canvas(modifier) {
        val short = size.minDimension
        if (short <= 0f) return@Canvas

        BURSTS.forEachIndexed { index, burst ->
            // 这一朵在本轮的局部进度；还没轮到就 +1 绕回上一轮
            var local = clock - burst.start
            if (local < 0f) local += 1f
            if (local > burst.span) return@forEachIndexed
            drawBurst(colors[burst.hue % colors.size], local / burst.span, burst, index)
        }
    }
}

/**
 * 画一朵。
 *
 * @param p 这一朵的生命周期进度，0 = 刚炸开，1 = 完全消失
 * @param seed 用于给花瓣加**确定性**抖动
 */
private fun DrawScope.drawBurst(color: Color, p: Float, burst: Burst, seed: Int) {
    val short = size.minDimension
    val center = Offset(size.width * burst.fx, size.height * burst.fy)

    // 半径先快后慢 —— 线性扩张看起来像「一圈在变大」，不像炸开
    val grow = 1f - (1f - p) * (1f - p)
    val radius = short * burst.reach * grow

    val alpha = if (p < FADE_FROM) 1f
    else (1f - (p - FADE_FROM) / (1f - FADE_FROM)).coerceIn(0f, 1f)
    if (radius <= 0f || alpha <= 0f) return

    // 起手的中心闪光
    if (p < 0.16f) {
        val flash = 1f - p / 0.16f
        drawCircle(
            color = color.copy(alpha = alpha * 0.5f * flash),
            radius = (2.dp + 6.dp * flash).toPx(),
            center = center,
        )
    }

    val spin = seed * 0.83f
    val dot = 2.6.dp

    for (k in 0 until burst.petals) {
        // 抖动必须由下标推出来，不能用随机数：动画每帧都重算，
        // 随机会让每朵花每帧都换一个形状，看起来像噪点而不是火花。
        // （37 与 11 互质，保证 10 个下标取到 10 个不同的值）
        val jitter = 0.76f + 0.46f * (((k * 37 + seed * 7) % 11) / 10f)
        val angle = spin + k * (2f * PI.toFloat() / burst.petals)
        val r = radius * jitter
        val x = center.x + cos(angle) * r
        val y = center.y + sin(angle) * r

        // 尾迹：从 62% 半径处连到火花。只画到 62% 而不是圆心 ——
        // 连到圆心的话所有尾迹会在中间糊成一片，反而看不出方向
        drawLine(
            color = color.copy(alpha = alpha * 0.26f),
            start = Offset(center.x + cos(angle) * r * 0.62f, center.y + sin(angle) * r * 0.62f),
            end = Offset(x, y),
            strokeWidth = 1.2.dp.toPx(),
            cap = StrokeCap.Round,
        )
        drawCircle(
            color = color.copy(alpha = alpha),
            radius = (dot * (1f - 0.42f * p)).toPx(),
            center = Offset(x, y),
        )
    }
}
