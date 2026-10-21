package com.example.englishcommute.ui

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.englishcommute.EnglishCommuteApp
import com.example.englishcommute.checkin.CheckInPoster
import com.example.englishcommute.checkin.CheckInShare
import com.example.englishcommute.checkin.CheckInStore
import com.example.englishcommute.checkin.DayKey
import com.example.englishcommute.checkin.GoalTimeline
import com.example.englishcommute.checkin.ListeningClock
import com.example.englishcommute.checkin.Streak
import com.example.englishcommute.diag.DiagLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 打卡页状态。
 *
 * ## 为什么大部分计算放在这里而不是界面里
 *
 * 「连续多少天」这类数字依赖 `days`（会在播放时每 5 秒变一次）。
 * 如果界面每次重组都重算 21 天格子 + 连续段，列表滚动时会持续做无用功；
 * 更要紧的是——**跨午夜时「今天」会变**，而界面不会因此重组。
 * 所以这里持有一个自己会走的 [today]，让它成为唯一的时间来源。
 */
class CheckInViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as EnglishCommuteApp).container
    private val store: CheckInStore = container.checkIn

    /** epochDay → 已听秒数。播放时每 5 秒推一次，界面直接订阅 */
    val days: StateFlow<Map<Long, Int>> = store.days

    /** epochDay → 海报文件路径 */
    val posters: StateFlow<Map<Long, String>> = store.posters

    /**
     * 「今天」是哪一天。**自己走**，不依赖界面重组 ——
     * 半夜听着音频跨过零点时，界面上如果还按旧日期算，
     * 就会出现「今天已经达标了但还显示 8 分钟」这种自相矛盾的画面。
     */
    private val _today = MutableStateFlow(DayKey.today())
    val today: StateFlow<Long> = _today.asStateFlow()

    private val _poster = MutableStateFlow<Bitmap?>(null)
    val poster: StateFlow<Bitmap?> = _poster.asStateFlow()

    /**
     * **今天**的目标（分钟）。界面直接订阅它 —— 进度条、日历格子的深浅、
     * 「还差多少」全都是相对目标算的，所以它们必须跟着它一起变。
     *
     * ⚠️ 它是从 [CheckInStore.goalChanges] 与「今天」一起派生的，不是一个存下来的值。
     *    3.4.0 之前它是「当前设置」；现在「当前设置」只决定**明天**，
     *    所以这里必须问「今天该按多少算」。写成 `store.goalMinutesToday()` 快照的话，
     *    跨午夜时它会停在昨天那个值上，而进度条已经在按新的一天算了。
     */
    val goalMinutes: StateFlow<Int> =
        combine(store.goalChanges, _today) { changes, day ->
            GoalTimeline.minutesOn(changes, day)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = store.goalMinutesToday(),
        )

    /**
     * **明天起**的目标（分钟）；没有待生效的改动就是 `null`。
     *
     * 「每日目标」那一块靠它区分「已经是这个值」和「刚改成这个值、明天生效」——
     * 没有它的话，用户点完只能看到一句什么都没变的提示。
     */
    val tomorrowGoalMinutes: StateFlow<Int?> =
        combine(store.goalChanges, _today) { changes, day ->
            GoalTimeline.pendingMinutes(changes, day)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = store.pendingGoalMinutes(),
        )

    /** 几档可选的目标。取自 [GoalTimeline]，界面不自己列一遍 */
    val goalPresets: List<Int> get() = GoalTimeline.PRESETS

    /** [day] 那一天的目标（分钟）。日历与记录列表**逐天**用它，不共用一个值 */
    fun goalMinutesOn(day: Long): Int = store.goalMinutesOn(day)

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** 一次性提示（分享失败 / 已生成海报之类），界面显示后调用 [clearHint] */
    private val _hint = MutableStateFlow<String?>(null)
    val hint: StateFlow<String?> = _hint.asStateFlow()

    private var posterDay: Long = -1L

    init {
        viewModelScope.launch {
            while (isActive) {
                delay(TODAY_REFRESH_MS)
                val t = DayKey.today()
                if (t != _today.value) {
                    DiagLog.i("Check", "跨天：${DayKey.dateLabel(t)} 开始新的一天")
                    _today.value = t
                }
            }
        }
        // 进页面就确保今天的海报在位（可能上次生成失败、或被系统清了缓存）
        ensureTodayPoster()
    }

    // ------------------------------------------------------------------
    // 派生数据
    // ------------------------------------------------------------------

    fun todaySeconds(): Int = days.value[_today.value] ?: 0

    fun currentStreak(): Int = Streak.current(doneKeys(), _today.value)

    fun bestStreak(): Int = Streak.best(doneKeys())

    /** 有记录的天数（听过就算，不要求达标） */
    fun activeDays(): Int = days.value.count { it.value > 0 }

    fun totalSeconds(): Long = days.value.values.sumOf { it.toLong() }

    fun isDone(day: Long): Boolean = (days.value[day] ?: 0) >= store.goalSecondsOn(day)

    /**
     * 已达标的那些天。
     *
     * ⚠️ 逐天取 `goalSecondsOn(day)`，**不是**取今天的那个值再套到所有天上 ——
     *    后者正是 3.3.0 的行为：改一次目标，整条历史跟着重算。
     */
    private fun doneKeys(): Set<Long> =
        days.value.filter { (day, sec) -> sec >= store.goalSecondsOn(day) }.keys

    /**
     * 打卡日历：从 [`today`-COUNT+1] 到今天，**按真实的星期对齐**。
     *
     * 21 天正好是三周，所以「每行 7 格、最后一行最后一个是今天」这个排法
     * 让每一列都落在同一个星期几上 —— 表头只需要一行，而且不会错位。
     * 这是选 21 天而不是「最近 20 天」的理由。
     */
    fun calendarDays(count: Int = CALENDAR_DAYS): List<Long> {
        val end = _today.value
        return (count - 1 downTo 0).map { end - it }
    }

    // ------------------------------------------------------------------
    // 海报
    // ------------------------------------------------------------------

    /**
     * 确保今天的海报存在，并解码出来给界面用。
     *
     * 三种情况都会走到这里：
     *  1. 今天刚达标 —— 正常路径（[ListeningClock] 在跨过目标线那一刻已经生成过，
     *     这里是幂等的第二次确认）
     *  2. 上一次生成失败（磁盘满、被中断）
     *  3. 海报文件被系统清理了缓存目录
     *
     * 幂等靠的是「文件存在就直接用」—— 不去比较 streak，因为打卡数一旦变化，
     * 海报里的数字就过时了，重新生成反而是对的。
     */
    fun ensureTodayPoster() {
        val day = _today.value
        if (!isDone(day)) {
            // 今天没达标就不该有海报；把上一张的解码结果也清掉，
            // 否则界面会继续显示昨天那张，看起来像「今天已经打过了」
            if (posterDay != day) {
                _poster.value = null
                posterDay = -1L
            }
            return
        }
        if (posterDay == day && _poster.value != null) return

        viewModelScope.launch {
            _busy.value = true
            val bmp = withContext(Dispatchers.IO) {
                // 「文件在就用、不在就现渲染」这条编排收在 CheckInPoster.ensureFile
                // 一处（1.2.7）。原来它只写在这里，而庆祝页只「等文件」——
                // 于是同一个下午出现了「庆祝页空着 / 打卡页一进去就有图」，见那个函数的说明
                val file = CheckInPoster.ensureFile(getApplication(), store, day)
                    ?: return@withContext null
                // 走 decodeForDisplay 而不是裸 decodeFile：
                // 后者是 1080×1440 ARGB_8888 = 5.93 MB，而这个 ViewModel
                // 挂在 Activity 作用域、开 App 就被 pager 预排创建 ——
                // 那 6 MB 会一路常驻。降采样后是 0.78 MB
                runCatching { CheckInPoster.decodeForDisplay(file.absolutePath) }
                    .onFailure { DiagLog.w("Check", "海报读取失败：${it.message}") }
                    .getOrNull()
            }
            if (bmp != null) {
                posterDay = day
                _poster.value = bmp
            }
            _busy.value = false
        }
    }

    /** 分享今天的海报。没生成过就先生成（`share` 之前必须有一张图） */
    fun shareToday(context: Context) {
        val day = _today.value
        if (!isDone(day)) {
            _hint.value = "今天还没达标，听满 ${store.goalMinutesToday()} 分钟就能生成海报"
            return
        }
        viewModelScope.launch {
            _busy.value = true
            val file = withContext(Dispatchers.IO) {
                CheckInPoster.ensureFile(getApplication(), store, day)
            }
            if (file == null) {
                _hint.value = "海报生成失败，请稍后再试"
                _busy.value = false
                return@launch
            }
            val caption = CheckInShare.caption(currentStreak(), todaySeconds())
            val ok = withContext(Dispatchers.Main) { CheckInShare.shareImage(context, file, caption) }
            if (!ok) _hint.value = "没有找到可以分享的应用"
            _busy.value = false
        }
    }

    /**
     * 改每日目标（3.3.0 引入，3.4.0 改成「明天起生效」）。
     *
     * ## 3.4.0 改掉了什么，以及为什么
     *
     * 3.3.0 的语义是「达标按**当前**目标现算」，于是**改目标会重算全部历史** ——
     * 把目标从 40 调到 60，过去那些「听满 40 就算过」的日子一起失效，连续天数当场掉。
     * 当时这里为此加了一句「连续打卡 $before 天 → $after 天」的提醒。
     *
     * 用户反馈很直接：**要第二天才生效，别动历史记录。**
     *
     * 数据层因此换成了 [GoalTimeline] 的按天时间轴（见 [CheckInStore] 的类注释），
     * 这里的职责也跟着变了：
     *
     * | | 3.3.0 | 3.4.0 |
     * |---|---|---|
     * | 提示说的是 | 「连续 30 天 → 8 天」（报后果） | 「明天起改为 30 分钟 · 今天仍按 40 分钟」（报生效时间） |
     * | 要重画海报吗 | 要（今天达标与否可能整个反过来） | **不要**（今天的阈值没变） |
     *
     * ⚠️ 海报那一条是这一版最容易顺手写错的地方：3.3.0 留着 `_poster.value = null`
     *    加 `ensureTodayPoster()`，看起来「保险」，实际是白重画一张内容完全相同的图
     *    （今天的目标没变）。**判据：改目标只写 `today + 1` 的条目** ——
     *    只要那条成立，今天的海报就与这次改动无关。
     */
    fun setGoalMinutes(minutes: Int) {
        val todayBefore = store.goalMinutesToday()
        if (!store.setGoalMinutes(minutes)) {
            // 值没变、也没有待生效的改动：那一下就落在**已经高亮的那一档**上，
            // 界面上本来就是选中态，不需要再说什么
            return
        }
        _hint.value = store.pendingGoalMinutes()?.let { tomorrow ->
            "明天起改为 $tomorrow 分钟 · 今天仍按 $todayBefore 分钟"
        } ?: "已改回原样 · 明天起仍按 $todayBefore 分钟"
    }

    /**
     * 清空打卡记录。
     *
     * 界面上是「点两次」确认（第一次变成确认文案），不用对话框 ——
     * 这个页面本身没有别的东西，一个系统弹窗反而比就地确认更突兀。
     *
     * ⚠️ 它**不清每日目标**（见 `CheckInStore.reset`）：记录是成果，目标是标准。
     */
    fun resetAll() {
        store.reset()
        _poster.value = null
        posterDay = -1L
        // ⚠️ 登录之后**必须连服务端一起清**：只清本地的话，下一次同步会把
        //    服务端那几行原样搬回来 —— 用户看到的是
        //    「清空之后过一会儿又自己回来了」，那个按钮就成了骗人的。
        viewModelScope.launch {
            val keep = "每日目标保留为 ${store.goalMinutesToday()} 分钟"
            if (!container.account.signedIn.first()) {
                _hint.value = "打卡记录已清空 · $keep"
                return@launch
            }
            val remoteOk = container.checkInSync.wipeRemote()
            _hint.value = if (remoteOk) {
                "所有设备上的打卡记录已清空 · $keep"
            } else {
                "本机已清空，但云端没清掉（连不上服务器）—— 联网后再点一次，否则它会同步回来"
            }
        }
    }

    fun clearHint() {
        _hint.value = null
    }

    companion object {
        /** 21 天 = 3 周，正好让日历的每一列落在同一个星期几上 */
        const val CALENDAR_DAYS = 21

        /** 「今天」的刷新间隔。跨午夜的判定晚半分钟无伤大雅 */
        private const val TODAY_REFRESH_MS = 30_000L
    }
}
