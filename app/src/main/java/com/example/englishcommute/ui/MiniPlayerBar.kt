package com.example.englishcommute.ui

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.englishcommute.player.SrtTrack
import com.example.englishcommute.ui.theme.Amber
import com.example.englishcommute.ui.theme.AppText
import com.example.englishcommute.ui.theme.Bg
import com.example.englishcommute.ui.theme.Glyph
import com.example.englishcommute.ui.theme.GlyphIcon
import com.example.englishcommute.ui.theme.Hairline
import com.example.englishcommute.ui.theme.Ink
import com.example.englishcommute.ui.theme.InkDim
import com.example.englishcommute.ui.theme.InkFaint
import com.example.englishcommute.ui.theme.Space
import com.example.englishcommute.ui.theme.pressFeedback

/**
 * 悬浮迷你播放条。
 *
 * ## 要解决的问题
 *
 * 播放页是一个全屏覆盖层，退出之后画面上完全没有「还有东西在播」的痕迹 ——
 * 音频在响，但界面看上去什么都没发生，想回去只能重新翻列表找那条内容。
 * 这个条就是那条退路：**一条随时可点的返回入口，顺带给出播放状态**。
 *
 * ## 形态取舍
 *
 * 做成细长条而不是圆形悬浮球，有三个理由：
 *
 *  1. 圆形球压在列表上会**永久遮挡右下角的内容**（那一带正好是条目的元信息行）。
 *     条状贴在底栏上方，占的是一条本来就存在的边缘，不遮任何东西。
 *  2. 球体只能表达「开/关」，条状能顺带放下标题、时间和进度 ——
 *     用户不用进去就知道播到哪了。
 *  3. 圆形悬浮球是「AI 生成界面」的典型素材之一。这里既已全面去卡片化，
 *     再放一个圆球会立刻把观感拉回去。
 *
 * ## 进度线为什么长在最底部
 *
 * 进度线不是画在条内部的一条线，而是**条的下边缘本身**。这样有两个好处：
 * 视觉上没有任何「浮起来的元素」，同时进度天然是整宽比例，不需要额外的
 * 内边距去对齐标题 —— 一旦要对齐，标题和进度条的左右边界就会互相牵制，
 * 改一个动另一个，最后总有一处对不齐。
 *
 * ## 状态来源
 *
 * [PlayerUiState] 由 Activity 作用域内的 `PlayerViewModel` 提供，与播放页
 * **是同一个实例**。所以这里不需要再连一次播放服务：退出播放页时
 * `MediaController` 本来就还连着，音频继续播，这个条读到的就是同一份实时状态。
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun MiniPlayerBar(
    ui: PlayerUiState,
    /**
     * 共享元素形变所在的 Scope（1.2.9）。**null = 形变关掉了**，
     * 见 [SHARED_MORPH] 那段说明 —— 判空就发生在下面一处。
     */
    morphScope: SharedTransitionScope? = null,
    /**
     * 这一端的封面「算不算在场」。
     *
     * 迷你条是**常驻**的（它不在任何 AnimatedVisibility 里，没有 scope 可传），
     * 所以可见性只能由调用方声明。播放页打开时传 `false` ——
     * 否则同一个键会同时存在两个「在场」的副本，形变会打架。
     */
    morphVisible: Boolean = true,
    onOpen: () -> Unit,
    onTogglePlay: () -> Unit,
    /** 「下一集」。通勤场景里「这集听完了想接着听」是最高频的动作之一，
     *  原先必须先点进播放页再点下一集（1.2.8 提到这里） */
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // title 为空 = 还没有任何内容被载入过（首次启动、或清空过缓存）。
    // 这时整条不存在，而不是显示一条空的占位 —— 空占位会让底栏区域一直挂着一块灰。
    if (ui.title.isEmpty()) return

    val fraction = if (ui.durationMs > 0L) {
        (ui.positionMs.toDouble() / ui.durationMs).coerceIn(0.0, 1.0).toFloat()
    } else 0f

    // 底色与底栏一致（都是 Bg），上边缘那条线由外层统一画（1.2.8）——
    // 这样迷你条与底栏在视觉上是**一块**，而不是两块叠着的灰条
    Column(modifier.fillMaxWidth().background(Bg)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .pressFeedback(onClick = onOpen)
                .padding(start = Space.pageH, end = 10.dp, top = 9.dp, bottom = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 封面是这条迷你条「像播放器」的关键一笔。
            // 加它之前，这条里只有一行标题和一个播放键，看起来更像一条通知；
            // 有了封面之后，它才和播放页是同一个东西的两个尺寸。
            //
            // ★ 1.2.9：它同时是**共享元素形变的一端**（§7 空间连续性）——
            //   播放页顶栏那张 44dp 封面用的是同一个键（见 ui/Morph.kt）。
            //
            //   用的是 `sharedElementWithCallerManagedVisibility` 而不是
            //   `sharedElement`：后者的第二个参数是 AnimatedVisibilityScope，
            //   而迷你条**不在任何 AnimatedVisibility 里**（它是常驻的），
            //   拿不到那个 scope。前者把「这一端算不算在场」交回调用方，
            //   正好对应这里的 [morphVisible]。
            //
            //   参数一律用**位置**传：这两个形变函数的参数名属于实验性 API，
            //   跟着版本改过，位置才稳。
            val coverMorph: Modifier = if (morphScope != null) {
                with(morphScope) {
                    Modifier.sharedElementWithCallerManagedVisibility(
                        rememberSharedContentState(MORPH_COVER_KEY),
                        morphVisible,
                    )
                }
            } else {
                Modifier
            }
            Artwork(
                sourceId = ui.sourceId,
                artworkUrl = ui.artworkUrl,
                placeholder = ui.sourceName,
                size = 40.dp,
                modifier = coverMorph,
            )

            Spacer(Modifier.width(11.dp))

            Column(Modifier.weight(1f)) {
                // 正在播时标题用墨白、暂停时退回中灰：一眼可判的播放状态，
                // 不用去读那个小图标
                Text(
                    text = ui.title,
                    style = AppText.title,
                    color = if (ui.playing) Ink else InkDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                // 「当前位置 / 总长」而不是只报当前位置 —— 播放器里用户更常问的是
                // 「还剩多少」。两个数都等宽，每秒跳动时整行不会左右抖
                Text(
                    text = buildString {
                        append(SrtTrack.formatClock(ui.positionMs))
                        if (ui.durationMs > 0L) {
                            append(" / ")
                            append(SrtTrack.formatClock(ui.durationMs))
                        }
                    },
                    style = AppText.label,
                    color = InkFaint,
                )
            }

            Spacer(Modifier.width(8.dp))

            // 播放/暂停独立可点。外面整条也是可点的（进播放页），
            // 所以这里必须自己吃掉点击事件 —— 否则按一下会同时触发两件事
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .pressFeedback(onClick = onTogglePlay),
                contentAlignment = Alignment.Center,
            ) {
                GlyphIcon(
                    glyph = if (ui.playing) Glyph.Pause else Glyph.Play,
                    size = 15.dp,
                    tint = if (ui.buffering) InkFaint else Amber,
                    strokeWidth = 1.7f,
                )
            }

            // 「下一集」。用 Chevron 而不是 SkipNext —— 后者在播放页的主控排里
            // 表示「下一句」，两者形状相近会真的点错（与播放页同一条规矩）
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .pressFeedback(enabled = ui.hasNext, onClick = onNext),
                contentAlignment = Alignment.Center,
            ) {
                GlyphIcon(
                    glyph = Glyph.ChevronRight,
                    size = 17.dp,
                    tint = if (ui.hasNext) Ink else InkFaint,
                    strokeWidth = 1.7f,
                )
            }
        }

        // 下边缘即进度线。没有 progress 时它就是一条 hairline，与上面的分割线同款
        if (fraction > 0f) {
            Box(Modifier.fillMaxWidth().height(2.dp).background(Hairline)) {
                Box(
                    Modifier
                        .fillMaxWidth(fraction)
                        .height(2.dp)
                        .background(Amber)
                )
            }
        } else {
            Box(Modifier.fillMaxWidth().height(2.dp).background(Hairline))
        }
    }
}
