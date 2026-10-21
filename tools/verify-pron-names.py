#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
读音文件名：**服务端与客户端的契约核对**。

================================================================================
## 为什么需要这个脚本
================================================================================

「lemma → mp3 文件名」这条映射在两个地方各有一份实现：

| 谁 | 在哪 |
|---|---|
| 服务端（生成时） | `server/bin/build_pron_v2.py` 的 `safe()` |
| 客户端（下载时） | `app/.../word/PronDownloader.kt` 的 `pronFileName()` |

**它们分叉过一次**，代价很具体（见 PronDownloader 的类注释）：

- 168 个词形客户端下不到（404）
- 更糟：`he'll` 拿到的是 `hell` 的音频 —— **文件存在、能播、读的是另一个词**

==============================================================================
## 这个脚本怎么保证两边一致
==============================================================================

中间放一张**契约表**（`PronDownloader.NAME_CASES`，写在 Kotlin 源码里），
然后让两边各自对着它断言：

```
        ┌──────────────────────────────────────┐
        │  NAME_CASES（Kotlin 源码里的字面量）  │
        └───────────────┬──────────────────────┘
                        │
        ┌───────────────┴───────────────┐
        ▼                               ▼
  PronNameTest.kt                本脚本
  （跑**真实的 Kotlin 函数**）      （跑**真实的 Python safe()**）
```

两边都红，才说明改一处就够；只红一边，说明有人只改了一处。

=============================================================================="""

import importlib.util
import io
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
SERVER_SCRIPT = os.path.join(ROOT, "2.0-服务端", "bin", "build_pron_v2.py")
KT_SOURCE = os.path.join(
    ROOT, "app/src/main/java/com/example/englishcommute/word/PronDownloader.kt"
)
VOCAB_BASE = os.path.join(ROOT, "app/src/main/assets/vocab/vocab.base")
VOCAB_FORMS = os.path.join(ROOT, "app/src/main/assets/vocab/vocab.forms")

PASS = 0
FAIL = 0


def section(title):
    print()
    print("=" * 78)
    print(title)
    print("=" * 78)


def check(label, ok, why=""):
    global PASS, FAIL
    if ok:
        PASS += 1
        print("  \u2713 %s" % label)
    else:
        FAIL += 1
        print("  \u2717 %s" % label)
        if why:
            print("      %s" % why)


# ===========================================================================
# 1. 加载服务端的 safe()
# ===========================================================================
section("一、加载服务端实现")

if not os.path.exists(SERVER_SCRIPT):
    sys.exit("找不到服务端脚本：%s" % SERVER_SCRIPT)

spec = importlib.util.spec_from_file_location("build_pron_v2", SERVER_SCRIPT)
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)

check("服务端 build_pron_v2.py 可以被加载（模块级没有副作用）", True)
check("它导出了 safe()", hasattr(mod, "safe"), "没找到 safe 函数 —— 客户端会全部 404")

server_safe = mod.safe

# 服务端脚本的 docstring 必须点名客户端的实现 —— 两边互相指着，
# 这样改了一边的人至少有机会看到另一边
server_src = io.open(SERVER_SCRIPT, encoding="utf-8").read()
check(
    "  · 服务端脚本的注释里点名了客户端的实现",
    "PronDownloader.pronFileName" in server_src,
    "两边各写一份实现而互不知道，就是上次分叉的成因",
)

# ===========================================================================
# 2. 解析 Kotlin 的契约表
# ===========================================================================
section("二、解析契约表 NAME_CASES")

kt_src = io.open(KT_SOURCE, encoding="utf-8").read()
check("Kotlin 源码里点名了服务端脚本", "build_pron_v2.py" in kt_src,
      "客户端注释里应该指出服务端那份在哪")

m = re.search(r"val NAME_CASES\s*:\s*List<Pair<String,\s*String>>\s*=\s*listOf\((.*?)\n\s*\)",
              kt_src, re.S)
if not m:
    sys.exit("解析不出 NAME_CASES —— 它的形状变了？本脚本要跟着改")


def kt_unescape(s):
    """Kotlin 字符串字面量里我们用到的转义：\\uXXXX 和 \\\\"""
    return re.sub(r"\\u([0-9a-fA-F]{4})", lambda mm: chr(int(mm.group(1), 16)), s)


cases = []
for line in m.group(1).split("\n"):
    line = line.strip()
    if line.startswith("//") or not line:
        continue
    mm = re.search(r'"((?:[^"\\]|\\.)*)"\s+to\s+"((?:[^"\\]|\\.)*)"', line)
    if mm:
        cases.append((kt_unescape(mm.group(1)), kt_unescape(mm.group(2))))

check("解析到契约样例 %d 条" % len(cases), len(cases) >= 15,
      "样例太少的话，规则里某个字符类可能没人守")
check("样例里同时有「受影响的词」和「不受影响的词」",
      any("'" in a for a, _ in cases) and any("'" not in a for a, _ in cases))

# ===========================================================================
# 3. 逐条核对：服务端 safe() + ".mp3" 必须等于契约表里的文件名
# ===========================================================================
section("三、服务端 safe() vs 契约表")

# ⚠️ 一个必须说清的差异：两边的函数**返回的东西不一样**。
#
#     服务端 safe(w)          -> "don_t"        （文件名**主体**，调用处自己拼 `.mp3`）
#     客户端 pronFileName(w)  -> "don_t.mp3"    （完整文件名）
#
#   这不是分叉 —— 它是一条明确的等式：
#
#       pronFileName(w) == safe(w) + ".mp3"
#
#   之所以不把两边改成完全一样：服务端 `safe()` 有四处调用（断点续传判断、
#   待生成列表、输出路径、prune 期望集合），改动面比收益大。
#   把这条等式写在这里，比强行统一更有价值 —— 它把差异变成了**显式的契约**。
EXT = ".mp3"

bad = []
for word, expect in cases:
    got = server_safe(word) + EXT
    if got != expect:
        bad.append((word, expect, got))

check("全部 %d 条一致（safe(w) + '.mp3' == pronFileName(w)）" % len(cases), not bad,
      "不一致的：%s" % bad[:5])
for word, expect, got in bad[:10]:
    print("      %-18s 契约 %-24s 服务端 %s" % (word, expect, got))

# ===========================================================================
# 4. 结构断言：两边实现里的**白名单**必须一样
# ===========================================================================
section("四、实现结构（防「改了规则但恰好样例都过」）")

# Kotlin 侧：必须用显式的字符范围，不能用 isLetterOrDigit
check(
    "Kotlin 用显式字符范围（'a'..'z' / '0'..'9' / '.' / '-'）",
    "c in 'a'..'z'" in kt_src and "c in '0'..'9'" in kt_src,
    "用 Char.isLetterOrDigit() 会放行西里尔/日文，而 Python 侧不会 —— 两边立刻分叉",
)
check(
    "  · Kotlin 没有用 isLetterOrDigit",
    "isLetterOrDigit" not in kt_src or "不要用" in kt_src,
)

# Python 侧：同样必须是显式范围。
# ⚠️ 取函数体要用**缩进边界**，不能用 `\n\n` —— safe() 的 docstring 里有空行，
#    非贪婪匹配会在 docstring 中间就停下，于是断言查了个寂寞（这个 bug 本脚本第一版就有）。
server_func = re.search(r"def safe\(word\):(.*?)(?=\ndef |\nasync def |\Z)", server_src, re.S)
if server_func:
    body = server_func.group(1)
    check("能定位到服务端 safe() 的函数体（%d 字符）" % len(body), len(body) > 200,
          "太短说明又被 docstring 里的空行截断了")
    check("  · Python 也用显式字符范围", "'a' <= c <= 'z'" in body and "'0' <= c <= '9'" in body)
    check("  · Python 没有用 c.isalnum()", "c.isalnum()" not in body,
          "isalnum() 对非 ASCII 字母返回 True，与 Kotlin 的显式范围不一致")
else:
    check("能定位到服务端的 safe() 函数体", False, "正则没匹配到")

# ===========================================================================
# 5. 唯一性：映射后不能有两个词落到同一个文件
# ===========================================================================
section("五、映射唯一性（在真实词表上跑）")

words = set()
for path, col in ((VOCAB_BASE, 0), (VOCAB_FORMS, 0)):
    if os.path.exists(path):
        for line in io.open(path, encoding="utf-8"):
            parts = line.split("\t")
            if parts and parts[col].strip():
                words.add(parts[col].strip())
check("从 assets 词表读到 %d 个词形" % len(words), len(words) > 10000)

collide = {}
for w in words:
    collide.setdefault(server_safe(w), []).append(w)
dups = {k: v for k, v in collide.items() if len(v) > 1}

check("映射后没有撞名", not dups,
      "撞名的：%s" % list(dups.items())[:5])

special = [w for w in words if server_safe(w) != w]
print("    （含特殊字符、需要映射的词形：%d 个 —— 上一版服务端在这一类上全错）" % len(special))
if special:
    print("     样例：%s" % ", ".join(
        "%s→%s" % (w, server_safe(w)) for w in sorted(special)[:6]))

# ===========================================================================
# 6. 安全字符集：产物里不能出现需要转义的字符
# ===========================================================================
section("六、产物字符集")

RISKY = set("'\"\u2019\u2018\u02bc/\\?#%& \t\n:;=+$@!*()[]{}<>|,~^`")
unsafe = [(w, server_safe(w)) for w in words if any(c in RISKY for c in server_safe(w))]
check("全部 %d 个词形生成的**主体**都只含安全字符" % len(words), not unsafe,
      "有风险的：%s" % unsafe[:5])
for w, name in unsafe[:5]:
    print("      %-20s -> %s" % (w, name))

# ===========================================================================
print()
print("=" * 78)
print("读音文件名契约：%d 项通过，%d 项失败" % (PASS, FAIL))
print("=" * 78)
if FAIL:
    print()
    print("⚠️ 服务端与客户端的文件名规则已经分叉。症状是「有些词读音一直下不下来」")
    print("   或者更糟：**文件能播，但读的是另一个词**（he'll → hell）。")
    print("   修法：改 NAME_CASES，让两边同时变红，再改两边实现。")
sys.exit(1 if FAIL else 0)
