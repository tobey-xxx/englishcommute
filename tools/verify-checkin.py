#!/usr/bin/env python3
"""
复刻 **打卡的数据层**：本地日算法、连续天数、40 分钟阈值、以及收听计时器的 tick 循环。

## 为什么必须验它

这四块共同的特点是「错了不报错，只给一个**看起来很正常的错数字**」：

  · 日键算错一格   → 今天听的 40 分钟记到昨天，界面上毫无异常
  · 连续天数错一格 → 显示「连续 4 天」而不是 5，用户只会觉得「我明明打了卡」
  · 阈值判错       → 海报要么永远不生成、要么每次 tick 都生成一遍
  · 计时的边界     → 锁屏后整段通勤一分钟都不记；或反过来凭空多出十分钟

真机上要发现其中任何一条，都得手动听满 40 分钟、再等到第二天、再对着日志数。
桌面复刻的成本是几分钟。

## 复刻要求

**逐行对齐** Kotlin，不能「意思一下重写」。特别注意除法语义：

| Kotlin | Python | 说明 |
|---|---|---|
| `Math.floorDiv(a, b)` | `a // b` | 都是向下取整 —— 直接对齐 |
| `a / b`（Long） | `kdiv(a, b)` | Kotlin 朝零取整，Python 的 `//` 向下取整 |

源：
  `checkin/DayKey.kt`、`checkin/Streak.kt`、`checkin/CheckInStore.kt`、
  `checkin/ListeningClock.kt`、`checkin/CheckInPoster.kt`、`checkin/CheckInShare.kt`

用法：
    python3 tools/verify-checkin.py
"""

import sys
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
KT = ROOT / "app/src/main/java/com/example/englishcommute"

# ---------------------------------------------------------------------------
# 常量（与 Kotlin 对齐，见末尾源码断言）
# ---------------------------------------------------------------------------

DAY_MS = 86_400_000
GOAL_SECONDS = 40 * 60          # CheckInStore.GOAL_SECONDS
MAX_DAY_SECONDS = 24 * 60 * 60  # CheckInStore.MAX_DAY_SECONDS
TICK_MS = 1000                  # ListeningClock.TICK_MS
FLUSH_SECONDS = 5               # ListeningClock.FLUSH_SECONDS
MAX_CREDIT_SECONDS = 5          # ListeningClock.MAX_CREDIT_SECONDS
H = 3_600_000


def kdiv(a: int, b: int) -> int:
    """Kotlin 整数除法（朝零取整）的复刻。见 verify-retention.py 里的同一处说明"""
    q = abs(a) // abs(b)
    return q if (a < 0) == (b < 0) else -q


# ---------------------------------------------------------------------------
# 复刻：checkin/DayKey.kt
# ---------------------------------------------------------------------------

class FixedZone:
    """固定偏移时区（Java 的 SimpleTimeZone 退化情形）"""

    def __init__(self, offset_ms: int):
        self.offset_ms = offset_ms

    def get_offset(self, millis: int) -> int:
        return self.offset_ms


class EasternZone:
    """美东 2026 年：用来验「夏令时当天也算对」。

    转换点（真实规则）：
      · 春季 2026-03-08 02:00 EST → 03:00 EDT（UTC 07:00）
      · 秋季 2026-11-01 02:00 EDT → 01:00 EST（UTC 06:00）
    """

    DST_START = int(datetime(2026, 3, 8, 7, 0, tzinfo=timezone.utc).timestamp() * 1000)
    DST_END = int(datetime(2026, 11, 1, 6, 0, tzinfo=timezone.utc).timestamp() * 1000)

    def get_offset(self, millis: int) -> int:
        if self.DST_START <= millis < self.DST_END:
            return -4 * H   # EDT
        return -5 * H       # EST


CST8 = FixedZone(8 * H)     # 中国标准时间，无夏令时
UTC = FixedZone(0)


def day_of(millis: int, zone) -> int:
    """`DayKey.of` —— **floorDiv，不是普通除法**"""
    local = millis + zone.get_offset(millis)
    return local // DAY_MS   # Python 的 // 就是 floorDiv


def start_of_day_millis(day: int, zone) -> int:
    """`DayKey.startOfDayMillis` —— 两次取偏移"""
    naive = day * DAY_MS
    first_pass = naive - zone.get_offset(naive)
    return naive - zone.get_offset(first_pass)


def minutes_label(seconds: int) -> str:
    """`DayKey.minutesLabel`"""
    if seconds <= 0:
        return "0 分钟"
    if seconds < 60:
        return f"{seconds} 秒"
    return f"{seconds // 60} 分钟"


def clock_label(seconds: int) -> str:
    """`DayKey.clockLabel` —— `%d:%02d`，必须锁 Locale.US（源码断言）"""
    s = max(0, seconds)
    return f"{s // 60}:{s % 60:02d}"


def relative_label(day: int, today: int) -> str:
    """`DayKey.relativeLabel`"""
    d = today - day
    if d == 0:
        return "今天"
    if d == 1:
        return "昨天"
    if d == 2:
        return "前天"
    return "date"


# ---------------------------------------------------------------------------
# 复刻：checkin/Streak.kt
# ---------------------------------------------------------------------------

def streak_current(done_days: set, today: int) -> int:
    """`Streak.current` —— 「今天还没达标」不算断"""
    cursor = today if today in done_days else today - 1
    n = 0
    while cursor in done_days:
        n += 1
        cursor -= 1
    return n


def streak_best(done_days: set) -> int:
    """`Streak.best` —— 排序后找最长连通段"""
    if not done_days:
        return 0
    best = 0
    run = 0
    prev = None
    for day in sorted(done_days):
        run = run + 1 if (prev is not None and day == prev + 1) else 1
        if run > best:
            best = run
        prev = day
    return best


# ---------------------------------------------------------------------------
# 复刻：checkin/CheckInStore.kt
# ---------------------------------------------------------------------------

def add_seconds(store: dict, day: int, seconds: int) -> dict:
    """`CheckInStore.addSeconds`。

    store 是 `{day: seconds}`，等价于 prefs 里的 `sec:<day>`。
    justCompleted 由**数据层**算，是生成海报的唯一触发条件。"""
    if seconds <= 0:
        return snapshot(store, day)
    before = store.get(day, 0)
    after = min(before + seconds, MAX_DAY_SECONDS)
    if after == before:
        return snapshot(store, day)
    store[day] = after
    tick = snapshot(store, day)
    tick["justCompleted"] = before < GOAL_SECONDS and after >= GOAL_SECONDS
    return tick


def snapshot(store: dict, day: int) -> dict:
    """`CheckInStore.snapshot` —— done 是**算出来的**，不是存下来的"""
    sec = store.get(day, 0)
    return {
        "day": day,
        "seconds": sec,
        "goalSeconds": GOAL_SECONDS,
        "done": sec >= GOAL_SECONDS,
        "justCompleted": False,
        "streak": streak_current(done_keys(store), day),
    }


def done_keys(store: dict) -> set:
    return {d for d, s in store.items() if s >= GOAL_SECONDS}


def recent_done(store: dict, day: int, count: int) -> list:
    """`CheckInStore.recentDone` —— 旧 → 新，最后一个元素是今天"""
    return [(store.get(day - i, 0) >= GOAL_SECONDS) for i in range(count - 1, -1, -1)]


def is_done(store: dict, day: int) -> bool:
    return store.get(day, 0) >= GOAL_SECONDS


# ---------------------------------------------------------------------------
# 复刻：checkin/ListeningClock.kt 的 runTimer 循环
# ---------------------------------------------------------------------------

class ListeningSim:
    """逐行复刻 `ListeningClock.runTimer`。

    只认「墙钟时间」，与播放倍速、与音频内容位置完全无关 —— 这是刻意的：
    2 倍速听 10 分钟也是 10 分钟（语义是「今天花了多少时间听英语」）。
    """

    def __init__(self, zone, store: dict):
        self.zone = zone
        self.store = store
        self.pending = 0
        self.pending_day = None
        self.mark = None
        self.flushes = []       # (day, seconds, justCompleted)
        self.dropped = []       # 被丢弃的 tick 的 elapsedSec

    def start(self, now: int):
        """`attach(player)`：先把 pendingDay 定在今天，mark 定在当下"""
        self.pending = 0
        self.pending_day = day_of(now, self.zone)
        self.mark = now

    def tick(self, now: int, playing: bool, ready: bool = True):
        """一次 `delay(TICK_MS)` 之后的循环体"""
        elapsed = kdiv(now - self.mark, 1000)
        self.mark = now

        # 时钟跳变 / 进程被冻结：这一段无从判断，整段丢弃
        if elapsed <= 0 or elapsed > MAX_CREDIT_SECONDS:
            if elapsed > MAX_CREDIT_SECONDS or elapsed <= 0:
                self.dropped.append(elapsed)
            return

        counting = playing and ready
        if not counting:
            self.pending = self._flush()
            return

        day = day_of(now, self.zone)
        if day != self.pending_day:
            self.pending = self._flush()
            self.pending_day = day
        self.pending += elapsed
        if self.pending >= FLUSH_SECONDS:
            self.pending = self._flush()

    def stop(self):
        """协程取消（服务销毁）→ finally 里兜住最后几秒"""
        self._flush()

    def _flush(self) -> int:
        if self.pending <= 0:
            return 0
        tick = add_seconds(self.store, self.pending_day, self.pending)
        self.flushes.append((self.pending_day, self.pending, tick["justCompleted"]))
        return 0


# ---------------------------------------------------------------------------
# 测试框架
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


def ms(y, mo, d, h=0, mi=0, s=0, tz=timezone.utc) -> int:
    return int(datetime(y, mo, d, h, mi, s, tzinfo=tz).timestamp() * 1000)


def days_between(y1, m1, d1, y2, m2, d2) -> int:
    return (datetime(y2, m2, d2) - datetime(y1, m1, d1)).days


EPOCH = days_between(1970, 1, 1, 2026, 9, 20)   # 2026-09-20 的 epochDay


# ---------------------------------------------------------------------------
# ① DayKey.of —— 本地日
# ---------------------------------------------------------------------------

section("① DayKey.of —— 「今天」是哪一天")

midnight_cn = ms(2026, 9, 20, tz=timezone.utc) - 8 * H  # 2026-09-20 00:00 +08:00
check("UTC+8 本地零点 → 2026-09-20", day_of(midnight_cn, CST8), EPOCH)
check("本地零点前 1 毫秒 → 前一天",
      day_of(midnight_cn - 1, CST8), EPOCH - 1)
check("本地 23:59:59.999 → 当天",
      day_of(midnight_cn + 24 * H - 1, CST8), EPOCH)
check("本地零点整 → 次日",
      day_of(midnight_cn + 24 * H, CST8), EPOCH + 1)

# 用 Python 的 datetime 独立复核（只在固定偏移下成立，这正是选固定偏移做基准的原因）
def date_of(millis: int, offset_ms: int) -> int:
    local = datetime.fromtimestamp((millis + offset_ms) / 1000, tz=timezone.utc)
    return days_between(1970, 1, 1, local.year, local.month, local.day)


bad = []
for h in range(0, 24 * 40):          # 40 天的整点
    m = midnight_cn + h * H
    if day_of(m, CST8) != date_of(m, 8 * H):
        bad.append(m)
check("40 天整点采样：与 datetime 算出的本地日期逐点一致", bad, [])

# 负 millis：floorDiv 而不是普通除法
check("epoch 前 1 毫秒（UTC）→ day = -1",
      day_of(-1, UTC), -1)
check("↳ 若用朝零取整会得到 0（Kotlin 源码里必须是 Math.floorDiv）",
      kdiv(-1, DAY_MS), 0)
check("epoch 前 1 毫秒但 UTC+8 → 已是 1970-01-01，day = 0",
      day_of(-1, CST8), 0)
check("epoch 前 2 小时（UTC）→ day = -1",
      day_of(-2 * H, UTC), -1)

# 源码断言：必须是 floorDiv
daykey_src = (KT / "checkin/DayKey.kt")
daykey_text = daykey_src.read_text(encoding="utf-8") if daykey_src.is_file() else ""
check_true("DayKey.of 用 Math.floorDiv（不是 `/`）",
           "Math.floorDiv(local, DAY_MS)" in daykey_text,
           "普通除法在 1970 年之前会朝零取整，一天里的前几小时会被算到后一天")

# 时区无关性：同一时刻在不同时区可能属于不同的本地日（这是正确行为）
t = ms(2026, 9, 20, 17, 0)   # UTC 17:00 = 北京 01:00 次日
check("UTC 17:00 → UTC 侧仍是 9-20",
      day_of(t, UTC), EPOCH)
check("同一时刻在北京已是 9-21（+1 天）",
      day_of(t, CST8), EPOCH + 1)


# ---------------------------------------------------------------------------
# ② startOfDayMillis —— 夏令时当天
# ---------------------------------------------------------------------------

section("② startOfDayMillis / 夏令时")

check("固定时区：零点回环成立",
      all(day_of(start_of_day_millis(d, CST8), CST8) == d for d in range(EPOCH - 5, EPOCH + 5)), True)

spring = days_between(1970, 1, 1, 2026, 3, 8)
fall = days_between(1970, 1, 1, 2026, 11, 1)

check("春季跳变日零点回环", day_of(start_of_day_millis(spring, EasternZone()), EasternZone()), spring)
check("**春季跳变日只有 23 小时**",
      start_of_day_millis(spring + 1, EasternZone()) - start_of_day_millis(spring, EasternZone()),
      23 * H)
check("前一天的日长仍是 24 小时",
      start_of_day_millis(spring, EasternZone()) - start_of_day_millis(spring - 1, EasternZone()),
      24 * H)
check("**秋季回拨日有 25 小时**",
      start_of_day_millis(fall + 1, EasternZone()) - start_of_day_millis(fall, EasternZone()),
      25 * H)

# 「零点前一毫秒属于前一天」—— 跨夏令时也成立（美东的转换点在凌晨 2 点，不在零点）
bad2 = []
for d in range(spring - 3, spring + 4):
    s = start_of_day_millis(d, EasternZone())
    if day_of(s - 1, EasternZone()) != d - 1:
        bad2.append(d)
for d in range(fall - 3, fall + 4):
    s = start_of_day_millis(d, EasternZone())
    if day_of(s - 1, EasternZone()) != d - 1:
        bad2.append(d)
check("跨夏令时的零点边界都正确（前 3 天 ~ 后 3 天）", bad2, [])

# 日序连续、无跳格
ds = [day_of(start_of_day_millis(d, EasternZone()) + H, EasternZone())
      for d in range(spring - 3, spring + 4)]
check("春季跳变前后日序连续（不跳格、不重复）",
      ds, list(range(spring - 3, spring + 4)))


# ---------------------------------------------------------------------------
# ③ 连续打卡
# ---------------------------------------------------------------------------

section("③ Streak —— 四种情形")

T = EPOCH
check("今天达标、连 5 天 → 5",
      streak_current({T, T - 1, T - 2, T - 3, T - 4}, T), 5)
check("今天达标、中间断过 → 只数到断点",
      streak_current({T, T - 1, T - 3, T - 4, T - 5}, T), 2)
check("**今天未达标、昨天达标 → 不断裂（从昨天往前数）**",
      streak_current({T - 1, T - 2, T - 3}, T), 3)
check("今天未达标、昨天也未达标 → 0",
      streak_current({T - 2, T - 3, T - 4}, T), 0)
check("只有今天达标 → 1", streak_current({T}, T), 1)
check("空记录 → 0", streak_current(set(), T), 0)
check("**「上周连了 5 天、这周一直没听」→ 0（不会永远显示 5）**",
      streak_current({T - 10, T - 9, T - 8, T - 7, T - 6}, T), 0)
check("跨月边界（T 是月初）也按整数算，没有格式问题",
      streak_current({T, T - 1, T - 2}, T), 3)
check("未来日期不会被打乱（守卫：只往前数）",
      streak_current({T, T + 1, T + 2}, T), 1)

check("最长：单段", streak_best({T, T - 1, T - 2}), 3)
check("最长：两段取长的", streak_best({T - 10, T - 9, T - 8, T - 7, T - 1, T - 2, T - 3}), 4)
check("最长：全断", streak_best({T - 10, T - 5, T - 1}), 1)
check("最长：空集 → 0", streak_best(set()), 0)
check("最长：大跨度（1 万天的集合里找段）",
      streak_best(set(range(0, 10_000)) | set(range(20_000, 20_030))), 10_000)
check("最长：乱序输入不影响结果",
      streak_best({T - 1, T - 3, T - 2}), 3)


# ---------------------------------------------------------------------------
# ④ 40 分钟阈值
# ---------------------------------------------------------------------------

section("④ addSeconds —— 阈值与封顶")

s = {}
t = add_seconds(s, T, 2399)
check("2399 秒 → 未达标", t["done"], False)
check("↳ justCompleted 为假", t["justCompleted"], False)

t = add_seconds(s, T, 1)
check("**2399 + 1 = 2400 → 刚好达标**", t["done"], True)
check("**↳ justCompleted 为真（唯一的庆祝触发点）**", t["justCompleted"], True)

t = add_seconds(s, T, 1)
check("2401 秒 → 仍然达标", t["done"], True)
check("**↳ justCompleted 为假（只庆祝一次）**", t["justCompleted"], False)

s2 = {}
t = add_seconds(s2, T, 2400)
check("一次加满 2400 → 达标且 justCompleted 为真", (t["done"], t["justCompleted"]), (True, True))

s3 = {}
add_seconds(s3, T, 2398)
t = add_seconds(s3, T, 5)
check("跨过阈值的那一次（2398 → 2403）justCompleted 为真",
      (t["seconds"], t["justCompleted"]), (2403, True))

# 每次 +5 的常规路径：从 0 到 2400 只有一次 justCompleted
s4 = {}
celebrations = 0
for _ in range(480):
    if add_seconds(s4, T, 5)["justCompleted"]:
        celebrations += 1
check("**480 次 +5 累计到 2400，只庆祝一次**", celebrations, 1)
check("↳ 累计秒数正确", s4[T], 2400)

# 零点与负数
s5 = {T: 100}
check("加 0 不改动", add_seconds(s5, T, 0)["seconds"], 100)
check("加负数不改动", add_seconds(s5, T, -50)["seconds"], 100)
check("↳ 也不会误触发 justCompleted", add_seconds(s5, T, -50)["justCompleted"], False)

# 封顶
s6 = {T: MAX_DAY_SECONDS - 10}
t = add_seconds(s6, T, 10)
check("加到正好 24 小时 → 允许", t["seconds"], MAX_DAY_SECONDS)
t = add_seconds(s6, T, 10)
check("**超过 24 小时 → 不再增长（时钟被改过也不会把统计带偏）**",
      t["seconds"], MAX_DAY_SECONDS)
check("↳ 也不触发 justCompleted（after == before 直接返回）",
      t["justCompleted"], False)

# done 是算出来的
s7 = {T: GOAL_SECONDS}
check("done 由 seconds >= GOAL 现算（改目标时长后历史自动跟着变）",
      snapshot(s7, T)["done"], True)
check("独立天数互不影响", add_seconds({T: 100, T - 1: 2400}, T, 5)["seconds"], 105)
check("↳ 昨天的达标状态不被今天的操作影响",
      is_done({T: 100, T - 1: 2400}, T - 1), True)

# streak 随 addSeconds 一起返回
s8 = {T - 1: 2400, T - 2: 2400}
t = add_seconds(s8, T, 2400)
check("刚达标时返回的 streak 已含今天（3 天）", t["streak"], 3)


# ---------------------------------------------------------------------------
# ⑤ recentDone —— 顺序是「旧 → 新」
# ---------------------------------------------------------------------------

section("⑤ recentDone —— 海报上那排格子")

s9 = {}
add_seconds(s9, T, GOAL_SECONDS)          # 今天
add_seconds(s9, T - 2, GOAL_SECONDS)      # 前天
add_seconds(s9, T - 6, GOAL_SECONDS)      # 6 天前
r = recent_done(s9, T, 7)
check("7 格：旧 → 新，最后一个元素是今天", r,
      [True, False, False, False, True, False, True])
check("↳ 最后一个是今天（画海报时 i==6 才描那圈琥珀）", r[-1], True)
check("长度与 CheckInPoster.RECENT_DAYS 一致", len(recent_done(s9, T, 7)), 7)
check("只听过没达标 → 不算（格子必须反映真实达标情况）",
      recent_done({T: 2399}, T, 1), [False])
check("全空 → 全 False", recent_done({}, T, 7), [False] * 7)

# 关键：不能凭空假设过去几天都达标
s10 = {T: GOAL_SECONDS}
check_true("**今天达标但前几天没达标 → 格子必须显示断过**",
           recent_done(s10, T, 7) != [True] * 7,
           "「假设过去都达标」正是这排格子要防的错（见 CheckInPoster 的注释）")


# ---------------------------------------------------------------------------
# ⑥ 计时器：墙钟口径与 40 分钟
# ---------------------------------------------------------------------------

section("⑥ ListeningClock —— 计时口径")

t0 = ms(2026, 9, 20, 8, 0, tz=timezone.utc) - 8 * H  # 北京 08:00
local_day = day_of(t0, CST8)

store = {}
sim = ListeningSim(CST8, store)
sim.start(t0)
for i in range(1, 2401):                     # 连续 40 分钟
    sim.tick(t0 + i * 1000, playing=True)
sim.stop()
check("**连续播放 40 分钟 → 恰好 2400 秒，达标**", store.get(local_day), 2400)
check("↳ 恰好触发一次 justCompleted",
      sum(1 for _, _, j in sim.flushes if j), 1)
check("↳ 最后 5 秒也被落盘（2400 是 5 的整数倍）", sim.flushes[-1][1], 5)

# 39 分钟不够
store2 = {}
sim2 = ListeningSim(CST8, store2)
sim2.start(t0)
for i in range(1, 2341):
    sim2.tick(t0 + i * 1000, playing=True)
sim2.stop()
check("连续 39 分钟 → 未达标", store2.get(local_day, 0) < 2400, True)
check("↳ 落盘的秒数是 5 的整数倍（内存攒 5 秒才写）",
      store2.get(local_day, 0) % 5, 0)
check("↳ 39 分钟实际落盘 2340 秒（不足 5 秒的部分留在内存）",
      store2.get(local_day, 0), 2340)

# 口径是墙钟：把「音频内容位置」当成无关变量
store3 = {}
sim3 = ListeningSim(CST8, store3)
sim3.start(t0)
for i in range(1, 601):                      # 墙钟 10 分钟
    sim3.tick(t0 + i * 1000, playing=True, ready=True)
sim3.stop()
check("**墙钟 10 分钟 = 600 秒（与倍速、与内容位置无关）**",
      store3.get(local_day), 600)

# 暂停不计
store4 = {}
sim4 = ListeningSim(CST8, store4)
sim4.start(t0)
for i in range(1, 301):                      # 听 5 分钟
    sim4.tick(t0 + i * 1000, playing=True)
for i in range(301, 901):                    # 暂停 10 分钟
    sim4.tick(t0 + i * 1000, playing=False)
for i in range(901, 1201):                   # 再听 5 分钟
    sim4.tick(t0 + i * 1000, playing=True)
sim4.stop()
check("暂停期间不累计（只记两段各 5 分钟）", store4.get(local_day), 600)

# 缓冲中不计
store5 = {}
sim5 = ListeningSim(CST8, store5)
sim5.start(t0)
for i in range(1, 121):
    sim5.tick(t0 + i * 1000, playing=True, ready=False)
sim5.stop()
check("**缓冲中不计（STATE_BUFFERING 时没有声音在响）**", store5.get(local_day, 0), 0)

# 时钟跳变 / 进程被冻结
store6 = {}
sim6 = ListeningSim(CST8, store6)
sim6.start(t0)
for i in range(1, 61):
    sim6.tick(t0 + i * 1000, playing=True)
freeze_at = t0 + 60 * 1000 + 10 * 60 * 1000        # 往前跳 10 分钟
sim6.tick(freeze_at, playing=True)
for i in range(1, 61):
    sim6.tick(freeze_at + i * 1000, playing=True)
sim6.stop()
check("**被冻结的那 10 分钟整段丢弃（宁可少记，不凭空多记）**",
      store6.get(local_day, 0), 120)
check("↳ 跳变确实被登记为丢弃", 600 in sim6.dropped, True)
check("↳ 跳变之后计时恢复正常（mark 已跟上，不会连续丢）",
      store6.get(local_day, 0) == 120, True)

# 重复 tick（elapsed = 0）不重复计
store7 = {}
sim7 = ListeningSim(CST8, store7)
sim7.start(t0)
for _ in range(100):
    sim7.tick(t0, playing=True)
sim7.stop()
check("同一时刻反复 tick（elapsed=0）→ 一秒都不记", store7.get(local_day, 0), 0)

# 服务销毁时兜住最后几秒
store8 = {}
sim8 = ListeningSim(CST8, store8)
sim8.start(t0)
for i in range(1, 23):                        # 22 秒，最后 2 秒还没到 5 秒阈值
    sim8.tick(t0 + i * 1000, playing=True)
sim8.stop()
check("**服务销毁 → finally 兜住不足 5 秒的尾数**（22 秒全部落盘）",
      store8.get(local_day, 0), 22)


# ---------------------------------------------------------------------------
# ⑦ 跨午夜分账
# ---------------------------------------------------------------------------

section("⑦ 跨午夜自动分账")

midnight = ms(2026, 9, 20, tz=timezone.utc) - 8 * H  # 北京 09-20 00:00
start_2350 = midnight - 10 * 60 * 1000               # 09-19 23:50
d_prev = day_of(start_2350, CST8)
d_next = d_prev + 1

store9 = {}
sim9 = ListeningSim(CST8, store9)
sim9.start(start_2350)
for i in range(1, 1201):                             # 播 20 分钟，跨过零点
    sim9.tick(start_2350 + i * 1000, playing=True)
sim9.stop()
# 边界归属：每个 tick 的那一秒归给**该 tick 结束时刻**所在的本地日。
# 于是 i=600 的那一秒（23:59:59 → 00:00:00）算新的一天 —— 差这一秒无所谓，
# 但要写清楚，否则「两天加起来必须等于墙钟总量」这条不变量就对不上。
check("23:50:01 → 23:59:59 的 599 秒记在前一天", store9.get(d_prev), 599)
check("**00:00:00 → 00:10:00 的 601 秒记在新的一天**", store9.get(d_next), 601)
check("两天加起来正好等于墙钟总量 1200 秒（一秒不丢、一秒不重）",
      store9.get(d_prev, 0) + store9.get(d_next, 0), 1200)
check("跨天时先结清了上一段（flush 的第一条是前一天）",
      sim9.flushes[0][0], d_prev)
check_true("↳ 且存在一条 flush 记录在次日",
           any(day == d_next for day, _, _ in sim9.flushes))

# 跨午夜连听 40 分钟：23:50 → 00:30，前一天只有 10 分钟、新一天 30 分钟，
# **两天都不达标** —— 不会出现「半夜听完就顺手把某一天打上卡」
store10 = {}
sim10 = ListeningSim(CST8, store10)
sim10.start(start_2350)
for i in range(1, 2 * 20 * 60 + 1):
    sim10.tick(start_2350 + i * 1000, playing=True)
sim10.stop()
check("跨午夜听 40 分钟 → 599 / 1801（按本地日分账）",
      (store10.get(d_prev), store10.get(d_next)), (599, 1801))
check("↳ 两天都不够 40 分钟，都不算达标",
      is_done(store10, d_prev) or is_done(store10, d_next), False)
check("↳ 分账而不是攒在一起（否则半夜听完就能打卡）",
      store10.get(d_prev, 0) + store10.get(d_next, 0), 2400)


# ---------------------------------------------------------------------------
# ⑧ 标签与格式化
# ---------------------------------------------------------------------------

section("⑧ 标签")

check("0 秒 → 「0 分钟」", minutes_label(0), "0 分钟")
check("1 秒 → 「1 秒」", minutes_label(1), "1 秒")
check("59 秒 → 「59 秒」", minutes_label(59), "59 秒")
check("60 秒 → 「1 分钟」", minutes_label(60), "1 分钟")
check("2399 秒 → 「39 分钟」", minutes_label(2399), "39 分钟")
check("2400 秒 → 「40 分钟」", minutes_label(2400), "40 分钟")
check("负数 → 「0 分钟」", minutes_label(-5), "0 分钟")

check("clockLabel 0 → 0:00", clock_label(0), "0:00")
check("clockLabel 59 → 0:59", clock_label(59), "0:59")
check("clockLabel 60 → 1:00", clock_label(60), "1:00")
check("clockLabel 2399 → 39:59", clock_label(2399), "39:59")
check("clockLabel 负数 → 0:00（不出现 -1:-1）", clock_label(-30), "0:00")

check("relativeLabel 今天", relative_label(T, T), "今天")
check("relativeLabel 昨天", relative_label(T - 1, T), "昨天")
check("relativeLabel 前天", relative_label(T - 2, T), "前天")
check("relativeLabel 更早 → 走日期格式化", relative_label(T - 3, T), "date")


# ---------------------------------------------------------------------------
# ⑨ 源码断言（Python 侧复刻不到的部分）
# ---------------------------------------------------------------------------

section("⑨ 源码断言")


def read(rel: str) -> str:
    p = KT / rel
    if not p.is_file():
        FAILURES.append(f"      找不到源文件：{rel}")
        return ""
    return p.read_text(encoding="utf-8")


def code_only(src: str) -> str:
    """剥掉 Kotlin 注释，只留可执行代码。

    「某字符串不该出现」这类断言必须基于**代码**而不是全文 ——
    否则注释里写「⚠️ 不要用 resolveActivity 预判」就会把断言判成失败。
    """
    out = []
    for line in src.splitlines():
        s = line.strip()
        if s.startswith("*") or s.startswith("//") or s.startswith("/*"):
            continue
        if "//" in line:
            line = line.split("//", 1)[0]
        out.append(line)
    return "\n".join(out)


store_src = read("checkin/CheckInStore.kt")
merge_src = read("checkin/SyncMerge.kt")
title_src = read("checkin/GoalTimeline.kt")
streak_src = read("checkin/Streak.kt")
clock_src = read("checkin/ListeningClock.kt")
poster_src = read("checkin/CheckInPoster.kt")
share_src = read("checkin/CheckInShare.kt")
vm_src = read("ui/CheckInViewModel.kt")
screen_src = read("ui/CheckInScreen.kt")
main_src = (ROOT / "app/src/main/java/com/example/englishcommute/MainActivity.kt").read_text(encoding="utf-8")
app_src = read("EnglishCommuteApp.kt")
service_src = read("player/PlaybackService.kt")
paths_src = (ROOT / "app/src/main/res/xml/file_paths.xml")
paths_text = paths_src.read_text(encoding="utf-8") if paths_src.is_file() else ""

# ⚠️ 3.3.0 起目标**可以自定义**了，所以这一组断言的形状跟着变了。
#    原来查的是「常量 = 40*60」；现在要查的是两件事：
#      ① 默认值仍是 40 分钟（老用户升级上来必须还是 40）
#      ② 判据处读的是**设置项**，而不是任何常量
#
# ⚠️ 3.4.0 又变了一次形状，理由记在这里：
#    目标从「一个值」变成了**一条按天的时间轴**（`GoalTimeline`），
#    所以默认值与钳制都搬到了那个纯函数模块里，判据也从 `goalSeconds`
#    变成 `goalSecondsOn(day)`（那一天的目标，不是当前设置）。
#    这个脚本只查「数据层有没有按天取」这一类事实，
#    「改目标只影响明天」那整套不变式在 `verify-checkin-goal.py` 里，不重复。
check_true("默认目标仍是 40 分钟（且定义在时间轴模块里，只此一份）",
           "const val DEFAULT_MINUTES = 40" in title_src
           and "DEFAULT_GOAL_MINUTES" not in store_src)
check_true("  · 而那个常量已经不再参与判据（不留第二份真相）",
           "const val GOAL_SECONDS" not in store_src)
check_true("目标可写，且有范围钳制（钳制在写入那一个入口里）",
           "fun setGoalMinutes(" in store_src
           and "coerceIn(MIN_MINUTES, MAX_MINUTES)" in title_src)
check_true("单日封顶 24 小时", "MAX_DAY_SECONDS = 24 * 60 * 60" in store_src)
# ⚠️ 4.1.1：累加与跨线判据搬进了 `SyncMerge.accumulate`（纯函数、有单测；
# 起因是一个「起点取合并值、终点写进本机那一列」的 bug，见 SyncMergeTest）。
# 意图没变：**由数据层算、且按那一天的目标算**。
check_true("justCompleted 由数据层算，且按那一天（现在在 SyncMerge 里）",
           "goal = goalSecondsOn(day)," in store_src
           and "justCompleted = beforeTotal < goal && total >= goal" in merge_src)
check_true("addSeconds 里封顶后不再写盘（`acc.mine` 没变就直接返回）",
           "if (acc.mine == mineNow) return snapshot(day)" in store_src)
check_true("seconds <= 0 直接返回 snapshot",
           "if (seconds <= 0) return snapshot(day)" in store_src)
check_true("**没有存 done 布尔量（避免两个真相源）**",
           "putBoolean" not in store_src,
           "done 必须由 seconds >= 目标 现算")
check_true("recentDone 顺序为 `count - 1 downTo 0`（旧 → 新）",
           "(count - 1 downTo 0).map { isDone(day - it) }" in store_src)
# ⚠️ 3.3.0 改过这条断言的**形状**，理由值得记：
#    原来是 `prefs.edit().clear().apply()` —— 整份 prefs 清空。
#    加了 `goal` 之后那句会把用户设定的目标一起清掉（悄悄回默认），
#    所以改成**按前缀删**。断言跟着改成「按前缀删、且不再整份 clear」。
check_true("reset() 按前缀删记录（不整份 clear —— 那会把目标也清掉）",
           "prefs.edit().clear()" not in store_src
           and "startsWith(KEY_SEC)" in store_src)
check_true("打卡记录不挂在「清空缓存」上（注释里明确写了）",
           "不挂在「清空缓存」上" in store_src)

check_true("Streak.current：cursor = if (today in doneDays) today else today - 1",
           "var cursor = if (today in doneDays) today else today - 1" in streak_src)
check_true("Streak.current 用 while 往前数（O(段长)）",
           "while (cursor in doneDays)" in streak_src)
check_true("Streak.best 用排序后找连通段（O(n log n)）",
           "val sorted = doneDays.sorted()" in streak_src)

check_true("计时器挂在 Player 上（不是 ViewModel）",
           "private suspend fun runTimer(player: Player)" in clock_src)
check_true("累加源是墙钟 now - mark",
           "((now - mark) / 1000).toInt()" in clock_src)
check_true("计时只认 isPlaying && STATE_READY",
           "player.isPlaying && player.playbackState == Player.STATE_READY" in clock_src)
check_true("单次最多认 5 秒", "MAX_CREDIT_SECONDS = 5" in clock_src)
check_true("tick 间隔 1 秒", "private const val TICK_MS = 1_000L" in clock_src)
check_true("攒 5 秒才落盘", "private const val FLUSH_SECONDS = 5" in clock_src)
check_true("用 while(true) + finally，不用 while(isActive)（避免漏掉几秒）",
           "while (true)" in clock_src and "} finally {" in clock_src)
check_true("跨午夜先结清上一段",
           "if (day != pendingDay) {" in clock_src and "pendingDay = day" in clock_src)
check_true("达标瞬间生成海报", "if (tick.justCompleted) celebrate(day, tick.streak)" in clock_src)
# 1.2.7：celebrate 改走 CheckInPoster.ensureFile（三个入口统一，
# 见 verify-checkin 后面那组「一处定义」断言）。这里只要求它仍然是兜住的
check_true("海报生成失败不影响计时（runCatching 兜住）",
           "runCatching { CheckInPoster.ensureFile(" in clock_src
           and "海报生成失败（不影响打卡）" in clock_src)
check_true("计时器与容器共用同一个 CheckInStore（否则界面进度不动）",
           "store = checkIn" in clock_src and "ListeningClock.install(this, container.checkIn)" in app_src)
check_true("PlaybackService 里 attach / detach 成对出现",
           "ListeningClock.attach(player)" in service_src and "ListeningClock.detach()" in service_src)

check_true("海报 1080×1440", "private const val W = 1080" in poster_src and "private const val H = 1440" in poster_src)
check_true("海报先写 .tmp 再 rename（半张图不会被当成完成）",
           ".png.tmp" in poster_src and "tmp.renameTo(file)" in poster_src)
check_true("写盘后 bitmap.recycle()", "bitmap.recycle()" in poster_src)


# ===========================================================================
# 1.2.7 海报：三个入口共用一份编排
# ===========================================================================
#
# 用户报的原话：「40 分钟完成时，弹出打卡成功页，但是生成不了海报图，
# 只能退出去，在打卡页分享」。
#
# 根因不是渲染坏了（打卡页一进去就有图），而是**同一件事有多个答案**：
# 打卡页的 ViewModel 里写着「文件不在就 render」，而庆祝页只「等 4 秒文件、
# 等不到就放弃」。于是庆祝页永远空着，打卡页永远有图。
#
# 这类分歧不会被任何一处发现 —— 它需要把三个入口放在一起看。

section("1.2.7 海报：三个入口共用一份编排（ensureFile）")

cvm_src = read("ui/CelebrationViewModel.kt")

check_true("CheckInPoster 提供 ensureFile（有就返回、没有才渲染）",
           "fun ensureFile(context: Context, store: CheckInStore, day: Long): File?" in poster_src)
check_true("  · 跨过目标线那一刻走它", "CheckInPoster.ensureFile(" in clock_src)
check_true("  · 庆祝页走它（用户报的就是这一处空着）",
           "CheckInPoster.ensureFile(" in cvm_src)
check_true("  · 打卡页走它", "CheckInPoster.ensureFile(" in vm_src)
check_true("  · 于是「裸 render」在三个调用方里一个都不剩",
           "CheckInPoster.render(" not in clock_src
           and "CheckInPoster.render(" not in vm_src
           and "CheckInPoster.render(" not in cvm_src,
           "外部还留着一个直接 render 的调用点，就又能分叉出新的一套行为")
check_true("  · ensureFile 自己确实调了 render（编排没被抽成空壳）",
           "render(" in poster_src)

# —— 并发：三个入口会在同一秒内都来问一次 ——
check_true("ensureFile 内部串行（双检锁）",
           "synchronized(ensureLock)" in poster_src
           and "private val ensureLock = Any()" in poster_src,
           "同一天的图会被两个线程同时压缩到同一个 tmp 文件 → rename 出半截 PNG，"
           "而界面判「完成」的依据正是「文件在不在」")
check_true("  · 拿到锁之后再检查一次文件（第二个进来的不重复渲染）",
           # ⚠️ 别用 index 比大小来判断「谁在前」—— `render(` 首次出现是
           #    **函数定义**那一行（在 ensureFile 之前），比出来只会是恒假。
           #    这里改成语义断言：进锁 → 查文件 → 有就直接 return
           "val existing = existingFor(store, day)" in poster_src
           and "if (existing != null) return existing" in poster_src)
check_true("  · tmp 文件名唯一",
           "File.createTempFile(" in poster_src
           and '"checkin-$day.png.tmp"' not in poster_src,
           "固定成 .png.tmp 时，两个渲染方写的是同一个文件")
check_true("  · 仍然是先写 tmp 再 rename（半张图不会被当成完成）",
           "tmp.renameTo(file)" in poster_src)

# —— 庆祝页的「等」不再是正确性的依赖 ——
check_true("庆祝页等不到会**自己补一张**，不放弃",
           "CheckInPoster.ensureFile(getApplication(), store, day)" in cvm_src
           and "等不到海报，自己画一张" in cvm_src)
check_true("  · 而且它记日志（下次一搜就知道走的是哪条路）",
           "庆祝页取到海报" in cvm_src and "庆祝页等不到海报" in cvm_src,
           "这个问题在代码上看着都「有处理」，只有日志能分清是哪条路走的")
check_true("out.fd.sync() 确保真正落盘", "out.fd.sync()" in poster_src)
check_true("小格子数量常量公开且为 7", "const val RECENT_DAYS = 7" in poster_src)
check_true("格子用 recentDone 的真实记录（不是假设都达标）",
           "recentDone.getOrElse(i)" in poster_src)
check_true("海报固定浅色纸（微信聊天背景多为浅色）",
           "PAPER" in poster_src and "海报固定用浅色" in poster_src)
check_true("streak = 0 时用占位数字而不是画 0（防崩）",
           "if (streak > 0) streak else PLACEHOLDER_STREAK" in poster_src)
# ⚠️ 3.3.0：目标可自定义之后，海报不能再去读那个常量 ——
#    它必须由**调用方把当前目标传进来**。
#    海报是唯一一处会被存下来、发出去的目标展示；画的时候读常量的话，
#    用户改了目标之后生成的海报会印着**别人的标准**，而且那张图会留很久。
check_true("海报的目标分钟数由参数传入（不读常量）",
           "goalMinutes: Int," in poster_src and "目标 $goalMinutes 分钟" in poster_src)
check_true("  · 且海报里不再出现那个常量", "GOAL_SECONDS" not in poster_src)
check_true("按视觉中心而非基线画字（大号数字不会偏上）",
           "cy - (fm.ascent + fm.descent) / 2f" in poster_src)

check_true("分享先单独试微信", 'setPackage(WECHAT_PACKAGE)' in share_src)
check_true("微信包名是 com.tencent.mm", 'WECHAT_PACKAGE = "com.tencent.mm"' in share_src)
check_true("失败回落 createChooser", "Intent.createChooser(send" in share_src)
check_true("**不用 resolveActivity 预判**（Android 11 包可见性会误判）",
           "resolveActivity" not in code_only(share_src),
           "没在 <queries> 声明的包一律解析不到，明明装了微信也会走不进那条分支")
check_true("按次授予读权限", "FLAG_GRANT_READ_URI_PERMISSION" in share_src
           and "grantUriPermission" in share_src)
check_true("Application context 启动时带 NEW_TASK，Activity 时不带",
           "if (context is Activity) 0 else Intent.FLAG_ACTIVITY_NEW_TASK" in share_src)
check_true("ACTION_SEND + image/png + EXTRA_STREAM",
           "Intent.ACTION_SEND" in share_src and '"image/png"' in share_src
           and "EXTRA_STREAM" in share_src)

check_true("FileProvider 的 posters 路径已声明",
           'name="posters"' in paths_text and 'path="posters/"' in paths_text)
check_true("ViewModel 自己走「今天」（跨午夜不用等界面重组）",
           "private val _today = MutableStateFlow(DayKey.today())" in vm_src)
check_true("跨天时打日志", "跨天：" in vm_src)
check_true("没达标就不该有海报（清掉上一张的解码结果）",
           "if (!isDone(day))" in vm_src and "_poster.value = null" in vm_src)
# 1.2.7：改断言 ensureFile ——「有就返回、没有才渲染」的编排只有一份，
# 打卡页只剩「调它 + 解码」两步
check_true("分享前若没图则先生成", "CheckInPoster.ensureFile(" in vm_src)
check_true("21 天 = 3 周（日历每列落在同一个星期几）",
           "const val CALENDAR_DAYS = 21" in vm_src and "21 天 = 3 周" in vm_src)
check_true("界面只在达标时渲染海报块", "if (done) {" in screen_src and "PosterBlock(" in screen_src)
check_true("重置是「点两次」确认（不用系统弹窗）",
           "onReset = vm::resetAll" in screen_src)

check_true("MainActivity：三页签，第 1 个是打卡", "1 -> CheckInScreen()" in main_src)
# 1.1.0 起底栏换成 pager + 「单一真相源」的 TABS 列表：底栏项与 pager 页数
# 都从这一份定义派生。旧断言查的是 `label = "打卡"`（当时 TabItem 是手写调用点），
# 现在那句已经不存在了 —— 但**「底栏里有打卡」这个事实还是要有断言盯着**，
# 否则哪天有人把打卡项从 TABS 里删掉，这里一声不响。
check_true("MainActivity：TABS 是页签的唯一真相源（底栏与 pager 都从它派生）",
           "TABS.forEachIndexed" in main_src and "pageCount = { TABS.size }" in main_src)
check_true("MainActivity：TABS 里三个页签齐全（素材库 / 打卡 / 设置）",
           all(('"%s" to Glyph.' % lb) in main_src for lb in ("素材库", "打卡", "设置")))
check_true("打卡页图标是自绘的 CalendarCheck（不引图标库）",
           "Glyph.CalendarCheck" in main_src)

check_true("DayKey 用 SimpleDateFormat 锁 Locale.CHINA（不会变英文月名）",
           'SimpleDateFormat("M月d日", Locale.CHINA)' in daykey_text)
check_true("clockLabel 锁 Locale.US（某些区域下 %d:%02d 会出逗号小数）",
           'String.format(Locale.US, "%d:%02d"' in daykey_text)


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
  · DayKey.of：本地零点边界、40 天整点采样与 datetime 逐点对齐、负 millis 的 floorDiv
  · startOfDayMillis：夏令时当天 23 小时 / 25 小时、零点前一毫秒属于前一天
  · Streak：四种情形（今天达标 / 今天未达标但昨天达标 / 断过 / 上周连过但本周没听）
  · addSeconds：2400 阈值边界、只庆祝一次、封顶 24 小时、负数与 0 无效
  · recentDone：旧 → 新、不假设过去都达标
  · ListeningClock：40 分钟恰好达标、暂停/缓冲不计、冻结段整段丢弃、
    销毁时兜尾数、墙钟口径与内容位置无关
  · 跨午夜分账：599 / 1801，两天加起来等于墙钟总量；连听 40 分钟两天都不达标
""" )
