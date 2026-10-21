package com.example.englishcommute.player

import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.example.englishcommute.diag.DiagLog

/**
 * 「此刻播放器里开着的是哪一条」。
 *
 * ## 为什么需要这么一个全局登记点
 *
 * 保留期清理会**删音频文件**，而 ExoPlayer 手里正拿着那个文件的句柄。
 * 删掉正在播的那条，下一秒就是播放报错 —— 用户看到的是「听着听着自己断了」，
 * 而且断在一个完全莫名其妙的时刻（后台刚好跑了一次清理）。
 *
 * ## 为什么不能只看「有没有播放进度」
 *
 * [com.example.englishcommute.settings.PlaybackPositions] 里确实有位置，
 * 但它有两个问题：一是**暂停了几小时的那条也还在里面**（它随时可能被按播放），
 * 二是刚点开还没到 3 秒的内容根本没有记录 —— 而那正是用户此刻在听的东西。
 *
 * ## 为什么由 [PlaybackService] 维护而不是 ViewModel
 *
 * 播放器活在 Service 里，UI 可以随时退出（退出播放页、划掉 Activity），
 * 音频却继续在放。只有 Service 的生命周期与「文件句柄是否还开着」一致。
 *
 * 用 `object` 而不是往容器里塞一个实例：这是一个**瞬时信号**，
 * 没有状态历史、没有订阅者、也不需要按进程区分 —— 加一层容器只是多一个
 * 能忘记初始化的地方。
 *
 * ## 除了「正在播哪条」，它还要管一件事：修剪播放队列
 *
 * ExoPlayer 手里不是「一条音频」而是**整个可播队列**（点开某一条时快照下来的，
 * 见 `PlayerViewModel.playableQueue`）。保留期删掉的文件，仍然排在那个队列里 ——
 * 等播放器自动连播到它，就是「播放失败：文件不存在」，而用户此刻什么都没做。
 *
 * 所以删完必须回过头把队列里对应的项摘掉（[prunePlaylist]）。这件事只能由
 * 持有播放器的这一层做，而且做起来是安全的：正在播的那条本来就在保留期的
 * keep 集合里，永远不会出现在待摘名单里。
 */
object NowPlaying {

    @Volatile
    var episodeId: String? = null
        private set

    /**
     * 播放器引用。只在主线程用 —— ExoPlayer 要求所有调用都在它被创建的线程上。
     * 因此这里不直接调 API，而是 [Handler] post 回主线程。
     */
    @Volatile
    private var player: Player? = null

    private val main = Handler(Looper.getMainLooper())

    /**
     * 挂到播放器上。
     *
     * 只登记**有媒体项**时的 id：`onMediaItemTransition` 在清空队列时会带
     * `mediaItem = null` 回调，那种情况要登记成「什么都没有」。
     */
    fun attach(player: Player) {
        this.player = player
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                episodeId = mediaItem?.mediaId
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_IDLE) episodeId = null
            }
        })
        episodeId = player.currentMediaItem?.mediaId
        DiagLog.i("Play", "已登记当前播放（保留期清理会避开它）")
    }

    /** 服务销毁时清掉，避免留下一个永远「正在播」的幽灵把清理挡住 */
    fun clear() {
        episodeId = null
        player = null
    }

    /**
     * 把已经从磁盘删掉的条目从播放队列里摘掉。
     *
     * 由保留期清理的调用方在删完之后调（见 `ContentRepository.Expired.ids`）。
     * 没有播放器、或队列里本来就没有这些 id 时，是空操作。
     *
     * 必须在主线程执行：`removeMediaItem` 是 ExoPlayer 的队列改动，
     * 从工作线程调会直接抛 `IllegalStateException`（"Player is accessed on the
     * wrong thread"）—— 而保留期清理恰恰跑在 IO 调度器上。
     */
    fun prunePlaylist(removedIds: Collection<String>) {
        if (removedIds.isEmpty()) return
        val p = player ?: return
        val ids = removedIds.toHashSet()
        if (Looper.myLooper() == Looper.getMainLooper()) {
            pruneNow(p, ids)
        } else {
            main.post { pruneNow(p, ids) }
        }
    }

    private fun pruneNow(player: Player, ids: Set<String>) {
        var removed = 0
        // 倒着摘：删一项会让它后面所有项的下标往前移
        for (i in player.mediaItemCount - 1 downTo 0) {
            val id = player.getMediaItemAt(i).mediaId
            // 正在播的那条不该被摘（它本来也在 keep 集合里，这里是第二道保险）
            if (id == episodeId) continue
            if (id in ids) {
                player.removeMediaItem(i)
                removed++
            }
        }
        if (removed > 0) {
            DiagLog.i("Play", "已从播放队列摘掉 $removed 条被保留期清理的音频")
        }
    }
}
