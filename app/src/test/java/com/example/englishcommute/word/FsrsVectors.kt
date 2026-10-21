// ⚠️⚠️ 本文件由 tools/gen-fsrs-vectors.py 自动生成，**不要手改**。
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
internal const val FSRS_VECTOR_T0_MILLIS = 1767225600000L

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

    FsrsVectorCase(
        name = "new-again",
        steps = listOf(FsrsVectorStep(rating = 1, gapDays = 0)),
        trace = listOf(
            FsrsVectorExpect(0.0, 2, null, 0.212, 6.4133, 1.0),
        ),
    ),
    FsrsVectorCase(
        name = "new-hard",
        steps = listOf(FsrsVectorStep(rating = 2, gapDays = 0)),
        trace = listOf(
            FsrsVectorExpect(0.0, 2, null, 1.2931, 5.112170705601056, 1.0),
        ),
    ),
    FsrsVectorCase(
        name = "new-good",
        steps = listOf(FsrsVectorStep(rating = 3, gapDays = 0)),
        trace = listOf(
            FsrsVectorExpect(0.0, 2, null, 2.3065, 2.118103970459016, 2.0),
        ),
    ),
    FsrsVectorCase(
        name = "new-easy",
        steps = listOf(FsrsVectorStep(rating = 4, gapDays = 0)),
        trace = listOf(
            FsrsVectorExpect(0.0, 2, null, 8.2956, 1.0, 8.0),
        ),
    ),
    FsrsVectorCase(
        name = "good-x3",
        steps = listOf(FsrsVectorStep(rating = 3, gapDays = 0), FsrsVectorStep(rating = 3, gapDays = 2), FsrsVectorStep(rating = 3, gapDays = 5)),
        trace = listOf(
            FsrsVectorExpect(0.0, 2, null, 2.3065, 2.118103970459016, 2.0),
            FsrsVectorExpect(0.9094932559773545, 2, null, 10.964332335820698, 2.111214235785395, 13.0),
            FsrsVectorExpect(0.9446107843740016, 2, null, 30.13323120791683, 2.1043313908464483, 37.0),
        ),
    ),
    FsrsVectorCase(
        name = "same-day-good",
        steps = listOf(FsrsVectorStep(rating = 3, gapDays = 0), FsrsVectorStep(rating = 3, gapDays = 0)),
        trace = listOf(
            FsrsVectorExpect(0.0, 2, null, 2.3065, 2.118103970459016, 2.0),
            FsrsVectorExpect(1.0, 2, null, 2.3065, 2.111214235785395, 2.0),
        ),
    ),
    FsrsVectorCase(
        name = "same-day-easy",
        steps = listOf(FsrsVectorStep(rating = 3, gapDays = 0), FsrsVectorStep(rating = 4, gapDays = 0)),
        trace = listOf(
            FsrsVectorExpect(0.0, 2, null, 2.3065, 2.118103970459016, 2.0),
            FsrsVectorExpect(1.0, 2, null, 3.946054067969477, 1.0, 4.0),
        ),
    ),
    FsrsVectorCase(
        name = "same-day-hard",
        steps = listOf(FsrsVectorStep(rating = 3, gapDays = 0), FsrsVectorStep(rating = 2, gapDays = 0)),
        trace = listOf(
            FsrsVectorExpect(0.0, 2, null, 2.3065, 2.118103970459016, 2.0),
            FsrsVectorExpect(1.0, 2, null, 2.3065, 4.752858488532557, 2.0),
        ),
    ),
    FsrsVectorCase(
        name = "same-day-again",
        steps = listOf(FsrsVectorStep(rating = 3, gapDays = 0), FsrsVectorStep(rating = 1, gapDays = 0)),
        trace = listOf(
            FsrsVectorExpect(0.0, 2, null, 2.3065, 2.118103970459016, 2.0),
            FsrsVectorExpect(1.0, 2, null, 0.7750839828558984, 7.394502741279718, 1.0),
        ),
    ),
    FsrsVectorCase(
        name = "hard-after-good",
        steps = listOf(FsrsVectorStep(rating = 3, gapDays = 0), FsrsVectorStep(rating = 2, gapDays = 3)),
        trace = listOf(
            FsrsVectorExpect(0.0, 2, null, 2.3065, 2.118103970459016, 2.0),
            FsrsVectorExpect(0.8809479557659419, 2, null, 9.234870781784839, 4.752858488532557, 12.0),
        ),
    ),
    FsrsVectorCase(
        name = "easy-after-good",
        steps = listOf(FsrsVectorStep(rating = 3, gapDays = 0), FsrsVectorStep(rating = 4, gapDays = 3)),
        trace = listOf(
            FsrsVectorExpect(0.0, 2, null, 2.3065, 2.118103970459016, 2.0),
            FsrsVectorExpect(0.8809479557659419, 2, null, 23.88306407915667, 1.0, 27.0),
        ),
    ),
    FsrsVectorCase(
        name = "lapse",
        steps = listOf(FsrsVectorStep(rating = 3, gapDays = 0), FsrsVectorStep(rating = 1, gapDays = 3)),
        trace = listOf(
            FsrsVectorExpect(0.0, 2, null, 2.3065, 2.118103970459016, 2.0),
            FsrsVectorExpect(0.8809479557659419, 2, null, 0.6368506992409603, 7.394502741279718, 4.0),
        ),
    ),
    FsrsVectorCase(
        name = "lapse-recover",
        steps = listOf(FsrsVectorStep(rating = 3, gapDays = 0), FsrsVectorStep(rating = 1, gapDays = 3), FsrsVectorStep(rating = 3, gapDays = 1), FsrsVectorStep(rating = 3, gapDays = 5)),
        trace = listOf(
            FsrsVectorExpect(0.0, 2, null, 2.3065, 2.118103970459016, 2.0),
            FsrsVectorExpect(0.8809479557659419, 2, null, 0.6368506992409603, 7.394502741279718, 4.0),
            FsrsVectorExpect(0.8661464927983407, 2, null, 2.4466106054397474, 7.382336607835277, 6.0),
            FsrsVectorExpect(0.8440152452346493, 2, null, 9.001724386637163, 7.37018264052428, 18.0),
        ),
    ),
    FsrsVectorCase(
        name = "overdue-30",
        steps = listOf(FsrsVectorStep(rating = 3, gapDays = 0), FsrsVectorStep(rating = 3, gapDays = 30)),
        trace = listOf(
            FsrsVectorExpect(0.0, 2, null, 2.3065, 2.118103970459016, 2.0),
            FsrsVectorExpect(0.6675263338730357, 2, null, 37.42063838925624, 2.111214235785395, 67.0),
        ),
    ),
    FsrsVectorCase(
        name = "overdue-200",
        steps = listOf(FsrsVectorStep(rating = 3, gapDays = 0), FsrsVectorStep(rating = 3, gapDays = 200)),
        trace = listOf(
            FsrsVectorExpect(0.0, 2, null, 2.3065, 2.118103970459016, 2.0),
            FsrsVectorExpect(0.5031467451256691, 2, null, 58.53103766767546, 2.111214235785395, 259.0),
        ),
    ),
    FsrsVectorCase(
        name = "again-x3",
        steps = listOf(FsrsVectorStep(rating = 3, gapDays = 0), FsrsVectorStep(rating = 1, gapDays = 1), FsrsVectorStep(rating = 1, gapDays = 1), FsrsVectorStep(rating = 1, gapDays = 1)),
        trace = listOf(
            FsrsVectorExpect(0.0, 2, null, 2.3065, 2.118103970459016, 2.0),
            FsrsVectorExpect(0.9468474993825461, 2, null, 0.5712991793450282, 7.394502741279718, 2.0),
            FsrsVectorExpect(0.8572118285676134, 2, null, 0.2094306891013188, 9.128819137736839, 3.0),
            FsrsVectorExpect(0.7650108884027307, 2, null, 0.09779604834510425, 9.698877143900798, 4.0),
        ),
    ),
    FsrsVectorCase(
        name = "easy-x3",
        steps = listOf(FsrsVectorStep(rating = 4, gapDays = 0), FsrsVectorStep(rating = 4, gapDays = 9), FsrsVectorStep(rating = 4, gapDays = 24)),
        trace = listOf(
            FsrsVectorExpect(0.0, 2, null, 8.2956, 1.0, 8.0),
            FsrsVectorExpect(0.8943037762457955, 2, null, 70.63155368576176, 1.0, 80.0),
            FsrsVectorExpect(0.9566333011665873, 2, null, 219.27411454851145, 1.0, 252.0),
        ),
    ),
)
