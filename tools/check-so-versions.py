#!/usr/bin/env python3
"""
校验一组 .so 的**符号版本需求**是否都能被同目录里实际发货的库满足。

## 为什么需要这个断言（这是一次真实真机闪退的根因）

真机第一次加载 ASR 模型时闪退：

    UnsatisfiedLinkError: dlopen failed: cannot locate symbol "OrtGetApiBase"
    referenced by ".../libsherpa-onnx-jni.so"

而库在、符号也在：

    libsherpa-onnx-jni.so  需要  OrtGetApiBase@VERS_1.28.2  来自 libonnxruntime.so
    libonnxruntime.so      只定义 OrtGetApiBase@@VERS_1.28.0

bionic 的 linker 会校验版本名，对不上就报「找不到符号」——
文案极具误导性，看起来像「库没打进去」，实际是**版本名对不上**。

这类问题的恶劣之处在于它**只在真机上第一次 dlopen 时才炸**：
编译通过、安装成功、启动正常，构建期的 p_align / dex / assets 断言全都发现不了。
所以必须专门加一条：把「谁要求哪个版本」和「谁定义了哪个版本」在构建期对一遍。

用法：
    check-so-versions.py <含 .so 的目录>
"""
from __future__ import annotations

import struct
import sys
from pathlib import Path

# 系统库：不在 APK 里，由系统提供，跳过校验
SYSTEM_LIBS = {
    'libc.so', 'libm.so', 'libdl.so', 'liblog.so', 'libandroid.so', 'libz.so',
    'libstdc++.so', 'libc++_shared.so', 'libc++abi.so', 'libEGL.so',
    'libGLESv2.so', 'libGLESv3.so', 'libnativewindow.so', 'libsync.so',
    'libvulkan.so', 'libOpenSLES.so', 'libjnigraphics.so', 'libatomic.so',
    'libunwind.so', 'libbase.so', 'libutils.so', 'libcutils.so',
    'libhardware.so', 'libhidlbase.so', 'libui.so', 'libgui.so',
    'libbinder.so', 'libpng.so', 'libjpeg.so', 'libwebp.so',
}


class So:
    def __init__(self, path: Path):
        self.path = path
        self.d = path.read_bytes()
        d = self.d
        if d[:4] != b'\x7fELF':
            raise ValueError('不是 ELF')
        if d[4] != 2:
            raise ValueError('只支持 ELF64')
        self.e = '<' if d[5] == 1 else '>'
        e = self.e
        self.sh_off = struct.unpack_from(e + 'Q', d, 0x28)[0]
        self.sh_entsize = struct.unpack_from(e + 'H', d, 0x3A)[0]
        self.sh_num = struct.unpack_from(e + 'H', d, 0x3C)[0]
        self.sh_strndx = struct.unpack_from(e + 'H', d, 0x3E)[0]
        secs = []
        for i in range(self.sh_num):
            off = self.sh_off + i * self.sh_entsize
            f = struct.unpack_from(e + 'IIQQQQIIQQ', d, off)
            secs.append(dict(name_off=f[0], offset=f[4], size=f[5]))
        shstr = secs[self.sh_strndx]
        self.secs = {}
        for s in secs:
            start = shstr['offset'] + s['name_off']
            s['name'] = bytes(d[start:d.index(b'\0', start)]).decode()
            self.secs[s['name']] = s
        self._strs = None

    @property
    def strings(self) -> dict[int, str]:
        """相对被链接字符串表的偏移 → 字符串。"""
        if self._strs is None:
            s = self.secs.get('.dynstr')
            self._strs = {}
            if s:
                blob = self.d[s['offset']:s['offset'] + s['size']]
                i = 0
                while i < len(blob):
                    j = blob.find(b'\0', i)
                    if j < 0:
                        break
                    if j > i:
                        self._strs[i] = blob[i:j].decode()
                    i = j + 1
        return self._strs

    def _at(self, off: int) -> str:
        return self.strings.get(off, '')

    def soname(self) -> str:
        dyn = self.secs.get('.dynamic')
        if not dyn:
            return self.path.name
        n = dyn['size'] // 16
        for i in range(n):
            tag, val = struct.unpack_from(self.e + 'qQ', self.d, dyn['offset'] + i * 16)
            if tag == 0:
                break
            if tag == 14:
                return self._at(val)
        return self.path.name

    def defined_versions(self) -> set[str]:
        vd = self.secs.get('.gnu.version_d')
        if not vd:
            return set()
        out, off = set(), vd['offset']
        while True:
            (_ver, vd_flags, _ndx, vd_cnt, _hash,
             vd_aux, vd_next) = struct.unpack_from(self.e + 'HHHHIII', self.d, off)
            aux = off + vd_aux
            for k in range(vd_cnt):
                vda_name, vda_next = struct.unpack_from(self.e + 'II', self.d, aux)
                if k == 0 and not (vd_flags & 1):      # 跳过 VER_FLG_BASE
                    out.add(self._at(vda_name))
                if vda_next == 0:
                    break
                aux += vda_next
            if vd_next == 0:
                break
            off += vd_next
        return out

    def required_versions(self) -> list[tuple[str, str]]:
        """[(依赖库名, 版本名)]"""
        vr = self.secs.get('.gnu.version_r')
        if not vr:
            return []
        out, off = [], vr['offset']
        while True:
            (_ver, vn_cnt, vn_file, vn_aux,
             vn_next) = struct.unpack_from(self.e + 'HHIII', self.d, off)
            fname = self._at(vn_file)
            aux = off + vn_aux
            for _ in range(vn_cnt):
                (_hash, _flags, _other, vna_name,
                 vna_next) = struct.unpack_from(self.e + 'IHHII', self.d, aux)
                out.append((fname, self._at(vna_name)))
                if vna_next == 0:
                    break
                aux += vna_next
            if vn_next == 0:
                break
            off += vn_next
        return out


def main() -> int:
    if len(sys.argv) != 2:
        print(__doc__)
        return 2
    d = Path(sys.argv[1])
    files = sorted(p for p in d.glob('*.so') if p.is_file())
    if not files:
        print(f'❌ {d} 下没有 .so')
        return 1

    sos = []
    for p in files:
        try:
            sos.append(So(p))
        except Exception as exc:                       # noqa: BLE001
            print(f'❌ {p.name}: {exc}')
            return 1

    provided: dict[str, set[str]] = {}
    for s in sos:
        provided.setdefault(s.soname(), set()).update(s.defined_versions())

    print(f'    发货 {len(sos)} 个 .so，其中带版本定义的：')
    for name in sorted(provided):
        vers = sorted(provided[name])
        if vers:
            print(f'      {name:<32} {", ".join(vers)}')
    print()

    bad = 0
    skipped = 0
    for s in sos:
        reqs = [r for r in set(s.required_versions()) if r[0] not in SYSTEM_LIBS]
        if not reqs:
            continue
        print(f'    {s.soname()}')
        for dep, ver in sorted(reqs):
            if dep not in provided:
                # 依赖的库不在这批发货里，且不是已知系统库 —— 值得提一句
                print(f'      ⚠️  {ver:<16} ← {dep}（不在本目录，也不是已知系统库）')
                skipped += 1
            elif ver in provided[dep]:
                print(f'      ✅ {ver:<16} ← {dep}')
            else:
                print(f'      ❌ {ver:<16} ← {dep}  未定义！'
                      f'（{dep} 只有 {sorted(provided[dep]) or "无版本定义"}）')
                bad += 1
    print()
    if bad:
        print(f'❌ {bad} 条符号版本需求无法满足 —— 真机 dlopen 会报 '
              f'"cannot locate symbol ... referenced by ..."')
        print('   若来自 sherpa AAR：跑 FORCE=1 ./tools/vendor-sherpa-aar.sh 重新对齐')
        return 1
    print(f'✅ 所有 .so 的符号版本需求都能被满足（跳过 {skipped} 条系统库依赖）')
    return 0


if __name__ == '__main__':
    sys.exit(main())
