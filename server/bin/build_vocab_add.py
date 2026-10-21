#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
生词本 · 词表增量（P2.3）

================================================================================
## 它解决什么
================================================================================

词表是**发版时**按当时的语料构建的（11795 个原形，2.2 MB，打进 assets）。
而每天的新素材都会带来新词 —— 人名、地名、机构名、新事件的专有名词。

实测（`probe_scale.py`）：拿**前 33 期**建表，**后 33 期**出现 6672 个词，
其中 **2925 个是表外的（43.8%）**。所以「查不到」是常态，不是异常。

⚠️ 3.0.0 的词卡上写着「**下一期同步后就会有**」—— 那句话当时**是假的**：
   同步链路（`SyncEngine` / `ServerCatalog`）根本不碰词表，
   服务端也没有任何生成增量词表的脚本。这是写文案时的想当然，
   用户这一问正好把它戳出来了。

================================================================================
## 做法：**产物差集**，不做代码复用
================================================================================

    最新全量   /opt/ec/www/vocab/vocab.base            （每轮重建）
  − 已发布的那份 /opt/ec/www/vocab/vocab.base.published  （= APK 里 assets 那份）
  = /opt/ec/www/vocab/add.json                         （客户端按需拉，几 KB）

### ⚠️ 为什么是差集，而不是「自己再算一遍新词」

算「新词」需要 normalize / resolve / to_ipa / ECDICT 查表这一整套逻辑，
而那套逻辑**已经完整地存在于 `build_vocab.py` 里**。

抄一份过来 ⇒ **两套规则必然分叉**。本项目在这一类错误上刚吃过大亏：
`build_pron.py` 的 v1 用「只留字母」做文件名的归一化，而词表用的是另一套，
后果是 168 个词形的读音要么下不到、要么**读的是另一个词**（`he'll` → `hell`）。
详见 `build_pron_v2.py` 的 docstring。

**差集天然不可能分叉** —— 它就是同一个脚本两次产物的差。

### 唯一的代价：要保证 `.published` 真的是客户端手里那份

发版时把 `app/src/main/assets/vocab/vocab.base` 传到服务端的 `vocab.base.published`。
漏了这一步的后果是**差集偏小**（把用户已经有的词又发一遍），
而不是偏大 —— 偏小是安全的（客户端只是多下一次几 KB 的重复词）。

================================================================================
## 收敛
================================================================================

发版之后 assets 里的 base 更新（含这些新词）⇒ 下一次 `.published` 就是新的
⇒ 差集自动变小、变空。

**不需要任何累积状态，也不需要「清理」逻辑** —— 这一点很重要：
任何「需要有人记得定期清理」的设计，最后都会变成没人清理。
"""

import hashlib
import io
import json
import os
import sys
from datetime import datetime

VOCAB_DIR = "/opt/ec/www/vocab"
BASE = os.path.join(VOCAB_DIR, "vocab.base")
BASE_PUBLISHED = os.path.join(VOCAB_DIR, "vocab.base.published")
FORMS = os.path.join(VOCAB_DIR, "vocab.forms")
FORMS_PUBLISHED = os.path.join(VOCAB_DIR, "vocab.forms.published")
OUT = os.path.join(VOCAB_DIR, "add.json")

# 一次最多下发多少条。超过它几乎一定意味着**出了问题**（`.published` 太旧、
# 或者语料暴涨），而不是「真的来了这么多新词」。这时候宁可少发、并发警告。
MAX_ENTRIES = 8000


def read_base(path):
    """读一份 vocab.base，返回 {lemma: 整行原文}。"""
    out = {}
    if not os.path.exists(path):
        return out
    for line in io.open(path, encoding="utf-8"):
        line = line.rstrip("\n")
        if not line:
            continue
        i = line.find("\t")
        if i <= 0:
            continue
        out[line[:i]] = line
    return out


def read_forms(path):
    """读一份 vocab.forms，返回 {surface: lemma}。"""
    out = {}
    if not os.path.exists(path):
        return out
    for line in io.open(path, encoding="utf-8"):
        line = line.rstrip("\n")
        i = line.find("\t")
        if i <= 0:
            continue
        out[line[:i]] = line[i + 1:]
    return out


def main():
    if not os.path.exists(BASE):
        sys.exit("找不到 %s —— 先跑 build_vocab.py 生成最新全量" % BASE)
    if not os.path.exists(BASE_PUBLISHED):
        # ⚠️ 不是致命错误，但要**大声说**。
        #    没有 published 时差集 = 全部（会把 11795 个词全发给客户端），
        #    而客户端本来就有这些词。所以这里**主动降级成「不发」**，
        #    而不是发一份巨大的、完全多余的包。
        print("⚠️ 没有 %s —— 无法算差集，本次不生成 add.json" % BASE_PUBLISHED)
        print("   发版时请把 assets 的 vocab.base 传上来：")
        print("   scp app/src/main/assets/vocab/vocab.base ec:%s" % BASE_PUBLISHED)
        sys.exit(0)

    latest = read_base(BASE)
    published = read_base(BASE_PUBLISHED)
    new_base = {k: v for k, v in latest.items() if k not in published}

    latest_forms = read_forms(FORMS)
    pub_forms = read_forms(FORMS_PUBLISHED)
    new_forms = {k: v for k, v in latest_forms.items() if k not in pub_forms}

    print("最新全量 %d 条 · 已发布 %d 条 · **新增 %d 条**" % (len(latest), len(published), len(new_base)))
    print("变形     %d 条 · 已发布 %d 条 · **新增 %d 条**" % (len(latest_forms), len(pub_forms), len(new_forms)))

    # 只留「有释义」的（词表里本来就不该有没释义的条目，这里是防御）
    dropped = [k for k, v in new_base.items() if len(v.split("\t")) < 5 or not v.split("\t")[4].strip()]
    for k in dropped:
        new_base.pop(k, None)
    if dropped:
        print("⚠️ 丢掉 %d 条没有释义的新词（前几个：%s）" % (len(dropped), dropped[:5]))

    # forms 只留「目标 lemma 在最新全量里」的（理论上必然成立，防御性）
    new_forms = {k: v for k, v in new_forms.items() if v in latest}

    if len(new_base) > MAX_ENTRIES:
        print("⚠️ 新词 %d 条，超过上限 %d —— 很可能是 .published 太旧。" % (len(new_base), MAX_ENTRIES))
        print("   仍然生成，但请检查：published 的 mtime = %s"
              % datetime.fromtimestamp(os.path.getmtime(BASE_PUBLISHED)).strftime("%Y-%m-%d %H:%M"))

    base_lines = [new_base[k] for k in sorted(new_base)]
    form_lines = ["%s\t%s" % (k, new_forms[k]) for k in sorted(new_forms)]

    # ⚠️ 版本号来自**内容**，不是时间戳。
    #    内容不变则版本不变 ⇒ 客户端不会重复下载同一个包。
    #    （用时间戳的话，每次重建都会产生"新版本"，客户端每轮拉一次几 KB，
    #      而且**没有任何东西会变化** —— 那是纯粹的浪费。）
    h = hashlib.sha1()
    for line in base_lines:
        h.update(line.encode("utf-8"))
        h.update(b"\n")
    for line in form_lines:
        h.update(line.encode("utf-8"))
        h.update(b"\n")
    version = h.hexdigest()[:10]

    payload = {
        "version": version,
        "generatedAt": datetime.now().isoformat(timespec="seconds"),
        "baseCount": len(base_lines),
        "formCount": len(form_lines),
        # 诊断用：这两个数字能让「为什么 add 是空的」一眼有答案
        "latestCount": len(latest),
        "publishedCount": len(published),
        "format": "base: lemma<TAB>ipa<TAB>freqRank<TAB>levelMask<TAB>defs(|分隔)  forms: surface<TAB>lemma",
        "base": base_lines,
        "forms": form_lines,
    }

    # tmp + rename：客户端在同步时拉这个文件，半截 JSON 会让它解析失败
    tmp = OUT + ".tmp"
    with io.open(tmp, "w", encoding="utf-8") as fh:
        json.dump(payload, fh, ensure_ascii=False, separators=(",", ":"))
    os.replace(tmp, OUT)

    size = os.path.getsize(OUT)
    print()
    print("✅ %s" % OUT)
    print("   版本 %s · %.1f KB" % (version, size / 1024))
    if base_lines:
        print("   新词样例：" + ", ".join(base_lines[i].split("\t")[0] for i in range(min(8, len(base_lines)))))
    else:
        print("   （没有新词 —— 客户端手里的词表已经是最新的）")


if __name__ == "__main__":
    main()
