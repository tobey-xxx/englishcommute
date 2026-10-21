package com.example.englishcommute.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.englishcommute.ui.theme.Amber
import com.example.englishcommute.ui.theme.AppText
import com.example.englishcommute.ui.theme.Bg
import com.example.englishcommute.ui.theme.Gloss
import com.example.englishcommute.ui.theme.Hairline
import com.example.englishcommute.ui.theme.Ink
import com.example.englishcommute.ui.theme.InkDim
import com.example.englishcommute.ui.theme.InkFaint
import com.example.englishcommute.ui.theme.InkMid
import com.example.englishcommute.ui.theme.Space
import com.example.englishcommute.ui.theme.pressFeedback
import com.example.englishcommute.word.ClozeBuilder
import com.example.englishcommute.word.FsrsScheduler
import com.example.englishcommute.word.PronState
import com.example.englishcommute.word.WordCard

/**
 * 复习页：整屏覆盖，一次过一张。
 *
 * ## 为什么是整屏而不是弹层
 *
 * 复习是**一段连续的动作**（连着过十几张），中途不应该能看到底下的列表 ——
 * 那会让人分心去点别的。所以这里不给「下拉关闭」，只有左上角一个 ✕。
 *
 * ## ★ 两段式：先问「认不认识」，再问「记到什么程度」（3.1.1 改）
 *
 * ```
 *   ① 例句 + 粗体词
 *        「认得出吗？」
 *         ├─ 认出来了 ─────────────────────→ Easy（= 太简单）
 *         └─ 没认出来 → ② 释义 + 原句 + 音频
 *                          「没印象 / 有点难 / 记住了」→ Again / Hard / Good
 * ```
 *
 * **为什么不把四档平铺在一面上**（原来就是那样）：四档平铺时，用户是在
 * **还没看答案**的情况下评「记住了 / 有点难」—— 而他这一刻只知道「我认不出」，
 * 根本无从判断难度。拆成两段之后，三档都在**答案已经揭晓**之后评，每个选择才有依据。
 *
 * **为什么正面从「挖空」改成「粗体」**：挖空考的是「**能把那个词复现出来**」
 * （近似拼写 / 听辨），而词量长起来之后，用户真正要练的是「**看到它知道意思**」。
 * 前者要求能写出来，后者只要求能认出来 —— 不是同一个能力。
 * 完型填空式的卡在几十个词时还行，上百个之后每次复习都变成一次默写，
 * 而**默写失败不等于这个词没记住**，于是评分失准、人也累。
 * （用户原话：「有点像完型填空，后面单词数量多了，太难记住了」）
 *
 * ## 正面只给那一句话
 *
 * 不显示释义、不显示音标、**不给任何音频按钮**（理由见 `QuestionSide` 里那段）。
 * 唯一能看的是那句话本身 —— 而它是用户自己听过的那句，回忆的起点就是这个语境。
 *
 * 底下一句「别硬想」是刻意的：猜不出来时在那里耗时间**不会让它记住**
 * （FSRS 拿到的是「你花了 40 秒」和「你花 3 秒」完全一样的输入），
 * 而多耗的时间全变成挫败感。
 *
 * ## 背面一次给全
 *
 * 词、音标、释义、你听到的（词高亮）、中文、教材例句、两个音频按钮。
 * 不再分第三屏 —— 复习的每一秒都该有信息增量。
 *
 * ## 每一档都写出「后果」
 *
 * 「认出来了」下面写「8 个月后」，背面三档下面写各自的间隔。
 * 这是这一屏最有用的一个信息：它让「评分」从「凭感觉」变成**可预期的后果**。
 */
@Composable
fun ReviewScreen(vm: WordViewModel, state: ReviewState, onClose: () -> Unit) {
    val card = state.current
    val entries by vm.entries.collectAsStateWithLifecycle()
    val pronStates by vm.pron.collectAsStateWithLifecycle()

    // 换到新的一张时，把它的读音提前备好（用户点之前就下完，体验是"点了就响"）
    LaunchedEffect(card?.lemma) { vm.preparePron(card?.lemma) }

    // 同一时刻问一次「这一句的原声还在不在」—— 答案要 stat 文件，
    // 所以只问一次，不让它在每次重组里发生（见 `WordViewModel.sentenceReady`）。
    // ⚠️ 退出页面时停掉原句播放：播放器是容器里的单例，不会自己停。
    LaunchedEffect(card?.lemma) { vm.refreshSentenceAudio(card?.contexts?.firstOrNull()) }
    DisposableEffect(Unit) { onDispose { vm.stopSentence() } }

    Box(Modifier.fillMaxSize().background(Bg)) {
        if (card == null) {
            // 兜底：正常路径下 finishSession 会把整页关掉，走不到这里。
            // 但如果真走到了（状态竞态），显示一句能懂的话而不是一片空白。
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("这一轮结束了", style = AppText.body, color = InkFaint)
            }
            return@Box
        }

        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            ReviewTopBar(rated = state.rated, total = state.total, onClose = onClose)

            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Space.pageH)
            ) {
                if (state.revealed) {
                    AnswerSide(card = card, entry = entries[card.lemma], vm = vm)
                } else {
                    QuestionSide(card = card, vm = vm)
                }
            }

            ReviewBottomBar(
                state = state,
                card = card,
                intervals = remember(card.lemma, card.reps) { vm.previewIntervals(card) },
                onReveal = vm::reveal,
                onRate = vm::rate,
            )
        }
    }
}

// =========================================================================
// 顶栏
// =========================================================================

/**
 * 顶栏：只有 ✕ 和一个「3 / 12」。
 *
 * ⚠️ 进度写的是**已评分的张数**（`rated`），不是「当前第几张」。
 *    显示「第 3 张」时用户会以为还有 9 张 —— 而他已经做完 3 张了。
 *    数字要反映**他已经付出的部分**，那才是让人想继续的东西。
 */
@Composable
private fun ReviewTopBar(rated: Int, total: Int, onClose: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .clip(RoundedCornerShape(10.dp))
                .pressFeedback(onClick = onClose)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text("✕", style = AppText.title, color = InkDim)
        }
        Spacer(Modifier.weight(1f))
        Text(
            text = "$rated / $total",
            style = AppText.pill,
            color = InkFaint,
        )
        Spacer(Modifier.weight(1f))
        // 占位：让「3 / 12」在视觉上居中（左边有个 ✕）
        Spacer(Modifier.width(45.dp))
    }
}

// =========================================================================
// 正面
// =========================================================================

@Composable
private fun QuestionSide(card: WordCard, vm: WordViewModel) {
    val ctx = card.contexts.firstOrNull()

    Spacer(Modifier.height(28.dp))

    if (ctx == null) {
        // 没有出处的卡（极少数：加词时那句话没取到）。
        // ⚠️ 这时**不能显示一个空白的挖空**，那看起来像加载失败。
        //    改成考「词义」：直接给词、不给释义 —— 退一步，但仍然是有效的一次回忆。
        Text("回忆它的意思", style = AppText.label, color = InkFaint)
        Spacer(Modifier.height(18.dp))
        Text(
            text = card.lemma,
            style = AppText.section,
            color = Amber,
        )
        Spacer(Modifier.height(28.dp))
    } else {
        Text("这句里的粗体词，认得出吗", style = AppText.label, color = InkFaint)
        Spacer(Modifier.height(18.dp))
        Text(
            text = highlighted(ctx.sentence, card, accent = true),
            style = AppText.reading,
            color = Ink,
        )
        Spacer(Modifier.height(26.dp))
    }

    // ⚠️ **正面故意不给任何音频按钮**（3.0.4 改的，别加回来）。
    //
    //    这一面问的是「你认不认得出这个词」。而「听读音」点一下就是
    //    /saɪnd/ —— 听到就想起意思，**等于把答案给他**。
    //    「听这一句」更糟：整句朗读里那个词就在里面，连猜都不用。
    //
    //    读音和原句都在**背面**给（那时答案已经揭晓，音频是辅助记忆而不是泄题）。
    //    这条规则值得写下来，因为「给每个词加一个播放按钮」看起来永远是个好主意 ——
    //    我自己的设计稿上正面就画了两个按钮，是做出来之后才想明白的。

    Spacer(Modifier.height(26.dp))
    Text(
        text = "别硬想 —— 认不出就直接点，多耗的那几秒不会让它记住。",
        style = AppText.caption,
        color = InkFaint,
    )
    Spacer(Modifier.height(24.dp))
}

// ⚠️ 这里原来有一个 `memorySentence()`：把句子里的目标词**换成空**。
//    3.1.1 删掉了 —— 正面的形态从「挖空」改成了「例句 + 粗体词」。
//
//    为什么改：挖空考的是**回忆出那个词是什么**（近似拼写/听辨），
//    而词量长起来之后，用户真正需要练的是「**看到它知道意思**」。
//    这两件事不是一回事：前者要求能复现，后者只要求能认。
//    完型填空式的卡在几十个词时还行，上百个之后每次复习都变成一次默写 ——
//    而默写失败不等于这个词没记住，于是评分失准、人也累。
//
//    `Cloze` 这个数据结构仍然在用（`highlighted()` 靠它切出 before/answer/after
//    来定位标粗的位置），只是不再用它的 `masked()`。

// =========================================================================
// 背面
// =========================================================================

@Composable
private fun AnswerSide(card: WordCard, entry: com.example.englishcommute.word.WordEntry?, vm: WordViewModel) {
    val ctx = card.contexts.firstOrNull()

    Spacer(Modifier.height(20.dp))

    // ── 词 + 音标 ───────────────────────────────────────────────────────
    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            text = card.lemma,
            style = AppText.section,
            color = Ink,
        )
        if (entry?.hasIpa == true) {
            Spacer(Modifier.width(10.dp))
            Text(text = "/${entry.ipa}/", style = AppText.gloss, color = InkFaint)
        }
    }

    // 用户点的是变形时说一句 —— 否则「我点的是 signed，怎么显示 sign」是个真疑问。
    // ⚠️ 音标只给**原形**的：词表按原形建（`signed` 的音标不在缺音标的 4% 里，
    //    而是**本来就没有** —— 词表里每个原形一条，变形不单独建条）。
    //    所以宁可说清「这是原形」，也不要拿原形的音标去冒充变形的。
    if (card.surface != card.lemma) {
        Spacer(Modifier.height(5.dp))
        Text(
            text = "你点的是 ${card.surface}，读音和释义按原形给",
            style = AppText.pill,
            color = InkFaint,
        )
    }

    Spacer(Modifier.height(14.dp))
    AudioRow(card = card, vm = vm)
    Spacer(Modifier.height(18.dp))

    // ── 释义 ────────────────────────────────────────────────────────────
    if (entry != null && entry.defs.isNotEmpty()) {
        entry.defs.forEachIndexed { i, d ->
            if (i > 0) Spacer(Modifier.height(6.dp))
            Text(d, style = AppText.body, color = InkMid)
        }
    } else {
        // 查不到释义（词表覆盖不到的新词）。**如实说**，不编。
        Text("这个词的释义还没收录", style = AppText.body, color = InkMid)
        Spacer(Modifier.height(4.dp))
        Text(
            text = "新闻里新出现的人名、地名常常还没收录，素材更新一次就会补上。",
            style = AppText.gloss,
            color = InkFaint,
        )
    }

    // ── 你听到的 ────────────────────────────────────────────────────────
    if (ctx != null) {
        Spacer(Modifier.height(20.dp))
        Text("你听到的", style = AppText.label, color = InkFaint)
        Spacer(Modifier.height(8.dp))
        Text(
            text = highlighted(ctx.sentence, card),
            style = AppText.line,
            color = InkMid,
        )
        if (!ctx.translation.isNullOrEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(ctx.translation, style = AppText.gloss, color = Gloss)
        }
        Spacer(Modifier.height(5.dp))
        Text(ctx.episodeTitle, style = AppText.pill, color = InkFaint)
    }

    Spacer(Modifier.height(18.dp))
}

/**
 * 句子里把目标词高亮出来。
 *
 * 高亮的是**句中的实际形态**（`signed`），不是原形 —— 用户眼睛要找的是
 * 刚才那个空里原来是什么，而那个位置写的是 `signed`。
 */
/**
 * 把句子里的目标词**标出来**。
 *
 * 高亮的是**句中的实际形态**（`signed`），不是原形 —— 用户眼睛要找的是那句话里
 * 长什么样的那个词，而那个位置写的就是 `signed`。
 *
 * ⚠️ `accent` 是「正面 / 背面」的区别，不是为了好看：
 *
 * | 用在哪 | 样式 | 为什么 |
 * |---|---|---|
 * | **正面**（`accent = true`） | 琥珀 + Bold | 正面**只有这一句**，用户第一眼就要找到那个词。它必须比周围文字跳出来 |
 * | 背面（默认） | 正文色 + SemiBold | 背面的主角是**释义**，句子是佐证。这里再抢眼就把注意力从答案上拉走了 |
 *
 * 共用一个函数（而不是写两份）的理由：**退化链只有一条**
 * （`surface` → `lemma` → 整句不标）。两份实现迟早会有一份漏掉某一级，
 * 而症状是「这句话在正面标了、在背面没标」这种没人会去查的不一致。
 */
@Composable
private fun highlighted(
    sentence: String,
    card: WordCard,
    accent: Boolean = false,
): androidx.compose.ui.text.AnnotatedString {
    val cloze = ClozeBuilder.build(sentence, card.surface)
        ?: ClozeBuilder.build(sentence, card.lemma)
        ?: return androidx.compose.ui.text.AnnotatedString(sentence)
    val mark = if (accent) {
        SpanStyle(color = Amber, fontWeight = FontWeight.Bold)
    } else {
        SpanStyle(color = Ink, fontWeight = FontWeight.SemiBold)
    }
    return buildAnnotatedString {
        append(cloze.before)
        withStyle(mark) { append(cloze.answer) }
        append(cloze.after)
    }
}

// =========================================================================
// 读音
// =========================================================================

/**
 * 背面的音频行：**两个按钮，两种东西**。
 *
 * | 按钮 | 是什么声音 | 什么时候有 |
 * |---|---|---|
 * | **听这一句** | **原声**：用户当初听到的那句话里的那个词（连读、语调都在） | 那期音频还在盘上（3 天保留期） |
 * | **听读音** | **标准音**：TTS 预生成的单词发音（孤立、干净） | 几乎总有（下过一次就在本地） |
 *
 * 两者不是重复：一个是「它在真实语流里长什么样」，一个是「它单独念是什么样」。
 * 学发音靠后者，认词靠前者。
 *
 * ⚠️ **「听这一句」不在时，什么都不显示**（不放一行「音频已过期」的说明）。
 *    理由：那一行会在绝大多数卡片上出现（音频 3 天就清），
 *    变成每次复习都要读的噪音；而用户并没有因此少做什么 ——
 *    「听读音」永远在，他的动作没有断。**说明只在有补救动作时才有价值。**
 *
 * ⚠️ 这一行**只在背面**。（正面给音频 = 泄题，理由见 `QuestionSide` 里那段。）
 */
@Composable
private fun AudioRow(card: WordCard, vm: WordViewModel) {
    val states by vm.pron.collectAsStateWithLifecycle()
    val state = states[card.lemma]
    val ctx = card.contexts.firstOrNull()
    val sentenceReady by vm.sentenceReady.collectAsStateWithLifecycle()

    // 本地已经有文件（上次下过）时直接当就绪 —— 不必等一次网络往返
    val ready = state is PronState.Ready || vm.hasAudio(card.lemma)

    // 「听这一句」在**左**：它是更自然的第一个动作（从整句到单词），
    // 而且它没有加载状态，不会挡住右边那个（读音可能还在下）。
    if (sentenceReady && ctx != null) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PronButton(label = "听这一句", enabled = true, onClick = { vm.playSentence(ctx) })
            PronReadButton(card = card, state = state, ready = ready, vm = vm)
        }
        return
    }

    PronReadButton(card = card, state = state, ready = ready, vm = vm)
}

/** 「听读音」那一个按钮（四种状态各自的说法），从原来的 [PronRow] 里抽出来 */
@Composable
private fun PronReadButton(
    card: WordCard,
    state: PronState?,
    ready: Boolean,
    vm: WordViewModel,
) {
    when {
        ready -> PronButton(label = "听读音", enabled = true, onClick = { vm.playPron(card.lemma) })

        state is PronState.Loading -> PronButton(label = "读音加载中…", enabled = false, onClick = {})

        state is PronState.NotPublished -> Column {
            PronButton(label = "读音还没生成好", enabled = false, onClick = {})
            Spacer(Modifier.height(6.dp))
            Text(
                text = "这个词比较新，读音还在陆续生成，过一两天再来听就有了。",
                style = AppText.pill,
                color = InkFaint,
            )
        }

        state is PronState.Failed -> Column {
            PronButton(label = "读音没下下来 · 重试", enabled = true, onClick = { vm.retryPron(card.lemma) })
            Spacer(Modifier.height(6.dp))
            Text(
                text = (state as PronState.Failed).reason,
                style = AppText.pill,
                color = InkFaint,
            )
        }

        // 还没问过（刚进这一张、预下载还没回来）
        else -> PronButton(label = "读音加载中…", enabled = false, onClick = {})
    }
}

@Composable
private fun PronButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Hairline.copy(alpha = if (enabled) 0.30f else 0.18f))
            .pressFeedback(enabled = enabled, pressedAlpha = 0.55f, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Text(
            text = label,
            style = AppText.body,
            color = if (enabled) InkMid else InkFaint,
        )
    }
}

// =========================================================================
// 底栏：看答案 / 四档评分
// =========================================================================

@Composable
private fun ReviewBottomBar(
    state: ReviewState,
    card: WordCard,
    intervals: Map<Int, Int>,
    onReveal: () -> Unit,
    onRate: (Int) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = Space.pageH, end = Space.pageH, top = 10.dp, bottom = Space.row)
    ) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Hairline.copy(alpha = 0.6f)))
        Spacer(Modifier.height(14.dp))

        if (!state.revealed) {
            // ── 正面：**一个导航 + 一个评分** ────────────────────────────────
            //
            // ⚠️ 「没认出来」**不是评分** —— 它是「让我先看答案」。真正评的是看完之后那一面。
            //    所以它这一档**不能**顺手打一个 Again：那样用户就丢掉了
            //    「我看了答案，其实有点印象」这个信息 —— 而 Again 的语义是
            //    「完全忘了」，它会把这个词的间隔压到最短。
            //    少一次评分，换来的是「Again / Hard / Good」三档各自都还是准的。
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                ChoiceButton(
                    label = "没认出来",
                    hint = "看答案",
                    primary = false,
                    onClick = onReveal,
                )
                ChoiceButton(
                    label = "认出来了",
                    hint = intervalLabel(intervals[FsrsScheduler.RATING_EASY] ?: 0),
                    primary = true,
                    onClick = { onRate(FsrsScheduler.RATING_EASY) },
                )
            }
        } else {
            // ── 背面：看完答案之后的三档 ────────────────────────────────────
            //
            // ⚠️ 这里**没有「太简单」** —— 它已经在正面用掉了（就是「认出来了」）。
            //    在这一面再给一次，等于让用户对**同一次回忆**评两个相反的结论，
            //    而且他会困惑：「我到底是简单还是难」。
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                RatingButton("没印象", FsrsScheduler.RATING_AGAIN, intervals, primary = false, onRate)
                RatingButton("有点难", FsrsScheduler.RATING_HARD, intervals, primary = false, onRate)
                RatingButton("记住了", FsrsScheduler.RATING_GOOD, intervals, primary = true, onRate)
            }
        }
    }
}

/**
 * 正面那两个按钮。它们看起来一样，但**下面那行小字说的是两件不同的事**：
 *
 * | 按钮 | 小字 | 它在回答什么问题 |
 * |---|---|---|
 * | 没认出来 | 「看答案」 | **按下去会发生什么**（我还没评，只是先看答案） |
 * | 认出来了 | 「8 个月后」 | **按下去的后果**（这个词会被推到多久之后） |
 *
 * 用户在这一屏只有一个疑问：「我按了会怎样」。两个按钮都必须正面回答它 ——
 * 而它们要回答的恰好是两件不同的事，所以这里是两个不同的字段，不是一个。
 */
@Composable
private fun androidx.compose.foundation.layout.RowScope.ChoiceButton(
    label: String,
    hint: String,
    primary: Boolean,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .weight(1f)
            .clip(RoundedCornerShape(12.dp))
            .background(if (primary) Amber else Hairline.copy(alpha = 0.30f))
            .pressFeedback(onClick = onClick)
            .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = label,
            style = AppText.title,
            color = if (primary) Bg else InkMid,
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = hint,
            style = AppText.pill,
            color = if (primary) Bg.copy(alpha = 0.75f) else InkFaint,
        )
    }
}

/**
 * 一档评分。上面是「用户对自己记忆的判断」，下面是**这个判断的后果**（几天后）。
 *
 * ⚠️ `Good` 用实心琥珀：它是默认选项，也是绝大多数时候该按的那个。
 *    四个按钮视觉权重完全一样的话，用户每次都要读一遍四个词才能决定。
 */
@Composable
private fun androidx.compose.foundation.layout.RowScope.RatingButton(
    label: String,
    rating: Int,
    intervals: Map<Int, Int>,
    primary: Boolean,
    onRate: (Int) -> Unit,
) {
    Column(
        Modifier
            .weight(1f)
            .clip(RoundedCornerShape(11.dp))
            .background(if (primary) Amber else Hairline.copy(alpha = 0.30f))
            .pressFeedback(onClick = { onRate(rating) })
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = label,
            style = AppText.body,
            color = if (primary) Bg else InkMid,
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = intervalLabel(intervals[rating] ?: 0),
            style = AppText.pill,
            // ⚠️ primary 上的小字不能用 InkFaint —— 琥珀底 + 浅灰字读不清。
            //    要用同一个 Bg 色、靠字号和后一档透明度区分层级。
            color = if (primary) Bg.copy(alpha = 0.75f) else InkFaint,
        )
    }
}

/**
 * 「几天后」。
 *
 * ⚠️ 超过一个月就换成月 —— 「第 37 天」这种精度对用户没有意义，
 *    而「1 个月后」能让他立刻感觉到「这个词我记住了」。
 *    这正是复习这个功能想给人的正反馈。
 */
private fun intervalLabel(days: Int): String = when {
    days <= 0 -> "马上"
    days == 1 -> "明天"
    days < 30 -> "$days 天后"
    days < 365 -> "${days / 30} 个月后"
    else -> "一年后"
}
