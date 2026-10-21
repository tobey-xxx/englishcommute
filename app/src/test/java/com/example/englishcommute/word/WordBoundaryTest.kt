package com.example.englishcommute.word

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 「手指落在哪个词上」。
 *
 * 这个函数的错误形态最阴险：**不会崩、不会报错**。用户长按 `signed`
 * 却加进来 `signe`，界面看起来完全正常，等他几天后复习时才发现加错了词。
 * 而那时候已经想不起来是哪一步错了。
 */
class WordBoundaryTest {

    /** 真实字幕句（P0 语料里那一期）。用 indexOf 定位而不是硬编码下标，改句子时不会全乱 */
    private val S = "Greenland and Denmark signed a security agreement today."

    private fun at(word: String, delta: Int = 0) = S.indexOf(word) + delta

    // =====================================================================
    // 一、落在字母上 —— 最常见的情况
    // =====================================================================

    @Test
    fun `点在词首、词中、词尾都取到同一个词`() {
        assertEquals("signed", WordBoundary.wordAt(S, at("signed")))
        assertEquals("signed", WordBoundary.wordAt(S, at("signed") + 3))
        assertEquals("signed", WordBoundary.wordAt(S, at("signed") + 5))
        assertEquals("Greenland", WordBoundary.wordAt(S, 0))
        assertEquals("today", WordBoundary.wordAt(S, at("today") + 4))
    }

    @Test
    fun `每次返回值都真的是原句里的一段`() {
        // 守一个低级但致命的错误：区间算错一位，substring 出来的词就不在原句里
        for (i in S.indices) {
            val w = WordBoundary.wordAt(S, i) ?: continue
            assertEquals("偏移 $i 切出来的「$w」必须是原句的子串", true, S.contains(w))
        }
    }

    // =====================================================================
    // 二、撇号与连字符 —— 词的一部分，但只在**内部**时
    // =====================================================================

    @Test
    fun `内部撇号是一个词的一部分`() {
        val t = "That's why I'm here."
        assertEquals("That's", WordBoundary.wordAt(t, t.indexOf("That")))
        assertEquals("That's", WordBoundary.wordAt(t, t.indexOf("'s") + 1))
        assertEquals("I'm", WordBoundary.wordAt(t, t.indexOf("I'm")))
    }

    @Test
    fun `弯引号与直引号等价`() {
        // iOS 输入法默认给的是 U+2019，字幕里两种都可能出现
        val t = "That\u2019s all."
        assertEquals("That\u2019s", WordBoundary.wordAt(t, t.indexOf("That")))
    }

    @Test
    fun `内部连字符是一个词的一部分`() {
        val t = "It was a self-esteem issue."
        assertEquals("self-esteem", WordBoundary.wordAt(t, t.indexOf("self")))
        assertEquals("self-esteem", WordBoundary.wordAt(t, t.indexOf("esteem")))
    }

    @Test
    fun `词首的词首撇号会被剥掉`() {
        // `'em` 要变成 `em` —— ECDICT 里没有 `'em`
        val t = "Give 'em a break."
        assertEquals("em", WordBoundary.wordAt(t, t.indexOf("'em") + 1))
    }

    @Test
    fun `词尾的连字符不算词的一部分`() {
        // 破折号连接两个从句的情况：`wait-` 应该只取 `wait`
        val t = "We had to wait- and it was long."
        assertEquals("wait", WordBoundary.wordAt(t, t.indexOf("wait")))
    }

    // =====================================================================
    // 三、★ 落在标点或空格上 —— 取左右**更近**的那个
    // =====================================================================

    @Test
    fun `点在句末的句号上取左边那个词`() {
        assertEquals("today", WordBoundary.wordAt(S, S.indexOf(".")))
    }

    @Test
    fun `点在词尾的逗号上取左边那个词`() {
        val t = "Greenland, and Denmark."
        assertEquals("Greenland", WordBoundary.wordAt(t, t.indexOf(",")))
    }

    @Test
    fun `点在句首的引号上取右边那个词`() {
        val t = "\"Hello\" he said."
        assertEquals("Hello", WordBoundary.wordAt(t, 0))
        assertEquals("Hello", WordBoundary.wordAt(t, t.indexOf("Hello") + 5))
    }

    @Test
    fun `点在空格上取更近的那个词`() {
        // 第一个空格在 Greenland 和 and 之间，两边距离都是 1 -> 平局取左
        assertEquals("Greenland", WordBoundary.wordAt(S, S.indexOf(" ")))
        // `signed a` 之间的空格：左边 signed 的距离 1，右边 a 的距离 1 -> 平局取左
        val gap = S.indexOf("signed") + "signed".length
        assertEquals("signed", WordBoundary.wordAt(S, gap))
    }

    @Test
    fun `连续多个标点时也能跳到最近的词`() {
        val t = "Yes . . . maybe."
        // 点在最后那个点上，左边最近的词字符在 `maybe` 里
        assertEquals("maybe", WordBoundary.wordAt(t, t.length - 1))
    }

    // =====================================================================
    // 四、边界与抗崩
    // =====================================================================

    @Test
    fun `空串和纯标点返回 null`() {
        assertNull(WordBoundary.wordAt("", 0))
        assertNull("整句里一个词字符都没有时不该硬凑出一个词", WordBoundary.wordAt(" . , ! ", 2))
    }

    @Test
    fun `偏移越界不会崩`() {
        // TextLayoutResult 在极端情况下可能给出 -1 或 length，不能让 App 崩在这一下
        assertEquals("Greenland", WordBoundary.wordAt(S, -5))
        assertEquals("today", WordBoundary.wordAt(S, 9999))
    }

    @Test
    fun `单个字母的词也能取到`() {
        // `a` 只有一个字符，扩边界的两个 while 都要能正确停住
        assertEquals("a", WordBoundary.wordAt(S, S.indexOf(" a ") + 1))
    }

    @Test
    fun `数字不算词字符`() {
        // 词表里没有数字词，切出来也查不到 —— 不如根本不认它
        val t = "It was 1995 and cold."
        assertEquals("It", WordBoundary.wordAt(t, t.indexOf("It")))
        assertEquals("and", WordBoundary.wordAt(t, t.indexOf("and")))
    }

    @Test
    fun `rangeAt 给出的区间能原样切回那个词`() {
        for (w in listOf("Greenland", "Denmark", "signed", "security", "agreement", "today")) {
            val r = WordBoundary.rangeAt(S, at(w))!!
            assertEquals(w, S.substring(r.first, r.last + 1))
        }
    }
}
