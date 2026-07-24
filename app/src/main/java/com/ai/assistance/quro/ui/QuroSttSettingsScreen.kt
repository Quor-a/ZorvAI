package com.ai.assistance.quro.ui

import android.Manifest
import android.content.ClipboardManager
import android.content.ClipData
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.core.content.ContextCompat
import com.ai.assistance.quro.core.model.QuroModelConfig
import com.ai.assistance.quro.core.model.QuroModelConfigRepository
import com.ai.assistance.quro.core.model.QuroSavedProfile
import com.ai.assistance.quro.core.model.QuroSavedProfileRepository
import com.ai.assistance.quro.core.network.QuroModelListFetcher
import com.ai.assistance.quro.core.network.QuroModelListResult
import com.ai.assistance.quro.core.tools.QuroSttHolder
import com.ai.assistance.quro.core.tools.QuroSttPrefs
import com.ai.assistance.quro.core.tools.QuroOnDeviceAsr
import com.ai.assistance.quro.core.tools.QuroOnDeviceModelManager
import com.ai.assistance.quro.core.tools.QuroOnDeviceModelPrefs
import com.ai.assistance.quro.core.tools.AsrModelCatalog
import com.ai.assistance.quro.core.tools.AsrModelSpec
import com.ai.assistance.quro.core.tools.AsrModelType
import com.ai.assistance.quro.ui.theme.Accent
import com.ai.assistance.quro.ui.theme.Line
import com.ai.assistance.quro.ui.theme.Sage
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import android.os.Handler
import android.os.Looper

private val STT_LANGUAGES = listOf(
    "中文（普通话）" to "zh-CN",
    "中文（繁体）" to "zh-TW",
    "粤语" to "yue-Hant",
    "English (US)" to "en-US",
    "English (UK)" to "en-GB",
    "日本語" to "ja-JP",
    "한국어" to "ko-KR",
)

/** 下拉里的单条模型选项。 */
private data class SttModelOption(
    val ref: String,        // "active" 或已保存预设 id
    val label: String,      // 模型名
    val provider: String,   // 厂商枚举名
    val modelName: String,  // 模型 id
    val sourceLabel: String,// "当前活跃配置" / 预设名
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuroSttSettingsScreen(onBack: () -> Unit = {}) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()

    var source by remember { mutableStateOf(QuroSttPrefs.getSource(ctx)) }
    var language by remember { mutableStateOf(QuroSttPrefs.getLanguage(ctx)) }
    var partial by remember { mutableStateOf(QuroSttPrefs.getPartial(ctx)) }
    var useChat by remember { mutableStateOf(QuroSttPrefs.getUseChatCompletions(ctx)) }
    var langMenu by remember { mutableStateOf(false) }

    // ── AI 模型选择 ──
    var modelOptions by remember { mutableStateOf<List<SttModelOption>>(emptyList()) }
    var selectedRef by remember { mutableStateOf(QuroSttPrefs.getModelRef(ctx)) }
    var selectedName by remember { mutableStateOf(QuroSttPrefs.getModelName(ctx)) }
    var selectedProvider by remember { mutableStateOf(QuroSttPrefs.getModelProvider(ctx)) }
    var modelMenu by remember { mutableStateOf(false) }

    // ── 从接口刷新 ──
    var fetching by remember { mutableStateOf(false) }
    var fetchedModels by remember { mutableStateOf<List<String>>(emptyList()) }
    var fetchedProvider by remember { mutableStateOf("") }
    var fetchMenu by remember { mutableStateOf(false) }
    var fetchStatus by remember { mutableStateOf<String?>(null) }

    // ── 语音转文本测试区 ──
    var recording by remember { mutableStateOf(false) }
    var resultText by remember { mutableStateOf("") }
    var testStatus by remember { mutableStateOf<String?>(null) }

    // ── 端侧模型管理（内置目录 / 自定义链接 / 下载部署） ──
    var selectedSpecId by remember { mutableStateOf(QuroOnDeviceModelPrefs.getSelectedSpecId(ctx)) }
    var customMode by remember { mutableStateOf(QuroOnDeviceModelPrefs.getCustomMode(ctx)) }
    var customLink by remember { mutableStateOf(QuroOnDeviceModelPrefs.getCustomLink(ctx)) }
    var customType by remember { mutableStateOf(runCatching { AsrModelType.valueOf(QuroOnDeviceModelPrefs.getCustomType(ctx)) }.getOrNull() ?: AsrModelType.SENSE_VOICE) }
    var downloading by remember { mutableStateOf(false) }
    var dlDownloaded by remember { mutableStateOf(0L) }
    var dlTotal by remember { mutableStateOf(0L) }
    var dlState by remember { mutableStateOf<String?>(null) }
    var deployStatus by remember { mutableStateOf(QuroOnDeviceModelPrefs.getStatus(ctx)) }
    var deployedName by remember { mutableStateOf(QuroOnDeviceModelPrefs.getDeployedName(ctx)) }
    var deployedType by remember { mutableStateOf(QuroOnDeviceModelPrefs.getDeployedType(ctx)) }
    var specMenu by remember { mutableStateOf(false) }
    var customTypeMenu by remember { mutableStateOf(false) }

    // ── Bug 日志区域 ──
    var sttLogs by remember { mutableStateOf(listOf<String>()) }
    fun addLog(msg: String) {
        val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())
        sttLogs = sttLogs + "[$ts] $msg"
        if (sttLogs.size > 80) sttLogs = sttLogs.takeLast(60)
    }

    fun reloadModels() {
        val cfg = QuroModelConfigRepository(ctx).load()
        val profiles = QuroSavedProfileRepository(ctx).loadAll()
        val opts = mutableListOf<SttModelOption>()
        opts.add(
            SttModelOption(
                ref = "active",
                label = cfg.model.ifBlank { "(未配置)" },
                provider = cfg.provider,
                modelName = cfg.model,
                sourceLabel = "当前活跃配置",
            )
        )
        profiles.forEach { p: QuroSavedProfile ->
            opts.add(
                SttModelOption(
                    ref = p.id,
                    label = p.model.ifBlank { "(未命名模型)" },
                    provider = p.provider,
                    modelName = p.model,
                    sourceLabel = p.name.ifBlank { "已保存预设" },
                )
            )
        }
        modelOptions = opts
    }

    fun selectModel(opt: SttModelOption) {
        selectedRef = opt.ref
        selectedName = opt.modelName
        selectedProvider = opt.provider
        QuroSttPrefs.setModelSelection(ctx, opt.ref, opt.modelName, opt.provider)
        addLog("已选模型: ${opt.sourceLabel} / ${opt.modelName} / provider=${opt.provider}")
    }

    fun refreshFromApi() {
        scope.launch {
            fetching = true
            fetchStatus = null
            try {
                val cfg = if (selectedRef == "active") {
                    QuroModelConfigRepository(ctx).load()
                } else {
                    QuroSavedProfileRepository(ctx).loadAll().firstOrNull { it.id == selectedRef }
                        ?.let { QuroModelConfig(provider = it.provider, baseUrl = it.baseUrl, apiKey = it.apiKey, model = it.model) }
                        ?: QuroModelConfigRepository(ctx).load()
                }
                fetchedProvider = cfg.provider
                if (cfg.baseUrl.isBlank()) {
                    addLog("❌ baseUrl 为空，无法拉取模型")
                    fetchStatus = "baseUrl 为空"
                    fetching = false
                    return@launch
                }
                addLog("正在从 ${cfg.baseUrl} 拉取模型…")
                val res = QuroModelListFetcher().fetch(cfg.baseUrl, cfg.apiKey)
                when (res) {
                    is QuroModelListResult.Success -> {
                        fetchedModels = res.models
                        fetchStatus = "拉取成功 ${res.models.size} 个"
                        addLog("✅ 拉取成功: ${res.models.size} 个模型")
                        fetchMenu = true
                    }
                    is QuroModelListResult.Error -> {
                        fetchStatus = res.message
                        addLog("❌ 拉取失败: ${res.message}")
                    }
                }
            } catch (e: Exception) {
                fetchStatus = e.message
                addLog("❌ 异常: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                fetching = false
            }
        }
    }

    // 注册日志回调（进入页面时绑定，退出时自动解除）
    DisposableEffect(Unit) {
        QuroSttHolder.setLogCallback { msg -> addLog(msg) }
        onDispose {
            QuroSttHolder.stopListening()
            QuroSttHolder.setLogCallback(null)
        }
    }

    LaunchedEffect(Unit) {
        addLog("页面加载：开始初始化 STT")
        reloadModels()
        addLog("已加载 ${modelOptions.size} 个模型选项（活跃配置 + 已保存预设）")
    }

    /** 当前选中模型的稳定 key（与下载部署时写入的 key 一致）。 */
    fun currentKey(): String = if (customMode) {
        QuroOnDeviceModelPrefs.deployedKeyFor("custom-${customLink.hashCode()}", customLink)
    } else {
        AsrModelCatalog.byId(selectedSpecId)?.let { QuroOnDeviceModelPrefs.deployedKeyFor(it.id, it.downloadUrl) } ?: selectedSpecId
    }

    // 进入页面时按「当前选中模型」刷新端侧部署状态（选中项已从 prefs 恢复）
    fun refreshDeployStatus() {
        val key = currentKey()
        val e = QuroOnDeviceModelPrefs.getDeployedEntry(ctx, key)
        deployStatus = e?.status ?: QuroOnDeviceModelPrefs.STATUS_NONE
        deployedName = e?.name
        deployedType = e?.type?.takeIf { it != "UNKNOWN" }
    }

    LaunchedEffect(Unit) {
        refreshDeployStatus()
    }

    /** 选择模型并持久化；若该模型已部署则切换为引擎激活模型。 */
    fun selectSpec(specId: String, isCustom: Boolean) {
        selectedSpecId = specId
        customMode = isCustom
        specMenu = false
        QuroOnDeviceModelPrefs.setSelectedSpecId(ctx, specId)
        QuroOnDeviceModelPrefs.setCustomMode(ctx, isCustom)
        val key = currentKey()
        val e = QuroOnDeviceModelPrefs.getDeployedEntry(ctx, key)
        if (e?.status == QuroOnDeviceModelPrefs.STATUS_DEPLOYED) {
            QuroOnDeviceModelPrefs.setActiveKey(ctx, key)
        }
        refreshDeployStatus()
    }

    /** 下载并自动部署端侧模型（内置目录或自定义链接 + 选定类型）。 */
    fun downloadAndDeployModel() {
        val spec: AsrModelSpec = if (customMode) {
            if (customLink.isBlank()) {
                dlState = "请先粘贴模型下载链接"
                addLog("❌ 链接为空")
                return
            }
            AsrModelSpec(
                id = "custom-${customLink.hashCode()}",
                displayName = "自定义模型",
                type = customType,
                downloadUrl = customLink,
                minSizeBytes = 5_000_000,
            )
        } else {
            AsrModelCatalog.byId(selectedSpecId) ?: run {
                dlState = "未选择模型"
                addLog("❌ 未选择模型")
                return
            }
        }
        downloading = true
        dlDownloaded = 0L
        dlTotal = 0L
        dlState = "准备下载…"
        addLog("开始下载并部署端侧模型: ${spec.displayName}（${spec.type.label}）→ ${spec.downloadUrl}")
        scope.launch(Dispatchers.IO) {
            val ok = QuroOnDeviceModelManager.downloadAndDeploy(
                ctx, spec,
                onProgress = { d, t -> withContext(Dispatchers.Main) { dlDownloaded = d; dlTotal = t } },
                onState = { s -> withContext(Dispatchers.Main) { dlState = s; addLog(s) } },
            )
            withContext(Dispatchers.Main) {
                downloading = false
                refreshDeployStatus()
                if (ok) addLog("✅ 模型已部署，端侧引擎可用")
                else addLog("❌ 部署未成功，请检查链接或网络（详见上方状态）")
            }
        }
    }

    fun deleteDeployedModel() {
        val key = currentKey()
        scope.launch(Dispatchers.IO) {
            QuroOnDeviceModelPrefs.getDeployedEntry(ctx, key)?.dir?.let { dir ->
                try { java.io.File(dir).deleteRecursively() } catch (_: Throwable) {}
            }
            QuroOnDeviceModelPrefs.clearEntry(ctx, key)
            withContext(Dispatchers.Main) {
                refreshDeployStatus()
                addLog("已删除模型: $key")
            }
        }
    }

    fun startNativeListen() {
        addLog("━━━ 开始录音测试 ━━━")
        val src = QuroSttPrefs.getSource(ctx)
        addLog(
            "测试引擎: ${
                if (src == QuroSttPrefs.SOURCE_MODEL) "AI 模型（Phase1 回退原生识别）"
                else "本地识别（原生 SpeechRecognizer）"
            }"
        )
        if (src == QuroSttPrefs.SOURCE_MODEL) {
            addLog("⚠️ 已选 AI 模型，但 Phase2 模型转写未实现，本测试使用原生识别")
        }
        recording = true
        testStatus = "聆听中…"
        resultText = ""
        QuroSttHolder.startListening(
            context = ctx,
            language = QuroSttPrefs.getLanguage(ctx),
            partialResults = QuroSttPrefs.getPartial(ctx),
            onPartial = { t ->
                resultText = t
                testStatus = "聆听中…"
            },
            onFinal = { text ->
                recording = false
                resultText = text
                testStatus = if (text.isNotBlank()) "识别完成 ✅" else "没听清，再试一次"
                addLog(if (text.isNotBlank()) "最终识别: $text" else "⚠️ 未识别到文字")
            },
            onError = { code, msg ->
                recording = false
                testStatus = "识别出错: $msg ❌"
                addLog("❌ $msg")
            },
        )
    }

    fun stopNativeListen() {
        recording = false
        QuroSttHolder.stopListening()
        testStatus = "已停止"
        addLog("手动停止录音")
    }

    /** 端侧（本地模型）测试：录音最长 8 秒 → QuroOnDeviceAsr 离线识别。 */
    fun startOnDeviceTest() {
        addLog("━━━ 端侧模型测试 ━━━")
        if (!QuroOnDeviceAsr.isModelAvailable(ctx)) {
            testStatus = "未找到端侧模型 ❌"
            addLog("❌ 未找到已部署的端侧模型")
            return
        }
        recording = true
        testStatus = "聆听中（端侧）…"
        resultText = ""
        addLog("开始录音（最长 8 秒）…")
        scope.launch(Dispatchers.IO) {
            // 兜底：原生 SIGSEGV 时 Java try/catch 无法捕获，用线程级 handler 防闪退
            val prevHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { t, e ->
                android.util.Log.e("QuroSttSettings", "⚠️ 原生崩溃被拦截", e)
                // 切回主线程更新 UI（不崩 App）——用 Handler 而非 withContext（此处非协程上下文）
                Handler(Looper.getMainLooper()).post {
                    recording = false
                    testStatus = "端侧引擎异常 ❌"
                    addLog("❌ 原生层崩溃(${e.javaClass.simpleName}): ${e.message}")
                }
            }
            try {
                // 预检：打印部署目录与文件详情
                val deployDir = QuroOnDeviceAsr.getDeployedDir(ctx)
                addLog("端侧模型目录: $deployDir")
                if (deployDir != null) {
                    val dirFile = File(deployDir)
                    if (dirFile.exists()) {
                        dirFile.listFiles()?.forEach { f ->
                            addLog("  📄 ${f.name} (${f.length()} bytes)")
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            recording = false; testStatus = "模型目录不存在 ❌"; addLog("❌ 目录不存在: $deployDir")
                        }
                        return@launch
                    }
                }

                if (!QuroOnDeviceAsr.isReady()) {
                    withContext(Dispatchers.Main) { testStatus = "模型加载中…"; addLog("⏳ 加载端侧模型") }
                    addLog("调用 QuroOnDeviceAsr.ensureLoaded()（独立 :asr 进程）…")
                    if (!QuroOnDeviceAsr.ensureLoaded(ctx)) {
                        withContext(Dispatchers.Main) {
                            recording = false; testStatus = "模型加载失败 ❌"; addLog("❌ ensureLoaded 返回 false（引擎进程崩溃或模型不兼容，详见 Logcat）")
                        }
                        return@launch
                    }
                    addLog("✅ 端侧模型就绪")
                }

                val minBuf = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                if (minBuf <= 0) {
                    withContext(Dispatchers.Main) { recording = false; testStatus = "录音缓冲初始化失败 ❌" }
                    return@launch
                }
                val rec = try {
                    AudioRecord(MediaRecorder.AudioSource.MIC, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 2)
                } catch (e: Throwable) {
                    withContext(Dispatchers.Main) { recording = false; testStatus = "无法录音 ❌"; addLog("❌ ${e.message}") }
                    return@launch
                }
                if (rec.state != AudioRecord.STATE_INITIALIZED) {
                    rec.release()
                    withContext(Dispatchers.Main) { recording = false; testStatus = "录音器不可用 ❌" }
                    return@launch
                }
                val pcm = ByteArrayOutputStream()
                val frame = ShortArray(16000)
                try {
                    rec.startRecording()
                } catch (e: Throwable) {
                    rec.release()
                    withContext(Dispatchers.Main) { recording = false; testStatus = "无法开始录音 ❌"; addLog("❌ ${e.message}") }
                    return@launch
                }
                val end = System.currentTimeMillis() + 8000
                try {
                    while (System.currentTimeMillis() < end && recording) {
                        val n = rec.read(frame, 0, frame.size)
                        if (n <= 0) continue
                        val b = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
                        for (i in 0 until n) b.putShort(frame[i])
                        pcm.write(b.array())
                    }
                } finally {
                    try { rec.stop() } catch (_: Throwable) {}
                    try { rec.release() } catch (_: Throwable) {}
                }
                addLog("录音结束 (${pcm.size()} bytes)，开始识别…")
                val text = QuroOnDeviceAsr.recognize(pcm.toByteArray())
                withContext(Dispatchers.Main) {
                    recording = false
                    resultText = text
                    testStatus = if (text.isNotBlank()) "识别完成 ✅" else "没听清，再试一次"
                    addLog(if (text.isNotBlank()) "最终识别: $text" else "⚠️ 未识别到文字")
                }
            } catch (e: Throwable) {
                android.util.Log.e("QuroSttSettings", "端侧测试异常", e)
                withContext(Dispatchers.Main) {
                    recording = false
                    testStatus = "端侧测试异常 ❌"
                    addLog("❌ 异常: ${e.javaClass.simpleName}: ${e.message}")
                }
            } finally {
                // 恢复默认 handler（避免泄漏到其他协程）
                Thread.setDefaultUncaughtExceptionHandler(prevHandler)
            }
        }
    }

    // ── 云端转写测试（AI 模型引擎）────────────────────────────────────
    /** PCM 数据写入 WAV 文件。 */
    fun writeWav(pcm: ByteArray, out: File) {
        FileOutputStream(out).use { os ->
            os.write("RIFF".toByteArray())
            val totalLen = pcm.size + 36
            os.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(totalLen).array())
            os.write("WAVE".toByteArray())
            os.write("fmt ".toByteArray())
            os.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(16).array())
            os.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(1).array())       // PCM
            os.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(1).array())       // mono
            os.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(16000).array())     // sample rate
            os.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(32000).array())     // byte rate
            os.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(2).array())       // block align
            os.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(16).array())      // bits per sample
            os.write("data".toByteArray())
            os.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(pcm.size).array())
            os.write(pcm)
        }
    }

    /** 录音 → 写 WAV → 调 /audio/transcriptions API → 显示结果。 */
    fun startCloudTest() {
        addLog("━━━ 云端转写测试 ━━━")
        val cfg = QuroModelConfigRepository(ctx).load()
        if (cfg.baseUrl.isBlank()) {
            testStatus = "未配置 Base URL ❌"
            addLog("❌ 请先在模型配置页填写 API 地址")
            return
        }
        if (cfg.apiKey.isBlank()) {
            testStatus = "未配置 API Key ❌"
            addLog("❌ 请先在模型配置页填写 API Key")
            return
        }
        val modelName = QuroSttPrefs.getModelName(ctx).ifBlank { "whisper-1" }
        val provider = QuroSttPrefs.getModelProvider(ctx)
        addLog("测试引擎: AI 模型（云端转写）")
        addLog("Endpoint: ${cfg.baseUrl.take(50)}")
        addLog("模型: $modelName (provider=$provider)")
        recording = true
        testStatus = "聆听中（云端）…"
        resultText = ""
        addLog("开始录音（最长 8 秒）…")
        scope.launch(Dispatchers.IO) {
            try {
                val minBuf = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                if (minBuf <= 0) {
                    withContext(Dispatchers.Main) { recording = false; testStatus = "录音缓冲初始化失败 ❌" }
                    return@launch
                }
                val rec = try {
                    AudioRecord(MediaRecorder.AudioSource.MIC, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 2)
                } catch (e: Throwable) {
                    withContext(Dispatchers.Main) { recording = false; testStatus = "无法录音 ❌"; addLog("❌ ${e.message}") }
                    return@launch
                }
                if (rec.state != AudioRecord.STATE_INITIALIZED) {
                    rec.release()
                    withContext(Dispatchers.Main) { recording = false; testStatus = "录音器不可用 ❌" }
                    return@launch
                }
                val pcm = ByteArrayOutputStream()
                val frame = ShortArray(16000)
                try { rec.startRecording() } catch (e: Throwable) {
                    rec.release()
                    withContext(Dispatchers.Main) { recording = false; testStatus = "无法开始录音 ❌"; addLog("❌ ${e.message}") }
                    return@launch
                }
                val end = System.currentTimeMillis() + 8000
                try {
                    while (System.currentTimeMillis() < end && recording) {
                        val n = rec.read(frame, 0, frame.size)
                        if (n <= 0) continue
                        val b = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
                        for (i in 0 until n) b.putShort(frame[i])
                        pcm.write(b.array())
                    }
                } finally {
                    try { rec.stop() } catch (_: Throwable) {}
                    try { rec.release() } catch (_: Throwable) {}
                }
                val pcmBytes = pcm.toByteArray()
                addLog("录音结束 (${pcmBytes.size} bytes)，发送转写请求…")

                // 写临时 WAV 文件
                val wavFile = File(ctx.cacheDir, "stt_test_${System.nanoTime()}.wav")
                writeWav(pcmBytes, wavFile)

                var errorShown = false
                QuroSttHolder.transcribe(
                    ctx = ctx,
                    audioFile = wavFile,
                    baseUrl = cfg.baseUrl,
                    apiKey = cfg.apiKey,
                    model = modelName,
                    language = QuroSttPrefs.getLanguage(ctx).split("-").firstOrNull()?.lowercase() ?: "zh",
                    onFinal = { text ->
                        errorShown = true
                        wavFile.delete()
                        Handler(Looper.getMainLooper()).post {
                            recording = false; resultText = text
                            testStatus = if (text.isNotBlank()) "转写完成 ✅" else "未识别到文字"
                            addLog(if (text.isNotBlank()) "最终转写: $text" else "⚠️ 转写结果为空")
                        }
                    },
                    onError = { code, msg ->
                        errorShown = true
                        wavFile.delete()
                        Handler(Looper.getMainLooper()).post {
                            recording = false; testStatus = "转写失败 ❌"; addLog("❌ [$code] $msg")
                        }
                    },
                )
            } catch (e: Throwable) {
                android.util.Log.e("QuroSttSettings", "云端测试异常", e)
                withContext(Dispatchers.Main) {
                    recording = false; testStatus = "云端测试异常 ❌"; addLog("❌ ${e.javaClass.simpleName}: ${e.message}")
                }
            }
        }
    }

    /** 按当前引擎分流测试：端侧走本地模型识别，AI 模型走云端转写，本地走原生识别。 */
    fun startTest() {
        when (QuroSttPrefs.getSource(ctx)) {
            QuroSttPrefs.SOURCE_ONDEVICE -> startOnDeviceTest()
            QuroSttPrefs.SOURCE_MODEL -> startCloudTest()
            else -> startNativeListen()
        }
    }

    // ── 录音权限（自包含请求） ──
    val recordPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startTest()
        else {
            testStatus = "需要录音权限才能测试"
            addLog("❌ 录音权限被拒绝")
        }
    }

    val selectedOption = modelOptions.firstOrNull { it.ref == selectedRef }
    val selectedDisplay = selectedOption?.let { "${it.sourceLabel}: ${it.modelName}" }
        ?: selectedName.ifBlank { "（未选择模型）" }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("语音识别 (STT)", style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "返回") }
                },
            )
        }
    ) { padding ->
        Column(
            Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("语音转文字配置，设置后悬浮语音球的识别语言生效。可切换「本地识别 / AI 模型 / 本地模型（端侧）」引擎；本地模型为手机离线运行、多语言、高准确率的 Sherpa-NCNN（SenseVoice）。", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            HorizontalDivider()

            // ── 识别引擎选择 ───────────────────────────────────────────────
            ChapterLabel("01", "识别引擎")
            SetGroup {
                Column {
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            source = QuroSttPrefs.SOURCE_LOCAL
                            QuroSttPrefs.setSource(ctx, QuroSttPrefs.SOURCE_LOCAL)
                        }.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = source == QuroSttPrefs.SOURCE_LOCAL,
                            onClick = {
                                source = QuroSttPrefs.SOURCE_LOCAL
                                QuroSttPrefs.setSource(ctx, QuroSttPrefs.SOURCE_LOCAL)
                            },
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text("本地识别", style = MaterialTheme.typography.bodyMedium)
                            Text("使用手机原生 SpeechRecognizer，离线可用、开箱即用", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                        }
                    }
                    HorizontalDivider()
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            source = QuroSttPrefs.SOURCE_MODEL
                            QuroSttPrefs.setSource(ctx, QuroSttPrefs.SOURCE_MODEL)
                            reloadModels()
                            addLog("切换到 AI 模型引擎，已刷新模型列表")
                        }.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = source == QuroSttPrefs.SOURCE_MODEL,
                            onClick = {
                                source = QuroSttPrefs.SOURCE_MODEL
                                QuroSttPrefs.setSource(ctx, QuroSttPrefs.SOURCE_MODEL)
                                reloadModels()
                                addLog("切换到 AI 模型引擎，已刷新模型列表")
                            },
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text("AI 模型", style = MaterialTheme.typography.bodyMedium)
                            Text("使用对话中已配置的 AI 模型转写（Phase 2 开放）", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                        }
                    }
                    HorizontalDivider()
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            source = QuroSttPrefs.SOURCE_ONDEVICE
                            QuroSttPrefs.setSource(ctx, QuroSttPrefs.SOURCE_ONDEVICE)
                        }.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = source == QuroSttPrefs.SOURCE_ONDEVICE,
                            onClick = {
                                source = QuroSttPrefs.SOURCE_ONDEVICE
                                QuroSttPrefs.setSource(ctx, QuroSttPrefs.SOURCE_ONDEVICE)
                            },
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text("本地模型（端侧）", style = MaterialTheme.typography.bodyMedium)
                            Text("手机离线运行 Sherpa-NCNN（SenseVoice），多语言、高准确率", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                        }
                    }
                }
            }

            // ── 端侧模型管理（仅选「本地模型（端侧）」时显示） ──────────────
            if (source == QuroSttPrefs.SOURCE_ONDEVICE) {
                ChapterLabel("02", "端侧模型下载与部署")
                Text(
                    "手机离线运行的 Sherpa-NCNN 模型需先下载。内置多种主流类型（自动识别布局），也可粘贴任意链接并选择类型。下载后自动解压部署并立即可用。",
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                )
                SetGroup {
                    Column(
                        Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        val statusText = when (deployStatus) {
                            QuroOnDeviceModelPrefs.STATUS_DEPLOYED ->
                                "已部署：$deployedName" + (deployedType?.let { "（${runCatching { AsrModelType.valueOf(it).label }.getOrNull() ?: it}）" } ?: "")
                            QuroOnDeviceModelPrefs.STATUS_DOWNLOADING -> "部署中…"
                            QuroOnDeviceModelPrefs.STATUS_ERROR -> "上一次部署失败"
                            else -> "尚未部署模型"
                        }
                        InfoBox(
                            text = "状态：$statusText",
                            tone = if (deployStatus == QuroOnDeviceModelPrefs.STATUS_DEPLOYED) Sage else cs.onSurfaceVariant,
                        )

                        // 模型选择（内置目录）
                        SetRowClickable(
                            icon = Icons.Filled.Memory,
                            name = "选择模型",
                            sub = run {
                                val selSpec = if (customMode) null else AsrModelCatalog.byId(selectedSpecId)
                                if (customMode) "自定义链接" else (selSpec?.let { "${it.displayName} · ${it.type.label}" } ?: selectedSpecId)
                            },
                            onClick = { specMenu = true },
                        )
                        DropdownMenu(expanded = specMenu, onDismissRequest = { specMenu = false }) {
                            val deployedKeys = QuroOnDeviceModelPrefs.allDeployedEntries(ctx)
                                .filterValues { it.status == QuroOnDeviceModelPrefs.STATUS_DEPLOYED }.keys
                            AsrModelCatalog.BUILTIN.forEach { spec ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(spec.displayName, style = MaterialTheme.typography.bodyMedium)
                                            Text("类型：${spec.type.label}", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                                            if (deployedKeys.contains(QuroOnDeviceModelPrefs.deployedKeyFor(spec.id, spec.downloadUrl)))
                                                Text("✅ 已部署", style = MaterialTheme.typography.bodySmall, color = Sage)
                                        }
                                    },
                                    onClick = { selectSpec(spec.id, false) },
                                )
                            }
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("➕ 自定义链接（自行选类型）", style = MaterialTheme.typography.bodyMedium) },
                                onClick = { selectSpec("", true) },
                            )
                        }

                        // 自定义链接 + 类型选择
                        if (customMode) {
                            UnderlineField(
                                label = "自定义模型下载链接",
                                value = customLink,
                                onValueChange = { customLink = it; QuroOnDeviceModelPrefs.setCustomLink(ctx, it); refreshDeployStatus() },
                                placeholder = "https://.../sherpa-onnx-xxx.tar.bz2",
                            )
                            SetRowClickable(
                                icon = Icons.Filled.Category,
                                name = "模型类型",
                                sub = customType.label,
                                onClick = { customTypeMenu = true },
                            )
                            DropdownMenu(expanded = customTypeMenu, onDismissRequest = { customTypeMenu = false }) {
                                AsrModelType.values().filter { it != AsrModelType.UNKNOWN }.forEach { t ->
                                    DropdownMenuItem(
                                        text = { Text(t.label, style = MaterialTheme.typography.bodyMedium) },
                                        onClick = { customType = t; customTypeMenu = false; QuroOnDeviceModelPrefs.setCustomType(ctx, t.name) },
                                    )
                                }
                            }
                        }

                        // 下载进度
                        if (downloading) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            val pct = if (dlTotal > 0) (dlDownloaded * 100 / dlTotal).toInt() else 0
                            Text(
                                if (dlTotal > 0) "已下载 $pct%  ($dlDownloaded / $dlTotal 字节)" else (dlState ?: "下载中…"),
                                style = MaterialTheme.typography.bodySmall,
                                color = cs.onSurfaceVariant,
                            )
                        } else {
                            dlState?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                            }
                        }

                        // 操作按钮
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PrimaryButton(
                                text = if (downloading) "部署中…" else "下载并部署",
                                onClick = { downloadAndDeployModel() },
                                enabled = !downloading,
                                modifier = Modifier.weight(1f),
                            )
                            if (deployStatus == QuroOnDeviceModelPrefs.STATUS_DEPLOYED) {
                                DangerButton(
                                    text = "删除模型",
                                    onClick = { deleteDeployedModel() },
                                    filled = true,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
            }

            // ── AI 模型下拉（仅选「AI 模型」时显示） ───────────────────────
            if (source == QuroSttPrefs.SOURCE_MODEL) {
                ChapterLabel("03", "模型")
                SetGroup {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        SetRowClickable(
                            icon = Icons.Filled.SmartToy,
                            name = selectedDisplay,
                            sub = run {
                                val selModelName = selectedOption?.modelName.orEmpty()
                                val audioCapable = QuroSttHolder.providerSupportsAudio(selectedProvider)
                                    || selModelName.contains("asr", true)
                                    || selModelName.contains("whisper", true)
                                    || selModelName.contains("transcribe", true)
                                    || selModelName.contains("stt", true)
                                    || selModelName.contains("speech", true)
                                if (audioCapable) "🎙 支持语音转写 · $selectedProvider" else "provider=${selectedProvider.ifBlank { "未知" }}"
                            },
                            onClick = { modelMenu = true },
                        )
                        DropdownMenu(
                            expanded = modelMenu,
                            onDismissRequest = { modelMenu = false },
                        ) {
                            modelOptions.forEach { opt ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text("${opt.sourceLabel} · ${opt.modelName}", style = MaterialTheme.typography.bodyMedium)
                                            val optAudioCapable = QuroSttHolder.providerSupportsAudio(opt.provider)
                                                || opt.modelName.contains("asr", true)
                                                || opt.modelName.contains("whisper", true)
                                                || opt.modelName.contains("transcribe", true)
                                                || opt.modelName.contains("stt", true)
                                                || opt.modelName.contains("speech", true)
                                            if (optAudioCapable) {
                                                Text("🎙 支持语音转写", style = MaterialTheme.typography.bodySmall, color = cs.primary)
                                            } else {
                                                Text("provider=${opt.provider}", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                                            }
                                        }
                                    },
                                    onClick = {
                                        selectModel(opt)
                                        modelMenu = false
                                    },
                                )
                            }
                        }
                        HorizontalDivider()
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            PrimaryButton(
                                text = if (fetching) "拉取中…" else "从接口刷新",
                                onClick = { refreshFromApi() },
                                enabled = !fetching,
                                modifier = Modifier.weight(1f),
                            )
                            if (fetchedModels.isNotEmpty()) {
                                PrimaryButton(
                                    text = "选择实时模型",
                                    onClick = { fetchMenu = true },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                        DropdownMenu(expanded = fetchMenu, onDismissRequest = { fetchMenu = false }) {
                            fetchedModels.forEach { m ->
                                DropdownMenuItem(
                                    text = { Text(m, style = MaterialTheme.typography.bodyMedium) },
                                    onClick = {
                                        selectedRef = m
                                        selectedName = m
                                        selectedProvider = fetchedProvider
                                        QuroSttPrefs.setModelSelection(ctx, selectedRef, selectedName, selectedProvider)
                                        addLog("已选实时模型: $m (provider=$fetchedProvider)")
                                        fetchMenu = false
                                    },
                                )
                            }
                        }
                        fetchStatus?.let {
                            Spacer(Modifier.height(4.dp))
                            Text(it, style = MaterialTheme.typography.labelSmall, color = if (it.contains("❌")) cs.error else cs.onSurfaceVariant)
                        }
                        HorizontalDivider()
                        // 云端转写模式：部分网关（如 MIMO）不支持 /audio/transcriptions（404），
                        // 但支持在 chat 消息里带音频走 /chat/completions。
                        SetRow(
                            icon = Icons.Filled.Chat,
                            name = "走 /chat/completions（多模态音频）",
                            sub = "关闭=标准 /audio/transcriptions；开启=把音频塞进 chat 消息（兼容不支持转写端点的网关）",
                            checked = useChat,
                            onToggle = {
                                val it = !useChat
                                useChat = it
                                QuroSttPrefs.setUseChatCompletions(ctx, it)
                                addLog(if (it) "✅ 已开启 /chat/completions 模式" else "已关闭 /chat/completions 模式")
                            },
                        )
                    }
                }
            }

            // ── 识别语言 + 部分结果（保留） ─────────────────────────────────
            ChapterLabel("04", "识别设置")
            SetGroup {
                SetRowClickable(
                    icon = Icons.Filled.Language,
                    name = "识别语言",
                    sub = STT_LANGUAGES.firstOrNull { it.second == language }?.first ?: language,
                    onClick = { langMenu = true },
                )
                DropdownMenu(expanded = langMenu, onDismissRequest = { langMenu = false }) {
                    STT_LANGUAGES.forEach { (label, code) ->
                        DropdownMenuItem(text = { Text(label) }, onClick = {
                            language = code
                            QuroSttPrefs.setLanguage(ctx, code)
                            langMenu = false
                        })
                    }
                }
                HorizontalDivider()
                SetRow(
                    icon = Icons.Filled.GraphicEq,
                    name = "部分结果",
                    sub = "说话时实时回显识别中间结果",
                    checked = partial,
                    onToggle = {
                        val it = !partial
                        partial = it
                        QuroSttPrefs.setPartial(ctx, it)
                    },
                )
            }

            // ── 语音转文本测试区 ───────────────────────────────────────────
            ChapterLabel("05", "语音转文本测试")
            Text(
                "选「本地模型（端侧）」时，本测试用手机离线 Sherpa-NCNN 识别；选其他引擎走原生 SpeechRecognizer。",
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
            )

            if (recording) {
                DangerButton(
                    text = "⏹ 停止",
                    onClick = {
                        if (recording) {
                            stopNativeListen()
                        } else {
                            when {
                                ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> startTest()
                                else -> recordPermission.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        }
                    },
                )
            } else {
                PrimaryButton(
                    text = "🎙 开始录音",
                    onClick = {
                        if (recording) {
                            stopNativeListen()
                        } else {
                            when {
                                ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> startTest()
                                else -> recordPermission.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        }
                    },
                )
            }

            testStatus?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = when {
                        it.contains("失败") || it.contains("异常") || it.contains("❌") -> cs.error
                        it.contains("✅") -> Sage
                        else -> cs.onSurfaceVariant
                    },
                )
            }

            Box(
                Modifier.fillMaxWidth().heightIn(min = 80.dp).clip(RoundedCornerShape(12.dp))
                    .background(cs.surfaceVariant).border(1.dp, Line, RoundedCornerShape(12.dp)).padding(12.dp),
            ) {
                SelectionContainer {
                    Text(
                        resultText.ifBlank { "识别出的文字会显示在这里" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (resultText.isBlank()) cs.onSurfaceVariant else cs.onSurface,
                    )
                }
            }

            // ── Bug 日志区域（镜像 TTS） ───────────────────────────────────
            if (sttLogs.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("📋 Bug 日志 (${sttLogs.size})", style = MaterialTheme.typography.titleSmall.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold), color = cs.primary)
                    Row {
                        Box(
                            Modifier.clip(RoundedCornerShape(8.dp)).clickable {
                                val text = sttLogs.joinToString("\n")
                                val clip = ClipData.newPlainText("QuroSTT", text)
                                (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
                                testStatus = "日志已复制到剪贴板 ✅ 直接粘贴给我即可"
                            }.padding(horizontal = 10.dp, vertical = 4.dp),
                        ) { Text("复制", fontSize = 12.sp, color = Accent) }
                        Spacer(Modifier.width(8.dp))
                        Box(
                            Modifier.clip(RoundedCornerShape(8.dp)).clickable { sttLogs = emptyList() }
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                        ) { Text("清空", fontSize = 12.sp, color = cs.onSurfaceVariant) }
                    }
                }
                SetGroup {
                    SelectionContainer {
                        Column(Modifier.padding(12.dp).heightIn(min = 80.dp, max = 220.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            sttLogs.forEach { entry ->
                                Text(
                                    entry,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = when {
                                        entry.contains("❌") || entry.contains("FAILED") || entry.contains("error") || entry.contains("Error") || entry.contains("exception", ignoreCase = true) -> cs.error
                                        entry.contains("✅") || entry.contains("SUCCESS") || entry.contains("READY") -> Sage
                                        else -> cs.onSurfaceVariant
                                    },
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
