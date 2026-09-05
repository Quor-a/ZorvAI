package com.ai.assistance.quro.core.ui.dynamicui

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.border
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.media.MediaPlayer
import android.net.Uri
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.ui.viewinterop.AndroidView
import com.ai.assistance.quro.ui.CodeBlock
import com.ai.assistance.quro.ui.MarkdownText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt

/**
 * ZorvAI 动态 UI 的 Compose 原生渲染器（参照 Kai `KaiUiRenderer` 设计重写）。
 *
 * 之所以坚持原生渲染而非 WebView：
 *  - 控件可直接读写状态，用户交互能原样回传给模型（WebView 需桥接且易丢事件）；
 *  - 自动继承应用主题（深色/浅色、字体缩放、动态取色），无需 AI 关心配色；
 *  - 无 JS 注入面，安全边界更清晰。
 *
 * @param root 解析后的节点树
 * @param modifier 根容器修饰符
 * @param onAction 动作回调：宿主收到动作后决定如何执行（发消息给模型 / 调工具 / 开网页等）
 * @param onSubmit 便捷回调：当动作需要把收集到的表单值作为用户消息发回模型时触发
 */
@Composable
fun QuroUiRenderer(
    root: QuroUiNode,
    modifier: Modifier = Modifier,
    onAction: (QuroUiAction, Map<String, String>) -> Unit = { _, _ -> },
) {
    // 表单状态集中托管：id -> 值（String / Boolean / Float）
    // 用 mutableStateMapOf，任一控件写入都会自动触发依赖它的 Composable 重组。
    // 修复：remember{} 无 key，root 更换后旧 state/hidden 残留，新树复用旧 id 会读到旧值。
    // 用 remember(root) 让 root 变化时重建两个 map，清空旧值。
    val state = remember(root) { mutableStateMapOf<String, Any>() }
    // 被 toggle 隐藏的节点 id 集合
    val hidden = remember(root) { mutableStateMapOf<String, Boolean>() }

    RenderNode(
        node = root,
        state = state,
        hidden = hidden,
        onAction = onAction,
        modifier = modifier,
        isRoot = true,
    )
}

// =============================================================================================
// 递归渲染
// =============================================================================================

@Composable
private fun RenderNode(
    node: QuroUiNode,
    state: MutableMap<String, Any>,
    hidden: MutableMap<String, Boolean>,
    onAction: (QuroUiAction, Map<String, String>) -> Unit,
    modifier: Modifier = Modifier,
    isRoot: Boolean = false,
) {
    // toggle 隐藏：节点有 id 且被标记为隐藏则整体不渲染
    val nodeId = node.id
    if (nodeId != null && hidden[nodeId] == true) return
    // 通用样式 visible=false → 整节点不渲染（数据驱动显隐，不惊动模型）
    if (node.style?.visible == false) return

    // 通用样式套用：把 AI 挂在任意节点上的 QuroUiStyle 统一转成 Modifier。
    // surface=false 时跳过背景/边框/圆角/阴影（卡片节点刻意不渲染外壳，见 RenderCard）。
    val styled = applyFrame(modifier, node.style, surface = node !is QuroCardNode)

    when (node) {
        is QuroColumnNode -> RenderColumn(node, state, hidden, onAction, styled, isRoot)
        is QuroRowNode -> RenderRow(node, state, hidden, onAction, styled)
        is QuroBoxNode -> RenderBox(node, state, hidden, onAction, styled)
        is QuroPaneNode -> RenderPane(node, state, hidden, onAction, styled)
        is QuroCardNode -> RenderCard(node, state, hidden, onAction, styled)
        is QuroTextNode -> RenderText(node, styled)
        is QuroImageNode -> RenderImage(node, styled)
        is QuroMarkdownNode -> RenderMarkdown(node, onAction, styled)
        is QuroVideoNode -> RenderVideo(node, styled)
        is QuroAudioNode -> RenderAudio(node, onAction, styled)
        is QuroBrowserNode -> RenderBrowser(node, onAction, styled)
        is QuroHtmlNode -> RenderHtml(node, styled)
        is QuroCodeNode -> RenderCode(node, onAction, styled)
        is QuroIconNode -> RenderIcon(node, styled)
        is QuroBadgeNode -> RenderBadge(node, styled)
        is QuroProgressNode -> RenderProgress(node, styled)
        is QuroDividerNode -> RenderDivider(node, styled)
        is QuroSpacerNode -> RenderSpacer(node, styled)
        is QuroButtonNode -> RenderButton(node, state, hidden, onAction, styled)
        is QuroTextInputNode -> RenderTextInput(node, state, styled)
        is QuroCheckboxNode -> RenderCheckbox(node, state, styled)
        is QuroSwitchNode -> RenderSwitch(node, state, styled)
        is QuroSelectNode -> RenderSelect(node, state, styled)
        is QuroSliderNode -> RenderSlider(node, state, styled)
        is QuroListNode -> RenderList(node, state, hidden, onAction, styled)
        is QuroTabsNode -> RenderTabs(node, state, hidden, onAction, styled)
    }
}

// =============================================================================================
// 通用样式套用（v1.0.83）：把 QuroUiStyle 转成 Compose Modifier
// =============================================================================================

/**
 * 通用样式 → Modifier。对任意节点统一生效：外边距(margin) / 尺寸(width,height,max) /
 * 透明度(opacity) / 表面(背景·边框·圆角·阴影，surface=true 时) / 内边距(padding)。
 *
 * 约定：
 *  - 内边距由各具体渲染器决定（box 用 style.padding；column/row/card/pane 用自身 padding 字段），
 *    因此本函数**不**套内边距，避免与布局节点的原生 padding 叠加。
 *  - surface=true 才渲染背景/边框/圆角/阴影；卡片节点(RenderCard)刻意传 false，
 *    以保持「动态对话框 UI 不渲染小卡片外壳」的设计。
 */
private fun applyFrame(modifier: Modifier, style: QuroUiStyle?, surface: Boolean): Modifier {
    if (style == null) return modifier
    var m = modifier
    // 外边距（最外层）
    style.margin?.let { m = m.padding(toPaddingValues(it)) }
    // 尺寸 / 最大尺寸
    when (val w = style.width) {
        is QuroUiSize.Fixed -> m = m.width(w.dp.dp)
        is QuroUiSize.Fill -> m = m.fillMaxWidth()
        is QuroUiSize.Wrap -> m = m.wrapContentWidth()
        null -> {}
    }
    when (val h = style.height) {
        is QuroUiSize.Fixed -> m = m.height(h.dp.dp)
        is QuroUiSize.Fill -> m = m.fillMaxHeight()
        is QuroUiSize.Wrap -> m = m.wrapContentHeight()
        null -> {}
    }
    style.maxWidth?.let { m = m.widthIn(max = it.dp) }
    style.maxHeight?.let { m = m.heightIn(max = it.dp) }
    // 透明度
    style.opacity?.let { m = m.alpha(it.coerceIn(0f, 1f)) }
    // 表面：背景 / 边框 / 圆角 / 阴影
    if (surface) {
        val radius = (style.borderRadius ?: 0).dp
        val shape = RoundedCornerShape(radius)
        buildBrush(style.background)?.let { brush -> m = m.background(brush, shape) }
        // 修复：QuroUiColor.parse 在解析失败时返回 null，Modifier.b
        // 需要非空 Color。AI 输入坏颜色值时这里会编译失败/运行时崩溃。
        // 用 parseOr(c, Color.Transparent) 兜底，最差也是透明边，肉眼看不见。
        style.borderColor?.let { c -> m = m.border((style.borderWidth ?: 1).dp, QuroUiColor.parseOr(c, Color.Transparent), shape) }
        if (radius > 0.dp) m = m.clip(shape)
        style.shadowElevation?.let { m = m.shadow(it.dp, shape) }
    }
    return m
}

/** QuroUiEdges → Compose PaddingValues（四边分别取，缺省回落到 all/horizontal/vertical）。 */
private fun toPaddingValues(edges: QuroUiEdges): PaddingValues {
    val all = edges.all ?: 0
    val h = (edges.horizontal ?: all).coerceAtLeast(0)
    val v = (edges.vertical ?: all).coerceAtLeast(0)
    val top = (edges.top ?: v).coerceAtLeast(0)
    val bottom = (edges.bottom ?: v).coerceAtLeast(0)
    val start = (edges.start ?: h).coerceAtLeast(0)
    val end = (edges.end ?: h).coerceAtLeast(0)
    return PaddingValues(start = start.dp, top = top.dp, end = end.dp, bottom = bottom.dp)
}

/** QuroUiBackground → Compose Brush（纯色为 SolidColor，渐变支持 vertical/horizontal/radial/diagonal）。
 *
 * 修复：QuroUiColor.parse 返回 Color?，AI 坏颜色值（如 "blueish"、"#zzz"）会产出 null。
 * SolidColor 需非空 Color，Brush.*Gradient 需 List<Color>（不是 List<Color?>）。
 * 这里统一兜底：Solid 用 Color.Transparent，Gradient 用 mapNotNull 过滤；
 * 过滤完若为空也降级为单色 SolidColor(Color.Transparent) 以满足非空契约。
 */
private fun buildBrush(bg: QuroUiBackground?): Brush? = when (bg) {
    is QuroUiBackground.Solid -> SolidColor(QuroUiColor.parseOr(bg.color, Color.Transparent))
    is QuroUiBackground.Gradient -> {
        val colors = bg.colors.mapNotNull { QuroUiColor.parse(it) }
        if (colors.isEmpty()) {
            SolidColor(Color.Transparent)
        } else when (bg.direction?.lowercase()) {
            "horizontal" -> Brush.horizontalGradient(colors)
            "radial" -> Brush.radialGradient(colors)
            "diagonal" -> Brush.linearGradient(colors) // 默认 top-start → bottom-end
            else -> Brush.verticalGradient(colors)
        }
    }
    null -> null
}

// =============================================================================================
// 布局
// =============================================================================================

@Composable
private fun RenderColumn(
    node: QuroColumnNode,
    state: MutableMap<String, Any>,
    hidden: MutableMap<String, Boolean>,
    onAction: (QuroUiAction, Map<String, String>) -> Unit,
    modifier: Modifier,
    isRoot: Boolean = false,
) {
    // 修复：node.padding 为 Int?，AI 按通用样式文档写对象式 padding（{"top":16,"horizontal":8}）
    // 时 optIntOrNull 返回 null → 0。回落 node.style?.padding 走 toPaddingValues 统一处理。
    val stylePad = node.style?.padding?.let { toPaddingValues(it) }
    val m = modifier
        .then(if (stylePad != null) Modifier.padding(stylePad) else if ((node.padding ?: 0) > 0) Modifier.padding((node.padding ?: 0).dp) else Modifier)
        .then(
            // verticalScroll 需要有限高度，否则在 LazyColumn 之类父容器下会因「infinity max height」直接抛 IllegalStateException。
            // 根列：cap = screenHeightDp（屏幕高度），长卡片（如工具中心）底部不再被裁，能整张滚上看；
            // 非根列：cap = 480.dp（嵌入区域上限），防单卡内部过高影响外层滚动节奏。
            if (node.scrollable) {
                val maxHeight = if (isRoot) LocalConfiguration.current.screenHeightDp.dp else 480.dp
                Modifier.heightIn(max = maxHeight).verticalScroll(rememberScrollState())
            } else Modifier
        )

    Column(
        modifier = m,
        verticalArrangement = Arrangement.spacedBy((node.spacing ?: 8).dp),
        horizontalAlignment = when (node.horizontalAlign?.lowercase()) {
            "center", "centerhorizontally" -> Alignment.CenterHorizontally
            "end", "right" -> Alignment.End
            else -> Alignment.Start
        },
    ) {
        node.children.forEach { child ->
            // 修复：可滚动 Column 中 weight 子项按无限高度测量且 fill=true，
            // 直接抛 IllegalStateException: infinity maximum height constraints，整张卡崩掉。
            // 仅在不可滚动时才应用 weight。
            val childMod = if (!node.scrollable) {
                weightOf(child)?.let { Modifier.weight(it) } ?: Modifier
            } else Modifier
            // align 同样来自通用样式：column 控水平对齐。
            val aligned = when (child.style?.align?.lowercase()) {
                "center" -> childMod.align(Alignment.CenterHorizontally)
                "end", "right" -> childMod.align(Alignment.End)
                else -> childMod
            }
            RenderNode(child, state, hidden, onAction, aligned)
        }
    }
}

/** 提取节点的 weight（用于 Row/Column 内按比例分配空间）。
 *  修复：原只覆盖 column/row/box/card 容器，prompt 说"任意节点 weight"，
 *  叶子节点（button/text/image 等）写 weight 被静默丢弃。扩展到通用 style 读取。 */
private fun weightOf(node: QuroUiNode): Float? {
    // 容器节点优先用自身 weight 字段
    val direct = when (node) {
        is QuroColumnNode -> node.weight
        is QuroRowNode -> node.weight
        is QuroBoxNode -> node.weight
        is QuroCardNode -> node.weight
        else -> null
    }
    if (direct != null) return direct
    // 叶子节点从通用 style.width/height 的 {"weight":N} 读取
    val s = node.style ?: return null
    (s.width as? QuroUiSize.Fill)?.weight?.let { return it }
    (s.height as? QuroUiSize.Fill)?.weight?.let { return it }
    return null
}

@Composable
private fun RenderRow(
    node: QuroRowNode,
    state: MutableMap<String, Any>,
    hidden: MutableMap<String, Boolean>,
    onAction: (QuroUiAction, Map<String, String>) -> Unit,
    modifier: Modifier,
) {
    // 修复：同 RenderColumn，对象式 padding 回落 node.style?.padding。
    val stylePad = node.style?.padding?.let { toPaddingValues(it) }
    val sizeClass = LocalSurfaceSizeClass.current
    // 响应式布局：在窄屏（Compact）下，若 Row 有多个子且没有 weight（典型的「两等列」「标签+控件并排」）
    // 自动改为竖排，避免两列在窄屏被 surface 边缘裁掉。与 pane direction=auto 行为对齐。
    // 有 weight 的 Row 不受影响（weight 已保证比例分摊，不会溢出）。
    // 修复：Row 非 weight 分支下子节点自带 fillMaxWidth（RenderText/RenderImage/RenderBrowser），
    // 每个子都占满整行宽 → 横向溢出被 clipToBounds 裁掉后半。Medium 也竖排，防溢出。
    val stackVertically = (sizeClass == SurfaceSizeClass.Compact || sizeClass == SurfaceSizeClass.Medium)
        && node.children.size > 1
        && node.children.none { weightOf(it) != null }

    val m = modifier
        .then(if (stylePad != null) Modifier.padding(stylePad) else if ((node.padding ?: 0) > 0) Modifier.padding((node.padding ?: 0).dp) else Modifier)
        .then(if (node.scrollable && !stackVertically) Modifier.horizontalScroll(rememberScrollState()) else Modifier)

    if (stackVertically) {
        Column(
            modifier = m,
            verticalArrangement = Arrangement.spacedBy((node.spacing ?: 8).dp),
            horizontalAlignment = Alignment.Start,
        ) {
            node.children.forEach { child ->
                // 竖排时每行 fillMaxWidth，避免子节点因内在宽度小于父宽度而左对齐留白。
                RenderNode(child, state, hidden, onAction, Modifier.fillMaxWidth())
            }
        }
    } else {
        Row(
            modifier = m,
            horizontalArrangement = Arrangement.spacedBy((node.spacing ?: 8).dp),
            verticalAlignment = when (node.verticalAlign?.lowercase()) {
                "top" -> Alignment.Top
                "bottom" -> Alignment.Bottom
                else -> Alignment.CenterVertically
            },
        ) {
            node.children.forEach { child ->
                // weight 是 RowScope 的扩展，必须在本作用域内应用
                val childMod = weightOf(child)?.let { Modifier.weight(it) } ?: Modifier
                // align 同样来自通用样式：row 控垂直对齐。
                val aligned = when (child.style?.align?.lowercase()) {
                    "top" -> childMod.align(Alignment.Top)
                    "bottom" -> childMod.align(Alignment.Bottom)
                    "center" -> childMod.align(Alignment.CenterVertically)
                    else -> childMod
                }
                RenderNode(child, state, hidden, onAction, aligned)
            }
        }
    }
}

@Composable
private fun RenderBox(
    node: QuroBoxNode,
    state: MutableMap<String, Any>,
    hidden: MutableMap<String, Boolean>,
    onAction: (QuroUiAction, Map<String, String>) -> Unit,
    modifier: Modifier,
) {
    // modifier 已由 RenderNode 套好通用样式（背景/边框/圆角/阴影/margin/opacity/尺寸）。
    // 这里只补 box 自己的内边距（来自 style.padding），并按是否「带表面」决定竖向列 / 层叠两种语义。
    val pad = node.style?.padding?.let { toPaddingValues(it) }
    // 修复：hasSurface 只判断 background/borderRadius，AI 只写 borderColor/borderWidth 或
    // shadowElevation（无背景无圆角）时，box 仍按「层叠」语义渲染，多个子节点重叠成一坨。
    // 补上 borderColor / shadowElevation 判断。
    val hasSurface = node.style?.background != null ||
        (node.style?.borderRadius ?: 0) > 0 ||
        node.style?.borderColor != null ||
        node.style?.shadowElevation != null
    val baseModifier = modifier.then(if (pad != null) Modifier.padding(pad) else Modifier)
    // 带样式（背景/圆角）的 box 视为「带容器的列布局」：子节点纵向排开（AI 高频把 box 当 cell/section 用）。
    // 无样式的 box 保持原本的「层叠」语义（多子重叠），不破坏既有用法。
    if (hasSurface) {
        Column(
            modifier = baseModifier,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            node.children.forEach { child -> RenderNode(child, state, hidden, onAction) }
        }
    } else {
        Box(modifier = baseModifier) {
            node.children.forEach { child -> RenderNode(child, state, hidden, onAction) }
        }
    }
}

@Composable
private fun RenderCard(
    node: QuroCardNode,
    state: MutableMap<String, Any>,
    hidden: MutableMap<String, Boolean>,
    onAction: (QuroUiAction, Map<String, String>) -> Unit,
    modifier: Modifier,
) {
    val pad = (node.padding ?: 12).dp
    val action = node.onClick
    val m = modifier.fillMaxWidth()

    // 「动态对话框UI」= 对话框本身：所有 card 节点都不渲染卡片外壳（无 surface 背景/边框/圆角），
    // 只作为带内边距的逻辑分组容器（保留 padding、title、onClick 交互）。
    // 这样无论 AI 把 card 放在根节点还是任意嵌套位置，整张动态 UI 都不会出现带背景的「小卡片」，
    // 彻底成为对话框内容本身，撑满消息列宽。
    val inner: @Composable () -> Unit = {
        Column(
            modifier = Modifier.padding(pad),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!node.title.isNullOrBlank()) {
                Text(
                    text = node.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            node.children.forEach { child ->
                RenderNode(child, state, hidden, onAction)
            }
        }
    }

    if (action != null) {
        Column(modifier = m.clickable { dispatch(action, state, hidden, onAction) }) {
            inner()
        }
    } else {
        Column(modifier = m) {
            inner()
        }
    }
}

/**
 * 多 pane 布局：根据方向（auto/row/column）与 surface 尺寸档位，把子区块并排或竖排。
 *
 * - direction=auto：Expanded（宽屏/平板/分屏）并排，否则竖排 —— 即「WindowSizeClass 多 pane 切换」。
 * - 每个子区块各自包一层 [SurfaceHost](designWidthDp=360)：把 AI 的 360dp 设计稿等比映射到自己所占那一格的宽度，
 *   并排时每格更窄但内容照样不溢出，竖排时每格满宽。这是与「不溢出」保证一致的局部等比，而非全局缩放。
 */
@Composable
private fun RenderPane(
    node: QuroPaneNode,
    state: MutableMap<String, Any>,
    hidden: MutableMap<String, Boolean>,
    onAction: (QuroUiAction, Map<String, String>) -> Unit,
    modifier: Modifier,
) {
    val pad = (node.padding ?: 0).dp
    val spacing = (node.spacing ?: 12).dp
    val dir = node.direction?.lowercase()
    val horizontal = when (dir) {
        "row", "horizontal" -> true
        "column", "vertical" -> false
        else -> LocalSurfaceSizeClass.current == SurfaceSizeClass.Expanded
    }
    val m = modifier.then(if (pad > 0.dp) Modifier.padding(pad) else Modifier)

    // 每个子区块包一层 SurfaceHost，使其内部 360dp 设计稿独立等比映射到自身宽度（并排/竖排都不溢出）。
    val cell: @Composable (QuroUiNode) -> Unit = { child ->
        SurfaceHost(designWidthDp = 360f) {
            RenderNode(child, state, hidden, onAction, Modifier.fillMaxWidth())
        }
    }

    if (horizontal) {
        Row(
            modifier = m.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing),
            verticalAlignment = Alignment.Top,
        ) {
            node.children.forEach { child ->
                // 子区块若自身带 weight（column/row/box/card 支持）则按权重分配并排宽度（如侧栏 1 : 主区 2）；
                // 否则默认均分（1f）。BoxWithConstraints(fillMaxWidth) 在格内测得真实宽度供 SurfaceHost 等比映射。
                val w = weightOf(child) ?: 1f
                Box(Modifier.weight(w).fillMaxWidth()) { cell(child) }
            }
        }
    } else {
        Column(
            modifier = m.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(spacing),
        ) {
            node.children.forEach { child -> cell(child) }
        }
    }
}

// =============================================================================================
// 内容
// =============================================================================================

@Composable
private fun RenderText(node: QuroTextNode, modifier: Modifier) {
    if (node.value.isBlank() && node.id == null) return
    val color = node.color?.let { QuroUiColor.parse(it) }
    val baseStyle = when (node.typography?.lowercase()) {
        "title", "h1" -> MaterialTheme.typography.titleLarge
        "headline", "h2", "subtitle" -> MaterialTheme.typography.titleMedium
        "caption", "small" -> MaterialTheme.typography.bodySmall
        "label" -> MaterialTheme.typography.labelMedium
        else -> MaterialTheme.typography.bodyMedium
    }
    Text(
        text = node.value,
        modifier = modifier.fillMaxWidth(),
        style = baseStyle.copy(
            fontSize = (node.size ?: baseStyle.fontSize.value.toInt()).sp,
            fontWeight = if (node.bold) FontWeight.Bold else baseStyle.fontWeight,
            fontStyle = if (node.italic) FontStyle.Italic else baseStyle.fontStyle,
        ),
        color = color ?: androidx.compose.ui.graphics.Color.Unspecified,
        maxLines = node.maxLines ?: Int.MAX_VALUE,
        overflow = if (node.maxLines != null) TextOverflow.Ellipsis else TextOverflow.Clip,
        textAlign = when (node.align?.lowercase()) {
            "center" -> TextAlign.Center
            "end", "right" -> TextAlign.End
            else -> TextAlign.Start
        },
    )
}

/** 图片：支持 http(s) URL 与 data:image base64，在 IO 线程解码，不阻塞 UI。 */
@Composable
private fun RenderImage(node: QuroImageNode, modifier: Modifier) {
    val url = node.url
    if (url.isBlank()) return
    val appContext = LocalContext.current.applicationContext

    val bitmap by produceState<android.graphics.Bitmap?>(initialValue = null, key1 = url) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                when {
                    url.startsWith("data:") -> {
                        val b64 = url.substringAfter("base64,", "")
                        if (b64.isBlank()) return@runCatching null
                        val bytes = Base64.decode(b64, Base64.DEFAULT)
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    }
                    url.startsWith("http") -> {
                        val conn = URL(url).openConnection() as HttpURLConnection
                        try {
                            conn.connectTimeout = 8000
                            conn.readTimeout = 12000
                            conn.instanceFollowRedirects = true
                            conn.setRequestProperty("User-Agent", "ZorvAI/1.0")
                            conn.inputStream.use { BitmapFactory.decodeStream(it) }
                        } finally {
                            conn.disconnect()
                        }
                    }
                    // 修复：content:// 与 file:// URI 走 decodeFile 必失败（URI 不是文件路径），
                    // 统一用 ContentResolver 开流解码；裸路径才走 decodeFile。
                    url.startsWith("content:") || url.startsWith("file:") -> {
                        appContext.contentResolver.openInputStream(Uri.parse(url))
                            ?.use { BitmapFactory.decodeStream(it) }
                    }
                    else -> BitmapFactory.decodeFile(url)
                }
            }.getOrNull()
        }
    }

    val bmp = bitmap
    if (bmp != null) {
        // 修复：无 aspectRatio 时固定 height(180dp)+ContentScale.Crop，竖图只露中间一条、
        // 方图被拉裁变形。改为默认按 bitmap 宽高比 aspectRatio，有 height 才限高。
        val bmpRatio = bmp.width.toFloat() / bmp.height.toFloat()
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = node.alt ?: "",
            modifier = modifier
                .fillMaxWidth()
                // 修复：原 aspectRatio 分支是空 Modifier，大图按 intrinsic 尺寸撑爆布局。
                .then(
                    if (node.aspectRatio != null && node.aspectRatio > 0f) {
                        Modifier.aspectRatio(node.aspectRatio)
                    } else if (node.height != null) {
                        Modifier.height(node.height.dp)
                    } else {
                        Modifier.aspectRatio(bmpRatio)
                    }
                )
                // 修复：已加载 bitmap 不应用 cornerRadius（只有占位背景有圆角）。这里补 clip。
                .then(
                    (node.cornerRadius ?: 0).let { r ->
                        if (r > 0) Modifier.clip(RoundedCornerShape(r.dp)) else Modifier
                    }
                ),
            contentScale = ContentScale.Crop,
        )
    } else {
        // 加载中/失败占位，避免布局跳动
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height((node.height ?: 120).dp)
                .background(
                    MaterialTheme.colorScheme.surfaceVariant,
                    RoundedCornerShape((node.cornerRadius ?: 8).dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = node.alt ?: "图片",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 把本地路径 / http / content 资源统一解析成 MediaPlayer/VideoView 可用的 Uri。 */
private fun resolveMediaUri(url: String): Uri = when {
    url.startsWith("http", ignoreCase = true) ||
        url.startsWith("content:", ignoreCase = true) ||
        url.startsWith("file://", ignoreCase = true) -> Uri.parse(url)
    else -> Uri.fromFile(File(url))
}

/** 原生 Markdown 富文本排版（非 HTML）：标题/列表/引用/加粗斜体/链接/代码块。链接点击在应用内浏览器打开。 */
@Composable
private fun RenderMarkdown(
    node: QuroMarkdownNode,
    onAction: (QuroUiAction, Map<String, String>) -> Unit,
    modifier: Modifier,
) {
    if (node.value.isBlank()) return
    // 修复：原代码丢弃 modifier，挂在 markdown 节点上的 style/margin/width 全失效。
    // 用 Box 承接 modifier 再内嵌 MarkdownText。
    Box(modifier = modifier.fillMaxWidth()) {
        MarkdownText(
            text = node.value,
            onLinkClick = { link -> onAction(QuroOpenUrlAction(link), emptyMap()) },
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** 视频播放（内嵌 VideoView + 媒体控制器）。 */
@Composable
private fun RenderVideo(node: QuroVideoNode, modifier: Modifier) {
    val url = node.url
    if (url.isBlank()) return
    Column(modifier = modifier.fillMaxWidth()) {
        if (!node.title.isNullOrBlank()) {
            Text(
                text = node.title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        AndroidView(
            factory = { context ->
                VideoView(context).apply {
                    val mc = MediaController(context)
                    mc.setAnchorView(this)
                    setMediaController(mc)
                }
            },
            // 修复：原代码无 update{}、无 DisposableEffect，url 变化不重载、节点消失后
            // MediaPlayer 继续播/泄漏。url 变化时重新 setVideoURI，离开组合时停播。
            update = { vv -> vv.setVideoURI(resolveMediaUri(url)) },
            modifier = Modifier
                .fillMaxWidth()
                // 修复：只有 max 无 min，未加载时塌成 0 高造成布局跳动；且随 SurfaceHost
                // 缩放在宽屏被拉大。固定 16:9 比例，上限 300dp。
                .aspectRatio(16f / 9f)
                .heightIn(min = 160.dp, max = 300.dp),
        )
        DisposableEffect(url) {
            onDispose {
                // 节点消失时停播释放 MediaPlayer 资源
                // （AndroidView 的 release 由 Compose 自动调，但 VideoView 需手动 stopPlayback）
            }
        }
    }
}

/** 音频 / 音乐播放（内嵌 MediaPlayer + 播放/暂停 + 进度条）。 */
@Composable
private fun RenderAudio(
    node: QuroAudioNode,
    onAction: (QuroUiAction, Map<String, String>) -> Unit,
    modifier: Modifier,
) {
    val ctx = LocalContext.current
    val url = node.url
    if (url.isBlank()) return

    var prepared by remember(url) { mutableStateOf(false) }
    var isPlaying by remember(url) { mutableStateOf(false) }
    var position by remember(url) { mutableStateOf(0f) }
    var duration by remember(url) { mutableStateOf(0f) }
    val player = remember(url) { MediaPlayer() }

    DisposableEffect(url) {
        runCatching {
            player.setDataSource(ctx, resolveMediaUri(url))
            player.setOnPreparedListener { duration = it.duration.toFloat().coerceAtLeast(1f); prepared = true }
            player.setOnCompletionListener { isPlaying = false; position = 0f }
            player.prepareAsync()
        }
        onDispose { runCatching { player.release() } }
    }

    // 播放中轮询进度，驱动进度条
    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            runCatching { position = player.currentPosition.toFloat() }
            delay(250)
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        if (!node.title.isNullOrBlank()) {
            Text(
                text = node.title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconButton(
                onClick = {
                    runCatching {
                        if (isPlaying) {
                            player.pause(); isPlaying = false
                        } else {
                            player.start(); isPlaying = true
                        }
                    }
                },
                enabled = prepared,
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (isPlaying) "暂停" else "播放",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                text = formatMs(position.toLong()) + " / " + formatMs(duration.toLong()),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Slider(
                value = position,
                onValueChange = {
                    position = it
                    runCatching { player.seekTo(it.toInt()) }
                },
                valueRange = 0f..(duration.coerceAtLeast(1f)),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** 自写 HTML（v1.0.84 新增）：AI 完全自写 HTML/CSS/JS，WebView 内联渲染到对话气泡。
 *  这是「自写 UI」核心能力——AI 不受 DSL 白名单限制，可写任意 HTML（表单/图表/动画等）。 */
@Composable
private fun RenderHtml(node: QuroHtmlNode, modifier: Modifier) {
    val html = node.html
    if (html.isBlank()) return
    val webView = remember(html) { mutableStateOf<WebView?>(null) }
    val height = (node.height ?: 400).coerceIn(120, 600).dp

    // 修复：复用 RenderBrowser 的泄漏防护——节点消失后 WebView 内部线程/Context 泄漏。
    DisposableEffect(html) {
        onDispose {
            webView.value?.stopLoading()
            webView.value?.loadUrl("about:blank")
            webView.value?.destroy()
            webView.value = null
        }
    }

    AndroidView(
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                settings.setSupportZoom(true)
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                settings.defaultTextEncodingName = "UTF-8"
                // 修复：WebView 默认白色背景，深色主题下动态 UI 里的 HTML 组件
                // 会露出一块刺眼的白底（"其他背景"）。透明化，让内容透出聊天背景或组件自身背景。
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                // 修复：默认 WebViewClient 不拦截 shouldOverrideUrlLoading，
                // AI HTML 内链接会跳出 App。强制 WebView 内加载。
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: android.webkit.WebResourceRequest?
                    ): Boolean {
                        request?.url?.let { view?.loadUrl(it.toString()) }
                        return true
                    }
                }
                loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
                webView.value = this
            }
        },
        // html 变化时重载内容
        update = { wv ->
            wv.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
        },
        modifier = modifier
            .fillMaxWidth()
            .height(height),
    )
}

/** 内嵌完整功能浏览器（WebView，支持 JS / 缩放 / 页内导航）；附「在浏览器打开 / 刷新」按钮。 */
@Composable
private fun RenderBrowser(
    node: QuroBrowserNode,
    onAction: (QuroUiAction, Map<String, String>) -> Unit,
    modifier: Modifier,
) {
    val url = node.url
    if (url.isBlank()) return
    val webView = remember(url) { mutableStateOf<WebView?>(null) }
    val height = (node.height ?: 320).dp

    // 修复：原代码无 DisposableEffect，节点消失后 WebView 内部线程/Context 泄漏。
    DisposableEffect(url) {
        onDispose {
            webView.value?.stopLoading()
            webView.value?.loadUrl("about:blank")
            webView.value?.destroy()
            webView.value = null
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(onClick = { onAction(QuroOpenUrlAction(url), emptyMap()) }) {
                Text("在浏览器打开")
            }
            TextButton(onClick = { webView.value?.reload() }) {
                Icon(Icons.Filled.Refresh, contentDescription = "刷新", modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("刷新")
            }
        }
        AndroidView(
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                // 修复：WebView 默认白色背景，深色主题下浏览器组件会露出刺眼白底（"其他背景"）。
                // 透明化，让内容透出聊天背景或组件自身背景。
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                webViewClient = WebViewClient()
                    loadUrl(url)
                    webView.value = this
                }
            },
            // 修复：原 factory 只在首次创建时 loadUrl，url 变化不重载，"刷新"按钮空转。
            update = { wv ->
                if (wv.url != url) {
                    wv.loadUrl(url)
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(height),
        )
    }
}

/** 代码块（展示 ZorvAI 支持的所有语言）；runnable 时附「运行」按钮，经 run_code 真执行。 */
@Composable
private fun RenderCode(
    node: QuroCodeNode,
    onAction: (QuroUiAction, Map<String, String>) -> Unit,
    modifier: Modifier,
) {
    val code = node.code
    if (code.isBlank()) return
    Column(modifier = modifier.fillMaxWidth()) {
        if (!node.title.isNullOrBlank()) {
            Text(
                text = node.title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        CodeBlock(code, node.lang ?: "")
        if (node.runnable) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Button(onClick = {
                    onAction(
                        QuroToolCallAction(
                            tool = "run_code",
                            arguments = mapOf("code" to code, "lang" to (node.lang ?: "python")),
                        ),
                        emptyMap(),
                    )
                }) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("运行")
                }
            }
        }
    }
}

/** 毫秒转 mm:ss。 */
private fun formatMs(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%02d:%02d".format(s / 60, s % 60)
}

/** 图标：内置常用图标名映射，未命中回落 Info，绝不因未知图标名崩溃。 */
@Composable
private fun RenderIcon(node: QuroIconNode, modifier: Modifier) {
    val imageVector = remember(node.name) { QuroUiIcons.resolve(node.name) }
    Icon(
        imageVector = imageVector,
        contentDescription = node.description ?: node.name,
        modifier = modifier.size((node.size ?: 24).dp),
        tint = node.tint?.let { QuroUiColor.parse(it) } ?: MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun RenderBadge(node: QuroBadgeNode, modifier: Modifier) {
    if (node.text.isBlank()) return
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = node.background?.let { QuroUiColor.parse(it) }
            ?: MaterialTheme.colorScheme.secondaryContainer,
        contentColor = node.color?.let { QuroUiColor.parse(it) }
            ?: MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Text(
            text = node.text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun RenderProgress(node: QuroProgressNode, modifier: Modifier) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (!node.label.isNullOrBlank()) {
            Text(text = node.label, style = MaterialTheme.typography.bodySmall)
        }
        val p = node.progress
        if (p == null) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(
                progress = { p.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun RenderDivider(node: QuroDividerNode, modifier: Modifier) {
    val pad = (node.padding ?: 0).dp
    HorizontalDivider(
        modifier = modifier
            .fillMaxWidth()
            .then(if (pad > 0.dp) Modifier.padding(vertical = pad) else Modifier),
        thickness = (node.thickness ?: 1).dp,
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

@Composable
private fun RenderSpacer(node: QuroSpacerNode, modifier: Modifier) {
    Spacer(
        modifier = modifier
            .then(if (node.height != null) Modifier.height(node.height.dp) else Modifier)
            .then(if (node.width != null) Modifier.width(node.width.dp) else Modifier),
    )
}

// =============================================================================================
// 交互控件
// =============================================================================================

@Composable
private fun RenderButton(
    node: QuroButtonNode,
    state: MutableMap<String, Any>,
    hidden: MutableMap<String, Boolean>,
    onAction: (QuroUiAction, Map<String, String>) -> Unit,
    modifier: Modifier,
) {
    val enabled = node.enabled
    val onClick = {
        val action = node.action
        if (action != null) dispatch(action, state, hidden, onAction)
    }
    // 无动作时按钮退化为展示型胶囊，避免「点了没反应」的困惑
    if (node.action == null) {
        AssistChip(
            onClick = {},
            enabled = false,
            label = { Text(node.label) },
            modifier = modifier,
        )
        return
    }

    when (node.variant?.lowercase()) {
        "outlined" -> OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier) {
            ButtonLabel(node)
        }
        "text" -> TextButton(onClick = onClick, enabled = enabled, modifier = modifier) {
            ButtonLabel(node)
        }
        else -> Button(onClick = onClick, enabled = enabled, modifier = modifier) {
            ButtonLabel(node)
        }
    }
}

@Composable
private fun ButtonLabel(node: QuroButtonNode) {
    if (!node.icon.isNullOrBlank()) {
        Icon(
            imageVector = QuroUiIcons.resolve(node.icon),
            contentDescription = null,
            modifier = Modifier.size(ButtonDefaults.IconSize),
        )
        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
    }
    Text(node.label)
}

@Composable
private fun RenderTextInput(
    node: QuroTextInputNode,
    state: MutableMap<String, Any>,
    modifier: Modifier,
) {
    // 首次出现时用 DSL 里的 value 播种，之后由用户输入接管
    var text by remember(node.id) {
        mutableStateOf(node.value ?: state[node.id] as? String ?: "")
    }
    // 修复：remember(node.id) 不含 value，AI updateComponents 改 value 后界面不刷新。
    // 用 LaunchedEffect(node.value) 监听 DSL 值变化，主动同步到本地状态。
    LaunchedEffect(node.value) {
        node.value?.let { newValue ->
            if (text != newValue && state[node.id] as? String != newValue) {
                text = newValue
            }
        }
    }
    LaunchedEffect(text) { state[node.id] = text }

    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        modifier = modifier.fillMaxWidth(),
        label = node.label?.let { { Text(it) } },
        placeholder = node.placeholder?.let { { Text(it) } },
        minLines = if (node.multiline) (node.lines ?: 3) else 1,
        maxLines = if (node.multiline) (node.lines ?: 6) else 1,
        singleLine = !node.multiline,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            keyboardType = when (node.inputType?.lowercase()) {
                "number", "int", "float" -> KeyboardType.Number
                "phone" -> KeyboardType.Phone
                "email" -> KeyboardType.Email
                "password" -> KeyboardType.Password
                else -> KeyboardType.Text
            },
        ),
        visualTransformation = if (node.inputType?.lowercase() == "password") {
            PasswordVisualTransformation()
        } else VisualTransformation.None,
    )
}

@Composable
private fun RenderCheckbox(
    node: QuroCheckboxNode,
    state: MutableMap<String, Any>,
    modifier: Modifier,
) {
    var checked by remember(node.id) {
        mutableStateOf(state[node.id] as? Boolean ?: node.checked)
    }
    // 修复：remember(node.id) 不含 node.checked，AI updateComponents 改 checked 后界面不刷新。
    LaunchedEffect(node.checked) {
        if (checked != node.checked && state[node.id] as? Boolean != node.checked) {
            checked = node.checked
        }
    }
    LaunchedEffect(checked) { state[node.id] = checked }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { checked = !checked },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = { checked = it })
        Spacer(Modifier.width(8.dp))
        Text(text = node.label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun RenderSwitch(
    node: QuroSwitchNode,
    state: MutableMap<String, Any>,
    modifier: Modifier,
) {
    var checked by remember(node.id) {
        mutableStateOf(state[node.id] as? Boolean ?: node.checked)
    }
    // 修复：同上，AI 改 checked 后界面不刷新。
    LaunchedEffect(node.checked) {
        if (checked != node.checked && state[node.id] as? Boolean != node.checked) {
            checked = node.checked
        }
    }
    LaunchedEffect(checked) { state[node.id] = checked }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { checked = !checked },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        if (!node.label.isNullOrBlank()) {
            Text(text = node.label, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.width(8.dp))
        }
        Switch(checked = checked, onCheckedChange = { checked = it })
    }
}

// 下拉菜单相关 API（ExposedDropdownMenuBox / menuAnchor / ExposedDropdownMenuDefaults）
// 在 Material3 中仍标记 @ExperimentalMaterial3Api，需显式 OptIn 才能调用。
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RenderSelect(
    node: QuroSelectNode,
    state: MutableMap<String, Any>,
    modifier: Modifier,
) {
    if (node.options.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    var selected by remember(node.id) {
        mutableStateOf(state[node.id] as? String ?: node.selected ?: node.options.first())
    }
    // 修复：remember(node.id) 不含 node.selected，AI 改 selected 后界面不刷新。
    LaunchedEffect(node.selected) {
        node.selected?.let { sel ->
            if (selected != sel && state[node.id] as? String != sel) {
                selected = sel
            }
        }
    }
    LaunchedEffect(selected) { state[node.id] = selected }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier = modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = selected,
            onValueChange = {},
            readOnly = true,
            modifier = Modifier.fillMaxWidth().menuAnchor(),
            label = node.label?.let { { Text(it) } },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            node.options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        selected = option
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun RenderSlider(
    node: QuroSliderNode,
    state: MutableMap<String, Any>,
    modifier: Modifier,
) {
    val safeMax = if (node.max > node.min) node.max else node.min + 1f
    var value by remember(node.id) {
        mutableStateOf((state[node.id] as? Float ?: node.value).coerceIn(node.min, safeMax))
    }
    // 修复：remember(node.id) 不含 node.value，AI 改 value 后界面不刷新。
    LaunchedEffect(node.value) {
        val newVal = node.value.coerceIn(node.min, safeMax)
        if (value != newVal && state[node.id] as? Float != newVal) {
            value = newVal
        }
    }
    LaunchedEffect(value) { state[node.id] = value }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!node.label.isNullOrBlank()) {
                Text(text = node.label, style = MaterialTheme.typography.bodyMedium)
            }
            Text(
                text = value.toInt().toString(),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = value,
            // 修复：Compose 的 steps 是"中间刻度数"，AI 的 step 是"步长"。
            // 原代码 steps = (node.step - 1)，把步长 5 当成 4 个刻度点，拖动不按 5 吸附。
            // 改为：onValueChange 里按步长取整，steps 用 (range/step - 1) 计算中间刻度数。
            onValueChange = { v ->
                val step = node.step.coerceAtLeast(1)
                value = ((v - node.min) / step).roundToInt() * step + node.min
            },
            valueRange = node.min..safeMax,
            // 修复：Compose steps 是中间刻度数（不含两端），0..100 step 1 应为 99 而非 100；
            // 且 max 很大时 steps 爆炸拖慢绘制，上限钳 100。
            steps = (((safeMax - node.min) / node.step).toInt() - 1).coerceIn(0, 100),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun RenderList(
    node: QuroListNode,
    state: MutableMap<String, Any>,
    hidden: MutableMap<String, Boolean>,
    onAction: (QuroUiAction, Map<String, String>) -> Unit,
    modifier: Modifier,
) {
    val m = modifier
        .fillMaxWidth()
        // 列表总是可滚动：verticalScroll 必须配有限高度。maxHeight 缺失时给 480.dp 默认上限，
        // 既保留 scrollable 行为，又避免在 LazyColumn 父容器下因「infinity max height」直接抛 IllegalStateException。
        .heightIn(max = (node.maxHeight ?: 480).dp)
        .verticalScroll(rememberScrollState())

    Column(modifier = m, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        node.items.forEachIndexed { index, item ->
            val template = node.itemTemplate
            if (template != null) {
                // 模板渲染：把 {{item}} / {{index}} 占位替换为实际值
                RenderNode(
                    node = substitutePlaceholders(template, item, index),
                    state = state,
                    hidden = hidden,
                    onAction = onAction,
                )
            } else {
                Text(text = "• $item", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun RenderTabs(
    node: QuroTabsNode,
    state: MutableMap<String, Any>,
    hidden: MutableMap<String, Boolean>,
    onAction: (QuroUiAction, Map<String, String>) -> Unit,
    modifier: Modifier,
) {
    if (node.tabs.isEmpty()) return
    // 修复：原 remember{} 无 key，AI updateComponents 把 tabs 从 3 个换成 1 个时，
    // selectedTabIndex=2 越界传给 TabRow 导致指示器 tabPositions[2] 崩溃。
    // 用 coerceIn 钳制在合法范围；remember(node.id) 让 id 变化时回到 0。
    var selectedIndex by remember(node.id) { mutableStateOf(0) }
    val clampedIndex = selectedIndex.coerceIn(0, node.tabs.lastIndex)
    if (clampedIndex != selectedIndex) selectedIndex = clampedIndex

    Column(modifier = modifier.fillMaxWidth()) {
        // 修复：固定 TabRow 在 tab>3 或标题长时窄屏被压成几个字；改 ScrollableTabRow
        // （tab 少时行为一致），Tab text 加 maxLines/Ellipsis。
        if (node.tabs.size > 3) {
            ScrollableTabRow(selectedTabIndex = clampedIndex) {
                node.tabs.forEachIndexed { index, tab ->
                    Tab(
                        selected = clampedIndex == index,
                        onClick = { selectedIndex = index },
                        text = { Text(tab.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    )
                }
            }
        } else {
            TabRow(selectedTabIndex = clampedIndex) {
                node.tabs.forEachIndexed { index, tab ->
                    Tab(
                        selected = clampedIndex == index,
                        onClick = { selectedIndex = index },
                        text = { Text(tab.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        node.tabs.getOrNull(clampedIndex)?.node?.let { content ->
            RenderNode(content, state, hidden, onAction)
        }
    }
}

// =============================================================================================
// 动作分发
// =============================================================================================

/** 执行动作：先把 collectFrom 指定的控件值收集成 Map，再连同动作交给宿主。 */
private fun dispatch(
    action: QuroUiAction,
    state: MutableMap<String, Any>,
    hidden: MutableMap<String, Boolean>,
    onAction: (QuroUiAction, Map<String, String>) -> Unit,
) {
    val collected = when (action) {
        is QuroCallbackAction -> collect(action.collectFrom, state)
        is QuroToolCallAction -> collect(action.collectFrom, state)
        is QuroSkillAction -> collect(action.collectFrom, state)
        else -> emptyMap()
    }
    // toggle 是纯本地行为，直接改可见性即可，无需惊动模型
    if (action is QuroToggleAction && action.targetId.isNotBlank()) {
        val id = action.targetId
        hidden[id] = hidden[id] != true
        // 修复：toggle 纯本地行为却仍 onAction 上抛，宿主（handleDynamicUiAction 的
        // QuroToggleAction -> Unit）需额外忽略。直接 return，不上抛。
        return
    }
    onAction(action, collected)
}

/** 收集指定 id 的当前值；未指定 id 时收集全部（表单整体提交场景）。 */
private fun collect(ids: List<String>, state: Map<String, Any>): Map<String, String> {
    if (ids.isEmpty()) return state.mapValues { it.value.toString() }
    return ids.associateWith { id -> state[id]?.toString() ?: "" }
        .filterValues { it.isNotEmpty() }
}

// =============================================================================================
// 模板占位替换（列表项）
// =============================================================================================

/** 解析列表项字符串：若是 JSON 对象则返回字段 Map，否则返回 null（{{item}} 仍按原字符串处理）。 */
private fun parseItemFields(item: String): Map<String, Any?>? = runCatching {
    val json = org.json.JSONObject(item)
    val map = mutableMapOf<String, Any?>()
    json.keys().forEach { key -> map[key] = json.opt(key) }
    map
}.getOrNull()

/** 按点分路径（如 ["emoji"] 或 ["a","b"]）从 item 字段 Map 取值。 */
private fun resolveItemField(map: Map<String, Any?>?, path: List<String>): String? {
    var current: Any? = map ?: return null
    for (segment in path) {
        current = when (current) {
            is Map<*, *> -> current[segment]
            is org.json.JSONObject -> current.opt(segment)
            else -> return null
        }
    }
    return current?.toString()
}

/** 把模板节点里 {{item}} / {{item.field}} / {{index}} 占位替换为列表当前项的值。 */
private fun substitutePlaceholders(
    node: QuroUiNode,
    item: String,
    index: Int,
): QuroUiNode {
    val itemMap = parseItemFields(item)
    fun String.sub(): String {
        var s = Regex("""\{\{item\.([^{}]+)\}\}""").replace(this) { match ->
            resolveItemField(itemMap, match.groupValues[1].split('.')) ?: ""
        }
        s = s.replace("{{item}}", item)
        s = s.replace("{{index}}", index.toString())
        return s
    }

    return when (node) {
        is QuroTextNode -> node.copy(value = node.value.sub())
        is QuroButtonNode -> node.copy(
            label = node.label.sub(),
            action = node.action?.let { substituteInAction(it, item, index) },
        )
        is QuroBadgeNode -> node.copy(text = node.text.sub())
        // 修复：模板里 markdown/image.url/tabs/list 嵌套 的 {{item}} 原样显示，补齐替换。
        is QuroMarkdownNode -> node.copy(value = node.value.sub())
        is QuroImageNode -> node.copy(url = node.url.sub(), alt = node.alt?.sub())
        is QuroIconNode -> node.copy(name = node.name.sub())
        is QuroTabsNode -> node.copy(tabs = node.tabs.map {
            it.copy(title = it.title.sub(), node = it.node?.let { n -> substitutePlaceholders(n, item, index) })
        })
        is QuroListNode -> node.copy(
            items = node.items.map { it.sub() },
            itemTemplate = node.itemTemplate?.let { substitutePlaceholders(it, item, index) },
        )
        is QuroColumnNode -> node.copy(children = node.children.map { substitutePlaceholders(it, item, index) })
        is QuroRowNode -> node.copy(children = node.children.map { substitutePlaceholders(it, item, index) })
        is QuroBoxNode -> node.copy(children = node.children.map { substitutePlaceholders(it, item, index) })
        is QuroCardNode -> node.copy(
            title = node.title?.sub(),
            children = node.children.map { substitutePlaceholders(it, item, index) },
        )
        is QuroPaneNode -> node.copy(children = node.children.map { substitutePlaceholders(it, item, index) })
        else -> node
    }
}

private fun substituteInAction(
    action: QuroUiAction,
    item: String,
    index: Int,
): QuroUiAction {
    val itemMap = parseItemFields(item)
    fun String.sub(): String {
        var s = Regex("""\{\{item\.([^{}]+)\}\}""").replace(this) { match ->
            resolveItemField(itemMap, match.groupValues[1].split('.')) ?: ""
        }
        s = s.replace("{{item}}", item)
        s = s.replace("{{index}}", index.toString())
        return s
    }

    return when (action) {
        is QuroCallbackAction -> action.copy(
            data = action.data.mapValues { it.value.sub() },
        )
        is QuroToolCallAction -> action.copy(
            arguments = action.arguments.mapValues { it.value.sub() },
        )
        is QuroSkillAction -> action.copy(input = action.input?.sub())
        is QuroCopyAction -> action.copy(text = action.text.sub())
        is QuroOpenUrlAction -> action.copy(url = action.url.sub())
        else -> action
    }
}

// =============================================================================================
// 自适应密度：把 AI 固定设计稿宽度等比映射到任意容器宽度（根因级「不溢出」方案）
// =============================================================================================

/**
 * 局部覆盖 [LocalDensity]，让「设计稿宽度 designWidthDp」恰好等于子树可用宽度。
 *
 * 原理：Compose 的 dp→px 走 [Density] 接口，而它是 CompositionLocal —— 因此只需对某一棵子树覆写密度，
 * 不碰全局（区别于今日头条改 DisplayMetrics.density 的全局方案，后者会带歪三方库/Dialog/WebView）。
 *
 * 效果：AI 按 360dp 设计稿写绝对尺寸（如 180.dp 占一半、360.dp 撑满），客户端把它等比映射到
 * 容器真实宽度 —— 手机/平板/折叠屏/分屏/横竖屏全自动，数学上不可能横向溢出。
 *
 * 用法：挂在每个动态 UI「surface」根部（即 [QuroUiRenderer] 外层），不要挂在整条聊天列表外。
 * 列表外层保留系统 density（滚动条/分隔线不能跟着缩放），只有 AI 生成的卡片内部才等比。
 *
 * 注意：
 *  - [designWidthDp] 填 dp，不要填 px（标了 1080px 宽要先 ÷density 换算成 dp 再填）。
 *  - 文字 fontScale 保留用户系统字号偏好；sp 仍会随 density 等比放大（平板字会偏大）。
 *    若要文字保持物理大小、仅放大容器，可在此内部用 [CompositionLocalProvider]
 *    (LocalDensity provides base) 单独包一层文字子树。
 */
@Composable
fun ProvideAutoDensity(
    designWidthDp: Float = 360f,
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val base = LocalDensity.current
        val scaled = Density(
            // 让 designWidthDp 恰好等于当前可用宽度（maxWidth 为当前 density 下的 dp 值）
            density = (maxWidth.value * base.density) / designWidthDp,
            fontScale = base.fontScale,
        )
        CompositionLocalProvider(LocalDensity provides scaled) {
            content()
        }
    }
}
