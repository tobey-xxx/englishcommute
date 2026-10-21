package com.example.englishcommute.player

import com.example.englishcommute.content.Episode

/**
 * 播放队列的排序规则。
 *
 * ## 要解决的问题
 *
 * 队列原本就是「所有能播的内容，按发布时间倒序」—— 于是 ExoPlayer 播完一条
 * 自动推进到下一条时，推进目标只看**时间**，不看**听没听过**。
 *
 * 通勤场景下这是必然出问题的一条路径：早上有 11 条新内容，用户听掉 3 条；
 * 第二天又来了 11 条，他点开最新那条往下听 —— 听完 3 条新的之后，
 * **自动接上的是昨天已经听完的那几条**，而且他自己什么都没做，
 * 只是把手机揣在兜里。用户的原话是「建议自动播放下一条尚未听完的」。
 *
 * ## 规则
 *
 * ```
 * [ 未听完的 · 发布时间倒序 ] + [ 已听完的 · 发布时间倒序 ]
 * ```
 *
 * 也就是把「听没听过」提到「时间」前面当第一关键字。
 * 于是「下一条」这个词在队列里的含义自动变成了「下一条还没听完的」——
 * 不需要在播放器的回调里写「跳过已听完」那种补丁，也不需要自己接管
 * 播完之后的推进逻辑（ExoPlayer 的 `setMediaItems` 会一路走到底）。
 *
 * ## 三个刻意的取舍
 *
 * 1. **已听完的不移出队列，只往后排。** 移出去的话，用户想回听某一条就只能
 *    去素材库里找；而「上一集 / 下一集」两个按钮也会在边界上突然失效。
 *    排在后面意味着「全部听完之后，它会接着往下放更早的内容」——
 *    不会静默停住让人以为坏了。
 * 2. **两个分组内部仍按时间倒序。** 新内容优先这条直觉不能丢：同是没听完的
 *    两条，先给新的。
 * 3. **排序只在这里发生，而且只在「打开某一期」时算一次。** 播放中途
 *    某条变成「已听完」不会让队列当场重排 —— 队列在用户脚下变化，
 *    比顺序不完美糟糕得多（`hasNext`/`hasPrevious` 会跟着闪）。
 *
 * ## 判据为什么是「听完」而不是「听到哪」
 *
 * [com.example.englishcommute.settings.PlaybackPositions] 里「听完」是一个
 * 显式写下的标记（播到结尾时写），而「听到哪」是每 5 秒刷一次的断点。
 * 拿断点当判据的话，听了 10 秒就退出的内容会被当成「听过」而排到后面 ——
 * 那恰恰是最该优先补上的一条。**只有二值的标记能做排序关键字。**
 *
 * ## 为什么做成不依赖 Android 的纯函数
 *
 * 和 [com.example.englishcommute.settings.SpeedPolicy] 同样的理由：
 * 排序错了不会崩、不会报错，只是「怎么又播了一遍昨天那条」这种
 * 说不清道不明的体验问题。抽出来之后可以在桌面上逐条复刻边界
 * （全听过 / 全没听过 / 混排 / 并列时间 / 空列表），
 * 见 `tools/verify-playback-queue.py`。
 */
object PlaybackQueue {

    /**
     * 把「能播的内容」排成播放顺序。
     *
     * @param playable 已经筛过「音频文件真的在盘上」的内容，顺序不敏感
     * @param doneIds 已听完的 id 集合（`PlaybackPositions.done` 的当前值）
     */
    fun order(playable: List<Episode>, doneIds: Set<String>): List<Episode> {
        if (playable.isEmpty()) return playable

        val (unfinished, finished) = playable.partition { it.id !in doneIds }
        // 全都没听过（最常见的一种）时不必排两次 —— sortedByDescending 虽稳，
        // 但这里是每次打开一集都会走的热路径
        if (finished.isEmpty()) return unfinished.sortedByDescending { it.publishedAt }
        if (unfinished.isEmpty()) return finished.sortedByDescending { it.publishedAt }

        return unfinished.sortedByDescending { it.publishedAt } +
            finished.sortedByDescending { it.publishedAt }
    }
}
