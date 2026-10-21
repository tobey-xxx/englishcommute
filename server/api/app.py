#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
EnglishCommute · 账号与打卡同步接口（4.0.0）

## 这份服务在整个系统里的位置

```
App ──https──> nginx(443, YOUR_DOMAIN) ──proxy──> uvicorn 127.0.0.1:8081 ──> SQLite
                    │
                    └── 静态内容（音频/字幕/清单/更新包）仍然是 nginx 直接送
```

**它是这个项目的第一个应用服务器。** 在此之前「服务端」只有静态文件与
每 30 分钟一跑的 `cycle.py` —— 没有数据库、没有鉴权、没有任何 POST 接口。

## 四件必须写进骨头里的事

**① 管理面不在这份接口里。**
审批 / 发重置码 / 踢令牌全部在 `bin/ec-admin`（服务器上直接读写库），
**没有对应的 HTTP 端点**。少一个端点就少一处要鉴权、要限速、要防越权的地方 ——
而这里的「管理员」只有一个人，他本来就有 SSH。

**② 同步只搬数字。**
不传音频、字幕、词表、海报。客户端上传的是「我这台设备今天听了多少秒」，
服务端返回的是「所有设备合计多少秒」。

**③ 冲突模型是「求和」，不是「后写赢」。**
见 `db.bump_daily` 的注释：每个格子只有一台设备会写、且只增不减 ⇒
重复请求 / 乱序 / 并发全都安全，永远不需要「保留哪一份」的交互。

**④ 凭证不进日志。**
uvicorn 的 access log 只记方法、路径、状态码。凭证在请求体与请求头里。
⚠️ 不要在任何地方打印 body 或 headers。
"""
import os
import re
import sqlite3
import sys
from contextlib import asynccontextmanager

from fastapi import Depends, FastAPI, Header, Request
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import db                                                       # noqa: E402
import security                                                 # noqa: E402

API_VERSION = "4.1.0"
NAME_RE = re.compile(r"^[0-9A-Za-z_\u4e00-\u9fa5-]{2,24}$")


@asynccontextmanager
async def lifespan(_app: FastAPI):
    # 建表放在启动钩子里而不是 import 时：import 是「读代码」的动作，
    # 不该顺带改磁盘（本地跑测试、或只是看一眼接口文档都会触发）。
    db.init()
    yield


app = FastAPI(title="EnglishCommute API", version=API_VERSION, docs_url=None,
              redoc_url=None, lifespan=lifespan)

# 三处入口各自的限速器（都是内存的，见 security.RateLimiter 的说明）
login_limiter = security.RateLimiter()
reset_limiter = security.RateLimiter()
register_limiter = security.RateLimiter(max_fails=20)


def err(status: int, code: str, message: str) -> JSONResponse:
    return JSONResponse(status_code=status, content={"error": code, "message": message})


def client_ip(request: Request) -> str:
    """nginx 反代过来的真实来源。

    ⚠️ 只信 `X-Real-IP`（由自己的 nginx 覆写），**不要**去解析
       `X-Forwarded-For` 的第一个值 —— 那是客户端可以随便伪造的。
       拿错了 IP 的后果是限速可以绕过，而且看起来一切正常。
    """
    return (request.headers.get("x-real-ip") or request.client.host or "?").strip()


# ---------------------------------------------------------------------------
# 请求体
# ---------------------------------------------------------------------------


class RegisterBody(BaseModel):
    name: str
    password: str


class LoginBody(BaseModel):
    name: str
    password: str
    device: str = Field(default="", max_length=64)


class ForgotBody(BaseModel):
    name: str


class ResetBody(BaseModel):
    name: str
    code: str
    password: str


class PasswordBody(BaseModel):
    oldPassword: str
    newPassword: str


class WordContextIn(BaseModel):
    episodeId: str = ""
    episodeTitle: str = ""
    lineStartMs: int = 0
    lineEndMs: int = 0
    sentence: str = ""
    translation: str | None = None


class WordReviewIn(BaseModel):
    lemma: str
    at: int
    rating: int
    device: str = ""


class WordCardIn(BaseModel):
    lemma: str
    surface: str = ""
    addedAt: int = 0
    deletedAt: int = 0
    contexts: list[WordContextIn] = []
    reviews: list[WordReviewIn] = []


class WordsBody(BaseModel):
    device: str = Field(max_length=64)
    cards: list[WordCardIn] = []
    """只删过、没有卡片的那些词（`lemma → 删除时刻`）。有卡片的删除走 `deletedAt`"""
    deleted: dict[str, int] = {}
    """本机产生的复习（服务端按 (设备, 时刻) 去重，重发多少次都一样）"""
    reviews: list[WordReviewIn] = []
    """客户端已经拿到的最大 seq（0 = 第一次同步，会把全部复习事件要回去）"""
    sinceSeq: int = 0


class SyncBody(BaseModel):
    device: str = Field(max_length=64)
    days: dict[int, int] = {}
    goals: dict[int, int] = {}
    since: int = 0


# ---------------------------------------------------------------------------
# 鉴权依赖
# ---------------------------------------------------------------------------


def current_user(authorization: str | None = Header(default=None)) -> sqlite3.Row:
    """从 `Authorization: Bearer <token>` 解出用户。

    401 的语义只有一种：**这个令牌不再有效**（过期 / 被撤销 / 从来没发过）。
    客户端收到 401 该做的是清掉本地令牌并让用户重新登录 —— 所以这里
    **不能**混进「账号被停用」之类的业务原因（那会让客户端把能修的问题也当成要重登）。
    """
    if not authorization or not authorization.lower().startswith("bearer "):
        raise Unauthorized()
    raw = authorization.split(" ", 1)[1].strip()
    row = db.find_token(security.token_hash(raw))
    if row is None:
        raise Unauthorized()
    user = db.get_user_by_id(int(row["user_id"]))
    if user is None or user["status"] != "active":
        raise Unauthorized()
    # 滑动续期：只要还在用就不踢（用户明确要求「不登出」）
    if db.now() - int(row["last_seen_at"]) > security.TOKEN_EXTEND_HOURS * 3600:
        db.touch_token(int(row["id"]), db.now(), security.TOKEN_EXTEND_HOURS,
                       security.TOKEN_DAYS)
    return user


class Unauthorized(Exception):
    pass


@app.exception_handler(Unauthorized)
async def _unauthorized(_request: Request, _exc: Unauthorized) -> JSONResponse:
    return err(401, "unauthorized", "登录状态已失效，请重新登录")


# ---------------------------------------------------------------------------
# 健康检查
# ---------------------------------------------------------------------------


@app.get("/api/healthz")
def healthz() -> dict:
    """部署后第一件事就打的端点。**不返回任何用户数据。**"""
    try:
        with db.connect() as c:
            c.execute("SELECT 1").fetchone()
        db_ok = True
    except Exception:
        db_ok = False
    return {"ok": db_ok, "db": db_ok, "version": API_VERSION}


# ---------------------------------------------------------------------------
# 账号
# ---------------------------------------------------------------------------


@app.post("/api/auth/register")
def register(body: RegisterBody, request: Request) -> JSONResponse:
    ip = client_ip(request)
    if register_limiter.blocked(ip):
        return err(429, "too_many", "请求太频繁，请稍后再试")

    name = body.name.strip()
    if not NAME_RE.match(name):
        return err(400, "bad_name", "账号请用 2~24 位字母、数字、下划线、短横线或中文")
    problem = security.password_error(body.password, name)
    if problem:
        return err(400, "weak_password", problem)

    if db.get_user(name) is not None:
        register_limiter.fail(ip)
        return err(409, "name_taken", "这个账号已经被注册了")

    pw_hash, salt = security.hash_password(body.password)
    db.create_user(name, pw_hash, salt, status="pending")
    # ⚠️ 不返回任何关于「接下来会怎样」的文案 —— 那句话由客户端说，
    #    因为它要随界面语境变（注册页 / 登录页各一句）。
    return JSONResponse(status_code=202, content={"status": "pending", "name": name})


@app.post("/api/auth/login")
def login(body: LoginBody, request: Request) -> JSONResponse:
    ip = client_ip(request)
    keys = ["ip:" + ip, "name:" + body.name.strip().lower()]
    for k in keys:
        wait = login_limiter.blocked(k)
        if wait:
            return err(429, "too_many", f"尝试太频繁，请 {wait} 秒后再试")

    user = db.get_user(body.name.strip())
    # ⚠️ 账号不存在与密码错误返回**同一个** 401：否则这个接口就是一个
    #    「哪些账号存在」的查询器。
    if user is None or not security.verify_password(
        body.password, user["pw_hash"], user["pw_salt"]
    ):
        for k in keys:
            login_limiter.fail(k)
        return err(401, "bad_credentials", "账号或密码不对")

    status = user["status"]
    if status != "active":
        # 这一条**要**说清原因：它不是攻击面（对方已经知道这个账号存在，
        # 因为他自己注册的），而「等审批」和「被拒绝」的下一步完全不同。
        for k in keys:
            login_limiter.clear(k)
        if status == "pending":
            return err(403, "pending", "这个账号还在等待通过，通过后就能登录")
        return err(403, "rejected", "这个账号没有通过")

    for k in keys:
        login_limiter.clear(k)
    token = security.new_token()
    db.issue_token(int(user["id"]), security.token_hash(token),
                   body.device, security.TOKEN_DAYS)
    return {"token": token, "name": user["name"],
            "expiresInDays": security.TOKEN_DAYS}


@app.get("/api/me")
def me(user: sqlite3.Row = Depends(current_user)) -> dict:
    return {
        "name": user["name"],
        "createdAt": int(user["created_at"]),
        "devices": db.live_token_count(int(user["id"])),
    }


@app.post("/api/auth/password")
def change_password(body: PasswordBody, request: Request,
                    user: sqlite3.Row = Depends(current_user)) -> JSONResponse:
    ip = client_ip(request)
    key = "pw:" + user["name"]
    if login_limiter.blocked(key):
        return err(429, "too_many", "尝试太频繁，请稍后再试")
    if not security.verify_password(body.oldPassword, user["pw_hash"], user["pw_salt"]):
        login_limiter.fail(key)
        return err(401, "bad_credentials", "当前密码不对")
    problem = security.password_error(body.newPassword, user["name"])
    if problem:
        return err(400, "weak_password", problem)
    pw_hash, salt = security.hash_password(body.newPassword)
    db.set_password(int(user["id"]), pw_hash, salt)
    login_limiter.clear(key)
    # ★ 刻意**不撤销其它设备的令牌**：用户只是改了密码，不该被从平板/备用机上踢下来。
    #   （「踢掉所有设备」只发生在**忘记密码走重置**那条路上 —— 那是「我怀疑号被用了」
    #    的场景，两件事的意图完全不同。）
    return {"ok": True, "revoked": 0}


@app.post("/api/auth/logout")
def logout(authorization: str | None = Header(default=None)) -> dict:
    """撤销**当前**这个令牌。其它设备不受影响。

    2.0 起 App 支持多设备，所以「登出」的语义必须收窄成「这台设备退出」——
    否则一台设备退出会把所有设备一起踢掉，而用户只是想在借来的手机上退出。
    """
    if authorization and authorization.lower().startswith("bearer "):
        db.revoke_token(security.token_hash(authorization.split(" ", 1)[1].strip()))
    return {"ok": True}


# ---------------------------------------------------------------------------
# 忘记密码（审批制：本人提交，管理员发码）
# ---------------------------------------------------------------------------


@app.post("/api/auth/forgot")
def forgot(body: ForgotBody, request: Request) -> JSONResponse:
    ip = client_ip(request)
    if reset_limiter.blocked("ip:" + ip):
        return err(429, "too_many", "请求太频繁，请稍后再试")
    user = db.get_user(body.name.strip())
    if user is None:
        # ⚠️ 这里**刻意**区分「没有这个账号」：账号名是用户自己起的，
        #    不是秘密（注册接口本来就是开放的），而说不清会让他在那儿
        #    干等一个永远不会来的码。
        return err(404, "no_such_user", "这个账号还没注册过")
    db.create_reset(int(user["id"]))
    reset_limiter.fail("ip:" + ip)
    return JSONResponse(status_code=202, content={"status": "submitted"})


@app.post("/api/auth/reset")
def reset(body: ResetBody, request: Request) -> JSONResponse:
    ip = client_ip(request)
    if reset_limiter.blocked("ip:code:" + ip):
        return err(429, "too_many", "尝试太频繁，请稍后再试")
    user = db.get_user(body.name.strip())
    if user is None:
        return err(404, "no_such_user", "这个账号还没注册过")
    problem = security.password_error(body.password, body.name.strip())
    if problem:
        return err(400, "weak_password", problem)

    verdict = db.consume_reset(int(user["id"]), security.code_hash(body.code.strip()))
    if verdict != "ok":
        reset_limiter.fail("ip:code:" + ip)
        return err(400, verdict,
                   {"bad_code": "重置码不对",
                    "expired": "重置码过期了（有效期 30 分钟），请让管理员重新生成",
                    "used": "这个重置码已经用过了，请让管理员重新生成"}[verdict])

    pw_hash, salt = security.hash_password(body.password)
    db.set_password(int(user["id"]), pw_hash, salt)
    # ★ 重置的**全部意义**就是「我怀疑号被人用了」⇒ 必须把旧设备全部踢掉。
    #   这是唯一一处会踢设备的地方（改密码不踢）。
    revoked = db.revoke_all(int(user["id"]))
    return {"ok": True, "revoked": revoked}


# ---------------------------------------------------------------------------
# 打卡同步
# ---------------------------------------------------------------------------


@app.post("/api/sync/checkin")
def sync_checkin(body: SyncBody, user: sqlite3.Row = Depends(current_user)) -> dict:
    """一次往返完成「上传我这台设备的增量 + 取回全设备合计」。

    ## 为什么不做成 pull / push 两个接口

    两个接口就有「先 pull 还是先 push」的顺序问题，而任何一次顺序错了
    （或中间断网）都会留下一个**本地与服务端不一致、且没人知道**的状态。
    合成一个：先合并对方写的，再回全量 —— 幂等、可重试、没有中间态。

    ## 为什么只回 `since` 之后的天数

    历史可以无限长。客户端只关心它自己知道的那一段（它会把 since 传成
    「本地最早那天」），所以响应大小有界。
    """
    uid = int(user["id"])
    since = max(0, min(int(body.since), 10 ** 7))
    now_day = db.now() // 86400

    # ① 收下这台设备的数据（每列只有它自己会写，见 db.bump_daily）
    for day, seconds in body.days.items():
        d = int(day)
        # 只接受合理范围的天：防有人把手机时间调到 1970 或 2200 之后往库里灌垃圾
        if abs(d - now_day) <= db.MAX_DAYS:
            db.bump_daily(uid, body.device, d, int(seconds))
    for day, seconds in body.goals.items():
        d = int(day)
        if abs(d - now_day) <= db.MAX_DAYS:
            db.put_goal(uid, d, int(seconds))

    # ② 回全设备合计
    return {
        "days": {str(k): v for k, v in db.merged_days(uid, since).items()},
        "goals": {str(k): v for k, v in db.merged_goals(uid, since).items()},
        "devices": db.device_count(uid),
        "serverDay": now_day,
        "serverTime": db.now(),
    }


@app.post("/api/sync/words")
def sync_words(body: WordsBody, user: sqlite3.Row = Depends(current_user)) -> dict:
    """生词本的同步（4.1.0）。一次往返：**先收下本机的，再回全量合并结果**。

    ## 服务端只做两件事：**收**（幂等）和**给**（合并后的全量）

    合并规则全在「三张表 + 几句 SQL」里，没有一处需要服务端做判断：

    | 数据 | 服务端的动作 | 为什么 |
    | --- | --- | --- |
    | 卡片的时间戳 | `MAX(旧值, 新值)` | 两个都只增 ⇒ 幂等、可交换 |
    | 语境 | `INSERT OR IGNORE` | 身份 = 哪一集的哪一行，天然只增 |
    | 复习事件 | `INSERT OR IGNORE` | 身份 = (设备, 时刻) |
    | **FSRS 状态** | **不存** | 它是复习事件的投影，由客户端重放算出 |

    ★ 最后一行是这一套能成立的关键：状态不落库，就不存在
    「两台设备各有一份状态、谁也不认谁」的问题。客户端拿到的是
    **事件的并集**，重放出来的结果两端必然一致。
    """
    uid = int(user["id"])
    dev = body.device or ""

    for c in body.cards:
        lemma = c.lemma.strip()[:120]
        if not lemma:
            continue
        db.bump_card(uid, lemma, c.surface, int(c.addedAt), int(c.deletedAt))
        for ctx in c.contexts:
            db.put_word_context(uid, lemma, ctx.model_dump())
        for r in c.reviews:
            db.put_word_review(uid, lemma, r.device or dev, r.at, r.rating)

    for lemma, at in body.deleted.items():
        key = lemma.strip()[:120]
        if key and int(at) > 0:
            # 「只删过、从来没加过」也留一行：added_at=0 ⇒ 判据 0 > deleted_at 为假 ⇒ 删着
            db.bump_card(uid, key, "", 0, int(at))

    for r in body.reviews:
        key = r.lemma.strip()[:120]
        if key:
            db.put_word_review(uid, key, r.device or dev, r.at, r.rating)

    ctxs = db.word_contexts(uid)
    rows, top = db.word_reviews_since(uid, max(0, int(body.sinceSeq)))

    return {
        "cards": [
            {
                "lemma": c["lemma"],
                "surface": c["surface"],
                "addedAt": int(c["added_at"]),
                "deletedAt": int(c["deleted_at"]),
                "contexts": ctxs.get(c["lemma"], []),
            }
            for c in db.word_alive_cards(uid)
        ],
        "deleted": db.word_tombstones(uid),
        "reviews": [
            {
                "lemma": r["lemma"],
                "at": int(r["at_ms"]),
                "rating": int(r["rating"]),
                "device": r["device"],
            }
            for r in rows
        ],
        "seq": top,
    }


@app.post("/api/sync/wipe")
def sync_wipe(user: sqlite3.Row = Depends(current_user)) -> dict:
    """清空这个账号在**所有设备**上的打卡记录。

    ⚠️ 少了它，「重置打卡记录」会变成一个骗人的按钮：本地清完，
    下一次同步把服务端那几行原样搬回来 —— 用户看到的是
    「清空之后过一会儿又自己回来了」。
    """
    n = db.wipe_records(int(user["id"]))
    return {"ok": True, "removed": n}


if __name__ == "__main__":                       # 本机跑（测试脚本用这个入口）
    import uvicorn
    uvicorn.run(app, host="127.0.0.1",
                port=int(os.environ.get("EC_PORT", "8081")), log_level="warning")
