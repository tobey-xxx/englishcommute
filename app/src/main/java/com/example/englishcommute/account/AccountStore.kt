package com.example.englishcommute.account

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.englishcommute.diag.DiagLog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

private val Context.accountStore: DataStore<Preferences> by preferencesDataStore(name = "account")

/**
 * 账号：登录状态与凭证。**未登录时一切照旧可用。**
 *
 * ## 账号是可选的（这是设计的第一条原则）
 *
 * 不登录照样能听、能打卡、能复习 —— 账号只多给一件事：**多设备一致**。
 * 理由不是偷懒：服务器是一台 2 核 2G 的机器，
 * 而「听」是这个 App 的全部价值，不该因为服务器抽风 / 证书过期 / 我出差没带电脑而失效。
 * 所以这里所有读取都返回「可以为空」，**没有任何一处会因为没登录而挡住功能**。
 *
 * ## 存什么、不存什么
 *
 * | 存 | 不存 |
 * | --- | --- |
 * | 账号名（界面要显示「已登录 · 名字」） | 密码（**一个字都不存**） |
 * | 令牌 | 令牌的哈希（那是服务端的事） |
 * | 本机设备 id（同步的键） | 任何同步状态（那是 `CheckInSync` 的运行期状态） |
 *
 * ⚠️ **密码永不落盘**：它只在登录那一次请求里出现过，之后再也用不到
 *    （要用到的是令牌）。这条不是洁癖：磁盘上少一份密码，就少一次
 *    「备份 / 换机 / 被翻到」的泄露面。
 *
 * ## 令牌为什么就放在普通 DataStore 里
 *
 * 没引 `EncryptedSharedPreferences`（那个库已被官方标注弃用，且会把依赖链拉长）。
 * 威胁模型是诚实的：**能解开这台手机的人，本来就能直接用这个 App**。
 * 而这个令牌能做的事只有「看/改自己的收听分钟数」—— 泄露的最坏后果
 * 可以在服务端一条命令撤销（`ec-admin revoke`）。
 * 为这个量级的资产引入一个长期无人维护的加密库，不划算。
 */
class AccountStore(context: Context) {

    private val store = context.applicationContext.accountStore

    /** 账号名。未登录 = null */
    val name: Flow<String?> = store.data.map { it[KEY_NAME] }

    /** 令牌。未登录 = null。**只在发请求时读，不往日志里写。** */
    val token: Flow<String?> = store.data.map { it[KEY_TOKEN] }

    /** 界面用：有没有登录 */
    val signedIn: Flow<Boolean> = store.data.map { !it[KEY_TOKEN].isNullOrEmpty() }

    /** 上次成功同步的时间（毫秒）。0 = 从没同步过 */
    val lastSyncAt: Flow<Long> = store.data.map { it[KEY_LAST_SYNC] ?: 0L }

    /**
     * 生词本拉取的水位线：**服务端的单调 seq**，不是时间戳。
     *
     * ⚠️ 用时间戳当水位线会**静默漏掉**「钟慢的设备」发来的事件
     *    （它的 `at` 比水位线小，但服务端其实是后收到的）。
     *    seq 由服务端生成，天然单调、与客户端时钟无关。
     */
    val wordSeq: Flow<Long> = store.data.map { it[KEY_WORD_SEQ] ?: 0L }

    suspend fun markWordsSynced(seq: Long) {
        store.edit { it[KEY_WORD_SEQ] = seq }
    }

    /** 有没有在本地改过「已同步」的水位线。见 [markLocalChange] */
    private val dirty: Flow<Boolean> = store.data.map { it[KEY_DIRTY] ?: false }

    private var cachedDeviceId: String? = null

    /**
     * 本机设备 id：随机 UUID，**装一次生成一次，之后永不改变**。
     *
     * ★ 它是多设备同步的地基：服务端那张表的每一行是
     *   `(用户, 天, 设备) → 秒数`，而「每台设备只写自己那一列」这条不变式
     *   靠的就是这个 id 稳定。**清数据 / 重装会换一个新 id** ——
     *   那台设备的旧计数会以「另一台设备」的身份留在服务端，
     *   结果是总数偏大（偏小才是灾难，见 `CheckInStore` 的说明）。
     */
    suspend fun deviceId(): String {
        cachedDeviceId?.let { return it }
        val existing = store.data.first()[KEY_DEVICE]
        if (!existing.isNullOrEmpty()) {
            cachedDeviceId = existing
            return existing
        }
        val fresh = UUID.randomUUID().toString().replace("-", "").take(16)
        store.edit { it[KEY_DEVICE] = fresh }
        DiagLog.i("Account", "生成设备标识 ${fresh.take(6)}…（同步的键，清数据会换新的）")
        cachedDeviceId = fresh
        return fresh
    }

    /** 登录成功：记下账号与令牌。**不记密码。** */
    suspend fun signIn(name: String, token: String) {
        store.edit {
            it[KEY_NAME] = name
            it[KEY_TOKEN] = token
            it[KEY_DIRTY] = true       // 刚登录 ⇒ 必须同步一次（认领本地记录）
        }
        DiagLog.act("账号：已登录 $name")
    }

    /**
     * 令牌失效（服务端 401）。
     *
     * ⚠️ 刻意**不叫「登出」**：它描述的是一个事实（这个令牌没用了），
     * 而不是一个动作。界面上要提醒用户重新登录，语气不该像「你自己退出的」。
     */
    suspend fun tokenRejected() {
        store.edit {
            it.remove(KEY_TOKEN)
            it[KEY_DIRTY] = true
        }
        DiagLog.w("Account", "令牌已失效（服务端 401），需要重新登录")
    }

    /** 主动退出：清掉本机凭证。**服务端那一份也顺手撤销**（见调用方） */
    suspend fun signOut() {
        store.edit {
            it.remove(KEY_TOKEN)
            it.remove(KEY_NAME)
        }
        DiagLog.act("账号：已退出登录")
    }

    suspend fun markSynced() {
        store.edit {
            it[KEY_LAST_SYNC] = System.currentTimeMillis()
            it[KEY_DIRTY] = false
        }
    }

    /** 本机有新的打卡增量 ⇒ 需要同步（`CheckInSync` 据此判断要不要跑） */
    suspend fun markLocalChange() {
        store.edit { it[KEY_DIRTY] = true }
    }

    suspend fun needsSync(): Boolean = store.data.first()[KEY_DIRTY] ?: false

    /**
     * 已经缓存好的设备标识（**同步读取**）。
     *
     * ⚠️ 存在的唯一理由是 WordStore 的 `deviceId` 参数是个普通 lambda
     *    （它要给复习记录署名，而那些调用点不是挂起函数）。
     *    冷启动时为空的后果**没有危害**：那些记录会被标成「老数据」，
     *    第一次同步时由 `syncPayload` 补上标识。
     */
    fun deviceIdOrEmpty(): String = cachedDeviceId.orEmpty()

    companion object {
        private val KEY_NAME = stringPreferencesKey("account_name")
        private val KEY_TOKEN = stringPreferencesKey("account_token")
        private val KEY_DEVICE = stringPreferencesKey("account_device")
        private val KEY_LAST_SYNC = longPreferencesKey("account_last_sync")
        private val KEY_WORD_SEQ = longPreferencesKey("account_word_seq")
        private val KEY_DIRTY = booleanPreferencesKey("account_dirty")
    }
}
