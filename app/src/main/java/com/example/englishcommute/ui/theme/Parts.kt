package com.example.englishcommute.ui.theme

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.material3.Text
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

/**
 * 跨页复用的小零件。
 *
 * ## 为什么要有这个文件（1.2.0）
 *
 * 之前素材库、打卡页、设置页各自实现了一遍 `Metric`、`formatBytes`、
 * 文字链接、页脚 —— 其中 `formatBytes` 在两个文件里是**逐字节相同**的两份。
 *
 * 重复实现本身不算大问题，真正的代价是：「这几处该不该长得一样」
 * 变成了一个没人回答的问题。改了素材库的链接样式，设置页不会跟着变，
 * 而两者在用户眼里是同一种东西 —— 于是界面慢慢显出拼接感。
 *
 * 收到这里之后，「一致」是默认行为，「不一致」才需要显式写出来。
 *
 * ## 边界
 *
 * 只收「**样式**层面确定该一致」的东西。各页的版式骨架（Masthead 的排法、
 * 区块间距）**刻意不收** —— 1.2.0 的方向恰恰是让三页各有各的节奏：
 * 素材库是流、打卡是单焦点、设置是密集列表。把版式也统一起来，
 * 就又回到「三页一个模板」了。
 */

/**
 * 文字链接。**下划线是它唯一的身份标识** —— 这套视觉里没有按钮色，
 * 没有下划线就跟普通文字没有区别。
 */
@Composable
fun LinkText(
    text: String,
    color: Color = InkDim,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Text(
        text = text,
        style = AppText.caption,
        color = color,
        textDecoration = TextDecoration.Underline,
        // 1.2.8：文字链接的按压语汇是「变淡」而不是缩放 ——
        // 下划线是它的身份标识，按下时淡下去即可，不要动它的几何
        modifier = modifier.pressFeedback(
            scale = 1f,
            pressedAlpha = 0.55f,
            onClick = onClick,
        ),
    )
}

/**
 * 一个指标：小标签 + 大号等宽数字。
 *
 * 数字用 Mono 是有理由的：这些值（体积、条数、耗时）经常并排出现，
 * 比例字体下同样位数的数字宽度不同，一排指标会显得参差。
 */
@Composable
fun Metric(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = Ink,
) {
    // ⚠️ 内容必须**居中对齐**（1.2.3 修）。
    //
    // 上一版只给每列加了 `weight(1f)`（等宽），但列内还是默认的左对齐 ——
    // 于是四列的文字各自贴在自己格子的左边，视觉上仍然是「一串靠左的文字
    // 加一堆空格」，等分等于白做。占满整行 + 内容居中，才是「平铺」。
    //
    // 不传 modifier（自然宽度）时，居中与左对齐是同一件事 ——
    // 所以打卡页那三处不受影响。
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = AppText.label)
        Spacer(Modifier.height(Space.tight))
        Text(value, style = AppText.digitLarge, color = valueColor)
    }
}

/**
 * 字节数 → 人话。
 *
 * ## 精度为什么是现在这样（1.2.2 定案）
 *
 * 这份实现原先写的是「粗粒度：KB/MB 取整、GB 一位小数」，理由是
 * 「用户要的是量级感，不是精确值」。**理由本身没错，但它没有成为事实** ——
 * 这份函数当时没有任何调用方，三处界面用的都是各自那份
 * `%.0f KB / %.1f MB / %.2f GB` 的版本。
 *
 * 于是收敛时按既成视觉对齐：升级之后用户看到的数字**与之前逐字相同**。
 * 一个只在注释里成立的「更优设计」不值得让所有人重新适应一遍。
 *
 * ⚠️ 上面这条也适用于本文件里的每一份零件：**看到定义和调用方不一致时，
 *    先问哪一份是用户正在看的**，再决定改哪边。
 */
fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.0f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f MB".format(mb)
    return "%.2f GB".format(mb / 1024.0)
}

// ---------------------------------------------------------------------------
// 按下反馈（1.2.8，Apple §1 / §10）
// ---------------------------------------------------------------------------

/**
 * 按下缩放的档位。
 *
 * **两档是刻意的**：整行条目按 [Control] 那么缩，卡片的左右边缘会「抖出去」，
 * 看起来像布局在动而不是它在被按。行越大，缩放要越小。
 */
object PressScale {
    /** 按钮、图标、chip 这类小控件 */
    const val Control = 0.96f

    /** 整行条目 / 卡片。只缩一点点，够察觉即可 */
    const val Row = 0.985f
}

/**
 * 可点击 + **按下有反馈**。
 *
 * ## 为什么需要它（1.2.8）
 *
 * 在此之前全工程 59 个可点元素都直接用 `Modifier.clickable`，而
 * `collectIsPressedAsState` 的使用次数是 **0** —— 也就是说**按下去什么都不发生**，
 * 只有松手后界面才变。Apple《Designing Fluid Interfaces》把它列在第 1 条：
 * 「反馈要发生在 pointer-down，不是松手」。一个按下去没有反应的按钮，
 * 用户读到的不是「还没生效」而是「没点上」——于是他会再点一次。
 *
 * ## 为什么是缩放，而不是水波纹
 *
 * 这套视觉里没有一个 Material 组件（底栏都是自绘的），突然冒出一圈
 * 水波纹会像从别的 App 里借来的。缩放是**这套视觉自己的语言**：
 * 纸面上的东西被按下去时本来就该轻微下沉。
 *
 * 顺带把 `indication = null` 显式写上——一个 app 同时存在两种按压语言
 * 比没有按压语言更糟。
 *
 * ## 为什么用 [Motion.quick] 而不是更短的时长
 *
 * 120 ms 配一条**起手斜率 2.25** 的曲线（[Motion.EasingStandard]），
 * 前 30 ms 就能看出缩小。真正决定「跟不跟手」的是曲线的起手陡度，
 * 不是时长 —— 1.2.8 之前那条曲线起手斜率是 0，同样的 120 ms
 * 前 40 ms 几乎看不出动。
 */
@Composable
fun Modifier.pressFeedback(
    scale: Float = PressScale.Control,
    enabled: Boolean = true,
    /** 按下时的不透明度。给**文字链接**用 —— 12.5sp 的字缩 4% 只有半个像素，
     *  等于没做，而「变淡」是文字本来就有的按压语汇 */
    pressedAlpha: Float = 1f,
    onClick: () -> Unit,
): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val target = if (pressed && enabled) scale else 1f
    val animated by animateFloatAsState(
        targetValue = target,
        animationSpec = Motion.quick(),
        label = "press",
    )
    // 「移除动画」时不要缩放（减弱不清零：颜色与透明度的变化仍然保留，
    // 而缩放属于要被去掉的那一类）
    val effective = if (Motion.reduceMotion) 1f else animated
    // 透明度**不**受「移除动效」影响：变淡不是位移，去掉它反而会让
    // 「点了没反应」和「没点」看起来一样
    // ⚠️ 局部量不要叫 `alpha`：`GraphicsLayerScope` 里也有一个同名属性，
    //    虽然 Kotlin 会让局部量胜出（`this.alpha = alpha` 也确实能跑），
    //    但这行代码读起来是在自己赋值给自己
    val shownAlpha by animateFloatAsState(
        targetValue = if (pressed && enabled) pressedAlpha else 1f,
        animationSpec = Motion.quick(),
        label = "pressAlpha",
    )

    return this
        .graphicsLayer {
            scaleX = effective
            scaleY = effective
            alpha = shownAlpha
        }
        .clickable(
            interactionSource = interaction,
            indication = null,
            enabled = enabled,
            onClick = onClick,
        )
}

/**
 * 同上，但**只做缩放**，点击手势由调用方自己接。
 *
 * 存在的理由是 `combinedClickable`（长按菜单）：它要同时处理点击与长按，
 * 没法塞进 [pressFeedback]。调用方把同一个 [source] 传给
 * `combinedClickable`，两边就共享同一份按压状态。
 */
@Composable
fun Modifier.pressScale(
    source: MutableInteractionSource,
    scale: Float = PressScale.Row,
): Modifier {
    val pressed by source.collectIsPressedAsState()
    val animated by animateFloatAsState(
        targetValue = if (pressed) scale else 1f,
        animationSpec = Motion.quick(),
        label = "pressScale",
    )
    val effective = if (Motion.reduceMotion) 1f else animated
    return this.graphicsLayer {
        scaleX = effective
        scaleY = effective
    }
}

/**
 * 一个输入框。**这是全应用第一个文本输入**（4.0.0 的账号页之前，
 * 这个 App 只需要开关、滑杆和档位 —— 没有一个地方要用户打字）。
 *
 * ## 为什么不用 M3 的 `OutlinedTextField`
 *
 * 它自带一套完整的外观：浮动标签、聚焦时的描边动画、自己的内边距与容器色。
 * 要把它改成本项目这套「细线 + 留白」需要覆盖十几个参数，
 * 而每覆盖一个就多一处「升级 M3 时可能悄悄变样」的地方。
 * `BasicTextField` 只给行为（光标、选中、输入法），外观全部由这里画 ——
 * 这一页上没有任何东西是不受控的。
 *
 * ## 三条设计选择
 *
 * - **底色用 `Hairline` 的 22%**：与「你听到的」那块引用框同一个色
 *   （`WordCardSheet` 的 `ContextBlock`），所以它看起来属于同一套东西；
 * - **密码框不给「显示密码」的小眼睛**：这个 App 的密码是给熟人用的，
 *   而多一个按钮就多一次「在公共场合把密码显示出来」的机会；
 * - **数字/大写锁定用 `KeyboardType`，不做前端校验**：校验的判据在服务端
 *   （那里才是权威），前端只负责把键盘调对，省用户的手。
 */
@Composable
fun InputField(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    modifier: Modifier = Modifier,
    password: Boolean = false,
    numeric: Boolean = false,
    digitStyle: Boolean = false,
    imeAction: ImeAction = ImeAction.Next,
    onImeAction: () -> Unit = {},
) {
    val shape = RoundedCornerShape(Radius.card)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        textStyle = (if (digitStyle) AppText.digit else AppText.body).copy(color = Ink),
        cursorBrush = SolidColor(Amber),
        visualTransformation = if (password) PasswordVisualTransformation()
        else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = when {
                numeric -> KeyboardType.NumberPassword
                password -> KeyboardType.Password
                else -> KeyboardType.Text
            },
            imeAction = imeAction,
        ),
        keyboardActions = KeyboardActions(onDone = { onImeAction() }),
        decorationBox = { inner ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(Hairline.copy(alpha = 0.22f))
                    .padding(horizontal = 14.dp, vertical = 13.dp),
            ) {
                if (value.isEmpty()) {
                    Text(hint, style = AppText.body, color = InkFaint)
                }
                inner()
            }
        },
    )
}

/**
 * 主操作按钮（实心强调色）。
 *
 * ⚠️ 这个形状在 4.0.0 之前**已经被手写过两次**（打卡页的「分享到微信」、
 *    更新面板的动作条）。这里收成一份，新地方一律用它 ——
 *    再手抄一份的话，「按钮高度 / 圆角 / 按下反馈」这些细节就会开始各自漂移，
 *    而那种漂移单看每一处都完全正常。
 *    （那两处**先不动**：改已经在跑的界面要单独一轮，不该混在这次里。）
 */
@Composable
fun ActionButton(
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    // ⚠️ `onClick` **必须放在最后**：写在中间的话，调用点写成
    //    `ActionButton("登录", enabled = x) { … }` 时那对大括号会绑到
    //    **最后一个参数**（`enabled: Boolean`）上，报的是一串看不懂的类型错。
    //    本工程为此专门吃过一次亏（「改参数表要 grep 全部调用点」）。
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.45f)
            .clip(RoundedCornerShape(Radius.card))
            .background(Amber)
            .pressFeedback(enabled = enabled, onClick = onClick)
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = AppText.title, color = Bg)
    }
}
