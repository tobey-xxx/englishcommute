#!/usr/bin/env python3
"""「切筛选不跳」的规则表 + 源码契约 + 坏样本。

规则实现：`app/src/main/java/com/example/englishcommute/ui/LibraryAnchor.kt`

=============================================================================
这一版（2.1.1）改了什么，以及为什么还要再改一次
=============================================================================

2.0.4 把位置从「下标」改成「内容 id + 像素」，方向是对的，也修掉了
「切走记在第 15 条、切回来落在别的内容上」。但它把落点**整个**交给了
目标档自己的历史（`inherit`: `target ?: current`），于是又冒出第二层症状。
用户的原话：

    「那 3 个 item 点击还是会乱跳，我的想的是，item 一栏在什么地方，
      跳转的时候就保持高度不变，一会儿高一会儿低好烦的」

「一会儿高一会儿低」指的是**筛选签那一栏在屏幕上的高度**：

    「全部」停在顶部（刊头可见，筛选签在屏幕中部）
      → 切到「收藏」
    「收藏」上次滚到第 5 条（筛选签贴顶）
      → 落点 = 第 5 条 → 筛选签栏「啪」地吸到顶上

**只要拿目标档的历史当落点，这个跳就一定发生** —— 两档的历史位置本来
就不同，用户什么都没做（只是换了个筛选签），那一栏却窜上去了。

所以 2.1.1 把判据收窄成一条：**看现在筛选签贴在哪儿，新档就落在哪儿。**

| 现在 | 新档 |
|---|---|
| 停在**头部区**（刊头 / 状态 / 筛选签自己） | 原样搬那一对（下标, 偏移）—— 三档的头部 item 完全相同，所以这一搬**一个像素都不动** |
| **已经吸顶**（首项滚进内容区） | 也必须吸顶；目标档若有「上次看到的那一条」就落到它身上（那条在内容区 → 仍然吸顶） |

分工是：**「吸不吸顶」由当前档决定，「看哪一条」才用目标档的历史。**
两者各管一件事，才能既不跳、又不丢浏览位置 —— 这是它唯一能共存的写法。

=============================================================================
2.1.6：那一栏其实有**三种**位置，「贴顶」被当成了两件事
=============================================================================

前四轮（2.1.1～2.1.5）都把它当成「两种」，于是落点一律写 `(headerItems - 1, 0)`：

| 状态 | 首项是谁 | 屏幕上的样子 |
|---|---|---|
| `筛选签在刊头下` | 刊头 / 状态 | 刊头可见，筛选签跟着列表走 |
| `筛选签贴顶位(未吸顶)` | **筛选签自己** | 在屏幕顶上，但**比吸顶位低一整个列表顶部内边距**（`Space.section` = 30dp） |
| `筛选签吸在顶` | 已经滚过的某条内容 | **吸**在屏幕最上面 |

`(headerItems - 1, 0)` 恰好落在**中间那一格**上。于是「吸顶」的档切到「贴顶位」的档，
整条栏**往下沉一档**。用户 2.1.4 / 2.1.5 两次录屏里都是它，而日志里两格同名
（都叫「贴顶」），所以**从日志上根本看不出来**。

原因是 `contentPadding.top = Space.section`：「下标 + 偏移」的原点落在**内容区起点**，
而吸顶线在它**上面**一整个内边距（`LazyList` 的 sticky header 贴列表顶部，不受内边距影响）。
真机日志给过直接证据（同一个档，两次落点）：

    顶部 4/43 条 · 筛选签 偏 -98px   ← 下标 3（内容）→ 已吸顶
    顶部  3/5 条 · 筛选签 偏   0px   ← 下标 2（筛选签自己）→ 低一档

所以 2.1.6 做两件事：① 落点改成 `headerItems`（第一条内容，把筛选签**推**进吸顶位）；
② 把三种状态各给一个名字，日志不再自欺。

**不变式（本文件 ⑦ 那一节就是在验它）**：

    落点 = 要么「头部区原样搬运」（像素级精确）
           要么「首项下标 >= headerItems」（筛选签必然吸顶）
    **绝不允许落在 headerItems - 1 上。**
"""

import pathlib
import re
import sys

TOOLS = pathlib.Path(__file__).resolve().parent
KT = TOOLS.parent / "app/src/main/java/com/example/englishcommute"

PASS = 0
FAIL = 0


def check_true(name: str, ok: bool, why: str = "") -> None:
    global PASS, FAIL
    if ok:
        PASS += 1
        print(f"  ✅ {name}")
    else:
        FAIL += 1
        print(f"  ❌ {name}")
        if why:
            print(f"     ↳ {why}")


def section(title: str) -> None:
    print(f"\n{'=' * 74}\n{title}\n{'=' * 74}")


def read(rel: str) -> str:
    return (KT / rel).read_text(encoding="utf-8")


def strip_comments(src: str) -> str:
    """去掉行注释与块注释。判据要落在**代码**上，而不是落在解释它的注释上。"""
    src = re.sub(r"/\*.*?\*/", "", src, flags=re.S)
    return re.sub(r"//[^\n]*", "", src)


ANCHOR = strip_comments(read("ui/LibraryAnchor.kt"))
SCREEN = strip_comments(read("ui/LibraryScreen.kt"))
VM = strip_comments(read("ui/LibraryViewModel.kt"))

HEADER = 3          # LIBRARY_HEADER_ITEMS：刊头 / 状态 / 筛选签
TOP = {"headerIndex": 0, "headerOffsetPx": 0, "episodeId": None, "offsetPx": 0,
       "atOrigin": False, "noContentId": False}

# 「筛选签贴在哪儿」的三种说法 —— 与 LibraryAnchor.kt 的字面量必须**逐字一致**。
# 两处各写一份的话，改一处漏一处；而「日志两行对不上」会被读成「又跳了」。
L_PINNED = "筛选签吸在顶"
L_AT_ORIGIN = "筛选签贴顶位(未吸顶)"
L_BELOW = "筛选签在刊头下"


# ===========================================================================
# Ⅰ. 规则表
#
#   用 Python 重实现一遍的意义不在于「验 Kotlin」（那由 Ⅱ 负责），
#   而在于**把规则钉死**：规则一旦被改，改动者必须同时改这张表和 Kotlin，
#   否则两边对不上 —— 而「两边对不上」正是这类 bug 的历史形态。
# ===========================================================================
section("Ⅰ、规则表：落点由**现在在哪儿**决定，不由目标档的历史决定")


def capture(index: int, offset_px: int, ids: list, header_items: int = HEADER):
    """对应 LibraryAnchor.capture"""
    safe = max(0, offset_px)
    # ⚠️★ 判据是 `index < header_items`，**含** `header_items - 1`（筛选签自己）。
    #    「筛选签自己在首项」那一格在屏幕上比吸顶位**低一档**（Space.section），
    #    归进「吸顶」就会让落点差 30dp —— 那是 2.1.4 犯过的错。
    #    而它在头部区里是**可跨档搬运**的（三档头部 item 相同）→ 像素级精确。
    if index < header_items:
        return {"headerIndex": max(0, index), "headerOffsetPx": safe,
                "episodeId": None, "offsetPx": 0,
                "atOrigin": index == header_items - 1, "noContentId": False}
    k = index - header_items
    # ⚠️★ k >= len(ids)（拿不到条目：空档、或列表刚被换掉那一帧）**算吸顶但没有
    #    内容锚**（noContentId）—— 必须与 Kotlin 的 `ids.getOrNull(k)` 语义逐字对齐。
    #    ⚠️ 这里有个 Python 特有的坑：写成 `ids[k]` 的话越界会 IndexError，
    #    而 k=-1 会从**尾部**取到一条别的内容（Python 负索引），Kotlin 返回 null。
    #    两份实现一旦语义不同，「规则表」就失去了它存在的意义 —— 它本来就是为了钉住规则。
    if k < 0 or k >= len(ids):
        return {"headerIndex": 0, "headerOffsetPx": 0,
                "episodeId": None, "offsetPx": 0,
                "atOrigin": False, "noContentId": True}
    return {"headerIndex": 0, "headerOffsetPx": 0,
            "episodeId": ids[k], "offsetPx": safe,
            "atOrigin": False, "noContentId": False}


def pinned(anchor):
    """对应 ListAnchor.pinned —— **只有「首项已滚进内容区」才算吸顶**。

    ⚠️ 2.1.6 改的口径：`atOrigin`（首项正好是筛选签）**不算吸顶** ——
    它在屏幕上比吸顶位低一整个 Space.section（30dp）。2.1.4 曾经把它算进来，
    后果就是「从吸顶的档切过去，整条栏往下沉一档」。
    """
    return anchor is not None and (
        anchor["episodeId"] is not None or anchor.get("noContentId", False))



def resolve(ids: list, target, current, header_items: int = HEADER):
    """对应 LibraryAnchor.resolve"""
    cur = current or TOP
    # ① 没吸顶 → 原样搬头部区那一对（含「贴顶位」那一格）→ 像素级精确
    if not pinned(cur):
        return (min(max(cur["headerIndex"], 0), header_items - 1),
                max(0, cur["headerOffsetPx"]))
    t = target
    # ⚠️ 判据是 `episodeId is not None`，不是 `pinned(t)` ——
    #    noContentId 那种锚也是吸顶，但没有内容锚（空档）。
    if t is not None and t["episodeId"] is not None:
        try:
            i = ids.index(t["episodeId"])
        except ValueError:
            i = -1
        if i >= 0:
            return (header_items + i, t["offsetPx"])
    # ③ ★ 确定值 = **第一条内容**（不是筛选签自己）→ 筛选签必然被推进吸顶位
    return (header_items, 0)


def label_of(index: int, header_items: int = HEADER) -> str:
    """对应 LibraryAnchor.labelOf —— 判据必须与 capture 逐字一致（三分支）"""
    if index >= header_items:
        return L_PINNED
    if index == header_items - 1:
        return L_AT_ORIGIN
    return L_BELOW


def label(anchor) -> str:
    """对应 ListAnchor.label"""
    if pinned(anchor):
        return L_PINNED
    if anchor is not None and anchor.get("atOrigin", False):
        return L_AT_ORIGIN
    return L_BELOW


IDs = [f"ep{i}" for i in range(10)]
PINNED_NOW = capture(HEADER + 4, 200, IDs)      # 「现在贴顶」（第 5 条在上方）
CUR_HEAD = capture(0, 0, IDs)                   # 「现在在刊头下」
CUR_HEAD2 = capture(1, 30, IDs)                 # 现在停在状态项中间
TARGET_DEEP = capture(HEADER + 2, 120, IDs)     # 目标档上次停在第 3 条

# ① 现在在刊头下 → 新档也停在同一个头部位置（零位移）
check_true("现在停在顶部 → 新档也停在顶部（画面一个像素都不动）",
           resolve(IDs, TARGET_DEEP, CUR_HEAD) == (0, 0),
           "⚠️ 这是 2.1.1 修的核心：拿目标档的历史当落点，"
           "筛选签会从这里突然吸到顶上 —— 用户说的「一会儿高一会儿低」")
check_true("  · 停在「头部#1 偏 30px」→ 原样搬（三档头部 item 相同）",
           resolve(["x", "y"], TARGET_DEEP, CUR_HEAD2) == (1, 30))
check_true("  · 越界下标（列表刚被换掉那一帧）→ 保持**吸顶**，不掉回刊头",
           resolve(["x"], TOP, capture(99, 0, ["only"])) == (HEADER, 0),
           "⚠️ 2.1.5 改了这一格：旧行为在拿不到内容锚时回退到「刊头下」，"
           "而那是**同一个错**的另一半 —— 与「空档滚到吸顶」共用一条修法。"
           "既然切走时是吸顶状态，换了内容之后就该继续吸顶")
check_true("  · 而停在头部区时，越界下标仍然钳回头部范围",
           resolve(["x"], TOP, capture(1, 0, ["only"])) == (1, 0),
           "只有「首项已经在内容区」才谈得上保持吸顶；头部区该照旧")

# ② 现在吸顶 → 新档也必须吸顶
check_true("现在吸顶 + 目标档从未访问过 → 落到**第一条内容**（吸顶位）",
           resolve(["a", "b", "c"], None, PINNED_NOW) == (HEADER, 0),
           "少了这一条，切到一个内容很少的档会落回头部区，"
           "筛选签就当着用户的面掉回刊头下面。"
           "⚠️★ 落点是 `HEADER`（第一条内容）而不是 `HEADER - 1`（筛选签自己）—— "
           "后者停在「内容区起点」上，比吸顶线低一整个 Space.section（30dp），"
           "那就是用户说的「往下沉一下」（2.1.6 修的那一行）")
check_true("  · 空档（收藏一条都没有）也一样",
           resolve([], None, PINNED_NOW) == (HEADER, 0))
check_true("  · 目标档有旧位置、但那条内容不在了 → 仍是**确定值**",
           resolve(["other"], TARGET_DEEP, PINNED_NOW) == (HEADER, 0),
           "交给 LazyColumn 钳一个越界下标会钳到列表末尾 —— 「掉到最底下」")

# ②b ★★ 「首项正好是筛选签」（index == HEADER - 1）—— 单独一格，而且**不算吸顶**。
#
#   这一格前后被误解过两次，值得写清楚：
#
#   · 2.1.4 之前：`capture` 的判据是 `index < headerItems`，这一格掉进「内容锚」
#     分支、`ids.getOrNull(-1)` 取到 null → `return TOP` → **日志说「在刊头下」，
#     而落点其实是对的**。这一对自相矛盾的行，让「切筛选不跳」被误修了三轮
#     （2.1.1 / 2.1.2 / 2.1.3）。
#   · 2.1.4～2.1.5：把它算成「吸顶」。日志不再自相矛盾，但**落点从此固定落在
#     这一格上** —— 而这一格比真吸顶**低 30dp**（`contentPadding.top = Space.section`）。
#     用户两次录屏里报的「往下沉一下」就是它。
#
#   2.1.6 的正解：它属于**头部区**（三档头部 item 相同 → 可原样搬运 → 像素级精确），
#   但**不是吸顶**。名字也得分开叫（`筛选签贴顶位(未吸顶)`）。
CUR_AT_TABS = capture(HEADER - 1, 0, IDs)      # 筛选签正好在顶上、还没滚出任何条目
check_true("★2.1.6 首项正好是筛选签 → 是「贴顶位」，**不算吸顶**",
           not pinned(CUR_AT_TABS) and label(CUR_AT_TABS) == L_AT_ORIGIN,
           "它在屏幕上比吸顶位低一整个 Space.section（30dp）。"
           "把它算成吸顶 → 落点就会落到它身上 → 从吸顶的档切过来就往下沉一档")
check_true("  · 而它切档时**原样搬那一对**（所以画面还是像素级不动）",
           resolve(["a", "b", "c"], None, CUR_AT_TABS) == (HEADER - 1, 0),
           "⚠️ 结果同样是 (2, 0)，看着和坏实现一样 —— 但走的是第 ① 支"
           "（没吸顶 → 原样搬头部），不是第 ③ 支（吸顶 → 确定值）。"
           "区别在**含义**：前者是「这一对坐标搬到哪一档都是同一个画面」，"
           "后者是「保证吸顶」。靠结果值分辨不出来，所以 ⑦ 那条不变式才必要")
check_true("  · 两种「没有内容锚」必须分开（它属于前者）",
           CUR_AT_TABS["episodeId"] is None
           and CUR_AT_TABS["atOrigin"] is True
           and CUR_AT_TABS["noContentId"] is False,
           "首项是筛选签自己（头部区、**未**吸顶） vs 首项是内容但取不到 id"
           "（空档、已吸顶）。2.1.4 把前者并进后者，代价就是那 30dp")
check_true("  · 日志口径与它逐字一致（下标 3 吸顶 / 2 贴顶位 / 1 在刊头下）",
           label_of(HEADER) == L_PINNED
           and label_of(HEADER - 1) == L_AT_ORIGIN
           and label_of(HEADER - 2) == L_BELOW,
           "labelOf 与 capture 的判据分叉过一次，代价就是那三轮白改")

# ②c ★★ 2.1.5 新增：**空档滚到贴顶** —— 用户在真机上实际就是这么复现的。
#
#   「收藏」一条内容都没有时 `ids` 是空的。用户照样能把列表往下滚
#   （空状态块自己撑满一屏，列表滚得动），滚到贴顶后首项下标 >= HEADER，
#   但**取不到条目 id**（列表里根本没有条目）。
#
#   旧实现在这一格 `return TOP`，于是这个锚被记成「在刊头下」；
#   下一次切档时 `pinned` 是 false，落点走「原样搬头部」那一支 ——
#   整页掉回顶部。用户在录屏里看到的正是「在收藏里滚到贴顶、切回全部，
#   页面往下沉了一下」（配 `diag(8).log` 16:50:19 那两行确认）。
CUR_EMPTY_PINNED = capture(HEADER + 2, 40, [])       # 空档滚到吸顶（列表里没有条目）
check_true("★2.1.5 空档滚到吸顶 → 算**吸顶**（不是「在刊头下」）",
           pinned(CUR_EMPTY_PINNED) and label(CUR_EMPTY_PINNED) == L_PINNED,
           "拿不到内容锚 ≠ 在刊头下。旧代码把这两件事混成了一件，"
           "而这个错误判断会**传播**到下一次切档")
check_true("  · 从「空档的吸顶」切到别档 → 新档也吸顶（不再掉回顶部）",
           resolve(IDs, None, CUR_EMPTY_PINNED) == (HEADER, 0),
           "这就是那个「往下沉」的完整修法：capture 认得它是吸顶之后，"
           "resolve 的吸顶分支会把新档放到**第一条内容**上（筛选签必然吸顶）")
check_true("  · 而空档**停在顶部**时仍然算「在刊头下」（别修过头）",
           not pinned(capture(0, 0, []))
           and not pinned(capture(HEADER - 2, 0, []))
           and not pinned(capture(HEADER - 1, 0, [])),
           "空档停在最上面时用户看到的就是刊头 —— 那必须仍然是「在刊头下」"
           "（或「贴顶位」），否则切档时会把刊头也一并滚掉")

# ③ 贴顶 + 目标档有旧位置 → 落到那一条（**且仍然贴顶**）
check_true("现在贴顶 + 目标档上次停在第 3 条 → 落到第 3 条",
           resolve(IDs, TARGET_DEEP, PINNED_NOW) == (HEADER + 2, 120),
           "这条保住「切回去还在原地」—— 它是与「不跳」共存的那一半")
check_true("  · 而它算出来的首项一定 >= 头部 item 数（所以筛选签必然贴顶）",
           resolve(IDs, TARGET_DEEP, PINNED_NOW)[0] >= HEADER,
           "内容锚落点的首项是 headerItems + i，i>=0 —— 贴顶是结构上保证的")
check_true("  · 列表前面插入两条新内容后，仍然落在 ep2 上（不是「第 5 行」）",
           resolve(["new1", "new2"] + IDs, TARGET_DEEP, PINNED_NOW) == (HEADER + 4, 120),
           "下标方案在这里会落在 new1 上 —— 那就是更早那几版的「乱跳」")
check_true("  · 像素偏移原样保留（不是只还原到「第几条」）",
           resolve(IDs, TARGET_DEEP, PINNED_NOW)[1] == 120)

# ④ 归零：App 刚起来
check_true("当前锚为 null（App 刚起来）→ 顶部",
           resolve(IDs, None, None) == (0, 0))
check_true("  · 此时目标档有内容锚也用不上（新档不该在第一次打开就跳）",
           resolve(IDs, TARGET_DEEP, None) == (0, 0))

# ⑤ 负的像素偏移 → 钳到 0
check_true("负的像素偏移 → 钳到 0",
           capture(HEADER, -50, IDs)["offsetPx"] == 0
           and resolve(IDs, None, capture(1, -9, IDs)) == (1, 0))

# ⑥ 日志口径：只有三种说法，而且两处**必须给出同一个字符串**
check_true("「吸顶 / 贴顶位 / 在刊头下」是仅有的三种说法，且两处一致",
           {label(PINNED_NOW), label(CUR_HEAD), label(CUR_AT_TABS)}
           == {label_of(HEADER + 5), label_of(0), label_of(HEADER - 1)}
           == {L_PINNED, L_AT_ORIGIN, L_BELOW},
           "两条日志要能逐字比对；说法不一致就比对不出「没跳」。"
           "⚠️ 2.1.6 从两种变三种：前两轮把「吸顶」和「贴顶位」同名 —— "
           "而它们在屏幕上差 30dp，同名就等于**不可能靠日志发现差异**")

# ⑦ ★★ 2.1.6 新增：**不变式**（「不跳」的全部理由都在这条上）
#
#   落点只允许两个来源：
#     · 没吸顶 → 原样搬头部区那一对（下标 <= headerItems - 1）→ 像素级精确
#     · 吸顶   → 首项下标 >= headerItems → 筛选签必然被推进吸顶位
#   **绝不允许落在 headerItems - 1（筛选签自己）上** —— 那是在第 ③ 支里
#   写 `headerItems - 1` 的结果（2.1.1～2.1.5 一直是这么写的），
#   它在屏幕上比吸顶位低一整个 Space.section。
#
#   ⚠️ 这条不变式必须**只能靠结构**判定，不能靠对几个具体输入的期望值 ——
#      因为「(2, 0)」这个结果在第 ① 支（正确）和第 ③ 支（错误）下都可能出现，
#      **只看结果值分辨不出来**。所以这里遍历输入空间，判的是「哪一支的产物」。
def invariant_violations(resolve_fn):
    """返回所有「吸顶了、却落到头部区」的落点（应当为空）"""
    bad = []
    for idx, off in [(0, 0), (1, 30), (2, 0), (2, 40), (3, 0), (3, 7),
                     (5, 200), (99, 0), (HEADER + 4, 77)]:
        for cur in [capture(idx, off, IDs), capture(idx, off, []),
                    TOP, None, PINNED_NOW, CUR_AT_TABS, CUR_EMPTY_PINNED]:
            for ids in [IDs, [], ["other"], ["a", "b"]]:
                for tgt in [None, TARGET_DEEP, CUR_AT_TABS, TOP]:
                    spot = resolve_fn(ids, tgt, cur)
                    if pinned(cur) and spot[0] < HEADER:
                        bad.append((idx, off, ids, spot))
    return bad


check_true("★2.1.6 不变式：吸顶的档，落点首项必须 >= headerItems",
           not invariant_violations(resolve),
           "写 `headerItems - 1` 会满足所有「期望值」用例（因为第 ① 支也会给出 "
           "(2, 0)），只有结构判定抓得住 —— 见下面那个坏实现对照")


# ===========================================================================
# Ⅱ. 源码契约
# ===========================================================================
section("Ⅱ、源码契约")

check_true("纯函数模块存在且与界面分离（可单测、可被 Python 重实现）",
           "object LibraryAnchor" in ANCHOR and "data class ListAnchor" in ANCHOR)
check_true("  · capture 的头部区判据是 `index < headerItems`（**含**筛选签自己）",
           "if (index < headerItems) {" in ANCHOR
           and "atOrigin = index == headerItems - 1" in ANCHOR
           and "val id = ids.getOrNull(index - headerItems)" in ANCHOR
           and "return ListAnchor(noContentId = true)" in ANCHOR,
           "⚠️ 这一格的边界改过两次，每次代价都是真机上的 30dp："
           "① 旧代码把它并进「在刊头下」（日志说错话）；"
           "② 2.1.4 把它挪去「吸顶」（落点从此固定落在它身上 → 往下沉一档）；"
           "③ 2.1.6 挪回**头部区**（三档头部 item 相同 → 原样搬运是像素级精确），"
           "另给它一个名字。注意 `noContentId`（空档、已吸顶）是另一个人，别混")
check_true("  · resolve 的**第一条**分支是「现在没吸顶 → 原样搬头部」",
           re.search(r"fun resolve\([\s\S]{0,400}?if \(!cur\.pinned\) \{", ANCHOR) is not None,
           "顺序不能反：先判 target 就退回了 2.0.4 的行为")
check_true("  · 第二条分支要求 target **指向内容**才用它",
           "if (t != null && t.episodeId != null) {" in ANCHOR,
           "不判的话会把「头部锚」或「noContentId 那种无内容锚的吸顶锚」当成内容锚"
           "去 `indexOf(null)` —— 位置白丢。"
           "⚠️ 判据用 episodeId 而不是 pinned：pinned 现在也包含 noContentId")
check_true("  · ★ 第三条的确定值是**第一条内容**，不是筛选签自己（2.1.6）",
           "return resolveSpot(headerItems)" in ANCHOR
           and "ListSpot(headerItems, 0)" in ANCHOR
           and "ListSpot(headerItems - 1, 0)" not in ANCHOR,
           "最后半条是**反向**断言：只要 `headerItems - 1` 重新出现在落点里，"
           "就会回到「往下沉一档」。坏样本 H 验它有分辨力")
check_true("  · `pinned` 的定义只有一处，且**只认「首项已滚进内容区」**",
           ANCHOR.count("val pinned: Boolean get() =") == 1
           and "episodeId != null || noContentId" in ANCHOR,
           "⚠️ 2.1.6 的口径：`atOrigin`（首项正好是筛选签）**不算吸顶** —— "
           "它在屏幕上比吸顶位低一整个 Space.section（30dp）。"
           "2.1.4 把它算进来，就是那个「往下沉一档」的直接原因")

check_true("界面把**两个**锚都传进 resolve（缺一个就等于没改）",
           "target = vm.anchorFor(filter)" in SCREEN
           and "current = vm.anchorBeforeSwitch()" in SCREEN)
check_true("  · ★ current 用的是「切走那一刻」的快照，不是目标档自己的",
           "vm.anchorBeforeSwitch()" in SCREEN,
           "用 vm.anchorFor(filter) 当 current 就等于「目标档优先」，"
           "筛选签会在两种高度之间来回跳 —— 正是用户报的那个")
check_true("  · 落点是 LazyListState 的**构造参数**（切换时不需要滚动动作）",
           "LazyListState(spot.index, spot.offsetPx)" in SCREEN)
check_true("  · 每档各自一份状态（`remember(filter)`）",
           "val listState = remember(filter) {" in SCREEN)
check_true("  · 位置由滚动的每一帧连续记录（切走不需要收尾动作）",
           "snapshotFlow {" in SCREEN and "vm.rememberAnchor(" in SCREEN
           and "LibraryAnchor.capture(index, offsetPx, latestIds.value, LIBRARY_HEADER_ITEMS)" in SCREEN)
check_true("  · 滚动回调读的是**最新**的 id 列表（不然会读到过期的那一份）",
           "rememberUpdatedState(ids)" in SCREEN and "latestIds.value" in SCREEN)

check_true("ViewModel 里存了「切走那一刻」的快照字段",
           "private var anchorBeforeSwitch: ListAnchor? = null" in VM)
check_true("  · 而它是在**换档之前**拍的（顺序反了就取到新档的位置）",
           re.search(r"anchorBeforeSwitch = anchors\[from\][\s\S]{0,400}?_filter\.value = filter",
                     VM) is not None,
           "先 `_filter.value = filter` 再读 anchors 的话，"
           "读到的是新档刚滚出来的位置，规则就退回「看目标档的历史」")
check_true("  · `anchorFor` 已经不再做「继承」（它是坏的来源）",
           "fun anchorFor(target: LibraryFilter): ListAnchor? = anchors[target]" in VM
           and "LibraryAnchor.inherit" not in VM)


def switch_path_has_scroll(src: str) -> bool:
    """切换筛选的代码块里有没有滚动调用。**同一条判据同时用于真源码与坏样本** ——
    否则坏样本只是「被改动了」，证明不了断言有分辨力。"""
    return re.search(r"onSelect = \{ next ->[\s\S]{0,1200}?(scrollToItem|scrollBy|requestScrollTo)",
                     src) is not None


check_true("切换路径上没有任何滚动调用（硬契约，坏样本 C 验它）",
           not switch_path_has_scroll(SCREEN),
           "有人为了「修一下位置」加一句滚动，就等于把时序问题请回来")


# ===========================================================================
# Ⅲ. 坏样本：把真源码做一处外科手术，断言必须被判失败
# ===========================================================================
section("Ⅲ、坏样本对照")

# A. 退回 2.0.4：落点由目标档决定（`current` 被替换成 `target`）
BAD_SRC_PRIORITY = ANCHOR.replace("if (!cur.pinned) {", "if (!pinned(t)) {", 1)


def bad_resolve_priority(ids, target, current, header_items=HEADER):
    """坏实现：拿**目标档**的历史决定吸不吸顶"""
    t = target or TOP
    if not pinned(t):
        return (min(max(t["headerIndex"], 0), header_items - 1), max(0, t["headerOffsetPx"]))
    if pinned(t):
        try:
            i = ids.index(t["episodeId"])
        except ValueError:
            i = -1
        if i >= 0:
            return (header_items + i, t["offsetPx"])
    return (header_items - 1, 0)


check_true("坏样本 A（落点改回「目标档优先」= 2.0.4 的写法）被拦下",
           "if (!cur.pinned) {" not in BAD_SRC_PRIORITY
           and BAD_SRC_PRIORITY != ANCHOR
           and bad_resolve_priority(IDs, TARGET_DEEP, CUR_HEAD) != resolve(IDs, TARGET_DEEP, CUR_HEAD),
           "这条坏样本精确复现用户报的症状：现在停在刊头下，"
           "切过去却因为「那一档上次滚到第 3 条」而把筛选签吸到顶上")

check_true("  · 而它对「本来就在同一状态」的两档给出的答案**相同**（说明它不是恒假）",
           bad_resolve_priority(IDs, TARGET_DEEP, PINNED_NOW)
           == resolve(IDs, TARGET_DEEP, PINNED_NOW),
           "⚠️ 坏样本必须只在**真出问题的那一格**上失败。"
           "如果它在所有输入上都给出不同答案，那它证明不了这条断言有分辨力")

# B. 贴顶时不再要求目标档也贴顶（等于「允许落回头部区」）
#
# ⚠️ 替换锚点随实现换代（2.1.4）：判据从 `t.pinned` 改成了 `t.episodeId != null`
#    —— 因为 atTabs 那种锚也是「贴顶」但没有内容锚，拿 null 去 indexOf 是白走。
#    **锚点过期会让坏样本变成「没改动的源码」，从而假通过**，
#    所以这条 `BAD_SRC_PIN != ANCHOR` 的断言是必需的，不是装饰。
BAD_SRC_PIN = ANCHOR.replace("if (t != null && t.episodeId != null) {",
                             "if (t != null) {", 1)
check_true("坏样本 B（去掉「target 必须指向内容」这一判）被拦下",
           BAD_SRC_PIN != ANCHOR
           and "if (t != null && t.episodeId != null) {" not in BAD_SRC_PIN,
           "一个头部锚（或 atTabs 那种「贴顶但没有内容锚」的锚）"
           "会被当成内容锚去查 id，位置白丢")

# C. 切换路径上加一句滚动
BAD_SCROLL = SCREEN.replace("vm.setFilter(next)",
                            "listState.scrollToItem(LIBRARY_HEADER_ITEMS - 1)\n"
                            "                        vm.setFilter(next)", 1)
check_true("坏样本 C（在切换路径上加一句滚动）被拦下",
           switch_path_has_scroll(BAD_SCROLL) and BAD_SCROLL != SCREEN,
           "加一句「修一下位置」，就把时序问题请回来了")

# D. 用 target 当 current（界面这一层写错）
BAD_WIRING = SCREEN.replace("current = vm.anchorBeforeSwitch()", "current = vm.anchorFor(filter)", 1)
check_true("坏样本 D（界面上把 current 传成目标档自己的锚）被拦下",
           "current = vm.anchorBeforeSwitch()" not in BAD_WIRING and BAD_WIRING != SCREEN,
           "这是最容易被「顺手改一下」的一处 —— 而它的表现是回退到 2.0.4 的症状")

# E. ViewModel：先换档再拍快照
BAD_ORDER = VM.replace(
    "anchorBeforeSwitch = anchors[from]\n", "", 1).replace(
    "_filter.value = filter", "_filter.value = filter\n        anchorBeforeSwitch = anchors[from]", 1)
check_true("坏样本 E（先换档、再拍快照）被拦下",
           not re.search(r"anchorBeforeSwitch = anchors\[from\][\s\S]{0,400}?_filter\.value = filter",
                         BAD_ORDER),
           "顺序反了会读到新档刚滚出来的位置")

# F. 每档共用一份滚动状态
BAD_SHARED = SCREEN.replace("val listState = remember(filter) {", "val listState = remember {", 1)
check_true("坏样本 F（三档共用一份滚动状态）被拦下",
           "val listState = remember(filter) {" not in BAD_SHARED and BAD_SHARED != SCREEN,
           "共用一份 = 每次切换都要搬位置，那是 1.2.3～1.2.6 反复出问题的地方")

# G. 把「现在」这一路整个删掉（只留目标档那一路）
BAD_NO_CURRENT = ANCHOR.replace("current: ListAnchor?,", "current: ListAnchor? = null,", 1)
check_true("坏样本 G（把 current 变成可有可无的默认参数）被拦下",
           "current: ListAnchor?," not in BAD_NO_CURRENT and BAD_NO_CURRENT != ANCHOR,
           "给了默认值之后，下一个人就可能不传它 —— 而那时规则又变回目标档优先")

# H. ★★ 2.1.6：把吸顶落点写回「筛选签自己」（= 低一档，前四轮就是它）
BAD_SRC_ORIGIN = ANCHOR.replace("ListSpot(headerItems, 0)", "ListSpot(headerItems - 1, 0)", 1)
check_true("坏样本 H（吸顶落点写回「筛选签自己」）被拦下",
           BAD_SRC_ORIGIN != ANCHOR
           and "ListSpot(headerItems - 1, 0)" not in ANCHOR,
           "⚠️ 后半条才是真断言（源码契约那一节也有一条同样的）："
           "只要 `headerItems - 1` 重新出现在落点里，整条栏就会比吸顶位低"
           "一整个 Space.section（30dp）。前半条 `!= ANCHOR` 是防「替换锚点过期"
           "→ 坏样本其实没改动 → 假通过」")


def bad_resolve_origin(ids, target, current, header_items=HEADER):
    """坏实现：吸顶落点写回 `header_items - 1`（筛选签自己）—— 2.1.1～2.1.5 的写法。
    ⚠️ 它满足**所有**「期望值」用例（包括空档那条，因为 (2, 0) 同时也是
    第 ① 支的合法产物），**只有 ⑦ 那条结构性不变式抓得住它** —— 这正是
    那条不变式存在的理由。"""
    cur = current or TOP
    if not pinned(cur):
        return (min(max(cur["headerIndex"], 0), header_items - 1),
                max(0, cur["headerOffsetPx"]))
    t = target
    if t is not None and t["episodeId"] is not None:
        try:
            i = ids.index(t["episodeId"])
        except ValueError:
            i = -1
        if i >= 0:
            return (header_items + i, t["offsetPx"])
    return (header_items - 1, 0)


check_true("  · 而 ⑦ 那条不变式一眼看穿它（不靠把某个期望值写对）",
           invariant_violations(bad_resolve_origin) != []
           and invariant_violations(resolve) == [],
           "坏实现的吸顶落点是 (2, 0)：**下标 2 = 筛选签自己** —— 比吸顶位低"
           "一整个 Space.section（30dp）。而它在「结果值」层面只是一个普通的"
           "二元组，跟正确的 (3, 0) 摆在一起才看得出差一格；"
           "**判「吸顶的档不许落到头部区」是结构判定，不依赖任何期望值写对没有** —— "
           "这就是 invariants 比 examples 值钱的地方")


# ===========================================================================
# Ⅳ. 日志口径（真机上唯一的验收凭据）
# ===========================================================================
section("Ⅳ、日志口径（切档前后两行必须能逐字比对）")

check_true("切档那行报的是**当前**档的位置（用户要拿它跟落点比）",
           'DiagLog.act(\n            "切筛选 → ${filter.label} · 从 " +\n'
           '                (anchorBeforeSwitch ?: LibraryAnchor.TOP).label(),' in VM,
           "报「目标档打算用的锚」是答非所问：那正是导致跳动的那个值")
check_true("落点那行报的是**实际**落点（下标 + 人话状态 + 筛选签的实测位置）",
           "LibraryAnchor.labelOf(top, LIBRARY_HEADER_ITEMS)" in SCREEN
           and "顶部 ${top + 1}/${info.totalItemsCount} 条" in SCREEN
           # ★ 2.1.4：把筛选签那一栏的**像素偏移**直接量出来，不再从下标推断 ——
           #   见下面那条断言的说明
           and "it.index == LIBRARY_HEADER_ITEMS - 1" in SCREEN
           and "tabsItem.offset == 0" in SCREEN,
           "落点必须报「实际是谁、在哪儿」，而不是让读日志的人自己换算")
check_true("  · ★2.1.4 筛选签的位置是**量**出来的，不是从下标推断的",
           "val tabsItem = info.visibleItemsInfo" in SCREEN
           and "吸在顶(完全滚过)" in SCREEN
           # ⚠️ 负偏移那一格不能漏：它表示「已滚出屏幕、正吸附」，
           #    漏了就会把吸附报成「在刊头下(偏 -98px)」——与上一轮误导排查的
           #    那类错误同源（结论必须落在事实上）
           and "tabsItem.offset < 0" in SCREEN,
           "推断链上有一环写错过（labelOf 比 capture 多吞一个下标），"
           "后果是每一条正确的吸顶都被报成「在刊头下」，"
           "而排查会一路去怀疑落点算法 —— 这个歧义浪费了三轮。"
           "直接量之后，日志不可能再给出与事实相反的结论")
check_true("  · ★2.1.6 而「量出来是 0」那一格的名字要**说真话**",
           '"贴顶位(未吸顶)"' in SCREEN and '"正好贴顶"' not in SCREEN,
           "`offset == 0` 表示筛选签停在「内容区起点」上 —— 它比真吸顶低一整个"
           "Space.section（30dp）。旧名字叫「正好贴顶」，于是**两个差 30dp 的状态"
           "共用一个名字**，日志上根本看不出用户报的那个沉；"
           "而它就是 2.1.4 / 2.1.5 两轮「改了但没修好」的原因")
check_true("  · 落点读得够晚（等一帧，不然读到的是旧列表的 layoutInfo）",
           re.search(r"LaunchedEffect\(listState\) \{\s*withFrameNanos \{ \}", SCREEN) is not None)
check_true("  · 三种说法各只定义**一次**（label 与 labelOf 共用同一组常量）",
           ANCHOR.count(f'"{L_PINNED}"') == 1
           and ANCHOR.count(f'"{L_AT_ORIGIN}"') == 1
           and ANCHOR.count(f'"{L_BELOW}"') == 1
           and "private const val LABEL_PINNED" in ANCHOR
           and "private const val LABEL_AT_ORIGIN" in ANCHOR,
           "两处各写一份字面量的话，改一处漏一处 —— "
           "而「两行对不上」会被读成「又跳了」，等于自己造了个假故障")


print(f"\n{'=' * 74}")
# ⚠️ 这行的格式要与 tools/run-all-verify.sh 的抓取正则对齐
#    （`全部通过：N 项` / `通过 N 项` / `全部 N 项通过`）——
#    对不齐的话总表上这一格是 `-`，而「没有计数」会被读成「没在跑」。
print(f"通过 {PASS} 项，失败 {FAIL} 项")
print("=" * 74)
sys.exit(1 if FAIL else 0)
