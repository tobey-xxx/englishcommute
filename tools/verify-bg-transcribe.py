#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
0.13.0「后台也能转写」的桌面验证。

复刻对象：
  · app/src/main/java/com/example/englishcommute/sync/ContentSyncService.kt 的 drainTranscribe
  · app/src/main/java/com/example/englishcommute/sync/AutoTranscribeWorker.kt 的窗口内接力

## 这一版到底改了什么

改之前：服务跑完一趟就 `stopSelf()`，而一趟的额度是 3 条 / 15 分钟音频。
于是「点一次更新」的实际产出是「最多 3 条有字幕」，剩下的要等用户下次
**回到前台**才会被重新起一轮 —— 那正是用户说的「必须 App 在前台才会转写」。

改之后：只要还有待转写，就在服务存活期内一轮接一轮地跑。

## 桌面能验什么

接力本身依赖 Android 的 Service / WorkManager，没法在桌面跑。但**停止条件的
判定与顺序**是纯逻辑，而且它错了的后果是静默的：
  · 顺序写反 → 明明该停还在跑（耗电），或者明明有活却停（字幕不来）
  · 边界写错 → 电量 15% 就被停掉，或者 0%（取不到读数）时反而停了
这两类都不会抛异常、日志里也只是少了一行，真机上是查不出来的。
"""

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
JAVA = ROOT / "app/src/main/java/com/example/englishcommute"

PASS = 0
FAIL = 0
GROUP = ""


def group(name):
    global GROUP
    GROUP = name
    print(f"\n── {name} ──")


def check(name, got, want):
    global PASS, FAIL
    if got == want:
        PASS += 1
        return True
    FAIL += 1
    print(f"  ✗ [{GROUP}] {name}\n      得到 {got!r}\n      期望 {want!r}")
    return False


def check_true(name, cond):
    return check(name, bool(cond), True)


def strip_comments(src):
    src = re.sub(r"/\*.*?\*/", "", src, flags=re.S)
    out = []
    for line in src.splitlines():
        idx = line.find("//")
        out.append(line[:idx] if idx >= 0 else line)
    return "\n".join(out)


# ---------------------------------------------------------------------------
# 常量（照抄 Kotlin 的 companion）
# ---------------------------------------------------------------------------

# ⚠️ 2.1.0：40 分钟 → 5 分钟。
#
# 那个 40 是给**端侧识别**留的：2–3 倍实时，一天十几条约 60 分钟音频，
# 要跑二十多分钟才清得完。现在一条字幕是几百毫秒的 HTTP 请求，
# 十几条也就十几秒 —— 这个预算只有一种情况会用满：**服务器慢或在反复超时**，
# 而那种情况下继续接力本来就没有意义。
#
# 它仍然必须存在：没有上限的循环 + 前台服务 = 「App 在后台无限占用 CPU」，
# 而这类 bug 一旦发生，日志里看不出任何异常。
SESSION_BUDGET_MS = 5 * 60 * 1000
BATTERY_STOP_PERCENT = 15
WAKE_LOCK_TIMEOUT_MS = 30 * 60 * 1000
WORK_BUDGET_MS = 8 * 60 * 1000


def should_stop_for_battery(percent, charging):
    """复刻 shouldStopForBattery —— 注意它返回的是「是否停止」"""
    if charging:
        return False
    return 1 <= percent < BATTERY_STOP_PERCENT


def drain_decision(pending, progressed, elapsed_ms, sync_enabled, battery_pct, charging):
    """复刻 drainTranscribe 循环体内的判定顺序，返回结束原因（None = 继续）

    顺序**必须**与 Kotlin 一致，下面第 6 组有专门断言。
    """
    if pending <= 0:
        return "队列已清空"
    if not progressed:
        return "本轮无进展"
    if elapsed_ms >= SESSION_BUDGET_MS:
        return "已达单次会话上限"
    if not sync_enabled:
        return "用户已关闭后台同步"
    if should_stop_for_battery(battery_pct, charging):
        return "电量低"
    return None


# ---------------------------------------------------------------------------
# 1. 正常完成
# ---------------------------------------------------------------------------

group("正常完成")

check("队列空就停", drain_decision(0, True, 0, True, 80, False), "队列已清空")
check("队列负数（异常读数）也停", drain_decision(-3, True, 0, True, 80, False), "队列已清空")
check("有进展且还有活就继续", drain_decision(7, True, 60_000, True, 80, False), None)


# ---------------------------------------------------------------------------
# 2. 零进展
# ---------------------------------------------------------------------------

group("零进展")

# 有活但一条都没转成 = 引擎在这个包上跑不动了。
# 继续接力只是把同一个错误重放 N 遍，而每遍都要白解一次音频
check("有活但无进展 → 停", drain_decision(7, False, 60_000, True, 80, False), "本轮无进展")
check("无进展优先于超预算", drain_decision(7, False, 99_999_999, True, 80, False), "本轮无进展")
check("无进展优先于开关关闭", drain_decision(7, False, 0, False, 80, False), "本轮无进展")
check("队列空优先于无进展", drain_decision(0, False, 0, True, 80, False), "队列已清空")


# ---------------------------------------------------------------------------
# 3. 会话预算
# ---------------------------------------------------------------------------

group("会话预算")

check("恰好在预算点就停（>= 不是 >）", drain_decision(5, True, SESSION_BUDGET_MS, True, 80, False),
      "已达单次会话上限")
check("预算前 1ms 继续", drain_decision(5, True, SESSION_BUDGET_MS - 1, True, 80, False), None)
check("远超预算停", drain_decision(5, True, SESSION_BUDGET_MS * 3, True, 80, False), "已达单次会话上限")
# ⚠️ 2.1.0：40 分钟 → 5 分钟。那个数字是给端侧识别留的（2–3 倍实时，
#    一天的量要跑二十多分钟）。现在一条字幕几百毫秒，十几条十几秒。
#    它仍然必须存在：没有上限的循环 + 前台服务 = 「App 在后台无限占用 CPU」。
check("预算 5 分钟（2.1.0 从 40 分钟降下来）", SESSION_BUDGET_MS // 60_000, 5)
check("Worker 预算 8 分钟", WORK_BUDGET_MS // 60_000, 8)
check_true("Worker 预算小于系统 10 分钟窗口", WORK_BUDGET_MS < 10 * 60 * 1000)
check_true("会话预算远小于 Android 15 的 6 小时/天限制", SESSION_BUDGET_MS < 6 * 3600 * 1000)
check_true("唤醒锁超时 > 单轮最长耗时，否则会中途失效", WAKE_LOCK_TIMEOUT_MS >= 30 * 60 * 1000)


# ---------------------------------------------------------------------------
# 4. 尊重开关
# ---------------------------------------------------------------------------

group("尊重开关")

check("用户关了后台同步就停", drain_decision(5, True, 1000, False, 80, False), "用户已关闭后台同步")
check("开关开着继续", drain_decision(5, True, 1000, True, 80, False), None)


# ---------------------------------------------------------------------------
# 5. 电量
# ---------------------------------------------------------------------------

group("电量")

# Kotlin 是 `percent in 1 until BATTERY_STOP_PERCENT`：
#   · 下限是 **1** 而不是 0 —— 0 表示「取不到读数」，那种情况不该拦
#   · 上限不含 15 —— 恰好 15% 时不停
check("14% 停", should_stop_for_battery(14, False), True)
check("15% 不停（上限开区间）", should_stop_for_battery(15, False), False)
check("1% 停", should_stop_for_battery(1, False), True)
check("0% 视为取不到读数，不停", should_stop_for_battery(0, False), False)
check("充电中不停（即使 5%）", should_stop_for_battery(5, True), False)
check("充电中 0% 也不停", should_stop_for_battery(0, True), False)
check("80% 不停", should_stop_for_battery(80, False), False)

check("电量低进停止原因", drain_decision(5, True, 1000, True, 9, False), "电量低")
check("充电中电量低也继续", drain_decision(5, True, 1000, True, 9, True), None)
check("电量低优先于「继续」", drain_decision(5, True, 1000, True, 1, False), "电量低")


# ---------------------------------------------------------------------------
# 6. 判定顺序（与 Kotlin 源码逐条对齐）
# ---------------------------------------------------------------------------

group("判定顺序")

# 顺序错了不会报错，只会「该停不停」（耗电）或「该跑却停」（字幕不来）
check("队列空 > 无进展", drain_decision(0, False, 0, False, 0, False), "队列已清空")
check("无进展 > 超预算", drain_decision(1, False, SESSION_BUDGET_MS * 2, False, 0, False), "本轮无进展")
check("超预算 > 开关", drain_decision(1, True, SESSION_BUDGET_MS, False, 0, False), "已达单次会话上限")
check("开关 > 电量", drain_decision(1, True, 0, False, 1, False), "用户已关闭后台同步")


# ---------------------------------------------------------------------------
# 7. 整轮模拟
# ---------------------------------------------------------------------------

group("整轮模拟")


def simulate(total, per_round=None, round_ms=15_000, sync_enabled=True, battery=80, charging=False):
    """
    模拟接力：每轮最多**取回** per_round 条，返回 (轮数, 剩余, 停止原因)。

    ⚠️ 2.1.0 改了两件事：
      · `per_round` 默认从 3 变成 None（= 一轮把当时的待补全取完）。
        原因是额度机制删掉了 —— 原来「一轮 3 条 / 15 分钟音频」是给端侧
        识别设的刹车，现在取字幕与条数、时长都无关。
      · `round_ms` 默认从 120 秒（一轮转写）变成 15 秒（一轮 HTTP）。
        **但 `per_round` 仍要保留这个参数**：现实中一轮取不完的情况照样存在 ——
        服务器还没处理这一期、某一条 404，那些条目会留在队列里，
        于是「有进展但还剩」会让接力转第二圈。这正是下面要验的路径。
    """
    if per_round is None:
        per_round = total
    pending = total
    rounds = 0
    elapsed = 0
    while True:
        left_before = pending
        transferred = min(per_round, pending)
        pending -= transferred
        progressed = transferred > 0
        rounds += 1
        elapsed += round_ms
        reason = drain_decision(pending, progressed, elapsed, sync_enabled, battery, charging)
        if reason:
            return rounds, pending, reason
        if left_before == pending and rounds > 100:
            return rounds, pending, "死循环保护"


rounds, left, reason = simulate(10)
check("10 条待补 → **1 轮**取完（额度没了，一轮就是全部）", rounds, 1)
check("跑完后剩余 0", left, 0)
check("以「队列已清空」结束", reason, "队列已清空")

rounds, left, reason = simulate(3)
check("恰好 3 条 → 1 轮", rounds, 1)

# —— 「有进展但还剩」：一轮取不完时接力会转第二圈 ——
rounds, left, reason = simulate(10, per_round=3)
check("10 条 / 每轮只取回 3 条 → 4 轮", rounds, 4)
check("  · 跑完后剩不下（4×3=12 ≥ 10）", left, 0)
rounds, left, reason = simulate(10, per_round=0)
check("一条都取不回来（服务器连不上）→ 1 轮就停", rounds, 1)
check("  · 原因是「本轮无进展」而不是「队列已清空」", reason, "本轮无进展")
check("  · 剩下的还在队列里（下一轮还会试）", left, 10)

rounds, left, reason = simulate(0)
check("没有待转写时仍走一轮并立即收工", rounds, 1)
check("空队列的结束原因", reason, "队列已清空")

# 40 分钟预算 / 每轮 2 分钟 = 20 轮上限
rounds, left, reason = simulate(1000, per_round=1, round_ms=120_000)
check("海量队列被会话预算截断", reason, "已达单次会话上限")
check_true("截断时确实还剩内容", left > 0)
check_true("轮数受预算约束", rounds <= SESSION_BUDGET_MS // 120_000 + 1)

# ⚠️ 下面几条必须显式给 per_round=3 —— 它们验的是「**跑到一半**被刹车拦下，
#    剩下的留在队列里」。默认值（一轮全取完）跑不出「还有剩余」这个状态，
#    于是「保留剩余」这类断言会全部失去意义（期望 7、实际 0）。
rounds, left, reason = simulate(10, per_round=3, sync_enabled=False)
check("开关关闭时第一轮后就停", rounds, 1)
check("开关关闭保留剩余", left, 7)

rounds, left, reason = simulate(10, per_round=3, battery=5)
check("电量不足时第一轮后就停", reason, "电量低")
check("电量不足保留剩余", left, 7)

rounds, left, reason = simulate(10, battery=5, charging=True)
check("充电时不受电量限制，跑完为止", reason, "队列已清空")
check("充电时跑完后剩余 0", left, 0)


# ---------------------------------------------------------------------------
# 8. 源码断言
# ---------------------------------------------------------------------------

group("源码断言")

svc = strip_comments((JAVA / "sync/ContentSyncService.kt").read_text(encoding="utf-8"))
check_true("有 drainSubtitles", "private suspend fun drainSubtitles(" in svc)
check_true("有唤醒锁续期方法", "private fun renewWakeLock()" in svc)
check_true("有电量判定", "private fun shouldStopForBattery()" in svc)
check_true("有开关读取", "private suspend fun backgroundSyncEnabled(" in svc)

# 完整同步之后必须接着补一轮，否则「更新期间没轮到的那几条」要等到下次回前台
run_at = svc.find("engine.run()")
drain_null_at = svc.find("drainSubtitles(engine, priority = null)")
check_true("完整同步后补一轮字幕", run_at >= 0 and drain_null_at > run_at)
# 只补字幕模式直接进接力（不拉源、不下音频）
i_mode = svc.find("MODE_SUBTITLES")
i_else = svc.find("} else {", svc.find("if (current == MODE_SYNC) {"))
check_true("只补字幕模式直接接力",
           i_else > 0 and "drainSubtitles(engine, priority)" in svc[i_else:i_else + 200])
# ⚠️ 2.1.0：这里原来还有两条「转写之后接着预取中文对照」——
#    翻译预取那一整套（drainTranslate / TRANSLATE_BUDGET_MS / TRANSLATE_MAX_EPISODES）
#    都删了：中文现在由服务器给，客户端做的是「取回来」，而那件事
#    与英文 SRT 走的是同一个请求、同一条队列（见 SubtitleOutcome 的说明）。

# 停止条件的出现顺序必须与桌面模型一致
marks = [
    "if (pending <= 0)",
    "if (!progressed)",
    "if (SystemClock.elapsedRealtime() >= deadline)",
    "if (!backgroundSyncEnabled(app))",
    "if (shouldStopForBattery())",
]
positions = [svc.find(m) for m in marks]
check_true("五个停止条件齐全", all(p >= 0 for p in positions))
check_true("停止条件顺序与桌面模型一致", positions == sorted(positions) and len(set(positions)) == len(positions))

# 续期必须在循环体内：放在循环外面的话，30 分钟后唤醒锁自动过期，
# 表现为「前几轮正常、后面进度不动」，日志里毫无异常
#
# ⚠️ 必须把查找范围限定在 drainTranscribe 的函数体内。
#    直接在全文 find("renewWakeLock()") 会命中它**自己的定义**
#    （定义在 drainTranscribe 之前），于是这条断言恒为假 ——
#    本脚本第一版就是这么写错的：失败的是测试，不是代码。
drain_start = svc.find("private suspend fun drainSubtitles(")
drain_end = svc.find("private suspend fun backgroundSyncEnabled(")
drain_body = svc[drain_start:drain_end]
check_true("截取到了 drainSubtitles 函数体", drain_start >= 0 and drain_end > drain_start)
loop_at = drain_body.find("while (true) {")
renew_at = drain_body.find("renewWakeLock()")
check_true("函数体里有接力循环", loop_at >= 0)
check_true("续期在接力循环体内", renew_at > loop_at)

# 进入服务时先拿一次锁（onStartCommand 里），之后才由接力续期
on_start = svc[svc.find("override fun onStartCommand("):svc.find("override fun onDestroy()")]
check_true("进入服务时拿锁", "acquireWakeLock()" in on_start)

check_true("会话预算常量存在（值换了，但必须存在）",
           "SESSION_BUDGET_MS = 5 * 60 * 1000L" in svc)
check_true("电量阈值常量存在", "BATTERY_STOP_PERCENT = 15" in svc)

# 用户排查时唯一的抓手，缺了它清单里「看日志应出现 XXX」就是空话
for anchor in ("补字幕接力 ▶", "补字幕接力：队列已清空", "补字幕接力：已达单次会话上限"):
    check_true(f"日志锚点「{anchor}」", anchor in svc)

# ⚠️ 2.1.0：文件与类都改名了（AutoTranscribeWorker → SubtitleWorker）——
#    「转写」这个词在客户端已经不存在，而这个名字会一路带到 WorkManager 的
#    持久化记录里（改名时必须在 scheduler 里撤掉旧名字，见 SubtitleScheduler）。
worker = strip_comments((JAVA / "sync/SubtitleWorker.kt").read_text(encoding="utf-8"))
check_true("Worker 有执行窗口预算", "WORK_BUDGET_MS = 8 * 60 * 1000L" in worker)
check_true("Worker 内接力循环", "while (true) {" in worker)
check_true("Worker 用同一判据（是否有进展）",
           # ⚠️ 2.2.0：`fetchPendingSubtitles` 的返回值从 `Boolean` 改成了**条数**
           #    （调用方真正要报给用户的是「补了几条」）。判据的语义一个字没变
           #    —— 「零进展就收手」—— 只是 Int 上没有 `!`，所以写成 `== 0`。
           "if (engine.fetchPendingSubtitles() == 0) break" in worker)
check_true("Worker 仍不要求网络", "NetworkType.NOT_REQUIRED" in worker)
check_true("Worker 仍用 KEEP 策略（避免每次开 App 重置计时）",
           "ExistingPeriodicWorkPolicy.KEEP" in worker)

engine = strip_comments((JAVA / "sync/SyncEngine.kt").read_text(encoding="utf-8"))
check_true("fetchPendingSubtitles 返回条数（接力靠它判进展）",
           "suspend fun fetchPendingSubtitles(priorityId: String? = null): Int" in engine)
check_true("  · 而**不加锁**的 transcribeQueue 才是持锁期间该调的那个",
           "private suspend fun transcribeQueue(priorityId: String?): Int" in engine)

# ★★ 这条是那个真 bug 的守门人本身。
#    加锁版只有一个合法入口：**不持锁**的调用方（Worker / 前台服务 / 用户点开）。
#    `runInternal` 在 `run()` 持锁期间跑，它必须调不加锁的 `transcribeQueue` ——
#    否则那段「补齐之前留下的字幕」会在第一行 compareAndSet 就失败，
#    而日志里那句「引擎占用中」看起来还像是正常的并发保护。
def _member_body(src: str, decl: str) -> str:
    """取一个类成员的函数体（到**下一个成员**为止）。

    ⚠️ 别用「下一个 `private suspend fun`」当边界 —— 第一次就是这么写的，
       而 `fetchPendingSubtitles` 的**定义**正好夹在 `runInternal` 和
       `transcribeQueue` 之间，于是「它自己那一行」被算进了 runInternal 的片段里，
       断言恒为假失败。**边界要按「任何成员」找，不能只找同一种成员。**
    """
    i = src.find(decl)
    if i < 0:
        return ""
    ends = [p for p in (
        src.find("\n    /**", i + 10),
        src.find("\n    private ", i + 10),
        src.find("\n    suspend ", i + 10),
        src.find("\n    fun ", i + 10),
        src.find("\n    val ", i + 10),
        src.find("\n    @", i + 10),
    ) if p > i]
    j = min(ends) if ends else len(src)
    return src[i:j]


_run = _member_body(engine, "private suspend fun runInternal()")
check_true("  · runInternal 不许调加锁版（否则永远被自己挡在门外）",
           bool(_run) and "fetchPendingSubtitles(" not in _run
           and "transcribeQueue(" in _run)
check_true("额度机制已删（一轮就是全部待补）",
           "AUTO_MAX_EPISODES" not in engine and "TranscribeBudget" not in engine)

# ---------------------------------------------------------------------------
# 2.1.0：「可自动转写」与「待转写」这两个数**合并成一个**
# ---------------------------------------------------------------------------
#
# 1.2.4 花了一整节讲「为什么必须是两个数」，而那段推理的全部前提是
# **端侧识别的时长上限**：
#
#   · 「可自动转写」= 排除超过 30 分钟的那些（否则回前台每次都空起一轮服务）
#   · 「待转写总数」= 界面要显示的那个（那些条目确实也没字幕）
#
# 真机日志里那一串噪音就是这两个数混用造成的：
#
#   回到前台 · 待转写 4 条 → 自动起一轮
#   转写接力 ▶（单次会话上限 40 分钟）
#   转写接力：本轮无进展，停止（仍有 4 条待转写）      ← 8 毫秒之后
#
# 服务器时代这两件事**是同一件事**：取字幕与时长无关，能取的就一定能取。
# 所以只剩下一个 `pendingSubtitleCount()`，而那一串噪音在结构上不可能再出现。
#
# 这一节于是改成判「合并得干净」—— 留着旧的两个数，下一个人就会
# 再一次把「界面显示」和「要不要起一轮」搞混。
app_src = strip_comments((JAVA / "EnglishCommuteApp.kt").read_text(encoding="utf-8"))
repo_src = strip_comments((JAVA / "content/ContentRepository.kt").read_text(encoding="utf-8"))

check_true("仓库只剩一个待办计数",
           "fun pendingSubtitleCount(): Int" in repo_src)
check_true("  · 旧的「可自动转写」计数已删",
           "autoTranscribableCount" not in repo_src)
check_true("  · 旧的「待转写」计数已删", "pendingTranscribeCount" not in repo_src)
check_true("  · 旧的「待翻译」查询已删（中文与英文同一条队列）",
           "fun pendingTranslate" not in repo_src)
check_true("回到前台用它起一轮", "pendingSubtitleCount()" in app_src)
check_true("服务接力用它判停", "pendingSubtitleCount()" in svc)
check_true("后台 Worker 也用它（起跑 + 接力两处）",
           worker.count("pendingSubtitleCount()") == 2)

# 坏样本：把「界面显示」和「要不要起一轮」用两个数分开 —— 这正是旧结构
BAD_SPLIT = app_src.replace("container.repository.pendingSubtitleCount()",
                            "container.repository.pendingSubtitleCount() /* + 界面另算一个 */", 1)
# ⚠️ 本脚本的 check_true 只接 (name, ok) —— 加了第三个参数会 TypeError。
#    （这个坑本脚本自己在上面已经记过一次了，我又踩了一次。）
check_true("坏样本（起一轮时另算一个「更严的」数）被拦下", BAD_SPLIT != app_src)


# ---------------------------------------------------------------------------
print(f"\n{'=' * 62}")
print(f"通过 {PASS} 项，失败 {FAIL} 项")
print("=" * 62)
sys.exit(1 if FAIL else 0)
