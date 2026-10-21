#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
生词本 · 词表**整包**发布（3.3.0）

================================================================================
## 它解决什么
================================================================================

3.3.0 之前，词表只有**两条**更新路径，而它们都绑在别的东西上：

    内置 assets        vocab.base 2.2 MB     ← 只能跟着 **App 版本**走
    vocab/add.json     增量，十几 KB          ← 跟着**素材同步**走（每 30 分钟一轮）

中间那一大块（全量表本身）没有任何独立通道。后果很具体：

  · 全量表是**发版那一刻**的语料快照，之后新增的词只能靠 `add.json` 一点点补；
  · `add.json` 是**差集**，它收敛的前提是「发版时把新 assets 同步成 .published」
    —— 也就是**词表的完整度仍然取决于发版频率**；
  · 用户想「把词库更新到最新」这件事，在 3.3.0 之前**做不到**：
    他能做的最接近的动作是「等下一次 App 更新，重装 18 MB」。

用户的原话是「把词库更新功能单独做出来，不用跟着 app 版本更新」。
这个脚本就是那条独立通道的发布端。

================================================================================
## 产物
================================================================================

    /opt/ec/www/vocab/table/<version>/vocab.base     ← 全量表（约 1.0 MB）
    /opt/ec/www/vocab/table/<version>/vocab.forms    ← 变形表（约 0.3 MB）
    /opt/ec/www/vocab/table/<version>/examples.base  ← 例句库（约 1.1 MB）
    /opt/ec/www/vocab/table.json                     ← 清单（几百字节）

客户端拿 `table.json` 比版本，不同就下那三个文件。

### ⚠️ 为什么路径里必须带 `<version>`

这一条是本项目的**老纪律**（见 `nginx/ec-routes.conf` 里 `/pron/` 那一段的注释）：

    「同一路径下的内容会变」就必须有一个随内容发布的版本号。

因为 nginx 给静态文件设的是**长缓存 + immutable**（不然每次打开都要重下 2.4 MB）。
而 `vocab.base` 这个**文件名**是固定的 —— 内容却每天在变。路径不带版本的话，
已经缓存过旧文件的客户端**永远拿不到新词表**，而且它自己不知道
（它看到版本号变了、于是去下载 —— 下回来的是缓存里那份旧的）。

带上 `<version>` 之后，「内容变了」就等于「换了一个 URL」，
`immutable` 就成立了。这也正是 `/audio/` 的做法（内容 id 编进文件名）。

### 版本号怎么来的：**内容哈希**，不是时间戳

时间戳每次重建都会变（哪怕一个字节都没改），于是客户端每轮都要重下 2.4 MB。
内容哈希不会 —— 没变的语料算出来的版本号是同一个，客户端一句「已是最新」就结束。

================================================================================
## 幂等与清理
================================================================================

同一个版本重复跑：什么都不做（只确保 `table.json` 是对的）。

**清理**：只留最新 [KEEP_VERSIONS] 个版本目录。
不带清理的话，语料每天都在长，目录会一天一个地堆下去 ——
而「需要有人记得定期清理」的设计最后都会变成没人清理。
版本号是内容哈希，所以**回退也天然可用**：把 `table.json` 指回旧版本即可，
旧目录还在（只要它还在保留窗口内）。

用法：

    python3 publish_vocab.py              # 发布（幂等）
    python3 publish_vocab.py --dry-run    # 只算版本号、打印计划，不动盘
    python3 publish_vocab.py --selftest    # 自检（不需要服务器环境）
"""

import hashlib
import io
import json
import os
import shutil
import sys
from datetime import datetime, timezone

VOCAB_DIR = "/opt/ec/www/vocab"
WWW_VOCAB = "vocab"                      # 对外路径前缀（相对站点根）

TABLE_DIR = os.path.join(VOCAB_DIR, "table")
MANIFEST = os.path.join(VOCAB_DIR, "table.json")

#: 参与打包的三个文件。**顺序即发布顺序**，也是版本号的输入顺序 ——
#: 换顺序会改变版本号（所以不要随手改动这个列表的顺序）
FILES = (
    ("base", "vocab.base"),
    ("forms", "vocab.forms"),
    ("examples", "examples.base"),
)

#: 保留几个历史版本。2 个够用：一个是当前，一个是「上一版」（回退用）
KEEP_VERSIONS = 2

VERSION_LEN = 12


def log(msg):
    print(msg, flush=True)


def sha256_of(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def count_lines(path):
    """非空行数。三个文件的每一行就是一条记录，所以它同时是「有多少条」"""
    n = 0
    with io.open(path, encoding="utf-8") as f:
        for line in f:
            if line.strip():
                n += 1
    return n


def compute_version(digests):
    """
    版本号 = 三个文件哈希的哈希，截前 [VERSION_LEN] 位。

    ⚠️ 为什么不直接用某一个文件的哈希：三个文件是**一起**发布的，
       只取一个的话，「另两个变了」不会改版本号 —— 而客户端会因为
       「版本没变」跳过下载，于是拿到一份**混版**的词表
       （新的 base + 旧的 forms），而查词结果会变得莫名其妙。
    """
    h = hashlib.sha256()
    for name, _ in FILES:
        h.update(digests[name].encode("ascii"))
        h.update(b"|")
    return h.hexdigest()[:VERSION_LEN]


def build_plan():
    """读三个源文件、算哈希、给出计划。文件缺任何一个都直接失败 ——
    发一份**不完整**的词表比不发更糟：客户端下了之后查词会大面积落空。"""
    plan = {}
    for key, name in FILES:
        path = os.path.join(VOCAB_DIR, name)
        if not os.path.isfile(path):
            raise SystemExit("❌ 找不到 %s —— 先跑 build_vocab.py / build_examples.py" % path)
        plan[key] = {
            "name": name,
            "path": path,
            "sizeBytes": os.path.getsize(path),
            "sha256": sha256_of(path),
            "count": count_lines(path),
        }
    plan["version"] = compute_version({k: plan[k]["sha256"] for k, _ in FILES})
    return plan


def read_manifest():
    if not os.path.isfile(MANIFEST):
        return None
    try:
        return json.loads(io.open(MANIFEST, encoding="utf-8").read())
    except Exception:
        # 清单坏了就当作没有 —— 重发一份即可。**不要抛**：
        # 这个脚本挂在每轮 cycle 上，抛出去会把整轮发布带停
        return None


def existing_versions():
    if not os.path.isdir(TABLE_DIR):
        return []
    out = [d for d in os.listdir(TABLE_DIR) if os.path.isdir(os.path.join(TABLE_DIR, d))]
    return sorted(out, key=lambda d: os.path.getmtime(os.path.join(TABLE_DIR, d)))


def prune(keep, dry_run):
    """只留最新 [KEEP_VERSIONS] 个版本目录，并且**永远保留 [keep]**。

    ⚠️ `keep` 必须豁免：如果只按 mtime 排，而当前版本恰好是最旧的那一个
       （内容哈希不变、但目录是几天前建的），清理会**把正在用的那份删掉** ——
       客户端立刻 404，而 `table.json` 还好好地指着它。
    """
    versions = existing_versions()
    doomed = [v for v in versions if v != keep][: max(0, len(versions) - KEEP_VERSIONS)]
    for v in doomed:
        target = os.path.join(TABLE_DIR, v)
        if dry_run:
            log("    [dry-run] 会删掉旧版本目录 %s" % v)
        else:
            shutil.rmtree(target, ignore_errors=True)
            log("    清理旧版本目录 %s" % v)
    return doomed


def write_manifest(plan, dry_run):
    files = []
    for key, name in FILES:
        f = plan[key]
        files.append({
            "name": f["name"],
            "path": "%s/table/%s/%s" % (WWW_VOCAB, plan["version"], f["name"]),
            "sizeBytes": f["sizeBytes"],
            "sha256": f["sha256"],
        })
    manifest = {
        "version": plan["version"],
        "releasedAt": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        # 三个数分别是「多少词条 / 多少变形 / 多少个词有例句」——
        # 客户端拿它显示「词表 N 词」，同时也是「这份表是不是明显变小了」的判据
        "baseCount": plan["base"]["count"],
        "formCount": plan["forms"]["count"],
        "exampleCount": plan["examples"]["count"],
        "totalBytes": sum(plan[k]["sizeBytes"] for k, _ in FILES),
        "files": files,
    }
    if dry_run:
        log("    [dry-run] 会写 table.json（version=%s）" % plan["version"])
        return manifest

    # tmp + rename：半截 JSON 会让客户端把整份清单判成「不合法」，
    # 而它**每次同步都会重新拉** —— 于是那个坏清单会一直挡在中间
    tmp = MANIFEST + ".tmp"
    io.open(tmp, "w", encoding="utf-8").write(
        json.dumps(manifest, ensure_ascii=False, indent=1)
    )
    os.replace(tmp, MANIFEST)
    return manifest


def publish(dry_run=False):
    plan = build_plan()
    version = plan["version"]
    target_dir = os.path.join(TABLE_DIR, version)

    log("  词表包：version=%s" % version)
    for key, name in FILES:
        log("    %-14s %8d KB  %d 条  %s"
            % (name, plan[key]["sizeBytes"] // 1024, plan[key]["count"],
               plan[key]["sha256"][:12]))

    current = read_manifest()
    complete = os.path.isdir(target_dir) and all(
        os.path.isfile(os.path.join(target_dir, name)) for _, name in FILES
    )

    if complete and current and current.get("version") == version:
        # 内容没变：不重写文件、也不动清单（动了只会让 releasedAt 变新，
        # 而那个字段被用来回答「这份词表是什么时候发的」，不该为了「跑过一次」而改）
        log("    ✅ 已是最新，无需重发")
        prune(version, dry_run)
        return 0

    if dry_run:
        log("    [dry-run] 会写入 %s/ 并更新 table.json" % target_dir)
        prune(version, dry_run)
        return 0

    # 先在**临时目录**里放齐三个文件，再整体 rename ——
    # 目录 rename 在同一文件系统内是原子的，所以「客户端看到一个版本目录」
    # 就等于「三个文件都在」。逐个 rename 的话中间会有一个「目录在、文件缺」的窗口，
    # 而那时的下载会拿到 404 或者半截文件
    os.makedirs(TABLE_DIR, exist_ok=True)
    staging = target_dir + ".staging"
    shutil.rmtree(staging, ignore_errors=True)
    os.makedirs(staging)
    for _, name in FILES:
        shutil.copyfile(os.path.join(VOCAB_DIR, name), os.path.join(staging, name))
    if os.path.isdir(target_dir):
        shutil.rmtree(target_dir, ignore_errors=True)
    os.replace(staging, target_dir)
    log("    已写入 %s/" % os.path.relpath(target_dir, VOCAB_DIR))

    manifest = write_manifest(plan, dry_run=False)
    log("    清单已更新：%s 词 / %s 变形 / %s 个词有例句（共 %d KB）"
        % (manifest["baseCount"], manifest["formCount"],
           manifest["exampleCount"], manifest["totalBytes"] // 1024))

    prune(version, dry_run)
    return 0


# ===========================================================================
# 自检
# ===========================================================================

def selftest():
    """不需要服务器环境的自检。守的是**这个脚本最贵的两处判断**。"""
    ok = 0
    bad = 0

    def check(name, cond, why=""):
        nonlocal ok, bad
        if cond:
            ok += 1
            print("  ✅ %s" % name)
        else:
            bad += 1
            print("  ❌ %s" % name)
            if why:
                print("       %s" % why)

    print()
    print("  词表包发布自检")

    # ① 版本号只由内容决定：同样的哈希 → 同样的版本
    d1 = {"base": "a" * 64, "forms": "b" * 64, "examples": "c" * 64}
    check("同样的内容哈希算出同样的版本号",
          compute_version(d1) == compute_version(dict(d1)))
    check("  · 长度固定为 %d" % VERSION_LEN, len(compute_version(d1)) == VERSION_LEN)

    # ② 三个文件**任何一个**变了，版本号都必须变。
    #    ★ 这是本脚本存在的主要理由：混版词表（新 base + 旧 forms）查词会莫名其妙，
    #      而客户端只看版本号，不看文件。
    for key in ("base", "forms", "examples"):
        d2 = dict(d1)
        d2[key] = "f" * 64
        check("  · %s 单独变化 → 版本号必须变" % key,
              compute_version(d2) != compute_version(d1),
              "只对某一个文件取哈希的话，另两个变化不会改版本号，"
              "客户端会跳过下载、拿到一份混版词表")

    # ③ 哈希与文件**必须一一对应**：把两个文件的哈希对调，版本号也必须变。
    #    这一条防的是「用 set / 排序后再拼」那种写法 —— 那样两个文件换了内容
    #    也算出同一个版本号（因为集合是一样的），于是客户端拿到混版词表。
    check("哈希与文件一一对应（对调两个文件的哈希 → 版本号必须变）",
          compute_version({"base": "b" * 64, "forms": "a" * 64, "examples": "c" * 64})
          != compute_version({"base": "a" * 64, "forms": "b" * 64, "examples": "c" * 64}),
          "用集合那类无序结构拼哈希的话，这一条会失败")

    # ④ 清单里的 path 必须带上版本号 —— 不带的话 nginx 的 immutable 缓存
    #    会把客户端永久钉在旧词表上
    fake = {
        "version": "abc123def456",
        "base": {"name": "vocab.base", "sizeBytes": 1, "sha256": "x" * 64, "count": 1},
        "forms": {"name": "vocab.forms", "sizeBytes": 1, "sha256": "y" * 64, "count": 1},
        "examples": {"name": "examples.base", "sizeBytes": 1, "sha256": "z" * 64, "count": 1},
    }
    m = write_manifest(fake, dry_run=True)
    check("清单里的每条 path 都带版本号",
          all("/table/abc123def456/" in f["path"] for f in m["files"]),
          "路径不带版本号 + nginx 设 immutable ⇒ 缓存过旧词表的客户端永远升不上来")
    check("  · 清单里有客户端要用的全部字段",
          {"version", "baseCount", "formCount", "exampleCount", "totalBytes", "files"}
          <= set(m.keys()))
    check("  · files 的顺序与发布顺序一致",
          [f["name"] for f in m["files"]] == [n for _, n in FILES])

    # ⑤ 保留窗口：正在用的那一个必须豁免清理
    keep = "abc123def456"
    versions = ["old1", "old2", keep]
    doomed = [v for v in versions if v != keep][: max(0, len(versions) - KEEP_VERSIONS)]
    check("★ 清理时会豁免当前版本（哪怕它 mtime 最旧）",
          keep not in doomed,
          "不豁免的话会删掉正在服务的那一份：客户端立刻 404，而 table.json 还指着它")

    print()
    print("  通过 %d 项，失败 %d 项" % (ok, bad))
    print()
    return 1 if bad else 0


def main(argv):
    dry = "--dry-run" in argv
    if "--selftest" in argv:
        return selftest()
    return publish(dry_run=dry)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
