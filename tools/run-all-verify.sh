#!/usr/bin/env bash
#
# 一键跑完全部验证脚本，并打印「脚本 → 项数 → 退出码」总表。
#
# ## 为什么需要它（两个真实的坑）
#
# ① **解释器用错会得出「脚本坏了」的假结论。**
#    `testdata/verify_stage3.py` 与 `verify_streaming_resample.py` 依赖 PyAV，
#    而 `av` 只装在托管 venv 里，不在基础解释器里。一直用基础解释器跑 →
#    `ModuleNotFoundError: No module named 'av'` → 在 1.0.0 的文档里被记成
#    「两个早期脚本跑不起来，与本轮无关」。**其实两个都没坏，是跑法不对。**
#    所以这里把解释器选择固化下来：**优先托管 venv**。
#
# ② **「项数」只能抄脚本自己的输出。**
#    曾经把 `verify-source-marks.py` 的 11（源个数）当成断言数记进文档，
#    数字虚高会让人误判覆盖强度。这个脚本只负责把每行输出原样带出来，
#    不做任何再加工 —— 这样「记项数」就只剩「照抄」一个动作。
#
# 用法：
#     ./tools/run-all-verify.sh            # 全跑
#     ./tools/run-all-verify.sh tools      # 只跑 tools/
#     ./tools/run-all-verify.sh testdata   # 只跑 testdata/
#     ./tools/run-all-verify.sh server     # 只跑服务端自检
#
# ## ③ 服务端自检也要能在这里跑（2.2.0 加）
#
# 它们以前只在 `deploy.sh` 里跑 —— 也就是**必须部署到服务器**才验得到。
# 而 `verify_gate.py`（发布闸门）和两个 `--selftest` 都只用标准库 + 本地模块，
# 在笔记本上就能跑。把「验不了」当默认，实际效果是**改动时没人跑它们**。
set -uo pipefail
cd "$(dirname "$0")/.."

# 托管 venv 优先（它有 av 等第三方依赖）；没有就退回基础解释器
VENV=${PYTHON:-python3}
BASE=${PYTHON:-python3}
if [ -x "$VENV" ]; then
    PY="$VENV"
    PYNOTE="托管 venv（含 av 等依赖）"
else
    PY="$BASE"
    PYNOTE="基础解释器（⚠️ 缺 av，两个 testdata 脚本会被跳过）"
fi

# ── 需要**线上域名**才能跑的脚本：**故意不进下面这张表** ──────────────────
#
# `verify-tls-30.py` 的默认模式会去打 `https://YOUR_DOMAIN`（DNS 解析、证书链、
# TLS 版本、443 上的每一条路径、80 跳转、证书剩余天数……）。
#
# 它是**线上健康检查**，不是本地静态检查。放进这张「改完代码跑一遍」的表里，
# 只会让每一轮全量验证都多一条红 —— 然后那条红会被当成噪音习惯性忽略，
# 而它恰恰是最该被看见的一类（「服务端到底还活着吗」）。
#
# 它该被跑的两个地方：
#   · 上域名那一次：`--compare testdata/tls-30-baseline.json`（80 有没有被弄坏）
#   · 之后每次怀疑服务端时：直接 `python3 tools/verify-tls-30.py`
# 两者都写在 server/README.md 的「域名 + TLS」一节。
#
# ⚠️ 排除的判据是「**需要线上环境**」，不是「跑不过」。
#    如果哪天某个静态脚本也进了这个列表，那多半是它在掩盖一个真问题。
SKIP=("tools/verify-tls-30.py")

# 服务端的这三条要带参数，所以不走 glob
SERVER_SPECS=(
    "server/bin/verify_zh.py --selftest"
    "server/bin/verify_publish.py --selftest"
    "server/bin/verify_gate.py"
)

SCOPE="${1:-all}"
# ⚠️ 必须先声明成空数组。macOS 自带的是 bash 3.2，在 `set -u` 下
#    `"${GLOBS[@]}"` 对一个**完全没赋值**的数组会直接报
#    `GLOBS[@]: unbound variable` 并退出 —— 而 `server` 那一支正是这种情况。
#    （这里踩过一次：`./tools/run-all-verify.sh server` 一跑就炸。）
GLOBS=()
case "$SCOPE" in
    tools)    GLOBS=("tools/verify-*.py"); SERVER=0 ;;
    testdata) GLOBS=("testdata/verify_*.py"); SERVER=0 ;;
    server)   GLOBS=(); SERVER=1 ;;
    all)      GLOBS=("tools/verify-*.py" "testdata/verify_*.py"); SERVER=1 ;;
    *)        echo "用法：$0 [tools|testdata|server]" >&2; exit 2 ;;
esac

echo "解释器：$PY"
echo "         $PYNOTE"
echo

FAILED=0
ROWS=()

# ⚠️ 用 `${GLOBS[@]+"${GLOBS[@]}"}` 而不是 `"${GLOBS[@]}"`：
#    macOS 自带的 bash 3.2 在 `set -u` 下对**空数组**也会报 unbound variable
#    （bash 4.4 才修）。而 `server` 那一支的 GLOBS 本来就是空的 ——
#    写常见形式的话 `./tools/run-all-verify.sh server` 一跑就炸。
for g in ${GLOBS[@]+"${GLOBS[@]}"}; do
    for f in $g; do
        [ -f "$f" ] || continue
        case " ${SKIP[*]} " in *" $f "*) continue ;; esac
        out=$("$PY" "$f" 2>&1)
        code=$?
        # 原样取脚本自己报的那行项数；取不到就留空（**不猜**）
        line=$(printf '%s\n' "$out" | grep -oE '(全部通过：|通过 )[0-9]+ 项|全部 [0-9]+ 项通过' | head -1)
        [ -z "$line" ] && line="-"
        if [ "$code" -ne 0 ]; then
            FAILED=1
            mark="❌"
        else
            mark="✅"
        fi
        ROWS+=("$(printf '%s\t%s\t%s\t%s' "$mark" "$f" "$code" "$line")")
    done
done

if [ "$SERVER" -eq 1 ]; then
    for spec in "${SERVER_SPECS[@]}"; do
        f="${spec%% *}"
        args="${spec#"$f"}"; args="${args# }"
        [ -f "$f" ] || continue
        # 故意不加引号：`args` 要按空格拆成多个参数（那正是它存在的意义）
        out=$("$PY" "$f" $args 2>&1)
        code=$?
        line=$(printf '%s\n' "$out" | grep -oE '(全部通过：|通过 )[0-9]+ 项|全部 [0-9]+ 项通过' | head -1)
        [ -z "$line" ] && line="-"
        if [ "$code" -ne 0 ]; then
            FAILED=1
            mark="❌"
        else
            mark="✅"
        fi
        ROWS+=("$(printf '%s\t%s\t%s\t%s' "$mark" "$f $args" "$code" "$line")")
    done
    # selftest 会在 bin/ 下留 __pycache__，别让它进版本库
    rm -rf server/bin/__pycache__
fi

echo "================================================================"
printf '%-3s %-44s %-5s %s\n' "" "脚本" "退出" "脚本自报项数"
echo "----------------------------------------------------------------"
for r in "${ROWS[@]}"; do
    IFS=$'\t' read -r mark f code line <<<"$r"
    printf '%-3s %-44s %-5s %s\n' "$mark" "$f" "$code" "$line"
done
echo "================================================================"

# ---------------------------------------------------------------------------
# 附：JVM 单元测试
# ---------------------------------------------------------------------------
#
# ## 为什么它不并进上面那张表
#
# 那张表的每一行是「一个 python 脚本 + 退出码 + 脚本自报项数」。
# 这一条要拉起 gradle（首次十几秒），形态不同，硬塞进去会让那三列都不诚实。
#
# ## 为什么又必须在这里跑
#
# `word/Fsrs.kt` 是从官方 Python 实现（py-fsrs 6.3.2）**移植**来的。
# 移植的正确性只有 `FsrsTest` 一处在守 —— 而一个下标写错
# （`parameters[9]` 写成 `parameters[10]`）在代码里**看着完全对称、完全正确**，
# 靠读代码是发现不了的（这个坏样本实测能跑出 stability 10.96 vs 7.42 的偏差）。
#
# 本脚本是「改完跑一遍」的固定动作。单测不挂在这里，等于没跑。
if [ "$SCOPE" = "all" ] || [ "$SCOPE" = "tools" ]; then
    echo
    echo "==> JVM 单元测试（FSRS 移植正确性 + 词表加载）"
    export JAVA_HOME="${JAVA_HOME:-}"
    export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$HOME/.gradle}"
    export PATH="$JAVA_HOME/bin:$PATH"
    if ./gradlew :app:testDebugUnitTest --console=plain -q > /tmp/ec-unit-test.log 2>&1; then
        UNIT=$("$PY" - <<'PYEOF'
import glob
import xml.etree.ElementTree as ET
t = f = 0
for x in glob.glob('app/build/test-results/testDebugUnitTest/TEST-*.xml'):
    r = ET.parse(x).getroot()
    t += int(r.get('tests') or 0)
    f += int(r.get('failures') or 0) + int(r.get('errors') or 0)
print('%d 项，失败 %d' % (t, f))
PYEOF
)
        printf '%-3s %-44s %-5s %s\n' "✅" "gradlew :app:testDebugUnitTest" "0" "通过 $UNIT"
    else
        printf '%-3s %-44s %-5s %s\n' "❌" "gradlew :app:testDebugUnitTest" "1" "见 /tmp/ec-unit-test.log"
        tail -25 /tmp/ec-unit-test.log
        FAILED=$((FAILED+1))
    fi
fi

if [ "$FAILED" -ne 0 ]; then
    echo "❌ 有脚本未通过。看上面的退出码，逐个重跑定位："
    echo "   $PY <脚本路径>"
    exit 1
fi

echo "✅ 全部脚本退出码为 0"
echo
echo "本轮跳过（要线上域名才能跑，所以不在上表）：${SKIP[*]}"
echo "    要跑它：python3 tools/verify-tls-30.py"
echo
echo "注：有的脚本自己不报项数（例如 verify_stage3 / verify_url_normalizer 只打 OK），"
echo "    它们在上表里显示 '-'。**这不是 0 项**，只是没有计数输出 ——"
echo "    要加就给它补一行 '通过 N 项，失败 M 项'，别在文档里估。"
