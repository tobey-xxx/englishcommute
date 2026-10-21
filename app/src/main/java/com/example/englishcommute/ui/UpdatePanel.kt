package com.example.englishcommute.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.englishcommute.update.UpdateState
import com.example.englishcommute.update.Updater
import com.example.englishcommute.ui.theme.Amber
import com.example.englishcommute.ui.theme.AmberFaint
import com.example.englishcommute.ui.theme.AppText
import com.example.englishcommute.ui.theme.Bg
import com.example.englishcommute.ui.theme.CardChip
import com.example.englishcommute.ui.theme.Hairline
import com.example.englishcommute.ui.theme.Ink
import com.example.englishcommute.ui.theme.InkDim
import com.example.englishcommute.ui.theme.InkFaint
import com.example.englishcommute.ui.theme.Olive
import com.example.englishcommute.ui.theme.Radius
import com.example.englishcommute.ui.theme.Space
import com.example.englishcommute.ui.theme.pressFeedback
import kotlinx.coroutines.launch

/**
 * 更新面板。**设置页与强制更新页共用同一个** —— 两处各写一份的话，
 * 「下载中 / 可安装 / 失败」这三态迟早会有一边漏掉。
 */
@Composable
fun UpdatePanel(
    updater: Updater,
    modifier: Modifier = Modifier,
    /** 强制模式：不提供「稍后再说」，文案也更直接 */
    forced: Boolean = false,
) {
    val state by updater.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var hint by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }

    fun check() {
        checking = true
        scope.launch {
            updater.check()
            checking = false
        }
    }

    fun install() {
        val ready = state as? UpdateState.Ready ?: return
        // `install` 返回 null = 已经唤起系统安装器；否则是给用户看的一句话
        hint = updater.install(ready.file)
    }

    Column(modifier = modifier.fillMaxWidth()) {
        when (val s = state) {
            is UpdateState.Unknown -> {
                Text("v${updater.installedVersionName}（build ${updater.installedVersionCode}）",
                    style = AppText.caption)
                Spacer(Modifier.height(Space.row))
                ActionBar(
                    primary = if (checking) "正在检查…" else "检查更新",
                    primaryEnabled = !checking,
                    onPrimary = ::check,
                )
            }

            is UpdateState.UpToDate -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("已是最新版本", style = AppText.body, color = Olive)
                    Spacer(Modifier.height(0.dp))
                    Spacer(Modifier.weight(1f))
                    LinkAction(if (checking) "检查中…" else "再查一次", !checking, ::check)
                }
                Spacer(Modifier.height(Space.tight))
                Text(
                    "v${updater.installedVersionName}（build ${updater.installedVersionCode}）",
                    style = AppText.caption,
                    color = InkFaint,
                )
            }

            is UpdateState.Available -> {
                Text(
                    "有新版本 v${s.info.versionName}（${s.info.sizeLabel}）",
                    style = AppText.title,
                    color = Amber,
                )
                if (s.info.notes.isNotBlank()) {
                    Spacer(Modifier.height(Space.tight))
                    Text(s.info.notes, style = AppText.body)
                }
                Spacer(Modifier.height(Space.row))
                ActionBar(
                    primary = "下载并安装",
                    primaryEnabled = true,
                    onPrimary = { updater.download(s.info) },
                )
            }

            is UpdateState.Downloading -> {
                Text("正在下载 v${s.info.versionName}", style = AppText.body)
                Spacer(Modifier.height(Space.tight))
                ProgressLine(s.fraction)
                Spacer(Modifier.height(Space.tight))
                Text(
                    "${"%.1f".format(s.doneBytes / 1048576.0)} / ${s.info.sizeLabel}" +
                        "（${(s.fraction * 100).toInt()}%）",
                    style = AppText.caption,
                    color = InkDim,
                )
                Spacer(Modifier.height(Space.tight))
                Text(
                    // ⚠️ 这句必须说清「断了不用重来」—— 跨境链路上一次下完 18 MB
                    //    是小概率事件，而用户会以为要重新开始，于是干脆放弃
                    "卡住或者断了都没关系，再点一次会接着上次继续下。",
                    style = AppText.caption,
                    color = InkFaint,
                )
            }

            is UpdateState.Ready -> {
                Text("新版本已下载好", style = AppText.title, color = Olive)
                Spacer(Modifier.height(Space.tight))
                Text(
                    "v${s.info.versionName}（${s.info.sizeLabel}）· 点下面交给系统安装",
                    style = AppText.caption,
                )
                Spacer(Modifier.height(Space.row))
                ActionBar(primary = "安装", primaryEnabled = true, onPrimary = ::install)
            }

            is UpdateState.Failed -> {
                Text("这次没更新成", style = AppText.title, color = Ink)
                Spacer(Modifier.height(Space.tight))
                Text(s.reason, style = AppText.body)
                Spacer(Modifier.height(Space.row))
                ActionBar(
                    primary = if (checking) "重试中…" else "重试",
                    primaryEnabled = !checking,
                    onPrimary = {
                        // 失败后重试：清单可能已经更新（比如包刚传完），所以先查一次
                        scope.launch {
                            checking = true
                            val info = updater.check() ?: s.info
                            checking = false
                            if (info != null && info.versionCode > updater.installedVersionCode) {
                                updater.download(info)
                            }
                        }
                    },
                )
            }
        }

        // 「安装未知应用」的引导。只在真的被系统挡住时出现 ——
        // 平时不显示，因为绝大多数用户一辈子不需要知道这个开关。
        hint?.let { h ->
            Spacer(Modifier.height(Space.row))
            Text(h, style = AppText.body, color = Amber)
            Spacer(Modifier.height(Space.tight))
            LinkAction("去打开那个开关", true) { updater.openInstallPermissionSettings() }
        }

        if (forced) {
            Spacer(Modifier.height(Space.row))
            Text(
                // 强制页面的**应急出口**：判据来自服务器清单，而服务器可能被我们自己
                // 搞坏（清单写错、包没传完、nginx 挂了）。没有出口的话，用户就完全
                // 没法用 App 了，而唯一的补救是手工重装。
                // 刻意藏起来：它不该被顺手点到，但必须存在。
                "版本过低，无法继续使用。长按这行字可以暂时跳过。",
                style = AppText.caption,
                color = InkFaint,
                modifier = Modifier.pointerInput(Unit) {
                    detectTapGestures(onLongPress = { updater.skipForcedOnce() })
                },
            )
        }
    }
}

/**
 * 强制更新页：盖住一切，不更新不能继续用。
 *
 * ⚠️ 它必须盖在**所有**东西之上（包括打卡庆祝页和播放页）—— 盖不住的话
 * 「不更新不能继续用」就是假的，而用户完全可能靠播放页继续用下去。
 * 实现在 `MainActivity` 里是最外层那个分支，别下沉到某个页签里面。
 */
@Composable
fun ForcedUpdateScreen(updater: Updater) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg)
            .padding(horizontal = Space.pageH),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("通勤英语", style = AppText.display)
            Spacer(Modifier.height(Space.block))
            Text(
                "这个版本已经不能继续用了，需要更新后才能听。",
                style = AppText.body,
                color = InkDim,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(Space.section))
            UpdatePanel(updater = updater, forced = true)
        }
    }
}

// ---------------------------------------------------------------------------
// 零件
// ---------------------------------------------------------------------------

/** 一条 3dp 的进度线。和这个 App 的其余部分一样：只有一条边、没有圆角装饰 */
@Composable
private fun ProgressLine(fraction: Float) {
    val f = fraction.coerceIn(0f, 1f)
    Box(
        Modifier
            .fillMaxWidth()
            .height(3.dp)
            .clip(RoundedCornerShape(Radius.pill))
            .background(CardChip)
    ) {
        // 宽度给 0f 时 Compose 会跳过这一层，不影响布局
        Box(
            Modifier
                .fillMaxWidth(f)
                .height(3.dp)
                .clip(RoundedCornerShape(Radius.pill))
                .background(Amber)
        )
    }
}

/**
 * 一整条主按钮（琥珀淡底 + 纸色字）。
 *
 * ⚠️ 3.3.0 从 `private` 放开成 `internal`：设置页的「词库」块也要用它。
 *    抄一份过去的话，「按钮的按压反馈」和「禁用态怎么灰」就会有两套写法 ——
 *    而这两个恰恰是最容易各写各的、也最容易被看出不一致的地方
 *    （同一个页面里两个按钮按下去手感不一样）。
 */
@Composable
internal fun ActionBar(
    primary: String,
    primaryEnabled: Boolean,
    onPrimary: () -> Unit,
) {
    val text = if (primaryEnabled) Ink else InkFaint
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.pill))
            .background(if (primaryEnabled) AmberFaint else CardChip)
            // pressFeedback 自带 onClick 与按压缩放 —— 别再叠一层手势，
            // 两层会各触发一次（而且缩放的 interactionSource 也拿不到按压状态）
            .pressFeedback(enabled = primaryEnabled, onClick = onPrimary)
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(primary, style = AppText.title, color = text)
    }
}

@Composable
private fun LinkAction(label: String, enabled: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        style = AppText.caption,
        color = if (enabled) Amber else InkFaint,
        modifier = Modifier
            .pressFeedback(enabled = enabled, pressedAlpha = 0.6f, onClick = onClick)
            .padding(vertical = 4.dp),
    )
}
