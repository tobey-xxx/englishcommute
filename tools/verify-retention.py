#!/usr/bin/env python3
"""
复刻 `ContentPolicy`（**懒加载预取** + 保留期）的**全部判据**，在桌面跑边界。

## 为什么必须验它

这一组规则的错误**一个都不报**：

  · 预取范围太宽   → 为**不会被听的 90%** 付流量（2.1.9 起音频走自己的服务器，
                     每一次下载都从 512 GB/月的流量包里出）；用户只觉得「更新很慢」
  · 预取范围太窄   → 打开 App 没有可听的，退化成「点了才下」
  · 保留期判错方向 → **删掉用户还想听的东西**（不可逆），或者一条不删、缓存涨到几个 G
  · 每源保底漏掉   → 周更源永远没有内容，看起来像「这个源坏了」
  · downloadedAt 迁移漏掉 → 保留期永远算不出来，「三天后自动删除」成为空话
  · 合并 refreshFeeds 时丢了 downloadedAt → 同上，而且是每轮刷新都丢一次

真机上要发现其中任何一条，都得先等三天、再翻索引 JSON、再对着文件系统数文件。
桌面复刻的成本是几分钟。

⚠️ 复刻要求**逐行对齐** Kotlin 实现，不能「意思一下重写」。
   源：`content/ContentPolicy.kt`、`content/ContentRepository.kt`、`content/Episode.kt`

用法：
    python3 tools/verify-retention.py
"""

import re
import sys
from dataclasses import dataclass, field
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
KT = ROOT / "app/src/main/java/com/example/englishcommute"

# ---------------------------------------------------------------------------
# 常量（与 Kotlin 侧对齐，见文件末尾的源码断言）
# ---------------------------------------------------------------------------

DAY_MS = 86_400_000
RETAIN_DAYS = 3
# ⚠️ 2.1.9：下载策略改成懒加载后，这个窗口**不再参与下载**（只留作历史语义，
#    见 ContentRepository 的同名常量注释）。它仍在 §⑥ 里被引用，但那里的
#    不变量已经**换了一条** —— 现在的配对是「本地保留期 < 服务端音频保留期」。
DOWNLOAD_WINDOW_DAYS = 2
PREFETCH_COUNT = 3
# 服务端 `cycle.py` 的 RETENTION_DAYS（音频保留期）。两者是**跨仓库的一对**，
# 由 §⑥ 断言。见那里的说明。
SERVER_AUDIO_RETAIN_DAYS = 7

NOW = 1_800_000_000_000  # 固定「现在」，避免用真实时钟导致结果不可复现


# ---------------------------------------------------------------------------
# 复刻：数据模型
# ---------------------------------------------------------------------------

@dataclass
class Episode:
    """对应 Kotlin `Episode`，只保留本脚本用得到的字段"""
    id: str
    source_id: str
    published_at: int = 0
    downloaded_at: int = 0
    busy: bool = False  # 对应 status.isBusy


@dataclass
class PrefetchSelection:
    """对应 Kotlin `ContentPolicy.PrefetchSelection`（2.1.9 从 WindowSelection 改名）。

    ⚠️ 改名的理由不是审美：旧名里的「window」指的是「按发布时间开一个 2 天的窗口、
    窗口内全收」，而 2.1.9 之后根本没有窗口了 —— 留着一个说谎的名字，
    下一个人会照它去理解策略，那就又回到「两件事共用一个名字」那类坑里。
    """
    selected: list = field(default_factory=list)
    skipped: list = field(default_factory=list)


# ---------------------------------------------------------------------------
# 复刻：content/ContentPolicy.kt
# ---------------------------------------------------------------------------

LONG_MAX = 2 ** 63 - 1


def kdiv(a: int, b: int) -> int:
    """**Kotlin 整数除法的复刻** —— 朝零取整。

    Python 的 `//` 是向下取整，负数上两者差 1：
        kdiv(-36_000_000, 86_400_000) = 0     （Kotlin 行为）
        -36_000_000 // 86_400_000     = -1    （Python 默认行为）

    这个差在 `ageDays` 上会被放大成「时间戳在未来时倒计时多显示一天」。
    本工程的第一条纪律就是 Python 复刻必须逐行对齐，所以显式写出来。
    """
    q = abs(a) // abs(b)
    return q if (a < 0) == (b < 0) else -q


def age_days(now: int, then: int) -> int:
    """`ageDays` —— 用整除而不是浮点：边界上不能有歧义。

    71 小时 = 2 天（留），72 小时整 = 3 天（删）。"""
    if then <= 0:
        return LONG_MAX
    return kdiv(now - then, DAY_MS)


def should_expire(downloaded_at: int, now: int, retain_days: int = RETAIN_DAYS) -> bool:
    """`shouldExpire` —— 只有真正落过盘的文件才有「保留期」这个概念"""
    return downloaded_at > 0 and age_days(now, downloaded_at) >= retain_days


def days_left(downloaded_at: int, now: int, retain_days: int = RETAIN_DAYS):
    """`daysLeft` —— None = 没有音频不给倒计时；0 = 今天到期"""
    if downloaded_at <= 0:
        return None
    return max(0, retain_days - age_days(now, downloaded_at))


def should_purge(published_at: int, has_audio: bool, now: int) -> bool:
    """`shouldPurge` —— 只对**盘上没有音频**的条目成立"""
    return (not has_audio) and published_at > 0 and age_days(now, published_at) >= RETAIN_DAYS


def select_prefetch(candidates, now, favorite_ids=(), playing_id=None,
                    done_ids=(), prefetch_count=PREFETCH_COUNT) -> PrefetchSelection:
    """`selectPrefetch` —— **懒加载策略**（2.1.9）。四条规则的并集：

        ① 接下来 N 条（按 publishedAt 倒序，跳过已听完的）
        ② 收藏  ③ 正在播  ④ 每源保底最新一条

    candidates 必须已按 publishedAt 倒序（① 与 ④ 都依赖它）。
    与旧 `selectByWindow` 的区别见本脚本 §⑤ 的说明。
    """
    if not candidates:
        return PrefetchSelection([], [])

    picked = []            # 保序去重，对应 Kotlin 的 LinkedHashSet
    picked_ids = set()

    def take(ep):
        if ep.id not in picked_ids:
            picked_ids.add(ep.id)
            picked.append(ep)

    # ① 接下来 N 条
    taken = 0
    for ep in candidates:
        if taken >= prefetch_count:
            break
        if ep.id in done_ids:
            continue
        take(ep)
        taken += 1

    # ② 收藏 / ③ 正在播
    for ep in candidates:
        if ep.id in favorite_ids or ep.id == playing_id:
            take(ep)

    # ④ 每源保底最新一条（倒序 → 每源第一次出现的就是它最新的）
    seen = set()
    for ep in candidates:
        if ep.source_id in seen:
            continue
        seen.add(ep.source_id)
        take(ep)

    skipped = [ep for ep in candidates if ep.id not in picked_ids]
    return PrefetchSelection(picked, skipped)


def select_expired(episodes, now, has_audio, keep_ids) -> list:
    """`selectExpired` —— 删文件之前的**纯函数**判定，四道豁免缺一不可"""
    return [
        ep for ep in episodes
        if ep.id not in keep_ids
        and not ep.busy
        and has_audio(ep)
        and should_expire(ep.downloaded_at, now)
    ]


# ---------------------------------------------------------------------------
# 复刻：ContentRepository 的三个调用点
# ---------------------------------------------------------------------------

def migrate_retention(episodes, has_audio, now) -> tuple:
    """`migrateRetention` —— 给「有音频但 downloadedAt <= 0」的补 now。

    0.11.0 及更早没有这个字段。替代信息都不可用：
      · 文件 mtime 被播放刷新过（AudioStore.touch）
      · publishedAt 会让刚下完的内容立刻进入倒计时
    补 now 的语义是「从这一版开始重新计算保留期」，代价只是老音频多留三天。
    删除不可逆，宁可迟删不可误删。"""
    patched = 0
    for ep in episodes:
        if has_audio(ep) and ep.downloaded_at <= 0:
            ep.downloaded_at = now
            patched += 1
    return episodes, patched


def refresh_merge(old, fresh, keep_downloaded_at=True):
    """`refreshFeeds` 的合并：保留旧条目的 downloadedAt。

    ⚠️ 漏掉这一行的后果是**静默的** —— 每次刷新 RSS 都把下载时间抹成 0，
    保留期永远算不出来，「三天后自动删除」成为空话且不报错。"""
    out = []
    for ep in fresh:
        if keep_downloaded_at and old is not None and old.id == ep.id:
            ep.downloaded_at = old.downloaded_at
        out.append(ep)
    return out


def run_pipeline_round(index, has_audio_map, now, favorite_ids=(), playing_id=None,
                       done_ids=(), prefetch_count=PREFETCH_COUNT, purge=True):
    """一轮「更新」：purgeStaleIndex → selectPrefetch。

    返回 (selected_ids, purged_ids)。这是 SyncEngine.runInternal 里的真实顺序。

    ⚠️ 顺序不能反：purge 在前。selectPrefetch 的**每源保底**会把某个源的
    最新一条无条件选中，而如果它先在、purge 在后，那条刚被选中的内容
    可能立刻被当成「没音频且发布满 3 天」清掉 —— 下下来又删，来回抖。
    """
    purged = []
    if purge:
        kept = []
        for ep in index:
            if should_purge(ep.published_at, has_audio_map.get(ep.id, False), now):
                purged.append(ep.id)
            else:
                kept.append(ep)
        index[:] = kept

    candidates = [ep for ep in index if not has_audio_map.get(ep.id, False)]
    sel = select_prefetch(candidates, now, favorite_ids, playing_id, done_ids,
                          prefetch_count)
    return [ep.id for ep in sel.selected], purged


# ---------------------------------------------------------------------------
# 测试框架（与本工程其它 verify 脚本同款）
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


H = 3_600_000
M = 60_000


# ---------------------------------------------------------------------------
# ① ageDays
# ---------------------------------------------------------------------------

section("① ageDays —— 整除边界")

check("0 小时 → 0 天", age_days(NOW, NOW), 0)
check("23 小时 → 0 天", age_days(NOW, NOW - 23 * H), 0)
check("24 小时整 → 1 天", age_days(NOW, NOW - 24 * H), 1)
check("47 小时 → 1 天", age_days(NOW, NOW - 47 * H), 1)
check("71.99 小时 → 2 天", age_days(NOW, NOW - 71 * H - 3599 * 1000), 2)
check("72 小时整 → 3 天", age_days(NOW, NOW - 72 * H), 3)
check("72.5 小时 → 3 天", age_days(NOW, NOW - 72 * H - 30 * M), 3)
check("未来时刻（负数差）→ 0 天而不是负数",
      age_days(NOW, NOW + 10 * H), 0)
check("↳ 与 Kotlin 的 `/`（朝零取整）一致；用 Python 的 `//` 会给 -1",
      kdiv(-36_000_000, DAY_MS), 0)
check("↳ Python 默认行为确实是 -1（这就是必须显式复刻的原因）",
      -36_000_000 // DAY_MS, -1)
check("未来 10 小时的时间戳 → daysLeft 仍是 3（不会显示成 4）",
      days_left(NOW + 10 * H, NOW), 3)
check("then <= 0 → MAX（调用方必须先确认时间戳存在）", age_days(NOW, 0), LONG_MAX)
check("then = -1 → MAX", age_days(NOW, -1), LONG_MAX)


# ---------------------------------------------------------------------------
# ② shouldExpire
# ---------------------------------------------------------------------------

section("② shouldExpire —— 保留期判据")

check("刚下载 → 不删", should_expire(NOW, NOW), False)
check("2 天 23 小时 → 不删", should_expire(NOW - 71 * H, NOW), False)
check("**3 天整 → 删**（边界）", should_expire(NOW - 72 * H, NOW), True)
check("3 天 1 秒 → 删", should_expire(NOW - 72 * H - 1000, NOW), True)
check("downloadedAt = 0（没下过）→ 不删", should_expire(0, NOW), False)
check("downloadedAt = -1 → 不删", should_expire(-1, NOW), False)
check("→ 索引里所有「还没下载」的条目都不会被判过期",
      all(not should_expire(ep.downloaded_at, NOW)
          for ep in [Episode("a", "s1"), Episode("b", "s2")]), True)
check("retain_days 可调（改成 5 天后 3 天不删）",
      should_expire(NOW - 72 * H, NOW, 5), False)


# ---------------------------------------------------------------------------
# ③ daysLeft
# ---------------------------------------------------------------------------

section("③ daysLeft —— 界面倒计时")

check("没音频 → None（不给倒计时）", days_left(0, NOW), None)
check("刚下载 → 3", days_left(NOW, NOW), 3)
check("1 天前下载 → 2", days_left(NOW - 24 * H, NOW), 2)
check("2 天前下载 → 1", days_left(NOW - 48 * H, NOW), 1)
check("**3 天整 → 0（今天到期，不是「剩 0 天」）**", days_left(NOW - 72 * H, NOW), 0)
check("4 天前下载 → 0 而不是 -1（不许出现负数）",
      days_left(NOW - 96 * H, NOW), 0)


# ---------------------------------------------------------------------------
# ④ shouldPurge —— 过时效剔出索引
# ---------------------------------------------------------------------------

section("④ shouldPurge —— 与保留期分工")

check("发布 4 天前 + 有音频 → **不剔**（归保留期管）",
      should_purge(NOW - 96 * H, True, NOW), False)
check("发布 4 天前 + 无音频 → 剔", should_purge(NOW - 96 * H, False, NOW), True)
check("发布 3 天整 + 无音频 → 剔（边界）", should_purge(NOW - 72 * H, False, NOW), True)
check("发布 71 小时 + 无音频 → 不剔", should_purge(NOW - 71 * H, False, NOW), False)
check("publishedAt = 0（源站没给日期）→ 不剔", should_purge(0, False, NOW), False)
check("→ 昨天刚下载的内容不会因为「发布时间旧」整个消失",
      should_purge(NOW - 5 * 24 * H, True, NOW), False)


# ---------------------------------------------------------------------------
# ⑤ selectPrefetch
# ---------------------------------------------------------------------------

section("⑤ selectPrefetch —— 懒加载：只下会听的")

# 索引倒序：每个源的条目按 publishedAt 从新到旧排列
# ⚠️ 这个「已倒序」是**前提**，不是巧合：① 与 ④ 两条规则都依赖它
#    （见 ContentPolicy.selectPrefetch 的 @param）。
def mk_index(now=NOW):
    return [
        Episode("npr-today", "npr", now - 3 * H),           # 3 小时前
        Episode("npr-yesterday", "npr", now - 30 * H),      # 30 小时前
        Episode("cs-44h", "codeswitch", now - 44 * H),      # 周更源，44 小时前
        Episode("npr-2d", "npr", now - 50 * H),             # 50 小时前
        Episode("npr-5d", "npr", now - 120 * H),            # 5 天前
        Episode("cs-old", "codeswitch", now - 8 * 24 * H),  # 8 天前
    ]


# ① 接下来 N 条 + ④ 每源保底
sel = select_prefetch(mk_index(), NOW)
ids = [ep.id for ep in sel.selected]
check_true("「接下来 3 条」= 最新的 3 条（未听完的）",
           ids[:3] == ["npr-today", "npr-yesterday", "cs-44h"], f"selected={ids}")
check_true("每源保底把周更源的 cs-44h 也带上了（它排在第 3 位，本来就在里面）",
           "cs-44h" in ids, f"selected={ids}")
check("5 天前 / 8 天前的两条**不进预取**（旧策略会把 2 天内的全下）",
      [i for i in ids if i in ("npr-5d", "cs-old")], [])
check("skipped = 候选里没选中的",
      sorted(ep.id for ep in sel.skipped), ["cs-old", "npr-2d", "npr-5d"])

# ★★ 本节的**核心数字**：每小时一集的源，10 条里只下 3 条。
#    旧规则（窗口内全收）会把这 10 条全下 —— 那是每天约 320 MB 的来源。
hourly = [Episode("nn-%d" % i, "npr-news-now", NOW - i * H) for i in range(10)]
sel = select_prefetch(hourly, NOW)
check("★★ 每小时一集的源：10 条里只预取 3 条（旧窗口策略会全下）",
      len(sel.selected), PREFETCH_COUNT)
check_true("★★ 而这 3 条正是最新的 3 条",
           [ep.id for ep in sel.selected] == ["nn-0", "nn-1", "nn-2"],
           "同一个源不会因为「2 天内发布的都算新」而一次下 10 条")

# 已听完的不占「接下来 N 条」的位置 —— 窗口靠它往下滑。
# ⚠️ 但结果里仍然有 nn-0：它是**每源保底**捞回来的（该源最新一条），
#    而保底**故意不看「听没听完」**。理由见下面那条断言。
sel = select_prefetch(hourly, NOW, done_ids={"nn-0", "nn-1"})
check("已听完的不占「接下来 N 条」的位置（窗口往下滑）",
      [ep.id for ep in sel.selected][:3], ["nn-2", "nn-3", "nn-4"])
check_true("★每源保底**含已听完的**（nn-0 被捞回来）—— 这是有意的",
           "nn-0" in [ep.id for ep in sel.selected],
           "保底的头号职责不是「给用户听」，而是**别让这个源从索引里消失**："
           "purgeStaleIndex 会清掉「没有音频 且发布满 3 天」的条目，"
           "而周更源的最新一条正是这种。若保底也跳过已听完的，"
           "那么「我把某个源全听完了」就会导致那个源整个从列表里不见 —— "
           "用户的观感是「这个源坏了」。多下一次的代价有界（每源至多一条）。")

# 坏样本：拿掉「跳过已听完」这条，窗口就不会滑 —— 断言必须能分辨
def _bad_ignore_done(candidates, now, favorite_ids=(), playing_id=None,
                     done_ids=(), prefetch_count=PREFETCH_COUNT):
    picked, pids = [], set()
    taken = 0
    for ep in candidates:
        if taken >= prefetch_count:
            break
        if ep.id not in pids:
            pids.add(ep.id)
            picked.append(ep)
        taken += 1
    return PrefetchSelection(picked, [])


check_true("★坏样本：不跳过已听完 → 窗口卡死在最上面 3 条（推不出来）",
           [ep.id for ep in _bad_ignore_done(hourly, NOW, done_ids={"nn-0", "nn-1"}).selected]
           == ["nn-0", "nn-1", "nn-2"],
           "用户听完前两条之后，正确的实现会给 nn-2/nn-3/nn-4，"
           "而坏实现永远停在 nn-0/nn-1/nn-2 —— 于是 nn-3 永远下不下来")

# ② 收藏 / ③ 正在播：与排位无关，永远取
sel = select_prefetch(hourly, NOW, favorite_ids={"nn-9"})
check_true("② 收藏的即使排在最后也必下", "nn-9" in [ep.id for ep in sel.selected])
sel = select_prefetch(hourly, NOW, playing_id="nn-7")
check_true("③ 正在播的必下（换集时不能没有它）",
           "nn-7" in [ep.id for ep in sel.selected])

# ④ 每源保底：把 prefetch_count 压到 1，单独看保底在做什么
weekly = [
    Episode("npr-new", "npr", NOW - 2 * H),
    Episode("cs-52h", "codeswitch", NOW - 52 * H),   # 周更源，发布已 52 小时
    Episode("cs-old", "codeswitch", NOW - 9 * 24 * H),
]
sel = select_prefetch(weekly, NOW, prefetch_count=1)
ids = [ep.id for ep in sel.selected]
check_true("④ **每源保底：周更源的最新一条即使很旧也入选**", "cs-52h" in ids,
           f"selected={ids}")
check_true("保底**只取该源最新一条**，不把 9 天前的也捞进来",
           "cs-old" not in ids, f"selected={ids}")

# 源站没给日期（publishedAt = 0）→ 排序上落到最后。
# ⚠️ 旧规则有一条「`publishedAt <= 0` 一律放行」的兜底（宁可多下一次也不丢）。
#    懒加载之后这条兜底**没有必要了**，因为「丢」这件事不会发生 ——
#    它不在预取里，但用户点开时仍会按需下载。见下面两条断言。
nodate = [Episode("x-1", "x", NOW - H), Episode("x-2", "x", 0)]
sel = select_prefetch(nodate, NOW, prefetch_count=1)
check("无日期的条目排在后面 → 不进「接下来 N 条」，也不被保底选中（保底取的是该源第一条）",
      [ep.id for ep in sel.selected], ["x-1"])
check_true("而它**不会丢**：用户点开时走 SyncEngine.requestDownload 按需下载",
           "x-2" in [ep.id for ep in sel.skipped],
           "它只是不被预取，不是被丢掉 ——「懒加载」的定义就是「点了才下」")
check_true("也不会被 purge 清掉（purge 的判据要求 publishedAt > 0）",
           not should_purge(0, False, NOW),
           "少了这一条，源站没给日期的条目会被当成「远比期限久」而清出索引")

# 空输入
sel = select_prefetch([], NOW)
check("空候选 → 空结果（不崩、不返回 None）",
      (sel.selected, sel.skipped), ([], []))

# 幂等：同一份候选跑两次结果一致（判据里不许有隐藏状态）
a = [ep.id for ep in select_prefetch(mk_index(), NOW).selected]
b = [ep.id for ep in select_prefetch(mk_index(), NOW).selected]
check("同一输入两次结果一致（无隐藏状态、无时钟依赖）", a, b)

# 重 id 去重
dup = [Episode("same", "s", NOW - H), Episode("same", "s", NOW - H)]
check("同一 id 重复出现 → 只下一次",
      len(select_prefetch(dup, NOW).selected), 1)


# ---------------------------------------------------------------------------
# ⑥ 配对的不变量（2.1.9 换掉了一对）
# ---------------------------------------------------------------------------

section("⑥ 本地保留期 < 服务端音频保留期")

# 【已作废的旧不变量 —— 写在这里，免得下一个人再去把它恢复】
#   旧下载规则是「按发布时间开一个 2 天的窗口，窗口内全收」，于是
#   「窗口(2) < 保留期(3)」是一对必须同时看住的数：窗口 >= 保留期 时会出现
#   「刚下完、同一轮就被保留期清掉」的荒谬循环。
#
#   2.1.9 之后**下载不再看发布时间**（懒加载：接下来 N 条 + 收藏 + 在播 + 每源保底），
#   那个窗口不存在了 —— 这条不变量不是被放宽，而是**判据本身没有了**。
#   ⚠️ 所以这里**故意不**断言 DOWNLOAD_WINDOW_DAYS < RETAIN_DAYS：
#      它现在恒真（2 < 3），而断言一个恒真的东西等于在暗示「窗口还管着下载」，
#      那会把下一个改这块的人引向错误的方向。

# 新的一对：**本地音频保留期 < 服务端音频保留期**。
# 意义：本地三天后自动清掉没收藏的音频，而用户可能过几天才想起要重听 ——
# 那时必须还能从服务端重下。服务端留得比本地久，这条路才一直通。
check_true("本地 3 天 < 服务端 7 天", RETAIN_DAYS < SERVER_AUDIO_RETAIN_DAYS,
           f"local={RETAIN_DAYS} server={SERVER_AUDIO_RETAIN_DAYS}")

# 跨仓库核对：**直接读服务端源码**，而不是在本地再抄一份数 ——
# 两个数承担同一件事的两端，必须能让一条断言看出漂移。
_cycle_src = (ROOT / "server/bin/cycle.py").read_text(encoding="utf-8")

# ⚠️★ 4.6.4：**常量齐全性**。
#
# 起因：我按「`def` 到 `def`」整段删掉一个重复函数时，把夹在两个同名函数
# **之间**的 5 个模块级常量一起删了（`TRANSLATE_SET/BEAMS/THREADS/GUARD_SEC`、
# `RETENTION_DAYS`）。`py_compile` **不会报**（语法没问题），只有真跑到
# `main()` 才会 `NameError` —— 而那意味着**整轮采集崩掉**。
# 这条断言把「名字还在不在」变成构建期就能发现的事。
_MUST_EXIST = (
    "PUBLIC_BASE", "UA", "STATE", "WWW", "WWW_AUDIO", "WWW_ART",
    "TRANSLATE_SET", "TRANSLATE_BEAMS", "TRANSLATE_THREADS", "TRANSLATE_GUARD_SEC",
    "RETENTION_DAYS",
)
_missing = [k for k in _MUST_EXIST
            if not re.search(r"^%s\s*=" % k, _cycle_src, re.M)]
check("服务端 cycle.py 的关键常量都还在（删代码块别再连带吃掉它们）",
      _missing, [])
_srv_line = [l for l in _cycle_src.splitlines() if l.startswith("RETENTION_DAYS")]
_srv_days = int(_srv_line[0].split("=")[1].strip()) if _srv_line else -1
check("服务端 cycle.py 的 RETENTION_DAYS 与本地认定的 7 一致",
      _srv_days, SERVER_AUDIO_RETAIN_DAYS)
check_true("而且它**严格大于**本地保留期（本地删了之后还能去服务端重下）",
           _srv_days > RETAIN_DAYS, f"server={_srv_days} local={RETAIN_DAYS}")


# ---------------------------------------------------------------------------
# ⑦ purgeStaleIndex 是「每源保底」的必需配套
# ---------------------------------------------------------------------------

section("⑦ purgeStaleIndex 与保底的配合")

# 场景：索引里留着一个 10 天前的条目（无音频）。不 purge 的话它会作为
# 该源「最新一条」被保底捞进来 —— 每次都下，下完被保留期删，删了再下。
index = [
    Episode("npr-2h", "npr", NOW - 2 * H),
    Episode("cs-10d", "codeswitch", NOW - 10 * 24 * H),
]
has_audio = {}

# prefetch_count=1：把「接下来 N 条」压到只剩一条，**单独看保底在做什么**
sel_no_purge = select_prefetch(
    [ep for ep in index if not has_audio.get(ep.id, False)], NOW, prefetch_count=1)
check_true("不 purge 时，10 天前的条目会被保底当成「该源最新一条」捞进来",
           "cs-10d" in [ep.id for ep in sel_no_purge.selected],
           "这正是必须配套 purgeStaleIndex 的原因")

idx2 = [Episode(e.id, e.source_id, e.published_at) for e in index]
selected, purged = run_pipeline_round(idx2, has_audio, NOW, prefetch_count=1, purge=True)
check("purge 之后 10 天前的条目已不在索引 → 不会被保底选中",
      "cs-10d" in selected, False)
check("它确实是被 purge 拿掉的", purged, ["cs-10d"])

# 不间断推演 14 天：断言下载次数有界（不会无限重复下载）
has_audio = {}
index = [
    Episode("npr", "npr", NOW),
    Episode("cs", "codeswitch", NOW),
]
downloads = []
for d in range(14):
    now = NOW + d * DAY_MS
    # 每天源站可能出新内容（npr 每天、codeswitch 每周）
    if d > 0:
        if d % 7 == 0:
            index.insert(0, Episode(f"cs-w{d}", "codeswitch", now))
        index.insert(0, Episode(f"npr-d{d}", "npr", now - H))
    # 保留期：到期的音频被删 → 该条目变回「无音频」，同时按 downloadedAt 计时
    for ep in index:
        if has_audio.get(ep.id) and should_expire(ep.downloaded_at, now):
            has_audio[ep.id] = False
            ep.downloaded_at = 0
    sel_ids, _ = run_pipeline_round(index, has_audio, now)
    for i in sel_ids:
        downloads.append(i)
        has_audio[i] = True
        next(ep for ep in index if ep.id == i).downloaded_at = now

check_true("14 天里下载次数有界（没出现「删了又下」的死循环）",
           len(downloads) <= 20, f"实际下载 {len(downloads)} 条：{downloads}")
check_true("同一个 id 不会被下载两次（除非先被保留期删掉且仍在索引）",
           len(downloads) == len(set(downloads)),
           f"重复下载：{[i for i in set(downloads) if downloads.count(i) > 1]}")


# ---------------------------------------------------------------------------
# ⑧ selectExpired —— 四道豁免
# ---------------------------------------------------------------------------

section("⑧ selectExpired —— 谁能被删、谁不能")

old = NOW - 5 * DAY_MS
eps = [
    Episode("plain", "s", old, old),                      # 正常过期 → 删
    Episode("fav", "s", old, old),                        # 收藏 → 留
    Episode("playing", "s", old, old),                    # 正在播 → 留
    Episode("busy", "s", old, old, busy=True),            # 转写中 → 留
    Episode("fresh", "s", old, NOW - H),                  # 刚下载 → 留
    Episode("nodownload", "s", old, 0),                   # 没音频 → 留
]
audio = {"plain": True, "fav": True, "playing": True, "busy": True,
         "fresh": True, "nodownload": False}
keep = {"fav", "playing"}
victims = [ep.id for ep in select_expired(eps, NOW, lambda e: audio.get(e.id, False), keep)]
check("只删「非收藏 + 不在播 + 不 busy + 有音频 + 满 3 天」的那一条",
      victims, ["plain"])

check("收藏永远不会被自动删（需求原文：3 天后如果没有手动收藏，自动删除）",
      "fav" not in victims, True)
check("**正在播的那条不会被删**（ExoPlayer 文件句柄还开着，删了当场报错）",
      "playing" not in victims, True)
check("转写中的条目不会被删（流水线会崩在半路）", "busy" not in victims, True)

# keepIds 为空时，「正在播」只能靠调用方给 → 源码断言在末尾把关
victims2 = [ep.id for ep in select_expired(eps, NOW, lambda e: audio.get(e.id, False), set())]
check("keepIds 为空时会多删两条 —— 说明调用方必须传全三类",
      sorted(victims2), ["fav", "plain", "playing"])


# ---------------------------------------------------------------------------
# ⑨ downloadedAt 迁移
# ---------------------------------------------------------------------------

section("⑨ downloadedAt 迁移（0.11.0 老索引）")

legacy = [
    Episode("old-has-audio", "s", NOW - 30 * DAY_MS, 0),   # 有音频、没字段
    Episode("old-no-audio", "s", NOW - 30 * DAY_MS, 0),    # 没音频
]
has = {"old-has-audio": True, "old-no-audio": False}
legacy, patched = migrate_retention(legacy, lambda e: has[e.id], NOW)
check("只给有音频的补时间戳", patched, 1)
check("补的是 now（从这一版开始重新计时）", legacy[0].downloaded_at, NOW)
check("没音频的不补（否则它会被算成「下载过」）", legacy[1].downloaded_at, 0)

check("迁移后立刻判过期 → 不删（老内容多留三天）",
      should_expire(legacy[0].downloaded_at, NOW), False)
check("迁移后 2 天 → 不删", should_expire(legacy[0].downloaded_at, NOW + 2 * DAY_MS), False)
check("迁移后 3 天 → 删（老内容不会永远留着）",
      should_expire(legacy[0].downloaded_at, NOW + 3 * DAY_MS), True)

# 反例：如果迁移补的是 publishedAt 会怎样
wrong = Episode("old-has-audio", "s", NOW - 30 * DAY_MS, NOW - 30 * DAY_MS)
check_true("反例：若补 publishedAt，30 天前的内容刚迁移完就被判定过期 → 立刻删掉",
           should_expire(wrong.downloaded_at, NOW),
           "所以迁移必须补 now，不能补 publishedAt")


# ---------------------------------------------------------------------------
# ⑩ refreshFeeds 合并时必须保留 downloadedAt
# ---------------------------------------------------------------------------

section("⑩ 合并刷新时的 downloadedAt（漏了是静默的）")

old_ep = Episode("ep1", "s", NOW - 2 * H, NOW - 50 * H)   # 50 小时前下载的
# 新一轮从 RSS 拉到的同一条。**它天然没有 downloadedAt**（feed 里没这个字段），
# 所以 Kotlin 侧是 `item.copy(downloadedAt = old.downloadedAt)`，
# 这里也必须用一个 downloaded_at = 0 的「新对象」来还原
fresh = Episode("ep1", "s", NOW - 2 * H)

merged = refresh_merge(old_ep, [fresh], keep_downloaded_at=True)
check("保留旧的 downloadedAt", merged[0].downloaded_at, NOW - 50 * H)
check("→ 现在还不该删（50 小时 = 2 天）",
      should_expire(merged[0].downloaded_at, NOW), False)
check("→ 再过 1 天就该删（74 小时 = 3 天）",
      should_expire(merged[0].downloaded_at, NOW + DAY_MS), True)

dropped = refresh_merge(old_ep, [Episode("ep1", "s", NOW - 2 * H)], keep_downloaded_at=False)
check("反例：漏掉这一行 → downloadedAt 归 0", dropped[0].downloaded_at, 0)
check_true("反例：归 0 之后**永远算不出过期**，且不报错 —— 这就是要防的静默 bug",
           not should_expire(dropped[0].downloaded_at, NOW + 365 * DAY_MS),
           "一年后都不删，缓存涨到几个 G")

# 每轮刷新都丢一次：模拟 5 轮
ep = Episode("ep1", "s", NOW - 2 * H, NOW - 50 * H)
for _ in range(5):
    ep = refresh_merge(ep, [Episode("ep1", "s", NOW - 2 * H)], keep_downloaded_at=True)[0]
check("连续 5 轮刷新后 downloadedAt 仍在（不会逐轮被抹）",
      ep.downloaded_at, NOW - 50 * H)


# ---------------------------------------------------------------------------
# ⑪ 保留期判据必须是 downloadedAt，不是文件 mtime
# ---------------------------------------------------------------------------

section("⑪ 为什么不能用文件 mtime / publishedAt 当判据")

# AudioStore.touch 每次播放都刷新 mtime → 用它当基准会得出「听过一次的永不过期」
mtime = NOW - H              # 昨天刚播过（mtime 被 touch 刷新）
downloaded_time = NOW - 5 * DAY_MS  # 但其实是 5 天前下载的
check("用 mtime 判 → 不删（错了：用户要的是三天前听过的也清掉）",
      should_expire(mtime, NOW), False)
check("用 downloadedAt 判 → 删（正确）",
      should_expire(downloaded_time, NOW), True)

# publishedAt 更糟：刚下完的内容立刻进入倒计时
just_downloaded = NOW
pub = NOW - 10 * DAY_MS      # 一条 10 天前发布的节目，今天才被保底下载
check("用 publishedAt 当保留期判据 → 刚下完就被判过期（立刻删）",
      should_expire(pub, NOW), True)
check("用 downloadedAt → 刚下完不算过期（正确）",
      should_expire(just_downloaded, NOW), False)


# ---------------------------------------------------------------------------
# 源码断言（Python 侧无法复刻的部分）
# ---------------------------------------------------------------------------

section("⑫ 源码断言")


def read(rel: str) -> str:
    p = KT / rel
    if not p.is_file():
        FAILURES.append(f"      找不到源文件：{rel}")
        return ""
    return p.read_text(encoding="utf-8")


repo = read("content/ContentRepository.kt")
policy = read("content/ContentPolicy.kt")
episode = read("content/Episode.kt")
service = read("player/PlaybackService.kt")
sync = read("sync/SyncEngine.kt")
app = read("EnglishCommuteApp.kt")
nowplaying = read("player/NowPlaying.kt")
listening = read("checkin/ListeningClock.kt")

check_true("ContentRepository: RETAIN_DAYS = 3 / DOWNLOAD_WINDOW_DAYS = 2",
           "const val RETAIN_DAYS = 3L" in repo and "const val DOWNLOAD_WINDOW_DAYS = 2L" in repo)
check_true("ContentRepository: 有 DAY_MS 常量（三处判据共用同一个换算）",
           "const val DAY_MS = 86_400_000L" in repo)
check_true("ContentPolicy: ageDays 用整除而不是浮点",
           "(now - then) / ContentRepository.DAY_MS" in policy)
check_true("ContentPolicy: shouldExpire 先判 downloadedAt > 0",
           "downloadedAt > 0L &&" in policy)
check_true("ContentPolicy: shouldPurge 只对没音频的成立",
           "!hasAudio && publishedAt > 0L" in policy)
check_true("ContentPolicy: selectExpired 里 isBusy 是独立的一道豁免",
           "!ep.status.isBusy &&" in policy)
check_true("ContentPolicy: 保底取「每源第一次出现」（依赖候选倒序）",
           "if (!seen.add(ep.sourceId)) continue" in policy)
# ⚠️ 2.1.9 改了这条：并入四个集合之后不是用 distinctBy 收尾，而是**边取边去重**
#    （`picked` 是 LinkedHashSet，`picked += ep` 本身就不会重复）。
#    换成 `distinctBy { it.id }` 也能达到同样效果 —— 那是另一种正确写法，
#    所以这里判的是**性质**：「存在一个按 id 去重的累加器」，
#    而不是绑死某一种写法（本工程的纪律：断言别绑死实现串，否则下次合法改动就假失败）。
check_true("ContentPolicy: 预取结果按 id 去重（同一个 id 不会下两次）",
           "val picked = LinkedHashSet<Episode>()" in policy
           and "picked += ep" in policy,
           "四条规则的并集必须去重；具体用 LinkedHashSet 还是 distinctBy 不作要求")

check_true("Episode: downloadedAt 出现在 toJson",
           'put("downloadedAt", downloadedAt)' in episode)
check_true("Episode: downloadedAt 出现在 fromJson",
           'downloadedAt = o.optLong("downloadedAt")' in episode)
check_true("Episode: fromJson 里**不**补 now()（迁移交给 migrateRetention）",
           "optLong(\"downloadedAt\")" in episode and "optLong(\"downloadedAt\", now()" not in episode)

check_true("ContentRepository: ⚠️ refreshFeeds 合并时保留 downloadedAt",
           "downloadedAt = old.downloadedAt" in repo)
check_true("ContentRepository: refreshFeeds 末尾跑 purgeStaleIndex",
           "purgeStaleIndex(System.currentTimeMillis())" in repo)
check_true("ContentRepository: downloadEpisode 成功时写入 downloadedAt",
           "downloadedAt = System.currentTimeMillis()" in repo)
check_true("ContentRepository: deleteLocalFiles / resetAll 把它归 0",
           repo.count("downloadedAt = 0L") >= 2,
           f"实际出现 {repo.count('downloadedAt = 0L')} 次")
check_true("ContentRepository: init 里调 migrateRetention()",
           "migrateRetention()" in repo)

check_true("NowPlaying 由 PlaybackService 维护（只有它的生命周期与文件句柄一致）",
           "NowPlaying.attach(player)" in service and "NowPlaying.clear()" in service)
check_true("NowPlaying 在 STATE_IDLE 时清空",
           "STATE_IDLE" in nowplaying and "episodeId = null" in nowplaying)

check_true("SyncEngine: 保留期 keepIds 含收藏 + 正在播 + busy",
           "NowPlaying.episodeId" in sync and "status.isBusy" in sync
           and "favorites" in sync)
check_true("SyncEngine: 每轮结束调 expireOldAudio",
           "repository.expireOldAudio(" in sync)
check_true("App: 启动即扫一次保留期（不用等下一轮同步）",
           "sweepRetention" in app and "expireOldAudio" in app)

# ---- 删完之后必须修剪播放队列（本轮新引入的跨层影响）----
check_true("Expired 带出被删的 id（给播放队列修剪用）",
           "val ids: List<String>" in repo,
           "只报 count 的话，调用方拿不到「该从队列里摘哪几条」")
check_true("**SyncEngine 删完调 prunePlaylist**",
           "NowPlaying.prunePlaylist(expired.ids)" in sync,
           "ExoPlayer 的队列是点开某一条时的快照，删掉的文件还在里面 → 连播到它报「播放失败」")
check_true("**启动清理也调 prunePlaylist**",
           "NowPlaying.prunePlaylist(expired.ids)" in app)
check_true("prune 在主线程执行（ExoPlayer 要求同线程访问）",
           "Looper.myLooper() == Looper.getMainLooper()" in nowplaying
           and "main.post { pruneNow(p, ids) }" in nowplaying,
           "从 IO 线程调 removeMediaItem 会抛 Player is accessed on the wrong thread")
check_true("prune 倒序删除（删一项会让后面所有项下标前移）",
           "downTo 0" in nowplaying and "removeMediaItem(i)" in nowplaying)
check_true("prune 跳过正在播的那条（第二道保险）",
           "if (id == episodeId) continue" in nowplaying)
check_true("prune 在没有播放器时是空操作",
           "val p = player ?: return" in nowplaying)

# ---- 保留期演练钩子（唯一能让「3 天后删除」在装机当天被验到的东西）----
diag_vm = read("ui/DiagViewModel.kt")
diag_screen = read("ui/DiagScreen.kt")
check_true("仓库有 rewindRetentionForTest 演练钩子",
           "fun rewindRetentionForTest(days: Long, keepIds: Set<String>): Int" in repo,
           "没有它，「三天后自动删除」最早只能在装机三天后被验证")
check_true("**演练只改时间戳、不删文件**（删除仍由 expireOldAudio 按线上判据决定）",
           "ep.copy(downloadedAt = now - days * DAY_MS)" in repo
           and "deleteEpisodeFiles" not in repo.split("fun rewindRetentionForTest")[1].split("fun purgeStaleIndex")[0],
           "演练必须走与线上完全相同的删除路径，否则验不出真问题")
check_true("演练尊重保护名单（收藏 / 正在播不会被推时间）",
           "ep.id !in keepIds &&" in repo)
check_true("演练只动「有音频」的条目",
           "ep.downloadedAt > 0L && audioStore.hasAudio(ep)" in repo)
check_true("演练的保护名单与启动清理一致（含收藏与正在播）",
           "container.favorites.ids.value" in diag_vm and "NowPlaying.episodeId" in diag_vm)
check_true("演练删完也修剪播放队列",
           "NowPlaying.prunePlaylist(expired.ids)" in diag_vm)
check_true("演练入口在诊断页、且要两次点击确认",
           "confirmingRetention" in diag_screen and "再点确认（会删音频）" in diag_screen,
           "会删用户文件的动作不能一次点到")
check_true("演练推前 4 天（比保留期 3 天多一天，确保判据命中）",
           "TEST_DAYS = 4L" in diag_vm)

check_true("ListeningClock: 计时累加源是墙钟（now - mark），不是 currentPosition",
           "(now - mark) / 1000" in listening and "currentPosition" not in listening)
check_true("ListeningClock: 只在 isPlaying && STATE_READY 时计时",
           "player.isPlaying && player.playbackState == Player.STATE_READY" in listening)
check_true("ListeningClock: 超长 tick 直接丢弃（防冻结后凭空多记）",
           "elapsedSec > MAX_CREDIT_SECONDS" in listening and "MAX_CREDIT_SECONDS = 5" in listening)
check_true("ListeningClock: 挂在 PlaybackService 的播放器上（锁屏后仍计时）",
           "ListeningClock.attach(player)" in service)
check_true("ListeningClock: 跨午夜先结清上一段",
           "if (day != pendingDay)" in listening)
check_true("ListeningClock: 达标瞬间生成海报（不是等用户打开打卡页）",
           "if (tick.justCompleted) celebrate" in listening)


# ---------------------------------------------------------------------------
# 汇总
# ---------------------------------------------------------------------------

print("\n" + "─" * 62)
if FAILURES:
    print(f"\033[31m失败 {FAIL} 项\033[0m / 通过 {PASS} 项\n")
    for f in FAILURES:
        print(f"  ✗ {f}")
    sys.exit(1)

print(f"\033[32m全部通过：{PASS} 项\033[0m")
print("""
覆盖范围
  · ageDays 整除边界（71.99h / 72h 整 / 负差 / 无时间戳）
  · shouldExpire 边界与「没下载过」的豁免
  · daysLeft 的 None / 0 语义（0 = 今天到期，不是「剩 0 天」）
  · shouldPurge 与保留期的分工（有音频的条目归保留期管）
  · selectPrefetch：接下来 N 条（跳过已听完）+ 收藏 + 在播 + 每源保底 + 去重 + 幂等
  · 「每小时一集的源 10 条只下 3 条」—— 懒加载省流量的核心数字
  · 每源保底**含已听完的**（它防的是「源从索引里消失」，不是省下载）
  · 坏样本「不跳过已听完」必须被拦下 —— 断言要有分辨力，不能只会通过
  · 本地保留期(3) < 服务端音频保留期(7)：跨仓库核对，本地删了还能去服务端重下
  · purgeStaleIndex 是保底的必需配套（否则 10 天前的内容会被反复下载）
  · 14 天不间断推演：下载次数有界、无重复下载
  · selectExpired 的四道豁免（收藏 / 正在播 / busy / 没音频）
  · downloadedAt 迁移补 now 而不是 publishedAt
  · refreshFeeds 合并保留 downloadedAt（漏掉 = 静默失效一整年）
  · 为什么判据不能是文件 mtime 或 publishedAt
  · 删完之后修剪 ExoPlayer 播放队列（否则连播到已删文件 → 「播放失败」）
  · 保留期演练钩子只改时间戳、不删文件，且尊重保护名单
""")
