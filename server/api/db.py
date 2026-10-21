#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
SQLite 存储层：表结构 + 每个操作一个函数。**没有 ORM。**

## 为什么是 SQLite 而不是「再起一个数据库」

规模：几十个用户、每人每天一行。这个量级上，SQLite 的读写比跨进程的
PostgreSQL 更快，而且**备份就是拷一个文件** —— 对一台 2 核 2G 的小机器来说，
「少一个常驻服务、少一处要记得升级的东西」比任何特性都值钱。

## 三条纪律

1. **WAL 模式**：读写不互相阻塞（发布脚本在跑的同时，App 还能同步）。
2. **每次操作一个连接**：SQLite 的连接很便宜，而长期持有的连接会在
   「进程被 systemd 重启」这类事件里留下状态。用 `with connect() as c`。
3. **不存任何可以由别处算出来的东西**。这里只存事实（谁、哪天、哪台设备、多少秒），
   「今天听了几分钟」永远是 Σ —— 一旦把它也存下来，就有了两个真相源。
"""
import os
import sqlite3
import time

DB_PATH = os.environ.get("EC_DB", "/opt/ec/data/ec.db")

# 每位用户保留的历史天数上限（防止某天有人把 clock 调错，一次写入几万个 day 行）
MAX_DAYS = 2000

SCHEMA = """
CREATE TABLE IF NOT EXISTS users (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    name        TEXT    NOT NULL UNIQUE,
    pw_hash     TEXT    NOT NULL,
    pw_salt     TEXT    NOT NULL,
    status      TEXT    NOT NULL,          -- pending | active | rejected
    created_at  INTEGER NOT NULL,
    decided_at  INTEGER,
    note        TEXT    NOT NULL DEFAULT ''
);

CREATE TABLE IF NOT EXISTS tokens (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id      INTEGER NOT NULL REFERENCES users(id),
    token_hash   TEXT    NOT NULL UNIQUE,
    device       TEXT    NOT NULL DEFAULT '',
    created_at   INTEGER NOT NULL,
    last_seen_at INTEGER NOT NULL,
    expires_at   INTEGER NOT NULL,
    revoked      INTEGER NOT NULL DEFAULT 0
);

-- ★ 打卡计数：**每台设备只写自己那一列**（见 README「多设备同步」）
CREATE TABLE IF NOT EXISTS daily (
    user_id    INTEGER NOT NULL REFERENCES users(id),
    day        INTEGER NOT NULL,
    device     TEXT    NOT NULL,
    seconds    INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    PRIMARY KEY (user_id, day, device)
);

-- 每日目标的时间轴（与客户端 CheckInStore 的 `goal:<day>` 一一对应）
CREATE TABLE IF NOT EXISTS goals (
    user_id    INTEGER NOT NULL REFERENCES users(id),
    day        INTEGER NOT NULL,
    seconds    INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    PRIMARY KEY (user_id, day)
);

-- 生词卡（4.1.0）：不可变字段 + **两个只增的时间戳**
--
-- ★ 存在判据：added_at > deleted_at。两者都只在合并时取 MAX。
--   用「两个时间戳取 MAX」而不是「谁晚听谁的」，是为了让
--   **删除后再添加**也自洽：重新添加会给 added_at 盖一个新时间戳。
CREATE TABLE IF NOT EXISTS word_cards (
    user_id    INTEGER NOT NULL REFERENCES users(id),
    lemma      TEXT    NOT NULL,
    surface    TEXT    NOT NULL DEFAULT '',
    added_at   INTEGER NOT NULL DEFAULT 0,
    deleted_at INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (user_id, lemma)
);

-- 语境：**只增**（身份 = 哪一集的哪一行），INSERT OR IGNORE 即幂等
CREATE TABLE IF NOT EXISTS word_contexts (
    user_id       INTEGER NOT NULL REFERENCES users(id),
    lemma         TEXT    NOT NULL,
    episode_id    TEXT    NOT NULL,
    line_start_ms INTEGER NOT NULL,
    episode_title TEXT    NOT NULL DEFAULT '',
    line_end_ms   INTEGER NOT NULL DEFAULT 0,
    sentence      TEXT    NOT NULL DEFAULT '',
    translation   TEXT,
    PRIMARY KEY (user_id, lemma, episode_id, line_start_ms)
);

-- ★ 复习事件：**只增**，而且带一个单调的 seq 作为客户端拉取的水位线。
--   身份 = (谁, 哪台设备, 什么时候) ⇒ 两台设备各自离线复习同一天，两条都留得住。
--   ⚠️ 卡片的 FSRS 状态**不存**：它是这些事件的投影，由客户端重放算出
--      （见客户端 WordMerge.replay）。存了就是第二个真相源。
CREATE TABLE IF NOT EXISTS word_reviews (
    seq     INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id INTEGER NOT NULL REFERENCES users(id),
    lemma   TEXT    NOT NULL,
    device  TEXT    NOT NULL DEFAULT '',
    at_ms   INTEGER NOT NULL,
    rating  INTEGER NOT NULL,
    UNIQUE (user_id, lemma, device, at_ms)
);

-- 忘记密码的重置申请（审批制：管理员批准后才有码）
CREATE TABLE IF NOT EXISTS resets (
    user_id    INTEGER NOT NULL REFERENCES users(id),
    code_hash  TEXT,
    requested_at INTEGER NOT NULL,
    expires_at INTEGER,
    used_at    INTEGER
);
"""


def connect() -> sqlite3.Connection:
    os.makedirs(os.path.dirname(DB_PATH), exist_ok=True)
    c = sqlite3.connect(DB_PATH, timeout=10)
    c.row_factory = sqlite3.Row
    c.execute("PRAGMA journal_mode=WAL")
    c.execute("PRAGMA foreign_keys=ON")
    c.execute("PRAGMA synchronous=NORMAL")
    return c


def init() -> None:
    with connect() as c:
        c.executescript(SCHEMA)


def now() -> int:
    return int(time.time())


# ---------------------------------------------------------------------------
# 用户
# ---------------------------------------------------------------------------


def create_user(name: str, pw_hash: str, pw_salt: str, status: str = "pending") -> int:
    with connect() as c:
        cur = c.execute(
            "INSERT INTO users (name, pw_hash, pw_salt, status, created_at)"
            " VALUES (?, ?, ?, ?, ?)",
            (name, pw_hash, pw_salt, status, now()),
        )
        return int(cur.lastrowid)


def get_user(name: str) -> sqlite3.Row | None:
    with connect() as c:
        return c.execute("SELECT * FROM users WHERE name = ?", (name,)).fetchone()


def get_user_by_id(uid: int) -> sqlite3.Row | None:
    with connect() as c:
        return c.execute("SELECT * FROM users WHERE id = ?", (uid,)).fetchone()


def set_status(name: str, status: str, note: str = "") -> bool:
    with connect() as c:
        cur = c.execute(
            "UPDATE users SET status = ?, decided_at = ?, note = ? WHERE name = ?",
            (status, now(), note, name),
        )
        return cur.rowcount > 0


def list_users() -> list[sqlite3.Row]:
    with connect() as c:
        return c.execute(
            "SELECT u.*, (SELECT MAX(last_seen_at) FROM tokens t"
            "  WHERE t.user_id = u.id AND t.revoked = 0) AS seen"
            " FROM users u ORDER BY u.created_at"
        ).fetchall()


def list_pending() -> list[sqlite3.Row]:
    with connect() as c:
        return c.execute(
            "SELECT * FROM users WHERE status = 'pending' ORDER BY created_at"
        ).fetchall()


# ---------------------------------------------------------------------------
# 令牌
# ---------------------------------------------------------------------------


def issue_token(user_id: int, token_hash: str, device: str, days: int) -> int:
    t = now()
    with connect() as c:
        cur = c.execute(
            "INSERT INTO tokens (user_id, token_hash, device, created_at, last_seen_at,"
            " expires_at) VALUES (?, ?, ?, ?, ?, ?)",
            (user_id, token_hash, device, t, t, t + days * 86400),
        )
        return int(cur.lastrowid)


def find_token(token_hash: str) -> sqlite3.Row | None:
    """只返回**未撤销且未过期**的令牌行。"""
    with connect() as c:
        return c.execute(
            "SELECT * FROM tokens WHERE token_hash = ? AND revoked = 0 AND expires_at > ?",
            (token_hash, now()),
        ).fetchone()


def touch_token(token_id: int, last_seen_at: int, extend_hours: int, days: int) -> None:
    """续期（滑动过期）。

    用户的明确要求是「**不登出**」：只要还在用，就不该被踢出去。
    所以每次用到令牌就把它往后推 —— 真正会过期的只有**被彻底遗忘**的那些
    （比如换手机之后留在旧机器上没人碰的那一份）。
    """
    t = now()
    with connect() as c:
        c.execute(
            "UPDATE tokens SET last_seen_at = ?,"
            " expires_at = MAX(expires_at, ?) WHERE id = ?",
            (t, t + days * 86400, token_id),
        )


def revoke_token(token_hash: str) -> None:
    with connect() as c:
        c.execute("UPDATE tokens SET revoked = 1 WHERE token_hash = ?", (token_hash,))


def revoke_all(user_id: int) -> int:
    """撤销该账号**当前还有效**的全部令牌，返回撤销了几个。

    ⚠️ `AND revoked = 0` 不是可有可无：没有它，rowcount 会把历史上
       早就撤销过的行也算进去 —— 而「踢掉了几台设备」是管理员判断
       「这次重置有没有真的生效」的依据，不能是个虚数。
    """
    with connect() as c:
        cur = c.execute(
            "UPDATE tokens SET revoked = 1 WHERE user_id = ? AND revoked = 0",
            (user_id,),
        )
        return cur.rowcount


def live_token_count(user_id: int) -> int:
    with connect() as c:
        return int(c.execute(
            "SELECT COUNT(*) FROM tokens WHERE user_id = ? AND revoked = 0"
            " AND expires_at > ?", (user_id, now()),
        ).fetchone()[0])


# ---------------------------------------------------------------------------
# 打卡计数（★ 求和模型的核心）
# ---------------------------------------------------------------------------


def bump_daily(user_id: int, device: str, day: int, seconds: int) -> None:
    """写下「这台设备在这一天累计听了多少秒」。

    ★ `MAX(seconds, excluded.seconds)` 这一句是整个多设备同步的地基：

      · **幂等** —— 重复请求、断网重试、乱序到达都不会把值改小；
      · **永不冲突** —— 每个主键格子只有一台设备会写（它自己那一列）；
      · 于是「今天一共听了多少」= Σ 所有设备，可以在任何时刻重算，
        且不需要任何「保留哪一份」的交互。
    """
    sec = max(0, min(int(seconds), 24 * 3600))
    with connect() as c:
        c.execute(
            "INSERT INTO daily (user_id, day, device, seconds, updated_at)"
            " VALUES (?, ?, ?, ?, ?)"
            " ON CONFLICT(user_id, day, device) DO UPDATE SET"
            "   seconds = MAX(seconds, excluded.seconds),"
            "   updated_at = excluded.updated_at",
            (user_id, int(day), device[:64], sec, now()),
        )


def merged_days(user_id: int, since: int) -> dict[int, int]:
    """`day → 全设备合计秒数`。只由 [bump_daily] 写下的行算出。"""
    with connect() as c:
        rows = c.execute(
            "SELECT day, SUM(seconds) AS total FROM daily"
            " WHERE user_id = ? AND day >= ? GROUP BY day",
            (user_id, int(since)),
        ).fetchall()
    return {int(r["day"]): int(r["total"]) for r in rows}


def device_count(user_id: int) -> int:
    with connect() as c:
        return int(c.execute(
            "SELECT COUNT(DISTINCT device) FROM daily WHERE user_id = ?", (user_id,),
        ).fetchone()[0])


def wipe_records(user_id: int) -> int:
    """清空这个账号在**所有设备**上的打卡记录。

    ⚠️ 用户点「重置打卡记录」时必须连服务端一起清，否则下一次同步会把
       刚清掉的数字原样搬回来 —— 界面上就成了「清空之后一刷新又回来了」。
    """
    with connect() as c:
        cur = c.execute("DELETE FROM daily WHERE user_id = ?", (user_id,))
        return cur.rowcount


# ---------------------------------------------------------------------------
# 每日目标的时间轴
# ---------------------------------------------------------------------------


def put_goal(user_id: int, day: int, seconds: int) -> None:
    """目标是**配置**不是计数：两台设备改同一天时，后写的赢。

    只有一个人在改它，所以「时间戳后写赢」足够；不需要计数器那套。
    """
    sec = max(0, int(seconds))
    with connect() as c:
        c.execute(
            "INSERT INTO goals (user_id, day, seconds, updated_at) VALUES (?, ?, ?, ?)"
            " ON CONFLICT(user_id, day) DO UPDATE SET"
            "   seconds = excluded.seconds, updated_at = excluded.updated_at",
            (user_id, int(day), sec, now()),
        )


def merged_goals(user_id: int, since: int) -> dict[int, int]:
    with connect() as c:
        rows = c.execute(
            "SELECT day, seconds FROM goals WHERE user_id = ? AND day >= ?",
            (user_id, int(since)),
        ).fetchall()
    return {int(r["day"]): int(r["seconds"]) for r in rows}


# ---------------------------------------------------------------------------
# 生词本（4.1.0）
# ---------------------------------------------------------------------------


def bump_card(user_id: int, lemma: str, surface: str, added_at: int, deleted_at: int = 0) -> None:
    """合并一张卡的两个时间戳。

    ★ `added_at` 与 `deleted_at` **都取 MAX**（都只增）。

    `surface` 的规则必须与客户端 [WordMerge.pickSurface] 一致：
    **加入时间更晚的那一份赢**；时间相同则取字典序小的 —— 这样两端算出来
    一定一样，不会出现「服务端存的是 A 的形态、客户端显示 B 的」。
    """
    with connect() as c:
        c.execute(
            "INSERT INTO word_cards (user_id, lemma, surface, added_at, deleted_at)"
            " VALUES (?, ?, ?, ?, ?)"
            " ON CONFLICT(user_id, lemma) DO UPDATE SET"
            "   surface = CASE"
            "     WHEN excluded.added_at > word_cards.added_at THEN excluded.surface"
            "     WHEN excluded.added_at < word_cards.added_at THEN word_cards.surface"
            "     WHEN excluded.surface < word_cards.surface THEN excluded.surface"
            "     ELSE word_cards.surface END,"
            "   added_at = MAX(word_cards.added_at, excluded.added_at),"
            "   deleted_at = MAX(word_cards.deleted_at, excluded.deleted_at)",
            (user_id, lemma[:120], surface[:120], int(added_at), int(deleted_at)),
        )


def put_word_context(user_id: int, lemma: str, ctx: dict) -> None:
    """语境只增：同一集的同一行再来一次什么都不做（幂等）"""
    with connect() as c:
        c.execute(
            "INSERT OR IGNORE INTO word_contexts"
            " (user_id, lemma, episode_id, line_start_ms, episode_title, line_end_ms,"
            "  sentence, translation) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            (user_id, lemma[:120], str(ctx.get("episodeId", ""))[:120],
             int(ctx.get("lineStartMs", 0)), str(ctx.get("episodeTitle", ""))[:300],
             int(ctx.get("lineEndMs", 0)), str(ctx.get("sentence", ""))[:2000],
             ctx.get("translation")),
        )


def put_word_review(user_id: int, lemma: str, device: str, at_ms: int, rating: int) -> None:
    """复习事件只增。`INSERT OR IGNORE`：重试/交叉到达都不会变成两条"""
    with connect() as c:
        c.execute(
            "INSERT OR IGNORE INTO word_reviews (user_id, lemma, device, at_ms, rating)"
            " VALUES (?, ?, ?, ?, ?)",
            (user_id, lemma[:120], device[:64], int(at_ms), int(rating)),
        )


def word_alive_cards(user_id: int) -> list:
    """活着的卡（`added_at > deleted_at`）"""
    with connect() as c:
        return c.execute(
            "SELECT lemma, surface, added_at, deleted_at FROM word_cards"
            " WHERE user_id = ? AND added_at > deleted_at ORDER BY lemma",
            (user_id,),
        ).fetchall()


def word_contexts(user_id: int) -> dict:
    with connect() as c:
        rows = c.execute(
            "SELECT lemma, episode_id, line_start_ms, episode_title, line_end_ms,"
            " sentence, translation FROM word_contexts WHERE user_id = ?"
            " ORDER BY episode_id, line_start_ms",
            (user_id,),
        ).fetchall()
    out: dict = {}
    for r in rows:
        out.setdefault(r["lemma"], []).append({
            "episodeId": r["episode_id"],
            "episodeTitle": r["episode_title"],
            "lineStartMs": int(r["line_start_ms"]),
            "lineEndMs": int(r["line_end_ms"]),
            "sentence": r["sentence"],
            "translation": r["translation"],
        })
    return out


def word_tombstones(user_id: int) -> dict:
    """删掉的（`deleted_at > added_at`，含「只删过、从来没加过」的那些）"""
    with connect() as c:
        rows = c.execute(
            "SELECT lemma, deleted_at FROM word_cards"
            " WHERE user_id = ? AND deleted_at > added_at",
            (user_id,),
        ).fetchall()
    return {r["lemma"]: int(r["deleted_at"]) for r in rows}


def word_reviews_since(user_id: int, since_seq: int) -> tuple[list, int]:
    """`seq > since_seq` 的复习事件，以及当前最大 seq（客户端下次的水位线）。

    ★ 水位线用**服务端的单调 seq**，不用时间戳：客户端的钟可能慢几秒，
    用它当水位线会**静默漏掉**那些「时间戳比水位线小、但其实还没拿到」的事件。
    """
    with connect() as c:
        rows = c.execute(
            "SELECT seq, lemma, device, at_ms, rating FROM word_reviews"
            " WHERE user_id = ? AND seq > ? ORDER BY seq",
            (user_id, int(since_seq)),
        ).fetchall()
        top = int(c.execute(
            "SELECT COALESCE(MAX(seq), 0) FROM word_reviews WHERE user_id = ?", (user_id,),
        ).fetchone()[0])
    return rows, top


# ---------------------------------------------------------------------------
# 忘记密码（审批制）
# ---------------------------------------------------------------------------


def create_reset(user_id: int) -> None:
    with connect() as c:
        c.execute(
            "INSERT INTO resets (user_id, code_hash, requested_at) VALUES (?, NULL, ?)",
            (user_id, now()),
        )


def pending_resets() -> list[sqlite3.Row]:
    with connect() as c:
        return c.execute(
            "SELECT r.rowid AS rid, r.*, u.name FROM resets r JOIN users u ON u.id = r.user_id"
            " WHERE r.code_hash IS NULL AND r.used_at IS NULL ORDER BY r.requested_at"
        ).fetchall()


def grant_reset(name: str, code_hash: str, ttl_min: int) -> bool:
    """管理员批准：把该用户**未使用**的申请全部作废，并写下一个新码。"""
    with connect() as c:
        row = c.execute("SELECT id FROM users WHERE name = ?", (name,)).fetchone()
        if row is None:
            return False
        uid = int(row["id"])
        c.execute("DELETE FROM resets WHERE user_id = ? AND used_at IS NULL", (uid,))
        c.execute(
            "INSERT INTO resets (user_id, code_hash, requested_at, expires_at)"
            " VALUES (?, ?, ?, ?)",
            (uid, code_hash, now(), now() + ttl_min * 60),
        )
        return True


def consume_reset(user_id: int, code_hash: str) -> str:
    """核销重置码。

    返回 `ok / bad_code / expired / used` —— **四种结果分开**，因为
    它们对用户的下一步完全不同（重输 / 找管理员要新的 / 换密码重新登录）。
    合成一句「重置失败」等于让人重试一遍所有可能。
    """
    with connect() as c:
        rows = c.execute(
            "SELECT rowid AS rid, * FROM resets WHERE user_id = ? AND code_hash IS NOT NULL"
            " ORDER BY requested_at DESC LIMIT 5",
            (user_id,),
        ).fetchall()
        if not rows:
            return "bad_code"
        for r in rows:
            if r["code_hash"] != code_hash:
                continue
            if r["used_at"] is not None:
                return "used"
            if r["expires_at"] is None or int(r["expires_at"]) < now():
                return "expired"
            c.execute("UPDATE resets SET used_at = ? WHERE rowid = ?", (now(), r["rid"]))
            return "ok"
    return "bad_code"


def set_password(user_id: int, pw_hash: str, pw_salt: str) -> None:
    with connect() as c:
        c.execute(
            "UPDATE users SET pw_hash = ?, pw_salt = ? WHERE id = ?",
            (pw_hash, pw_salt, user_id),
        )
