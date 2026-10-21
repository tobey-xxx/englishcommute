package com.example.englishcommute.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.englishcommute.EnglishCommuteApp
import com.example.englishcommute.account.AccountStore
import com.example.englishcommute.diag.DiagLog
import com.example.englishcommute.net.ApiClient
import com.example.englishcommute.sync.CheckInSync
import com.example.englishcommute.sync.WordBookSync
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * 账号页的状态与动作（4.0.0）。
 *
 * ## 一条贯穿全部文案的规矩
 *
 * 每个失败都必须回答「**我接下来做什么**」。服务端已经按这个口径返回
 * `{error, message}`（等审批 / 密码不对 / 重置码过期 / 换网络），
 * 这里**直接把 message 透给用户**，只在少数几种情况下才自己造一句
 * （那些情况是「服务端不知道的上下文」，比如「注册成功之后该做什么」）。
 *
 * ⚠️ 密码只在这个类里停留一次请求的时间：**不进日志、不落盘、不回显**。
 */
class AccountViewModel(app: Application) : AndroidViewModel(app) {

    private val c = (app as EnglishCommuteApp).container
    private val account: AccountStore = c.account
    private val sync: CheckInSync = c.checkInSync
    private val wordSync: WordBookSync = c.wordBookSync

    /** 界面上正在做哪件事。它决定显示哪些输入框 */
    enum class Flow { LOGIN, REGISTER, FORGOT, RESET, CHANGE_PW }

    data class Ui(
        val signedIn: Boolean = false,
        val name: String? = null,
        val phase: CheckInSync.Phase = CheckInSync.Phase.SIGNED_OUT,
        val pending: Boolean = false,
        val lastSyncAt: Long = 0L,
        val syncMessage: String? = null,
        /** 生词本那一条的状态（两条同步流各自如实显示） */
        val wordPhase: WordBookSync.Phase = WordBookSync.Phase.SIGNED_OUT,
        val wordPending: Boolean = false,
        val wordLastSyncAt: Long = 0L,
        val wordMessage: String? = null,
        val flow: Flow = Flow.LOGIN,
        val busy: Boolean = false,
        /** 成功类提示（注册已提交 / 密码已改…）—— 它们是「下一步」而不是「完成」 */
        val notice: String? = null,
        val error: String? = null,
        /** 忘记密码提交过之后，才显示「我已拿到重置码」那一步 */
        val forgotSent: Boolean = false,
    )

    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                account.name,
                account.signedIn,
                account.lastSyncAt,
                sync.status,
            ) { name, signedIn, lastSync, st ->
                // 登录状态一变就回到默认流程（退出登录后不该停在「修改密码」上）
                name to (signedIn to (lastSync to st))
            }.collect { (name, rest) ->
                val (signedIn, tail) = rest
                val (lastSync, st) = tail
                _ui.update {
                    it.copy(
                        signedIn = signedIn,
                        name = name,
                        lastSyncAt = lastSync,
                        phase = st.phase,
                        pending = st.pending,
                        syncMessage = st.message,
                        flow = if (signedIn && it.flow == Flow.LOGIN) Flow.LOGIN else it.flow,
                    )
                }
            }
        }

        viewModelScope.launch {
            wordSync.status.collect { st ->
                _ui.update {
                    it.copy(
                        wordPhase = st.phase,
                        wordPending = st.pending,
                        wordLastSyncAt = st.lastSyncAt,
                        wordMessage = st.message,
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 表单
    // ------------------------------------------------------------------

    fun setFlow(flow: Flow) = _ui.update {
        it.copy(flow = flow, error = null, notice = null, forgotSent = false)
    }

    fun clearMessages() = _ui.update { it.copy(error = null, notice = null) }

    private fun busy() = _ui.update { it.copy(busy = true, error = null, notice = null) }
    private fun fail(msg: String) = _ui.update { it.copy(busy = false, error = msg) }
    private fun ok(msg: String?, flow: Flow? = null) = _ui.update {
        it.copy(busy = false, error = null, notice = msg, flow = flow ?: it.flow)
    }

    private fun from(res: ApiClient.Res): String? = when (res) {
        is ApiClient.Res.Ok -> null
        is ApiClient.Res.Err -> res.message
    }

    // ------------------------------------------------------------------
    // 动作
    // ------------------------------------------------------------------

    fun login(name: String, password: String) {
        if (name.isBlank() || password.isEmpty()) return fail("账号和密码都要填")
        busy()
        viewModelScope.launch {
            val res = c.api.post("/auth/login", JSONObject().apply {
                put("name", name.trim())
                put("password", password)
                put("device", account.deviceId())
            })
            when (res) {
                is ApiClient.Res.Ok -> {
                    val token = res.json.optString(ApiClient.FIELD_SESSION)
                    val who = res.json.optString("name").ifEmpty { name.trim() }
                    if (token.isEmpty()) return@launch fail("服务器没有返回登录凭证，稍后再试")
                    account.signIn(who, token)
                    ok("已登录 · 正在同步打卡记录…")
                    // ★ 登录后的第一次同步就是「认领本机已有的记录」：
                    //   不做的话，攒了 30 天的连续天数会因为「开始用账号」而清零。
                    sync.syncAfterLogin()
                }
                is ApiClient.Res.Err -> fail(res.message)
            }
        }
    }

    fun register(name: String, password: String) {
        if (name.isBlank() || password.isEmpty()) return fail("账号和密码都要填")
        busy()
        viewModelScope.launch {
            val res = c.api.post("/auth/register", JSONObject().apply {
                put("name", name.trim())
                put("password", password)
            })
            when (res) {
                is ApiClient.Res.Ok -> ok("已提交。管理员通过之后就能登录了。", Flow.LOGIN)
                is ApiClient.Res.Err -> fail(res.message)
            }
        }
    }

    fun sendForgot(name: String) {
        if (name.isBlank()) return fail("先填账号名")
        busy()
        viewModelScope.launch {
            val res = c.api.post("/auth/forgot", JSONObject().apply { put("name", name.trim()) })
            when (res) {
                is ApiClient.Res.Ok -> _ui.update {
                    it.copy(
                        busy = false, error = null, forgotSent = true,
                        notice = "申请已提交。管理员会给你一个 8 位重置码，拿到后填在下面。",
                    )
                }
                is ApiClient.Res.Err -> fail(res.message)
            }
        }
    }

    fun reset(name: String, code: String, newPassword: String) {
        if (name.isBlank() || code.isBlank() || newPassword.isEmpty()) {
            return fail("账号、重置码、新密码都要填")
        }
        busy()
        viewModelScope.launch {
            val res = c.api.post("/auth/reset", JSONObject().apply {
                put("name", name.trim())
                put("code", code.trim())
                put("password", newPassword)
            })
            when (res) {
                is ApiClient.Res.Ok -> ok("密码已重置。为了安全，其它设备都被登出了 —— 用新密码登录即可。", Flow.LOGIN)
                is ApiClient.Res.Err -> fail(res.message)
            }
        }
    }

    fun changePassword(old: String, newPassword: String) {
        if (old.isEmpty() || newPassword.isEmpty()) return fail("两个框都要填")
        busy()
        viewModelScope.launch {
            val token = account.token.first()
                ?: return@launch fail("登录状态已失效，请重新登录")
            val res = c.api.post("/auth/password", JSONObject().apply {
                put("oldPassword", old)
                put("newPassword", newPassword)
            }, token)
            when (res) {
                is ApiClient.Res.Ok -> ok("密码已改。其它设备不受影响。", Flow.LOGIN)
                is ApiClient.Res.Err -> fail(res.message)
            }
        }
    }

    /**
     * 退出登录。
     *
     * ⚠️ 只撤销**当前这台设备**的令牌（服务端 `/auth/logout` 的语义就是如此）。
     *    本地数据**一个字都不删** —— 退出登录不是「清空记录」，
     *    而用户退出之后多半只是想换个账号看看。
     */
    fun logout() {
        busy()
        viewModelScope.launch {
            val token = account.token.first()
            if (!token.isNullOrEmpty()) c.api.post("/auth/logout", null, token)
            account.signOut()
            ok("已退出登录。本机的打卡记录还在，只是不再同步。")
        }
    }

    fun syncNow() {
        viewModelScope.launch {
            DiagLog.act("账号：手动同步")
            // 两条流一起同步：打卡 + 生词本。**先词本后打卡**（词本那条更重）
            wordSync.sync(force = true)
            val okAll = sync.sync(force = true)
            if (okAll) ok("同步完成") else clearMessages()
        }
    }
}
