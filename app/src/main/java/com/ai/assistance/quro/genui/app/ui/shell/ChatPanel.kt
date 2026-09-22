@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.ai.assistance.quro.genui.app.ui.shell

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.delay
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import com.ai.assistance.quro.genui.app.agent.ChatMsg
import com.ai.assistance.quro.genui.app.agent.ToolTrace
import com.ai.assistance.quro.genui.app.miniapp.GenUiMiniAppActivity
import com.ai.assistance.quro.genui.app.ui.theme.GenTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 标准 Agent 对话面板：把 [ChatMsg] 流渲染成气泡流。
 *
 * 视觉与交互约定（v0.16.1 打磨）：
 * - 每条消息入场：淡入 + 12dp 上滑（240ms，FastOutSlowIn）——流式滚动不再生硬；
 * - 角色头像列：助手 ✦ 琥珀圈 / 工具族专属图标圈 / 错误 ⚠；用户消息右对齐无头像；
 * - 工具调用是可展开卡片：状态图标（运行中=旋转 loader / ✓ / ✗）+ 族图标 + 工具名
 *   + 耗时徽标 + 展开箭头；展开看参数与结果摘要（等宽字体，限行防爆屏）；
 * - 助手流式光标 ▌ 呼吸闪烁；首 token 前的空泡渲染三点跳动（思考中）；
 * - 旧持久化记录（无 ToolTrace 元数据）按文本格式兼容渲染。
 */
fun hhmm(ts: Long): String = SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(ts))

/** 工具族 → 专属图标 */
internal fun toolIcon(name: String): String = when {
    name.startsWith("web_search") || name.startsWith("news_search") -> "\uD83D\uDD0D"
    name.startsWith("web_fetch") -> "\uD83C\uDF10"
    name.startsWith("community_search") -> "\uD83D\uDCAC"
    name.startsWith("github") -> "\uD83D\uDCDB"
    name.startsWith("memory") -> "\uD83D\uDCBE"
    name.startsWith("clipboard") -> "\uD83D\uDCCB"
    name.startsWith("notify") -> "\uD83D\uDD14"
    name.startsWith("tts") -> "\uD83D\uDD0A"
    name.startsWith("haptics") -> "\uD83D\uDCF3"
    name.startsWith("device") || name.startsWith("system_status") -> "\uD83D\uDCF1"
    name.startsWith("time") -> "\uD83D\uDD51"
    name.startsWith("file") -> "\uD83D\uDCC4"
    name.startsWith("calendar") -> "\uD83D\uDCC5"
    name.startsWith("location") -> "\uD83D\uDCCD"
    name.startsWith("mcp_") -> "\uD83E\uDE9B"
    name.startsWith("share") -> "\u2197"
    name.startsWith("flashlight") -> "\uD83D\uDD26"
    name.startsWith("contacts") -> "\uD83D\uDC65"
    name.startsWith("sms") -> "\uD83D\uDCE9"
    name.startsWith("call") -> "\uD83D\uDCDE"
    name.startsWith("alarm") || name.startsWith("open_url") -> "\uD83D\uDD17"
    else -> "\u2699"
}

/** 一次性入场动画包装：淡入 + 上滑。按 key 只播一次——
 *  LazyColumn 回收后滚回来不再重播（否则翻看历史时满屏闪动）。 */
@Composable
private fun Appear(key: String, content: @Composable () -> Unit) {
    var played by rememberSaveable(key) { mutableStateOf(false) }
    val v = remember(key) { Animatable(if (played) 1f else 0f) }
    LaunchedEffect(key) {
        if (!played) {
            v.animateTo(1f, tween(240, easing = FastOutSlowInEasing))
            played = true
        }
    }
    Box(Modifier.graphicsLayer {
        alpha = v.value
        translationY = (1f - v.value) * 36f   // ~12dp
    }) { content() }
}

/** 运行中实时秒表：startTs = 工具开始时刻（tool 消息创建时间），每秒刷新 */
@Composable
private fun ElapsedBadge(startTs: Long, color: Color) {
    var now by remember(startTs) { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(startTs) {
        while (true) { delay(1000); now = System.currentTimeMillis() }
    }
    Text(fmtMsBadge(now - startTs), color = color, fontSize = 9.sp,
        fontFamily = FontFamily.Monospace)
}

/** 旋转 loader（工具运行中） */
@Composable
private fun Loader(size: Int = 12, color: Color) {
    val t = rememberInfiniteTransition(label = "loader")
    val r by t.animateFloat(0f, 360f, infiniteRepeatable(tween(800, easing = LinearEasing)), label = "rot")
    Text("◠", color = color, fontSize = size.sp, modifier = Modifier.graphicsLayer { rotationZ = r })
}

/** 呼吸闪烁的光标（流式输出中） */
@Composable
private fun BlinkCursor() {
    val t = rememberInfiniteTransition(label = "cursor")
    val a by t.animateFloat(1f, 0.15f, infiniteRepeatable(tween(480, easing = LinearEasing), RepeatMode.Reverse), label = "a")
    Text("▌", color = GenTheme.Amber, fontSize = 13.sp, modifier = Modifier.graphicsLayer { alpha = a })
}

/** 思考中三点跳动（首个 token 到达前） */
@Composable
private fun ThinkingDots() {
    val t = rememberInfiniteTransition(label = "dots")
    val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "p")
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(3) { i ->
            val phase = ((p * 3f - i) % 3f + 3f) % 3f
            val lift = if (phase < 1f) (1f - phase) else 0f   // 依次上浮
            Text("●", color = GenTheme.Amber.copy(alpha = 0.35f + 0.65f * lift), fontSize = 9.sp,
                modifier = Modifier.graphicsLayer { translationY = -lift * 7f })
        }
    }
}

/** 渲染分组：单条消息 或 连续工具段（≥3 条折叠） */
private sealed class RenderItem {
    abstract val key: String
    data class Single(val m: ChatMsg, val prevFailCount: Int = 0, val prevSuccessCount: Int = 0) : RenderItem() {
        override val key get() = m.id
    }
    data class Fold(val seg: List<ChatMsg>) : RenderItem() {
        override val key get() = "fold_" + seg.first().id
    }
}

/** 连续 ≥3 条工具消息折叠为一组（失败连击时七八张红卡连排，噪音淹没正文）。
 *  含运行中条目的段不折叠（"运行中"永远可见）；折叠头行给汇总。 */
private fun groupToolRuns(messages: List<ChatMsg>): List<RenderItem> {
    val out = mutableListOf<RenderItem>()
    var prevFail = 0
    var prevOk = 0
    var i = 0
    while (i < messages.size) {
        if (messages[i].role != "tool") {
            val m = messages[i]
            // 警示按【本轮】（最近一条 user 之后）统计：跨轮累计会把上一轮失败算到本轮头上
            out.add(RenderItem.Single(m, prevFail.takeIf { m.role == "assistant" } ?: 0,
                prevOk.takeIf { m.role == "assistant" } ?: 0))
            if (m.role == "user") { prevFail = 0; prevOk = 0 }
            i++
            continue
        }
        var j = i
        while (j < messages.size && messages[j].role == "tool") j++
        val seg = messages.subList(i, j)
        val hasRunning = seg.any { it.tool?.ms == -1L }
        val failCount = seg.count { it.tool?.isError == true }
        // 只聚合带 ToolTrace 的实时消息；legacy 段（历史恢复/回放，tool==null）
        // 没有结构化元数据，折叠头行会拿到空汇总——2026-09-17 崩溃教训
        val allTraced = seg.all { it.tool != null }
        if (seg.size >= 3 && !hasRunning && allTraced) {
            out.add(RenderItem.Fold(seg.toList()))
        } else {
            seg.forEach { out.add(RenderItem.Single(it)) }
        }
        prevFail = failCount
        prevOk = seg.count { it.tool?.isError == false }
        i = j
    }
    return out
}

/** 折叠组行：头像列 + 汇总头卡（点击展开逐条工具卡） */
@Composable
private fun FoldRow(seg: List<ChatMsg>) {
    val fails = seg.count { it.tool?.isError == true }
    val allFail = fails == seg.size
    val first = seg.first()
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.Top
    ) {
        Avatar(toolIcon(first.tool?.name ?: "tool"),
            bg = (if (allFail) GenTheme.Red else GenTheme.Green).copy(alpha = 0.15f))
        Spacer(Modifier.width(6.dp))
        FoldedTools(seg, Modifier.weight(1f, fill = false))
        Spacer(Modifier.width(5.dp))
        Text(hhmm(first.ts), color = GenTheme.Dim.copy(alpha = .6f), fontSize = 9.sp,
            fontFamily = FontFamily.Monospace, modifier = Modifier.align(Alignment.Bottom))
    }
}

/** 折叠组卡：汇总头（N 次调用 · 失败 M · 总耗时）+ 展开逐条 */
@Composable
private fun FoldedTools(seg: List<ChatMsg>, modifier: Modifier = Modifier) {
    // rememberSaveable：条目滚出视口回收后再回来，展开态不丢
    var expanded by rememberSaveable(seg.first().id) { mutableStateOf(false) }
    val fails = seg.count { it.tool?.isError == true }
    val denied = seg.count { it.tool?.denied == true }
    val allFail = fails == seg.size
    val names = seg.mapNotNull { it.tool?.name }.distinct()
    val totalMs = seg.sumOf { (it.tool?.ms ?: 0L).coerceAtLeast(0L) }
    val accent = when {
        allFail -> GenTheme.Red
        fails > 0 -> GenTheme.Amber
        else -> GenTheme.Green
    }
    Column(
        modifier
            .widthIn(max = 300.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(GenTheme.Screen)
            .drawBehind {
                drawRect(accent.copy(alpha = 0.6f), size = Size(3.dp.toPx(), size.height))
            }
            .border(0.5.dp, accent.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
            .clickable { expanded = !expanded }
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (allFail) "✗" else "✓", color = accent, fontSize = 12.sp,
                fontFamily = FontFamily.Monospace)
            Spacer(Modifier.width(7.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    when (names.size) {
                        0 -> "工具调用"
                        1 -> names[0]
                        else -> names.first() + " 等 " + names.size + " 种工具"
                    },
                    color = GenTheme.Text, fontSize = 13.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "${seg.size} 次调用 · 失败 " + fails +
                        (if (denied > 0) " · 拒绝 $denied" else "") +
                        " · 共 " + fmtMsBadge(totalMs),
                    color = GenTheme.Dim, fontSize = 9.5.sp,
                    fontFamily = FontFamily.Monospace,
                )
                // 首个失败原因直接亮在头行——不展开即知死因
                val firstErr = seg.firstOrNull { it.tool?.isError == true }
                    ?.text?.lineSequence()?.drop(1)?.firstOrNull()?.take(64) ?: ""
                if (firstErr.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(firstErr, color = accent.copy(alpha = 0.85f), fontSize = 9.sp,
                        lineHeight = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Text(if (expanded) "▾" else "▸", color = GenTheme.Dim, fontSize = 11.sp,
                fontFamily = FontFamily.Monospace)
        }
        AnimatedVisibility(expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Spacer(Modifier.height(4.dp))
                seg.forEach { tm -> ToolCard(tm, Modifier.fillMaxWidth()) }
            }
        }
    }
}

@Composable
fun ChatPanel(
    messages: List<ChatMsg>,
    modifier: Modifier = Modifier,
    onCardAction: (String) -> Unit = {},
    onOpenCanvas: (String) -> Unit = {},
    onOpenPerms: () -> Unit = {},
    onRetry: () -> Unit = {},
    aiAvatarPath: String? = null,
    aiName: String = "AI",
) {
    val listState = rememberLazyListState()
    // —— 分组渲染：绝不能包 remember(messages)！——
    // messages 是同一个 SnapshotStateList 实例，引用恒等 → remember 永不重算 →
    // 新消息不显示、启动恢复不刷新，只有切模式/重启才强制刷新（2026-09-17 回归）。
    // 直接在重组作用域内遍历：SnapshotStateList 的任何增删改都会触发重组重算。
    val renderItems = groupToolRuns(messages)
    // 进入/历史恢复完成：立即定位到最新（此前停在旧消息处，要手动翻到底）
    LaunchedEffect(messages.isNotEmpty()) {
        if (messages.isNotEmpty()) {
            kotlinx.coroutines.delay(120)   // 等历史恢复与首帧布局
            listState.scrollToItem(renderItems.lastIndex)
        }
    }
    // 自动跟随：流式时瞬时定位（scrollToItem 无动画——animateScrollToItem 动画期间
    // isScrollInProgress=true 会吞掉后续触发，形成"流式不跟随"）；上翻不打扰
    LaunchedEffect(messages.size, messages.lastOrNull()?.text?.length) {
        if (renderItems.isNotEmpty() && !listState.isScrollInProgress) {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            if (lastVisible >= renderItems.size - 2) {
                listState.scrollToItem(renderItems.lastIndex)
            }
        }
    }

    if (messages.isEmpty()) {
        Column(
            modifier.fillMaxSize().padding(horizontal = 30.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("◎", color = GenTheme.Amber, fontSize = 26.sp, fontFamily = FontFamily.Serif)
            Spacer(Modifier.height(14.dp))
            Text("跟 AI 说什么都行", color = GenTheme.Text, fontSize = 15.sp, fontFamily = FontFamily.Serif)
            Spacer(Modifier.height(8.dp))
            Text(
                "它会调工具查资料、跑搜索、读时间、记备忘 ——\n比如「今天有什么值得关注的新闻？」",
                color = GenTheme.Dim, fontSize = 12.sp, lineHeight = 19.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
        return
    }

    LazyColumn(
        state = listState,
        modifier = modifier,
        // 水平内容边距下沉到各条目：小程序条目要贴边全宽渲染（背景不再被"围栏"框住）
        contentPadding = PaddingValues(vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(renderItems, key = { it.key }) { ri ->
            Appear(ri.key) {
                Column {
                    if (ri is RenderItem.Fold) {
                        Box(Modifier.padding(horizontal = 12.dp)) { FoldRow(ri.seg) }
                    } else {
                    val m = (ri as RenderItem.Single).m
                    when (m.role) {
                        "user" -> Row(
                            Modifier.padding(horizontal = 12.dp).fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.Bottom
                        ) {
                            Text(hhmm(m.ts), color = GenTheme.Dim.copy(alpha = .6f), fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace)
                            Spacer(Modifier.width(5.dp))
                            UserBubble(m.text, m.attachments)
                        }
                        "tool" -> Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Start,
                            verticalAlignment = Alignment.Top
                        ) {
                            Avatar(toolIcon(m.tool?.name ?: legacyToolName(m.text)),
                                bg = GenTheme.Amber.copy(alpha = 0.14f))
                            Spacer(Modifier.width(6.dp))
                            ToolCard(m, Modifier.weight(1f, fill = false))
                            Spacer(Modifier.width(5.dp))
                            Text(hhmm(m.ts), color = GenTheme.Dim.copy(alpha = .6f), fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace, modifier = Modifier.align(Alignment.Bottom))
                        }
                        "miniapp" -> MiniAppRow(m.text)
                        "error" -> Row(
                            Modifier.padding(horizontal = 12.dp).fillMaxWidth(),
                            horizontalArrangement = Arrangement.Start,
                            verticalAlignment = Alignment.Top
                        ) {
                            Avatar("⚠", bg = GenTheme.Red.copy(alpha = 0.15f))
                            Spacer(Modifier.width(6.dp))
                            Column {
                                ErrorBubble(m.text)
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "↻ 重新回答",
                                    color = GenTheme.Amber, fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .clickable { onRetry() }
                                        .padding(horizontal = 6.dp, vertical = 3.dp)
                                )
                            }
                            Spacer(Modifier.width(5.dp))
                            Text(hhmm(m.ts), color = GenTheme.Dim.copy(alpha = .6f), fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace, modifier = Modifier.align(Alignment.Bottom))
                        }
                        else -> Row(
                            Modifier.padding(horizontal = 12.dp).fillMaxWidth(),
                            horizontalArrangement = Arrangement.Start,
                            verticalAlignment = Alignment.Top
                        ) {
                            if (aiAvatarPath != null && java.io.File(aiAvatarPath).exists()) {
                                val bmp = remember(aiAvatarPath) {
                                    runCatching {
                                        val bo = android.graphics.BitmapFactory.Options()
                                        bo.inJustDecodeBounds = true
                                        android.graphics.BitmapFactory.decodeFile(aiAvatarPath, bo)
                                        bo.inSampleSize = maxOf(1, bo.outWidth / 96)
                                        bo.inJustDecodeBounds = false
                                        android.graphics.BitmapFactory.decodeFile(aiAvatarPath, bo)
                                    }.getOrNull()
                                }
                                if (bmp != null) {
                                    androidx.compose.foundation.Image(
                                        bitmap = bmp.asImageBitmap(), contentDescription = aiName,
                                        modifier = Modifier.size(22.dp).clip(androidx.compose.foundation.shape.CircleShape))
                                } else {
                                    rememberAppIconBitmap()?.let { icon ->
                                        androidx.compose.foundation.Image(
                                            bitmap = icon, contentDescription = aiName,
                                            modifier = Modifier.size(22.dp).clip(androidx.compose.foundation.shape.CircleShape))
                                    } ?: Avatar(aiName.take(1), bg = GenTheme.Amber.copy(alpha = 0.18f), fg = GenTheme.Amber)
                                }
                            } else {
                                rememberAppIconBitmap()?.let { icon ->
                                    androidx.compose.foundation.Image(
                                        bitmap = icon, contentDescription = aiName,
                                        modifier = Modifier.size(22.dp).clip(androidx.compose.foundation.shape.CircleShape))
                                } ?: Avatar("✦", bg = GenTheme.Amber.copy(alpha = 0.18f), fg = GenTheme.Amber)
                            }
                            // —— 灵魂注入名字：头像下方显示 AI 名字（仅首条与每轮首行可读即可，直接常显开销小） ——
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Spacer(Modifier.height(2.dp))
                                Text(aiName, color = GenTheme.Dim.copy(alpha = 0.85f), fontSize = 8.sp,
                                    maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    modifier = Modifier.widthIn(max = 40.dp))
                            }
                            Spacer(Modifier.width(6.dp))
                            AssistantBubble(m.text, m.done, onCardAction, Modifier.weight(1f, fill = false), onOpenCanvas,
                                warnNote = run {
                                    val s = ri as? RenderItem.Single
                                    when {
                                        s == null || s.prevFailCount == 0 -> null
                                        s.prevSuccessCount > 0 ->
                                            "ℹ 本轮 ${s.prevFailCount} 项工具失败、${s.prevSuccessCount} 项成功，以下回答仅部分有实时数据"
                                        else ->
                                            "⚠ 本轮 ${s.prevFailCount} 项工具调用全部失败，以下回答没有实时数据支撑"
                                    }
                                },
                                onOpenPerms = onOpenPerms,
                                reasoning = m.reasoning, reasoningMs = m.reasoningMs)
                            Spacer(Modifier.width(5.dp))
                            Text(hhmm(m.ts), color = GenTheme.Dim.copy(alpha = .6f), fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace, modifier = Modifier.align(Alignment.Bottom))
                        }
                    }
                    }
                }
            }
        }
    }
}

private fun legacyToolName(text: String): String =
    text.removePrefix("✅ ").removePrefix("❌ ").removePrefix("⚙ ").substringBefore('·').substringBefore('(').trim()

/** App 图标位图（adaptive icon 也能画：Drawable 统一绘制到 canvas） */
@Composable
private fun rememberAppIconBitmap(): androidx.compose.ui.graphics.ImageBitmap? {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    return remember {
        runCatching {
            val d = androidx.core.content.ContextCompat.getDrawable(ctx, com.ai.assistance.quro.R.mipmap.ic_launcher)
                ?: return@runCatching null
            val s = 96
            val bm = android.graphics.Bitmap.createBitmap(s, s, android.graphics.Bitmap.Config.ARGB_8888)
            val cv = android.graphics.Canvas(bm)
            d.setBounds(0, 0, s, s); d.draw(cv)
            bm.asImageBitmap()
        }.getOrNull()
    }
}

/** 20dp 角色头像圈 */
@Composable
private fun Avatar(glyph: String, bg: Color, fg: Color = GenTheme.Text) {
    Box(
        Modifier.size(22.dp).clip(CircleShape).background(bg),
        contentAlignment = Alignment.Center
    ) { Text(glyph, fontSize = 11.sp, color = fg) }
}

@Composable
private fun UserBubble(text: String, attachments: List<com.ai.assistance.quro.genui.app.agent.Attach> = emptyList()) {
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Column(
        modifier = Modifier
            .widthIn(max = 300.dp)
            .clip(RoundedCornerShape(12.dp, 12.dp, 3.dp, 12.dp))
            .background(GenTheme.Amber)
            .combinedClickable(
                onLongClick = {
                    clipboard.setText(AnnotatedString(text))
                    Toast.makeText(ctx, "已复制", Toast.LENGTH_SHORT).show()
                },
                onClick = {},
            )
            .padding(horizontal = 12.dp, vertical = 9.dp)
    ) {
        // —— 附件渲染：图片缩略图 / 文件 chip（发送后气泡内可见） ——
        attachments.forEach { a ->
            if (a.isImage) {
                val bmp = remember(a.path) {
                    runCatching {
                        val opts = android.graphics.BitmapFactory.Options().apply {
                            inJustDecodeBounds = true
                            android.graphics.BitmapFactory.decodeFile(a.path, this)
                            val w = if (outWidth > 0) outWidth else 512
                            inSampleSize = maxOf(1, w / 256)
                            inJustDecodeBounds = false
                        }
                        android.graphics.BitmapFactory.decodeFile(a.path, opts)
                    }.getOrNull()
                }
                if (bmp != null) {
                    androidx.compose.foundation.Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = a.name,
                        modifier = Modifier
                            .widthIn(max = 220.dp)
                            .heightIn(max = 160.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .padding(bottom = 6.dp),
                        contentScale = androidx.compose.ui.layout.ContentScale.FillWidth,
                    )
                }
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 6.dp),
                ) {
                    Text("📄", fontSize = 12.sp)
                    Spacer(Modifier.width(4.dp))
                    Text(a.name, color = Color.White.copy(alpha = 0.92f), fontSize = 11.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Text(
            text = text,
            color = Color.White, fontSize = 14.sp, lineHeight = 21.sp,
        )
    }
}

@Composable
private fun AssistantBubble(text: String, done: Boolean, onCardAction: (String) -> Unit = {},
                            modifier: Modifier = Modifier, onOpenCanvas: (String) -> Unit = {},
                            warnNote: String? = null, onOpenPerms: () -> Unit = {},
                            reasoning: String = "", reasoningMs: Long = 0) {
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Column(
        modifier
            .widthIn(max = 310.dp)
            .clip(RoundedCornerShape(12.dp, 12.dp, 12.dp, 3.dp))
            .background(GenTheme.Panel)
            .combinedClickable(
                onLongClick = {
                    clipboard.setText(AnnotatedString(text))
                    Toast.makeText(ctx, "已复制全文", Toast.LENGTH_SHORT).show()
                },
                onClick = {},
            )
            .padding(horizontal = 12.dp, vertical = 9.dp)
    ) {
        // —— 思考折叠条（对标 ChatGPT "Thought for Ns"）：默认收起，点开展开全文 ——
        if (reasoning.isNotBlank()) {
            var rOpen by rememberSaveable(text.hashCode()) { mutableStateOf(false) }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(GenTheme.PanelUp)
                    .clickable { rOpen = !rOpen }
                    .padding(horizontal = 8.dp, vertical = 5.dp)
            ) {
                Text(if (rOpen) "▾" else "▸", color = GenTheme.AmberDim, fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace)
                Spacer(Modifier.width(6.dp))
                Text(
                    if (done) "已深度思考 · ${reasoningMs / 1000.0}s" else "思考中…",
                    color = GenTheme.AmberDim, fontSize = 10.5.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
            AnimatedVisibility(rOpen) {
                Text(
                    text = reasoning,
                    color = GenTheme.Dim, fontSize = 10.sp, lineHeight = 14.5.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                        .heightIn(max = 240.dp)
                        .verticalScroll(rememberScrollState()),
                )
            }
            Spacer(Modifier.height(6.dp))
        }
        if (warnNote != null) {
            // 客户端硬兜底的可见化 + 自救闭环：点击直达权限屏（权限类失败占大头）
            Text(
                warnNote + " · 点此检查权限 →",
                color = GenTheme.Red.copy(alpha = 0.92f), fontSize = 10.sp, lineHeight = 14.sp,
                modifier = Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(GenTheme.Red.copy(alpha = 0.10f))
                    .clickable { onOpenPerms() }
                    .padding(horizontal = 8.dp, vertical = 5.dp)
            )
            Spacer(Modifier.height(6.dp))
        }
        if (text.isBlank() && !done) {
            ThinkingDots()
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (done) {
                    // 完整渲染引擎分段：普通文本 → RichText；```html/裸文档 → 内嵌 WebView
                    splitHtmlSegments(text).forEach { seg ->
                        if (seg.startsWith(HTML_SEG)) {
                            HtmlEngineChip(
                                html = seg.removePrefix(HTML_SEG),
                                onOpenCanvas = onOpenCanvas,
                            )
                        } else {
                            ChatCards.split(seg).forEach { (isCard, s2) ->
                                if (isCard) CardInline(s2)
                                else RichText(
                                    text = s2,
                                    baseStyle = TextStyle(fontSize = 14.sp, color = GenTheme.Text, lineHeight = 21.sp),
                                )
                            }
                        }
                    }
                } else {
                    // 流式中：只渲染文本段，html 围栏未闭合显示接收占位
                    ChatCards.split(text).forEach { (isCard, seg) ->
                        if (isCard) CardInline(seg)
                        else if (seg.trimStart().startsWith("```html")) {
                            HtmlReceivingChip()
                        }
                        else RichText(
                            text = seg,
                            baseStyle = TextStyle(fontSize = 14.sp, color = GenTheme.Text, lineHeight = 21.sp),
                        )
                    }
                }
            }
            if (!done) {
                Spacer(Modifier.height(3.dp))
                BlinkCursor()
            }
        }
    }
}

@Composable
private fun ErrorBubble(text: String) {
    Text(
        text = text,
        color = GenTheme.Red, fontSize = 13.sp, lineHeight = 19.sp,
        modifier = Modifier
            .widthIn(max = 300.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(GenTheme.Red.copy(alpha = 0.08f))
            .border(0.5.dp, GenTheme.Red.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp)
    )
}

// ---------- 工具调用卡片（可展开，状态动画） ----------

@Composable
private fun ToolCard(m: ChatMsg, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    val t: ToolTrace? = m.tool
    // 兼容旧持久化记录：从文本解析状态与工具名
    val legacy = t == null
    val name = t?.name ?: legacyToolName(m.text)
    val running = (t != null && t.ms < 0L)
    val isErr = (t?.isError == true) || m.text.startsWith("❌")
    val denied = t?.denied == true
    val ms = t?.ms ?: -1L
    val summary = if (legacy) m.text.removePrefix("✅ ").removePrefix("❌ ")
        else m.text.substringAfter('\n', "").ifBlank { m.text }

    // 状态色语义分层：失败=红，拒绝=琥珀（用户主动选择，不是事故），运行中=琥珀，成功=绿
    val accent = when {
        isErr -> GenTheme.Red
        denied -> GenTheme.Amber
        running -> GenTheme.Amber
        else -> GenTheme.Green
    }
    val statusLabel = when {
        running -> "运行中"
        denied -> "已拒绝"
        isErr -> "失败"
        else -> "完成"
    }

    Column(
        modifier
            .widthIn(max = 300.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(GenTheme.Screen)
            .drawBehind {
                // 左缘状态竖条：一眼扫过即知本步成败
                drawRect(accent.copy(alpha = if (running) 0.9f else 0.6f),
                    size = Size(3.dp.toPx(), size.height))
            }
            .border(0.5.dp, accent.copy(alpha = if (running) 0.8f else 0.4f), RoundedCornerShape(10.dp))
            .clickable { expanded = !expanded }
            .padding(horizontal = 10.dp, vertical = 7.dp)
    ) {
        // —— 卡头：状态 + 图标 + 名字 + 耗时 + 箭头 ——
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (running) Loader(color = GenTheme.Amber)
            else Text(if (isErr || denied) "✗" else "✓",
                color = accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(6.dp))
            Text(toolIcon(name), fontSize = 12.sp)
            Spacer(Modifier.width(4.dp))
            Column(Modifier.weight(1f)) {
                Text(name, color = GenTheme.Text, fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (t?.brief?.isNotBlank() == true) {
                    Text(t.brief, color = GenTheme.Dim, fontSize = 10.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.width(6.dp))
            Text(statusLabel, color = accent, fontSize = 9.sp,
                fontFamily = FontFamily.Monospace)
            if (running) {
                Spacer(Modifier.width(4.dp))
                ElapsedBadge(m.ts, GenTheme.Dim)
            } else if (ms > 0) {
                Spacer(Modifier.width(4.dp))
                Text(fmtMsBadge(ms), color = GenTheme.Dim, fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace)
            }
            Spacer(Modifier.width(4.dp))
            Text(if (expanded) "▾" else "▸", color = GenTheme.Dim, fontSize = 10.sp)
        }
        // —— 展开区：结果 / 参数 分段排版 ——
        AnimatedVisibility(expanded) {
            Column {
                Spacer(Modifier.height(6.dp))
                Text(
                    if (running) "正在执行，尚无输出…" else "结果",
                    color = GenTheme.Dim, fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(Modifier.height(2.dp))
                Box(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(GenTheme.Panel)
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Text(
                        summary.ifBlank { if (running) "运行中，尚无输出…" else "（无输出）" },
                        color = if (isErr && summary.isNotBlank()) GenTheme.Red.copy(alpha = 0.95f)
                                else GenTheme.Text.copy(alpha = 0.85f),
                        fontSize = 10.5.sp, lineHeight = 15.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 12, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (!legacy && t?.brief?.isNotBlank() == true) {
                    Spacer(Modifier.height(4.dp))
                    Text("参数", color = GenTheme.Dim, fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.height(2.dp))
                    Text(t.brief, color = GenTheme.Text.copy(alpha = 0.6f), fontSize = 10.sp,
                        lineHeight = 14.sp, fontFamily = FontFamily.Monospace,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(GenTheme.Panel)
                            .padding(horizontal = 8.dp, vertical = 5.dp))
                }
            }
        }
    }
}

private fun fmtMsBadge(ms: Long): String = when {
    ms < 1000 -> "${ms}ms"
    ms < 60_000 -> String.format(java.util.Locale.CHINA, "%.1fs", ms / 1000.0)
    else -> "${ms / 60_000}m${(ms % 60_000) / 1000}s"
}

// ---------- 轻量 Markdown ----------

/** 按代码围栏切块：(是否代码块, 内容) 交替 */
private fun splitMd(text: String): List<Pair<Boolean, String>> {
    val out = mutableListOf<Pair<Boolean, String>>()
    val re = Regex("(?s)```[a-zA-Z]*\\n?(.*?)```")
    var last = 0
    for (m in re.findAll(text)) {
        if (m.range.first > last) out.add(false to text.substring(last, m.range.first))
        out.add(true to m.groupValues[1].removeSuffix("\n"))
        last = m.range.last + 1
    }
    if (last < text.length) out.add(false to text.substring(last))
    return out
}

/** 行内 Markdown：**粗体** / `行内代码` → AnnotatedString */
private fun inlineMd(s: String): AnnotatedString = buildAnnotatedString {
    val re = Regex("\\*\\*(.+?)\\*\\*|`([^`\n]+?)`")
    var last = 0
    for (m in re.findAll(s)) {
        if (m.range.first > last) append(s.substring(last, m.range.first))
        val b = m.groupValues[1]
        val c = m.groupValues[2]
        if (b != null) {
            pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
            append(b)
            pop()
        } else if (c != null) {
            pushStyle(SpanStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                background = Color(0x14000000)
            ))
            append(c)
            pop()
        }
        last = m.range.last + 1
    }
    if (last < s.length) append(s.substring(last))
}


// ───────────────────────── 对话内嵌浏览器渲染引擎 ─────────────────────────

/** html 分段前缀标记（内部协议） */
internal const val HTML_SEG = "\u0000HTMLSEG\u0000"

/** 把助手回复切成 [文本段 / html 段] 序列：```html 围栏 与裸 <!DOCTYPE>…</html> 文档 */
internal fun splitHtmlSegments(text: String): List<String> {
    val out = mutableListOf<String>()
    var last = 0
    val fence = Regex("(?is)```html\\s*\\n(.*?)```")
    val bare = Regex("(?is)(<!DOCTYPE html>.*?</html>)")
    val marks = mutableListOf<Triple<IntRange, Boolean, String?>>() // range, isHtml, payload
    fence.findAll(text).forEach { m ->
        marks.add(Triple(m.range, true, m.groupValues[1].trim()))
    }
    // 裸文档：剔除已被围栏覆盖的范围后追加
    val fenced = fence.findAll(text).map { it.range }.toList()
    bare.findAll(text).forEach { m ->
        val covered = fenced.any { it.intersects(m.range) }
        if (!covered) marks.add(Triple(m.range, true, m.groupValues[1].trim()))
    }
    marks.sortBy { it.first.first }
    for ((range, isHtml, payload) in marks) {
        if (range.first > last) {
            val t = text.substring(last, range.first).trim()
            if (t.isNotBlank()) out.add(t)
        }
        if (isHtml) {
            out.add(HTML_SEG + (payload ?: text.substring(range).trim()))
            last = range.last + 1
        }
    }
    if (last < text.length) {
        val t = text.substring(last).trim()
        if (t.isNotBlank()) out.add(t)
    }
    return out
}

private fun IntRange.intersects(o: IntRange) = first <= o.last && o.first <= last

/** 流式接收占位：围栏未闭合 */
@Composable
internal fun HtmlReceivingChip() {
    val alpha = rememberInfiniteTransition(label = "recv").animateFloat(
        0.35f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "a")
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
            .background(GenTheme.Panel.copy(alpha = 0.6f))
            .border(1.dp, GenTheme.Dim.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("🌐", fontSize = 13.sp)
        Spacer(Modifier.width(7.dp))
        Text("正在接收网页内容…", fontSize = 12.sp, color = GenTheme.Dim,
            modifier = Modifier.graphicsLayer { this.alpha = alpha.value })
    }
}

/**
 * 对话内嵌完整浏览器渲染引擎：系统 WebView（Chromium），CSS/JS/DOM 全开。
 * 高度自适应（onPageFinished 测 scrollHeight），可展开全高；一键转画布全屏体验。
 */
@Composable
internal fun HtmlEngineChip(
    html: String,
    onOpenCanvas: (String) -> Unit,
) {
    val ctx = LocalContext.current
    val density = LocalDensity.current
    var contentH by remember(html) { mutableStateOf<Int?>(null) }   // px
    var expanded by remember(html) { mutableStateOf(false) }
    // 诊断：资源加载失败 / JS 报错 / 页面实际渲染出的内容量
    val problems = remember(html) { androidx.compose.runtime.mutableStateListOf<String>() }
    var probe by remember(html) { mutableStateOf("") }
    val maxH = with(density) { 420.dp.toPx() }.toInt()

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .background(Color.White)
            .border(1.dp, GenTheme.Dim.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
    ) {
        // 顶栏
        Row(
            Modifier.fillMaxWidth().background(Color(0xFFF4F1EA))
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("🌐", fontSize = 11.sp)
            Spacer(Modifier.width(6.dp))
            Text(
                "完整渲染" + (contentH?.let { " · ${((it / density.density).toInt())}dp" } ?: ""),
                fontSize = 10.sp, color = GenTheme.Dim, fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f),
            )
            Text(
                if (expanded) "收起" else "展开",
                fontSize = 11.sp, color = GenTheme.Amber,
                modifier = Modifier.clickable { expanded = !expanded }.padding(horizontal = 6.dp),
            )
            Text(
                "画布 ↗",
                fontSize = 11.sp, color = GenTheme.Amber,
                modifier = Modifier.clickable { onOpenCanvas(html) }.padding(horizontal = 6.dp),
            )
        }
        // 诊断条（有异常或页面空内容时才出现）
        val diag = when {
            problems.isNotEmpty() -> "⚠ ${problems.size} 项失败：${problems.last().take(110)}"
            probe == "empty" -> "⚠ 页面已加载但内容为空（多为 JS 渲染失败/资源被拦）"
            else -> null
        }
        if (diag != null) {
            Text(
                diag,
                color = GenTheme.Red, fontSize = 9.sp, fontFamily = FontFamily.Monospace,
                maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().background(Color(0xFFFFF3F0))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
        // WebView 容器：完整引擎
        val hPx = when {
            expanded -> contentH ?: maxH
            else -> minOf(contentH ?: with(density) { 220.dp.toPx() }.toInt(), maxH)
        }
        AndroidView(
            factory = { c ->
                WebView(c).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    setBackgroundColor(android.graphics.Color.WHITE)
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(v: WebView, url: String?) {
                            v.evaluateJavascript(
                                "(function(){return document.documentElement.scrollHeight})()"
                            ) { r ->
                                val sh = r?.trim()?.removePrefix("\"")?.removeSuffix("\"")?.toFloatOrNull()?.toInt()
                                if (sh != null && sh > 0) post { contentH = sh }
                            }
                            // 空白探测：body 里既没有子节点也没有文字 → 判定空内容
                            v.evaluateJavascript(
                                "(function(){try{var b=document.body;if(!b)return 'empty';" +
                                    "var t=((b.innerText||'')+'').trim();" +
                                    "return (b.children.length===0 && t.length===0) ? 'empty' : 'ok:'+b.children.length;}catch(e){return 'empty';}})()"
                            ) { r -> post { probe = (r ?: "").trim('"').substringBefore(":") } }
                        }

                        override fun onReceivedError(
                            v: WebView, req: android.webkit.WebResourceRequest, err: android.webkit.WebResourceError
                        ) {
                            val u = req.url?.toString() ?: ""
                            post { if (problems.size < 8) problems.add("加载失败 ${err.errorCode} ${u.take(70)}") }
                        }

                        override fun onReceivedHttpError(
                            v: WebView, req: android.webkit.WebResourceRequest, resp: android.webkit.WebResourceResponse
                        ) {
                            val u = req.url?.toString() ?: ""
                            post { if (problems.size < 8) problems.add("HTTP ${resp.statusCode} ${u.take(70)}") }
                        }
                    }
                    webChromeClient = object : android.webkit.WebChromeClient() {
                        override fun onConsoleMessage(m: android.webkit.ConsoleMessage): Boolean {
                            if (m.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.ERROR) {
                                post { if (problems.size < 8) problems.add("JS: ${m.message().take(90)}") }
                            }
                            return true
                        }
                    }
                    loadDataWithBaseURL("https://genui.local/", html, "text/html", "utf-8", null)
                }
            },
            update = { /* html 变化由 remember(html) 重建处理 */ },
            modifier = Modifier.fillMaxWidth()
                .height(with(density) { hPx.toDp() }),
        )
        if (!expanded) {
            Text(
                "· 点「展开」查看全高，内容可交互（JS 已启用）",
                fontSize = 9.sp, color = GenTheme.Dim,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}


/**
 * 对话内嵌小程序卡片：MiniAppEngine 原生视图直接嵌入对话流，无 chrome、不跳独立程序。
 * 渲染失败会回显错误原因，避免黑屏无从下手。
 *
 * ⚠ 高度必须**确定**：内部的 AndroidView 用 fillMaxSize，一旦外层容器没有高度约束
 * （对话流是 verticalScroll，高度无界），fillMaxSize 在无界约束下退化成 0 高 →
 * 条目在、却什么都看不见，表现就是"对话框里没有小程序围栏"。
 * 这里给一个兜底高度（屏高 58%），并把 [modifier] 放在**外层**：调用方显式给的高度仍然优先。
 */
@Composable
internal fun MiniAppCard(
    appId: String,
    modifier: Modifier = Modifier,
    onStatus: ((String) -> Unit)? = null,
    /** 变化即销毁重建引擎实例（"重载"按钮 / 包刚落地后想再看一次）。 */
    reloadKey: Int = 0,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val fallbackHeight = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * 0.58f).dp
    var error by remember { mutableStateOf<String?>(null) }
    val viewRef = remember { mutableStateOf<com.yuanbao.miniapp.core.MiniAppView?>(null) }
    // 把引擎内部状态吐给宿主（显示在画面之外，页面空白时也能看见卡在哪一步）
    if (onStatus != null) {
        LaunchedEffect(appId, reloadKey) {
            while (true) {
                val v = viewRef.value
                onStatus(v?.debugStatus() ?: "引擎尚未创建")
                kotlinx.coroutines.delay(800)
            }
        }
    }
    // 关键：小程序引擎是自研触摸视图，嵌在对话流的滚动容器里时，
    // 父级滚动会抢走触摸事件 → 点击/滑动失灵。用 nestedScroll 让父级在手指落在小程序上时
    // 完全让出滚动（拖动 + 惯性一并消费），触摸事件原样交给引擎。
    val nsConn = remember {
        object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
            override fun onPreScroll(
                available: androidx.compose.ui.geometry.Offset,
                source: androidx.compose.ui.input.nestedscroll.NestedScrollSource
            ): androidx.compose.ui.geometry.Offset =
                if (source == androidx.compose.ui.input.nestedscroll.NestedScrollSource.UserInput)
                    available else androidx.compose.ui.geometry.Offset.Zero

            override suspend fun onPreFling(available: androidx.compose.ui.unit.Velocity) =
                available
        }
    }
    // 完全放开：卡片不附加任何固定背景/边框/标题栏，背景、配色、布局 100% 由小程序自己决定。
    // 高度：先铺满宽度，再给兜底高度（内层，调用方显式高度优先），最后叠调用方的 modifier。
    Box(
        Modifier.fillMaxWidth().then(modifier).height(fallbackHeight).nestedScroll(nsConn)
    ) {
        if (error != null) {
            Text(
                "⚠ $error",
                color = GenTheme.Amber, fontSize = 12.sp,
                modifier = Modifier.align(Alignment.Center).padding(16.dp)
            )
        }
        key(appId, reloadKey) {
        AndroidView(
            factory = { c ->
                val view = com.yuanbao.miniapp.core.MiniAppEngine.createResolved(c, appId)
                if (view == null) {
                    error = "小程序 $appId 不存在"
                    onStatus?.invoke("⚠ 小程序 $appId 不存在（用户目录与内置 assets 均未找到）")
                    android.widget.TextView(c).apply {
                        text = "小程序 $appId 不存在"
                        setTextColor(0xFFD9A05B.toInt()); textSize = 13f
                        gravity = android.view.Gravity.CENTER
                    }
                } else {
                    view.isClickable = true
                    view.isFocusable = true
                    view.setErrorListener { msg ->
                        error = msg
                        onStatus?.invoke("⚠ " + msg.replace('\n', ' ').take(120))
                    }
                    viewRef.value = view
                    view
                }
            },
            modifier = Modifier.fillMaxSize(),
            onRelease = { view ->
                (view as? com.yuanbao.miniapp.core.MiniAppView)?.let {
                    runCatching { com.yuanbao.miniapp.core.MiniAppEngine.destroy(it) }
                }
            }
        )
        }
    }
}

/**
 * 对话内小程序条目：全宽贴边渲染（背景不再被消息栏"围栏"框住）+ 信息/诊断条 + ⛶ 全屏入口。
 * 诊断行刻意放在画面之外（画面层满铺整个卡片，避免与内容重叠干扰）。
 */
@Composable
internal fun androidx.compose.foundation.lazy.LazyItemScope.MiniAppRow(appId: String) {
    val ctx = LocalContext.current
    val cfg = androidx.compose.ui.platform.LocalConfiguration.current
    var status by remember(appId) { mutableStateOf("引擎初始化中…") }
    val cardHeight = (cfg.screenHeightDp * 0.58f).dp
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "▦ " + appId,
                color = GenTheme.Dim, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                "⛶ 全屏",
                color = GenTheme.Amber, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable {
                        runCatching {
                            ctx.startActivity(
                                android.content.Intent(ctx, GenUiMiniAppActivity::class.java)
                                    .putExtra("appId", appId)
                                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    }
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
        Text(
            status,
            color = if (status.startsWith("⚠")) GenTheme.Red else GenTheme.Dim,
            fontSize = 9.sp, fontFamily = FontFamily.Monospace,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 1.dp),
        )
        MiniAppCard(appId, Modifier.fillMaxWidth().height(cardHeight), onStatus = { status = it })
    }
}