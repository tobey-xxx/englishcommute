package com.example.englishcommute.player

/** 一条句级字幕，时间是毫秒 */
data class SubtitleLine(
    val index: Int,
    val startMs: Long,
    val endMs: Long,
    val text: String,
) {
    val durationMs: Long get() = endMs - startMs
}

/**
 * SRT 解析与「播放位置 → 当前句」的索引。
 *
 * ## 为什么不用 Media3 的 SubripParser
 *
 * 规划阶段本来打算照搬 `SubripParser`（「不自造轮子」），真读它的 API 之后放弃了：
 * Media3 的 `Cue` **不含时间字段**。它的解析模型是「按当前播放位置解析出此刻该显示的 cue」，
 * 即面向「一次只显示一行」的电视字幕场景。
 *
 * 而本 App 要的是**整篇文稿铺在屏幕上、当前句高亮、自动滚动、点击任意句跳转**——
 * 这需要拿到全部句段的完整时间轴。用 `Cue` 的话每 250 ms 都得重新解析一遍整个文件，
 * 还得自己维护「这行对应哪个序号」，反而比直接解析更复杂。SRT 格式本身极简单
 * （空行分隔的块：序号 / 时间码 / 文本），几十行就能写完且完全可控。
 *
 * 2.1.0 起 SRT 由**服务器**生成（`faster-whisper` + 静音点切块），
 * 客户端只做解析。它的时间码格式与端侧那套实现产物**不完全一样**
 * （服务端用的是标准 `HH:MM:SS,mmm`，靠的是同一个 ffmpeg 风格的约定），
 * 所以这里的解析器按宽松规则写、而不是按「我们自己生成的格式」写 ——
 * 这条纪律在换实现的那一天救了命，别收窄它。
 */
object SrtTrack {

    /**
     * 时间码。刻意放宽：
     *  - 小时位允许 1–2 位（有的工具不给小时补零）
     *  - 毫秒位允许 1–3 位（`00:00:01.5` 这种也认）
     *  - 分隔符允许 `,` 或 `.`（两种写法都常见）
     */
    private val TIMECODE = Regex(
        """(\d{1,3}):(\d{2}):(\d{2})[,.](\d{1,3})\s*-->\s*(\d{1,3}):(\d{2}):(\d{2})[,.](\d{1,3})"""
    )

    /**
     * 解析 SRT 文本。
     *
     * 容错优先：任何一块只要时间码读不出来就整块丢弃，**不影响其他块**。
     * 宁可少几句，也不能因为一行畸形导致整集没字幕。
     */
    fun parse(raw: String): List<SubtitleLine> {
        if (raw.isBlank()) return emptyList()

        // 统一换行符：文件可能是 CRLF（Windows 工具导出过就是），
        // 不统一的话行尾会残留 \r，直接显示在字幕末尾变成一个小方块
        val normalized = raw.replace("\r\n", "\n").replace('\r', '\n')
        val blocks = normalized.split(Regex("\n\\s*\n"))

        val out = ArrayList<SubtitleLine>(blocks.size)
        for (block in blocks) {
            val lines = block.trim('\n').lines().filter { it.isNotBlank() }
            if (lines.isEmpty()) continue

            // 第 1 行通常是序号，但也可能是时间码（没有序号的非标 SRT）
            val timecodeIdx = lines.indexOfFirst { TIMECODE.containsMatchIn(it) }
            if (timecodeIdx < 0) continue

            val m = TIMECODE.find(lines[timecodeIdx]) ?: continue
            val startMs = toMillis(m.groupValues[1], m.groupValues[2], m.groupValues[3], m.groupValues[4])
            val endMs = toMillis(m.groupValues[5], m.groupValues[6], m.groupValues[7], m.groupValues[8])

            val text = lines.drop(timecodeIdx + 1).joinToString("\n").trim()
            if (text.isEmpty()) continue

            // 末尾补齐：endMs <= startMs 的畸形块会让「当前句」永远匹配不上，
            // 给一个最短停留时间，至少能闪一下而不是整条消失
            val safeEnd = if (endMs > startMs) endMs else startMs + 300

            out.add(SubtitleLine(out.size, startMs, safeEnd, text))
        }
        return out
    }

    /**
     * 播放位置 → 当前句下标。找不到返回 -1（字幕为空时）。
     *
     * 二分查找，O(log n)。**不用线性扫描**：这个函数会被 250 ms 的轮询反复调用，
     * 一集 Up First 有 150+ 句，线性扫描就是每次 150 次比较 × 每秒 4 次，
     * 白白占着主线程。
     */
    fun indexAt(lines: List<SubtitleLine>, positionMs: Long): Int {
        if (lines.isEmpty()) return -1

        var lo = 0
        var hi = lines.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            val line = lines[mid]
            when {
                positionMs < line.startMs -> hi = mid - 1
                positionMs > line.endMs -> lo = mid + 1
                else -> return mid
            }
        }
        // 落在两句之间的空隙（SegmentBuilder 刻意留了 20 ms 间隔防止一帧双高亮）。
        // 取前一句：播放头刚从上一句走出去，视觉上延续上一句比跳到下一句更自然。
        // lo == 0 表示还在第一句之前（通常是音频开头的前奏），clamp 到第 0 句。
        return (lo - 1).coerceIn(0, lines.size - 1)
    }

    private fun toMillis(h: String, m: String, s: String, ms: String): Long {
        val hour = h.toLongOrNull() ?: 0L
        val min = m.toLongOrNull() ?: 0L
        val sec = s.toLongOrNull() ?: 0L
        // 毫秒位按位数补齐：`5` → 500ms，`05` → 50ms，`005` → 5ms
        val milli = when (ms.length) {
            1 -> ms.toLongOrNull()?.times(100) ?: 0L
            2 -> ms.toLongOrNull()?.times(10) ?: 0L
            else -> ms.toLongOrNull() ?: 0L
        }
        return ((hour * 60 + min) * 60 + sec) * 1000 + milli
    }

    /** 毫秒 → `m:ss`，用于进度条两侧的时间显示 */
    fun formatClock(ms: Long): String {
        val total = (ms.coerceAtLeast(0L)) / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }
}
