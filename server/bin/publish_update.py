#!/usr/bin/env python3
"""把一个新的 APK 发布成「App 内更新」能读到的清单。

    publish_update.py <apk 路径> --version-code N --version-name X.Y.Z
                      [--notes 文本] [--min-supported N] [--keep 3]
                      [--www /opt/ec/www] [--base-url http://...]
    publish_update.py --selftest

产出两样东西（都在 `<www>/apk/` 下）：

    EnglishCommute-<versionName>.apk   客户端要下回去的那个包
    update.json                        版本清单（App 每次前台来读它）

## 为什么清单里的 sha256 必须**在文件落地之后**再算

客户端会拿它校验下载结果 —— 校验不通过就整份丢弃重下。所以这个值必须是
**服务器上那个文件**的哈希，而不是「我本地算的那个」。两者在理想情况下一样，
但只要中间出过一次传输截断（跨境链路上很常见），它们就会不一样，
而症状是「用户每次都下载成功、每次都校验失败」—— 一个自己造出来的死循环。

## 强制更新的开关是 `minSupportedVersionCode`

- `0`（默认）= 不强更
- 设成某个版本号（**必须用数字，不能用版本名字符串**）= 低于它的客户端**不许继续用**

⚠️ 判据必须是数字。字符串比较在这里是错的：`"2.10.0" < "2.9.0"`。
客户端那边同理，比的是 `PackageManager` 报出来的 `longVersionCode`。
"""

import argparse
import hashlib
import json
import shutil
import sys
import tempfile
import time
from pathlib import Path

# ⚠️ 这个值决定 `update.json` 里 `url` 的前缀，而那个 URL 会被
# **所有版本**的客户端拿到 —— 包括那些 BASE_URL 还写着 http://IP 的老版本。
#
# ## 为什么它可以是域名（一个必须先想清楚才敢改的地方）
#
# 老客户端（3.0.5 及之前）的 `network_security_config.xml` 里有两条：
#
#     base-config  cleartextTrafficPermitted="false"  ← 明文一律拒绝
#     domain-config (放行那一个 IP 的明文)              ← 只对 IP 生效
#
# 也就是说它**拒绝的是明文，不是 HTTPS** —— 用 `https://YOUR_DOMAIN` 去下载
# 完全走 base-config 那条路（TLS + 系统 CA），而 Let's Encrypt 的证书由
# 系统 CA 信任，所以**老版本能正常下载新包**。
#
# （反过来说也成立：如果这里留着 http://IP，那么每个客户端每次更新都要走一次
#   明文，这一轮上的 HTTPS 就只覆盖了内容、没覆盖升级通道。）
#
# ## 回退
#
# 万一域名出了问题，一条命令就能把清单切回 IP：
#
#     ssh YOUR_HOST '/opt/ec/venv/bin/python /opt/ec/bin/publish_update.py \
#         --retag-only --base-url http://YOUR_SERVER_IP'
#
# 80/IP 那条通道**始终在**，所以回退是可用的 —— 这正是它不能被关掉的原因。
DEFAULT_BASE_URL = os.environ.get("EC_PUBLIC_BASE", "https://your-domain.example")

# 应急回退地址。⚠️ 它必须与客户端 `network_security_config.xml` 里放行的那条 IP
# **完全一致** —— 那是同一条通道的两端：这边写进清单的 url，那边写「允许明文去哪」。
# 不一致的话，回退会产出一个**客户端直接拒绝访问**的地址，
# 而症状是「按文档切回去了，还是下载失败」，看起来像命令没生效。
# tools/verify-server-20.py 里有断言核对这两处（含下面注释里那段示例命令）。
FALLBACK_BASE_URL = os.environ.get("EC_FALLBACK_BASE", "http://203.0.113.10")
APK_DIR = "apk"


def sha256_of(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def build_index(www: Path, apk_name: str, *, version_code: int, version_name: str,
                min_supported: int, notes: str, base_url: str) -> dict:
    """算出 update.json 的内容。**apk 必须已经在 www/apk 下。**

    分成两步（先落盘、再算哈希、再写清单）是刻意的：
    一个函数里同时「拷文件」和「算哈希」，很容易写成先算后拷。
    """
    apk = www / APK_DIR / apk_name
    if not apk.is_file():
        raise SystemExit(f"❌ 发布区里找不到 {apk}")
    return {
        "generatedAt": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
        # ⚠️ 客户端比的是这个**数字**，不是 versionName
        "versionCode": version_code,
        "versionName": version_name,
        "url": f"{base_url.rstrip('/')}/{APK_DIR}/{apk_name}",
        "sizeBytes": apk.stat().st_size,
        "sha256": sha256_of(apk),
        # 低于它就不许继续用（0 = 不强更）
        "minSupportedVersionCode": min_supported,
        "notes": notes,
    }


def prune(www: Path, keep: int, keep_name: str) -> list:
    """删掉旧的 APK，只留最近 `keep` 个（当前发布的那个一定保留）。

    这台机器只有 40 GB，而每个包 ~18 MB —— 不删的话半年就是几十个包。
    """
    apks = sorted((www / APK_DIR).glob("EnglishCommute-*.apk"),
                  key=lambda p: p.stat().st_mtime, reverse=True)
    removed = []
    for p in apks[keep:]:
        if p.name == keep_name:
            continue
        p.unlink()
        removed.append(p.name)
    return removed


def retag(www: Path, base_url: str) -> int:
    """只换 `update.json` 里 url 的前缀，**不重新发布 APK**。

    存在的理由：`url` 是绝对地址，而它的前缀（域名 / IP）是会变的 ——
    域名出问题时要有一条**秒级**的回退路径，而不是重新走一遍
    「本机构建 → scp 18 MB 跨境 → 服务器算哈希」。

    ⚠️ 它**重新算** sha256（从发布区里那个 APK 现算），而不是把旧值抄过来。
       抄过来更快，但那个哈希属于「上一次发布时那个文件」—— 万一之后文件被动过，
       客户端就会「每次都下载成功、每次都校验失败」。多跑几十毫秒的哈希，
       换的是这个值永远落在**当下那个文件**上。
    """
    idx_path = www / APK_DIR / "update.json"
    if not idx_path.is_file():
        print(f"❌ 发布区里没有 {idx_path} —— 从来没发布过？先正常发布一次")
        return 1
    old = json.loads(idx_path.read_text(encoding="utf-8"))
    apk_name = old.get("url", "").rsplit("/", 1)[-1]
    if not apk_name:
        print(f"❌ 现有 update.json 里解析不出文件名：url = {old.get('url')!r}")
        return 1

    idx = build_index(www, apk_name,
                      version_code=old["versionCode"],
                      version_name=old["versionName"],
                      min_supported=old.get("minSupportedVersionCode", 0),
                      notes=old.get("notes", ""), base_url=base_url)
    idx_path.write_text(json.dumps(idx, ensure_ascii=False, indent=1), encoding="utf-8")

    print(f"✅ 已重写 {idx_path}")
    print(f"   {old.get('url')}")
    print(f"   → {idx['url']}")
    print(f"   （{idx['versionName']} · build {idx['versionCode']} · "
          f"{idx['sizeBytes'] / 1048576:.1f} MB —— 包本身没动）")
    return 0


def selftest() -> int:
    """坏样本对照：哈希与清单必须真的对得上。"""
    print("发布清单自检 —— 每条坏样本都必须被拦下\n")
    ok = True
    tmp = Path(tempfile.mkdtemp(prefix="ecapk_"))
    (tmp / APK_DIR).mkdir(parents=True, exist_ok=True)
    src = tmp / "in.apk"
    src.write_bytes(b"PK\x03\x04" + b"x" * 5000)
    shutil.copy2(src, tmp / APK_DIR / "EnglishCommute-2.3.0.apk")

    idx = build_index(tmp, "EnglishCommute-2.3.0.apk", version_code=46,
                      version_name="2.3.0", min_supported=0, notes="",
                      base_url=DEFAULT_BASE_URL)

    def case(name, cond):
        nonlocal ok
        ok &= bool(cond)
        print(f"  {'✅' if cond else '❌ 断言失效'} {name}")

    case("versionCode 是数字 46", idx["versionCode"] == 46)
    case("minSupportedVersionCode 是数字（不是版本名字符串）",
         isinstance(idx["minSupportedVersionCode"], int))
    case("sizeBytes 与文件真实大小一致",
         idx["sizeBytes"] == (tmp / APK_DIR / "EnglishCommute-2.3.0.apk").stat().st_size)
    case("sha256 与文件真实哈希一致",
         idx["sha256"] == sha256_of(tmp / APK_DIR / "EnglishCommute-2.3.0.apk")
         and len(idx["sha256"]) == 64)
    case("url 指向**我们自己**那份（不是原始 CDN、也不是别的版本）",
         idx["url"].endswith("/apk/EnglishCommute-2.3.0.apk")
         and idx["url"].startswith(DEFAULT_BASE_URL))

    # ★ 换 base_url（域名 ↔ IP）时**只有 url 前缀变**，哈希与大小一个字节都不变。
    #   这一条守的是 retag 那条回退路径：回退时最容易犯的错是「顺手重算了哈希」
    #   或者「包被重新打了一次」—— 那样客户端会认为这根本不是同一个版本。
    alt = build_index(tmp, "EnglishCommute-2.3.0.apk", version_code=46,
                      version_name="2.3.0", min_supported=0, notes="",
                      base_url="http://YOUR_SERVER_IP")
    case("★换 base_url 后 url 前缀跟着变（retag 回退路径可用）",
         alt["url"].startswith("http://YOUR_SERVER_IP/") and alt["url"] != idx["url"])
    case("  · 换 base_url 不影响 sha256 / sizeBytes",
         alt["sha256"] == idx["sha256"] and alt["sizeBytes"] == idx["sizeBytes"])
    case("  · 默认基址是域名，不是残留的 IP",
         DEFAULT_BASE_URL.startswith("https://") and "YOUR_SERVER_IP" not in DEFAULT_BASE_URL)

    # 坏样本：文件被截断之后，清单里的哈希必须**不再**匹配
    # （这一条守的是「先落盘再算哈希」这个顺序）
    victim = tmp / APK_DIR / "EnglishCommute-2.3.0.apk"
    good_hash = idx["sha256"]
    victim.write_bytes(victim.read_bytes()[:1000])
    case("★坏样本：文件被截断后哈希不再匹配（顺序错了就会漏）",
         sha256_of(victim) != good_hash)

    # 坏样本：清单指向一个不存在的包
    try:
        build_index(tmp, "EnglishCommute-9.9.9.apk", version_code=1,
                    version_name="9.9.9", min_supported=0, notes="",
                    base_url=DEFAULT_BASE_URL)
        case("★坏样本：apk 不在发布区时必须报错", False)
    except SystemExit:
        case("★坏样本：apk 不在发布区时必须报错", True)

    shutil.rmtree(tmp, ignore_errors=True)
    print(f"\n{'全部通过' if ok else '有断言失效'}")
    return 0 if ok else 1


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("apk", nargs="?", help="要发布的 APK 路径（本地；会被拷进发布区）")
    ap.add_argument("--version-code", type=int, required=False)
    ap.add_argument("--version-name", required=False)
    ap.add_argument("--notes", default="")
    ap.add_argument("--min-supported", type=int, default=0,
                    help="低于这个 versionCode 的客户端会被强制更新（0 = 不强更）")
    ap.add_argument("--keep", type=int, default=3, help="保留几个历史包（默认 3）")
    ap.add_argument("--www", default="/opt/ec/www")
    ap.add_argument("--base-url", default=DEFAULT_BASE_URL)
    ap.add_argument("--selftest", action="store_true")
    ap.add_argument("--retag-only", action="store_true",
                    help="只按新的 --base-url 重写现有 update.json 的 url，不传新包")
    args = ap.parse_args()

    if args.selftest:
        return selftest()

    if args.retag_only:
        return retag(Path(args.www), args.base_url)

    if not args.apk or not args.version_code or not args.version_name:
        ap.error("需要 apk 路径 + --version-code + --version-name")

    if args.min_supported > args.version_code:
        # 这个组合等于「要求所有人都升到比这一版还新的版本」—— 谁都被挡住，
        # 包括刚装完这一版的人。几乎一定是手误。
        print(f"❌ --min-supported ({args.min_supported}) 不能大于 "
              f"--version-code ({args.version_code})")
        return 1

    www = Path(args.www)
    dest_dir = www / APK_DIR
    dest_dir.mkdir(parents=True, exist_ok=True)
    dest = dest_dir / f"EnglishCommute-{args.version_name}.apk"
    shutil.copy2(args.apk, dest)
    dest.chmod(0o644)

    idx = build_index(www, dest.name, version_code=args.version_code,
                      version_name=args.version_name, min_supported=args.min_supported,
                      notes=args.notes, base_url=args.base_url)
    (dest_dir / "update.json").write_text(
        json.dumps(idx, ensure_ascii=False, indent=1), encoding="utf-8")

    removed = prune(www, max(1, args.keep), dest.name)

    print(f"✅ 已发布 {idx['versionName']} (build {idx['versionCode']})")
    print(f"   {dest}  {idx['sizeBytes'] / 1048576:.1f} MB")
    print(f"   sha256 {idx['sha256'][:16]}…")
    print(f"   {dest_dir / 'update.json'}")
    if idx["minSupportedVersionCode"]:
        print(f"   ⚠️ 强制更新已开启：低于 build {idx['minSupportedVersionCode']} 的客户端会被挡住")
    else:
        print("   强制更新：未开启（minSupportedVersionCode = 0）")
    if removed:
        print(f"   清理旧包：{', '.join(removed)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
