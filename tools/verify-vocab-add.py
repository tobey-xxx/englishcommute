#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
词表增量链路的契约锁（3.0.1）。

================================================================================
## 为什么要专门一个脚本
================================================================================

这条链路的特点：**任何一环断掉，用户看到的现象都一模一样** ——

    「这个词还是查不到」

而日志里可能明明写着「词表增量已更新：+141 个新词」。

| 断在哪 | 现象 | 日志 |
|---|---|---|
| 服务端没算 | 永远查不到 | 什么都没有 |
| 算错方向（published − latest） | 查不到，而且增量是空的 | 「+0 个新词」 |
| 客户端没拉到 | 查不到 | 「拉取失败」（这一条能查） |
| **拉到了但没让词表缓存失效** | **查不到** | **「+141 个新词」← 骗人的** |
| 合并顺序反了（主表覆盖增量） | 内置表里有的词永远修不正 | 「+141 个新词」← 也是骗人的 |

后两行是**最贵的**：日志说成功，实际没生效。所以这里把
「机制在不在」逐条钉住 —— 效果对不对由真机验收负责（清单 §2）。

## 判据一律用 strip_comments 之后的源码

这几个文件的注释里大量讨论这些机制（尤其是「为什么必须 invalidate」），
直接搜原始源码会命中注释 —— 那是一条**永远为真**的断言。
"""

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
JAVA = ROOT / "app/src/main/java/com/example/englishcommute"
SERVER = ROOT / "server/bin"

PASS = 0
FAIL = 0
FAILURES = []


def check_true(name, ok, why=""):
    global PASS, FAIL
    if ok:
        PASS += 1
        print(f"  ✓ {name}")
    else:
        FAIL += 1
        FAILURES.append(name + (f"  ← {why}" if why else ""))
        print(f"  ✗ {name}" + (f"\n      ↳ {why}" if why else ""))


def section(t):
    print(f"\n{t}")


def strip_comments(src):
    out = []
    i, n = 0, len(src)
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
            out.append(src[i:j + 1])
            i = j + 1
        else:
            out.append(src[i])
            i += 1
    return "".join(out)


def py(rel):
    return (SERVER / rel).read_text(encoding="utf-8")


def kt(rel):
    return strip_comments((JAVA / rel).read_text(encoding="utf-8"))


# ===========================================================================
section("一、服务端：生成脚本")
# ===========================================================================
gen = py("build_vocab_add.py")

check_true(
    "版本号来自**内容哈希**（不是时间戳）",
    "hashlib.sha1()" in gen and "version = h.hexdigest()" in gen,
    "用时间戳的话每次重建都算「新版本」，客户端每轮拉一次几 KB 而内容毫无变化",
)
check_true(
    "  · 而且是**排序后**再算哈希（顺序不同不算新版本）",
    "for line in base_lines:" in gen,
)
check_true(
    "有数量上限保护（防止 .published 太旧时一次发全量）",
    "MAX_ENTRIES" in gen,
)
check_true(
    "写盘用 tmp + rename",
    "os.replace(tmp, OUT)" in gen,
    "客户端会在同步时拉它，半截 JSON 会让解析失败",
)
check_true(
    "⚠️ 缺 .published 时**降级成不发**，而不是发全量",
    "本次不生成 add.json" in gen,
    "发全量 = 把用户已经有的 11795 个词又传一遍，纯浪费流量",
)
check_true(
    "差集方向是 latest − published",
    "if k not in published" in gen,
)
check_true(
    "  · 变形同样做差集，且只留目标在最新全量里的",
    "new_forms = {k: v for k, v in new_forms.items() if v in latest}" in gen,
)

# cycle：必须挂在发布收口处，否则机制形同虚设（脚本在，但从没人跑）
cycle = py("cycle.py")
check_true(
    "服务端循环里**真的调用了**它（挂在发布收口处）",
    "refresh_vocab()" in cycle and "def refresh_vocab():" in cycle,
    "光有脚本没人跑 = 用户永远拿不到新词，而这一点从客户端完全看不出来",
)
check_true(
    "  · 且刷新失败**不影响发布本身**",
    "发布本身不受影响" in cycle,
    "词表是锦上添花，不该让「算词表失败」带出「这一轮内容都别发」",
)

# ===========================================================================
section("二、客户端：合并与缓存失效")
# ===========================================================================
table = kt("word/WordTable.kt")
check_true(
    "WordTable.parse 支持增量（extraBase / extraForms）",
    "extraBase: List<String> = emptyList()" in table
    and "extraForms: List<String> = emptyList()" in table,
)
check_true(
    "⚠️ 增量在**主表之后**合并 —— 同名时增量覆盖内置",
    table.find("extraBase.forEach") > table.find("base.forEachLine"),
    "反过来的话服务端永远没法修正一个错词条，只能发新 APK（18 MB）",
)

sync = kt("word/VocabAddSync.kt")
check_true("VocabAddResult 是三种情况（不是一个 Boolean）",
           all(x in sync for x in ("UpToDate", "Updated", "Unreachable")))
check_true(
    "⚠️ 本地解析失败时**返回空表而不是抛**（增量坏了不该拖垮主表）",
    "增量词表解析失败，本次按空处理" in sync,
)
check_true(
    "写盘前确保目录在（首装时 words/ 还不存在）",
    "dir.mkdirs()" in sync,
)

loader = kt("word/WordTableLoader.kt")
check_true(
    "词表加载器接了增量（注入而不是自己 new）",
    "private val vocabAdd: VocabAddSync" in loader,
)
check_true(
    "  · 且提供 invalidate()",
    "fun invalidate()" in loader and "cached = null" in loader,
)

engine = kt("sync/SyncEngine.kt")
check_true(
    "⚠️⚠️ 同步拉到新版本后**让词表缓存失效**",
    "wordTable.invalidate()" in engine,
    "少了这一行：日志说「+141 个新词」而用户查词毫无变化 —— 那种错会让人去查网络",
)
_bad = engine.replace("wordTable.invalidate()", "", 1)
check_true(
    "坏样本（删掉 invalidate）被拦下",
    "wordTable.invalidate()" not in _bad and "wordTable.invalidate()" in engine,
    "反向断言不能失败时，它就只是一句写在脚本里的注释",
)
check_true(
    "增量**不参与**这一轮同步的成败判断",
    "is VocabAddResult.Unreachable -> \"\"" in engine,
    "拉不到增量不该把「更新失败」摆到用户面前 —— 内置表照常工作",
)

# 「查不到 ≠ 已是最新」：这条纪律在 Updater 里有过，增量这边必须同样成立
check_true(
    "「拉不到」不等于「已是最新」（两个不同的返回分支）",
    "VocabAddResult.Unreachable(" in sync and "VocabAddResult.UpToDate(" in sync,
    "把两者混起来的后果：断网时用户以为自己手里的词表是新的",
)
check_true(
    "  · 拉不到时**保留**本地那份（不清空）",
    "本地那份保持不变" in sync,
)

# ===========================================================================
section("三、分发")
# ===========================================================================
# ⚠️ 3.1.0：nginx 配置从一份拆成了四份（ec / ec-domain / ec-tls / ec-routes）。
#    这里改成把整个目录拼起来再找。
#    写死 `ec.conf` 的话，这条断言会在「location 搬家」时变成**假失败** ——
#    而假失败会让人不再跑这个脚本（比没有断言更糟）。
conf = "\n".join(
    p.read_text(encoding="utf-8")
    for p in sorted((ROOT / "server/nginx").glob("*.conf"))
)
check_true(
    "nginx 给 /vocab/add.json 设了 no-store",
    "location = /vocab/add.json" in conf and "no-store" in conf,
    "它**文件名不带内容版本**，缓存住之后客户端会一直拿到旧的那份「新词」而无法自愈",
)
# 3.1.0 收口：/vocab/ 下其余文件（词表全量、例句库、*.published 发布账本、
# 抽样文件）原来落在文件末尾的 try_files 兜底上 —— 公网可以直接下载。
check_true(
    "  · 且 /vocab/ 的其余部分一律 404（3.1.0 收口）",
    "location ^~ /vocab/" in conf and "return 404" in conf,
    "上域名之后这是**公网可发现**的路径：里面是服务端自己的中间产物，"
    "不属于要公开的东西。⚠️ 用 return 404 而不是 deny all —— 后者回 403，"
    "等于告诉扫描的人「这里确实有东西」",
)

# ===========================================================================
section("四、用户可见文案（那句话曾经是假的）")
# ===========================================================================
sheet = kt("ui/WordCardSheet.kt")
review = kt("ui/ReviewScreen.kt")
check_true(
    "词卡不再说「下一期同步后就会有」",
    "下一期同步后就会有" not in sheet and "下一期同步后就会有" not in review,
    "3.0.0 时那句话是**假的** —— 同步链路根本不碰词表，服务端也没有生成脚本。"
    "是用户这一问把机制补上的，不是反过来",
)
check_true(
    "  · 改成了「素材更新一次就会补上」（与真实机制一致）",
    "素材更新一次就会补上" in sheet and "素材更新一次就会补上" in review,
)

# ===========================================================================
print()
print("=" * 62)
print(f"通过 {PASS} 项，失败 {FAIL} 项")
if FAIL:
    print("\n❌ 有断言未通过：")
    for f in FAILURES:
        print("  - " + f)
    sys.exit(1)
print("✅ 全部通过")
print("=" * 62)
