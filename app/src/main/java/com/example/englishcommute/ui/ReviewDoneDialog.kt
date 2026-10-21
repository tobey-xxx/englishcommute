package com.example.englishcommute.ui

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.englishcommute.ui.theme.Amber
import com.example.englishcommute.ui.theme.AppText
import com.example.englishcommute.ui.theme.Bg
import com.example.englishcommute.ui.theme.BgRaised
import com.example.englishcommute.ui.theme.Hairline
import com.example.englishcommute.ui.theme.Ink
import com.example.englishcommute.ui.theme.InkFaint
import com.example.englishcommute.ui.theme.InkMid
import com.example.englishcommute.ui.theme.Space
import com.example.englishcommute.ui.theme.pressFeedback

/**
 * 「今天的复习做完了」—— 复习完当天全部到期词之后弹一次。
 *
 * ## 它跟打卡是**两个独立的成就**，不合并
 *
 * | | 看什么 | 判据 |
 * |---|---|---|
 * | 打卡 | 收听时长（40 分钟） | 你今天听了没有 |
 * | 这个弹窗 | 复习队列 | 你今天把该记的词过完了没有 |
 *
 * 用户明确要求「复习**不计入**打卡时长」，所以两者也不能共用一个判据 ——
 * 那会把「躺着刷词也算学习」放进来。分开的代价是多一个提示，
 * 收益是「听」和「记」各自有各自的完成感。
 *
 * ## 三条「不烦人」的规矩
 *
 * 1. **只有「好」一个按钮** —— 一个庆祝弹窗不该有任何需要决策的东西
 * 2. **一天只弹一次**（判据见 `WordStore.shouldCelebrate` / `celebratedToday`）
 * 3. **不生成海报、不给分享入口**。打卡页那张海报是有意义的（它能发出去），
 *    而生词的复习记录**没有对外分享的价值** —— 硬加一个分享按钮只是噪音
 */
@Composable
fun ReviewDoneDialog(count: Int, total: Int, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(BgRaised)
                .padding(horizontal = Space.block, vertical = 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 一个简单的对勾。不用 Canvas 画烟花 —— 那个体量配不上这个时刻，
            // 而且打卡页已经有一套庆祝视觉了，重复一套只会显得浮夸
            Box(
                Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(23.dp))
                    .background(Amber),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "✓",
                    style = AppText.section,
                    color = Bg,
                )
            }

            Spacer(Modifier.height(18.dp))
            Text(
                text = "今天的复习做完了",
                style = AppText.title,
                color = Ink,
            )

            Spacer(Modifier.height(10.dp))
            Text(
                // ⚠️ 数字用**这次会话真正过完的那个数**（`count`），不是生词本总数。
                //    「生词本共 40 个」跟今天这件事没关系，摆在这里会让人
                //    以为「还有 40 个要学」——那正好是反效果。
                text = "刚刚过完 $count 个词，它们下次见面就是几天以后了。",
                style = AppText.body,
                color = InkMid,
            )

            Spacer(Modifier.height(8.dp))
            Text(
                text = if (total > count) {
                    "生词本里一共 $total 个词，明天会有一批新的到期。"
                } else {
                    "这是你生词本里的全部。明天会有新的一批到期。"
                },
                style = AppText.gloss,
                color = InkFaint,
            )

            Spacer(Modifier.height(22.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Amber)
                    .pressFeedback(onClick = onDismiss)
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "好",
                    style = AppText.title,
                    color = Bg,
                )
            }
        }
    }
}

/** 分隔线。与复习页底栏用的是同一条，放这里避免两处各写一遍粗细 */
@Composable
internal fun HairlineDivider(modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().height(1.dp).background(Hairline.copy(alpha = 0.6f))) {}
}
