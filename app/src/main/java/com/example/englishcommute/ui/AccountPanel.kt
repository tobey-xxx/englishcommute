package com.example.englishcommute.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.englishcommute.sync.CheckInSync
import com.example.englishcommute.sync.WordBookSync
import com.example.englishcommute.ui.theme.ActionButton
import com.example.englishcommute.ui.theme.Amber
import com.example.englishcommute.ui.theme.AppText
import com.example.englishcommute.ui.theme.InkDim
import com.example.englishcommute.ui.theme.InkFaint
import com.example.englishcommute.ui.theme.InkMid
import com.example.englishcommute.ui.theme.InputField
import com.example.englishcommute.ui.theme.LinkText
import com.example.englishcommute.ui.theme.Olive
import com.example.englishcommute.ui.theme.Space

/**
 * 设置页的「账号」块（4.0.0）。
 *
 * ## 这一块要回答两个问题
 *
 * 1. **我现在是什么状态**（没登录 / 已登录 + 同步到哪儿了）；
 * 2. **下一步点什么**（登录 / 注册 / 等审批 / 拿重置码 / 立即同步）。
 *
 * ## 文案口径（每一句都按这个写）
 *
 * - 每个失败都要能回答「**我接下来做什么**」。服务端返回的中文文案本来就是
 *   按这个口径写的（等审批 / 密码不对 / 重置码过期 / 换网络），所以这里
 *   **原样透出**，只在服务端不知道的上下文里才自己补一句
 *   （比如「注册成功之后该做什么」）。
 * - 未登录时**不劝登录**。账号是可选增强，这一块第一句就写清现状，
 *   而不是「登录以启用云同步」那种推销腔。
 */
@Composable
fun AccountBlock(vm: AccountViewModel = viewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()

    // 表单里的字全放在这里：切流程时不清空（用户多半只是点错了按钮）
    var name by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }

    Block(
        title = "账号",
        trailing = {
            if (ui.signedIn) {
                Text("已登录", style = AppText.label, color = Olive)
            }
        },
    ) {
        if (ui.signedIn) {
            SignedIn(ui, vm, password, onPassword = { password = it })
        } else {
            SignedOut(
                ui = ui,
                vm = vm,
                name = name, onName = { name = it },
                password = password, onPassword = { password = it },
                code = code, onCode = { code = it },
            )
        }

        // 成功提示（注册已提交 / 密码已改 / 已退出）
        ui.notice?.let {
            Spacer(Modifier.height(Space.row))
            Text(it, style = AppText.caption, color = Olive)
        }
        // 失败提示
        ui.error?.let {
            Spacer(Modifier.height(Space.row))
            Text(it, style = AppText.caption, color = Amber)
        }
    }
}

// ---------------------------------------------------------------------------

@Composable
private fun SignedOut(
    ui: AccountViewModel.Ui,
    vm: AccountViewModel,
    name: String,
    onName: (String) -> Unit,
    password: String,
    onPassword: (String) -> Unit,
    code: String,
    onCode: (String) -> Unit,
) {
    Text(
        text = "登录之后，打卡记录在多台设备之间同步。不登录也能正常用，" +
            "只是进度只在这一台手机上。",
        style = AppText.caption,
        color = InkDim,
    )
    Spacer(Modifier.height(Space.row))

    when (ui.flow) {
        AccountViewModel.Flow.LOGIN -> {
            InputField(name, onName, "账号", imeAction = androidx.compose.ui.text.input.ImeAction.Next)
            Spacer(Modifier.height(8.dp))
            InputField(password, onPassword, "密码", password = true,
                imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                onImeAction = { vm.login(name, password) })
            Spacer(Modifier.height(Space.row))
            ActionButton("登录", enabled = !ui.busy) { vm.login(name, password) }
            Spacer(Modifier.height(Space.row))
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                LinkText("注册新账号") { vm.setFlow(AccountViewModel.Flow.REGISTER) }
                LinkText("忘记密码") { vm.setFlow(AccountViewModel.Flow.FORGOT) }
            }
        }

        AccountViewModel.Flow.REGISTER -> {
            InputField(name, onName, "账号（2~24 位，字母数字或中文）")
            Spacer(Modifier.height(8.dp))
            InputField(password, onPassword, "密码（至少 8 位）", password = true)
            Spacer(Modifier.height(Space.row))
            ActionButton("提交注册", enabled = !ui.busy) { vm.register(name, password) }
            Spacer(Modifier.height(10.dp))
            Text(
                text = "提交后需要管理员通过，通过之前登录会提示「等待通过」。",
                style = AppText.caption,
                color = InkFaint,
            )
            Spacer(Modifier.height(Space.row))
            LinkText("已有账号，去登录") { vm.setFlow(AccountViewModel.Flow.LOGIN) }
        }

        AccountViewModel.Flow.FORGOT, AccountViewModel.Flow.RESET -> {
            InputField(name, onName, "账号")
            if (!ui.forgotSent) {
                Spacer(Modifier.height(Space.row))
                ActionButton("提交重置申请", enabled = !ui.busy) { vm.sendForgot(name) }
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "提交之后，管理员会给你一个 8 位重置码（有效期 30 分钟）。" +
                        "拿到后填在下一步。",
                    style = AppText.caption,
                    color = InkFaint,
                )
            } else {
                Spacer(Modifier.height(Space.row))
                InputField(code, onCode, "8 位重置码", numeric = true, digitStyle = true)
                Spacer(Modifier.height(8.dp))
                InputField(password, onPassword, "新密码（至少 8 位）", password = true)
                Spacer(Modifier.height(Space.row))
                ActionButton("重置密码", enabled = !ui.busy) { vm.reset(name, code, password) }
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "重置成功之后，其它设备都会被登出 —— 那是为了「号被人用了」这种情况。",
                    style = AppText.caption,
                    color = InkFaint,
                )
            }
            Spacer(Modifier.height(Space.row))
            LinkText("返回登录") { vm.setFlow(AccountViewModel.Flow.LOGIN) }
        }

        AccountViewModel.Flow.CHANGE_PW -> {
            // 已登录时才可能进到这里；由 SignedIn 那一支负责渲染
            LinkText("返回") { vm.setFlow(AccountViewModel.Flow.LOGIN) }
        }
    }
}

// ---------------------------------------------------------------------------

@Composable
private fun SignedIn(
    ui: AccountViewModel.Ui,
    vm: AccountViewModel,
    password: String,
    onPassword: (String) -> Unit,
) {
    var oldPw by remember { mutableStateOf("") }

    Text("已登录 · ${ui.name ?: ""}", style = AppText.title, color = InkMid)
    Spacer(Modifier.height(6.dp))
    Text(syncLine(ui), style = AppText.caption, color = if (ui.phase == CheckInSync.Phase.ERROR) Amber else InkFaint)
    Spacer(Modifier.height(4.dp))
    // ★ 4.1.0：两条同步流**各自如实显示**。
    //   合成一句「已同步」会掩盖「打卡同步了、词本没同步」这种真实状态 ——
    //   而用户正是在词本出问题时才会来这里看。
    Text(
        text = wordSyncLine(ui),
        style = AppText.caption,
        color = if (ui.wordPhase == WordBookSync.Phase.ERROR) Amber else InkFaint,
    )

    Spacer(Modifier.height(Space.row))
    if (ui.flow == AccountViewModel.Flow.CHANGE_PW) {
        InputField(oldPw, { oldPw = it }, "当前密码", password = true)
        Spacer(Modifier.height(8.dp))
        InputField(password, onPassword, "新密码（至少 8 位）", password = true)
        Spacer(Modifier.height(Space.row))
        ActionButton("保存新密码", enabled = !ui.busy) {
            vm.changePassword(oldPw, password)
            oldPw = ""
            onPassword("")
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = "改密码不会把其它设备登出 —— 那是个改密码，不是「号被人用了」。",
            style = AppText.caption,
            color = InkFaint,
        )
        Spacer(Modifier.height(Space.row))
        LinkText("取消") { vm.setFlow(AccountViewModel.Flow.LOGIN) }
    } else {
        ActionButton("立即同步", enabled = !ui.busy) { vm.syncNow() }
        Spacer(Modifier.height(Space.row))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LinkText("修改密码") { vm.setFlow(AccountViewModel.Flow.CHANGE_PW) }
            LinkText("退出登录") { vm.logout() }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = "退出登录会撤掉这台设备的登录状态，本机记录不会删。",
            style = AppText.caption,
            color = InkFaint,
        )
    }
}

/**
 * 打卡页上那一行同步状态（只在登录之后出现）。
 *
 * ⚠️ 未登录时**一个字都不显示**：那不是一个「错误状态」，
 * 也不该在打卡页反复提醒用户去登录 —— 账号是可选的。
 */
@Composable
fun SyncLine(vm: AccountViewModel = viewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    if (!ui.signedIn) return
    Spacer(Modifier.height(Space.tight))
    Text(
        text = syncLine(ui),
        style = AppText.caption,
        color = if (ui.phase == CheckInSync.Phase.ERROR) Amber else InkFaint,
        textAlign = TextAlign.Start,
    )
}

private fun wordSyncLine(ui: AccountViewModel.Ui): String = when {
    ui.wordPhase == WordBookSync.Phase.SYNCING -> "词本正在同步…"
    ui.wordPhase == WordBookSync.Phase.ERROR -> ui.wordMessage ?: "词本同步失败，稍后会自动再试"
    ui.wordPending -> "词本有新改动还没上传，联网后会自动同步"
    ui.wordLastSyncAt <= 0L -> "词本还没同步过"
    else -> "词本已同步 · ${relativeTime(ui.wordLastSyncAt)}"
}

/** 状态 → 一句话。判据是「用户接下来做什么」，不是「系统在干什么」。 */
private fun syncLine(ui: AccountViewModel.Ui): String = when {
    ui.phase == CheckInSync.Phase.SYNCING -> "正在同步…"
    ui.phase == CheckInSync.Phase.ERROR -> ui.syncMessage ?: "同步失败，稍后会自动再试"
    ui.pending -> "本机有新记录还没上传，联网后会自动同步"
    ui.lastSyncAt <= 0L -> "还没同步过"
    else -> "已同步 · ${relativeTime(ui.lastSyncAt)}"
}

private fun relativeTime(at: Long): String {
    val min = (System.currentTimeMillis() - at) / 60_000
    return when {
        min < 1 -> "刚刚"
        min < 60 -> "$min 分钟前"
        min < 60 * 24 -> "${min / 60} 小时前"
        else -> "${min / (60 * 24)} 天前"
    }
}

/**
 * 「现在登录了吗」——给那些**文案要跟着变**的地方用（例如清空记录的确认句）。
 *
 * ⚠️ 它读的是**同一个** [AccountViewModel] 实例：`viewModel()` 在同一个
 *    Activity 里按类型返回同一个对象，所以设置页那一块与这里看到的是一致的。
 */
@Composable
fun signedInNow(vm: AccountViewModel = viewModel()): Boolean =
    vm.ui.collectAsStateWithLifecycle().value.signedIn
