#!/usr/bin/env python3
"""
机型兼容性自检：把一个 APK 和一颗 SoC 摆在一起，回答「这台机器上能不能跑」。

## ⚠️★ 2.1.0：这个脚本现在**主要是一份说明书**

端侧识别与翻译整条拆掉之后，包里**没有任何 .so**（实测 0 个），
所以下面那三层 native 门槛一项都不适用 —— 脚本会如实报「不适用」并退出 0。

它没有被删掉，理由和 `build-apk.sh` 里那三条对齐断言一样：
**原生依赖回来的那一天，这里是唯一能在装机之前拦住问题的地方。**
而那时候最需要的东西不是代码，是**这三十行注释里记着的坑** ——
比如「中央目录的 extra length 不能用来反算条目起点」这种，
不知道的话会得出一份「全部没对齐」的假结论，然后去改一个根本没坏的东西。

## 为什么需要它（原理，仍然成立）

本工程的 native 依赖有三层，任何一层不匹配都会在**真机第一次 dlopen 时**才炸，
编译期与桌面模拟器一律静默：

  1. **ABI**        —— 设备是 arm64 还是 armv7，包里有没有对应的 .so
  2. **ELF 段对齐**  —— `p_align >= 16 KB`；Android 15+ 的 16 KB 页设备硬性要求
  3. **ZIP 条目对齐** —— 条目数据起点必须落在 16 KB 边界
                       （`useLegacyPackaging = false` 时 .so 是直接 mmap 的，
                        不经解压落盘，所以这条与第 2 条**缺一不可**）

第 3 条最阴：**它坏掉时，4 KB 页设备（开发机）一切正常，只有 16 KB 页设备崩。**
换句话说，唯一能发现它的时机就是拿到新旗舰机的那一刻 —— 而那时候
用户看到的会是一个没有堆栈的启动闪退。

真实案例：天玑 9500 这一代旗舰普遍启用 16 KB 页。本脚本的第 3 条检查
就是为了在**装机之前**把这类问题拦住。build-apk.sh 的断言 9 调它。

## 「能不能用」的判据从哪来

不猜，全部读自产物本身：

  - ABI            → 读 APK 的 lib/<abi>/ 目录清单
  - ELF 段对齐      → 逐个 PT_LOAD 读 p_align 与 (p_vaddr - p_offset)
  - ZIP 条目对齐    → 从 local file header **自己**解析 data offset
                      （⚠️ 不能用 central directory 的 extra length 反算：
                        zipalign 对齐正是靠往 **local** extra field 插 padding 实现的，
                        两者长度不同，用后者算出来的结果会全是不对齐的假象）
  - int8 加速指令    → 在 libonnxruntime.so 里找 MLAS / dotprod / i8mm 字串
  - SME 依赖        → 找 SME 字串。**找到 0 次才是好消息**（见下）

## 为什么「SME 出现 0 次」值得专门断言

天玑 9500 是 Armv9.3 + SME2。理论上存在一类故障：库用了 SME 指令，
而设备的 Android 内核没有开启 SME 上下文切换 → SIGILL。
**本工程不存在这个风险，因为 onnxruntime 1.28 完全不碰 SME**（实测字串计数 0）。
这条断言的作用是：万一将来升级 ORT 之后字串出现了，脚本会说话，
而不是等用户拿新机去撞。

## 用法

    python3 tools/check-device-compat.py apk/EnglishCommute-0.13.0-debug.apk
    python3 tools/check-device-compat.py <apk> --cpus 8 --soc "天玑 9500"

`--cpus` 用来推演 ASR 的实际线程数（默认 8，即当前旗舰常见核数）。
"""

import argparse
import glob
import os
import struct
import sys
import zipfile

# ---- 纯函数：判据与推演 --------------------------------------------------
#
# 下面这些函数都是「错误的后果是静默的」那一类，所以刻意写成无副作用的纯函数，
# 方便在桌面直接喂边界值。与 Kotlin 侧逐行对齐的部分用 ⚠️ 标出。

ELF_PT_LOAD = 1
PAGE_4K = 0x1000
PAGE_16K = 0x4000


def elf_load_segments(blob: bytes):
    """返回 [(p_offset, p_vaddr, p_align)]，只含 PT_LOAD 段。非 ELF 返回 None。"""
    if blob[:4] != b"\x7fELF":
        return None
    is64 = blob[4] == 2
    if is64:
        phoff = struct.unpack_from("<Q", blob, 32)[0]
        phentsize = struct.unpack_from("<H", blob, 54)[0]
        phnum = struct.unpack_from("<H", blob, 56)[0]
    else:
        phoff = struct.unpack_from("<I", blob, 28)[0]
        phentsize = struct.unpack_from("<H", blob, 42)[0]
        phnum = struct.unpack_from("<H", blob, 44)[0]

    segs = []
    for i in range(phnum):
        o = phoff + i * phentsize
        if struct.unpack_from("<I", blob, o)[0] != ELF_PT_LOAD:
            continue
        if is64:
            off, va, al = (struct.unpack_from("<Q", blob, o + k)[0] for k in (8, 16, 48))
        else:
            off, va, al = (struct.unpack_from("<I", blob, o + k)[0] for k in (4, 8, 28))
        segs.append((off, va, al))
    return segs


def elf_page_compliant(blob: bytes, page: int = PAGE_16K):
    """
    AOSP 的 16 KB 判据，两条都要满足：

      1. 每个 PT_LOAD 的 p_align >= page
      2. (p_vaddr - p_offset) % page == 0

    ⚠️ 注意第 2 条是**差值**取模，不是要求 p_offset 本身是 page 的倍数。
    只检查 p_align 会漏掉「声明了 16 KB 对齐、实际段位置没对齐」的库。
    """
    segs = elf_load_segments(blob)
    if segs is None:
        return False, ["非 ELF"]
    bad = [
        f"offset={off:#x} vaddr={va:#x} align={al:#x}"
        for off, va, al in segs
        if al < page or (va - off) % page != 0
    ]
    return not bad, bad


def zip_entry_data_offset(apk_path: str, name: str):
    """
    从 local file header 自身解析出条目数据的起始偏移。

    ⚠️ 不要用 ZipInfo.extra 反算 —— 那是 central directory 的 extra，
       zipalign 的 padding 只写在 **local** extra field 里，两者长度不同。
       照 central 算，得到一个「全部不对齐」的假结论。
    """
    with open(apk_path, "rb") as f:
        f.seek(0)
        blob = f.read()
    with zipfile.ZipFile(apk_path) as z:
        info = z.getinfo(name)
    o = info.header_offset
    if blob[o : o + 4] != b"PK\x03\x04":
        raise ValueError(f"{name}: 不是 local file header")
    nlen, elen = struct.unpack_from("<HH", blob, o + 26)
    return o + 30 + nlen + elen


# ⚠️ 与 AsrEngine.defaultThreadCount() 逐行对齐。
#    Kotlin 的 Long/Int 除法朝零取整，Python 的 // 向下取整 —— 这里被除数恒为正，
#    所以两者一致；但仍然显式写成「先整除再 clamp」以保持形状可对照。
# ⚠️ 2.1.0 删掉了 `default_thread_count()`（(核数/2).coerceIn(2,4)）——
#    App 里已经没有任何东西要挑线程数了。留一个没有消费者的 helper，
#    下一个人会以为「还有个地方在用这个公式」。


def check(apk_path: str, cpus: int, soc: str) -> int:
    print(f"APK      {os.path.basename(apk_path)}")
    print(f"目标 SoC  {soc}（按 {cpus} 核推演）")
    print()

    failed = []

    with zipfile.ZipFile(apk_path) as z:
        infos = z.infolist()
        so_entries = [i for i in infos if i.filename.endswith(".so")]

        # ---------- 0. 有没有原生库（2.1.0 起会走进这里）----------
        #
        # ⚠️ 这个分支**不能只是 return 0 就完事** —— 它必须把那三层门槛
        #    一条条说清楚「为什么不适用」，否则读日志的人只会看到一句
        #    「结论：通过」，而无法区分「检查过了没问题」与「根本没检查」。
        #    这两件事在排查时的价值完全相反：前者排除了 ABI 问题，后者什么都没排除。
        if not so_entries:
            print("==> 0. 原生库清点")
            print("    ℹ 包内 0 个 .so —— 2.1.0 起客户端没有任何原生代码")
            print("      （端侧识别 sherpa-onnx 与端侧翻译 onnxruntime 都拆了）")
            print()
            print("    ❗ 下面三项**都不适用**，不是「检查通过」：")
            print("       1. ABI 覆盖      —— 没有 .so 就没有 ABI 这回事")
            print("       2. ELF 段对齐    —— 没有 ELF 可查")
            print("       3. zip 条目对齐  —— 没有需要 mmap 的条目")
            print("       4. int8 / SME    —— onnxruntime 已不在包里")
            print("       5. 线程数推演    —— App 里已没有任何要挑线程数的东西")
            print()
            print("    为什么这个脚本还留着：原生依赖一旦回来，")
            print("    这里是**唯一**能在装机之前拦住「16 KB 页设备装不上」的地方。")
            print()
            print("✅ 结论：无需检查（包内没有任何原生库）")
            return 0

        # ---------- 1. ABI 覆盖 ----------
        print("==> 1. ABI 覆盖")
        abis = {}
        for i in so_entries:
            parts = i.filename.split("/")
            if len(parts) >= 3 and parts[0] == "lib":
                abis.setdefault(parts[1], []).append(parts[2])
        for abi, names in sorted(abis.items()):
            print(f"    {abi:14s} {len(names)} 个 .so")
        if "arm64-v8a" not in abis:
            print("    ❌ 缺 arm64-v8a —— 所有现代 Android 设备都装不上")
            failed.append("ABI")
        else:
            print(f"    ✅ 有 arm64-v8a（{len(abis['arm64-v8a'])} 个库）")
            # ⚠️★ 2.1.0：这里原来查「arm64-v8a 里有没有 ORT / ORT-JNI / sherpa-JNI
            #    三个关键库」—— 那三个是端侧识别与翻译的运行时，已经拆掉了。
            #    现在正确的判据**反过来**：它们**不该**在包里。
            #    （清单里剩下的是依赖库自己的：Compose 的 graphics.path、
            #      DataStore 的 shared_counter，各约 10 KB。）
            stale = [n for n in abis["arm64-v8a"]
                     if "onnxruntime" in n or "sherpa" in n]
            if stale:
                print(f"    ❌ 端侧推理库又回来了：{sorted(stale)}")
                failed.append("ABI")
            else:
                print("    ✅ 端侧推理库（onnxruntime / sherpa）确实不在包里")
        extra = set(abis) - {"arm64-v8a"}
        if extra:
            print(f"    ℹ 另外打包了 {sorted(extra)} —— 白占体积（这些是依赖库自己的小库）")

        # ---------- 2. ELF 段对齐 ----------
        print()
        print("==> 2. ELF 段对齐（16 KB 页设备硬性要求）")
        elf_bad = []
        for i in so_entries:
            if not i.filename.startswith("lib/arm64-v8a/"):
                continue
            ok, bad = elf_page_compliant(z.read(i))
            mark = "✅" if ok else "❌"
            print(f"    {mark} {i.filename.split('/')[-1]:34s} {'' if ok else bad[:1]}")
            if not ok:
                elf_bad.append(i.filename)
        if elf_bad:
            failed.append("ELF 段对齐")

        # ---------- 3. ZIP 条目对齐（本脚本存在的理由）----------
        print()
        print("==> 3. APK 内 zip 条目对齐")
        print("     (.so 是 STORED + 直接 mmap，条目起点必须落在 16 KB 边界)")
        zip_bad = []
        for i in so_entries:
            if not i.filename.startswith("lib/arm64-v8a/"):
                continue
            off = zip_entry_data_offset(apk_path, i.filename)
            ok16, ok4 = off % PAGE_16K == 0, off % PAGE_4K == 0
            stored = i.compress_type == zipfile.ZIP_STORED
            if not (ok16 and stored):
                zip_bad.append(i.filename)
            note = "" if ok16 else ("4 KB 对齐（老设备能跑，新设备崩）" if ok4 else "仅字节对齐")
            print(
                f"    {'✅' if ok16 and stored else '❌'} "
                f"{i.filename.split('/')[-1]:34s} off={off:#10x} "
                f"{'STORED' if stored else 'DEFLATED'} {note}"
            )
        if zip_bad:
            print()
            print("    ❌ 有 .so 未 16 KB 对齐 —— 这正是「开发机上好好的、")
            print("       换了 16 KB 页新机就启动闪退」的形态。")
            failed.append("zip 条目对齐")

        # ---------- 4. 推理后端与指令集 ----------
        #
        # ⚠️ 2.1.0：这一整节的对象是 libonnxruntime.so，而它已经不在包里。
        #    原来的写法会在「文件不存在」时把 `ort` 当成空字节，
        #    于是打印出「MLAS 0 次 ❌ 只有通用 C++ 路径」——
        #    **一句与技术事实无关的假警报**。假警报会让人以后不再看这个脚本，
        #    所以这里改成如实跳过，并说清「为什么可以跳过」。
        print()
        print("==> 4. 推理后端与 int8 加速")
        try:
            ort = z.read("lib/arm64-v8a/libonnxruntime.so")
        except KeyError:
            ort = None
        if ort is None:
            print("    ℹ 包内没有 libonnxruntime.so（2.1.0 起端侧推理已拆掉）")
            print("      这一节**不适用** —— 不是「检查通过」，是没有可查的对象。")
            print("      它留着是因为：ORT 加回来的那一天，SME / i8mm 这两条")
            print("      是唯一能在装机之前拦住「SIGILL / 慢十倍」的地方。")
        else:
            counts = {k: ort.count(k) for k in (b"MLAS", b"dotprod", b"i8mm", b"SME")}
            print(f"    MLAS 内核     {counts[b'MLAS']:4d} 次" + ("  ✅ 走 Arm 优化路径" if counts[b"MLAS"] else "  ❌ 只有通用 C++ 路径"))
            print(f"    dotprod(v8.2) {counts[b'dotprod']:4d} 次  Armv9 必然支持 ✅")
            print(f"    i8mm(v8.6)    {counts[b'i8mm']:4d} 次  Armv8.6+ 支持，天玑 9500 在列 ✅")
            if counts[b"SME"] == 0:
                print("    SME           0 次  ✅ 不碰 SME —— 不存在内核未开 SME 上下文导致的 SIGILL 风险")
            else:
                print(f"    SME           {counts[b'SME']} 次  ⚠️ ORT 版本变了，需确认设备内核是否开启 SME")

        # ---------- 5. 线程数推演 ----------
        # ⚠️ 2.1.0：原来这里还有第 5 节「ASR 线程数推演」—— 它把
        #    (核数/2).coerceIn(2,4) 的推演打印出来，而那套东西整块删了
        #    （App 里没有任何要挑线程数的地方，`DeviceInfo.asrThreads` 也删了）。
        #
        #    「这台机器实际给进程留了几个核」这个事实仍然有用（上面第 1 节会打），
        #    所以删掉的是**推演**，不是那个事实。


    print()
    if failed:
        print(f"❌ 结论：不通过 —— {failed}")
        return 1
    print("✅ 结论：这台机器能跑（ABI / ELF 对齐 / zip 对齐 三层门槛全过）")
    return 0


def main():
    ap = argparse.ArgumentParser(description="APK × SoC 兼容性自检")
    ap.add_argument("apk", nargs="?", default=None)
    ap.add_argument("--cpus", type=int, default=8)
    ap.add_argument("--soc", default="未知旗舰")
    a = ap.parse_args()

    apk = a.apk
    if apk is None:
        cands = sorted(glob.glob("apk/*.apk"))
        if not cands:
            print("找不到 APK，请显式给出路径")
            return 2
        apk = cands[-1]
        print(f"（未指定 APK，取最新的 {os.path.basename(apk)}）")

    return check(apk, a.cpus, a.soc)


if __name__ == "__main__":
    sys.exit(main())
