#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
打卡「每日目标」的契约（3.3.0 新建，3.4.0 按新语义重写）。

## 3.4.0：这个脚本为什么要重写

3.3.0 把 `GOAL_SECONDS` 从常量做成设置项，语义是「达标按**当前**目标现算」。
那条不变式的代价写在当时的注释里，而且**为它辩护过**：

    另一条路是「每天记下当天的目标」，那要为一个几乎是单调的量建一张按日的表，
    而且用户会看到「20 号算达标、21 号不算，可我明明两天听的一样多」。

用户用了两天，反馈是：**要第二天生效，不要动历史记录。**

也就是说我当初判断「不值得建那张表」的前提（用户不在意历史被重算）是错的。
而这类判断的代价从来不在写代码那一刻 —— 在用户真的用起来之后。
（这类「前提过期」的教训本工程记过几次，最近一次是「先不做成可配置」。）

## 现在的语义（一句话）

    目标是一条按天的时间轴：某天算不算达标，永远问「**那一天**的目标是多少」。

于是它带来三个**改完不报错**的危险，这一整个脚本就是冲着这三件事去的：

### ① 某处还在用「当前目标」判过去

最容易漏的是日历格子、打卡记录、海报那三处 —— 它们原来接收一个共用的
`goal` 参数。改成按天之后忘改其中一处，界面上每一处单看都合理，
**只有把它们放一起才看得出互相矛盾**（日历画满、记录里却写「未达标」）。

### ② 迁移把历史改掉

3.3.0 只存了一个「当前目标」，没有日期。升级到这一版时假如把它当成
**从今天起生效**，那过去所有按旧目标算达标的日子会一起变成未达标、
连续天数当场掉下来 —— 那正是用户这次要求修的东西，不能让升级自己制造一次。
所以它必须落在 `SINCE_ALWAYS`（从最早生效）。

### ③ 顺手把「报后果」那段老逻辑留着

3.3.0 的 `setGoalMinutes` 要比较改前改后的连续天数、并清掉海报重画
（因为那时今天达标与否可能整个翻转）。这一版**没有那个副作用**了：
改目标只写 `today + 1`，今天的阈值一个字节都没变。
留着那段代码不会报错，但它是「按旧语义写的」—— 它是活的错误信息源。

## 这个脚本守什么

一、目标是一条按天的时间轴（且只有一份实现）
二、★ 改目标只影响明天 —— 历史一个字节都不动
三、界面三处（日历 / 记录 / 海报）都按**那一天**算
四、清空记录不许动目标，也不许取消「明天起」那个改动
五、3.3.0 的单值迁移必须落在「从最早生效」
六、界面要说清「明天起生效」和「记录不变」
七、坏样本（每条关键判据都要能在坏样本上失败）
"""
import io
import os
import sys

ROOT = os.path.dirname(os.path.abspath(__file__)) + "/.."
KT = os.path.join(ROOT, "app/src/main/java/com/example/englishcommute")

TITLE = os.path.join(KT, "checkin/GoalTimeline.kt")
STORE = os.path.join(KT, "checkin/CheckInStore.kt")
POSTER = os.path.join(KT, "checkin/CheckInPoster.kt")
VM = os.path.join(KT, "ui/CheckInViewModel.kt")
SCREEN = os.path.join(KT, "ui/CheckInScreen.kt")

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
    """去掉注释、**字符串字面量原样保留**。

    ⚠️ 本文件的 KDoc 里原样解释了三种错法（`prefs.edit().clear()`、
       `const val GOAL_SECONDS`、`连续打卡 $before 天 → $after 天`），
       不去注释的话每条反向断言都会命中自己的说明文字 —— 那是**假失败**。
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
    """从 marker 起取**一整个函数体**（按大括号配平）。

    ⚠️ 这里踩过一次，值得记：
       第一版写的是「取到第一个 `\\n}` 之前」。而 Kotlin 里函数自己的收尾括号是
       **缩进**的（`    }`），所以那个 `\\n}` 匹配的是**类**的收尾 —— 于是
       「函数体」变成了「从那个函数到文件末尾」，把后面的函数全都框了进来。

       后果很隐蔽：`loadDays()` 里也有一句 `key.startsWith(KEY_SEC)`，
       而它落在 `reset()` 的“函数体”里 ⇒ 一条坏样本断言**假通过**
       （坏样本明明把 reset 改坏了，可它去看的是别的函数）。

       **判据取多了和取少了都不行**：取多了会看到不该看的东西（假绿），
       取少了会漏掉该看的东西（假红）。所以按括号配平，不猜缩进。
    """
    i = src.find(marker)
    if i < 0:
        return ""
    j = src.find("{", i)
    if j < 0:
        return ""
    depth, k = 0, j
    while k < len(src):
        if src[k] == "{":
            depth += 1
        elif src[k] == "}":
            depth -= 1
            if depth == 0:
                return src[i:k + 1]
        k += 1
    return src[i:]


title_raw = read(TITLE)
store_raw = read(STORE)
poster_raw = read(POSTER)
vm_raw = read(VM)
screen_raw = read(SCREEN)

title = code_only(title_raw)
store = code_only(store_raw)
poster = code_only(poster_raw)
vm = code_only(vm_raw)
screen = code_only(screen_raw)

# ===========================================================================
section("一、目标是一条按天生效的时间轴")
# ===========================================================================

check("存在一个纯粹的「按天求目标」模块",
      "object GoalTimeline" in title,
      "求值逻辑必须能在**没有 Context 的地方**被单测逐天推演 —— "
      "「改目标不动历史」这条不变式就是在那儿钉住的（GoalTimelineTest）")

check("  · 求值取「不大于那天的最大条目」",
      "fun secondsOn(changes: Map<Long, Int>, day: Long)" in title
      and "key <= day" in title,
      "取「不大于」而不是「等于」：改动是稀疏的，中间那些天沿用上一条")

check("  · 默认仍是 40 分钟",
      "const val DEFAULT_MINUTES = 40" in title,
      "需求原文就是 40 分钟；做成可配置**不该顺手把默认值也改了** —— "
      "老用户升级上来必须还是 40")

check("  · 写入前钳制到可选范围",
      "coerceIn(MIN_MINUTES, MAX_MINUTES)" in title,
      "不钳制的话，一个算错的数字会把「达标」变成永远不可能")

check("  · 时间轴暴露成 StateFlow（界面要跟着它一起动）",
      "val goalChanges: StateFlow<Map<Long, Int>>" in store,
      "写成普通字段的话，改完只有那行文字会变，进度条/日历/连续天数要等"
      "下一次因为别的原因重组才跟上 —— 看起来像界面坏了")

# ★ 反向断言：判据只许有一份实现。留一个「当前目标」的字段就一定会有人继续读它。
check("★ 反向：Store 里不再有「当前目标」这个字段",
      "_goalMinutes" not in store and "val goalSeconds: Int get()" not in store,
      "★ 留着它最危险 —— 某处按天算、某处读当前值，界面会自相矛盾，"
      "而且每一处单看都正确")

def compares_to_a_single_goal(src):
    """有没有「直接和某一个目标值比」的写法。

    ⚠️ 判据必须是**前缀 + 边界**，不能是裸的 `>= goalSeconds`：
      按天取的那个函数叫 `goalSecondsOn(day)`，它**以同样的前缀开头** ——
      裸匹配会把正确的写法也判成错的（一条永远为假的断言）。
      `(?!On)` 就是那个边界。
    """
    import re
    return re.search(r">=\s*(store\.)?goalSeconds(?!On)", src) is not None


check("★ 反向：判据里不许再出现「直接和某个目标比」的写法",
      not compares_to_a_single_goal(store) and not compares_to_a_single_goal(vm),
      "那一形态就是 3.3.0 的「用今天的标准审判昨天」")

for fun, label in (("fun isDone(", "isDone"),
                   ("fun doneDays(", "doneDays"),
                   ("fun addSeconds(", "addSeconds"),
                   ("fun snapshot(", "snapshot")):
    body = block(store, fun)
    check("  · %s 按**那一天**取目标" % label,
          "goalSecondsOn(day)" in body,
          "漏掉一处就是「日历说达标了、连续天数说没有」这种矛盾")

# ⚠️ 4.1.1：这条判据挪过一次家 —— 累加与跨线判据现在都在
# `SyncMerge.accumulate` 里（那是纯函数、有单测），`addSeconds` 只做三件事：
# 取出「本机那一列」、调它、落盘。判据跟着挪，**意图一个字没变**：
# 跨线必须按**那一天**的目标算。
MERGE_SRC = read(os.path.join(KT, "checkin/SyncMerge.kt"))
check("  · 跨线（庆祝）判据也按那一天",
      "goal = goalSecondsOn(day)," in block(store, "fun addSeconds(")
      and "justCompleted = beforeTotal < goal && total >= goal" in MERGE_SRC,
      "庆祝页与海报都挂在这个判据上；用当前设置的话，补记前几天的时长时"
      "要么不庆祝、要么提前庆祝")

check("  · 单日封顶没被顺手改掉",
      "MAX_DAY_SECONDS = 24 * 60 * 60" in store)

# ===========================================================================
section("二、★ 改目标只影响明天（历史一个字节都不动）")
# ===========================================================================
# 这是这一轮用户明确要求的那件事，也是整个脚本最值钱的一节。
# 判据落在**事实**上：写入函数只写 today + 1，且 Store 走的是那个函数。

check("★ 时间轴只提供「写明天」这一个入口",
      "fun withChange(changes: Map<Long, Int>, today: Long, minutes: Int)" in title
      and "today + 1" in block(title, "fun withChange("),
      "★ 一旦允许调用方传「哪天生效」，就一定会有人传 today —— "
      "那正是这一版要修掉的旧行为。生效日收在函数里，比散在调用点上靠自觉可靠")

check("  · 改动会顺手去掉冗余条目（但不许把「改回默认」当冗余）",
      "fun normalize(" in title and "v != effective" in block(title, "fun normalize("),
      "冗余的定义是「与它生效前的值相同」，不是「等于默认值」—— 后者是两次真实的改动")

check("  · Store 的写入口走的就是它",
      "GoalTimeline.withChange(_goalChanges.value, today, minutes)" in store,
      "自己再拼一遍 `putInt` 就等于把「只写明天」这条规则抄了一份")

check("★ 反向：不许再出现「把单个目标写进去」的老形态",
      "putInt(KEY_GOAL," not in store.replace("putInt(KEY_GOAL + day", ""),
      "那是 3.3.0 的写法：一个没有日期的值 —— 它没有历史，也就救不回历史")

check("  · 落盘按「整份重写 + 删掉不再存在的条目」",
      "doomed.forEach { editor.remove(KEY_GOAL + it) }" in store
      and "next.forEach { (day, sec) -> editor.putInt(KEY_GOAL + day, sec) }" in store,
      "只加不删的话，改回原值之后 prefs 里会留下一条永远生效的旧条目")

vm_set = block(vm, "fun setGoalMinutes(")

check("  · 日志说清「明天起生效」",
      "明天起生效" in block(store, "fun setGoalMinutes("),
      "日志是排查「我明明改过怎么没变」时的唯一凭据 —— "
      "只说「改为 30 分钟」的话，它和旧语义下的同一句话长得一模一样")

check("★ 反向：「报后果」那段老逻辑不许留着",
      "currentStreak()" not in vm_set and "连续打卡" not in vm_set,
      "★ 3.3.0 要比较改前改后的连续天数，因为那时改目标会让历史重算。"
      "这一版没有那个副作用了 —— 留着这段代码不会报错，"
      "但它是「按旧语义写的」，早晚会误导下一次改动")

check("★ 反向：改目标不再重画海报",
      "ensureTodayPoster" not in vm_set and "_poster.value = null" not in vm_set,
      "★ 今天的阈值没变（改动只写 today+1），所以今天那张图的内容也不会变。"
      "3.3.0 留着这段是对的（那时可能整个翻转），现在留着就是白重画一张同样的图")

# ===========================================================================
section("三、界面三处都按**那一天**算")
# ===========================================================================
# 这一节防的是「只改了一部分」—— 界面上每一处单看都合理，
# 只有把它们放一起才看得出互相矛盾。


def calendar_is_per_day(src):
    return "vm.goalMinutesOn(day)" in block(src, "private fun CalendarBlock(")


def record_is_per_day(src):
    return "vm.isDone(day)" in block(src, "private fun RecordBlock(")


def poster_is_per_day(src):
    return "store.goalMinutesOn(day)" in code_only(src)


check("★ 日历格子逐天取目标",
      calendar_is_per_day(screen),
      "★ 共用一个「当前目标」的话，改一次目标会把过去画满的格子一起变浅 —— "
      "那是**偷偷重写历史**，而且发生在用户最相信这张图的时候")

check("★ 打卡记录逐天判达标",
      record_is_per_day(screen),
      "★ 拿一个共用的 goal 去比，等于用今天的标准审判昨天")

check("★ 海报取的是**那一天**的目标",
      poster_is_per_day(poster_raw)
      and "goalMinutes: Int," in poster_raw
      and "目标 $goalMinutes 分钟" in poster_raw,
      "★ 海报是唯一一处**会被存下来、发出去**的目标展示。"
      "印错的话，那张图会一直留在对方的聊天记录里")

check("  · 反向：海报渲染里不再引用任何全局常量",
      "GOAL_SECONDS" not in poster)

check("  · 今天那一份仍然只有一个来源（进度条 / 还差多少）",
      "val goal = goalMinutes * 60" in screen,
      "今天的目标由 VM 派生（`goalMinutes`），日历与记录另走逐天取 —— "
      "两件事，但都不许各自再算一次")

check("  · 海报的重算触发条件里**不再需要**目标",
      "LaunchedEffect(days, today)" in screen,
      "这条只有在「改目标只写 today+1」成立时才对 —— 上面第二节已经钉住了它。"
      "把目标塞回 key 列表只会白重画一张内容完全相同的海报")

check("  · 有若干档位可选（不是自由输入），且档位只有一份",
      "PRESETS: List<Int> = listOf(10, 20, 30, 40, 60, 90)" in title
      and "GoalTimeline.PRESETS" in vm,
      "档位抄两份的话，界面按一套显示、判据按另一套算")

# ===========================================================================
section("四、清空记录不许动目标")
# ===========================================================================

store_reset = block(store, "fun reset(")

check("★ reset 不再整份 clear()",
      "prefs.edit().clear()" not in store,
      "★ `clear()` 会把目标一起清掉：用户清一次记录，"
      "自己设定的目标悄悄回到默认，而界面上没有任何提示")

check("  · 改成按前缀删（sec: / poster:）",
      "startsWith(KEY_SEC)" in store_reset and "startsWith(KEY_POSTER)" in store_reset,
      "按前缀删让这件事变成结构性的：目标条目虽然也带前缀，但它不在被点名的那两个里")

check("  · 注释里写明了「记录 vs 目标」这条界线",
      "保留每日目标" in store_reset or "每日目标也变成了按天的条目" in store_raw,
      "不写的话，下一个人会觉得「清空就是清空」而把它改回去")

check("  · ★ 也不许顺手删掉过去的 goal 条目",
      "goal" not in store_reset.replace("KEY_SEC", "").replace("KEY_POSTER", ""),
      "★ 那会顺手改掉「今天按多少算」—— 比如用户的目标是从最早生效的 30 分钟，"
      "把过去的条目删掉就等于把今天也变回 40")

check("  · 清完之后告诉用户目标还在",
      "每日目标保留为" in vm_raw,
      "这是最好的一处提示位：用户刚做完一个「清空」动作，此刻最合适确认"
      "「我只清了记录」")

# ===========================================================================
section("五、3.3.0 的单值迁移必须落在「从最早生效」")
# ===========================================================================

check("★ 迁移用 SINCE_ALWAYS，不是今天",
      "GoalTimeline.SINCE_ALWAYS" in store,
      "★ 3.3.0 只存了一个没有日期的数。把它当成「从今天起生效」的话，"
      "升级那一刻过去按旧目标算达标的日子会一起变成未达标、连续天数当场掉 —— "
      "那正是用户这次要求修的东西，不能让升级自己制造一次")

check("  · 迁移过一次就把老键删掉（同一件事不留两个来源）",
      "prefs.edit().remove(KEY_LEGACY_GOAL)" in store,
      "留着它的话，哪次读到空的 `goal:` 集合又会把老值搬回来")

check("  · 两个 key 必须能一眼分开：新键带冒号，老键不带",
      'private const val KEY_GOAL = "goal:"' in store
      and 'private const val KEY_LEGACY_GOAL = "goal"' in store,
      "靠 `toLongOrNull()` 兜底也能「碰巧不出错」，但那是隐式的 —— "
      "把区别写在 key 本身，读代码的人不需要推理就能看出来")

check("  · 迁移会在日志里留一行",
      "每日目标迁移" in store,
      "迁移是**一次性的、会改变判据来源**的动作。没有这一行的话，"
      "升级之后出现的任何「达标情况对不上」都无从下手")

# ===========================================================================
section("六、界面要说清「明天起生效」和「记录不变」")
# ===========================================================================
# 改目标这件事在用户那边的全部焦虑就是一句：「我攒的连续记录会不会没了」。
# 所以文案不是装饰，它是这个功能能不能被放心使用的一部分。

goal_block = block(screen_raw, "private fun GoalBlock(")

check("★ 明确写出「以前的记录不变」",
      "以前的打卡记录不变" in goal_block or "以前的记录不变" in goal_block,
      "★ 用户正是因为担心这件事才提的这个需求。不写的话，改了目标之后"
      "他看到连续天数没变，也不会知道那是「没动」还是「他数错了」")

check("  · 有待生效的改动时写清「今天仍按 N 分钟」",
      "今天仍按 $todayMinutes 分钟" in goal_block,
      "上面写着「/ 40 分钟」、这里高亮 30 —— 不解释的话看起来像界面自相矛盾")

check("  · 高亮的是**设置值**（有待生效的改动时就是新值）",
      "val setting = tomorrowMinutes ?: todayMinutes" in goal_block
      and "selected = m == setting" in goal_block,
      "高亮今天的值、把新值只写在文案里看起来更「诚实」，实际更糟："
      "用户点完 30 看到高亮还在 40 上，第一反应是「没点上」")

check("  · 而且有一个「明天起」的标记解释它",
      "明天起" in goal_block,
      "高亮跳到还不是今天的那个值上时，必须有东西解释它")

check("  · VM 提供了「明天起」这个派生值",
      "val tomorrowGoalMinutes: StateFlow<Int?>" in vm
      and "GoalTimeline.pendingMinutes(" in vm,
      "没有它的话，用户点完只能看到一句什么都没变的提示")

check("  · 提示语报的是生效时间，不是「掉了多少天」",
      "明天起改为 $tomorrow 分钟" in vm_set,
      "与第二节那条反向断言配对：报后果的老逻辑必须真的换成报生效时间")

# ===========================================================================
section("七、坏样本")
# ===========================================================================

# 坏样本 1：写入口从「明天」改成「今天」—— 那就是 3.3.0 的行为
bad_write = title_raw.replace("((today + 1) to sec)", "(today to sec)", 1)
check("坏样本 1（生效日改回今天）被拦下",
      "today + 1" in block(title, "fun withChange(")
      and "today + 1" not in block(code_only(bad_write), "fun withChange("),
      "★ 这就是 3.3.0 的行为：改目标立刻生效，于是历史跟着重算、连续天数会掉")

# 坏样本 2：日历用共用的「当前目标」
#
# ⚠️ 锚点必须取**代码里独有的那一整行**。第一版用的是裸的 `vm.goalMinutesOn(day)`，
#    而它在 CalendarBlock 的 KDoc 里也被原样写过 —— 替换打在了注释上，
#    真正的调用点一个字没动，于是这条坏样本「通过」了（什么都没测）。
#    「替换打在注释上」是本工程记过账的一类，这里又踩了一次。
bad_cal = screen_raw.replace("val dayGoal = vm.goalMinutesOn(day) * 60", "val dayGoal = goalToday * 60", 1)
check("坏样本 2（日历用当前目标）被拦下",
      calendar_is_per_day(screen) and not calendar_is_per_day(code_only(bad_cal)),
      "过去画满的格子会一起变浅 —— 偷偷重写历史")
check("  · 坏样本 2 的构造本身是有效的",
      bad_cal != screen_raw,
      "替换没生效的话，这条坏样本「通过」是假的")

# 坏样本 3：记录用共用的 goal
_bad_rec = screen_raw.replace("val ok = vm.isDone(day)", "val ok = sec >= goal", 1)
check("坏样本 3（记录用共用的目标）被拦下",
      record_is_per_day(screen) and not record_is_per_day(code_only(_bad_rec)),
      "用今天的标准审判昨天")

# 坏样本 4：海报退回读「当前目标」
_bad_post = poster_raw.replace("store.goalMinutesOn(day)", "store.goalChanges.value.size * 0", 1)
check("坏样本 4（海报不再按天取）被拦下",
      poster_is_per_day(poster_raw) and not poster_is_per_day(_bad_post),
      "那张图会被存下来、发出去")

# 坏样本 5：迁移落在今天
#
# ⚠️ 锚点同样取代码里独有的那一整段 —— 裸的 `GoalTimeline.SINCE_ALWAYS`
#    在 KDoc 与行内注释里都出现过，替换打在注释上就等于没改（见坏样本 2）。
_bad_mig = store_raw.replace(
    "mapOf(GoalTimeline.SINCE_ALWAYS to legacy * 60)",
    "mapOf(DayKey.today() to legacy * 60)",
    1,
)
check("坏样本 5（迁移落在今天）被拦下",
      "GoalTimeline.SINCE_ALWAYS" in store
      and "GoalTimeline.SINCE_ALWAYS" not in code_only(_bad_mig),
      "★ 升级那一刻历史全变、连续天数当场掉 —— 正是用户要求修掉的东西")
check("  · 坏样本 5 的构造本身是有效的",
      _bad_mig != store_raw,
      "替换没生效的话，这条坏样本「通过」是假的")

# 坏样本 6：reset 又把配置一起清了
# ⚠️ 4.0.0：这个坏样本的锚点换过一次 —— 原因是**它自己失效了**。
#    原来锚的是那一整行 `filter { it.startsWith(KEY_SEC) || it.startsWith(KEY_POSTER) }`，
#    而 4.0.0 往里面加了第三个前缀（`KEY_OTHERS`，其它设备的合计）⇒ 字面量不再匹配
#    ⇒ `replace` 静默什么都没做 ⇒ 坏样本变成「恒通过」。
#    ★ 抓到它的不是运气：那条 check 判的是**后置条件**（改坏之后必须判不过），
#      而不是「替换动作有没有发生」；再配一条「构造本身有效」把这一层也钉住。
#    ★★ 教训（本工程记过多次）：**坏样本的锚点一旦绑在「会被合理改动的那一行」上，
#       它就会在下一次改动时静默失效** —— 所以每条坏样本都要带一个「构造有效」的断言。
bad_reset = store_raw.replace(
    "it.startsWith(KEY_SEC) || it.startsWith(KEY_POSTER) || it.startsWith(KEY_OTHERS)",
    "true",
    1,
)
check("坏样本 6（reset 又把配置一起清了）被拦下",
      "startsWith(KEY_SEC)" in store_reset
      and "startsWith(KEY_SEC)" not in block(code_only(bad_reset), "fun reset("),
      "全量删 = 用户的目标悄悄回默认")
check("  · 坏样本 6 的构造本身有效（锚点没被改动静默打断）",
      bad_reset != store_raw,
      "替换没生效的话，这条坏样本「通过」是假的 —— 4.0.0 就是这样断过一次")

# 坏样本 7：把「报后果」那段老逻辑加回来
_bad_old_effect = vm_raw.replace(
    "        _hint.value = store.pendingGoalMinutes()?.let { tomorrow ->",
    "        val before = currentStreak()\n        _poster.value = null\n"
    "        _hint.value = store.pendingGoalMinutes()?.let { tomorrow ->",
    1,
)
check("坏样本 7（「报后果」的老逻辑被加回来）被拦下",
      "currentStreak()" not in vm_set
      and "currentStreak()" in block(code_only(_bad_old_effect), "fun setGoalMinutes("),
      "它不会报错，但它是按旧语义写的 —— 下一轮改动会照着它去理解代码")

# 坏样本 8：两个 key 混成一个
_bad_key = store_raw.replace('private const val KEY_GOAL = "goal:"', 'private const val KEY_GOAL = "goal"', 1)
check("坏样本 8（新键的冒号被抹掉）被拦下",
      'private const val KEY_GOAL = "goal:"' in store
      and 'private const val KEY_GOAL = "goal:"' not in code_only(_bad_key),
      "两个 key 混在一起之后，老值会被当成一条 `day=?` 的条目来解析")

# 坏样本 9：界面把「记录不变」那句话删掉
_bad_copy = screen_raw.replace("以前的打卡记录不变。", "", 1)
check("坏样本 9（「记录不变」那句被删掉）被拦下",
      "以前的打卡记录不变" in goal_block
      and "以前的打卡记录不变" not in block(code_only(_bad_copy), "private fun GoalBlock("),
      "用户最担心的那件事就没有人回答他了")

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
