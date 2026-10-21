#!/usr/bin/env bash
#
# 把一个构建好的 APK 发布成「App 内更新」能收到的新版本。
#
#   ./tools/publish-update.sh                     # 发布当前 build 出来的包
#   ./tools/publish-update.sh --force-min 45      # 同时开启强制更新（低于 45 的必须升）
#   ./tools/publish-update.sh --notes "修了字幕状态" --keep 5
#   ./tools/publish-update.sh --dry-run           # 只打印将要做什么
#
# ## 它做四件事，顺序不能变
#
#   ① 从 `app/build.gradle.kts` 读版本号（**唯一的真源**，不要去解析文件名）
#   ② 核对 `ServerConfig.BASE_URL` 与 `--base-url` 一致
#      —— 这两个是两个副本的同一份契约（客户端写死的那一行 / 清单里的 url），
#         对不上时客户端会去一个错地址下载，而症状只是「更新失败」
#
#         ⚠️ 3.1.0 起默认是 `https://YOUR_DOMAIN`。清单里的 url 是**绝对地址**，
#            会被所有版本的客户端读到 —— 包括 BASE_URL 还写着 http://IP 的老版本。
#            它们能下，因为 network_security_config 拒绝的是**明文**而不是 HTTPS
#            （详见 bin/publish_update.py 里 DEFAULT_BASE_URL 的注释）。
#
#         回退到 IP：加 `--base-url http://YOUR_SERVER_IP`。
#         只是换前缀、不重发包的话，服务器上有更快的路：
#            ssh YOUR_HOST '/opt/ec/venv/bin/python /opt/ec/bin/publish_update.py \
#                --retag-only --base-url http://YOUR_SERVER_IP'
#   ③ 把 APK 传上去，**在服务器上**算 sha256 并写 update.json
#      —— 必须服务器算：客户端校验的是**服务器上那个文件**的哈希
#   ④ 把 update.json 拉回来校验一遍（版本号 / 大小 / 哈希 / 可下载）
#
# 前三条各自都很容易漏，而任何一条漏了，症状都是「看着发布了、客户端却没反应」。
set -euo pipefail
cd "$(dirname "$0")/.."

HOST="${EC_HOST:-ec}"
GRADLE_KTS=app/build.gradle.kts
SERVER_CONFIG=app/src/main/java/com/example/englishcommute/net/ServerConfig.kt
WWW=/opt/ec/www
DEFAULT_BASE_URL=https://YOUR_DOMAIN

FORCE_MIN=0
NOTES=""
KEEP=3
DRY=""
BASE_URL="$DEFAULT_BASE_URL"

while [ $# -gt 0 ]; do
  case "$1" in
    --force-min) FORCE_MIN="$2"; shift 2 ;;
    --notes)     NOTES="$2"; shift 2 ;;
    --keep)      KEEP="$2"; shift 2 ;;
    --base-url)  BASE_URL="$2"; shift 2 ;;
    --dry-run)   DRY=1; shift ;;
    *) echo "未知参数：$1" >&2; exit 2 ;;
  esac
done

# ⚠️ 变量一律写成 ${X}：`$VAR` 后面紧跟中文/全角标点时，
#    bash 会把这个多字节字符也当成变量名的一部分 → unbound variable。
#    实测就栽在这一行（`$GRADLE_KTS）` 里的全角右括号）。
echo "== 1. 读版本号（唯一真源：${GRADLE_KTS}）=="
VER_CODE=$(sed -n 's/^val appVersionCode *= *\([0-9][0-9]*\).*/\1/p' "$GRADLE_KTS" | head -1)
VER_NAME=$(sed -n 's/^val appVersionName *= *"\([^"]*\)".*/\1/p' "$GRADLE_KTS" | head -1)
if [ -z "$VER_CODE" ] || [ -z "$VER_NAME" ]; then
  echo "   ❌ 没能从 $GRADLE_KTS 解析出版本号（变量名改过？）" >&2
  exit 1
fi
echo "   build $VER_CODE · v$VER_NAME"

APK="apk/EnglishCommute-${VER_NAME}-debug.apk"
if [ ! -f "$APK" ]; then
  echo "   ❌ 找不到 $APK —— 先跑 ./build-apk.sh" >&2
  exit 1
fi
LOCAL_SHA=$(shasum -a 256 "$APK" | awk '{print $1}')
LOCAL_SIZE=$(stat -f%z "$APK")
echo "   $APK  $((LOCAL_SIZE / 1048576)) MB  ${LOCAL_SHA:0:16}…"

echo
echo "== 2. 核对服务端地址（客户端写死的那一行 vs 清单里的 url）=="
SRC_URL=$(sed -n 's/.*const val BASE_URL: String *= *"\([^"]*\)".*/\1/p' "$SERVER_CONFIG" | head -1)
if [ -z "$SRC_URL" ]; then
  echo "   ❌ 没能从 $SERVER_CONFIG 解析出 BASE_URL" >&2
  exit 1
fi
if [ "$SRC_URL" != "$BASE_URL" ]; then
  # ⚠️ 这两个是同一份契约的两个副本。对不上时客户端会去一个错地址下载，
  #    而症状只有「更新一直失败」——查起来方向完全是错的。
  echo "   ❌ ServerConfig.BASE_URL = $SRC_URL" >&2
  echo "      而清单将用               $BASE_URL" >&2
  echo "      两边必须一致（要么改代码、要么传 --base-url）" >&2
  exit 1
fi
echo "   ✅ $SRC_URL"

if [ -n "$DRY" ]; then
  echo
  echo "（--dry-run）将要："
  echo "   scp $APK  → $HOST:/tmp/"
  echo "   在服务器上跑 publish_update.py --version-code $VER_CODE --version-name $VER_NAME \\"
  echo "        --min-supported $FORCE_MIN --keep $KEEP --base-url $BASE_URL"
  exit 0
fi

echo
echo "== 2.5 目标地址必须真的可达 =="
# ⚠️ 3.1.0 加的。原因很具体：清单里的 `url` 是**绝对地址**，而「从 IP 换成域名」
#    这件事第一次让它有可能指向一个**还不存在的入口**（DNS 没生效 / 证书没签 /
#    443 没起来 —— 这三件事各有各的失败方式，而且都不会在本地构建时报错）。
#
#    那种清单一旦发出去，症状是「App 说发现新版本、点更新一直失败」——
#    服务器上一切正常，日志里什么都没有，查起来完全没有方向。
#    与其等用户发现，不如在这里挡住。
HEALTH=$(curl -s -o /dev/null -w '%{http_code}' --max-time 15 "$BASE_URL/healthz" || true)
if [ "$HEALTH" != "200" ]; then
  echo "   ❌ $BASE_URL/healthz 返回 $HEALTH（期望 200）—— 别发布这个地址" >&2
  echo "      域名刚上时最常见的三种原因：DNS 没生效 / 证书没签 / 443 没起来。" >&2
  echo "      要么等它好，要么换一个可达的地址：--base-url \$OTHER" >&2
  exit 1
fi
echo "   ✅ $BASE_URL/healthz = 200（客户端照着这个地址能取到东西）"

echo
echo "== 3. 上传并在**服务器上**算哈希、写清单 =="
case "$NOTES" in
  *"'"*) echo "   ❌ --notes 里不能有单引号（会被 ssh 那层吃掉）" >&2; exit 1 ;;
esac
# 先传到 /tmp，再由服务器脚本拷进发布区 —— 「落地」和「算哈希」都在服务器上完成。
# `stat -f%z` 是 BSD/macOS 的写法：这个脚本是**本机**跑的（服务器那份是 update.json 的算法）。
scp -q "$APK" "$HOST:/tmp/ec-upload.apk"
ssh "$HOST" "/opt/ec/venv/bin/python /opt/ec/bin/publish_update.py /tmp/ec-upload.apk \
  --version-code $VER_CODE --version-name $VER_NAME --min-supported $FORCE_MIN \
  --keep $KEEP --notes '$NOTES' --base-url $BASE_URL"
ssh "$HOST" 'rm -f /tmp/ec-upload.apk'

echo
echo "== 4. 从客户端视角复核（拉回 update.json + 真下一个字节）=="
TMPIDX=$(mktemp)
curl -s --max-time 30 "$BASE_URL/apk/update.json" -o "$TMPIDX"
/Users/examplexu/.workbuddy/binaries/python/versions/3.13.12/bin/python3 - "$TMPIDX" "$VER_CODE" "$VER_NAME" "$LOCAL_SHA" "$LOCAL_SIZE" "$FORCE_MIN" <<'PY'
import json, sys, urllib.request, urllib.error
idx_path, code, name, sha, size, force_min = sys.argv[1:7]
idx = json.load(open(idx_path, encoding="utf-8"))
ok = True
def chk(label, cond, extra=""):
    global ok
    ok &= bool(cond)
    print(f"   {'✅' if cond else '❌'} {label}{('  ' + extra) if extra else ''}")

chk("versionCode 是数字且与构建一致", idx.get("versionCode") == int(code), str(idx.get("versionCode")))
chk("versionName 一致", idx.get("versionName") == name, str(idx.get("versionName")))
chk("sha256 与本地一致（服务器上那份没被传坏）", idx.get("sha256") == sha, idx.get("sha256", "")[:16])
chk("sizeBytes 与本地一致", idx.get("sizeBytes") == int(size), str(idx.get("sizeBytes")))
chk("minSupportedVersionCode 是数字", isinstance(idx.get("minSupportedVersionCode"), int),
    str(idx.get("minSupportedVersionCode")))
if int(force_min):
    chk("强制更新阈值与本次要求一致", idx.get("minSupportedVersionCode") == int(force_min))

# 真去下一个 Range 片段：证明这个 URL 能从公网取到、且支持断点续传
url = idx.get("url", "")
try:
    req = urllib.request.Request(url, headers={"Range": "bytes=0-1023"})
    with urllib.request.urlopen(req, timeout=30) as r:
        got = r.read()
        chk("URL 可下载且支持 Range（断点续传的前提）",
            r.status == 206 and len(got) == 1024, f"HTTP {r.status} {len(got)} B")
except urllib.error.HTTPError as e:
    chk("URL 可下载", False, f"HTTP {e.code}")
except Exception as e:
    chk("URL 可下载", False, repr(e)[:60])

print()
print("   ✅ 发布成功。App 下次回到前台就会看到新版本" if ok else "   ❌ 复核未通过，别在真机上试")
sys.exit(0 if ok else 1)
PY
rm -f "$TMPIDX"

echo
echo "强制更新怎么用："
echo "   下次发布时加 --force-min <版本号>，低于它的客户端会被挡住、必须升级。"
echo "   例：./tools/publish-update.sh --force-min $VER_CODE    # 从现在起低于 build $VER_CODE 的都要升"
