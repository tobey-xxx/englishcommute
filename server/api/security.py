#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
密码、令牌、限速 —— 这个服务里**唯一**碰秘密的地方。

## 为什么三样收在一个文件里

它们是同一件事的三个面：**证明你是谁**。散进路由里的话，「密码比对」这种代码
会在几个地方各写一遍，而其中一处漏了比较、或漏了限速，不会有任何东西报错。

## 三条硬要求

**① 密码只以 scrypt 哈希落盘，每人独立 salt。**
用 stdlib 的 `hashlib.scrypt`（不引第三方库）：它是**内存硬**的，抗 GPU/ASIC；
同在 stdlib 的 pbkdf2 只能靠堆迭代次数硬扛。代价是每次比对约 50~100 ms、
16 MB 内存 —— 对登录接口完全够用，而且它顺手给暴力猜测加了成本。

**② 令牌在库里只存 sha256。**
库被拖走时拿到的是一堆哈希，不是可以直接用的凭证。
（令牌本身是 256 位随机数，没有字典可猜 —— 所以 sha256 足够，
不需要再加盐或慢哈希，那是给**低熵口令**准备的手段。）

**③ 日志里永远不出现密码与令牌。**
这条有断言守着（`tools/verify-auth-api.py`）。本服务唯一的日志出口是
uvicorn 的 access log，它只记「方法 / 路径 / 状态码」；凭证都在**请求体与
请求头**里，天然进不了日志。
⚠️ 所以：**不要**在任何地方 `print(body)`、`logger.info(headers)`。
"""
import hashlib
import hmac
import os
import secrets
import threading
import time

# scrypt 参数：n=2^14 约 16 MB 内存、50~100 ms。调大要重新估计登录延迟，
# 调小等于削弱它存在的意义。这三个数与 dklen 都写进哈希本身之外的注释里 ——
# 将来要换参数时，必须知道老用户是用哪一套算的（见 verify_password 的说明）。
SCRYPT_N = 2 ** 14
SCRYPT_R = 8
SCRYPT_P = 1
DKLEN = 64

# 口令规则：≥8 位、不许等于账号名。
# ⚠️ 刻意**不要求**大小写数字符号齐全：那种规则只会逼人写出 `Aa1!` 然后记不住，
#    而「记不住」在这个 App 里会直接变成一次重置请求（人工成本转嫁给我们）。
MIN_PASSWORD = 8


def hash_password(password: str) -> tuple[str, str]:
    """返回 `(哈希 hex, salt hex)`。salt 每次都不一样。"""
    salt = secrets.token_bytes(16)
    dk = hashlib.scrypt(
        password.encode("utf-8"), salt=salt,
        n=SCRYPT_N, r=SCRYPT_R, p=SCRYPT_P, dklen=DKLEN,
    )
    return dk.hex(), salt.hex()


def verify_password(password: str, hash_hex: str, salt_hex: str) -> bool:
    """常数时间比对。

    ⚠️ 必须用 `hmac.compare_digest` 而不是 `==`：`==` 会在第一个不同的字符处
       提前返回，于是「逐字节猜」在时间上可测 —— 这是教科书级的漏洞，
       而它在本地自测里完全看不出来。
    """
    try:
        dk = hashlib.scrypt(
            password.encode("utf-8"), salt=bytes.fromhex(salt_hex),
            n=SCRYPT_N, r=SCRYPT_R, p=SCRYPT_P, dklen=DKLEN,
        )
    except ValueError:
        # salt 不是合法 hex（库被手改过）—— 当作比对失败，不抛给调用方
        return False
    return hmac.compare_digest(dk.hex(), hash_hex)


def password_error(password: str, name: str) -> str | None:
    """返回不符合规则的原因；合规返回 None。判据要能直接当文案用。"""
    if len(password) < MIN_PASSWORD:
        return f"密码至少 {MIN_PASSWORD} 位"
    if password.lower() == name.lower():
        return "密码不能和账号一样"
    return None


# ---------------------------------------------------------------------------
# 令牌
# ---------------------------------------------------------------------------

TOKEN_DAYS = 400          # 兜底上限：只被「用到」的令牌会一直续期（见 touch_token）
TOKEN_EXTEND_HOURS = 24   # 距上次续期超过这个时长才写一次库（省掉每请求一次写）


def new_token() -> str:
    """256 位随机、URL 安全。**只在签发那一刻存在于内存里。**"""
    return secrets.token_urlsafe(32)


def token_hash(token: str) -> str:
    return hashlib.sha256(token.encode("utf-8")).hexdigest()


# ---------------------------------------------------------------------------
# 重置码
# ---------------------------------------------------------------------------

RESET_CODE_TTL_MIN = 30


def new_reset_code() -> str:
    """8 位数字。

    ⚠️ 字符集刻意去掉 0/1/6/9 这类易混字形（人工念、人工抄的场景），
       用的是 2/3/4/5/7/8。8 位 × 6 种 = 约 1.7e6 种，配合
       「30 分钟 + 一次即废 + 限速」足够；而且它**不靠邮件**送达，
       是管理员看着屏幕念给用户的。
    """
    alphabet = "234578"
    return "".join(secrets.choice(alphabet) for _ in range(8))


def code_hash(code: str) -> str:
    return hashlib.sha256(code.encode("utf-8")).hexdigest()


# ---------------------------------------------------------------------------
# 限速（内存实现）
# ---------------------------------------------------------------------------


def _env_int(key: str, default: int) -> int:
    try:
        return int(os.environ.get(key, default))
    except ValueError:
        return default


class RateLimiter:
    """滑动窗口限速。**进程内、重启即清空。**

    ⚠️ 参数可以经环境变量覆盖（`EC_RL_WINDOW` / `EC_RL_MAX_FAILS`）——
       不是为了线上调参，而是为了让**验证脚本能把窗口调到几秒**：
       一条 300 秒的窗口会把测试自己后面所有的登录都挡住，
       而「窗口过去之后能自己恢复」这条判据也就没法验了。

    ## 为什么可以只是内存的

    这台机器上只有**一个** uvicorn 进程（systemd 管的单实例）。多开 worker 会让
    每个 worker 各算各的窗口 —— 那不是「限速没那么严」，而是**限速形同不存在**，
    而且没有任何东西会报错。所以 `ec-api.service` 里那句 `--workers 1` 是硬要求。

    真要做多实例的话，这份状态必须挪进数据库或 Redis —— 届时这个类的签名不变，
    换掉实现即可（调用点只认 `blocked` / `fail` / `clear`）。

    ## 为什么按「账号 + IP」两条线各限一次

    只按 IP：换个网络就能绕（家用宽带重播号即可）。
    只按账号：能把一个已知账号**锁死**，等于免费的拒绝服务。
    两条线都限，攻击者要同时换 IP 和账号才走得动。
    """

    def __init__(self, window_sec: int | None = None, max_fails: int | None = None,
                 max_keys: int = 4096):
        window_sec = window_sec if window_sec is not None else _env_int("EC_RL_WINDOW", 300)
        max_fails = max_fails if max_fails is not None else _env_int("EC_RL_MAX_FAILS", 5)
        self.window_sec = window_sec
        self.max_fails = max_fails
        self.max_keys = max_keys
        self._hits: dict[str, list[float]] = {}
        self._lock = threading.Lock()

    def _prune(self, now: float) -> None:
        """顺手清掉过期的键。

        ⚠️ 必须清：键里含 IP，而公网上的扫描器会用无数个源地址打进来 ——
           不清就是一条缓慢的内存泄漏（几周后才看得出来，而且不会报错）。
        """
        if len(self._hits) <= self.max_keys:
            cutoff = now - self.window_sec
            for k in [k for k, v in self._hits.items() if not v or v[-1] < cutoff]:
                self._hits.pop(k, None)
        else:
            # 撞上限时直接清空：宁可放弃这一窗口的统计，也不让内存无界增长
            self._hits.clear()

    def blocked(self, key: str) -> int:
        """被限速时返回还要等多少秒；否则 0。"""
        now = time.time()
        with self._lock:
            self._prune(now)
            hits = [t for t in self._hits.get(key, []) if t > now - self.window_sec]
            self._hits[key] = hits
            if len(hits) >= self.max_fails:
                return max(1, int(self.window_sec - (now - hits[0])))
            return 0

    def fail(self, key: str) -> None:
        with self._lock:
            self._hits.setdefault(key, []).append(time.time())

    def clear(self, key: str) -> None:
        with self._lock:
            self._hits.pop(key, None)
