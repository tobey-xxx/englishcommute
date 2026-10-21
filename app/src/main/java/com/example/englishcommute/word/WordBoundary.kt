package com.example.englishcommute.word

/**
 * 「手指点在字幕的哪个词上」—— 从字符偏移算出那个词的字符区间。
 *
 * ## 为什么这个纯函数值得单独一个文件 + 一组单测
 *
 * 它是长按选词的**全部智能所在**：`TextLayoutResult.getOffsetForPosition()`
 * 只告诉你「落在第几个字符」，剩下的事全在这里。而这个函数的错误**没有任何报错** ——
 * 用户长按 `signed`，却加进来一个 `sign`（截断）或者 `igned`（少一个字母），
 * 界面看起来完全正常，只是加错了词。等到他复习时才发现。
 *
 * 而且它的边界情况特别多：撇号、连字符、手指落在标点上、落在句末。
 *
 * ## 三条规则
 *
 * 1. **词字符** = 字母 + **词内部的**撇号/连字符。
 *    `that's`、`self-esteem` 是一个词；`Signed,` 后面的逗号不是。
 * 2. **手指落在标点或空格上**时，取**左右两侧最近的那个词的词字符**作为起点。
 *
 *    ⚠️ 不能简单地「往左找」或「往右找」—— 两种都会在常见情况下选错词：
 *    - `signed a` 的空格：往左得 `signed`、往右得 `a`。用户点偏了，
 *      他心里想的是离手指更近的那个 —— 距离最近才是对的。
 *    - `Today.` 的句号：往右什么也没有（句末），只能往左。
 *    - `"Hello"` 的开引号：往左什么也没有，只能往右。
 *
 *    平局时取左边（标点更常见的形态是跟在词尾）。
 * 3. 扩完边界后**剥掉首尾的**撇号/连字符。`'em` 应该变成 `em`，
 *    `well-` 不应该把连字符带进去（那种情况下连字符后面没有词字符了）。
 *
 * ⚠️ 与 `WordTable.normalize()` 的分工：这里只负责**切出词形**，
 * 不做大小写和引号归一化 —— 那一步在查词时统一做，避免两处规则打架。
 */
object WordBoundary {

    /** 词内部的连接符。直引号与弯引号都算 —— iOS 输入法默认给的是弯的 */
    private const val APOSTROPHES = "'\u2019\u2018\u02bc"
    private const val HYPHENS = "-\u2010\u2011"

    /**
     * ⚠️ `internal` 而不是 `private`：`ClozeBuilder` 要靠它切句子里同一个
     * 「词」的概念。**两处各写一份的话，会出现「长按能选中、却挖不出空」
     * 这种极难查的不一致** —— 所以要的是同一个函数，不是同一段逻辑。
     */
    internal fun isWordChar(c: Char): Boolean =
        c.isLetter() || c in APOSTROPHES || c in HYPHENS

    private fun isJoiner(c: Char): Boolean = c in APOSTROPHES || c in HYPHENS

    /**
     * @param offset 从 `TextLayoutResult.getOffsetForPosition()` 拿到的字符下标
     * @return 词在 [text] 里的下标区间（**两端闭合**）；整句里没有词字符时返回 null
     */
    fun rangeAt(text: String, offset: Int): IntRange? {
        if (text.isEmpty()) return null
        var seed = offset.coerceIn(0, text.length - 1)

        if (!isWordChar(text[seed])) {
            // 落在标点/空格上：找**左右最近**的词字符。规则与理由见文件头第 2 条
            var leftDist = Int.MAX_VALUE
            var rightDist = Int.MAX_VALUE
            var left = -1
            var right = -1

            var j = seed
            while (j >= 0) {
                if (isWordChar(text[j])) { left = j; leftDist = seed - j; break }
                j--
            }
            j = seed
            while (j < text.length) {
                if (isWordChar(text[j])) { right = j; rightDist = j - seed; break }
                j++
            }

            seed = when {
                left < 0 && right < 0 -> return null      // 整句里一个词字符都没有
                left < 0 -> right
                right < 0 -> left
                leftDist <= rightDist -> left              // 平局取左
                else -> right
            }
        }

        var start = seed
        while (start > 0 && isWordChar(text[start - 1])) start--
        var end = seed
        while (end < text.length - 1 && isWordChar(text[end + 1])) end++

        // 剥掉首尾的连接符 —— 它们只有在**夹在两个字母之间**时才算词的一部分
        while (start <= end && isJoiner(text[start])) start++
        while (end >= start && isJoiner(text[end])) end--
        if (start > end) return null

        return start..end
    }

    /** 手指底下那个词的文本。查词时直接用它 */
    fun wordAt(text: String, offset: Int): String? = rangeAt(text, offset)?.let { text.substring(it.first, it.last + 1) }
}
