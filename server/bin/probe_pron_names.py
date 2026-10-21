#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""诊断：读音文件名规则 与 词表 lemma 规则 是否一致。

两套规则：
  pron 脚本（build_pron.py）  "".join(c for c in w if c.isalpha()).lower()
      -> 删掉所有非字母（撇号、连字符、点都删）   don't -> dont
  词表（build_vocab.py / WordTable.normalize）
      -> 弯引号转直、剥首尾标点空白、转小写      don't -> don't

如果不一致，客户端拿 lemma 去下 `<lemma>.mp3` 会 404；
更糟的是 `dont.mp3` 里 TTS 读的是 "dont" 而不是 "don't" —— 读音是错的。
"""
import glob
import os
import re

SUBS = "/opt/ec/www/subs/*.srt"
PRON = "/opt/ec/www/pron"
BASE = "/opt/ec/www/vocab/vocab.base"

APOS = "'\u2019\u2018\u02bc"


def possessive_norm(w):
    """词表的归一化（近似）：弯引号转直、剥首尾标点、转小写"""
    for a in "\u2019\u2018\u02bc":
        w = w.replace(a, "'")
    w = w.strip(".,!?;:()[]{}\"“”'‘’–—- \t\n")
    return w.lower()


def pron_norm(w):
    """build_pron.py 的归一化：只留字母"""
    return "".join(c for c in w if c.isalpha()).lower()


# ---- 1. 语料里的原始 token ----
raw = set()
for f in glob.glob(SUBS):
    t = open(f, encoding="utf-8").read()
    for b in t.strip().split("\n\n"):
        lines = b.strip().split("\n")
        if len(lines) >= 3:
            for w in " ".join(lines[2:]).split():
                if len([c for c in w if c.isalpha()]) > 1:
                    raw.add(w)

print("语料原始 token（含标点）: %d 个" % len(raw))

# ---- 2. 两套归一化不同的词 ----
diff = {}
for w in raw:
    a, b = possessive_norm(w), pron_norm(w)
    if a != b and a:
        diff.setdefault(a, set()).add(b)

print("两套归一化结果不同的词形: %d 个" % len(diff))
print()

# ---- 3. 这些词在词表 base 里的 lemma 是什么 ----
base_lemmas = set()
for line in open(BASE, encoding="utf-8"):
    p = line.split("\t")
    if p and p[0]:
        base_lemmas.add(p[0])

in_base = {k: v for k, v in diff.items() if k in base_lemmas}
print("其中在 vocab.base 里**真实存在**的 lemma: %d 个（这些是客户端会去下读音的）" % len(in_base))
print()

# ---- 4. 服务端文件名现状 ----
files = set(f[:-4] for f in os.listdir(PRON) if f.endswith(".mp3"))
print("=== 逐条核对：客户端会下的名字  vs  服务端实际有的名字 ===")
print("  %-16s %-16s %-8s %-8s %s" % ("lemma（客户端要下）", "pron 归一化", "服务端有?", "内容对不对?", "说明"))
bad_missing = []
bad_wrong = []
for lemma in sorted(in_base):
    pn = sorted(in_base[lemma])[0]
    has_exact = lemma in files
    has_pron = pn in files
    if has_exact:
        note = "OK 精确命中"
    elif has_pron:
        note = "★ 会读到 TTS 拼错的词（%s，读音可能是错的）" % pn
        bad_wrong.append((lemma, pn))
    else:
        note = "★ 服务端两个名字都没有（还没生成到）"
        bad_missing.append((lemma, pn))
    print("  %-16s %-16s %-8s %-8s %s" % (lemma, pn, "有" if has_exact else "-", "有" if has_pron else "-", note))

print()
print("=== 汇总 ===")
print("  精确命中: %d" % (len(in_base) - len(bad_wrong) - len(bad_missing)))
print("  名字不对但有近似文件（读音会错）: %d" % len(bad_wrong))
print("  两个名字都没有（生成还没跑到）: %d" % len(bad_missing))
print()
print("=== 受影响样例（前 25 个「读音会错」的）===")
for lemma, pn in bad_wrong[:25]:
    print("  %-18s -> 会读到 %s" % (lemma, pn))
print()
print("=== 检查已生成文件里那些「拼错的词」的内容长度是否正常 ===")
for lemma, pn in bad_wrong[:8]:
    p = os.path.join(PRON, pn + ".mp3")
    if os.path.exists(p):
        print("  %-18s %s.mpx %d 字节" % (lemma, pn, os.path.getsize(p)))
