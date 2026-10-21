#!/usr/bin/env python3
"""核验发布清单（index.json）与磁盘上的文件**互相一致**。

    verify_publish.py [www 目录] [--selftest]

守的是一类**不报错但会让客户端整集不可用**的问题：

  ① 清单里 `zh` / `srt` / `words` 指向的文件不存在（广告了却是 404）
  ② 磁盘上有 `.srt` 却不在清单里（孤儿：占盘且永远没人访问）
  ③ 清单有中文标记、但句段数对不上（App 会整份丢弃 → 用户看不到译文却不报错）
  ④ `count` 字段与实际条目数不符
  ⑤ **（2.1.9）标记 `hosted` 的条目，`www/audio/<id>.mp3` 必须真的在** ——
     托管音频是「字幕与音频是同一份」这个承诺的**唯一凭据**，
     它一旦 404，用户拿到的就又是另一次广告拼接（见 cycle.py 的说明）

断言的写法与 `verify_zh.py` 一致：**先有坏样本，再谈断言**。
"""

import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from verify_zh import check_one                      # noqa: E402

FILE_KEYS = ("srt", "words", "zh")

# 托管音频在 www 下的相对位置。**与 cycle.py 的 `WWW_AUDIO / f"{eid}.mp3"` 是同一份约定** ——
# 两边各写一份就会漂移，而漂移的表现是「音频全 404」这种一眼看不出原因的事。
AUDIO_DIR = "audio"
AUDIO_SUFFIX = ".mp3"


def audio_rel(eid: str) -> str:
    return f"{AUDIO_DIR}/{eid}{AUDIO_SUFFIX}"


def check_index(www: Path, index: dict):
    """返回错误列表。"""
    errs = []
    eps = index.get("episodes") or []
    if index.get("count") != len(eps):
        errs.append(f"count 字段 {index.get('count')} ≠ 实际条目 {len(eps)}")

    referenced = set()
    for e in eps:
        eid = e.get("id", "?")
        for k in FILE_KEYS:
            rel = e.get(k)
            if not rel:
                continue
            referenced.add(rel)
            if not (www / rel).exists():
                errs.append(f"{eid[:20]} 的 {k} 指向不存在的文件 {rel}")
        # ⑤ 托管音频：**只认文件，不认字段**（字段是意图，文件才是事实）
        if e.get("hosted"):
            rel = audio_rel(eid)
            referenced.add(rel)
            if not (www / rel).exists():
                errs.append(f"{eid[:20]} 标了 hosted，但 {rel} 不存在")
            # 地址必须指向**这一条自己**的文件。指错的话客户端播的是别人的音频，
            # 而字幕是这一条的 —— 比 404 更难发现（能播、但全错）
            url = e.get("audioUrl") or ""
            if f"{eid}{AUDIO_SUFFIX}" not in url:
                errs.append(f"{eid[:20]} 标了 hosted，但 audioUrl 不指向自己：{url[:60]}")
        # ⑥ **id 种子必须存在**（2.1.9）。
        #    客户端的 id = `stableId(guid, audioUrl)`，**guid 缺失时退回用 audioUrl**。
        #    而 2.1.9 起 audioUrl 会随「这条音频托没托管」变化（CDN ↔ 本服务）——
        #    于是一条缺 guid 的条目会在换地址的那一刻**换掉自己的身份**，
        #    收藏 / 播放进度 / 已下音频全部孤立，而且**不报任何错**。
        #    cycle.py 的 `backfill_missing_guid` 负责补，这里是最后一道闸。
        if not (e.get("guid") or "").strip():
            errs.append(f"{eid[:20]} 缺 guid（客户端会退回用 audioUrl 算 id，"
                        f"而 audioUrl 会随托管状态变化 → 收藏与进度会孤立）")
        # ⑦ **发布闸门**（2.2.0）：清单里每一条都必须是「字幕已就绪」的。
        #    用户的诉求原话：「确保服务器端字幕都准备好，才在 app 端更新，
        #    如果字幕未更新好，不在 app 端刷新出这个音频素材。」
        #    两条判据（旧版**一条都没有**，闸门只写在 cycle.py 里，没有守门人）：
        #      · `srt` 字段必须**非空** —— 旧版照发，客户端拿到一条没字幕的音频
        #      · 有英文字幕就必须有中文，除非显式标了 `zhPending`
        #        （那是熔断放行，见 cycle.publish 里的说明）
        if not (e.get("srt") or "").strip():
            errs.append(f"{eid[:20]} 没有 srt 字段 —— 发布闸门漏了，"
                        f"这条在 App 里是不可用的")
        if not (e.get("zh") or "").strip() and not e.get("zhPending"):
            errs.append(f"{eid[:20]} 有英文字幕但没有中文、也没有 zhPending 标记"
                        f" —— 发布闸门漏了，用户会以为这一期的中文坏了")
        # 有 zh 就顺便核一遍对齐（这类错误不报错，最难发现）
        zh = e.get("zh")
        srt = e.get("srt")
        if zh and srt and (www / zh).exists() and (www / srt).exists():
            try:
                arr = json.loads((www / zh).read_text(encoding="utf-8"))
            except Exception as ex:
                errs.append(f"{eid[:20]} 的 zh 解析失败：{ex}")
                continue
            sub = check_one(www / srt, arr)
            if sub:
                errs.append(f"{eid[:20]} 译文未对齐：" + "；".join(sub))

    # ⚠️★ 2.2.0：**在索引里的条目**的文件不算孤儿。
    #
    #   存量重做（`--rehost`）和常规一轮的顺序都是「先把字幕/音频写进发布区，
    #   最后一次才 publish」。中间那几十分钟里「文件在、清单还没指它」是
    #   **正常过程**，不是缺陷。
    #
    #   不排除它的话，一次部署只要撞上重做就会报一堆假问题 —— 实测报了 10 条。
    #   而**假问题比没有问题更糟**：它会让人不再看这个脚本的输出。
    #   （真孤儿仍然抓得住：条目滚出索引后留下的文件，其 id 不在 eps 里。）
    known_ids = {e.get("id") for e in eps}

    orphans = [p.name for p in (www / "subs").glob("*.srt")
               if f"subs/{p.name}" not in referenced and p.stem not in known_ids]
    if orphans:
        errs.append(f"{len(orphans)} 个字幕不在清单里（孤儿）：{orphans[:3]}")
    if (www / AUDIO_DIR).is_dir():
        audio_orphans = [p.name for p in (www / AUDIO_DIR).glob(f"*{AUDIO_SUFFIX}")
                         if f"{AUDIO_DIR}/{p.name}" not in referenced
                         and p.stem not in known_ids]
        if audio_orphans:
            errs.append(f"{len(audio_orphans)} 个音频不在清单里（孤儿）：{audio_orphans[:3]}")
    return errs


def selftest():
    print("坏样本自检 —— 正确产出必须通过，每条坏样本必须被拦下\n")
    import shutil
    root = Path("/tmp/_vp_selftest")
    shutil.rmtree(root, ignore_errors=True)
    (root / "subs").mkdir(parents=True, exist_ok=True)
    (root / AUDIO_DIR).mkdir(parents=True, exist_ok=True)
    (root / "subs" / "a.srt").write_text(
        "1\n00:00:01,000 --> 00:00:02,000\nHello world today.\n", encoding="utf-8")
    (root / "subs" / "a.zh.json").write_text('["你好，世界。"]', encoding="utf-8")
    (root / AUDIO_DIR / "a.mp3").write_bytes(b"\xff\xfb\x90\x00" * 100)
    # ⚠️ 这份必须是**真的错位**：a.srt 只有 1 个句段，所以 2 个元素才是坏样本。
    #    （第一版这里写的是 1 个元素 —— 那本来就是合法配对，测试自己错了。）
    (root / "subs" / "mismatch.zh.json").write_text('["你好，世界。","多出来的一句"]',
                                                    encoding="utf-8")

    good = {
        "count": 1,
        "episodes": [{"id": "a", "guid": "g-a", "srt": "subs/a.srt", "zh": "subs/a.zh.json",
                      "hosted": True, "audioUrl": "http://x/audio/a.mp3"}],
    }
    cases = [
        ("正确清单（应通过）", good, False),
        ("广告了不存在的 zh", {**good, "episodes": [
            {"id": "a", "guid": "g-a", "srt": "subs/a.srt", "zh": "subs/ghost.zh.json"}]}, True),
        ("count 与实际不符", {**good, "count": 9}, True),
        ("译文句数对不上", {**good, "episodes": [
            {"id": "a", "guid": "g-a", "srt": "subs/a.srt",
             "zh": "subs/mismatch.zh.json"}]}, True),
        ("srt 路径写错", {**good, "episodes": [
            {"id": "a", "guid": "g-a", "srt": "subs/nope.srt", "zh": "subs/a.zh.json"}]}, True),
        # ★ 2.1.9 新增的三条：托管音频
        ("标了 hosted 但音频不在", {**good, "episodes": [
            {"id": "ghost", "guid": "g-ghost", "srt": "subs/a.srt", "hosted": True,
             "audioUrl": "http://x/audio/ghost.mp3"}]}, True),
        ("标了 hosted 但地址指向别人", {**good, "episodes": [
            {"id": "a", "guid": "g-a", "srt": "subs/a.srt", "hosted": True,
             "audioUrl": "http://x/audio/somebody-else.mp3"}]}, True),
        # ★ 2.1.9 新增：缺 guid（id 会退回用 audioUrl 算，而 audioUrl 会变）
        ("缺 guid（id 会随音频地址漂移）", {**good, "episodes": [
            {"id": "a", "srt": "subs/a.srt", "zh": "subs/a.zh.json"}]}, True),
        # ★★ 2.2.0 三条：发布闸门（字幕未就绪 → 不许出现在 App 里）
        ("★有英文字幕但没中文、也没标记", {**good, "episodes": [
            {"id": "a", "guid": "g-a", "srt": "subs/a.srt", "hosted": True,
             "audioUrl": "http://x/audio/a.mp3"}]}, True),
        ("★连 srt 字段都没有（旧版照发）", {**good, "episodes": [
            {"id": "a", "guid": "g-a", "zh": "subs/a.zh.json", "hosted": True,
             "audioUrl": "http://x/audio/a.mp3"}]}, True),
        # ⚠️ 这一条是**好样本**，必须通过：熔断放行的条目靠 zhPending 自证
        #    「中文会来、现在还不算缺」。少了这个标记，客户端会一直等一个
        #    不存在的东西 —— 把「预告」和「缺失」混成一件事。
        ("★熔断放行（无 zh 但标了 zhPending）", {**good, "episodes": [
            {"id": "a", "guid": "g-a", "srt": "subs/a.srt", "zhPending": True,
             "hosted": True, "audioUrl": "http://x/audio/a.mp3"}]}, False),
    ]
    ok = True
    n_bad = n_good = 0
    for name, idx, want_fail in cases:
        errs = check_index(root, idx)
        caught = bool(errs)
        ok &= (caught == want_fail)
        if want_fail:
            n_bad += 1
        else:
            n_good += 1
        tag = "✅" if caught == want_fail else "❌ 断言失效"
        print(f"  {tag} {name:<20} → " + ("；".join(errs[:2]) if errs else "无问题"))

    # 孤儿：磁盘上有一份不在清单里的字幕
    (root / "subs" / "orphan.srt").write_text(
        "1\n00:00:01,000 --> 00:00:02,000\nOrphan.\n", encoding="utf-8")
    errs = check_index(root, good)
    caught = any("孤儿" in e and "字幕" in e for e in errs)
    ok &= caught
    n_bad += 1
    print(f"  {'✅' if caught else '❌ 断言失效'} {'孤儿字幕（不在清单里）':<20} → "
          + ("；".join(errs[:1]) if errs else "无问题"))

    # 孤儿：磁盘上有一份不在清单里的托管音频
    (root / AUDIO_DIR / "orphan.mp3").write_bytes(b"\xff\xfb\x90\x00" * 100)
    errs = check_index(root, good)
    caught = any("孤儿" in e and "音频" in e for e in errs)
    ok &= caught
    n_bad += 1
    print(f"  {'✅' if caught else '❌ 断言失效'} {'孤儿音频（不在清单里）':<20} → "
          + ("；".join(errs[:1]) if errs else "无问题"))

    # ── ★★ 2.2.0 好样本：**重做中途的暂态**必须不被误报 ──────────────────
    #    顺序是「先把文件写进发布区、最后一次才 publish」，所以中间会有
    #    「音频在、清单还没指它」。判据是「**条目在不在索引里**」——
    #    在索引里就说明下一次发布就会指它，那不是孤儿。
    #    ⚠️ 这一条是**实测逼出来的**：一次部署撞上重做，报了 10 条假问题。
    #    重建一个干净目录，免得前面两个孤儿样本干扰。
    shutil.rmtree(root, ignore_errors=True)
    (root / "subs").mkdir(parents=True, exist_ok=True)
    (root / AUDIO_DIR).mkdir(parents=True, exist_ok=True)
    (root / "subs" / "a.srt").write_text(
        "1\n00:00:01,000 --> 00:00:02,000\nHello world today.\n", encoding="utf-8")
    (root / "subs" / "a.zh.json").write_text('["你好，世界。"]', encoding="utf-8")
    (root / AUDIO_DIR / "a.mp3").write_bytes(b"\xff\xfb\x90\x00" * 100)
    (root / AUDIO_DIR / "pending.mp3").write_bytes(b"\xff\xfb\x90\x00" * 100)
    mid = {"count": 2, "episodes": [
        {"id": "a", "guid": "g-a", "srt": "subs/a.srt", "zh": "subs/a.zh.json",
         "hosted": True, "audioUrl": "http://x/audio/a.mp3"},
        # 已经进了索引、音频也下回来了，但**这一次发布还没轮到它**
        {"id": "pending", "guid": "g-p", "srt": "subs/a.srt", "zh": "subs/a.zh.json",
         "hosted": False, "audioUrl": "https://cdn/pending.mp3"},
    ]}
    errs = check_index(root, mid)
    # ⚠️ 判据是「**一条错误都没有**」，不是「没有孤儿」—— 第一版写成后者，
    #    于是 count 字段写错时它照样通过（测试自己不合格却报绿）。
    caught = not errs
    ok &= caught
    n_good += 1
    print(f"  {'✅' if caught else '❌ 断言失效'} {'★重做中途（文件先到、清单后指）':<20} → "
          + ("；".join(errs[:1]) if errs else "无问题"))

    print(f"\n{'坏样本全被拦下，且正确清单未被误报' if ok else '有坏样本漏过 —— 断言需加强'}")
    # ⚠️ 必须报出**项数**（`tools/run-all-verify.sh` 的总表抄这一行；
    #    没有它那里显示 `-`，而 `-` 会被读成「0 项」）
    print(f"通过 {n_bad + n_good} 项"
          f"（坏样本 {n_bad} 条须拦下 + 好样本 {n_good} 条须放行），"
          f"失败 {0 if ok else 1} 项")
    shutil.rmtree(root, ignore_errors=True)
    return 0 if ok else 1


def main():
    if "--selftest" in sys.argv:
        return selftest()
    # 位置参数跳过 `--xxx`，理由同 verify_zh.py
    pos = [a for a in sys.argv[1:] if not a.startswith("--")]
    www = Path(pos[0]) if pos else Path("/opt/ec/www")
    idx_path = www / "index.json"
    if not idx_path.exists():
        print(f"❌ 找不到 {idx_path}")
        return 1
    index = json.loads(idx_path.read_text(encoding="utf-8"))
    errs = check_index(www, index)

    eps = index.get("episodes") or []
    with_zh = sum(1 for e in eps if e.get("zh"))
    hosted = sum(1 for e in eps if e.get("hosted"))
    srts = len(list((www / "subs").glob("*.srt")))
    audios = len(list((www / AUDIO_DIR).glob(f"*{AUDIO_SUFFIX}"))) \
        if (www / AUDIO_DIR).is_dir() else 0
    print(f"清单 {len(eps)} 条（{with_zh} 条带中文、{hosted} 条音频托管）| "
          f"磁盘 {srts} 份字幕 / {audios} 份音频 | 生成于 {index.get('generatedAt')}\n")
    if errs:
        for e in errs[:20]:
            print(f"  ❌ {e}")
        print(f"\n结论：{len(errs)} 个问题")
        return 1
    print("结论：清单与文件完全一致 ✅")
    return 0


if __name__ == "__main__":
    sys.exit(main())
