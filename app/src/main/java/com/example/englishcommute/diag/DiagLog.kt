package com.example.englishcommute.diag

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * 诊断日志。
 *
 * ## 为什么不用 `android.util.Log`
 *
 * logcat 只在连着电脑、且**在崩溃的那一刻正在抓**的时候有用。而这个 App 的
 * 主要故障场景恰好相反：用户揣着手机通勤，出了问题回到工位时已经过去几小时，
 * logcat 环形缓冲早就被冲掉了；就算接上 `adb logcat`，也只能拿到「之后」的日志。
 * 所以必须**自己落盘**。
 *
 * ## 三条硬约束
 *
 * 1. **崩溃时最后几条不能丢。** 普通异步写日志有个致命缺陷：进程突然死掉时，
 *    还在内存里的那几条恰恰是最关键的（「最后一次调用了什么」）。这里普通日志走
 *    单线程 executor，而 [crash] 路径**同步写 + flush**，保证落盘。
 *    另外不做 fsync —— `flush()` 已经把数据交给内核了，进程被 LMK 杀掉不会丢，
 *    只有内核崩溃/断电才丢，那种情况也不差这几行日志。
 *
 * 2. **日志本身不能成为故障源。** 所有 IO 都在 `diag-log` 线程上，调用方永不阻塞；
 *    单条消息截断到 [MAX_MSG_CHARS]；单文件超过 [MAX_BYTES] 滚动，最多留
 *    [MAX_ROLL] 份历史 —— 总量封死在 2 MB 量级；任何 IO 异常都被吞掉并把
 *    sink 置空，绝不让写日志失败连累主流程（否则就成了「为了 debug 而制造崩溃」）。
 *
 * 3. **必须能回答「崩溃前在干什么」。** 这是 native 崩溃**唯一**可行的定位手段
 *    —— C++ 层的 SIGSEGV 走不到 JVM 的异常处理器，拿不到堆栈，
 *    但能拿到崩溃前最后一条日志。
 *
 *    ⚠️ 2.1.0 之后包里**已经没有 native 代码**了（端侧识别与翻译一起拆掉），
 *    这条理由变成「将来谁再加原生依赖时仍然成立」的约定。
 *    打点纪律不变：**按阶段/按条**记，不按进度记 —— 每 400 ms 一次会把
 *    真正有用的上下文淹掉。
 *
 * ## 格式
 *
 * ```
 * 22:09:31.442 +0.312s [main         ] I App       会话开始
 * ```
 *
 * `+0.312s` 是**进程启动后**的秒数，不是时钟。排查崩溃时要算「这两步隔了多久」，
 * 用它不会被系统时间跳变（NTP 校时、用户改时间）骗到。
 */
object DiagLog {

    private const val FILE_NAME = "diag.log"
    /**
     * 单文件上限与保留份数（1.2.5 提高）。
     *
     * 原来是 512 KB × 3。而 1.2.5 起「所有用户操作」都进日志（见 [act]）——
     * 一次通勤（听 40 分钟、点十几下、跑几轮转写）大约几十 KB，
     * 加上转写链路的按块打点，一天就能吃掉旧上限的整份历史。
     *
     * 排查一次「昨天下午那事儿」需要的正是**跨会话**的上下文，
     * 所以宁可多留：1 MB × 4 + 当前 = 5 MB 封顶。对手机存储是噪音级别，
     * 而它换来的是一条能回溯几天的完整时间线。
     */
    private const val MAX_BYTES = 1024L * 1024L
    private const val MAX_ROLL = 4
    private const val RING_CAPACITY = 3000
    private const val MAX_MSG_CHARS = 2000
    private const val STACK_MAX_LINES = 40
    private const val THREAD_WIDTH = 13
    private const val TAG_WIDTH = 9

    /** 「用户操作」通道的 tag。固定成一个常量，方便事后按 `I Act` 整段捞出来 */
    private const val TAG_ACTION = "Act"

    private val bootAt = SystemClock.elapsedRealtime()
    private val seq = AtomicLong(0)

    private val lock = Any()
    private val ring = ArrayDeque<String>(RING_CAPACITY)

    private val writer = Executors.newSingleThreadExecutor { r ->
        Thread(r, "diag-log").apply { isDaemon = true }
    }

    private val clock = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    }

    @Volatile
    private var logDir: File? = null

    @Volatile
    private var sink: FileOutputStream? = null

    @Volatile
    private var written = 0L

    @Volatile
    private var installed = false

    /**
     * 崩溃时由 [CrashGuard] 调用，返回一段「当前应用状态」快照。
     *
     * 做成钩子而不是让 DiagLog 依赖 Container：容器还没构造完时日志就得能用
     * （否则启动阶段的崩溃就记不下来），两者的初始化顺序不能互相绑死。
     */
    @Volatile
    var snapshotProvider: (() -> String)? = null

    // ------------------------------------------------------------------
    // 安装
    // ------------------------------------------------------------------

    /**
     * 幂等。**必须在 `Application.onCreate` 的最开头调用** ——
     * 容器构造（会读索引、会对账状态）本身就可能崩，那正是最需要日志的时候。
     */
    fun install(context: Context) {
        if (installed) return
        installed = true

        val d = File(context.filesDir, "diag").apply { mkdirs() }
        logDir = d

        synchronized(lock) {
            val cur = File(d, FILE_NAME)
            written = if (cur.isFile) cur.length() else 0L
            // 上次会话没超过上限就不用滚，保持时间线连续 ——
            // 「崩溃前后各发生了什么」必须在同一个文件里，滚走了就没法对照
            rollIfNeededLocked()
            openLocked()
        }

        i("DiagLog", "日志启动 · ${environment(context)}")
    }

    private fun environment(context: Context): String {
        val pkg = runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0)
        }.getOrNull()
        val code = when {
            pkg == null -> "?"
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> pkg.longVersionCode.toString()
            else -> @Suppress("DEPRECATION") pkg.versionCode.toString()
        }
        return buildString(200) {
            append("v").append(pkg?.versionName ?: "?").append('(').append(code).append(") · ")
            // 安装时间。**这一项存在的唯一理由是防止「用户以为装了新版、其实还在跑旧包」。**
            // 曾经为此白查一整轮：用户报的三个转写问题全部发生在 0.9.0 上，
            // 而 0.9.1 起就已经修完 —— 但日志头只写版本号，
            // 看到 v0.9.0 时我更倾向「怀疑代码」而不是「怀疑包没装上」。
            // 有了这一项，一眼就能分清「我发的版本」和「你装的版本」。
            append("装于 ").append(BuildInfo.installStamp(context)).append(" · ")
            append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append(" · ")
            append("Android ").append(Build.VERSION.RELEASE)
            append("(SDK ").append(Build.VERSION.SDK_INT).append(") · ")
            append(Build.SUPPORTED_ABIS.firstOrNull() ?: "?")
        }
    }

    // ------------------------------------------------------------------
    // 对外写入
    // ------------------------------------------------------------------

    fun d(tag: String, msg: String) = emit(render('D', tag, msg), Log.DEBUG, tag)

    fun i(tag: String, msg: String) = emit(render('I', tag, msg), Log.INFO, tag)

    /**
     * **用户操作**（1.2.5）。
     *
     * ## 为什么单开一条通道
     *
     * 原先的日志只记「系统做了什么」：拉了几个源、转写到第几块、模型有没有释放。
     * 但用户报问题时的第一句话是「我点了 X，然后 Y」—— 而 X **根本没进日志**。
     * 于是排查只能靠猜「他大概是点的那里吧」，那是效率最低的一段。
     *
     * 现在所有界面动作（更新、检查源、转写、收藏、删除、切筛选、播放控制、
     * 切页签、开关）都走这里，格式固定成 `I Act` 一行。拿到日志后
     * 按 `I Act` 一捞就是一条**完整的操作时间线**，和系统事件按时间戳对齐，
     * 「点了什么 → 系统做了什么 → 结果如何」三段一次看全。
     *
     * ## 记什么、不记什么
     *
     * - 记：**离散的用户动作**（点一下算一次），以及它的关键参数（哪一条、切到哪个筛选）
     * - 不记：每帧都在变的东西（进度百分比、播放位置、滚动位置）。
     *   那些要么已有节流版本（见 `滚动条位置`），要么会把日志淹掉 ——
     *   而被淹掉的日志等于没有日志
     */
    fun act(what: String) = i(TAG_ACTION, what)

    fun w(tag: String, msg: String) = emit(render('W', tag, msg), Log.WARN, tag)

    /**
     * 记一条异常。
     *
     * 堆栈**展开成多行**写进去，而不是塞在一行里加 `\n`：日志查看页是逐行渲染的，
     * 一行里带换行会让「只看错误」的过滤和滚动都失效。
     */
    fun e(tag: String, msg: String, t: Throwable? = null) {
        emit(render('E', tag, msg), Log.ERROR, tag)
        if (t != null) {
            val text = Log.getStackTraceString(t)
            if (text.isNotBlank()) {
                text.lineSequence()
                    .filter { it.isNotBlank() }
                    .take(STACK_MAX_LINES)
                    .forEach { emit("                            │ " + it.trimEnd(), Log.ERROR, tag) }
            }
        }
    }

    /**
     * 崩溃专用入口：**同步落盘**。
     *
     * 与 [e] 的差别只在「不等 executor」——崩溃现场没有下一个调度周期了。
     */
    fun crash(tag: String, msg: String, t: Throwable? = null) {
        writeSync(render('E', tag, msg))
        // 用 Log.e 而不是 Log.println(..., Throwable)：println 只有
        // (int, String, String) 这一个重载，四参版本不存在
        Log.e("EC/$tag", msg, t)
        if (t != null) {
            val text = Log.getStackTraceString(t)
            text.lineSequence()
                .filter { it.isNotBlank() }
                .take(STACK_MAX_LINES)
                .forEach { writeSync("                            │ " + it.trimEnd()) }
        }
    }

    /** 崩溃现场的分隔块：一眼能看出「这次会话死在哪」 */
    fun crashBanner(title: String, details: String) {
        val bar = "=".repeat(20)
        writeSync("")
        writeSync("$bar $title $bar")
        details.lineSequence().filter { it.isNotBlank() }.forEach { writeSync("  " + it.trimEnd()) }
        writeSync("")
    }

    /**
     * 主动快照：把当前应用状态写进日志。
     *
     * 用户报问题时会先点这个（设置页的「记录当前状态」），
     * 于是我们要的上下文就在他随手导出的日志里，不必隔着屏幕来回问。
     */
    fun dumpState(reason: String) {
        val snap = runCatching { snapshotProvider?.invoke() }.getOrNull() ?: return
        i("DiagLog", "状态快照（$reason）")
        snap.lineSequence().filter { it.isNotBlank() }.forEach { i("  ·", it) }
    }

    // ------------------------------------------------------------------
    // 读取（给诊断界面）
    // ------------------------------------------------------------------

    /** 内存里最近 [RING_CAPACITY] 条，用于界面秒开。 */
    fun recent(): List<String> = synchronized(lock) { ring.toList() }

    /** 从**磁盘**读尾部若干行。用于「日志被滚走 / 冷启动」时看历史。 */
    fun readFromDisk(maxLines: Int = 800): List<String> {
        val f = currentFile() ?: return emptyList()
        if (!f.isFile) return emptyList()
        return runCatching {
            val deque = ArrayDeque<String>(maxLines)
            f.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEach { l ->
                    if (deque.size >= maxLines) deque.removeFirst()
                    deque.addLast(l)
                }
            }
            deque.toList()
        }.getOrDefault(emptyList())
    }

    fun currentFile(): File? = logDir?.let { File(it, FILE_NAME) }

    /** 当前 + 已滚动的历史文件，按「新 → 旧」排序。 */
    fun files(): List<File> {
        val d = logDir ?: return emptyList()
        val list = mutableListOf<File>()
        File(d, FILE_NAME).takeIf { it.isFile }?.let { list += it }
        for (i in 1..MAX_ROLL) {
            File(d, "diag.$i.log").takeIf { it.isFile }?.let { list += it }
        }
        return list
    }

    fun totalBytes(): Long = files().sumOf { it.length() }

    fun lineCount(): Int = synchronized(lock) { seq.get().toInt() }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private fun render(level: Char, tag: String, msg: String): String {
        val up = (SystemClock.elapsedRealtime() - bootAt) / 1000.0
        val thread = Thread.currentThread().name
        return buildString(192) {
            append(clock.get()?.format(Date()) ?: "??:??:??.???")
            append(" +")
            append(String.format(Locale.US, "%.3f", up))
            append("s [")
            append(if (thread.length > THREAD_WIDTH) thread.take(THREAD_WIDTH - 1) + "…" else thread.padEnd(THREAD_WIDTH))
            append("] ")
            append(level)
            append(' ')
            val t = if (tag.length > TAG_WIDTH) tag.take(TAG_WIDTH) else tag.padEnd(TAG_WIDTH)
            append(t)
            append(' ')
            append(sanitize(msg.replace('\n', ' ')).take(MAX_MSG_CHARS))
        }
    }

    /**
     * 抹掉控制字符。
     *
     * ## 为什么必须有这一道（而不是信任调用方）
     *
     * 日志文件一旦含 NUL 字节，ripgrep / grep 会**把整个文件判为二进制并停止匹配**
     * —— 从此这份日志什么关键词都搜不到，而且完全看不出是怎么废掉的。
     * 0.12.0 那份真机日志就是这样废掉的：源头是 `ApplicationExitInfo.traceInputStream`
     * 的 protobuf 字节被当成文本写进来（那个 bug 已修，见 CrashGuard.printableStrings）。
     *
     * 但那一处只是**已知的一个**来源。日志的价值全在「事后能搜」，
     * 而废掉它的代价是不可逆的（用户已经把文件发过来了）。所以这里做最后一道闸：
     * 凡是控制字符（含 NUL、BEL、ESC）一律替换成 `?`。
     * 代价是极少数情况下少几个字节的原貌，换来的是「这份文件永远可搜」。
     */
    private fun sanitize(s: String): String {
        var bad = false
        for (c in s) if (c < ' ' || c == '\u007F') { bad = true; break }
        if (!bad) return s
        val sb = StringBuilder(s.length)
        for (c in s) sb.append(if (c < ' ' || c == '\u007F') '?' else c)
        return sb.toString()
    }

    private fun emit(line: String, priority: Int, tag: String) {
        remember(line)
        Log.println(priority, "EC/$tag", line)
        runCatching { writer.execute { writeLocked(line) } }
    }

    private fun remember(line: String) {
        synchronized(lock) {
            seq.incrementAndGet()
            if (ring.size >= RING_CAPACITY) ring.removeFirst()
            ring.addLast(line)
        }
    }

    private fun writeLocked(line: String) {
        synchronized(lock) {
            val s = sink ?: return
            try {
                val bytes = (line + "\n").toByteArray(Charsets.UTF_8)
                s.write(bytes)
                s.flush()
                written += bytes.size
                if (written > MAX_BYTES) {
                    rollIfNeededLocked()
                    openLocked()
                }
            } catch (t: Throwable) {
                // 写日志失败不能连累应用：关掉 sink，本次会话静默降级为只在 logcat 里输出
                runCatching { s.close() }
                sink = null
            }
        }
    }

    /** 崩溃路径专用：加锁直写，不等 executor。 */
    private fun writeSync(line: String) {
        // 这条路径的文本来自崩溃现场（异常 message、系统 trace 片段），
        // 是最可能带控制字符的地方 —— 见 sanitize 的说明
        val safe = sanitize(line)
        remember(safe)
        Log.println(Log.ERROR, "EC/crash", safe)
        synchronized(lock) {
            val s = sink ?: return
            runCatching {
                s.write((safe + "\n").toByteArray(Charsets.UTF_8))
                s.flush()
            }
        }
    }

    private fun openLocked() {
        val d = logDir ?: return
        runCatching { sink?.close() }
        val f = File(d, FILE_NAME)
        sink = runCatching { FileOutputStream(f, true) }.getOrNull()
        written = if (f.isFile) f.length() else 0L
    }

    private fun rollIfNeededLocked() {
        val d = logDir ?: return
        if (written <= MAX_BYTES) return
        runCatching { sink?.close() }
        sink = null
        File(d, "diag.$MAX_ROLL.log").delete()
        for (i in MAX_ROLL - 1 downTo 1) {
            File(d, "diag.$i.log").takeIf { it.isFile }
                ?.renameTo(File(d, "diag.${i + 1}.log"))
        }
        File(d, FILE_NAME).takeIf { it.isFile }?.renameTo(File(d, "diag.1.log"))
        written = 0L
    }

    fun clear() {
        synchronized(lock) {
            runCatching { sink?.close() }
            sink = null
            ring.clear()
            files().forEach { runCatching { it.delete() } }
            written = 0L
            openLocked()
        }
        i("DiagLog", "日志已清空")
    }

    /** 供诊断界面显示「本次进程已经跑了多久」 */
    fun uptimeSec(): Double = (SystemClock.elapsedRealtime() - bootAt) / 1000.0
}

/**
 * 一段被计时的操作。
 *
 * 打点的价值一半在「发生了」，另一半在「花了多久」——
 * 排查转写卡死时，「加载模型 4.2s」和「加载模型 240s」是完全不同的结论。
 * 用 [span] 而不是手写两个时间戳，是为了让**异常路径也一定会留下记录**。
 */
class Span internal constructor(private val tag: String, private val what: String) {

    private val startedAt = SystemClock.elapsedRealtime()
    private var closed = false

    fun done(extra: String = "") {
        if (closed) return
        closed = true
        DiagLog.i(tag, buildString(96) {
            append(what).append(" 完成 ").append(SystemClock.elapsedRealtime() - startedAt).append("ms")
            if (extra.isNotEmpty()) append(" · ").append(extra)
        })
    }

    fun fail(t: Throwable? = null, extra: String = "") {
        if (closed) return
        closed = true
        DiagLog.e(tag, buildString(96) {
            append(what).append(" 失败（").append(SystemClock.elapsedRealtime() - startedAt).append("ms）")
            if (extra.isNotEmpty()) append(" · ").append(extra)
        }, t)
    }
}

fun span(tag: String, what: String): Span = Span(tag, what)
