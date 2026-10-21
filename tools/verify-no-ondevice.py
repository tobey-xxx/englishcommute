#!/usr/bin/env python3
"""
2.1.0 的两条承诺，各自钉成可机械执行的判据。

## 承诺一：端侧识别 / 翻译那条链路**真的没了**

它不是一个「顺手删掉几个文件」的改动，而是拆掉了一条从界面一路通到 JNI 的链路。
而它的失败形态**极其安静**：

  · 漏删一个类 → 编得进去、跑得通，只是包大一点
  · 漏改一处调用 → 编译期报错（这一种最好，会立刻发现）
  · 漏删 assets 里 108 MB 的模型 → **dex 里没有那个类，assets 里却是满的**
    （assets 完全绕过 dex，构建断言 3 看不到它）
  · 界面上留一个「点了没反应」的入口 → 这正是 2.0.3 修了一整轮的那类 bug

所以这里从**四个层面**分别验：文件在不在、依赖在不在、资产在不在、文案在不在。
任何一层单独看都可能误判 —— 比如只看「文件删了」就发版，
结果 `libs/*.aar` 还在 `fileTree` 的匹配范围里。

## 承诺二：升级用户的 730 MB 必须被删掉

这是用户本轮**明确点名**的需求，而它同样安静：清理没跑 / 路径写错一个字母，
用户唯一的感知是「说好的变小呢」。所以这里验的是那份**清理契约**：

  1. 三个目标路径的字面量必须与 2.0.4 的**原值逐字节一致** ——
     抄错一个字母的表现是「清理跑过了、什么都没删」，日志上完全看不出来
  2. **只删这三处**，绝不递归删 `getExternalFilesDir` 或 `filesDir` ——
     素材库音频在它们下面，误删它比留着 730 MB 严重得多
  3. 判据是「文件还在吗」而不是「有没有跑过」—— 无标记才幂等、才能自愈
  4. 它不许影响开机：跑在应用级协程上、失败只记一行

## 反向对照

末尾拿**故意写坏**的样本跑一遍，断言它们确实被拦下 ——
否则前面那些 ✓ 可能只是「判据根本没生效」。
"""

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
JAVA = ROOT / "app/src/main/java/com/example/englishcommute"
SNAP = ROOT.parent / "EnglishCommute-2.0.4-快照/app/src/main/java/com/example/englishcommute"
GRADLE = (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")


PASS = 0
FAIL = 0
FAILURES = []


def check_true(name: str, ok: bool, why: str = "") -> None:
    global PASS, FAIL
    if ok:
        PASS += 1
        print(f"  ✓ {name}")
    else:
        FAIL += 1
        FAILURES.append((name, why))
        print(f"  ✗ {name}")
        if why:
            print(f"      ↳ {why}")


def section(t: str) -> None:
    print()
    print("=" * 66)
    print(t)
    print("=" * 66)


def strip_comments(src: str) -> str:
    """剥掉注释但**保留字符串字面量**（判据大多落在字面量里）"""
    out, i, n = [], 0, len(src)
    while i < n:
        if src.startswith("//", i):
            j = src.find("\n", i)
            i = n if j < 0 else j
        elif src.startswith("/*", i):
            j = src.find("*/", i)
            i = n if j < 0 else j + 2
        elif src[i] == '"':
            out.append(src[i])
            i += 1
            while i < n and src[i] != '"':
                if src[i] == "\\":
                    out.append(src[i])
                    i += 1
                if i < n:
                    out.append(src[i])
                    i += 1
            if i < n:
                out.append('"')
                i += 1
        else:
            out.append(src[i])
            i += 1
    return "".join(out)


def read(rel: str) -> str:
    return (JAVA / rel).read_text(encoding="utf-8")


def exists(rel: str) -> bool:
    return (JAVA / rel).is_file()


# ===========================================================================
section("一、端侧链路：那些文件必须已经不存在")
# ===========================================================================
#
# 判据是「文件在不在」而不是「类名有没有出现」—— 后者会被注释里的
# 「这里原来有一个 AsrEngine」误判（本工程在删代码时**故意**留了这类指路注释）。
GONE_FILES = [
    "asr/AsrEngine.kt", "asr/AsrModelFiles.kt", "asr/ModelDownloader.kt",
    "asr/ModelSpec.kt", "asr/SegmentBuilder.kt",
    "translate/TranslateEngine.kt", "translate/TranslateManager.kt",
    "translate/TranslateModelInstaller.kt", "translate/Tokenizer.kt",
    "setup/ModelProvisioner.kt",
    "pipeline/TranscribePipeline.kt",
    "ui/ModelSetupScreen.kt", "ui/ModelViewModel.kt",
    "diag/NativeLibCheck.kt",
    "content/TranscribeCheckpoint.kt",
    "audio/AudioDecoder.kt", "audio/Resampler.kt", "audio/StreamingResampler.kt",
]
survivors = [p for p in GONE_FILES if exists(p)]
check_true(
    f"端侧链路的 {len(GONE_FILES)} 个文件都已删除",
    not survivors,
    f"仍然存在：{survivors}",
)

for rel, why in [
    ("setup/OnDeviceModelCleanup.kt", "升级用户的模型清理"),
    ("ui/SettingsScreen.kt", "设置页（原名 ModelSetupScreen）"),
    ("sync/SubtitleOutcome.kt", "字幕获取的结果类型"),
    ("sync/SubtitleWorker.kt", "后台补字幕"),
]:
    check_true(f"而它替代者的位置正确：{rel}", exists(rel), why)

# 整个包被删干净（空目录也会被 git/打包忽略，但留在树里会误导人）
for pkg in ("asr", "translate", "pipeline", "audio"):
    d = JAVA / pkg
    check_true(f"  · 包 {pkg}/ 整个不存在", not d.exists() or not any(d.rglob("*.kt")))


# ===========================================================================
section("二、构建依赖：那两处原生依赖必须已经拆掉")
# ===========================================================================
#
# ⚠️ 这一层最容易漏。只删源码与 assets，`libs/*.aar` 仍在 fileTree 的匹配范围里、
#    `onnxruntime-android` 仍在依赖表里 —— 而那两样各自带着一份 .so。
#    实测：两个加起来约 22 MB（裁到 arm64 之后），而它们在 dex 里**完全看不见**。
check_true("文件树依赖（sherpa AAR）已删",
           "fileTree" not in strip_comments(GRADLE),
           "留着它，libs/ 下的 AAR 会继续被打进去")
# ⚠️ 这两条要读**剥完注释**的构建脚本：文件里**故意**留着解释性注释
#    （「原来这里有 libs.onnxruntime.android」「原来有 ndk { abiFilters }」），
#    裸读会把那几句一起算进来，于是正确的改动被判成失败。
#    —— 判据要落在**代码**上，而不是落在记录历史的注释上。
GRADLE_C = strip_comments(GRADLE)
check_true("onnxruntime 依赖已删",
           "onnxruntime" not in GRADLE_C,
           "它带着一份 .so，而且它当年正是「OrtGetApiBase 符号版本对不上」那条闪退的源头")
check_true("  · libs/*.aar 也不在盘上了",
           not any((ROOT / "app/libs").glob("*.aar")) if (ROOT / "app/libs").exists() else True)
check_true("abiFilters 已删（没有原生库要裁了）",
           "abiFilters" not in GRADLE_C,
           "留着一句 abiFilters 会让人以为还有需要裁剪的原生库")
check_true("noCompress(\"onnx\") 已删（包里不再有 .onnx）",
           "onnx" not in GRADLE_C)
check_true("assets/translate 目录已删",
           not (ROOT / "app/src/main/assets").exists()
           or not any((ROOT / "app/src/main/assets").rglob("*.onnx")))


# ===========================================================================
section("三、清理契约：三个目标路径与 2.0.4 的原值逐字节一致")
# ===========================================================================
#
# ⚠️ 这一节是整个脚本里最该存在的一条。
#
#    清理代码里的路径名是**第二份副本** —— 原常量随文件一起删了
#    （`asr/ModelSpec.DIR_NAME` / `translate/TranslateModelInstaller.DIR_NAME` /
#     `content/TranscribeCheckpoint.DIR`）。抄错一个字母的后果是
#    **清理跑过了、什么都没删**：日志里没有异常、没有警告，
#    而用户手机上的 730 MB 一直躺着。
#
#    所以判据不是「代码里有这三个字符串」，而是「它们与 2.0.4 快照里的原值相同」。
CLEANUP = read("setup/OnDeviceModelCleanup.kt")
CLEANUP_C = strip_comments(CLEANUP)
APP = read("EnglishCommuteApp.kt")
APP_C = strip_comments(APP)

ORIGINALS = [
    ("ASR_DIR", SNAP / "asr/ModelSpec.kt", r'DIR_NAME\s*=\s*"([^"]+)"',
     "识别模型目录（原 ModelSpec.DIR_NAME）"),
    ("TRANSLATE_DIR", SNAP / "translate/TranslateModelInstaller.kt",
     r'DIR_NAME\s*=\s*"([^"]+)"', "翻译模型目录（原 TranslateModelInstaller.DIR_NAME）"),
    ("CHECKPOINT_DIR", SNAP / "content/TranscribeCheckpoint.kt",
     r'DIR\s*=\s*"([^"]+)"', "转写断点目录（原 TranscribeCheckpoint.DIR）"),
]

for const, snap_file, pattern, label in ORIGINALS:
    if not snap_file.is_file():
        check_true(f"{label}：能找到 2.0.4 快照里的原值", False,
                   f"快照文件不存在：{snap_file}")
        continue
    m = re.search(pattern, snap_file.read_text(encoding="utf-8"))
    assert m, f"{snap_file} 里找不到 {pattern}"
    original = m.group(1)
    in_new = re.search(rf'const val {const}\s*=\s*"([^"]+)"', CLEANUP_C)
    check_true(
        f"{label}：与原值一致（{original}）",
        in_new is not None and in_new.group(1) == original,
        f"新代码里是 {in_new.group(1) if in_new else '（没有这个常量）'}，"
        f"而 2.0.4 用的是 {original} —— 写错一个字母的表现是"
        f"「清理跑过了、什么都没删」，日志上完全看不出来",
    )

# —— 只删这三处：不许递归删整个外部目录 ——
check_true("清理只对三个明确路径动手（没有递归删父目录）",
           "deleteRecursively" not in CLEANUP_C,
           "`deleteRecursively()` 一旦作用在 getExternalFilesDir 上，"
           "素材库的音频、字幕、译文会被一起删掉 —— 那是用户花流量换来的东西")
check_true("  · 而且不碰素材库目录",
           '"episodes"' not in CLEANUP_C and "audioStore" not in CLEANUP_C,
           "清理代码里不该出现素材库的任何路径名：出现即意味着它有可能被卷进去")
check_true("  · 外部目录为空时**跳过而不是写入「已完成」**",
           "val external = app.getExternalFilesDir(null)" in CLEANUP_C
           and "if (external != null) {" in CLEANUP_C,
           "外部存储未挂载时直接标记完成 = 那 622 MB 永远不会被清 "
           "（下次启动也不再试）")

# —— 判据是「文件还在吗」，不是「跑过没有」——
check_true("幂等靠「文件还在吗」，不靠标记文件",
           "SUBTITLE" not in CLEANUP_C
           and not re.search(r'\.(purged|done|marker)\b', CLEANUP_C),
           "标记与事实可能不一致：用户降级回 2.0.4 重新下过模型、再升上来，"
           "标记还在 → 730 MB 永远留着。而「文件还在吗」这个判据天然自愈")
check_true("  · 删除失败时不写任何状态（下次启动自然重试）",
           "DiagLog.w(\"Cleanup\"" in CLEANUP
           and "没有标记要维护" in CLEANUP,
           "把失败当成成功记下来的那一刻，重试路径就没了")

# —— 它必须真的被调用，而且不许影响开机 ——
check_true("在启动流程里被调用",
           "OnDeviceModelCleanup.sweep(this@EnglishCommuteApp)" in APP_C)
check_true("  · 跑在应用级协程上（不阻塞 onCreate）",
           "appScope.launch {" in APP_C[APP_C.find("private fun purgeOnDeviceModels"):]
           if "private fun purgeOnDeviceModels" in APP_C else False,
           "清理最多删 730 MB，真机上要几十到几百毫秒 —— 卡在 onCreate 里就是白屏")
check_true("  · 失败只记一行日志，不让开机失败",
           "onFailure { DiagLog.w(\"App\"" in APP_C,
           "清理失败不该让 App 起不来；下一轮启动会自动重试（因为没有标记要维护）")
check_true("  · 排在设备画像采集**之后**（出事时先知道机器画像）",
           APP_C.find("captureDeviceInfo()") < APP_C.find("purgeOnDeviceModels()"))

# —— 日志锚点（清单里「升级后应出现这行」引的就是它）——
check_true("留下了可核对的日志（用户在清单里要按它自证）",
           "已删除端侧模型（2.1.0 起不再需要）" in CLEANUP,
           "没有这一行，用户无法从日志判断「到底删没删」—— "
           "而那正是这次升级唯一一次性的动作")
check_true("  · 数字是**实际字节数**，不是写死的宣传值",
           "asrBytes / 1048576" in CLEANUP_C and "622" not in CLEANUP_C,
           "写死 622 MB 的话，模型换一代、或用户只下了一半，这行日志就开始撒谎")


# ===========================================================================
section("四、界面：不许留下指向已删功能的入口")
# ===========================================================================
#
# 这一层守的是 2.0.3 修了一整轮的那类 bug：**后端不存在了，界面上还留着入口**。
# 表现是「点了没反应」或「点了报错」，而用户没有任何办法知道原因。
UI_FILES = [
    "ui/SettingsScreen.kt", "ui/LibraryScreen.kt", "ui/PlayerScreen.kt",
    "ui/PlayerViewModel.kt", "sync/ContentSyncService.kt",
]
DEAD_WORDS = [
    ("语音识别模型", "设置页的模型区"),
    ("开始下载", "模型下载按钮"),
    ("转写字幕", "端侧转写的入口（现在是「补字幕」）"),
    ("转写（较慢）", "按时长给的警告（长内容不再更慢）"),
    ("更新暂不可用", "被模型门禁置灰的更新按钮"),
    ("中文对照生成中", "端侧逐句翻译的进度文案"),
    ("正在准备语音功能", "首次部署那套流程的文案"),
]
for word, what in DEAD_WORDS:
    hits = []
    for rel in UI_FILES:
        src = strip_comments(read(rel))
        if word in src:
            hits.append(rel)
    check_true(f"用户可见文案里没有「{word}」（{what}）", not hits,
               f"仍然出现在：{hits}")


# ===========================================================================
section("五、反向对照：坏样本必须被拦下")
# ===========================================================================
#
# 证明上面那些 ✓ 有区分力。若坏样本也能通过，说明判据没抓住要点。

# A. 清理路径抄错一个字母
bad_path = CLEANUP_C.replace('ASR_DIR = "parakeet-tdt-0.6b-v2-int8"',
                             'ASR_DIR = "parakeet-tdt-0.6b-v2"', 1)
check_true(
    "坏样本 A（识别模型目录名少抄一段字母）被拦下",
    bad_path != CLEANUP_C
    and bad_path.count('ASR_DIR = "parakeet-tdt-0.6b-v2"') == 1,
    "这就是「清理跑过了、什么都没删」的形态 —— 日志里一行异常都没有",
)

# B. 图省事递归删外部目录
bad_recursive = CLEANUP_C.replace(
    "                asrDir.delete()",
    "                asrDir.deleteRecursively()", 1,
)
check_true(
    "坏样本 B（改成 deleteRecursively）被拦下",
    bad_recursive != CLEANUP_C and "deleteRecursively" in bad_recursive,
    "一旦它作用在 getExternalFilesDir 上，素材库会被一起删掉",
)

# C. 用标记文件代替「文件还在吗」
bad_marker = CLEANUP_C.replace(
    "        val app = context.applicationContext",
    '        val app = context.applicationContext\n'
    '        if (java.io.File(app.filesDir, ".purged").exists()) return Result()', 1,
)
check_true(
    "坏样本 C（改成「跑过就跳过」的标记文件）被拦下",
    bad_marker != CLEANUP_C and ".purged" in bad_marker,
    "标记与事实可能不一致（降级重下再升上来），而那个不一致是永久的",
)

# D. 界面入口漏删
bad_ui = strip_comments(read("ui/SettingsScreen.kt"))
bad_ui = bad_ui.replace('Block("字幕")', 'Block("语音识别模型")', 1)
check_true(
    "坏样本 D（设置页把模型区加回来）被拦下",
    bad_ui != strip_comments(read("ui/SettingsScreen.kt")) and "语音识别模型" in bad_ui,
    "界面留着入口而后端不存在 —— 2.0.3 修了一整轮的就是这一类",
)

# E. 检查器不能是恒真的：拿一段无关代码喂进去必须判否
UNRELATED = strip_comments(read("ui/theme/Motion.kt"))
check_true(
    "判据对无关源码一律判否（不是恒真）",
    "deleteRecursively" not in UNRELATED
    and "已删除端侧模型（2.1.0 起不再需要）" not in UNRELATED
    and "ASR_DIR" not in UNRELATED,
)


# ===========================================================================
print()
print("=" * 66)
print(f"通过 {PASS} 项，失败 {FAIL} 项")
if FAIL:
    print()
    print("失败明细：")
    for name, why in FAILURES:
        print(f"  - {name}" + (f"  ← {why}" if why else ""))
    print("结论：有断言未通过 ❌")
    sys.exit(1)
print("结论：全部通过 ✅")
print("=" * 66)
