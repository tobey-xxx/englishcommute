#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
生词本 · 标准读音批量生成（P0.4 · v2 修正版）

音色：en-US-GuyNeural（美式男声 · 用户 2026-09-23 选定）

================================================================================
## v1 的两个 bug（这一版修的就是它们）
================================================================================

v1 用的归一化是 `"".join(c for c in w if c.isalpha()).lower()` —— 「只留字母」。
它在两处同时出错：

### bug 1：文件名与词表 lemma 对不上 → 客户端下载 404

词表（`build_vocab.py` / `WordTable.normalize`）的规则是「弯引号转直、剥首尾标点、转小写」，
**保留词内部的撇号和连字符**。所以客户端的 lemma 是 `don't`，会去下 `don't.mp3`；
而 v1 生成的是 `dont.mp3`。实测 **168 个词形**落在这个缝里。

### bug 2（更严重）：TTS 读到的是拼错的词 → **读音是错的**

`he'll` 被当成 `hell` 送进 TTS，读出来是 /hel/；`we'll` 变成 `well`；
`i'll` 变成 `ill`；`i'd` 变成 `id`。**用户听到的读音是另一个词的。**

这类错最难发现：文件存在、字节数正常、点开能播 —— 只是读错了。
只有把「TTS 收到的文本」和「客户端要的名字」当成**两件事**分别处理才对。

================================================================================
## v2 的两条规则
================================================================================

1. **TTS 的输入文本** = 词表里的**原始词形**（`don't`、`well-known`、`alzheimer's`）
2. **文件名** = 同一个词形做**安全映射**：

       safe(w) = ''.join(c if (c 是 ASCII 字母或数字) else '_' for c in w.lower())

   `don't` → `don_t.mp3` · `well-known` → `well_known.mp3` · `sign` → `sign.mp3`（不变）

   为什么要映射而不是直接用原名：撇号/连字符在 shell、URL、nginx 里都需要转义，
   而**「一个不需要在任何环节转义的文件名」是最不容易出问题的形式**。

   ⚠️ 映射必须**唯一**。实测 vocab.base 的 171 个特殊 lemma 映射后零冲突
      （`test_pron_names.py` 有断言）。若将来词表变动导致冲突，脚本会拒绝启动。

⚠️ **这个 safe() 必须在客户端有一份逐字符一致的实现**（`PronDownloader.pronFileName`）。
   两边不一致的症状是「有些词读音一直下不下来」，而日志里只有一个 404。

用法：
    python3 build_pron_v2.py --limit 20                # 小批量试跑
    python3 build_pron_v2.py --delay 0.15              # 全量（断点续传，已存在的跳过）
    python3 build_pron_v2.py --report                  # 只统计，不生成
    python3 build_pron_v2.py --prune                   # 删除不属于任何 safe(lemma) 的旧文件
    python3 build_pron_v2.py --budget-seconds 180      # **被素材循环调用时用这个**
    python3 build_pron_v2.py --budget-seconds 180 --quiet

================================================================================
## 被素材循环调用（`cycle.py` 的 `refresh_pron`）
================================================================================

新词只可能来自新素材，所以读音必须跟着素材循环走 —— 否则「这周加的
`algorithmic` 有释义、点读音却什么都没有」，而那个词可能永远等不到有人
再手工跑一次全量。

两条保护，缺一不可：

1. **`--budget-seconds`（时间预算）**：到点就停，剩下的下一轮继续。
   为什么不用 `--limit`（词数）—— 因为**单词耗时不是常数**：网络一慢，
   「50 个词」能从 40 秒变成 5 分钟，一轮 15 分钟的循环就被它吃掉了。
   预算按**秒**算，才跟「这一轮还能花多久」对得上。

2. **单实例锁**（`--prune` 之外都加）：同一个微软在线接口**不能有两个调用者**。
   手工跑着全量的时候，循环里那一次必须安静地让路 —— 两个进程并发只会
   互相拖慢、并提高被限流的概率（`dictionaryapi` 40 个请求封 IP 是同一种坑）。
   拿不到锁时**打印一行并正常退出（码 0）**，不是失败：让路是预期行为。
"""

import argparse
import asyncio
import fcntl
import glob
import os
import sys
import time

VOICE = "en-US-GuyNeural"
OUT_DIR = "/opt/ec/www/pron"
VOCAB_DIR = "/opt/ec/www/vocab"
SUBS = "/opt/ec/www/subs/*.srt"


def safe(word):
    """文件名安全映射。**必须与客户端 `PronDownloader.pronFileName` 逐字符一致**。

    白名单 = ASCII 字母 + 数字 + `.` + `-`，其余一律换成 `_`。

    ⚠️ 白名单的边界是**试出来的，不是拍的**：第一版把「非字母数字」一律换成 `_`，
       自检立刻报出冲突 —— `a&m` 和 `a.m` 都变成 `a_m`。
       而 `.` 和 `-` 本身在 URL、shell、nginx、Android File API 里都不需要转义，
       所以保留它们既缩小了替换面、又消掉了这类冲突。

    ⚠️ 撇号**故意不在白名单里**：它虽然合法，但在 shell 双引号、部分 URL 编码器、
       以及某些文件系统工具链上都要额外小心 —— 换成 `_` 的代价只是名字不好看。
    """
    return "".join(
        c if (('a' <= c <= 'z') or ('0' <= c <= '9') or c == '.' or c == '-') else "_"
        for c in word.lower()
    )


def wanted_words():
    """
    要生成读音的全部词形。**= 语料 token ∪ 它们的原形。**

    ## 为什么是「语料 token ∪ 它们的 lemma」，而不是「vocab.base 全部」

    先看客户端到底会来取哪些名字 —— 它只可能来自这一条链路：

        用户长按字幕里的词（surface，**必然出现在语料里**）
          → WordTable.lookup(surface) → 返回 base 里的一个 entry
          → 取 entry.lemma → 下载 safe(lemma).mp3

    所以「客户端可能来取的名字」= `{t : t ∈ 语料}` ∪ `{lemma(t)}`。
    **它不含 vocab.base 里那些语料从没出现过的词条。**

    ⚠️ 这不是小差别。实测（2026-09-23）：

    | 集合 | 数量 |
    |---|---|
    | 语料原始 token | 9353 |
    | `vocab.base` 全部词条 | 11936 |
    | `vocab.forms` 全部变形 | 14253 |
    | **三者并集（旧算法）** | **26837** |
    | **语料 token ∪ 它们的 lemma（本函数）** | **10080** |
    | 差 = 白生成 | **16757** ⇒ 约 7 小时 + 180 MB |

    那 16757 个是给谁生成的？是 **P0.1 补进来的 bnc 高频词**（`abbey` / `abandonment` /
    `abdominal`…）—— 它们让「新语料的词形覆盖率」从 65% 提到 83%，是**为了查词**，
    不是为了读音。用户只有在字幕里遇到它们时才可能点，而那时它们就进语料了。

    ## 为什么旧的算法当时看着也「对」

    因为它写的判据是「vocab.base 的 lemma 列 = 客户端按 lemma 下载的权威集合」——
    这句话**本身没错**，但它用了**代理量**：base 是「有释义的词」的集合，
    而这里要问的是「**用户会点到的词**」的集合。两个集合在「语料∖base」
    和「base∖语料」两个方向上都不同，而只有后者是空集。

    ## 保留「原始 token」这一路的理由（v1 的教训）

    v1 用的是「只留字母」后的 token，所以 `we'll` 从来没作为一个独立的词进过队列。
    这一路**不剥撇号与连字符** —— 它们正是客户端会拿去当文件名的形态。
    """
    words = set()

    # ① 语料原始 token（保留撇号与连字符，只去首尾标点）
    for f in glob.glob(SUBS):
        t = open(f, encoding="utf-8").read()
        for b in t.strip().split("\n\n"):
            lines = b.strip().split("\n")
            if len(lines) >= 3:
                for w in " ".join(lines[2:]).split():
                    w = w.strip(".,!?;:()[]{}\"\u201c\u201d\u2018\u2019\u2013\u2014")
                    for a in "\u2019\u2018\u02bc":
                        w = w.replace(a, "'")
                    # ⚠️ 判据是「**至少一个**字母」，不是「至少两个」。
                    #    老版本写的是 `> 1`，而 `a` / `I` 是真实的高频词 ——
                    #    收紧候选集时实测发现：`a.mp3` 因此被判成「用不到」要删掉，
                    #    可用户长按 `a` 时客户端照样会去取。删了就是 404。
                    #    （`> 1` 的本意是滤掉标点残渣，而「至少一个字母」就够滤了 ——
                    #      纯标点 strip 完是空串，本来就会被下面 `if w` 挡掉。）
                    if any(c.isalpha() for c in w):
                        words.add(w.lower())

    corpus = set(words)

    # ② 它们的 lemma（客户端 lookup 之后真正拿去下载的那个名字）
    #
    #    ⚠️ 只取 **forms 里指向的**，不加 base 的全部词条 —— 见上面那段推导。
    forms = os.path.join(VOCAB_DIR, "vocab.forms")
    if os.path.exists(forms):
        for line in open(forms, encoding="utf-8"):
            i = line.find("\t")
            if i > 0:
                surface = line[:i].strip()
                lemma = line[i + 1:].strip()
                if surface in corpus and lemma:
                    words.add(lemma)

    return words


def check_unique(words):
    """映射唯一性自检。**冲突必须拒绝启动** —— 两个词共用一个音频文件，
    不可能都对，而且哪一个是错的取决于生成顺序。"""
    m = {}
    dup = {}
    for w in words:
        k = safe(w)
        if k in m and m[k] != w:
            dup.setdefault(k, [m[k]]).append(w)
        m[k] = w
    if dup:
        print("❌ 文件名映射冲突（%d 组）：" % len(dup))
        for k, v in list(dup.items())[:10]:
            print("   %-20s <- %s" % (k, v))
        sys.exit("映射不唯一，两个词会共用一个音频文件。先改 safe() 规则。")
    return m


LOCK_PATH = "/opt/ec/state/pron.lock"


class AlreadyRunning(Exception):
    """另一个实例正跑着。让路是预期行为，调用方不该当成失败。"""


def acquire_singleton_lock():
    """
    拿单实例锁。拿不到就抛 [AlreadyRunning]。

    ⚠️ 用 `flock`（`LOCK_NB`）而不是「检查 pid 文件」：
       进程被 `kill -9` 时 pid 文件会留下来，而 flock 由内核在进程退出时自动释放
       —— 没有「需要有人去清理的残留状态」。这一点与读音文件名的教训是同一条：
       **凡是有残留状态的机制，都需要有人负责清理，而那个人永远会漏。**
    """
    os.makedirs(os.path.dirname(LOCK_PATH), exist_ok=True)
    f = open(LOCK_PATH, "w")
    try:
        fcntl.flock(f.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
    except OSError:
        f.close()
        raise AlreadyRunning()
    # 锁跟着这个 fd 走，所以要把它返回给调用方持有到进程结束
    return f


async def gen_one(word, out, retries=3):
    """生成一个词。返回 True/False。word 是**原始词形**（TTS 的输入）"""
    import edge_tts
    for attempt in range(retries):
        try:
            c = edge_tts.Communicate(word, VOICE)
            await c.save(out)
            if os.path.getsize(out) > 500:
                return True
            os.remove(out)
        except Exception as e:
            if attempt == retries - 1:
                print("  x %-22s %s" % (word, type(e).__name__), flush=True)
        await asyncio.sleep(0.4 * (attempt + 1))
    return False


# ⚠️ 所有「过程性」输出都走这个函数而不是 print（2026-09-23 修）。
#
# 起因：`--quiet` 原来只挡了进度行，前面那些
# 「候选词形 / 含特殊字符 / 待生成」和在「没有待生成」时打印的
# **20 行文件名对照表**照样输出。而调用方（`cycle.py` 的 `refresh_pron`）
# 天真地以为「--quiet 就是安静」，用「取最后两行」来裁剪 ——
# 于是每轮素材循环都往 cycle.log 里灌两行毫不相干的对照表。
#
# 两处都错，两处都改了：这里让 `--quiet` 名副其实；
# 调用方那边改成**按内容找那一行**（见 cycle.py 的 refresh_pron）。
QUIET = False


def say(*a, **kw):
    """过程性输出。`--quiet` 时不打（最终那一行「读音：…」用 print，始终要打）"""
    if not QUIET:
        print(*a, **kw)


async def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--limit", type=int, default=0)
    ap.add_argument("--delay", type=float, default=0.15)
    ap.add_argument("--report", action="store_true")
    ap.add_argument("--prune", action="store_true")
    ap.add_argument("--prune-dry", action="store_true",
                    help="只报告会删掉哪些文件，不真删（改过候选集之后先跑这个）")
    ap.add_argument("--budget-seconds", type=float, default=0,
                    help="最多跑这么多秒，到点停下、剩下的下一轮继续（被素材循环调用时用）")
    ap.add_argument("--quiet", action="store_true",
                    help="精简输出（循环里跑时用，避免每 15 分钟往日志里灌一段进度）")
    args = ap.parse_args()

    global QUIET
    QUIET = args.quiet

    # --prune / --prune-dry 不加锁：它们只列出或删除文件，不做生成。
    # 而且 **dry-run 必须随时能跑** —— 想看一眼「会删掉什么」时，
    # 不该被一个正在跑的全量挡在门外（那正是最需要看清单的时刻）。
    # 其余情况都要拿锁，见文件头「两条保护」。
    lock = None
    if not (args.prune or args.prune_dry):
        try:
            lock = acquire_singleton_lock()
        except AlreadyRunning:
            say("另一个读音生成任务正在跑，本次让路（这不是失败）")
            return

    os.makedirs(OUT_DIR, exist_ok=True)
    words = wanted_words()
    say("候选词形: %d" % len(words))

    mapping = check_unique(words)
    special = [w for w in words if safe(w) != w]
    say("含特殊字符（撇号/连字符/点）: %d 个 —— 这些正是 v1 读错的" % len(special))
    say("映射唯一性: ✅ 无冲突")
    say()

    todo = sorted(w for w in words
                  if not (os.path.exists(os.path.join(OUT_DIR, safe(w) + ".mp3"))
                          and os.path.getsize(os.path.join(OUT_DIR, safe(w) + ".mp3")) > 500))
    say("待生成: %d · 已存在: %d" % (len(todo), len(words) - len(todo)))

    if args.prune or args.prune_dry:
        expect = set(safe(w) + ".mp3" for w in words)
        stale = sorted(f for f in os.listdir(OUT_DIR)
                       if f.endswith(".mp3") and f not in expect)
        freed = sum(os.path.getsize(os.path.join(OUT_DIR, f)) for f in stale)

        if args.prune_dry:
            # ⚠️ 先 dry 再删。--prune 是不可逆的，而「改过候选集」正是最容易
            #    删错东西的时刻 —— 清单要让人**看一眼**再决定，
            #    而不是信一句「已清理 N 个」。
            print("🧪 DRY RUN —— 会删掉 %d 个文件（%.1f MB），**这次没删**"
                  % (len(stale), freed / 1048576))
            say()
            say("   抽查 20 个（它们对应的词形，看是不是真的用不到）：")
            for f in stale[:20]:
                say("     %-30s （文件名去后缀后的词）" % f)
            say()
            say("   如果里面有「你认识的、像是会点到的词」，**先别删** ——")
            say("   那说明候选集收得太紧了，要回去改 wanted_words()。")
            return

        for f in stale:
            os.remove(os.path.join(OUT_DIR, f))
        say("🧹 清理了 %d 个不属于任何词的文件（释放 %.1f MB）"
              % (len(stale), freed / 1048576))
        if stale:
            say("   样例:", ", ".join(stale[:10]))
        return

    if args.report or not todo:
        if args.report:
            say("✅ 没有待生成的了")
        elif not args.quiet:
            say("✅ 没有待生成的了")
        else:
            # ⚠️ **`--quiet` 且没事可做时也必须给出结论行**（2026-09-23 修）。
            #
            #    这个是**常态**：绝大多数轮次都没有新词要生成。
            #    原来这里在 quiet 下什么都不打，于是调用方
            #    （`cycle.py` 的 `refresh_pron`）分不清两件事：
            #
            #        「这一轮没事可做」  vs  「脚本的输出格式变了 / 被截断了」
            #
            #    而后者是它**应该报警**的情况 —— 结果每轮都报一次假警。
            #    「什么都没有」和「出错了」长得一样，是最贵的 bug 类型
            #    （项目纪律里那条「静默吞掉」正是说这个）。
            print("读音：本轮 +0 · 待生成 0")
        if not args.report:
            return
        # 抽样报告：特殊字符词的文件名对照（这是给人看的，不受 --quiet 影响）
        say()
        say("=== 特殊字符词的正确文件名（前 20）===")
        for w in sorted(special)[:20]:
            p = os.path.join(OUT_DIR, safe(w) + ".mp3")
            say("  %-22s -> %-26s %s" % (w, safe(w) + ".mp3",
                                           ("%d 字节" % os.path.getsize(p)) if os.path.exists(p) else "(待生成)"))
        return

    if args.limit:
        todo = todo[:args.limit]
        say("（--limit %d，只跑前 %d 个）" % (args.limit, len(todo)))

    say()
    t0 = time.time()
    ok = fail = 0
    done = 0
    for i, w in enumerate(todo, 1):
        # ⚠️ 预算检查放在**生成之前**，不是生成之后。放在后面意味着
        #    「已经超时了还要再生成一个」，而单词耗时可能有几秒的尾巴 ——
        #    一轮 15 分钟的循环里，这个尾巴会累积成「每次发布都迟到」。
        if args.budget_seconds and (time.time() - t0) >= args.budget_seconds:
            break
        out = os.path.join(OUT_DIR, safe(w) + ".mp3")
        if await gen_one(w, out):
            ok += 1
        else:
            fail += 1
        done = i
        if not args.quiet and (i % 50 == 0 or i == len(todo)):
            el = time.time() - t0
            rate = i / el if el > 0 else 0
            left = (len(todo) - i) / rate / 60 if rate > 0 else 0
            say("[%d/%d] ok=%d fail=%d · %.2f 词/秒 · 已用 %.0f 分 · 剩余约 %.0f 分"
                  % (i, len(todo), ok, fail, rate, el / 60, left), flush=True)
        await asyncio.sleep(args.delay)

    left_n = len(todo) - done
    if args.quiet:
        # 循环里跑：一行说清「这一轮做了什么、还剩多少」，剩下什么都不打印
        if fail:
            say("读音：本轮 +%d（失败 %d）· 待生成 %d" % (ok, fail, left_n))
        else:
            print("读音：本轮 +%d · 待生成 %d" % (ok, left_n))
    else:
        say()
        say("✅ 完成：成功 %d，失败 %d，用时 %.1f 分钟" % (ok, fail, (time.time() - t0) / 60))
        if left_n:
            say("（时间预算用完，还剩 %d 个，下一轮继续）" % left_n)

    # lock 由进程退出释放。显式留个引用，避免被 gc 提前关掉 fd。
    _ = lock


if __name__ == "__main__":
    asyncio.run(main())
