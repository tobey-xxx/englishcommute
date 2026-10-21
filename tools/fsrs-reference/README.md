# FSRS 官方参照实现（只读，**不要改**）

这四个字是重要约束：**改了它就不是参照了**。

- 来源：https://github.com/open-spaced-repetition/py-fsrs
- 版本：`6.3.2`（FSRS-6）
- 许可：MIT（见 py-fsrs 仓库）
- 收录文件：`scheduler.py` / `card.py` / `state.py` / `rating.py` / `review_log.py`
  （未收录 `optimizer.py` —— 它需要 torch，且我们不做参数拟合）

## 为什么把它放进仓库

`word/Fsrs.kt` 是我们从这份实现**移植**出来的纯 Kotlin 版本。
移植的正确性**不能靠肉眼比对**，要靠**数值对比**：

```
tools/gen-fsrs-vectors.py   ← 用这份参照实现跑出测试向量
        ↓ 生成
FsrsVectors.kt              ← 向量变成 Kotlin 常量
        ↓ 被
FsrsTest.kt                 ← 逐值比对我们的实现（容差 1e-9）
```

跑法：`./gradlew :app:testDebugUnitTest`

## ⚠️ 依赖说明

`card.py` / `scheduler.py` 里有 `from typing_extensions import Self`，需要
`typing_extensions`（`pip install typing_extensions`）。
**不要为了省这个依赖去改源码的 import** —— 见本文第一行。

## ⚠️ 我们**没有**照抄的部分（写在 Fsrs.kt 里，这里只列）

1. **`learning_steps` / `relearning_steps` 都传空数组**。
   官方默认是 `[1min, 10min]` / `[10min]`，那是 Anki 式「当天重复到记住」的流程。
   我们的场景是通勤路上加词、当场复习一次，用户不会等在手机前 10 分钟。
   传空之后新词第一次评分就直接进 `Review`、按天排。

2. **`maximum_interval = 365`**（官方默认 36500 天 = 100 年）。
   一年足够，而且不会让一个词从复习队列里彻底消失。

3. **fuzzing 开着**（官方默认也开）。避免「同一天加的词永远同一天到期」。
   单测里关掉（否则不可复现），只断言「抖动后落在允许区间内」。
