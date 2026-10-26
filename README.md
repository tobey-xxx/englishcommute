# EnglishCommute

**Turn your commute into a study session.** A self-hosted pipeline that turns 16 English podcast feeds into
bilingual, sentence-level transcripts — plus an Android app where listening, reading and reviewing are one loop.

**English** · [中文](README.zh-CN.md) · [Architecture](docs/ARCHITECTURE.md) · [Attribution](ATTRIBUTION.md) · MIT

<p align="center"><img src="docs/architecture.svg" alt="Architecture: podcast RSS → self-hosted server (download, transcribe, translate) → Android app" width="100%"></p>

## Why another English-listening app

Most apps are a player plus a subtitle file fetched from the publisher, if one exists at all.
This one **owns the pipeline**:

| | Typical app | EnglishCommute |
| --- | --- | --- |
| Subtitles | publisher's file, often missing or out of sync | **you** transcribe the audio you actually host |
| Audio | streamed from third-party CDNs (ads get re-stitched → timestamps drift) | **served from your own domain** |
| On-device model | 100–700 MB download, minutes of waiting per episode | **none — 18 MB APK** |
| Data | telemetry SDKs | **local only; no ads, no analytics** |
| Cost | subscription | **a $5 VPS** (2 vCPU / 2 GB is enough) |

The point is not "offline". The point is that **the audio you hear and the subtitle you read are the same bytes** —
so sentence-level playback, tap-a-sentence-to-repeat, and word-level review actually line up.

## What you get

- **16 feeds, one tap** — NPR, BBC, CBC, WSJ, Al Jazeera, TED and more; the server pulls every 30 minutes.
- **Sentence-level subtitles with Chinese translation** — generated server-side (faster-whisper + translation),
  published as plain `.srt` you can read or edit.
- **Tap a sentence to replay it**, loop it, or jump to the next one.
- **Vocabulary with FSRS-6** — long-press a word in a subtitle: lemma lookup, the sentence it came from,
  then spaced repetition (front: recognise it, back: example + pronunciation + the original audio).
- **Streaks & check-in** — listening time counted locally, calendar view, monthly poster.
- **Optional multi-device sync** — log in and your check-ins / vocabulary sync; fully usable without an account.

## Quick start

**Just the app** (no server — falls back to parsing RSS locally, no subtitles):

```bash
./gradlew :app:assembleDebug
```

**With your own server:**

```bash
# 1. point the app at your domain at build time (never edit strings in source)
./gradlew :app:assembleDebug -PserverBaseUrl=https://your-domain.example

# 2. on a Debian/Ubuntu box (python3.11+, ffmpeg, nginx):
cp -r server /opt/ec
python3 -m venv /opt/ec/venv && /opt/ec/venv/bin/pip install -r server/api/requirements.txt
/opt/ec/venv/bin/python server/api/init_db.py
EC_PUBLIC_BASE=https://your-domain.example /opt/ec/venv/bin/python server/bin/cycle.py --dry-run
```

Full deployment (nginx, systemd, TLS, backups) → [`server/README.md`](server/README.md).

Two constraints worth knowing before you deploy:

- **`--workers 1`** — login rate-limiting is in-process state; more workers means the limit silently becomes *N×*.
- If you keep the IP fallback update channel, it must match `FALLBACK_BASE_URL` in `publish_update.py`.

## Repo layout

```
app/      Android client — content/ (catalog → local index → lazy download), player/ (Media3 + subtitle sync),
          word/ (FSRS-6), checkin/, sync/ (optional accounts), notify/ (background subtitle prefetch)
server/   FastAPI + SQLite; api/ (accounts, sync, catalog), bin/ (cycle.py pipeline, publish & admin scripts),
          nginx/ (reverse proxy + static, as templates)
tools/    build-time assertions (see docs/工程约定-构建与验证.md — why an always-true assertion is worse than none)
docs/     architecture, UI conventions, engineering conventions
```

## FAQ

**Why not use the publisher's transcript?** Many shows don't publish one; some publish a transcript for a
*differently stitched* audio (different ad inserts), which is worse than none — the timestamps lie.
Transcribing the exact file you serve is the only way to guarantee alignment.

**Why is the package `com.example.englishcommute`?** This repo is a scrubbed template: no real domain, server,
account or personal identifier. Change `applicationId` for your own build.

**Does it work without the server?** Yes, degraded: it parses RSS itself and plays audio — no subtitles,
no Chinese, no word review.

**Why no push notifications?** Vendor push in China requires corporate developer verification; FCM needs GMS.
A local alarm was the only option that worked, and it doesn't survive aggressive ROMs — so reminders were
removed in 4.7.0 rather than shipped broken. Progress lives in the app header instead.

## Known tradeoffs

- Listening to the same episode on two devices counts the time twice (the server sums per device).
- Example sentences for a word can differ between devices: the merge keeps *your* local order on purpose.
- Single point of failure: if the server is down, the app falls back to local RSS.

## License

- **Code**: [MIT](LICENSE).
- **Bundled third-party data**: dictionary from [ECDICT](https://github.com/skywind3000/ECDICT) (MIT),
  examples from [Tatoeba](https://tatoeba.org) (CC BY 2.0 FR), FSRS reference from
  [py-fsrs](https://github.com/open-spaced-repetition/py-fsrs) (MIT) — see [ATTRIBUTION](ATTRIBUTION.md).
- **Podcast content** (audio, cover art, transcripts of it) is **not** in this repo and remains the property of
  each publisher. Personal use only; check each source's terms before serving it to others.
