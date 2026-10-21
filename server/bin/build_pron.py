#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
生词本 · 标准读音批量生成（P0.4）

音色：en-US-GuyNeural（美式男声 · 用户 2026-09-23 选定）

## 三条设计约束

1. **断点续传**：已存在的 mp3 直接跳过。edge-tts 是微软的在线服务，
   批量跑到一半断了、或者被限流，重跑不能从头来。
2. **要生成的词 = 语料词形 ∪ 它们的原形**。
   用户加词时点到的是词形（signed），但生词本存的是原形（sign），
   复习时播原形的音频 —— 两边都得有。
3. **输出到 /opt/ec/www/pron/`<word>`.mp3**，由 nginx 直接对外，
   客户端加词时按需下载（约 11 KB/词）。

用法：
    python3 build_pron.py --limit 20              # 小批量试跑
    python3 build_pron.py --delay 0.15            # 全量（约 2.5–3 小时）
    python3 build_pron.py --report                # 只统计，不生成
"""

import argparse
import asyncio
import csv
import glob
import os
import time

VOICE = "en-US-GuyNeural"
OUT_DIR = "/opt/ec/www/pron"
ECDICT = "/tmp/ecdict.csv"
SUBS = "/opt/ec/www/subs/*.srt"


def corpus_words():
    """语料里出现过的所有词形（只留字母，做文件名安全）"""
    words = set()
    for f in glob.glob(SUBS):
        t = open(f, encoding="utf-8").read()
        for b in t.strip().split("\n\n"):
            lines = b.strip().split("\n")
            if len(lines) >= 3:
                for w in " ".join(lines[2:]).split():
                    x = "".join(c for c in w if c.isalpha()).lower()
                    if len(x) > 1:
                        words.add(x)
    return words


def with_lemmas(words):
    """用 ECDICT 的 exchange 字段补上原形（`0:sign` 就是 signed 的原形）"""
    csv.field_size_limit(10 ** 9)
    need = set(words)
    n = 0
    for row in csv.DictReader(open(ECDICT, encoding="utf-8")):
        w = (row.get("word") or "").strip().lower()
        if w not in words:
            continue
        for part in (row.get("exchange") or "").split("/"):
            if part.startswith("0:"):
                lem = "".join(c for c in part[2:] if c.isalpha()).lower()
                if len(lem) > 1 and lem not in need:
                    need.add(lem)
                    n += 1
    return need, n


async def gen_one(word, out, retries=3):
    import edge_tts
    for i in range(retries):
        try:
            c = edge_tts.Communicate(word, VOICE)
            await c.save(out)
            if os.path.getsize(out) > 500:
                return True
            os.remove(out)
        except Exception as e:
            if i == retries - 1:
                print("  x %-20s %s" % (word, type(e).__name__), flush=True)
        await asyncio.sleep(1.0 + i * 1.5)
    return False


async def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--limit", type=int, default=0, help="只生成前 N 个（试跑用）")
    ap.add_argument("--delay", type=float, default=0.15, help="每词之间的间隔秒数")
    ap.add_argument("--report", action="store_true", help="只统计不生成")
    args = ap.parse_args()

    os.makedirs(OUT_DIR, exist_ok=True)

    forms = corpus_words()
    need, added = with_lemmas(forms)
    print("语料词形 %d 个 · 补上原形 %d 个 · 合计 %d 个词条" % (len(forms), added, len(need)), flush=True)

    todo = sorted(w for w in need if not os.path.exists(os.path.join(OUT_DIR, w + ".mp3")))
    print("已生成 %d · 待生成 %d" % (len(need) - len(todo), len(todo)), flush=True)

    done_bytes = sum(os.path.getsize(os.path.join(OUT_DIR, f))
                     for f in os.listdir(OUT_DIR)) if os.path.isdir(OUT_DIR) else 0
    print("已占用磁盘 %.1f MB" % (done_bytes / 1048576), flush=True)

    if args.report:
        return
    if args.limit:
        todo = todo[:args.limit]

    ok = fail = 0
    t0 = time.time()
    for i, w in enumerate(todo, 1):
        out = os.path.join(OUT_DIR, w + ".mp3")
        if await gen_one(w, out):
            ok += 1
        else:
            fail += 1
        if i % 100 == 0 or i == len(todo):
            el = time.time() - t0
            rate = i / max(0.1, el)
            eta = (len(todo) - i) / max(0.01, rate) / 60
            print("[%d/%d] ok=%d fail=%d · %.2f 词/秒 · 剩余约 %.0f 分钟"
                  % (i, len(todo), ok, fail, rate, eta), flush=True)
        await asyncio.sleep(args.delay)

    total = sum(os.path.getsize(os.path.join(OUT_DIR, f)) for f in os.listdir(OUT_DIR))
    print("完成：ok=%d fail=%d · 目录共 %d 个文件 / %.1f MB"
          % (ok, fail, len(os.listdir(OUT_DIR)), total / 1048576), flush=True)


asyncio.run(main())
