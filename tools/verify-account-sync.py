#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
账号与打卡同步：**客户端侧**的契约（4.0.0）。

## 与服务端那份脚本的分工

| 脚本 | 守什么 |
| --- | --- |
| `tools/verify-auth-api.py` | 服务端的**行为**：注册/审批/限速/重置/合并（起真 uvicorn，打真 HTTP） |
| **本脚本** | 客户端的**结构与接线**：账号是不是可选、凭证会不会漏出去、同步的触发点齐不齐 |

两边的交集只有一处，而那一处恰恰是这一轮的核心：
「A 听 25 分钟、B 接着听 15 分钟则合计 40」——服务端那侧验的是
**求和**（`daily` 表按设备分行 + `MAX` 幂等），客户端这侧验的是
**反算**（`总量 − 本机 = 其它设备`），中间那一步减法写错就会把本机算两遍。

## 为什么这一份全是「读源码」

因为这些东西的失败形态都是**接线掉了**，而不是逻辑错了：
回调没接上、少一个触发点、多打了一行日志把 token 带出去。
它们的共同点是——**编译通过、界面正常、只有在真机上用上一阵才发现**。
"""
import io
import os
import re
import sys

ROOT = os.path.dirname(os.path.abspath(__file__)) + "/.."
KT = os.path.join(ROOT, "app/src/main/java/com/example/englishcommute")
NGINX = os.path.join(ROOT, "server/nginx")

STORE = os.path.join(KT, "checkin/CheckInStore.kt")
MERGE = os.path.join(KT, "checkin/SyncMerge.kt")
CLOCK = os.path.join(KT, "checkin/ListeningClock.kt")
ACCOUNT = os.path.join(KT, "account/AccountStore.kt")
API = os.path.join(KT, "net/ApiClient.kt")
SYNC = os.path.join(KT, "sync/CheckInSync.kt")
APP = os.path.join(KT, "EnglishCommuteApp.kt")
VM = os.path.join(KT, "ui/AccountViewModel.kt")
PANEL = os.path.join(KT, "ui/AccountPanel.kt")
CVM = os.path.join(KT, "ui/CheckInViewModel.kt")
CONFIG = os.path.join(KT, "net/ServerConfig.kt")

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
    """去掉注释、字符串字面量原样保留。

    ⚠️ 本脚本的说明文字里原样写着 `KEY_PASSWORD`、`token` 这些词，
       不去注释的话，「不许出现」的判据会命中自己的注释 —— 假失败。
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


store_raw, merge_raw = read(STORE), read(MERGE)
clock_raw, account_raw = read(CLOCK), read(ACCOUNT)
api_raw, sync_raw = read(API), read(SYNC)
app_raw, vm_raw, panel_raw, cvm_raw = read(APP), read(VM), read(PANEL), read(CVM)

store, merge = code_only(store_raw), code_only(merge_raw)
clock, account = code_only(clock_raw), code_only(account_raw)
api, sync = code_only(api_raw), code_only(sync_raw)
app, vm, panel, cvm = code_only(app_raw), code_only(vm_raw), code_only(panel_raw), code_only(cvm_raw)

# ===========================================================================
section("一、账号必须是**可选增强**，不是门禁")
# ===========================================================================

check("★ 未登录时同步直接返回（一次网络都不发）",
      "if (!account.signedIn.first())" in sync,
      "账号是可选增强：服务器是一台 2 核 2G 的机器，"
      "不能让「听」这件核心的事依赖它")

check("★ 未登录时打卡页**一个字都不显示**（不许劝登录）",
      "if (!ui.signedIn) return" in panel,
      "打卡页是用户来听东西的地方。未登录不是错误状态，"
      "在那里反复提醒只会让「不登录」显得像没做完的事")

check("  · 账号块第一句就写清「不登录也能正常用」",
      "不登录也能正常用" in panel)

check("★ 合并规则只有一份，在纯函数里",
      "SyncMerge.merge(_mine.value, _others.value)" in store
      and "object SyncMerge" in merge,
      "写在 CheckInStore 里就没有单测了 —— 而这条运算正是"
      "「多设备能不能接得上」的全部逻辑（SyncMergeTest 13 项）")

check("  · 而且 CheckInStore 里没有第二处自己相加",
      "out[day] = sec + (others[day] ?: 0)" not in store)

check("★★ 累加的起点取的是**本机那一列**，不是合并值",
      "mineNow = _mine.value[day] ?: 0" in store
      and "SyncMerge.accumulate(" in store
      and "val before = secondsOn(day)" not in store,
      "★★ 4.1.1 修的那个 bug：起点取 `secondsOn(day)`（合并值）而终点写进 mine ⇒ "
      "每累加一次就把别的设备那部分并进本机一次 ⇒ 数字爆炸式增长"
      "（录屏实测 8 秒内 617→667 分钟，活了两版）")

# 坏样本：起点改回合并值（就是 4.0.0 的写法）
bad_accum = store_raw.replace("mineNow = _mine.value[day] ?: 0", "mineNow = secondsOn(day)", 1)
check("  · 坏样本（起点改回合并值 = 4.0.0 的写法）被拦下",
      bad_accum != store_raw
      and "mineNow = _mine.value[day] ?: 0" not in code_only(bad_accum),
      "★ 这条坏样本就是那个 bug 的原文 —— 有了它，下一轮谁再写成这样都会被拦住")

check("★ 反算用减法（总量含本机），不是把总量直接当 others",
      "SyncMerge.othersFrom(totals, mineNow)" in store
      and "total - (mine[day] ?: 0)" in merge,
      "★ 直接当 others 会把本机算两遍：症状是「数字莫名其妙变大」，"
      "而它看起来像「终于同步上了」")

check("  · 反算结果 clamp 到 0（另一台清了记录时不能出负数）",
      "if (other > 0) out[day] = other" in merge)

# ===========================================================================
section("二、凭证不进日志、不落盘")
# ===========================================================================

# 只看**代码**里有没有把敏感量喂给日志的调用。
#
# ⚠️ 判据要精确到「**注入了什么**」，不能写成「日志里出现了 json 这个词」——
#    第一版就是这么写的，结果 `DiagLog.act("…（${res.json.optInt("removed")} 行）")`
#    被误判成泄露（它打印的是一个删除行数）。判据落在名字上就会这样。
#    真正要拦的是两种：把**请求体 / 令牌 / 密码 / 请求头**这一类变量插进日志，
#    以及把整份响应对象 dump 出来。
LEAK = re.compile(
    r"DiagLog\.\w+\([^)]*(\$\{?\s*(body|payload|token|password|authorization)\b"
    r"|json\.toString\(\)|\.headers\b)",
    re.I)
for path, body, label in (
    (API, api, "ApiClient"),
    (SYNC, sync, "CheckInSync"),
    (ACCOUNT, account, "AccountStore"),
    (VM, vm, "AccountViewModel"),
):
    hits = LEAK.findall(body)
    check("★ %s 里没有把凭证/请求体交给日志" % label, not hits,
          "诊断日志是**要发出来给人看**的东西 —— 凭证混进去就是一次泄露。"
          "命中：%s" % hits)

check("★ ApiClient 的日志只描述「方法 + 路径 + 状态码」",
      'val described = "$method /api$path"' in api
      and "DiagLog" in api,
      "它连请求体都不持有 —— 这是刻意的")

check("★ 账号存储里**没有**密码字段（只有名字/令牌/设备/时间/脏标记）",
      "KEY_PASSWORD" not in account and "password" not in account.lower().replace(
          "passwordvisualtransformation", ""),
      "密码只在登录那一次请求里停留，之后再也用不到（要用到的是令牌）")

check("  · 密码输入框走 PasswordVisualTransformation",
      "PasswordVisualTransformation" in code_only(
          read(os.path.join(KT, "ui/theme/Parts.kt"))),
      "⚠️ 第一版忘了 read() —— 把**路径字符串**当成源码传给了 code_only，"
      "于是这条永远是假失败（而且看不出错在哪）")

check("★ 令牌走 Bearer 请求头，**不放进查询串**",
      '"Authorization"' in api and '"Bearer " + token' in api and "token=" not in api,
      "★ 查询串会被 nginx 原样写进 access log —— 令牌放那里等于"
      "在服务器上留一份明文凭证。请求头不进日志（access log 只记路径）")
check("  · 令牌只在账号存储里落盘，别处没有第二份",
      account.count("KEY_TOKEN") >= 1 and "account_token" in account
      and "token" not in cvm.lower())

# ===========================================================================
section("三、同步的触发点齐了（少一个 = A 听了 B 看不到）")
# ===========================================================================

check("★ 收听计时器每次落盘后通知同步（边听边传）",
      "runCatching { onTicked() }" in clock,
      "★ 这一条是多设备能不能真的「接得上」的关键："
      "只在「回到前台」同步的话，A 听完直接锁屏去开 B，数据还没上去")

check("  · 安装点把回调接到了 onLocalChange",
      "checkInSync.onLocalChange()" in app)

check("  · 回到前台也同步一次",
      "checkInSync.maybeSync()" in app)

check("  · 用户能手动「立即同步」",
      "fun syncNow()" in vm and "sync(force = true)" in vm)

check("★ 自动同步有节流（不能每 5 秒打一次服务器）",
      "minIntervalMs" in sync and "now - lastAttemptAt < minIntervalMs" in sync)

check("  · 而且同一时刻只允许一次（计时器那条路会频繁触发）",
      "running.compareAndSet(false, true)" in sync)

check("  · 失败要能说出下一步（透出服务端的文案）",
      "res.message" in sync and "_status.value.copy" in sync,
      "「同步失败」这四个字不回答「我接下来做什么」")

# ===========================================================================
section("四、清空记录必须连云端一起清")
# ===========================================================================

check("★ 清空记录会调服务端（否则一同步又回来了）",
      "container.checkInSync.wipeRemote()" in cvm,
      "只清本地的话，下一次同步把服务端那几行原样搬回来 —— "
      "用户看到的是「清空之后过一会儿又自己回来了」")

check("  · 本地清空连 others 一起清",
      "it.startsWith(KEY_OTHERS)" in store and "it.startsWith(KEY_SEC)" in store,
      "只清 mine 的话，界面上会继续显示「其它设备那 15 分钟」")

check("  · 云端清不掉时**说出来**，不静默",
      "云端没清掉" in cvm,
      "静默的话用户以为清干净了，而它下次同步还会回来")

check("  · 登录时确认文案改成「所有设备」",
      "所有设备" in code_only(read(os.path.join(KT, "ui/CheckInScreen.kt"))))

# ===========================================================================
section("五、接口只走 https，且路径形状统一")
# ===========================================================================

check("★ BASE_URL 是 https（凭证不能走明文）",
      'https://' in read(CONFIG),
      "3.1.0 起内容已经全走 HTTPS；账号令牌更不能例外")

routes = read(os.path.join(NGINX, "ec-routes.conf"))
check("★ 反代不在共享的 80 路由片段里（那会被两个明文入口 include）",
      "/api/" not in routes)

check("  · ApiClient 统一拼 `/api` 前缀",
      '"$base/api$path"' in api)

check("★ 只有 ApiClient 直接用 OkHttp（凭证不会散在多个调用点）",
      "OkHttpClient" in api
      and not any("OkHttpClient" in t for t in (store, sync, vm, panel, account)),
      "凭证一旦散在多个调用点，就会出现「某一处忘了带 token」或"
      "「某一处把它打进了日志」—— 前者是故障、后者是事故，都不报错")
check("  · 调用点都走 api.post（登录/注册/忘记/重置/改密/登出/同步）",
      (sync + vm).count("api.post(") >= 6, "实际 %d 处" % (sync + vm).count("api.post("))

# ===========================================================================
section("六、坏样本（每条关键判据都要能失败）")
# ===========================================================================


def still(needle, text):
    return needle in text


# ① 反算那一步减法被删掉
bad_sub = merge_raw.replace("val other = total - (mine[day] ?: 0)", "val other = total", 1)
check("坏样本 1（反算不再减本机）被拦下",
      bad_sub != merge_raw
      and "total - (mine[day] ?: 0)" not in code_only(bad_sub),
      "★ 这就是「本机算两遍」那个错法")

# ② 未登录也发请求
bad_gate = sync_raw.replace("if (!account.signedIn.first()) {", "if (false) {", 1)
check("坏样本 2（未登录也去同步）被拦下",
      bad_gate != sync_raw
      and "if (!account.signedIn.first())" not in code_only(bad_gate))

# ③ 凭证进日志
bad_log = api_raw.replace("DiagLog.i(\"Api\", \"$described → ${resp.code}\")",
                          "DiagLog.i(\"Api\", \"$described → $body\")", 1)
check("坏样本 3（把请求体打进日志）被拦下",
      bad_log != api_raw and not LEAK.findall(code_only(bad_log)) == []
      and LEAK.findall(code_only(bad_log)),
      "★ 判据就是那条正则：它必须能抓到 $body")

# ④ 落盘里多一个密码字段
bad_key = account_raw.replace("private val KEY_DEVICE = stringPreferencesKey(\"account_device\")",
                              "private val KEY_DEVICE = stringPreferencesKey(\"account_device\")\n"
                              "        private val KEY_PASSWORD = stringPreferencesKey(\"pw\")", 1)
check("坏样本 4（账号存储里加一个密码字段）被拦下",
      bad_key != account_raw and "KEY_PASSWORD" in code_only(bad_key))

# ⑤ 计时器那条触发点被删
bad_clock = clock_raw.replace("        runCatching { onTicked() }\n", "", 1)
check("坏样本 5（计时器不再通知同步）被拦下",
      bad_clock != clock_raw and "runCatching { onTicked() }" not in code_only(bad_clock),
      "★ 删掉它之后，界面一切正常、只有「A 听完切到 B 看不到进度」")

# ⑥ 清空不连云端
bad_wipe = cvm_raw.replace("container.checkInSync.wipeRemote()", "true", 1)
check("坏样本 6（清空不通知云端）被拦下",
      bad_wipe != cvm_raw and "container.checkInSync.wipeRemote()" not in code_only(bad_wipe))

# ⑦ 本地清空漏掉 others
bad_reset = store_raw.replace("it.startsWith(KEY_SEC) || it.startsWith(KEY_POSTER) || it.startsWith(KEY_OTHERS)",
                              "it.startsWith(KEY_SEC) || it.startsWith(KEY_POSTER)", 1)
check("坏样本 7（本地清空漏掉 others）被拦下",
      bad_reset != store_raw and "it.startsWith(KEY_OTHERS)" not in code_only(bad_reset))

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
