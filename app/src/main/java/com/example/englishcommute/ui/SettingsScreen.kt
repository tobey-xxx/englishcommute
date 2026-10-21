package com.example.englishcommute.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationManagerCompat
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.example.englishcommute.notify.DailyReminder
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.englishcommute.EnglishCommuteApp
import com.example.englishcommute.settings.ThemeMode
import com.example.englishcommute.word.VocabTableResult
import com.example.englishcommute.ui.theme.Amber
import com.example.englishcommute.ui.theme.AmberFaint
import com.example.englishcommute.ui.theme.AppText
import com.example.englishcommute.ui.theme.Bg
import com.example.englishcommute.ui.theme.Card
import com.example.englishcommute.ui.theme.CardEdge
import com.example.englishcommute.ui.theme.Crimson
import com.example.englishcommute.ui.theme.Hairline
import com.example.englishcommute.ui.theme.HairlineStrong
import com.example.englishcommute.ui.theme.Ink
import com.example.englishcommute.ui.theme.InkDim
import com.example.englishcommute.ui.theme.InkFaint
import com.example.englishcommute.ui.theme.InkMid
import com.example.englishcommute.ui.theme.Olive
import com.example.englishcommute.ui.theme.Radius
import com.example.englishcommute.ui.theme.Space
import com.example.englishcommute.ui.theme.formatBytes
import com.example.englishcommute.ui.theme.pressFeedback
import kotlinx.coroutines.launch

/**
 * 设置页。
 *
 * ## 版式
 *
 * 和素材库同一套语言：刊头 + 若干区块，区块之间用卡片分隔（1.2.10 起不再用 1px 细线 ——
 * 靠 1px 分组在深色下不成立，而这一页有六七个区块，那是六七条看不见的线）。
 *
 * 区块标题用 16sp 无衬线，而不是刊头那种衬线：一页里有六七个衬线标题会变成
 * 噪音。衬线在这个 App 里只属于「刊头」和「节目标题」两个位置。
 *
 * ## 2.1.0：整页少了两个区块，而这不是「简化」而是**更新**
 *
 * 它原来叫 `ModelSetupScreen`，因为它一半的内容是「语音识别模型」——
 * 630 MB 的下载进度、就绪状态、重试按钮、「删除后重新下载」，
 * 外加一个「自检：转写一段音频」的区块（选一段本地音频，看识别能不能跑通）。
 *
 * 端侧识别与翻译整条拆掉之后，这些东西**没有对应的东西可指**：
 * 没有模型可下、没有引擎可自检。留着它们就是留着两个「点了没反应或点了报错」的
 * 入口 —— 而 2.0.3 修了一整轮的正是这类问题（界面留着入口、后端已经不存在）。
 *
 * 所以：删掉，并把省出来的位置给一句**用户真的会问**的话
 * （见 [SubtitleBlock]：「我升级完少了 700 多兆，字幕还有吗」）。
 */
@Composable
fun SettingsScreen(
    onOpenDiag: () -> Unit,
    settingsVm: SettingsViewModel = viewModel(),
) {
    val backgroundWork by settingsVm.backgroundWorkEnabled.collectAsStateWithLifecycle()
    val reminderOn by settingsVm.reminderEnabled.collectAsStateWithLifecycle()
    val storage by settingsVm.storage.collectAsStateWithLifecycle()
    val themeMode by settingsVm.themeMode.collectAsStateWithLifecycle()
    val diag by settingsVm.diag.collectAsStateWithLifecycle()

    // 用 background 而不是 Surface：Surface 会带一层 Material 的 tonal elevation
    // 逻辑，在这套「无卡片底色」的版式里没有任何用处，反而多一层不确定的颜色来源
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Space.pageH),
    ) {
        Spacer(Modifier.height(Space.section))
        Masthead()
        Spacer(Modifier.height(Space.block))

        // 4.0.0：账号**放在最上面**（用户要求）。
        // 理由站得住：它是「这台设备上的东西属于谁」，而登录状态会改变
        // 下面每一块数据的含义（记录是只在这台机器上、还是在多台之间同步）——
        // 先看它，后面的数字才读得对。
        AccountBlock()

        AppearanceBlock(current = themeMode, onSelect = settingsVm::setThemeMode)

        BackgroundSubtitleBlock(
            enabled = backgroundWork,
            onChange = settingsVm::setBackgroundWorkEnabled,
        )

        // 4.2.0：每晚 23:30 的提醒（本地闹钟，不是推送）
        ReminderBlock(
            enabled = reminderOn,
            onChange = settingsVm::setReminderEnabled,
        )

        StorageBlock(
            info = storage,
            onClearPlayed = settingsVm::clearPlayedAudio,
            onRefresh = settingsVm::refreshStorage,
        )

        // 2.2.1：App 自己的版本与更新。放在「存储」之后 ——
        // 它跟内容无关，属于「这个 App 本身」的事，而最下面那块是诊断
        //
        // 3.3.0：词库排在它**上面**，两块挨着 —— 用户在这里要能一眼看出
        // 「词库有自己的更新按钮，跟下面那个 App 版本不是一回事」，
        // 那正是这一轮加它的理由。分开摆的话，这个区别要靠读文案才知道
        VocabBlock()

        UpdateBlock()

        SubtitleBlock()

        DiagBlock(
            info = diag,
            onOpen = onOpenDiag,
            onCapture = settingsVm::captureDiagState,
        )

        Colophon()

        Spacer(Modifier.height(40.dp))
    }
}

// ---------------------------------------------------------------------------
// 区块骨架
// ---------------------------------------------------------------------------

/**
 * 一个区块：标题（可带右侧动作）+ 内容。
 *
 * ## 1.2.10：从「标题 + 细线」变成**卡片**
 *
 * 与素材库列表是同一条理由：靠 1px `hairline` 分组在深色下不成立
 * （它压在 `Bg` 上只有约 1.1:1）。卡片改用「底的亮度差 + 描边」表达分组，
 * 两种主题下都读得出来。
 *
 * ⚠️ 卡片**故意不加** `pressFeedback`：整块不可点，可点的都在里面。
 * 给它加缩放，会让「点里面的开关」时整张卡一起缩 —— 那是错的反馈。
 */
/**
 * 每晚 23:30 的提醒。
 *
 * ## 文案要回答的两件事
 *
 * ① **为什么关掉系统通知就收不到**（而不是让他以为是 bug）；
 * ② **什么情况下它也会失效**（强行停止 / 国产 ROM 的后台限制）——
 *    这句必须写出来：不写的话，用户某天没收到就会以为功能坏了。
 */
@Composable
private fun ReminderBlock(
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    // 开关拨动后要重新读一次系统权限状态
    val notificationsOn = remember(enabled) {
        NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    Block(
        title = "每日提醒",
        trailing = { Switch(checked = enabled, onCheckedChange = onChange, colors = switchColors()) },
    ) {
        Text(
            text = "每晚 23:30 提醒你还没听的英语。今天已经达标就不打扰。",
            style = AppText.caption,
            color = InkDim,
        )
        if (enabled && !notificationsOn) {
            Spacer(Modifier.height(Space.row))
            Text(
                text = "系统里没给这个 App 通知权限，提醒不会出现。" +
                    "去「设置 → 应用 → 通勤英语 → 通知」里打开。",
                style = AppText.caption,
                color = Amber,
            )
        }
        if (enabled) {
            // 4.2.1：把「系统里到底排上了什么」显示出来 —— 这是唯一能自证的一句话
            val scheduled = remember(enabled) { DailyReminder.lastScheduled(context) }
            Spacer(Modifier.height(Space.row))
            Text(
                text = when {
                    scheduled == null -> "★ 系统里还没有排上（打开一次 App 会重排）"
                    scheduled.strategy == "inexact" ->
                        "下次提醒 ${stamp(scheduled.at)}（不精确，系统可能晚几分钟）"
                    else -> "下次提醒 ${stamp(scheduled.at)}"
                },
                style = AppText.caption,
                color = if (scheduled == null) Amber else InkDim,
            )

            Spacer(Modifier.height(Space.row))
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                LinkText("现在试一条", color = InkDim) {
                    // ★ 这一步把「通知通道通不通」和「闹钟会不会响」分开：
                    //   能收到 → 通知没问题，问题在闹钟（看 ROM 的设置）
                    //   收不到 → 通知权限 / 通道的问题，跟闹钟无关
                    DailyReminder.ensureChannel(context)
                    DailyReminder.notifyNow(context, force = true)
                }
                if (!remember(enabled) { DailyReminder.exactAllowed(context) }) {
                    LinkText("允许精确闹钟", color = Amber) {
                        runCatching {
                            context.startActivity(
                                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                                    .setData(Uri.parse("package:" + context.packageName))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(Space.row))
            Text(
                text = "⚠️ 如果手机管家把本应用「强行停止」，闹钟会失效，直到你再打开一次 App。" +
                    "国产手机还需要在「自启动 / 后台运行」里放行。",
                style = AppText.caption,
                color = InkFaint,
            )
        }
    }
}

@Composable
internal fun Block(
    title: String,
    trailing: @Composable (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(Radius.card)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Space.block)
            .clip(shape)
            .background(Card)
            .border(1.dp, CardEdge, shape)
            .padding(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = AppText.title, modifier = Modifier.weight(1f))
            trailing?.invoke()
        }
        Spacer(Modifier.height(Space.row))
        content()
    }
}

@Composable
private fun Masthead() {
    val context = LocalContext.current
    val version = remember(context) { appVersionLabel(context) }
    // 安装时间**必须放在首屏**，不能塞进底部的诊断区 ——
    // 它的用途是「一眼判断手里这个包到底是哪一版」，
    // 需要用户滚动去找的话就等于没做（0.11.0 那次返工的教训）
    val installed = remember(context) { appInstalledLabel(context) }
    Column {
        Text("设置", style = AppText.display)
        Spacer(Modifier.height(Space.tight))
        // ⚠️ 2.1.0 换掉了这里原来的「语音识别自检」。
        //    那句话描述的是一个已经不存在的功能 —— 而这一行是用户判断
        //    「这个包有没有装对」用的，写着一个不存在的功能只会让人更糊涂。
        Text("v$version", style = AppText.caption)
        Spacer(Modifier.height(Space.tight))
        Text("安装于 $installed", style = AppText.caption)
    }
}

// ---------------------------------------------------------------------------
// 词库（3.3.0）
// ---------------------------------------------------------------------------

/**
 * 词库：**这一页上唯一一个和 App 版本无关的更新入口**。
 *
 * ## 为什么它必须单独一块、单独一个按钮
 *
 * 在此之前，词库要更新只有一条路：等下一次 App 版本（重装 18 MB）。
 * 于是「我查一个词，它说没收录」这件事对用户而言**无解** ——
 * 他能做的只有等。用户的原话就是「把词库更新功能单独做出来，
 * 不用跟着 app 版本更新」。
 *
 * ## 为什么状态那一行要说清「现在是哪一份在生效」
 *
 * 用户更新成功之后，界面上必须有东西变。只显示一个「更新」按钮的话，
 * 下完和没下看起来一模一样 —— 而这个词库更新**没有**任何别的可见效果
 * （它只影响「查得到查不到某个词」，而那是一件概率性的事）。
 * 所以这里报出**词数**和版本号：词数变多，就是这次更新的产出。
 *
 * ## 文案口径
 *
 * 一律说「词库」，不说「词表」—— 后者是构建脚本那一层的说法（词频表），
 * 而用户脑子里只有「我查词的那个库」。这条已经写进 `verify-copy-audit.py` 的
 * 黑名单，改回去会当场红。
 */
@Composable
private fun VocabBlock() {
    val context = LocalContext.current
    val app = remember(context) {
        (context.applicationContext as EnglishCommuteApp).container
    }
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var percent by remember { mutableIntStateOf(-1) }
    var note by remember { mutableStateOf<String?>(null) }
    // 版本与词数都不在 StateFlow 里（它们只在更新成功那一刻变），
    // 所以用本地状态记一份 —— 更新完手动刷新
    var version by remember { mutableStateOf(app.vocabTableSync.localVersion()) }
    var words by remember { mutableStateOf(app.vocabTableSync.localWords()) }

    Block("词库") {
        val v = version
        Text(
            text = when {
                busy && percent >= 0 -> "正在下载 $percent%"
                busy -> "正在检查…"
                v != null -> "已更新 · $words 词 · ${v.take(8)}"
                else -> "内置词库 · 跟随 App 版本"
            },
            style = AppText.body,
            color = if (v != null) Olive else InkDim,
        )
        Spacer(Modifier.height(Space.tight))
        Text(
            text = "词库可以单独更新，不用等 App 新版本。一次约 2.4 MB。",
            style = AppText.caption,
            color = InkFaint,
        )

        note?.let { msg ->
            Spacer(Modifier.height(Space.row))
            Text(msg, style = AppText.body, color = InkMid)
        }

        Spacer(Modifier.height(Space.row))
        ActionBar(
            primary = if (busy) "更新中…" else "检查词库更新",
            primaryEnabled = !busy,
            onPrimary = {
                busy = true
                percent = -1
                note = null
                scope.launch {
                    val r = app.vocabTableSync.sync { done, total ->
                        if (total > 0L) percent = ((done * 100) / total).toInt().coerceIn(0, 100)
                    }
                    when (r) {
                        is VocabTableResult.Updated -> {
                            // ★ 必须让词表缓存失效 —— 不失效的话，这一次更新
                            //   在本进程里**什么都不会发生**：内存里还是旧的那份，
                            //   而界面已经说「已更新」。用户再查一次那个词，
                            //   依然查不到（症状和没更新一模一样，且不自愈）
                            app.wordTable.invalidate()
                            version = app.vocabTableSync.localVersion()
                            words = app.vocabTableSync.localWords()
                            note = "词库已更新到 $words 词。"
                        }
                        is VocabTableResult.UpToDate -> note = "已经是最新的。"
                        is VocabTableResult.Unreachable -> {
                            // 失败必须给出**下一步**（本工程的文案纪律）。
                            // 后端那句 reason 是给日志看的，不直接抛给用户
                            note = if (r.reason.contains("还没有这个")) {
                                "服务器上还没准备好，过一会儿再试。"
                            } else {
                                "这次没连上。等网络好一点再试，已经更新过的不会退回去。"
                            }
                        }
                    }
                    busy = false
                    percent = -1
                }
            },
        )
    }
}

// ---------------------------------------------------------------------------
// 更新
// ---------------------------------------------------------------------------

/**
 * 更新区块（2.2.1）。
 *
 * ⚠️ 它和**强制更新页**用的是同一个 [UpdatePanel] —— 两处各写一份的话，
 * 「下载中 / 可安装 / 失败」这三态迟早会有一边漏掉，而漏掉的那一边
 * 恰好就是用户当时看的那一边。
 */
@Composable
private fun UpdateBlock() {
    val context = LocalContext.current
    val updater = remember(context) {
        (context.applicationContext as EnglishCommuteApp).container.updater
    }
    Block("更新") {
        UpdatePanel(updater = updater)
    }
}

// ---------------------------------------------------------------------------
// 外观
// ---------------------------------------------------------------------------

/** 三档的显示名。用「日间 / 夜间」而不是「浅色 / 深色」——后者是开发者的说法 */
private val ThemeMode.label: String
    get() = when (this) {
        ThemeMode.System -> "跟随系统"
        ThemeMode.Light -> "日间"
        ThemeMode.Dark -> "夜间"
    }

/**
 * 当前档位的一句话说明。
 *
 * 这三个 App 都是深色，用户在系统里可能从没见过它们的浅色长什么样，
 * 所以「日间」到底是白底还是别的，必须用文字点破。
 */
private val ThemeMode.hint: String
    get() = when (this) {
        ThemeMode.System -> "随手机的深浅色设置自动切换。"
        ThemeMode.Light -> "米白纸面 + 深墨，白天和强光下更清楚。"
        ThemeMode.Dark -> "近黑底面 + 暖白字，夜里看着不刺眼。"
    }

@Composable
private fun AppearanceBlock(current: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    Block("外观") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ThemeMode.entries.forEach { mode ->
                OptionChip(
                    label = mode.label,
                    selected = mode == current,
                    modifier = Modifier.weight(1f),
                    onClick = { onSelect(mode) },
                )
            }
        }
        Spacer(Modifier.height(Space.row))
        Text(current.hint, style = AppText.caption, color = InkFaint)
    }
}

/**
 * 档位选项。
 *
 * 直角 + 1px 描边，选中时描边和文字都转成强调色、底色铺一层极淡的琥珀。
 *
 * **不靠底色深浅来区分选中**：日间模式下 `AmberFaint`（12% 琥珀叠在米白纸上）
 * 与底色的对比只有 1.17:1，几乎看不出来。真正扛住区分度的是那圈描边和文字色 ——
 * 这是浅底界面必须走的思路，深色下可以偷懒用「提亮一点」，浅色下不行。
 */
@Composable
private fun OptionChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    mono: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .background(if (selected) AmberFaint else Color.Transparent)
            .border(1.dp, if (selected) Amber else HairlineStrong)
            .pressFeedback(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = if (mono) AppText.digit else AppText.caption,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            color = if (selected) Amber else InkDim,
        )
    }
}

// ---------------------------------------------------------------------------
// 后台加载字幕
// ---------------------------------------------------------------------------

/**
 * 后台加载字幕：一个开关。
 *
 * ## 3.5.0：它是原来「每日自动更新」那一块改出来的
 *
 * 那块有开关 + 六个触发时刻档位，管的是「每天几点帮你把新内容备好」。
 * 那条 24 小时的后台任务拆掉了（它只在 App 长时间待在后台时才可能被唤醒，
 * 而这个 App 的用户不会那样用 —— 而「打开 App 时自动更新」一直都在）。
 * 但**开关不能跟着一起删**：同一个开关还管着
 * 「每小时一次的后台字幕加载」和服务里的接力循环，删了它，
 * 曾经把它关掉的人会被静默地重新打开，而且再也找不到关的地方。
 *
 * 所以这里剩下的这一块，就是那个开关改名换姓后的样子。
 *
 * ## 文案口径
 *
 * - 说清「趁你没打开 App」—— 那是这件工作的全部意义（打开就有字幕 vs 打开才开始等）；
 * - 说清代价可忽略（不下载音频），因为这是用户唯一会犹豫的地方；
 * - 关掉时说清**失去什么**，而不是只说「已关闭」。
 *
 * ⚠️ 「补字幕」这个说法被术语表拦过（`verify-copy-audit.py`）：
 *    「补」暗示之前缺过，而用户并不知道缺过什么 —— 一律用「加载」。
 */
@Composable
private fun BackgroundSubtitleBlock(
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Block(
        title = "后台加载字幕",
        trailing = {
            Switch(checked = enabled, onCheckedChange = onChange, colors = switchColors())
        },
    ) {
        Text(
            text = if (enabled) {
                "趁你没打开 App，提前把最新一期的字幕加载好。只发很小的请求，" +
                    "不下载音频，流量和耗电都可以忽略。"
            } else {
                "字幕只在你打开 App 时加载。"
            },
            style = AppText.caption,
            color = InkDim,
        )
    }
}

/**
 * 开关配色。
 *
 * Material 默认的未选中态是「浅灰轨道 + 灰色滑块」，在近黑底上是一块发白的东西，
 * 在米白纸上又几乎看不见。这里两侧都显式指定，保证两套主题下都成立。
 */
@Composable
private fun switchColors() = SwitchDefaults.colors(
    checkedThumbColor = Bg,
    checkedTrackColor = Amber,
    checkedBorderColor = Amber,
    uncheckedThumbColor = InkDim,
    uncheckedTrackColor = Color.Transparent,
    uncheckedBorderColor = HairlineStrong,
)

// ---------------------------------------------------------------------------
// 存储占用
// ---------------------------------------------------------------------------

/**
 * 存储占用。
 *
 * 为什么把「音频 / 音频占用 / 字幕」分开列而不是给一个总数：
 * 用户看到「占用 300 MB」时的第一反应是「删点东西」，而**只有音频**是能安全删掉的
 * （删了下次更新会重新下），字幕跟着音频走。拆开显示才能让「该不该删」有个依据。
 *
 * ⚠️ 2.1.0 删掉了第四列「语音模型」。
 *
 *    那一列当年是回答「700 MB 里 630 MB 是模型、那个不能删」——
 *    而端侧模型这一版整条拆掉了，那一列现在恒为 `0 B`。
 *    **一个永远显示 0 的统计项比没有更糟**：用户会去查「为什么是 0、
 *    是不是没装好」，而它其实只是不存在了。
 *    升级用户的模型由 `OnDeviceModelCleanup` 删掉，这里不需要再做说明。
 */
@Composable
private fun StorageBlock(
    info: StorageInfo?,
    onClearPlayed: () -> Unit,
    onRefresh: () -> Unit,
) {
    Block(
        title = "存储占用",
        trailing = {
            Text(
                text = "重新统计",
                style = AppText.caption,
                color = InkDim,
                modifier = Modifier
                    .pressFeedback(onClick = onRefresh)
                    .padding(start = 10.dp, top = 4.dp, bottom = 4.dp),
            )
        },
    ) {
        if (info == null) {
            Text("统计中…", style = AppText.caption, color = InkFaint)
            return@Block
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Metric("音频", "${info.breakdown.audioCount} 条", Modifier.weight(1f))
            Metric("音频占用", formatBytes(info.breakdown.audioBytes), Modifier.weight(1f))
            Metric("字幕", "${info.breakdown.srtCount} 条", Modifier.weight(1f))
        }

        Spacer(Modifier.height(Space.row))
        Text(
            text = "音频可以随时清理，下次更新会重新下载。",
            style = AppText.caption,
            color = InkFaint,
        )

        if (info.doneCount > 0) {
            Spacer(Modifier.height(Space.row))
            Text(
                text = "清理已听完的音频（${info.doneCount} 条）",
                style = AppText.caption,
                color = Amber,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier
                    .pressFeedback(onClick = onClearPlayed)
                    .padding(vertical = 4.dp),
            )
        }
    }
}

/**
 * 设置页专用的**紧凑**指标。
 *
 * ⚠️ 与 `ui/theme/Parts.kt` 里那份同名，但**故意不收过去**（1.2.2 判定）：
 *
 * | | 这里 | Parts 那份 |
 * |---|---|---|
 * | 数字 | `AppText.digit`（12.5sp） | `AppText.digitLarge`（15sp） |
 * | 间距 | 4dp | `Space.tight` |
 * | 用途 | 三列挤在一行的空间统计 | 更新结果那几列 |
 *
 * 硬套大号数字会把「音频 / 音频占用 / 字幕」这一行撑变形。
 * 收敛的前提是「样式层面确定该一致」——这一份不确定，就不该进那个文件。
 */
@Composable
private fun Metric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = AppText.label, color = InkFaint)
        Spacer(Modifier.height(4.dp))
        Text(value, style = AppText.digit, color = Ink)
    }
}

// ---------------------------------------------------------------------------
// 字幕（2.1.0 新增，用来补上删掉的模型区）
// ---------------------------------------------------------------------------

/**
 * 字幕从哪来 —— 2.1.0 新增，**用来补上被删掉的模型区**。
 *
 * ## 为什么这一块必须加，而不是「删掉就算了」
 *
 * 升级用户的手机里会**凭空少掉 700 多 MB**（模型被清理掉了，见
 * `OnDeviceModelCleanup`）。用户对「我的 App 变轻了」这件事的第一反应
 * 不是高兴，而是「是不是有东西没下完 / 字幕是不是没有了」——
 * 这个区块就是回答那个问题的。
 *
 * 顺带把 2.1.0 唯一的**代价**摆在明面上：服务器连不上就没有新字幕。
 * 之前它由端侧识别兜底（虽然那台手机上从来没装过模型，兜底一次都没生效），
 * 现在它是真的没有了 —— 与其让用户自己发现，不如直接写清楚。
 *
 * ⚠️ 措辞守 copy-audit 的黑名单：不出现「端侧 / 模型 / 部署 / 就位」。
 */
@Composable
private fun SubtitleBlock() {
    Block("字幕") {
        Text(
            // ⚠️ 这里原来还有半句「不用再下载离线内容（省下约 700 MB）」。
            //    用户的原话是「什么省下 700MB 没必要吧」—— 他说得对：
            //    那个数字是**我们省下的工程成本**，不是他得到的东西。
            //    对一个新装的用户来说「省下 700 MB」甚至是无意义的（他没经历过要下的那版）。
            text = "字幕和中文翻译都由服务器生成，手机上自动加载。",
            style = AppText.caption,
            color = InkDim,
        )
        Spacer(Modifier.height(Space.row))
        Text(
            text = "偶尔连不上服务器时，那几条会标成「字幕加载中」，网络恢复后自己会好，不用管它。",
            style = AppText.caption,
            color = InkFaint,
        )
    }
}

// ---------------------------------------------------------------------------
// 诊断
// ---------------------------------------------------------------------------

@Composable
private fun DiagBlock(
    info: DiagInfo,
    onOpen: () -> Unit,
    onCapture: () -> Unit,
) {
    Block("诊断") {
        Text(
            text = "出问题时导出日志发我。日志不含音频与账号信息。",
            style = AppText.caption,
            color = InkFaint,
        )

        Spacer(Modifier.height(Space.row))

        DiagRow("日志", formatDiagBytes(info.sizeBytes), Ink)

        Spacer(Modifier.height(Space.tight))

        DiagRow(
            label = "上次退出",
            value = info.lastExit ?: "暂无记录（首次启动，或系统未提供）",
            valueColor = if (info.lastExitAbnormal) Crimson else InkDim,
        )

        if (info.jvmCrashes > 0) {
            Spacer(Modifier.height(Space.tight))
            DiagRow("累计崩溃", "${info.jvmCrashes} 次", Crimson)
        }

        Spacer(Modifier.height(Space.tight))

        // 封面走的是**另一个域**的图床，可达性和 feed 完全是两回事。
        // 失败时界面上只表现为「占位块」，所以这里必须把「哪个源没下到」摆出来
        DiagRow(
            label = "封面",
            value = info.artwork,
            valueColor = if (info.artworkBad) Amber else InkDim,
        )

        Spacer(Modifier.height(Space.tight))

        // 待补字幕条数：它单调不增 = 字幕真的在补齐，
        // 长期不动 = 服务器那边一直没轮上（或者根本连不上）
        DiagRow(
            label = "字幕未到",
            value = if (info.pendingSubs == 0) "都已加载" else "${info.pendingSubs} 条",
            valueColor = if (info.pendingSubs == 0) InkDim else Amber,
        )

        Spacer(Modifier.height(Space.row))

        GhostButton("查看诊断日志", enabled = true, onClick = onOpen)
        Spacer(Modifier.height(Space.tight))
        LinkText("记录当前状态", color = InkDim, onClick = onCapture)
    }
}

/** 诊断区块里的一行「标签 + 值」。标签固定宽度，让几行的值左对齐成一列 */
@Composable
private fun DiagRow(label: String, value: String, valueColor: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = AppText.label,
            color = InkDim,
            modifier = Modifier.width(58.dp),
        )
        Text(text = value, style = AppText.caption, color = valueColor)
    }
}

// ---------------------------------------------------------------------------
// 说明
// ---------------------------------------------------------------------------

/**
 * 页脚：回答用户**真正会担心**的那个问题（「我的音频会不会被传走」）。
 *
 * ⚠️★ 2.1.0 必须改这一句。它原来是「生成字幕不需要联网，音频不会上传」——
 *    后半句到今天仍然成立（音频永远是手机直连源站下载，从不经过我们的服务器），
 *    但**前半句反了**：2.1.0 起字幕恰恰是服务器生成的，需要联网。
 *
 *    这类「一半真一半假」的文案比整句假话更难发现 —— 用户读到「不需要联网」
 *    就不会去怀疑网络，而当下他遇到的所有字幕问题**全都是网络问题**。
 */
@Composable
private fun Colophon() {
    Column(Modifier.padding(top = Space.block)) {
        Text(
            text = "音频只在你手机上播放，不会上传。字幕由服务器生成，手机上自动加载。",
            style = AppText.caption,
            color = InkFaint,
        )
    }
}

// ---------------------------------------------------------------------------
// 按钮
// ---------------------------------------------------------------------------

/** 次按钮：只有一圈描边，没有填充 */
@Composable
private fun GhostButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, if (enabled) HairlineStrong else Hairline)
            .pressFeedback(enabled = enabled, onClick = onClick)
            .padding(vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = AppText.caption,
            color = if (enabled) InkMid else InkFaint,
        )
    }
}

/**
 * 文字链接。下划线是它的唯一标识 —— 这套视觉里没有「按钮底色」这一层
 *
 * ⚠️ 与 `ui/theme/Parts.kt` 里那份同名，但多了一层 `padding(vertical = 4.dp)`：
 *    设置页的链接常贴在分割线旁边、上下都是密集小字，不加这 4dp 点击热区
 *    会窄到不好按。素材库那份在刊头里有天然留白，不需要。
 *    同样的理由见上面那个 Metric —— **不确定该一致就不收**。
 */
@Composable
private fun LinkText(label: String, color: Color, onClick: () -> Unit) {
    Text(
        text = label,
        style = AppText.caption,
        color = color,
        textDecoration = TextDecoration.Underline,
        modifier = Modifier
            .pressFeedback(onClick = onClick)
            .padding(vertical = 4.dp),
    )
}

// ---------------------------------------------------------------------------

// formatBytes 已收到 ui/theme/Parts.kt（1.2.2）—— 它在素材库与本文件里
// 是逐字节相同的两份，而「缓存占了多少」这类文案两处必须一样。
// 上面那份 LinkText **没有**收：它的尺寸与热区本就不同，
// 属于「不确定该一致」的东西（理由写在它自己的定义处）。
//
// ⚠️ 2.1.0 顺手删掉了 SolidButton 与 formatDuration/formatTime：
//    SolidButton 原来只被「开始下载」用（模型区），两个格式化函数只被
//    「自检：转写一段音频」用。三个都没有调用点了。

/** `at` → 「今晚 23:30」/「10-19 23:30」 */
private fun stamp(at: Long): String {
    val cal = java.util.Calendar.getInstance().apply { timeInMillis = at }
    val today = java.util.Calendar.getInstance()
    val sameDay = cal.get(java.util.Calendar.DAY_OF_YEAR) == today.get(java.util.Calendar.DAY_OF_YEAR) &&
        cal.get(java.util.Calendar.YEAR) == today.get(java.util.Calendar.YEAR)
    val hm = "%02d:%02d".format(
        cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE),
    )
    return if (sameDay) "今晚 $hm" else "%02d-%02d $hm".format(
        cal.get(java.util.Calendar.MONTH) + 1, cal.get(java.util.Calendar.DAY_OF_MONTH),
    )
}
