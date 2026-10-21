#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
3.1.0 · 域名 + TLS 的契约锁。**这个脚本要在改动前后各跑一次。**

================================================================================
## 这一轮最难的地方不是「让 HTTPS 能用」，而是「别把 80 端口弄坏」
================================================================================

`http://YOUR_SERVER_IP` 这条通道**不能停**，原因是一个先有鸡还是先有蛋的死结：

    App 内更新的清单（/apk/update.json）在服务器上，
    而**已经装在手机里的 3.0.5 只会去 http://YOUR_SERVER_IP 取它**。

    所以：域名生效 → 新版本才存在 → 但新版本要靠老版本去 IP 上把它拉下来。
    80 端口一关，所有老客户端**永远升不了级**，而且没有任何补救手段。

于是 nginx 的结构是「**加法**」而不是「替换」：

    :80  default_server  ← 原样不动。老客户端 + 升级通道的生命线
    :80  YOUR_DOMAIN     ← 新增：ACME 质询 + 301 跳 https
    :443 ssl YOUR_DOMAIN ← 新增：内容（与 80 同一套 location）

而「原样不动」不能靠肉眼确认 —— 它就是本脚本 `--capture` / `--compare` 存在的理由：
把改动前 80 端口上每一条路径的**状态码 / Content-Type / Cache-Control / Accept-Ranges
/Access-Control-Allow-Origin** 全部录下来，改动后逐条比对，**任何预期外的差异都算失败**。

⚠️ 反过来同样重要：写进 `EXPECTED` 的变更如果**没有发生**，也算失败
（否则「我以为改生效了」会变成一句没人验证的话）。

==============================================================================
## 用法
==============================================================================

    python3 tools/verify-tls-30.py --capture testdata/tls-30-baseline.json   # 改之前
    python3 tools/verify-tls-30.py --compare testdata/tls-30-baseline.json   # 改之后
    python3 tools/verify-tls-30.py                                           # 全套（含域名 443）

前两个模式**不需要 DNS**（都在服务器本地 127.0.0.1 上探，避免跨境链路抖动
让「缓存头少了一个空格」这种噪声把真问题淹掉）。默认模式才需要域名已解析。
"""

import argparse
import json
import os
import socket
import ssl
import subprocess
import sys
import time
import urllib.error
import urllib.request

HOST_IP = "YOUR_SERVER_IP"
DOMAIN = "YOUR_DOMAIN"
EC_HOST = os.environ.get("EC_HOST", "ec")

PASS = 0
FAIL = 0
FAILURES = []


def check(name, ok, why=""):
    global PASS, FAIL
    if ok:
        PASS += 1
        print("  \u2713 %s" % name)
    else:
        FAIL += 1
        FAILURES.append(name + (("  \u2190 " + why) if why else ""))
        print("  \u2717 %s" % name)
        if why:
            print("      \u21b3 %s" % why)


def section(title):
    print()
    print("=" * 78)
    print(title)
    print("=" * 78)


# ===========================================================================
# 远端探测：在服务器本地打 127.0.0.1，但**明确带上 Host: <IP>**
# ===========================================================================
#
# 为什么要带 Host 头：改造后 :80 上会多出一个 `server_name YOUR_DOMAIN` 的块。
# 不带 Host 时请求走 default_server，带了 Host: <IP> 时**同样**走 default_server
# （因为那个域名块不匹配这个 Host）—— 两种写法都行，但显式写出来能证明
# 我们探的确实是「老客户端看到的那一份」，而不是碰巧落到了别的块上。
REMOTE_SH = r"""
set -u
cd /opt/ec/www

ID=$(basename "$(ls subs/*.srt | head -1)" .srt)
AUD=$(basename "$(ls audio/*.mp3 | head -1)")
PRON=$(basename "$(ls pron/*.mp3 | head -1)")

PATHS="/ /healthz /index.json /apk/update.json"
PATHS="$PATHS /subs/${ID}.srt /subs/${ID}.words.json /subs/${ID}.zh.json"
PATHS="$PATHS /audio/${AUD} /pron/${PRON} /vocab/add.json"
# /vocab/ 下除 add.json 之外**全部**要收口（见本脚本「三、发布区收口」）。
# 动态枚举而不是写死文件名：哪天多一个中间产物，这里自动就跟上了。
for f in $(ls vocab/ 2>/dev/null | sort); do
  if [ "$f" != "add.json" ]; then PATHS="$PATHS /vocab/$f"; fi
done

HOSTHDR="Host: __HOST__"

hdr_of() {
  curl -s -o /dev/null -D - --max-time 25 -H "$HOSTHDR" "http://127.0.0.1$1"
}

probe() {
  h=$(hdr_of "$1")
  code=$(printf '%s\n' "$h" | head -1 | awk '{print $2}')
  pick() {
    printf '%s\n' "$h" | tr -d '\r' | awk -v k="$1" '
      BEGIN { k = k ":" }
      tolower($1) == k { $1 = ""; sub(/^ /, ""); print; exit }'
  }
  printf '%s\t%s\t%s\t%s\t%s\t%s\n' \
    "$1" "$code" "$(pick content-type)" "$(pick cache-control)" \
    "$(pick accept-ranges)" "$(pick access-control-allow-origin)"
}

for p in $PATHS; do probe "$p"; done

echo "---META---"
curl -s --max-time 25 -H "$HOSTHDR" http://127.0.0.1/index.json \
  | /opt/ec/venv/bin/python -c 'import json,sys; d=json.load(sys.stdin); print(d["episodes"][0].get("audioUrl",""))'
curl -s --max-time 25 -H "$HOSTHDR" http://127.0.0.1/apk/update.json \
  | /opt/ec/venv/bin/python -c 'import json,sys; print(json.load(sys.stdin).get("url",""))'
curl -s --max-time 25 -H "$HOSTHDR" http://127.0.0.1/index.json \
  | /opt/ec/venv/bin/python -c 'import json,sys; d=json.load(sys.stdin); e=d["episodes"][0]; print(e.get("srt",""))'
""".replace("__HOST__", HOST_IP)


def remote_scan():
    """在服务器上跑一遍探测。返回 {
        'probes': {path: {...}}, 'meta': {...}
    }"""
    try:
        r = subprocess.run(["ssh", "-o", "ConnectTimeout=12", EC_HOST, "bash -s"],
                           input=REMOTE_SH, text=True, capture_output=True, timeout=300)
    except subprocess.TimeoutExpired:
        sys.exit("❌ ssh 超时 —— 服务器不可达？")
    if r.returncode != 0:
        sys.exit("❌ 远端探测失败（exit %d）\n%s" % (r.returncode, r.stderr[:1200]))

    lines = r.stdout.splitlines()
    if "---META---" not in lines:
        sys.exit("❌ 远端输出里没有 ---META--- 分隔符，格式变了：\n%s" % r.stdout[:1200])
    cut = lines.index("---META---")

    probes = {}
    for line in lines[:cut]:
        parts = line.split("\t")
        if len(parts) != 6:
            continue
        path, code, ctype, cache, ranges, acao = parts
        probes[path] = {"code": code, "ctype": ctype, "cache": cache,
                        "ranges": ranges, "acao": acao}

    meta_lines = [x for x in lines[cut + 1:] if x.strip()]
    meta = {
        "audioUrl": meta_lines[0] if len(meta_lines) > 0 else "",
        "updateUrl": meta_lines[1] if len(meta_lines) > 1 else "",
        "srt": meta_lines[2] if len(meta_lines) > 2 else "",
    }
    return {"probes": probes, "meta": meta}


# ===========================================================================
# --capture
# ===========================================================================
def cmd_capture(out_path):
    section("采集 80 端口基线（改造**之前**必须跑一次）")
    snap = remote_scan()
    if not snap["probes"]:
        sys.exit("❌ 一条路径都没探到 —— 远端脚本没工作")
    snap["capturedAt"] = time.strftime("%Y-%m-%dT%H:%M:%S%z")
    snap["note"] = "3.1.0 上域名之前，http://%s 上每一条路径的响应头快照" % HOST_IP

    os.makedirs(os.path.dirname(out_path) or ".", exist_ok=True)
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(snap, f, ensure_ascii=False, indent=1)

    print("  探了 %d 条路径" % len(snap["probes"]))
    for p in sorted(snap["probes"]):
        d = snap["probes"][p]
        print("    %-42s %s  %-26s %s" % (p, d["code"], d["ctype"] or "-", d["cache"] or "-"))
    print()
    print("  audioUrl  = %s" % snap["meta"]["audioUrl"])
    print("  updateUrl = %s" % snap["meta"]["updateUrl"])
    print("  srt       = %s" % snap["meta"]["srt"])
    print()
    print("  → 已写入 %s" % out_path)
    return 0


# ===========================================================================
# 预期变更表
# ===========================================================================
#
# ⚠️ 这张表是**双向**的：表里写了要变的，结果没变 → 失败；
#    没写却变了 → 也失败。只有「恰好只变了表里这些」才算通过。
#
# 为什么必须双向：单向（只检查「没变的那部分没变」）会让「改动根本没生效」
# 一路绿灯 —— 那正是 3.0.5 清单缺 22 条锚点却一路报全过的成因。
EXPECTED_NOTE = {
    "vocab_close": "3.0.x 之前 /vocab/ 下除 add.json 外全部落到 try_files 兜底 → 公网可直接下载。"
                   "本轮按公网标准收口为 404。",
    "base_url": "内容地址改为 https://%s —— 音频 audioUrl 是**绝对地址**，"
                "不改就等于客户端仍然走明文 IP。" % DOMAIN,
}


def is_vocab_private(path):
    return path.startswith("/vocab/") and path != "/vocab/add.json"


def diff_snapshots(baseline, current, stage="all"):
    """纯函数：返回 (unexpected, missing_expected)。坏样本直接喂这个函数。

    unexpected：预期外发生了变化的条目（含预期外**新出现**的路径）
    missing_expected：预期要变、结果没变的条目

    ## `stage` 有三个档 —— 因为**同一个基址的两条链路切换时机不同**

    | stage | 什么时候用 | `audioUrl` | `update.json` 的 url |
    |---|---|---|---|
    | `nginx` | 只装了配置（`deploy.sh --nginx-only`） | 不变 | 不变 |
    | `content` | 跑过一轮 cycle | **已切域名** | 不变 |
    | `all` | 发布过新 APK | 已切 | **已切** |

    ⚠️ 第二档是第一版漏掉的：`audioUrl` 由服务端**每轮发布时现算**，
    而 `update.json` 里的地址只在**发布 APK 那一刻**才写一次。
    把两者当成「一起变」，中间那个状态就会被误报成失败 ——
    而假失败比没有断言更糟（它会让人不再跑这个脚本）。

    ⚠️ `nginx` 档里那两个「不变」是**主动断言**，不是「不检查」：
    它们变了就说明 bin/ 被提前同步了，而那是「index.json 已指向域名、
    443 还没起来」的状态，客户端会静默地没声音。
    """
    expect_content = stage in ("content", "all")
    expect_update = stage == "all"
    unexpected = []
    missing_expected = []

    b_probes, c_probes = baseline["probes"], current["probes"]

    # 1. 每条老路径都必须还在（少一条 = 老客户端少一个能力）
    for path in b_probes:
        if path not in c_probes:
            unexpected.append("%s 消失了（path 探测不到）" % path)

    # 2. 逐字段比对
    #
    # ⚠️ **顺序在这里是有意义的**：`/vocab/` 那两条必须放在
    #    `if not changed: continue` **之前**处理。
    #
    #    第一版把「该变没变」写在 `changed` 为空就 continue 的后面 ——
    #    于是「收口根本没做」时 `changed` 为空、直接跳过、报通过。
    #    而「收口没做」正是这个检查存在的**唯一理由**：
    #    它变成了一句在最坏情况下（什么都没做）恒真的话。
    #    ★ 坏样本 2 当场抓住了它。没有坏样本的话，这条会一直是假绿灯。
    fields = ("code", "ctype", "cache", "ranges", "acao")
    for path, b in b_probes.items():
        c = c_probes.get(path)
        if not c:
            continue
        changed = {k: (b[k], c[k]) for k in fields if b[k] != c[k]}

        if path == "/vocab/add.json":
            # 唯一要保留的那条：一个字都不许变
            if changed:
                unexpected.append("%s 变了 %s —— 它是 /vocab/ 下唯一要保留的那条"
                                  % (path, changed))
            continue

        if is_vocab_private(path):
            # 收口后这条路径返回 `return 404`，响应头自然换成 404 页那一套
            # （Content-Type 从 application/octet-stream 变 text/html 等）——
            # 那是收口的**必然结果**，所以只看状态码，其余字段不再逐条比。
            if b["code"] == "404":
                continue          # 基线本来就没暴露，没什么要收的
            if c["code"] != "404":
                missing_expected.append(
                    "%s 期望 404（收口），实际 %s ← 它还在公网可下载"
                    % (path, c["code"]))
            continue

        if changed:
            unexpected.append("%s 变了 %s" % (path, changed))

    # 3. 预期外**新增**的路径（探测脚本动态枚举 /vocab/，收口后不该出现新东西）
    for path in c_probes:
        if path not in b_probes:
            unexpected.append("%s 是新冒出来的路径（不该有）" % path)

    # 4. 两条链路的基址（时机不同，见 docstring）
    for key, label, expect in (("audioUrl", "index.json 的 audioUrl", expect_content),
                               ("updateUrl", "update.json 的 url", expect_update)):
        b, c = baseline["meta"][key], current["meta"][key]
        if not expect:
            # 这个阶段**要求它一个字不变** —— 主动断言，不是「不检查」
            if b != c:
                unexpected.append(
                    "%s 在本阶段就变了（%s → %s）—— 它比该切的时机早切了"
                    % (label, b or "(空)", c or "(空)"))
            continue
        if b.startswith("http://" + HOST_IP) and c.startswith("https://" + DOMAIN):
            continue
        if b == c:
            missing_expected.append("%s 该切了但没变（还是 %s）" % (label, b or "(空)"))
        else:
            unexpected.append("%s 变成了预期外的值：%s → %s" % (label, b, c))

    # 5. srt 必须**保持相对路径**。
    #
    #    ⚠️ 判据不能写成「与基线逐字相同」—— 第一版就是那么写的，结果在
    #    **什么都没坏**的情况下报了一条失败：index.json 的 episodes 顺序会变
    #    （新一轮发布之后第一条就换了），于是「第一条的 srt 文件名」本来就会变。
    #
    #    而**假失败比没有断言更糟** —— 它会让人不再跑这个脚本。
    #    真正的不变量是一条**性质**：它不能是绝对地址。
    #    （是绝对地址的话，换域名时这一处会被漏掉，症状是「字幕突然全没了」。）
    for label, v in (("基线", baseline["meta"]["srt"]), ("当前", current["meta"]["srt"])):
        if v.startswith("http://") or v.startswith("https://"):
            unexpected.append(
                "%s 的 srt 是绝对地址（%s）—— 它必须是相对路径，"
                "否则换域名时这一处会被漏掉" % (label, v))

    return unexpected, missing_expected


def cmd_compare(baseline_path, stage="all"):
    section("对照 80 端口基线（改造**之后**跑）")
    if not os.path.isfile(baseline_path):
        sys.exit("❌ 找不到基线 %s —— 先跑 --capture（而且必须是在改动之前）" % baseline_path)
    baseline = json.load(open(baseline_path, encoding="utf-8"))
    print("  基线采集于 %s（%d 条路径）"
          % (baseline.get("capturedAt", "?"), len(baseline["probes"])))
    print("  阶段：--stage %s" % stage)

    current = remote_scan()
    unexpected, missing_expected = diff_snapshots(baseline, current, stage)

    section("一、老客户端看到的 80/IP 通道")
    check("探到 %d 条路径（基线有 %d 条）" % (len(current["probes"]), len(baseline["probes"])),
          len(current["probes"]) == len(baseline["probes"]))
    check("没有**预期外**的差异（这是防回归的主断言）", not unexpected,
          "\n      ".join(unexpected[:8]))
    check("预期变更**确实发生了**（不是「我以为改了」）", not missing_expected,
          "\n      ".join(missing_expected[:8]))

    # 逐条把还活着的路径打出来，让人能扫一眼
    print()
    for p in sorted(current["probes"]):
        d = current["probes"][p]
        mark = "404收口" if is_vocab_private(p) else d["code"]
        print("    %-42s %-8s %-26s %s" % (p, mark, d["ctype"] or "-", d["cache"] or "-"))

    section("二、坏样本：对比逻辑本身有没有分辨力")
    _selftest_diff(baseline)

    return finish()


def _selftest_diff(baseline):
    """★ 坏样本：把 OK 的采集结果**故意改坏**，对比器必须报出来。

    没有这一节的话，`diff_snapshots` 里任何一个写错的比较（比如把 `!=` 写成 `==`）
    都会让整个防回归机制变成一句永远为真的话 —— 而它看起来一切正常。
    """
    import copy

    good = copy.deepcopy(baseline)
    # 先把「预期变更」施加到 good 上 → 它应该是**通过**的那种结果
    for p in list(good["probes"]):
        if is_vocab_private(p):
            good["probes"][p]["code"] = "404"
    good["meta"]["audioUrl"] = "https://%s/audio/x.mp3" % DOMAIN
    good["meta"]["updateUrl"] = "https://%s/apk/x.apk" % DOMAIN

    u, m = diff_snapshots(baseline, good)
    check("干净样本通过对比器（预期变更都做了）", not u and not m,
          "unexpected=%s missing=%s" % (u[:3], m[:3]))

    # 坏样本 1：老路径的缓存头被改掉一个字符
    bad1 = copy.deepcopy(good)
    victim = next(p for p in bad1["probes"] if not is_vocab_private(p))
    bad1["probes"][victim]["cache"] = "public, max-age=60"
    u1, _ = diff_snapshots(baseline, bad1)
    check("★坏样本1：老路径缓存头被改动 → 必须报出来", bool(u1),
          "对比器没发现 %s 的缓存头变了 —— 防回归形同虚设" % victim)

    # 坏样本 2：/vocab/ 收口**没做**
    bad2 = copy.deepcopy(baseline)
    bad2["meta"]["audioUrl"] = "https://%s/audio/x.mp3" % DOMAIN
    bad2["meta"]["updateUrl"] = "https://%s/apk/x.apk" % DOMAIN
    _, m2 = diff_snapshots(baseline, bad2)
    check("★坏样本2：/vocab/ 没收口 → 必须报「预期变更没发生」", bool(m2),
          "收口没做却报通过 —— 这是最危险的一类假绿灯")

    # 坏样本 3：基址**没切**（改动漏了一半）
    bad3 = copy.deepcopy(good)
    bad3["meta"]["audioUrl"] = baseline["meta"]["audioUrl"]
    _, m3 = diff_snapshots(baseline, bad3)
    check("★坏样本3：audioUrl 没切到域名 → 必须报出来", bool(m3),
          "音频仍走明文 IP 却不报 —— 上域名等于白上")

    # 坏样本 4：srt 被顺手改成绝对地址
    bad4 = copy.deepcopy(good)
    bad4["meta"]["srt"] = "https://%s/subs/x.srt" % DOMAIN
    u4, _ = diff_snapshots(baseline, bad4)
    check("★坏样本4：srt 从相对变绝对 → 必须报出来", bool(u4),
          "相对路径是「跟着 BASE_URL 走」的前提，改了之后换域名必漏")

    # 坏样本 5：**分阶段核对的第一阶段**里，基址被提前切了。
    #   这是上域名最危险的一种「多做了一步」：配置装好了、443 还没起来，
    #   而 bin/ 已经同步过去 ⇒ 下一轮发布（≤30 分钟，定时器自己会跑）
    #   就把 index.json 写成域名地址。它**不会报任何错**，
    #   表现只是「点下去没声音」，而且不会自己恢复。
    u5, _ = diff_snapshots(baseline, good, stage="nginx")
    check("★坏样本5：第一阶段就把基址切了 → 必须报出来", bool(u5),
          "提前同步 bin/ 不会被任何别的东西发现")

    good_nginx = copy.deepcopy(baseline)
    for p in list(good_nginx["probes"]):
        if is_vocab_private(p):
            good_nginx["probes"][p]["code"] = "404"
    u5b, m5b = diff_snapshots(baseline, good_nginx, stage="nginx")
    check("  · 反向：第一阶段「只收口、不切基址」时要通过", not u5b and not m5b,
          "unexpected=%s missing=%s" % (u5b[:3], m5b[:3]))


# ===========================================================================
# 默认模式：全套（需要 DNS 已生效）
# ===========================================================================
def _rdn_pairs(seq):
    """把 `getpeercert()` 里的 RDN 序列拍平成 [(键, 值)]。

    ⚠️ 它的结构是**三层嵌套**，不是一眼看上去的两层：

        ((('countryName', 'US'),),
         (('organizationName', "Let's Encrypt"),),
         (('commonName', 'YE1'),))

    外层每一项是「一个 RDN」，里面才是「一组属性（可多值）」。
    按两层写成 `for k, v in seq` 会拿到 `k = ('countryName', 'US')`、`v` 缺失
    ⇒ `ValueError: not enough values to unpack`。

    **本脚本就在真证书上这么崩过一次**，而且它在自检里是过的 ——
    自检碰不到真实证书。教训是通用的：**凡是「只有真环境才走得到」的解析，
    都得在真环境跑一次才算验过**，本机自检给不了这个保证。
    """
    out = []
    for rdn in seq or ():
        for pair in rdn:
            if len(pair) == 2:
                out.append((pair[0], pair[1]))
    return out


def _looks_like_local_intercept(status, headers):
    """这个响应是不是**本机网络策略**返回的，而不是服务器返回的？

    判据很硬：真 nginx 一定会在响应里带 `server: nginx/...`。
    本机的沙箱/代理拒绝时回的是**它自己造的** 403，没有那个头。

    ⚠️ 这一条是踩出来的：在后台上跑本脚本时，80 端口的请求被本机沙箱拦成 403，
    于是「老客户端通道完好」那三行**全报失败** —— 而服务器上一切正常
    （前台再测一次全是 200，跳转也对）。

    **假失败比没有断言更糟**：它会让人不再跑这个脚本，
    而它守的正是那条「弄坏了也没法补救」的通道。
    """
    if status not in (403, 407):
        return False
    return "nginx" not in (headers.get("server") or "").lower()


def ssh_http_status(host_header, path):
    """在**服务器本机**打一次（走 ssh），用来排除「本机网络策略」这个干扰项。

    它证明的是「服务端侧这条路径是好的」；配合本机那一次的结果，
    就能区分「服务端坏了」与「我这边出不去」—— 这两种情况的下一步完全不同。

    ⚠️ `-H` 的值**必须写成 `Host: xxx`**（带前缀）。
    只写 `-H 'xxx'` 时 curl 会把它理解成「**删掉**名为 xxx 的请求头」，
    于是请求**根本没用上 Host** —— 落到 default_server 上，
    却仍然返回一个看着合理的状态码。本脚本在这里错过一次：
    用域名复核时拿到 200（其实走的是 default_server，本该是 301），
    而用 IP 复核时**碰巧**是对的（default_server 正是它要测的那个）。
    「一半碰巧对」是这类 bug 最难发现的地方。
    """
    cmd = ("curl -s -o /dev/null -w '%%{http_code}' --max-time 10 "
           "-H 'Host: %s' 'http://127.0.0.1%s'\n" % (host_header, path))
    try:
        r = subprocess.run(["ssh", "-o", "ConnectTimeout=12", EC_HOST, "bash -s"],
                           input=cmd, text=True, capture_output=True, timeout=90)
        return (r.stdout or "").strip() or "?"
    except Exception as e:
        return "ssh失败(%s)" % repr(e)[:40]


def probe_http(url, headers=None, follow=False):
    """返回 (status, headers_dict, body_bytes)。不跟重定向时 status 可能是 301。"""
    req = urllib.request.Request(url, headers=headers or {})
    try:
        with urllib.request.urlopen(req, timeout=25) as r:
            return r.status, {k.lower(): v for k, v in r.headers.items()}, r.read()
    except urllib.error.HTTPError as e:
        return e.code, {k.lower(): v for k, v in e.headers.items()}, e.read()
    except Exception as e:
        return None, {}, repr(e).encode()


def cmd_full():
    section("三、DNS 解析")
    try:
        infos = socket.getaddrinfo(DOMAIN, 443, proto=socket.IPPROTO_TCP)
        addrs = sorted({i[4][0] for i in infos})
    except Exception as e:
        addrs = []
        print("   解析失败：%s" % e)
    check("%s 解析到 %s" % (DOMAIN, HOST_IP), addrs == [HOST_IP],
          "实际解析到：%s。解析没生效（或指到了别的机器）时，后面所有 443 断言都没有意义"
          % (addrs or "无"))

    section("四、证书")
    cert = None
    tls_ver = None
    try:
        ctx = ssl.create_default_context()
        # ⚠️ 用 create_default_context()（会做完整校验），不是 _create_unverified_context。
        #    后者会把「自签证书 / 域名不匹配 / 过期」全部放行 —— 而客户端那边不会放行，
        #    于是这个脚本会给出一个与真机相反的结论。
        with socket.create_connection((DOMAIN, 443), timeout=15) as sock:
            with ctx.wrap_socket(sock, server_hostname=DOMAIN) as ss:
                cert = ss.getpeercert()
                tls_ver = ss.version()
    except ssl.SSLCertVerificationError as e:
        check("TLS 握手且证书通过系统校验", False, "证书校验失败：%s" % e.verify_message)
    except Exception as e:
        check("TLS 握手且证书通过系统校验", False, repr(e)[:200])

    if cert:
        check("TLS 握手且证书通过系统校验", True, "")
        sans = [v for k, v in cert.get("subjectAltName", ()) if k == "DNS"]
        check("  证书 SAN 含 %s" % DOMAIN, DOMAIN in sans, "SAN = %s" % sans)
        issuer = dict(_rdn_pairs(cert.get("issuer", ())))
        org = issuer.get("organizationName", "")
        check("  签发者是 Let's Encrypt（不是自签）", "Let's Encrypt" in org,
              "实际 issuer = %s" % issuer)
        not_after = ssl.cert_time_to_seconds(cert["notAfter"])
        days = int((not_after - time.time()) / 86400)
        check("  证书还有 %d 天到期（>30 天）" % days, days > 30,
              "过早到期说明自动续期没配好 —— 而症状是「某天突然全连不上」")
        check("  TLS 版本 >= 1.2（实际 %s）" % tls_ver,
              tls_ver in ("TLSv1.2", "TLSv1.3"), "太低，主流 Android 会拒绝")

    section("五、域名上的各条路径")
    probe_paths = ["/healthz", "/index.json", "/apk/update.json", "/vocab/add.json"]
    # 从 index.json 里取一条真实内容
    st, _, body = probe_http("https://%s/index.json" % DOMAIN)
    ep = None
    if st == 200:
        try:
            ep = json.loads(body)["episodes"][0]
        except Exception:
            ep = None
    if ep:
        probe_paths += [ep["srt"], ep["words"], ep["zh"]]
        au = ep.get("audioUrl", "")
        if au.startswith("https://%s/" % DOMAIN):
            probe_paths.append(au[len("https://%s" % DOMAIN):])
        else:
            check("index.json 里的 audioUrl 走域名", False,
                  "实际是 %r —— 客户端会去别的地方取音频" % au)

    for p in probe_paths:
        url = "https://%s/%s" % (DOMAIN, p.lstrip("/"))
        st, h, _ = probe_http(url)
        check("%-42s → %s" % (p, st), st == 200 and h.get("content-type"),
              "拿不到（%s）—— 内容在 80 上、不在 443 上，说明 location 没共用" % st)

    # 内容类型与缓存策略必须与 80 上一致（同一套 location 的直接证据）
    st, h, _ = probe_http("https://%s/audio/%s" % (DOMAIN, "x") )
    st2, h2, _ = probe_http("https://%s/apk/update.json" % DOMAIN)
    check("update.json 走 443 时仍是 no-store",
          h2.get("cache-control") == "no-store", "实际 %s" % h2.get("cache-control"))
    st3, h3, _ = probe_http("https://%s/index.json" % DOMAIN)
    check("index.json 走 443 时缓存 300s",
          "max-age=300" in (h3.get("cache-control") or ""),
          "实际 %s" % h3.get("cache-control"))

    section("六、发布区收口（/vocab/ 只放行 add.json）")
    for p in ("/vocab/vocab.base", "/vocab/vocab.forms", "/vocab/examples.base",
              "/vocab/vocab.meta.json"):
        st, _, _ = probe_http("https://%s%s" % (DOMAIN, p))
        check("%-30s → 404（不再是 200）" % p, st == 404,
              "拿到了 %s —— 服务端中间产物在公网可直接下载" % st)
    st, h, _ = probe_http("https://%s/vocab/add.json" % DOMAIN)
    check("/vocab/add.json 仍可下载且 no-store",
          st == 200 and h.get("cache-control") == "no-store",
          "这条是客户端要用的，收口时不能一起关掉（%s / %s）"
          % (st, h.get("cache-control")))

    section("七、域名上的明文 http 必须跳转到 https")
    st, h, _ = probe_http("http://%s/healthz" % DOMAIN)
    if _looks_like_local_intercept(st, h):
        st = ssh_http_status(DOMAIN, "/healthz")
        check("http://%s 返回 301/308（本机被拦截，经服务器复核）" % DOMAIN,
              st in ("301", "308"),
              "实际 %s" % st)
    else:
        check("http://%s 返回 301/308" % DOMAIN, st in (301, 308), "实际 %s" % st)
        check("  跳转目标以 https:// 开头",
              (h.get("location") or "").startswith("https://"),
              "实际 Location = %s" % h.get("location"))

    section("八、老客户端那条路仍然完好（最终确认）")
    for p in ("/healthz", "/index.json", "/apk/update.json"):
        st, h, _ = probe_http("http://%s%s" % (HOST_IP, p))
        if _looks_like_local_intercept(st, h):
            # 本机网络策略拦的 ⇒ 改从服务器那侧确认，**而不是报成失败**
            s = ssh_http_status(HOST_IP, p)
            check("http://%s%-18s → %s（本机被拦截，经服务器复核）" % (HOST_IP, p, s),
                  s == "200",
                  "服务器那侧也不是 200 —— 那才说明这条通道真断了。"
                  "它是老客户端唯一的升级路径，弄坏了没法补救")
        else:
            check("http://%s%-18s → %s" % (HOST_IP, p, st), st == 200,
                  "老客户端依赖这条通道取更新清单，它断了就**永远升不了级**")

    return finish()


def finish():
    print()
    print("=" * 78)
    print("3.1.0 域名 + TLS：%d 项通过，%d 项失败" % (PASS, FAIL))
    print("=" * 78)
    if FAIL:
        print()
        print("⚠️ 失败项里如果有「预期外的差异」，优先看**是不是 80 端口被弄坏了**：")
        print("   那是唯一一条无法补救的通道（老客户端升不了级 = 永久失联）。")
        for f in FAILURES[:12]:
            print("   · %s" % f)
    return 1 if FAIL else 0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--capture", metavar="FILE", help="采集改造前的 80 端口基线")
    ap.add_argument("--compare", metavar="FILE", help="与基线对照（改造后）")
    ap.add_argument("--stage", choices=("nginx", "content", "all"), default="all",
                    help="nginx = 只装了配置（两条基址都应当**未**变）；"
                         "content = 跑过一轮 cycle（内容基址已切、更新包还没）；"
                         "all = 也发布过新 APK（默认）")
    args = ap.parse_args()
    if args.capture:
        return cmd_capture(args.capture)

    if args.compare:
        return cmd_compare(args.compare, args.stage)
    return cmd_full()


if __name__ == "__main__":
    sys.exit(main())
