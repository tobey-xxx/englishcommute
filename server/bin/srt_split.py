#!/usr/bin/env python3
"""SRT 解析 + 「把句段合并成整句」——**纯文本，零第三方依赖**。

## 为什么单独一个模块

先用它的有两个人：

- `translate_batch.py` —— 按合并后的整句去翻（一集 300 段 → 120 句）
- `resegment.py` —— 把字幕的 cue 划分改成与句子划分一致（2.1.1）

原来这两个函数住在 `translate_batch.py` 里。而那个文件顶部 import 了
`translate_server` → `numpy` / `onnxruntime` / `CTranslate2`。于是：

    「把几行字幕合并成一句」这个纯字符串操作，需要 1 GB 的推理依赖才能跑。

后果测试先撞上：本机想单测切分逻辑，报的是 `No module named 'numpy'` ——
一个与它毫不相干的错误。往后每次改动这份规则，都得先架起模型环境。

搬出来之后两个文件都只 import 它，规则**只有一份**（两处各写一份必然漂移，
而漂移的症状是「译文挂到了错误的句子上」，静默且难查）。

## 切分为什么是这样的

见 [merge_sentences] 的说明。要点：这是**确定性**的纯函数 ——
同一份 SRT 反复跑得到同一份划分，`resegment.py` 的重排和
`translate_batch.py` 的断点续做都依赖这一条。
"""

import re

# 句末判据
SENT_END = re.compile(r'[.!?…]["\')\]]*$')
# 缩写 / 小数 / 编号，不算句末
ABBREV = re.compile(r'\b(?:Mr|Mrs|Ms|Dr|Prof|Sen|Rep|Gov|St|Jr|Sr|vs|etc|Inc|Ltd|'
                    r'U\.S|U\.K|a\.m|p\.m|No|Fig|approx)\.$')
# 从句边界（兜底断开时优先退到这些位置）
CLAUSE_END = (",", ";", ":", "—", "–")

MAX_CHARS = 240
MAX_CUES = 4


def parse_srt_text(txt: str):
    """返回 [(start_ms, end_ms, text)]，保持文件顺序。"""
    cues = []
    for blk in re.split(r"\n\s*\n", txt.strip()):
        lines = [l for l in blk.split("\n") if l.strip()]
        if len(lines) < 2:
            continue
        # 有编号行时时间码在第 2 行，没有时在第 1 行
        ti = 0 if "-->" in lines[0] else 1
        if ti >= len(lines):
            continue
        m = re.match(r"(\d+):(\d+):(\d+)[,.](\d+)\s*-->\s*(\d+):(\d+):(\d+)[,.](\d+)",
                     lines[ti].strip())
        if not m:
            continue
        g = [int(x) for x in m.groups()]
        start = ((g[0] * 60 + g[1]) * 60 + g[2]) * 1000 + g[3]
        end = ((g[4] * 60 + g[5]) * 60 + g[6]) * 1000 + g[7]
        text = " ".join(lines[ti + 1:]).strip()
        if text:
            cues.append((start, end, text))
    return cues


def parse_srt(path):
    """[parse_srt_text] 的文件版。"""
    with open(path, encoding="utf-8") as f:
        return parse_srt_text(f.read())


def ends_sentence(text):
    t = text.rstrip()
    if not t or not SENT_END.search(t):
        return False
    return not ABBREV.search(t)


def merge_sentences(cues, max_chars=MAX_CHARS, max_cues=MAX_CUES):
    """返回 [(first_cue_idx, last_cue_idx, text)]，覆盖全部句段且不重叠。

    ⚠️ **每次调用都从原始 cues 重算**，不要拿上一次的输出再喂进来 ——
    合并过的 cue 里有一部分不以句末标点结尾（`max_chars` 兜底断开的那种），
    再跑一次会被并进下一句，结果就变了。它**不幂等**。
    """
    texts = [c[2] for c in cues]
    n = len(texts)
    out, i = [], 0
    while i < n:
        j, buf = i, []
        while j < n:
            buf.append(texts[j])
            joined = " ".join(buf)
            if ends_sentence(joined):
                j += 1
                break
            if len(joined) >= max_chars or len(buf) >= max_cues:
                # 兜底：退到最近的从句边界。至少保留 2 段，否则退无可退就原地断
                cut = None
                for k in range(len(buf) - 1, 1, -1):
                    if buf[k - 1].rstrip().endswith(CLAUSE_END):
                        cut = k
                        break
                if cut:
                    buf = buf[:cut]
                    j = i + cut
                else:
                    j += 1
                break
            j += 1
        out.append((i, j - 1, " ".join(buf)))
        i = j
    return out
