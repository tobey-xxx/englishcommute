#!/usr/bin/env python3
"""把字幕的切分粒度对齐到翻译的粒度：**一句一条字幕**。

## 为什么需要它（用户在真机上指出的）

字幕和译文是**两套粒度**：

| | 粒度 |
|---|---|
| SRT | 原始识别结果，每 2–4 秒一条（一集约 300 条） |
| 译文 | 合并成整句之后翻（一集约 120 句） |

写回时译文只挂在**句首那一行**（`arr[a] = zh`，续行留 `""`）——
这是 App 侧的硬契约：同一句译文只该显示一次，挂在三行上会像翻译错了。

于是**跨行的句子只有第一行有中文**，后面几行光秃秃地只有英文。
用户的原话是：「有些段落只有英文没有中文，既然如此，
为什么不把没有中文的那句话跟上一句拼在一起呢？」

## 做法

把 cue 划分**改成与句子划分一致**：一句一条，
时间轴 = 首 cue 的 start → 末 cue 的 end。于是每条字幕有且只有一份译文，
中英覆盖面完全一致。

## 为什么放在服务端，而不是让客户端去拼

客户端拼的话，渲染时得知道「哪几行属于同一句」——
那等于把服务端的切分规则复制一份到客户端，两边一定会漂移。
放在这里做，客户端拿到的就是一份普通的、一一对应的 srt + zh，**一行都不用改**。

## ⚠️ 它**不幂等**，同一个文件只能跑一次

合并过的 cue 里有一部分**不以句末标点结尾**（`max_chars` 兜底断开的那种）。
再跑一次，它们会被和下一句并起来 —— 结果就变了。
所以「要不要跑」这件事**必须由外部标记决定**，不能靠内容判断。
调用方（`cycle.py`）用 state 里的 `rs` 字段控制，见那边的说明。

## 已有的译文怎么办

旧 `zh.json` 是按**原始 cue 下标**排的，重切分后下标体系变了。
所以这里会按新划分重排一遍：新数组第 i 个元素 = 旧数组第 `sents[i][0]` 个元素。
**不需要重跑模型** —— 句子划分是确定性的，旧译文一个都没浪费。
"""

import argparse
import json
import os
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
# ⚠️ 从 `srt_split` 拿，**不要**从 `translate_batch` —— 后者会拖进
#    numpy / onnxruntime / CTranslate2（1 GB 的推理栈），
#    而这里做的只是字符串切分。见 srt_split.py 的说明。
from srt_split import merge_sentences, parse_srt  # noqa: E402


def log(msg):
    print(msg, flush=True)


def fmt_ts(ms: int) -> str:
    h, rem = divmod(max(0, ms), 3600_000)
    m, rem = divmod(rem, 60_000)
    s, msec = divmod(rem, 1000)
    return f"{h:02d}:{m:02d}:{s:02d},{msec:03d}"


def build(cues, sents):
    """按句子划分重建 SRT 文本。返回 (文本, 每条的字数)"""
    blocks = []
    for i, (a, b, text) in enumerate(sents, 1):
        start = cues[a][0]
        end = cues[b][1] if b < len(cues) else cues[a][1]
        # ⚠️ end 必须 >= start：识别结果里偶有「末条 cue 的 end 早于首条 start」
        #    这种越界（VAD 边界与识别切分不共享时钟），那样写出来的时间码
        #    会被播放器当成非法条目整条丢掉 —— 而丢的是**整句**，不是几毫秒。
        if end < start:
            end = start
        blocks.append(f"{i}\n{fmt_ts(start)} --> {fmt_ts(end)}\n{text}\n")
    return "\n".join(blocks)


def write_atomic(path: Path, text: str):
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = str(path) + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        f.write(text)
    os.replace(tmp, path)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("srt", help="要重切分的 SRT（原地替换）")
    ap.add_argument("zh", nargs="?", help="同期的 zh.json；给了就一起重排")
    ap.add_argument("--dry-run", action="store_true", help="只统计，不写盘")
    args = ap.parse_args()

    srt_path = Path(args.srt)
    cues = parse_srt(srt_path)
    if not cues:
        log(f"❌ 没解析出任何句段：{srt_path}")
        return 1

    sents = merge_sentences(cues)
    if len(sents) >= len(cues):
        # 已经是一句一条（或本来就一句一条）。再合并只会更粗。
        log(f"ℹ {srt_path.name}：{len(cues)} 段 → {len(sents)} 句，无需重切分")
        return 0

    text = build(cues, sents)
    gains = len(cues) - len(sents)
    log(f"   重切分 {srt_path.name}：{len(cues)} 段 → {len(sents)} 句"
        f"（合并掉 {gains} 条无译文的续行）")

    # 译文：按新划分重排
    zh_path = Path(args.zh) if args.zh else None
    if zh_path and zh_path.exists():
        try:
            old = json.loads(zh_path.read_text(encoding="utf-8"))
        except Exception as e:
            log(f"   ⚠️ 旧译文读不出来（{type(e).__name__}），跳过重排：{e}")
            old = None
        if old is not None:
            if len(old) == len(cues):
                new = [old[a] if a < len(old) else None for a, _, _ in sents]
                filled = sum(1 for x in new if x)
                log(f"   译文 {len(old)} → {len(new)} 条（有译文的 {filled} 条）")
                if not args.dry_run:
                    write_atomic(zh_path, json.dumps(new, ensure_ascii=False,
                                                    separators=(",", ":")))
            elif len(old) == len(sents):
                log(f"   译文已经是新划分（{len(old)} 条），不动")
            else:
                # 长度两不靠：说明它既不是旧划分也不是新划分（上一轮跑到一半？）。
                # **不猜**，整个重来一遍最安全 —— 错位的译文比没有译文更糟。
                log(f"   ⚠️ 旧译文 {len(old)} 条，既不是 {len(cues)} 也不是 {len(sents)}"
                    f" —— 整份作废，等下一轮重翻")
                if not args.dry_run:
                    zh_path.unlink(missing_ok=True)

    if not args.dry_run:
        write_atomic(srt_path, text)
    return 0


if __name__ == "__main__":
    sys.exit(main())
