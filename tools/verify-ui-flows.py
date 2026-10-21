#!/usr/bin/env python3
"""
验证 1.1.0 的三条**交互**改动：达标庆祝页、三页签左右滑动、退回素材库自动定位。

## 为什么必须验它

这三条的失败形态全部是「**不报错，但用户会觉得哪里不对**」：

  · 庆祝信号不是从数据层那一处来的 → 跨过 40 分钟那一秒**偶尔不弹**，
    而且只在「计时器刚写完、状态流还没推到界面」的那个窗口里漏 ——
    复现概率极低，用户只会说「昨天弹了今天没弹」
  · 庆祝信号落盘 → **第二天打开 App 把昨天那次又庆祝一遍**
  · 烟花用随机数 → 每帧重算，每朵花每帧换形状，看起来像噪点而不是烟花
  · 底栏只按 currentPage 上色 → 划到一半底栏纹丝不动，松手「啪」地跳一下，
    看起来像两个互不相干的东西（这正是需求里那句「跟手平滑」）
  · 退回定位用布尔量而不是计数 → **第二次不再触发**，
    而「进去、出来、再进去、再出来」是通勤路上最平常的用法
  · 列表头部的 item 数与定位偏移量不同步 → 滚到隔壁一行，
    看起来像「定位不准」，实际是数错了

这几条在真机上都要「听满 40 分钟」「手指划到一半」才能看出来。源码契约是几毫秒。

源：`checkin/CheckInStore.kt`、`ui/CelebrationViewModel.kt`、`ui/CheckInCelebration.kt`、
    `MainActivity.kt`、`ui/LibraryScreen.kt`

用法：
    python3 tools/verify-ui-flows.py
"""

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
KT = ROOT / "app/src/main/java/com/example/englishcommute"


# ---------------------------------------------------------------------------
# 断言框架
# ---------------------------------------------------------------------------

PASS = 0
FAIL = 0
FAILURES = []


def check(name: str, got, want):
    global PASS, FAIL
    if got == want:
        PASS += 1
    else:
        FAIL += 1
        FAILURES.append(f"{name}\n      期望 {want!r}\n      实际 {got!r}")


def check_true(name: str, cond, detail=""):
    check(name, bool(cond), True)
    if not cond and detail:
        FAILURES.append(f"      ↳ {detail}")


def section(title: str):
    print(f"\n\033[1m{title}\033[0m")


def strip_comments(src: str) -> str:
    src = re.sub(r"/\*.*?\*/", lambda m: "\n" * m.group(0).count("\n"), src,
                 flags=re.S)
    src = re.sub(r"//[^\n]*", "", src)
    return src


def kt(rel: str) -> str:
    return (KT / rel).read_text(encoding="utf-8")


def body(src: str, signature: str) -> str:
    """
    按大括号配对截出一个函数的**函数体**。

    ⚠️ 不能用定长窗口（`src[i:i+1800]`）：本工程函数注释动辄几十行，
       定长窗口会把下一个函数的判据算进来，产生假阳性。
    """
    i = src.find(signature)
    if i < 0:
        return ""
    j = src.find("{", i)
    if j < 0:
        return ""
    depth = 0
    k = j
    while k < len(src):
        if src[k] == "{":
            depth += 1
        elif src[k] == "}":
            depth -= 1
            if depth == 0:
                return src[i:k + 1]
        k += 1
    return ""


# ---------------------------------------------------------------------------
# ① 庆祝信号：单一真相源、不落盘、只庆祝一次
# ---------------------------------------------------------------------------

section("① 庆祝信号")

store_raw = kt("checkin/CheckInStore.kt")
store = strip_comments(store_raw)

check_true("CheckInStore 持有 celebrate 状态",
           "private val _celebrate = MutableStateFlow(false)" in store)
check_true("对外暴露的是只读 StateFlow",
           "val celebrate: StateFlow<Boolean> = _celebrate.asStateFlow()" in store)
check_true("有 consumeCelebrate()（用户关掉时消费掉）",
           "fun consumeCelebrate()" in store)
check_true("消费即置 false",
           "_celebrate.value = false" in body(store, "fun consumeCelebrate()"))

# 单一真相源：庆祝信号必须由 addSeconds 里那个 justCompleted 置起
add = body(store, "fun addSeconds(")
# ⚠️ 3.3.0：目标从常量变成了设置项，所以这句里的 `GOAL_SECONDS` 跟着变成
#    `goalSeconds`。判据本身（「数据层算，不在界面比秒数」）一个字没变。
# ⚠️ 3.4.0：又跟着变成「和**那一天**的目标比」—— 局部变量 `goal` 现在来自
#    `goalSecondsOn(day)`。两次都是**同一件事的实现变化**，判据的意图没变：
#    这个信号只许由数据层算出来，界面自己再比一次秒数就是第二个真相源。
# ⚠️ 4.1.1：判据搬进了 SyncMerge.accumulate（纯函数）。意图没变：
# 这个信号只能由**数据层**算出来，界面自己比一次秒数就是第二个真相源。
check_true("justCompleted 由数据层算，不在界面比秒数",
           "SyncMerge.accumulate(" in add and "goalSecondsOn(day)" in add)
i_just = add.find("if (justCompleted) {")
check_true("庆祝信号置在 justCompleted 分支里", i_just > 0)
check_true("  · 且就在那个分支内（不是另起一个判据）",
           "_celebrate.value = true" in add[i_just:i_just + 400])
check("_celebrate.value = true 全文件只出现一次（第二个触发点就是第二个真相源）",
      store.count("_celebrate.value = true"), 1)

# 不落盘：第二天打开 App 不该把昨天那次再庆祝一遍
#
# ⚠️ 3.3.0 起是 **3** 个 key、**3** 处 put（多了 `goal`：每日目标）。
#    这三条断言的**意图**没变 —— 仍然在问「有没有给 celebrate 落盘」，
#    只是把那两个数字跟着实现的合法变化调了一次。
#    ⚠️ 改断言时把「为什么这次要改」写在这里，否则下一个人只看到数字变了，
#       会以为是在「为了让测试变绿而放宽」。
#
# ⚠️ 3.4.0 再调一次，同样是数字跟着实现走、意图一个字没变：
#    · key **4 个**：多出来的是 `KEY_LEGACY_GOAL`（3.3.0 那个不带日期的 `goal`）。
#      它只在迁移时被读一次、读完就删 —— 是一个**只读不写**的过渡键。
#    · put **从 3 处降到 2 处**：目标不再是一个「值」而是一份**按天的时间轴**，
#      写入走的是整份重写（`editor.putInt(KEY_GOAL + day, …)`），
#      所以 `prefs.edit().put…` 这个形态只剩 sec: 与 poster: 两处。
#    ★ 两条要一起改 —— 只调其中一条会让「数字跟着实现改」变成「为了让脚本变绿而放宽」。
#
# ⚠️ 4.0.0 又调一次，仍然是同一个理由（意图没变、数字跟着实现走）：
#    多出来的是 `KEY_OTHERS`（`others:<day>` = **其它设备**在这一天的合计）。
#    打卡时长从「一个数」拆成 mine + others 两半，是多设备同步的地基 ——
#    而 `prefs.edit().put` 的处数**没变**（others 由同步整份重写，
#    走的是 `editor.putInt`，与目标时间轴那条路同一个形状）。
check("落盘用的 key 有 5 个（sec: / others: / poster: / goal: / 迁移专用 goal）",
      store.count('private const val KEY_'), 5)
check_true("没有给 celebrate 落盘的 key",
           'KEY_CELEBRATE' not in store and '"celebrate"' not in store)
check_true("落盘写入只有 2 处（sec: / poster:）；目标的写入走整份重写",
           store.count("prefs.edit().put") == 2, str(store.count("prefs.edit().put")))
check_true("读取时只认前缀（这是「谁会被 reset 误伤」的唯一依据）",
           'startsWith(KEY_SEC)' in store and 'startsWith(KEY_POSTER)' in store
           and 'startsWith(KEY_GOAL)' in store,
           "★ 3.4.0：`goal:` 也带上前缀了。它不在被删的那两个之列，所以 reset() "
           "仍然不动它 —— 3.3.0 靠的是「不带前缀」，现在靠的是「不在名单里」，"
           "两条都成立，但写法变了（见 CheckInStore 的类注释）")

# reset() 也要清掉，否则「清空记录」之后立刻弹「打卡成功」
reset = body(store, "fun reset()")
check_true("reset() 里也清了庆祝信号（清记录不该紧跟着庆祝）",
           "_celebrate.value = false" in reset)

# ViewModel 只是转发，不自己算
cvm = strip_comments(kt("ui/CelebrationViewModel.kt"))
check_true("CelebrationViewModel 直接转发 store.celebrate（不复制状态）",
           "val celebrate: StateFlow<Boolean> = store.celebrate" in cvm)
check_true("dismiss 走 consumeCelebrate", "store.consumeCelebrate()" in cvm)
check_true("ViewModel 里没有自己再比一次秒数（那是第二个真相源）",
           "GOAL_SECONDS" not in cvm)
check_true("分享走的是打卡页同一条路径 CheckInShare.shareImage",
           "CheckInShare.shareImage(" in cvm)

# 海报是「等文件」而不是自己重画
#
# 1.2.0：解码入口从裸 `BitmapFactory.decodeFile` 换成了
# `CheckInPoster.decodeForDisplay`（带 inSampleSize=2 + RGB_565 降采样，
# 单份 5.93 MB → 0.78 MB）。**契约本身没变** —— 仍然是「读那个已经落盘的
# 文件」而不是「自己再渲染一张」，只是读法省内存了。
check_true("海报是轮询等文件落盘，不自己再渲染一张",
           "store.posterFor(" in cvm and "CheckInPoster.decodeForDisplay(" in cvm)
check_true("  · 而且走降采样入口，不裸调 BitmapFactory",
           "BitmapFactory.decodeFile" not in cvm,
           "1080×1440 ARGB_8888 是 5.93 MB，而这个 ViewModel 是 Activity 作用域")
# ⚠️ 1.2.7：「等不到」的退路变了。
#    1.2.6 及以前是「等 4 秒 → 放弃 → 庆祝页空着」（用户只能退出去、进打卡页看图），
#    现在是「等一小会儿 → 等不到就**自己补生成一张**」。
#    所以下面不再断言「退化成不带图的庆祝」，而是断言**兜底渲染存在**。
check_true("等待有上限（不会无限等）",
           "POSTER_WAIT_TRIES" in cvm and "for (attempt in 0 until POSTER_WAIT_TRIES)" in cvm)
check_true("★ 等不到必须自己补一张（1.2.7 修的就是「空着」）",
           "CheckInPoster.ensureFile(getApplication(), store, day)" in cvm,
           "用户报的原话：「弹出打卡成功页，但是生成不了海报图，只能退出去，在打卡页分享」——"
           "打卡页本来就有 render 兜底，庆祝页没有，同一件事两个答案")
# 等待窗口要**算一遍**，而不是看注释说了什么 —— 注释会过期，数字不会
m_tries = re.search(r"const val POSTER_WAIT_TRIES\s*=\s*(\d+)", cvm)
m_ms = re.search(r"const val POSTER_WAIT_MS\s*=\s*(\d+)L", cvm)
check_true("两个常量都在", m_tries is not None and m_ms is not None)
if m_tries and m_ms:
    wait_ms = int(m_tries.group(1)) * int(m_ms.group(1))
    # 1.2.7：窗口从 4 秒压到 ~1 秒以内。理由不是「等得起等不起」——
    # 等不到已经会自己补渲染，长等只剩下一个副作用：弹出后近 1 秒不给反馈。
    # 这个窗口现在的职责只是「让 ListeningClock 先画完，省一次重复渲染」
    check_true(f"等待窗口 {wait_ms}ms ≤ 1 秒（有兜底渲染之后，长等没有意义）",
               0 < wait_ms <= 1000, f"{wait_ms}ms")


# ---------------------------------------------------------------------------
# ② 庆祝页本身：关闭、分享、烟花
# ---------------------------------------------------------------------------

section("② 庆祝页")

cel_raw = kt("ui/CheckInCelebration.kt")
cel = strip_comments(cel_raw)

check_true("系统返回键接住了（行为和点叉一致）",
           "BackHandler { onDismiss() }" in cel)
check_true("右上角有单独的关闭按钮（44dp 触达区）",
           "size(44.dp)" in cel and "Glyph.Close" in cel)
i_close = cel.find("Glyph.Close")
check_true("  · 关闭按钮在恭喜区**之前**（也就是页面顶部）",
           0 < i_close < cel.find("今日打卡成功"), str(i_close))
check_true("有「分享到微信」按钮",
           '"分享到微信"' in cel)
check_true("onDismiss 至少两个入口（叉 + 返回键）",
           cel.count("onDismiss") >= 2, str(cel.count("onDismiss")))

# 海报还没就绪时分享按钮点不动 —— 但**不能只让它点不动**。
# 满亮的琥珀按钮按下去没反应，用户读到的是「App 卡了」，而不是「等一下」。
# 这条断言盯的是「禁用态必须同时有视觉表达」，不只是 enabled=false。
check_true("海报未就绪时分享按钮点不动",
           "pressFeedback(enabled = ready" in cel)
check_true("  · 而且禁用态有视觉反馈（不是只把 clickable 关掉）",
           re.search(r"alpha\(if \(ready\) 1f else 0\.\d+f\)", cel) is not None,
           "禁用态没变暗 → 按下去像卡死")

# 数字要定住
check_true("数字取「弹出来那一刻」的值并 remember（不跟着计时器涨）",
           "val stats = remember { streak to seconds }" in cel)

# 烟花：一个时钟驱动全部
#
# ⚠️ 计数时要排掉 import 行，否则「rememberInfiniteTransition 出现 2 次」
#    会让人以为多起了一个动画。检查器自己被这种细节骗过，就没资格去检查别人。
def uses(src: str, name: str) -> int:
    return sum(1 for line in src.splitlines()
               if name in line and not line.strip().startswith("import"))

check_true("烟花只用一个无限循环时钟",
           uses(cel, "rememberInfiniteTransition") == 1,
           f"{uses(cel, 'rememberInfiniteTransition')} 处")
check_true("每个爆点按自己的 start 从时钟里取一段",
           "local / burst.span" in cel or "/ burst.span" in cel)

# ⚠️ 抖动必须确定性：每帧重算的随机数会让每朵花每帧换形状
check_true("烟花抖动是确定性公式，不是随机数",
           "Random" not in cel and "kotlin.random" not in cel,
           "绘图是每帧重算的，随机会让花型每帧变一次")
check_true("  · 用的是取模那种确定性写法",
           re.search(r"\(k \* \d+ \+ seed \* \d+\) % \d+", cel) is not None)

# 颜色必须在组合期取好（DrawScope 里读不到 @Composable 主题属性）
check_true("颜色在组合期取好再传进绘制",
           "val colors = listOf(Amber, Olive, Crimson)" in cel)
i_draw = cel.find("private fun DrawScope.drawBurst(")
check_true("  · drawBurst 是 DrawScope 扩展，不在这里取主题色",
           i_draw > 0 and "Amber" not in cel[i_draw:i_draw + 900],
           "绘制作用域里读不到 Amber 这类 @Composable 属性")

# 爆点分布：只落在上下两段（中间被海报占着）
bursts = re.findall(r"(?<![A-Za-z_])Burst\(([^)]*)\)", cel)
# 正则还会命中 `data class Burst(start: Float, …)` 那条声明，以及 `drawBurst(…)`
# —— 前者用「有没有类型标注」排掉，后者用前面的负向断言排掉
bursts = [b for b in bursts if ":" not in b]
check_true("爆点数量足够铺开（≥ 6 个）", len(bursts) >= 6, str(len(bursts)))
fys = []
for b in bursts:
    parts = [p.strip().rstrip("f") for p in b.split(",")]
    fys.append(float(parts[3]))
bad_fy = [y for y in fys if not (0.06 <= y <= 0.26 or 0.76 <= y <= 0.96)]
check_true("爆点的 fy 只落在 0.06–0.26 与 0.76–0.96 两段"
           "（中间那块被海报盖着，画了等于没画）",
           not bad_fy, str(bad_fy))
check_true("  · 上下两段都真的用到了",
           any(y <= 0.26 for y in fys) and any(y >= 0.76 for y in fys), str(fys))

# 海报尺寸
check_true("海报宽度上限 0.82（别把烟花全挡掉）",
           re.search(r"POSTER_WIDTH_CAP\s*=\s*0\.82f", cel) is not None)
check_true("海报比例 3:4", re.search(r"POSTER_RATIO\s*=\s*3f\s*/\s*4f", cel) is not None)
check_true("尺寸算成 Dp 再转（不在 BoxWithConstraints 里做 Dp 运算）",
           "private fun posterWidth(maxW: Dp, maxH: Dp): Dp" in cel)

# 那句不该出现的占位文案（0.12.0 的教训：渲染失败时会一直挂着像卡死）
check_true("退化成占位时写「已打卡」，不写「生成中…」",
           '"已打卡"' in cel and "生成中" not in cel,
           "「生成中…」万一渲染真失败会一直挂着，看起来像卡死")

# 同一个坑在打卡页也有一份 —— 本轮顺带修掉。两处必须是同一条规矩：
# 忙碌态转圈（它本身就表示「还在动」），非忙碌态给结论，**都不写「生成中…」**。
#
# 这条之所以值得专门盯：打卡页和庆祝页各自独立演进，一处改了另一处没改
# 是**看不到**的（两屏不会同时出现在一张截图里）。而它们的失效形态也一样隐蔽 ——
# 渲染失败时只有那行小字提示「这里本该有东西」。
checkin_src = strip_comments(kt("ui/CheckInScreen.kt"))
check_true("打卡页的海报占位也是「已打卡」，不写「生成中…」",
           '"已打卡"' in checkin_src and "生成中" not in checkin_src)
check_true("  · 忙碌态用转圈（不是一句会永远挂着的文字）",
           "if (busy) {" in checkin_src and "CircularProgressIndicator(" in checkin_src)
check_true("打卡页的分享按钮在禁用态也有视觉反馈（与庆祝页同一条规矩）",
           "alpha(if (ready) 1f else 0.45f)" in checkin_src,
           "只关掉 clickable、不降透明度 → 按下去像卡死")
# ★ 1.2.7：两处「就绪判据」一起换掉了。
#   原来是 `!busy` —— 忙完那一刻按钮变亮，而图可能仍是 null，
#   按下去只换回一句「稍等」。**同一个缺陷在两屏各存了一份**，
#   而这两屏不会同时出现在一张截图里，所以查一处时必须问「另一处有没有」。
check_true("  · 就绪判据是「图在不在」，不是「没在忙」",
           "val ready = bitmap != null" in checkin_src
           and "val ready = !busy" not in checkin_src,
           "打卡页与庆祝页是同一条规矩，两处都要改")


# ---------------------------------------------------------------------------
# ③ 三个页签：滑动 + 底栏插值
# ---------------------------------------------------------------------------

section("③ 页签滑动")

main_raw = kt("MainActivity.kt")
main = strip_comments(main_raw)

check_true("用 HorizontalPager 而不是 when(tab) 瞬移",
           "HorizontalPager(" in main)
check_true("  · 页数从 TABS 派生（唯一一份定义）",
           "rememberPagerState(initialPage = LIBRARY_PAGE, pageCount = { TABS.size })" in main)
check_true("预排当前页两侧（避免划到一半目标页才开始建）",
           "beyondViewportPageCount = 1" in main)

tabs_block = body(main, "private val TABS =")
labels = re.findall(r'"([^"]+)"\s+to\s+Glyph\.(\w+)', tabs_block)
# ⚠️ P1 加了第 4 个页签「生词」，这三条的期望值跟着从 3 改成 4。
#    这是**判据过期**，不是「放宽」—— 它们守的性质一个字没变：
#    页签数量、页签顺序、图标两两不同。
#
#    顺序里「生词」排在「设置」之前是刻意的：设置是工具类入口，习惯上永远在最后；
#    而生词和素材库/打卡是一类（都是内容）。图标唯一这条尤其要守住 ——
#    「生词」和「设置」在底栏里挨着，形状一样的话用户会点错，
#    而且他不会觉得是自己点错（Glyph.Book 的注释里写了这件事）。
check("页签是四个", len(labels), 4)
check("页签顺序：素材库 / 打卡 / 生词 / 设置",
      [l for l, _ in labels], ["素材库", "打卡", "生词", "设置"])
check("页签图标不重复", len({g for _, g in labels}), 4)
check_true("没有残留的 var tab（改成 pager 之后它是死代码）",
           "var tab" not in main)

bottom = body(main, "private fun BottomTabs(")
check_true("底栏拿的是 pagerState（所以能读到拖动进度）",
           "pagerState: PagerState" in bottom)
check_true("位置含拖动的小数偏移",
           "pagerState.currentPage + pagerState.currentPageOffsetFraction" in bottom)
check_true("选中态是插值，不是「第几页点亮第几个」",
           "val tint = lerp(InkFaint, Amber, emphasis)" in main)
check_true("  · 用的是线性取反，不是平方（平方会让中间态太暗）",
           "1f - abs(position - index)" in bottom
           and "(1f - abs(position - index)) * (1f - abs(position - index))" not in bottom)
check_true("  · 并 clamp 到 0..1", "coerceIn(0f, 1f)" in bottom)
check_true("底栏的项也来自 TABS（加页签不会漏）",
           "TABS.forEachIndexed" in bottom)
check_true("点页签走动画而不是瞬移",
           "pagerState.animateScrollToPage(index)" in main)


# ---------------------------------------------------------------------------
# ④ 退回素材库自动定位
# ---------------------------------------------------------------------------

section("④ 退回定位")

check_true("定位信号是自增计数而不是布尔量",
           "var focusPlaying by rememberSaveable { mutableIntStateOf(0) }" in main,
           "布尔量第二次就不再是变化了")
check_true("计数可跨横竖屏（rememberSaveable）", "rememberSaveable" in main)

i_back = main.find("onBack = {")
seg = main[i_back:i_back + 500]
check_true("只有当前页是素材库时才定位",
           "if (pagerState.currentPage == LIBRARY_PAGE) focusPlaying++" in seg)
check_true("  · 从打卡/设置页点迷你条进去的，退回来仍在原来那一页",
           "LIBRARY_PAGE = 0" in main and "currentPage ==" in seg)
check_true("素材库拿到了 focusNonce",
           "focusNonce = focusPlaying," in main)

lib = strip_comments(kt("ui/LibraryScreen.kt"))
check_true("LibraryScreen 有 focusNonce 参数",
           "focusNonce: Int = 0," in lib)
# 1.2.7：滚动状态从「一份」变成「每个筛选一份」。
# 2.0.4：位置改记**内容锚点**（见 verify-filter-scroll.py 的规则表）——
#   每档的状态由锚点**建出来**，切换时不搬运、也不滚动。
check_true("列表状态被显式接出来（不接就没法滚）",
           "val listState = remember(filter) {" in lib and "state = listState," in lib)
check_true("  · 而且是**每个筛选各一份**（切换时既不搬运也不滚动，见 verify-library-ux）",
           "LazyListState(spot.index, spot.offsetPx)" in lib)

eff = body(lib, "LaunchedEffect(focusNonce)")
check_true("0 时不动作（首次进入不该自己滚）", "focusNonce <= 0" in eff)
check_true("只滚「正在播的那一条」", "indexOfFirst { it.id == playingId }" in eff)
check_true("找不到就不滚（可能被筛掉了、或音频已被清理）",
           "if (index < 0) return@LaunchedEffect" in eff)
check_true("已经可见就不动（不打扰正在翻列表的人）",
           "alreadyVisible" in eff and "if (!alreadyVisible)" in eff)
check_true("滚到列表顶部（退回列表的人就是来找这一条的）",
           "animateScrollToItem(target, paddingTopPx - tabsHeightPx)" in eff,
           "2.0.4 起多传一个「让开吸顶筛选签」的偏移：不让开的话目标行会被筛选签盖掉一截，"
           "用户读到的是「退回来还是没看见那一条」。"
           "⚠️ 2.1.6 又把顶部内边距减掉了 —— `scrollOffset = 0` 指的是"
           "「内容区起点」，它比视口顶低一整个 contentPadding.top")

# 偏移量必须与列表头部的 item 数一致 —— 这是最容易悄悄错位的一处
#
# ⚠️ 数法很重要：`item(key = "empty")` 在源码里排在 `items(visible)` **之前**
#   （它挂在 `if (visible.isEmpty())` 分支里），所以「往前数到 items(visible)」
#   会多数一个。分界要取 `if (visible.isEmpty())`。
m = re.search(r"private const val LIBRARY_HEADER_ITEMS = (\d+)", lib)
check_true("LIBRARY_HEADER_ITEMS 是具名常量（不是散落的魔法数字）", m is not None)
if m:
    declared = int(m.group(1))

    # ⚠️ 分界必须**限定在 LazyColumn 内部**。
    #
    # 原来的写法是 `lib.find("if (visible.isEmpty())")` —— 那时这个字符串
    # 在文件里只出现一次（LazyColumn 里那个空状态分支）。1.2.3 给
    # 「切换筛选时恢复滚动位置」加了一段算 itemCount 的代码，同一句
    # 又出现了一次、而且位置更靠前 → 截取窗口直接缩到 0，断言假失败。
    #
    # 这也是这个常量本身的性格：它数的是**列表头部**的 item，
    # 所以分界必须从 `LazyColumn(` 开始找。
    i_lazy = lib.find("LazyColumn(")
    i_empty = lib.find("if (visible.isEmpty())", i_lazy) if i_lazy >= 0 else -1
    check_true("能定位到 LazyColumn", i_lazy > 0, f"i_lazy={i_lazy}")
    check_true(
        "  · 且能在其内部找到空状态分支（分界唯一）",
        i_empty > i_lazy,
        f"i_lazy={i_lazy} i_empty={i_empty} "
        f"全文出现 {lib.count('if (visible.isEmpty())')} 次",
    )
    check_true(
        "  · 该分界在 LazyColumn 内只出现一次",
        i_empty > i_lazy and lib.find("if (visible.isEmpty())", i_empty + 1) < 0,
        "⚠️ 不能写成 lib.count(sub, i_lazy, i_empty + 1) —— "
        "end 只到子串的第一个字符，永远数不出完整匹配（这一版就是这么写错的）",
    )
    head = lib[i_lazy:i_empty] if i_empty > i_lazy else ""

    def header_item_count(src: str) -> int:
        """数 LazyColumn 头部的 item 声明。

        ⚠️ 1.2.2：筛选签那一行从 `item` 换成了 `stickyHeader`（吸顶）。
           它**仍然是 LazyColumn 的一个 item** —— 索引照旧从 0 数，
           所以 LIBRARY_HEADER_ITEMS 还是 3。
           只数 `item(` 会得出「头部只有 2 个」而判失败 ——
           **失败的是这个计数器，不是代码**（这次真的踩到了）。
        """
        return (src.count("item(key = ") + src.count("stickyHeader(key = "))

    def has_header_key(src: str, key: str) -> bool:
        return (f'item(key = "{key}")' in src
                or f'stickyHeader(key = "{key}")' in src)

    actual = header_item_count(head)
    check("LIBRARY_HEADER_ITEMS 与列表头部的 item 数一致", actual, declared)
    # 1.2.0：刊头与更新动作合成了一个 item（masthead），所以从四个 key 变三个。
    # 这条断言之所以要写死 key 名而不是只数个数，是因为「数对了但少了状态块」
    # 这种情况数量上看不出来 —— 比如把 status 删掉、又多加一个装饰 item
    check_true("  · 头部的三个 key 都还在（masthead/status/tabs）",
               all(has_header_key(head, k) for k in ("masthead", "status", "tabs")))
    check_true("  · 筛选签是吸顶的（1.2.2）",
               'stickyHeader(key = "tabs")' in head,
               "内容多起来之后，往下翻几屏就再也点不到筛选签了")
    check_true("  · 「update」已并入 masthead（不再是独立 item）",
               'item(key = "update")' not in lib)
    check("定位时加上这个偏移", "index + LIBRARY_HEADER_ITEMS" in eff, True)

# 播放页是覆盖层而不是第四个页签 —— 「退回来还在原页签」之所以天然成立，就靠这一点
#
# ⚠️ 1.2.0 起三个覆盖层都包在 AnimatedVisibility 里（加转场），
#    所以不能再按 `if (playingId.isNotEmpty())` 这种字面量定位。
#    改用各自的 Screen 调用点 —— 那才是「这一层在哪」的真实依据，
#    而且它不会随着「用 if 还是用 AnimatedVisibility」这类写法变化而失效。
i_pager = main.find("HorizontalPager(")
i_player = main.find("PlayerScreen(")
i_diag = main.find("DiagScreen(")
i_celebrate = main.find("CheckInCelebrationDialog(")
check_true("播放页在 pager **之外**（否则它就成了第四个页签）", i_player > i_pager)
check_true("  · pager 的 pageContent 里没有 PlayerScreen",
           "PlayerScreen(" not in main[i_pager:i_player])
check_true("分层的顺序：主界面 → 播放页 → 诊断页 → 庆祝页（庆祝页在最上层）",
           0 < i_pager < i_player < i_diag < i_celebrate,
           "听满 40 分钟那一刻用户很可能正看着播放页，庆祝页必须盖在它上面")

# 1.2.0 新增：三个覆盖层都要有转场。
#
# 这三处在 1.1.0 之前全是 `if (...) Screen(...)` 硬切 —— 下一帧整屏被换掉，
# 用户分不清「换了一页」和「这一页内容被替换了」。
for name in ("PlayerScreen", "DiagScreen", "CheckInCelebrationDialog"):
    i = main.find(name + "(")
    # 往前找最近的 AnimatedVisibility，中间不应该再夹着另一个 Screen 调用。
    #
    # ⚠️ 窗口长度是**契约**：1.2.10 给播放页那句加了 enter/exit 的注释，
    #    把那一段撑长了 400 字符以上，原来的窗口就够不着 AnimatedVisibility 了
    #    （表现为「播放页没有进出转场」这条假失败）。给到 900。
    seg = main[max(0, i - 900):i]
    check_true(f"{name} 包在 AnimatedVisibility 里（有进出转场）",
               "AnimatedVisibility(" in seg)
    check_true(f"  · {name} 用的是统一的 Motion.overlayEnter/Exit",
               "Motion.overlayEnter" in seg and "Motion.overlayExit" in seg,
               "各写各的 tween 会让三个覆盖层的节奏对不上")

# 退出动画期间内容不能变空白
check_true("播放页用 lastPlayingId 而不是 playingId（退场那 220ms 里不能空白）",
           "episodeId = lastPlayingId" in main,
           "playingId 在点返回那一刻就空了，而覆盖层还要往下沉 220ms")
check_true("  · lastPlayingId 只在打开时更新",
           "if (playingId.isNotEmpty() && playingId != lastPlayingId)" in main)


# ---------------------------------------------------------------------------
# ⑤ 反向对照：坏实现必须被判失败
# ---------------------------------------------------------------------------
#
# 证明上面那些 ✓ 有区分力。若坏样本也能通过，说明断言写得没抓住要点。

section("⑤ 反向对照（坏实现必须被判失败）")

def bad(src: str) -> bool:
    """返回 True 表示「被拦下了」"""
    return not all((
        uses(src, "rememberInfiniteTransition") == 1,
        "1f - abs(position - index)" in src,
        "_celebrate.value = true" in src,
    ))


# 坏实现 A：底栏按整数页上色（就是「不跟手」的原始写法）
A = ("val position = pagerState.currentPage\n"
     "val emphasis = if (index == position) 1f else 0f\n"
     "rememberInfiniteTransition(label = \"x\")")
check_true("坏实现 A（底栏不插值）被拦下", bad(A))

# 坏实现 B：每朵烟花各起一个动画
B = ("BURSTS.forEach { rememberInfiniteTransition(label = \"b\") }\n"
     "val emphasis = 1f - abs(position - index)")
check_true("坏实现 B（每朵一个动画）被拦下", bad(B))

# 坏实现 C：拿随机数做抖动
C = ("val jitter = Random.nextFloat()\n"
     "val emphasis = 1f - abs(position - index)\n"
     "rememberInfiniteTransition(label = \"x\")")
check_true("坏实现 C（随机抖动）被拦下 —— 用「每帧重算」那条判据",
           "Random" in C and "Random" not in cel)

# 海报占位 / 分享按钮禁用态这一组判据，单独一个谓词。
# 它们和上面那组无关（一个是动画与底栏，一个是「东西没准备好时怎么表现」），
# 混进同一个 all(...) 会让任何一条坏样本都连带触发，失去定位能力。
def bad_poster(src: str) -> bool:
    """返回 True 表示「被拦下了」"""
    return not all((
        '"已打卡"' in src,                     # 非忙碌态给结论
        "生成中" not in src,                   # 不许写会永远挂着的占位
        "if (busy) {" in src,                  # 忙碌态有分支
        "CircularProgressIndicator(" in src,   # 忙碌态是转圈
        # 就绪判据看的是**图在不在**，不是「没在忙」：
        # 忙完那一刻按钮会变亮，而图可能仍是 null —— 按下去只换回一句「稍等」
        ("val ready = poster != null" in src or "val ready = bitmap != null" in src),
        "val ready = !busy" not in src,
        "alpha(if (ready) 1f else 0.45f)" in src,  # 且禁用态降透明度
    ))


# 坏实现 E：占位写成「生成中…」（就是这轮修掉的那个写法）
E = cel.replace('"已打卡"', '"海报生成中…"')
check_true("坏实现 E（占位写「生成中…」）被拦下", bad_poster(E))
check_true("  · E 与真源码只差那一处（否则是在证明别的东西）",
           E != cel and E.count("生成中") == 1 and cel.count("生成中") == 0)

# 坏实现 F：分享按钮只关掉 clickable，不降透明度
F = cel.replace(".alpha(if (ready) 1f else 0.45f)", "")
check_true("坏实现 F（禁用态无视觉反馈）被拦下", bad_poster(F))
check_true("  · F 仍然「能点不动」（所以拦下的确实是视觉那条）",
           # 1.2.8：`.clickable` → `.pressFeedback`
           "pressFeedback(enabled = ready" in F)

# 交叉：真正发出去的源码必须通过同一组判据
# （庆祝页在 cel 里，打卡页在 checkin_src 里 —— 两处都要过）
check_true("庆祝页的真源码通过这组判据", not bad_poster(cel))
check_true("打卡页的真源码通过这组判据", not bad_poster(checkin_src))

# 交叉：真正发出去的源码必须通过同一组判据
D = cel + main + lib + store
check_true("发出去的源码通过这组判据（反向对照没有把所有人都拦下）", not bad(D))


# ---------------------------------------------------------------------------

section("汇总")
print(f"通过 {PASS} 项，失败 {FAIL} 项")
if FAIL:
    print("\n❌ 有断言未通过：")
    for f in FAILURES:
        print("  - " + f)
    sys.exit(1)
print("✅ 全部通过")
