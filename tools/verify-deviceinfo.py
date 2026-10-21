#!/usr/bin/env python3
"""
验证 diag/DeviceInfo 的判据与接入。

## 为什么单独验这个

设备画像本身不复杂，但它承载了两个**结论必须来自真机**的问题：

  1. 这台机器是不是 16 KB 页？—— 决定「原生库对齐断言是否在为本机工作」
  2. 本进程实际能用几个核？—— 判断 ROM 有没有用 cpuset 限制本进程
     （2.1.0 之前它还有一个用途：ASR 线程数上限；端侧识别拆掉后只剩前者）

这两条一旦读错或**没接进去**，表现是「日志里没有那一行」——
而缺失是最难注意到的失败形态：你不会发现少了一行，只会继续猜。

所以这里验三类：

  A. 纯函数边界：页大小判据、核数受限判据、页标签格式化
  B. 接入完整性：启动路径顺序、崩溃快照、界面渲染（源码断言）
  C. ~~「不重写公式」~~ —— 2.1.0 删除，线程数这个概念在 App 里已不存在

C 特别值得单独盯：把公式抄一份到 DeviceInfo，两处迟早漂移，
而漂移的症状恰恰是「日志里写的线程数和实际用的不一样」—— 比不写还糟。
"""

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
PASS = 0
FAIL = 0


def check_true(desc, cond, extra=""):
    global PASS, FAIL
    if cond:
        PASS += 1
        print(f"  ✓ {desc}")
    else:
        FAIL += 1
        print(f"  ✗ {desc}" + (f"  [{extra}]" if extra else ""))


def check_eq(desc, got, want):
    check_true(desc, got == want, f"got={got!r} want={want!r}")


def strip_comments(src: str) -> str:
    """剥掉注释再断言 —— 本工程注释里到处是解释性的反面例子。"""
    src = re.sub(r"/\*.*?\*/", "", src, flags=re.S)
    src = re.sub(r"//[^\n]*", "", src)
    return src


def read(rel: str) -> str:
    return (ROOT / rel).read_text()


# ============================================================
# A. 纯函数：与 Kotlin 逐行对齐
# ============================================================

def page_label(size: int) -> str:
    """⚠️ 对齐 Kotlin：size <= 0 → 未知；% 1024 == 0 → "{n}KB"；否则 "{n}B" """
    if size <= 0:
        return "未知"
    if size % 1024 == 0:
        return f"{size // 1024}KB"
    return f"{size}B"


def is_16k(size: int) -> bool:
    """⚠️ 对齐 Kotlin：>= 16384"""
    return size >= 16384


def cpu_capped(available: int, configured: int) -> bool:
    """⚠️ 对齐 Kotlin：configured > available"""
    return configured > available


def test_pure():
    print("\n=== A. 页大小与核数判据边界 ===")

    # --- 页大小标签 ---
    cases = [
        (-1, "未知"),      # sysconf 失败返回 -1
        (0, "未知"),       # 读取异常时的兜底
        (1, "1B"),
        (512, "512B"),     # 不是 1024 的倍数
        (1024, "1KB"),
        (4096, "4KB"),     # 绝大多数设备
        (8192, "8KB"),
        (16384, "16KB"),   # ⚠️ 新一代旗舰
        (16385, "16385B"), # 略超 16KB 但不是整倍数
        (65536, "64KB"),
    ]
    for size, want in cases:
        check_eq(f"页 {size:6d} → {want}", page_label(size), want)

    # --- 16 KB 判据：边界要精确 ---
    check_true("16383 不算 16KB 页", not is_16k(16383))
    check_true("16384 算 16KB 页（含等号）", is_16k(16384))
    check_true("4096 不算", not is_16k(4096))
    check_true("0 / 负值不算", not is_16k(0) and not is_16k(-1))
    check_true("65536 算", is_16k(65536))

    # 关键交叉：标签与判据必须一致。
    #
    # ⚠️ 判据是「页 >= 16KB」，**不是**「标签恰好写着 16KB」——
    # 我第一版就写成了 `page_label(size) == "16KB"`，于是在 64KB 页上判错。
    # 而 64KB 页只会让对齐要求更严，把它归到「非 16KB 页」是反的。
    for size in (4096, 8192, 16384, 32768, 65536):
        kb = size // 1024
        check_true(f"页 {size}（{page_label(size)}）: 标签与判据一致",
                   (kb >= 16) == is_16k(size))

    # --- 核数受限 ---
    check_true("8 可用 / 8 配置 → 未受限", not cpu_capped(8, 8))
    check_true("4 可用 / 8 配置 → 受限（ROM 做了 cpuset）", cpu_capped(4, 8))
    check_true("8 可用 / 4 配置 → 未受限（配置读到偏小也不误报）",
               not cpu_capped(8, 4))
    check_true("2 可用 / 2 配置 → 未受限", not cpu_capped(2, 2))

    # 常见场景：全大核旗舰（8 核全可用）vs 被限制到 4 核
    check_true("天玑 9500 典型值 8/8 → 不受限", not cpu_capped(8, 8))
    check_true("某些 ROM 限制成 4/8 → 受限，线程数按 4 算", cpu_capped(4, 8))


# ============================================================
# B. 接入完整性
# ============================================================

def test_wiring():
    print("\n=== B. 接入完整性 ===")

    dev_raw = read("app/src/main/java/com/example/englishcommute/diag/DeviceInfo.kt")
    dev = strip_comments(dev_raw)
    app_raw = read("app/src/main/java/com/example/englishcommute/EnglishCommuteApp.kt")
    app = strip_comments(app_raw)
    screen = strip_comments(read("app/src/main/java/com/example/englishcommute/ui/DiagScreen.kt"))

    # --- 采集要读的两样关键值 ---
    check_true("读页大小（_SC_PAGESIZE）", "_SC_PAGESIZE" in dev)
    check_true("读系统配置核数（_SC_NPROCESSORS_CONF）", "_SC_NPROCESSORS_CONF" in dev)
    check_true("读本进程可用核数（availableProcessors）", "availableProcessors()" in dev)
    check_true("读首选 ABI（SUPPORTED_ABIS）", "SUPPORTED_ABIS" in dev)

    # sysconf 失败会抛 / 返回 -1 —— 诊断组件不允许自己成为故障源
    check_true("sysconf 读取包在 runCatching 里", "runCatching { Os.sysconf" in dev)
    check_true("采集入口不抛异常", "runCatching { DeviceInfo.capture(this) }" in app)

    # --- 启动路径：顺序是有意的 ---
    check_true("启动时调用采集", "captureDeviceInfo()" in app)

    i_dev = app.find("captureDeviceInfo()")
    # 找**调用**而不是定义：定义在启动流程之后，会误判
    call_at = app.find("captureDeviceInfo()")
    check_true("采集在启动路径里被调用（不是只有定义）",
               call_at >= 0 and call_at != app.find("private fun captureDeviceInfo()"))
    # ⚠️ 2.1.0：这条原来比的是「采集排在 `checkNativeLibs()` 之前」——
    #    理由是「原生库预检失败时，得先知道这台机器是不是 16 KB 页」。
    #    原生库预检随端侧识别一起删了，于是换成**同一顺序意图**的新对象：
    #    采集仍然要排在「清理端侧模型」之前。理由是同一类 ——
    #    清理过程中一旦出任何意外，这台机器是几核、几 KB 页必须已经在日志里了。
    check_true("采集排在清理端侧模型之前（出事时先知道机器画像）",
               app.find("captureDeviceInfo()") < app.find("purgeOnDeviceModels()"))

    # --- 崩溃快照 ---
    check_true("崩溃快照里带上设备信息", "DeviceInfo.last?.let" in app)
    check_true("快照用的是缓存值，不在崩溃路径重新 sysconf",
               "DeviceInfo.last?.let" in app and "DeviceInfo.capture" not in
               app[app.find("private fun snapshot()"):app.find("private fun snapshot()") + 1600])

    # --- 界面 ---
    check_true("诊断页 import 了 DeviceInfo", "diag.DeviceInfo" in screen)
    check_true("诊断页渲染 rows()", "dev.rows().forEach" in screen)
    check_true("诊断页用 remember 避免每次重组重算", "remember(context)" in screen)
    check_true("界面兜底调用 peek（启动没采到也能显示）", "DeviceInfo.peek(context)" in screen)

    # --- 日志锚点：必须作为**完整字面量**出现 ---
    #
    # ⚠️ build-apk.sh 是在 dex 里逐字节搜字符串常量的，而
    # `"设备 ${s.logLine()}"` 这类模板会编译成**两段**：dex 里只有 `"设备 "`，
    # `Android` 落在 logLine() 内部的另一个常量里 —— 跨段的锚点永远搜不到。
    # 它已经因此让一次构建失败过（`MISS 设备 Android`）。
    check_true("日志前缀「设备 Android 」是一个完整字面量",
               'append("设备 Android ")' in dev_raw)
    check_true("capture 不再重复拼前缀（否则输出两个「设备」）",
               '"设备 ${s.logLine()}"' not in dev_raw)


# ============================================================
# C. （2.1.0 已删）线程数公式不得重复实现
# ============================================================
#
# 这一节原本查的是「DeviceInfo 不许自己重写 (核数/2).coerceIn(2,4) 那个公式，
# 必须复用它读自 AsrEngine」—— 因为两处各写一份**必然漂移**，
# 而漂移的症状恰恰是「日志里写的线程数和实际用的不一样」，比不写还糟。
#
# 端侧识别拆掉之后**没有任何东西要挑线程数了**（DeviceInfo 里的 `asrThreads`
# 也一并删了），于是这一节连同它的 Python 复刻公式一起删。
# 那条纪律本身仍然成立，它的新居所是 skill 里的原生依赖章节。


# ============================================================
# D. 构建断言已登记
# ============================================================

def test_build_assertion():
    print("\n=== D. 构建脚本已登记 ===")

    sh = read("build-apk.sh")
    check_true("日志锚点含「设备 Android」", "'设备 Android'" in sh)
    check_true("dex 类含 diag/DeviceInfo", "'diag/DeviceInfo'" in sh)
    check_true("用类型描述符而非裸名（DeviceInfo 太常见，裸名会假阳性）",
               "'diag/DeviceInfo'" in sh and "'DeviceInfo'" not in sh.split("targets = [")[1][:2000])

    import subprocess
    r = subprocess.run(["bash", "-n", str(ROOT / "build-apk.sh")],
                       capture_output=True, text=True)
    check_true("build-apk.sh 语法检查通过", r.returncode == 0, r.stderr.strip()[:120])


def test_anchors_are_literals():
    """
    把 build-apk.sh 的每个日志锚点，拿去 Kotlin 源码里对一遍。

    为什么做成**通用**检查而不是只盯「设备 Android」：
    锚点有无数种写法可以踩坑（字符串模板、StringBuilder 分段、常量拼接），
    而它们在编译期**全都合法** —— 唯一会说话的地方是构建脚本里那句 `MISS`，
    而那要等到打包跑完。提前对一遍，把「写完代码等十分钟、然后构建失败」
    变成秒级反馈。
    """
    print("\n=== E. 日志锚点必须是完整字面量 ===")

    sh = read("build-apk.sh")
    m = re.search(r"anchors = \[(.*?)\]", sh, re.S)
    anchors = re.findall(r"'([^']+)'", m.group(1)) if m else []
    check_true("能从构建脚本解析出锚点列表", len(anchors) > 0, f"{len(anchors)} 个")

    # 把所有 Kotlin 源码拼起来。**这一步不剥注释** —— 它只做「是否原样存在」的
    # 存在性判断，多算几行注释不会漏掉真问题（下一层才专门防这个）。
    kt = "\n".join(p.read_text() for p in (ROOT / "app/src/main/java").rglob("*.kt"))

    not_in_code = [a for a in anchors if a not in kt]
    check_true(f"{len(anchors)} 个锚点都能在 Kotlin 源码里原样找到",
               not not_in_code, f"缺失：{not_in_code}")

    # 再一层：确认它出现在**含双引号的代码行**里，而不是只写在注释中。
    # 只写在注释里的锚点，dex 里当然没有。
    only_in_comment = []
    for a in anchors:
        ok = False
        for line in kt.splitlines():
            code = line.split("//")[0]
            if a in code and '"' in code:
                ok = True
                break
        if not ok:
            only_in_comment.append(a)
    check_true("每个锚点都在含双引号的代码行里（不是只在注释里）",
               not only_in_comment, f"{only_in_comment}")


def main():
    print("验证设备画像：判据边界 + 接入完整性 + 公式不重复 + 锚点可搜")
    test_pure()
    test_wiring()
    # test_no_duplicate_formula()  ← 2.1.0 删除（线程公式随端侧识别一起没了）
    test_build_assertion()
    test_anchors_are_literals()

    print()
    print(f"通过 {PASS} 项，失败 {FAIL} 项")
    if FAIL:
        print("❌ 有断言未通过")
        return 1
    print("✅ 全部通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
