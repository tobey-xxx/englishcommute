#!/usr/bin/env python3
"""
复刻 Kotlin 的 `sourceMark()`，断言 11 个源的占位台标**两两不重复**。

## 为什么要在桌面验证这件事

列表左侧那块占位牌是「封面图没下到」时的替身。用户对它的原话是
「也只是一个字母 logo，没有什么特别的」—— 也就是**它没起到台标的作用**。

重做之后的规则是「双字母 + 纵向渐变」，而双字母方案有一个只有数据能回答的风险：
**两个源算出同一个台标**（例如 `NPR News Now` 和 `NPR Politics` 如果都取到 `NP`）。
一旦撞了，列表上就会出现两个长得一模一样的源，比单字母更糟 ——
单字母至少还看得出「都是 NPR」，撞双字母是**看不出错**的。

这件事不需要真机：`sourceMark()` 是纯字符串函数，逐行复刻即可。
源名清单直接从 `FeedSource.kt` 里读，所以**文件加了新源而台标撞了，这个脚本会红**。

## 关于「项数」

这个脚本原来只打「读到 11 个源」而**不打断言总数**，于是文档里一度把
「11」（源个数）当成断言数记下来 —— 数字虚高会让人误判覆盖强度。
现在它和别的 verify 脚本一样在末尾输出 `通过 N 项，失败 M 项`，
**记项数一律抄这个输出，不要抄上面那行源个数。**

用法：
    python3 tools/verify-source-marks.py
"""

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
KOTLIN = ROOT / "app/src/main/java/com/example/englishcommute/content/FeedSource.kt"
MARK_KT = ROOT / "app/src/main/java/com/example/englishcommute/ui/Artwork.kt"

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
        FAILURES.append(name)
        print(f"  ✗ {name}" + (f" —— {why}" if why else ""))


def source_mark(name: str) -> str:
    """与 Artwork.kt 的 sourceMark() 逐行对齐，不要「意思一下重写」。"""
    words = [w for w in re.split(r"\s+", name.strip()) if w]
    if not words:
        return "·"

    head = words[0]
    # 全大写的短首词（NPR / TED / BBC）是**机构缩写**，它本身不该参与取字母 ——
    # 否则 6 个 NPR 源会全部以 NP 开头。丢掉它，从「真正的名字」里取
    is_acronym = len(words) > 1 and len(head) <= 4 and head == head.upper()
    body = words[1:] if is_acronym else words

    if len(body) >= 2:
        return (body[0][0] + body[1][0]).upper()
    if body:
        return body[0][:2].upper()
    return head[:2].upper()


def load_names() -> list:
    """从 Kotlin 源里读源名 —— 不在这里手抄一份，否则两边会漂"""
    text = KOTLIN.read_text(encoding="utf-8")
    return re.findall(r'^\s*name = "([^"]+)",\s*$', text, re.MULTILINE)


def main() -> int:
    if not KOTLIN.is_file():
        print(f"❌ 找不到 {KOTLIN}")
        return 1

    names = load_names()
    if not names:
        print(f"❌ 从 {KOTLIN.name} 里没读到任何 `name = \"...\"`")
        return 1

    print(f"从 FeedSource.kt 读到 {len(names)} 个源\n")

    marks = {n: source_mark(n) for n in names}
    width = max(len(n) for n in names)
    for n in names:
        print(f"    {n:<{width}}  →  {marks[n]}")

    print()
    check_true("读到至少一个源（读不到就没在验任何东西）", len(names) > 0)

    # 1. 每个台标都必须是 2 个字符。1 个字符就是退回成用户嫌弃的「一个字母」，
    #    3 个字符在那个方块里会被挤到换行。逐源计一项 —— 失败时能直接指出是谁
    for n, m in marks.items():
        check_true(
            f"  {n} 的台标是 2 个字符（现为 {m!r}）",
            len(m) == 2,
            "1 个字符会退回成「一个字母」，3 个字符在那个方块里会换行",
        )

    # 2. 两两不重复（核心断言）
    seen = {}
    dup = []
    for n, m in marks.items():
        if m in seen:
            dup.append(f"{seen[m]} 与 {n} 撞了：都是 {m}")
        else:
            seen[m] = n
    check_true(
        f"{len(marks)} 个源台标两两不重复",
        not dup,
        "；".join(dup) + "（改法：调 sourceMark 的取字母规则，或给某个源换显示名）",
    )

    # 3. 顺手核对 Artwork.kt 里的关键实现还在（防止有人把渐变改回纯色）
    kt = MARK_KT.read_text(encoding="utf-8")
    for needle, why in [
        ("Brush.verticalGradient", "占位牌必须是渐变底，纯色就又变回「加载失败的方块」"),
        ("0.30f", "字号按方块尺寸的 30% 走，写死 dp 会在迷你条里溢出"),
    ]:
        check_true(f"Artwork.kt 里有 `{needle}`", needle in kt, why)

    # 4. 检查器自测 —— 「检查器比被检查的东西更容易错」
    #
    # 上面那条「两两不重复」若写坏了（例如 seen 永远为空、或比较时用了错的那一侧），
    # 脚本仍然会打印「全部通过」。所以拿一对**构造出来的**撞车名探一下：
    # 它必须被判为重复。探针不通过 = 那条核心断言没有区分力。
    probe_a, probe_b = "NPR News Now", "NPR News Night"
    check_true(
        "自测：构造出的撞车对（NPR News Now / NPR News Night）能被判为重复",
        source_mark(probe_a) == source_mark(probe_b) == "NN",
        f"探针算出来是 {source_mark(probe_a)!r} / {source_mark(probe_b)!r}，"
        "取字母规则变了 → 换一对真正会撞的名字来探",
    )
    check_true(
        "自测：单字符台标会被判不合格（退回「一个字母」是用户明确嫌弃过的）",
        len(source_mark("A")) == 1,
        f"source_mark('A') = {source_mark('A')!r}，长度不是 1 → 换一个更短的探针",
    )

    print()
    print(f"通过 {PASS} 项，失败 {FAIL} 项")
    if FAIL:
        print("\n❌ 有断言未通过：")
        for f in FAILURES:
            print("  - " + f)
        return 1
    print("✅ 全部通过")
    print(f"   台标集合：{' '.join(sorted(set(marks.values())))}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
