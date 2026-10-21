#!/usr/bin/env python3
"""
播客每日转写服务：Feed -> mp3 -> 带时间轴的 SRT + 词级 JSON + 清单

实测性能（macOS / CPU int8 / base.en）：5 分钟音频转写 17.3 秒（17.7x 实时）。

用法:
    python3 transcribe.py --out ./dist
    python3 transcribe.py --out ./dist --model small.en --per-feed 3 --hours 48
    python3 transcribe.py --out ./dist --no-audio --srt-only   # 只补字幕，不重下音频

产出目录:
    <out>/manifest/index.json
    <out>/audio/<guid>.mp3
    <out>/subs/<guid>.srt
    <out>/subs/<guid>.words.json
    <out>/state.json            # 幂等记账，重跑不重复转写

依赖: pip install faster-whisper    （CTranslate2 后端，不需要 torch，也不需要系统 ffmpeg）
"""

import os
import re
import sys
import json
import time
import html
import hashlib
import argparse
import datetime
import urllib.request
import urllib.error

UA = ("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
      "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36")

DEFAULT_FEEDS = {
    "npr-news-now": "https://feeds.npr.org/500005/podcast.xml",
    "cbc-world-this-hour": "https://www.cbc.ca/podcasting/includes/hourlynews.xml",
    "npr-up-first": "https://feeds.npr.org/510318/podcast.xml",
    "ted-talks-daily": "https://feeds.simplecast.com/54nAGcIl",
}

# ---------- 网络 ----------

def http_get(url, timeout=60, headers=None, retries=3):
    h = {"User-Agent": UA, "Accept": "*/*"}
    if headers:
        h.update(headers)
    last = None
    for i in range(retries):
        try:
            req = urllib.request.Request(url, headers=h)
            with urllib.request.urlopen(req, timeout=timeout) as r:
                return r.read()
        except Exception as e:
            last = e
            time.sleep(2 ** i)
    raise last


def http_download(url, dest, retries=3):
    """下载到 .tmp 后原子 rename，避免半截文件被当成完整音频。"""
    tmp = dest + ".tmp"
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    last = None
    for i in range(retries):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": UA})
            got = 0
            with urllib.request.urlopen(req, timeout=180) as r, open(tmp, "wb") as f:
                total = int(r.headers.get("Content-Length") or 0)
                while True:
                    chunk = r.read(262144)
                    if not chunk:
                        break
                    f.write(chunk)
                    got += len(chunk)
            if total and got < total:
                raise IOError(f"不完整: {got}/{total}")
            os.replace(tmp, dest)
            return got
        except Exception as e:
            last = e
            if os.path.exists(tmp):
                os.remove(tmp)
            time.sleep(2 ** i)
    raise last


# ---------- Feed 解析 ----------

def unescape(u):
    return (u.replace("&amp;", "&").replace("&#38;", "&")
             .replace("&quot;", '"').replace("&lt;", "<").replace("&gt;", ">"))


def strip_html(s):
    s = re.sub(r"<br\s*/?>", "\n", s, flags=re.I)
    s = re.sub(r"<[^>]+>", " ", s)
    s = html.unescape(s)
    s = re.sub(r"[ \t]+", " ", s)
    return re.sub(r"\n{3,}", "\n\n", s).strip()


def parse_duration(raw):
    if not raw:
        return None
    raw = raw.strip()
    if raw.isdigit():
        return int(raw)
    parts = [p for p in raw.split(":") if p != ""]
    try:
        parts = [float(p) for p in parts]
    except ValueError:
        return None
    sec = 0.0
    for p in parts:
        sec = sec * 60 + p
    return int(sec)


def parse_pubdate(raw):
    if not raw:
        return None
    import email.utils
    try:
        dt = email.utils.parsedate_to_datetime(raw.strip())
        if dt.tzinfo is None:
            dt = dt.replace(tzinfo=datetime.timezone.utc)
        return dt.astimezone(datetime.timezone.utc)
    except Exception:
        return None


def parse_feed(raw_bytes, feed_id):
    text = raw_bytes.decode("utf-8", "ignore")
    ch_title = re.search(r"<channel>.*?<title>(?:<!\[CDATA\[)?(.*?)(?:\]\]>)?</title>", text, re.S)
    feed_title = strip_html(ch_title.group(1)) if ch_title else feed_id

    # 频道封面 `<itunes:image href="...">`。⚠️ 只在**第一个 <item> 之前**收 ——
    # item 内部也有同名标签，不设这个界会把「某一集的节目封面」当成频道封面。
    # 与 App 的 RssParser 用的是同一条规则（那边注释里也写了这个理由）。
    head = text.split("<item>", 1)[0]
    mm = re.search(r"<itunes:image[^>]*href=[\"']([^\"']+)[\"']", head, re.I)
    channel_art = mm.group(1) if mm else None

    out = []
    for m in re.finditer(r"<item>(.*?)</item>", text, re.S):
        it = m.group(1)

        def pick(tag, flags=re.S):
            mm = re.search(rf"<{tag}[^>]*>(?:<!\[CDATA\[)?(.*?)(?:\]\]>)?</{tag}>", it, flags)
            return mm.group(1).strip() if mm else None

        title = strip_html(pick("title") or "")
        guid = pick("guid") or pick("link") or title
        if not guid:
            continue

        # 封面：条目自带优先，回退频道封面 —— 与 App 的 Episode.artworkUrl 同一规则。
        # 缺了它的后果很显眼：素材库与播放页的封面全变成占位色块。
        mm = re.search(r"<itunes:image[^>]*href=[\"']([^\"']+)[\"']", it, re.I)
        artwork = mm.group(1) if mm else channel_art

        audio = None
        for enc in re.findall(r"<enclosure\b[^>]*>", it, re.I):
            u = re.search(r'url=["\']([^"\']+)["\']', enc, re.I)
            t = re.search(r'type=["\']([^"\']+)["\']', enc, re.I)
            if not u:
                continue
            url = unescape(u.group(1))
            if (t and "audio" in t.group(1).lower()) or re.search(r"\.(mp3|m4a|aac|ogg)(\?|$)", url, re.I):
                audio = url
                break
        if not audio:
            continue

        out.append({
            "feedId": feed_id,
            "feedTitle": feed_title,
            "guid": guid,
            "title": title or guid,
            "description": strip_html(pick("description") or pick("itunes:summary") or ""),
            "audioUrl": audio,
            "durationSec": parse_duration(pick("itunes:duration")),
            "publishedAt": parse_pubdate(pick("pubDate")),
            "link": (pick("link") or "").strip(),
            "artworkUrl": artwork or "",
        })
    return out


def safe_name(guid):
    """确定性文件名。

    ⚠️ 这里**不能**用 `hash(guid)`：Python 对 str 的 hash 默认带随机盐
    （PYTHONHASHSEED 未固定时每个进程不同）。实测三次独立进程
    `hash("abc")` 得到三个不同值 —— 后果是同一条音频每跑一次就生成一个**新文件名**，
    state.json 的幂等记账形同虚设（旧的永远不会被命中），磁盘上会堆积重复字幕。
    sha1 是稳定的：同一 guid 永远得到同一个名字。
    """
    s = re.sub(r"[^A-Za-z0-9._-]", "_", guid)
    digest = hashlib.sha1(guid.encode("utf-8")).hexdigest()[:16]
    return f"{s[:80] or 'ep'}_{digest}"


# ---------- SRT 生成与后处理 ----------

def fmt_ts(sec):
    """Media3 只接受严格 3 位毫秒的 SRT 时间码，多一位少一位都会被静默丢弃。"""
    if sec < 0:
        sec = 0.0
    ms = int(round(sec * 1000))
    h, ms = divmod(ms, 3600000)
    m, ms = divmod(ms, 60000)
    s, ms = divmod(ms, 1000)
    return f"{h:02d}:{m:02d}:{s:02d},{ms:03d}"


def postprocess(segs, min_dur=1.0, gap_ms=20):
    """
    1) 合并过短碎片句段（Whisper 常从句尾切出 0.2-0.5s 的残句）
       - 优先并入前一句；若没有前一句（首句过短），改并入后一句
    2) 前后句边界相同则让前句早退 gap_ms，避免出现双高亮帧
    """
    # pass 1：向后合并（并入前一句）
    merged = []
    for s in segs:
        if not s["text"]:
            continue
        if merged and (s["end"] - s["start"]) < min_dur:
            prev = merged[-1]
            prev["text"] = (prev["text"] + " " + s["text"]).strip()
            prev["end"] = s["end"]
            continue
        merged.append(dict(s))

    # pass 2：首句过短时没有前句可并，改为并入后一句
    fixed = []
    for s in merged:
        if fixed and (s["end"] - s["start"]) < min_dur:
            prev = fixed[-1]
            prev["text"] = (prev["text"] + " " + s["text"]).strip()
            prev["end"] = s["end"]
            continue
        fixed.append(s)
    if len(fixed) >= 2 and (fixed[0]["end"] - fixed[0]["start"]) < min_dur:
        nxt = fixed[1]
        nxt["text"] = (fixed[0]["text"] + " " + nxt["text"]).strip()
        nxt["start"] = fixed[0]["start"]
        fixed.pop(0)
    merged = fixed

    for i in range(len(merged) - 1):
        if abs(merged[i]["end"] - merged[i + 1]["start"]) < 1e-6:
            merged[i]["end"] = max(merged[i]["start"] + 0.05,
                                   merged[i]["end"] - gap_ms / 1000.0)
    return merged


def write_srt(segs, path):
    lines = []
    for i, s in enumerate(segs, 1):
        lines.append(f"{i}\n{fmt_ts(s['start'])} --> {fmt_ts(s['end'])}\n{s['text']}\n")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(lines))
    os.replace(tmp, path)


def write_words(whisper_segs, path):
    """词级时间戳，供卡拉OK式逐词高亮。按句聚合，便于按当前句惰性加载。"""
    out = []
    for s in whisper_segs:
        ws = [{"word": w.word, "start": round(w.start, 3), "end": round(w.end, 3)}
              for w in (s.words or [])]
        out.append({"start": round(s.start, 3), "end": round(s.end, 3),
                    "text": s.text.strip(), "words": ws})
    os.makedirs(os.path.dirname(path), exist_ok=True)
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8", newline="\n") as f:
        json.dump(out, f, ensure_ascii=False)
    os.replace(tmp, path)


# ---------- 主流程 ----------

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="./dist", help="产出根目录")
    ap.add_argument("--feeds", help="feeds.txt，每行 feedUrl 或 feedId=feedUrl")
    ap.add_argument("--model", default="base.en", help="tiny.en / base.en / small.en")
    ap.add_argument("--per-feed", type=int, default=2, help="每源每轮最多处理几条")
    ap.add_argument("--hours", type=int, default=48, help="只处理最近 N 小时内发布的")
    ap.add_argument("--min-dur", type=float, default=1.0, help="合并短于该秒数的碎片句段")
    ap.add_argument("--no-audio", action="store_true", help="跳过 mp3 下载")
    ap.add_argument("--force", action="store_true", help="忽略记账，强制重转写")
    args = ap.parse_args()

    from faster_whisper import WhisperModel

    out = os.path.abspath(args.out)
    for d in ("audio", "subs", "manifest"):
        os.makedirs(os.path.join(out, d), exist_ok=True)

    feeds = dict(DEFAULT_FEEDS)
    if args.feeds:
        feeds = {}
        for line in open(args.feeds, encoding="utf-8"):
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            if "=" in line:
                k, v = line.split("=", 1)
                feeds[k.strip()] = v.strip()
            else:
                feeds[safe_name(line)] = line

    state_path = os.path.join(out, "state.json")
    state = json.load(open(state_path)) if os.path.exists(state_path) else {"done": {}}

    cutoff = datetime.datetime.now(datetime.timezone.utc) - datetime.timedelta(hours=args.hours)

    print(f"=== 拉取 {len(feeds)} 个源（只取最近 {args.hours} 小时）===")
    eps = []
    for fid, url in feeds.items():
        try:
            raw = http_get(url, timeout=45)
            items = parse_feed(raw, fid)
            fresh = [e for e in items if e["publishedAt"] and e["publishedAt"] >= cutoff]
            fresh.sort(key=lambda e: e["publishedAt"], reverse=True)
            fresh = fresh[:args.per_feed]
            eps.extend(fresh)
            print(f"  [OK] {fid:22s} 条目={len(items):4d} 近期={len(fresh)}")
        except Exception as e:
            print(f"  [!!] {fid:22s} 拉取失败: {type(e).__name__}: {e}")

    todo = [e for e in eps if args.force or e["guid"] not in state["done"]]
    print(f"\n=== 待处理 {len(todo)} 条（已记账跳过 {len(eps) - len(todo)} 条）===")

    model = None
    manifest = []

    for ep in eps:
        guid = ep["guid"]
        name = safe_name(guid)
        audio_path = os.path.join(out, "audio", name + ".mp3")
        srt_path = os.path.join(out, "subs", name + ".srt")
        words_path = os.path.join(out, "subs", name + ".words.json")

        rec = {
            "guid": guid, "feedId": ep["feedId"], "feedTitle": ep["feedTitle"],
            "title": ep["title"], "publishedAt": ep["publishedAt"].isoformat() if ep["publishedAt"] else None,
            "durationSec": ep["durationSec"], "link": ep["link"],
            "audio": f"audio/{name}.mp3", "audioBytes": None,
            "srt": None, "words": None,
            "transcriptStatus": "missing", "transcriptModel": None,
        }

        if guid in state["done"] and not args.force:
            prev = state["done"][guid]
            rec.update(prev)
            manifest.append(rec)
            continue

        t0 = time.time()
        try:
            if not args.no_audio:
                if os.path.exists(audio_path):
                    rec["audioBytes"] = os.path.getsize(audio_path)
                else:
                    rec["audioBytes"] = http_download(ep["audioUrl"], audio_path)
                    print(f"  下载 {name}.mp3  {rec['audioBytes']/1048576:.1f} MB")

            if model is None:
                print(f"  加载模型 {args.model} ...")
                model = WhisperModel(args.model, device="cpu", compute_type="int8")

            segments, info = model.transcribe(
                audio_path, language="en", beam_size=5,
                vad_filter=True, word_timestamps=True,
            )
            raw_segs = list(segments)

            segs = [{"start": s.start, "end": s.end, "text": s.text.strip()} for s in raw_segs]
            segs = postprocess(segs, min_dur=args.min_dur)

            write_srt(segs, srt_path)
            write_words(raw_segs, words_path)

            rec.update({
                "srt": f"subs/{name}.srt", "words": f"subs/{name}.words.json",
                "transcriptStatus": "ok", "transcriptModel": args.model,
            })
            if not rec["durationSec"] and raw_segs:
                rec["durationSec"] = int(raw_segs[-1].end)

            dur = raw_segs[-1].end if raw_segs else 0
            el = time.time() - t0
            print(f"  [OK] {ep['title'][:46]:48s} 句段 {len(raw_segs)}->{len(segs)} "
                  f"音频 {dur:.0f}s 耗时 {el:.0f}s ({dur/el if el else 0:.1f}x)")

        except Exception as e:
            rec["transcriptStatus"] = "failed"
            print(f"  [!!] {ep['title'][:46]:48s} 失败: {type(e).__name__}: {e}")

        state["done"][guid] = {k: rec[k] for k in
                               ("audio", "audioBytes", "srt", "words",
                                "transcriptStatus", "transcriptModel", "durationSec")}
        manifest.append(rec)

    manifest.sort(key=lambda r: (r.get("publishedAt") or ""), reverse=True)
    with open(state_path, "w", encoding="utf-8") as f:
        json.dump(state, f, ensure_ascii=False, indent=1)

    index = {
        "generatedAt": datetime.datetime.now().astimezone().isoformat(timespec="seconds"),
        "model": args.model,
        "episodes": manifest,
    }
    ip = os.path.join(out, "manifest", "index.json")
    with open(ip, "w", encoding="utf-8", newline="\n") as f:
        json.dump(index, f, ensure_ascii=False, indent=1)

    ok = sum(1 for r in manifest if r["transcriptStatus"] == "ok")
    print(f"\n=== 完成：清单 {len(manifest)} 条，字幕就绪 {ok} 条 ===")
    print(f"清单: {ip}")
    print("把整个 --out 目录上传到静态托管即可供 App 消费。")


if __name__ == "__main__":
    main()
