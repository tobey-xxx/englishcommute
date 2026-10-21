#!/usr/bin/env python3
"""
界面文案审查：**用户能看到的每一句话里，不许出现开发者才懂的东西**。

## 为什么必须验它

这条需求的原文是：「app 里所有的文字提醒，你检查一遍，很多地方是开发者提示词，
比如什么在本机完成等等……不要把开发者逻辑的信息放到用户 app 中」。

它的失败形态是**最隐蔽的一类**：不会崩、不会报错、功能也完全正常 ——
只是用户读到一句「拉取、下载、转写全部在本机完成」，心里想的是
「所以呢？我要做什么？」。这类问题**没有测试能发现**，只能一条一条读。

而它又极易复发：下次加功能时，顺手写下「端侧识别中」是最自然的写法 ——
因为在开发者的脑子里，那个东西就叫「端侧识别」。

所以这里把它变成一个可以机械执行的判据：**黑名单上的词，一个都不许出现**。

## 判定范围（本脚本最需要小心的一段）

难点全在「哪些字面量算用户可见」。做窄了会漏（`friendlyError` 里那句
`"语音功能暂时不可用…"` 就是光秃秃一行，没有任何 `Text(` 可抓）；
做宽了会误报（`DiagLog.i("Deploy", "首次部署 ▶")` 里的词**本来就该是工程词**，
误报会让人干脆关掉检查器 —— 那比没有检查器更糟）。

办法是**算出所有「日志/断言调用」在源码里覆盖的区间**，落在区间里的字面量一律豁免：

  1. 找到名字属于 `NON_UI_CALLS`（或形如 `XxxException` / `XxxError`）的调用，
     从它的 `(` 配到对应的 `)`；紧跟其后的**尾随 lambda**（`require(x) { "…" }`
     里的那句话在花括号里，不在括号里）也要一起圈进去。
  2. 括号配对必须**跳过字符串内部**，否则一句 `"(见下文)"` 就能把配对带偏。
  3. `const val NAME = "…"` 这类全大写常量豁免 —— 它们是标识符，不是文案。
  4. 赋值给 `NON_UI_TARGETS` 里那些字段的（如 `lastError[…] = "解码失败"`）豁免。

⚠️ **行号必须对着原始文件**。第一版我拿「剥完注释的文本」数行号，报告里指到的行是错的
   —— 一份指错位置的报告比没有报告更费时间。所以块注释替换成等量换行，而不是直接删掉。

⚠️ 第一版还有另一个错：判断「这个字面量属于哪次调用」时只取 `(` 前标识符的**第一段**，
   于是 `sp.done(...)` 被认成 `sp`、`DiagLog.i(...)` 被认成 `DiagLog` 碰巧对了。
   改成**逐段比对**（`sp.done` → `{sp, done}`，命中 `done`）。

## 反向对照

末尾拿**故意写坏**的样本跑一遍，断言它确实被拦下来 ——
否则前面那些 ✓ 可能只是「黑名单根本没生效」。

用法：
    python3 tools/verify-copy-audit.py
"""

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
KT = ROOT / "app/src/main/java/com/example/englishcommute"


# ---------------------------------------------------------------------------
# 黑名单：开发者话术
# ---------------------------------------------------------------------------
#
# 收录标准：**这句话对用户没有任何可执行的含义**。
# 例如「在本机完成」—— 用户关心的是「要不要联网、会不会上传」，
# 不关心这件事发生在哪个进程里。
# 反过来「离线」「不需要联网」是用户语言，可以用。

BLACKLIST = [
    # 实现视角的「在哪儿跑的」
    "本机完成", "本机生成", "本机识别", "本机缓存", "在本机",
    "端侧", "本地模型",
    # 组件与内部结构
    "原生库", "引擎", "流水线", "队列", "进程", "线程", "内核",
    "播放服务", "解析器", "回调",
    # 部署与生命周期
    "部署", "就位",
    # 额度 / 调度这类内部策略
    "额度", "配额",
    # 音频处理链路
    "解码", "重采样", "采样率", "分块", "切块",
    # 模型工程参数
    "int8", "onnx", "mmap", "量化", "权重", "词表", "token", "Token",
    # 打包 / 分发的实现细节
    "翻译模型", "随安装包自带", "私有目录", "assets",
    # 其它
    "断点续传", "错误码", "异常栈",
]

# ---------------------------------------------------------------------------
# 整包 / 整文件豁免（**口子**，所以每一条都要写理由，且硬性限制条数）
# ---------------------------------------------------------------------------

EXEMPT_FILES = {
    "diag/": "整个诊断包。它存在的意义就是给排障用的：页大小、ABI、核数、"
             "原生库预检结果 —— 翻译成人话之后反而读不出问题。",
    "ui/DiagScreen.kt": "诊断日志页。用户点进来就是为了看那些原始行，"
                        "把它改写成人话等于把日志废掉。",
}

# 落在这些调用里的字面量 = 日志 / 断言，不是界面文案。
# 逐段比对，所以 `sp.done(...)` / `DiagLog.i(...)` / `total.fail(...)` 都能命中。
NON_UI_CALLS = {
    "DiagLog", "Log", "println", "print",
    "span", "done", "fail",     # 日志计时与转写批次汇总
    "require", "check", "error", "requireNotNull", "checkNotNull",
    "append",                   # 诊断快照的字符串拼接
}

# 赋值给这些目标的字面量 = 内部错误记录，不是界面文案
NON_UI_TARGETS = {"lastError"}

# 全大写常量名豁免：`const val DIR_NAME = "parakeet-…"` 是标识符
CONST_RE = re.compile(r"\b(?:private\s+|internal\s+)?const\s+val\s+[A-Z0-9_]+\s*=\s*$")

# ---------------------------------------------------------------------------
# 白名单：确实会命中、但**在这个位置是对的**
# ---------------------------------------------------------------------------
#
# key = (相对 KT 的路径, 命中的词)
# 每条都必须解释「为什么这里可以」。解释写不出来，就说明它该被改掉。

ALLOWED = {
    # ⌨️ 「落盘的键名」不是文案 —— 用户永远看不见 `account_token` 这个字符串。
    #    它一旦发布就**冻结**：改它 = 已登录的设备读不到自己的令牌，
    #    而症状是「一重启就要求重新登录」，看起来像会话过期，不像改名事故。
    #    （4.0.0 引入。另外两处 token 字面量已经改掉了：
    #     HTTP 头用字符串拼接、JSON 字段名抽成了 const val ——
    #     能避开的就不留在这里，免得白名单越攒越多没人再读。）
    ("account/AccountStore.kt", "token"): "落盘的偏好键名，不是界面文案",
}


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


# ---------------------------------------------------------------------------
# 源码扫描的小工具
# ---------------------------------------------------------------------------

def strip_comments(src: str) -> str:
    """
    去掉 `//` 与 `/* */`，但**保持行号不变**（块注释换成等量换行）。
    行号错了，那份报告就没用了。
    """
    src = re.sub(r"/\*.*?\*/", lambda m: "\n" * m.group(0).count("\n"), src,
                 flags=re.S)
    src = re.sub(r"//[^\n]*", "", src)
    return src


def _match(src: str, start: int, open_ch: str, close_ch: str) -> int:
    """
    从 `start`（open_ch 的位置）配到对应的 close_ch，返回其下标；配不上返回 -1。

    **必须跳过字符串内部**：一句 `"(见下文)"` 里的括号会把配对带偏，
    而带偏的结果是「豁免区间多圈一段」——那意味着漏检，而且看不出为什么。
    """
    depth = 0
    i = start
    in_str = False
    while i < len(src):
        c = src[i]
        if in_str:
            if c == "\\":
                i += 2
                continue
            if c == '"':
                in_str = False
        elif c == '"':
            in_str = True
        elif c == open_ch:
            depth += 1
        elif c == close_ch:
            depth -= 1
            if depth == 0:
                return i
        i += 1
    return -1


CALL_RE = re.compile(r"([A-Za-z_][\w.]*)\s*\(")


def non_ui_spans(src: str):
    """所有「落在日志 / 断言调用里」的区间 [start, end)。"""
    spans = []
    for m in CALL_RE.finditer(src):
        parts = m.group(1).split(".")
        if not (any(p in NON_UI_CALLS for p in parts)
                or parts[-1].endswith("Exception")
                or parts[-1].endswith("Error")):
            continue
        open_i = m.end() - 1
        close_i = _match(src, open_i, "(", ")")
        if close_i < 0:
            continue
        end = close_i + 1
        # 尾随 lambda：`require(x) { "这句话在花括号里" }`
        j = end
        while j < len(src) and src[j] in " \t":
            j += 1
        if j < len(src) and src[j] == "{":
            b = _match(src, j, "{", "}")
            if b > 0:
                end = b + 1
        spans.append((m.start(), end))
    spans.sort()
    return spans


def in_spans(i: int, spans) -> bool:
    # spans 已排序，但区间可能嵌套，二分容易写错 —— 数量是几百级，线性扫足够
    for s, e in spans:
        if s <= i < e:
            return True
        if s > i:
            break
    return False


LIT = re.compile(r'"(?:[^"\\\n]|\\.)*"')


def candidates(rel: str, src: str):
    """
    **用户可能看到的**字面量：(行号, 原文, 所属语句前缀)。

    这是整个脚本的核心 —— `audit` 与「点名的句子已消失」都走它。
    两处若各写一份筛选逻辑，迟早会出现「审查说没有、消失检查说有」这种自相矛盾，
    而那种矛盾最难查：两边各自都算「对」，只是算的不是同一件事。
    """
    clean = strip_comments(src)
    spans = non_ui_spans(clean)
    line_starts = [0]
    for m in re.finditer(r"\n", clean):
        line_starts.append(m.end())

    out = []
    for m in LIT.finditer(clean):
        body = m.group(0)[1:-1]
        if not body:
            continue
        if in_spans(m.start(), spans):
            continue
        lo = 0
        for k in range(len(line_starts) - 1, -1, -1):
            if line_starts[k] <= m.start():
                lo = line_starts[k]
                break
        prefix = clean[lo:m.start()]
        if CONST_RE.search(prefix):
            continue
        if any(t in prefix for t in NON_UI_TARGETS):
            continue
        lineno = clean.count("\n", 0, m.start()) + 1
        out.append((lineno, body, prefix.strip()[-48:]))
    return out


def audit(rel: str, src: str):
    """违规项：(行号, 词, 字面量, 所属语句前缀)"""
    hits = []
    for lineno, body, ctx in candidates(rel, src):
        for word in BLACKLIST:
            if word in body and (rel, word) not in ALLOWED:
                hits.append((lineno, word, body, ctx))
                break
    return hits


def is_clean(rel: str, src: str) -> bool:
    return not audit(rel, src)


def is_exempt(rel: str) -> bool:
    return any(rel.startswith(p) for p in EXEMPT_FILES)


# ---------------------------------------------------------------------------
# ① 全量扫描
# ---------------------------------------------------------------------------

section("① 全量扫描用户可见文案")

files = [p for p in sorted(KT.rglob("*.kt"))
         if not is_exempt(str(p.relative_to(KT)).replace("\\", "/"))]

scanned = 0
all_hits = []
for p in files:
    rel = str(p.relative_to(KT)).replace("\\", "/")
    src = p.read_text(encoding="utf-8")
    scanned += 1
    all_hits.extend((rel,) + h for h in audit(rel, src))

print(f"  扫了 {scanned} 个 Kotlin 文件（已豁免 {len(EXEMPT_FILES)} 项）")
check_true("扫描面够大（不是扫了个空目录）", scanned >= 30, f"{scanned} 个文件")
check_true("黑名单非空（空黑名单会永远通过）", len(BLACKLIST) >= 20)
check_true("豁免清单不超过 3 条（豁免是口子，开太多等于没有判据）",
           len(EXEMPT_FILES) <= 3, str(list(EXEMPT_FILES)))

if all_hits:
    print(f"\n  ❌ 命中 {len(all_hits)} 处：")
    for rel, lineno, word, body, ctx in all_hits[:60]:
        print(f"     {rel}:{lineno}  「{word}」  前缀 …{ctx}  →  {body[:58]}")
    if len(all_hits) > 60:
        print(f"     …还有 {len(all_hits) - 60} 处")
check_true("用户可见文案里没有开发者话术", not all_hits, f"{len(all_hits)} 处命中")


# ---------------------------------------------------------------------------
# ② 用户点名过的那几句，逐字确认已经消失
# ---------------------------------------------------------------------------
#
# ⚠️ 必须查**用户可见的字面量**，不能查全文，也不能查所有字面量。
#
#   · 查全文会假失败：这几句话常常还留在注释里
#     （「旧文案写的是『计时在本机完成』」这种）。
#   · 查所有字面量也会假失败：「首次部署」**必须**留在 DiagLog 里 ——
#     它是 build-apk.sh 那 32 条日志锚点之一。日志本来就该用工程词。
#
# 所以复用 `candidates()`：和上面那句「用户可见文案里没有开发者话术」
# 用的是同一份筛选逻辑，两处不会各算各的。

section("② 用户点名的句子已消失")

CALLED_OUT = [
    "计时在本机完成",            # 打卡页副标题
    "转写全部在本机完成",        # 素材库刊头
    "本机识别中",                # 进行中的提示行
    "中文对照在本机生成",        # 播放页
    "转写（等引擎）",            # 条目上的转写链接
    "本轮额度用完",              # 待转写的解释
    "本机缓存",                  # 页脚
    "中文对照不可用：",          # 播放页失败态（原来拼异常原文）
    "播放失败：",                # 播放器错误（原来拼 ExoPlayer 错误码）
    "Parakeet TDT",              # 设置页里的模型型号
    "端侧语音识别自检",          # 设置页小标题
    "正在下载语音识别模型",      # 部署进度（通知栏与素材库都改成了「正在准备语音功能」）
    "首次部署",                  # 界面上原来到处是这个词
    "安装翻译模型",              # 原来是「随安装包自带，不用下载」
    "随安装包自带",              # 讲的是打包方式，用户不关心
    "没自动转",                  # 调度策略，用户不关心
    "本轮",                      # 「本轮额度用完」留下来的句式残留
]

ui_literals = {}     # 字面量 → 出现它的文件集合
for p in files:
    rel = str(p.relative_to(KT)).replace("\\", "/")
    src = p.read_text(encoding="utf-8")
    for _, body, _ in candidates(rel, src):
        ui_literals.setdefault(body, set()).add(rel)

for phrase in CALLED_OUT:
    offenders = sorted({rel for body, rels in ui_literals.items()
                        if phrase in body for rel in rels})
    check_true(f"已消失（界面字面量里）：{phrase}", not offenders, str(offenders))


# ⚠️★ 2.1.0：「模型」这个词**从 App 里彻底消失了**，所以这一段从
# 「钉住一条边界」变成了「钉住它真的没了」。
#
# 它原来是这样的：黑名单里有「翻译模型 / 本地模型」，但**没有**「语音识别模型」——
# 因为设置页里用户要管理那个 1.2 GB 的东西，「删除后重新下载」删的是什么
# 必须有个名字，那里叫它「语音识别模型」是清楚的。于是这一段把那条边界
# 钉成断言，防止下次有人「统一一下措辞」时两边来回改。
#
# 端侧识别与翻译整条拆掉之后，**没有任何模型需要用户管理了** ——
# 于是这一段的存在前提消失。现在的判据反过来：
# 这个词出现在任何**用户可见**的字面量里，都是错的（要么是漏改的旧文案，
# 要么是新写的话没讲人话）。
section("②b 「模型」这个词已经彻底消失")

# ⚠️ 判据用的是 `ui_literals`（已经过「注释剥掉 + 只留用户可见」两步），
#    不是裸读源码 —— 设置页的注释里**故意**留着「语音识别模型」这个词，
#    它记录的是「这里原来有什么、为什么删」。裸读源码会把它一起算进来，
#    于是一条正确的断言会因为一句解释性注释而失败。
check_true("全工程没有任何用户可见文案提到「模型」",
           not any("模型" in b for b in ui_literals),
           f"越界的字面量：{[b[:40] for b in ui_literals if '模型' in b][:4]}")


# ---------------------------------------------------------------------------
# ②c 术语表：同一个动作，全 App 只有一个词（2.1.1）
# ---------------------------------------------------------------------------
#
# 起因是用户的一句反馈：「app 上取回字幕，这个提示太傻了，你跟我知道
# 取回字幕是什么意思，普通用户不懂的。」
#
# 它指出的不是「一句话写得不好」，而是一类问题：**同一个动作在不同地方
# 用了不同的词，而且每一个都是工程师视角**。「取」是「从服务器拿回来」，
# 而用户看到的是「还有多久能看」。这些词在源码里看着都很自然 ——
# 只有站在用户那一侧读一遍才会发现。
#
# 所以下面这一组不是「文案检查」，是**术语表的守门人**：
# 每个词都曾经真实出现过，而且每一个都能说清它错在哪。
section("②c 术语表：同一个动作只有一个词，而且不是工程师的词")

TERMS = [
    ("取字幕", "「取」是工程师视角（从哪儿取）。用户看到的是「还要等多久」→ 用「加载」"),
    ("取回字幕", "同上，而且「取回」更含糊 —— 回哪儿？"),
    ("补字幕", "「补」暗示之前缺过，而用户并不知道缺过什么 → 用「加载」"),
    ("字幕待补", "「待补」看起来像一个坏掉的内部状态 → 用「字幕未到 / 字幕加载中」"),
    ("字幕补齐", "同一个动作在通知栏和列表里叫了两个名字 → 统一成「加载字幕」"),
    ("中文对照", "「对照」是翻译腔。用户说的是「中文翻译」"),
    (r"个源", "「源」是缩略语。日常中文里是「来源」"),
    (r"(?<!来)源检查", "同上 —— 用户可见处一律写「来源」"),
    ("转写", "2.1.0 起手机上不再做识别。写「转写」会让用户以为手机上跑了什么"),
]
# ⚠️ 判据用**正则**而不是 `in`：`来源检查` 里含 `源检查` 子串，
#    纯子串判断会把刚刚改对的文案当违规 —— 而误报比漏报更难处理，
#    它会让人把这条断言删掉。
for pattern, why in TERMS:
    rx = re.compile(pattern)
    offenders = sorted({rel for body, rels in ui_literals.items()
                        if rx.search(body) for rel in rels})
    check_true(f"界面里不再出现「{pattern}」", not offenders,
               f"{why}；越界：{offenders[:4]}")



# ---------------------------------------------------------------------------
# ③ 反向对照
# ---------------------------------------------------------------------------

section("③ 反向对照")

BAD_SAMPLES = [
    'text = "计时在本机完成"',
    'text = "转写（等引擎）"',
    'Text("本机缓存")',
    'text = "正在准备首次部署，语音识别模型就位后即可更新内容"',
    'text = "Parakeet TDT 0.6B v2 · int8 · 1.2 GB"',
    'text = "解码 → 重采样 → 端侧转写"',
    'error = "播放失败：错误码 2003"',
    'text = "正在安装翻译模型（随安装包自带）…"',
    # 光秃秃一行、没有任何 Text( 可抓 —— 这一类正是「做窄了会漏」的地方
    '"语音功能暂时不可用：原生库加载失败"',
]
for sample in BAD_SAMPLES:
    check_true(f"坏样本被拦下：{sample.splitlines()[-1][:40]}…",
               not is_clean("ui/Fake.kt", sample), repr(sample))

# 上面那条「光秃秃一行」要靠 NON_UI 区间的判定才成立：裸字面量不算在日志里
check_true("裸字面量确实进入了检查范围（不是被当成日志豁免掉）",
           candidates("ui/Fake.kt", '"语音功能暂时不可用：原生库加载失败"') != [])

GOOD_SAMPLES = [
    'text = "每天听满 40 分钟即算打卡"',
    'text = "正在生成字幕 · 通常一两分钟一集"',
    'text = "离线内容"',
    'text = "正在准备语音功能，请稍后再试"',
    'text = "退出后仍在后台播放"',
    'text = "生成字幕不需要联网，音频不会上传"',
    'text = "正在下载音频 · 可以退到后台"',
    'text = "第 1/4 个文件"',
    'text = "只在最近 3 天里挑内容"',
]
for sample in GOOD_SAMPLES:
    check_true(f"好文案不误伤：{sample[:38]}…",
               is_clean("ui/Fake.kt", sample))

# 豁免必须在起作用 —— 每一条豁免都要有一个对应的正例
check_true("DiagLog 行豁免（日志本来就该用工程词）",
           is_clean("ui/Fake.kt", 'DiagLog.i("Deploy", "首次部署 ▶ 原生库预检通过")'))
check_true("多行 DiagLog 也豁免（DiagLog 在上一行）",
           is_clean("ui/Fake.kt",
                    'DiagLog.i(\n    "Play",\n    "连播队列：未听完 ${n} 条 · ",\n)'))
check_true("逐段比对：sp.done() 豁免",
           is_clean("asr/Fake.kt", 'sp.done("$numThreads 线程 · $MODEL_TYPE")'))
check_true("逐段比对：total.fail() 豁免",
           is_clean("pipeline/Fake.kt",
                    'total.fail(e, "Error（非 Exception）—— 不让进程死")'))
check_true("尾随 lambda 一起圈进去：require(x) { \"…\" } 豁免",
           is_clean("translate/Fake.kt",
                    'require(entries.isNotEmpty()) { "词表清单为空，assets 没打进去" }'))
check_true("异常构造豁免（走异常链，不是界面文案）",
           is_clean("translate/Fake.kt",
                    'throw IllegalStateException("解码器缺少 KV cache")')
           and is_clean("audio/Fake.kt",
                        'throw DecodeException("解码失败：${e.message}", e)'))
check_true("全大写常量豁免",
           is_clean("asr/Fake.kt", 'const val DIR_NAME = "parakeet-tdt-0.6b-v2-int8"'))
check_true("  · 但普通 val 不豁免",
           not is_clean("ui/Fake.kt", 'val hint = "正在准备首次部署"'))
check_true("内部错误记录（lastError）豁免",
           is_clean("content/Fake.kt", 'lastError[sourceId] = "封面解码失败"'))
check_true("注释豁免（注释里可以讨论这些词）",
           is_clean("ui/Fake.kt",
                    '// 旧文案写的是「计时在本机完成」，那是开发者视角\n'
                    'text = "每天听满 40 分钟即算打卡"'))
check_true('字符串里的括号不会带偏配对（紧接着的那句仍然被检查）',
           is_clean("ui/Fake.kt",
                    'DiagLog.i("T", "配对(测试)别带偏")\ntext = "每天听满 40 分钟即算打卡"')
           and not is_clean("ui/Fake.kt",
                            'DiagLog.i("T", "配对(测试)")\ntext = "正在准备首次部署"'))
check_true("诊断包整包豁免", is_exempt("diag/DeviceInfo.kt"))
check_true("  · 但 ui/ 下的其它文件不豁免", not is_exempt("ui/LibraryScreen.kt"))
# ⚠️ 2.1.0：探针从 `asr/AsrEngine.kt` 换成了 `sync/SubtitleOutcome.kt` ——
#    前者随端侧识别一起删了，而这条断言考的是「前缀匹配不许退化成子串匹配」，
#    拿一个不存在的路径去考，考的就变成了「文件不存在」。
check_true("豁免用前缀不错按子串：sync/ 不被 diag/ 放过",
           not is_exempt("sync/SubtitleOutcome.kt"))

# 白名单机制要能工作（当前为空）
def _allowed_probe():
    ALLOWED[("ui/Fake.kt", "队列")] = "探针"
    try:
        return is_clean("ui/Fake.kt", 'text = "队列已清空"')
    finally:
        del ALLOWED[("ui/Fake.kt", "队列")]
check_true("白名单机制生效（登记后放行）", _allowed_probe())
check_true("  · 撤销登记后又会被拦", not is_clean("ui/Fake.kt", 'text = "队列已清空"'))


# ---------------------------------------------------------------------------
# ④ 行号必须能定位
# ---------------------------------------------------------------------------

section("④ 报告的行号能直接定位（检查器自己也要被检查）")

FIXTURE = '''
package x

import y

/** 注释
 *  多行
 */
fun f() {
    DiagLog.i("T", "首次部署 ▶")          // 第 10 行：应豁免
    val a = 1
    text = "正在准备首次部署"              // 第 12 行：应命中
}
'''
hits = audit("ui/Fake.kt", FIXTURE)
check("命中数", len(hits), 1)
check("命中行号 = 12（剥注释不能让行号错位）", hits[0][0] if hits else None, 12)


# ---------------------------------------------------------------------------
# ⑤ 传递路径：错误信息从底层冒到界面的三处，必须已经被掐断
# ---------------------------------------------------------------------------
#
# 黑名单只能查「字面量」。而有三条路径是**变量**冒到界面的 ——
# 文案写得再好也没用，因为拼进来的是别人给的字符串。
# 这三条 1.2.x 都亲手改过，这里把「改过」变成断言，防止下次又被拼回去。
#
# ⚠️★ 2.1.0：这一节**缩小了一半**，因为被它保护的两条路径整块消失了：
#    · `pipeline/TranscribePipeline.kt` 的 `friendlyError`（四类原生库错误
#      各给一句固定文案）—— 客户端不再有原生代码，没有 `Error` 可翻译
#    · 播放页的「中文对照失败态」—— 现在中文只有「到 / 还没到」两种状态，
#      没有任何失败信息可以拼
#    留下的两条仍然是活的（播放器错误码、通知栏不显示文件名），
#    另外补一条**面向未来**的：中文提示必须是固定文案。

section("⑤ 拼接路径不许把底层原文甩给用户")

pv = (KT / "ui/PlayerViewModel.kt").read_text(encoding="utf-8")
ps = (KT / "ui/PlayerScreen.kt").read_text(encoding="utf-8")

check_true("播放器错误不再拼 ExoPlayer 错误码", "error.errorCodeName" not in pv)
check_true("  · 而是一句固定的话", '"播放失败，请稍后重试"' in pv)
# ⚠️ 判据用 `ui_literals`（注释已剥掉）而不是裸读源码 ——
#    PlayerScreen 的注释里**故意**留着「中文对照」这个词（它记的是
#    「这里原来叫什么、为什么改」）。裸读会把它算进来，于是一条正确的
#    断言被一句解释性注释判失败。
check_true("播放页的中文提示是固定文案，不拼任何变量",
           '"中文翻译正在加载，稍等一下"' in ps
           and not any("中文对照" in b for b in ui_literals),
           "2.1.1：措辞从「中文对照正在取回」改成「中文翻译正在加载」——"
           "「对照」是翻译腔，「取回」是工程师视角（从哪儿取）")
# ⚠️ 2.1.0：这条原来查的是「部署通知里不许出现 encoder.int8.onnx 这种文件名」，
#    而部署通知整块删了。改成**对整份通知代码**的判据：不许出现任何文件名变量 ——
#    判据的意图没变（用户不需要知道我们下载的是什么文件），
#    但覆盖面从「一个函数」扩到了「整个通知层」，因为现在没有部署通知可以豁免了。
check_true("通知层不显示任何文件名",
           ".fileName" not in (KT / "sync/ContentSyncService.kt").read_text(encoding="utf-8"))
check_true("设置页也不再显示模型文件名",
           "Text(p.fileName" not in (KT / "ui/SettingsScreen.kt").read_text(encoding="utf-8"))
# ⚠️ 2.1.0：渠道名从「内容更新与语音准备」改成了「内容更新」——
#    「语音准备」指的是那 630 MB 模型的下装，它已经不存在了。
check_true("通知渠道名与描述已去技术味",
           all(s in (KT / "EnglishCommuteApp.kt").read_text(encoding="utf-8")
               for s in ("内容更新", "显示每日内容更新的下载与字幕进度")))
# ⚠️ 2.1.0：原来还有一条「自检区块允许显示底层消息」的**豁免**断言 ——
#    自检区块（用户选一段音频试转写）随端侧识别一起删了，
#    所以那条豁免也没有对象了。豁免机制仍在（ALLOWED 字典），
#    但这一轮起它为空 —— 没有口子才是最好的状态。


# ---------------------------------------------------------------------------
# ⑥ 黑名单本身不许腐化
# ---------------------------------------------------------------------------

section("⑥ 黑名单不许腐化")

for word in ("本机完成", "端侧", "原生库", "引擎", "部署", "就位", "额度",
             "int8", "onnx", "断点续传", "流水线"):
    check_true(f"黑名单里有「{word}」", word in BLACKLIST)

for (rel, word), reason in ALLOWED.items():
    check_true(f"白名单 ({rel}, {word}) 有理由", bool(reason.strip()),
               "没有理由的白名单等于把判据拆掉")


# ---------------------------------------------------------------------------

section("汇总")
print(f"通过 {PASS} 项，失败 {FAIL} 项")
if FAIL:
    print("\n❌ 有断言未通过：")
    for f in FAILURES:
        print("  - " + f)
    sys.exit(1)
print("✅ 全部通过")
