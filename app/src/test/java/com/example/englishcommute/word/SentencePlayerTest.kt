package com.example.englishcommute.word

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `SentencePlayer.clampRange` —— 「从那期音频里取哪一段」这个纯计算。
 *
 * ## 为什么单独测这个函数
 *
 * [SentencePlayer] 本体要 `MediaPlayer` + `Looper`，在 JVM 单测里跑不起来。
 * 但**真正会出错的判断全在这个纯函数里**：
 * 时间戳与音频对不上、句子跨过音频末尾、时长还没解析出来……
 * 把这些从播放器里抽出来，就是为了让它们可测。
 *
 * ## 这些边界不是想出来的
 *
 * 三条都对应真实可能：
 *
 * | 边界 | 什么时候真的发生 |
 * |---|---|
 * | 时长未知（`duration <= 0`） | `prepare()` 之前问时长；或某些容器格式给不出总长 |
 * | 起点超出音频末尾 | 服务端换过一版音频、或字幕与音频来自不同版本 |
 * | 末尾超出音频末尾 | **很正常**：新闻最后一句经常正好卡在结尾 |
 */
class SentencePlayerTest {

    // =====================================================================
    // 一、正常区间
    // =====================================================================

    @Test
    fun `正常区间：起点从 start 开始，末尾提前 30ms 收住`() {
        val r = SentencePlayer.clampRange(1_000, 2_000, 60_000)!!
        assertEquals("起点就是时间戳", 1_000, r.first)
        // 末尾往前收 30ms，且 `until` 是半开的 ⇒ last = end - 30 - 1
        assertEquals("末尾收住（不把下一句的音头带进来）", 1_969, r.last)
    }

    @Test
    fun `句子卡在音频结尾时，夹到文件末尾而不是判定为坏数据`() {
        // 59000~61000 而音频只有 60000：这是「最后一句读到结尾」，很常见
        val r = SentencePlayer.clampRange(59_000, 61_000, 60_000)!!
        assertEquals(59_000, r.first)
        assertEquals("不能超出文件", 59_999, r.last)
    }

    @Test
    fun `负数起点夹到 0`() {
        val r = SentencePlayer.clampRange(-500, 1_000, 60_000)!!
        assertEquals("不许有负数位置", 0, r.first)
    }

    // =====================================================================
    // 二、不该播的情况（返回 null）
    // =====================================================================

    @Test
    fun `时长未知时不播 —— 而不是从头播`() {
        // ⚠️ 这条最重要。`duration <= 0` 时若「退化成从头播」，
        //    用户会以为「这句话在开头」，而它其实在 20 分钟处 ——
        //    那比什么都不播更糟：它是**错的**，而且用户不会怀疑它。
        assertNull(SentencePlayer.clampRange(1_000, 2_000, 0))
        assertNull(SentencePlayer.clampRange(1_000, 2_000, -1))
    }

    @Test
    fun `起止颠倒或长度为零时不播（数据坏了）`() {
        assertNull(SentencePlayer.clampRange(2_000, 1_000, 60_000))
        assertNull(SentencePlayer.clampRange(1_000, 1_000, 60_000))
    }

    @Test
    fun `整段落在音频之外时不播（时间戳与音频对不上）`() {
        assertNull(SentencePlayer.clampRange(70_000, 71_000, 60_000))
        // 起点正好等于时长也算越界（那一帧已经不存在了）
        assertNull(SentencePlayer.clampRange(60_000, 61_000, 60_000))
    }

    // =====================================================================
    // 三、退化情形：收完尾巴之后太短
    // =====================================================================

    @Test
    fun `极短句：收完 30ms 尾巴后不足 200ms，按原样播而不是不播`() {
        // 59900~60000 而音频 60000：收尾后只剩 70ms
        // 宁可按原样播（100ms），也不要因为「太短」就什么都不播 ——
        // 音频本来就只有那么长，那是内容的属性，不是错误
        val r = SentencePlayer.clampRange(59_900, 60_000, 60_000)!!
        assertEquals(59_900, r.first)
        assertEquals("不越过文件末尾", 59_999, r.last)
        // 关键：它**不为空**，也就是「还是播了」
        assertEquals("确实有内容可播", 99, r.last - r.first)
    }

    @Test
    fun `短句但高于阈值时仍走收尾那条路`() {
        // 1000~1300（300ms）：收尾后 270ms ≥ 200 ⇒ 正常收尾
        val r = SentencePlayer.clampRange(1_000, 1_300, 60_000)!!
        assertEquals(1_000, r.first)
        assertEquals(1_269, r.last)
    }

    // =====================================================================
    // 四、返回的区间一定非空（这是播放器的前提）
    // =====================================================================

    @Test
    fun `只要不是 null，返回的区间一定有内容`() {
        val cases = listOf(
            Triple(0L, 1L, 60_000),
            Triple(0L, 50L, 60_000),
            Triple(59_999L, 60_000L, 60_000),
            Triple(30_000L, 30_100L, 60_000),
            Triple(-1_000L, 50L, 60_000),
        )
        for ((s, e, d) in cases) {
            val r = SentencePlayer.clampRange(s, e, d)
            if (r != null) {
                // ⚠️ 判据是「first <= last」而不是「长度 > 200」——
                //    音频只有 100ms 时正确的区间就是 100ms 长。
                //    写死 200 会把上面那条极短句的用例判成失败。
                assert(r.first <= r.last) {
                    "区间不能为空：clampRange($s, $e, $d) = $r"
                }
                assert(r.first >= 0 && r.last < d) {
                    "区间必须在文件内：clampRange($s, $e, $d) = $r（音频 ${d}ms）"
                }
            }
        }
    }
}
