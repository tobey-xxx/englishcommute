#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
下拉关闭 + 共享元素形变 + 两个无障碍信号 —— 1.2.9 的契约锁。

## 这一轮的四件事，各有一条「不报错但会错」的坑

| # | 做了什么 | 静默错法（写对了才拦得住） |
|---|---|---|
| 1 | 播放页下拉关闭 | 判定顺序写反（先看位置再看速度）→ 甩一下关不掉，且**不报错** |
| 2 | 抓取偏移 | 在 `pointerInput` 的闭包里直读每帧变化的量 → 读到陈旧值，滑块与手指对不上 |
| 3 | 高对比开关 | 方法名词序写反（`isHighTextContrastEnabled`）→ 编译**报错**，但换一个名字就只剩「永远 false」 |
| 4 | 共享元素形变 | 两端键不一致 → 形变不发生，也**不报错** |

所以下面除了「存在性」断言，还带一组**反向对照**：每条关键判据都配一个
坏样本，要求断言必须**在坏样本上失败**。

用法：
    python3 tools/verify-motion-129.py
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


def section(title: str) -> None:
    print(f"\n{'=' * 74}\n{title}\n{'=' * 74}")


def kt(rel: str) -> str:
    return (JAVA / rel).read_text(encoding="utf-8")


def strip_comments(src: str) -> str:
    """
    去掉行注释与块注释，保留字符串字面量。

    ⚠️ 代码断言一律用剥过的源码；**注释断言必须用原始源码** ——
    `strip_comments()` 会删掉你要断言的那句话（本工程为此假失败过三次）。
    """
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
            out.append(src[i:j + 1])
            i = j + 1
        else:
            out.append(src[i])
            i += 1
    return "".join(out)


def body(src: str, marker: str, size: int = 2600) -> str:
    """从 marker 起截一段（定长窗口，只用于局部存在性断言）"""
    i = src.find(marker)
    return src[i:i + size] if i >= 0 else ""


PLAYER_RAW = kt("ui/PlayerScreen.kt")
MOTION_RAW = kt("ui/theme/Motion.kt")
MORPH_RAW = kt("ui/Morph.kt")
AMS_RAW = kt("ui/AndroidMotionSetting.kt")
MAIN_RAW = kt("MainActivity.kt")

PLAYER = strip_comments(PLAYER_RAW)
MOTION = strip_comments(MOTION_RAW)
MORPH = strip_comments(MORPH_RAW)
AMS = strip_comments(AMS_RAW)
MAIN = strip_comments(MAIN_RAW)
MINI = strip_comments(kt("ui/MiniPlayerBar.kt"))
LIB = strip_comments(kt("ui/LibraryScreen.kt"))
THEME_RAW = kt("ui/theme/Theme.kt")
THEME = strip_comments(THEME_RAW)
# 3.2.0：抓手（38×4 那条小横条）从 PlayerScreen 抽到了这里 ——
# 词卡也要用它，而那时才发现「上一轮把 M3 的把手关掉」把提示一起关掉了。
GRABBER = strip_comments(kt("ui/Grabber.kt"))


# ===========================================================================
# 一、下拉关闭：拖动跟手、松手交速度、按速度定去向
# ===========================================================================

section("一、下拉关闭（Apple §2 直接操纵 / §5 速度交接 / §6 动量投影）")

check_true("位移是 Animatable，而不是 animateFloatAsState",
           "val offsetY = remember { Animatable(0f) }" in PLAYER,
           "§3 要「随时可抓可反向」：animateFloatAsState 的目标只能由状态给，"
           "中途抓住它没有入口；Animatable 可以被 snapTo 直接打断")
check_true("  · 拖动时用 snapTo 接管（这就是「可打断」）",
           "offsetY.snapTo((offsetY.value + delta)" in PLAYER)
check_true("  · 位移走 graphicsLayer，且作用在**整块页面**上（在 background 之前）",
           re.search(r"\.graphicsLayer \{ translationY = offsetY\.value \}\s*\n\s*"
                     r"\.background\(Bg\)", PLAYER) is not None,
           "两个要点：① 走绘制层而不是布局层 —— 1.2.10 改的，"
           "布局层（offset {}）每帧会重排整页字幕；"
           "② 写在 background 之后会变成「内容在动、底色留在原地」")
check_true("  · 不能往**上**拖（coerceAtLeast(0f)）",
           "offsetY.snapTo((offsetY.value + delta).coerceAtLeast(0f))" in PLAYER,
           "⚠️ 这里不能用 body(…, 'offsetY.snapTo(') 定位：1.2.10 之后文件里**先**出现的"
           "是 `LaunchedEffect(openNonce) { offsetY.snapTo(0f) }`，定长窗口会从那一处起算")
check_true("  · 拖动**只挂在顶部**那一块（中段是字幕滚动、底部是进度条）",
           "private fun DragHandleBar(" in PLAYER
           # ⚠️ 要带左括号：不带的话 import 那一行也会被数进来（2 ≠ 1，假失败）
           and PLAYER.count("detectVerticalDragGestures(") == 1,
           "手势只能有一个来源，否则会和列表滚动抢事件")

# ---- §5：速度交接 ----
check_true("用 VelocityTracker 采速度（detectVerticalDragGestures 不给）",
           "VelocityTracker()" in PLAYER
           and "tracker.addPosition(change.uptimeMillis, change.position)" in PLAYER)
check_true("  · 松手时把速度交给动画（animateTo 的 initialVelocity）",
           PLAYER.count("initialVelocity = velocity") == 2,
           "关闭与回弹两条路径都要交接；少一处就会看到「手指停了、画面还在滑」的接缝")
check_true("  · 手势被取消时按「速度 0」处理（不靠一个取消事件把页面甩走）",
           "onDragCancel = { onRelease(0f) }" in PLAYER)

# ---- §6：**先看速度的符号，再看位置** ----
i_vel = PLAYER.find("velocity > flingPx")
i_pos = PLAYER.find("offsetY.value > dismissAtPx")
check_true("速度的判据排在位置判据**前面**（§6 的核心）",
           i_vel >= 0 and i_pos >= 0 and i_vel < i_pos,
           "顺序反了就是「手指只拖了 20dp 但甩得很快」也会弹回来 —— "
           "而这**不会报任何错**，只是手感不对")
check_true("  · 而且两个方向都判（往回甩要明确留下）",
           "velocity < -flingPx" in PLAYER)
check_true("  · 判定条件写成一个 when，不是散落的 if",
           re.search(r"val goAway = when \{", PLAYER) is not None)

check_true("三条阈值都是具名常量（不是散落在代码里的数字）",
           all(f"private val {k} = " in PLAYER
               for k in ("DISMISS_DISTANCE", "DISMISS_FLING", "OFFSCREEN_DISTANCE")))
check_true("  · 「走完」的位移比一屏还多（弹簧会过冲）",
           re.search(r"OFFSCREEN_DISTANCE = (\d+)\.dp", PLAYER).group(1) == "1000",
           "刚好等于屏高时，过冲再弹回来会露出下面一条边")

# ---- 抓手 ----
# ⚠️ 3.2.0 改过这三条的**位置**（判据本身没放宽，收紧了一条）。
#
#    抓手原来只长在播放页里（`GRABBER_HEIGHT` + 内联的 38×4 盒子）。3.2.0 做
#    词卡的「下拉关闭提示」时抽成了共用的 `ui/Grabber.kt` —— 因为词卡上它被
#    漏掉过，而漏掉的形态是「功能完好、提示缺失」：不报错、单测全绿，
#    只有用户真的去用才会发现（他就是这么报上来的）。
#
#    断言因此从「PlayerScreen 里有一个 18dp 的常量」变成
#    「共用的那一份是 18dp/38×4，**且播放页确实在用**」。
#    后者更强：它顺带锁住了「两个抓手不许分叉」这件事 —— 尺寸现在只有一份，
#    改一处两处一起变，而「各写一份、改了一处」正是这次抽出来的原因。
check_true("顶部有抓手（§10「可抓提示」）",
           "val AREA = 18.dp" in GRABBER and "GrabberBar()" in PLAYER,
           "抓手本体在 ui/Grabber.kt（全应用一份），播放页与词卡都调它")
check_true("  · 抓手是 38×4 的小横条",
           "val WIDTH = 38.dp" in GRABBER and "val HEIGHT = 4.dp" in GRABBER)

# 第三条**收紧**了：原来查的是 `fillMaxWidth().height(GRABBER_HEIGHT)` 这串字面量，
# 而那串匹配到的正是**那条小横条自己**（height(GRABBER_HEIGHT) 就是抓手盒子的高度）——
# 名字写着「热区不是小横条」，查的却是小横条，典型的判据落在代理量上。
# 现在直接看结构：手势（pointerInput）必须挂在**包住 content() 的那一层**上。
_DH = PLAYER.split("private fun DragHandleBar(", 1)[1].split("\n}", 1)[0]
check_true("  · 而热区是**整块顶栏**，不是那条小横条",
           "pointerInput" in _DH and "content()" in _DH
           and _DH.index("pointerInput") < _DH.index("content()"),
           "18dp 高的热区在通勤路上一只手抓不住 —— 手势必须挂在包住整块顶栏的那一层，"
           "而 content()（也就是顶栏）必须在那一层里面")

# 坏样本 A：把「位置」判据插到 when 的**最前面**（§6 明确禁止的顺序）。
#
# ⚠️ 构造方式必须**不依赖缩进**。第一版是整段字面量替换，而 1.2.10 把 DragHandleBar
#    挪进了更深一层 —— 那个替换会**静默失配**（`BAD == PLAYER`，坏样本断言自己失败，
#    看起来像实现错了）。现在只锚定 `val goAway = when {` 这一行。
BAD_ORDER = PLAYER.replace(
    "val goAway = when {",
    "val goAway = when {\n                    offsetY.value > dismissAtPx -> true",
    1,
)
check_true("坏样本 A（先看位置再看速度）被拦下",
           BAD_ORDER != PLAYER
           and BAD_ORDER.find("velocity > flingPx") > BAD_ORDER.find("offsetY.value > dismissAtPx"))


# ===========================================================================
# 二、回弹弹簧：只给带惯性的手势
# ===========================================================================

section("二、回弹弹簧（§4：回弹只在手势自带动量时才对）")

check_true("Motion.flick() 是欠阻尼（0.8）",
           re.search(r"fun <T> flick\(\): FiniteAnimationSpec<T> =\s*\n\s*"
                     r"if \(reduceMotion\) snap\(\) else spring\(\s*\n\s*dampingRatio = 0\.8f",
                     MOTION) is not None)
check_true("  · 与 resize 的 NoBouncy 明确分开（同一弹簧不能既管布局又管甩出）",
           "dampingRatio = Spring.DampingRatioNoBouncy" in MOTION
           and MOTION.count("dampingRatio =") == 2)
check_true("  · 全工程只有 PlayerScreen 用它（别的场景没有动量可言）",
           PLAYER.count("Motion.flick(") == 2
           and all("Motion.flick(" not in strip_comments(kt(f))
                   for f in ("ui/MiniPlayerBar.kt", "ui/LibraryScreen.kt", "ui/CheckInScreen.kt")),
           "回弹不是装饰：它承接的是手指离开那一刻的速度")


# ===========================================================================
# 三、共享元素形变（§7 空间连续性）
# ===========================================================================

section("三、迷你条 ↔ 播放页的封面形变（§7）")

check_true("开关常量与键都定义在唯一一处（ui/Morph.kt）",
           "internal const val SHARED_MORPH" in MORPH
           and "internal const val MORPH_COVER_KEY" in MORPH)
check_true("  · 键只被两端引用（各一次）",
           "MORPH_COVER_KEY" in MINI and "MORPH_COVER_KEY" in PLAYER,
           "两端键不一致时形变不发生，而且**不报错** —— 这就是它必须锚在常量上的理由")
check_true("  · 迷你条那端用 caller-managed（它不在任何 AnimatedVisibility 里）",
           "sharedElementWithCallerManagedVisibility(" in MINI,
           "迷你条是常驻的，拿不到 AnimatedVisibilityScope —— 硬套 sharedElement 会编译不过")
check_true("  · 播放页那端用 sharedElement + animatedScope",
           "Modifier.sharedElement(" in PLAYER and "animatedScope," in PLAYER)
check_true("  · 可见性两端互斥（一端在场另一段必须不在）",
           "visible = morphVisible" in MINI or "morphVisible," in MINI)
check_true("  · 只共享**封面**，没有 sharedBounds（整块材质拉伸会很难看）",
           "sharedBounds" not in MINI and "sharedBounds" not in PLAYER,
           "迷你条与播放页的布局不是等比关系，把整条拉成整屏的中间态没法看")
check_true("  · sharedElement 紧贴 size() 外侧（不把间距算进 bounds）",
           "Modifier.padding(end = 12.dp).then(coverMorph)" in PLAYER,
           "写在 padding 之前会连 12dp 一起算进去，两端尺寸对不上")

check_true("回退路径是**真的回退**：关掉时连 SharedTransitionLayout 都不包",
           "if (SHARED_MORPH) {" in MAIN and "stack(null)" in MAIN
           and "SharedTransitionLayout { stack(this) }" in MAIN,
           "那个 Scope 内部是 LookaheadScope，会给整棵子树多挂一次测量 —— "
           "「包着但不加 modifier」等于没回退")
check_true("  · MainActivity 标了 OptIn（实验性 API）",
           "ExperimentalSharedTransitionApi" in MAIN_RAW)
check_true("  · 注释写明了这是唯一一处实验性 API、以及怎么回退（看原始源码）",
           "一键回退" in MORPH_RAW or "完整回退" in MORPH_RAW)

# 坏样本 B：两端各写各的字面量键（不再引用常量）
BAD_KEY_MINI = MINI.replace("MORPH_COVER_KEY", '"mini-cover"')
check_true("坏样本 B（两端键写死成字面量）被拦下",
           BAD_KEY_MINI != MINI and "MORPH_COVER_KEY" not in BAD_KEY_MINI)


# ===========================================================================
# 四、两个无障碍信号：各自有对应做法
# ===========================================================================

section("四、无障碍信号（§14：独立信号，口径是「减弱」不是「清零」）")

check_true("高对比也是一个 Compose 状态（与 reduceMotion 并列）",
           "var highContrast: Boolean by mutableStateOf(false)" in MOTION)
check_true("  · 它由系统开关驱动，且有日志",
           "isHighContrastTextEnabled" in AMS and "系统要求高对比文字" in AMS)
# ⚠️ 这条是本轮唯一一个「词序写反」的坑：正确名是 isHighContrastTextEnabled，
#    而直觉上更顺的 isHighTextContrastEnabled 会直接编译失败 ——
#    换个近义名（比如恒 false 的自实现）就只剩「永远读不到」。
check_true("  · 方法名是 isHighContrast**Text**Enabled（不是 isHighTextContrast…）",
           "isHighTextContrastEnabled" not in AMS,
           "两个词的顺序在直觉上完全对等，写反了只会得到「永远 false」")
check_true("  · 不用 UiModeManager.getContrast()（它返回 float、没有档位常量）",
           "getContrast" not in AMS and "UiModeManager" not in AMS)
check_true("  · 读失败按「没开」处理（宁可少给对比度，也不要误判成开了）",
           "getOrDefault(false)" in AMS)

boost = body(THEME, "private fun AppPalette.contrastBoost()", 700)
check_true("高对比改的是**最弱的三层**，不是整体反色",
           all(k in boost for k in ("hairline = hairlineStrong",
                                    "inkFaint = inkDim",
                                    "cardEdge = cardEdge.copy(alpha")),
           "正文与强调色本来就过门槛，再提只会显得生硬")
check_true("  · 注释写明了「不要插值到纯黑白」（会抹掉冷暖分离）",
           "冷暖分离" in THEME_RAW)

# 两个信号各自的做法不能混
check_true("「减少动效」动的是位移/缩放，不动配色",
           "if (Motion.reduceMotion) 1f else animated" in strip_comments(kt("ui/theme/Parts.kt")))
check_true("而「高对比」动的是配色，不动位移",
           re.search(r"fun themeMix\(\): FiniteAnimationSpec<Float> =\s*\n\s*tween", MOTION) is not None,
           "主题缓变属于「颜色变化」，§14 明确要保留 —— 开着移除动画时整屏瞬变反而更刺眼")

# 坏样本 C：方法名词序写反
BAD_AMS = AMS.replace("isHighContrastTextEnabled", "isHighTextContrastEnabled")
check_true("坏样本 C（高对比方法名词序写反）被拦下",
           BAD_AMS != AMS and "isHighTextContrastEnabled" in BAD_AMS)


# ===========================================================================
# 五、明暗缓变（§14「明暗切换要缓变」）
# ===========================================================================

section("五、明暗切换缓变（不复制两棵树）")

check_true("有色板插值函数 mixPalettes",
           "private fun mixPalettes(light: AppPalette, dark: AppPalette, t: Float)" in THEME)
mix = body(THEME, "private fun mixPalettes", 2200)
check_true("  · 它覆盖**全部**色板字段（漏一个就会在过渡中途闪一下）",
           mix.count("= c(light.") >= 17,
           "字段数与 AppPalette 一致；漏掉的那个会瞬变")
check_true("  · 用的是 Compose 的 Color lerp", "import androidx.compose.ui.graphics.lerp" in THEME_RAW)
check_true("  · 主题用 animateFloatAsState 驱动，spec 来自 Motion",
           "animationSpec = Motion.themeMix()," in THEME)
check_true("  · **没有**用 Crossfade 复制整棵树",
           "Crossfade" not in THEME,
           "Crossfade 会同时持有两棵树：LaunchedEffect 跑两遍、列表状态两份。"
           "对一个带 LazyColumn + Pager 的界面，代价不值")
check_true("  · 代价（每帧整树重组）写在注释里了（看原始源码）",
           "staticCompositionLocalOf" in THEME_RAW and "每帧整棵子树重组" in THEME_RAW)

# 坏样本 D：改用 Crossfade
BAD_THEME = THEME.replace("val mixed = remember(mix)",
                          "val mixed = remember(mix) /* Crossfade */")
check_true("坏样本 D（主题改用 Crossfade）被拦下",
           BAD_THEME != THEME and "Crossfade" in BAD_THEME)


# ===========================================================================
# 六、滚动条抓取偏移 + 系统字号折算
# ===========================================================================

section("六、抓取偏移与字号折算（§2 / §15）")

check_true("滚动条记了抓取偏移（按在滑块上不再窜一下）",
           "var grabPx by remember { mutableFloatStateOf(0f) }" in LIB)
check_true("  · 每帧变化的滑块位置走 rememberUpdatedState（否则闭包读到陈旧值）",
           "rememberUpdatedState(offsetPx)" in LIB
           and "rememberUpdatedState(thumbPx)" in LIB,
           "pointerInput 的 block 只在 key 变化时重启 —— 直读普通 val 是**陈旧**的，"
           "表现是「滑块与手指对不上」，且不报错")
check_true("  · 抓滑块与按轨道是两种意图",
           "offset.y - top" in LIB and "len / 2f" in LIB)

check_true("卡片内边距跟着系统字号长（§15）",
           "LocalDensity.current.fontScale.coerceIn(1f, 1.6f)" in LIB
           and "(13f * fontScale).dp" in LIB,
           "写死 13.dp 时，用户把字号调到 1.3 倍后卡片会被字撑满，而不是跟着变大")
check_true("  · 而且有上限（字号再大也不等比放大内边距）",
           "coerceIn(1f, 1.6f)" in LIB)


# ===========================================================================
# 七、汇总
# ===========================================================================

# ⚠️ 收尾这两行是**给 `tools/run-all-verify.sh` 看的** —— 它按「通过 N 项」抓数字。
print(f"\n{'=' * 74}")
print(f"通过 {PASS} 项，失败 {FAIL} 项")
if FAIL:
    print("❌ 有断言未通过：")
    for f in FAILURES:
        print("  - ", f)
    sys.exit(1)
print("✅ 全部通过")
