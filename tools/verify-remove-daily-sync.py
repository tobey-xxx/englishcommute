#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
拆掉「每日自动更新」的契约（3.5.0 新建）。

## 为什么拆一个功能也要有守门人

因为**「拆掉」这件事的失败形态全是静默的**，而且它们分两类，方向正好相反：

### ① 拆得不干净（还留着一条能跑的路）

- 代码删了、某个调用点没删 → 编译期就能发现，属于好运气
- 叫 `DailySync` 的东西还在别处被引用 / 镜像脚本还在验一个已经不存在的函数
  → **编译通过、脚本全绿**，只有人来读代码才发现
- 产物里还留着那两个类 → 跑起来完全正常（少一条定时任务而已）

### ② 拆过了头（把不该拆的一起拆了）

这一类更贵，因为它**不是「少了个功能」，而是「多了一件用户没要求的事」**：

| 跟着一起被拆的东西 | 后果 |
|---|---|
| 系统里那条**已经登记出去**的周期任务 | 系统按点唤醒一个不存在的类，失败得悄无声息 |
| **共用的那个开关** | 曾经关掉后台干活的人被静默地重新打开，而且再也找不到关的地方 |
| `RECEIVE_BOOT_COMPLETED` 权限 | 重启之后 `SubtitleWorker` 不再被恢复 —— 字幕得等用户打开 App 才加载 |
| `testdata/verify_listening.py` 里那份 `parseTime` 镜像 | 一个每天都在跑、每天都「通过」、而它验的函数已经不在的脚本 |

★ 所以这个脚本的判据分三节：**该没的没了 / 该在的还在 / 撤销本身是可判断的**。
第二节和第三节才是重点 —— 第一节靠编译器也能过一半。

## 拆一个后台任务时最反直觉的一条

**删掉一个 Worker 类 ≠ 删掉那个任务。** WorkManager 的任务活在系统的库里，
上一版登记出去的那条记录不会因为少了一个 `.kt` 文件而消失。
唯一能撤掉它的是**知道它名字的代码** —— 也就是 `sync/RetiredWork`。
这也是为什么它必须**每次启动都跑、而且必须无条件跑**（跟开关无关）。
"""
import io
import os
import re
import sys

ROOT = os.path.dirname(os.path.abspath(__file__)) + "/.."
KT = os.path.join(ROOT, "app/src/main/java/com/example/englishcommute")
KT_TEST = os.path.join(ROOT, "app/src/test/java/com/example/englishcommute")

APP = os.path.join(KT, "EnglishCommuteApp.kt")
SETTINGS = os.path.join(KT, "settings/AppSettings.kt")
SCREEN = os.path.join(KT, "ui/SettingsScreen.kt")
SVC = os.path.join(KT, "sync/ContentSyncService.kt")
SUB = os.path.join(KT, "sync/SubtitleWorker.kt")
RETIRED = os.path.join(KT, "sync/RetiredWork.kt")
OLD_WORKER = os.path.join(KT, "sync/DailySyncWorker.kt")
MANIFEST = os.path.join(ROOT, "app/src/main/AndroidManifest.xml")
BUILD = os.path.join(ROOT, "build-apk.sh")
MIRROR = os.path.join(ROOT, "testdata/verify_listening.py")

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
    return io.open(p, encoding="utf-8").read() if os.path.isfile(p) else ""


def code_only(src):
    """去掉注释、**字符串字面量原样保留**。

    ⚠️ 本脚本的说明文字里原样写着 `DailySyncWorker` / `dailySyncEnabled` 这些名字，
       不去注释的话，每条「不许再出现」都会命中自己的说明 —— 那是假失败。
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


def sh_code_only(src):
    """去掉 shell 注释（`#` 到行尾；那段内嵌 Python 的注释也一起去掉）。

    ⚠️ 这次要防的正是「**判据匹配到注释**」：我自己在 `targets` 的注释里
       写了那个已删的类名（"它进了下面的 gone 清单"），不去注释的话，
       「targets 里不许再有它」这条会直接假失败 ——
       而假失败会让人把断言删掉，比漏报更麻烦。
       本工程在 `verify-vocab-add.py` 与 `verify-motion-129.py` 上各踩过一次。
    """
    out = []
    for line in src.split("\n"):
        i = line.find("#")
        out.append(line if i < 0 else line[:i])
    return "\n".join(out)


def dangling_mentions(overrides=None):
    """注释里提到已删类名、却**没有标明「这是历史」**的地方。

    ⚠️ 判据刻意不落在「有没有出现这个名字」上：`RetiredWork` 必须能指名道姓
       说出它要撤的是哪个类的工作，`SubtitleWorker` 那段「和那个用 UPDATE 的
       调度器相反」也在解释自己为什么这么选 —— 这两处都是**有价值的历史陈述**。
       真正要防的是**指向空气的引用**：提到一个已经不存在的类，却没有任何
       「拆掉了 / 退役了」的标记 —— 读者会以为它还在某处活着。

       这就是「判据落在事实上，而不是落在代理量上」的一个小例子：
       代理量是「这个名字出现了几次」，事实是「这条引用有没有交代自己的处境」。
    """
    overrides = overrides or {}
    bad = []
    for p in all_kotlin():
        text = overrides.get(p, read(p))
        for i, line in enumerate(text.split("\n"), 1):
            if "DailySyncWorker" in line or "DailySyncScheduler" in line:
                if not any(m in line for m in ("3.5.0", "已拆掉", "已删除", "退役")):
                    bad.append("%s:%d" % (os.path.relpath(p, ROOT), i))
    return bad


def all_kotlin():
    """全工程的 Kotlin 源码（含单测）。**只扫 .kt** ——

    `build-apk.sh` 的 gone 清单与各版本清单里**故意**写着这两个类名，
    扫进去会自相矛盾。判据要落在「还有没有人引用它」上，而不是「还有没有出现过这个名字」。
    """
    files = []
    for base in (KT, KT_TEST):
        for dp, _, fs in os.walk(base):
            for f in fs:
                if f.endswith(".kt"):
                    files.append(os.path.join(dp, f))
    return files


app = code_only(read(APP))
settings = code_only(read(SETTINGS))
screen = code_only(read(SCREEN))
svc = code_only(read(SVC))
sub = code_only(read(SUB))
retired_raw = read(RETIRED)
retired = code_only(retired_raw)
build = read(BUILD)
manifest = read(MANIFEST)
mirror = read(MIRROR)

# ===========================================================================
section("一、该没的：那条 24 小时的任务真的没了")
# ===========================================================================

check("★ 那个 Worker 的源文件已经不在了",
      not os.path.isfile(OLD_WORKER),
      "文件还在的话，等于什么都没拆 —— 而它跑起来毫无症状")

refs = []
for p in all_kotlin():
    body = code_only(read(p))
    for name in ("DailySyncWorker", "DailySyncScheduler"):
        if name in body:
            refs.append("%s → %s" % (os.path.relpath(p, ROOT), name))
check("★ 全工程不再引用这两个类（编译期也能抓到一半）",
      not refs, "仍在引用：%s" % refs[:4])

check("  · 设置页不再有那一块（含它的触发时刻档位）",
      "DailySyncBlock" not in screen
      and "每日自动更新" not in screen
      and "触发时刻" not in screen)

check("  · AppSettings 里它专有的那套工具也一起拆了",
      "dailySyncTime" not in settings
      and "SYNC_TIME_STEPS" not in settings
      and "DEFAULT_SYNC_TIME" not in settings
      and "fun parseTime(" not in settings,
      "只删功能、留着它专有的常量与解析函数，下一个人会以为「时刻」这件事还有人管")

check("  · ★ 那份镜像脚本也跟着拆了（它验的函数已经不存在）",
      "def parse_time(" not in mirror and "test_parse_time" not in mirror,
      "★ 它每天都会跑、每天都报「通过」，而它验的东西在 Kotlin 侧已经没了 —— "
      "「绿着的假脚本」比没有脚本更糟：它让人以为这条路径还有人管。"
      "⚠️ 判据取的是**函数与调用点**，不是「文件里有没有 parseTime 这个词」——"
      "那份脚本的头部现在**故意**解释着「parseTime 已拆掉」，"
      "搜名字会把这段说明也算成违规（判据落在名字上就会这样）")

check("  · 构建清单把它移出了 targets、放进了 gone",
      "'DailySyncWorker'" not in sh_code_only(build).split("gone = [")[0]
      and "com/example/englishcommute/sync/DailySyncWorker" in sh_code_only(build).split("gone = [")[1]
      and "com/example/englishcommute/sync/DailySyncScheduler" in sh_code_only(build).split("gone = [")[1],
      "「拆掉了」这件事的失败形态是**产物里还留着**：跑起来完全正常，"
      "只有电池统计里多出一些说不清的唤醒")

# ===========================================================================
section("二、该在的还在：三样东西不许跟着一起拆")
# ===========================================================================
# 这一节才是重点。上面那些靠编译器能过一半，下面这三条只能靠人想清楚。

check("★ 撤撤销代码在（它是老机器上那条任务的唯一出口）",
      "object RetiredWork" in retired
      and 'private const val DAILY_CONTENT_SYNC = "daily_content_sync"' in retired,
      "★ WorkManager 的任务活在系统的库里：删掉类不会删掉那条记录。"
      "少了它，系统会按点唤醒一个不存在的类")

check("★ 而且它每次启动都被调用、且无条件执行（在开关之前）",
      "RetiredWork.cancelAll(this@EnglishCommuteApp)" in app
      and app.index("RetiredWork.cancelAll(") < app.index("backgroundWorkEnabled.first()"),
      "★ 放在读开关之后的话，用户一旦关过后台同步，那条旧任务就永远留在系统里 —— "
      "这恰好是最需要撤掉它的人")

check("★ 共用的那个开关还在（它现在还管着字幕）",
      "val backgroundWorkEnabled: Flow<Boolean>" in settings
      and "fun setBackgroundWorkEnabled(" in settings
      and "SubtitleScheduler.apply(this@EnglishCommuteApp, enabled)" in app,
      "★ 删了它，曾经关掉过的人会被静默地重新打开，而且再也找不到关的地方")

check("★ 磁盘上的 key **没改**",
      'booleanPreferencesKey("daily_sync_enabled")' in settings,
      "★ 换 key = 老用户存下的 false 读不出来、静默回到默认的 true。"
      "为名字好看去丢用户设置是不划算的 —— 这条纪律 AppSettings 里写过两遍")

check("  · 服务里那个接力循环也还读它（语义没变）",
      "app.container.settings.backgroundWorkEnabled.first()" in svc,
      "用户关掉后台干活时，服务里那个「接着加载字幕」的循环也必须停")

check("  · ★ 开机自启权限还在（旧注释只写着「每日自动更新」）",
      "RECEIVE_BOOT_COMPLETED" in manifest,
      "★ 它服务的还有 SubtitleWorker：没有它，重启之后周期任务不会恢复，"
      "而要等到用户主动打开 App —— 那正是这个 Worker 想避免的事。"
      "旧注释只指着已拆掉的功能，最容易让人顺手把权限一起摘掉")

check("  · 注释更新了（不许出现「指向已删功能」的权限说明）",
      "SubtitleWorker" in manifest)

check("  · 注释里提到它时必须标明「这是历史」（不许指向空气）",
      dangling_mentions() == [],
      "提到一个已经不存在的类、却不说它已经拆掉 —— 读者会以为它还在某处活着。"
      "实测越界：%s" % dangling_mentions())

# ===========================================================================
section("三、撤销本身必须可判断（先查再撤、只为真残留留痕）")
# ===========================================================================
# 「每次启动都撤 + 打一行『已撤销』」看起来更保险，实际更糟：
# 那行日志会**永远出现**，于是它证明不了任何事，还会把真信号淹掉。

cancel = retired[retired.find("suspend fun cancelAll("):]
check("★ 先问「它在不在」，而不是无脑撤",
      "getWorkInfosForUniqueWorkFlow(name).first()" in cancel,
      "★ 判据落在事实上：日志只在**真的撤掉了东西**时出现 —— "
      "它一出现就等于「这台机器是升级上来的，而且那一刻确实还挂着一条」")

check("  · 干净时什么都不做（全新安装走的就是这一支）",
      "if (present != null && present.isEmpty()) continue" in cancel)

check("  · ★ 查不出来的时候**照样撤**",
      "present != null" in cancel and cancel.count("cancelUniqueWork(name)") == 1,
      "★ 缺信息时的默认方向应该是「把事做完」：撤一个不存在的东西是无害的，"
      "漏撤会留下一条永远失败的周期任务（同一个口径见服务的电量判断）")

check("  · 撤的动作在日志之前（日志读的是撤之前查到的数）",
      cancel.index("cancelUniqueWork(name)") < cancel.index("DiagLog.i"))

check("  · 两条日志分开：查得到才敢报条数",
      "DiagLog.i(\"Work\"" in cancel and "DiagLog.w(\"Work\"" in cancel,
      "查不出来那次只能说「按有残留处理」—— 报「撤掉了 N 条」是我们没验证过的话")

# ===========================================================================
section("四、界面：开关留着，但文案换成它现在真正管的事")
# ===========================================================================

check("★ 设置页那块改名成「后台加载字幕」",
      "private fun BackgroundSubtitleBlock(" in screen
      and 'title = "后台加载字幕"' in screen,
      "★ 开关不能删，但它的名字必须跟着改 —— 留着「每日自动更新」就是一句假话")

check("  · 文案说清它是「趁你没打开 App」",
      "趁你没打开 App" in screen,
      "那是这件工作的全部意义：打开就有字幕 vs 打开才开始等")

check("  · ★ 关掉时说清**失去什么**，而不是只说「已关闭」",
      "字幕只在你打开 App 时加载。" in screen,
      "「已关闭」不回答「那我失去了什么」—— 用户没法据此判断要不要关")

check("  · 没被术语表禁掉的词（「补字幕」一类）",
      "补字幕" not in screen,
      "术语表：用户不知道「缺过什么」，一律用「加载」。"
      "（这条由 verify-copy-audit.py 兜底，这里只是让它在最显眼处早一点红）")

check("  · 没有留下空的块（区块数没变少）",
      screen.count("@Composable\nprivate fun ") >= 8,
      "拆掉一块之后最容易留下的形态是「标题还在、里面什么都没有」")

# ===========================================================================
section("五、坏样本（每条关键判据都要能失败）")
# ===========================================================================

BAD_1 = read(APP).replace("                RetiredWork.cancelAll(this@EnglishCommuteApp)\n", "", 1)
check("坏样本 1（启动时不再撤销退役任务）被拦下",
      BAD_1 != read(APP)
      and "RetiredWork.cancelAll(this@EnglishCommuteApp)" not in code_only(BAD_1),
      "★ 系统里那条任务会一直留着，按点唤醒一个不存在的类")

BAD_2 = read(RETIRED).replace(
    "            if (present != null && present.isEmpty()) continue\n", "", 1)
check("坏样本 2（改成无脑撤、每次启动都留一行日志）被拦下",
      BAD_2 != read(RETIRED)
      and "if (present != null && present.isEmpty()) continue" not in code_only(BAD_2),
      "那行日志会永远出现 ⇒ 它证明不了任何事，还会把真信号淹掉")

BAD_3 = read(SETTINGS).replace(
    'booleanPreferencesKey("daily_sync_enabled")',
    'booleanPreferencesKey("background_work_enabled")', 1)
check("坏样本 3（顺手把磁盘上的 key 改名）被拦下",
      BAD_3 != read(SETTINGS)
      and 'booleanPreferencesKey("daily_sync_enabled")' not in code_only(BAD_3),
      "★ 老用户存下的 false 读不出来、静默回到默认的 true —— "
      "改名的收益只是名字好看，代价是丢用户设置")

BAD_4 = read(MANIFEST).replace("RECEIVE_BOOT_COMPLETED", "NOT_DECLARED", 1)
check("坏样本 4（把开机自启权限一起摘掉）被拦下",
      "RECEIVE_BOOT_COMPLETED" not in BAD_4,
      "重启之后 SubtitleWorker 不再被恢复 —— 而它想避免的正是「等到你打开 App」")

BAD_5 = read(SCREEN).replace('"字幕只在你打开 App 时加载。"', '"已关闭。"', 1)
check("坏样本 5（关掉时只说「已关闭」）被拦下",
      BAD_5 != read(SCREEN)
      and "字幕只在你打开 App 时加载。" not in code_only(BAD_5),
      "不回答「那我失去了什么」")

BAD_6 = read(APP).replace(
    "SubtitleScheduler.apply(this@EnglishCommuteApp, enabled)",
    "SubtitleScheduler.apply(this@EnglishCommuteApp, true)", 1)
check("坏样本 6（字幕任务不再受开关控制）被拦下",
      BAD_6 != read(APP)
      and "SubtitleScheduler.apply(this@EnglishCommuteApp, enabled)" not in code_only(BAD_6),
      "开关就成了摆设：界面上关掉了，后台照样跑")

BAD_7 = read(RETIRED).replace("试图实例化**已拆掉的** DailySyncWorker", "试图实例化 DailySyncWorker", 1)
check("坏样本 7（历史陈述不标明自己是历史）被拦下",
      BAD_7 != read(RETIRED)
      and dangling_mentions() == []
      and dangling_mentions({RETIRED: BAD_7}) != [],
      "提到已删的类却不说它已拆掉 —— 读者会以为它还在")

check("  · 自测：文件被加回来时第一条会立刻红",
      not os.path.isfile(OLD_WORKER))

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
