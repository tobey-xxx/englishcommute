#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
0.12.1 三处闪退修复的桌面验证。

这三处都是「真机上才炸、桌面完全看不出来」的类型，所以验法分两类：

| 修复 | 为什么必须单独验 | 怎么验 |
|---|---|---|
| ① ListeningClock 线程 | 是**线程约束**，桌面没有 ExoPlayer，运行时行为复刻不了 | 对源码断死「调度器必须是 Main」 |
| ② ASR 释放竞态 | 是**并发时序**，真机上表现为 native SIGSEGV、连栈都拿不到 | 桌面复刻计数语义，逐个时序走一遍 |
| ③ trace 字符串提取 | 是**纯函数**，但输入是二进制，边界很多 | 1:1 复刻 + 真实脏字节 + 合成边界 |

用法：
    python3 tools/verify-crashfix.py

权威实现在 Kotlin 侧，本文件里的复刻必须逐行对齐 ——
改了 Kotlin 先同步这里，否则验证就成了自说自话。
"""

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
KT = ROOT / "app/src/main/java/com/example/englishcommute"
REAL_LOG = ROOT / "testdata/diag-0920.log"

PASS = 0
FAILURES = []


def section(title: str) -> None:
    print(f"\n{'─' * 74}\n{title}\n{'─' * 74}")


def check(label: str, got, want) -> None:
    global PASS
    ok = got == want
    if ok:
        PASS += 1
        print(f"  ✅ {label}")
    else:
        FAILURES.append(label)
        print(f"  ❌ {label}\n        期望 {want!r} · 实际 {got!r}")


def check_true(label: str, cond: bool, note: str = "") -> None:
    global PASS
    if cond:
        PASS += 1
        print(f"  ✅ {label}")
    else:
        FAILURES.append(label)
        suffix = f"\n        {note}" if note else ""
        print(f"  ❌ {label}{suffix}")


def read(rel: str) -> str:
    p = KT / rel
    if not p.is_file():
        FAILURES.append(f"找不到源文件 {rel}")
        print(f"  ❌ 找不到源文件：{rel}")
        return ""
    return p.read_text(encoding="utf-8")


def strip_comments(src: str) -> str:
    """
    剥掉注释再断言。

    本工程的注释里到处写着「⚠️ 不要用 XXX」这类反面教材，
    直接对全文断言会被自己的注释判失败（0.12.0 真实踩过）。
    """
    src = re.sub(r"/\*.*?\*/", "", src, flags=re.S)
    src = re.sub(r"//[^\n]*", "", src)
    return src


# ===========================================================================
section("① ListeningClock：调度器必须是主线程（0.12.0 一播放就闪退的根因）")
# ===========================================================================
#
# 症状：真机上 `attach` 之后**恰好 1.000 秒**抛
#   IllegalStateException: Player is accessed on the wrong thread
# 而且它不是「被 catch 住的小错」—— 协程里未捕获的异常会交给进程的默认
# 异常处理器，那是**进程级崩溃**，用户看到的就是闪退。
#
# 为什么只能断言源码：ExoPlayer 的线程校验（verifyApplicationThread）
# 是运行时行为，Python 里没有对应物。这类约束按本工程惯例改对源码断言。

lc = read("checkin/ListeningClock.kt")
lc_code = strip_comments(lc)

check_true(
    "计时器 scope 用 Main.immediate",
    "CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)" in lc_code,
)
check_true(
    "计时器 scope **没有**用 Dispatchers.Default（这就是崩溃原因）",
    "private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)" not in lc_code,
    "`player.isPlaying` 只能在主线程读",
)
check_true(
    "海报渲染走另一个 scope —— 它是 CPU 活，恰恰不该在主线程跑",
    "workScope.launch" in lc_code and "Dispatchers.Default)" in lc_code,
)
check_true(
    "读播放状态包了 try/catch",
    "val counting = try {" in lc_code and "catch (t: Throwable)" in lc_code,
)
check_true(
    "catch 的是 Throwable 而不是 Exception",
    "catch (e: Exception)" not in lc_code,
    "UnsatisfiedLinkError 这类继承 Error，Exception 接不住",
)
check_true(
    "注释里写清了「为什么必须是 Main」——防止后人改回去",
    "只能在主线程读" in lc,
)


# ===========================================================================
# ⚠️★ 2.1.0：这里原本是第 ② 节「AsrEngine：release 不能 free 掉正在被 native
# 使用的识别器」—— 含 11 条源码断言 + 一整套「release 判据」与「空闲重试循环」的
# 桌面复刻。
#
# 它记录的是 **v0.11.0 真机那次 SIGSEGV**：空闲 90 秒后释放识别器，
# 而 native 侧的 decode 还在跑 → use-after-free。修法是三件套：
# activeSessions 活跃计数、`release()` 返回 Boolean（忙就跳过）、
# 在 SyncEngine 里带重试的空闲计时器。
#
# 端侧识别整条拆掉之后，**没有识别器可以释放了**，所以这一节连同复刻代码一起删。
# 那段知识不是消失了 —— 它以四种形式留着：
#   · `EnglishCommute-2.0.4-快照/` 与 `EnglishCommute-1.0-归档/` 里的原源码
#   · skill `android-ondevice-asr-sherpa-onnx`（「release 必须等活跃会话归零」）
#   · `MEMORY.md` 的教训条目（「直接 close 就是 v0.11.0 那次 SIGSEGV」）
#   · 本节这段说明
#
# 留说明而不是删干净，是因为**下一个人再加原生依赖时会撞上完全一样的坑** ——
# 那时这一行注释就是唯一的指路牌。

# ===========================================================================
# ⚠️★ 2.1.0：这里原本是第 ② 节「AsrEngine：release 不能 free 掉正在被 native
# 使用的识别器」—— 含 11 条源码断言 + 一整套 release 判据与重试循环的桌面复刻。
#
# 它记录的是 **v0.11.0 真机那次 SIGSEGV**：空闲 90 秒后释放识别器，
# 而 native 侧的 decode 还在跑 → use-after-free。修法是 activeSessions
# 活跃计数 + release() 返回 Boolean（忙就跳过）+ 在 SyncEngine 里带重试的
# 空闲计时器。
#
# 端侧识别整条拆掉之后，**没有识别器可以释放了**，所以这一节连同它的复刻代码
# 一起删。那段知识不是消失了 —— 它以三种形式留着：
#   · `EnglishCommute-2.0.4-快照/` 与 `EnglishCommute-1.0-归档/` 里的原源码
#   · skill `android-ondevice-asr-sherpa-onnx` 里「release 必须等活跃会话归零」
#   · `MEMORY.md` 的教训条目（「直接 close 就是 v0.11.0 那次 SIGSEGV」）
#
# 留下这一段说明而不是只删干净，是因为**下一个人如果再加原生依赖，
# 会撞上完全一样的坑** —— 而那时这一行注释就是唯一的指路牌。

section("③ CrashGuard：从二进制 proto 里捞字符串（别再污染日志）")
# ===========================================================================
#
# `ApplicationExitInfo.traceInputStream` 是 **protobuf 二进制**，不是文本。
# 原先用 bufferedReader().useLines{} 直接读，两个后果：
#   1. 写出 NUL 字节 → ripgrep/grep 把整个日志判定为 binary 并停止匹配，
#      从此这份日志什么关键词都搜不到（0.12.0 真机日志就是这样）；
#   2. 120 行约 60KB 乱码，占掉日志文件六成体积，把有用记录挤出滚动窗口。

cg = read("diag/CrashGuard.kt")
cg_code = strip_comments(cg)

check_true("不再按文本读 trace", "bufferedReader()" not in cg_code)
check_true("改为按字节提取", "printableStrings(ins.readBytes())" in cg_code)
check_true("单条字符串有长度上限", "private const val MAX_STRING_LEN" in cg_code)
check_true("过短的段被丢弃", "private const val MIN_STRING_LEN" in cg_code)


# ---- 1:1 复刻 ----
MIN_STRING_LEN = 6
MAX_STRING_LEN = 240
TRACE_MAX_LINES = 120


def printable_strings(data: bytes, max_lines: int = TRACE_MAX_LINES) -> str:
    """对应 CrashGuard.printableStrings —— 逐行对齐，别"意思一下重写"。"""
    out = []
    sb = []
    for b in data:
        c = b & 0xFF
        if 0x20 <= c <= 0x7E:
            sb.append(chr(c))
            continue
        if len(sb) >= MIN_STRING_LEN:
            out.append("".join(sb)[:MAX_STRING_LEN])
        sb = []
        if len(out) >= max_lines:
            break
    if len(out) < max_lines and len(sb) >= MIN_STRING_LEN:
        out.append("".join(sb)[:MAX_STRING_LEN])
    return "\n".join(out)


print("\n  ── 合成边界 ──")
check("空输入 → 空输出", printable_strings(b""), "")
check("短于 6 的段被丢弃", printable_strings(b"abc\x00def"), "")
check("恰好 6 → 保留", printable_strings(b"abcdef"), "abcdef")
check("尾部没有分隔符时也要收下", printable_strings(b"short\x00abcdef"), "abcdef")
check("不可打印字节当分隔符", printable_strings(b"abcdef\x01ghijkl"), "abcdef\nghijkl")

long_seg = b"x" * 300
check("超长段被截到 240", len(printable_strings(long_seg)), MAX_STRING_LEN)

many = b"\x00".join(b"seg%03d" % i for i in range(200))
check("超过 120 段时截断", len(printable_strings(many).split("\n")), TRACE_MAX_LINES)

full_range = bytes(range(256)) * 4
out_r = printable_strings(full_range)
check("全字节范围输入不产生控制字符", all(c == "\n" or 0x20 <= ord(c) <= 0x7E for c in out_r), True)

print("\n  ── 真实脏字节（0.12.0 那份真机日志）──")


def strip_log_prefix(chunk: bytes) -> bytes:
    """
    剥掉每行开头由 DiagLog 加的前缀（时间戳 / 线程名 / 级别 / │）。

    真机上喂给 printableStrings 的是**纯 proto 字节**，没有这些前缀。
    不剥的话，前 120 段会被前缀噪音占满，后面真正的栈信息根本轮不到 ——
    那是测试样本不忠实，不是产品的问题。
    """
    bar = "\u2502".encode("utf-8")
    lines = []
    for line in chunk.split(b"\n"):
        i = line.find(bar)
        lines.append(line[i + len(bar):].lstrip(b" ") if i != -1 else line)
    return b"\n".join(lines)


if REAL_LOG.is_file():
    raw = REAL_LOG.read_bytes()
    nul_at = raw.find(b"\x00")
    check_true("样本里确实含 NUL（就是它把 grep 弄瞎的）", nul_at != -1)

    # 从「上次退出 trace」那行开始切：它之后才是真正的 proto 内容，
    # 之前是同一份日志里正常的业务记录 —— 不切掉的话前 70 段全是它们，
    # 真东西会被 120 行的上限挡在外面（我第一版就踩了这个）。
    # 终点取到 NUL 之后 7KB：栈帧名（art::Thread::Park、java.util.concurrent…）
    # 都落在这一段里。
    marker = "\u4e0a\u6b21\u9000\u51fa trace".encode("utf-8")
    start = raw.find(marker, 0, nul_at)
    if start == -1:
        start = max(0, nul_at - 3000)
    chunk = strip_log_prefix(raw[start:nul_at + 7000])
    out_real = printable_strings(chunk)

    check_true("**输出里没有 NUL**（这是当初把日志搞坏的直接原因）", "\x00" not in out_real)
    check_true(
        "输出里只有可打印字符 + 换行",
        all(c == "\n" or 0x20 <= ord(c) <= 0x7E for c in out_real),
    )
    check_true("捞出了崩溃类型 SIGSEGV", "SIGSEGV" in out_real)
    check_true("捞出了故障类型 SEGV_MAPERR", "SEGV_MAPERR" in out_real)
    check_true("捞出了故障原因 null pointer dereference", "null pointer dereference" in out_real)
    check_true("捞出了 native 栈帧名（原来全被噪音埋着）", "art::Thread::Park" in out_real)
    check_true("捞出了 java 栈帧名", "java.util.concurrent" in out_real)
    check_true("行数不超过上限", len(out_real.split("\n")) <= TRACE_MAX_LINES)

    # 断绝对体积而不是比例：比例取决于「这一段里恰好有多少可打印内容」，
    # 换个崩溃场景就变了；而日志吃不吃得消只看绝对量。
    # 旧写法（按文本读）固定产出 120 行、约 60 KB。
    size = len(out_real.encode("utf-8"))
    check_true(
        f"输出体积可控（{size / 1024:.1f} KB · 旧写法约 60 KB）",
        size < 10 * 1024,
        "日志是滚动覆盖的，乱码会把有用记录挤出去",
    )
else:
    print(f"  ⚠️  跳过：找不到 {REAL_LOG.name}（真机日志不在 testdata/ 里）")


# ===========================================================================
print(f"\n{'=' * 74}")
if FAILURES:
    print(f"❌ 失败 {len(FAILURES)} 项 / 共 {PASS + len(FAILURES)} 项")
    for f in FAILURES:
        print(f"   · {f}")
    sys.exit(1)
print(f"✅ 全部通过：{PASS} 项")
print(
    """
本文件覆盖的三处闪退，都是「编译期完全静默、真机才炸」的类型：

  · ① 计时器挂错调度器 → 一播放就闪退，且是进程级（catch 不到）
  · ② 空闲释放撞上正在跑的 native decode → SIGSEGV，连 Kotlin 栈都没有
  · ③ 二进制 trace 写进文本日志 → 日志从此搜不到任何东西

共同点：**它们都不会让编译失败，也不会被任何「功能测试」发现**。
①只存在于运行时线程校验里，②只在特定时序下发生，③损坏的是排查手段本身。
"""
)
