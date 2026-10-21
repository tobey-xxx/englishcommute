package com.example.englishcommute.ui

import com.example.englishcommute.update.UpdateInfo
import com.example.englishcommute.update.UpdateState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/**
 * 「设置」页签那个圆点的判断（[updateBadge]）。
 *
 * ## 为什么这件事需要单测
 *
 * 分两半看：
 *
 * **前半（六种状态各归哪一边）** 是纯逻辑，但它想错了完全不报错 ——
 * 漏掉一种状态，那个提示就永远不出现，而界面上一切正常。
 * 这一半在这里逐态点名。
 *
 * **后半（圆点会不会把图标挤偏）** 属于布局，只在真机上看得出来，
 * 所以它在 `tools/verify-update-badge.py` 里守（那边用的是同一个
 * `floatingOnIconCorner`，和数字角标共用）。这里不做重复的事。
 *
 * ## 这个测试最值钱的两条
 *
 * [「已经下好但还没装」也要有圆点] 与 [「下载失败」也要有圆点] ——
 * 它们是「有新版本才显示」那个直觉写法会**静默漏掉**的两态，
 * 而恰恰是用户最需要被提醒的时候（他已经动手了，只差最后一步）。
 */
class UpdateBadgeTest {

    private fun info(versionCode: Long = 57) = UpdateInfo(
        versionCode = versionCode,
        versionName = "3.2.0",
        url = "https://YOUR_DOMAIN/apk/EnglishCommute-3.2.0.apk",
        sizeBytes = 18_000_000L,
        sha256 = "0".repeat(64),
        minSupportedVersionCode = 0L,
        notes = "",
    )

    // ------------------------------------------------------------------
    // 不该有圆点
    // ------------------------------------------------------------------

    @Test
    fun `还没查到时不显示圆点`() {
        // ★ 这条最容易写反。「查不到」在断网时是**常态**，
        //   把它当成「可能没查成，先提示一下」的话，飞行模式下每次打开 App
        //   都会看到一个圆点 —— 而它指向的还是同一条永远查不出来的检查。
        //   角标要用烂的前提是它对得上现实（见 Updater 里那句
        //   「『查不到』不等于『已是最新』」）。
        assertNull(updateBadge(UpdateState.Unknown))
    }

    @Test
    fun `已是最新时不显示圆点`() {
        assertNull(updateBadge(UpdateState.UpToDate))
    }

    // ------------------------------------------------------------------
    // 该有圆点
    // ------------------------------------------------------------------

    @Test
    fun `服务器上有新版本时显示圆点`() {
        assertEquals(TabBadge.Dot, updateBadge(UpdateState.Available(info())))
    }

    @Test
    fun `「已经下好但还没装」也要有圆点`() {
        // ★ 这一态是「有新版本才显示」那个写法会漏掉的：
        //   Ready 时服务器上**确实**有新版本，所以按字面也对 ——
        //   但用户需要的不再是「知道有新版」（他知道了），
        //   而是「知道该点安装了」。两句话的区别就在这里。
        assertEquals(
            TabBadge.Dot,
            updateBadge(UpdateState.Ready(info(), File("/tmp/EnglishCommute-3.2.0.apk"))),
        )
    }

    @Test
    fun `正在下载时显示圆点`() {
        // 用户点了下载就去听东西了。回来时唯一的痕迹就是它 ——
        // 没有它，那个「正在下」的状态只存在于设置页里，
        // 而用户没有任何理由再进那个页面。
        assertEquals(
            TabBadge.Dot,
            updateBadge(UpdateState.Downloading(info(), doneBytes = 4_000_000L)),
        )
    }

    @Test
    fun `「下载失败」也要有圆点`() {
        // ★ 另一条会漏掉的。「失败」不是「没事发生」——
        //   用户点完就走了，而那次下载可能在后台悄悄失败。
        //   没有圆点，他就永远不知道自己还欠一个动作。
        assertEquals(
            TabBadge.Dot,
            updateBadge(
                UpdateState.Failed(info(), "下载的文件不完整（网络中途断了）。已经丢弃，再试一次")
            ),
        )
    }

    // ------------------------------------------------------------------
    // 形态
    // ------------------------------------------------------------------

    @Test
    fun `圆点不写数字`() {
        // 「有新版本」这件事只有一个，写「1」在语义上是错的
        // （用户会去找那「另外的」），而且数字角标会让人以为「点掉就少一个」。
        // 它是个**待办**，不是计数。用类型表达，别用字符串凑。
        val dot: TabBadge = updateBadge(UpdateState.Available(info()))!!
        assertEquals(TabBadge.Dot, dot)
    }

    @Test
    fun `数字角标仍然可以单独表达`() {
        // 生词那边的角标走的是同一个类型。保留这一条是为了让
        // 「两种形态都在」这件事在单测里有一份痕迹 ——
        // 哪天有人把 Count 删掉、只留 Dot，这里会先红。
        assertEquals(TabBadge.Count("3"), TabBadge.Count("3"))
        assertEquals("3", (TabBadge.Count("3") as TabBadge.Count).text)
    }
}
