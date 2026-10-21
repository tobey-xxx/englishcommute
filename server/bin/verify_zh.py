#!/usr/bin/env python3
"""核验服务端产出的中文字幕与英文字幕**逐条对齐**。

    verify_zh.py [subs 目录] [--require-all]
    verify_zh.py --selftest          # 用构造的坏样本验证断言本身有分辨力

守的是那条最要命的契约：**`.zh.json` 的元素数必须严格等于 `.srt` 的句段数**。
App 侧 `cached.size == parsed.size` 才采用，否则整份丢弃 ——
而错位的译文比没有译文更糟（它会把 A 句的中文挂到 B 句上，且看起来一切正常）。

这些错误**都不会报错**，只会让译文张冠李戴，所以必须靠断言拦：
  ① 元素数与句段数不等
  ② 句子首行是空串（中文挂到续行上去了）
  ③ **同一段中文出现在两条不同的 cue 上**（用户看到同一句被重复显示）
  ④ 出现 null（上次翻失败/被打断）
  ⑤ 有 SRT 却没有 zh.json

## ⚠️★★ 2.1.9：「续行贴了中文」这个判据**必然误报**，已换掉

第一版（以及到 2.1.8 为止的这一版）用的是「按 `merge_sentences` 重新推出的句子结构，
要求同一句的续行全空」。**它在 2.1.1 之后必然误报**，理由是：

`resegment.py` 把 cue 划分改成了一句一条。而 `merge_sentences` **不幂等**
（它自己的 docstring 就写着这一条）—— 拿它去分析**已经重切分过的** srt，
它会把两条本来各自成句的 cue 又并成一句（因为重切分后的 cue 更长，
累计到 `MAX_CHARS` 时退到的从句边界与当初不同），于是要求后一条留空，
而它里面本来就有**它自己的**（完全不同的）中文。

实测：2026-09-22 全量 48 条里这个判据报出 **45 处**，逐条比对后
**真·同一句重复 0 处、误报 45 处**。也就是说这个断言一直在**纯报噪音**，
而它的代价是真实的 —— 部署脚本里那句「真实产出」永远红着，
看久了就没人再看它（本工程的纪律：**一条会误报的断言比没有断言更糟**）。

现在的判据是**用户真正会看到的东西**：同一段非空中文不许出现在两条 cue 上。
它不依赖任何句子结构，也就不会跟 `merge_sentences` 的幂等性纠缠。

退出码非 0 表示有问题，可直接接进 CI / 定时任务。
"""

import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
# ⚠️ 2.1.1：从 `srt_split` 拿。原先经 `translate_batch` 转手，
#    而那个模块会 import 整个推理栈（numpy / onnxruntime / CTranslate2）——
#    只是解析一份 SRT 而已，不该拖上它们。
from srt_split import merge_sentences, parse_srt   # noqa: E402

# ③ 号判据的门槛：中文短于这个长度就不判重复。
# 依据是实测 —— 撞车的全是 "是啊。"（3 字）这种语气词，而真正要拦的
# 「一整段中文被挂到两个地方」动辄几十字。15 是个留足余量的保守值。
DUP_MIN_CHARS = 15


def same_en(a: str, b: str) -> bool:
    """两段英文算不算「同一句」。

    ## ⚠️ 为什么必须归一化之后再比（3.3.0 修一条真实的假警报）

    ③ 号判据的前提是「**英文不同** 且 中文相同」。而「英文不同」原来是
    `cues[j][2].strip() == cues[i][2].strip()` —— **逐字**比较。

    实测（2026-09-29，全量 211 条里唯一一条告警）：
    同一句话在 SRT 里出现两次，一次是「…NPR Politics **P**odcast…」、
    另一次是「…NPR Politics **p**odcast…」—— **只差一个字母的大小写**。
    逐字比较判成「英文不同」⇒ 报「长段中文重复（英文并不相同）」。
    而那不是缺陷：**音频真的把同一句播了两遍，字幕跟着重复是对的**。

    代价是真实的：部署脚本里那句「真实产出」永远红着，看久了就没人再看它 ——
    本工程的纪律原话是「**一条会误报的断言比没有断言更糟**」。

    归一化只做两件事：**折空白**（换行/多空格）与**忽略大小写**。
    ⚠️ 刻意**不折标点** —— 标点不同往往真的不是同一句
       （「他说：」/「他说」这种差别在 ASR 里就代表不同的分割）。
    """
    return " ".join(a.split()).casefold() == " ".join(b.split()).casefold()


def check_one(srt_path, arr):
    """返回错误字符串列表（空 = 合格）。arr 为 None 表示文件缺失。"""
    cues = parse_srt(srt_path)
    if not cues:
        return [f"SRT 解析不出句段：{srt_path}"]
    if arr is None:
        return ["缺 zh.json"]
    errs = []
    if len(arr) != len(cues):
        errs.append(f"元素数 {len(arr)} ≠ 句段数 {len(cues)}")
        return errs          # 长度都不对，再往下判没有意义（会全是噪音）
    n_null = sum(1 for x in arr if x is None)
    if n_null:
        errs.append(f"含 {n_null} 个 null（未完成）")
    # ② 句子首行必须有中文。
    #    这一条**仍然要靠结构**：重切分后每条 cue 就是一个句子，
    #    所以「有字母却没中文」的 cue 就是真的漏了译文。
    bad_head = 0
    for a, b, txt in merge_sentences(cues):
        if any(ch.isalpha() for ch in txt) and not arr[a]:
            bad_head += 1
    if bad_head:
        errs.append(f"{bad_head} 处句子首行没有中文")
    # ③ ★ 2.1.9：**长段中文配着不同的英文重复出现**才是真缺陷。
    #
    #    ⚠️ 这条判据必须先排除两类**实测到的假阳性**，否则它会变成新的噪音源
    #    （2026-09-22 全量数据上实测 66 对"中文重复"，逐对看过之后：
    #      · 63 对是**英文也相同** —— 音频本身在重复（"This is NPR News." / "Okay." /
    #        "Good morning, Steve." 这类短句在主番外里反复出现），字幕跟着重复
    #        **是正确的**，不是缺陷。
    #      · 剩下 3 对是短的语气词（"Yeah." → "是啊。"）撞成同一段译文 —— 也正常。
    #    所以判据收窄成三条同时成立：英文不同 + 中文相同 + 中文足够长。）
    #
    #    这一条不依赖句子结构，因此不会和 `merge_sentences` 的**非幂等性**纠缠 ——
    #    而那是上一个版本 ③ 号判据每天在报 45 处噪音的原因（见文件头）。
    short_same_en = 0
    seen: dict = {}
    dups = []
    for i, x in enumerate(arr):
        if not x:
            continue
        key = x.strip()
        if key in seen:
            j = seen[key]
            # ⚠️ 3.3.0：这里从**逐字**比较改成 `same_en`（折空白 + 忽略大小写）。
            #    理由是实测到的一条假警报，全部经过写在 same_en 的 docstring 里。
            if same_en(cues[j][2], cues[i][2]):
                short_same_en += 1
            elif len(key) >= DUP_MIN_CHARS:
                dups.append((j, i))
            continue
        seen[key] = i
    if dups:
        a, b = dups[0]
        errs.append(f"{len(dups)} 处长段中文重复（cue{a} 与 cue{b} 等，英文并不相同）")
    return errs


def selftest():
    """坏样本对照：每条坏样本都必须被拦下，否则断言没有分辨力。

    ⚠️ 2.1.9 起这里**同时收好样本** —— 本脚本历史上最大的问题是「误报」，
    而误报只能靠「一条本来就正确的产出」钉住：旧的 ③ 号判据每天报 45 处噪音，
    根因就是没有这样一条样本（当时只放了坏样本）。
    """
    print("坏样本对照：每条坏样本都必须被拦下；★ 的好样本必须通过\n")
    tmp = Path("/tmp/_vz_selftest.srt")
    tmp.write_text(
        "1\n00:00:01,000 --> 00:00:03,000\nThe war with Iran is expected to be\n\n"
        "2\n00:00:03,000 --> 00:00:05,000\nthe main focus this week.\n\n"
        "3\n00:00:05,000 --> 00:00:07,000\nWall Street futures are higher today.\n",
        encoding="utf-8")
    # 「音频本身在重复」那一类：两条 cue 的英文**完全相同**（NPR 的短句反复出现）
    rep = Path("/tmp/_vz_selftest_rep.srt")
    rep.write_text(
        "1\n00:00:01,000 --> 00:00:03,000\nThis is NPR News.\n\n"
        "2\n00:00:03,000 --> 00:00:05,000\nThis is NPR News.\n",
        encoding="utf-8")
    # ★★ 好样本 4（3.3.0）：「同一句话、大小写不一样」。
    #    这一条钉住的是**一条真实的假警报**：ASR 在第一处写 "Podcast"、
    #    第二处写 "podcast"，逐字比较会把它判成「英文不同」⇒
    #    报「长段中文重复」。而音频确实把同一句播了两遍，重复是对的。
    case_last = Path("/tmp/_vz_selftest_case.srt")
    case_last.write_text(
        "1\n00:00:01,000 --> 00:00:03,000\n"
        "This week on the NPR Politics Podcast, Republicans are on their heels.\n\n"
        "2\n00:00:03,000 --> 00:00:05,000\n"
        "This week on the NPR Politics podcast, Republicans are on their heels.\n",
        encoding="utf-8")
    good = ["预计与伊朗的战争将是本周的主要焦点。", "", "华尔街期货今日走高。"]
    LONG = "这是一段足够长的中文译文，用来验证长段中文被挂到两处时会被拦下来。"
    cases = [
        ("正确产出", good, False),
        ("元素数少一个", good[:2], True),
        ("首行空、中文挪到续行", ["", good[0], good[2]], True),
        ("出现 null（未翻完）", [good[0], None, good[2]], True),
        ("缺文件", None, True),
        ("★长段中文重复（英文不同）", [LONG, LONG, good[2]], True),
        # ★★ 好样本 1：重切分之后 cue0/cue1 各自成句、各自有自己的（不同的）中文。
        #    这是旧结构判据误报的主力（线上 45 处全是这一类）。
        ("★重切分后各自成句（必须通过）",
         ["这是前半句的译文。", "这是完全不同的一句的译文。", good[2]], False),
    ]
    ok = True
    n_bad = n_good = 0
    for name, arr, want_fail in cases:
        errs = check_one(tmp, arr)
        caught = bool(errs)
        good_news = caught == want_fail
        ok &= good_news
        # 分开计数：坏样本与好样本是**两种**断言（「要拦住」/「不许误报」），
        # 合成一个数会让「全靠坏样本撑起来」的假覆盖看不出来
        if want_fail:
            n_bad += 1
        else:
            n_good += 1
        tag = "✅" if good_news else "❌ 断言失效"
        detail = ("；".join(errs) if errs else "无问题")
        print(f"  {tag} {name:<24} → {detail}")

    # ★★ 好样本 2：**音频本身在重复**（两条 cue 英文相同）—— 字幕跟着重复是对的。
    #    线上实测 66 对「中文重复」里 63 对属于这一类。
    errs = check_one(rep, ["这是NPR新闻。", "这是NPR新闻。"])
    p2 = not errs
    ok &= p2
    n_good += 1
    print(f"  {'✅' if p2 else '❌ 断言失效'} {'★音频本身重复（必须通过）':<24} → "
          + ("；".join(errs) if errs else "无问题"))

    # ★★ 好样本 3：短的语气词撞成同一段译文（"Yeah." / "Okay."）—— 正常。
    errs = check_one(rep, ["是啊。", "是啊。"])
    p3 = not errs
    ok &= p3
    n_good += 1
    print(f"  {'✅' if p3 else '❌ 断言失效'} {'★短语气词撞车（必须通过）':<24} → "
          + ("；".join(errs) if errs else "无问题"))

    # ★★ 好样本 4（3.3.0）：**同一句话、只差大小写**。
    #    这一条钉住一条**真实的假警报**（全量 211 条里唯一那条）：
    #    ASR 在第一处写 "Podcast"、第二处写 "podcast"，逐字比较把它判成
    #    「英文不同」⇒ 报「长段中文重复」。而音频确实把同一句播了两遍。
    CASE_ZH = "本周在NPR政治播客上, 共和党人紧跟其后, 即将花很多钱在广告上。"
    errs = check_one(case_last, [CASE_ZH, CASE_ZH])
    p4 = not errs
    ok &= p4
    n_good += 1
    print(f"  {'✅' if p4 else '❌ 断言失效'} {'★同一句仅大小写不同（必须通过）':<24} → "
          + ("；".join(errs) if errs else "无问题"))

    tmp.unlink(missing_ok=True)
    rep.unlink(missing_ok=True)
    case_last.unlink(missing_ok=True)
    print(f"\n{'全部坏样本都被拦住，且好样本都未被误报' if ok else '有断言失效 —— 判据需加强'}")
    # ⚠️ 必须报出**项数**：`tools/run-all-verify.sh` 的总表直接抄这一行，
    #    没有它那里只能显示 `-`，而 `-` 会被读成「0 项」。
    #    （这个项目为此定过规矩：数字要抄脚本自己的，不许在文档里估。）
    print(f"通过 {n_bad + n_good} 项"
          f"（坏样本 {n_bad} 条须拦下 + 好样本 {n_good} 条须放行），"
          f"失败 {0 if ok else 1} 项")
    return 0 if ok else 1


def main():
    if "--selftest" in sys.argv:
        return selftest()
    # ⚠️ 位置参数要跳过 `--xxx`：否则 `verify_zh.py --require-all` 会把
    #    开关本身当成目录路径，报「没有 .srt」——一个自己制造的假故障。
    pos = [a for a in sys.argv[1:] if not a.startswith("--")]
    subs = Path(pos[0]) if pos else Path("/opt/ec/www/subs")
    require_all = "--require-all" in sys.argv

    srts = sorted(subs.glob("*.srt"))
    if not srts:
        print(f"❌ {subs} 下没有 .srt")
        return 1

    bad, no_zh, total_cues, total_zh = 0, 0, 0, 0
    print(f"检查 {len(srts)} 份字幕 → {subs}\n")
    for srt in srts:
        eid = srt.stem
        zhp = subs / f"{eid}.zh.json"
        arr = None
        if zhp.exists():
            try:
                arr = json.loads(zhp.read_text(encoding="utf-8"))
            except Exception as e:
                bad += 1
                print(f"❌ {eid[:26]}  译文 JSON 解析失败：{e}")
                continue
        cues = parse_srt(srt)
        total_cues += len(cues)
        errs = check_one(srt, arr)
        if arr is None:
            no_zh += 1
            print(f"{'❌' if require_all else '⚠️ '} {eid[:26]}  句段 {len(cues):4d}  "
                  f"** 缺 zh.json **")
            if require_all:
                bad += 1
            continue
        if errs:
            bad += 1
            print(f"❌ {eid[:26]}  句段 {len(cues):4d}  → " + "；".join(errs))
        else:
            filled = sum(1 for x in arr if x)
            total_zh += filled
            print(f"✅ {eid[:26]}  句段 {len(cues):4d}  中文 {filled:4d}  "
                  f"续行留空 {len(arr) - filled:4d}")

    print(f"\n合计：句段 {total_cues}，已翻 {total_zh}，缺译文 {no_zh} 集，异常 {bad} 集")
    fail = bad or (require_all and no_zh)
    print(f"结论：{'有问题' if fail else '全部对齐'}")
    return 1 if fail else 0


if __name__ == "__main__":
    sys.exit(main())
