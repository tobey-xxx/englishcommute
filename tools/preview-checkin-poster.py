#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
打卡海报的**版式镜像预览** —— 用来在装机之前把海报真看一遍。

## 它是干什么的

海报由 `checkin/CheckInPoster.kt` 用 Android 的 `Canvas` 绘制，而 Canvas
只能在设备/模拟器上跑。这意味着「海报长什么样」在装机前是完全不可见的 ——
而它偏偏是这个 App 里唯一**会被发到微信里、被外人看到**的输出，
版式一旦出问题（文字重叠、数字贴边、格子错位），代价比其他界面大得多。

所以这里用 PIL 把**同一套坐标常量**重画一遍，输出 PNG 供肉眼核对。

## ⚠️ 权威在 Kotlin 侧

**任何版式改动都必须先改 `CheckInPoster.kt`，然后同步到这里。**
这个脚本只保证「坐标取值相同」，不保证渲染完全一致 —— PIL 与 Android
在字体度量、字距、抗锯齿上有细微差别（比如 PIL 不能设 letterSpacing，
这里是用逐字绘制近似出来的）。它的用途是**发现明显的版式错误**，
不是逐像素比对。

用法：
    python3 tools/preview-checkin-poster.py [输出路径]
"""

from __future__ import annotations

import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

# ---------------------------------------------------------------------------
# 与 CheckInPoster.kt 一一对应的常量
# ---------------------------------------------------------------------------

W, H = 1080, 1440
MARGIN = 96

PAPER = (0xFB, 0xFA, 0xF7)
INK = (0x16, 0x18, 0x1C)
INK_MID = (0x3E, 0x42, 0x49)
INK_DIM = (0x6B, 0x6F, 0x77)
INK_FAINT = (0x8F, 0x8B, 0x82)
AMBER = (0x9C, 0x5F, 0x14)
AMBER_SOFT = (0xD9, 0xA0, 0x5B)
HAIRLINE = (0xE6, 0xE1, 0xD5)

GOAL_MINUTES = 40
RECENT_DAYS = 7

# macOS 上可用的替代字体。Android 侧用的是系统默认 sans / serif，
# 这里只是尽量贴近，不追求一致
CJK_SANS = "/System/Library/Fonts/Hiragino Sans GB.ttc"
SERIF_BOLD = "/System/Library/Fonts/Supplemental/Times New Roman Bold.ttf"
SERIF = "/System/Library/Fonts/Supplemental/Times New Roman.ttf"


def load_font(path: str, size: int) -> ImageFont.FreeTypeFont:
    try:
        return ImageFont.truetype(path, size)
    except OSError:
        return ImageFont.load_default()


def draw_spaced(draw: ImageDraw.ImageDraw, xy, text, font, fill, spacing_px):
    """PIL 没有 letterSpacing，逐字画一遍近似它（Kotlin 侧用的是 Paint.letterSpacing）"""
    x, y = xy
    for ch in text:
        draw.text((x, y), ch, font=font, fill=fill, anchor="lm")
        x += draw.textlength(ch, font=font) + spacing_px
    return x


def flame_outline(cx: float, top: float, half_w: float, h: float):
    """
    火苗轮廓。

    试错记录（两版都失败过，别走回头路）：
      1. 两条贝塞尔对称收在同一个点 → 上尖下尖、中间鼓，那是**水滴**；
      2. 加一个居中的大内焰挖空 → 变成**甜甜圈**。

    真正让形状读作「火」的是两件事：
      · 尖**偏右**（火焰被气流吹偏），底部圆、宽度峰值靠近底部；
      · 左侧有一个**内凹的缺口**（火舌卷回来的那一瓣）—— 这是水滴和火的
        唯一结构性差别，加上它形状立刻就对了。
    """
    def P(px, py):
        return (cx + px * half_w, top + py * h)

    tip = P(0.10, 0.00)
    right_bottom = P(0.58, 1.00)
    left_bottom = P(-0.58, 1.00)
    dent = P(-0.40, 0.54)          # 左侧缺口：整个形状的「火舌」

    def bezier(p0, p1, p2, p3, steps=90):
        out = []
        for i in range(steps + 1):
            t = i / steps
            mt = 1 - t
            x = (mt ** 3) * p0[0] + 3 * (mt ** 2) * t * p1[0] + 3 * mt * (t ** 2) * p2[0] + (t ** 3) * p3[0]
            y = (mt ** 3) * p0[1] + 3 * (mt ** 2) * t * p1[1] + 3 * mt * (t ** 2) * p2[1] + (t ** 3) * p3[1]
            out.append((x, y))
        return out

    pts = bezier(tip, P(0.70, 0.26), P(0.98, 0.62), right_bottom)
    pts += bezier(right_bottom, P(0.34, 1.26), P(-0.34, 1.26), left_bottom)
    pts += bezier(left_bottom, P(-0.88, 0.74), P(-0.70, 0.50), dent)
    pts += bezier(dent, P(-0.62, 0.20), P(-0.28, 0.07), tip)
    return pts


def draw_flame(draw: ImageDraw.ImageDraw, cx: float, top: float, half_w: float, h: float,
               outer, inner):
    """外层实心 + 一个**小得多**的内焰（第一版内焰太大，整块变成了环）"""
    draw.polygon(flame_outline(cx, top, half_w, h), fill=outer)
    draw.polygon(
        flame_outline(cx + half_w * 0.10, top + h * 0.44, half_w * 0.26, h * 0.34),
        fill=inner,
    )


def render(out_path: Path, streak: int = 21, today_seconds: int = 2460,
           recent_done=None, date_label: str = "9月20日", weekday: str = "周六"):
    if recent_done is None:
        # 故意留一格断掉，验证「看得出断没断」
        recent_done = [True, True, False, True, True, True, True]

    img = Image.new("RGB", (W, H), PAPER)
    d = ImageDraw.Draw(img)

    f_label = load_font(CJK_SANS, 40)
    f_date = load_font(CJK_SANS, 36)
    f_hero = load_font(SERIF_BOLD, 320)
    f_streak = load_font(CJK_SANS, 50)
    f_sub = load_font(CJK_SANS, 42)
    f_recent = load_font(CJK_SANS, 30)
    f_foot1 = load_font(CJK_SANS, 38)

    # ---- 页眉 ----
    draw_spaced(d, (MARGIN, 140), "通勤英语 · 每日打卡", f_label, INK_DIM, 7)
    d.text((W - MARGIN, 140), f"{date_label} {weekday}", font=f_date, fill=INK_FAINT, anchor="rm")
    d.rectangle([MARGIN, 178, W - MARGIN, 180], fill=HAIRLINE)

    # ---- 主角：连续天数 ----
    #
    # 原本这里画一个火苗（多邻国的标志）。试了三版都不对：
    # 水滴 → 甜甜圈 → 梨形。原因是火苗那条轮廓**靠猜贝塞尔控制点做不出来**，
    # 它需要的是专业图标库里现成的路径。而这里没有引入矢量图库的预算，
    # 所以改成「巨型数字 + 短琥珀横线」—— 数字本身就够抢眼，
    # 横线替代图标承担视觉分割，而且是一块矩形，画不歪。
    hero_cy = 480
    shown = streak if streak > 0 else 1
    d.text((W / 2, hero_cy), str(shown), font=f_hero, fill=AMBER, anchor="mm")
    draw_spaced(d, (W / 2 - 110, hero_cy + 200), "天连续打卡", f_streak, INK, 5)

    # 短横线：居中的一块琥珀，宽 192、高 8
    d.rectangle([W / 2 - 96, 790, W / 2 + 96, 798], fill=AMBER)

    minutes = max(0, today_seconds) // 60
    d.text((W / 2, 900), f"今天听了 {minutes} 分钟 · 目标 {GOAL_MINUTES} 分钟",
           font=f_sub, fill=INK_MID, anchor="mm")

    d.rectangle([MARGIN, 990, W - MARGIN, 992], fill=HAIRLINE)

    # ---- 最近 7 天 ----
    draw_spaced(d, (W / 2 - 96, 1052), "最近 7 天", f_recent, INK_FAINT, 6)

    cell, gap = 96, 24
    row_w = cell * RECENT_DAYS + gap * (RECENT_DAYS - 1)
    left = (W - row_w) / 2
    cell_top = 1090
    for i in range(RECENT_DAYS):
        x = left + i * (cell + gap)
        box = [x, cell_top, x + cell, cell_top + cell]
        if recent_done[i]:
            d.rounded_rectangle(box, radius=10, fill=AMBER_SOFT)
        else:
            d.rounded_rectangle(box, radius=10, outline=HAIRLINE, width=2)
        if i == RECENT_DAYS - 1:
            d.rounded_rectangle(
                [x - 7, cell_top - 7, x + cell + 7, cell_top + cell + 7],
                radius=15, outline=AMBER, width=3,
            )

    # ---- 页脚：只留一句口号 ----
    #
    # 原本还有第二行「端侧转写 · N 个英语新闻源 · 无需上传」，已删 ——
    # 那是产品参数，不是一张发给微信好友看的图该说的话。
    # 权威版式在 Kotlin 侧 CheckInPoster.kt，改文案先改那边再同步这里。
    d.text((W / 2, 1314), "把通勤变成听力课", font=f_foot1, fill=INK_FAINT, anchor="mm")

    out_path.parent.mkdir(parents=True, exist_ok=True)
    img.save(out_path, "PNG")
    print(f"已输出 {out_path}  ({W}×{H})")
    print(f"  连续 {shown} 天 · 今日 {minutes} 分钟 · 最近7天 {''.join('●' if x else '○' for x in recent_done)}")
    return out_path


if __name__ == "__main__":
    target = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("/tmp/checkin-poster-preview.png")
    render(target)
