#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
0.13.0 倍速连续可调（0.05 精度 + 一键回常速）的桌面验证。

复刻对象：app/src/main/java/com/example/englishcommute/settings/SpeedPolicy.kt

**逐行对齐，不是「意思一下重写」。** 三个必须显式复刻的语义：

  1. `normalizePercent` 的对齐法是「加半步再整除」，不是向下取整。
     写成向下取整会让 77 落到 75 而不是最近的 75、78 落到 75 而不是 80 ——
     用户拖到哪就停在哪的**最近处**，这一点错了手感是黏的。
  2. Kotlin 的 `roundToInt()` = `Math.round()` = `floor(x + 0.5)`，
     **不是** Python 的 `round()`（后者是银行家舍入：round(2.5) == 2）。
  3. Kotlin 的 `speed * 100f` 是 **Float（32 位）** 乘法。
     这里用 struct 模拟 float32，避免 double 在边界上给出不同的结果。

为什么值得搬到桌面跑：倍速出问题**全是静默的** —— 显示成 `1.3500001x`、
两个不同档位显示成同一个字符串、精听反而比正常播放更快。
真机上没有人会盯着数字逐档比对，而这三种错都不会有任何异常抛出。
"""

import math
import re
import struct
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
JAVA = ROOT / "app/src/main/java/com/example/englishcommute"

PASS = 0
FAIL = 0
GROUP = ""


def group(name):
    global GROUP
    GROUP = name
    print(f"\n── {name} ──")


def check(name, got, want):
    global PASS, FAIL
    if got == want:
        PASS += 1
        return True
    FAIL += 1
    print(f"  ✗ [{GROUP}] {name}\n      得到 {got!r}\n      期望 {want!r}")
    return False


def check_true(name, cond):
    return check(name, bool(cond), True)


def strip_comments(src):
    """剥掉 Kotlin 注释。

    本工程的注释里到处写着「不要用 XXX」「以前是这么写的」这类反面教材，
    直接对全文做断言会把注释本身判成违规（`resolveActivity` 那次就是这么踩的）。
    """
    src = re.sub(r"/\*.*?\*/", "", src, flags=re.S)
    out = []
    for line in src.splitlines():
        idx = line.find("//")
        out.append(line[:idx] if idx >= 0 else line)
    return "\n".join(out)


# ---------------------------------------------------------------------------
# SpeedPolicy 的逐行复刻
# ---------------------------------------------------------------------------

PERCENT_MIN = 50
PERCENT_MAX = 200
PERCENT_STEP = 5
PERCENT_DEFAULT = 100
PERCENT_SLOW = 70

STEP_COUNT = (PERCENT_MAX - PERCENT_MIN) // PERCENT_STEP + 1
SLIDER_STEPS = STEP_COUNT - 2


def f32(x):
    """把一个 Python float 折成 float32 再取回，用来模拟 Kotlin 的 Float 运算"""
    return struct.unpack("<f", struct.pack("<f", float(x)))[0]


def kotlin_round_to_int(x):
    """kotlin.math.roundToInt = Math.round = floor(x + 0.5)"""
    return math.floor(x + 0.5)


def normalize_percent(percent):
    clamped = min(max(int(percent), PERCENT_MIN), PERCENT_MAX)
    offset = clamped - PERCENT_MIN
    aligned = ((offset + PERCENT_STEP // 2) // PERCENT_STEP) * PERCENT_STEP
    return min(max(PERCENT_MIN + aligned, PERCENT_MIN), PERCENT_MAX)


def percent_of(speed):
    s = f32(speed)
    if not math.isfinite(s):
        return PERCENT_DEFAULT
    return normalize_percent(kotlin_round_to_int(f32(s * f32(100.0))))


def speed_of(percent):
    return f32(normalize_percent(percent) / f32(100.0))


def step(percent, delta):
    return normalize_percent(normalize_percent(percent) + delta * PERCENT_STEP)


def is_default(percent):
    return normalize_percent(percent) == PERCENT_DEFAULT


def slow_percent_for(user_percent):
    return min(normalize_percent(user_percent), PERCENT_SLOW)


def fmt(percent):
    p = normalize_percent(percent)
    whole = p // 100
    frac = p % 100
    if frac % 10 == 0:
        return f"{whole}.{frac // 10}x"
    return f"{whole}.{frac:02d}x"


ALL_PERCENTS = list(range(PERCENT_MIN, PERCENT_MAX + 1, PERCENT_STEP))


# ---------------------------------------------------------------------------
# 1. 常量
# ---------------------------------------------------------------------------

group("常量")

check("档位总数", STEP_COUNT, 31)
check("滑杆 steps", SLIDER_STEPS, 29)
check("首档", ALL_PERCENTS[0], 50)
check("末档", ALL_PERCENTS[-1], 200)
check("档位个数与 STEP_COUNT 一致", len(ALL_PERCENTS), STEP_COUNT)


# ---------------------------------------------------------------------------
# 2. 归一化与对齐
# ---------------------------------------------------------------------------

group("归一化与对齐")

# 边界夹取
check("低于下限被夹取", normalize_percent(49), 50)
check("远低于下限被夹取", normalize_percent(-999), 50)
check("高于上限被夹取", normalize_percent(201), 200)
check("远高于上限被夹取", normalize_percent(99999), 200)

# 「加半步再整除」的核心：77 落 75、78 落 80
check("77 落到 75（不是 80）", normalize_percent(77), 75)
check("78 落到 80（不是 75）", normalize_percent(78), 80)
check("52 落到 50", normalize_percent(52), 50)
check("53 落到 55", normalize_percent(53), 55)
check("恰好在中点偏下", normalize_percent(52), 50)
check("恰好在中点偏上", normalize_percent(53), 55)

# 已对齐的值不能再动
for p in ALL_PERCENTS:
    if normalize_percent(p) != p:
        check(f"已对齐档位 {p} 被改动", normalize_percent(p), p)

# 幂等：归一化两次与一次相同
for probe in range(45, 210):
    once = normalize_percent(probe)
    if normalize_percent(once) != once:
        check(f"归一化不幂等：{probe}", normalize_percent(once), once)

# 结果永远落在合法档位上
for probe in range(-50, 260):
    got = normalize_percent(probe)
    if got not in ALL_PERCENTS:
        check(f"归一化产生了非法档位：{probe}", got, "应为 5 的倍数且在 50..200")


# ---------------------------------------------------------------------------
# 3. 格式化：31 档必须两两不同
# ---------------------------------------------------------------------------

group("格式化")

# 组这一条是整组里最要紧的：`1.05x` 不补零会变成 `1.5x`，
# 而 1.50x 是真实存在的另一个档位 —— 两个档位显示成同一个字符串，
# 用户会以为「调到 105 却显示 1.5」「调到 150 也显示 1.5」，怎么调都撞车。
labels = [fmt(p) for p in ALL_PERCENTS]
check("31 档标签互不相同", len(set(labels)), len(ALL_PERCENTS))

check("最慢档", fmt(50), "0.5x")
check("0.55 档", fmt(55), "0.55x")
check("0.6 档", fmt(60), "0.6x")
check("0.7 档（精听档）", fmt(70), "0.7x")
check("0.75 档（旧档位）", fmt(75), "0.75x")
check("常速", fmt(100), "1.0x")
check("1.05 档必须补零", fmt(105), "1.05x")
check("1.35 档", fmt(135), "1.35x")
check("1.5 档（旧档位）", fmt(150), "1.5x")
check("最快档", fmt(200), "2.0x")

# 常速必须带一位小数：`1x` 和最慢档 `0.5x` 并排时会像两种不同的东西
check_true("常速带小数位", "1.0x" == fmt(100))

# 非法输入先被归一化再格式化，不能抛异常或输出空串
check("非法输入格式化退到下限", fmt(-1), "0.5x")
check("超上限格式化退到上限", fmt(999), "2.0x")
check_true("格式化结果都以 x 结尾", all(s.endswith("x") for s in labels + [fmt(-1), fmt(999)]))


# ---------------------------------------------------------------------------
# 4. Float 往返
# ---------------------------------------------------------------------------

group("Float 往返")

for p in ALL_PERCENTS:
    back = percent_of(speed_of(p))
    if back != p:
        check(f"档位往返失真 {p}", back, p)

check("0.75 → 75", percent_of(0.75), 75)
check("0.7 → 70", percent_of(0.7), 70)
check("1.0 → 100", percent_of(1.0), 100)
check("1.35 → 135", percent_of(1.35), 135)
check("1.05 → 105", percent_of(1.05), 105)
check("2.0 → 200", percent_of(2.0), 200)
check("0.5 → 50", percent_of(0.5), 50)

# 老版本存下的固定档位必须原样读出（那时只有 0.5/0.75/1.0/1.25/1.5/2.0）
for old in (0.5, 0.75, 1.0, 1.25, 1.5, 2.0):
    check(f"老档位 {old} 保留", percent_of(old), int(round(old * 100)))

# 坐标轴之外的值（老版本理论上不会存，但不能因此崩）
check("NaN 回落常速", percent_of(float("nan")), PERCENT_DEFAULT)
check("正无穷回落常速", percent_of(float("inf")), PERCENT_DEFAULT)
check("负无穷回落常速", percent_of(float("-inf")), PERCENT_DEFAULT)
check("0.0 被抬到下限", percent_of(0.0), PERCENT_MIN)
check("99.0 被压到上限", percent_of(99.0), PERCENT_MAX)


# ---------------------------------------------------------------------------
# 5. ± 微调
# ---------------------------------------------------------------------------

group("± 微调")

check("常速 +1 档", step(100, 1), 105)
check("常速 -1 档", step(100, -1), 95)
check("105 再 -1 回 100", step(105, -1), 100)

# 到边界停住，绝不循环 —— 从 2.0x 按「+」绕回 0.5x 是惊悚体验
check("下限再减不动", step(PERCENT_MIN, -1), PERCENT_MIN)
check("上限再加不动", step(PERCENT_MAX, 1), PERCENT_MAX)
check("195 +1 到顶", step(195, 1), PERCENT_MAX)
check("55 -1 到底", step(55, -1), PERCENT_MIN)

# 连续 +1 走满 31 档，不会漏格也不会越界
cur = PERCENT_MIN
seen = [cur]
for _ in range(STEP_COUNT - 1):
    cur = step(cur, 1)
    seen.append(cur)
check("连按 30 次 + 走满全程", seen, ALL_PERCENTS)
check("之后恒为上限", step(cur, 1), PERCENT_MAX)

# 非法起点先归一化
check("非法起点 -1 档", step(77, -1), 70)
check("非法起点 +1 档", step(77, 1), 80)


# ---------------------------------------------------------------------------
# 6. 精听降速
# ---------------------------------------------------------------------------

group("精听降速")

# 这条是修出来的：原先精听硬编码 0.7x，用户把正常倍速调到 0.5x 之后
# 点「慢速重听」反而**变快** —— 而按钮叫「慢速重听」，自相矛盾
check("1.5x 精听降到 0.7x", slow_percent_for(150), 70)
check("1.0x 精听降到 0.7x", slow_percent_for(100), 70)
check("0.7x 精听保持 0.7x", slow_percent_for(70), 70)
check("0.65x 精听用 0.65x（不加快）", slow_percent_for(65), 65)
check("0.5x 精听保持 0.5x（不加快）", slow_percent_for(50), 50)
check("下限处也不会变快", slow_percent_for(PERCENT_MIN), PERCENT_MIN)

for p in ALL_PERCENTS:
    slow = slow_percent_for(p)
    if slow > p:
        check(f"精听比正常快！用户设定 {p}", slow, f"应 <= {p}")

check_true("精听永不快于常速基准", all(slow_percent_for(p) <= max(p, PERCENT_SLOW) for p in ALL_PERCENTS))


# ---------------------------------------------------------------------------
# 7. 常速判定
# ---------------------------------------------------------------------------

group("常速判定")

check_true("100 是常速", is_default(100))
check_true("100.4 归一化后仍是常速", is_default(100))
check_true("75 不是常速", not is_default(75))
check_true("105 不是常速", not is_default(105))
check_true("非法值 77 不是常速", not is_default(77))


# ---------------------------------------------------------------------------
# 8. 源码断言
# ---------------------------------------------------------------------------

group("源码断言")

policy_src = (JAVA / "settings/SpeedPolicy.kt").read_text(encoding="utf-8")
policy = strip_comments(policy_src)
check_true("SpeedPolicy 存在 normalizePercent", "fun normalizePercent" in policy)
check_true("SpeedPolicy 存在 percentOf", "fun percentOf" in policy)
check_true("SpeedPolicy 存在 slowPercentFor", "fun slowPercentFor" in policy)
check_true("SLIDER_STEPS 用减 2（Compose 语义）", "STEP_COUNT - 2" in policy)
check_true("没有用到 abs（对齐不用绝对值）", "abs(" not in policy)

vm = strip_comments((JAVA / "ui/PlayerViewModel.kt").read_text(encoding="utf-8"))
check_true("PlayerViewModel 有 setSpeedPercent", "fun setSpeedPercent" in vm)
check_true("PlayerViewModel 有 resetSpeed", "fun resetSpeed" in vm)
check_true("resetSpeed 指向常速", "resetSpeed() = applySpeed(SpeedPolicy.PERCENT_DEFAULT" in vm)
check_true("倍速落盘走防抖 job", "speedPersistJob" in vm)
check_true("防抖里有 delay", "delay(speedPersistDelayMs)" in vm)
check_true("resetSpeed 立刻落盘", "immediate = true" in vm)
check_true("归一化在 ViewModel 里也做了一次", "SpeedPolicy.normalizePercent(percent)" in vm)
check_true("精听走 slowPercentFor", "SpeedPolicy.slowPercentFor" in vm)
# 倍速状态必须是整百分数，不能退回 Float
check_true("状态字段是 speedPercent", "val speedPercent: Int" in vm)
check_true("状态里没有 Float 的 speed 字段", "val speed: Float" not in vm)
check_true("没有裸 Float 的 setSpeed(Float)", "fun setSpeed(speed: Float)" not in vm)
check_true("没有旧的 Float 读接口", "settings.playbackSpeed." not in vm)

screen = strip_comments((JAVA / "ui/PlayerScreen.kt").read_text(encoding="utf-8"))
# ---------------------------------------------------------------------------
# 1.2.0：倍速交互从「滑杆 + ± + 回到1.0x 三件套占两整排」压缩成一排
# ---------------------------------------------------------------------------
#
# 这是一次**有意的设计变更**，不是实现漂移，所以下面这组断言整体改写过。
# 变更的理由（用户要求把控制区压缩、给字幕让出空间）：
#
#   · 滑杆的价值是「大范围移动」，但倍速实际是围绕当前值微调的量。
#     0.05 一格在全宽滑杆上只占约 3%，手指很难准确停住 —— 真正在用的一直是 ±。
#   · 「回到 1.0x」原来是独立 chip 占半排，现在**数值本身**就是那个按钮，
#     而且它天然在两个 ± 中间，是最好够到的位置。
#
# ⚠️ 被删掉的只是**交互形式**。下面这些核心契约一条都不能少，
#    所以这里反而要把它们查得更严：31 个档位、0.05 步长、边界不循环、
#    归一化仍然走 SpeedPolicy。
check_true("不再引用固定档位表", "SPEED_STEPS" not in screen)
check_true("旧的 SpeedChip 已删除", "fun SpeedChip" not in screen)
check_true("有 ± 微调组件", "fun SpeedStepButton" in screen)
check_true("± 走 SpeedPolicy.step（不自己加减 5）",
           "SpeedPolicy.step(ui.speedPercent, -1)" in screen
           and "SpeedPolicy.step(ui.speedPercent, 1)" in screen)
# 循环会让用户在 2.0x 再按一下掉回 0.5x —— 那是听力材料里最糟的意外
check_true("± 在边界置灰，不循环",
           "SpeedPolicy.isAtMin(ui.speedPercent)" in screen
           and "SpeedPolicy.isAtMax(ui.speedPercent)" in screen)
check_true("倍速数值用 SpeedPolicy.format（补零，1.05x 不会显示成 1.5x）",
           "SpeedPolicy.format(ui.speedPercent)" in screen)

# 「回到常速」这个能力必须还在，只是换了载体
# 其余档位都是相对的，只有 1.0x 是绝对参照点
check_true("仍然可以一步回到常速", "onClick = onResetSpeed" in screen)
check_true("  · 载体是倍速数值本身（不再占一个独立 chip）",
           "val offDefault = !SpeedPolicy.isDefault(ui.speedPercent)" in screen
           and "pressFeedback(enabled = offDefault, onClick = onResetSpeed)" in screen)
check_true("  · 已在常速时不可点，但**不消失**（消失会让布局跳变）",
           "if (offDefault) Amber else InkDim" in screen)

# 滑杆连同它的本地拖动状态一起删干净了 —— 留着会是一组没人用的死状态
check_true("倍速滑杆已移除", "steps = SpeedPolicy.SLIDER_STEPS" not in screen)
check_true("  · 配套的拖动状态也删干净了（不留死代码）",
           "speedDragging" not in screen and "speedDragPercent" not in screen)

settings = strip_comments((JAVA / "settings/AppSettings.kt").read_text(encoding="utf-8"))
check_true("设置对外是 Int 百分数", "val playbackSpeedPercent: Flow<Int>" in settings)
check_true("写设置前归一化", "SpeedPolicy.speedOf(percent)" in settings)
check_true("读设置时归一化", "SpeedPolicy.percentOf(" in settings)
check_true("固定档位表已移除", "val SPEED_STEPS" not in settings)
check_true("旧的 Float 写接口已移除", "suspend fun setPlaybackSpeed(speed: Float)" not in settings)

# 磁盘 key 不能动：换 key 等于老用户存下的 0.75 读不出来、静默回到 1.0x
check_true("沿用原 KEY_SPEED（不做数据迁移）", 'floatPreferencesKey("playback_speed")' in settings)


# ---------------------------------------------------------------------------
print(f"\n{'=' * 62}")
print(f"通过 {PASS} 项，失败 {FAIL} 项")
print("=" * 62)
sys.exit(1 if FAIL else 0)
