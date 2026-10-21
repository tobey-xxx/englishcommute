package com.example.englishcommute.checkin

import android.content.Context
import com.example.englishcommute.diag.DiagLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 打卡记录：每天听了多少秒、以及每天的海报文件在哪。
 *
 * ## 为什么用 SharedPreferences 而不是 DataStore
 *
 * 收听时长是**秒级追加写**（见 [ListeningClock]，每 5 秒落一次），
 * 而 DataStore 每次 `edit` 都是「读全量 → 改 → 写全量」的事务。
 * 一天下来 40 分钟就是几百次全量重写，既费 IO 又没意义。
 * SharedPreferences 的 `apply()` 是内存先行 + 异步落盘，正好适配。
 *
 * （同一份理由在 [com.example.englishcommute.settings.PlaybackPositions] 里也写过一遍 ——
 * 那两处是同一个取舍。）
 *
 * ## 数据布局
 *
 * ```
 * sec:20391    → Int     **本机**在这一天听了多少秒（4.0.0 起它叫 mine）
 * others:20391 → Int     其它设备在这一天的合计（4.0.0，登录后才有）
 * poster:20391 → String  这一天的打卡海报路径（生成后才有）
 * goal:20392   → Int     **从这一天起**每日目标是多少秒（3.4.0）
 * ```
 *
 * ## ★ 为什么打卡时长要拆成 mine / others 两半（4.0.0）
 *
 * 多设备同步最朴素的做法是「同步一个『今天多少秒』的数」—— 它会错三处：
 * 两台设备互相覆盖、重试一次可能变两倍、而且**没有办法判断「40 分钟」
 * 是真的听了 40 还是同一次被算了两次**。
 *
 * 所以拆成两个**语义不同**的数：
 *
 * | 键 | 是什么 | 谁写 |
 * | --- | --- | --- |
 * | `sec:`（mine） | 本机听的总量，**只增不减** | 只有本机写；上传给服务端 |
 * | `others:` | **其它设备**的合计，来自上次同步的响应 | 只有同步写 |
 *
 * 界面显示 = `mine + others`。三条好处：
 *
 * 1. **加法在本地完成**，永远不会出现「显示 40 但其实是 25」这种半同步状态；
 * 2. **离线时 `others` 保持上次的值** ⇒ 断网照样看得到另一台设备的进度；
 * 3. **未登录用户 `others` 恒为空** ⇒ 行为与 4.0.0 之前**完全一致**，
 *    这条路径不需要任何新的测试面。
 *
 * ⚠️ 服务端返回的是**含本机在内的总量**，所以同步时要用
 *    `others = 总量 − mine` 反算（见 [applyServerTotals]）——
 *    直接拿总量当 others 会把本机的时长算两遍。
 * ⚠️ 旧的 `sec:` 前缀**故意保留**：老用户盘上那些数据就是 mine，
 *    一个字都不用迁移，第一次同步就自动「认领」了。
 *
 * 用前缀而不是「一天一个 key 文件」：同一份 prefs 里还放着别的东西，
 * 前缀是区分几类数据最省事的办法，也让 [reset] 可以按前缀扫。
 *
 * ⚠️ 三类 key **都带前缀**，而且 [reset] 只按前缀删 —— 这不是巧合，
 *    是选 key 名时就想好的：删记录时谁会被误伤，取决于它的名字长什么样，
 *    不取决于谁记得去改那个函数。3.3.0 加上目标时靠的是「它不带前缀」，
 *    3.4.0 目标变成按天记之后它**也带上了前缀**，于是那条不变式换了写法：
 *    不是「配置不带前缀所以不会被删」，而是「`reset` 只删它点名的那两个前缀」。
 *
 * ## 「达标」是算出来的，但它算的是**那一天**的目标（3.4.0）
 *
 * 只有秒数是原始数据，`done` 永远现算。但**用哪个目标算**这件事，
 * 3.3.0 和 3.4.0 完全不同：
 *
 * | | 判据 | 改目标之后 |
 * |---|---|---|
 * | 3.3.0 | `seconds >= 当前目标` | **全部历史跟着重算**，连续天数会掉 |
 * | 3.4.0 | `seconds >= goalOn(那一天)` | 只影响**明天起**，历史一个字节都不动 |
 *
 * ⚠️ 3.3.0 那段注释为「重算历史」辩护过，理由是「另一条路要为这个几乎是单调的量
 *    建一张按日的表」。**那个判断的前提是错的** —— 它假设用户不在意历史被重算。
 *    用户第二天就反馈了这件事。所以这里留一句给后来的人：
 *    **别用「这个量几乎是单调的」去证明「不需要它的历史」** ——
 *    单调说的是值，而用户在意的是「我那天到底算不算过」。
 *    细节与判据在 [GoalTimeline]。
 */
class CheckInStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 本机每天听了多少秒（旧的 `sec:` 键，只增不减） */
    private val _mine = MutableStateFlow(loadDays())
    val mine: StateFlow<Map<Long, Int>> = _mine.asStateFlow()

    /** 其它设备的合计（`others:` 键）。未登录时恒为空 */
    private val _others = MutableStateFlow(loadOthers())
    val others: StateFlow<Map<Long, Int>> = _others.asStateFlow()

    /**
     * epochDay → **全设备合计**秒数。界面直接订阅它。
     *
     * ⚠️ 它是一个**派生值**：任何一次 [addSeconds] 或同步之后都要 [recompute]。
     *    14 处界面读数（进度条、日历、连续天数、海报…）全都看这一个 map，
     *    所以「合并」只在这一个地方发生 —— 一旦让某处自己相加，
     *    就会长出第二份真相同步规则。
     */
    private val _days = MutableStateFlow(SyncMerge.merge(_mine.value, _others.value))
    val days: StateFlow<Map<Long, Int>> = _days.asStateFlow()

    /**
     * 重算显示用的合并值。
     *
     * ⚠️ 规则**只有一份**，在 [SyncMerge] 里（那是纯函数、配了单测）。
     *    写在这里的话，它就没有单测了 —— 而这条运算正是「多设备能不能
     *    接得上」的全部逻辑。
     */
    private fun recompute() {
        _days.value = SyncMerge.merge(_mine.value, _others.value)
    }

    /**
     * 每日目标的**改动时间轴**：`epochDay → 从那一天起的目标秒数`。
     *
     * 做成状态流而不是普通字段：打卡页上除了一行「目标 40 分钟」，
     * 还有进度条、日历格子的深浅、连续天数 —— 它们全都是「相对目标」的量，
     * 目标一变必须**一起**重算。写成一个普通字段的话，改完只有那行文字会变，
     * 其余几处要等下一次因为别的原因重组才跟上，看起来像界面坏了。
     *
     * ⚠️ 别在别处自己实现「取不大于某天的最大条目」那套逻辑 —— 那就在
     *    [GoalTimeline] 里，只有那一份。
     */
    private val _goalChanges = MutableStateFlow(loadGoalChanges())
    val goalChanges: StateFlow<Map<Long, Int>> = _goalChanges.asStateFlow()

    private val _posters = MutableStateFlow(loadPosters())
    val posters: StateFlow<Map<Long, String>> = _posters.asStateFlow()

    /**
     * 「今天刚刚达标」的一次性信号，界面订阅它弹出庆祝页（烟花 + 海报 + 分享）。
     *
     * ## 为什么由数据层置起，而不是让界面自己比较秒数
     *
     * 「跨过目标线那一刻」这个判断已经由 [addSeconds] 里的
     * `before < goalSecondsOn(day) && after >= goalSecondsOn(day)` 做过了
     * （[Tick.justCompleted]）。
     * 界面再比一次就是**第二个真相源**：两边都各自算「对」，
     * 但算的不是同一件事 —— 比如界面按自己的缓存值比较，就会在
     * 「计时器刚写完、还没来得及推状态流」的窗口里漏掉这一次。
     *
     * 而且这条规则必须与「生成海报」共用同一个触发点：两者错开的话，
     * 会出现「弹出庆祝页但图还没画好」，或者反过来「图有了但没庆祝」。
     *
     * ## 为什么不落盘
     *
     * 它是**进程内的一次性事件**，不是状态。落盘的话，用户第二天打开 App
     * 会被昨天那次打卡再庆祝一遍。进程重启即消失，正是想要的行为 ——
     * 没弹出来的那一份，打卡页里的海报和数字本来就都在。
     */
    private val _celebrate = MutableStateFlow(false)
    val celebrate: StateFlow<Boolean> = _celebrate.asStateFlow()

    /** 界面处理完庆祝页（用户关掉）之后调用。幂等 */
    fun consumeCelebrate() {
        _celebrate.value = false
    }

    fun secondsOn(day: Long): Int = _days.value[day] ?: 0

    /**
     * [day] 那一天的目标（秒）。**这一版所有达标判据都从这里取**
     * —— 别看当前设置，那正是 3.3.0 的错。
     */
    fun goalSecondsOn(day: Long): Int = GoalTimeline.secondsOn(_goalChanges.value, day)

    /** 同 [goalSecondsOn]，取分钟（界面与海报上只用得到分钟） */
    fun goalMinutesOn(day: Long): Int = goalSecondsOn(day) / 60

    /** 今天的目标（分钟）。界面副标题、档位高亮用它 */
    fun goalMinutesToday(today: Long = DayKey.today()): Int = goalMinutesOn(today)

    /** 明天起的目标（分钟）；没有待生效的改动就是 `null`。见 [GoalTimeline.pendingMinutes] */
    fun pendingGoalMinutes(today: Long = DayKey.today()): Int? =
        GoalTimeline.pendingMinutes(_goalChanges.value, today)

    fun isDone(day: Long): Boolean = secondsOn(day) >= goalSecondsOn(day)

    fun todaySeconds(): Int = secondsOn(DayKey.today())

    /** 已达标的日集合。连续天数、日历格子都由它派生 */
    fun doneDays(): Set<Long> =
        _days.value.filter { (day, sec) -> sec >= goalSecondsOn(day) }.keys

    fun currentStreak(today: Long = DayKey.today()): Int = Streak.current(doneDays(), today)

    fun bestStreak(): Int = Streak.best(doneDays())

    // ------------------------------------------------------------------
    // 与云端同步交换的两份数据（4.0.0）。**只在这里暴露「本机那份」。**
    // ------------------------------------------------------------------

    /** 上传用的：本机每天听了多少（只有本机能写的那一列） */
    fun mineSnapshot(): Map<Long, Int> = _mine.value

    /** 上传用的：本机知道的目标时间轴（`day → 秒`） */
    fun goalSnapshot(): Map<Long, Int> = _goalChanges.value

    /** 本机有没有值得上传的东西（全新设备 + 从未听过 = 不用打扰服务器） */
    fun hasLocalData(): Boolean = _mine.value.isNotEmpty() || _goalChanges.value.isNotEmpty()

    /**
     * 收下服务端的**全设备合计**，反算出「其它设备」那一份。
     *
     * ★ 反算是必须的：服务端返回的总量**含本机**。直接把它当 others 存下来，
     *   界面显示就会变成 `mine + (本机 + 别的设备)` —— 本机被算两遍，
     *   而症状是「数字莫名其妙变大」，最容易怀疑到同步头上却看不出为什么。
     *
     * `coerceAtLeast(0)` 是防御性的：服务端被清空（用户在另一台设备上
     * 点了「清空记录」）时，总量会小于本机的 mine —— 那时 others 该是 0。
     */
    fun applyServerTotals(totals: Map<Long, Int>) {
        val mineNow = _mine.value
        val next = SyncMerge.othersFrom(totals, mineNow)
        if (next == _others.value) return
        val editor = prefs.edit()
        (oldKeys() - next.keys).forEach { editor.remove(KEY_OTHERS + it) }
        next.forEach { (day, sec) -> editor.putInt(KEY_OTHERS + day, sec) }
        editor.apply()
        _others.value = next
        recompute()
        DiagLog.i("Check", "同步：其它设备的合计 ${next.size} 天（本机 ${mineNow.size} 天）")
    }

    private fun oldKeys(): Set<Long> = _others.value.keys

    /** 收下服务端的目标时间轴（目标只有一个作者，后写赢即可） */
    fun applyServerGoals(goals: Map<Long, Int>) {
        var changed = false
        val editor = prefs.edit()
        for ((day, sec) in goals) {
            if (_goalChanges.value[day] == sec) continue
            editor.putInt(KEY_GOAL + day, sec)
            changed = true
        }
        if (!changed) return
        editor.apply()
        _goalChanges.value = GoalTimeline.normalize(_goalChanges.value + goals)
    }

    /**
     * 改每日目标。**从明天起生效，历史一个字节都不动。**
     *
     * ⚠️ 3.3.0 的版本会重算全部历史，所以调用方有义务把掉下来的连续天数报出来。
     *    这一版**不再有那个副作用**，于是「报后果」这件事也不需要了 ——
     *    取而代之的是「说清什么时候生效」（由 `CheckInViewModel.setGoalMinutes` 负责）。
     *    这条改动同时也删掉了 `doneDaysIfGoal` / `streakIfGoal` 那两个
     *    「先看清后果」的辅助函数：它们存在的唯一理由就是那个副作用。
     *
     * @return 真的变了才返回 true。同一个值重复点不写盘、也不记日志
     */
    fun setGoalMinutes(minutes: Int, today: Long = DayKey.today()): Boolean {
        val next = GoalTimeline.withChange(_goalChanges.value, today, minutes)
        if (next == _goalChanges.value) return false

        // 整份重写：条目很少（用户改几次就有几条），而「按差异维护」在这里
        // 要多写一遍 set 比较，出错的代价（一份和内存对不上的 prefs）却更大
        val doomed = _goalChanges.value.keys - next.keys
        val editor = prefs.edit()
        doomed.forEach { editor.remove(KEY_GOAL + it) }
        next.forEach { (day, sec) -> editor.putInt(KEY_GOAL + day, sec) }
        editor.apply()
        _goalChanges.value = next

        DiagLog.act(
            "每日目标改为 ${GoalTimeline.minutesOn(next, today + 1)} 分钟" +
                "（明天起生效，今天仍按 ${GoalTimeline.minutesOn(next, today)} 分钟）",
        )
        return true
    }

    /** 有记录的总天数与总时长，给「打卡记录」页脚用 */
    fun activeDays(): Int = _days.value.count { it.value > 0 }

    fun totalSeconds(): Long = _days.value.values.sumOf { it.toLong() }

    fun posterFor(day: Long): String? = _posters.value[day]?.takeIf { it.isNotEmpty() }

    /**
     * 最近 [count] 天的达标情况，**旧 → 新**（最后一个元素是 [day] 本身）。
     *
     * 给海报上那排小格子用。它必须问数据层要，而不是「假设过去几天都达标」——
     * 那排格子的全部意义就是「看得出断没断」。
     */
    fun recentDone(day: Long, count: Int): List<Boolean> =
        (count - 1 downTo 0).map { isDone(day - it) }

    /**
     * 加一段收听时长，返回加完之后的状态。
     *
     * @param seconds 墙钟秒数（不是音频内容秒数 —— 见 [ListeningClock]）
     * @return 加完之后的快照。其中 [Tick.justCompleted] 只在**刚刚跨过目标线**
     *   的那一次为 true —— 它是「生成打卡海报」的唯一触发条件，
     *   放在这里算而不是让调用方自己比较，是为了让「只庆祝一次」这件事
     *   由数据层保证（进程重启、多入口调用都不会重复触发）。
     */
    fun addSeconds(day: Long, seconds: Int): Tick {
        if (seconds <= 0) return snapshot(day)

        // ★★ 4.1.1 修：这里原来取的是 `secondsOn(day)`（**合并值** = 本机 + 其它设备），
        //    然后把结果写回**本机那一列** —— 等于每累加一次就把别的设备那部分
        //    并进本机一次，而计时器每 5 秒调一次 ⇒ 数字爆炸式增长
        //    （用户录屏：8 秒内 617 分钟 → 667 分钟）。
        //    运算与判据都收在 [SyncMerge.accumulate] 里（那儿有单测）。
        val mineNow = _mine.value[day] ?: 0
        val acc = SyncMerge.accumulate(
            mineNow = mineNow,
            others = _others.value[day] ?: 0,
            delta = seconds,
            cap = MAX_DAY_SECONDS,
            // ⚠️ 跨线判据用**那一天**的目标（不是当前设置）：补记前几天的时长、
            //    或者跨天之后才落盘的那一次，都必须按那一天的标准算 ——
            //    否则「昨天听满 40 分钟」会因为今天把目标调高而不再触发庆祝
            goal = goalSecondsOn(day),
        )
        if (acc.mine == mineNow) return snapshot(day)
        persistSeconds(day, acc.mine)
        val justCompleted = acc.justCompleted
        val tick = snapshot(day)
        if (justCompleted) {
            // 置起庆祝信号。界面（若在）立刻弹分享页；不在也没关系 ——
            // 信号留在内存里，用户回到 App 时同样会看到这一次庆祝
            _celebrate.value = true
            DiagLog.i(
                "Check",
                // 报**合计**（用户看到的就是它），不是本机那一列
                "打卡成功 · ${DayKey.dateLabel(day)} · 累计 ${acc.total / 60} 分钟 · " +
                    "目标 ${goalSecondsOn(day) / 60} 分钟 · " +
                    "连续 ${tick.streak} 天（最长 ${bestStreak()} 天）",
            )
        }
        return tick
    }

    /** 记下某天的海报文件位置。生成失败时不写，下次进打卡页会重新生成 */
    fun markPoster(day: Long, path: String) {
        prefs.edit().putString(KEY_POSTER + day, path).apply()
        _posters.value = _posters.value + (day to path)
    }

    /**
     * 清空全部打卡记录。
     *
     * **不挂在「清空缓存」上** —— 打卡记录是用户攒出来的成果，和音频缓存
     * 完全是两件事。删缓存顺手把连续 30 天清零，是那种会被骂到卸载的操作。
     *
     * ⚠️ 3.3.0 起**按前缀删**，不再 `clear()` 整份 prefs。
     *
     * ⚠️ 3.4.0：每日目标也变成了按天的条目（`goal:<day>`），而它**同样不在
     *    被删的两个前缀里** —— 所以「清空记录」不会把用户给自己定的标准一起抹掉，
     *    也不会把「明天起改成 30 分钟」这个还没生效的改动一起取消。
     *
     *    这里**刻意不做「顺手清掉过去那些 goal 条目」的优化**：那会顺手改掉
     *    `goalOn(今天)`（比如用户的目标是从最早生效的 30 分钟，把过去的条目删掉
     *    就等于把今天也变回 40）。它们一共只有几条，留着比删对了便宜。
     */
    fun reset() {
        // ⚠️ 4.0.0 起连同 `others:` 一起清 —— 只清 mine 的话，界面上会继续
        //    显示「其它设备那 15 分钟」，用户看到的是「清空之后还剩一半」。
        //    （服务端那一份由调用方顺带清掉，见 `CheckInSync.wipeRemote()`。）
        val doomed = prefs.all.keys.filter {
            it.startsWith(KEY_SEC) || it.startsWith(KEY_POSTER) || it.startsWith(KEY_OTHERS)
        }
        val editor = prefs.edit()
        doomed.forEach { editor.remove(it) }
        editor.apply()
        _mine.value = emptyMap()
        _others.value = emptyMap()
        _days.value = emptyMap()
        _posters.value = emptyMap()
        // 记录都清了，挂在上面的庆祝信号也该失效 —— 否则会出现
        // 「打卡记录已清空」之后紧跟着弹一张「今日打卡成功」
        _celebrate.value = false
        DiagLog.w("Check", "打卡记录已被用户清空（${doomed.size} 条，保留每日目标）")
    }

    // ------------------------------------------------------------------

    /** 当前快照 */
    fun snapshot(day: Long, today: Long = DayKey.today()): Tick {
        val sec = secondsOn(day)
        val goal = goalSecondsOn(day)
        return Tick(
            day = day,
            seconds = sec,
            goalSeconds = goal,
            done = sec >= goal,
            justCompleted = false,
            streak = Streak.current(doneDays(), today),
        )
    }

    private fun persistSeconds(day: Long, seconds: Int) {
        // 只写内存里的 StateFlow、交给 SharedPreferences 异步落盘。
        // ⚠️ 必须 putInt 到一份新 key 上：SharedPreferences 内部对值本身是值拷贝，
        //    但同一份 editor 里连续 put 同一个 key 只有最后一次生效
        prefs.edit().putInt(KEY_SEC + day, seconds).apply()
        _mine.value = _mine.value + (day to seconds)
        recompute()
    }

    private fun loadDays(): Map<Long, Int> = prefs.all.mapNotNull { (key, value) ->
        if (!key.startsWith(KEY_SEC)) return@mapNotNull null
        val day = key.removePrefix(KEY_SEC).toLongOrNull() ?: return@mapNotNull null
        val sec = (value as? Int) ?: return@mapNotNull null
        day to sec
    }.toMap()

    /**
     * 读目标时间轴，并顺手把 3.3.0 那一个「当前目标」迁移过来。
     *
     * ⚠️ 迁移必须落在 [GoalTimeline.SINCE_ALWAYS]（从最早生效）—— 3.3.0 只存了一个
     *    数字，没有日期，也就无从知道它从哪天开始。假如把它当成「从今天起生效」，
     *    升级那一刻过去所有按旧目标算达标的日子会一起变成未达标、连续天数当场掉下来：
     *    **那正是用户这次要求修的东西，不能让升级自己制造一次。**
     */
    private fun loadGoalChanges(): Map<Long, Int> {
        val stored = prefs.all.mapNotNull { (key, value) ->
            if (!key.startsWith(KEY_GOAL)) return@mapNotNull null
            val day = key.removePrefix(KEY_GOAL).toLongOrNull() ?: return@mapNotNull null
            val sec = (value as? Int) ?: return@mapNotNull null
            day to sec
        }.toMap()
        if (stored.isNotEmpty()) return GoalTimeline.normalize(stored)

        // ⚠️ 老键 "goal" **不带冒号**，所以上面那个 `startsWith("goal:")` 扫不到它 ——
        //    这两行是一对，改任一个都要一起看（`verify-checkin-goal.py` 有断言守着）
        val legacy = prefs.getInt(KEY_LEGACY_GOAL, 0)
        if (legacy <= 0) return emptyMap()
        // 迁移完就把老键删掉：同一件事不留两个来源
        prefs.edit().remove(KEY_LEGACY_GOAL).apply()
        DiagLog.i("Check", "每日目标迁移：$legacy 分钟 → 从最早生效（历史不变）")
        return GoalTimeline.normalize(mapOf(GoalTimeline.SINCE_ALWAYS to legacy * 60))
    }

    private fun loadOthers(): Map<Long, Int> = prefs.all.mapNotNull { (key, value) ->
        if (!key.startsWith(KEY_OTHERS)) return@mapNotNull null
        val day = key.removePrefix(KEY_OTHERS).toLongOrNull() ?: return@mapNotNull null
        val sec = (value as? Int) ?: return@mapNotNull null
        if (sec <= 0) null else day to sec
    }.toMap()

    private fun loadPosters(): Map<Long, String> = prefs.all.mapNotNull { (key, value) ->
        if (!key.startsWith(KEY_POSTER)) return@mapNotNull null
        val day = key.removePrefix(KEY_POSTER).toLongOrNull() ?: return@mapNotNull null
        val path = value as? String ?: return@mapNotNull null
        if (path.isEmpty()) null else day to path
    }.toMap()

    companion object {
        private const val PREFS_NAME = "checkin"
        /**
         * 本机那一列。
         *
         * ⚠️ 名字（`sec:`）**故意保留旧值**：4.0.0 之前它就是「今天听了多少」，
         *    而那个语义正是现在的 mine —— 于是老用户盘上的数据
         *    **一个字都不用迁移**，第一次同步就自动被认领成「本机的贡献」。
         *    换成 `mine:` 就得写一次迁移，而迁移的风险远大于名字的整齐。
         */
        private const val KEY_SEC = "sec:"

        /** 其它设备的合计。登录后才有；未登录用户的这份永远为空 */
        private const val KEY_OTHERS = "others:"
        private const val KEY_POSTER = "poster:"

        /**
         * 目标改动条目的前缀：`goal:<epochDay>` → 从那天起的秒数。
         *
         * ⚠️ **带冒号**，于是它和 [KEY_LEGACY_GOAL]（`"goal"`，3.3.0 留下的那个
         * 无日期版本）是两个不同的键。这个区别必须一直成立 —— 否则
         * [loadGoalChanges] 会把老的那个当成一条 `day=0` 的条目读进来。
         */
        private const val KEY_GOAL = "goal:"

        /** 3.3.0 的 key：单个「当前目标」（**分钟**）。只在迁移时读一次，读到就删 */
        private const val KEY_LEGACY_GOAL = "goal"

        /**
         * 默认目标与可选范围都在 [GoalTimeline] 里 —— 这里**不再抄一份**。
         * 抄一份就是两个真相源：界面按预设档位显示、判据按另一个常量算，
         * 两者不同步时表现是「界面写着 40、进度条按 30 算」。
         */

        /** 单日秒数封顶（24 小时），防时钟跳变把统计带偏 */
        private const val MAX_DAY_SECONDS = 24 * 60 * 60
    }
}

/**
 * 一次计时追加之后的状态快照。
 *
 * @param justCompleted 本次**刚刚**跨过目标线。它是生成海报的唯一触发条件
 */
data class Tick(
    val day: Long,
    val seconds: Int,
    val goalSeconds: Int,
    val done: Boolean,
    val justCompleted: Boolean,
    val streak: Int,
) {
    val fraction: Float get() = (seconds.toFloat() / goalSeconds).coerceIn(0f, 1f)
    val remainingSeconds: Int get() = (goalSeconds - seconds).coerceAtLeast(0)
}
