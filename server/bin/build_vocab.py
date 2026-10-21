# -*- coding: utf-8 -*-
"""P0.1 + P0.2 + P0.3 —— 一次性产出客户端要的词表。

   P0.1 裁词典   : ECDICT(释义/词形/考试标签) + CMUdict(美式音标)  -> vocab.base
   P0.2 音标映射 : ARPAbet -> IPA，重音标在音节首、单音节不标      -> 并进 vocab.base
   P0.3 词形还原 : ECDICT exchange 的 0: 优先 + 反向索引 + 所有格   -> vocab.forms

## 三条设计取舍（免得以后要翻文档）

1. **base 只放原形，forms 放「变形 -> 原形」。**
   否则 `signed` / `signing` / `signs` 各自成条 —— 用户会把同一个词加进生词本三次，
   FSRS 也会为它建三张卡。客户端查询一律先过 forms 再查 base。

2. **音标只用 CMUdict**（纯美式 ARPAbet，39 个音素，映射无歧义）。
   ECDICT 那份音标是**英美混杂**的（`air`=єә 英式、`caring`='kεәriŋ 英式、
   还有 16% 是重拼如 `sign`=`sain`），而 TTS 选的是**美式男声** ——
   英式音标配美音音频就是「同一功能两套标准」，和上一轮删掉 TTS 兜底同一个理由。
   **宁可 10% 的词没有音标，也不要两套标准。**

3. **必须有中文释义才入表**（音标可选）。只有音标没有释义的词条对用户没有价值。
"""
import csv, glob, collections, os, json, random, datetime

CSV_PATH = '/opt/ec/state/ecdict.csv'
CMU_PATH = '/opt/ec/state/cmudict.dict'
OUT_DIR = '/opt/ec/www/vocab'
SUBS = '/opt/ec/www/subs/*.srt'
csv.field_size_limit(10 ** 9)

# ================= P0.2  ARPAbet -> IPA =================
VOW = {'AA': 'ɑ', 'AE': 'æ', 'AO': 'ɔ', 'AW': 'aʊ', 'AY': 'aɪ',
       'EH': 'ɛ', 'EY': 'eɪ', 'IH': 'ɪ', 'IY': 'i', 'OW': 'oʊ', 'OY': 'ɔɪ',
       'UH': 'ʊ', 'UW': 'u'}
CONS = {'B': 'b', 'CH': 'tʃ', 'D': 'd', 'DH': 'ð', 'F': 'f', 'G': 'ɡ', 'HH': 'h',
        'JH': 'dʒ', 'K': 'k', 'L': 'l', 'M': 'm', 'N': 'n', 'NG': 'ŋ', 'P': 'p',
        'R': 'r', 'S': 's', 'SH': 'ʃ', 'T': 't', 'TH': 'θ', 'V': 'v', 'W': 'w',
        'Y': 'j', 'Z': 'z', 'ZH': 'ʒ'}


# 合法的英语**首音簇**（两辅音）。用来决定「重音符号插在辅音簇的哪个位置」。
# 依据 Maximum Onset Principle：辅音簇尽量归给后面的音节，但**只有合法的**才归。
#   `greenland` 的 GR 合法 -> /ˈɡrinlənd/（不是 /ɡrˈinlənd/）
#   `continuation` 的 NT 不合法 -> /kənˌtɪnjuˈeɪʃən/（不是 /kəˌntɪnjuˈeɪʃən/）
#   `businessman` 的 SM 合法但其实是复合词边界 -> /ˈbɪznəsˌsmæn/（这个词会差一点，
#      算法看不到构词法；影响仅限 `s` 归哪一音节，读音不变）
ONSET2 = {
    'sp', 'st', 'sk', 'sm', 'sn', 'sl', 'sw', 'sf', 'sj',
    'pl', 'pr', 'bl', 'br', 'kl', 'kr', 'ɡl', 'ɡr', 'tr', 'dr',
    'θr', 'ʃr', 'fl', 'fr', 'tw', 'kw', 'dw', 'ɡw', 'hw', 'θw', 'vw',
    'pj', 'bj', 'kj', 'ɡj', 'fj', 'vj', 'mj', 'nj', 'lj', 'hj', 'tj', 'dj', 'zj',
    'tʃ', 'dʒ',
}


def _onset(syms, vowel_i):
    """重读元音在 vowel_i，返回重音符号该插的索引。"""
    j = vowel_i
    while j - 1 >= 0 and not syms[j - 1][0]:         # 往前扫到辅音簇的起点
        j -= 1
    if vowel_i - j <= 1:                             # 没有辅音 / 只有一个 -> 就是它
        return j
    if ''.join(s[1] for s in syms[vowel_i - 2:vowel_i]) in ONSET2:
        return vowel_i - 2                           # 后两个是合法首音簇 -> 都归后
    return vowel_i - 1                               # 否则只把最后一个归后


def to_ipa(phones):
    """CMUdict 的 ARPAbet -> IPA。

    ⚠️ 重音位置：CMUdict 把重音标在**元音**上（`S AY1 N`），
    而 IPA 要标在**音节首辅音之前**（/saɪn/ 不是 /sˈaɪn/）。
    做法：从重读元音往前扫辅音，扫到上一个元音为止，在起点前插 ˈ。
    **单音节词不标重音** —— 学习词典的惯例（/saɪn/ 而非 /ˈsaɪn/）。
    """
    syms = []                      # (是否元音, ipa, 重音数字)
    for p in phones:
        stress, base = '', p
        if p and p[-1].isdigit():
            base, stress = p[:-1], p[-1]
        if base == 'AH':
            ipa, is_v = ('ə' if stress == '0' else 'ʌ'), True
        elif base == 'ER':
            ipa, is_v = ('ɚ' if stress == '0' else 'ɝ'), True
        elif base in VOW:
            ipa, is_v = VOW[base], True
        elif base in CONS:
            ipa, is_v = CONS[base], False
        else:
            return None
        syms.append((is_v, ipa, stress))

    n_vow = sum(1 for v, _, _ in syms if v)
    if n_vow == 0:
        return None
    if n_vow == 1:                                   # 单音节：不标重音
        return ''.join(s for _, s, _ in syms)

    marks = {}
    for i, (is_v, _, stress) in enumerate(syms):
        if not is_v or stress not in ('1', '2'):
            continue
        marks.setdefault(_onset(syms, i), 'ˈ' if stress == '1' else 'ˌ')

    out = []
    for i, (_, s, _) in enumerate(syms):
        if i in marks:
            out.append(marks[i])
        out.append(s)
    return ''.join(out)


# ================= 载入数据源 =================
CMU = {}
for line in open(CMU_PATH, encoding='utf-8', errors='replace'):
    line = line.strip()
    if not line or line.startswith(';;;'):
        continue
    if '  ' in line:
        w, ph = line.split('  ', 1)
    elif ' ' in line:
        w, ph = line.split(' ', 1)
    else:
        continue
    base = w.strip().lower().split('(')[0]
    if not base.replace("'", '').isalpha():          # 允许 don't 这类带撇号的
        continue
    if base not in CMU:
        CMU[base] = ph.split()
print('CMUdict 载入 %d 词' % len(CMU))

EC = {}
for r in csv.DictReader(open(CSV_PATH, encoding='utf-8')):
    w = (r.get('word') or '').strip().lower()
    if w and w not in EC:
        EC[w] = r
print('ECDICT  载入 %d 词' % len(EC))

# ================= 语料词形（保留撇号）=================
APOS_IN = {'\u2019': "'", '\u2018': "'", '\u02bc': "'"}


def normalize(tok):
    for a, b in APOS_IN.items():
        tok = tok.replace(a, b)
    return tok.strip('.,!?;:()[]{}"“”\'‘’–—-').lower()


counter = collections.Counter()
for f in glob.glob(SUBS):
    t = open(f, encoding='utf-8').read()
    for b in t.strip().split('\n\n'):
        l = b.strip().split('\n')
        if len(l) >= 3:
            for w in ' '.join(l[2:]).split():
                w2 = normalize(w)
                if len(w2) > 1 and any(c.isalpha() for c in w2):
                    counter[w2] += 1
print('语料词形 %d 种（%d 次）' % (len(counter), sum(counter.values())))

# ================= P0.3 词形还原 =================
FORM_KEYS = ('p', 'd', 'i', '3', 's', 'r', 't')
direct = {}          # surface -> lemma，来自 exchange 的 0:（最可信）
reverse = {}         # surface -> lemma，来自 p/d/i/3/s/r/t 的反向索引
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
print('  exchange 直接原形 %d · 反向变形 %d' % (len(direct), len(reverse)))

# 所有格与缩写：X's -> X（语料里 npr's / trump's 这类查不到，共 825 次）
possessive = {}
for lemma in EC:
    if len(lemma) < 3 or not lemma.isalpha():
        continue
    possessive.setdefault(lemma + "'s", lemma)
    possessive.setdefault(lemma + "'", lemma)
print('  所有格映射 %d' % len(possessive))


def resolve(surface):
    """词形 -> 原形。

    ⚠️ 优先级有个容易写错的点（我第一版就写错了）：**真实词条优先于所有格拆解**。
       所有格表是从「EC 里每个词 + 's」生成的，覆盖面是 77 万条，
       如果放在最前面，`it's` 会被归到 `it` —— 可是 ECDICT 里 `it's` 自己就有词条
       （`it is 的缩写, it has 的缩写`），归到 `it` 反而丢了更准的释义。

       正确顺序：exchange 的 `0:`（ECDICT 自己标的原形，最可信）
              -> 若该词形没有独立词条，才用所有格拆解
              -> 反向变形索引（p/d/i/s/3/r/t）
              -> 自身
    """
    if surface in direct:
        return direct[surface]
    if surface not in EC and surface in possessive:
        return possessive[surface]
    if surface in reverse:
        return reverse[surface]
    return surface


# ---------------- base 只放原形 ----------------
base_lemmas = set()
for surface in counter:
    base_lemmas.add(resolve(surface))
n_corpus = len(base_lemmas)
print('  原形集合（语料派生）%d 个' % n_corpus)

# ★ P0.1 的规模决策：补 bnc 高频词，取 1 万。
#   依据是同目录的 probe_scale.py（用一半语料建表、测另一半）：
#     ├ 只用语料派生： 词形 65.0% · **词频 93.3%** · 0.8 MB
#     ├ + bnc 前 1 万：词形 83.3% · **词频 97.0%** · 1.6 MB
#     ├ + bnc 前 2 万：词形 88.2% · **词频 97.7%** · 2.8 MB
#     └ + bnc 前 5 万：词形 91.4% · **词频 98.3%** · 6.0 MB
#   1 万 -> 5 万只多 1.3 个百分点、体积翻近 4 倍 —— 边际收益断崖。
#   ⚠️ 注意「词形 65% / 词频 93%」这个落差：查不到的几乎全是低频人名地名，
#      用户真正会点的词基本都覆盖了。
#   ⚠️ 这只是**缓解**：未来新语料仍会有 base 外的词。根治是服务端按期下发
#      增量词（原规划 P2.3），那时覆盖率才到 100%。
BNC_TOP_N = 10000
_ranked = []
for w, r in EC.items():
    v = (r.get('bnc') or '').strip()
    if v.isdigit() and int(v) > 0:
        _ranked.append((int(v), w))
_ranked.sort()
common = set(w for _, w in _ranked[:BNC_TOP_N])
base_lemmas |= common
print('  补 bnc 前 %d 高频后：%d 个（净增 %d，其中 %d 个与语料重合）'
      % (BNC_TOP_N, len(base_lemmas), len(base_lemmas) - n_corpus,
         len(common) - (len(base_lemmas) - n_corpus)))

# ================= 组装词条 =================
LEVEL_BITS = {'zk': 1, 'gk': 2, 'cet4': 4, 'cet6': 8, 'ky': 16,
              'toefl': 32, 'ielts': 64, 'gre': 128, 'oxford': 256}


def clean_defs(raw, max_lines=3, max_len=170):
    if not raw:
        return []
    # ⚠️ ECDICT 的多行是**字面 "\n"**（反斜杠 + n 两个字符），不是真换行 —— 实测确认
    parts = raw.replace('\\r', '').replace('\r', '').split('\\n')
    out = []
    for p in parts:
        p = ' '.join(p.split())
        if not p:
            continue
        p = p.replace('|', '/').replace('\t', ' ')
        if len(p) > max_len:
            p = p[:max_len - 1] + '…'
        out.append(p)
    return out[:max_lines]


rows, dropped = [], 0
n_ipa = n_rank = n_tag = 0
for w in sorted(base_lemmas):
    r = EC.get(w)
    defs = clean_defs(r.get('translation') if r else None) if r else []
    if not defs:                                     # 取舍 3：没释义不入表
        dropped += 1
        continue
    phones = CMU.get(w)
    ipa = to_ipa(phones) if phones else None
    if ipa:
        n_ipa += 1

    rank = 0
    if r:
        for k in ('bnc', 'frq'):
            v = (r.get(k) or '').strip()
            if v.isdigit() and int(v) > 0:
                rank = int(v)
                break
    if rank:
        n_rank += 1

    lvl = 0
    if r:
        for t in (r.get('tag') or '').split():
            lvl |= LEVEL_BITS.get(t, 0)
        if (r.get('oxford') or '').strip() not in ('', '0'):
            lvl |= LEVEL_BITS['oxford']
    if lvl:
        n_tag += 1

    rows.append((w, ipa or '', rank, lvl, '|'.join(defs)))

print()
print('=== 产出统计 ===')
print('  入表词条 %d（丢弃 %d：只有音标没有释义 / 查不到）' % (len(rows), dropped))
print('  有音标 %d (%.1f%%) · 有词频 %d (%.1f%%) · 有标签 %d (%.1f%%)'
      % (n_ipa, 100 * n_ipa / max(1, len(rows)), n_rank,
         100 * n_rank / max(1, len(rows)), n_tag, 100 * n_tag / max(1, len(rows))))

os.makedirs(OUT_DIR, exist_ok=True)
base_path = os.path.join(OUT_DIR, 'vocab.base')
with open(base_path, 'w', encoding='utf-8') as fh:
    for w, ipa, rank, lvl, defs in rows:
        fh.write('%s\t%s\t%d\t%d\t%s\n' % (w, ipa, rank, lvl, defs))

base_set = set(x[0] for x in rows)
out_forms, seen = [], set()


def add_form(surface, lemma):
    """变形只保留「指向表内、且自己不是表内原形」的。"""
    if surface == lemma or surface in base_set or surface in seen:
        return
    if lemma not in base_set:
        return
    seen.add(surface)
    out_forms.append((surface, lemma))


# ⚠️ 所有格（X's -> X）**只收录语料里真实出现过的词形**。
#    一开始我遍历了整张所有格表（77 万条），过滤后剩 16000 条 X's ——
#    那是在为「用户永远不会点到的词」占空间（用户只会点他听到的内容里的词）。
for surface, lemma in possessive.items():
    if surface in counter:
        add_form(surface, lemma)
# exchange 的 0: 与反向索引：这是 ECDICT 自己标的变形关系，准确，全收
for src in (direct, reverse):
    for surface, lemma in src.items():
        add_form(surface, lemma)

forms_path = os.path.join(OUT_DIR, 'vocab.forms')
with open(forms_path, 'w', encoding='utf-8') as fh:
    for s, l in sorted(out_forms):
        fh.write('%s\t%s\n' % (s, l))

b, fb = os.path.getsize(base_path), os.path.getsize(forms_path)
print('  vocab.base  : %.2f MB (%d 行)' % (b / 1048576, len(rows)))
print('  vocab.forms : %.2f MB (%d 行)' % (fb / 1048576, len(out_forms)))
print('  合计        : %.2f MB' % ((b + fb) / 1048576))

# ================= 覆盖率 =================
n_direct = sum(1 for s in counter if s in base_set)
n_via = sum(1 for s in counter if s not in base_set and s in seen)
cov = sum(c for s, c in counter.items() if s in base_set or s in seen)
print()
print('=== 语料覆盖率 ===')
print('  直接命中 %d 种 · 经还原 %d 种 · 查不到 %d 种'
      % (n_direct, n_via, len(counter) - n_direct - n_via))
print('  按出现次数加权：%.1f%%（%d / %d）' % (100 * cov / sum(counter.values()), cov, sum(counter.values())))

# ================= 抽样核验 =================
random.seed(42)
with open(os.path.join(OUT_DIR, 'sample-120.txt'), 'w', encoding='utf-8') as fh:
    fh.write('抽样 120 个词条（人工核验：音标是否像标准美音、释义是否通顺）\n' + '=' * 72 + '\n\n')
    for w, ipa, rank, lvl, defs in random.sample(rows, min(120, len(rows))):
        tags = ' '.join(k for k, v in LEVEL_BITS.items() if lvl & v)
        fh.write('%-24s /%s/\n' % (w, ipa or '—'))
        fh.write('    频次 %-6s  标签 %s\n' % (rank or '—', tags or '—'))
        for d in defs.split('|'):
            fh.write('    %s\n' % d)
        fh.write('\n')

with open(os.path.join(OUT_DIR, 'sample-forms.txt'), 'w', encoding='utf-8') as fh:
    fh.write('抽样 60 个变形词（核验：还原对不对）\n' + '=' * 72 + '\n')
    fh.write('%-20s %-18s %s\n' % ('变形', '-> 原形', '原形的释义（截断）'))
    fh.write('-' * 72 + '\n')
    for s, l in random.sample(out_forms, min(60, len(out_forms))):
        fh.write('%-20s %-18s %s\n' % (s, l, (EC.get(l, {}).get('translation') or '')[:44]))

with open(os.path.join(OUT_DIR, 'vocab.meta.json'), 'w', encoding='utf-8') as fh:
    json.dump({
        'builtAt': datetime.datetime.now().isoformat(timespec='seconds'),
        'entries': len(rows), 'forms': len(out_forms),
        'ipaCoverage': round(100 * n_ipa / max(1, len(rows)), 1),
        'corpusCoverage': round(100 * cov / sum(counter.values()), 1),
        'format': 'base: lemma<TAB>ipa<TAB>freqRank<TAB>levelMask<TAB>defs(|分隔)  forms: surface<TAB>lemma',
        'sources': {'defs': 'ECDICT (MIT)', 'ipa': 'CMUdict (BSD, American ARPAbet->IPA)'},
    }, fh, ensure_ascii=False, indent=2)

print()
print('产物 %s：' % OUT_DIR)
for n in sorted(os.listdir(OUT_DIR)):
    print('  %-22s %8.1f KB' % (n, os.path.getsize(os.path.join(OUT_DIR, n)) / 1024))
