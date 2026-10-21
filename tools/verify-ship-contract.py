#!/usr/bin/env python3
"""
**发布契约**：每一次交付都成立的那几条，与「这一轮做了什么功能」无关。

## 它从哪来

2.1.0 之前，这些断言住在 `verify-firstrun-deploy.py` 里 —— 那个脚本 1400 行，
主题是「首次部署 + 卸载清理」：就绪判据、归属校验、installId、
模型的 630 MB 下载、四处模型门禁、让位、OOM 兜底……

**那些东西在 2.1.0 整块拆掉了**（端侧识别与翻译不存在了），
于是那个脚本连 import 的源文件都找不到。但它里面有几节
**与端侧毫无关系**，是「每次发布都要成立」的：

  · 备份规则的 domain/path 必须与代码里的目录常量一致（写错**静默不生效**）
  · Manifest 的卸载语义（hasFragileUserData / allowBackup / 两个 xml 声明）
  · 构建脚本里登记了关键类与日志锚点（以及**端侧必须已缺席**）
  · 交付清单第 4 节的「日志对照表」与构建锚点**双向闭环**
  · 版本号与清单一致

这几节被搬到这里，主题收成一个：**发布契约**。
把「一次性部署逻辑」和「每次都要成立的东西」分开之后，
下一轮删功能时不会再一次误伤后者。

用法：
    python3 tools/verify-ship-contract.py
"""

import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
KT = ROOT / "app/src/main/java/com/example/englishcommute"

PASS = 0
FAIL = 0
FAILURES = []


def check_true(desc, cond, extra=""):
    global PASS, FAIL
    if cond:
        PASS += 1
        print(f"  ✓ {desc}")
    else:
        FAIL += 1
        FAILURES.append((desc, extra))
        print(f"  ✗ {desc}" + (f"  [{extra}]" if extra else ""))


def check_eq(desc, got, want):
    check_true(desc, got == want, f"got={got!r} want={want!r}")


def read(rel):
    return (ROOT / rel).read_text()


def kt_read(rel):
    return (KT / rel).read_text()


def strip_comments(src: str) -> str:
    """剥掉注释再断言 —— 本工程注释里到处是解释性的反面例子。"""
    src = re.sub(r"/\*.*?\*/", "", src, flags=re.S)
    src = re.sub(r"//[^\n]*", "", src)
    return src


# ============================================================
# A. 备份规则的域与路径
# ============================================================
#
# ⚠️ 这一节的历史价值极高：它守的是一个**完全不报错**的失败。
#
#   0.14.0 及更早的两个 exclude 都是错的（`file/models/`、`file/audio/`），
#   而错误形式是「这条规则不生效」—— 没有警告、没有日志。
#   后果是 630 MB 的模型挤进 Auto Backup 的 25 MB 上限，
#   而超限的代价不是「只备份前 25 MB」，是**整次备份失败**：
#   连真正该备份的每日更新时刻、播放进度也一起丢了。
#
#   2.1.0 之后要排除的东西少了（模型没了），但判据一条没变：
#   **期望集合必须从代码常量拼出来**，不许手抄。


def _parse_excludes(xml_rel: str):
    """返回 ({块名: {(domain, path), ...}}, root)"""
    root = ET.parse(ROOT / xml_rel).getroot()
    out = {}
    if root.tag == "full-backup-content":
        out["*"] = {(e.get("domain"), e.get("path")) for e in root.findall("exclude")}
    else:
        for block in root:
            out[block.tag] = {
                (e.get("domain"), e.get("path")) for e in block.findall("exclude")
            }
    return out, root


def test_a_backup_rules():
    print("\n=== A. 备份规则的域与路径 ===")

    # --- 从代码读出真实目录（不手抄）---
    audio = kt_read("content/AudioStore.kt")
    diag = kt_read("diag/DiagLog.kt")

    m = re.search(r'getExternalFilesDir\(null\),\s*"([^"]+)"\)', audio)
    episodes_dir = m.group(1) if m else None
    m3 = re.search(r'File\(context\.filesDir,\s*"([^"]+)"\)', diag)
    diag_dir = m3.group(1) if m3 else None

    check_eq("代码里音频目录名 = episodes", episodes_dir, "episodes")
    check_eq("代码里日志目录名 = diag", diag_dir, "diag")

    expected = {
        ("external", f"{episodes_dir}/"),   # getExternalFilesDir()/episodes
        ("file", f"{diag_dir}/"),           # filesDir/diag
    }

    br, br_root = _parse_excludes("app/src/main/res/xml/backup_rules.xml")
    check_true("backup_rules.xml 能解析且根是 full-backup-content",
               br_root.tag == "full-backup-content")
    check_eq("backup_rules.xml 排除集与代码常量完全一致",
             sorted(br["*"]), sorted(expected))
    check_true("backup_rules 里没有 <include>（include 与 exclude 混用极易自相矛盾）",
               len(br_root.findall("include")) == 0)

    der, der_root = _parse_excludes("app/src/main/res/xml/data_extraction_rules.xml")
    check_true("data_extraction_rules.xml 根是 data-extraction-rules",
               der_root.tag == "data-extraction-rules")
    check_eq("有 cloud-backup 与 device-transfer 两块",
             sorted(der.keys()), ["cloud-backup", "device-transfer"])
    check_eq("cloud-backup 排除集与代码常量一致",
             sorted(der["cloud-backup"]), sorted(expected))
    check_eq("device-transfer 排除集与代码常量一致",
             sorted(der["device-transfer"]), sorted(expected))
    check_eq("两块**完全一致**（只排 cloud-backup 会让换机把音频搬过去）",
             sorted(der["cloud-backup"]), sorted(der["device-transfer"]))
    check_true("data_extraction_rules 里没有 include",
               len(der_root.findall(".//include")) == 0)
    check_eq("两个 XML 的排除集一致（11 及以下 / 12+ 走的两条路径必须等价）",
             sorted(br["*"]), sorted(der["cloud-backup"]))

    # 逐条把「过去的错法」钉死
    check_true("不再出现 domain=\"file\" path=\"models/\"（filesDir 下根本没有 models/）",
               ("file", "models/") not in br["*"])
    check_true("不再出现 domain=\"file\" path=\"audio/\"（内部目录下没有 audio/）",
               ("file", "audio/") not in br["*"])
    check_true("音频走 domain=\"external\"（getExternalFilesDir）",
               ("external", "episodes/") in br["*"])
    check_true("日志走 domain=\"file\"", ("file", "diag/") in br["*"])

    # ⚠️ 2.1.0：**反向**判据 —— 模型那两个目录必须**不在**规则里。
    #    它们已经不存在了，而留着两条指不到东西的 exclude 不会报错 ——
    #    它只会让下一个读这个文件的人以为「还有模型要排除」。
    for gone, what in (("parakeet-tdt-0.6b-v2-int8/", "识别模型"),
                       ("translate/", "翻译模型")):
        check_true(f"已删的「{what}」目录不再出现在任何一条规则里",
                   all(gone not in {p for _, p in s} for s in
                       [br["*"], der["cloud-backup"], der["device-transfer"]]),
                   "指不到东西的规则不会报错，只会误导下一个人")


def test_a2_manifest():
    print("\n=== A2. Manifest 的卸载语义 ===")

    man = read("app/src/main/AndroidManifest.xml")

    m = re.search(r'android:hasFragileUserData="([^"]*)"', man)
    check_true("显式声明了 hasFragileUserData（不留默认值给 ROM 去解释）", m is not None)
    check_eq("  · 值必须是 false —— true 会在卸载框里多出「保留数据」勾选框",
             m.group(1) if m else None, "false")
    check_true("没有写成 true（那会让离线音频在卸载后留在盘上）",
               'android:hasFragileUserData="true"' not in man)

    check_true('allowBackup="true"（设置与进度该备份）',
               'android:allowBackup="true"' in man)
    check_true("声明 fullBackupContent（11 及以下）",
               'android:fullBackupContent="@xml/backup_rules"' in man)
    check_true("声明 dataExtractionRules（12+）",
               'android:dataExtractionRules="@xml/data_extraction_rules"' in man)

    app_tag_start = man.find("<application")
    check_true("注释都写在 <application> 开始标签**之前**（属性之间不能写注释）",
               "<!--" not in man[app_tag_start:man.find(">", app_tag_start)])


# ============================================================
# B. 构建脚本：关键类与锚点已登记
# ============================================================


def test_b_build_assertion():
    print("\n=== B. 构建脚本已登记 ===")

    sh = read("build-apk.sh")

    for cls in ("'sync/SubtitleOutcome'", "'SubtitleWorker'", "'SubtitleScheduler'",
                "'OnDeviceModelCleanup'", "'net/ServerCatalog'", "'ui/LibraryAnchor'"):
        check_true(f"dex 类名已登记：{cls}", cls in sh)

    for anchor in ("'已删除端侧模型（2.1.0 起不再需要）'",
                   "'补字幕接力 ▶'", "'点「补字幕」· '",
                   "'☁ 译文还没生成，下一轮再补'"):
        check_true(f"日志锚点已登记：{anchor.strip(chr(39))}", anchor in sh)

    # 内嵌 python 段不得被 shell 抢先展开
    lines = sh.split("\n")
    start = next(i for i, l in enumerate(lines) if l.startswith('"$PY" -c "'))
    end = next(i for i in range(start + 1, len(lines)) if lines[i] == '"')
    seg = lines[start + 1:end]
    bad = [(i + start + 2, l) for i, l in enumerate(seg) if "$" in l or "`" in l]
    check_true("内嵌 python 段里没有 $ 或反引号（shell 会先做展开，set -u 下直接报错）",
               not bad, str(bad[:2]))

    r = subprocess.run(["bash", "-n", str(ROOT / "build-apk.sh")],
                       capture_output=True, text=True)
    check_true("build-apk.sh 语法检查通过", r.returncode == 0, r.stderr.strip()[:120])


# ============================================================
# C. 清单与锚点的闭环
# ============================================================


def newest_checklist_rel():
    """
    最新那份交付清单。

    清单与代码是同一份契约的两个副本，而副本有多个版本 —— 只有**最新那份**
    描述的是当前这个包。盯住旧清单等于在验一个已经发出去的版本。
    """
    best = None
    for p in ROOT.glob("*-重点测试清单.md"):
        m = re.match(r"^(\d+)\.(\d+)\.(\d+)", p.name)
        if m:
            key = tuple(int(g) for g in m.groups())
            if best is None or key > best[0]:
                best = (key, p.name)
    assert best, "工作区里找不到任何 <版本>-重点测试清单.md"
    return best[1]


CHECKLIST_REL = newest_checklist_rel()


def checklist_version():
    """最新清单声明的版本号，形如 `v1.1.0 (build 18)`。

    为什么不硬编码：硬编码的下一轮必然失败，而「检查器失败」很容易被
    当成「检查器坏了」，最后没人再跑它 —— 那比没有检查器更糟。
    改成以清单为准之后，它反而多拦住一类错误：清单说 v2.1.0、包里还是 2.0.4。
    """
    m = re.search(r"v(\d+\.\d+\.\d+)\s*\(build\s*(\d+)\)", read(CHECKLIST_REL))
    assert m, f"{CHECKLIST_REL} 里找不到 vX.Y.Z (build N)"
    return m.group(1), int(m.group(2))


def parse_checklist_rows():
    """
    从清单第 4 节的表里取出「应出现的内容」列。

    只认反引号包起来的第一列 —— 表里第二、三列是给人看的说明，不含契约。
    """
    text = read(CHECKLIST_REL)
    start = text.find("## 4.")
    end = text.find("## 5.")
    assert start >= 0 and end > start, "清单里找不到第 4 节"
    rows = []
    for line in text[start:end].splitlines():
        line = line.strip()
        if not line.startswith("|") or set(line) <= set("|-: "):
            continue
        cells = [c.strip() for c in line.strip("|").split("|")]
        if len(cells) < 2 or cells[0] in ("应出现的内容",):
            continue
        m = re.match(r"^`(.+?)`$", cells[0])
        if m:
            rows.append(m.group(1))
    return rows


def test_c_checklist_contract():
    print(f"\n=== C. 清单（{CHECKLIST_REL}）第 4 节 ↔ 构建锚点 的闭环 ===")

    sh = read("build-apk.sh")
    m = re.search(r"anchors = \[(.*?)\]\n", sh, re.S)
    anchors = re.findall(r"'([^']+)'", m.group(1)) if m else []

    rows = parse_checklist_rows()
    check_true("能从清单解析出对照表", len(rows) >= 15, f"{len(rows)} 行")

    # 每一行都必须被某个锚点**覆盖**：锚点要么与它相同，要么是它的前缀。
    #
    # 为什么允许前缀：带插值的日志在 dex 里会被切碎（`下载音频 ` 后面的
    # 数字是算出来的），完整句子根本不存在于常量池。前缀是能拿到的最强形式。
    uncovered = [r for r in rows if not any(r.startswith(a) for a in anchors)]
    check_true("清单每一行都有锚点覆盖（前缀匹配）", not uncovered,
               f"未覆盖：{uncovered}")

    # 反向：**每一条锚点**都要在清单里有能查到它的行 ——
    # 否则就是「验了但没写进清单」，用户真机上看到那行日志时无处可查。
    #
    # ⚠️⚠️ 这里原来是一个**写死的 2.1.0 时代字符串列表**
    #      （「已删除端侧模型」「补字幕」「译文还没生成」「取字幕」）——
    #      于是 2.1.0 之后新增的锚点**全都不在检查范围内**，
    #      而这个检查一直报「✅ 通过」。
    #
    #      代价实测出来了（3.0.5 补）：3.0.2 / 3.0.3 / 3.0.4 三份清单
    #      **各缺 22 条**锚点说明，而这份脚本一路报全过。
    #
    #      **一个只覆盖历史子集的检查，会让人以为整个方向都验过了** ——
    #      这比没有检查更糟。判据要跟着「锚点集合」这个量走，不能跟着
    #      「当时那几个字符串」走。
    #
    # ⚠️ 用**包含**而不是前缀：有些锚点本来就是同一行日志被插值切碎的
    #    **后半截**（` · 长度 12%` 是「滚动条位置 4% · 顶部 3/42 条」的尾段），
    #    要求它出现在行首没有道理。
    #    行 → 锚点 那个方向仍然用**前缀**（那一侧要证明的是
    #    「这一行说的东西在产物里找得到」，而带插值的日志在 dex 里正是从
    #    锚点那个位置被切开的）。
    orphan = [a for a in anchors if not any(a in r for r in rows)]
    check_true("每一条锚点都在清单里有说明（不留「验了但没写」）",
               not orphan, f"清单缺 {len(orphan)} 条：{orphan[:6]}")

    text = read(CHECKLIST_REL)
    check_true("清单第 0 节是「先确认装的是哪一版」",
               text.find("先确认装的是哪一版") > 0
               and text.find("先确认装的是哪一版") < text.find("本轮改了什么"))
    check_true("清单里有版本号 vX.Y.Z (build N)",
               re.search(r"v\d+\.\d+\.\d+\s*\(build\s*\d+\)", text) is not None)
    check_true("  · 且要求核对安装时间", "安装时间" in text[:3000])
    check_true("清单区分了「本机已验证」与「只有你真机能验」",
               "本机已验证" in text and "只有你真机能验" in text)
    check_true("清单里的核对表覆盖了本脚本",
               "verify-ship-contract.py" in text,
               "脚本换了名字而清单没跟 —— 清单里那份「跑过哪些脚本」的表就会开始撒谎")


# ============================================================
# D. 版本号
# ============================================================


def test_d_version():
    want_name, want_code = checklist_version()
    print(f"\n=== D. 版本号（与 {CHECKLIST_REL} 对齐）===")

    gradle = read("app/build.gradle.kts")
    m_code = re.search(r"val appVersionCode\s*=\s*(\d+)", gradle)
    m_name = re.search(r'val appVersionName\s*=\s*"([^"]+)"', gradle)

    check_true("appVersionCode 是变量声明", m_code is not None)
    check_true("appVersionName 是变量声明", m_name is not None)
    check_eq(f"versionName 与最新清单一致（{want_name}）",
             m_name.group(1) if m_name else None, want_name)
    check_eq(f"versionCode 与最新清单一致（{want_code}）",
             int(m_code.group(1)) if m_code else None, want_code)

    i_plugins = gradle.find("plugins {")
    i_close = gradle.find("}", i_plugins)
    check_true("版本号变量声明在 plugins{} **之后**（放在前面会编译失败）",
               i_plugins >= 0 and m_code is not None and m_code.start() > i_close)

    check_true("versionCode 与 versionName 在 defaultConfig 里被引用",
               "versionCode = appVersionCode" in gradle
               and "versionName = appVersionName" in gradle)

    apk = ROOT / "apk" / f"EnglishCommute-{want_name}-debug.apk"
    if apk.is_file():
        check_true(f"已产出 apk/EnglishCommute-{want_name}-debug.apk", True)
    else:
        print(f"  · 跳过产物检查（还没构建 {apk.name}）")


# ============================================================

def main():
    print("验证发布契约：备份规则 / 卸载语义 / 构建登记 / 清单闭环 / 版本号")
    print(f"当前清单：{CHECKLIST_REL}")

    test_a_backup_rules()
    test_a2_manifest()
    test_b_build_assertion()
    test_c_checklist_contract()
    test_d_version()

    print()
    print("=" * 62)
    print(f"通过 {PASS} 项，失败 {FAIL} 项")
    if FAIL:
        print()
        for desc, extra in FAILURES:
            print(f"  - {desc}" + (f"  ← {extra}" if extra else ""))
        print("❌ 有断言未通过")
        return 1
    print("✅ 全部通过")
    print("=" * 62)
    return 0


if __name__ == "__main__":
    sys.exit(main())
