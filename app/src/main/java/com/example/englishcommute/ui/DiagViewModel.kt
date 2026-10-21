package com.example.englishcommute.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.englishcommute.EnglishCommuteApp
import com.example.englishcommute.diag.CrashGuard
import com.example.englishcommute.diag.DiagLog
import com.example.englishcommute.player.NowPlaying
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 诊断页顶部的概览。
 *
 * 刻意把「上次退出原因」放进来而不是藏在日志里：它是判断问题性质的**第一个分叉**——
 * 「内存不足被系统回收」和「Native 层崩溃」在用户眼里都是「闪退」，
 * 但一个要降内存峰值、一个要查 JNI 边界，修法完全不同。
 */
data class DiagSummary(
    val totalBytes: Long = 0L,
    val lineCount: Int = 0,
    val errorCount: Int = 0,
    val files: List<Pair<String, Long>> = emptyList(),
    val lastExit: String? = null,
    val lastExitAbnormal: Boolean = false,
    val jvmCrashes: Int = 0,
) {
    val sizeLabel: String get() = formatDiagBytes(totalBytes)
}

/**
 * 日志行的级别判定。
 *
 * 日志格式是 `HH:mm:ss.SSS +12.345s [线程] L tag 消息`，其中 `L` 是 D/I/W/E。
 * 不用「行里是否含 ` E `」这种判断 —— 消息正文完全可能恰好包含它，
 * 那种误判在过滤错误时会很烦人。这里锁死 `]` 之后那一格。
 */
private val LEVEL_RE = Regex("""]\s([DIWE])\s""")

internal fun String.diagLevel(): Char =
    LEVEL_RE.find(this)?.groupValues?.get(1)?.firstOrNull() ?: 'I'

/**
 * 是否算「错误行」。
 *
 * 除了 `E` 级日志，还要算上**堆栈行**（续行，以 `│` 开头）和**崩溃横幅**——
 * 只看 `E` 会把堆栈全部滤掉，而堆栈恰恰是最该看的部分。
 */
internal fun String.isDiagError(): Boolean {
    val t = trimStart()
    if (t.startsWith("│")) return true
    if (this.contains("=====") && this.contains("CRASH")) return true
    return diagLevel() == 'E'
}

class DiagViewModel(app: Application) : AndroidViewModel(app) {

    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    private val _errorsOnly = MutableStateFlow(false)
    val errorsOnly: StateFlow<Boolean> = _errorsOnly.asStateFlow()

    private val _summary = MutableStateFlow(DiagSummary())
    val summary: StateFlow<DiagSummary> = _summary.asStateFlow()

    init {
        refresh()
    }

    /**
     * 从**磁盘**读，而不是只读内存环形缓冲。
     *
     * 环形缓冲只装得下本次会话的一千多条 —— 而用户想看的多半正好是
     * 「上一次闪退之前发生了什么」，那部分在上一轮进程里就被挤出缓冲了。
     * 磁盘文件是跨会话连续追加的，所以它才是完整时间线。
     */
    fun refresh() {
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                DiagLog.readFromDisk(READ_LINES).ifEmpty { DiagLog.recent() }
            }
            _lines.value = loaded
            _summary.value = withContext(Dispatchers.IO) { buildSummary(loaded) }
        }
    }

    fun toggleErrorsOnly() {
        _errorsOnly.value = !_errorsOnly.value
    }

    /** 手动记一次当前状态。用户报问题前点一下，我们要的上下文就都在日志里了。 */
    fun captureState() {
        DiagLog.dumpState("用户手动记录")
        refresh()
    }

    fun clear() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { DiagLog.clear() }
            refresh()
        }
    }

    /**
     * 保留期演练：把「没收藏」的音频按「下载于 [TEST_DAYS] 天前」重算一遍，
     * 然后走一次**正常的**清理路径。
     *
     * ## 为什么放在诊断页
     *
     * 因为它是**验证工具**而不是功能：保留期的第一次真清理最早在装机三天后，
     * 想当场看到效果就得有人为地把时间戳往前推。这种「会删用户文件」的动作
     * 放在设置页当功能会被误触，放在诊断页（用户已经在看日志、目的就是排查）
     * 才合适，而且要两次点击确认。
     *
     * 保护名单与启动清理一致（收藏 + 正在播）—— 演练必须复现线上判据，
     * 否则「收藏的不会被删」这条就没被验到。
     */
    fun simulateRetention() {
        viewModelScope.launch {
            runCatching {
                val container = (getApplication<Application>() as EnglishCommuteApp).container
                val repo = container.repository
                val keep = buildSet {
                    addAll(container.favorites.ids.value)
                    NowPlaying.episodeId?.let { add(it) }
                }
                val n = withContext(Dispatchers.IO) {
                    repo.rewindRetentionForTest(TEST_DAYS, keep)
                }
                val expired = withContext(Dispatchers.IO) {
                    repo.expireOldAudio(System.currentTimeMillis(), keep)
                }
                // 与线上一样：删完要修剪播放队列，否则连播到已删文件会报「播放失败」
                NowPlaying.prunePlaylist(expired.ids)
                DiagLog.w(
                    "Diag",
                    "保留期演练结果：改动 $n 条 → 清掉 ${expired.count} 条" +
                        "（${expired.bytes / 1048576} MB）" +
                        " · 收藏 ${keep.size} 条受保护",
                )
            }.onFailure { DiagLog.e("Diag", "保留期演练失败：${it.message}") }
            refresh()
        }
    }

    private fun buildSummary(shown: List<String>): DiagSummary {
        val ctx = getApplication<Application>()
        return DiagSummary(
            totalBytes = DiagLog.totalBytes(),
            lineCount = shown.size,
            errorCount = shown.count { it.isDiagError() },
            files = DiagLog.files().map { it.name to it.length() },
            lastExit = CrashGuard.lastExitSummary(ctx),
            lastExitAbnormal = CrashGuard.isLastExitAbnormal(ctx),
            jvmCrashes = CrashGuard.jvmCrashCount(ctx),
        )
    }

    private companion object {
        /** 读太多会拖慢页面打开；一千多行足够覆盖一次完整同步 + 一次崩溃 */
        const val READ_LINES = 1500

        /** 演练时把下载时间往前推几天。比保留期（3 天）多一天，确保判据一定命中 */
        const val TEST_DAYS = 4L
    }
}

internal fun formatDiagBytes(bytes: Long): String = when {
    bytes >= 1048576 -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576.0)
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}
