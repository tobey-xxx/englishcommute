package com.example.englishcommute.ui

import com.example.englishcommute.net.ServerConfig
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.layout.heightIn
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.englishcommute.content.Episode
import com.example.englishcommute.content.EpisodeStatus
import com.example.englishcommute.content.FeedSources
import com.example.englishcommute.content.SourceResult
import com.example.englishcommute.diag.DiagLog
import com.example.englishcommute.player.SrtTrack
import com.example.englishcommute.sync.SyncPhase
import com.example.englishcommute.sync.SyncState
import com.example.englishcommute.ui.theme.Amber
import com.example.englishcommute.ui.theme.AmberFaint
import com.example.englishcommute.ui.theme.AppText
import com.example.englishcommute.ui.theme.Bg
import com.example.englishcommute.ui.theme.BgRaised
import com.example.englishcommute.ui.theme.Card
import com.example.englishcommute.ui.theme.CardChip
import com.example.englishcommute.ui.theme.CardEdge
import com.example.englishcommute.ui.theme.Crimson
import com.example.englishcommute.ui.theme.Glyph
import com.example.englishcommute.ui.theme.GlyphIcon
import com.example.englishcommute.ui.theme.Hairline
import com.example.englishcommute.ui.theme.Ink
import com.example.englishcommute.ui.theme.InkDim
import com.example.englishcommute.ui.theme.InkFaint
import com.example.englishcommute.ui.theme.InkMid
import com.example.englishcommute.ui.theme.LinkText
import com.example.englishcommute.ui.theme.Metric
import com.example.englishcommute.ui.theme.Motion
import com.example.englishcommute.ui.theme.Olive
import com.example.englishcommute.ui.theme.Radius
import com.example.englishcommute.ui.theme.Rule
import com.example.englishcommute.ui.theme.Space
import com.example.englishcommute.ui.theme.formatBytes
import com.example.englishcommute.ui.theme.pressFeedback
import com.example.englishcommute.ui.theme.pressScale
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 列表里排在第一个条目之前的那几个「非条目」item 的数量。
 *
 * 存在的唯一理由是 [LazyColumn] 的索引是**整列**的，而我们要滚到的是「第几条素材」。
 * 两者的差值就是这个常量。**改动列表头部结构时必须同步改它** ——
 * 少算一条的表现是「退回素材库后滚到了隔壁一行」，看起来像定位不准，
 * 实际是数错了。所以头部的 `item(key = ...)` 也列在这里当对照：
 *
 *   `masthead`（刊头 + 更新动作）/ `status`（同步状态）/ `tabs`（筛选签）
 *
 * ⚠️ 1.2.0 从 4 改成 3：刊头与更新动作合成了一个 item。
 *    这正是这个常量存在的意义 —— 它是一处**必须手改**的耦合点，
 *    而 `tools/verify-ui-flows.py` 会从源码里数一遍 header item
 *    再和它对照，改漏了会被拦下。
 *
 * ⚠️ 1.2.2：`tabs` 从 `item` 换成了 `stickyHeader`，但**它仍然是一个 item** ——
 *    LazyColumn 的索引照旧从 0 数，所以这个常量还是 3。
 *    「吸顶」改的是它停在哪，不是它排在第几。
 *
 * ★ 2.1.0：**最后一个头部 item 就是筛选签**，所以「筛选签吸顶位」= 这个常量 − 1。
 *    `LibraryAnchor` 的回落落点正是这么算的（它只认 `headerItems`，不认识筛选签）。
 *    1.2.x 这里还有一个 `TABS_ITEM_INDEX`，2.1.0 删了 —— 留着一个没人用的下标常量，
 *    下一个人会以为「落点还得自己算」。
 */
private const val LIBRARY_HEADER_ITEMS = 3

/**
 * 空状态块占屏幕这么高。
 *
 * ## 它同时管两件事
 *
 * 观感上：空页面本来就该留白，不能让一行提示孤零零贴在顶上。
 *
 * 功能上：**吸顶的筛选签要吸得住，前提是列表能滚得动** ——
 * 需要 `内容总高 ≥ 一屏 + 筛选签自己的高度`。空筛选（收藏还没加过东西）
 * 时列表只有几个头部 item，够不到这个条件，切过去的瞬间就被钳回顶部、
 * 筛选签掉回刊头下面。撑到一屏之后总高必然够，筛选签就稳住了。
 *
 * ## ⚠️ 1.2.5 试过「按行数给整个列表垫一屏底部留白」，1.2.7 回退了
 *
 * 那个做法能覆盖「空」也覆盖「只有两三条」，但有三个后果：
 *
 *   · **整页空白**：滚动位置一旦落进那块留白，屏幕上什么都没有 ——
 *     只剩一个吸顶的筛选签和右侧的滚动条（用户报的「切回全部变空白页」）
 *   · 列表多出一段「可以滚进去、但没有内容」的区域
 *   · 三个筛选签的滚动位置互相钳制，一档短一档长时来回都对不上
 *
 * 这里回到最朴素的规矩：**只在真的空的时候撑高**。够不到一屏的短列表
 * （收藏只有两条）就让它够不到 —— 那是内容的物理事实，不该用空白去伪造。
 */
private const val EMPTY_MIN_HEIGHT_FRACTION = 1f

/**
 * 一行素材的高度估计。用于算「这一档的内容够不够滚到筛选签贴顶」。
 *
 * ## ⚠️★ 为什么这里必须是**常量**，而不是从 `layoutInfo` 里量出来的实际高度（2.1.3 的正解）
 *
 * 2.1.2 的写法是：
 *
 * ```kotlin
 * val rows = listState.layoutInfo.visibleItemsInfo.filter { it.index >= LIBRARY_HEADER_ITEMS }
 * val avgRowPx = rows.sumOf { it.size }.toFloat() / rows.size      // ← 测量输出
 * (viewportPx - tabsHeightPx - avgRowPx * visible.size)            // ← 用来算测量输入
 * ```
 *
 * 而它的消费点是 `LazyColumn(contentPadding = …)` —— **测量的输入**。
 * 用输出当输入，两次测量之间就会互相追逐：
 *
 * ```
 * 垫留白 → 内容变长 → 可见项集合变 → 平均行高变 → 留白再变 → …
 * ```
 *
 * 真机上的表现**不是**「跳」，而是主线程一直在重组/测量、**界面画不出第一帧** ——
 * 用户看到的是「点开一片空屏，一直不动」。2.1.2 交付后的白屏就是这个。
 * （`float` 平均值在小数位上的抖动尤其致命：两次测量差 0.3px，`.toInt()` 后
 * 留白就变 1px，足以把这个环永远维持下去。）
 *
 * 改成常量后，这个值**只跟内容条数有关**（推导见 `tailPadPx`），
 * 与「这一帧恰好测量成了什么样」彻底无关，环就不存在了。
 *
 * ## 为什么取 88dp
 *
 * 条目是「来源行 + 标题（1–2 行）+ 元信息行」加上下内边距，单行标题时约 88–96dp。
 * **故意取偏小的一侧**：
 *
 * - 估**小** → 算出的内容高偏矮 → 留白垫得略多 → 只是一点多余空白，无害
 * - 估**大** → 短档本该垫的不垫 → 筛选签又吸不住，回到 2.1.2 之前的那个「跳」
 *
 * 系统字号放大后真实行高会超过它，此时留白偏多 —— 这是可接受的降级方向。
 */
private val ROW_HEIGHT_ESTIMATE = 88.dp

/**
 * 长按动作条「最后一次操作后」多久自动收起（1.2.4）。
 *
 * 用户的要求是「按下收藏的时候，不要立马把这两个按钮收起来」——
 * 点完还想看一眼星标亮没亮，或者接着点删除。一按就收会让人来不及确认。
 *
 * 但也不能一直挂着：它占着一行高度、把下面的内容压下去，
 * 而且这一行的其余部分已经不能点了（整行被长按接管）。
 */
private const val MENU_AUTO_HIDE_MS = 5_000L

/**
 * 素材库 —— 主界面。
 *
 * ## 版式
 *
 * 整页按「编辑部刊物」排：一个刊头、一条更新动作、一排筛选签，然后是条目流。
 * 全程**不用卡片**：条目之间只靠 1px 分割线和大留白分开。卡片一旦出现，
 * 页面就会退回「仪表盘」的观感 —— 而这里展示的是「今天的稿件」，不是「我的数据」。
 *
 * 每个条目的结构固定为四层，顺序不能换：
 *
 * ```
 * NPR · 2 小时前          ← kicker：来源与时间，小字高字距
 * 联储周三维持利率不变        ← 标题
 * 3:24 · 字幕 32 句        ← 元信息，等宽数字
 * ▓▓▓▓▓░░░░░░  已听 1:12  ← 进度（没听过就不画）
 * ```
 *
 * ## 交互
 *
 * 整行可点即进入播放页 —— 不再单放一个「开始听」按钮。列表本身就是入口，
 * 多一个按钮只是噪音；唯一需要额外动作的「转写字幕」以文字链接挂在元信息行尾。
 */
@Composable
fun LibraryScreen(
    onPlay: (String) -> Unit,
    /** 现在到底在不在播（4.6.3）—— 列表播放键靠它决定显示播放还是暂停 */
    isPlaying: Boolean = false,
    /** 就地播放：只开始播，不跳播放页（4.6.0） */
    onPlayHere: (String) -> Unit = onPlay,
    /**
     * 点了一条**还没有音频**的内容（2.0.3）。
     *
     * 以前这种点击被 `if (canPlay) onPlay()` 静默吞掉：不进播放页、不弹提示、
     * 日志里也没有任何记录。用户看到的是「这条音频我点不出来」，而真相是
     * 「它还没下载」—— 一个本来一句话就能说清、而且可以立刻补救的状态。
     * 现在改成「现在就下这一条」：登记的登记、起一轮更新，列表上那行会立刻
     * 出现「下载中」的进度。
     */
    onNeedDownload: (String) -> Unit = {},
    /** 正在播放的那一条的 id。空串 = 没在播 */
    playingId: String = "",
    /**
     * 「从播放页退回素材库」的信号，每退一次自增。
     *
     * 用计数而不是 `Boolean`：布尔量第二次就不再是变化了，而「进去、出来、
     * 再进去、再出来」是通勤路上最平常的用法。0 表示还没发生过。
     */
    focusNonce: Int = 0,
    vm: LibraryViewModel = viewModel(),
) {
    val context = LocalContext.current
    val episodes by vm.episodes.collectAsStateWithLifecycle()
    val sync by vm.syncState.collectAsStateWithLifecycle()
    val cacheBytes by vm.cacheBytes.collectAsStateWithLifecycle()
    val positions by vm.positions.collectAsStateWithLifecycle()
    val doneIds by vm.doneIds.collectAsStateWithLifecycle()
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()

    // 筛选放在界面层做：它纯粹是「把已经拿到的列表按标记过滤」，
    // 不需要在 ViewModel 里再存一份派生列表，也就没有「筛完的列表和标记不同步」的机会
    val visible = remember(episodes, filter, favorites, doneIds) {
        val filtered = when (filter) {
            LibraryFilter.ALL -> episodes
            LibraryFilter.FAVORITE -> episodes.filter { it.id in favorites }
            LibraryFilter.UNFINISHED -> episodes.filter { it.id !in doneIds }
        }
        // ★★ 4.6.3：**按时间排，不再交错来源**。
        //
        //    交错排序（interleaveBySource）是为了「别让同一家的内容连着刷屏」，
        //    但它让「今天 / 昨天 / 更早」的分组头**反复出现**（用户截图：
        //    今天 → 昨天 → 今天 → 昨天…）。
        //    两个特性天生冲突：分组要求时间连续，交错要求来源分散。
        //    这里选**时间**：这是一份「今日素材」清单，读的是「今天有什么」，
        //    同一家的几条连着出现反而更好扫。
        filtered.sortedByDescending { it.publishedAt ?: 0L }
    }

    // 正在处理的那一条。列表靠它把进度画到**对应的那个条目**下面，
    // 而不是笼统地堆在页面顶部（见 EpisodeRow 的 task 参数）。
    val active = remember(sync.phase) { sync.phase.activeTask() }

    // Android 13+ 需要运行时授权才能显示前台服务的进度通知；
    // 用户拒绝也照样能同步，只是看不到通知栏进度，所以回调里不判断结果直接开跑
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { vm.startSync() }

    val onUpdate: () -> Unit = {
        val needPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        if (needPermission) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            vm.startSync()
        }
    }

    // 同步结束后刷新一次缓存占用
    LaunchedEffect(sync.running) {
        if (!sync.running) vm.refreshCacheSize()
    }

    // 三个筛选签**各记各的位置**（1.2.7 起），而位置记的是**内容**而不是下标（2.1.0）。
    //
    // ## 历史：为什么「每档各一份 LazyListState」还不够
    //
    // 1.2.3 ～ 1.2.6 是「一份状态 + 手工存 / 恢复」，连着踩了三次时序：
    //
    //   · 记下的位置可能超出新列表的范围 → 被钳 → 跳到别处
    //   · 恢复用的 animateScrollToItem 与「内容整份替换」抢同一帧 →
    //     用户看到两次动作叠在一起
    //   · 1.2.5 试过「换内容之前先把列表摆回吸顶位」，反而更跳 ——
    //     真机日志里旧列表先被拽上去（顶部 3/42），随后才换内容（顶部 2/5）
    //
    // 1.2.7 改成「每档一份 LazyListState」，时序问题没有了 —— 但
    // **下标仍然不是坐标**：同步拉回新条目、保留期清掉旧条目、
    // `interleaveBySource` 的全局贪心重排、某一条被筛掉（取消收藏 / 听完）
    // —— 任何一件都会让「第 15 条」变成另一条内容。
    // 于是「切走时记在第 15 条、切回来落在别的内容上」，用户看到的就是「乱跳」。
    // 这也是为什么这个问题拖了七八个版本：每一轮都在修「怎么滚」，
    // 而问题在**记错了坐标**。
    //
    // ## 现在（2.1.1 又改了一次规则 —— 这次是用户在真机上指出的）
    //
    // 规则全在 `LibraryAnchor.kt`（纯函数，可单测），一句话：
    // **看现在筛选签贴在哪儿，新档就落在哪儿。**
    //
    //    · 现在筛选签在刊头下（还没往下翻）→ 新档停在**同一个头部位置**。
    //      三档的头部 item 完全相同，所以这一搬**一个像素都不动**。
    //    · 现在筛选签贴顶（已经往下翻过）→ 新档也必须贴顶。
    //      目标档若有「上次看到的那一条」就落到它身上（那条在内容区，仍然贴顶）。
    //    · 落点在**建状态那一刻**就算好（构造参数）→ 切换时**不做任何滚动动作**；
    //      没有动作就没有时序，「两次动作叠在一起」在这条线上不可能再发生
    //    · 位置由滚动的每一帧连续记进 ViewModel → 「切走」不需要任何收尾动作
    //
    // ⚠️★ 2.0.4 的规则是「优先用**目标档自己**上次的位置」，那正是用户说的
    //    「一会儿高一会儿低」：切到一个上次滚过的档，落点会跳到它那一次的位置，
    //    筛选签栏跟着窜上去；切回来又掉下来。**只要拿目标档的历史当落点，
    //    这个跳就一定发生** —— 两档的历史位置本来就是不同的。
    //    现在「贴不贴顶」由**当前档**决定，「看哪一条」才用目标档的历史，
    //    两者各管一件事，才能既不跳又不丢位置。
    //
    // ⚠️ `remember(filter)` 是刻意的：每次切档**重建**一个状态，而不是复用旧实例。
    //    复用旧实例就必须「把旧位置搬到新内容上」—— 那正是更早那三次踩坑的形态。
    // 筛选签吸顶时会**盖住内容区顶部**，所以「把某一行滚进视野」必须让开这么多像素。
    // 量出来而不是写死：字号放大、加了分隔线，高度都会变（1.2.9 的教训之一）。
    var tabsHeightPx by remember { mutableIntStateOf(0) }
    // 列表视口的高度。判「这一档的内容撑不撑得起滚动」要用它（见 tailPadPx）
    var viewportPx by remember { mutableIntStateOf(0) }
    // 行高估计（dp）换算成像素要用当前密度。系统字号 / 显示大小变化时密度会变，
    // 所以它进 tailPadPx 的 remember key —— 否则改完字号留白还按旧密度算
    val density = LocalDensity.current

    val ids = remember(visible) { visible.map { it.id } }
    val listState = remember(filter) {
        val spot = LibraryAnchor.resolve(
            ids = ids,
            // 目标档自己的历史：只用来决定「看哪一条」
            target = vm.anchorFor(filter),
            // 切走那一刻的位置：它决定「贴顶还是在刊头下」
            current = vm.anchorBeforeSwitch(),
            headerItems = LIBRARY_HEADER_ITEMS,
        )
        LazyListState(spot.index, spot.offsetPx)
    }

    /**
     * 内容太少的档，在列表末尾垫出来的留白高度。
     *
     * ## 它解决的是什么（2.1.2，用户第二次报「还是会跳」）
     *
     * 用户的原话：「当 item 吸顶时，如果收藏和未听完没打开过，或者留在最上面，
     * 从全部跳转过去依然会出现跳的情况」。
     *
     * 「未打开过 / 留在最上面」⟹ 目标档没有内容锚 ⟹ 落点是**筛选签贴顶位**。
     * 而那个落点要靠滚动实现 —— **列表得滚得动才行**。
     * 「收藏」只有两三条时，总内容高（头部 + 三条）还不到一屏，
     * `LazyColumn` 根本滚不动，于是 `LazyListState(headerItems - 1, 0)` 被钳回顶部，
     * 筛选签就掉回刊头下面 —— 与刚离开的那一档（贴顶）**高度不同**，就是那个「跳」。
     *
     * ## 要垫多少（推导）
     *
     * 让筛选签成为首项需要的滚动距离 = 它上方那些 item 的高度和（= `above`）。
     * 这个距离必须落在合法范围内：
     *
     *     总高 − 视口 ≥ above
     *     (above + tabs + content) − 视口 ≥ above
     *     content ≥ 视口 − tabs
     *
     * 所以垫的量就是 `视口 − tabs − content`（负数取 0）。头部高度在两边抵消掉了，
     * 这就是它没出现在公式里的原因 —— 也是这个估算不依赖「头部有多高」的原因。
     *
     * ## ⚠️ 这和 1.2.7 删掉的那个「短列表垫一屏」有什么不同
     *
     * 1.2.7 删它是因为：那时位置是**记住并恢复**的，恢复点可能落在**留白深处** ——
     * 用户看到「整页空白 + 顶上一个吸顶的筛选签」，比跳还糟。
     *
     * 现在不会了：落点只有两种可能（头部区 / 筛选签贴顶位），**都不会落在留白里**。
     * 用户只有主动往下滚才会看到留白，而那时上面还有内容、下面还有一句尾注
     * （见列表末尾那个 `tail-note`）。所以这一轮可以安全地把它加回来。
     *
     * ## ⚠️★ 这个值**绝不能**从 `layoutInfo` 里量（2.1.2 白屏的原因）
     *
     * 2.1.2 用的是「已渲染条目的**平均**高度 × 条数」。当时它看起来比
     * 「`layoutInfo` 的总高」更稳（`layoutInfo` 量不到屏幕外的 item），
     * 但两者犯的是同一个错：**读的是测量输出，而它的消费点 `contentPadding`
     * 是测量的输入。**
     *
     * ```
     * 垫留白 → 内容变长 → 可见项集合变 → 平均行高变 → 留白再变 → …
     * ```
     *
     * 结果是主线程一直在重组/测量、**界面画不出第一帧**（详见 [ROW_HEIGHT_ESTIMATE]）。
     *
     * 现在改成**常量估计**，只依赖三个量：
     *
     * | 量 | 来源 | 为什么它不参与环 |
     * |---|---|---|
     * | `visible.size` | 内容条数 | 与测量无关 |
     * | `viewportPx` | `onSizeChanged` | 视口由**外部约束**给出，不受 `contentPadding` 影响 |
     * | `tabsHeightPx` | `onSizeChanged` | 筛选签高度由**它自己的内容**决定，同上 |
     *
     * ## 顺带纠正一条被写反的结论
     *
     * 上一版这里写着「估偏大时不会误垫，估偏小时垫得略少」——**实测是反的**：
     *
     * - 估**小** → 内容高算矮 → 留白垫**多** → 一点多余空白，无害
     * - 估**大** → 短档本该垫的不垫 → 筛选签又吸不住，回到 2.1.2 之前那个「跳」
     *
     * 所以安全方向是**偏小**，`ROW_HEIGHT_ESTIMATE` 也是照这个方向取的。
     */
    val tailPadPx = remember(visible.size, viewportPx, tabsHeightPx, density) {
        // ⚠️ 空列表**不垫**。空状态块自己就撑满一屏（见 EMPTY_MIN_HEIGHT_FRACTION），
        //    列表本来就滚得动，再垫一段只会多出一片没有来由的空白 ——
        //    而且那会让「以上是「收藏」里的全部内容」这句尾注挂在一个
        //    **根本没有内容**的档下面，那是句假话。
        //
        //    （2.1.2 那版在这里也会垫：它 `rows` 过滤的是 `index >= 3` 的项，
        //    空状态块的下标正好是 3，所以 `rows` 非空；而乘数 `visible.size`
        //    是 0 —— 于是留白量退化成「视口 − 筛选签」，白白垫一屏。
        //    这一条是顺手修掉的，与白屏无关。）
        if (visible.isEmpty() || viewportPx <= 0 || tabsHeightPx <= 0) {
            0
        } else {
            val rowPx = with(density) { ROW_HEIGHT_ESTIMATE.toPx() }
            (viewportPx - tabsHeightPx - rowPx * visible.size)
                .coerceAtLeast(0f)
                .toInt()
        }
    }

    // 滚动回调里要读**最新**的 id 列表：`LaunchedEffect(listState, filter)` 不会
    // 因为 visible 变了而重启，闭包里直接捕 visible 会读到过期的那一份
    val latestIds = rememberUpdatedState(ids)

    // 连续记录位置。「切回去还在原地」的全部实现就是这一段。
    // snapshotFlow 只在滚动位置真的变了才发（拖动时一帧一次，纯内存写，不触发重组）。
    LaunchedEffect(listState, filter) {
        snapshotFlow {
            listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        }.collect { (index, offsetPx) ->
            vm.rememberAnchor(
                filter,
                LibraryAnchor.capture(index, offsetPx, latestIds.value, LIBRARY_HEADER_ITEMS),
            )
        }
    }

    // 落点自证：切档之后报一次**实际**落点。
    //
    // 它是本轮的验收点 —— 与 `切筛选 → … · 从 …` 配对读，**两行的「筛选签…」
    // 必须一模一样**。不一样就是那一栏动了（用户说的「一会儿高一会儿低」）。
    // 等一帧再读：切换那一帧的 layoutInfo 还是旧列表的。
    LaunchedEffect(listState) {
        withFrameNanos { }
        val info = listState.layoutInfo
        val top = listState.firstVisibleItemIndex
        // ★ 2.1.4：把**筛选签那一栏相对视口顶部的像素偏移**直接量出来报掉。
        //
        // 以前只报 `firstVisibleItemIndex`，而「贴不贴顶」是从它**推断**出来的 ——
        // 推断链上有一环写错过（`labelOf` 的判据比 `capture` 多吞了一个下标），
        // 于是**每一次正确的贴顶都被报成「筛选签在刊头下」**，
        // 与上一行的「从 筛选签贴顶」摆在一起看就是自相矛盾，
        // 把这个需求一路带偏了三轮。这里改成直接量，不再推断：
        //
        //   · 找得到 → 它在视口里：`offset == 0` 是**贴在内容区起点**（比吸顶低一档），
        //     > 0 才是**在刊头下**
        //   · 找不到 → 它已经被滚过去了，正**吸**在顶部
        val tabsItem = info.visibleItemsInfo
            .firstOrNull { it.index == LIBRARY_HEADER_ITEMS - 1 }
        // ⚠️ 四种说法互斥，而且**不能漏掉负偏移**：
        //    负 offset = 筛选签已经被滚出屏幕上方 → 它正**吸**在顶部。
        //    第一版漏了这一格，把吸附报成了「在刊头下(偏 -98px)」——
        //    与上一次误导排查的那类错误同源：**结论必须落在事实上，不是落在近似上**。
        //
        // ⚠️★ 2.1.6：`offset == 0` 这一格原来叫「正好贴顶」，**那是个会骗人的名字** ——
        //    它比真吸顶**低一整个列表顶部内边距**（`Space.section` = 30dp）。
        //    两个状态共用一个名字，就等于不可能靠日志发现它们的差异；
        //    而用户报的「往下沉一下」正是这两个状态互切。现在叫「未吸顶」。
        val tabsWhere = when {
            tabsItem == null -> "吸在顶(完全滚过)"
            tabsItem.offset == 0 -> "贴顶位(未吸顶)"
            tabsItem.offset < 0 -> "吸在顶(滚过 ${-tabsItem.offset}px)"
            else -> "在刊头下(偏 ${tabsItem.offset}px)"
        }
        DiagLog.act(
            "筛选落点 · ${filter.label} · " +
                "顶部 ${top + 1}/${info.totalItemsCount} 条" +
                " · ${LibraryAnchor.labelOf(top, LIBRARY_HEADER_ITEMS)}" +
                " · 筛选签 $tabsWhere",
        )
    }

    // 从播放页退回素材库时，把正在听的那一条滚进视野。
    //
    // 只在 focusNonce 变化时跑：它是「退回来了一次」这件事本身，而不是「当前在播哪一条」——
    // 后者每秒都在变（切集、自动连播），拿它当键会让列表在用户没做任何事的时候自己滚动。
    //
    // 滚到顶部而不是「最小滚动到可见」：退回列表的人此刻是来找这一条的，
    // 把它摆在列表最上方最不容易看漏，也顺带把「后面还有几集」摊在眼前。
    LaunchedEffect(focusNonce) {
        if (focusNonce <= 0) return@LaunchedEffect
        val index = visible.indexOfFirst { it.id == playingId }
        // 找不到就不动：可能被当前筛选签排除了、也可能音频已被保留期清掉。
        // 这时候硬滚到一个错误的行上，比什么都不做更糟。
        if (index < 0) return@LaunchedEffect
        val target = index + LIBRARY_HEADER_ITEMS
        // 已经在屏幕上就不要动 —— 用户可能刚划到别处，无谓的滚动会打断他。
        // ⚠️ 让开筛选签的高度：这一行要停在**吸顶的筛选签下面**。
        //    不这么做的话，目标行会被吸顶的筛选签盖掉一截 —— 用户看到的是
        //    「退回来还是没看见那一条 / 停在隔壁」，而代码这边「明明滚到了」。
        //
        // ★ 2.1.6：`scrollOffset = 0` 指的是「贴到**内容区起点**」，而内容区起点
        //    比视口顶低一整个 `contentPadding.top`（这里是 `Space.section`）。
        //    所以要减掉它，否则这一行会停得比目标位置低 30dp（**同一个约定错误**
        //    在播放页的跟随定位里更严重 —— 那边的内边距是一整个顶栏）。
        val paddingTopPx = with(density) { Space.section.roundToPx() }
        val alreadyVisible = listState.layoutInfo.visibleItemsInfo.any { it.index == target }
        if (!alreadyVisible) {
            listState.animateScrollToItem(target, paddingTopPx - tabsHeightPx)
            DiagLog.act(
                "退回列表定位 · ${visible[index].title.take(20)} · 第 ${target + 1} 行" +
                    "（让开吸顶的筛选签 ${tabsHeightPx}px）",
            )
        }
    }

    // 列表与右侧快速滚动条叠在一起（1.2.2）。
    //
    // ⚠️ 必须拆成两个 Composable，而且**滚动条自己读 layoutInfo**（见 QuickScrollBar）。
    //    在这一层算滑块位置的话，layoutInfo 的每次变化都会让整个 LibraryScreen
    //    —— 包括屏幕上所有条目 —— 重组一次，也就是「滚动时每一帧重建一遍列表」。
    //    这是 1.2.0 花大气力修掉的那一类问题（Root() 顶层读播放状态），
    //    所以这里的边界要写清楚：**读数的人和画图的人必须待在同一个 Composable 里**。
    Box(Modifier.fillMaxSize()) {
    // ★ `key(filter)`：换筛选 = **换一整个列表**，不是往同一个列表里换数据。
    //
    // ## 为什么这一句是必需的
    //
    // 不包这一层时，切筛选只是 `visible` 整份换掉 —— 条目带稳定 key
    // （`key = { it.id }`），于是 `animateItem()` 会老老实实播动画：
    // 40 条一起淡出、40 条一起淡入，**新旧两批同时存在于屏幕上**。
    // 用户看到的就是「页面错乱」：一行行文字互相叠着。
    //
    // 那个动画本身没错，它服务的是「交错排序让新内容插进列表中部」——
    // 那是**增量**更新。而切筛选是整份替换，整份替换不需要过渡，
    // 它需要的恰恰是「一帧换完」。
    //
    // 用 key 而不是一个「动画开关」布尔量：布尔量要在切换后的一段时间里
    // 关掉动画，于是又变成时序问题（这轮已经删掉两个了）。
    // key 变化直接丢弃旧子树、重建新子树，旧条目无从淡出 ——
    // 这不是「绕过动画」，而是**说清楚这里没有前后关系**。
    key(filter) {
    // 底部渐变的颜色要在**可组合作用域**里取（Bg 是 @Composable getter，
    // drawWithContent 的 lambda 不是可组合的）
    val fadeTo = Bg
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            // 量给 tailPadPx 用（判断这一档的内容撑不撑得起滚动）
            .onSizeChanged { viewportPx = it.height }
            // ★ 4.6.1：**列表底部淡出**。
            //   迷你条出现时，最后一条会被一条硬边切断；这里在列表自己的底部
            //   32dp 上压一层「透明 → 页面底色」的渐变，内容看起来是淡出去的，
            //   而不是「到这儿就没了」。
            .drawWithContent {
                drawContent()
                val h = 32.dp.toPx()
                if (size.height > h) {
                    drawRect(
                        brush = Brush.verticalGradient(
                            0f to Color.Transparent,
                            1f to fadeTo,
                            startY = size.height - h,
                            endY = size.height,
                        ),
                        topLeft = Offset(0f, size.height - h),
                        size = Size(size.width, h),
                    )
                }
            },
        // 1.2.8：条目之间靠**间距**分组，不再画线。
        // 线分组在深色下是失效的（hairline 压 bg 只有 ~1.1:1），
        // 而间距在任何主题下都成立
        // ⚠️ 4.6.1：不再是 `spacedBy(8.dp)` —— 现在每条之间有一条**缩进分隔线**，
        //    再叠 8dp 间距会让那条线飘在缝的中间（截图里能看出来）。
        //    节奏交给「行内 12dp 上下内边距 + 分隔线」。
        verticalArrangement = Arrangement.spacedBy(0.dp),
        contentPadding = PaddingValues(
            start = Space.pageH,
            end = Space.pageH,
            top = Space.section,
            // ⚠️ 这里曾经是 `if (shortList) viewport else 40.dp` —— 短列表垫一屏
            //    底部留白，好让筛选签吸得住。**1.2.7 删掉**：那会让滚动位置
            //    落进一整块空白里，用户看到「整页白 + 顶上一个吸顶的筛选签」。
            //
            // ★ 2.1.2 加回来，但**换了一个判据**：不再是「列表短不短」，
            //    而是「按上面的推导，还差多少才够滚到筛选签贴顶」。
            //    1.2.7 那个问题的前提（落点可能落在留白里）已经不存在了 —— 见 tailPadPx。
            bottom = 40.dp + with(LocalDensity.current) { tailPadPx.toDp() },
        ),
    ) {
        // 刊头与更新动作合成**一个** item（1.2.0）。
        // 它们本来就是一件事：「今天有什么」和「去取今天的」。
        // 拆成两个 item 只是让首屏多占一块，而首屏的每一寸都该留给内容
        item(key = "masthead") {
            val (todaySec, goalSec) = vm.todayProgress.collectAsStateWithLifecycle().value
            Masthead(
                    sync = sync,
                    onUpdate = onUpdate,
                    onCheckSources = vm::checkSources,
                    todaySec = todaySec,
                    goalSec = goalSec,
                )
        }
        item(key = "status") {
            StatusBlock(
                sync = sync,
                issues = sync.sourceResults.filter { it.error != null },
            )
        }
        // 筛选签**吸顶**（1.2.2）。
        //
        // 内容多起来之后，「全部 / 收藏 / 未听完」在往下翻几屏之后就看不见了 ——
        // 而想看「收藏」的时候，人往往已经翻到了很下面。吸顶之后它一直在，
        // 不用先滚回顶部再点。
        //
        // ⚠️ stickyHeader 仍然是 LazyColumn 的**一个 item**，索引不变 ——
        //    所以 LIBRARY_HEADER_ITEMS 还是 3（见它的说明）。
        //    换成 `item {}` 包装会在吸顶背景上出缝，别「简化」这一步。
        stickyHeader(key = "tabs") {
            FilterTabs(
                // 量给「退回列表定位」用 —— 那一处要把目标行停在吸顶的筛选签**下面**
                modifier = Modifier.onSizeChanged { tabsHeightPx = it.height },
                current = filter,
                total = episodes.size,
                favorite = favorites.size,
                unfinished = episodes.count { it.id !in doneIds },
                onSelect = { next ->
                    // 只做一件事：换筛选（1.2.7）。
                    //
                    // 这里曾经有四行「先存位置 → 也许先把列表摆回吸顶位 → 再切」。
                    // 1.2.5 那版真机日志证明，那一类「先把旧列表摆好再换内容」
                    // 的做法本身就是旧内容先动一次：
                    //
                    //   22:13:14.563 I UI  滚动条位置 4% · 顶部 3/42 条   ← 旧列表被拽上去
                    //   22:13:14.567 I UI  滚动条位置 25% · 顶部 2/5 条    ← 这才换内容
                    //
                    // 现在每个筛选签各有一份 LazyListState，位置跟着状态走 ——
                    // 切换时**不需要搬运任何东西**，所以这里什么都不用做
                    if (next != filter) vm.setFilter(next)
                },
            )
        }

        if (visible.isEmpty()) {
            item(key = "empty") {
                EmptyBlock(
                    running = sync.running,
                    filter = filter,
                    hasAny = episodes.isNotEmpty(),
                    // ★ 撑到接近一屏高（1.2.4）—— 这是「切筛选整页跳」的关键一半。
                    //
                    // 空筛选（比如收藏还没加过东西）时列表只有三个头部 item，
                    // **总高不足一屏 → LazyColumn 根本滚不动** → 下面那句
                    // 落到「筛选签吸顶位」会被钳回去 →
                    // 吸顶的筛选签掉回刊头下面。用户读到的就是「切一下跳一下」。
                    //
                    // 撑高之后总高必然超过一屏，滚动位置就有地方可放，
                    // 筛选签能稳稳留在吸顶位 —— 切过去、切回来都不动。
                    // 多出来的部分本来就是空白，视觉上没有代价。
                    modifier = Modifier.fillParentMaxHeight(EMPTY_MIN_HEIGHT_FRACTION),
                )
            }
        } else {
            // ★ 4.5.0：按天分组（今天 / 昨天 / 更早）。
            //
            // ⚠️ 刻意**不往 LazyColumn 里插表头 item**：这一页的吸顶、滚动位置
            //    与尾部留白三套逻辑都建在「条目下标」上（2.1.2 白屏、1.2.4 吸顶、
            //    3.0.5 位置记内容 —— 出过三次事故）。
            //    改成「第一条换组时在它自己上面画一行标题」：
            //    **列表的 item 数一个都没变**，下标语义原封不动。
            itemsIndexed(visible, key = { _, it -> it.id }) { i, ep ->
                Column {
                    dayGroupLabel(ep, visible.getOrNull(i - 1))?.let { GroupHeader(it) }
                    EpisodeRow(
                    // 1.2.0：列表增删与重排有了动画。
                    //
                    // 这里早就有稳定 key（`key = { it.id }`），只差这一个
                    // modifier —— 在此之前，一次更新拉回新内容、或者切换筛选签，
                    // 整个列表是**瞬间换掉**的：旧条目原地消失、新条目原地出现，
                    // 用户完全看不出「哪几条是新来的」。
                    //
                    // 交错排序（interleaveBySource）会让新内容插进列表中部，
                    // 这个动画恰恰让那件事变得可读。
                    modifier = Modifier.animateItem(
                        fadeInSpec = Motion.standard(),
                        placementSpec = Motion.resize(),
                        fadeOutSpec = Motion.quick(),
                    ),
                    episode = ep,
                    canPlay = vm.canPlay(ep),
                    needsAudio = vm.needsAudio(ep),
                    canTranscribe = vm.canTranscribe(ep),
                    // ⚠️ 「忙」是**这一条自己**的属性，不是引擎的。
                    //    见 EpisodeRow 里那段注释 —— 这里传错过一次，
                    //    代价是「只要后台在转写任何一条，整个列表都点不动」。
                    selfBusy = ep.status.isBusy,
                    task = active?.takeIf { it.episodeId == ep.id },
                    engineBusy = sync.running,
                    progress = progressOf(ep, positions, doneIds),
                    done = ep.id in doneIds,
                    favorite = ep.id in favorites,
                    playing = ep.id == playingId,
                    isPlaying = isPlaying,
                    onPlay = { onPlay(ep.id) },
                    onPlayHere = { onPlayHere(ep.id) },
                    onNeedDownload = { onNeedDownload(ep.id) },
                    onTranscribe = { vm.fetchSubtitlesOne(ep) },
                    onToggleFavorite = { vm.toggleFavorite(ep) },
                    onDelete = { vm.deleteEpisode(ep) },
                    )
                }
            }
        }

        // 短内容档垫了留白（见 tailPadPx）—— 留白之前先给一句尾注，
        // 不然用户往下滚只会看到一片没有来由的空白，像界面坏了。
        //
        // ⚠️ 只在真的垫了留白时出现（`tailPadPx > 0`）。长列表不垫留白，
        //    也就不会有这句 —— 那句话在那里是多余的（列表本来就还长着呢）。
        if (tailPadPx > 0) {
            item(key = "tail-note") {
                Text(
                    text = "以上是「${filter.label}」里的全部内容",
                    style = AppText.caption,
                    color = InkFaint,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = Space.row),
                )
            }
        }

        item(key = "colophon") {
            Colophon(
                bytes = cacheBytes,
                doneCount = doneIds.size,
                onClearPlayed = vm::clearPlayedAudio,
                onClear = vm::clearCache,
            )
        }
    }
    }   // key(filter) ── 见上面的说明：换筛选 = 换一整个列表

        QuickScrollBar(
            state = listState,
            modifier = Modifier.align(Alignment.CenterEnd),
        )
    }
}

// ---------------------------------------------------------------------------
// 快速滚动条
// ---------------------------------------------------------------------------

/** 触摸热区宽度。条本身只有 2–3dp，手指够不着，热区要宽得多 */
private val SCROLLBAR_TOUCH_WIDTH = 26.dp
private val SCROLLBAR_TRACK_WIDTH = 2.dp
private val SCROLLBAR_THUMB_WIDTH = 3.dp

/** 滑块最短这么长。再短就只是一个点，看不出「我能拖」 */
private val SCROLLBAR_MIN_THUMB = 26.dp

/**
 * 滑块长度占轨道的下限。
 *
 * 内容有几百条时按比例算出来的长短得看不见，而**「看得见」比「比例精确」重要** ——
 * 这是一个用来拖的东西，不是一个数据图表。
 */
private const val SCROLLBAR_MIN_LENGTH_FRACTION = 0.06f

/** 轨道占列表高度的比例。留出上下边距，免得贴着屏幕边缘不好按 */
private const val SCROLLBAR_TRACK_FRACTION = 0.72f

/**
 * 不用时的透明度。
 *
 * **半隐藏而不是消失**：它要一直在那里，用户才知道「这里能拖」；
 * 但满亮的一条会跟内容抢注意力 —— 内容才是主角。
 * 手一碰（拖动）或者一滚（isScrollInProgress）就完全显出来。
 */
private const val SCROLLBAR_IDLE_ALPHA = 0.25f

/** 滚动条要显示的两个比例。null = 不显示（没内容 / 一屏装得下） */
private data class ScrollMetrics(
    /**
     * 已滚过的比例 0f..1f。
     *
     * ⚠️ **必须是连续量**（1.2.5）：它由「首项 index + 项内像素偏移」构成。
     * 只用 `firstVisibleItemIndex / (total-1)` 的话，滑块的位置是**按条**离散的 ——
     * 四十条内容、几百像素的滑轨，手指每移动十几像素滑块才挪一格，
     * 看上去就是一格一格蹦。
     */
    val scrolled: Float,
    val total: Int,
    /** 真实的首项 index（来自 scrollPosition，不受吸顶干扰） */
    val first: Int,
    /** 用「末项 index − 可见条数」反推的首项，与 [first] 互为对照 */
    val tailFirst: Int,
)

/**
 * 右侧快速滚动条（1.2.2 引入；1.2.3 修滑块不动；1.2.5 改无级；1.2.9 修抓取偏移）。
 *
 * ## 为什么需要
 *
 * 索引上限 40 条，一屏放 4–5 条 → 想找回昨天那条要划七八屏。
 * 而「昨天那条」恰恰是最常要找的（今天的已经听完了）。
 *
 * ## ⚠️ 这里面读的每一个值都在滚动时每帧变化
 *
 * `layoutInfo`（首项 index、可见条数、总条数）是**每滚一帧都变**的状态。
 * 所以这一段必须**自成 Composable**，让重组的范围收缩在这两根细线里 ——
 * 写在调用方（LibraryScreen 那一层）会让每滚一帧整个列表重建一次。
 *
 * ## 1.2.3：滑块「一直停在最上面」是怎么修的
 *
 * 根因是取值取错了：`visibleItemsInfo.first().index` 里那个「首项」**永远是吸顶的
 * 筛选签自己**（它占据可见列表的第一个位置）。改用 `state.firstVisibleItemIndex`
 * （来自滚动位置本身）之后才对。真机日志里那一串「第 1~2 条」是唯一的线索 ——
 * 这类问题光读代码看不出来。
 *
 * ## 1.2.5：从「按条跳」改成无级
 *
 * 用户的原话是「不丝滑…你是通过标记每一个素材的位置实现的」—— 说得完全对。
 * 两处都是「按条取整」：
 *
 * | 位置 | 1.2.4 | 1.2.5 |
 * |---|---|---|
 * | 滑块比例 | `first / (total-1)` | `(first + 项内偏移/条目高) / (total-1)` |
 * | 滚动目标 | `roundToInt()` 后 `scrollToItem(i)` | 保留小数，折成条目内**像素偏移** |
 *
 * 还有一处更隐蔽的：拖动时每来一个事件就 `scope.launch` 一个协程去滚 ——
 * 十几个 `scrollToItem` 互相打断、互相追，观感就是一顿一顿的。现在只留一个
 * 「当前定位任务」，新事件来了先取消它。
 *
 * ## 交互（拖动那一条 1.2.9 改过，细节写在 `dragTo` 那段）
 *
 * - 拖动：捏住**滑块**时保持抓取偏移、1:1 跟手；按在**轨道**上则跳过去
 * - 拖动期间滑块**直接跟手指**，松手后回到「由滚动位置反推」（两者一致，不会跳）
 * - 半隐藏：静止时 25% 不透明度，拖动或列表滚动中变为 100%
 * - 一屏装得下就不画：画了也拖不动，只是个让人困惑的装饰
 */
@Composable
private fun QuickScrollBar(
    state: LazyListState,
    modifier: Modifier = Modifier,
) {
    val metrics by remember(state) {
        derivedStateOf {
            val info = state.layoutInfo
            val total = info.totalItemsCount
            val vis = info.visibleItemsInfo
            if (total <= 0 || vis.isEmpty()) {
                null
            } else {
                // ★★ 1.2.4 真正的根因在这一行 ★★
                //
                // 原来用的是 `vis.first().index` —— 而**吸顶的 stickyHeader
                // 会一直占据 visibleItemsInfo 的第一个位置**，它的 index
                // 恒等于它自己那一项（tabs = 2）。
                //
                // 于是：只要列表滚到筛选签吸顶，`first` 就永远是 2，
                // `scrolled` 永远是 2/41 ≈ 5% —— 滑块钉在最上面。
                // 而且**越往下滚越看不出来**（因为它一直不变）。
                //
                // 真机日志把这件事摊开了：41 条「滚动条位置 N% · 第 i/total 条」
                // 里 i 只有 1、2、3 三种值，而 total 在 35~42 之间变。
                // 「位置在变、首项不变」这个组合，只可能是取错了值。
                //
                // `state.firstVisibleItemIndex` 来自滚动位置本身，不受吸顶影响。
                val first = state.firstVisibleItemIndex
                // 末项反推的对照值：两者正常情况下相等。
                // 留着是因为——万一 firstVisibleItemIndex 在某些版本上也受
                // 吸顶影响，日志里这一对数字会立刻显出差异，不必再猜一轮。
                val tailFirst = (vis.last().index - vis.size + 1).coerceAtLeast(0)
                if (first == 0 && vis.last().index >= total - 1) {
                    null
                } else {
                    // 条目高度不齐（刊头和一行差好几倍），所以这里用**可见条目的
                    // 平均值**当尺度把「项内像素偏移」折算成小数部分。
                    // 它只影响滑块位置的连续感，不影响到不到位
                    val avg = (vis.sumOf { it.size }.toFloat() / vis.size).coerceAtLeast(1f)
                    val sub = state.firstVisibleItemScrollOffset
                        .coerceIn(0, avg.toInt().coerceAtLeast(1))
                    ScrollMetrics(
                        scrolled = ((first + sub / avg) / (total - 1).coerceAtLeast(1))
                            .coerceIn(0f, 1f),
                        total = total,
                        first = first,
                        tailFirst = tailFirst,
                    )
                }
            }
        }
    }
    val m = metrics ?: return

    var trackPx by remember { mutableStateOf(0) }
    val density = LocalDensity.current
    val thumbMinPx = with(density) { SCROLLBAR_MIN_THUMB.toPx() }

    // ── 滑块长度（1.2.6）──
    //
    // ## 为什么不能每帧跟着量
    //
    // 一次滚动里屏幕上的条目会进进出出，量出来的「可见比例」会来回摆 2–7%
    // （桌面复刻实测，见 `tools/verify-library-ux.py` 那一节）。
    // 而拇指本身只有几十像素 —— 同样是 2%，作用到长度上就是**几十个百分点的视觉变化**。
    // 用户的原话是「滑动的时候，滑块在不停的变长变短」。
    //
    // 关键在它不是高频噪声，而是随滚动**缓慢游走**的量：滤波压不掉
    // （滤狠了真的内容变化也跟不上）。所以换一条规矩：
    //
    //   **长度只在「内容变了」或「停下来了」这两个时机重算，滚动过程中一个像素都不动。**
    //
    // 内容变了（总条数变化）→ 立刻跟随；手指停下来 → 收敛回实测值（走补间，不跳）。
    var lengthTarget by remember(state) { mutableFloatStateOf(0.12f) }
    var lengthKey by remember(state) { mutableIntStateOf(-1) }
    LaunchedEffect(state) {
        snapshotFlow {
            val info = state.layoutInfo
            Triple(info.totalItemsCount, state.isScrollInProgress, thumbFractionOf(info))
        }.collect { (total, scrolling, frac) ->
            if (frac <= 0f) return@collect
            if (total != lengthKey || !scrolling) {
                lengthKey = total
                lengthTarget = frac
            }
        }
    }
    val lengthFraction by animateFloatAsState(
        targetValue = lengthTarget,
        animationSpec = Motion.standard(),
        label = "thumbLength",
    )
    val thumbPx = (trackPx * lengthFraction).coerceAtLeast(thumbMinPx)
    val travelPx = (trackPx - thumbPx).coerceAtLeast(0f)

    var dragging by remember { mutableStateOf(false) }
    /**
     * 拖动期间手指对应的比例。
     *
     * 有它的时候滑块**只认手指**，不再等滚动位置追上来 ——
     * 否则「手指动了、滑块还在原地」的滞后会让整条滑轨显得不跟手。
     * 松手置 null，滑块回到「由滚动位置反推」；两条路径算的是同一个比例，
     * 所以不会出现松手瞬间滑块跳一下。
     */
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val offsetPx = travelPx * (dragFraction ?: m.scrolled)

    val fade by animateFloatAsState(
        targetValue = if (dragging || state.isScrollInProgress) 1f else SCROLLBAR_IDLE_ALPHA,
        animationSpec = Motion.standard(),
        label = "scrollBarFade",
    )
    val scope = rememberCoroutineScope()

    /** 当前正在执行的定位协程。新的拖动事件会把它取消掉，见 [dragTo] */
    var seekJob by remember(state) { mutableStateOf<Job?>(null) }

    /**
     * 抓取时手指相对**滑块顶部**的偏移（1.2.9）。
     *
     * 只在一次「按下 → 松手」内有效，所以用 state holder 而不是普通局部量：
     * 下面 `pointerInput` 的 block 只在 key 变化时重启，闭包捕获的普通量会陈旧。
     */
    var grabPx by remember { mutableFloatStateOf(0f) }

    // ⚠️ `offsetPx` / `thumbPx` 每帧都在变，而 pointerInput 的 block 只在
    //    key（m.total）变化时重启 —— 直接在 block 里读这两个会读到**陈旧的**值，
    //    表现就是「滑块位置和手指对不上」。走 rememberUpdatedState 才是实时的。
    val thumbTopNow = rememberUpdatedState(offsetPx)
    val thumbLenNow = rememberUpdatedState(thumbPx)

    // 排查用。1.2.3 靠这行日志找到了「首项恒为 2」这件事 ——
    // 上一轮纯看代码是找不到的。括号里那个对照值正常情况下应该相等，
    // 不相等就说明 firstVisibleItemIndex 也被吸顶影响了（那就是换算法的信号）
    //
    // 只在「首项」变化时记：项内偏移每帧都在变，记它会把日志淹掉
    LaunchedEffect(m.first, m.total) {
        DiagLog.i(
            "UI",
            "滚动条位置 ${(m.scrolled * 100).toInt()}% · 顶部 ${m.first + 1}/${m.total} 条" +
                "（末项反推 ${m.tailFirst + 1}）" +
                // 长度也记一笔：1.2.6 修的是「长度跟着滚动乱跳」，
                // 下一次若还有偏差，这一项能立刻分清是「量错了」还是「没跟对」
                " · 长度 ${(thumbFractionOf(state.layoutInfo) * 100).toInt()}%",
        )
    }

    Box(
        modifier = modifier
            .width(SCROLLBAR_TOUCH_WIDTH)
            .fillMaxHeight(SCROLLBAR_TRACK_FRACTION)
            .alpha(fade)
            .onSizeChanged { trackPx = it.height }
            .pointerInput(m.total) {
                // ── 拖动映射（1.2.9 改）──
                //
                // ## 改之前是什么，为什么不对
                //
                // 之前一律 `jumpTo(手指 y)`：把手指的 y 直接换算成内容比例，也就是
                // **绝对定位** —— 「按在滑块的哪个位置，滑块顶部就跳到哪」。
                // 于是手指捏住滑块**下半部分**时，按下那一瞬间滑块会向上窜一下、
                // 内容跟着跳。§2 的原话是「尊重抓取时的偏移」。
                //
                // ## 现在两种按法分开
                //
                // | 按在哪 | 行为 | 用户的意思是 |
                // |---|---|---|
                // | 滑块上 | 保持抓取偏移，1:1 跟手 | 「挪这个滑块」—— 不该跳 |
                // | 轨道空白 | 跳到该处、滑块以手指为中心 | 「我要到那里」 |
                //
                // 两条路径都算「滑块顶部的目标位置 → 比例」，所以松手回落到
                // 「由滚动位置反推」时是同一个数，不会跳。
                fun dragTo(y: Float, trackH: Float) {
                    val travel = (trackH - thumbLenNow.value).coerceAtLeast(0f)
                    if (travel <= 0f) return
                    val top = (y - grabPx).coerceIn(0f, travel)
                    val frac = top / travel
                    dragFraction = frac
                    // ★ 先取消上一次定位（1.2.5）。
                    //
                    // 拖动一秒会来几十个事件，每个都 `launch` 一个 `scrollToItem`
                    // 的话，它们会互相打断 —— 前一个刚滚到一半就被后一个拽走，
                    // 观感是「一顿一顿」。只保留最新那个目标，滑轨才跟手。
                    seekJob?.cancel()
                    seekJob = scope.launch { seekToFraction(state, frac) }
                }
                detectVerticalDragGestures(
                    onDragStart = { offset ->
                        dragging = true
                        val top = thumbTopNow.value
                        val len = thumbLenNow.value
                        grabPx = if (offset.y >= top && offset.y <= top + len) {
                            // 捏在滑块上：记住捏住的是哪一段，按下不跳
                            offset.y - top
                        } else {
                            // 按在轨道上：滑块以手指为中心，
                            // 也就是「跳到这里、并且滑块就在手指下」
                            len / 2f
                        }
                        dragTo(offset.y, size.height.toFloat())
                    },
                    onDragEnd = {
                        dragging = false
                        dragFraction = null
                    },
                    onDragCancel = {
                        dragging = false
                        dragFraction = null
                    },
                ) { change, _ ->
                    change.consume()
                    dragTo(change.position.y, size.height.toFloat())
                }
            },
    ) {
        // 轨道
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .width(SCROLLBAR_TRACK_WIDTH)
                .fillMaxHeight()
                .background(Hairline),
        )
        // 滑块
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .offset { IntOffset(0, offsetPx.roundToInt()) }
                .width(SCROLLBAR_THUMB_WIDTH)
                .height(with(density) { thumbPx.toDp() })
                .background(InkDim),
        )
    }
}

/**
 * 把列表滚到「整份内容 [frac] 处」（无级）。
 *
 * ## ⚠️ 不要退回成 `roundToInt()` + `scrollToItem(index)`
 *
 * 那是 1.2.4 的写法，也正是用户说的「不丝滑…你是通过标记每一个素材的位置实现的」：
 * 每次拖动只能落在**整条**上。四十条内容、几百像素的滑轨，手指要移动十几像素
 * 滑块才跳一格 —— 而每一格在屏幕上就是整整一行，看起来当然是一格一格蹦。
 *
 * 正解是把小数部分折成**条目内的像素偏移**：`scrollToItem` 的第二个参数
 * 就是它。于是手指移动一像素、内容也移动一像素。
 *
 * 条目高度不齐（刊头比一行高出好几倍），所以「小数 → 像素」这一步是近似的 ——
 * 它只让滑轨在跨越不同高度的条目时手感略有不同，不会出现跳变。
 * 真正保证「拖到底就是底」的是 [frac] 本身，与这处近似无关。
 */
private suspend fun seekToFraction(state: LazyListState, frac: Float) {
    val info = state.layoutInfo
    val total = info.totalItemsCount
    if (total <= 0) return
    val exact = frac * (total - 1)
    val index = exact.toInt().coerceIn(0, total - 1)
    val within = (exact - index).coerceIn(0f, 1f)
    // 目标条目自己的高度最准；它不在可见区时用可见条目的平均高度代替
    val itemPx = info.visibleItemsInfo.firstOrNull { it.index == index }?.size
        ?: info.visibleItemsInfo.map { it.size }.average().toInt()
    if (itemPx <= 0) return
    state.scrollToItem(index, (within * itemPx).toInt())
}

/**
 * 「屏幕上大约装着全部内容的百分之几」—— 决定滑块多长。
 *
 * 用**视口像素 ÷ 可见条目的中位高度**先把「屏幕上有几条」算成连续量，再除以总条数。
 * 比直接数 `visibleItemsInfo.size` 好两点：
 *
 * - 它是连续的（不按条跳）
 * - 更接近真值：数条数会把**吸顶的筛选签**也算进去，长度偏长两成上下
 *   （桌面复刻：42 条内容时数条数得出 18.75%、中位高得出 14.1%，真值 15.0%）
 *
 * ⚠️ 它**仍然会随滚动摆动**（可见项进进出出，实测幅度 2–7%）——
 * 所以调用方只在「换内容 / 停下来」时才用它，**不要每帧跟**。
 * 这是「滑块不停变长变短」的根治点，改动它之前先看 QuickScrollBar 里那段说明。
 */
private fun thumbFractionOf(info: LazyListLayoutInfo): Float {
    val vis = info.visibleItemsInfo
    if (vis.isEmpty() || info.totalItemsCount <= 0) return 0f
    val viewport = (info.viewportEndOffset - info.viewportStartOffset).coerceAtLeast(1)
    val sizes = vis.map { it.size }.sorted()
    val median = sizes[sizes.size / 2].coerceAtLeast(1)
    val onScreen = viewport.toFloat() / median
    return (onScreen / info.totalItemsCount).coerceIn(SCROLLBAR_MIN_LENGTH_FRACTION, 1f)
}

// ---------------------------------------------------------------------------
// 刊头
// ---------------------------------------------------------------------------

/**
 * 刊头。
 *
 * 只有一处衬线大标题 —— 整个 App 的字号峰值就落在这里，其余地方一律退让。
 * 这也是一种「设计决策」：所有地方都想强调，等于哪儿都没强调。
 */
@Composable
private fun Masthead(
    sync: SyncState,
    onUpdate: () -> Unit,
    onCheckSources: () -> Unit,
    todaySec: Int = 0,
    goalSec: Int = 40 * 60,
) {
    Column(Modifier.padding(bottom = Space.row)) {
        // 刊头与「只检查源」同行：标题占满左边，次级动作贴右。
        // 「只检查源」原来是一个与主按钮等宽的大块，而它是个**排查用**的动作
        // （看十一个源通不通），日常根本用不到。降成右上角的文字链接之后，
        // 主按钮独占一整行，首屏也省出一块
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
        ) {
            Column(Modifier.weight(1f)) {
                // 4.4.0：31sp 的刊头占掉 60dp，而这一页每天只看一次标题 ——
                // 换成 21sp 的 section 级，把高度让给列表
                // 4.5.0：标题右边一个 24dp 的环 —— 今日已听 / 目标。
                // 素材页与打卡页从此看的是同一个数（同一个容器、同一份 prefs）
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("今日素材", style = AppText.section)
                    if (goalSec > 0) {
                        Spacer(Modifier.width(12.dp))
                        TodayRing(todaySec, goalSec)
                    }
                }
                Spacer(Modifier.height(Space.tight))
                Text(
                    text = "${FeedSources.ALL.size} 个来源 · 每天更新",
                    style = AppText.caption,
                )
            }
            LinkText(
                text = "只检查来源",
                color = if (sync.running) InkFaint else InkDim,
                onClick = { if (!sync.running) onCheckSources() },
            )
        }
        Spacer(Modifier.height(Space.row))
        UpdateBlock(sync = sync, onUpdate = onUpdate)
    }
}

// ---------------------------------------------------------------------------
// 更新动作
// ---------------------------------------------------------------------------

/**
 * 更新动作区。
 *
 * 「一键更新」做成实心琥珀矩形而不是 Material 的胶囊按钮：胶囊的圆角是
 * 这套视觉里最出戏的一笔 —— 页面其它地方全是直角和细线。
 *
 * 两个动作主次分明：更新是实心的，检查源是无填充的纯文字。
 *
 * ## 为什么模型没就位时要**禁用**更新
 *
 * 不是「点了会失败」，而是**点了没有意义**：拉回来的音频没有识别模型就转不出
 * 字幕，用户拿到的是一整屏「无字幕」的条目，然后以为 App 坏了。
 * 与其让他点一次、等几分钟、最后得到一个自己看不懂的结果，不如一开始就把
 * 「还在准备、要等」摆出来。
 *
 * 「只检查源」不受影响 —— 它不下载音频、不转写，纯粹看十一个源通不通，
 * 这件事和模型没有任何关系，没理由一起拦住。
 */
@Composable
private fun UpdateBlock(
    sync: SyncState,
    onUpdate: () -> Unit,
) {
    // ⚠️ 2.1.0 把这里的「未就绪就禁用」整块删掉了。
    //
    //    它当年守的是一条硬约束：没有端侧识别模型就没有字幕，所以
    //    「模型没到位」时更新按钮必须灰着 —— 不灰的话，用户点了会下回一堆
    //    转不出字幕的音频，白白花掉流量。为此还配了一整块 [DeployNotice]
    //    （630 MB 的下载进度、「重试」按钮、「已下载的部分会保留」）。
    //
    //    现在字幕由服务器给，**下载音频的唯一前提就是有网** ——
    //    那个门禁和它背后那张提示卡都没有存在的理由了。
    //    删掉之后有个顺带的好处：设置页以外不再有任何地方需要回答
    //    「模型好了没有」，于是也就不会再出现「按钮灰着但说不清为什么」。
    Column {
        // 主按钮独占整行（1.2.0）。原来它和「只检查源」按 1.2:1 分左右，
        // 而后者是排查用的低频动作 —— 让它占掉主操作 45% 的宽度，
        // 等于在视觉上说「这两件事差不多重要」。现在它去了刊头右上角
        val bg by animateColorAsState(
            targetValue = if (sync.running) AmberFaint else Amber,
            animationSpec = Motion.standard(),
            label = "updateBg",
        )
        val fg by animateColorAsState(
            targetValue = if (sync.running) Amber else Bg,
            animationSpec = Motion.standard(),
            label = "updateFg",
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(bg)
                .pressFeedback(enabled = !sync.running, onClick = onUpdate)
                .padding(vertical = 13.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (sync.running) "正在更新" else "更新今日内容",
                style = AppText.title,
                color = fg,
            )
        }

    }
}

// ---------------------------------------------------------------------------
// 状态区
// ---------------------------------------------------------------------------

/**
 * 状态区。按「有无在跑」二选一渲染，**同一时刻只出现一块**。
 *
 * 之前是四块卡片各判各的条件，更新完成卡片和源问题卡片会同时出现，
 * 内容还互相重复。现在收敛成一个出口：在跑就报进度，跑完就报结果。
 */
@Composable
private fun StatusBlock(sync: SyncState, issues: List<SourceResult>) {
    val phase = sync.phase
    val hasSomething = sync.running ||
        phase is SyncPhase.Done ||
        phase is SyncPhase.SourceChecked ||
        phase is SyncPhase.Failed ||
        issues.isNotEmpty()

    // 1.2.0：整块的出现/消失不再是硬切。
    //
    // 原来是 `if (!hasSomething) return` —— 一整块（20dp 到上百 dp）
    // 瞬间从布局里插入或抽掉，下面的筛选签和整个列表跟着跳一下。
    // 更新刚结束那一刻这件事尤其明显：进度块消失、结果块出现，
    // 列表位置连跳两次。
    //
    // 用 AnimatedVisibility 包起来，高度变化交给 animateContentSize —— 
    // 后者是必须的，因为块**内部**还会换（RunningBlock → DoneBlock），
    // 那种变化不经过 visible 开关。
    AnimatedVisibility(
        visible = hasSomething,
        enter = Motion.blockEnter,
        exit = Motion.blockExit,
    ) {
        Column(Modifier.animateContentSize(animationSpec = Motion.resize())) {
            if (sync.running) RunningBlock(sync)

            if (!sync.running) {
                when (phase) {
                    is SyncPhase.Done -> DoneBlock(phase, issues)
                    is SyncPhase.SourceChecked -> SourceCheckedBlock(phase, issues)
                    is SyncPhase.Failed -> FailedBlock(phase.message)
                    else -> Unit
                }
            }
            Spacer(Modifier.height(Space.block))
        }
    }
}

/**
 * 正在跑的时候的状态块。
 *
 * ## 这里**不再**画进度条
 *
 * 早先顶部有一条 `(index-1+fraction)/total` 的总体进度条，而列表里的条目
 * 什么都看不出来 —— 用户的原话是「转写完成进度，放在每一条音频文件下，
 * 而不是整体放在顶部」。
 *
 * 那个总体进度条的信息量其实很小：转写是**串行**的，`2/4` 这个数字已经
 * 说明了剩余工作量，再画一条平均分配的进度条只是把同一个事实重复一遍。
 * 真正有用的是「这一条转到哪了」，而那个只有条目自己知道。
 *
 * 所以现在分工是：
 * - 这里：还剩多少条（`2/4`）——只有跳出去看别的页面时才需要
 * - 条目里：这一条的具体进度 ——用户盯着看的时候需要
 */
@Composable
private fun RunningBlock(sync: SyncState) {
    val phase = sync.phase
    val title = when (phase) {
        is SyncPhase.FetchingFeeds -> "获取内容列表"
        is SyncPhase.Downloading -> "下载音频 ${phase.index}/${phase.total}"
        is SyncPhase.FetchingSubs -> "加载字幕 ${phase.index}/${phase.total}"
        is SyncPhase.Cleaning -> "清理旧内容"
        else -> "处理中"
    }
    val detail = when (phase) {
        is SyncPhase.FetchingFeeds -> "正在获取今天的内容"
        is SyncPhase.Cleaning -> "清理最久没听的内容"
        else -> null
    }

    Column(Modifier.fillMaxWidth()) {
        Text("进行中", style = AppText.label, color = Amber)
        Spacer(Modifier.height(Space.tight))
        Text(title, style = AppText.title)
        if (detail != null) {
            Spacer(Modifier.height(3.dp))
            Text(detail, style = AppText.caption)
        }
        Spacer(Modifier.height(Space.tight))
        Text(
            text = when (phase) {
                // 2.1.0：字幕不再在手机上生成 —— 它由服务器做好、客户端取回来。
                // 说「正在生成」会让用户以为要等，而实际上一条几百毫秒。
                is SyncPhase.FetchingSubs -> "正在加载字幕"
                is SyncPhase.Downloading -> "正在下载音频 · 可以退到后台"
                else -> "退到后台也会继续（通知栏可见进度）"
            },
            style = AppText.label,
            color = InkFaint,
        )
    }
}

@Composable
private fun DoneBlock(p: SyncPhase.Done, issues: List<SourceResult>) {
    Column(Modifier.fillMaxWidth()) {
        // ⚠️ 这一行原来**只有标题没有值**（1.2.2 补）。
        //
        //    不是渲染漏了：Done 里当时压根没有时间字段，所以「上次更新」
        //    后面永远是空的 —— 四个字悬在那里，看起来像加载失败。
        //    根治要动数据（见 SyncPhase.Done.at），只在界面上补个假时间
        //    只会让下一次有人再问一遍。
        Row(verticalAlignment = Alignment.Bottom) {
            Text("上次更新", style = AppText.label)
            Spacer(Modifier.width(8.dp))
            Text(relativeTime(p.at), style = AppText.label, color = InkMid)
        }
        Spacer(Modifier.height(Space.row))
        // 指标**占满整行、等分**（1.2.2）。
        //
        // 原来是 `Arrangement.spacedBy(28.dp)` 的左对齐行：几列数字全挤在
        // 左边、右边空一大片。而 Metric 用的是等宽数字，视觉上本该是
        // 「几列对齐的表格」—— 挤在左边反而让等宽数字的优势没了。
        // 每列 weight(1f) 之后，无论四个还是五个（多出「已清理」时）都等分。
        Row(Modifier.fillMaxWidth()) {
            Metric("素材", p.fetched.toString(), Modifier.weight(1f))
            Metric("新下载", p.downloaded.toString(), Modifier.weight(1f))
            // 「已转写」→「已取字幕」：2.1.0 起字幕全部来自服务器，
            // 写「转写」会让用户以为手机上跑了识别（真机反馈里就出现过这个误解）
            Metric("已有字幕", p.subtitled.toString(), Modifier.weight(1f))
            if (p.pendingSubs > 0) {
                Metric(
                    label = "字幕未到",
                    value = p.pendingSubs.toString(),
                    modifier = Modifier.weight(1f),
                    valueColor = Amber,
                )
            }
            if (p.expired > 0) {
                Metric("已清理", p.expired.toString(), Modifier.weight(1f))
            }
        }
        // 不解释「为什么这一条没有字幕」：原因只有两种（服务器还没处理到 /
        // 服务器连不上），而两种都不需要用户做什么。他唯一需要知道的是
        // **它会不会自己好** —— 所以只说这一点。
        //
        // ⚠️ 2.1.0 改了这句话的后半句。它原来是「点条目上的『转写字幕』
        //    可以单独生成」——而现在没有任何东西可以被「生成」，
        //    那个入口已经改成「补字幕」（去服务器取一次）。让用户去点一个
        //    语义已经变了的按钮，是比不说更糟的引导。
        if (p.pendingSubs > 0) {
            Spacer(Modifier.height(Space.row))
            Text(
                text = "有 ${p.pendingSubs} 条字幕还没到，会自动补上。" +
                    "急着看就点条目上的「加载字幕」。",
                style = AppText.caption,
                color = Amber,
            )
        }
        // 「内容自己消失了」在用户侧是最需要解释的一件事：不说的话，
        // 用户只会记得「我昨天还看到这一条」
        if (p.expired > 0) {
            Spacer(Modifier.height(Space.row))
            Text(
                text = "已清理 ${p.expired} 条音频。没收藏的保留 3 天。",
                style = AppText.caption,
            )
        }
        SourceIssues(issues)
    }
}

@Composable
private fun SourceCheckedBlock(p: SyncPhase.SourceChecked, issues: List<SourceResult>) {
    val allOk = p.failed == 0
    Column(Modifier.fillMaxWidth()) {
        Text("来源检查", style = AppText.label, color = if (allOk) Olive else Amber)
        Spacer(Modifier.height(Space.tight))
        Text(
            text = if (allOk) "全部可达" else "${p.failed} 个失败",
            style = AppText.title,
            color = if (allOk) Ink else Amber,
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = "${p.ok}/${p.ok + p.failed} 个来源 · 共 ${p.totalItems} 条内容",
            style = AppText.caption,
        )
        // 一句话把边界划出来（1.2.1）。
        //
        // 加它的起因是一条很典型的误读：「点只检查源，新内容出现了，
        // 但没有下载音频」—— 用户据此以为这个按钮做了半截更新，
        // 甚至以为两个按钮接反了。
        //
        // 事实是：拉 RSS **必然**会把新条目带进索引（这是刷新的定义），
        // 但这一步确实不下音频、也不转写。不写出来，用户会一直拿
        // 「列表里有没有新内容」当作「有没有更新」的判据 —— 而那个判据
        // 在两个按钮上是同一个结果，于是永远分不清谁干了什么。
        Text(
            text = "只测了连通性，没下载音频",
            style = AppText.caption,
            color = InkFaint,
        )
        SourceIssues(issues)
    }
}

@Composable
private fun FailedBlock(message: String) {
    Column(Modifier.fillMaxWidth()) {
        Text("更新失败", style = AppText.label, color = Crimson)
        Spacer(Modifier.height(Space.tight))
        Text(message, style = AppText.caption, color = InkMid)
    }
}

/**
 * 取不到的源。
 *
 * 单个源失败不影响其他源，所以这里只作为附注列在结果下面，
 * 不单独占一块「有源没取到内容」的告示 —— 那会让偶发失败看起来像整体故障。
 */
@Composable
private fun SourceIssues(issues: List<SourceResult>) {
    if (issues.isEmpty()) return
    Spacer(Modifier.height(Space.row))
    Text("没连上的来源", style = AppText.label, color = InkFaint)
    Spacer(Modifier.height(Space.tight))
    issues.forEach { r ->
        Text(
            text = "${r.source.name} — ${r.error}",
            style = AppText.caption,
            color = InkDim,
        )
    }
}

// ---------------------------------------------------------------------------
// 筛选
// ---------------------------------------------------------------------------

/**
 * 筛选签。
 *
 * 带数量不是为了好看 —— 「收藏 0」能立刻回答「我是不是没收藏成功」，
 * 比让用户切过去看一个空列表快得多。数字用等宽，切换时不会左右抖动。
 *
 * 选中态靠**琥珀下划线**表达，不做填充块：填充块会把这一行变成一排按钮，
 * 而这里语义上更接近「刊物的分栏」。
 */
@Composable
private fun FilterTabs(
    modifier: Modifier = Modifier,
    current: LibraryFilter,
    total: Int,
    favorite: Int,
    unfinished: Int,
    onSelect: (LibraryFilter) -> Unit,
) {
    val counts = mapOf(
        LibraryFilter.ALL to total,
        LibraryFilter.FAVORITE to favorite,
        LibraryFilter.UNFINISHED to unfinished,
    )
    // 吸顶时要有**不透明背景**，否则列表内容会从底下透出来（1.2.2）。
    // 用页面底色而不是卡片色 —— 这一条是「贴住的一条」，不是浮起来的一块
    Column(
        Modifier
            .fillMaxWidth()
            .background(Bg)
            // ★ 2.1.1：把落在**空白处**的点击吃掉。
            //
            // 筛选签吸顶时这一栏是**盖在列表上面**的（stickyHeader 是一层 overlay），
            // 而它真正能接收点击的只有三个 `pressFeedback` 格子 ——
            // 顶部那段 `padding(top = Space.tight)`、格子与格子之间的缝、下划线下方的余量
            // 都**没有** pointer input。手指落在那儿，事件会穿过去打到下面的条目上，
            // 于是「点筛选签」变成了「进播放页」。
            // 用户的原话是「点击那 3 个 item，经常会误触进入播放页」——
            // 他以为点的是筛选签，其实点在那一栏的空白上。
            //
            // ⚠️ 必须写在 `.padding` **之前**：modifier 是从外到内生效的，
            //    写在后面的话消费区域会缩到 padding 以内 —— 而漏掉的恰恰就是那一段。
            // ⚠️ 它不会抢走三个格子自己的点击：`detectTapGestures` 默认只认
            //    **未被消费**的按下事件，而格子里的 `clickable` 会先消费掉。
            .pointerInput(Unit) { detectTapGestures { } }
            // ★ 4.6.2：20dp → 4dp。刊头滚走之后这一栏是贴在状态栏下面的，
            //    20dp 就是纯空白（用户两次提到"留白有点多"）。
            .padding(top = 4.dp, bottom = 2.dp),
    ) {
        // 三个筛选签**等分整行**（1.2.2）。
        //
        // 原来是 `Arrangement.spacedBy(26.dp)` 的左对齐行：三个签挤在左边，
        // 右边空一大片，而且「全部 12 / 收藏 3 / 未听完 5」宽度还各不相同，
        // 右边界参差不齐。改成每格 weight(1f) 之后，三条下划线的起点
        // 落在 0、1/3、2/3 —— 扫读时列是齐的。
        Row(Modifier.fillMaxWidth()) {
            LibraryFilter.entries.forEach { option ->
                val selected = option == current
                Column(
                    // weight 在前、clickable 在后：这样整个 1/3 格都是热区，
                    // 而不是只有「文字 + 数字」那一小块可点
                    modifier = Modifier
                        .weight(1f)
                        .pressFeedback { onSelect(option) },
                    // 每格内容**居中**（1.2.3 修）。
                    //
                    // 上一版只做了等分、内容仍左对齐，于是三个签各自贴在自己
                    // 格子的左边 —— 视觉上还是「一串靠左的文字」，等分等于白做。
                    // 居中的另一个好处是下划线也跟着到中间，三条短线在
                    // 1/6、1/2、5/6 处，扫一眼就知道哪条被选中。
                    //
                    // 数字紧跟在文字后面（同一条 Row），不是推到格子右侧 ——
                    // 那样会变成「左标题右数字」的表格感，而这里要的是
                    // 「一个词后面跟着它的计数」。
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = option.label,
                            style = AppText.title,
                            color = if (selected) Ink else InkDim,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = (counts[option] ?: 0).toString(),
                            style = AppText.digit,
                            color = if (selected) Amber else InkFaint,
                        )
                    }
                    Spacer(Modifier.height(5.dp))
                    // 下划线**铺满整格**（1.2.6）。
                    //
                    // 原来固定 26dp：它只盖住「全部」两个字，而这一格本身有
                    // 1/3 屏宽 —— 在整行等分之后，那条小短线跟版式没有任何关系，
                    // 用户的原话是「3 个 item 下方的横条太短了，要不就跟 item 一样长」。
                    //
                    // 用 `fillMaxWidth(0f..1f)` 而不是具体 dp：长度天然等于**这一格的
                    // 宽度**，换字号、换屏幕、加一个筛选签都不用回来改数字。
                    // 仍是 0 → 1 的补间（1.2.0 加的）—— 瞬变会让下划线「啪」地跳过去，
                    // 而列表内容同时整块换掉，两件事叠在一起看不出因果
                    val underline by animateFloatAsState(
                        targetValue = if (selected) 1f else 0f,
                        animationSpec = Motion.standard(),
                        label = "filterUnderline",
                    )
                    Box(
                        Modifier
                            .fillMaxWidth(underline)
                            .height(1.5.dp)
                            .background(Amber)
                    )
                }
            }
        }
        Spacer(Modifier.height(2.dp))
        Rule()
    }
}

// ---------------------------------------------------------------------------
// 条目
// ---------------------------------------------------------------------------

/**
 * 一条内容。
 *
 * 整行可点进入播放页。不可播（音频没下好）时整行不可点，但仍显示 ——
 * 用户需要看到「今天有哪些内容还没准备好」，而不是它们凭空消失。
 *
 * ## `selfBusy` 与 `engineBusy` 是两件事，不要合并
 *
 * 这里踩过一次真实的坑：早先只有一个 `busy` 参数，传进来的是全局的
 * `sync.running`。后果是**只要后台在转写任何一条，整个列表都点不动** ——
 * 连早就转写完成、音频完好、跟本次同步毫无关系的内容也进不去。
 * 用户的原话是「有转写任务未完成的时候，无法进入已经转写完成的音频」。
 *
 * 所以现在分得很清楚：
 *
 * | 参数 | 来源 | 影响 |
 * |---|---|---|
 * | [selfBusy] | `episode.status.isBusy` | **只有它**能禁用整行的点击 |
 * | [engineBusy] | `sync.running` | 只影响「转写字幕」链接的可用性 |
 *
 * 判断依据也很直白：同步引擎是**全局单例**，而「这一条能不能播」是
 * 条目自己的属性，两者在语义上没有任何关系。
 */
@Composable
private fun EpisodeRow(
    modifier: Modifier = Modifier,
    episode: Episode,
    canPlay: Boolean,
    /** 本地还没有音频 —— 点下去会边听边下（2.2.0） */
    needsAudio: Boolean,
    canTranscribe: Boolean,
    selfBusy: Boolean,
    /** 这一条正在跑的任务（带实时进度）。null = 这条没在处理 */
    task: ActiveTask?,
    engineBusy: Boolean,
    progress: ListenProgress?,
    done: Boolean,
    favorite: Boolean,
    /** 是不是正在播的那一条 */
    playing: Boolean,
    /** 现在**在不在播**（`playing` 只说「是不是这一条」） */
    isPlaying: Boolean = false,
    onPlay: () -> Unit,
    /** 就地播放（不跳播放页）—— 见右侧那个播放键 */
    onPlayHere: () -> Unit = onPlay,
    /** 还没有音频时点了这一行：请求「现在就下这一条」（2.0.3） */
    onNeedDownload: () -> Unit,
    onTranscribe: () -> Unit,
    onToggleFavorite: () -> Unit,
    /** 长按动作条里的「删除」 */
    onDelete: () -> Unit,
) {
    // 长按展开的动作条是否打开。
    //
    // 用**局部状态**而不是提到列表层：它只影响这一行，提到外面之后
    // 每开一次菜单整列表都要重组一遍。列表滚到几十条时那是看得见的卡。
    var menuOpen by remember { mutableStateOf(false) }

    // 在菜单里做一次操作就自增 —— 它是「自动收起」计时器的重启信号。
    //
    // ⚠️ 用计数而不是布尔量：连续点两次收藏，布尔量第二次就不再是变化，
    //    计时器不会重置（这与「退回列表定位用 focusNonce 而不是布尔」同一条理由）。
    var menuNonce by remember { mutableStateOf(0) }

    // 删除是这套界面里**唯一破坏性且不可逆**的动作，配一次触觉 ——
    // 触觉在这里的作用不是「确认点击」，而是让手指知道「刚才那下很重」
    val haptic = LocalHapticFeedback.current

    // 打开后 5 秒自动收起，**最后一次操作重新计时**（1.2.4）。
    //
    // 用户的原话：「按下收藏的时候不要立马把这两个按钮收起来，
    // 你可以设置个无操作 5s 收起来的动画」——点完还想看看星标亮没亮、
    // 或者接着点删除，一按就收会让人来不及确认。
    // 但也不能一直挂着：它占着高度、也把下面的内容压下去了。
    LaunchedEffect(menuOpen, menuNonce) {
        if (menuOpen) {
            delay(MENU_AUTO_HIDE_MS)
            menuOpen = false
        }
    }

    // 按下反馈要作用在**整张卡片**上，而点击/长按由 combinedClickable 接下 ——
    // 两者共用同一个 interaction source，否则会各算一份按压状态
    val pressSource = remember { MutableInteractionSource() }
    val cardShape = RoundedCornerShape(Radius.card)

    // ★ 1.2.9：内边距跟着系统字号一起长（§15「尊重系统字号」）。
    //
    // 改之前是写死的 13.dp。用户把系统字号调到 1.3 倍之后，卡片里的字全变大了，
    // 边框却还停在原地 —— 看起来是「字把卡片撑满了」，而不是「卡片变大了」。
    //
    // 上限 1.6 是刻意的：字号再大也不是等比放大内边距的理由，那只会让
    // 一屏塞进更少的条目。1.6 之后内边距不再长，字号继续长。
    //
    // ⚠️ 不要改用 `LocalDensity.current` 的其它字段：`density` 本身（dp↔px）
    //    在各机型上是稳定的，只有 `fontScale` 才是「用户把字调大了」。
    val fontScale = LocalDensity.current.fontScale.coerceIn(1f, 1.6f)
    val cardPad = (13f * fontScale).dp

    // ★★ 4.4.0：**卡片 → 行**。
    //
    //    原来的每一期都是一张白底 + 1dp 描边 + 13dp 内边距的卡：
    //    一屏只放得下 4 条，而且十几张卡并排时没有任何一条是重点。
    //    通勤场景要的是「扫一眼挑一条」，卡片把扫视的节奏打断了。
    //
    //    现在：无底色（正在播的那条才有 8% 琥珀底 + 左侧 2dp 竖条）、
    //    无描边、分隔线在行尾**从文字左沿起画**（见下面的 Divider）。
    //    这一条缩进是「精致」最廉价也最有效的一招 —— 通栏分隔线会让整页
    //    看起来像一张表格。
    //
    //    ⚠️ 菜单仍然包在这个 Column 里：展开时跟着长高，而不是在外面另起一层。
    Column(
        // ⚠️ 4.6.4：**整行琥珀底删了**。
        //    它在当前页边距下，左边缘必然和封面贴在一起（封面就在内容左沿），
        //    看起来像"封面被压着" —— 除非把页边距从 LazyColumn 挪进每一行
        //    （那会牵动表头 / 页脚 / 空态四处对齐，不值得）。
        //    「正在播」现在由三件事表达：**琥珀色标题**（本来就有）+
        //    暂停图标 + 底部迷你条。这三样在截图里都看得见。
        modifier
            .fillMaxWidth()
            .pressScale(pressSource),
    ) {
        // ⚠️ 4.6.3：**左侧那条 2dp 竖条删了**（用户："位置不对"）。
        //    它画在行的最左沿，而行的背景是整行宽的 ⇒ 那条线贴着屏幕边，
        //    看起来像一条不知道属于谁的装饰线（截图里就是这样）。
        //    「正在播」现在只靠三件事表达：浅琥珀底（这行）+ 暂停图标 + 底部迷你条。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = (12f * fontScale).dp)
                // ⚠️ `enabled` 不能跟着 canPlay 走（1.2.2）。
                //
                //    老写法是 `clickable(enabled = canPlay && !selfBusy)`，
                //    于是「还没有音频/转写失败」的条目**长按也毫无反应** ——
                //    而用户最想删的恰恰是那些下不下来、转不出来的废条目。
                //    所以点击的可用性改到 onClick 里判，长按对所有条目都有效。
                .combinedClickable(
                    interactionSource = pressSource,
                    // 显式关掉水波纹：整套视觉只有一个按压语汇（缩放），
                    // 两种同时出现比没有反馈更糟
                    indication = null,
                    onClick = {
                        menuOpen = false
                        // ★ 2.0.3：这里原来是 `if (canPlay && !selfBusy) onPlay()` ——
                        //   两个条件都会**静默**吞掉点击：不进播放页、不弹提示、
                        //   日志里连一行都没有。真机上用户的原话是「这个音频我也无法播放」，
                        //   而真相只是「它还没下载」。
                        //
                        //   现在只有一条规则：能播就播，不能播就**现在把它下下来**。
                        //
                        //   `selfBusy` 不再参与判定 —— 它在 2.0 里是「正在下载 / 正在转写」，
                        //   而「正在转写」说明音频早在盘上、完全能播，过去也被一起挡住了。
                        //   音频没落地时列表这一行本来就该自己变成「下载中」的进度态
                        //   （`task` 那一块），那才是用户能看懂的下文。
                        if (canPlay) {
                            // ★ 2.2.0：本地没音频时**先开播**（边下边播），
                            //   同时登记一次下载让它后台落盘 —— 下次离线也能听。
                            //
                            //   顺序不能反：登记会拉起更新服务，但它要几百毫秒才开跑，
                            //   不会挡住起播；反过来先 onPlay 的话，那份「必须下这一条」
                            //   的请求会被挤到列表末尾，落盘要等整整一轮。
                            //
                            //   这一条同时兑现了「懒加载不能变成『点了也不给下』」：
                            //   用户明确点过的内容**一定要**落到本地。
                            if (needsAudio) {
                                DiagLog.act(
                                    "点开一条还没落盘的 → 先开播（边听边下），" +
                                        "同时排入后台下载 · " + episode.title.take(24)
                                )
                                onNeedDownload()
                            }
                            onPlay()
                        } else {
                            DiagLog.act(
                                "点了一条还没下好音频的内容 → 现在就下它 · " +
                                    episode.title.take(24)
                            )
                            onNeedDownload()
                        }
                    },
                    // 再长按一次收起 —— 否则误触之后只能点别处，而这一行
                    // 没有「别处」可点（整行都被这里接管了）
                    onLongClick = { menuOpen = !menuOpen },
                ),
        ) {
            // 封面。它承担的是「一眼认出这是哪家」——在加了 11 个源之后，
            // 这件事比标题更重要：同一屏里会出现 11 个不同来源的内容，
            // 而标题都是英文长句，扫读时区分度远不如一张方图。
            // ★ 4.4.0：封面是这一页**唯一能「一眼认节目」**的东西，
            //    所以它当主角：外框 48、圆角 9、图 42 居中 ——
            //    留出的 3dp 环带用来画收听进度。
            //
            //    ⚠️ 进度**不再另起一个环或一条进度条**：同一个物件同时表达
            //    「是什么」和「听了多少」，少一个元素、多一层沉浸（见 4.4.0 设计稿）。
            // ⚠️ 4.6.2：**封面外那一圈进度弧删了**（用户：字母块上一个圆环，
            //    又丑又没意义）。原因很实在：封面还没加载出来时，它绕着一个
            //    字母块，读起来像「这个块被圈住了」，而不是「听了 62%」。
            //    进度信息保留在元信息行的「已听 03:12」里 —— 数字比弧线准确。
            Artwork(
                sourceId = episode.sourceId,
                // ★★ 4.6.5：**用目录里的地址，不要自己推导**。
                //
                //    4.6.3 我推导成 `art/<episode.id>.jpg` —— 那条**永远 404**：
                //      · 客户端 id  = `sha1(guid)`（RssParser.stableId）
                //      · 服务端封面 = `guid尾_md5(audioUrl).jpg`（cycle.py 的 eid）
                //    两套 id 本来就不一样，而且**不能**让任一端去模仿另一端
                //    （stableId 的注释写着「算法只留在客户端一处」）。
                //
                //    目录刷新时 `refreshFeeds` 会用**新条目**做 copy（只保留本地状态），
                //    所以 `artworkUrl` 一定会被换成服务端发布时的托管地址 ✅。
                artworkUrl = episode.artworkUrl,
                placeholder = episode.sourceName,
                size = 48.dp,
                corner = Radius.cover,
            )

            Spacer(Modifier.width(11.dp))

            Column(Modifier.weight(1f)) {
                // kicker：来源 + 时间
                // ★ 2.2.3：这一行的高度必须**钉死**。
                //
                //   它原来的高度是被 FavoriteMark 的 44dp 触摸区撑出来的，
                //   而 kicker 文字在行内垂直居中 —— 于是文字上方凭空多出
                //   (44 − 16) / 2 ≈ 14dp 空白。
                //
                //   真机上逐像素量出来是：**上留白 30dp、下留白 16dp**，
                //   整个文字块看起来往下沉（用户的原话「感觉有点整体偏下」）。
                //   卡片的 padding 四边都是 13dp，所以问题**不在 padding**。
                //
                //   定成 26dp 之后，星标的触摸区跟着变成 44×26，
                //   文字居中只多 5dp —— 上下留白差从 14dp 收到 2dp。
                //
                //   ⚠️ 高度要跟着 fontScale 长（见上面 cardPad 那段）：
                //      系统字号调大时行高不变，同样会把行撑出偏下的空白。
                Text(
                    text = episode.title,
                    style = AppText.title,
                    color = when {
                        playing -> Amber
                        canPlay -> Ink
                        else -> InkMid
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )

                Spacer(Modifier.height(4.dp))
                // ★ 4.6.1：**钉死高度改成了最小高度 + 单行省略**。
                //
                //    原来钉 26dp 是为了拦「星标 44dp 触摸区把这行撑高、文字被压偏」
                //    （2.2.3）。但这一行现在装的是 `来源 · 时长 · 字幕 N 句 · 已听`，
                //    一行放不下就换行、换行又超出 26dp ⇒ **下半行被裁掉**（用户截图）。
                //
                //    正解不是"把高度调大"（字号一放大又裁），而是**把星标搬走**：
                //    它现在在整行的最右端、和播放键一起垂直居中 ——
                //    这一行于是没有 44dp 的子元素，最小高度就够了。
                Row(
                    modifier = Modifier.heightIn(min = (20f * fontScale).dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 「正在播」的指示放在最前面，而不是靠标题变色单独承担 ——
                    // 琥珀色标题在深色底上够明显，但在亮色主题下的对比度会弱一些，
                    // 加一个只有它才有的三角，两种主题下都不会认错
                    if (playing) {
                        GlyphIcon(
                            glyph = Glyph.Play,
                            size = 8.dp,
                            tint = Amber,
                            strokeWidth = 2f,
                        )
                        Spacer(Modifier.width(5.dp))
                    }
                    // ★ 4.6.4：**把这一行拆成「可让位」和「不可让位」两段**。
                    //
                    //    原来是一整串 `来源 · 时长 · 字幕 N 句 · 已听 X`，
                    //    一行放不下就从**尾巴**开始省略 —— 于是被省略的
                    //    恰恰是「字幕 N 句」这种有用的信息，看起来像"字被藏了"。
                    //
                    //    现在：左边只放**来源**（放不下就省略它，无所谓），
                    //    右边放**时长 / 已听**（永远完整）。
                    //    「字幕 N 句」整条删掉 —— 它是诊断信息，不是决策信息：
                    //    需要补字幕时那一行会自己出现「加载字幕」。
                    Text(
                        text = episode.sourceName,
                        style = AppText.caption,
                        color = if (playing) Amber else if (canPlay) InkDim else InkFaint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = metaRight(episode, done, needsAudio, progress),
                        style = AppText.caption,
                        color = if (playing) Amber else InkFaint,
                        maxLines = 1,
                    )
                    if (selfBusy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(11.dp),
                            strokeWidth = 1.4.dp,
                            color = Amber,
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                }

                // 补字幕仍然是一个**动作**，但只在需要时占一行；
                // 常见情况（已经有字幕）不再多付那 26dp
                if (canTranscribe && task == null) {
                    val label = if (engineBusy) "加载字幕（稍等）" else "加载字幕"
                    Text(
                        text = label,
                        style = AppText.caption,
                        color = if (engineBusy) InkFaint else Amber,
                        textDecoration = TextDecoration.Underline,
                        modifier = Modifier
                            .pressFeedback(enabled = !engineBusy, onClick = onTranscribe)
                            .padding(top = 6.dp, bottom = 2.dp),
                    )
                }

                // 处理进度排在收听进度前面，而且**两者只画一条**：
                // 同时出现就是上下两条进度条，没人分得清哪条是哪条。
                // 正在处理时用户关心的是「还要等多久」，不是「上次听到哪」。
                //
                // 进度画在这一条自己下面 —— 这正是用户提的第 3 条要求：
                // 早先它只在页面顶部有一个总的进度，列表里完全看不出在转哪一条。
                if (task != null) {
                    Spacer(Modifier.height(10.dp))
                    Rule(progress = task.fraction.takeIf { it > 0f }, height = 3.dp, color = Amber)
                    Spacer(Modifier.height(5.dp))
                    Text(
                        text = "${task.label} ${(task.fraction * 100).toInt()}%",
                        style = AppText.label,
                        color = Amber,
                    )
                }

                // ⚠️ 4.4.0：收听进度**只由封面上的弧线 + kicker 里的「已听」表达**。
                //    这里原来还有一条 Rule + 一行「已听 03:12」—— 和弧线重复，
                //    而且那一块占 18dp（一屏少半条）。

                episode.error?.let { err ->
                    Spacer(Modifier.height(7.dp))
                    Text(err, style = AppText.caption, color = Crimson)
                }
            }
                // 播放键 + 星标：放在**整行的最右端**、垂直居中。
                // ⚠️ 它们不能待在元信息行里 —— 星标的 44dp 触摸区会把那一行撑高，
                //    而那一行现在是「最小高度」（见上面的注释），撑高又会压偏文字。
                Box(Modifier.align(Alignment.CenterVertically)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RowPlayGlyph(
                            pausing = playing && isPlaying,
                            enabled = canPlay,
                            onClick = onPlayHere,
                        )
                        Spacer(Modifier.width(2.dp))
                        FavoriteMark(favorite = favorite, onClick = onToggleFavorite)
                    }
                }
        }

        // 长按动作条（1.2.2）。
        //
        // ## 为什么不用 Material 的 DropdownMenu
        //
        // 它自带圆角 + 卡片阴影，而这套视觉从刊头到分割线全是**直角和细线** ——
        // 一个浮起来的圆角卡在页面中间是整套设计里最出戏的一笔
        // （同样的理由，更新按钮当初也没用胶囊按钮）。
        //
        // 改成**在原位向下展开**一条动作行：没有浮层、没有定位问题
        // （长按点可能在这行的任意位置），也不会遮住后面的内容。
        AnimatedVisibility(
            visible = menuOpen,
            enter = Motion.blockEnter,
            exit = Motion.blockExit,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                // 居中（1.2.4）。贴左的时候它和条目的正文挤在同一条视线上，
                // 看起来像「又一个字段」；居中之后才像「针对这一条的两个动作」。
                // spacedBy 的第二个参数就是对齐方式，不必再套一层 Box
                horizontalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 文案跟着当前状态走：已收藏的显示「取消收藏」。
                // 这一条是用户点名要的 —— 菜单上写着「收藏」而点下去是取消，
                // 会让人不敢点第二下
                Text(
                    text = if (favorite) "取消收藏" else "收藏",
                    style = AppText.caption,
                    color = if (favorite) Amber else InkDim,
                    modifier = Modifier.pressFeedback {
                        // 不收起：让 5 秒计时器管（点完还要看一眼星标）
                        menuNonce++
                        onToggleFavorite()
                    },
                )
                Text(
                    text = "删除",
                    style = AppText.caption,
                    color = Crimson,
                    modifier = Modifier.pressFeedback {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        // 删除会把这整条从列表里拿掉，菜单跟着消失 ——
                        // 这里显式收起只是防御（万一将来改成不删条目）
                        menuOpen = false
                        onDelete()
                    },
                )
            }
        }

        // 分隔线：**从文字左沿起画**（封面 48 的宽度 + 11 间距），不顶到屏幕边。
        // 通栏分隔线会让整页看起来像一张表格；缩进之后每一条自成一段。
        // 正在播的那条不画 —— 它有底色和竖条，再画一条线是多余的
        if (!playing) {
            val line = Hairline
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 59.dp)
                    .height(0.5.dp)
                    .background(line),
            )
        }
    }
}

/**
 * 「这一条正在处理」的状态，供列表把进度画到对应条目上。
 *
 * 从 [SyncPhase] 里抽出来而不是让界面直接 `when (phase)`：
 * 界面上要用它的地方有两处（列表匹配、顶部汇总），各写一遍 when
 * 就有两处会漏改的可能性 —— 加一个阶段时只改一处，另一处静默失效。
 */
internal data class ActiveTask(
    val episodeId: String,
    /** 面向用户的动作名，直接进文案 */
    val label: String,
    val fraction: Float,
)

internal fun SyncPhase.activeTask(): ActiveTask? = when (this) {
    is SyncPhase.Downloading -> ActiveTask(episodeId, "下载中", fraction)
    // 2.1.0：它原来是「转写中」，带一个 0f..1f 的进度。取字幕没有中间进度
    // （一个请求几百毫秒），所以这里固定给 1f —— 条目的进度条会显示成满格，
    // 语义是「这一条正在被处理」，而不是「处理到几成了」
    is SyncPhase.FetchingSubs -> ActiveTask(episodeId, "加载字幕中", 1f)
    else -> null
}

/**
 * 卡片上的元信息胶囊（1.2.8）。
 *
 * 底色用 [CardChip] 而不是 [BgRaised] —— 后者是**相对页面**的抬升色，
 * 深色下压在卡片上只差 3/255，等于没有底色。这条是在设计稿阶段
 * 用截图核对时抓到的（见 Theme.kt 里 cardChip 的说明）。
 */
@Composable
private fun MetaPill(text: String, done: Boolean = false, amber: Boolean = false) {
    Text(
        text = text,
        style = AppText.pill,
        color = when {
            amber -> Amber
            done -> Olive
            else -> InkDim
        },
        modifier = Modifier
            .clip(RoundedCornerShape(Radius.pill))
            .background(if (amber) AmberFaint else CardChip)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/**
 * kicker：**只有来源**。
 *
 * ## ⚠️ 这里故意不显示相对时间（2.2.2），别顺手加回来
 *
 * 列表的顺序不是时间序，而是 [interleaveBySource] 的「源间交错」——
 * 为了让第一屏能看到不同来源（否则 NPR News Now 每小时一期、
 * 在索引里占 65%，第一屏七条全是它）。
 *
 * 于是「位置」和「时间」变成两个互相打脸的信号：实测 65 条里有
 * 23 个相邻位置是时间倒挂的（后一条比前一条更新）。两者同时摆在
 * 屏幕上，用户只会得出一个结论 —— **排序坏了**。原话：
 * 「这些素材的时间戳我是没看懂，4 小时前的比 1 小时前的排的靠前」。
 *
 * 那个时间戳当时就不是信息，是**误导**：它在暗示一个列表并没有遵守的
 * 顺序。去掉之后，列表诚实地表现为「一个混排的推荐流」，
 * 而不是「一个排错了的时间流」。
 *
 * ## 什么时候才可以把时间加回来
 *
 * 先解决内容分布 —— 索引上限 40 条，但「每小时一更」和「每周一更」
 * 占掉的价值差着数量级。高频源限流做完（见 [interleaveBySource]
 * 结尾那段「更彻底的做法」）、排序回归严格时间倒序之后，
 * 加回时间才是对的。**在那之前加回来，等于把同一个误解再造一遍。**
 */
/**
 * kicker 那一行的高度（dp，未乘 fontScale）。
 *
 * ⚠️ **必须钉死**：这一行里放了 44dp 触摸区的星标，如果不给它确定的高度，
 * 行高就会被星标撑成 44dp，而文字在行内垂直居中 —— 上方凭空多出约 14dp，
 * 整块内容看起来往下沉。实测（逐像素量真机截图）：上留白 30dp、下留白 16dp。
 */
private const val KICKER_ROW_DP = 26f

/** 这一条属于哪个时间组（只看**日历日**，不是「多少小时前」） */
private enum class DayGroup(val label: String) { TODAY("今天"), YESTERDAY("昨天"), EARLIER("更早") }

private fun groupOf(ep: Episode): DayGroup {
    val ms = ep.publishedAt ?: return DayGroup.EARLIER
    val cal = java.util.Calendar.getInstance().apply { timeInMillis = ms }
    val today = java.util.Calendar.getInstance()
    val sameYear = cal.get(java.util.Calendar.YEAR) == today.get(java.util.Calendar.YEAR)
    if (sameYear && cal.get(java.util.Calendar.DAY_OF_YEAR) == today.get(java.util.Calendar.DAY_OF_YEAR)) {
        return DayGroup.TODAY
    }
    today.add(java.util.Calendar.DAY_OF_YEAR, -1)
    if (sameYear && cal.get(java.util.Calendar.DAY_OF_YEAR) == today.get(java.util.Calendar.DAY_OF_YEAR)) {
        return DayGroup.YESTERDAY
    }
    return DayGroup.EARLIER
}

/**
 * 换组时返回组名，否则 null。
 *
 * ⚠️ 列表第一条（`previous == null`）**也要返回** —— 否则第一屏没有组名。
 */
private fun dayGroupLabel(ep: Episode, previous: Episode?): String? {
    val g = groupOf(ep)
    if (previous == null) return g.label
    return if (groupOf(previous) == g) null else g.label
}

/** 组标题：11sp 淡色，只是「路标」，不该抢标题的注意力 */
@Composable
private fun GroupHeader(label: String) {
    // 组标题 + 一条延到右边的 hairline —— 它把「今天 / 昨天 / 更早」变成
    // 真正的分段，而不只是三个小字。线是发丝级，不抢标题的注意力。
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Space.row, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = AppText.label, color = InkFaint)
        Spacer(Modifier.width(10.dp))
        val line = Hairline
        Box(
            Modifier
                .weight(1f)
                .height(0.5.dp)
                .background(line),
        )
    }
}

/** 今日进度的小环：弧长 = 已听 / 目标；右边跟一行 `12/40` */
@Composable
private fun TodayRing(todaySec: Int, goalSec: Int) {
    val frac = (todaySec.toFloat() / goalSec).coerceIn(0f, 1f)
    val arc = Amber
    val track = AmberFaint
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(24.dp)) {
            val w = 2.dp.toPx()
            val inset = w / 2
            val side = size.minDimension - w
            drawArc(
                color = track, startAngle = 0f, sweepAngle = 360f, useCenter = false,
                topLeft = Offset(inset, inset), size = Size(side, side), style = Stroke(w),
            )
            if (frac > 0f) {
                drawArc(
                    color = arc, startAngle = -90f, sweepAngle = 360f * frac, useCenter = false,
                    topLeft = Offset(inset, inset), size = Size(side, side),
                    style = Stroke(w, cap = StrokeCap.Round),
                )
            }
        }
        Spacer(Modifier.width(6.dp))
        Text("${todaySec / 60}/${goalSec / 60}", style = AppText.label, color = InkDim)
    }
}

/**
 * 元信息行**右侧**：只放「不看就会被误判」的那一个数（4.6.4）。
 *
 * - 听了一半 → `2:10 / 4:40`（比只给时长有用：一眼知道还差多少）
 * - 本地没音频 → `4:40 · 边听边下`
 * - 其余 → `4:40`
 *
 * ⚠️ 刻意**不放**「字幕 N 句」：它是诊断信息，不是决策信息。
 *    真正需要用户动手时（缺字幕），那一行会自己出现「加载字幕」的链接。
 */
private fun metaRight(
    episode: Episode,
    done: Boolean,
    needsAudio: Boolean,
    progress: ListenProgress?,
): String {
    val dur = episode.durationLabel ?: "时长未知"
    return when {
        done -> "已听完 · $dur"
        progress != null && progress.listenedMs > 0 ->
            "${SrtTrack.formatClock(progress.listenedMs)} / $dur"
        needsAudio -> "$dur · 边听边下"
        else -> dur
    }
}

/** `来源 · 时长 · 字幕 N 句 · 已听 03:12` —— 全部压成**一行** */
private fun kickerLine(
    episode: Episode,
    done: Boolean,
    needsAudio: Boolean,
    progress: ListenProgress?,
): String = buildString {
    append(buildKicker(episode))
    append(" · ")
    append(metaLine(episode, done, needsAudio))
    if (progress != null && !done) {
        append(" · 已听 ")
        append(SrtTrack.formatClock(progress.listenedMs))
    }
}

/**
 * 行内播放键（4.6.0，设计稿 B 版）。
 *
 * ## 为什么要它
 *
 * 整行都能点，但**没有任何东西告诉你"可以点"**。通勤时单手操作，
 * 一个看得见的按钮比一个看得见的热区重要。
 *
 * ## 为什么是「就地播放」
 *
 * 点按钮 = 开始播但**不跳播放页**（列表还在，底部出现正在播的那条）；
 * 点整行 = 进播放页。两个热区各管一件事 ——
 * 通勤路上最常见的是「先放起来，边收拾边听」，少一次跳转就是省一次等待。
 */
@Composable
private fun RowPlayGlyph(pausing: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val tint = if (enabled) InkMid else InkFaint
    val ring = if (enabled) InkDim else InkFaint
    Box(
        Modifier
            .size(30.dp)
            .pressFeedback(enabled = enabled, scale = 0.9f, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(30.dp)) {
            drawCircle(color = ring.copy(alpha = 0.22f), radius = size.minDimension / 2 - 0.5f, style = Stroke(1f))
        }
        if (pausing) {
            Canvas(Modifier.size(9.dp)) {
                drawRect(color = tint, topLeft = Offset(0f, 0f), size = Size(size.width * 0.32f, size.height))
                drawRect(color = tint, topLeft = Offset(size.width * 0.68f, 0f), size = Size(size.width * 0.32f, size.height))
            }
        } else {
            Canvas(Modifier.size(10.dp)) {
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(size.width * 0.12f, 0f)
                    lineTo(size.width, size.height / 2)
                    lineTo(size.width * 0.12f, size.height)
                    close()
                }
                drawPath(path, color = tint)
            }
        }
    }
}

private fun buildKicker(episode: Episode): String = episode.sourceName

/** 元信息行：时长 · 字幕句数，或「已听完」结论 */
private fun metaLine(episode: Episode, done: Boolean, needsAudio: Boolean): String = buildString {
    if (done) {
        append("已听完")
        episode.durationLabel?.let { append(" · $it") }
    } else {
        append(episode.durationLabel ?: "时长未知")
        // ★ 2.2.0：本地还没落盘时**必须说清** —— 那一行现在点下去是**在线听**
        //   （边听边下），而不是放本地文件。不说的话用户会以为这一条已经离线可听，
        //   然后在没有信号的地铁里点开、转圈，而这一版以前从没发生过那件事。
        //   用词跟用户自己的说法一致（「像主流音乐软件那样边听边下」）。
        if (needsAudio) append(" · 边听边下")
    }
    if (episode.segmentCount > 0) {
        append(" · 字幕 ")
        append(episode.segmentCount)
        append(" 句")
    } else if (episode.srtFileName == null && episode.isPlayable) {
        // ★ 2.0.3：有音频、还没字幕 —— 说「待补」而不是留空。
        //
        //   留空的后果在真机上出现过：这一行看起来和「有字幕但界面没显示」
        //   完全一样，用户唯一的线索是下方那行红字（而红字说的还是错的：
        //   让他在没装模型的时候去「装离线识别」）。中性色的胶囊说清状态，
        //   红色只留给真正的问题（见 SyncEngine.SERVER_DOWN_HINT）。
        append(" · 字幕加载中")
    }
}

/**
 * 收藏星标。
 *
 * 字形 17sp 但触摸区撑到 34dp —— 列表里手指落点本来就偏，
 * 按字形大小给点击区经常点不中，而「点了没反应」比「图标难看」糟得多。
 */
@Composable
private fun FavoriteMark(favorite: Boolean, onClick: () -> Unit) {
    // 颜色补间：收藏是个「表态」动作，给它一点确认感。
    // 不做缩放弹跳 —— 那和这套纸面视觉不搭（见 Motion 的说明）
    val tint by animateColorAsState(
        targetValue = if (favorite) Amber else InkFaint,
        animationSpec = Motion.quick(),
        label = "favoriteTint",
    )
    Box(
        modifier = Modifier
            // ⚠️ 这里**不能**写 `.size(44.dp)`（2.2.3 改）。
            //
            //    它会让这个 Box 撑高所在的 Row —— 而那一行的高度本该由
            //    kicker 的文字决定。后果是文字在 44dp 的行里垂直居中，
            //    上方多出 14dp 空白，整块内容看起来往下沉（见 EpisodeRow
            //    里那段关于 KICKER_ROW_DP 的说明）。
            //
            //    改成「宽 44、高随行」：触摸区跟着行高走（44×26）。
            //    宽度保持 44 是因为星标贴着列表右缘，**水平方向最容易点偏** ——
            //    垂直方向上下都是空白，少一点不影响命中。
            .width(44.dp)
            .fillMaxHeight()
            .pressFeedback(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        GlyphIcon(
            glyph = if (favorite) Glyph.StarFilled else Glyph.Star,
            size = 17.dp,
            tint = tint,
            strokeWidth = 1.5f,
        )
    }
}

// ---------------------------------------------------------------------------
// 空态
// ---------------------------------------------------------------------------

/**
 * 空态。三种情况必须说清楚，否则用户会以为「内容全没了」：
 * 正在更新 / 没更新过 / 筛选后为空。
 *
 * ⚠️ 2.1.0 删掉了原来排在最前面的那一档「正在首次部署」。
 *    它存在的理由是「更新按钮此时是灰的，所以不能让用户照着指引去点它」——
 *    而那个按钮现在任何时候都能点（唯一的门禁是有没有网），
 *    所以这一档既没有对应的状态，也没有要避开的坑。
 */
@Composable
private fun EmptyBlock(
    running: Boolean,
    filter: LibraryFilter,
    hasAny: Boolean,
    modifier: Modifier = Modifier,
) {
    val (title, body) = when {
        running -> "正在获取内容" to "正在拉取列表并下载音频。"
        !hasAny -> "还没有内容" to
            "从 ${FeedSources.ALL.size} 个公开新闻源拉取今天的音频。"
        filter == LibraryFilter.FAVORITE -> "还没有收藏" to
            "想反复听的内容，点一下星标就会收进这里。"
        else -> "没有未听完的内容" to
            "这一批都听完了。换一批新的，或回「全部」重听。"
    }
    Column(
        modifier
            .fillMaxWidth()
            .padding(top = Space.section, bottom = Space.section)
    ) {
        Text(title, style = AppText.section)
        Spacer(Modifier.height(Space.row))
        Text(body, style = AppText.body)
    }
}

// ---------------------------------------------------------------------------
// 页脚
// ---------------------------------------------------------------------------

/**
 * 页脚。
 *
 * 「清理已听完」只在真有听完的内容时出现 —— 平时多一个按钮只是噪音。
 * 「清空全部缓存」是破坏性操作，做成无填充的暗色文字，不与主操作抢注意力。
 */
@Composable
private fun Colophon(bytes: Long, doneCount: Int, onClearPlayed: () -> Unit, onClear: () -> Unit) {
    Column(Modifier.padding(top = 34.dp)) {
        Rule()
        Spacer(Modifier.height(Space.row))
        Text("离线内容", style = AppText.label)
        Spacer(Modifier.height(Space.tight))
        Text(
            text = formatBytes(bytes),
            style = AppText.digitLarge,
        )
        Spacer(Modifier.height(3.dp))
        Text("超出上限会自动淘汰最久没听的", style = AppText.caption, color = InkFaint)
        Spacer(Modifier.height(Space.row))
        Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            if (doneCount > 0) {
                Text(
                    text = "清理已听完（$doneCount）",
                    style = AppText.caption,
                    color = InkDim,
                    textDecoration = TextDecoration.Underline,
                    modifier = Modifier.pressFeedback(onClick = onClearPlayed),
                )
            }
            Text(
                text = "清空全部缓存",
                style = AppText.caption,
                color = InkFaint,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier.pressFeedback(onClick = onClear),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 零件
// ---------------------------------------------------------------------------
//
// `Metric` 与 `formatBytes` 已经不在这里了（1.2.2）—— 它们和打卡页、
// 设置页各自那一份逐字节相同，三份都得改一次「缓存怎么显示」。
// 收到 `ui/theme/Parts.kt` 之后，「一致」是默认行为，不一致才需要写出来。
//
// ⚠️ 但 `relativeTime` **留在这里**，别顺手也搬过去：
//    它比 Parts/BuildInfo 里那个多一个 `epochMillis <= 0 → "时间未知"` 的分支 ——
//    RSS 没给发布日期的条目会走到那里。搬过去之后那个分支会被
//    「delta 是巨大正数 → 20500 天前」吃掉，界面上就会冒出「20500 天前」。
//
//    2.2.2 起它的唯一调用点是同步卡片那行「上次更新」—— 列表条目的
//    kicker 不再显示时间了（理由写在 buildKicker 上）。别因为
//    「只剩一个调用点」就把它并回通用版本：那个分支仍然需要。

// ---------------------------------------------------------------------------

/** 收听进度：比例用于进度条，毫秒用于文案 */
private data class ListenProgress(val fraction: Float, val listenedMs: Long)

/**
 * 算出这条的收听进度。
 *
 * 三种情况返回 null，都是「不该画进度条」的情形：
 *  - 已经听完（断点位置被清掉了，画出来只会是 0%）
 *  - RSS 没给时长（分母未知，比例没有意义）
 *  - 还没听过、或者只听了开头三秒（低于落盘门槛，本来就没记录）
 */
private fun progressOf(
    episode: Episode,
    positions: Map<String, Long>,
    doneIds: Set<String>,
): ListenProgress? {
    if (episode.id in doneIds) return null
    if (episode.durationSec <= 0) return null
    val listened = positions[episode.id] ?: return null
    if (listened <= 0L) return null
    val fraction = (listened.toDouble() / (episode.durationSec * 1000.0)).toFloat()
    return ListenProgress(fraction.coerceIn(0f, 1f), listened)
}

/**
 * `51 分钟前` / `刚刚` / `4 小时前`。
 *
 * ⚠️ 2.2.2 起**只有同步卡片那行「上次更新」用它**。列表条目的 kicker
 * 已经不再显示相对时间了（见 buildKicker）—— 列表的排序是源间交错、
 * 不是时间序，把时间摆在旁边会被读成「排序坏了」。
 *
 * `epochMillis <= 0` 这条分支是它跟 `Parts`/`BuildInfo` 里那个同名函数的
 * **唯一区别**，别删：丢掉它，「没有发布时间的条目」会显示成 20500 天前。
 */
private fun relativeTime(epochMillis: Long): String {
    if (epochMillis <= 0L) return "时间未知"
    val diffMin = (System.currentTimeMillis() - epochMillis) / 60_000
    return when {
        diffMin < 1 -> "刚刚"
        diffMin < 60 -> "$diffMin 分钟前"
        diffMin < 1440 -> "${diffMin / 60} 小时前"
        else -> "${diffMin / 1440} 天前"
    }
}

/**
 * 源间交错：不让同一来源的内容连续排在一起。
 *
 * ## 为什么需要
 *
 * 索引是按 `publishedAt` 倒序的，而 NPR News Now 是**每小时**更新 ——
 * 它永远占据列表最前面两格。所以哪怕加到 11 个源，第一屏看上去
 * 还是「全是 NPR」：用户的原话是「我每次刷新，出来的都是 NPR 源」。
 *
 * 问题不在排序错了，而在于排序**只有一个维度**。这里加一个软约束：
 * 在整体保持「由新到旧」的前提下，优先挑选与上一条来源不同的内容。
 *
 * ## 算法
 *
 * 每个源各自维护一个已排好序的队列（队列内部仍是新→旧），每轮从各队首
 * 里挑一条：**优先挑「来源 ≠ 上一条」中最新的一条**；如果所有候选都同源，
 * 就只能接着排。等价于「多路归并 + 相邻不同源」。
 *
 * 效果是第一屏依次出现各源各自最新的一条（按时间比较），
 * 高频源不再霸屏，而整体趋势仍然是新的在前。
 *
 * ## ⚠️ 它的代价必须跟界面改动配对（2.2.2）
 *
 * 这个算法保证的是「相邻不同源」，**不保证**「位置顺序 = 时间顺序」：
 * 实测 65 条里有 23 个相邻位置是时间倒挂的（后一条比前一条更新）。
 * 所以从 2.2.2 起，列表条目**不再显示相对时间** —— 两者同时出现时，
 * 用户看到的是「这个 App 排序坏了」，而不是「这是一个混排流」。
 * 改这个函数之前先读 buildKicker 上那段说明。
 *
 * 唯一的例外在最前面：第一轮 `lastSource` 是 null，取的是全局最大 ——
 * **列表第 1 条永远是真正最新的那一条**，这条性质可以依赖。
 *
 * ## 更彻底的做法（还没做）
 *
 * 病根在内容层：索引上限 40 条，但「每小时一更」的源一天就能吃掉 60%
 * （NPR News Now 在服务端目录里占 42/65），而它那些期次绝大多数是
 * 重复度极高的整点新闻。若给高频源限流（例如只保留最近 3 条），
 * 排序就能回归严格时间倒序，列表也就能重新显示时间。
 * **那才是根治；本函数只是当下的缓解。**
 *
 * 单次复杂度 O(n × 源数)，n ≤ 40、源数 = 11，可忽略。
 * 只在派生列表上做，**不回写数据层** —— 索引顺序保持纯粹的「按时间」语义。
 */
private fun interleaveBySource(episodes: List<Episode>): List<Episode> {
    if (episodes.size <= 2) return episodes

    val queues = episodes
        .groupBy { it.sourceId }
        .mapValues { (_, list) -> ArrayDeque(list.sortedByDescending { it.publishedAt }) }

    val result = ArrayList<Episode>(episodes.size)
    val pending = queues.keys.toMutableSet()
    var lastSource: String? = null

    while (pending.isNotEmpty()) {
        val candidates = pending.mapNotNull { id -> queues[id]?.firstOrNull()?.let { id to it } }
        if (candidates.isEmpty()) break

        val pick = candidates
            .filter { (id, _) -> id != lastSource }
            .maxByOrNull { (_, ep) -> ep.publishedAt }
            ?: candidates.maxByOrNull { (_, ep) -> ep.publishedAt }
            ?: break

        result += pick.second
        val queue = queues[pick.first]
        queue?.removeFirst()
        if (queue.isNullOrEmpty()) pending -= pick.first
        lastSource = pick.first
    }
    return result
}

// formatBytes 已收到 ui/theme/Parts.kt（1.2.2）—— 见「零件」那一节的说明
