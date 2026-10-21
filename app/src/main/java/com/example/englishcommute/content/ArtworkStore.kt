package com.example.englishcommute.content

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.example.englishcommute.diag.DiagLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 封面图仓库。
 *
 * ## 为什么自己写而不用 Coil
 *
 * 这个 App 的封面需求窄到只有一种：**一个源一张方图**（11 个源）。
 * 没有 GIF、没有视频帧、没有变换链、没有 disk cache 策略要调。
 * 为这点需求引入 Coil 要多一个依赖、多一套混淆规则、多一个「版本升不升」的问题 ——
 * 而依赖版本在这个工程里已经是个踩过坑的地方（见 `gradle/libs.versions.toml`）。
 * 自带的 [LruCache] + 一次 `BitmapFactory` 就够了，代码量比读 Coil 的文档还少。
 *
 * ## 三级缓存，各自解决一个问题
 *
 * | 层 | 存什么 | 解决 |
 * |---|---|---|
 * | 内存 LruCache | 解码后的 Bitmap（≤ [TARGET_PX] 边长） | 滚动列表时的**重复解码**。这是唯一会在 UI 线程上被感知的一层 |
 * | 磁盘 `filesDir/artwork/{sourceId}.jpg` | 原始下载字节 | 重启后的**重复下载** |
 * | 网络 | — | 首次 |
 *
 * 内存层按**解码后**计费而不是按文件字节：一张 600×600 的 JPEG 约 200 KB，
 * 解码成 ARGB_8888 却是 1.4 MB（7 倍）。按文件大小设上限，实际占用会失控。
 *
 * ## 按源缓存，不按条
 *
 * 文件名是 `{sourceId}.jpg` —— 同源所有条目共用一张。这一批 11 个源里，
 * 绝大多数条目的封面本来就是频道封面（NPR News Now、Listening Time 的
 * `<item>` 里连图都没有）。按条缓存会存 40 张、其中大量是同一张图。
 *
 * 代价是 TED 这类「每集不同封面」的源只能显示第一张 —— 可以接受：
 * 列表里同一来源看起来一致，反而更像一个有序的播放列表。
 *
 * ## ⚠️ 图片所在的域和 feed 不是同一个
 *
 * 选源时立的规矩是「宿主必须在真机上验证可达」，而验证的是
 * `feeds.npr.org` / `feeds.simplecast.com`。但**图床是另外三个域**：
 * `media.npr.org`（8 个源）、`image.simplecastcdn.com`（2 个）、
 * `npr.brightspotcdn.com`（1 个）。它们的可达性是**没验证过的**，
 * 而且是「跨域失败也完全静默」的那种故障 —— 界面只表现为占位块。
 *
 * 为此这里做了三件事，让「图到底下没下下来」不再需要靠猜：
 *  1. 每个源的成败与原因都记日志（`Art` tag），并汇总成 [lastReport]
 *  2. 失败过的图床进入**冷却**，同一轮不再对同一个域反复撞超时
 *  3. 界面侧有 [cachedCount] 与 [lastReport] 可直接显示
 */
class ArtworkStore(context: Context) {

    private val appContext = context.applicationContext
    private val dir = File(appContext.filesDir, ARTWORK_DIR)

    /**
     * 超时收得比 feed 短得多。
     *
     * 一张封面 100–300 KB，正常几百毫秒就到。而图床在大陆**可能是黑洞**
     * （包被丢掉而不是拒绝），那种情况下会一直等到超时 —— 按 30 s 算，
     * 11 个源就是 5 分半，全卡在「一键更新」的开头。
     */
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /**
     * 内存缓存。上限按**解码后字节数**算，见类注释。
     *
     * RGB_565 而不是默认的 ARGB_8888：封面是方图、没有透明通道，
     * 用 2 字节/像素能省一半 —— 11 张 512px 的图从 11 MB 降到 5.5 MB。
     */
    private val memory = object : LruCache<String, Bitmap>(MEMORY_BUDGET_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /**
     * 每源一把锁，防止**同一张图被并发下载多遍**。
     *
     * 列表一屏会同时请求好几个源的封面，而每个源可能有多条内容在屏幕上 ——
     * 没有这层保护时，NPR News Now 的两条会各发一次请求下同一张图。
     */
    private val locks = ConcurrentHashMap<String, Mutex>()

    /**
     * 下载专用的作用域。
     *
     * ⚠️ **不能用调用方（界面）的协程直接下载。** 界面那边是
     * `LaunchedEffect`，在 `LazyColumn` 里滚动出屏幕就会被取消 ——
     * 结果是一张 300 KB 的图刚下到一半就被掐断，快速滑动时永远下不完，
     * 而且不报错，只表现为「封面一直是占位块」。
     *
     * 放到应用级作用域之后，界面取消的只是**等待**，下载本身跑完，
     * 下次进入列表直接从磁盘命中。
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 在飞的下载，按 sourceId 去重。见 [jobFor] */
    private val inFlight = ConcurrentHashMap<String, Deferred<Bitmap?>>()

    /** 每个源最近一次失败的原因（成功时清掉），用于汇总上报 */
    private val lastError = ConcurrentHashMap<String, String>()

    /** 冷却中的图床：host → 可以再试的时刻。见 [isHostCoolingDown] */
    private val hostDownUntil = ConcurrentHashMap<String, Long>()

    /** 最近一次批量补齐的结果，供设置页显示 */
    @Volatile
    var lastReport: Report? = null
        private set

    /**
     * 一次封面补齐的结果。
     *
     * @param ok 成功张数
     * @param failures 失败的源（显示名 → 原因）。**原因必须带出来** ——
     *   「封面下不来」这个信息本身没用，要能一眼看出是
     *   「图床不可达」还是「源站根本没给图」
     */
    data class Report(
        val requested: Int,
        val ok: Int,
        val failures: List<Pair<String, String>>,
    ) {
        val failed: Int get() = failures.size
    }

    private fun fileFor(sourceId: String): File = File(dir, "$sourceId.jpg")

    /** 只读内存，不碰磁盘。可以放心在组合期间调用 */
    fun peek(sourceId: String): Bitmap? = memory.get(sourceId)

    /**
     * 取封面：内存 → 磁盘 → 网络。
     *
     * 同一个源的并发请求会**共享同一个下载任务**，不会各下一份。
     * 调用方被取消（滚动出屏幕）时只是不再等，任务本身照常跑完。
     *
     * @param url 空串表示源站没给图 —— 直接返回 null，让界面用占位块，
     *   不去猜、不去第三方接口找。猜出来的封面跟真的长得一样，猜错就是错的品牌信息。
     */
    suspend fun load(sourceId: String, url: String): Bitmap? {
        memory.get(sourceId)?.let { return it }
        return runCatching { jobFor(sourceId, url).await() }.getOrNull()
    }

    /**
     * 拿到（或创建）这个源的下载任务。
     *
     * 用 `LAZY` 起手 + `putIfAbsent`：如果别的协程抢先注册了，这个任务
     * 就永远不会启动，不会白下一次。`remove(key, value)` 带值比较，
     * 保证只清理自己那一个 —— 否则会把后来者的任务从表里挤掉，
     * 导致同一张图被重复下载。
     */
    private fun jobFor(sourceId: String, url: String): Deferred<Bitmap?> {
        inFlight[sourceId]?.let { return it }
        val deferred = scope.async(start = CoroutineStart.LAZY) { loadLocked(sourceId, url) }
        val existing = inFlight.putIfAbsent(sourceId, deferred)
        if (existing != null) return existing
        deferred.invokeOnCompletion { inFlight.remove(sourceId, deferred) }
        deferred.start()
        return deferred
    }

    private suspend fun loadLocked(sourceId: String, url: String): Bitmap? {
        val lock = locks.getOrPut(sourceId) { Mutex() }
        return lock.withLock {
            // 等锁期间别的协程可能已经下好了，再查一次
            memory.get(sourceId)?.let { return@withLock it }

            val file = fileFor(sourceId)
            if (file.isFile) {
                decode(file)?.let {
                    memory.put(sourceId, it)
                    return@withLock it
                }
                // 解不出来说明文件坏了（下载时被截断、或写过一半被系统杀掉）。
                // 删掉重下，否则会永远停在一个「有文件但显示不出来」的状态
                DiagLog.w("Art", "封面文件损坏，删除重下：$sourceId")
                file.delete()
            }

            if (url.isEmpty()) {
                lastError[sourceId] = "源站没给图"
                return@withLock null
            }
            download(sourceId, url)?.also { memory.put(sourceId, it) }
        }
    }

    /**
     * 批量补齐缺失的封面，每源只下一次。
     *
     * ## 并发 3，不是串行
     *
     * 串行的代价在**图床不可达**时才显出来：11 个源 × 最多 15 s 超时 = 165 s，
     * 而且这段时间是堵在「一键更新」最前面的。并发之后上限降到
     * `ceil(11/3) × 15s ≈ 60s`，且正常网络下十几个请求加起来不到两秒。
     *
     * 限 3 而不是全放：再高只是把同一块带宽切成更细的片，
     * 而且会跟紧接着要跑的音频下载抢连接。
     *
     * ## 失败图床冷却
     *
     * 8 个 NPR 源共用 `media.npr.org`。如果它不可达，不冷却的话就是
     * 8 次独立的超时；冷却之后第二个源开始直接跳过，日志里只剩一条
     * 明确的「该域不可达」。
     *
     * @return 本轮结果。调用方**不应该**把它当成关键路径 —— 封面是装饰。
     */
    suspend fun fetchMissing(episodes: List<Episode>): Report {
        val bySource = episodes
            .filter { it.artworkUrl.isNotEmpty() }
            .distinctBy { it.sourceId }
        val targets = bySource.filter { !fileFor(it.sourceId).isFile }

        if (targets.isEmpty()) {
            val r = Report(0, 0, emptyList())
            lastReport = r
            return r
        }

        val results = coroutineScope {
            val sem = Semaphore(ARTWORK_CONCURRENCY)
            targets.map { ep ->
                async {
                    val loaded = sem.withPermit { load(ep.sourceId, ep.artworkUrl) != null }
                    Triple(ep.sourceName, loaded, lastError[ep.sourceId] ?: "未知原因")
                }
            }.awaitAll()
        }

        val ok = results.count { it.second }
        val failures = results.filterNot { it.second }.map { it.first to it.third }
        val report = Report(targets.size, ok, failures)
        lastReport = report

        DiagLog.i(
            "Art",
            "封面补齐：${ok}/${targets.size} 成功" +
                if (failures.isEmpty()) ""
                else " · 失败：" + failures.joinToString("、") { "${it.first}(${it.second})" },
        )
        return report
    }

    private suspend fun download(sourceId: String, url: String): Bitmap? = withContext(Dispatchers.IO) {
        val host = hostOf(url)
        // 冷却中的图床直接跳过。这一步必须在最前面 —— 它的价值就是
        // 不再为同一个不可达的域付第二遍超时
        if (isHostCoolingDown(host)) {
            lastError[sourceId] = "$host 不可达（本轮已跳过）"
            return@withContext null
        }

        val tmp = File(dir, "$sourceId.tmp")
        try {
            dir.mkdirs()
            client.newCall(
                Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
            ).execute().use { r ->
                if (!r.isSuccessful) {
                    val reason = "HTTP ${r.code}"
                    // 403/429/5xx 是**域级**问题（WAF、限流、图床挂了），
                    // 404 只是这张图不在了 —— 前者才值得把整个域冷却掉
                    if (r.code == 403 || r.code == 429 || r.code >= 500) markHostDown(host)
                    lastError[sourceId] = reason
                    DiagLog.w("Art", "封面 $reason：$sourceId · $host")
                    return@withContext null
                }
                val body = r.body ?: return@withContext null
                // 先写临时文件再改名：直接写目标名的话，进程在写一半时被杀
                // 就会留下一个「存在但损坏」的文件，而 [load] 只在解码失败时才发现
                tmp.outputStream().use { out -> body.byteStream().copyTo(out) }
            }
            if (tmp.length() <= 0L) {
                tmp.delete()
                lastError[sourceId] = "响应为空"
                return@withContext null
            }

            val dest = fileFor(sourceId)
            dest.delete()
            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
            }

            decode(dest).also { bmp ->
                if (bmp == null) {
                    lastError[sourceId] = "解码失败"
                    DiagLog.w("Art", "封面下载后解码失败：$sourceId")
                } else {
                    lastError.remove(sourceId)
                }
            }
        } catch (e: Exception) {
            // 连接超时 / 无法解析主机 / TLS 失败 —— 都是域级问题，冷却掉
            markHostDown(host)
            lastError[sourceId] = "${e::class.simpleName}: ${e.message?.take(60)}"
            DiagLog.w("Art", "封面下载失败 $sourceId · $host：${e::class.simpleName}: ${e.message}")
            tmp.delete()
            null
        }
    }

    private fun hostOf(url: String): String = runCatching {
        java.net.URI(url).host ?: url
    }.getOrDefault(url)

    private fun isHostCoolingDown(host: String): Boolean {
        val until = hostDownUntil[host] ?: return false
        if (System.currentTimeMillis() >= until) {
            hostDownUntil.remove(host)
            return false
        }
        return true
    }

    private fun markHostDown(host: String) {
        val first = hostDownUntil.putIfAbsent(host, System.currentTimeMillis() + HOST_COOLDOWN_MS) == null
        if (first) {
            DiagLog.w("Art", "$host 标记为不可达，${HOST_COOLDOWN_MS / 1000}s 内不再重试本域图片")
        }
    }

    /**
     * 解码并按 [TARGET_PX] 降采样。
     *
     * 两步走（先 `inJustDecodeBounds` 只读尺寸、再带 `inSampleSize` 真正解码）：
     * 一步到位会把 1400×1400 的原图完整解进内存再缩，那正是内存峰值的来源。
     */
    private fun decode(file: File): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, TARGET_PX)
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        BitmapFactory.decodeFile(file.absolutePath, opts)
    }.getOrNull()

    /** 磁盘上已有几个源的封面（界面上用来确认「封面到底下没下下来」） */
    fun cachedCount(): Int = dir.listFiles()?.count { it.isFile && it.name.endsWith(".jpg") } ?: 0

    fun bytes(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0L

    /** 只清磁盘与内存，不动音频。设置页的「清空缓存」会连带调用 */
    fun clear() {
        memory.evictAll()
        lastError.clear()
        hostDownUntil.clear()
        lastReport = null
        runCatching { dir.listFiles()?.forEach { it.delete() } }
    }

    /**
     * 只丢内存里那份解码结果，**磁盘上的图留着**。
     *
     * 给 `Application.onTrimMemory` 用。和 [clear] 的区别很关键：
     * [clear] 是用户在设置页点「清空缓存」，意思是「这些图我不要了」；
     * 而内存压力只说明「现在挤，先让一让」—— 把磁盘上的图也删掉，
     * 等压力过去还得重新联网下一遍，用流量换内存是亏的。
     *
     * 丢掉之后下次显示会从磁盘重新解码（几毫秒），列表不会闪空 ——
     * `Artwork` 组件的 `peek()` 磁盘命中路径仍然是首帧出图。
     */
    fun trimMemory() {
        memory.evictAll()
    }

    private companion object {
        const val ARTWORK_DIR = "artwork"
        const val USER_AGENT = "EnglishCommute/0.1 (Android)"

        /** 同时最多下几张封面。见 [fetchMissing] */
        const val ARTWORK_CONCURRENCY = 3

        /**
         * 图床被判定不可达后的冷却时长。
         *
         * 10 分钟是「比一轮同步长、比一次通勤短」——足够避免同一轮里
         * 反复撞同一个域，又不会把一次瞬时抖动记成永久故障。
         */
        const val HOST_COOLDOWN_MS = 10 * 60 * 1000L

        /**
         * 解码目标边长。
         *
         * 列表里显示 56dp（3x 屏约 168px），播放页大图 240dp（约 720px）。
         * 取 512 是两者的折中：按列表算它是浪费的，但按播放页算刚好够 ——
         * 一张封面只在切换主题/清缓存时才重新解码，为省几 MB 内存把它做糊不划算。
         */
        const val TARGET_PX = 512

        /** 内存上限。512px RGB_565 每张 512 KB，这里大约能放 8 张 */
        const val MEMORY_BUDGET_BYTES = 4 * 1024 * 1024

        fun sampleSizeFor(width: Int, height: Int, target: Int): Int {
            var maxDim = maxOf(width, height)
            var sample = 1
            while (maxDim / 2 >= target) {
                maxDim /= 2
                sample *= 2
            }
            return sample
        }
    }
}
