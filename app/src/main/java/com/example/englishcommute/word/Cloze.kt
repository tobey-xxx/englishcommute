package com.example.englishcommute.word

/**
 * 挖空：把「你听到的那句话」里要复习的那个词挖掉，剩下的给用户当回忆线索。
 *
 * ## 为什么复习卡用挖空，而不是「看词想义」
 *
 * 孤立地看一个 `sign` 想「签名」跟听力没关系 —— 用户真正需要的能力是
 * **在语流里听出这个词**。所以正面的形态必须是那句话本身，空出的位置
 * 就是该出现这个词的位置，用户回忆的是「这里应该是什么」。
 *
 * ## 这个函数为什么值得单测
 *
 * 和 [WordBoundary] 同一类：**错了没有任何报错**。
 * 挖空位置偏一个词，用户看到的是一句「空看起来很怪但语法也说得通」的句子，
 * 他会以为是自己记错了，而不是怀疑卡片挖错了。
 *
 * 而且它有真实的边界：词在句首（`before` 为空）、词在句末、
 * 句子里的形态带标点（`signed,`）、大小写不同（`Signed`）、
 * 同一个词在一句里出现两次。
 *
 * ## 退化路径（[build] 返回 null 时）
 *
 * 找不到目标词时**不是错误**，这里如实返回 null，由界面决定怎么办。
 * 目前的处理是：显示完整句子 + 目标词，提示「回忆这个词的意思」——
 * 也就是把考「拼写/听辨」降级成考「词义」。**不编一个假的挖空位置**。
 */
data class Cloze(
    /** 挖空前的部分（原样保留，含它和空之间的空格与标点） */
    val before: String,
    /** 句中的**实际形态**（`signed`，不是原形 `sign`）—— 背面高亮的就是它 */
    val answer: String,
    /** 挖空后的部分 */
    val after: String,
) {
    /** 正面显示用的：`Greenland and Denmark ______ a security agreement…` */
    fun masked(blank: String = "______"): String = before + blank + after
}

object ClozeBuilder {

    /**
     * 在 [sentence] 里找第一个与 [surface] 相等的词，把它挖掉。
     *
     * 比较用 [WordTable.normalize]（弯引号→直引号、剥首尾标点空白、转小写），
     * 所以 `Signed,` 能找到 `signed`。**与查词用的是同一套归一化** ——
     * 两处规则不一致的话，会出现「查得到这个词、却挖不出这个空」这种极难查的错。
     *
     * @return 找不到时 null（见文件头的退化路径）
     */
    fun build(sentence: String, surface: String): Cloze? {
        val target = WordTable.normalize(surface)
        if (target.isEmpty() || sentence.isEmpty()) return null

        var i = 0
        while (i < sentence.length) {
            if (!WordBoundary.isWordChar(sentence[i])) {
                i++
                continue
            }
            val start = i
            while (i < sentence.length && WordBoundary.isWordChar(sentence[i])) i++
            val token = sentence.substring(start, i)
            if (WordTable.normalize(token) == target) {
                return Cloze(
                    before = sentence.substring(0, start),
                    answer = token,
                    after = sentence.substring(i),
                )
            }
        }
        return null
    }
}
