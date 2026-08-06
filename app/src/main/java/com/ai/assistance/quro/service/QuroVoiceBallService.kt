package com.ai.assistance.quro.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.net.Uri
import android.provider.Settings
import android.os.Handler
import android.os.Looper
import android.content.pm.ServiceInfo
import android.speech.SpeechRecognizer
import java.util.Locale
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.sqrt
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.appcompat.view.ContextThemeWrapper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.core.app.NotificationCompat
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.ai.assistance.quro.core.QuroAssistant
import com.ai.assistance.quro.core.util.QuroServiceLifecycleOwner
import com.ai.assistance.quro.core.tools.QuroSttHolder
import com.ai.assistance.quro.core.QuroPlatformManifest
import com.ai.assistance.quro.core.QuroConversationStore
import com.ai.assistance.quro.core.network.QuroLlmClient
import com.ai.assistance.quro.core.model.QuroFunctionModelConfigRepository
import com.ai.assistance.quro.core.model.QuroFunctionType
import com.ai.assistance.quro.core.model.QuroModelConfigRepository
import com.ai.assistance.quro.core.QuroMessage
import com.ai.assistance.quro.core.QuroPersona
import com.ai.assistance.quro.core.QuroPersonaRepository
import com.ai.assistance.quro.core.QuroTagRepository
import com.ai.assistance.quro.core.memory.QuroMemoryEntry
import com.ai.assistance.quro.core.memory.QuroMemoryRepository
import com.ai.assistance.quro.core.tools.buildQuroRegistry
import com.ai.assistance.quro.core.tools.QuroTtsHolder
import com.ai.assistance.quro.core.tools.QuroTtsPrefs
import com.ai.assistance.quro.core.tools.QuroVoiceFeaturePrefs
import com.ai.assistance.quro.core.tools.QuroSttPrefs
import com.ai.assistance.quro.core.tools.QuroOnDeviceAsr
import com.ai.assistance.quro.ui.QuroVoiceBall
import com.ai.assistance.quro.ui.QuroChatViewModel
import com.ai.assistance.quro.activity.QuroMainActivity
import com.ai.assistance.quro.core.tools.QuroVoiceStyle
import com.ai.assistance.quro.core.tools.QuroCloudTtsCatalog
import com.ai.assistance.quro.core.tools.QuroTtsProviderPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.math.hypot
import kotlinx.coroutines.launch

/**
 * 悬浮语音球服务：在任意界面挂一个可点击的球，
 * 流水线为 语音转文本(STT) → LLM → 文本转语音(TTS)。
 *
 * 防御性 try/catch 避免任意异常导致进程闪退
 * （Service 中不强制需要 LifecycleOwner）。
 */
class QuroVoiceBallService : Service(), CoroutineScope by CoroutineScope(Dispatchers.Main + SupervisorJob()) {

    private lateinit var windowManager: WindowManager
    private var composeView: ComposeView? = null
    private var composeLifecycleOwner: QuroServiceLifecycleOwner? = null

    private val store = QuroConversationStore()
    // 必须延迟到 onCreate 之后（context 已 attach）再构建，否则 Service 构造阶段
    // mBase 为 null → buildQuroRegistry→mergeSkills→getSharedPreferences NPE（详见崩溃日志）。
    private val registry by lazy { buildQuroRegistry(this) }
    private val assistant by lazy { QuroAssistant(QuroLlmClient(), registry, store) }

    // 人格 / 标签 / 记忆库：用于构建 system prompt（与对话框保持一致的人格认知）
    private val personaRepo by lazy { QuroPersonaRepository(applicationContext) }
    private val tagRepo by lazy { QuroTagRepository(applicationContext) }
    private val memoryRepo by lazy { QuroMemoryRepository(applicationContext) }

    private var listening by mutableStateOf(false)
    private var status by mutableStateOf("点我说话")

    // 实时对话主开关 / 播报中 / 连续空或错计数
    private var conversationActive by mutableStateOf(false)
    private var speaking by mutableStateOf(false)
    /** 语音球 TTS 是否正在播报中（防止 process 重入再次 speak 造成重复/并发播报）。 */
    @Volatile private var ttsBusy = false
    private var emptyCount = 0
    /** 悬浮球是否已挂载（由通知栏「语音球」按钮 / 设置开关控制，与服务/通知栏常驻解耦）。 */
    private var ballShown by mutableStateOf(false)

    // ── 云端转写录音（Phase 2，不依赖原生 SpeechRecognizer） ──
    private val REC_SAMPLE_RATE = 16000
    private val REC_CHANNELS = 1
    private val REC_ENCODING_BITS = 16
    private val REC_VAD_SILENCE_MS = 1200L   // 静音持续多久判定一句话结束
    private val REC_VAD_THRESHOLD = 0.012     // RMS 归一化振幅阈值（低于=静音）
    private val REC_MIN_MS = 400L             // 最短有效录音时长，避免误触发
    private val REC_MAX_MS = 30000L           // 单句最长录音保护

    @Volatile private var audioRecord: AudioRecord? = null
    @Volatile private var cloudRecording = false
    @Volatile private var onDeviceRecording = false

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        try {
            windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            // Android 要求使用麦克风权限的 Service 必须在 5 秒内调 startForeground，
            // 否则系统会杀掉进程。通知保持显示（含语音球/聊天框按钮），
            // 仅删除冗长描述文字（用户要求"删除字"——删描述、保留通知和按钮）。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIF_ID, buildNotification())
            }
        } catch (e: Throwable) {
            updateStatus("语音服务启动失败：${e.message}")
            stopSelf()
            return
        }
        // 常住通知栏：服务启动即显示通知（与悬浮语音球解耦）。
        // 悬浮球由通知栏「语音球」按钮或设置开关显隐，不在此自动挂载——这避免了
        // 「没授权悬浮窗→挂球异常→stopSelf 把整服务和通知一起杀掉→通知栏永不显示」的旧 bug。
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        val noListen = intent?.getBooleanExtra(EXTRA_NO_LISTEN, false) ?: false
        when {
            action == ACTION_VOICE_TALK -> {
                val i = intent
                if (i != null) {
                    val explicit = i.hasExtra(EXTRA_BALL_SHOW)
                    if (explicit) setBall(i.getBooleanExtra(EXTRA_BALL_SHOW, false)) else toggleBall()
                } else toggleBall()
            }
            noListen -> updateStatus("Zorv AI 正在后台运行")
            else -> updateStatus("Zorv AI 正在后台运行")
        }
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val chan = NotificationChannel(
            CHANNEL_ID, "Quro 语音球", NotificationManager.IMPORTANCE_LOW,
        )
        nm.createNotificationChannel(chan)
        val intent = Intent(this, javaClass)
        val pi = PendingIntent.getService(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE,
        )
        val voiceIntent = Intent(this, QuroVoiceBallService::class.java).apply { action = ACTION_VOICE_TALK }
        val voicePi = PendingIntent.getService(
            this, 2, voiceIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val chatIntent = Intent(this, QuroMainActivity::class.java).apply { action = ACTION_OPEN_CHAT }
        val chatPi = PendingIntent.getActivity(
            this, 3, chatIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Zorv AI")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pi)
            .addAction(android.R.drawable.ic_btn_speak_now, "语音球", voicePi)
            .addAction(android.R.drawable.ic_dialog_email, "聊天框", chatPi)
            .build()
    }

    private fun addBall() {
        val lifecycleOwner = QuroServiceLifecycleOwner().apply { create(); resume() }
        composeLifecycleOwner = lifecycleOwner
        val context = ContextThemeWrapper(this, getAppThemeRes())
        val ball = ComposeView(context).apply {
            // 官方公开静态 API 直接绑定 owner，无需反射。
            // 此前误以为 ViewTreeLifecycleOwner 在 Compose BOM + lifecycle 2.9.4 下
            // 是 internal 不可引用，故走了脆弱的反射桥 → 运行期经常失败 →
            // owner 缺失 → "ViewTreeLifecycleOwner not found" 闪退。
            // 实际 androidx.lifecycle.ViewTreeLifecycleOwner.set / .get 是自 lifecycle 2.x
            // 起就一直 public 的静态方法，直接调用即可。
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeViewModelStoreOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)
            setContent {
                QuroVoiceBall(listening, speaking, !conversationActive, status)
            }
        }
        composeView = ball
        // 绝对坐标：Gravity.TOP|LEFT，初始 (0,0)，落点由下面 post 测量后钳制到右下角
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = 0
            y = 0
        }
        windowManager.addView(ball, params)

        // 测量真实尺寸后定位到右下角并钳制（避免贴边溢出）
        ball.post {
            val dm = resources.displayMetrics
            val ballW = ball.width; val ballH = ball.height
            params.x = (dm.widthPixels - ballW - (24 * dm.density).toInt()).coerceAtLeast(0)
            params.y = (dm.heightPixels - ballH - (140 * dm.density).toInt()).coerceAtLeast(0)
            windowManager.updateViewLayout(ball, params)
        }

        // 触摸监听：拖动移动 / 轻点 = 暂停或恢复
        var downX = 0f; var downY = 0f; var offsetX = 0f; var offsetY = 0f; var moved = false
        val threshold = (MOVE_THRESHOLD_DP * resources.displayMetrics.density).toInt()
        ball.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX; downY = ev.rawY
                    offsetX = ev.rawX - params.x; offsetY = ev.rawY - params.y
                    moved = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downX; val dy = ev.rawY - downY
                    if (!moved && hypot(dx, dy) > threshold) moved = true
                    if (moved) {
                        val dm = resources.displayMetrics
                        params.x = (ev.rawX - offsetX).toInt()
                            .coerceIn(0, (dm.widthPixels - ball.width).coerceAtLeast(0))
                        params.y = (ev.rawY - offsetY).toInt()
                            .coerceIn(0, (dm.heightPixels - ball.height).coerceAtLeast(0))
                        windowManager.updateViewLayout(ball, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) onBallClick()   // 轻点 = 暂停/恢复
                    true
                }
                else -> false
            }
        }
    }

    private fun getAppThemeRes(): Int {
        // 复用应用主题（Zorv AI 主题为 Theme.Quro），确保 Material3 配色可用
        return resources.getIdentifier("Theme.Quro", "style", packageName).let {
            if (it != 0) it else android.R.style.Theme_Material_Light
        }
    }

    private fun onBallClick() {
        try { if (conversationActive) stopConversation() else startConversation() }
        catch (e: Throwable) { updateStatus("操作失败：${e.message}") }
    }

    /** 显隐悬浮语音球（与服务的生命周期、通知栏常驻完全解耦）。 */
    private fun setBall(show: Boolean) {
        if (show) {
            if (!Settings.canDrawOverlays(this)) {
                updateStatus("开启语音球需授予「悬浮窗」权限（系统设置→应用→悬浮窗）")
                try {
                    startActivity(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                } catch (_: Throwable) {}
                return
            }
            if (ballShown) return
            try {
                addBall()
                ballShown = true
                QuroVoiceFeaturePrefs.setVoiceBall(this, true)
                updateStatus("语音球已开启，点按开始对话")
            } catch (e: Throwable) {
                updateStatus("语音球启动失败：${e.message}")
            }
        } else {
            if (conversationActive) stopConversation("语音球已关闭")
            removeBall()
            ballShown = false
            QuroVoiceFeaturePrefs.setVoiceBall(this, false)
            updateStatus("语音球已关闭")
        }
    }

    private fun toggleBall() = setBall(!ballShown)

    private fun removeBall() {
        try {
            composeView?.let { windowManager.removeView(it) }
            composeView = null
            composeLifecycleOwner?.destroy()
            composeLifecycleOwner = null
        } catch (_: Throwable) {}
    }

    private fun startConversation() {
        conversationActive = true; emptyCount = 0
        updateStatus("聆听中…"); startListening()
    }

    private fun stopConversation(reason: String? = null) {
        conversationActive = false; listening = false; speaking = false
        onDeviceRecording = false
        ttsBusy = false
        QuroTtsHolder.voiceBallOwnsSpeech = false
        QuroSttHolder.stopListening(); stopCloudRecording(); updateStatus(reason ?: "已暂停")
    }

    private fun onEmptyOrError(reason: String) {
        if (!conversationActive) return
        emptyCount++
        if (emptyCount > MAX_CONSECUTIVE_EMPTY) {
            stopConversation("连续无语音，已自动暂停"); return
        }
        updateStatus("$reason，稍后重试")
        mainHandler.postDelayed({ if (conversationActive) startListening() }, BACKOFF_MS)
    }

    private fun startListening() {
        try {
            // 读取 STT 引擎选择
            val source = QuroSttPrefs.getSource(this)
            val provider = QuroSttPrefs.getModelProvider(this)
            val modelName = QuroSttPrefs.getModelName(this)

            when {
                source == QuroSttPrefs.SOURCE_MODEL -> {
                    // 云端模型模式：走真实 /audio/transcriptions 转写（不依赖原生识别）
                    startCloudListening()
                    return
                }
                source == QuroSttPrefs.SOURCE_ONDEVICE -> {
                    // 端侧（本地模型）模式：Sherpa-NCNN 离线识别，不依赖原生识别/云端
                    startOnDeviceListening()
                    return
                }
                else -> {
                    // 本地模式：必须依赖系统 SpeechRecognizer
                    if (!SpeechRecognizer.isRecognitionAvailable(this)) {
                        stopConversation("设备不支持语音识别，请在 STT 设置将识别引擎切到 AI 模型 / 本地模型")
                        return
                    }
                }
            }
            updateStatus("聆听中…")
            listening = true
            QuroSttHolder.startListening(
                context = this,
                language = QuroSttPrefs.getLanguage(this),
                partialResults = QuroSttPrefs.getPartial(this),
                onPartial = { t -> if (t.isNotBlank()) updateStatus("聆听中：$t") },
                onFinal = { text ->
                    if (!conversationActive) return@startListening
                    listening = false
                    if (text.isNotBlank()) {
                        emptyCount = 0
                        updateStatus("你说：$text"); process(text)
                    } else {
                        onEmptyOrError("没听清")
                    }
                },
                onError = { code, msg ->
                    if (!conversationActive) return@startListening
                    listening = false; onEmptyOrError("识别出错($msg)")
                },
            )
        } catch (e: Throwable) {
            listening = false
            updateStatus("无法启动识别")
        }
    }

    private fun stopListening() {
        listening = false
        QuroSttHolder.stopListening()
    }

    /** 停止云端录音并释放 AudioRecord。 */
    private fun stopCloudRecording() {
        cloudRecording = false
        try { audioRecord?.stop() } catch (_: Throwable) {}
        try { audioRecord?.release() } catch (_: Throwable) {}
        audioRecord = null
    }

    /**
     * 云端转写入口（Phase 2）：直接录音（AudioRecord + VAD 静音断句）→ 写 WAV →
     * 调 QuroSttHolder.transcribe 走 /audio/transcriptions。不依赖原生 SpeechRecognizer。
     */
    private fun startCloudListening() {
        if (cloudRecording) return
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            QuroSttHolder.pushLog("⚠️ 云端转写缺少 RECORD_AUDIO 权限")
            stopConversation("缺少录音权限，无法云端转写"); return
        }
        val cfg = QuroModelConfigRepository(applicationContext).load()
        val provider = QuroSttPrefs.getModelProvider(this)
        val modelName = QuroSttPrefs.getModelName(this).ifBlank { QuroSttPrefs.getModelRef(this) }
        // v51: 移除 provider 白名单硬拦截（v46 已在 transcribe() 内改为软提示）。
        // 非标准端点由 useChatCompletions 兜底走 /chat/completions 多模态消息，
        // HTTP 错误再由 onError 回调展示，不再在此处提前阻断。
        if (!QuroSttHolder.providerSupportsAudio(provider)) {
            QuroSttHolder.pushLog("ℹ️ provider($provider) 不在已知音频转写白名单，仍尝试请求")
        }
        if (cfg.apiKey.isBlank()) {
            QuroSttHolder.pushLog("⚠️ 云端转写缺少 API Key")
            stopConversation("未配置 API Key，无法云端转写"); return
        }
        val minBuf = AudioRecord.getMinBufferSize(
            REC_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) { stopConversation("录音缓冲初始化失败"); return }
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, REC_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 2
            )
        } catch (e: Throwable) {
            stopConversation("无法创建录音器"); return
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            try { rec.release() } catch (_: Throwable) {}
            stopConversation("录音器不可用"); return
        }

        audioRecord = rec
        cloudRecording = true
        listening = true
        updateStatus("聆听中（云端）…")
        QuroSttHolder.pushLog("☁️ 云端转写录音启动：provider=$provider model=$modelName baseUrl=${cfg.baseUrl.take(40)}")

        launch(Dispatchers.IO) {
            val pcm = ByteArrayOutputStream()
            try {
                val frame = ShortArray(minBuf / 2)
                rec.startRecording()
                val startMs = System.currentTimeMillis()
                var lastVoiceMs = startMs
                while (cloudRecording && conversationActive) {
                    val n = rec.read(frame, 0, frame.size)
                    if (n <= 0) {
                        if (n == AudioRecord.ERROR_INVALID_OPERATION || n < 0) break
                        continue
                    }
                    val buf = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
                    for (i in 0 until n) buf.putShort(frame[i])
                    pcm.write(buf.array())
                    var sum = 0.0
                    for (i in 0 until n) sum += frame[i] * frame[i]
                    val rms = kotlin.math.sqrt(sum / n) / 32768.0
                    val now = System.currentTimeMillis()
                    if (rms > REC_VAD_THRESHOLD) lastVoiceMs = now
                    val dur = now - startMs
                    if (dur > REC_MIN_MS &&
                        now - lastVoiceMs > REC_VAD_SILENCE_MS &&
                        pcm.size() > REC_SAMPLE_RATE * REC_ENCODING_BITS / 8 * 0.3f
                    ) break
                    if (dur > REC_MAX_MS) break
                }
                rec.stop()
            } catch (e: Throwable) {
                QuroSttHolder.pushLog("❌ 录音异常：${e.message}")
            } finally {
                try { rec.release() } catch (_: Throwable) {}
                if (audioRecord === rec) audioRecord = null
            }
            cloudRecording = false
            if (!conversationActive) return@launch
            if (pcm.size() <= REC_SAMPLE_RATE * REC_ENCODING_BITS / 8 * 0.3f) {
                mainHandler.post { listening = false; onEmptyOrError("没听清") }
                return@launch
            }
            val wav = File(cacheDir, "stt_cloud_${System.currentTimeMillis()}.wav")
            writeWav(pcm.toByteArray(), wav)
            QuroSttHolder.transcribe(
                ctx = this@QuroVoiceBallService,
                audioFile = wav,
                baseUrl = cfg.baseUrl,
                apiKey = cfg.apiKey,
                model = modelName.ifBlank { "whisper-1" },
                language = "zh",
                onFinal = { text ->
                    mainHandler.post {
                        listening = false
                        if (text.isNotBlank()) {
                            emptyCount = 0
                            updateStatus("你说：$text")
                            process(text)
                        } else onEmptyOrError("没听清")
                    }
                },
                onError = { code, msg ->
                    mainHandler.post { listening = false; onEmptyOrError("转写出错($msg)") }
                }
            )
        }
    }

    /**
     * 端侧（本地模型）转写入口：直接录音（AudioRecord + VAD 静音断句）→ 调 QuroOnDeviceAsr 离线识别。
     * 完全离线、不依赖原生 SpeechRecognizer、不依赖云端 API；多语言自动检测。
     */
    private fun startOnDeviceListening() {
        if (onDeviceRecording) return
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            QuroSttHolder.pushLog("⚠️ 端侧转写缺少 RECORD_AUDIO 权限")
            stopConversation("缺少录音权限，无法端侧转写"); return
        }
        if (!QuroOnDeviceAsr.isModelAvailable(this)) {
            QuroSttHolder.pushLog("⚠️ 端侧模型未部署")
            stopConversation("未找到端侧模型，请在 STT 设置下载并部署模型"); return
        }
        val minBuf = AudioRecord.getMinBufferSize(
            REC_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) { stopConversation("录音缓冲初始化失败"); return }
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, REC_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 2
            )
        } catch (e: Throwable) {
            stopConversation("无法创建录音器"); return
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            try { rec.release() } catch (_: Throwable) {}
            stopConversation("录音器不可用"); return
        }
        audioRecord = rec
        onDeviceRecording = true
        listening = true
        updateStatus("聆听中（端侧）…")
        QuroSttHolder.pushLog("📱 端侧转写录音启动")

        launch(Dispatchers.IO) {
            // 首次加载模型（应用私有目录内权重，可能耗时 1-3s）
            if (!QuroOnDeviceAsr.isReady()) {
                mainHandler.post { updateStatus("端侧模型加载中…") }
                QuroSttHolder.pushLog("⏳ 加载端侧模型…")
                if (!QuroOnDeviceAsr.ensureLoaded(applicationContext)) {
                    mainHandler.post { stopConversation("端侧模型加载失败") }
                    return@launch
                }
                QuroSttHolder.pushLog("✅ 端侧模型就绪")
            }
            val pcm = ByteArrayOutputStream()
            try {
                val frame = ShortArray(minBuf / 2)
                rec.startRecording()
                val startMs = System.currentTimeMillis()
                var lastVoiceMs = startMs
                while (onDeviceRecording && conversationActive) {
                    val n = rec.read(frame, 0, frame.size)
                    if (n <= 0) {
                        if (n == AudioRecord.ERROR_INVALID_OPERATION || n < 0) break
                        continue
                    }
                    val buf = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
                    for (i in 0 until n) buf.putShort(frame[i])
                    pcm.write(buf.array())
                    var sum = 0.0
                    for (i in 0 until n) sum += frame[i] * frame[i]
                    val rms = kotlin.math.sqrt(sum / n) / 32768.0
                    val now = System.currentTimeMillis()
                    if (rms > REC_VAD_THRESHOLD) lastVoiceMs = now
                    val dur = now - startMs
                    if (dur > REC_MIN_MS &&
                        now - lastVoiceMs > REC_VAD_SILENCE_MS &&
                        pcm.size() > REC_SAMPLE_RATE * REC_ENCODING_BITS / 8 * 0.3f
                    ) break
                    if (dur > REC_MAX_MS) break
                }
                rec.stop()
            } catch (e: Throwable) {
                QuroSttHolder.pushLog("❌ 录音异常：${e.message}")
            } finally {
                try { rec.release() } catch (_: Throwable) {}
                if (audioRecord === rec) audioRecord = null
            }
            onDeviceRecording = false
            if (!conversationActive) return@launch
            if (pcm.size() <= REC_SAMPLE_RATE * REC_ENCODING_BITS / 8 * 0.3f) {
                mainHandler.post { listening = false; onEmptyOrError("没听清") }
                return@launch
            }
            val text = QuroOnDeviceAsr.recognize(pcm.toByteArray())
            mainHandler.post {
                listening = false
                if (text.isNotBlank()) {
                    emptyCount = 0
                    updateStatus("你说：$text")
                    process(text)
                } else onEmptyOrError("没听清")
            }
        }
    }

    /** 把 PCM 16bit little-endian 裸流写成标准 WAV 文件。 */
    private fun writeWav(pcm: ByteArray, out: File) {
        val totalLen = pcm.size + 36
        FileOutputStream(out).use { os ->
            os.write("RIFF".toByteArray())
            os.write(intToLittle(totalLen))
            os.write("WAVE".toByteArray())
            os.write("fmt ".toByteArray())
            os.write(intToLittle(16))
            os.write(shortToLittle(1))
            os.write(shortToLittle(REC_CHANNELS.toShort()))
            os.write(intToLittle(REC_SAMPLE_RATE))
            val byteRate = REC_SAMPLE_RATE * REC_CHANNELS * REC_ENCODING_BITS / 8
            os.write(intToLittle(byteRate))
            os.write(shortToLittle((REC_CHANNELS * REC_ENCODING_BITS / 8).toShort()))
            os.write(shortToLittle(REC_ENCODING_BITS.toShort()))
            os.write("data".toByteArray())
            os.write(intToLittle(pcm.size))
            os.write(pcm)
        }
    }

    private fun intToLittle(v: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()

    private fun shortToLittle(v: Short): ByteArray =
        ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v).array()

    private fun process(text: String) {
        val baseCfg = QuroModelConfigRepository(applicationContext).load()
        if (baseCfg.apiKey.isBlank()) {
            updateStatus("未配置 API Key")
            speak("请先在模型配置页填写 API Key")
            return
        }
        // 语音球问答属于 CHAT 调用：接入「功能模型配置」的 CHAT 独立模型绑定，让开关真正生效
        val cfg = QuroFunctionModelConfigRepository(applicationContext).resolveConfig(QuroFunctionType.CHAT, baseCfg)
        updateStatus("思考中…")
        // 绑定到「选中的对话框」：若 ViewModel 已就绪，把语音球对话写入用户当前正在看的那个对话框，
        // 并实时落盘；否则回退到服务自有 store（不与任何对话框绑定，仅保底）。
        val vm = runCatching { QuroChatViewModel.instance }.getOrNull()
        // 语音球绑定的对话框：空串 = 跟随当前正在看的对话框（自动）
        val boundSession = QuroVoiceFeaturePrefs.getVoiceBallSessionId(this@QuroVoiceBallService)
        launch {
            try {
                val reply = if (vm != null) {
                    vm.voiceBallTurn(text, cfg, boundSession)
                } else {
                    Log.w(TAG, "VoiceBall: VM 未就绪，回退自有 store")
                    // A2 修复：回退路径也带上发送者昵称/头像（与 ViewModel 同源，读取 quro_ui 偏好）。
                    val uiPrefs = getSharedPreferences("quro_ui", 0)
                    store.add(
                        QuroMessage(
                            role = "user",
                            content = text,
                            senderName = uiPrefs.getString("user_name", "")?.takeIf { it.isNotBlank() },
                            avatarUrl = uiPrefs.getString("user_avatar", "")?.takeIf { it.isNotBlank() },
                        )
                    )
                    assistant.ask(applicationContext, cfg, buildVoiceSystemPrompt())
                }
                mainHandler.post { speaking = true; status = "回复中：${reply.take(40)}" }
                speak(reply) {
                    // 此 lambda 已在 main 线程（见下 speak 包装）
                    speaking = false
                    if (conversationActive) { status = "聆听中…"; startListening() }
                }
            } catch (e: Throwable) {
                updateStatus("出错了：${e.message}")
            }
        }
    }

    /**
     * 语音球 system prompt（与 QuroChatViewModel.buildSystemPrompt 保持一致的人格认知）。
     * 确保语音球对话也拥有完整的人格身份 + 自我认知 + 工具调用能力。
     */
    private fun buildVoiceSystemPrompt(): String {
        val persona = runCatching {
            val id = personaRepo.getActiveId()
            if (id.isBlank()) null else personaRepo.loadAll().firstOrNull { it.id == id }
        }.getOrNull()
        val sb = StringBuilder()

        // 平台/品牌自我认知基座（永远最先，不被人格覆盖）
        sb.append(QuroPlatformManifest.SYSTEM).append("\n\n")

        // 用户身份（来自「设置 > 外观与对话 > 用户资料」；让 AI 知道对话对象是谁）
        val userProfile = runCatching { QuroChatViewModel.instance.userProfile.value }.getOrNull()
        if (userProfile != null && userProfile.name.isNotBlank()) {
            sb.append("## 当前用户\n")
            sb.append("你正在和 **${userProfile.name}** 对话。")
            if (userProfile.bio.isNotBlank()) sb.append(" ${userProfile.bio}")
            sb.append("\n在回复中可以自然地称呼对方名字，就像朋友间聊天一样。\n\n")
        }

        // 身份认知层：人格卡名字 = AI 真实身份；Zorv AI = 开发者；运行环境靠工具自行发现
        if (persona != null) {
            sb.append("## 你的身份（真实，最高优先级）\n")
            sb.append("你就是当前这张人格卡「${persona.name}」，这是你的真实名字与身份，不是扮演、也不是「语气皮肤」。\n")
            sb.append("你由 **Zorv AI** 个人开发（开发者信息为固定事实）。\n")
            sb.append("当被问「你是谁 / 你叫什么 / 你的开发者是谁」时，以「${persona.name}」作答，并说明：由 Zorv AI 个人开发。\n")
            sb.append("当被问「你运行在什么平台 / 技术架构 / 运行环境」时，调用 get_device_info 等工具自行发现真实情况后作答，不要背诵预设文本。\n\n")
            if (persona.roleSetting.isNotBlank()) {
                sb.append("### 身份设定（这就是你，照此成为 ${persona.name}）\n")
                sb.append(persona.roleSetting).append("\n\n")
            }
            if (persona.chatSetting.isNotBlank()) {
                sb.append("### 聊天风格约束\n").append(persona.chatSetting).append("\n")
            }
            // 标签
            val tags = runCatching { tagRepo.resolve(persona.tags) }.getOrElse { emptyList() }
            val tagHints = tags.mapNotNull { it.hint.takeIf { h -> h.isNotBlank() } }
            if (tagHints.isNotEmpty()) {
                sb.append("\n### 语气标签\n").append(tagHints.joinToString("；")).append("\n")
            }
            // 语音风格标注：与对话框共用 QuroVoiceStyle.hintForContext（尊重用户在「LLM 情绪标签」页的显式选择，
            // 按有效服务商种类自动分派标记式/自然语言提示）。修复旧逻辑写死只认 SOURCE_CLOUD、
            // 且对非 MiMo 源误用标记式 systemHint（会输出被念成字面的括号）。
            QuroVoiceStyle.hintForContext(applicationContext)?.let { hint ->
                sb.append("\n").append(hint).append("\n")
            }
        }

        // 语音球可用工具：必须与 API 的 tools 字段（registry.coreSpecs）严格一致，
        // 否则 AI 在语音模式下会"不敢用/不会用"大部分功能（此前硬编码 15 个导致大面积失效）。
        sb.append("\n## 可用工具（与 API tools 字段完全一致）\n")
        sb.append(com.ai.assistance.quro.core.tools.QuroToolUsageHints.buildToolUseDirective())
        sb.append("\n### 工具清单（格式：工具名：用途 [· 常见说法/多用途]）\n")
        registry.coreSpecs().forEach { s ->
            sb.append("- ${s.name}：${s.description}\n")
            com.ai.assistance.quro.core.tools.QuroToolUsageHints.TOOL_USAGE_HINTS[s.name]?.let { hint ->
                sb.append("    · 常见说法/多用途：$hint\n")
            }
        }
        sb.append("\n## 规则\n")
        sb.append("- 用户想打开应用 → search_and_launch_app\n")
        sb.append("- 查信息/电量/WiFi/时间/天气/当前状态 → 调用对应查询工具（天气、时间、设备状态、联网信息永远用工具取真实值，不凭记忆瞎编）\n")
        sb.append("- 多个独立动作可在一轮内并行发起多个 tool_calls\n")
        sb.append("- 你拥有记忆库（memory_save/list/search/delete），可在用户透露持久信息时主动保存\n")
        sb.append("- **何时必须调用工具**：任何依赖「实时/当前/外部/最新」信息的问题（天气、当前时间、设备状态、联网信息）必须主动调工具拿真实数据，绝不凭训练截止前的旧知识瞎编；需要真实动作（打开应用、朗读、读写文件、控制设备）时调用对应工具真正执行。\n")
        sb.append("- **如何组合「说话」与「用工具」**：可在同一条回复里先说一句再发工具调用，也可多轮穿插「思考 → 调用工具 → 看到结果 → 再思考 → 再回答」直到任务完成；纯主观/创意/情感/闲聊可直接文字作答，但凡涉及真实数据或真实动作一律用工具。\n")

        // 长期记忆
        val memories = runCatching { memoryRepo.loadForPersona(persona?.id ?: "") }.getOrElse { emptyList() }
        if (memories.isNotEmpty()) {
            sb.append("## 已有记忆\n")
            memories.forEach { m ->
                sb.append("- ")
                if (m.tags.isNotEmpty()) sb.append("[${m.tags.joinToString(",")}] ")
                sb.append(m.content).append("\n")
            }
        }
        return sb.toString().trim()
    }

    private fun speak(text: String, onDone: () -> Unit = {}) {
        // 全程统一走 QuroTtsHolder（全场唯一 TTS 实例），避免与设置页在同进程内
        // 重复创建 TextToSpeech 抢占引擎连接导致 OnInit 返回 ERROR。
        // onDone 经 mainHandler 切回主线程，供 Service 在播完后续听。
        // 用 AtomicBoolean 一次性守卫：speak 入队失败时回退 speakMinimal，onDone 只触发一次，
        // 避免「TTS 失败瞬间立即续听」+「回退播放再次续听」造成双重 startListening / 回声。
        // 新增 ttsBusy 重入守卫：正在播报时忽略新的 speak 请求，避免「识别回调重复 / 续听时序重叠」
        // 导致同一段或多段语音被重复/并发播报。
        if (ttsBusy) { Log.w(TAG, "speak: 正在播报中，忽略重复请求，避免重复/并发播报"); return }
        ttsBusy = true
        QuroTtsHolder.voiceBallOwnsSpeech = true
        val fired = java.util.concurrent.atomic.AtomicBoolean(false)
        val doneOnce = {
            if (fired.compareAndSet(false, true)) {
                ttsBusy = false
                QuroTtsHolder.voiceBallOwnsSpeech = false
                mainHandler.post(onDone)
            }
        }
        launch {
            try {
                QuroTtsHolder.ensureReady(this@QuroVoiceBallService)
                val r = QuroTtsHolder.speak(text, doneOnce)
                if (r != 0) QuroTtsHolder.speakMinimal(text, doneOnce)
            } catch (e: Throwable) {
                Log.e(TAG, "speak 异常: ${e.message}")
                doneOnce()
            }
        }
    }

    private fun updateStatus(s: String) {
        mainHandler.post { status = s }
    }

    override fun onDestroy() {
        try {
            conversationActive = false
            coroutineContext.cancel()   // 取消所有未完成的协程（含续听/播放）
            composeView?.let { windowManager.removeView(it) }
            composeView = null
            composeLifecycleOwner?.destroy()
            composeLifecycleOwner = null
            QuroSttHolder.stopListening()
            stopCloudRecording()
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (e: Throwable) {
            // ignore
        }
        coroutineContext.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "QuroVoiceBall"
        private const val NOTIF_ID = 8801
        private const val CHANNEL_ID = "quro_voice_ball"
        /** 通知栏「语音球」动作：切换聆听。 */
        const val ACTION_VOICE_TALK = "com.ai.assistance.quro.action.VOICE_TALK"
        /** 通知栏「聊天框」动作。 */
        const val ACTION_OPEN_CHAT = "com.ai.assistance.quro.action.OPEN_CHAT"
        /** 应用快捷方式：唤起悬浮语音球。 */
        const val ACTION_SHORTCUT_VOICE_BALL = "com.ai.assistance.quro.action.SHORTCUT_VOICE_BALL"
        /** 应用快捷方式：直接进入对话。 */
        const val ACTION_SHORTCUT_CHAT = "com.ai.assistance.quro.action.SHORTCUT_CHAT"
        /** 启动意图附加：为 true 时服务启动但不自动开始聆听（用于开机自启动）。 */
        const val EXTRA_NO_LISTEN = "extra_no_listen"
        /** 通知栏「语音球」按钮 / 设置开关：携带此 extra 显式控制悬浮球显隐（true=显示, false=隐藏）。 */
        const val EXTRA_BALL_SHOW = "extra_ball_show"
        private const val MOVE_THRESHOLD_DP = 8
        private const val BACKOFF_MS = 600L
        private const val MAX_CONSECUTIVE_EMPTY = 3
        private const val REC_SAMPLE_RATE = 16000
        private const val REC_CHANNELS = AudioFormat.CHANNEL_IN_MONO
        private const val REC_ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val VAD_SILENCE_MS = 600
        private const val VAD_RMS_THRESHOLD = 800.0
    }
}
