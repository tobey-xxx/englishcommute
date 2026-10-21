package com.example.englishcommute.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.englishcommute.diag.DiagLog
import com.example.englishcommute.player.SrtTrack
import com.example.englishcommute.player.SubtitleLine
import com.example.englishcommute.settings.SpeedPolicy
import com.example.englishcommute.ui.theme.Amber
import com.example.englishcommute.ui.theme.AmberFaint
import com.example.englishcommute.ui.theme.AppText
import com.example.englishcommute.ui.theme.Bg
import com.example.englishcommute.ui.theme.BgRaised
import com.example.englishcommute.ui.theme.Crimson
import com.example.englishcommute.ui.theme.Gloss
import com.example.englishcommute.ui.theme.Glyph
import com.example.englishcommute.ui.theme.GlyphIcon
import com.example.englishcommute.ui.theme.Hairline
import com.example.englishcommute.ui.theme.HairlineStrong
import com.example.englishcommute.ui.theme.Ink
import com.example.englishcommute.ui.theme.InkDim
import com.example.englishcommute.ui.theme.InkFaint
import com.example.englishcommute.ui.theme.InkMid
import com.example.englishcommute.ui.theme.Motion
import com.example.englishcommute.ui.theme.Radius
import com.example.englishcommute.ui.theme.Space
import com.example.englishcommute.ui.theme.pressFeedback
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextLayoutResult
import com.example.englishcommute.EnglishCommuteApp
import com.example.englishcommute.word.WordBoundary
import com.example.englishcommute.word.WordContext
import com.example.englishcommute.word.WordLookup

/**
 * 播放页：整篇文稿 + 当前句高亮 + 中文对照 + 跟随滚动。
 *
 * ## 四个刻意的设计
 *
 * 1. **字幕区占满剩余空间，控制区压到底部。** 通勤时手不稳，控制区越靠下越好点。
 * 2. **手动滚动即锁定。** 用户往上翻想回看前几句时，如果字幕还在自动往下追，
 *    读两行就被拽走 —— 这是同类 App 最让人恼火的地方。所以检测到拖动就停止跟随，
 *    并在底部常驻一个「回到当前句」，绝不偷偷恢复。
 * 3. **点任意一句即跳转。** 听不懂的地方点回那句重听，是听力训练的核心动作。
 * 4. **英文在上、中文在下。** 通勤场景里用户是「先听再看」，中文是辅助理解的
 *    第二眼信息，所以它必须在英文**下面**、字号更小、颜色更弱 —— 反过来
 *    会让眼睛先读到中文，英语听力训练就废了。
 *
 * ## 中文对照什么时候出现
 *
 * 进入页面后，只要有字幕且没翻过，就自动开始翻。翻译在后台逐句推进，
 * 每 20 句落一次盘，界面跟着一行行长出来。用户不需要按任何按钮 ——
 * 「对照」是这个 App 的默认形态，不是可选功能，所以不该藏在开关后面。
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun PlayerScreen(
    episodeId: String,
    /**
     * 「打开播放页」的第几次（自增计数，1.2.10 修）。
     *
     * 用途只有一个：让每次打开都**复位下拉关闭留下的位移**。
     * 为什么不能靠 [episodeId] 变化来复位，见下面 `LaunchedEffect(openNonce)` 那段 ——
     * 那是一个真机上复现出来的 bug（「点迷你条进不去了」）。
     */
    openNonce: Int = 0,
    /**
     * 共享元素形变的两端（1.2.9，§7）。
     *
     * [morphScope] 为 null 表示形变关掉了（见 [SHARED_MORPH]）；[animatedScope]
     * 是包住本页的那个 `AnimatedVisibility` —— `sharedElement` 需要它来判断
     * 「这一端是在进还是在出」。
     */
    morphScope: SharedTransitionScope? = null,
    animatedScope: AnimatedVisibilityScope? = null,
    onBack: () -> Unit,
    vm: PlayerViewModel = viewModel(),
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    // 当前句号单独一条流：换句才变（几秒一次），而 ui 是每 250 ms 一次。
    // 字幕列表订阅这一个，就不会被播放位置的更新带着一起重组
    val currentIndex by vm.currentLineIndex.collectAsStateWithLifecycle()
    val lines by vm.lines.collectAsStateWithLifecycle()
    val translations by vm.translations.collectAsStateWithLifecycle()
    val translateStatus by vm.translateStatus.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    // ── 长按选词 -> 词卡（P1.3 / P1.4）──────────────────────────────────
    //
    // ⚠️ 这里用的是**同一份 WordViewModel**（Activity 作用域，与生词页共用）——
    //    「在播放页加的词，切到生词页立刻能看到」靠的就是这个。
    //    各建一份的话，用户加完词切过去会发现列表是空的。
    val wordVm: WordViewModel = viewModel()
    val inWordBook by wordVm.allLemmas.collectAsStateWithLifecycle()
    val pickScope = rememberCoroutineScope()
    val appContext = LocalContext.current.applicationContext as EnglishCommuteApp
    var pickedWord by remember { mutableStateOf<PickedWord?>(null) }
    var pickedLookup by remember { mutableStateOf<WordLookup?>(null) }

    LaunchedEffect(Unit) { wordVm.start() }

    val onPickWord: (String, SubtitleLine, String?) -> Unit = { word, line, translation ->
        // 期名从仓库现查 —— PlayerScreen 只拿到 episodeId，而「你听到的」那一段
        // 要显示这是哪一期。仓库是唯一真相源，不在这里缓存一份标题
        val episode = appContext.container.repository.episodes.value.firstOrNull { it.id == episodeId }
        pickedWord = PickedWord(
            surface = word,
            context = WordContext(
                episodeId = episodeId,
                episodeTitle = episode?.title.orEmpty(),
                lineStartMs = line.startMs,
                lineEndMs = line.endMs,
                sentence = line.text,
                translation = translation,
            ),
        )
        // 先清空上一次的查询结果：不清的话，长按第二个词时会先闪一下上一个词的释义
        pickedLookup = null
        pickScope.launch { pickedLookup = wordVm.lookup(word) }
    }

    // ── 下拉关闭（1.2.9，Apple §2 / §3 / §5 / §6）──────────────────────────
    //
    // ## 为什么只挂在**顶部**那一块
    //
    // 整页的纵向拖动已经被两处接管：中段是字幕列表（那是滚动，用户往上翻
    // 回看前几句是核心动作），底部控制区还有进度条自己的拖动手势。
    // 顶部这一条是唯一「没有别的纵向手势」的区域，而且它本来就是
    // iOS / Android 上「把这一层往下拽走」的通用位置。
    //
    // ## 偏移为什么是 Animatable 而不是 animateFloatAsState
    //
    // §3 要的是「随时可抓可反向、从当前值出发」。`animateFloatAsState` 的目标值
    // 只能由状态给，中途抓住它没有入口；`Animatable` 可以被 `snapTo` 直接中断，
    // 也就是手指按下那一刻动画就交出控制权 —— 这正是 §3 说的「打断」。
    val density = LocalDensity.current
    val offsetY = remember { Animatable(0f) }
    val dragScope = rememberCoroutineScope()
    val dismissAtPx = with(density) { DISMISS_DISTANCE.toPx() }
    val flingPx = with(density) { DISMISS_FLING.toPx() }
    // 走完整屏还多留一截：弹簧会过冲，刚好走到边界会露出下面一条缝
    val offscreenPx = with(density) { OFFSCREEN_DISTANCE.toPx() }

    // ⚠️ 每次「打开播放页」都要把位移复位（1.2.10 修一个真机复现的 bug）。
    //
    // ## 为什么不能靠 `LaunchedEffect(episodeId)`
    //
    // 因为 PlayerScreen 的实例**可能被保留**：`AnimatedVisibility` 的退出是 220ms，
    // 用户在这段时间内又点开时，退出会被取消、本页不销毁 ——
    // 于是 `episodeId` 没变、那个 effect 不重跑，而 `offsetY` 还停在
    // 上一轮被拽走的 1000dp 上。页面因此停在**屏幕外面**，
    // 用户看到的是「点了没反应」。
    //
    // 真机日志把这件事摊得很干净（1.2.9 的 diag）：
    // ```
    // 13:12:44.170  退出播放页
    // 13:12:44.323  迷你条点开 → 播放页     ← 间隔 153ms < 220ms
    // （此后连续 9 次点开，全都只有「点开」、没有「进入播放页」）
    // ```
    // 「点了有日志、页面却没了」这个组合，只可能是「页面在、只是被移出了屏幕」。
    //
    // ## 为什么是自增计数而不是布尔量
    //
    // 连着打开两次都要能触发，而 `true` 第二次不再是变化 ——
    // 与 `focusPlaying` / `menuNonce` 是同一条纪律。
    LaunchedEffect(openNonce) { offsetY.snapTo(0f) }

    // ── 材质浮层的高度：**量**出来的，不是写死的（1.2.10 播放页材质化）──
    //
    // 字幕层现在铺满整屏、从两块材质下面穿过去，所以它必须知道材质各占多高 ——
    // 那决定字幕首尾各让出多少（否则第一句被顶栏压住、最后一句被控制区压住），
    // 也决定「当前句停在哪」这件事怎么算（见下面那个滚动 effect）。
    //
    // 高度必须量：顶栏的标题可能两行，控制区那条翻译状态行可能不出现，两者都是变的。
    // 初值给一个接近的估算（见 [TOP_BAR_ESTIMATE]），免得第一帧字幕从材质底下露一下。
    var topBarPx by remember { mutableIntStateOf(with(density) { TOP_BAR_ESTIMATE.roundToPx() }) }
    var controlPx by remember { mutableIntStateOf(with(density) { CONTROL_ESTIMATE.roundToPx() }) }

    // 播放页是覆盖在主界面之上的全屏层，不在导航栈里 ——
    // 不接管系统返回键的话，按返回会直接退出 App，而用户的心理预期是「回到列表」。
    // 音频不会因此中断：播放器在 Service 里，退出播放页只是断开控制器。
    BackHandler { onBack() }

    LaunchedEffect(episodeId) { vm.start(episodeId) }

    // 字幕一到就去读中文对照。key 里带 lines.size 是因为字幕是异步解析的：
    // 首次进入时 lines 还是空的，只依赖 episodeId 会在这时候空跑一次就再不触发。
    //
    // ⚠️ 2.1.0：这一句原来是「字幕一到就自动开始翻译」——那时翻译在**本机**跑，
    //    要 1–3 分钟，所以必须尽早启动。现在只是读一次盘（还没有就等它到，
    //    见 PlayerViewModel.refreshTranslation），启动时机的敏感度低得多，
    //    但**仍然要在这里触发**：它也是「中文还没到就起一个等待循环」的入口。
    LaunchedEffect(episodeId, lines.size) {
        if (lines.isNotEmpty()) vm.refreshTranslation()
    }

    // 出手拖动 = 用户想自己看。只认 DragInteraction：
    // 用「滚动中」判断会把程序触发的自动滚动也算进去，导致刚滚到当前句就自我锁定
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) vm.setFollowing(false)
        }
    }

    // 当前句变化 → 对齐一次；**当前句的行高变了**（中文对照到达、系统字号变化）→ 再对齐一次。
    //
    // ⚠️ key 里用 currentIndex 而不是 positionMs：后者每 250 ms 变一次，
    //    会把滚动动画反复打断，看起来像在抖。
    //
    // ## ★★ 为什么必须跟着**行高**重排（2.1.7 补的第二半）
    //
    // 落点是「按**当时量到的行高**算出来的」。而中文对照是**异步到达**的：还没到时
    // 那一行只画一个占位下划线，行高会小一大截。于是「先按小行高算落点 → 中文到了
    // 行高变大」—— **而增长是向下的**，尾巴就伸到控制区下面去了。而 `currentIndex`
    // 没变，原来那个一次性 effect 不会重跑，它就一直停在被切的位置。
    //
    // 用户 17:52 录屏里看到的正是这个形态：**第一句**（列表还在最顶上、落点被钳住）
    // 看着正常，**第二句的中文只露出一行、其余被控制区切掉** —— 他说「第二句话就出问题了」。
    //
    // ⚠️ 判据是「当前那一项的行高」，它**只跟内容有关**：对齐本身不会改行高，
    //    所以这个循环是**收敛**的（不会出现「垫上 → 装不下 → 取消」那种来回抖）。
    // ⚠️ `distinctUntilChanged` 去重：同一个行高反复发射不会重复滚动。
    // ⚠️ `itemPx == null`（这一项还没被渲染，跳转得很远）→ 按「可能很长」先滚过去
    //    把它变成可见的；随后 flow 会发出真实行高 → 自动再对齐一次。
    LaunchedEffect(listState, currentIndex, ui.following, lines.size) {
        if (!ui.following || currentIndex < 0 || lines.isEmpty()) return@LaunchedEffect

        // 对齐一次。@param itemPx 当前句的行高；null = 还没被渲染
        suspend fun alignOnce(itemPx: Int?, pass: Int) {
            val viewport = listState.layoutInfo.viewportSize.height
            if (viewport <= 0) return
            // ⚠️ 1.2.10：字幕层铺满整屏之后，视口的**顶和底都被材质盖着** ——
            //    「当前句停在上三分之一」必须按**看得见的那一段**算，
            //    否则它正好落进顶栏底下（看起来像「自动滚动停错了位置」）。
            val visible = (viewport - topBarPx - controlPx).coerceAtLeast(1)
            // ★ 2.1.2：字幕按句合并之后**单条变长了**（原来一条 2–4 秒，现在是一整句，
            //   中文可能有十几行）。原来一律停在「可见区的上三分之一」，于是长度超过
            //   下三分之二的那些句子**开头就被推到了顶栏底下** —— 用户切到下一句时
            //   读到的是一句从中间开始的话。
            //
            //   判据是「按原对齐方式放下去，剩下的空间够不够装下这一句」：
            //     · 够  → 保持原样（上三分之一，下方还能瞥见下一句）
            //     · 不够 → 贴到顶栏下面，让它**从头开始**
            //
            // ★★ 2.1.7：判据要**留余量**。`restPx` 是「停在上三分之一之后下方还剩多少」，
            //   正好等于它时，底边会**正好压在控制区上沿** —— 任何测量误差（或中文晚到
            //   一帧）立刻变成「被切掉一截」。所以只剩不到一行的空间时宁可贴顶栏下：
            //   那种句子「下方还能瞥见下一句」本来就是零，贴顶是更稳的选择。
            val restPx = visible - visible / 3
            val marginPx = with(density) { SUBTITLE_FIT_MARGIN.roundToPx() }
            val longLine = itemPx == null || itemPx > restPx - marginPx
            // 希望这一行的**顶边**落在「视口顶往下」多少像素处
            val targetTopPx = if (longLine) topBarPx else topBarPx + visible / 3
            // ⚠️★ 2.1.6：`scrollOffset = 0` 不是「贴到屏幕顶」，而是「贴到**内容区起点**」——
            //    这个 LazyColumn 的顶部内边距是 `topBarPx + Space.block`（字幕要从两块材质
            //    下面穿过去）。所以写 `-targetTopPx` 会把这一行停在**比目标低一整个内边距**
            //    的位置：
            //
            //        内容区起点 = 视口顶 + (topBarPx + Space.block)
            //        写 `-targetTopPx`               → 行顶 = 内容区起点 + targetTopPx   ← 低了
            //        写 `paddingTopPx - targetTopPx` → 行顶 = 视口顶 + targetTopPx      ✓
            //
            //    真机上：短句只是整体偏低（看不出问题），**长句的尾巴被控制区切掉**。
            //    ⚠️ 2.1.2 只改了「用哪一种对齐」，两种对齐都带着同一个偏移 ——
            //      所以他的感觉是「你完全没动」。**只改对齐、不改原点，等于没改。**
            //
            //    ⚠️ 这个「0 = 内容区起点」不是猜的：上一轮排查「切筛选往下沉 30dp」时，
            //       从录屏量出 `(下标, 0)` 落点比吸顶线低整整一个 `Space.section`，
            //       与内边距精确吻合（见 LibraryAnchor 的说明）。
            val paddingTopPx = topBarPx + with(density) { Space.block.roundToPx() }
            runCatching {
                listState.animateScrollToItem(
                    currentIndex,
                    scrollOffset = paddingTopPx - targetTopPx,
                )
            }
            // ★ 把这一次跟随**量出来**报掉 —— 它是「长句显示不全」唯一的凭据。
            //   行高与可见区一摆，就能分清两件下一步完全不同的事：
            //     · 行高 ≤ 可见区 → 本来能完整显示 → 是**落位**算错了（改代码）
            //     · 行高 > 可见区 → 这一句本来就有两屏高 → 只能保证**开头**可见
            //   ⚠️ 「目标」与「实到」分两个数报：只报意图的话，日志会与屏幕不一致 ——
            //      2.1.4 那三轮就是这么被带偏的。
            //   ⚠️ 末尾的「重排」= 这一行的高在落位**之后**又变了（中文刚到）。
            //      出现它就证明「按旧行高算的落点已经过期」这件事真的在发生。
            val landedPx = listState.layoutInfo.visibleItemsInfo
                .firstOrNull { it.index == currentIndex }
                ?.offset
            DiagLog.act(
                "跟随定位 · 第 ${currentIndex + 1}/${lines.size} 句 · " +
                    "行高 ${itemPx ?: -1}px · 可见区 ${visible}px · " +
                    "目标顶边 ${targetTopPx}px · " +
                    "实到 ${landedPx?.plus(paddingTopPx) ?: -1}px · " +
                    (if (longLine) "贴顶栏下" else "上三分之一") +
                    (if (pass > 1) " · 重排" else ""),
            )
        }

        var pass = 0
        snapshotFlow {
            listState.layoutInfo.visibleItemsInfo
                .firstOrNull { it.index == currentIndex }
                ?.size
        }.distinctUntilChanged().collect { itemPx ->
            pass += 1
            alignOnce(itemPx, pass)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            // 下拉关闭的位移。两处讲究：
            //
            // ① `graphicsLayer` 而不是 `offset { }`（1.2.10 改）。
            //    `offset { }` 是**布局层**的位移 —— 每帧都会让整棵子树重新测量与布局，
            //    而这里面装着 8–12 行字幕。`graphicsLayer` 只改绘制用的变换矩阵，
            //    不触发布局。§11「只动 transform / opacity」本来就是这条要求，
            //    而这里曾经违反它（改之前每次拖动都在重新布局整页）。
            // ② 放在 `background` 之前：跟着手指移动的是**整块页面**（含底色），
            //    而不是「内容在动、底色留在原地」。
            .graphicsLayer { translationY = offsetY.value }
            .background(Bg),
    ) {
        // ── ① 字幕层：铺满整屏 ──────────────────────────────────────────
        //
        // 它从顶栏与控制区**下面穿过去** —— 这是设计稿 §12 在播放页的那一笔。
        // 首尾的 contentPadding 让出两块材质的高度，于是内容是「滚到材质下面没入」，
        // 而不是「被裁在材质边缘」；边界那一段由 [ScrollEdgeFade] 渐隐。
        Box(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            when {
                ui.error != null -> ErrorHint(ui.error!!)
                ui.loading -> LoadingHint()
                lines.isEmpty() -> NoSubtitleHint()
                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = Space.pageH,
                        end = Space.pageH,
                        top = with(density) { topBarPx.toDp() } + Space.block,
                        bottom = with(density) { controlPx.toDp() } + Space.block,
                    ),
                ) {
                    itemsIndexed(lines, key = { _, l -> l.index }) { index, line ->
                        SubtitleRow(
                            line = line,
                            translation = translations.getOrNull(index),
                            // 用派生流而不是 `ui.currentIndex`：后者每 250 ms 发一次，
                            // 会让所有可见的 8–12 行每秒重组 4 遍 ——
                            // 而真正需要变的只有两行（离开的和进入的）
                            isCurrent = index == currentIndex,
                            onClick = { vm.seekToLine(index) },
                            onWordLongPress = { word ->
                                onPickWord(word, line, translations.getOrNull(index))
                            },
                        )
                    }
                }
            }

            if (!ui.following && lines.isNotEmpty()) {
                ResumeFollowBar(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        // 浮在底部材质**之上** —— 所以要让开它的高度，
                        // 否则两块会叠在一起
                        .padding(bottom = with(density) { controlPx.toDp() } + 14.dp),
                    onClick = { vm.setFollowing(true) },
                )
            }

            // 词卡。加入成功**不关卡片**，而是把按钮变成「已在生词本」——
            // 关掉的话用户不知道自己点中了没有，而这里是「记下一个词」这种
            // 不可逆（至少很容易忘）的动作，确认比流畅重要
            pickedWord?.let { picked ->
                WordCardSheet(
                    picked = picked,
                    lookup = pickedLookup,
                    alreadyIn = pickedLookup?.let { lk -> lk.entry.lemma in inWordBook } ?: false,
                    onAdd = {
                        pickedLookup?.let { lk -> wordVm.add(lk, picked.context) }
                    },
                    onDismiss = { pickedWord = null },
                )
            }
        }

        // ── ② 顶部材质 ────────────────────────────────────────────────
        //
        // 抓手 + 顶栏整块是「下拉关闭」的手势区。抓手（38×4）是 §10 的「可抓提示」，
        // 但热区是**整块**，不是只有那条小横条 —— 18dp 在通勤路上一只手抓不住。
        // 顶栏里「返回」和「星标」的点击**不受影响**：拖动检测只在意
        // 「移动超过 touch slop」，不是点击。
        Column(Modifier.align(Alignment.TopCenter)) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Bg)
                    .statusBarsPadding()
                    .onSizeChanged { topBarPx = it.height },
            ) {
                DragHandleBar(
                    onDrag = { delta ->
                        // snapTo 顺带中断正在跑的回弹或关闭动画 —— 这就是 §3 的「可打断」。
                        // 用 launch 是因为手势回调不是 suspend：每个事件一个协程，
                        // 它们在同一调度器上按序执行，累加因此不会丢
                        dragScope.launch {
                            offsetY.snapTo((offsetY.value + delta).coerceAtLeast(0f))
                        }
                    },
                    onRelease = { velocity ->
                        // §6：**先看速度的符号，再看位置**。
                        // 手指只拖了 20dp 但甩得很快 → 应该关，而不是「没过半就弹回来」
                        val goAway = when {
                            velocity > flingPx -> true
                            velocity < -flingPx -> false
                            else -> offsetY.value > dismissAtPx
                        }
                        dragScope.launch {
                            if (goAway) {
                                // 让弹簧把这一下走完，再真正退出。外层 AnimatedVisibility
                                // 的退出位移会**叠在**这个偏移之上，两段同向，
                                // 观感是被继续推下去，而不是「弹回来再滑走」
                                offsetY.animateTo(offscreenPx, Motion.flick(), initialVelocity = velocity)
                                onBack()
                            } else {
                                // 回弹也走同一条弹簧（§4：带惯性的手势才回弹）。
                                // 手指往反方向甩时 velocity 是负的，动画会顺着那个方向先动一点
                                offsetY.animateTo(0f, Motion.flick(), initialVelocity = velocity)
                            }
                        }
                    },
                ) {
                    PlayerTopBar(
                        title = ui.title,
                        sourceName = ui.sourceName,
                        sourceId = ui.sourceId,
                        artworkUrl = ui.artworkUrl,
                        lineCount = lines.size,
                        currentIndex = ui.currentIndex,
                        queueIndex = ui.queueIndex,
                        queueSize = ui.queueSize,
                        favorite = ui.favorite,
                        morphScope = morphScope,
                        animatedScope = animatedScope,
                        onBack = onBack,
                        onToggleFavorite = vm::toggleFavorite,
                    )
                }
            }
            // 字幕「没入」材质，而不是「被切断」
            ScrollEdgeFade(atTop = true)
        }

        // ── ③ 底部材质 ────────────────────────────────────────────────
        Column(Modifier.align(Alignment.BottomCenter)) {
            ScrollEdgeFade(atTop = false)
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(Bg)
                    .navigationBarsPadding()
                    .onSizeChanged { controlPx = it.height },
            ) {
                ControlPanel(
                    ui = ui,
                    translateStatus = translateStatus,
                    canStepLine = lines.isNotEmpty(),
                    onSeek = vm::seekTo,
                    onTogglePlay = vm::togglePlay,
                    onSkip = vm::skipBy,
                    onStepLine = vm::stepLine,
                    onSpeedPercent = vm::setSpeedPercent,
                    onResetSpeed = vm::resetSpeed,
                    onRepeat = vm::repeatCurrentLine,
                    onPrev = vm::previousEpisode,
                    onNext = vm::nextEpisode,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 下拉关闭
// ---------------------------------------------------------------------------

/** 拖过这么远松手就算「要走」。约 1/6 屏高 —— 低于它更可能是误触，而不是意图 */
private val DISMISS_DISTANCE = 132.dp

/**
 * 甩得比这个快就算「要走」，**哪怕只拖了 20dp**（§6「看速度的符号，不看过了没过半」）。
 *
 * 900dp/s ≈ 每秒 1/5 屏。定得再低的话，列表里那种「随手划一下」的力度就会
 * 关掉整页 —— 这个手势的代价是整页消失，阈值该偏保守。
 */
private val DISMISS_FLING = 900.dp

/**
 * 「已经走了」的位移：比一屏还多一截。
 *
 * 不能刚好等于屏高 —— [Motion.flick] 是欠阻尼弹簧，会**过冲**，
 * 若正好停在屏高那一刻再弹回来，就会把已经滑走的页面又拉回来一条边。
 */
private val OFFSCREEN_DISTANCE = 1000.dp

/**
 * 材质浮层高度的**初值**（1.2.10）。
 *
 * 这两块高度是 `onSizeChanged` 量出来的（顶栏的标题可能两行、控制区的翻译状态行
 * 可能不出现，两者都是变的）。但量的结果是**下一帧**才有的 ——
 * 如果初值给 0，第一帧字幕会从材质下面露出来一下。
 *
 * 所以给一个接近的估算值兜底。它们不需要准，只需要「第一帧别露馅」。
 */
private val TOP_BAR_ESTIMATE = 92.dp
private val CONTROL_ESTIMATE = 216.dp

/** Scroll edge 渐隐带的高度。太短看不出「渐隐」，太长会吃掉可读的字幕 */
private val SCROLL_EDGE_FADE = 16.dp

/**
 * 抓手 + 顶栏：下拉关闭的手势区。
 *
 * ## 热区为什么是整块，不是那条小横条
 *
 * 抓手（38×4）是 §10 说的「可抓提示」——它让用户知道这里能往下拽。
 * 但**手势挂在整个 `Column` 上**：一个 18dp 高的热区在通勤路上一只手是抓不住的，
 * 而顶部这一整块本来就没有别的纵向手势（中段是字幕滚动、底部是进度条）。
 *
 * ⚠️ 抓手本身（尺寸 + 配色）**不在这个文件里** —— 3.2.0 起它和词卡的把手
 *    共用 `ui/Grabber.kt` 那一份。理由是词卡上曾经漏掉它，而漏掉之后
 *    界面完全正常、只是没人知道能下拉（见 [Grabber] 的说明）。
 *    两处各写一遍尺寸的话，「改一处、另一处不动」不会报任何错。
 *
 * ## 速度为什么要手动采
 *
 * §5：手势结束那一刻，动画必须**接着手指的速度**跑。少了这一步，
 * 拖拽与动画之间会有一道肉眼可见的接缝（手指停了、画面还在滑）。
 * `detectVerticalDragGestures` 不提供速度，所以这里自己喂 `VelocityTracker`。
 */
@Composable
private fun DragHandleBar(
    /** 拖动增量（px），累加交给调用方 —— 它才知道边界怎么钳 */
    onDrag: (Float) -> Unit,
    /** 松手速度，px/s，向下为正。负值 = 往回甩 */
    onRelease: (Float) -> Unit,
    content: @Composable () -> Unit,
) {
    val tracker = remember { VelocityTracker() }
    Column(
        Modifier
            .fillMaxWidth()
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = { tracker.resetTracking() },
                    onDragEnd = { onRelease(tracker.calculateVelocity().y) },
                    // 取消（手势被别的层抢走、或指针异常消失）按「速度 0」处理 ——
                    // 也就是纯按位移判定，绝不会因为一个取消事件把页面甩走
                    onDragCancel = { onRelease(0f) },
                ) { change, dragAmount ->
                    change.consume()
                    tracker.addPosition(change.uptimeMillis, change.position)
                    onDrag(dragAmount)
                }
            },
    ) {
        // 抓手只是视觉；手势在**上一层**的 Column 上（整块顶栏都是热区）
        GrabberBar()
        content()
    }
}

// ---------------------------------------------------------------------------
// 材质浮层
// ---------------------------------------------------------------------------

/**
 * Scroll edge effect（§12）：用一小段**渐隐**替代 1px 硬分隔线。
 *
 * ## 它解决什么
 *
 * 字幕层铺满整屏、从顶栏与控制区下面穿过去之后，内容在材质边缘是**被硬切**的 ——
 * 一行字走到边界上突然没了。渐隐带让它在 16dp 内淡出，读起来是
 * 「滚到浮层下面去了」，而不是「被裁掉了」。
 *
 * ## ⚠️ 这不是设计稿里那种「模糊」
 *
 * 设计稿画的是 `backdrop-filter: blur()`。Compose **没有**等价物
 * （`Modifier.blur` 模糊的是**自己**而不是身后），所以这里退一档：
 * 渐隐到材质底色。观感上「内容没入材质」这一层意思保住了，
 * 「透过毛玻璃看到它还在」那一层没有 —— 这与 1.2.8 底部材质那条降级是同一件事，
 * 也写在清单的「与设计稿的偏差」里。
 *
 * @param atTop true = 这块材质在屏幕**上方**，所以是「从材质色渐隐到透明」（向下）；
 *              false = 在下方，反过来
 */
@Composable
private fun ScrollEdgeFade(atTop: Boolean) {
    val c = Bg
    Box(
        Modifier
            .fillMaxWidth()
            .height(SCROLL_EDGE_FADE)
            .background(
                Brush.verticalGradient(
                    if (atTop) listOf(c, c.copy(alpha = 0f))
                    else listOf(c.copy(alpha = 0f), c)
                )
            )
    )
}

// ---------------------------------------------------------------------------
// 顶栏
// ---------------------------------------------------------------------------

/**
 * 顶栏。
 *
 * 标题只给一行 —— 播客标题动辄两三行，占满顶部会把真正要看的字幕挤下去。
 * 完整的标题在素材库里本来就能看到，这里只需要「我在听哪条」。
 *
 * ## 封面为什么是 44dp 的小图，而不是播放器惯用的大图
 *
 * 这个页面的主体是**字幕**，不是封面。Spotify 那种「大封面占掉上半屏」的
 * 布局搬过来，代价是字幕可视行数直接少三分之一 —— 而字幕正是这个 App
 * 存在的理由。
 *
 * 所以封面缩到与返回键等宽（44dp）放进顶栏：它承担的是「一眼认出在听哪一档」
 * 和「与列表/迷你条是同一个东西」这两个识别作用，而不是视觉主角。
 * 尺寸与返回键一致还有个好处 —— 左边两个 44dp 方块自然对齐，不需要额外调间距。
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun PlayerTopBar(
    title: String,
    sourceName: String,
    sourceId: String,
    artworkUrl: String,
    lineCount: Int,
    currentIndex: Int,
    queueIndex: Int,
    queueSize: Int,
    favorite: Boolean,
    /** 形变用。两者都为 null 时不加共享元素（等价于 [SHARED_MORPH] 关闭） */
    morphScope: SharedTransitionScope?,
    animatedScope: AnimatedVisibilityScope?,
    onBack: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 6.dp, end = 6.dp, top = 4.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .pressFeedback(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            GlyphIcon(Glyph.ChevronLeft, size = 21.dp, tint = Ink, strokeWidth = 1.8f)
        }

        // ★ 1.2.9：这张封面与迷你条那张共享同一个键（见 ui/Morph.kt），
        //   使得「点迷你条进入播放页」时它是**长过来**的，而不是在顶部重新出现。
        //
        // ⚠️ modifier 的顺序不能换：`sharedElement` 必须紧贴在 `size(44.dp)` 外面。
        //    Artwork 内部是 `modifier.size(size)`，所以把 sharedElement 写在
        //    `padding(end = 12.dp)` **之后**，共享的 bounds 才正好是那 44dp 方形；
        //    写在 padding 之前会连 12dp 间距一起算进去 —— 两端尺寸对不上
        //    （一端含间距、一端不含），形变中途会歪一下。
        val coverMorph: Modifier = if (morphScope != null && animatedScope != null) {
            with(morphScope) {
                Modifier.sharedElement(
                    rememberSharedContentState(MORPH_COVER_KEY),
                    animatedScope,
                )
            }
        } else {
            Modifier
        }
        Artwork(
            sourceId = sourceId,
            artworkUrl = artworkUrl,
            placeholder = sourceName,
            size = 44.dp,
            modifier = Modifier.padding(end = 12.dp).then(coverMorph),
        )

        Column(Modifier.weight(1f)) {
            Text(
                text = title.ifBlank { "加载中…" },
                style = AppText.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = buildString {
                    append(sourceName)
                    // 队列位置放在句序号前面：它变化得少（一集才变一次），
                    // 而句序号每几秒就跳一次，放前面会让整行一直在抖
                    if (queueSize > 1) {
                        append(" · 第 ")
                        append(queueIndex.coerceAtLeast(1))
                        append("/")
                        append(queueSize)
                        append(" 集")
                    }
                    if (lineCount > 0) {
                        append(" · 第 ")
                        append(if (currentIndex < 0) 1 else currentIndex + 1)
                        append("/")
                        append(lineCount)
                        append(" 句")
                    }
                },
                style = AppText.digit,
                color = InkFaint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // 收藏放在顶栏而不是控制区：控制区那一行全是「和播放有关」的动作，
        // 星标是「给这条打标记」，混在一起会让五个播放按钮里多出一个异类
        Box(
            modifier = Modifier
                .size(44.dp)
                .pressFeedback(onClick = onToggleFavorite),
            contentAlignment = Alignment.Center,
        ) {
            GlyphIcon(
                glyph = if (favorite) Glyph.StarFilled else Glyph.Star,
                size = 19.dp,
                tint = if (favorite) Amber else InkFaint,
                strokeWidth = 1.5f,
            )
        }
    }
    // ⚠️ 这里原来有一条 1px Hairline（顶栏与字幕之间的硬分隔线），1.2.10 删掉了：
    //    字幕层现在从顶栏**下面**穿过去，一条横线会把「穿过」这件事打断 ——
    //    边界改由外层的 ScrollEdgeFade 渐隐承担。
    //
    //    注意这个函数**不再画那条线**，所以它现在只负责内容与内边距；
    //    材质底（`background(Bg)`）与状态栏内边距由调用方
    //    （PlayerScreen 里的顶部材质层）提供 —— 那里还要 onSizeChanged 量高度。
}

// ---------------------------------------------------------------------------
// 字幕
// ---------------------------------------------------------------------------

/**
 * 一句字幕：时间 + 英文 + 中文。
 *
 * 当前句用**左侧 2dp 琥珀竖线 + 极淡琥珀底**标记，而不是整块高亮背景。
 * 整块高亮会把一段文字变成一张「卡片」，页面上同时悬浮着十几张卡片时，
 * 视线找不到落点；一根竖线则始终是一条连续的时间轴刻度。
 *
 * 竖线用 [drawBehind] 画而不是塞一个 `Box` 进 `Row`：后者要配
 * `IntrinsicSize.Min` 才能撑满高度，在 `LazyColumn` 的每一行上做 intrinsic
 * 测量是没必要的开销。
 */
@Composable
private fun SubtitleRow(
    line: SubtitleLine,
    translation: String?,
    isCurrent: Boolean,
    onClick: () -> Unit,
    /**
     * 长按某个词，参数是**手指底下那个词的文本**（边界已经切好，见 [WordBoundary]）。
     *
     * ⚠️ 与 [onClick] 的分工必须保持清楚：**单击整行 = 跳回这一句重听**，
     * 那是播放页的核心交互，长按选词**不能**动它一个字节。
     * 手势只加在下面那个 `Text` 上，且只处理长按 —— 单击会继续冒泡到外层的 `pressFeedback`。
     */
    onWordLongPress: (String) -> Unit,
) {
    // ## 1.2.0：高亮迁移不再引起布局位移
    //
    // 改之前当前句用 `AppText.line.copy(fontSize = 17.sp, lineHeight = 26.sp)`，
    // 而非当前句是 15/24 —— **行高跟着变**。于是每换一句，那一行就长高 2dp，
    // 它下面的所有内容跟着往下挪一下。整篇文稿在播放时一直在轻微抽动，
    // 而自动滚动又叠在上面，观感是「字幕在抖」。
    //
    // 解法不是放弃字号差异（原注释的理由是对的：手机在手里晃时，
    // 光靠颜色分辨不出念到哪），而是**把行高固定下来，只让字号变**。
    // 行高统一取大的那个值（26sp），非当前句因此行距略松 —— 反而更好读。
    val lineStyle = AppText.line.copy(
        fontSize = if (isCurrent) 17.sp else 15.sp,
        lineHeight = SUBTITLE_LINE_HEIGHT,
    )
    // 颜色做补间，让「哪一句是当前句」的转移是流动的而不是闪切的
    val textColor by animateColorAsState(
        targetValue = if (isCurrent) Ink else InkMid,
        animationSpec = Motion.quick(),
        label = "subtitleText",
    )
    val clockColor by animateColorAsState(
        targetValue = if (isCurrent) Amber else InkFaint,
        animationSpec = Motion.quick(),
        label = "subtitleClock",
    )
    val rowBg by animateColorAsState(
        targetValue = if (isCurrent) AmberFaint else Color.Transparent,
        animationSpec = Motion.quick(),
        label = "subtitleBg",
    )
    val markerColor by animateColorAsState(
        targetValue = if (isCurrent) Amber else Color.Transparent,
        animationSpec = Motion.quick(),
        label = "subtitleMarker",
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                // 竖线也跟着补间：不再用 if (isCurrent) 硬开关，
                // 否则颜色在淡入、线却是瞬间出现的，两者对不上
                drawRect(color = markerColor, size = Size(2.dp.toPx(), size.height))
            }
            .background(rowBg)
            .pressFeedback(onClick = onClick)
            .padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
    ) {
        Text(
            // 时间戳对某一行是**永远不变**的常量，但 `formatClock` 内部是
            // `String.format`（要建 Formatter、解析格式串、装箱参数）。
            // 不 remember 的话，10 行 × 4 次/秒 = 每秒算 40 次同一个字符串
            text = remember(line.startMs) { SrtTrack.formatClock(line.startMs) },
            style = AppText.label,
            color = clockColor,
        )
        Spacer(Modifier.height(5.dp))
        // ## 长按选词（P1.3）
        //
        // 「手指落在第几个字符」要靠 `TextLayoutResult.getOffsetForPosition(pos)`，
        // 所以得先从 `onTextLayout` 把 layout 留下来；再交给 [WordBoundary]
        // 把字符下标扩成一个词的区间（它处理撇号、连字符、点在标点上的情况）。
        //
        // ⚠️ 手势只挂**这一行 Text**、且只处理长按：`detectTapGestures(onLongPress = ...)`
        //    不会消费单击，单击仍然冒泡到外层 Column 的 `pressFeedback`
        //    （= 跳回这句重听）。这条分工是播放页的核心交互，不能被选词功能挪走。
        var layout by remember(line.text) { mutableStateOf<TextLayoutResult?>(null) }
        Text(
            text = line.text,
            style = lineStyle,
            color = textColor,
            onTextLayout = { layout = it },
            modifier = Modifier.pointerInput(line.text) {
                detectTapGestures(
                    onLongPress = { pos ->
                        val word = layout?.let {
                            WordBoundary.wordAt(line.text, it.getOffsetForPosition(pos))
                        }
                        if (word != null) onWordLongPress(word)
                    },
                )
            },
        )
        // 译文。null = 还没翻到，显示一个占位下划线，让用户知道「这里将来会有中文」；
        // "" = 这句不需要翻（纯符号），直接不占位
        when {
            translation == null -> {
                Spacer(Modifier.height(5.dp))
                Box(
                    Modifier
                        .width(46.dp)
                        .height(1.dp)
                        .background(if (isCurrent) AmberFaint else Hairline)
                )
            }
            translation.isNotEmpty() -> {
                Spacer(Modifier.height(5.dp))
                Text(
                    text = translation,
                    style = AppText.gloss,
                    color = if (isCurrent) Gloss else InkDim,
                )
            }
        }
    }
}

/**
 * 「回到当前句」。
 *
 * 用户手动滚动后就常驻，点一下恢复跟随。文案直接说清「现在没在跟随」，
 * 而不是只说「回到当前句」—— 后者会让人以为刚才也一直在跟随。
 */
@Composable
private fun ResumeFollowBar(modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier = modifier
            .background(BgRaised)
            .pressFeedback(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlyphIcon(Glyph.ChevronRight, size = 13.dp, tint = Amber, strokeWidth = 1.8f)
        Spacer(Modifier.width(7.dp))
        Text("已暂停跟随 · 回到当前句", style = AppText.caption, color = Amber)
    }
}

// ---------------------------------------------------------------------------
// 底部控制区
// ---------------------------------------------------------------------------

@Composable
private fun ControlPanel(
    ui: PlayerUiState,
    translateStatus: TranslateUiStatus,
    canStepLine: Boolean,
    onSeek: (Long) -> Unit,
    onTogglePlay: () -> Unit,
    onSkip: (Long) -> Unit,
    onStepLine: (Int) -> Unit,
    onSpeedPercent: (Int) -> Unit,
    onResetSpeed: () -> Unit,
    onRepeat: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
) {
    // 拖动进度条时用本地值，松手才真 seek。
    // 直接边拖边 seek 会出现「拖到一半被播放位置覆盖回去」的拉扯感
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }

    val duration = ui.durationMs.coerceAtLeast(1L)
    val shownPosition = if (dragging) dragValue.toLong() else ui.positionMs

    // 1.2.0：倍速滑杆连同它的本地拖动状态一起删掉了（见下面那一排的注释）。
    // 那套 speedDragging / speedDragPercent 是专门为「拖动时不被归一化值拽回去」
    // 存在的 —— 没有滑杆就没有这个问题，± 按钮每次只走一档，本来就是离散的。

    Column(
        modifier = Modifier
            .fillMaxWidth()
            // 1.2.10：它不再是「页面下半部的一块凸起」，而是**浮在字幕之上的材质**。
            // 底色因此与页面一致（Bg），层次靠外面那条渐隐带 ——
            // 而不是靠 BgRaised 那点明度差（深色下只有 9/255，本来就几乎看不出来）
            .background(Bg)
            .padding(horizontal = Space.pageH, vertical = 12.dp),
    ) {
        // ⚠️ 这里原来有一条 1px Hairline 分隔线，1.2.10 删掉了。
        //    它与顶栏那条是同一个问题：材质之间不该用硬线去切。
        //    「内容滚到浮层下面没入」这件事现在由 ScrollEdgeFade 表达。

        // 进度。Slider 自带拖动手势，保留它，只把配色换掉 ——
        // 自绘一条线再补手势，等于把 Slider 重写一遍还得处理无障碍。
        Slider(
            value = shownPosition.toFloat().coerceIn(0f, duration.toFloat()),
            onValueChange = {
                dragging = true
                dragValue = it
            },
            onValueChangeFinished = {
                onSeek(dragValue.toLong())
                dragging = false
            },
            valueRange = 0f..duration.toFloat(),
            modifier = Modifier
                .fillMaxWidth()
                .height(24.dp),
            colors = SliderDefaults.colors(
                thumbColor = Amber,
                activeTrackColor = Amber,
                inactiveTrackColor = HairlineStrong,
            ),
        )

        Row(Modifier.fillMaxWidth()) {
            Text(SrtTrack.formatClock(shownPosition), style = AppText.digit)
            Spacer(Modifier.weight(1f))
            // ★ 1.2.8：右端从**总时长**改成**剩余时间**。
            //
            // 通勤场景里用户更常问的是「还有多久听完」而不是「这集多长」；
            // 而总时长并没有丢 —— 迷你条上仍然写着「当前位置 / 总长」。
            // （Apple 第 16 节举的例子正是「显示剩余时间的进度条」：
            //   有时候**加**一点上下文反而更简单。）
            Text(
                text = "-" + SrtTrack.formatClock((duration - shownPosition).coerceAtLeast(0L)),
                style = AppText.digit,
                color = InkDim,
            )
        }

        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextChip("−15s") { onSkip(-15_000L) }
            IconChip(Glyph.SkipPrev, enabled = canStepLine) { onStepLine(-1) }
            PlayPauseButton(playing = ui.playing, buffering = ui.buffering, onClick = onTogglePlay)
            IconChip(Glyph.SkipNext, enabled = canStepLine) { onStepLine(1) }
            TextChip("+15s") { onSkip(15_000L) }
        }

        Spacer(Modifier.height(12.dp))

        // ---- 倍速 + 精听 + 跳集：全部收进这一排 ----
        //
        // ## 1.2.0 把控制区从 5 排压到 3 排
        //
        // 改之前这里是**三排**：倍速标签行、倍速滑杆行、精听跳集行。
        // 加上进度条和主控键，控制区一共 5 排 14 个可交互元素，
        // 占掉屏幕下半部很大一块 —— 而这个页面的核心是**字幕**，
        // 它被挤在上面。
        //
        // 现在合成一排，字幕区多出约两排的高度（3–4 行）。
        //
        // ## 滑杆为什么可以去掉
        //
        // 滑杆的价值是「大范围移动」。但实际使用里，倍速是一个
        // **围绕当前值微调**的量：用户从 1.0x 调到 1.2x，再调到 1.15x，
        // 很少一次从 0.5x 拖到 2.0x。而 0.05 一格在全宽滑杆上只占约 3%，
        // 手指本来就很难准确停在某一格 —— 真正在用的一直是 ± 按钮。
        //
        // 去掉滑杆还顺手解决了它自带的两个麻烦：拖动时要维护本地值
        // 防回弹、以及每帧都可能触发 setPlaybackSpeed 要靠跨档判断来节流。
        //
        // ## 数值本身就是「回到常速」的按钮
        //
        // 原来「回到 1.0x」是一个独立的 ActionChip，占掉半排。
        // 现在点数值即回正 —— 它天然就在两个 ± 中间，是手指最容易够到的位置。
        // 已经在 1.0x 时它变成普通色且不可点（不是消失，消失会让布局跳变）。
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SpeedStepButton(
                label = "−",
                enabled = !SpeedPolicy.isAtMin(ui.speedPercent),
            ) { onSpeedPercent(SpeedPolicy.step(ui.speedPercent, -1)) }

            // 点一下回到常速。其余档位都是相对的，只有 1.0x 是绝对参照点
            val offDefault = !SpeedPolicy.isDefault(ui.speedPercent)
            Box(
                modifier = Modifier
                    .width(56.dp)
                    .height(36.dp)
                    .pressFeedback(enabled = offDefault, onClick = onResetSpeed),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = SpeedPolicy.format(ui.speedPercent),
                    style = AppText.digit,
                    // 琥珀色 = 「偏离常速了，点我回去」。常速时是普通色，
                    // 既表示当前正常，也表示这里现在没什么可点
                    color = if (offDefault) Amber else InkDim,
                )
            }

            SpeedStepButton(
                label = "+",
                enabled = !SpeedPolicy.isAtMax(ui.speedPercent),
            ) { onSpeedPercent(SpeedPolicy.step(ui.speedPercent, 1)) }

            Spacer(Modifier.width(4.dp))

            // 「重听本句」提升为这一排里最宽的一个。
            // 它是整个 App 最「学习向」的动作（把当前句用慢速再放一遍，
            // 到句尾自动恢复原速），而在 1.1.0 里它却和「上一集/下一集」
            // 这种低频导航平级排在最后一排
            ActionChip(
                label = if (ui.repeating) "慢速重听中" else "重听本句",
                glyph = Glyph.Refresh,
                enabled = canStepLine,
                highlighted = ui.repeating,
                modifier = Modifier.weight(1f),
                onClick = onRepeat,
            )

            // 上一集 / 下一集改成箭头图标。
            //
            // 用 Chevron 而不是 SkipPrev/SkipNext —— 后者在同一屏的主控排里
            // 已经表示「上一句/下一句」，两者形状相近会真的点错。
            // 箭头在这里读作「翻页」，与顶栏那个「第 x/y 集」是同一个语义
            IconChip(Glyph.ChevronLeft, enabled = ui.hasPrevious, onClick = onPrev)
            IconChip(Glyph.ChevronRight, enabled = ui.hasNext, onClick = onNext)
        }

        Spacer(Modifier.height(10.dp))

        TranslateStatusLine(status = translateStatus)
    }
}

/**
 * 中文对照的进度。
 *
 * 只在「正在翻」和「翻失败」时出现 —— 译好了就是常态，不需要再报一次。
 * 失败必须说出来：用户会一直等一个永远不来的中文，比明确告诉他「翻了但失败」
 * 要糟得多。
 */
@Composable
private fun TranslateStatusLine(status: TranslateUiStatus) {
    when (status) {
        // 1.2.0：Idle 态**什么都不画**。
        //
        // 原来这里常驻一句「退出后仍在后台播放」。它占掉控制区最下面一整行，
        // 而说的是一件用户**试过一次就永远知道**的事 —— 而且多数人根本不会
        // 特意去验证它，他只是按了返回，音乐还在响，于是这件事就被学会了。
        //
        // 更关键的是：这个位置是留给「正在发生的事」的（翻译进度、失败原因）。
        // 让它在没事发生时显示一句静态说明，等于把一个状态位当成了广告位。
        is TranslateUiStatus.Idle -> Unit

        // 2.1.0：Preparing / Running(progress) / Failed 三个态都删了，只剩这一个。
        //
        // 那三个态都是为「端侧翻译要跑 1–3 分钟」准备的 —— 长时间等待才需要
        // 进度与失败说明。现在中文是服务器给的、一次取回来，唯一还没有过的
        // 情况就是「服务器还没翻好」，而那件事用户唯一能做的就是等
        // （而且它自己会到，不需要退出重进）。所以这里只说这件事，不报错。
        is TranslateUiStatus.Waiting -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(
                modifier = Modifier.size(10.dp),
                strokeWidth = 1.4.dp,
                color = Amber,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "中文翻译正在加载，稍等一下",
                style = AppText.label,
                color = Amber,
            )
        }
    }
}

/**
 * 播放/暂停。
 *
 * 方形实心琥珀而不是圆形按钮 —— 圆形在整套直角视觉里是唯一没有理由的曲线。
 * 图形本身仍然手绘（见 [GlyphIcon]），因为 `material-icons-core` 里只有
 * `PlayArrow`、**没有 Pause**，凑不齐一对。
 */
@Composable
private fun PlayPauseButton(playing: Boolean, buffering: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(54.dp)
            .background(Amber)
            .pressFeedback(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (buffering) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = Bg,
            )
        } else {
            GlyphIcon(
                glyph = if (playing) Glyph.Pause else Glyph.Play,
                size = 22.dp,
                tint = Bg,
                strokeWidth = 2.0f,
            )
        }
    }
}

/** 纯文字的小动作（±15s）。无填充、无边框，靠字重和字距站住 */
@Composable
private fun TextChip(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .pressFeedback(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = AppText.digit, color = InkMid)
    }
}

/** 图标动作（上一句/下一句） */
@Composable
private fun IconChip(glyph: Glyph, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .pressFeedback(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        GlyphIcon(
            glyph = glyph,
            size = 19.dp,
            tint = if (enabled) Ink else InkFaint,
            strokeWidth = 1.6f,
        )
    }
}

/**
 * 控制区第二排的动作按钮。
 *
 * 无填充，只有一圈 1px 描边 —— 这一排本来就是次级动作，
 * 给它实心底色会和控制区里那个实心播放键抢主次。
 */
@Composable
private fun ActionChip(
    label: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    glyph: Glyph? = null,
    highlighted: Boolean = false,
    onClick: () -> Unit,
) {
    val fg = when {
        highlighted -> Amber
        enabled -> InkMid
        else -> InkFaint
    }

    // ⚠️ 这两条上下细线的颜色必须在这里取好，不能写进 drawBehind 里。
    //
    // drawBehind 的 lambda 是 DrawScope 而不是 @Composable 作用域 ——
    // 主题色（Amber / HairlineStrong）现在是从 CompositionLocal 里读的
    // composable 属性，在绘制作用域里取不到，编译器会直接拦下来。
    // 顺带这也更省：颜色只在重组时算一次，而不是每帧绘制时重算。
    val ruleColor = if (highlighted) Amber else HairlineStrong

    Row(
        modifier = modifier
            .background(if (highlighted) AmberFaint else Color.Transparent)
            .drawBehind {
                val w = 1.dp.toPx()
                drawRect(
                    color = ruleColor,
                    size = Size(size.width, w),
                )
                drawRect(
                    color = ruleColor,
                    topLeft = androidx.compose.ui.geometry.Offset(0f, size.height - w),
                    size = Size(size.width, w),
                )
            }
            .pressFeedback(enabled = enabled, onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (glyph != null) {
            GlyphIcon(glyph, size = 13.dp, tint = fg, strokeWidth = 1.6f)
            Spacer(Modifier.width(6.dp))
        }
        Text(label, style = AppText.caption, color = fg, maxLines = 1)
    }
}

/**
 * 倍速微调（±0.05x）。
 *
 * 尺寸与 [IconChip] 一致（40dp 方形）—— 视觉上一眼看出它和「上一句 / 下一句」
 * 是同一类东西：都是「挪一小步」。滑杆负责大范围移动，它负责「就慢一点点」。
 *
 * 到边界就置灰。从 2.00x 再按「+」绕回 0.50x 是惊悚体验，
 * 而静默不动又像按钮坏了 —— 置灰把「已经到头」这件事说清楚。
 */
@Composable
private fun SpeedStepButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .pressFeedback(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = AppText.digit,
            color = if (enabled) InkMid else InkFaint,
        )
    }
}

// ---------------------------------------------------------------------------
// 空态 / 错误态
// ---------------------------------------------------------------------------

@Composable
private fun LoadingHint() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = Amber, strokeWidth = 2.dp)
    }
}

@Composable
private fun ErrorHint(message: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.padding(horizontal = 34.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("无法播放", style = AppText.section, color = Crimson)
            Spacer(Modifier.height(Space.row))
            Text(message, style = AppText.caption, color = InkDim)
        }
    }
}

@Composable
private fun NoSubtitleHint() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.padding(horizontal = 34.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("这条还没有字幕", style = AppText.section, color = Amber)
            Spacer(Modifier.height(Space.row))
            Text(
                text = "音频现在就能听，字幕稍后会自己补上。",
                style = AppText.caption,
                color = InkDim,
            )
        }
    }
}

/**
 * 字幕行的**固定**行高。
 *
 * 取当前句那一档（26sp）而不是普通句（24sp）：只有固定下来，
 * 高亮在句间迁移时才不会让整列内容上下抽动 —— 而自动滚动又叠在上面，
 * 两者一起就会被读成「字幕在抖」。
 *
 * 代价是非当前句的行距略松。实测反而更好读，且这个页面本来就是
 * 「整篇文稿铺开」的排版，宽松一点符合它的性格。
 */
private val SUBTITLE_LINE_HEIGHT = 26.sp

/**
 * 「放得下」这条判据的**余量**（跟随定位用）。
 *
 * `restPx`（停在上三分之一之后下方还剩多少）是「正好放在那里所需的空间」——
 * 行高**正好等于**它时，这一句的底边会**正好压在控制区上沿**：任何测量误差、
 * 或者中文对照晚到一帧把行高撑大，都会立刻变成「被切掉一截」。
 *
 * 所以离边界不到一行的距离时，宁可走「贴顶栏下」那一支：那种句子本来就已经
 * 顶到控制区了，「下方还能瞥见下一句」等于零，贴顶是更稳的选择。
 *
 * ⚠️ 别把它调小到 0 —— 那就退回了「底边贴在材质边缘」的写法，
 *    而那正是 2.1.7 之前反复出现「第二句中文被切」的原因。
 */
private val SUBTITLE_FIT_MARGIN = 26.dp
