package com.example.englishcommute.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.englishcommute.checkin.DayKey
import com.example.englishcommute.ui.theme.Amber
import com.example.englishcommute.ui.theme.AmberFaint
import com.example.englishcommute.ui.theme.AppText
import com.example.englishcommute.ui.theme.Bg
import com.example.englishcommute.ui.theme.BgRaised
import com.example.englishcommute.ui.theme.Card
import com.example.englishcommute.ui.theme.CardEdge
import com.example.englishcommute.ui.theme.Hairline
import com.example.englishcommute.ui.theme.HairlineStrong
import com.example.englishcommute.ui.theme.Ink
import com.example.englishcommute.ui.theme.InkDim
import com.example.englishcommute.ui.theme.InkFaint
import com.example.englishcommute.ui.theme.InkMid
import com.example.englishcommute.ui.theme.Metric
import com.example.englishcommute.ui.theme.Olive
import com.example.englishcommute.ui.theme.Radius
import com.example.englishcommute.ui.theme.Rule
import com.example.englishcommute.ui.theme.Space
import com.example.englishcommute.ui.theme.pressFeedback
import kotlinx.coroutines.delay

/**
 * 打卡页 —— 第二个页签。
 *
 * ## 这一页要回答的三个问题，顺序不能变
 *
 * 1. **今天还差多少** —— 这是唯一能驱动行为的数字。放在最上面，字号最大
 * 2. **连了多少天 / 最长多少** —— 成就，用户来这一页主要是为了看它
 * 3. **海报与记录** —— 分享和回顾，属于「看完了顺手做点什么」
 *
 * ## 版式沿用素材库那套「编辑部排版」
 *
 * 不用卡片、不用圆角，靠 1px 细线和留白分组。原因和素材库一样：
 * 这一页的核心内容是一串数字，卡片只会把它包装成一个仪表盘。
 */
@Composable
fun CheckInScreen(vm: CheckInViewModel = viewModel()) {
    val context = LocalContext.current
    val days by vm.days.collectAsStateWithLifecycle()
    val today by vm.today.collectAsStateWithLifecycle()
    val poster by vm.poster.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val hint by vm.hint.collectAsStateWithLifecycle()
    // 3.3.0：目标可自定义了。它一变，这一页上**所有相对目标的量**都要跟着重算
    // （进度条、还差多少、日历格子的深浅、连续天数、海报），
    // 所以它和 days/today 是同一级的输入，而不是某个子块内部的常量
    val goalMinutes by vm.goalMinutes.collectAsStateWithLifecycle()
    // 3.4.0：**明天起**的目标。它只影响「每日目标」那一块的说明文字与高亮 ——
    // 因为它还没生效，所以这一页其他任何数字都不该看它
    val tomorrowGoalMinutes by vm.tomorrowGoalMinutes.collectAsStateWithLifecycle()

    // 今天达标但海报还没生成（上一次失败 / 缓存被清）→ 补一张。
    //
    // ⚠️ 3.4.0 把 `goalMinutes` 从这个 key 列表里去掉了，理由要写清楚：
    //    今天的达标阈值是 `goalSecondsOn(today)`，而改目标只写 `today + 1` 的条目
    //    （见 `CheckInStore.setGoalMinutes`）—— 所以改一次目标**不可能**影响今天这张图。
    //    3.3.0 把它当 key 是那会儿的必然（改目标会翻转今天的达标），
    //    现在留着只会白重画一张内容完全相同的海报。
    //    ★ 哪天有人让 `setGoalMinutes` 也能写今天的条目，这里必须一起改回来 ——
    //      判据是「改目标只写 today + 1」，`verify-checkin-goal.py` 有断言守着。
    LaunchedEffect(days, today) { vm.ensureTodayPoster() }

    val seconds = days[today] ?: 0
    val goal = goalMinutes * 60
    val done = seconds >= goal
    val streak = remember(days, today, goalMinutes) { vm.currentStreak() }
    val best = remember(days, today, goalMinutes) { vm.bestStreak() }
    val active = remember(days) { vm.activeDays() }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = Space.pageH,
            end = Space.pageH,
            top = Space.section,
            bottom = 40.dp,
        ),
    ) {
        item(key = "masthead") { Masthead(goalMinutes) }

        item(key = "today") {
            TodayBlock(seconds = seconds, goal = goal, done = done, streak = streak)
        }

        // 目标就放在「今天」下面：它解释的就是上面那一圈进度是按什么算的。
        // 放到页面末尾（设置项惯常的位置）会让人先看到「还差 12 分钟」
        // 再去别处找那个 40 是从哪来的
        item(key = "goal") {
            GoalBlock(
                todayMinutes = goalMinutes,
                tomorrowMinutes = tomorrowGoalMinutes,
                presets = vm.goalPresets,
                onSelect = vm::setGoalMinutes,
            )
        }

        item(key = "streak") {
            StreakBlock(current = streak, best = best, active = active, goalMinutes = goalMinutes)
        }

        item(key = "calendar") {
            CalendarBlock(vm = vm, days = days, today = today)
        }

        if (done) {
            item(key = "poster") {
                PosterBlock(
                    bitmap = poster,
                    busy = busy,
                    streak = streak,
                    seconds = seconds,
                    onShare = { vm.shareToday(context) },
                )
            }
        } else if (poster != null) {
            // 达标过、但因为跨天/重置又回到未达标：把上一张收起来，
            // 避免出现「今天还没听却已经有一张今天的海报」这种矛盾
            item(key = "poster-stale") { Spacer(Modifier.height(0.dp)) }
        }

        item(key = "records") {
            RecordBlock(vm = vm, days = days, today = today)
        }

        item(key = "colophon") {
            Colophon(
                activeDays = active,
                totalSeconds = days.values.sumOf { it.toLong() },
                onReset = vm::resetAll,
            )
        }
    }

    hint?.let { msg ->
        HintBar(message = msg, onDismiss = vm::clearHint)
    }
}

// ---------------------------------------------------------------------------
// 卡片容器（1.2.10）
// ---------------------------------------------------------------------------

/**
 * 打卡页的一张卡片。
 *
 * ## 为什么这一页也要卡片化
 *
 * 1.2.8 只把素材库列表卡片化了，这一页仍然是「小标签 + 内容」直接排在底色上 ——
 * 连 `Rule()` 都没画（靠留白分组）。深色下底色与内容之间没有任何可见的分界，
 * 整页糊成一片。
 *
 * ## 内边距为什么是 16dp，而不是素材库那 13dp
 *
 * 三页各有各的节奏，这是 1.2.0 定下、这次改动**没有**抹平的东西：
 *
 * | 页 | 性格 | 卡内边距 |
 * |---|---|---|
 * | 素材库 | 流（一屏好几条） | 13dp × fontScale |
 * | 打卡 | **单焦点**（一天只有一个「今日」） | 16dp |
 * | 设置 | 密集列表（来找某一项） | 14dp |
 *
 * 打卡页卡片里的东西少而大，给它更宽的呼吸空间才撑得住。
 * 三处不一样是**有意的**，不要顺手统一。
 */
@Composable
private fun CheckInCard(content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(Radius.card)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = Space.block)
            .clip(shape)
            .background(Card)
            .border(1.dp, CardEdge, shape)
            .padding(16.dp),
        content = content,
    )
}

// ---------------------------------------------------------------------------
// 刊头
// ---------------------------------------------------------------------------

@Composable
private fun Masthead(goalMinutes: Int) {
    Column(Modifier.padding(bottom = Space.block)) {
        Text("打卡", style = AppText.display)
        Spacer(Modifier.height(Space.tight))
        Text(
            text = "每天听满 $goalMinutes 分钟即算打卡",
            style = AppText.caption,
        )
    }
}

// ---------------------------------------------------------------------------
// 每日目标（3.3.0）
// ---------------------------------------------------------------------------

/**
 * 每日目标：一排档位，点一下就改。**改的是「明天起」，今天不动。**
 *
 * ## 为什么是几档预设，不是滑杆
 *
 * 「今天该听多久」是个**粗判断**（通勤单程 / 双程 / 刻意多听），不是需要
 * 精确到分钟的参数。滑杆会逼用户在一个连续区间上做一个假精确的选择，
 * 而且滑杆天生适合「5…180」这种范围 —— 那里面 45、55 这种数没有任何意义。
 * 六个档位一屏放得下、一眼能比较，点一下就是一个决定。
 *
 * ## 为什么不是「立刻生效」（3.4.0 改的）
 *
 * 3.3.0 是立刻生效，而且会**重算全部历史** —— 把目标调高，过去按旧目标算过的
 * 日子会一起失效、连续天数当场掉。用户的原话是「从第二天开始生效，不要改变
 * 历史的打卡记录」。改完之后这件事变成结构性的：数据层只往「明天」写条目，
 * 于是**界面这边连解释「掉了多少天」都不需要了**（见 `CheckInViewModel.setGoalMinutes`）。
 *
 * ## 高亮哪一档
 *
 * 高亮的是**设置值**：有待生效的改动时就是那个新值（并在右上角标「明天起」）。
 * 这样「我把它设成了 30」在界面上是成立的，而「今天按多少算」由下面那行字说明。
 *
 * ⚠️ 反过来（高亮今天的值、把新值只写在文案里）看起来更「诚实」，实际更糟：
 *    用户点完 30，看到高亮还在 40 上，第一反应是「没点上」。
 */
@Composable
private fun GoalBlock(
    todayMinutes: Int,
    tomorrowMinutes: Int?,
    presets: List<Int>,
    onSelect: (Int) -> Unit,
) {
    val setting = tomorrowMinutes ?: todayMinutes

    CheckInCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("每日目标", style = AppText.label, modifier = Modifier.weight(1f))
            // 高亮跳到「还不是今天的那个值」上时，必须有东西解释它 —— 否则
            // 上面写着「/ 40 分钟」、这里高亮 30，看起来像界面自相矛盾
            if (tomorrowMinutes != null) {
                Text("明天起", style = AppText.label, color = Amber)
            }
        }
        Spacer(Modifier.height(Space.row))

        // 六个档位一行放不下会换行 —— 用 chunked 主动分成两行，
        // 让每一行的宽度均匀（自动换行会得到「5 个 + 1 个」这种难看的排法）
        presets.chunked(3).forEachIndexed { i, row ->
            if (i > 0) Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { m ->
                    GoalChip(
                        minutes = m,
                        selected = m == setting,
                        modifier = Modifier.weight(1f),
                        onClick = { onSelect(m) },
                    )
                }
                // 补齐空位，保证最后一行不满时按钮宽度和上面一行一致
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }

        Spacer(Modifier.height(Space.row))
        Text(
            // ⚠️ 这一句是这一版最重要的文案：用户之所以要求改这里，就是担心
            //    「改一下目标，我攒了 30 天的连续记录会不会没了」。所以无论有没有
            //    待生效的改动，最后半句都要在
            text = if (tomorrowMinutes == null) {
                "改了从明天开始算，以前的打卡记录不变。"
            } else {
                "明天起改为 $tomorrowMinutes 分钟 · 今天仍按 $todayMinutes 分钟 · " +
                    "以前的记录不变"
            },
            style = AppText.caption,
            color = InkFaint,
        )
    }
}

/**
 * 一档目标。
 *
 * 单独写一个而不是复用设置页那个 `OptionChip`：那一个是「竖向 10dp 内边距 +
 * 小字」的胶囊，适合设置页那种密集列表；而这里的每一格要写「40 分钟」，
 * 数字需要更大的字号才读得出重点，两处的排版不同。
 */
@Composable
private fun GoalChip(
    minutes: Int,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(Radius.card)
    Row(
        modifier = modifier
            .clip(shape)
            .background(if (selected) AmberFaint else Color.Transparent)
            .border(1.dp, if (selected) Amber else HairlineStrong, shape)
            .pressFeedback(onClick = onClick)
            .padding(vertical = 11.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "$minutes",
            style = AppText.digit,
            color = if (selected) Amber else Ink,
        )
        Spacer(Modifier.width(2.dp))
        Text(
            text = "分钟",
            style = AppText.caption,
            color = if (selected) Amber else InkDim,
        )
    }
}

// ---------------------------------------------------------------------------
// 今日
// ---------------------------------------------------------------------------

@Composable
private fun TodayBlock(seconds: Int, goal: Int, done: Boolean, streak: Int) {
    val fraction = (seconds.toFloat() / goal).coerceIn(0f, 1f)
    CheckInCard {
        Text("今日", style = AppText.label)
        Spacer(Modifier.height(Space.row))

        Row(verticalAlignment = Alignment.Bottom) {
            // 主数字用 display 字号：这一页只有这一个数字值得那么大
            Text(
                text = (seconds / 60).toString(),
                style = AppText.display,
                color = if (done) Amber else Ink,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "/ ${goal / 60} 分钟",
                style = AppText.digitLarge,
                color = InkDim,
                modifier = Modifier.padding(bottom = 7.dp),
            )
        }

        Spacer(Modifier.height(Space.row))
        Rule(progress = fraction, height = 4.dp)

        Spacer(Modifier.height(Space.tight))
        Text(
            text = when {
                done -> "今天已达标"
                else -> "还差 ${DayKey.clockLabel(goal - seconds)}"
            },
            style = AppText.caption,
            color = if (done) Olive else InkMid,
        )
        if (done && streak > 0) {
            Spacer(Modifier.height(3.dp))
            Text("连续第 $streak 天", style = AppText.caption, color = Amber)
        }
    }
}

// ---------------------------------------------------------------------------
// 连续天数
// ---------------------------------------------------------------------------

@Composable
private fun StreakBlock(current: Int, best: Int, active: Int, goalMinutes: Int) {
    CheckInCard {
        Text("连续打卡", style = AppText.label)
        Spacer(Modifier.height(Space.row))
        Row(horizontalArrangement = Arrangement.spacedBy(30.dp)) {
            // valueColor 必须**点名**写：公共 Metric 的第三个位置参数是
            // modifier（见 ui/theme/Parts.kt），位置传色值会编译不过。
            // 收敛成一份之后，「改缓存数字怎么显示」只需要改一个地方
            Metric(
                label = "当前",
                value = if (current > 0) "$current 天" else "—",
                valueColor = if (current > 0) Amber else InkDim,
            )
            Metric("最长", if (best > 0) "$best 天" else "—")
            Metric("累计", "$active 天")
        }
        Spacer(Modifier.height(Space.tight))
        Text(
            // 「今天还没达标不算断」这条规则必须写出来 ——
            // 不写的话，用户早上打开看到「当前 —」会以为记录丢了
            text = if (current == 0) {
                "听满 $goalMinutes 分钟开始新一轮"
            } else {
                "今天补齐还能接上"
            },
            style = AppText.caption,
            color = InkFaint,
        )
    }
}

// ---------------------------------------------------------------------------
// 日历
// ---------------------------------------------------------------------------

/**
 * 最近 21 天（= 3 周）。
 *
 * 选 3 周而不是「最近 20 天」是有意的：21 天正好让每一列落在同一个星期几上，
 * 于是表头只需要一行、且与格子严格对齐。少一天就会错位一周。
 *
 * ⚠️ 3.4.0：格子的深浅**逐天**按那一天的目标算（`vm.goalMinutesOn(day)`），
 *    不再共用一个「当前目标」。差别只在改过目标的人身上看得见 ——
 *    而那正是这一版要修的人：过去按 30 分钟画满的格子，不该因为今天改成 60
 *    就一起变浅（那等于**偷偷重写了历史**，而且是在他最相信这张图的时候）。
 */
@Composable
private fun CalendarBlock(vm: CheckInViewModel, days: Map<Long, Int>, today: Long) {
    val dates = remember(today) { vm.calendarDays() }

    CheckInCard {
        Text("最近 3 周", style = AppText.label)
        Spacer(Modifier.height(Space.row))

        // 表头：最后一行的 7 天分别是星期几（整列同星期几，所以一行就够）
        Row(horizontalArrangement = Arrangement.spacedBy(CELL_GAP)) {
            for (col in 0 until 7) {
                val day = today - 6 + col
                Box(Modifier.size(CELL_SIZE), contentAlignment = Alignment.Center) {
                    Text(
                        text = DayKey.weekdayLabel(day).removePrefix("周"),
                        style = AppText.label,
                        color = InkFaint,
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))

        dates.chunked(7).forEach { week ->
            Spacer(Modifier.height(CELL_GAP))
            Row(horizontalArrangement = Arrangement.spacedBy(CELL_GAP)) {
                week.forEach { day ->
                    val sec = days[day] ?: 0
                    // 那一天的目标 —— 不是「当前目标」，见上面那段说明
                    val dayGoal = vm.goalMinutesOn(day) * 60
                    val ratio = (sec.toFloat() / dayGoal).coerceIn(0f, 1f)
                    DayCell(
                        dayOfMonth = dayLabel(day),
                        ratio = ratio,
                        isToday = day == today,
                        isFuture = day > today,
                    )
                }
            }
        }

        Spacer(Modifier.height(Space.row))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Legend()
        }
    }
}

/** 格子里显示「几号」。用日期的日部分，人扫一眼就能定位到具体哪天 */
private fun dayLabel(day: Long): String {
    val text = DayKey.dateLabel(day)          // 形如 "9月20日"
    return text.substringAfter('月').removeSuffix("日").take(2)
}

@Composable
private fun DayCell(dayOfMonth: String, ratio: Float, isToday: Boolean, isFuture: Boolean) {
    // 底色深浅 = 完成度。不用「达标/未达标」两档，是因为「今天听了 20 分钟」
    // 也应该在日历上留下痕迹 —— 只画达标会让大部分格子永远是空的
    val fill = when {
        isFuture -> Color.Transparent
        ratio >= 1f -> Amber
        ratio > 0f -> Amber.copy(alpha = 0.20f + 0.62f * ratio)
        else -> BgRaised
    }
    val textColor = when {
        ratio >= 0.55f -> Bg
        ratio > 0f -> InkMid
        else -> InkFaint
    }

    Box(
        modifier = Modifier
            .size(CELL_SIZE)
            .background(fill)
            .then(
                // 今天单独描一圈：不然「还没听的今天」和「听了 0 分钟的其他天」
                // 长得完全一样，用户会以为格子漏了一天
                if (isToday && ratio < 1f) Modifier.border(1.5.dp, Amber) else Modifier
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = dayOfMonth, style = AppText.digit, color = textColor)
    }
}

@Composable
private fun Legend() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(Amber))
        Spacer(Modifier.width(5.dp))
        Text("已达标", style = AppText.label, color = InkFaint)
        Spacer(Modifier.width(14.dp))
        Box(Modifier.size(10.dp).background(Amber.copy(alpha = 0.45f)))
        Spacer(Modifier.width(5.dp))
        Text("没听够", style = AppText.label, color = InkFaint)
        Spacer(Modifier.width(14.dp))
        Box(Modifier.size(10.dp).background(BgRaised))
        Spacer(Modifier.width(5.dp))
        Text("没听", style = AppText.label, color = InkFaint)
    }
}

// ---------------------------------------------------------------------------
// 海报
// ---------------------------------------------------------------------------

/**
 * 今日海报 + 分享。
 *
 * 分享按钮是实心琥珀（主操作）——「一键分享到微信」是这个功能唯一的出口，
 * 不该藏在二级入口里。
 */
@Composable
private fun PosterBlock(
    bitmap: android.graphics.Bitmap?,
    busy: Boolean,
    streak: Int,
    seconds: Int,
    onShare: () -> Unit,
) {
    CheckInCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("今日海报", style = AppText.label, modifier = Modifier.weight(1f))
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    strokeWidth = 1.5.dp,
                    color = Amber,
                )
            }
        }
        Spacer(Modifier.height(Space.row))

        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "今日打卡海报",
                modifier = Modifier
                    .fillMaxWidth()
                    // 海报是 1080×1440（3:4）。按比例给它定高，
                    // 不写死 dp —— 写死的话在不同屏宽上会拉伸变形
                    .aspectRatio(3f / 4f),
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(3f / 4f)
                    .background(BgRaised),
                contentAlignment = Alignment.Center,
            ) {
                // ⚠️ 不写「生成中…」。走到这个 else 有两种可能：几百毫秒的等待，
                // 或者渲染真的失败了。后者会让「生成中」一直挂着，读起来像卡死。
                // 所以忙碌态用转圈（它本身就是「还在动」的意思），
                // 非忙碌态给一句结论。庆祝页的 PosterFallback 是同一条规矩。
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

        Spacer(Modifier.height(Space.row))
        // 海报没就绪时分享按钮点不动（点了也没有图可发）—— 但**不能只让它点不动**：
        // 满亮琥珀按下去毫无反应，用户读到的是「App 卡了」。未就绪时整体降到 45%。
        //
        // ⚠️ 判据是**图在不在**，不是「没在忙」（1.2.7）。
        //    原来写的是 `!busy`：忙完之后按钮就变亮，而图可能仍是 null ——
        //    按下去只换回一句「稍等」。庆祝页有一模一样的第二份，
        //    两处一起改；这类「同一缺陷在两屏各存一份」在本工程反复出现，
        //    查一处时要把「另一处有没有同样的东西」当固定动作。
        // 触觉：分享是「把今天这件事输出给别人」，值得标记一下 ——
        // 与庆祝页的分享按钮是**同一处语义**，两屏都要有（1.2.8）
        val haptic = LocalHapticFeedback.current
        val ready = bitmap != null
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .alpha(if (ready) 1f else 0.45f)
                .background(Amber)
                .pressFeedback(enabled = ready) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onShare()
                }
                .padding(vertical = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("分享到微信", style = AppText.title, color = Bg)
        }
        Spacer(Modifier.height(Space.tight))
        Text(
            text = "连续 $streak 天 · 今天 ${DayKey.minutesLabel(seconds)}",
            style = AppText.label,
            color = InkFaint,
        )
    }
}

// ---------------------------------------------------------------------------
// 记录
// ---------------------------------------------------------------------------

/**
 * 打卡记录：最近 14 天里**有记录的那些**，新的在前。
 *
 * 过滤掉完全没听的日子：把空白天也列出来，一屏只有「—」，看不出任何信息。
 * 而「哪几天没听」这件事，上面的日历格子已经用留白表达了。
 *
 * ⚠️ 3.4.0：「达标/未达标」逐天判（`vm.isDone(day)`）—— 它内部按那一天的目标算。
 *    这里原来拿一个共用的 `goal` 去比，等于用今天的标准审判昨天。
 */
@Composable
private fun RecordBlock(vm: CheckInViewModel, days: Map<Long, Int>, today: Long) {
    val rows = remember(days, today) {
        (0 until 14).map { today - it }
            .filter { (days[it] ?: 0) > 0 }
    }

    CheckInCard {
        Text("打卡记录", style = AppText.label)
        Spacer(Modifier.height(Space.row))

        if (rows.isEmpty()) {
            Text(
                text = "还没有记录",
                style = AppText.body,
            )
            // ⚠️ 标签跟着容器走：这里原来是 `return@Column`，
            //    1.2.10 把容器换成 CheckInCard 之后它就成了「未解析的标签」（编译期就炸）。
            //    **换容器名时一定要 grep 一遍 `return@旧名`** ——
            //    这是隐式标签，IDE 的「重命名」不会碰它。
            return@CheckInCard
        }

        rows.forEach { day ->
            val sec = days[day] ?: 0
            val ok = vm.isDone(day)
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = DayKey.relativeLabel(day, today),
                    style = AppText.title,
                    color = if (ok) Ink else InkMid,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = DayKey.minutesLabel(sec),
                    style = AppText.digit,
                    color = InkMid,
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = if (ok) "达标" else "未达标",
                    style = AppText.label,
                    color = if (ok) Amber else InkFaint,
                )
            }
            Rule()
        }
    }
}

// ---------------------------------------------------------------------------
// 页脚
// ---------------------------------------------------------------------------

/**
 * 页脚。
 *
 * 「重置打卡记录」做成**点两次**确认而不是弹对话框：这个页面没有别的可操作项，
 * 一个系统 AlertDialog 在这套自绘视觉里非常突兀。点两次的第一次会把链接
 * 变成一句明确的问句，语义上已经足够。
 */
@Composable
private fun Colophon(activeDays: Int, totalSeconds: Long, onReset: () -> Unit) {
    var confirming by remember { mutableStateOf(false) }
    // 登录之后，清空是「所有设备一起清」—— 确认文案必须跟着变，
    // 否则用户以为只清这一台，回头在另一台上看到记录还在。
    val allDevices = signedInNow()

    // 4 秒不点就收回确认态：避免用户随手点了一次、几分钟后又误触生效
    LaunchedEffect(confirming) {
        if (confirming) {
            delay(4000)
            confirming = false
        }
    }

    Column(Modifier.padding(top = 30.dp)) {
        Rule()
        Spacer(Modifier.height(Space.row))
        Text("累计", style = AppText.label)
        Spacer(Modifier.height(Space.tight))
        Text(
            text = "${totalSeconds / 3600} 小时 ${(totalSeconds % 3600) / 60} 分",
            style = AppText.digitLarge,
        )
        Spacer(Modifier.height(3.dp))
        Text("$activeDays 天有收听记录", style = AppText.caption, color = InkFaint)

        Spacer(Modifier.height(Space.row))
        Text(
            text = when {
                !confirming -> "重置打卡记录"
                allDevices -> "再点一次，清空**所有设备**上的打卡记录"
                else -> "再点一次，清空全部打卡记录"
            },
            style = AppText.caption,
            color = if (confirming) Amber else InkFaint,
            textDecoration = TextDecoration.Underline,
            modifier = Modifier.pressFeedback {
                if (confirming) {
                    onReset()
                    confirming = false
                } else {
                    confirming = true
                }
            },
        )
    }
}

// ---------------------------------------------------------------------------
// 零件
// ---------------------------------------------------------------------------

// Metric 已收到 ui/theme/Parts.kt（1.2.2）—— 原来是素材库 / 打卡页 / 设置页各一份

/** 一次性提示条，贴在屏幕底部。自动消失，不需要用户处理 */
@Composable
private fun HintBar(message: String, onDismiss: () -> Unit) {
    LaunchedEffect(message) {
        delay(2600)
        onDismiss()
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
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

/** 格子边长与间距。7 列 + 6 个间隔要能塞进 360dp 的窄屏里 */
private val CELL_SIZE = 38.dp
private val CELL_GAP = 4.dp
