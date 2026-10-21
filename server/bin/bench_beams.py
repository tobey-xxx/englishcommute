#!/usr/bin/env python3
"""束搜索 vs 贪心：目标句对照 + 速度/内存代价。

判据是「贪心静默丢内容」—— 所以重点看：**beam4 是否把 beam1 丢掉的部分补回来了**。
"""
import sys
import time
import resource

sys.path.insert(0, "/opt/ec/bin")
from translate_server import Translator

CASES = [
    ("最初发现丢内容的那句",
     "The war with Iran is expected to be the main focus this week when President Trump addresses the UN General Assembly."),
    ("另一处丢内容（绿卡）",
     "Mamdani recently joined other mayors and governors who sued the White House over a policy that makes it harder to obtain green cards."),
    ("否定易错",
     "President Trump's hand-picked Fed Chair did the opposite of what Trump wanted him to do."),
    ("习语直译",
     "Walsh hopes the stable prices will help families keep their heads above water."),
    ("长句（政治语境）",
     "We think that just like with any cross-border activity, that moving from opaque to more transparency between the number one and the number two AI powers in the world, very important."),
    ("短句（应当无差别）",
     "Wall Street futures are higher this morning."),
]

rows = {}
for beams in (1, 4):
    t = Translator("fp32", 2, None, None, beams)
    print(f"===== 束 = {beams}（加载 {t.load_sec:.1f}s）=====", flush=True)
    times = []
    for name, s in CASES:
        t0 = time.monotonic()
        zh, n = t.translate(s)
        dt = time.monotonic() - t0
        times.append(dt)
        rows.setdefault(name, {})[beams] = (zh, dt, n)
        print(f"  {name}  {dt*1000:6.0f} ms / {n:3d} 轮\n    {zh}", flush=True)
    print(f"  平均 {sum(times)/len(times)*1000:.0f} ms/句\n", flush=True)

print("=" * 76)
print(f"{'句子':<22} {'beam1':>8} {'beam4':>8} {'倍数':>6}  长度增益")
for name in rows:
    z1, d1, _ = rows[name][1]
    z4, d4, _ = rows[name][4]
    gain = len(z4) - len(z1)
    tag = f"+{gain} 字（补全）" if gain > 3 else (f"{gain} 字" if gain else "相同")
    print(f"{name:<22} {d1*1000:7.0f}ms {d4*1000:7.0f}ms {d4/d1:5.1f}x  {tag}")

peak = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss / 1024
print(f"\n本进程峰值 {peak:.0f} MB（两个模型集先后加载，数字偏大属正常）")
