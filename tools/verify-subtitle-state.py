#!/usr/bin/env python3
"""字幕状态：**模型必须与磁盘收敛**（真机 bug 的守门人）。

    verify-subtitle-state.py

## 守的是哪一类问题

「SRT 文件在盘上」和「模型里记着它」是**两个不同的事实**，而界面读的是后者。
两者不一致时，会进入一个**两边都不管**的死角：

  · 「要不要去取字幕」的判据看的是**文件** → 文件在 → 「不缺」→ 没人处理它
  · 列表那一行看的是**模型** → 没有 → 一直显示「字幕加载中」

真机上的原话是：**「都显示转写未加载，但实际上我已经拿到字幕了」**
（播放页里中英文都在，列表写「字幕加载中」，点「加载字幕」也没反应）。

## 为什么用「模拟器」而不是只查源码

这个 bug 的形状是**状态机不收敛**，不是某一行长什么样。只 grep 源码的话，
下面这些改法都能骗过静态检查，而每一种都会让真机再次挂掉：

  · 把「模型没记」那一条从判据里删掉（判据仍然自洽，就是不看模型）
  · 把队列里那句「顺手对齐」删掉（下载路径完好，只是没人对齐）
  · 播放页取完之后不对齐（照旧 —— 这就是当初的 bug）
  · 把「服务器换过一版」的判据挪到两个早退**后面**（写法还在，永远读不到）
  · 对齐时**无脑**把版本盖章成最新（「下载失败」会被当成「已是最新」）

所以这里用一个十来行的状态机把这五条都走一遍，并**逐条配一个坏实现** ——
坏实现必须在同一批用例上失败。这是本工程对「关键判据」的固定要求。
"""

import sys
from pathlib import Path

PASS = FAIL = 0


# ---------------------------------------------------------------------------
# 源码读取（与其它 verify-*.py 同一套写法）
# ---------------------------------------------------------------------------
JAVA = Path(__file__).resolve().parent.parent / "app/src/main/java/com/example/englishcommute"


def read(rel: str) -> str:
    p = JAVA / rel
    return p.read_text(encoding="utf-8") if p.is_file() else ""


def strip_comments(src: str) -> str:
    """去掉注释与字符串字面量，避免断言被注释里的示例代码骗过。"""
    out, i, n = [], 0, len(src)
    while i < n:
        if src.startswith("//", i):
            j = src.find("\n", i)
            i = n if j < 0 else j
        elif src.startswith("/*", i):
            j = src.find("*/", i)
            j = n if j < 0 else j + 2
            out.append("\n" * src.count("\n", i, j))
            i = j
        elif src[i] == '"':
            j = i + 1
            while j < n and src[j] != '"':
                if src[j] == "\\":
                    j += 1
                j += 1
            out.append('\"\"')
            i = j + 1
        else:
            out.append(src[i])
            i += 1
    return "".join(out)


def check(name: str, ok: bool, why: str = "") -> None:
    global PASS, FAIL
    if ok:
        PASS += 1
        print(f"  ✅ {name}")
    else:
        FAIL += 1
        print(f"  ❌ {name}")
        if why:
            print(f"       {why}")


# ===========================================================================
# Ⅰ. 状态机（对应 Kotlin 侧的四个事实）
# ===========================================================================
#
#   磁盘：hasSrt / hasZh              —— 文件系统（真相）
#   模型：srtFileName                 —— 界面读的那个字段
#   版本：remoteSrtVer / srtVerOnDisk —— 「本地这份对应服务器哪一版」
#   服务器：canFetch                  —— 这一期能不能取（remoteSrt 在不在）


def ep(has_srt=False, has_zh=False, model=None, remote_ver=0, disk_ver=0,
       can_fetch=True, zh_offered=True):
    return {
        "hasSrt": has_srt, "hasZh": has_zh, "srtFileName": model,
        "remoteSrtVer": remote_ver, "srtVerOnDisk": disk_ver,
        "canFetch": can_fetch, "zhOffered": zh_offered,
    }


def needs(e, *, model_matters=True):
    """对应 `ContentRepository.needsSubtitles`

    `model_matters=False` 是**坏实现**：不看「模型有没有记下来」。
    """
    if e["remoteSrtVer"] > e["srtVerOnDisk"]:
        return True
    if not e["hasSrt"]:
        return True
    if model_matters and e["srtFileName"] is None:
        return True
    return bool(e["zhOffered"]) and not e["hasZh"]


def player_fetch(e, *, align=True):
    """用户在播放页点开 → 当场取一次字幕（2.2.0 加的路径）

    `align=False` 是**坏实现**：只写文件、不更新模型（这正是当初的 bug）。
    """
    if not e["canFetch"]:
        return e
    e = {**e, "hasSrt": True}
    if e["zhOffered"]:
        e["hasZh"] = True
    if align:
        # 取成功了 ⇒ 盘上这份**就是**服务器当前那一版，可以盖章
        e["srtFileName"] = "x.srt"
        e["srtVerOnDisk"] = e["remoteSrtVer"]
    return e


def sync_round(e, *, align=True, stamp_at_entry=False, fail_fetch=False,
               model_matters=True):
    """同步引擎处理一轮。返回 (新状态, 这一轮做了什么)

    `align=False`       —— 坏实现：队列那句「顺手对齐」被删掉
    `stamp_at_entry=True`—— 坏实现：**进门先盖章**（不先判断这次要不要重下）。
                           这是最容易顺手写成的一版：「先把模型对齐了再说」——
                           而它在「服务器换了新版、这次下载又失败」时，
                           会把本地旧文件盖章成最新，于是**永远**不再重取。
    `fail_fetch=True`   —— 模拟「这一轮下载失败」
    """
    if stamp_at_entry and e["hasSrt"]:
        e = {**e, "srtFileName": "x.srt", "srtVerOnDisk": e["remoteSrtVer"]}
    if not needs(e, model_matters=model_matters):
        return e, "noop"

    stale = e["remoteSrtVer"] > e["srtVerOnDisk"]
    if e["hasSrt"] and not stale and e["hasZh"]:
        # 「都齐了、且不是过期版本」→ **不下载**，但必须把模型对齐
        if align and e["srtFileName"] is None:
            return {**e, "srtFileName": "x.srt", "srtVerOnDisk": e["srtVerOnDisk"]}, "align"
        return e, "noop"
    # 需要（重）下载
    if not e["canFetch"] or fail_fetch:
        return e, "fail"
    return {**e, "hasSrt": True,
            "hasZh": e["hasZh"] or e["zhOffered"],
            "srtFileName": "x.srt",
            "srtVerOnDisk": e["remoteSrtVer"]}, "fetch"


def settle(e, *, rounds=4, **kw):
    """反复跑同步，直到不需要再处理（或跑满 rounds）"""
    seen = []
    for _ in range(rounds):
        if not needs(e):
            break
        e, what = sync_round(e, **kw)
        seen.append(what)
    return e, seen


def ok_state(e):
    """收敛目标：**模型与磁盘一致**，且没有待办。

    ⚠️ 只看 `needs == False` 是不够的 —— 有一种坏实现能让 needs 立刻为假，
       而模型永远是 None（界面永远写「字幕加载中」）。那正是真机那个 bug。
    """
    if e["hasSrt"] and e["srtFileName"] is None:
        return False, "文件在盘上，模型却没记（界面会一直写「字幕加载中」）"
    if not e["hasSrt"] and e["srtFileName"] is not None:
        return False, "文件不在，模型却记着（界面会说有字幕）"
    if needs(e):
        return False, "还有待办（会每轮重复处理）"
    return True, ""


# ===========================================================================
# Ⅱ. 用例
# ===========================================================================

def run_cases(*, model_matters=True, align=True, stamp_at_entry=False):
    """在给定的（可能是坏的）实现下跑全部用例，返回失败的用例名"""
    bad = []

    def case(name, start, **kw):
        e, _ = settle(start, model_matters=model_matters, align=align,
                      stamp_at_entry=stamp_at_entry, **kw)
        good, why = ok_state(e)
        if not good:
            bad.append(f"{name} —— {why}")
        return e

    # A. 真机那一例：播放页当场取成功，但**没更新模型**（2.2.0 的 bug）
    case("A 播放页取过字幕、模型没记", ep(has_srt=True, has_zh=True, model=None,
                                          remote_ver=1, disk_ver=0))
    # B. 播放页取过且盖了章（模型仍然没记）—— 只剩「模型没记」这一条判据能救它
    case("B 播放页取过且盖章、模型仍没记", ep(has_srt=True, has_zh=True, model=None,
                                              remote_ver=1, disk_ver=1))
    # C. 服务器换过一版、这一轮**下载失败** → 必须保持「待办」，不许盖章装作最新。
    #
    #    ⚠️ 它**不是**收敛用例：失败时正确的终态就是「还没齐、下一轮再来」。
    #       第一版把它塞进了收敛检查里，于是正确实现反而被判失败 —— 测试自己的判据错了。
    start = ep(has_srt=True, has_zh=True, model="x.srt", remote_ver=2, disk_ver=1)
    e, _ = settle(start, model_matters=model_matters, align=align,
                  stamp_at_entry=stamp_at_entry, fail_fetch=True, rounds=3)
    if e["srtVerOnDisk"] != 1:
        bad.append("C —— 下载失败却把版本盖章成最新，这条**永远**不会再被重取")
    if not needs(e, model_matters=model_matters):
        bad.append("C —— 下载失败了却不再有待办（等于放弃这条字幕）")

    # D. 服务器换过一版、下载成功 → 收敛
    case("D 服务器换版 + 下载成功 → 收敛",
         ep(has_srt=True, has_zh=True, model="x.srt", remote_ver=2, disk_ver=1))
    # E. 全新一条（服务器有，本地什么都没有）
    case("E 全新一条：取回来就齐了", ep(remote_ver=1))
    # F. 只有英文、中文还没生成 → 不算待办（否则计数永不归零）
    case("F 服务器还没出中文 → 停在「英文可用」",
         ep(has_srt=True, has_zh=False, model="x.srt", remote_ver=1, disk_ver=1,
            zh_offered=False))
    # G. 服务器还没处理这一期（取不到）→ 不算能做成，但也不该崩
    e, _ = settle(ep(remote_ver=0, disk_ver=0, can_fetch=False), rounds=3)
    # H. 播放页在「取不到」时不该盖任何章
    e = player_fetch(ep(remote_ver=0, disk_ver=0, can_fetch=False))
    if e["srtFileName"] is not None:
        bad.append("H —— 取不到却记成了有字幕")
    return bad


def main():
    print("字幕状态收敛性 —— 正确实现必须让四个用例都收敛，坏实现必须失败\n")

    print("【正确实现】")
    bad = run_cases()
    check("全部用例收敛（模型与磁盘一致、无待办）", not bad,
          "；".join(bad))

    print("\n【坏实现逐条对照】（每条都必须让用例失败）")

    bad_a = run_cases(align=False)
    check("坏实现①：处理完不对齐模型（播放页那条路的老样子）", bool(bad_a),
          "删掉「顺手对齐」之后应当有用例失败")

    bad_b = run_cases(model_matters=False)
    check("坏实现②：判据不看「模型有没有记下来」", bool(bad_b),
          "少这一条 → 队列永远不认这种条目 → 界面永远写「字幕加载中」")

    bad_c = run_cases(stamp_at_entry=True)
    check("坏实现③：进门先盖章（不先判断这次要不要重下）", bool(bad_c),
          "「服务器换了新版 + 这次下载失败」会被盖章成「已是最新」，从此不再重取")

    # ── Ⅲ. 源码契约：模拟器只是**模型**，必须核对真代码与它一致 ──────────
    #
    # ⚠️ 少了这一节，上面那套模拟器就是空的：Kotlin 改了而模拟器没改，
    #    用例照样全绿，而真机上该挂还是挂。
    print("\n【源码契约：真代码必须与上面的模型一致】")
    repo = strip_comments(read("content/ContentRepository.kt"))
    sync = strip_comments(read("sync/SyncEngine.kt"))
    player = strip_comments(read("ui/PlayerViewModel.kt"))

    check("① 有一条**唯一**的「把模型对齐到磁盘」的实现",
          "fun syncSubtitleState(" in repo
          and repo.count("fun syncSubtitleState(") == 1,
          "两条路径各写一份 = 一定有一条被漏掉（这就是当初的 bug）")

    check("  · 它带 `markAsCurrentServerVersion` 开关（不许无脑盖章）",
          "markAsCurrentServerVersion: Boolean = false" in repo,
          "无脑盖章 ⇒「服务器换了新版但这次下载失败」会被当成已是最新，永不重取")

    check("② 判据认「模型没记下来」这一格",
          "if (episode.srtFileName == null) return true" in repo,
          "少这一条 → 队列永远不认这种条目 → 界面永远写「字幕加载中」")

    check("  · 且**没有**把 segmentCount 也写进判据（会让计数永不归零）",
          "episode.segmentCount <= 0) return true" not in repo)

    check("③ 同步引擎在「都齐了」那一支**先对齐再返回**",
          "alignSubtitleModel(episode)" in sync
          and "if (hadSrt && !srtStale && store.hasTranslation(episode)) {" in sync,
          "直接 return = 模型永远补不上（真机那个 bug）")

    check("④ 播放页当场取完之后也对齐",
          "syncSubtitleState(" in player
          and "markAsCurrentServerVersion = fetched != null" in player,
          "只写文件不更新模型 = 列表与播放页长期不一致")

    check("  · 且只在**取到了**才盖章",
          "markAsCurrentServerVersion = fetched != null" in player)

    # ★ 「服务器换过一版」那条判据必须排在两个早退**前面**。
    #   排在后面写法还在、却永远读不到 —— 这是本工程反复踩的形状
    #   （「判据写在后面 = 判据不存在」）。
    i_stale = sync.find("val srtStale =")
    i_first_ret = sync.find("if (hadSrt && !srtStale && episode.remoteZh == null")
    i_second_ret = sync.find("if (hadSrt && !srtStale && store.hasTranslation(episode))")
    check("⑤ 版本判据排在两个「已经有了就跳过」的分支**前面**",
          0 < i_stale < i_first_ret > 0 and i_second_ret > i_first_ret,
          "写在后面 = 被 hadSrt && hasTranslation 先返回吞掉，"
          "「服务端重切字幕」对老用户等于没发生过")

    print()
    print("=" * 66)
    print(f"通过 {PASS} 项，失败 {FAIL} 项")
    return 0 if FAIL == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
