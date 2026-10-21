package com.example.englishcommute.ui

import android.content.Context
import androidx.core.content.pm.PackageInfoCompat
import com.example.englishcommute.diag.BuildInfo

/**
 * 版本标签，从 PackageManager 读，**不写死字符串**。
 *
 * 之前踩过：`versionName` 一直是 0.1.0 没动过，而对外交付的 APK 文件
 * 我却按修复轮次起成 0.2.0 / 0.3.0 —— 结果系统「应用信息」里永远显示 0.1.0，
 * 装完根本分不清是哪一版。
 *
 * 现在 Manifest 里的版本号、APK 输出文件名、界面上的显示，
 * 三者都源自 `app/build.gradle.kts` 顶部的 `appVersionName` / `appVersionCode`。
 */
fun appVersionLabel(context: Context): String = runCatching {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    "v${info.versionName} (build ${PackageInfoCompat.getLongVersionCode(info)})"
}.getOrDefault("版本未知")

/**
 * 安装时间标签。和 [appVersionLabel] 是**一对**：
 *
 * · 版本号只能证明「我发的是哪一版」
 * · 安装时间才能证明「你装的是哪一版」
 *
 * 只有版本号时，看到的是「v0.9.0」—— 第一反应是怀疑代码；
 * 加上「装于 09-17 22:14（18 小时前）」，立刻就能判断是包没换上。
 * 这一条是 0.11.0 那次返工换来的，别再删。
 */
fun appInstalledLabel(context: Context): String = BuildInfo.installDetail(context)
