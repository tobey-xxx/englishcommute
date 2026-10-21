package com.example.englishcommute

import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.englishcommute.ui.WordViewModel
import com.example.englishcommute.word.DueBadge
import com.example.englishcommute.diag.DiagLog
import com.example.englishcommute.settings.ThemeMode
import com.example.englishcommute.ui.AndroidMotionSetting
import com.example.englishcommute.ui.WordScreen
import com.example.englishcommute.ui.CelebrationHint
import com.example.englishcommute.ui.CelebrationViewModel
import com.example.englishcommute.ui.CheckInCelebrationDialog
import com.example.englishcommute.ui.CheckInScreen
import com.example.englishcommute.ui.DiagScreen
import com.example.englishcommute.ui.ForcedUpdateScreen
import com.example.englishcommute.ui.LibraryScreen
import com.example.englishcommute.ui.LocalArtworkStore
import com.example.englishcommute.ui.MiniPlayerBar
import com.example.englishcommute.ui.SettingsScreen
import com.example.englishcommute.ui.PlayerScreen
import com.example.englishcommute.ui.PlayerViewModel
import com.example.englishcommute.ui.SettingsViewModel
import com.example.englishcommute.ui.SHARED_MORPH
import com.example.englishcommute.ui.TabBadge
import com.example.englishcommute.ui.updateBadge
import com.example.englishcommute.ui.theme.Amber
import com.example.englishcommute.ui.theme.AppText
import com.example.englishcommute.ui.theme.Bg
import com.example.englishcommute.ui.theme.EnglishCommuteTheme
import com.example.englishcommute.ui.theme.Glyph
import com.example.englishcommute.ui.theme.GlyphIcon
import com.example.englishcommute.ui.theme.Hairline
import com.example.englishcommute.ui.theme.InkFaint
import com.example.englishcommute.ui.theme.Motion
import com.example.englishcommute.ui.theme.pressFeedback
import kotlin.math.abs
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onStart() {
        super.onStart()
        // ⚠️ 1.2.9：无障碍开关（移除动画 / 高对比）的读取**不在这里**，
        //    搬到了 setContent 里的 LifecycleEventEffect，原因见 AndroidMotionSetting 的类注释：
        //    那两个值现在写在 Compose 状态上，而「在 onStart 里改一个状态」
        //    并不会让已经在树上的界面重组 —— 1.2.8 因此有一个「生效时机随机」的缺陷。
        //    留在这一层的仍是不依赖组合周期的两件事。
        // 前后台切换本身要留痕（1.2.5）：用户说「回到 App 就怎样怎样」时，
        // 日志里得能对上「哪一刻算回到了前台」。配 kickAutoSubtitles 的
        // 「回到前台 · 有 N 条字幕待补」一起看，就能分清「没触发」和「触发了没干成」
        DiagLog.i("App", "界面可见（回到前台）")
        // ★ 2.1.9：**每次进入 App 都查一次有没有新素材，并把该预取的静默下下来。**
        //   放在补字幕之前：那一轮是「本地已有音频、只是缺字幕」，
        //   而这一轮会先把新音频拉下来 —— 顺序反了的话，新内容要等到下次进 App
        //   才有字幕，而用户此刻正看着列表。
        (application as EnglishCommuteApp).kickForegroundRefresh()
        // 「打开 App」本身就是一次补字幕触发。实现放在 Application 上 ——
        // 它既要读文件判据、又要起前台服务，两件事都不该由 Activity 承担
        (application as EnglishCommuteApp).kickAutoSubtitles()
        // ★ 2.2.1：顺手问一次有没有新版本（6 小时节流一次）。
        //   放在最后：它是三件事里最不重要的一件，而前两件决定「这次打开有没有东西听」。
        //   失败静默 —— 查更新失败不是用户此刻需要知道的事（见 kickUpdateCheck 的说明）。
        (application as EnglishCommuteApp).kickUpdateCheck()
    }

    override fun onStop() {
        // 注意：这不等于「App 退到后台」——播放页是覆盖层，不会让 Activity onStop；
        // 息屏、切到别的 App、回桌面都会走到这里
        DiagLog.i("App", "界面不可见（进入后台/息屏）")
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 系统栏的**初值**：跟随系统深浅色。
        //
        // 这里不再写死 `SystemBarStyle.dark()`。App 现在有日间模式，系统栏图标的
        // 明暗必须由当前主题决定，写死深色的话，日间模式下状态栏就是「浅图标压在
        // 米白背景上」——正是之前那个「跟背景融在一起看不见」的 bug 的镜像版。
        //
        // 真正的解析在下面 setContent 里（那里能读到持久化的 ThemeMode），
        // 这里只是为了**让第一帧不出错**：onCreate 到首次 composition 之间如果
        // 系统栏样式还是系统默认值，会闪一下。而 ThemeMode 的默认值恰好就是
        // 「跟随系统」，所以 auto() 与默认配置完全一致，首帧天然正确。
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )

        setContent {
            // 和设置页里拿到的是同一个实例（都在 Activity 的 ViewModelStore 作用域下）
            val settingsVm: SettingsViewModel = viewModel()
            val mode by settingsVm.themeMode.collectAsStateWithLifecycle()

            // 系统无障碍开关（移除动画 / 高对比文字）每回到前台重读一次。
            //
            // ⚠️ 必须写在组合里（1.2.9）。1.2.8 是写在 `onStart` 里的，而那时
            //    它只是往一个 `@Volatile` 变量里赋值 —— **不会触发任何重组**，
            //    于是「改了系统设置再切回来」是否生效取决于界面有没有因为别的原因
            //    重组，是随机的。现在两个值都是 Compose 状态，写进去就会重跑
            //    读到它的那部分组合（主题、覆盖层转场、按下反馈）。
            LifecycleEventEffect(Lifecycle.Event.ON_START) {
                AndroidMotionSetting.refresh(this@MainActivity)
            }

            // 先无条件读一次系统值，再按模式取值。
            //
            // 不在 when 的分支里直接调 isSystemInDarkTheme()：虽然 Compose 允许条件
            // 调用，但把它提到外面能让「三种模式各读什么」这件事一眼看清，
            // 也避免模式切换时的合成槽位跳动。
            val systemDark = isSystemInDarkTheme()
            val dark = when (mode) {
                ThemeMode.System -> systemDark
                ThemeMode.Light -> false
                ThemeMode.Dark -> true
            }

            // 系统栏图标颜色跟着主题走。
            //
            // 只在 dark 真正变化时重新设一次 —— enableEdgeToEdge 内部要动 WindowInsetsController
            // 和 windown flags，放在每次重组都跑的 SideEffect 里没必要。
            //
            // ⚠️ 判据是 `dark`（目标值）而不是主题插值的中间值：系统栏图标只有
            //    「深」「浅」两态，没有中间态可插值，所以它在过渡中途就该切过去，
            //    晚切反而会出现「浅底浅图标」的瞬间。
            LaunchedEffect(dark) {
                this@MainActivity.enableEdgeToEdge(
                    statusBarStyle = barStyle(dark),
                    navigationBarStyle = barStyle(dark),
                )
            }

            EnglishCommuteTheme(dark = dark, highContrast = Motion.highContrast) {
                Surface(modifier = Modifier.fillMaxSize(), color = Bg) {
                    // ★★ 2.2.1：**强制更新是盖在所有东西之上的最后一层。**
                    //
                    //   位置很关键：它必须在 `Surface` 里面、`Root()` **外面** ——
                    //   放进某个页签（比如设置页）里就等于没做，
                    //   因为用户完全可以靠打卡页或正在播放的音频继续用下去。
                    //
                    //   `forced` 的判据是「本机 versionCode < 服务器清单里的
                    //   minSupportedVersionCode」，而那个下限**持久化在本地** ——
                    //   所以拔网线绕不过去。见 Updater 的说明。
                    val app = LocalContext.current.applicationContext as EnglishCommuteApp
                    val forced by app.container.updater.forced.collectAsStateWithLifecycle()
                    // ★★ 4.6.6：**这里原来少了一层 provide。**
                    //
                    //    `LocalArtworkStore` 的默认值是 `null`，而全仓没有任何
                    //    地方 `provides` 它 —— 于是 `Artwork` 里 `store == null`，
                    //    封面加载**一次都没执行过**：没有请求、没有失败、没有日志，
                    //    界面上只剩一块字母占位。
                    //
                    //    这个缺陷活了很久而且**无声**（`staticCompositionLocalOf`
                    //    给了可空默认值 ⇒ 没接上就等于「静默降级」）。
                    //    ★ 纪律：可空的 CompositionLocal 默认值，等于给「忘了接线」
                    //      发了一张免罚单。
                    CompositionLocalProvider(
                        LocalArtworkStore provides app.container.artwork,
                    ) {
                        if (forced) {
                            ForcedUpdateScreen(app.container.updater)
                        } else {
                            Root()
                        }
                    }
                }
            }
        }
    }
}

/**
 * 根路由。
 *
 * 从下往上一共四层，顺序不能乱：
 *
 * ```
 * 打卡成功庆祝页   ← 一年里最该被看见的那几秒，盖在所有东西之上
 * 诊断页           ← 从设置里进去，用户此刻要的是导日志
 * 播放页           ← 全屏覆盖，连底栏一起盖住
 * 主界面           ← Scaffold + 三个可横向滑动的页签
 * ```
 *
 * ## 三个页签为什么用 HorizontalPager 而不是 `when (tab)`
 *
 * `when (tab)` 切页是**瞬移**的：点一下，内容就换了，中间没有任何过渡。
 * 而 Android 用户对「底部三个页签」这件事的肌肉记忆里本来就带着左右滑动
 * （微信、微博、各种资讯流都是这样）。手指划到一半、页面跟着走，
 * 这个反馈本身就是「我在这一层的两个页签之间」的空间感来源。
 *
 * 顺带还解决了一件事：`when` 切换会把离开的页整棵扔掉，
 * 列表滚动位置、设置页滚动位置全靠 `rememberSaveable` 各自救回来；
 * 而 pager 用 `SaveableStateProvider` 统一托管，不用每个页面自己操心。
 *
 * ## 播放页为什么仍然是覆盖层，而不是第四个页签
 *
 * 播放页承担的是「沉浸听」这个场景，底栏在那儿只是干扰；而底下的主界面必须
 * **保持存活**（不重建），否则退出再进入时列表会重新滚回顶部。
 * 用覆盖层正好同时满足两点 —— 而且它绕过了 pager：「从播放页返回时留在原页签」
 * 这件事因此是天然的，不需要记「进去之前在哪一页」。
 *
 * ## episodeId 为什么用 rememberSaveable 存字符串
 *
 * 字符串天然可存进 Bundle，横竖屏切换回来能接着播；而且条目数据始终以仓库为准，
 * 不会拿着一份过期快照。
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun Root() {
    var playingId by rememberSaveable { mutableStateOf("") }
    var showDiag by rememberSaveable { mutableStateOf(false) }

    /**
     * 「从播放页退回素材库」的次数。
     *
     * 素材库订阅它：每变一次就把正在播的那条滚进视野。用自增计数而不是布尔量 ——
     * 连着进出两次都要能触发，而 `true` 第二次就不再是变化了。
     */
    var focusPlaying by rememberSaveable { mutableIntStateOf(0) }

    /**
     * 「打开播放页」的次数（1.2.10 修一个真机 bug）。
     *
     * ## 为什么需要它
     *
     * 播放页的下拉关闭会留下一个位移（`offsetY`），而那个值记在 PlayerScreen
     * 内部的 `remember` 里。正常情况下面容被销毁时它自然归零 ——
     * 但**实例可能被保留**：`AnimatedVisibility` 的退出是 220ms，
     * 用户在这段时间内又点开时，退出会被取消、这一页不销毁，
     * 于是位移还停在「已经被拽走」的位置，页面停在屏幕外面。
     * 用户看到的是「点了没反应」，而日志里只有「迷你条点开」、没有「进入播放页」。
     *
     * 所以给一个「这是第几次打开」的信号，让播放页每次打开都复位位移。
     * 用自增计数而不是布尔量：连着打开两次都要能触发 ——
     * 与 [focusPlaying]、`EpisodeRow.menuNonce` 是同一条纪律。
     */
    var openNonce by rememberSaveable { mutableIntStateOf(0) }

    /**
     * 最后一次打开的条目 id。
     *
     * 退出播放页时 `playingId` 立刻变成空串，但覆盖层还要往下沉 220 ms ——
     * 这段时间里 `PlayerScreen` 仍然在组合树上。直接把空串传进去，
     * 它会先变成一片空白再滑走，比不做动画还难看。
     *
     * 所以留一份「上一次是谁」，只在**打开**时更新，关闭时不动。
     */
    var lastPlayingId by rememberSaveable { mutableStateOf("") }
    if (playingId.isNotEmpty() && playingId != lastPlayingId) {
        lastPlayingId = playingId
    }

    // Root() 是顶层 Composable，拿不到 Activity 的成员方法，
    // 所以从 LocalContext 取 Application 再调 —— boostSubtitles 本来
    // 就该属于应用级（它要起服务），放在 Activity 上只是历史原因
    val context = LocalContext.current
    val app = context.applicationContext as EnglishCommuteApp

    // ⚠️ 这里和 PlayerScreen 里拿的是**同一个** PlayerViewModel 实例。
    //
    // 两者都在 Activity 的 ViewModelStore 作用域下（本 App 不用 Navigation 组件，
    // 没有更细的 backstack 作用域），所以 viewModel() 返回同一对象。
    // 这不是巧合而是设计：迷你条要读的播放状态、要触发的播放/暂停，
    // 全都来自播放页正在用的那一个 MediaController，没有第二份状态需要同步。
    val playerVm: PlayerViewModel = viewModel()

    // 底栏「生词」角标（3.0.5）要在**任何页**都正确，所以生词本的读盘
    // 必须在 Root 这一层就先拉起来 —— 它本来只在生词页被创建时才跑，
    // 而首页是素材库，pager 只预排相邻页，刚打开 App 时生词页还没建出来。
    // 结果是角标在「刚打开 App」那一刻永远是 0，而那正是它唯一有用的时刻。
    // 见 WordViewModel.loadStore 的说明。
    val wordVm: WordViewModel = viewModel()
    LaunchedEffect(Unit) { wordVm.loadStore() }

    // ★ 跨天重算（3.0.5）。到期判据是「**今天 24:00 前**到期」，
    //   而 App 完全可能在后台待一整夜 —— 那时 `store.cards` 一个字节都没变，
    //   没有任何东西会重算。不推这一下，第二天早上看到的是昨天的数字
    //   （该复习的显示成 0 个）。和上面那个 ON_START 是同一种时机：
    //   「用户回来了」。
    LifecycleEventEffect(Lifecycle.Event.ON_START) { wordVm.refreshToday() }

    // ⚠️ **不要在这里读 `playerVm.ui`**（1.2.0 的教训）。
    //
    // 那个 StateFlow 每 250 ms 发一次（播放位置在走）。在 Root() 顶层读它，
    // 就等于让**整棵界面树**每秒重组 4 次 —— 而 pager 的
    // `beyondViewportPageCount = 1` 让三个页签全部保持 composed，
    // 于是用户明明在看打卡页，素材库的列表也在 4 次/秒地重跑。
    //
    // 这里只取「正在播哪一条」，它一集才变一次。完整的播放状态下推到
    // 真正需要它的 bottomBar 里面去读。
    val playingEpisodeId by playerVm.playingEpisodeId.collectAsStateWithLifecycle()

    // 4.6.3：列表播放键要显示「播放 / 暂停」—— 只取「在不在播」，
    // 它换状态才发（VM 里做了 distinctUntilChanged），不会让整页跟着 250ms 重组
    val isPlayingNow by playerVm.isPlayingNow.collectAsStateWithLifecycle()

    // 庆祝页的状态。单独一个 ViewModel 而不是复用 CheckInViewModel ——
    // 后者一被创建就会解码当天海报（6 MB 位图常驻），而庆祝页只在
    // 达标那一秒需要它，见 CelebrationViewModel 的说明
    val celebrationVm: CelebrationViewModel = viewModel()
    val celebrating by celebrationVm.celebrate.collectAsStateWithLifecycle()
    val celebrationPoster by celebrationVm.poster.collectAsStateWithLifecycle()
    val celebrationBusy by celebrationVm.busy.collectAsStateWithLifecycle()
    val celebrationHint by celebrationVm.hint.collectAsStateWithLifecycle()

    val pagerState = rememberPagerState(initialPage = LIBRARY_PAGE, pageCount = { TABS.size })
    val scope = rememberCoroutineScope()

    // ⚠️ 这一层 lambda 只为一件事：让 SHARED_MORPH 关掉时**连
    //    SharedTransitionLayout 都不包**。那个 Scope 内部是 LookaheadScope，
    //    会给整棵子树多挂一次测量 pass；「包着但不加 modifier」等于没有回退。
    //    详见 ui/Morph.kt。
    val stack: @Composable (SharedTransitionScope?) -> Unit = { morphScope ->
        Box(Modifier.fillMaxSize()) {
            Scaffold(
                containerColor = Bg,
                bottomBar = {
                    Column {
                        // 完整播放状态**只在这里读**。迷你条要显示进度条和时间，
                        // 它是全应用唯一真正需要 250 ms 粒度的地方。
                        // 读在 bottomBar 的 lambda 内部，重组范围就被限制在这一条里
                        val playerUi by playerVm.ui.collectAsStateWithLifecycle()
                        // ★ 1.2.8：整个底部是**一块材质**，只有一条上边缘。
                        //
                        // 改之前：迷你条与底栏是两块各自带 1px 线的条带
                        // （迷你条用 bgRaised、底栏用 bg），叠起来在深色下是
                        // 「两块深浅不一的灰 + 两条几乎看不见的线」—— 而它们
                        // 本来就是同一个东西的两个部分。
                        //
                        // ⚠️ 这里**不是**真正的半透明模糊：Compose 没有
                        //    `backdrop-filter` 的等价物（`Modifier.blur` 模糊的是
                        //    自己而不是身后），所以取的是「一块底 + 一条上边缘」。
                        //    设计稿里画的是半透明材质，这一处是有意降级的，
                        //    见 1.2.8 清单里「材质」那一节。
                        Box(Modifier.fillMaxWidth().height(1.dp).background(Hairline))

                        // ★ 4.6.1：迷你条**淡入淡出**。
                        //
                        //   原来是「凭空冒出来 / 突然消失」—— 一条 60dp 的条
                        //   突然顶上来，下面的列表跟着跳一下。现在高度和透明度
                        //   一起进（expand + fade），列表不会瞬移。
                        //
                        //   ⚠️ 显不显示看 `playerUi.episodeId`（载入了哪一条），
                        //   **不是** playingId（那是「播放页开着吗」）。
                        AnimatedVisibility(
                            visible = playerUi.episodeId.isNotEmpty(),
                            enter = fadeIn(Motion.quick()) + expandVertically(),
                            exit = fadeOut(Motion.quick()) + shrinkVertically(),
                        ) {
                        MiniPlayerBar(
                            ui = playerUi,
                            // 封面的形变源。null = 形变关掉了（见 ui/Morph.kt）
                            morphScope = morphScope,
                            // ⚠️ 两端可见性必须**互斥**（1.2.10 修）：
                            //    播放页打开时这一端就「不在场」，否则同一个键会同时存在
                            //    两个在场的副本 —— 共享元素的状态机会在两者之间来回切，
                            //    表现是形变时明显卡顿（用户在真机上确认过这一条）。
                            morphVisible = playingId.isEmpty(),
                            onOpen = {
                                playerUi.episodeId.takeIf { it.isNotEmpty() }?.let {
                                    DiagLog.act("迷你条点开 → 播放页")
                                    playingId = it
                                    // 每次打开都自增：播放页靠它复位下拉关闭留下的位移
                                    openNonce++
                                }
                            },
                            onTogglePlay = playerVm::togglePlay,
                            onNext = {
                                DiagLog.act("迷你条点下一集")
                                playerVm.nextEpisode()
                            },
                        )
                        }
                        // 角标数字也**在这一层读** —— 和上面 playerUi 同一个理由：
                        // 把重组的范围限制在底栏这一条里，别让整个界面树跟着动
                        val wordDue by wordVm.dueCount.collectAsStateWithLifecycle()
                        // 更新状态同理。它一集才变一次（查更新 / 下载进度会变，
                        // 但那些都发生在设置页里，那时底栏本来就该跟着亮）
                        val updateState by app.container.updater.state.collectAsStateWithLifecycle()
                        BottomTabs(
                            pagerState = pagerState,
                            // 与 TABS 一一对应。**由 TABS.size 生成**而不是手写四个 ——
                            // 少写一个的话，下标会整段错位（角标贴到别的页签上，
                            // 而界面上不报任何错）
                            badges = List(TABS.size) { i ->
                                when (i) {
                                    // 生词：一个**数字**，回答「今天欠几个」
                                    WORD_PAGE -> DueBadge.text(wordDue)?.let(TabBadge::Count)
                                    // 设置：一个**圆点**，只回答「有没有」——
                                    // 「有新版本」这件事没有数量，写「1」会让人去找那「另外的」
                                    SETTINGS_PAGE -> updateBadge(updateState)
                                    else -> null
                                }
                            },
                            onSelect = { index ->
                                DiagLog.act("切页签 → ${TABS.getOrNull(index)?.first ?: index}")
                                // 用动画而不是 scrollToPage：点页签是一次「导航」，
                                // 它和手指划动的落点应该看起来是同一件事
                                scope.launch { pagerState.animateScrollToPage(index) }
                            },
                        )
                    }
                },
            ) { innerPadding ->
                // 不用 topBar：Material3 的 Scaffold 在无 topBar 时会自动把
                // contentWindowInsets 的顶部（状态栏高度）算进 innerPadding，
                // 内容自然避开状态栏，不需要每屏再手动 statusBarsPadding()
                Box(Modifier.padding(innerPadding)) {
                    HorizontalPager(
                        state = pagerState,
                        // 预排当前页**两侧**的页。三个页面的 ViewModel 都挂在 Activity 上、
                        // 不会被 pager 销毁，所以这里只是提前把视图树建好，
                        // 为的是第一次划动时不出现「手指已经划了一半、目标页才开始创建」的掉帧
                        beyondViewportPageCount = 1,
                        modifier = Modifier.fillMaxSize(),
                    ) { page ->
                        when (page) {
                            // playingId 传的是**正在播的那一条**（来自 PlayerViewModel），
                            // 不是「正在看的那一条」。列表靠它把当前曲目标成琥珀色 ——
                            // 没有这个标记的话，从播放页返回列表后完全看不出刚才在听哪条，
                            // 而「回到刚才那条」是通勤场景里最高频的动作之一
                            0 -> LibraryScreen(
                                isPlaying = isPlayingNow,
                                onPlay = { id ->
                                    DiagLog.act("列表点开一条 → 播放页")
                                    playingId = id
                                    // 与迷你条那条路径一样：每次打开都自增
                                    openNonce++
                                    // 点开 = 此刻就要听。字幕没齐的话把它插到队首，
                                    // 而不是让它排在队列后面等一整轮
                                    app.boostSubtitles(id)
                                },
                                // 4.6.0：就地播放 —— 只开播、**不** openNonce++（不跳播放页）
                                onPlayHere = { id ->
                                    // ★ 4.6.2：**再点一次要暂停**。
                                    //   原来这里无条件 `start(id)` —— 于是「暂停」
                                    //   这个动作根本没入口（用户一眼发现）。
                                    //   规则很简单：这一条就是当前这条 ⇒ 切换播放/暂停；
                                    //   不是它 ⇒ 起播它（不跳页）。
                                    if (id == playerVm.playingEpisodeId.value) {
                                        DiagLog.act("列表播放键 → 暂停/继续 · $id")
                                        playerVm.togglePlay()
                                    } else {
                                    // ★★ 4.6.1 修：不要设 `playingId`。
                                    //
                                    //    播放页的可见性是
                                    //    `AnimatedVisibility(visible = playingId.isNotEmpty())`，
                                    //    所以「设 playingId」= 「打开播放页」。
                                    //    上一版正是这么写的 —— 于是「就地播放」
                                    //    变成了「进播放页」，用户一眼就发现了。
                                    //
                                    //    就地播放要的只有一件事：**让播放器起播**。
                                    //    `playerVm.start(id)` 就是播放页里那句
                                    //    `LaunchedEffect(episodeId) { vm.start(episodeId) }`。
                                    //    起播之后迷你条自己会出现（它看的是 playerUi）。
                                    DiagLog.act("列表就地播放（不跳页）· $id")
                                    playerVm.start(id)
                                    app.boostSubtitles(id)
                                    }
                                },
                                // 点了一条**还没有音频**的内容（2.0.3）。
                                // 以前这种点击在 LibraryScreen 里被静默丢掉，
                                // 用户看到的是「这条音频点不动/播放不了」。
                                // 现在：登记「这一条必须下」+ 起一轮更新，
                                // 那一行随即变成「下载中 N%」。
                                onNeedDownload = { id -> app.downloadNow(id) },
                                // 用派生流而不是 playerUi.episodeId：后者会把
                                // 250 ms 的播放位置更新一路带进列表
                                playingId = playingEpisodeId,
                                focusNonce = focusPlaying,
                            )
                            // 打卡页的 ViewModel 挂在 Activity 作用域，切走再切回来
                            // 还是同一个实例 —— 这是「连续天数」这类数字不闪的必要条件
                            1 -> CheckInScreen()
                            // 生词页的 ViewModel 同样挂在 Activity 作用域，
                            // 切走再切回来是同一个实例 —— 判据与打卡页一致：
                            // 「今天要复习的数字不能闪」
                            2 -> WordScreen()
                            else -> SettingsScreen(onOpenDiag = { showDiag = true })
                        }
                    }
                }
            }

            // ## 1.2.0：三个覆盖层都有了转场
            //
            // 之前全是 `if (...) Screen(...)` —— 下一帧整屏被换掉，没有任何过渡。
            // 点一条内容，播放页「啪」地出现；返回，「啪」地消失。每一帧都正确，
            // 但两帧之间什么都没发生，用户无法判断刚才是「换了一页」还是
            // 「这一页的内容被替换了」。
            //
            // ⚠️ **内容 id 要单独记一份**（[lastPlayingId]）。退出动画期间
            //    `playingId` 已经是空串了，直接拿它当参数会让播放页在下沉的
            //    那 220 ms 里变成一片空白 —— 那比没有动画更糟。

            AnimatedVisibility(
                visible = playingId.isNotEmpty(),
                // 形变开着时**不做整页位移**（1.2.10 修掉一处真机卡顿）。
                //
                // 那条位移（`slideInVertically`）走的是**布局层**，而共享元素又在做
                // 第二套位移（封面 bounds 的补间）。两者叠在同一屏上：
                // ① 每帧要重新布局整棵子树（这里面是 8–12 行字幕）；
                // ② 封面自己的轨迹叠在整页的轨迹上，看起来是「斜着飞」。
                // 空间连续性交给共享元素本身就够了（§7 要的正是这个）。
                // 关掉 SHARED_MORPH 时自动退回带位移的版本。
                enter = if (SHARED_MORPH) Motion.overlayEnterMorph else Motion.overlayEnter,
                exit = if (SHARED_MORPH) Motion.overlayExitMorph else Motion.overlayExit,
            ) {
                PlayerScreen(
                    episodeId = lastPlayingId,
                    // 每次打开都复位下拉关闭留下的位移（见 openNonce 的说明）
                    openNonce = openNonce,
                    // 形变的两端：`morphScope` 是共享元素所在的 Scope，
                    // `animatedScope`（← 就是这个 AnimatedVisibility）负责告诉
                    // 系统「这一端正在进还是正在出」。`this@AnimatedVisibility`
                    // 的标签能指向最近的那个 AnimatedVisibility —— 本文件里有三个，
                    // 这里指的是包住播放页的这个
                    morphScope = morphScope,
                    animatedScope = this@AnimatedVisibility,
                    onBack = {
                        DiagLog.act("退出播放页（音频继续在后台播）")
                        playingId = ""
                        // 只有回到素材库时才定位。从打卡页/设置页点迷你条进来的，
                        // 退出去当然要回到原来那一页 —— pager 压根没动过，
                        // 所以这里只需要决定「要不要滚列表」
                        if (pagerState.currentPage == LIBRARY_PAGE) focusPlaying++
                    },
                )
            }

            // 诊断页盖在播放页之上。它是从设置里进去的，用户此刻的目的是导日志 ——
            // 如果正播着内容，播放页也不该挡住它（后台播放本身不受影响）
            AnimatedVisibility(
                visible = showDiag,
                enter = Motion.overlayEnter,
                exit = Motion.overlayExit,
            ) {
                DiagScreen(onBack = { showDiag = false })
            }

            // 打卡成功庆祝页盖在最上层。它甚至比播放页还优先 ——
            // 跨过目标线那一秒用户很可能正看着播放页，而这正是当天最该被看见的一屏
            AnimatedVisibility(
                visible = celebrating,
                enter = Motion.overlayEnter,
                exit = Motion.overlayExit,
            ) {
                CheckInCelebrationDialog(
                    poster = celebrationPoster,
                    busy = celebrationBusy,
                    streak = celebrationVm.currentStreak(),
                    seconds = celebrationVm.todaySeconds(),
                    onShare = { celebrationVm.share(context) },
                    onDismiss = celebrationVm::dismiss,
                )
            }
            // 提示条不参与覆盖层转场：它自己有 2.6 秒的生命周期，
            // 套进庆祝页的 AnimatedVisibility 会让它跟着整屏一起下沉
            if (celebrating) {
                celebrationHint?.let { msg ->
                    CelebrationHint(message = msg, onDismiss = celebrationVm::clearHint)
                }
            }
        }
    }
    if (SHARED_MORPH) {
        SharedTransitionLayout { stack(this) }
    } else {
        stack(null)
    }
}

/**
 * 三个页签的显示名与图标。**只有这一份定义** —— pager 的页数、底栏的项数
 * 都从它派生。各写一份的话，加一个页签就会出现「能划过去但底栏没有它」
 * （或反过来）这种对不上的情况。
 */
private val TABS = listOf(
    "素材库" to Glyph.Headphones,
    "打卡" to Glyph.CalendarCheck,
    // 「生词」放在「设置」之前：设置是工具类入口，习惯上永远在最后；
    // 而生词和素材库/打卡是一类（都是内容），挨着它们更顺。
    // ⚠️ 底栏图标必须与「设置」一眼分得清 —— 见 Glyph.Book 的注释。
    "生词" to Glyph.Book,
    "设置" to Glyph.Sliders,
)

/** 素材库在 pager 里的下标。它出现在两处判断里（是否定位、初始页），必须是具名的 */
private const val LIBRARY_PAGE = 0

/** 「生词」页签的显示名。角标要找它落脚的那个页签 —— 只用在这里 */
private const val WORD_TAB_LABEL = "生词"

/** 「设置」页签的显示名。同上 —— 3.2.0 的更新圆点要找它 */
private const val SETTINGS_TAB_LABEL = "设置"

/** 底栏图标尺寸。角标要贴着它的右上角，所以这一处不能各写各的 */
private const val ICON_DP = 19f

/**
 * 圆点角标的直径。
 *
 * 它比数字角标小得多（数字最小也有约 15dp 宽），所以**不会**撑宽父容器、
 * 也不会把图标挤偏。仍然走同一个 [floatingOnIconCorner]：
 * 「贴哪儿」这件事只该有一个定义 —— 数字能贴偏、圆点也能，
 * 而分开写之后改一处另一处不动是看不出来的。
 */
private val BADGE_DOT = 8.dp

/**
 * 生词在 pager 里的下标。**从 [TABS] 派生，不写死 2** ——
 * 写死的话，谁调整了页签顺序，角标就会静默地贴到别的页签上
 * （「贴错页签」在界面上不报任何错，只有人眼能发现）。
 *
 * 找不到就直接炸在启动时：这是编码期的错误，不该拖到用户手上变成一个没解释的角标。
 */
private val WORD_PAGE = TABS.indexOfFirst { it.first == WORD_TAB_LABEL }.also {
    check(it >= 0) { "底栏里找不到「$WORD_TAB_LABEL」页签 —— 角标没有落脚处" }
}

/** 设置在 pager 里的下标。理由与 [WORD_PAGE] 逐字相同 */
private val SETTINGS_PAGE = TABS.indexOfFirst { it.first == SETTINGS_TAB_LABEL }.also {
    check(it >= 0) { "底栏里找不到「$SETTINGS_TAB_LABEL」页签 —— 圆点没有落脚处" }
}

/**
 * 底栏。
 *
 * 不用 Material3 的 `NavigationBar`：它自带 80dp 的高度、胶囊形的选中指示器
 * 和一套 Material 的图标规格。那三样凑在一起，正是这套视觉要摆脱的东西。
 * 自绘的版本只有「一条细线 + 三组图标文字」，高度也更省。
 *
 * [navigationBarsPadding] 必须加：内容是全屏铺开的（edge-to-edge），
 * 不加这一层，手势条会压在这两个页签上。
 *
 * ## 选中态为什么要插值，而不是「第几页就点亮第几个」
 *
 * 底栏和 pager 是同一件事的两个视图。只按 `currentPage` 上色的话，
 * 手指划到一半时底栏完全不动，松手才「啪」地跳一下 —— 看起来像两个
 * 互不相干的东西。改成按 `currentPage + currentPageOffsetFraction`
 * 插值之后，划到一半就是两个页签各亮一半，底部这条和上面那一页
 * 是**同时**在动的，跟手感来自这里。
 *
 * 代价是拖动期间底栏每帧重组一次。可以接受：这一层只有一个 Column、
 * 一个 Box 和三组「图标 + 文字」，而它换来的正是需求里那句「跟手」。
 */
@Composable
private fun BottomTabs(
    pagerState: PagerState,
    onSelect: (Int) -> Unit,
    /** 每个页签右上角的角标，`null` = 不显示。**下标与 [TABS] 一一对应** */
    badges: List<TabBadge?>,
) {
    // 当前停在的位置，含拖动的小数偏移。取值范围大致是 [currentPage-0.5, currentPage+0.5]
    val position = pagerState.currentPage + pagerState.currentPageOffsetFraction

    Column(
        Modifier
            .fillMaxWidth()
            .background(Bg)
            .navigationBarsPadding()
    ) {
        // ⚠️ 这条线**已经移到外层**（bottomBar 的顶部）——1.2.8 起整个底部
        //    是一块材质，只允许有一条上边缘。留在这里的话，它就会变成
        //    「迷你条与底栏之间的分隔线」，而那正是要消掉的两条线之一
        Row(Modifier.fillMaxWidth().padding(vertical = 9.dp)) {
            TABS.forEachIndexed { index, (label, glyph) ->
                // 离得越近越亮。abs 而不是平方：平方会让「一半」看起来只有四分之一亮，
                // 中间态太暗就失去了「两边都在动」的观感
                val emphasis = (1f - abs(position - index)).coerceIn(0f, 1f)
                TabItem(
                    label = label,
                    glyph = glyph,
                    emphasis = emphasis,
                    // getOrNull 而不是 [index]：badges 比 TABS 短时应该
                    // 「那个页签没有角标」，不是抛异常
                    badge = badges.getOrNull(index),
                    onClick = { onSelect(index) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * @param emphasis 0 = 完全未选中，1 = 完全选中。中间值出现在拖动过程中
 */
@Composable
private fun TabItem(
    label: String,
    glyph: Glyph,
    emphasis: Float,
    /** 右上角角标。`null` = 不显示 */
    badge: TabBadge? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 未选中 → 选中的颜色插值。两个端点都是主题色，所以明暗两套主题下
    // 中间态也自然落在同一套色阶里，不需要另配
    val tint = lerp(InkFaint, Amber, emphasis)

    Column(
        modifier = modifier
            .pressFeedback(onClick = onClick)
            .padding(vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box {
            GlyphIcon(
                glyph = glyph,
                size = ICON_DP.dp,
                tint = tint,
                strokeWidth = 1.5f,
            )
            // 两种角标都在这一层选。`when` 是穷尽的（TabBadge 是 sealed），
            // 以后再加一种形态，编译器会把这里标出来
            when (badge) {
                null -> Unit
                is TabBadge.Count -> BadgePill(badge.text)
                is TabBadge.Dot -> BadgeDot()
            }
        }
        Spacer(Modifier.height(5.dp))
        Text(
            text = label,
            style = AppText.label,
            color = tint,
        )
    }
}

/**
 * 数字角标（「3」「99+」）。
 *
 * ⚠️ 必须走 [floatingOnIconCorner]，不能写成 `Box(align = TopEnd)` ——
 *    角标比图标宽，直接放进 Box 会**把图标挤偏**。理由见那个函数的注释。
 */
@Composable
private fun BadgePill(text: String) {
    Box(
        Modifier
            .floatingOnIconCorner(ICON_DP.dp)
            .clip(CircleShape)
            // 用满强度的 Amber，**不跟着 emphasis 插值** ——
            // 它是「有活要干」这个事实，不是选中态的一部分。
            // 跟着变淡的话，未选中时几乎看不见，
            // 而「未选中」恰恰是它最需要被看见的时候。
            .background(Amber)
            .padding(horizontal = 4.5.dp, vertical = 1.5.dp)
    ) {
        Text(
            text = text,
            style = AppText.badge,
            // Bg 是纸张色：浅色主题下 Amber 是深金、深色主题下是亮金，
            // 两种主题下「金底 + 纸色字」对比都够
            color = Bg,
        )
    }
}

/**
 * 圆点角标：一个实心的琥珀小圆。
 *
 * 它和 [BadgePill] 共用同一个落点修饰符（理由见 [BADGE_DOT]），
 * 但**没有文字** —— 所以它不需要 `AppText.badge` 那一档样式，
 * 也就不参与「那一档只许底栏数字角标用」的约束（见 verify-word-badge.py）。
 */
@Composable
private fun BadgeDot() {
    Box(
        Modifier
            .floatingOnIconCorner(ICON_DP.dp)
            .size(BADGE_DOT)
            .clip(CircleShape)
            .background(Amber)
    )
}

/**
 * 把角标浮在图标右上角，**且不让它参与父容器的尺寸计算**。
 *
 * ## 为什么不能写成 `Box { GlyphIcon(...); Text(badge) }`
 *
 * Compose 的 `Box` 尺寸 = 所有子元素里最大的那个。角标一旦超过图标宽度，
 * 这个 Box 就跟着变宽 —— 而外层 Column 是**居中对齐**的，图标会往左挪。
 * 按 [AppText.badge]（10.5sp）的实际字宽算，图标是 19dp：
 *
 *     「3」   角标约 15dp < 19dp  → 盒子不变宽，图标**不动**
 *     「12」  角标约 21dp         → 图标偏左约 **1dp**
 *     「99+」 角标约 27dp         → 图标偏左约 **4dp**
 *
 * ⚠️ 最值得记的是**第一行**：一位数的时候这个 bug 根本不出现。
 *    也就是说它一定躲得过「刚加了一两个词」的自测，只在数量长起来之后才露出来 ——
 *    而那时也没人会把这个 4dp 的歪和角标联系起来。
 *    （同理，数字从 9 变 10 时宽度跳一档，图标会跟着抖一下。）
 *
 * 要保的从来不是「盒子够不够宽」，而是「**图标的位置不许动**」——
 * 两者在角标比图标窄的时候结果一样，一宽就分道扬镳。
 *
 * ⚠️ 这里值得记的是**判据错在哪**：一个自然的写法是「保证 Box 够宽」，
 *    但真正要保的是「**图标的位置不许动**」。两者在角标比图标窄时结果一样，
 *    一宽就分道扬镳。所以这一层报 **0×0**（`layout(0, 0)`），
 *    父容器只看得到图标；角标自己的位置改用 `graphicsLayer` 的平移量来定 ——
 *    **平移不参与布局**，它只影响绘制。
 *
 * @param iconSize 图标边长。角标以自身中心对准「图标的右上角」那一点
 */
private fun Modifier.floatingOnIconCorner(iconSize: Dp): Modifier =
    this.layout { measurable, _ ->
        // 不受父容器约束地量一次：角标要按自己的内容撑开，
        // 否则会被 19dp 的上限挤到换行或省略号
        val placeable = measurable.measure(Constraints())
        // 报 0×0 —— 父 Box 的尺寸因此只由图标决定，图标一个像素都不会动
        layout(0, 0) { placeable.place(0, 0) }
    }.graphicsLayer {
        // ⚠️ `size` 在这里是**角标自己**的尺寸（不是 0，也不是图标尺寸）——
        //    作用域给的是这一层 layer 的大小，而 layer 包着的是角标。
        //    平移量 = 「图标右边缘」− 「角标自身宽度的一半」（水平）
        //            「−角标自身高度的一半」（垂直）
        //    于是角标的**中心**正好落在那一点上。
        translationX = iconSize.toPx() - size.width / 2f
        translationY = -size.height / 2f
    }

/**
 * 系统栏样式：底色是浅的就配深色图标，反之配浅色图标。
 *
 * scrim 一律透明 —— 界面自己铺满全屏（edge-to-edge），不需要系统再叠一层遮罩。
 */
private fun barStyle(dark: Boolean): SystemBarStyle = if (dark) {
    // 「背景是深的，图标用浅色」
    SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
} else {
    // 「背景是浅的，图标用深色」。第二个参数是 API < 29 的降级方案：
    // 那个版本改不了导航栏图标颜色，只能给一条半透明黑底保证可见 ——
    // 这里同样传透明，因为这些设备上米白底配默认深色图标本来就能看清。
    SystemBarStyle.light(
        android.graphics.Color.TRANSPARENT,
        android.graphics.Color.TRANSPARENT,
    )
}
