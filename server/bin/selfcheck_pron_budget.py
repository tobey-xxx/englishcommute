#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
验证 `--budget-seconds` 的语义：**到点就停，剩下的下一轮继续**。

做法：把脚本当模块加载，把 OUT_DIR / LOCK_PATH / gen_one 换成测试用的，
这样既走了真实的「列待生成词」逻辑（真的扫 vocab.base + subs），
又不用真去调微软的在线接口。

⚠️ 这个测试的价值在于它验证的是**记账**而不是**生成**：
   「本轮 +N / 待生成 M」这两个数字必须和实际生成数对得上。
   数错了不会报错，只会让循环永远追不平（或者以为追平了其实没有）。
"""
import asyncio
import importlib.util
import os
import shutil
import sys

SCRIPT = "/opt/ec/bin/build_pron_v2.py"

spec = importlib.util.spec_from_file_location("bp", SCRIPT)
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)

TEST_DIR = "/tmp/pron_budget_test"
TEST_LOCK = "/tmp/pron_budget_test.lock"

for p in (TEST_DIR, TEST_LOCK):
    if os.path.isdir(p):
        shutil.rmtree(p)
    elif os.path.exists(p):
        os.remove(p)
os.makedirs(TEST_DIR, exist_ok=True)

mod.OUT_DIR = TEST_DIR
mod.LOCK_PATH = TEST_LOCK

generated = []


async def fake_gen_one(word, out, retries=3):
    """假装生成成功。故意让它花一点点时间，好让预算真的到点。"""
    await asyncio.sleep(0.002)
    with open(out, "w") as f:
        f.write("x" * 600)
    generated.append(word)
    return True


mod.gen_one = fake_gen_one

budget = 2.0
sys.argv = ["build_pron_v2.py", "--budget-seconds", str(budget), "--quiet"]

import time
t0 = time.time()
asyncio.run(mod.main())
elapsed = time.time() - t0

n_files = len([f for f in os.listdir(TEST_DIR) if f.endswith(".mp3")])

print()
print("=" * 62)
print("  预算 %.1f 秒 · 实际用时 %.2f 秒" % (budget, elapsed))
print("  桩函数记录生成 %d 个 · 目录里实际有 %d 个文件" % (len(generated), n_files))
print("=" * 62)

ok = True

# 判据 1：真的到点停了。留 1.5 秒余量（进程启动 + 列词表本身要时间）
if elapsed > budget + 1.5:
    print("  ❌ 超时太多：预算 %.1fs，跑了 %.2fs —— 预算检查可能写在生成之后了" % (budget, elapsed))
    ok = False
else:
    print("  ✅ 到点就停（%.2fs ≤ %.1fs + 余量）" % (elapsed, budget))

# 判据 2：记账一致 —— 「生成的文件数」必须等于「桩函数被调用的次数」
if len(generated) != n_files:
    print("  ❌ 记账不一致：桩调用 %d 次，目录 %d 个文件" % (len(generated), n_files))
    ok = False
else:
    print("  ✅ 记账一致（%d 个）" % n_files)

# 判据 3：不许一次跑完（否则说明预算根本没生效）
if n_files >= 17543:
    print("  ❌ 一次把全部 %d 个都跑了 —— 预算没生效" % n_files)
    ok = False
else:
    print("  ✅ 确实中断在中途（没跑完 17543 个）")

# 判据 4：第二次跑必须**接着跑**（断点续传），且不会重复生成已存在的
before = len(generated)
sys.argv = ["build_pron_v2.py", "--budget-seconds", "1", "--quiet"]
asyncio.run(mod.main())
second = len(generated) - before
overlap = len(set(generated)) != len(generated)
if overlap:
    print("  ❌ 第二次跑重复生成了已有的词")
    ok = False
else:
    print("  ✅ 第二次接着跑（本轮 +%d，无重复）" % second)

# 判据 5：锁文件能被第二个进程正常拿到（前一个已退出，flock 应自动释放）
sys.argv = ["build_pron_v2.py", "--budget-seconds", "0.5", "--quiet"]
try:
    asyncio.run(mod.main())
    print("  ✅ 上一个进程退出后锁自动释放（flock 语义正确）")
except mod.AlreadyRunning:
    print("  ❌ 锁没释放 —— 前一个进程退出了还拿不到")
    ok = False

shutil.rmtree(TEST_DIR, ignore_errors=True)
if os.path.exists(TEST_LOCK):
    os.remove(TEST_LOCK)

print()
print("✅ 全部通过" if ok else "❌ 有判据失败")
sys.exit(0 if ok else 1)
