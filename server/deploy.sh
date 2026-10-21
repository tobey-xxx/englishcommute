#!/usr/bin/env bash
# 把本地的服务端代码同步到腾讯云轻量服务器，并做上传后体检。
#
#   ./deploy.sh            同步 + 语法检查 + 核验
#   ./deploy.sh --nginx-only   只装 nginx/systemd 配置，不动 bin/（见下面说明）
#   ./deploy.sh --dry-run  只看会改哪些文件
#
# 为什么要有它：这些脚本**真正运行的地方在服务器上**，而人在这里改。
# 没有一键同步，两边很快就不一致 —— 那时「本地看着对、线上是旧的」
# 会变成一个查不出来源的怪问题（本工程的 Android 侧为此吃过一次亏：
# 报 bug 的人装的其实是上一版）。
#
# ⚠️ 转写脚本 `chunked_transcribe.py` 的家在 skill 里
#    （`~/.workbuddy/skills/podcast-transcribe-srt/scripts/`），
#    这里是**从 skill 取过来一起发**，不要在 bin/ 里单独改它。
set -euo pipefail

HOST="${EC_HOST:-ec}"          # ~/.ssh/config 里的别名
BIN=/opt/ec/bin
HERE="$(cd "$(dirname "$0")" && pwd)"
ASR_SRC="$HOME/.workbuddy/skills/podcast-transcribe-srt/scripts/chunked_transcribe.py"

# ── --nginx-only：只装配置，**不同步 bin/** ────────────────────────────────
#
# 3.1.0 上域名必须分两步走，而两步之间隔着一个窗口：
#
#     ① 装「纯 IP + /vocab 收口」的配置 —— 与域名无关，随时可做
#     ② 签证书 → 装域名入口（80 跳转 + 443 TLS）
#
# 如果第 ① 步用完整 deploy，它会连带把 bin/ 同步过去，而 bin/cycle.py 里
# 带着域名基址 —— 定时器**最多 30 分钟**就会把 index.json 的 audioUrl
# 写成 https://YOUR_DOMAIN/...。那一刻 443 还没起来，客户端就是
# 「有字幕、有封面、点下去没声音」，而且**不会自己恢复**。
#
# 所以第 ① 步走这个开关。
if [ "${1:-}" = "--nginx-only" ]; then
  exec "$HERE/install-nginx.sh"
fi

DRY=""
[ "${1:-}" = "--dry-run" ] && DRY="--dry-run"

echo "== 1. 同步 bin/ → $HOST:$BIN =="
# 用 tar 走 ssh，而不是 rsync：**服务器上没装 rsync**，而为了同步一个 100 KB 的目录
# 去 apt 装一个包不值得 —— 这台机器每多一样东西都要算账（2 GB 内存）。
if [ -n "$DRY" ]; then
  echo "（--dry-run）将要发送的文件："
  (cd "$HERE/bin" && find . -type f | sed 's|^\./|   |')
  echo "（--dry-run 到此为止）"
  exit 0
fi
# --no-xattrs：macOS 给每个文件都带一个 com.apple.provenance 扩展属性，
# 打进包里会让 Linux 端刷一屏 "Ignoring unknown extended header keyword"。
# 内容本来就对，但噪音会掩盖真正的告警 —— 所以宁可加这个开关。
COPYFILE_DISABLE=1 tar --no-xattrs -czf - -C "$HERE/bin" . \
  | ssh "$HOST" "mkdir -p $BIN && tar xzf - -C $BIN"
echo "   已同步 $(find "$HERE/bin" -type f | wc -l | tr -d ' ') 个文件"

echo
echo "== 2. 从 skill 取转写脚本 =="
if [ -f "$ASR_SRC" ]; then
  scp -q "$ASR_SRC" "$HOST:$BIN/chunked_transcribe.py"
  echo "   ✅ $(basename "$ASR_SRC")"
else
  echo "   ❌ 找不到 $ASR_SRC —— 转写脚本不会被更新"
  exit 1
fi

echo
echo "== 3. 安装站点配置与定时器（2.1.9 起纳入版本管理）=="
# 3.1.0 起这一段拆进了 install-nginx.sh —— 因为上域名要求「先装配置、后同步代码」，
# 而本脚本默认是两件事一起做（原因见文件开头 --nginx-only 的说明）。
"$HERE/install-nginx.sh"

echo
echo "== 4. 网络调优（BBR + 缓冲区，2.2.0）=="
# 为什么放在部署脚本里：真机实测「挂 VPN 快、用移动流量慢」——
# 瓶颈在国内到香港的国际出口（丢包/抖动），而 Linux 默认的 cubic 会把随机丢包
# 当成拥塞信号、收缩发送窗口。BBR 是这类链路上投入产出比最高的一项。
# 详见 sysctl/99-ec-net.conf 的注释。
scp -q "$HERE/sysctl/99-ec-net.conf" "$HOST:/tmp/99-ec-net.conf"
scp -q "$HERE/sysctl/ec-modules.conf" "$HOST:/tmp/ec-modules.conf"
ssh "$HOST" '
set -e
# ⚠️ 顺序不能反：**先**加载模块，**再**写 tcp_congestion_control。
#    tcp_bbr 在 Debian 上是模块，模块不在时那条 sysctl 会静默失败
#    （`sysctl --system` 只在 stderr 上抱怨一句），结果就是「配了但没生效」。
install -m 644 /tmp/ec-modules.conf /etc/modules-load.d/ec-net.conf
modprobe tcp_bbr
install -m 644 /tmp/99-ec-net.conf /etc/sysctl.d/99-ec-net.conf
# --system 会读所有 sysctl.d；把输出收起来，只在真失败时报
sysctl --system >/dev/null 2>&1 || true
cur=$(sysctl -n net.ipv4.tcp_congestion_control)
qdisc=$(sysctl -n net.core.default_qdisc)
rmem=$(sysctl -n net.core.rmem_max)
echo "   拥塞控制 = $cur | 队列规则 = $qdisc | rmem_max = $rmem"
if [ "$cur" = "bbr" ] && [ "$qdisc" = "fq" ]; then
  echo "   ✅ BBR 已生效"
else
  echo "   ❌ 期望 bbr/fq，实际 $cur/$qdisc —— 这条链路不会被优化"
  exit 1
fi
rm -f /tmp/99-ec-net.conf /tmp/ec-modules.conf
'

echo
echo "== 5. 语法检查（上传后立刻做，不留坏文件在线上）=="
ssh "$HOST" 'chmod +x /opt/ec/bin/*.py /opt/ec/bin/*.sh 2>/dev/null; rc=0; for f in /opt/ec/bin/*.py; do
  /opt/ec/venv/bin/python -m py_compile "$f" || { echo "   ❌ $(basename $f)"; rc=1; }
done
# ⚠️ .sh 也要查：`bash -n` 只做语法解析、不执行任何东西。
#    没有这一句的话，一个少写的引号会一路活到有人手跑它的那一刻 ——
#    而 setup-tls.sh 恰好是「出事时才第一次跑」的那类脚本。
for f in /opt/ec/bin/*.sh; do
  bash -n "$f" || { echo "   ❌ $(basename $f)"; rc=1; }
done
rm -rf /opt/ec/bin/__pycache__; [ $rc -eq 0 ] && echo "   ✅ 全部脚本语法通过"; exit $rc'

echo
echo "== 6. 自检与核验 =="
ssh "$HOST" '/opt/ec/venv/bin/python /opt/ec/bin/verify_zh.py --selftest | tail -2
/opt/ec/venv/bin/python /opt/ec/bin/verify_publish.py --selftest | tail -2
echo "--- 发布闸门（直接跑 cycle.publish）---"
/opt/ec/venv/bin/python /opt/ec/bin/verify_gate.py | tail -3
echo "--- 更新清单（App 内更新靠它）---"
/opt/ec/venv/bin/python /opt/ec/bin/publish_update.py --selftest | tail -2
echo "--- 真实产出 ---"
# ⚠️ 有一轮 cycle 正在跑时，下面这两条**可能报出暂态问题**：
#    正常顺序是「先把字幕/音频写进发布区、最后一次才 publish」，
#    中间那几十分钟里文件与清单本来就不一致。这不是缺陷。
#    （孤儿文件那一类已经在 verify_publish 里收窄掉了；字幕与译文的
#     句段比对无法收窄 —— 它看到的是正在被改写的那一份。）
if [ -e /opt/ec/data/state/cycle.lock ]; then
  echo "⚠️ 有一轮 cycle 正在跑（$(cat /opt/ec/data/state/cycle.lock 2>/dev/null)）——"
  echo "   下面的「真实产出」可能报出**暂态**问题。等它跑完再复核："
  echo "   ssh <host> /opt/ec/venv/bin/python /opt/ec/bin/verify_publish.py"
fi
/opt/ec/venv/bin/python /opt/ec/bin/verify_zh.py --require-all | tail -3
/opt/ec/venv/bin/python /opt/ec/bin/verify_publish.py | tail -3
rm -rf /opt/ec/bin/__pycache__'

echo
echo "✅ 部署完成。跑一轮看效果：ssh $HOST '/opt/ec/venv/bin/python /opt/ec/bin/cycle.py --translate-only'"
echo "   存量补做（旧字幕与音频对不上，需重下重转）："
echo "   ssh $HOST '/opt/ec/venv/bin/python /opt/ec/bin/cycle.py --rehost --rehost-limit 6 --no-translate'"
