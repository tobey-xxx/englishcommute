package com.example.englishcommute.content

import android.content.Context
import com.example.englishcommute.diag.DiagLog
import com.example.englishcommute.net.ServerCatalog
import com.example.englishcommute.net.ServerConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/** 单个源的拉取结果 */
data class SourceResult(
    val source: FeedSource,
    val found: Int,
    val error: String? = null,
)

/**
 * 服务器目录在界面上的「源」身份（2.0）。
 *
 * ⚠️ 它**不在** [FeedSources.ALL] 里，也不要加进去 —— 那个列表是「要逐个拉取的 RSS 源」，
 * 加进去会让客户端又去拉一遍服务器已经在做的事。这里只是让 [SourceResult]
 * 有个能显示的名字（更新进度里那行「11 个源」在服务器模式下是「服务器」1 个）。
 */
private val SERVER_SOURCE = FeedSource(
    id = "__server",
    name = "服务器",
    url = ServerConfig.BASE_URL,
    maxEpisodes = 0,
)

/**
 * 内容仓库：把「拉 RSS → 合并索引 → 下载音频 → 转写」串起来，并持有唯一的一份条目状态。
 *
 * 索引落盘在 `filesDir/episodes.json`。**合并时以条目 id 为准保留已有状态** ——
 * 否则每次更新都会把「已下载完的音频」当成新条目重新下一遍。
 */
class ContentRepository(context: Context) {

    private val appContext = context.applicationContext
    private val parser = RssParser()

    val audioStore = AudioStore(appContext)

    /**
     * 服务器目录客户端（2.0）。**内容来源由它和 RSS 二选一**，
     * 判据是「能不能拿到目录」，见 [refreshFeeds]。
     */
    val serverCatalog = ServerCatalog(appContext)

    private val indexFile = File(appContext.filesDir, "episodes.json")

    /**
     * 用户明确删掉的条目 id（1.2.2）。
     *
     * ## 为什么不能只「从索引里去掉」
     *
     * 索引是**每次刷新重建**的：`refreshFeeds` 把 RSS 拉回来的条目并进来。
     * 只从内存里删掉的话，用户下一次点「更新今日内容」——
     * 只要那条还在源的 feed 里（NPR 是每小时更新的）—— 它就会原样回来，
     * 而且因为发布在 2 天窗口内，**还会被重新下载一遍音频**。
     *
     * 用户读到的是「我明明删了它」。这类「删了又回来」比删不掉更让人恼火，
     * 所以必须有一份跨刷新的记录。
     *
     * ## 为什么放在仓库而不是 AppSettings
     *
     * 它是「这条内容不存在」这件事的一部分，和索引同生共死 ——
     * 索引文件没了、删除记录却留着，或者反过来，都是不一致的。
     * 放在同一个类里，两件事的读写节奏才有可能对齐。
     *
     * 没有上限：索引本身最多 40 条，id 约 20 字节，用户删一万条也才 200 KB。
     */
    private val ignoredFile = File(appContext.filesDir, "ignored.json")

    private val _ignored = MutableStateFlow<Set<String>>(emptySet())

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    private val _episodes = MutableStateFlow<List<Episode>>(emptyList())
    val episodes: StateFlow<List<Episode>> = _episodes.asStateFlow()

    /**
     * 落盘用的 scope。应用级 —— 索引写入不该被任何界面的生命周期取消。
     *
     * `SupervisorJob` 是必须的：一次写盘失败（磁盘满、权限变更）不该让
     * 后续所有写入都跟着停摆。
     */
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pendingPersist: Job? = null

    init {
        load()
        // 删除记录要在合并之前就位：refreshFeeds 靠它跳过用户删掉的内容
        loadIgnored()
        // 对账必须在 load 之后、任何界面读到这份索引之前完成。
        // 见 reconcileInterruptedState 的注释：不做这一步，一次闪退就会让条目永久卡住。
        reconcileInterruptedState()
        // 保留期迁移紧跟着对账 —— 两者都在「索引已经读进内存、还没被任何人看到」的窗口里。
        // 顺序不能反：迁移要按磁盘现状决定给谁补时间戳，而对账会改状态位
        migrateRetention()
        // 2.0.3 的迁移放在最后：它要按**已经对账过**的状态位来判断谁被错标过
        migrateStaleSubtitleFailure()
    }

    /**
     * 2.0.3 迁移：把「字幕还没到位」被错标成失败的那些条目改回来。
     *
     * ## 为什么要专门做一次迁移，而不是「新代码不管状态位」就完事
     *
     * 修好的判据（[Episode.isPlayable]）确实已经不看状态位了，所以升级上来
     * 那些行**立刻就能点**。但这批状态是**落了盘**的，不改就会一直留在索引里：
     *
     *  ① 界面上那条红色文案（「字幕暂缺 · 装离线识别后自动补上」）本身就是
     *     那次误报的原话 —— 留着它，下一次排查还会被它带偏（这一轮的诊断
     *     就差点被它骗过一次）；
     *  ② 以后任何一处按状态位做的判断（比如「失败的要重试」）都会重新踩同一个坑。
     *
     * ## 只动「Failed + 音频在盘上」这一类
     *
     * 音频真没下来的（`audioFileName == null`）不在这里，它们仍然是 Failed，
     * 那是诚实的。所以这里的 `error` 一律可以清掉：这一类里它只可能是那条字幕提示。
     *
     * 代价是一轮启动时的文件检查，正常情况下这个集合是空的（升级后第一次启动才非空）。
     */
    private fun migrateStaleSubtitleFailure(): Int {
        val stale = _episodes.value.filter {
            it.status == EpisodeStatus.Failed && it.audioFileName != null
        }
        if (stale.isEmpty()) return 0
        val ids = stale.map { it.id }.toSet()
        _episodes.value = _episodes.value.map { ep ->
            if (ep.id !in ids) {
                ep
            } else {
                ep.copy(
                    status = if (audioStore.hasSrt(ep)) {
                        EpisodeStatus.Ready
                    } else {
                        EpisodeStatus.Downloaded
                    },
                    error = null,
                )
            }
        }
        persistNow()
        DiagLog.w(
            "Repo",
            "迁移：${stale.size} 条「字幕还没到位」被上一版错标成失败，" +
                "已恢复为可播放（它们一直能听，只是点不动）",
        )
        return stale.size
    }

    // ------------------------------------------------------------------
    // 索引读写
    // ------------------------------------------------------------------

    private fun load() {
        if (!indexFile.isFile) return
        runCatching {
            val arr = JSONArray(indexFile.readText(Charsets.UTF_8))
            _episodes.value = (0 until arr.length()).mapNotNull { i ->
                runCatching { Episode.fromJson(arr.getJSONObject(i)) }.getOrNull()
            }.sortedByDescending { it.publishedAt }
        }
    }

    /**
     * 把索引写到磁盘。**原子写**（1.2.0 改）。
     *
     * 原来是裸 `indexFile.writeText(...)`。索引是这个 App 里最关键的一份数据，
     * 却是唯一一处没有原子写保护的 —— `ArtworkStore` 和 `AudioStore` 都老老实实
     * 用了 tmp + rename。进程在 `writeText` 中途被杀（转写要跑十几分钟，
     * 这期间被系统回收是常态）就会留下半截 JSON，而 [load] 的 `runCatching`
     * 会**静默吞掉**解析失败 —— 用户看到的是「素材库突然空了」，
     * 而且日志里什么都没有。
     */
    private fun persistNow() {
        runCatching {
            val arr = JSONArray()
            _episodes.value.forEach { arr.put(it.toJson()) }
            val tmp = File(indexFile.parentFile, indexFile.name + ".tmp")
            tmp.writeText(arr.toString(), Charsets.UTF_8)
            if (indexFile.exists()) indexFile.delete()
            if (!tmp.renameTo(indexFile)) {
                tmp.copyTo(indexFile, overwrite = true)
                tmp.delete()
            }
        }
    }

    /**
     * 防抖落盘（1.2.0 新增）。
     *
     * ## 为什么需要
     *
     * 一条内容从发现到就绪要写 5 次索引（Downloading → Downloaded →
     * Transcribing → Ready，外加一次重复的 Downloading），而一轮同步处理
     * 3 条 + 刷新 + 裁剪 + 清理，**合计约 20 次全量重写**。
     *
     * 20 次写 24 KB 本身不贵。真正的代价是**跨层放大**：每次
     * `_episodes.value` 换新对象，素材库的 `remember(episodes, ...)` 就失效，
     * 于是源间交错排序（`interleaveBySource`）在**主线程组合期**重跑一遍。
     * 一轮同步里这件事要发生 20 次。
     *
     * ## 为什么不能简单地「少写几次」
     *
     * 索引是崩溃恢复的唯一依据。状态位写晚了，进程被杀时对账就会
     * 把一条已经下好的内容当成「没下过」。所以这里是**推迟**而不是**省略**，
     * 并且 [persistNow] 在关键节点（删除、裁剪）仍然立刻落盘。
     */
    private fun persist() {
        pendingPersist?.cancel()
        pendingPersist = ioScope.launch {
            delay(PERSIST_DEBOUNCE_MS)
            persistNow()
        }
    }

    /** 更新单个条目并落盘 */
    fun update(episode: Episode) {
        // ⚠️ 不再 sortedByDescending（1.2.0 改）。
        //
        // 替换一个已存在的条目**不会改变任何条目的 publishedAt**，
        // 所以列表顺序本来就不变 —— 每次都重排一遍 40 条纯属白做，
        // 而它恰好在「一轮同步要做 20 次」的那条路径上。
        //
        // 唯一需要排序的地方是 load()（磁盘顺序不可信）与 refreshFeeds()
        // （新条目进来了），两处都还在排。
        _episodes.value = _episodes.value
            .map { if (it.id == episode.id) episode else it }
        persist()
    }

    /**
     * 对账：把上一次进程留下的「进行中」状态收回来。
     *
     * ## 为什么必须有这一步
     *
     * [EpisodeStatus.Downloading] / [EpisodeStatus.Transcribing] 是**会落盘的**状态
     * （见 [Episode.toJson]）。而进程可能在任意一刻被杀掉 —— 内存回收、native 崩溃、
     * 用户强杀，都不给我们收尾的机会。于是索引里永久留下一条「转写中」：
     *
     * - 列表上那个转圈动画永远不会停（`isBusy` 驱动的）
     * - 条目点不动（`clickable(enabled = canPlay && !busy)`，而 busy 状态下不能播）
     * - 「转写字幕」按钮也不显示（条件是 `canTranscribe && !busy`）
     *
     * 用户看到的就是「卡住了」—— 这正是 0.9.0 之前那个 bug 的全貌。
     * 注意它**不只是界面问题**：状态位是数据层的真相，界面只是如实反映。
     *
     * ## 落到哪个状态以文件系统为准
     *
     * 状态位可能是错的（它崩在半路），文件不会。可能有三种情形：
     *
     * | 磁盘现状 | 判定 | 理由 |
     * |---|---|---|
     * | 有 srt | [EpisodeStatus.Ready] | 字幕已落盘，只是状态没来得及更新 |
     * | 只有音频 | [EpisodeStatus.Downloaded] | 音频可播，差字幕，用户可以手动重转 |
     * | 什么都没有 | [EpisodeStatus.Discovered] | 下次同步会重新下载 |
     *
     * 只对 `isBusy` 的条目做文件检查：正常情况下这个集合是空的，
     * 启动路径上不会有额外的 IO 开销。
     *
     * @return 被修正的条目数，供启动日志记录
     */
    fun reconcileInterruptedState(): Int {
        val interrupted = _episodes.value.filter { it.status.isBusy }
        if (interrupted.isEmpty()) return 0
        // 记下回落前的状态，只用于日志对照
        val wasStatus = interrupted.associate { it.id to it.status }

        _episodes.value = _episodes.value.map { ep ->
            if (!ep.status.isBusy) return@map ep

            val wasTranscribing = ep.status == EpisodeStatus.Transcribing
            val hasAudio = audioStore.hasAudio(ep)
            val hasSrt = audioStore.hasSrt(ep)
            val status = when {
                hasSrt -> EpisodeStatus.Ready
                hasAudio -> EpisodeStatus.Downloaded
                else -> EpisodeStatus.Discovered
            }

            ep.copy(
                status = status,
                audioFileName = if (hasAudio) (ep.audioFileName ?: audioStore.defaultAudioName(ep)) else null,
                srtFileName = if (hasSrt) (ep.srtFileName ?: "${ep.id}.srt") else null,
                // 字幕文件在但句数没记下来：从 srt 数一下时间轴行。
                // 不数的话界面会一直显示「0 句」—— 因为这条有 srt，
                // 后续同步的待转写集合（有音频且无字幕）永远不会再收它，没人会去修
                segmentCount = if (hasSrt && ep.segmentCount <= 0) countSrtCues(ep) else ep.segmentCount,
                error = if (status == EpisodeStatus.Ready) {
                    null
                } else {
                    "上次${if (wasTranscribing) "转写" else "下载"}被中断（应用意外退出）"
                },
            )
        }
        // 立刻落盘：启动对账：这份结果是之后一切判断的基准
        persistNow()

        // ⚠️ 这里必须打印**回落之后**的状态。
        //
        //   早先的写法是复用 `interrupted` 那个列表，而它存的是旧状态，
        //   于是日志写成「…→Transcribing」—— 对账的意义就是把这个状态改掉，
        //   日志却显示它还在，排查时会直接误判成「对账没生效」，白绕一圈。
        //   改成 before→after 对照后，一眼就能确认「到底回落成了什么」。
        val after = _episodes.value.filter { it.id in wasStatus.keys }
        DiagLog.w(
            "Repo",
            "对账：${interrupted.size} 条卡在进行中，已按磁盘现状回落 " +
                after.joinToString("、") { "${it.title.take(18)} ${wasStatus[it.id]}→${it.status}" },
        )
        return interrupted.size
    }

    /** 数 srt 里的时间轴行。每个字幕块恰好一行 `-->`，与解析实现无关，够稳。 */
    private fun countSrtCues(episode: Episode): Int = runCatching {
        val f = audioStore.srtFile(episode)
        if (!f.isFile) 0
        else f.bufferedReader(Charsets.UTF_8).useLines { lines -> lines.count { it.contains("-->") } }
    }.getOrDefault(0)

    fun find(id: String): Episode? = _episodes.value.firstOrNull { it.id == id }

    /**
     * 保留期迁移：给「盘上有音频、但没记下载时间」的条目补一个时间戳。
     *
     * ## 为什么必须补 `now` 而不是回推一个「看起来更准」的时间
     *
     * 这些条目来自 0.11.0 及更早的索引 —— 那时根本没有 [Episode.downloadedAt]
     * 这个字段。可用的替代信息有两个，都不该用：
     *
     * | 候选 | 为什么不选 |
     * |---|---|
     * | 文件 `lastModified` | 播放时被 [AudioStore.touch] 刷新过，等于「最近听过的永不过期」 |
     * | `publishedAt` | 发布早于下载，用它会让刚下完的内容立刻进入删除倒计时 |
     *
     * 补 `now` 的意思是「从这一版开始重新计算保留期」，代价只是老音频多留三天。
     * 删除是不可逆的，宁可迟删不可误删 —— 这条规矩在 [AudioStore.enforceCacheLimit]
     * 选 `KEEP` 而不是 `REPLACE` 时也是同一个道理。
     *
     * 只对「盘上真的有音频」的条目补：其余条目的 downloadedAt 本就该是 0。
     *
     * @return 被补上的条数，供启动日志记录
     */
    private fun migrateRetention(): Int {
        val now = System.currentTimeMillis()
        var migrated = 0
        _episodes.value = _episodes.value.map { ep ->
            if (ep.downloadedAt <= 0L && audioStore.hasAudio(ep)) {
                migrated++
                ep.copy(downloadedAt = now)
            } else {
                ep
            }
        }
        if (migrated > 0) {
            // 立刻落盘：启动迁移：一次性动作，没有拖延的理由
            persistNow()
            DiagLog.i("Repo", "保留期迁移：$migrated 条已有音频但没有下载时间，按「刚刚下载」起算 3 天")
        }
        return migrated
    }

    /**
     * 保留策略：**三天后自动清掉没被收藏的音频**。
     *
     * ## 与「更新窗口」是同一套设计的两半
     *
     * 下载窗口是 2 天（只补最近的），保留期是 3 天 —— 窗口比保留期短，
     * 所以**永远不会出现「刚下完就过期」**。反过来如果窗口更长，
     * 用户会看到「更新下来的内容下一轮就被删了」，那才是真的莫名其妙。
     *
     * ## 判据用 `downloadedAt` 而不是 `publishedAt`
     *
     * 因为用户说的是「音频文件保留 3 天」—— 文件在盘上存在多久，而不是
     * 内容发布多久。用发布时间的坏处很具体：一条发布 2 天后才被下载的内容，
     * 会在下载完的第二天就被删掉。
     *
     * ## 谁不能被删
     *
     * [keepIds] 由调用方给出，至少包含三类：
     *  - **收藏**（用户明确说过要留）
     *  - **正在播的那条**（文件句柄还开着，删了 ExoPlayer 会当场报错）
     *  - **正在下载/转写的**（[EpisodeStatus.isBusy]，删了会让流水线崩在半路）
     *
     * ## 一次删除要带走四样东西
     *
     * 音频、字幕、译文（[AudioStore.deleteEpisodeFiles]），外加**断点、
     * 播放进度、听完标记**。少清任何一样，都会在界面上留下一个自相矛盾的条目：
     * 断点会让下次续跑接在一段不存在的音频上，进度会让列表显示「已听 2:13」
     * 却点不动。
     *
     * @return 被清理的条数与释放的字节数
     */
    fun expireOldAudio(now: Long, keepIds: Set<String>): Expired {
        // 判定逻辑在 ContentPolicy（纯函数，桌面端可复刻验证）；
        // 这里只负责「真的删文件」与「把索引改回待下载」
        val targets = ContentPolicy.selectExpired(
            episodes = _episodes.value,
            now = now,
            hasAudio = { audioStore.hasAudio(it) },
            keepIds = keepIds,
        )
        if (targets.isEmpty()) return Expired(0, 0L, emptyList())

        var bytes = 0L
        val ids = targets.map { it.id }.toSet()
        targets.forEach { ep ->
            bytes += audioStore.audioFile(ep).length()
            audioStore.deleteEpisodeFiles(ep)
        }
        _episodes.value = _episodes.value.map { ep ->
            if (ep.id in ids) {
                ep.copy(
                    status = EpisodeStatus.Discovered,
                    audioFileName = null,
                    srtFileName = null,
                    segmentCount = 0,
                    // 归零而不是留着：下一条音频落盘时会写新的时间。
                    // 留着旧值的话，一旦这条又被下载，删除倒计时会从旧时间继续算
                    downloadedAt = 0L,
                    error = null,
                )
            } else {
                ep
            }
        }
        // 立刻落盘：删过音频文件 —— 晚落盘就会出现「索引说有、磁盘上没有」
        persistNow()

        DiagLog.i(
            "Repo",
            "保留期到期：清掉 ${targets.size} 条（${bytes / 1048576} MB）· " +
                targets.joinToString("、") {
                    "${it.title.take(18)}(${ContentPolicy.ageDays(now, it.downloadedAt)}天)"
                },
        )
        return Expired(targets.size, bytes, targets.map { it.id })
    }

    /**
     * **仅用于真机验证**：把「有音频、没被保护」的条目的 `downloadedAt` 往前推 [days] 天。
     *
     * ## 为什么必须有这么一个钩子
     *
     * 保留期的第一次真清理最早发生在**装上这一版的三天之后**：
     * [migrateRetention] 会把老音频的起算点推到现在，所以装机当天
     * 「三天后自动删除」是**不可能被验证**的，只能干等三天 ——
     * 而一个要等三天才能看到效果的规则，等于没法验证，也没法在出问题时复现。
     *
     * 只改时间戳、**不删任何东西**：删不删由随后的 [expireOldAudio] 按正常判据决定，
     * 所以这次演练走的是与线上**完全相同**的代码路径 —— 不是另一条「测试专用逻辑」
     * （那种东西验不出真问题）。
     *
     * @param keepIds 保护名单（收藏 + 正在播），这些条目的时间戳不动
     * @return 被改动条数
     */
    fun rewindRetentionForTest(days: Long, keepIds: Set<String>): Int {
        val now = System.currentTimeMillis()
        var n = 0
        _episodes.value = _episodes.value.map { ep ->
            if (ep.id !in keepIds && ep.downloadedAt > 0L && audioStore.hasAudio(ep)) {
                n++
                ep.copy(downloadedAt = now - days * DAY_MS)
            } else {
                ep
            }
        }
        // 立刻落盘：保留期演练：用户正盯着看结果
        if (n > 0) persistNow()
        DiagLog.w("Repo", "保留期演练：把 $n 条音频的下载时间戳往前推 $days 天（只改时间，不删文件）")
        return n
    }

    /**
     * 索引裁剪：把「已经没有音频、且发布时间超出保留期」的条目整个剔掉。
     *
     * ## 为什么必须有这一步
     *
     * 加上更新窗口（只补最近 2 天）之后，一条发布超过 2 天的内容**永远不会
     * 被重新下载**。如果它的音频又被保留期清掉了，那么留在索引里就只是一个
     * 「点不动、也下不回来」的死条目 —— 而这种条目会随着天数堆积，
     * 几天之后列表里大部分是灰色的死行，比没有内容更糟。
     *
     * 判据是 `publishedAt` 而不是 `downloadedAt`：这里问的是「这条内容还有没有
     * 必要留在库里」，答案取决于它的**内容时效**。收藏的条目天然被保护，
     * 因为它们盘上有音频。
     *
     * `publishedAt <= 0`（源站没给日期）一律保留：宁可多留一条，
     * 也不要因为解析不出日期就把内容清掉。
     */
    fun purgeStaleIndex(now: Long): Int {
        val before = _episodes.value.size
        val kept = _episodes.value.filter { ep ->
            !ContentPolicy.shouldPurge(
                publishedAt = ep.publishedAt,
                hasAudio = audioStore.hasAudio(ep),
                now = now,
            )
        }
        if (kept.size == before) return 0
        val dropped = _episodes.value.filter { it !in kept }
        dropped.forEach { audioStore.deleteEpisodeFiles(it) }
        _episodes.value = kept
        // 立刻落盘：裁剪删过文件，同上
        persistNow()
        DiagLog.i(
            "Repo",
            "索引裁剪：剔掉 ${dropped.size} 条已过时效且无音频的条目（${before} → ${kept.size} 条）",
        )
        return dropped.size
    }

    /** 清理结果，用于日志与设置页 */
    /**
     * 保留期清理的结果。
     *
     * [ids] 必须带出去：ExoPlayer 手里那份播放队列是**在点开某一条时快照下来的**
     * （见 `PlayerViewModel.playableQueue`），里面会排着这些刚被删掉的文件。
     * 队列不修剪的话，播到那一条就是「播放失败：文件不存在」——
     * 而且是在用户什么都没做的时候突然断掉。调用方拿到 [ids] 后要调
     * [com.example.englishcommute.player.NowPlaying.prunePlaylist]。
     */
    data class Expired(val count: Int, val bytes: Long, val ids: List<String> = emptyList())

    // ------------------------------------------------------------------
    // 拉取 RSS
    // ------------------------------------------------------------------

    /**
     * 拉取全部源并合并进索引。
     *
     * **单源失败不影响其他源** —— 每个源独立 try/catch，失败信息通过 [SourceResult] 带回给 UI。
     * 全部源都失败也不算致命：索引里原有的内容仍然可播。
     */
    suspend fun refreshFeeds(
        onSourceDone: (SourceResult) -> Unit = {},
    ): List<SourceResult> = withContext(Dispatchers.IO) {
        val existing = _episodes.value.associateBy { it.id }
        val merged = _episodes.value.toMutableList()

        // ---- 阶段一：并发拉取（1.2.0 改）--------------------------------
        //
        // 原来是 `for (source in FeedSources.ALL)` 串行跑 11 个源。每个源要付
        // 一次完整的 TCP + TLS 握手，移动网络下 300–800 ms，**串行累加就是
        // 5–9 秒**；只要有一个源命中 20 秒的 connectTimeout，单点就能把整段
        // 拖到 20 秒以上。而这段时间恰好堵在「点了更新之后」的最前面 ——
        // 用户看到的就是点完按钮长时间停在「获取内容列表」。
        //
        // 并发 4 而不是全放开：11 个源里 9 个是同一个 host（feeds.npr.org），
        // 对同一域名开太多并发连接既无收益也不礼貌。上限从 11×T 降到 ⌈11/4⌉×T。
        //
        // ⚠️ **合并必须留到阶段二单线程做。** 这里只负责把 items 取回来 ——
        //    `merged` 是个普通 MutableList，并发改它会丢数据，
        //    而且丢得毫无征兆（表现为「某个源今天没内容」）。
        val gate = Semaphore(FEED_CONCURRENCY)
        val doneCount = java.util.concurrent.atomic.AtomicInteger(0)

        // ── 2.0：内容来源优先走服务器目录 ─────────────────────────────────
        //
        // 服务器（`server/bin/cycle.py`）已经替客户端做完了：拉 11 个源、
        // 按窗口筛掉过期的、转写、翻译。客户端因此**不再需要自己解析 RSS** ——
        // 少 11 次请求、少一套解析器，也少一处「两边结果不一致」的地方。
        //
        // ⚠️ 拿不到目录（没网 / 超时 / 解析失败）时**退回下面原有的 RSS 路径**，
        //    而不是把素材库清空。这条退路不是历史包袱：服务器是单点，
        //    它挂掉的时候 App 仍然要能更新、能播放 —— 这正是 1.0 最稳的地方。
        //
        // 用 `?:` 包住整段而不是 if/else：原有的并发拉取**一行都不用改缩进**，
        // 于是 code review 时看到的 diff 只有这里几行，不会被一片重排淹没。
        val serverEps = serverCatalog.fetchCatalog()
        val fetched: List<Pair<SourceResult, List<Episode>>> = serverEps?.let { list ->
            // 与 RSS 路径同一条口径：超过单条时长上限的不进候选
            //（服务器不知道客户端的这个上限，它的职责是「有什么发布什么」）
            val usable = list.filter {
                it.durationSec == 0 || it.durationSec <= FeedSources.MAX_SINGLE_EPISODE_SEC
            }
            // 报一次进度，让界面那行「3/11」在服务器模式下显示「1/1」
            onSourceDone(SourceResult(SERVER_SOURCE, usable.size))
            listOf(SourceResult(SERVER_SOURCE, usable.size) to usable)
        } ?: FeedSources.ALL.map { source ->
            async {
                gate.withPermit {
                    val items = runCatching {
                        fetchFeed(source)
                            // 超过单条时长上限的直接丢，别把 35 分钟以上的特辑放进候选
                            .filter {
                                it.durationSec == 0 ||
                                    it.durationSec <= FeedSources.MAX_SINGLE_EPISODE_SEC
                            }
                    }
                    val result = items.fold(
                        onSuccess = { SourceResult(source, it.size) },
                        onFailure = { e ->
                            SourceResult(source, 0, reasonOf(e))
                        },
                    )
                    // 每完成一个就报一次，让界面能显示「3/11」而不是干等。
                    // 回调可能在任意线程上被调用 —— 接收方只做一次状态赋值，
                    // 而 StateFlow 的写入本身是线程安全的
                    doneCount.incrementAndGet()
                    onSourceDone(result)
                    result to items.getOrDefault(emptyList())
                }
            }
        }.awaitAll()

        // ---- 阶段二：单线程合并 ------------------------------------------
        //
        // 顺序与串行版完全一致（FeedSources.ALL 的声明顺序），所以
        // 「同一时间戳的两条谁在前」这类稳定排序行为不会因为并发而改变。
        // 服务器不可达时**必须让用户看见**，不能静默降级。
        //
        // 降级本身是对的（素材库照样更新，音频照样能听），但用户那边能观察到的
        // 现象是「新内容都没有字幕」—— 不说的话他会以为 App 坏了。
        // 复用已有的「源问题」卡片：它本来就是干这个的，不必新造一个界面。
        // 只在服务器模式开启时才报 —— 关掉开关的 1.0 模式下本来就没有服务器。
        val serverNote = if (serverEps == null && ServerConfig.ENABLED) {
            // ⚠️ 这句话会**显示给用户**（素材库的「源问题」卡片），所以不许出现
            //    内部行话。第一版写的是「服务器不可达，本轮改用本机源」——
            //    被 copy-audit 拦下了：「本轮」是开发者的时间单位，用户不知道它指什么。
            //    换成说**后果**：他要能看懂「接下来会发生什么」。
            //
            // ⚠️★ 2.1.0 必须改这句话。它原来是「字幕改由手机生成（会慢一些）」——
            //    那是 2.0 的事实（端侧识别兜底），而端侧那条链路这一版整条拆掉了。
            //    留着它就是**一句谎话**：手机不会再生成任何字幕，用户照着
            //    「慢一点但会有」去等，等到的是一条永远没有字幕的内容。
            //    新句子只说事实：字幕要等服务器恢复。
            listOf(SourceResult(SERVER_SOURCE, 0, "服务器连不上，字幕要等它恢复"))
        } else {
            emptyList()
        }
        val results = fetched.map { it.first } + serverNote
        // 用户删掉过的内容不再并回来（1.2.2）。
        //
        // 快照在合并**开始前**取一次：合并是单线程的，中途不会有人改它，
        // 但养成习惯 —— 并发段里读一个可变集合，会出现「前半批过滤了、
        // 后半批没过滤」这种只在特定时序下发生的诡异结果。
        val ignored = _ignored.value
        // 同一期内容的**第二条判据**：音频地址（2.0.3）。
        //
        // 正常情况下 id 就够 —— 两边都由 guid 算出来。但服务端在条目滚出 RSS
        // 窗口之后会**丢掉 guid**（它靠重新拉源反查，滚出去就反查不回来），
        // 于是它对同一期算出另一个 id。后果是素材库里出现「同一期两条」：
        // 一条有音频没字幕、一条有字幕没音频 —— 而后者没下载过，**点下去毫无反应**。
        //
        // 音频地址是这一期内容的天然主键（就是那个 mp3），拿它兜一层，
        // 这种分裂就不会发生。用 `associateBy` 只花一次哈希，
        // 而它在真机上救回的是「用户点不动某一条」这种最难查的现象。
        val byAudioUrl = _episodes.value
            .filter { it.audioUrl.isNotBlank() }
            .associateBy { it.audioUrl }
        for ((_, items) in fetched) {
            for (item in items) {
                if (item.id in ignored) continue
                val old = existing[item.id] ?: byAudioUrl[item.audioUrl]
                if (old == null) {
                    merged.add(item)
                } else {
                    // 按音频地址认出来的那一条：**沿用本地的 id**。
                    // 收藏、播放进度、已下载的音频、已翻好的译文全都挂在它上面 ——
                    // 换 id 等于把这些一起丢掉，而它们才是用户积累下来的东西。
                    val keepId = old.id
                    if (keepId != item.id) {
                        DiagLog.i(
                            "Sync",
                            "同一期按音频地址认出（服务端没给 guid）：${item.title.take(24)}",
                        )
                    }
                    // 保留已有处理状态与落盘文件名，只刷新标题/时长等元信息
                    val i = merged.indexOfFirst { it.id == keepId }
                    if (i >= 0) {
                        merged[i] = item.copy(
                            id = keepId,
                            status = old.status,
                            audioFileName = old.audioFileName,
                            srtFileName = old.srtFileName,
                            segmentCount = old.segmentCount,
                            // ⚠️ 这一行漏掉的后果是**静默的**：每次刷新 RSS
                            //    都会把下载时间抹成 0，保留期永远算不出来，
                            //    「三天后自动删除」就成了一句空话 —— 而且不报任何错。
                            //    加字段时容易只加 status 那一排，这里单独标出来。
                            downloadedAt = old.downloadedAt,
                            error = old.error,
                            // 字幕在服务器上的位置（2.0）。用 `?:` 而不是直接取新的 ——
                            // 从服务器模式掉回 RSS 模式时，新条目的这两项是 null，
                            // 直接覆盖会把「去哪儿取字幕」这件事忘掉。
                            // 单调保留：只在新值存在时更新，绝不清空。
                            remoteSrt = item.remoteSrt ?: old.remoteSrt,
                            remoteZh = item.remoteZh ?: old.remoteZh,
                            // ⚠️ 这一行是**本地字段**，必须沿用旧的。
                            //    漏掉的后果与上面 downloadedAt 那条一模一样：
                            //    每次刷新索引都以为「本地没有字幕」，
                            //    于是把几 KB 的字幕重下一遍 —— 每条每轮一次。
                            //    （`remoteSrtVer` 相反，它来自服务器，用新的。）
                            srtVerOnDisk = old.srtVerOnDisk,
                        )
                    }
                }
            }
        }

        // 裁剪：只留最近 MAX_INDEX_ENTRIES 条，被裁掉的连同文件一起清理，
        // 不然索引会无限膨胀、缓存也压不住
        val sorted = merged.sortedByDescending { it.publishedAt }
        val kept = sorted.take(MAX_INDEX_ENTRIES)
        val dropped = sorted.drop(MAX_INDEX_ENTRIES)
        dropped.forEach { audioStore.deleteEpisodeFiles(it) }

        // 源被换掉之后，索引里会留着它的历史条目（状态还是 Failed，点也没用）。
        // 这里顺手清掉，连同已下载的音频/字幕一起删，避免界面上一堆死条目。
        val activeSourceIds = FeedSources.ALL.map { it.id }.toSet()
        val orphans = kept.filter { it.sourceId !in activeSourceIds }
        if (orphans.isNotEmpty()) {
            orphans.forEach { audioStore.deleteEpisodeFiles(it) }
        }
        val cleaned = kept.filter { it.sourceId in activeSourceIds }

        _episodes.value = cleaned
        // 立刻落盘：一轮刷新收尾（且删过孤儿文件）
        persistNow()
        // 时效裁剪放在合并之后、而不是替代按条数裁剪：两者管的不是同一件事 ——
        // 上面那个管「别让索引无限长」，这里管「别留下永远下不回来的死条目」。
        // 见 purgeStaleIndex 的注释（更新窗口会让超期内容的音频再也回不来）
        purgeStaleIndex(System.currentTimeMillis())
        results
    }

    /**
     * 拉一个 feed，**边下边解析，够条数就断流**。
     *
     * ## 为什么返回 `List<Episode>` 而不是 `ByteArray`
     *
     * 早先的写法是「先 `body.bytes()` 把整个 feed 读进内存，再喂给解析器」。
     * 那等于**为了每条内容 1–2 条，把整个 feed 全下下来**。实测各源体积：
     *
     * | 源 | feed 体积 | 取几条 | 有效载荷占比 |
     * |---|---|---|---|
     * | TED Talks Daily | 19.7 MB / 2979 条 | 1 | **0.03%** |
     * | NPR Politics | 7.5 MB / 1750 条 | 1 | 0.06% |
     * | Consider This | 7.0 MB / 1750 条 | 1 | 0.06% |
     *
     * 4 个源就是 28 MB XML；再扩到 13 个源就是 54 MB —— 一次「一键更新」
     * 光拉列表就要下几十 MB，还全是我们不要的历史条目。这不是优化项，是必须修的账。
     *
     * ## 断流的坑：不能靠 `close()`
     *
     * OkHttp 的 `ResponseBody.close()` 对没读完的定长响应会**先把剩余字节全部读掉再丢弃**
     * （`FixedLengthSource.close()` → `discard()`），磁盘和带宽一样不省。
     * 所以这里显式 `call.cancel()` —— 直接掐掉 socket，close 随后触发的 discard
     * 会立刻失败并被 OkHttp 自己吞掉（它就是这么设计的）。
     *
     * ## 为什么敢按「读到 limit 条就停」
     *
     * 因为这批源**全部是倒序**（最新条目在最前面）。这是实测结论，不是假设：
     * 对 13 个源逐个发请求，解析每个 feed 的前两个 `pubDate`，全部满足
     * 「第 1 条 ≥ 第 2 条」。⚠️ **新增源时必须重新确认这一点** —— 正序的 feed
     * 会被这条策略截在最旧的一端，表现是「这个源永远拿不到新内容」，
     * 而且 HTTP 状态码完全正常，很难看出来。
     *
     * 为此在主循环里放了一道兜底检查：解析出的条目如果整体偏旧，打 W 级日志。
     */
    private fun fetchFeed(source: FeedSource): List<Episode> {
        val url = source.url
        val host = url.toHttpUrlOrNull()?.host ?: url
        val started = System.currentTimeMillis()

        // ⚠️ DNS 解析**只在失败路径上做**（1.2.0 改）。
        //
        // 它的价值是排障：污染的特征就是「解析出来的 IP 跟真实 IP 完全不是
        // 一个服务商」，异常信息里不带这一层就看不出来。这个价值要保留。
        //
        // 但原来的写法是在**每个源**请求之前无条件解析一次，而 11 个源里
        // 只有 2 个不同 host（feeds.npr.org × 9、feeds.simplecast.com × 2）——
        // 等于付了 9 次完全重复的阻塞解析。更糟的是 Android 的 AddressCache
        // TTL 只有 2 秒，串行跑完足以让缓存过期、真的重新解析。
        //
        // 成功时 OkHttp 内部自己会解析（它有连接池和自己的缓存），
        // 我们不需要抢在它前面再做一遍。

        val call = client.newCall(
            Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/rss+xml, application/xml, text/xml, */*")
                .build()
        )

        // 建连和读 body 纳入同一个 try：被拦的场景（Connection reset）
        // 实测可能发生在任一阶段，分开就只有一半能带上诊断信息
        try {
            return call.execute().use { r ->
                if (!r.isSuccessful) {
                    throw IOException("HTTP ${r.code} ${r.message}")
                }
                val body = r.body ?: throw IOException("响应体为空")
                val counted = CountingStream(body.byteStream())
                // 解析器顺手把频道封面塞进了每一条的 artworkUrl（条目自带优先）
                val items = parser.parse(counted, source).items

                // 先掐连接再退出 use：顺序反了就等于没优化（见上）
                call.cancel()

                DiagLog.i(
                    "Feed",
                    "${source.name} · 读入 ${counted.count / 1024} KB / 共 " +
                        "${body.contentLength().let { if (it > 0) "${it / 1024} KB" else "未知" }} · " +
                        "解析 ${items.size} 条 · ${System.currentTimeMillis() - started}ms",
                )

                if (items.isNotEmpty()) warnIfStale(source, items)
                items
            }
        } catch (e: IOException) {
            val ms = System.currentTimeMillis() - started
            // 到这里才解析 DNS：这一条路径上多花几十毫秒无所谓，
            // 而「域名解析到了哪个 IP」恰恰是判断有没有被污染/劫持的关键证据
            val ips = runCatching {
                InetAddress.getAllByName(host).joinToString(", ") { it.hostAddress ?: "?" }
            }.getOrElse { "解析失败(${it::class.simpleName})" }
            throw IOException(
                "${e::class.simpleName}: ${e.message ?: "无详细信息"} · $host → $ips · ${ms}ms"
            )
        }
    }

    /**
     * 兜底：解析出来的条目如果整体都很旧，多半是「feed 不是倒序」——
     * 流式截断把最老的那几条留下来了，而那几条本来就该被丢掉。
     *
     * 这种情况 HTTP 状态码、条目数、音频链接全部正常，只有日期是错的，
     * 所以必须显式报出来，否则只会表现成「这个源好像一直不更新」。
     */
    private fun warnIfStale(source: FeedSource, items: List<Episode>) {
        val newest = items.maxOfOrNull { it.publishedAt } ?: return
        if (newest <= 0L) return
        val days = (System.currentTimeMillis() - newest) / 86_400_000L
        if (days > STALE_FEED_DAYS) {
            DiagLog.w(
                "Feed",
                "${source.name}：最新条目已是 ${days} 天前 —— 要么源停更，要么 feed 不是倒序" +
                    "（后者会让截断策略拿到最旧的几条，见 fetchFeed 注释）",
            )
        }
    }

    /**
     * 统计实际从网络读到的字节数。
     *
     * 流式解析下没有「Content-Length」可参考（我们主动断流，读到的远小于声明值），
     * 不数就完全看不到优化是否生效 —— 而这个数字正是判断「要不要继续加源」的依据。
     * 只转发不改写，`XmlPullParser` 那边无感。
     */
    private class CountingStream(private val delegate: InputStream) : InputStream() {
        var count: Long = 0L
            private set

        override fun read(): Int = delegate.read().also { if (it >= 0) count++ }

        override fun read(b: ByteArray, off: Int, len: Int): Int =
            delegate.read(b, off, len).also { if (it > 0) count += it }

        override fun available(): Int = delegate.available()

        override fun close() = delegate.close()
    }

    // ------------------------------------------------------------------
    // 下载
    // ------------------------------------------------------------------

    /**
     * 下载一期音频，并把状态推到 [EpisodeStatus.Downloaded]。
     * 失败时条目落到 [EpisodeStatus.Failed]，UI 上可单条重试。
     */
    suspend fun downloadEpisode(
        episode: Episode,
        onProgress: (AudioDownloadProgress) -> Unit = {},
    ): Episode {
        update(episode.copy(status = EpisodeStatus.Downloading, error = null))
        return try {
            val file = audioStore.download(episode, onProgress)
            val done = episode.copy(
                status = EpisodeStatus.Downloaded,
                audioFileName = file.name,
                // 保留期从这里开始算。**只在真正落盘之后写** ——
                // 放在下载前写的话，一次失败的中途下载也会开始跑三天的倒计时
                downloadedAt = System.currentTimeMillis(),
                error = null,
            )
            update(done)
            done
        } catch (e: Exception) {
            // ⚠️ 同理：`e.message` 会直接显示在条目的错误位上，而它是
            //    「ENOSPC (No space left on device)」这类英文串。
            //    用户能做的只有「再试一次」，所以就说这一句（详情在日志里）。
            val failed = episode.copy(
                status = EpisodeStatus.Failed,
                error = "这条没下载成功，点它再试一次",
            )
            update(failed)
            failed
        }
    }

    // ------------------------------------------------------------------
    // 缓存
    // ------------------------------------------------------------------

    /**
     * 播放过的条目不会被淘汰；越界时按「最久没听」删。
     *
     * ## ⚠️ 收藏必须在保护名单里（1.2.0 修）
     *
     * 原来的名单只有 `isBusy` 和 `Discovered` 两类 —— 而后者根本没有音频文件，
     * 保护它是个空操作。于是缓存一旦超过 1 GB，**用户收藏的音频会被 LRU 删掉**。
     *
     * 这与保留期那条路径是矛盾的：`SyncEngine.retentionKeepIds()` 一直把收藏
     * 算在内，`ContentPolicy` 里也白纸黑字写着「收藏 = 用户明确说过要留」。
     * 两条删除路径对同一个问题给了相反的答案，而先触发哪条取决于
     * 用户攒了多少缓存 —— 这正是它一直没被发现的原因。
     */
    fun enforceCacheLimit(favoriteIds: Set<String> = emptySet()): Int {
        val protected = _episodes.value
            .filter { it.status.isBusy || it.status == EpisodeStatus.Discovered }
            .map { it.id }
            .toMutableSet()
        // 收藏由调用方传进来（仓库不认识 Favorites，那是设置域的东西）。
        // 它是整个删除逻辑里唯一一条「用户意图」依据，两条删除路径必须一致
        protected += favoriteIds
        return audioStore.enforceCacheLimit(protected)
    }

    /**
     * 这一条的字幕还缺不缺东西。
     *
     * ## 判据：「服务器有的、本地还没有」
     *
     * | 情况 | 缺什么 |
     * |---|---|
     * | 连英文字幕都没有 | SRT |
     * | 有英文、服务器也发布了中文、本地没有中文 | 中文对照 |
     *
     * ## 为什么必须有第二个条件（2.1.0 的关键一笔）
     *
     * 服务器的 `zh` **比 `srt` 晚一轮**（它先转写、下一轮才翻译，见服务端
     * `cycle.py` 的 `translate_pending`）。而客户端原来是「有 srt 就不再取」，
     * 于是**在服务器翻译完成之前点开过这一期的用户，永远只有英文**。
     *
     * 2.1.0 之前这条空洞被端侧翻译兜住了（播放页发现没译文就自己翻）——
     * 而端侧那条链路这一版拆掉了，**兜底不存在了**。
     * 少了下面这个 `remoteZh` 分支，表现是「中文对照时有时无」，
     * 而且从现象上看不出任何规律：它取决于用户当时点得快不快。
     *
     * ⚠️ `remoteZh == null` 时**不算缺**。服务器还没翻译的东西，本地取不到，
     *    把它算成「待补」会让待补数永远归不了零 —— 界面上一行
     *    「N 条字幕待补」挂了三天不变，用户只会以为坏了。
     *    等服务器翻译好，下一轮 `refreshFeeds` 会把 `remoteZh` 带回来，
     *    那时它才变成真正的待办。
     *
     * 判据是**文件**而不是状态位：状态位会撒谎（进程被杀时会停在 `Downloading`），
     * 文件不会。用它当「回到前台要不要起一轮」的开关，崩过一次之后
     * **自动就恢复了** —— 不需要用户做任何事。
     *
     * 只做文件存在性检查、不解析内容，便宜到可以每次回到前台都调一遍。
     * 判据依赖 [audioStore] 的目录布局，所以它是仓库的知识，不该散到界面里。
     */
    fun needsSubtitles(episode: Episode): Boolean {
        // ★ 2.2.0：**去掉了 `if (!audioStore.hasAudio(episode)) return false`。**
        //
        //   原来的前提是「字幕只在音频下完之后才取」（2.1.0 之前流水线的写法）。
        //   而 2.2.0 有两条改动把这个前提推翻了：
        //     ① 音频改成**边下边播** —— 用户可以在音频还没落盘时点开任何一条，
        //        那时他需要字幕（播放页要铺整篇文稿）；
        //     ② 取字幕是**几十 KB 的 HTTP**，跟音频的几 MB～几十 MB 完全不是一个量级，
        //        没有理由让它排在音频后面干等。
        //   留着那句的后果很具体：点开一条没下载的内容 → 音频流式播起来了，
        //   而播放页**永远没有文稿**（判据说不缺，谁都不会去取）。
        //
        //   ⚠️ 顺序仍然不能动：`remoteSrtVer` 那条**必须排在最前**（见下）。
        if (episode.remoteSrtVer > episode.srtVerOnDisk) return true
        if (!audioStore.hasSrt(episode)) return true
        // ★★ 2.2.1：**盘上有字幕、但模型里没记下来** 也算「没到位」。
        //
        //   为什么必须补这一条（真机上就是这么挂的）：
        //   字幕有**两条**写文件的路径 —— 同步引擎（批处理）和**播放页**（用户点开时
        //   当场取一次）。两条都把 SRT 写到盘上，但「模型里的 `srtFileName`」不会自己变，
        //   而列表那一行判的正是那个字段。
        //
        //   于是出现一个**两边都不管**的死角：
        //     · 这里（队列判据）看的是**文件** → 文件在 → 「不缺」→ 没人去处理它；
        //     · 列表看的是**模型** → null → 一直显示「字幕加载中」。
        //   点那个「加载字幕」按钮也没用（走到底还是「不缺」）。
        //   用户看到的是「播到页里中英文都在、列表却写着字幕加载中」。
        //
        //   把这两条并进同一个判据，队列才会把这种条目捡起来，
        //   在 `fetchSubtitlesFor` 里走一次**只对齐、不下载**的收尾
        //   （见 `syncSubtitleState`）。收敛很快：对齐一次就永久为假。
        //
        //   ⚠️ 判据只取 `srtFileName == null`，**不要**把 `segmentCount <= 0` 也写进来：
        //      万一某个 srt 一行 `-->` 都数不出来，那条判据永远为真、
        //      这一条就每次都进队列 —— 于是「N 条字幕待补」永远归不了零，
        //      而界面上一行挂着三天不变的计数只会让人以为坏了。
        if (episode.srtFileName == null) return true
        return episode.remoteZh != null && !audioStore.hasTranslation(episode)
    }

    /**
     * 把一条内容的**字幕状态**按磁盘上的事实对齐到模型里。
     *
     * ## 为什么必须有这么一个函数，而且只能有这一个
     *
     * 「SRT 文件在盘上」和「模型里记着它」是**两个不同的事实**，而界面读的是后者。
     * 写文件的路径有两条（同步引擎 / 播放页当场取），如果让**每条路径各自**
     * 去更新模型，就一定会有一条被漏掉 —— 2.2.0 就是这样：播放页那条只写了文件，
     * 于是模型永远停在 `srtFileName == null`，列表一直写「字幕加载中」，
     * 而播放页里中英文都在（用户的原话：「都显示转写未加载，但实际上我已经拿到字幕了」）。
     *
     * 所以更新模型这件事**只在这里做一处**，两条路径都调它。
     *
     * ## 判据一律落在文件上
     *
     * 不看状态位、不看 `remoteSrt`（那是服务器的意图，不是本地的现状）。
     * 幂等 —— 重复调用没有副作用，代价只有一两次 `isFile` 和（必要时）数一遍时间轴。
     *
     * ## ⚠️ `markAsCurrentServerVersion` 什么时候才敢传 true
     *
     * `srtVerOnDisk` 的语义是「本地这份对应的就是服务器现在这一版」。
     * 所以它**只**能在「文件确实是刚刚从服务器那份写下来的」时候更新：
     *   · 播放页当场取成功之后 → 是（传 true）
     *   · 同步引擎发现「都齐了」顺手对齐 → **不是**。那种情况下盘上那份可能是旧版
     *     （服务器换过一版、而这次重下还没成功），盖章成最新会让它**永远不会被重取** ——
     *     这是静默的、且不可恢复。
     */
    fun syncSubtitleState(id: String, markAsCurrentServerVersion: Boolean = false): Boolean {
        val ep = find(id) ?: return false
        val hasSrt = audioStore.hasSrt(ep)
        val name = if (hasSrt) (ep.srtFileName ?: "${ep.id}.srt") else null
        // 句数只在「还没记下来」时去数（数一遍要读整个 srt，别每轮都做）
        val cues = if (hasSrt) {
            if (ep.segmentCount > 0) ep.segmentCount else countSrtCues(ep)
        } else {
            0
        }
        val ver = when {
            !hasSrt -> ep.srtVerOnDisk
            markAsCurrentServerVersion -> ep.remoteSrtVer
            else -> ep.srtVerOnDisk
        }
        if (ep.srtFileName == name && ep.segmentCount == cues && ep.srtVerOnDisk == ver) {
            return false
        }
        update(ep.copy(srtFileName = name, segmentCount = cues, srtVerOnDisk = ver))
        return true
    }

    /**
     * 有几条「有音频、字幕没齐」。界面与「要不要起一轮补字幕」都用它。
     *
     * 2.1.0 起它取代了原来**三个**数（`pendingTranscribeCount` /
     * `autoTranscribableCount` / `pendingTranslate`）。那三个的分工全部来自
     * 端侧的限制：转写比实时慢 2–3 倍，所以「能不能自动做」要看你给多少预算，
     * 而「翻译」干脆是另一条独立的链路。
     *
     * 现在这三件事合成了一件：**向服务器取回这一期还缺的那部分字幕**。
     * 一个数就够了，而且它不会再出现「有 N 条待办但一条都做不成」那种
     * 自相矛盾的状态（那种状态当年让回前台的动作每次都白起一次前台服务）。
     */
    fun pendingSubtitleCount(): Int = _episodes.value.count { needsSubtitles(it) }

    fun cacheBytes(): Long = audioStore.cacheBytes()

    /**
     * 某一期的**本地音频文件**；不在盘上时返回 null（3.0.4）。
     *
     * ## 为什么由这里回答，而不是界面自己去拼路径
     *
     * 「音频目录在哪」是 [AudioStore] 的事（`getExternalFilesDir/episodes/audio`），
     * 界面不该知道。更要紧的是**判据**：
     *
     * ⚠️ 判的是 `AudioStore.hasAudio`（文件真的在、长度 > 0），
     *    **不是** `Episode.audioFileName != null`。后者是**索引里的记录**，
     *    而保留期清理可能已经删了文件、索引还没重新发布 ——
     *    「记录说在」和「文件在盘上」是两件事。
     *    这个项目在字幕上已经栽过一次同型的跟头（看 `Episode.isPlayable` 那段），
     *    这里不重犯。
     *
     * ⚠️ 它只服务一个场景：复习卡背面的「听这一句」。那一期音频过了保留期
     *    就会被清掉，所以**这个方法的返回值会随时间变** —— 界面必须在
     *    每次进入卡片时重新问，不能缓存。
     */
    fun localAudioFile(episodeId: String): File? {
        val ep = episodes.value.firstOrNull { it.id == episodeId } ?: return null
        return if (audioStore.hasAudio(ep)) audioStore.audioFile(ep) else null
    }

    /** 重排：把当前索引同步回内存（外部改动音频文件后调用） */
    fun reload() = load()

    /**
     * 删除指定条目的本地文件，并把状态退回「待下载」。
     *
     * 与 [resetAllToDiscovered] 的差别在于它是**挑着删**：设置页的「清理已听完」
     * 只针对听过的那几条，不能顺手把刚更新下来还没听的也干掉。
     * 索引里保留条目本身 —— 用户想重听时下次同步会自动重新下载，不需要重新拉 RSS 才有条目。
     */
    fun deleteLocalFiles(targets: List<Episode>) {
        if (targets.isEmpty()) return
        val ids = targets.map { it.id }.toSet()
        targets.forEach { audioStore.deleteEpisodeFiles(it) }
        _episodes.value = _episodes.value.map { ep ->
            if (ep.id in ids) {
                ep.copy(
                    status = EpisodeStatus.Discovered,
                    audioFileName = null,
                    srtFileName = null,
                    segmentCount = 0,
                    downloadedAt = 0L,
                    error = null,
                )
            } else {
                ep
            }
        }
        // 立刻落盘：删过本地文件，同上
        persistNow()
    }

    /**
     * 删掉一条内容：本地文件、索引条目、删除记录，一次做完（1.2.2）。
     *
     * 长按菜单的「删除」走这里。三件事必须一起做：
     *
     * | 少了哪一步 | 会怎样 |
     * |---|---|
     * | 不删文件 | 音频还占着空间，「删除」名不副实 |
     * | 不出索引 | 条目还在列表里，只是变成「待下载」，用户以为没删掉 |
     * | 不记 [ignored] | **下次刷新原样回来**，还会重新下载一遍 |
     *
     * 条目本身从索引移除而不是标记成某种状态：这是用户的明确意图，
     * 留一个「已删除但还在列表里」的中间态只是自找解释成本。
     */
    fun removeEpisode(episode: Episode) {
        audioStore.deleteEpisodeFiles(episode)
        _episodes.value = _episodes.value.filterNot { it.id == episode.id }
        _ignored.value = _ignored.value + episode.id
        persistNow()
        persistIgnored()
        // 留一条日志：用户报「删了怎么又回来了」时，先看这里有没有 ——
        // 有 = 记录写进去了但刷新没跳过（代码问题）；
        // 没有 = 那次删除根本没走到这个入口（界面问题）
        DiagLog.i("Repo", "已删除：${episode.title.take(24)} · 不再出现在后续更新里")
    }

    /** 这条是否被用户删过（刷新时会跳过） */
    fun isIgnored(episodeId: String): Boolean = episodeId in _ignored.value

    private fun loadIgnored() {
        if (!ignoredFile.isFile) return
        runCatching {
            val arr = JSONArray(ignoredFile.readText(Charsets.UTF_8))
            _ignored.value = buildSet {
                for (i in 0 until arr.length()) {
                    arr.optString(i).takeIf { it.isNotEmpty() }?.let { add(it) }
                }
            }
        }.onFailure { DiagLog.w("Repo", "删除记录读取失败（按没有删过处理）：${it.message}") }
    }

    /** tmp + rename，与索引同一套原子写：被杀不会留半截 JSON */
    private fun persistIgnored() {
        runCatching {
            val tmp = File(ignoredFile.parentFile, ignoredFile.name + ".tmp")
            tmp.writeText(JSONArray(_ignored.value.toList()).toString(), Charsets.UTF_8)
            if (!tmp.renameTo(ignoredFile)) {
                ignoredFile.delete()
                tmp.renameTo(ignoredFile)
            }
        }.onFailure { DiagLog.w("Repo", "删除记录落盘失败：${it.message}") }
    }

    /**
     * 清空文件后把索引一起归零。
     *
     * 必须成对做 —— 否则索引里还留着 `audioFileName`，界面会显示「待转写」，
     * 但点转写时文件根本不存在，用户只会看到一个莫名其妙的失败。
     */
    fun resetAllToDiscovered() {
        _episodes.value = _episodes.value.map {
            it.copy(
                status = EpisodeStatus.Discovered,
                audioFileName = null,
                srtFileName = null,
                segmentCount = 0,
                downloadedAt = 0L,
                error = null,
            )
        }
        // 立刻落盘：清空文件后把索引归零，同上
        persistNow()
    }

    companion object {
        private const val USER_AGENT = "EnglishCommute/0.1 (Android)"

        const val DAY_MS = 86_400_000L

        /** 索引保留条数上限。约 8 天的通勤量，再多也没人往回翻 */
        const val MAX_INDEX_ENTRIES = 40

        /**
         * 同时拉取几个 RSS 源（1.2.0）。
         *
         * 不取 11（全放开）的理由：11 个源里有 9 个挂在同一个 host
         * （`feeds.npr.org`），对单一域名开十来条并发连接既拿不到额外收益
         * （对端会排队），也不是一个客户端该有的样子。
         *
         * 4 是个保守但足够的值：最慢的那一档从「11 × 单源耗时」降到
         * 「3 × 单源耗时」，已经把用户能感知的那段空白消掉了。
         */
        const val FEED_CONCURRENCY = 4

        /**
         * 索引落盘的防抖窗口（1.2.0）。
         *
         * 500 ms 的取舍：一轮同步里状态位变化是密集的（一条内容 4–5 次），
         * 这个窗口足以把它们合成一次写；而它又远短于「用户可能在这期间
         * 划掉 App」的时间尺度 —— 真被杀了最多丢掉最后半秒的状态，
         * 而状态本身有 `reconcileInterruptedState` 在下次启动时对账兜底。
         */
        const val PERSIST_DEBOUNCE_MS = 500L

        /**
         * 音频保留天数。**三天后自动清掉没被收藏的**。
         *
         * ⚠️ 2.1.9 起这个数字**不再**与下载策略配对（原来和 [DOWNLOAD_WINDOW_DAYS]
         * 是「窗口必须短于保留期」的一对）。现在下载走
         * [ContentPolicy.selectPrefetch]（懒加载），不再有「窗口」这个概念，
         * 于是那条不变量自然消失 —— 不会再出现「更新下来一条、下一轮就被清掉」，
         * 因为已经不存在「按发布时间批量补下」这个动作了。
         *
         * 它现在的搭档是服务端的**音频保留期**（`cycle.py` 的 `RETENTION_DAYS = 7`）：
         * 服务端留得比这里久，所以「本地删了之后想去服务端重下」这条路一直通。
         */
        const val RETAIN_DAYS = 3L

        /**
         * 预取条数：**「接下来几条」**（2.1.9 懒加载策略的核心参数）。
         *
         * ## 为什么是 3
         *
         * 它决定「听完了手上这条，还有没有下一条可听」。
         * 单集多在 5–15 分钟，3 条 ≈ 20–45 分钟 —— 比一次通勤长一点，
         * 而且**没听完的条目不会被删**（保留期按 `downloadedAt` 算 3 天），
         * 所以实际可用缓冲比 3 条大。
         *
         * ## 它与流量直接挂钩
         *
         * 每天新增素材约 320 MB（11 个源）。全量预下载 = 320 MB/天；
         * 预取 3 条 ≈ 30 MB/天，**差一个数量级**。
         * 而服务端 512 GB/月 的流量包里，出流量才是被计费的那部分。
         */
        const val PREFETCH_COUNT = 3

        /**
         * 更新窗口：只补最近这么多天发布的内容。
         *
         * ## ⚠️ 2.1.9 起**不再用于下载**，只作为一条兜底上限
         *
         * 下载改走 [ContentPolicy.selectPrefetch]（懒加载，见那里的说明）。
         * 这个常量留着的唯一用处是 [ContentPolicy.shouldPurge] 之外的历史语义 ——
         * 别按它再写「按天批量补下」的逻辑：那正是 2.1.9 要修掉的东西
         * （每天 320 MB 里绝大多数没人听）。
         *
         * 当初它解决的是「很多天没打开、一打开点更新 = 一次下几十条」。
         * 懒加载把这个问题解决得更彻底：**不是下得少一点，而是只下会听的**。
         */
        const val DOWNLOAD_WINDOW_DAYS = 2L

        /**
         * 最新条目超过这个天数就打 W 级日志。
         *
         * 14 天而不是 7 天：周更节目（Throughline 每周四、Code Switch 隔几天）
         * 正常间隔就有 7 天，卡在 7 会天天误报，久了就没人看这条日志了。
         */
        private const val STALE_FEED_DAYS = 14L
    }
}


/**
 * 把拉取失败的原因说成人话。
 *
 * 这些字符串会**显示在界面上**（素材库的「没连上的来源」那张卡片），
 * 所以不能直接把 `e.message` 甩出去 —— 它长这样：
 * `Unable to resolve host "feeds.simplecast.com": No address associated with hostname`。
 * 用户看到它的唯一感想是「出问题了」，而看不出**是哪种问题、要不要做什么**。
 *
 * 判据是「用户能不能据此决定下一步」：
 *   · 网络不通 / 超时 → 换个网络或等一会儿，**别的什么都不用做**（下一轮会重试）
 *   · 其它            → 说不清就别编，给一句中性的话
 *
 * ⚠️ 分类必须**看类型**，不能看 message 里有没有某个词 ——
 *    文案会随 ROM 与网络栈变，而类型不会。
 * ⚠️ 详情没有丢：`fetchFeed` 的 span 里记着原始异常（`span.fail`），
 *    排查时去日志里看，而不是让用户替你念英文。
 * ⚠️ 判断顺序不能反：`UnknownHostException` 与 `ConnectException` 都是
 *    `IOException` 的子类，把 `is IOException` 写在前面会把它们全吃掉。
 */
private fun reasonOf(e: Throwable): String = when (e) {
    is UnknownHostException -> "连不上（网络不通）"
    is SocketTimeoutException -> "连不上（超时）"
    is ConnectException -> "连不上"
    is IOException -> "读取出错"
    else -> "读取失败"
}
