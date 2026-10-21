#!/usr/bin/env python3
"""App 内更新：判据、校验顺序与安装通路（2.2.1）。

    verify-update.py

## 守的是哪一类问题

「更新」这条路上的错误几乎都是**静默**的：点了没反应、装上了旧版、
或者装上去之后才发现文件是坏的。所以断言要盯住**三处顺序**：

  ① **比版本号用数字，不用字符串** —— 字符串比较在这里是错的：
     `"2.10.0" < "2.9.0"` 为真 ⇒ 升级到 2.10.0 的用户会被提示「有新版本 2.9.0」，
     而 Android 允许同签名**降级覆盖**，于是他点下去真的会被装回旧版。
  ② **sha256 必须在「改名 / 交给安装器」之前校验** —— 18 MB 在跨境链路上
     下不完整是常态。少了这一步，用户看到的是系统级的「解析包时出现问题」，
     而真相是「上次没下完」。
  ③ **安装必须走 FileProvider** —— `Uri.fromFile` 在 targetSdk 24+ 会抛
     `FileUriExposedException`，而它是抛在 runCatching 里的：**连崩溃都看不到**。

## 为什么还要跑一段「行为」而不是只查源码

①②这两条都是**判据错**而不是**写法错**：写成 `>` 还是 `<`、比名字还是比数字，
源码上看都「有一句比较」。所以这里把比较与校验规则用 Python 重实现一遍，
再拿**坏实现**去撞同一批用例 —— 坏实现必须在用例上失败。
"""

import sys
from pathlib import Path

PASS = FAIL = 0


def check(name: str, ok: bool, why: str = "") -> None:
    global PASS, FAIL
    if ok:
        PASS += 1
        print(f"  ✅ {name}")
    else:
        FAIL += 1
        print(f"  ❌ {name}")
        if why:
            print(f"       {why}")


JAVA = Path(__file__).resolve().parent.parent / "app/src/main/java/com/example/englishcommute"
RES = Path(__file__).resolve().parent.parent / "app/src/main/res"
ROOT = Path(__file__).resolve().parent.parent


def read(p: Path) -> str:
    return p.read_text(encoding="utf-8") if p.is_file() else ""


def strip_comments(src: str) -> str:
    """去掉注释与字符串字面量，避免断言被注释里的示例骗过。"""
    out, i, n = [], 0, len(src)
    while i < n:
        if src.startswith("//", i):
            j = src.find("\n", i)
            i = n if j < 0 else j
        elif src.startswith("/*", i):
            j = src.find("*/", i)
            j = n if j < 0 else j + 2
            out.append("\n" * src.count("\n", i, j))
            i = j
        elif src[i] == '"':
            j = i + 1
            while j < n and src[j] != '"':
                if src[j] == "\\":
                    j += 1
                j += 1
            out.append('""')
            i = j + 1
        else:
            out.append(src[i])
            i += 1
    return "".join(out)


# ===========================================================================
# Ⅰ. 行为：哪个更新？（版本号 vs 版本名）
# ===========================================================================

def newer_by_code(installed_code, info_code, installed_name="", info_name=""):
    """正确实现：比**数字** versionCode"""
    return info_code > installed_code


def newer_by_name(installed_code, info_code, installed_name="", info_name=""):
    """**坏实现**：比字符串 versionName（这是这类功能最经典的错）"""
    return info_name > installed_name


CASES = [
    # (说明, installed_code, installed_name, server_code, server_name, 期望「有更新」)
    ("普通升级", 45, "2.2.0", 46, "2.2.1", True),
    ("已是最新", 46, "2.2.1", 46, "2.2.1", False),
    ("★ 跨位升级：2.9.0 → 2.10.0", 29, "2.9.0", 30, "2.10.0", True),
    ("★ 不能降级：2.10.0 → 服务器还挂着 2.9.0", 30, "2.10.0", 29, "2.9.0", False),
    ("大版本跃迁", 46, "2.2.1", 60, "3.0.0", True),
]


def run_cases(fn):
    bad = []
    for name, ic, inm, sc, snm, want in CASES:
        got = fn(ic, sc, inm, snm)
        if got != want:
            bad.append(f"{name}（期望 {want}，得到 {got}）")
    return bad


# ===========================================================================
# Ⅱ. 行为：清单解析必须拒绝哪些东西
# ===========================================================================

def parse_ok(version_code, version_name, url, sha256):
    """正确实现：任何一项不合法就整份拒绝（宁可当作「查不到」）"""
    if version_code <= 0 or not version_name:
        return False
    if not (url.startswith("http://") or url.startswith("https://")):
        return False
    if len(sha256) != 64 or any(c not in "0123456789abcdef" for c in sha256):
        return False
    return True


def parse_lenient(version_code, version_name, url, sha256):
    """**坏实现**：只要求有字段，不校验格式"""
    return bool(version_name)


GOOD_SHA = "a" * 64
PARSE_CASES = [
    ("正常清单", 46, "2.2.1", "http://x/apk/a.apk", GOOD_SHA, True),
    ("空版本名", 46, "", "http://x/a.apk", GOOD_SHA, False),
    ("versionCode 为 0", 0, "2.2.1", "http://x/a.apk", GOOD_SHA, False),
    ("★ url 不是 http（清单可被中间人改）", 46, "2.2.1", "file:///etc/passwd", GOOD_SHA, False),
    ("url 为空", 46, "2.2.1", "", GOOD_SHA, False),
    ("★ sha256 太短", 46, "2.2.1", "http://x/a.apk", "abc123", False),
    ("★ sha256 含非十六进制字符", 46, "2.2.1", "http://x/a.apk", "z" * 64, False),
]


def run_parse_cases(fn):
    bad = []
    for name, code, vname, url, sha, want in PARSE_CASES:
        got = fn(code, vname, url, sha)
        if got != want:
            bad.append(f"{name}（期望 {want}，得到 {got}）")
    return bad


def main():
    print("App 内更新 —— 判据与通路\n")

    print("【Ⅰ. 哪个更新：必须比数字 versionCode】")
    bad = run_cases(newer_by_code)
    check("正确实现全部用例通过", not bad, "；".join(bad))
    bad_name = run_cases(newer_by_name)
    check("★坏实现（比字符串 versionName）必须失败", bool(bad_name),
          "少这一条，「2.9.0 → 2.10.0」会被判成「没有更新」，而反向还会提示降级")

    print("\n【Ⅱ. 清单解析：不合法就整份拒绝】")
    bad = run_parse_cases(parse_ok)
    check("正确实现全部用例通过", not bad, "；".join(bad))
    bad_l = run_parse_cases(parse_lenient)
    check("★坏实现（只要求有字段）必须失败", bool(bad_l),
          "会放行 file:// 这类 url —— 而那个值会**直接变成下载目标**")

    # ── Ⅲ. 源码契约 ──────────────────────────────────────────────────────
    print("\n【Ⅲ. 源码契约】")
    updater = strip_comments(read(JAVA / "update/Updater.kt"))
    info = strip_comments(read(JAVA / "update/UpdateInfo.kt"))
    activity = strip_comments(read(JAVA / "MainActivity.kt"))
    app = strip_comments(read(JAVA / "EnglishCommuteApp.kt"))
    manifest = read(ROOT / "app/src/main/AndroidManifest.xml")
    paths = read(RES / "xml/file_paths.xml")

    check("① `Updater` 存在且是唯一一份",
          "class Updater(" in updater and updater.count("class Updater(") == 1)

    check("② 比较用的是 versionCode（不是 versionName）",
          "info.versionCode > installedVersionCode" in updater
          and "versionName >" not in updater,
          "字符串比较会让 2.10.0 < 2.9.0")

    check("  · 本机版本来自 PackageManager（装的是什么就是什么）",
          "getPackageInfo(app.packageName" in updater
          and "PackageInfoCompat.getLongVersionCode" in updater,
          "BuildConfig 是「编的时候是什么」，与「装的是什么」不是一回事")

    check("③ 下载完**先校验 sha256、再改名**（顺序不能反）",
          updater.find("sha256(part)") > 0
          and updater.find("sha256(part)") < updater.find("renameTo(target)"),
          "先改名的话，坏文件已经躺在正式路径上了")

    # ⚠️ 这条必须用**原始源码**：`strip_comments` 会把字符串字面量换成 `""`，
    #    于是 `"校验不通过"` 这种模式在剥离后的文本里永远找不到 ——
    #    本工程在别处踩过同一个坑（含字面量的断言要用原始源码）。
    updater_raw = read(JAVA / "update/Updater.kt")
    check("  · 校验不通过时**删掉**重下（不许留着）",
          "校验不通过" in updater_raw and "part.delete()" in updater)

    check("④ 安装走 FileProvider（绝不用 Uri.fromFile）",
          "FileProvider.getUriForFile" in updater
          and "Uri.fromFile" not in updater,
          "file:// 在 targetSdk 24+ 抛 FileUriExposedException，"
          "而它抛在 runCatching 里 —— 连崩溃都看不到")

    check("  · 且先检查系统的「安装未知应用」开关",
          "canRequestPackageInstalls()" in updater
          and "ACTION_MANAGE_UNKNOWN_APP_SOURCES" in updater,
          "这是唯一会让用户卡住的一步，必须能给出一条能走的下一步")

    check("⑤ 清单声明了 REQUEST_INSTALL_PACKAGES",
          "android.permission.REQUEST_INSTALL_PACKAGES" in manifest)

    check("⑥ file_paths 开放了 updates/（且是 cache-path）",
          'name="updates"' in paths and 'path="updates/"' in paths
          and "<cache-path" in paths,
          "与 Updater 里的 cacheDir/updates 必须一致；写错会在运行期抛")

    check("  · 且 Updater 确实把包放在 cacheDir/updates",
          'File(app.cacheDir, DIR)' in updater)

    check("⑦ 强制更新的下限**持久化**（断网也挡得住）",
          "putLong(KEY_MIN_SUPPORTED" in updater
          and "prefs.getLong(KEY_MIN_SUPPORTED" in updater,
          "只放在内存里的话，用户拔网线重启就能绕过")

    check("  · 且强制页有应急出口（服务器自己搞坏时不至于没法用）",
          "fun skipForcedOnce()" in updater and "skipForcedOnce()" in
          read(JAVA / "ui/UpdatePanel.kt"))

    check("⑧ 强制页盖在 `Root()` **外面**（放进某个页签里等于没做）",
          "ForcedUpdateScreen" in activity
          and activity.find("ForcedUpdateScreen") < activity.find("Root()"),
          "放里面的话，用户靠打卡页或正在播的音频照样继续用")

    check("⑨ 每次回到前台都查一次，且节流比「查新素材」粗",
          "kickUpdateCheck()" in activity and "UPDATE_CHECK_MIN_MS" in app
          and "6 * 60 * 60 * 1000L" in app,
          "版本几周才发一次，按素材那个频率（60 s）问毫无意义")

    check("⑩ 文案里有「断了会接着下」（否则用户会以为要重来）",
          "接着上次继续下" in read(JAVA / "ui/UpdatePanel.kt"))

    print()
    print("=" * 66)
    print(f"通过 {PASS} 项，失败 {FAIL} 项")
    return 0 if FAIL == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
