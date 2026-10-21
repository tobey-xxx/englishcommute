package com.example.englishcommute.word

import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import com.example.englishcommute.diag.DiagLog
import java.io.File

/**
 * 播「你听到的那一句」—— 从那期节目的音频里取 `[startMs, endMs]` 这一段。
 *
 * ## 为什么值得做（它不是「额外的一个按钮」）
 *
 * 一个词的记忆锚点是**语境**，而语境里最有力的部分是**声音**：
 * 用户当初就是在听那句新闻时决定要记这个词的。
 * 「听读音」给的是**孤立的标准发音**（TTS），而这里是**那句话里的那个词** ——
 * 连读、弱读、语调全都在。两者不是重复，是互补。
 *
 * ## 与 [PronPlayer] 必须**互斥**
 *
 * 两个都是 `MediaPlayer`，同时播就是**两个声音叠在一起**。
 * 这不是理论问题：背面这两个按钮并排，用户很容易先听读音、再听句子。
 * 所以两边都持有一个「我要开始了，你把声音让出来」的回调（容器里注入）。
 *
 * ## 为什么不像 [PronPlayer] 那样只播到文件末尾
 *
 * 这里播的是**一个片段**，播到 `endMs` 必须停 —— 否则用户听完这句
 * 会继续听到下一句、下下句，而他只是想确认这一句。
 * 用一个 `Handler.postDelayed` 到点停，比轮询位置简单且不需要帧级精度。
 *
 * ## 一条明确的局限
 *
 * `seekTo` 落在最近的关键帧上，对 MP3 可能有几十毫秒偏差。
 * 新闻播报里「这一句」的边界不需要毫秒精度，**所以刻意不做精确 seek**
 * （精确 seek 要么换解码器、要么预扫描，代价远大于收益）。
 * 但开头那半秒可能带上上一句的尾巴 —— 这是可以接受的。
 */
class SentencePlayer(
    /**
     * 「我要开始播了，请另一个播放器让出声音」。
     *
     * ⚠️ 用回调而不是直接持有 [PronPlayer] 实例：两者在容器里是**互相**引用的，
     *    直接持有就要处理初始化顺序；lambda 是延迟求值的，天然绕开。
     */
    private val onTakeOver: () -> Unit = {},
) {

    private var player: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var stopTask: Runnable? = null

    /**
     * 播 [file] 的 `[startMs, endMs]` 段。返回是否**真的开始播了**。
     *
     * 返回 false 的两种情形，界面都要说出来（不能静默）：
     * - 文件不在（那期音频过了保留期被清掉）
     * - 区间无效（起止颠倒、或整段落在音频长度之外）
     */
    fun play(file: File, startMs: Long, endMs: Long): Boolean {
        onTakeOver()
        release()

        if (!file.isFile || file.length() <= 0L) return false

        return try {
            val mp = MediaPlayer()
            mp.setDataSource(file.absolutePath)
            mp.prepare()

            val range = clampRange(startMs, endMs, mp.duration) ?: run {
                mp.release()
                DiagLog.w("Word", "句子区间无效：${startMs}~${endMs}（音频长 ${mp.duration}ms）")
                return false
            }

            mp.seekTo(range.first)
            mp.setOnErrorListener { p, what, extra ->
                DiagLog.w("Word", "原句播放失败：${file.name}（what=$what extra=$extra）")
                p.release()
                true
            }
            mp.start()
            player = mp

            // 到 endMs 停。**不用 OnCompletionListener** —— 那是「整首播完」，
            // 而我们要停在中间。
            val runFor = (range.last - range.first).coerceAtLeast(1)
            stopTask = Runnable { release() }.also { handler.postDelayed(it, runFor.toLong()) }
            true
        } catch (e: Exception) {
            DiagLog.w("Word", "原句播不出来：${file.name}（${e.javaClass.simpleName}）")
            release()
            false
        }
    }

    fun stop() = release()

    private fun release() {
        stopTask?.let { handler.removeCallbacks(it) }
        stopTask = null
        runCatching {
            player?.let { if (it.isPlaying) it.stop() }
            player?.release()
        }
        player = null
    }

    companion object {
        /**
         * 把 `[startMs, endMs]` 夹进 `[0, durationMs]`，并判断这段值不值得播。
         *
         * 返回 null = 别播了。三种情况：
         *
         * 1. **音频时长未知**（`duration <= 0`）—— 还没解析出来，位置判不了。
         *    这时**宁可什么都不播**，也不要「从头开始播」——
         *    后者会让用户以为「这句在开头」，而它其实在 20 分钟处。
         * 2. **起止颠倒或长度非正**（`end <= start`）—— 数据坏了。
         * 3. **整段落在音频之外**（`start >= duration`）—— 时间戳与音频对不上。
         *    这是真实可能的：服务端换过一次音频、或字幕与音频来自不同版本。
         *
         * ⚠️ 还有一条**不返回 null 而是夹住**的情况：`end` 超出音频末尾。
         *    那说明「这一句读到了结尾」，把它夹到文件末尾是**对的**，
         *    而不是「数据坏了」—— 新闻最后一句经常正好卡在结尾。
         *
         * ⚠️ 返回的区间**含头不含尾**会更好听：多播 `end` 那一下会把下一句的
         *    第一个音带进来。见 [MIN_PLAY_MS] 那一行。
         */
        fun clampRange(startMs: Long, endMs: Long, durationMs: Int): IntRange? {
            if (durationMs <= 0) return null
            if (endMs <= startMs) return null
            if (startMs >= durationMs) return null

            val s = startMs.coerceAtLeast(0L).toInt()
            // 末尾往前收 30ms：srt 的时间戳是「这句结束的那一刻」，
            // 播到那一刻会把下一句的第一个音头带进来（听感上像抢拍）。
            val e = (endMs - TAIL_TRIM_MS).coerceAtMost(durationMs.toLong()).toInt()
            if (e - s < MIN_PLAY_MS) {
                // 收完之后太短了（比如一个 80ms 的短句）——宁可按原样播，
                // 也不要因为「太短」就什么都不播。
                return s until durationMs.coerceAtMost(s + MIN_PLAY_MS)
            }
            return s until e
        }

        /** 短于这个就当没播（用户听到的只有一声咔哒） */
        private const val MIN_PLAY_MS = 200

        /** 末尾往前收的毫秒数，理由见 [clampRange] */
        private const val TAIL_TRIM_MS = 30
    }
}
