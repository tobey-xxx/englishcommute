package com.example.englishcommute.word

import com.example.englishcommute.diag.DiagLog

/**
 * 给生词本里**还没有读音**的词补下载。
 *
 * ## 为什么需要它
 *
 * 读音是按需下载的（`PronDownloader.ensure`），而下载会失败：
 *
 * | 场景 | 那一刻为什么不成功 | 什么时候能补上 |
 * |---|---|---|
 * | 地铁里加词（没信号） | 请求发不出去 | 有网之后 |
 * | 加词时服务端还没生成这个词的读音 | 404 | 服务端生成之后（跟着素材循环走） |
 * | 加词时音频正在下、网络正忙 | 超时 | 网络空下来之后 |
 *
 * 三种都不是「用户做错了什么」，所以**不能让他自己去重试**。
 * 原来的实现只有「打开词卡时下这一次」，失败就停在 `PronState.Failed` ——
 * 用户看到「读音暂不可用」，而它其实几分钟后就能好。
 * 于是有了这个类：**在有网的时候（同步收尾）把缺的补上**。
 *
 * ## 一次只补一小批（[PER_RUN]）
 *
 * 因为触发时机是「每轮同步」，而不是「只此一次」。一次补 8 个的好处是：
 * ① 不给同步收尾那一刻加太长的尾巴（每个 11 KB，8 个不到 100 KB）；
 * ② 用户有 300 个词缺读音时，不会一次性打 300 个请求（那既慢又像攻击）。
 *
 * 代价是「300 个词要 38 轮同步才补完」。可以接受 —— 因为**缺的大概率是旧的**、
 * 而且下一轮就补到「今天要复习的那几个」了（见下面的排序）。
 *
 * ## 先补哪一个：**到期 > 新加的**
 *
 * 排序不是随便定的，它决定这个功能有没有用：
 *
 * 1. **今天要复习的排最前** —— 复习卡正面有一个「听读音」按钮，
 *    而那正是用户唯一会用到的时刻。补了 200 个用不上的，不如先补这 3 个。
 * 2. 其次是**最近加的词**（`addedAt` 倒序）。刚加的词最可能还没下成
 *    （用户加完就关 App 了），而旧的早在之前的轮次里补过了。
 *
 * ⚠️ 两条都不是「按字母序」那种想当然的顺序 —— 前者对齐用户的实际动作，
 *    后者对齐「哪一批最不可能是补过的」。
 *
 * ## 与 `PronDownloader` 里那份「负缓存」的关系
 *
 * `PronDownloader` 自己有一个 `notPublished` 集合：**本进程内**确认过服务端
 * 还没有的词，不再重复请求。这个类**继承**它的结论（`isKnownMissing` 过滤掉），
 * 而不是绕开它 —— 否则那些词会白占每轮的名额。
 *
 * 为什么这样就够了：负缓存是**进程内**的，重启即失效。而服务端的读音是
 * 分批补齐的（`cycle.py` 的 `refresh_pron`），所以「今天没有」不等于「明天没有」——
 * 用户在通勤路上加的词标着「还在补」，第二天打开 App 就会重新问一遍，
 * 那时大概率已经有了。
 *
 * ⚠️ 所以**不要**把 `notPublished` 改成持久化 —— 那会让「服务端后来补上了」
 *    永远修不回来，而这恰恰是这个类存在的理由。
 */
class PronBackfill(
    private val store: WordStore,
    private val pron: PronDownloader,
    private val perRun: Int = PER_RUN,
) {

    /**
     * 补一批。返回**真的下成功**的个数（不是「尝试了几个」）。
     *
     * 上限 [perRun]，缺的没那么多时就是实际个数，一个都不缺时返回 0 且不发请求。
     */
    suspend fun run(): Int {
        // ⚠️ 两个条件缺一不可：
        //    `has` = 本地还没有；
        //    `isKnownMissing` = 本进程内已经问过、服务端返回过 404。
        //    少了第二个，那些「问了也是 404」的词会白占本轮的 [perRun] 名额 ——
        //    而补漏机制的全部价值就在于**把有限的名额给到真能补上的词**。
        val missing = store.cards.value.filter { !pron.has(it.lemma) && !pron.isKnownMissing(it.lemma) }
        if (missing.isEmpty()) return 0

        // 今天要复习的那批排最前 —— 判据是 store 自己算的 dueCards，
        // 不在这里重算一遍（两处算「今天到期」必然分叉，而那个规则有一个
        // 很容易写错的点：按「今天 24:00 前到期」而不是「此刻已到期」）。
        val dueLemmas = store.dueCards().mapTo(HashSet()) { it.lemma }

        val ordered = missing.sortedWith(
            compareByDescending<WordCard> { it.lemma in dueLemmas }
                // 同一档内：新加的优先。用 addedAt 倒序而不是「列表顺序」——
                // 后者是渲染用的顺序，会随筛选变，不是稳定依据。
                .thenByDescending { it.addedAt },
        )
        // ⚠️ 上面第一档（到期优先）在当前实现下**不产生可观测差异**：
        //    `WordStore` 在 `add()` 和 `load()` 两处都把卡片按 `dueAtMillis` 升序存，
        //    而「到期」的判据是 `dueAt < 今天 24:00` —— 所以到期的那些天然排在前面。
        //    （坏样本验证过：只保留 `thenByDescending { addedAt }`，测试全绿。）
        //
        //    那为什么还留着它？因为**存储顺序是一个隐含前提**，而这里要的是
        //    「名额先给今天要用的词」这个**语义**。把语义写成代码，下次谁调整了
        //    存储顺序，这一档会自己兜住；不写的话，那件事没有任何判据守着。
        //    代价只是排序时多比较一次布尔 —— 值得。

        var ok = 0
        for (card in ordered.take(perRun)) {
            // 单个失败**不中断整批**：一个词的读音也没有，不该挡住后面 7 个。
            when (pron.ensure(card.lemma)) {
                is PronState.Ready -> ok++
                // Failed / 其它：留给下一轮。这里刻意不记日志 ——
                // 缺读音是常态（服务端还没轮到），每个词记一行会把日志淹掉。
                else -> Unit
            }
        }
        if (ok > 0) {
            DiagLog.i("Word", "补下 $ok 个词的读音（缺 ${missing.size} 个，本轮上限 $perRun）")
        }
        return ok
    }

    companion object {
        /** 每轮同步最多补几个。理由见类注释「一次只补一小批」 */
        const val PER_RUN = 8
    }
}
