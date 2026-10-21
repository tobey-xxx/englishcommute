package com.example.englishcommute.word

import com.example.englishcommute.diag.DiagLog
import com.example.englishcommute.net.ServerConfig
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/** 一次词表**整包**同步的结果。三种情况界面上的处理完全不同，所以不用 Boolean */
sealed interface VocabTableResult {
    /** 版本一致，什么都没改 */
    data class UpToDate(val version: String) : VocabTableResult

    /** 下好并通过校验、已经切换过去了。调用方要**让词表缓存失效** */
    data class Updated(val version: String, val words: Int) : VocabTableResult

    /**
     * 没拉到（断网 / 服务器 404 / 校验不过）。
     *
     * ⚠️ **什么都不改**，手里那份继续用 ——「查不到」不等于「已是最新」。
     * 与 `Updater` / `VocabAddSync` 是同一条纪律。
     */
    data class Unreachable(val reason: String) : VocabTableResult
}

/**
 * 词表**整包**：让「更新词库」这件事不必等下一次 App 版本。
 *
 * ## 它和 `VocabAddSync` 的分工
 *
 * | | 谁在用 | 多大 | 频率 |
 * |---|---|---|---|
 * | `assets/vocab/` 下那三个 | 内置，**只跟 App 版本走** | 2.2 MB | 发版才动 |
 * | [VocabAddSync] 的 `add.json` | 同步收尾自动拉 | 十几 KB | 每轮（30 分钟） |
 * | **本类** | 用户在设置页点「更新词库」 | 约 2.4 MB | 用户想更新时 |
 *
 * 三层不是重复，因为它们回答的是三个不同的问题：
 * 内置那份是「没网也能用」，增量是「新词跟上得够快」，
 * 而整包是「**我想现在就拿到最新的词库，不想等发版**」——
 * 这在 3.3.0 之前是做不到的（那正是用户提的第三条需求）。
 *
 * ## 落盘布局
 *
 * ```
 * filesDir/words/
 *     vocab.add.json              增量（VocabAddSync）
 *     table.active.json           **当前生效**的那份清单（本类）
 *     table/<version>/            整包，三个文件
 *         vocab.base  vocab.forms  examples.base
 *     table/<version>.staging/    下载中的临时目录（下完整体改名）
 * ```
 *
 * ## 三条判据都落在**文件**上，不落在记录上
 *
 * 1. **「装好了没有」看三个文件在不在、长不为零**，不看 `table.active.json`。
 *    ⚠️ 记录和文件是会被拆开的：下载完写清单、然后用户清了数据目录、
 *    或者写到一个一半被杀。只要清单在就说「装好了」，会让词表加载到一个
 *    不存在的目录上 —— 而症状是「查不到词」，看起来像词表内容的问题。
 * 2. **「下完了没有」看 sha256**，不看长度、也不看 HTTP 200。
 *    2.4 MB 的跨境下载被截断是常态。
 * 3. **切换靠 `rename` 目录**（同一文件系统内是原子的）：先全部下进
 *    `.staging/`，校验通过再整体改名。于是「版本目录存在」⟺「三个文件都在」，
 *    不存在「目录在、文件缺」的中间态。
 *
 * ## 版本目录名就是内容哈希，所以旧目录可以放心删
 *
 * 服务端把内容哈希编进了路径（`table/<version>/…`），所以同一个版本号
 * 永远对应同一份内容。于是：**同一个版本重复下载可以直接跳过**（目录已在就是它），
 * 而**切换版本不需要覆盖任何文件** —— 这也是「回退」天然可用的原因。
 */
class VocabTableSync(
    private val dir: File,
    private val client: OkHttpClient = defaultClient(),
) {

    private val tableRoot = File(dir, "table")
    private val activeManifest = File(dir, "table.active.json")

    /** 串行化：用户连点两次「更新词库」不该并发下两遍 */
    private val lock = Mutex()

    // ------------------------------------------------------------------
    // 本地状态
    // ------------------------------------------------------------------

    /** 当前生效的版本号。`null` = 还在用内置词表（首装 / 从没更新过） */
    fun localVersion(): String? = readActive()?.optString("version")?.takeIf { it.isNotEmpty() }

    /** 当前生效那份的词条数。给设置页显示「词表 N 词」 */
    fun localWords(): Int = readActive()?.optInt("baseCount") ?: 0

    /**
     * **当前生效**的词表目录；没有可用的整包时返回 `null`（调用方退回 assets）。
     *
     * ⚠️ 判据是**三个文件都在**，不是「清单说装好了」—— 理由见类注释第 1 条。
     */
    fun tableDir(): File? {
        val v = localVersion() ?: return null
        val d = File(tableRoot, v)
        val ok = TABLE_FILES.all { f -> File(d, f).let { it.isFile && it.length() > 0L } }
        if (!ok) {
            DiagLog.w("Word", "词表包 $v 的清单在、文件不全 —— 本次退回内置词表")
            return null
        }
        return d
    }

    // ------------------------------------------------------------------
    // 同步
    // ------------------------------------------------------------------

    /**
     * 去服务器对一次版本；不一样就把整包下下来。
     *
     * @param onProgress `(已下载字节, 总字节)`，用于界面上的进度。总字节来自清单，
     *   所以是「准的」而不是估算 —— 2.4 MB 的等待里，一个准的进度条
     *   和「转圈」是两种体验
     */
    suspend fun sync(onProgress: (Long, Long) -> Unit = { _, _ -> }): VocabTableResult =
        lock.withLock {
            withContext(Dispatchers.IO) {
                runCatching { doSync(onProgress) }.getOrElse { e ->
                    val msg = if (e is IOException) "网络不通（${e.message?.take(40)}）"
                    else e.message?.take(60) ?: e.javaClass.simpleName
                    DiagLog.w("Word", "词表包更新失败（手里那份保持不变）：$msg")
                    VocabTableResult.Unreachable(msg)
                }
            }
        }.also { r ->
            when (r) {
                is VocabTableResult.Updated ->
                    DiagLog.act("词表已更新：${r.words} 词（版本 ${r.version}）")
                is VocabTableResult.UpToDate ->
                    DiagLog.i("Word", "词表已是最新（版本 ${r.version}）")
                else -> Unit
            }
        }

    private fun doSync(onProgress: (Long, Long) -> Unit): VocabTableResult {
        val manifest = fetchManifest()
        val version = manifest.optString("version")
        if (version.isEmpty()) return VocabTableResult.Unreachable("清单里没有版本号")

        // 服务器上那份就是我们手里这份 —— 不必下 2.4 MB
        if (version == localVersion() && tableDir() != null) {
            return VocabTableResult.UpToDate(version)
        }

        val files = manifest.optJSONArray("files")
            ?: return VocabTableResult.Unreachable("清单里没有文件列表")
        if (files.length() < TABLE_FILES.size) {
            // 少文件就整份不下 —— 下一份**不完整**的词表比不更新更糟：
            // 词表加载器会用它覆盖内置表，然后大面积查不到词
            return VocabTableResult.Unreachable("清单里的文件不全")
        }

        val total = manifest.optLong("totalBytes").takeIf { it > 0L } ?: 0L
        val target = File(tableRoot, version)
        val staging = File(tableRoot, "$version.staging")

        // 目标版本已经完整躺在盘上（上次下完但没切换、或版本回退）⇒ 直接切
        val alreadyComplete = TABLE_FILES.all {
            File(target, it).let { f -> f.isFile && f.length() > 0L }
        }
        if (!alreadyComplete) {
            staging.deleteRecursively()
            if (!staging.mkdirs()) {
                return VocabTableResult.Unreachable("建不了目录 ${staging.name}")
            }
            var done = 0L
            try {
                for (i in 0 until TABLE_FILES.size) {
                    val meta = files.optJSONObject(i) ?: continue
                    val name = meta.optString("name")
                    if (name !in TABLE_FILES) {
                        return VocabTableResult.Unreachable("清单里出现了预期外的文件：$name")
                    }
                    val expectSha = meta.optString("sha256")
                    val expectSize = meta.optLong("sizeBytes")
                    val dest = File(staging, name)
                    download(ServerConfig.abs(meta.optString("path")), dest)
                    // ★ 校验必须在改名之前。截断的文件在**用起来**时才暴露，
                    //   而那时症状是「查不到词」—— 排查方向会跑到词表内容上去
                    val actual = sha256(dest)
                    if (actual != expectSha) {
                        return VocabTableResult.Unreachable("$name 校验不通过（下载不完整）")
                    }
                    if (expectSize > 0L && dest.length() != expectSize) {
                        return VocabTableResult.Unreachable("$name 大小对不上")
                    }
                    done += dest.length()
                    onProgress(done, total)
                }
                target.deleteRecursively()
                if (!staging.renameTo(target)) {
                    // 极少数情况下 rename 会失败（目标已存在、跨挂载点）。
                    // 退回「复制 + 删」，但**先复制完再删**，顺序不能反
                    staging.copyRecursively(target, overwrite = true)
                    staging.deleteRecursively()
                }
            } catch (e: Exception) {
                staging.deleteRecursively()
                throw e
            }
        }

        writeActive(manifest)
        pruneExcept(version)
        return VocabTableResult.Updated(version, manifest.optInt("baseCount"))
    }

    private fun fetchManifest(): JSONObject {
        val text = client.newCall(
            Request.Builder()
                .url(ServerConfig.abs(MANIFEST_PATH))
                // nginx 上这条已经是 no-store，这里再压一道：
                // 清单被缓存住的话，用户会一直看到旧版本号，
                // 表现是「我更新过词库了，它还说没有新版」—— 而且不会自愈
                .header("Cache-Control", "no-cache")
                .build()
        ).execute().use { r ->
            if (r.code == 404) throw IOException("服务器上还没有这个词库包")
            if (!r.isSuccessful) throw IOException("服务器返回 ${r.code}")
            r.body?.string().orEmpty().ifEmpty { throw IOException("响应内容为空") }
        }
        return JSONObject(text)
    }

    private fun download(url: String, dest: File) {
        client.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            val body = r.body ?: throw IOException("响应为空")
            dest.outputStream().use { out -> body.byteStream().copyTo(out) }
        }
    }

    /**
     * 把当前版本写成「生效中」。
     *
     * tmp + rename：半截 JSON 会让下次启动读不到清单 —— 而那时文件其实都在，
     * 于是用户会**白白退回内置词表**（词变少），却看不出为什么。
     */
    private fun writeActive(manifest: JSONObject) {
        dir.mkdirs()
        val tmp = File(dir, "table.active.json.tmp")
        tmp.writeText(manifest.toString(), Charsets.UTF_8)
        if (!tmp.renameTo(activeManifest)) {
            tmp.copyTo(activeManifest, overwrite = true)
            tmp.delete()
        }
    }

    private fun readActive(): JSONObject? {
        if (!activeManifest.isFile || activeManifest.length() <= 0L) return null
        return try {
            JSONObject(activeManifest.readText(Charsets.UTF_8))
        } catch (e: Exception) {
            DiagLog.w("Word", "词表清单读不出来（${e.javaClass.simpleName}），退回内置词表")
            null
        }
    }

    /** 只留下正在用的那一版 + 上一版（回退用）。见类注释最后一段 */
    private fun pruneExcept(version: String) {
        runCatching {
            val all = tableRoot.listFiles()?.filter { it.isDirectory } ?: return@runCatching
            all.filter { it.name != version }
                .sortedByDescending { it.lastModified() }
                .drop(1)                       // 留最新那个旧的
                .forEach { it.deleteRecursively() }
        }
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        /** 清单路径（与 nginx 上的 `location = /vocab/table.json` 一一对应） */
        const val MANIFEST_PATH = "vocab/table.json"

        /** 整包的三个文件。**顺序与服务端 `publish_vocab.py` 的 FILES 一致** */
        val TABLE_FILES = listOf("vocab.base", "vocab.forms", "examples.base")

        /**
         * 2.4 MB 在跨境链路上要几十秒。读超时给 60 秒 ——
         * 默认 10 秒会让「慢但没断」的连接被误判成失败，而重来一次还是同样慢。
         */
        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}
