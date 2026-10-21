#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
生词本底栏角标的契约（3.0.5 新建）。

## 为什么需要这个脚本

角标是「一行 UI」，看起来没什么可守的。但它有**三处错了都不会报错**的地方 ——
而这三处的共同点是：**代码读起来完全正常，只有人眼能看出问题**。

### ① 角标会把图标挤偏

`Box { GlyphIcon(...); Text(badge) }` 是所有人的第一反应。但 Compose 的 `Box`
尺寸 = 所有子元素里最大的那个，而角标（「12」约 20dp）比图标（19dp）宽 ——
于是角标一出现，图标就往左挪一个多 dp，消失时再挪回来。

⚠️ 这里真正要保的是「**图标的位置不许动**」，而一个自然的替代判据是
   「Box 够不够宽」—— 角标比图标窄时两者结果一样，一宽就分道扬镳。
   这就是「判据落在代理量上」的又一个实例。

### ② 刚打开 App 时角标永远是 0

角标的数字来自生词本，而生词本原来只在**生词页第一次被创建**时才读盘。
首页是素材库、pager 只预排相邻页 ⇒ 刚打开 App 时生词页还没建出来 ⇒ 数字是 0。

**而那正是角标唯一有用的时刻**（打开之后用户就进生词页了，那里本来就写着
「今天要复习 N 个」）。所以这个错等于「功能没做」，但界面上看不出任何异常。

### ③ 跨天不重算

到期判据是「今天 24:00 前到期」。App 在后台待一整夜时 `store.cards`
一个字节都没变 ⇒ 没有任何东西会重算 ⇒ 第二天早上看到的是昨天的数字。

## 这个脚本守什么

一、角标文字（0 / 封顶）
二、角标不许影响图标位置（含坏样本）
三、数字的来源与页签下标
四、首屏必须有数
五、跨天必须重算
六、样式
"""
import io
import os

ROOT = os.path.dirname(os.path.abspath(__file__)) + "/.."
MAIN = os.path.join(ROOT, "app/src/main/java/com/example/englishcommute/MainActivity.kt")
WVM = os.path.join(ROOT, "app/src/main/java/com/example/englishcommute/ui/WordViewModel.kt")
THEME = os.path.join(ROOT, "app/src/main/java/com/example/englishcommute/ui/theme/Theme.kt")
DUEBADGE = os.path.join(ROOT, "app/src/main/java/com/example/englishcommute/word/DueBadge.kt")
UITEST = os.path.join(ROOT, "app/src/test/java/com/example/englishcommute/word/DueBadgeTest.kt")
UIDIR = os.path.join(ROOT, "app/src/main/java/com/example/englishcommute/ui")
APPDIR = os.path.join(ROOT, "app/src/main/java/com/example/englishcommute")

ok_count = 0
fail_count = 0


def check(name, cond, why=""):
    global ok_count, fail_count
    if cond:
        ok_count += 1
        print("  ✅ %s" % name)
    else:
        fail_count += 1
        print("  ❌ %s" % name)
        if why:
            print("       %s" % why)


def read(p):
    return io.open(p, encoding="utf-8").read()


def code_only(src):
    """去掉注释，只留代码（**字符串字面量原样保留**）。

    ⚠️ 这个函数是被一次**假失败**逼出来的（3.0.5 写这个脚本的当时）：

        `layout(0, 0)` 在 MainActivity 里出现**两次** —— 一次在 KDoc 的说明里
        （带反引号），一次在代码里。坏样本替换的是**第一次**（注释那处），
        于是「代码里那份还在」⇒ 坏样本照样通过 ⇒ 断言报了一次假失败。

    本项目早就记过这条：**「匹配到注释」是三种假通过/假失败之一**。
    这次好在是坏样本红了（假失败），而不是断言假绿 —— 后者更贵。

    字符串字面量必须保留：像 `check(it >= 0) { "…$WORD_TAB_LABEL…" }`
    这种判据，要找的东西**就在字面量里面**。
    """
    out = []
    i, n = 0, len(src)
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


def section(t):
    print()
    print("=" * 68)
    print("  " + t)
    print("=" * 68)


print()
print("  生词本底栏角标契约（3.0.5）")

main_src = read(MAIN)
wvm_src = read(WVM)
theme_src = read(THEME)
due_src = read(DUEBADGE)
due_test = read(UITEST)

# 代码类断言一律用**去注释**的版本（见 code_only 的说明）
main_code = code_only(main_src)
wvm_code = code_only(wvm_src)

# ===========================================================================
section("一、角标文字")
# ===========================================================================
# 「0 该显示成什么」和「四位数怎么办」是两个**界面语义**的判断，
# 它们不该散在 UI 里，也不该混进 WordStore（那里只回答「今天欠多少」）。

check("DueBadge 里 0 和负数都返回 null",
      "due <= 0 -> null" in due_src,
      "返回 \"0\" 会挂一个看起来像回事、其实什么也没说的角标，而且它永远不会消失")

check("  · 封顶用的是具名常量 CAP",
      "const val CAP = 99" in due_src and 'due > CAP -> "$CAP+"' in due_src)

check("  · 单测点名了这几个边界（0 / 99 / 100）",
      "assertEquals(null, DueBadge.text(0))" in due_test
      and 'assertEquals("99", DueBadge.text(99))' in due_test
      and 'assertEquals("99+", DueBadge.text(100))' in due_test,
      "0 与 99 是两个边界，写反成 >= 会让 99 显示成 99+（少一格信息，且看不出来）")

check("  · 反向断言：\"99+\" 这个字面量只许出现在 DueBadge.kt",
      main_src.count('"99+"') == 0 and wvm_src.count('"99+"') == 0,
      "在别处再拼一次角标文字 = 第二份真相源")

# ===========================================================================
section("二、角标不许影响图标位置")
# ===========================================================================
# 这一节是本脚本里最值钱的部分。坏的写法读起来完全正常。


def zero_size_trick(src):
    """角标那一层必须报 0×0 —— 父容器因此只看得到图标"""
    return "layout(0, 0) { placeable.place(0, 0) }" in src


check("floatingOnIconCorner 用 layout(0, 0) 报零尺寸",
      zero_size_trick(main_src),
      "不报零尺寸的话，角标一超过图标宽度就把 Box 撑宽，居中的图标跟着位移"
      "（实测：「3」不动、「12」偏 1dp、「99+」偏 4dp —— "
      "一位数时不出现，所以它躲得过随手自测）")

check("  · TabItem 里的角标走的是这个修饰符",
      "floatingOnIconCorner(ICON_DP.dp)" in main_src,
      "写成裸的 Box(align = TopEnd) 就等于回到了被挤偏的写法")

check("  · 图标尺寸是具名的，不是散落的 19.dp",
      "private const val ICON_DP = 19f" in main_src
      and "size = ICON_DP.dp" in main_src,
      "角标要贴着「图标右上角」，两处各写各的尺寸，改一个就错位")

bad_layout = main_code.replace(
    "layout(0, 0) { placeable.place(0, 0) }",
    "layout(placeable.width, placeable.height) { placeable.place(0, 0) }",
    1,
)
check("坏样本（角标按自身尺寸占位）被拦下",
      zero_size_trick(main_src) and not zero_size_trick(bad_layout),
      "这就是「Box 尺寸 = 最大子元素」那条默认行为，也正是图标被挤偏的原因")

# ===========================================================================
section("三、数字的来源与页签下标")
# ===========================================================================
# 角标贴错页签、或者拿「生词总数」当「今天欠多少」—— 两者都不报错。

check("角标数字取自 dueCount（今天欠的），不是卡片总数",
      "DueBadge.text(wordDue)" in main_src and "wordDue by wordVm.dueCount" in main_src,
      "拿 total 当角标会让它永远不归零 —— 用户「今天做完了」也还挂着个数字")

check("  · 反向断言：MainActivity 里不许出现 cards 的 size",
      ".value.size" not in main_src,
      "cards 还受筛选档影响，拿它算角标会在切档时跳数字")

check("  · badges 由 TABS.size 生成，不是手写四个",
      "List(TABS.size)" in main_src,
      "手写的话少一个就会整段错位（角标贴到别的页签上，而界面不报错）")


def word_page_derived(src):
    """页签下标必须从 TABS 派生 —— 写死 2 的话，谁调整顺序就静默错位"""
    return "TABS.indexOfFirst { it.first == WORD_TAB_LABEL }" in src


check("生词页签的下标从 TABS 派生，不写死",
      word_page_derived(main_code),
      "写死下标 = 第二份真相源；调页签顺序时角标贴上别的页签，没有任何报错")

check("  · 派生失败会立刻炸（而不是静默变成 -1）",
      "check(it >= 0)" in main_code and "$WORD_TAB_LABEL" in main_code,
      "indexOfFirst 找不到返回 -1，静默的话角标只是「不显示」—— 谁也看不出为什么")

bad_page = main_code.replace("TABS.indexOfFirst { it.first == WORD_TAB_LABEL }", "2", 1)
check("坏样本（下标写死成 2）被拦下",
      word_page_derived(main_code) and not word_page_derived(bad_page))

# ===========================================================================
section("四、首屏必须有数")
# ===========================================================================
# 这一条对应「功能等于没做」的那个错。

check("loadStore 是独立方法（不藏在 start 里）",
      "fun loadStore()" in wvm_code and "fun start()" in wvm_code,
      "start() 只在生词页被创建时才跑，而首屏是素材库 —— 角标会永远是 0")

check("  · loadStore 幂等",
      "private var storeLoaded = false" in wvm_code and "if (storeLoaded) return" in wvm_code,
      "不幂等的话，每次进前台都会重新读盘（几十毫秒 IO）")


def root_loads_store(src):
    return "wordVm.loadStore()" in src


check("Root 在组合时就把它拉起来",
      "LaunchedEffect(Unit) { wordVm.loadStore() }" in main_code,
      "只要不拉，角标就在「刚打开 App」那一刻为 0 —— 而那正是它唯一有用的时刻")

check("  · 反向断言：Root 里不许调 start()",
      "wordVm.start()" not in main_code,
      "start() 会连带把 2 MB 词表预热一起做掉（几十到几百毫秒）—— 底栏角标不该等它")

bad_root = main_code.replace("LaunchedEffect(Unit) { wordVm.loadStore() }", "", 1)
check("坏样本（Root 不拉词本）被拦下",
      root_loads_store(main_code) and not root_loads_store(bad_root),
      "这就是 3.0.4 之前的样子：角标永远 0，而界面上完全正常")

# ===========================================================================
section("五、跨天必须重算")
# ===========================================================================


def due_combined(src):
    return "combine(store.cards, dayTick) { _, _ -> store.dueCount() }" in src


check("dueCount 跟着 dayTick 重算",
      due_combined(wvm_code),
      "光靠 store.cards 不会重算：跨天时那张列表一个字节都没变")

check("  · 列表与计数也一起跟上（漏一个就是「列表对了、角标没对」）",
      "combine(store.cards, _filter, dayTick)" in wvm_code
      and "combine(store.cards, dayTick) { _, _ -> store.counts() }" in wvm_code,
      "三处用的是同一个 clock，必须一起重算，否则同一天的两个数字会互相矛盾")

check("  · refreshToday 挂在 ON_START 上",
      "LifecycleEventEffect(Lifecycle.Event.ON_START) { wordVm.refreshToday() }" in main_code,
      "挂在别处（比如只在 onCreate）的话，隔夜回来不生效")

check("  · 外部可调，且幂等语义写在注释里（首次不算跨天）",
      "fun refreshToday()" in wvm_code and "lastTodayKey != Long.MIN_VALUE" in wvm_code,
      "首次就当成跨天的话，每次冷启动都会打一行「跨天重算」的假日志")

bad_combine = wvm_code.replace(
    "combine(store.cards, dayTick) { _, _ -> store.dueCount() }",
    "store.cards.map { store.dueCount() }",
    1,
)
check("坏样本（dueCount 退回只跟 cards）被拦下",
      due_combined(wvm_code) and not due_combined(bad_combine),
      "这就是 3.0.4 的样子：隔夜打开 App 看到昨天的数字")

# ===========================================================================
section("六、样式")
# ===========================================================================
# ⚠️ 搜索根是**整个包目录**，不是 ui/ ——
#    MainActivity 在包的根下（`com/example/englishcommute/MainActivity.kt`），
#    只扫 ui/ 的话会扫出「一处都没用」，然后这条断言就变成了
#    「因为找不到所以失败」—— 又是一次判据落在代理量上。
# ⚠️ 统计的也是**代码里**的引用（走 code_only）—— 同一个「匹配到注释」的坑
#    在这个脚本里出现了两次：
#      第一次：`layout(0, 0)` 在 KDoc 里被提到了一次（见 code_only 的说明）
#      第二次：这条统计 —— MainActivity 的注释里写了「按 [AppText.badge]（10.5sp）…」
#              于是被数成了「用了两处」，而真正用它的地方只有一处。
#    ⇒ **凡是要数「出现了几次」的判据，都必须先把注释去掉。**
BADGE_USES = []
for dirpath, _, files in os.walk(APPDIR):
    for f in files:
        if f.endswith(".kt"):
            p = os.path.join(dirpath, f)
            n = code_only(read(p)).count("AppText.badge")
            if n:
                BADGE_USES.append((os.path.basename(p), n))

check("角标用的是具名样式 AppText.badge",
      "style = AppText.badge" in main_code)

check("  · 这一档只被用在一处（底栏角标）",
      BADGE_USES == [("MainActivity.kt", 1)],
      "实测：%s —— 样式表是按语义分的，多一处就是「按字号凑」的开始"
      % (BADGE_USES or "一处都没用"))

check("  · 反向断言：UI 里不许再出现 copy(fontSize",
      "copy(fontSize" not in main_code,
      "那正是 3.0.1 清掉的反模式（18 处自定义字号 = 每个模块自己长一套字）")

check("  · 样式定义里有「只许一处用」的说明",
      "只许底栏角标用" in theme_src,
      "没有这句话的话，下一个人会拿它当「小号文字」的通用档")

# ===========================================================================
print()
print("=" * 68)
print("  %d 项通过 · %d 项失败" % (ok_count, fail_count))
print("=" * 68)
print()

raise SystemExit(1 if fail_count else 0)
