package com.ai.assistance.quro.ui.canvas

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ai.assistance.quro.core.canvas.Aip
import com.ai.assistance.quro.core.canvas.AipConvert
import com.ai.assistance.quro.core.canvas.CanvasRouter
import com.ai.assistance.quro.core.ui.dynamicui.SurfaceHost
import com.ai.assistance.quro.core.tools.AiwpsCreateTool
import com.ai.assistance.quro.ui.MarkdownText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * AIP Canvas 渲染引擎（B 通道）：一个 Block 类型 = 一个渲染分支（注册表模式的 when 实现），
 * 未知类型走 [AipCanvasBlock] 的 Fallback 富文本兜底，绝不空白、绝不泄漏 JSON 源码。
 *
 * 消息内容接入方式：```aip 围栏 或 前导 AIP 信封 JSON → [AipCanvas]；
 * 解析失败（L3/L4）→ 显示降级横幅 + [MarkdownText] 纯文本兜底。
 */
@Composable
fun AipCanvas(
    source: String,
    onLinkClick: (String) -> Unit = {},
) {
    val result = remember(source) { Aip.parse(source) }
    val cs = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    // 形态互转（PRD M3）：null = 跟随信封原始 kind；用户点过切换后固定
    var kindOverride by remember { mutableStateOf<String?>(null) }
    var presenting by remember { mutableStateOf(false) }
    var exportMenu by remember { mutableStateOf(false) }
    // 整页/全屏查看开关（"不满屏"修复）：长文档/PPT/报告整篇排版脱离对话框层、满屏阅读
    var fullscreen by remember { mutableStateOf(false) }

    when {
        result.envelope != null -> {
            val env0 = result.envelope!!
            val kind = kindOverride ?: env0.kind
            // 转换开销 O(blocks)，remember 缓存；kind 相同直接原样返回零成本
            val env = remember(env0, kind) { AipConvert.convert(env0, kind) }
            // [排版引擎修复] 移除外层 Surface 边框/底色（"有框限制"）：改为无框纯容器，
            // 由内部各 Block 自行控制样式，撑满父容器（全宽内联时即满对话框宽）。
            Column(
                Modifier.fillMaxWidth().padding(vertical = 4.dp, horizontal = 14.dp).animateContentSize(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                    // 文档头部区（标题 / 副标题 / 元信息）
                    if (env.title.isNotBlank()) {
                        Text(env.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    }
                    if (env.subtitle.isNotBlank()) {
                        Text(env.subtitle, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                    }
                    if (env.author.isNotBlank()) {
                        Text(env.author, fontSize = 11.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
                    }

                    // ── 工具栏：形态切换 / 演示 / 导出（PRD M3） ──
                    Row(
                        Modifier.fillMaxWidth().padding(top = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        listOf("doc" to "文档", "deck" to "幻灯", "mindmap" to "导图").forEach { (k, label) ->
                            FilterChip(
                                selected = kind == k,
                                onClick = { kindOverride = k },
                                label = { Text(label, fontSize = 11.sp) },
                                modifier = Modifier.height(28.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                        }
                        Spacer(Modifier.weight(1f))
                        if (kind == "deck") {
                            val slides = env.blocks.filterIsInstance<Aip.Block.Slide>()
                            if (slides.isNotEmpty()) {
                                IconButton(onClick = { presenting = true }, modifier = Modifier.size(30.dp)) {
                                    androidx.compose.material3.Icon(Icons.Filled.Slideshow, "演示模式", Modifier.size(18.dp), tint = cs.primary)
                                }
                            }
                        }
                        FilterChip(
                            selected = false,
                            onClick = { fullscreen = true },
                            label = { Text("全屏", fontSize = 11.sp) },
                            modifier = Modifier.height(28.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        IconButton(onClick = { copyAip(ctx, scope, env) }, modifier = Modifier.size(30.dp)) {
                            androidx.compose.material3.Icon(Icons.Filled.ContentCopy, "复制", Modifier.size(18.dp), tint = cs.onSurfaceVariant)
                        }
                        Spacer(Modifier.width(4.dp))
                        Box {
                            IconButton(onClick = { exportMenu = true }, modifier = Modifier.size(30.dp)) {
                                androidx.compose.material3.Icon(Icons.Filled.FileDownload, "导出", Modifier.size(18.dp), tint = cs.onSurfaceVariant)
                            }
                            DropdownMenu(expanded = exportMenu, onDismissRequest = { exportMenu = false }) {
                                DropdownMenuItem(text = { Text("导出 Word (.docx)") }, onClick = { exportMenu = false; exportAip(ctx, scope, env, "docx") })
                                DropdownMenuItem(text = { Text("导出 PPT (.pptx)") }, onClick = { exportMenu = false; exportAip(ctx, scope, env, "pptx") })
                                DropdownMenuItem(text = { Text("导出 Markdown (.md)") }, onClick = { exportMenu = false; exportAip(ctx, scope, env, "md") })
                            }
                        }
                    }

                    if (env.title.isNotBlank()) HorizontalDivider(Modifier.padding(vertical = 8.dp), color = cs.outlineVariant.copy(alpha = 0.4f))

                    // [自适应屏幕] 把排版内容区用 SurfaceHost(360f) 等比缩放包裹：
                    // 360dp 设计稿恰好等于容器可用宽度，手机/平板/分屏/横竖屏全自动铺满，数学上不可能横向溢出。
                    SurfaceHost(360f) {
                        when (kind) {
                            "deck" -> {
                                DeckPager(env)
                                if (presenting) {
                                    val slides = env.blocks.filterIsInstance<Aip.Block.Slide>()
                                    if (slides.isNotEmpty()) DeckPresentOverlay(slides, onDismiss = { presenting = false })
                                }
                            }
                            "mindmap" -> env.blocks.filterIsInstance<Aip.Block.Mindmap>().forEach { MindmapView(it) }
                            else -> env.blocks.forEach { AipCanvasBlock(it, onLinkClick) }
                        }
                    }
                }
                // 整页/全屏查看：长文档/PPT/报告整篇排版脱离对话框层，满屏阅读（"不满屏"修复）
                if (fullscreen) {
                    AipFullscreenSheet(env, kind, onLinkClick, onDismiss = { fullscreen = false })
                }
        }
        // L3 通道降级 / L4 纯文本兜底：横幅 + Markdown 渲染（永不空白气泡）
        else -> {
            Column {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = cs.errorContainer.copy(alpha = 0.5f),
                    modifier = Modifier.padding(vertical = 4.dp),
                ) {
                    Text(
                        if (result.degradation == Aip.Degradation.ChannelDown) "排版引擎已降级为 Markdown 显示"
                        else "排版失败，已按纯文本显示",
                        fontSize = 11.sp,
                        color = cs.onErrorContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
                MarkdownText(text = result.raw, onLinkClick = onLinkClick)
            }
        }
    }
}

/** 导出：AIP 信封 → AiwpsCreateTool（docx 走 Markdown 内容 / pptx 走 `---` 分页内容）。 */
private fun exportAip(ctx: Context, scope: CoroutineScope, env: Aip.Envelope, type: String) {    scope.launch(Dispatchers.IO) {
        val content = if (type == "pptx") AipConvert.toPptxText(env) else AipConvert.toMarkdown(env)
        val r = runCatching {
            AiwpsCreateTool().run(ctx, JSONObject().apply {
                put("type", type)
                put("title", env.title)
                put("content", content)
                put("filename", AipConvert.exportFileStem(env))
            }.toString())
        }.getOrElse { "导出失败：${it.message}" }
        withContext(Dispatchers.Main) { Toast.makeText(ctx, r, Toast.LENGTH_LONG).show() }
    }
}

/** 复制：将 AIP 排版内容转为 Markdown 写入系统剪贴板（"缺少复制"修复）。 */
private fun copyAip(ctx: Context, scope: CoroutineScope, env: Aip.Envelope) {
    scope.launch(Dispatchers.IO) {
        val md = AipConvert.toMarkdown(env)
        withContext(Dispatchers.Main) {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText(env.title.ifBlank { "AIP 排版内容" }, md))
            Toast.makeText(ctx, "已复制排版内容", Toast.LENGTH_SHORT).show()
        }
    }
}

/** 演示模式（PRD M4）：全屏黑底放映，右 2/3 点按下一页、左 1/3 上一页，✕ 退出。 */
@Composable
private fun DeckPresentOverlay(slides: List<Aip.Block.Slide>, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        var page by remember { mutableStateOf(0) }
        val p = page.coerceIn(0, slides.lastIndex)
        Box(Modifier.fillMaxSize().background(Color(0xFF0A0A0F)).systemBarsPadding()) {
            Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                SlideCard(slides[p])
            }
            // 点按区：左 1/3 上一页，右 2/3 下一页
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxHeight().weight(1f).clickable { if (p > 0) page = p - 1 })
                Box(Modifier.fillMaxHeight().weight(2f).clickable { if (p < slides.lastIndex) page = p + 1 })
            }
            IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                androidx.compose.material3.Icon(Icons.Filled.Close, "退出", tint = Color.White)
            }
            Text(
                "${p + 1} / ${slides.size}",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 12.sp,
                modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
            )
        }
    }
}

/* ===================== 整页/全屏查看（"不满屏"修复） ===================== */

/** 整页/全屏查看：长文档 / PPT / 报告整篇排版脱离对话框层，满屏渲染、可滚动、无框。 */
@Composable
private fun AipFullscreenSheet(
    env: Aip.Envelope,
    kind: String,
    onLinkClick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(cs.background).systemBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    env.title.ifBlank { "排版预览" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss) {
                    androidx.compose.material3.Icon(Icons.Filled.Close, "退出", tint = cs.onSurface)
                }
            }
            HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.4f))
            when (kind) {
                "deck" -> {
                    val slides = env.blocks.filterIsInstance<Aip.Block.Slide>()
                    if (slides.isNotEmpty()) {
                        // [自适应屏幕] 整篇 PPT 满屏等比缩放：360dp 设计稿映射为全屏宽度，逐页 16:9 满宽、可滚动
                        SurfaceHost(360f) {
                            Column(
                                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                slides.forEach { SlideCard(it) }
                            }
                        }
                    } else {
                        AipEnvelopeScroll(env, kind, onLinkClick)
                    }
                }
                else -> AipEnvelopeScroll(env, kind, onLinkClick)
            }
        }
    }
}

/** 全屏滚动内容（doc / mindmap / 退化 deck）：标题 + 分节 + 各 Block 原生富排版，可纵向滚动。 */
@Composable
private fun AipEnvelopeScroll(env: Aip.Envelope, kind: String, onLinkClick: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        if (env.title.isNotBlank()) {
            Text(env.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }
        if (env.subtitle.isNotBlank()) {
            Text(env.subtitle, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        }
        if (env.author.isNotBlank()) {
            Text(env.author, fontSize = 11.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
        }
        Spacer(Modifier.height(10.dp))
        HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.4f))
        Spacer(Modifier.height(10.dp))
        // [自适应屏幕] 满屏内容等比缩放：360dp 设计稿映射为全屏宽度
        SurfaceHost(360f) {
            when (kind) {
                "mindmap" -> env.blocks.filterIsInstance<Aip.Block.Mindmap>().forEach { MindmapView(it) }
                else -> env.blocks.forEach { AipCanvasBlock(it, onLinkClick) }
            }
        }
    }
}

/* ===================== 通用 Block 渲染 ===================== */

@Composable
fun AipCanvasBlock(b: Aip.Block, onLinkClick: (String) -> Unit = {}) {
    val cs = MaterialTheme.colorScheme
    when (b) {
        is Aip.Block.Heading -> when (b.level.coerceAtMost(4)) {
            1 -> Text(b.text, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 4.dp))
            2 -> Text(b.text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 3.dp))
            3 -> Text(b.text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(vertical = 2.dp))
            else -> Text(b.text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        }
        is Aip.Block.Section -> Column(Modifier.padding(vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(width = 4.dp, height = 18.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(cs.primary)
                )
                Spacer(Modifier.width(8.dp))
                Text(b.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            HorizontalDivider(Modifier.padding(top = 4.dp), color = cs.outlineVariant.copy(alpha = 0.4f))
        }
        is Aip.Block.Paragraph -> MarkdownText(text = b.text, onLinkClick = onLinkClick)
        is Aip.Block.ListBlock -> Column(Modifier.padding(vertical = 2.dp)) {
            b.items.forEachIndexed { i, item ->
                Row {
                    Text(if (b.ordered) "${i + 1}. " else "•  ", color = cs.primary, fontWeight = FontWeight.SemiBold)
                    Box(Modifier.weight(1f)) { MarkdownText(text = item, onLinkClick = onLinkClick) }
                }
            }
        }
        is Aip.Block.Table -> AipTable(b)
        is Aip.Block.Code -> Column(
            Modifier.fillMaxWidth().padding(vertical = 4.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF1E1E1E))
                .padding(10.dp)
        ) {
            if (b.lang.isNotBlank()) {
                Text(b.lang, fontSize = 10.sp, color = Color(0xFF9CDCFE), modifier = Modifier.padding(bottom = 4.dp))
            }
            Text(b.code, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Color(0xFFD4D4D4))
        }
        is Aip.Block.Quote -> Column(
            Modifier.padding(vertical = 4.dp).fillMaxWidth()
                .border(2.dp, cs.outlineVariant, RoundedCornerShape(4.dp))
                .padding(10.dp)
        ) {
            MarkdownText(text = b.text, onLinkClick = onLinkClick, color = cs.onSurfaceVariant)
            if (b.cite.isNotBlank()) Text("—— ${b.cite}", fontSize = 11.sp, color = cs.onSurfaceVariant, fontStyle = FontStyle.Italic)
        }
        is Aip.Block.Callout -> CalloutCard(b)
        is Aip.Block.Divider -> HorizontalDivider(Modifier.padding(vertical = 8.dp), color = cs.outlineVariant)
        is Aip.Block.Image -> Column(
            Modifier.padding(vertical = 4.dp).fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(cs.surfaceVariant.copy(alpha = 0.5f))
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("🖼  ${b.ref}", fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = cs.onSurfaceVariant)
            if (b.caption.isNotBlank()) Text(b.caption, fontSize = 11.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        }
        is Aip.Block.Chart -> AipChart(b)
        is Aip.Block.Columns -> AipColumns(b, onLinkClick)
        is Aip.Block.Steps -> AipSteps(b)
        is Aip.Block.Timeline -> AipTimeline(b)
        is Aip.Block.Mindmap -> MindmapView(b)
        is Aip.Block.Slide -> SlideCard(b)
        // L2 兜底：未知/损坏块 → 富文本渲染原始内容，不丢弃
        is Aip.Block.Fallback -> Surface(
            shape = RoundedCornerShape(8.dp),
            color = cs.surfaceVariant.copy(alpha = 0.4f),
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        ) {
            Text(b.text, fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(8.dp))
        }
    }
}

@Composable
private fun CalloutCard(b: Aip.Block.Callout) {
    val cs = MaterialTheme.colorScheme
    val (bg, fg) = when (b.tone) {
        "warn", "warning" -> cs.errorContainer.copy(alpha = 0.4f) to cs.onErrorContainer
        "success" -> Color(0xFFE8F5E9) to Color(0xFF1B5E20)
        "danger" -> cs.errorContainer to cs.onError
        else -> cs.primaryContainer.copy(alpha = 0.4f) to cs.onPrimaryContainer
    }
    Surface(color = bg, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(10.dp)) {
            if (b.title.isNotBlank()) Text(b.title, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = fg)
            if (b.text.isNotBlank()) MarkdownText(text = b.text, onLinkClick = {}, color = fg)
        }
    }
}

@Composable
private fun AipTable(b: Aip.Block.Table) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().padding(vertical = 4.dp)
            .horizontalScroll(rememberScrollState())
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, cs.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(8.dp)),
    ) {
        Row(Modifier.fillMaxWidth().background(cs.surfaceVariant.copy(alpha = 0.5f))) {
            b.headers.forEach {
                Text(it, fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.weight(1f).padding(8.dp), maxLines = 3)
            }
        }
        b.rows.forEachIndexed { ri, row ->
            if (ri % 2 == 1) Row(Modifier.fillMaxWidth().background(cs.surfaceVariant.copy(alpha = 0.2f))) {
                row.forEach { Text(it, fontSize = 12.sp, modifier = Modifier.weight(1f).padding(8.dp)) }
            } else Row(Modifier.fillMaxWidth()) {
                row.forEach { Text(it, fontSize = 12.sp, modifier = Modifier.weight(1f).padding(8.dp)) }
            }
        }
    }
}

@Composable
private fun AipColumns(b: Aip.Block.Columns, onLinkClick: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        b.children.forEachIndexed { i, col ->
            Column(Modifier.weight(if (i < b.ratio.size) b.ratio[i].toFloat() else 1f)) {
                col.forEach { AipCanvasBlock(it, onLinkClick) }
            }
        }
    }
}

@Composable
private fun AipSteps(b: Aip.Block.Steps) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.padding(vertical = 4.dp)) {
        b.items.forEachIndexed { i, item ->
            Row(Modifier.padding(vertical = 2.dp)) {
                Box(
                    Modifier.size(22.dp).clip(CircleShape).background(cs.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("${i + 1}", color = cs.onPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(8.dp))
                Column {
                    MarkdownText(text = item)
                    if (i < b.items.size - 1) {
                        Box(Modifier.padding(start = 10.dp).width(2.dp).height(6.dp).background(cs.outlineVariant))
                    }
                }
            }
        }
    }
}

@Composable
private fun AipTimeline(b: Aip.Block.Timeline) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.padding(vertical = 4.dp)) {
        b.items.forEach { item ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(cs.primary))
                    if (b.items.indexOf(item) < b.items.size - 1) {
                        Box(Modifier.width(2.dp).height(28.dp).background(cs.outlineVariant.copy(alpha = 0.6f)))
                    }
                }
                Spacer(Modifier.width(10.dp))
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (item.time.isNotBlank()) Text(item.time, fontSize = 11.sp, color = cs.primary, fontWeight = FontWeight.SemiBold)
                        if (item.time.isNotBlank() && item.title.isNotBlank()) Spacer(Modifier.width(6.dp))
                        if (item.title.isNotBlank()) Text(item.title, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    }
                    if (item.text.isNotBlank()) MarkdownText(text = item.text)
                }
            }
        }
    }
}

/* ===================== 图表（Canvas 手绘，无三方依赖） ===================== */

private val CHART_COLORS = listOf(
    Color(0xFF2E6BE6), Color(0xFFE65100), Color(0xFF00897B),
    Color(0xFF8E24AA), Color(0xFF558B2F), Color(0xFFD81B60),
)

@Composable
private fun AipChart(b: Aip.Block.Chart) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        if (b.title.isNotBlank()) Text(b.title, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, modifier = Modifier.padding(bottom = 4.dp))
        Canvas(
            Modifier.fillMaxWidth().height(180.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(cs.surfaceVariant.copy(alpha = 0.3f)),
        ) {
            when (b.chartType) {
                "pie", "donut" -> drawPie(b)
                "line", "area" -> drawLine(b)
                "radar" -> drawRadar(b)
                else -> drawBar(b)
            }
        }
        // 图例
        if (b.series.size > 1) {
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                b.series.forEachIndexed { i, s ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(CHART_COLORS[i % CHART_COLORS.size]))
                        Spacer(Modifier.width(3.dp))
                        Text(s.name.ifBlank { "系列${i + 1}" }, fontSize = 10.sp)
                    }
                }
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBar(b: Aip.Block.Chart) {
    val series = b.series.ifEmpty { return }
    val n = b.labels.size.coerceAtLeast(1)
    val maxV = series.maxOf { s -> s.values.maxOrNull() ?: 1.0 }.coerceAtLeast(0.001)
    val padL = 12f
    val padB = 18f
    val chartW = size.width - padL
    val chartH = size.height - padB
    val groupW = chartW / n
    val barW = (groupW * 0.6f) / series.size
    series.forEachIndexed { si, s ->
        val brush = Brush.verticalGradient(listOf(CHART_COLORS[si % CHART_COLORS.size], CHART_COLORS[si % CHART_COLORS.size].copy(alpha = 0.65f)))
        s.values.forEachIndexed { i, v ->
            val h = (v / maxV).toFloat() * chartH * 0.9f
            drawRoundRect(
                brush = brush,
                topLeft = Offset(padL + i * groupW + groupW * 0.2f + si * barW, chartH - h),
                size = androidx.compose.ui.geometry.Size(barW - 3f, h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f, 4f),
            )
        }
    }
    // 轴线
    drawLine(Color.Gray.copy(alpha = 0.4f), Offset(padL, chartH), Offset(size.width, chartH), strokeWidth = 2f)
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawLine(b: Aip.Block.Chart) {
    val series = b.series.ifEmpty { return }
    val n = b.labels.size.coerceAtLeast(2)
    val maxV = series.maxOf { s -> s.values.maxOrNull() ?: 1.0 }.coerceAtLeast(0.001)
    val padL = 12f
    val padB = 18f
    val chartH = size.height - padB
    series.forEachIndexed { si, s ->
        val color = CHART_COLORS[si % CHART_COLORS.size]
        val path = Path()
        s.values.forEachIndexed { i, v ->
            val x = padL + i * (size.width - padL) / (n - 1)
            val y = chartH - (v / maxV).toFloat() * chartH * 0.9f
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color, style = Stroke(width = 4f, cap = StrokeCap.Round))
        s.values.forEachIndexed { i, v ->
            val x = padL + i * (size.width - padL) / (n - 1)
            val y = chartH - (v / maxV).toFloat() * chartH * 0.9f
            drawCircle(color, radius = 5f, center = Offset(x, y))
        }
    }
    drawLine(Color.Gray.copy(alpha = 0.4f), Offset(padL, chartH), Offset(size.width, chartH), strokeWidth = 2f)
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPie(b: Aip.Block.Chart) {
    val s = b.series.firstOrNull() ?: return
    val values = s.values.ifEmpty { return }
    val total = values.sum().coerceAtLeast(0.001)
    var start = -90f
    val r = minOf(size.width, size.height) * 0.36f
    val c = Offset(size.width / 2, size.height / 2)
    values.forEachIndexed { i, v ->
        val sweep = (v / total).toFloat() * 360f
        drawArc(
            color = CHART_COLORS[i % CHART_COLORS.size],
            startAngle = start,
            sweepAngle = sweep - 1.5f,
            useCenter = b.chartType == "pie",
            topLeft = Offset(c.x - r, c.y - r),
            size = androidx.compose.ui.geometry.Size(r * 2, r * 2),
            style = if (b.chartType == "pie") androidx.compose.ui.graphics.drawscope.Fill else Stroke(width = r * 0.55f),
        )
        start += sweep
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawRadar(b: Aip.Block.Chart) {
    val s = b.series.firstOrNull() ?: return
    val labels = b.labels.ifEmpty { return }
    val n = labels.size.coerceAtLeast(3)
    val maxV = s.values.maxOrNull() ?: 1.0
    val c = Offset(size.width / 2, size.height / 2)
    val r = minOf(size.width, size.height) * 0.36f
    // 网格
    listOf(0.33f, 0.66f, 1f).forEach { f ->
        val path = Path()
        for (i in 0 until n) {
            val ang = Math.toRadians((360.0 / n * i - 90))
            val p = Offset(c.x + (r * f * Math.cos(ang)).toFloat(), c.y + (r * f * Math.sin(ang)).toFloat())
            if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
        }
        path.close()
        drawPath(path, Color.Gray.copy(alpha = 0.3f), style = Stroke(1.5f))
    }
    // 数据多边形
    val path = Path()
    for (i in 0 until n) {
        val v = s.values.getOrElse(i) { 0.0 } / maxV
        val ang = Math.toRadians((360.0 / n * i - 90))
        val p = Offset(c.x + (r * v * Math.cos(ang)).toFloat(), c.y + (r * v * Math.sin(ang)).toFloat())
        if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
    }
    path.close()
    drawPath(path, CHART_COLORS[0].copy(alpha = 0.25f))
    drawPath(path, CHART_COLORS[0], style = Stroke(3f))
}

/* ===================== 思维导图（缩进树 + 肘形引导线） ===================== */

@Composable
fun MindmapView(b: Aip.Block.Mindmap) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        MindmapNodeView(b.root, isRoot = true)
    }
}

@Composable
private fun MindmapNodeView(node: Aip.Block.Mindmap.Node, isRoot: Boolean) {
    val cs = MaterialTheme.colorScheme
    Column {
        Surface(
            color = if (isRoot) cs.primary else cs.secondaryContainer.copy(alpha = 0.6f),
            shape = RoundedCornerShape(if (isRoot) 10.dp else 8.dp),
        ) {
            Text(
                node.text.ifBlank { " " },
                color = if (isRoot) cs.onPrimary else cs.onSecondaryContainer,
                fontWeight = if (isRoot) FontWeight.Bold else FontWeight.SemiBold,
                fontSize = if (isRoot) 14.sp else 12.sp,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }
        if (node.children.isNotEmpty()) {
            Column(Modifier.padding(start = 12.dp)) {
                node.children.forEach { child ->
                    Row {
                        // 肘形引导线（垂直段）
                        Box(
                            Modifier.width(14.dp).height(24.dp)
                                .verticalGuide(cs.outlineVariant.copy(alpha = 0.7f)),
                        )
                        MindmapNodeView(child, isRoot = false)
                    }
                }
            }
        }
    }
}

/** 思维导图节点的垂直引导线。 */
private fun Modifier.verticalGuide(color: Color): Modifier =
    this.drawBehind {
        drawLine(color, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), strokeWidth = 2f)
    }

/* ===================== Deck：幻灯片翻页容器 ===================== */

@Composable
private fun DeckPager(env: Aip.Envelope) {
    val cs = MaterialTheme.colorScheme
    val slides = env.blocks.filterIsInstance<Aip.Block.Slide>()
    if (slides.isEmpty()) {
        env.blocks.forEach { AipCanvasBlock(it) }
        return
    }
    val pagerState = rememberPagerState { slides.size }
    Column(Modifier.fillMaxWidth()) {
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxWidth()) { page ->
            SlideCard(slides[page])
        }
        // 缩略进度条（PRD 6.2：未生成完的页显示骨架占位）
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            Text("${pagerState.currentPage + 1} / ${slides.size}", fontSize = 11.sp, color = cs.onSurfaceVariant)
        }
    }
}

/** 幻灯片页卡：16:9 画布 + 12 种版式中 V1 落地的核心 9 种，其余回落 titleBody。 */
@Composable
fun SlideCard(b: Aip.Block.Slide) {
    val cs = MaterialTheme.colorScheme
    val accent = Color(0xFF2E6BE6)
    // [排版引擎修复] 固定 16:9 + Arrangement.Center 在内容超长时会溢出框外、与相邻页/指示器重叠
    // （"文字被覆盖一个盖一个"）。改为：卡片保持 16:9 固定高度，内部内容可纵向滚动 + 顶部对齐，
    // 超长幻灯片滚动查看而非溢出重叠。
    Column(
        Modifier.fillMaxWidth().aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(14.dp))
            .background(
                Brush.verticalGradient(listOf(cs.surfaceVariant.copy(alpha = 0.25f), cs.surfaceVariant.copy(alpha = 0.6f))),
            )
            .verticalScroll(rememberScrollState())
            .padding(18.dp),
        verticalArrangement = Arrangement.Top,
    ) {
        when (b.layout) {
            "cover" -> Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.Center) {
                Text(b.title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                if (b.subtitle.isNotBlank()) Text(b.subtitle, style = MaterialTheme.typography.titleMedium, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                Box(Modifier.padding(top = 10.dp).size(width = 48.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(accent))
            }
            "section" -> Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.Center) {
                Text(b.title, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, color = accent)
                if (b.subtitle.isNotBlank()) Text(b.subtitle, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 4.dp))
            }
            "stats" -> Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.Center) {
                if (b.title.isNotBlank()) Text(b.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    b.stats.forEach { (v, l) ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                            Text(v, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = accent)
                            Text(l, fontSize = 11.sp, color = cs.onSurfaceVariant)
                        }
                    }
                }
            }
            "quote" -> Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.Center) {
                Text("“${b.quote.ifBlank { b.title }}”", style = MaterialTheme.typography.headlineSmall, fontStyle = FontStyle.Italic)
                if (b.quoteAuthor.isNotBlank()) Text("—— ${b.quoteAuthor}", fontSize = 12.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            }
            "twoCol" -> Column {
                Text(b.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    b.columns.forEach { (t, txt) ->
                        Column(Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(cs.surface.copy(alpha = 0.5f)).padding(10.dp)) {
                            if (t.isNotBlank()) Text(t, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            Text(txt, fontSize = 11.sp)
                        }
                    }
                }
            }
            "chart" -> Column {
                Text(b.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                b.chart?.let { AipChart(it) }
            }
            "table" -> Column {
                Text(b.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                b.table?.let { AipTable(it) }
            }
            "summary" -> Column {
                Text(b.title.ifBlank { "要点汇总" }, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                b.bullets.forEach {
                    Row(Modifier.padding(top = 4.dp)) {
                        Text("✓ ", color = accent, fontWeight = FontWeight.Bold)
                        Text(it, fontSize = 13.sp)
                    }
                }
            }
            else -> Column {
                // titleBody / imageLeft / imageFull / timeline / 未知版式回落
                Text(b.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                if (b.subtitle.isNotBlank()) Text(b.subtitle, color = cs.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
                b.bullets.forEach {
                    Row(Modifier.padding(top = 6.dp)) {
                        Text("•  ", color = accent, fontWeight = FontWeight.Bold)
                        Text(it, fontSize = 13.sp)
                    }
                }
                if (b.bullets.isEmpty() && b.columns.isNotEmpty()) {
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        b.columns.forEach { (t, txt) ->
                            Column(Modifier.weight(1f)) {
                                if (t.isNotBlank()) Text(t, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                                Text(txt, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}
