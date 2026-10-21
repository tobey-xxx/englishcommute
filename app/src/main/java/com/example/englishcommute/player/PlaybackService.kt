package com.example.englishcommute.player

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.example.englishcommute.EnglishCommuteApp
import com.example.englishcommute.MainActivity
import com.example.englishcommute.R
import com.example.englishcommute.checkin.ListeningClock
import com.example.englishcommute.diag.DiagLog

/**
 * 播放服务：ExoPlayer + MediaSession 的宿主。
 *
 * ## 为什么播放器要放在 Service 里
 *
 * 通勤场景的真实用法是「点播放 → 锁屏揣兜」。
 * 如果播放器挂在 Activity 的 ViewModel 上，锁屏后 Activity 被回收，音乐就断了。
 * [MediaSessionService] 是 Media3 给出的标准形态：它把播放器放到前台服务里，
 * 自动维护通知栏（播放/暂停/进度）与蓝牙耳机按键响应 —— 这些自己写要几百行，
 * 而且各家 ROM 的保活规则各不相同。
 *
 * ## 为什么 UI 侧不直接持有 ExoPlayer
 *
 * UI 通过 `MediaController` 连服务（见 [com.example.englishcommute.ui.PlayerViewModel]）。
 * 这样「界面在不在」和「声音在不在」彻底解耦：退出播放页音频继续，
 * 回到界面时控制器重连拿到的仍是同一个播放位置。
 */
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null

    /** 上一次的播放器状态码。只记**迁移**，相邻重复不记（见监听器里的说明） */
    private var lastPlaybackState: Int = Player.STATE_IDLE

    override fun onCreate() {
        super.onCreate()
        DiagLog.i("Play", "播放服务创建 · 通知 ID ${EnglishCommuteApp.NOTIFY_ID_PLAYBACK}")

        val player = ExoPlayer.Builder(this)
            // 显式声明是「语音内容」：系统的音频焦点与降噪策略会据此调整 ——
            // 播客/新闻不该像音乐那样在来电时被完全压掉，而是按语音规则处理
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            // 拔耳机 / 断蓝牙 → 自动暂停。通勤路上没有比这更刚需的行为
            .setHandleAudioBecomingNoisy(true)
            // 屏幕熄灭后 CPU 仍要跑（下载转写时同理，这里是解码）
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()

        session = MediaSession.Builder(this, player)
            .setSessionActivity(openAppIntent())
            .build()

        // 播放状态迁移入日志（1.2.5）。
        //
        // ## 为什么在这里记，而不是在界面上
        //
        // 这里是**唯一**权威的播放状态来源：锁屏、蓝牙耳机、通知栏按钮、自动连播，
        // 全都走它；而界面（PlayerViewModel）只是它的一层视图，界面不在的时候
        // 什么都记不到。用户报「听着听着断了」时，需要的正是界面不存在那段时间的记录。
        //
        // 只记**迁移**（播放↔暂停、缓冲、播完、报错），不记进度 —— 进度每 250 ms 变一次。
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                DiagLog.act(if (isPlaying) "▶ 开始播放" else "⏸ 停止播放")
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                // 缓冲态是「听着听着卡住」的直接证据，值得记；但相邻重复不记
                if (playbackState == lastPlaybackState) return
                lastPlaybackState = playbackState
                when (playbackState) {
                    Player.STATE_BUFFERING -> DiagLog.act("播放器缓冲中…")
                    Player.STATE_READY -> DiagLog.act("播放器就绪")
                    Player.STATE_ENDED -> DiagLog.act("本集播完")
                    Player.STATE_IDLE -> DiagLog.act("播放器空闲（无媒体/已释放）")
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                // 「播放失败：文件不存在」这类问题的唯一线索就在这里 ——
                // 界面上的错误文案是给人看的，日志里这条是给排查用的
                DiagLog.e("Play", "播放出错 · ${error.errorCodeName} · ${error.message}", error)
            }
        })

        // 让「正在播的是哪一条」可被其他地方读到 —— 保留期清理靠它避开手里的文件句柄
        NowPlaying.attach(player)
        // 听课时长统计挂在同一个播放器上：息屏后台播同样要计入打卡
        ListeningClock.attach(player)

        // 用独立的「播放控制」通知渠道，和下载/同步的进度通知分开 ——
        // 用户可以单独静音其中一个。
        //
        // ⚠️ `setSmallIcon` 是 provider 的**实例方法**，不在 Builder 上
        //    （media3 1.11.1 实测：Builder 只有 setNotificationId / setChannelId /
        //    setChannelName / setNotificationIdProvider）。不设的话会退回用
        //    ApplicationInfo.icon —— 那是个自适应图标，被通知栏取 alpha 通道后
        //    会变成一整块白方块。
        val notificationProvider = DefaultMediaNotificationProvider.Builder(this)
            .setChannelId(EnglishCommuteApp.CHANNEL_PLAYBACK)
            // ★ 显式钉住通知 ID（1.2.5）。
            //
            // 不传的话它取 `DEFAULT_NOTIFICATION_ID`（= 1001），而同步服务那边的
            // 进度通知当时也写的是 1001 —— **同一条通知**：一跑更新，正在播的那条
            // 播放控件就被顶掉；同步收尾的 cancel 还会把它整个撤走。
            //
            // 传的值和 media3 的默认值相同，这里写出来是为了**让这处依赖可见**：
            // 改 [EnglishCommuteApp.NOTIFY_ID_PLAYBACK] 必须与库的默认值一致。
            .setNotificationId(EnglishCommuteApp.NOTIFY_ID_PLAYBACK)
            .build()
        notificationProvider.setSmallIcon(R.drawable.ic_stat_notify)
        setMediaNotificationProvider(notificationProvider)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /**
     * 用户从最近任务里划掉 App 时调用。
     *
     * 正在播就继续播（通勤用户划掉界面的意图通常是「不想看界面」而不是「别出声」）；
     * 已经暂停/播完了就顺手退出，不留在后台占内存。
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        ListeningClock.detach()
        NowPlaying.clear()
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
