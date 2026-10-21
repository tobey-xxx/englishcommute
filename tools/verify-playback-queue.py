#!/usr/bin/env python3
"""
复刻**播放队列的排序规则**（`player/PlaybackQueue.kt`）。

## 为什么必须验它

这条需求的原文是：「自动播放下一条尚未听完的」。它错了**不会崩、不会报错、
不会有任何异常日志** —— 只会在你把手机揣兜里的时候，默默地又放一遍昨天听过的那几条。
真机上要发现它，得先攒够「已听完的」和「未听完的」混在一起，再听上二十分钟。

而它的判据有且只有一条**二值**的输入：`PlaybackPositions.done`。
把判据换成「听到哪」（断点位置），症状会变成另一种同样说不清的样子：
**听了 10 秒就退出的内容被当成「听过」排到后面** ——
而那恰恰是最该优先补上的一条。

桌面复刻这两种错误形态的成本是几毫秒。

## 复刻要求

**逐行对齐** Kotlin，不能「意思一下重写」。两处语义必须显式对齐：

  · `partition {}` 保持两组内部的**原相对顺序**
  · `sortedByDescending` 在 Kotlin 里是**稳定排序**（下层是 TimSort），
    所以 `publishedAt` 并列时，先后由**输入顺序**决定 —— Python 的
    `sorted(key=..., reverse=True)` 同样稳定，可以直接对齐。
    ⚠️ 但这条只对「按 key 排序」成立；若哪天改成 `sorted(reverse=True)` 同时
    按元组排，稳定性就换成了元组内部比较，那时必须重验。

源：`player/PlaybackQueue.kt`、`ui/PlayerViewModel.kt`

用法：
    python3 tools/verify-playback-queue.py
"""

import re
import sys
from dataclasses import dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
KT = ROOT / "app/src/main/java/com/example/englishcommute"


# ---------------------------------------------------------------------------
# 被测逻辑的 Python 复刻
# ---------------------------------------------------------------------------

@dataclass(frozen=True)
class Episode:
    id: str
    published_at: int


def order(playable: list[Episode], done_ids: set[str]) -> list[Episode]:
    """逐行对齐 PlaybackQueue.order"""
    if not playable:
        return playable

    unfinished = [e for e in playable if e.id not in done_ids]
    finished = [e for e in playable if e.id in done_ids]

    if not finished:                                    # finished.isEmpty()
        return sorted(unfinished, key=lambda e: e.published_at, reverse=True)
    if not unfinished:                                  # unfinished.isEmpty()
        return sorted(finished, key=lambda e: e.published_at, reverse=True)

    return (sorted(unfinished, key=lambda e: e.published_at, reverse=True) +
            sorted(finished, key=lambda e: e.published_at, reverse=True))


# 负向对照：两个「看起来也对」的错误实现。它们必须与正解产生不同结果，
# 否则说明我的测试数据根本没有区分力，前面那些 ✓ 全是假的。
def order_time_only(playable, done_ids):
    """错误实现 A：只按时间倒序 —— 就是改之前的行为"""
    return sorted(playable, key=lambda e: e.published_at, reverse=True)


def order_done_by_progress(playable, done_ids, progressed_ids):
    """
    错误实现 B：把「听过一点点」也算成已听完。
    这对应于拿断点位置（positions）当判据，而不是 done 标记。
    """
    return order(playable, done_ids | progressed_ids)


# ---------------------------------------------------------------------------
# 断言框架（与其它 verify-*.py 一致）
# ---------------------------------------------------------------------------

PASS = 0
FAIL = 0
FAILURES = []


def check(name: str, got, want):
    global PASS, FAIL
    if got == want:
        PASS += 1
    else:
        FAIL += 1
        FAILURES.append(f"{name}\n      期望 {want!r}\n      实际 {got!r}")


def check_true(name: str, cond, detail=""):
    check(name, bool(cond), True)
    if not cond and detail:
        FAILURES.append(f"      ↳ {detail}")


def section(title: str):
    print(f"\n\033[1m{title}\033[0m")


def ids(eps: list[Episode]) -> list[str]:
    return [e.id for e in eps]


# ---------------------------------------------------------------------------
# 数据：刻意用「时间倒序」当输入，好让「顺序没变」这件事一眼可见
# ---------------------------------------------------------------------------

def make(n: int) -> list[Episode]:
    """第 0 条最新（publishedAt 最大），第 n-1 条最旧 —— 和真实仓库一致"""
    base = 1_700_000_000_000
    return [Episode(f"ep{i:02d}", base - i * 3_600_000) for i in range(n)]


E = make(6)          # ep00 最新 … ep05 最旧
NEWEST, OLDEST = E[0].id, E[-1].id


# ---------------------------------------------------------------------------
# ① 基本形状
# ---------------------------------------------------------------------------

section("① order —— 形状与长度")

check("空列表 → 空列表", ids(order([], set())), [])
check("单条 · 未听完 → 原样", ids(order([E[0]], set())), [NEWEST])
check("单条 · 已听完 → 原样（不移出队列）", ids(order([E[0]], {NEWEST})), [NEWEST])

for done in (set(), {NEWEST}, {NEWEST, OLDEST}, {e.id for e in E}):
    got = order(E, done)
    check_true(f"输出长度 = 输入长度（done={len(done)} 条）", len(got) == len(E),
               f"{len(got)} vs {len(E)}")
    check_true(f"输出是输入的一个排列（不丢不重，done={len(done)} 条）",
               sorted(ids(got)) == sorted(ids(E)), str(ids(got)))

check("done 里含不在库里的 id → 无影响", ids(order(E, {"ghost"})), ids(E))


# ---------------------------------------------------------------------------
# ② 分组：未听完在前 —— 这就是「自动播下一集」的全部内容
# ---------------------------------------------------------------------------

section("② 未听完优先")

# 已听完三条最**新**的。若按时间排，新的一定在前；
# 正确行为应该反过来：把新的那三条推到后面
done = {E[0].id, E[1].id, E[2].id}
got = ids(order(E, done))
check("未听完的三条排在前面", got, ["ep03", "ep04", "ep05", "ep00", "ep01", "ep02"])
check_true("第一条不是已听完的", got[0] not in done, got[0])
check_true("分界点之后全是已听完的",
           all(i in done for i in got[3:]), str(got))

# 反过来：最旧的三条已听完
done = {E[3].id, E[4].id, E[5].id}
got = ids(order(E, done))
check("未听完的最新三条在前，已听完的在后的块内仍按时间倒序",
      got, ["ep00", "ep01", "ep02", "ep03", "ep04", "ep05"])

# 交错
done = {E[1].id, E[3].id, E[5].id}
got = ids(order(E, done))
check("交错时两组各自保持时间倒序",
      got, ["ep00", "ep02", "ep04", "ep01", "ep03", "ep05"])


section("③ 两个极端（也是最常见的两种）")

check("全都没听过 → 纯时间倒序", ids(order(E, set())), ids(E))
check("全都听过 → 纯时间倒序",
      ids(order(E, {e.id for e in E})), ids(E))
check_true("全都听过时**不返回空**（否则上一集/下一集在边界突然失效）",
           len(order(E, {e.id for e in E})) == len(E))


# ---------------------------------------------------------------------------
# ④ 「下一条」到底是谁 —— 这才是用户能感知到的东西
# ---------------------------------------------------------------------------

section("④ 「下一条」是谁")

def next_of(seq: list[Episode], current: str):
    """ExoPlayer 自动推进的下一目标：队列里的后一条"""
    i = ids(seq).index(current)
    if i + 1 >= len(seq):
        return None
    return seq[i + 1].id


seq = order(E, {E[0].id, E[1].id})       # 最新两条已听完
check("从 ep02 出发，下一条是 ep03（未听完），不是 ep00（已听完）",
      next_of(seq, "ep02"), "ep03")
check("把未听完的都放完，才会接到已听完的块",
      next_of(seq, "ep05"), "ep00")
check("队尾没有下一条（返回 None，交由 ExoPlayer 停住）",
      next_of(seq, "ep01"), None)

# 边界：只剩一条未听完，且它排在最后
seq = order(E, {E[i].id for i in range(5)})
check("只有一条未听完时，它被顶到队首", ids(seq)[0], "ep05")
check("它的下一条是已听完的那一批里最新的", next_of(seq, "ep05"), "ep00")


# ---------------------------------------------------------------------------
# ⑤ 稳定性：时间并列时不能乱序
# ---------------------------------------------------------------------------

section("⑤ 时间并列（稳定排序）")

T = [Episode("a", 100), Episode("b", 100), Episode("c", 100), Episode("d", 50)]
check("三条并列 · 全未听完 → 保持输入顺序", ids(order(T, set())), ["a", "b", "c", "d"])
# ⚠️ 这里我第一版把期望写成了 ['a','c','b','d'] —— 错的。
#    分界是「先分组、再各自排序」，d 的 publishedAt 是 50，比 a/c 都旧，
#    所以它在未听完组内排最后，b 才落到队尾。**跑出来不对先怀疑自己算错了。**
check("三条并列 · 其中一条已听完 → 未听完组内仍保持输入顺序",
      ids(order(T, {"b"})), ["a", "c", "d", "b"])
check("并列组跨过分界线时，分界只切在做完分组之后",
      ids(order(T, {"a", "c"})), ["b", "d", "a", "c"])


# ---------------------------------------------------------------------------
# ⑥ 负向对照：错误实现必须被判失败
# ---------------------------------------------------------------------------
#
# 这一段的存在意义：证明上面那些 ✓ **有区分力**。如果错误实现也能通过，
# 说明测试数据选得不对，前面全是在自证。

section("⑥ 负向对照（坏实现必须与正解不同）")

MIXED_DONE = {E[0].id, E[1].id, E[2].id}
good = ids(order(E, MIXED_DONE))

check_true("错误实现 A（只按时间）与正解不同",
           ids(order_time_only(E, MIXED_DONE)) != good,
           str(ids(order_time_only(E, MIXED_DONE))))
check("  · 它会把已听完的最新一条排在最前（正是用户抱怨的那个现象）",
      ids(order_time_only(E, MIXED_DONE))[0], "ep00")

# 错误实现 B：听了 10 秒就退出的内容被当成「听过」
#
# 这里 done = 真的听完了 ep00；ep03 只听了 10 秒（有断点、没听完）。
# 正解里 ep03 必须留在**未听完那一组**（它是最该优先补上的一条）；
# 错误实现会把它和 ep00 一起丢进已听完组，排到整条队列的后半段。
progressed = {"ep03"}
good_b = ids(order(E, {"ep00"}))
bad_b = ids(order_done_by_progress(E, {"ep00"}, progressed))
check("正解：最新那条（ep01）在最前 —— 未听完组内仍按时间倒序", good_b[0], "ep01")
check_true("正解：听了 10 秒的 ep03 落在未听完组里（排在已听完的 ep00 **之前**）",
           good_b.index("ep03") < good_b.index("ep00"), str(good_b))
check_true("错误实现 B（拿断点当判据）与正解不同", bad_b != good_b, str(bad_b))
check_true("  · 它会把听了 10 秒的那条推到已听完那一组里去",
           bad_b.index("ep03") > good_b.index("ep03"),
           f"正解第 {good_b.index('ep03')} 位 vs 坏实现第 {bad_b.index('ep03')} 位")


# ---------------------------------------------------------------------------
# ⑦ 源码断言：复刻的东西和发货的东西是同一份判据
# ---------------------------------------------------------------------------

section("⑦ 源码契约")


def kt_read(rel: str) -> str:
    return (KT / rel).read_text(encoding="utf-8")


def strip_comments(src: str) -> str:
    """去掉 // 与 /* */。注释里提到某个词不该让断言失败（本项目踩过）"""
    src = re.sub(r"/\*.*?\*/", "", src, flags=re.S)
    src = re.sub(r"//[^\n]*", "", src)
    return src


pq = kt_read("player/PlaybackQueue.kt")
pq_code = strip_comments(pq)

check_true("PlaybackQueue.kt 存在且是 object",
           "object PlaybackQueue {" in pq_code)
check_true("签名是 order(playable, doneIds)",
           "fun order(playable: List<Episode>, doneIds: Set<String>): List<Episode>" in pq_code)
check_true("判据用的是 doneIds（二值标记），而不是断点位置",
           "it.id !in doneIds" in pq_code)
check_true("没有读任何「听到哪」的字段",
           "positions" not in pq_code and "listenedMs" not in pq_code)
check_true("先按「听没听过」分组，再在组内按时间倒序",
           "partition {" in pq_code and "sortedByDescending { it.publishedAt }" in pq_code,
           "分组与排序缺一不可")
check_true("已听完的只往后排，没有被 filter 掉",
           "finished.sortedByDescending" in pq_code
           and "return unfinished.sortedByDescending { it.publishedAt }" in pq_code)
check_true("纯函数：不 import android",
           "import android" not in pq_code)

# 排序逻辑不许有第二份实现
pv = kt_read("ui/PlayerViewModel.kt")
pv_code = strip_comments(pv)
check("分区逻辑只在 PlaybackQueue.kt 里出现一次（全工程）",
      sum(kt_read(p).count("partition { it.id !in")
          for p in ("player/PlaybackQueue.kt", "ui/PlayerViewModel.kt")), 1)
check_true("PlayerViewModel 调用的是 PlaybackQueue.order",
           "PlaybackQueue.order(" in pv_code)
check_true("传进去的是 done 集合，不是 positions",
           "container.playbackPositions.done.value" in pv_code
           and "PlaybackQueue.order(playable, done)" in pv_code)

# 「只在打开某一期时排一次」：buildPlayableQueue 只有一个调用点，
# 且它必须在 IO 调度器上（读文件系统）
#
# ⚠️ 2.2.0：它多了一个参数（`allowStreamId` —— 边下边播要放行「正在打开的那一条」），
#    所以匹配串不能再写成 `buildPlayableQueue()`。这里改成**按括号匹配**，
#    并对参数名免疫：定义 1 处 + 调用 1 处 = 一共出现 2 次。
i_def = pv_code.find("private fun buildPlayableQueue(")
uses = [m.start() for m in re.finditer(r"buildPlayableQueue\(", pv_code)]
calls = [x for x in uses if x != i_def + len("private fun ")]
check("buildPlayableQueue 只有一个调用点（定义 1 + 调用 1）", len(calls), 1)
if calls:
    seg = pv_code[max(0, calls[0] - 120):calls[0]]
    check_true("  · 且在 withContext(Dispatchers.IO) 里（要读文件系统）",
               "withContext(Dispatchers.IO)" in seg, seg[-120:])
    # ★ 2.2.0：传进去的必须是**正在打开的那一条** —— 少了它，「本地没音频」的条目
    #    会被队列直接过滤掉，用户点开时命中的是 applyEpisode 里那条
    #    「这条内容暂时打不开」的兜底错误，而它其实完全能边下边播。
    check_true("  · 且把「正在打开的这一条」放行（边下边播的入口）",
               "buildPlayableQueue(episodeId)" in pv_code)

# 不许在播放器回调里写「跳过已听完」的补丁 —— 队列顺序就是全部实现
i_cb = pv_code.find("onMediaItemTransition")
if i_cb > 0:
    seg = pv_code[i_cb:i_cb + 600]
    check_true("onMediaItemTransition 里没有「跳过已听完」的补丁",
               "done" not in seg and "PlaybackQueue" not in seg,
               "排序应当是排一次队列就够了，回调里再补一次等于两个真相源")

# 连播队列那一行日志：锚点必须落在完整字面量里（0.14.0 的教训）
check_true("队列构成写进了日志（真机上唯一的自证手段）",
           '"连播队列：未听完 ' in pv,
           "锚点必须取完整字面量或其固定前缀，不能用插值当锚点")


# ---------------------------------------------------------------------------

section("汇总")
print(f"通过 {PASS} 项，失败 {FAIL} 项")
if FAIL:
    print("\n❌ 有断言未通过：")
    for f in FAILURES:
        print("  - " + f)
    sys.exit(1)
print("✅ 全部通过")
