package com.example.englishcommute.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.englishcommute.ui.theme.Amber
import com.example.englishcommute.ui.theme.AmberFaint
import com.example.englishcommute.ui.theme.AppText
import com.example.englishcommute.ui.theme.Gloss
import com.example.englishcommute.ui.theme.Hairline
import com.example.englishcommute.ui.theme.Ink
import com.example.englishcommute.ui.theme.InkDim
import com.example.englishcommute.ui.theme.InkFaint
import com.example.englishcommute.ui.theme.InkMid
import com.example.englishcommute.ui.theme.Space
import com.example.englishcommute.ui.theme.pressFeedback
import com.example.englishcommute.word.WordCard
import com.example.englishcommute.word.WordEntry
import com.example.englishcommute.word.WordFilter
import com.example.englishcommute.word.WordLookup

/**
 * 生词页（第 4 个页签）。
 *
 * ## 这一屏要回答的问题只有一个：**现在该做什么**
 *
 * 所以最上面不是列表，是一块「今天要复习 N 个」。复习这件事需要被推着做 ——
 * 一上来就是一大片词，用户会往下滑两下然后退出去。
 *
 * ## 列表行的信息取舍
 *
 * 折叠态只给三样：**词 + 音标**（认不认识）、**第一条释义**（想起来没有）、
 * **下次什么时候复习**（这个功能在替我做什么）。点开才给全部释义、教材例句、
 * 以及那条「你听到的」。
 *
 * ## 复习入口就是这块 banner 上的「开始复习」
 *
 * 它只在**今天确实有到期词**时出现（`dueCount > 0`）。没有到期词时那块 banner
 * 写的是「今天的复习做完了」—— 那是个结论，不是一个可点的东西。
 *
 * ⚠️ 也就是说**按钮的出现与「点了有用」是同一个判据**，不会出现
 *    「点了没反应」。1.2.x 曾经在这里放过一个占位按钮，那比没有更糟。
 */
@Composable
fun WordScreen(vm: WordViewModel = viewModel()) {
    LaunchedEffect(Unit) { vm.start() }

    val cards by vm.cards.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val counts by vm.counts.collectAsStateWithLifecycle()
    val dueCount by vm.dueCount.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val entries by vm.entries.collectAsStateWithLifecycle()
    val review by vm.review.collectAsStateWithLifecycle()
    val celebrate by vm.celebrate.collectAsStateWithLifecycle()

    val totalCount = counts[WordFilter.ALL] ?: 0

    // ⚠️ 复习中按系统返回键，期望的是「回到生词本」，不是「退出 App」。
    //    不拦的话 Back 会直接冒泡到 Activity —— 用户复习到第 8 张手滑按到，
    //    整个 App 退出，他会以为进度丢了（其实已经逐张落盘了，但他不知道）。
    BackHandler(enabled = review != null) { vm.endReview() }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader()

            DueBanner(
                dueCount = dueCount,
                total = totalCount,
                onStartReview = {
                    // 返回 false 表示「队列是空的」（理论上到不了这里：按钮只在
                    // dueCount > 0 时出现）。所以不做处理，也不假装点了有用。
                    vm.startReview()
                },
            )

            FilterBar(
                selected = filter,
                counts = counts,
                onSelect = vm::select,
            )

            when {
                loading -> CenterHint("正在打开生词本…")
                cards.isEmpty() -> EmptyState(filter, totalCount)
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 24.dp),
                ) {
                    items(cards, key = { it.lemma }) { card ->
                        WordRow(
                            card = card,
                            entry = entries[card.lemma],
                            lookup = { vm.lookup(card.lemma) },
                            onRemove = { vm.remove(card.lemma) },
                        )
                    }
                }
            }
        }

        // 复习页整屏覆盖。**放在 Column 之后** —— Compose 里的 z 序就是声明顺序，
        // 写在前面会被列表盖住（而且它自己带不透明背景，所以视觉上是整屏替换）
        review?.let { state ->
            ReviewScreen(vm = vm, state = state, onClose = { vm.endReview() })
        }
    }

    // 庆祝弹窗。`celebrate` 非 null 时它同时带着「这次过完几个」
    celebrate?.let { count ->
        ReviewDoneDialog(
            count = count,
            total = totalCount,
            onDismiss = { vm.dismissCelebrate() },
        )
    }
}

// =========================================================================
// 顶部
// =========================================================================

/**
 * 页刊头。**与素材库/打卡/设置同构**：`display` 大标题 + 一行 `caption` 副标题。
 *
 * ## 副标题写什么
 *
 * 打卡页写的是**规则**（「每天听满 40 分钟即算打卡」），素材库写的是**状态**
 * （「11 个来源 · 每天更新」）。生词本选规则 —— 因为「词从哪来」是这一页
 * 唯一需要教的事，而它只在这里出现一次（列表里的空态会再说一次，
 * 但那时用户已经知道该去哪了）。
 *
 * ⚠️ 别再写「共 N 个生词」当副标题：那个数字一变，标题行就会跟着变宽变窄，
 *    而它下面那块横幅已经写了「共 N 个生词」。
 */
@Composable
private fun ScreenHeader() {
    Column(
        Modifier.padding(
            start = Space.pageH,
            end = Space.pageH,
            top = Space.section,
            bottom = Space.row,
        )
    ) {
        Text("生词本", style = AppText.display)
        Spacer(Modifier.height(Space.tight))
        Text("长按播放页里的词就能加进来", style = AppText.caption)
    }
}

/**
 * 「今天要复习 N 个」。
 *
 * 一个词都没有的时候不显示 —— 那时候下面的空态文案已经把该说的话说完了，
 * 再顶一块「今天要复习 0 个」只是噪音。
 */
@Composable
private fun DueBanner(dueCount: Int, total: Int, onStartReview: () -> Unit) {
    if (total == 0) return
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.pageH)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
            .background(if (dueCount > 0) AmberFaint else Hairline.copy(alpha = 0.35f))
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Column {
            if (dueCount > 0) {
                Text(
                    text = "今天要复习 $dueCount 个",
                    style = AppText.title,
                    color = Ink,
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        // 「约 N 分钟」按每个词 15 秒估 —— 一个词要看释义、回忆、评分，
                        // 通勤路上做不到更快；给一个偏保守的数字比给一个偏乐观的好
                        text = "约 ${estimateMinutes(dueCount)} 分钟 · 共 $total 个生词",
                        style = AppText.gloss,
                        color = InkDim,
                    )
                    Spacer(Modifier.weight(1f))
                    Box(
                        Modifier
                            // 深色实心：banner 的底是 AmberFaint（很浅），
                            // 在那里放实心琥珀按钮对比不够，深色够
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(9.dp))
                            .background(Ink)
                            .pressFeedback(onClick = onStartReview)
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = "开始复习",
                            style = AppText.pill,
                            color = com.example.englishcommute.ui.theme.Bg,
                        )
                    }
                }
            } else {
                Text(
                    text = "今天的复习做完了",
                    style = AppText.title,
                    color = Ink,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "共 $total 个生词 · 明天会有一批到期",
                    style = AppText.gloss,
                    color = InkDim,
                )
            }
        }
    }
    Spacer(Modifier.height(14.dp))
}

private fun estimateMinutes(count: Int): Int = (count * 15 + 59) / 60

/** 四档筛选。**带计数** —— 「已掌握 0 个」和「已掌握 30 个」是两种完全不同的心情 */
@Composable
private fun FilterBar(
    selected: WordFilter,
    counts: Map<WordFilter, Int>,
    onSelect: (WordFilter) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.pageH, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        WordFilter.entries.forEach { f ->
            val n = counts[f] ?: 0
            val on = f == selected
            Box(
                Modifier
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(9.dp))
                    .background(if (on) Ink else Hairline.copy(alpha = 0.28f))
                    .pressFeedback(onClick = { onSelect(f) })
                    .padding(horizontal = 11.dp, vertical = 7.dp)
            ) {
                Text(
                    text = if (n > 0) "${f.label} $n" else f.label,
                    style = AppText.pill,
                    color = if (on) com.example.englishcommute.ui.theme.Bg else InkMid,
                )
            }
        }
    }
    Spacer(Modifier.height(6.dp))
}

// =========================================================================
// 列表行
// =========================================================================

/**
 * 一行生词。
 *
 * 折叠时只给三样东西（见 [WordScreen] 的注释），点一下展开看全部。
 * 用行内展开而不是跳详情页：用户此刻在做的事是「扫一遍我加了哪些词」，
 * 每点一次都要跳走再退回来的话，扫读就断了。
 */
@Composable
private fun WordRow(
    card: WordCard,
    entry: WordEntry?,
    lookup: suspend () -> WordLookup?,
    onRemove: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxWidth()
            .pressFeedback(onClick = { expanded = !expanded })
            .padding(horizontal = Space.pageH, vertical = 11.dp)
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = card.lemma,
                style = AppText.title,
                color = Ink,
            )
            if (entry?.hasIpa == true) {
                Spacer(Modifier.width(8.dp))
                Text(text = "/${entry.ipa}/", style = AppText.gloss, color = InkFaint)
            }
            Spacer(Modifier.weight(1f))
            Text(text = dueLabel(card), style = AppText.pill, color = InkDim)
        }

        if (entry != null && entry.defs.isNotEmpty()) {
            Spacer(Modifier.height(3.dp))
            Text(
                text = if (expanded) entry.defs.joinToString("  ／  ") else entry.defs.first(),
                style = AppText.gloss,
                color = InkMid,
                maxLines = if (expanded) Int.MAX_VALUE else 1,
            )
        }

        AnimatedVisibility(visible = expanded) {
            ExpandedDetail(card = card, lookup = lookup, onRemove = onRemove)
        }
    }
    Spacer(Modifier.height(1.dp))
    Box(Modifier.fillMaxWidth().padding(start = Space.pageH).height(1.dp).background(Hairline.copy(alpha = 0.5f)))
}

@Composable
private fun ExpandedDetail(
    card: WordCard,
    lookup: suspend () -> WordLookup?,
    onRemove: () -> Unit,
) {
    // 例句要现查（WordEntry 里没有）—— 展开时才查，折叠时一次都不查
    val result by produceState<WordLookup?>(initialValue = null, card.lemma) {
        value = lookup()
    }

    Column(Modifier.padding(top = 10.dp)) {
        // 「你听到的」：出处 + 那句原文 + 中文
        val ctx = card.contexts.firstOrNull()
        if (ctx != null) {
            Text("你听到的", style = AppText.label, color = InkFaint)
            Spacer(Modifier.height(4.dp))
            Text(ctx.sentence, style = AppText.gloss, color = InkMid)
            if (!ctx.translation.isNullOrEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(ctx.translation, style = AppText.gloss, color = Gloss)
            }
            Spacer(Modifier.height(3.dp))
            Text(ctx.episodeTitle, style = AppText.pill, color = InkFaint)
        }

        // 教材例句（如果有）。**与「你听到的」分开标名** ——
        // 前者是编教材的人写的规范句，后者是转写来的真实语料，两者性质不同
        val example = result?.examples?.firstOrNull()
        if (example != null) {
            Spacer(Modifier.height(12.dp))
            Text("教材例句", style = AppText.label, color = InkFaint)
            Spacer(Modifier.height(4.dp))
            Text(example.en, style = AppText.gloss, color = InkMid)
            Spacer(Modifier.height(2.dp))
            Text(example.zh, style = AppText.gloss, color = Gloss)
        }

        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = reviewStat(card),
                style = AppText.pill,
                color = InkFaint,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "移出生词本",
                style = AppText.pill,
                color = InkDim,
                modifier = Modifier
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                    .pressFeedback(onClick = onRemove)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}

// =========================================================================
// 空态
// =========================================================================

/**
 * 空态要分清**三种**情况 —— 它们的下一步动作完全不同：
 *
 * 1. 一个词都没有 → 告诉他去哪儿加（这是唯一一次需要教他的机会）
 * 2. 当前这一档是空的，但别的档有 → 告诉他可以去哪一档看
 * 3. 待复习是空的，但生词本有东西 → **这是好消息**，不该写得像出了问题
 */
@Composable
private fun EmptyState(filter: WordFilter, total: Int) {
    val (title, hint) = when {
        total == 0 -> "还没有生词" to
            "在播放页长按字幕里的任意一个词，就能把它加进来。\n复习时会用你自己听过的那句话来考你。"

        filter == WordFilter.DUE -> "今天的复习做完了" to
            "共 $total 个生词。明天会有新的一批到期。"

        filter == WordFilter.MASTERED -> "还没有已掌握的词" to
            "一个词连续记得住、下次复习排到一个月以后，就会归到这里。"

        else -> "这一档还没有词" to
            "切到「全部」看看，共 $total 个生词。"
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 36.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = AppText.title, color = InkMid)
        Spacer(Modifier.height(8.dp))
        Text(hint, style = AppText.body, color = InkFaint)
    }
}

@Composable
private fun CenterHint(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, style = AppText.body, color = InkFaint)
    }
}

// =========================================================================
// 文案
// =========================================================================

/**
 * 下次什么时候复习。
 *
 * ⚠️ 这里说「今天 / 明天 / N 天后」而不是**具体日期**：
 * 用户关心的是「离下次还有多久」，而不是「10 月 3 号」——
 * 后者还要在他脑子里和今天的日期做一次减法。
 */
private fun dueLabel(card: WordCard, now: Long = System.currentTimeMillis()): String {
    if (card.isNew) return "新词"
    val days = (card.fsrs.dueAtMillis - now) / 86_400_000L
    return when {
        days < 1L -> "今天"
        days == 1L -> "明天"
        days < 30L -> "$days 天后"
        days < 365L -> "${days / 30} 个月后"
        else -> "一年后"
    }
}

private fun reviewStat(card: WordCard): String = buildString {
    append("复习过 ${card.reps} 次")
    if (card.lapses > 0) append(" · 忘过 ${card.lapses} 次")
    append(" · 加入于 ")
    append(java.text.SimpleDateFormat("M月d日", java.util.Locale.CHINA).format(java.util.Date(card.addedAt)))
}
