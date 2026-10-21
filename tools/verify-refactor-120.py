#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
1.2.0 重构的契约锁。

## 这个脚本盯的是什么

1.2.0 是一次同时动了**性能、内存、动效、版式、文案**五个方向的重构。
它引入的新约定有一个共同特点：**坏掉的时候不报错，只是慢慢变回原样**。

  · 有人在别处再 new 一个 AsrEngine → 峰值 1.26 GB，真机 native OOM，
    没有 Java 堆栈，日志停在「加载识别模型 ▶」
  · 有人给 TranslateManager 加了个直接 close() 的调用点 → use-after-free，
    和 v0.11.0 那次 SIGSEGV 同一个坑
  · 有人在 Root() 顶层重新读 playerUi → 整棵界面树回到 4 次/秒重组，
    界面照样能用，只是又开始掉帧
  · 有人写动画时自己写了个 tween(300) → 三个覆盖层的节奏对不上

这些都不是编译期能发现的，也不是「功能测试」能发现的 ——
功能全都正常，只是变回了重构之前的样子。

用法：
    python3 tools/verify-refactor-120.py
"""

import re
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
    """去掉注释，但保留行数（块注释换成等量换行）"""
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


# ---------------------------------------------------------------------------
section("① 内存：模型实例与释放路径")
# ---------------------------------------------------------------------------

app_src = strip_comments(kt("EnglishCommuteApp.kt"))
sync_src = strip_comments(kt("sync/SyncEngine.kt"))

# ⚠️★ 2.1.0：这一节原来有三块，现在只剩一块 —— 而这是一个**结果**，不是省略：
#
#   ① 「AsrEngine 全工程只被构造 1 次」 —— 那是一条真实的闪退路径的修复
#      （后台转写与设置页自检各 new 一个 630 MB 识别器 → 1.26 GB → 被系统杀掉）。
#      识别器与那个类都没了，没有实例可以数。
#   ② 「TranslateManager 的引用计数必须覆盖整个 translateEpisode」 —— 同理。
#   ③ 海报位图的降采样 —— **仍然有效**，见下。
#
# 前两块的知识没有消失：`verify-crashfix.py` 的 ② 节（已删）留了指针，
# 2.0.4 快照与 1.0 归档里是原源码，skill 里是方法论。
# 它们在服务器架构下**不可能复发** —— 因为手机上不再有任何常驻的大 buffer。

# —— onTrimMemory ——
check_true("Application 实现了 onTrimMemory", "override fun onTrimMemory(level: Int)" in app_src)
i_trim = app_src.find("override fun onTrimMemory")
trim_body = app_src[i_trim:i_trim + 900]
check_true("  · 封面缓存在 UI 不可见时就丢", "container.artwork.trimMemory()" in trim_body)
# ⚠️ 2.1.0：这里原来还有两条 —— 「模型留到 CRITICAL 才放」与「两个模型都走
#    『忙就跳过』的安全释放（直接 close 会在 native 推理中拆 session — v0.11.0
#    的 SIGSEGV）」。两个模型都拆了，所以那些断言的对象不存在。
#    保留「封面在 UI_HIDDEN 就丢」这一条：它是 onTrimMemory 里唯一还在做的事，
#    也是**最容易被人顺手删掉的那一条**（它看起来只是省几 MB）。
check_true("  · 而 onTrimMemory 本身还在（别因为「只是省几 MB」就删掉它）",
           "override fun onTrimMemory" in app_src)
check_true("  · 容器没初始化时直接返回（onTrimMemory 可能早于 onCreate 完成）",
           "::container.isInitialized" in trim_body)

# —— 海报降采样 ——
poster_src = strip_comments(kt("checkin/CheckInPoster.kt"))
check_true("海报有统一的降采样解码入口", "fun decodeForDisplay(" in poster_src)
check_true("  · inSampleSize + RGB_565",
           "inSampleSize = DISPLAY_SAMPLE_SIZE" in poster_src
           and "Bitmap.Config.RGB_565" in poster_src)
for vm in ("ui/CheckInViewModel.kt", "ui/CelebrationViewModel.kt"):
    body = strip_comments(kt(vm))
    check_true(f"  · {vm.split('/')[-1]} 不裸调 BitmapFactory",
               "BitmapFactory.decodeFile" not in body,
               "1080×1440 ARGB_8888 = 5.93 MB，而这两个 VM 都是 Activity 作用域")


# ---------------------------------------------------------------------------
section("② 等待时间：并发与预取")
# ---------------------------------------------------------------------------

repo_src = strip_comments(kt("content/ContentRepository.kt"))
svc_src = strip_comments(kt("sync/ContentSyncService.kt"))

# —— RSS 并发 ——
check_true("RSS 源并发拉取", "FeedSources.ALL.map { source ->" in repo_src
           and "async {" in repo_src)
check_true("  · 有并发上限（不对同一 host 开十来条连接）",
           "Semaphore(FEED_CONCURRENCY)" in repo_src)
check_true("  · 合并留在单线程做（并发改 MutableList 会静默丢数据）",
           "for ((_, items) in fetched)" in repo_src,
           "阶段一只取回 items，阶段二才合并")
check_true("  · 逐源回调还在（界面能显示 3/11 而不是干等）",
           "onSourceDone(result)" in repo_src)
# 冗余预 DNS：11 个源只有 2 个 host，原先每个源都解析一次
i_fetch = repo_src.find("private fun fetchFeed(")
i_call = repo_src.find("client.newCall(", i_fetch)
check_true("成功路径上没有多余的 DNS 解析",
           "InetAddress.getAllByName" not in repo_src[i_fetch:i_call],
           "9 个 NPR 源会重复解析同一个 host，而 Android 的 DNS 缓存 TTL 只有 2 秒")
check_true("  · 失败路径仍然解析（DNS 污染的证据不能丢）",
           "InetAddress.getAllByName" in repo_src[i_call:i_call + 2000])

# —— 下载与取字幕 ——
#
# ⚠️ 2.1.0：这里原来是「下载与转写必须用 Channel 解耦，否则网络空闲 12–18 分钟」
#    （端侧识别一集 4–6 分钟，边下边转会让第二条音频干等）。
#    现在取一条字幕是几百毫秒的 HTTP 请求，那条理由消失了 —— 于是代码
#    改成了**下一条、取一条**的直线写法，少一条协程、少一个 channel。
#    所以这一条断言**反过来**了：不许再有 channel。
check_true("下载与取字幕已经合成一条直线（不再需要 channel）",
           "Channel<Episode>" not in sync_src,
           "留着 channel 就等于留着「谁负责计数」这个分叉 —— "
           "而它只在「取字幕要几分钟」时才划算")

# —— 补字幕那一轮的刹车 ——
#
# 2.1.0 删掉了翻译预取那一整套（drainTranslate / TRANSLATE_* / pendingTranslate）。
# 但服务里那几道刹车**一条都不能少** —— 它们与「字幕从哪来」无关：
check_true("服务里有补字幕接力", "private suspend fun drainSubtitles(" in svc_src)
for guard, why in [
    ("SESSION_BUDGET_MS", "没有上限的循环 + 前台服务 = 「App 在后台无限占用 CPU」"),
    ("backgroundSyncEnabled(app)", "用户关了后台同步就该停"),
    ("shouldStopForBattery()", "电量刹车"),
    ("renewWakeLock()", "每轮续期 —— 不续的话后面几轮会在 CPU 停转时空跑"),
    ("queuedSync", "用户点了更新要能插进来（让位）"),
]:
    check_true(f"  · 有刹车：{guard}", guard in svc_src, why)

# —— 索引落盘 ——
check_true("索引落盘是原子写（tmp + rename）",
           "val tmp = File(indexFile.parentFile, indexFile.name" in repo_src,
           "裸 writeText 被杀会留半截 JSON，而 load() 静默吞异常 → 素材库整个空掉")
check_true("高频更新走防抖", "pendingPersist?.cancel()" in repo_src)
check_true("  · 删除类操作仍然立刻落盘",
           repo_src.count("persistNow()") >= 8,
           "索引晚落盘会出现「索引说有、磁盘上没有」")
i_update = repo_src.find("fun update(episode: Episode)")
check_true("update 不再每次重排整个列表",
           "sortedByDescending" not in repo_src[i_update:i_update + 300],
           "替换单个条目不改变任何 publishedAt，顺序本来就不变")


# ---------------------------------------------------------------------------
section("③ 动效：统一的时长与曲线")
# ---------------------------------------------------------------------------

motion_src = kt("ui/theme/Motion.kt")
check_true("有统一的 Motion 规范", "object Motion" in motion_src)
for name in ("Quick", "Standard", "Large", "EasingStandard", "overlayEnter", "overlayExit"):
    check_true(f"  · 提供 {name}", name in motion_src)

# 各处不许自己写 tween —— 那正是「三个覆盖层节奏对不上」的来源
ui_files = [f for f in (JAVA / "ui").rglob("*.kt")]
offenders = []
for f in ui_files:
    if f.name == "Motion.kt":
        continue
    body = strip_comments(f.read_text(encoding="utf-8"))
    # 烟花那个无限循环时钟必须线性，是唯一豁免
    if f.name == "CheckInCelebration.kt":
        continue
    if re.search(r"\btween\s*\(", body):
        offenders.append(f.relative_to(JAVA).as_posix())
check_true("界面里没有散落的 tween(...)（烟花时钟除外）",
           not offenders,
           f"命中：{offenders} —— 各写各的时长会让节奏对不上")

rule_src = strip_comments(kt("ui/theme/Rule.kt"))
check_true("进度条有补间", "animateFloatAsState" in rule_src,
           "转写按块回调，不补间的话表现是「不动 → 猛跳一格 → 又不动」")
check_true("  · 但留了关掉的开关（连续型进度不该再叠一层动画）",
           "animated: Boolean = true" in rule_src)

lib_src = strip_comments(kt("ui/LibraryScreen.kt"))
check_true("列表增删有动画", "Modifier.animateItem(" in lib_src)
check_true("状态块出现/消失不再硬切",
           "AnimatedVisibility(" in lib_src and "animateContentSize(" in lib_src)

player_src = strip_comments(kt("ui/PlayerScreen.kt"))
check_true("字幕高亮迁移不引起布局位移",
           "lineHeight = SUBTITLE_LINE_HEIGHT" in player_src,
           "原来当前句 26sp / 普通句 24sp，每换一句整列内容抖一下")
check_true("  · 行高是固定常量", "private val SUBTITLE_LINE_HEIGHT = 26.sp" in player_src)
check_true("  · 字号差异保留（手机在手里晃时光靠颜色分辨不出）",
           "if (isCurrent) 17.sp else 15.sp" in player_src)


# ---------------------------------------------------------------------------
section("④ 重组范围")
# ---------------------------------------------------------------------------

main_src = strip_comments(kt("MainActivity.kt"))
pvm_src = strip_comments(kt("ui/PlayerViewModel.kt"))

check_true("PlayerViewModel 暴露了低频派生流",
           "val playingEpisodeId: StateFlow<String>" in pvm_src
           and "val currentLineIndex: StateFlow<Int>" in pvm_src)
check_true("  · 都做了 distinctUntilChanged",
           pvm_src.count("distinctUntilChanged()") >= 2,
           "没有它，上游每 250ms 的发射仍然穿透过来，拆了等于没拆")

# Root() 顶层不许读完整的 playerUi —— 那会让整棵界面树每秒重组 4 次
i_root = main_src.find("private fun Root()")
i_scaffold = main_src.find("Scaffold(", i_root)
check_true("Root() 顶层不读完整播放状态",
           "playerVm.ui.collectAsStateWithLifecycle()" not in main_src[i_root:i_scaffold],
           "pager 预排了相邻页 → 顶层读它等于三个页签一起 4 次/秒重组")
check_true("  · 顶层只取「正在播哪一条」",
           "playerVm.playingEpisodeId.collectAsStateWithLifecycle()" in main_src)
check_true("  · 完整状态下推到 bottomBar 内部",
           "val playerUi by playerVm.ui.collectAsStateWithLifecycle()" in main_src
           and main_src.find("val playerUi by") > i_scaffold)

for vmf, cls in (("ui/LibraryViewModel.kt", "LibraryViewModel"),
                 ("ui/PlayerViewModel.kt", "PlayerViewModel")):
    body = kt(vmf)
    check_true(f"{cls} 标了 @Stable（否则 screen 不可跳过）",
               f"@Stable\nclass {cls}(" in body)


# ---------------------------------------------------------------------------
section("⑤ 文案：不自我引用、不教学、不解释实现")
# ---------------------------------------------------------------------------

SCREENS = ["ui/LibraryScreen.kt", "ui/PlayerScreen.kt",
           "ui/CheckInScreen.kt", "ui/SettingsScreen.kt"]
joined = "\n".join(strip_comments(kt(f)) for f in SCREENS)

BANNED = {
    "一键": "营销腔。按钮的位置和配色已经说明它是主操作",
    "点上面的「": "自我引用 —— 界面在教用户认自己的按钮",
    "这一格已经点亮": "教学式，在替用户感动",
    "不用重启 App": "实现细节",
    "系统会挑这个时刻": "排期逻辑",
    " —— ": "破折号是技术注释的语气，不该出现在界面上",
}
for word, why in BANNED.items():
    check_true(f"界面上没有「{word.strip()}」", word not in joined, why)

# 翻译状态行的 Idle 态不该常驻一句静态说明
i_idle = player_src.find("is TranslateUiStatus.Idle ->")
check_true("翻译状态 Idle 时什么都不画",
           "is TranslateUiStatus.Idle -> Unit" in player_src,
           "那个位置是留给「正在发生的事」的，不是广告位")


# ---------------------------------------------------------------------------
section("⑥ 版式：三页各有重心，公共零件只有一份")
# ---------------------------------------------------------------------------

parts_src = kt("ui/theme/Parts.kt")
for fn in ("fun LinkText(", "fun Metric(", "fun formatBytes("):
    check_true(f"公共零件有 {fn.split('(')[0].split()[-1]}", fn in parts_src)

check_true("素材库刊头与更新动作合成一个 item",
           'item(key = "update")' not in lib_src and 'item(key = "masthead")' in lib_src)
check_true("  · LIBRARY_HEADER_ITEMS 同步改成 3",
           "private const val LIBRARY_HEADER_ITEMS = 3" in lib_src,
           "数错的表现是「退回素材库后滚到了隔壁一行」")

# ⚠️ 2.1.0：u i/ModelSetupScreen.kt → ui/SettingsScreen.kt（改名，见 verify-ui-1210）
setup_src = strip_comments(kt("ui/SettingsScreen.kt"))
check_true("设置页区块间距收紧（密集列表，不是阅读页）",
           "Column(Modifier.padding(top = Space.block)) {" in setup_src)

# 播放页控制区压缩
check_true("倍速滑杆已移除", "steps = SpeedPolicy.SLIDER_STEPS" not in player_src)
check_true("  · ± 微调仍在", "SpeedPolicy.step(ui.speedPercent, -1)" in player_src)
check_true("  · 倍速数值本身就是「回到常速」按钮",
           "pressFeedback(enabled = offDefault, onClick = onResetSpeed)" in player_src)
check_true("  · 上/下一集用 Chevron（与主控排的 SkipPrev/Next 区分开）",
           "IconChip(Glyph.ChevronLeft, enabled = ui.hasPrevious" in player_src,
           "两者形状相近会真的点错")


# ---------------------------------------------------------------------------
section("⑦ 反向对照：坏实现必须被判失败")
# ---------------------------------------------------------------------------
#
# 证明上面那些 ✓ 有区分力。若坏样本也能通过，说明断言没抓住要点。

# ⚠️ 2.1.0：坏实现 A / B 换代了（对象消失，但**要防的错误没消失**）。
#
#   旧 A：「在 ViewModel 里重新 new 一个 AsrEngine」——防的是「同一份大资源被
#         建了两份实例」。识别器没了，但同类错误在新架构下有一个等价的形态：
#         **把「字幕从哪来」的判断抄成第二份**（比如界面自己再算一遍是否需要字幕），
#         两处判据迟早会分叉。
#   旧 B：「onTrimMemory 里直接 close 翻译模型」（v0.11.0 的 SIGSEGV 形态）——
#         防的是「在别人可能正在用的东西上直接动手」。等价形态：直接删文件
#         而不走 `AudioStore` 的原子写（tmp + rename）。
def bad_dup_judgement(src: str) -> bool:
    """返回 True = 被拦下：界面自己重算了一遍「需不需要字幕」"""
    return "needsSubtitles(" in src


check_true("坏实现 A（界面自己重算一遍「需不需要字幕」）被拦下",
           bad_dup_judgement("if (repository.needsSubtitles(ep)) show(\"待补\")"))
check_true("  · 而真源码里这个判据只在仓库里定义",
           "fun needsSubtitles(" in repo_src
           and not bad_dup_judgement(strip_comments(kt("ui/LibraryScreen.kt")))
           and not bad_dup_judgement(strip_comments(kt("ui/SettingsViewModel.kt"))),
           "它只有一个定义点（ContentRepository），界面与引擎都从那里问 —— "
           "两处各写一份必然分叉，而分叉的表现是「列表说没有、引擎说有」")


def bad_atomic(src: str) -> bool:
    """直接 writeText 而不是 tmp + rename = 坏（被杀会留半截文件）"""
    return "writeText(content)" in src and "renameTo" not in src


check_true("坏实现 B（字幕直接裸写，不走原子替换）被拦下",
           bad_atomic("file.writeText(content)"))
check_true("  · 真源码走的是 tmp + rename",
           "renameTo" in strip_comments(kt("content/AudioStore.kt")))

check_true("坏实现 C（顶层读 playerUi）被拦下",
           "playerVm.ui.collectAsStateWithLifecycle()" in
           "val playerUi by playerVm.ui.collectAsStateWithLifecycle()")


# ---------------------------------------------------------------------------
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
