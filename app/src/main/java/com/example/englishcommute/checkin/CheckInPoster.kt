package com.example.englishcommute.checkin

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import com.example.englishcommute.diag.DiagLog
import java.io.File
import java.io.FileOutputStream

/**
 * 打卡海报。**这是一个要发到微信里的东西**，不是应用内部的界面。
 *
 * ## 因此它和 App 内的排版规则不一样
 *
 * 应用内部那套「编辑部排版」刻意不用卡片、不用圆角、字号拉到 31sp 就封顶 ——
 * 那是为了「读文字时不被打扰」。而海报是**在聊天列表里被瞥一眼**的东西：
 * 它要在别人手机的一屏缩略图里就传达出「这家伙连着 21 天在听英语」。
 * 所以这里反过来：一个巨型数字、一块高饱和的琥珀、七格日历。
 *
 * 具体抄的是多邻国的**信息结构**（巨型连续天数 + 火苗 + 最近几天的小格子），
 * 配色仍然沿用应用自己的米白纸 + 赭石，不抄他那套绿。
 *
 * ## 为什么用 Canvas 手画而不是 XML 布局截图
 *
 * 布局截图要起一个 Activity、等测量布局、再 `drawToBitmap`，
 * 而且必须在前台（后台 Activity 会被限制）。Canvas 绘制是纯计算，
 * 可以在后台线程直接跑完 —— 它是在「跨过 40 分钟那一秒」被触发的，
 * 那一刻用户很可能正锁着屏听音频。
 *
 * ## 尺寸
 *
 * 1080×1440（3:4）。微信分享缩略图是正方形裁切，所以**核心信息必须在
 * 中央正方形内**：连续天数与「天」被特意放在 y = 480–860 这一段。
 */
object CheckInPoster {

    private const val W = 1080
    private const val H = 1440
    private const val MARGIN = 96f

    /**
     * 读回内存时的降采样倍率（见 [decodeForDisplay]）。
     *
     * 2 → 540×720。取 2 而不是 4 是有理由的：庆祝页上这张图能占到
     * 屏幕宽度的 82%，在 1080p 手机上约 890px 宽 —— 540 已经略低于它，
     * 再降到 270 就会在大字边缘看出毛糙。而海报的主角正是那个大号数字。
     */
    private const val DISPLAY_SAMPLE_SIZE = 2

    // 与应用浅色主题同源。海报固定用浅色 ——微信聊天背景多为浅色，
    // 一张深色海报在缩略图里会显得像截图而不是作品
    private const val PAPER = 0xFFFBFAF7.toInt()
    private const val INK = 0xFF16181C.toInt()
    private const val INK_MID = 0xFF3E4249.toInt()
    private const val INK_DIM = 0xFF6B6F77.toInt()
    private const val INK_FAINT = 0xFF8F8B82.toInt()
    private const val AMBER = 0xFF9C5F14.toInt()
    private const val AMBER_SOFT = 0xFFD9A05B.toInt()
    private const val HAIRLINE = 0xFFE6E1D5.toInt()

    /** 连续天数为 0 时不该出现海报，但真出现时也不能崩 */
    private const val PLACEHOLDER_STREAK = 1

    /** 海报上那排小格子画几天。与 [CheckInStore.recentDone] 的 count 对齐 */
    const val RECENT_DAYS = 7

    /**
     * 画一张海报并落盘。
     *
     * @param streak 连续打卡天数（海报的主角）
     * @param todaySeconds 当天累计收听秒数
     * @param recentDone 最近 7 天的达标情况，**下标 6 必须是今天**（旧 → 新）。
     *   由调用方从 [CheckInStore] 取 —— 海报不能自己猜「过去几天都达标了」，
     *   那样断过卡的记录看不出断过，而这排格子的全部意义就是「看得出断没断」。
     * @param goalMinutes 当天的目标分钟数。**必须由调用方传**：3.3.0 起它是用户
     *   自己设的（见 [CheckInStore.goalMinutes]），海报上那行字跟着它走
     * @return 写好的 PNG 文件
     */
    fun render(
        context: Context,
        day: Long,
        streak: Int,
        todaySeconds: Int,
        recentDone: List<Boolean>,
        goalMinutes: Int,
    ): File {
        val bitmap = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        draw(bitmap, day, streak, todaySeconds, recentDone, goalMinutes)

        val dir = File(context.cacheDir, DIR_NAME).apply { mkdirs() }
        val file = File(dir, "checkin-$day.png")
        // 先写临时文件再改名：生成过程被打断时不会留下半张图，
        // 而界面上的判据是「文件在不在」—— 半张图会被当成已完成
        // ⚠️ 后缀必须唯一（1.2.7）。固定成 `.png.tmp` 时，两个渲染方
        //    （庆祝页 / 打卡页 / ListeningClock）同时进来会写同一个文件，
        //    rename 出来的是半截 PNG —— 而「文件存在」正是界面判完成的依据
        val tmp = File.createTempFile("checkin-$day-", ".png.tmp", dir)
        FileOutputStream(tmp).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.fd.sync()
        }
        if (file.exists()) file.delete()
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
        bitmap.recycle()
        return file
    }

    /**
     * 确保这一天的海报**存在**，返回那个文件。
     *
     * ## 为什么这是一个公共函数，而不是各页面各写一遍
     *
     * 原本有两份「文件在就用、不在就 render」的代码，都写在打卡页的 ViewModel 里
     * （[com.example.englishcommute.ui.CheckInViewModel.ensureTodayPoster] 和 `shareToday`），
     * 而**庆祝页只等了 4 秒文件、等不到就放弃**。于是出现了一个荒谬的结果：
     *
     *   · 跨过 40 分钟那一秒弹出庆祝页 → 海报区空着、底部写「海报还在生成，稍等一下」
     *   · 退出庆祝页去打卡页 → 它**自己 render 了一次**，图立刻就有
     *
     * 三条路径本该是同一件事：「今天该有一张海报」。写成三份，缺一份就变成上面这样。
     *
     * ⚠️ 调用方一律要在**后台线程**调（Canvas 绘制 + PNG 压缩是几十到几百毫秒的 CPU 活）。
     *
     * @return 拿不到（渲染失败 / 磁盘满）返回 null，调用方自己决定怎么退化
     */
    fun ensureFile(context: Context, store: CheckInStore, day: Long): File? {
        // ⚠️ 必须串行。三个入口（[com.example.englishcommute.checkin.ListeningClock]
        //    跨线那一刻、庆祝页、打卡页）会在同一秒内都来问一次，
        //    而它们写的是**同一个 tmp 文件**：两个线程同时压缩 → 互相盖掉 →
        //     rename 出一张半截 PNG，而界面上的判据是「文件在不在」，
        //     半截图会被当成已完成。
        //
        // 双检：拿到锁之后再看一次文件，第二个进来的直接返回，不重复渲染
        synchronized(ensureLock) {
            val existing = existingFor(store, day)
            if (existing != null) return existing

            return runCatching {
                render(
                    context = context,
                    day = day,
                    streak = store.currentStreak(day),
                    todaySeconds = store.secondsOn(day),
                    recentDone = store.recentDone(day, RECENT_DAYS),
                    // 3.3.0：目标可自定义了，所以它必须**当参数传进来**，
                    // 不能在画的时候去读那个常量 —— 海报上「目标 N 分钟」这行字
                    // 一旦和用户设的值对不上，就是一张印着别人标准的图。
                    //
                    // ⚠️ 3.4.0：取的是**那一天**的目标（`goalMinutesOn(day)`），
                    //    不是「当前设置」。补生成海报时（今天达标、但图被系统清了缓存）
                    //    这一天就是今天，两者相同；但只要这张图是给**过去某一天**画的，
                    //    「当前设置」就会印错 —— 而它是用户会截图发出去的东西。
                    goalMinutes = store.goalMinutesOn(day),
                ).also { store.markPoster(day, it.absolutePath) }
            }
                .onSuccess { DiagLog.i("Check", "海报已生成：${it.name}") }
                .onFailure { DiagLog.w("Check", "海报生成失败：${it.message}") }
                .getOrNull()
        }
    }

    /** 今天这一张已经在磁盘上了吗。判据是**文件本身**（长度 > 0），不是记录里的路径 */
    private fun existingFor(store: CheckInStore, day: Long): File? {
        val path = store.posterFor(day) ?: return null
        val f = File(path)
        return f.takeIf { it.isFile && it.length() > 0L }
    }

    /** 见 [ensureFile] 的说明。所有渲染都从这里过，所以放在 object 级 */
    private val ensureLock = Any()

    /**
     * 把落盘的海报读回内存，**按显示用途降采样**。
     *
     * ## 为什么不直接 `BitmapFactory.decodeFile(path)`
     *
     * 1.2.0 之前打卡页和庆祝页各自裸调 `decodeFile`，没有任何 Options ——
     * 于是 1080×1440 按 ARGB_8888 整张解进来，**单份 5.93 MB**。
     * 更糟的是打卡页那份的生命周期：`CheckInViewModel` 挂在 Activity 作用域，
     * 而三页签的 pager 预排了相邻页 → **App 一启动它就被创建、就解码**，
     * 然后一直常驻到 Activity 销毁。达标之后每天白付 6 MB。
     * 庆祝页弹出时两份并存，峰值 11.9 MB。
     *
     * 这张图是**纯色底 + 大字**，而它出现的地方（卡片、弹层）宽度都远小于
     * 1080px。`inSampleSize = 2` 得到 540×720，肉眼看不出差别；
     * 再加 RGB_565（海报没有透明通道）每像素从 4 字节降到 2 字节。
     *
     *   1080×1440×4 = 5.93 MB  →  540×720×2 = 0.78 MB（**7.6 倍**）
     *
     * @return 解码失败返回 null（文件可能被系统清了 —— 它在 cacheDir 里）
     */
    fun decodeForDisplay(path: String): Bitmap? {
        val opts = BitmapFactory.Options().apply {
            inSampleSize = DISPLAY_SAMPLE_SIZE
            // 纯色底 + 大字，没有透明通道要保 —— 每像素省一半
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return BitmapFactory.decodeFile(path, opts)
    }

    private fun draw(
        bitmap: Bitmap,
        day: Long,
        streak: Int,
        todaySeconds: Int,
        recentDone: List<Boolean>,
        goalMinutes: Int,
    ) {
        val c = Canvas(bitmap)
        c.drawColor(PAPER)

        val sans = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        val sansBold = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        val serifBold = Typeface.create(Typeface.SERIF, Typeface.BOLD)

        fun paint(size: Float, color: Int, typeface: Typeface = sans): Paint = Paint().apply {
            isAntiAlias = true
            this.color = color
            textSize = size
            this.typeface = typeface
        }

        // ---- 页眉：左边是谁，右边是哪天 ----
        paint(40f, INK_DIM, sansBold).apply {
            letterSpacing = 0.18f
        }.let { c.drawText("通勤英语 · 每日打卡", MARGIN, 140f, it) }
        paint(36f, INK_FAINT).apply { textAlign = Paint.Align.RIGHT }
            .let { c.drawText("${DayKey.dateLabel(day)} ${DayKey.weekdayLabel(day)}", W - MARGIN, 140f, it) }
        c.drawRect(MARGIN, 178f, W - MARGIN, 180f, paint(1f, HAIRLINE))

        // ---- 主角：连续天数 ----
        //
        // ## 这里原本画的是一个火苗（多邻国的标志），被撤掉了
        //
        // 试了三版都不对：水滴 → 甜甜圈 → 梨形。原因是火苗轮廓**靠猜贝塞尔
        // 控制点做不出来** —— 它需要的是图标库里那条已经调好的路径，
        // 而这个工程没有引入矢量图库的预算（见 Glyph.kt 里同样的取舍）。
        //
        // 换成「巨型数字 + 短琥珀横线」之后反而更贴这套视觉：数字本身就是
        // 最强的视觉信号，横线替代图标承担分割，而且它是一块矩形 —— 画不歪。
        // 版式已用 `tools/preview-checkin-poster.py` 渲染核对过。
        val heroCenterY = 480f
        val hero = paint(320f, AMBER, serifBold).apply { textAlign = Paint.Align.CENTER }
        val shown = if (streak > 0) streak else PLACEHOLDER_STREAK
        drawCentered(c, shown.toString(), W / 2f, heroCenterY, hero)

        paint(50f, INK, sansBold).apply { textAlign = Paint.Align.CENTER; letterSpacing = 0.10f }
            .let { drawCentered(c, "天连续打卡", W / 2f, heroCenterY + 200f, it) }

        c.drawRect(W / 2f - 96f, 790f, W / 2f + 96f, 798f, paint(1f, AMBER))

        paint(42f, INK_MID).apply { textAlign = Paint.Align.CENTER }
            .let {
                drawCentered(
                    c,
                    "今天听了 ${DayKey.minutesLabel(todaySeconds)} · 目标 $goalMinutes 分钟",
                    W / 2f, 900f, it,
                )
            }

        c.drawRect(MARGIN, 990f, W - MARGIN, 992f, paint(1f, HAIRLINE))

        // ---- 最近 7 天：多邻国那种小格子，一眼看得出断没断 ----
        paint(30f, INK_FAINT).apply { textAlign = Paint.Align.CENTER; letterSpacing = 0.14f }
            .let { drawCentered(c, "最近 7 天", W / 2f, 1052f, it) }

        val cell = 96f
        val gap = 24f
        val rowW = cell * 7 + gap * 6
        val left = (W - rowW) / 2f
        val cellTop = 1090f
        for (i in 0 until 7) {
            // i = 0 是 6 天前 … i = 6 是今天
            val x = left + i * (cell + gap)
            // 达标 = 实心琥珀；没达标 = 空心细线。海报上没有空间写数字，
            // 填充与否是最快的传达方式 —— 而它必须来自真实记录
            val done = recentDone.getOrElse(i) { false }
            if (done) {
                c.drawRoundRect(x, cellTop, x + cell, cellTop + cell, 10f, 10f, paint(1f, AMBER_SOFT))
            } else {
                c.drawRoundRect(
                    x, cellTop, x + cell, cellTop + cell, 10f, 10f,
                    paint(2f, HAIRLINE).apply { style = Paint.Style.STROKE },
                )
            }
            if (i == 6) {
                // 今天单独描一圈：刚打完卡的这一格要和左边几天明确区分开
                c.drawRoundRect(
                    x - 7f, cellTop - 7f, x + cell + 7f, cellTop + cell + 7f,
                    15f, 15f, paint(3f, AMBER).apply { style = Paint.Style.STROKE },
                )
            }
        }

        // ---- 页脚：只留一句口号 ----
        //
        // 这里原本还有第二行「端侧转写 · N 个英语新闻源 · 无需上传」，删掉了。
        // 那是**产品参数**，不是海报该说的话：这张图的读者是微信里收到图的
        // 那个人，而「端侧转写」「无需上传」是工程内部词汇 —— 他看不懂，
        // 甚至可能反过来想「为什么需要特意强调不上传」。
        // 多邻国的底部也只有一行品牌/口号，不是功能清单。
        // 品牌名已经在页眉左上角（「通勤英语 · 每日打卡」），不必再署名一次。
        paint(38f, INK_FAINT).apply { textAlign = Paint.Align.CENTER; letterSpacing = 0.04f }
            .let { drawCentered(c, "把通勤变成听力课", W / 2f, 1314f, it) }
    }

    /**
     * 按「视觉中心」而不是基线画文字。
     *
     * 直接用 `drawText` 的 y 参数是**基线**，而基线的位置随字号和字形变化 ——
     * 大号数字（300sp）的字面中心比基线高约 0.35em。不校正的话，
     * 连续天数会整体偏上，下方的「天连续打卡」和它挤在一起。
     */
    private fun drawCentered(c: Canvas, text: String, cx: Float, cy: Float, p: Paint) {
        val fm = p.fontMetrics
        val baseline = cy - (fm.ascent + fm.descent) / 2f
        c.drawText(text, cx, baseline, p)
    }

    const val DIR_NAME = "posters"
}
