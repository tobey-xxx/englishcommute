#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
生词本落盘路径的契约（3.0.3 新建）。

## 为什么需要这个脚本

3.0.0 埋了一个真 bug，一路活到用户手上：

    `PronDownloader` 往 `filesDir/words/audio/` 写读音文件，
    而创建 `words/` 的是**另一个类**（`VocabAddSync`）——
    那个子目录**从来没有人创建过**。

后果链：

    写文件抛 FileNotFoundException（IOException 的子类）
      → 被兜底翻译成「网络不通」
      → 用户按提示去查网络（网络明明是好的）
      → 真机表现：「生词本里，复习显示读音没下来，其他功能正常」

**单测也发现不了**：测试的 `setUp` 里写了 `audioDir.mkdirs()` ——
等于测试**替被测对象做了它该做的事**。

所以这里守三条：

  ① 往 `words/` 下写文件的每个类，在写之前都自己 `mkdirs()`
     （不依赖「别人应该已经建过了」—— 那个假设没有任何东西守着）
  ② 错误文案必须把「本地存不下」和「网络不通」分开
     （两类问题的下一步动作完全不同）
  ③ 测试不许替被测对象建目录（否则第 ① 条永远测不出来）
"""
import io
import os
import re
import sys

ROOT = os.path.dirname(os.path.abspath(__file__)) + "/.."
WORD = os.path.join(ROOT, "app/src/main/java/com/example/englishcommute/word")
TEST = os.path.join(ROOT, "app/src/test/java/com/example/englishcommute/word")

ok_count = 0
fail_count = 0


def check(name, cond, why=""):
    global ok_count, fail_count
    if cond:
        ok_count += 1
        print("  ✅ %s" % name)
    else:
        fail_count += 1
        print("  ❌ %s" % name)
        if why:
            print("       %s" % why)


def read(p):
    return io.open(p, encoding="utf-8").read()


def section(t):
    print()
    print("=" * 68)
    print("  " + t)
    print("=" * 68)


print()
print("  生词本落盘路径契约（3.0.3）")

# ===========================================================================
section("一、往 words/ 下写文件的类，写之前都必须自己建目录")
# ===========================================================================
#
# 判据是「这个文件里有写盘动作（writeText / outputStream / copyTo 落到 words 下），
# 就必须有 mkdirs()」。**不判「有几个 mkdirs」** —— 数量会随实现变，
# 那是「写死数量 ⇒ 合法改动就假失败」的写法。

# 题面：这三个类各自写什么，以及它们的目录是不是同一个
CASES = [
    ("WordStore.kt", "words/words.json", "dir.mkdirs()"),
    ("VocabAddSync.kt", "words/vocab.add.json", "dir.mkdirs()"),
    ("PronDownloader.kt", "words/audio/<safe>.mp3", "audioDir.mkdirs()"),
]

for fname, what, expect in CASES:
    path = os.path.join(WORD, fname)
    if not os.path.exists(path):
        check("%s 存在" % fname, False, "文件不在了？")
        continue
    src = read(path)
    check(
        "%s 写 %s 前自己建目录" % (fname, what),
        expect in src,
        "缺 %s ⇒ 目录不存在时写盘会抛 FileNotFoundException" % expect,
    )

# 反向：确认没有哪个类在「没有 mkdirs」的情况下写文件
for fname, what, expect in CASES:
    src = read(os.path.join(WORD, fname))
    writes = bool(re.search(r"outputStream\(\)|writeText\(|copyTo\(", src))
    check("  · %s 确实有写盘动作（判据不是空转）" % fname, writes)

# ===========================================================================
section("二、两类失败必须分开报（用户的下一步动作不同）")
# ===========================================================================
#
# ⚠️ 这一条是那个 bug「骗人」的地方：`FileNotFoundException` 是 `IOException`
#    的子类，所以 `is IOException -> "网络不通"` 会把它吃掉。

pd = read(os.path.join(WORD, "PronDownloader.kt"))
check(
    "PronDownloader 把 FileNotFoundException 单独识别出来",
    "is FileNotFoundException" in pd,
    "不分开的话，「本地目录不存在/存不下」会被报成「网络不通」",
)
check(
    "  · 写盘错误有自己的文案（不是复用网络那句）",
    "本地存不下" in pd or "写入本地文件失败" in pd,
)
check(
    "  · 写盘被单独包了一层 runCatching",
    "val writeError = runCatching" in pd,
    "不分开包，「存不下」和「下不来」就会落到同一个兜底里",
)

# 坏样本：把 FileNotFoundException 那一支删掉，上面第二条判据必须为假
bad = pd.replace("is FileNotFoundException ->", "is java.io.IOException ->", 1)
check(
    "坏样本（把 FileNotFoundException 支删掉）被拦下",
    "is FileNotFoundException" not in bad,
    "反向断言必须能真的失败",
)

# ===========================================================================
section("三、测试不许替被测对象建目录")
# ===========================================================================
#
# ⚠️ 这是那个 bug 逃过单测的**直接原因**：`setUp` 里写了
#    `File(dir, "audio").apply { mkdirs() }`，于是「目录不存在」这个前提
#    在测试里永远不成立。

bf = read(os.path.join(TEST, "PronBackfillTest.kt"))

# 判据：setUp 里不许出现「给 audioDir 建目录」
check(
    "PronBackfillTest 的 setUp 不替被测对象建 audioDir",
    not re.search(r"audioDir\s*=\s*File\([^)]*\)\s*\.apply\s*\{\s*mkdirs", bf),
    "setUp 建了目录 ⇒ 「目录不存在时会自己建」那条测试恒真",
)
check(
    "  · 有那条专门的测试",
    "音频目录不存在时会自己建" in bf,
)
check(
    "  · 它的第一句断言了「前提：目录不存在」",
    "前提：这个目录现在必须是不存在的" in bf,
    "少了这句，测试会退化成恒真 —— 恒真的测试比没有测试更糟",
)

ws = read(os.path.join(TEST, "WordStoreTest.kt"))
check(
    "WordStoreTest 有「目录不存在也能落盘」",
    "目录不存在时也能落盘" in ws,
)

# ===========================================================================
print()
print("=" * 68)
print("  通过 %d 项，失败 %d 项" % (ok_count, fail_count))
print("=" * 68)
if fail_count:
    print("❌ 有判据失败")
    sys.exit(1)
print("✅ 全部通过")
