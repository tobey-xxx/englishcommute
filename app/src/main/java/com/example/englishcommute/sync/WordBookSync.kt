package com.example.englishcommute.sync

import com.example.englishcommute.account.AccountStore
import com.example.englishcommute.diag.DiagLog
import com.example.englishcommute.net.ApiClient
import com.example.englishcommute.word.FsrsCard
import com.example.englishcommute.word.ReviewLog
import com.example.englishcommute.word.WordCard
import com.example.englishcommute.word.WordContext
import com.example.englishcommute.word.WordStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 生词本的云端同步（4.1.0）。
 *
 * ## 和打卡同步的那一套**刻意不一样**，理由写在 [WordStore.mergeRemote] 里
 *
 * | | 打卡 | 生词本 |
 * | --- | --- | --- |
 * | 数据性质 | **只增的计数**（今天听了多少秒） | **可变状态 + 可删除** |
 * | 合并 | 各设备一列，服务端**求和** | 复习事件取**并集**，状态由客户端**重放** |
 * | 状态存在哪 | 服务端的表里 | **只存在于客户端**（它是事件的投影） |
 *
 * 共同点只有两条，而这两条都值得抄：**服务端只做幂等的「收」和全量的「给」**，
 * 以及**客户端只看事实（文件/事件），不看「谁赢」**。
 *
 * ## 一次往返
 *
 * ```
 * 上行：{ device, sinceSeq, cards[], deleted{}, reviews[]（只发本机产生的） }
 * 下行：{ cards[], deleted{}, reviews[]（seq > sinceSeq）, seq }
 * ```
 *
 * ⚠️ 上行不下发 FSRS 状态（服务端也不存）；下行带回来的卡片**不带复习记录**，
 *    复习统一在 `reviews` 里按 `lemma` 分组 —— 这样「一条复习属于哪个词」
 *    只有一个来源，不会出现两处各写一遍。
 */
class WordBookSync(
    private val words: WordStore,
    private val account: AccountStore,
    private val api: ApiClient,
    private val scope: CoroutineScope,
) {

    enum class Phase { SIGNED_OUT, IDLE, SYNCING, ERROR }

    data class Status(
        val phase: Phase = Phase.SIGNED_OUT,
        val pending: Boolean = false,
        val lastSyncAt: Long = 0L,
        val cards: Int = 0,
        val message: String? = null,
    )

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    private val running = AtomicBoolean(false)
    private var lastAttemptAt = 0L
    private val minIntervalMs = 2 * 60_000L

    init {
        // 本机一改生词本就记脏、并试着传一次。
        // ⚠️ 订阅的是 `localChanges`（只由本机写操作发出），**不是** `cards` ——
        //    后者在合并远端结果时也会变，会变成「同步完再同步」的循环。
        scope.launch {
            words.localChanges.collect {
                account.markLocalChange()
                refresh()
                sync()
            }
        }
    }

    /** 本机刚改了生词本（加词 / 复习 / 删除） */
    fun onLocalChange() {
        scope.launch {
            account.markLocalChange()
            refresh()
        }
        maybeSync()
    }

    fun maybeSync(force: Boolean = false) {
        scope.launch { sync(force = force) }
    }

    fun syncAfterLogin() {
        scope.launch {
            account.markLocalChange()
            sync(force = true)
        }
    }

    suspend fun refresh(message: String? = null) {
        val signedIn = account.signedIn.first()
        _status.value = Status(
            phase = when {
                !signedIn -> Phase.SIGNED_OUT
                message != null -> Phase.ERROR
                else -> Phase.IDLE
            },
            pending = account.needsSync(),
            lastSyncAt = account.lastSyncAt.first(),
            cards = words.cards.value.size,
            message = message,
        )
    }

    suspend fun sync(force: Boolean = false): Boolean {
        if (!account.signedIn.first()) {
            refresh()
            return false
        }
        val now = System.currentTimeMillis()
        if (!force && now - lastAttemptAt < minIntervalMs) return false
        if (!running.compareAndSet(false, true)) return false
        lastAttemptAt = now

        val token = account.token.first()
        if (token.isNullOrEmpty()) {
            running.set(false)
            return false
        }

        _status.value = _status.value.copy(phase = Phase.SYNCING, message = null)
        try {
            val device = account.deviceId()
            val payload = words.syncPayload(device)

            val body = JSONObject().apply {
                put("device", device)
                put("sinceSeq", account.wordSeq.first())
                put("cards", JSONArray().apply {
                    payload.cards.forEach { c ->
                        put(JSONObject().apply {
                            put("lemma", c.lemma)
                            put("surface", c.surface)
                            put("addedAt", c.addedAt)
                            put("deletedAt", 0)          // 活着的卡才在上行列表里
                            put("contexts", JSONArray().apply {
                                c.contexts.forEach { ctx ->
                                    put(JSONObject().apply {
                                        put("episodeId", ctx.episodeId)
                                        put("episodeTitle", ctx.episodeTitle)
                                        put("lineStartMs", ctx.lineStartMs)
                                        put("lineEndMs", ctx.lineEndMs)
                                        put("sentence", ctx.sentence)
                                        put("translation", ctx.translation ?: JSONObject.NULL)
                                    })
                                }
                            })
                        })
                    }
                })
                put("deleted", JSONObject().apply {
                    payload.deleted.forEach { (lemma, at) -> put(lemma, at) }
                })
                put("reviews", JSONArray().apply {
                    payload.reviews.forEach { (lemma, list) ->
                        list.forEach { r ->
                            put(JSONObject().apply {
                                put("lemma", lemma)
                                put("at", r.at)
                                put("rating", r.rating)
                                put("device", r.device)
                            })
                        }
                    }
                })
            }

            return when (val res = api.post("/sync/words", body, token)) {
                is ApiClient.Res.Ok -> {
                    val remoteCards = readCards(res.json.optJSONArray("cards"))
                    val remoteDeleted = readLongMap(res.json.optJSONObject("deleted"))
                    val remoteReviews = readReviews(res.json.optJSONArray("reviews"))
                    val changed = words.mergeRemote(remoteCards, remoteDeleted, remoteReviews)
                    account.markWordsSynced(res.json.optLong("seq", 0L))
                    DiagLog.i(
                        "WordSync",
                        "词本同步完成 · 上行 ${payload.cards.size} 张卡 / " +
                            "${payload.reviews.values.sumOf { it.size }} 条复习 · " +
                            "下行 ${remoteCards.size} 张 / ${remoteReviews.values.sumOf { it.size }} 条复习 · " +
                            "改动 $changed 处",
                    )
                    refresh()
                    true
                }
                is ApiClient.Res.Err -> {
                    if (res.code == "unauthorized") {
                        account.tokenRejected()
                        refresh(message = "登录已失效，请重新登录")
                    } else {
                        DiagLog.w("WordSync", "词本同步失败：${res.code}")
                        refresh(message = res.message)
                    }
                    false
                }
            }
        } catch (e: Exception) {
            DiagLog.w("WordSync", "词本同步异常：${e.javaClass.simpleName}")
            refresh(message = "同步失败，稍后会自动再试")
            return false
        } finally {
            running.set(false)
        }
    }

    // ------------------------------------------------------------------

    /**
     * 服务端下来的卡片**不带复习记录**（那些在 `reviews` 里统一给）。
     *
     * 于是这里要造一个「新卡」的 FSRS 占位状态（`dueAtMillis = addedAt`，
     * 与 `WordStore.add` 造新卡时完全一致）—— 如果这个词有复习记录，
     * `mergeRemote` 的重放会立刻把它算准。
     */
    private fun readCards(arr: JSONArray?): List<WordCard> {
        if (arr == null) return emptyList()
        val out = ArrayList<WordCard>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val lemma = o.optString("lemma")
            if (lemma.isEmpty()) continue
            val addedAt = o.optLong("addedAt")
            val ctxArr = o.optJSONArray("contexts") ?: JSONArray()
            val contexts = (0 until ctxArr.length()).mapNotNull { k ->
                val c = ctxArr.optJSONObject(k) ?: return@mapNotNull null
                WordContext(
                    episodeId = c.optString("episodeId"),
                    episodeTitle = c.optString("episodeTitle"),
                    lineStartMs = c.optLong("lineStartMs"),
                    lineEndMs = c.optLong("lineEndMs"),
                    sentence = c.optString("sentence"),
                    translation = if (c.isNull("translation")) null else c.optString("translation"),
                )
            }
            out.add(
                WordCard(
                    lemma = lemma,
                    surface = o.optString("surface").ifEmpty { lemma },
                    addedAt = addedAt,
                    contexts = contexts,
                    fsrs = FsrsCard(dueAtMillis = addedAt),
                    reps = 0,
                    lapses = 0,
                    reviews = emptyList(),
                ),
            )
        }
        return out
    }

    private fun readReviews(arr: JSONArray?): Map<String, List<ReviewLog>> {
        if (arr == null) return emptyMap()
        val out = HashMap<String, MutableList<ReviewLog>>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val lemma = o.optString("lemma")
            if (lemma.isEmpty()) continue
            out.getOrPut(lemma) { ArrayList() }.add(
                ReviewLog(
                    at = o.optLong("at"),
                    rating = o.optInt("rating"),
                    device = o.optString("device"),
                ),
            )
        }
        return out
    }

    private fun readLongMap(o: JSONObject?): Map<String, Long> {
        if (o == null) return emptyMap()
        val out = HashMap<String, Long>(o.length())
        val keys = o.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val v = o.optLong(k, 0L)
            if (v > 0) out[k] = v
        }
        return out
    }
}
