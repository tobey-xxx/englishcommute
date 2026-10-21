#!/usr/bin/env bash
#
# 给 YOUR_DOMAIN 申请 Let's Encrypt 证书。**在服务器上跑**（由 deploy.sh 同步到 /opt/ec/bin/）。
#
#   /opt/ec/bin/setup-tls.sh           申请（已有有效证书则跳过）
#   /opt/ec/bin/setup-tls.sh --force   强制重签
#
# ============================================================================
# ## 为什么用 webroot 而不是 `certbot --nginx`
# ============================================================================
#
# `certbot --nginx` 会**自动改写** /etc/nginx 下那个 server 块：插 ssl_certificate、
# 加 listen 443、把 80 改成跳转。而这台机器上那些文件是**版本管理的**
# （本地 nginx/ 目录是真相源，deploy.sh 负责装过去）。
#
# 于是会发生这样的事：certbot 改了服务器上的文件 → 一切正常 → 下次 deploy.sh
# 用本地版本覆盖回去 → certbot 的改动没了 → 下次续期时 certbot 又发现
# 「配置里没有 ssl_certificate」，再改一遍……**两边永远在打架**，
# 而症状只是「偶尔证书不更新」，看不出是谁改的。
#
# webroot 模式只往 /opt/ec/www/.well-known/acme-challenge/ 放一个临时文件，
# 一行 nginx 配置都不碰。配置的所有权始终在一处。
#
# ============================================================================
# ## 为什么不在这里顺手把 443 配置也装上
# ============================================================================
#
# 因为顺序依赖是**反的**：
#
#     ec-tls.conf 里 `ssl_certificate` 指向证书 → 证书不存在时 `nginx -t` 直接失败
#
# 所以分工是：
#
#     本脚本  → 只负责把证书弄到 /etc/letsencrypt/live/YOUR_DOMAIN/
#     deploy.sh → 看到证书在，才把域名那两个 server 块装上
#
# 这样任何一个时刻站点都处在**自洽**状态：要么纯 IP、要么 IP + 域名。
#
# ============================================================================
set -euo pipefail

DOMAIN="${EC_TLS_DOMAIN:-YOUR_DOMAIN}"
WEBROOT="${EC_TLS_WEBROOT:-/opt/ec/www}"
EMAIL="${EC_TLS_EMAIL:-admin@example.com}"
LIVE="/etc/letsencrypt/live/$DOMAIN"
FORCE="${1:-}"

say() { printf '%s\n' "$*"; }
hr()  { printf '%s\n' "────────────────────────────────────────────────────────────────"; }

hr
say "① 前置检查"
hr

# ── nginx 活着且在服务内容 ────────────────────────────────────────────────────
if ! nginx -t >/dev/null 2>&1; then
  say "❌ nginx 配置检查不通过，先修它："
  nginx -t
  exit 1
fi
# ⚠️ 用 Host: _ 明确走 default_server：域名块装上之后，带真实域名的请求
#    会被 301 跳走，拿不到 200，看着像「站点坏了」。
code=$(curl -s -o /dev/null -w '%{http_code}' -H 'Host: _' http://127.0.0.1/healthz || true)
if [ "$code" != "200" ]; then
  say "❌ 本机 /healthz 返回 $code（期望 200）—— nginx 没在工作，先解决它"
  exit 1
fi
say "   ✅ nginx 正常，/healthz = 200"

# ── DNS 必须已经指向这台机器 ──────────────────────────────────────────────────
# ⚠️ 这一步不能省。Let's Encrypt 是从**公网**来打这台机器的，
#    解析没生效时申请会失败，而且失败信息是"challenge could not be validated"
#    —— 看起来像服务器/防火墙的问题，实际是 DNS 还没生效。
resolved=$(getent hosts "$DOMAIN" 2>/dev/null | awk '{print $1}' | head -1 || true)
public_ip=$(curl -s --max-time 10 https://api.ipify.org || true)
say "   $DOMAIN 解析到   = ${resolved:-（无）}"
say "   这台机器的公网 IP = ${public_ip:-（取不到）}"
# ⚠️ 本机解析通了**不代表** Let's Encrypt 那边也通了 —— 两边缓存策略不同。
#    真踩过这一次：记录加完几秒，本机 dig 立刻就有答案，而 LE 报的是
#    `NXDOMAIN looking up A for ...` —— 它的递归解析器在记录存在**之前**
#    查过一次，把失败结果缓存住了（负缓存通常 10 分钟）。
#    多问两个公共递归，是为了在**发起申请之前**对这件事有个数，
#    而不是等 LE 报一个看起来像「你根本没加记录」的错。
for r in 8.8.8.8 1.1.1.1; do
  v=$(dig +short @"$r" A "$DOMAIN" 2>/dev/null | head -1 || true)
  say "   经 $r 查     = ${v:-（没答案 —— 外面还没看到，等一会儿再签）}"
done
if [ -z "$resolved" ]; then
  say ""
  say "❌ $DOMAIN 解析不出 IP。去 DNSPod 加一条 A 记录（或等它生效，通常几分钟）。"
  exit 1
fi
if [ -n "$public_ip" ] && [ "$resolved" != "$public_ip" ]; then
  say ""
  say "❌ 解析到了别的机器（$resolved ≠ $public_ip）。"
  say "   证书必须签给「公网能打到的那台」，否则签发会失败在质询阶段。"
  exit 1
fi
say "   ✅ 解析指向本机"

# ── 已有证书就先报一下状态 ────────────────────────────────────────────────────
if [ -f "$LIVE/fullchain.pem" ]; then
  say "   已有证书，$(openssl x509 -enddate -noout -in "$LIVE/fullchain.pem" | cut -d= -f2) 到期"
fi

say ""
hr
say "② 安装 certbot（如果还没有）"
hr
if command -v certbot >/dev/null 2>&1; then
  say "   ✅ 已装：$(certbot --version 2>&1 | head -1)"
else
  say "   正在装（Debian 的 python3-certbot，会顺带装 certbot.timer 负责自动续期）…"
  apt-get update -qq
  DEBIAN_FRONTEND=noninteractive apt-get install -y -qq python3-certbot
  say "   ✅ $(certbot --version 2>&1 | head -1)"
fi

say ""
hr
say "③ 申请证书"
hr
if [ "$FORCE" = "--force" ]; then
  KEEP="--force-renewal"
  say "   （--force：强制重签）"
else
  # 幂等：证书还在且没临近过期时**不做任何事**。
  # ⚠️ 这一条很重要 —— Let's Encrypt 对同一组域名有「每周 5 张重复证书」的限流，
  #    反复重签会在最需要证书的时候把自己锁在门外。
  KEEP="--keep-until-expiring"
fi

# `--deploy-hook "systemctl reload nginx"` 会被写进
# /etc/letsencrypt/renewal/YOUR_DOMAIN.conf 的 renew_hook，之后每次续期都会跑。
# ⚠️ 没有它的话：续期成功了，但 nginx 仍然捧着**旧证书**在内存里，
#    直到下一次 reload 才换 —— 而下次 reload 可能是几个月后的一次重启，
#    中间这段时间客户端看到的是已过期的证书。这是最常见的「续期配了但没用」。
CERTBOT_ARGS=(
  certonly --webroot -w "$WEBROOT"
  -d "$DOMAIN"
  --non-interactive --agree-tos "$KEEP"
  --deploy-hook "systemctl reload nginx"
)

set +e
OUT=$(certbot "${CERTBOT_ARGS[@]}" -m "$EMAIL" 2>&1)
rc=$?
set -e
printf '%s\n' "$OUT" | sed 's/^/   /'

if [ "$rc" -ne 0 ]; then
  say ""
  # ⚠️ **先看失败的是什么，再决定要不要重试。**
  #
  #    这里踩过一次，值得原样记下来：第一次真跑时失败原因写得很清楚 ——
  #        DNS problem: NXDOMAIN looking up A for YOUR_DOMAIN
  #    （记录刚加了几分钟，LE 的递归解析器在我们加记录**之前**查过一次，
  #      把 NXDOMAIN 缓存住了 —— 负缓存通常 10 分钟。）
  #
  #    而脚本当时把**所有**失败都归成「邮箱被拒」并自动重试。重试恰好成功了，
  #    于是看起来像「兜底救场」—— 实际是把真因指向了一个完全无关的方向。
  #    如果那天重试也失败，下一个人会去折腾邮箱，而问题一直在 DNS。
  #
  #    这就是「兜底分类必须先判子类」：把不同原因合并成一个「失败」，
  #    等于把下一步动作也一起弄丢了。
  case "$OUT" in
    *"DNS problem"*|*NXDOMAIN*|*"no valid A records"*)
      say "   ❌ 失败原因是 **DNS**：Let's Encrypt 从公网解析不到 $DOMAIN。"
      say "      最常见的情形是**记录刚加几分钟** —— 它那边的递归解析器在我们加记录"
      say "      **之前**查过一次，把 NXDOMAIN 缓存住了（负缓存通常 10 分钟）。"
      say "      ⚠️ 本地 dig 通了**不能**说明它那边也通了：两边缓存策略不同。"
      say "      等 5~10 分钟重跑本脚本即可，别的什么都不用改。"
      return_hint_dns=1
      ;;
    *"Timeout during connect"*|*"Connection refused"*|*"unauthorized"*)
      say "   ❌ 失败原因是 **80 端口打不进来**（ACME 质询要在 80 上取一个文件）。"
      say "      检查：① 云厂商安全组 / 防火墙是否放行 80"
      say "            ② nginx 在不在跑："
      say "               ssh <host> \"curl -s -o /dev/null -w '%{http_code}' -H 'Host: _' http://127.0.0.1/healthz\""
      ;;
    *"too many certificates"*|*rateLimited*|*"too many failed authorizations"*)
      say "   ❌ 失败原因是 **Let's Encrypt 限流**（同一组域名每周 5 张重复证书）。"
      say "      通常意味着前面已经反复重签过。等一周，或者："
      say "      · 已有证书能续期就别再重签（--keep-until-expiring 已经是默认行为了）"
      say "      · 只是想把配置装上去的话，不需要新证书"
      ;;
    *)
      say "   ⚠️ 带邮箱注册失败（rc=$rc），但**失败原因不属于上面任何一个已知类别**。"
      say "      先看上面那段 certbot 原文，再决定。如果确实与邮箱有关，可以不带邮箱重试 ——"
      say "      邮箱只用于证书快到期时的提醒邮件，而我们已配自动续期 + deploy-hook，"
      say "      缺它不影响能不能续上："
      certbot "${CERTBOT_ARGS[@]}" --register-unsafely-without-email 2>&1 | sed 's/^/   /'
      ;;
  esac
fi

# 已知的环境类失败：重试没有意义，直接出去（让上面那段话成为最后看到的东西）
if [ "${return_hint_dns:-0}" = "1" ]; then
  exit 1
fi

say ""
hr
say "④ 验证"
hr
if [ ! -f "$LIVE/fullchain.pem" ]; then
  say "❌ 证书没签出来。往上翻 certbot 的输出，最常见的原因："
  say "   · 80 端口从公网打不进来（腾讯云轻量的**防火墙**默认只开部分端口）"
  say "   · DNS 刚加还没生效（TTL 内）"
  exit 1
fi

notafter=$(openssl x509 -enddate -noout -in "$LIVE/fullchain.pem" | cut -d= -f2)
days=$(( ($(date -d "$notafter" +%s) - $(date +%s)) / 86400 ))
say "   ✅ $LIVE/fullchain.pem"
say "      到期：$notafter（还有 $days 天）"
say "      SAN： $(openssl x509 -noout -ext subjectAltName -in "$LIVE/fullchain.pem" | tail -1 | xargs)"
say "      签发：$(openssl x509 -noout -issuer -in "$LIVE/fullchain.pem" | sed 's/.*O = //; s/,.*//')"

# 续期定时器
if systemctl list-timers certbot.timer --no-pager >/dev/null 2>&1; then
  say "      续期：$(systemctl list-timers certbot.timer --no-pager | sed -n 2p | awk '{print $1, $2, $3}')"
else
  say "      ⚠️ 没看到 certbot.timer —— 自动续期可能没装上，检查 systemctl status certbot.timer"
fi

say ""
hr
say "⑤ 续期链路演练（certbot renew --dry-run）"
hr
say "   这一步用 LE 的 staging 环境**真的走一遍质询**。"
say "   跳过它的话，最危险的失败是「定时器在跑、每次都失败」—— 直到证书过期那天"
say "   所有客户端同时失联，而中间几个月系统里一切看起来都正常。"
if certbot renew --dry-run 2>&1 | sed 's/^/   /'; then
  say "   ✅ 续期链路可用"
else
  say "   ⚠️ 演练失败 —— **现在**就查，别等到证书过期。"
  say "      最可能的原因：80 上 ACME 质询被跳转或鉴权挡住了"
  say "      （看 /etc/nginx/sites-available/ec-domain.conf 的那个 ^~ 块）"
fi

say ""
hr
say "✅ 证书就绪。回本机跑一次部署，域名入口才会装上："
say "     cd EnglishCommute/2.0-服务端 && ./deploy.sh"
hr
