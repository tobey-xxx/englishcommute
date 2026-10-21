# -*- coding: utf-8 -*-
"""P0.5 建规范例句库 + P0.6 覆盖率报告。

数据源：Tatoeba 的中英句对（`cmn-eng_links` 把中文句和英文句连起来）。
产物面向客户端：「给一个 lemma，给 1~2 条教材式例句 + 中文对照」。

## 三个必须处理的问题

1. **Tatoeba 的中文是简繁混杂**（`我們試試看！` 和 `我该去睡觉了。` 共存）。
   用户是简体用户 —— 必须**优先挑简体**。用一张繁体特征字表判别。

2. **不是所有链接都能用**：英文可能有 `In 1995, ...`（带数字）、引号对话、
   省略号残片。要筛。筛选的松紧直接决定覆盖率，所以把多档规则都跑出来看曲线。

3. **词形要还原**：例句里出现的是 `signed`，我们要挂到 `sign` 上。
   复用 build_vocab 的 resolve()，保证「能查到词」和「能查到例句」用同一套规则。
"""
import csv, glob, collections, os, re, random, sys
# 繁简转换。Tatoeba 的中文是简繁混杂（实测：繁体句占 39%，全丢掉太浪费）——
# 用户是简体用户，但**转换 > 丢弃**。
try:
    from zhconv import convert as zh_convert
except ImportError:
    sys.exit('需要 zhconv：/opt/ec/ttsv/bin/pip install zhconv（并用该 venv 的 python 跑本脚本）')

csv.field_size_limit(10 ** 9)
TAT = '/opt/ec/state/tatoeba'
VOCAB_BASE = '/opt/ec/www/vocab/vocab.base'
CSV_PATH = '/opt/ec/state/ecdict.csv'
OUT_DIR = '/opt/ec/www/vocab'

# ---------------- 载入词表（目标 lemma 集合）----------------
target = set()
for line in open(VOCAB_BASE, encoding='utf-8'):
    p = line.rstrip('\n').split('\t')
    if p and p[0]:
        target.add(p[0])
print('目标 lemma %d 个（vocab.base）' % len(target))

# ---------------- 复刻 resolve（与 build_vocab 保持一致）----------------
EC = {}
for r in csv.DictReader(open(CSV_PATH, encoding='utf-8')):
    w = (r.get('word') or '').strip().lower()
    if w and w not in EC:
        EC[w] = r
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
    if len(lemma) >= 3 and lemma.isalpha():
        possessive.setdefault(lemma + "'s", lemma)
print('ECDICT 就绪（%d 词）' % len(EC))


def resolve(surface):
    if surface in direct:
        return direct[surface]
    if surface not in EC and surface in possessive:
        return possessive[surface]
    if surface in reverse:
        return reverse[surface]
    return surface


APOS_IN = {'\u2019': "'", '\u2018': "'", '\u02bc': "'"}


def norm(t):
    for a, b in APOS_IN.items():
        t = t.replace(a, b)
    return t.strip('.,!?;:()[]{}"“”\'‘’–—-').lower()


# ---------------- 1) 链接：cmn_id -> [eng_id] ----------------
links = collections.defaultdict(list)
for line in open(os.path.join(TAT, 'cmn-eng_links.tsv'), encoding='utf-8'):
    p = line.rstrip('\n').split('\t')
    if len(p) == 2 and p[0].isdigit():
        links[p[0]].append(p[1])
need_cmn = set(links.keys())
need_eng = set()
for v in links.values():
    need_eng.update(v)
print('链接 %d 条 · 中文句 %d · 英文句 %d' % (sum(len(v) for v in links.values()),
                                              len(need_cmn), len(need_eng)))

# ---------------- 2) 只读用得到的中文句 ----------------
cmn = {}
for line in open(os.path.join(TAT, 'cmn_sentences.tsv'), encoding='utf-8'):
    p = line.rstrip('\n').split('\t', 2)
    if len(p) == 3 and p[0] in need_cmn:
        cmn[p[0]] = p[2]
print('取到中文句 %d' % len(cmn))

# 繁体残留判别：zhconv 转完之后再查一遍，仍有繁体特征字的直接丢
TRAD = set('們這來個說為國會時對開關門長學實體發無與從後還進運過產經濟題區類點網話語讀寫師親愛兒錢買賣車東馬鳥魚龍鳳龜歲歷歸屬歡樂聽讀館處當黨嚴議護')
def is_trad(s):
    return any(c in TRAD for c in s)


# ---------------- 3) 只读用得到的英文句 ----------------
eng = {}
for line in open(os.path.join(TAT, 'eng_sentences.tsv'), encoding='utf-8'):
    p = line.rstrip('\n').split('\t', 2)
    if len(p) == 3 and p[0] in need_eng:
        eng[p[0]] = p[2]
print('取到英文句 %d' % len(eng))

# ---------------- 4) 组装候选句对 ----------------
def eng_ok(s, lo=4, hi=16, max_comma=2):
    n = len(s.split())
    if not (lo <= n <= hi):
        return False
    if s[0].isupper() is False or s[-1] not in '.?!':
        return False
    if re.search(r'\d', s):
        return False
    if any(c in s for c in '"“”'):
        return False
    if '--' in s or ' - ' in s or '...' in s or '…' in s:
        return False
    if s.count(',') > max_comma:
        return False
    if re.search(r'[^\x00-\x7F]', s):           # 英文句里不该有非 ASCII
        return False
    return True


def cmn_ok(s, lo=4, hi=40):
    if not (lo <= len(s) <= hi):
        return False
    if re.search(r'[0-9０-９]', s):
        return False
    if any(c in s for c in '"“”《》'):
        return False
    return True


pairs = []            # (en, zh, en_id)
skipped = collections.Counter()
for cid, eids in links.items():
    zh = cmn.get(cid)
    if not zh or not cmn_ok(zh):
        skipped['zh'] += 1
        continue
    zh2 = zh_convert(zh, 'zh-cn')            # 繁体 -> 简体
    if is_trad(zh2):                          # 转完还有繁体字（生僻字/异体字）-> 丢
        skipped['trad'] += 1
        continue
    zh = zh2
    for eid in eids:
        en = eng.get(eid)
        if not en or not eng_ok(en):
            skipped['en'] += 1
            continue
        pairs.append((en, zh, eid))
print('可用句对 %d（筛掉：中文 %d · 繁体 %d · 英文 %d）'
      % (len(pairs), skipped['zh'], skipped['trad'], skipped['en']))

# ---------------- 5) 倒排：lemma -> [(en, zh, score)] ----------------
buckets = collections.defaultdict(list)
for en, zh, eid in pairs:
    seen = set()
    for tok in en.split():
        w = norm(tok)
        if len(w) < 2 or not any(c.isalpha() for c in w):
            continue
        lem = resolve(w)
        if lem in target and lem not in seen:
            seen.add(lem)
            # 打分：越接近 9 词越好；逗号越少越好
            score = abs(len(en.split()) - 9) + en.count(',') * 2
            buckets[lem].append((score, en, zh, eid))

hit = len(buckets)
print()
print('=== P0.6 覆盖率 ===')
print('  有规范例句的 lemma：%d / %d = %.1f%%' % (hit, len(target), 100 * hit / len(target)))

# 按词频加权（用户实际会点到谁）
rank_of = {}
for line in open(VOCAB_BASE, encoding='utf-8'):
    p = line.rstrip('\n').split('\t')
    if len(p) >= 3 and p[2].isdigit() and int(p[2]) > 0:
        rank_of[p[0]] = int(p[2])
w_tot = sum(1 for _ in target)
print('  其中带词频信息的 %d 个' % len(rank_of))

# ---------------- 6) 每词挑 1~2 条最优 ----------------
chosen = {}
for lem, cands in buckets.items():
    cands.sort(key=lambda x: (x[0], x[3]))       # 稳定排序，可复现
    chosen[lem] = [(en, zh) for _, en, zh, _ in cands[:2]]

ex_path = os.path.join(OUT_DIR, 'examples.base')
with open(ex_path, 'w', encoding='utf-8') as fh:
    for lem in sorted(chosen):
        for en, zh in chosen[lem]:
            fh.write('%s\t%s\t%s\n' % (lem, en, zh))

sz = os.path.getsize(ex_path)
n_lines = sum(len(v) for v in chosen.values())
print()
print('  examples.base：%.2f MB（%d 行，覆盖 %d 个 lemma，平均 %.1f 条/词）'
      % (sz / 1048576, n_lines, len(chosen), n_lines / max(1, len(chosen))))

# ---------------- 7) 抽样核验 ----------------
random.seed(11)
keys = sorted(chosen)
with open(os.path.join(OUT_DIR, 'sample-examples.txt'), 'w', encoding='utf-8') as fh:
    fh.write('抽样 60 条规范例句（核验：是不是通顺的教材式英文 + 中文对照是否对得上）\n')
    fh.write('=' * 78 + '\n\n')
    for lem in random.sample(keys, min(60, len(keys))):
        fh.write('[%s]\n' % lem)
        for en, zh in chosen[lem]:
            fh.write('  %s\n' % en)
            fh.write('  %s\n' % zh)
        fh.write('\n')

# 反查：语料里查不到释义的词，是不是也没有例句
print()
print('  （对照）vocab.base 里的 lemma 一旦有例句，卡片上就能同时给「规范例句」和「你听到的」')
