package com.example.englishcommute.word

import com.example.englishcommute.diag.DiagLog
import com.example.englishcommute.net.ServerConfig
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/** 一次增量同步的结果。**三种情况界面上的处理完全不同**，所以不能用 Boolean */
sealed interface VocabAddResult {
    /**
     * 版本一致，什么都没改。
     *
     * 带着 version 是为了日志 —— 「已是最新（版本 f5e8c94e08）」能让人
     * 一眼看出「本地和服务端说的是同一个版本」，而不是只知道「没动」。
     */
    data class UpToDate(val version: String) : VocabAddResult

    /** 拉到新版本并落盘了。调用方要**让词表缓存失效** */
    data class Updated(val version: String, val newWords: Int) : VocabAddResult

    /**
     * 没拉到（断网 / 服务器 404 / 内容不合法）。
     *
     * ⚠️ **什么都不改**。这和 [UpToDate] 是两件完全不同的事 ——
     * 把「查不到」写成「已是最新」是在撒谎，而且会让用户在断网时
     * 以为自己手里的词表是新的。（与 `Updater` 里那条纪律同源。）
     */
    data class Unreachable(val reason: String) : VocabAddResult
}

/**
 * 词表**增量**：服务端下发的「内置表里还没有的词」。
 *
 * ## 它存在的理由
 *
 * 词表是发版时按当时语料构建的（11795 个原形，2.2 MB 打进 assets）。
 * 而每天的新素材都会带来新词 —— 人名、地名、机构名、新事件。
 * 实测：前 33 期建表，**后 33 期有 43.8% 是表外的**。
 *
 * 所以「这个词暂未收录」是常态。没有增量机制时，它的**唯一**出路是
 * 等下一次发版 —— 而为了几百个新词让用户重装 18 MB 的包，显然不是办法。
 *
 * ## 增量有多大
 *
 * 实测（2026-09-23）：**141 个新词 + 114 个新变形 = 13.3 KB**（一个 JSON）。
 * 对比 APK 里那份 2.2 MB —— 相差 170 倍。
 *
 * ## 版本判据来自**内容哈希**，不是时间戳
 *
 * 服务端每次重建都会产生新的时间戳，但**内容不变时哈希不变** ——
 * 于是客户端不会为了「一个字节都没变」的包反复下载。
 *
 * ## ⚠️ 为什么存**原文**而不是解析后的行
 *
 * 存原文（那个 JSON）之后，「版本」和「内容」永远是同一份文件的同一时刻，
 * 不存在「版本已更新但内容没写全」的中间态。解析放在加载词表时做一次 ——
 * 13 KB 的 JSON 解析是毫秒级的。
 */
class VocabAddSync(
    private val dir: File,
    private val client: OkHttpClient = defaultClient(),
) {

    private val file = File(dir, "vocab.add.json")

    /** 串行化：同步可能被「用户手动点更新」和「定时同步」同时触发 */
    private val lock = Mutex()

    /** 本地这份增量的版本。`null` = 还没有（首装 / 从没同步成功过） */
    fun localVersion(): String? =
        readLocal()?.optString("version")?.takeIf { it.isNotEmpty() }

    /**
     * 本地的增量内容：`(base 行, forms 行)`。
     *
     * ⚠️ **任何异常都返回两个空表**，绝不让词表加载失败 ——
     * 增量是「锦上添花」，它坏了不该把主表一起拖下水。
     * 但也不静默：坏掉时会记一行日志（用户看到的是「有些词查不到」，
     * 而日志里能看出是增量文件的问题）。
     */
    fun localLines(): Pair<List<String>, List<String>> {
        val obj = readLocal() ?: return emptyList<String>() to emptyList()
        return try {
            obj.optJSONArray("base").toStringList() to obj.optJSONArray("forms").toStringList()
        } catch (e: Exception) {
            DiagLog.w("Word", "增量词表解析失败，本次按空处理：${e.javaClass.simpleName}")
            emptyList<String>() to emptyList()
        }
    }

    /**
     * 去服务端拉一次增量。**版本一致时不写盘、不返回 Updated**。
     */
    suspend fun sync(): VocabAddResult = lock.withLock {
        val url = ServerConfig.abs("vocab/add.json")
        withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder().url(url).build()
                client.newCall(req).execute().use { resp ->
                    if (resp.code == 404) {
                        // 服务端还没生成过增量（比如所有词都已发布）。
                        // ⚠️ 这不是错误，**也不该清掉本地那份** ——
                        // 本地那份可能正是有用的。
                        return@withContext VocabAddResult.Unreachable("服务器还没有增量包")
                    }
                    if (!resp.isSuccessful) {
                        return@withContext VocabAddResult.Unreachable("服务器返回 ${resp.code}")
                    }
                    val text = resp.body?.string().orEmpty()
                    if (text.isEmpty()) {
                        return@withContext VocabAddResult.Unreachable("响应内容为空")
                    }

                    val obj = JSONObject(text)
                    val version = obj.optString("version")
                    if (version.isEmpty()) {
                        return@withContext VocabAddResult.Unreachable("清单里没有版本号")
                    }
                    if (version == localVersion()) {
                        return@withContext VocabAddResult.UpToDate(version)
                    }

                    // tmp + rename：半截 JSON 会让下次解析失败。
                    //
                    // ⚠️ 先确保目录在 —— 首装时 `words/` 还不存在。
                    //
                    // ⚠️⚠️ 这句注释原来写的是「它由 WordStore 在加第一个词时创建」——
                    //    **那是错的**（3.0.3 发现）：`WordStore.persist()` 自己也不建目录。
                    //    于是往 `words/` 下写的三个类互相以为别人会建，实际只有**这一个**建了。
                    //    漏掉的那个（`PronDownloader` 的 `audio/` 子目录）就是
                    //    「复习里读音永远下不来」的真因。
                    //
                    //    结论：**「别人应该已经建过了」这种假设不该存在** ——
                    //    每个写自己文件的人都 `mkdirs()`，幂等且几乎免费。
                    dir.mkdirs()
                    val tmp = File(dir, "vocab.add.json.tmp")
                    tmp.writeText(text, Charsets.UTF_8)
                    if (!tmp.renameTo(file)) {
                        tmp.copyTo(file, overwrite = true)
                        tmp.delete()
                    }
                    VocabAddResult.Updated(version, obj.optInt("baseCount"))
                }
            }.getOrElse { e ->
                val msg = if (e is IOException) "网络不通" else e.javaClass.simpleName
                DiagLog.w("Word", "增量词表拉取失败（本地那份保持不变）：$msg")
                VocabAddResult.Unreachable(msg)
            }
        }.also { r ->
            when (r) {
                is VocabAddResult.Updated ->
                    DiagLog.act("词表增量已更新：+${r.newWords} 个新词（版本 ${r.version}）")
                // ⚠️ 这里必须是 `is` —— `UpToDate` 带了一个 version 字段，
                //    所以它是 data class 而不是 object；而 `when` 里对 data class
                //    写裸类名会被当「表达式」用（编译报
                //    "Classifier ... does not have a companion object"）。
                is VocabAddResult.UpToDate ->
                    DiagLog.i("Word", "词表增量已是最新（版本 ${r.version}）")
                else -> Unit
            }
        }
    }

    /** 读本地原文。读不出来或不是合法 JSON 时返回 `null`（不抛） */
    private fun readLocal(): JSONObject? {
        if (!file.exists() || file.length() <= 0L) return null
        return try {
            JSONObject(file.readText(Charsets.UTF_8))
        } catch (e: Exception) {
            DiagLog.w("Word", "增量词表文件读不出来（${e.javaClass.simpleName}），按空处理")
            null
        }
    }

    private fun org.json.JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        val out = ArrayList<String>(length())
        for (i in 0 until length()) {
            val s = optString(i)
            if (s.isNotEmpty()) out += s
        }
        return out
    }

    companion object {
        /** 增量包很小（十几 KB），超时给 15 秒足够 */
        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}
