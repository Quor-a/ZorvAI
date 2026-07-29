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
import android.webkit.WebView
import android.webkit.WebViewClient
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
    Box(Modifier.size(248.dp), contentAlignment = Alignment.Center) {
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
        Box(Modifier.size(220.dp), contentAlignment = Alignment.Center) {
            if (avatarSource == "custom") GLBAvatarView(avatarFile, mouthOpen, phase)
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
            Text("自定义 3D 预览依赖 Three.js（首次需联网加载，之后走缓存）。", fontSize = 11.sp, color = Muted, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
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

/** 用户自制 GLB 3D 头像：WebView + Three.js 渲染，口型以整体缩放模拟同步。 */
@Composable
private fun GLBAvatarView(modelFile: File?, mouthOpen: Float, phase: String) {
    val pathKey = modelFile?.absolutePath ?: ""
    key(pathKey) {
        AndroidView(
            modifier = Modifier.size(220.dp).clip(RoundedCornerShape(16.dp)),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = true
                    webViewClient = WebViewClient()
                    if (modelFile != null && modelFile.exists()) {
                        val b64 = Base64.encodeToString(modelFile.readBytes(), Base64.NO_WRAP)
                        val htmlFile = File(ctx.cacheDir, "quro_dh_gltf.html")
                        htmlFile.writeText(buildGltfHtml(b64))
                        loadUrl("file://" + htmlFile.absolutePath)
                    } else {
                        loadDataWithBaseURL(null, "<body style='margin:0;background:#222;color:#fff;font-family:sans-serif;display:flex;align-items:center;justify-content:center'>未选择 GLB 模型</body>", "text/html", "utf-8", null)
                    }
                }
            },
            update = { wv ->
                wv.evaluateJavascript("if(window.__setBlend)window.__setBlend(${mouthOpen});", null)
            }
        )
    }
}

/** 生成内嵌 base64 GLB 的 Three.js 预览页（渲染库走 CDN，首次需联网）。 */
private fun buildGltfHtml(base64: String): String = """
<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
<style>html,body{margin:0;height:100%;background:#1b1b1b;overflow:hidden}#c{width:100%;height:100%;display:block}</style>
<script type="importmap">{"imports":{"three":"https://unpkg.com/three@0.160.0/build/three.module.js","three/addons/":"https://unpkg.com/three@0.160.0/examples/jsm/"}}</script>
</head><body><canvas id="c"></canvas>
<script type="module">
import * as THREE from 'three';
import { GLTFLoader } from 'three/addons/loaders/GLTFLoader.js';
const canvas=document.getElementById('c');
const renderer=new THREE.WebGLRenderer({canvas,alpha:true,antialias:true});
renderer.setPixelRatio(window.devicePixelRatio);
const scene=new THREE.Scene();
const camera=new THREE.PerspectiveCamera(45,1,0.1,100);
camera.position.set(0,1,3);
scene.add(new THREE.AmbientLight(0xffffff,1.3));
const dir=new THREE.DirectionalLight(0xffffff,1.1); dir.position.set(2,3,2); scene.add(dir);
const loader=new GLTFLoader();
let model=null;
window.__setBlend=function(open){ if(model){ const s=1+open*0.06; model.scale.setScalar(model.userData.baseScale*(1+open*0.06)); } };
function resize(){ const w=canvas.clientWidth,h=canvas.clientHeight; if(canvas.width!==w||canvas.height!==h){renderer.setSize(w,h,false);camera.aspect=w/h;camera.updateProjectionMatrix();} }
function loop(){ resize(); if(model) model.rotation.y+=0.004; renderer.render(scene,camera); requestAnimationFrame(loop); }
loop();
const b64="__B64__";
try{
  const bin=atob(b64); const len=bin.length; const arr=new Uint8Array(len);
  for(let i=0;i<len;i++) arr[i]=bin.charCodeAt(i);
  loader.parse(arr.buffer, '', (gltf)=>{
    model=gltf.scene;
    const box=new THREE.Box3().setFromObject(model); const c=box.getCenter(new THREE.Vector3()); const size=box.getSize(new THREE.Vector3());
    const maxd=Math.max(size.x,size.y,size.z)||1;
    model.position.sub(c); model.position.y+=size.y/2;
    const sc=2.2/maxd; model.userData.baseScale=sc; model.scale.setScalar(sc);
    scene.add(model);
  }, (e)=>{ document.body.innerHTML='<p style="color:#fff;font-family:sans-serif;padding:8px">模型加载失败：'+(e&&e.message?e.message:e)+'</p>'; });
}catch(err){ document.body.innerHTML='<p style="color:#fff;font-family:sans-serif;padding:8px">解析失败：'+err+'</p>'; }
</script></body></html>
""".replace("__B64__", base64)
