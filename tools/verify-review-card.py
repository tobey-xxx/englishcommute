#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
复习卡的规则契约（3.0.4 新建）。

## 守两条规则

### 一、**正面不许有任何音频按钮**（泄题）

复习卡正面问的是「这句话里的空是什么」。而：

| 按钮 | 点了会发生什么 |
|---|---|
| 听读音 | 听到 /saɪnd/ ⇒ 想一下就知道是 `signed` |
| 听这一句 | 整句朗读里那个词就在里面 ⇒ 连猜都不用 |

所以音频**只在背面**给（那时答案已揭晓，音频是辅助记忆而不是泄题）。

⚠️ 这条规则值得写成一个脚本，因为「给每个词加一个播放按钮」看起来
   **永远**是个好主意 —— 我自己的设计稿上正面就画了两个按钮，
   是做出来之后才想明白的。

### 二、背面两个按钮都要在

「听这一句」（原声 / 语境）和「听读音」（标准音 / 孤立）是**两种东西**，
不是重复：学发音靠后者，认词靠前者。少一个都不算完整。

## ⚠️ 这个脚本为什么值得存在

上面第一条如果被违反，**编译通过、单测全绿、界面上看起来一切正常**，
唯一的后果是「复习变得没有意义了」—— 而没有任何东西会报出来。
"""
import io
import os
import re
import sys

ROOT = os.path.dirname(os.path.abspath(__file__)) + "/.."
P = os.path.join(ROOT, "app/src/main/java/com/example/englishcommute/ui/ReviewScreen.kt")

ok_count = 0
fail_count = 0


def check(name, cond, why=""):
    global ok_count, fail_count
    if cond:
        ok_count += 1
        print("  ✅ %s" % name)
    else:
        fail_count += 1
        print("  ❌ %s" % name)
        if why:
            print("       %s" % why)


def section(t):
    print()
    print("=" * 68)
    print("  " + t)
    print("=" * 68)


def body_of(src, fun_sig):
    """
    取出一个 Composable 函数的**函数体**。

    ⚠️ 必须限定在函数体内 —— 用整份文件当范围的话，
       背面那个 `AudioRow(` 会让「正面不许有」这条判据永远为假通过。
       （项目纪律：判据的范围要限定在函数体内，`rfind` 会越过函数边界。）
    """
    i = src.find(fun_sig)
    if i < 0:
        return None
    # 从签名往后找到「第一层花括号配平」的那个位置
    j = src.find("{", i)
    if j < 0:
        return None
    depth = 0
    for k in range(j, len(src)):
        if src[k] == "{":
            depth += 1
        elif src[k] == "}":
            depth -= 1
            if depth == 0:
                return src[j:k + 1]
    return None


src = io.open(P, encoding="utf-8").read()

print()
print("  复习卡规则契约（3.0.4）")

# ===========================================================================
section("一、正面不许有任何音频按钮（泄题）")
# ===========================================================================
#
# ⚠️ 判据用**完整调用**（带左括号），不是裸词 ——
#    注释里会提到「听读音」「听这一句」这些说法（解释为什么不做），
#    拿裸词当判据会命中注释、恒为假失败，然后下一个人会把断言放宽。

front = body_of(src, "private fun QuestionSide(")
check("能定位到 QuestionSide 的函数体", front is not None, "签名变了？")
if front:
    for call, what in [
        ("PronButton(", "「听读音」类按钮"),
        ("AudioRow(", "音频行（听这一句 + 听读音）"),
        ("PronReadButton(", "读音按钮"),
        ("vm.playPron(", "播放读音"),
        ("vm.playSentence(", "播放原句"),
        ("vm.playSentence(ctx)", "播放原句"),
    ]:
        check(
            "  · QuestionSide 里没有 %s" % what,
            call not in front,
            "正面给音频 = 泄题（听到发音就知道答案了）",
        )

    # 反向断言必须能真的失败 —— 坏样本
    #
    # ⚠️ 坏样本的构造**不能绑在某句文案上**。
    #
    #    第一版就是绑的：锚点是正面底部那句「想不起来就直接看答案」。
    #    3.1.1 把正面文案改成「别硬想 —— 认不出就直接点…」之后，`replace`
    #    找不到锚点、**静默返回原串** —— 坏样本就成了「一个必然失败的坏样本」：
    #    看起来还在守，其实什么都没测。
    #
    #    （它这次是被下面那个 `"AudioRow(" in bad` 抓住的 —— 那个 check 写对了：
    #      它同时验证了「替换真的发生了」，而不只是「我们尝试替换过」。）
    #
    #    改成按**结构**定位：插入点用函数开头那句与文案无关的布局常量。
    #    文案会变，布局常量不会 —— 判据落在更稳的那个量上。
    bad = front.replace(
        "Spacer(Modifier.height(28.dp))",
        "AudioRow(card = card, vm = vm)\n    Spacer(Modifier.height(28.dp))",
        1,
    )
    check(
        "坏样本（把 AudioRow 加回正面）被拦下",
        "AudioRow(" in bad,
        "反向断言不能失败 ⇒ 它只是一句写在脚本里的注释",
    )

# ===========================================================================
section("二、背面两个按钮都在")
# ===========================================================================

back = body_of(src, "private fun AnswerSide(")
check("能定位到 AnswerSide 的函数体", back is not None)
if back:
    check("  · 背面调用了 AudioRow", "AudioRow(" in back)
    check(
        "  · 背面**不直接**调 PronRow（已改名，不应再存在）",
        "PronRow(" not in src,
        "改名要 grep 全部调用点 —— 留一个旧名字就是两处真相源",
    )

row = body_of(src, "private fun AudioRow(")
check("能定位到 AudioRow 的函数体", row is not None)
if row:
    check("  · 有「听这一句」", '"听这一句"' in row)
    check(
        "  · 它调 playSentence",
        "vm.playSentence(" in row,
    )
    check(
        "  · 「听读音」抽到了 PronReadButton（不是内联在 AudioRow 里）",
        "PronReadButton(" in row and "private fun PronReadButton(" in src,
    )

read_btn = body_of(src, "private fun PronReadButton(")
check("能定位到 PronReadButton 的函数体", read_btn is not None)
if read_btn:
    check("  · 它提供「听读音」", '"听读音"' in read_btn)
    check("  · 且四种状态都还在", "PronState.Loading" in read_btn and "PronState.Failed" in read_btn
          and "PronState.NotPublished" in read_btn)

# ===========================================================================
section("三、两个播放器必须互相让声音")
# ===========================================================================
#
# ⚠️ 两个 `MediaPlayer` 同时播 = 两个声音叠在一起。
#    背面这两个按钮并排，用户很容易「先听读音、再听句子」。

APP = io.open(
    os.path.join(ROOT, "app/src/main/java/com/example/englishcommute/EnglishCommuteApp.kt"),
    encoding="utf-8",
).read()
check(
    "PronPlayer 在开播前让 SentencePlayer 停",
    "PronPlayer(onTakeOver = { sentencePlayer.stop() })" in APP,
)
check(
    "SentencePlayer 在开播前让 PronPlayer 停",
    "SentencePlayer(onTakeOver = { pronPlayer.stop() })" in APP,
)

# 坏样本：只留一个方向
bad2 = APP.replace("SentencePlayer(onTakeOver = { pronPlayer.stop() })",
                   "SentencePlayer()", 1)
check(
    "坏样本（去掉一个方向）被拦下",
    "SentencePlayer(onTakeOver = { pronPlayer.stop() })" not in bad2,
    "只做一个方向 ⇒「先点听读音、再点听这一句」会叠音",
)

# ===========================================================================
section("四、两段式：正面两档（一个导航 + 一个评分），背面三档")
# ===========================================================================
#
# 3.1.1 的核心改动。守的是三件「改错了也不会报错」的事：
#
#   ① 正面不再有挖空 —— 用户明确反馈「像完型填空，词多了太难记住」
#   ② 「没认出来」必须是**导航** —— 这条最容易改错，见下面的说明
#   ③ 背面不再有「太简单」 —— 它在正面用掉了

bar = body_of(src, "private fun ReviewBottomBar(")
check("能定位到 ReviewBottomBar 的函数体", bar is not None, "签名变了？")
if bar and front:
    check(
        "  · 正面不再有挖空（masked）",
        "masked(" not in front,
        "挖空考的是「能把那个词复现出来」（≈默写），而词量长起来之后要练的是"
        "「看到它知道意思」—— 不是同一个能力，而默写失败不等于没记住",
    )
    check(
        "  · 正面把例句里的目标词标出来（highlighted + accent = true）",
        "highlighted(" in front and "accent = true" in front,
        "改了标粗方式却忘了传 accent ⇒ 那个词变成正文色，用户一眼找不到它，"
        "而「找到那个词」正是这一面唯一要做的事",
    )
    # ★★ 下面这条是整节最要紧的
    check(
        "  · 「没认出来」是**导航**（reveal），不是评分",
        "onClick = onReveal" in bar,
        "把它写成 onRate(AGAIN) 会**静默丢掉**「看了答案、其实有点印象」这个信息 ——"
        "而 Again 的语义是「完全忘了」，它会把这个词的间隔压到最短。"
        "少一次评分，换来的是背面三档各自都还是准的",
    )
    check(
        "  · 「认出来了」落在 EASY 档",
        "RATING_EASY" in bar,
        "认出来 = 太简单 = Easy。落到别的档上，用户按的是「认出来了」、"
        "后果却不是「8 个月后」，而按钮上就写着这个后果",
    )
    check(
        "  · 背面恰好三档，且没有「太简单」",
        # ⚠️ 判据写 `RatingButton("太简单"` 而**不是**裸词「太简单」：
        #    这一面的注释里正好在解释「为什么这里不给太简单」——
        #    裸词会命中注释、恒为假失败，然后下一个人会把断言放宽。
        #    （同一类坑这个工程踩过多次：「断言匹配到注释」。）
        bar.count("RatingButton(") == 3 and 'RatingButton("太简单"' not in bar,
        "「太简单」已在正面用掉（就是「认出来了」）。背面再给一次，"
        "等于让用户对**同一次回忆**评两个相反的结论",
    )

# ===========================================================================
print()
print("=" * 68)
print("  通过 %d 项，失败 %d 项" % (ok_count, fail_count))
print("=" * 68)
if fail_count:
    print("❌ 有判据失败")
    sys.exit(1)
print("✅ 全部通过")
