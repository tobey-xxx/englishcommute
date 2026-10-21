package com.example.englishcommute.content

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

/** 音频下载进度 */
data class AudioDownloadProgress(
    val episodeId: String,
    val bytesDone: Long,
    val bytesTotal: Long,
    val bytesPerSecond: Long,
) {
    val fraction: Float
        get() = if (bytesTotal > 0) {
            (bytesDone.toDouble() / bytesTotal).toFloat().coerceIn(0f, 1f)
        } else 0f
}

/** 本地文件占用明细，供设置页展示 */
data class StorageBreakdown(
    val audioBytes: Long,
    val audioCount: Int,
    val srtBytes: Long,
    val srtCount: Int,
    val zhBytes: Long,
    val zhCount: Int,
)

/**
 * 音频、字幕与译文的文件仓库。
 *
 * 全部放 App 私有外部目录（`getExternalFilesDir`），**免存储权限**，
 * 卸载 App 时系统会一并清理，不会在用户手机里留垃圾。
 *
 * ```
 * .../files/episodes/audio/<episodeId>.mp3
 * .../files/episodes/srt/<episodeId>.srt
 * .../files/episodes/zh/<episodeId>.zh.json
 * ```
 *
 * ## 译文为什么单独存一份 JSON，而不是写回 SRT
 *
 * 「双语 SRT」看着更整洁，但译文是**可以缺失、可以后来补上**的：
 * 断网时没翻成、用户中途取消、某一句模型吐了空。把它混进 SRT 里，
 * 「有字幕没译文」和「字幕本身就坏了」就分不开了。
 * 分开存之后，两边的生命周期各自独立，删一个不影响另一个。
 *
 * 数组下标 = SRT 句段下标，一一对应；未翻译的位置写 null。
 *
 * ## 2.1.0：这份文件现在是**服务器**给的
 *
 * 它以前由端侧翻译逐句增量写入（每 20 句落一次盘，被打断也能接着跑）。
 * 现在服务器在发布字幕的同时把中文一起发出来，客户端只做「取回来、校验句数、
 * 原子落盘」（见 `ServerCatalog.fetchSubtitles`）—— 所以**句数校验比以前更关键**：
 * 端侧逐句写不可能错位，而跨机器传过来的数组只有长度对得上才敢用
 * （错位的译文比没有译文更糟，它会张冠李戴）。
 */
class AudioStore(context: Context) {

    private val appContext = context.applicationContext

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        // 单集最长 35 分钟音频，不能设总超时
        .callTimeout(0, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        // 实测三个源的 enclosure 全是跳转链：
        //   NPR → prfx.byspotify.com
        //   TED → dts.podtrac.com
        // 必须跟随重定向，否则下到的是 HTML
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    val rootDir: File get() = File(appContext.getExternalFilesDir(null), "episodes")
    val audioDir: File get() = File(rootDir, "audio")
    val srtDir: File get() = File(rootDir, "srt")

    /** 译文目录。与字幕并列 —— 删音频时两者要一起走，否则会留下播不了的孤儿 */
    val zhDir: File get() = File(rootDir, "zh")

    fun audioFile(episode: Episode): File =
        File(audioDir, (episode.audioFileName ?: defaultAudioName(episode)))

    fun srtFile(episode: Episode): File =
        File(srtDir, episode.srtFileName ?: "${episode.id}.srt")

    fun translationFile(episode: Episode): File = File(zhDir, "${episode.id}.zh.json")

    /** 已下载音频的文件名（含扩展名），用于在下载前就确定落盘路径 */
    fun defaultAudioName(episode: Episode): String = "${episode.id}.${extensionOf(episode.audioUrl)}"

    fun hasAudio(episode: Episode): Boolean = audioFile(episode).let { it.isFile && it.length() > 0 }

    fun hasSrt(episode: Episode): Boolean = srtFile(episode).let { it.isFile && it.length() > 0 }

    fun hasTranslation(episode: Episode): Boolean =
        translationFile(episode).let { it.isFile && it.length() > 2 }

    /**
     * 下载一期音频。
     *
     * 地址来自 [AudioUrlNormalizer] 的候选列表（直连优先、原链兜底），**逐个尝试**。
     * 播客的 enclosure 是统计跳转链，中间域被运营商干扰或限流是常态，
     * 单个地址失败就整条内容失败太脆弱 —— 多试一个地址的成本远低于重下一次。
     *
     * 断点续传策略与模型下载器一致：
     *  - 已有 `.part` → `Range: bytes=<已下>-`
     *  - 服务器回 206 → 追加；回 200 → 它不支持续传，丢弃重写
     *  - 只有完整下完才改名到正式文件，中途失败留下的是 `.part`
     *
     * 实测各层跳转的 final-url 完全相同，所以换候选时沿用 `.part` 续传是安全的。
     */
    suspend fun download(
        episode: Episode,
        onProgress: (AudioDownloadProgress) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        audioDir.mkdirs()
        val target = audioFile(episode)
        if (target.isFile && target.length() > 0) return@withContext target

        val candidates = AudioUrlNormalizer.candidates(episode.audioUrl)
        val failures = mutableListOf<String>()
        var lastError: Exception? = null

        for ((index, url) in candidates.withIndex()) {
            var attempt = 0
            while (true) {
                attempt++
                try {
                    return@withContext attemptDownload(url, episode, target, onProgress)
                } catch (e: Exception) {
                    lastError = e
                    // 同一地址重试：实测 NPR 的 CDN 偶发挂起（同一个 URL 前一次超时、
                    // 后一次正常），换地址之前先给自己一次机会，成本远低于重新下整条。
                    if (attempt < ATTEMPTS_PER_CANDIDATE) continue
                    failures.add("${hostOf(url)}: ${e.message ?: e::class.simpleName}")
                }
                break
            }
            // 后面还有候选就继续试；最后一个失败则把每个地址的原因汇总抛出去
            if (index == candidates.lastIndex) {
                throw IOException(
                    "试过 ${candidates.size} 个地址都不可用 → " + failures.joinToString("；")
                )
            }
        }
        // 理论上不可达（candidates 至少 1 个）
        throw IOException(lastError?.message ?: "下载失败")
    }

    /** 对单个地址做一次下载尝试，成功返回正式文件 */
    private fun attemptDownload(
        url: String,
        episode: Episode,
        target: File,
        onProgress: (AudioDownloadProgress) -> Unit,
    ): File {
        val part = File(audioDir, target.name + ".part")
        val startAt = if (part.isFile) part.length() else 0L

        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "audio/*,*/*")
        if (startAt > 0) builder.header("Range", "bytes=$startAt-")

        client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code}")
            }
            val body = response.body ?: throw IOException("响应体为空")
            // URL 可能被 Content-Length 为 0 或极小的错误页顶掉，提前拦一下
            val contentLength = body.contentLength()
            val total = if (contentLength > 0) contentLength + (if (response.code == 206) startAt else 0L) else 0L

            val resuming = response.code == 206 && startAt > 0L
            if (!resuming && part.exists()) part.delete()

            var done = if (resuming) startAt else 0L
            var lastReport = System.currentTimeMillis()
            var windowStart = lastReport
            var windowBytes = 0L

            RandomAccessFile(part, "rw").use { raf ->
                raf.seek(done)
                body.byteStream().use { input ->
                    val buf = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        raf.write(buf, 0, n)
                        done += n
                        windowBytes += n

                        val now = System.currentTimeMillis()
                        if (now - lastReport >= PROGRESS_INTERVAL_MS) {
                            val elapsed = (now - windowStart).coerceAtLeast(1L)
                            onProgress(
                                AudioDownloadProgress(
                                    episodeId = episode.id,
                                    bytesDone = done,
                                    bytesTotal = total,
                                    bytesPerSecond = windowBytes * 1000L / elapsed,
                                )
                            )
                            lastReport = now
                            if (now - windowStart >= SPEED_WINDOW_MS) {
                                windowStart = now
                                windowBytes = 0L
                            }
                        }
                    }
                }
                raf.fd.sync()
            }

            if (done <= 0L) throw IOException("下载到 0 字节")
            // 拿到总长度就校验完整性；拿不到（chunked）就只要求非空
            if (total > 0 && done != total) {
                // 半截文件留着只会让下次续传出错，直接丢掉重来
                part.delete()
                throw IOException("字节数不符：$done != $total")
            }

            if (target.exists()) target.delete()
            if (!part.renameTo(target)) {
                part.copyTo(target, overwrite = true)
                part.delete()
            }
            onProgress(
                AudioDownloadProgress(episode.id, target.length(), target.length(), 0L)
            )
            return target
        }
    }

    private fun hostOf(url: String): String =
        url.substringAfter("://", url).substringBefore('/')

    /**
     * 写字幕。**临时文件 + 改名**（2.0 改成原子写）。
     *
     * 原来这里是裸 `f.writeText(...)`。它的风险不是「写不进去」，而是
     * **写进去半截**：进程在写的中途被杀（改动之后这条路径还多了「从服务器下载」，
     * 网络中断也是同一类），`srt/` 下就留下一份截断的 SRT。
     * 而 `SrtTrack.parse` 对截断是**宽容**的 —— 它会照样解析出前 N 句、
     * 不报任何错，用户看到的是「字幕到一半就没了」。
     * 这类「不报错但内容少了一半」正是本工程最想避免的失败形态。
     * 译文那一侧（[writeTranslation]）早就是 tmp + rename，字幕这边补齐。
     */
    fun writeSrt(episode: Episode, content: String): File {
        srtDir.mkdirs()
        val f = srtFile(episode)
        val tmp = File(srtDir, f.name + ".tmp")
        tmp.writeText(content, Charsets.UTF_8)
        if (f.exists()) f.delete()
        if (!tmp.renameTo(f)) {
            tmp.copyTo(f, overwrite = true)
            tmp.delete()
        }
        return f
    }

    fun readSrt(episode: Episode): String? =
        srtFile(episode).takeIf { it.isFile }?.readText(Charsets.UTF_8)

    // ------------------------------------------------------------------
    // 译文
    // ------------------------------------------------------------------

    /**
     * 写译文。[lines] 的下标必须与 SRT 句段下标一一对应，
     * 还没翻的位置传 null —— 这样任务被打断后下次能接着翻。
     */
    fun writeTranslation(episode: Episode, lines: List<String?>) {
        zhDir.mkdirs()
        val arr = JSONArray()
        for (s in lines) arr.put(s ?: JSONObject.NULL)
        val f = translationFile(episode)
        // 先写临时文件再改名：直接覆写时被进程杀死会留下半截 JSON，
        // 下次读出来解析失败，前面翻好的全白费
        val tmp = File(zhDir, f.name + ".tmp")
        tmp.writeText(arr.toString(), Charsets.UTF_8)
        if (f.exists()) f.delete()
        if (!tmp.renameTo(f)) {
            tmp.copyTo(f, overwrite = true)
            tmp.delete()
        }
    }

    fun readTranslation(episode: Episode): List<String?>? {
        val f = translationFile(episode)
        if (!f.isFile || f.length() <= 2) return null
        return runCatching {
            val arr = JSONArray(f.readText(Charsets.UTF_8))
            (0 until arr.length()).map { i -> if (arr.isNull(i)) null else arr.getString(i) }
        }.getOrNull()
    }

    fun deleteEpisodeFiles(episode: Episode) {
        audioFile(episode).delete()
        File(audioDir, audioFile(episode).name + ".part").delete()
        srtFile(episode).delete()
        // 原子写留下的临时文件也要一起清 —— 留着就是永远不会被读到的孤儿
        File(srtDir, srtFile(episode).name + ".tmp").delete()
        translationFile(episode).delete()
        File(zhDir, "${episode.id}.zh.json.tmp").delete()
    }

    /** 音频缓存占用（只算正式文件，不含 .part） */
    fun cacheBytes(): Long =
        audioDir.listFiles()?.filter { it.isFile && it.extension != "part" }?.sumOf { it.length() } ?: 0L

    /**
     * 存储占用明细。
     *
     * 音频、字幕、译文**分开统计**：字幕和译文都是纯文本，一集才几十 KB ——
     * 合成一个数字，用户看到「占用 500 MB」会以为文本很占地方，清理时删错东西。
     */
    fun breakdown(): StorageBreakdown {
        val audio = audioDir.listFiles()?.filter { it.isFile && it.extension != "part" } ?: emptyList()
        val srt = srtDir.listFiles()?.filter { it.isFile } ?: emptyList()
        val zh = zhDir.listFiles()?.filter { it.isFile && it.extension != "tmp" } ?: emptyList()
        return StorageBreakdown(
            audioBytes = audio.sumOf { it.length() },
            audioCount = audio.size,
            srtBytes = srt.sumOf { it.length() },
            srtCount = srt.size,
            zhBytes = zh.sumOf { it.length() },
            zhCount = zh.size,
        )
    }

    /**
     * LRU 淘汰：把缓存压到 [limitBytes] 以内，被淘汰的文件不会删掉 [keepIds] 里的条目。
     *
     * 按 `lastModified` 排序 —— 播放时会把文件 touch 一次，所以它近似「最近使用时间」。
     * 用「先删最久没听的」而不是「先删最老的」，才不会把今天刚更新、还没听的删掉。
     */
    fun enforceCacheLimit(keepIds: Set<String>, limitBytes: Long = DEFAULT_CACHE_LIMIT_BYTES): Int {
        // ⚠️ `.part` 必须排除在外（1.2.0 修）。原来这里只过滤 `isFile`，
        // 而下面提取 id 用的是 `substringBeforeLast('.')` ——
        // 对 `abc.mp3.part` 得到的是 `abc.mp3`，**永远匹配不上** keepIds 里的 `abc`。
        // 于是一个正在下载（或已续传一半）的临时文件会被当成「可淘汰」删掉，
        // 表现是「下载进度莫名归零又重来」。
        //
        // 同文件里的 `cacheBytes()` 与 `breakdown()` 都正确地排除了 `.part`，
        // 唯独这里漏了 —— 这也是为什么它没被任何统计口径发现。
        val files = audioDir.listFiles()
            ?.filter { it.isFile && it.extension != "part" }
            ?: return 0
        var total = files.sumOf { it.length() }
        if (total <= limitBytes) return 0

        var removed = 0
        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= limitBytes) break
            val id = f.name.substringBeforeLast('.')
            if (id in keepIds) continue
            val size = f.length()
            if (f.delete()) {
                total -= size
                removed++
                // 字幕和译文跟着音频一起走，否则会留下永远播不了的孤儿文件
                File(srtDir, "$id.srt").delete()
                File(zhDir, "$id.zh.json").delete()
            }
        }
        return removed
    }

    /** 播放时调用，刷新 LRU 时间戳 */
    fun touch(episode: Episode) {
        runCatching { audioFile(episode).setLastModified(System.currentTimeMillis()) }
    }

    fun clearAll() {
        audioDir.listFiles()?.forEach { it.delete() }
        srtDir.listFiles()?.forEach { it.delete() }
        zhDir.listFiles()?.forEach { it.delete() }
    }

    companion object {
        private const val BUFFER_SIZE = 128 * 1024
        private const val PROGRESS_INTERVAL_MS = 400L
        private const val SPEED_WINDOW_MS = 5_000L
        private const val USER_AGENT = "EnglishCommute/0.1 (Android)"

        /** 同一个地址最多试几次再换下一个候选 */
        private const val ATTEMPTS_PER_CANDIDATE = 2

        /** 音频缓存上限。5 条内容约 50 MB，1 GB 能留约 20 天的通勤量 */
        const val DEFAULT_CACHE_LIMIT_BYTES = 1024L * 1024 * 1024

        /** 从 URL 推断扩展名。实测源的 enclosure 都带 query，必须先切掉 */
        fun extensionOf(url: String): String {
            val path = url.substringBefore('?').substringBefore('#')
            return when (path.substringAfterLast('.', "").lowercase()) {
                "mp3" -> "mp3"
                "m4a" -> "m4a"
                "aac" -> "aac"
                "mp4" -> "mp4"
                "wav" -> "wav"
                "ogg" -> "ogg"
                "opus" -> "opus"
                else -> "mp3"
            }
        }
    }
}
