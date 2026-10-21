package com.example.englishcommute.word

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 挖空：在「你听到的那句话」里找到要复习的那个词，把它变成空。
 *
 * ## 为什么值得一组单测
 *
 * 和 [WordBoundary] 同一类 —— **错了没有任何报错**。挖空位置偏一个词，
 * 用户看到的是一句「空的位置有点怪、但语法也说得通」的句子，
 * 他会以为是自己记错了，而不会怀疑卡片挖错了。
 *
 * 而且它有真实的边界：词在句首 / 句末、句中的形态带标点、大小写不同、
 * 同一个词在一句里出现两次、以及**找不到**（这时必须返回 null 而不是瞎猜）。
 */
class ClozeTest {

    private val sentence = "Greenland and Denmark signed a security agreement today while in New York."

    @Test
    fun `句中的词被挖掉，前后原样保留`() {
        val c = ClozeBuilder.build(sentence, "signed")
        assertEquals("Greenland and Denmark ", c!!.before)
        assertEquals("signed", c.answer)
        assertEquals(" a security agreement today while in New York.", c.after)
    }

    @Test
    fun `正面显示成一句带空的句子`() {
        val c = ClozeBuilder.build(sentence, "signed")!!
        assertEquals("Greenland and Denmark ______ a security agreement today while in New York.", c.masked())
    }

    @Test
    fun `词在句首时 before 为空`() {
        val c = ClozeBuilder.build("Signed today while in New York.", "signed")!!
        assertEquals("", c.before)
        assertEquals("Signed", c.answer)          // ⚠️ 保留句中的大小写
        assertEquals(" today while in New York.", c.after)
    }

    @Test
    fun `词在句末时 after 为空`() {
        val c = ClozeBuilder.build("The two sides signed", "signed")!!
        assertEquals("The two sides ", c.before)
        assertEquals("signed", c.answer)
        assertEquals("", c.after)
    }

    @Test
    fun `句中的形态带标点也能找到 —— 标点留在 after 里`() {
        val c = ClozeBuilder.build("They signed, then left the room.", "signed")!!
        assertEquals("They ", c.before)
        assertEquals("signed", c.answer)          // 逗号不属于这个词
        assertEquals(", then left the room.", c.after)
    }

    @Test
    fun `大小写不敏感 —— 但答案保留句中的写法`() {
        // ⚠️ `answer` 是**句中的实际形态**，不是传进来的 target。
        //    背面要高亮那句话里原样的词（用户要找的是「刚才那个空里原来是什么」），
        //    所以句首的 `Signed` 下面给出的答案就该是 `Signed`，不是 `signed`。
        assertEquals("SIGNED", ClozeBuilder.build("SIGNED today.", "signed")!!.answer)
        assertEquals("Signed", ClozeBuilder.build("Signed today.", "SIGNED")!!.answer)
        assertEquals("signed", ClozeBuilder.build("they signed it", "SIGNED")!!.answer)
    }

    @Test
    fun `自己的变形写法也能对上（查词那一层的 surface）`() {
        // ⚠️ 传进来的通常是 user 点到的形态（signed），而不是原形（sign）。
        //    传原形时会**挖不出来** —— 这是正确的：句子里写的就是 signed。
        val bySurface = ClozeBuilder.build(sentence, "signed")
        assertEquals("signed", bySurface!!.answer)
        assertNull("句中没有 sign 这个词，所以原形挖不出来（如实返回 null）",
            ClozeBuilder.build(sentence, "sign"))
    }

    @Test
    fun `撇号词是一个整体`() {
        val c = ClozeBuilder.build("I don't know what to say.", "don't")!!
        assertEquals("I ", c.before)
        assertEquals("don't", c.answer)
        assertEquals(" know what to say.", c.after)
    }

    @Test
    fun `连字符词也是一个整体`() {
        val c = ClozeBuilder.build("It is a well-known fact.", "well-known")!!
        assertEquals("It is a ", c.before)
        assertEquals("well-known", c.answer)
    }

    @Test
    fun `同一个词出现两次时挖第一个`() {
        val c = ClozeBuilder.build("Signed here, signed there.", "signed")!!
        assertEquals("", c.before)
        assertEquals("Signed", c.answer)
        assertEquals(" here, signed there.", c.after)
    }

    @Test
    fun `找不到时返回 null —— 不编一个假的位置`() {
        // 这一条是**契约**：调用方（复习卡）拿到 null 会退化成「显示完整句子 + 给词」，
        // 而不是显示一个空的、位置错误的句子。
        assertNull(ClozeBuilder.build(sentence, "helicopter"))
        assertNull(ClozeBuilder.build("", "signed"))
        assertNull(ClozeBuilder.build(sentence, ""))
        // 只给了标点，归一化后是空串
        assertNull(ClozeBuilder.build(sentence, "..."))
    }

    @Test
    fun `句子里的标点不会让「找词」越界`() {
        val c = ClozeBuilder.build("(signed)", "signed")!!
        assertEquals("(", c.before)
        assertEquals("signed", c.answer)
        assertEquals(")", c.after)
    }
}
