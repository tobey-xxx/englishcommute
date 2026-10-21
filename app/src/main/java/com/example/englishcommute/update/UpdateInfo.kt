package com.example.englishcommute.update

import org.json.JSONObject
import java.io.File

/**
 * 服务器发布的版本清单（`/apk/update.json`）。
 *
 * ```json
 * { "versionCode": 46, "versionName": "2.3.0",
 *   "url": "http://…/apk/EnglishCommute-2.3.0.apk",
 *   "sizeBytes": 17770781, "sha256": "…",
 *   "minSupportedVersionCode": 46, "notes": "…" }
 * ```
 *
 * ## ⚠️ 判据一律用 `versionCode`（数字），**不要**用 `versionName`（字符串）
 *
 * 「哪个版本更新」是一个**数值比较**。字符串比较在这里是错的：
 * `"2.10.0" < "2.9.0"` 为真 —— 于是升级到 2.10.0 的用户会被提示「有新版本 2.9.0」，
 * 而且他点下去会**降级**（Android 允许同签名降级覆盖，于是真的会装上）。
 * `versionName` 只用来显示。
 *
 * ## 为什么 `sha256` 是必填的
 *
 * 18 MB 的文件在跨境链路上下不完整是常态。没有哈希校验的话，
 * 一个截断的 APK 会在安装时抛一句系统级的「解析包时出现问题」——
 * 用户看到的是「更新坏了」，而真相是「上次没下完」。
 * 校验不通过时客户端的正确动作是**整份丢弃、重新下**（见 [Updater]）。
 */
data class UpdateInfo(
    val versionCode: Long,
    val versionName: String,
    val url: String,
    val sizeBytes: Long,
    val sha256: String,
    /** 低于它就**不许继续用**（0 = 不强更）。也是数字，理由同上 */
    val minSupportedVersionCode: Long,
    val notes: String,
) {
    val sizeLabel: String
        get() = if (sizeBytes <= 0) "大小未知" else "%.1f MB".format(sizeBytes / 1048576.0)

    fun toJson(): String = JSONObject().apply {
        put("versionCode", versionCode)
        put("versionName", versionName)
        put("url", url)
        put("sizeBytes", sizeBytes)
        put("sha256", sha256)
        put("minSupportedVersionCode", minSupportedVersionCode)
        put("notes", notes)
    }.toString()

    companion object {
        /**
         * 解析清单。**任何一个必需字段不合法就返回 null**（宁可当作「查不到」）。
         *
         * ⚠️ 校验 `url` 必须是 http(s) 绝对地址：清单是可以被中间人改的（这里是明文 http），
         *    而 `url` 会直接变成下载目标。放行一个 `file://` 或空串，
         *    轻则更新永远失败，重则去下本地路径 —— 判据放在这里只需要一行。
         */
        fun parse(body: String): UpdateInfo? = runCatching {
            val o = JSONObject(body)
            val code = o.optLong("versionCode", 0L)
            val name = o.optString("versionName").trim()
            val url = o.optString("url").trim()
            val sha = o.optString("sha256").trim().lowercase()
            if (code <= 0 || name.isEmpty()) return@runCatching null
            if (!url.startsWith("http://") && !url.startsWith("https://")) return@runCatching null
            // 64 位十六进制的 sha256；长度不对说明清单坏了，不能拿它去校验
            if (sha.length != 64 || sha.any { it !in "0123456789abcdef" }) return@runCatching null
            UpdateInfo(
                versionCode = code,
                versionName = name,
                url = url,
                sizeBytes = o.optLong("sizeBytes", 0L),
                sha256 = sha,
                minSupportedVersionCode = o.optLong("minSupportedVersionCode", 0L),
                notes = o.optString("notes"),
            )
        }.getOrNull()

        fun fromJson(text: String): UpdateInfo? = parse(text)
    }
}

/** 更新这件事的界面状态。UI 只读它，不做判断 */
sealed interface UpdateState {
    /** 还没查过（或这次没查成）。**不等于**「已是最新」 */
    data object Unknown : UpdateState

    data object UpToDate : UpdateState

    data class Available(val info: UpdateInfo) : UpdateState

    data class Downloading(val info: UpdateInfo, val doneBytes: Long) : UpdateState {
        val fraction: Float
            get() = if (info.sizeBytes <= 0) 0f
            else (doneBytes.toFloat() / info.sizeBytes).coerceIn(0f, 1f)
    }

    /** 已经下好并通过校验，等用户点「安装」 */
    data class Ready(val info: UpdateInfo, val file: File) : UpdateState

    data class Failed(val info: UpdateInfo?, val reason: String) : UpdateState
}
