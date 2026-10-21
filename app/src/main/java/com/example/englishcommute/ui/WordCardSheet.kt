package com.example.englishcommute.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.englishcommute.diag.DiagLog
import com.example.englishcommute.ui.theme.Amber
import com.example.englishcommute.ui.theme.AppText
import com.example.englishcommute.ui.theme.Bg
import com.example.englishcommute.ui.theme.Gloss
import com.example.englishcommute.ui.theme.Hairline
import com.example.englishcommute.ui.theme.Ink
import com.example.englishcommute.ui.theme.InkFaint
import com.example.englishcommute.ui.theme.InkMid
import com.example.englishcommute.ui.theme.Motion
import com.example.englishcommute.ui.theme.Space
import com.example.englishcommute.ui.theme.pressFeedback
import com.example.englishcommute.word.WordContext
import com.example.englishcommute.word.WordLookup
import kotlinx.coroutines.delay

/**
 * 加完词之后，这张卡还留多久（毫秒）。
 *
 * 1 秒是用户定的。它的两个边界都值得记一下：
 *
 *   · 太短（< 0.5s）→「加入生词本」变「已在生词本」这一下**看不见**，
 *     卡片就滑走了。那时用户唯一的感受是「我点到了吗」。
 *   · 太长（> 2s）→ 用户的下一个动作（回去听）被卡片卡住，
 *     而他刚做完一个动作、正等着界面让开。
 *
 * 1 秒刚好落在「确认能看清、但不挡路」那一段里。它**不是**一个可以随手调的
 * 手感参数，所以给它一个名字 —— 改它之前先想清楚上面两句。
 */
private const val AUTO_CLOSE_MS = 1_000L

/**
 * 退场时整张卡片下沉的距离。
 *
 * ⚠️ 3.4.0：这个数从 28dp 提到 40dp，**因为它的角色变了**。
 *
 * 3.3.0 的注释写着「刻意很小，因为面板自己也在往下滑」。那句话在当时是对的，
 * 但**退场已经不是面板在滑了**（见下面 `exit` 的说明）：现在屏幕上唯一在动的
 * 就是这张卡片，40dp 是它「向下走」的全部表达。继续留 28dp 的话，
 * 观感会变成「原地淡掉、几乎没动」—— 那是另一个极端，不是更丝滑。
 */
private val EXIT_SINK = 40.dp

/** 退场时整张卡片缩小的比例。**很小**：它只负责给「离开」一点纵深，不负责表达位移 */
private const val EXIT_SCALE = 0.03f

// 圆角形状（[cardShape]）在 `WordCardSheet` 里取一次给两处用，见那里的说明 ——
// 它必须是一个在函数内读到的 `Shape` 值（`BottomSheetDefaults.ExpandedShape`
// 是 `@Composable` 属性），所以放不成顶层常量。


/** 用户长按选中的那个词 + 它所在的那句话（「你听到的」） */
data class PickedWord(
    val surface: String,
    val context: WordContext,
)

/**
 * 词卡：长按字幕里的一个词之后从底部升起来的这一张。
 *
 * ## 卡片的信息顺序是刻意的
 *
 * 「**词 + 音标 → 释义 → 你听到的 → 教材例句**」。
 *
 * 把「你听到的」放在教材例句**前面**，是因为它才是用户此刻的锚点 ——
 * 他刚刚就在那句话里遇到这个词。教材例句是补充，不是主线。
 *
 * ## 三种状态都要说得清楚
 *
 * | 情况 | 卡片上显示什么 |
 * |---|---|
 * | 查到了、还没加 | 完整词条 + 「加入生词本」按钮 |
 * | 查到了、已经加过 | 完整词条 + 「已在生词本」（**不可点**，不是灰按钮） |
 * | **查不到** | 明说「这个词暂未收录」+ 仍然给出那句「你听到的」 |
 *
 * ⚠️ 第三种最要紧。词表覆盖的是「当前语料」，新一期里出现的新词（人名、地名、
 * 新事件）随时可能查不到。这时候**不能显示一张空卡片**，也不能编一个释义 ——
 * 如实说出来，并且把用户唯一确定有用的东西（那句话）留给他。
 *
 * ## 加完词为什么 1 秒后自己收起来（3.2.0）
 *
 * 原来这里是「加入成功**不关卡片**，把按钮变成『已在生词本』」，
 * 理由写的是「确认比流畅重要」。那个理由本身没错 —— 错的是它只有两个选项。
 * 用户给的第三条路更好：**先确认、再收起**。
 *
 * 拆开看是这样：点下去之后，「加入生词本」立刻变成「已在生词本」，
 * 这一下就是确认（不需要任何多余的文案，也不需要 toast）；看得到它之后，
 * 这张卡就没有存在的理由了 —— 用户的下一个动作一定是回去听，
 * 而不是继续盯着一个已经完成的面板。**留着它反而要求用户自己关一次。**
 *
 * ⚠️ 判据必须是「**刚刚加过**」这个一次性信号，不能拿 `alreadyIn` 代替。
 *    `alreadyIn` 对「本来就在词本里」的词也是 true —— 那样每次点开一张
 *    老词的卡，它都会自己合上，而用户根本没做任何操作。
 *    这类「用状态代替事件」的错法在本工程出现过多次：状态是持续的，
 *    而这里要的是「刚刚发生了那一下」。
 *
 * ## 把手：它不是装饰（3.2.0）
 *
 * 这里原来写的是 `dragHandle = null`，理由是「M3 自带的把手和这套视觉不是一路」。
 * 但**手势一直是好的**（拖动挂在整张 sheet 上，不依赖那个槽位），
 * 所以关掉之后功能毫发无损 —— 只是没有任何东西告诉用户这里能下拉。
 * 「功能完好 + 提示缺失」，而它不报错、单测也不会有意见。
 * 现在换成自家那一条（见 [Grabber]）：视觉是自己的，提示也回来了。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WordCardSheet(
    picked: PickedWord,
    lookup: WordLookup?,
    alreadyIn: Boolean,
    onAdd: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    /**
     * 「刚刚加进生词本」这个**一次性**信号。
     *
     * 只由按钮点击置起来 —— 不用 `alreadyIn` 代替，理由见文件头（状态 ≠ 事件）。
     * 按词形 + 所属期次做 key：换一个词重新长按时要复位，
     * 否则上一张卡留下的 `true` 会让新卡一出现就自己合上。
     */
    var justAdded by remember(picked.surface, picked.context.episodeId) {
        mutableStateOf(false)
    }

    /**
     * 这张卡自己的**退场进度**：0 = 在场，1 = 走完。
     *
     * ## 这张卡自己的**退场进度**：0 = 在场，1 = 走完
     *
     * 3.3.0 就有这个量，但**它当时不是屏幕上唯一在动的东西**，
     * 所以两次反馈都是「还是不丝滑、过渡不自然」。3.4.0 把根因找全了：
     *
     * ### 一、一张卡有两半，只有一半是我们的
     *
     * `ModalBottomSheet` 的面板底色（`containerColor`）和它自带的位置位移
     * 都由 M3 画；我们的 `Column` 只是**画在它上面的一层内容**。
     * 所以 3.3.0 那句「让内容在前 220ms 淡掉」做出来的效果是：
     *
     * ```
     * 0ms ───────────────── 220ms ─────────────── ~190ms（面板滑完）
     * 内容淡出 ✅              内容已经没了
     * 面板底色（不透明！）             ← 一块**空白面板**还在继续下滑
     * ```
     *
     * 用户看到的是「卡片先空掉、再滑走」。那不是过渡，那是**两段动作**。
     *
     * ### 二、那段位移的曲线和时长都改不了，而且它本来就不该是主角
     *
     * 面板的 spec 不是常量，是每次组合时从 `MaterialTheme.motionScheme` 取的
     * （实测：`DefaultSpatial` → `showMotionSpec` / `hideMotionSpec` /
     * `anchoredDraggableMotionSpec`）。想换掉它，三条路全堵着：
     * `SheetState.hide/show` 收不下 spec，`animateTo` / `hideMotionSpec` /
     * `MotionScheme` 全是 `internal`（**编译器直接拦住**，不是靠读文档猜的）。
     *
     * 而它是一条**弹簧**（`dampingRatio = 0.9`、`stiffness = 700`）：
     * 阻尼接近临界，所以「晃」不是问题；系数 700 意味着整段位移
     * **约 190ms 就走完了** —— 一块整屏宽的面板在 190ms 内滑走一整个自身高度。
     * 这个速度不归我们管，所以**干脆不让它参与可见运动**。
     *
     * ### 三、改法：把整张卡（含底色与抓手）收进我们自己的手里
     *
     * | 谁 | 怎么变 | 为什么 |
     * |---|---|---|
     * | 面板底色 | `containerColor = Transparent`，底色由我们自己画 | 它才能跟着一起淡，不再留下一块空白 |
     * | 抓手 | 搬出 M3 的槽位 → 跟着一起淡 | 否则会看到**一条小横杠单独滑走** |
     * | 可见的那段运动 | **本工程的 `Motion.exit()`**：淡出 + 下沉 40dp + 缩 3% | 一条曲线、一个时长、一个时钟 |
     * | 面板的位移与遮罩 | 等上面那段**走完之后**才 `hide()` | 那时面板已空且透明，它的位移与时长都不再有人看得见 |
     *
     * ⚠️ 顺序不能反。先 `hide()` 的话，面板会带着我们那张**还没淡完**的卡一起滑，
     *    于是又回到「两个时钟驱动同一屏」—— 那正是这一版要修的东西。
     *    （反过来先淡完再 hide，hide 那一段就是纯粹的收尾：面板透明、内容已空，
     *    遮罩由 M3 自己平滑收掉。）
     *
     * ⚠️ 抓手仍然留在 `dragHandle` 槽位里（手势挂在整张 sheet 上、不依赖这个槽位，
     *    这一点 3.2.0 已经验证过）—— 只是把它自己的 `graphicsLayer` 接上同一个进度。
     *    搬进 `Column` 会动到面板顶部的间距（M3 在槽位上有自己的 padding），
     *    而这一版要改的不是排版。
     *
     * 用 `graphicsLayer { }`（延迟到绘制层读）而不是 `offset { }`：后者是布局修饰符，
     * 每帧都会重新布局一遍这张卡里的所有文字。
     */
    val exit = remember(picked.surface, picked.context.episodeId) { Animatable(0f) }

    LaunchedEffect(justAdded) {
        if (!justAdded) return@LaunchedEffect
        delay(AUTO_CLOSE_MS)
        // 日志放在**收起动作之前**：`onDismiss()` 会让这张卡离开组合，
        // 而离开组合会取消这个协程 —— 写在它后面的话有概率打不出来
        // （「做完了但没留痕」比「没做」更难查）。
        DiagLog.act("词卡：加入生词本后自动收起")
        // ① 可见的那一段：整张卡（底色 + 抓手 + 内容）一起淡出、下沉、微缩
        exit.animateTo(1f, Motion.exit())
        // ② 收尾：此刻卡片已经全透明、面板也是透明的，所以这一步的位移**看不见**。
        //    它只是把「面板已收起」这个状态交给 M3（遮罩由 M3 平滑收掉）。
        //    ⚠️ 不能和 ① 并行 —— 并行就是回到「两个时钟驱动同一屏」，
        //       那正是这一版要修的东西（见上面 `exit` 的说明）。
        sheetState.hide()
        // ③ 再从组合里摘掉它。屏幕上此刻已经没有任何属于它的像素，
        //    所以这一步不会产生可见的跳变
        onDismiss()
    }

    // 圆角形状取一次、两处用（`shape` 与 `clip`）—— 见文件里那条注释
    val cardShape = BottomSheetDefaults.ExpandedShape

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        // 底色由我们自己在内容里画（见上面 `exit` 的说明）：只有这样它才会
        // 和内容一起淡出，而不是留下一块**空白面板**继续往下滑
        containerColor = Color.Transparent,
        shape = cardShape,
        // 把手：「这里能下拉」唯一的提示。详见 [Grabber]。
        // ⚠️ 它必须跟着卡片一起淡：底色透明之后面板上只剩它还看得见，
        //    不淡的话就是「卡片没了，一条小横杠自己滑走」
        dragHandle = {
            GrabberBar(Modifier.graphicsLayer { alpha = 1f - exit.value })
        },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                // ⚠️ 顺序不能动：`graphicsLayer` 必须在最外层。
                //    `background` 是**外层**修饰符 —— 它先绘制，再进入内层的图层，
                //    所以图层写在它里面的话，那块底色不会跟着淡
                .graphicsLayer {
                    alpha = 1f - exit.value
                    // 「移除动画」时只留淡出、去掉位移与缩放 —— 与 Motion 的口径一致
                    // （减弱不是清零）。它读在绘制层里，不触发重组
                    translationY =
                        if (Motion.reduceMotion) 0f else exit.value * EXIT_SINK.toPx()
                    val s = if (Motion.reduceMotion) 1f else 1f - EXIT_SCALE * exit.value
                    scaleX = s
                    scaleY = s
                }
                .clip(cardShape)
                .background(Bg)
                .navigationBarsPadding()
                .padding(
                    start = Space.pageH,
                    end = Space.pageH,
                    // 顶距比别处小一档：上面那条抓手自己已经占了 18dp 的节奏，
                    // 再加 Space.block 会让词和卡片顶边之间空出一大块
                    top = Space.row,
                    bottom = Space.block,
                )
        ) {
            // ── 词 + 音标 ──────────────────────────────────────────────
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = lookup?.entry?.lemma ?: picked.surface,
                    style = AppText.section,
                    color = Ink,
                )
                val ipa = lookup?.entry?.ipa
                if (!ipa.isNullOrEmpty()) {
                    Spacer(Modifier.width(10.dp))
                    Text(text = "/$ipa/", style = AppText.gloss, color = InkFaint)
                }
            }

            // 用户点的是变形时要说一句 —— 否则「我点的是 signed，怎么显示 sign」
            if (lookup?.inflected == true) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "你点的是 ${picked.surface}，原形是 ${lookup.entry.lemma}",
                    style = AppText.pill,
                    color = InkFaint,
                )
            }

            Spacer(Modifier.height(14.dp))

            when {
                lookup == null -> NotFoundBlock(picked)
                else -> {
                    // ── 释义 ──────────────────────────────────────────
                    lookup.entry.defs.forEachIndexed { i, d ->
                        if (i > 0) Spacer(Modifier.height(5.dp))
                        Text(d, style = AppText.body, color = InkMid)
                    }
                    Spacer(Modifier.height(16.dp))
                    ContextBlock(picked)

                    val example = lookup.examples.firstOrNull()
                    if (example != null) {
                        Spacer(Modifier.height(14.dp))
                        ExampleBlock(example.en, example.zh)
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            AddButton(
                enabled = lookup != null && !alreadyIn,
                label = when {
                    lookup == null -> "暂未收录，无法加入"
                    alreadyIn -> "已在生词本"
                    else -> "加入生词本"
                },
                onClick = {
                    // 先置信号再回调：回调会让 alreadyIn 变 true（按钮随即变成
                    // 「已在生词本」，那一下就是确认），而收起由上面那个
                    // LaunchedEffect 负责 —— 两件事各自独立，互不阻塞
                    justAdded = true
                    onAdd()
                },
            )
        }
    }
}

/**
 * 查不到的时候。
 *
 * ⚠️ 这里**不显示一个空的释义块**（那看起来像加载失败），而是说清事实，
 * 并把「你听到的」留着 —— 那句话是用户此刻唯一确定有用的东西，
 * 而且它本来就是加词这个动作的起点。
 */
@Composable
private fun NotFoundBlock(picked: PickedWord) {
    Text(
        text = "这个词暂未收录",
        style = AppText.body,
        color = InkMid,
    )
    Spacer(Modifier.height(4.dp))
    // ⚠️ 这句话改过一次：原来写的是「词表按当前素材构建，新出现的人名、地名
    //    常常还查不到」——被 `verify-copy-audit.py` 拦下了，因为「词表」
    //    是实现层的东西（用户脑子里没有这个词）。
    //    现在这句话只说两件用户能验证的事：什么词查不到（人名地名）、
    //    以及**接下来会怎样**（下一期同步后就有）。
    Text(
        text = "新闻里新出现的人名、地名常常还没收录，素材更新一次就会补上。",
        style = AppText.gloss,
        color = InkFaint,
    )
    Spacer(Modifier.height(16.dp))
    ContextBlock(picked)
}

/** 「你听到的」—— 那句原话 + 中文 */
@Composable
private fun ContextBlock(picked: PickedWord) {
    Text("你听到的", style = AppText.label, color = InkFaint)
    Spacer(Modifier.height(6.dp))
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Hairline.copy(alpha = 0.22f))
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Column {
            Text(picked.context.sentence, style = AppText.line, color = Ink)
            if (!picked.context.translation.isNullOrEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(picked.context.translation, style = AppText.gloss, color = Gloss)
            }
        }
    }
}

@Composable
private fun ExampleBlock(en: String, zh: String) {
    Text("教材例句", style = AppText.label, color = InkFaint)
    Spacer(Modifier.height(6.dp))
    Text(en, style = AppText.line, color = InkMid)
    Spacer(Modifier.height(4.dp))
    Text(zh, style = AppText.gloss, color = Gloss)
}

@Composable
private fun AddButton(enabled: Boolean, label: String, onClick: () -> Unit) {
    // 底色跟着可用性补间。
    //
    // ⚠️ 这一下原来是**硬切**的：点「加入生词本」的同一帧，底色从琥珀直接翻成灰。
    //    那正是用户看的那一帧 —— 一个硬邦邦的状态翻转，紧跟着 1 秒的静止。
    //    整段退场动画里最容易显得「不丝滑」的其实是这里，而不是后面的下滑。
    //
    // 时长取 Quick（120ms）：它只是「确认你点到了」，不该让人等。
    val fill by animateColorAsState(
        targetValue = if (enabled) Amber else Hairline.copy(alpha = 0.4f),
        animationSpec = Motion.quick(),
        label = "addButtonFill",
    )
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(fill)
            .pressFeedback(enabled = enabled, onClick = onClick)
            .padding(vertical = 15.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = AppText.title,
            // 不可用时用 InkFaint 而不是半透明的 Ink —— 后者在浅色背景上
            // 会变成一块糊掉的灰，看起来像坏了
            color = if (enabled) Bg else InkFaint,
        )
    }
}

// 这里原来还有一个 AddedToast（「已加入生词本」的浮动提示）。
// 3.2.0 删掉了：它是**没人调用**的死代码，而且它代表的正是这一版明确不要的东西 ——
// 「加完弹一条文字提醒」。现在的确认是按钮自己变成「已在生词本」，
// 然后 1 秒后卡片收起（见文件头）。
//
// ⚠️ 留着它的代价不是几行代码，而是**下一个人会顺手用它** ——
//    一个已经写好的提示组件，在需要「给个反馈」的时候几乎必然被拿来用。
//    那会把刚定下的「不加文字提醒」悄悄推翻，而且不会有任何断言拦他。
