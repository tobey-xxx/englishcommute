#!/usr/bin/env python3
"""核验**发布闸门本身**（`cycle.publish`）—— 用真的那份代码 + 临时目录跑坏样本。

    verify_gate.py [--selftest]

## 为什么需要一个**单独**的脚本

`verify_publish.py` 检的是「已经发出去的清单合不合法」——**消费方**。
这里检的是「不合法的东西到底会不会被发出去」——**生产方**。

两者是两件事，不能互相替代：**闸门写错、而这一批数据恰好合法**时，
前者一条都查不出来。而闸门写错的后果正是用户最在意的那一件事：
「字幕没准备好就不该出现在 App 里」。

## 为什么它能用真的代码跑

`publish()` 用到的 `WWW` / `WWW_AUDIO` / `PUBLIC_BASE` 都是模块级的名字，
所以把临时目录赋进去、把 `log()` 换成哑的，就能在**任何机器上**（不需要服务器、
不需要模型、不需要网络）验证它的判据。

## 覆盖的形态

| # | 形态 | 期望 |
| --- | --- | --- |
| A | 字幕 + 中文 + 音频都在 | 发布，hosted=true |
| B | **`srt` 字段整个缺失** | **不发布**（旧版会照发 —— 这是那个洞） |
| C | `srt` 字段是空串 | 不发布 |
| D | `srt` 有路径但文件不在 | 不发布 |
| E | 有英文字幕、中文还没生成（少量） | 那一条不发布 |
| F | 大量缺中文（超熔断阈值） | **照常发布** + 每条打 `zhPending` |
| G | 音频不在发布区 | hosted=false，且 `audioUrl` **保持原样**（不被改写成死链） |
"""

import json
import re
import shutil
import sys
import tempfile
from pathlib import Path

BIN = Path(__file__).resolve().parent
sys.path.insert(0, str(BIN))

import cycle  # noqa: E402

PASS = FAIL = 0


def check(name: str, got, want) -> None:
    global PASS, FAIL
    ok = got == want
    if ok:
        PASS += 1
    else:
        FAIL += 1
    print(f"  {'✅' if ok else '❌'} {name}" + ("" if ok else f"\n       得到 {got!r}\n       期望 {want!r}"))


def run_publish(state, *, audio_in_www=True):
    """把 publish 跑在一个临时目录里，返回它产出的 index。"""
    tmp = Path(tempfile.mkdtemp(prefix="ecgate_"))
    (tmp / "subs").mkdir(parents=True, exist_ok=True)
    audio = tmp / "audio"
    audio.mkdir(parents=True, exist_ok=True)
    for eid, e in state["episodes"].items():
        # ⚠️ 脚手架默认「写了路径就把文件造出来」—— 于是坏样本 D
        #    （「字段写了路径、文件其实不在」）会被脚手架**自己修好**，
        #    断言就永远抓不到它。第一版就是这样，实测报了一次假通过。
        #    `_no_file` 是这个开关：只写字段，不落盘。
        if not e.pop("_no_file", False):
            srt = e.get("srt") or ""
            if srt:
                (tmp / srt).write_text(
                    "1\n00:00:01,000 --> 00:00:02,000\nHello there.\n", encoding="utf-8")
            zh = e.get("zh") or ""
            if zh:
                (tmp / zh).write_text('["你好。"]', encoding="utf-8")
        if audio_in_www and e.pop("_audio", True):
            (audio / f"{eid}.mp3").write_bytes(b"\xff\xfb\x90\x00" * 60)

    # 闸门读的是模块级的这三个名字 —— 换掉它们就等于换掉了工作目录
    cycle.WWW = tmp
    cycle.WWW_AUDIO = audio
    cycle.PUBLIC_BASE = "https://example.test"
    cycle.log = lambda *a, **k: None          # 别往真日志里写

    cycle.publish(state, [])
    out = json.loads((tmp / "index.json").read_text(encoding="utf-8"))
    shutil.rmtree(tmp, ignore_errors=True)
    return out


def ep(eid, *, srt=True, zh=True, audio=True, no_file=False,
       published="2026-09-22T10:00:00+0800"):
    """造一条 state 里的账。

    · `srt=False` 表示**连字段都没有**（坏样本 B）
    · `srt="subs/x.srt"` 表示字段写了这个路径
    · `no_file=True` 表示**字段写了、文件不落盘**（坏样本 D）——
      少了这个开关，脚手架会把文件造出来，D 就永远抓不到
    """
    d = {
        "status": "ok",
        "guid": f"g-{eid}",
        "title": f"测试 {eid}",
        "publishedAt": published,
        "audioUrl": f"https://cdn.example.test/{eid}.mp3",
        "_audio": audio,
        "_no_file": no_file,
    }
    if srt is True:
        d["srt"] = f"subs/{eid}.srt"
    elif isinstance(srt, str):
        d["srt"] = srt
    if zh is True:
        d["zh"] = f"subs/{eid}.zh.json"
    return d


def ids(index):
    return sorted(e["id"] for e in index["episodes"])


def main():
    print("发布闸门自检 —— 每条坏样本都必须被拦下，每个好样本都必须放行\n")

    # ── A / B / C / D：字幕这三态 ────────────────────────────────────────
    index = run_publish({"episodes": {"a-good": ep("a-good")}})
    check("A 好样本（字幕+中文+音频都在）→ 发布", ids(index), ["a-good"])
    check("  · hosted=true 且 audioUrl 指向自己",
          index["episodes"][0]["hosted"] is True
          and "a-good.mp3" in index["episodes"][0]["audioUrl"], True)
    check("  · hostedCount 与 audioServed 都报出来",
          (index["hostedCount"], index["audioServed"]), (1, True))

    index = run_publish({"episodes": {"b-nosrt": ep("b-nosrt", srt=False)}})
    check("B ★ srt 字段**整个缺失** → 不发布（旧版会照发，这就是那个洞）",
          ids(index), [])
    check("  · count 字段随之归零（不能留旧数字）", index["count"], 0)

    index = run_publish({"episodes": {"c-empty": ep("c-empty", srt="")}})
    check("C srt 字段是空串 → 不发布", ids(index), [])

    index = run_publish({"episodes": {
        "d-ghost": ep("d-ghost", srt="subs/ghost.srt", no_file=True)}})
    check("D srt 有路径但文件不在 → 不发布", ids(index), [])

    # ── E：少量缺中文 → 拦那一条 ─────────────────────────────────────────
    six = {f"e{i}": ep(f"e{i}") for i in range(5)}
    six["e-missing-zh"] = ep("e-missing-zh", zh=False)
    index = run_publish({"episodes": six})
    check("E 少量缺中文 → 只拦那一条，其余照发",
          ids(index), sorted([f"e{i}" for i in range(5)]))
    check("  · 它没有 zh 字段，也**没有** zhPending（还没到熔断）",
          "zhPending" not in index["episodes"][0], True)

    # ── F：大量缺中文 → 熔断放行，但不能交空清单 ─────────────────────────
    ten = {}
    for i in range(10):
        ten[f"f{i}"] = ep(f"f{i}", zh=(i >= 8))     # 只有 2 条有中文
    index = run_publish({"episodes": ten})
    check("F ★ 缺中文比例过高 → **熔断放行**（不能交一份空清单）",
          len(index["episodes"]), 10)
    pending = [e for e in index["episodes"] if e.get("zhPending")]
    check("  · 缺中文的那 8 条带 zhPending 标记", len(pending), 8)
    check("  · 有中文的那 2 条**不**带这个标记",
          sum(1 for e in index["episodes"] if e.get("zh")), 2)

    # ── G：音频不在 → 退回原始 CDN，不许改写成死链 ───────────────────────
    index = run_publish({"episodes": {"g1": ep("g1", audio=False)}})
    e0 = index["episodes"][0]
    check("G 音频不在发布区 → hosted=false", e0["hosted"], False)
    check("  · 且 audioUrl **保持原始的 CDN 地址**（写成托管地址就是 404）",
          e0["audioUrl"], "https://cdn.example.test/g1.mp3")

    print()
    print("=" * 66)
    print(f"通过 {PASS} 项，失败 {FAIL} 项")
    return 0 if FAIL == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
