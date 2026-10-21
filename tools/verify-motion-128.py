#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
动效与卡片视觉 —— 1.2.8 的契约锁。

## 这一轮改的是「看起来差不多」的那些东西

用户选定重做方向（方案 B「现代工具风」）之后，按 `apple-design` 的 17 节
逐条过了一遍。其中两条问题是**算出来的，不是看出来的**：

| # | 现象 | 真相 |
|---|---|---|
| 1 | 每次点按都像慢半拍 | `EasingStandard` 与 `EasingExit` 的**起手斜率都是 0**（前者两端都慢的 ease-in-out，后者纯 ease-in），而它们的注释分别写着「起步偏快」「前段就快」—— 实现和注释是反的 |
| 2 | 按下去什么都不发生 | 全工程 59 个可点元素直接用 `Modifier.clickable`，`collectIsPressedAsState` 使用 **0 次** |
| 3 | 深色下整个列表糊成一片 | 分组完全靠 1px `hairline`，而深色下它压在底上对比度只有约 **1.1:1** |
| 4 | 星标点不准 | 命中区跟着字形（17dp），不是 Apple 的 44pt 门槛 |
| 5 | 底部是「两块灰条 + 两条看不见的线」 | 迷你条与底栏各画一条上边缘，而它们本来是同一个东西 |

**这个文件的重点是第 1 条**：曲线对不对不能靠眼睛判断 —— 要看就把
控制点代进起手斜率 `y₁/x₁` 算一遍。所以下面第一节是**从源码里解析控制点、
算出斜率再断言区间**，而不是比对字面量。

用法：
    python3 tools/verify-motion-128.py
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


def check(name: str, got, want) -> None:
    check_true(f"{name}（得到 {got!r}，期望 {want!r}）", got == want)


def section(title: str) -> None:
    print(f"\n{'=' * 74}\n{title}\n{'=' * 74}")


def kt(rel: str) -> str:
    return (JAVA / rel).read_text(encoding="utf-8")


def strip_comments(src: str) -> str:
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
    """
    从 marker 起截一段。

    ⚠️ 窗口是定长的（够用即可），只用于**局部存在性**断言；
    要断言「某个东西不在里面」时先确认窗口没截到隔壁函数 —— 见
    `verify-library-ux.py` 里那条被窗口长度坑过的注释。
    """
    i = src.find(marker)
    return src[i:i + size] if i >= 0 else ""


def changed_lines(a: str, b: str) -> int:
    la, lb = a.splitlines(), b.splitlines()
    if len(la) != len(lb):
        return -1
    return sum(1 for x, y in zip(la, lb) if x != y)


# ⚠️⚠️ 两类源码：**剥了注释的**用于代码断言，**原始的**用于注释断言。
#
#    本文件第一版把注释断言也写在剥过注释的源码上，于是三条断言恒假
#    （`strip_comments()` 会删掉你要断言的那句话 —— 这条本工程记过，还是踩了）。
#    要断言「注释里写明了某件事」时，一律用 *_RAW。
MOTION_RAW = kt("ui/theme/Motion.kt")
MAIN_RAW = kt("MainActivity.kt")

MOTION = strip_comments(kt("ui/theme/Motion.kt"))
PARTS = strip_comments(kt("ui/theme/Parts.kt"))
THEME = kt("ui/theme/Theme.kt")
LIB = strip_comments(kt("ui/LibraryScreen.kt"))
PLAYER = strip_comments(kt("ui/PlayerScreen.kt"))
MINI = strip_comments(kt("ui/MiniPlayerBar.kt"))
MAIN = strip_comments(kt("MainActivity.kt"))


# ===========================================================================
# 一、曲线：把控制点代进去算斜率
# ===========================================================================

section("一、三条曲线的起手斜率（算出来的，不是看出来的）")


def curve_of(name: str, src: str = MOTION):
    """从源码里解析 `val X: Easing = CubicBezierEasing(a, b, c, d)`"""
    m = re.search(
        r"val %s: Easing = CubicBezierEasing\(([\d.]+)f?, ([\d.]+)f?, ([\d.]+)f?, ([\d.]+)f?\)"
        % name, src,
    )
    return tuple(float(g) for g in m.groups()) if m else None


def start_slope(p) -> float:
    """起手斜率 y₁/x₁。**这是判「跟不跟手」的那个数**"""
    _, y1, _, _ = p
    return float("inf") if p[0] == 0 else y1 / p[0]


def end_slope(p) -> float:
    """收尾斜率 (1−y₂)/(1−x₂)"""
    _, _, x2, y2 = p
    return float("inf") if x2 == 1 else (1 - y2) / (1 - x2)


std, enter, exit_ = curve_of("EasingStandard"), curve_of("EasingEnter"), curve_of("EasingExit")
check_true("三条曲线都能从源码里解析出来（改写法会让这里先失败）",
           None not in (std, enter, exit_), f"{std} / {enter} / {exit_}")

if None not in (std, enter, exit_):
    s_std, s_enter, s_exit = start_slope(std), start_slope(enter), start_slope(exit_)
    print(f"\n      实测：Standard 起手 {s_std:.2f} · Enter {s_enter:.2f} · Exit {s_exit:.2f}")
    print(f"      收尾：Standard {end_slope(std):.2f} · Exit {end_slope(exit_):.2f}\n")

    check_true(
        "EasingStandard 是**快出慢入**（起手斜率 ≥ 1.5）",
        s_std >= 1.5,
        f"起手斜率 {s_std:.2f} —— 1.2.8 之前是 0（两端都慢的 ease-in-out），"
        "而它注释写着「起步偏快让界面显得跟手」",
    )
    check_true(
        "  · 同一条曲线收尾要慢下来（≤ 0.4）",
        end_slope(std) <= 0.4,
        f"收尾斜率 {end_slope(std):.2f} —— 快出慢入的「慢入」由它保证，"
        "两端都平就是 ease-in-out，不是快出慢入",
    )
    check_true(
        "  · 起手也不能太陡（≤ 6），否则状态变化会像瞬移",
        s_std <= 6.0,
        f"起手斜率 {s_std:.2f}",
    )
    check_true(
        "EasingExit 比标准曲线**更果断**（起手更陡）",
        s_exit > s_std,
        f"Exit {s_exit:.2f} vs Standard {s_std:.2f} —— 要走的东西不该让人等",
    )
    check_true(
        "EasingEnter 仍是强 ease-out（起手 ≥ 8，1.2.8 没动它）",
        s_enter >= 8.0,
        f"起手斜率 {s_enter:.2f}",
    )

# 反向：把标准曲线改回 1.2.8 之前那条，斜率断言必须失败
BAD_CURVE = MOTION.replace("CubicBezierEasing(0.32f, 0.72f, 0f, 1f)",
                           "CubicBezierEasing(0.2f, 0f, 0f, 1f)", 1)
check_true("坏样本 A（把标准曲线改回 `(0.2, 0, 0, 1)`）被拦下",
           start_slope(curve_of("EasingStandard", BAD_CURVE)) < 1.5)
check_true("  · 且坏样本与真源码只差那一处", changed_lines(BAD_CURVE, MOTION) == 1)


# ===========================================================================
# 二、按下反馈：59 个可点元素都该有
# ===========================================================================

section("二、按下反馈（Apple 第 1 节：反馈发生在 pointer-down）")

check_true("Parts.kt 里有 pressFeedback", "fun Modifier.pressFeedback(" in PARTS)
check_true("  · 读的是按压状态，不是点击结果",
           "collectIsPressedAsState()" in PARTS)
check_true("  · 缩放走 graphicsLayer（只动 transform，不重新布局）",
           "graphicsLayer {" in PARTS and "scaleX = effective" in PARTS)
check_true("  · 显式关掉水波纹（一套视觉只允许一种按压语汇）",
           "indication = null" in PARTS)

# 全工程不该再有裸 clickable
bare = []
for f in sorted(JAVA.rglob("*.kt")):
    if f.name == "Parts.kt":
        continue
    for i, l in enumerate(f.read_text(encoding="utf-8").splitlines(), 1):
        if ".clickable(" in l and not l.strip().startswith("//"):
            bare.append(f"{f.name}:{i}")
check_true(
    "全工程没有裸 `Modifier.clickable`（按下必须有反馈）",
    not bare,
    f"还有 {len(bare)} 处：{bare[:5]}",
)

uses = sum(f.read_text(encoding="utf-8").count("pressFeedback(") for f in JAVA.rglob("*.kt"))
check_true(f"pressFeedback 的使用点 ≥ 30（实测 {uses}）", uses >= 30,
           "这个数不需要精确，它防的是「加完 API 只改了两处就收工」")

sc = re.search(r"object PressScale \{(.*?)\n\}", PARTS, re.S)
ctrl = float(re.search(r"const val Control = ([\d.]+)f", sc.group(1)).group(1)) if sc else 0
row = float(re.search(r"const val Row = ([\d.]+)f", sc.group(1)).group(1)) if sc else 0
check_true("两档缩放都是「按下去」而不是「飞出去」（0.9 < x < 1）",
           0.9 < ctrl < 1 and 0.9 < row < 1, f"Control={ctrl} Row={row}")
check_true("  · 行越大缩得越少（整行缩多了会左右抖）",
           ctrl < row < 1, f"Control={ctrl} 应小于 Row={row}")

# 坏样本：把一个使用点改回 clickable
# ⚠️ 2.1.0：锚点从 `onRetry` 换成了 `onUpdate` —— 前者属于 DeployNotice
#    （模型部署失败时的「重试」），那块提示随端侧识别一起删了。
#    这条断言考的是「把一处 pressFeedback 改回裸 clickable 必须被拦下」，
#    拿一个已经不存在的调用去改，考的就变成了「字符串替换没命中」。
BAD_PRESS = LIB.replace(".pressFeedback(enabled = !sync.running, onClick = onUpdate)",
                        ".clickable(enabled = !sync.running, onClick = onUpdate)", 1)
check_true("坏样本 B（有一处退回裸 clickable）被拦下",
           ".clickable(" in BAD_PRESS and BAD_PRESS != LIB)


# ===========================================================================
# 三、减少动效（Apple 第 14 节：三个信号里的第一个）
# ===========================================================================

section("三、减少动效：减弱，而不是清零")

check_true("Motion 有一个 reduceMotion 开关",
           "var reduceMotion: Boolean by mutableStateOf(false)" in MOTION)
# ★ 1.2.9 反转：1.2.8 断言的是「@Volatile」，改成了 Compose 状态。
#   原因不是风格偏好：写在 @Volatile 上时，`onStart` 里改它**不会触发任何重组**
#   （界面从后台回来时组合树本来就在，不会因为 onStart 自动重跑），
#   于是「用户改了系统设置再切回来」是否生效取决于别的原因有没有触发重组。
check_true("  · 它是 Compose 状态，而不是 @Volatile（1.2.9 改）",
           "reduceMotion" in MOTION and "@Volatile" not in MOTION,
           "写成 @Volatile 时「改值」与「界面跟着变」之间没有联系 —— "
           "1.2.8 的生效时机是随机的")
check_true("覆盖层进入在「减少动效」时只淡入（去掉位移）",
           re.search(r"val overlayEnter: EnterTransition\s*\n\s*get\(\) = if \(reduceMotion\) \{\s*\n\s*fadeIn",
                     MOTION) is not None)
check_true("  · 而位移确实还在 else 分支里（不是两边都删了位移）",
           re.search(r"\} else \{\s*\n\s*slideInVertically", MOTION) is not None)
check_true("覆盖层退出同样处理", "slideOutVertically" in MOTION and MOTION.count("if (reduceMotion)") >= 2)
check_true("弹簧类在减少动效时退化成 snap（而不是慢慢飘）",
           MOTION.count("if (reduceMotion) snap()") >= 1)
check_true("按下反馈的缩放也受它约束",
           "if (Motion.reduceMotion) 1f else animated" in PARTS)
# ⚠️ 窗口只给 400 字符：给长了会把下面 pressScale 里那句
#    `if (Motion.reduceMotion)` 也框进来，断言恒假（这次踩到了）
check_true("  · 但**透明度**不受影响（变淡不是位移，去掉它反而看不出点没点上）",
           "val shownAlpha by animateFloatAsState(" in PARTS
           and "reduceMotion" not in body(PARTS, "val shownAlpha by animateFloatAsState(", 400))

ams = kt("ui/AndroidMotionSetting.kt")
check_true("AndroidMotionSetting 读的是系统的「移除动画」",
           "ValueAnimator.areAnimatorsEnabled()" in ams)
check_true("  · 读失败按「允许动效」处理（宁可多给动画）",
           "runCatching" in ams and "getOrDefault(false)" in ams
           and "!ValueAnimator.areAnimatorsEnabled()" in ams,
           "reduced = !enabled，读失败时 getOrDefault(false) 让 reduced 也是 false")
check_true("  · 值变化时留一行日志（下次能对上「用户说我关了动画」）",
           "DiagLog.i(" in ams)
check_true("MainActivity 每次回前台重读一次（用户可以切出去改）",
           "AndroidMotionSetting.refresh(this@MainActivity)" in MAIN
           and "LifecycleEventEffect(Lifecycle.Event.ON_START)" in MAIN)
# ★ 1.2.9 新增：旧写法（在 onStart 里刷）必须消失。
#   它**能编译、也能改到值**，但改的是一个没有读者的东西 —— 界面不会重组。
#   这类缺陷不会报错，只会「看起来没生效」，所以必须由断言钉住。
check_true("  · 而且读在**组合里**，不是在 onStart（1.2.9 改）",
           "AndroidMotionSetting.refresh()" not in MAIN,
           "写在 onStart 里时「改值」与「重组」之间没有联系")


# ===========================================================================
# 四、卡片视觉（方案 B）
# ===========================================================================

section("四、卡片视觉：靠底的亮度差 + 描边分组")

check_true("色板里有 card / cardEdge / cardChip 三个字段",
           all(f"val {k}: Color," in THEME for k in ("card", "cardEdge", "cardChip")))
check_true("  · 明暗两套都给了值", THEME.count("card = Color(0x") == 2)


def chip_alpha(palette: str) -> int:
    m = re.search(r"cardChip = Color\(0x([0-9A-Fa-f]{8})\)", palette)
    return int(m.group(1)[:2], 16) if m else -1


dark_pal = body(THEME, "val DarkPalette = AppPalette(", 900)
light_pal = body(THEME, "val LightPalette = AppPalette(", 1400)
check_true(
    "★ 胶囊底色是**半透明**而不是页面级抬升色（深色下差 3/255 等于没底色）",
    0 <= chip_alpha(dark_pal) < 0x60 and 0 <= chip_alpha(light_pal) < 0x60,
    f"深色 alpha=0x{chip_alpha(dark_pal):02X} 浅色 alpha=0x{chip_alpha(light_pal):02X}",
)
BAD_CHIP = THEME.replace("cardChip = Color(0x1CFFFFFF)", "cardChip = Color(0xFF141518)", 1)
check_true("坏样本 C（胶囊改用不透明的 bgRaised）被拦下",
           chip_alpha(body(BAD_CHIP, "val DarkPalette = AppPalette(", 900)) >= 0x60)

check_true("圆角刻度集中在 Radius（值不重要，一致才重要）",
           "object Radius {" in THEME
           and all(f"val {k} = " in body(THEME, "object Radius {", 400) for k in ("card", "cover", "pill")))

# ⚠️ 锚点必须是**代码**：LIB 是剥过注释的源码，拿注释当锚点必然落空
row_src = body(LIB, "val pressSource = remember {", 1400)
check_true("能定位到条目卡片的开头", len(row_src) > 400, f"截到 {len(row_src)} 字符")
# ⚠️★ 4.4.0 改了这里的**判据对象**，原因写清楚（免得下一轮以为是谁放宽了断言）：
#   清单/设计稿把素材列表从「一期一张卡」改成了**行式列表** ——
#   每张卡的白底 + 1px 描边 + 圆角是「一屏只放 4 条、且没有重点」的根源。
#   所以「条目是卡片」这条断言**不是被放宽了，是它守的那个设计被替换了**：
#   现在要守的是「无卡片外观 + 有缩进分隔线 + 正在播靠底色和竖条识别」。
check_true("条目是**行**：无卡片底色 / 无描边 / 无圆角裁切",
           ".clip(cardShape)" not in row_src
           and ".background(Card)" not in row_src
           and ".border(" not in row_src)
# ⚠️★ 4.6.3：**竖条删了**（用户："左侧的一条细线，位置不对"）。
#   它画在行的最左沿，而背景是整行宽 ⇒ 那条线贴着屏幕边，
#   像一条不属于任何人的装饰线。
#   「正在播」现在由三件事表达：8% 琥珀底 + 暂停图标 + 底部迷你条。
#   所以这条判据从「有竖条」改成「**没有**竖条，但底色还在」。
# ⚠️★ 4.6.4：**底色块也删了**（用户："琥珀底，左侧边界跟字母块对齐了，视觉不好看"）。
#   它的左边缘在当前页边距下必然贴着封面；除非把页边距从列表挪进每一行。
#   「正在播」现在只靠 **琥珀色标题 + 暂停图标 + 底部迷你条**。
#   ★ 这条判据三年里改过三次（描边 → 底色+竖条 → 底色 → 标题），
#     每次都是「正在播怎么标」这个问题的不同答案 —— 所以它守的是**"有且只有一种标记"**，
#     而不是某一种具体画法。
check_true("  · 正在播：靠**琥珀色标题**识别（无底色块 / 无竖条 / 无描边）",
           # 判据落在**整份源码**上：`row_src` 只截了 1400 字，
           # 而标题那段的 `playing -> Amber` 在更后面（这正是上次误判的原因）
           "playing -> Amber" in LIB
           and "AmberFaint else Color.Transparent" not in LIB
           and "barModifier" not in LIB)
check_true("  · 按压缩放与点击手势共用同一份状态",
           "pressScale(pressSource)" in row_src and "interactionSource = pressSource," in row_src)
check_true("  · 分隔线在行尾、从文字左沿起画（不是通栏）",
           "padding(start = 59.dp)" in LIB and "if (!playing)" in LIB)

# ⚠️ 4.4.0 反向：条目之间**现在画一条缩进分隔线**（缩进是「精致」的那一下）。
check_true("列表条目之间用**缩进分隔线**分组（不是通栏线、也不是卡片间距）",
           "padding(start = 59.dp)" in LIB and ".height(0.5.dp)" in LIB)
check_true("  · 条目自己那条 Rule() 已删除",
           "Rule()" not in body(LIB, "private fun EpisodeRow("))

fav = body(LIB, "private fun FavoriteMark(")
# ⚠️ 2.2.3 更新了这条断言的判据 —— **不是放宽，是判据本身换了**。
#
#    原来是 `.size(44.dp)`（44dp 正方形触摸区）。但那个 44dp 的**高度**
#    会把 kicker 那一行撑高，而文字在行内垂直居中 —— 上方凭空多出
#    (44 − 16) / 2 ≈ 14dp。真机上逐像素量出来是「上留白 30dp、下留白 16dp」，
#    用户报的正是「素材里文字整体偏下」。
#
#    现在改成「宽 44、高随行」：水平命中区仍是 Apple 的 44pt 门槛
#    （星标贴着列表右缘，**水平方向最容易点偏**），垂直方向跟随行高（26dp）。
#    这是一次**有意的取舍：排版优先**。判据跟着实现改，而不是去掉这条断言。
check_true("星标水平命中区 44dp（Apple 44pt 门槛；视觉字形不变）",
           ".width(44.dp)" in fav and ".size(44.dp)" not in fav,
           "星标在右侧靠边，通勤单手点它本来就偏 —— 水平方向必须够宽")
check_true("  · 垂直命中区跟随行高，不再写死 44dp（2.2.3 的取舍）",
           ".fillMaxHeight()" in fav and "KICKER_ROW_DP" in LIB,
           "写死高度会把 kicker 行撑开，文字上方凭空多 14dp —— 就是「偏下」的成因")
BAD_STAR = LIB.replace(".width(44.dp)", ".size(34.dp)", 1)
check_true("坏样本 D（星标水平命中区改回 34dp）被拦下",
           ".width(44.dp)" not in body(BAD_STAR, "private fun FavoriteMark("))

check_true("大标题有负字距（字号越大越要收紧）",
           "letterSpacing = (-0.02).em" in THEME)
check_true("条目标题靠**字重**拉开（SemiBold），不是靠加大字号",
           "fontWeight = FontWeight.SemiBold" in THEME and "fontSize = 15.sp" in THEME)
check_true("新增 pill 样式（字距接近 0，label 那 1.2sp 在胶囊里会撑开）",
           "val pill = TextStyle(" in THEME and "letterSpacing = 0.2.sp" in THEME)
check_true("Theme.kt 文件头里那条被推翻的「取消卡片」已标注",
           "1.2.8 用户重新选定了方向：卡片回来了" in THEME,
           "实现改了而注释还写着旧结论 —— 这正是本轮修的另一类问题")


# ===========================================================================
# 五、底部是一块材质（Apple 第 12 节）
# ===========================================================================

section("五、底部：一块材质、一条上边缘")

bar = body(MAIN, "bottomBar = {", 3000)
check_true("底部只有一条上边缘，且画在迷你条**之前**",
           bar.index("background(Hairline)") < bar.index("MiniPlayerBar("))
check_true("  · BottomTabs 自己那条线已删除（否则就是两条）",
           "height(1.dp).background(Hairline)" not in body(MAIN, "private fun BottomTabs("))
check_true("迷你条底色并入底部（用 Bg，不是 BgRaised）",
           ".background(Bg)) {" in MINI and "BgRaised" not in MINI,
           "两块深浅不一的灰叠着，正是要消掉的东西")
check_true("  · 且不再自己画顶边线", "height(1.dp).background(Hairline)" not in MINI)
check_true("改动点写明了「这不是真正的半透明模糊」（注释 → 看原始源码）",
           "Compose 没有" in MAIN_RAW and "backdrop-filter" in MAIN_RAW,
           "Compose 没有 backdrop-filter 的等价物，这一处是有意降级，必须写出来")


# ===========================================================================
# 六、操作层：剩余时间 / 下一集 / 触觉
# ===========================================================================

section("六、操作层（Apple 第 13 / 16 节）")

check_true("时间行右端是**剩余时间**（通勤更常问「还有多久」）",
           '"-" + SrtTrack.formatClock((duration - shownPosition)' in PLAYER)
check_true("  · 剩余时间为负时不显示负数（拖到末尾那一瞬）",
           "coerceAtLeast(0L)" in body(PLAYER, '"-" + SrtTrack.formatClock'))
check_true("迷你条补了「下一集」", "onNext: () -> Unit," in MINI and "Glyph.ChevronRight" in MINI)
check_true("  · 用的是 Chevron 而不是 SkipNext（与「下一句」区分开）",
           "Glyph.SkipNext" not in MINI)
check_true("  · 且接上了真实的下一集动作", "onNext = {" in MAIN and "nextEpisode()" in MAIN)

haptics = sum(f.read_text(encoding="utf-8").count("performHapticFeedback(HapticFeedbackType.LongPress)")
              for f in JAVA.rglob("*.kt"))
check_true(f"触觉只在少数几处（实测 {haptics} 处，上限 5）", 3 <= haptics <= 5,
           "「每个点击都震一下」会训练用户把所有反馈都忽略掉")
check_true("  · 达标那一刻有", "LaunchedEffect(Unit) { haptic.performHapticFeedback" in
           kt("ui/CheckInCelebration.kt"))
check_true("  · 分享有（庆祝页与打卡页**两处**都算同一件事）",
           kt("ui/CheckInCelebration.kt").count("performHapticFeedback") >= 2
           and "performHapticFeedback" in kt("ui/CheckInScreen.kt"))
check_true("  · 删除有（唯一的破坏性且不可逆动作）",
           # 不用定长窗口 —— 删除在 EpisodeRow 靠后的位置，窗口得开很大才够得着，
           # 而开太大又会跨到别的函数。这里盯「整个文件里只出现一次」更准
           LIB.count("performHapticFeedback") == 1
           and "haptic.performHapticFeedback(HapticFeedbackType.LongPress)" in LIB)
check_true("  · 而按下反馈**不带**触觉（那是另一件事，不该混）",
           "performHapticFeedback" not in PARTS)


# ===========================================================================
# 七、时间轴 API 不许有尸体
# ===========================================================================

section("七、时间轴 API 不许有尸体")

SYMPTOM = ("一个定义了却没人调的函数，比没有这个函数更糟 —— 它会让下一个人以为"
           "「这里已经处理过了」。本工程为此付过代价（transcribeManually）")

check_true("Motion 里没有无人调用的 `settle()`", "fun <T> settle(" not in MOTION, SYMPTOM)

# ★ 1.2.9：`flick` 的断言**反过来了**。
#
# 1.2.8 断言的是「它必须不存在」—— 那时它确实没有调用点（唯一该用它的
# 下拉关闭排到了下一轮），所以按上面那条纪律留空。
# 这一轮下拉关闭落地，它从「尸体」变成了「有调用点的 API」，
# 于是断言变成「定义与调用必须同时存在」—— 这也是同一个纪律的另一面。
check_true("Motion 里有 flick()（1.2.9 的下拉关闭需要它）", "fun <T> flick(" in MOTION)
check_true("  · 而且**有调用点**（不然又变成尸体）",
           "Motion.flick(" in PLAYER, SYMPTOM)
check_true("  · 它是**欠阻尼**，与 resize 的 NoBouncy 明确分开",
           re.search(r"fun <T> flick\(\): FiniteAnimationSpec<T> =\s*\n\s*"
                     r"if \(reduceMotion\) snap\(\) else spring\(\s*\n\s*dampingRatio = 0\.8f",
                     MOTION) is not None,
           "§4：回弹只在**手势自带动量**时才对；纸面上的东西（resize）一律 NoBouncy")
check_true("  · 注释写明了它 1.2.8 为什么留空、1.2.9 为什么补上（看原始源码）",
           "1.2.8 有意留空" in MOTION_RAW and "transcribeManually" in MOTION_RAW,
           "理由要写在代码旁边：否则下一个人会以为「这里只是还没做」")

# 坏样本：把 flick 的阻尼比改回 NoBouncy（等于「有回弹需求却没给回弹」）
BAD_FLICK = MOTION.replace("dampingRatio = 0.8f", "dampingRatio = Spring.DampingRatioNoBouncy", 1)
check_true("坏样本（flick 用 NoBouncy）被拦下",
           BAD_FLICK != MOTION
           and re.search(r"fun <T> flick\(\): FiniteAnimationSpec<T> =\s*\n\s*"
                         r"if \(reduceMotion\) snap\(\) else spring\(\s*\n\s*"
                         r"dampingRatio = 0\.8f", BAD_FLICK) is None)


# ===========================================================================
# 八、汇总
# ===========================================================================

# ⚠️ 收尾这两行是**给 `tools/run-all-verify.sh` 看的** —— 它按「通过 N 项」抓数字。
#    本文件第一版写的是「✅ 全部通过（65 项）」，于是总表里这一栏显示 `-`，
#    而清单里的合计数就会少算 65 项。**格式本身就是契约。**
print(f"\n{'=' * 74}")
print(f"通过 {PASS} 项，失败 {FAIL} 项")
if FAIL:
    print("❌ 有断言未通过：")
    for f in FAILURES:
        print("  - ", f)
    sys.exit(1)
print("✅ 全部通过")
