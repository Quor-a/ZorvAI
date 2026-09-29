package com.ai.assistance.quro.ui
import androidx.compose.ui.res.stringResource
import com.ai.assistance.quro.R
import com.ai.assistance.quro.util.qstr

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
import androidx.compose.ui.graphics.Color
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
import com.ai.assistance.quro.core.tools.AsrDeviceCompat
import com.ai.assistance.quro.core.tools.AsrModelCatalog
import com.ai.assistance.quro.core.tools.AsrModelSpec
import com.ai.assistance.quro.core.tools.AsrModelType
import com.ai.assistance.quro.core.tools.MIN_VALID_MODEL_BYTES
import com.ai.assistance.quro.core.tools.formatBytes
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
    val sourceLabel: String,// qstr(R.string.qk_02505) / 预设名
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
    // 自定义链接的模型类型：当前引擎只跑流式 transducer，保留下拉仅为将来扩展
    var customType by remember { mutableStateOf(AsrModelType.STREAMING_TRANSDUCER) }
    var downloading by remember { mutableStateOf(false) }
    var dlDownloaded by remember { mutableStateOf(0L) }
    var dlTotal by remember { mutableStateOf(0L) }
    var dlState by remember { mutableStateOf<String?>(null) }
    var deployStatus by remember { mutableStateOf(QuroOnDeviceModelPrefs.getStatus(ctx)) }
    var deployedName by remember { mutableStateOf(QuroOnDeviceModelPrefs.getDeployedName(ctx)) }
    /** 已部署模型占用的磁盘空间，用户最关心的「到底吃我多少存储」。 */
    var deployedSize by remember { mutableStateOf(0L) }
    /** 已部署目录是本机引擎跑不动的旧模型（SenseVoice / ONNX）。 */
    var legacyDeployed by remember { mutableStateOf(false) }
    var specMenu by remember { mutableStateOf(false) }
    var customTypeMenu by remember { mutableStateOf(false) }

    // ── 端侧引擎设备兼容性（仅 arm64-v8a 支持；其余架构禁用下载/部署，不再假装可部署） ──
    val asrSupported = remember { AsrDeviceCompat.isSupported(ctx) }

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
                label = cfg.model.ifBlank { qstr(R.string.qk_02506) },
                provider = cfg.provider,
                modelName = cfg.model,
                sourceLabel = qstr(R.string.qk_02505),
            )
        )
        profiles.forEach { p: QuroSavedProfile ->
            opts.add(
                SttModelOption(
                    ref = p.id,
                    label = p.model.ifBlank { qstr(R.string.qk_02507) },
                    provider = p.provider,
                    modelName = p.model,
                    sourceLabel = p.name.ifBlank { qstr(R.string.qk_00037) },
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
        addLog(qstr(R.string.qk_02508, (opt.sourceLabel).toString(), (opt.modelName).toString(), (opt.provider).toString()))
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
                    addLog(qstr(R.string.qk_02509))
                    fetchStatus = qstr(R.string.qk_02510)
                    fetching = false
                    return@launch
                }
                addLog(qstr(R.string.qk_02511, (cfg.baseUrl).toString()))
                val res = QuroModelListFetcher().fetch(cfg.baseUrl, cfg.apiKey)
                when (res) {
                    is QuroModelListResult.Success -> {
                        fetchedModels = res.models.map { it.id }
                        fetchStatus = qstr(R.string.qk_02512, (res.models.size).toString())
                        addLog(qstr(R.string.qk_02513, (res.models.size).toString()))
                        fetchMenu = true
                    }
                    is QuroModelListResult.Error -> {
                        fetchStatus = res.message
                        addLog(qstr(R.string.qk_02514, (res.message).toString()))
                    }
                }
            } catch (e: Exception) {
                fetchStatus = e.message
                addLog(qstr(R.string.qk_02515, (e.javaClass.simpleName).toString(), (e.message).toString()))
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
        addLog(qstr(R.string.qk_02516))
        reloadModels()
        addLog(qstr(R.string.qk_02517, (modelOptions.size).toString()))
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
        legacyDeployed = QuroOnDeviceModelManager.isLegacyIncompatible(ctx)
        deployedSize = QuroOnDeviceModelManager.deployedSizeBytes(ctx)
        val e = QuroOnDeviceModelPrefs.getDeployedEntry(ctx, key)
        if (e == null) {
            deployStatus = QuroOnDeviceModelPrefs.STATUS_NONE
            deployedName = null
            return
        }
        // 二次进入闭环校验：若记录为「已部署」，但磁盘文件缺失/损坏（被删、解压不完整），
        // 则降级为 ERROR 并提示重新下载，避免「记录说已部署、实际不可用」导致的误判/卡死；
        // 若文件完整（大小 + NCNN 布局齐全）则保持 DEPLOYED，不重复下载。
        if (e.status == QuroOnDeviceModelPrefs.STATUS_DEPLOYED &&
            !QuroOnDeviceModelManager.verifyDeployedDir(e.dir)
        ) {
            QuroOnDeviceModelPrefs.setEntryStatus(ctx, key, QuroOnDeviceModelPrefs.STATUS_ERROR)
            deployStatus = QuroOnDeviceModelPrefs.STATUS_ERROR
            deployedName = e.name
            addLog(qstr(R.string.qk_02518, (e.dir).toString()))
            return
        }
        deployStatus = e.status
        deployedName = e.name
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
        // 设备兼容性前置校验：架构不支持则明确报错并禁用，不再假装可部署
        if (!asrSupported) {
            dlState = qstr(R.string.qk_02519)
            addLog(qstr(R.string.qk_02520, (AsrDeviceCompat.unsupportedReason(ctx)).toString()))
            return
        }
        val spec: AsrModelSpec = if (customMode) {
            if (customLink.isBlank()) {
                dlState = qstr(R.string.qk_02521)
                addLog(qstr(R.string.qk_02522))
                return
            }
            AsrModelSpec(
                id = "custom-${customLink.hashCode()}",
                displayName = qstr(R.string.qk_02523),
                note = qstr(R.string.qk_02524),
                type = customType,
                downloadUrl = customLink,
                downloadBytes = 0L,
                minSizeBytes = MIN_VALID_MODEL_BYTES,
            )
        } else {
            AsrModelCatalog.byId(selectedSpecId) ?: run {
                dlState = qstr(R.string.qk_02525)
                addLog(qstr(R.string.qk_02526))
                return
            }
        }
        downloading = true
        dlDownloaded = 0L
        dlTotal = 0L
        dlState = qstr(R.string.qk_02527)
        addLog(qstr(R.string.qk_02528, (spec.displayName).toString(), (spec.type.label).toString(), (spec.downloadUrl).toString()))
        scope.launch(Dispatchers.IO) {
            val ok = QuroOnDeviceModelManager.downloadAndDeploy(
                ctx, spec,
                onProgress = { d, t -> withContext(Dispatchers.Main) { dlDownloaded = d; dlTotal = t } },
                onState = { s -> withContext(Dispatchers.Main) { dlState = s; addLog(s) } },
            )
            withContext(Dispatchers.Main) {
                downloading = false
                refreshDeployStatus()
                if (ok) addLog(qstr(R.string.qk_02529))
                else addLog(qstr(R.string.qk_02530))
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
                addLog(qstr(R.string.qk_02531, (key).toString()))
            }
        }
    }

    fun startNativeListen() {
        addLog(qstr(R.string.qk_02532))
        val src = QuroSttPrefs.getSource(ctx)
        addLog(
            "测试引擎: ${
                if (src == QuroSttPrefs.SOURCE_MODEL) "AI 模型（Phase1 回退原生识别）"
                else "本地识别（原生 SpeechRecognizer）"
            }"
        )
        if (src == QuroSttPrefs.SOURCE_MODEL) {
            addLog(qstr(R.string.qk_02535))
        }
        recording = true
        testStatus = qstr(R.string.qk_01757)
        resultText = ""
        QuroSttHolder.startListening(
            context = ctx,
            language = QuroSttPrefs.getLanguage(ctx),
            partialResults = QuroSttPrefs.getPartial(ctx),
            onPartial = { t ->
                resultText = t
                testStatus = qstr(R.string.qk_01757)
            },
            onFinal = { text ->
                recording = false
                resultText = text
                testStatus = if (text.isNotBlank()) qstr(R.string.qk_02536) else qstr(R.string.qk_02537)
                addLog(if (text.isNotBlank()) qstr(R.string.qk_02538, (text).toString()) else qstr(R.string.qk_02539))
            },
            onError = { code, msg ->
                recording = false
                testStatus = qstr(R.string.qk_02540, (msg).toString())
                addLog("❌ $msg")
            },
        )
    }

    fun stopNativeListen() {
        recording = false
        QuroSttHolder.stopListening()
        testStatus = qstr(R.string.qk_00326)
        addLog(qstr(R.string.qk_02541))
    }

    /** 端侧（本地模型）测试：录音最长 8 秒 → QuroOnDeviceAsr 离线识别。 */
    fun startOnDeviceTest() {
        addLog(qstr(R.string.qk_02542))
        if (!asrSupported) {
            testStatus = qstr(R.string.qk_02543)
            addLog("❌ ${AsrDeviceCompat.unsupportedReason(ctx)}")
            return
        }
        if (QuroOnDeviceModelManager.isLegacyIncompatible(ctx)) {
            testStatus = qstr(R.string.qk_02544)
            addLog(qstr(R.string.qk_02545))
            return
        }
        if (!QuroOnDeviceAsr.isModelAvailable(ctx)) {
            testStatus = qstr(R.string.qk_02546)
            addLog(qstr(R.string.qk_02547))
            return
        }
        recording = true
        testStatus = qstr(R.string.qk_02548)
        resultText = ""
        addLog(qstr(R.string.qk_02549))
        scope.launch(Dispatchers.IO) {
            // 兜底：原生 SIGSEGV 时 Java try/catch 无法捕获，用线程级 handler 防闪退
            val prevHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { t, e ->
                android.util.Log.e("QuroSttSettings", qstr(R.string.qk_02550), e)
                // 切回主线程更新 UI（不崩 App）——用 Handler 而非 withContext（此处非协程上下文）
                Handler(Looper.getMainLooper()).post {
                    recording = false
                    testStatus = qstr(R.string.qk_02551)
                    addLog(qstr(R.string.qk_02552, (e.javaClass.simpleName).toString(), (e.message).toString()))
                }
            }
            try {
                // 预检：打印部署目录与文件详情
                val deployDir = QuroOnDeviceAsr.getDeployedDir(ctx)
                addLog(qstr(R.string.qk_02553, (deployDir).toString()))
                if (deployDir != null) {
                    val dirFile = File(deployDir)
                    if (dirFile.exists()) {
                        dirFile.listFiles()?.forEach { f ->
                            addLog("  📄 ${f.name} (${f.length()} bytes)")
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            recording = false; testStatus = qstr(R.string.qk_02554); addLog(qstr(R.string.qk_02555, (deployDir).toString()))
                        }
                        return@launch
                    }
                }

                if (!QuroOnDeviceAsr.isReady()) {
                    withContext(Dispatchers.Main) { testStatus = qstr(R.string.qk_02556); addLog(qstr(R.string.qk_02557)) }
                    addLog(qstr(R.string.qk_02558))
                    if (!QuroOnDeviceAsr.ensureLoaded(ctx)) {
                        val reason = QuroOnDeviceAsr.lastError.ifBlank { qstr(R.string.qk_02559) }
                        withContext(Dispatchers.Main) {
                            recording = false; testStatus = qstr(R.string.qk_02560); addLog("❌ $reason")
                        }
                        return@launch
                    }
                    addLog(qstr(R.string.qk_02561))
                }

                val minBuf = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                if (minBuf <= 0) {
                    withContext(Dispatchers.Main) { recording = false; testStatus = qstr(R.string.qk_02562) }
                    return@launch
                }
                val rec = try {
                    AudioRecord(MediaRecorder.AudioSource.MIC, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 2)
                } catch (e: Throwable) {
                    withContext(Dispatchers.Main) { recording = false; testStatus = qstr(R.string.qk_02563); addLog("❌ ${e.message}") }
                    return@launch
                }
                if (rec.state != AudioRecord.STATE_INITIALIZED) {
                    rec.release()
                    withContext(Dispatchers.Main) { recording = false; testStatus = qstr(R.string.qk_02564) }
                    return@launch
                }
                val pcm = ByteArrayOutputStream()
                val frame = ShortArray(16000)
                try {
                    rec.startRecording()
                } catch (e: Throwable) {
                    rec.release()
                    withContext(Dispatchers.Main) { recording = false; testStatus = qstr(R.string.qk_02565); addLog("❌ ${e.message}") }
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
                addLog(qstr(R.string.qk_02566, (pcm.size()).toString()))
                val startedAt = System.currentTimeMillis()
                val text = QuroOnDeviceAsr.recognize(pcm.toByteArray())
                val costMs = System.currentTimeMillis() - startedAt
                val failReason = QuroOnDeviceAsr.lastError
                withContext(Dispatchers.Main) {
                    recording = false
                    resultText = text
                    testStatus = if (text.isNotBlank()) qstr(R.string.qk_02567, (costMs).toString()) else qstr(R.string.qk_02568)
                    addLog(
                        if (text.isNotBlank()) qstr(R.string.qk_02569, (costMs).toString(), (text).toString())
                        else "❌ ${failReason.ifBlank { "未识别到文字" }}"
                    )
                }
            } catch (e: Throwable) {
                android.util.Log.e("QuroSttSettings", qstr(R.string.qk_02570), e)
                withContext(Dispatchers.Main) {
                    recording = false
                    testStatus = qstr(R.string.qk_02571)
                    addLog(qstr(R.string.qk_02515, (e.javaClass.simpleName).toString(), (e.message).toString()))
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
        addLog(qstr(R.string.qk_02572))
        val cfg = QuroModelConfigRepository(ctx).load()
        if (cfg.baseUrl.isBlank()) {
            testStatus = qstr(R.string.qk_02573)
            addLog(qstr(R.string.qk_02574))
            return
        }
        if (cfg.apiKey.isBlank()) {
            testStatus = qstr(R.string.qk_02575)
            addLog(qstr(R.string.qk_02576))
            return
        }
        val modelName = QuroSttPrefs.getModelName(ctx).ifBlank { "whisper-1" }
        val provider = QuroSttPrefs.getModelProvider(ctx)
        addLog(qstr(R.string.qk_02577))
        addLog("Endpoint: ${cfg.baseUrl.take(50)}")
        addLog(qstr(R.string.qk_02578, (modelName).toString(), (provider).toString()))
        recording = true
        testStatus = qstr(R.string.qk_02579)
        resultText = ""
        addLog(qstr(R.string.qk_02549))
        scope.launch(Dispatchers.IO) {
            try {
                val minBuf = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                if (minBuf <= 0) {
                    withContext(Dispatchers.Main) { recording = false; testStatus = qstr(R.string.qk_02562) }
                    return@launch
                }
                val rec = try {
                    AudioRecord(MediaRecorder.AudioSource.MIC, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 2)
                } catch (e: Throwable) {
                    withContext(Dispatchers.Main) { recording = false; testStatus = qstr(R.string.qk_02563); addLog("❌ ${e.message}") }
                    return@launch
                }
                if (rec.state != AudioRecord.STATE_INITIALIZED) {
                    rec.release()
                    withContext(Dispatchers.Main) { recording = false; testStatus = qstr(R.string.qk_02564) }
                    return@launch
                }
                val pcm = ByteArrayOutputStream()
                val frame = ShortArray(16000)
                try { rec.startRecording() } catch (e: Throwable) {
                    rec.release()
                    withContext(Dispatchers.Main) { recording = false; testStatus = qstr(R.string.qk_02565); addLog("❌ ${e.message}") }
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
                addLog(qstr(R.string.qk_02580, (pcmBytes.size).toString()))

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
                            testStatus = if (text.isNotBlank()) qstr(R.string.qk_02581) else qstr(R.string.qk_02582)
                            addLog(if (text.isNotBlank()) qstr(R.string.qk_02583, (text).toString()) else qstr(R.string.qk_02584))
                        }
                    },
                    onError = { code, msg ->
                        errorShown = true
                        wavFile.delete()
                        Handler(Looper.getMainLooper()).post {
                            recording = false; testStatus = qstr(R.string.qk_02585); addLog("❌ [$code] $msg")
                        }
                    },
                )
            } catch (e: Throwable) {
                android.util.Log.e("QuroSttSettings", qstr(R.string.qk_02586), e)
                withContext(Dispatchers.Main) {
                    recording = false; testStatus = qstr(R.string.qk_02587); addLog("❌ ${e.javaClass.simpleName}: ${e.message}")
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
            testStatus = qstr(R.string.qk_02588)
            addLog(qstr(R.string.qk_02589))
        }
    }

    val selectedOption = modelOptions.firstOrNull { it.ref == selectedRef }
    val selectedDisplay = selectedOption?.let { "${it.sourceLabel}: ${it.modelName}" }
        ?: selectedName.ifBlank { qstr(R.string.qk_02590) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.qk_00234), style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.qk_00143)) }
                },
            )
        }
    ) { padding ->
        Column(
            Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.qk_02591), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            HorizontalDivider()

            // ── 识别引擎选择 ───────────────────────────────────────────────
            ChapterLabel("01", stringResource(R.string.qk_02592))
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
                            Text(stringResource(R.string.qk_02593), style = MaterialTheme.typography.bodyMedium)
                            Text(stringResource(R.string.qk_02594), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                        }
                    }
                    HorizontalDivider()
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            source = QuroSttPrefs.SOURCE_MODEL
                            QuroSttPrefs.setSource(ctx, QuroSttPrefs.SOURCE_MODEL)
                            reloadModels()
                            addLog(qstr(R.string.qk_02595))
                        }.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = source == QuroSttPrefs.SOURCE_MODEL,
                            onClick = {
                                source = QuroSttPrefs.SOURCE_MODEL
                                QuroSttPrefs.setSource(ctx, QuroSttPrefs.SOURCE_MODEL)
                                reloadModels()
                                addLog(qstr(R.string.qk_02595))
                            },
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.qk_02596), style = MaterialTheme.typography.bodyMedium)
                            Text(stringResource(R.string.qk_02597), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
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
                            Text(stringResource(R.string.qk_02598), style = MaterialTheme.typography.bodyMedium)
                            Text(stringResource(R.string.qk_02599), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                        }
                    }
                }
            }

            // ── 端侧模型管理（仅选「本地模型（端侧）」时显示） ──────────────
            if (source == QuroSttPrefs.SOURCE_ONDEVICE) {
                ChapterLabel("02", stringResource(R.string.qk_02600))
                Text(stringResource(R.string.qk_02601),
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                )
                if (asrSupported) {
                    InfoBox(
                        text = stringResource(R.string.qk_02602),
                        tone = Sage,
                    )
                } else {
                    val warnColor = Color(android.graphics.Color.parseColor("#C0432F"))
                    InfoBox(
                        text = stringResource(R.string.qk_02603, (AsrDeviceCompat.unsupportedReason(ctx)).toString()),
                        tone = warnColor,
                    )
                }
                Spacer(Modifier.height(8.dp))
                SetGroup {
                    Column(
                        Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        val statusText = when (deployStatus) {
                            QuroOnDeviceModelPrefs.STATUS_DEPLOYED ->
                                stringResource(R.string.qk_02604, (deployedName).toString(), (formatBytes(deployedSize)).toString())
                            QuroOnDeviceModelPrefs.STATUS_DOWNLOADING -> stringResource(R.string.qk_01555)
                            QuroOnDeviceModelPrefs.STATUS_ERROR -> stringResource(R.string.qk_02605)
                            else -> stringResource(R.string.qk_02606)
                        }
                        InfoBox(
                            text = stringResource(R.string.qk_02607, (statusText).toString()),
                            tone = if (deployStatus == QuroOnDeviceModelPrefs.STATUS_DEPLOYED) Sage else cs.onSurfaceVariant,
                        )

                        // 历史遗留部署迁移提示：旧 SenseVoice / ONNX 目录本机引擎跑不了，
                        // 这正是用户此前「端侧识别一直没反应」的根因，必须显式告知而不是静默失败。
                        if (legacyDeployed) {
                            val warnColor = Color(android.graphics.Color.parseColor("#C0432F"))
                            InfoBox(
                                text = stringResource(R.string.qk_02608) + stringResource(R.string.qk_02609),
                                tone = warnColor,
                            )
                        }

                        // 模型选择（内置目录）
                        SetRowClickable(
                            icon = Icons.Filled.Memory,
                            name = stringResource(R.string.qk_00199),
                            sub = run {
                                val selSpec = if (customMode) null else AsrModelCatalog.byId(selectedSpecId)
                                if (customMode) qstr(R.string.qk_02610) else (selSpec?.displayName ?: selectedSpecId.ifBlank { qstr(R.string.qk_02611) })
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
                                            Text(spec.note, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                                            Text(qstr(R.string.qk_02612, (formatBytes(spec.downloadBytes)).toString()), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                                            if (deployedKeys.contains(QuroOnDeviceModelPrefs.deployedKeyFor(spec.id, spec.downloadUrl)))
                                                Text(qstr(R.string.qk_02613), style = MaterialTheme.typography.bodySmall, color = Sage)
                                        }
                                    },
                                    onClick = { selectSpec(spec.id, false) },
                                )
                            }
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.qk_02614), style = MaterialTheme.typography.bodyMedium) },
                                onClick = { selectSpec("", true) },
                            )
                        }

                        // 自定义链接 + 类型选择
                        if (customMode) {
                            UnderlineField(
                                label = stringResource(R.string.qk_02615),
                                value = customLink,
                                onValueChange = { customLink = it; QuroOnDeviceModelPrefs.setCustomLink(ctx, it); refreshDeployStatus() },
                                placeholder = "https://.../sherpa-onnx-xxx.tar.bz2",
                            )
                            SetRowClickable(
                                icon = Icons.Filled.Category,
                                name = stringResource(R.string.qk_02616),
                                sub = customType.label,
                                onClick = { customTypeMenu = true },
                            )
                            DropdownMenu(expanded = customTypeMenu, onDismissRequest = { customTypeMenu = false }) {
                                // 只列出引擎真正能跑的类型：随包 .so 仅实现流式 transducer
                                listOf(AsrModelType.STREAMING_TRANSDUCER).forEach { t ->
                                    DropdownMenuItem(
                                        text = { Text(t.label, style = MaterialTheme.typography.bodyMedium) },
                                        onClick = { customType = t; customTypeMenu = false; QuroOnDeviceModelPrefs.setCustomType(ctx, t.name) },
                                    )
                                }
                            }
                        }

                        // 下载进度
                        if (downloading) {
                            if (dlTotal > 0) {
                                LinearProgressIndicator(
                                    progress = { (dlDownloaded.toFloat() / dlTotal.toFloat()).coerceIn(0f, 1f) },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            } else {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            }
                            val pct = if (dlTotal > 0) (dlDownloaded * 100 / dlTotal).toInt() else 0
                            Text(
                                if (dlTotal > 0) stringResource(R.string.qk_02617, (pct).toString(), (formatBytes(dlDownloaded)).toString(), (formatBytes(dlTotal)).toString())
                                else (dlState ?: stringResource(R.string.qk_02224)),
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
                                text = when {
                                    !asrSupported -> stringResource(R.string.qk_02618)
                                    downloading -> stringResource(R.string.qk_01555)
                                    else -> stringResource(R.string.qk_02619)
                                },
                                onClick = { downloadAndDeployModel() },
                                enabled = !downloading && asrSupported,
                                modifier = Modifier.weight(1f),
                            )
                            if (deployStatus == QuroOnDeviceModelPrefs.STATUS_DEPLOYED) {
                                DangerButton(
                                    text = stringResource(R.string.qk_02620),
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
                ChapterLabel("03", stringResource(R.string.qk_02238))
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
                                if (audioCapable) qstr(R.string.qk_02621, (selectedProvider).toString()) else "provider=${selectedProvider.ifBlank { "未知" }}"
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
                                                Text(qstr(R.string.qk_02622), style = MaterialTheme.typography.bodySmall, color = cs.primary)
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
                                text = if (fetching) stringResource(R.string.qk_02113) else stringResource(R.string.qk_02623),
                                onClick = { refreshFromApi() },
                                enabled = !fetching,
                                modifier = Modifier.weight(1f),
                            )
                            if (fetchedModels.isNotEmpty()) {
                                PrimaryButton(
                                    text = stringResource(R.string.qk_02624),
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
                                        addLog(qstr(R.string.qk_02625, (m).toString(), (fetchedProvider).toString()))
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
                            name = stringResource(R.string.qk_02626),
                            sub = stringResource(R.string.qk_02627),
                            checked = useChat,
                            onToggle = {
                                val it = !useChat
                                useChat = it
                                QuroSttPrefs.setUseChatCompletions(ctx, it)
                                addLog(if (it) qstr(R.string.qk_02628) else qstr(R.string.qk_02629))
                            },
                        )
                    }
                }
            }

            // ── 识别语言 + 部分结果（保留） ─────────────────────────────────
            ChapterLabel("04", stringResource(R.string.qk_02630))
            SetGroup {
                SetRowClickable(
                    icon = Icons.Filled.Language,
                    name = stringResource(R.string.qk_02631),
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
                    name = stringResource(R.string.qk_02632),
                    sub = stringResource(R.string.qk_02633),
                    checked = partial,
                    onToggle = {
                        val it = !partial
                        partial = it
                        QuroSttPrefs.setPartial(ctx, it)
                    },
                )
            }

            // ── 语音转文本测试区 ───────────────────────────────────────────
            ChapterLabel("05", stringResource(R.string.qk_02634))
            Text(stringResource(R.string.qk_02635),
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
            )

            if (recording) {
                DangerButton(
                    text = stringResource(R.string.qk_02636),
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
                    text = stringResource(R.string.qk_02637),
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
                        resultText.ifBlank { stringResource(R.string.qk_02639) },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (resultText.isBlank()) cs.onSurfaceVariant else cs.onSurface,
                    )
                }
            }

            // ── Bug 日志区域（镜像 TTS） ───────────────────────────────────
            if (sttLogs.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.qk_02640, (sttLogs.size).toString()), style = MaterialTheme.typography.titleSmall.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold), color = cs.primary)
                    Row {
                        Box(
                            Modifier.clip(RoundedCornerShape(8.dp)).clickable {
                                val text = sttLogs.joinToString("\n")
                                val clip = ClipData.newPlainText("QuroSTT", text)
                                (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
                                testStatus = qstr(R.string.qk_02641)
                            }.padding(horizontal = 10.dp, vertical = 4.dp),
                        ) { Text(stringResource(R.string.qk_00088), fontSize = 12.sp, color = Accent) }
                        Spacer(Modifier.width(8.dp))
                        Box(
                            Modifier.clip(RoundedCornerShape(8.dp)).clickable { sttLogs = emptyList() }
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                        ) { Text(stringResource(R.string.qk_00764), fontSize = 12.sp, color = cs.onSurfaceVariant) }
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