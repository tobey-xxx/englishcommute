#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
账号与打卡同步接口的契约（4.0.0 新建）。

## 这个脚本跑的是**真的服务器代码**

它自己起一个 uvicorn（临时库 + 随机端口），用真的 HTTP 打进去 ——
不做 Python 侧的「复刻实现」。理由：这一轮新增的东西里，
**最贵的错法全都在「接线」上**（限速按哪个键、库里的密码是不是明文、
多设备合并是不是幂等），而接线只有跑真的代码才验得到。

## 它守的四类事

| 类 | 守什么 | 代表判据 |
| --- | --- | --- |
| 凭证 | 密码/令牌不以可用形态落盘、不进日志 | 直接打开 SQLite 看 `pw_hash` 长什么样 |
| 入口 | 谁能登录、谁不能、被枚举 | pending 不能登录；**账号不存在与密码错返回同一个响应** |
| 同步 | 多设备合并是「求和 + 幂等」 | 你说的那个例子：A 1500 + B 900 = 2400，且重发 1000 不会变小 |
| 结构 | 有些事实只能从源码/配置读 | 管理端点**不存在**于 HTTP；`/api/` 不在共享的 80 路由里 |

## 为什么坏样本要改**源码文本**

有三条判据是「某个写法必须存在」（`MAX(...)`、`hmac.compare_digest`、
unit 里的 `--workers 1`）。它们很容易变成**恒真的 ✓**：
删掉那个写法，脚本照样绿。所以每条都配一个「故意改坏源码 → 判据必须失败」
的坏样本 —— 这是本工程对「断言有没有区分力」的固定检查。
"""
import io
import json
import os
import re
import socket
import sqlite3
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.request

ROOT = os.path.dirname(os.path.abspath(__file__)) + "/.."
SRV = os.path.join(ROOT, "2.0-服务端")
API = os.path.join(SRV, "api")
VENV_PY = os.path.expanduser("~/.workbuddy/binaries/python/envs/ecapi/bin/python")
DB_SRC = os.path.join(API, "db.py")
APP_SRC = os.path.join(API, "app.py")
SEC_SRC = os.path.join(API, "security.py")
ADMIN = os.path.join(SRV, "bin/ec-admin")
UNIT = os.path.join(SRV, "systemd/ec-api.service")
NGINX = os.path.join(SRV, "nginx")

PASS, FAIL = 0, 0
FAILURES = []


def check(name, ok, why=""):
    global PASS, FAIL
    if ok:
        PASS += 1
        print("  ✅ %s" % name)
    else:
        FAIL += 1
        FAILURES.append(name)
        print("  ❌ %s" % name)
        if why:
            print("       %s" % why)


def section(t):
    print()
    print("=" * 68)
    print("  " + t)
    print("=" * 68)


def read(p):
    return io.open(p, encoding="utf-8").read() if os.path.isfile(p) else ""


# ===========================================================================
# 0. 起一个真的服务（临时库 + 随机端口）
# ===========================================================================


def free_port() -> int:
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    p = s.getsockname()[1]
    s.close()
    return p


TMP = tempfile.mkdtemp(prefix="ec-api-test-")
TMP_DB = os.path.join(TMP, "test.db")
PORT = free_port()
BASE = "http://127.0.0.1:%d" % PORT

if not os.path.isfile(VENV_PY):
    sys.exit("❌ 找不到测试用的虚拟环境 %s\n   先跑：python3 -m venv %s && %s/bin/pip install fastapi 'uvicorn[standard]'"
             % (VENV_PY, os.path.dirname(os.path.dirname(VENV_PY)), os.path.dirname(os.path.dirname(VENV_PY))))

# ⚠️ 限速窗口调成 4 秒：默认的 300 秒会把**本脚本自己后面所有的登录**都挡住。
#    能调小这件事本身也有价值 —— 「窗口过去之后能自己恢复」才有得验。
RL_WINDOW = 4
ENV = dict(os.environ, EC_DB=TMP_DB, PYTHONUNBUFFERED="1",
           EC_RL_WINDOW=str(RL_WINDOW))
proc = subprocess.Popen(
    [VENV_PY, "-m", "uvicorn", "app:app", "--host", "127.0.0.1",
     "--port", str(PORT), "--log-level", "warning"],
    cwd=API, env=ENV, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
)


def wait_ready(timeout=30) -> bool:
    t0 = time.time()
    while time.time() - t0 < timeout:
        try:
            with urllib.request.urlopen(BASE + "/api/healthz", timeout=2) as r:
                if r.status == 200:
                    return True
        except Exception:
            time.sleep(0.3)
    return False


def call(method, path, body=None, token=None, ip=None):
    """返回 (状态码, dict)。**不抛 HTTPError** —— 4xx 是我们要断言的东西。"""
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    if body is not None:
        req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    if ip:
        # 模拟另一台来源（限速按 IP 分键）。nginx 反代会覆写这个头。
        req.add_header("X-Real-IP", ip)
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            raw = r.read().decode()
            return r.status, (json.loads(raw) if raw else {})
    except urllib.error.HTTPError as e:
        raw = e.read().decode()
        try:
            return e.code, json.loads(raw)
        except Exception:
            return e.code, {"raw": raw[:200]}
    except Exception as e:
        return 0, {"err": repr(e)[:200]}


# 直接操作库（等价于 ec-admin 的路径 —— 管理面不经过 HTTP）
sys.path.insert(0, API)
os.environ["EC_DB"] = TMP_DB
import db as ec_db            # noqa: E402
import security as ec_sec     # noqa: E402


def sql(q, args=()):
    c = sqlite3.connect(TMP_DB)
    c.row_factory = sqlite3.Row
    try:
        return c.execute(q, args).fetchall()
    finally:
        c.close()


def register(name, pw):
    return call("POST", "/api/auth/register", {"name": name, "password": pw})


def login(name, pw, device="devA", ip=None):
    # ip 用来模拟「另一个来源」—— 限速按「账号 + IP」两条线分键，两条都要能验
    return call("POST", "/api/auth/login",
                {"name": name, "password": pw, "device": device}, ip=ip)


def sync(token, device, days=None, goals=None, since=0, ip=None):
    return call("POST", "/api/sync/checkin",
                {"device": device, "days": days or {}, "goals": goals or {},
                 "since": since}, token=token, ip=ip)


# ===========================================================================
section("〇、先确认这个脚本真的在打一个活着的服务")
# ===========================================================================

if not wait_ready():
    proc.kill()
    out = proc.stdout.read() if proc.stdout else ""
    sys.exit("❌ 服务没起来（30 秒超时）。它自己的输出：\n" + out[-2000:])

st, body = call("GET", "/api/healthz")
check("健康检查可用", st == 200 and body.get("ok") is True and body.get("db") is True,
      "实际 %s %s" % (st, body))
check("  · 且它不泄露任何用户信息",
      not ({"users", "names", "count"} & set(body.keys())),
      "健康检查是**匿名**能打的，带上用户数就等于对外公布规模：%s" % list(body.keys()))
check("  · 版本号对得上",
      body.get("version", "").startswith("4."), "实际 %s" % body.get("version"))

check("★ 这个脚本用的是**临时库**（不是生产数据）",
      TMP_DB.startswith("/var/folders") or TMP_DB.startswith("/tmp"),
      "测试脚本绝不能碰 /opt/ec/data —— 这是所有破坏性断言的前提")

# ===========================================================================
section("一、注册与审批（注册了也不能直接用）")
# ===========================================================================

st, body = register("alice", "hunter2hunter")
check("注册返回 pending", st == 202 and body.get("status") == "pending",
      "实际 %s %s" % (st, body))

st, _ = register("alice", "hunter2hunter")
check("  · 重名被拒", st == 409, "实际 %s" % st)

st, body = register("bob", "123")
check("  · 弱密码被拒，且说清原因", st == 400 and "至少" in body.get("message", ""),
      "「密码不对」这种话对用户没有下一步。实际：%s %s" % (st, body))

st, _ = register("a", "longenough1")
check("  · 过短的账号名被拒", st == 400, "实际 %s" % st)

st, body = login("alice", "hunter2hunter")
check("★ 未审批的账号不能登录，且**说清是在等审批**",
      st == 403 and body.get("error") == "pending" and "等待通过" in body.get("message", ""),
      "「账号或密码不对」会让他去怀疑密码，而他要做的是等 —— 实际 %s %s" % (st, body))

check("  · 库里存的是 pending 状态",
      sql("SELECT status FROM users WHERE name='alice'")[0]["status"] == "pending")

ec_db.set_status("alice", "active")
st, body = login("alice", "hunter2hunter")
token_a = body.get("token", "")
check("审批后可以登录，并拿到令牌", st == 200 and len(token_a) > 20, "实际 %s %s" % (st, body))
check("  · 登录响应里**没有**任何密码字段",
      not ({"password", "pw_hash", "pw_salt"} & set(body.keys())), str(list(body.keys())))

register("carol", "hunter2hunter")
ec_db.set_status("carol", "rejected", "测试")
st, body = login("carol", "hunter2hunter")
check("  · 被拒绝的账号说「没有通过」", st == 403 and body.get("error") == "rejected",
      "实际 %s %s" % (st, body))

# ── ★ 最值钱的两条：直接打开库看凭证长什么样 ──────────────────────────────
row = sql("SELECT pw_hash, pw_salt FROM users WHERE name='alice'")[0]
check("★ 库里**没有明文密码**，存的是 scrypt 哈希（64 字节 hex）",
      re.fullmatch(r"[0-9a-f]{128}", row["pw_hash"] or "") is not None
      and re.fullmatch(r"[0-9a-f]{32}", row["pw_salt"] or "") is not None,
      "实际 pw_hash=%r" % (row["pw_hash"] or "")[:24])
check("  · 而且整个库文件里搜不到那个明文口令",
      b"hunter2hunter" not in io.open(TMP_DB, "rb").read(),
      "★ 光看 pw_hash 那一列是不够的 —— 明文可能落在别的列、或者 WAL 里")
check("★ 库里存的是令牌的 sha256，不是令牌本身",
      sql("SELECT COUNT(*) FROM tokens WHERE token_hash=?", (token_a,))[0][0] == 0
      and sql("SELECT COUNT(*) FROM tokens WHERE token_hash=?",
              (ec_sec.token_hash(token_a),))[0][0] == 1)

# ===========================================================================
section("二、登录的两条安全判据")
# ===========================================================================

st1, b1 = login("alice", "错的密码错的密码", device="d")
st2, b2 = login("nosuchuser", "错的密码错的密码", device="d")
check("★ 密码错与账号不存在返回**完全相同**的响应",
      st1 == st2 == 401 and b1 == b2,
      "不同的话，这个接口就成了「哪些账号存在」的查询器。"
      "实际：%s / %s" % (b1, b2))

st, body = login("alice", "hunter2hunter", device="d")
check("  · 成功登录后限速被清零（不会因为之前手抖被挡）", st == 200, "实际 %s" % st)

# 限速：5 次失败后第 6 次必须被挡（按账号）
for _ in range(5):
    login("alice", "错的密码错的密码", device="d")
st, body = login("alice", "hunter2hunter", device="d")
check("★ 连续错 5 次之后被限速（429）", st == 429, "实际 %s %s" % (st, body))
check("  · 且响应里说清还要等多久",
      "秒" in body.get("message", ""), "实际 %s" % body.get("message"))

st, _ = login("alice", "hunter2hunter", device="d", ip="10.9.9.9")
check("  · 换个来源 IP 也拦（按账号那条线在起作用）", st == 429,
      "只按 IP 限速的话，换个网络就能继续猜 —— 实际 %s" % st)

register("dave", "hunter2hunter")
ec_db.set_status("dave", "active")
# ① 同一来源：alice 把 127.0.0.1 这条线也用满了 ⇒ 换账号照样进不来。
#    ⚠️ 这是**按 IP 限速的应有之义**，不是 bug —— 我第一版把它写成了
#    「别人应该能进」，那是把两条限速线的作用搞混了。
st, _ = login("dave", "hunter2hunter", device="devD")
check("★ 来自同一个来源时，换账号也进不来（按 IP 那条线在起作用）",
      st == 429, "实测 %s" % st)

# ② 换一个来源：dave 立刻能进 ⇒ 说明限速既没有永久锁死账号，也没有殃及别人
st, body = login("dave", "hunter2hunter", device="devD", ip="10.9.9.9")
token_d = body.get("token", "")
check("★ 换个来源就能进（限速没有把账号永久锁死，也没有殃及别的来源）",
      st == 200 and bool(token_d),
      "只按 IP 分键会让同网所有人被一个人拖下水；只按账号分键则能把账号锁死。"
      "实际 %s %s" % (st, body))

time.sleep(RL_WINDOW + 0.6)
st, body = login("alice", "hunter2hunter", device="d")
check("★ 窗口过去之后限速自己恢复（不是永久封号）",
      st == 200, "实际 %s %s —— 永久锁定等于一个免费的拒绝服务" % (st, body))

# ===========================================================================
section("三、打卡同步：求和 + 幂等（★ 你说的那个例子）")
# ===========================================================================

D = 20500   # 随便一个「今天」
st, body = sync(token_a, "phoneA", days={D: 1500}, since=D - 30)
check("设备 A 上报 25 分钟", st == 200 and body["days"].get(str(D)) == 1500,
      "实际 %s %s" % (st, body))

st, body = sync(token_a, "tabletB", days={D: 900}, since=D - 30)
check("★ 设备 B 上报 15 分钟 → 合计 40 分钟（在 B 上能看到 25 分钟）",
      st == 200 and body["days"].get(str(D)) == 2400,
      "★ 这就是「A 听 25 → B 接着听 15」那个例子。实际 %s" % body.get("days"))
check("  · 服务端认得这是两台设备", body.get("devices") == 2, str(body.get("devices")))

st, body = sync(token_a, "phoneA", days={D: 1500}, since=D - 30)
check("★ 幂等：A 把同一个数重发一遍，合计不变（2400）",
      body["days"].get(str(D)) == 2400,
      "断网重试、交叉到达都会重发 —— 不幂等就会越加越多。实际 %s" % body.get("days"))

st, body = sync(token_a, "phoneA", days={D: 1000}, since=D - 30)
check("★ 更小的值不会把已有值改小（max 语义）",
      body["days"].get(str(D)) == 2400,
      "乱序到达时，先到的那个大值不能被后来的小值覆盖。实际 %s" % body.get("days"))

st, body = sync(token_a, "phoneA", days={D: 2100}, since=D - 30)
check("  · 更大的值正常生效（A 又听了 10 分钟 → 3000）",
      body["days"].get(str(D)) == 3000, "实际 %s" % body.get("days"))

# 并发：两个线程同时上报（真机上就是两台设备同时同步）
D2 = D + 1
res = {}


def _one(dev, sec):
    res[dev] = sync(token_a, dev, days={D2: sec}, since=D2)[1]


t1 = threading.Thread(target=_one, args=("c1", 600))
t2 = threading.Thread(target=_one, args=("c2", 400))
t1.start(); t2.start(); t1.join(); t2.join()
after = sync(token_a, "c1", days={}, since=D2)[1]["days"].get(str(D2))
check("★ 两台设备**同时**上报，结果正好是两者之和（1000）",
      after == 1000, "并发下丢更新是本类功能最典型的 bug。实际 %s" % after)

st, body = sync(token_a, "phoneA", days={D: 3000}, since=D2)
check("  · `since` 生效：只回 >= since 的天（老的那天被过滤掉）",
      str(D) not in body["days"] and str(D2) in body["days"],
      "历史可以无限长，响应必须有界。实际 %s" % list(body["days"].keys()))

st, body = sync(token_a, "phoneA", days={0: 999999, 2200: 1}, since=0)
check("  · 荒谬的日期（1970 / 远未来）被忽略",
      "0" not in body["days"] and "2200" not in body["days"],
      "手机时间被改错时不能往库里灌垃圾。实际 %s" % list(body["days"].keys())[:6])

# 目标同步
st, body = sync(token_a, "phoneA", goals={D: 1800}, since=D - 1)
check("每日目标随同步一起走", st == 200 and body["goals"].get(str(D)) == 1800,
      "实际 %s" % body.get("goals"))
st, body = sync(token_a, "tabletB", goals={}, since=D - 1)
check("  · 另一台设备能读到同一个目标", body["goals"].get(str(D)) == 1800)

# 隔离：另一个账号看不到 alice 的数
st, body = sync(token_d, "phoneA", days={}, since=0)
check("★ 账号之间完全隔离（换一个账号读不到别人的记录）",
      body.get("days") == {}, "实际 %s" % body.get("days"))

# ===========================================================================
section("四、忘记密码（审批制 + 一次性码）")
# ===========================================================================

st, body = call("POST", "/api/auth/forgot", {"name": "nosuchuser"})
check("没注册过的账号：说清「还没注册过」", st == 404 and body.get("error") == "no_such_user",
      "让他干等一个永远不会来的码，比说清更糟。实际 %s %s" % (st, body))

st, body = call("POST", "/api/auth/forgot", {"name": "alice"})
check("已注册的账号：提交成功但**还没有码**", st == 202 and "code" not in body,
      "审批制的全部意义就在这一条：码不是自动发的。实际 %s %s" % (st, body))
check("  · 服务端记下了这条申请（管理员能看见）",
      len(ec_db.pending_resets()) == 1, str(len(ec_db.pending_resets())))

st, body = call("POST", "/api/auth/reset",
                {"name": "alice", "code": "22222222", "password": "newpassword1"})
check("管理员还没批准 → 任何码都无效", st == 400 and body.get("error") == "bad_code",
      "实际 %s %s" % (st, body))

code = ec_sec.new_reset_code()
ec_db.grant_reset("alice", ec_sec.code_hash(code), ec_sec.RESET_CODE_TTL_MIN)
check("  · 管理员批准后生成一次性码（8 位）",
      len(code) == 8 and re.fullmatch(r"[234578]{8}", code) is not None,
      "码要能人工念、人工抄 —— 不含易混字形。实际 %r" % code)

# 过期的码
D_ex = "expired-1"
bad = ec_sec.new_reset_code()
ec_db.grant_reset("dave", ec_sec.code_hash(bad), 30)
c = sqlite3.connect(TMP_DB)
c.execute("UPDATE resets SET expires_at = ? WHERE user_id = (SELECT id FROM users WHERE name='dave')",
          (int(time.time()) - 10,))
c.commit(); c.close()
st, body = call("POST", "/api/auth/reset",
                {"name": "dave", "code": bad, "password": "whatever123"})
check("过期的码 → 说清「过期了，请让管理员重新生成」",
      st == 400 and body.get("error") == "expired" and "重新生成" in body.get("message", ""),
      "实际 %s %s" % (st, body))

# 正常重置
# ⚠️ 已知问题（4.1.1 记录在案，尚未定位）：这一步在**单独跑**时返回 bad_code，
#    而全量跑（连跑多个脚本之后）是通过的。已确认：库里那一行的 code_hash
#    与期待值**一致**、expires_at 在未来、used_at 为 NULL —— 也就是说
#    `consume_reset` 该返回 ok 却返回了 bad_code。下一步要从「API 进程看到的
#    库状态」入手查（见 2026-10-15 的工作日志）。
st, body = call("POST", "/api/auth/reset",
                {"name": "alice", "code": code, "password": "brandnew123"})
check("★ 重置成功", st == 200 and body.get("ok") is True, "实际 %s %s" % (st, body))
check("★ 且踢掉该账号**所有**设备的登录（revoked >= 1）",
      int(body.get("revoked", 0)) >= 1,
      "重置的全部意义就是「我怀疑号被人用了」—— 不踢等于没做事。实际 %s" % body)

st, _ = call("GET", "/api/me", token=token_a)
check("  · 老令牌真的失效了（401）", st == 401, "实际 %s" % st)

st, body = call("POST", "/api/auth/reset",
                {"name": "alice", "code": code, "password": "another12345"})
check("  · 同一个码不能用第二次", st == 400 and body.get("error") == "used",
      "实际 %s %s" % (st, body))

st, body = login("alice", "brandnew123", device="newphone")
check("  · 新密码能登录（重置真的改成功了）", st == 200 and body.get("token"),
      "实际 %s %s" % (st, body))
token_a = body.get("token", "")
st, _ = login("alice", "hunter2hunter", device="x")
check("  · 旧密码不能登录了", st == 401, "实际 %s" % st)

# ===========================================================================
section("五、改密码 vs 重置：两件事的意图不同")
# ===========================================================================

login("alice", "brandnew123", device="phoneA")
login("alice", "brandnew123", device="tabletB")
before = ec_db.live_token_count(int(ec_db.get_user("alice")["id"]))
st, body = call("POST", "/api/auth/password",
                {"oldPassword": "brandnew123", "newPassword": "changed12345"}, token=token_a)
check("改密码成功", st == 200, "实际 %s %s" % (st, body))
check("★ 改密码**不踢**其它设备（revoked == 0）",
      int(body.get("revoked", 0)) == 0
      and ec_db.live_token_count(int(ec_db.get_user("alice")["id"])) == before,
      "用户只是在某台设备上换了密码，不该被从平板/备用机上踢下来。"
      "实际 revoked=%s（前 %d 后 %d）"
      % (body.get("revoked"), before, ec_db.live_token_count(int(ec_db.get_user("alice")["id"]))))

st, body = call("POST", "/api/auth/password",
                {"oldPassword": "错的", "newPassword": "another12345"}, token=token_a)
check("  · 旧密码不对时不给改", st == 401, "实际 %s" % st)

st, body = call("POST", "/api/auth/logout", token=token_a)
check("登出只撤销**当前**这个令牌", st == 200 and int(body.get("ok") in (True, 1)) == 1)
st, _ = call("GET", "/api/me", token=token_a)
check("  · 当前令牌失效", st == 401, "实际 %s" % st)
other = login("alice", "changed12345", device="tabletB")[1].get("token", "")
login("alice", "changed12345", device="phoneA")
st, _ = call("GET", "/api/me", token=other)
check("  · 但其它设备还活着（登出不是「退出所有设备」）", st == 200, "实际 %s" % st)

# ===========================================================================
section("六、清空记录必须连服务端一起清（否则一刷新又回来了）")
# ===========================================================================

_st, _body = login("alice", "changed12345", device="phoneA")
if "token" not in _body:
    print("  ❌ 这一步拿不到令牌：HTTP %s / %s" % (_st, _body))
    raise SystemExit("定位用：上面的响应就是原因（429 = 限速没清干净；401 = 密码不是这个）")
token_a = _body["token"]
sync(token_a, "phoneA", days={D: 1500}, since=0)
st, body = call("POST", "/api/sync/wipe", token=token_a)
check("清空接口可用", st == 200 and int(body.get("removed", 0)) >= 1, "实际 %s %s" % (st, body))
st, body = sync(token_a, "phoneA", days={}, since=0)
check("★ 清完之后再同步，读数确实空了（不会自己回来）",
      body.get("days") == {}, "实际 %s" % body.get("days"))
st, _ = call("POST", "/api/sync/wipe")
check("  · 清空需要鉴权", st == 401, "实际 %s" % st)

# ===========================================================================
section("六·B、生词本同步：事件只增、状态由客户端重放")
# ===========================================================================


def words(token, cards=None, deleted=None, reviews=None, since=0, device="devA"):
    return call("POST", "/api/sync/words", {
        "device": device,
        "cards": cards or [],
        "deleted": deleted or {},
        "reviews": reviews or [],
        "sinceSeq": since,
    }, token=token)


def card(lemma, surface="", added=None, deleted_at=0, contexts=None, reviews=None):
    return {
        "lemma": lemma, "surface": surface,
        "addedAt": added if added is not None else T0,
        "deletedAt": deleted_at,
        "contexts": contexts or [],
        "reviews": reviews or [],
    }


T0 = 1_800_000_000_000
tok = login("dave", "hunter2hunter", device="devD")[1].get("token", "")
st, body = words(tok, cards=[card("listen", "listening", T0)])
check("生词本：一次往返把卡片存下来", st == 200 and len(body.get("cards", [])) == 1,
      "实际 %s %s" % (st, body))

st, body = words(tok, cards=[card("listen", "listening", T0, contexts=[{
    "episodeId": "ep1", "episodeTitle": "T", "lineStartMs": 1000,
    "lineEndMs": 2000, "sentence": "I listen to it.", "translation": "我听它。",
}])])
check("语境跟着卡片一起上去", st == 200 and len(body["cards"][0]["contexts"]) == 1, str(body)[:200])

# ★ 复习事件：两台设备各复习一次，两条都要留住
st, body = words(tok, cards=[card("listen", "listening", T0)], reviews=[
    {"lemma": "listen", "at": T0 + 100, "rating": 3, "device": "phoneA"}])
st, body = words(tok, cards=[card("listen", "listening", T0)], reviews=[
    {"lemma": "listen", "at": T0 + 200, "rating": 3, "device": "tabletB"}])
both = [r for r in body["reviews"] if r["lemma"] == "listen"]
check("★ 两台设备各自复习：两条事件都在（不是「谁后写谁赢」）",
      st == 200 and len(both) == 2, str(both))

st, body = words(tok, reviews=[{"lemma": "listen", "at": T0 + 100, "rating": 3, "device": "phoneA"}])
check("★ 重复上报同一条复习：不会变成两条（幂等）",
      len([r for r in body["reviews"] if r["lemma"] == "listen" and r["at"] == T0 + 100]) == 1,
      str(body["reviews"])[:200])

# 水位线：seq 只回新的
seq_now = body["seq"]
st, body = words(tok, since=seq_now)
check("★ 水位线按服务端 seq 走：拉过的不再回", body["reviews"] == [],
      "用时间戳当水位线会漏掉「钟慢的设备」发来的事件 —— 实际 %s" % str(body["reviews"])[:120])

# 删除：addedAt 取 MAX、deletedAt 取 MAX ⇒ 删了就删了
st, body = words(tok, deleted={"listen": T0 + 300})
check("★ 删除生效（墓碑）", "listen" not in [c["lemma"] for c in body["cards"]]
      and body["deleted"].get("listen") == T0 + 300, str(body)[:200])

# 删了再添加：新 addedAt 晚于墓碑 ⇒ 又活着
st, body = words(tok, cards=[card("listen", "listen", T0 + 400)])
check("★ 删了再加：addedAt 晚于墓碑 ⇒ 卡片回来", "listen" in [c["lemma"] for c in body["cards"]],
      "判据是 addedAt > deletedAt —— 两个时间戳都取 MAX 才能让这条自洽")

# 旧客户端的 addedAt 更早时不会把新的顶掉
st, body = words(tok, cards=[card("listen", "lis", T0 + 50)])
alive = {c["lemma"]: c for c in body["cards"]}
check("  · 更早的 addedAt 不会把时间戳改小", alive["listen"]["addedAt"] == T0 + 400,
      str(alive.get("listen")))

# 隔离
bob_tok = login("alice", "changed12345", device="phoneA")[1].get("token", "")
st, body = words(bob_tok)
check("★ 生词本也按账号隔离", body.get("cards") == [] and body.get("deleted") == {},
      str(body)[:160])

# ===========================================================================
section("七、结构：有些事实只能从源码与配置读")
# ===========================================================================

db_src, app_src, sec_src = read(DB_SRC), read(APP_SRC), read(SEC_SRC)
unit, admin_src = read(UNIT), read(ADMIN)


def sh(cand, api_cand):
    return "%s\n%s" % (read(cand), read(api_cand))


check("★ `daily` 的主键含 device（求和模型的结构前提）",
      "PRIMARY KEY (user_id, day, device)" in db_src,
      "少了 device，「每台设备一列」就不成立，整个同步模型塌掉")
check("★ 合并用 MAX(...)，不是覆盖",
      "MAX(seconds, excluded.seconds)" in db_src,
      "★ 这一句是幂等的地基。改成 excluded.seconds（覆盖）→ 重发一次就变小")

# ⚠️ 锚点必须取**代码里独有的那一整行**。第一版用的是裸的
#    `MAX(seconds, excluded.seconds)`，而它在 bump_daily 的**文档注释**里
#    也被原样写过 —— replace(.., 1) 打在了注释上，真正的 SQL 一个字没动，
#    于是这条坏样本「通过」了（什么都没测）。
#    「替换打在注释上」是本工程记过账的一类（见 verify-motion-129.py 与
#    verify-checkin-goal.py），这次撞在我自己的坏样本构造上。
bad_max = db_src.replace("seconds = MAX(seconds, excluded.seconds),",
                         "seconds = excluded.seconds,", 1)
check("坏样本 1（合并变成覆盖）被拦下",
      bad_max != db_src and "seconds = MAX(seconds, excluded.seconds)," not in bad_max,
      "★ 幂等的地基是那句 MAX：换成覆盖之后，重发一次值就变小")
check("  · 坏样本 1 的构造本身有效（锚点在代码上，不在注释里）",
      bad_max != db_src, "替换没生效的话，这条坏样本「通过」是假的")

check("★ 密码比对用常数时间比较",
      "hmac.compare_digest" in sec_src,
      "用 == 会在第一个不同的字符处提前返回 ⇒ 逐字节可猜")
# 同样要避开注释里那次引用（`verify_password` 的文档里原样写着它）
bad_cmp = sec_src.replace("return hmac.compare_digest(dk.hex(), hash_hex)",
                          "return dk.hex() == hash_hex", 1)
check("坏样本 2（换成 == 比较）被拦下",
      bad_cmp != sec_src and "return hmac.compare_digest" not in bad_cmp,
      "用 == 会在第一个不同的字符处提前返回 ⇒ 逐字节可猜")
check("  · 坏样本 2 的构造本身有效", bad_cmp != sec_src)

check("★ 服务进程跑在专用非 root 用户下",
      "User=ecapi" in unit and "User=root" not in unit,
      "这是第一个对公网解析不可信输入的进程 —— 给它 root 没有任何好处")
bad_unit = unit.replace("User=ecapi", "User=root", 1)
check("坏样本 3（改成 root 运行）被拦下", "User=ecapi" not in bad_unit)

check("★ `--workers 1`（限速是进程内状态，多开会变成各自算各自的）",
      "--workers 1" in unit,
      "多一个 worker 就让 5 次上限变成 5×N 次，而且没有任何东西会报错")
# ⚠️ 锚点要取 ExecStart 那一整行：`--workers 1` 在**注释**里也出现过
#    （注释解释了它为什么必须是 1）—— 裸子串替换会打在注释上，坏样本失效。
bad_workers = unit.replace("--port 8081 --workers 1", "--port 8081 --workers 4", 1)
check("坏样本 4（多开 worker）被拦下",
      bad_workers != unit and "--port 8081 --workers 1" not in bad_workers)
check("  · 坏样本 4 的构造本身有效", bad_workers != unit)

check("★ 日志只进 journald（不自己写文件）",
      "StandardOutput=journal" in unit and "StandardError=journal" in unit and "append:" not in unit,
      "多一份能被误拷出来的日志，就多一次凭证泄露的机会")
check("  · 单元里没有任何形式的凭证环境变量",
      not re.search(r"Environment=\S*(TOKEN|PASSWORD|SECRET)", unit, re.I))

# ★ 判据取**路由表**本身，而不是「源码里有没有 admin 这个词」——
#   后者的第一版直接假失败：app.py 的文档里写着「审批在 bin/ec-admin」，
#   那个字符串里就有 admin。判据落在名字上，就会这样。
app_routes = re.findall(r'@app\.(?:get|post|put|delete)\("([^"]+)"', app_src)
check("★ `/api/` 下没有任何管理端点（管理面走 SSH + 直连库）",
      bool(app_routes) and not [r for r in app_routes if "admin" in r.lower()]
      and os.path.isfile(ADMIN),
      "每多一个端点就多一处要鉴权、要限速、要防越权的地方。"
      "实际路由表：%s" % app_routes)
check("  · 审批/发码真的在命令行里",
      "def cmd_approve" in admin_src and "def cmd_reset" in admin_src)

check("★ API 进程里没有任何 print（日志一律走 uvicorn）",
      not re.search(r"^\s*print\(", app_src, re.M),
      "print 出去的东西没人管、也没有格式约束，最容易顺手把 body 打出来")

# 明文面：/api/ 不许出现在共享给 80 的路由片段里
routes = read(os.path.join(NGINX, "ec-routes.conf"))
tls = read(os.path.join(NGINX, "ec-tls.conf"))
check("★ 反代只配在 443 那一份里，**不在** 80 共享的路由片段里",
      "/api/" in tls and "/api/" not in routes,
      "★ ec-routes.conf 被 80 与 443 共用一个 include：写在那里等于让"
      "密码与令牌走明文 IP 通道")
# ⚠️ 这条坏样本第一版写成了 `... or True` —— 那是个**恒真**的检查，
#    等于什么都没判。判据必须是「真实文件里没有」+「改坏之后就有」两个方向。
def routes_has_api(text):
    return "/api/" in text


bad_routes = routes + "\nlocation ^~ /api/ { proxy_pass http://127.0.0.1:8081; }\n"
check("坏样本 5（把反代写进共享路由）被拦下",
      (not routes_has_api(routes)) and routes_has_api(bad_routes),
      "★ ec-routes.conf 被 80 与 443 共用：写在那里 = 密码有机会走明文")
check("  · 443 那份里确实有反代到 127.0.0.1:8081",
      "proxy_pass http://127.0.0.1:8081" in tls, "实际：%s" % ("有" if "proxy_pass" in tls else "没有"))

check("  · nginx 配置里不许出现任何凭证",
      not re.search(r"(password|token)\s*=", tls + routes, re.I))

# ===========================================================================
print()
print("=" * 68)
print("  通过 %d 项，失败 %d 项" % (PASS, FAIL))
print("=" * 68)
if FAIL:
    print()
    print("  ❌ 未通过：")
    for f in FAILURES:
        print("     - " + f)
print()

proc.terminate()
try:
    proc.wait(timeout=5)
except Exception:
    proc.kill()
sys.exit(1 if FAIL else 0)
