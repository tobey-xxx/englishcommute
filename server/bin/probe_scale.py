# -*- coding: utf-8 -*-
"""P0.1 的规模决策实验：base 到底要收多少词？

背景：只用当前语料派生的原形（6004 个），未来新一期里出现的词就会查不到。
上一轮实测「新词率不收敛（10~24%/期）」，所以必须知道：
  **加多少高频词，能把「未来语料」的覆盖率提到多少？**

做法：把 66 期语料分半（按文件名字典序），
      用前一半建 base，测后一半的覆盖率。
      再叠加 ECDICT 的 bnc 高频词，看覆盖率怎么涨、体积怎么涨。

另做一个更严格的模拟：**按源留一**（10 个源建表、测第 11 个源）——
       这测的是「来了一个全新来源」时的情况。
"""
import csv, glob, os, collections, random

csv.field_size_limit(10 ** 9)
SUBS = sorted(glob.glob('/opt/ec/www/subs/*.srt'))
print('语料文件 %d 期' % len(SUBS))

# ---------------- 复刻 build_vocab 的词形提取 ----------------
APOS_IN = {'\u2019': "'", '\u2018': "'", '\u02bc': "'"}


def normalize(tok):
    for a, b in APOS_IN.items():
        tok = tok.replace(a, b)
    return tok.strip('.,!?;:()[]{}"“”\'‘’–—-').lower()


def vocab_of(files):
    c = collections.Counter()
    for f in files:
        t = open(f, encoding='utf-8').read()
        for b in t.strip().split('\n\n'):
            l = b.strip().split('\n')
            if len(l) >= 3:
                for w in ' '.join(l[2:]).split():
                    w2 = normalize(w)
                    if len(w2) > 1 and any(ch.isalpha() for ch in w2):
                        c[w2] += 1
    return c


# ---------------- 载入 ECDICT ----------------
EC = {}
for r in csv.DictReader(open('/opt/ec/state/ecdict.csv', encoding='utf-8')):
    w = (r.get('word') or '').strip().lower()
    if w and w not in EC:
        EC[w] = r
print('ECDICT %d 词' % len(EC))

FORM_KEYS = ('p', 'd', 'i', '3', 's', 'r', 't')
direct, reverse = {}, {}
for w, r in EC.items():
    ex = (r.get('exchange') or '').strip()
    if not ex:
        continue
    for part in ex.split('/'):
        if ':' not in part:
            continue
        k, v = part.split(':', 1)
        v = v.strip().lower()
        if not v or v == w:
            continue
        if k == '0':
            direct[w] = v
        elif k in FORM_KEYS:
            reverse.setdefault(v, w)

possessive = {}
for lemma in EC:
    if len(lemma) < 3 or not lemma.isalpha():
        continue
    possessive.setdefault(lemma + "'s", lemma)


def resolve(surface):
    """⚠️ 修正后的优先级：真实词条优先于所有格拆解。
       原版把所有格放在最前，`it's` 会被错归到 `it`（而 ECDICT 里 `it's` 有自己的释义）。"""
    if surface in direct:
        return direct[surface]
    if surface not in EC and surface in possessive:
        return possessive[surface]
    if surface in reverse:
        return reverse[surface]
    return surface


def has_defs(w):
    r = EC.get(w)
    return bool(r and (r.get('translation') or '').strip())


# ---------------- bnc 高频原形阶梯 ----------------
ranked = []
for w, r in EC.items():
    v = (r.get('bnc') or '').strip()
    if v.isdigit() and int(v) > 0:
        ranked.append((int(v), w))
ranked.sort()
print('有 bnc 排名的词 %d' % len(ranked))


def top_common(n):
    return set(w for _, w in ranked[:n])


def measure(base_lemmas, test_surf):
    """覆盖率：surface 经 resolve 后落在 base_lemmas 里"""
    hit_forms = sum(1 for s in test_surf if resolve(s) in base_lemmas)
    hit_freq = sum(c for s, c in test_surf.items() if resolve(s) in base_lemmas)
    return (100 * hit_forms / max(1, len(test_surf)),
            100 * hit_freq / max(1, sum(test_surf.values())))


def est_size(base_lemmas):
    """粗略估体积：每条 base 约 81 字节；forms 只对语料相关原形建，约 16 字节/条。
       这里用「base 条数 × (81 + 16×3.3)」估，与实测 0.79 MB/6004 条校准。"""
    return len(base_lemmas) * (81 + 16 * 3.3) / 1048576


# ================= 模拟 A：按文件分半 =================
half = len(SUBS) // 2
old_files, new_files = SUBS[:half], SUBS[half:]
old_v, new_v = vocab_of(old_files), vocab_of(new_files)
print()
print('模拟 A：前 %d 期建表 -> 后 %d 期测试（后一半 %d 个词形 / %d 词次）'
      % (half, len(SUBS) - half, len(new_v), sum(new_v.values())))

base_A = set()
for s in old_v:
    lem = resolve(s)
    if has_defs(lem):
        base_A.add(lem)
base_A |= set(s for s in old_v if has_defs(s))
print('  只用前一半语料：base %d 条' % len(base_A))
print()
print('  %-30s %-12s %-12s %s' % ('base 组成', '词形覆盖', '词频覆盖', '估算体积'))
print('  ' + '-' * 68)
for n in (0, 5000, 10000, 20000, 30000, 50000):
    b = set(base_A)
    if n:
        b |= set(w for w in top_common(n) if has_defs(w))
    f, fr = measure(b, new_v)
    label = '只用语料派生' if n == 0 else '+ bnc 前 %d 高频' % n
    print('  %-30s %6.1f%%      %6.1f%%      %.1f MB' % (label, f, fr, est_size(b)))

# 参照：用全部语料
allv = vocab_of(SUBS)
base_all = set()
for s in allv:
    base_all.add(resolve(s))
base_all |= set(allv.keys())
f, fr = measure(set(w for w in base_all if has_defs(w)), new_v)
print('  %-30s %6.1f%%      %6.1f%%      %.1f MB' % ('（参照）全部 66 期语料', f, fr,
                                                     est_size(set(w for w in base_all if has_defs(w)))))

# ================= 模拟 B：按源留一 =================
print()
print('模拟 B：留一法（10 个源建表 -> 第 11 个源测试）—— 测「来了全新来源」')
feeds = collections.defaultdict(list)
for f in SUBS:
    feeds[os.path.basename(f).split('_')[0]].append(f)
print('  源数 %d，各源期数：%s' % (len(feeds), dict((k, len(v)) for k, v in sorted(feeds.items()))))

feed_vocab = dict((k, vocab_of(v)) for k, v in feeds.items())
print()
print('  %-14s %-8s %-8s %-10s %s' % ('留出的源', '词形数', '只用其他源', '+2万高频', '+5万高频'))
print('  ' + '-' * 62)
for k in sorted(feeds):
    test = feed_vocab[k]
    if len(test) < 200:
        continue
    train = set()
    for k2, v2 in feed_vocab.items():
        if k2 == k:
            continue
        for s in v2:
            train.add(resolve(s))
    train = set(w for w in train if has_defs(w))
    a, _ = measure(train, test)
    b, _ = measure(train | set(top_common(20000)), test)
    c, _ = measure(train | set(top_common(50000)), test)
    print('  %-14s %-8d %7.1f%%  %7.1f%%   %7.1f%%' % (k, len(test), a, b, c))

# ================= 当前 base 的真实缺口 =================
print()
print('=== 当前 base（6004 条，只用 66 期语料）在「模拟留出」下的表现 ===')
cur = set()
for s in allv:
    cur.add(resolve(s))
cur = set(w for w in cur if has_defs(w))
for k in sorted(feeds):
    test = feed_vocab[k]
    if len(test) < 200:
        continue
    a, _ = measure(cur, test)
    print('  若这位用户在收听 %-12s 的节目，能查到 %.1f%%' % (k, a))
