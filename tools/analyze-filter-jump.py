#!/usr/bin/env python3
"""
解析 EnglishCommute 的 diag.log，把「切筛选」那一对日志抽出来比对。

用法：
    python3 analyze-filter-jump.py /path/to/diag.log

它回答的唯一问题：**切档前后，筛选签那一栏在屏幕上的位置变了吗？**

日志是成对的（都在 I Act 通道）：

    切筛选 → 收藏 · 从 筛选签吸在顶                      ← 切走那一刻，筛选签在哪儿
    筛选落点 · 收藏 · 顶部 3/12 条 · 筛选签吸在顶 · 筛选签 吸在顶(滚过 30px)
                                          └ 结论词          └ 实测（直接量的像素）

**两行的结论词一致 = 那一栏没动。** 不一致 = 就是用户报的「往下沉一下」。

=============================================================================
⚠️ 三种状态，不是两种（2.1.6 把这件事定死）
=============================================================================

    筛选签在刊头下          刊头可见，筛选签跟着列表走
    筛选签贴顶位(未吸顶)     在屏幕顶上，但比吸顶位**低一整个 Space.section（30dp）**
    筛选签吸在顶            吸在屏幕最上面

前两轮把后两者都叫「筛选签贴顶」，于是**两个差 30dp 的状态共用一个名字** ——
日志上看起来「两行一致、没跳」，而屏幕上确实沉了一档。这就是 2.1.4 / 2.1.5
两轮「改了但没修好」的原因。

所以这个脚本除了比对两行的结论词，还要**交叉验证结论词与实测值**：
`筛选签吸在顶` 必须配 `吸在顶(...)`，`筛选签贴顶位(未吸顶)` 必须配 `贴顶位(未吸顶)`。
只要有一个对不上，就是日志在自欺 —— 立刻报出来，不要再往下猜。
"""

import sys
from pathlib import Path

# 22:09:31.442 +0.312s [main] I Act  切筛选 → 收藏 · 从 筛选签吸在顶
LINE_MARK = " I Act "

# ⚠️ 别用「一个正则吃下整行」：2.1.4 给落点行加了一个 `筛选签 <实测>` 字段，
#    而当时那个 `label = (.+)$` 会把整个尾巴当成结论词 —— 于是两行永远「不一致」，
#    脚本**误报「跳了」**。按「 · 」切开，字段位置就固定了。
SEP = " · "


def family(s: str) -> str:
    """把任一种说法归到三个族里。旧版日志的说法也认，但单列一档（见下）。"""
    t = s.strip()
    for prefix in ("从 ",):
        if t.startswith(prefix):
            t = t[len(prefix):]
    if t.startswith("筛选签"):
        t = t[len("筛选签"):]
    for prefix, fam in (
        ("吸在顶", "pinned"),
        ("吸附在顶", "pinned"),      # 2.1.4 的措辞：已滚出屏幕、正吸附 → 真吸顶
        ("贴顶位", "origin"),        # 2.1.6 的措辞：在内容区起点上，低一档
        ("正好贴顶", "origin"),      # 2.1.4 的措辞：offset==0 —— 其实也是低一档
        ("在刊头下", "below"),
        # ⚠️ 2.1.4 之前的旧说法。它只有一个词，而那个词**同时指两种状态**
        #    （下标 >= 2 就算）—— 所以**旧日志回答不了这个问题**，单列一档。
        ("贴顶", "legacy"),
    ):
        if t.startswith(prefix):
            return fam
    return "?"


def parse_switch(msg: str):
    """`切筛选 → 收藏 · 从 筛选签吸在顶`"""
    if not msg.startswith("切筛选 → "):
        return None
    parts = msg.split(SEP)
    if len(parts) < 2:
        return None
    return {"to": parts[0][len("切筛选 → "):], "label": parts[1]}


def parse_land(msg: str):
    """`筛选落点 · 收藏 · 顶部 3/5 条 · 筛选签吸在顶 [· 筛选签 吸在顶(滚过 30px)]`"""
    if not msg.startswith("筛选落点"):
        return None
    parts = msg.split(SEP)
    if len(parts) < 4:
        return None
    top, total = "?", "?"
    m = parts[2]
    if "/" in m:
        head = m.replace("顶部", "").strip()
        num = head.split("条")[0].strip()
        if "/" in num:
            top, total = num.split("/", 1)
    return {
        "of": parts[1],
        "top": top,
        "total": total,
        "label": parts[3],
        "measured": parts[4][len("筛选签 "):] if len(parts) > 4 else None,
    }


def main(path: str) -> int:
    text = Path(path).read_text(errors="replace")
    events = []
    for raw in text.splitlines():
        if LINE_MARK not in raw:
            continue
        ts = raw.split(" ", 1)[0]
        msg = raw.split(LINE_MARK, 1)[1].strip()
        s = parse_switch(msg)
        if s:
            events.append(("switch", ts, s, msg))
            continue
        l = parse_land(msg)
        if l:
            events.append(("land", ts, l, msg))

    switches = [e for e in events if e[0] == "switch"]
    lands = [e for e in events if e[0] == "land"]

    print(f"读到 diag.log：{path}")
    print(f"  「切筛选」{len(switches)} 条 · 「筛选落点」{len(lands)} 条")
    if not switches:
        print("\n⚠️ 没有「切筛选」记录 —— 要么这份日志里没切过档，要么被滚动覆盖了"
              "（从文件末尾往前找最新的那几条）。")
        return 0

    print()
    print("=" * 78)
    print("逐次切换：两行的结论词是否一致 + 结论词与实测值是否自洽")
    print("=" * 78)

    bad, legacy, unknown = 0, 0, 0
    used = set()
    for _, ts, g, _ in switches:
        land = None
        for j, e in enumerate(lands):
            if j in used:
                continue
            if e[1] >= ts and e[2]["of"] == g["to"]:
                land = e
                used.add(j)
                break
        print(f"\n{ts}  切到「{g['to']}」")
        print(f"    切时：{g['label']}")
        if land is None:
            print("    落点：**没有对应记录**（可能没切成功，或被日志滚动挤掉了）")
            continue

        lg = land[2]
        f_switch, f_land = family(g["label"]), family(lg["label"])
        same = f_switch == f_land
        # 实测值 vs 结论词 —— 只在新版日志里有实测值
        f_measured = family(lg["measured"]) if lg["measured"] else None
        self_consistent = f_measured is None or f_measured == f_land

        if "legacy" in (f_switch, f_land):
            legacy += 1
            verdict = "⚠️ 无法判定 —— 这两行来自旧版（那时只有一个「贴顶」，指两种状态）"
        elif "?" in (f_switch, f_land):
            unknown += 1
            verdict = "⚠️ 说法不认识（日志格式变过？）"
        elif not same:
            bad += 1
            verdict = "❌ 动了 ← 这就是「跳」"
        elif not self_consistent:
            bad += 1
            verdict = ("❌ 结论词与实测值**自相矛盾** —— 日志在自欺，"
                       "不要照着它改代码")
        else:
            verdict = "✅ 没动"
        print(f"    {verdict}")
        print(f"    落点：{lg['label']}"
              + (f"   （实测：{lg['measured']}）" if lg["measured"] else "   （旧版日志无实测值）"))
        print(f"    落点位置：顶部第 {lg['top']}/{lg['total']} 条")

    print()
    print("=" * 78)
    known = len(switches) - legacy
    if bad == 0:
        if legacy:
            print(f"结论：可判定的 {known} 次切换里，筛选签位置都一致 —— **没有跳**。")
            print(f"      另有 {legacy} 次来自旧版日志，那一版只有一个「贴顶」，"
                  f"**回答不了这个问题**（升级后再导一份）。")
        else:
            print("结论：所有切换的筛选签位置都一致 —— **没有跳**。")
            print("      如果真机上仍然看着跳，那就不是「落点」的问题了："
                  "去量帧，别再改落点。")
    else:
        print(f"结论：{bad} 次切换里筛选签位置变了（或日志自相矛盾）—— 复现了。")
        print("      优先看这两类：")
        print("        · 切时「吸在顶」而落点「在刊头下」→ 落点被钳回了顶部")
        print("        · 结论词「吸在顶」而实测写「贴顶位(未吸顶)」→ 判据在自欺")
    if unknown:
        print(f"      ⚠️ 另有 {unknown} 次的说法不认识 —— 日志格式可能又变了，"
              f"先把这个脚本的 family() 对齐。")
    return 0


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(2)
    sys.exit(main(sys.argv[1]))
