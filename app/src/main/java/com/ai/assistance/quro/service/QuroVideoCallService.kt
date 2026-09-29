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
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
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
 * 在悬浮窗里打开摄像头预览，运行「语音球式」实时对话，并在对话之上叠加两层识别：
 *  - 视觉识别层：把实时摄像头画面作为图片直接喂给「当前多模态模型」，让模型亲眼看到用户环境
 *    （与 visual_analysis 的 Level1 同源；当前模型不支持视觉时退化为视频识别层）。
 *  - 视频识别层：当前模型非视觉时，调用「功能模型配置 → 视频识别」绑定的模型对画面做描述，
 *    把文字结果作为上下文注入对话。
 *
 * 摄像头走框架 Camera2（无需新增依赖）；对话/STT/TTS 复用语音球的成熟链路（QuroSttHolder / QuroTtsHolder / QuroAssistant）。
 */
class QuroVideoCallService : Service(), CoroutineScope by CoroutineScope(Dispatchers.Main + SupervisorJob()) {

    private lateinit var windowManager: WindowManager
    private var composeView: ComposeView? = null
    private var composeLifecycleOwner: QuroServiceLifecycleOwner? = null

    // 视频通话使用服务自有的会话 store（与语音球回退路径同源），避免改动对话框主链路。
    private val store = QuroConversationStore()
    private val registry by lazy { buildQuroRegistry(this) }
    private val assistant by lazy { QuroAssistant(QuroLlmClient(), registry, store) }

    private val mainHandler = Handler(Looper.getMainLooper())

    private var callActive by mutableStateOf(false)
    private var listening by mutableStateOf(false)
    private var speaking by mutableStateOf(false)
    private var status by mutableStateOf("点我开始视频通话")

    // ── Camera2 ──
    private val cameraThread = HandlerThread("video-call-camera").also { it.start() }
    private val cameraHandler = Handler(cameraThread.looper)
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var textureView: TextureView? = null

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        try {
            windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            else 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                startForeground(NOTIF_ID, buildNotification(), fgsType)
            else startForeground(NOTIF_ID, buildNotification())
        } catch (e: Throwable) {
            Log.e(TAG, "VideoCall onCreate failed", e)
            stopSelf(); return
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_VIDEO_CALL -> toggleCall()
            else -> status = "Zorv AI 视频通话待命"
        }
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val chan = NotificationChannel(CHANNEL_ID, "ZorvAI 视频通话", NotificationManager.IMPORTANCE_LOW)
        nm.createNotificationChannel(chan)
        val selfIntent = Intent(this, QuroVideoCallService::class.java).apply { action = ACTION_VIDEO_CALL }
        val pi = PendingIntent.getService(
            this, 0, selfIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val chatIntent = Intent(this, QuroMainActivity::class.java).apply { action = QuroVoiceBallService.ACTION_OPEN_CHAT }
        val chatPi = PendingIntent.getActivity(
            this, 3, chatIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Zorv AI 视频通话")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pi)
            .addAction(android.R.drawable.ic_btn_speak_now, "开关通话", pi)
            .addAction(android.R.drawable.ic_dialog_email, "聊天框", chatPi)
            .build()
    }

    // ──────────────────── 通话开关 ────────────────────

    private fun toggleCall() {
        if (callActive) stopCall() else startCall()
    }

    private fun startCall() {
        if (callActive) return
        callActive = true
        addCallView()
        status = "摄像头已开启 · 点「说话」开始"
        startListening()
    }

    private fun stopCall() {
        callActive = false
        listening = false; speaking = false
        QuroSttHolder.stopListening()
        closeCamera()
        removeCallView()
        status = "已结束视频通话"
    }

    // ──────────────────── 对话链路（复用语音球 STT/TTS） ────────────────────

    private fun startListening() {
        try {
            if (!SpeechRecognizer.isRecognitionAvailable(this)) {
                status = "设备不支持语音识别（请在 STT 设置切换引擎）"; return
            }
            listening = true
            status = qstr(R.string.qk_01757)
            QuroSttHolder.startListening(
                context = this,
                language = QuroSttPrefs.getLanguage(this),
                partialResults = QuroSttPrefs.getPartial(this),
                onPartial = { t -> if (t.isNotBlank()) status = "聆听中：$t" },
                onFinal = { text ->
                    if (!callActive) return@startListening
                    listening = false
                    if (text.isNotBlank()) { status = "你说：$text"; process(text) }
                    else { status = "没听清，稍后重试"; scheduleListen() }
                },
                onError = { _, msg ->
                    if (!callActive) return@startListening
                    listening = false; status = "识别出错($msg)"; scheduleListen()
                },
            )
        } catch (e: Throwable) {
            listening = false; status = "无法启动识别"
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
            status = "未配置 API Key"
            speak("请先在模型配置页填写 API Key"); return
        }
        // 视频通话属于 CHAT 调用：接入「功能模型配置 → 对话」独立模型绑定，让开关生效。
        val cfg = QuroFunctionModelConfigRepository(applicationContext).resolveConfig(QuroFunctionType.CHAT, baseCfg)

        // 视觉识别层：把当前实时画面作为图片注入本轮对话（当前模型支持视觉时）；
        // 否则退化为视频识别层（用「视频识别」配置描述画面并注入文字）。
        injectFrameContext(cfg.model)

        status = "思考中…"
        launch {
            try {
                store.add(QuroMessage(role = "user", content = text))
                val reply = assistant.ask(applicationContext, cfg, buildVideoSystemPrompt())
                mainHandler.post { speaking = true; status = "回复中：${reply.take(40)}" }
                speak(reply) {
                    speaking = false
                    if (callActive) { status = qstr(R.string.qk_01757); startListening() }
                }
            } catch (e: Throwable) {
                mainHandler.post { status = "出错了：${e.message}" }
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
            // 视频识别层：调「视频识别」绑定模型描述画面，注入文字上下文。
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

    /** 视频识别层：把单帧发给「视频识别」绑定的模型，返回一句话描述（无密钥返回 null）。 */
    private fun captionFrame(file: File): String? {
        return try {
            val global = QuroModelConfigRepository(applicationContext).load()
            val vcfg = QuroFunctionModelConfigRepository(applicationContext).resolveConfig(QuroFunctionType.VIDEO_RECOGNITION, global)
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
            status = "缺少相机权限，仍以语音对话"; return
        }
        try {
            val mgr = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val camId = mgr.cameraIdList.firstOrNull { id ->
                mgr.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
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

    private fun addCallView() {
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
        val w = (dm.widthPixels * 0.62).toInt().coerceAtMost(360)
        val h = (dm.heightPixels * 0.7).toInt().coerceAtMost(540)
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
            x = (dm.widthPixels - w - 24).coerceAtLeast(0)
            y = 140
        }
        windowManager.addView(view, params)
    }

    private fun removeCallView() {
        try { composeView?.let { windowManager.removeView(it) } } catch (_: Throwable) {}
        composeView = null
        textureView = null
        try { composeLifecycleOwner?.destroy() } catch (_: Throwable) {}
        composeLifecycleOwner = null
    }

    @Composable
    private fun VideoCallScreen() {
        Column(
            modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.85f)),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = status,
                color = Color.White,
                fontSize = 12.sp,
                modifier = Modifier.padding(8.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                Button(onClick = {
                    if (listening) QuroSttHolder.stopListening() else startListening()
                }) {
                    Text(if (listening) stringResource(R.string.qk_03503) else stringResource(R.string.qk_03723))
                }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { stopCall() }) { Text(stringResource(R.string.qk_03594)) }
            }
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
        private const val NOTIF_ID = 9003
        private const val CHANNEL_ID = "zorv_video_call"
        private const val TAG = "QuroVideoCall"
    }
}