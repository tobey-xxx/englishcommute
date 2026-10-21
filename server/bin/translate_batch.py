#!/usr/bin/env python3
"""把一份英文字幕（SRT）整体翻成中文，产出 App 直接可用的译文数组。

    translate_batch.py IN.srt OUT.zh.json [--set fp32] [--threads 2]

## 产出格式：与 App 的 `zh/<id>.zh.json` **完全一致**

一个 JSON 数组，**长度严格等于 SRT 句段数、下标一一对应**。这一点是硬约束：
App 侧有一道校验 —— `cached.size == parsed.size` 才采用，否则整份丢弃。
（因为「错位的译文」比「没有译文」更糟：它会张冠李戴。）

数组元素的三种取值，沿用 App 已有的语义：

| 值 | 含义 | UI 表现 |
|---|---|---|
| 中文串 | 这行的译文 | 正常显示 |
| `""` | **这行不需要译文** | 不占位（不留空行） |
| `null` | 翻过了但失败 | 显示一条占位下划线，将来可补 |

## 为什么必须按「句子」翻，而不是按字幕行翻（实测）

ASR 的句段是**按静音切的**，经常把一句话切成两半：

    2  The war with Iran is expected to be the main focus this week when President Trump addresses
    3  the UN General Assembly.

半句没有完整语义，照字面翻会得出「…特朗普总统**住址**」这种结果。
按句合并后一次翻完，译文是正确的完整句。

## 跨行的句子，中文挂在**哪一行**（有数据支撑的决定）

统计 13 份真实字幕、2996 个句段：**65.3% 的句子只占 1 行**，
23.0% 占 2 行，10.6% 占 3 行，共 34.7% 跨行。

两种做法：
- **每行都挂中文** → 那 34.7% 会在屏幕上把同一段中文重复 2–3 遍。译文是整段渲染的
  （不限行数），重复看起来就是明显的排版事故。
- **只挂在句子的第一行**（本脚本采用）→ 读起来是「英文前半 / 中文整句 / 英文后半」，
  语义正确且干净。续行留 `""`，不占位。

代价：听到句子后半时，中文在上一行（仍在屏幕内，只是不在当前高亮行上）。
考虑到那 34.7% 里有一半是「短尾巴」（如 `the UN General Assembly.`），
视线大部分时间落在首行，这个代价可以接受。

## 合并规则

1. 句段末尾是 `.!?…`（允许后跟右引号/右括号）→ 句子到此为止
2. 缩写不算句末（`Mr.` / `U.S.` / `3.5`）
3. ⚠️ 兜底：到上限（240 字符 / 4 行）还没等到句末时，**退到最近的「以逗号等结尾」的句段处断开**
   —— 那是从句边界。直接在半个从句中间切，等于在兜底路径上又制造一个「半句没语义」的坑，
   而那正是这个脚本要消灭的东西。
"""

import argparse
import json
import os
import re
import resource
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from translate_server import Translator  # noqa: E402

# ★ 解析与句子切分已搬到 `srt_split.py`（2.1.1）。
#
# 原来它们住在这里，而这个文件顶部 import 了 `translate_server` → numpy /
# onnxruntime / CTranslate2。于是「把几行字幕合并成一句」这个纯字符串操作
# 需要 1 GB 的推理依赖才能跑 —— 本机想单测它就报 `No module named 'numpy'`。
# 现在两个使用者（本文件与 `resegment.py`）都只 import 那个模块，规则只有一份。
from srt_split import merge_sentences, parse_srt  # noqa: E402

def log(msg):
    print(msg, flush=True)


# ---------------------------------------------------------------- 主流程
def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("srt", help="输入 SRT")
    ap.add_argument("zh", nargs="?", help="输出 zh.json；省略则只做合并统计")
    ap.add_argument("--set", default="fp32", help="fp32（默认，又快又准）| int8（内存兜底）")
    ap.add_argument("--threads", type=int, default=2)
    ap.add_argument("--models-root", default=None)
    ap.add_argument("--show", type=int, default=0, help="打印前 N 句对照，便于人眼复核")
    ap.add_argument("--audit", default=None, help="另存一句一行的 TSV（用于归档复核）")
    ap.add_argument("--beams", type=int, default=4,
                    help="束数。模型原设计是 4（贪心会静默丢内容）；1 = 贪心")
    ap.add_argument("--force", action="store_true", help="忽略已有产出，整份重翻")
    ap.add_argument("--timeout-guard", type=float, default=0.0,
                    help="超过这么多秒就停止（0 = 不限制）。防一轮跑太久")
    args = ap.parse_args()

    t_all = time.monotonic()
    cues = parse_srt(args.srt)
    if not cues:
        log(f"❌ 没解析出任何句段：{args.srt}")
        return 1
    sents = merge_sentences(cues)
    merged = sum(1 for a, b, _ in sents if b > a)
    log(f"句段 {len(cues)} → 句子 {len(sents)}（跨行 {merged} 句，"
        f"{merged * 100 / len(sents):.1f}%）")

    if not args.zh:
        for a, b, txt in sents[:args.show or 10]:
            log(f"  [{a + 1}-{b + 1}] {txt[:100]}")
        return 0

    out_path = Path(args.zh)

    # ── 断点续做 ─────────────────────────────────────────────────────
    # 上一轮被 systemd 的超时杀掉、或触发了 --timeout-guard 时，
    # 没翻完的位置在数组里是 null。重算一遍句子划分是**确定性**的，
    # 所以只要「句数没变」，就能按句首位置精确跳过已翻好的部分。
    # 句数变了（字幕被重新转写过）→ 整份作废重翻：错位的译文比没有更糟。
    prev, kept = None, 0
    if out_path.exists() and not args.force:
        try:
            prev = json.loads(out_path.read_text(encoding="utf-8"))
        except Exception:
            prev = None
        if prev is not None and len(prev) != len(cues):
            log(f"  已有译文句数 {len(prev)} ≠ 本次 {len(cues)}，整份重翻")
            prev = None

    # 没字母的行（"♪"、"1."、"——"）不用翻。与 App 的 needsTranslation 一致：
    # text.any { it.isLetter() }
    plan = [(a, b, txt) for a, b, txt in sents if any(ch.isalpha() for ch in txt)]
    log(f"需要翻译 {len(plan)} 句（跳过纯符号 {len(sents) - len(plan)} 句）")

    arr = [""] * len(cues)
    audit = []
    todo = []
    for a, b, txt in plan:
        if prev is not None and prev[a]:
            arr[a] = prev[a]
            kept += 1
            audit.append((a + 1, b + 1, txt, prev[a]))
        else:
            todo.append((a, b, txt))
    if kept:
        log(f"  沿用上一轮已翻好的 {kept} 句，本轮待翻 {len(todo)} 句")
    if not todo:
        log("✅ 全部已有译文，无需重跑")
        return 0

    t = Translator(args.set, args.threads, models_root=args.models_root, beams=args.beams)
    log(f"模型 {t.set_name} | 优化 {t.opt_used.upper()} | {t.beams} 束 | "
        f"加载 {t.load_sec:.1f}s | vocab={t.vocab_size} KV={len(t.past_names)}")

    fails, slowest, done_now = 0, (0.0, ""), 0
    for k, (a, b, txt) in enumerate(todo, 1):
        t0 = time.monotonic()
        try:
            zh, _rounds = t.translate(txt)
        except Exception as e:
            # 单句失败不该毁掉整份。置 null：App 会显示占位下划线，下次能补
            arr[a] = None
            fails += 1
            log(f"    ⚠️ 第 {k} 句失败：{type(e).__name__}: {e}")
            continue
        dt = time.monotonic() - t0
        if dt > slowest[0]:
            slowest = (dt, txt[:60])
        # ★ 只挂在句子首行；续行保持 ""（见模块注释里的实测数据）
        arr[a] = zh
        done_now += 1
        audit.append((a + 1, b + 1, txt, zh))
        if args.timeout_guard and time.monotonic() - t_all > args.timeout_guard:
            log(f"    ⏹ 触发时长上限（{args.timeout_guard:.0f}s），余下 "
                f"{len(todo) - k} 句留到下一轮")
            for aa, bb, tt in todo[k:]:
                arr[aa] = None
            break

    # 原子写：被杀时不会留半截 JSON。App 那边读失败会整份丢弃，所以半截格外致命
    out_path.parent.mkdir(parents=True, exist_ok=True)
    tmp = str(out_path) + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(arr, f, ensure_ascii=False, separators=(",", ":"))
    os.replace(tmp, out_path)

    if args.audit and audit:
        audit.sort(key=lambda r: r[0])
        Path(args.audit).write_text(
            "cue_start\tcue_end\tEN\tZH\n" +
            "\n".join(f"{a}\t{b}\t{txt}\t{zh}" for a, b, txt, zh in audit),
            encoding="utf-8")

    filled = sum(1 for x in arr if x)
    dt_all = time.monotonic() - t_all
    # ⚠️ ru_maxrss 的单位**随平台变**：Linux 是 KB，macOS 是字节。
    #    不区分就会得到「583168 MB」这种离谱数字（本机实测踩到过）。
    rss = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
    peak = rss / 1024 if sys.platform.startswith("linux") else rss / 1048576
    ms = dt_all * 1000 / max(1, done_now)
    log(f"✅ 新翻 {done_now} 句（沿用 {kept}），失败 {fails}，"
        f"用时 {dt_all:.0f}s（{ms:.0f} ms/句）")
    if slowest[0]:
        log(f"   最慢一句 {slowest[0] * 1000:.0f} ms：{slowest[1]}")
    log(f"   峰值内存 {peak:.1f} MB | 有译文 {filled}/{len(plan)} 行首 | "
        f"输出 {out_path.name}（{out_path.stat().st_size / 1024:.0f} KB，"
        f"{len(arr)} 个元素）")
    log(f"PEAK_MB={peak:.1f}")     # 供 cycle.py 抓取，格式固定

    if args.show:
        log("")
        for a, b, txt, zh in audit[:args.show]:
            log(f"  [{a}-{b}] EN {txt}")
            log(f"        ZH {zh}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
