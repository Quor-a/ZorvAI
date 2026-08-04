package com.ai.assistance.quro.ui

import android.util.Log
import android.net.Uri
import android.media.RingtoneManager
import android.content.Intent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.collectAsState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import com.ai.assistance.quro.core.tools.QuroTool
import com.ai.assistance.quro.core.tools.ImportedToolDef
import com.ai.assistance.quro.core.tools.QuroImportedToolRegistry
import com.ai.assistance.quro.core.tools.QuroUiActionBridge
import com.ai.assistance.quro.BuildConfig
import com.ai.assistance.quro.core.linux.QuroLinuxEnv
import com.ai.assistance.quro.core.terminal.QuroTerminalController
import com.ai.assistance.quro.ui.QuroChatCardTray
import com.ai.assistance.quro.ui.QuroChatCardView
import com.ai.assistance.quro.core.cards.QuroChatCard
import com.ai.assistance.quro.core.cards.parseComponentSpec
import com.ai.assistance.quro.ui.QuroShareBridge
import com.ai.assistance.quro.service.QuroMediaService
import com.ai.assistance.quro.core.tools.QuroMediaController
import com.ai.assistance.quro.core.QuroBrowserBridge
import android.app.Activity
import android.content.ContextWrapper
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.provider.OpenableColumns
import android.content.ContentValues
import android.widget.Toast
import java.io.File
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import com.canhub.cropper.CropImageContract
import com.canhub.cropper.CropImageContractOptions
import com.canhub.cropper.CropImageOptions
import com.canhub.cropper.CropImageView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface

import androidx.compose.material3.Button
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebSettings
import android.view.ViewGroup
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.ai.assistance.quro.core.agent.QuroAgentTrace
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.ai.assistance.quro.core.QuroAttachment
import com.ai.assistance.quro.core.QuroMessage
import com.ai.assistance.quro.ui.data.Attachment
import com.ai.assistance.quro.ui.data.ChatModel
import com.ai.assistance.quro.ui.data.HistoryItem
import com.ai.assistance.quro.ui.data.Message
import com.ai.assistance.quro.ui.data.ModelGroup
import com.ai.assistance.quro.ui.data.Persona
import com.ai.assistance.quro.ui.data.SAMPLE_MESSAGES
import com.ai.assistance.quro.ui.data.SAMPLE_PERSONAS
import com.ai.assistance.quro.ui.data.ThinkBlock
import com.ai.assistance.quro.ui.data.ToolCallUi
import com.ai.assistance.quro.ui.icons.LucideIcon
import com.ai.assistance.quro.ui.chat.ChatPermissionModeBar
import com.ai.assistance.quro.ui.chat.ChatTopBar
import com.ai.assistance.quro.core.policy.QuroPolicy
import com.ai.assistance.quro.core.policy.QuroPolicyStore
import com.ai.assistance.quro.core.QuroAttachmentKit
import com.ai.assistance.quro.core.QuroConversationMeta
import com.ai.assistance.quro.core.QuroPersona
import com.ai.assistance.quro.core.QuroCrashLogger
import com.ai.assistance.quro.ui.QuroChatViewModel
import com.ai.assistance.quro.ui.dialog.RichText
import com.ai.assistance.quro.core.tools.QuroVoiceStyle
import com.ai.assistance.quro.core.tools.QuroSttHolder
import com.ai.assistance.quro.core.tools.QuroSttPrefs
import com.ai.assistance.quro.core.tools.QuroTtsHolder
import com.ai.assistance.quro.core.tools.QuroVoiceFeaturePrefs
import android.speech.SpeechRecognizer
import com.ai.assistance.quro.core.media.QuroVideoLauncher
import com.ai.assistance.quro.core.media.QuroMusicLauncher
import com.ai.assistance.quro.core.media.QuroDocLauncher
import com.ai.assistance.quro.core.model.QuroModelConfigRepository
import com.ai.assistance.quro.ui.QuroModelConfigViewModel
import com.ai.assistance.quro.core.network.QuroModelListResult
import com.ai.assistance.quro.core.model.QuroSavedProfile
import com.ai.assistance.quro.core.model.QuroSavedProfileRepository
import com.ai.assistance.quro.core.model.QuroCustomProviderRepository
import com.ai.assistance.quro.core.model.QuroLocalModelRepository
import com.ai.assistance.quro.core.model.QuroLocalModelType
import com.ai.assistance.quro.ui.QuroPersonaViewModel
import com.ai.assistance.quro.ui.theme.Accent
import com.ai.assistance.quro.ui.theme.AccentPress
import com.ai.assistance.quro.ui.theme.AccentSoft
import com.ai.assistance.quro.ui.theme.Card
import com.ai.assistance.quro.ui.theme.Ink
import com.ai.assistance.quro.ui.theme.InkD
import com.ai.assistance.quro.ui.theme.InkSoft
import com.ai.assistance.quro.ui.theme.Line
import com.ai.assistance.quro.ui.theme.Line2
import com.ai.assistance.quro.ui.theme.Muted
import com.ai.assistance.quro.ui.theme.Paper
import com.ai.assistance.quro.ui.theme.Sage
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextDecoration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.filterNotNull
import org.json.JSONObject
import com.ai.assistance.quro.core.tools.RunCodeTool
import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.History
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.Icon
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.graphics.asImageBitmap
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private enum class SheetType { Model, Persona, Settings, Upload, Voice }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ChatScreen(
    vm: QuroChatViewModel,
    modelVm: QuroModelConfigViewModel,
    personaVm: QuroPersonaViewModel,
    voiceBallEnabled: Boolean = false,
    onToggleVoiceBall: (Boolean) -> Unit = {},
    darkMode: Boolean = false,
    onToggleDark: () -> Unit = {},
) {
    val cs = MaterialTheme.colorScheme
    val fontTiers = listOf(0.92f, 1f, 1.14f)
    val fontNames = listOf("小", "标准", "大")

    // ---- Zorv AI 后端状态（单一真相源） ----
    val messages by vm.messages.collectAsState()
    val busy by vm.busy.collectAsState()
    val generatingIds by vm.generatingIds.collectAsState()
    val conversations by vm.conversations.collectAsState()
    val currentId by vm.currentId.collectAsState()
    val thinking by vm.thinking.collectAsState()
    val autoSaveMemory by vm.autoSaveMemory.collectAsState()
    val activePersona by personaVm.activePersona.collectAsState()
    val personas by personaVm.personas.collectAsState()
    val cfg by modelVm.cfg.collectAsState()
    // [D5] 收集 ViewModel 错误通道，供顶部错误横幅展示（异常不再被静默吞掉）。
    val errorState by vm.error.collectAsState()

    // ── 执行轨迹总线：全局只在此处订阅一次，去重后存入共享状态，三个面板统一读取；
    //    切换会话时清空，避免跨会话污染；add 前按 id 去重，避免重复 key / 重复事件。 ──
    val traceLines = remember { mutableStateListOf<QuroAgentTrace.AgentTraceEvent>() }
    LaunchedEffect(Unit) {
        QuroAgentTrace.flow.collect { ev ->
            if (traceLines.none { it.id == ev.id }) {
                traceLines.add(ev)
                if (traceLines.size > 200) traceLines.removeAt(0)
            }
        }
    }
    LaunchedEffect(currentId) {
        traceLines.clear()
    }

    // ---- 本地 UI 偏好（单一真相源：QuroChatViewModel.quro_ui，落盘持久化） ----
    val fontTier by vm.fontTierPref.collectAsState()
    val soundOn by vm.soundOnPref.collectAsState()
    val enterSend by vm.enterSendPref.collectAsState()
    val aiReplyNotify by vm.aiReplyNotifyPref.collectAsState()
    // 深色模式由 QuroApp 根部经 darkOverride 注入 QuroTheme，这里仅透传参数
    val ringtoneCtx = LocalContext.current

    // 回复完成提示音：监听 busy 由 true→false 的下降沿（首帧 prevBusy=false 不触发）
    var prevBusy by remember { mutableStateOf(false) }
    LaunchedEffect(busy, currentId) {
        if (prevBusy && !busy && vm.isSoundOn()) {
            runCatching {
                val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                RingtoneManager.getRingtone(ringtoneCtx, uri)?.play()
            }
        }
        prevBusy = busy
    }
    val attachments = remember { mutableStateListOf<QuroAttachment>() }

    // 选中模型：直接用 cfg.model 合成（真实配置的模型，不再反查示例假数据）
    val selectedModel: ChatModel = remember(cfg.model) {
        ChatModel(
            name = cfg.model.ifBlank { "未配置" }, id = cfg.model,
            desc = "当前配置的模型。", provider = cfg.provider,
            mark = cfg.provider.firstOrNull()?.uppercase() ?: "Q",
        )
    }
    // 顶栏芯片显示「真正配置 / 真正发送给 API 的模型」（不再经示例列表翻译，确保与模型配置一致）
    val modelLabel = remember(cfg) { if (cfg.model.isBlank()) "未配置模型" else cfg.model }
    val selectedPersona: Persona = remember(activePersona) {
        (activePersona ?: fallbackPersona()).toPersona()
    }

    // 当前会话 → MoWen Message 列表（工具调用可见化：把 hidden 管道消息配对成「🔧 工具调用」块）
    val uiMessages = remember(messages, busy, selectedPersona, thinking) {
        // 🔑 工具结果渲染策略（自包含为主 + 旧数据回退），同上
        val fallbackMap = messages
            .filter { it.role == "tool" && it.toolCallId != null }
            .mapNotNull { m -> if (isGarbageToolResult(m.content)) null else (m.toolCallId!! to m.content) }
            .toMap()

        // 🔧 修复「每次输出重开一个气泡」：把同一回合（相邻 user 消息之间）连续的
        //   assistant(+隐藏 tool) 消息聚合成【单个气泡】，内部累积 思考/工具/文本/卡片。
        //   这样「思考→工具调用→再思考→工具调用→最终文本」整轮响应只在 1 个气泡内流式增长，
        //   不再为每个 assistant 轮次各开一个气泡。单轮纯文本回复不受影响（仍是 1 气泡）。
        val out = mutableListOf<Message>()
        var aggId = 0
        val aggIds = mutableListOf<String>()
        var aggTime = ""
        var hasAgg = false
        val aggThinkLines = mutableListOf<String>()
        val aggTools = mutableListOf<ToolCallUi>()
        val aggText = StringBuilder()
        val aggCards = mutableListOf<QuroChatCard>()

        fun flushAgg() {
            if (!hasAgg) return
            val think = if (aggThinkLines.isNotEmpty()) ThinkBlock(aggThinkLines.toList()) else null
            val text = aggText.toString().takeIf { it.isNotBlank() }
            if (think != null || aggTools.isNotEmpty() || text != null || aggCards.isNotEmpty()) {
                out.add(
                    Message(
                        id = aggId,
                        uids = aggIds.toList(),
                        mine = false,
                        author = selectedPersona.name,
                        avatar = selectedPersona.ava,
                        avatarUri = selectedPersona.avatarUri,
                        time = aggTime,
                        text = text,
                        think = think,
                        tools = if (aggTools.isEmpty()) null else aggTools.toList(),
                        cards = aggCards.toList(),
                    )
                )
            }
            hasAgg = false
            aggThinkLines.clear(); aggTools.clear(); aggText.clear(); aggCards.clear(); aggIds.clear()
        }

        for (m in messages) {
            when (m.role) {
                "user" -> {
                    if (m.hidden) { flushAgg(); continue } // hidden 用户消息（如 [第N轮] 标记）不渲染
                    flushAgg()
                    out.add(m.toMessage(selectedPersona.name, selectedPersona.ava, selectedPersona.avatarUri, vm.userProfile.value.avatarUri, vm.userProfile.value.name))
                }
                "tool" -> { /* 隐藏内部消息：结果已进 toolCalls.result，不单独渲染、不参与聚合文本 */ }
                else -> {
                    // 隐藏且无任何可见内容的纯管道占位 → 跳过；否则参与聚合（含隐藏但有工具/推理/文本/卡片）
                    if (m.hidden && m.toolCalls.isNullOrEmpty() && m.reasoning.isNullOrBlank() && m.content.isBlank() && m.cards.isEmpty()) continue
                    aggIds.add(m.id)
                    if (!hasAgg) {
                        hasAgg = true
                        aggId = m.id.hashCode()
                        aggTime = formatChatTime(m.createdAt)
                    }
                    m.reasoning?.takeIf { it.isNotBlank() }?.lineSequence()
                        ?.filter { it.isNotBlank() }?.forEach { aggThinkLines.add(it) }
                    m.toolCalls?.forEach { c ->
                        val r = (c.result ?: fallbackMap[c.id])?.takeIf { !isGarbageToolResult(it) }
                        aggTools.add(ToolCallUi(c.name, c.arguments, r, c.durationMs))
                    }
                    if (m.content.isNotBlank()) {
                        if (aggText.isNotEmpty()) aggText.append("\n\n")
                        aggText.append(m.content)
                    }
                    if (m.cards.isNotEmpty()) aggCards.addAll(m.cards)
                }
            }
        }
        flushAgg()

        // 用户关闭「深度思考」时不渲染推理过程卡片（重建 Message 去掉 think）
        if (!thinking) {
            for (i in out.indices) {
                val mm = out[i]
                if (mm.think != null) out[i] = mm.copy(think = null)
            }
        }

        if (busy) {
            // 🔧 对话框进度反馈（v216 修复 → v230 优化）：
            //   仅当聚合列表中尚无助手消息时才追加占位气泡，避免与已聚合的真实消息重复显示。
            //   聚合逻辑已经把 hidden assistant 消息的 thinking/toolCalls 合并进了同一个气泡，
            //   所以大部分情况下不需要额外占位；占位仅覆盖「纯等待首条响应」的空窗期。
            val hasAssistantMsg = out.any { !it.mine && it.id != -1 }
            if (!hasAssistantMsg) {
                val pendingTool = messages.lastOrNull { it.role == "assistant" && it.hidden && it.toolCalls?.isNotEmpty() == true }
                    ?.toolCalls?.firstOrNull { it.result == null }
                val hint = if (pendingTool != null) "🔧 正在调用 ${pendingTool.name}…" else "正在思考…"
                out.add(
                    Message(
                        id = -1, mine = false, author = selectedPersona.name,
                        avatar = selectedPersona.ava, avatarUri = selectedPersona.avatarUri, time = "", text = null,
                        think = ThinkBlock(listOf(hint)),
                    )
                )
            }
        }
        out
    }

    // 历史（按日期分组，标注当前会话）与人格列表
    val history = remember(conversations, currentId) {
        conversations.map { it.toHistoryItem(it.id == currentId) }
    }
    val personaList = remember(personas) { personas.map { it.toPersona() } }

    var sheet by remember { mutableStateOf<SheetType?>(null) }
    var lastSheet by remember { mutableStateOf<SheetType?>(null) }
    SideEffect { if (sheet != null) lastSheet = sheet }

    // 人格编辑对话框（Zorv AI 完整流程：图片上传头像 / 描述 / 角色设定 / 开场白 / 聊天设定 / 标签 / AI孵化 / 保存）
    var personaToEdit by remember { mutableStateOf<QuroPersona?>(null) }
    var personaEditIsNew by remember { mutableStateOf(false) }
    var showSoulSheet by remember { mutableStateOf(false) }
    var showMemoryDialog by remember { mutableStateOf(false) }
    // 模型配置仓库：在可组合作用域直接创建（LocalContext.current 不能放进 remember/普通 lambda）
    val modelConfigRepo = QuroModelConfigRepository(LocalContext.current)
    var showAbout by remember { mutableStateOf(false) }
    // ACI 管理中心屏：从设置「功能 → ACI 管理中心」进入（此前仅有 AI 工具 ui_open_aci 可打开，无手动按钮）
    var showAci by remember { mutableStateOf(false) }
    // 功能模型配置屏（从设置「功能模型配置」进入）：为 5 类 AI 子能力各自绑定模型
    var showFeatureModelConfig by remember { mutableStateOf(false) }
    var showAppearance by remember { mutableStateOf(false) }
    var showVoice by remember { mutableStateOf(false) }
    var showTts by remember { mutableStateOf(false) }
    var showStt by remember { mutableStateOf(false) }
    // 语音服务 Hub（四卡片总览）
    var showVoiceService by remember { mutableStateOf(false) }
    // 云模型配置屏（语音合成 TTS 的真实配置界面：选服务商 + 填参数 + 音色 + 风格标签）
    var showCloudTts by remember { mutableStateOf(false) }
    // 独立模型配置屏（从设置底部弹层「模型配置」或「添加模型」进入）
    var showModelConfig by remember { mutableStateOf(false) }
    // 追踪模型配置屏是否从设置页进入（用于返回键正确导航：设置→模型配置→返回→回到设置）
    var modelConfigFromSettings by remember { mutableStateOf(false) }
    // 权限管理页（从设置页入口进入）
    var showPermission by remember { mutableStateOf(false) }
    // 包管理页（插件 / 工具包 / 技能 / MCP，从设置页入口进入）
    var showCms by remember { mutableStateOf(false) }
    // 工具箱（设置页入口：文件管理 / 包名查询 / 代码运行 / 内置浏览器）
    var showToolbox by remember { mutableStateOf(false) }
    // 插件运行时 Demo 入口
    var showPlugins by remember { mutableStateOf(false) }
    // 技能 SKILL 管理入口
    var showSkills by remember { mutableStateOf(false) }
    // 定时任务管理入口
    var showSchedule by remember { mutableStateOf(false) }
    // 知识库管理页（从设置页入口进入：浏览 / 查看 / 新建 / 删除 knowledge_base 文档）
    var showKnowledge by remember { mutableStateOf(false) }
    // 机器人设置页（C2）：从工具箱「机器人」入口进入
    var showBots by remember { mutableStateOf(false) }
    // 应用内文档查看器（本地渲染引擎，替代原 Collabora/外跳 ONLYOFFICE；已整合原「文档中心」）
    var showOnlyOffice by remember { mutableStateOf(false) }
    var showTerminal by remember { mutableStateOf(false) }
    var showMcp by remember { mutableStateOf(false) }
    // 聚合式「系统状态」浏览界面（设备 / 权限 / 模块运行态 / 人格心跳）
    var showSystemStatus by remember { mutableStateOf(false) }
    // 可视化组件画廊
    var showComponentGallery by remember { mutableStateOf(false) }
    // 内置代码编辑器入口
    var showEditor by remember { mutableStateOf(false) }
    // 输入框文本（提升到本层，便于编辑器结果回填）
    var inputText by remember { mutableStateOf(TextFieldValue("")) }
    // 系统分享桥：其它 App 分享进来的文本/链接预填到输入框；图片/文件作为附件（用户在对话框确认后发送）
    val shareCtx = LocalContext.current
    LaunchedEffect(Unit) {
        snapshotFlow { QuroShareBridge.pendingText.value to QuroShareBridge.pendingUris.value }
            .collect { pair ->
                val text = pair.first
                val uris = pair.second
                if (text == null && uris.isEmpty()) return@collect
                if (!text.isNullOrBlank()) inputText = TextFieldValue(text)
                if (uris.isNotEmpty()) {
                    uris.forEach { uri ->
                        val mime = shareCtx.contentResolver.getType(uri) ?: "*/*"
                        QuroAttachmentKit.fromUri(shareCtx, uri, mime)?.let { a -> attachments.add(a) }
                    }
                }
                QuroShareBridge.consume()
            }
    }
    // 内置浏览器 URL（open_web 工具或工具箱触发）
    var browserUrl by remember { mutableStateOf<String?>(null) }

    // 应用内视频播放器（VideoView 渲染屏）
    var showVideoPlayer by remember { mutableStateOf(false) }
    var videoPlayerUri by remember { mutableStateOf("") }
    var videoPlayerTitle by remember { mutableStateOf("") }
    // 应用内全屏音乐播放器
    var showMusicPlayer by remember { mutableStateOf(false) }

    // ═══ UI 动作桥：把 AI 调用的 ui_* 工具回调到本组合作用域，打开对应界面/弹层/开关 ═══
    fun handleUiAction(action: String) {
        when (action) {
            "ui_open_onlyoffice" -> showOnlyOffice = true
            "ui_open_knowledge" -> showKnowledge = true
            "ui_open_terminal" -> showTerminal = true
            "ui_open_editor" -> showEditor = true
            "ui_open_toolbox" -> showToolbox = true
            "ui_open_plugins" -> showPlugins = true
            "ui_open_skills" -> showSkills = true
            "ui_open_schedule" -> showSchedule = true
            "ui_open_bots" -> showBots = true
            "ui_open_cms" -> showCms = true
            "ui_open_system_status" -> showSystemStatus = true
            "ui_open_permission" -> showPermission = true
            "ui_open_model_config" -> showModelConfig = true
            "ui_open_voice" -> showVoice = true
            "ui_open_tts" -> showTts = true
            "ui_open_stt" -> showStt = true
            "ui_open_voice_service" -> showVoiceService = true
            "ui_open_doc_viewer" -> showOnlyOffice = true
            "ui_open_about" -> showAbout = true
            "ui_open_aci" -> showAci = true
            "ui_open_appearance" -> showAppearance = true
            "ui_open_soul" -> showSoulSheet = true
            "ui_open_memory" -> showMemoryDialog = true
            "ui_open_sheet_model" -> sheet = SheetType.Model
            "ui_open_sheet_persona" -> sheet = SheetType.Persona
            "ui_open_sheet_settings" -> sheet = SheetType.Settings
            "ui_open_sheet_upload" -> sheet = SheetType.Upload
            "ui_open_sheet_voice" -> sheet = SheetType.Voice
            "ui_open_upload", "ui_open_import_tool", "ui_open_ai_search", "ui_open_doc_generate" -> sheet = SheetType.Upload
            "ui_toggle_deepthink" -> vm.setThinking(!vm.thinking.value)
            "ui_toggle_memory" -> vm.setAutoSaveMemory(!vm.autoSaveMemory.value)
            "ui_clear_chat" -> vm.clear()
            "ui_new_chat" -> vm.newConversation()
        }
    }
    val appCtx = LocalContext.current
    LaunchedEffect(Unit) {
        QuroUiActionBridge.dispatch = { handleUiAction(it) }
        // 可视化组件融进聊天气泡：AI 经 ui_widget / ui_card 下发的卡片挂到当前助手消息
        QuroUiActionBridge.onCard = { vm.attachCardToLastAssistant(it) }
        // 文档事件桥 → 打开文档查看器
        launch {
            QuroDocLauncher.file.collect { f ->
                if (f != null) {
                    if (!QuroDocOpener.open(appCtx, f)) {
                        Toast.makeText(appCtx, "未找到可打开该文档的应用，可尝试用应用内文档查看器或安装 WPS / Office", Toast.LENGTH_LONG).show()
                    }
                    QuroDocLauncher.consume()
                }
            }
        }
    }

    // 媒体启动事件桥：把工具 / 卡片发出的「打开视频 / 音乐播放器」请求落到应用内渲染屏
    LaunchedEffect(Unit) {
        launch {
            QuroVideoLauncher.event.collect { req ->
                if (req != null) {
                    videoPlayerUri = req.uri
                    videoPlayerTitle = req.title
                    showVideoPlayer = true
                    QuroVideoLauncher.consume()
                }
            }
        }
        launch {
            QuroMusicLauncher.open.collect { open ->
                if (open) {
                    showMusicPlayer = true
                    QuroMusicLauncher.consume()
                }
            }
        }
    }

    // 应用上下文：提前声明，供 handleUiAction / handleCardCommand 等局部函数捕获
    val ctx = LocalContext.current
    quroDiagCtx = ctx

    // 卡片动作命令分发：ui_* 走 UI 桥；linux:install 触发沙箱安装；run:<cmd> 喂给终端。
    fun handleCardCommand(cmd: String) {
        when {
            cmd.startsWith("reply:") -> {
                val t = cmd.removePrefix("reply:").trim()
                if (t.isNotEmpty()) vm.send(t, emptyList(), cfg)
            }
            cmd.startsWith("ui_") -> QuroUiActionBridge.dispatch?.invoke(cmd)
            cmd == "linux:install" -> QuroLinuxEnv.setup(ctx)
            cmd.startsWith("run:") -> {
                val c = cmd.removePrefix("run:")
                QuroTerminalController.session ?: QuroTerminalController.createSession(ctx)
                QuroTerminalController.sendToShell(c)
                showTerminal = true
            }
            // ── v221 富事件命令：open / copy / ai / screen ──
            cmd.startsWith("open:") -> QuroBrowserBridge.open(cmd.removePrefix("open:").trim())
            cmd.startsWith("copy:") -> {
                val text = cmd.removePrefix("copy:").trim()
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("Zorv", text))
                Toast.makeText(ctx, "已复制", Toast.LENGTH_SHORT).show()
            }
            cmd.startsWith("ai:") -> {
                val t = cmd.removePrefix("ai:").trim()
                if (t.isNotEmpty()) vm.send(t, emptyList(), cfg)
            }
            cmd.startsWith("screen:") -> QuroUiActionBridge.dispatch?.invoke(cmd.removePrefix("screen:").trim())
            // 打开全屏音乐播放器（工具 / 卡片触发）
            cmd == "ui_open_music_player" -> showMusicPlayer = true
            // 点击 AI 头像 → 编辑当前激活灵魂卡（v232 修复：此前 __edit_soul_card__ 无对应分支，点击无反应进不去）
            cmd == "__edit_soul_card__" -> {
                val p = personaVm.activePersona.value
                if (p != null) {
                    personaToEdit = p
                    personaEditIsNew = false
                } else {
                    showSoulSheet = true
                }
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            QuroUiActionBridge.dispatch = null
            QuroUiActionBridge.onCard = null
        }
    }

    val drawerState = rememberDrawerState(initialValue = androidx.compose.material3.DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val openDrawer: () -> Unit = { scope.launch { drawerState.open() } }

    val fontScale = fontTiers[fontTier]
    fun scaled(base: Int) = (base * fontScale).sp

    // 文件选择器（支持多选：真实唤起系统选择器，落盘到应用私有目录）
    val pickLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris: List<Uri> ->
        for (uri in uris) {
            val mime = ctx.contentResolver.getType(uri) ?: "*/*"
            QuroAttachmentKit.fromUri(ctx, uri, mime)?.let { a -> attachments.add(a) }
        }
    }

    fun send(text: String) {
        val t = text.trim()
        if (t.isEmpty() && attachments.isEmpty()) return
        vm.send(t, attachments.toList(), cfg)
        attachments.clear()
    }

    Box(Modifier.fillMaxSize()) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            gesturesEnabled = true,
            drawerContent = {
                HistoryDrawer(
                    history = history,
                    onClose = { scope.launch { drawerState.close() } },
                    onNew = { vm.newConversation(); scope.launch { drawerState.close() } },
                    onPick = { id -> vm.selectConversation(id); scope.launch { drawerState.close() } },
                    onCopyAll = { copyConversation(ctx, uiMessages) },
                    onDelete = { vm.deleteConversation(it) },
                    onDeleteAll = { vm.deleteAllConversations() },
                    scaled = { scaled(it) },
                    generatingIds = generatingIds,
                )
            }
        ) {
            Scaffold(
                containerColor = cs.background,
                topBar = {
                    ChatTopBar(
                        modelName = modelLabel,
                        onMenu = openDrawer,
                        onModel = { sheet = SheetType.Model },
                        onSettings = { sheet = SheetType.Settings },
                        persona = selectedPersona,
                        onPick = { sheet = SheetType.Persona },
                        scaled = { scaled(it) }
                    )
                }
            ) { pad ->
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(pad)
                ) {
                    // [D5] 错误横幅：ViewModel 捕获的异常经 error StateFlow 暴露，这里以顶部横幅呈现并在数秒后自动消失。
                    errorState?.let { err ->
                        LaunchedEffect(err) {
                            kotlinx.coroutines.delay(4000L)
                            vm.clearError()
                        }
                        Surface(
                            color = cs.errorContainer,
                            modifier = Modifier.fillMaxWidth().padding(8.dp),
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("⚠️ $err", color = cs.onErrorContainer, fontSize = 13.sp, modifier = Modifier.weight(1f))
                                TextButton(onClick = { vm.clearError() }) { Text("关闭", color = cs.onErrorContainer) }
                            }
                        }
                    }
                    MessageList(
                        messages = uiMessages,
                        scaled = { scaled(it) },
                        currentId = currentId,
                        traceLines = traceLines,
                        onOpenLink = { browserUrl = it },
                        onCommand = { handleCardCommand(it) },
                        onAskFollowup = { txt ->
                            inputText = TextFieldValue(
                                "针对上面的回答，我想追问：\n> " + txt.take(200).replace("\n", "\n> ") + "\n\n"
                            )
                        },
                        onShare = { txt -> shareText(ctx, txt) },
                        onRegenerate = {
                            val lastUser = uiMessages.lastOrNull { it.mine }?.text
                            if (!lastUser.isNullOrBlank()) send(lastUser)
                        },
                        onDelete = { vm.deleteMessage(it) },
                        modifier = Modifier.weight(1f)
                    )
                    // 语音：对话框语音输入按钮（受「语音设置 · 对话框按钮」开关控制）
                    val voiceInputEnabled = remember { QuroVoiceFeaturePrefs.getDialogVoiceButton(ctx) }
                    fun startDialogStt() {
                        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
                            Toast.makeText(ctx, "设备不支持语音识别", Toast.LENGTH_SHORT).show()
                            return
                        }
                        QuroSttHolder.startListening(
                            context = ctx,
                            language = QuroSttPrefs.getLanguage(ctx),
                            partialResults = QuroSttPrefs.getPartial(ctx),
                            onPartial = { },
                            onFinal = { txt ->
                                if (txt.isNotBlank()) {
                                    val cur = inputText.text
                                    inputText = inputText.copy(text = if (cur.isBlank()) txt else "$cur $txt")
                                }
                            },
                            onError = { _, msg -> Toast.makeText(ctx, "语音识别出错：$msg", Toast.LENGTH_SHORT).show() }
                        )
                    }
                    // 自动朗读：AI 回复完成后 TTS 朗读（受「语音设置 · 自动朗读」开关控制）
                    var autoRead by remember { mutableStateOf(QuroVoiceFeaturePrefs.getAutoRead(ctx)) }
                    fun toggleAutoRead() { autoRead = !autoRead; QuroVoiceFeaturePrefs.setAutoRead(ctx, autoRead) }
                    // #411 TTS 去重提升到 ViewModel：remember 是纯内存态，退出对话框 Compose 树销毁即重置为 "" →
                    // 重进入时 last.id != "" 永远成立 → 重复播放已播过的消息。改用 ViewModel 的 StateFlow，
                    // 生命周期跟随 ViewModel（Activity 重建也不丢）。
                    val lastSpokenId by vm.lastSpokenMsgId.collectAsState()
                    var wasBusy by remember { mutableStateOf(false) }
                    var busyConvId by remember { mutableStateOf<String?>(null) }
                    val ttsScope = rememberCoroutineScope()
                    LaunchedEffect(busy, currentId) {
                        // 记录「正在生成的是哪个会话」，切走其它会话时不该误触发朗读
                        if (busy) busyConvId = currentId
                        if (wasBusy && !busy && busyConvId == currentId) {
                            val msgs = vm.messages.value
                            val last = msgs.lastOrNull()
                            if (autoRead && last != null && last.role == "assistant" && last.id != lastSpokenId) {
                                vm.markSpoken(last.id)
                                // ★ v414 修复「语音朗读不播 / 语音合成播不完」：
                                //   ensureReady/speak 是挂起调用，若直接写在随 busy/currentId 取消的 LaunchedEffect 内，
                                //   挂起过程被取消 → 要么不播、要么播到一半被掐断。改由稳定 scope 承接播放，UI 状态变化不再杀掉朗读。
                                ttsScope.launch {
                                    QuroTtsHolder.ensureReady(ctx)
                                    QuroTtsHolder.speak(last.content) {}
                                }
                            }
                        }
                        wasBusy = busy
                    }

                    val visionOn by vm.visionEnabled.collectAsState()
                    Composer(
                        deepThink = thinking,
                        onToggleThink = { vm.setThinking(!thinking) },
                        attachments = attachments,
                        onRemoveAttach = { attachments.remove(it) },
                        onAttach = { sheet = SheetType.Upload },
                        autoSaveMemory = autoSaveMemory, onToggleAutoSave = { vm.setAutoSaveMemory(!autoSaveMemory) },
                        onSend = { send(it) },
                        text = inputText,
                        onTextChange = { inputText = it },
                        enterSend = enterSend,
                        busy = busy,
                        onStop = { vm.stop() },
                        onOpenMusicPlayer = { showMusicPlayer = true },
                        autoRead = autoRead,
                        onToggleAutoRead = { toggleAutoRead() },
                        visionEnabled = visionOn,
                        onToggleVision = { vm.setVisionEnabled(!vm.visionEnabled.value) },
                        voiceInputEnabled = voiceInputEnabled,
                        onVoiceInput = { startDialogStt() },
                        onOpenSkills = { showSkills = true },
                        scaled = { scaled(it) }
                    )
                }
            }
        }

        // （系统通知改为「离开软件时」由 QuroReplyNotifier 弹 heads-up，不在软件内不弹，故此处无应用内浮层）

        // 模型选择 = 当前配置 + 已保存预设 + 从 API 拉取到的真实可用模型（彻底摒弃硬编码假示例）
        val modelList by modelVm.modelList.collectAsState()
        val isFetchingModels by modelVm.isFetchingModels.collectAsState()
        val modelFetchError = (modelList as? QuroModelListResult.Error)?.message

        val currentModelGroup = remember(cfg) {
            listOf(ModelGroup("当前配置", listOf(
                ChatModel(
                    name = cfg.model.ifBlank { "未配置" },
                    id = cfg.model,
                    desc = "提供商: ${cfg.provider} · ${cfg.baseUrl.takeIf { it.isNotBlank() } ?: "未设置BaseURL"}",
                    provider = cfg.provider.ifBlank { "自定义" },
                    mark = cfg.provider.firstOrNull()?.uppercase() ?: "Q"
                )
            )))
        }

        // 已保存预设：从持久化仓库加载，每个预设是一个可快速切换的模型选项
        val profileRepo = remember { QuroSavedProfileRepository(ctx.applicationContext) }
        var savedProfiles by remember { mutableStateOf(profileRepo.loadAll()) }
        val savedProfileGroup: ModelGroup? = remember(savedProfiles) {
            if (savedProfiles.isEmpty()) null
            else ModelGroup("已保存预设", savedProfiles.map { p ->
                ChatModel(
                    name = p.name,
                    id = "__profile__${p.id}",  // 特殊 id 前缀标识这是预设
                    desc = "${p.model} @ ${p.provider} · ${p.baseUrl}",
                    provider = p.customProviderName.ifBlank { p.provider },
                    mark = p.name.firstOrNull()?.uppercase() ?: "S"
                )
            })
        }

        // 用户自建「其他供应商」：在「模型配置」里添加，这里作为独立分组供选择
        val customProviderRepo = remember { QuroCustomProviderRepository(ctx.applicationContext) }
        var customProviders by remember { mutableStateOf(customProviderRepo.loadAll()) }
        val customProviderGroup: ModelGroup? = remember(customProviders) {
            if (customProviders.isEmpty()) null
            else ModelGroup("其他供应商（自建）", customProviders.map { p ->
                ChatModel(
                    name = p.name.ifBlank { "未命名供应商" },
                    id = "__custom__${p.id}",
                    desc = "${p.defaultModel.ifBlank { "（请在模型配置填写默认模型）" }} @ ${p.baseUrl.ifBlank { "未设置地址" }}",
                    provider = p.name.ifBlank { "其他供应商" },
                    mark = p.name.firstOrNull()?.uppercase() ?: "C"
                )
            })
        }

        // 本地离线模型：MNN（上传 .mnn 文件）/ llama.cpp（文件夹扫描 .gguf）
        val localModelRepo = remember { QuroLocalModelRepository(ctx.applicationContext) }
        var localModels by remember { mutableStateOf(localModelRepo.loadAll()) }
        val localModelGroup: ModelGroup? = remember(localModels) {
            if (localModels.isEmpty()) null
            else ModelGroup("本地离线模型", localModels.map { m ->
                val typeLabel = if (m.type == QuroLocalModelType.LLAMA_CPP) "llama.cpp" else "MNN"
                ChatModel(
                    name = m.name.ifBlank { typeLabel },
                    id = "__local__${m.id}",
                    desc = "$typeLabel 本地推理 · ${m.path.ifBlank { "未设置路径" }}${if (m.modelNames.isNotEmpty()) " · ${m.modelNames.first()}" else ""}",
                    provider = m.type.name,
                    mark = "L"
                )
            })
        }

        // 打开模型选择时，刷新所有持久化分组
        LaunchedEffect(sheet) {
            if (sheet == SheetType.Model) {
                savedProfiles = profileRepo.loadAll()
                customProviders = customProviderRepo.loadAll()
                localModels = localModelRepo.loadAll()
                // v396：改为手动拉取——进入模型面板只加载本地缓存，不自动联网；
                // 用户点「从 API 拉取可用模型」按钮才请求，结果会持久化缓存。
                modelVm.loadCachedModels()
            }
        }

        // 将拉取结果转换为可选模型分组
        val fetchedGroup: ModelGroup? = when (val ml = modelList) {
            is QuroModelListResult.Success -> ModelGroup(
                "可用模型 (API)",
                ml.models.map { id ->
                    ChatModel(name = id, id = id, desc = "来自 ${cfg.baseUrl}", provider = cfg.provider.ifBlank { "自定义" }, mark = id.firstOrNull()?.uppercase() ?: "M")
                }
            )
            else -> null
        }

        val allModelGroups = remember(currentModelGroup, savedProfileGroup, fetchedGroup, customProviderGroup, localModelGroup) {
            buildList {
                addAll(currentModelGroup)
                savedProfileGroup?.let { add(it) }
                customProviderGroup?.let { add(it) }
                localModelGroup?.let { add(it) }
                fetchedGroup?.let { add(it) }
            }
        }

        // 任意「设置子页」浮层是否开着：用于禁用设置 sheet 的返回回调，保证逐级返回
        val settingsChildOpen = showModelConfig || showToolbox || showVoice || showAbout || showAppearance ||
            showPermission || showCms || showPlugins || showKnowledge || showTerminal || showSchedule || showBots ||
            showTts || showStt || showVoiceService || showSystemStatus || showFeatureModelConfig || showAci
        // 底部弹层（自定义，统一遮罩 + 上滑）
        SheetOverlay(
            sheet = sheet, lastSheet = lastSheet,
            onDismiss = { sheet = null },
            backEnabled = sheet != null && !settingsChildOpen,
            scaled = { scaled(it) },
            modelGroups = allModelGroups,
            selectedModel = selectedModel,
            onSelectModel = { m ->
                when {
                    // 已保存预设 → 应用整套预设配置
                    m.id.startsWith("__profile__") -> {
                        val profileId = m.id.removePrefix("__profile__")
                        val profile = savedProfiles.find { it.id == profileId }
                        if (profile != null) {
                            profileRepo.applyToConfig(profile, modelVm.repo)
                            modelVm.reload()
                        }
                    }
                    // 自建「其他供应商」→ 回填其 BaseURL / 默认模型，路由走 HTTP
                    m.id.startsWith("__custom__") -> {
                        val cpId = m.id.removePrefix("__custom__")
                        val cp = customProviders.find { it.id == cpId }
                        if (cp != null) {
                            modelVm.update {
                                copy(
                                    provider = "OTHER",
                                    customProviderName = cp.name,
                                    baseUrl = cp.baseUrl,
                                    model = cp.defaultModel,
                                )
                            }
                        }
                    }
                    // 本地离线模型（MNN / llama.cpp）→ 走本地推理路由
                    m.id.startsWith("__local__") -> {
                        val lmId = m.id.removePrefix("__local__")
                        val lm = localModels.find { it.id == lmId }
                        if (lm != null) {
                            modelVm.update {
                                copy(
                                    provider = lm.type.name,
                                    localModelPath = lm.path,
                                    model = lm.modelNames.firstOrNull() ?: "",
                                )
                            }
                        }
                    }
                    // 普通模型（当前配置或 API 拉取）→ 仅切换 model 字段
                    else -> {
                        modelVm.update { copy(model = m.id) }
                    }
                }
                sheet = null
            },
            onAddModel = { _, _ -> showModelConfig = true },  // 添加模型 → 独立模型配置屏（保留设置弹层，返回回设置）
            isFetchingModels = isFetchingModels,
            modelFetchError = modelFetchError,
            onFetchModels = { modelVm.fetchModels() },
            personaList = personaList,
            selectedPersona = selectedPersona,
            onSelectPersona = { p -> if (p.id.isNotBlank()) personaVm.setActive(p.id); sheet = null },
            onAddPersona = { _, _ ->
                sheet = null
                personaToEdit = QuroPersona(
                    id = java.util.UUID.randomUUID().toString(), name = "", description = "",
                    avatarEmoji = "🤖", avatarType = "emoji",
                    roleSetting = "", opening = "", chatSetting = "", voiceSetting = "",
                    tags = emptyList(), incubation = "", createdAt = 0L, updatedAt = 0L,
                )
                personaEditIsNew = true
            },  // 打开 Zorv AI 完整人格创建对话框
            onPickFile = { mime -> pickLauncher.launch(mime) },
            onExport = {
                val path = exportConversation(ctx, uiMessages)
                if (path != null) {
                    Toast.makeText(ctx, "已导出对话：$path", Toast.LENGTH_LONG).show()
                    val share = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, "QuroAI 对话导出，文件已保存至：$path")
                    }
                    runCatching { ctx.startActivity(Intent.createChooser(share, "分享对话")) }
                } else {
                    Toast.makeText(ctx, "导出失败，请重试", Toast.LENGTH_SHORT).show()
                }
            },
            onClear = { vm.clear() },
            onOpenBrowser = { browserUrl = it },
            // 设置底部弹层：UI 取自 MoWenApp，功能接 Zorv AI 现有状态/页面
            settingsDarkMode = darkMode,
            onSettingsToggleDark = onToggleDark,
            settingsSoundOn = soundOn,
            onSettingsToggleSound = { vm.setSoundOn(!soundOn) },
            settingsEnterSend = enterSend,
            onSettingsToggleEnter = { vm.setEnterSend(!enterSend) },
            settingsFontName = fontNames[fontTier],
            onSettingsCycleFont = { vm.setFontTier((fontTier + 1) % 3) },
            onOpenModelConfig = { showModelConfig = true; modelConfigFromSettings = true },
            onOpenFeatureModelConfig = { showFeatureModelConfig = true },
            onOpenPermission = { showPermission = true },
            onOpenCms = { showCms = true },
            onOpenToolbox = { showToolbox = true },
            onOpenKnowledge = { showKnowledge = true },
            onOpenTerminal = { showTerminal = true },
            onOpenPlugins = { showPlugins = true },
            onOpenSkills = { showSkills = true },
            onOpenBots = { showBots = true },
            onOpenSchedule = { showSchedule = true },
            onManagePersona = { sheet = null; showSoulSheet = true },
            onOpenVoice = { showVoice = true },
            onOpenTts = { showTts = true },
            onOpenStt = { showStt = true },
            onOpenOnlyOffice = { showOnlyOffice = true },
            onOpenVoiceService = { sheet = null; showVoiceService = true },
            onClearChat = { vm.clear() },
            settingsVoiceBallEnabled = voiceBallEnabled,
            onSettingsToggleVoiceBall = onToggleVoiceBall,
            settingsAiReplyNotify = aiReplyNotify,
            onSettingsToggleAiReplyNotify = { vm.setAiReplyNotify(!aiReplyNotify) },
            onOpenAbout = { showAbout = true },
            onOpenAci = { showAci = true },
            onOpenMcp = { showMcp = true },
            onOpenSystemStatus = { showSystemStatus = true },
            onOpenComponentGallery = { showComponentGallery = true },
            onOpenAppearance = { showAppearance = true },
            vm = vm,
            onSendText = { send(it) },
        )

        // 权限管理页：全屏覆盖层（从设置底部弹层入口进入，返回关页回对话）
        if (showPermission) {
            // 从设置进入的子屏：返回键只关闭本层，保留设置（showSettings 仍 true）
            BackHandler { showPermission = false }
            Box(Modifier.fillMaxSize().zIndex(100f).background(Color(0xFFF2F2F7))) {
                QuroPermissionScreen(onClose = { showPermission = false })
            }
        }

        // CMS v2 能力模块系统页：全屏覆盖层（从设置页入口进入，返回关页回设置）
        if (showCms) {
            BackHandler { showCms = false }
            Box(Modifier.fillMaxSize().zIndex(100f).background(Color(0xFFF2F2F7))) {
                QuroCmsScreen(onClose = { showCms = false })
            }
        }

        // 聚合式「系统状态」浏览界面：全屏覆盖层（从设置页入口进入，返回关页回设置）
        if (showSystemStatus) {
            BackHandler { showSystemStatus = false }
            Box(Modifier.fillMaxSize().zIndex(100f).background(MaterialTheme.colorScheme.background)) {
                QuroSystemStatusScreen(onClose = { showSystemStatus = false }, personaVm = personaVm)
            }
        }

        // 可视化组件画廊
        if (showComponentGallery) {
            BackHandler { showComponentGallery = false }
            Box(Modifier.fillMaxSize().zIndex(100f).background(MaterialTheme.colorScheme.background)) {
                QuroComponentGalleryScreen(onBack = { showComponentGallery = false })
            }
        }

        // 插件运行时 Demo 页：全屏覆盖层（从设置页入口进入）
        if (showPlugins) {
            BackHandler { showPlugins = false }
            Box(Modifier.fillMaxSize().zIndex(100f).background(Color(0xFFF2F2F7))) {
                PluginsScreen(onClose = { showPlugins = false })
            }
        }

        // 技能 SKILL 管理页：全屏覆盖层（从工具栏「技能」/ ui_open_skills 进入）
        if (showSkills) {
            BackHandler { showSkills = false }
            Box(Modifier.fillMaxSize().zIndex(100f).background(Color(0xFFF2F2F7))) {
                QuroSkillsScreen(onClose = { showSkills = false })
            }
        }

        // 定时任务管理页：全屏覆盖层（从设置页「定时任务」/ ui_open_schedule 进入）
        if (showSchedule) {
            BackHandler { showSchedule = false }
            Box(Modifier.fillMaxSize().zIndex(100f).background(Color(0xFFF2F2F7))) {
                QuroScheduleScreen(onClose = { showSchedule = false })
            }
        }

        // 内置代码编辑器：全屏覆盖层（从输入框「代码」按钮进入）
        if (showEditor) {
            BackHandler { showEditor = false }
            Box(Modifier.fillMaxSize().zIndex(100f).background(Color(0xFFF2F2F7))) {
                EditorScreen(
                    initialCode = inputText.text,
                    initialLang = "javascript",
                    onClose = { code -> inputText = TextFieldValue(code); showEditor = false }
                )
            }
        }

        // 人格创建/编辑对话框（Zorv AI 完整流程）
        personaToEdit?.let { p ->
            PersonaEditDialog(
                initial = p,
                vm = personaVm,
                isNew = personaEditIsNew,
                onDismiss = { personaToEdit = null },
            )
        }

        // 灵魂注入 / 人格管理（旧 Zorv AI 设置功能，经设置页入口唤出）
        if (showSoulSheet) {
            SoulInjectionSheet(
                vm = personaVm,
                onDismiss = { showSoulSheet = false },
                onCreate = {
                    showSoulSheet = false
                    personaToEdit = QuroPersona(
                        id = java.util.UUID.randomUUID().toString(), name = "", description = "",
                        avatarEmoji = "🤖", avatarType = "emoji",
                        roleSetting = "", opening = "", chatSetting = "", voiceSetting = "",
                        tags = emptyList(), incubation = "", createdAt = 0L, updatedAt = 0L,
                    )
                    personaEditIsNew = true
                },
                onEdit = { id ->
                    showSoulSheet = false
                    personaToEdit = personas.find { it.id == id }
                    personaEditIsNew = false
                },
                onManageMemory = {
                    showMemoryDialog = true
                },
            )
        }

        if (showMemoryDialog) {
            MemoryDialog(personaVm = personaVm, onDismiss = { showMemoryDialog = false })
        }

        // 关于页：全屏覆盖层（从设置「关于 Zorv AI」进入）
        if (showAbout) {
            // 拦截系统返回键：先关闭关于页回到设置（showSettings 仍 true），而非 finish Activity
            BackHandler { showAbout = false }
            Box(Modifier.fillMaxSize().zIndex(100f).background(cs.background)) {
                QuroAboutScreen(onBack = { showAbout = false })
            }
        }

        // 功能模型配置页：全屏覆盖层（从设置「功能模型配置」进入）
        if (showFeatureModelConfig) {
            BackHandler { showFeatureModelConfig = false }
            Box(Modifier.fillMaxSize().zIndex(100f).background(cs.background)) {
                QuroFeatureModelConfigScreen(onBack = { showFeatureModelConfig = false })
            }
        }

        // MCP 服务设置页：全屏覆盖层（从设置「MCP 服务」进入）
        if (showMcp) {
            BackHandler { showMcp = false }
            Box(Modifier.fillMaxSize().zIndex(100f).background(cs.background)) {
                QuroMcpSettingsScreen(onBack = { showMcp = false })
            }
        }

        // ACI 管理中心页：全屏覆盖层（从设置「功能 → ACI 管理中心」进入，AI 工具 ui_open_aci 亦可打开）
        if (showAci) {
            BackHandler { showAci = false }
            Box(Modifier.fillMaxSize().zIndex(100f).background(cs.background)) {
                QuroAciCenterScreen(onClose = { showAci = false })
            }
        }

        // 语音设置页：内嵌对话框底部的紧凑面板（不再是全屏页）
        if (showVoice) {
            BackHandler { showVoice = false }
            Box(Modifier.fillMaxSize().zIndex(100f)) {
                Surface(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().heightIn(max = 560.dp)
                        .navigationBarsPadding(),
                    shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                    tonalElevation = 8.dp,
                    shadowElevation = 12.dp,
                ) {
                    QuroVoiceSettingsScreen(
                        onBack = { showVoice = false },
                        onToggleVoiceBall = onToggleVoiceBall,
                        voiceBallEnabled = voiceBallEnabled,
                    )
                }
            }
        }

        // 语音合成 (TTS) 设置页：内嵌底部紧凑面板
        if (showTts) {
            BackHandler { showTts = false }
            Box(Modifier.fillMaxSize().zIndex(100f).background(cs.background)) {
                Surface(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().heightIn(max = 560.dp).navigationBarsPadding(),
                    shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                    tonalElevation = 8.dp, shadowElevation = 12.dp,
                ) {
                    QuroTtsSettingsScreen(
                        onBack = { showTts = false },
                        onOpenCloudConfig = { showTts = false; sheet = null; showCloudTts = true },
                    )
                }
            }
        }

        // 语音识别 (STT) 设置页：内嵌底部紧凑面板
        if (showStt) {
            BackHandler { showStt = false }
            Box(Modifier.fillMaxSize().zIndex(100f).background(cs.background)) {
                Surface(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().heightIn(max = 560.dp).navigationBarsPadding(),
                    shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                    tonalElevation = 8.dp, shadowElevation = 12.dp,
                ) {
                    QuroSttSettingsScreen(onBack = { showStt = false })
                }
            }
        }

        // 语音服务 Hub：全屏覆盖（v161 改为全屏，彻底规避底部 Surface 被设置弹层遮挡/约束问题）
        if (showVoiceService) {
            BackHandler { showVoiceService = false; sheet = SheetType.Settings }
            Box(Modifier.fillMaxSize().zIndex(101f).background(cs.background)) {
                QuroVoiceServiceScreen(
                    onBack = { showVoiceService = false; sheet = SheetType.Settings },
                    onOpenTts = { showVoiceService = false; showTts = true },
                    onOpenStt = { showVoiceService = false; showStt = true },
                    onOpenVoiceSettings = { showVoiceService = false; showVoice = true },
                )
            }
        }

        // 云模型配置屏（语音合成 TTS 的真实配置界面）：全屏覆盖
        if (showCloudTts) {
            BackHandler { showCloudTts = false; showTts = true }
            Box(Modifier.fillMaxSize().zIndex(101f).background(cs.background)) {
                QuroCloudTtsConfigScreen(
                    onBack = { showCloudTts = false; showTts = true },
                )
            }
        }

        // 独立模型配置屏：从齿轮设置页「模型配置」或「添加模型」进入
        if (showModelConfig) {
            // 拦截系统返回键：若从设置页进入则回设置，否则回对话
            BackHandler { showModelConfig = false; modelConfigFromSettings = false }
            Box(Modifier.fillMaxSize().zIndex(100f).background(cs.background)) {
                QuroModelConfigScreen(modelVm, onBack = { showModelConfig = false })
            }
        }

        // 工具箱（从输入框「+」工具进入：文件管理 / 包名查询 / 工作区）
        if (showToolbox) {
            BackHandler { showToolbox = false }
            Box(Modifier.fillMaxSize().zIndex(100f).background(cs.background)) {
                QuroToolboxScreen(
                    onClose = { showToolbox = false },
                    onOpenOnlyOffice = { showOnlyOffice = true },
                    onOpenMusic = { showMusicPlayer = true },
                    onOpenVideo = { uri, title ->
                        videoPlayerUri = uri
                        videoPlayerTitle = title
                        showVideoPlayer = true
                    },
                    allTools = vm.allTools(),
                    onImportTool = { vm.importTool(it) },
                )
            }
        }

        // 知识库管理页：全屏覆盖层（从设置页入口进入，返回关页回设置）
        if (showKnowledge) {
            BackHandler { showKnowledge = false }
            Box(Modifier.fillMaxSize().zIndex(100f).background(cs.background)) {
                QuroKnowledgeScreen(onClose = { showKnowledge = false })
            }
        }

        // 机器人设置页（C2）：全屏覆盖层（从工具箱「机器人」入口进入，返回关页回工具箱）
        if (showBots) {
            BackHandler { showBots = false }
            Box(Modifier.fillMaxSize().zIndex(100f).background(cs.background)) {
                QuroBotSettingsScreen(onClose = { showBots = false })
            }
        }

        // 应用内文档查看器：全屏覆盖层（从工具栏「文档」进入；已整合原「文档中心」）
        if (showOnlyOffice) {
            BackHandler { showOnlyOffice = false }
            Box(Modifier.fillMaxSize().zIndex(100f).background(cs.background)) {
                QuroDocScreen(onClose = { showOnlyOffice = false })
            }
        }

        // 可交互终端（v109 恢复，纯应用内免权限）：全屏覆盖层（从工具栏「终端」进入）
        if (showTerminal) {
            BackHandler { showTerminal = false }
            Box(Modifier.fillMaxSize().zIndex(100f).background(cs.background)) {
                QuroTerminalScreen(
                    onClose = { showTerminal = false },
                )
            }
        }

        // 外观与对话设置页：全屏覆盖层（从设置「外观与对话」进入，返回关页回设置）
        val liveProfile by vm.userProfile.collectAsState()
        if (showAppearance) {
            BackHandler { showAppearance = false }
            Box(Modifier.fillMaxSize().zIndex(100f).background(cs.background)) {
                QuroAppearanceSettingsScreen(
                    darkMode = darkMode, onToggleDark = onToggleDark,
                    soundOn = soundOn, onToggleSound = { vm.setSoundOn(!soundOn) },
                    enterSend = enterSend, onToggleEnter = { vm.setEnterSend(!enterSend) },
                    fontName = fontNames[fontTier], onCycleFont = { vm.setFontTier((fontTier + 1) % 3) },
                    voiceBallEnabled = voiceBallEnabled, onToggleVoiceBall = onToggleVoiceBall,
                    historyRounds = vm.historyRoundsPref.collectAsState().value,
                    onSetHistoryRounds = { vm.setHistoryRounds(it) },
                    userProfile = liveProfile,
                    onSaveProfile = { vm.saveProfile(it) },
                    onClose = { showAppearance = false },
                    scaled = { scaled(it) }
                )
            }
        }

        // 内置浏览器（WebView）：open_web 工具或工具箱触发打开
        browserUrl?.let { url ->
            Box(Modifier.fillMaxSize().zIndex(100f).background(cs.background)) {
                QuroBrowserScreen(
                    url = url,
                    onClose = { browserUrl = null },
                    onOpenInSystem = { sysUrl ->
                        runCatching {
                            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(sysUrl))
                            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            ctx.startActivity(intent)
                        }
                    },
                )
            }
        }

        // 应用内视频播放器（VideoView 渲染屏）
        if (showVideoPlayer) {
            BackHandler { showVideoPlayer = false }
            Box(Modifier.fillMaxSize().zIndex(120f).background(Color.Black)) {
                QuroVideoPlayerScreen(
                    uri = videoPlayerUri,
                    title = videoPlayerTitle,
                    onClose = { showVideoPlayer = false },
                )
            }
        }

        // 应用内全屏音乐播放器
        if (showMusicPlayer) {
            BackHandler { showMusicPlayer = false }
            Box(Modifier.fillMaxSize().zIndex(120f).background(cs.background)) {
                QuroMusicPlayerScreen(onClose = { showMusicPlayer = false })
            }
        }
    }

    // 监听来自 open_web 工具的内置浏览器打开请求
    LaunchedEffect(Unit) {
        for (u in QuroBrowserBridge.requests) {
            browserUrl = u
        }
    }
}

// ---------------- 顶栏 ----------------
// TopBar / PersonaBar 已抽到 ui/chat/ChatTopBar.kt（ChatTopBar），修复「长模型名 / 长人格名
// 把设置图标测量成 0 宽」的布局塌陷根因。此处仅保留引用。

// ---------------- 人格快捷条 ----------------

/**
 * 用户资料条（显示在对话框顶部：头像 + 名字 + 签名）。
 * 资料来自「设置 > 外观与对话 > 用户资料」。
 */
@Composable
private fun UserProfileBar(
    profile: QuroChatViewModel.UserProfile,
    scaled: (Int) -> androidx.compose.ui.unit.TextUnit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 头像圆圈（显示名字首字母或默认图标）
        Box(
            Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(cs.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                profile.name.first().uppercase(),
                fontSize = 13.sp, color = cs.onPrimaryContainer, fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.width(8.dp))
        // 名字 + 签名（长名字/长签名各自省略号截断，不挤压彼此）
        Column(Modifier.weight(1f)) {
            Text(profile.name, fontSize = scaled(12), fontWeight = FontWeight.Medium, color = cs.onBackground,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (profile.bio.isNotBlank()) {
                Text(profile.bio, fontSize = scaled(10), color = cs.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

// ---------------- 消息列表 ----------------

@Composable
private fun MessageList(
    messages: List<Message>,
    scaled: (Int) -> androidx.compose.ui.unit.TextUnit,
    traceLines: SnapshotStateList<QuroAgentTrace.AgentTraceEvent>,
    onOpenLink: (String) -> Unit,
    onCommand: (String) -> Unit,
    onAskFollowup: (String) -> Unit = {},
    onShare: (String) -> Unit = {},
    onRegenerate: () -> Unit = {},
    onDelete: (List<String>) -> Unit = {},
    currentId: String,
    modifier: Modifier = Modifier
) {
    val cs = MaterialTheme.colorScheme
    val narrow = LocalConfiguration.current.screenWidthDp < 400
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val lastMsg = messages.lastOrNull()

    /**
     * 把「最后一条消息的底部」贴住视口底部（而非把消息顶部对齐视口顶部）。
     *
     * 关键修复（#auto-follow）：长流式 AI 回复往往比一屏还高，旧逻辑 scrollToItem(lastIndex)
     * 只把消息顶部对齐视口顶，最新涌出的 token 仍在输入框下方看不见。这里按
     * 「视口高度 − 最后项高度」计算底部的对齐偏移（项比视口高时偏移为负，scrollToItem
     * 会钳制到最大滚动 → 底部贴底），确保输入框下方的内容始终可见。
     *
     * ⚠️ 索引映射：LazyColumn 第 0 项是顶部日期头，messages[i] 实际位于 LazyList index = i+1，
     * 因此最后一条消息的 LazyList 索引是 messages.size（不是 messages.lastIndex）。
     */
    suspend fun pinToBottom() {
        if (messages.isEmpty()) return
        val lastItemIndex = messages.size
        listState.scrollToItem(lastItemIndex)
        val layoutInfo = listState.layoutInfo
        val lastInfo = layoutInfo.visibleItemsInfo.lastOrNull { it.index == lastItemIndex } ?: return
        val viewportH = layoutInfo.viewportSize.height
        val offset = if (lastInfo.size > viewportH) (viewportH - lastInfo.size) else 0
        listState.scrollToItem(lastItemIndex, offset)
    }

    // 触发①：进入 / 切换会话 → 无条件落到底部看最新一条。
    LaunchedEffect(currentId) {
        pinToBottom()
    }

    // 触发②：用户刚发出新消息（最后一条是用户消息）→ 强制跳到底部，即使此前在中部上滑。
    LaunchedEffect(lastMsg?.id) {
        if (lastMsg != null && lastMsg.mine) {
            pinToBottom()
        }
    }

    // 触发③：流式回复增长（同条消息 content 变）→ 仅当用户停在底部附近才跟随，不打扰阅读历史；
    // 跟随的是「最后一项底部」而非顶部，长消息也不会漏掉输入框下方涌出的新内容。
    val lastContentLen = (lastMsg?.text ?: "").length
    LaunchedEffect(lastContentLen) {
        if (messages.isEmpty()) return@LaunchedEffect
        val lastItemIndex = messages.size
        // 流式增长时无条件把最后一项底部贴住视口底（与 pinToBottom 一致的两步法）：
        // 先 scrollToItem 把最后一项带入布局，再按「视口高 − 项高」算底部对齐偏移。
        // ⚠️ 修复：旧实现要求最后一项已出现在 visibleItemsInfo 才滚动，导致流式回复一长高、
        // 最后一项滑出视口后就不再跟随 →「自动定位最新内容」在中长回复上完全失效。
        // 现在改为无条件先滚入，再算偏移，长回复/输入框下方涌出的新内容始终可见。
        listState.scrollToItem(lastItemIndex)
        val layoutInfo = listState.layoutInfo
        val lastInfo = layoutInfo.visibleItemsInfo.lastOrNull { it.index == lastItemIndex } ?: return@LaunchedEffect
        val viewportH = layoutInfo.viewportSize.height
        // 项比视口高时把其底部贴住视口底部（offset 为负，scrollToItem 钳制到最大滚动）；
        // 否则项能整屏容纳，顶部对齐即可。
        val offset = if (lastInfo.size > viewportH) (viewportH - lastInfo.size) else 0
        listState.scrollToItem(lastItemIndex, offset)
    }
    // 注意：执行轨迹事件已统一在 ChatScreen 顶层订阅一次（单一真相源 traceLines），
    // 此处不再各自 collect 全局流，避免重复订阅 / 跨会话污染。
    val lastToolIdx = messages.indexOfLast { !it.mine && !it.tools.isNullOrEmpty() }
    // 互动条（scroll-to-bottom 交互）：用户上滑离开底部时浮出「回到底部」按钮，
    // 点击即跳到最新一条。与上方三触发自动滚底互补，作手动兜底。
    val isAtBottom by remember { derivedStateOf { !listState.canScrollForward } }
    Box(modifier) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = if (narrow) 8.dp else 16.dp),
        state = listState,
        contentPadding = PaddingValues(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            val today = java.time.LocalDate.now()
            val dateLabel = today.format(java.time.format.DateTimeFormatter.ofPattern("M月d日"))
            Text(dateLabel, fontSize = scaled(12), color = Muted, modifier = Modifier.padding(start = 2.dp, bottom = 2.dp))
        }
        // 执行轨迹已「融和升级」进工具调用输出：
        // 轨迹不再作为底部独立面板，而是内嵌到最近一次助手工具调用卡内（见 ToolsInlineContent）。
        // 纯文本（无工具卡可融）回复不再渲染独立追踪卡，避免与工具卡重复 /「旧 UI 重显」。
        val visibleTraces = traceLines.filter { it.kind != QuroAgentTrace.TraceKind.STATUS }
        // 稳定 key：每条消息的 Message.id 来自 QuroMessage.id（UUID）的 hashCode，唯一且流式更新时不变，
        // 避免流式增量刷新时按 index 重组导致的整段闪烁/错位。
        itemsIndexed(messages, key = { _, msg -> msg.id }) { index, msg ->
            AnimatedVisibility(
                visible = true,
                enter = fadeIn(initialAlpha = 0.35f) + slideInVertically(initialOffsetY = { it / 10 }),
            ) {
                MessageRow(
                    msg, scaled, onOpenLink,
                    isLastToolMsg = index == lastToolIdx,
                    embeddedTrace = if (index == lastToolIdx) visibleTraces else emptyList(),
                    onCommand = { onCommand(it) },
                        onAskFollowup = onAskFollowup,
                        onShare = onShare,
                        onRegenerate = onRegenerate,
                        onDelete = onDelete,
                        narrow = narrow,
                    )
            }
        }
    }
    // ── 互动条：回到底部（紧凑悬浮按钮，右下角贴边，不遮挡内容）──
    AnimatedVisibility(
        visible = !isAtBottom && messages.isNotEmpty(),
        modifier = Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 80.dp),
        enter = fadeIn() + slideInVertically(initialOffsetY = { it / 2 }),
        exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 2 })
    ) {
        Surface(
            onClick = { scope.launch { pinToBottom() } },
            shape = CircleShape,
            color = cs.surface.copy(alpha = 0.92f),
            contentColor = cs.onSurface,
            modifier = Modifier.size(36.dp).shadow(4.dp, CircleShape),
        ) {
            Box(contentAlignment = Alignment.Center) {
                LucideIcon("chevron-down", "回到底部", Modifier.size(18.dp), tint = cs.onSurfaceVariant)
            }
        }
    }
    }
}

/**
 * 执行追踪已从「底部独立面板」迁移为「内嵌到工具调用卡」：见 MessageRow / ToolsInlineContent。
 * 旧 AgentTracePanel 已删除，避免与工具卡重复渲染 / 旧 UI 重显。
 */

@Composable
private fun TraceRow(ev: QuroAgentTrace.AgentTraceEvent) {
    val (color, label, icon) = when (ev.kind) {
        QuroAgentTrace.TraceKind.THOUGHT -> Triple(Color(0xFF9C27B0), "思考", "sparkles")
        QuroAgentTrace.TraceKind.ACTION -> Triple(Color(0xFF2196F3), "行动", "play")
        QuroAgentTrace.TraceKind.RESULT -> Triple(Color(0xFF4CAF50), "结果", "check-circle")
        QuroAgentTrace.TraceKind.STATUS -> Triple(Color(0xFF757575), "状态", "clock")
    }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 1.5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 左侧色条 + 图标胶囊
        Box(
            Modifier
                .size(18.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(color.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            LucideIcon(icon, label, Modifier.size(11.dp), tint = color)
        }
        Spacer(Modifier.width(6.dp))
        // 标签药丸
        Text(
            label,
            fontSize = 10.sp,
            color = color.copy(alpha = 0.85f),
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(color.copy(alpha = 0.1f))
                .padding(horizontal = 5.dp, vertical = 1.dp)
        )
        Spacer(Modifier.width(6.dp))
        // 内容文字：展示文本已在发射时一次性清洗（QuroAgentTrace.cleanTrace → ev.display），
        // 此处直接复用，绝不在重组时跑正则，避免几百事件 × 全量行重组打爆主线程 → ANR。
        Text(
            ev.display,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            lineHeight = 15.sp
        )
    }
}

private fun shareText(context: android.content.Context, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(intent, "分享给"))
}

@Composable
private fun BubbleActionButton(label: String, tint: Color, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
        modifier = Modifier.height(28.dp)
    ) {
        Text(label, fontSize = 11.sp, color = tint)
    }
}

@Composable
private fun MessageRow(
    msg: Message,
    scaled: (Int) -> androidx.compose.ui.unit.TextUnit,
    onOpenLink: (String) -> Unit,
    isLastToolMsg: Boolean = false,
    embeddedTrace: List<QuroAgentTrace.AgentTraceEvent> = emptyList(),
    onCommand: (String) -> Unit = {},
    onAskFollowup: (String) -> Unit = {},
    onShare: (String) -> Unit = {},
    onRegenerate: () -> Unit = {},
    onDelete: (List<String>) -> Unit = {},
    narrow: Boolean = false,
) {
    val cs = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    val avatarSize = if (narrow) 28 else 34
    var showCopyMenu by remember { mutableStateOf(false) }
    var copiedText by remember { mutableStateOf("") }

    // 长按复制反馈：将文本写入剪贴板并显示短暂提示
    fun copyToClipboard(text: String) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("Zorv", text))
        copiedText = if (text.length > 30) text.take(30) + "…" else text
        showCopyMenu = true
    }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (msg.mine) Arrangement.End else Arrangement.Start) {
        if (!msg.mine) {
            // AI 头像可点击 → 编辑灵魂卡
            var showAvatarMenu by remember { mutableStateOf(false) }
            Box(Modifier.clickable { showAvatarMenu = true }) {
                AvatarContent(msg.avatarUri, msg.avatar, avatarSize)
                DropdownMenu(expanded = showAvatarMenu, onDismissRequest = { showAvatarMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("编辑灵魂卡", fontSize = 14.sp) },
                        onClick = { showAvatarMenu = false; onCommand("__edit_soul_card__") },
                        leadingIcon = { Icon(Icons.Filled.Edit, null, Modifier.size(18.dp)) }
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
        }
        Column(Modifier.widthIn(max = if (narrow) 260.dp else 280.dp)) {
            // ── 名字行 + 思考/工具小按钮 ──────────────────────────────
            // 状态提升到 Column 作用域（展开内容在 Row 外渲染）
            var showThink by remember { mutableStateOf(false) }
            var showTools by remember { mutableStateOf(isLastToolMsg) }
            val hasThinkOrTools = !msg.mine && (msg.think != null || !msg.tools.isNullOrEmpty())

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, bottom = if (hasThinkOrTools && (showThink || showTools)) 2.dp else 4.dp)) {
                Text(
                    "${msg.author} · ${msg.time}",
                    fontSize = scaled(11), color = Muted,
                )
                // 仅 AI 消息且含有思考/工具数据时显示小按钮
                if (hasThinkOrTools) {
                    Spacer(Modifier.width(6.dp))
                    // 思考中按钮（紧凑胶囊）
                    if (msg.think != null) {
                        TinyChip(
                            onClick = { showThink = !showThink },
                            containerColor = Accent.copy(alpha = 0.12f),
                        ) {
                            LucideIcon("sparkles", null, Modifier.size(10.dp), tint = Accent)
                            Spacer(Modifier.width(3.dp))
                            Text("思考中", fontSize = 9.sp, color = Accent)
                        }
                    }
                    // 工具调用按钮（紧凑胶囊）
                    if (!msg.tools.isNullOrEmpty()) {
                        Spacer(Modifier.width(4.dp))
                        TinyChip(
                            onClick = { showTools = !showTools },
                            containerColor = cs.primary.copy(alpha = 0.1f),
                        ) {
                            LucideIcon("wrench", null, Modifier.size(10.dp), tint = cs.primary.copy(alpha = 0.7f))
                            Spacer(Modifier.width(3.dp))
                            Text("· ${msg.tools.size} 工具", fontSize = 9.sp, color = cs.primary.copy(alpha = 0.7f))
                        }
                    }
                }
            }
            // 展开的思考内容（在名字行下方，正文上方）
            if (showThink && msg.think != null) {
                ThinkInlineContent(msg.think, scaled)
                Spacer(Modifier.height(6.dp))
            }
            // 展开的工具卡片
            if (showTools && !msg.tools.isNullOrEmpty()) {
                ToolsInlineContent(msg.tools, scaled, embeddedTrace = embeddedTrace)
                Spacer(Modifier.height(6.dp))
            }
            if (msg.attachment != null) {
                AttachmentBubble(msg.attachment, scaled, onDownload = {
                    // 下载附件到应用私有 Download 目录
                    downloadAttachment(ctx, msg.attachment)
                })
                Spacer(Modifier.height(6.dp))
            }
            // ── 正文气泡（思考/工具已移至名字行小按钮）────────────
            if (!msg.text.isNullOrBlank()) {
                // 去掉 LLM 回复里的语音风格标记 (风格)，仅用于显示与复制，不影响朗读
                val displayText = QuroVoiceStyle.strip(msg.text ?: "")
                // 从文本中抽离 AI 内联下发的组件 JSON（如 {"type":"info",...}），剥离泄露的原文并就地渲染为富卡片
                val (cleanText, inlineCards) = remember(displayText) { extractInlineComponents(displayText) }
                val isMine = msg.mine
                val bubbleShape = RoundedCornerShape(16.dp, if (isMine) 4.dp else 16.dp, 16.dp, 16.dp)
                val bubbleColor = if (isMine) AccentSoft else cs.surface
                val borderColor = if (isMine) Color(android.graphics.Color.parseColor("#EAD3C8")) else Line
                val textColor = if (isMine) Color(android.graphics.Color.parseColor("#5A3322")) else cs.onBackground
                // [v382] AI 输出（非 mine）不渲染聊天气泡：仅保留内边距，无背景/边框；用户消息保留气泡。
                val bubbleModifier = if (isMine) {
                    Modifier
                        .clip(bubbleShape)
                        .border(1.dp, borderColor, bubbleShape)
                        .background(bubbleColor)
                        .padding(if (narrow) 10.dp else 12.dp, if (narrow) 8.dp else 10.dp)
                } else {
                    Modifier.padding(if (narrow) 10.dp else 12.dp, if (narrow) 8.dp else 10.dp)
                }
                Box(
                    bubbleModifier
                        .combinedClickable(
                            onClick = { copyPlain(ctx, displayText) },
                            onLongClick = { if (!msg.text.isNullOrBlank()) copyToClipboard(msg.text) }
                        )
                ) {
                    val blocks = remember(cleanText) { parseBlocks(cleanText) }
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        blocks.forEach { blk ->
                            when (blk) {
                                is MsgBlock.Text -> {
                                    // [D1] 接入项目统一富文本渲染器 RichText（ui/dialog/RichText.kt）：
                                    // 原生支持加粗/斜体/行内代码/链接/标题/引用/代码块，主题色自适应，零第三方依赖。
                                    RichText(
                                        text = blk.text,
                                        baseStyle = TextStyle(fontSize = scaled(15), color = textColor, lineHeight = scaled(23)),
                                        onLinkClick = onOpenLink,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                                is MsgBlock.Heading -> {
                                    val size = when (blk.level) {
                                        1 -> scaled(22); 2 -> scaled(19); 3 -> scaled(17)
                                        4 -> scaled(16); 5 -> scaled(15); else -> scaled(14)
                                    }
                                    val rich = remember(blk.text, textColor) { buildRich(blk.text, TextStyle(fontSize = size, fontWeight = FontWeight.Bold, color = textColor, lineHeight = size),
                                        boldColor = if (msg.mine) AccentPress else cs.primary, linkColor = cs.primary,
                                        codeBackground = cs.surfaceVariant.copy(alpha = 0.5f)) }
                                    ClickableText(text = rich, style = TextStyle(fontSize = size, color = textColor, lineHeight = size),
                                        onClick = { offset -> rich.getStringAnnotations("link", offset, offset).firstOrNull()?.item?.let { onOpenLink(it) } },
                                        modifier = Modifier.fillMaxWidth())
                                }
                                is MsgBlock.Quote -> {
                                    Surface(
                                        color = cs.surfaceVariant.copy(alpha = 0.5f),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                    ) {
                                        val rich = remember(blk.text, textColor) { buildRich(blk.text, TextStyle(fontSize = scaled(15), color = textColor, lineHeight = scaled(23), fontStyle = FontStyle.Italic),
                                            boldColor = if (msg.mine) AccentPress else cs.primary, linkColor = cs.primary,
                                            codeBackground = cs.surfaceVariant.copy(alpha = 0.5f)) }
                                        ClickableText(text = rich, style = TextStyle(fontSize = scaled(15), color = textColor, lineHeight = scaled(23), fontStyle = FontStyle.Italic),
                                            onClick = { offset -> rich.getStringAnnotations("link", offset, offset).firstOrNull()?.item?.let { onOpenLink(it) } },
                                            modifier = Modifier.fillMaxWidth().padding(start = 10.dp, top = 6.dp, bottom = 6.dp, end = 8.dp))
                                    }
                                }
                                is MsgBlock.Rule -> HorizontalDivider(color = Line, modifier = Modifier.padding(vertical = 4.dp))
                                is MsgBlock.Table -> RenderTable(blk.header, blk.rows, scaled, textColor, onOpenLink)
                                is MsgBlock.Code -> CodeBlock(lang = blk.lang, code = blk.code, scaled = scaled)
                            }
                        }
                        // 消息自带富组件（一等公民）+ AI 文本内联下发的组件 JSON，合体进气泡
                        val bubbleCards = remember(msg.cards, inlineCards) { msg.cards + inlineCards }
                        bubbleCards.forEach { card ->
                            Spacer(Modifier.height(8.dp))
                            QuroChatCardView(card, onCommand)
                        }
                    }
                    // 复制成功提示（浮在气泡内右下角）
                    if (showCopyMenu && copiedText.isNotBlank()) {
                        Surface(
                            color = cs.primary.copy(alpha = 0.9f),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(4.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                                Icon(Icons.Filled.Check, null, Modifier.size(12.dp), tint = Color.White)
                                Spacer(Modifier.width(4.dp))
                                Text("已复制", fontSize = 11.sp, color = Color.White)
                            }
                        }
                        // 2 秒后自动隐藏
                        androidx.compose.runtime.LaunchedEffect(showCopyMenu) {
                            kotlinx.coroutines.delay(2000L)
                            showCopyMenu = false
                            copiedText = ""
                        }
                    }
                }
                // 气泡操作任务栏（仅 AI 消息）：复制 / 追问 / 分享 / 重试
                if (!msg.mine) {
                    Spacer(Modifier.height(6.dp))
                    val bubbleActions: @Composable () -> Unit = {
                        BubbleActionButton("复制", Muted) { copyToClipboard(displayText) }
                        BubbleActionButton("追问", cs.primary) { onAskFollowup(msg.text ?: "") }
                        BubbleActionButton("分享", Muted) { onShare(msg.text ?: "") }
                        BubbleActionButton("删除", Muted) { onDelete(msg.uids) }
                        BubbleActionButton("重试", Muted) { onRegenerate() }
                    }
                    if (narrow) {
                        FlowRow(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Start,
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) { bubbleActions() }
                    } else {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Start,
                            verticalAlignment = Alignment.CenterVertically,
                        ) { bubbleActions() }
                    }
                }
            }
        }
        if (msg.mine) {
            Spacer(Modifier.width(10.dp))
            AvatarContent(msg.avatarUri, msg.avatar, avatarSize)
        }
    }
}

@Composable
private fun AttachmentBubble(
    att: Attachment,
    scaled: (Int) -> androidx.compose.ui.unit.TextUnit,
    onDownload: (() -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(cs.surfaceVariant)
            .border(1.dp, Line, RoundedCornerShape(12.dp))
            .padding(10.dp, 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val icon = when (att.type) {
                "image" -> "image"
                "video" -> "video"
                else -> "file_text"
            }
            LucideIcon(icon, null, Modifier.size(18.dp), tint = cs.primary)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(att.name, fontSize = scaled(13), color = cs.onSurface, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(att.meta, fontSize = scaled(11), color = Muted)
            }
            if (onDownload != null) {
                IconButton(onClick = onDownload, Modifier.size(32.dp)) {
                    LucideIcon("download", "下载", Modifier.size(16.dp), tint = cs.primary)
                }
            }
        }
        // 图片缩略图预览（其余类型仅提供下载入口）
        if (att.type == "image" && att.path != null) {
            Spacer(Modifier.height(8.dp))
            val bmp = remember(att.path) {
                runCatching { BitmapFactory.decodeFile(att.path).asImageBitmap() }.getOrNull()
            }
            if (bmp != null) {
                Image(
                    bitmap = bmp,
                    contentDescription = att.name,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 200.dp)
                        .clip(RoundedCornerShape(10.dp))
                )
            }
        }
    }
}

@Composable
/**
 * 升级版工具调用输出块 —— 结构化卡片 + 分类图标 + 解析参数/结果 + 风险徽标 + 时间线轨迹。
 */
private fun ToolCallBlock(
    tools: List<ToolCallUi>,
    scaled: (Int) -> androidx.compose.ui.unit.TextUnit,
    withTrace: Boolean = false,
    traceLines: SnapshotStateList<QuroAgentTrace.AgentTraceEvent> = mutableStateListOf(),
) {
    val cs = MaterialTheme.colorScheme
    var expanded by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(cs.surfaceContainerLow.copy(alpha = 0.7f))
            .border(0.7.dp, cs.outlineVariant.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
            .then(
                if (expanded) Modifier.padding(14.dp)
                else Modifier.padding(horizontal = 14.dp, vertical = 11.dp)
            )
    ) {
        // ═══ 标题栏 ═══
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clickable { expanded = !expanded },
        ) {
            if (tools.size == 1) {
                val cat = toolCategory(tools.first().name)
                Box(Modifier.size(22.dp).clip(CircleShape).background(cat.color.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                    LucideIcon(cat.icon, null, Modifier.size(13.dp), tint = cat.color)
                }
            } else {
                Box(Modifier.size(22.dp).clip(RoundedCornerShape(6.dp)).background(cs.primary.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                    LucideIcon("blocks", null, Modifier.size(13.dp), tint = cs.primary)
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (tools.size == 1) tools.first().name else "调用工具 ×${tools.size}",
                fontSize = scaled(12), color = cs.onSurface, fontWeight = FontWeight.SemiBold,
            )
            if (tools.size > 1) {
                Spacer(Modifier.width(6.dp))
                Text("${tools.size}", fontSize = 9.sp, color = cs.onSurface.copy(alpha = 0.5f),
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(cs.primaryContainer.copy(alpha = 0.4f))
                        .padding(horizontal = 5.dp, vertical = 1.dp))
            }
            // 🔧 #879-B5：折叠态标题栏也显示工具结果状态色点（失败红/警告黄/成功绿），
            // 不必展开即可一眼识别异常（此前必须展开 SingleToolCard 才看得到）。
            val aggStatus = run {
                val statuses = tools.mapNotNull { t -> t.result?.let { detectResultStatus(it) } }
                when {
                    statuses.contains(ResultStatus.ERROR) -> ResultStatus.ERROR
                    statuses.contains(ResultStatus.WARNING) -> ResultStatus.WARNING
                    statuses.any { it == ResultStatus.SUCCESS } -> ResultStatus.SUCCESS
                    else -> null
                }
            }
            aggStatus?.let { st ->
                val dotColor = when (st) {
                    ResultStatus.ERROR -> Color(0xFFEF4444)
                    ResultStatus.WARNING -> Color(0xFFF59E0B)
                    else -> Color(0xFF22C55E)
                }
                Box(Modifier.size(8.dp).clip(CircleShape).background(dotColor))
                Spacer(Modifier.width(8.dp))
            }
            Spacer(Modifier.weight(1f))
            LucideIcon(if (expanded) "chevron_up" else "chevron_down", null, Modifier.size(14.dp), tint = Muted)
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(tween(200)) + fadeIn(tween(150)),
            exit = shrinkVertically(tween(180)) + fadeOut(tween(120)),
        ) {
            Column {
                Spacer(Modifier.height(10.dp))
                HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.25f))
                Spacer(Modifier.height(10.dp))

                tools.forEachIndexed { idx, t ->
                    SingleToolCard(t, scaled, index = idx)
                    if (idx < tools.size - 1) Spacer(Modifier.height(8.dp))
                }

                // ═══ 执行轨迹（时间线风格） ═══
                if (withTrace) {
                    Spacer(Modifier.height(10.dp))
                    HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.25f))
                    Spacer(Modifier.height(8.dp))

                    var traceExpanded by remember { mutableStateOf(true) }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { traceExpanded = !traceExpanded },
                    ) {
                        Box(Modifier.size(16.dp).clip(RoundedCornerShape(4.dp)).background(cs.primary.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                            LucideIcon("git-branch", "执行轨迹", Modifier.size(10.dp), tint = cs.primary)
                        }
                        Spacer(Modifier.width(6.dp))
                        Text("执行轨迹", fontSize = scaled(11), color = cs.primary, fontWeight = FontWeight.SemiBold)
                        Text("（实时）", fontSize = 10.sp, color = Muted)
                        Spacer(Modifier.weight(1f))
                        LucideIcon(if (traceExpanded) "chevron_up" else "chevron_down", null, Modifier.size(13.dp), tint = Muted)
                    }
                    AnimatedVisibility(
                        visible = traceExpanded,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut(),
                    ) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(max = 220.dp)
                                .verticalScroll(rememberScrollState())
                                .padding(top = 6.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(cs.surfaceContainerLowest)
                                .padding(8.dp)
                        ) {
                            if (traceLines.isEmpty()) {
                                Row(Modifier.padding(vertical = 8.dp)) {
                                    Text("⏳ ", fontSize = 11.sp)
                                    Text("等待 AI 行动…", fontSize = 11.sp, color = Muted)
                                }
                            } else {
                                // 封顶渲染最近 100 条，避免几百条事件全量重组打爆主线程
                            traceLines.takeLast(100).forEach { ev -> TraceRow(ev) }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ──── 工具调用输出：辅助函数（分类 / 解析 / 格式化） ────

data class ToolCategory(val icon: String, val color: Color, val label: String)

private fun toolCategory(name: String): ToolCategory = when {
    name.startsWith("read_screen") || name.startsWith("tap") || name.startsWith("swipe") ||
    name.startsWith("input_text") || name.startsWith("scroll") || name.startsWith("global_action") ||
    name.startsWith("get_foreground") || name.startsWith("get_screen_state") ->
        ToolCategory("monitor-smartphone", Color(0xFF6366F1), "无障碍控屏")

    name.contains("shizuku") || name.contains("root_exec") || name.contains("root_status") ||
    name.contains("device_admin") || name.contains("lock_screen") || name.contains("set_camera") ->
        ToolCategory("shield-check", Color(0xFFEF4444), "系统权限")

    name.contains("terminal") || name.contains("run_shell") || name.startsWith("linux_") ||
    name.startsWith("open_12306") || name == "home" || name == "open_app" ->
        ToolCategory("terminal", Color(0xFFF59E0B), "终端执行")

    name == "cms_list" || name == "cms_call" || name == "priv_status" ||
    name == "get_device_info" || name.contains("draw_qwen") ||
    name == "open_repo" || name == "open_calendar" || name == "set_alarm" ->
        ToolCategory("cpu", Color(0xFF06B6D4), "系统能力")

    name.contains("list_dir") || name.contains("read_file") || name.contains("write_file") ||
    name.contains("file_") || name.contains("download") ->
        ToolCategory("folder-open", Color(0xFF8B5CF6), "文件工具")

    name.contains("web_search") || name.contains("open_url") || name.startsWith("web_") ->
        ToolCategory("globe", Color(0xFF10B981), "网络能力")

    name == "echo_step" || name == "open_calendar" || name == "set_alarm" ->
        ToolCategory("bell-ring", Color(0xFFEC4899), "提醒与步骤")

    name.startsWith("ui_open_") || name.startsWith("ui_toggle_") ||
    name.startsWith("ui_clear_") || name.startsWith("ui_new_") ->
        ToolCategory("layout-panel", Color(0xFF14B8A6), "界面控制")

    name.contains("image_gen") || name.contains("generate_image") || name.contains("text_to_image") ->
        ToolCategory("image", Color(0xFFEC4899), "图像生成")

    name.contains("video_gen") || name.contains("generate_video") ->
        ToolCategory("video", Color(0xFF8B5CF6), "视频生成")

    name.contains("memory_save") || name.contains("memory_list") || name.contains("memory_search") || name.contains("memory_delete") ->
        ToolCategory("brain", Color(0xFF06B6D4), "记忆库")

    name.contains("summary") || name.contains("context") ->
        ToolCategory("file-text", Color(0xFF14B8A6), "上下文总结")

    name.contains("incubate") || name == "persona_hatch" ->
        ToolCategory("user-round", Color(0xFFF59E0B), "人格孵化")

    else -> ToolCategory("wrench", Color(0xFF64748B), "工具")
}

data class RiskLevel(val label: String, val color: Color, val bgAlpha: Float)

private fun parseRiskLevel(text: String): RiskLevel? {
    val regex = RE_RISK_LEVEL
    val match = regex.find(text) ?: return null
    return when (match.groupValues[1].lowercase()) {
        "critical", "高危" -> RiskLevel("高危", Color(0xFFEF4444), 0.18f)
        "warning", "中危" -> RiskLevel("中危", Color(0xFFF59E0B), 0.16f)
        "normal", "low", "normal" -> RiskLevel("Normal", Color(0xFF22C55E), 0.14f)
        "safe", "安全" -> RiskLevel("安全", Color(0xFF06B6D4), 0.14f)
        else -> RiskLevel(match.groupValues[1], Muted, 0.12f)
    }
}

enum class ResultStatus { SUCCESS, ERROR, WARNING, INFO }

private fun detectResultStatus(result: String): ResultStatus {
    // 🔧 #879-B5：仅扫描前 200 字符判定状态，避免正文里偶然出现「失败/error」字样（如"本操作不会失败"）
    // 的成功结果被误标红。显式 ❌/✗ 前缀优先，仍兜底关键词。
    val head = result.take(200)
    return when {
        result.startsWith("\u274C") || result.startsWith("\u2717") || head.contains("失败") || head.contains("error", ignoreCase = true) -> ResultStatus.ERROR
        result.startsWith("\u26A0\uFE0F") || result.startsWith("\u26A0") || head.contains("警告") || head.contains("warning", ignoreCase = true) -> ResultStatus.WARNING
        result.startsWith("\u2705") || result.startsWith("\u2714") || head.contains("成功") -> ResultStatus.SUCCESS
        else -> ResultStatus.INFO
    }
}

/** 单个工具的渲染卡片 */
@Composable
private fun SingleToolCard(t: ToolCallUi, scaled: (Int) -> androidx.compose.ui.unit.TextUnit, index: Int) {
    val cs = MaterialTheme.colorScheme
    val cat = toolCategory(t.name)
    val status = t.result?.let { detectResultStatus(it) } ?: ResultStatus.INFO
    var cardExpanded by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(cs.surface.copy(alpha = 0.5f))
            .border(0.5.dp, cs.outlineVariant.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
            .then(if (cardExpanded) Modifier.padding(12.dp) else Modifier.padding(horizontal = 10.dp, vertical = 8.dp))
    ) {
        // ── 卡片头部 ──
        Row(verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clickable { cardExpanded = !cardExpanded }) {
            Box(Modifier.size(16.dp).clip(CircleShape).background(cat.color.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center) {
                LucideIcon(cat.icon, cat.label, Modifier.size(9.dp), tint = cat.color)
            }
            Spacer(Modifier.width(6.dp))
            Text(t.name, fontSize = scaled(12), fontWeight = FontWeight.Medium, color = cs.onSurface)
            Spacer(Modifier.width(6.dp))
            Text(cat.label, fontSize = 9.sp, color = cat.color.copy(alpha = 0.75f),
                modifier = Modifier
                    .clip(RoundedCornerShape(3.dp))
                    .background(cat.color.copy(alpha = 0.1f))
                    .padding(horizontal = 4.dp, vertical = 1.dp))
            Spacer(Modifier.weight(1f))
            // 🔧 #879：工具执行耗时（仅当 >0 时显示）
            if (t.durationMs > 0) {
                Text(
                    text = if (t.durationMs >= 1000) "%.1fs".format(t.durationMs / 1000.0) else "${t.durationMs}ms",
                    fontSize = 9.sp, color = Muted, fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.width(6.dp))
            }
            if (!t.result.isNullOrBlank()) {
                val statusColor = when (status) {
                    ResultStatus.SUCCESS -> Color(0xFF22C55E)
                    ResultStatus.ERROR -> Color(0xFFEF4444)
                    ResultStatus.WARNING -> Color(0xFFF59E0B)
                    ResultStatus.INFO -> Muted
                }
                val statusIcon = when (status) {
                    ResultStatus.SUCCESS -> "check-circle-2"
                    ResultStatus.ERROR -> "x-circle"
                    ResultStatus.WARNING -> "alert-triangle"
                    ResultStatus.INFO -> "info"
                }
                LucideIcon(statusIcon, "状态", Modifier.size(13.dp), tint = statusColor)
            }
            Spacer(Modifier.width(4.dp))
            LucideIcon(if (cardExpanded) "chevron_down" else "chevron_right", null,
                Modifier.size(12.dp), tint = Muted)
        }

        // ── 展开内容 ──
        AnimatedVisibility(visible = cardExpanded,
            enter = expandVertically(tween(200)) + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column {
                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.2f))
                Spacer(Modifier.height(8.dp))

                if (t.args.isNotBlank() && t.args != "{}") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LucideIcon("sliders-horizontal", "参数", Modifier.size(11.dp), tint = Muted)
                        Spacer(Modifier.width(4.dp))
                        Text("参数", fontSize = 10.sp, color = Muted, fontWeight = FontWeight.Medium)
                    }
                    Spacer(Modifier.height(4.dp))
                    ParsedArgsContent(t.args, scaled)
                    Spacer(Modifier.height(8.dp))
                }

                if (!t.result.isNullOrBlank()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val sIcon = when (detectResultStatus(t.result)) {
                            ResultStatus.SUCCESS -> "check-circle"
                            ResultStatus.ERROR -> "x-circle"
                            ResultStatus.WARNING -> "alert-triangle"
                            ResultStatus.INFO -> "arrow-right-circle"
                        }
                        val sColor = when (detectResultStatus(t.result)) {
                            ResultStatus.SUCCESS -> Color(0xFF22C55E)
                            ResultStatus.ERROR -> Color(0xFFEF4444)
                            ResultStatus.WARNING -> Color(0xFFF59E0B)
                            ResultStatus.INFO -> Muted
                        }
                        LucideIcon(sIcon, "结果", Modifier.size(11.dp), tint = sColor)
                        Spacer(Modifier.width(4.dp))
                        Text("返回", fontSize = 10.sp, color = Muted, fontWeight = FontWeight.Medium)
                        val risk = parseRiskLevel(t.result)
                        if (risk != null) {
                            Spacer(Modifier.width(6.dp))
                            Text(risk.label, fontSize = 8.sp, color = risk.color, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(risk.color.copy(risk.bgAlpha))
                                    .padding(horizontal = 4.dp, vertical = 1.dp))
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    FormattedResultContent(t.result!!, scaled)
                }

                if (t.name.startsWith("ui_")) {
                    Spacer(Modifier.height(8.dp))
                    val actLabel = if (t.name.startsWith("ui_open_")) "重新打开" else "再次执行"
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { QuroUiActionBridge.dispatch?.invoke(t.name) }) {
                            LucideIcon("external-link", null, Modifier.size(12.dp), tint = cs.primary)
                            Spacer(Modifier.width(4.dp))
                            Text(actLabel, fontSize = 10.sp, color = cs.primary)
                        }
                    }
                }
            }
        }
    }
}

/** 将 JSON 参数字符串解析为 key-value 对并美化展示 */
@Composable
private fun ParsedArgsContent(argsJson: String, scaled: (Int) -> androidx.compose.ui.unit.TextUnit) {
    val cs = MaterialTheme.colorScheme
    val pairs = remember(argsJson) {
        runCatching {
            org.json.JSONObject(argsJson).keys().asSequence().associateWith { key ->
                org.json.JSONObject(argsJson).optString(key, "").take(80)
            }.toList()
        }.getOrDefault(emptyList())
    }

    if (pairs.isEmpty()) {
        Box(Modifier.fillMaxWidth().heightIn(max = 100.dp).verticalScroll(rememberScrollState())) {
            Text(argsJson, fontSize = scaled(11), fontFamily = FontFamily.Monospace,
                color = InkSoft, lineHeight = scaled(16))
        }
        return
    }

    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        pairs.forEach { (key, value) ->
            Row(
                Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(cs.primaryContainer.copy(alpha = 0.35f))
                    .padding(horizontal = 7.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(key, fontSize = 10.sp, fontWeight = FontWeight.Medium, color = cs.primary)
                Text(":", fontSize = 10.sp, color = Muted)
                Text(value.ifEmpty { "\u2014" }, fontSize = 10.sp, color = cs.onSurfaceVariant, maxLines = 1)
            }
        }
    }
}

/** 格式化输出结果 */
@Composable
private fun FormattedResultContent(result: String, scaled: (Int) -> androidx.compose.ui.unit.TextUnit) {
    val cs = MaterialTheme.colorScheme
    val lines = result.lines()
    val isListLike = lines.size > 1 && lines.count { it.trimStart().startsWith("- ") || it.trimStart().startsWith("\u2192 ") } >= lines.size / 2
    val isJson = runCatching { org.json.JSONObject(result); true }.getOrElse { false }

    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(max = if (isListLike || isJson) 280.dp else 160.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(cs.surfaceContainerLowest)
            .verticalScroll(rememberScrollState())
            .padding(10.dp)
    ) {
        when {
            isJson -> JsonFormattedText(result, scaled)
            isListLike -> {
                Column {
                    lines.filter { it.isNotBlank() }.forEach { line ->
                        val trimmed = line.trim()
                        val isBullet = trimmed.startsWith("- ") || trimmed.startsWith("\u2192 ")
                        Row(Modifier.padding(vertical = 0.5.dp)) {
                            if (isBullet) {
                                Text("\u2022 ", fontSize = scaled(11), color = cs.primary, fontWeight = FontWeight.Bold)
                                val content = trimmed.removePrefix("- ").removePrefix("\u2192 ")
                                renderInlineFormatted(content, scaled, cs)
                            } else {
                                Text("  ", fontSize = scaled(11))
                                renderInlineFormatted(trimmed, scaled, cs)
                            }
                        }
                    }
                }
            }
            else -> {
                Column {
                    lines.filterIndexed { i, s -> s.isNotBlank() || i < lines.lastIndex }.forEach { line ->
                        if (line.isBlank()) { Spacer(Modifier.height(4.dp)) }
                        else {
                            renderInlineFormatted(line.trim(), scaled, cs)
                            Spacer(Modifier.height(1.dp))
                        }
                    }
                }
            }
        }
    }
}

/** 渲染带 [方括号] 标签的内联文本 */
@Composable
private fun renderInlineFormatted(text: String, scaled: (Int) -> androidx.compose.ui.unit.TextUnit, cs: androidx.compose.material3.ColorScheme) {
    val bracketPattern = RE_BRACKET
    val match = bracketPattern.matchEntire(text)
    if (match != null) {
        val tag = match.groupValues[1]
        val rest = match.groupValues[2]
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("[", fontSize = scaled(11), color = Muted)
            Text(tag, fontSize = scaled(11), color = Accent, fontWeight = FontWeight.Medium)
            Text("]", fontSize = scaled(11), color = Muted)
            if (rest.isNotBlank()) {
                Spacer(Modifier.width(3.dp))
                Text(rest, fontSize = scaled(11), color = cs.onSurfaceVariant)
            }
        }
    } else {
        Text(text, fontSize = scaled(11), color = cs.onSurfaceVariant, lineHeight = scaled(17))
    }
}

@Composable
private fun JsonFormattedText(jsonStr: String, scaled: (Int) -> androidx.compose.ui.unit.TextUnit) {
    val cs = MaterialTheme.colorScheme
    // JSON 解析在 remember 中完成（非 Composable 树内 try-catch）
    val jsonPairs = remember(jsonStr) {
        runCatching {
            org.json.JSONObject(jsonStr).keys().asSequence().map { key ->
                key to org.json.JSONObject(jsonStr).optString(key, "").take(120)
            }.toList()
        }.getOrDefault(null)
    }

    if (jsonPairs != null) {
        Column {
            jsonPairs.forEachIndexed { idx, (key, value) ->
                Row(Modifier.padding(vertical = 1.dp), verticalAlignment = Alignment.Top) {
                    Text("$key", fontSize = scaled(11), color = Accent, fontWeight = FontWeight.Medium,
                        fontFamily = FontFamily.Monospace)
                    Text(": ", fontSize = scaled(11), color = Muted, fontFamily = FontFamily.Monospace)
                    Text(formatJsonValue(value), fontSize = scaled(11),
                        color = cs.onSurfaceVariant, fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(1f))
                }
                if (idx < jsonPairs.size - 1) Spacer(Modifier.height(2.dp))
            }
        }
    } else {
        Text(jsonStr, fontSize = scaled(11), fontFamily = FontFamily.Monospace,
            color = InkSoft, lineHeight = scaled(17))
    }
}

private fun formatJsonValue(v: String): String = when {
    v.length > 60 -> "${v.take(57)}\u2026"
    v.isEmpty() -> "\"\""
    else -> v
}

@Composable
private fun ThinkBubble(think: ThinkBlock, scaled: (Int) -> androidx.compose.ui.unit.TextUnit) {
    val cs = MaterialTheme.colorScheme
    var expanded by remember { mutableStateOf(false) }
    // 🛡️ 过滤 reasoning 中的 HTML 标签（MiMO 等模型思考过程常输出 HTML 片段），
    //   避免原始 <div style=...> 直接显示在思考气泡里。保留纯文本语义。
    val cleanSteps = remember(think.steps) {
        think.steps.map { s ->
            // 去除 HTML 标签但保留文本内容；再将连续空白压缩为单空格
            s.replace(Regex("<[^>]*>"), " ").replace(Regex("\\s+"), " ").trim()
        }.filter { it.isNotBlank() }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(android.graphics.Color.parseColor("#FBF8F2")))
            .border(1.dp, Line2, RoundedCornerShape(14.dp))
            .then(
                if (expanded) Modifier.padding(12.dp)
                else Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
            )
    ) {
        // 标题栏：始终可见，点击切换展开/收起
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clickable { expanded = !expanded },
        ) {
            LucideIcon("sparkles", "思考", Modifier.size(16.dp), tint = Accent)
            Spacer(Modifier.width(6.dp))
            Text("思考中", fontSize = scaled(12), color = Accent, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            LucideIcon(if (expanded) "chevron_up" else "chevron_down", null, Modifier.size(15.dp), tint = Muted)
        }
        // 思考步骤（展开时显示，带 AnimatedVisibility 动画）
        androidx.compose.animation.AnimatedVisibility(visible = expanded,
            enter = androidx.compose.animation.expandVertically() + fadeIn(),
            exit = androidx.compose.animation.shrinkVertically() + fadeOut()) {
            Column {
                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = Line2.copy(alpha = 0.5f))
                Spacer(Modifier.height(6.dp))
                cleanSteps.forEachIndexed { i, s ->
                    Row(Modifier.padding(vertical = 2.dp)) {
                        Text("${i + 1}. ", fontSize = scaled(13), color = InkSoft)
                        Text(s, fontSize = scaled(13), color = InkSoft)
                    }
                }
            }
        }
    }
}

/**
 * 合体气泡：思考中 + 调用工具合并为单个卡片（解决"思考中/调用工具拆成两个独立气泡"的问题）。
 * 外观与 ThinkBubble 一致（暖色背景 + 思考中标题），展开后先列思考步骤，再列工具调用。
 */
@Composable
private fun ThinkingWithToolsBubble(
    think: ThinkBlock,
    tools: List<ToolCallUi>,
    scaled: (Int) -> androidx.compose.ui.unit.TextUnit,
    withTrace: Boolean = false,
    traceLines: SnapshotStateList<QuroAgentTrace.AgentTraceEvent> = mutableStateListOf(),
) {
    val cs = MaterialTheme.colorScheme
    var expanded by remember { mutableStateOf(false) }
    val cleanSteps = remember(think.steps) {
        think.steps.map { s ->
            s.replace(Regex("<[^>]*>"), " ").replace(Regex("\\s+"), " ").trim()
        }.filter { it.isNotBlank() }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(android.graphics.Color.parseColor("#FBF8F2")))
            .border(1.dp, Line2, RoundedCornerShape(14.dp))
            .then(
                if (expanded) Modifier.padding(12.dp)
                else Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
            )
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clickable { expanded = !expanded },
        ) {
            LucideIcon("sparkles", "思考", Modifier.size(16.dp), tint = Accent)
            Spacer(Modifier.width(6.dp))
            Text("思考中", fontSize = scaled(12), color = Accent, fontWeight = FontWeight.SemiBold)
            if (tools.isNotEmpty()) {
                Spacer(Modifier.width(6.dp))
                // 工具调用计数角标
                Box(
                    Modifier
                        .clip(CircleShape)
                        .background(cs.primary.copy(alpha = 0.12f))
                        .padding(horizontal = 5.dp, vertical = 1.dp)
                ) {
                    Text("· ${tools.size} 工具", fontSize = 9.sp, color = cs.primary.copy(alpha = 0.7f))
                }
            }
            Spacer(Modifier.weight(1f))
            LucideIcon(if (expanded) "chevron_up" else "chevron_down", null, Modifier.size(15.dp), tint = Muted)
        }
        AnimatedVisibility(visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()) {
            Column {
                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = Line2.copy(alpha = 0.5f))
                Spacer(Modifier.height(6.dp))
                // 思考步骤
                cleanSteps.forEachIndexed { i, s ->
                    Row(Modifier.padding(vertical = 2.dp)) {
                        Text("${i + 1}. ", fontSize = scaled(13), color = InkSoft)
                        Text(s, fontSize = scaled(13), color = InkSoft)
                    }
                }
                // 工具调用（合体在思考步骤下方）
                if (tools.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider(color = Line2.copy(alpha = 0.3f))
                    Spacer(Modifier.height(6.dp))
                    tools.forEachIndexed { idx, t ->
                        SingleToolCard(t, scaled, index = idx)
                        if (idx < tools.size - 1) Spacer(Modifier.height(6.dp))
                    }
                    // 执行轨迹（如果启用）
                    if (withTrace) {
                        Spacer(Modifier.height(8.dp))
                        HorizontalDivider(color = Line2.copy(alpha = 0.25f))
                        Spacer(Modifier.height(6.dp))
                        var traceExpanded by remember { mutableStateOf(true) }
                        Row(verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable { traceExpanded = !traceExpanded }) {
                            Text("执行追踪", fontSize = scaled(10), color = Muted)
                            Spacer(Modifier.weight(1f))
                            LucideIcon(if (traceExpanded) "chevron_up" else "chevron_down", null,
                                Modifier.size(12.dp), tint = Muted)
                        }
                        AnimatedVisibility(traceExpanded) {
                            Column {
                                traceLines.takeLast(30).forEach { ev ->
                                    Text("  · [${ev.kind}] ${ev.tag}: ${ev.summary}",
                                        fontSize = scaled(9), color = InkSoft.copy(alpha = 0.6f),
                                        fontFamily = FontFamily.Monospace)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── 思考/工具内联展开组件（名字行小按钮点击后显示）───────────────────

/**
 * 名字行的超紧凑胶囊按钮（思考中 / 工具），高度 ~20dp、9sp，替代默认 32dp 的 AssistChip。
 */
@Composable
private fun TinyChip(
    onClick: () -> Unit,
    containerColor: Color,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .height(20.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(containerColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) { content() }
    }
}

/**
 * 紧凑型思考内容展开区：点击名字行「✦思考中」按钮后显示在按钮下方。
 * 比 ThinkBubble 更紧凑，适合嵌入消息头部区域。
 */
@Composable
private fun ThinkInlineContent(think: ThinkBlock, scaled: (Int) -> androidx.compose.ui.unit.TextUnit) {
    val cleanSteps = remember(think.steps) {
        think.steps.map { s ->
            s.replace(Regex("<[^>]*>"), " ").replace(Regex("\\s+"), " ").trim()
        }.filter { it.isNotBlank() }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color(android.graphics.Color.parseColor("#FBF8F2")))
            .border(1.dp, Line2, RoundedCornerShape(10.dp))
            .padding(10.dp, 8.dp)
    ) {
        cleanSteps.forEachIndexed { i, s ->
            Row(Modifier.padding(vertical = 1.dp)) {
                Text("${i + 1}. ", fontSize = scaled(11), color = InkSoft)
                Text(s, fontSize = scaled(11), color = InkSoft, lineHeight = scaled(16))
            }
        }
    }
}

/**
 * 紧凑型工具调用展开区：点击名字行「·N 工具」按钮后显示在按钮下方。
 */
@Composable
private fun ToolsInlineContent(
    tools: List<ToolCallUi>,
    scaled: (Int) -> androidx.compose.ui.unit.TextUnit,
    embeddedTrace: List<QuroAgentTrace.AgentTraceEvent> = emptyList(),
) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(cs.surfaceVariant.copy(alpha = 0.5f))
            .border(1.dp, Line.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
            .padding(10.dp, 8.dp)
    ) {
        tools.forEachIndexed { idx, t ->
            val cat = toolCategory(t.name)
            val status = t.result?.let { detectResultStatus(it) } ?: ResultStatus.INFO
            val statusColor = when (status) {
                ResultStatus.SUCCESS -> Color(0xFF22C55E)
                ResultStatus.ERROR -> Color(0xFFEF4444)
                ResultStatus.WARNING -> Color(0xFFF59E0B)
                else -> Muted
            }
            Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                LucideIcon(cat.icon, null, Modifier.size(13.dp), tint = cat.color)
                Spacer(Modifier.width(5.dp))
                Text(t.name, fontSize = scaled(11), color = cs.primary, fontWeight = FontWeight.Medium)
                Spacer(Modifier.weight(1f))
                Box(Modifier.size(8.dp).clip(CircleShape).background(statusColor))
            }
            if (t.args.isNotBlank()) {
                Text("  参数: ${formatJsonValue(t.args)}",
                    fontSize = scaled(9), color = Muted, fontFamily = FontFamily.Monospace)
            }
            if (!t.result.isNullOrBlank()) {
                Text("  结果: ${formatJsonValue(t.result)}",
                    fontSize = scaled(9), color = Muted, fontFamily = FontFamily.Monospace)
            }
            if (idx < tools.size - 1) {
                Spacer(Modifier.height(4.dp))
                HorizontalDivider(color = Line.copy(alpha = 0.2f))
            }
        }
        // ── 执行轨迹（内嵌到工具卡，作为本次工具调用的「过程轨迹」，不再独立浮层）──
        if (embeddedTrace.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = Line.copy(alpha = 0.25f))
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                LucideIcon("sparkles", null, Modifier.size(13.dp), tint = cs.primary)
                Spacer(Modifier.width(5.dp))
                Text("执行轨迹", fontSize = scaled(11), color = cs.primary, fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.height(4.dp))
            embeddedTrace.forEach { ev -> TraceRow(ev) }
        }
    }
}

// ---------------- 输入区 ----------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
/**
 * 聊天输入框上方的「正在播放」内联指示卡：后台有音乐播放时显示，点击打开全屏播放器。
 * 订阅 [QuroMediaController] 的全局播放状态（由 [QuroMediaService] 写入）。
 */
private fun QuroMusicPlayerCard(
    onOpen: () -> Unit,
    scaled: (Int) -> androidx.compose.ui.unit.TextUnit,
) {
    val cs = MaterialTheme.colorScheme
    val media by QuroMediaController.state.collectAsState()
    if (media.uri.isEmpty()) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(cs.surfaceVariant)
            .border(1.dp, Line, RoundedCornerShape(12.dp))
            .clickable { onOpen() }
            .padding(10.dp, 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.MusicNote, null, Modifier.size(20.dp), tint = cs.primary)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                media.title.ifEmpty { "本地音乐" },
                fontSize = scaled(13), color = cs.onSurface, maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (media.isPlaying) "正在播放" else "已暂停",
                fontSize = scaled(11), color = cs.onSurfaceVariant, maxLines = 1,
            )
        }
        Icon(
            if (media.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            null, Modifier.size(18.dp), tint = cs.onSurfaceVariant,
        )
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun Composer(
    deepThink: Boolean,
    onToggleThink: () -> Unit,
    attachments: List<QuroAttachment>,
    onRemoveAttach: (QuroAttachment) -> Unit,
    onAttach: () -> Unit,
    onSend: (String) -> Unit,
    autoSaveMemory: Boolean = true,
    onToggleAutoSave: () -> Unit = {},
    text: TextFieldValue,
    onTextChange: (TextFieldValue) -> Unit,
    enterSend: Boolean,
    busy: Boolean,
    onStop: () -> Unit,
    onOpenMusicPlayer: () -> Unit = {},
    autoRead: Boolean = false,
    onToggleAutoRead: () -> Unit = {},
    visionEnabled: Boolean = false,
    onToggleVision: () -> Unit = {},
    voiceInputEnabled: Boolean = false,
    onVoiceInput: () -> Unit = {},
    onOpenSkills: () -> Unit = {},
    scaled: (Int) -> androidx.compose.ui.unit.TextUnit
) {
    val cs = MaterialTheme.colorScheme

    Column(
        Modifier
            .fillMaxWidth()
            .background(cs.background)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        // 深度思考 + 权限模式（合并为一条可收起控制条）
        ChatPermissionModeBar(
            deepThink = deepThink,
            onToggleThink = onToggleThink,
            autoSaveMemory = autoSaveMemory,
            onToggleAutoSave = onToggleAutoSave,
            autoRead = autoRead,
            onToggleAutoRead = onToggleAutoRead,
            visionEnabled = visionEnabled,
            onToggleVision = onToggleVision,
        )
        Spacer(Modifier.height(10.dp))
        // 正在播放（内联指示卡）：当前有音乐在后台播放时显示，点击打开全屏播放器
        QuroMusicPlayerCard(onOpen = onOpenMusicPlayer, scaled = scaled)
        // 附件预览
        if (attachments.isNotEmpty()) {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                attachments.forEach { att ->
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(cs.surfaceVariant)
                            .border(1.dp, Line, RoundedCornerShape(10.dp))
                            .padding(8.dp, 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        LucideIcon("file_text", null, Modifier.size(15.dp), tint = cs.primary)
                        Spacer(Modifier.width(6.dp))
                        Text(att.name, fontSize = scaled(12), color = cs.onSurface, maxLines = 1)
                        Spacer(Modifier.width(6.dp))
                        Box(Modifier.clickable { onRemoveAttach(att) }) {
                            LucideIcon("x", "移除", Modifier.size(14.dp), tint = Muted)
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        // 输入条
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .border(1.dp, Line, RoundedCornerShape(18.dp))
                .background(cs.surface)
                .padding(6.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            IconButton(onClick = onAttach, Modifier.size(44.dp).padding(2.dp)) {
                Icon(Icons.Filled.Add, "上传文件", Modifier.size(22.dp), tint = cs.onSurfaceVariant)
            }
            // [v382] 输入框技能按钮：恢复被 v373 checkout 冲掉的 sparkles「选择技能」入口（v372 Composer 输入行原在 上传 与 语音 之间）
            IconButton(onClick = onOpenSkills, Modifier.size(44.dp).padding(2.dp)) {
                LucideIcon("sparkles", "选择技能", Modifier.size(22.dp), tint = cs.onSurfaceVariant)
            }
            if (voiceInputEnabled) {
                IconButton(onClick = onVoiceInput, Modifier.size(44.dp).padding(2.dp)) {
                    Icon(Icons.Filled.Mic, "语音输入", Modifier.size(22.dp), tint = cs.onSurfaceVariant)
                }
            }
            BasicTextField(
                value = text,
                onValueChange = { onTextChange(it) },
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 24.dp, max = 110.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 9.dp, horizontal = 4.dp),
                textStyle = TextStyle(fontSize = scaled(15), color = cs.onSurface),
                keyboardOptions = KeyboardOptions(imeAction = if (enterSend) ImeAction.Done else ImeAction.Default),
                keyboardActions = KeyboardActions(
                    onDone = {
                        if (enterSend) {
                            onSend(text.text); onTextChange(TextFieldValue(""))
                        }
                    }
                ),
                decorationBox = { inner ->
                        if (text.text.isEmpty()) {
                            Text("和 Zorv 说点什么…", fontSize = scaled(15), color = Muted)
                        }
                    inner()
                },
                cursorBrush = SolidColor(cs.primary)
            )
            if (busy) {
                // 生成中：发送按钮变身「停止生成」（红色实心方块），点击打断当前回复
                IconButton(
                    onClick = onStop,
                    modifier = Modifier.size(44.dp).padding(2.dp)
                ) {
                    LucideIcon("square", "停止生成", Modifier.size(22.dp), tint = cs.error)
                }
            } else {
                IconButton(
                    onClick = { onSend(text.text); onTextChange(TextFieldValue("")) },
                    modifier = Modifier.size(44.dp).padding(2.dp),
                    enabled = text.text.isNotBlank() || attachments.isNotEmpty()
                ) {
                    LucideIcon("arrow_up", "发送", Modifier.size(22.dp),
                        tint = if (text.text.isNotBlank() || attachments.isNotEmpty()) cs.primary else Muted)
                }
            }
        }
    }
}


// ---------------- 历史抽屉 ----------------

@Composable
private fun HistoryDrawer(
    history: List<HistoryItem>,
    onClose: () -> Unit,
    onNew: () -> Unit,
    onPick: (String) -> Unit,
    onCopyAll: () -> Unit = {},
    onDelete: (String) -> Unit,
    onDeleteAll: () -> Unit,
    scaled: (Int) -> androidx.compose.ui.unit.TextUnit,
    generatingIds: Set<String> = emptySet(),
) {
    val cs = MaterialTheme.colorScheme
    var lastGroup by remember { mutableStateOf("") }
    var pendingDeleteId by remember { mutableStateOf<String?>(null) }
    var showDeleteAllConfirm by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxHeight()
            .width(300.dp)
            .background(cs.surface)
            .border(1.dp, Line, RoundedCornerShape(0.dp))
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp, 16.dp, 16.dp, 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("对话", fontSize = scaled(18), fontWeight = FontWeight.SemiBold, color = cs.onSurface,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Serif)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onCopyAll, Modifier.size(36.dp)) {
                Icon(Icons.Filled.ContentCopy, "复制全部对话", Modifier.size(20.dp), tint = cs.onSurface)
            }
            IconButton(onClick = { showDeleteAllConfirm = true }, Modifier.size(36.dp)) {
                Icon(Icons.Filled.Delete, "删除全部对话", Modifier.size(20.dp), tint = cs.error)
            }
            IconButton(onClick = onClose, Modifier.size(36.dp)) {
                LucideIcon("x", "关闭", Modifier.size(20.dp), tint = cs.onSurface)
            }
        }
        Row(
            Modifier
                .padding(horizontal = 16.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(AccentSoft)
                .clickable(onClick = onNew)
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LucideIcon("square_pen", null, Modifier.size(18.dp), tint = Accent)
            Spacer(Modifier.width(10.dp))
            Text("新建对话", fontSize = scaled(14), color = AccentPress, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(8.dp))
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            history.forEach { item ->
                if (item.group != lastGroup) {
                    lastGroup = item.group
                    Text(item.group, fontSize = scaled(12), color = Muted,
                        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (item.active) AccentSoft else cs.surface)
                        .clickable(onClick = { onPick(item.id) })
                        .padding(12.dp, 10.dp)
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(item.title, fontSize = scaled(14), color = cs.onSurface, fontWeight = if (item.active) FontWeight.SemiBold else FontWeight.Normal)
                        Text(item.sub, fontSize = scaled(12), color = Muted,
                            maxLines = 1, modifier = Modifier.padding(top = 2.dp))
                    }
                    // #862：该会话仍在后台生成时显示「生成中」徽标，让切走后续跑可见
                    if (item.id in generatingIds) {
                        Surface(
                            color = AccentSoft,
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.padding(start = 8.dp)
                        ) {
                            Text("生成中", fontSize = scaled(11), color = AccentPress,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                        }
                    }
                    Text(item.time, fontSize = scaled(11), color = Muted, modifier = Modifier.padding(start = 8.dp))
                    IconButton(onClick = { pendingDeleteId = item.id }, Modifier.size(32.dp)) {
                        Icon(Icons.Filled.Delete, "删除", Modifier.size(18.dp), tint = cs.onSurfaceVariant)
                    }
                }
            }
        }
    }

    if (pendingDeleteId != null) {
        AlertDialog(
            onDismissRequest = { pendingDeleteId = null },
            title = { Text("删除对话") },
            text = { Text("确定删除这条对话记录吗？删除后不可恢复。") },
            confirmButton = {
                TextButton(onClick = { onDelete(pendingDeleteId!!); pendingDeleteId = null }) {
                    Text("删除", color = cs.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteId = null }) { Text("取消") }
            }
        )
    }
    if (showDeleteAllConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteAllConfirm = false },
            title = { Text("清空全部对话") },
            text = { Text("确定删除全部对话记录吗？此操作不可恢复。") },
            confirmButton = {
                TextButton(onClick = { onDeleteAll(); showDeleteAllConfirm = false }) {
                    Text("全部删除", color = cs.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteAllConfirm = false }) { Text("取消") }
            }
        )
    }
}

// ---------------- 底部弹层（统一遮罩 + 上滑） ----------------

@Composable
private fun SheetOverlay(
    sheet: SheetType?,
    lastSheet: SheetType?,
    onDismiss: () -> Unit,
    backEnabled: Boolean = true,
    scaled: (Int) -> androidx.compose.ui.unit.TextUnit,
    modelGroups: List<ModelGroup>,
    selectedModel: ChatModel,
    onSelectModel: (ChatModel) -> Unit,
    onAddModel: (String, String) -> Unit,
    isFetchingModels: Boolean = false,
    modelFetchError: String? = null,
    onFetchModels: () -> Unit = {},
    personaList: List<Persona>,
    selectedPersona: Persona,
    onSelectPersona: (Persona) -> Unit,
    onAddPersona: (String, String) -> Unit,
    onPickFile: (String) -> Unit,
    onExport: () -> Unit,
    onClear: () -> Unit,
    onOpenBrowser: (String) -> Unit,
    // 设置底部弹层（UI 结构来自 MoWenApp，功能保留 Zorv AI 现有设置项）
    settingsDarkMode: Boolean,
    onSettingsToggleDark: () -> Unit,
    settingsSoundOn: Boolean,
    onSettingsToggleSound: () -> Unit,
    settingsEnterSend: Boolean,
    onSettingsToggleEnter: () -> Unit,
    settingsFontName: String,
    onSettingsCycleFont: () -> Unit,
    onOpenModelConfig: () -> Unit,
    onOpenFeatureModelConfig: () -> Unit,
    onOpenPermission: () -> Unit,
    onOpenCms: () -> Unit,
    onOpenToolbox: () -> Unit,
    onOpenKnowledge: () -> Unit,
    onOpenTerminal: () -> Unit,
    onOpenPlugins: () -> Unit,
    onOpenSkills: () -> Unit,
    onOpenBots: () -> Unit,
    onManagePersona: () -> Unit,
    onOpenVoice: () -> Unit,
    onOpenTts: () -> Unit,
    onOpenStt: () -> Unit,
    onOpenOnlyOffice: () -> Unit,
    onOpenVoiceService: () -> Unit,
    onClearChat: () -> Unit,
    settingsVoiceBallEnabled: Boolean,
    onSettingsToggleVoiceBall: (Boolean) -> Unit,
    settingsAiReplyNotify: Boolean,
    onSettingsToggleAiReplyNotify: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenAci: () -> Unit,
    onOpenMcp: () -> Unit,
    onOpenSystemStatus: () -> Unit,
    onOpenComponentGallery: () -> Unit,
    onOpenAppearance: () -> Unit,
    vm: QuroChatViewModel,
    onSendText: (String) -> Unit,
    onOpenSchedule: () -> Unit = {},
) {
    val shown = sheet ?: lastSheet
    // 设置底部弹层：系统返回键关闭弹层。关键修复——当任意「设置子页」浮层开着时禁用本回调，
    // 保证返回键永远由最上层子页先处理（子页关 → 回到设置 → 再关设置），实现逐级返回而非跳层。
    BackHandler(enabled = backEnabled) { onDismiss() }
    val sheetShape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)

    Box(Modifier.fillMaxSize().zIndex(50f)) {
        // 遮罩：仅当 sheet 打开时可见且可点击关闭
        AnimatedVisibility(
            visible = sheet != null,
            enter = fadeIn(tween(220)),
            exit = fadeOut(tween(200)),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.42f))
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = onDismiss
                    )
            )
        }
        // 弹层主体：从底部滑入
        AnimatedVisibility(
            visible = sheet != null,
            enter = slideInVertically(initialOffsetY = { it }),
            exit = slideOutVertically(targetOffsetY = { it }),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            val surfaceColor = Card
            Column(
                Modifier
                    .fillMaxWidth()
                    .shadow(8.dp, sheetShape)
                    .clip(sheetShape)
                    .background(surfaceColor)
                    .border(1.dp, Line, sheetShape)
            ) {
                val settingsCtx = LocalContext.current
                when (shown) {
                    SheetType.Model -> ModelSheetContent(modelGroups, selectedModel, onSelectModel, onAddModel, scaled, isFetchingModels, modelFetchError, onFetch = onFetchModels)
                    SheetType.Persona -> PersonaSheetContent(personaList, selectedPersona, onSelectPersona, onAddPersona, scaled)
                    SheetType.Upload -> UploadSheetContent(
                        onPickFile, onOpenBrowser, onClear,
                        onOpenVoiceService, onOpenToolbox, onOpenCms, onOpenSkills, onOpenKnowledge, onOpenTerminal,
                        onOpenStt,
                        { q -> onSendText(q) },
                        scaled,
                        onOpenSchedule = onOpenSchedule,
                        onOpenBots = onOpenBots
                    )
                    SheetType.Voice -> VoiceSheetContent(onOpenTts, onOpenStt, onOpenVoice, scaled)
                    SheetType.Settings -> SettingsSheetContent(
                        settingsDarkMode, onSettingsToggleDark,
                        settingsSoundOn, onSettingsToggleSound,
                        settingsEnterSend, onSettingsToggleEnter,
                        settingsFontName, onSettingsCycleFont,
                        onOpenModelConfig, onOpenFeatureModelConfig, onOpenPermission, onOpenCms, onOpenToolbox, onOpenKnowledge, onOpenTerminal, onOpenPlugins, onOpenSkills,
                        onManagePersona, onOpenVoiceService,
                        onClearChat, settingsVoiceBallEnabled, onSettingsToggleVoiceBall,
                    settingsAiReplyNotify, onSettingsToggleAiReplyNotify,
                        onOpenAbout, onOpenAci, onOpenMcp, onOpenSystemStatus, onOpenComponentGallery, onOpenAppearance, onExport, onClear, scaled
                    )
                    else -> {}
                }
            }
        }
    }
}

@Composable
private fun SheetHeader(title: String, sub: String, scaled: (Int) -> androidx.compose.ui.unit.TextUnit) {
    val cs = MaterialTheme.colorScheme
    Box(Modifier.fillMaxWidth().padding(top = 10.dp), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.size(38.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(Line2))
    }
    Column(Modifier.fillMaxWidth().padding(20.dp, 12.dp, 20.dp, 4.dp)) {
        Text(title, fontSize = scaled(18), fontWeight = FontWeight.SemiBold, color = cs.onSurface,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Serif)
        Text(sub, fontSize = scaled(12), color = Muted, modifier = Modifier.padding(top = 4.dp))
    }
}

// ---------------- 设置底部弹层（UI 结构照搬 MoWenApp，功能保留 Zorv AI 现有设置项） ----------------

@Composable
private fun SettingsSheetContent(
    darkMode: Boolean, onToggleDark: () -> Unit,
    soundOn: Boolean, onToggleSound: () -> Unit,
    enterSend: Boolean, onToggleEnter: () -> Unit,
    fontName: String, onCycleFont: () -> Unit,
    onOpenModelConfig: () -> Unit,
    onOpenFeatureModelConfig: () -> Unit,
    onOpenPermission: () -> Unit,
    onOpenCms: () -> Unit,
    onOpenToolbox: () -> Unit,
    onOpenKnowledge: () -> Unit,
    onOpenTerminal: () -> Unit,
    onOpenPlugins: () -> Unit,
    onOpenSkills: () -> Unit,
    onManagePersona: () -> Unit,
    onOpenVoiceService: () -> Unit,
    onClearChat: () -> Unit,
    voiceBallEnabled: Boolean, onToggleVoiceBall: (Boolean) -> Unit,
    settingsAiReplyNotify: Boolean, onSettingsToggleAiReplyNotify: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenAci: () -> Unit,
    onOpenMcp: () -> Unit,
    onOpenSystemStatus: () -> Unit,
    onOpenComponentGallery: () -> Unit,
    onOpenAppearance: () -> Unit,
    onExport: () -> Unit, onClear: () -> Unit,
    scaled: (Int) -> androidx.compose.ui.unit.TextUnit
) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().heightIn(max = 480.dp)
            .verticalScroll(rememberScrollState()).padding(bottom = 20.dp)
    ) {
        SheetHeader("设置", "偏好、外观与功能，随手可调。", scaled)
        GroupCaption("外观与对话")
        SetGroup {
            SetRowClickable(Icons.Filled.ColorLens, "外观与对话", "深色模式 · 字号 · 提示音 · 回车发送 · 语音球", "", onOpenAppearance, scaled)
        }
        GroupCaption("语音")
        SetGroup {
            SetRowClickable(Icons.Filled.VolumeUp, "语音服务", "合成 / 识别 / 设置", "", onOpenVoiceService, scaled)
        }
        GroupCaption("功能")
        SetGroup {
            SetRowClickable(Icons.Filled.Tune, "模型配置", "推理引擎、参数与能力范围", "", onOpenModelConfig, scaled)
            HorizontalDivider(color = Line, thickness = 1.dp, modifier = Modifier.padding(horizontal = 12.dp))
            SetRowClickable(Icons.Filled.Build, "功能模型配置", "上下文总结 / 记忆 / 人格孵化 / 视频 / 图片", "", onOpenFeatureModelConfig, scaled)
            HorizontalDivider(color = Line, thickness = 1.dp, modifier = Modifier.padding(horizontal = 12.dp))
            SetRowClickable(Icons.Filled.Security, "权限", "L1 无障碍 / L2 Shizuku / L3 设备管理员 / L4 ROOT", "", onOpenPermission, scaled)
            HorizontalDivider(color = Line, thickness = 1.dp, modifier = Modifier.padding(horizontal = 12.dp))
            SetRowClickable(Icons.Filled.Info, "系统状态", "设备 / 权限能力 / 模块运行态 / 人格心跳", "", onOpenSystemStatus, scaled)
            HorizontalDivider(color = Line, thickness = 1.dp, modifier = Modifier.padding(horizontal = 12.dp))
            SetRowClickable(Icons.Filled.Info, "组件画廊", "可视化组件库：卡片 / 按钮 / 输入 / 交互 / 覆盖层", "", onOpenComponentGallery, scaled)
            HorizontalDivider(color = Line, thickness = 1.dp, modifier = Modifier.padding(horizontal = 12.dp))
            SetRowClickable(Icons.Filled.Extension, "插件运行时", "小程序式插件 Demo", "", onOpenPlugins, scaled)
            HorizontalDivider(color = Line, thickness = 1.dp, modifier = Modifier.padding(horizontal = 12.dp))
            SetRowClickable(Icons.Filled.Person, "灵魂注入", "灵魂注入 · 灵魂卡 · 记忆库", "", onManagePersona, scaled)
            HorizontalDivider(color = Line, thickness = 1.dp, modifier = Modifier.padding(horizontal = 12.dp))
            SetRowClickable(Icons.Filled.Hub, "MCP 服务", "把内置工具以 MCP 协议暴露给本机客户端", "", onOpenMcp, scaled)
            HorizontalDivider(color = Line, thickness = 1.dp, modifier = Modifier.padding(horizontal = 12.dp))
            SetRowClickable(Icons.Filled.Public, "ACI 管理中心", "已发现第三方 App / 绑定状态 / 能力清单 / 手动注册刷新重绑", "", onOpenAci, scaled)
        }
        GroupCaption("通知")
        SetGroup {
            SetRow(Icons.Filled.Notifications, "AI 回复通知", "离开软件时系统弹窗通知 / 桌面卡片", settingsAiReplyNotify, onSettingsToggleAiReplyNotify, scaled)
        }
        GroupCaption("数据")
        SetGroup {
            SetRowClickable(Icons.Filled.Download, "导出对话", "", "导出为文本", onExport, scaled)
            HorizontalDivider(color = Line, thickness = 1.dp, modifier = Modifier.padding(horizontal = 12.dp))
            SetRowClickable(Icons.Filled.DeleteSweep, "清除全部对话", "", "", onClear, scaled, danger = true)
        }
        GroupCaption("关于")
        SetGroup {
            SetRowClickable(Icons.Filled.Info, "关于 Zorv AI", "项目地址 / 开源许可 / 开发者", "", onOpenAbout, scaled)
        }
        Text("Zorv AI · v${BuildConfig.VERSION_NAME}",
            fontSize = scaled(11), color = Muted, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
    }

}

/**
 * 外观与对话设置子页（从设置「外观与对话」进入，仍在设置体系内）。
 * 合并原「外观」与「对话」两个分区：深色模式 / 字号 / 回复提示音 / 回车发送 / 悬浮语音球。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuroAppearanceSettingsScreen(
    darkMode: Boolean, onToggleDark: () -> Unit,
    soundOn: Boolean, onToggleSound: () -> Unit,
    enterSend: Boolean, onToggleEnter: () -> Unit,
    fontName: String, onCycleFont: () -> Unit,
    voiceBallEnabled: Boolean, onToggleVoiceBall: (Boolean) -> Unit,
    historyRounds: Int? = null, onSetHistoryRounds: (Int?) -> Unit = {},
    userProfile: QuroChatViewModel.UserProfile,
    onSaveProfile: (QuroChatViewModel.UserProfile) -> Unit,
    onClose: () -> Unit,
    scaled: (Int) -> androidx.compose.ui.unit.TextUnit,
) {
    val cs = MaterialTheme.colorScheme
    var showUserProfileEditor by remember { mutableStateOf(false) }
    var showHistoryPicker by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(cs.background)) {
        TopAppBar(
            title = { Text("外观与对话") },
            navigationIcon = {
                IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = cs.background),
        )
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 20.dp)
        ) {
            GroupCaption("外观")
            SetGroup {
                SetRow(Icons.Filled.DarkMode, "深色模式", "夜间自动降低亮度", darkMode, onToggleDark, scaled)
                HorizontalDivider(color = Line, thickness = 1.dp, modifier = Modifier.padding(horizontal = 12.dp))
                SetRowClickable(Icons.Filled.FormatSize, "字号", "", fontName, onCycleFont, scaled)
            }
            GroupCaption("对话")
            SetGroup {
                SetRow(Icons.Filled.Notifications, "回复提示音", "", soundOn, onToggleSound, scaled)
                HorizontalDivider(color = Line, thickness = 1.dp, modifier = Modifier.padding(horizontal = 12.dp))
                SetRow(Icons.Filled.Keyboard, "回车发送", "关闭后回车换行", enterSend, onToggleEnter, scaled)
                HorizontalDivider(color = Line, thickness = 1.dp, modifier = Modifier.padding(horizontal = 12.dp))
                SetRow(Icons.Filled.Mic, "悬浮语音球", "STT → LLM → TTS 随时语音对话", voiceBallEnabled, { onToggleVoiceBall(!voiceBallEnabled) }, scaled)
                HorizontalDivider(color = Line, thickness = 1.dp, modifier = Modifier.padding(horizontal = 12.dp))
                SetRowClickable(
                    Icons.Filled.History, "保留对话轮数", "限制发给模型的近期轮次",
                    value = when (historyRounds) {
                        null -> "跟随模型默认"
                        else -> "${historyRounds} 轮"
                    },
                    onClick = { showHistoryPicker = true },
                    scaled = scaled,
                )
            }
            GroupCaption("用户资料")
            SetGroup {
                SetRowClickable(Icons.Filled.Person, "头像与名字",
                    if (userProfile.name.isNotBlank()) userProfile.name else "设置你的资料",
                    "", { showUserProfileEditor = true }, scaled)
            }
        }
        // 用户资料编辑弹窗
        if (showUserProfileEditor) {
            UserProfileEditDialog(
                initial = userProfile,
                onSave = {
                    onSaveProfile(it)
                    showUserProfileEditor = false
                },
                onDismiss = { showUserProfileEditor = false },
                scaled = scaled,
            )
        }
        // 保留对话轮数选择器：预设 10 / 20 / 50 轮，或「全部」(null = 跟随模型默认)
        if (showHistoryPicker) {
            AlertDialog(
                onDismissRequest = { showHistoryPicker = false },
                confirmButton = {},
                title = { Text("保留对话轮数") },
                text = {
                    Column {
                        val presets = listOf(
                            10 to "10 轮",
                            20 to "20 轮",
                            50 to "50 轮",
                            null to "全部（跟随模型默认）",
                        )
                        presets.forEach { (n, label) ->
                            val selected = historyRounds == n
                            Row(
                                Modifier.fillMaxWidth().clickable { onSetHistoryRounds(n); showHistoryPicker = false }
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (selected) Icon(Icons.Filled.Check, null, Modifier.size(18.dp), tint = cs.primary)
                                else Spacer(Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(label, fontSize = scaled(14), color = cs.onSurface)
                            }
                        }
                    }
                },
            )
        }
    }
}

/**
 * 用户资料编辑弹窗（名字 / 头像路径 / 签名）。
 * 头像暂支持输入 URI 路径（后续可扩展为相册选取/拍照）。
 */
@Composable
private fun UserProfileEditDialog(
    initial: QuroChatViewModel.UserProfile,
    onSave: (QuroChatViewModel.UserProfile) -> Unit,
    onDismiss: () -> Unit,
    scaled: (Int) -> androidx.compose.ui.unit.TextUnit,
) {
    val cs = MaterialTheme.colorScheme
    var name by remember(initial.name) { mutableStateOf(initial.name) }
    var avatarUri by remember(initial.avatarUri) { mutableStateOf(initial.avatarUri) }
    var bio by remember(initial.bio) { mutableStateOf(initial.bio) }

    val ctx = LocalContext.current
    val cropLauncher = rememberLauncherForActivityResult(CropImageContract()) { result ->
        if (result.isSuccessful) {
            result.uriContent?.let { cropped ->
                runCatching {
                    val dir = File(ctx.filesDir, "avatars")
                    dir.mkdirs()
                    val dest = File(dir, "user_avatar.jpg")
                    ctx.contentResolver.openInputStream(cropped)?.use { input ->
                        dest.outputStream().use { output -> input.copyTo(output) }
                    }
                    avatarUri = dest.absolutePath
                }
            }
        }
    }
    val pickAvatar = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        cropLauncher.launch(
            CropImageContractOptions(
                uri,
                CropImageOptions(guidelines = CropImageView.Guidelines.ON, cropShape = CropImageView.CropShape.OVAL)
            )
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = cs.surface,
        shape = RoundedCornerShape(20.dp),
        title = { Text("用户资料", fontSize = scaled(18), fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // 头像预览 + 导入（复制进应用私有目录，路径稳定可持久）
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AvatarContent(avatarUri, name.ifBlank { "U" }, 56)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Button(onClick = { pickAvatar.launch("image/*") }) {
                            Text("导入图片", fontSize = scaled(13))
                        }
                        if (avatarUri.isNotBlank()) {
                            Spacer(Modifier.height(6.dp))
                            TextButton(onClick = { avatarUri = "" }) {
                                Text("清除头像", fontSize = scaled(12))
                            }
                        }
                    }
                }
                // 名字
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("你的名字", fontSize = scaled(12)) },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    placeholder = { Text("AI 会知道你叫什么", fontSize = scaled(12), color = cs.onSurfaceVariant) },
                )
                // 签名/简介
                OutlinedTextField(
                    value = bio,
                    onValueChange = { bio = it },
                    label = { Text("个人签名（可选）", fontSize = scaled(12)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 80.dp),
                    minLines = 2, maxLines = 3,
                )
            }
        },
        confirmButton = {
            Button(onClick = { onSave(QuroChatViewModel.UserProfile(name, avatarUri, bio)) }) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}


@Composable
private fun ModelSheetContent(
    groups: List<ModelGroup>,
    selected: ChatModel,
    onSelect: (ChatModel) -> Unit,
    onAdd: (String, String) -> Unit,
    scaled: (Int) -> androidx.compose.ui.unit.TextUnit,
    isFetching: Boolean = false,
    fetchError: String? = null,
    onFetch: () -> Unit = {},
) {
    val cs = MaterialTheme.colorScheme

    Column(
        Modifier.fillMaxWidth().heightIn(max = 480.dp)
            .verticalScroll(rememberScrollState()).padding(bottom = 20.dp)
    ) {
        SheetHeader("选择模型", "「当前配置」为你在设置中配置的真实模型；「可用模型」为从接口拉取到的真实列表，点选即切换。", scaled)
        // 手动拉取入口：v396 起进入不再自动联网，需用户点此按钮才拉取，结果会本地缓存
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(AccentSoft)
                .clickable(enabled = !isFetching) { onFetch() }
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LucideIcon(if (isFetching) "loader" else "refresh_cw", null, Modifier.size(18.dp), tint = Accent)
            Spacer(Modifier.width(8.dp))
            Text(
                if (isFetching) "正在拉取可用模型…" else "从 API 拉取可用模型（手动）",
                fontSize = scaled(13), color = AccentPress, fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.weight(1f))
            LucideIcon("chevron_right", null, Modifier.size(15.dp), tint = Muted)
        }
        // 拉取状态：失败 / 仅当前配置（无可用预设）
        if (fetchError != null) {
            Text("拉取失败：$fetchError。请到「模型配置」填写正确的 Base URL / API Key 后重试。",
                fontSize = scaled(12), color = Color(android.graphics.Color.parseColor("#C0432F")),
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        }
        if (!isFetching && fetchError == null && groups.size <= 1) {
            Text("当前仅有「当前配置」的模型。需要更多可选模型，请到「模型配置」填写 Base URL 与 API Key 后拉取，或手动填入模型名。",
                fontSize = scaled(12), color = Muted,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        }
        groups.forEach { g ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(
                    if (g.provider == "当前配置") Accent else Sage
                ))
                Spacer(Modifier.width(8.dp))
                Text(g.provider, fontSize = scaled(13), fontWeight = FontWeight.SemiBold,
                    color = if (g.provider == "当前配置") AccentPress else cs.onSurface)
            }
            g.models.forEach { m ->
                val sel = m.id == selected.id || (m.id.isNotBlank() && m.id == selected.id)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (sel) AccentSoft else cs.surface)
                        .border(1.dp, if (sel) Accent else Line, RoundedCornerShape(14.dp))
                        .clickable { onSelect(m) }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(cs.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) { Text(m.mark, fontSize = scaled(15), color = cs.primary, fontWeight = FontWeight.SemiBold) }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(m.name, fontSize = scaled(14), color = cs.onSurface, fontWeight = FontWeight.SemiBold)
                        Text(m.desc, fontSize = scaled(11), color = Muted, modifier = Modifier.padding(top = 2.dp), maxLines = 2)
                    }
                    Box(Modifier.size(18.dp).clip(CircleShape).border(2.dp, if (sel) Accent else Line2, CircleShape),
                        contentAlignment = Alignment.Center) {
                        if (sel) Box(Modifier.size(10.dp).clip(CircleShape).background(Accent))
                    }
                }
            }
        }
        // 前往完整模型配置（替代内联 provider+id 表单）
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                .border(1.dp, Line2, RoundedCornerShape(12.dp))
                .clickable { onAdd("", "") }
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LucideIcon("settings", "模型配置", Modifier.size(20.dp), tint = Accent)
            Spacer(Modifier.width(8.dp))
            Text("前往模型配置 · 设置 API Key / Base URL / 参数", fontSize = scaled(13), color = AccentPress, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            LucideIcon("chevron_right", null, Modifier.size(15.dp), tint = Muted)
        }
        Text("当前选中「${selected.name}」· 切换即生效",
            fontSize = scaled(11), color = Muted, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
    }
}

@Composable
private fun PersonaSheetContent(
    list: List<Persona>,
    selected: Persona,
    onSelect: (Persona) -> Unit,
    onAdd: (String, String) -> Unit,
    scaled: (Int) -> androidx.compose.ui.unit.TextUnit
) {
    val cs = MaterialTheme.colorScheme

    Column(
        Modifier.fillMaxWidth().heightIn(max = 480.dp)
            .verticalScroll(rememberScrollState()).padding(bottom = 20.dp)
    ) {
        SheetHeader("选择灵魂", "每个灵魂是不同语气与专长的「对话伙伴」，切换即换一种相处方式。", scaled)
        list.forEach { p ->
            val sel = p.name == selected.name
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (sel) AccentSoft else cs.surface)
                    .border(1.dp, if (sel) Accent else Line, RoundedCornerShape(14.dp))
                    .clickable { onSelect(p) }
                    .padding(14.dp)
            ) {
                AvatarContent(p.avatarUri, p.name, 42)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.fillMaxWidth()) {
                    Text("${p.name} · ${p.role}", fontSize = scaled(15), color = cs.onSurface, fontWeight = FontWeight.SemiBold)
                    Text(p.desc, fontSize = scaled(12), color = Muted, modifier = Modifier.padding(top = 3.dp))
                    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        p.tags.forEach { tag ->
                            Box(Modifier.clip(RoundedCornerShape(6.dp)).background(cs.surfaceVariant).padding(4.dp, 2.dp)) {
                                Text(tag, fontSize = scaled(11), color = cs.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
        // 新建人格（打开完整 Zorv AI 人格创建对话框：头像/描述/角色设定/开场白/聊天设定/标签/AI孵化）
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                .border(1.dp, Line2, RoundedCornerShape(12.dp))
                .clickable { onAdd("", "") }
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(20.dp).clip(CircleShape).background(Accent), contentAlignment = Alignment.Center) {
                Text("+", fontSize = scaled(16), color = Color.White, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.width(8.dp))
            Text("灵魂注入 · 完整设定（头像/角色/语气/标签）", fontSize = scaled(13), color = AccentPress, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            LucideIcon("chevron_right", null, Modifier.size(15.dp), tint = Muted)
        }
        Text("人格仅改变语气与专长，不改变事实与能力边界",
            fontSize = scaled(11), color = Muted, modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp))
    }
}

// 注：底部快捷设置（深色模式/字号/提示音/回车发送/悬浮语音球）已合并进设置底部弹层，
// 通过 SheetType.Settings 呈现，UI 结构取自 MoWenApp，功能保留 Zorv AI 现有设置项。

@Composable
private fun UploadSheetContent(
    onPickFile: (String) -> Unit,
    onOpenBrowser: (String) -> Unit,
    onClearChat: () -> Unit,
    onOpenVoiceService: () -> Unit,
    onOpenToolbox: () -> Unit,
    onOpenCms: () -> Unit,
    onOpenSkills: () -> Unit,
    onOpenKnowledge: () -> Unit,
    onOpenTerminal: () -> Unit,
    onOpenStt: () -> Unit,
    onAiSearch: (String) -> Unit,
    scaled: (Int) -> androidx.compose.ui.unit.TextUnit,
    onOpenSchedule: () -> Unit = {},
    onOpenBots: () -> Unit = {},
) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var showUploadChooser by remember { mutableStateOf(false) }
    var showAiSearch by remember { mutableStateOf(false) }

    // 应用内媒体浏览器（QuroMediaBrowser）覆盖层状态
    var showMediaBrowser by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxWidth().heightIn(max = 480.dp)
            .verticalScroll(rememberScrollState()).padding(bottom = 20.dp)
    ) {
        SheetHeader("工具", "点击下方工具快速执行操作；也可导入工具 / 让 AI 自写工具，导入成功即成为可调用工具。", scaled)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            ToolTile({ Icon(Icons.Filled.Add, "上传", Modifier.size(22.dp), tint = cs.primary) }, "上传", { showUploadChooser = true }, scaled)
            ToolTile({ Icon(Icons.Filled.Public, "AI 浏览器", Modifier.size(22.dp), tint = cs.primary) }, "AI 浏览器", { showAiSearch = true }, scaled)
            ToolTile({ Icon(Icons.Filled.VolumeUp, "语音服务", Modifier.size(22.dp), tint = cs.primary) }, "语音服务", onOpenVoiceService, scaled)
            ToolTile({ Icon(Icons.Filled.Build, "工具箱", Modifier.size(22.dp), tint = cs.primary) }, "工具箱", onOpenToolbox, scaled)
            ToolTile({ Icon(Icons.Filled.Extension, "CMS v2", Modifier.size(22.dp), tint = cs.primary) }, "CMS v2", onOpenCms, scaled)
            ToolTile({ Icon(Icons.Filled.Description, "知识库", Modifier.size(22.dp), tint = cs.primary) }, "知识库", onOpenKnowledge, scaled)
            ToolTile({ Icon(Icons.Filled.Terminal, "终端", Modifier.size(22.dp), tint = cs.primary) }, "终端", onOpenTerminal, scaled)
            ToolTile({ LucideIcon("trash_2", "清屏", Modifier.size(22.dp), tint = cs.primary) }, "清屏", onClearChat, scaled)
            ToolTile({ LucideIcon("sparkles", "技能", Modifier.size(22.dp), tint = cs.primary) }, "技能", onOpenSkills, scaled)
            ToolTile({ Icon(Icons.Filled.Schedule, "定时", Modifier.size(22.dp), tint = cs.primary) }, "定时", onOpenSchedule, scaled)
            ToolTile({ LucideIcon("bot", "机器人", Modifier.size(22.dp), tint = cs.primary) }, "机器人", onOpenBots, scaled)
        }
    }
    showMediaBrowser?.let { k ->
        QuroMediaBrowser(
            kind = k,
            onPick = { uri, title ->
                showMediaBrowser = null
                if (k == "music") {
                    val intent = Intent(ctx, QuroMediaService::class.java)
                        .putExtra(QuroMediaService.EXTRA_URI, uri.toString())
                        .putExtra(QuroMediaService.EXTRA_TITLE, title)
                    runCatching { ctx.startForegroundService(intent) }
                    QuroMusicLauncher.request()
                } else {
                    QuroVideoLauncher.open(uri.toString(), title)
                }
            },
            onClose = { showMediaBrowser = null },
        )
    }
    if (showUploadChooser) {
        AlertDialog(
            onDismissRequest = { showUploadChooser = false },
            confirmButton = {},
            title = { Text("选择上传类型（可多选）", color = cs.onSurface) },
            text = {
                Column(Modifier.fillMaxWidth()) {
                    listOf("图片" to "image/*", "文件" to "*/*", "视频" to "video/*").forEach { (label, mime) ->
                        TextButton(onClick = { showUploadChooser = false; onPickFile(mime) }, Modifier.fillMaxWidth()) {
                            Text(label, color = cs.onSurface, fontSize = scaled(14))
                        }
                    }
                }
            }
        )
    }
    if (showAiSearch) {
        var q by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAiSearch = false },
            confirmButton = {
                TextButton(onClick = {
                    val text = q.trim()
                    if (text.isNotEmpty()) {
                        if (text.startsWith("http://") || text.startsWith("https://")) {
                            onOpenBrowser(text)
                        } else {
                            onAiSearch("请联网搜索：$text")
                        }
                    }
                    showAiSearch = false
                    q = ""
                }) { Text("搜索 / 打开") }
            },
            dismissButton = { TextButton(onClick = { showAiSearch = false }) { Text("取消") } },
            title = { Text("AI 浏览器 · 联网搜索") },
            text = {
                Column(Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        q, { q = it },
                        label = { Text("搜索词或网址") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("如：今天的新闻 / https://example.com") },
                    )
                    Spacer(Modifier.height(6.dp))
                    Text("输入网址→在内置浏览器打开；输入关键词→交给 AI 联网检索（后台可用）。", fontSize = scaled(11), color = Muted)
                }
            }
        )
    }
}

@Composable
private fun VoiceSheetContent(
    onOpenTts: () -> Unit,
    onOpenStt: () -> Unit,
    onOpenVoice: () -> Unit = {},
    scaled: (Int) -> androidx.compose.ui.unit.TextUnit
) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().heightIn(max = 420.dp)
            .verticalScroll(rememberScrollState()).padding(bottom = 20.dp)
    ) {
        SheetHeader("语音服务", "语音合成、语音识别与语音设置入口。", scaled)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            ToolTile({ Icon(Icons.Filled.VolumeUp, "语音合成", Modifier.size(22.dp), tint = cs.primary) }, "语音合成 (TTS)", onOpenTts, scaled)
            ToolTile({ Icon(Icons.Filled.Mic, "语音识别", Modifier.size(22.dp), tint = cs.primary) }, "语音识别 (STT)", onOpenStt, scaled)
            ToolTile({ Icon(Icons.Filled.Settings, "语音设置", Modifier.size(22.dp), tint = cs.primary) }, "语音设置", onOpenVoice, scaled)
        }
    }
}

@Composable
private fun ToolTile(
    icon: @Composable () -> Unit,
    title: String,
    onClick: () -> Unit,
    scaled: (Int) -> androidx.compose.ui.unit.TextUnit
) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.widthIn(min = 72.dp, max = 96.dp).clip(RoundedCornerShape(12.dp)).background(cs.surfaceVariant)
            .border(1.dp, Line, RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        icon()
        Spacer(Modifier.height(6.dp))
        Text(title, fontSize = scaled(11), color = cs.onSurface, maxLines = 1)
    }
}

// ---------------- 富文本（**加粗**） ----------------

private fun buildRich(text: String, base: TextStyle, boldColor: Color, linkColor: Color, codeBackground: Color): AnnotatedString {
    val parts = text.split("**")
    return buildAnnotatedString {
        parts.forEachIndexed { i, p ->
            if (p.isEmpty()) return@forEachIndexed
            if (i % 2 == 1) {
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = boldColor)) { append(p) }
            } else {
                // 普通文本段内检测 HTML 标签并渲染
                val htmlParsed = parseInlineHtml(p, base.color, linkColor, codeBackground)
                append(htmlParsed)
            }
        }
    }
}

/** 解析行内 HTML 标签为 AnnotatedString，并把 <a> 链接标注为 "link" 注解（由上层 Text.onClick 接管打开内置浏览器）。 */
private fun parseInlineHtml(raw: String, defaultColor: Color, linkColor: Color, codeBackground: Color): AnnotatedString {
    // 1) HTML 实体解码（&amp; &lt; &nbsp; …），避免模型输出原样显示
    val decoded = decodeHtmlEntities(raw)
    // 2) 块级标签转换行
    val text = decoded
        .replace(RE_INLINE_BR, "\n")
        .replace(RE_INLINE_P, "\n")
        .replace(RE_INLINE_DIV, "\n")
        .replace(RE_INLINE_H, "\n")
        .replace(RE_INLINE_LI_OPEN, "\n• ")
        .replace(RE_INLINE_LI_CLOSE, "")
        .replace(RE_INLINE_LIST_CLOSE, "\n")
        .replace(RE_INLINE_PRE, "\n")
        .replace(RE_INLINE_BLOCKQUOTE, "\n")

    return buildAnnotatedString {
        // 用正则拆分：HTML标签 + 文本交替处理（含 u / s / del）
        val matches = RE_INLINE_TAG.findAll(text)

        data class Seg(val isTag: Boolean, val text: String)
        val segs = matches.map { m ->
            val g1 = m.groupValues[1]
            val g2 = m.groupValues[2]
            when {
                g1.isNotBlank() -> Seg(true, g1.trim())
                else -> Seg(false, g2)
            }
        }.toList()

        // 栈式解析格式标签
        data class FmtState(
            val bold: Boolean = false,
            val italic: Boolean = false,
            val strike: Boolean = false,
            val underline: Boolean = false,
            val code: Boolean = false,
            val link: String? = null,
            val color: Color? = null,
        )

        fun styleFor(state: FmtState) = SpanStyle(
            fontWeight = if (state.bold || state.code) FontWeight.SemiBold else FontWeight.Normal,
            fontStyle = if (state.italic) FontStyle.Italic else FontStyle.Normal,
            fontFamily = if (state.code) FontFamily.Monospace else null,
            background = if (state.code) codeBackground else Color.Unspecified,
            color = state.color ?: (if (state.link != null) linkColor else defaultColor),
            textDecoration = when {
                state.link != null -> TextDecoration.Underline
                state.underline -> TextDecoration.Underline
                state.strike -> TextDecoration.LineThrough
                else -> null
            },
        )

        var state = FmtState()
        val stack = ArrayDeque<FmtState>()

        segs.forEach { seg ->
            if (!seg.isTag) {
                if (seg.text.isNotEmpty()) {
                    val url = state.link
                    if (url != null) {
                        val mark = pushStringAnnotation("link", url)
                        withStyle(styleFor(state)) { append(seg.text) }
                        pop(mark)
                    } else {
                        withStyle(styleFor(state)) { append(seg.text) }
                    }
                }
            } else {
                val tag = seg.text.lowercase()
                when {
                    tag in listOf("<b>", "<strong>") -> { stack.addLast(state); state = state.copy(bold = true) }
                    tag in listOf("</b>", "</strong>") -> { state = stack.removeLastOrNull() ?: FmtState() }
                    tag in listOf("<i>", "<em>") -> { stack.addLast(state); state = state.copy(italic = true) }
                    tag in listOf("</i>", "</em>") -> { state = stack.removeLastOrNull() ?: FmtState() }
                    tag == "<u>" -> { stack.addLast(state); state = state.copy(underline = true) }
                    tag == "</u>" -> { state = stack.removeLastOrNull() ?: FmtState() }
                    tag in listOf("<s>", "<del>") -> { stack.addLast(state); state = state.copy(strike = true) }
                    tag in listOf("</s>", "</del>") -> { state = stack.removeLastOrNull() ?: FmtState() }
                    tag == "<code>" -> { stack.addLast(state); state = state.copy(code = true) }
                    tag == "</code>" -> { state = stack.removeLastOrNull() ?: FmtState() }
                    tag.startsWith("<a ") || tag == "<a>" -> {
                        val href = RE_HREF.find(tag)?.groupValues?.get(1)
                        stack.addLast(state); state = state.copy(link = href)
                    }
                    tag == "</a>" -> { state = stack.removeLastOrNull() ?: FmtState() }
                    tag.startsWith("<span") -> {
                        val c = RE_SPAN_COLOR.find(tag)?.groupValues?.get(1)?.let { parseColorOrNull(it) }
                        stack.addLast(state); state = state.copy(color = c ?: state.color)
                    }
                    tag == "</span>" -> { state = stack.removeLastOrNull() ?: FmtState() }
                    // <pre> / <h1-6> / 未知标签忽略（块级换行已处理）
                }
            }
        }
    }
}

/** 解码常见 HTML 实体，避免模型输出里的 &amp; &lt; &nbsp; 等原样显示。 */
private fun decodeHtmlEntities(s: String): String {
    return s
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&nbsp;", " ")
        .replace("&copy;", "©")
        .replace("&reg;", "®")
        .replace("&trade;", "™")
        .replace("&hellip;", "…")
        .replace("&mdash;", "—")
        .replace("&ndash;", "–")
        .replace("&raquo;", "»")
        .replace("&laquo;", "«")
        .replace("&bull;", "•")
}

/** 解析 HTML 颜色（仅支持 #rrggbb / #rgb），失败返回 null。供 <span style="color"> 使用。 */
private fun parseColorOrNull(s: String): Color? = try {
    if (s.startsWith("#")) Color(android.graphics.Color.parseColor(s)) else null
} catch (_: Exception) { null }

// ---------------- 消息分块（文本 / 代码块 / 块级 HTML / Markdown） ----------------

private sealed class MsgBlock {
    data class Text(val text: String) : MsgBlock()
    data class Code(val lang: String, val code: String) : MsgBlock()
    data class Heading(val level: Int, val text: String) : MsgBlock()
    data class Quote(val text: String) : MsgBlock()
    data class Rule(val text: String = "") : MsgBlock()
    data class Table(val header: List<String>, val rows: List<List<String>>) : MsgBlock()
}

/**
 * 从 AI 文本消息里抽离「内联组件 JSON」：形如 {"type":"info","body":"..."} 的结构化组件。
 * 返回「去掉组件 JSON 后的干净文本」与「解析成功的组件列表」。
 * - 只有 type 属于已知组件类型才会被抽离并渲染，其它 JSON（如代码块里的 schema）原样保留；
 * - 抽离时连同 JSON 前后的多余空行一起裁掉，避免气泡里留下大段空白。
 */
private fun extractInlineComponents(text: String): Pair<String, List<QuroChatCard>> {
    if (text.isBlank()) return text to emptyList()
    val cards = mutableListOf<QuroChatCard>()
    val sb = StringBuilder()
    var i = 0
    while (i < text.length) {
        val brace = text.indexOf('{', i)
        if (brace < 0) { sb.append(text.substring(i)); break }
        sb.append(text.substring(i, brace))
        val end = findBalancedBrace(text, brace)
        if (end < 0) { sb.append(text.substring(brace)); break }
        val candidate = text.substring(brace, end + 1)
        val card = runCatching { parseComponentSpec(candidate) }.getOrNull()
        if (card != null) {
            cards.add(card)
            i = end + 1
            // 跳过紧随其后的空白与换行，避免残留空行
            while (i < text.length && text[i].isWhitespace()) i++
        } else {
            sb.append(candidate)
            i = end + 1
        }
    }
    // v150：腾讯元宝回答链接 → 气泡内预览卡（原生安卓点击查看体验）。
    // 仅元宝域名走预览卡；其余外链保留既有内联 ClickableText 行为。
    val yuanbaoRe = RE_YUANBAO
    val seen = mutableSetOf<String>()
    val out = StringBuilder()
    var last = 0
    for (m in yuanbaoRe.findAll(sb)) {
        out.append(sb.substring(last, m.range.first))
        val url = m.value.trimEnd(')', ']', '}', '.', ',', ';', '"', '\'')
        if (seen.add(url)) {
            cards.add(
                QuroChatCard.YuanbaoCard(
                    id = "yb_" + url.hashCode().toString(36).replace("-", "m"),
                    title = "腾讯元宝回答",
                    url = url,
                )
            )
        }
        last = m.range.last + 1
    }
    out.append(sb.substring(last))
    return out.toString() to cards
}

/**
 * 从 [start]（'{' 下标）起，按括号深度匹配到配对的 '}'，返回其下标；
 * 字符串字面量（含转义）内的括号不参与计数。匹配失败返回 -1。
 */
private fun findBalancedBrace(text: String, start: Int): Int {
    if (start < 0 || start >= text.length || text[start] != '{') return -1
    var depth = 0
    var inStr = false
    var esc = false
    for (j in start until text.length) {
        val c = text[j]
        if (inStr) {
            if (esc) esc = false
            else if (c == '\\') esc = true
            else if (c == '"') inStr = false
            continue
        }
        when (c) {
            '"' -> inStr = true
            '{' -> depth++
            '}' -> {
                depth--
                if (depth == 0) return j
            }
        }
    }
    return -1
}

// ── ANR 修复（v384）：以下正则全部预编译为文件级常量，只编译一次。
//    原实现在各解析函数内 `Regex(...)` / `.toRegex()`，每次 Compose 重组都重新编译，
//    走 ICU native PatternNative.compileImpl；几百消息 × 多正则 × 每帧重组 → 主线程卡死（见 ANR 报告）。
private val RE_FENCE = Regex("```(\\w*)\\n?([\\s\\S]*?)```")
private val RE_BLOCK = Regex("(?is)<h([1-6])>(.*?)</h\\1>|<blockquote>(.*?)</blockquote>|<hr\\s*/?>|<table>(.*?)</table>|<(ul|ol)>(.*?)</\\6>")
private val RE_HR = Regex("(?i)<hr")
private val RE_LI = Regex("(?is)<li>(.*?)</li>")
private val RE_P = Regex("(?i)</?p>")
private val RE_BR = Regex("(?i)<br\\s*/?>")
private val RE_HEADING_LINE = Regex("^\\s*#{1,6}\\s+.+")
private val RE_HEADING_STRIP = Regex("^\\s*#{1,6}\\s+")
private val RE_QUOTE_LINE = Regex("^\\s*>\\s?.+")
private val RE_QUOTE_STRIP = Regex("^\\s*>\\s?")
private val RE_LIST_LINE = Regex("^\\s*[-*]\\s+.+")
private val RE_LIST_STRIP = Regex("^\\s*[-*]\\s+")
private val RE_TR = Regex("(?is)<tr>(.*?)</tr>")
private val RE_TD = Regex("(?is)<t[hd]>(.*?)</t[hd]>")
private val RE_INLINE_BR = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)
private val RE_INLINE_P = Regex("</?p>", RegexOption.IGNORE_CASE)
private val RE_INLINE_DIV = Regex("</?div>", RegexOption.IGNORE_CASE)
private val RE_INLINE_H = Regex("</(h[1-6])>", RegexOption.IGNORE_CASE)
private val RE_INLINE_LI_OPEN = Regex("<li>", RegexOption.IGNORE_CASE)
private val RE_INLINE_LI_CLOSE = Regex("</li>", RegexOption.IGNORE_CASE)
private val RE_INLINE_LIST_CLOSE = Regex("</(ul|ol)>", RegexOption.IGNORE_CASE)
private val RE_INLINE_PRE = Regex("</pre>", RegexOption.IGNORE_CASE)
private val RE_INLINE_BLOCKQUOTE = Regex("</blockquote>", RegexOption.IGNORE_CASE)
private val RE_INLINE_TAG = Regex("(</?(?:b|strong|em|i|u|s|del|code|a|span|pre|h[1-6])[^>]*>)|([^<]+)")
private val RE_HREF = Regex("href=\"([^\"]+)\"")
private val RE_SPAN_COLOR = Regex("(?i)color:\\s*([#\\w]+)")
// 以下为消息渲染/卡片热路径中残留的逐次编译正则，同样预编译（同一条 ANR 根因）。
private val RE_RISK_LEVEL = Regex("""风险级别[：:]\s*(\S+)""")
private val RE_BRACKET = Regex("""\[([^\]]+)\]\s*(.*)""")
private val RE_YUANBAO = Regex("""https?://(?:yb|yuanbao)\.tencent\.com/\S+""")
private val RE_HTML_HEAD_BODY = Regex("""<(head|body)\b""", RegexOption.IGNORE_CASE)
private val RE_HTML_TAG = Regex("""<(html|head|body|div|span|p|a|button|h1|h2|h3|ul|ol|li|table|form|style|script|img|section|header|footer|main|nav)\b""", RegexOption.IGNORE_CASE)

/** 判断整段文本是否为「完整独立的 HTML 文档」（无围栏时也应走预览代码块）。 */
// v404 诊断：ChatScreen 内诊断上下文与去重键（供 parseBlocks 记录 AI 回复 HTML 判定）
private var quroDiagCtx: android.content.Context? = null
private var lastDiagHtmlKey: String? = null

private fun isFullHtmlDocument(text: String): Boolean {
    val t = text.trim()
    if (!t.startsWith("<!DOCTYPE html", ignoreCase = true) && !t.startsWith("<html", ignoreCase = true)) return false
    return t.contains("</html>", ignoreCase = true) || RE_HTML_HEAD_BODY.containsMatchIn(t)
}

/** 仅匹配「开围栏」（` ```lang ` 行首），用于流式生成中围栏尚未闭合的情况。 */
private val RE_FENCE_OPEN = Regex("""(?m)^```([a-zA-Z0-9+#-]*)[ \t]*\n""")

/** 解析 ```lang ... ``` 围栏代码块；其余文本走 HTML/Markdown 块级解析。 */
private fun parseBlocks(text: String): List<MsgBlock> {
    // v404 诊断：记录 AI 回复是否含 HTML 及其判定结果（排查「HTML 裸文本」问题）
    val trimmed = text.trim()
    if (quroDiagCtx != null && (trimmed.startsWith("<") || trimmed.contains("```html") || isFullHtmlDocument(text) || looksLikeHtmlStrict(trimmed))) {
        val key = trimmed.take(200)
        if (key != lastDiagHtmlKey) {
            lastDiagHtmlKey = key
            val detected = isFullHtmlDocument(text) || looksLikeHtmlStrict(trimmed)
            QuroCrashLogger.logEvent(
                quroDiagCtx!!, "HTML",
                "startsWith< =${trimmed.startsWith("<")} fenceHtml=${trimmed.contains("```html")} fullDoc=${isFullHtmlDocument(text)} segHtml=${looksLikeHtmlStrict(trimmed)} detected=$detected | head=${trimmed.take(180).replace("\n", "\\n")}",
            )
        }
    }
    // 整段完整 HTML 文档（AI 未加围栏直接贴源码）也路由到预览代码块，避免裸 HTML 当纯文本
    if (isFullHtmlDocument(text)) return listOf(MsgBlock.Code("html", text))
    val blocks = mutableListOf<MsgBlock>()
    var last = 0
    val fences = RE_FENCE.findAll(text).toList()
    for (m in fences) {
        if (m.range.first > last) blocks.addAll(parseTail(text.substring(last, m.range.first)))
        blocks.add(MsgBlock.Code(m.groupValues[1].trim(), m.groupValues[2].removeSuffix("\n")))
        last = m.range.last + 1
    }
    if (last < text.length) blocks.addAll(parseTail(text.substring(last)))
    return if (blocks.isEmpty()) listOf(MsgBlock.Text(text)) else blocks
}

/**
 * 处理围栏之间的尾段：若含「未闭合的开围栏」（流式生成中常见），
 * 把开围栏之后的内容直接当代码块渲染（带边框），实现「边写边出框」；
 * 否则走原 HTML 片段嗅探 / Markdown 解析。
 */
private fun parseTail(seg: String): List<MsgBlock> {
    val open = RE_FENCE_OPEN.find(seg)
    if (open != null) {
        val before = seg.substring(0, open.range.first)
        val after = seg.substring(open.range.last + 1)
        val lang = open.groupValues[1].trim()
        // 语言非空，或虽为空但有后续内容 → 视为开围栏（流式未闭合，而非孤立的闭合围栏）
        if (lang.isNotBlank() || after.trim().isNotEmpty()) {
            val out = mutableListOf<MsgBlock>()
            if (before.isNotBlank()) out.addAll(parseSegments(before))
            out.add(MsgBlock.Code(lang.ifBlank { "text" }, after))
            return out
        }
    }
    return parseSegments(seg)
}

/**
 * 把一段非围栏文本拆成块：若整段明显是「独立 HTML 片段」（非行内/散文），
 * 整体作为 html 预览代码块（点「预览」即可 WebView 渲染）；否则走原 Markdown/HTML 块级解析。
 * 判定需以 < 开头 + 含块级标签且标签总数较多，避免把散文里的 <b> 也误判成代码块。
 */
private fun parseSegments(seg: String): List<MsgBlock> {
    val t = seg.trim()
    if (t.isNotEmpty() && t.startsWith("<") && looksLikeHtmlStrict(t)) {
        return listOf(MsgBlock.Code("html", seg.trim()))
    }
    return parseRichBlocks(seg)
}

/** 判定整段是否为「独立 HTML 片段」而非行内/散文：需以 < 开头，且含块级标签且标签总数较多。 */
private fun looksLikeHtmlStrict(s: String): Boolean {
    val t = s.trim()
    if (t.startsWith("<!DOCTYPE html", ignoreCase = true)) return true
    if (t.startsWith("<html", ignoreCase = true)) return true
    if (RE_HTML_HEAD_BODY.containsMatchIn(t)) return true
    val blockTags = Regex("(?i)<(div|section|article|table|form|ul|ol|header|footer|main|nav|body|html|style|script|iframe|head)\\b").findAll(t).count()
    val totalTags = RE_HTML_TAG.findAll(t).count()
    return blockTags >= 1 && totalTags >= 4
}

/** 识别块级 HTML（h1-6 / blockquote / hr / table / ul-ol）与 Markdown 块（# 标题、> 引用），其余按段落切分。 */
private fun parseRichBlocks(seg: String): List<MsgBlock> {
    val out = mutableListOf<MsgBlock>()
    var pos = 0
    for (m in RE_BLOCK.findAll(seg)) {
        if (m.range.first > pos) out.addAll(parseParagraphs(seg.substring(pos, m.range.first)))
        when {
            m.groupValues[1].isNotBlank() ->
                out.add(MsgBlock.Heading(m.groupValues[1].toInt(), m.groupValues[2].trim()))
            m.groupValues[3].isNotBlank() ->
                out.add(MsgBlock.Quote(m.groupValues[3].trim()))
            m.groupValues[4].matches(RE_HR) ->
                out.add(MsgBlock.Rule())
            m.groupValues[5].isNotBlank() ->
                out.add(parseTable(m.groupValues[5]))
            m.groupValues[6].isNotBlank() -> {
                val items = RE_LI.findAll(m.groupValues[7])
                    .map { it.groupValues[1].trim() }.toList()
                if (items.isNotEmpty()) out.add(MsgBlock.Text(items.joinToString("\n") { "• $it" }))
            }
        }
        pos = m.range.last + 1
    }
    if (pos < seg.length) out.addAll(parseParagraphs(seg.substring(pos)))
    return out.ifEmpty { listOf(MsgBlock.Text(seg)) }
}

/** 普通段落 / Markdown 块切分：识别 # 标题、> 引用、无序列表；其余按空行分段，保留行内 HTML。 */
private fun parseParagraphs(s: String): List<MsgBlock> {
    val lines = s.replace(RE_P, "\n").replace(RE_BR, "\n").split("\n")
    val out = mutableListOf<MsgBlock>()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        when {
            line.matches(RE_HEADING_LINE) -> {
                val level = line.takeWhile { it == '#' }.length
                out.add(MsgBlock.Heading(level, line.replaceFirst(RE_HEADING_STRIP, "").trim()))
                i++
            }
            line.matches(RE_QUOTE_LINE) -> {
                val sb = StringBuilder()
                while (i < lines.size && lines[i].matches(RE_QUOTE_LINE)) {
                    sb.appendLine(lines[i].replaceFirst(RE_QUOTE_STRIP, "")); i++
                }
                out.add(MsgBlock.Quote(sb.toString().trimEnd()))
            }
            line.matches(RE_LIST_LINE) -> {
                val items = mutableListOf<String>()
                while (i < lines.size && lines[i].matches(RE_LIST_LINE)) {
                    items.add(lines[i].replaceFirst(RE_LIST_STRIP, "")); i++
                }
                out.add(MsgBlock.Text(items.joinToString("\n") { "• $it" }))
            }
            line.isBlank() -> i++
            else -> {
                val sb = StringBuilder()
                while (i < lines.size && lines[i].isNotBlank()
                    && !lines[i].matches(RE_HEADING_LINE)
                    && !lines[i].matches(RE_QUOTE_LINE)
                    && !lines[i].matches(RE_LIST_LINE)
                ) {
                    sb.appendLine(lines[i]); i++
                }
                val para = sb.toString().trimEnd()
                if (para.isNotBlank()) out.add(MsgBlock.Text(para))
            }
        }
    }
    return out.ifEmpty { listOf(MsgBlock.Text(s)) }
}

/** 解析 <table>：每行 <tr>，单元格 <td>/<th>；首行作为表头。 */
private fun parseTable(html: String): MsgBlock.Table {
    val rows = RE_TR.findAll(html)
        .map { tr ->
            RE_TD.findAll(tr.groupValues[1])
                .map { it.groupValues[1].trim() }.toList()
        }.toList()
    val header = rows.firstOrNull() ?: emptyList()
    val body = if (rows.size > 1) rows.subList(1, rows.size) else emptyList()
    return MsgBlock.Table(header, body)
}

/** 渲染 <table>：首行为表头（primary 色），单元格内联 HTML 由 buildRich 渲染；横向滚动防溢出。 */
@Composable
private fun RenderTable(
    header: List<String>,
    rows: List<List<String>>,
    scaled: (Int) -> androidx.compose.ui.unit.TextUnit,
    textColor: Color,
    onOpenLink: (String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val all = if (header.isNotEmpty()) listOf(header) + rows else rows
    Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        all.forEachIndexed { ri, row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { cell ->
                    val rich = buildRich(
                        cell,
                        TextStyle(fontSize = scaled(13), color = if (ri == 0) cs.primary else textColor, lineHeight = scaled(18)),
                        boldColor = cs.primary, linkColor = cs.primary,
                        codeBackground = cs.surfaceVariant.copy(alpha = 0.5f),
                    )
                    ClickableText(
                        text = rich,
                        style = TextStyle(fontSize = scaled(13), color = if (ri == 0) cs.primary else textColor, lineHeight = scaled(18)),
                        onClick = { offset -> rich.getStringAnnotations("link", offset, offset).firstOrNull()?.item?.let { onOpenLink(it) } },
                        modifier = Modifier.widthIn(min = 80.dp, max = 220.dp).padding(6.dp),
                    )
                }
            }
            HorizontalDivider(color = Line)
        }
    }
}

/** 嗅探代码内容是否像 HTML（兼容 AI 把语言标签写成空 / xml / markup / htm 等情况）。 */
private fun looksLikeHtml(code: String): Boolean {
    val t = code.trim()
    if (t.startsWith("<!DOCTYPE html", ignoreCase = true)) return true
    if (t.startsWith("<html", ignoreCase = true)) return true
    if (RE_HTML_HEAD_BODY.containsMatchIn(t)) return true
    // 含 2 个以上常见 HTML 标签即判定为 HTML
    val tagCount = RE_HTML_TAG.findAll(t).count()
    return tagCount >= 2
}

/** HTML 转义（用于在 WebView 中安全展示非 HTML 代码）。 */
private fun escapeHtml(text: String): String {
    return text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")
}

/** 对话内代码块：可复制、可直接运行（IDE 能力）；HTML 代码块额外支持「代码 / 预览」双 Tab 渲染。 */
@Composable
private fun CodeBlock(lang: String, code: String, scaled: (Int) -> androidx.compose.ui.unit.TextUnit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var output by remember { mutableStateOf<String?>(null) }
    val isHtml = lang.equals("html", ignoreCase = true) ||
        lang.equals("htm", ignoreCase = true) ||
        lang.equals("markup", ignoreCase = true) ||
        looksLikeHtml(code)
    var showPreview by remember { mutableStateOf(isHtml) }
    var showFullscreen by remember { mutableStateOf(false) }
    // v405：WebView 在 AndroidView + WRAP_CONTENT 下高度不随内容展开，预览常空白。
    // 用状态高度：初始固定值，onPageFinished 读实际内容高度动态扩容（上限 640dp）。
    var webHeight by remember { mutableStateOf(220.dp) }

    Surface(
        color = cs.surfaceVariant,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth().border(1.dp, cs.outlineVariant, RoundedCornerShape(10.dp)),
    ) {
        Column(Modifier.fillMaxWidth()) {
            // 头部：语言标签 + 操作按钮（HTML 时多一个 代码/预览 切换）
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Text(lang.ifBlank { "code" }, fontSize = 11.sp, color = cs.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
                // [代码|预览] 切换 + 复制 + 运行（始终显示）
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Row(
                        Modifier
                            .background(cs.surface, RoundedCornerShape(6.dp))
                            .padding(2.dp)
                    ) {
                        val tabs = listOf("代码" to false, "预览" to true)
                        tabs.forEach { (label, isPrev) ->
                            val selected = (showPreview == isPrev)
                            Surface(
                                color = if (selected) cs.primary else Color.Transparent,
                                shape = RoundedCornerShape(4.dp),
                                onClick = { showPreview = isPrev },
                            ) {
                                Text(label, fontSize = 10.sp, color = if (selected) cs.onPrimary else cs.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                            }
                        }
                    }
                    Spacer(Modifier.width(4.dp))
                IconButton(onClick = { showFullscreen = true }, Modifier.size(28.dp)) {
                    LucideIcon("maximize", "全屏预览", Modifier.size(16.dp), tint = Muted)
                }
                Spacer(Modifier.width(2.dp))
                IconButton(onClick = { copyPlain(ctx, code) }, Modifier.size(28.dp)) {
                    LucideIcon("corner_down_left", "复制代码", Modifier.size(14.dp), tint = Muted)
                }
                IconButton(onClick = {
                    scope.launch(Dispatchers.IO) {
                        val r = RunCodeTool().run(
                            ctx,
                            JSONObject().put("code", code).put("lang", lang.ifBlank { "python" }).toString(),
                        )
                        withContext(Dispatchers.Main) { output = r }
                    }
                }, Modifier.size(28.dp)) {
                    Icon(Icons.Filled.PlayArrow, "运行", Modifier.size(16.dp), tint = cs.primary)
                }
                }  // end of inner action row
            }

            // 内容区：源码 or 预览（HTML 默认直接渲染，无需手动点「预览」）
            if (showPreview) {
                // 预览模式：WebView 渲染（高度由 webHeight 状态驱动，避免空白）
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(webHeight)
                        .clip(RoundedCornerShape(bottomStart = 8.dp, bottomEnd = 8.dp))
                        .background(Color.White)
                ) {
                    AndroidView(factory = { context ->
                            WebView(context).apply {
                            // 安全加固：预览内容来自 AI 生成（不可信），默认禁用 JS 执行，防止脚本注入。
                            // 纯代码预览无需 JS；若未来需要渲染含 JS 的可信 HTML 工件，应单独加来源校验后再开。
                            settings.javaScriptEnabled = false
                            settings.domStorageEnabled = true
                            settings.cacheMode = WebSettings.LOAD_NO_CACHE
                            settings.useWideViewPort = false
                            settings.loadWithOverviewMode = true
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                            webViewClient = object : WebViewClient() {
                                override fun onPageFinished(view: WebView?, url: String?) {
                                    super.onPageFinished(view, url)
                                    val density = view?.context?.resources?.displayMetrics?.density ?: return
                                    view.evaluateJavascript("document.documentElement.scrollHeight") { value ->
                                        val px = value?.replace("\"", "")?.toIntOrNull() ?: return@evaluateJavascript
                                        val dp = (px / density).toInt().coerceIn(120, 640)
                                        webHeight = dp.dp
                                    }
                                }
                            }
                            // 初始加载（流式时后续由 update 跟随重载，避免预览冻结在首帧）
                            val html = buildPreviewHtml(code, isHtml)
                            tag = html
                            loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
                        }
                    }, modifier = Modifier.fillMaxSize(), update = { wv ->
                        // 流式生成：code 变化时重新加载，预览随写随更新
                        val html = buildPreviewHtml(code, isHtml)
                        if (wv.tag != html) {
                            wv.tag = html
                            wv.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
                        }
                    })
                }
            } else {
                // 默认：源码文本展示（双向滚动 + 裁剪到圆角框内，避免长代码/HTML 撑出卡片）
                // v396 修复：长单行代码/HTML 不再横向溢出——改为水平滚动 + 裁剪，
                // softWrap=false 让长行保持单行、由 horizontalScroll 承载。
                SelectionContainer(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .heightIn(max = 300.dp)
                        .verticalScroll(rememberScrollState())
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 10.dp)
                        .padding(bottom = 8.dp)
                ) {
                    Text(
                        code,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        color = cs.onSurface,
                        softWrap = false
                    )
                }
            }

            output?.let {
                HorizontalDivider(color = Line)
                Text("运行结果", fontSize = 11.sp, color = Muted, modifier = Modifier.padding(start = 10.dp, top = 6.dp))
                SelectionContainer(Modifier.fillMaxWidth().padding(10.dp)) {
                    Text(it, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = cs.onSurface)
                }
            }
        }
    }

    // 全屏预览覆盖层
    if (showFullscreen) {
        FullscreenPreview(code = code, isHtml = isHtml, onDismiss = { showFullscreen = false })
    }
}

// 全屏预览覆盖层（CodeBlock 内点击 🔲 全屏按钮触发）—— 用 Dialog 真正覆盖全屏
@Composable
private fun FullscreenPreview(code: String, isHtml: Boolean, onDismiss: () -> Unit) {
    // #877 修复全屏白屏：Compose Dialog 是 overlay window，内部 WebView 在多 ROM 上无法正确
    // 合成 → 白屏。改用挂在真实 Activity window 上的传统 Dialog，WebView 用 Activity context
    // 创建，能稳定渲染。若取不到 Activity（极端），兜底仍用 Compose Dialog。
    val ctx = LocalContext.current
    val activity = ctx.findActivity()
    val finalHtml = if (isHtml) {
        val t = code.trim()
        val isFullDoc = t.startsWith("<!DOCTYPE html", ignoreCase = true) || t.startsWith("<html", ignoreCase = true)
        if (isFullDoc) code else buildString {
            append("<!DOCTYPE html><html><head><meta charset=\"UTF-8\">")
            append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
            append("<style>body{margin:0;padding:16px;font-family:sans-serif;word-wrap:break-word;}img{max-width:100%;height:auto;}</style>")
            append("</head><body>").append(code).append("</body></html>")
        }
    } else {
        buildString {
            append("<!DOCTYPE html><html><head><meta charset=\"UTF-8\">")
            append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
            append("<style>body{margin:0;padding:16px;background:#1e1e1e;}")
            append("pre{white-space:pre-wrap;word-break:break-word;font-family:monospace;font-size:14px;color:#d4d4d4;}</style></head><body><pre>")
            append(escapeHtml(code))
            append("</pre></body></html>")
        }
    }

    if (activity == null) {
        // 兜底路径（理论上 ChatScreen 必在 Activity 内，不会走到）
        Dialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Box(Modifier.fillMaxSize().background(Color.White)) {
                AndroidView(factory = {
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.cacheMode = WebSettings.LOAD_NO_CACHE
                        settings.loadWithOverviewMode = true
                        settings.useWideViewPort = true
                        loadDataWithBaseURL(null, finalHtml, "text/html", "UTF-8", null)
                    }
                }, modifier = Modifier.fillMaxSize())
                IconButton(onClick = onDismiss, Modifier.align(Alignment.TopEnd).padding(12.dp).size(36.dp)) {
                    LucideIcon("x", "关闭全屏", Modifier.size(22.dp), tint = Color.White)
                }
            }
        }
        return
    }

    DisposableEffect(Unit) {
        val host = activity
        val dialog = android.app.Dialog(host, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.window?.apply {
            // 全屏三件套：全屏标志 + 允许延伸到系统栏区域 + 沉浸式隐藏导航栏
            setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            )
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            // 沉浸式：隐藏状态栏和导航栏（兼容 API 30+）
            @Suppress("DEPRECATION")
            decorView.systemUiVisibility = (
                android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                    or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
        }
        val webView = WebView(host).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            loadDataWithBaseURL(null, finalHtml, "text/html", "UTF-8", null)
        }
        // 右上角半透明关闭按钮（传统 View，避免再套一层 Compose）
        val closeBtn = TextView(host).apply {
            text = "✕"
            textSize = 22f
            setTextColor(android.graphics.Color.WHITE)
            background = GradientDrawable().apply { setColor(0x73000000.toInt()); cornerRadius = 22f }
            setPadding(22, 10, 22, 10)
            setOnClickListener { dialog.dismiss() }
        }
        val frame = FrameLayout(host)
        frame.addView(webView, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val closeLp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            topMargin = 56; rightMargin = 28
        }
        frame.addView(closeBtn, closeLp)
        dialog.setContentView(frame)
        dialog.setOnDismissListener { onDismiss() }
        dialog.show()
        onDispose { dialog.dismiss() }
    }
}

// 从 Context 中安全取出宿主 Activity（兼容 ContextWrapper 多层包装）。
// 与 QuroBrowserScreen.findActivity 同逻辑，但本文件需独立定义（原实现为 private 不可跨文件见）。
private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c != null) {
        if (c is Activity) return c
        c = if (c is ContextWrapper) c.baseContext else null
    }
    return null
}

private fun copyPlain(ctx: Context, text: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    cm.setPrimaryClip(android.content.ClipData.newPlainText("Zorv", text))
    Toast.makeText(ctx, "已复制", Toast.LENGTH_SHORT).show()
}

/** 构造 WebView 预览用 HTML：完整文档直接渲染，HTML 片段包裹为完整文档，非 HTML 代码用 <pre> 转义防 XSS。 */
private fun buildPreviewHtml(code: String, isHtml: Boolean): String {
    val t = code.trim()
    val isFullDoc = t.startsWith("<!DOCTYPE html", ignoreCase = true) || t.startsWith("<html", ignoreCase = true)
    return if (isFullDoc || isHtml) {
        if (isFullDoc) code else buildString {
            append("<!DOCTYPE html><html><head><meta charset=\"UTF-8\">")
            append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
            append("<style>body{margin:8px;padding:0;font-family:sans-serif;word-wrap:break-word;}img{max-width:100%;height:auto;}</style></head><body>")
            append(code)
            append("</body></html>")
        }
    } else {
        buildString {
            append("<!DOCTYPE html><html><head><meta charset=\"UTF-8\">")
            append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
            append("<style>body{margin:8px;padding:0;background:#f5f5f5;}pre{white-space:pre-wrap;word-break:break-word;font-family:monospace;font-size:14px;color:#222;padding:12px;margin:0;}</style></head><body><pre>")
            append(escapeHtml(code))
            append("</pre></body></html>")
        }
    }
}

/** 复制整段对话全文（全部复制）。 */
private fun copyConversation(ctx: Context, messages: List<Message>) {
    val sb = StringBuilder()
    messages.forEach { m ->
        val who = if (m.mine) "我" else m.author
        sb.append("$who：${m.text ?: ""}\n")
    }
    copyPlain(ctx, sb.toString().trim())
}

/** [D2] 导出当前对话为 Markdown 文件，保存到应用外部私有目录 QuroAI_exports/，返回保存路径；失败返回 null。不发起任何网络请求。 */
private fun exportConversation(ctx: Context, messages: List<Message>): String? {
    return runCatching {
        val dir = File(ctx.getExternalFilesDir(null), "QuroAI_exports").apply { if (!exists()) mkdirs() }
        val stamp = java.time.format.DateTimeFormatter
            .ofPattern("yyyyMMdd_HHmmss")
            .format(java.time.LocalDateTime.now())
        val file = File(dir, "QuroAI_对话_$stamp.md")
        val sb = StringBuilder()
        sb.append("# QuroAI 对话导出\n\n")
        sb.append("_导出时间：${java.time.LocalDateTime.now()}_\n\n")
        messages.forEach { m ->
            val who = if (m.mine) "我" else m.author
            val body = (m.text ?: "").trim()
            if (body.isNotBlank()) {
                sb.append("**$who**：\n\n$body\n\n---\n\n")
            }
        }
        file.writeText(sb.toString())
        file.absolutePath
    }.getOrElse { e ->
        Log.e("ChatScreen", "导出对话失败", e)
        null
    }
}

// ---------------- Zorv AI 后端 → MoWen UI 适配器 ----------------

/** 无激活人格时的兜底人格。 */
private fun fallbackPersona() = QuroPersona(name = "Zorv", description = "智能助手", avatarEmoji = "🤖")

/** QuroMessage → MoWen Message（mine 由 role 决定）。 */
/** 判断工具结果是否为已知的垃圾值（旧版 bug 残留 / 异常调用）。渲染期与持久化迁移共用。 */
private fun isGarbageToolResult(c: String): Boolean {
    if (c.isBlank() || c == "?" || c == "OK") return true
    // 33.333… 是 100/3 的浮点残留，旧版曾当工具结果落盘
    if (c.startsWith("33.")) return true
    return false
}

private fun QuroMessage.toMessage(
    assistantName: String,
    assistantAvatar: String,
    assistantAvatarUri: String = "",
    userAvatarUri: String = "",
    userName: String = "",
): Message {
    val mine = role == "user"
    val think = reasoning?.takeIf { it.isNotBlank() }?.let {
        ThinkBlock(
            it.split("\n")
                .map { l -> l.trim().removePrefix("•").removePrefix("-").trim() }
                .filter { l -> l.isNotBlank() }
        )
    }
    val attachment = attachments?.firstOrNull()?.let {
        Attachment(it.name, formatSize(it.size), path = it.uri, type = it.type)
    }
    return Message(
        id = id.hashCode(),
        uids = listOf(id),
        mine = mine,
        // A2 修复：用户消息气泡显示发送者昵称——优先用消息自带 senderName，回退到当前资料昵称，最终回退"我"；
        // 不再死写"你"。头像同理优先用消息自带 avatarUrl。
        author = if (mine) (senderName ?: userName).ifBlank { "我" } else assistantName,
        avatar = if (mine) (senderName ?: userName).ifBlank { "我" } else assistantAvatar,
        avatarUri = if (mine) (avatarUrl ?: userAvatarUri) else assistantAvatarUri,
        time = formatChatTime(createdAt),
        text = content.ifBlank { null },
        attachment = attachment,
        think = think,
        cards = cards,
    )
}

/** QuroPersona → MoWen Persona（含 id 以便回写激活状态）。 */
private fun QuroPersona.toPersona(): Persona {
    // 头像：emoji 类型用 emoji；图片/无图退化为名称首字母（与 QuroSoulUi.AvatarContent 一致）
    val safeName = name.ifBlank { "Zorv" }
    val name1 = (if (safeName == "?") "Q" else safeName).first().toString()  // 永远非空（fallback "Q"，绝不返回 "?"）
    val ava = when {
        avatarType == "emoji" && avatarEmoji.isNotBlank() -> avatarEmoji
        else -> name1  // 优先首字母，绝不返回空或 "?"
    }
    return Persona(
        id = id,
        name = name.ifBlank { "Zorv" },
        role = description.ifBlank { "智能助手" },
        desc = description.ifBlank { "你的 AI 助手，随时待命。" },
        ava = ava,
        color = "#211E1A",
        avatarUri = if (avatarType == "image") avatarUri else "",
        tags = tags,  // 标签仅存全局标签名（String）
    )
}

/** QuroConversationMeta → MoWen HistoryItem。 */
private fun QuroConversationMeta.toHistoryItem(active: Boolean): HistoryItem {
    return HistoryItem(
        id = id,
        title = title.ifBlank { "新对话" },
        sub = preview.ifBlank { "空对话" },
        time = formatChatTime(updatedAt),
        group = formatGroup(updatedAt),
        active = active,
    )
}

private fun formatChatTime(ts: Long): String {
    val now = Calendar.getInstance()
    val c = Calendar.getInstance().also { it.timeInMillis = ts }
    val sameDay = c.get(Calendar.YEAR) == now.get(Calendar.YEAR) &&
        c.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)
    val f = if (sameDay) SimpleDateFormat("HH:mm", Locale.getDefault())
    else SimpleDateFormat("MM/dd", Locale.getDefault())
    return f.format(Date(ts))
}

private fun formatGroup(ts: Long): String {
    val diffDays = ((System.currentTimeMillis() - ts) / 86_400_000L).toInt()
    return when {
        diffDays < 1 -> "今天"
        diffDays < 7 -> "本周"
        else -> "更早"
    }
}

private fun formatSize(bytes: Long): String {
    return when {
        bytes <= 0 -> "附件"
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    }
}

// ---------------- 附件下载 ----------------

/**
 * 将聊天附件保存到用户可见的 Download/Quro 目录（优先 MediaStore，失败回退到应用私有目录）。
 */
private fun downloadAttachment(ctx: Context, att: Attachment) {
    val src = att.path?.let { File(it) }?.takeIf { it.exists() } ?: run {
        Toast.makeText(ctx, "源文件不存在", Toast.LENGTH_SHORT).show()
        return
    }
    val collectedName = att.name.ifBlank { "quro_${System.currentTimeMillis()}" }
    val mime = when (att.type) {
        "image" -> "image/*"
        "video" -> "video/*"
        else -> "*/*"
    }
    try {
        val uri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val collection = when (att.type) {
                "image" -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                "video" -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                else -> MediaStore.Downloads.EXTERNAL_CONTENT_URI
            }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, collectedName)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Quro")
            }
            ctx.contentResolver.insert(collection, values)
        } else {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val out = File(dir, collectedName)
            Uri.fromFile(out)
        }
        if (uri == null) { fallbackCopy(ctx, src, collectedName); return }
        ctx.contentResolver.openOutputStream(uri)?.use { os ->
            src.inputStream().use { it.copyTo(os) }
        } ?: run { fallbackCopy(ctx, src, collectedName); return }
        Toast.makeText(ctx, "已下载：$collectedName", Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        fallbackCopy(ctx, src, collectedName)
    }
}

private fun fallbackCopy(ctx: Context, src: File, name: String) {
    runCatching {
        val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: ctx.filesDir
        val out = File(dir, name)
        src.inputStream().use { ins -> out.outputStream().use { os -> ins.copyTo(os) } }
        Toast.makeText(ctx, "已保存到应用目录：${out.absolutePath}", Toast.LENGTH_LONG).show()
    }.onFailure {
        Toast.makeText(ctx, "下载失败：${it.message}", Toast.LENGTH_SHORT).show()
    }
}

// PermissionModeBar / PolicyChipGroup 已抽到 ui/chat/ChatPermissionModeBar.kt（ChatPermissionModeBar），
// 修复「权限模式全选后收起/返回按钮被测量成 0 宽而消失」的布局塌陷根因。
