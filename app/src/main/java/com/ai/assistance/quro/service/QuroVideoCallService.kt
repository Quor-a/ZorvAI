package com.ai.assistance.quro.service
import androidx.compose.ui.res.stringResource
import com.ai.assistance.quro.R
import com.ai.assistance.quro.util.qstr

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.NotificationCompat
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import com.ai.assistance.quro.core.QuroAssistant
import com.ai.assistance.quro.core.QuroAttachmentKit
import com.ai.assistance.quro.core.QuroConversationStore
import com.ai.assistance.quro.core.QuroMessage
import com.ai.assistance.quro.core.QuroPlatformManifest
import com.ai.assistance.quro.core.QuroReplyLanguage
import com.ai.assistance.quro.core.model.QuroFunctionModelConfigRepository
import com.ai.assistance.quro.core.model.QuroFunctionType
import com.ai.assistance.quro.core.model.QuroModelConfigRepository
import com.ai.assistance.quro.core.network.QuroLlmClient
import com.ai.assistance.quro.core.tools.QuroSttHolder
import com.ai.assistance.quro.core.tools.QuroSttPrefs
import com.ai.assistance.quro.core.tools.QuroTtsHolder
import com.ai.assistance.quro.core.tools.buildQuroRegistry
import com.ai.assistance.quro.core.util.QuroServiceLifecycleOwner
import com.ai.assistance.quro.activity.QuroMainActivity
import com.ai.assistance.quro.util.QuroLocale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 视频通话服务（参考 QuroVoiceBallService 语音球实现）。
 *
 * 悬浮窗里是一套**完整的通话界面**：顶部状态条（可拖动）+ 实时摄像头预览 + 字幕 + 底部三键控制
 * （麦克风开关 / 前后摄切换 / 挂断），并在语音对话之上叠加两层画面理解：
 *  - 视觉识别层：把实时摄像头画面作为图片直接喂给「当前多模态模型」，让模型亲眼看到用户环境
 *    （与 visual_analysis 的 Level1 同源；当前模型不支持视觉时退化为视频识别层）。
 *  - 视频识别层：当前模型非视觉时，调用「功能模型配置 → 视频通话 / 视频识别」绑定的模型描述画面，
 *    把文字结果作为上下文注入对话。
 *
 * 模型来源：对话走「功能模型配置 → 视频通话」的绑定（[QuroFunctionType.VIDEO_CALL]），
 * 未指定独立模型时跟随主模型。
 *
 * 摄像头走框架 Camera2（无需新增依赖）；对话/STT/TTS 复用语音球的成熟链路。
 *
 * ## 关键修复（本轮）
 * 原实现的 [textureView] 从未被赋值 —— [VideoCallScreen] 里只有状态文字与两个按钮，
 * 没有任何视图承载 [surfaceTextureListener]，于是：① 用户看不到摄像头画面（黑框）；
 * ② [captureFrame] 因 `textureView == null` 恒返回 null，画面理解层等于完全失效。
 * 现在用 [AndroidView] 把 [TextureView] 真正挂进悬浮窗，预览与截帧链路同时打通。
 */
class QuroVideoCallService : Service(), CoroutineScope by CoroutineScope(Dispatchers.Main + SupervisorJob()) {

    private lateinit var windowManager: WindowManager
    private var composeView: ComposeView? = null
    private var composeLifecycleOwner: QuroServiceLifecycleOwner? = null
    private var viewParams: WindowManager.LayoutParams? = null

    // 视频通话使用服务自有的会话 store（与语音球回退路径同源），避免改动对话框主链路。
    private val store = QuroConversationStore()
    private val registry by lazy { buildQuroRegistry(this) }
    private val assistant by lazy { QuroAssistant(QuroLlmClient(), registry, store) }

    private val mainHandler = Handler(Looper.getMainLooper())

    private var callActive by mutableStateOf(false)
    private var listening by mutableStateOf(false)
    private var speaking by mutableStateOf(false)
    private var muted by mutableStateOf(false)
    private var frontCamera by mutableStateOf(true)
    private var status by mutableStateOf(qstr(R.string.qk_03874))
    /** 预览区字幕：显示用户这句话 / AI 这句话。 */
    private var subtitle by mutableStateOf("")

    // ── Camera2 ──
    private val cameraThread = HandlerThread("video-call-camera").also { it.start() }
    private val cameraHandler = Handler(cameraThread.looper)
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var textureView: TextureView? = null
    private var lensFacing = CameraCharacteristics.LENS_FACING_FRONT

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        try {
            windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 14+ 要求声明的前台服务类型必须与「已授予的运行时权限」匹配，
                // 否则 startForeground 直接抛 SecurityException。按实际授权拼类型。
                var t = 0
                if (checkSelfPermission(android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
                    t = t or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                    t = t or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                if (t == 0) t = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                startForeground(NOTIF_ID, buildNotification(), t)
            } else {
                startForeground(NOTIF_ID, buildNotification())
            }
        } catch (e: Throwable) {
            Log.e(TAG, "VideoCall onCreate failed", e)
            stopSelf(); return
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_VIDEO_CALL -> toggleCall()
            ACTION_START -> startCall()
            ACTION_STOP -> stopCall()
            else -> status = qstr(R.string.qk_03875)
        }
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val chan = NotificationChannel(CHANNEL_ID, qstr(R.string.qk_03893), NotificationManager.IMPORTANCE_LOW)
        nm.createNotificationChannel(chan)
        val toggleIntent = Intent(this, QuroVideoCallService::class.java).apply { action = ACTION_VIDEO_CALL }
        val togglePi = PendingIntent.getService(
            this, 0, toggleIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val chatIntent = Intent(this, QuroMainActivity::class.java).apply { action = QuroVoiceBallService.ACTION_OPEN_CHAT }
        val chatPi = PendingIntent.getActivity(
            this, 3, chatIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(qstr(R.string.qk_03893))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(togglePi)
            .addAction(android.R.drawable.ic_btn_speak_now, qstr(R.string.qk_03894), togglePi)
            .addAction(android.R.drawable.ic_dialog_email, qstr(R.string.qk_03895), chatPi)
            .build()
    }

    // ──────────────────── 通话开关 ────────────────────

    private fun toggleCall() {
        if (callActive) stopCall() else startCall()
    }

    private fun startCall() {
        if (callActive) return
        callActive = true
        muted = false
        subtitle = ""
        if (!addCallView()) {
            callActive = false
            status = qstr(R.string.qk_03878)
            return
        }
        status = qstr(R.string.qk_03876)
        startListening()
    }

    private fun stopCall() {
        callActive = false
        listening = false; speaking = false
        QuroSttHolder.stopListening()
        closeCamera()
        removeCallView()
        status = qstr(R.string.qk_03877)
        subtitle = ""
    }

    /** 麦克风开关（静音时停掉识别，但摄像头画面继续）。 */
    private fun toggleMute() {
        muted = !muted
        if (muted) {
            QuroSttHolder.stopListening()
            listening = false
            status = qstr(R.string.qk_03888)
        } else {
            status = qstr(R.string.qk_03889)
            if (callActive) startListening()
        }
    }

    /** 前后摄切换：必须 close → 重开，Camera2 不允许在同一个 device 上改镜头。 */
    private fun switchCamera() {
        frontCamera = !frontCamera
        lensFacing = if (frontCamera) CameraCharacteristics.LENS_FACING_FRONT
        else CameraCharacteristics.LENS_FACING_BACK
        status = if (frontCamera) qstr(R.string.qk_03890) else qstr(R.string.qk_03891)
        closeCamera()
        cameraHandler.post { openCamera() }
    }

    // ──────────────────── 对话链路（复用语音球 STT/TTS） ────────────────────

    private fun startListening() {
        if (muted) { status = qstr(R.string.qk_03888); return }
        try {
            if (!SpeechRecognizer.isRecognitionAvailable(this)) {
                status = qstr(R.string.qk_03881); return
            }
            listening = true
            status = qstr(R.string.qk_01757)
            QuroSttHolder.startListening(
                context = this,
                language = QuroSttPrefs.getLanguage(this),
                partialResults = QuroSttPrefs.getPartial(this),
                onPartial = { t -> if (t.isNotBlank()) status = qstr(R.string.qk_03879, (t).toString()) },
                onFinal = { text ->
                    if (!callActive) return@startListening
                    listening = false
                    if (text.isNotBlank()) {
                        subtitle = qstr(R.string.qk_03900, (text).toString())
                        status = qstr(R.string.qk_00093)
                        process(text)
                    } else {
                        status = qstr(R.string.qk_03880)
                        scheduleListen()
                    }
                },
                onError = { _, msg ->
                    if (!callActive) return@startListening
                    listening = false; status = qstr(R.string.qk_03882, (msg).toString()); scheduleListen()
                },
            )
        } catch (e: Throwable) {
            listening = false; status = qstr(R.string.qk_03883)
        }
    }

    private fun scheduleListen() {
        mainHandler.postDelayed({ if (callActive && !listening && !speaking) startListening() }, 600)
    }

    private fun process(text: String) {
        // 朗读协调：每轮复位「本轮 AI 是否用 speak 工具播报」标记（与语音球一致）。
        QuroTtsHolder.speakToolFiredThisTurn = false
        val baseCfg = QuroModelConfigRepository(applicationContext).load()
        if (baseCfg.apiKey.isBlank()) {
            status = qstr(R.string.qk_03884)
            speak(qstr(R.string.qk_03885)); return
        }
        // 视频通话接入「功能模型配置 → 视频通话」的独立模型绑定；未指定独立模型时跟随主模型。
        val cfg = QuroFunctionModelConfigRepository(applicationContext)
            .resolveConfig(QuroFunctionType.VIDEO_CALL, baseCfg)

        // 视觉识别层：把当前实时画面作为图片注入本轮对话（当前模型支持视觉时）；
        // 否则退化为视频识别层（用「视频通话 / 视频识别」配置描述画面并注入文字）。
        injectFrameContext(cfg.model)

        status = qstr(R.string.qk_00093)
        launch {
            try {
                store.add(QuroMessage(role = "user", content = text))
                val reply = assistant.ask(applicationContext, cfg, buildVideoSystemPrompt())
                mainHandler.post { speaking = true; subtitle = reply; status = qstr(R.string.qk_03886) }
                speak(reply) {
                    speaking = false
                    if (callActive) { status = qstr(R.string.qk_01757); startListening() }
                }
            } catch (e: Throwable) {
                mainHandler.post { status = qstr(R.string.qk_03887, (e.message).toString()) }
                scheduleListen()
            }
        }
    }

    private fun buildVideoSystemPrompt(): String {
        val sb = StringBuilder()
        sb.append(QuroPlatformManifest.SYSTEM).append("\n\n")
        sb.append(
            "你正在与用户进行「视频通话」：你能通过实时摄像头画面看到用户当前的环境，" +
                "画面会以图片形式随对话传入。请基于看到的画面回答用户的问题、描述场景、" +
                "识别物体 / 文字 / 人物、给出操作建议，就像你真的在看着摄像头一样。" +
                "若某轮没有附带画面，则用语言正常回答。\n"
        )
        // 语言一致性：通话回复必须是用户当前的界面语言。
        // 旧实现只在「显式选了非中文语言」时才补一句英文提示，「跟随系统」时整段跳过
        // → 系统是英文时视频通话仍用中文说话（且 TTS 照念中文）。现统一走 QuroReplyLanguage。
        sb.append(QuroReplyLanguage.directive(applicationContext))
        sb.append(QuroReplyLanguage.tailReminder(applicationContext))
        return sb.toString()
    }

    private fun speak(text: String, onDone: () -> Unit = {}) {
        launch {
            try {
                QuroTtsHolder.speak(text, onDone = { mainHandler.post(onDone) })
            } catch (e: Throwable) {
                Log.e(TAG, "speak failed", e)
                mainHandler.post(onDone)
            }
        }
    }

    // ──────────────────── 识别层：视觉 / 视频 ────────────────────

    /** 把当前实时画面作为上下文注入会话：视觉模型→注入图片；非视觉→视频识别层生成文字描述。 */
    private fun injectFrameContext(model: String) {
        val frame = captureFrame() ?: return
        if (isLikelyVisionModel(model)) {
            // 视觉识别层：直接把图片喂给当前多模态模型「亲眼」看。
            val att = QuroAttachmentKit.fromFile(this, frame, "image/jpeg")
            store.add(
                QuroMessage(
                    role = "user",
                    content = "[视频通话实时画面·请直接查看并描述]",
                    attachments = listOf(att),
                    hidden = true,
                )
            )
        } else {
            // 视频识别层：调绑定模型描述画面，注入文字上下文。
            val caption = captionFrame(frame)
            if (caption != null) {
                store.add(QuroMessage(role = "user", content = "[视频画面识别] $caption", hidden = true))
            }
        }
    }

    /** 截图当前 TextureView 画面并压缩为 JPEG 文件（无需 ImageReader，直接用预览位图）。 */
    private fun captureFrame(): File? {
        val tv = textureView ?: return null
        if (!tv.isAvailable) return null
        return try {
            val bmp = tv.getBitmap(640, 480) ?: return null
            val f = File(filesDir, "videocall_frame_${System.currentTimeMillis()}.jpg")
            f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 80, it) }
            bmp.recycle()
            f
        } catch (e: Throwable) {
            Log.e(TAG, "captureFrame failed", e); null
        }
    }

    /**
     * 视频识别层：把单帧发给绑定模型，返回一句话描述（无密钥返回 null）。
     * 优先用「视频通话」的独立绑定（视频通话模型 = 画面理解模型），未独立配置时回落「视频识别」。
     */
    private fun captionFrame(file: File): String? {
        return try {
            val global = QuroModelConfigRepository(applicationContext).load()
            val repo = QuroFunctionModelConfigRepository(applicationContext)
            val callBinding = repo.getBinding(QuroFunctionType.VIDEO_CALL)
            val vcfg = if (!callBinding.useGlobal && callBinding.model.isNotBlank()) {
                repo.resolveConfig(QuroFunctionType.VIDEO_CALL, global)
            } else {
                repo.resolveConfig(QuroFunctionType.VIDEO_RECOGNITION, global)
            }
            if (vcfg.apiKey.isBlank()) return null
            val model = vcfg.model.ifBlank { "gpt-4o" }
            val baseUrl = vcfg.baseUrl.ifBlank { "https://api.openai.com/v1" }
            val b64 = android.util.Base64.encodeToString(file.readBytes(), android.util.Base64.NO_WRAP)
            val json = JSONObject().apply {
                put("model", model)
                put("messages", org.json.JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", org.json.JSONArray().apply {
                            put(JSONObject().apply { put("type", "text"); put("text", "简要描述这张实时画面的主要内容（一句话）") })
                            put(JSONObject().apply {
                                put("type", "image_url")
                                put("image_url", JSONObject().apply { put("url", "data:image/jpeg;base64,$b64"); put("detail", "low") })
                            })
                        })
                    })
                })
                put("max_tokens", 200)
            }
            val req = Request.Builder()
                .url(baseUrl.trimEnd('/') + "/chat/completions")
                .addHeader("Authorization", "Bearer ${vcfg.apiKey}")
                .post(json.toString().toRequestBody("application/json".toMediaType()))
                .build()
            val resp = httpClient.newCall(req).execute()
            if (resp.isSuccessful) {
                val j = JSONObject(resp.body?.string() ?: "")
                j.getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content")
            } else null
        } catch (e: Throwable) {
            Log.e(TAG, "captionFrame failed", e); null
        }
    }

    /** 当前主模型是否大概率支持视觉输入（与 VisualAnalysisTool 同源判断）。默认乐观。 */
    private fun isLikelyVisionModel(name: String): Boolean {
        val n = name.lowercase()
        if (n.isBlank()) return true
        val textOnly = listOf("gpt-3.5", "text-embedding", "babbage", "davinci", "ada", "tts-1", "whisper",
            "embedding", "instruct", "llama-2", "llama2", "qwen2-0.5b", "qwen2-1.5b", "qwen2-7b-instruct",
            "qwen2.5-0.5b", "qwen2.5-1.5b", "qwen3-0.6b", "qwen3-1.7b")
        if (textOnly.any { n.contains(it) }) return false
        return true
    }

    // ──────────────────── Camera2 ────────────────────

    private val surfaceTextureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, w: Int, h: Int) {
            openCamera()
        }
        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, w: Int, h: Int) {}
        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
            closeCamera(); return true
        }
        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
    }

    private fun openCamera() {
        if (checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            status = qstr(R.string.qk_03892); return
        }
        try {
            val mgr = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val camId = mgr.cameraIdList.firstOrNull { id ->
                mgr.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == lensFacing
            } ?: mgr.cameraIdList.firstOrNull() ?: return
            mgr.openCamera(camId, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) { cameraDevice = device; startPreview() }
                override fun onDisconnected(device: CameraDevice) { device.close(); cameraDevice = null }
                override fun onError(device: CameraDevice, error: Int) { device.close(); cameraDevice = null }
            }, cameraHandler)
        } catch (e: Throwable) {
            Log.e(TAG, "openCamera failed", e)
        }
    }

    private fun startPreview() {
        val tv = textureView ?: return
        val st = tv.surfaceTexture ?: return
        val device = cameraDevice ?: return
        try {
            st.setDefaultBufferSize(640, 480)
            val surface = Surface(st)
            val req = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            req.addTarget(surface)
            device.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    try { session.setRepeatingRequest(req.build(), null, cameraHandler) } catch (e: Throwable) {}
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {}
            }, cameraHandler)
        } catch (e: Throwable) {
            Log.e(TAG, "startPreview failed", e)
        }
    }

    private fun closeCamera() {
        try { captureSession?.close() } catch (_: Throwable) {}
        try { cameraDevice?.close() } catch (_: Throwable) {}
        captureSession = null; cameraDevice = null
    }

    // ──────────────────── 悬浮窗 UI ────────────────────

    /** 挂载通话界面；返回是否成功（无悬浮窗权限时返回 false）。 */
    private fun addCallView(): Boolean {
        if (composeView != null) return true
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "no overlay permission")
            return false
        }
        return try {
            val lifecycleOwner = QuroServiceLifecycleOwner().apply { create(); resume() }
            composeLifecycleOwner = lifecycleOwner
            val view = ComposeView(this).apply {
                setViewTreeLifecycleOwner(lifecycleOwner)
                setViewTreeViewModelStoreOwner(lifecycleOwner)
                setViewTreeSavedStateRegistryOwner(lifecycleOwner)
                setContent { VideoCallScreen() }
            }
            composeView = view
            val dm = resources.displayMetrics
            val w = (dm.widthPixels * 0.78).toInt().coerceAtMost(420)
            val h = (dm.heightPixels * 0.62).toInt().coerceAtMost(640)
            val params = WindowManager.LayoutParams(
                w, h,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.LEFT
                x = ((dm.widthPixels - w) / 2).coerceAtLeast(0)
                y = 120
            }
            viewParams = params
            windowManager.addView(view, params)
            true
        } catch (e: Throwable) {
            Log.e(TAG, "addCallView failed", e)
            composeView = null
            viewParams = null
            false
        }
    }

    private fun removeCallView() {
        try { composeView?.let { windowManager.removeView(it) } } catch (_: Throwable) {}
        composeView = null
        viewParams = null
        textureView = null
        try { composeLifecycleOwner?.destroy() } catch (_: Throwable) {}
        composeLifecycleOwner = null
    }

    /** 拖动悬浮窗（顶部状态条为拖拽把手）。 */
    private fun dragBy(dx: Int, dy: Int) {
        val v = composeView ?: return
        val p = viewParams ?: return
        p.x += dx; p.y += dy
        try { windowManager.updateViewLayout(v, p) } catch (_: Throwable) {}
    }

    @Composable
    private fun VideoCallScreen() {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(18.dp))
                .background(Color(0xF2101216)),
        ) {
            // ── ① 顶部状态条（拖拽把手 + 状态 + 关闭）──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        detectDragGestures { change, drag ->
                            change.consume()
                            dragBy(drag.x.toInt(), drag.y.toInt())
                        }
                    }
                    .padding(start = 12.dp, end = 6.dp, top = 10.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (callActive) Color(0xFF34C759) else Color(0xFF8E8E93))
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.qk_00183),
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                    Text(status, color = Color(0xFFB9BDC6), fontSize = 11.sp, maxLines = 1)
                }
                Box(
                    Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(Color(0x33FFFFFF))
                        .clickable { stopCall() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Close, stringResource(R.string.qk_00011), Modifier.size(16.dp), tint = Color.White)
                }
            }
            // ── ② 实时预览（真机摄像头画面）+ 字幕 ──
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.Black),
            ) {
                AndroidView(
                    factory = { ctx ->
                        TextureView(ctx).apply {
                            surfaceTextureListener = this@QuroVideoCallService.surfaceTextureListener
                            textureView = this
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                if (subtitle.isNotBlank()) {
                    Text(
                        subtitle,
                        color = Color.White,
                        fontSize = 11.sp,
                        maxLines = 4,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .background(Color(0x99000000))
                            .padding(8.dp),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            // ── ③ 底部控制：麦克风 / 切换摄像头 / 挂断 ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CallCircle(
                    icon = if (muted) Icons.Filled.MicOff else Icons.Filled.Mic,
                    label = if (muted) stringResource(R.string.qk_03897) else stringResource(R.string.qk_03896),
                    background = if (muted) Color(0x33FFFFFF) else Color(0xFF2C6BED),
                ) { toggleMute() }
                CallCircle(
                    icon = Icons.Filled.Cameraswitch,
                    label = stringResource(R.string.qk_03898),
                    background = Color(0x33FFFFFF),
                ) { switchCamera() }
                CallCircle(
                    icon = Icons.Filled.CallEnd,
                    label = stringResource(R.string.qk_03899),
                    background = Color(0xFFFF3B30),
                ) { stopCall() }
            }
        }
    }

    /** 圆形通话控制键 + 底部小字标签。 */
    @Composable
    private fun CallCircle(icon: ImageVector, label: String, background: Color, onClick: () -> Unit) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(background)
                    .clickable(onClick = onClick),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, label, Modifier.size(24.dp), tint = Color.White)
            }
            Spacer(Modifier.height(4.dp))
            Text(label, color = Color(0xFFB9BDC6), fontSize = 10.sp, maxLines = 1)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        callActive = false
        QuroSttHolder.stopListening()
        closeCamera()
        removeCallView()
        try { cameraHandler.looper.quitSafely() } catch (_: Throwable) {}
        cancel()
    }

    companion object {
        const val ACTION_VIDEO_CALL = "com.ai.assistance.quro.action.VIDEO_CALL"
        const val ACTION_START = "com.ai.assistance.quro.action.VIDEO_CALL_START"
        const val ACTION_STOP = "com.ai.assistance.quro.action.VIDEO_CALL_STOP"
        private const val NOTIF_ID = 9003
        private const val CHANNEL_ID = "zorv_video_call"
        private const val TAG = "QuroVideoCall"
    }
}
