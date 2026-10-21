package com.example.englishcommute.setup

import android.content.Context
import com.example.englishcommute.diag.DiagLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 摘掉端侧识别 / 翻译之后，把**升级用户**手机上已经下过的模型删掉（2.1.0）。
 *
 * ## 为什么必须做这件事，而不是「不管它」
 *
 * 2.1.0 把端侧那条链路整条拆了（APK 从 169 MB 降到二十几 MB）。但**拆代码不会动
 * 用户手机上的文件**：升级上来的机器里躺着
 *
 * | 位置 | 内容 | 体积 |
 * |---|---|---|
 * | `getExternalFilesDir/parakeet-tdt-0.6b-v2-int8/` | 识别模型（4 个文件 + `.part` 半成品） | 约 622 MB |
 * | `filesDir/translate/` | 翻译模型（从 assets 铺出来的 4 个文件） | 约 108 MB |
 * | `filesDir/transcribe-ckpt/` | 转写断点（`.trk`） | 每条约 150 KB |
 *
 * 这三样加起来 730 MB。**卸载重装会清掉，但升级不会** —— 而用户能感知到的唯一
 * 区别是「手机上莫名少了 730 MB」。这是拆链路必须一起交的作业。
 *
 * ## 判据为什么是「文件还在吗」而不是「有没有跑过」
 *
 * 最直觉的写法是落一个标记文件（`.purged`），跑过就跳过。**那是错的**，因为：
 *
 * - 标记与事实可能不一致：用户降级回 2.0.4、重新下了一次模型、再升上来 ——
 *   标记还在，730 MB 就永远留着了
 * - 标记本身也是一个需要维护的状态（写失败怎么办？什么时候清？）
 *
 * 而 [`sweep`] 的判据是**目的本身**：「这三条路径上还有东西吗」。
 * 无东西可删时它只是 3 次 `isDirectory`，微秒级 —— 换来的是
 * **不需要任何标记就天然幂等，而且能自愈**（任何时候这些文件回来，下次启动就清掉）。
 *
 * ## 安全边界（这段是重点）
 *
 * 只删上面那三条路径，**逐字写死在 [`TARGETS`] 里**。特别是：
 *
 * - ⚠️ **绝不递归删 `getExternalFilesDir` 或 `filesDir`**。素材库的音频、
 *   字幕、中文译文全在 `getExternalFilesDir/episodes/` 下，而
 *   「已下载音频」是用户花了流量换来的东西 —— 误删它比留着 730 MB 严重得多。
 * - ⚠️ 路径名与 1.0/2.0 的**字面量**保持一致（`ModelSpec.DIR_NAME` /
 *   `TranslateModelInstaller.DIR_NAME` / `TranscribeCheckpointStore.DIR`）。
 *   那三个常量所在的文件已经删了，所以这里是**第二份副本** ——
 *   `tools/verify-no-ondevice.py` 会拿 2.0.4 快照里的原值核对，防止抄错一个字母
 *   （抄错的表现是「清理跑过了、什么都没删」，而日志上完全看不出来）。
 */
object OnDeviceModelCleanup {

    /** 识别模型的目录名（原 `asr/ModelSpec.DIR_NAME`） */
    const val ASR_DIR = "parakeet-tdt-0.6b-v2-int8"

    /** 翻译模型的目录名（原 `translate/TranslateModelInstaller.DIR_NAME`） */
    const val TRANSLATE_DIR = "translate"

    /** 转写断点的目录名（原 `content/TranscribeCheckpoint.DIR`） */
    const val CHECKPOINT_DIR = "transcribe-ckpt"

    /** 原来给「模型归属」用的安装标识（只有 ModelProvisioner 读它，一起收掉） */
    private const val INSTALL_ID_FILE = ".install_id"

    /** 一次清扫的结果 */
    data class Result(
        val asrBytes: Long = 0L,
        val translateBytes: Long = 0L,
        val checkpoints: Int = 0,
        val freedBytes: Long = 0L,
    ) {
        /** 什么都没删。**这是新装的正常结果**，不是失败 */
        val empty: Boolean get() = freedBytes == 0L && checkpoints == 0
    }

    /**
     * 扫一遍并删掉。可重复调用，**幂等**（见类注释的「判据」一节）。
     *
     * 调用点只有一个：[com.example.englishcommute.EnglishCommuteApp.onCreate]。
     * 跑在应用级协程上、失败只记一行日志 —— 清理失败不该影响开机，
     * 而且下一轮启动会自动重试（因为没有标记要维护）。
     */
    suspend fun sweep(context: Context): Result = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        var asrBytes = 0L
        var translateBytes = 0L
        var ckptFiles = 0
        var ckptBytes = 0L

        // ① 识别模型。它在**外部**目录，可能整块不可用（未挂载）——
        //    这时什么都不做，下次启动再试（没有标记，所以「下次」一定会来）
        val external = app.getExternalFilesDir(null)
        if (external != null) {
            val asrDir = File(external, ASR_DIR)
            if (asrDir.isDirectory) {
                asrBytes = sizeOf(asrDir)
                // ⚠️ 用「列目录后逐个删」而不是 deleteRecursively()：
                //    前者每一步的返回值都能看见，而且**绝不会**顺着软链接跑到目录外
                if (!deleteContents(asrDir)) {
                    DiagLog.w("Cleanup", "识别模型目录没有清干净，下次启动重试")
                }
                asrDir.delete()
            }
        }

        // ② 翻译模型。在**内部**私有目录，随卸载必删
        val translateDir = File(app.filesDir, TRANSLATE_DIR)
        if (translateDir.isDirectory) {
            translateBytes = sizeOf(translateDir)
            if (!deleteContents(translateDir)) {
                DiagLog.w("Cleanup", "翻译模型目录没有清干净，下次启动重试")
            }
            translateDir.delete()
        }

        // ③ 转写断点。它原来是全工程唯一一处「只增不减」的存储
        val ckptDir = File(app.filesDir, CHECKPOINT_DIR)
        if (ckptDir.isDirectory) {
            val files = ckptDir.listFiles().orEmpty()
            ckptFiles = files.count { it.isFile }
            ckptBytes = files.sumOf { if (it.isFile) it.length() else 0L }
            if (!deleteContents(ckptDir)) {
                DiagLog.w("Cleanup", "转写断点目录没有清干净，下次启动重试")
            }
            ckptDir.delete()
        }

        // ④ 安装标识。36 字节，但它的唯一读者（ModelProvisioner）已经不存在了 ——
        //    留着只会让下一个人以为「还有东西在认领模型目录」
        runCatching { File(app.filesDir, INSTALL_ID_FILE).delete() }

        val result = Result(
            asrBytes = asrBytes,
            translateBytes = translateBytes,
            checkpoints = ckptFiles,
            freedBytes = asrBytes + translateBytes + ckptBytes,
        )
        if (!result.empty) {
            // 措辞带**实际字节数**而不是「622 MB」这种宣传值：将来模型换代、
            // 或者用户当时只下了一半，这行日志必须仍然是真的
            DiagLog.i(
                "Cleanup",
                "已删除端侧模型（2.1.0 起不再需要）· 识别 ${asrBytes / 1048576} MB" +
                    " · 翻译 ${translateBytes / 1048576} MB" +
                    " · 断点 ${ckptFiles} 个 · 共腾出 ${result.freedBytes / 1048576} MB",
            )
        }
        result
    }

    /** 目录内容的总字节数（含 `.part` 半成品 —— 它同样要还回去） */
    private fun sizeOf(dir: File): Long =
        dir.listFiles().orEmpty().sumOf { if (it.isFile) it.length() else 0L }

    /** 删掉目录里的**内容**（不删目录本身），全部成功返回 true */
    private fun deleteContents(dir: File): Boolean =
        dir.listFiles().orEmpty().all { it.delete() }
}
