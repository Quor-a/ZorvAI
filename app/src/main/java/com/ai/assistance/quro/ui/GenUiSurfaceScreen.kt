package com.ai.assistance.quro.ui

import android.content.Context
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import com.ai.assistance.quro.core.QuroAssistant
import com.ai.assistance.quro.core.QuroConversationStore
import com.ai.assistance.quro.core.QuroMessage
import com.ai.assistance.quro.core.network.QuroLlmClient
import com.ai.assistance.quro.core.tools.buildQuroRegistry
import com.ai.assistance.quro.core.ui.dynamicui.QuroUiAction
import com.ai.assistance.quro.core.ui.dynamicui.QuroUiDslParser
import com.ai.assistance.quro.core.ui.dynamicui.QuroUiNode
import com.ai.assistance.quro.core.ui.dynamicui.QuroUiParseResult
import com.ai.assistance.quro.core.ui.dynamicui.QuroUiRenderer
import com.ai.assistance.quro.core.ui.dynamicui.SurfaceHost
import com.ai.assistance.quro.ui.icons.LucideIcon
import com.zorv.genui.prompt.GenUiPrompt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 真正的「非文本 GenUI 渲染面」。
 *
 * 与「带 GenUI 标签的普通文本聊天框」完全不同：这里**没有文本气泡流**。
 * 整个界面就是 AI 用 quro-ui 原生 DSL 渲染出来的 UI（[QuroUiRenderer] + [SurfaceHost]），
 * 底部只有一条 prompt 输入。提交后一次性 [QuroAssistant.ask] 生成，解析 quro-ui 后**全屏重渲染**；
 * 界面内交互（按钮 callback / 工具调用 / 打开链接等）经 [handleDynamicUiAction] 处理，
 * 其中 callback / 工具结果回传被重定向为「重新生成」而非发到聊天。
 *
 * 会话上下文由本屏私有的 [QuroConversationStore] 持有（跨重组保留，离开本屏才清空），
 * 因此多轮「界面↔指令」可连续进行。尊重 A2UI 红线：原生渲染，绝不在 WebView 里执行 AI 代码。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GenUiSurfaceScreen(
    vm: QuroChatViewModel,
    modelVm: QuroModelConfigViewModel,
    darkMode: Boolean = false,
    onToggleDark: () -> Unit = {},
) {
    val ctx = LocalContext.current.applicationContext
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val openDrawer: () -> Unit = { scope.launch { drawerState.open() } }

    val conversations by vm.conversations.collectAsState()
    val currentId by vm.currentId.collectAsState()
    val generatingIds by vm.generatingIds.collectAsState()
    val cfg by modelVm.cfg.collectAsState()
    val history = conversations.map { it.toHistoryItem(it.id == currentId) }

    // ── 渲染状态（本屏持有）──
    var uiRoot by remember { mutableStateOf<QuroUiNode?>(null) }
    var isBusy by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var input by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue("")) }
    var browserUrl by remember { mutableStateOf<String?>(null) }

    // ── 会话上下文（跨重组保留；离开本屏才随 composable 销毁而清空）──
    val store = remember { QuroConversationStore() }
    val assistant = remember(ctx) { QuroAssistant(QuroLlmClient(), buildQuroRegistry(ctx), store) }

    fun generate(prompt: String) {
        val p = prompt.trim()
        if (p.isBlank() || isBusy) return
        isBusy = true
        errorText = null
        input = androidx.compose.ui.text.input.TextFieldValue("")
        // 用户意图进会话上下文：GenUI 没有文本气泡，但模型需要看到历史才能连续生成界面
        store.add(QuroMessage(role = "user", content = p))
        scope.launch(Dispatchers.IO) {
            try {
                val sys = GenUiPrompt.build(force = true)
                val text = assistant.ask(
                    ctx, cfg,
                    systemPrompt = sys,
                    autoSaveMemory = false,
                    stream = false,
                    historyRounds = 8,
                    deepThink = false,
                )
                val parsed = QuroUiDslParser.parseFirst(text)
                withContext(Dispatchers.Main) {
                    when (parsed) {
                        is QuroUiParseResult.Success -> uiRoot = parsed.root
                        is QuroUiParseResult.Failure -> errorText =
                            "AI 未返回可用界面：${parsed.reason}\n\n${text.take(600)}"
                        null -> errorText = "AI 未返回界面（无 quro-ui 块）。\n\n${text.take(600)}"
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { errorText = "生成失败：${e.message}" }
            } finally {
                withContext(Dispatchers.Main) { isBusy = false }
            }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = true,
        drawerContent = {
            HistoryDrawer(
                history = history,
                onClose = { scope.launch { drawerState.close() } },
                onNew = { vm.newConversation(); scope.launch { drawerState.close() } },
                onNewGenUi = { vm.newConversation(genUiType = "genui"); scope.launch { drawerState.close() } },
                onPick = { id -> vm.selectConversation(id); scope.launch { drawerState.close() } },
                onCopyAll = {},
                onDelete = { vm.deleteConversation(it) },
                onDeleteAll = { vm.deleteAllConversations() },
                scaled = { it.sp },
                generatingIds = generatingIds,
            )
        },
    ) {
        Scaffold(
            containerColor = cs.background,
            topBar = {
                TopAppBar(
                    title = { Text("GenUI · 生成式界面", color = cs.onSurface) },
                    navigationIcon = {
                        IconButton(onClick = openDrawer) {
                            LucideIcon("panel_left", "菜单", Modifier.size(24.dp), tint = cs.onSurface)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = cs.surface),
                )
            },
        ) { pad ->
            Column(Modifier.fillMaxSize().padding(pad)) {
                // 主渲染面：整个界面即 AI 回复（无文本气泡）
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(10.dp),
                ) {
                    when {
                        isBusy && uiRoot == null -> {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        }
                        uiRoot != null -> {
                            SurfaceHost(designWidthDp = 360f) {
                                QuroUiRenderer(
                                    root = uiRoot!!,
                                    modifier = Modifier.fillMaxWidth(),
                                    onAction = { action: QuroUiAction, values: Map<String, String> ->
                                        handleDynamicUiAction(
                                            action, values, ctx, scope,
                                            onCommand = { generate(it) },
                                            onOpenLink = { browserUrl = it },
                                        )
                                    },
                                )
                            }
                        }
                        else -> {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(
                                    "输入需求，AI 将直接生成界面（没有文本气泡）。\n例如：「做一个带勾选的今日待办」「画一个计算器」",
                                    color = cs.onSurfaceVariant,
                                    fontSize = 14.sp,
                                )
                            }
                        }
                    }
                }

                // 错误条
                errorText?.let { err ->
                    Surface(color = cs.errorContainer, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            err,
                            color = cs.onErrorContainer,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(8.dp),
                        )
                    }
                }

                // 底部单条 prompt 输入
                Row(
                    Modifier.fillMaxWidth().padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("描述你想要的界面 / 直接下指令…") },
                        maxLines = 4,
                        enabled = !isBusy,
                    )
                    Spacer(Modifier.width(8.dp))
                    IconButton(onClick = { generate(input.text) }, enabled = !isBusy) {
                        LucideIcon("arrow_up", "生成", Modifier.size(26.dp), tint = cs.primary)
                    }
                }
            }
        }

        // 浏览器覆盖层（open_url 动作触发）
        browserUrl?.let { url ->
            Box(
                Modifier
                    .fillMaxSize()
                    .zIndex(200f)
                    .background(cs.background),
            ) {
                Column(Modifier.fillMaxSize()) {
                    Row(
                        Modifier.fillMaxWidth().padding(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = { browserUrl = null }) {
                            LucideIcon("x", "关闭", Modifier.size(22.dp), tint = cs.onSurface)
                        }
                    }
                    AndroidView(
                        factory = { c: Context ->
                            WebView(c).apply {
                                webViewClient = WebViewClient()
                                settings.javaScriptEnabled = true
                                loadUrl(url)
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}
