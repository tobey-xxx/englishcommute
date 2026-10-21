package com.example.englishcommute.net

// 开源版：服务器地址来自构建参数（app/build.gradle.kts 的 serverBaseUrl）
import com.example.englishcommute.BuildConfig

/**
 * 服务端地址。**全工程只在这里写一次。**
 *
 * ## 2.0 的内容从哪来
 *
 * ```
 *   服务器（腾讯云轻量·中国香港）
 *     /index.json          ← 目录：有哪些期、音频地址、字幕地址、封面
 *     /subs/<id>.srt       ← 英文断句字幕（服务器转写）
 *     /subs/<id>.zh.json   ← 中文译文（服务器翻译，与字幕逐行对应）
 *
 *   App
 *     音频仍然**直连原始 CDN**下载 —— 服务器不承担音频带宽，也不做音频托管
 *     （版权与流量两条都不划算，见 server/README.md）
 * ```
 *
 * ## 为什么是常量而不是可配置项
 *
 * 给朋友用时，「地址填错」是最容易发生也最难自查的一类问题。写死一个默认值，
 * 少一个可以填错的框。将来换成域名时，改的就是这一行。
 *
 * [ENABLED] 是留给自己的总开关：关掉之后 App 完全退回 1.0 的行为
 * （自己拉 RSS、端侧转写）。它存在的意义是**排查用** ——
 * 「是服务端的问题还是客户端的问题」可以用它一刀切开。
 */
object ServerConfig {

    /**
     * 腾讯云轻量 · 中国香港 · 2核2G。
     *
     * ## 3.1.0：从 `http://<IP>` 换成了域名 + HTTPS
     *
     * 用 IP 有三个代价，都属于「忍得了但不该一直忍」：
     *
     *   ① **明文**。`network_security_config.xml` 里为这一个 IP 专门开了个口子
     *      —— targetSdk 28+ 默认拒绝明文，不开的话连一个字节都发不出去。
     *      为一个 IP 把例外写进包里，是过渡期的权宜。
     *   ② **换机器就得重发 App**。地址写死在包里，IP 一动全体失联。
     *   ③ **音频地址是服务端给的绝对地址**（`index.json` 的 `audioUrl`），
     *      那边也写着同一个 IP —— 换域名要同时改两处，漏一处就是
     *      「有字幕、封面、点下去没声音」。
     *
     * 换域名之后 ① 不再需要（TLS + 系统 CA，Let's Encrypt 的证书直接被信任），
     * ②③ 各自只剩一处要改（这里 / `server/bin/cycle.py` 的 `PUBLIC_BASE`）。
     *
     * ⚠️ **服务端 80/IP 那条通道仍然开着，而且不能关**：已经装在手机上的老版本
     *    只会去 `http://<IP>` 取更新清单（`/apk/update.json`），关掉它们就
     *    **永远升不了级**。详见 `server/nginx/ec.conf` 的文件头。
     */
    // ★ 开源版：地址从**构建参数**读（见 app/build.gradle.kts 的 serverBaseUrl），
    //   仓库里不出现任何人的真实域名。生产环境请用 HTTPS 域名。
    val BASE_URL: String = BuildConfig.SERVER_BASE_URL

    /** 服务器清单的路径 */
    const val CATALOG_PATH: String = "index.json"

    /**
     * 总开关。false = 完全退回 1.0 的自给自足模式。
     *
     * ## ⚠️ 必须是 `val`，**不能是 `const val`**（这一条是踩出来的）
     *
     * 写成 `const val` 时编译器会做常量折叠：`!modelReady() && !ENABLED` 被折成
     * `!modelReady() && false` = 恒假 → **那几段 1.0 的兜底代码被当作死代码整个删掉**：
     * 模型门禁、首启自动部署、"插队转写让位"那条日志，全都不在包里。
     *
     * 后果有两个，都不会在编译期提醒：
     *   ① `build-apk.sh` 的 dex 锚点检查报「缺失 4 项」—— 而它守的正是「这些代码
     *      真的发出去了」。看上去像锚点过期，实际是代码真的没了；
     *   ② 更糟的是语义：注释里写着「关掉开关 1.0 的行为原样回来」，
     *      而 `const val` 让这句话变成「关掉之后重新编译，代码才回来」——
     *      同一个二进制里根本没有那段路可走。
     *
     * 写成普通 `val` 之后，编译器无法折叠，两套路径**都在包里**，开关只是选一条走。
     * 代价是 dex 大一点点（几 KB），换的是「兜底真的存在且可被断言验证」。
     */
    val ENABLED: Boolean = true

    fun abs(path: String): String =
        BASE_URL.trimEnd('/') + "/" + path.trimStart('/')
}
