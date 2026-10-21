#!/usr/bin/env bash
#
# 安装 nginx 站点配置 + systemd 定时器单元。**本地跑**（它自己 scp 上去）。
#
#   ./install-nginx.sh          # 装配置 + reload
#
# `deploy.sh` 的第 3 步就是调它；`deploy.sh --nginx-only` 也只调它。
#
# ============================================================================
# ## 为什么从 deploy.sh 里拆出来（3.1.0）
# ============================================================================
#
# 上域名这件事必须**分两步**，而两步之间隔着一个几十秒到几分钟的窗口：
#
#     ① 装「纯 IP + /vocab 收口」的配置 —— 与域名完全无关，随时可以做
#     ② 签证书 → 装域名入口（80 跳转 + 443 TLS）
#
# 如果只有一个入口（deploy.sh），第 ① 步会连带把 `bin/` 同步过去，
# 而 `bin/cycle.py` 里带着域名基址 —— 定时器**最多 30 分钟**就会跑下一轮，
# 把 index.json 的 audioUrl 写成 `https://YOUR_DOMAIN/...`。
#
# 那一刻如果 443 还没起来，客户端会去一个连不上的地址取音频：
#
#     **有字幕、有封面、点下去没声音。**
#
# 而且这个状态**不会自己恢复** —— index.json 一直那么写着，直到 443 起来。
# 所以「配置」和「代码」需要在时间上分开，而不是靠「记得快点签证书」。
# ============================================================================
set -euo pipefail

HOST="${EC_HOST:-ec}"          # ~/.ssh/config 里的别名
HERE="$(cd "$(dirname "$0")" && pwd)"

echo "== 安装站点配置与定时器（2.1.9 起纳入版本管理）=="
# 为什么这一步必须在这里、而不是靠人工登上去改：
# nginx 与 systemd 的配置原来**只存在于服务器上**，于是「改了本地、忘了服务器」
# 或者反过来，都查不出来源 —— 而症状往往是「行为与代码对不上」，最难查。
# 现在它们和 bin/ 一样是版本化的，这里负责装过去。
ssh "$HOST" 'mkdir -p /opt/ec/www/audio /opt/ec/www/apk && chown -R www-data:www-data /opt/ec/www/audio /opt/ec/www/apk && chmod 755 /opt/ec/www/audio /opt/ec/www/apk && echo "   ✅ www/audio 与 www/apk 就位（音频发布区 + 更新包发布区）"'

# 3.1.0 起 nginx/ 下有四份，**四份都要发过去**：
#   ec.conf          纯 IP 入口（default_server）—— 是升级通道的生命线
#   ec-domain.conf   域名 80：ACME 质询 + 301 跳 https
#   ec-tls.conf      域名 443：TLS + 同一套内容
#   ec-routes.conf   三处共用的 location（抽出来就是为了不让它们分叉）
# 少发一份的表现是 nginx -t 报「include 的文件不存在」—— 看着像服务器上被删了。
scp -q "$HERE/nginx/ec.conf" "$HERE/nginx/ec-routes.conf" \
       "$HERE/nginx/ec-domain.conf" "$HERE/nginx/ec-tls.conf" "$HOST:/tmp/"
scp -q "$HERE/systemd/ec-cycle.timer" "$HERE/systemd/ec-cycle.service" "$HOST:/tmp/"

# 申证书那个脚本（`bin/setup-tls.sh`）**必须在这里单独传**。
#
# 它本来会跟着 `deploy.sh` 第 1 步的 bin/ 同步一起过去 —— 但那一步正是
# 我们**不能**在这个阶段做的（它会把带域名基址的 cycle.py 一起送上去，
# 而 443 还没起来。见本文件头部）。
#
# 所以「上域名」这个阶段自己把它带上。这不是绕过版本管理 ——
# 文件的来源仍然是版本库里的那一份，只是**换了一个时机**送过去。
scp -q "$HERE/bin/setup-tls.sh" "$HOST:/opt/ec/bin/setup-tls.sh"
ssh "$HOST" 'chmod +x /opt/ec/bin/setup-tls.sh && echo "   ✅ setup-tls.sh 就位（签证书用）"'

ssh "$HOST" '
set -e
# 装之前把**当前**那份留一份现场（只留最近一次）。
# 出问题时第一件事是「和上一版 diff」，而没有上一版时只能靠回忆 ——
# 这个工程里「靠回忆」已经错过好几次了。
mkdir -p /opt/ec/data/nginx-prev
for f in ec ec-domain ec-tls; do
  if [ -f "/etc/nginx/sites-available/$f" ]; then
    cp -a "/etc/nginx/sites-available/$f" "/opt/ec/data/nginx-prev/$f.prev"
  fi
done
if [ -f /etc/nginx/snippets/ec-routes.conf ]; then
  cp -a /etc/nginx/snippets/ec-routes.conf /opt/ec/data/nginx-prev/ec-routes.conf.prev
fi
mkdir -p /etc/nginx/snippets
install -m 644 /tmp/ec-routes.conf /etc/nginx/snippets/ec-routes.conf
install -m 644 /tmp/ec.conf /etc/nginx/sites-available/ec
ln -sf /etc/nginx/sites-available/ec /etc/nginx/sites-enabled/ec
# ── 域名入口：**证书在才启用** ─────────────────────────────────────────────
# 这个 if 不是「容错」，它在处理一个真实的启动顺序依赖：
# ec-tls.conf 里 ssl_certificate 指向一个还不存在的文件时，`nginx -t` 会
# **直接失败**（不是警告），于是整个 reload 被拒 —— 站点看着还在跑
# （旧配置仍在内存里），但新加的东西全没生效，而报出来的是
# 「nginx 配置有语法错误」，把人引向配置本身去查。
#
# 摘掉而不是留着：让站点停在一个**自洽**的状态（纯 IP 完全可用、域名未启用）。
CERT=/etc/letsencrypt/live/YOUR_DOMAIN/fullchain.pem
install -m 644 /tmp/ec-domain.conf /etc/nginx/sites-available/ec-domain
install -m 644 /tmp/ec-tls.conf /etc/nginx/sites-available/ec-tls
if [ -f "$CERT" ]; then
  ln -sf /etc/nginx/sites-available/ec-domain /etc/nginx/sites-enabled/ec-domain
  ln -sf /etc/nginx/sites-available/ec-tls /etc/nginx/sites-enabled/ec-tls
  echo "   ✅ 证书在位（$(openssl x509 -enddate -noout -in $CERT | cut -d= -f2) 到期）→ 域名入口已启用"
else
  rm -f /etc/nginx/sites-enabled/ec-domain /etc/nginx/sites-enabled/ec-tls
  echo "   ⚠️ 还没有证书 → 域名入口未启用，站点停在纯 IP 状态"
  echo "      签证书：在服务器上跑 /opt/ec/bin/setup-tls.sh"
fi
# ⚠️ 先 -t 再 reload：配置写错时 reload 会被拒（旧配置仍在内存里跑，站点不受影响），
#    但**别**把它当成「没关系」—— 新加的路由不会生效，而下一个人会以为已经生效了。
if nginx -t 2>&1 | grep -q "syntax is ok"; then
  systemctl reload nginx
  echo "   ✅ nginx 配置已生效"
  echo "      sites-enabled：$(ls /etc/nginx/sites-enabled/ | tr "\n" " ")"
else
  echo "   ❌ nginx 配置有语法错误，未 reload（站点仍在用旧配置）"
  nginx -t
  exit 1
fi
install -m 644 /tmp/ec-cycle.timer /etc/systemd/system/ec-cycle.timer
install -m 644 /tmp/ec-cycle.service /etc/systemd/system/ec-cycle.service
systemctl daemon-reload
# ⚠️ 必须 restart 而不是 enable --now：定时器的**时间表**只在单元被读取时生效，
#    已经在跑的 timer 不会因为文件变了就换时间表。
systemctl restart ec-cycle.timer
# ⚠️ 打印**下一次运行时刻**而不是「某个日期字段」——第一版这里是
#    `list-timers | sed 2p | awk "{print \$1, \$2}"`，输出的是
#    「Tue 2026-09-22」，看着像生效了、其实什么信息都没给。
echo "   ✅ 定时器已生效：$(systemctl list-timers ec-cycle.timer --no-pager | sed -n 2p | awk "{print \$1, \$2, \$3}") 触发（$(systemctl show ec-cycle.timer -p TimersCalendar --value | sed "s/.*OnCalendar=//; s/ ;.*//")）"
rm -f /tmp/ec.conf /tmp/ec-routes.conf /tmp/ec-domain.conf /tmp/ec-tls.conf \
      /tmp/ec-cycle.timer /tmp/ec-cycle.service
'
