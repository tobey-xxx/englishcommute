package com.example.englishcommute.checkin

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.example.englishcommute.diag.DiagLog
import java.io.File

/**
 * 把打卡海报分享出去。
 *
 * ## 为什么「一键分享到微信」不能真的自动发出去
 *
 * Android 上**没有任何合法途径**能让一个普通应用静默地把图片发进微信：
 * 微信只接受通过 `ACTION_SEND` 进来的内容，而 `ACTION_SEND` 必然会
 * 弹出它自己的选择界面（发给谁 / 发到哪个群）。所谓「一键」，
 * 指的是**省掉「保存图片 → 打开微信 → 找到聊天 → 从相册选图」这四步**，
 * 而不是省掉「选发送对象」这一步 —— 那一步是用户的决定，不该由代码代劳。
 *
 * ## 为什么要先单独试微信、失败再退回落选择器
 *
 * 直接 `setPackage("com.tencent.mm")` 能跳过系统选择器，一步进微信，
 * 这是体验上真正的差别（少一次点击、少一次「到底选哪个应用」的犹豫）。
 * 但没装微信时会抛 [ActivityNotFoundException]。
 *
 * ⚠️ **不要用 `resolveActivity` 去预判**：Android 11 起的包可见性限制下，
 *    没在 `<queries>` 里声明过的包一律「解析不到」，于是明明装了微信
 *    也走不进那条分支 —— 报错文案还是「找不到应用」，查起来极其费劲。
 *    直接 try/catch 是唯一可靠的判断方式。
 */
object CheckInShare {

    private const val WECHAT_PACKAGE = "com.tencent.mm"

    /**
     * 分享一张图。
     *
     * @return true = 交出去了（拉起微信或选择器都算成功）。
     *   false = 连选择器都没有（极少见），调用方应当提示用户。
     */
    fun shareImage(context: Context, file: File, caption: String): Boolean {
        val uri = runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }.getOrElse { e ->
            DiagLog.w("Check", "海报 URI 生成失败：${e.message}")
            return false
        }

        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            // 微信会用 EXTRA_TEXT 作为配文（部分版本会忽略，忽略也无害）
            putExtra(Intent.EXTRA_TEXT, caption)
            // 授权是**按次**给的，只对这一个 URI 生效，不会留下长期可读的入口
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.grantUriPermission(WECHAT_PACKAGE, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)

        // 从 Application context 启动 activity 必须带 NEW_TASK；
        // 从 Activity 启动则不能带（会把新界面塞进新任务栈，返回行为变得很怪）
        val newTaskFlag = if (context is Activity) 0 else Intent.FLAG_ACTIVITY_NEW_TASK

        val wechatOk = runCatching {
            context.startActivity(Intent(send).setPackage(WECHAT_PACKAGE).addFlags(newTaskFlag))
        }.isSuccess
        if (wechatOk) {
            DiagLog.i("Check", "海报已交给微信")
            return true
        }

        val chooserOk = runCatching {
            context.startActivity(
                Intent.createChooser(send, "分享打卡海报").addFlags(newTaskFlag)
            )
        }.onFailure { e ->
            if (e !is ActivityNotFoundException) DiagLog.w("Check", "分享失败：${e.message}")
        }.isSuccess
        DiagLog.i("Check", if (chooserOk) "海报已交给系统分享" else "没有可用的分享目标")
        return chooserOk
    }

    /** 分享时附在图片后面的配文。放在这里而不是界面里，是为了和海报文案保持一处 */
    fun caption(streak: Int, todaySeconds: Int): String =
        "我在通勤英语连续打卡 $streak 天，今天听了 ${DayKey.minutesLabel(todaySeconds)}。"
}
