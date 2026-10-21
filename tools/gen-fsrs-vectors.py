#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""用官方参照实现（tools/fsrs-reference，py-fsrs 6.3.2）生成 FSRS 测试向量，
   输出成 Kotlin 常量文件，供 FsrsTest.kt 逐值比对。

    ⚠️ 这里**不改动参照实现的一行代码** —— 改了它就不是参照了。
       需要 typing_extensions：pip install typing_extensions

    跑法：
        PYTHONPATH=tools/fsrs-reference python3 tools/gen-fsrs-vectors.py
"""
import json
import os
import sys
from datetime import datetime, timedelta, timezone

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), 'fsrs-reference'))
from fsrs import Scheduler, Card, Rating, State          # noqa: E402

T0 = datetime(2026, 1, 1, 0, 0, tzinfo=timezone.utc)
T0_MILLIS = int(T0.timestamp() * 1000)

# 我们的配置（与 Fsrs.kt 里的默认值必须一致）
SCHED = Scheduler(
    learning_steps=[],            # 通勤场景不需要「1 分钟/10 分钟后再来」
    relearning_steps=[],
    maximum_interval=365,
    desired_retention=0.9,
    enable_fuzzing=False,         # 向量必须可复现 -> 关抖动
)

# ⚠️ (rating, gapDays)：gapDays 是**相对上一次复习**的天数（0 = 同一天）。
CASES = [
    # --- 新卡四种评分（第一次复习：走 S0 / D0 初始化）---
    ("new-again", [(1, 0)]),
    ("new-hard", [(2, 0)]),
    ("new-good", [(3, 0)]),
    ("new-easy", [(4, 0)]),

    # --- 跨日连续 Good（走 _next_stability 的成功分支）---
    ("good-x3", [(3, 0), (3, 2), (3, 5)]),

    # --- 同日重复（走 _short_term_stability，FSRS-6 那个带 S^(-w19) 的新公式）---
    ("same-day-good", [(3, 0), (3, 0)]),
    ("same-day-easy", [(3, 0), (4, 0)]),
    ("same-day-hard", [(3, 0), (2, 0)]),
    ("same-day-again", [(3, 0), (1, 0)]),

    # --- Hard / Easy 的惩罚与奖励系数（w15 / w16）---
    ("hard-after-good", [(3, 0), (2, 3)]),
    ("easy-after-good", [(3, 0), (4, 3)]),

    # --- 遗忘与恢复（走 _next_forget_stability，含 FSRS-6 的 min(...) 上限）---
    ("lapse", [(3, 0), (1, 3)]),
    ("lapse-recover", [(3, 0), (1, 3), (3, 1), (3, 5)]),

    # --- 长间隔（R 很低，测遗忘曲线与 R 对 S 的影响）---
    ("overdue-30", [(3, 0), (3, 30)]),
    ("overdue-200", [(3, 0), (3, 200)]),

    # --- ★ 验收标准直接对应的两条 ---
    ("again-x3", [(3, 0), (1, 1), (1, 1), (1, 1)]),      # 间隔不该增长
    ("easy-x3", [(4, 0), (4, 9), (4, 24)]),              # 间隔该逐次变长
]


def build():
    out = []
    for name, steps in CASES:
        card = Card()
        now = T0
        trace = []
        for rating, gap in steps:
            now = now + timedelta(days=gap)
            # 复习**之前**的可提取度 —— 这是 get_card_retrievability 的返回值
            r_before = SCHED.get_card_retrievability(card, current_datetime=now)
            card, _log = SCHED.review_card(card, Rating(rating), review_datetime=now)
            trace.append({
                'rating': rating,
                'gapDays': gap,
                'retrievabilityBefore': r_before,
                'phase': int(card.state),
                'step': card.step,
                'stability': card.stability,
                'difficulty': card.difficulty,
                'dueDays': (card.due - T0).total_seconds() / 86400.0,
            })
        out.append({'name': name, 'trace': trace})
    return out


KT_HEADER = '''// ⚠️⚠️ 本文件由 tools/gen-fsrs-vectors.py 自动生成，**不要手改**。
//
// 数据来源：tools/fsrs-reference（py-fsrs 6.3.2 官方实现的只读副本）。
// 用途：FsrsTest.kt 用它逐值比对 word/Fsrs.kt —— **移植的正确性只能靠数值对比**，
//       肉眼比对公式是看不出来的。
//
// 要重新生成：
//     PYTHONPATH=tools/fsrs-reference python3 tools/gen-fsrs-vectors.py
//
// 配置（与 Fsrs.kt 的默认值一致）：learning_steps=[] · relearning_steps=[]
//     · maximum_interval=365 · desired_retention=0.9 · enable_fuzzing=false
package com.example.englishcommute.word

/** T0 = 2026-01-01T00:00:00Z，所有 dueAt 都以它为基准（毫秒） */
internal const val FSRS_VECTOR_T0_MILLIS = %dL

internal data class FsrsVectorStep(val rating: Int, val gapDays: Int)

internal data class FsrsVectorExpect(
    val retrievabilityBefore: Double,
    val phase: Int,
    val step: Int?,
    val stability: Double,
    val difficulty: Double,
    val dueDays: Double,
)

internal data class FsrsVectorCase(
    val name: String,
    val steps: List<FsrsVectorStep>,
    val trace: List<FsrsVectorExpect>,
)

internal val FSRS_VECTORS: List<FsrsVectorCase> = listOf(
'''


def kd(x):
    """把 float 写成 Kotlin 认得的 Double 字面量。

    ⚠️ 不能用 `%.17g`：它对整数值会输出 `2`、`13` 这种**没有小数点**的形式，
       Kotlin 的类型推导会当成 Int，编译期报
       「Argument type mismatch: actual type is Int, but Double was expected」。
       `repr()` 给的是最短可 round-trip 表示，整数值一定带 `.0`。
    """
    return repr(float(x))


def kt(report):
    lines = [KT_HEADER % T0_MILLIS]
    for case in report:
        steps = ', '.join('FsrsVectorStep(rating = %d, gapDays = %d)' % (t['rating'], t['gapDays'])
                          for t in case['trace'])
        exps = []
        for t in case['trace']:
            step_lit = 'null' if t['step'] is None else str(t['step'])
            exps.append(
                'FsrsVectorExpect(%s, %d, %s, %s, %s, %s)'
                % (kd(t['retrievabilityBefore']), t['phase'], step_lit,
                   kd(t['stability']), kd(t['difficulty']), kd(t['dueDays']))
            )
        lines.append('    FsrsVectorCase(')
        lines.append('        name = "%s",' % case['name'])
        lines.append('        steps = listOf(%s),' % steps)
        lines.append('        trace = listOf(')
        for e in exps:
            lines.append('            %s,' % e)
        lines.append('        ),')
        lines.append('    ),')
    lines.append(')')
    return '\n'.join(lines) + '\n'


def main():
    report = build()
    dest = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..',
                        'app', 'src', 'test', 'java', 'com', 'example',
                        'englishcommute', 'word', 'FsrsVectors.kt')
    dest = os.path.normpath(dest)
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    with open(dest, 'w', encoding='utf-8') as fh:
        fh.write(kt(report))

    print('生成 %d 个用例 -> %s' % (len(report), dest))
    print()
    print('%-18s %s' % ('用例', '每一步的 due（相对 T0 的天数）'))
    print('-' * 72)
    for case in report:
        dues = ' → '.join('%.2f' % t['dueDays'] for t in case['trace'])
        print('%-18s %s' % (case['name'], dues))
    print()
    # 直接验证验收标准里那两条
    ax = next(c for c in report if c['name'] == 'again-x3')
    intervals = [ax['trace'][i]['dueDays'] - sum(-0.0 for _ in ()) for i in range(len(ax['trace']))]
    print('★ again-x3 各步 due：%s' % ' → '.join('%.3f' % t['dueDays'] for t in ax['trace']))
    ex = next(c for c in report if c['name'] == 'easy-x3')
    print('★ easy-x3  各步 due：%s' % ' → '.join('%.3f' % t['dueDays'] for t in ex['trace']))
    stab = [t['stability'] for t in ex['trace']]
    print('   easy-x3 stability：%s  ← 单调递增？%s'
          % (' → '.join('%.3f' % s for s in stab), all(stab[i] < stab[i + 1] for i in range(len(stab) - 1))))
    json.dump(report, open('/tmp/fsrs-vectors.json', 'w'), ensure_ascii=False, indent=1)


if __name__ == '__main__':
    main()
