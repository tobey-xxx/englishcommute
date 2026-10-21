package com.example.englishcommute.ui

import com.example.englishcommute.checkin.DayKey
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.map
import android.app.Application
import androidx.compose.runtime.Stable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.englishcommute.EnglishCommuteApp
import com.example.englishcommute.content.Episode
import com.example.englishcommute.content.FeedSources
import com.example.englishcommute.diag.DiagLog
import com.example.englishcommute.player.NowPlaying
import com.example.englishcommute.sync.ContentSyncService
import com.example.englishcommute.sync.SyncState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 素材库的筛选方式 */
enum class LibraryFilter(val label: String) {
    ALL("全部"),
    FAVORITE("收藏"),
    UNFINISHED("未听完"),
}

/**
 * 素材库界面状态。
 *
 * 数据全部来自应用级容器里的唯一实例 —— 界面**不自己做任何网络或转写**，
 * 只是触发 [ContentSyncService] 并观察状态。这样用户退到后台时，
 * 前台服务继续跑，回到界面时看到的仍然是同一份进度。
 *
 * 收听进度与收藏也是同一条路子：直接从容器里取 `StateFlow` 交给界面订阅。
 * **不在这里做 `collectAsState` 之前的口味加工** —— 一旦多拷一层快照，
 * 播放页在写、列表页还在显示旧值这种撕裂就有机会发生。
 */
/**
 * ⚠️ [Stable] 不是装饰，它决定了 `LibraryScreen` 能不能被跳过。
 *
 * `LibraryScreen(vm: LibraryViewModel = viewModel())` 的参数类型如果不稳定，
 * Compose 编译器就无法为它生成 skip 逻辑 —— 于是**只要外层重组，
 * 整个列表函数体就必跟着重跑**，哪怕所有入参都没变。
 *
 * 这里的承诺是成立的：公开成员全是 `StateFlow` 与函数引用，
 * 实例本身在 Activity 生命周期内不变，`equals` 就是引用相等。
 */
@Stable
class LibraryViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as EnglishCommuteApp).container

    /**
     * 今日打卡进度（秒, 目标秒）—— 4.5.0 接到刊头的那个环上。
     *
     * 素材页原来只回答「有什么」，不回答「我今天还差多少」；而这两件事
     * 在通勤时是同一个决定（今天该不该再听一条）。数据本来就在同一个容器里，
     * 缺的只是把它读出来。
     */
    val todayProgress: StateFlow<Pair<Int, Int>> = container.checkIn.days
        .map { container.checkIn.todaySeconds() to container.checkIn.goalSecondsOn(DayKey.today()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0 to 40 * 60)

    val episodes: StateFlow<List<Episode>> = container.repository.episodes
    val syncState: StateFlow<SyncState> = container.syncEngine.state

    /** episodeId → 已听到的毫秒位置 */
    val positions: StateFlow<Map<String, Long>> = container.playbackPositions.positions

    /** 已听完的 episodeId */
    val doneIds: StateFlow<Set<String>> = container.playbackPositions.done

    /** 收藏的 episodeId */
    val favorites: StateFlow<Set<String>> = container.favorites.ids

    private val _filter = MutableStateFlow(LibraryFilter.ALL)
    val filter: StateFlow<LibraryFilter> = _filter.asStateFlow()

    private val _cacheBytes = MutableStateFlow(0L)
    val cacheBytes: StateFlow<Long> = _cacheBytes.asStateFlow()

    init {
        refreshCacheSize()
    }

    /** 一键更新：交给前台服务，界面退出也不会中断 */
    fun startSync() {
        DiagLog.act("点「更新今日内容」")
        ContentSyncService.start(getApplication())
    }

    /**
     * 只检查各源能不能拉到，不下载、不转写。
     *
     * 不启前台服务：这一步只发几个 HTTP 请求，几秒就完，退出界面被回收也无所谓。
     */
    fun checkSources() {
        DiagLog.act("点「只检查源」（不下载）")
        viewModelScope.launch {
            container.syncEngine.checkSources()
        }
    }

    /**
     * 手动补单条字幕。用于上次没取到、或者用户急着想看的内容。
     *
     * ## 2.1.0：名字从 transcribeOne 改成 fetchSubtitlesOne
     *
     * 它原来叫「转写单条」，做的事是「在本机把这条音频识别成字幕」——
     * 一条 24 分钟的 TED 要跑好几分钟。所以那时的注释全在讲
     * 「必须走前台服务、断点会让下次接着跑」。
     *
     * 现在它做的事是**向服务器取一次这一条的字幕**（几百毫秒），
     * 但**仍然走前台服务** —— 理由变了，判断没变：用户在通勤路上点一下
     * 就会锁屏，而 `viewModelScope` 在退出界面时会被取消。
     * 几百毫秒虽然短，但「点了没反应」和「点了马上有」在用户侧差得很远。
     *
     * 走 [ContentSyncService.startSubtitles] 的插队模式：这条会排到队首，
     * 因为用户是**明确点了它**。
     */
    fun fetchSubtitlesOne(episode: Episode) {
        DiagLog.act("点「补字幕」· ${episode.title.take(24)}")
        ContentSyncService.startSubtitles(getApplication(), episode.id)
        refreshCacheSize()
    }

    /**
     * 每一档筛选最后一次离开时看到的**位置锚点**。
     *
     * ## 为什么放在 ViewModel 而不是 `remember`
     *
     * 1300 行前那一版把三个 `LazyListState` 放在 `LibraryScreen` 的 `remember` 里 ——
     * 只要素材库这一页离开组合（**横向翻到设置页再翻回来**、系统回收后重建），
     * 三档的位置就一起归零，表现是「切回来又跳了」。ViewModel 挂在 Activity 作用域，
     * 翻页签不会重建。
     *
     * ## 为什么不是 StateFlow
     *
     * 位置在滚动时**每一帧**都在变。做成被观察的状态，等于让整个列表每帧重组一次
     * —— 这正是 1.2.0 花大气力从 `Root()` 里搬走的那类问题。
     * 只有切换筛选那一瞬间才有人来读它，所以这里就是一张普通的表。
     *
     * ⚠️ 只有主线程读写：写入来自 `snapshotFlow`（组合/主线程），读取来自组合。
     *    加锁反而会引入「谁先谁后」的新时序，见 [ListAnchor] 的说明。
     */
    private val anchors = HashMap<LibraryFilter, ListAnchor>()

    /**
     * 某一档**自己**上一次的位置。
     *
     * ⚠️ 它已经不是「切档要用的那个锚」了 —— 那件事由 [anchorBeforeSwitch] 回答。
     *    2.0.4 正是把这两件事合成一件才导致「一会儿高一会儿低」：切到一个
     *    上次滚过的档，落点会跳到它**那一次**的位置，筛选签栏跟着窜上去。
     *    现在它只在「现在贴顶」时被用（决定落在哪一条，而不是决定贴不贴顶），
     *    见 [LibraryAnchor.resolve]。
     */
    fun anchorFor(target: LibraryFilter): ListAnchor? = anchors[target]

    /**
     * **切走那一刻**当前档的位置。新档是「贴顶」还是「在刊头下」，由它决定。
     *
     * 为什么要单独存一份快照：`anchors[_filter.value]` 在切档之后会开始记**新档**的
     * 位置，而落点是在新档下算的（`remember(filter)`）—— 那时候再去读已经来不及了。
     * 不存快照的话，读到的会是新档自己刚滚出来的位置，规则就退回「看目标档的历史」。
     */
    private var anchorBeforeSwitch: ListAnchor? = null

    fun anchorBeforeSwitch(): ListAnchor? = anchorBeforeSwitch

    /**
     * 记下某一档此刻的位置。
     *
     * 由界面在滚动时连续调用（每帧一次，纯内存写，很便宜）。
     * 这样「切走」不需要任何收尾动作 —— 切走之前最后一帧已经把位置记好了，
     * 少一个「先存后切」的时序（1.2.5 那次真机日志里的「旧列表先被拽上去」就是它）。
     */
    fun rememberAnchor(filter: LibraryFilter, anchor: ListAnchor) {
        anchors[filter] = anchor
    }

    fun setFilter(filter: LibraryFilter) {
        val from = _filter.value
        if (from == filter) return
        // ⚠️ 顺序不能反：先把「切走时在哪儿」拍下来，再换档
        anchorBeforeSwitch = anchors[from]
        // 日志里成对出现，是「切档那一栏没动」在真机上的唯一凭据：
        //   切筛选 → 未听完 · 从 筛选签贴顶
        //   筛选落点 · 未听完 · 顶部 4/15 条 · 筛选签贴顶     ← 两行相同 = 没跳
        DiagLog.act(
            "切筛选 → ${filter.label} · 从 " +
                (anchorBeforeSwitch ?: LibraryAnchor.TOP).label(),
        )
        _filter.value = filter
    }

    fun toggleFavorite(episode: Episode) {
        val wasOn = episode.id in container.favorites.ids.value
        container.favorites.toggle(episode.id)
        DiagLog.act("${if (wasOn) "取消收藏" else "收藏"} · ${episode.title.take(24)}")
    }

    fun refreshCacheSize() {
        viewModelScope.launch {
            val bytes = withContext(Dispatchers.IO) { container.repository.cacheBytes() }
            _cacheBytes.value = bytes
        }
    }

    /**
     * 清空全部音频缓存。
     *
     * 播放记录必须跟着一起清 —— 文件都删了，留着一堆指向不存在音频的进度和「听完」标记，
     * 列表上会出现「已听完」但点开报「音频文件不在了」的鬼条目。
     */
    fun clearCache() {
        DiagLog.act("点「清空全部音频缓存」")
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                container.repository.audioStore.clearAll()
                container.repository.resetAllToDiscovered()
                container.playbackPositions.clearAll()
                container.favorites.retainOnly(emptySet())
                // ⚠️ 2.1.0 删掉了 `container.checkpoints.clearAll()`：
                //    「转写断点」这套机制随端侧识别一起拆掉了
                //    （升级用户手机上残留的 .trk 由 OnDeviceModelCleanup 清）。
            }
            refreshCacheSize()
        }
    }

    /**
     * 删掉一条内容（长按菜单的「删除」，1.2.2）。
     *
     * 五样东西必须**一起**走，缺任何一样都会留下「看着还在」的残迹：
     *
     * | 少了哪一样 | 残迹是什么 |
     * |---|---|
     * | 音频 / 字幕 / 译文文件 | 空间没腾出来，「删除」名不副实 |
     * | 索引条目 | 列表里那条还在，变成「待下载」 |
     * | 删除记录 | 下次更新原样回来，还会重新下载一遍 |
     * | 播放进度 / 听完标记 | 留着指向不存在音频的记录，界面状态自相矛盾 |
     *
     * 最后一步是摘播放队列：ExoPlayer 手里那份是「打开某一期」时的**快照**，
     * 里面排着刚被删掉的文件。不摘的话，自动连播到它就是一句
     * 「播放失败：文件不存在」—— 而且发生在一个用户完全没操作的时刻。
     *
     * ⚠️ `prunePlaylist` 必须在主线程，所以它在 `withContext` **外面**。
     */
    fun deleteEpisode(episode: Episode) {
        DiagLog.act("长按删除 · ${episode.title.take(24)}")
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                container.repository.removeEpisode(episode)
                container.playbackPositions.clear(episode.id)
                container.playbackPositions.clearDone(episode.id)
                // 收藏也要跟着取消：留着一个 id，筛选签上就会多一个
                // 永远点不开的计数
                container.favorites.remove(episode.id)
            }
            NowPlaying.prunePlaylist(listOf(episode.id))
            refreshCacheSize()
        }
    }

    /**
     * 只清理「已听完」的音频。
     *
     * 比「清空全部」温和得多：通勤路上听完的那几集通常不会再听，
     * 但今天刚更新、还没听的必须原样留着 —— 那正是明天早上要用的东西。
     */
    fun clearPlayedAudio() {
        DiagLog.act("点「清理已听完的音频」")
        viewModelScope.launch {
            val done = container.playbackPositions.done.value
            val targets = container.repository.episodes.value.filter { it.id in done }
            if (targets.isEmpty()) {
                refreshCacheSize()
                return@launch
            }
            withContext(Dispatchers.IO) {
                container.repository.deleteLocalFiles(targets)
                // 文件没了，「听完」标记也不再有意义，一起清掉；
                // 否则列表会显示成「已听完 · 待下载」这种自相矛盾的状态
                targets.forEach { container.playbackPositions.clearDone(it.id) }
            }
            refreshCacheSize()
        }
    }

    /**
     * 这条内容是不是「有音频但没字幕」——决定要不要显示「转写字幕」按钮。
     *
     * **只读内存里的字段，不做文件检查。** 这个方法会在每次重组时对每个条目调用，
     * 里面如果做 `File.isFile` 就是主线程 IO，列表一滚就卡。
     */
    fun canTranscribe(episode: Episode): Boolean =
        !episode.status.isBusy &&
            // ★ 2.2.0：去掉了 `audioFileName != null`。
            //   字幕不再依赖「音频已经落盘」—— 边听边播的条目同样需要文稿，
            //   而「加载字幕」这个入口正是给用户手动补的那一个（见 needsSubtitles）。
            episode.srtFileName == null

    /**
     * 能不能播。
     *
     * ★ 2.0.3：判据收到一处，不再看状态位 —— 那个状态位会把「字幕还没到位」
     * （被错标成 Failed 的那一类）也算成不能播，于是那一行**整行点不动**，
     * 用户看到的是「这条音频无法播放」。
     *
     * ★ 2.2.0：判据从「本地有音频」放宽到「**有音频地址**」。
     *
     *   原因是播放器改成了**边下边播**（见 `PlayerViewModel.toMediaItem`）：
     *   本地没有音频时它会直接把远程地址交给 Media3，边播边下。
     *   于是「还没下载」不再是「不能播」的充分理由 —— 恰恰相反，
     *   那些没被懒加载预取到的条目**正是用户最想点开的那几条**。
     *
     *   ⚠️ 留着的那个 `isNotBlank()` 不是摆设：索引里 audioUrl 为空的条目
     *      是真的没有任何地址可取（`ServerCatalog.parse` 会把它过滤掉，
     *      这里是第二道）。那种情况点下去只能去下载，而下载也拿不到东西。
     *
     * 仍然只读字段、不做文件检查（每次重组都会调）；真正的存在性判断
     * 交给播放页 —— 那边给得出明确错误。
     */
    fun canPlay(episode: Episode): Boolean = episode.audioUrl.isNotBlank()

    /**
     * 本地还没有音频 —— 点下去会**边听边下**，同时后台把它落盘。
     *
     * ★ 2.0.3：以前这种点击被静默吞掉（不进播放页、不弹提示、日志里连一行都没有）。
     * ★ 2.2.0：现在它的含义变了 —— 不是「点不动」，而是「能立刻听，但先不占本地缓存」。
     *   列表要照它给那一行加一句说明，否则用户会以为自己听的是离线内容。
     */
    fun needsAudio(episode: Episode): Boolean = !episode.isPlayable

}
