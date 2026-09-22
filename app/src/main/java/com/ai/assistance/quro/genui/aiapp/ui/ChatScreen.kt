package com.ai.assistance.quro.genui.aiapp.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.DrawerValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.CompositionLocalProvider
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.geometry.Offset
import com.ai.assistance.quro.genui.sdk.components.PetSpec
import com.ai.assistance.quro.genui.sdk.components.PetPhase
import com.ai.assistance.quro.genui.sdk.components.PetSprite
import kotlin.math.abs
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
// R 类归属宿主 App 模块（namespace = com.ai.assistance.quro），
// 不是 aiapp 子包；上游的 com.genui.aiapp.R 已随去品牌化一并改到宿主根命名空间。
import com.ai.assistance.quro.R
import com.ai.assistance.quro.genui.aiapp.agent.ui.AgentThinkingPanel
import com.ai.assistance.quro.genui.aiapp.host.AiActionHost
import com.ai.assistance.quro.genui.aiapp.viewmodel.ChatState
import com.ai.assistance.quro.genui.aiapp.viewmodel.ChatViewModel
import com.ai.assistance.quro.genui.aiapp.viewmodel.ToolCallRecord
import com.ai.assistance.quro.genui.sdk.GenUI

/**
 * GenUI 生成式 UI 画布 — 纯画布版
 *
 * 核心定位：这是画布，不是对话框
 * - 画布全屏，不被任何固定栏截断
 * - 输入栏浮动，不占固定空间（点击空白处或底部弹出）
 * - 操作按钮浮动在画布上
 * - 思考过程、工具调用全部在独立底部抽屉
 *
 * 画布就是画布，Agent 有自己的工作界面，不混在画布上
 */
/** 全屏层枚举（一个按钮一层） */
private enum class Layer { DRAWER, SETTINGS, PET, HISTORY }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    modifier: Modifier = Modifier
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val actionHost = remember { AiActionHost(context) }
    // 多级界面：open_screen 压栈 / goBack 与系统返回键弹栈
    actionHost.onOpenScreen = { spec -> viewModel.pushPage(spec) }
    actionHost.onGoBack = {
        actionHost.stopMedia()
        if (state.channel != null) viewModel.closeChannel() else viewModel.popPage()
    }
    // 对话回传：界面上的选择/落子 → 自动作为新消息继续编排
    actionHost.onSendMessage = { text ->
        // canSend 同时挡住「生成中」和「正在等用户选渲染通道」，避免压进第二个问题
        if (text.isNotBlank() && state.canSend) {
            viewModel.send(text)
        }
    }
    var inputText by remember { mutableStateOf("") }

    // 思考面板状态
    var showThinkingPanel by remember { mutableStateOf(false) }
    // sheetState 随开随建：与 showThinkingPanel 同生命周期（修复：sheet 被 M3 内部返回关闭后
    // 状态残留 → hasBackLayer 恒 true → 连按返回误弹其它层）
    val sheetState = if (showThinkingPanel) rememberModalBottomSheetState(skipPartiallyExpanded = true) else null

    var layerStack by remember { mutableStateOf(listOf<Layer>()) }
    // 抽屉是栈的一层：从抽屉进任何界面，DRAWER 保留在栈里，
    // 返回时弹掉目标层 → 露出的就是打开着的抽屉（而不是主界面）
    fun openLayer(l: Layer) { layerStack = layerStack + l }
    fun popLayer() { if (layerStack.isNotEmpty()) layerStack = layerStack.dropLast(1) }
    val topLayer = layerStack.lastOrNull()
    val showSettingsScreen = topLayer == Layer.SETTINGS
    val showPetSettings = topLayer == Layer.PET
    val showHistoryScreen = topLayer == Layer.HISTORY
    // 右侧输入侧边栏（RightDrawerCompose 四锁手势：位置/轴向/方向/速度锁）
    val drawerAnimateTarget = remember { mutableStateOf<Float?>(null) }
    val openSidePanel: () -> Unit = {
        drawerAnimateTarget.value = 1f
        if (layerStack.lastOrNull() != Layer.DRAWER) layerStack = layerStack + Layer.DRAWER
    }
    val closeSidePanel: () -> Unit = {
        drawerAnimateTarget.value = 0f
        if (layerStack.lastOrNull() == Layer.DRAWER) layerStack = layerStack.dropLast(1)
    }
    val isSidePanelOpen = remember { mutableStateOf(false) }
    // 「每轮询问渲染通道」开关的本地镜像（真值落 SharedPreferences，由 ViewModel 读写）
    var askChannelOn by remember { mutableStateOf(viewModel.askChannelEachTurn()) }
    // 根布局尺寸（宠物拖动 clamp 用）
    var rootSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    // 漂浮宠物：位置 / 定义 / 设置（持久化于 PetPrefs）
    var petOffset by remember { mutableStateOf(Offset(60f, 640f)) }
    var petPlaced by remember { mutableStateOf(false) }
    var petSettings by remember { mutableStateOf(PetPrefs.load(context)) }
    val petSpec = petSettings.current
    androidx.compose.runtime.LaunchedEffect(rootSize) {
        if (!petPlaced && rootSize.height > 0) {
            petOffset = Offset(40f, rootSize.height * 0.52f)
            petPlaced = true
        }
    }


    // 新思考提示脉冲状态
    var hasNewThought by remember { mutableStateOf(false) }
    // ══ 全屏层导航栈：一个按钮一层，进入压栈、返回弹栈（独立成层，不是优先级链）══

    // 历史作品 / 灵魂注入 抽屉（层由 layerStack 驱动）
    val thoughtCount = state.thoughtChain?.steps?.size ?: 0
    LaunchedEffect(thoughtCount) {
        if (thoughtCount > 0 && state.isStreaming) {
            hasNewThought = true
        }
    }
    LaunchedEffect(showThinkingPanel) {
        if (showThinkingPanel) {
            hasNewThought = false
        }
    }

    // 纯画布：全屏，不截栏
    // 背景渐变保持全屏（statusBarsPadding 在 background 之后），
    // 内容则避开状态栏，防止页头钻到系统时钟底下
    // 系统返回逐层出栈：设置屏 → 宠物设置 → 思考面板 → 侧边栏 → 通道页 → 页栈 → 默认
    // 统一返回出栈：按 UI 层级从顶往下，一次只退一层（多入口进入也不乱）
    // 统一返回：永远只弹「栈顶那一层」。全屏层是 layerStack（一个按钮一层，层内可继续压栈），
    // 其余是独立的单层状态（drawer/思考/通道/画布/页栈）。
    // 思考面板的返回完全交给 M3 ModalBottomSheet 自身（sheetState 随开随建，
    // onDismissRequest 已同步 showThinkingPanel）——此处不再重复处理，消除双处理器竞态。
    // 剩余层：layerStack（全屏层栈）→ drawer → 通道 → 画布 → 页栈，一次弹一层。
    // 弹栈时如果弹的是 DRAWER，同步收抽屉动画
    fun popLayerAndSync() {
        val top = layerStack.lastOrNull()
        if (top == Layer.DRAWER) closeSidePanel() else popLayer()
    }
    val hasBackLayer = layerStack.isNotEmpty() ||
            state.channel != null || (state.hasUI && state.currentGenUI != null) ||
            state.pageStack.isNotEmpty()
    androidx.activity.compose.BackHandler(enabled = hasBackLayer) {
        when {
            layerStack.isNotEmpty() -> popLayerAndSync()
            state.channel != null -> viewModel.closeChannel()
            state.hasUI && state.currentGenUI != null -> viewModel.closeCanvas()
            state.pageStack.isNotEmpty() -> viewModel.popPage()
            else -> Unit
        }
    }

    // RightDrawerLayout：[内容区, 右抽屉] 两个子槽
    com.ai.assistance.quro.genui.aiapp.widget.RightDrawerCompose(
        onOpenStateChange = { open ->
            isSidePanelOpen.value = open
            // 手势关抽屉 → 同步弹 DRAWER 层（保持栈与视觉一致）
            if (!open && layerStack.lastOrNull() == Layer.DRAWER) {
                layerStack = layerStack.dropLast(1)
            }
        },
        drawerCommand = { val t = drawerAnimateTarget.value; drawerAnimateTarget.value = null; t },
        drawer = {
            androidx.compose.material3.Surface(
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 12.dp
            ) {
            SidePanelContent(
                            state = state,
                            works = state.works,
                            onReplayWork = { w ->
                                closeSidePanel()
                                viewModel.restoreWork(w)
                            },
                            inputText = inputText,
                            onTextChange = { inputText = it },
                            onSend = {
                                if (inputText.isNotBlank() && state.canSend) {
                                    viewModel.send(inputText.trim())
                                    inputText = ""
                                    closeSidePanel()   // 发送即收，画布回到全屏
                                }
                            },
                            onStop = { viewModel.stop() },
                            onClear = { viewModel.clear(); closeSidePanel() },
                            onSettingsClick = { openLayer(Layer.SETTINGS) },
                            onThinkingClick = { showThinkingPanel = true },
                            onPetImport = { openLayer(Layer.PET) },
                            onSendPrompt = { p ->
                                closeSidePanel()
                                viewModel.send(p)   // 快捷词直接发送
                            },
                            hasNewThought = hasNewThought
                        )
        }
        }

    ) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.background,
                        MaterialTheme.colorScheme.surface
                    )
                )
            )
            .statusBarsPadding()
            .onGloballyPositioned { rootSize = it.size }
    ) {
        // ── 画布主体（全屏，不被截断）──────────────────────
        when {
            // 多通道内容（Markdown / A2UI / HTML）— 直接画在画布里
            state.channel != null -> {
                com.ai.assistance.quro.genui.aiapp.renderx.ChannelViewer(
                    page = state.channel!!,
                    embedded = true,
                    modifier = Modifier.fillMaxSize(),
                    onBack = { viewModel.closeChannel() },
                    // A2UI 按钮点击 → 作为新用户消息发出（AI 接着响应，如「再来一条」）
                    onAction = { label ->
                        if (label.isNotBlank()) {
                            viewModel.closeChannel()
                            viewModel.send(label)
                        }
                    }
                )
                if (state.pageStack.isNotEmpty() || true) {
                    // 返回胶囊复用下方通用实现
                }
            }

            // 有 UI — 全屏渲染
            state.hasUI && state.currentGenUI != null -> {
                GenUICanvas(
                    json = state.pageStack.lastOrNull() ?: state.currentGenUI!!,
                    host = actionHost,
                    isStreaming = state.isStreaming,
                    modifier = Modifier.fillMaxSize()
                )
                // 流式时在右下角显示迷你指示器
                if (state.isStreaming) {
                    StreamingMiniIndicator(
                        onClick = { showThinkingPanel = true }
                    )
                }
            }

            // 没有 UI + 正在生成 — 极简加载
            state.awaitingChannel -> {
                ChannelAskingPlaceholder()
            }

            state.isStreaming -> {
                MinimalLoadingPlaceholder()
            }

            // 没有 UI + 有错误
            state.error != null -> {
                ErrorPlaceholder(
                    error = state.error!!,
                    onRetry = {
                        if (state.currentRequest.isNotBlank()) {
                            viewModel.send(state.currentRequest)
                        }
                    },
                    onViewDetails = { showThinkingPanel = true }
                )
            }

            // 空状态
            else -> {
                WelcomePlaceholder(
                    onQuickPrompt = { prompt ->
                        inputText = prompt
                        viewModel.send(prompt)
                    }
                )
            }
        }

        // ── 多级界面返回按钮（左上角，系统返回之外的可视保障）──────────────────
        if (state.pageStack.isNotEmpty() || state.channel != null) {
            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                shape = RoundedCornerShape(999.dp),
                tonalElevation = 3.dp,
                shadowElevation = 3.dp,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .clickable { actionHost.stopMedia(); if (state.channel != null) viewModel.closeChannel() else viewModel.popPage() }
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(text = "←", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        text = "返回",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
            }
        }

        // ── 漂浮宠物（可自由拖动；点击开侧边栏；按 AI 状态播报）──
        val petPhase = when {
            !state.isStreaming && state.streamingText.isNotBlank() -> PetPhase.DONE
            state.isStreaming && state.streamingText.isNotBlank() -> PetPhase.GENERATING
            state.isStreaming && state.toolCallRecords.lastOrNull()?.result?.isBlank() == true ->
                PetPhase.TOOL_CALLING
            state.isStreaming && state.thoughtChain != null -> PetPhase.THINKING
            else -> PetPhase.IDLE
        }
        val lastTool = state.toolCallRecords.lastOrNull()?.name
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .offset { IntOffset(petOffset.x.toInt(), petOffset.y.toInt()) }
                // 轻点 → 开侧边栏（可关）；长按 → AI 互动（可关）；拖动 → 移动
                .pointerInput(petSettings.tapOpensPanel, petSettings.tapInteract) {
                    detectTapGestures(
                        onTap = { if (petSettings.tapOpensPanel) openSidePanel() },
                        onLongPress = {
                            if (petSettings.tapInteract) {
                                viewModel.send("（我摸了摸宠物）")
                            }
                        }
                    )
                }
                .pointerInput(Unit) {
                    detectDragGestures { change, drag ->
                        petOffset = Offset(
                            (petOffset.x + drag.x).coerceIn(0f, (rootSize.width - 200f).coerceAtLeast(0f)),
                            (petOffset.y + drag.y).coerceIn(0f, (rootSize.height - 200f).coerceAtLeast(0f))
                        )
                        change.consume()
                    }
                }
        ) {
            if (petSettings.petVisible) {
                PetSprite(petSpec, petPhase, toolName = lastTool, showBubble = petSettings.bubbleEnabled)
            }
        }


    }

    // 历史作品抽屉
    // 设置页（身份与模型信息 / 历史对话）
    if (showSettingsScreen) {
        Surface(Modifier.fillMaxSize()) {
            SettingsScreen(
                worksCount = state.works.size,
                personaName = state.hostPersonaName.ifBlank { "跟随 ZorvAI" },
                modelLabel = state.modelLabel.ifBlank { "跟随 ZorvAI" },
                askChannel = askChannelOn,
                onToggleAskChannel = { on ->
                    askChannelOn = on
                    viewModel.setAskChannelEachTurn(on)
                },
                onBack = { popLayer() },
                onOpenHistory = { openLayer(Layer.HISTORY) }
            )
        }
    }

    // 历史对话页（界面回放 + 往来记录）
    if (showHistoryScreen) {
        Surface(Modifier.fillMaxSize()) {
            HistoryScreen(
                works = state.works,
                messages = state.conversationHistory,
                personaName = state.hostPersonaName.ifBlank { "跟随 ZorvAI" },
                stripAssistant = { viewModel.stripAssistantForDisplay(it) },
                formatTime = { formatTimestamp(it) },
                onBack = { popLayer() },
                onReplay = { w ->
                    viewModel.restoreWork(w)
                    popLayer()
                }
            )
        }
    }

    // 多通道合体：通道内容直接作为画布主体渲染（Markdown / A2UI / HTML）

    // 宠物设置屏（全屏）
    if (showPetSettings) {
        Surface(Modifier.fillMaxSize()) {
            PetSettingsScreen(
                settings = petSettings,
                onSettingsChange = {
                    petSettings = it
                    PetPrefs.save(context, it)
                },
                onBack = { popLayer() }
            )
        }
    }

    // 思考面板 — 独立底部抽屉（sheetState 随开随建）
    if (showThinkingPanel && sheetState != null) {
        ThinkingBottomSheet(
            state = state,
            sheetState = sheetState!!,
            onDismiss = { showThinkingPanel = false }
        )
    }
    }
}

// ============================================================================
// 浮动操作按钮组 — 不截画布
// ============================================================================

/**
 * 右侧输入侧边栏内容（重设计）：
 * 功能卡 2×2（思考/清空/设置/宠物）→ 快捷词直达列表 → 底部输入栏。无空白区。
 */
@Composable
private fun SidePanelContent(
    state: ChatState,
    works: List<com.ai.assistance.quro.genui.aiapp.data.GenUISessionStore.WorkItem>,
    onReplayWork: (com.ai.assistance.quro.genui.aiapp.data.GenUISessionStore.WorkItem) -> Unit,
    inputText: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onSendPrompt: (String) -> Unit,
    onStop: () -> Unit,
    onClear: () -> Unit,
    onSettingsClick: () -> Unit,
    onThinkingClick: () -> Unit,
    onPetImport: () -> Unit,
    hasNewThought: Boolean
) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier = Modifier.fillMaxSize().padding(14.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("快捷操作", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = cs.onSurfaceVariant)
        // 功能卡 2×2
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            PanelActionCard("思考面板", "查看推理过程", Modifier.weight(1f)) { onThinkingClick() }
            PanelActionCard("设置中心", "身份 / 模型 / 历史", Modifier.weight(1f)) { onSettingsClick() }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            PanelActionCard("宠物管理", "切换/导入导出", Modifier.weight(1f)) { onPetImport() }
            PanelActionCard("清空画布", "回到空白状态", Modifier.weight(1f)) { onClear() }
        }
        Spacer(Modifier.height(4.dp))
        // ── 历史记录（独立滚动区占满剩余空间，输入栏固定底部永不遮挡）──
        Text("历史记录", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = cs.onSurfaceVariant)
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (works.isEmpty()) {
                Text("暂无界面记录", fontSize = 12.sp, color = cs.onSurfaceVariant)
            }
            works.take(8).forEach { w ->
                Surface(
                    color = cs.surfaceVariant.copy(alpha = 0.92f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().clickable { onReplayWork(w) }
                ) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                        // 渲染类型徽章 + 标题（老数据标题里的「[通道] 」前缀剥掉，有徽章了）
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RenderTypeBadge(w.channel, dense = true)
                            Spacer(Modifier.width(5.dp))
                            Text(
                                w.title.removePrefix("[通道] ").ifBlank { w.request.take(24) },
                                fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1
                            )
                        }
                        Text(
                            java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA).format(java.util.Date(w.time)),
                            fontSize = 10.sp, color = cs.onSurfaceVariant
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(2.dp))
        FloatingInputBar(
            text = inputText,
            onTextChange = onTextChange,
            onSend = onSend,
            onStop = onStop,
            isStreaming = state.isStreaming,
            enabled = state.canSend,
            onQuickPrompt = onTextChange
        )
    }
}

@Composable
private fun PanelActionCard(title: String, sub: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(
        color = cs.surfaceVariant.copy(alpha = 0.92f),
        shape = RoundedCornerShape(14.dp),
        modifier = modifier.clickable { onClick() }
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface)
            Text(sub, fontSize = 11.sp, color = cs.onSurfaceVariant)
        }
    }
}

@Composable
private fun FloatingActionBar(
    state: ChatState,
    onClear: () -> Unit,
    onSettingsClick: () -> Unit,
    onThinkingClick: () -> Unit,
    onHistory: () -> Unit = {},
    onSoul: () -> Unit = {},
    hasUI: Boolean,
    hasNewThought: Boolean,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 思考按钮
        ThinkingButton(
            onClick = onThinkingClick,
            hasNewThought = hasNewThought,
            enabled = state.thoughtChain != null ||
                    state.toolCallRecords.isNotEmpty() ||
                    state.streamingText.isNotBlank()
        )
        if (hasUI) {
            GenUIconButton(
                iconRes = R.drawable.genui_clear,
                contentDescription = "清除",
                onClick = onClear
            )
        }
        GenUIconButton(
            iconRes = R.drawable.genui_settings,
            contentDescription = "设置",
            onClick = onSettingsClick
        )
    }
}

// ============================================================================
// 思考面板 — 底部抽屉
// ============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThinkingBottomSheet(
    state: ChatState,
    sheetState: androidx.compose.material3.SheetState,
    onDismiss: () -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("思考过程", "工具调用", "原始输出")

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        scrimColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.32f),
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 12.dp, bottom = 8.dp)
                    .width(40.dp)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.outlineVariant)
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
        ) {
            Text(
                text = "AI 思考面板",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
            )

            Spacer(Modifier.height(12.dp))

            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.primary,
                indicator = { tabPositions ->
                    TabRowDefaults.SecondaryIndicator(
                        modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                        height = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                },
                divider = {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(0.5.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant)
                    )
                }
            ) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = if (selectedTab == index) FontWeight.SemiBold else FontWeight.Medium
                            )
                        },
                        selectedContentColor = MaterialTheme.colorScheme.primary,
                        unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(500.dp)
            ) {
                when (selectedTab) {
                    0 -> ThinkingTabContent(state = state)
                    1 -> ToolCallsTabContent(records = state.toolCallRecords)
                    2 -> RawOutputTabContent(state = state)
                }
            }
        }
    }
}

/**
 * Tab 1: 思考过程
 */
@Composable
private fun ThinkingTabContent(state: ChatState) {
    val chain = state.thoughtChain
    if (chain != null && chain.steps.isNotEmpty()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                AgentThinkingPanel(
                    chain = chain,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (state.streamingReasoning.isNotBlank()) {
                item {
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text(
                                text = "实时推理",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = state.streamingReasoning,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                lineHeight = 18.sp
                            )
                        }
                    }
                }
            }
        }
    } else if (state.streamingReasoning.isNotBlank()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "实时推理",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = state.streamingReasoning,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 18.sp
                    )
                }
            }
        }
    } else {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    painter = painterResource(id = R.drawable.genui_spark),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier.size(48.dp)
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "暂无思考过程",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Tab 2: 工具调用记录
 */
@Composable
private fun ToolCallsTabContent(records: List<ToolCallRecord>) {
    if (records.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                    shape = CircleShape,
                    modifier = Modifier.size(56.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(text = "🔧", fontSize = 24.sp)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "暂无工具调用",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    } else {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(records) { record ->
                ToolCallRecordItem(record = record)
            }
        }
    }
}

/**
 * 单条工具调用记录
 */
@Composable
private fun ToolCallRecordItem(record: ToolCallRecord) {
    var expanded by remember { mutableStateOf(false) }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 0.dp,
        shadowElevation = 1.dp,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Surface(
                    color = if (record.isSuccess) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.errorContainer
                    },
                    shape = CircleShape,
                    modifier = Modifier.size(28.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = if (record.isSuccess) "✓" else "×",
                            color = if (record.isSuccess) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = record.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = formatTimestamp(record.timestamp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn(tween(200)) + slideInVertically(tween(200)) { -it / 2 },
                exit = fadeOut(tween(150)) + slideOutVertically(tween(150)) { -it / 2 }
            ) {
                Column(modifier = Modifier.padding(top = 10.dp)) {
                    Text(
                        text = "参数",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = record.arguments.takeIf { it.isNotBlank() } ?: "(无参数)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(10.dp),
                            maxLines = 5,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "结果",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = record.result,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (record.isSuccess) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                            modifier = Modifier.padding(10.dp),
                            maxLines = 10,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

private fun formatTimestamp(ts: Long): String {
    val diff = System.currentTimeMillis() - ts
    return when {
        diff < 1000 -> "刚刚"
        diff < 60000 -> "${diff / 1000} 秒前"
        diff < 3600000 -> "${diff / 60000} 分钟前"
        else -> "${diff / 3600000} 小时前"
    }
}

/**
 * Tab 3: 原始输出
 */
@Composable
private fun RawOutputTabContent(state: ChatState) {
    val rawText = when {
        state.isStreaming && state.streamingText.isNotBlank() -> state.streamingText
        state.currentGenUI != null -> state.currentGenUI
        state.error != null -> state.error
        else -> ""
    }

    // 画布诊断信息（置顶显示）
    if (!state.debugInfo.isNullOrBlank()) {
        Surface(
            color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.6f),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
        ) {
            Text(
                text = state.debugInfo,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.padding(10.dp)
            )
        }
    }

    if (rawText.isBlank()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    painter = painterResource(id = R.drawable.genui_spark),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier.size(48.dp)
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "暂无原始输出",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = rawText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(12.dp)
                        .verticalScroll(rememberScrollState()),
                    lineHeight = 18.sp
                )
            }
        }
    }
}

// ============================================================================
// 浮动按钮组件
// ============================================================================

@Composable
private fun ThinkingButton(
    onClick: () -> Unit,
    hasNewThought: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }

    val pulseScale by animateFloatAsState(
        targetValue = if (hasNewThought) 1.15f else 1f,
        animationSpec = if (hasNewThought) {
            infiniteRepeatable(
                animation = tween(800),
                repeatMode = RepeatMode.Reverse
            )
        } else {
            tween(300)
        },
        label = "thinking_pulse"
    )

    Box(
        modifier = modifier
            .size(36.dp)
            .clip(CircleShape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
                enabled = enabled
            )
            .background(
                MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Rounded.Psychology,
            contentDescription = "思考",
            tint = if (enabled) {
                if (hasNewThought) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
            },
            modifier = Modifier
                .size(18.dp)
                .scale(pulseScale)
        )
    }
}

@Composable
private fun GenUIconButton(
    iconRes: Int,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .size(36.dp)
            .clip(CircleShape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(id = iconRes),
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp)
        )
    }
}

// ============================================================================
// 全屏 GenUI 渲染
// ============================================================================

@Composable
private fun GenUICanvas(
    json: String,
    host: AiActionHost,
    isStreaming: Boolean = false,
    modifier: Modifier = Modifier
) {
    val currentSpec = remember(json, isStreaming) {
        if (isStreaming) {
            runCatching { GenUI.safeParseStreaming(json, "AI 正在生成界面...") }.getOrNull()
        } else {
            runCatching { GenUI.safeParse(json) }.getOrNull()
        }
    }

    var lastValidSpec by remember { mutableStateOf<com.ai.assistance.quro.genui.sdk.dsl.UISpec?>(null) }

    SideEffect {
        if (isStreaming) {
            val currentIsValid = currentSpec != null &&
                    currentSpec.root.type.isNotBlank() &&
                    currentSpec.schemaVersion != "streaming"
            if (currentIsValid) {
                lastValidSpec = currentSpec
            }
        } else {
            lastValidSpec = null
        }
    }

    val spec = if (isStreaming) {
        val currentIsValid = currentSpec != null &&
                currentSpec.root.type.isNotBlank() &&
                currentSpec.schemaVersion != "streaming"

        if (currentIsValid) {
            currentSpec
        } else {
            lastValidSpec ?: currentSpec
        }
    } else {
        currentSpec
    }

    if (isStreaming) {
        val isLoadingUI = spec == null ||
                spec.root.type.isBlank() ||
                spec.schemaVersion == "streaming"
        if (isLoadingUI) {
            StreamingLoadingPlaceholder(
                modifier = modifier
            )
            return
        }
    }

    if (spec != null && spec.root.type.isNotBlank()) {
        GenUI.Screen(
            spec = spec,
            host = host,
            modifier = modifier
        )
    } else {
        Column(
            modifier = modifier
                .padding(20.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                shape = RoundedCornerShape(14.dp),
                tonalElevation = 0.dp,
                shadowElevation = 1.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            color = MaterialTheme.colorScheme.error,
                            shape = CircleShape,
                            modifier = Modifier.size(28.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = "!",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp
                                )
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = "UI 解析失败",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "AI 返回的数据格式不正确，请重试或调整描述。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * 流式加载中占位
 */
@Composable
private fun StreamingLoadingPlaceholder(
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                tonalElevation = 0.dp,
                shadowElevation = 2.dp,
                modifier = Modifier.size(56.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        painter = painterResource(id = R.drawable.genui_spark),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            GeneratingDots(
                color = MaterialTheme.colorScheme.primary,
                size = 6.dp
            )
        }
    }
}

// ============================================================================
// 流式迷你指示器
// ============================================================================

@Composable
private fun StreamingMiniIndicator(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomEnd
    ) {
        AnimatedVisibility(
            visible = true,
            enter = fadeIn(tween(300)) + slideInVertically(tween(300)) { it / 2 },
            exit = fadeOut(tween(200)) + slideOutVertically(tween(200)) { it / 2 }
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                shape = RoundedCornerShape(20.dp),
                tonalElevation = 0.dp,
                shadowElevation = 3.dp,
                modifier = Modifier
                    .padding(end = 16.dp, bottom = 76.dp)
                    .clickable(onClick = onClick)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    GeneratingDots(
                        color = MaterialTheme.colorScheme.primary,
                        size = 4.dp
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "生成中",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

/**
 * 三个跳动小圆点
 */
@Composable
private fun GeneratingDots(
    color: Color,
    size: androidx.compose.ui.unit.Dp
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(3) { index ->
            val scale by animateFloatAsState(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = 600,
                    delayMillis = index * 200
                ),
                label = "dot_$index"
            )
            Box(
                modifier = Modifier
                    .size(size)
                    .scale(scale)
                    .clip(CircleShape)
                    .background(color)
            )
        }
    }
}

// ============================================================================
// 极简加载占位
// ============================================================================

/**
 * 「正在等你选渲染通道」占位。
 *
 * 正常情况下用户看到的是可视化询问弹窗，这块只是它背后的底衬 ——
 * 万一弹窗没弹出（例如界面在后台等），至少不是一片空白。
 */
@Composable
private fun ChannelAskingPlaceholder(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("🎛", fontSize = 34.sp)
            Spacer(Modifier.height(12.dp))
            Text("请选择本次渲染通道", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(
                "GenUI SDK / A2UI / Markdown / HTML —— 选完即开始生成",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun MinimalLoadingPlaceholder(
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "loading_pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                tonalElevation = 0.dp,
                shadowElevation = 2.dp,
                modifier = Modifier.size(64.dp).scale(pulseScale)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        painter = painterResource(id = R.drawable.genui_spark),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            GeneratingDots(
                color = MaterialTheme.colorScheme.primary,
                size = 6.dp
            )
        }
    }
}

// ============================================================================
// 欢迎页
// ============================================================================

@Composable
private fun WelcomePlaceholder(
    onQuickPrompt: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.primary,
            tonalElevation = 0.dp,
            shadowElevation = 6.dp,
            modifier = Modifier.size(96.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                                    Color.Transparent
                                )
                            )
                        )
                )
                Icon(
                    painter = painterResource(id = R.drawable.genui_logo),
                    contentDescription = null,
                    tint = Color.Unspecified,
                    modifier = Modifier.size(56.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(28.dp))

        Text(
            text = "GenUI 生成式画布",
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = "我用界面跟你对话，说点什么吧",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(36.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            CapabilityCard(
                iconRes = R.drawable.genui_layers,
                title = "UI 即回复",
                subtitle = "用界面回答",
                modifier = Modifier.weight(1f)
            )
            CapabilityCard(
                iconRes = R.drawable.genui_palette,
                title = "精美设计",
                subtitle = "精致细节",
                modifier = Modifier.weight(1f)
            )
            CapabilityCard(
                iconRes = R.drawable.genui_spark,
                title = "实时生成",
                subtitle = "即想即得",
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "试试这些",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .height(1.dp)
                    .weight(1f)
                    .background(MaterialTheme.colorScheme.outlineVariant)
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        val prompts = listOf(
            Triple("今天天气怎么样", R.drawable.genui_weather, "weather"),
            Triple("讲个笑话听听", R.drawable.genui_joke, "joke"),
            Triple("帮我算个账", R.drawable.genui_calc, "calc"),
            Triple("推荐一首音乐", R.drawable.genui_music, "music"),
            Triple("今日运势", R.drawable.genui_fortune, "fortune"),
            Triple("说晚安", R.drawable.genui_night, "night")
        )

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            prompts.chunked(2).forEach { rowPrompts ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    rowPrompts.forEach { (prompt, iconRes, _) ->
                        QuickPromptCard(
                            prompt = prompt,
                            iconRes = iconRes,
                            onClick = { onQuickPrompt(prompt) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(28.dp))

        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f),
            shape = RoundedCornerShape(12.dp),
            tonalElevation = 0.dp
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.secondary,
                    shape = CircleShape,
                    modifier = Modifier.size(18.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = "i",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "试着描述具体的颜色、布局、功能",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }
    }
}

@Composable
private fun CapabilityCard(
    iconRes: Int,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 0.dp,
        shadowElevation = 1.dp,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(vertical = 16.dp, horizontal = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.size(42.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        painter = painterResource(id = iconRes),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun QuickPromptCard(
    prompt: String,
    iconRes: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(14.dp),
        tonalElevation = 0.dp,
        shadowElevation = 1.dp,
        modifier = modifier
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = CircleShape,
                modifier = Modifier.size(36.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        painter = painterResource(id = iconRes),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Text(
                text = prompt,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            Icon(
                painter = painterResource(id = R.drawable.genui_clear),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

// ============================================================================
// 错误状态
// ============================================================================

@Composable
private fun ErrorPlaceholder(
    error: String,
    onRetry: () -> Unit,
    onViewDetails: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(28.dp)
        ) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(20.dp),
                tonalElevation = 0.dp,
                shadowElevation = 2.dp,
                modifier = Modifier.size(64.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = "!",
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = "生成失败",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = error.take(80) + if (error.length > 80) "..." else "",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(24.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                    shape = RoundedCornerShape(14.dp),
                    tonalElevation = 0.dp,
                    modifier = Modifier.clickable(onClick = onViewDetails)
                ) {
                    Text(
                        text = "查看详情",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                    )
                }
                Surface(
                    color = MaterialTheme.colorScheme.primary,
                    shape = RoundedCornerShape(14.dp),
                    tonalElevation = 0.dp,
                    shadowElevation = 2.dp,
                    modifier = Modifier.clickable(onClick = onRetry)
                ) {
                    Text(
                        text = "重试",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.padding(horizontal = 28.dp, vertical = 12.dp)
                    )
                }
            }
        }
    }
}

// ============================================================================
// 浮动输入栏 — 不占固定空间，浮在画布上
// ============================================================================

@Composable
private fun FloatingInputBar(
    pageHidden: com.ai.assistance.quro.genui.aiapp.renderx.ChannelPage? = null,
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    isStreaming: Boolean,
    enabled: Boolean,
    onQuickPrompt: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding()
    ) {


        // 输入框 — 浮动卡片样式
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(24.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.95f))
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                        shape = RoundedCornerShape(24.dp)
                    )
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                BasicTextField(
                    value = text,
                    onValueChange = onTextChange,
                    enabled = enabled,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onBackground
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    decorationBox = { innerTextField ->
                        Box {
                            if (text.isEmpty()) {
                                Text(
                                    text = "说点什么...",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                )
                            }
                            innerTextField()
                        }
                    }
                )
            }

            Spacer(modifier = Modifier.width(10.dp))

            if (isStreaming) {
                Surface(
                    color = MaterialTheme.colorScheme.error,
                    shape = CircleShape,
                    tonalElevation = 0.dp,
                    shadowElevation = 2.dp,
                    modifier = Modifier
                        .size(42.dp)
                        .clickable(onClick = onStop)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            painter = painterResource(id = R.drawable.genui_stop),
                            contentDescription = "停止",
                            tint = Color.Unspecified,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            } else {
                val sendEnabled = text.isNotBlank() && enabled
                Surface(
                    color = if (sendEnabled) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceVariant,
                    shape = CircleShape,
                    tonalElevation = 0.dp,
                    shadowElevation = if (sendEnabled) 2.dp else 0.dp,
                    modifier = Modifier
                        .size(42.dp)
                        .then(
                            if (sendEnabled) Modifier.clickable(onClick = onSend)
                            else Modifier
                        )
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            painter = painterResource(id = R.drawable.genui_send),
                            contentDescription = "生成",
                            tint = if (sendEnabled) Color.Unspecified
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}
