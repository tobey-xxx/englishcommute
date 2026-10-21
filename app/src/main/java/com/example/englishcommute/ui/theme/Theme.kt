package com.example.englishcommute.ui.theme

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * 视觉系统。
 *
 * ## 这一版为什么推倒重来
 *
 * 上一版是「Material 默认配方 + 换配色」：所有内容都包在 16dp 圆角卡片里、
 * 卡片里再套卡片、图标用 emoji、字号只有 12/14/16 三档、配色是纯正的
 * 蓝黑（#0B1220）+ 品牌蓝（#3B82F6）。单独看每一步都没错，叠起来就是
 * 一眼可辨的「AI 生成界面」——**因为它没有任何设计决策，只有默认值**。
 *
 * 这一版按「编辑部排版」的路子重做，核心是四条：
 *
 *  1. ~~**取消卡片**。卡片是「不知道该怎么分组」时的兜底答案。~~
 *     ⚠️ **1.2.8 用户重新选定了方向：卡片回来了**（圆角 + 描边 + 仅一层浅阴影），
 *     理由是「靠 1px 线分组」在深色下彻底失效 —— hairline #1D1E21 压在
 *     bg #0B0C0E 上对比度只有约 1.1:1，整列表糊成一片。
 *     改回来的是**视觉**，不是当年那条结论：分组仍然要有理由，
 *     只是这次用「底的亮度差 + 描边」而不是「一条线」。
 *  2. **冷暖分离**。底色从蓝黑换成中性近黑，文字从冷白换成暖白，
 *     强调色从品牌蓝换成暖琥珀。三个一起换，才不会再滑回 Material 味。
 *  3. **字号拉开层级**。31sp 的衬线大标题和 10.5sp 的字距标签同时存在，
 *     中间留出真实的层次，而不是每一档都差 2sp。
 *  4. **数字全部等宽**。时间、时长、百分比、集数用 Monospace 对齐 ——
 *     播放位置每 250ms 跳动一次，比例字体会让整行左右抖动。
 *
 * ## 明暗两套是「两套设计」，不是一次取反
 *
 * 把暗色那套原样搬到浅底上会直接崩掉可读性 —— 实测：
 *
 * | token | 暗色在 #0B0C0E 上 | 原值搬到 #FBFAF7 上 |
 * |---|---|---|
 * | Ink  #F4F2EE | 17.50:1 | **1.07:1**（等于隐身） |
 * | Amber #D9A05B | 8.51:1 | **2.20:1**（远低于 4.5 门槛） |
 * | Olive #8FA86B | 7.44:1 | **2.52:1** |
 *
 * 原因是人眼对亮部的分辨被强烈压缩：近黑底上「再加一点亮」非常显眼，
 * 而米白底上「再多白一点」根本看不出来。所以两套必须各自独立配平，
 * 浅底那套的每一个前景色都单独验过对比度（见 [LightPalette] 注释）。
 *
 * ## 调用方为什么一行都不用改
 *
 * 原来 `Ink` / `Amber` 这些是顶层 `val Color` 常量，全局引用了 230 处。
 * 现在它们仍是**同名的顶层属性**，只是 getter 变成 `@Composable` 的、
 * 从 [LocalAppPalette] 里取值。语法上 `Ink` 还是 `Ink`，但值会随主题走：
 *
 * ```kotlin
 * Text(color = Ink)        // 暗色下是暖白，日间下是深墨 —— 调用点无感
 * ```
 *
 * 代价是这些属性**只能在 @Composable 上下文里读**。这其实是好事：
 * 编译器会把任何想在非界面代码里偷偷取色的地方都拦下来。
 */

// ---------------------------------------------------------------------------
// 色板
// ---------------------------------------------------------------------------

/**
 * 一套完整色板。明暗两套共用同一个骨架，字段一一对应。
 *
 * 用 [Immutable] 标注是为了让 Compose 能跳过不必要的重组；
 * 整个对象在两套主题间只会存在两个实例，切换时是一次纯粹的状态替换。
 */
@Immutable
data class AppPalette(
    /** 页面底色 */
    val bg: Color,
    /** 浮起一层的底色（次要按钮、档位未选中态） */
    val bgRaised: Color,
    /** 分割线。只用来提示分组，不用来画格子 */
    val hairline: Color,
    val hairlineStrong: Color,
    /** 正文墨色 */
    val ink: Color,
    val inkMid: Color,
    val inkDim: Color,
    /** 最弱的层次：未选中的页签图标、禁用态 */
    val inkFaint: Color,
    /** 强调色 */
    val amber: Color,
    val amberDim: Color,
    val amberFaint: Color,
    /** 字幕中文译文的颜色 */
    val gloss: Color,
    val crimson: Color,
    val olive: Color,
    /**
     * 卡片底色（1.2.8）。
     *
     * 浅色下**比页面底更亮**（纯白 / 米白），深色下比页面底更亮一档 ——
     * 两个方向都是「浮起来」，而不是「凹下去」。
     * 这里刻意不复用 [bgRaised]：那个值是给「控制区、未选中的档位」用的，
     * 它比页面底**暗**（浅色下 #F2EEE5 压在 #FBFAF7 上），拿它当卡片会凹进去。
     */
    val card: Color,
    /** 卡片描边。半透明而不是实色 —— 叠在任何底上都不会突然变成一条硬边 */
    val cardEdge: Color,
    /**
     * 卡片内部的小胶囊（pill）底色。
     *
     * ⚠️ **必须相对卡片取色，不能沿用页面级的 bgRaised**：深色下
     * `bgRaised #141518` 压在卡片 `card #17181C` 上只差 3/255，等于没有底色。
     * 用半透明叠色，换成任何卡片色都不会失效。
     * （这条是在设计稿阶段踩出来的，见 memory 里「深色 pill」那条。）
     */
    val cardChip: Color,
)

/**
 * 高对比变体（1.2.9）。由系统「高对比文字」开关触发，见 `Motion.highContrast`。
 *
 * ## 改的是哪三个 token，为什么是这三个
 *
 * | token | 改法 | 理由 |
 * |---|---|---|
 * | [inkFaint] | → [inkDim] | 未选中的页签、禁用态、占位下划线。它是全屏最弱的一层，
 * 正是高对比模式下第一个该消失的东西（弱到看起来像「没有内容」） |
 * | [hairline] | → [hairlineStrong] | 分割线与卡片描边。常规模式下刻意压弱是「不画格子」，
 * 但对需要高对比的用户，线看不见＝分组消失 |
 * | [cardEdge] | alpha × 2 | 同理，卡片的边界得看得见 |
 *
 * **不动的**：正文与强调色。它们本来就过了对比度门槛（浅色板每个 token 都验过），
 * 再提只会让页面显得生硬 —— §14 的口径是「可读」，不是「全都变成纯黑白」。
 *
 * ⚠️ 不要用「把整个色板取反」或者「插值到纯黑白」来实现：那会把这套视觉的
 * 冷暖分离（暖白墨 + 琥珀）抹掉，界面会退回 Material 味的灰。
 */
private fun AppPalette.contrastBoost(): AppPalette = copy(
    hairline = hairlineStrong,
    inkFaint = inkDim,
    cardEdge = cardEdge.copy(alpha = (cardEdge.alpha * 2f).coerceAtMost(1f)),
)

/**
 * 两套色板之间按 [t] 插值（0 = 全浅色，1 = 全深色）。
 *
 * ## 为什么用插值，而不是给整棵树套一个 `Crossfade`
 *
 * §14 要求「明暗切换要缓变（现在是瞬变）」。最直觉的写法是
 * `Crossfade(targetState = dark) { EnglishCommuteTheme(dark = it) { Root() } }`，
 * 但那个写法有个硬伤：**它会同时持有两棵树**，于是
 * `Root()` 里的 `LaunchedEffect`、列表状态、pager 状态都会各存在两份，
 * 交叉期间副作用跑两遍。对一个带 LazyColumn + HorizontalPager 的界面来说，
 * 那是「为了一个 220 ms 的过渡，让整页短暂翻倍」。
 *
 * 颜色插值没有这个问题：树只有一棵，只是它读到的颜色在 220 ms 里连续变过去。
 * 观感上其实更对 —— 交叉淡入是「两张图叠化」，而这里本来就是同一张图在变色。
 *
 * ## 代价（写在这里，因为它是可测量的）
 *
 * 插值期间 [LocalAppPalette] 每帧换一个新值，而它是 `staticCompositionLocalOf` ——
 * 也就是**每帧整棵子树重组**。13 帧 × 一屏 40 个条目 ≈ 几百次重组。
 * 实测可接受（颜色变化不触发重新测量，只触发重绘），但这是「主题切换是低频操作」
 * 这个前提换来的：**不要**把这个手法用到高频状态上。
 */
private fun mixPalettes(light: AppPalette, dark: AppPalette, t: Float): AppPalette {
    if (t <= 0f) return light
    if (t >= 1f) return dark
    fun c(a: Color, b: Color) = lerp(a, b, t)
    return AppPalette(
        bg = c(light.bg, dark.bg),
        bgRaised = c(light.bgRaised, dark.bgRaised),
        hairline = c(light.hairline, dark.hairline),
        hairlineStrong = c(light.hairlineStrong, dark.hairlineStrong),
        ink = c(light.ink, dark.ink),
        inkMid = c(light.inkMid, dark.inkMid),
        inkDim = c(light.inkDim, dark.inkDim),
        inkFaint = c(light.inkFaint, dark.inkFaint),
        amber = c(light.amber, dark.amber),
        amberDim = c(light.amberDim, dark.amberDim),
        amberFaint = c(light.amberFaint, dark.amberFaint),
        gloss = c(light.gloss, dark.gloss),
        crimson = c(light.crimson, dark.crimson),
        olive = c(light.olive, dark.olive),
        card = c(light.card, dark.card),
        cardEdge = c(light.cardEdge, dark.cardEdge),
        cardChip = c(light.cardChip, dark.cardChip),
    )
}

/**
 * 夜间色板 —— 「编辑部夜班」。
 *
 * 底色中性近黑（不是蓝黑，蓝黑会带出科技感），文字暖白（不是纯白，
 * 纯白在近黑上有眩光），强调色暖琥珀。
 */
val DarkPalette = AppPalette(
    bg = Color(0xFF0B0C0E),
    bgRaised = Color(0xFF141518),
    hairline = Color(0xFF1D1E21),
    hairlineStrong = Color(0xFF2A2B2F),
    ink = Color(0xFFF4F2EE),
    inkMid = Color(0xFFA8A49C),
    inkDim = Color(0xFF6E6B65),
    inkFaint = Color(0xFF4A4844),
    amber = Color(0xFFD9A05B),
    amberDim = Color(0x59D9A05B),
    amberFaint = Color(0x1AD9A05B),
    gloss = Color(0xFF9C9285),
    crimson = Color(0xFFD9694A),
    olive = Color(0xFF8FA86B),
    card = Color(0xFF17181C),
    cardEdge = Color(0x1FFFFFFF),
    cardChip = Color(0x1CFFFFFF),
)

/**
 * 日间色板 —— 「米白纸 + 深墨 + 赭石」。
 *
 * 底色刻意**不用纯白**（#FFFFFF）：纯白在纸面感上是反的，而且 AMOLED 上刺眼。
 * 用 #FBFAF7 这个偏暖的米白，配上纸色的分隔线，才有「印刷品」的联想。
 *
 * 每一个前景色都算过对比度（门槛：正文 4.5:1，图形/大字 3:1）：
 *
 * | token | 值 | 在 #FBFAF7 上 | 说明 |
 * |---|---|---|---|
 * | ink | #16181C | 17.03:1 | 深墨，不是纯黑 —— 纯黑压在暖纸上会发脏 |
 * | inkMid | #3E4249 | 9.67:1 | |
 * | inkDim | #6B6F77 | 4.83:1 | 次要信息，刚好过正文线 |
 * | inkFaint | #8F8B82 | 3.25:1 | 未选中图标，按图形门槛要求 |
 * | amber | #9C5F14 | 4.95:1 | ⚠️ 见下 |
 * | gloss | #756E64 | 4.82:1 | 译文 |
 * | crimson | #B23A1E | 5.72:1 | |
 * | olive | #5E7A32 | 4.67:1 | |
 *
 * **amber 是这次调色里唯一真正棘手的**：暗色版的 #D9A05B 直接搬过来只有
 * 2.20:1。往深里找，直到 #9C5F14 才越过 4.5:1。好在结果不亏 ——
 * 深琥珀压在米白纸上，比原来那个亮琥珀更像「钢笔里的赭石墨水」，
 * 反而更贴编辑部那套隐喻。
 *
 * 半透明的两个衍生色也跟着调了 alpha，因为纸上 8% 的琥珀几乎不可见：
 * - amberDim 从 35% → 40%（#D5BC9C 级别的可见度）
 * - amberFaint 从 10% → 12%（选中态背景，再淡就看不出来被选中）
 */
val LightPalette = AppPalette(
    bg = Color(0xFFFBFAF7),
    bgRaised = Color(0xFFF2EEE5),
    hairline = Color(0xFFE6E1D5),
    hairlineStrong = Color(0xFFD5CEC0),
    ink = Color(0xFF16181C),
    inkMid = Color(0xFF3E4249),
    inkDim = Color(0xFF6B6F77),
    inkFaint = Color(0xFF8F8B82),
    amber = Color(0xFF9C5F14),
    amberDim = Color(0x669C5F14),
    amberFaint = Color(0x1F9C5F14),
    gloss = Color(0xFF756E64),
    crimson = Color(0xFFB23A1E),
    olive = Color(0xFF5E7A32),
    card = Color(0xFFFFFFFF),
    cardEdge = Color(0x1416181C),
    cardChip = Color(0x1716181C),
)

/**
 * 当前色板。
 *
 * 用 `staticCompositionLocalOf` 而不是 `compositionLocalOf`：主题切换是
 * 低频操作（一次点击），而颜色读取是超高频（每次重组、每个元素）。
 * static 版本不做细粒度订阅追踪，代价是值一变就整棵子树重组 ——
 * 正是这里想要的权衡。Material 自己的 `LocalColorScheme` 也是这么做的。
 *
 * 默认值给暗色：万一某处忘了套 [EnglishCommuteTheme]，界面退化成
 * 上一版的观感，而不是白底白字。
 */
internal val LocalAppPalette = staticCompositionLocalOf { DarkPalette }

// ---------------------------------------------------------------------------
// 颜色 token（调用方按原名使用，实际取值随主题走）
// ---------------------------------------------------------------------------

/** 页面底色 */
val Bg: Color
    @Composable @ReadOnlyComposable get() = LocalAppPalette.current.bg

/** 浮起一层的底色 */
val BgRaised: Color
    @Composable @ReadOnlyComposable get() = LocalAppPalette.current.bgRaised

/**
 * 分割线。
 *
 * 刻意压得极弱：编辑部风格里分割线的作用是「提示分组」，不是「画格子」。
 * 亮度稍高一点，整页就会重新长出网格感，前功尽弃。
 */
val Hairline: Color
    @Composable @ReadOnlyComposable get() = LocalAppPalette.current.hairline

val HairlineStrong: Color
    @Composable @ReadOnlyComposable get() = LocalAppPalette.current.hairlineStrong

/** 墨色。暗色下是暖白（纯白在近黑底上有眩光），日间下是深墨（纯黑在暖纸上发脏） */
val Ink: Color
    @Composable @ReadOnlyComposable get() = LocalAppPalette.current.ink

val InkMid: Color
    @Composable @ReadOnlyComposable get() = LocalAppPalette.current.inkMid

val InkDim: Color
    @Composable @ReadOnlyComposable get() = LocalAppPalette.current.inkDim

val InkFaint: Color
    @Composable @ReadOnlyComposable get() = LocalAppPalette.current.inkFaint

/**
 * 强调色。
 *
 * 暗色的暖琥珀在近黑底上有一种「纸上的高亮笔」的联想，正好对应本 App 的
 * 主场景——在字幕上标出正在读的那一句。日间不可能沿用同一个值（对比度只有
 * 2.2:1），换成深赭石，隐喻从「高亮笔」变成「墨水」。
 */
val Amber: Color
    @Composable @ReadOnlyComposable get() = LocalAppPalette.current.amber

val AmberDim: Color
    @Composable @ReadOnlyComposable get() = LocalAppPalette.current.amberDim

val AmberFaint: Color
    @Composable @ReadOnlyComposable get() = LocalAppPalette.current.amberFaint

/** 译文色。和英文正文拉开，但不至于像第二个强调色那样抢戏 */
val Gloss: Color
    @Composable @ReadOnlyComposable get() = LocalAppPalette.current.gloss

val Crimson: Color
    @Composable @ReadOnlyComposable get() = LocalAppPalette.current.crimson

val Olive: Color
    @Composable @ReadOnlyComposable get() = LocalAppPalette.current.olive

/** 卡片底色。1.2.8 起列表条目、设置页区块都用它 */
val Card: Color
    @Composable @ReadOnlyComposable get() = LocalAppPalette.current.card

val CardEdge: Color
    @Composable @ReadOnlyComposable get() = LocalAppPalette.current.cardEdge

val CardChip: Color
    @Composable @ReadOnlyComposable get() = LocalAppPalette.current.cardChip

// ---------------------------------------------------------------------------
// 排版
// ---------------------------------------------------------------------------

/**
 * 字体族。
 *
 * 衬线只给标题用 —— 英文衬线（Noto Serif）在标题尺寸下像报纸刊头，
 * 是这套视觉里最不像「AI 生成」的一笔。但**正文一律不用衬线**：
 * 中文落回系统宋体后，小字号下笔画会糊成一片。
 */
private val Serif = FontFamily.Serif
private val Mono = FontFamily.Monospace

/**
 * 字号字号系统。
 *
 * 做成 class 而不是 object，是因为 TextStyle 里**烧死了颜色** ——
 * 主题一换，每个样式都得重新构造。做成 object 的话颜色会在类初始化时
 * 定死成某一个主题的值，切到日间模式后标题还是暖白色，等于没切。
 *
 * 每个属性都是 `val`，所以一个实例内只会构造一遍；而实例本身由
 * [EnglishCommuteTheme] 用 `remember(palette)` 持有，切主题才重建。
 */
@Immutable
class AppTextStyles(p: AppPalette) {

    /**
     * 页面刊头。
     *
     * ⚠️ 这里原来写的是「整个 App 只有一处」—— 那句话已经不成立了：
     *    素材库、打卡、设置、生词**四个页签各有一处**（每屏一处峰值，
     *    而不是全 App 一处）。留着旧说法会让人以为多出来的三处是错的，
     *    然后各自去 copy 一个字号出来。
     */
    val display = TextStyle(
        fontFamily = Serif,
        fontSize = 31.sp,
        // 负字距 + 紧行高：字号越大，字母之间越显得散、行与行越显得远。
        // 三者的调整方向是反的 —— 这是排版里的常识，但只有写下来才会被执行
        letterSpacing = (-0.02).em,
        lineHeight = 33.sp,
        color = p.ink,
    )

    /** 节目标题 / 分节日期 —— 衬线的第二次也是最后一次出场 */
    val section = TextStyle(
        fontFamily = Serif,
        fontSize = 21.sp,
        lineHeight = 27.sp,
        color = p.ink,
    )

    /** 列表项标题 */
    val title = TextStyle(
        fontSize = 15.sp,
        lineHeight = 20.sp,
        // 1.2.8：Medium(500) → SemiBold(600)。卡片版式里标题与元信息离得更近，
        // 靠**字重**拉开比靠字号拉开更省空间，也不用把字号做小
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.005).em,
        color = p.ink,
    )

    /** 正文散文（设置页说明、空态文案） */
    val body = TextStyle(
        fontSize = 14.sp,
        lineHeight = 22.sp,
        color = p.inkMid,
    )

    /** 字幕英文。行高给足，通勤路上扫读才不费劲 */
    val line = TextStyle(
        fontSize = 15.sp,
        lineHeight = 24.sp,
        color = p.inkMid,
    )

    /**
     * 复习卡正面的那句原话 —— **整套样式里唯一为「大段无衬线英文」加的档**。
     *
     * ## 为什么不能用现成的
     *
     * | 候选 | 为什么不行 |
     * |---|---|
     * | [line]（15sp） | 它是字幕的**正常**字号。而复习卡正面整屏只有这一句 —— 摆 15sp 看着像没做完 |
     * | [section] / [display] | 都是**衬线**。这个 App 的约定是「衬线只给标题」（见 section 的注释），用它读一整句英文违背它 |
     *
     * ## ⚠️ 只在这一处用
     *
     * 别再为了「这里想大一点」去加第五个字号档。先问一句
     * **「line 真的不够用吗」** —— 生词本的四个界面文件里曾经有 18 处
     * `AppText.x.copy(fontSize = ...)`，就是这么长出来的，
     * 而结果是「生词页的字和其它三页都不一样」（用户报的）。
     */
    val reading = TextStyle(
        fontSize = 19.sp,
        lineHeight = 30.sp,
        color = p.ink,
    )

    /** 字幕中文译文。比英文略小、颜色略退，形成从属关系 */
    val gloss = TextStyle(
        fontSize = 13.5.sp,
        lineHeight = 21.sp,
        color = p.gloss,
    )

    /** 次级信息（来源、时长、相对时间） */
    val caption = TextStyle(
        fontSize = 12.5.sp,
        lineHeight = 17.sp,
        color = p.inkDim,
    )

    /** 标签。字距拉开 + 全大写，是编辑部排版里最典型的「小字」处理 */
    val label = TextStyle(
        fontSize = 10.5.sp,
        lineHeight = 14.sp,
        letterSpacing = 1.2.sp,
        fontWeight = FontWeight.Medium,
        color = p.inkDim,
    )

    /** 等宽数字 */
    val digit = TextStyle(
        fontFamily = Mono,
        fontSize = 12.5.sp,
        lineHeight = 16.sp,
        color = p.inkMid,
    )

    /** 卡片上的元信息胶囊（1.2.8）。字距接近 0 —— [label] 那 1.2sp 在胶囊里会撑开 */
    val pill = TextStyle(
        fontSize = 10.5.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.2.sp,
        color = p.inkDim,
    )

    /**
     * 底栏页签上的角标（3.0.5）。
     *
     * ⚠️ 和 [pill] 的区别**只有行高**，但那一项非改不可：
     *    pill 的 14sp 行高会让角标高到快赶上图标（图标 19dp），
     *    看着像在图标上贴了一块饼，而不是一个角标。
     *    这里不写行高 —— 交给字体自己的度量（约 1.2 倍字号 ≈ 12.6dp），
     *    加上下各 1.5dp 内边距，整个角标约 15.6dp。
     *
     * 字距归零同上：角标里通常只有一两位数字，[label] 那 1.2sp 会把它拉散。
     *
     * ⚠️ **只许底栏角标用**。别拿它当「小号文字」的通用档 ——
     *    这个样式表是按**语义**分的（[display] 是页标题、[pill] 是卡片胶囊…），
     *    按字号分档会让「谁该用哪一档」变成看心情。
     *    见 `tools/verify-word-badge.py`，它守着这一条。
     */
    val badge = TextStyle(
        fontSize = 10.5.sp,
        letterSpacing = 0.sp,
        fontWeight = FontWeight.Medium,
        color = p.inkDim,
    )

    /** 大号等宽数字（播放页时间） */
    val digitLarge = TextStyle(
        fontFamily = Mono,
        fontSize = 15.sp,
        lineHeight = 19.sp,
        color = p.ink,
    )
}

internal val LocalAppTextStyles = staticCompositionLocalOf { AppTextStyles(DarkPalette) }

/**
 * 排版 token。
 *
 * 和颜色一样保持同名可读：`AppText.display` 换个主题就换了颜色，
 * 调用点完全不需要知道主题的存在。
 */
val AppText: AppTextStyles
    @Composable @ReadOnlyComposable get() = LocalAppTextStyles.current

private fun typographyOf(t: AppTextStyles) = Typography(
    bodyLarge = t.body,
    bodyMedium = t.caption,
    titleLarge = t.section,
    titleMedium = t.title,
    labelLarge = t.caption,
    labelSmall = t.label,
)

// ---------------------------------------------------------------------------
// 间距
// ---------------------------------------------------------------------------

/**
 * 间距刻度。
 *
 * 页面左右留 22dp 而不是 Material 默认的 16dp：多这 6dp，正文每行少放
 * 一两个词，但纸面感完全不一样。
 *
 * 间距不随主题变，所以仍然是普通常量 —— 不必为一致性把没有主题差异的
 * 东西也塞进 CompositionLocal。
 */
object Space {
    val pageH = 22.dp
    val section = 30.dp
    val block = 20.dp
    val row = 14.dp
    val tight = 6.dp
}

/**
 * 圆角刻度（1.2.8 新增）。
 *
 * 1.2.0 那版这套视觉是**全直角**的（唯一例外是封面图上那 3dp）。
 * 用户重新选定方向之后卡片回来了，圆角也就回来了 —— 但只给
 * **有实体边界的东西**（卡片、封面、胶囊），文字块、分隔线、控制区仍然直角。
 *
 * 集中在这里的理由和 [Space] 一样：值本身不重要，**一致**才重要。
 */
object Radius {
    /** 列表条目卡片。13dp 在 390dp 宽的屏上是「一眼看出是卡片，但不圆润」 */
    val card = 13.dp
    /** 卡片里的封面小图 */
    val cover = 9.dp
    /** 胶囊。给一个大到必然溢出的值，而不是算出来的半高 —— 内容变高也不用回来改 */
    val pill = 100.dp
}

// ---------------------------------------------------------------------------
// 主题入口
// ---------------------------------------------------------------------------

private fun schemeOf(p: AppPalette, dark: Boolean) = if (dark) {
    darkColorScheme(
        primary = p.amber,
        onPrimary = p.bg,
        background = p.bg,
        onBackground = p.ink,
        surface = p.bg,
        onSurface = p.ink,
        surfaceVariant = p.bgRaised,
        onSurfaceVariant = p.inkMid,
        outline = p.hairline,
        error = p.crimson,
        onError = p.ink,
    )
} else {
    lightColorScheme(
        primary = p.amber,
        onPrimary = p.bg,
        background = p.bg,
        onBackground = p.ink,
        surface = p.bg,
        onSurface = p.ink,
        surfaceVariant = p.bgRaised,
        onSurfaceVariant = p.inkMid,
        outline = p.hairline,
        error = p.crimson,
        onError = p.ink,
    )
}

/**
 * 主题。
 *
 * [dark] 默认为「跟随系统」，这样每个调用点都不必重复解析
 * [ThemeMode]；由 Activity 决定具体值后显式传进来。
 *
 * ## 1.2.9 的两处变化
 *
 * 1. **明暗之间连续过渡**（§14「明暗切换要缓变」）。改之前 palette 是
 *    `if (dark) DarkPalette else LightPalette` —— 瞬变。现在按 220 ms
 *    插值，理由与代价写在 [mixPalettes] 上。
 *
 * 2. **[highContrast] 参数**，来自系统「高对比文字」开关。
 *    它**乘在插值之后**，所以过渡期间也是连续的。
 */
@Composable
fun EnglishCommuteTheme(
    dark: Boolean = isSystemInDarkTheme(),
    highContrast: Boolean = false,
    content: @Composable () -> Unit,
) {
    val mix by animateFloatAsState(
        targetValue = if (dark) 1f else 0f,
        // 时长与曲线一律从 Motion 取（`verify-refactor-120.py` 会扫
        // 「界面里散落的 tween(...)」—— 各写各的时长会让节奏对不上）
        animationSpec = Motion.themeMix(),
        label = "themeMix",
    )
    val mixed = remember(mix) { mixPalettes(LightPalette, DarkPalette, mix) }
    val palette = remember(mixed, highContrast) {
        if (highContrast) mixed.contrastBoost() else mixed
    }
    val textStyles = remember(palette) { AppTextStyles(palette) }
    val typography = remember(textStyles) { typographyOf(textStyles) }
    val scheme = remember(palette, dark) { schemeOf(palette, dark) }

    CompositionLocalProvider(
        LocalAppPalette provides palette,
        LocalAppTextStyles provides textStyles,
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = typography,
            content = content,
        )
    }
}
