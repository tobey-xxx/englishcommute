package com.example.englishcommute.word

import android.media.MediaPlayer
import com.example.englishcommute.diag.DiagLog
import java.io.File

/**
 * 播放一个词的读音（本地 mp3，约 11 KB、1 秒左右）。
 *
 * ## 为什么是裸 `MediaPlayer` 而不是 ExoPlayer
 *
 * ExoPlayer（media3）是为**长音频 + 队列 + 后台播放**设计的，它在这个项目里的
 * 正当用途是那一期播客。一个 1 秒的音效片断用它是杀鸡用牛刀：
 * 建 ExoPlayer 实例、准备、释放，每个环节都比文件本身大。
 *
 * ## 三条必须做的事
 *
 * 1. **每次播放前先释放上一个。** 用户连点「听读音」很常见，不释放会叠着播。
 * 2. **`release()` 要能被外部调用**（复习页退出时）—— 否则离开页面后
 *    播放器还攥着一个文件描述符。
 * 3. **`play()` 返回 Boolean**，让界面能区分「播了」和「这个文件播不了」。
 *    ⚠️ 前者为 false 时不能静默 —— 文件存在但解码失败（下载被截断、
 *    或者服务端生成的 mp3 有问题）是真实存在的情况，
 *    界面要说出来，否则用户读到的只是「点了没反应」。
 */
class PronPlayer(
    /**
     * 「我要开始播了，请另一个播放器让出声音」（3.0.4 加）。
     *
     * ⚠️ 和 [SentencePlayer] 是**互相**的：复习卡背面那两个按钮并排，
     *    用户很容易「先听读音、再听句子」—— 不互斥就是两个声音叠在一起。
     *    用回调而不是直接持有对方实例，是为了绕开容器里的初始化顺序
     *    （lambda 延迟求值）。
     */
    private val onTakeOver: () -> Unit = {},
) {

    private var player: MediaPlayer? = null

    /**
     * 播一个文件。返回是否成功开始播放。
     *
     * **同步**的：一个 11 KB 的本地文件 `prepare()` 是毫秒级，
     * 为它开协程反而要把「播完了没有」的状态传回来，更复杂。
     */
    fun play(file: File): Boolean {
        onTakeOver()
        release()
        if (!file.exists() || file.length() <= 0L) return false
        return try {
            val mp = MediaPlayer()
            mp.setDataSource(file.absolutePath)
            mp.prepare()
            mp.setOnCompletionListener { it.release() }
            mp.setOnErrorListener { p, what, extra ->
                DiagLog.w("Word", "读音播放失败：${file.name}（what=$what extra=$extra）")
                p.release()
                true
            }
            mp.start()
            player = mp
            true
        } catch (e: Exception) {
            // 解码失败 / 文件损坏。**不抛给调用方**，但要让调用方知道失败了
            DiagLog.w("Word", "读音播不出来：${file.name}（${e.javaClass.simpleName}）")
            player?.release()
            player = null
            false
        }
    }

    /**
     * 停止播放。给「另一个播放器要开始播了」那条路径用（3.0.4）。
     *
     * ⚠️ 这里 `stop()` 和 `release()` **是同一件事**：本类不保留闲置的实例
     *    （每次 `play` 都新建、播完就释放），所以「停」就是「放掉」。
     *    留两个名字是因为调用方的意图不同 —— 一个是「我不播了」，
     *    一个是「你让一下」。把意图写进名字里，读代码的人就不用想。
     */
    fun stop() = release()

    fun release() {
        runCatching {
            player?.let { if (it.isPlaying) it.stop() }
            player?.release()
        }
        player = null
    }
}
