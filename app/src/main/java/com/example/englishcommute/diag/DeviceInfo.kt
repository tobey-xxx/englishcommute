package com.example.englishcommute.diag

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.system.Os
import android.system.OsConstants
import java.util.Locale

/**
 * 这台机器的硬件 / 系统画像。
 *
 * ## 为什么需要它（2.1.0 重写了这一段）
 *
 * 它当年是为了回答「这台机器跑不跑得动端侧识别」——三层 native 门槛
 * （ABI / ELF 段对齐 / APK 内 zip 条目对齐）、16 KB 页设备会不会
 * `dlopen` 失败、这个 ROM 到底给进程留了几个核。
 *
 * **端侧识别与翻译已经整条拆掉，包里不再有任何 .so**，所以那些问题
 * 现在都不存在了。但这个组件留下，理由变了：
 *
 * 1. **它仍然是「构建期断言有没有在为我工作」的唯一真机依据。**
 *    `build-apk.sh` 里那几条对齐断言在「没有 .so」时会如实报「无需检查」——
 *    而「真的没有 .so 吗」这件事，只有装机之后看 `页16KB` 这一行才能确认。
 *    哪天谁把一个原生依赖加回来，这一段就是第一时间能看出问题的现场记录。
 * 2. **崩溃现场需要它。** 内存总量/可用量、页大小、ABI 是判读
 *    「这次崩溃是不是资源问题」的固定上下文，而崩溃日志里**没有别的来源**。
 *
 * ⚠️ 它已经删掉了「ASR 线程数」这一项 —— 那个数字现在没有任何读者，
 *    而一个永远打印「无」的诊断项只会浪费下一次排查的时间。
 */
object DeviceInfo {

    /**
     * 一次采集的结果。
     *
     * 所有字段都给了「读不到」的默认值：这是诊断组件，
     * 它自己不允许成为新的故障源。
     */
    data class Snapshot(
        /** 内存页大小（字节）。4096 = 4KB 页；16384 = 16KB 页。读不到为 0 */
        val pageSize: Int,
        /** `Runtime.availableProcessors()` —— 本进程**实际能用**的核数 */
        val cpusAvailable: Int,
        /** `_SC_NPROCESSORS_CONF` —— 系统配置的核数（物理/逻辑核总数） */
        val cpusConfigured: Int,
        /** 首选 ABI，如 `arm64-v8a` */
        val abi: String,
        /** SoC 型号，如 `Dimensity 9500`；读不到退回硬件名 */
        val soc: String,
        /** 内存总量 / 当前可用（MB） */
        val totalMemMb: Long,
        val availMemMb: Long,
        /** `Android 16` */
        val androidLabel: String,
        /** SDK_INT，如 36 */
        val sdkInt: Int,
        // ⚠️ 2.1.0 删掉了 `asrThreads`（「端侧识别该开几个线程」）。
        //   它当年是从 AsrEngine 的公式读来的，而这个 App 里已经没有
        //   任何要挑线程数的东西了 —— 留一个永远打印「无」的诊断项，
        //   比没有更糟：下次排查的人会先去查它为什么是「无」。
    ) {
        /**
         * 是否 16 KB 页设备 —— 断言 7 挡的就是这类。
         *
         * 判据用 `>=` 而不是 `==`：Android 目前只有 4 KB 与 16 KB 两种页，
         * 但将来若出现更大的页，对齐要求只会更**严** ——
         * 用 `==` 会把它们误判成「非 16 KB 页」而放过。
         */
        val is16kPage: Boolean get() = pageSize >= 16384

        /** 页大小的人话写法 */
        val pageLabel: String
            get() = when {
                pageSize <= 0 -> "未知"
                pageSize % 1024 == 0 -> "${pageSize / 1024}KB"
                else -> "${pageSize}B"
            }

        /**
         * 本进程拿到的是否少于系统全部核。
         *
         * 为 true 说明 ROM 做了 cpuset 限制 —— 此时 `availableProcessors()` 才是
         * 线程数的正确基数，别拿芯片标称核数去推。
         */
        val cpuCapped: Boolean get() = cpusConfigured > cpusAvailable

        /**
         * 日志用：紧凑一行（日志每行都要短）。
         *
         * ⚠️ 开头的 `设备 Android` 必须是**一个完整的字面量**。
         *
         * 不能写成 `"设备 ${...}"` 再让 `logLine()` 以 `"Android "` 开头 ——
         * 那样编译后 dex 里会落成 `"设备 "` 和 `"Android "` **两段**，
         * 而 `build-apk.sh` 的日志锚点是在 dex 里逐字节搜字符串常量的，
         * 跨段的锚点永远搜不到。**它已经因此失败过一次**（`MISS 设备 Android`）。
         */
        fun logLine(): String = buildString {
            append("设备 Android ").append(androidLabel).append('(').append(sdkInt).append(')')
            append(" · ").append(abi)
            // 页大小单独强调：它是判断「断言 7 是否在为我工作」的唯一依据
            append(" · 页").append(pageLabel)
            if (is16kPage) append("(16K)")
            append(" · 核").append(cpusAvailable).append('/').append(cpusConfigured)
            if (cpuCapped) append("(受限)")
            append(" · 内存").append(availMemMb).append('/').append(totalMemMb).append("MB")
            append(" · ").append(soc)
        }

        /** 诊断页用：逐条列出，长的放前面 */
        fun rows(): List<Pair<String, String>> = listOf(
            "处理器" to "$soc · ${cpusAvailable} 核可用" +
                if (cpuCapped) "（系统 ${cpusConfigured} 核，进程被限制）" else "",
            "内存页" to "$pageLabel" + if (is16kPage) "（16 KB 页，对原生库对齐敏感）" else "",
            "ABI" to abi,
            "内存" to "$availMemMb / $totalMemMb MB 可用",
            "系统" to "Android $androidLabel（API $sdkInt）",
        )
    }

    /** 缓存。设备信息在进程生命周期内不会变，采一次就够 */
    @Volatile
    private var cached: Snapshot? = null

    /** 已采集的快照；没采过返回 null（界面据此显示「读取中…」） */
    val last: Snapshot? get() = cached

    /**
     * 采集并缓存，同时写一行进日志。
     *
     * 本身绝不抛 —— 它是诊断组件。
     */
    fun capture(context: Context): Snapshot {
        val s = read(context)
        cached = s
        // 前缀已经含在 logLine() 里 —— 见它上面那段关于「锚点必须是完整字面量」的说明
        DiagLog.i("Device", s.logLine())
        if (s.is16kPage) {
            DiagLog.i(
                "Device",
                "16 KB 页设备：原生库必须同时满足 ELF 段对齐与 APK 内 zip 条目对齐，" +
                    "否则 dlopen 会失败（构建期断言 2 / 7 覆盖）",
            )
        }
        if (s.cpuCapped) {
            DiagLog.i(
                "Device",
                "本进程核数被限制为 ${s.cpusAvailable}（系统共 ${s.cpusConfigured}）—— " +
                    "线程数按可用核算，不按芯片标称核数",
            )
        }
        return s
    }

    /** 只采集不写日志（诊断页刷新用） */
    fun peek(context: Context): Snapshot = cached ?: read(context).also { cached = it }

    private fun read(context: Context): Snapshot {
        val avail = Runtime.getRuntime().availableProcessors()
        return Snapshot(
            pageSize = sysconf(OsConstants._SC_PAGESIZE),
            cpusAvailable = avail,
            cpusConfigured = sysconf(OsConstants._SC_NPROCESSORS_CONF).takeIf { it > 0 } ?: avail,
            abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "未知",
            soc = socLabel(),
            totalMemMb = memInfo(context)?.first ?: 0L,
            availMemMb = memInfo(context)?.second ?: 0L,
            androidLabel = Build.VERSION.RELEASE ?: "未知",
            sdkInt = Build.VERSION.SDK_INT,
        )
    }

    /** `Os.sysconf` 在少数 ROM 上会抛，且失败返回 -1 */
    private fun sysconf(name: Int): Int =
        runCatching { Os.sysconf(name).toInt() }.getOrDefault(0)

    /**
     * SoC 名。
     *
     * `Build.SOC_MODEL` 是 API 31 才有的，且部分厂商留空 ——
     * 所以三级降级：SOC_MODEL → HARDWARE → "未知"。
     */
    private fun socLabel(): String {
        val model = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Build.SOC_MODEL?.takeIf { it.isNotBlank() && it != Build.UNKNOWN }
        } else null
        return model ?: Build.HARDWARE?.takeIf { it.isNotBlank() } ?: "未知"
    }

    private fun memInfo(context: Context): Pair<Long, Long>? = runCatching {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        (mi.totalMem / 1048576L) to (mi.availMem / 1048576L)
    }.getOrNull()

    /** 给设置页那种「一行摘要」用 */
    fun summaryLine(context: Context): String = peek(context).logLine()

    /** 供日志/界面统一格式化内存（Locale 锁死，避免 `%,d` 在部分区域出问题） */
    fun mb(mb: Long): String = String.format(Locale.US, "%d", mb)
}
