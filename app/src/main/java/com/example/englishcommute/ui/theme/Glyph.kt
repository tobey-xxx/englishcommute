package com.example.englishcommute.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import androidx.compose.ui.geometry.CornerRadius

/**
 * 手绘细线图标。
 *
 * ## 为什么不用 material-icons-extended
 *
 * 上一版图省事，两个页签的图标直接用了 emoji（🎧 / ⚙️）。这在深色界面上是
 * 最刺眼的一处「AI 味」：emoji 是彩色位图、有自己的一套配色和体积，
 * 和界面的细线风格完全不兼容，而且在不同的 Android 版本上渲染成不同的样子。
 *
 * 换成 material-icons-extended 又要多背十几 MB 的 dex（2000+ 个矢量的代价），
 * 为这十来个图标不划算。
 *
 * 所以**直接用 Canvas 画**：所有图形都是 24×24 坐标系里的直线、折线、
 * 圆弧、三角和圆角矩形，按 Dp 等比缩放。一共不到 200 行，零依赖，
 * 而且在任何设备上都是同一个样子。
 *
 * 线条统一 1.6 单位粗细 + 圆头圆角 —— 比 Material 默认的 2.0 更细，
 * 是这套视觉里「不 Material」的一个具体来源。
 */
enum class Glyph {
    ChevronLeft,
    ChevronRight,
    Play,
    Pause,
    SkipPrev,
    SkipNext,
    Star,
    StarFilled,
    Refresh,
    Search,
    Check,
    Close,
    Sliders,
    Headphones,
    Translate,
    Trash,

    /**
     * 打卡页签用。日历框 + 框内一个对勾。
     *
     * 试过几个方案都不如它：火苗（海报那边也栽过 —— 轮廓靠贝塞尔猜不出来）、
     * 奖章（外圈锯齿在小尺寸下糊成一团）、纯对勾（和列表里的「已完成」标记撞车）。
     * 日历 + 勾的好处是**结构简单**：两条直线 + 一个圆角矩形 + 一个折线，
     * 缩到 19dp 仍然分得清。
     */
    CalendarCheck,

    /**
     * 生词本：一本竖着的书（外框 + 左侧书脊）。
     *
     * ⚠️ 为什么不能偷懒复用 [Sliders]（设置）：这两个图标在底栏里**挨着**，
     * 而「三条横线」和「三条横线加两个圆点」缩到 19dp 之后几乎分不出来 ——
     * 用户想进生词本却点开了设置，而他不会觉得是自己点错。
     * 形状必须**结构上**不同（一个是开放线条，一个是闭合外框）。
     */
    Book,
}

@Composable
fun GlyphIcon(
    glyph: Glyph,
    modifier: Modifier = Modifier,
    size: Dp = 22.dp,
    tint: Color = Ink,
    strokeWidth: Float = 1.6f,
) {
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension / 24f
        if (s <= 0f) return@Canvas
        withTransform({ scale(s, s, pivot = Offset.Zero) }) {
            drawGlyph(glyph, tint, strokeWidth)
        }
    }
}

private fun DrawScope.drawGlyph(glyph: Glyph, tint: Color, sw: Float) {
    val stroke = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round)

    fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
        drawLine(tint, Offset(x1, y1), Offset(x2, y2), sw, StrokeCap.Round)

    fun poly(points: List<Pair<Float, Float>>, close: Boolean, fill: Boolean) {
        val p = Path().apply {
            moveTo(points[0].first, points[0].second)
            points.drop(1).forEach { lineTo(it.first, it.second) }
            if (close) close()
        }
        if (fill) drawPath(p, tint) else drawPath(p, tint, style = stroke)
    }

    fun chevron(cx: Float, dir: Int) {
        // 一个 45° 的尖角，dir = -1 朝左、+1 朝右
        poly(
            listOf((cx - 3.4f * dir) to 4.6f, (cx + 3.4f * dir) to 12f, (cx - 3.4f * dir) to 19.4f),
            close = false, fill = false
        )
    }

    fun triangle(cx: Float, dir: Int) {
        poly(listOf((cx - 5.4f * dir) to 5.2f, (cx + 5.4f * dir) to 12f, (cx - 5.4f * dir) to 18.8f),
            close = true, fill = true)
    }

    fun starPoints(): List<Pair<Float, Float>> = (0 until 10).map { i ->
        val r = if (i % 2 == 0) 9.2f else 4.0f
        val a = (-90.0 + i * 36.0) * PI / 180.0
        (12f + (r * cos(a)).toFloat()) to (12f + (r * sin(a)).toFloat())
    }

    when (glyph) {
        Glyph.ChevronLeft -> chevron(11.8f, -1)
        Glyph.ChevronRight -> chevron(12.2f, 1)

        Glyph.Play -> triangle(11.2f, 1)

        Glyph.Pause -> {
            // 用粗线画两条竖条，比矩形更贴合细线体系
            drawLine(tint, Offset(9.2f, 5.2f), Offset(9.2f, 18.8f), 2.6f, StrokeCap.Round)
            drawLine(tint, Offset(14.8f, 5.2f), Offset(14.8f, 18.8f), 2.6f, StrokeCap.Round)
        }

        Glyph.SkipPrev -> {
            drawLine(tint, Offset(6.4f, 5.6f), Offset(6.4f, 18.4f), 1.9f, StrokeCap.Round)
            poly(listOf(18.2f to 5.6f, 9.4f to 12f, 18.2f to 18.4f), close = true, fill = true)
        }

        Glyph.SkipNext -> {
            drawLine(tint, Offset(17.6f, 5.6f), Offset(17.6f, 18.4f), 1.9f, StrokeCap.Round)
            poly(listOf(5.8f to 5.6f, 14.6f to 12f, 5.8f to 18.4f), close = true, fill = true)
        }

        Glyph.Star -> poly(starPoints(), close = true, fill = false)
        Glyph.StarFilled -> poly(starPoints(), close = true, fill = true)

        Glyph.Refresh -> {
            drawArc(
                color = tint,
                startAngle = -50f,
                sweepAngle = 295f,
                useCenter = false,
                topLeft = Offset(4.6f, 4.6f),
                size = Size(14.8f, 14.8f),
                style = stroke,
            )
            // 箭头贴在弧的末端（-50+295 = 245°），用旋转把「右侧的三角」转过去
            rotate(245f, pivot = Offset(12f, 12f)) {
                poly(listOf(16.9f to 9.6f, 16.9f to 14.4f, 20.9f to 12f), close = true, fill = true)
            }
        }

        Glyph.Search -> {
            drawCircle(tint, 5.4f, Offset(10.4f, 10.4f), style = stroke)
            line(14.4f, 14.4f, 19.2f, 19.2f)
        }

        Glyph.Check -> poly(listOf(6.2f to 12.6f, 10.1f to 16.6f, 18.2f to 7.4f), false, false)

        Glyph.Close -> {
            line(7f, 7f, 17f, 17f)
            line(17f, 7f, 7f, 17f)
        }

        Glyph.Sliders -> {
            line(5f, 8.6f, 19f, 8.6f)
            drawCircle(tint, 2.5f, Offset(15.2f, 8.6f))
            line(5f, 15.4f, 19f, 15.4f)
            drawCircle(tint, 2.5f, Offset(8.8f, 15.4f))
        }

        Glyph.Headphones -> {
            drawArc(
                color = tint,
                startAngle = 180f,
                sweepAngle = 180f,
                useCenter = false,
                topLeft = Offset(4.2f, 5.4f),
                size = Size(15.6f, 15.6f),
                style = stroke,
            )
            drawRoundRect(
                color = tint,
                topLeft = Offset(4.2f, 13.2f),
                size = Size(3.6f, 6.6f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.8f, 1.8f),
                style = stroke,
            )
            drawRoundRect(
                color = tint,
                topLeft = Offset(16.2f, 13.2f),
                size = Size(3.6f, 6.6f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.8f, 1.8f),
                style = stroke,
            )
        }

        // 「中」字：一个方框 + 一竖。比任何翻译类图标都直白
        Glyph.Translate -> {
            drawRoundRect(
                color = tint,
                topLeft = Offset(5.4f, 5.4f),
                size = Size(13.2f, 13.2f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.2f, 2.2f),
                style = stroke,
            )
            line(12f, 5.4f, 12f, 18.6f)
        }

        Glyph.Trash -> {
            line(4.6f, 7.4f, 19.4f, 7.4f)
            poly(listOf(9.6f to 7.4f, 9.6f to 4.6f, 14.4f to 4.6f, 14.4f to 7.4f), false, false)
            poly(listOf(6.8f to 7.4f, 7.8f to 19.4f, 16.2f to 19.4f, 17.2f to 7.4f), false, false)
            line(10.4f, 10.6f, 10.4f, 16.4f)
            line(13.6f, 10.6f, 13.6f, 16.4f)
        }

        Glyph.Book -> {
            // 书：外框 + 书脊。闭合形状，与 Sliders 的开放横线在缩略尺寸下也不会混
            drawRoundRect(
                color = tint,
                topLeft = Offset(5.4f, 4.2f),
                size = Size(13.2f, 15.6f),
                cornerRadius = CornerRadius(2.2f, 2.2f),
                style = stroke,
            )
            line(9.6f, 4.4f, 9.6f, 19.6f)
        }

        Glyph.CalendarCheck -> {
            drawRoundRect(
                color = tint,
                topLeft = Offset(4.6f, 6.4f),
                size = Size(14.8f, 13f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.4f, 2.4f),
                style = stroke,
            )
            // 表头分隔线。画在 y=10.4 —— 比中线略高，给格子里留出画勾的空间
            line(4.6f, 10.4f, 19.4f, 10.4f)
            // 两条「装订钉」：顶端伸出来一点，日历的身份全靠它
            line(8.6f, 4f, 8.6f, 8.2f)
            line(15.4f, 4f, 15.4f, 8.2f)
            poly(listOf(8.6f to 15.2f, 11f to 17.4f, 15.6f to 12.8f), close = false, fill = false)
        }
    }
}
