package com.example.englishcommute.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import com.example.englishcommute.diag.DiagLog
import com.example.englishcommute.net.ServerConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * App 内更新：检查 → 下载（可续传）→ 校验 → 交给系统安装器。
 *
 * ## 它解决的问题
 *
 * 以前每次发版都要「想办法把安装包放到手机上」——数据线、微信传文件、
 * 或者临时搭个 http 服务。而这些都是**为了装一个 APK**在做运维。
 * 现在 App 自己去服务器要。
 *
 * ## 三处判据都落在**事实**上
 *
 * | 判断什么 | 用什么 | 为什么不用另一个 |
 * | --- | --- | --- |
 * | 本机是什么版本 | `PackageManager`（装的是什么就是什么） | `BuildConfig` 是「编的时候是什么」——两者在用户装了别的包时不相等 |
 * | 哪个更新 | **数字** `versionCode` | `versionName` 是字符串，`"2.10.0" < "2.9.0"` |
 * | 下完了没有 | **sha256 对上** | 文件存在 / 长度相等都可能被截断的包骗过 |
 *
 * ## 「查不到」不等于「已是最新」
 *
 * 断网、服务器 5xx、清单字段不合法 —— 全部返回 `null` 并且**什么状态都不改**。
 * 把它们写成「已是最新」是在撒谎，而且会让强制更新在断网时静默失效
 * （用户只要拔网线就能绕过）。
 *
 * ## 强制更新是**本地持久化**的判断
 *
 * `minSupportedVersionCode` 一旦读到就存下来。这样断网时它仍然生效 ——
 * 否则「强制」只对在线的用户有效，而离线的人恰好是最需要被挡住的那一批
 * （旧版可能有一堆已经修掉的问题）。
 */
class Updater(context: Context) {

    private val app: Context = context.applicationContext
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 18 MB 的文件在跨境链路上可能要下几分钟。
     * 读超时给足（60 s）—— 默认 10 s 会让「慢但没断」的连接被误判成失败。
     */
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    // ── 本机版本（装的是什么就是什么）───────────────────────────────────
    private val pkgInfo = runCatching {
        app.packageManager.getPackageInfo(app.packageName, 0)
    }.getOrNull()

    /** 用 `PackageInfoCompat`：`longVersionCode` 要 API 28，而 minSdk 是 26 */
    val installedVersionCode: Long =
        pkgInfo?.let { PackageInfoCompat.getLongVersionCode(it) } ?: 0L

    val installedVersionName: String = pkgInfo?.versionName ?: "未知"

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Unknown)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /** 低于它就必须升级（0 = 不强更）。来自最近一次成功读到的清单，**持久化** */
    private val _minSupported = MutableStateFlow(prefs.getLong(KEY_MIN_SUPPORTED, 0L))

    /** 本次会话里被临时跳过（见 [skipForcedOnce]）。**不持久化**，重启就恢复 */
    private val _skipped = MutableStateFlow(false)

    val forced: StateFlow<Boolean> =
        combine(_minSupported, _skipped) { minSup, skipped ->
            !skipped && minSup > 0L && installedVersionCode < minSup
        }.stateIn(scope, SharingStarted.Eagerly, false)

    // ------------------------------------------------------------------
    // 检查
    // ------------------------------------------------------------------

    /**
     * 去服务器问一次有没有新版本。拿到清单返回它，否则返回 null。
     *
     * @return 服务器上的清单（即使不比本机新）—— 调用方大多不看返回值，
     *         界面读的是 [state]
     */
    suspend fun check(): UpdateInfo? = withContext(Dispatchers.IO) {
        val body = runCatching {
            client.newCall(
                Request.Builder()
                    .url(ServerConfig.abs(UPDATE_PATH))
                    // 这条路径在 nginx 上已经是 no-store，这里再压一道：
                    // 清单被缓存过的话，用户会一直看到旧版本号 ——
                    // 表现是「我发布了新版，App 里永远说已是最新」
                    .header("Cache-Control", "no-cache")
                    .build()
            ).execute().use { r ->
                if (!r.isSuccessful) error("HTTP ${r.code}")
                r.body?.string() ?: error("响应为空")
            }
        }.getOrElse { e ->
            // ⚠️ 这里**什么都不改**：不能写成「已是最新」，也不能动 minSupported。
            DiagLog.w("Update", "查更新没成功（${e.message?.take(50)}）——本次不改任何状态")
            return@withContext null
        }

        val info = UpdateInfo.parse(body)
        if (info == null) {
            DiagLog.w("Update", "更新清单不合法（缺字段或 url/sha256 格式不对），忽略")
            return@withContext null
        }

        // 持久化：断网时「要不要挡住用户」仍然按最后一次权威答复办
        prefs.edit()
            .putLong(KEY_MIN_SUPPORTED, info.minSupportedVersionCode)
            .putString(KEY_LAST, info.toJson())
            .apply()
        _minSupported.value = info.minSupportedVersionCode

        val newer = info.versionCode > installedVersionCode
        DiagLog.i(
            "Update",
            "查更新 · 服务器 ${info.versionName}(build ${info.versionCode}) · " +
                "本机 ${installedVersionName}(build $installedVersionCode) · " +
                (if (newer) "有新版本" else "已是最新") +
                if (info.minSupportedVersionCode > 0L) {
                    " · 强制下限 build ${info.minSupportedVersionCode}"
                } else "",
        )

        // ⚠️ 正在下载 / 已经下好时**不要**用 Available 覆盖 —— 进度条会当场消失，
        //    而用户看到的是「点了更新，什么都没发生」。
        val cur = _state.value
        val sameInFlight = when (cur) {
            is UpdateState.Downloading -> cur.info.versionCode == info.versionCode
            is UpdateState.Ready -> cur.info.versionCode == info.versionCode
            else -> false
        }
        if (!sameInFlight) {
            _state.value = if (newer) UpdateState.Available(info) else UpdateState.UpToDate
        }
        info
    }

    // ------------------------------------------------------------------
    // 下载
    // ------------------------------------------------------------------

    /**
     * 下载更新包。**可以重复调用** —— 已经下好且哈希对时直接跳到「可安装」，
     * 中途断了则从 `.part` 的断点续传（跨境链路上一次下完 18 MB 是小概率事件）。
     */
    fun download(info: UpdateInfo) {
        if (_state.value is UpdateState.Downloading) return
        scope.launch {
            val dir = File(app.cacheDir, DIR).apply { mkdirs() }
            val target = File(dir, "EnglishCommute-${info.versionName}.apk")
            if (isDownloaded(target, info)) {
                _state.value = UpdateState.Ready(info, target)
                return@launch
            }
            val part = File(dir, target.name + ".part")
            _state.value = UpdateState.Downloading(
                info,
                if (part.isFile) part.length() else 0L,
            )
            try {
                fetch(info, part) { done ->
                    _state.value = UpdateState.Downloading(info, done)
                }
                // ★ 校验**必须**在这里，而且必须在改名之前。
                //   截断的包在安装时会抛一句系统级的「解析包时出现问题」——
                //   用户读到的是「更新坏了」，真相是「上次没下完」。
                val actual = sha256(part)
                if (actual != info.sha256) {
                    part.delete()
                    throw IOException("校验不通过")
                }
                if (target.exists()) target.delete()
                if (!part.renameTo(target)) {
                    part.copyTo(target, overwrite = true)
                    part.delete()
                }
                pruneOther(dir)
                DiagLog.i("Update", "更新包已下好并校验通过 · ${info.versionName}")
                _state.value = UpdateState.Ready(info, target)
            } catch (e: Exception) {
                if (!isActive) return@launch
                DiagLog.w("Update", "下载更新失败（${e.message?.take(60)}）")
                _state.value = UpdateState.Failed(info, humanReason(e))
            }
        }
    }

    private fun isDownloaded(f: File, info: UpdateInfo): Boolean =
        f.isFile && f.length() > 0L && (info.sizeBytes <= 0L || f.length() == info.sizeBytes)

    private fun fetch(info: UpdateInfo, part: File, onProgress: (Long) -> Unit) {
        val startAt = if (part.isFile) part.length() else 0L
        val b = Request.Builder().url(info.url).header("Accept", "*/*")
        if (startAt > 0L) b.header("Range", "bytes=$startAt-")
        client.newCall(b.build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val body = resp.body ?: throw IOException("响应为空")
            // 服务器不支持续传（回了 200）⇒ 已经下过的那些字节不算数，从头写
            val resuming = resp.code == 206 && startAt > 0L
            if (!resuming && part.isFile) part.delete()

            var done = if (resuming) startAt else 0L
            var lastReport = 0L
            RandomAccessFile(part, "rw").use { raf ->
                raf.seek(done)
                body.byteStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        raf.write(buf, 0, n)
                        done += n
                        val now = System.currentTimeMillis()
                        if (now - lastReport >= 200L) {
                            onProgress(done)
                            lastReport = now
                        }
                    }
                }
                raf.fd.sync()
            }
            onProgress(done)
        }
    }

    // ------------------------------------------------------------------
    // 安装
    // ------------------------------------------------------------------

    /**
     * 交给系统安装器。
     *
     * @return 给用户看的一句话；`null` 表示已经成功唤起安装界面
     */
    fun install(file: File): String? {
        if (!app.packageManager.canRequestPackageInstalls()) {
            // 这是**唯一**会让用户卡住的一步：系统要求用户手动授权
            // 「安装未知应用」。文案要说清「接下来做什么」，并且能一键跳过去
            // （[openInstallPermissionSettings]）—— 只说「权限不足」等于没说。
            return "系统还不允许本应用安装更新。需要先打开「安装未知应用」"
        }
        return runCatching {
            val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file)
            app.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            DiagLog.act("已交给系统安装器 · ${file.name}")
            null
        }.getOrElse { e ->
            "没能唤起系统安装器（${e.message?.take(50) ?: "未知原因"}）"
        }
    }

    fun openInstallPermissionSettings() {
        runCatching {
            app.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                    .setData(Uri.parse("package:${app.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /** 上一次成功读到的清单（离线时也能告诉用户「服务器上是什么版本」） */
    fun lastSeen(): UpdateInfo? = prefs.getString(KEY_LAST, null)?.let(UpdateInfo::fromJson)

    /**
     * 强制更新页上的**应急出口**：本次会话内跳过。
     *
     * 存在的理由很实际：强制更新的判据来自服务器清单，而服务器可能被我们自己
     * 搞坏（清单写错、包没传完、nginx 挂了）。那时候如果没有出口，
     * 用户就完全没法用 App 了 —— 而**唯一**的补救手段是刷机式的手工重装。
     *
     * ⚠️ 刻意做成「不持久化 + 藏在长按里」：它不该被顺手点到，
     *    但它必须存在。重启 App 后强制状态自动恢复。
     */
    fun skipForcedOnce() {
        _skipped.value = true
        DiagLog.w("Update", "强制更新被手动跳过（仅本次会话有效）")
    }

    fun resetSkip() {
        _skipped.value = false
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    /** 一次 18 MB 的文件，顺手清掉上一次留下的其它版本 */
    private fun pruneOther(dir: File) {
        runCatching {
            dir.listFiles { f -> f.name.endsWith(".apk") || f.name.endsWith(".part") }
                ?.forEach { f -> if (!f.name.contains("EnglishCommute-")) f.delete() }
        }
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * 把异常翻成「用户接下来做什么」。
     *
     * 判据（本工程的文案纪律）：**每一种失败都要能给下一步**。
     * 「下载失败」不是一句话，它是「再试一次」「等一下」还是「去找人」三件不同的事。
     */
    private fun humanReason(e: Exception): String {
        val m = (e.message ?: "").lowercase()
        return when {
            m.contains("校验不通过") -> "下载的文件不完整（网络中途断了）。已经丢弃，再试一次"
            m.contains("http 404") -> "服务器上找不到这个更新包，可能还没传完"
            m.contains("http 5") -> "服务器暂时出错了，等一会儿再试"
            m.contains("timeout") || m.contains("timed out") -> "网络太慢，这次没下完。再试一次会接着上次的继续下"
            m.contains("unable to resolve host") || m.contains("failed to connect") ->
                "连不上服务器，检查一下网络"
            else -> "下载没成功（${e.message?.take(40) ?: "未知原因"}），再试一次"
        }
    }

    companion object {
        /** 清单路径（与 nginx 上的 `location = /apk/update.json` 一一对应） */
        const val UPDATE_PATH = "apk/update.json"

        private const val PREFS = "update"
        private const val KEY_MIN_SUPPORTED = "minSupportedVersionCode"
        private const val KEY_LAST = "lastSeenManifest"

        /** 更新包落在 cacheDir 下 —— 卸载/清缓存会自动清掉，不占用户的长期空间 */
        private const val DIR = "updates"
    }
}
