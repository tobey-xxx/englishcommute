#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
「设置」页签更新圆点的契约（3.2.0 新建）。

## 为什么需要这个脚本

这个需求一句话就说完了：「app 有新版本更新时，设置上加一个角标」。
但它有**三处不报错**的地方，而且三处各自只有一种错法：

### ① 用「有新版本」当判据，会静默漏掉两态

直觉写法是 `state is Available`。而 `Ready`（已经下好、等点安装）和
`Failed`（下失败了）在字面上**都不是**「有新版本」，可它们恰恰是用户最需要
被提醒的时候 —— 他已经动手了，只差最后一步，而他很可能已经离开这个页面去听东西了。

圆点的语义是「**这个页面上有未完成的事**」，不是「服务器上有新版本」。
前者包含后者，多出来的那两态正是最容易漏的。

### ② 把圆点塞进字符串

`badges` 原来是 `List<String?>`，只支持数字。圆点要进来只有两条路，两条都错：
`" "` 渲染出一个空胶囊（看起来像坏了），`"•"` 依赖设备字体里有没有那个字形，
而且它和外层琥珀胶囊叠起来会得到一个「点里有点」的怪东西。
这条现在是靠类型挡住的（`TabBadge.Dot`），脚本守住「类型还在」这件事。

### ③ 圆点也是角标，也会把图标挤偏

数字角标挤偏图标这件事在 3.0.5 已经修过（`floatingOnIconCorner` 报 0×0）。
圆点比图标小得多（8dp < 19dp），所以**它不会挤偏** ——
但正因如此，写成 `Box(align = TopEnd)` 也「看起来是对的」，
直到哪天有人把圆点调大一点点。判据必须落在「贴哪儿」这件共用的事上，
而不是「现在这个尺寸会不会出问题」上。

## 这个脚本守什么

一、映射：六种状态各归哪一边，且不许有 else
二、形态：圆点是类型，不是字符串
三、落点：和数字角标共用同一个修饰符
四、接线：下标派生、状态只读一次、来源是实时状态而不是持久化快照
五、单测点名了那两态
"""
import io
import os
import sys

ROOT = os.path.dirname(os.path.abspath(__file__)) + "/.."
JAVA = os.path.join(ROOT, "app/src/main/java/com/example/englishcommute")
MAIN = os.path.join(JAVA, "MainActivity.kt")
TABB = os.path.join(JAVA, "ui/TabBadge.kt")
TEST = os.path.join(
    ROOT, "app/src/test/java/com/example/englishcommute/ui/UpdateBadgeTest.kt"
)

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
    """去掉注释，字符串字面量原样保留。

    ⚠️ 本文件的 KDoc 里**原样解释**了三种错法（`UpdateState.Ready`、
    `TabBadge.Dot`、`Box(align = TopEnd)`），不去注释的话每条断言都会被
    自己的说明文字命中 —— 那是「假通过」，比没有断言更糟。
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


def block(src, marker):
    """从 marker 起、到文件里下一个顶格的 `}` 之前（= 那个函数的函数体）。

    不依赖缩进、也不依赖函数内部的行数 —— 只依赖 Kotlin 的顶层函数
    以顶格 `}` 结尾这个语法事实。
    """
    if marker not in src:
        return ""
    return src.split(marker, 1)[1].split("\n}", 1)[0]


main_raw = read(MAIN)
tabb_raw = read(TABB)
test_raw = read(TEST)

main_code = code_only(main_raw)
tabb_code = code_only(tabb_raw)

MAPPING = block(tabb_code, "fun updateBadge(")
DOT = block(main_code, "private fun BadgeDot(")
PILL = block(main_code, "private fun BadgePill(")

# ===========================================================================
section("一、映射：六种状态各归哪一边")
# ===========================================================================
# 「有新版本才显示」这句话看起来就是需求原文，但按字面实现会漏掉两态。

check("映射是一个纯函数（不在 UI 里做判断）",
      "fun updateBadge(state: UpdateState): TabBadge?" in tabb_code,
      "写进 UI 的话，它只能靠真机 + 恰好有一条更新可用才验得到 —— 而那正是最难的组合")

for state, want in (
    ("Unknown", "无"),
    ("UpToDate", "无"),
    ("Available", "有"),
    ("Downloading", "有"),
    ("Ready", "有"),
    ("Failed", "有"),
):
    check("  · %s → %s圆点" % (state, want),
          "UpdateState." + state in MAPPING,
          "漏一种状态的话，用户永远看不到那个提示，而界面上一切正常")

check("  · ★ Ready（已下好、等点安装）也算「有活要干」",
      MAPPING.count("UpdateState.Ready") == 1
      and MAPPING.index("UpdateState.Ready") > MAPPING.index("-> null"),
      "★ 这是「有新版本才显示」那个写法会漏掉的一态：Ready 时服务器上确实有新版本，"
      "所以按字面也对 —— 但用户需要的不再是「知道有新版」（他知道了），"
      "而是「知道该点安装了」")

check("  · ★ Failed（下失败了）也算",
      MAPPING.count("UpdateState.Failed") == 1
      and MAPPING.index("UpdateState.Failed") > MAPPING.index("-> null"),
      "★ 另一条会漏掉的。用户点完下载就走了，那次下载可能在后台悄悄失败 —— "
      "没有圆点，他就永远不知道自己还欠一个动作")

check("  · 反向断言：Unknown 不显示（『查不到』≠『有问题』）",
      MAPPING.index("UpdateState.Unknown") < MAPPING.index("-> null"),
      "断网时它恒为 Unknown —— 报警的话，飞行模式下每次打开 App 都会看到一个圆点")

check("  · 没有 else 兜底",
      "else ->" not in MAPPING,
      "写了 else 之后，新加一种 UpdateState 会**静默**落进某一边；"
      "枚举齐了的话编译器会直接报错，逼着做一次上面的判断")

check("  · 判据写在文件里（而不是只存在于这次对话里）",
      "这个页面上有未完成的事" in tabb_raw,
      "下一个人只会看到 `else -> TabBadge.Dot`，他有理由把它当成「只要有状态就显示」")

# ===========================================================================
section("二、形态：圆点是类型，不是字符串")
# ===========================================================================

check("TabBadge 是一个 sealed 类型，有两种形态",
      "sealed interface TabBadge" in tabb_code
      and "data class Count(" in tabb_code
      and "data object Dot" in tabb_code,
      "用 List<String?> 的话，圆点只能靠一个空格或一个圆点字符凑 —— "
      "前者渲染成空胶囊，后者依赖设备字体里有没有那个字形")

check("  · 渲染端是穷尽的 when（不是 if badge != null）",
      "is TabBadge.Count ->" in main_code and "is TabBadge.Dot ->" in main_code,
      "粗判「非空就当数字画」的话，加点第三种形态时不会有任何地方报错")

check("  · 圆点不写字（没有 Text）",
      "Text(" not in DOT,
      "圆点的意思就是「不说几个」—— 一旦给它写字，它就该叫数字角标了")

check("  · 单测点名了「圆点不写数字」这件事",
      "圆点不写数字" in test_raw or "圆点不写数" in test_raw)

# ===========================================================================
section("三、落点：和数字角标共用同一个修饰符")
# ===========================================================================
# 数字角标挤偏图标这件事在 3.0.5 修过。圆点现在比图标小（8dp < 19dp）、
# 不会挤偏 —— 正因如此，写成 Box(align = TopEnd) 也「看起来是对的」。

check("圆点走 floatingOnIconCorner",
      "floatingOnIconCorner(ICON_DP.dp)" in DOT,
      "圆点比图标小所以现在不会挤偏 —— 但那是**当前尺寸**的巧合，不是这条代码的性质。"
      "判据要落在「贴哪儿」这件共用的事上")

check("  · 数字角标也走同一个（一条定义，两处使用）",
      "floatingOnIconCorner(ICON_DP.dp)" in PILL)

check("  · 这个修饰符全工程只有一份定义",
      code_only(main_raw).count("private fun Modifier.floatingOnIconCorner(") == 1
      and code_only(main_raw).count("layout(0, 0) { placeable.place(0, 0) }") == 1,
      "第二份定义 = 第二个「报零尺寸」的实现机会，而漏掉它的形态是图标位移（看不出来）")

check("  · 圆点尺寸是具名的",
      "private val BADGE_DOT = " in main_code,
      "散在调用点的 8.dp 没法被这条断言找到，也没法解释「多大才是圆点」")

bad_dot = main_raw.replace(
    DOT,
    DOT.replace("floatingOnIconCorner(ICON_DP.dp)", "align(Alignment.TopEnd)"),
    1,
)
check("坏样本（圆点改用 align 定位）被拦下",
      bad_dot != main_raw
      and "floatingOnIconCorner(ICON_DP.dp)" in DOT
      and "floatingOnIconCorner(ICON_DP.dp)" not in block(code_only(bad_dot), "private fun BadgeDot("),
      "替换没生效的话这条「通过」是假的；生效了但断言没红，说明判据没落在落点上")

# ===========================================================================
section("四、接线：下标派生、状态实时")
# ===========================================================================

check("设置页签的下标从 TABS 派生，不写死 3",
      "TABS.indexOfFirst { it.first == SETTINGS_TAB_LABEL }" in main_code,
      "调页签顺序时，圆点会静默贴到别的页签上（界面上不报任何错）")

check("  · 派生失败立刻炸（而不是静默变成 -1）",
      'check(it >= 0) { "底栏里找不到「$SETTINGS_TAB_LABEL」页签' in main_code,
      "indexOfFirst 找不到返回 -1，静默的话圆点只是「不显示」—— 谁也看不出为什么")

check("  · 角标列表仍由 TABS.size 生成",
      "List(TABS.size)" in main_code,
      "少写一个下标就会整段错位")

check("  · 圆点在 SETTINGS_PAGE、数字在 WORD_PAGE",
      "SETTINGS_PAGE -> updateBadge(updateState)" in main_code
      and "WORD_PAGE -> DueBadge.text(wordDue)" in main_code,
      "两者搞反的症状是「生词页签挂了个圆点、设置页签挂着个数字」，"
      "看起来都像角标、都像有道理")

check("  · 状态来自实时的 StateFlow，不是持久化快照",
      "app.container.updater.state.collectAsStateWithLifecycle()" in main_code,
      "state 是这次会话里**真实**发生过的事（含下载进度/失败）；"
      "而 lastSeen() 只是「上次读到的清单」，它永远不知道用户已经下完了")

check("  · 反向断言：不许用 lastSeen() 当判据",
      "lastSeen()" not in main_code,
      "它回答的是「服务器上是什么版本」，不是「这个页面上还有没有活要干」——"
      "拿它当判据的话，Ready / Failed / Downloading 三态全会漏")

# ===========================================================================
section("五、单测点名了那两态")
# ===========================================================================
# 这个功能的「有活要干」判断全部落在一个纯函数上，而它想错了不会报错 ——
# 所以它必须在单测里逐态点名，而不是靠真机撞上一次更新。

for state in ("Unknown", "UpToDate", "Available", "Downloading", "Ready", "Failed"):
    check("  · 单测里出现 UpdateState.%s" % state, "UpdateState." + state in test_raw)

check("  · 单测真的断言了 Ready / Failed 的期望值",
      "UpdateState.Ready(" in test_raw and "UpdateState.Failed(" in test_raw,
      "只写进注释不算 —— 它得是构造出来的那个对象")

check("  · 断言的是「等于 Dot」，不是「非空」",
      "assertEquals(TabBadge.Dot," in test_raw,
      "断言非空的话，哪天有人给设置页签换成一个数字角标，单测照样绿")

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
