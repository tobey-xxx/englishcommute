package com.example.englishcommute.ui

import com.example.englishcommute.diag.DiagLog
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.englishcommute.content.ArtworkStore
import com.example.englishcommute.ui.theme.AppText
import java.util.Locale

/**
 * 封面仓库的下发通道。
 *
 * 用 CompositionLocal 而不是逐层传参：封面出现在列表项、迷你条、播放页、
 * 通知栏四个地方，而列表项外面还隔着 `LazyColumn` 和若干私有 Composable ——
 * 一路把参数穿下去，「要不要给这个私有组件也加一个参数」会变成一个反复出现的噪音。
 *
 * 默认给 null 而不是抛异常：没有容器时封面退化成占位块，界面其余部分照常工作。
 * 这个 App 里封面是**装饰**，不该有能力让整个页面挂掉。
 */
val LocalArtworkStore = staticCompositionLocalOf<ArtworkStore?> { null }

/**
 * 一个方形封面。
 *
 * ## 加载是「先显示、后替换」
 *
 * 首帧总是先画占位块，拿到图之后再换成真图。
 * 不做 loading 骨架屏、不做淡入：通勤场景下用户是在列表里快速滑动，
 * 一个会闪烁的过渡比直接切换更干扰。而且**磁盘命中时这个切换在同一帧内完成**
 * （`peek` 是同步的），从播放页退回来不会有任何闪烁。
 *
 * ## 占位块为什么是「双字母 + 渐变」而不是「一个字母 + 纯色」
 *
 * 一个字母的纯色块看起来就是个**加载失败的占位符** —— 用户的原话是
 * 「只是一个字母 logo，没有什么特别的」。而现实是：这批源的图床
 * （`media.npr.org` / `image.simplecastcdn.com`）与 feed 不同域，
 * 在国内不一定可达，占位块**可能长期就是用户看到的样子**。
 *
 * 所以它不能只是「等图的时候顶一下」，得本身站得住：
 *  - 双字母取自源名，比首字母的区分度高得多（9 个 NPR 系源首字母全是 N）
 *  - 纵向渐变 + 稍高的饱和度，让它看起来像一张**唱片标签**，
 *    而不是一块「颜色 + 字符」
 *  - 不做圆角卡片、不加阴影 —— 这套视觉是明确去卡片化的
 *
 * @param placeholder 取源名，用来算双字母标记。必须是**稳定**的，否则每次重组占位块都会跳字
 */
@Composable
fun Artwork(
    sourceId: String,
    artworkUrl: String,
    placeholder: String,
    size: Dp,
    modifier: Modifier = Modifier,
    corner: Dp = 2.dp,
) {
    val store = LocalArtworkStore.current
    // ★ 4.6.6：**没接上要喊一声**。它原来只是静默地不加载 —— 而「封面一直是
    //    字母块、日志里一个字都没有」正是这个沉默造成的（改动前它活了很久）。
    //    只打一次，避免列表每行刷屏。
    if (store == null) {
        remember(sourceId) {
            DiagLog.w("Artwork", "封面仓库没接上（LocalArtworkStore 未提供）")
            true
        }
    }

    // key 用 sourceId：同一源的每条内容共用一张图，滚动时不会反复重新加载。
    // 记忆化初值走 peek（只读内存），所以已经下载过的源在首帧就是真图
    var bitmap by remember(sourceId) { mutableStateOf(store?.peek(sourceId)) }

    LaunchedEffect(sourceId, artworkUrl) {
        if (bitmap == null && store != null && artworkUrl.isNotEmpty()) {
            bitmap = store.load(sourceId, artworkUrl)
            // ★ 4.6.5：失败**留一行日志**。
            //   封面这件事改了四版才定位到根因（URL 推导错了），而期间
            //   日志里**一句话都没有** —— 只有一个字母块。以后一眼能看出
            //   是「地址拿不到」还是「渲染没画」。
            if (bitmap == null) {
                DiagLog.i("Artwork", "封面加载失败 · $sourceId · ${artworkUrl.take(70)}")
            }
        }
    }

    val mark = remember(placeholder) { sourceMark(placeholder) }
    val tone = remember(sourceId) { placeholderTone(sourceId) }

    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(corner))
            .background(Brush.verticalGradient(listOf(tone.top, tone.bottom))),
        contentAlignment = Alignment.Center,
    ) {
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                text = mark,
                style = AppText.title,
                fontSize = (size.value * 0.30f).sp,
                // 字距让两个字母读起来像台标而不是缩写错误
                letterSpacing = (size.value * 0.02f).sp,
                color = Color(0xFFF7F2E8),
            )
        }
    }
}

/**
 * 从源名算一个**两字母标记**。
 *
 * 规则里唯一不显然的是「丢掉全大写的首词」这一步：
 * 11 个源里有 9 个叫 `NPR xxx`，直接取首字母会得到 9 个一模一样的 `N`。
 * 而 `NPR` / `TED` 这类词本身就是台标缩写 —— 它后面的词才是区分点。
 *
 * 实测结果（11 个源两两不重复）：
 * NPR News Now→NN、NPR Up First→UF、NPR Consider This→CT、
 * The Indicator→TI、NPR Short Wave→SW、NPR Politics→PO、
 * NPR Life Kit→LK、Planet Money→PM、NPR Code Switch→CS、
 * TED Talks Daily→TD、Listening Time→LT
 */
internal fun sourceMark(name: String): String {
    val words = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return "·"

    val head = words[0]
    val isAcronym = words.size > 1 && head.length <= 4 && head == head.uppercase(Locale.US)
    val body = if (isAcronym) words.drop(1) else words

    return when {
        body.size >= 2 -> "${body[0].first()}${body[1].first()}".uppercase(Locale.US)
        body.isNotEmpty() -> body[0].take(2).uppercase(Locale.US)
        else -> head.take(2).uppercase(Locale.US)
    }
}

/** 一块占位牌的上下两色 */
private class Tone(val top: Color, val bottom: Color)

/**
 * 占位配色：由 sourceId 稳定散列到一组「上浅下深」的暖色对。
 *
 * 这组颜色是单独配的，不参与主题 token —— 它们是「图的替身」，
 * 要同时满足三个条件：在两种主题下都能承载近白色文字（所以底色够深）、
 * 与琥珀强调色同处低饱和区（不能跳出来抢眼）、
 * 且上下两色有足够落差让渐变读得出来。
 */
private val PlaceholderTones = listOf(
    Tone(Color(0xFFB4703A), Color(0xFF6E441F)), // 赭
    Tone(Color(0xFF6F8F4E), Color(0xFF3F5528)), // 橄榄
    Tone(Color(0xFF8E6A80), Color(0xFF553E4D)), // 紫褐
    Tone(Color(0xFF4F7E8C), Color(0xFF2D4C56)), // 灰蓝
    Tone(Color(0xFFA06258), Color(0xFF5F3831)), // 砖
    Tone(Color(0xFF6A70A0), Color(0xFF3D4063)), // 靛
)

private fun placeholderTone(sourceId: String): Tone {
    if (sourceId.isEmpty()) return PlaceholderTones[0]
    var hash = 0
    for (c in sourceId) hash = hash * 31 + c.code
    val i = ((hash % PlaceholderTones.size) + PlaceholderTones.size) % PlaceholderTones.size
    return PlaceholderTones[i]
}
