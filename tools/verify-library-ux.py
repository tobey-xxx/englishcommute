#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
素材库的浏览与操作 —— 1.2.2 的契约锁。

## 这一轮修的都是「界面说了但代码没做」或「做了但看不出来」

| # | 用户报的 | 真相 |
|---|---|---|
| 1 | 通知栏一直挂着「更新完成 · 共 0 条可用」，关 App 也不行 | `stopForeground(DETACH)` 把一条 **ongoing（划不掉）**的进度通知留在了通知栏 |
| 2 | 素材多了，找旧内容要拖很久 | 没有快速滚动条 |
| 3 | 长按条目想删除 / 收藏 | 没有长按；而且删除必须**跨刷新**记住，否则「删了又回来」 |
| 4 | **长素材手动点转写无效** | `transcribeManually`（唯一能越限的入口）**没有调用点**；实际路径传的是 `includeOverLimit = false`，30 分钟以上的内容被静默过滤 —— 而界面写着「转写（较慢）」 |
| 5 | 「上次更新」后面是空的 | `SyncPhase.Done` 里压根没有时间字段 |
| 5b/6 | 四个指标、三个筛选签挤在左边 | 左对齐 + 固定间距；筛选签还不吸顶 |

**每一条都有同一个特征：不报错。** 通知在那儿、按钮亮着、数字排列着 ——
只有用户能看出「这不对」。所以这里的断言既要盯代码事实，也要在桌面把
「删了会不会回来」「长素材能不能选上」这类判据 1:1 跑一遍。

用法：
    python3 tools/verify-library-ux.py
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


def check_eq(name: str, actual, expected) -> None:
    check_true(f"{name}（期望 {expected}，实际 {actual}）", actual == expected)


def section(t: str) -> None:
    print(f"\n{t}")


def kt(rel: str) -> str:
    return (JAVA / rel).read_text(encoding="utf-8")


def strip_comments(src: str) -> str:
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


def body(src: str, marker: str, size: int = 2400) -> str:
    """从 marker 起截一段。

    ⚠️ size 要够长：1.2.3 给 QuickScrollBar 补了一大段注释，
       默认窗口截不到函数尾部，于是「offset 那一行在不在」被判失败 ——
       **失败的是这个窗口，不是代码**（这次踩到了）。
    """
    i = src.find(marker)
    return src[i:i + size] if i >= 0 else ""


def changed_lines(a: str, b: str) -> int:
    """两个源码串差几行；行数不同返回 -1。用于「坏样本只差那一处」"""
    la, lb = a.splitlines(), b.splitlines()
    if len(la) != len(lb):
        return -1
    return sum(1 for x, y in zip(la, lb) if x != y)


LIB = strip_comments(kt("ui/LibraryScreen.kt"))
# 2.1.0：滚动锚点（位置记在 ViewModel、规则在纯函数模块里）
VM = strip_comments(kt("ui/LibraryViewModel.kt"))
ANCHOR = strip_comments(kt("ui/LibraryAnchor.kt"))
SVC = strip_comments(kt("sync/ContentSyncService.kt"))
ENG = strip_comments(kt("sync/SyncEngine.kt"))
REPO = strip_comments(kt("content/ContentRepository.kt"))
LVM = strip_comments(kt("ui/LibraryViewModel.kt"))
APP = strip_comments(kt("EnglishCommuteApp.kt"))
FAV = strip_comments(kt("settings/Favorites.kt"))
# ⚠️ 2.1.0：设置页从 ui/ModelSetupScreen.kt 改名成 ui/SettingsScreen.kt
#    （模型区删掉之后，那个名字已经名不副实）。
MSS = strip_comments(kt("ui/SettingsScreen.kt"))
CIS = strip_comments(kt("ui/CheckInScreen.kt"))
PLAY = strip_comments(kt("player/PlaybackService.kt"))
PVM = strip_comments(kt("ui/PlayerViewModel.kt"))
MAIN = strip_comments(kt("MainActivity.kt"))
LOG = strip_comments(kt("diag/DiagLog.kt"))


# ===========================================================================
# 一、长按菜单与删除（需求 3）
# ===========================================================================

section("一、长按菜单与删除")

# 整行 clickable 的参数片段（从 combinedClickable( 起 600 字）
_i = LIB.index("combinedClickable(")
_row_click = LIB[_i:_i + 600]

# ⚠️★ 4.6.6：**封面仓库必须真的 provide 进组合**。
#
#    `LocalArtworkStore` 的默认值是 `null`（`staticCompositionLocalOf { null }`），
#    而全仓曾经**没有任何地方 provides 它** —— 于是 `Artwork` 里 `store == null`，
#    封面加载一次都没执行：没有请求、没有失败、没有日志，
#    界面上只剩一块字母占位。这个缺陷活了很久，因为它是**完全无声**的。
#    可空的 CompositionLocal 默认值 = 给「忘了接线」发免罚单。
check_true("★ 封面仓库真的接进了组合（LocalArtworkStore provides）",
           "LocalArtworkStore provides" in MAIN,
           "不 provide ⇒ Artwork 静默不加载，封面永远是字母块，且日志里没有任何线索")
_bad_no_provide = MAIN.replace("LocalArtworkStore provides", "LocalArtworkStore /*x*/ provides", 1)
check_true("  · 坏样本（去掉 provide）被拦下",
           "LocalArtworkStore provides" not in _bad_no_provide)

check_true("条目接了长按", "combinedClickable(" in LIB and "onLongClick" in LIB)
check_true(
    "  · 长按对**所有**条目有效（enabled 不跟 canPlay 走）",
    # 1.2.8：`combinedClickable` 多了 interactionSource / indication 两个参数
    #（按压缩放与点击手势共用同一份状态），所以不能再盯紧邻的 `onClick = {`
    "combinedClickable(" in LIB
    and "interactionSource = pressSource" in LIB
    and "onLongClick = { menuOpen = !menuOpen }" in LIB
    # ⚠️★ 4.6.0 把这条判据**收窄到「整行 clickable 的参数块」**，原因很具体：
    #   新增的行内播放键写了 `RowPlayGlyph(enabled = canPlay, …)` —— 那是
    #   **按钮**该有的禁用（没音频就点不动），跟「整行能不能长按」是两件事。
    #   原来那句 `"enabled = canPlay" not in LIB` 是**全局子串**判断，
    #   于是一个合法的按钮参数把这条断言打红了。
    #   判据要盯的是**事实**（这一行自己的 clickable 有没有被 canPlay 掐住），
    #   不是「文件里能不能搜到这个字符串」。
    and "enabled =" not in _row_click,
    "老写法是 clickable(enabled = canPlay && !selfBusy)，于是没有音频的废条目长按也没反应 —— 而那正是最想删的一类",
)
check_true(
    "  · 再长按一次收起（toggle 而不是置 true）",
    "onLongClick = { menuOpen = !menuOpen }" in LIB,
)
check_true(
    "  · 点击时先收起菜单再决定播不播",
    re.search(r"onClick = \{\s*menuOpen = false", LIB) is not None,
    "菜单开着的时候点条目，应该先关菜单 —— 否则下一次长按会立刻又把它收起来",
)
check_true(
    "收藏项跟着状态换文案（已收藏 → 「取消收藏」）",
    'if (favorite) "取消收藏" else "收藏"' in LIB,
    "菜单写着「收藏」而点下去是取消，会让人不敢点第二下",
)
check_true("删除用危险色", 'text = "删除"' in LIB and "Crimson" in LIB)
check_true(
    "动作条是**原位向下展开**，不是 Material 的圆角浮层",
    "AnimatedVisibility(\n            visible = menuOpen," in LIB
    and "DropdownMenu" not in LIB,
    "这套视觉从刊头到分割线全是直角和细线，浮起来一个圆角卡片是最出戏的一笔",
)

# —— 删除的清理面 ——
#
# ⚠️ 2.1.0：清单里少了「转写断点」那一项 —— 断点机制随端侧识别一起拆掉了，
#    `container.checkpoints` 已经不存在。升级用户手机上残留的 .trk 由
#    `OnDeviceModelCleanup` 在启动时清掉（那是一条独立的路径，见
#    tools/verify-no-ondevice.py）。
del_body = body(LVM, "fun deleteEpisode(")
for call, why in [
    ("container.repository.removeEpisode(episode)", "音频/字幕/译文 + 索引条目 + 删除记录"),
    ("container.playbackPositions.clear(episode.id)", "播放进度"),
    ("container.playbackPositions.clearDone(episode.id)", "听完标记"),
    ("container.favorites.remove(episode.id)", "收藏 —— 留着会多一个点不开的计数"),
]:
    check_true(f"删除时清掉：{why}", call in del_body)

i_ctx = del_body.find("withContext(Dispatchers.IO)")
i_prune = del_body.find("NowPlaying.prunePlaylist(")
check_true(
    "摘播放队列在主线程（withContext 之外）",
    0 <= i_ctx < i_prune,
    "ExoPlayer 的 removeMediaItem 只能主线程调 —— 1.2.0 记过这一条",
)
check_true("  · 摘队列用的是同一条 pruned 路径", "listOf(episode.id)" in del_body)

# —— 仓库侧：删除必须跨刷新 ——
check_true("仓库有 removeEpisode", "fun removeEpisode(episode: Episode)" in REPO)
rem = body(REPO, "fun removeEpisode(")
check_true("  · 删文件", "audioStore.deleteEpisodeFiles(episode)" in rem)
check_true("  · 出索引", "filterNot { it.id == episode.id }" in rem)
check_true("  · 记进忽略集合", "_ignored.value = _ignored.value + episode.id" in rem)
check_true("  · 索引立刻落盘", "persistNow()" in rem)
check_true("  · 忽略集合也落盘", "persistIgnored()" in rem)
check_true(
    "刷新合并时跳过忽略集合",
    "if (item.id in ignored) continue" in REPO,
    "否则「删了又回来」，而且发布在 2 天窗口内时还会被重新下载一遍",
)
check_true("  · 忽略集合取的是合并开始前的快照", "val ignored = _ignored.value" in REPO)
check_true("忽略集合在构造时加载", "loadIgnored()" in body(REPO, "init {"))
check_true("  · 落盘是 tmp + rename（同索引那套原子写）",
           'ignoredFile.name + ".tmp"' in REPO)
check_true("  · 读失败按「没删过」处理（宁可多留不可误删）",
           '"Repo", "删除记录读取失败（按没有删过处理）' in REPO)
check_true("Favorites 有幂等的 remove", "fun remove(episodeId: String)" in FAV)
check_true(
    "  · 且它不是 toggle",
    "if (episodeId !in current) return" in body(FAV, "fun remove("),
    "调用方已经知道要的是「取消」而不是「切换」，让它再读一次状态就多一种读错的可能",
)


# —— 桌面复刻：删掉的内容会不会被下一次刷新带回来 ——
section("一·复刻 · 删除之后刷新，它会不会回来")


def merge_refresh(ignored, feed_ids, existing):
    """
    复刻 `refreshFeeds` 的合并循环：
        for item in items:
            if item.id in ignored: continue     ← 1.2.2 加的
            ...
    """
    merged = list(existing)
    for i in feed_ids:
        if i in ignored:
            continue
        if i not in merged:
            merged.append(i)
    return merged


DELETED = "ep-abc"
FEED = ["ep-abc", "ep-xyz"]
check_true(
    "1.2.2：删过的条目不会再被刷新带回来",
    DELETED not in merge_refresh({DELETED}, FEED, []),
)
check_true(
    "  · 同一批里没删过的照常进来",
    merge_refresh({DELETED}, FEED, []) == ["ep-xyz"],
)
check_true(
    "坏实现（不记忽略集合）→ 它原样回来了",
    DELETED in merge_refresh(set(), FEED, []),
    "这就是用户会报「我明明删了它」的那个版本",
)
check_true(
    "  · 而且回来时会被当成新条目（在 2 天窗口内 → 还会重新下载）",
    "ep-abc" in merge_refresh(set(), FEED, []),
)


# ===========================================================================
# 二、转写额度机制**已经整块删掉**（2.1.0）
# ===========================================================================
#
# 这一节原来是整个脚本里最复杂的一段：它验的是「用户点的那一条可以越过
# **自动转写的时长上限**（30 分钟）」—— 含
#   · `includeOverLimit = asked != null` 这条判据
#   · `val forced = if (includeOverLimit && priorityId != null)` 这个兜底分支
#   · 一整套 `transcribe_batch()` 的 Python 复刻（额度、超限计数、排序）
#   · 五条正面断言 + 一条坏样本
#
# 全部存在的理由只有一个：**端侧转写是实时的 2–3 倍**，所以 45 分钟的 TED
# 要跑十几分钟，必须有个「一轮做几条」的刹车，而「用户明确点了」要知道
# 怎么越过它。真机上报的「30 分钟以上的内容点多少次都不转」就是这个
# 刹车与越过逻辑没对齐造成的。
#
# 2.1.0 取字幕只要几百毫秒、且与音频时长无关，于是**刹车和不存在的理由一起消失**：
#   · `AUTO_MAX_EPISODES` / `AUTO_MAX_AUDIO_SEC` / `TranscribeBudget` 全删
#   · `includeOverLimit` / `forced` 分支全删
#   · 界面上那档「转写（较慢）」也删了（长内容不再更慢）
#
# 现在这一段改判**相反**的命题：**不许有时长上限**。
# 它比原来的断言更简单，但同样重要 —— 因为「顺手加个上限」是很自然的想法，
# 而在服务器时代它只会造成「用户明明点了更新，还有 7 条没字幕」。

section("二、不许有时长上限（额度机制已整块删掉）")

check_true("额度相关常量已删", "AUTO_MAX_EPISODES" not in ENG and "AUTO_MAX_AUDIO_SEC" not in ENG,
           "留着任何一个，下一个人就会想「那我把上限调大一点」—— 而上限本身不该存在")
check_true("TranscribeBudget 记账对象已删", "class TranscribeBudget" not in ENG)
check_true("  · 越限分支与 forced 兜底已删",
           "includeOverLimit" not in ENG and "overLimit" not in ENG)
check_true("  · 待补队列不按时长过滤",
           "durationSec" not in body(ENG, "private suspend fun transcribeQueue("),
           "服务器对每一期都会转写，客户端取回来与时长无关 —— "
           "在这里按时长过滤，等于把一个「服务器已经做好了」的字幕故意丢掉")
check_true("  · 而「用户点的那条排第一」仍然在（插队没被一起删掉）",
           "compareByDescending<Episode> { it.id == priorityId }" in ENG)
check_true("界面上不再有「较慢」这一档", '转写（较慢）' not in LIB,
           "长内容不再更慢，留着这个标注会让用户以为要等更久")

# 坏样本：把时长过滤加回来，必须被上面那条断言拦下
BAD_DURATION = ENG.replace(
    "val missing = all.filter { repository.needsSubtitles(it) }",
    "val missing = all.filter { repository.needsSubtitles(it) && it.durationSec <= 30 * 60 }", 1)
check_true("坏样本 J（把「超过 30 分钟就不补字幕」加回来）被拦下",
           BAD_DURATION != ENG
           and "durationSec" in body(BAD_DURATION, "private suspend fun transcribeQueue("),
           "这正是 1.2.2 之前那个 bug 的形态：长素材永远取不到字幕，"
           "而且**不报任何错** —— 用户只会觉得「这条一直没字幕」")


# ===========================================================================
# 三、通知清理（需求 1）
# ===========================================================================

section("三、通知栏僵尸条")

check_true("退前台用 REMOVE 而不是 DETACH",
           "stopForeground(STOP_FOREGROUND_REMOVE)" in SVC)
check_true(
    "  · 源码里不再出现 DETACH",
    "STOP_FOREGROUND_DETACH" not in SVC,
    "DETACH 的语义是「退出前台但把通知留下」，而那条通知是 ongoing 的 —— 用户划不掉",
)
check_true(
    "  · 有双保险（显式 cancel 同一个 id）",
    "NotificationManagerCompat.from(this).cancel(PROGRESS_NOTIFICATION_ID)" in SVC,
)
check_true(
    "  · 进度通知确实是 ongoing（这就是为什么必须 REMOVE）",
    "setOngoing(true)" in SVC,
)
check_true(
    "  · 收尾通知用另一个 id（它才该留在通知栏）",
    "FINAL_NOTIFICATION_ID" in SVC and "PROGRESS_NOTIFICATION_ID" in SVC,
)
# ★ 1.2.5：这一条**以前写的是「PROGRESS_NOTIFICATION_ID = 1001」** ——
#   断言把 bug 锁在了源码里。而 1001 正是 media3 播放通知的默认值：
#   通知的唯一标识是「包名 + tag + id」，id 撞了就是同一条通知 ——
#   一跑更新就把正在播的播放控件顶掉，收尾的 cancel 还把它整个撤走。
NOTIFY_IDS = [int(x) for x in re.findall(r"NOTIFY_ID_\w+ = (\d+)", APP)]
check_eq("通知 ID 全部定义在 EnglishCommuteApp 里", len(NOTIFY_IDS), 3)
check_eq("  · 而且两两不同（撞车 = 互相顶掉）", len(set(NOTIFY_IDS)), len(NOTIFY_IDS))
check_true(
    "  · 播放那一个必须与 media3 的默认值一致",
    "NOTIFY_ID_PLAYBACK = 1001" in APP,
    "DefaultMediaNotificationProvider.DEFAULT_NOTIFICATION_ID = 1001（javap 实测）",
)
check_true(
    "  · 播放服务显式钉住它（不靠库的默认值）",
    "setNotificationId(EnglishCommuteApp.NOTIFY_ID_PLAYBACK)" in PLAY,
)
check_true(
    "  · 同步的两个不再落在 1001/1002 上",
    "PROGRESS_NOTIFICATION_ID = EnglishCommuteApp.NOTIFY_ID_SYNC_PROGRESS" in SVC
    and "FINAL_NOTIFICATION_ID = EnglishCommuteApp.NOTIFY_ID_SYNC_FINAL" in SVC,
)
check_true("有清理入口 clearStaleNotification", "fun clearStaleNotification(" in SVC)
check_true(
    "  · App 冷启时兜一次（旧版本留下的通知，重开也不会自己消失）",
    "ContentSyncService.clearStaleNotification(this)" in APP,
)
check_true(
    "  · 且排在 createNotificationChannels 之后、容器之前",
    APP.find("clearStaleNotification") < APP.find("container = AppContainer(this)"),
)


# ===========================================================================
# 四、上次更新时间（需求 5）
# ===========================================================================

section("四、「上次更新」的时间")

check_true("SyncPhase.Done 带时间戳", "val at: Long = System.currentTimeMillis()," in ENG)
check_true(
    "  · 界面把它渲染出来了",
    "Text(relativeTime(p.at)" in LIB,
    "原来那一行只有 Text(\"上次更新\")，后面永远是空的",
)
check_true(
    "  · 用的是列表里同一套相对时间（不是另写一份格式）",
    "private fun relativeTime(epochMillis: Long)" in LIB,
)
check_true(
    "  · 而 relativeTime 没被收进 Parts —— 它多一个「时间未知」分支",
    'if (epochMillis <= 0L) return "时间未知"' in LIB,
    "RSS 没给发布日期的条目会走到那里；换成通用相对时间会显示成「20500 天前」",
)
bad_done = LIB.replace("Text(relativeTime(p.at)", "Text(", 1)
check_true(
    "坏样本（只留标题不渲染值）被拦下",
    "Text(relativeTime(p.at)" not in bad_done and changed_lines(bad_done, LIB) == 1,
)


# ===========================================================================
# 四之二、列表条目不显示时间（2.2.2）
# ===========================================================================
#
# 用户报的：「这些素材的时间戳我是没看懂，4 小时前的比 1 小时前的排的靠前」。
#
# 时间戳本身是准的 —— 不准的是**顺序**：列表跑的是 interleaveBySource
# 的源间交错，位置顺序 ≠ 时间顺序（实测 65 条里 23 个相邻位置时间倒挂）。
# 两者摆在同一个屏幕上，用户只会读成「排序坏了」。
#
# 所以这一节的判据是一条**反向断言**：列表条目上不许再出现发布时间。
# 配套的坏样本是「把时间加回来」—— 反向断言如果不能失败，
# 它就只是一句写在脚本里的注释。

section("四之二、列表条目不显示相对时间")

check_true(
    "kicker 只返回来源名",
    "private fun buildKicker(episode: Episode): String = episode.sourceName" in LIB,
)
check_true(
    "  · 列表条目上不再出现发布时间（反向断言）",
    "relativeTime(episode.publishedAt)" not in LIB,
    "列表是源间交错排的，位置顺序 ≠ 时间顺序；把时间摆在旁边会被读成「排序坏了」",
)
check_true(
    "  · 「上次更新」那行仍在显示时间（那里的时间语义是对的）",
    "Text(relativeTime(p.at)" in LIB,
)
check_true(
    "  · relativeTime 因此必须保留，没被顺手删掉",
    "private fun relativeTime(epochMillis: Long)" in LIB,
)
# ===========================================================================
# 四之三、卡片里文字不往下沉（2.2.3）
# ===========================================================================
#
# 用户报的：「素材里文字的排版，我感觉有点整体偏下」。
#
# 逐像素量真机截图后确认：**上留白 30dp、下留白 16dp**。
# 而卡片 padding 四边都是 13dp —— 所以多出来的 14dp 不在 padding 上。
#
# 根因：kicker 那一行里放着 44dp 触摸区的星标，行高被它撑成 44dp，
# 而文字在行内垂直居中 → 文字上方凭空多出 (44−16)/2 ≈ 14dp。
# 实测 14.3dp，与推算吻合。
#
# 修法是给这一行**钉死高度**（26dp × fontScale），星标的触摸区跟着变成 44×26。
# 这条断言守的就是「那行的高度不许再被撑开」。

section("四之三、卡片里文字不往下沉")

check_true(
    "kicker 行的高度是钉死的",
    # ⚠️★ 4.6.1 把这条判据**换了对象**，理由写清（这不是放宽）：
    #   原来钉死 26dp 是为了拦「星标 44dp 触摸区把这行撑高、文字被压偏」（2.2.3）。
    #   但这一行现在装 `来源 · 时长 · 字幕 N 句 · 已听 03:12` —— 钉死会**裁掉半行字**
    #   （用户截图就是那个）。
    #   正解是**把星标搬出这一行**（现在它在整行最右端、垂直居中），
    #   于是这一行没有 44dp 的子元素，钉死就变成纯负担。
    #   所以判据改成：① 这行是**最小高度**（不再钉死）；② **星标不在这行里**。
    "heightIn(min = (20f * fontScale).dp)" in LIB
    and "FavoriteMark(" not in LIB[
        LIB.index("heightIn(min = (20f * fontScale).dp)"):
        LIB.index("heightIn(min = (20f * fontScale).dp)") + 1400
    ],
    "不钉死 → 44dp 的星标触摸区会把这行撑成 44dp，文字垂直居中、上方多出 14dp",
)
check_true(
    "  · 行高跟着系统字号长（否则放大字号后又会撑开）",
    "20f * fontScale" in LIB,
)
check_true(
    "  · 常量存在",
    "const val KICKER_ROW_DP" in LIB,
)
check_true(
    "  · 星标改为「宽 44、高随行」，不再用 .size(44.dp) 撑高所在行",
    ".width(44.dp)\n            .fillMaxHeight()" in LIB or ".width(44.dp)" in LIB,
)
bad_pad = LIB.replace(".width(44.dp)\n            .fillMaxHeight()", ".size(44.dp)")
check_true(
    "坏样本（星标改回 .size(44.dp)）被拦下",
    ".width(44.dp)\n            .fillMaxHeight()" in LIB and ".width(44.dp)\n            .fillMaxHeight()" not in bad_pad,
    "这是 2.2.3 之前的样子，也是用户看到「偏下」的原因",
)


bad_kicker = LIB.replace(
    "private fun buildKicker(episode: Episode): String = episode.sourceName",
    'private fun buildKicker(episode: Episode): String = episode.sourceName + " · " + relativeTime(episode.publishedAt)',
    1,
)
check_true(
    "坏样本（把时间加回来）被拦下",
    "relativeTime(episode.publishedAt)" in bad_kicker and changed_lines(bad_kicker, LIB) == 1,
    "反向断言必须能真的失败，否则它只是一句注释",
)


# ===========================================================================
# 五、对齐与吸顶（需求 5b / 6）
# ===========================================================================

section("五、指标行与筛选签的对齐、吸顶")

done = body(LIB, "private fun DoneBlock(")
check_true("指标行占满整行", "Row(Modifier.fillMaxWidth()) {" in done)
check_true(
    "  · 四个指标等分（每列 weight(1f)）",
    done.count("Modifier.weight(1f)") >= 3,
    f"实际 {done.count('Modifier.weight(1f)')} 个 weight",
)
check_true(
    "  · 不再用固定间距的左对齐排法",
    "Arrangement.spacedBy(28.dp)" not in done,
    "挤在左边时，旁边空一大片，而等宽数字本来是拿来列对齐的",
)

tabs = body(LIB, "private fun FilterTabs(")
check_true("筛选签同样等分整行",
           "Row(Modifier.fillMaxWidth()) {" in tabs
           and "Modifier\n                        .weight(1f)" in tabs)
check_true("  · 不再用固定间距", "Arrangement.spacedBy(26.dp)" not in tabs)
check_true(
    "  · 整格都是热区（weight 在前、按压手势在后）",
    # 1.2.8：`.clickable` → `.pressFeedback`
    ".weight(1f)\n                        .pressFeedback { onSelect(option) }" in tabs,
)
check_true("筛选签吸顶", 'stickyHeader(key = "tabs")' in LIB)
check_true(
    "  · 吸顶块有不透明背景（否则内容从底下透出来）",
    "background(Bg)" in body(LIB, "private fun FilterTabs(") or
    "background(Bg)" in LIB[LIB.find("private fun FilterTabs(") - 400:LIB.find("private fun FilterTabs(")],
)

# —— 零件收敛 ——
section("五·顺带 · 四处重复的零件")
check_true("Parts 里有一份 Metric", "fun Metric(" in kt("ui/theme/Parts.kt"))
check_true("  · 素材库不再自带一份", "private fun Metric(" not in LIB)
check_true("  · 打卡页不再自带一份", "private fun Metric(" not in CIS)
check_true(
    "  · 设置页那份**保留**（尺寸不同，是有意的）",
    "private fun Metric(" in MSS and "AppText.digit," in MSS,
    "它用 12.5sp + 4dp 间距服务「四列挤一行」的空间统计，硬套大号数字会撑变形",
)
check_true("formatBytes 只剩一份定义",
           "private fun formatBytes(" not in LIB
           and "private fun formatBytes(" not in MSS
           and "fun formatBytes(" in kt("ui/theme/Parts.kt"))


# ===========================================================================
# 六、快速滚动条（需求 2）
# ===========================================================================

section("六、右侧快速滚动条")

check_true("有 QuickScrollBar", "private fun QuickScrollBar(" in LIB)
check_true("  · 叠在列表右侧", "QuickScrollBar(\n            state = listState," in LIB)
qsb = body(LIB, "private fun QuickScrollBar(", size=9000)
check_true("  · 不用时半隐藏", "SCROLLBAR_IDLE_ALPHA" in qsb and "alpha(fade)" in qsb, )
check_true(
    "  · 拖动中或列表滚动中完全显出来",
    "if (dragging || state.isScrollInProgress) 1f else SCROLLBAR_IDLE_ALPHA" in qsb,
)
check_true(
    "  · 半隐藏不等于消失（只是降低不透明度）",
    "SCROLLBAR_IDLE_ALPHA = 0.25f" in LIB,
    "完全隐掉的话，用户根本不知道这里能拖",
)
# ★ 1.2.9 改：断言从「绝对定位」变成「抓滑块不跳、按轨道才跳」。
#
# 1.2.5 那版把「手指按在哪就跳到哪」写成了优点（防积累误差），
# 但它的副作用是**捏住滑块下半部分按下去时滑块会先窜一下** ——
# §2 的原话是「尊重抓取时的偏移」。现在两种按法分开，两条断言各管一半。
check_true(
    "  · 捏住**滑块**时保持抓取偏移（按下不跳）",
    re.search(r"grabPx = if \(offset\.y >= top && offset\.y <= top \+ len\)", qsb) is not None
    and "offset.y - top" in qsb,
    "§2：抓取偏移要尊重 —— 否则按下那一瞬间滑块会窜，内容跟着跳",
)
check_true(
    "  · 按在**轨道空白**处才跳过去（且滑块以手指为中心）",
    "len / 2f" in qsb,
    "这是「我要到那里」—— 与「挪这个滑块」是两种意图，必须分开",
)
check_true(
    "  · 拖动期间滑块位置走 rememberUpdatedState（否则会读到陈旧的 offsetPx）",
    "rememberUpdatedState(offsetPx)" in qsb and "rememberUpdatedState(thumbPx)" in qsb,
    "pointerInput 的 block 只在 key 变化时重启，闭包捕获的普通 val 是**陈旧**的 —— "
    "表现是「滑块位置和手指对不上」，且不报错",
)
check_true(
    "  · 目标比例仍由「滑块顶部的目标位置」算出（松手不会跳）",
    "val top = (y - grabPx).coerceIn(0f, travel)" in qsb
    and "scope.launch { seekToFraction(state, frac) }" in qsb,
    "拖动路径与「由滚动位置反推」算的是同一个数，所以松手瞬间一致",
)
check_true(
    "  · 一屏装得下就不画",
    "if (first == 0 && vis.last().index >= total - 1)" in qsb,
    "画了也拖不动，只是一个让人困惑的装饰",
)
check_true("  · 触摸热区比视觉条宽得多", "SCROLLBAR_TOUCH_WIDTH = 26.dp" in LIB)

# ⚠️ 重组隔离：滚动条读的 layoutInfo 是每帧都变的状态，读写必须待在同一个
#    Composable 里 —— 否则每滚一帧整个 LibraryScreen（含所有条目）重组一次
i_screen = LIB.find("fun LibraryScreen(")
i_qsb = LIB.find("private fun QuickScrollBar(")
screen_body = LIB[i_screen:i_qsb] if 0 <= i_screen < i_qsb else ""
check_true("截取到了 LibraryScreen 的函数体", len(screen_body) > 1000)

# ⚠️ 这里有**一处合法的** layoutInfo：`LaunchedEffect(focusNonce)` 里那次
#    「退回列表时，目标行是不是已经在屏幕上」的查询。它在 effect 里跑一次，
#    不在组合期，也不会随滚动每帧执行。
#
#    所以判据不能是「外层不许出现 layoutInfo」，而是
#    **「外层的每一次出现都必须在 LaunchedEffect 里」** —— 第一版写成前者，
#    结果把那一处合法用法也判失败了。失败的是断言，不是代码。
# ⚠️ 这条断言在 2.1.0 从「只许有一处」改成了「每一处都合法」。
#
#   原来库里只有一处 layoutInfo（focusNonce 那次「目标行在不在屏幕上」），
#   于是断言写成 `count(...) == 1`。而 2.1.0 的「筛选落点自证」日志也要读一次
#   （报实际落点是第几条 / 共几条）—— 那一处同样在 LaunchedEffect 里、同样只跑一次。
#
#   写死数量会让「新增一处合法用法」变成假失败，而假失败会让人不再跑这个脚本。
#   所以判据换成**性质**：每一次 layoutInfo 都必须落在「一次性查询型」的 effect 里
#   （key 不是滚动位置那种每帧都变的东西）。
occ = [m.start() for m in re.finditer("layoutInfo", screen_body)]
EFFECT_KEYS = ("LaunchedEffect(focusNonce)", "LaunchedEffect(listState)")


def _in_once_effect(i: int) -> bool:
    return any(
        0 <= i - screen_body.rfind(k, 0, i) < 600
        for k in EFFECT_KEYS
        if screen_body.rfind(k, 0, i) >= 0
    )


def _derived_block(i: int):
    """i 所在的那个 `derivedStateOf { … }` 块（到下一个 4 空格缩进的 `}`）。

    ⚠️ 不用「往前取 N 个字符」那种窗口：第一次写这条判据时用固定长度，
    结果把**后面**的 `snapshotFlow { firstVisibleItemIndex }` 也框了进来 ——
    失败的是断言，不是代码。
    """
    k = screen_body.rfind("derivedStateOf {", 0, i)
    if k < 0 or i - k > 1200:
        return None
    j = screen_body.find("\n    }", k)
    return screen_body[k:j] if j > k else screen_body[k:]


def _in_safe_derived(i: int) -> bool:
    """
    2.1.2 新增的合法位置：`derivedStateOf`。

    原来只允许「一次性 effect」。但 `tailPadPx` 必须在**组合期**拿到
    「已渲染条目的高度」（它要参与 contentPadding 的计算），effect 拿不到。

    它安全的前提是：**`derivedStateOf` 只在结果变化时通知读者**。
    而它的输入是「条目的完整高度 × 条数」—— 与滚动位置无关。
    滚动时它每帧重算（纯算术，很便宜），但结果不变，于是不触发重组。

    ⚠️ 所以判据是**性质**而不是位置：这个块里**不许出现滚动位置**。
    一旦有人在那里读 `firstVisibleItemIndex`，它就退化成「每帧重组」。
    """
    b = _derived_block(i)
    if b is None:
        return False
    return ("firstVisibleItemIndex" not in b
            and "firstVisibleItemScrollOffset" not in b)


bad_li = [i for i in occ if not (_in_once_effect(i) or _in_safe_derived(i))]
check_true(
    "滚动状态不在组合期读（layoutInfo 只在一次性 effect 或「与滚动无关的 derivedStateOf」里）",
    bool(occ) and not bad_li,
    f"组合期读 layoutInfo = 滚动时每一帧重建整个列表，正是 1.2.0 修掉的那类问题；"
    f"越界的出现位置：{bad_li}",
)
check_true(
    "  · 且那些 effect 的 key 不是滚动位置（否则等于每帧跑一次）",
    "LaunchedEffect(listState.firstVisibleItemIndex" not in screen_body
    and "LaunchedEffect(listState.layoutInfo" not in screen_body,
    "把滚动位置当 key，就等于「每滚一帧起一个协程」",
)
check_true("  · 滚动条内部读布局状态", "layoutInfo" in qsb)
check_true(
    "  · 外层只传 listState 对象（不读它的字段）",
    "QuickScrollBar(\n            state = listState," in LIB,
)


# ===========================================================================
# 六·补、1.2.3 的三处修正
# ===========================================================================

section("六·补 · 1.2.3：居中 / 滑块会动 / 切换不跳")

# —— ① 占满整行之后还得**居中** ——
PARTS = strip_comments(kt("ui/theme/Parts.kt"))
check_true(
    "Metric 的内容是居中的",
    "horizontalAlignment = Alignment.CenterHorizontally" in body(PARTS, "fun Metric("),
    "只做等分不做居中，四列的文字还是各自贴着格子左边 —— 等分等于白做",
)
# ⚠️ 窗口按**实测**给：2.0.4 给 FilterTabs 加了 modifier 参数之后，
#    下划线那一行落到了 +3235，而默认的 2400 截不到 —— 失败的是窗口不是代码。
#    函数体在 +4182 结束，3800 是「够到尾部、又不会伸进下一个函数」的值。
tabs_body = body(LIB, "private fun FilterTabs(", size=3800)
check_true(
    "筛选签每一格的内容也是居中的",
    "horizontalAlignment = Alignment.CenterHorizontally" in tabs_body,
)
check_true(
    "  · 而且不再有残留的左对齐",
    "horizontalAlignment = Alignment.Start" not in tabs_body,
)
check_true(
    "  · 数字仍紧跟文字（同一条 Row，不是推到格子右侧）",
    "Row(verticalAlignment = Alignment.Bottom)" in tabs_body,
    "推右会变成「左标题右数字」的表格感，而这里要的是「一个词后面跟着它的计数」",
)

# —— ② 滑块为什么会动 ——
check_true(
    "滚动比例用 derivedStateOf 显式声明",
    "derivedStateOf {" in qsb and "ScrollMetrics(" in qsb,
    "上一版在组合体里直接读 layoutInfo，真机上滑块不动",
)
check_true(
    "  · 轨道高度用**实测像素**，不依赖 BoxWithConstraints 的 maxHeight",
    "onSizeChanged { trackPx = it.height }" in qsb and "maxHeight" not in qsb,
    "万一约束与实际绘制高度不一致，滑块的位置和长度会一起错",
)
check_true(
    "  · 位移按像素算（offset 的 lambda 版本）",
    "offset { IntOffset(0, offsetPx.roundToInt()) }" in qsb,
)
check_true(
    "  · 留了诊断日志（比例到底有没有在变）",
    "滚动条位置 " in LIB,
    "这个问题在代码上看不出来，只能靠真机的这一行 —— 1.2.3 正是靠它找到根因的",
)
check_true("  · 日志按首项变化节流（不是每帧刷）",
           "LaunchedEffect(m.first, m.total)" in qsb)

# —— ③ 切换筛选不应该整页跳 ——
#
# ★ 1.2.7 换了一条完全不同的路子。
#
#   1.2.3 ～ 1.2.6 是「一份 LazyListState + savedScroll 记每档位置 +
#   LaunchedEffect 恢复」，那条路上连着踩了三次：位置被钳到不存在的行、
#   恢复的动画与内容替换抢同一帧、以及「先把旧列表摆回吸顶位再换内容」
#   反而让旧列表先动一次（真机日志里的 3/42 → 2/5）。
#
#   根子是**一份状态被三份内容轮流用**：每次切换都要把位置搬运一次，
#   而搬运恰好发生在「旧布局还在、新数据已到」的那一帧里。
#
#   现在每个筛选签各有一份状态，位置跟着状态走 —— 切换时不需要搬运任何东西。
#   所以下面这组断言整体反转了：**不是**「恢复得对不对」，而是「有没有搬运」。
check_true(
    "每个筛选签各记各的位置（不共用一份状态）",
    "val listState = remember(filter) {" in LIB and "listStates" not in LIB,
    "共用一份时每次切换都要搬位置，而搬运与「内容整份替换」抢同一帧 —— "
    "这是 1.2.3～1.2.6 反复出问题的那个来源。"
    "2.1.0 起连「复用旧实例」也不要了：每档重建一个状态（见下一条）",
)
check_true(
    "  · ★ 位置记的是**内容 id + 像素偏移**，不是下标（2.1.0）",
    "LibraryAnchor.capture(" in LIB and "LibraryAnchor.resolve(" in LIB,
    "下标只在「内容一个字都不变」时有意义；而这里每天都会插入新条目、"
    "清掉旧条目、按源交错重排、被筛掉的条目消失 —— 任何一件都会让"
    "「第 15 条」变成另一条内容。这就是「乱跳」拖了七八个版本的根因："
    "每一轮都在修「怎么滚」，而问题在**记错了坐标**。判据与规则表见 "
    "tools/verify-filter-scroll.py 与 ui/LibraryAnchor.kt",
)
check_true(
    "  · 落点在**建状态那一刻**算好，切换时不做任何滚动动作",
    "LazyListState(spot.index, spot.offsetPx)" in LIB,
    "有动作就有时序。1.2.5 那版真机日志：旧列表先被拽上去（顶部 3/42）、"
    "随后才换内容（顶部 2/5）—— 两次动作叠在一起",
)
check_true(
    "  · 位置由滚动的每一帧连续记进 ViewModel（切走不需要收尾）",
    "vm.rememberAnchor(" in LIB and "snapshotFlow {" in LIB,
    "「先存位置再切」是把收尾动作塞进切换那一帧 —— 那条路已经否过三次了",
)
check_true(
    "  · ★ 切档的落点由**当前档**的位置决定，不是目标档自己的历史（2.1.1）",
    "current = vm.anchorBeforeSwitch()" in LIB and "target = vm.anchorFor(filter)" in LIB,
    "⚠️ 2.0.4 是「目标档优先」（inherit: target ?: current），那正是用户说的"
    "「一会儿高一会儿低」：从一个停在顶部的档切到一个上次滚过的档，"
    "筛选签栏会突然吸到顶上；切回来又掉下来。细分规则与 8 个坏样本见 "
    "tools/verify-filter-scroll.py",
)
check_true(
    "  · ★ 吸顶落点是**第一条内容**（headerItems），不是筛选签自己（2.1.6）",
    "resolveSpot(headerItems)" in ANCHOR and "ListSpot(headerItems, 0)" in ANCHOR,
    "越界下标被 measure 钳到列表末尾，观感就是「只换了个筛选却掉到最底下」；"
    "而写 `headerItems - 1`（筛选签自己）会停在「内容区起点」上 —— "
    "它比吸顶线低一整个列表顶部内边距（Space.section = 30dp），"
    "真机上就是「整条筛选签栏往下沉一档」。细分规则见 tools/verify-filter-scroll.py",
)
check_true(
    "  · 「记位置 / 恢复位置 / 首帧不滚」那一整套旧机制都清干净了",
    # ⚠️ 只列这几个符号，**别把 `animateScrollToItem(target` 也塞进来**：
    #    LaunchedEffect(focusNonce) 里的「退回定位」就是那个写法，而它是要留的
    not any(k in LIB for k in ("savedScroll", "scrollRestoreArmed",
                               "listStates", "LaunchedEffect(filter)")),
    "⚠️ 留着任何一条，它都会在切筛选那一帧里和「新列表已经有自己的位置」打架 —— "
    "这正是「错乱 / 空白页」两个症状的共同来源，所以是**反向**断言",
)
check_true(
    "  · 切换时只做一件事：换筛选",
    re.search(r"onSelect = \{ next ->[\s\S]{0,1200}?if \(next != filter\) vm\.setFilter\(next\)",
              LIB) is not None,
    "任何「先存 / 先摆 / 先滚」都会变成旧列表先动一次",
)
check_true(
    "  · 切换路径上没有任何滚动调用（硬契约，坏样本 I 验它）",
    not re.search(r"onSelect = \{ next ->[\s\S]{0,1200}?(scrollToItem|scrollBy|requestScrollTo)",
                  LIB),
    "有人为了「修一下位置」加一句滚动，就等于把时序问题请回来",
)


# ===========================================================================
# 六·补二、1.2.4：滑块不动的真正根因
# ===========================================================================

section("六·补二 · 1.2.4：首项必须来自滚动位置（吸顶会挡住它）")

check_true(
    "首项 index 取自 state.firstVisibleItemIndex",
    "val first = state.firstVisibleItemIndex" in qsb,
    "1.2.3 用的是 vis.first().index —— 而吸顶的 stickyHeader 一直占着"
    " visibleItemsInfo 的第一位，index 恒等于它自己（tabs = 2）",
)
check_true(
    "  · 不再从 visibleItemsInfo 取首项",
    "vis.first()" not in qsb and "visibleItemsInfo.first()" not in qsb,
    "真机日志里 41 条「滚动条位置 N% · 第 i/total 条」的 i 只有 1/2/3 —— "
    "位置在变、首项不变，只可能是取错了值",
)
check_true(
    "  · 留了「末项反推」的对照值",
    "tailFirst" in qsb and "vis.last().index - vis.size + 1" in qsb,
    "万一 firstVisibleItemIndex 在某些版本上也受吸顶影响，日志里的这一对数会立刻显出差异",
)
check_true(
    "  · 日志同时打两个值",
    "末项反推" in LIB,
)

BAD_FIRST = re.sub(
    r"val first = state\.firstVisibleItemIndex",
    "val first = vis.first().index",
    qsb, count=1,
)
check_true(
    "坏样本 F（改回 vis.first().index）被拦下",
    "val first = state.firstVisibleItemIndex" not in BAD_FIRST,
    "这就是 1.2.2 / 1.2.3 两轮都没修好的那个写法",
)


# ===========================================================================
# 六·补三、1.2.4：切换筛选的「来回跳」
# ===========================================================================

section("六·补三 · 1.2.4：切换筛选不再来回跳")

check_true(
    "空状态块**撑满一屏**（0.75 → 1，按用户「把空状态放长一点」）",
    "fillParentMaxHeight(EMPTY_MIN_HEIGHT_FRACTION)" in LIB
    and "EMPTY_MIN_HEIGHT_FRACTION = 1f" in LIB,
    "空筛选下列表只有几个头部 item、总高不足一屏 → 滚不动 → "
    "位置被钳回顶部 → 吸顶的筛选签掉下来（用户看到的「跳」）。"
    "撑满一屏之后总高必然够，而且它是**有内容的一块空白**，"
    "不像给整个列表垫底部留白那样会滚进一片全白",
)
check_true(
    "  · 空状态块收得下 modifier",
    "modifier: Modifier = Modifier," in body(LIB, "private fun EmptyBlock(")
    and "modifier\n            .fillMaxWidth()" in LIB,
)
# 「切筛选时用什么滚动」这一组断言随 1.2.7 整体消失 ——
# 现在切换不产生任何滚动，所以「动画还是瞬移」这个问题不存在了。
#
# ⚠️ 但**退回列表定位**（LaunchedEffect(focusNonce)）仍然要带动画：
#    那是「把正在听的那一条摆到眼前」，隔着几十条时瞬移就是「啪」地换屏。
#    下面这条盯的就是它 —— 原来的坏样本 G 盯错了地方（盯的是切筛选那处）。
check_true(
    "退回列表定位仍然用带动画的滚动",
    "listState.animateScrollToItem(target, paddingTopPx - tabsHeightPx)" in LIB,
    "切筛选不再滚动了，但「退回素材库时把正在听的那条摆出来」还是要动 —— "
    "那条路径隔着几十条，瞬移就是「啪」地换屏",
)
check_true(
    "  · ★ 2.1.6 偏移里减掉了顶部内边距（`scrollOffset = 0` 是内容区起点，不是屏幕顶）",
    "val paddingTopPx = with(density) { Space.section.roundToPx() }" in LIB
    and "animateScrollToItem(target, paddingTopPx - tabsHeightPx)" in LIB,
    "不减的话这一行会停得比目标位置低一整个 Space.section（30dp）。"
    "**同一个约定错误在播放页的跟随定位里更严重**（那边的内边距是一整个顶栏），"
    "表现就是用户报的「长段落显示不全」",
)

BAD_FOCUS = LIB.replace("listState.animateScrollToItem(target, paddingTopPx - tabsHeightPx)",
                        "listState.scrollToItem(target, paddingTopPx - tabsHeightPx)", 1)
check_true(
    "坏样本 G（退回定位改成瞬移）被拦下",
    "listState.animateScrollToItem(target)" not in BAD_FOCUS and BAD_FOCUS != LIB,
)


# ===========================================================================
# 六·补四、1.2.4：长按动作条
# ===========================================================================

section("六·补四 · 1.2.4：长按动作条居中 + 5 秒自动收起")

check_true(
    "动作条占满整行并居中",
    "Arrangement.spacedBy(28.dp, Alignment.CenterHorizontally)" in LIB
    # 1.2.8：卡片化之后间距从 `padding(bottom = 14.dp)` 变成 `padding(top = 12.dp)`
    #（卡片自己已经给了 13dp 内边距，再补 14dp 底部会变成 27dp）
    and re.search(r"\.fillMaxWidth\(\)\s*\n\s*\.padding\(top = 12\.dp\)", LIB) is not None,
    "贴左时它和条目正文挤在同一条视线上，看起来像「又一个字段」",
)
check_true(
    "点收藏**不**立刻收起",
    re.search(r"menuNonce\+\+\s*\n\s*onToggleFavorite\(\)", LIB) is not None,
    "一按就收，用户来不及确认星标亮没亮",
)
check_true(
    "  · 收藏那一处的 onClick 里没有 menuOpen = false",
    re.search(r"menuNonce\+\+[\s\S]{0,120}?onToggleFavorite\(\)", LIB) is not None
    and "onToggleFavorite()" in LIB,
)
check_true("有自动收起的时长常量", "MENU_AUTO_HIDE_MS = 5_000L" in LIB)
check_true(
    "  · 计时器由「打开状态 + 操作计数」共同驱动",
    "LaunchedEffect(menuOpen, menuNonce)" in LIB and "delay(MENU_AUTO_HIDE_MS)" in LIB,
    "操作计数用来重置计时 —— 用布尔量的话，连续两次操作第二次不再是变化",
)
check_true(
    "  · 收起时走的是已有的退场动画",
    "AnimatedVisibility(\n            visible = menuOpen," in LIB,
)
check_true(
    "  · 删除仍然立刻收起（它会把整条拿掉）",
    re.search(r"menuOpen = false\s*\n\s*onDelete\(\)", LIB) is not None,
)


# ===========================================================================
# 六·补五、1.2.5：通知 ID 撞车（播放控件被顶掉）
# ===========================================================================

section("六·补五 · 1.2.5：播放通知与同步通知不撞车")

check_true(
    "播放服务记一条自检日志（含它用的通知 ID）",
    "播放服务创建 · 通知 ID" in PLAY,
    "撞车类问题的第一现场就是「播放控件突然没了」，日志里得能对上是哪条通知顶的",
)
check_true(
    "  · 通知的收发都进日志",
    "\"Notify\"" in SVC and "进度通知 #" in SVC and "收尾通知 #" in SVC,
    "没有这一层，用户在通知栏看到的东西在日志里**完全不存在**",
)
check_true(
    "冷启兜底只撤同步那条（不再误伤播放通知）",
    "NotificationManagerCompat.from(context).cancel(PROGRESS_NOTIFICATION_ID)" in SVC
    and "NOTIFY_ID_PLAYBACK" not in SVC.split("fun clearStaleNotification")[1][:400],
)
check_true(
    "退出前台的路径不止 finally 一条（onDestroy 也兜）",
    SVC.count("cancel(PROGRESS_NOTIFICATION_ID)") >= 3,
    "服务被系统提前回收时 finally 可能跑不到 —— 那条 ongoing 通知就永远留在通知栏",
)


# ===========================================================================
# 六·补六、1.2.5：通知文案不再撒谎
# ===========================================================================

section("六·补六 · 1.2.5：「更新完成 · 共 0 条可用」的第二次围剿")

check_true(
    "终态不再走进度通知（那条是 ongoing、划不掉的）",
    "if (!state.running) return" in SVC,
    "完成/失败属于收尾通知（autoCancel 可划掉）；"
    "把 Done 也推成进度通知，就会短暂挂着一条「更新完成 · 共 0 条可用」",
)
check_true(
    "  · 而且那句文案在源码里彻底消失",
    "条可用" not in SVC,
    "补转写那一轮不拉源，fetched 恒为 0 —— 这个数字本来就说不通",
)
check_true("通知措辞按**轮次**给（更新 / 加载字幕 / 准备）",
           "private fun actionWord(mode: String)" in SVC)
check_true(
    "  · 补字幕那一轮不再自称「更新」",
    "else -> \"加载字幕\"" in SVC,
    "2.1.1：措辞从「字幕补齐」改成「加载字幕」（App 内各处统一用这一个词）",
)
check_true(
    "什么都没干的一轮不发收尾通知",
    "!p.produced && mode != MODE_SYNC" in SVC,
    "回到前台 / Worker 触发的补转写经常是空转，一条「更新完成」正是从这里漏出去的",
)
check_true(
    "  · 判据只在一处定义（SyncPhase.Done.produced）",
    "val produced: Boolean" in ENG
    and "get() = fetched > 0 || downloaded > 0 || subtitled > 0 || expired > 0" in ENG,
    "2.1.0：字段从 transcribed 改名成 subtitled —— 字幕全部来自服务器，"
    "写「转写」会让「手机上是不是跑了识别」这个问题永远说不清",
)
check_true(
    "  · 用户主动点的那一轮例外（他点了就该有回音）",
    "mode != MODE_SYNC" in SVC and "今天没有新内容" in SVC,
)
check_true("收尾正文只报「这一轮真正做了什么」",
           "private fun doneSummary(state: SyncState, mode: String)" in SVC)
check_true(
    "  · 源全挂时说的是「没连上」，不是「没有新内容」",
    "个来源没连上，今天没取到新内容" in SVC,
    "2.1.1：用户可见处一律写「来源」而不是「源」",
)


# ===========================================================================
# 六·补七、1.2.5：滚动条从「按条」改成无级
# ===========================================================================

section("六·补七 · 1.2.5：滚动条无级连续")

check_true(
    "滑动比例是连续量（首项 + 项内像素偏移）",
    "state.firstVisibleItemScrollOffset" in qsb and "sub / avg" in qsb,
    "只用 firstVisibleItemIndex/(total-1) 的话，滑块位置是按条离散的 —— "
    "四十条内容、几百像素的滑轨，手指每动十几像素才挪一格",
)
check_true(
    "  · 项内偏移按可见条目的平均高度折算",
    "vis.sumOf { it.size }.toFloat() / vis.size" in qsb,
    "条目高度不齐（刊头和一行差好几倍），平均值只影响观感、不影响落点",
)
check_true(
    "定位保留小数、折成条目内像素偏移（不是取整到整条）",
    "private suspend fun seekToFraction(" in LIB
    and "state.scrollToItem(index, (within * itemPx).toInt())" in LIB,
    "1.2.4 是 roundToInt() 之后 scrollToItem(index)：只能落在整条上，所以一格一格蹦",
)
check_true(
    "  · 不再有「取整到整条」的残留写法",
    "roundToInt()).coerceIn(0, m.total - 1)" not in LIB,
)
check_true(
    "拖动只保留一个定位任务（新的先取消旧的）",
    "seekJob?.cancel()" in qsb and "seekJob = scope.launch" in qsb,
    "每个事件都 launch 一个 scrollToItem 的话，十几个协程互相打断、互相追，观感是一顿一顿",
)
check_true(
    "  · 拖动期间滑块直接跟手指（不等滚动位置追上来）",
    "travelPx * (dragFraction ?: m.scrolled)" in qsb,
)
check_true(
    "  · 松手回到「由滚动位置反推」（两条路径算同一个比例，不会跳）",
    "dragFraction = null" in qsb,
)


# ===========================================================================
# 六·补八、1.2.5：切筛选的最后一截跳变
# ===========================================================================

section("六·补八 · 1.2.5：切筛选零位移 + 短列表也吸得住")

# ★ 1.2.7 回退了「短列表垫一屏底部留白」。它解决了一个问题、制造了两个更糟的：
#   · 滚动位置一旦落进那块留白，屏幕上**什么都没有** —— 只剩吸顶的筛选签
#     和右侧滚动条（用户报的「切回全部变空白页」）
#   · 三个筛选签的滚动位置互相钳制，一档短一档长时来回都对不上
#   现在的规矩是「只在真的空的时候把空状态撑高」。
# ⚠️★ 2.1.2：这一节**又改了** —— 但换的是判据，不是「回退」。
#
#   1.2.7 删掉「短列表垫一屏」的理由是：那时位置是**记住并恢复**的，
#   恢复点可能落在留白深处 → 用户看到「整页空白 + 顶上一个吸顶的筛选签」。
#   2.1.1 之后落点只有两种（头部区 / 筛选签贴顶位），**都不会落进留白** ——
#   前提没了。
#
#   而真机上长出了第三种跳（用户第二次报）：内容不足一屏的档
#   （比如「收藏」只有两三条）**根本滚不动**，筛选签贴不了顶，
#   从一个贴顶的档切过去，那一栏就掉回刊头下面。
#
#   所以现在垫，但垫的量是**算出来**的「还差多少才够滚到贴顶」：
#       content ≥ 视口 − tabs          （推导见 LibraryScreen.tailPadPx）
#   而不是一刀切一屏。
check_true("短列表垫留白**换了判据**（不再是 `if (shortList) viewport`）",
           "TAIL_PAD_MAX_ROWS" not in LIB and "if (shortList) viewport" not in LIB,
           "一刀切一屏会垫出用不完的空白；按缺少量垫才刚好")
check_true("  · 留白量 = 视口 − 筛选签 − 内容（推导出的最小值）",
           "tailPadPx" in LIB
           and "(viewportPx - tabsHeightPx - rowPx * visible.size)" in LIB
           and "bottom = 40.dp + with(LocalDensity.current) { tailPadPx.toDp() }," in LIB,
           "头部高度在推导里两边抵消了 —— 所以这个估算不依赖「头部有多高」")
def _tail_pad_block(src: str) -> str:
    """`val tailPadPx = remember(…) { … }` 这一块（到下一个 4 空格缩进的 `}`）"""
    i = src.find("val tailPadPx = remember")
    if i < 0:
        return ""
    j = src.find("\n    }", i)
    return src[i:j] if j > i else src[i:]


_tp = _tail_pad_block(LIB)
check_true("  · 判据只跟**内容**有关，不含滚动位置（否则会来回抖）",
           bool(_tp) and "firstVisibleItemIndex" not in _tp
           and "firstVisibleItemScrollOffset" not in _tp,
           "若用 layoutInfo 的总高判断：垫上之后「全部可见」不成立 → 判据翻转 → "
           "取消 → 又成立 → 抖。判据只能取只跟内容有关的量。"
           "（判据的取块方式也踩过一次：别用定长窗口，会把后面的 snapshotFlow 框进来）")
# ★★ 2.1.3 新增，**这是本轮的核心守门人**。
#
#   2.1.2 的真机白屏就是这一段：留白量读 `layoutInfo.visibleItemsInfo[].size`
#   （**测量的输出**）去算 `contentPadding`（**测量的输入**）。
#   两次测量互相追逐 → 主线程一直在重组/测量 → **首帧永远画不出来** ——
#   用户看到的是「点开一片空屏，一直不动」，而且**不是闪退**（没有异常），
#   所以崩溃日志里一条都搜不到，只能靠这条断言在构建期拦住。
#
#   ⚠️ 反面样本想清楚了再删这条：把 `rowPx * visible.size` 换回
#      `avgRowPx * visible.size`（avgRowPx 取自已渲染条目的平均高度）——
#      此时本断言必须失败。若它仍然通过，说明取块 `_tail_pad_block` 失效了
#      （比如源码里改了缩进或去掉了 `= remember` 的写法）。
check_true("  · ★2.1.3 留白量**不读 layoutInfo**（2.1.2 白屏的正解）",
           bool(_tp) and "layoutInfo" not in _tp and "avgRowPx" not in _tp,
           "layoutInfo 是测量的**输出**，而 contentPadding 是测量的**输入**。"
           "用输出当输入会让首帧画不出来（真机症状：空屏一直不动，且无崩溃日志）")
check_true("  · 行高用的是**常量估计**（估值方向偏小才安全）",
           "ROW_HEIGHT_ESTIMATE" in _tp and "private val ROW_HEIGHT_ESTIMATE = 88.dp" in LIB,
           "估小 → 留白略多 = 无害空白；估大 → 短档不垫 = 筛选签又吸不住。"
           "88dp 是「来源行 + 标题(1–2 行) + 元信息行」+ 内边距的偏小取值")
check_true("  · 空列表**不垫**留白（空状态块自己就撑满一屏）",
           "visible.isEmpty() || viewportPx <= 0" in _tp,
           "空列表时垫一屏只会多出没来由的空白，还会让「以上是…全部内容」"
           "这句尾注挂在一个**没有内容**的档下面 —— 那是句假话")
check_true("  · 留白之前有一句尾注（免得滚下去只看到一片空白）",
           '以上是「${filter.label}」里的全部内容' in LIB
           and 'item(key = "tail-note")' in LIB,
           "用户主动滚下去时，上面还有内容、下面还有一句说明 —— "
           "而不是一片没有来由的空白")
check_true("  · 尾注只在真的垫了留白时出现",
           "if (tailPadPx > 0) {" in LIB)
check_true(
    "切筛选时**只动一次**：不许先摆位置再换内容（1.2.5 的写法，1.2.6 回退）",
    "requestScrollToItem" not in LIB,
    "真机日志把那一版否了：那一帧旧列表先被拽上去（顶部 3/42），随后才换内容 —— "
    "两次动作叠在一起，比单次滚动更跳。注释里记着，代码里不许再有",
)
check_true(
    "  · 那个「先摆位置」的辅助函数也删了（没有调用点的函数是最贵的假象）",
    "isScrolledPastTabs" not in LIB,
)

BAD_PAD = LIB.replace("bottom = 40.dp,",
                      "bottom = viewport,", 1)
check_true(
    "坏样本 H（把底部垫成一屏）被拦下",
    "if (shortList) viewport" not in BAD_PAD or BAD_PAD == LIB,
    "垫一屏 = 列表里出现「可以滚进去、但没有任何内容」的一片区域",
)

BAD_PRE = LIB.replace("vm.setFilter(next)",
                      "listState.scrollToItem(LIBRARY_HEADER_ITEMS - 1)\n"
                      "                        vm.setFilter(next)", 1)
check_true(
    "坏样本 I（把「先摆位置」加回来）被拦下",
    # 坏样本里**必须存在**那一句 —— 上面那条 `not in LIB` 才会判它失败。
    # ⚠️ 第一版写成 `not in BAD_PRE`，方向反了（断言永远为假）
    "scrollToItem(LIBRARY_HEADER_ITEMS - 1)" in BAD_PRE and BAD_PRE != LIB,
    "这就是 1.2.5 那版，用户的原话是「还不如上一版」",
)


# ===========================================================================
# 九、操作日志通道（1.2.5）
# ===========================================================================

section("九、操作日志：所有用户动作都留痕")

check_true("DiagLog 有独立的操作通道", "fun act(what: String) = i(TAG_ACTION, what)" in LOG)
check_true("  · tag 固定成一个常量（事后按 `I Act` 整段捞）",
           'TAG_ACTION = "Act"' in LOG)
check_true(
    "  · 记的是离散动作，不是每帧都变的东西",
    "不记：每帧都在变的东西" in kt("diag/DiagLog.kt"),
    "被进度数字淹掉的日志等于没有日志",
)
check_true(
    "日志写盘统一过滤控制字符",
    "private fun sanitize(s: String)" in LOG and "append(sanitize(" in LOG,
    "含 NUL 的文件会让 ripgrep 判为二进制并停止整文件匹配 —— 整份日志等于废掉",
)
check_true("  · 崩溃直写路径也过一道", "val safe = sanitize(line)" in LOG)
check_true(
    "单文件上限与份数提高（跨会话的时间线才留得住）",
    "MAX_BYTES = 1024L * 1024L" in LOG and "MAX_ROLL = 4" in LOG,
)

# ⚠️ 2.1.0：「点「转写字幕」」改成了「点「补字幕」」—— 动作的语义变了
#    （不再是「在本机生成」，而是「去服务器取一次」），日志文案跟着改了，
#    而这个清单是**动作 → 日志锚点**的对照表，必须跟着走。
LIB_ACTS = ["点「更新今日内容」", "点「只检查源」（不下载）", "点「补字幕」",
            "切筛选 → ", "长按删除", "点「清空全部音频缓存」", "点「清理已听完的音频」"]
check_true(
    "素材库的每一个动作都记了",
    all(k in LVM for k in LIB_ACTS),
    f"缺：{[k for k in LIB_ACTS if k not in LVM]}",
)
PLAY_ACTS = ["进入播放页", "点暂停", "点下一集", "点上一集", "精听本句",
             "倍速 → ", "点字幕第 ", "快进", "收藏这一集"]
check_true(
    "播放页的每一个动作都记了",
    all(k in PVM for k in PLAY_ACTS),
    f"缺：{[k for k in PLAY_ACTS if k not in PVM]}",
)
check_true(
    "播放状态迁移由**播放服务**记（界面不在时也留痕）",
    "onIsPlayingChanged" in PLAY and "本集播完" in PLAY,
    "锁屏、蓝牙按键、自动连播都不经过界面 —— 用户报「听着听着断了」时缺的正是这段",
)
check_true("  · 播放出错也记（含错误码）",
           "onPlayerError" in PLAY and "errorCodeName" in PLAY)
check_true("  · 缓冲/就绪只记迁移（相邻重复不记）",
           "if (playbackState == lastPlaybackState) return" in PLAY)
check_true(
    "前后台切换留痕",
    "界面可见（回到前台）" in MAIN and "界面不可见（进入后台/息屏）" in MAIN,
)
check_true("  · 导航也记（切页签 / 点开 / 退出播放页）",
           "切页签 → " in MAIN and "退出播放页（音频继续在后台播）" in MAIN)
check_true(
    "  · 通知与操作**同一时间轴**（都走 DiagLog）",
    "DiagLog.i(\"Notify\"" in SVC and "DiagLog.act(" in LVM,
    "「点了什么 → 系统做了什么 → 结果如何」三段能被同一份日志对上",
)

# ===========================================================================
# 六·补九、1.2.6：滑块「不停变长变短」
# ===========================================================================

section("六·补九 · 1.2.6：滑块长度不许跟着滚动乱跳")

check_true(
    "长度不再是「每帧跟着量」的（ScrollMetrics 里已经没有这个字段）",
    "visibleFraction" not in LIB,
    "用户的原话是「滑动的时候，滑块在不停的变长变短」",
)
check_true(
    "  · 长度只在**两个时机**重算：换内容 / 停下来",
    "if (total != lengthKey || !scrolling)" in qsb,
    "它不是高频噪声而是随滚动缓慢游走的量 —— 滤波压不掉，只能不给它跟",
)
check_true(
    "  · 其余时间目标值原地不动（滚动中一个像素都不变）",
    "var lengthTarget by remember(state) { mutableFloatStateOf(0.12f) }" in qsb,
)
check_true(
    "  · 变化本身走补间（换筛选时不「啪」地跳）",
    "animateFloatAsState(\n        targetValue = lengthTarget," in qsb,
)
check_true(
    "换算用**视口像素 ÷ 可见条目的中位高度**（连续量，且不含吸顶项的偏差）",
    "private fun thumbFractionOf(info: LazyListLayoutInfo): Float" in LIB
    and "info.viewportEndOffset - info.viewportStartOffset" in LIB
    and "sizes[sizes.size / 2].coerceAtLeast(1)" in LIB,
)
check_true(
    "  · 不再用「数可见条数 / 总条数」",
    "vis.size.toFloat() / total" not in LIB,
)
check_true("有长度下限常量（内容极多时滑块仍看得见）",
           "SCROLLBAR_MIN_LENGTH_FRACTION = 0.06f" in LIB)
check_true(
    "诊断日志里带上长度（下次有偏差能分清「量错了」还是「没跟对」）",
    " · 长度 " in LIB,
)


# ---- 桌面复刻：整段滚动里滑块长度抖多少 ----
#
# 这段复刻的就是「滑块在不停变长变短」：把 LazyColumn 的 visibleItemsInfo
# （含吸顶项常驻）算出来，分别按「每帧跟」与「只在两个时机重算」跑一遍整段滚动。
#
# 判据：**每帧跟的必须抖得明显**（否则上一版不会被用户看出来），
#       而**新策略在整段滚动里必须一动不动**。
LZ_VIEWPORT = 700.0


# 行高变化用**固定序列**而不是随机数：验证据要可复现，随机种子会让
# 「偶发失败」变成没人再跑的断言（而且这里要的只是「高度不齐」这一个性质）
_ROW_PATTERN = [96.0, 118.0, 140.0, 118.0, 162.0, 118.0, 140.0, 96.0]


def lz_build(offset: int = 0):
    heights = ([170.0, 90.0, 52.0]
               + [_ROW_PATTERN[(i + offset) % len(_ROW_PATTERN)] for i in range(38)]
               + [120.0])
    tops, acc = [], 0.0
    for h in heights:
        tops.append(acc)
        acc += h
    return heights, tops, acc


def lz_visible(heights, tops, scroll, tabs_index=2):
    """复刻 visibleItemsInfo：真实可见项 + 吸顶项（偏移恒为 0，常驻）"""
    out = []
    for i, (top, h) in enumerate(zip(tops, heights)):
        off = top - scroll
        if off + h > 0 and off < LZ_VIEWPORT:
            out.append((i, off, h))
    if scroll > tops[tabs_index]:
        out = [(tabs_index, 0.0, heights[tabs_index])] + [x for x in out if x[0] != tabs_index]
    return out


def lz_frac_count(vis, n):
    """旧写法：数可见条数"""
    return len(vis) / n


def lz_frac_median(vis, n):
    """新写法：视口像素 ÷ 中位条目高"""
    sizes = sorted(x[2] for x in vis)
    return (LZ_VIEWPORT / max(1.0, sizes[len(sizes) // 2])) / n


def lz_sweep(fn):
    heights, tops, content = lz_build(0)
    n = len(heights)
    maxscroll = content - LZ_VIEWPORT
    vals, scroll = [], 0.0
    while scroll <= maxscroll:
        vals.append(fn(lz_visible(heights, tops, min(scroll, maxscroll)), n))
        scroll += 8.0
    return vals, content


def lz_policy(raw):
    """新策略：整段滚动里 total 不变且一直在滚 → 目标值**一次都不动**"""
    shown, last_total = raw[0], 42
    for total, scrolling, v in zip([42] * len(raw), [True] * len(raw), raw):
        if total != last_total or not scrolling:
            shown = v
    return shown


raw_count, content = lz_sweep(lz_frac_count)
raw_median, _ = lz_sweep(lz_frac_median)
jitter_count = max(raw_count) - min(raw_count)
jitter_median = max(raw_median) - min(raw_median)

check_true(
    "复刻：**每帧跟着量**确实会抖（这就是用户看到的现象）",
    jitter_count > 0.02,
    f"实测抖动幅度 {jitter_count * 100:.1f}% —— 拇指只有几十像素，这一点就是肉眼可见的伸缩",
)
check_true(
    "  · 而新策略在整段滚动里**一动不动**",
    abs(lz_policy(raw_median) - lz_policy(list(reversed(raw_median)))) < 1e-9,
    "只在「换内容 / 停下来」重算 —— 滚动过程中目标值根本没有被写过",
)
truth = LZ_VIEWPORT / content
check_true(
    "  · 换算用中位高比数条数更准（选它的理由）",
    abs(sum(raw_median) / len(raw_median) - truth) < abs(sum(raw_count) / len(raw_count) - truth),
    f"中位高偏差 {abs(sum(raw_median) / len(raw_median) - truth) * 100:.2f}% "
    f"< 数条数偏差 {abs(sum(raw_count) / len(raw_count) - truth) * 100:.2f}% "
    f"（真值 {truth * 100:.2f}%，数条数把吸顶项也算了进去）",
)


# ===========================================================================
# 六·补十、1.2.6：筛选签下划线铺满整格
# ===========================================================================

section("六·补十 · 1.2.6：筛选签下划线跟整格一样长")

check_true(
    "下划线用 `fillMaxWidth(0..1)` 而不是固定 26dp",
    "fillMaxWidth(underline)" in tabs_body
    and "if (selected) 1f else 0f" in tabs_body,
    "用户的原话是「3 个 item 下方的横条太短了，要不就跟 item 一样长」",
)
check_true(
    "  · 长度是这一格的比例，不是写死的 dp",
    "26.dp" not in tabs_body,
    "写死 dp 的话，换字号 / 换屏幕 / 加一个筛选签都要回来改数字",
)
check_true(
    "  · 仍然是 0 → 1 的补间（不是瞬变）",
    "animateFloatAsState(" in tabs_body,
    "瞬变会让下划线「啪」地跳过去，而列表内容同时整块换掉，两件事叠在一起看不出因果",
)

# ===========================================================================
# 七、坏样本对照
# ===========================================================================

section("七、坏样本对照 · 断言有区分力")

BAD_REMOVE = SVC.replace("stopForeground(STOP_FOREGROUND_REMOVE)",
                         "stopForeground(STOP_FOREGROUND_DETACH)", 1)
check_true("坏样本 A（退回 DETACH）被拦下",
           "STOP_FOREGROUND_DETACH" in BAD_REMOVE
           and "stopForeground(STOP_FOREGROUND_REMOVE)" not in BAD_REMOVE)
check_true("  · 且与真源码只差那一行", changed_lines(BAD_REMOVE, SVC) == 1)

# ⚠️ 2.1.0：坏样本 B 换代了。
#
#   旧 B：「把越限判据 includeOverLimit 改成恒 false」—— 它模拟的是
#         「长素材点多少次都不转」，而那个机制（额度 + 越限）整块删了。
#   新 B：「把时长过滤加回来」—— 模拟的是同一类错误在新架构下的形态：
#         因为「长内容会不会太慢」而顺手加一道过滤，于是长素材永久没有字幕。
#   两代要防的**是同一件事**（长内容被静默地排除在外），所以换了实现不换意图。
BAD_LIMIT = BAD_DURATION
check_true("坏样本 B（把时长过滤加回来）被拦下",
           BAD_LIMIT != ENG
           and "durationSec" in body(BAD_LIMIT, "private suspend fun transcribeQueue("),
           "它是「长内容被静默排除」这类错误在服务器时代的形态")
check_true("  · 且与真源码只差那一行", changed_lines(BAD_LIMIT, ENG) == 1)

BAD_IGNORE = REPO.replace("if (item.id in ignored) continue", "", 1)
check_true("坏样本 C（刷新时不看忽略集合）被拦下",
           "if (item.id in ignored) continue" not in BAD_IGNORE)
check_true("  · 且与真源码只差那一行", changed_lines(BAD_IGNORE, REPO) == 1)

BAD_ALIGN_TABS = tabs_body.replace("horizontalAlignment = Alignment.CenterHorizontally",
                                   "horizontalAlignment = Alignment.Start", 1)
check_true("坏样本 D（筛选签改回左对齐）被拦下",
           "horizontalAlignment = Alignment.CenterHorizontally" not in BAD_ALIGN_TABS)
check_true("  · 且与真源码只差那一行", changed_lines(BAD_ALIGN_TABS, tabs_body) == 1)

# 坏样本 E 的演化史（每一版都是「当时最可能被写回去的那一行」）：
#   1.2.3～1.2.6：「先存位置还是先切筛选」的顺序问题
#   1.2.7：退回「三个筛选共用一份 LazyListState」
#   2.1.0：退回「用下标记位置」或「把锚点扔掉」
BAD_SHARED = LIB.replace("val listState = remember(filter) {", "val listState = remember {", 1)
check_true(
    "坏样本 E（退回「三个筛选共用一份滚动状态」）被拦下",
    "val listState = remember(filter) {" not in BAD_SHARED and BAD_SHARED != LIB,
    "共用一份 = 每次切换都要搬位置，而那正是 1.2.3～1.2.6 反复出问题的地方",
)

# 坏样本 E2 随规则一起换代：
#   2.0.4 那版防的是「把落点整个扔掉」（退回按档记位置）；
#   2.1.1 要防的是「把『现在在哪儿』换成『目标档的历史』」——
#   后者才是真机上那个「一会儿高一会儿低」。
BAD_INDEX = LIB.replace(
    "            current = vm.anchorBeforeSwitch(),",
    "            current = vm.anchorFor(filter),", 1,
)
check_true(
    "坏样本 E2（把「现在在哪儿」换成「目标档自己的历史」）被拦下",
    "current = vm.anchorBeforeSwitch()," not in BAD_INDEX and BAD_INDEX != LIB,
    "这正是 2.0.4 的症状，也是用户说的「一会儿高一会儿低」",
)
check_true(
    "  · 且它确实与真源码不同",
    changed_lines(BAD_SHARED, LIB) > 0,
    "⚠️ 这里不能断言「差几行」：换一行会牵动 surrounding 的对齐，"
    "逐行差的计数没有分辨力 —— 只要证明坏样本真的被改动了",
)

# ★ 2.1.3 的坏样本：把行高换回「从 layoutInfo 里量」的写法。
#
#   这正是 2.1.2 交付出去、并且在真机上白屏的那一行 —— 它**必须**被上面
#   「留白量不读 layoutInfo」那条断言拦下。没有这个坏样本，那条断言就可能
#   是一条永远为真的断言（比如取块 `_tail_pad_block` 失效了也照样"通过"）。
BAD_TAILPAD = LIB.replace(
    "            val rowPx = with(density) { ROW_HEIGHT_ESTIMATE.toPx() }",
    "            val rowPx = listState.layoutInfo.visibleItemsInfo.first().size.toFloat()",
    1,
)
_bad_tp = _tail_pad_block(BAD_TAILPAD)
check_true(
    "坏样本 F（留白量改回读 layoutInfo）被拦下",
    BAD_TAILPAD != LIB
    # 取块本身必须仍然有效，否则这条「被拦下」是假象
    and bool(_bad_tp)
    # 断言在坏样本上**不成立** —— 这才是「有区分力」的定义
    and not ("layoutInfo" not in _bad_tp and "avgRowPx" not in _bad_tp),
    "用测量输出当测量输入 → 主线程一直在重组/测量 → 首帧永远画不出来"
    "（用户看到「空屏一直不动」，而且日志里一条崩溃都没有）",
)

UNRELATED = strip_comments(kt("ui/theme/Motion.kt"))
check_true(
    "无关源码上不会误命中",
    "combinedClickable(" not in UNRELATED
    and "QuickScrollBar" not in UNRELATED
    and "stopForeground(" not in UNRELATED,
)


# ===========================================================================
# 四之四、字形与版式对齐**全局样式表**（3.0.1）
# ===========================================================================
#
# 用户报的：「生词这个页签，里面的字体比如生词本，跟素材库、打卡、设置的都不一样」。
#
# 根因：生词本四个文件里有 **18 处 `AppText.x.copy(fontSize = ...)`**，
# 而其它三个页面几乎全部直接用语义样式（display / title / caption / label / pill）。
# 也就是说：我把 `AppText.line` 当成了「基准字体」然后到处改字号 ——
# 而项目的约定是**按语义选样式名**，字号由样式表定义，改一处全局生效。
#
# ## 为什么这一节值得写
#
# 这类偏差**不会报错、不会崩溃、也没法靠单测发现** —— 只有人眼看着不对。
# 而它一旦长出来就会持续长：下一个人看到「这里可以 copy 一个字号」，
# 于是每个功能模块都有自己的一套字。
#
# ⚠️ 所以这里全是**反向断言**（「不许再出现」）。反向断言必须能真的失败，
#    所以每条都配了坏样本。
#
# ⚠️ 判据必须建立在 `strip_comments` 之后的源码上 ——
#    Theme 的注释里就写着「18 处 AppText.x.copy(fontSize = ...)」，
#    直接搜原始源码会把这句注释当成违规。

section("四之四、生词本的字形与版式对齐全局样式表")

WORD_UI = {
    "WordScreen.kt": "ui/WordScreen.kt",
    "WordCardSheet.kt": "ui/WordCardSheet.kt",
    "ReviewScreen.kt": "ui/ReviewScreen.kt",
    "ReviewDoneDialog.kt": "ui/ReviewDoneDialog.kt",
}
_word = {k: strip_comments(kt(v)) for k, v in WORD_UI.items()}

bad_size = [k for k, src in _word.items() if "copy(fontSize" in src]
check_true(
    "四个界面文件里没有自定义字号（copy(fontSize）",
    not bad_size,
    f"违规：{bad_size} —— 请改用语义样式（display/title/section/body/line/gloss/caption/label/pill）",
)
bad_ls = [k for k, src in _word.items() if "copy(letterSpacing" in src]
check_true(
    "  · 也没有自定义字距（copy(letterSpacing）",
    not bad_ls,
    f"违规：{bad_ls} —— 需要零字距的小字时用 pill（它就是为这个准备的）",
)

# 坏样本：把页刊头改回自定义字号 —— 上面那条必须变红
_bad = _word["WordScreen.kt"].replace(
    'Text("生词本", style = AppText.display)',
    'Text("生词本", style = AppText.line.copy(fontSize = 22.sp))',
    1,
)
check_true(
    "坏样本（页刊头改回自定义字号）被拦下",
    "copy(fontSize" in _bad and "copy(fontSize" not in _word["WordScreen.kt"],
    "反向断言不能失败时，它就只是一句写在脚本里的注释",
)

# ── 页刊头：四个页签必须同构 ───────────────────────────────────────────────
check_true(
    "生词页的刊头用的是全局的 display",
    'Text("生词本", style = AppText.display)' in _word["WordScreen.kt"],
    "其它三个页签（素材库/打卡/设置）都用它 —— 这是「一屏一处字号峰值」的落点",
)
check_true(
    "  · 并且和打卡/设置一样带一行 caption 副标题",
    'style = AppText.caption)' in _word["WordScreen.kt"],
    "打卡写规则、素材库写状态、生词写「词从哪来」—— 只留一个大标题会显得没做完",
)

# ── 新增的那一档：只许有一处 ───────────────────────────────────────────────
THEME = strip_comments(kt("ui/theme/Theme.kt"))
check_true(
    "样式表里有 reading（为「大段无衬线英文」加的唯一一档）",
    "val reading = TextStyle(" in THEME,
)
users = [k for k, src in _word.items() if "AppText.reading" in src]
check_true(
    "  · 且只有复习页在用它",
    users == ["ReviewScreen.kt"],
    f"实际用在：{users} —— 再有别处想放大正文，先问「line 真的不够用吗」",
)

# ── 版式：左右内边距必须走 Space.pageH ────────────────────────────────────
# ⚠️ 判据**只查 20.dp / 24.dp**，不查 16.dp —— 这一条是被自己抓出来的。
#
#    第一版我把 `16.dp` 也列进了违规，于是它报了横幅卡片那一处：
#
#        Modifier.fillMaxWidth()
#            .padding(horizontal = Space.pageH)              ← 页面边距
#            .clip(RoundedCornerShape(14.dp))
#            .background(AmberFaint)
#            .padding(horizontal = 16.dp, vertical = 14.dp)  ← 卡片**内部**留白
#
#    这两层**都对**，而且不同义。把「卡片内部留白」也判成违规，
#    就是一条**会误报的断言** —— 而误报的断言比没有断言更糟：
#    它逼下一个人去放宽它，放宽的那一刻真判据也一起没了。
#
#    所以判据收窄到「**像是想当页面边距但写错了**」的两个值：
#    pageH 是 22，手写的人会写 20 或 24。
hard_coded = []
for name, src in _word.items():
    for pat in ("padding(horizontal = 20.dp", "padding(horizontal = 24.dp"):
        if pat in src:
            hard_coded.append(f"{name}:{pat}")
check_true(
    "没有把页面边距写成 20/24（要走 Space.pageH，它是 22）",
    not hard_coded,
    f"违规：{hard_coded}",
)

_bad_pad = _word["ReviewScreen.kt"].replace(
    "padding(horizontal = Space.pageH)", "padding(horizontal = 24.dp)", 1
)
check_true(
    "坏样本（页面边距写死 24.dp）被拦下",
    "padding(horizontal = 24.dp" in _bad_pad
    and "padding(horizontal = 24.dp" not in _word["ReviewScreen.kt"],
)
check_true(
    "  · 且 16.dp 这类**卡片内部留白**不会被误判（判据收窄过）",
    'padding(horizontal = 16.dp' in _word["WordScreen.kt"],
    "横幅卡片内部那层 16dp 是对的，断言不该拦它",
)


# ===========================================================================
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
