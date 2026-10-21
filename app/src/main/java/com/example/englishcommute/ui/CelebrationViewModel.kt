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
import com.example.englishcommute.diag.DiagLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 打卡成功庆祝页的状态。
 *
 * ## 为什么单独一个 ViewModel，而不是复用 [CheckInViewModel]
 *
 * [CheckInViewModel] 一旦被创建就会去解码当天的海报（`ensureTodayPoster`）。
 * 那个逻辑对打卡页是对的 —— 那一页本来就是来看海报的；但对**每个**用户来说，
 * 只要今天达标了，从进入 App 到退出都会一直持有一个 1080×1440 的 Bitmap
 * （ARGB_8888 约 6 MB）。庆祝页是几十秒就消失的东西，不该让整机陪着付这份内存。
 *
 * 所以这里做成**按需加载**：只有 `celebrate` 真的翻成 true 时才去取图。
 *
 * ## 为什么要「等」一下海报文件（以及等不到时怎么办）
 *
 * 海报由 [com.example.englishcommute.checkin.ListeningClock] 在跨过目标线那一秒
 * 开始画（几十到几百毫秒）。而庆祝信号是**同一秒**从数据层置起的 ——
 * 两边几乎同时发生，界面上大概率是「信号到了、图还没写完」。
 *
 * 所以先轮询一小会儿（[POSTER_WAIT_TRIES] × [POSTER_WAIT_MS]）——
 * 这是**省一次重复渲染**的优化，不是正确性的依赖。
 *
 * ## ⚠️ 1.2.7：等不到**必须自己补一张**，不能就这么空着
 *
 * 原来这里等 4 秒就放弃，理由是「自己再渲染一次等于两个渲染入口」。
 * 但真正的第二个入口是**打卡页** —— 它本来就有「文件不在就 render」的兜底。
 * 于是同一个下午出现了这样一件事：
 *
 *   · 跨过 40 分钟弹出庆祝页 → 海报区空着，底部写「海报还在生成，稍等一下」
 *   · 用户只能退出去、进打卡页 → 图立刻就在那里
 *
 * 「不做第二个入口」防的是**分叉**，而这里分叉早就发生了，只是发生得不对称。
 * 现在三个入口（本页 / 打卡页 / ListeningClock）统一走
 * [CheckInPoster.ensureFile]：编排只有一份，「等」退化成纯粹的优化。
 */
class CelebrationViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as EnglishCommuteApp).container
    private val store: CheckInStore = container.checkIn

    /** 是否正在庆祝。与数据层同一个 StateFlow，界面订阅它决定弹不弹 */
    val celebrate: StateFlow<Boolean> = store.celebrate

    private val _poster = MutableStateFlow<Bitmap?>(null)
    val poster: StateFlow<Bitmap?> = _poster.asStateFlow()

    /** 正在取图 / 正在分享。两者共用，因为它俩不会同时发生 */
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _hint = MutableStateFlow<String?>(null)
    val hint: StateFlow<String?> = _hint.asStateFlow()

    /** 分享时用的那张图。与 [poster] 是同一个文件，避免再拼一次路径 */
    private var posterFile: File? = null

    init {
        viewModelScope.launch {
            store.celebrate.collect { on ->
                if (!on) {
                    // 用户关掉庆祝页：把图放掉。不清的话它会一直挂到 ViewModel 销毁
                    _poster.value = null
                    posterFile = null
                    _hint.value = null
                    return@collect
                }
                loadPoster()
            }
        }
    }

    /**
     * 庆祝页上要显示的两个数字。
     *
     * 取「信号置起那一刻」的值，之后**不跟着计时器走**：用户盯着看的时候
     * 数字一直在涨，会让「今天听了 40 分钟」这句话失去确定性。
     * 调用方用 `remember` 记住首次取值即可。
     */
    fun currentStreak(): Int = store.currentStreak()

    fun todaySeconds(): Int = store.secondsOn(DayKey.today())

    /** 用户点右上角的叉，或按系统返回键 */
    fun dismiss() {
        store.consumeCelebrate()
    }

    /**
     * 分享今天的海报。
     *
     * 走 [CheckInShare]（先单独试微信、失败再退回落选择器）——
     * 和打卡页里那个按钮是**同一条路径**，不是「差不多」的另一套。
     */
    fun share(context: Context) {
        val file = posterFile
        if (file == null || !file.isFile || file.length() <= 0L) {
            _hint.value = "海报还在生成，稍等一下"
            return
        }
        if (_busy.value) return

        _busy.value = true
        // 必须在主线程调：shareImage 里是 startActivity
        val caption = CheckInShare.caption(currentStreak(), todaySeconds())
        val ok = runCatching { CheckInShare.shareImage(context, file, caption) }
            .onFailure { DiagLog.w("Check", "庆祝页分享失败：${it.message}") }
            .getOrDefault(false)
        if (!ok) _hint.value = "没有找到可以分享的应用"
        _busy.value = false
    }

    fun clearHint() {
        _hint.value = null
    }

    /** 取当天的海报图。等不到就返回 null，界面退化成不带图的庆祝 */
    private suspend fun loadPoster() {
        val day = DayKey.today()
        if (!store.isDone(day)) return

        _busy.value = true
        val file = withContext(Dispatchers.IO) {
            var found: File? = null
            // 先给 ListeningClock 一小会儿：它几乎和我们同时被唤醒（见类注释）。
            // 让一步就能省掉一次重复的 Canvas 绘制 + PNG 压缩
            for (attempt in 0 until POSTER_WAIT_TRIES) {
                val path = store.posterFor(day)
                val f = path?.let { File(it) }?.takeIf { it.isFile && it.length() > 0L }
                if (f != null) {
                    found = f
                    break
                }
                delay(POSTER_WAIT_MS)
            }
            if (found != null) {
                DiagLog.i("Check", "庆祝页取到海报：${found.name}")
                found
            } else {
                // ★ 等不到就自己补一张。ensureFile 里是串行的双检：
                //   ListeningClock 若还在画，这里会等它画完再看到文件，
                //   不会出现两份渲染互相盖
                DiagLog.i("Check", "庆祝页等不到海报，自己画一张")
                CheckInPoster.ensureFile(getApplication(), store, day)
            }
        }

        posterFile = file
        _poster.value = file?.let { f ->
            runCatching { CheckInPoster.decodeForDisplay(f.absolutePath) }
                .onFailure { DiagLog.w("Check", "庆祝页海报解码失败：${it.message}") }
                .getOrNull()
        }
        if (file != null && _poster.value == null) {
            DiagLog.w("Check", "庆祝页海报解码返回 null：${file.length()} 字节")
        }
        _busy.value = false
    }

    private companion object {
        /**
         * 等海报落盘的次数与间隔：6 × 150ms ≈ 0.9 秒。
         *
         * 原来给到 4 秒 —— 那时等不到就什么都没有，所以往长里赌。
         * 现在等不到会**自己补生成**，长等只剩下一个副作用：
         * 庆祝页弹出后近 1 秒不给用户任何反馈。0.9 秒足够覆盖
         * 「ListeningClock 正在同一个文件上压 PNG」这件事。
         */
        const val POSTER_WAIT_TRIES = 6
        const val POSTER_WAIT_MS = 150L
    }
}
