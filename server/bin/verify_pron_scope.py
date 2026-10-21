# -*- coding: utf-8 -*-
"""
独立验证：收紧后的读音候选集**不会漏掉任何一个「用户可能点到的词」**。

## 判据（直接对着客户端的行为推）

客户端只可能这样取读音：

    用户长按字幕里的词（surface ∈ 语料）
      → WordTable.lookup(surface)
          ├ 命中 base 的词条      → entry.lemma
          ├ 命中 forms 的映射     → 那个 form 指向的 lemma
          └ 都没命中              → 仍会以 normalize(surface) 为 lemma 尝试
      → 下载 safe(lemma).mp3

所以「必须存在的文件」= { safe(t) : t ∈ 语料 } ∪ { safe(lemma(t)) : t ∈ 语料 }

⚠️ 这个脚本**故意不复用 build_pron_v2.py 的 wanted_words()** ——
   用同一份实现去验证它自己等于没验证。语料 token 的切法这里**重写一遍**
   （与客户端/服务端的 normalize 保持同一条规则），然后和脚本算出来的候选集对。
"""
import glob, os, re, sys

VOCAB = "/opt/ec/www/vocab"
SUBS = "/opt/ec/www/subs/*.srt"
PRON = "/opt/ec/www/pron"

# ---- 与两端一致的两条规则 ----
TRIM = ".,!?;:()[]{}\"\u201c\u201d\u2018\u2019\u2013\u2014"


def normalize(raw):
    """与 WordTable.normalize / build_vocab.py 的 normalize 同一规则"""
    s = raw
    for a, b in (("\u2019", "'"), ("\u2018", "'"), ("\u02bc", "'")):
        s = s.replace(a, b)
    return s.strip(TRIM + " \t\n\r").lower()


def safe(word):
    """与 PronDownloader.pronFileName / build_pron_v2.safe 同一规则"""
    return "".join(c if (c in "abcdefghijklmnopqrstuvwxyz0123456789.-") else "_"
                   for c in word.lower())


# ---- 客户端的 forms 映射 ----
forms_map = {}
for line in open(os.path.join(VOCAB, "vocab.forms"), encoding="utf-8"):
    i = line.find("\t")
    if i > 0:
        forms_map[line[:i].strip()] = line[i + 1:].strip()
base_set = set()
for line in open(os.path.join(VOCAB, "vocab.base"), encoding="utf-8"):
    p = line.split("\t")
    if p and p[0]:
        base_set.add(p[0])

# ---- 语料 token ----
corpus = set()
for f in glob.glob(SUBS):
    t = open(f, encoding="utf-8").read()
    for b in t.strip().split("\n\n"):
        lines = b.strip().split("\n")
        if len(lines) >= 3:
            for w in " ".join(lines[2:]).split():
                w = normalize(w)
                if any(c.isalpha() for c in w):
                    corpus.add(w)

# ---- 客户端可能来取的名字 ----
needed = set()
for t in corpus:
    needed.add(t)                       # ① 点到的词本身
    lm = forms_map.get(t)
    if lm:
        needed.add(lm)                  # ② forms 映射后的原形
    elif t not in base_set:
        pass                            # ③ 查不到就是查不到，仍旧用 t（已在 ①）

needed_files = {safe(w) + ".mp3" for w in needed}

# ---- 脚本算出来的候选集 ----
sys.path.insert(0, "/opt/ec/bin")
import importlib.util
spec = importlib.util.spec_from_file_location("bp", "/opt/ec/bin/build_pron_v2.py")
bp = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bp)
candidates = bp.wanted_words()
cand_files = {safe(w) + ".mp3" for w in candidates}

missing = needed_files - cand_files

print("语料 token（normalize 后去重）      %6d" % len(corpus))
print("客户端可能来取的名字               %6d" % len(needed))
print("  → 对应文件                        %6d" % len(needed_files))
print("脚本的候选集                       %6d 个文件" % len(cand_files))
print()
print("=== 判据 1：客户端要的，候选集必须都有 ===")
if missing:
    print("  ❌ 漏了 %d 个：" % len(missing))
    for f in sorted(missing)[:20]:
        print("       ", f)
else:
    print("  ✅ 一个都不漏")

# ---- 判据 2：现在盘上有的文件，有多少会被删 ----
on_disk = set(f for f in os.listdir(PRON) if f.endswith(".mp3"))
will_delete = on_disk - cand_files
print()
print("=== 判据 2：会被删掉的文件里，有没有客户端真会取的 ===")
bad = will_delete & needed_files
if bad:
    print("  ❌ 会删掉 %d 个客户端真会取的文件：" % len(bad))
    for f in sorted(bad)[:20]:
        print("       ", f)
else:
    print("  ✅ 待删 %d 个里，没有一个是客户端会取的" % len(will_delete))

# ---- 判据 3：抽样看看待删的都是什么词（人工过目）----
print()
print("=== 待删文件抽样（按字母序，看是不是都是语料里没有的）===")
sample = sorted(will_delete)
step = max(1, len(sample) // 14)
for f in sample[::step][:14]:
    print("   %-28s" % f)

print()
if missing or bad:
    print("❌ 收紧收过头了，必须改 wanted_words()")
    sys.exit(1)
print("✅ 收紧安全：客户端会取的词一个不落，待删的都是语料里从没出现的")
