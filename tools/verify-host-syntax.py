#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
**宿主语法的坑**：那些「写注释写出来的错」。

## 为什么值得单独一个脚本

这三个坑有一个共同形状：**错的地方和报出来的地方不是同一处**，
而且每一条都在本工程真实发生过（各花了至少一轮排查）。

| 坑 | 报错长什么样 | 真实原因 |
|---|---|---|
| Kotlin 注释里出现「斜杠 + 星号」 | `Unclosed comment`，**行号在文件末尾** | Kotlin 的块注释**可以嵌套**，那个星号开了一层没人收的注释 |
| XML 注释里出现连续两个减号 | 编译期报 `Element type must be followed by...` 之类的怪错 | XML 注释**不允许** `--` |
| shell 里嵌的 python 段出现反引号 / 美元符 | `set -u` 下直接以一句无关的 `unbound variable` 退出 | shell 先做命令替换和变量展开，**再**交给 python |

它们都不是「逻辑写错了」，而是「在 A 语言里写 B 语言的注释时，
A 的语法规则不会为你让路」。而人眼**看不出**这类错 ——
三种字符组合在屏幕上和正常文本没有任何区别。

## 这个脚本守什么

1. 所有 `.kt` 的**注释内部**不出现 `/*`（Kotlin 注释可嵌套）
2. 所有 `.xml` 的**注释内部**不出现 `--`
3. `build-apk.sh` 的**内嵌 python 段**里不出现反引号与 `$`

⚠️ 只查**注释内部**和**内嵌段内部** —— 代码里的 `/*`（比如正则、路径拼接）
   是合法的，一起查会得到一堆假失败，而假失败会让人不再跑这个脚本。
"""
import io
import os
import re
import sys

ROOT = os.path.dirname(os.path.abspath(__file__)) + "/.."
KT_ROOT = os.path.join(ROOT, "app/src/main/java")
RES_ROOT = os.path.join(ROOT, "app/src/main/res")
BUILD_SH = os.path.join(ROOT, "build-apk.sh")

PASS, FAIL = 0, 0
FAILURES = []


def check(name, ok, why=""):
    global PASS, FAIL
    if ok:
        PASS += 1
        print("  ✅ %s" % name)
    else:
        FAIL += 1
        FAILURES.append(name)
        print("  ❌ %s" % name)
        if why:
            print("       %s" % why)


def section(t):
    print()
    print("=" * 68)
    print("  " + t)
    print("=" * 68)


def walk(root, ext):
    for dirpath, _, files in os.walk(root):
        for f in files:
            if f.endswith(ext):
                yield os.path.join(dirpath, f)


# ===========================================================================
section("一、Kotlin 注释里不许出现「斜杠 + 星号」")
# ===========================================================================
# Kotlin 的块注释**可以嵌套**：`/* a /* b */ c */` 是合法的。
# 所以在 KDoc 里写一个路径通配（`assets/vocab` 后面跟一个星号）会开出一层
# 新的注释，而后面没有对应的收尾 —— 编译器报 `Unclosed comment`，
# **行号是文件末尾**，和真正出问题的那一行隔着几十行。
#
# 本工程为此白跑了一整轮编译（3.3.0）。而在 XML 那边，同形状的坑
# （注释里不许出现连续两个减号）已经踩过三次。

KT_OPEN = re.compile(r"/\*")


def comment_spans(src):
    """所有**注释**的区间 `(start, end)`。

    ⚠️ 必须自己扫，不能用「找 /* ... */ 再找 //」这种朴素写法：
       字符串字面量里也可能有这两个字符，而那种命中是**假失败**。
       所以这里先跳过字符串（含转义、含三引号），只认真正的注释。
    """
    spans, i, n = [], 0, len(src)
    while i < n:
        c = src[i]
        if c == '"':
            if src.startswith('"""', i):
                j = src.find('"""', i + 3)
                i = n if j < 0 else j + 3
            else:
                j = i + 1
                while j < n:
                    if src[j] == "\\":
                        j += 2
                        continue
                    if src[j] == '"' or src[j] == "\n":
                        j += 1
                        break
                    j += 1
                i = j
        elif src.startswith("//", i):
            j = src.find("\n", i)
            j = n if j < 0 else j
            spans.append((i, j))
            i = j
        elif src.startswith("/*", i):
            depth, j = 1, i + 2
            while j < n and depth:
                if src.startswith("/*", j):
                    depth += 1
                    j += 2
                elif src.startswith("*/", j):
                    depth -= 1
                    j += 2
                else:
                    j += 1
            spans.append((i, j))
            i = j
        else:
            i += 1
    return spans


def bad_kotlin_comments(path):
    """注释**内部**（不含注释自己的开头 `/*`）出现 `/*` 的位置"""
    src = io.open(path, encoding="utf-8").read()
    bad = []
    for s, e in comment_spans(src):
        body = src[s + 2:e]
        for m in KT_OPEN.finditer(body):
            line = src.count("\n", 0, s + 2 + m.start()) + 1
            bad.append((line, src.split("\n")[line - 1].strip()[:70]))
    return bad


kt_files = sorted(walk(KT_ROOT, ".kt"))
hits = []
for p in kt_files:
    for line, text in bad_kotlin_comments(p):
        hits.append((os.path.relpath(p, ROOT), line, text))

check("扫了足够多的 Kotlin 文件（不是扫了个空目录）", len(kt_files) >= 60, "%d 个" % len(kt_files))
check("没有注释里带 `/*` 的（Kotlin 块注释会嵌套）",
      not hits,
      "\n       ".join("%s:%d  %s" % h for h in hits[:5])
      + ("\n       ⇒ 编译器会报 `Unclosed comment` 并把行号指到文件末尾" if hits else ""))

# 自测：这个检查器**必须真的能发现**这个问题（否则它就是一句注释）
_self = os.path.join(ROOT, "tools", "_selfprobe.kt")
io.open(_self, "w", encoding="utf-8").write(
    "package x\n\n/** 看看 assets/vocab/* 这种写法 */\nclass A\n"
)
probe = bad_kotlin_comments(_self)
os.remove(_self)
check("  · 自测：构造出来的那一段能被判出来（检查器有区分力）",
      len(probe) == 1,
      "探针没报出来 ⇒ 这个检查恒真，等于没做")

# ⚠️ 反向探针。这一条不是「顺手加的」：一个只查 `/*` 的朴素实现会把
#    **字符串里的** `/*` 也算进来（正则、路径拼接里很常见），
#    于是脚本一跑就是一片红 —— 而**假失败会让人不再跑这个脚本**。
_self2 = os.path.join(ROOT, "tools", "_selfprobe2.kt")
io.open(_self2, "w", encoding="utf-8").write(
    'package x\n\n// 注释里提到 assets/vocab 这个目录（没有星号）\n'
    'val p = "a/*b"\nval r = Regex("x/*y")\n'
)
probe2 = bad_kotlin_comments(_self2)
os.remove(_self2)
check("  · 自测：字符串与代码里的 `/*` **不会**被误报",
      not probe2,
      "探针报出了 %s —— 假失败会让人不再跑这个脚本，"
      "而它守的正是「注释写错、报错却指到别处」那一类" % (probe2,))

# ===========================================================================
section("二、XML 注释里不许出现连续两个减号")
# ===========================================================================
# XML 的注释语法**不允许** `--` 出现在注释体里。本工程踩过三次
# （最近一次是在注释里写命令行参数 `--base-url`）。
# 症状是编译期的报错完全指不到那一行。

xml_files = sorted(walk(RES_ROOT, ".xml")) + [
    os.path.join(ROOT, "app/src/main/AndroidManifest.xml")
]
xml_hits = []
for p in xml_files:
    if not os.path.isfile(p):
        continue
    src = io.open(p, encoding="utf-8").read()
    for m in re.finditer(r"<!--(.*?)-->", src, re.S):
        body = m.group(1)
        if "--" in body:
            line = src.count("\n", 0, m.start()) + 1
            xml_hits.append((os.path.relpath(p, ROOT), line))

check("扫了足够多的 XML（不是扫了个空目录）", len(xml_files) >= 5, "%d 个" % len(xml_files))
check("XML 注释里没有连续两个减号",
      not xml_hits,
      "命中：%s ⇒ 命令行参数要写进注释的话，把两个减号换成别的说法"
      % (xml_hits[:5],))

# ===========================================================================
section("三、build-apk.sh 的内嵌 python 段")
# ===========================================================================
# 那一段是 shell **双引号**字符串里的 python 源码：shell 会先做命令替换
# （反引号）和变量展开（`$`），再交给 python。所以段里出现这两个字符，
# 报的是 shell 的错，而 `set -u` 下会以一句 `unbound variable` 直接退出 ——
# 看起来和 python 那边毫无关系。

if os.path.isfile(BUILD_SH):
    lines = io.open(BUILD_SH, encoding="utf-8").read().split("\n")
    starts = [i for i, l in enumerate(lines) if l.startswith('"$PY" -c "')]
    check("build-apk.sh 里找得到内嵌 python 段", bool(starts), "找不到 ⇒ 断言结构变了，要跟着改")
    if starts:
        s = starts[0]
        e = next((i for i in range(s + 1, len(lines)) if lines[i] == '"'), len(lines))
        seg = lines[s + 1:e]
        bad = [(s + i + 2, l.strip()[:60]) for i, l in enumerate(seg) if "$" in l or "`" in l]
        check("  · 段里没有 `$` 与反引号（shell 会先展开）",
              not bad,
              "\n       ".join("%d: %s" % b for b in bad[:5]))
        check("  · 段足够长（不是找到了一个空段）", len(seg) >= 50, "%d 行" % len(seg))

# ===========================================================================
print()
print("=" * 68)
print("  通过 %d 项，失败 %d 项" % (PASS, FAIL))
print("=" * 68)
if FAIL:
    print()
    print("  ❌ 未通过：")
    for f in FAILURES:
        print("     - " + f)
print()
sys.exit(1 if FAIL else 0)
