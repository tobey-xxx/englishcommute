package com.example.englishcommute

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.example.englishcommute.checkin.CheckInStore
import com.example.englishcommute.checkin.ListeningClock
import com.example.englishcommute.content.ArtworkStore
import com.example.englishcommute.content.ContentRepository
import com.example.englishcommute.diag.CrashGuard
import com.example.englishcommute.diag.DeviceInfo
import com.example.englishcommute.diag.DiagLog
import com.example.englishcommute.diag.span
import com.example.englishcommute.player.NowPlaying
import com.example.englishcommute.settings.AppSettings
import com.example.englishcommute.settings.Favorites
import com.example.englishcommute.settings.PlaybackPositions
import com.example.englishcommute.setup.OnDeviceModelCleanup
import com.example.englishcommute.account.AccountStore
import com.example.englishcommute.net.ApiClient
import com.example.englishcommute.notify.DailyReminder
import com.example.englishcommute.sync.CheckInSync
import com.example.englishcommute.sync.WordBookSync
import com.example.englishcommute.sync.SubtitleScheduler
import com.example.englishcommute.sync.ContentSyncService
import com.example.englishcommute.sync.RetiredWork
import com.example.englishcommute.sync.SyncEngine
import java.io.File
import com.example.englishcommute.update.Updater
import com.example.englishcommute.word.PronBackfill
import com.example.englishcommute.word.PronDownloader
import com.example.englishcommute.word.PronPlayer
import com.example.englishcommute.word.SentencePlayer
import com.example.englishcommute.word.VocabAddSync
import com.example.englishcommute.word.VocabTableSync
import com.example.englishcommute.word.WordStore
import com.example.englishcommute.word.WordTableLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Date
import java.util.Locale

/**
 * 应用级容器。
 *
 * 不引入 DI 框架：这个 App 只有少数几个需要共享的单例
 * （内容仓库、同步引擎、设置、播放记录、收藏），手写一个容器比多一层注解处理更省事，
 * 也不会因为注解处理器让构建变慢。
 *
 * **仓库与同步引擎必须是全应用唯一的实例** —— 它们各自持有 [kotlinx.coroutines.flow.StateFlow]
 * 作为真相来源，如果界面和前台服务各建一份，会出现「服务在下载、界面显示空闲」这种撕裂状态。
 *
 * 同理，[playbackPositions] 与 [favorites] 也必须是唯一实例：播放页在写、素材库在读，
 * 两份实例意味着列表上的进度条永远停在进入播放页之前的值。
 *
 * ## 2.1.0：这个容器少了五个成员
 *
 * 拆掉端侧识别 / 翻译之后，[asrEngine]、[ModelProvisioner]、[TranslateManager]、
 * [TranslateModelInstaller]、[TranscribeCheckpointStore] 全部消失 —— 而它们
 * 当年每一个都带着一整套需要注意的东西（唯一实例、声明顺序、延迟释放、
 * 活跃计数保护…）。参考备份：`EnglishCommute-2.0.4-快照/`。
 */
class AppContainer(context: Context) {
    val repository: ContentRepository = ContentRepository(context)
    val settings: AppSettings = AppSettings(context)
    val playbackPositions: PlaybackPositions = PlaybackPositions(context)
    val favorites: Favorites = Favorites(context)

    /**
     * 封面图。必须是唯一实例 —— 它内部持有一份解码后的 [android.graphics.Bitmap]
     * 内存缓存，界面和通知栏各建一份就等于把同一批图解码两遍、占两份内存。
     *
     * 声明在 [syncEngine] **之前**：同步时要顺带补齐缺失的封面，
     * 而 Kotlin 的属性按声明顺序初始化 —— 顺序反了会拿到一个还没构造好的 null。
     */
    val artwork: ArtworkStore = ArtworkStore(context)

    /**
     * 打卡记录。必须是唯一实例 —— 界面订阅它的 StateFlow 显示今日进度，
     * 而写入方是挂在播放器上的计时器。两份实例意味着「听了 40 分钟、
     * 界面上还是 3 分钟」，而那正是打卡功能唯一的可见产出。
     */
    val checkIn: CheckInStore = CheckInStore(context)

    /**
     * 账号（4.0.0）。**未登录时它什么也不做** —— 不登录照样能听能打卡，
     * 账号只多给「多设备一致」这一件事。
     */
    val account: AccountStore = AccountStore(context)

    /** `/api/` 的薄封装。凭证只在它内部被拼进请求头，不进日志。 */
    val api: ApiClient = ApiClient()

    /**
     * 打卡同步。它需要 [checkIn] 与 [account]，所以**必须声明在两者之后**
     * （Kotlin 的属性按声明顺序初始化，反过来写会直接编译失败 —— 这是好事）。
     *
     * ⚠️ 它自带一个与进程同寿命的作用域：同步的触发点全在热路径上
     *    （每 5 秒一次的计时回调、每次回到前台），不能依赖任何界面的生命周期。
     */
    /**
     * 生词本同步（4.1.0）。
     *
     * ⚠️ 用 `by lazy`：容器里 `wordStore` 声明在**后面**，而 Kotlin 的属性
     *    按声明顺序初始化 —— 直接在这里构造会拿到一个还没初始化的实例。
     */
    val wordBookSync: WordBookSync by lazy {
        WordBookSync(
            words = wordStore,
            account = account,
            api = api,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        )
    }

    val checkInSync: CheckInSync = CheckInSync(
        checkIn = checkIn,
        account = account,
        api = api,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    )

    /**
     * 词表增量（服务端下发的「内置表里还没有的词」）。
     *
     * ⚠️ **必须声明在 [wordTable] 之前** —— 它是 loader 的构造参数。
     *    Kotlin 的属性按声明顺序初始化，反过来写会直接编译失败
     *    （`Variable 'vocabAddSync' must be initialized`）—— 这是好事，
     *    它不会拖到运行时才炸。
     *
     * 存的目录和生词本同一个（`filesDir/words/`）：两者都是「用户攒下来的、
     * 不能被清理缓存带走」的东西。理由见 [VocabAddSync] 的类注释。
     */
    val vocabAddSync: VocabAddSync = VocabAddSync(File(context.filesDir, "words"))

    /**
     * 词表**整包**（3.3.0）：用户在设置页点「更新词库」时下的那一份。
     *
     * 和增量放在同一个目录下（`filesDir/words/`），理由同上；
     * 它自己再建一个 `table/<version>/` 子目录，所以两者不会打架。
     *
     * ⚠️ 也**必须声明在 [wordTable] 之前**（它是 loader 的第二个构造参数）。
     */
    val vocabTableSync: VocabTableSync =
        VocabTableSync(File(context.filesDir, "words"))

    /**
     * 词表（内置 assets **或**用户更新过的整包，2 MB 级别）。
     *
     * ⚠️ **懒加载**，不在这里构造 —— 容器是 `Application.onCreate` 里同步建的，
     * 而词表解析要几十到几百毫秒。见 [WordTableLoader] 的类注释。
     */
    val wordTable: WordTableLoader =
        WordTableLoader(context, vocabAddSync, vocabTableSync)


    /**
     * 同步引擎。[playbackPositions] 是 2.1.9 加的 —— 懒加载要知道「哪几条已听完」，
     * 那是「接下来 N 条」这个预取窗口往前滑的唯一依据（见 ContentPolicy.selectPrefetch）。
     */
    val syncEngine: SyncEngine =
        SyncEngine(
            context, repository, artwork, favorites, playbackPositions,
            // 3.0.1：词表增量。同步收尾时会拉一次，拉到新版本就顺手让词表缓存失效
            vocabAddSync, wordTable,
            // 3.0.2：读音补漏。
            //
            // ⚠️ 这里引用的是**声明在下面**的 [pronBackfill]。这是安全的：
            //    lambda 只是被存下来，真正读它发生在同步收尾时 —— 那时
            //    容器早就构造完了。（同一个模式见 [wordStore] 的 `onAudioOrphaned`。）
            backfillPron = { pronBackfill.run() },
        )

    /**
     * App 内更新（2.2.1）。
     *
     * 唯一实例：它持有下载状态（进度 / 已下好的文件）与 `minSupportedVersionCode`
     * 的持久化值。两份实例会出现「设置页在下载、强制页看不到进度」这种怪事。
     */
    val updater: Updater = Updater(context)

    /**
     * 生词本的跨天状态（「今天庆祝过没有」）。
     *
     * ⚠️ 声明顺序在 [wordStore] **之前**：下面那几个 lambda 会读它。
     *    虽然 lambda 是延迟求值的（构造 WordStore 时不调用），
     *    但「先声明后使用」这条读起来才不需要想。
     */
    private val wordPrefs: android.content.SharedPreferences =
        context.getSharedPreferences("englishcommute.word", Context.MODE_PRIVATE)

    /**
     * 读音下载器。
     *
     * ⚠️ **文件名的映射规则只有这一份**（[PronDownloader.pronFileName]）。
     * 服务端生成时用的是同一个规则（`server/bin/build_pron_v2.py` 的 `safe()`）。
     *
     * 第一版服务端用的是「只留字母」，后果是 168 个词形要么下不到、
     * 要么**读的是另一个词**（`he'll` → `hell`、`we'll` → `well`）——
     * 详见 [PronDownloader] 的类注释。
     */
    val pronDownloader: PronDownloader =
        PronDownloader(File(context.filesDir, "words/audio"))

    /**
     * 读音播放。**单例** —— 每次播放都新建 MediaPlayer 会叠着播。
     *
     * 3.0.4 起它还要在开播前把 [sentencePlayer] 停掉（两个都是 MediaPlayer，
     * 同时播就是两个声音叠在一起）。⚠️ 这里引用的是**声明在下面**的属性 ——
     * lambda 延迟求值，真正读它发生在播放时（那时容器早建完了）。
     */
    val pronPlayer: PronPlayer = PronPlayer(onTakeOver = { sentencePlayer.stop() })

    /**
     * 「你听到的那一句」的播放（3.0.4）。单例的理由同上。
     *
     * 它与 [pronPlayer] 是**互相**让声音 —— 两个方向都要有，
     * 否则「先点听读音、再点听这一句」会叠音（反过来不会）。
     */
    val sentencePlayer: SentencePlayer = SentencePlayer(onTakeOver = { pronPlayer.stop() })

    /**
     * 生词本（P1）。
     *
     * 唯一实例：界面订阅它的 `cards` StateFlow，写入方是词卡/复习卡。
     * 两份实例意味着「加了一个词、列表里没有」——而那正是这个功能唯一的可见产出。
     *
     * 目录放在 `filesDir/words/`，与素材缓存（会被保留期清理）分开：
     * 用户攒的生词和复习进度**绝不能**被「清理缓存」顺手带走。
     * 读音文件在它的 `audio/` 子目录下。
     *
     * 三个回调都是「WordStore 不该自己知道的事」：
     *
     * | 回调 | 为什么不由 WordStore 自己做 |
     * |---|---|
     * | `onAudioOrphaned` | 读音文件名规则在 [PronDownloader] 里。再写一次 `"$lemma.mp3"` 就是两处真相源，而它们**已经分叉过一次** |
     * | `celebratedDayReader/Writer` | 「今天庆祝过没有」是跨天状态，用 SharedPreferences 比塞进卡片 JSON 干净（卡片 JSON 是**数组**，加元数据要改结构） |
     */
    val wordStore: WordStore = WordStore(
        dir = File(context.filesDir, "words"),
        // 复习记录的署名。冷启动时可能为空 —— 没有危害（见 AccountStore.deviceIdOrEmpty）
        deviceId = { account.deviceIdOrEmpty() },
        onAudioOrphaned = { lemma -> pronDownloader.delete(lemma) },
        celebratedDayReader = { wordPrefs.getLong(KEY_CELEBRATED_DAY, 0L) },
        celebratedDayWriter = { day -> wordPrefs.edit().putLong(KEY_CELEBRATED_DAY, day).apply() },
    )

    /**
     * 读音补漏（3.0.2）。
     *
     * ⚠️ 声明在 `syncEngine` **之后** —— 而 `syncEngine` 的构造参数里有一个
     *    lambda 会调它。上面那段注释解释了为什么这样是安全的。
     *
     * 它是个无状态的编排（要补哪些词靠 `store.cards` + `pron` 现算），
     * 所以没有「两个实例会打架」的问题。
     */
    val pronBackfill: PronBackfill = PronBackfill(wordStore, pronDownloader)

    private companion object {
        /**
         * 「今天的复习做完了」这个弹窗最近一次出现在哪一天（**当天 0 点的 epoch 毫秒**，
         * 不是「上次弹窗的时刻」—— 判据按天比较，存时刻会在跨天时算错）。
         *
         * ⚠️ 存的是**日期**而不是 Boolean：存 Boolean 的话，第二天要有人负责把它重置，
         *    而「谁重置」永远是最容易漏的一环。存日期则天然自愈：
         *    今天 != 上次那天 ⇒ 还没庆祝过。
         */
        const val KEY_CELEBRATED_DAY = "celebratedDay"
    }
}

class EnglishCommuteApp : Application() {

    lateinit var container: AppContainer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 上一次「回到前台查新素材」的时刻；0 = 本次进程内还没跑过。
     * 只在主线程读写（`onStart` → `kickForegroundRefresh`），用 `@Volatile` 是
     * 为了让同进程内别的线程读到的是新值 —— 它只是个节流用的时间戳。
     *
     * @see kickForegroundRefresh
     */
    @Volatile
    private var lastForegroundRefreshAt = 0L

    /** 上一次「查有没有新版本」的时刻；0 = 本次进程内还没查过。见 [UPDATE_CHECK_MIN_MS] */
    private var lastUpdateCheckAt = 0L

    override fun onCreate() {
        super.onCreate()

        // 日志必须**第一个**装。紧随其后的容器构造要读索引、要对账上次崩溃留下的
        // 残留状态 —— 那一步本身就是高危现场，装晚了就什么都记不下来。
        DiagLog.install(this)
        CrashGuard.install(this)
        DiagLog.i("App", "进程启动")

        createNotificationChannels()
        // 撤掉旧版本可能留在通知栏里的进度通知（1.2.2）。
        //
        // 那条通知是 ongoing 的、用户划不掉，App 重开也不会自己消失 ——
        // 只有在冷启时显式清一次。放在最前面：它不需要容器，而且越早做
        // 用户越看不到那条「更新完成 · 共 0 条可用」再闪一下。
        ContentSyncService.clearStaleNotification(this)

        val boot = span("App", "容器初始化")
        container = AppContainer(this)
        boot.done("索引 ${container.repository.episodes.value.size} 条")

        // 打卡计时器要挂在播放器上，而播放器由 PlaybackService 创建 ——
        // 所以这里只做「安装」：把容器里那份 CheckInStore 和进程 Context 交给它。
        // 必须用同一份实例，否则界面读到的进度永远不动
        ListeningClock.install(this, container.checkIn) { container.checkInSync.onLocalChange() }

        // 崩溃时的状态快照钩子。放在容器之后是因为快照要读容器的状态；
        // 而日志本身在容器之前就已经可用了（见上）。
        DiagLog.snapshotProvider = ::snapshot

        scheduleBackgroundWork()
        // 设备信息必须排在**清理端侧模型之前**：若清理过程中出任何事，
        // 「这台机器是几核、几 KB 页」是最需要先知道的一条
        captureDeviceInfo()
        purgeOnDeviceModels()
        sweepRetention()
        kickAutoSubtitles()
        DiagLog.i("App", "启动完成")
    }

    /**
     * 升级用户：把手机上已经下过的端侧模型删掉（2.1.0）。
     *
     * 为什么必须有这一步、以及它的判据为什么是「文件还在吗」而不是跑没跑过，
     * 全部写在 [OnDeviceModelCleanup] 的类注释里。这里只说调用方式上的两点：
     *
     * - **不阻塞开机**：它是 IO 操作（最多 730 MB 的文件删除，真机上几十到几百毫秒），
     *   挂在应用级协程上。失败也只记一行 —— 清理失败不该让 App 起不来，
     *   而且下一轮启动会自动重试（没有标记要维护）。
     * - **不在 `sweepRetention` 之后**：那一步会删素材库的音频，
     *   两者混在一起时日志里会分不清谁删了什么。分开、而且各记各的。
     */
    private fun purgeOnDeviceModels() {
        appScope.launch {
            runCatching { OnDeviceModelCleanup.sweep(this@EnglishCommuteApp) }
                .onFailure { DiagLog.w("App", "清理端侧模型失败（下次启动重试）：${it.message}") }
        }
    }

    /**
     * 启动即跑一次保留期清理。
     *
     * ## 为什么不能只靠「更新时清理」
     *
     * 保留期是**按天**算的，而用户可能好几天不点更新。只在更新路径上清，
     * 意味着「三天没打开 App，一打开缓存还是满的」—— 他要听的今天的内容
     * 可能正卡在一个快满的缓存里，得先点一次更新才腾出来。
     *
     * 启动时清一次，代价只是扫一遍索引（几十条）加几个文件删除，
     * 换来的是「打开就是干净的」。而且这一步**不联网**，飞行模式也照样有效。
     *
     * 只保留「收藏 + 正在播」：启动这一刻不会有下载在跑
     * （要跑也是几秒之后由 [kickAutoSubtitles] 起的），所以不必再列 busy。
     * 真加进来的话反而是隐患 —— 那一次启动的清理会被一个正在跑的下载挡住。
     */
    private fun sweepRetention() {
        appScope.launch {
            runCatching {
                val repo = container.repository
                val now = System.currentTimeMillis()
                val keep = buildSet {
                    addAll(container.favorites.ids.value)
                    NowPlaying.episodeId?.let { add(it) }
                }
                val expired = repo.expireOldAudio(now, keep)
                // 删完要顺手修剪播放队列：ExoPlayer 手里那份队列是点开某一条时
                // 快照下来的，里面排着刚被删掉的文件。不摘掉的话，自动连播到它
                // 就是「播放失败：文件不存在」，而用户此刻什么都没做。
                // 这一句在本进程首次启动时通常是空操作（还没有播放器）
                NowPlaying.prunePlaylist(expired.ids)
                val purged = repo.purgeStaleIndex(now)
                DiagLog.i(
                    "App",
                    "启动清理：保留期到期 ${expired.count} 条（${expired.bytes / 1048576} MB）" +
                        " · 过时效条目 ${purged} 条",
                )
            }.onFailure { DiagLog.w("App", "启动清理失败（不影响使用）：${it.message}") }
        }
    }

    /**
     * 采集并记录这台机器的硬件画像。
     *
     * 放在启动路径上是安全的：`sysconf` + `Build` + `MemoryInfo` 全是**只读内存查询**，
     * 微秒量级，不碰文件系统。
     *
     * 为什么值得每次都记：**它让「这台机器该用几个线程」不再靠猜。**
     *
     * - `availableProcessors()` 与 `_SC_NPROCESSORS_CONF` 的差值，能看出 ROM
     *   有没有用 cpuset 限制本进程 —— 线程数该按「可用核」算，不是按芯片标称核数。
     * - 页大小确认了 16 KB 对齐断言是不是在为本机工作。
     *
     * 这两条都**只能从真机读出来**，光知道芯片型号推不出来 —— 同一个 SoC
     * 在不同厂商 ROM 上给出的可用核数完全可能不同。
     */
    private fun captureDeviceInfo() {
        runCatching { DeviceInfo.capture(this) }
    }

    /**
     * 写进日志的应用状态快照。崩溃时自动调用，用户也可以手动触发。
     *
     * **只读内存状态，绝不碰文件系统。** 这个函数会在未捕获异常处理器里同步执行，
     * 那一刻进程已经处于危险状态（很可能正是 OOM 触发的），任何额外的 IO
     * 都可能让它连日志都写不完就死掉 —— 那就白装了。
     *
     * ⚠️ 2.1.0 删掉了「翻译」与「原生库」两行：它们读的
     *    `translateManager.state` 与 `NativeLibCheck.last` 都不存在了。
     *    顺手删而不是留个占位，是因为**一行永远打印「无」的诊断信息
     *    比没有更糟** —— 下次排查的人会先去查它为什么是「无」。
     */
    private fun snapshot(): String {
        val repo = container.repository
        val eps = repo.episodes.value
        val busy = eps.filter { it.status.isBusy }
        return buildString(512) {
            append("时间 ").append(Date()).append('\n')
            append("运行 ").append(String.format(Locale.US, "%.1fs", DiagLog.uptimeSec())).append('\n')
            append(CrashGuard.memoryLine()).append('\n')
            append("条目 ").append(eps.size).append(" 条 · ")
            append(eps.groupingBy { it.status.name }.eachCount().entries.joinToString("、") { "${it.key}=${it.value}" })
            append('\n')
            if (busy.isNotEmpty()) {
                append("进行中 ").append(busy.joinToString("、") { "${it.title.take(20)}(${it.status})" }).append('\n')
            }
            append("同步 ").append(container.syncEngine.state.value.phase::class.simpleName)
            append(" · 引擎占用=").append(container.syncEngine.isRunning)
            append(" · 待补字幕=").append(runCatching { repo.pendingSubtitleCount() }.getOrDefault(-1))
            append('\n')
            // 用缓存值（DeviceInfo.last），不在崩溃路径上重新 sysconf —— 一切从简。
            // logLine() 自带「设备 」前缀，别再拼一次
            DeviceInfo.last?.let { append(it.logLine()).append('\n') }
        }
    }

    /**
     * 每次进程启动都按设置重新对齐一次后台任务。
     *
     * 为什么不在「用户拨开关」时才登记：WorkManager 的任务在清除数据、
     * 强制停止、系统清理等情况下会被丢掉，而用户是感知不到的。
     * 启动时无条件对齐一次，代价只是一次本地查询，换来的是定时任务不会悄悄消失。
     *
     * ## 3.5.0：只剩一个后台任务了
     *
     * 「每日自动更新」（24 小时周期、WiFi + 电量约束、跑完整的一轮同步）拆掉了。
     * 它**只在 App 长时间待在后台时才可能被系统唤醒**，而我们这个 App 的用户
     * 不会那样用 —— 于是它实际上几乎从不执行，却让每次登记都要多一条开机自启权限、
     * 多一个说不清的唤醒来源。而「打开 App 时自动更新」这条路径一直都在
     * （见 [kickForegroundRefresh]），那才是用户真正感知到的那一次。
     *
     * ⚠️ 拆掉的东西里有两个**不能跟着一起删**的：
     *
     *  · **系统里那条任务**（[RetiredWork]）：它活在 WorkManager 的库里，
     *    删代码不会删它 —— 留着的话，系统会按点唤醒一个不存在的类。
     *  · **这个开关**（`backgroundWorkEnabled`）：它同时还管着下面这个
     *    补字幕任务和服务里的接力循环。删了它，曾经关掉过的人会被静默地重新打开。
     */
    private fun scheduleBackgroundWork() {
        appScope.launch {
            runCatching {
                // 先把退役任务撤掉：它和下面那条**不是**同一件事，
                // 而且它必须无条件执行（跟开关无关 —— 用户关了后台同步，
                // 那条旧任务更不能留在系统里）
                RetiredWork.cancelAll(this@EnglishCommuteApp)

                val enabled = container.settings.backgroundWorkEnabled.first()

                // 每小时一次的后台字幕加载。
                //
                // 它和刚拆掉的那个是两件完全不同的事：这个**只加载字幕、不下载音频**，
                // 所以不要求 WiFi、也不要电量约束（详见 SubtitleWorker）。
                // 它的价值恰恰就是「用户没打开 App 的那段时间」——
                // 那正是「打开就能看字幕」和「打开才开始等字幕」的差别。
                SubtitleScheduler.apply(this@EnglishCommuteApp, enabled)

                // 4.2.0：每晚 23:30 的提醒。**每次启动都重新排一次** ——
                // 闹钟在「清除数据 / 强行停止 / 系统清理 / 装新版本」之后会被丢掉，
                // 而用户感知不到（3.5.0 那条 WorkManager 任务就是死在这里）。
                DailyReminder.sync(
                    this@EnglishCommuteApp,
                    container.settings.reminderEnabled.first(),
                )
            }
        }
    }

    /**
     * 回到前台时**查一次有没有新素材**，并把该预取的**静默**下下来（2.1.9）。
     *
     * ## 为什么必须有它
     *
     * 2.1.9 把下载策略改成了懒加载（[ContentPolicy.selectPrefetch]）：
     * 只预取「接下来 N 条 + 收藏 + 每源保底」。而**懒加载要成立**，前提是
     * 「用户打开 App 时手上那几条已经下好了」—— 否则他点开发现没有音频，
     * 就退化成「点了才下」，那正是我们要避开的。所以「打开 App」必须
     * 成为一次预取触发。
     *
     * ## 为什么是「静默」：直接跑引擎，不走前台服务
     *
     * 走 [ContentSyncService] 会带一条**常驻进度通知**（前台服务的硬性要求）。
     * 而这是每次打开 App 都会发生的事 —— 用户刚点开图标，状态栏立刻多一条
     * 「正在更新今日内容」，感知很重。直接跑 [SyncEngine.run] 则完全不可见：
     * 不占通知栏、不发收尾提示。
     *
     * 代价是**不享受前台服务的保护**：用户马上锁屏的话，下载可能随进程被挂起。
     * 这一点可以接受，理由是三条都成立：
     *   · 预取量小（3 条 ≈ 30 MB），正常几十秒完成；
     *   · 下载写 `.part` 且支持 `Range` 续传（见 `AudioStore.attemptDownload`），
     *     被打断**不会**留下半截坏文件；
     *   · 下次进 App 会重新算一遍预取集合，自然接着下。
     *
     * ⚠️ 用户**主动点**「更新今日内容」走的是另一条路（前台服务 + 通知）——
     *    那是「他点了就该有回音」的动作。两条路的差别是有意的，不要合并。
     *
     * ## 节流
     *
     * 同一个前台周期 `onStart` 可能连来好几次，见 [FOREGROUND_REFRESH_MIN_MS]。
     */
    fun kickForegroundRefresh() {
        // 4.0.0：回到前台顺手对齐一次打卡记录（未登录时是一次空调用）。
        // ⚠️ 放在**节流判断之前**：它自带 2 分钟节流，而「回到前台」正是
        //    用户主动来了的信号 —— 不该被素材那一套 60 秒的节流连累。
        container.checkInSync.maybeSync()
        if (container.syncEngine.isRunning) {
            DiagLog.i("App", "回到前台 · 已有一轮在跑，这次不再起")
            return
        }
        val now = System.currentTimeMillis()
        val since = now - lastForegroundRefreshAt
        if (lastForegroundRefreshAt > 0L && since < FOREGROUND_REFRESH_MIN_MS) {
            DiagLog.i(
                "App",
                "回到前台 · 距上次查新素材 ${since / 1000}s" +
                    "（< ${FOREGROUND_REFRESH_MIN_MS / 1000}s），跳过",
            )
            return
        }
        lastForegroundRefreshAt = now
        DiagLog.i("App", "回到前台 · 查一次有没有新素材（静默，不走前台服务）")
        appScope.launch {
            runCatching { container.syncEngine.run() }
                .onFailure { DiagLog.w("App", "前台静默预取异常：${it.message}") }
        }
    }

    /**
     * 回到前台时顺手问一次有没有新版本（2.2.1）。
     *
     * ## 为什么节流到 6 小时（而「查新素材」是 60 秒）
     *
     * 两件事的频率完全不同：素材每天都有新的，而**版本**几周才发一次。
     * 按素材那个频率去问，等于每天朝服务器打几十次毫无意义的请求，
     * 换来的只是「发布后最快一分钟就能收到」——而用户并不会因此多得到什么。
     *
     * ## 为什么失败就静默
     *
     * 查更新失败（断网、服务器抖）**不是**用户需要知道的事 ——
     * 他此刻打开 App 是想听东西。强制更新的判据已经持久化在本地，
     * 不受这次查询成败影响（见 [Updater.forced]）。
     */
    fun kickUpdateCheck() {
        val now = System.currentTimeMillis()
        if (lastUpdateCheckAt > 0L && now - lastUpdateCheckAt < UPDATE_CHECK_MIN_MS) return
        lastUpdateCheckAt = now
        appScope.launch {
            runCatching { container.updater.check() }
                .onFailure { DiagLog.w("App", "查更新异常：${it.message}") }
        }
    }

    /**
     * 回到前台时顺手补一轮字幕。
     *
     * ## 为什么要有这个
     *
     * 加它之前，「什么时候会补字幕」完全等于「什么时候点一键更新」，
     * 再叠上每日 Worker（要求非 Metered 网络 + 电量不低 + 系统愿意唤醒）。
     * 两条路径都断掉之后，用户感受到的就是「字幕经常不来」——
     * 而音频**已经**在本地了，补字幕这件事根本不需要下载。
     *
     * 现在「打开 App」本身就是一次触发。判据是「有音频、字幕没齐」，
     * 而不是状态位 —— 所以上次崩溃留下的半成品天然会被重新捡起来，
     * 不需要用户做任何事。
     *
     * ## 2.1.0：门禁整条删掉了
     *
     * 这里原来有两句提前返回：「模型没就位就别起」（无服务器模式下）——
     * 端侧模型已经不存在了，所以它连判据都没有了。
     *
     * 引擎已在跑时直接跳过：正在跑的那一轮会自己把队列走完。
     */
    fun kickAutoSubtitles() {
        if (container.syncEngine.isRunning) return
        appScope.launch {
            // ⚠️ 判据用「**有音频且字幕没齐**的条数」。
            //
            //    原来这里用的是「可自动转写」的数（1.2.4），那个区分来自
            //    「超过 30 分钟的音频不会被自动转写」这条端侧限制。现在服务器
            //    对每一期都会转写、客户端取回来只要几百毫秒，**长的短的都一样** ——
            //    所以那个区分连同 `MAX_AUTO_TRANSCRIBE_SEC` 一起删了。
            //
            //    少了这一层，就不会再出现那种自相矛盾的三行日志：
            //      「回到前台 · 待转写 4 条 → 自动起一轮」
            //      「转写接力 ▶」
            //      「本轮无进展，停止（仍有 4 条待转写）」   ← 8 毫秒之后
            val pending = runCatching { container.repository.pendingSubtitleCount() }
                .getOrDefault(0)
            if (pending <= 0) return@launch
            DiagLog.i("App", "回到前台 · 有 $pending 条字幕待补 → 自动起一轮")
            runCatching { ContentSyncService.startSubtitles(this@EnglishCommuteApp) }
                .onFailure { DiagLog.w("App", "自动补字幕启动失败：${it.message}") }
        }
    }

    /**
     * 用户点开某条内容 = 他**此刻**就要听它。
     *
     * 字幕没齐的话把它插到队首。少了这一步，用户会盯着一条没有中文对照的内容
     * 等完一整轮队列，而他真正要的那条其实只要几百毫秒。
     */
    fun boostSubtitles(episodeId: String) {
        if (episodeId.isEmpty()) return
        appScope.launch {
            val ep = container.repository.find(episodeId) ?: return@launch
            val needs = runCatching { container.repository.needsSubtitles(ep) }.getOrDefault(false)
            if (!needs) return@launch
            DiagLog.i("App", "点开字幕没齐的内容 → 请求插队补字幕：${ep.title.take(24)}")
            runCatching { ContentSyncService.startSubtitles(this@EnglishCommuteApp, ep.id) }
                .onFailure { DiagLog.w("App", "插队补字幕启动失败：${it.message}") }
        }
    }

    /**
     * 用户点了某条内容，而它**连音频都还没下下来**（2.0.3）。
     *
     * 与 [boostSubtitles] 是一对：那个管「有音频、字幕没齐」，这个管「连音频都没有」。
     * 两个都是「用户此刻就要这一条」的表达，只是缺的东西不同。
     *
     * ## 为什么必须起一轮更新，而不是只登记
     *
     * 下载需要网络 + 前台服务（用户可能立刻锁屏，而下载要跑几十秒）。所以
     * 登记（[SyncEngine.requestDownload]）之后必须真的把服务拉起来；
     * 服务已经在跑时 [ContentSyncService.onStartCommand] 会把这次请求登记成
     * 「本轮收尾后立刻补做」，不会丢。
     *
     * ⚠️ 这里**不能**像 [boostSubtitles] 那样先 `if (!needs) return` 就完事：
     *    「音频还没有」恰恰是本函数的正常输入，不是要跳过的理由。
     */
    fun downloadNow(episodeId: String) {
        if (episodeId.isEmpty()) return
        container.syncEngine.requestDownload(episodeId)
        val title = container.repository.find(episodeId)?.title?.take(24).orEmpty()
        DiagLog.i("App", "点了一条还没下好音频的内容 → 现在就下：$title")
        runCatching { ContentSyncService.start(this) }
            .onFailure { DiagLog.w("App", "插队下载启动失败：${it.message}") }
    }

    /**
     * 内存压力响应。
     *
     * ## 2.1.0：这里现在只做一件事，而且够用
     *
     * 1.2.0 加它的时候，进程稳态下挂着三块大东西：识别模型 630 MB、翻译模型 137 MB、
     * 封面解码缓存 4 MB —— 所以它按等级分别放掉它们，还专门解释了
     * 「两个模型的释放为什么必须走『忙就放掉机会』的那条路」（直接 close 会在
     * native 推理途中拆 session，那是 v0.11.0 闪退的同一个坑）。
     *
     * 那两个模型都拆了，现在只剩封面缓存。而它本来也是最该先丢的那一块：
     * 磁盘上还有原图，重新解码只要几毫秒，用户几乎无感。
     *
     * ⚠️ 函数**留着**而不是删掉：系统照样会喊，而在 UI 不可见时把几 MB
     *    位图还回去是一件白拿的好事（切后台时尤其明显）。将来再加任何缓存，
     *    挂载点也已经在这里了。
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (!::container.isInitialized) return

        // UI 不可见（切后台）与内存偏紧，都先丢掉解码后的封面。
        // 磁盘上的图留着，不花流量
        if (level >= TRIM_MEMORY_UI_HIDDEN) {
            runCatching { container.artwork.trimMemory() }
            DiagLog.i("App", "内存压力 level=$level → 已丢弃封面解码缓存")
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_DOWNLOAD,
                "内容更新",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "显示每日内容更新的下载与字幕进度"
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_PLAYBACK,
                "播放控制",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "后台播放时的控制条"
                setShowBadge(false)
            }
        )
    }

    companion object {
        const val CHANNEL_DOWNLOAD = "download"
        const val CHANNEL_PLAYBACK = "playback"

        /**
         * 「回到前台自动查新素材」的最小间隔（2.1.9）。
         *
         * 一次前台周期里 `onStart` 可能连来好几次（息屏→亮屏、切走→切回），
         * 而这一轮会拉一次目录（78 KB）并**写一次索引**。
         * 60 秒既能让「每次打开 App 都是最新的」这句话成立，
         * 又不会把连续几次亮屏变成连续几次写盘。
         *
         * @see kickForegroundRefresh
         */
        const val FOREGROUND_REFRESH_MIN_MS = 60_000L

        /**
         * 查更新的间隔。**刻意比「查新素材」长两个数量级。**
         *
         * 素材每天都有新的（所以按分钟节流），而版本几周才发一次 ——
         * 按素材那个频率去问服务器，换来的只是「发布后最快一分钟收到」，
         * 而用户并不会因此多得到什么，却要每天多打几十次请求。
         */
        const val UPDATE_CHECK_MIN_MS = 6 * 60 * 60 * 1000L

        /**
         * 通知 ID 全App**只在这里定义**。
         *
         * ## 为什么必须收成一处（1.2.5 修的真 bug）
         *
         * 通知的唯一标识是「包名 + tag + id」，**id 相同就是同一条通知** —— 后发的
         * 直接顶掉先发的。而 media3 的 `DefaultMediaNotificationProvider` 默认
         * 用的就是 1001（`DEFAULT_NOTIFICATION_ID`），我们的同步进度通知当时
         * 也写的是 1001。后果是一串彼此看起来无关的症状：
         *
         * - **正在听的时候一有更新，播放控制的通知就没了**（进度通知把它顶掉）
         * - 同步收尾时那句「cancel(1001)」把播放通知也一起撤了
         * - 冷启兜底清理旧僵尸通知时，同样会顺手清掉播放通知
         * - 而收尾留下的「更新完成」会**替换**掉播放控件，看起来像播放器坏了
         *
         * 三个数字放在一起、并在 `tools/verify-library-ux.py` 里断言两两不同，
         * 是为了让「加一个新通知时又撞上」这件事在构建期就被拦下。
         */
        /**
         * 播放控制通知。
         *
         * **必须等于 media3 的默认值**（`DefaultMediaNotificationProvider
         * .DEFAULT_NOTIFICATION_ID`，1.11.1 实测 = 1001）—— 即使我们显式传了这个值，
         * 也要保持一致：Media3 内部还有按这个 id 取消/更新的路径
         * （见 [com.example.englishcommute.player.PlaybackService]）。
         */
        const val NOTIFY_ID_PLAYBACK = 1001

        /** 同步/下载的**进度**通知（ongoing，用户划不掉，靠服务自己撤） */
        const val NOTIFY_ID_SYNC_PROGRESS = 2001

        /** 同步/下载的**收尾**通知（autoCancel，用户可划掉） */
        const val NOTIFY_ID_SYNC_FINAL = 2002
    }
}
