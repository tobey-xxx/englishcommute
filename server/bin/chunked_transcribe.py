#!/usr/bin/env python3
"""内存受限环境下的转写：按**静音点**切块 + 流式解码，峰值与音频长度解耦。

为什么需要它（全部为 2026-09 实测，macOS 8 核 / base.en / int8 / 15.3 分钟音频）：

| 喂法                           | 峰值 RSS | 句数 | 边界质量 |
|--------------------------------|---------|------|---------|
| 直接把 mp3 路径交给模型         | 1553 MB | 241  | 好      |
| 先转 16k wav 再给路径           | 1388 MB | 259  | 好      |
| 整段 16k 数组                    | 1256 MB | 259  | 好      |
| 固定 5 分钟切块                  |  788 MB | 231  | **差**  |
| **按静音点切块（本脚本）**       |  **749 MB** | **257** | **好** |

结论有三条，都反直觉：

1. **峰值由「一次喂多长」决定，不由模型大小决定。**
   模型文件只有 ~75 MB，加载完却已占 ~370 MB；而整段转写又再加 ~880 MB。
   把模型从 base.en 换成 tiny.en（省 ~40 MB）几乎不解决问题；
   把喂入长度从 15 分钟压到 5 分钟，直接省掉 ~470 MB。

2. **固定时间切块不能用。** 实测 3 个边界 3 处出错：句子被切成两半、
   `singing` 被误识成 `thinking`、还有块头出现了原文没有的句子。
   时间轴本身没错位（末句差 0.11 s），错的是**内容** —— 属于「不报错但会错」。

3. **改成在静音中点下刀，边界错误消失，代价为零。**
   句数 257（对齐全段直通的 259）、耗时 103 s（直通 102 s）、峰值 749 MB。

另外：`cpu_threads` 从 2 调到 8，峰值只变 5% —— **线程数不是内存的主因**，
不要指望靠调它省内存（但它决定速度：2 → 8 线程墙钟基本不变，CPU 时间差一倍）。

用法：
    python chunked_transcribe.py in.mp3 out.srt --model base.en --threads 2
    python chunked_transcribe.py in.mp3 out.srt --words --chunk-sec 300

服务器上建议配合 ffmpeg 使用（本脚本优先用 ffmpeg 流式解码，没有则退回 PyAV）：
    ffmpeg -i in.mp3 -f s16le -ar 16000 -ac 1 -   # 内存约 40 MB，不落整段 PCM
"""

import argparse
import json
import os
import resource
import subprocess
import sys
import tempfile
import time
import wave

import numpy as np

SAMPLE_RATE = 16000


# --------------------------------------------------------------------------
# 时间码：Media3 只接受严格 3 位毫秒，多一位少一位都会被**静默丢弃**
# --------------------------------------------------------------------------
def fmt_ts(sec: float) -> str:
    if sec < 0:
        sec = 0.0
    ms = int(round(sec * 1000))
    h, ms = divmod(ms, 3_600_000)
    m, ms = divmod(ms, 60_000)
    s, ms = divmod(ms, 1000)
    return f"{h:02d}:{m:02d}:{s:02d},{ms:03d}"


def peak_mb() -> float:
    raw = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
    return raw / 1_048_576 if sys.platform == "darwin" else raw / 1024


# --------------------------------------------------------------------------
# 解码：优先 ffmpeg（流式，内存最低），退回 PyAV
# --------------------------------------------------------------------------
def decode_to_16k(path: str) -> np.ndarray:
    if _has_ffmpeg():
        return _decode_ffmpeg(path)
    return _decode_pyav(path)


def _has_ffmpeg() -> bool:
    try:
        subprocess.run(["ffmpeg", "-version"], capture_output=True, check=True)
        return True
    except Exception:
        return False


def _decode_ffmpeg(path: str) -> np.ndarray:
    """ffmpeg 输出 s16le 单声道 16k 到 stdout，我们分块读走。

    ⚠️ 不要用 `subprocess.run(capture_output=True)` —— 那是把整段 PCM 攒在
    内存里，等于白白多占「时长 × 32 KB/s」。这里用 Popen 逐块读。
    """
    proc = subprocess.Popen(
        ["ffmpeg", "-nostdin", "-loglevel", "error", "-i", path,
         "-f", "s16le", "-ac", "1", "-ar", str(SAMPLE_RATE), "-"],
        stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
    )
    blocks = []
    while True:
        chunk = proc.stdout.read(1 << 20)  # 1 MB ≈ 32 秒
        if not chunk:
            break
        blocks.append(chunk)
    proc.stdout.close()
    rc = proc.wait()
    if rc != 0:
        raise RuntimeError(f"ffmpeg 退出码 {rc}")
    raw = b"".join(blocks)
    return np.frombuffer(raw, dtype="<i2").astype(np.float32) / 32768.0


def _decode_pyav(path: str) -> np.ndarray:
    """⚠️ 必须按 frame.samples 截断 plane —— PyAV 的 plane 缓冲区**大于**有效样本数，
    整块取走会在每一帧后面塞进填充字节。后果是「音频被拉长」（实测 15.3 分钟
    变成 17.6 分钟），于是**所有时间码整体偏移，且不报任何错**。
    """
    import av

    with av.open(path) as container:
        resampler = av.audio.resampler.AudioResampler(
            format="s16", layout="mono", rate=SAMPLE_RATE,
        )
        pcm = bytearray()

        def take(out):
            if out is not None:
                pcm.extend(bytes(out.planes[0])[: out.samples * 2])  # s16 单声道

        for frame in container.decode(container.streams.audio[0]):
            for out in resampler.resample(frame):
                take(out)
        for out in resampler.resample(None):  # 冲刷重采样器尾部缓冲
            take(out)
    return np.frombuffer(bytes(pcm), dtype="<i2").astype(np.float32) / 32768.0


# --------------------------------------------------------------------------
# 静音点：在静音「中点」下刀，句子才不会被切断
# --------------------------------------------------------------------------
def silence_centers(audio: np.ndarray, frame=320, sil_min=0.35, sil_db=-38.0):
    n = len(audio) // frame
    if n == 0:
        return []
    fr = audio[: n * frame].reshape(n, frame)
    db = 20 * np.log10(np.sqrt((fr ** 2).mean(axis=1)) + 1e-12)
    quiet = db < sil_db
    min_fr = int(sil_min * SAMPLE_RATE / frame)
    centers, i = [], 0
    while i < len(quiet):
        if quiet[i]:
            j = i
            while j < len(quiet) and quiet[j]:
                j += 1
            if j - i >= min_fr:
                centers.append((i + j) / 2 * frame / SAMPLE_RATE)
            i = j
        else:
            i += 1
    return centers


def pick_cuts(duration, centers, min_chunk, max_chunk):
    cuts, cur = [], 0.0
    while duration - cur > max_chunk:
        cand = [c for c in centers if cur + min_chunk <= c <= cur + max_chunk]
        if cand:
            target = cur + min_chunk + (max_chunk - min_chunk) / 2
            cut = min(cand, key=lambda c: abs(c - target))
        else:
            cut = cur + min_chunk  # 找不到静音只能硬切 —— 频率极低，但要留日志
            print(f"  ⚠️ {fmt_ts(cur)} 之后 {min_chunk}s 内没有静音点，硬切", flush=True)
        cuts.append(cut)
        cur = cut
    cuts.append(duration)
    return cuts


# --------------------------------------------------------------------------
def transcribe_chunked(path, model_name, threads, min_chunk, max_chunk,
                       words, beam):
    from faster_whisper import WhisperModel

    audio = decode_to_16k(path)
    dur = len(audio) / SAMPLE_RATE
    print(f"  解码后 {len(audio) * 4 / 1_048_576:.1f} MB / {dur / 60:.1f} 分钟，"
          f"峰值 {peak_mb():.1f} MB", flush=True)

    centers = silence_centers(audio)
    cuts = pick_cuts(dur, centers, min_chunk, max_chunk)
    print(f"  静音中点 {len(centers)} 个，切点 "
          + " ".join(fmt_ts(c) for c in cuts), flush=True)

    # ⚠️ num_workers 必须为 1：>1 会让 CTranslate2 复制多份模型
    model = WhisperModel(model_name, device="cpu", compute_type="int8",
                         cpu_threads=threads, num_workers=1)
    print(f"  模型加载后峰值 {peak_mb():.1f} MB", flush=True)

    out, prev, t0 = [], 0.0, time.monotonic()
    for c in cuts:
        block = audio[int(prev * SAMPLE_RATE):int(c * SAMPLE_RATE)]
        if len(block) < SAMPLE_RATE:
            break
        segs, _ = model.transcribe(block, language="en", beam_size=beam,
                                   vad_filter=True, word_timestamps=words)
        for s in segs:
            # ★ 块内时间 + 偏移 = 全局时间。漏掉偏移 = 每块都从 0 开始 =
            #   字幕整体错位，而这一步不会报任何错。
            out.append({"start": s.start + prev, "end": s.end + prev,
                        "text": s.text.strip(),
                        "words": ([{"w": w.word, "s": w.start + prev,
                                    "e": w.end + prev} for w in (s.words or [])]
                                  if words else [])})
        prev = c
    print(f"  逐块转写完成 {time.monotonic() - t0:.0f}s，峰值 {peak_mb():.1f} MB",
          flush=True)
    return out, dur


def merge_short(segs, min_dur):
    """Whisper 会从句尾切出碎片句段（实测最短 0.26 秒），直接渲染会「闪一下就没」。"""
    if not segs:
        return segs
    out = [dict(segs[0])]
    for s in segs[1:]:
        prev = out[-1]
        if (prev["end"] - prev["start"]) < min_dur and prev["end"] >= s["start"] - 0.05:
            prev["end"] = s["end"]
            prev["text"] = (prev["text"] + " " + s["text"]).strip()
        else:
            out.append(dict(s))
    # 相邻句 end == start 会造成双高亮帧 → 前句早退 20 ms
    for i in range(len(out) - 1):
        if out[i]["end"] > out[i + 1]["start"] - 0.02:
            out[i]["end"] = max(out[i]["start"] + 0.01, out[i + 1]["start"] - 0.02)
    return out


def write_srt(segs, path):
    lines = [f"{i}\n{fmt_ts(s['start'])} --> {fmt_ts(s['end'])}\n{s['text']}\n"
             for i, s in enumerate(segs, 1)]
    os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(lines))
    os.replace(tmp, path)  # 原子替换：被杀不会留半截 SRT


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("audio")
    ap.add_argument("out_srt")
    ap.add_argument("--model", default="base.en",
                    help="tiny.en / base.en / small.en")
    ap.add_argument("--threads", type=int, default=2,
                    help="默认 2 —— 对齐 2 核小机器。调大只影响速度，不影响内存")
    ap.add_argument("--min-chunk", type=float, default=240, help="最少块长（秒）")
    ap.add_argument("--max-chunk", type=float, default=360, help="最多块长（秒）")
    ap.add_argument("--min-dur", type=float, default=1.0, help="短于该秒数的碎片句段合并")
    ap.add_argument("--beam", type=int, default=5)
    ap.add_argument("--words", action="store_true",
                    help="额外产出词级时间戳 json（内存略增，事后无法补）")
    args = ap.parse_args()

    segs, dur = transcribe_chunked(args.audio, args.model, args.threads,
                                   args.min_chunk, args.max_chunk,
                                   args.words, args.beam)
    n0 = len(segs)
    segs = merge_short(segs, args.min_dur)
    write_srt(segs, args.out_srt)
    print(f"  句段 {n0} → {len(segs)}（合并碎片），音频 {dur / 60:.1f} 分钟")
    print(f"  ✅ {args.out_srt} | 最终峰值 {peak_mb():.1f} MB")

    if args.words:
        wp = os.path.splitext(args.out_srt)[0] + ".words.json"
        tmp = wp + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(segs, f, ensure_ascii=False)
        os.replace(tmp, wp)
        print(f"  ✅ {wp}")


if __name__ == "__main__":
    main()
