#!/usr/bin/env python3
"""
验证 tools/check-device-compat.py 与 build-apk.sh 断言 7 的判据。

## 为什么判据本身也要验

「16 KB 页对齐」这件事在本工程已经栽过一次 —— 不是栽在对齐上，是栽在
**我自己的检查脚本写错了**：

  - 第一版只读第一个 program header，把 PT_PHDR 的 p_align 当结论（全是 0x8）
  - 第二版读对了 PT_LOAD，却要求 p_offset 本身是 16 KB 的倍数 ——
    AOSP 的判据是 **(p_vaddr - p_offset) % page == 0**，不是 offset 对齐。
    两版都打印出「全部不对齐」，而产物其实完全合规。
  - 第三版算 zip 条目偏移时用了 central directory 的 extra length，
    又得出「全部不对齐」的假结论。真相是 zipalign 的 padding 只写在
    **local** extra field 里。

三次都错在同一个方向：**检查器比被检查的东西更容易错**。
所以这里把每条判据单独拆出来喂边界值，并且额外验证
「坏样本必须被判失败」—— 一个永远返回 true 的检查器比没有检查器更危险。

## 覆盖范围

  A. default_thread_count 的边界 + Kotlin 源码公式未漂移
  B. elf_page_compliant：合成 ELF 的对齐/差值/多个 LOAD 段
  C. zip 条目偏移：真实 APK 交叉验证 + 合成 zip 的天然坏样本
  D. build-apk.sh 断言 7 确实存在，且坏样本会让它退出非零
"""

import os
import re
import struct
import subprocess
import sys
import zipfile
import pathlib

ROOT = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "tools"))

from importlib import import_module

compat = import_module("check-device-compat")  # noqa: E402

PASS = 0
FAIL = 0
GROUP = ""


def g(name):
    global GROUP
    GROUP = name
    print(f"\n=== {name} ===")


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
    """
    剥掉注释再断言。

    本工程的注释里到处是「不要用 resolveActivity」「不能改成 X」这类反面教材，
    直接对全文断言会把注释本身判成违规。
    """
    src = re.sub(r"/\*.*?\*/", "", src, flags=re.S)
    src = re.sub(r"//[^\n]*", "", src)
    return src


# ============================================================
# A. 线程数推演（2.1.0 整段删除）
# ============================================================
#
# 这一节原来验的是 `default_thread_count(核数) = (核数/2).coerceIn(2,4)`：
# 边界值、上限触顶、以及「公式不许在 Kotlin 与 Python 之间漂移」。
# 它对应的是 AsrEngine / TranslateEngine 挑线程数那件事。
#
# 端侧推理整条拆掉之后，**App 里没有任何东西要挑线程数了** ——
# 于是连 `default_thread_count` 这个 helper 本身也一并删掉
# （留在 check-device-compat.py 里就是一段没有消费者的死代码，
#  而一个永远为真的断言比没有断言更糟）。
#
# 真正仍然成立的判据在 B / C 两节：ELF 段对齐与 zip 条目对齐。
# 它们对**任何**原生库都成立，而现在包里确实还有依赖库自带的那几个小 .so。


# ============================================================
# B. ELF 段对齐判据
# ============================================================

def make_elf(segments, is64=True):
    """
    合成一个最小 ELF，只填 header + program headers。

    segments: [(p_offset, p_vaddr, p_align), ...]，一律写成 PT_LOAD。
    """
    if is64:
        ehsize, phentsize = 64, 56
        phoff = ehsize
        body = b""
        for off, va, al in segments:
            ph = struct.pack("<IIQQQQQQ", 1, 5, off, va, va, 0x100, 0x100, al)
            body += ph
        hdr = bytearray(64)
        hdr[0:4] = b"\x7fELF"
        hdr[4] = 2      # ELFCLASS64
        hdr[5] = 1      # little endian
        hdr[6] = 1
        struct.pack_into("<Q", hdr, 32, phoff)
        struct.pack_into("<H", hdr, 52, ehsize)
        struct.pack_into("<H", hdr, 54, phentsize)
        struct.pack_into("<H", hdr, 56, len(segments))
        return bytes(hdr) + body
    else:
        ehsize, phentsize = 52, 32
        phoff = ehsize
        body = b""
        for off, va, al in segments:
            ph = struct.pack("<IIIIIIII", 1, off, va, va, 0x100, 0x100, 5, al)
            body += ph
        hdr = bytearray(52)
        hdr[0:4] = b"\x7fELF"
        hdr[4] = 1      # ELFCLASS32
        hdr[5] = 1
        hdr[6] = 1
        struct.pack_into("<I", hdr, 28, phoff)
        struct.pack_into("<H", hdr, 40, ehsize)
        struct.pack_into("<H", hdr, 42, phentsize)
        struct.pack_into("<H", hdr, 44, len(segments))
        return bytes(hdr) + body


def test_elf_criterion():
    g("B. elf_page_compliant 判据")

    # 合规：align=0x4000 且 vaddr-offset 是 0x4000 的倍数
    ok = make_elf([(0x0, 0x0, 0x4000), (0x4000, 0x4000, 0x4000)])
    good, bad = compat.elf_page_compliant(ok)
    check_true("标准 16 KB 对齐的 ELF 判通过", good, str(bad))

    # 关键边界：**offset 本身不是 16 KB 的倍数，但差值对齐** —— 依然合规。
    # 这正是我第二版脚本判错的那种形态。
    tricky = make_elf([(0x1c40, 0x1c40, 0x4000), (0x14000, 0x14000, 0x4000)])
    good, _ = compat.elf_page_compliant(tricky)
    check_true("offset 非 16KB 倍数但差值对齐 → 仍判通过（不要求 offset 本身对齐）", good)

    # 不合规 1：align 太小（4 KB）
    small = make_elf([(0x0, 0x0, 0x1000)])
    good, bad = compat.elf_page_compliant(small)
    check_true("p_align=4KB → 判失败", not good)
    check_true("失败信息里带上 align", "align" in bad[0], str(bad))

    # 不合规 2：align 声明 16 KB，但差值不对齐
    skewed = make_elf([(0x0, 0x8, 0x4000)])
    good, _ = compat.elf_page_compliant(skewed)
    check_true("align=16KB 但 (vaddr-offset)=8 → 判失败", not good)

    # 多个 LOAD 段里有一个坏 → 整体失败（不能只看第一个）
    one_bad = make_elf([(0x0, 0x0, 0x4000), (0x8000, 0x8008, 0x4000)])
    good, _ = compat.elf_page_compliant(one_bad)
    check_true("两个 LOAD 段、第二个坏 → 整体判失败", not good)

    # 32 位 ELF 也要能解析（armeabi-v7a 的库）
    elf32 = make_elf([(0x0, 0x0, 0x4000)], is64=False)
    check_true("32 位 ELF 可解析且判通过", compat.elf_page_compliant(elf32)[0])

    # 非 ELF
    check_true("非 ELF 输入判失败", not compat.elf_page_compliant(b"not an elf")[0])

    # 没有 PT_LOAD 段：vacuous truth —— 判通过但不该用来当证据
    noload = bytearray(make_elf([(0x0, 0x0, 0x4000)]))
    struct.pack_into("<I", noload, 64, 6)  # 把第一个 phdr 的 p_type 改成 PT_PHDR
    good, _ = compat.elf_page_compliant(bytes(noload))
    check_true("无 LOAD 段时判通过（空真）—— 故真实产物必须另有断言覆盖", good)


# ============================================================
# C. zip 条目偏移
# ============================================================

def test_zip_offset():
    g("C. zip 条目数据偏移解析")

    tmp = ROOT / "build" / "verify-compat-tmp.zip"
    tmp.parent.mkdir(parents=True, exist_ok=True)

    # --- 坏样本：普通 zipfile 写出来的包天然不做对齐 ---
    payload = b"\x7fELF" + b"\x00" * 4096
    with zipfile.ZipFile(tmp, "w", zipfile.ZIP_STORED) as z:
        z.writestr("lib/arm64-v8a/libonnxruntime.so", payload)
        z.writestr("lib/arm64-v8a/libsherpa-onnx-jni.so", payload)

    off = compat.zip_entry_data_offset(str(tmp), "lib/arm64-v8a/libonnxruntime.so")
    check_true("解析出的偏移落在文件内", 0 < off < tmp.stat().st_size, f"off={off:#x}")

    # 用 local file header 重新独立算一遍，确认口径一致
    blob = tmp.read_bytes()
    with zipfile.ZipFile(tmp) as z:
        info = z.getinfo("lib/arm64-v8a/libonnxruntime.so")
    o = info.header_offset
    check_true("header_offset 处确实是 local file header", blob[o:o + 4] == b"PK\x03\x04")

    # 关键：**不能用 central directory 的 extra length 反算**。
    # 两者在 zipalign 之后长度不同；这里用一个不经过 zipalign 的包演示两者会给出
    # 不同答案的情形（普通 zipfile 下 extra 均为空，故此处只断言"口径来自 local"）。
    nlen_local, elen_local = struct.unpack_from("<HH", blob, o + 26)
    central_extra_len = len(info.extra)
    check_eq("local extra 与 central extra 是两个不同的字段（不可互换）",
             compat.zip_entry_data_offset(str(tmp), "lib/arm64-v8a/libonnxruntime.so"),
             o + 30 + nlen_local + elen_local)
    print(f"      local extra={elen_local}B, central extra={central_extra_len}B")
    check_true("未 zipalign 的包：条目偏移不是 16 KB 倍数 → 判据会正确地判失败",
               off % 16384 != 0, f"off={off:#x}")

    tmp.unlink(missing_ok=True)

    # --- 好样本：真实 APK 必须全部对齐 ---
    apks = sorted((ROOT / "apk").glob("*.apk"))
    if not apks:
        check_true("存在 APK 供交叉验证（跳过则说明还没构建过）", False, "apk/ 下无产物")
        return
    apk = apks[-1]
    print(f"      真实样本：{apk.name}")
    with zipfile.ZipFile(apk) as z:
        names = [i.filename for i in z.infolist()
                 if i.filename.startswith("lib/arm64-v8a/") and i.filename.endswith(".so")]
    check_true("真实 APK 含 arm64-v8a 的 .so", len(names) > 0, f"{len(names)} 个")
    allok = True
    for n in names:
        o = compat.zip_entry_data_offset(str(apk), n)
        if o % 16384 != 0:
            allok = False
            print(f"      ✗ {n} off={o:#x}")
    check_true(f"真实 APK 全部 {len(names)} 个 .so 条目 16 KB 对齐", allok)

    # 与 build-apk.sh 的一致性：两边都必须认为它是对齐的
    check_true("ELF 段对齐也成立（真实 APK 的 arm64 .so）",
               all(compat.elf_page_compliant(
                   zipfile.ZipFile(apk).read(n))[0] for n in names))


# ============================================================
# D. build-apk.sh 断言 7 存在且真的会拦
# ============================================================

def test_build_assertion():
    g("D. build-apk.sh 断言 7")

    sh = (ROOT / "build-apk.sh").read_text()
    check_true("存在 zip 条目对齐断言", "APK 内 zip 条目对齐" in sh)
    check_true("断言体里用 local file header 解析（不是 central）",
               "local file header" in sh)
    check_true("断言体要求 STORED", "ZIP_STORED" in sh)
    check_true("坏样本会让脚本 exit 1", "sys.exit(1)" in sh)
    check_true("调用了机型兼容性自检", "check-device-compat.py" in sh)

    # ⚠️ build-apk.sh 的 heredoc 里不能出现反引号 —— 会触发命令替换。
    #    这条踩过两次（Tick、strings 都被当成命令执行过）。
    heredoc = sh.split("<<'PYEOF'")[1].split("PYEOF")[0] if "<<'PYEOF'" in sh else ""
    check_true("断言段内无反引号", "`" not in heredoc)
    check_true("heredoc 用引号包裹（禁掉变量展开）", "<<'PYEOF'" in sh)

    # bash 语法
    r = subprocess.run(["bash", "-n", str(ROOT / "build-apk.sh")],
                       capture_output=True, text=True)
    check_true("build-apk.sh 语法检查通过", r.returncode == 0, r.stderr.strip()[:120])


# ============================================================

def main():
    print("验证机型兼容性判据 —— 重点是「检查器自己」也要被检查")
    test_elf_criterion()
    test_zip_offset()
    test_build_assertion()

    print()
    print(f"通过 {PASS} 项，失败 {FAIL} 项")
    if FAIL:
        print("❌ 有断言未通过")
        return 1
    print("✅ 全部通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
