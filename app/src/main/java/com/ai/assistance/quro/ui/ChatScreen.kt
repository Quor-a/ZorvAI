package com.ai.assistance.quro.ui

import android.util.Log
import android.net.Uri
import android.media.RingtoneManager
import android.content.Intent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.collectAsState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import com.ai.assistance.quro.core.tools.QuroTool
import com.ai.assistance.quro.core.tools.ImportedToolDef
import com.ai.assistance.quro.core.tools.QuroImportedToolRegistry
import com.ai.assistance.quro.core.tools.QuroUiActionBridge
import com.ai.assistance.quro.core.tools.UiNavigationBus
import com.ai.assistance.quro.core.tools.ui.UiNavigationEvent
import com.ai.assistance.quro.core.tools.VisualCustomPopupQueue
import com.ai.assistance.quro.core.tools.VisualPopupQueue
import com.ai.assistance.quro.core.tools.PopupButton
import com.ai.assistance.quro.core.tools.PopupInput
import com.ai.assistance.quro.core.tools.PopupResult
import com.ai.assistance.quro.BuildConfig
import com.ai.assistance.quro.core.linux.QuroLinuxEnv
import com.ai.assistance.quro.core.terminal.QuroTerminalPrefs
import com.ai.assistance.quro.core.termux.QuroTermuxTerminalController
import com.ai.assistance.quro.ui.QuroChatCardTray
import com.ai.assistance.quro.ui.QuroChatCardView
import com.ai.assistance.quro.ui.VisualDialogs
import com.ai.assistance.quro.ui.VisualPopupDialog
import com.ai.assistance.quro.ui.VisualCustomPopupDialog
import com.ai.assistance.quro.core.cards.QuroChatCard
import com.ai.assistance.quro.core.cards.parseComponentSpec
import com.ai.assistance.quro.ui.QuroShareBridge
import com.ai.assistance.quro.service.QuroMediaService
import com.ai.assistance.quro.service.QuroMiniWindowManager
import com.ai.assistance.quro.service.QuroMiniWindowManager.MiniChatLine
import com.ai.assistance.quro.core.tools.QuroMediaController
import com.ai.assistance.quro.core.QuroBrowserBridge
import com.ai.assistance.quro.util.QuroDiag
import com.ai.assistance.quro.workflow.data.WorkflowRepository
import com.ai.assistance.quro.workflow.data.model.Workflow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
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
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.DragInteraction
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
import androidx.compose.foundation.layout.wrapContentWidth
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
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.view.ViewGroup
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.CircularProgressIndicator
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
import kotlinx.coroutines.flow.filterNotNull
import org.json.JSONObject
import com.ai.assistance.quro.core.tools.RunCodeTool
import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.core.content.FileProvider
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.FolderOpen
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
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.TouchApp
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
import com.ai.assistance.quro.ui.VisualPopupConfigDialog
import com.ai.assistance.quro.ui.VisualQuestionConfigDialog
// 动态 UI：AI 输出的 quro-ui DSL 直接渲染为原生可交互控件
import com.ai.assistance.quro.core.ui.dynamicui.QuroUiDslParser
import com.ai.assistance.quro.core.ui.dynamicui.QuroUiParseResult
import com.ai.assistance.quro.core.ui.dynamicui.QuroUiRenderer
import com.ai.assistance.quro.core.ui.dynamicui.QuroUiAction
import com.ai.assistance.quro.core.ui.dynamicui.QuroCallbackAction
import com.ai.assistance.quro.core.ui.dynamicui.QuroToolCallAction
import com.ai.assistance.quro.core.ui.dynamicui.QuroSkillAction
import com.ai.assistance.quro.core.ui.dynamicui.QuroOpenUrlAction
import com.ai.assistance.quro.core.ui.dynamicui.QuroCopyAction
import com.ai.assistance.quro.core.ui.dynamicui.QuroOpenAppAction
import com.ai.assistance.quro.core.ui.dynamicui.QuroToggleAction

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
        val aggAttach = mutableListOf<Attachment>()

        fun flushAgg() {
            if (!hasAgg) return
            val think = if (aggThinkLines.isNotEmpty()) ThinkBlock(aggThinkLines.toList()) else null
            val text = aggText.toString().takeIf { it.isNotBlank() }
            if (think != null || aggTools.isNotEmpty() || text != null || aggCards.isNotEmpty() || aggAttach.isNotEmpty()) {
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
                        attachments = aggAttach.toList(),
                        think = think,
                        tools = if (aggTools.isEmpty()) null else aggTools.toList(),
                        cards = aggCards.toList(),
                    )
                )
            }
            hasAgg = false
            aggThinkLines.clear(); aggTools.clear(); aggText.clear(); aggCards.clear(); aggAttach.clear(); aggIds.clear()
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
                    // 隐藏的 system 指令（如防死循环的「[系统提示]」）只给模型看，绝不渲染进对话气泡
                    if (m.role == "system" && m.hidden) { flushAgg(); continue }
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
                        // v1057：run_code 的 html 产物 → 对话框内联实时网页预览卡片
                        //（AI 写网页 → AI 运行 → 网页直接长在气泡里，把手机 AI IDE 的产出物真正融入对话内容区）
                        if (c.name == "run_code" && r != null) {
                            when {
                                // HTML/SVG 内容 → 网页预览卡片
                                looksLikeHtml(r) || r.contains("<svg") -> {
                                    aggCards.add(
                                        QuroChatCard.HtmlPreviewCard(
                                            id = "rh_" + (c.id ?: r.hashCode().toString()),
                                            title = "网页预览（AI 运行产物）",
                                            html = r,
                                        )
                                    )
                                }
                                // 图片路径 → 媒体卡片
                                r.startsWith("🖼️ IMAGE_PATH:") -> {
                                    val imagePath = r.removePrefix("🖼️ IMAGE_PATH:")
                                    aggCards.add(
                                        QuroChatCard.MediaCard(
                                            id = "ri_" + (c.id ?: r.hashCode().toString()),
                                            title = "图片预览（AI 运行产物）",
                                            mediaUrl = imagePath,
                                            mediaType = "image",
                                        )
                                    )
                                }
                            }
                        }
                    }
                    if (m.content.isNotBlank()) {
                        if (aggText.isNotEmpty()) aggText.append("\n\n")
                        aggText.append(m.content)
                    }
                    if (m.cards.isNotEmpty()) aggCards.addAll(m.cards)
                    if (!m.attachments.isNullOrEmpty()) {
                        aggAttach.addAll(m.attachments.map { Attachment(it.name, formatSize(it.size), path = it.uri, type = it.type) })
                    }
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
            // 🔧 对话框进度反馈（v216 修复 → v230 优化 → v453 升级「等等」动态小组件）：
            //   仅当聚合列表中尚无助手消息时才追加占位气泡，避免与已聚合的真实消息重复显示。
            //   聚合逻辑已经把 hidden assistant 消息的 thinking/toolCalls 合并进了同一个气泡，
            //   所以大部分情况下不需要额外占位；占位仅覆盖「纯等待首条响应」的空窗期。
            //   v453：占位不再用默认收起的「思考中」文字气泡（等于看不见），改为内容区独立的
            //   「等等」动态小组件（跳动圆点动画）；头像与人格名字保持不变（复用 persona），
            //   小组件独立于头像/名字，不替代、不隐藏它们。
            // 🔧 Bug修复「等待气泡缺失」：原判定 `out.any { !it.mine }` 会把会话首条的
            //   【欢迎语 assistant 消息】也算进去 → 任何会话恒为 true → 「等等」小组件永远不显示。
            //   正确语义：仅统计【最后一条用户消息之后】是否已有助手回复（含流式占位）。
            //   用户刚发出消息、AI 还没产出任何内容时，等待气泡才出现；首个 token/思考到达后消失。
            val lastUserIdx = out.indexOfLast { it.mine }
            val afterUser = out.drop(if (lastUserIdx >= 0) lastUserIdx + 1 else 0)
            val hasAssistantMsg = afterUser.any { !it.mine && it.id != -1 }
            // 🔧 执行态展示：存在「结果尚未回填」的工具调用（正卡在 engine.execute 慢任务上）时，
            // 追加一条「AI 正在执行工具…」工作指示气泡。多轮工具循环里第一轮之后必有助手消息，
            // 原「等等」组件因 hasAssistantMsg=true 永不出现 → 循环期间对话框无任何「工作中」反馈，
            // 看起来像死循环重复文本。此分支专门补上执行中展示。
            val hasPendingTool = afterUser.any { !it.mine && !it.tools.isNullOrEmpty() && it.tools.any { t -> t.result.isNullOrBlank() } }
            if (!hasAssistantMsg) {
                out.add(
                    Message(
                        id = -1, mine = false, author = selectedPersona.name,
                        avatar = selectedPersona.ava, avatarUri = selectedPersona.avatarUri,
                        time = "", text = null, isWaiting = true,
                    )
                )
            } else if (hasPendingTool) {
                out.add(
                    Message(
                        id = -2, mine = false, author = selectedPersona.name,
                        avatar = selectedPersona.ava, avatarUri = selectedPersona.avatarUri,
                        time = "", text = null, isWorking = true,
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
    var showCleanup by remember { mutableStateOf(false) }
    var showFileManager by remember { mutableStateOf(false) }
    // ACI 管理中心屏：从设置「功能 → ACI 管理中心」进入（此前仅有 AI 工具 ui_open_aci 可打开，无手动按钮）
    var showAci by remember { mutableStateOf(false) }
    // ACI 应用选择器对话框
    var showAciSelector by remember { mutableStateOf(false) }
    // 工作区选择器对话框
    var showWorkspaceSelector by remember { mutableStateOf(false) }
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
    var showLspose by remember { mutableStateOf(false) }
    // USB / 无线调试 (ADB) 页（从设置页入口进入）
    var showUsbDebug by remember { mutableStateOf(false) }
    // 默认应用角色管理页（从设置页入口进入）
    var showDefaultApp by remember { mutableStateOf(false) }
    // 包管理页（插件 / 工具包 / 技能 / MCP，从设置页入口进入）
    var showCms by remember { mutableStateOf(false) }
    // 工具箱（设置页入口：文件管理 / 包名查询 / 代码运行 / 内置浏览器）
    var showToolbox by remember { mutableStateOf(false) }
    // 插件运行时 Demo 入口
    var showPlugins by remember { mutableStateOf(false) }
    // 技能 SKILL 管理入口
    var showSkills by remember { mutableStateOf(false) }
    // 技能选择对话框（从输入框工具菜单/上下文标识栏进入）
    var showSkillSelector by remember { mutableStateOf(false) }
    // 可视化弹窗配置对话框
    var showVisualPopupConfig by remember { mutableStateOf(false) }
    val pendingVisualPopup = remember { mutableStateOf(false) }
    // 可视化询问配置对话框
    var showVisualQuestionConfig by remember { mutableStateOf(false) }
    val pendingVisualQuestion = remember { mutableStateOf(false) }
    // 定时任务管理入口
    var showSchedule by remember { mutableStateOf(false) }
    // 工作流管理入口
    var showWorkflow by remember { mutableStateOf(false) }
    // 知识库管理页（从设置页入口进入：浏览 / 查看 / 新建 / 删除 knowledge_base 文档）
    var showKnowledge by remember { mutableStateOf(false) }
    // 机器人设置页（C2）：从工具箱「机器人」入口进入
    var showBots by remember { mutableStateOf(false) }
    // 应用内文档查看器（本地渲染引擎，替代原 Collabora/外跳 ONLYOFFICE；已整合原「文档中心」）
    var showOnlyOffice by remember { mutableStateOf(false) }
    var showTerminal by remember { mutableStateOf(false) }
    var showBrowser by remember { mutableStateOf(false) }
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
    // 浏览器「化小窗」：非空时全屏浏览器收起、改由可拖拽悬浮小窗承载同一网址
    var browserFloatUrl by remember { mutableStateOf<String?>(null) }
    // 对话框「化小窗」：true 时主对话收起、改由可拖拽悬浮小窗承载
    var chatMinimized by remember { mutableStateOf(false) }

    // 应用内视频播放器（VideoView 渲染屏）
    var showVideoPlayer by remember { mutableStateOf(false) }
    var videoPlayerUri by remember { mutableStateOf("") }
    var videoPlayerTitle by remember { mutableStateOf("") }
    // 全屏图片查看器
    var showImageViewer by remember { mutableStateOf(false) }
    var imageViewerPath by remember { mutableStateOf("") }
    var imageViewerName by remember { mutableStateOf("") }
    // 应用内文档查看器（附件点击 → 用 QuoroDocumentViewer 内联渲染 docx/xlsx/pdf 等）
    var showDocViewer by remember { mutableStateOf(false) }
    var docViewerPath by remember { mutableStateOf("") }
    var docViewerName by remember { mutableStateOf("") }
    // 应用内全屏音乐播放器
    var showMusicPlayer by remember { mutableStateOf(false) }
    // 工具中心（能力聚合入口：终端/小程序/CMS/工具箱/沙箱/私有库）
    var showToolCenter by remember { mutableStateOf(false) }

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
            "ui_open_tool_center" -> showToolCenter = true
        }
    }
    val appCtx = LocalContext.current
    LaunchedEffect(Unit) {
        QuroUiActionBridge.dispatch = { handleUiAction(it) }
        // 桌面组件/通知在「Activity 已建、Compose 未组合」窗口期下发的打开请求：此刻补派
        QuroUiActionBridge.pendingAction?.let { a ->
            handleUiAction(a)
            QuroUiActionBridge.pendingAction = null
        }
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

    // ═══ UI 控制事件桥：AI 调用 ui_control 工具时，通过 UiNavigationBus 通知 ChatScreen 执行界面操作 ═══
    LaunchedEffect(Unit) {
        // 轮询 UiNavigationBus.navEvent（因为不是 Flow，需要定期检查）
        while (true) {
            val event = UiNavigationBus.navEvent
            if (event != null) {
                UiNavigationBus.navEvent = null // 清除事件，避免重复处理
                when (event) {
                    // ─── 打开界面 ───
                    is UiNavigationEvent.OpenScreen -> {
                        when (event.target) {
                            "editor" -> showEditor = true
                            "terminal" -> showTerminal = true
                            "toolbox" -> showToolbox = true
                            "knowledge" -> showKnowledge = true
                            "cms" -> showCms = true
                            "aci" -> showAci = true
                            "about" -> showAbout = true
                            "appearance" -> showAppearance = true
                            "soul" -> showSoulSheet = true
                            "memory" -> showMemoryDialog = true
                            "permission" -> showPermission = true
                            "model_config" -> showModelConfig = true
                            "voice" -> showVoice = true
                            "settings" -> sheet = SheetType.Settings
                            "tool_center" -> showToolCenter = true
                            else -> { /* 忽略未知界面 */ }
                        }
                    }

                    // ─── 切换开关 ───
                    is UiNavigationEvent.ToggleSwitch -> {
                        when (event.target) {
                            "deepthink" -> vm.setThinking(!vm.thinking.value)
                            "memory" -> vm.setAutoSaveMemory(!vm.autoSaveMemory.value)
                            else -> { /* 忽略未知开关 */ }
                        }
                    }

                    // ─── 打开弹层 ───
                    is UiNavigationEvent.OpenSheet -> {
                        when (event.target) {
                            "model" -> sheet = SheetType.Model
                            "persona" -> sheet = SheetType.Persona
                            "settings" -> sheet = SheetType.Settings
                            "upload" -> sheet = SheetType.Upload
                            "voice" -> sheet = SheetType.Voice
                            else -> { /* 忽略未知弹层 */ }
                        }
                    }

                    // ─── 对话管理 ───
                    is UiNavigationEvent.ChatAction -> {
                        when (event.action) {
                            "new" -> vm.newConversation()
                            "clear" -> vm.clear()
                            else -> { /* 忽略未知对话操作 */ }
                        }
                    }

                    // ─── 渲染卡片 ───
                    is UiNavigationEvent.RenderCard -> {
                        val card = com.ai.assistance.quro.core.cards.QuroChatCard.InfoCard(
                            id = "ui_card_${System.currentTimeMillis()}",
                            title = event.title,
                            body = event.content,
                            align = "start"
                        )
                        vm.attachCardToLastAssistant(card)
                    }

                    // ─── 渲染组件 ───
                    is UiNavigationEvent.RenderWidget -> {
                        // 根据widget类型创建对应的卡片
                        val card = when (event.type) {
                            "button" -> com.ai.assistance.quro.core.cards.QuroChatCard.ButtonCard(
                                id = event.id,
                                title = event.label,
                                label = event.label,
                                command = event.value,
                                variant = "filled"
                            )
                            "toggle" -> com.ai.assistance.quro.core.cards.QuroChatCard.ToggleCard(
                                id = event.id,
                                title = event.label,
                                label = event.label,
                                checked = event.value.toBooleanStrictOrNull() ?: false,
                                command = ""
                            )
                            // v1070 修复：AI 经 ui_control(action:"widget", type:"mermaid") 渲染可视化编程，
                            // 之前走 else→InfoCard 被当纯文本，图出不来。这里直接构造 MermaidCard，
                            // 复用与 ui_widget 完全一致的 onCard→气泡→MermaidCardView 离线渲染通路。
                            "mermaid" -> com.ai.assistance.quro.core.cards.QuroChatCard.MermaidCard(
                                id = event.id.ifBlank { "mermaid_${System.currentTimeMillis()}" },
                                title = event.label.ifBlank { "可视化编程" },
                                source = event.value,
                                theme = ""
                            )
                            else -> com.ai.assistance.quro.core.cards.QuroChatCard.InfoCard(
                                id = event.id,
                                title = event.label,
                                body = event.value,
                                align = "start"
                            )
                        }
                        vm.attachCardToLastAssistant(card)
                    }

                    // ─── 查询状态 ───
                    is UiNavigationEvent.QueryStatus -> {
                        // TODO: 实现状态查询逻辑
                    }

                    // ─── 更新组件属性 ───
                    is UiNavigationEvent.UpdateComponent -> {
                        // TODO: 实现组件属性更新逻辑
                    }

                    // ─── 滚动 ───
                    is UiNavigationEvent.ScrollTo -> {
                        // TODO: 实现滚动逻辑
                    }

                    // ─── 聚焦 ───
                    is UiNavigationEvent.FocusComponent -> {
                        // TODO: 实现聚焦逻辑
                    }

                    // ─── 隐藏 ───
                    is UiNavigationEvent.HideComponent -> {
                        // TODO: 实现隐藏逻辑
                    }

                    // ─── 显示 ───
                    is UiNavigationEvent.ShowComponent -> {
                        // TODO: 实现显示逻辑
                    }

                    // ─── 页面内导航 ───
                    is UiNavigationEvent.NavigateTo -> {
                        // TODO: 实现页面内导航逻辑
                    }

                    // ─── 权限控制 ───
                    is UiNavigationEvent.PermissionControl -> {
                        // TODO: 实现权限控制逻辑
                    }

                    // ─── 手势操作（点击/长按/双击/滑动/缩放/旋转）───
                    is UiNavigationEvent.ClickElement,
                    is UiNavigationEvent.LongPress,
                    is UiNavigationEvent.DoubleTap,
                    is UiNavigationEvent.Swipe,
                    is UiNavigationEvent.Pinch,
                    is UiNavigationEvent.Rotate -> {
                        // TODO: 实现手势操作逻辑
                    }

                    // ─── 文本操作（输入/复制/粘贴/剪切/全选/撤销/重做）───
                    is UiNavigationEvent.InputText,
                    is UiNavigationEvent.Copy,
                    is UiNavigationEvent.Paste,
                    is UiNavigationEvent.Cut,
                    is UiNavigationEvent.SelectAll,
                    is UiNavigationEvent.Undo,
                    is UiNavigationEvent.Redo -> {
                        // TODO: 实现文本操作逻辑
                    }

                    // ─── 系统操作（返回/首页/最近任务/分屏/截屏等）───
                    is UiNavigationEvent.Back,
                    is UiNavigationEvent.Home,
                    is UiNavigationEvent.Recent,
                    is UiNavigationEvent.SplitScreen,
                    is UiNavigationEvent.Screenshot,
                    is UiNavigationEvent.Share,
                    is UiNavigationEvent.Search,
                    is UiNavigationEvent.Refresh,
                    is UiNavigationEvent.StopLoading,
                    is UiNavigationEvent.Bookmark,
                    is UiNavigationEvent.Fullscreen,
                    is UiNavigationEvent.Minimize,
                    is UiNavigationEvent.Maximize,
                    is UiNavigationEvent.Close,
                    is UiNavigationEvent.MinimizeApp,
                    is UiNavigationEvent.LockScreen,
                    is UiNavigationEvent.WakeScreen,
                    is UiNavigationEvent.OpenNotification,
                    is UiNavigationEvent.OpenQuickSettings -> {
                        // TODO: 实现系统操作逻辑
                    }

                    // ─── 媒体控制 ───
                    is UiNavigationEvent.TakePhoto,
                    is UiNavigationEvent.StartRecording,
                    is UiNavigationEvent.StopRecording,
                    is UiNavigationEvent.PlayMedia,
                    is UiNavigationEvent.PauseMedia,
                    is UiNavigationEvent.StopMedia,
                    is UiNavigationEvent.NextTrack,
                    is UiNavigationEvent.PrevTrack -> {
                        // TODO: 实现媒体控制逻辑
                    }

                    // ─── 设备控制 ───
                    is UiNavigationEvent.VolumeUp,
                    is UiNavigationEvent.VolumeDown,
                    is UiNavigationEvent.Mute,
                    is UiNavigationEvent.Unmute,
                    is UiNavigationEvent.BrightnessUp,
                    is UiNavigationEvent.BrightnessDown,
                    is UiNavigationEvent.AutoBrightness,
                    is UiNavigationEvent.WifiOn,
                    is UiNavigationEvent.WifiOff,
                    is UiNavigationEvent.BluetoothOn,
                    is UiNavigationEvent.BluetoothOff,
                    is UiNavigationEvent.AirplaneModeOn,
                    is UiNavigationEvent.AirplaneModeOff,
                    is UiNavigationEvent.DoNotDisturbOn,
                    is UiNavigationEvent.DoNotDisturbOff,
                    is UiNavigationEvent.FlashlightOn,
                    is UiNavigationEvent.FlashlightOff,
                    is UiNavigationEvent.LocationOn,
                    is UiNavigationEvent.LocationOff,
                    is UiNavigationEvent.NfcOn,
                    is UiNavigationEvent.NfcOff,
                    is UiNavigationEvent.AutoRotateOn,
                    is UiNavigationEvent.AutoRotateOff,
                    is UiNavigationEvent.Portrait,
                    is UiNavigationEvent.Landscape -> {
                        // TODO: 实现设备控制逻辑
                    }

                    // ─── 应用管理 ───
                    is UiNavigationEvent.OpenApp,
                    is UiNavigationEvent.CloseApp,
                    is UiNavigationEvent.InstallApp,
                    is UiNavigationEvent.UninstallApp,
                    is UiNavigationEvent.FreezeApp,
                    is UiNavigationEvent.UnfreezeApp -> {
                        // TODO: 实现应用管理逻辑
                    }

                    // ─── 设置管理 ───
                    is UiNavigationEvent.OpenSettings,
                    is UiNavigationEvent.OpenAccessibilitySettings,
                    is UiNavigationEvent.OpenDeveloperOptionsSettings,
                    is UiNavigationEvent.OpenAboutPhoneSettings,
                    is UiNavigationEvent.OpenBatterySettings,
                    is UiNavigationEvent.OpenStorageSettings,
                    is UiNavigationEvent.OpenNetworkSettings,
                    is UiNavigationEvent.OpenDisplaySettings,
                    is UiNavigationEvent.OpenSoundSettings,
                    is UiNavigationEvent.OpenSecuritySettings,
                    is UiNavigationEvent.OpenPrivacySettings,
                    is UiNavigationEvent.OpenAccountsSettings,
                    is UiNavigationEvent.OpenDateTimeSettings,
                    is UiNavigationEvent.OpenLanguageSettings -> {
                        // TODO: 实现设置管理逻辑
                    }
                }
            }
            kotlinx.coroutines.delay(100) // 每100ms检查一次
        }
    }

    // 应用上下文：提前声明，供 handleUiAction / handleCardCommand 等局部函数捕获
    val ctx = LocalContext.current
    quroDiagCtx = ctx
    // 系统级悬浮窗（TYPE_APPLICATION_OVERLAY）权限：已授权时化小窗走 QuroMiniWindowManager，
    // 即使 App 退后台也浮于其他 App 之上；未授权降级为应用内 Compose 浮层。提前声明供下方条件判断使用。
    val useSystemOverlay = remember { QuroMiniWindowManager.hasOverlayPermission(ctx) }
    // 当前选择的工作区路径（从持久化存储初始化）
    var currentWorkspace by remember { mutableStateOf(com.ai.assistance.quro.core.tools.WorkspacePreferences.getCurrentWorkspace(ctx)) }
    // 当前选择的 ACI 应用名称（从持久化存储初始化，用于上下文标识栏）
    var currentAciName by remember { mutableStateOf(com.ai.assistance.quro.core.aidlaci.AciAppPreferences.getDefaultAppName(ctx)) }
    // 已启用的技能数量（用于上下文标识栏）
    var enabledSkillsCount by remember { mutableStateOf(
        com.ai.assistance.quro.core.skill.QuroSkillStore.load(ctx).count { it.enabled }
    ) }

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
                showTerminal = true
                QuroTermuxTerminalController.initialCommand = c
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
        // 构建上下文信息（作为隐藏消息注入，用户不可见）
        val ctxParts = mutableListOf<String>()
        val wsPath = currentWorkspace
        if (wsPath != null) {
            val wsName = wsPath.substringAfterLast("/").ifBlank { "工作区" }
            ctxParts.add("工作区: $wsName ($wsPath)")
        }
        val aciName = currentAciName
        if (aciName != null) {
            val pkg = com.ai.assistance.quro.core.aidlaci.AciAppPreferences.getDefaultPackage(ctx)
            if (pkg != null) {
                ctxParts.add("ACI应用: $aciName (包名: $pkg)")
            }
        }
        if (pendingVisualPopup.value) {
            ctxParts.add("用户选择了：可视化弹窗，请立即调用visual_popup工具创建一个可视化弹窗")
            pendingVisualPopup.value = false
        }
        if (pendingVisualQuestion.value) {
            ctxParts.add("用户选择了：可视化询问，请立即调用visual_question工具创建一个可视化询问")
            pendingVisualQuestion.value = false
        }
        if (enabledSkillsCount > 0) {
            val enabledSkills = com.ai.assistance.quro.core.skill.QuroSkillStore.load(ctx)
                .filter { it.enabled }
                .map { it.name }
            ctxParts.add("已启用技能(${enabledSkillsCount}个): ${enabledSkills.joinToString("、")}")
        }
        val contextStr = ctxParts.joinToString("\n").ifBlank { null }
        vm.send(t, attachments.toList(), cfg, contextMessage = contextStr)
        attachments.clear()
    }

    Box(Modifier.fillMaxSize()) {
        // 对话框「化小窗」：chatMinimized 时主对话收起为悬浮小窗，根布局仅留背景占位。
        // 系统级浮窗（useSystemOverlay）下不拆除主屏内容：主屏在浮层之下保持已组合状态，
        // 返回全屏时仅移除浮层即可，避免整屏重建导致的卡顿；占位仅在应用内降级浮层时生效。
        if (chatMinimized && !useSystemOverlay) {
            Box(Modifier.fillMaxSize().background(cs.background))
        } else ModalNavigationDrawer(
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
            // 可视化问答和操作弹窗
            VisualDialogs()
            // 自由可视化弹窗
            VisualPopupDialog()
            // AI自写UI可视化弹窗
            VisualCustomPopupDialog()
            Scaffold(
                containerColor = cs.background,
                topBar = {
                    ChatTopBar(
                        modelName = modelLabel,
                        onMenu = openDrawer,
                        onModel = { sheet = SheetType.Model },
                        onSettings = { sheet = SheetType.Settings },
                        onToolCenter = { showToolCenter = true },
                        onMinimize = {
                            chatMinimized = true
                            // 系统级浮窗：把 App 切到后台，让对话小窗浮于桌面/其他 App 之上
                            if (useSystemOverlay) (ctx as? android.app.Activity)?.moveTaskToBack(true)
                        },
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
                        busy = busy,
                        traceLines = traceLines,
                        onOpenLink = { browserUrl = it },
                        onCommand = { handleCardCommand(it) },
                        onSend = { send(it) },
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
                        onAttachmentActivate = { att ->
                            when (att.type) {
                                "image" -> {
                                    imageViewerPath = att.path ?: ""
                                    imageViewerName = att.name
                                    showImageViewer = true
                                }
                                "video" -> {
                                    videoPlayerUri = "file://" + (att.path ?: "")
                                    videoPlayerTitle = att.name
                                    showVideoPlayer = true
                                }
                                else -> {
                                    // 文档/文件：使用应用内 QuoroDocumentViewer 预览，
                                    // 支持 docx/xlsx/pptx/pdf 等格式的富文本渲染
                                    val f = att.path?.let { File(it) }
                                    if (f != null && f.exists()) {
                                        val ext = f.extension.lowercase()
                                        val previewableExts = setOf(
                                            "docx", "xlsx", "pptx", "pdf",
                                            "txt", "md", "markdown", "json", "csv", "xml",
                                            "html", "htm", "log", "kt", "kts", "py", "js", "ts", "css", "java",
                                            "png", "jpg", "jpeg", "gif", "webp", "bmp",
                                        )
                                        if (ext in previewableExts) {
                                            docViewerPath = att.path ?: ""
                                            docViewerName = att.name
                                            showDocViewer = true
                                        } else {
                                            openFileWithSystemViewer(ctx, att)
                                        }
                                    } else {
                                        openFileWithSystemViewer(ctx, att)
                                    }
                                }
                            }
                        },
                        onAttachmentDownload = { downloadAttachment(ctx, it) },
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
                                // ★ 朗读协调：若本回合 AI 已用 speak 工具主动播报（用户要求"让 AI 控制朗读顺序"），
                                //   自动朗读让位，不再重复朗读同一回复；AI 的多次 speak 调用由串行队列按调用顺序播放。
                                if (QuroTtsHolder.consumeSpeakToolFired()) {
                                    Log.d("TTS", "自动朗读让位：本回合 AI 已用 speak 工具控制播报顺序")
                                } else {
                                    // v414 修复：ensureReady/speak 是挂起调用，改由稳定 scope 承接，UI 状态变化不再杀掉朗读。
                                    ttsScope.launch {
                                        QuroTtsHolder.ensureReady(ctx)
                                        QuroTtsHolder.speak(last.content)
                                    }
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
                        onOpenSkills = { showSkillSelector = true },
                        onOpenAciSelector = { showAciSelector = true },
                        onOpenEditor = { showEditor = true },
                        onSelectVisualPopup = {
                            pendingVisualPopup.value = !pendingVisualPopup.value
                            if (pendingVisualPopup.value) {
                                Toast.makeText(ctx, "已选择：可视化弹窗，发送消息时将触发", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(ctx, "已取消：可视化弹窗", Toast.LENGTH_SHORT).show()
                            }
                        },
                        onSelectVisualQuestion = {
                            pendingVisualQuestion.value = !pendingVisualQuestion.value
                            if (pendingVisualQuestion.value) {
                                Toast.makeText(ctx, "已选择：可视化询问，发送消息时将触发", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(ctx, "已取消：可视化询问", Toast.LENGTH_SHORT).show()
                            }
                        },
                        pendingVisualPopup = pendingVisualPopup.value,
                        pendingVisualQuestion = pendingVisualQuestion.value,
                        currentWorkspace = currentWorkspace,
                        onOpenWorkspaceSelector = { showWorkspaceSelector = true },
                        currentAciName = currentAciName,
                        enabledSkillsCount = enabledSkillsCount,
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
                ml.models.map { info ->
                    ChatModel(name = info.id, id = info.id, desc = "来自 ${cfg.baseUrl}", provider = cfg.provider.ifBlank { "自定义" }, mark = info.id.firstOrNull()?.uppercase() ?: "M")
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
            showTts || showStt || showVoiceService || showSystemStatus || showFeatureModelConfig || showAci ||
            showToolCenter || showUsbDebug || showDefaultApp
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
            onOpenLspose = { showLspose = true },
            onOpenUsbDebug = { showUsbDebug = true },
            onOpenDefaultApp = { showDefaultApp = true },
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
            onOpenCleanup = { showCleanup = true },
            onOpenFileManager = { showFileManager = true },
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

        // LSPosed 模块页：全屏覆盖层（从设置底部弹层入口进入，返回关页回设置）
        if (showLspose) {
            BackHandler { showLspose = false }
            Box(Modifier.fillMaxSize().zIndex(100f).background(MaterialTheme.colorScheme.background)) {
                QuroLsposeScreen(onClose = { showLspose = false })
            }
        }

        // USB / 无线调试 (ADB) 页：全屏覆盖层（从设置页入口进入，返回关页回设置）
        if (showUsbDebug) {
            BackHandler { showUsbDebug = false }
            Box(Modifier.fillMaxSize().zIndex(100f).background(MaterialTheme.colorScheme.background)) {
                QuroUsbDebugScreen(onClose = { showUsbDebug = false })
            }
        }

        // 默认应用角色管理页：全屏覆盖层（从设置页入口进入，返回关页回设置）
        if (showDefaultApp) {
            BackHandler { showDefaultApp = false }
            Box(Modifier.fillMaxSize().zIndex(100f).background(MaterialTheme.colorScheme.background)) {
                QuroDefaultAppScreen(onClose = { showDefaultApp = false })
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
                QuroSkillsScreen(onClose = {
                    showSkills = false
                    // 技能页关闭后刷新已启用技能数量
                    enabledSkillsCount = com.ai.assistance.quro.core.skill.QuroSkillStore.load(ctx).count { it.enabled }
                })
            }
        }

        // 定时任务管理页：全屏覆盖层（从设置页「定时任务」/ ui_open_schedule 进入）
        if (showSchedule) {
            BackHandler { showSchedule = false }
            Box(Modifier.fillMaxSize().zIndex(100f).background(Color(0xFFF2F2F7))) {
                QuroScheduleScreen(onClose = { showSchedule = false })
            }
        }

        // 工作流管理页：全屏覆盖层（从工具箱「工作流」入口进入）
        if (showWorkflow) {
            BackHandler { showWorkflow = false }
            Box(Modifier.fillMaxSize().zIndex(100f).background(Color(0xFFF2F2F7))) {
                QuroWorkflowScreen(onClose = { showWorkflow = false })
            }
        }

        // 全屏文本编辑器：编辑对话框长文本
        if (showEditor) {
            BackHandler { showEditor = false }
            Box(Modifier.fillMaxSize().zIndex(100f).background(Color(0xFFF2F2F7))) {
                TextEditorScreen(
                    initialText = inputText.text,
                    onClose = { text -> inputText = TextFieldValue(text); showEditor = false }
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

        // 清理存储页：全屏覆盖层（从设置「数据 → 清理存储」进入）
        if (showCleanup) {
            BackHandler { showCleanup = false }
            Box(Modifier.fillMaxSize().zIndex(100f).background(cs.background)) {
                CleanupScreen(onClose = { showCleanup = false }, scaled = { scaled(it) })
            }
        }

        // 文件管理页：全屏覆盖层（从设置「文件管理」进入）
        if (showFileManager) {
            BackHandler { showFileManager = false }
            Box(Modifier.fillMaxSize().zIndex(100f).background(cs.background)) {
                FileManagerDialog(onClose = { showFileManager = false }, scaled = { scaled(it) })
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
                QuroAidlAciCenterScreen(onClose = { showAci = false })
            }
        }

        // ACI 应用选择器对话框
        if (showAciSelector) {
            AciAppSelectionDialog(
                onDismiss = { showAciSelector = false },
                onAppSelected = { packageName, appName ->
                    // 设置默认 ACI 应用
                    com.ai.assistance.quro.core.aidlaci.AciAppPreferences.setDefaultApp(
                        ctx, packageName, appName
                    )
                    currentAciName = appName
                    Toast.makeText(ctx, "已设置默认 ACI 应用: $appName", Toast.LENGTH_SHORT).show()
                    showAciSelector = false
                },
                onClearSelection = {
                    // 清除默认 ACI 应用
                    com.ai.assistance.quro.core.aidlaci.AciAppPreferences.clearDefaultApp(ctx)
                    currentAciName = null
                    Toast.makeText(ctx, "已清除默认 ACI 应用", Toast.LENGTH_SHORT).show()
                },
                initialSelectedPackage = com.ai.assistance.quro.core.aidlaci.AciAppPreferences.getDefaultPackage(ctx),
            )
        }

        // 工作区选择器对话框
        if (showWorkspaceSelector) {
            WorkspaceSelectionDialog(
                onDismiss = { showWorkspaceSelector = false },
                onWorkspaceSelected = { path ->
                    currentWorkspace = path
                    com.ai.assistance.quro.core.tools.WorkspacePreferences.setCurrentWorkspace(ctx, path)
                    Toast.makeText(ctx, "已选择工作区: ${path.substringAfterLast('/')}", Toast.LENGTH_SHORT).show()
                },
                onClearWorkspace = {
                    currentWorkspace = null
                    com.ai.assistance.quro.core.tools.WorkspacePreferences.clearCurrentWorkspace(ctx)
                    Toast.makeText(ctx, "已恢复默认工作区", Toast.LENGTH_SHORT).show()
                },
                initialSelectedPath = currentWorkspace,
            )
        }

        // 技能选择对话框（从输入框工具菜单 / 上下文标识栏进入）
        if (showSkillSelector) {
            SkillSelectionDialog(
                onDismiss = { showSkillSelector = false },
                onSkillsChanged = { count -> enabledSkillsCount = count },
            )
        }

        // 可视化弹窗配置对话框
        // 可视化弹窗配置对话框（已移除，使用状态模式）

        // 语音设置页：内嵌对话框底部的紧凑面板（不再是全屏页）
        if (showVoice) {
            BackHandler { showVoice = false; showVoiceService = true }
            Box(Modifier.fillMaxSize().zIndex(100f)) {
                Surface(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().heightIn(max = 560.dp)
                        .navigationBarsPadding(),
                    shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                    tonalElevation = 8.dp,
                    shadowElevation = 12.dp,
                ) {
                    QuroVoiceSettingsScreen(
                        onBack = { showVoice = false; showVoiceService = true },
                        onToggleVoiceBall = onToggleVoiceBall,
                        voiceBallEnabled = voiceBallEnabled,
                    )
                }
            }
        }

        // 语音合成 (TTS) 设置页：内嵌底部紧凑面板
        if (showTts) {
            BackHandler { showTts = false; showVoiceService = true }
            Box(Modifier.fillMaxSize().zIndex(100f).background(cs.background)) {
                Surface(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().heightIn(max = 560.dp).navigationBarsPadding(),
                    shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                    tonalElevation = 8.dp, shadowElevation = 12.dp,
                ) {
                    QuroTtsSettingsScreen(
                        onBack = { showTts = false; showVoiceService = true },
                        onOpenCloudConfig = { showTts = false; sheet = null; showCloudTts = true },
                    )
                }
            }
        }

        // 语音识别 (STT) 设置页：内嵌底部紧凑面板
        if (showStt) {
            BackHandler { showStt = false; showVoiceService = true }
            Box(Modifier.fillMaxSize().zIndex(100f).background(cs.background)) {
                Surface(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().heightIn(max = 560.dp).navigationBarsPadding(),
                    shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                    tonalElevation = 8.dp, shadowElevation = 12.dp,
                ) {
                    QuroSttSettingsScreen(onBack = { showStt = false; showVoiceService = true })
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
                    onOpenWorkflow = { showWorkflow = true },
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

        // 可交互终端（终端渲染 + proot/Ubuntu 24.04 后端）：全屏覆盖层（从工具栏「终端」进入）
        if (showTerminal) {
            BackHandler { showTerminal = false }
            Box(Modifier.fillMaxSize().zIndex(100f).background(cs.background)) {
                QuroTermuxTerminalScreen(
                    onClose = { showTerminal = false },
                )
            }
        }

        // 工具中心（能力聚合入口：终端/小程序/CMS/工具箱/沙箱/私有库）
        if (showToolCenter) {
            BackHandler { showToolCenter = false }
            Box(Modifier.fillMaxSize().zIndex(100f).background(cs.background)) {
                QuroToolCenterScreen(
                    context = appCtx,
                    onLaunch = { target ->
                        when (target) {
                            "terminal" -> showTerminal = true
                            "cms" -> showCms = true
                            "toolbox" -> showToolbox = true
                            "editor" -> showEditor = true
                            "browser_ai" -> {
                                com.ai.assistance.quro.core.QuroBrowserBridge.open("https://www.baidu.com")
                                showToolCenter = false
                                android.widget.Toast.makeText(appCtx, "AI 可用 browser_act 操控此浏览器", android.widget.Toast.LENGTH_SHORT).show()
                            }
                            "crawler" -> {
                                com.ai.assistance.quro.core.QuroBrowserBridge.open("https://www.baidu.com")
                                showToolCenter = false
                                android.widget.Toast.makeText(appCtx, "AI 可用 web_crawler 批量抓取（网页爬虫）：告诉它起始 URL 即可", android.widget.Toast.LENGTH_LONG).show()
                            }
                            "python_ai" -> {
                                showTerminal = true
                                showToolCenter = false
                                android.widget.Toast.makeText(appCtx, "AI 可用 python_run 跑 Python", android.widget.Toast.LENGTH_SHORT).show()
                            }
                            "mitm" -> {
                                showToolCenter = false
                                kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                    val tool = com.ai.assistance.quro.core.tools.PacketCaptureTool()
                                    val out = tool.run(appCtx, "{\"action\":\"start\",\"port\":8080}")
                                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                        android.widget.Toast.makeText(appCtx, out.take(200), android.widget.Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                            else -> showToolCenter = false
                        }
                    },
                    onClose = { showToolCenter = false },
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
                    onMinimize = {
                        val u = browserUrl
                        browserFloatUrl = u
                        // 系统级浮窗下保留主浏览器（不销毁 WebView）：返回全屏仅移除浮层，
                        // 主浏览器已加载无需整页重载，避免卡顿；仅应用内降级浮层才销毁主浏览器。
                        if (!useSystemOverlay) browserUrl = null
                        // 系统级浮窗：把 App 切到后台，让小窗真正浮于桌面/其他 App 之上
                        if (useSystemOverlay) (ctx as? android.app.Activity)?.moveTaskToBack(true)
                    },
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

        // ═══ 系统级化小窗（TYPE_APPLICATION_OVERLAY，浮于桌面/其他 App 之上）═══
        // 已授予 SYSTEM_ALERT_WINDOW 时，把小窗渲染为真正的系统悬浮窗（QuroMiniWindowManager），
        // 即使 App 退后台/切到其他软件也持续可见；未授权则下方继续走应用内 Compose 浮层（降级）。
        var askedOverlayPerm by remember { mutableStateOf(false) }
        DisposableEffect(Unit) {
            QuroMiniWindowManager.onExpandChat = { chatMinimized = false; if (useSystemOverlay) QuroMiniWindowManager.bringAppToForeground() }
            QuroMiniWindowManager.onCloseChat = { chatMinimized = false; if (useSystemOverlay) QuroMiniWindowManager.bringAppToForeground() }
            QuroMiniWindowManager.onNewConversation = { vm.newConversation(); chatMinimized = false; if (useSystemOverlay) QuroMiniWindowManager.bringAppToForeground() }
            QuroMiniWindowManager.onRestoreBrowser = { url -> browserUrl = url; browserFloatUrl = null; if (useSystemOverlay) QuroMiniWindowManager.bringAppToForeground() }
            QuroMiniWindowManager.onCloseBrowser = { browserFloatUrl = null; if (useSystemOverlay) QuroMiniWindowManager.bringAppToForeground() }
            QuroMiniWindowManager.onSendMessage = { send(it) }
            QuroMiniWindowManager.setMessageSource(vm.messages)
            onDispose {
                QuroMiniWindowManager.onExpandChat = null
                QuroMiniWindowManager.onCloseChat = null
                QuroMiniWindowManager.onNewConversation = null
                QuroMiniWindowManager.onRestoreBrowser = null
                QuroMiniWindowManager.onCloseBrowser = null
                QuroMiniWindowManager.onSendMessage = null
                // 离开对话界面时收起系统悬浮窗，避免残留浮在其它界面
                QuroMiniWindowManager.hideChat()
                QuroMiniWindowManager.hideBrowser()
            }
        }
        LaunchedEffect(chatMinimized) {
            if (useSystemOverlay) {
                if (chatMinimized) QuroMiniWindowManager.showChat(ctx, cs) else QuroMiniWindowManager.hideChat()
            } else if (chatMinimized && !askedOverlayPerm) {
                // 未授权时引导用户开启「显示在其他应用上层」，以便真正系统级浮窗
                askedOverlayPerm = true
                QuroMiniWindowManager.requestOverlayPermission(ctx)
            }
        }
        LaunchedEffect(browserFloatUrl) {
            if (useSystemOverlay) {
                val url = browserFloatUrl
                if (url != null) QuroMiniWindowManager.showBrowser(ctx, url, cs) else QuroMiniWindowManager.hideBrowser()
            } else if (browserFloatUrl != null && !askedOverlayPerm) {
                askedOverlayPerm = true
                QuroMiniWindowManager.requestOverlayPermission(ctx)
            }
        }

        // 内置浏览器「化小窗」：全屏浏览器收起后，改由可拖拽悬浮小窗承载同一网址。
        // 小窗内是独立 WebView（复用 QuroBrowserController.attach，AI 的 browser_act 仍可用），
        // 拖到任意位置即可边浏览边用对话；点还原回到全屏，点关闭退出小窗。
        // （仅当未启用系统级悬浮窗时走此应用内 Compose 浮层；系统级由 QuroMiniWindowManager 承载）
        if (!useSystemOverlay) browserFloatUrl?.let { furl ->
            Box(Modifier.fillMaxSize().zIndex(300f)) {
                FloatingMiniWindow(
                    title = "浏览器小窗",
                    initialX = 40.dp, initialY = 150.dp,
                    initialWidth = 320.dp, initialHeight = 400.dp,
                    onRestore = { browserUrl = furl; browserFloatUrl = null },
                    onClose = { browserFloatUrl = null },
                ) {
                    val webSchemes = setOf("http", "https", "file", "about", "data", "javascript")
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { c ->
                            WebView(c).apply {
                                settings.apply {
                                    javaScriptEnabled = true
                                    domStorageEnabled = true
                                    databaseEnabled = true
                                    loadsImagesAutomatically = true
                                    loadWithOverviewMode = true
                                    useWideViewPort = true
                                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                                    allowFileAccess = true
                                    javaScriptCanOpenWindowsAutomatically = true
                                    defaultTextEncodingName = "utf-8"
                                }
                                com.ai.assistance.quro.core.tools.QuroBrowserController.attach(this)
                                webViewClient = object : WebViewClient() {
                                    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                                        com.ai.assistance.quro.core.tools.QuroBrowserController.markPageStarted(url)
                                    }
                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        com.ai.assistance.quro.core.tools.QuroBrowserController.markPageFinished(url)
                                    }
                                    override fun shouldOverrideUrlLoading(view: WebView?, request: android.webkit.WebResourceRequest?): Boolean {
                                        val u = request?.url ?: return false
                                        val scheme = u.scheme?.lowercase() ?: return false
                                        if (scheme in webSchemes) return false
                                        runCatching {
                                            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, u)
                                            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                            c.startActivity(intent)
                                        }
                                        return true
                                    }
                                    @Suppress("DEPRECATION")
                                    override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                                        if (url.isNullOrEmpty()) return false
                                        val parsed = runCatching { android.net.Uri.parse(url) }.getOrNull() ?: return false
                                        val scheme = parsed.scheme?.lowercase() ?: return false
                                        if (scheme in webSchemes) return false
                                        runCatching {
                                            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, parsed)
                                            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                            c.startActivity(intent)
                                        }
                                        return true
                                    }
                                }
                                // 化小窗同样需要 WebChromeClient：补齐 onReceivedTitle → markTitle，
                                // 否则化小窗后 status()/snapshot() 的 title 永远为空，AI 看不到页面标题（首个被忽略的真实缺陷）。
                                webChromeClient = object : WebChromeClient() {
                                    override fun onReceivedTitle(view: WebView?, title: String?) {
                                        if (!title.isNullOrEmpty()) {
                                            com.ai.assistance.quro.core.tools.QuroBrowserController.markTitle(title)
                                        }
                                    }
                                }
                                loadUrl(furl)
                            }
                        },
                        onRelease = {
                            com.ai.assistance.quro.core.tools.QuroBrowserController.detach(it)
                            it.destroy()
                        },
                    )
                }
            }
        }

        // 对话框「化小窗」：主对话收起后，改由可拖拽悬浮小窗承载最近对话，可边看边用其它功能。
        // （仅当未启用系统级悬浮窗时走此应用内 Compose 浮层；系统级由 QuroMiniWindowManager 承载）
        if (!useSystemOverlay && chatMinimized) {
            Box(Modifier.fillMaxSize().zIndex(300f)) {
                FloatingMiniWindow(
                    title = "对话小窗",
                    initialX = 24.dp, initialY = 120.dp,
                    initialWidth = 300.dp, initialHeight = 420.dp,
                    onRestore = { chatMinimized = false },
                    onClose = { chatMinimized = false },
                ) {
                    Column(Modifier.fillMaxSize()) {
                        LazyColumn(
                            Modifier.fillMaxWidth().weight(1f).padding(8.dp),
                            reverseLayout = true,
                        ) {
                            val recent = uiMessages.takeLast(15)
                            items(recent.size) { idx ->
                                val m = recent[idx]
                                val label = if (m.mine) "我" else "AI"
                                Text(
                                    "$label：${(m.text ?: "").take(200)}",
                                    fontSize = 12.sp,
                                    color = cs.onSurface,
                                    maxLines = 4,
                                    modifier = Modifier.padding(vertical = 3.dp),
                                )
                            }
                        }
                        Row(
                            Modifier.fillMaxWidth().background(cs.surfaceVariant).padding(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(onClick = { vm.newConversation(); chatMinimized = false }) {
                                Text("新建对话")
                            }
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = { chatMinimized = false }) {
                                Text("展开对话")
                            }
                        }
                    }
                }
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

        // 全屏图片查看器（双击缩放、双指捏合、拖动）
        if (showImageViewer) {
            BackHandler { showImageViewer = false }
            FullScreenImageViewer(
                path = imageViewerPath,
                name = imageViewerName,
                onClose = { showImageViewer = false },
            )
        }

        // 应用内文档查看器（附件点击后全屏预览 docx/xlsx/pptx/pdf 等）
        if (showDocViewer) {
            BackHandler { showDocViewer = false }
            Box(Modifier.fillMaxSize().zIndex(130f).background(cs.background)) {
                val docFile = runCatching { File(docViewerPath) }.getOrNull()
                if (docFile != null && docFile.exists()) {
                    QuroDocumentViewer(
                        file = docFile,
                        onClose = { showDocViewer = false },
                        onExternal = {
                            openFileWithSystemViewer(ctx, Attachment(
                                name = docViewerName,
                                meta = "",
                                path = docViewerPath,
                                type = "file"
                            ))
                            showDocViewer = false
                        },
                        readOnly = false,
                    )
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("文件不存在：$docViewerPath", color = cs.error, fontSize = 13.sp)
                    }
                }
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
    onAttachmentActivate: (Attachment) -> Unit = {},
    onAttachmentDownload: (Attachment) -> Unit = {},
    onSend: (String) -> Unit = {},
    currentId: String,
    busy: Boolean = false,
    modifier: Modifier = Modifier
) {
    val cs = MaterialTheme.colorScheme
    val narrow = LocalConfiguration.current.screenWidthDp < 400
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val lastMsg = messages.lastOrNull()
    // 是否贴底（仅用于「回到底部」按钮可见性判定：列表还能继续向下滚 = 不在底部）。
    val isAtBottom by remember { derivedStateOf { !listState.canScrollForward } }

    // 是否自动跟随流式输出：由【用户拖拽手势意图】决定，而非「当前布局是否到底」。
    // 旧逻辑曾用 !canScrollForward 同时控制跟随，但流式输出使最后一项超出视口后 canScrollForward
    // 恒为 true，导致内容一超过一屏就停止跟随（#auto-follow 回归：对话框不能跟随 AI 最新输出）。
    // 新方案：用户开始拖拽即暂停跟随；松手时下方仍有可滚内容=停在中/上方→保持暂停，
    // 松手已在底部→恢复跟随。程序 scrollToItem 不触发 DragInteraction，二者互不干扰。
    val shouldFollow = remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { inter ->
            when (inter) {
                is DragInteraction.Start -> shouldFollow.value = false
                is DragInteraction.Stop -> shouldFollow.value = !listState.canScrollForward
                is DragInteraction.Cancel -> shouldFollow.value = !listState.canScrollForward
            }
        }
    }

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
        // 先把最后一项滚入视口（顶部对齐），触发布局测量
        listState.scrollToItem(lastItemIndex)
        // 关键修复（#滚动对齐）：等一帧让布局稳定，再读真实 item 高度——否则取到的是滚动前的旧布局，
        // 导致「高于一屏的长消息」底部算不到位、最新 token 被推到视口下方看不见（即内容能到哪里 vs 滚动只到哪里）。
        kotlinx.coroutines.delay(0)
        val layoutInfo = listState.layoutInfo
        val lastInfo = layoutInfo.visibleItemsInfo.lastOrNull { it.index == lastItemIndex } ?: return
        val viewportH = layoutInfo.viewportSize.height
        // 项比一屏高：scrollToItem 的 scrollOffset 必须非负，用「项高 − 视口高」(正值) 把该项底部对齐视口底，
        // 保证输入框上方的最新内容始终可见；项比一屏矮时无需额外偏移（同样钳制到底部）。
        if (lastInfo.size > viewportH) {
            listState.scrollToItem(lastItemIndex, lastInfo.size - viewportH)
        }
    }

    // 触发①：进入 / 切换会话 → 无条件落到底部看最新一条，并恢复自动跟随。
    LaunchedEffect(currentId) {
        shouldFollow.value = true
        pinToBottom()
    }

    // 触发②：用户刚发出新消息（最后一条是用户消息）→ 强制跳到底部，即使此前在中部上滑，并恢复跟随。
    LaunchedEffect(lastMsg?.id) {
        if (lastMsg != null && lastMsg.mine) {
            shouldFollow.value = true
            pinToBottom()
        }
    }

    // 触发③：流式回复增长（同条消息的可见内容变）→ 仅当用户「意图停在底部」(shouldFollow) 时自动跟随；
    // 用户若上滑阅读历史，shouldFollow=false，跳过自动滚动，由下方「回到底部」按钮兜底，
    // 避免流式 token 持续把视口拽回底部、导致无法阅读上方内容（#auto-follow 回归修复）。
    // 综合签名覆盖 text + 工具卡数 + 推理步数 + 卡片数，确保思考/工具/最终文本任何阶段增长都跟随，
    // 不再只依赖单一 text 字段（文本尚为空、仅思考/工具在推进时也能跟随）。
    val lastSig = buildString {
        val lm = messages.lastOrNull()
        append(lm?.text ?: "")
        append('#'); append(lm?.tools?.size ?: -1)
        append('#'); append(lm?.think?.steps?.size ?: -1)
        append('#'); append(lm?.cards?.size ?: -1)
    }
    LaunchedEffect(lastSig) {
        if (messages.isEmpty()) return@LaunchedEffect
        if (!shouldFollow.value) return@LaunchedEffect
        pinToBottom()
    }
    // 注意：执行轨迹事件已统一在 ChatScreen 顶层订阅一次（单一真相源 traceLines），
    // 此处不再各自 collect 全局流，避免重复订阅 / 跨会话污染。
    // 互动条（scroll-to-bottom 交互）：用户上滑离开底部时浮出「回到底部」按钮，
    // 点击即跳到最新一条。与上方三触发自动滚底互补，作手动兜底。
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
                    embeddedTrace = if (!msg.tools.isNullOrEmpty()) visibleTraces else emptyList(),
                    onCommand = { onCommand(it) },
                        onAskFollowup = onAskFollowup,
                        onShare = onShare,
                        onRegenerate = onRegenerate,
                        onDelete = onDelete,
                        narrow = narrow,
                        onAttachmentActivate = onAttachmentActivate,
                        onAttachmentDownload = onAttachmentDownload,
                        // 🔧 Bug修复「思考没有修复」：生成中的最后一条消息默认展开思考内容，
                        // 让流式 reasoning 实时可见（此前思考只藏在 9sp 收起胶囊后，等于看不见）。
                        streamingThink = busy && index == messages.lastIndex,
                        onSend = onSend,
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
            onClick = { scope.launch { shouldFollow.value = true; pinToBottom() } },
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

/**
 * 判断富卡片是否为「紧凑型」：在聊天气泡自由排版（FlowRow）中，紧凑型卡片按内容自然宽度排布、
 * 可与其他卡片并排成行并自动换行；宽型卡片（图表 / 表格 / 流程图等）独占一整行，避免被挤窄。
 */
private fun isCompactQuroCard(card: QuroChatCard): Boolean = when (card) {
    is QuroChatCard.ButtonCard,
    is QuroChatCard.ActionCard,
    is QuroChatCard.ToggleCard,
    is QuroChatCard.SliderCard,
    is QuroChatCard.ProgressCard,
    is QuroChatCard.StatCard,
    is QuroChatCard.SegmentedCard,
    is QuroChatCard.RatingCard,
    is QuroChatCard.CountdownCard,
    is QuroChatCard.ChipsCard,
    is QuroChatCard.QuickReplyCard,
    is QuroChatCard.QuickActionCard,
    is QuroChatCard.ColorCard,
    is QuroChatCard.CounterCard,
    is QuroChatCard.BreadcrumbCard,
    is QuroChatCard.TagCloudCard,
    is QuroChatCard.BadgeCard,
    is QuroChatCard.AvatarGroupCard -> true
    else -> false
}

@Composable
private fun MessageRow(
    msg: Message,
    scaled: (Int) -> androidx.compose.ui.unit.TextUnit,
    onOpenLink: (String) -> Unit,
    embeddedTrace: List<QuroAgentTrace.AgentTraceEvent> = emptyList(),
    onCommand: (String) -> Unit = {},
    onAskFollowup: (String) -> Unit = {},
    onShare: (String) -> Unit = {},
    onRegenerate: () -> Unit = {},
    onDelete: (List<String>) -> Unit = {},
    narrow: Boolean = false,
    /** 附件点击：图片→全屏预览 / 视频→播放器 / 文档→系统查看器（由 ChatScreen 处理具体路由）。 */
    onAttachmentActivate: (Attachment) -> Unit = {},
    onAttachmentDownload: (Attachment) -> Unit = {},
    /** 该消息是否为「正在生成中的最后一条」：是则默认展开思考内容（流式 reasoning 实时可见）。 */
    streamingThink: Boolean = false,
    /** 代码块自动修复：发送错误信息给 AI 分析修复 */
    onSend: (String) -> Unit = {},
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
            // 🔧 用户诉求（toolfix8 修正）：思考过程默认【折叠】，不手动点永远不展开。
            //   即使生成中最后一条也保持折叠；点击下方「思考过程 · N步」胶囊才展开。
            var showThink by remember { mutableStateOf(false) }
            // 🔧 用户诉求（toolfix8 修正）：工具调用默认【折叠】，不手动点永远不展开。
            //   点击「· N 工具」胶囊才展开；内层每个工具块默认也折叠（见 ToolCallBlock expanded 默认 false）。
            var showTools by remember { mutableStateOf(false) }
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
                            // 生成中显示「思考中」，完成后显示「思考过程 · N 步」（此前恒为「思考中」，完成后文案误导）
                            Text(if (streamingThink) "思考中" else "思考过程 · ${msg.think.steps.size}步", fontSize = 9.sp, color = Accent)
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
            // 等待指示：「等等」动态小组件（内容区 loading，独立于头像/名字）。
            // 仅在 AI 尚未产出首条内容（busy 占位）时出现，真实回复到达后该占位被移除。
            if (msg.isWaiting) {
                WaitingDots()
                Spacer(Modifier.height(4.dp))
            }
            // 执行中指示：AI 正在执行工具（结果尚未回填）→ 显示「AI 正在执行工具…」工作小组件。
            if (msg.isWorking) {
                WorkingIndicator()
                Spacer(Modifier.height(4.dp))
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
            // ═══ 可视化弹窗/自定义弹窗小卡片：独立于工具区域显示 ═══
            if (!msg.tools.isNullOrEmpty()) {
                msg.tools.filter { it.name == "visual_popup" && it.args.isNotBlank() }.forEach { t ->
                    Spacer(Modifier.height(6.dp))
                    val popupCardInfo = remember(t.args) {
                        runCatching {
                            val json = org.json.JSONObject(t.args)
                            val title = json.optString("title", "可视化弹窗")
                            val cardTitle = json.optString("card_title", title)
                            val cardDescription = json.optString("card_description", "点击查看详情")
                            Triple(title, cardTitle, cardDescription)
                        }.getOrDefault(Triple("可视化弹窗", "可视化弹窗", "点击查看详情"))
                    }
                    val (_, cardTitle, cardDescription) = popupCardInfo
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                runCatching {
                                    val json = org.json.JSONObject(t.args)
                                    val pTitle = json.optString("title", "可视化弹窗")
                                    val content = json.optString("content", "")
                                    val btns = mutableListOf<PopupButton>()
                                    json.optJSONArray("buttons")?.let { arr ->
                                        for (i in 0 until arr.length()) {
                                            val b = arr.optJSONObject(i) ?: continue
                                            val txt = b.optString("text", "")
                                            val v = b.optString("value", "")
                                            if (txt.isNotBlank() && v.isNotBlank()) btns.add(PopupButton(txt, v, b.optString("style", "primary")))
                                        }
                                    }
                                    val inps = mutableListOf<PopupInput>()
                                    json.optJSONArray("inputs")?.let { arr ->
                                        for (i in 0 until arr.length()) {
                                            val inp = arr.optJSONObject(i) ?: continue
                                            val id = inp.optString("id", "")
                                            val lbl = inp.optString("label", "")
                                            if (id.isNotBlank() && lbl.isNotBlank()) inps.add(PopupInput(id, lbl, inp.optString("placeholder", ""), inp.optString("default_value", ""), inp.optString("type", "text")))
                                        }
                                    }
                                    val latch = java.util.concurrent.CountDownLatch(1)
                                    val resultRef = java.util.concurrent.atomic.AtomicReference<PopupResult?>(null)
                                    VisualPopupQueue.addPopup(com.ai.assistance.quro.core.tools.VisualPopupData(
                                        id = "popup_${System.currentTimeMillis()}_${(Math.random() * 1000).toInt()}",
                                        title = pTitle, content = content, buttons = btns, inputs = inps,
                                        imageUrl = json.optString("image_url", "").ifBlank { null },
                                        width = if (json.has("width")) json.optInt("width") else null,
                                        height = if (json.has("height")) json.optInt("height") else null,
                                        cardTitle = json.optString("card_title", pTitle),
                                        cardDescription = json.optString("card_description", "点击查看详情"),
                                        cancelable = json.optBoolean("cancelable", true),
                                        timeout = json.optInt("timeout", 60),
                                        latch = latch, result = resultRef
                                    ))
                                }
                            },
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(containerColor = cs.primaryContainer),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Language, null, tint = cs.primary, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(cardTitle, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = cs.onPrimaryContainer, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (cardDescription.isNotBlank()) Text(cardDescription, fontSize = 10.sp, color = cs.onPrimaryContainer.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, "打开弹窗", tint = cs.primary, modifier = Modifier.size(16.dp))
                        }
                    }
                }
                msg.tools.filter { it.name == "visual_custom_popup" && it.args.isNotBlank() }.forEach { t ->
                    Spacer(Modifier.height(6.dp))
                    val popupCardInfo = remember(t.args) {
                        runCatching {
                            val json = org.json.JSONObject(t.args)
                            val title = json.optString("title", "自定义弹窗")
                            val cardTitle = json.optString("card_title", title)
                            val cardDescription = json.optString("card_description", "点击查看详情")
                            Triple(title, cardTitle, cardDescription)
                        }.getOrDefault(Triple("自定义弹窗", "自定义弹窗", "点击查看详情"))
                    }
                    val (_, cardTitle, cardDescription) = popupCardInfo
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                runCatching {
                                    val json = org.json.JSONObject(t.args)
                                    val title = json.optString("title", "自定义弹窗")
                                    val html = json.optString("html", "")
                                    if (html.isBlank()) return@runCatching
                                    val cardTitle = json.optString("card_title", title)
                                    val cardDescription = json.optString("card_description", "点击查看详情")
                                    val width = if (json.has("width")) json.optInt("width") else null
                                    val height = if (json.has("height")) json.optInt("height") else null
                                    val cancelable = json.optBoolean("cancelable", true)
                                    val timeout = json.optInt("timeout", 120)
                                    val latch = java.util.concurrent.CountDownLatch(1)
                                    val resultRef = java.util.concurrent.atomic.AtomicReference<String?>(null)
                                    VisualCustomPopupQueue.addPopup(
                                        com.ai.assistance.quro.core.tools.VisualCustomPopupData(
                                            id = "popup_${System.currentTimeMillis()}_${(Math.random() * 1000).toInt()}",
                                            title = title,
                                            htmlContent = html,
                                            cardTitle = cardTitle,
                                            cardDescription = cardDescription,
                                            width = width,
                                            height = height,
                                            cancelable = cancelable,
                                            timeout = timeout,
                                            latch = latch,
                                            result = resultRef
                                        )
                                    )
                                }
                            },
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(containerColor = cs.primaryContainer),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Language, null, tint = cs.primary, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(cardTitle, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = cs.onPrimaryContainer, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (cardDescription.isNotBlank()) Text(cardDescription, fontSize = 10.sp, color = cs.onPrimaryContainer.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, "打开弹窗", tint = cs.primary, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
            // 附件：用户与 AI 消息通用，支持一条消息多个文件，图片/视频/文档发出来可直接预览
            if (msg.attachments.isNotEmpty()) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    msg.attachments.forEach { att ->
                        AttachmentBubble(
                            att = att,
                            scaled = scaled,
                            onActivate = { onAttachmentActivate(att) },
                            onDownload = { onAttachmentDownload(att) },
                        )
                    }
                }
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
                // 自由复制修复：此前父 Box 挂了 combinedClickable(onClick=copyPlain)，
                // 其长按手势会吞掉 SelectionContainer 的文本选区手势，且单击即整段复制，
                // 导致「长按自由选词复制」失效。移除此点击处理，让 SelectionContainer 接管选区；
                // 整段复制仍由下方「复制」操作按钮提供。
                Box(bubbleModifier) {
                    val blocks = remember(cleanText) { parseBlocks(cleanText) }
                    SelectionContainer {
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
                                is MsgBlock.Code -> CodeBlock(lang = blk.lang, code = blk.code, scaled = scaled, onSend = onSend)
                                is MsgBlock.Mermaid -> QuroChatCardView(
                                    QuroChatCard.MermaidCard(
                                        id = "mmd_" + blk.source.hashCode().toString(36).replace("-", "m"),
                                        title = "流程图 / 可视化",
                                        source = blk.source,
                                        theme = "",
                                    ),
                                    onCommand = onCommand
                                )
                                is MsgBlock.DynamicUi -> DynamicUiBlock(
                                    source = blk.source,
                                    onCommand = onCommand,
                                    onOpenLink = onOpenLink,
                                )
                            }
                        }
                        // 消息自带富组件（一等公民）+ AI 文本内联下发的组件 JSON，合体进气泡。
                        // 自由排版：富卡片用 FlowRow 流式排布——紧凑型卡片（按钮/开关/标签等）并排成行、自动换行；
                        // 宽型卡片（图表/表格/流程图等）独占一整行，避免被挤窄。
                        val bubbleCards = remember(msg.cards, inlineCards) { msg.cards + inlineCards }
                        if (bubbleCards.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            FlowRow(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                maxItemsInEachRow = Int.MAX_VALUE,
                            ) {
                                bubbleCards.forEach { card ->
                                    QuroChatCardView(
                                        card,
                                        onCommand,
                                        modifier = if (isCompactQuroCard(card)) Modifier.wrapContentWidth() else Modifier.fillMaxWidth(),
                                    )
                                }
                            }
                        }
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
            // 🔧 Bug修复「简短回复（快捷回复卡片）不显示」：此前卡片只在「有正文气泡」分支内
            //   渲染（见上方 bubbleCards）。AI 只下发卡片、没有正文时（如 quickreply 快捷回复建议、
            //   attachCardToLastAssistant 兜底建的 content="" 纯卡片消息），整条消息什么都不渲染。
            //   这里在无正文但带卡片时独立渲染卡片（有正文时仍走气泡内渲染，不重复）。
            if (!msg.mine && msg.text.isNullOrBlank() && msg.cards.isNotEmpty()) {
                // 自由排版：无正文的纯卡片消息同样用 FlowRow 流式排布（紧凑卡片并排、宽型独占一行）
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    maxItemsInEachRow = Int.MAX_VALUE,
                ) {
                    msg.cards.forEach { card ->
                        QuroChatCardView(
                            card,
                            onCommand,
                            modifier = if (isCompactQuroCard(card)) Modifier.wrapContentWidth() else Modifier.fillMaxWidth(),
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                    BubbleActionButton("删除", Muted) { onDelete(msg.uids) }
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
    onActivate: () -> Unit,
    onDownload: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(cs.surfaceVariant)
            .border(1.dp, Line, RoundedCornerShape(12.dp))
            .padding(10.dp, 8.dp)
    ) {
        // 预览区（按类型）：点击触发 onActivate（图片全屏 / 视频播放 / 文档打开）
        when (att.type) {
            "image" -> AttachmentImagePreview(att, onActivate)
            "video" -> AttachmentVideoPreview(att, onActivate)
            else -> AttachmentFilePreview(att, onActivate)
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            LucideIcon(fileTypeIcon(att), null, Modifier.size(16.dp), tint = cs.primary)
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f)) {
                Text(att.name, fontSize = scaled(13), color = cs.onSurface, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(att.meta, fontSize = scaled(11), color = Muted)
            }
            IconButton(onClick = onDownload, Modifier.size(32.dp)) {
                LucideIcon("download", "下载", Modifier.size(16.dp), tint = cs.primary)
            }
        }
    }
}

/** 根据附件类型 / 扩展名返回 Lucide 图标名。 */
private fun fileTypeIcon(att: Attachment): String {
    if (att.type == "image") return "image"
    if (att.type == "video") return "video"
    val ext = att.name.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "pdf" -> "file_text"
        "doc", "docx" -> "file_text"
        "xls", "xlsx" -> "table"
        "ppt", "pptx" -> "presentation"
        "txt", "md", "log", "csv", "json", "xml", "html", "htm" -> "file_text"
        "zip", "rar", "7z" -> "archive"
        "mp3", "wav", "flac", "aac", "ogg" -> "music"
        "mp4", "avi", "mov", "mkv" -> "video"
        else -> "paperclip"
    }
}

/** 图片：缩略图（点击查看大图）。 */
@Composable
private fun AttachmentImagePreview(att: Attachment, onActivate: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val bmp = remember(att.path) {
        runCatching { att.path?.let { BitmapFactory.decodeFile(it)?.asImageBitmap() } }.getOrNull()
    }
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 220.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(cs.surface)
            .clickable { onActivate() },
        contentAlignment = Alignment.Center
    ) {
        if (bmp != null) {
            Image(
                bitmap = bmp, contentDescription = att.name, contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp),
            )
            Box(
                Modifier.align(Alignment.TopEnd).padding(6.dp)
                    .background(Color.Black.copy(alpha = 0.4f), CircleShape).padding(4.dp)
            ) {
                LucideIcon("maximize", "查看大图", Modifier.size(14.dp), tint = Color.White)
            }
        } else {
            Text("图片预览不可用", color = Muted, fontSize = 12.sp)
        }
    }
}

/** 视频：首帧 + 播放按钮（点击打开应用内播放器）。 */
@Composable
private fun AttachmentVideoPreview(att: Attachment, onActivate: () -> Unit) {
    val frame = remember(att.path) {
        runCatching {
            att.path?.let { p ->
                val retr = MediaMetadataRetriever()
                retr.setDataSource(p)
                val b = retr.frameAtTime ?: retr.getFrameAtTime(0)
                retr.release()
                b?.asImageBitmap()
            }
        }.getOrNull()
    }
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 220.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color.Black)
            .clickable { onActivate() },
        contentAlignment = Alignment.Center
    ) {
        if (frame != null) {
            Image(
                bitmap = frame, contentDescription = att.name, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp),
            )
        }
        Box(
            Modifier.size(48.dp).background(Color.Black.copy(alpha = 0.45f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.PlayArrow, "播放", Modifier.size(28.dp), tint = Color.White)
        }
    }
}

/** 文档 / 文件：类型图标 + 内容预览卡片 + 打开提示（点击打开应用内查看器）。 */
@Composable
private fun AttachmentFilePreview(att: Attachment, onActivate: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    val ext = att.name.substringAfterLast('.', "").lowercase()
    val isPreviewable = ext in setOf(
        "docx", "xlsx", "pptx", "pdf",
        "txt", "md", "markdown", "json", "csv", "xml",
        "html", "htm", "log", "kt", "kts", "py", "js", "ts", "css", "java",
    )
    val isOfficeDoc = ext in setOf("docx", "xlsx", "pptx", "pdf")
    // 尝试读取文件前几行作为预览
    val previewText = remember(att.path) {
        if (!isPreviewable || att.path == null) return@remember null
        val f = File(att.path)
        if (!f.exists() || f.length() > 2L * 1024 * 1024) return@remember null
        runCatching {
            if (isOfficeDoc) {
                // Office 文档：提取纯文本前 120 字符作为预览
                val text = extractOfficeText(f)
                text.take(120).replace("\n", " ").ifBlank { null }
            } else {
                // 文本文件：直接读取前 120 字符
                f.bufferedReader(Charsets.UTF_8).use { reader ->
                    val buf = CharArray(256)
                    val n = reader.read(buf, 0, 256)
                    if (n > 0) String(buf, 0, n) else ""
                }.take(120)
            }
        }.getOrNull()
    }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(cs.surface)
            .border(1.dp, Line, RoundedCornerShape(10.dp))
            .clickable { onActivate() }
            .padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).background(cs.primaryContainer, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                LucideIcon(fileTypeIcon(att), null, Modifier.size(22.dp), tint = cs.primary)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    att.name,
                    fontSize = 13.sp, color = cs.onSurface,
                    fontWeight = FontWeight.SemiBold, maxLines = 1,
                )
                Text(
                    if (isPreviewable) "点击在应用内预览排版" else "点击打开文件",
                    fontSize = 11.sp, color = cs.primary,
                )
            }
            // 类型徽标
            if (isOfficeDoc) {
                Text(
                    ext.uppercase(),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = cs.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(cs.primaryContainer)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        // 文档内容预览卡（仅在有预览文本时显示）
        if (!previewText.isNullOrBlank()) {
            Spacer(Modifier.height(8.dp))
            Column(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(cs.surfaceVariant.copy(alpha = 0.6f))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                Text(
                    "内容预览",
                    fontSize = 10.sp,
                    color = Muted,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    previewText + if (previewText.length >= 120) "…" else "",
                    fontSize = 11.sp,
                    color = cs.onSurface.copy(alpha = 0.8f),
                    lineHeight = 15.sp,
                    maxLines = 4,
                )
            }
        }
        // 对话框内直接渲染文档排版：可展开内联预览（复用 QuroDocumentViewer 进程内渲染引擎，
        // docx/xlsx/pptx/pdf 走 mammoth/SheetJS/pdf.js，真正实现「对话框直接渲染文档排版」）。
        if (isOfficeDoc && att.path != null) {
            val docFile = remember(att.path) { File(att.path!!) }
            var inlineExpanded by remember(att.path) { mutableStateOf(false) }
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { inlineExpanded = !inlineExpanded }) {
                    Text(
                        if (inlineExpanded) "收起排版预览" else "对话框内预览排版",
                        color = cs.primary,
                        fontSize = 12.sp,
                    )
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onActivate) {
                    Text("全屏预览", color = cs.primary.copy(alpha = 0.8f), fontSize = 12.sp)
                }
            }
            if (inlineExpanded && docFile.exists()) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 260.dp, max = 420.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(cs.surface)
                        .border(1.dp, Line, RoundedCornerShape(8.dp))
                ) {
                    QuroDocumentViewer(
                        file = docFile,
                        onClose = { inlineExpanded = false },
                        onExternal = {
                            openFileWithSystemViewer(ctx, att)
                            inlineExpanded = false
                        },
                        readOnly = true,
                    )
                }
            } else if (inlineExpanded) {
                Text(
                    "文件不存在：${att.path}",
                    color = cs.error,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(8.dp),
                )
            }
        }
    }
}

/** 全屏可缩放图片查看器（双击在 1x / 2.5x 间切换，支持双指捏合与拖动）。 */
@Composable
private fun FullScreenImageViewer(path: String, name: String, onClose: () -> Unit) {
    val bmp = remember(path) { runCatching { BitmapFactory.decodeFile(path)?.asImageBitmap() }.getOrNull() }
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val state = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 5f)
        offset += pan
    }
    Box(Modifier.fillMaxSize().zIndex(130f).background(Color.Black)) {
        IconButton(onClick = onClose, Modifier.align(Alignment.TopEnd).padding(8.dp).size(40.dp)) {
            LucideIcon("x", "关闭", Modifier.size(22.dp), tint = Color.White)
        }
        if (bmp != null) {
            Image(
                bitmap = bmp,
                contentDescription = name,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y)
                    .transformable(state)
                    .clickable { scale = if (scale > 1f) 1f else 2.5f; offset = Offset.Zero },
            )
        } else {
            Text("图片加载失败", color = Color.White, modifier = Modifier.align(Alignment.Center))
        }
    }
}

/** 用系统查看器打开文档 / 视频（FileProvider 共享，无需写权限）。 */
private fun openFileWithSystemViewer(ctx: Context, att: Attachment) {
    val file = att.path?.let { File(it) }?.takeIf { it.exists() } ?: run {
        Toast.makeText(ctx, "源文件不存在", Toast.LENGTH_SHORT).show()
        return
    }
    val mime = when (att.type) {
        "image" -> "image/*"
        "video" -> "video/*"
        else -> fileMimeByExt(file.extension)
    }
    runCatching {
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(Intent.createChooser(intent, "打开文件"))
    }.onFailure {
        Toast.makeText(ctx, "无法打开：${it.message}", Toast.LENGTH_SHORT).show()
    }
}

private fun fileMimeByExt(ext: String): String = when (ext.lowercase()) {
    "pdf" -> "application/pdf"
    "txt", "log", "md", "json", "csv", "xml", "html", "htm" -> "text/plain"
    "doc" -> "application/msword"
    "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    "xls" -> "application/vnd.ms-excel"
    "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    "ppt" -> "application/vnd.ms-powerpoint"
    "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
    "zip" -> "application/zip"
    "apk" -> "application/vnd.android.package-archive"
    else -> "*/*"
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
            // 🔧 执行中：存在尚未回填结果的工具调用 → 标题栏显示脉冲点 + 执行中（多轮循环期间持续可见）。
            if (tools.any { it.result.isNullOrBlank() }) {
                val pulse by rememberInfiniteTransition().animateFloat(0.35f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse))
                Box(Modifier.size(8.dp).clip(CircleShape).background(cs.primary.copy(alpha = pulse)))
                Spacer(Modifier.width(6.dp))
                Text("执行中…", fontSize = 10.sp, color = cs.primary, fontWeight = FontWeight.Medium)
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
    // 执行中标记：工具结果尚未回填（正卡在 engine.execute 慢任务）→ 头部与边框显示「进行中」强调态。
    val pending = t.result.isNullOrBlank()
    var cardExpanded by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (pending) cs.primary.copy(alpha = 0.06f) else cs.surface.copy(alpha = 0.5f))
            .border(0.5.dp, if (pending) cs.primary.copy(alpha = 0.5f) else cs.outlineVariant.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
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
            } else {
                // 🔧 执行中指示：结果尚未回填 → 脉冲点 + 「执行中…」，让慢任务在对话框里有明确「进行中」展示。
                val pulse by rememberInfiniteTransition().animateFloat(0.35f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse))
                Box(Modifier.size(9.dp).clip(CircleShape).background(cs.primary.copy(alpha = pulse)))
                Spacer(Modifier.width(4.dp))
                Text("执行中…", fontSize = 9.sp, color = cs.primary, fontWeight = FontWeight.Medium)
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

/** 渲染卡片数据结构 */
private data class RenderCard(
    val type: String,
    val title: String,
    val content: String,
    val path: String? = null,
    val language: String? = null
)

/** 解析工具结果中的渲染卡片标签 */
private fun parseRenderCards(result: String): List<RenderCard> {
    val cards = mutableListOf<RenderCard>()
    val regex = Regex("""\[渲染卡片\]\s*\n类型：(.+?)\s*\n标题：(.+?)\s*\n(路径：(.+?)\s*\n)?(语言：(.+?)\s*\n)?内容：\s*\n([\s\S]*?)\[/渲染卡片\]""")
    regex.findAll(result).forEach { match ->
        val type = match.groupValues[1].trim()
        val title = match.groupValues[2].trim()
        val path = match.groupValues[4].trim().ifBlank { null }
        val language = match.groupValues[6].trim().ifBlank { null }
        val content = match.groupValues[7].trim()
        cards.add(RenderCard(type, title, content, path, language))
    }
    return cards
}

/** 格式化输出结果 */
@Composable
private fun FormattedResultContent(result: String, scaled: (Int) -> androidx.compose.ui.unit.TextUnit) {
    val cs = MaterialTheme.colorScheme
    
    // 首先检查是否有渲染卡片
    val renderCards = remember(result) { parseRenderCards(result) }
    if (renderCards.isNotEmpty()) {
        // 渲染卡片模式
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 400.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(cs.surfaceContainerLowest)
                .verticalScroll(rememberScrollState())
                .padding(8.dp)
        ) {
            renderCards.forEach { card ->
                RenderCardView(card, scaled)
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
        return
    }
    
    // 原有的格式化逻辑
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

/** 渲染单个渲染卡片 */
@Composable
private fun RenderCardView(card: RenderCard, scaled: (Int) -> androidx.compose.ui.unit.TextUnit) {
    val cs = MaterialTheme.colorScheme
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = cs.surfaceContainerHigh
        ),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            // 卡片标题
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                // 类型图标
                val icon = when (card.type) {
                    "HTML" -> Icons.Filled.Code
                    "Markdown" -> Icons.Filled.Description
                    "代码" -> Icons.Filled.Code
                    "图片" -> Icons.Filled.Description // 使用描述图标作为图片占位
                    "PDF" -> Icons.Filled.Description // 使用描述图标作为PDF占位
                    else -> Icons.Filled.Description
                }
                Icon(icon, null, tint = cs.primary, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(card.title, fontWeight = FontWeight.Bold, fontSize = scaled(13))
                Spacer(modifier = Modifier.weight(1f))
                Text(card.type, fontSize = 9.sp, color = cs.onSurfaceVariant)
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // 根据类型渲染内容
            when (card.type) {
                "HTML" -> {
                    // HTML 渲染
                    AndroidView(
                        factory = { context ->
                            android.webkit.WebView(context).apply {
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                loadDataWithBaseURL(null, card.content, "text/html", "UTF-8", null)
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                            .clip(RoundedCornerShape(4.dp))
                    )
                }
                "Markdown" -> {
                    // Markdown 渲染（简单实现：保留格式）
                    Text(
                        text = card.content,
                        fontSize = scaled(11),
                        color = cs.onSurfaceVariant,
                        lineHeight = scaled(16),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                "代码" -> {
                    // 代码渲染
                    Surface(
                        color = cs.surfaceContainerLowest,
                        shape = RoundedCornerShape(4.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = card.content,
                            fontSize = scaled(10),
                            fontFamily = FontFamily.Monospace,
                            color = cs.onSurface,
                            lineHeight = scaled(14),
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }
                "图片" -> {
                    // 图片渲染（如果路径有效）
                    card.path?.let { path ->
                        AndroidView(
                            factory = { context ->
                                android.widget.ImageView(context).apply {
                                    setImageURI(android.net.Uri.parse(path))
                                    scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(150.dp)
                                .clip(RoundedCornerShape(4.dp))
                        )
                    }
                }
                "PDF" -> {
                    // PDF 说明
                    Text(
                        text = "PDF 文件已保存到工作区，可在文件管理器中打开查看",
                        fontSize = scaled(11),
                        color = cs.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                else -> {
                    // 文本渲染
                    Text(
                        text = card.content,
                        fontSize = scaled(11),
                        color = cs.onSurfaceVariant,
                        lineHeight = scaled(16),
                        modifier = Modifier.fillMaxWidth()
                    )
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
 * AI 尚未产出首条内容时的内容区 loading 指示（「等等」动态小组件）。
 * 仅渲染三个跳动圆点，独立于头像/人格名字（头像名字由 MessageRow 的 persona 渲染，不在此处）。
 * 使用 rememberInfiniteTransition 做错相位弹跳，纯 Compose 动画、无 emoji、无图片。
 * 注：此前静态的「等等」文字已移除，保留动态跳动圆点；AI 消息上的「删除」按钮是独立的
 * 气泡操作项（BubbleActionButton），不在本组件内，不受影响。
 */
@Composable
private fun WaitingDots() {
    val cs = MaterialTheme.colorScheme
    val transition = rememberInfiniteTransition()
    // 单一进度 0→1 无限循环；三个圆点按 1/3 相位错开，形成依次跳动效果。
    // 用 tween + 相位偏移，避免 keyframes / StartOffset 在此 Compose 版本的 API 限制。
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 6.dp),
    ) {
        Spacer(Modifier.width(8.dp))
        repeat(3) { i ->
            val t = (progress + i * (1f / 3f)) % 1f
            val y = -(Math.abs(Math.sin(t * Math.PI)) * 5f)
            Box(
                Modifier
                    .size(7.dp)
                    .offset(y = y.dp)
                    .clip(CircleShape)
                    .background(cs.primary),
            )
            if (i < 2) Spacer(Modifier.width(5.dp))
        }
    }
}

/**
 * 「AI 正在执行工具…」工作指示：复用 [WaitingDots] 的跳动圆点动画 + 文案，
 * 让多轮工具循环期间对话框明确显示「进行中」，而非静止空白或重复文本。
 */
@Composable
private fun WorkingIndicator() {
    val cs = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 4.dp),
    ) {
        Spacer(Modifier.width(8.dp))
        WaitingDots()
        Spacer(Modifier.width(6.dp))
        Text(
            "AI 正在执行工具…",
            fontSize = 12.sp, color = cs.primary, fontWeight = FontWeight.Medium,
        )
    }
}

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
    onOpenAciSelector: () -> Unit = {},
    onOpenEditor: () -> Unit = {},
    onSelectVisualPopup: () -> Unit = {},
    onSelectVisualQuestion: () -> Unit = {},
    pendingVisualPopup: Boolean = false,
    pendingVisualQuestion: Boolean = false,
    currentWorkspace: String? = null,
    onOpenWorkspaceSelector: () -> Unit = {},
    currentAciName: String? = null,
    enabledSkillsCount: Int = 0,
    scaled: (Int) -> androidx.compose.ui.unit.TextUnit
) {
    val cs = MaterialTheme.colorScheme

    Column(
        Modifier
            .fillMaxWidth()
            .background(cs.background)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
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
        // ═══ 上下文标识栏：显示当前工作区 / ACI / 技能，让用户一眼看到 AI 能读到什么 ═══
        val hasCtx = currentWorkspace != null || currentAciName != null || enabledSkillsCount > 0
        if (hasCtx) {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (currentWorkspace != null) {
                    val wsLabel = currentWorkspace.substringAfterLast('/')
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(cs.primaryContainer.copy(alpha = 0.5f))
                            .clickable { onOpenWorkspaceSelector() }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        LucideIcon("folder", null, Modifier.size(12.dp), tint = cs.primary)
                        Spacer(Modifier.width(4.dp))
                        Text("📂 $wsLabel", fontSize = scaled(11), color = cs.onPrimaryContainer, maxLines = 1)
                    }
                }
                if (currentAciName != null) {
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(cs.secondaryContainer.copy(alpha = 0.5f))
                            .clickable { onOpenAciSelector() }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        LucideIcon("link", null, Modifier.size(12.dp), tint = cs.secondary)
                        Spacer(Modifier.width(4.dp))
                        Text("🔗 $currentAciName", fontSize = scaled(11), color = cs.onSecondaryContainer, maxLines = 1)
                    }
                }
                if (enabledSkillsCount > 0) {
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(cs.tertiaryContainer.copy(alpha = 0.5f))
                            .clickable { onOpenSkills() }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        LucideIcon("sparkles", null, Modifier.size(12.dp), tint = cs.tertiary)
                        Spacer(Modifier.width(4.dp))
                        Text("🧩 ${enabledSkillsCount}个技能", fontSize = scaled(11), color = cs.onTertiaryContainer)
                    }
                }
            }
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
            val ctx = LocalContext.current
            IconButton(onClick = onAttach, Modifier.size(44.dp).padding(2.dp)) {
                Icon(Icons.Filled.Add, "上传文件", Modifier.size(22.dp), tint = cs.onSurfaceVariant)
            }
            // 工具菜单按钮：合并技能选择、ACI 应用选择、编辑器
            var showToolMenu by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { showToolMenu = true }, Modifier.size(44.dp).padding(2.dp)) {
                    Icon(Icons.Filled.Build, "工具", Modifier.size(22.dp), tint = cs.onSurfaceVariant)
                }
                DropdownMenu(
                    expanded = showToolMenu,
                    onDismissRequest = { showToolMenu = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("选择技能") },
                        onClick = {
                            showToolMenu = false
                            onOpenSkills()
                        },
                        leadingIcon = {
                            LucideIcon("sparkles", null, Modifier.size(18.dp), tint = cs.primary)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("选择 ACI 应用") },
                        onClick = {
                            showToolMenu = false
                            onOpenAciSelector()
                        },
                        leadingIcon = {
                            Icon(Icons.Filled.Public, null, Modifier.size(18.dp), tint = cs.primary)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("编辑文本") },
                        onClick = {
                            showToolMenu = false
                            onOpenEditor()
                        },
                        leadingIcon = {
                            Icon(Icons.Filled.Edit, null, Modifier.size(18.dp), tint = cs.primary)
                        },
                    )
                    HorizontalDivider()
                    // 可视化交互工具（支持切换选择/取消选择）
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.DesktopWindows, null, Modifier.size(18.dp), tint = cs.primary)
                                Spacer(Modifier.width(8.dp))
                                Text("可视化弹窗")
                                if (pendingVisualPopup) {
                                    Spacer(Modifier.width(8.dp))
                                    Icon(Icons.Filled.Check, "已选择", Modifier.size(16.dp), tint = cs.primary)
                                }
                            }
                        },
                        onClick = {
                            showToolMenu = false
                            onSelectVisualPopup()
                        },
                    )
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.TouchApp, null, Modifier.size(18.dp), tint = cs.primary)
                                Spacer(Modifier.width(8.dp))
                                Text("可视化询问")
                                if (pendingVisualQuestion) {
                                    Spacer(Modifier.width(8.dp))
                                    Icon(Icons.Filled.Check, "已选择", Modifier.size(16.dp), tint = cs.primary)
                                }
                            }
                        },
                        onClick = {
                            showToolMenu = false
                            onSelectVisualQuestion()
                        },
                    )
                }
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
        // 深度思考 + 权限模式控制条：移到底部（输入框下方），符合「权限模式在下面」的布局要求
        ChatPermissionModeBar(
            deepThink = deepThink,
            onToggleThink = onToggleThink,
            autoSaveMemory = autoSaveMemory,
            onToggleAutoSave = onToggleAutoSave,
            autoRead = autoRead,
            onToggleAutoRead = onToggleAutoRead,
            visionEnabled = visionEnabled,
            onToggleVision = onToggleVision,
            currentWorkspace = currentWorkspace,
            onOpenWorkspaceSelector = onOpenWorkspaceSelector,
        )
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
    onOpenLspose: () -> Unit,
    onOpenUsbDebug: () -> Unit,
    onOpenDefaultApp: () -> Unit,
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
    onOpenCleanup: () -> Unit,
    onOpenFileManager: () -> Unit,
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
                        onOpenModelConfig, onOpenFeatureModelConfig, onOpenPermission, onOpenLspose, onOpenUsbDebug, onOpenDefaultApp, onOpenCms, onOpenToolbox, onOpenKnowledge, onOpenTerminal, onOpenPlugins, onOpenSkills,
                        onManagePersona, onOpenVoiceService,
                        onClearChat, settingsVoiceBallEnabled, onSettingsToggleVoiceBall,
                    settingsAiReplyNotify, onSettingsToggleAiReplyNotify,
                        onOpenAbout, onOpenAci, onOpenMcp, onOpenSystemStatus, onOpenComponentGallery, onOpenAppearance, onExport, onClear, onOpenCleanup, onOpenFileManager, scaled
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
    onOpenLspose: () -> Unit,
    onOpenUsbDebug: () -> Unit,
    onOpenDefaultApp: () -> Unit,
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
    onOpenCleanup: () -> Unit,
    onOpenFileManager: () -> Unit,
    scaled: (Int) -> androidx.compose.ui.unit.TextUnit
) {
    val cs = MaterialTheme.colorScheme
    var usePty by remember { mutableStateOf(QuroTerminalPrefs.usePty) }
    var requireDestructiveConfirm by remember { mutableStateOf(QuroTerminalPrefs.requireDestructiveConfirm) }
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
            SetRowClickable(Icons.Filled.Extension, "LSPosed 模块", "钩子注入 / 作用域管理", "", onOpenLspose, scaled)
            HorizontalDivider(color = Line, thickness = 1.dp, modifier = Modifier.padding(horizontal = 12.dp))
            SetRowClickable(Icons.Filled.Usb, "USB / 无线调试", "ADB：被电脑控制 · 本机客户端 · TCP 监听", "", onOpenUsbDebug, scaled)
            HorizontalDivider(color = Line, thickness = 1.dp, modifier = Modifier.padding(horizontal = 12.dp))
            SetRowClickable(Icons.Filled.Apps, "默认应用", "桌面启动器 / 浏览器 / 相册 / 视频 / 邮箱 / 文档 / 短信 / 拨号", "", onOpenDefaultApp, scaled)
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
            HorizontalDivider(color = Line, thickness = 1.dp, modifier = Modifier.padding(horizontal = 12.dp))
            SetRow(
                Icons.Filled.Code, "真实 PTY 终端（实验）",
                "伪终端：vim/top/REPL 可交互、SIGINT 正常；出问题请关闭回退管道", usePty,
                onToggle = { usePty = !usePty; QuroTerminalPrefs.usePty = usePty },
            )
            HorizontalDivider(color = Line, thickness = 1.dp, modifier = Modifier.padding(horizontal = 12.dp))
            SetRow(
                Icons.Filled.Warning, "破坏性命令二次确认（授权）",
                "开启后 rm -rf / dd / mkfs 等危险命令需再次发送或 confirm 才执行", requireDestructiveConfirm,
                onToggle = {
                    requireDestructiveConfirm = !requireDestructiveConfirm
                    QuroTerminalPrefs.requireDestructiveConfirm = requireDestructiveConfirm
                },
            )
        }
        GroupCaption("通知")
        SetGroup {
            SetRow(Icons.Filled.Notifications, "AI 回复通知", "离开软件时系统弹窗通知 / 桌面卡片", settingsAiReplyNotify, onSettingsToggleAiReplyNotify, scaled)
        }
        GroupCaption("数据")
        SetGroup {
            SetRowClickable(Icons.Filled.Download, "导出对话", "", "导出为文本", onExport, scaled)
            HorizontalDivider(color = Line, thickness = 1.dp, modifier = Modifier.padding(horizontal = 12.dp))
            SetRowClickable(Icons.Filled.DeleteSweep, "清理存储", "分类清理日志、缓存、AI产物等", "", onOpenCleanup, scaled)
            SetRowClickable(Icons.Filled.FolderOpen, "文件管理", "浏览沙箱目录 · 在系统文件管理器中打开", "", onOpenFileManager, scaled)
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
    data class Mermaid(val source: String) : MsgBlock()
    /** 动态 UI：```quro-ui 围栏，渲染为原生可交互控件（非 WebView）。 */
    data class DynamicUi(val source: String) : MsgBlock()
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
    // v150：链接回答 → 气泡内预览卡（原生安卓点击查看体验）。
    // 仅该域名走预览卡；其余外链保留既有内联 ClickableText 行为。
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
                    title = "链接回答",
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

    // Agent 模式：检测 <!-- FILE: filename --> 标记，提取多文件
    val fileMarkerRegex = Regex("<!--\\s*FILE:\\s*(\\S+)\\s*-->")
    if (fileMarkerRegex.containsMatchIn(text)) {
        val blocks = mutableListOf<MsgBlock>()
        val parts = text.split(fileMarkerRegex)
        var i = 1
        while (i < parts.size) {
            val filename = parts[i]
            val content = if (i + 1 < parts.size) parts[i + 1].trim() else ""
            if (content.isNotBlank()) {
                // 根据文件扩展名确定语言
                val lang = when {
                    filename.endsWith(".html", true) || filename.endsWith(".htm", true) -> "html"
                    filename.endsWith(".css", true) -> "css"
                    filename.endsWith(".js", true) || filename.endsWith(".ts", true) -> "javascript"
                    filename.endsWith(".json", true) -> "json"
                    filename.endsWith(".py", true) -> "python"
                    filename.endsWith(".xml", true) -> "xml"
                    filename.endsWith(".java", true) -> "java"
                    filename.endsWith(".kt", true) -> "kotlin"
                    filename.endsWith(".c", true) || filename.endsWith(".cpp", true) -> "cpp"
                    filename.endsWith(".sh", true) || filename.endsWith(".bash", true) -> "shell"
                    else -> "text"
                }
                blocks.add(MsgBlock.Code(lang, content))
            }
            i += 2
        }
        if (blocks.isNotEmpty()) return blocks
    }
    val blocks = mutableListOf<MsgBlock>()
    var last = 0
    val fences = RE_FENCE.findAll(text).toList()
    for (m in fences) {
        if (m.range.first > last) blocks.addAll(parseTail(text.substring(last, m.range.first)))
        val lang = m.groupValues[1].trim()
        blocks.add(
            if (lang.equals("mermaid", true) || lang.equals("mmd", true)) {
                // 可视化编程：原始 mermaid / mmd 围栏直接渲染成离线矢量图（AI 或用户均可作者）
                MsgBlock.Mermaid(m.groupValues[2].removeSuffix("\n"))
            } else if (isDynamicUiLang(lang)) {
                // 动态 UI：AI 写的 UI DSL 渲染为原生可交互控件（可回传表单值给模型）
                MsgBlock.DynamicUi(m.groupValues[2].removeSuffix("\n"))
            } else {
                MsgBlock.Code(lang, m.groupValues[2].removeSuffix("\n"))
            }
        )
        last = m.range.last + 1
    }
    if (last < text.length) blocks.addAll(parseTail(text.substring(last)))
    return if (blocks.isEmpty()) listOf(MsgBlock.Text(text)) else blocks
}

/**
 * 判断是否动态 UI 围栏语言。
 *
 * 兼容三种写法：`quro-ui`（现行）、`quro_ui`（部分模型会把连字符写成下划线）、
 * `zorv-ui`（历史前缀）。与 [com.ai.assistance.quro.core.ui.dynamicui.QuroUiDslParser]
 * 的围栏识别保持一致，否则解析器认得、渲染层却不认，会出现「明明写了却当普通代码块显示」。
 */
private fun isDynamicUiLang(lang: String): Boolean {
    val l = lang.trim().lowercase()
    return l == "quro-ui" || l == "quro_ui" || l == "zorv-ui"
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
            if (lang.equals("mermaid", true) || lang.equals("mmd", true)) {
                out.add(MsgBlock.Mermaid(after))
            } else if (isDynamicUiLang(lang)) {
                // 流式输出中 quro-ui 围栏还没闭合时，也实时渲染（边写边出界面）
                out.add(MsgBlock.DynamicUi(after))
            } else {
                out.add(MsgBlock.Code(lang.ifBlank { "text" }, after))
            }
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

/**
 * 动态 UI 块：把 AI 输出的 ```quro-ui DSL 渲染成**原生**可交互控件。
 *
 * 之所以用原生渲染而非内嵌 HTML/WebView：
 *  - 控件状态由 Compose 托管，用户填写的表单值能原样回传给模型继续处理；
 *  - 自动继承应用主题（深浅色 / 字体缩放），AI 无需关心配色；
 *  - 没有 JS 执行面，安全边界更清晰。
 *
 * 解析失败时**不留空白**：回退展示失败原因与原始内容，
 * 这样用户看得到东西、模型下一轮也能据此修正 DSL。
 */
@Composable
private fun DynamicUiBlock(
    source: String,
    onCommand: (String) -> Unit,
    onOpenLink: (String) -> Unit,
) {
    // 只在 source 变化时重新解析：流式输出期间每来一个字都会重组，
    // 若把解析写在重组体内会导致每帧重解析一次（白白烧 CPU）。
    val parsed = remember(source) { QuroUiDslParser.parseBlock(source) }

    when (parsed) {
        is QuroUiParseResult.Success -> Surface(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        ) {
            Column(Modifier.padding(12.dp)) {
                QuroUiRenderer(
                    root = parsed.root,
                    onAction = { action, values ->
                        handleDynamicUiAction(action, values, onCommand, onOpenLink)
                    },
                )
            }
        }

        is QuroUiParseResult.Failure -> Surface(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f),
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(
                    text = "⚠️ 动态 UI 解析失败：${parsed.reason}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = parsed.rawJson.take(600),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 把动态 UI 的交互动作翻译成对话侧的行为。
 *
 * 绝大多数动作统一走 [onCommand]（即作为一条用户消息发回给模型），
 * 因为「接下来该做什么」的判断权在模型手里，客户端只负责把用户填了什么如实送达。
 */
private fun handleDynamicUiAction(
    action: QuroUiAction,
    values: Map<String, String>,
    onCommand: (String) -> Unit,
    onOpenLink: (String) -> Unit,
) {
    when (action) {
        is QuroCallbackAction -> {
            val merged = LinkedHashMap<String, String>(values).apply { putAll(action.data) }
            val body = if (merged.isNotEmpty()) {
                merged.entries.joinToString("\n") { "${it.key}: ${it.value}" }
            } else {
                action.event
            }
            onCommand(if (action.event.isNotBlank()) "【${action.event}】\n$body" else body)
        }

        is QuroToolCallAction -> {
            val args = LinkedHashMap(action.arguments).apply { putAll(values) }
            val argText = if (args.isEmpty()) "" else "，参数：" +
                args.entries.joinToString("，") { "${it.key}=${it.value}" }
            onCommand("请调用工具 ${action.tool}$argText")
        }

        is QuroSkillAction -> {
            val input = action.input?.takeIf { it.isNotBlank() }
                ?: values.values.joinToString("，")
            onCommand("请执行技能 ${action.skill}：$input")
        }

        is QuroOpenUrlAction -> if (action.url.isNotBlank()) onOpenLink(action.url)

        is QuroCopyAction -> onCommand("请把以下内容复制到剪贴板：${action.text}")

        is QuroOpenAppAction -> onCommand("请打开应用 ${action.packageName}")

        // 纯本地行为（显示/隐藏节点），渲染器内部已切换可见性，无需惊动模型
        is QuroToggleAction -> Unit
    }
}

/** 对话内代码块：可复制、可直接运行（IDE 能力）；HTML 代码块额外支持「代码 / 预览」双 Tab 渲染。 */
@Composable
private fun CodeBlock(lang: String, code: String, scaled: (Int) -> androidx.compose.ui.unit.TextUnit, onSend: (String) -> Unit = {}) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var output by remember { mutableStateOf<String?>(null) }
    var isRunning by remember { mutableStateOf(false) }
    val isHtml = lang.equals("html", ignoreCase = true) ||
        lang.equals("htm", ignoreCase = true) ||
        lang.equals("markup", ignoreCase = true) ||
        lang.equals("svg", ignoreCase = true) ||
        lang.equals("xml", ignoreCase = true) ||
        looksLikeHtml(code) ||
        code.contains("<svg") ||
        code.contains("<svg ")
    val isJs = lang.equals("javascript", ignoreCase = true) ||
        lang.equals("js", ignoreCase = true) ||
        lang.equals("typescript", ignoreCase = true) ||
        lang.equals("ts", ignoreCase = true)
    var showPreview by remember { mutableStateOf(true) }
    var showFullscreen by remember { mutableStateOf(false) }

    // 错误检测辅助函数
    fun isErrorCode(result: String): Boolean {
        return result.startsWith("运行失败") ||
            result.contains("错误") ||
            result.contains("error", ignoreCase = true) ||
            result.contains("Traceback") ||
            result.contains("not found", ignoreCase = true) ||
            result.contains("command not found", ignoreCase = true) ||
            result.contains("Exception") ||
            result.contains("SyntaxError") ||
            result.contains("TypeError") ||
            result.contains("ReferenceError") ||
            result.contains("NameError") ||
            result.contains("ValueError") ||
            result.contains("IndexError") ||
            result.contains("KeyError") ||
            result.contains("ImportError") ||
            result.contains("ModuleNotFoundError") ||
            result.contains("FileNotFoundError") ||
            result.contains("PermissionError") ||
            result.contains("TimeoutError") ||
            result.contains("ConnectionError") ||
            result.contains("RuntimeError") ||
            result.contains("IOError") ||
            result.contains("OSError") ||
            result.contains("AttributeError") ||
            result.contains("ZeroDivisionError") ||
            result.contains("OverflowError") ||
            result.contains("MemoryError")
    }

    // Agent 模式：自动运行 HTML/JS 代码
    LaunchedEffect(code, lang) {
        if ((isHtml || isJs) && code.isNotBlank() && output == null && !isRunning) {
            isRunning = true
            try {
                val r = withContext(Dispatchers.IO) {
                    RunCodeTool().run(
                        ctx,
                        JSONObject().put("code", code).put("lang", lang.ifBlank { "html" }).toString(),
                    )
                }
                output = r
            } catch (e: Exception) {
                output = "运行失败：${e.message}"
            } finally {
                isRunning = false
            }
        }
    }
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
                    val extension = when (lang.lowercase()) {
                        "javascript", "js" -> ".js"
                        "python", "py" -> ".py"
                        "html", "htm" -> ".html"
                        "json" -> ".json"
                        "css" -> ".css"
                        "xml", "svg" -> ".xml"
                        "java" -> ".java"
                        "kotlin", "kt" -> ".kt"
                        "c", "cpp", "c++" -> ".cpp"
                        "shell", "sh", "bash" -> ".sh"
                        else -> ".txt"
                    }
                    val fileName = "quro_code_${System.currentTimeMillis()}$extension"
                    saveCodeToDownloads(ctx, fileName, code)
                }, Modifier.size(28.dp)) {
                    LucideIcon("download", "下载代码", Modifier.size(14.dp), tint = Muted)
                }
                // 自动修复按钮：仅在有错误输出时显示
                if (output != null && isErrorCode(output!!)) {
                    Spacer(Modifier.width(2.dp))
                    IconButton(onClick = {
                        val fixPrompt = buildString {
                            appendLine("代码运行出错，请分析并修复：")
                            appendLine()
                            appendLine("语言：$lang")
                            appendLine("代码：")
                            appendLine("```$lang")
                            appendLine(code)
                            appendLine("```")
                            appendLine()
                            appendLine("错误信息：")
                            appendLine(output)
                            appendLine()
                            appendLine("请分析错误原因，提供修复后的完整代码，并解释修复内容。")
                        }
                        onSend(fixPrompt)
                    }, Modifier.size(28.dp)) {
                        LucideIcon("sparkles", "自动修复", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.error)
                    }
                }
                IconButton(onClick = {
                    if (!isRunning) {
                        scope.launch(Dispatchers.IO) {
                            isRunning = true
                            output = null
                            try {
                                val r = RunCodeTool().run(
                                    ctx,
                                    JSONObject().put("code", code).put("lang", lang.ifBlank { "python" }).toString(),
                                )
                                withContext(Dispatchers.Main) { output = r }
                            } catch (e: Exception) {
                                withContext(Dispatchers.Main) { output = "运行失败：${e.message}" }
                            } finally {
                                withContext(Dispatchers.Main) { isRunning = false }
                            }
                        }
                    }
                }, Modifier.size(28.dp)) {
                    if (isRunning) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = cs.primary)
                    } else {
                        Icon(Icons.Filled.PlayArrow, "运行", Modifier.size(16.dp), tint = cs.primary)
                    }
                }
                }  // end of inner action row
            }

            // 内容区：源码 or 预览（HTML 默认直接渲染，无需手动点「预览」）
            if (showPreview) {
                // 预览模式：WebView 渲染（高度由 webHeight 状态驱动，避免空白）
                // Python/JS 等代码需要 JS 来执行语法高亮，HTML/SVG/JSON 预览也需要 JS
                val needsJs = true // 所有语言都启用JS以支持交互性
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(webHeight)
                        .clip(RoundedCornerShape(bottomStart = 8.dp, bottomEnd = 8.dp))
                        .background(Color.White)
                ) {
                    AndroidView(factory = { context ->
                            WebView(context).apply {
                            settings.javaScriptEnabled = needsJs
                            settings.domStorageEnabled = true
                            settings.databaseEnabled = true
                            settings.allowContentAccess = true
                            settings.cacheMode = WebSettings.LOAD_NO_CACHE
                            settings.useWideViewPort = true
                            settings.loadWithOverviewMode = true
                            settings.setSupportZoom(true)
                            settings.builtInZoomControls = false
                            settings.setSupportMultipleWindows(false)
                            settings.setSupportZoom(true)
                            settings.builtInZoomControls = true
                            settings.displayZoomControls = false
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                                settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                            }
                            setBackgroundColor(android.graphics.Color.TRANSPARENT)
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
                            // 调试：把 JS console 输出打到诊断日志，方便定位 canvas/脚本问题
                            webChromeClient = object : android.webkit.WebChromeClient() {
                                override fun onConsoleMessage(cm: android.webkit.ConsoleMessage?): Boolean {
                                    cm ?: return super.onConsoleMessage(cm)
                                    QuroDiag.log("WebView", "[${cm.sourceId()}:${cm.lineNumber()}] ${cm.message()}")
                                    return true
                                }
                            }
                            // 初始加载（流式时后续由 update 跟随重载，避免预览冻结在首帧）
                            val html = buildPreviewHtml(code, isHtml, lang)
                            tag = html
                            loadDataWithBaseURL("https://localhost/", html, "text/html", "UTF-8", null)
                        }
                    }, modifier = Modifier.fillMaxSize(), update = { wv ->
                        // 流式生成：code 变化时重新加载，预览随写随更新
                        val html = buildPreviewHtml(code, isHtml, lang)
                        if (wv.tag != html) {
                            wv.tag = html
                            wv.loadDataWithBaseURL("https://localhost/", html, "text/html", "UTF-8", null)
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

            output?.let { result ->
                HorizontalDivider(color = Line)
                // 如果结果是HTML，用WebView渲染；否则用纯文本
                val resultIsHtml = result.trimStart().startsWith("<!DOCTYPE html") ||
                    result.trimStart().startsWith("<html") ||
                    result.contains("<html", ignoreCase = true) ||
                    result.contains("<body", ignoreCase = true)
                if (resultIsHtml && !isErrorCode(result)) {
                    // HTML结果用WebView渲染（交互式）
                    var resultHeight by remember { mutableStateOf(200.dp) }
                    Surface(
                        color = cs.surface,
                        modifier = Modifier.fillMaxWidth().padding(8.dp),
                        shape = RoundedCornerShape(6.dp),
                    ) {
                        Column(Modifier.padding(4.dp)) {
                            val context = LocalContext.current
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "✅ 运行结果（交互式）",
                                    fontSize = 11.sp,
                                    color = Muted,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Row {
                                    // 下载按钮
                                    IconButton(onClick = {
                                        val fileName = "quro_html_${System.currentTimeMillis()}.html"
                                        saveCodeToDownloads(context, fileName, result)
                                    }, modifier = Modifier.size(24.dp)) {
                                        LucideIcon("download", "下载HTML", Modifier.size(14.dp), tint = Muted)
                                    }
                                    // 全屏按钮
                                    IconButton(onClick = { showFullscreen = true }, modifier = Modifier.size(24.dp)) {
                                        LucideIcon("maximize", "全屏预览", Modifier.size(14.dp), tint = Muted)
                                    }
                                }
                            }
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(resultHeight)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Color.White)
                            ) {
                                AndroidView(factory = { context ->
                                    WebView(context).apply {
                                        settings.javaScriptEnabled = true
                                        settings.domStorageEnabled = true
                                        settings.databaseEnabled = true
                                        settings.allowContentAccess = true
                                        settings.allowFileAccess = true
                                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                                        settings.setSupportMultipleWindows(false)
                                        settings.setSupportZoom(true)
                                        settings.builtInZoomControls = true
                                        settings.displayZoomControls = false
                                        settings.useWideViewPort = true
                                        settings.loadWithOverviewMode = true
                                        setBackgroundColor(android.graphics.Color.TRANSPARENT)
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
                                                    resultHeight = dp.dp
                                                }
                                            }
                                        }
                                        webChromeClient = object : android.webkit.WebChromeClient() {
                                            override fun onConsoleMessage(cm: android.webkit.ConsoleMessage?): Boolean {
                                                cm ?: return super.onConsoleMessage(cm)
                                                QuroDiag.log("WebView", "[${cm.sourceId()}:${cm.lineNumber()}] ${cm.message()}")
                                                return true
                                            }
                                        }
                                        loadDataWithBaseURL("https://localhost/", result, "text/html", "UTF-8", null)
                                    }
                                }, modifier = Modifier.fillMaxSize())
                            }
                        }
                    }
                } else {
                    // 纯文本结果
                    val context = LocalContext.current
                    Surface(
                        color = if (isErrorCode(result))
                            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                        else
                            cs.surface,
                        modifier = Modifier.fillMaxWidth().padding(8.dp),
                        shape = RoundedCornerShape(6.dp),
                    ) {
                        Column(Modifier.padding(10.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    if (isErrorCode(result))
                                        "❌ 运行结果" else "✅ 运行结果",
                                    fontSize = 11.sp,
                                    color = if (isErrorCode(result))
                                        MaterialTheme.colorScheme.error else Muted,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                // 下载按钮（仅成功结果）
                                if (!isErrorCode(result)) {
                                    IconButton(onClick = {
                                        val fileName = "quro_result_${System.currentTimeMillis()}.txt"
                                        saveCodeToDownloads(context, fileName, result)
                                    }, modifier = Modifier.size(24.dp)) {
                                        LucideIcon("download", "下载结果", Modifier.size(14.dp), tint = Muted)
                                    }
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            SelectionContainer {
                                Text(
                                    result,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    color = cs.onSurface,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // 全屏预览覆盖层
    if (showFullscreen) {
        FullscreenPreview(code = code, isHtml = isHtml, lang = lang, onDismiss = { showFullscreen = false })
    }
}

// 全屏预览覆盖层（CodeBlock 内点击 🔲 全屏按钮触发）—— 用 Dialog 真正覆盖全屏
@Composable
private fun FullscreenPreview(code: String, isHtml: Boolean, lang: String = "", onDismiss: () -> Unit) {
    // #877 修复全屏白屏：Compose Dialog 是 overlay window，内部 WebView 在多 ROM 上无法正确
    // 合成 → 白屏。改用挂在真实 Activity window 上的传统 Dialog，WebView 用 Activity context
    // 创建，能稳定渲染。若取不到 Activity（极端），兜底仍用 Compose Dialog。
    val ctx = LocalContext.current
    val activity = ctx.findActivity()
    val finalHtml = if (isHtml || code.contains("<svg") || code.contains("<svg ")) {
        val t = code.trim()
        val isFullDoc = t.startsWith("<!DOCTYPE html", ignoreCase = true) || t.startsWith("<html", ignoreCase = true)
        if (isFullDoc) code else buildString {
            append("<!DOCTYPE html><html><head><meta charset=\"UTF-8\">")
            append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
            append("<style>body{margin:0;padding:16px;font-family:sans-serif;word-wrap:break-word;}img{max-width:100%;height:auto;}</style>")
            append("</head><body>").append(code).append("</body></html>")
        }
    } else {
        // 代码块全屏预览：使用 Highlight.js 语法高亮
        buildString {
            append("<!DOCTYPE html><html><head><meta charset=\"UTF-8\">")
            append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
            // Highlight.js CDN
            append("<link rel=\"stylesheet\" href=\"https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.9.0/styles/github-dark.min.css\">")
            append("<script src=\"https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.9.0/highlight.min.js\"></script>")
            append("<script src=\"https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.9.0/languages/python.min.js\"></script>")
            append("<script src=\"https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.9.0/languages/javascript.min.js\"></script>")
            append("<script src=\"https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.9.0/languages/bash.min.js\"></script>")
            append("<script src=\"https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.9.0/languages/xml.min.js\"></script>")
            append("<script src=\"https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.9.0/languages/json.min.js\"></script>")
            append("<script src=\"https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.9.0/languages/java.min.js\"></script>")
            append("<script src=\"https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.9.0/languages/kotlin.min.js\"></script>")
            append("<style>")
            append("body{margin:0;padding:16px;background:#1e1e1e;}")
            append("pre{margin:0;padding:0;overflow-x:auto;font-family:'Fira Code',Consolas,Monaco,'Courier New',monospace;font-size:14px;line-height:1.6;}")
            append("code{background:transparent;padding:0;}")
            append("</style></head><body><pre><code class=\"language-${lang.ifBlank { "plaintext" }}\">")
            append(escapeHtml(code))
            append("</code></pre>")
            append("<script>hljs.highlightAll();</script>")
            append("</body></html>")
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

private fun saveCodeToDownloads(context: Context, fileName: String, content: String) {
    android.os.Handler(android.os.Looper.getMainLooper()).post {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val contentValues = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                uri?.let {
                    context.contentResolver.openOutputStream(it)?.use { outputStream ->
                        outputStream.write(content.toByteArray())
                    }
                    Toast.makeText(context, "✅ 已保存到 Downloads/$fileName", Toast.LENGTH_SHORT).show()
                } ?: Toast.makeText(context, "❌ 保存失败：无法创建文件", Toast.LENGTH_SHORT).show()
            } else {
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!downloadsDir.exists()) downloadsDir.mkdirs()
                val file = java.io.File(downloadsDir, fileName)
                file.writeText(content)
                Toast.makeText(context, "✅ 已保存到 Downloads/$fileName", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(context, "❌ 保存失败：${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}

/** 构造 WebView 预览用 HTML：完整文档直接渲染，HTML 片段包裹为完整文档，非 HTML 代码用 <pre> 转义防 XSS。 */
private fun buildPreviewHtml(code: String, isHtml: Boolean, lang: String = ""): String {
    val t = code.trim()
    val isFullDoc = t.startsWith("<!DOCTYPE html", ignoreCase = true) || t.startsWith("<html", ignoreCase = true)

    // SVG 内容：直接渲染为图形
    if (t.contains("<svg") || t.contains("<svg ")) {
        return buildString {
            append("<!DOCTYPE html><html><head><meta charset=\"UTF-8\">")
            append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
            append("<style>body{margin:0;padding:8px;background:white;display:flex;justify-content:center;align-items:center;}svg{max-width:100%;height:auto;}</style>")
            append("</head><body>")
            append(t)
            append("</body></html>")
        }
    }

    // JSON 内容：渲染为格式化的树形视图
    if (lang.equals("json", ignoreCase = true) || looksLikeJson(t)) {
        return buildJsonPreviewHtml(t)
    }

    return if (isFullDoc || isHtml) {
        if (isFullDoc) code else buildString {
            append("<!DOCTYPE html><html><head><meta charset=\"UTF-8\">")
            append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
            append("<base href=\"https://localhost/\">")
            append("<style>body{margin:8px;padding:0;font-family:sans-serif;word-wrap:break-word;}img{max-width:100%;height:auto;}</style></head><body>")
            append(code)
            append("</body></html>")
        }
    } else {
        // 代码块：使用内联 CSS 高亮 + 简单语法着色（不依赖外部 CDN）
        buildString {
            append("<!DOCTYPE html><html><head><meta charset=\"UTF-8\">")
            append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
            // 内联语法高亮样式
            append("<style>")
            append("body{margin:0;padding:0;background:#1e1e1e;}")
            append("pre{margin:0;padding:16px;overflow-x:auto;font-family:'Fira Code',Consolas,Monaco,'Courier New',monospace;font-size:13px;line-height:1.5;color:#d4d4d4;white-space:pre-wrap;word-break:break-word;}")
            append("code{background:transparent;padding:0;}")
            // Python 语法高亮颜色
            append(".keyword{color:#c586c0;}")  // 关键词
            append(".string{color:#ce9178;}")   // 字符串
            append(".number{color:#b5cea8;}")   // 数字
            append(".comment{color:#6a9955;}")  // 注释
            append(".function{color:#dcdcaa;}")  // 函数名
            append(".decorator{color:#dcdcaa;}") // 装饰器
            append(".builtin{color:#4ec9b0;}")  // 内置函数
            append(".operator{color:#d4d4d4;}") // 运算符
            append("</style></head><body><pre><code>")
            // 简单语法高亮
            append(highlightCode(escapeHtml(code), lang))
            append("</code></pre>")
            append("</body></html>")
        }
    }
}

/** 检测是否看起来像 JSON 内容。 */
private fun looksLikeJson(code: String): Boolean {
    val t = code.trim()
    return (t.startsWith("{") && t.endsWith("}")) || (t.startsWith("[") && t.endsWith("]"))
}

/** 为 JSON 内容构建格式化的树形预览 HTML。 */
private fun buildJsonPreviewHtml(code: String): String {
    // 提取实际的 JSON 内容（跳过提示文本）
    val jsonContent = if (code.contains("\n\n")) {
        code.substringAfter("\n\n").trim()
    } else {
        code.trim()
    }

    return buildString {
        append("<!DOCTYPE html><html><head><meta charset=\"UTF-8\">")
        append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
        append("<style>")
        append("body{margin:0;padding:12px;background:#1e1e1e;font-family:'Fira Code',Consolas,Monaco,'Courier New',monospace;font-size:13px;line-height:1.5;}")
        append(".json-key{color:#9cdcfe;}")   // 键名
        append(".json-string{color:#ce9178;}") // 字符串值
        append(".json-number{color:#b5cea8;}") // 数字值
        append(".json-boolean{color:#569cd6;}") // 布尔值
        append(".json-null{color:#569cd6;}")   // null 值
        append(".json-brace{color:#d4d4d4;}")  // 大括号
        append(".json-bracket{color:#d4d4d4;}") // 中括号
        append(".json-colon{color:#d4d4d4;}")  // 冒号
        append(".json-comma{color:#d4d4d4;}")  // 逗号
        append(".json-indent{display:inline-block;width:20px;}")
        append("</style></head><body><pre>")
        // 简单的 JSON 语法高亮
        append(highlightJsonForPreview(escapeHtml(jsonContent)))
        append("</pre></body></html>")
    }
}

/** 为 JSON 预览添加语法高亮。 */
private fun highlightJsonForPreview(code: String): String {
    var result = code
    // 键名（双引号包裹的键，后面跟冒号）
    result = result.replace(Regex("(&quot;[^&]*?&quot;)(\\s*:)"), "<span class=\"json-key\">$1</span><span class=\"json-colon\">$2</span>")
    // 字符串值（冒号后面的双引号包裹的内容）
    result = result.replace(Regex(":(\\s*)&quot;([^&]*?)&quot;"), ":<span class=\"json-string\">&quot;$2&quot;</span>")
    // 数字
    result = result.replace(Regex("\\b(\\d+\\.?\\d*)\\b"), "<span class=\"json-number\">$1</span>")
    // 布尔值
    result = result.replace(Regex("\\b(true|false)\\b"), "<span class=\"json-boolean\">$1</span>")
    // null
    result = result.replace(Regex("\\b(null)\\b"), "<span class=\"json-null\">$1</span>")
    return result
}

/** 简单语法高亮：根据语言类型用正则替换关键词为带样式的 span */
private fun highlightCode(code: String, lang: String): String {
    val lower = lang.lowercase()
    return when {
        lower == "python" || lower == "py" -> highlightPython(code)
        lower == "javascript" || lower == "js" || lower == "ts" || lower == "typescript" -> highlightJs(code)
        lower == "kotlin" || lower == "kt" -> highlightKotlin(code)
        lower == "java" -> highlightJava(code)
        lower == "json" -> highlightJson(code)
        lower == "cpp" || lower == "c" || lower == "c++" || lower == "cc" || lower == "h" || lower == "hpp" -> highlightCpp(code)
        lower == "css" -> highlightCss(code)
        lower == "xml" || lower == "svg" -> highlightXml(code)
        else -> code // 其他语言不处理
    }
}

/** Python 语法高亮 */
private fun highlightPython(code: String): String {
    var result = code
    // 注释（# 开头到行尾）
    result = result.replace(Regex("(#[^\n]*)"), "<span class=\"comment\">$1</span>")
    // 字符串（单引号/双引号/三引号）
    result = result.replace(Regex("(&quot;[^&]*?&quot;|&#39;[^&]*?&#39;|(&quot;&quot;&quot;)[\\s\\S]*?(&quot;&quot;&quot;))"), "<span class=\"string\">$1</span>")
    // 关键词
    val keywords = listOf("def", "class", "if", "else", "elif", "for", "while", "return", "import", "from", "as", "try", "except", "finally", "with", "lambda", "yield", "pass", "break", "continue", "and", "or", "not", "in", "is", "None", "True", "False", "self", "print")
    keywords.forEach { kw ->
        result = result.replace(Regex("\\b($kw)\\b"), "<span class=\"keyword\">$1</span>")
    }
    // 数字
    result = result.replace(Regex("\\b(\\d+\\.?\\d*)\\b"), "<span class=\"number\">$1</span>")
    // 函数名
    result = result.replace(Regex("\\b(\\w+)\\s*\\("), "<span class=\"function\">$1</span>(")
    return result
}

/** JavaScript 语法高亮 */
private fun highlightJs(code: String): String {
    var result = code
    // 注释
    result = result.replace(Regex("(//[^\n]*)"), "<span class=\"comment\">$1</span>")
    // 字符串
    result = result.replace(Regex("(#[^\n]*)"), "<span class=\"comment\">$1</span>")
    result = result.replace(Regex("(&quot;[^&]*?&quot;|&#39;[^&]*?&#39;)"), "<span class=\"string\">$1</span>")
    // 关键词
    val keywords = listOf("var", "let", "const", "function", "return", "if", "else", "for", "while", "class", "extends", "import", "export", "default", "new", "this", "console", "log", "true", "false", "null", "undefined")
    keywords.forEach { kw ->
        result = result.replace(Regex("\\b($kw)\\b"), "<span class=\"keyword\">$1</span>")
    }
    // 数字
    result = result.replace(Regex("\\b(\\d+\\.?\\d*)\\b"), "<span class=\"number\">$1</span>")
    return result
}

/** Kotlin 语法高亮 */
private fun highlightKotlin(code: String): String {
    var result = code
    // 注释
    result = result.replace(Regex("(//[^\n]*)"), "<span class=\"comment\">$1</span>")
    // 字符串
    result = result.replace(Regex("(&quot;[^&]*?&quot;|&#39;[^&]*?&#39;)"), "<span class=\"string\">$1</span>")
    // 关键词
    val keywords = listOf("fun", "val", "var", "class", "object", "interface", "if", "else", "when", "for", "while", "return", "import", "package", "private", "public", "internal", "protected", "override", "abstract", "data", "sealed", "companion", "suspend", "launch", "with", "this", "super", "true", "false", "null")
    keywords.forEach { kw ->
        result = result.replace(Regex("\\b($kw)\\b"), "<span class=\"keyword\">$1</span>")
    }
    // 数字
    result = result.replace(Regex("\\b(\\d+\\.?\\d*)\\b"), "<span class=\"number\">$1</span>")
    return result
}

/** Java 语法高亮 */
private fun highlightJava(code: String): String {
    var result = code
    // 注释
    result = result.replace(Regex("(//[^\n]*)"), "<span class=\"comment\">$1</span>")
    // 字符串
    result = result.replace(Regex("(&quot;[^&]*?&quot;|&#39;[^&]*?&#39;)"), "<span class=\"string\">$1</span>")
    // 关键词
    val keywords = listOf("public", "private", "protected", "static", "final", "class", "interface", "extends", "implements", "if", "else", "for", "while", "return", "import", "package", "new", "this", "super", "void", "int", "long", "double", "float", "boolean", "char", "byte", "short", "String", "null", "true", "false")
    keywords.forEach { kw ->
        result = result.replace(Regex("\\b($kw)\\b"), "<span class=\"keyword\">$1</span>")
    }
    // 数字
    result = result.replace(Regex("\\b(\\d+\\.?\\d*)\\b"), "<span class=\"number\">$1</span>")
    return result
}

/** JSON 语法高亮 */
private fun highlightJson(code: String): String {
    var result = code
    // 字符串（键和值）
    result = result.replace(Regex("(&quot;[^&]*?&quot;)"), "<span class=\"string\">$1</span>")
    // 数字
    result = result.replace(Regex("\\b(\\d+\\.?\\d*)\\b"), "<span class=\"number\">$1</span>")
    // 布尔值
    result = result.replace(Regex("\\b(true|false|null)\\b"), "<span class=\"keyword\">$1</span>")
    return result
}

/** C/C++ 语法高亮 */
private fun highlightCpp(code: String): String {
    var result = code
    // 块注释 /* */
    result = result.replace(Regex("(/\\*[\\s\\S]*?\\*/)"), "<span class=\"comment\">$1</span>")
    // 行注释 //
    result = result.replace(Regex("(//[^\\n]*)"), "<span class=\"comment\">$1</span>")
    // 字符串
    result = result.replace(Regex("(&quot;[^&]*?&quot;|&#39;[^&]*?&#39;)"), "<span class=\"string\">$1</span>")
    // 预处理指令
    result = result.replace(Regex("(#\\w+)"), "<span class=\"builtin\">$1</span>")
    // 关键词
    val keywords = listOf("int", "long", "short", "char", "float", "double", "bool", "void", "auto", "const", "static", "struct", "class", "public", "private", "protected", "virtual", "template", "typename", "namespace", "using", "if", "else", "for", "while", "do", "switch", "case", "return", "break", "continue", "new", "delete", "true", "false", "nullptr", "include", "define", "ifdef", "ifndef", "endif", "std")
    keywords.forEach { kw ->
        result = result.replace(Regex("\\b($kw)\\b"), "<span class=\"keyword\">$1</span>")
    }
    // 数字
    result = result.replace(Regex("\\b(\\d+\\.?\\d*)\\b"), "<span class=\"number\">$1</span>")
    return result
}

/** CSS 语法高亮 */
private fun highlightCss(code: String): String {
    var result = code
    // 注释
    result = result.replace(Regex("(/\\*[\\s\\S]*?\\*/)"), "<span class=\"comment\">$1</span>")
    // 选择器 { 前的高亮
    result = result.replace(Regex("([.#]?[a-zA-Z_-][\\w-]*)\\s*\\{"), "<span class=\"function\">$1</span> {")
    // 属性名（冒号前）
    result = result.replace(Regex("([a-z-]+)\\s*:"), "<span class=\"keyword\">$1</span>:")
    // 数值 + 单位
    result = result.replace(Regex("\\b(\\d+\\.?\\d*)(px|em|rem|%|vh|vw|s|ms)?\\b"), "<span class=\"number\">$1$2</span>")
    // 颜色
    result = result.replace(Regex("(#[0-9a-fA-F]{3,8})\\b"), "<span class=\"string\">$1</span>")
    // 字符串
    result = result.replace(Regex("(&quot;[^&]*?&quot;|&#39;[^&]*?&#39;)"), "<span class=\"string\">$1</span>")
    return result
}

/** XML/SVG 语法高亮（输入已 escapeHtml：< → &lt;，> → &gt;，\" → &quot;） */
private fun highlightXml(code: String): String {
    var result = code
    // 注释
    result = result.replace(Regex("(&lt;!--[\\s\\S]*?--&gt;)"), "<span class=\"comment\">$1</span>")
    // 标签名
    result = result.replace(Regex("(&lt;/?)([a-zA-Z][\\w:-]*)"), "$1<span class=\"keyword\">$2</span>")
    // 属性名
    result = result.replace(Regex("([a-zA-Z_:][\\w:.-]*)="), "<span class=\"function\">$1</span>=")
    // 属性值
    result = result.replace(Regex("=(&quot;[^&]*?&quot;|&#39;[^&]*?&#39;)"), "=<span class=\"string\">$1</span>")
    return result
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
    val attachmentList = attachments?.map {
        Attachment(it.name, formatSize(it.size), path = it.uri, type = it.type)
    } ?: emptyList()
    return Message(
        id = id.hashCode(),
        uids = listOf(id),
        mine = mine,
        author = if (mine) (senderName ?: userName).ifBlank { "我" } else assistantName,
        avatar = if (mine) (senderName ?: userName).ifBlank { "我" } else assistantAvatar,
        avatarUri = if (mine) (avatarUrl ?: userAvatarUri) else assistantAvatarUri,
        time = formatChatTime(createdAt),
        text = content.ifBlank { null },
        attachments = attachmentList,
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

// 工作流管理界面
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun QuroWorkflowScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    var workflows by remember { mutableStateOf(listOf<Workflow>()) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var editingWorkflow by remember { mutableStateOf<Workflow?>(null) }

    // 初始化 WorkflowRepository
    LaunchedEffect(Unit) {
        WorkflowRepository.init(context)
        workflows = WorkflowRepository.getAll()
    }

    // 监听工作流变化
    LaunchedEffect(WorkflowRepository.changeSignal.value) {
        workflows = WorkflowRepository.getAll()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("工作流管理", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { editingWorkflow = null; showCreateDialog = true }) {
                        Icon(Icons.Filled.Add, contentDescription = "新建工作流")
                    }
                }
            )
        }
    ) { padding ->
        if (workflows.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(Icons.Filled.List, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f))
                Spacer(Modifier.height(16.dp))
                Text("还没有工作流", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                Spacer(Modifier.height(8.dp))
                Text("点击右上角 + 新建，或让 AI 帮你创建", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(workflows, key = { it.id }) { workflow ->
                    WorkflowCard(
                        workflow = workflow,
                        onToggle = { enabled ->
                            val updated = workflow.copy(enabled = enabled)
                            WorkflowRepository.upsert(updated)
                        },
                        onRun = {
                            // 运行工作流
                            CoroutineScope(Dispatchers.IO).launch {
                                try {
                                    com.ai.assistance.quro.workflow.executor.WorkflowEngine.run(workflow.id)
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(context, "工作流执行完成", Toast.LENGTH_SHORT).show()
                                    }
                                } catch (e: Exception) {
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(context, "执行失败: ${e.message}", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        },
                        onEdit = {
                            editingWorkflow = workflow
                            showCreateDialog = true
                        },
                        onDelete = {
                            WorkflowRepository.delete(workflow.id)
                        }
                    )
                }
            }
        }
    }

    // 创建/编辑工作流对话框
    if (showCreateDialog) {
        WorkflowCreateDialog(
            workflow = editingWorkflow,
            onDismiss = { showCreateDialog = false },
            onSave = { workflow ->
                WorkflowRepository.upsert(workflow)
                showCreateDialog = false
            }
        )
    }
}

// 工作流卡片
@Composable
private fun WorkflowCard(
    workflow: Workflow,
    onToggle: (Boolean) -> Unit,
    onRun: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = cs.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = workflow.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = cs.onSurface
                    )
                    Text(
                        text = "触发: ${workflow.trigger} | 节点: ${workflow.nodes.size}",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant
                    )
                    if (workflow.lastStatus != "idle") {
                        Text(
                            text = "上次状态: ${workflow.lastStatus}",
                            style = MaterialTheme.typography.bodySmall,
                            color = when (workflow.lastStatus) {
                                "success" -> Color(0xFF4CAF50)
                                "failed" -> Color(0xFFF44336)
                                else -> cs.onSurfaceVariant
                            }
                        )
                    }
                }
                Switch(
                    checked = workflow.enabled,
                    onCheckedChange = onToggle
                )
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onRun,
                    modifier = Modifier.weight(1f),
                    enabled = workflow.enabled
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("运行")
                }
                OutlinedButton(
                    onClick = onEdit,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("编辑")
                }
                IconButton(
                    onClick = onDelete,
                    colors = IconButtonDefaults.iconButtonColors(contentColor = cs.error)
                ) {
                    Icon(Icons.Filled.Delete, contentDescription = "删除")
                }
            }
        }
    }
}

// 工作流创建/编辑对话框
@Composable
private fun WorkflowCreateDialog(
    workflow: Workflow?,
    onDismiss: () -> Unit,
    onSave: (Workflow) -> Unit
) {
    var name by remember { mutableStateOf(workflow?.name ?: "") }
    var trigger by remember { mutableStateOf(workflow?.trigger ?: "manual") }
    var schedule by remember { mutableStateOf(workflow?.schedule ?: "") }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (workflow != null) "编辑工作流" else "新建工作流") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("工作流名称") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = trigger,
                    onValueChange = { trigger = it },
                    label = { Text("触发类型 (manual/time/event)") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = schedule,
                    onValueChange = { schedule = it },
                    label = { Text("调度规则 (如 daily:09:00)") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val newWorkflow = Workflow(
                        id = workflow?.id ?: java.util.UUID.randomUUID().toString(),
                        name = name,
                        trigger = trigger,
                        schedule = schedule,
                        enabled = workflow?.enabled ?: true,
                        nodes = workflow?.nodes ?: emptyList()
                    )
                    onSave(newWorkflow)
                },
                enabled = name.isNotBlank()
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

/**
 * 文件管理页面（从设置「文件管理」进入）。
 * 浏览沙箱目录，并支持「在系统文件管理器中打开」与「复制路径」；
 * 顶部展示共享存储挂载状态（Android 11+ 应用私有目录在系统文件管理器可见）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileManagerDialog(
    onClose: () -> Unit,
    scaled: (Int) -> androidx.compose.ui.unit.TextUnit,
) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val externalFiles = ctx.getExternalFilesDir(null)
    val dirs = listOfNotNull(
        "应用私有文件" to ctx.filesDir,
        "应用数据(quro_data)" to File(ctx.filesDir, "quro_data"),
        "导出(quro_exports)" to File(externalFiles, "quro_exports"),
        "备份(quro_backups)" to File(externalFiles, "quro_backups"),
        "公共下载/QuroAI_logs" to File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "QuroAI_logs",
        ),
    )
    val sharedMounted = Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED

    fun openInSystemFileManager(dir: File) {
        if (!dir.exists()) {
            Toast.makeText(ctx, "目录不存在：${dir.absolutePath}", Toast.LENGTH_SHORT).show()
            return
        }
        // 路径 1：用 FileProvider 暴露 content:// URI，再 ACTION_OPEN_DOCUMENT_TREE 落到 SAF。
        // ACTION_VIEW + file:// 在 Android 7+ StrictMode 直接崩、11+ 无文件管理器响应，
        // 所以这里尽量走 SAF / 复制路径兜底，避免误以为功能不工作。
        try {
            val treeIntent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(treeIntent)
            Toast.makeText(ctx, "请在系统文件管理器中导航到：${dir.absolutePath}", Toast.LENGTH_LONG).show()
        } catch (_: Exception) {
            try {
                val authority = "${ctx.packageName}.fileprovider"
                val uri = androidx.core.content.FileProvider.getUriForFile(ctx, authority, dir)
                val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "resource/folder")
                    addCategory(Intent.CATEGORY_DEFAULT)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                ctx.startActivity(viewIntent)
            } catch (_: Exception) {
                val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("path", dir.absolutePath))
                Toast.makeText(ctx, "已复制路径，请在文件管理器粘贴：${dir.absolutePath}", Toast.LENGTH_LONG).show()
            }
        }
    }

    Column(Modifier.fillMaxSize().background(cs.background)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
            Spacer(Modifier.width(8.dp))
            Text("文件管理", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface)
        }
        // 共享存储状态
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                .clip(RoundedCornerShape(10.dp)).background(cs.surfaceVariant.copy(alpha = 0.4f))
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (sharedMounted) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                null,
                tint = if (sharedMounted) cs.primary else cs.error,
            )
            Spacer(Modifier.width(10.dp))
            Column {
                Text("共享存储", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface)
                Text(
                    if (sharedMounted) "已挂载（Android 11+ 应用私有目录在系统文件管理器可见）" else "未挂载或不可用",
                    fontSize = 12.sp, color = cs.onSurfaceVariant,
                )
            }
        }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            items(dirs) { (label, dir) ->
                val exists = dir.exists()
                Column(
                    Modifier.fillMaxWidth().clickable { openInSystemFileManager(dir) }
                        .padding(vertical = 12.dp),
                ) {
                    Text(label, fontSize = 15.sp, color = cs.onSurface, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${if (exists) "存在" else "不存在"} · ${dir.absolutePath}",
                        fontSize = 12.sp, color = cs.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip(
                            onClick = { openInSystemFileManager(dir) },
                            label = { Text("在系统文件管理器中打开") },
                            leadingIcon = { Icon(Icons.Filled.FolderOpen, null, Modifier.size(16.dp)) },
                        )
                        AssistChip(
                            onClick = {
                                val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                cm.setPrimaryClip(android.content.ClipData.newPlainText("path", dir.absolutePath))
                                Toast.makeText(ctx, "已复制路径", Toast.LENGTH_SHORT).show()
                            },
                            label = { Text("复制路径") },
                            leadingIcon = { Icon(Icons.Filled.ContentCopy, null, Modifier.size(16.dp)) },
                        )
                    }
                    HorizontalDivider(Modifier.padding(top = 10.dp), color = cs.outlineVariant)
                }
            }
        }
    }
}

/**
 * 清理存储页面（从设置「数据 → 清理存储」进入）。
 * 分类清理：日志、离线模型缓存、AI产物、临时文件等。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CleanupScreen(
    onClose: () -> Unit,
    scaled: (Int) -> androidx.compose.ui.unit.TextUnit,
) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    // 各类真实写盘路径（与运行时一致，不再写死不存在的目录）
    val ext = context.getExternalFilesDir(null)
    val paths = remember {
        mapOf(
            "appdata" to File(context.filesDir, "quro_data"),
            "sandbox" to File(context.filesDir, "linux-sandbox"), // Ubuntu rootfs + tmp，体积最大
            "logs" to File(
                android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS
                ), "QuroAI_logs"
            ), // 诊断日志（公共 Download，需 MANAGE_EXTERNAL_STORAGE 才能删）
            "exports" to (ext?.let { File(it, "quro_exports") } ?: File(context.filesDir, "quro_exports")),
            "backups" to (ext?.let { File(it, "quro_backups") } ?: File(context.filesDir, "quro_backups")),
            "cache" to context.cacheDir,
        )
    }
    var sizes by remember { mutableStateOf(mapOf<String, Long>()) }
    var showCleanupDialog by remember { mutableStateOf(false) }
    var cleanupType by remember { mutableStateOf("") }

    fun total() = sizes.values.sum()

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            sizes = paths.mapValues { (_, f) -> calculateDirSize(f) }
        }
    }

    Column(Modifier.fillMaxSize().background(cs.background)) {
        TopAppBar(
            title = { Text("清理存储") },
            navigationIcon = {
                IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = cs.background),
        )
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 20.dp)
        ) {
            GroupCaption("存储概览")
            SetGroup {
                SetRowClickable(
                    Icons.Filled.Info,
                    "可清理总大小",
                    formatFileSize(total()),
                    "以下分类之和（不含离线模型等用户外部文件）",
                    { },
                    scaled
                )
            }

            GroupCaption("分类清理（谨慎选择，重要数据会丢失）")
            SetGroup {
                CleanupRow(Icons.Filled.List, "应用数据", formatFileSize(sizes["appdata"] ?: 0),
                    "对话 / 设置 / 模型配置", scaled) { cleanupType = "appdata"; showCleanupDialog = true }
                HorizontalDivider(color = Line, thickness = 1.dp, modifier = Modifier.padding(horizontal = 12.dp))
                CleanupRow(Icons.Filled.Folder, "Linux 沙箱 (rootfs)", formatFileSize(sizes["sandbox"] ?: 0),
                    "Ubuntu rootfs / 缓存，清理后需重新下载", scaled) { cleanupType = "sandbox"; showCleanupDialog = true }
                HorizontalDivider(color = Line, thickness = 1.dp, modifier = Modifier.padding(horizontal = 12.dp))
                CleanupRow(Icons.Filled.Description, "诊断日志", formatFileSize(sizes["logs"] ?: 0),
                    "手机 Download/QuroAI_logs", scaled) { cleanupType = "logs"; showCleanupDialog = true }
                HorizontalDivider(color = Line, thickness = 1.dp, modifier = Modifier.padding(horizontal = 12.dp))
                CleanupRow(Icons.Filled.Folder, "导出文件", formatFileSize(sizes["exports"] ?: 0),
                    "导出的 ZIP 数据包", scaled) { cleanupType = "exports"; showCleanupDialog = true }
                HorizontalDivider(color = Line, thickness = 1.dp, modifier = Modifier.padding(horizontal = 12.dp))
                CleanupRow(Icons.Filled.Build, "备份文件", formatFileSize(sizes["backups"] ?: 0),
                    "数据备份归档", scaled) { cleanupType = "backups"; showCleanupDialog = true }
                HorizontalDivider(color = Line, thickness = 1.dp, modifier = Modifier.padding(horizontal = 12.dp))
                CleanupRow(Icons.Filled.Delete, "缓存目录", formatFileSize(sizes["cache"] ?: 0),
                    "图片 / WebView / 临时缓存", scaled) { cleanupType = "cache"; showCleanupDialog = true }
            }

            GroupCaption("全部清理")
            SetGroup {
                SetRowClickable(
                    Icons.Filled.DeleteSweep,
                    "清理所有以上项",
                    "删除应用数据 / 沙箱 / 日志 / 导出 / 备份 / 缓存",
                    "",
                    {
                        cleanupType = "all"
                        showCleanupDialog = true
                    },
                    scaled,
                    danger = true
                )
            }
        }
    }

    if (showCleanupDialog) {
        val title = when (cleanupType) {
            "appdata" -> "清理应用数据"
            "sandbox" -> "清理 Linux 沙箱"
            "logs" -> "清理诊断日志"
            "exports" -> "清理导出文件"
            "backups" -> "清理备份文件"
            "cache" -> "清理缓存目录"
            "all" -> "清理所有以上项"
            else -> "清理"
        }
        val message = when (cleanupType) {
            "appdata" -> "将删除所有对话 / 设置 / 模型配置（不可恢复），确定吗？"
            "sandbox" -> "将删除 Ubuntu rootfs 与沙箱缓存，下次使用 CMS/终端需重新下载，确定吗？"
            "logs" -> "将删除手机 Download/QuroAI_logs 诊断日志，确定吗？"
            "exports" -> "将删除所有导出的 ZIP 数据包，确定吗？"
            "backups" -> "将删除所有数据备份归档，确定吗？"
            "cache" -> "将删除应用缓存目录，确定吗？"
            "all" -> "将删除以上全部项（不含离线模型等用户外部文件），确定吗？"
            else -> "确定要清理吗？"
        }

        AlertDialog(
            onDismissRequest = { showCleanupDialog = false },
            title = { Text(title) },
            text = { Text(message) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showCleanupDialog = false
                        CoroutineScope(Dispatchers.IO).launch {
                            val targets = if (cleanupType == "all") paths else mapOf(cleanupType to (paths[cleanupType] ?: File("/dev/null")))
                            targets.forEach { (_, f) -> deleteDir(f) }
                            sizes = paths.mapValues { (_, f) -> calculateDirSize(f) }
                        }
                    }
                ) { Text("确定清理") }
            },
            dismissButton = {
                TextButton(onClick = { showCleanupDialog = false }) { Text("取消") }
            }
        )
    }
}

/**
 * 清理存储分类行（点击触发确认）。
 */
@Composable
private fun CleanupRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    size: String,
    sub: String,
    scaled: (Int) -> androidx.compose.ui.unit.TextUnit,
    onClick: () -> Unit,
) {
    SetRowClickable(icon, title, size, sub, onClick, scaled)
}

/**
 * 计算目录大小（递归）。
 */
private fun calculateDirSize(dir: File): Long {
    if (!dir.exists()) return 0
    var size = 0L
    dir.listFiles()?.forEach { file ->
        size += if (file.isDirectory) {
            calculateDirSize(file)
        } else {
            file.length()
        }
    }
    return size
}

/**
 * 删除目录（保留顶层目录本身，仅清空内容，避免后续写入因目录缺失而报错）。
 */
private fun deleteDir(dir: File) {
    if (!dir.exists()) return
    dir.listFiles()?.forEach { file ->
        if (file.isDirectory) {
            deleteDir(file)
            file.delete()
        } else {
            file.delete()
        }
    }
}

/**
 * 格式化文件大小
 */
private fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
        else -> "${bytes / (1024 * 1024 * 1024)} GB"
    }
}
