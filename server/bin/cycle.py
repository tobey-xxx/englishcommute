#!/usr/bin/env python3
"""服务端内容管线（EnglishCommute 2.0）

    RSS → 选新条目 → 下载音频 → 静音切块转写 → 中文字幕 → 音频与字幕入库 → 静态发布

设计要点（前四条都是为了 2 GB 内存这台机器）：

1. **ASR 与翻译各自起独立子进程**。onnxruntime / CTranslate2 的内存池不会可靠归还给系统，
   「进程退出」才是唯一彻底的归还方式；同时一个进程只处理一集，内存峰值与集数无关。
   ⚠️ 两者还必须**串行**：ASR 峰值 ~830 MB、翻译峰值 ~1050 MB，同时在场会顶到 1.9 GB。
   所以顺序固定为「先把这一轮全部转写完，再统一做翻译」。
2. **音频下完即转**，转完**留下来发布**（2.1.9 改，见 [publish]）。
   原始 URL 常带签名会过期，所以必须「抓到即下、下完即转」，不能先记账后下载。
   ⚠️ 但**不能下完就删** —— 那正是「字幕与音频对不上」的成因，见下。
3. **幂等**：state.json 按 guid 记账，重跑不重复转写。子进程写的是 .tmp 再 rename，
   所以「字幕文件在」就等于「转写完整」。翻译同理，且支持按句续做。
4. **id 必须确定性**：用 sha1(guid)，**不要用 Python 的 hash()** ——
   字符串 hash 默认带随机盐（PYTHONHASHSEED），跨进程会变，文件名每次都不一样。
5. **翻译是独立阶段，可单独回填**：字幕已经有的老内容也会被补翻（见 [translate_pending]）。

## ★★ 为什么音频必须托管（2.1.9，实测得出）

2026-09-22 用户报「某期从 **0:56** 起字幕与音频对不上」。查明**不是**转写也不是切分的问题：

> NPR / Simplecast 走 **AdsWizz 动态广告插入**（URL 里的 `awCollectionId` / `awEpisodeId` /
> `t=podcast` 就是签名）。**同一个 URL，对不同客户端特征 / 不同请求时刻 / 不同出口 IP
> 会返回不同的拼接。**

实测同一集四种结果：`curl/8.x` 12,638,283 B（789.89 s）、`okhttp/4.12.0` 12,637,865 B、
`Dalvik/2.1.0 (Android 14)` **14,414,188 B（900.88 s）** —— **差 111 秒**，
两条音频在第 56.0 秒（第一个广告位）开始分叉，且在任何时间偏移上都对不回来。

**推论**：只要「服务端转写的」和「客户端播放的」不是同一个文件，字幕就可能错位。
唯一可靠的做法就是**转写完把这一份留下来，让客户端播的就是它**。
顺带解决另一个老问题：**原始音频直链带签名会过期**。

⚠️ **不幂等，且代价不可逆**：音频一旦按老逻辑删掉，那一次的字幕就**永远**对不上它了 ——
补下回来的音频是**另一次拼接**。存量只能重做（`--rehost`）。
"""

import argparse
import datetime
import hashlib
import json
import os
import re
import shutil
import subprocess
import urllib.parse
import urllib.request
import sys
import time
from pathlib import Path

BIN = Path(__file__).resolve().parent
sys.path.insert(0, str(BIN))
import transcribe as T  # noqa: E402  复用已实测的 RSS 解析 / 下载 / 时间码

ROOT = Path("/opt/ec")
VENV_PY = ROOT / "venv/bin/python"
CHUNKED = BIN / "chunked_transcribe.py"
TRANSLATE_BATCH = BIN / "translate_batch.py"
RESEGMENT = BIN / "resegment.py"
FEEDS = BIN / "feeds.txt"
AUDIO = ROOT / "data/audio"          # 转写期暂存区：**下完即用即搬走**，正常情况是空的
STATE = ROOT / "data/state/state.json"
WWW = ROOT / "www"
WWW_AUDIO = WWW / "audio"            # 发布区：音频留在这里，客户端直接来取
WWW_ART = WWW / "art"                # 封面（4.4.0）：跟着音频一起托管
LOG = ROOT / "logs/cycle.log"
LOCK = ROOT / "data/state/cycle.lock"

# 音频对外的地址前缀。App 从 index.json 拿到 audioUrl 后直接来这里取。
#
# ## 3.1.0 起是**域名 + HTTPS**
#
# 这个值会被原样写进 index.json 的 `audioUrl`，那是一个**绝对地址** ——
# 也就是说它完全由这里决定，客户端照着取，**不看自己的 BASE_URL**。
# （`srt` / `words` / `zh` 三个是相对路径，那三个才跟着客户端的 BASE_URL 走。
#   这个不对称很容易看漏：改客户端地址时觉得「音频是服务端给的，不用管」。）
#
# ⚠️ 改这里的**时机**比改什么更重要：它一生效，下一轮发布（≤30 分钟）就会
#    把 index.json 里所有 audioUrl 换成新前缀。所以必须等到新的入口真的能用
#    之后再改 —— 否则客户端会拿到一个连不上的音频地址，
#    表现是「有字幕、有封面、点下去没声音」，而且**不会自己恢复**。
#    完整的两步顺序见 server/README.md 的「上域名」一节。
#
# 覆盖方式：`EC_PUBLIC_BASE=https://...`。仅在临时排查时用 ——
# 长期靠环境变量会变成「手动跑和定时器跑出来的清单不一样」。
PUBLIC_BASE = os.environ.get("EC_PUBLIC_BASE", "http://127.0.0.1:8080").rstrip("/")

# ⚠️ 4.6.4：这一组常量被我在「删重复函数」时连带删掉了（它们夹在两个
#    同名函数之间，而我按「def 到 def」整段删）。恢复自 4.4.0 的备份。
TRANSLATE_SET = "fp32"       # int8 是内存兜底，但译文会丢否定语义
TRANSLATE_BEAMS = 4
TRANSLATE_THREADS = 2
TRANSLATE_GUARD_SEC = 1200   # 单集翻译的时长上限；超了落盘部分结果，下轮续做

RETENTION_DAYS = 7
# 抓封面用的 UA：有些站的图床对空 UA 直接 403
UA = ("Mozilla/5.0 (compatible; EnglishCommute/4.6; +https://example.com)")


def _shrink_art(data: bytes) -> bytes:
    """把封面压到 <=512px 的 JPEG（4.6.4）。

    源站封面是 3000x3000（BBC 767 KB、NPR 的 S3 源图 6.7 MB），
    而列表只用 48dp —— 不压就是白烧手机流量。压完约 50 KB。
    没装 Pillow 时原样返回（不能因为依赖缺失让整轮发布失败）。
    """
    try:
        from PIL import Image
        import io as _io
        im = Image.open(_io.BytesIO(data)).convert("RGB")
        im.thumbnail((512, 512))
        out = _io.BytesIO()
        im.save(out, "JPEG", quality=82, optimize=True)
        return out.getvalue()
    except Exception:
        return data


def public_artwork(eid: str, fallback: str | None) -> str:
    """发布时给封面地址：**盘上有就用托管地址**，没有就退回原地址（4.6.2）。

    ## 为什么这一层必须在 publish() 里

    `rehost_artwork` 只在**新发布**时跑；存量条目的 `artworkUrl` 还指向原站点
    （`ichef.bbci.co.uk` / `www.cbc.ca`），大陆手机拉不到 ⇒ 显示字母块。
    而 4.5.0 那个 `backfill_artwork.py` 改的是 `index.json` ——
    **它是派生文件**，下一轮 `publish()` 就会覆盖掉那次改写（线上 224 条里
    只剩 7 条指向托管地址，用户看到的就是"封面还是字母块"）。

    ★ 判据落在**文件在不在**上，就与"谁在什么时候改写过索引"无关了。

    ⚠️ **刻意不发网络请求**：它对每条都调用一次，而旧条目里不少原始链接早已失效；
       在那里抓图会让一次发布跑十几分钟（实测卡死过一次）。
       抓图只在**新发布**那条路径里做一次（见 `rehost_artwork` 的调用点）。
    """
    if eid and (WWW_ART / f"{eid}.jpg").is_file():
        return f"{PUBLIC_BASE}/art/{eid}.jpg"
    return fallback or ""


def rehost_artwork(url: str | None, eid: str) -> str:
    """把封面抓到发布区；失败退回原地址（4.4.0 / 4.6.4）。

    ## 4.6.4：两个必须的修补（"封面一直是字母块"的真因）

    ① **失败要换源**：NPR 的封面走 `npr.brightspotcdn.com/dims3/...?url=<S3>`，
       这个代理**对本服务 403**（curl 也一样，不是 UA/Referer 的问题）。
       而它 `?url=` 里的 S3 源图是 200 —— 第一跳失败就把那个参数解出来再试。
       ★ NPR 是最大的一批源，所以这一条决定了大半张列表有没有封面。
    ② **一律压缩**：源图 3000x3000，列表只用 48dp。

    三条约束保持：失败不阻断发布、只认 image/*、文件名用 eid（幂等）。
    """
    if not url:
        return ""
    dest = WWW_ART / f"{eid}.jpg"
    if dest.is_file() and dest.stat().st_size > 1024:
        return f"{PUBLIC_BASE}/art/{eid}.jpg"

    cands = [url]
    mm = re.search(r"[?&]url=([^&]+)", url)
    if mm:
        cands.append(urllib.parse.unquote(mm.group(1)))

    for i2, cand in enumerate(cands):
        try:
            req = urllib.request.Request(cand, headers={"User-Agent": UA})
            with urllib.request.urlopen(req, timeout=20) as r:
                ctype = (r.headers.get("Content-Type") or "").lower()
                if not ctype.startswith("image/"):
                    log("    ⚠️ 封面不是图片（%s）" % ctype[:20])
                    continue
                cap = 2 * 1024 * 1024 if i2 == 0 else 9 * 1024 * 1024
                data = r.read(cap + 1)
            if len(data) > cap:
                log("    ⚠️ 封面超过 %d MB，跳过" % (cap // 1048576))
                continue
            data = _shrink_art(data)
            if not data.startswith(b"\xff\xd8"):
                log("    ⚠️ 压缩后不是 JPEG，跳过")
                continue
            WWW_ART.mkdir(parents=True, exist_ok=True)
            tmp = dest.with_suffix(".tmp")
            tmp.write_bytes(data)
            tmp.replace(dest)
            log("    封面已入库 %s（%.0f KB）" % (dest.name, len(data) / 1024))
            return f"{PUBLIC_BASE}/art/{eid}.jpg"
        except Exception as e:
            if i2 + 1 < len(cands):
                log("    ⚠️ 封面源 ① 失败（%s），换备用源" % type(e).__name__)
            else:
                log("    ⚠️ 封面托管失败（%s），用原地址" % type(e).__name__)
    return url


def log(msg):
    line = f"[{time.strftime('%Y-%m-%d %H:%M:%S')}] {msg}"
    print(line, flush=True)
    LOG.parent.mkdir(parents=True, exist_ok=True)
    with open(LOG, "a", encoding="utf-8") as f:
        f.write(line + "\n")
    # 简单限容：超过 2 MB 就把前一半丢掉
    try:
        if LOG.stat().st_size > 2 * 1024 * 1024:
            data = LOG.read_text(encoding="utf-8").splitlines()
            LOG.write_text("\n".join(data[len(data) // 2:]) + "\n", encoding="utf-8")
    except Exception:
        pass


def ep_id(guid):
    """确定性 id。⚠️ 不要用 hash()：它带随机盐，跨进程不稳定，会让文件名每次都变。"""
    h = hashlib.sha1(guid.encode("utf-8")).hexdigest()[:16]
    tail = re.sub(r"[^A-Za-z0-9]+", "", guid)[-24:]
    return f"{tail}_{h}" if tail else h


def load_json(path, default):
    try:
        return json.loads(Path(path).read_text(encoding="utf-8"))
    except Exception:
        return default


def save_json(path, obj):
    p = Path(path)
    p.parent.mkdir(parents=True, exist_ok=True)
    tmp = str(p) + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(obj, f, ensure_ascii=False, indent=1)
    os.replace(tmp, p)


def read_feeds():
    out = []
    for line in FEEDS.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        if "=" in line:
            fid, url = line.split("=", 1)
            out.append((fid.strip(), url.strip()))
        else:
            out.append((re.sub(r"\W+", "-", line)[:24], line))
    return out


def run_asr(audio: Path, srt: Path, model: str, threads: int) -> bool:
    """独立子进程跑转写。返回是否成功。"""
    cmd = [str(VENV_PY), str(CHUNKED), str(audio), str(srt),
           "--model", model, "--threads", str(threads), "--words"]
    t0 = time.monotonic()
    r = subprocess.run(cmd, capture_output=True, text=True)
    dt = time.monotonic() - t0
    peak = ""
    m = re.search(r"最终峰值 ([\d.]+) MB", r.stdout)
    if m:
        peak = f" 峰值 {m.group(1)} MB"
    if r.returncode != 0:
        log(f"    ❌ 转写失败 rc={r.returncode}  {r.stderr.strip()[-300:]}")
        return False
    log(f"    ✅ 转写完成 {dt:.0f}s{peak}")
    return True


def run_translate(srt: Path, zh: Path) -> tuple[bool, float]:
    """独立子进程跑翻译。返回 (是否成功, 峰值 MB)。"""
    if not srt.exists():
        return False, 0.0
    cmd = [str(VENV_PY), str(TRANSLATE_BATCH), str(srt), str(zh),
           "--set", TRANSLATE_SET, "--beams", str(TRANSLATE_BEAMS),
           "--threads", str(TRANSLATE_THREADS),
           "--timeout-guard", str(TRANSLATE_GUARD_SEC)]
    t0 = time.monotonic()
    r = subprocess.run(cmd, capture_output=True, text=True)
    dt = time.monotonic() - t0
    peak = 0.0
    m = re.search(r"PEAK_MB=([\d.]+)", r.stdout)
    if m:
        peak = float(m.group(1))
    if r.returncode != 0:
        log(f"    ❌ 翻译失败 rc={r.returncode}  {r.stderr.strip()[-300:]}")
        return False, peak
    # 子进程只把关键行回吐，避免日志被几百行「第 N 句」淹没
    for line in r.stdout.splitlines():
        s = line.strip()
        if s.startswith(("✅", "⏹", "沿用", "⚠️")) or "句段" in s:
            log(f"    {s}")
    log(f"    ✅ 翻译完成 {dt:.0f}s 峰值 {peak:.0f} MB")
    return True, peak


def run_resegment(srt: Path, zh: Path | None = None) -> bool:
    """把字幕切成「一句一条」。返回是否成功。"""
    if not srt.exists():
        return False
    cmd = [str(VENV_PY), str(RESEGMENT), str(srt)]
    if zh is not None and zh.exists():
        cmd.append(str(zh))
    r = subprocess.run(cmd, capture_output=True, text=True)
    if r.returncode != 0:
        log(f"    ❌ 重切分失败 rc={r.returncode}  {r.stderr.strip()[-300:]}")
        return False
    for line in r.stdout.splitlines():
        if line.strip():
            log(f"    {line.strip()}")
    return True


def resegment_pending(state, only_eid=None):
    """把「一句一条字幕」这件事补齐（2.1.1）。

    ## 它解决的是什么

    字幕与译文本来是**两套粒度**：SRT 每 2–4 秒一条，译文按合并后的整句翻。
    写回时译文只挂在句首那一行（续行留空 —— 同一句译文挂三行会像翻译错了），
    于是**跨行的句子只有第一行有中文**。

    用户的原话：「有些段落只有英文没有中文，既然如此，
    为什么不把没有中文的那句话跟上一句拼在一起呢？」

    做法就是把 cue 划分改成与句子划分一致，见 `resegment.py` 的说明。

    ## ⚠️ 判据只能是 state 里的标记，**不能是内容**

    合并过的 cue 里有一部分不以句末标点结尾（`max_chars` 兜底断开的那种）。
    再跑一次，它们会被并进下一句 —— **结果就变了**。
    所以「做过没有」这件事无法从文件本身看出来，只认 `rs` 标记。

    ## 为什么要抬 `srtVer`

    它唯一的用途是让客户端知道「本地那份字幕已经过期」。
    客户端按「服务器版本 > 本地版本」决定要不要重下 —— 不抬这一格，
    手机上那份旧的细粒度 srt 会一直配着新的粗粒度译文，句数对不上，
    客户端会整份丢弃译文（这是它的硬契约，宁可不显示也不能张冠李戴），
    表现就是「中文全没了」。
    """
    eps = state.get("episodes", {})
    done = 0
    for eid, e in eps.items():
        if only_eid and eid != only_eid:
            continue
        if e.get("status") != "ok" or e.get("rs"):
            continue
        srt = WWW / "subs" / f"{eid}.srt"
        if not srt.exists():
            continue
        zh = WWW / "subs" / f"{eid}.zh.json"
        log(f"  重切分 {eid}  {str(e.get('title'))[:44]}")
        if run_resegment(srt, zh):
            e["rs"] = True
            e["srtVer"] = e.get("srtVer", 0) + 1
            save_json(STATE, state)
            done += 1
    if done:
        log(f"重切分完成 {done} 条（旧译文按新划分重排，没有重跑模型）")
    return done


def translate_pending(state, only_eid=None):
    """给「有字幕但还没中文」的期数补翻。

    独立成一步（而不是塞在下载循环里）有两个理由：
    ① ASR 与翻译的峰值内存必须错开，所以在时间上要分开；
    ② 让翻译可以**单独回填** —— 字幕早就有的老内容也能补上，不必重新转写。
    """
    eps = state.get("episodes", {})
    pending = []
    for eid, e in eps.items():
        if e.get("status") != "ok":
            continue
        if only_eid and eid != only_eid:
            continue
        srt = WWW / "subs" / f"{eid}.srt"
        zh = WWW / "subs" / f"{eid}.zh.json"
        if not srt.exists():
            continue
        # 已存在且没有 null（null = 上次翻失败或被打断）就不再跑
        if zh.exists():
            try:
                arr = json.loads(zh.read_text(encoding="utf-8"))
                if arr and not any(x is None for x in arr):
                    # ⚠️ 跳过前必须把账补上。译文可能是**手工跑出来的**或上一版
                    #    程序留下的 —— 文件在、state 里却没有 zh 字段，
                    #    索引于是不发布它，客户端就以为这集没中文。
                    #    （实测踩到过：磁盘 13 个 zh.json，索引只写了 12 条。）
                    if e.get("zh") != f"subs/{eid}.zh.json":
                        e["zh"] = f"subs/{eid}.zh.json"
                        e.setdefault("translatedAt",
                                     time.strftime("%Y-%m-%dT%H:%M:%S%z"))
                        save_json(STATE, state)
                        log(f"  补记译文账目 {eid}")
                    continue
                log(f"  续做未完成的译文 {eid}")
            except Exception:
                log(f"  译文文件损坏，重做 {eid}")
        pending.append((eid, e, srt, zh))

    if not pending:
        log("翻译：没有待处理的内容")
        return 0
    log(f"翻译：{len(pending)} 集待处理（{TRANSLATE_SET} / {TRANSLATE_BEAMS} 束）")
    ok = fail = 0
    for eid, e, srt, zh in pending:
        log(f"  --- {eid}  {str(e.get('title'))[:52]}")
        good, peak = run_translate(srt, zh)
        if good and zh.exists():
            e["zh"] = f"subs/{eid}.zh.json"
            e["translatedAt"] = time.strftime("%Y-%m-%dT%H:%M:%S%z")
            e["translatePeakMb"] = round(peak, 1)
            save_json(STATE, state)
            ok += 1
        else:
            fail += 1
    log(f"翻译结束：成功 {ok} 失败 {fail}")
    return ok


def backfill_missing_guid(state):
    """给「feed 没给 guid」的条目补一个**稳定**的 id 种子（2.1.9）。

    ## 为什么必须补

    客户端的 id 是 `RssParser.stableId(guid, audioUrl)` —— **guid 缺失时退回用 audioUrl**
    （`val seed = guid?.takeIf { it.isNotBlank() } ?: audioUrl`）。

    而 2.1.9 起音频地址会从第三方 CDN 换成本服务（`.../audio/<id>.mp3`）。
    **地址一变，那个条目的 id 就变了** —— 而 id 是收藏 / 播放进度 / 已下音频的唯一键，
    表现是「我收藏的那条怎么没了」，且**不报任何错**。
    实测 48 条里有 **1 条**（`npr-news-now`，那个源的 feed 不给 guid）会踩到。

    ## 为什么把**原始 CDN 地址**写进 guid

    因为这正是客户端在没有 guid 时**本来就会**拿来做种子的那个字符串 ——
    补上它之后 `stableId(guid=原地址, audioUrl=新地址)` 与旧版本算出来的 id **逐位相同**。
    这不是发明一套新规则，是把客户端那条隐式回退**显式化并钉住**。

    ⚠️ 别改成「补一个哈希」或「补本服务地址」：前者与客户端算法无关，后者又会随地址变化。
    """
    eps = state.get("episodes", {})
    filled = 0
    for eid, e in eps.items():
        if e.get("status") != "ok":
            continue
        if (e.get("guid") or "").strip():
            continue
        seed = (e.get("audioUrl") or "").strip()
        if not seed:
            continue
        e["guid"] = seed
        filled += 1
        log(f"  补 id 种子 {eid}（该源不给 guid → 用原始音频地址当种子，"
            f"否则换托管地址会让它的收藏/进度孤立）")
    if filled:
        log(f"补 id 种子完成 {filled} 条")
    return filled


def rehost_items(state, limit=0):
    """存量补做：给「字幕在、但服务器上没有音频」的条目重下音频 + **重转写**。

    ## 为什么必须重转写，不能只把音频补下来

    那些条目的音频在 2.1.9 之前就被删了。补下回来的是**另一次广告拼接**
    （见文件头）—— 拿旧字幕配新音频，只是换一种方式错位，问题一点没解决。
    所以这里的做法是：**旧字幕连同译文一起删掉，从音频重新走一遍全流程**。

    ## 判据为什么是「音频没托管」而不是「字幕在不在」

    删字幕这一步发生在枚举之后。如果中途被杀，条目会变成「state 说 ok、
    字幕却没了」—— 用「字幕在不在」当判据的话它就**永远不会被再做一次**，
    而索引里还挂着一条指向 404 的字幕。用「音频没托管」当判据则天然自愈。

    返回 `(feedId, eid, item)` 列表，`item` 的形状与 feed 解析出来的条目一致
    （`publishedAt` 是 **datetime**，主循环会调 `.isoformat()`）。
    """
    out = []
    for eid, e in state.get("episodes", {}).items():
        if e.get("status") != "ok":
            continue
        if (WWW_AUDIO / f"{eid}.mp3").exists():
            continue
        out.append((eid, e))
    out.sort(key=lambda t: t[1].get("publishedAt") or "", reverse=True)
    if limit > 0:
        out = out[:limit]

    res = []
    for eid, e in out:
        pub = e.get("publishedAt")
        try:
            when = datetime.datetime.fromisoformat(pub) if pub else None
        except Exception:
            when = None
        # ⚠️ 旧字幕必须在**这里**删掉。留着的话主循环会走
        #    「字幕已存在，跳过转写」—— 于是音频换了、字幕还是旧的，白做一轮。
        for suffix in (".srt", ".words.json", ".zh.json"):
            f = WWW / "subs" / f"{eid}{suffix}"
            if f.exists():
                f.unlink()
        # 账也要清干净，否则翻译阶段会认为「已经翻好了」而不重翻
        for k in ("rs", "srtVer", "zh", "translatedAt", "translatePeakMb"):
            e.pop(k, None)
        res.append((e.get("feedId") or "?", eid, {
            "guid": e.get("guid"),
            "audioUrl": e.get("audioUrl"),
            "feedTitle": e.get("feedTitle"),
            "title": e.get("title"),
            "description": e.get("description"),
            "link": e.get("link"),
            "durationSec": e.get("durationSec"),
            "artworkUrl": e.get("artworkUrl"),
            "publishedAt": when,
        }))
    return res


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--per-feed", type=int, default=2, help="每源每轮最多几条")
    ap.add_argument("--hours", type=int, default=48, help="只处理最近 N 小时发布的")
    ap.add_argument("--model", default="base.en")
    ap.add_argument("--threads", type=int, default=2)
    ap.add_argument("--dry-run", action="store_true", help="只列出要做的事，不下载不转写")
    ap.add_argument("--no-translate", action="store_true", help="跳过翻译（只做转写）")
    ap.add_argument("--translate-only", action="store_true",
                    help="不拉源、不转写，只把已有的字幕补翻")
    ap.add_argument("--rehost", action="store_true",
                    help="存量重做：给「字幕在但没有托管音频」的条目重下音频并重转写"
                         "（音频被删过 → 旧字幕永远对不上它，只能重做）")
    ap.add_argument("--rehost-limit", type=int, default=0,
                    help="配合 --rehost：本轮最多重做几条（0 = 不限）。用来把长活拆成几轮")
    args = ap.parse_args()

    # 单实例锁：定时任务可能被上一轮还没跑完的重叠触发
    if LOCK.exists():
        try:
            pid = int(LOCK.read_text().strip())
            os.kill(pid, 0)
            log(f"上一轮（pid {pid}）还在跑，本轮退出")
            return 0
        except Exception:
            LOCK.unlink(missing_ok=True)
    LOCK.parent.mkdir(parents=True, exist_ok=True)
    LOCK.write_text(str(os.getpid()))

    try:
        return run(args)
    finally:
        LOCK.unlink(missing_ok=True)


def run(args):
    t_start = time.monotonic()

    # 启动清扫：被 SIGKILL 掉的那一轮不会走 finally，会留下音频与半截临时文件。
    # 「下完即转即删」这条纪律得有一个兜底，否则一次强杀就永久占着盘（也永久留一份版权副本）。
    for f in list(AUDIO.glob("*")) if AUDIO.exists() else []:
        try:
            age_h = (time.time() - f.stat().st_mtime) / 3600
            if f.name.endswith(".tmp") or age_h > 6:
                f.unlink()
                log(f"  清扫残留 {f.name}（{age_h:.1f} 小时前）")
        except Exception:
            pass

    feeds = read_feeds()
    state = load_json(STATE, {"episodes": {}})
    eps = state.setdefault("episodes", {})
    # ★ 2.1.9：先补 id 种子。必须在**任何发布之前**跑完 ——
    #   它防的是「换了音频地址之后，客户端算不出同一个 id」（见 backfill_missing_guid）。
    #   放在这里而不是 publish 里，是因为它要**落进 state**：state 才是账本，
    #   只改发布出去的副本的话，下一轮又会退回缺失状态。
    backfill_missing_guid(state)
    cutoff = time.time() - args.hours * 3600

    log(f"=== 开始：{len(feeds)} 个源，窗口 {args.hours} 小时，每源最多 {args.per_feed} 条 ===")

    # 只补翻译：不拉源、不下载、不转写。用于「换了解码方式/换了模型，把存量重翻一遍」
    if args.translate_only:
        resegment_pending(state)
        translate_pending(state)
        save_json(STATE, state)
        publish(state, feeds)
        log(f"=== 结束（仅翻译）：用时 {time.monotonic() - t_start:.0f}s ===")
        return 0

    todo = []
    for fid, url in feeds:
        try:
            raw = T.http_get(url)
            items = T.parse_feed(raw, fid)
        except Exception as e:
            log(f"  拉取失败 {fid}: {type(e).__name__}: {e}")
            continue
        # ★ 每个源**最新的一条无条件收**，不受时间窗口限制（2.1.0）。
        #
        # ## 为什么时间窗口不能是「内容边界」
        #
        # 客户端的下载窗口是「最近 2 天 + **每源保底最新一条**」
        # （见 content/ContentPolicy.selectByWindow 的 fallback：某个源在窗口内
        # 一条都没有时，会把它最新那条也下下来，否则周更源会整块消失）。
        #
        # 那条「保底」可以**任意老** —— 周更源是 7 天、停更前的源是几个月。
        # 而服务端原来只处理 cutoff（48 小时）以内的内容，于是：
        #
        #     手机上有这一条的音频 → 服务端永远不会转写它 → 永远没有字幕
        #
        # ## 2.1.0 把这个洞从「可恢复」变成了「永久」
        #
        # 在这之前，这条空洞被端侧识别兜着（客户端取不到服务器字幕就自己转）。
        # 端侧那条链路这一版整条拆掉了 —— 于是它变成一个**没有任何补救的缺口**，
        # 而且用户侧的表现只是「这条内容一直没有字幕」，看不出和别的有什么不同。
        #
        # 判据是「每源最新一条」而不是「把窗口放大到 N 天」：后者要么放得不够
        # （还是漏掉停更源），要么放到离谱（冷启动时去转写几百条历史内容）。
        # 而客户端保底取的正是每源最新那一条，两者**严格对齐**。
        #
        # 去重仍然靠 state（`status == "ok"`），所以这不会让每轮都重做。
        # 代价是首次跑会补上 11 个源的「最新一条」（最多 11 条，之后永不重复）——
        # 每源 per_feed 仍然限制同一轮里最多做几条。
        newest = max(
            (it.get("publishedAt") for it in items if it.get("publishedAt")),
            default=None,
        )
        fresh = []
        for it in items:
            pub = it.get("publishedAt")
            if pub is not None and pub.timestamp() < cutoff and pub != newest:
                continue
            eid = ep_id(it["guid"])
            if eps.get(eid, {}).get("status") == "ok":
                continue
            fresh.append((eid, it))
            if len(fresh) >= args.per_feed:
                break
        log(f"  {fid:<22} 条目 {len(items):3d} → 待处理 {len(fresh)}")
        todo += [(fid, e, it) for e, it in fresh]

    # ── 存量重做（--rehost）───────────────────────────────────────────────
    # 放在拉源之后：这样一轮就能同时把「新内容」和「存量缺口」都往前推，
    # 不必单独占用一次定时轮次。
    if args.rehost:
        again = rehost_items(state, args.rehost_limit)
        if again:
            log(f"存量重做：{len(again)} 条（音频没托管 → 旧字幕不可信，重下重转）")
            todo += again
        else:
            log("存量重做：没有缺音频的条目")

    if args.dry_run:
        log(f"[dry-run] 共 {len(todo)} 条待处理：")
        for fid, eid, it in todo:
            log(f"    {fid:<22} {eid}  {it['title'][:50]}")
        return 0

    if not todo:
        log("没有新内容")
        # ⚠️ 这里不能直接 return —— 翻译是独立阶段，存量字幕可能还没中文
        #    （比如换了模型/解码方式之后回填）。少了这一步，那些内容会永远没译文。
        if not args.no_translate:
            resegment_pending(state)
            translate_pending(state)
        save_json(STATE, state)
        publish(state, feeds)
        log(f"=== 结束：无新增内容，用时 {time.monotonic() - t_start:.0f}s ===")
        return 0

    ok = fail = 0
    for i, (fid, eid, it) in enumerate(todo, 1):
        log(f"--- [{i}/{len(todo)}] {it['title'][:60]}")
        audio = AUDIO / f"{eid}.mp3"
        hosted = WWW_AUDIO / f"{eid}.mp3"
        srt = WWW / "subs" / f"{eid}.srt"
        # 只有「字幕真的产出了」才把音频搬进发布区。
        # 转写失败的音频留着没有意义 —— 客户端拿到一份没有字幕的音频，
        # 和它去原始 CDN 取是一样的，反而多占一份盘（还多一份版权副本）。
        publishable = False
        try:
            if not audio.exists():
                got = T.http_download(it["audioUrl"], str(audio))
                log(f"    已下载 {got / 1048576:.1f} MB")
            else:
                log("    音频已在本地，跳过下载")

            if not (WWW / "subs" / f"{eid}.srt").exists():
                if not run_asr(audio, srt, args.model, args.threads):
                    fail += 1
                    continue
            else:
                log("    字幕已存在，跳过转写")

            # ★ 先把字幕切成「一句一条」，再记进 state。
            #   顺序不能反：记完 state 紧接着就是翻译阶段，而翻译的粒度
            #   必须与字幕一致（否则译文会挂到错误的下标上）。
            rs_ok = run_resegment(srt)
            eps[eid] = {
                "status": "ok",
                # ★ 必须发布原始 guid（2.0 客户端要它）。
                #    客户端**不使用**服务端这个 eid，而是用 guid 自己算 id ——
                #    因为得和 1.0 已落盘的 id 对齐（收藏、播放进度、已下载的音频）
                #    全靠那个 id。算法只留在客户端一处（RssParser.stableId），
                #    服务端只负责如实转达 guid，不做第二份实现。
                #    实测 11 个源 11392 条**没有一条缺 guid**，所以：
                #      客户端 stableId(guid, audioUrl) == stableId(guid) == 1.0 的 id
                "guid": it.get("guid"),
                "feedId": fid,
                "feedTitle": it.get("feedTitle"),
                "title": it.get("title"),
                "description": (it.get("description") or "")[:400],
                # ⚠️ 这里存的是**原始 CDN 地址**，不是客户端最终拿到的那个 ——
                #    最终地址由 [publish] 按「音频文件在不在」逐条决定。
                #    这样做的理由有两条：① 音频被清理后条目能自动退回直连 CDN
                #    （而不是给客户端一个 404）；② 原始地址留着便于排查。
                "audioUrl": it.get("audioUrl"),
                "link": it.get("link"),
                "durationSec": it.get("durationSec"),
                # 封面（条目优先、回退频道）。客户端 1.0 是靠自己解析 RSS 拿这个的，
                # 2.0 客户端不再解析 RSS，所以必须由服务端如实转达 ——
                # 缺了它的后果很显眼：素材库与播放页的封面全变占位色块。
                # 4.4.0：封面跟音频一样由服务器托管 —— 原站点在大陆手机上
                # 未必拉得到（BBC / CBC 就是）。失败时退回原地址，不阻断发布。
                # 4.6.2：改成 `public_artwork` —— **盘上有就直接用**，
                #        这样存量节目也会自动指向托管地址（哪怕当初没抓成功过）。
                "artworkUrl": rehost_artwork(it.get("artworkUrl"), eid),
                "publishedAt": it["publishedAt"].isoformat() if it.get("publishedAt") else None,
                "srt": f"subs/{eid}.srt",
                # `rs` = 已按句子粒度重切分过（判据是它，不是内容 —— 不幂等）
                # `srtVer` 每次重切分 +1，客户端靠它发现本地字幕过期
                "rs": rs_ok,
                "srtVer": 1 if rs_ok else 0,
                "words": f"subs/{eid}.words.json",
                "transcribedAt": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
            }
            publishable = True
            save_json(STATE, state)
            ok += 1
        except Exception as e:
            log(f"    ❌ {type(e).__name__}: {e}")
            fail += 1
        finally:
            # ★ 2.1.9：转写完成后音频**搬去发布区**，不再删掉。
            #   理由见文件头的「为什么音频必须托管」——
            #   下完就删，等于客户端播的是**另一次广告拼接**，字幕必然错位。
            if audio.exists():
                try:
                    if publishable:
                        WWW_AUDIO.mkdir(parents=True, exist_ok=True)
                        shutil.move(str(audio), str(hosted))
                        log(f"    音频已入库 {hosted.name}（{hosted.stat().st_size / 1048576:.1f} MB）")
                    else:
                        audio.unlink()
                except Exception as e:
                    log(f"    ⚠️ 音频入库失败：{type(e).__name__}: {e}")

    # ── 翻译阶段：**在所有转写之后**统一做 ──────────────────────────────
    # 顺序不能反、也不能交叉：转写峰值 ~830 MB、翻译峰值 ~1050 MB，
    # 两者同时在场会顶到 1.9 GB（这台机器只有 1.93 GB）。
    # 各自是独立子进程 → 内存随进程退出归还，峰值也只看单个进程。
    if not args.no_translate and ok:
        # 先对齐粒度再翻译：翻译是按「句子」走的，字幕也得是「一句一条」，
        # 否则译文只挂得上句首那一行（见 resegment_pending 的说明）
        resegment_pending(state)
        translate_pending(state)

    # 清理过期字幕
    kept, dropped = [], []
    now = time.time()
    for eid, e in eps.items():
        pub = e.get("publishedAt")
        try:
            age = (now - __import__("datetime").datetime.fromisoformat(pub).timestamp()) / 86400 \
                if pub else 0
        except Exception:
            age = 0
        if age > RETENTION_DAYS:
            dropped.append(eid)
        else:
            kept.append(eid)
    for eid in dropped:
        # 译文必须和字幕一起删 —— 只删字幕会留下永远对不上的 zh.json
        for suffix in (".srt", ".words.json", ".zh.json"):
            f = WWW / "subs" / f"{eid}{suffix}"
            if f.exists():
                f.unlink()
        # ★ 托管的音频也要跟着删（2.1.9）。
        #   留着它有两个坏处：白占盘；以及**索引里已经没有这一条了、文件还在**，
        #   而 verify_publish 是双向核对的 —— 孤儿文件会让它报出来。
        hosted = WWW_AUDIO / f"{eid}.mp3"
        if hosted.exists():
            try:
                hosted.unlink()
            except Exception:
                pass
        eps.pop(eid, None)
    if dropped:
        log(f"清理过期字幕 {len(dropped)} 条（保留 {RETENTION_DAYS} 天）")

    save_json(STATE, state)
    publish(state, feeds)
    log(f"=== 结束：成功 {ok} 失败 {fail}，用时 {time.monotonic() - t_start:.0f}s ===")
    log(f"    磁盘：{shutil.disk_usage('/opt/ec').free / 2**30:.1f} GB 可用")
    return 0


def publish(state, feeds):
    """生成 App 拉的清单。

    ## `audioUrl` 是**逐条**决定的（2.1.9）

    | 音频文件在不在 `www/audio/<eid>.mp3` | 发布出去的 `audioUrl` | `hosted` |
    |---|---|---|
    | 在 | `{PUBLIC_BASE}/audio/<eid>.mp3` | `true` |
    | 不在 | **原始 CDN 地址**（state 里存着） | `false` |

    为什么不一律写托管地址：存量条目（2.1.9 之前转写的）服务器上**没有**音频，
    一律写托管地址会让它们全变 404 —— 客户端整集不可用。
    逐条判定的另一个好处是**自愈**：音频到了保留期被清掉，条目自动退回直连 CDN。

    ⚠️ 三处「只发布真的存在的东西」是同一个纪律，不要只改其中一处：
    字幕（`srt`）、译文（`zh`）、音频（`audioUrl`）。
    """
    eps = state.get("episodes", {})
    order = sorted(eps.items(),
                   key=lambda kv: kv[1].get("publishedAt") or "",
                   reverse=True)
    # ── 发布闸门（2.2.0）：字幕没备齐的条目**不发布** ────────────────────────
    #
    # 用户的诉求原话：「确保服务器端字幕都准备好，才在 app 端更新，
    # 如果字幕未更新好，不在 app 端刷新出这个音频素材。」
    #
    # 闸门要拦三种形态，缺一不可（旧版**只拦住了第 ① 种**）：
    #   ① state 里有 `srt` 路径、但文件不在（被打断 / 正在重做）
    #   ② state 里**根本没有 `srt` 字段或它是空串** —— 旧版的条件写作
    #      `if srt and not (WWW / srt).exists()`，`srt` 为空时短路成 False，
    #      于是这种条目**照样发布**。客户端拿到的是「一条没有字幕的音频」，
    #      而 2.1.0 拆掉端侧兜底之后，它等于不可用。
    #   ③ 英文字幕有了、**中文还没生成**。见下方 ZH_GATE 的说明。
    #
    # ⚠️ 只**拦住发布**，**不要**从 state 里删。它只是被打断/正在重做，
    #    下一轮的 `--rehost` / `translate_pending` 会把它捡起来补完。
    def _srt_ok(d) -> bool:
        srt = (d.get("srt") or "").strip()
        return bool(srt) and (WWW / srt).exists()

    def _zh_ok(d) -> bool:
        zh = (d.get("zh") or "").strip()
        return bool(zh) and (WWW / zh).exists()

    # ③ 为什么中文也要拦：2.1.0 把端侧翻译整条拆掉之后，**中文只有一个来源**（本服务）。
    #    放行「有英文没中文」的后果是这一集在用户那里**永远只有英文** —— 而它会一直
    #    留在列表里，看起来像「这一期的中文坏了」，而不像「还没做好」。
    #    拦掉的代价**很小**：翻译就在同一轮里紧挨着转写跑（见 main 的顺序），
    #    真会走到这里的只有「翻译失败」这一种，而 `translate_pending` 下一轮就补上
    #    —— 延迟一轮（半小时），不是丢失。
    #
    # ⚠️ 但它必须配一个**熔断**：如果因为「缺中文」被拦掉的比例过高，说明是系统性故障
    #    （模型坏了 / 磁盘满 / 权限错），而不是零星重做中。那时**宁可发出去**
    #    （英文版至少能听、能看）也不能交出一份空清单 ——
    #    空清单会让 App 显示「没有内容」，那是比「只有英文」严重得多的故障。
    ZH_GATE_MIN_SAMPLE = 5      # 样本太小时不启用熔断（几条内容正在重做是常态）
    ZH_GATE_MAX_SKIP_RATIO = 0.5

    hard_skip, zh_skip = [], []
    for eid, e in order:
        d = {"id": eid, **{k: v for k, v in e.items() if k != "status"}}
        if not _srt_ok(d):
            hard_skip.append(eid)
        elif not _zh_ok(d):
            zh_skip.append(eid)

    zh_skip_ids = set()
    candidates = len(order) - len(hard_skip)
    if zh_skip:
        ratio = len(zh_skip) / candidates if candidates else 1.0
        if candidates >= ZH_GATE_MIN_SAMPLE and ratio > ZH_GATE_MAX_SKIP_RATIO:
            log(f"    ⚠️ 缺中文的比例 {ratio:.0%} 超过熔断阈值"
                f"（{len(zh_skip)}/{candidates}）—— 判定为系统性故障，"
                f"照常发布（这批条目带 zhPending 标记）")
        else:
            zh_skip_ids = set(zh_skip)

    out_eps, with_zh, hosted_n, zh_pending = [], 0, 0, 0
    for eid, e in order:
        if eid in hard_skip or eid in zh_skip_ids:
            continue
        d = {"id": eid, **{k: v for k, v in e.items() if k != "status"}}
        if _zh_ok(d):
            with_zh += 1
        else:
            # 走到这里只可能是熔断放行。显式标记，让客户端知道
            # 「中文会来、现在还不算缺」——否则它会一直等一个不存在的东西。
            d.pop("zh", None)
            d["zhPending"] = True
            zh_pending += 1
        # ★ 封面：和音频同一条纪律 —— **盘上有就用托管地址**（4.6.2）
        if (WWW_ART / f"{eid}.jpg").exists():
            d["artworkUrl"] = f"{PUBLIC_BASE}/art/{eid}.jpg"
        # ★ 音频：在发布区就把地址换成自己的；不在就退回原始 CDN
        if (WWW_AUDIO / f"{eid}.mp3").exists():
            d["audioUrl"] = f"{PUBLIC_BASE}/audio/{eid}.mp3"
            d["hosted"] = True
            hosted_n += 1
        else:
            d["hosted"] = False
        out_eps.append(d)
    index = {
        "generatedAt": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
        "generator": "englishcommute-server/2.1.9",
        # ⚠️ 两个名字不同的字段，不要合并（本工程的纪律：位置不同的两件事不许同名）：
        #    · audioServed  —— **这个服务端**启用了音频托管（全局开关，用来判断
        #                      「能不能一直拿到同一份音频」，也就是字幕可不可信）
        #    · hosted       —— **这一条**的音频在不在本服务上（逐条，见上表）
        "audioServed": True,
        "hostedCount": hosted_n,
        # 让 App 一眼知道「这批内容带了中文」——没有 zh 字段的条目得自己端侧翻
        "translate": {"model": "opus-mt-en-zh", "set": TRANSLATE_SET,
                      "beams": TRANSLATE_BEAMS},
        "sources": [{"id": f, "url": u} for f, u in feeds],
        "count": len(out_eps),
        "episodes": out_eps,
    }
    save_json(WWW / "index.json", index)
    log(f"已发布清单：{len(out_eps)} 条（其中 {with_zh} 条带中文、{hosted_n} 条音频已托管）"
        f"→ {WWW / 'index.json'}")
    if hard_skip:
        log(f"    ⚠️ 跳过 {len(hard_skip)} 条：**英文字幕**没备齐"
            f"（被打断或正在重做，等 --rehost 补）")
    if zh_skip_ids:
        log(f"    ⚠️ 跳过 {len(zh_skip_ids)} 条：中文还没生成"
            f"（下一轮 translate_pending 补完，不会丢）")
    if zh_pending:
        log(f"    ⚠️ {zh_pending} 条带 zhPending 发布（熔断放行）—— 用户会先看到只有英文")

    # ── 词表增量（3.0.1）──────────────────────────────────────────────────
    # 把「最新语料里出现的、而客户端手里那份内置词表还没有的词」算出来，
    # 客户端同步时按需拉（实测 141 词 / 13 KB）。
    #
    # ⚠️ 挂在这里、而不是单独开一个 timer：**新词只可能来自新素材**，
    #    而这里正是「有新素材被发布」的收口处。放在别处要么跑得太勤
    #    （每轮 5 秒白跑），要么跑得太疏（新词滞后几小时）。
    refresh_vocab()

    # 读音必须跟着词表走 —— 否则会出现「本周新加的 algorithmic 有释义、
    # 点读音什么都没有」，而那个词可能永远等不到有人再手工跑一次全量。
    # 顺序要紧：**先有词表，才知道该给哪些词生成读音**。
    refresh_pron()


# 读音生成每轮最多占用多少秒。
#
# 为什么按**秒**而不是按**个数**：单词耗时不是常数。网络一慢，「50 个词」
# 能从 40 秒变成 5 分钟 —— 而循环是 15 分钟一轮，被它吃掉就意味着这一轮的
# 素材发布迟到。按秒算，才和「这一轮还能花多久」对得上。
#
# 180 秒 ≈ 每轮 250 个词（实测 1.4~1.6 词/秒）。而每轮真正新增的词通常
# 只有几十个 —— 所以正常情况下它远跑不满预算，只有全量补漏时才会跑满。
PRON_BUDGET_SECONDS = 180

# edge-tts 装在一个 venv 里，**不能用 sys.executable** ——
# cycle.py 用的是系统 python3，那里没有 edge_tts。
# （这个 venv 是 cp -r 复制过来的，bin/ 下的 shebang 曾指向旧路径 /tmp/ttsv，
#   已修；所以这里直接调绝对路径是安全的。）
PRON_PYTHON = "/opt/ec/ttsv/bin/python3"


def refresh_pron():
    """
    给「词表里有、读音还没生成」的词补读音（P0.4 接进循环）。

    ## 为什么是**增量**而不是每轮重跑全量

    脚本本身是断点续传的（已存在的 mp3 跳过），所以「重跑全量」实际上是
    「扫一遍 17000 个文件、发现都不缺、退出」—— 那也要几秒，而且每轮都做。
    用 `--budget-seconds` 把上限钉死，正常情况下它 1 秒内就退出了。

    ## 三条保护（都在 build_pron_v2.py 里，这里是它们存在的理由）

    1. **单实例锁**：现在可能正有一个手工跑的全量在跑。同一个微软在线接口
       不能有两个调用者 —— 并发只会互相拖慢 + 提高被限流的概率
       （`dictionaryapi` 40 个请求就封 IP 是同一种坑）。拿不到锁时脚本
       打印一行并退出 0，**这不是失败**。
    2. **时间预算**：见 [PRON_BUDGET_SECONDS]。
    3. **失败只记一行**：读音是锦上添花 —— 缺了它，「加词 + 复习」照常工作
       （只是没声音）。不能让「生成读音失败」把「这一轮的内容都别发布」
       带出来。这一点和 [refresh_vocab] 的判断完全一致。

    ## ⚠️ 一个必须知道的后果

    读音是按需下载的（客户端加词时才去取那一个），所以**服务端这边晚几天
    补上完全没关系**；但 `--prune` 不在循环里跑（它只删文件，应当人工确认）。
    """
    script = BIN / "build_pron_v2.py"
    if not script.exists():
        log("    ⚠️ 找不到 build_pron_v2.py，跳过读音生成")
        return
    if not Path(PRON_PYTHON).exists():
        log(f"    ⚠️ 找不到 {PRON_PYTHON}（edge-tts 的 venv），跳过读音生成")
        return

    try:
        r = subprocess.run(
            [PRON_PYTHON, str(script),
             "--budget-seconds", str(PRON_BUDGET_SECONDS), "--quiet"],
            capture_output=True, text=True,
            # ⚠️ subprocess 的超时要**比脚本自己的预算宽裕**，否则这里先
            #    SIGKILL，脚本就没机会打印「还剩多少」。宽裕 60 秒足够
            #    （脚本最多在预算到点后多生成一个词）。
            timeout=PRON_BUDGET_SECONDS + 60,
        )
    except subprocess.TimeoutExpired:
        log("    ⚠️ 读音生成超时（发布本身不受影响）")
        return

    out = (r.stdout or "").strip()
    if r.returncode != 0:
        tail = (r.stderr or out or "").strip()[-160:]
        log(f"    ⚠️ 读音生成失败（发布本身不受影响）：{tail}")
        return

    # ⚠️ **按内容找那一行，不按位置**（2026-09-23 修）。
    #
    #    原来写的是 `out.splitlines()[-2:]` —— 取最后两行。
    #    那是**位置判据**，而它错得非常隐蔽：
    #    脚本在「没有待生成」时会打印 20 行文件名对照表，
    #    于是最后两行成了对照表的尾巴，cycle.log 里每轮多两行
    #    「atlantic's -> atlantic_s.mp3」这种毫不相干的输出。
    #
    #    根因是**两处都在猜**：脚本以为 `--quiet` 安静了（其实没有），
    #    调用方以为「最后一两行就是结论」（其实取决于脚本打印了几行）。
    #    现在脚本那边让 `--quiet` 名副其实，这边**按前缀认那一行** ——
    #    脚本多打少打都与它无关。
    #
    #    （这是项目纪律里那条「判据不许落在代理量上」的又一实例：
    #      「倒数第二行」是代理量，「以『读音：』开头的那一行」才是事实。）
    picked = [ln.strip() for ln in out.splitlines() if ln.strip().startswith("读音：")]
    for ln in picked:
        log(f"    {ln}")
    if not picked:
        # 拿不到那一行**不能静默** —— 它说明脚本的输出格式变了（或者被截断了），
        # 而那是「这一轮到底补了几个」唯一的凭据。
        log("    ⚠️ 读音生成没有给出结论行（输出格式变了？）")


def refresh_vocab():
    """
    重建全量词表 + 算增量供客户端下载。

    ## 两个脚本的分工

        build_vocab.py      语料 → vocab.base（**全量**，每次重建，约 5 秒）
        build_vocab_add.py  vocab.base − vocab.base.published → add.json（**增量**）

    差集的意义见 build_vocab_add.py 的 docstring，一句话：
    **不要重新实现一遍「什么算新词」** —— 那样必然与主构建分叉，
    而本项目刚在读音文件名的归一化上吃过一次（168 个词形受害）。

    ## 为什么失败只记一行、不抛出去

    词表是**锦上添花**：拉不到增量，客户端用内置那份照常工作
    （只是新词的覆盖晚一轮）。而素材发布是主线 ——
    不能让「算词表失败」把「这一轮的内容全都别发布」带出来。
    """
    for script in ("build_vocab.py", "build_vocab_add.py"):
        path = BIN / script
        if not path.exists():
            log(f"    ⚠️ 找不到 {path}，跳过词表刷新")
            return
        try:
            r = subprocess.run([sys.executable, str(path)],
                               capture_output=True, text=True, timeout=900)
        except subprocess.TimeoutExpired:
            log(f"    ⚠️ {script} 超时（发布本身不受影响）")
            return
        if r.returncode != 0:
            tail = (r.stderr or r.stdout or "").strip()[-160:]
            log(f"    ⚠️ {script} 失败（发布本身不受影响）：{tail}")
            return

    try:
        meta = json.loads((WWW / "vocab" / "add.json").read_text(encoding="utf-8"))
        log(f"    词表增量：+{meta.get('baseCount', 0)} 词 / +{meta.get('formCount', 0)} 变形"
            f"（版本 {meta.get('version', '?')}）")
    except Exception as e:
        log(f"    ⚠️ 读不到 add.json：{e}")

    # 词表**整包**（3.3.0）：把全量表按内容版本发布成一份可独立下载的东西，
    # 让「更新词库」不必等下一次 App 版本。
    #
    # ⚠️ 它必须排在两个 build 之后 —— 它发布的就是它们刚写出来的那三个文件。
    #    放前面的话会发布上一轮的产物，而且**看不出来**（版本号照算、清单照写）。
    #
    # ⚠️ 幂等靠内容哈希：语料没变的一轮里它什么都不做（一次哈希 + 一次比对），
    #    所以挂在每 30 分钟一轮的 cycle 上是安全的。
    publish_vocab_pkg()


def publish_vocab_pkg():
    """
    发布词表**整包**（3.3.0）。见 `bin/publish_vocab.py` 的 docstring。

    ## 为什么单独一个函数，而不是并进上面那个 for 循环

    那个循环里的两个脚本是「**构建**」—— 从语料算出词表；这一个的输入
    就是它们刚产出的东西，是「**发布**」。两者的失败语义也不同：
    构建失败意味着「这一轮的词表是旧的」，发布失败意味着「客户端还看不到新版」
    —— 后者完全无害（客户端继续用手里那份），所以这里**只记一行**。

    ## 为什么失败绝不往上抛

    和 `refresh_vocab` 同一条纪律：词表是锦上添花，素材发布是主线。
    让「发词表包失败」把「这一轮的内容全都别发布」带出来，是拿主线赔支线。
    """
    path = BIN / "publish_vocab.py"
    if not path.exists():
        log("    ⚠️ 找不到 publish_vocab.py，跳过词表包发布")
        return
    try:
        r = subprocess.run([sys.executable, str(path)],
                           capture_output=True, text=True, timeout=300)
    except subprocess.TimeoutExpired:
        log("    ⚠️ 词表包发布超时（发布本身不受影响）")
        return
    if r.returncode != 0:
        tail = (r.stderr or r.stdout or "").strip()[-160:]
        log(f"    ⚠️ 词表包发布失败（发布本身不受影响）：{tail}")
        return
    # 只把「已是最新」或「清单已更新」那一行带出来 —— 前面的逐文件明细
    # 属于脚本自己的输出，粘进 cycle.log 只会把每轮日志撑长
    for line in reversed((r.stdout or "").splitlines()):
        s = line.strip()
        if s.startswith("✅") or s.startswith("清单已更新"):
            log(f"    词表包：{s}")
            return


if __name__ == "__main__":
    sys.exit(main())