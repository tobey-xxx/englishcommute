#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
「用户点了更新，请求不许被悄悄吃掉」—— 1.2.1 的契约锁。

## 这个 bug 长什么样

1.2.0 的真机表现（用户原话）：

    点「更新今日内容」一直无法更新，反而是点「只检查源」更新了新内容，
    但是没有自动下载音频。

读起来像两个按钮接反了。实际两条路走的根本不是同一个东西：

    「更新今日内容」→ startForegroundService → ContentSyncService → 撞上 work.isActive → **被丢掉**
    「只检查源」    → viewModelScope      → SyncEngine.checkSources()  → 正常

而 1.2.0 给服务加了「中文对照预取」（drainTranslate）之后，服务会在
**转写早已跑完、SyncEngine 完全空闲**的状态下再多占十几分钟 —— 于是
「点更新没反应」变成了常态，而「只检查源」照样能用（它不经过服务）。

## 为什么必须锁在这里

这条链上**没有任何一步会报错**：

  · onStartCommand 丢掉请求时只写一行 DiagLog（用户看不到）
  · 界面拿到的 phase 没有变化（本来就没错，只是没人干活）
  · 通知栏反倒多一条「正在获取今日内容」（见下）

三个环节（登记 → 让位 → 补做）缺任何一个，症状都只是「点了没反应」。
所以本脚本两路验：

  1. **桌面复刻**：把「服务忙 + 用户点更新」的时序在 Python 里跑一遍，
     三个环节各去掉一个，看请求还剩不剩得下来
  2. **源码断言**：三处代码事实 —— 缺任何一处，上面那个模型就不再成立

用法：
    python3 tools/verify-update-queued.py
"""

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
JAVA = ROOT / "app/src/main/java/com/example/englishcommute"

PASS = 0
FAIL = 0
FAILURES = []


def check_true(name: str, ok: bool, why: str = "") -> None:
    global PASS, FAIL
    if ok:
        PASS += 1
        print(f"  ✓ {name}")
    else:
        FAIL += 1
        FAILURES.append(name + (f"  ← {why}" if why else ""))
        print(f"  ✗ {name}" + (f"\n      ↳ {why}" if why else ""))


def section(t: str) -> None:
    print(f"\n{t}")


def kt(rel: str) -> str:
    return (JAVA / rel).read_text(encoding="utf-8")


def strip_comments(src: str) -> str:
    """去掉注释，但保留行数与字符串字面量（同 verify-refactor-120.py）"""
    out = []
    i = 0
    n = len(src)
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
            out.append(src[i:j + 1])
            i = j + 1
        else:
            out.append(src[i])
            i += 1
    return "".join(out)


SVCS = strip_comments(kt("sync/ContentSyncService.kt"))
# ⚠️ 2.1.0：`TRANS = TranslateManager.kt` 删了 —— 逐句翻译那条链路整块拆掉，
# 让位粒度从「1 句」变成了「1 轮」（见下面 PHASES 的说明）。
PVM = strip_comments(kt("ui/PlayerViewModel.kt"))


# ===========================================================================
# 一、桌面复刻：用户点了更新，请求还剩不剩得下来
# ===========================================================================
#
# 时间片 = 让位检查的最小间隔。**2.1.0 起只剩一个阶段。**
#
#   subtitles  补字幕接力 —— 让位检查在**每一轮**的开头
#
# 它原来有两个阶段，而且粒度差得很远：
#
#   translate  中文对照预取 —— 让位检查在**每一句**的开头（1 片 = 1 句 ≈ 0.5s）
#   transcribe 转写接力     —— 让位检查在**每一轮**的开头（1 片 = 1 轮 ≈ 6 min）
#
# 那个「1 句」的细粒度是 1.2.1 最得意的一笔：它把「服务忙但引擎闲」的窗口
# （十几分钟的预取）压缩成最多 0.5 秒的等待。端侧翻译拆掉之后，
# 服务里**已经没有任何按分钟计的工作**了 —— 补一条字幕几百毫秒，
# 一整轮（十几条）也就十几秒，所以「轮」这个粒度已经足够细。
#
# 少了一个阶段不等于少了一层保障：下面那三条控制流（登记 / 让位 / 补做）
# 与「字幕从哪来」毫无关系，它们要防的是**用户的明确动作被静默丢弃**。

SUBTITLE_ROUNDS = 3

PHASES = {
    "subtitles": SUBTITLE_ROUNDS,
}

# 用户点击可以落在阶段的任意一片上 —— 用「点在第几片」表示
V120 = dict(register=False, check_yield=False, resume=False)
V121 = dict(register=True, check_yield=True, resume=True)


def simulate(phase: str, click_at: int, register: bool, check_yield: bool, resume: bool):
    """
    ContentSyncService 在「服务正忙时收到 MODE_SYNC」这条路径上的时序复刻。

    只复刻与本契约有关的三条控制流，其余（拉源、下载、转写本身）不建模：

      register    onStartCommand 里 `if (alreadyWorking) { mode == MODE_SYNC -> queuedSync = true }`
      check_yield drainSubtitles 每轮检查 queuedSync 并 return
      resume      work 主循环里 `if (!queuedSync) break` 之后的 `current = MODE_SYNC`

    返回 (sync_ran, waited_slices)：
      sync_ran      完整同步最终有没有真正跑起来
      waited_slices 从点击到真正跑起来隔了几片；请求丢掉时是 None
    """
    total = PHASES[phase]
    queued = False
    t = 0
    for t in range(total):
        if t == click_at:
            # 用户在这一刻点了「更新今日内容」，而服务正忙
            if register:
                queued = True
            else:
                # 1.2.0：所有模式一视同仁地丢掉。界面无任何变化
                return (False, None)
        # 每一片的末尾都是一个让位点（粒度由 phase 决定）
        if check_yield and queued and t >= click_at:
            break
        # 让位之后服务还要继续跑完这一轮剩下的活，但那与本契约无关

    if not queued:
        return (False, None)
    if not resume:
        # 让位了，但没有任何人补做 —— 请求还是丢了，只是白让了一次 CPU。
        # 这是「只加油门不挂挡」：症状与完全不登记几乎一样
        return (False, None)
    return (True, t - click_at + 1)


def merged_clicks(n: int, register: bool) -> int:
    """
    服务忙碌期间用户连点 n 次。

    待办位是**布尔量**而不是队列：用户点十次，需要的仍然只是「忙完做一次更新」。
    做成队列的话，一次更新要跑十遍 —— 那是比丢掉更糟的结果。
    """
    queued = False
    for _ in range(n):
        if register:
            queued = True
    return 1 if queued else 0


section("一、桌面复刻 · 服务忙时用户点「更新今日内容」")

# —— 1.2.0 的行为（复现用户报的那个 bug）——
check_true(
    "1.2.0：服务跑中文对照预取时点更新 → 请求被吃掉",
    simulate("subtitles", 1, **V120) == (False, None),
    "这正是用户看到的「点了更新一直无法更新」（那一版的窗口长达十几分钟）",
)

# —— 1.2.1 的行为 ——
ran, waited = simulate("subtitles", 1, **V121)
check_true("1.2.1 起：补字幕途中点更新 → 完整同步真的跑起来了", ran)
check_true("  · 等待只有 1 片（= 1 轮）", waited == 1, f"实际 {waited} 片")

# —— 任意点击时刻都不能退化成「等很久」——
waits = [simulate("subtitles", t, **V121)[1] for t in range(PHASES["subtitles"])]
check_true(
    "无论在哪一轮点，等待都恰好是 1 轮",
    set(waits) == {1},
    f"实际分布 {sorted(set(waits))}",
)

# —— 反向对照：请求被丢掉时，用户要等多久 ——
old_waits = [PHASES["subtitles"] - t for t in range(PHASES["subtitles"])]
check_true(
    "请求被丢掉时最长要等整段跑完（这就是「像没反应」的来源）",
    max(old_waits) == SUBTITLE_ROUNDS and min(old_waits) == 1,
    "用户点在最前面时，这一整段里界面上什么都不会发生",
)

# —— 三个环节一个都不能少 ——
check_true(
    "去掉「登记」→ 请求丢掉",
    simulate("subtitles", 1, register=False, check_yield=True, resume=True) == (False, None),
)
check_true(
    "去掉「让位」→ 请求要等整段跑完才轮得到",
    simulate("subtitles", 0, register=True, check_yield=False, resume=True)[1]
    == SUBTITLE_ROUNDS,
    "这就是「服务其实会补做，但用户早就以为坏了」",
)
check_true(
    "去掉「补做」→ 让了位也没人接，请求照样丢",
    simulate("subtitles", 1, register=True, check_yield=True, resume=False) == (False, None),
)
check_true(
    "  · 三个坏样本都没有得到「正常补做」那个结局",
    all(
        bad != (True, 1)
        for bad in (
            simulate("subtitles", 1, register=False, check_yield=True, resume=True),
            simulate("subtitles", 0, register=True, check_yield=False, resume=True),
            simulate("subtitles", 1, register=True, check_yield=True, resume=False),
        )
    ),
)
check_true(
    "  · 丢掉与不补做的结局一致（都是丢），而不让位是「等整段」",
    simulate("subtitles", 1, register=False, check_yield=True, resume=True)
    == simulate("subtitles", 1, register=True, check_yield=True, resume=False)
    == (False, None)
    and simulate("subtitles", 0, register=True, check_yield=False, resume=True)
    == (True, SUBTITLE_ROUNDS),
)

# —— 连点合并 ——
check_true("服务忙碌期间点 1 次 → 补做 1 次", merged_clicks(1, True) == 1)
check_true("服务忙碌期间点 10 次 → 仍然只补做 1 次（布尔量天然合并）", merged_clicks(10, True) == 1)
check_true("  · 1.2.0 的连点：一次都没有", merged_clicks(10, False) == 0)

# —— 「只检查源」之所以看起来像做对了一半 ——
check_true(
    "「只检查源」不经过服务（这条不对称正是误判的根源）",
    "checkSources" in strip_comments(kt("ui/LibraryViewModel.kt"))
    and "ContentSyncService" not in
    strip_comments(kt("ui/LibraryViewModel.kt")).split("fun checkSources()")[1].split("}")[0],
    "它走 viewModelScope，所以服务再忙也不受影响 —— 用户据此以为两个按钮接反了",
)


# ===========================================================================
# 二、源码断言：上面那个模型必须能从真源码里读出来
# ===========================================================================

section("二、源码断言 · 三个环节都在代码里")

# —— 环节 1：登记 ——
check_true("服务里有待办位 queuedSync", "private var queuedSync = false" in SVCS)
check_true(
    "  · 且是 @Volatile（drainSubtitles 在 IO 线程读它）",
    "@Volatile\n    private var queuedSync = false" in SVCS,
)

i_busy = SVCS.find("if (alreadyWorking) {")
i_work_launch = SVCS.find("work = scope.launch {")
busy_branch = SVCS[i_busy:i_work_launch] if 0 <= i_busy < i_work_launch else ""
check_true("「已有任务在跑」是一段独立分支", bool(busy_branch))
check_true(
    "  · MODE_SYNC 在这一分支里被**登记**，不再被丢掉",
    "mode == MODE_SYNC ->" in busy_branch and "queuedSync = true" in busy_branch,
    "1.2.0 在这里对所有权重一视同仁地忽略 —— 那就是 bug 本身",
)
check_true(
    "  · 登记时留了日志（静默失败最难查）",
    '"已有任务在跑 → 登记一次完整同步，本轮收尾后立即执行"' in SVCS,
)
check_true(
    "  · 插队请求那条路径没被这次改动破坏",
    "engine.requestPriority(priority)" in busy_branch,
)

# —— 环节 2：让位 ——
#
# ⚠️ 2.1.0：这里原来查的是**两处**让位点（转写接力 + 中文对照预取），
#    而后者是「每句一次」—— 那是 1.2.1 最细的一处粒度。
#    翻译预取拆掉之后只剩一处，而它仍然在「轮与轮之间」这个正确的粒度上：
#    补字幕一轮（十几条）十几秒，比原来「跑到队列空再让位」短得多。
check_true(
    "drainSubtitles 每轮开头检查待办位",
    "if (queuedSync) {" in SVCS.split("private suspend fun drainSubtitles(")[1]
    .split("private suspend fun backgroundSyncEnabled(")[0],
    "检查放在轮与轮之间：一条字幕取到一半让位没有意义（它只要几百毫秒）",
)
i_rounds = SVCS.find("while (true) {")
i_yield = SVCS.find("if (queuedSync) {", i_rounds)
i_take = SVCS.find("val progressed = engine.fetchPendingSubtitles(", i_yield)
check_true(
    "  · 让位检查排在「真的去取」**之前**",
    0 <= i_rounds < i_yield < i_take,
    "排在后面的话，用户点更新之后还要白跑完这一轮才让位",
)

# —— 环节 3：补做 ——
i_loop = SVCS.find("var current = mode")
i_final = SVCS.find("} finally {", i_loop)
loop_body = SVCS[i_loop:i_final] if 0 <= i_loop < i_final else ""
check_true("work 主体是一个「跑完再看还有没有活」的循环", "while (true) {" in loop_body)
check_true("  · 循环里按 current 分派模式",
           "if (current == MODE_SYNC) {" in loop_body,
           "2.1.0 只剩两个模式（更新 / 补字幕），用 if 比 when 直白；"
           "关键是分派这件事本身还在 —— 没有它，补做完就不知道该跑哪个模式了")
check_true(
    "  · 跑完一轮后消费待办位并改跑 MODE_SYNC",
    "if (!queuedSync) break" in loop_body and "current = MODE_SYNC" in loop_body,
    "少了它，让位就是白让 —— 请求还是丢",
)
check_true(
    "  · 补做时留了日志",
    '"补做被登记的完整同步"' in SVCS,
)
check_true(
    "  · 空闲启动时会清掉残留的待办位",
    "if (mode == MODE_SYNC) queuedSync = false" in SVCS,
    "否则「上一轮的残留」会让新一轮跑完后再多补一次更新",
)

# —— 同一个症状的另一个成因：工作域被复用成「死」的 ——
#
# 每轮收尾都会 scope.cancel()，而 stopSelf() 是异步的：服务实例不保证已销毁。
# 同一个实例再收到一次 startService 时，scope.launch 会拿到一个**已取消**的协程，
# body 一行都不执行 —— 症状与「请求被静默丢弃」一模一样，而且日志里干干净净。
check_true(
    "工作域是可重建的（不是 val）",
    "private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)" in SVCS,
)
check_true(
    "  · 启动新工作前先确认它还活着",
    "if (!scope.isActive) {" in SVCS and "scope = CoroutineScope(SupervisorJob()" in SVCS,
)
check_true(
    "  · 重建时留了日志",
    '"上一轮的工作域已关闭（服务实例被复用）→ 重建"' in SVCS,
)

# —— 通知不许撒谎 ——
# 注意只 find 一次：`startForegroundCompat(` 在文件里第一处出现就是 onStartCommand
# 里的那次调用（定义在文件末尾）。多 find 一次会跳到定义上
i_foreground = SVCS.find("startForegroundCompat(")
fg_block = SVCS[i_foreground:SVCS.find('DiagLog.i("Svc", "已进入前台服务")')]
check_true("截取到了进前台前的那段通知构造", "if (alreadyWorking)" in fg_block)
check_true(
    "被忽略的启动不再用 initialState() 覆盖通知",
    "engine.state.value" in fg_block,
    "1.2.0 会在通知栏留下一条永远停在「正在获取今日内容」的僵尸进度条",
)
# ⚠️ 2.1.0：这里原来还有一条「部署模式的通知读的是真实部署状态」——
#    部署通知整块删了（没有模型要装）。留下的那条「被忽略的启动不覆盖通知」
#    仍然有效，而且**更重要了**：现在服务里跑的都是短任务，
#    「已有任务在跑」这个分支被撞上的概率反而更高。

# —— 让位不能丢进度 ——
#
# ⚠️★ 2.1.0：这一整段原来是围绕「逐句让位」写的 —— 它验的是
# `TranslateResult.Yielded` 这个返回值、`shouldYield` 一路传进逐句循环、
# 以及「让位之前先把已翻好的写盘」。那套机制解决的是**长任务被中途打断后
# 进度全丢**的问题（一集 300 句翻到 250 句时被打断，要从头再来 1–3 分钟）。
#
# 现在没有长任务了：一条字幕的获取是**一个原子动作**（取回来 → 校验句数 →
# tmp+rename 落盘），中途让位最多浪费这一次请求的几百毫秒。
# 于是「进度保管」这件事从「靠返回值 + 增量落盘」变成了「根本不需要」——
# 但下面这条断言仍然要留着：它守的是那个**原子性**，
# 也就是「让位/被杀不会留下半条字幕」。
check_true(
    "字幕落盘是原子的（tmp + rename）——让位/被杀都不会留半条",
    "renameTo" in strip_comments(kt("content/AudioStore.kt")),
    "半条字幕比没有字幕更糟：它看起来是对的，只是缺了后半段。"
    "⚠️ 这条断言上一版被我写成了 `A and B is False or True` —— 那是一个"
    "**恒真**的表达式，等于没有断言。判据只该落在唯一的落盘口 AudioStore 上"
    "（ServerCatalog 也是调它落盘，不自己写文件）",
)
check_true(
    "播放页那条路径不再有逐句让位（因为它不再逐句做事）",
    "shouldYield" not in PVM and "translateCurrent" not in PVM,
    "2.1.0 起播放页只读盘 + 等，没有可以被打断的长循环",
)
check_true(
    "  · 等待用的是有上限的轮询（不是无限等）",
    "TRANSLATION_WAIT_MS" in PVM and "TRANSLATION_POLL_MS" in PVM,
    "没有上限的轮询 = 用户停在这一页就一直在回读盘",
)


# ===========================================================================
# 三、坏样本对照：把真源码做一处外科手术，断言必须被判失败
# ===========================================================================

section("三、坏样本对照 · 断言有区分力")


def has_queued_marker(src: str) -> bool:
    """环节 1：忙时收到 MODE_SYNC 会不会留下待办位"""
    i = src.find("if (alreadyWorking) {")
    j = src.find("work = scope.launch {")
    if i < 0 or j < i:
        return False
    return "queuedSync = true" in src[i:j]


def has_resume(src: str) -> bool:
    """环节 3：主循环跑完一轮会不会补做"""
    i = src.find("var current = mode")
    j = src.find("} finally {", i)
    if i < 0 or j < i:
        return False
    return "if (!queuedSync) break" in src[i:j] and "current = MODE_SYNC" in src[i:j]


def has_yield_in_loop(src: str) -> bool:
    """
    环节 2：让位检查在不在**接力轮次**的开头。

    ⚠️ 2.1.0：它原来查的是 `TranslateManager` 里逐句循环的开头
    （`for (i in lines.indices)` 之后紧跟 `if (shouldYield())`）——
    那个文件整块删了。判据的意思没变（「让位点必须在工作的最前面」），
    换成了在 `drainSubtitles` 的轮循环里查。
    """
    i = src.find("private suspend fun drainSubtitles(")
    if i < 0:
        return False
    j = src.find("private suspend fun backgroundSyncEnabled(", i)
    body = src[i:j] if j > i else src[i:]
    a = body.find("while (true) {")
    b = body.find("if (queuedSync) {")
    c = body.find("val progressed = engine.fetchPendingSubtitles(")
    return 0 <= a < b < c


def changed_lines(a: str, b: str) -> int:
    """
    两个源码串有几行不同。行数不一致返回 -1。

    ⚠️ 不能用「把坏样本 replace 回去看等不等于真源码」来判定 ——
       第一版就是这么写的，而 `queuedSync = false` 在真源码里**第一次出现
       是字段声明**（`private var queuedSync = false`），反解必然命中的是它，
       于是「只差一处」这条断言恒为假。失败的是测试，不是代码。
    """
    la, lb = a.splitlines(), b.splitlines()
    if len(la) != len(lb):
        return -1
    return sum(1 for x, y in zip(la, lb) if x != y)


check_true("真源码：三个环节都在",
           has_queued_marker(SVCS) and has_resume(SVCS) and has_yield_in_loop(SVCS))

BAD_QUEUE = SVCS.replace("queuedSync = true", "queuedSync = false", 1)
check_true("坏样本 A（登记写成 false）被拦下", not has_queued_marker(BAD_QUEUE))
check_true(
    "  · 且坏样本与真源码只差那一处",
    BAD_QUEUE != SVCS and changed_lines(BAD_QUEUE, SVCS) == 1,
    f"实际差 {changed_lines(BAD_QUEUE, SVCS)} 行",
)

BAD_RESUME = SVCS.replace("if (!queuedSync) break", "if (true) break", 1)
check_true("坏样本 B（跑完不检查待办位）被拦下", not has_resume(BAD_RESUME))
check_true(
    "  · 且坏样本与真源码只差那一处",
    BAD_RESUME != SVCS and changed_lines(BAD_RESUME, SVCS) == 1,
    f"实际差 {changed_lines(BAD_RESUME, SVCS)} 行",
)

BAD_YIELD = SVCS.replace("val progressed = engine.fetchPendingSubtitles(",
                         "val progressed = run { if (queuedSync) return; engine.fetchPendingSubtitles(",
                         1)
check_true("坏样本 C（把让位检查挪到取字幕之后）被拦下", not has_yield_in_loop(BAD_YIELD))
check_true("  · 且坏样本与真源码确实不同", BAD_YIELD != SVCS)

# 三个检查器不能是「恒真」的 —— 拿一段无关代码喂进去必须全否
UNRELATED = strip_comments(kt("ui/theme/Motion.kt"))
check_true(
    "三个检查器对无关源码一律判否（不是恒真）",
    not has_queued_marker(UNRELATED)
    and not has_resume(UNRELATED)
    and not has_yield_in_loop(UNRELATED),
)


# ===========================================================================
print()
print("=" * 62)
print(f"通过 {PASS} 项，失败 {FAIL} 项")
if FAIL:
    print("\n❌ 有断言未通过：")
    for f in FAILURES:
        print("  - " + f)
    sys.exit(1)
print("✅ 全部通过")
print("=" * 62)
