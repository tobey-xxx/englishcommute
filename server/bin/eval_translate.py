#!/usr/bin/env python3
"""译文质量对照：同一批句子，不同解码方式 / 不同模型集，并排看。

    eval_translate.py IN.srt --n 20 --sets fp32 --beams 1,4

为什么要有这个脚本：译文好坏**没法用断言测**（没有标准答案），
只能用「同批句子并排看」的方式人工判断。所以判据要可复现、可归档，
而不是靠临时敲一条命令看一眼。

它复用 translate_batch 的**同一套合并逻辑** —— 抽样的是合并后的「句子」，
不是 SRT 行。这一点很关键：按行抽样的评估会得出与线上不一致的结论。
"""

import argparse
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
# ⚠️ 2.1.1：从 `srt_split` 拿。原先经 `translate_batch` 转手，
#    而那个模块会 import 整个推理栈（numpy / onnxruntime / CTranslate2）——
#    只是解析一份 SRT 而已，不该拖上它们。
from srt_split import merge_sentences, parse_srt   # noqa: E402
from translate_server import Translator                  # noqa: E402


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("srt")
    ap.add_argument("--n", type=int, default=20)
    ap.add_argument("--sets", default="fp32", help="逗号分隔，如 fp32,int8")
    ap.add_argument("--beams", default="1,4", help="逗号分隔，如 1,4")
    ap.add_argument("--threads", type=int, default=2)
    ap.add_argument("--models-root", default=None)
    args = ap.parse_args()

    cues = parse_srt(args.srt)
    sents = [t for _a, _b, t in merge_sentences(cues) if any(c.isalpha() for c in t)]
    step = max(1, len(sents) // args.n)
    items = sents[::step][:args.n]
    print(f"源：{Path(args.srt).name}")
    print(f"句段 {len(cues)} → 合并后 {len(sents)} 句，等距抽 {len(items)} 句\n")

    combos = [(s.strip(), int(b)) for s in args.sets.split(",") for b in args.beams.split(",")]
    results = {}
    for set_name, beams in combos:
        t = Translator(set_name, args.threads, None, args.models_root, beams)
        key = f"{set_name}/beam{beams}"
        out, tot, rounds = [], 0.0, 0
        for s in items:
            t0 = time.monotonic()
            zh, n = t.translate(s)
            tot += time.monotonic() - t0
            rounds += n
            out.append(zh)
        results[key] = out
        print(f"[{key}] 平均 {tot / len(items) * 1000:.0f} ms/句，平均 {rounds / len(items):.1f} 轮")

    print("\n" + "=" * 78)
    for i, s in enumerate(items):
        print(f"\n[{i + 1}] EN {s}")
        for key, out in results.items():
            print(f"    {key:<14} {out[i]}")

    print("\n" + "=" * 78)
    print("差异统计（相对第一组）")
    base = list(results)[0]
    for key in list(results)[1:]:
        diff = sum(1 for a, b in zip(results[base], results[key]) if a != b)
        same_len = sum(1 for a, b in zip(results[base], results[key])
                       if len(b) > len(a) + 3)
        print(f"  {base} → {key}: {diff}/{len(items)} 句不同；"
              f"其中 {same_len} 句后者明显更长（更可能是补全而非截断）")


if __name__ == "__main__":
    main()
