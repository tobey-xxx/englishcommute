#!/usr/bin/env bash
# 安装 / 更新「账号与打卡同步」接口（4.0.0）
#
#   ./deploy-api.sh          装或更新，然后体检
#   ./deploy-api.sh --stop   停掉服务（回滚用）
#
# ## 它只管**应用层**，四件事，全部幂等
#
#   ① 建专用用户 ecapi 与 /opt/ec/data（750、属 ecapi）
#   ② 传 api/*.py 与 bin/ec-admin、bin/backup_db.py
#   ③ 在现有 venv 里装 fastapi + uvicorn（已装就跳过，不重复打网络）
#   ④ 装并重启 systemd 单元（ec-api.service / ec-backup.timer），最后打健康检查
#
# ## ⚠️ 为什么它不碰 nginx
#
# nginx 的改动一律走 `install-nginx.sh`（那份是「入口」的唯一入口，而且会先留现场
# 好 diff）。分开的理由是**故障定位**：出问题时能一眼看出是「应用层没起来」
# 还是「入口没配」，而不是对着一坨混合输出猜。
#
# ## 为什么用专用用户而不是 root
#
# 这是这个项目**第一个对公网解析不可信输入**的进程。它只需要读自己的代码、
# 读写自己的数据目录 —— 给它 root 没有任何好处，代价是「一处解析漏洞 = 整台机器」。
set -euo pipefail

HOST="${EC_HOST:-ec}"          # ~/.ssh/config 里的别名
HERE="$(cd "$(dirname "$0")" && pwd)"
VENV=/opt/ec/venv
API_DIR=/opt/ec/api
DATA_DIR=/opt/ec/data

say() { printf '   %s\n' "$*"; }
head_() { printf '\n==> %s\n' "$*"; }

if [ "${1:-}" = "--stop" ]; then
  head_ "停掉接口（内容分发不受影响）"
  ssh "$HOST" 'systemctl disable --now ec-api.service 2>/dev/null || true;
               systemctl disable --now ec-backup.timer 2>/dev/null || true;
               systemctl is-active ec-api.service || echo "ec-api: 已停"'
  say "⚠️ nginx 里的 /api/ 反代还在，此时打它会得到 502（那是预期，不是故障）。"
  exit 0
fi

head_ "① 专用用户与数据目录"
ssh "$HOST" "
set -e
id -u ecapi >/dev/null 2>&1 || useradd --system --home-dir $API_DIR --shell /usr/sbin/nologin ecapi
mkdir -p $API_DIR $DATA_DIR $DATA_DIR/backup
# ⚠️ 数据目录 750 + 属 ecapi：这个库里是用户数据。同机的其它服务（nginx 以
#    www-data 跑、cycle.py 以 root 跑）都不该随手读到它。
chown -R ecapi:ecapi $DATA_DIR
chmod 750 $DATA_DIR $DATA_DIR/backup
id ecapi; ls -ld $DATA_DIR"
say "✅ ecapi 就位"

head_ "② 同步应用代码"
scp -q "$HERE/api/app.py" "$HERE/api/db.py" "$HERE/api/security.py" "$HOST:/tmp/"
scp -q "$HERE/bin/ec-admin" "$HERE/bin/backup_db.py" "$HOST:/tmp/"
ssh "$HOST" "
set -e
install -o root -g root -m 644 /tmp/app.py /tmp/db.py /tmp/security.py $API_DIR/
install -o root -g root -m 755 /tmp/ec-admin /tmp/backup_db.py /opt/ec/bin/
rm -f /tmp/app.py /tmp/db.py /tmp/security.py /tmp/ec-admin /tmp/backup_db.py
$VENV/bin/python -m py_compile $API_DIR/app.py $API_DIR/db.py $API_DIR/security.py
rm -rf $API_DIR/__pycache__
echo '   ✅ 三个模块语法通过'"
say "✅ 代码已同步到 $API_DIR"

head_ "③ 依赖（已装则跳过）"
if ssh "$HOST" "$VENV/bin/python -c 'import fastapi, uvicorn' 2>/dev/null"; then
  say "✅ fastapi / uvicorn 已经在 $VENV 里了"
else
  say "正在装……（这一步要打网络，几秒到几十秒）"
  ssh "$HOST" "$VENV/bin/pip install -q --disable-pip-version-check fastapi 'uvicorn[standard]'"
  ssh "$HOST" "$VENV/bin/python -c 'import fastapi, uvicorn; print(\"   ✅ fastapi\", fastapi.__version__, \"| uvicorn\", uvicorn.__version__)'"
fi

head_ "④ systemd 单元"
scp -q "$HERE/systemd/ec-api.service" "$HERE/systemd/ec-backup.service" \
       "$HERE/systemd/ec-backup.timer" "$HOST:/tmp/"
ssh "$HOST" "
set -e
install -m 644 /tmp/ec-api.service /etc/systemd/system/
install -m 644 /tmp/ec-backup.service /etc/systemd/system/
install -m 644 /tmp/ec-backup.timer /etc/systemd/system/
rm -f /tmp/ec-api.service /tmp/ec-backup.service /tmp/ec-backup.timer
systemctl daemon-reload
systemctl enable --now ec-backup.timer >/dev/null
systemctl enable ec-api.service >/dev/null
systemctl restart ec-api.service
sleep 1
systemctl is-active ec-api.service
systemctl is-active ec-backup.timer"

head_ "⑤ 体检"
ssh "$HOST" '
set -e
echo -n "   本机 /api/healthz → "
curl -s --max-time 8 http://127.0.0.1:8081/api/healthz || { echo "（没有响应）"; exit 1; }
echo
echo -n "   监听端口      → "; ss -tlnp 2>/dev/null | grep -c ":8081" || true
echo -n "   数据库        → "; ls -l /opt/ec/data/ec.db 2>/dev/null || echo "（还没建，第一次请求时才建）"
echo -n "   库权限        → "; stat -c "%U:%G %a" /opt/ec/data 2>/dev/null
echo "   备份定时器    → $(systemctl list-timers --all 2>/dev/null | grep ec-backup | head -1)"'

printf '\n✅ 接口已上线。域名侧还要确认一次：\n'
printf '     curl -s https://YOUR_DOMAIN/api/healthz\n'
printf '     curl -s -o /dev/null -w "%%{http_code}\\n" http://YOUR_SERVER_IP/api/healthz   # 期望 404\n'
printf '\n   看日志： ssh %s "journalctl -u ec-api -n 50 --no-pager"\n' "$HOST"
printf '   管账号： ssh %s "ec-admin list"\n' "$HOST"
