@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.ai.assistance.quro.ui

import android.Manifest
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.annotation.SuppressLint
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.ConsoleMessage
import com.ai.assistance.quro.util.QuroDiag
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.ai.assistance.quro.core.model.QuroDigitalHumanConfig
import com.ai.assistance.quro.core.model.QuroDigitalHumanConfigRepository
import com.ai.assistance.quro.core.model.QuroModelConfig
import com.ai.assistance.quro.core.model.QuroModelConfigRepository
import com.ai.assistance.quro.core.tools.QuroOnDeviceAsr
import com.ai.assistance.quro.core.tools.QuroSttHolder
import com.ai.assistance.quro.core.tools.QuroSttPrefs
import com.ai.assistance.quro.core.tools.QuroTtsHolder
import com.ai.assistance.quro.core.tools.QuroVoiceStyle
import com.ai.assistance.quro.ui.theme.Accent
import com.ai.assistance.quro.ui.theme.AccentSoft
import com.ai.assistance.quro.ui.theme.Card
import com.ai.assistance.quro.ui.theme.Line
import com.ai.assistance.quro.ui.theme.Muted
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt
import org.json.JSONObject

/**
 * 数字人屏幕（对应「二、3D 全离线：LLM+ASR+TTS+A2BS+渲染都在手机」）。
 *
 * 闭环：离线 ASR(sherpa-ncnn) 拾音 → LLM → 离线/云 TTS 朗读，头像口型随 TTS 同步。
 *
 * 用户可自决：
 * 1) LLM 来源：「云端口」（跟随全局模型配置，cloud）或「离线」（自己填本地端点 baseUrl/apiKey/model，
 *    如 LM Studio / 端侧 LLM / Ollama），达成 100% 离线闭环。
 * 2) 头像来源：「内置 2.5D」Canvas 或「自定义 GLB」（用户自制 3D 模型，SAF 选取后由 WebView+Three.js 渲染）。
 */
@Composable
fun QuroDigitalHumanScreen(onExitToHome: () -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val vm = remember { QuroChatViewModel(ctx) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val repo = remember { QuroDigitalHumanConfigRepository(ctx) }
    var dh by remember { mutableStateOf(repo.load()) }
    var showSettings by remember { mutableStateOf(false) }

    // 依据配置计算实际 LLM 配置：离线模式用用户本地端点，否则跟随全局
    val effectiveCfg = remember(dh.llmMode, dh.offlineBaseUrl, dh.offlineApiKey, dh.offlineModel) {
        if (dh.llmMode == "offline") {
            QuroModelConfig(
                provider = "OPENAI",
                baseUrl = dh.offlineBaseUrl,
                apiKey = dh.offlineApiKey,
                model = dh.offlineModel,
                enableTools = false,
                maxToolRounds = 0,
                temperature = 0.7f,
                maxTokens = 4096,
            )
        } else {
            QuroModelConfigRepository(ctx).load()
        }
    }

    var phase by remember { mutableStateOf("idle") } // idle/listening/recognizing/thinking/speaking/error
    var statusText by remember { mutableStateOf("点击话筒说话，或在下方输入文字与数字人对话") }
    var mouthOpen by remember { mutableStateOf(0f) }
    var inputText by remember { mutableStateOf("") }
    val transcript = remember { mutableStateListOf<Pair<String, String>>() }

    val listening = AtomicBoolean(false)
    val setPhase: (String) -> Unit = { phase = it }
    val setStatus: (String) -> Unit = { statusText = it }
    val setMouth: (Float) -> Unit = { mouthOpen = it }

    val glbLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            val path = copyGlbToCache(ctx, uri)
            if (path != null) {
                dh = dh.copy(avatarSource = "custom", customModelPath = path)
                repo.save(dh)
                setStatus("已选择自定义 3D 模型，点击话筒即可对话")
            } else {
                setStatus("GLB 文件拷贝失败，请换一个文件重试")
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startListeningSession(ctx, scope, listening, setPhase, setStatus) { text ->
                if (text.isNotBlank()) ask(ctx, vm, scope, transcript, setMouth, setPhase, setStatus, text, effectiveCfg)
                else { setPhase("idle"); setStatus("没听清，请重试") }
            }
        } else {
            setStatus("需要录音权限才能进行语音输入")
        }
    }

    fun startListeningTurn() {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        val source = QuroSttPrefs.getSource(ctx)
        if (source == QuroSttPrefs.SOURCE_ONDEVICE) {
            // 端侧离线识别（sherpa-ncnn）
            startListeningSession(ctx, scope, listening, setPhase, setStatus) { text ->
                if (text.isNotBlank()) ask(ctx, vm, scope, transcript, setMouth, setPhase, setStatus, text, effectiveCfg)
                else { setPhase("idle"); setStatus("没听清，请重试") }
            }
            return
        }
        if (source == QuroSttPrefs.SOURCE_MODEL) {
            // AI 模型转写：端侧模型可用时复用离线识别兜底，否则提示去部署
            if (QuroOnDeviceAsr.isModelAvailable(ctx)) {
                startListeningSession(ctx, scope, listening, setPhase, setStatus) { text ->
                    if (text.isNotBlank()) ask(ctx, vm, scope, transcript, setMouth, setPhase, setStatus, text, effectiveCfg)
                    else { setPhase("idle"); setStatus("没听清，请重试") }
                }
            } else {
                setStatus("当前 STT 引擎为「AI 模型」转写，需先在「设置→语音→STT」部署端侧模型，或改用原生/离线引擎")
                setPhase("idle")
            }
            return
        }
        // 默认 SOURCE_LOCAL：原生 SpeechRecognizer，不依赖离线模型
        setPhase("listening")
        setStatus("聆听中…")
        QuroSttHolder.startListening(
            ctx,
            QuroSttPrefs.getLanguage(ctx),
            QuroSttPrefs.getPartial(ctx),
            onPartial = { p -> setPhase("listening"); setStatus(p) },
            onFinal = { text ->
                if (text.isNotBlank()) ask(ctx, vm, scope, transcript, setMouth, setPhase, setStatus, text, effectiveCfg)
                else { setPhase("idle"); setStatus("没听清，请重试") }
            },
            onError = { _, msg -> setPhase("idle"); setStatus(msg) },
        )
    }

    fun stopListening() {
        listening.set(false)
        if (phase == "listening") { setPhase("idle"); setStatus("已停止，点击话筒继续") }
    }

    val avatarFile = remember(dh.avatarSource, dh.customModelPath) {
        if (dh.avatarSource == "custom" && dh.customModelPath.isNotBlank()) File(dh.customModelPath) else null
    }

    Column(Modifier.fillMaxSize().background(cs.background)) {
        TopAppBar(
            title = { Text("数字人", style = MaterialTheme.typography.titleLarge) },
            navigationIcon = {
                IconButton(onClick = onExitToHome) { Icon(Icons.Filled.Person, null) }
            },
            actions = {
                TextButton(onClick = { showSettings = !showSettings }) { Text(if (showSettings) "收起" else "设置") }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = cs.background),
        )

        // 单一滚动容器：舞台 + 设置 + 对话记录全部在一个 LazyColumn 内；输入栏在底部固定。
        // 以往顶部舞台/设置为「固定高度」不在滚动容器，设置展开或屏幕偏矮时把 weight(1f) 的
        // 对话区压成 0 高 → 对话滑不动、输入框被挤出（布局重叠）。现改为整体滚动，根因消除。
        val listState = rememberLazyListState()
        LaunchedEffect(transcript.size) { if (transcript.isNotEmpty()) listState.scrollToItem(transcript.size - 1) }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Column(
                    Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Spacer(Modifier.height(10.dp))
                    DigitalHumanStage(
                        phase = phase,
                        avatarSource = dh.avatarSource,
                        avatarFile = avatarFile,
                        mouthOpen = mouthOpen,
                        statusText = statusText,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        AssistChip(onClick = {}, label = { Text("🔊 离线 ASR", fontSize = 11.sp) })
                        AssistChip(onClick = {}, label = { Text("🔊 离线 TTS", fontSize = 11.sp) })
                        AssistChip(
                            onClick = {},
                            label = { Text(if (dh.llmMode == "offline") "🖥 离线 LLM" else "☁ 云端口 LLM", fontSize = 11.sp) },
                        )
                    }
                    if (dh.llmMode == "offline" && !dh.isOfflineConfigured()) {
                        Spacer(Modifier.height(6.dp))
                        Text("离线模式未配置本地端点：点右上「设置」填写 baseUrl / model", fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }

            if (showSettings) {
                item {
                    DigitalHumanSettingsCard(
                        dh = dh,
                        onModeChange = { m -> dh = dh.copy(llmMode = m); repo.save(dh) },
                        onOfflineChange = { b, k, mo -> dh = dh.copy(offlineBaseUrl = b, offlineApiKey = k, offlineModel = mo); repo.save(dh) },
                        onAvatarChange = { a -> dh = dh.copy(avatarSource = a); repo.save(dh) },
                        onPickGlb = { glbLauncher.launch(arrayOf("model/gltf-binary", "application/octet-stream", "*/*")) },
                    )
                }
            }

            // 对话记录（沉浸式气泡舞台）
            items(transcript) { (who, msg) ->
                val isUser = who == "你"
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start) {
                    Surface(
                        color = if (isUser) Accent else AccentSoft,
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Text(
                            msg,
                            fontSize = 13.sp,
                            color = if (isUser) Color.White else cs.onSurface,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        }

        // 输入区：语音 + 文字（沉浸式舞台栏）
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(Card)
                .border(1.dp, Line, RoundedCornerShape(18.dp))
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            FloatingActionButton(
                onClick = { if (phase == "listening") stopListening() else startListeningTurn() },
                containerColor = if (phase == "listening") MaterialTheme.colorScheme.error else Accent,
                modifier = Modifier.size(56.dp),
            ) {
                Icon(
                    if (phase == "listening") Icons.Filled.Stop else Icons.Filled.Mic,
                    null,
                    tint = Color.White,
                )
            }
            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                placeholder = { Text("输入文字与数字人对话…") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = { if (inputText.isNotBlank()) { val t = inputText; inputText = ""; ask(ctx, vm, scope, transcript, setMouth, setPhase, setStatus, t, effectiveCfg) } },
                enabled = inputText.isNotBlank() && phase != "speaking",
            ) {
                Icon(Icons.Filled.Send, null, tint = Accent)
            }
        }
    }
}

/** 沉浸式对话舞台：阶段环 + 头像 + 阶段胶囊 + 状态文案。 */
@Composable
private fun DigitalHumanStage(
    phase: String,
    avatarSource: String,
    avatarFile: File?,
    mouthOpen: Float,
    statusText: String,
) {
    val cs = MaterialTheme.colorScheme
    val (pLabel, pColor) = phaseMeta(phase)

    // Live2D 全屏模式
    if (avatarSource == "live2d") {
        Box(Modifier.fillMaxSize().background(cs.background)) {
            Live2DAvatarView(mouthOpen = mouthOpen, phase = phase)
            // 状态覆盖层
            Surface(
                color = cs.surface.copy(alpha = 0.85f),
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
            ) {
                Column(
                    Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Surface(color = pColor.copy(alpha = 0.14f), shape = RoundedCornerShape(999.dp)) {
                        Text(pLabel, fontSize = 12.sp, color = pColor, modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp), fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(statusText, fontSize = 13.sp, color = Muted, maxLines = 2, textAlign = TextAlign.Center)
                }
            }
        }
        return
    }

    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(cs.surfaceVariant.copy(alpha = 0.5f))
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PhaseRing(phase = phase, avatarSource = avatarSource, avatarFile = avatarFile, mouthOpen = mouthOpen)
        Spacer(Modifier.height(14.dp))
        Surface(color = pColor.copy(alpha = 0.14f), shape = RoundedCornerShape(999.dp)) {
            Text(pLabel, fontSize = 12.sp, color = pColor, modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp), fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(8.dp))
        Text(statusText, fontSize = 13.sp, color = Muted, maxLines = 2, textAlign = TextAlign.Center)
    }
}

/** 阶段指示环：随对话阶段点亮进度弧，中央承载头像。 */
@Composable
private fun PhaseRing(phase: String, avatarSource: String, avatarFile: File?, mouthOpen: Float) {
    val ringColor = when (phase) {
        "listening" -> Color(0xFFEF4444)
        "speaking" -> Color(0xFF3B82F6)
        "thinking", "recognizing" -> Accent
        "error" -> Color(0xFFE53935)
        else -> Muted
    }
    val progress = when (phase) {
        "listening" -> 0.2f
        "recognizing" -> 0.4f
        "thinking" -> 0.6f
        "speaking" -> 0.85f
        "error" -> 1f
        else -> 0.05f
    }
    // 自定义 GLB 头像给更大的展示框（横向填满舞台、纵向 320dp），避免被固定 220dp 小框裁掉头部；内置 2.5D 维持 248/220。
    val isCustom = avatarSource == "custom"
    Box(if (isCustom) Modifier.fillMaxWidth().height(320.dp) else Modifier.size(248.dp), contentAlignment = Alignment.Center) {
        // 进度环只给内置 2.5D 头像用；自定义 GLB 头像本身已很完整，套圈会像瞄准镜，故不画。
        if (!isCustom) {
            Canvas(Modifier.fillMaxSize()) {
                val cx = size.width / 2
                val cy = size.height / 2
                val r = size.minDimension / 2 - 10.dp.toPx()
                drawCircle(color = ringColor.copy(alpha = 0.16f), radius = r, center = Offset(cx, cy), style = Stroke(6.dp.toPx()))
                drawArc(
                    color = ringColor,
                    startAngle = -90f,
                    sweepAngle = progress * 360f,
                    useCenter = false,
                    topLeft = Offset(cx - r, cy - r),
                    size = Size(r * 2, r * 2),
                    style = Stroke(6.dp.toPx(), cap = StrokeCap.Round),
                )
            }
        }
        Box(if (isCustom) Modifier.fillMaxSize() else Modifier.size(220.dp), contentAlignment = Alignment.Center) {
            if (isCustom) GLBAvatarView(avatarFile, mouthOpen, phase)
            else DigitalHumanAvatar(mouthOpen = mouthOpen, phase = phase)
        }
    }
}

/** 阶段 → 中文标签 + 强调色。 */
private fun phaseMeta(phase: String): Pair<String, Color> = when (phase) {
    "listening" -> "聆听中" to Color(0xFFEF4444)
    "recognizing" -> "识别中" to Accent
    "thinking" -> "思考中" to Accent
    "speaking" -> "回复中" to Color(0xFF3B82F6)
    "error" -> "出错了" to Color(0xFFE53935)
    else -> "待命中" to Muted
}

@Composable
private fun DigitalHumanSettingsCard(
    dh: QuroDigitalHumanConfig,
    onModeChange: (String) -> Unit,
    onOfflineChange: (String, String, String) -> Unit,
    onAvatarChange: (String) -> Unit,
    onPickGlb: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    var ob by remember(dh.offlineBaseUrl) { mutableStateOf(dh.offlineBaseUrl) }
    var ok by remember(dh.offlineApiKey) { mutableStateOf(dh.offlineApiKey) }
    var om by remember(dh.offlineModel) { mutableStateOf(dh.offlineModel) }

    SetGroup {
        Text("LLM 来源", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
        Row(Modifier.padding(horizontal = 16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = dh.llmMode == "cloud", onClick = { onModeChange("cloud") }, label = { Text("☁ 云端口") })
            FilterChip(selected = dh.llmMode == "offline", onClick = { onModeChange("offline") }, label = { Text("🖥 离线本地端点") })
        }
        if (dh.llmMode == "offline") {
            Spacer(Modifier.height(8.dp))
            UnderlineField(label = "本地端点 baseUrl", value = ob, onValueChange = { ob = it; onOfflineChange(ob, ok, om) }, placeholder = "http://127.0.0.1:1234/v1")
            Spacer(Modifier.height(8.dp))
            UnderlineField(label = "API Key（可空）", value = ok, onValueChange = { ok = it; onOfflineChange(ob, ok, om) }, placeholder = "sk-...", isSecret = true)
            Spacer(Modifier.height(8.dp))
            UnderlineField(label = "模型名（如 llava / qwen2.5）", value = om, onValueChange = { om = it; onOfflineChange(ob, ok, om) }, placeholder = "qwen2.5")
        }
        Spacer(Modifier.height(12.dp))
        HorizontalDivider()
        Spacer(Modifier.height(12.dp))
        Text("头像来源", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
        Row(Modifier.padding(horizontal = 16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = dh.avatarSource == "builtin", onClick = { onAvatarChange("builtin") }, label = { Text("内置 2.5D") })
            FilterChip(selected = dh.avatarSource == "custom", onClick = { onAvatarChange("custom") }, label = { Text("自定义 GLB") })
            FilterChip(selected = dh.avatarSource == "live2d", onClick = { onAvatarChange("live2d") }, label = { Text("Live2D") })
        }
        if (dh.avatarSource == "custom") {
            Spacer(Modifier.height(10.dp))
            Box(Modifier.padding(horizontal = 16.dp)) {
                PrimaryButton(
                    text = if (dh.customModelPath.isNotBlank()) "重新选择 GLB 模型" else "选择 GLB 模型文件",
                    onClick = onPickGlb,
                )
            }
            if (dh.customModelPath.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text("已载入：${dh.customModelPath.substringAfterLast("/")}", fontSize = 11.sp, color = Muted, modifier = Modifier.padding(horizontal = 16.dp))
            }
            Spacer(Modifier.height(4.dp))
            Text("自定义 3D 预览已内置离线 Three.js 引擎（assets/www/three/），无需联网即可渲染。", fontSize = 11.sp, color = Muted, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        }
        if (dh.avatarSource == "live2d") {
            Spacer(Modifier.height(8.dp))
            Text("Live2D 模型全屏显示，口型随语音同步。默认搭载 Hiyori 模型（Live2D Open Software License）。", fontSize = 11.sp, color = Muted, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        }
    }
}

// ===================== 文件级逻辑 =====================

private fun copyGlbToCache(ctx: Context, uri: Uri): String? = runCatching {
    ctx.contentResolver.openInputStream(uri)?.use { ins ->
        val out = File(ctx.cacheDir, "quro_dh_model.glb")
        out.outputStream().use { os -> ins.copyTo(os) }
        out.absolutePath
    }
}.getOrNull()

/** 从 assets 读取文本内容；失败返回 null 并记录诊断日志。 */
private fun readAssetText(ctx: Context, path: String): String? = runCatching {
    ctx.assets.open(path).bufferedReader().use { it.readText() }
}.onFailure { QuroDiag.log("GLB", "读取 assets/$path 失败：${it.message}") }.getOrNull()

/** 把 Draco 解码器（wasm + js 胶水）从 assets 提取到 cacheDir/three/draco/，供离线 GLTFLoader 解码 Draco 压缩模型。 */
private fun extractDracoAssets(ctx: Context): Boolean = runCatching {
    val outDir = File(ctx.cacheDir, "three/draco")
    outDir.mkdirs()
    for (name in listOf("draco_decoder.js", "draco_wasm_wrapper.js", "draco_decoder.wasm")) {
        val out = File(outDir, name)
        if (!out.exists()) {
            ctx.assets.open("www/three/draco/$name").use { ins -> out.outputStream().use { os -> ins.copyTo(os) } }
        }
    }
    true
}.onFailure { QuroDiag.log("GLB", "Draco 资源提取失败：${it.message}") }.getOrDefault(false)

private fun estimatedSpeakMs(text: String): Long = (text.length * 200L).coerceAtLeast(900L)

private fun animateMouth(scope: CoroutineScope, setMouth: (Float) -> Unit, ms: Long) {
    scope.launch {
        val start = System.currentTimeMillis()
        while (isActive && System.currentTimeMillis() - start < ms) {
            val t = (System.currentTimeMillis() - start) / 1000.0
            val open = (0.5 + 0.5 * kotlin.math.sin(t * 2 * Math.PI * 5.0)).toFloat()
            val env = 0.55f + 0.45f * kotlin.math.sin(t * 2 * Math.PI * 0.8).toFloat()
            setMouth((open * env).coerceIn(0f, 1f))
            delay(38)
        }
        setMouth(0f)
    }
}

private fun speakReply(
    ctx: Context,
    scope: CoroutineScope,
    setMouth: (Float) -> Unit,
    setPhase: (String) -> Unit,
    setStatus: (String) -> Unit,
    reply: String,
) {
    if (reply.isBlank()) { setPhase("idle"); setStatus("点击话筒继续"); return }
    setPhase("speaking")
    setStatus("数字人正在说…")
    scope.launch {
        val animJob = launch { animateMouth(scope, setMouth, estimatedSpeakMs(reply)) }
        runCatching { QuroTtsHolder.ensureReady(ctx) }
        // ★ 一次性守卫：speak 与 speakMinimal 共用同一 done；speak 返回 -1/-2 时内部已【同步】触发 done，
        // 随后因 rc!=0 又调 speakMinimal(同 done)，末尾 if(rc2!=0) done() 还可能再调一次 → done 最多被执行 3 次
        // （setPhase("idle")/animJob.cancel() 重复执行）。与语音球 AtomicBoolean 守卫对齐，确保只复位一次。
        val fired = java.util.concurrent.atomic.AtomicBoolean(false)
        val done: () -> Unit = {
            if (fired.compareAndSet(false, true)) {
                setMouth(0f)
                animJob.cancel()
                setPhase("idle")
                setStatus("点击话筒继续")
            }
        }
        // 主路径 speak 失败（未就绪/引擎异常）时回退到安全模式 speakMinimal；
        // 任一成功都通过 onDone 复位相位，两条都失败则直接复位，避免卡在 speaking。
        val rc = runCatching { QuroTtsHolder.speak(reply, done) }.getOrDefault(-2)
        if (rc != 0) {
            val rc2 = runCatching { QuroTtsHolder.speakMinimal(reply, done) }.getOrDefault(-2)
            if (rc2 != 0) done()
        }
        animJob.join()
    }
}

private fun ask(
    ctx: Context,
    vm: QuroChatViewModel,
    scope: CoroutineScope,
    transcript: SnapshotStateList<Pair<String, String>>,
    setMouth: (Float) -> Unit,
    setPhase: (String) -> Unit,
    setStatus: (String) -> Unit,
    text: String,
    cfg: QuroModelConfig,
) {
    val t = text.trim()
    if (t.isBlank()) return
    transcript.add("你" to t)
    setPhase("thinking")
    setStatus("数字人思考中…")
    scope.launch {
        val reply = runCatching { vm.voiceBallTurn(t, cfg) }
            .getOrDefault("⚠️ 出错了，请检查模型配置（离线模式请确认本地端点可用）")
            .toString()
        // 显示用「干净文本」（剥离 (开心) 等情绪标签）；语音合成用「原始(带标签)文本」：
        // 云/MiMo 源会逐段解析标签做情感合成，本地源在 QuroTtsHolder 内自动剥离（无情感但至少显示干净）。
        // 之前数字人气泡直接 Text(reply) 露出原始标签，是「数字人不会用情绪标签」的可见根因。
        val replyDisplay = QuroVoiceStyle.strip(reply)
        transcript.add("数字人" to replyDisplay)
        speakReply(ctx, scope, setMouth, setPhase, setStatus, reply)
    }
}

private fun startListeningSession(
    ctx: Context,
    scope: CoroutineScope,
    listening: AtomicBoolean,
    setPhase: (String) -> Unit,
    setStatus: (String) -> Unit,
    onResult: (String) -> Unit,
) {
    scope.launch(Dispatchers.IO) {
        runListening(ctx, listening, { p, s ->
            Handler(Looper.getMainLooper()).post { setPhase(p); setStatus(s) }
        }, onResult)
    }
}

/**
 * 离线录音 → 端侧识别一轮。结果通过 [onResult] 回传主线程；[onUi] 用于回传阶段/状态文案。
 */
private suspend fun runListening(
    context: Context,
    active: AtomicBoolean,
    onUi: (String, String) -> Unit,
    onResult: (String) -> Unit,
) {
    if (!QuroOnDeviceAsr.isReady()) {
        onUi("listening", "端侧模型加载中…")
        if (!QuroOnDeviceAsr.ensureLoaded(context.applicationContext)) {
            onUi("error", "端侧模型加载失败，请到 STT 设置重试")
            return
        }
    }
    val minBuf = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
    if (minBuf <= 0) { onUi("error", "录音初始化失败"); return }
    val rec = try {
        AudioRecord(MediaRecorder.AudioSource.MIC, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 2)
    } catch (e: Throwable) { onUi("error", "无法创建录音器"); return }
    if (rec.state != AudioRecord.STATE_INITIALIZED) {
        try { rec.release() } catch (_: Throwable) {}
        onUi("error", "录音器不可用")
        return
    }

    val pcm = ByteArrayOutputStream()
    try {
        val frame = ShortArray(minBuf / 2)
        rec.startRecording()
        val startMs = System.currentTimeMillis()
        var lastVoiceMs = startMs
        while (active.get()) {
            val n = rec.read(frame, 0, frame.size)
            if (n <= 0) { if (n < 0) break; continue }
            val buf = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until n) buf.putShort(frame[i])
            pcm.write(buf.array())
            var sum = 0.0
            for (i in 0 until n) sum += frame[i] * frame[i]
            val rms = sqrt(sum / n) / 32768.0
            val now = System.currentTimeMillis()
            if (rms > 0.012) lastVoiceMs = now
            val dur = now - startMs
            if (dur > 400 && now - lastVoiceMs > 1200 && pcm.size() > 16000 * 16 / 8 * 0.3f) break
            if (dur > 30000) break
        }
        rec.stop()
    } catch (e: Throwable) {
        onUi("error", "录音异常：${e.message}")
    } finally {
        try { rec.release() } catch (_: Throwable) {}
    }
    active.set(false)
    if (pcm.size() <= 16000 * 16 / 8 * 0.3f) {
        onUi("idle", "没听清，请重试")
        return
    }
    onUi("recognizing", "识别中…")
    val text = QuroOnDeviceAsr.recognize(pcm.toByteArray())
    withContext(Dispatchers.Main) {
        if (text.isNotBlank()) onResult(text) else onUi("idle", "没听清，请重试")
    }
}

/** 内置 2.5D 风格数字人头像：体积光渐变 + 眨眼 + 口型同步（简化 A2BS 渲染层）。 */
@Composable
private fun DigitalHumanAvatar(mouthOpen: Float, phase: String) {
    val cs = MaterialTheme.colorScheme
    val tick = remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        while (isActive) { tick.value = System.currentTimeMillis(); delay(60) }
    }
    val t = (tick.value % 100000L) / 1000.0
    Canvas(Modifier.size(220.dp)) {
        val c = size.minDimension / 2
        val cx = size.width / 2
        val cy = size.height / 2
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Accent.copy(alpha = 0.95f), Accent.copy(alpha = 0.45f), Accent.copy(alpha = 0.12f)),
                center = Offset(cx - c * 0.3f, cy - c * 0.3f),
                radius = c * 1.25f,
            ),
            radius = c,
            center = Offset(cx, cy),
        )
        drawCircle(color = Accent.copy(alpha = 0.55f), radius = c, center = Offset(cx, cy), style = Stroke(2.dp.toPx()))
        val eyeY = cy - c * 0.12f
        val eyeDX = c * 0.34f
        val eyeR = c * 0.10f
        val blinking = ((t * 0.7) % 4.0) < 0.12
        for (dir in listOf(-1, 1)) {
            val ex = cx + dir * eyeDX
            if (blinking) {
                drawLine(
                    color = cs.onSurface,
                    start = Offset(ex - eyeR, eyeY),
                    end = Offset(ex + eyeR, eyeY),
                    strokeWidth = 3.dp.toPx(),
                )
            } else {
                drawOval(
                    color = cs.onSurface,
                    topLeft = Offset(ex - eyeR, eyeY - eyeR * 0.7f),
                    size = Size(eyeR * 2, eyeR * 1.4f),
                )
            }
        }
        val mouthY = cy + c * 0.34f
        val mouthW = c * 0.5f
        val mouthH = (c * 0.05f) + mouthOpen * c * 0.30f
        drawOval(
            color = cs.onSurface,
            topLeft = Offset(cx - mouthW / 2, mouthY - mouthH / 2),
            size = Size(mouthW, mouthH),
        )
        if (phase == "thinking") {
            val p = (t * 3) % 1
            drawCircle(
                color = Accent.copy(alpha = 0.85f),
                radius = c * 0.07f,
                center = Offset(cx, cy - c * 0.78f - (p * 8.dp.toPx()).toFloat()),
            )
        }
    }
}

/** 用户自制 GLB 3D 头像：WebView + 离线内联 Three.js 渲染，口型以整体缩放模拟同步。 */
@Composable
private fun GLBAvatarView(modelFile: File?, mouthOpen: Float, phase: String) {
    val pathKey = modelFile?.absolutePath ?: ""
    key(pathKey) {
        AndroidView(
            // 填满外层头像框（自定义 GLB 用 300dp），不再锁死 220dp 也不做圆角裁剪，避免头部被固定小框裁掉。
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                // 诊断：把 JS 控制台与关键节点写入 Download/QuroAI_logs/，用户无需 adb 即可定位问题。
                QuroDiag.log("GLB", "WebView 创建；modelFile=${modelFile?.absolutePath ?: "null"}；exists=${modelFile?.exists() ?: false}；bytes=${modelFile?.let { runCatching { it.length() }.getOrNull() } ?: 0}")
                WebView(ctx).apply {
                    // 强制硬件加速层：Android WebView 跑 WebGL 必须走 GPU 合成，否则画面不显示（全黑）。
                    setLayerType(View.LAYER_TYPE_HARDWARE, null)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = true
                    // 离线加载 Draco 解码器 wasm 需要放开 file:// 跨文件访问（deprecated 但当前 WebView 仍生效）
                    @Suppress("DEPRECATION")
                    settings.allowFileAccessFromFileURLs = true
                    @Suppress("DEPRECATION")
                    settings.allowUniversalAccessFromFileURLs = true
                    // 透明背景：让 HTML 渲染前的短暂瞬间透出舞台，最终由 WebGL 透明画布接管。
                    setBackgroundColor(0)
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            QuroDiag.log("GLB", "页面加载完成：$url")
                            super.onPageFinished(view, url)
                        }
                    }
                    webChromeClient = object : WebChromeClient() {
                        override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                            val lvl = when (m.messageLevel()) {
                                ConsoleMessage.MessageLevel.ERROR -> "E"
                                ConsoleMessage.MessageLevel.WARNING -> "W"
                                else -> "I"
                            }
                            QuroDiag.log("GLB-JS", "[$lvl] ${m.message()} @${m.lineNumber()}")
                            return super.onConsoleMessage(m)
                        }
                    }
                    if (modelFile != null && modelFile.exists()) {
                        val threeJs = readAssetText(ctx, "www/three/three.min.js")
                        val bgUtils = readAssetText(ctx, "www/three/BufferGeometryUtils.js")
                        val gltfLoader = readAssetText(ctx, "www/three/GLTFLoader.js")
                        val dracoLoader = readAssetText(ctx, "www/three/DRACOLoader.js")
                        val orbit = readAssetText(ctx, "www/three/OrbitControls.js")
                        if (threeJs == null || gltfLoader == null || orbit == null) {
                            QuroDiag.log("GLB", "离线引擎 assets 读取失败；threeJs=$threeJs gltfLoader=$gltfLoader orbit=$orbit")
                            loadDataWithBaseURL(null, "<body style='margin:0;background:#15151a;color:#fff;font-family:sans-serif;display:flex;align-items:center;justify-content:center;font-size:13px;text-align:center;padding:16px'>离线 3D 引擎缺失<br>请确认 APK 包含 assets/www/three/</body>", "text/html", "utf-8", null)
                        } else {
                            val dracoOk = extractDracoAssets(ctx)
                            val dracoPath = "file://" + File(ctx.cacheDir, "three/draco").absolutePath + "/"
                            QuroDiag.log("GLB", "Draco 提取=$dracoOk；dracoPath=$dracoPath")
                            val b64 = Base64.encodeToString(modelFile.readBytes(), Base64.NO_WRAP)
                            val htmlFile = File(ctx.cacheDir, "quro_dh_gltf.html")
                            htmlFile.writeText(buildGltfHtml(b64, threeJs, bgUtils ?: "", gltfLoader, dracoLoader ?: "", dracoPath, orbit))
                            QuroDiag.log("GLB", "HTML 已写入 ${htmlFile.absolutePath}；engine.len=${threeJs.length}；b64.len=${b64.length}")
                            loadUrl("file://" + htmlFile.absolutePath)
                        }
                    } else {
                        loadDataWithBaseURL(null, "<body style='margin:0;background:#15151a;color:#fff;font-family:sans-serif;display:flex;align-items:center;justify-content:center;font-size:13px'>未选择 GLB 模型</body>", "text/html", "utf-8", null)
                    }
                }
            },
            update = { wv ->
                wv.evaluateJavascript("if(window.__setBlend)window.__setBlend(${mouthOpen});", null)
            }
        )
    }
}

/**
 * 生成内嵌 base64 GLB 的 Three.js 预览页。
 *
 * 关键修复（「上传模型被挡住/看不到」根因）：
 * 1) 渲染库（three / GLTFLoader / BufferGeometryUtils）改为【离线内联】到单个 HTML，
 *    不依赖 file:// 相对路径或 CDN，彻底摆脱网络与跨源限制。
 * 2) 相机按模型包围球自适应取景（lookAt 中心 + 距离随半径计算），保证整体
 *    可见、居中，解决「位置固定(0,1,3)导致大模型被截顶/挤出画面」的遮挡感。
 * 3) WebView 设深色兜底背景，避免引擎未就绪时露出底层浅色舞台。
 * 4) 控制台日志回传 Kotlin（WebViewClient/WebChromeClient）→ QuroDiag 落盘，
 *    用户无需 adb 即可在 Download/QuroAI_logs/ 看到引擎/模型加载结果。
 */
private fun buildGltfHtml(
    base64: String,
    threeJs: String,
    bgUtils: String,
    gltfLoader: String,
    dracoLoader: String,
    dracoPath: String,
    orbit: String,
): String = """
<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
<style>html,body{margin:0;padding:0;height:100%;background:radial-gradient(circle at 50% 35%,#2b2b33,#15151a);overflow:hidden}#c{position:fixed;top:0;left:0;width:100%;height:100%;display:block;touch-action:none}#msg{position:fixed;left:0;right:0;bottom:0;color:#fff;font-family:sans-serif;font-size:12px;padding:6px 8px;background:rgba(0,0,0,.6);text-align:center;z-index:9}</style>
</head><body><canvas id="c"></canvas><div id="msg">正在加载 3D 引擎…</div>
<script>
__THREE__
</script>
<script>
__BGUTILS__
</script>
<script>
__GLTFLOADER__
</script>
<script>
__DRACOLOADER__
</script>
<script>
__ORBIT__
</script>
<script>
function showMsg(t,err){var m=document.getElementById('msg');if(m){m.textContent=t;m.style.display='block';m.style.background=err?'rgba(170,30,30,.85)':'rgba(0,0,0,.6)';}}
window.onerror=function(msg,src,line,col){showMsg('脚本错误：'+msg+' @'+line,true);console.error('ONERROR '+msg+' @'+line);return true;};
function boot(){
 try{
  if(typeof THREE==='undefined'){showMsg('Three.js 未加载（离线引擎缺失）',true);console.error('THREE-undefined');return;}
  console.log('THREE-REV '+(THREE.REVISION||'?'));
  var canvas=document.getElementById('c');
  var W=canvas.clientWidth||window.innerWidth||220,H=canvas.clientHeight||window.innerHeight||220;console.log('SIZE_INIT cw='+canvas.clientWidth+' ch='+canvas.clientHeight+' iw='+window.innerWidth+' ih='+window.innerHeight);
  var renderer;
  // 透明渲染：画布清屏 alpha=0，让 WebView 透明背景下的数字人舞台自然透出。
  // 之前黑屏根因是 canvas 高度塌成 0（position:fixed + 尺寸日志已证实），不是透明合成问题。
  try{renderer=new THREE.WebGLRenderer({canvas:canvas,alpha:true,antialias:true});}
  catch(e){showMsg('WebGL 不可用：'+(e&&e.message?e.message:e),true);console.error('WEBGL_FAIL '+(e&&e.message?e.message:e));return;}
  renderer.setClearColor(0x000000,0);
  renderer.setPixelRatio(Math.min(window.devicePixelRatio||1,2));
  renderer.setSize(W,H,false);
  try{renderer.outputEncoding=THREE.sRGBEncoding;}catch(e){console.warn('OENC '+(e&&e.message));}
  var scene=new THREE.Scene();
  var camera=new THREE.PerspectiveCamera(45,W/H,0.01,1000);
  // 轨道控制器：让用户手动拖拽旋转/双指缩放，自行转到正面、看清所有部位。
  // 不再硬编码某个朝向（之前猜 180° 仍显示背面，根因是模型正面方向未知）。缺失则降级为固定视角。
  var controls=null;
  try{ if(THREE.OrbitControls){controls=new THREE.OrbitControls(camera,renderer.domElement);controls.target.set(0,0,0);controls.enableDamping=true;controls.dampingFactor=0.08;controls.enablePan=false;controls.enableZoom=true;controls.enableRotate=true;controls.autoRotate=false;controls.rotateSpeed=0.9;controls.zoomSpeed=0.9;controls.minDistance=0.1;controls.maxDistance=100;console.log('ORBIT_OK');}else{console.warn('ORBIT_MISSING');} }catch(e){console.warn('ORBIT_FAIL '+(e&&e.message?e.message:e));}
  scene.add(new THREE.AmbientLight(0xffffff,1.4));
  var dl=new THREE.DirectionalLight(0xffffff,1.2);dl.position.set(2,3,2);scene.add(dl);
  var dl2=new THREE.DirectionalLight(0xffffff,0.5);dl2.position.set(-2,-1,-2);scene.add(dl2);
  // 环境贴图：金属度/物理材质若无 IBL 会渲染成纯黑（在深色舞台上=“看不见”）。用 PMREM 生成一张
  // 灰色环境，让 PBR 材质有反射，避免“模型加载了但一片黑”的常见误判。背景仍透明（alpha）。
  try{var pmrem=new THREE.PMREMGenerator(renderer);var envScene=new THREE.Scene();envScene.background=new THREE.Color(0xbfc4cc);scene.environment=pmrem.fromScene(envScene,0.5).texture;console.log('ENV_OK');}catch(e){console.warn('ENV_FAIL '+(e&&e.message?e.message:e));}
  var loader=new THREE.GLTFLoader();
  try{var draco=new THREE.DRACOLoader();draco.setDecoderPath('__DRACO__');loader.setDRACOLoader(draco);console.log('DRACO_READY');}
  catch(e){console.warn('DRACO_SETUP '+(e&&e.message?e.message:e));}
  var model=null;
  window.__setBlend=function(open){if(model){model.scale.setScalar(model.userData.baseScale*(1+open*0.06));}};
  function fit(){
    var box=new THREE.Box3().setFromObject(model);
    if(!isFinite(box.min.x)||box.min.x===box.max.x){console.warn('FIT_degenerate');return;}
    var center=box.getCenter(new THREE.Vector3());
    var size=box.getSize(new THREE.Vector3());
    model.position.sub(center);
    var maxd=Math.max(size.x,size.y,size.z)||1;
    var sc=2.0/maxd;model.userData.baseScale=sc;model.scale.setScalar(sc);
    // 用真实画布宽高比，避免 WebView 实际高度与宽度不一致导致垂直视场被压、头部被推出画面
    var cw=canvas.clientWidth||window.innerWidth||W;
    var ch=canvas.clientHeight||window.innerHeight||H;
    camera.aspect=cw/ch;
    var fov=camera.fov*Math.PI/180;
    // 完整框入模型（盒高与半宽），各留 1.5 倍边距，确保头脚都在画面内；缩放范围也按此距离
    var halfH=size.y*sc*0.5, halfW=size.x*sc*0.5;
    var distV=halfH/Math.tan(fov/2)*1.5;
    var distH=(halfW/Math.tan(fov/2))/camera.aspect*1.5;
    var dist=Math.max(distV,distH);
    if(!isFinite(dist)||dist<=0)dist=3;
    // 相机从 +Z 略上方看向原点（模型中心），整身入镜
    var dirv=new THREE.Vector3(0,0.08,1).normalize().multiplyScalar(dist);
    camera.position.copy(dirv);
    camera.near=Math.max(dist*0.1,0.01);
    camera.far=dist+size.y*sc*2;
    camera.lookAt(0,0,0);
    camera.updateProjectionMatrix();
    if(controls){controls.target.set(0,0,0);controls.minDistance=Math.max(dist*0.3,0.1);controls.maxDistance=dist*4;controls.update();}
    console.log('FIT cw='+cw+' ch='+ch+' aspect='+camera.aspect.toFixed(3)+' dist='+dist.toFixed(3));
  }
  function resize(){var w=canvas.clientWidth||window.innerWidth||W,h=canvas.clientHeight||window.innerHeight||H;if(w&&h){if(canvas.width!==w||canvas.height!==h){renderer.setSize(w,h,false);camera.aspect=w/h;camera.updateProjectionMatrix();console.log('SIZE_RESIZE w='+w+' h='+h);}}}
  // 去掉自动旋转；数字人应稳定面向用户，由 OrbitControls 让用户手动旋转/缩放，TTS 说话时 mouthOpen 驱动整体缩放做口型同步。
  function loop(){resize();if(controls)controls.update();renderer.render(scene,camera);requestAnimationFrame(loop);}
  loop();
  console.log('THREE-LOADED r'+(THREE.REVISION||'?'));
  var b64="__B64__";
  try{
    var bin=atob(b64);var len=bin.length;var arr=new Uint8Array(len);
    for(var i=0;i<len;i++)arr[i]=bin.charCodeAt(i);
    loader.parse(arr.buffer,'',function(gltf){
      model=gltf.scene;
      model.traverse(function(o){if(o.isMesh&&o.material){if(Array.isArray(o.material)){o.material.forEach(function(m){m.side=THREE.DoubleSide;});}else{o.material.side=THREE.DoubleSide;}}});
      fit();      scene.add(model);
      var meshes=0;model.traverse(function(o){if(o.isMesh)meshes++;});
      var m=document.getElementById('msg');if(m)m.style.display='none';
      console.log('GLB_PARSE_OK bytes='+len+' meshes='+meshes);
    },function(e){var t='模型加载失败：'+(e&&e.message?e.message:e);showMsg(t,true);console.error('GLB_PARSE_FAIL '+t);});
  }catch(err){var t='解析失败：'+err;showMsg(t,true);console.error('GLB_ERR '+t);}
 }catch(err){var t='初始化失败：'+err;showMsg(t,true);console.error('BOOT_ERR '+t);}
}
if(document.readyState==='complete'||document.readyState==='interactive'){setTimeout(boot,0);}else{window.addEventListener('DOMContentLoaded',boot);}
</script></body></html>
""".replace("__THREE__", threeJs)
  .replace("__BGUTILS__", bgUtils)
  .replace("__GLTFLOADER__", gltfLoader)
  .replace("__DRACOLOADER__", dracoLoader)
  .replace("__DRACO__", dracoPath)
  .replace("__ORBIT__", orbit)
  .replace("__B64__", base64)


// ==================== Live2D Avatar ====================

/**
 * Live2D 头像：复用 GLB 的 WebView 画布，加载 PixiJS + pixi-live2d-display 渲染 Live2D 模型。
 * 与 GLBAvatarView 共享同一个 WebView 实例，只是加载不同的 HTML 页面。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun Live2DAvatarView(mouthOpen: Float, phase: String) {
    val ctx = LocalContext.current
    val main = remember { Handler(Looper.getMainLooper()) }
    var status by remember { mutableStateOf("正在加载 Live2D 模型…") }
    var modelReady by remember { mutableStateOf(false) }
    val webViewRef = remember { mutableStateOf<WebView?>(null) }

    val bridge = remember {
        Live2dBridge(
            onReady = { emo, _, diag ->
                main.post {
                    modelReady = true
                    status = if (diag.isNotBlank()) "就绪 · $diag" else "就绪 · 模型已加载"
                }
            },
            onEmotion = { name -> main.post { status = "情绪：$name" } },
            onErrorCb = { msg -> main.post { status = "加载失败：$msg" } },
            onLogCb = { msg -> /* no-op for digital human */ },
        )
    }

    fun callJs(js: String) {
        webViewRef.value?.evaluateJavascript(js, null)
    }

    // 根据 phase 驱动 Live2D 情绪/口型
    LaunchedEffect(phase, mouthOpen) {
        if (!modelReady) return@LaunchedEffect
        val emo = when (phase) {
            "listening" -> "neutral"
            "thinking" -> "thinking"
            "speaking" -> "happy"
            "error" -> "angry"
            else -> "neutral"
        }
        callJs("window.ZorvLive2D.setEmotion('$emo')")
        // 口型：speaking 时用 mouthOpen 值，否则归零
        val mouth = if (phase == "speaking") mouthOpen.coerceIn(0f, 1f) else 0f
        callJs("window.ZorvLive2D.setMouth($mouth)")
    }

    // 复用 GLB 的 WebView 画布（同一个 WebView 实例）
    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { c ->
                WebView(c.applicationContext).apply {
                    // 强制硬件加速层：与 GLBAvatarView 对齐
                    setLayerType(View.LAYER_TYPE_HARDWARE, null)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = true
                    @Suppress("DEPRECATION")
                    settings.allowFileAccessFromFileURLs = true
                    @Suppress("DEPRECATION")
                    settings.allowUniversalAccessFromFileURLs = true
                    setBackgroundColor(0) // 透明背景，与 GLB 对齐
                    addJavascriptInterface(bridge, "ZorvBridge")
                    webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                            val url = request?.url?.toString() ?: return null
                            if (url.startsWith(LIVE2D_BASE)) {
                                val assetPath = "live2d/" + url.removePrefix(LIVE2D_BASE)
                                return try {
                                    val `is` = c.assets.open(assetPath)
                                    WebResourceResponse(
                                        mimeFor(assetPath), "utf-8", 200, "OK",
                                        mapOf("Access-Control-Allow-Origin" to "*"), `is`,
                                    )
                                } catch (_: Exception) {
                                    null
                                }
                            }
                            return null
                        }
                        override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                            bridge.reportError("${description ?: "加载错误"} ($errorCode)")
                        }
                    }
                    webChromeClient = object : WebChromeClient() {
                        override fun onConsoleMessage(m: ConsoleMessage?): Boolean {
                            m?.let { bridge.reportLog("[${it.lineNumber()}] ${it.message()}") }
                            return true
                        }
                    }
                    // 加载 Live2D 页面（与 GLB 共享同一个 WebView 实例）
                    val html = c.assets.open("live2d/index.html").bufferedReader(Charsets.UTF_8).use { it.readText() }
                    loadDataWithBaseURL(LIVE2D_BASE, html, "text/html", "utf-8", null)
                }.also { webViewRef.value = it }
            },
            onRelease = { it.destroy() },
        )
        // 加载状态
        if (!modelReady) {
            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                modifier = Modifier.align(Alignment.Center).padding(32.dp),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(
                    status,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
}

// Live2D WebView 常量
private const val LIVE2D_BASE = "https://live2d.local/live2d/"

private fun mimeFor(path: String): String = when {
    path.endsWith(".html", true) -> "text/html"
    path.endsWith(".js", true) -> "application/javascript"
    path.endsWith(".json", true) -> "application/json"
    path.endsWith(".png", true) -> "image/png"
    path.endsWith(".jpg", true) || path.endsWith(".jpeg", true) -> "image/jpeg"
    path.endsWith(".moc3", true) -> "application/octet-stream"
    path.endsWith(".exp3.json", true) -> "application/json"
    path.endsWith(".motion3.json", true) -> "application/json"
    else -> "application/octet-stream"
}
