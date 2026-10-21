package com.example.englishcommute.diag

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃守卫：把「上一次进程怎么死的」和「这一次崩在哪」都落到日志里。
 *
 * ## 为什么要专门查「上一次进程退出原因」
 *
 * 排查这个 App 的崩溃时，最大的困难是**看不出崩溃的性质**。三种情况用户看到的
 * 都只是「应用一闪就没了」，但修法完全不同：
 *
 * | 退出原因 | 真实含义 | 该往哪修 |
 * |---|---|---|
 * | `REASON_LOW_MEMORY` | 被系统内存回收杀掉 | 降内存峰值，**不是代码 bug** |
 * | `REASON_CRASH_NATIVE` | C++ 层段错误（sherpa 是 native） | 查 JNI 调用边界 |
 * | `REASON_CRASH` | JVM 未捕获异常 | 有完整堆栈，好办 |
 *
 * 其中 native 崩溃**拿不到 Java 堆栈**，而 `try/catch` 也永远拦不住它 ——
 * 所以「靠猜」是猜不出来的，必须问系统。Android 11 起
 * [ActivityManager.getHistoricalProcessExitInfos] 正好能回答这个问题，
 * 而且连 tombstone 的文本都能读（有无权限另说，读不到就算了）。
 *
 * ## 这个类捕获不到什么
 *
 * [Thread.setDefaultUncaughtExceptionHandler] **只对 JVM 异常有效**。
 * native 段错误、SIGKILL（LMK 用的就是它）都不会经过这里 ——
 * 那些情况唯一的线索是 [DiagLog] 里崩溃前的最后几行。
 * 两件事配合起来才完整：**这里回答「怎么死的」，日志回答「死之前在干什么」**。
 */
object CrashGuard {

    private const val PREFS = "diag_exit"
    private const val KEY_REASON = "last_exit_reason"
    private const val KEY_AT = "last_exit_at"
    private const val KEY_DETAIL = "last_exit_detail"
    private const val KEY_ABNORMAL = "last_exit_abnormal"
    private const val KEY_JVM_CRASH_COUNT = "jvm_crash_count"

    private const val TRACE_MAX_LINES = 120

    /**
     * 从二进制 trace 里捞字符串时的最短长度。
     *
     * 取 6：native / java 的栈帧名（`art::Thread::Park`、
     * `java.util.concurrent.locks.LockSupport.parkNanos`）都远长于它，
     * 而 protobuf 的字段噪音大多是 1–3 个字符的碎片。
     */
    private const val MIN_STRING_LEN = 6

    /**
     * 单条字符串的长度上限。
     *
     * proto 里偶尔会出现很长的连续 ASCII 段（一整块 buffer），
     * 不截断的话会写出几千字符的一行，把日志按行读的工具全带偏。
     * 符号名远用不到 240 个字符 —— 前面这段就够看清栈帧了。
     */
    private const val MAX_STRING_LEN = 240

    @Volatile
    private var lastExit: ExitReport? = null

    @Volatile
    private var installed = false

    /** 上一次进程退出信息。未查询到（首次安装、Android 10 及以下）为 null。 */
    fun lastExit(): ExitReport? = lastExit

    fun install(context: Context) {
        if (installed) return
        installed = true

        // 这一步是一次 binder 跨进程调用，而且跑在 Application.onCreate 的主线程上。
        // 绝大多数设备上是毫秒级，但「绝大多数」不等于「全部」——
        // 打点是为了万一遇到慢的机型，冷启动慢的原因能一眼看出来，
        // 而不是让人去怀疑是容器构造或者索引读取。
        val exitSpan = span("Exit", "读取上次退出原因")
        val report = runCatching { queryLastExit(context.applicationContext) }
            .getOrElse { e ->
                DiagLog.e("Exit", "读取上次退出信息失败", e)
                null
            }
        lastExit = report
        if (report != null) {
            report.writeToLog()
            exitSpan.done(report.reasonLabel)
        } else {
            exitSpan.done("无记录（首次安装，或 SDK < 30）")
        }

        installHandler(context.applicationContext)
    }

    /** 供设置页显示。冷启动时可能还没查到，返回 null 让界面显示「暂无记录」。 */
    fun lastExitSummary(context: Context): String? {
        lastExit?.let { return it.describe() }
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val reason = p.getString(KEY_REASON, null) ?: return null
        val at = p.getLong(KEY_AT, 0L)
        val detail = p.getString(KEY_DETAIL, "").orEmpty()
        return buildString {
            append(reason)
            if (at > 0) append(" · ").append(stamp(at))
            if (detail.isNotBlank()) append(" · ").append(detail)
        }
    }

    fun isLastExitAbnormal(context: Context): Boolean =
        lastExit?.isAbnormal
            ?: context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_ABNORMAL, false)

    fun jvmCrashCount(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_JVM_CRASH_COUNT, 0)

    // ------------------------------------------------------------------
    // JVM 未捕获异常
    // ------------------------------------------------------------------

    private fun installHandler(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // 处理器内部绝不能再抛异常 —— 那样系统只会看到「处理崩溃时又崩了」，
            // 原始的崩溃信息反而丢了。整个 body 用 runCatching 兜住。
            runCatching { onUncaught(context, thread, throwable) }
            // 交回给系统默认处理器：正常弹出「应用已停止」并结束进程。
            // 不吞掉是刻意的 —— 我们只负责记录，不负责掩盖。
            previous?.uncaughtException(thread, throwable)
        }
        DiagLog.i("Crash", "已接管未捕获异常（JVM 层）")
    }

    private fun onUncaught(context: Context, thread: Thread, t: Throwable) {
        val summary = "${t::class.java.simpleName}: ${t.message ?: "无消息"}"

        DiagLog.crashBanner(
            "JVM CRASH",
            buildString {
                append("线程: ").append(thread.name).append('\n')
                append("异常: ").append(summary).append('\n')
                append("时间: ").append(stamp(System.currentTimeMillis())).append('\n')
                append("运行: ").append(String.format(Locale.US, "%.1fs", DiagLog.uptimeSec())).append('\n')
                append("上次退出: ").append(lastExit?.reasonLabel ?: "未知").append('\n')
                append(memoryLine())
            },
        )
        DiagLog.crash("Crash", summary, t)

        // 崩溃瞬间的应用状态 —— 没有它，只知道「崩了」，不知道「停在哪个条目上」
        runCatching { DiagLog.snapshotProvider?.invoke() }
            .getOrNull()
            ?.lineSequence()
            ?.filter { it.isNotBlank() }
            ?.forEach { DiagLog.crash("State", it) }

        runCatching {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            p.edit()
                .putInt(KEY_JVM_CRASH_COUNT, p.getInt(KEY_JVM_CRASH_COUNT, 0) + 1)
                .apply()
        }
    }

    // ------------------------------------------------------------------
    // 上次退出原因
    // ------------------------------------------------------------------

    private fun queryLastExit(context: Context): ExitReport? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return queryLastExitR(context)
    }

    /**
     * 列表按时间倒序返回**已结束**的进程记录，第一项就是上一次会话。
     * 当前进程还在跑，不会出现在里面。
     */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun queryLastExitR(context: Context): ExitReport? {
        val am = context.getSystemService(ActivityManager::class.java) ?: return null
        // ⚠️ 方法名是 getHistoricalProcessExit**Reasons**，不是 ...ExitInfos ——
        //    后者根本不存在，只是读起来更顺口，很容易记错。
        // 三个参数是 (包名, pid, 最多几条)：
        //   · 包名传 null —— 表示「我自己这个包」，不需要任何权限
        //   · pid 传 0 —— 不按 pid 过滤（上次崩溃那个 pid 早就没了，过滤必然是空）
        //   · maxNum 传 8 —— 多取几条，这样即使上一轮是正常退出，
        //     也能往回找到更早那一次真正的异常退出
        val info = am.getHistoricalProcessExitReasons(null, 0, 8).firstOrNull() ?: return null

        val reason = info.reason
        val meta = reasonInfo(reason)
        return ExitReport(
            reasonCode = reason,
            reasonLabel = meta.label,
            description = info.description.orEmpty().take(120),
            atMillis = info.timestamp,
            isAbnormal = !meta.normal,
            trace = readTrace(info),
        )
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun readTrace(info: ApplicationExitInfo): String? = runCatching {
        // tombstone / ANR trace 读不到是常态（需要 READ_LOGS 或 root），
        // 所以这里失败完全正常，静默返回 null
        info.traceInputStream?.use { ins ->
            printableStrings(ins.readBytes())
        }?.takeIf { it.isNotBlank() }
    }.getOrNull()

    /**
     * 从 trace 字节里捞出可打印的字符串。
     *
     * ## ⚠️ `traceInputStream` 不是文本，是 **protobuf 二进制**
     *
     * 原先这里写的是 `bufferedReader().useLines {}`，后果有两个，都很糟：
     *
     * 1. **它会往日志里写 NUL 字节。** 而 ripgrep / grep 一旦在文件里见到 NUL，
     *    就**把整个文件判定为二进制并停止匹配** —— 从此这份日志什么关键词都搜不到，
     *    而且完全看不出是怎么废掉的。0.12.0 那份真机日志就是这样：
     *    我拿到手时 ripgrep 只回一句「binary file matches」，一整个文件搜不出任何东西。
     * 2. 它写出 120 行、约 60KB 的乱码，占掉日志文件六成体积。日志是滚动覆盖的，
     *    等于把真正有用的记录挤出去。
     *
     * 解码 proto 需要它的 schema（系统私有），所以这里反过来做：
     * **扫出长度达标的连续可打印 ASCII 段**。崩溃类型（`SIGSEGV`、`SEGV_MAPERR`、
     * `null pointer dereference`）、故障地址、以及 art / java 的栈帧名本来就在
     * 那堆字节里，只是被噪音埋住了 —— 捞出来之后比原先那种乱码有用得多，
     * 而且不再污染日志文件。
     */
    private fun printableStrings(bytes: ByteArray, maxLines: Int = TRACE_MAX_LINES): String {
        val out = ArrayList<String>(maxLines)
        val sb = StringBuilder()
        for (b in bytes) {
            val c = b.toInt() and 0xFF
            if (c in 0x20..0x7E) {
                sb.append(c.toChar())
                continue
            }
            if (sb.length >= MIN_STRING_LEN) out.add(sb.toString().take(MAX_STRING_LEN))
            sb.setLength(0)
            if (out.size >= maxLines) break
        }
        if (out.size < maxLines && sb.length >= MIN_STRING_LEN) out.add(sb.toString().take(MAX_STRING_LEN))
        return out.joinToString("\n")
    }

    /** 退出原因的说明文案 + 是否算「异常」。两者一起给，避免两份 case 列表走偏。 */
    private data class ReasonInfo(val label: String, val normal: Boolean)

    /**
     * `REASON_EXIT_SELF` 也算正常：进程自杀在 Android 上是完全正常的收尾方式
     * （系统在内存压力下回收空进程走的也是这条路）。把它当异常会让「上次退出原因」
     * 天天报警，真正的异常反而被淹没。
     */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun reasonInfo(code: Int): ReasonInfo = when (code) {
        ApplicationExitInfo.REASON_EXIT_SELF -> ReasonInfo("主动退出", true)
        ApplicationExitInfo.REASON_USER_REQUESTED -> ReasonInfo("用户主动关闭", true)
        ApplicationExitInfo.REASON_USER_STOPPED -> ReasonInfo("用户强制停止", true)
        ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> ReasonInfo("包状态变更", true)
        ApplicationExitInfo.REASON_PACKAGE_UPDATED -> ReasonInfo("应用被更新", true)

        ApplicationExitInfo.REASON_LOW_MEMORY -> ReasonInfo("内存不足被系统回收", false)
        ApplicationExitInfo.REASON_CRASH_NATIVE -> ReasonInfo("Native 层崩溃", false)
        ApplicationExitInfo.REASON_CRASH -> ReasonInfo("Java 层崩溃", false)
        ApplicationExitInfo.REASON_ANR -> ReasonInfo("无响应（ANR）", false)
        ApplicationExitInfo.REASON_SIGNALED -> ReasonInfo("被信号杀死", false)
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> ReasonInfo("资源占用过高", false)
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> ReasonInfo("初始化失败", false)
        ApplicationExitInfo.REASON_FREEZER -> ReasonInfo("被系统冻结后清理", false)
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> ReasonInfo("依赖进程死亡", false)
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> ReasonInfo("权限变更", false)
        ApplicationExitInfo.REASON_OTHER -> ReasonInfo("其他原因", false)
        else -> ReasonInfo("未知（$code）", false)
    }

    // ------------------------------------------------------------------

    fun memoryLine(): String {
        val rt = Runtime.getRuntime()
        val usedMb = (rt.totalMemory() - rt.freeMemory()) / 1048576
        val totalMb = rt.totalMemory() / 1048576
        val maxMb = rt.maxMemory() / 1048576
        return "Java 堆 ${usedMb}/${totalMb} MB（上限 ${maxMb} MB）"
    }

    private fun stamp(millis: Long): String =
        SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date(millis))

    data class ExitReport(
        val reasonCode: Int,
        val reasonLabel: String,
        val description: String,
        val atMillis: Long,
        val isAbnormal: Boolean,
        val trace: String?,
    ) {
        fun describe(): String = buildString(120) {
            append(reasonLabel)
            if (atMillis > 0) append(" · ").append(stamp(atMillis))
            if (description.isNotBlank()) append(" · ").append(description)
        }

        /** 写进日志。**这是排查的第一步** —— 打开日志第一眼就知道上次怎么死的。 */
        fun writeToLog() {
            val level = if (isAbnormal) "⚠️ 异常退出" else "正常退出"
            DiagLog.i("Exit", "上次进程：$level · ${describe()}")
            if (isAbnormal) {
                DiagLog.w(
                    "Exit",
                    "若本次启动前发生过闪退，原因即上方那条；" +
                        "对应的场景日志在崩溃标记（= JVM CRASH =）之前",
                )
            }
            trace?.let {
                DiagLog.i("Exit", "上次退出 trace（前 ${it.lines().size} 行）")
                it.lineSequence().forEach { l -> DiagLog.i("  │", l) }
            }
        }

        private companion object {
            fun stamp(millis: Long): String =
                SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date(millis))
        }
    }
}
