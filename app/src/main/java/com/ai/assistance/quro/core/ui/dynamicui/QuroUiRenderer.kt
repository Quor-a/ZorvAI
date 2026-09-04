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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
    val state = remember { mutableStateMapOf<String, Any>() }
    // 被 toggle 隐藏的节点 id 集合
    val hidden = remember { mutableStateMapOf<String, Boolean>() }

    RenderNode(
        node = root,
        state = state,
        hidden = hidden,
        onAction = onAction,
        modifier = modifier,
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
) {
    // toggle 隐藏：节点有 id 且被标记为隐藏则整体不渲染
    val nodeId = node.id
    if (nodeId != null && hidden[nodeId] == true) return

    when (node) {
        is QuroColumnNode -> RenderColumn(node, state, hidden, onAction, modifier)
        is QuroRowNode -> RenderRow(node, state, hidden, onAction, modifier)
        is QuroBoxNode -> RenderBox(node, state, hidden, onAction, modifier)
        is QuroPaneNode -> RenderPane(node, state, hidden, onAction, modifier)
        is QuroCardNode -> RenderCard(node, state, hidden, onAction, modifier)
        is QuroTextNode -> RenderText(node, modifier)
        is QuroImageNode -> RenderImage(node, modifier)
        is QuroMarkdownNode -> RenderMarkdown(node, onAction, modifier)
        is QuroVideoNode -> RenderVideo(node, modifier)
        is QuroAudioNode -> RenderAudio(node, onAction, modifier)
        is QuroBrowserNode -> RenderBrowser(node, onAction, modifier)
        is QuroCodeNode -> RenderCode(node, onAction, modifier)
        is QuroIconNode -> RenderIcon(node, modifier)
        is QuroBadgeNode -> RenderBadge(node, modifier)
        is QuroProgressNode -> RenderProgress(node, modifier)
        is QuroDividerNode -> RenderDivider(node, modifier)
        is QuroSpacerNode -> RenderSpacer(node, modifier)
        is QuroButtonNode -> RenderButton(node, state, hidden, onAction, modifier)
        is QuroTextInputNode -> RenderTextInput(node, state, modifier)
        is QuroCheckboxNode -> RenderCheckbox(node, state, modifier)
        is QuroSwitchNode -> RenderSwitch(node, state, modifier)
        is QuroSelectNode -> RenderSelect(node, state, modifier)
        is QuroSliderNode -> RenderSlider(node, state, modifier)
        is QuroListNode -> RenderList(node, state, hidden, onAction, modifier)
        is QuroTabsNode -> RenderTabs(node, state, hidden, onAction, modifier)
    }
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
) {
    val pad = (node.padding ?: 0).dp
    val m = modifier
        .then(if (pad > 0.dp) Modifier.padding(pad) else Modifier)
        .then(if (node.scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier)

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
            // weight 是 ColumnScope 的扩展，必须在本作用域内应用，
            // 不能提到 RenderNode 内部（那里没有 ColumnScope，会编译失败）。
            val childMod = weightOf(child)?.let { Modifier.weight(it) } ?: Modifier
            RenderNode(child, state, hidden, onAction, childMod)
        }
    }
}

/** 提取节点的 weight（用于 Row/Column 内按比例分配空间）。 */
private fun weightOf(node: QuroUiNode): Float? = when (node) {
    is QuroColumnNode -> node.weight
    is QuroRowNode -> node.weight
    is QuroBoxNode -> node.weight
    is QuroCardNode -> node.weight
    else -> null
}

@Composable
private fun RenderRow(
    node: QuroRowNode,
    state: MutableMap<String, Any>,
    hidden: MutableMap<String, Boolean>,
    onAction: (QuroUiAction, Map<String, String>) -> Unit,
    modifier: Modifier,
) {
    val pad = (node.padding ?: 0).dp
    val m = modifier
        .then(if (pad > 0.dp) Modifier.padding(pad) else Modifier)
        .then(if (node.scrollable) Modifier.horizontalScroll(rememberScrollState()) else Modifier)

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
            RenderNode(child, state, hidden, onAction, childMod)
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
    val pad = (node.padding ?: 0).dp
    Box(
        modifier = modifier
            .then(if (pad > 0.dp) Modifier.padding(pad) else Modifier),
    ) {
        node.children.forEach { child ->
            RenderNode(child, state, hidden, onAction)
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
                // weight(1f) 让并排格子均分宽度；BoxWithConstraints(fillMaxWidth) 在格内测得真实宽度供 SurfaceHost 映射。
                Box(Modifier.weight(1f).fillMaxWidth()) { cell(child) }
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
    val baseStyle = when (node.style?.lowercase()) {
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
                    else -> BitmapFactory.decodeFile(url)
                }
            }.getOrNull()
        }
    }

    val bmp = bitmap
    if (bmp != null) {
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = node.alt ?: "",
            modifier = modifier
                .fillMaxWidth()
                .then(
                    if (node.aspectRatio != null && node.aspectRatio > 0f) {
                        Modifier
                    } else Modifier.height((node.height ?: 180).dp)
                ),
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
    MarkdownText(
        text = node.value,
        onLinkClick = { link -> onAction(QuroOpenUrlAction(link), emptyMap()) },
        color = MaterialTheme.colorScheme.onSurface,
    )
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
                    setVideoURI(resolveMediaUri(url))
                    val mc = MediaController(context)
                    mc.setAnchorView(this)
                    setMediaController(mc)
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 300.dp),
        )
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
                    webViewClient = WebViewClient()
                    loadUrl(url)
                    webView.value = this
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
            onValueChange = { value = it },
            valueRange = node.min..safeMax,
            steps = (node.step - 1).coerceAtLeast(0),
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
        .then(if (node.maxHeight != null) Modifier.heightIn(max = node.maxHeight.dp) else Modifier)
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
    var selectedIndex by remember { mutableStateOf(0) }

    Column(modifier = modifier.fillMaxWidth()) {
        TabRow(selectedTabIndex = selectedIndex) {
            node.tabs.forEachIndexed { index, tab ->
                Tab(
                    selected = selectedIndex == index,
                    onClick = { selectedIndex = index },
                    text = { Text(tab.title) },
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        node.tabs.getOrNull(selectedIndex)?.node?.let { content ->
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

/** 把模板节点里 {{item}} / {{index}} 占位替换为列表当前项的值。 */
private fun substitutePlaceholders(
    node: QuroUiNode,
    item: String,
    index: Int,
): QuroUiNode {
    fun String.sub(): String = this
        .replace("{{item}}", item)
        .replace("{{index}}", index.toString())

    return when (node) {
        is QuroTextNode -> node.copy(value = node.value.sub())
        is QuroButtonNode -> node.copy(
            label = node.label.sub(),
            action = node.action?.let { substituteInAction(it, item, index) },
        )
        is QuroBadgeNode -> node.copy(text = node.text.sub())
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
    fun String.sub(): String = this
        .replace("{{item}}", item)
        .replace("{{index}}", index.toString())

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
