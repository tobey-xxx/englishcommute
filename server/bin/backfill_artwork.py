#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把**已经发布**的节目的封面补进 /opt/ec/www/art/，并改写 index.json（4.4.0）。

## 为什么需要它

`rehost_artwork()` 只在**新一轮发布**时执行，而库里已有的节目还指向原站点
（`ichef.bbci.co.uk` / `www.cbc.ca` …）—— 大陆手机上拉不到，表现就是
「少数源有封面、其它源是字母块」。这个脚本一次性把存量补齐。

## 三条与 rehost 一致的约束

- 幂等：文件名 = `<eid>.jpg`，已经有且 > 1 KB 的直接跳过；
- 失败不阻断：单条失败只记一行，原地址留着（客户端会退化成台标）；
- 原子写 index.json：先写 .tmp 再 rename，避免客户端读到半截 JSON。

用法（在服务器上）：`/opt/ec/venv/bin/python /opt/ec/bin/backfill_artwork.py`
"""
import importlib.util
import json
import os
import sys
from pathlib import Path

sys.path.insert(0, "/opt/ec/bin")
spec = importlib.util.spec_from_file_location("cycle", "/opt/ec/bin/cycle.py")
cy = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cy)

INDEX = Path("/opt/ec/www/index.json")


def main() -> int:
    data = json.loads(INDEX.read_text(encoding="utf-8"))
    # ⚠️ 键名是 `episodes`（不是 `items`）—— 我第一次写错，脚本静默变成「共 0 条」
    items = data.get("episodes") or []
    print(f"共 {len(items)} 条，开始补封面…")
    done = skipped = failed = 0
    for it in items:
        url = (it.get("artworkUrl") or "").strip()
        eid = it.get("id") or ""
        if not url or not eid:
            skipped += 1
            continue
        if url.startswith(cy.PUBLIC_BASE):
            skipped += 1          # 已经是托管地址
            continue
        new = cy.rehost_artwork(url, eid)
        if new and new != url:
            it["artworkUrl"] = new
            done += 1
        elif not new:
            # 封面确实抓不到 —— 留原地址（客户端会退化成台标）
            failed += 1
        else:
            skipped += 1
    tmp = INDEX.with_suffix(".json.tmp")
    tmp.write_text(json.dumps(data, ensure_ascii=False), encoding="utf-8")
    tmp.replace(INDEX)
    os.system("chown www-data:www-data /opt/ec/www/index.json 2>/dev/null")
    print(f"✅ 补齐 {done} 条，跳过 {skipped} 条，抓不到 {failed} 条")
    return 0


if __name__ == "__main__":
    sys.exit(main())
