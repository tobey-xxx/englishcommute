#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
词卡面板的契约（3.2.0 新建）：下拉提示 + 加完词 1 秒自动收起。

## 为什么需要这个脚本

这两件事都是「**不报错、不崩、单测全绿**」的形状，而且它们的错法各自只有一种：

### ① 把手被当成装饰去掉了

`ModalBottomSheet` 的 `dragHandle` 槽位本来就有把手，但它的样式（一条居中的
浅色横杠）和这套「细线 + 留白」的视觉不是一路，于是 3.0.0 那一版写的是
`dragHandle = null`，理由也写进了注释 —— 看起来是个合理的取舍。

问题是**手势一直挂在整张 sheet 上，不依赖那个槽位**。所以关掉之后功能毫发无损，
"下拉关闭"照常能用，只是屏幕上**没有任何东西告诉用户可以下拉**。
用户就是这么报上来的：「最上方加个小横条，用以引导客户可以下滑关闭」。

⚠️ 这个 bug 最贵的地方在于：**「关掉把手」和「换一个自己的把手」在写代码那一刻
   看起来完全等价**（都不显示 M3 那个样式）。差别要等到有人问「这个怎么关掉」
   才显现 —— 而那时它已经上线了。

### ② 自动收起的判据用错了量

「加完词 1 秒后收起」直觉写法是 `LaunchedEffect(alreadyIn) { if (alreadyIn) … }`。
`alreadyIn` 对**本来就在词本里**的词也成立 ⇒ 每次点开一张老词的卡，它都会
自己合上，而用户根本没做任何操作。

根因是**拿状态代替事件**：`alreadyIn` 是持续为真的状态，「刚刚加过一次」是
一瞬间的事件。这类错法在本工程出现过多次（`start()` 只在生词页被创建时跑、
`openNonce` 用自增代替布尔量），所以这里把它钉死。

## 这个脚本守什么

一、把手确实在，而且和播放页共用同一份尺寸
二、加完词真的会自己收起（判据是事件，不是状态）
三、收起的顺序：可见的那一段走完，才让面板走
三·B、屏幕上动的必须是**我们**那一整张卡（3.4.0 —— 面板底色透明 + 抓手跟着淡
     + 一条自己的曲线；这一节是「不丝滑」那两次反馈的正解）
四、没有文字提醒（删掉的提示组件不许被加回来）
五、坏样本（每条关键判据都要能在坏样本上失败）

## 3.4.0：这个脚本改了什么、为什么

用户第二次反馈同一件事：「退出动画还是不丝滑，过渡的不自然」。
3.3.0 的解法（让内容先淡掉、面板继续滑）**方向是对的但漏了一半** ——
面板的底色是 M3 画的，它不会跟着淡，于是内容淡完之后还剩一块空白面板在滑。
判据因此从「内容有没有淡出」改成了「**屏幕上还有没有一处不归我们管的像素**」：
底色是不是我们画的、抓手有没有接上同一个进度、图层有没有罩在底色外面。
"""
import io
import os
import sys

ROOT = os.path.dirname(os.path.abspath(__file__)) + "/.."
UIDIR = os.path.join(ROOT, "app/src/main/java/com/example/englishcommute/ui")
SHEET = os.path.join(UIDIR, "WordCardSheet.kt")
PLAYER = os.path.join(UIDIR, "PlayerScreen.kt")
GRABBER = os.path.join(UIDIR, "Grabber.kt")
APPDIR = os.path.join(ROOT, "app/src/main/java/com/example/englishcommute")

PASS, FAIL = 0, 0
FAILURES = []


def check(name, ok, why=""):
    global PASS, FAIL
    if ok:
        PASS += 1
        print("  ✅ %s" % name)
    else:
        FAIL += 1
        FAILURES.append(name)
        print("  ❌ %s" % name)
        if why:
            print("       %s" % why)


def section(t):
    print()
    print("=" * 68)
    print("  " + t)
    print("=" * 68)


def read(p):
    return io.open(p, encoding="utf-8").read()


def code_only(src):
    """去掉注释，**字符串字面量原样保留**。

    ⚠️ 这个脚本里所有「出现了几次」和「有没有这串」的判据都必须走它 ——
       本文件要防的两个错，我都在 KDoc 里**原样解释过**（`dragHandle = null`、
       `alreadyIn`），不去注释的话每条反向断言都会被自己的说明文字命中，
       而且报的是「假失败」（看起来像实现错了，实际是断言写错了）。

    这个坑本工程踩过多次（`verify-word-badge.py` 里同一个脚本内踩了两次），
    所以这里第一段就把它写下来。
    """
    out, i, n = [], 0, len(src)
    while i < n:
        c = src[i]
        if c == '"':
            if src.startswith('"""', i):
                j = src.find('"""', i + 3)
                j = n if j < 0 else j + 3
            else:
                j = i + 1
                while j < n:
                    if src[j] == "\\":
                        j += 2
                        continue
                    if src[j] == '"' or src[j] == "\n":
                        j += 1
                        break
                    j += 1
            out.append(src[i:j])
            i = j
        elif src.startswith("//", i):
            j = src.find("\n", i)
            i = n if j < 0 else j
        elif src.startswith("/*", i):
            j = src.find("*/", i + 2)
            i = n if j < 0 else j + 2
        else:
            out.append(c)
            i += 1
    return "".join(out)


def call_block(src, name):
    """从 `name(` 开始取到括号配平为止。

    刻意**不依赖缩进**定界：本工程吃过一次亏 —— 坏样本用「整段字面量替换」
    构造，而那一轮把代码挪进了更深一层，替换静默失配，坏样本断言自己失败
    （`verify-motion-129.py` 的 `BAD_ORDER` 里记着这件事）。
    """
    i = src.find(name + "(")
    if i < 0:
        return ""
    j, depth = i + len(name), 0
    while j < len(src):
        if src[j] == "(":
            depth += 1
        elif src[j] == ")":
            depth -= 1
            if depth == 0:
                return src[i:j + 1]
        j += 1
    return ""


sheet_raw = read(SHEET)
player_raw = read(PLAYER)
grabber_raw = read(GRABBER)

sheet = code_only(sheet_raw)
grabber = code_only(grabber_raw)

# ===========================================================================
section("一、把手：它在，而且和播放页是同一份")
# ===========================================================================
# 「关掉把手」和「换一个自己的把手」在代码上看起来等价，所以判据必须是
# 「槽位里**有东西**」，而不只是「没在用 M3 自带的那个」。


def handle_is_ours(src):
    """槽位里放的是自家那条。

    ⚠️ 3.4.0 起判据从「`GrabberBar()` 空参」放宽成「`GrabberBar` 后面跟着左括号」——
    抓手这一版带上了一个 modifier（它要跟着卡片一起淡，见第三节）。
    形态一变就失配，正是本工程记过账的那一类：「断言看起来还在跑，实际什么都没测」。
    """
    b = src.split("dragHandle", 1)
    if len(b) < 2:
        return False
    return "GrabberBar(" in b[1][:200]


check("词卡的把手槽位里放的是自家那条",
      handle_is_ours(sheet),
      "3.0.0 写的是 dragHandle = null —— 手势仍然能用（它挂在整张 sheet 上，"
      "不依赖这个槽位），但屏幕上没有任何东西告诉用户可以下拉。"
      "用户就是这么报上来的")

check("  · 反向断言：不许再出现 dragHandle = null",
      "dragHandle = null" not in sheet,
      "同一个取舍再被做一次，症状和这次一模一样，而且不会有任何东西报错")

check("  · 抓手是全应用共用的一份（Grabber.kt）",
      "object Grabber" in grabber and "fun GrabberBar(" in grabber)

check("  · 尺寸在共用那一份里，且仍是 38×4",
      "val WIDTH = 38.dp" in grabber and "val HEIGHT = 4.dp" in grabber,
      "两处各写一遍之后，「改一处、另一处不动」不会被任何东西发现")

check("  · 播放页也在用同一个组件（两个把手不许分叉）",
      "GrabberBar(" in code_only(player_raw),
      "播放页的抓手是 1.2.9 就有的，词卡是 3.2.0 补的 —— "
      "它们必须在屏幕上长得一样，否则用户会以为是两种东西")


def grabber_call_sites(appdir):
    """数一数抓手组件在**调用点**出现几次。

    声明写的是 `fun GrabberBar(modifier: Modifier = Modifier)`，
    所以「`GrabberBar(` 的次数 − `fun GrabberBar(` 的次数」就是调用点数。
    （3.4.0 之前用的是空参形态 `GrabberBar()`，词卡那处带参数之后那个形态就不成立了。）
    """
    hits = []
    for dirpath, _, files in os.walk(appdir):
        for f in files:
            if f.endswith(".kt"):
                src = code_only(read(os.path.join(dirpath, f)))
                n = src.count("GrabberBar(") - src.count("fun GrabberBar(")
                if n:
                    hits.append((f, n))
    return sorted(hits)


SITES = grabber_call_sites(APPDIR)
check("  · 调用点恰好两处（播放页 + 词卡）",
      SITES == [("PlayerScreen.kt", 1), ("WordCardSheet.kt", 1)],
      "实测：%s —— 多一处说明有人又抄了一份；少一处说明有一个入口的提示没了"
      % (SITES or "一处都没有"))

# ===========================================================================
section("二、加完词真的会自己收起（判据是事件，不是状态）")
# ===========================================================================
# 这一节要防的是 `LaunchedEffect(alreadyIn)` 那种写法：它对**本来就在词本里**
# 的词也成立 ⇒ 每次点开一张老词的卡它都会自己合上。

AUTO_MS = "private const val AUTO_CLOSE_MS = 1_000L"


def auto_close_block(src):
    """自动收起那一段（从 effect 到 ModalBottomSheet 之间）。"""
    if "LaunchedEffect(justAdded)" not in src:
        return ""
    return src.split("LaunchedEffect(justAdded)", 1)[1].split("ModalBottomSheet(", 1)[0]


def driven_by_event(src):
    b = auto_close_block(src)
    if not b or "justAdded" not in b:
        return False
    # ⚠️ 这一段里**不许出现 alreadyIn** —— 它是持续为真的状态，
    #    用它当判据会让「打开一张老词的卡」也自动关闭。
    return "alreadyIn" not in b and "delay(AUTO_CLOSE_MS)" in b


check("延时是 1 秒，且是具名常量",
      AUTO_MS in sheet,
      "写在调用点的话，改它的人不会看到「太短看不见确认、太长挡住下一步」这两句")

check("  · 触发条件是「刚刚加过」这个一次性信号",
      driven_by_event(sheet),
      "★ 用 alreadyIn 代替的话：每次点开一张**本来就在词本里**的词，卡片都会自己合上，"
      "而用户根本没做任何操作 —— 而且不报错、单测也测不到")

check("  · 信号由点击置起（且全文件只有一处写它）",
      sheet.count("justAdded = true") == 1
      and sheet.find("justAdded = true") > sheet.find("AddButton(")
      and sheet.find("justAdded = true") < sheet.find("private fun AddButton("),
      "★ 少了这一句，自动收起永远不会发生：按钮还是会变成「已在生词本」，"
      "看起来一切正常，只是卡片一直挂着")

check("  · 信号复位：换一个词重新长按要重来",
      "var justAdded by remember(" in sheet and "picked.surface" in sheet,
      "不带 key 的 remember 会在同一个组合位置上复用上一个词的 true")

check("  · 按钮可用性判据没变（点击必然是一次真的加词）",
      "lookup != null && !alreadyIn" in sheet,
      "如果哪天允许在 alreadyIn 时点击，这个一次性信号就不再等价于「加过词」")

# ===========================================================================
section("三、收起的顺序：可见的那一段走完，才让面板走")
# ===========================================================================
# 直接 onDismiss 是让卡片从组合里消失（「啪」地不见），而同一个动作用手势做时
# 是滑走的。同一个动作应该只有一种观感。
#
# ⚠️ 3.4.0 把顺序**倒了过来**，这是这一版的核心改动之一：
#
#   3.3.0：`hide()` 与内容淡出**同时**开始 → 面板（那时还是不透明的）带着
#          还没淡完的卡片一起滑，而它的曲线与时长都不归我们管。
#          结果就是用户第二次说的「还是不丝滑、过渡不自然」。
#   3.4.0：先把**整张卡**（底色 + 抓手 + 内容）用自己的曲线走完，再 `hide()`。
#          那时面板已经透明、内容已经看不见 —— 它的位移与时长都没人看得见了。
#
# 所以这一节判的是**顺序**：animateTo → hide → onDismiss，一步都不能提前。


def close_sequence(src):
    """退场那一段里三个动作的先后。返回下标元组，缺任何一个都给 -1。"""
    b = auto_close_block(src)
    return (
        b.find("exit.animateTo("),
        b.find("sheetState.hide()"),
        b.find("onDismiss()"),
    )


def seq_is_one_timeline(src):
    a, h, d = close_sequence(src)
    b = auto_close_block(src)
    # 三段都得在、必须按序、而且这一段里不许再有第二条时间线（launch）
    return a >= 0 and a < h < d and "launch {" not in b


check("★ 顺序：自己的退场动画 → hide() → onDismiss()",
      seq_is_one_timeline(sheet),
      "★ 这是 3.3.0 那个「还是不丝滑」的根因：`hide()` 与内容淡出同时开始，"
      "面板带着还没淡完的卡片一起滑 —— 两个时钟驱动同一屏。"
      "现在面板那一段被推迟到卡片已经看不见之后，它的位移与时长就都不重要了")

check("  · 留痕在收起动作**之前**",
      auto_close_block(sheet).index("DiagLog.act") < auto_close_block(sheet).index("exit.animateTo"),
      "写在 onDismiss() 后面的话，这个协程可能已经被作用域取消 —— "
      "「做完了但没留痕」比「没做」更难查")

# ===========================================================================
section("三·B、退场：屏幕上动的是**我们**那一整张卡")
# ===========================================================================
# 用户连提两次「退出动画不丝滑 / 过渡不自然」。根因不是曲线不好，而是
# **一张卡有两半，而只有一半是我们的**：
#
#   · `ModalBottomSheet` 的面板底色（`containerColor`）由 M3 画；
#   · 它的位置位移与时长由 `MaterialTheme.motionScheme` 的 `DefaultSpatial` 决定，
#     而 `SheetState.hide/animateTo`、`hideMotionSpec`、`MotionScheme`
#     **三者全是 internal**（编译器拦下的，不是读文档猜的）。
#
# 于是 3.3.0 那句「让内容在前 220ms 淡掉」做出的是：
# 内容没了、**一块不透明的空白面板还在往下滑**，而且它滑得比 220ms 还久。
# 两个时钟驱动同一屏 —— 那就是「不自然」。
#
# 3.4.0 的改法（下面每一条都对应一个断言）：
#   ① 面板底色改成透明，底色由我们自己画 → 它才会跟着内容一起淡
#   ② 抓手也接上同一个进度           → 否则会看到一条小横杠自己滑走
#   ③ 可见的那段运动用 Motion.exit()  → 一条曲线、一个时长、一个时钟


def brace_block(src, marker):
    """从 marker 起到配对的花括号结束。

    用来取**一个 lambda 的整体**：这样里面怎么换行、怎么调格式都不会失配。
    （「按字面量整段匹配」在 3.3.0 吃过一次亏：那一轮代码挪深了一层，
    坏样本静默失配、什么都没测。）
    """
    i = src.find(marker)
    if i < 0:
        return ""
    j = src.find("{", i)
    if j < 0:
        return ""
    depth = 0
    while j < len(src):
        if src[j] == "{":
            depth += 1
        elif src[j] == "}":
            depth -= 1
            if depth == 0:
                return src[i:j + 1]
        j += 1
    return ""


def card_chain(src):
    """我们那张卡片的修饰符链（`Column(...)` 上那一串）。

    取的是「最后一个 `.fillMaxWidth()`」—— 文件里 Row / Text 上也用过它。
    """
    bg = src.find(".background(Bg)")
    if bg < 0:
        return ""
    i = src.rfind(".fillMaxWidth()", 0, bg)
    if i < 0:
        return ""
    j = src.find(".padding(", i)
    return src[i:j if j > 0 else i + 600]


MB = call_block(sheet, "ModalBottomSheet")
CHAIN = card_chain(sheet)
DRAG = brace_block(sheet, "dragHandle")

check("有一个属于这张卡的退场进度（不是靠面板的 spec）",
      "val exit = remember(" in sheet and "Animatable(0f)" in sheet,
      "面板的 spec 是 internal，改不了 —— 那就把**整张卡**自己驱动起来")

check("  · 用的是本工程的退场曲线（Motion.exit）",
      "exit.animateTo(1f, Motion.exit())" in sheet,
      "界面里散落的 tween(...) 会被 verify-refactor-120.py 拦下；"
      "曲线只该从 Motion 取")

check("★ 面板底色交给透明，底色由我们自己画",
      "containerColor = Color.Transparent" in MB,
      "★ 这是「过渡不自然」的第一层根因：面板底色是 M3 画的，它不跟着内容淡 —— "
      "内容淡没之后，屏幕上还剩**一块空白面板**在继续下滑。"
      "改成透明之后，屏幕上移动的每一像素都属于我们")

check("  · 底色真的由我们画（clip + background 都在卡上）",
      ".clip(cardShape)" in CHAIN and ".background(Bg)" in CHAIN,
      "面板透明了、我们又没画底色的话，卡片会变成透明的一层浮字")

check("  · ★ alpha 罩在底色**外面**（graphicsLayer 必须在 background 之前）",
      ".graphicsLayer" in CHAIN
      and CHAIN.index(".graphicsLayer") < CHAIN.index(".clip(cardShape)")
      < CHAIN.index(".background(Bg)"),
      "★ 修饰符顺序 = 绘制顺序：`background` 是**外层**修饰符，它先绘制、"
      "再进入内层的图层。图层写在它里面的话，那块底色不会跟着淡 —— "
      "症状和没改成透明时**一模一样**，而代码看起来已经完全改对了")

check("  · ★ 抓手也接上了同一个进度",
      "GrabberBar(" in DRAG and "graphicsLayer" in DRAG and "exit.value" in DRAG,
      "★ 底色透明之后，面板上只剩抓手还看得见 —— 不淡的话就是"
      "「卡片没了，一条小横杠自己滑走」")

check("  · 圆角只定义一次（shape 与 clip 共用同一个值）",
      "shape = cardShape" in MB and ".clip(cardShape)" in CHAIN,
      "两处各写一遍（比如都写 28dp）会在某天调了其中一处时沿卡片边缘露出错位的边")

check("  · 走 graphicsLayer，不是 offset",
      "translationY =" in CHAIN,
      "offset 是**布局修饰符**，每帧会重新布局这张卡里的所有文字；"
      "graphicsLayer 只影响绘制（本工程在角标上踩过同一个坑）")

check("  · 「移除动画」时去掉位移与缩放、保留淡出",
      "if (Motion.reduceMotion) 0f" in sheet
      and "if (Motion.reduceMotion) 1f" in sheet,
      "与 Motion 的口径一致：减弱不是清零 —— 去掉的是位移与缩放，透明度留下")

check("  · 下沉的距离是具名常量（3.4.0 起它是唯一在动的位移）",
      "private val EXIT_SINK = 40.dp" in sheet,
      "3.3.0 是 28dp，理由写着「面板自己也在往下走」—— 那句话现在不成立了："
      "面板那一段被推迟到卡片看不见之后，所以 40dp 就是「向下走」的全部表达")

check("  · 缩小的比例是具名常量，且很小",
      "private const val EXIT_SCALE = 0.03f" in sheet,
      "它只负责给「离开」一点纵深，不负责表达位移")

check("  · 点下去那一下的确认也不是硬切",
      "animateColorAsState(" in sheet and "animationSpec = Motion.quick()" in sheet,
      "按钮底色原来是**同一帧**从琥珀翻成灰的，而那一帧正是用户盯着的那一帧；"
      "它是整段退场里最容易显得不丝滑的地方，不是后面的下滑")

# ---- 坏样本 ---------------------------------------------------------------

bad_serial = sheet_raw.replace(
    "        exit.animateTo(1f, Motion.exit())",
    "        val hiding = launch { sheetState.hide() }\n        exit.animateTo(1f, Motion.exit())\n        hiding.join()",
    1,
)
check("坏样本 A（退场又变回并行：hide + 动画同时开始）被拦下",
      bad_serial != sheet_raw and seq_is_one_timeline(sheet)
      and not seq_is_one_timeline(code_only(bad_serial)),
      "★ 那就是 3.3.0 的样子：两个时钟驱动同一屏，而它看起来「也是淡出+下滑」，"
      "只有用户看得出不对")

bad_opaque = sheet_raw.replace("containerColor = Color.Transparent", "containerColor = Bg", 1)
check("坏样本 B（面板底色改回不透明）被拦下",
      "containerColor = Color.Transparent" in MB
      and "containerColor = Color.Transparent" not in call_block(code_only(bad_opaque), "ModalBottomSheet"),
      "内容淡完之后会剩一块空白面板继续滑 —— 这正是用户看到的那个「不自然」")

bad_layer = sheet_raw.replace(
    "                .graphicsLayer {\n                    alpha = 1f - exit.value",
    "                .clip(cardShape)\n                .background(Bg)\n                .graphicsLayer {\n                    alpha = 1f - exit.value",
    1,
)
_bad_chain = card_chain(code_only(bad_layer))
check("坏样本 C（图层写到 background 里面）被拦下",
      bad_layer != sheet_raw
      and ".graphicsLayer" in _bad_chain
      and _bad_chain.index(".graphicsLayer") > _bad_chain.index(".background(Bg)"),
      "底色不跟着淡，症状与「没改成透明」完全一样 —— 而代码看起来已经改对了")

bad_handle_static = sheet_raw.replace(
    "            GrabberBar(Modifier.graphicsLayer { alpha = 1f - exit.value })",
    "            GrabberBar()",
    1,
)
check("坏样本 D（抓手不跟着淡）被拦下",
      bad_handle_static != sheet_raw
      and "graphicsLayer" in DRAG
      and "graphicsLayer" not in brace_block(code_only(bad_handle_static), "dragHandle"),
      "卡片没了、一条小横杠自己滑走")

bad_offset = sheet_raw.replace(
    "                .graphicsLayer {\n                    alpha = 1f - exit.value",
    "                .offset { androidx.compose.ui.unit.IntOffset(0, (exit.value * 80f).toInt()) }\n                .graphicsLayer {\n                    alpha = 1f - exit.value",
    1,
)
check("坏样本 E（退场改回 offset 布局修饰符）被拦下",
      bad_offset != sheet_raw and bad_offset.count(".offset {") > sheet.count(".offset {"),
      "布局修饰符每帧重排整张卡片里的文字")

bad_motion = sheet_raw.replace("if (Motion.reduceMotion) 0f", "0f")
bad_motion = bad_motion.replace("if (Motion.reduceMotion) 1f", "1f")
check("坏样本 F（去掉「移除动画」的判断）被拦下",
      "Motion.reduceMotion" in sheet
      and "Motion.reduceMotion" not in code_only(bad_motion),
      "开了「移除动画」的用户会看到一整屏滑动 —— 那正是他要关掉的东西。"
      "⚠️ 两处判断（位移、缩放）要一起移除才算「去掉」，只删一处这条断言不会红")

# ===========================================================================
section("四、没有文字提醒")
# ===========================================================================

# 用户明确说了「不用做任何文字提醒」。确认由**按钮自己变成「已在生词本」**承担。

check("确认仍然是按钮文案的变化",
      '"已在生词本"' in sheet and '"加入生词本"' in sheet)

check("  · 那个没人调用的 AddedToast 已删掉",
      "AddedToast" not in sheet,
      "★ 它是死代码，但它代表的东西这一版明确不要。留着的代价不是几行代码，"
      "而是**下一个人会顺手用它** —— 一个已经写好的提示组件，在需要「给个反馈」时"
      "几乎必然被拿来用，而不会有任何断言拦他")

check("  · 反向断言：词卡里不许出现任何 Toast",
      "Toast" not in sheet,
      "包括 android.widget.Toast 和 Compose 的 Snackbar —— "
      "这一版的反馈刻意只有「按钮文案 + 卡片收起」两下")

check("  · 也不许写一条专门说「已加入」的文案",
      "已加入" not in sheet)

# ===========================================================================
section("五、坏样本（每条关键判据都要能失败）")
# ===========================================================================

# 坏样本 1 的构造走 brace_block 定位，不写字面量 —— 「整段字面量替换」在
# 3.3.0 静默失配过一次（代码挪深了一层），那种坏样本看起来在跑、其实什么都没测。
_DRAG_RAW = brace_block(sheet_raw, "dragHandle")
bad_null_handle = (
    sheet_raw.replace(_DRAG_RAW, "dragHandle = null", 1) if _DRAG_RAW else sheet_raw
)
check("坏样本 1（把手又关了）被拦下",
      handle_is_ours(sheet) and not handle_is_ours(code_only(bad_null_handle)),
      "就是 3.0.0 那一版的样子：功能全对，只是没人知道能下拉")
check("  · 坏样本 1 的构造本身是有效的",
      _DRAG_RAW.startswith("dragHandle") and bad_null_handle != sheet_raw,
      "替换没生效的话，这条坏样本「通过」是假的")

bad_state = sheet_raw.replace("LaunchedEffect(justAdded)", "LaunchedEffect(alreadyIn)", 1)
check("坏样本 2（判据用 alreadyIn 代替事件）被拦下",
      driven_by_event(sheet) and not driven_by_event(code_only(bad_state)),
      "打开一张本来就在词本里的词，卡片会自己合上")

bad_no_signal = sheet_raw.replace("justAdded = true\n", "", 1)
_bs = code_only(bad_no_signal)
check("坏样本 3（点击不置信号）被拦下",
      _bs.count("justAdded = true") == 0 and sheet.count("justAdded = true") == 1,
      "自动收起永远不会发生，而界面看起来完全正常")

# ⚠️ 这条坏样本改过两次构造方式，记一下：
#    3.3.0 之前锚在「hide() 紧跟着 onDismiss()」那两行；3.3.0 把 hide() 搬进
#    `launch { }`、中间夹了 join，那个字面量就不存在了 —— 替换静默失配、
#    坏样本什么都没测（本工程记过账的一类）。
#    3.4.0 又调了一次顺序，而且两句之间现在夹着注释，所以**不能**再用
#    「相邻两行的字面量」当锚点。改成「把 hide() 那一句整体挪到 onDismiss() 之后」——
#    它模拟的正是那个错法本身，和中间有没有注释无关。
_HIDE = "        sheetState.hide()"
bad_order = (
    sheet_raw.replace(_HIDE, "", 1)
             .replace("        onDismiss()", "        onDismiss()\n" + _HIDE, 1)
)
check("坏样本 4（面板还没收就先拆卡片）被拦下",
      seq_is_one_timeline(sheet)
      and seq_is_one_timeline(code_only(bad_order)) is False,
      "`hide()` 作用在一个已经离开组合的 sheet 上 —— "
      "看起来是「闪一下」，而日志里一切正常")

check("  · 坏样本 4 的构造本身是有效的",
      bad_order != sheet_raw
      and "".join(code_only(bad_order).split()).count("sheetState.hide()") == 1
      and code_only(bad_order).index("sheetState.hide()")
      > code_only(bad_order).index("onDismiss()"),
      "替换没生效的话，这条坏样本「通过」是假的")

bad_toast = sheet_raw.replace(
    "// 这里原来还有一个 AddedToast",
    "fun AddedToast(text: String) {}\n\n// 这里原来还有一个 AddedToast",
    1,
)
check("坏样本 5（提示组件被加回来）被拦下",
      "AddedToast" not in sheet and "AddedToast" in code_only(bad_toast),
      "下一个人会顺手用它，把刚定下的「不加文字提醒」悄悄推翻")

# 坏样本 5 必须**真的**是替换成功了的 —— 否则它测的是「什么都没变」。
check("  · 坏样本 5 的构造本身是有效的",
      bad_toast != sheet_raw,
      "替换没生效的话，这条坏样本「通过」是假的（本工程在 3.1.0 踩过一次）")

# ===========================================================================
print()
print("=" * 68)
print("  通过 %d 项，失败 %d 项" % (PASS, FAIL))
print("=" * 68)
if FAIL:
    print()
    print("  ❌ 未通过：")
    for f in FAILURES:
        print("     - " + f)
print()
sys.exit(1 if FAIL else 0)
