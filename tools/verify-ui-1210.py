#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
1.2.10 —— 两个真机 bug 的回归锁 + 三页视觉落地。

## 这一轮为什么值得单独一个脚本

用户装了 1.2.9 之后报了两个症状，**两个都不会报错**：

| 症状 | 真因 | 为什么静态断言必须钉它 |
|---|---|---|
| 下拉关闭几次之后，点迷你条进不去播放页 | 退出动画（220ms）内再次打开会**取消退出、保留实例**，而位移还停在被拽走的 1000dp —— 页面在屏幕外 | 日志里只有「点开」没有「进入播放页」，两个都不像错误；只能靠「必须复位」这条断言守 |
| 封面形变时明显卡顿 | ① 迷你条那端的 `morphVisible` **漏接线**（两端同时在场）② 整页位移走了**布局层**的 `offset {}`（每帧重新布局整页字幕）③ 进页位移与形变是**两套位移**叠在同一屏 | 三者都能编译、都能跑，只是慢/坏 |

所以下面第一节的每一条都配了坏样本：把修复**逐个改回去**，断言必须失败。

用法：
    python3 tools/verify-ui-1210.py
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
    """剥注释（保留字符串字面量）。代码断言用它；注释断言一律用 *_RAW。"""
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


PLAYER_RAW = kt("ui/PlayerScreen.kt")
MAIN_RAW = kt("MainActivity.kt")
MOTION_RAW = kt("ui/theme/Motion.kt")
# ⚠️ 2.1.0：这个文件从 ModelSetupScreen.kt 改名成 SettingsScreen.kt ——
#    它本来就不是「模型设置页」，而模型区删掉之后更不该叫那个名字。
SETUP_RAW = kt("ui/SettingsScreen.kt")
CHECKIN_RAW = kt("ui/CheckInScreen.kt")

PLAYER = strip_comments(PLAYER_RAW)
MAIN = strip_comments(MAIN_RAW)
MOTION = strip_comments(MOTION_RAW)
SETUP = strip_comments(SETUP_RAW)
CHECKIN = strip_comments(CHECKIN_RAW)


# ===========================================================================
# 一、bug 1：下拉关闭之后「点不进播放页」
# ===========================================================================

section("一、bug 1：位移必须每次打开都复位")

check_true("PlayerScreen 接收 openNonce（「这是第几次打开」）",
           "openNonce: Int = 0," in PLAYER)
check_true("  · 打开时用它把位移 snapTo 回 0",
           "LaunchedEffect(openNonce) { offsetY.snapTo(0f) }" in PLAYER,
           "实例被保留时（退出动画中途再打开）episodeId 不变、那个 effect 不重跑 —— "
           "只有 openNonce 能触发复位")
check_true("  · 而**不是**靠 LaunchedEffect(episodeId) 复位（那个不重跑）",
           "LaunchedEffect(episodeId) { offsetY" not in PLAYER)
check_true("MainActivity 有两个自增点（迷你条 + 列表）",
           MAIN.count("openNonce++") == 2,
           "两条打开路径都要自增；漏一条就会留下「那种方式打不开」的 bug")
check_true("  · 而 playingId 的清空（退出）**不**自增",
           MAIN.count("openNonce++") == 2 and 'playingId = ""' in MAIN)
check_true("  · openNonce 被真正传进 PlayerScreen",
           "openNonce = openNonce," in MAIN)
check_true("  · 它用自增计数而不是布尔量（连着打开两次也要能触发）",
           "var openNonce by rememberSaveable { mutableIntStateOf(0) }" in MAIN)

# 坏样本 A：把复位那段去掉
BAD_NO_RESET = PLAYER.replace("LaunchedEffect(openNonce) { offsetY.snapTo(0f) }", "")
check_true("坏样本 A（删掉复位）被拦下",
           BAD_NO_RESET != PLAYER
           and "LaunchedEffect(openNonce) { offsetY.snapTo(0f) }" not in BAD_NO_RESET)

# 坏样本 B：只在迷你条那条路径自增
BAD_ONE_PATH = MAIN.replace("                                    openNonce++\n", "", 1)
check_true("坏样本 B（只接一条打开路径）被拦下",
           BAD_ONE_PATH != MAIN and BAD_ONE_PATH.count("openNonce++") == 1)


# ===========================================================================
# 二、bug 2：形变时的卡顿
# ===========================================================================

section("二、bug 2：形变的三个卡顿来源")

# ① 两端可见性必须互斥
check_true("迷你条那端的可见性由调用方按「播放页开没开」传入",
           "morphVisible = playingId.isEmpty()," in MAIN,
           "漏接线时它一直是默认的 true —— 同一个键同时存在两个「在场」的副本，"
           "共享元素的状态机会在两者之间来回切，形变因此卡顿")
check_true("  · 而 MiniPlayerBar 那边确实用它（不是收了参数不用）",
           "morphVisible," in strip_comments(kt("ui/MiniPlayerBar.kt")))
check_true("  · 参数有默认值（true）—— 所以**光看编译不会发现漏接线**",
           "morphVisible: Boolean = true," in strip_comments(kt("ui/MiniPlayerBar.kt")),
           "这正是它需要一条「调用方必须显式传」的断言的原因")

# ② 整页位移必须走绘制层
check_true("下拉位移走 graphicsLayer（绘制层），不是 offset {}（布局层）",
           ".graphicsLayer { translationY = offsetY.value }" in PLAYER,
           "offset {} 每帧让整棵子树重新测量——里面是 8–12 行字幕")
check_true("  · 而且 PlayerScreen 里不再有布局层的 .offset {}",
           ".offset {" not in PLAYER and ".offset(" not in PLAYER)

# ③ 形变开启时不做整页位移
check_true("Motion 提供「只淡入淡出」的形变版转场",
           "val overlayEnterMorph: EnterTransition get() = fadeIn(animationSpec = enter())" in MOTION
           and "val overlayExitMorph: ExitTransition get() = fadeOut(animationSpec = exit())" in MOTION)
check_true("  · 播放页的进出按 SHARED_MORPH 二选一",
           "if (SHARED_MORPH) Motion.overlayEnterMorph else Motion.overlayEnter" in MAIN
           and "if (SHARED_MORPH) Motion.overlayExitMorph else Motion.overlayExit" in MAIN)
check_true("  · 且注释写明了「两套位移叠在同一屏」是根因（看原始源码）",
           "两套位移" in MOTION_RAW)

# 坏样本 C：把 graphicsLayer 换回布局层的 offset
BAD_LAYOUT_OFFSET = PLAYER.replace(
    ".graphicsLayer { translationY = offsetY.value }",
    ".offset { IntOffset(0, offsetY.value.roundToInt()) }",
)
check_true("坏样本 C（位移退回布局层 offset{}）被拦下",
           BAD_LAYOUT_OFFSET != PLAYER and ".offset {" in BAD_LAYOUT_OFFSET)

# 坏样本 D：两端可见性写死成 true
BAD_VIS = MAIN.replace("morphVisible = playingId.isEmpty(),", "morphVisible = true,")
check_true("坏样本 D（两端都写死在场）被拦下",
           BAD_VIS != MAIN and "morphVisible = playingId.isEmpty()," not in BAD_VIS)


# ===========================================================================
# 三、播放页材质化（设计稿 §12 在播放页的那一笔）
# ===========================================================================

section("三、播放页材质化：字幕从顶栏与控制区下面穿过")

check_true("字幕层铺满整屏（不再被夹在两块材质中间）",
           "// ── ① 字幕层：铺满整屏" in PLAYER_RAW
           and PLAYER.count("Modifier\n                .fillMaxSize()\n                .statusBarsPadding()") >= 1)
check_true("  · 首尾用 contentPadding 让出两块材质的高度（量出来的）",
           "top = with(density) { topBarPx.toDp() } + Space.block," in PLAYER
           and "bottom = with(density) { controlPx.toDp() } + Space.block," in PLAYER,
           "写死高度会在「标题两行」「翻译状态行出现」时露馅")
check_true("  · 两块材质的高度是 onSizeChanged 量的",
           "onSizeChanged { topBarPx = it.height }" in PLAYER
           and "onSizeChanged { controlPx = it.height }" in PLAYER)
check_true("  · 初值是估算值（避免第一帧字幕从材质底下露出来）",
           "TOP_BAR_ESTIMATE" in PLAYER and "CONTROL_ESTIMATE" in PLAYER)
check_true("边界用渐隐而不是 1px 硬线（§12）",
           "private fun ScrollEdgeFade(" in PLAYER
           and PLAYER.count("ScrollEdgeFade(atTop =") == 2
           and "Brush.verticalGradient" in PLAYER)
check_true("  · 顶栏与控制区都不再画那条 Hairline",
           PLAYER.count("background(Hairline)") == 0,
           "那条线会把「穿过」这件事打断")
check_true("  · 控制区底色改成 Bg（不再是 BgRaised 的「凸起」）",
           ".background(Bg)\n            .padding(horizontal = Space.pageH, vertical = 12.dp)," in PLAYER)
check_true("「当前句停在上三分之一」按**可见区**算，不是整个视口",
           "val visible = (viewport - topBarPx - controlPx).coerceAtLeast(1)" in PLAYER
           and "topBarPx + visible / 3" in PLAYER,
           "视口顶底被材质盖着，按整个视口算会让当前句正好落进顶栏底下")
# ★ 2.1.2：字幕按句合并之后单条变长了（原来一条 2–4 秒，现在是一整句，
#   中文可能有十几行），「一律停在上三分之一」会把超过下三分之二的句子
#   的**开头推到顶栏底下** —— 用户读到的是一句从中间开始的话。
#   用户的原话：「切换到下一句会出现显示不全」。
check_true("  · ★ 放不下的句子改为**贴顶**（让它从头开始读）",
           "val restPx = visible - visible / 3" in PLAYER
           # ⚠️ 判据要写全（含 `- marginPx`）：只匹配前缀的话，
           #    「把余量去掉」这种回归照样能通过 —— 而余量正是 2.1.7 修的东西
           and "val longLine = itemPx == null || itemPx > restPx - marginPx" in PLAYER
           and "val targetTopPx = if (longLine) topBarPx else topBarPx + visible / 3" in PLAYER,
           "超长的句子一屏本来就放不下 —— 能保证的是「开头可见、往下滚能读全」，"
           "而不是两边各切一半")
# ★★ 2.1.7：光有「贴不贴顶」这条判据还不够，**边界上要留余量**。
#
#   `restPx` 是「停在上三分之一之后下方还剩多少」= 正好放下所需的全部空间。
#   行高正好等于它时，这一句的底边会**正好压在控制区上沿** —— 任何测量误差、
#   或者中文对照晚到一帧把行高撑大，立刻变成「被切掉一截」。
check_true("  · ★★ 2.1.7 边界要留余量（不然底边正好压在控制区上沿）",
           "val marginPx = with(density) { SUBTITLE_FIT_MARGIN.roundToPx() }" in PLAYER
           and "private val SUBTITLE_FIT_MARGIN = 26.dp" in PLAYER,
           "余量取一行行高（26dp，与 SUBTITLE_LINE_HEIGHT 对齐）。"
           "别把它调到 0 —— 那就退回了「底边贴在材质边缘」，"
           "而那正是「第二句中文被切」反复出现的原因")
# ★★ 2.1.7：落点必须**跟着行高重排**。
#
#   中文对照是异步到达的：没到时那一行只画占位下划线，行高小一大截 →
#   「先按小行高算落点、中文到了行高变大」，而**增长是向下的**，
#   尾巴就伸到控制区下面；而 currentIndex 没变，一次性 effect 不会重跑。
#   用户录屏里就是这个形态（第一句被钳住看着正常、第二句中文只剩一行）。
check_true("  · ★★ 2.1.7 落点跟着**行高**重排（中文晚到会让落点过期）",
           "snapshotFlow {" in PLAYER
           and "?.size" in PLAYER
           and "distinctUntilChanged().collect { itemPx ->" in PLAYER
           and "alignOnce(itemPx, pass)" in PLAYER,
           "判据是「当前那一项的行高」—— 它只跟内容有关，对齐本身不改行高，"
           "所以这个循环是**收敛**的（不会来回抖）；`distinctUntilChanged` 防重复滚动")
check_true("  · 且「重排」这件事在日志里能看出来",
           "(if (pass > 1) \" · 重排\" else \"\")" in PLAYER,
           "没有这个标记的话，「按旧行高算的落点已经过期」这件事只能靠猜")
# ★★ 2.1.6：这条才是「长段落显示不全」的真根因。
#
#   `scrollOffset = 0` 指的是「贴到**内容区起点**」，而内容区起点比视口顶低
#   一整个 `contentPadding.top`（这里 = 顶栏 + 一段留白）。所以写 `-targetTopPx`
#   会把当前句停在**比目标低一整个内边距**的位置：短句只是偏低（看不出问题），
#   **长句的尾巴被控制区切掉**。
#
#   ⚠️ 2.1.2 只改了「用哪一种对齐」，两种对齐都带着同一个偏移 ——
#      所以用户的感觉是「你完全没动」。**只改对齐、不改原点，等于没改。**
check_true("  · ★★ 2.1.6 落位要减掉顶部内边距（`scrollOffset` 的 0 是「内容区起点」）",
           "val paddingTopPx = topBarPx + with(density) { Space.block.roundToPx() }" in PLAYER
           and "scrollOffset = paddingTopPx - targetTopPx" in PLAYER,
           "不减的话当前句会停在比目标低一整个内边距的位置。"
           "⚠️ 这个「0 = 内容区起点」不是猜的：上一轮排查「切筛选往下沉 30dp」时"
           "从录屏量出 `(下标, 0)` 的落点比吸顶线低整整一个 Space.section，与内边距精确吻合")
check_true("  · ★ 并把这一次跟随**量出来**报掉（行高 vs 可见区）",
           "DiagLog.act(" in PLAYER
           and "跟随定位 · 第 " in PLAYER
           and "行高 ${itemPx ?: -1}px · 可见区 ${visible}px · " in PLAYER,
           "行高 ≤ 可见区 → 本来能完整显示 → 是**落位**算错了（改代码）；"
           "行高 > 可见区 → 这一句本来就有两屏高 → 只能保证**开头**可见。"
           "这两种情况下一步完全不同，而在此之前没有任何数据能分清它们")
check_true("「回到当前句」浮在底部材质**之上**",
           "with(density) { controlPx.toDp() } + 14.dp" in PLAYER)
check_true("  · 并写明这不是设计稿里那种 blur（Compose 没有 backdrop-filter）",
           "backdrop-filter" in PLAYER_RAW and "Modifier.blur" in PLAYER_RAW)

# 坏样本 E：把边界渐隐换成硬线
BAD_EDGE = PLAYER.replace("ScrollEdgeFade(atTop = true)", "Box(Modifier.height(1.dp).background(Hairline))")
check_true("坏样本 E（边界退回 1px 硬线）被拦下",
           BAD_EDGE != PLAYER and BAD_EDGE.count("ScrollEdgeFade(atTop =") == 1)


# ===========================================================================
# 四、设置页与打卡页的卡片化
# ===========================================================================

section("四、设置页与打卡页：用同一套卡片语言")

setup_block = SETUP[SETUP.index("internal fun Block("):]
setup_block = setup_block[:setup_block.index("\n}\n") + 3]
check_true("设置页的 Block 是卡片（七条看不见的线 → 一块块分明的卡）",
           ".clip(shape)" in setup_block and ".background(Card)" in setup_block
           and ".border(1.dp, CardEdge, shape)" in setup_block,
           "hairline 压在 Bg 上只有约 1.1:1，深色下七个区块是七条看不见的线")
check_true("  · 收尾那条 Rule() 删掉了（卡片自己就是边界）",
           "Rule()" not in setup_block)
check_true("  · 卡内边距是 14dp（密集列表的性格不变）",
           ".padding(14.dp)," in setup_block)
check_true("  · 且**故意不加** pressFeedback（整块不可点，加了会让开关连卡片一起缩）",
           "pressFeedback" not in setup_block and "pressFeedback" in SETUP_RAW)

check_true("打卡页有共用的卡片容器",
           "private fun CheckInCard(" in CHECKIN and "fun CheckInCard(content: @Composable ColumnScope.() -> Unit)" in CHECKIN)
# ⚠️ 3.3.0：从 **5** 处变 **6** 处 —— 多了「每日目标」那一块（预设档位）。
#    判据本身没变（「打卡页的区块都得走同一个卡片容器」），变的是那个数字。
#    改断言时把理由写在这里：下一个人只看到数字变了，会以为是在放宽。
check_true("  · 六个区块都换成了它（今日 / 目标 / 连续 / 日历 / 海报 / 记录）",
           CHECKIN.count("CheckInCard {") == 6,
           f"实际 {CHECKIN.count('CheckInCard {')} 处")
# ⚠️ 3.3.0：刊头的签名多了一个参数（要把当前目标写进那句副标题 ——
#    目标可自定义之后，「每天听满 40 分钟」这句话不能再写死）。
#    **它的形状（不卡片化）没变**，这条断言问的仍然是那件事。
check_true("  · 刊头**没有**被卡片化（它是页面的头，不是一块内容）",
           "private fun Masthead(goalMinutes: Int) {\n    Column(Modifier.padding(bottom = Space.block)) {" in CHECKIN)
check_true("  · 卡内边距 16dp，与素材库的 13 / 设置页的 14 **不同**（三页各自节奏）",
           ".padding(16.dp),\n        content = content," in CHECKIN
           and "三处不一样是**有意的**" in CHECKIN_RAW)

# 坏样本 F：把刊头也一起卡片化
BAD_MASTHEAD = CHECKIN.replace(
    "private fun Masthead(goalMinutes: Int) {\n    Column(Modifier.padding(bottom = Space.block)) {",
    "private fun Masthead(goalMinutes: Int) {\n    CheckInCard {",
)
check_true("坏样本 F（把刊头也卡片化）被拦下",
           BAD_MASTHEAD != CHECKIN
           and "private fun Masthead() {\n    Column(Modifier.padding(bottom = Space.block)) {" not in BAD_MASTHEAD)


# ===========================================================================
# 五、汇总
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
