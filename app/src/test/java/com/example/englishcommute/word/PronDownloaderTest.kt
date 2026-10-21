package com.example.englishcommute.word

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * `PronDownloader` 里**不涉及网络**的那部分：路径、判断、删除。
 *
 * ## 为什么不测 `ensure()`
 *
 * `ensure()` 要发 HTTP 请求（而且还要覆盖 404 / 超时 / 截断三种分支）。
 * 那属于**集成测试** —— 它的真实验收是「在服务端生成完读音之后，
 * 装上 App、加一个词、听到声音」，那个动作在 `3.0.0-重点测试清单.md` 里。
 *
 * 这里只守纯文件操作：它们的错误**没有任何报错**（文件写到别的地方去了，
 * 界面只是永远显示「读音加载中」），而且成本极低。
 */
class PronDownloaderTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("pron-test").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun downloader() = PronDownloader(dir)

    @Test
    fun `file 的路径跟 pronFileName 对得上`() {
        val f = downloader().file("don't")
        assertEquals("don_t.mp3", f.name)
        assertEquals(dir, f.parentFile)
    }

    @Test
    fun `has 对不存在和空文件都返回 false`() {
        val d = downloader()
        assertFalse("文件不存在", d.has("sign"))

        // ⚠️ 空文件必须也算「没有」：下载中途被杀会留下 0 字节的文件，
        //    而 `exists()` 会为它返回 true —— 界面就会显示一个
        //    「点了没声音」的按钮（比显示「还没下好」糟得多）。
        d.file("sign").writeText("")
        assertFalse("0 字节的文件不算已就绪", d.has("sign"))

        d.file("sign").writeText("假的音频")
        assertTrue("有内容才算", d.has("sign"))
    }

    @Test
    fun `delete 只删那一个词`() {
        val d = downloader()
        d.file("sign").writeText("a")
        d.file("clash").writeText("b")

        d.delete("sign")

        assertFalse("删掉的那个没了", d.file("sign").exists())
        assertTrue("别的不能被顺手带走", d.file("clash").exists())
    }

    @Test
    fun `delete 一个不存在的词不抛异常`() {
        // 移出生词本时会调它，而那个词可能从来没下过读音
        // —— 这是**常见路径**，不是异常路径。
        downloader().delete("never-downloaded")
    }
}
