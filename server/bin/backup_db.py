#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
每日备份用户库（打卡记录 / 账号）。由 `ec-backup.timer` 每天调一次。

## 为什么用 `sqlite3.Connection.backup` 而不是 `cp`

`cp` 一个**正在被写**的库（我们是 WAL 模式）会拿到一个「看起来完整、
其实少了最后几个事务」的文件 —— 而「备份文件存在」恰恰是我们判断备份
成功与否的那条判据。**判据落在文件存在上，就会漏掉这种最坏的情况。**

`.backup()` 是 SQLite 自己的在线备份 API：在事务边界上取一致快照，
不需要停服务、不需要锁表。

## 两条判据（都不是「文件在不在」）

1. 备份完立刻 `PRAGMA integrity_check` 打开一遍 —— **能打开的备份才算备份**；
2. 数一遍里面的账号行数，并打出来。数字变了或读不出来，日志里一眼看得见。

## 保留策略

最近 14 份轮转。⚠️ 刻意**不做**「每月留一份」那种花活：这个库很小（几百 KB），
真出事时你要的是「最近这几天里随便挑一份」，而不是一份三个月前的。
"""
import glob
import os
import sqlite3
import sys
import time

DB = os.environ.get("EC_DB", "/opt/ec/data/ec.db")
DEST = os.environ.get("EC_BACKUP_DIR", "/opt/ec/data/backup")
KEEP = 14


def main() -> int:
    if not os.path.isfile(DB):
        print("没有库文件 %s —— 还没人注册过，没什么可备份的。" % DB)
        return 0
    os.makedirs(DEST, exist_ok=True)

    stamp = time.strftime("%Y%m%d-%H%M")
    out = os.path.join(DEST, "ec-%s.db" % stamp)

    src = sqlite3.connect(DB, timeout=15)
    dst = sqlite3.connect(out)
    try:
        with dst:
            src.backup(dst)
    finally:
        dst.close()
        src.close()

    # ★ 判据一：备份**能用**吗（不是「文件在不在」）
    users = -1
    try:
        chk = sqlite3.connect(out)
        verdict = chk.execute("PRAGMA integrity_check").fetchone()[0]
        try:
            users = int(chk.execute("SELECT COUNT(*) FROM users").fetchone()[0])
        except sqlite3.Error:
            users = -1          # 表还不存在（4.0.0 之前建的老库）—— 不算错
        chk.close()
    except sqlite3.Error as e:
        print("备份无法打开：%s（这一次的备份不要留）" % e)
        os.unlink(out)
        return 1

    # 轮转
    files = sorted(glob.glob(os.path.join(DEST, "ec-*.db")))
    removed = []
    for f in files[:-KEEP]:
        os.remove(f)
        removed.append(os.path.basename(f))

    size_kb = os.path.getsize(out) / 1024
    print("备份 %s · %.1f KB · 完整性 %s · 账号 %s 个 · 现有 %d 份%s"
          % (os.path.basename(out), size_kb, verdict,
             users if users >= 0 else "（表还没有）",
             len(files) - len(removed),
             ("· 清理 " + "、".join(removed)) if removed else ""))
    return 0


if __name__ == "__main__":
    sys.exit(main())
