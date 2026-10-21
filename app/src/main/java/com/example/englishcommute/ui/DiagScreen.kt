package com.example.englishcommute.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.englishcommute.diag.DeviceInfo
import com.example.englishcommute.diag.DiagLog
import com.example.englishcommute.ui.theme.Amber
import com.example.englishcommute.ui.theme.AmberFaint
import com.example.englishcommute.ui.theme.AppText
import com.example.englishcommute.ui.theme.Bg
import com.example.englishcommute.ui.theme.Crimson
import com.example.englishcommute.ui.theme.Glyph
import com.example.englishcommute.ui.theme.GlyphIcon
import com.example.englishcommute.ui.theme.HairlineStrong
import com.example.englishcommute.ui.theme.Ink
import com.example.englishcommute.ui.theme.InkDim
import com.example.englishcommute.ui.theme.InkFaint
import com.example.englishcommute.ui.theme.Rule
import com.example.englishcommute.ui.theme.Space
import com.example.englishcommute.ui.theme.pressFeedback

/**
 * 诊断日志页。
 *
 * 存在的理由很直接：出问题的现场在用户手机上，而**用户没法把 logcat 给我**。
 * 这一页让用户自己就能把现场导出来 —— 导出的是一条跨会话连续的完整时间线，
 * 包含崩溃前最后做了什么、以及系统判定的上次退出原因。
 *
 * 版式沿用编辑部那套（无卡片、细线分组、等宽正文），但**日志正文用等宽字体**
 * 不是为了好看：日志是纵向对齐的，比例字体会让每一列都错位，一眼扫不出级别。
 */
@Composable
fun DiagScreen(
    onBack: () -> Unit,
    vm: DiagViewModel = viewModel(),
) {
    val all by vm.lines.collectAsStateWithLifecycle()
    val errorsOnly by vm.errorsOnly.collectAsStateWithLifecycle()
    val summary by vm.summary.collectAsStateWithLifecycle()

    val context = LocalContext.current
    // 设备画像。采集是纯内存查询（毫秒级），启动时通常已采过一次，这里只是兜底。
    // 用 remember 而不是每次重组重算：它读的是平台值，不会变。
    val deviceInfo = remember(context) {
        runCatching { DeviceInfo.peek(context) }.getOrNull()
    }
    val listState = rememberLazyListState()
    var didInitialScroll by remember { mutableStateOf(false) }
    var confirmingClear by remember { mutableStateOf(false) }
    var confirmingRetention by remember { mutableStateOf(false) }

    val shown = remember(all, errorsOnly) {
        if (errorsOnly) all.filter { it.isDiagError() } else all
    }

    // 只在**首次**进入时定位到最新一行。
    // 不放进 every-recomposition 的 effect：用户往回翻着看的时候，
    // 新日志一到就把他拽到底部，那是没法读的。
    LaunchedEffect(shown.size) {
        if (!didInitialScroll && shown.isNotEmpty()) {
            listState.scrollToItem(shown.lastIndex)
            didInitialScroll = true
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Bg)
    ) {
        // ── 头部 ──────────────────────────────────────────────────────────
        Column(Modifier.padding(horizontal = Space.pageH)) {
            Spacer(Modifier.height(Space.section))

            Row(
                modifier = Modifier
                    .pressFeedback(onClick = onBack)
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlyphIcon(Glyph.ChevronLeft, size = 15.dp, tint = InkDim)
                Spacer(Modifier.width(6.dp))
                Text("设置", style = AppText.caption, color = InkDim)
            }

            Spacer(Modifier.height(Space.row))
            Text("诊断日志", style = AppText.display)

            Spacer(Modifier.height(Space.tight))
            Text(
                text = buildString {
                    append("v").append(appVersionLabel(context))
                    append(" · 本次运行 ").append(uptimeLabel())
                    append(" · ").append(summary.sizeLabel)
                    append(" · ").append(summary.lineCount).append(" 行")
                    if (summary.errorCount > 0) append(" · ").append(summary.errorCount).append(" 条错误")
                },
                style = AppText.caption,
                color = InkFaint,
            )

            // 上次退出原因是判断问题性质的第一个分叉，放在最显眼的位置
            summary.lastExit?.let { exit ->
                Spacer(Modifier.height(Space.row))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "上次退出",
                        style = AppText.label,
                        color = if (summary.lastExitAbnormal) Crimson else InkDim,
                        modifier = Modifier.width(58.dp),
                    )
                    Text(
                        text = exit,
                        style = AppText.caption,
                        color = if (summary.lastExitAbnormal) Crimson else InkDim,
                    )
                }
                if (summary.jvmCrashes > 0) {
                    Spacer(Modifier.height(Space.tight))
                    Text(
                        text = "累计捕获 Java 崩溃 ${summary.jvmCrashes} 次",
                        style = AppText.label,
                        color = Crimson,
                        modifier = Modifier.padding(start = 58.dp),
                    )
                }
            }

            // ── 设备画像 ────────────────────────────────────────────────────
            //
            // 「这台机器该用几个线程」不该靠猜：availableProcessors() 与
            // _SC_NPROCESSORS_CONF 的**差值**能看出 ROM 有没有用 cpuset 限制本进程；
            // 页大小则确认了 16 KB 对齐断言（build-apk.sh 断言 2 / 7）是不是在为本机工作。
            // 这两个值都只能从真机读出来 —— 光知道芯片型号推不出来。
            deviceInfo?.let { dev ->
                Spacer(Modifier.height(Space.row))
                Text("设备", style = AppText.label, color = InkDim)
                Spacer(Modifier.height(Space.tight))
                dev.rows().forEach { (k, v) ->
                    Row(
                        modifier = Modifier.padding(vertical = 2.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Text(
                            text = k,
                            style = AppText.caption,
                            color = InkFaint,
                            modifier = Modifier.width(64.dp),
                        )
                        Text(text = v, style = AppText.caption, color = InkDim)
                    }
                }
            }

            Spacer(Modifier.height(Space.block))
            Rule()
            Spacer(Modifier.height(Space.row))

            // ── 过滤 + 抓现场 ──────────────────────────────────────────────
            //
            // 「记录当前状态」放在这一行而不是底部按钮组里：它是**给日志加内容**的动作，
            // 和底部的「把日志拿出去」是两类事。混在一起会让用户以为它也是导出。
            Row(verticalAlignment = Alignment.CenterVertically) {
                DiagToggle("全部", selected = !errorsOnly, onClick = vm::toggleErrorsOnly)
                Spacer(Modifier.width(4.dp))
                DiagToggle("只看错误", selected = errorsOnly, onClick = vm::toggleErrorsOnly)
                Spacer(Modifier.weight(1f))
                DiagLink("记录当前状态") {
                    vm.captureState()
                    didInitialScroll = false
                }
            }

            Spacer(Modifier.height(Space.block))
        }

        // ── 日志正文 ──────────────────────────────────────────────────────
        if (shown.isEmpty()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (errorsOnly) "没有错误记录" else "日志是空的",
                    style = AppText.caption,
                    color = InkFaint,
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 12.dp),
            ) {
                items(shown) { line ->
                    Text(
                        text = line,
                        style = LogLineStyle,
                        color = lineColor(line),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 1.dp),
                    )
                }
            }
        }

        // ── 底部动作 ──────────────────────────────────────────────────────
        Column(Modifier.padding(horizontal = Space.pageH)) {
            Rule()
            Spacer(Modifier.height(Space.row))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                DiagAction("复制日志", Modifier.weight(1f)) { copyLog(context) }
                DiagAction("导出文件", Modifier.weight(1f)) { shareLog(context) }
                DiagAction(
                    label = if (confirmingClear) "再点确认" else "清空",
                    modifier = Modifier.weight(1f),
                    danger = confirmingClear,
                ) {
                    if (confirmingClear) {
                        vm.clear()
                        confirmingClear = false
                        didInitialScroll = false
                    } else {
                        confirmingClear = true
                    }
                }
            }
            Spacer(Modifier.height(Space.tight))
            Text(
                text = "「复制日志」取最近 $COPY_LINES 行到剪贴板，「导出文件」把完整文件交给分享面板。",
                style = AppText.label,
                color = InkFaint,
            )

            // ── 保留期演练 ────────────────────────────────────────────────
            //
            // 单独一行而不是塞进上面那排：上面三个都是「对日志的操作」，
            // 这一个会**删音频文件**。混在一起点错的代价不对等。
            Spacer(Modifier.height(Space.row))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                DiagAction(
                    label = if (confirmingRetention) "再点确认（会删音频）" else "保留期演练",
                    modifier = Modifier.weight(1f),
                    danger = confirmingRetention,
                ) {
                    if (confirmingRetention) {
                        vm.simulateRetention()
                        confirmingRetention = false
                        didInitialScroll = false
                    } else {
                        confirmingRetention = true
                    }
                }
            }
            Spacer(Modifier.height(Space.tight))
            Text(
                text = "「保留期演练」把所有没收藏的音频按「下载于 4 天前」重算一遍，再走一次正常的清理。" +
                    "用来当场验证「3 天后自动删除」—— 否则要等三天才能看到第一次清理。" +
                    "收藏的和正在播的不会被动。",
                style = AppText.label,
                color = InkFaint,
            )
            Spacer(Modifier.height(28.dp))
        }
    }
}

// ---------------------------------------------------------------------------

/**
 * 日志正文样式。
 *
 * 行高刻意压到字号的 1.35 倍：日志是密排的，行距一大，
 * 一屏只能看几行，「崩溃前后」这种需要扫一眼连续十几行的场景就没法用了。
 */
private val LogLineStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 10.sp,
    lineHeight = 13.5.sp,
    letterSpacing = 0.sp,
)

@Composable
private fun lineColor(line: String): Color = when {
    line.isDiagError() -> Crimson
    line.diagLevel() == 'W' -> Amber
    line.diagLevel() == 'D' -> InkFaint
    else -> InkDim
}

@Composable
private fun DiagToggle(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .background(if (selected) AmberFaint else Color.Transparent)
            .pressFeedback(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        // 浅底上不能靠底色深浅表达选中（amberFaint 叠纸只差 1.17:1），
        // 这里靠文字色 + 字重扛区分度
        Text(
            text = label,
            style = AppText.label,
            color = if (selected) Amber else InkDim,
        )
    }
}

@Composable
private fun DiagAction(
    label: String,
    modifier: Modifier = Modifier,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .pressFeedback(onClick = onClick)
            .background(if (danger) AmberFaint else Color.Transparent)
            .padding(vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = AppText.caption,
            color = if (danger) Crimson else Ink,
        )
    }
}

@Composable
private fun DiagLink(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        style = AppText.label,
        color = InkDim,
        textDecoration = TextDecoration.Underline,
        modifier = Modifier
            .pressFeedback(onClick = onClick)
            .padding(vertical = 6.dp),
    )
}

/** 复制多少行。尾部这么多行足够覆盖一次完整的同步 + 一次崩溃 */
private const val COPY_LINES = 250

/**
 * 复制日志尾部到剪贴板。
 *
 * 与 [shareLog] 是两条不同的路，都必须有：
 * · 导出文件适合「发邮件 / 存网盘」
 * · 复制文本适合「直接粘到聊天窗口里」
 *
 * 只取尾部而不是整份：剪贴板塞进几万行会卡住输入法，
 * 而排查需要的永远是**最近的那一段** —— 崩溃之前发生了什么。
 */
private fun copyLog(context: Context) {
    val lines = DiagLog.readFromDisk(COPY_LINES)
    if (lines.isEmpty()) {
        Toast.makeText(context, "日志是空的", Toast.LENGTH_SHORT).show()
        return
    }
    runCatching {
        val cm = context.getSystemService(ClipboardManager::class.java) ?: return@runCatching
        cm.setPrimaryClip(ClipData.newPlainText("通勤英语 诊断日志", lines.joinToString("\n")))
        Toast.makeText(context, "已复制最近 ${lines.size} 行", Toast.LENGTH_SHORT).show()
    }
}

/**
 * 把日志文件交给系统分享面板。
 *
 * 用 [Intent.ACTION_SEND] 而不是直接写死某个应用：日志可能想发微信、发邮件、
 * 或者存到网盘，那是用户的选择。我们只负责把文件安全地暴露出去。
 *
 * `FLAG_GRANT_READ_URI_PERMISSION` 不能省 —— 少了它接收方拿到的是一个
 * 读不了的 uri，表现为「分享出去是个空文件」，而且不报错。
 */
private fun shareLog(context: Context) {
    val file = DiagLog.currentFile() ?: return
    if (!file.isFile || file.length() == 0L) return

    runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "通勤英语 · 诊断日志")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, "导出日志")
        if (context !is Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }
}

private fun uptimeLabel(): String {
    val total = DiagLog.uptimeSec().toInt()
    val m = total / 60
    val s = total % 60
    return if (m > 0) "${m} 分 ${s} 秒" else "${s} 秒"
}
