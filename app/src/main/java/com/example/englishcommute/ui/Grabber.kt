package com.example.englishcommute.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.example.englishcommute.ui.theme.HairlineStrong
import com.example.englishcommute.ui.theme.Radius

/**
 * 抓手 —— 那条 38×4 的小横条。
 *
 * ## 它不是装饰，它是「这里可以下拉」这件事在屏幕上唯一的表达
 *
 * 1.2.9 做播放页下拉关闭时它就出现了（`DragHandleBar` 里）。到 3.0.0 做词卡，
 * 我在 `ModalBottomSheet` 上传了 `dragHandle = null`，理由也写进了注释 ——
 * 「不用它自带的把手：那是一条居中的浅色横杠，和这套细线 + 留白的视觉不是一路」。
 *
 * 那时我把「**M3 自带的把手长什么样**」当成了问题，于是整个槽位被关掉。
 * 但手势一直是好的（`ModalBottomSheet` 的拖动挂在整张 sheet 上，不依赖那个槽位），
 * 所以关掉之后功能毫发无损 —— 只是**没有任何东西告诉用户这里能下拉**。
 *
 * ⚠️ 这个 bug 的形态值得记住：**功能完好 + 提示缺失**。
 *    不报错、不崩、编译干净、单测全绿，唯一能发现它的方式是有人真的去用它 ——
 *    用户就是这么报上来的：「加个小横条，用以引导客户可以下滑关闭」。
 *
 * 所以正解不是「去掉把手」，而是「**换一个和这套视觉一致的把手**」。
 * 这两条路在一开始看起来完全等价（都不显示 M3 那个样式），
 * 差别要等到有人问「这个怎么关掉」时才显现出来。
 *
 * ## 为什么放这里而不是各写一份
 *
 * 播放页的抓手和词卡的抓手不会同时出现在屏幕上，但它们**必须长得一样**。
 * 两处各写一遍 `width(38.dp) / height(4.dp)` 之后，「改一处、另一处不动」
 * 是那种「单看每一处都完全正确」的分叉 —— 值本身不重要，**一致**才重要。
 */
object Grabber {

    /** 横条本身的宽 */
    val WIDTH = 38.dp

    /** 横条本身的高 */
    val HEIGHT = 4.dp

    /**
     * 连抓手在内的整块高度。
     *
     * 它同时是播放页那块下拉热区的**组成部分** —— 不过那里真正接手势的是
     * **整块顶栏**（见 `DragHandleBar`）：18dp 在通勤路上一只手抓不住。
     */
    val AREA = 18.dp
}

/**
 * 抓手本体：居中、**不带手势**。
 *
 * 手势刻意留在调用方，因为两个调用点的语义不一样：
 *
 * | 调用点 | 手势在哪 |
 * |---|---|
 * | 播放页 | `DragHandleBar` 的整块顶栏上（要接手指出手的速度，所以自己采 VelocityTracker） |
 * | 词卡 | `ModalBottomSheet` 内部（它是整张 sheet 的拖动，这里只是视觉） |
 */
@Composable
fun GrabberBar(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxWidth().height(Grabber.AREA),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .width(Grabber.WIDTH)
                .height(Grabber.HEIGHT)
                .clip(RoundedCornerShape(Radius.pill))
                .background(HairlineStrong)
        )
    }
}
