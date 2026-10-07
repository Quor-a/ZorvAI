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
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
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
import com.ai.assistance.quro.core.model.QuroFrameVisionOption
import com.ai.assistance.quro.core.model.QuroFrameVisionRouter
import com.ai.assistance.quro.core.model.QuroFrameVisionSource
import com.ai.assistance.quro.core.model.QuroFunctionModelConfigRepository
import com.ai.assistance.quro.core.model.QuroFunctionType
import com.ai.assistance.quro.core.model.QuroModelConfig
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
 * **全屏**通话界面：顶部状态条 + **铺满整屏**的实时摄像头预览 + 字幕 + 底部三键控制
 * （麦克风开关 / 前后摄切换 / 挂断）。窗口已是 [WindowManager.LayoutParams.MATCH_PARENT] 全屏，
 * 因此界面不再有圆角与拖拽把手，改用 [WindowInsets.safeDrawing] 让内容避开状态栏/导航栏/刘海。
 *
 * ## 画面理解降级链（本轮重构）
 * 优先级由纯函数 [QuroFrameVisionRouter.plan] 固化（可单测钉死），本服务只负责执行：
 *  1. **主模型自带视觉** → 直接把实时帧喂给它「亲眼」看，零额外远程调用；
 *  2. **主模型无视觉 + 「功能模型配置 → 视频通话」指定了独立模型** → 直接用**视频通话模型**做视觉识别；
 *  3. 上面都没有 → 用**「功能模型配置 → 图像识别」**作为**保底**视觉模型；
 *  4. 全都没有 → 本轮不注入画面上下文，退化为纯语音对话。
 *
 * 🔴 「视频识别」（[QuroFunctionType.VIDEO_RECOGNITION]）是**另一个工具**（`video_understanding`，
 * 面向用户主动发起的整段视频文件分析），与本链路的**实时单帧**理解不是一回事，这里刻意不用它。
 *
 * 模型来源：对话走「功能模型配置 → 视频通话」的绑定（[QuroFunctionType.VIDEO_CALL]），
 * 未指定独立模型时跟随主模型。
 *
 * 摄像头走框架 Camera2（无需新增依赖）；对话/STT/TTS 复用语音球的成熟链路。
 *
 * ## 历史修复
 * 原实现的 [textureView] 从未被赋值 —— [VideoCallScreen] 里只有状态文字与两个按钮，
 * 没有任何视图承载 [surfaceTextureListener]，于是：① 用户看不到摄像头画面（黑框）；
 * ② [captureFrame] 因 `textureView == null` 恒返回 null，画面理解层等于完全失效。
 * 现用 [AndroidView] 把 [TextureView] 真正挂进通话窗，预览与截帧链路同时打通。
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
            val types = resolveForegroundTypes()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && types != 0) {
                // Android 14+：前台服务类型必须与「已授予的运行时权限」一一对应，
                // 多声明一个没授权的类型就直接抛 SecurityException。
                startForeground(NOTIF_ID, buildNotification(), types)
            } else {
                // 🔴 types==0（相机与麦克风都没授权）时走不带类型的重载。
                // 旧实现在这里反向兜底成 FOREGROUND_SERVICE_TYPE_MICROPHONE —— 而麦克风权限
                // 恰恰是没有的，于是 Android 14+ 必抛 SecurityException，被下面的 catch 吞掉后
                // stopSelf()，表现为「视频通话打开马上关闭」且全程无任何提示。
                startForeground(NOTIF_ID, buildNotification())
            }
        } catch (e: Throwable) {
            Log.e(TAG, "VideoCall onCreate failed: startForeground", e)
            toast(getString(R.string.qk_03910))
            stopSelf(); return
        }
    }

    /**
     * 按**实际已授予的运行时权限**拼出前台服务类型集合。
     *
     * 🔴 绝不能在没有对应权限时声明该类型（Android 14+ 会抛 SecurityException）。
     * 返回 0 表示相机与麦克风都未授权，调用方须走不带类型的 [Service.startForeground] 重载。
     */
    private fun resolveForegroundTypes(): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return 0
        var t = 0
        if (checkSelfPermission(android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
            t = t or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
            t = t or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        return t
    }

    private fun toast(msg: String) {
        runCatching {
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
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
            // 🔴 旧实现在这里只改了个 status 字符串 —— 但悬浮窗根本没挂上，界面不存在，
            // 没有任何地方能显示这个 status，用户只看到「闪一下就没了」，完全不知道原因。
            // 现在必须显式提示，并补齐权限缺失这一最常见原因。
            callActive = false
            val reason = when {
                !Settings.canDrawOverlays(this) -> getString(R.string.qk_03911)
                else -> getString(R.string.qk_03912)
            }
            status = reason
            toast(reason)
            Log.w(TAG, "startCall aborted: addCallView failed ($reason)")
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
                onPartial = { t -> if (t.isNotBlank()) status = qstr(R.string.qk_03879, t) },
                onFinal = { text ->
                    if (!callActive) return@startListening
                    listening = false
                    if (text.isNotBlank()) {
                        subtitle = qstr(R.string.qk_03900, text)
                        status = qstr(R.string.qk_00093)
                        process(text)
                    } else {
                        status = qstr(R.string.qk_03880)
                        scheduleListen()
                    }
                },
                onError = { _, msg ->
                    if (!callActive) return@startListening
                    listening = false; status = qstr(R.string.qk_03882, msg); scheduleListen()
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

        // 画面理解：三级降级链由 QuroFrameVisionRouter 判定（主模型视觉 → 视频通话模型 → 图像识别模型）。
        injectFrameContext(cfg)

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

    // ──────────────────── 画面理解层：主模型 → 视频通话模型 → 图像识别模型 ────────────────────

    /**
     * 把当前实时画面作为上下文注入本轮对话。
     *
     * 优先级完全交给纯函数 [QuroFrameVisionRouter.plan] 判定，本函数只负责执行：
     *  - [QuroFrameVisionSource.MAIN_MODEL]：直接把帧喂给当前多模态模型「亲眼」看（零额外远程调用）。
     *  - [QuroFrameVisionSource.VIDEO_CALL_MODEL]：主模型没视觉，但「功能模型配置 → 视频通话」
     *    配了独立模型 → **直接用视频通话模型做视觉识别**。
     *  - [QuroFrameVisionSource.IMAGE_RECOGNITION_MODEL]：以上都没有 → 用「功能模型配置 → 图像识别」**保底**。
     *  - [QuroFrameVisionSource.NONE]：全都没配 → 不注入任何画面上下文，本轮退化为纯语音对话。
     *
     * @param mainCfg 本轮实际下发的对话模型配置（= 视频通话绑定解析结果）。
     */
    private fun injectFrameContext(mainCfg: QuroModelConfig) {
        val repo = QuroFunctionModelConfigRepository(applicationContext)
        val global = QuroModelConfigRepository(applicationContext).load()

        val callCfg = repo.resolveConfig(QuroFunctionType.VIDEO_CALL, global)
        val imageCfg = repo.resolveConfig(QuroFunctionType.IMAGE_RECOGNITION, global)

        val callBinding = repo.getBinding(QuroFunctionType.VIDEO_CALL)
        val imageBinding = repo.getBinding(QuroFunctionType.IMAGE_RECOGNITION)

        val source = QuroFrameVisionRouter.plan(
            mainModelHasVision = QuroFrameVisionRouter.isLikelyVisionModel(mainCfg.model),
            mainHasApiKey = mainCfg.apiKey.isNotBlank(),
            videoCall = QuroFrameVisionOption(
                dedicatedModel = !callBinding.useGlobal && callBinding.model.isNotBlank(),
                hasApiKey = callCfg.apiKey.isNotBlank(),
            ),
            imageRecognition = QuroFrameVisionOption(
                dedicatedModel = !imageBinding.useGlobal && imageBinding.model.isNotBlank(),
                hasApiKey = imageCfg.apiKey.isNotBlank(),
            ),
        )

        // 先决策再截帧：判到 NONE 时连一次 Bitmap 都不用抓，省掉 640×480 的无谓开销。
        if (source == QuroFrameVisionSource.NONE) return
        val frame = captureFrame() ?: return

        when (source) {
            QuroFrameVisionSource.MAIN_MODEL -> {
                val att = QuroAttachmentKit.fromFile(this, frame, "image/jpeg")
                store.add(
                    QuroMessage(
                        role = "user",
                        content = "[视频通话实时画面·请直接查看并描述]",
                        attachments = listOf(att),
                        hidden = true,
                    )
                )
            }
            // 🔴 本级失败（网络/模型名写错/超时）时继续往下退，绝不因为一层挂了就丢掉整轮画面理解。
            QuroFrameVisionSource.VIDEO_CALL_MODEL -> {
                val caption = captionFrame(frame, callCfg)
                    ?: captionFrame(frame, imageCfg)
                if (caption != null) store.add(QuroMessage(role = "user", content = "[视频画面识别] $caption", hidden = true))
            }
            QuroFrameVisionSource.IMAGE_RECOGNITION_MODEL -> {
                val caption = captionFrame(frame, imageCfg)
                if (caption != null) store.add(QuroMessage(role = "user", content = "[视频画面识别] $caption", hidden = true))
            }
            QuroFrameVisionSource.NONE -> Unit
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
     * 用指定配置把单帧发给一个**视觉模型**，返回一句话画面描述。
     *
     * 与旧实现的差异：入参从「内部自己解析配置」改为「调用方显式传入已解析好的 [cfg]」——
     * 降级链的选路集中在 [QuroFrameVisionRouter]，这里退化成纯粹的「一次远程调用」，
     * 因此主模型 / 视频通话模型 / 图像识别模型三条路径能共用同一份实现，不会各自漂移。
     *
     * 无密钥或调用失败返回 null，由调用方决定是否退到下一级。
     */
    private fun captionFrame(file: File, cfg: QuroModelConfig): String? {
        return try {
            if (cfg.apiKey.isBlank()) return null
            val model = cfg.model.ifBlank { "gpt-4o" }
            val baseUrl = cfg.baseUrl.ifBlank { "https://api.openai.com/v1" }
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
                .addHeader("Authorization", "Bearer ${cfg.apiKey}")
                .post(json.toString().toRequestBody("application/json".toMediaType()))
                .build()
            val resp = httpClient.newCall(req).execute()
            if (resp.isSuccessful) {
                val j = JSONObject(resp.body?.string() ?: "")
                j.getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content")
                    .takeIf { it.isNotBlank() }
            } else {
                Log.w(TAG, "captionFrame HTTP ${resp.code} model=$model")
                null
            }
        } catch (e: Throwable) {
            Log.e(TAG, "captionFrame failed", e); null
        }
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

    // ──────────────────── 全屏通话窗 ────────────────────

    /**
     * 挂载**全屏**通话界面；返回是否成功（无悬浮窗权限时返回 false）。
     *
     * 旧实现是 78%×62%（最大 420×640）的小悬浮窗，且顶部状态条是拖拽把手 —— 画面被压得很小，
     * 竖屏手机上预览区只剩一条。现在改为 [WindowManager.LayoutParams.MATCH_PARENT] 真全屏：
     *  - 尺寸铺满整屏，`FLAG_LAYOUT_IN_SCREEN` 让窗口延伸到状态栏/导航栏之下，
     *    再由 Compose 侧 [WindowInsets.safeDrawing] 把内容压回安全区（刘海/挖孔/手势条都不遮挡）；
     *  - 保留 [WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE]：全屏不等于抢输入焦点，
     *    去掉它会让通话窗吞掉键盘与返回键，用户无法在通话中切到别的 App；
     *  - 全屏后没有「拖动」语义，[viewParams] 也不再参与拖拽，仅保留给 [removeCallView]。
     */
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
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 0
                y = 0
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

    @Composable
    private fun VideoCallScreen() {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0B0D10))
                // 全屏窗口会盖住状态栏/导航栏/刘海，用 safeDrawing 把内容压回可视区。
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            // ── ① 顶部状态条（全屏后不再是拖拽把手，只保留状态与关闭）──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 8.dp),
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
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                    Text(status, color = Color(0xFFB9BDC6), fontSize = 12.sp, maxLines = 1)
                }
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color(0x33FFFFFF))
                        .clickable { stopCall() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Close, stringResource(R.string.qk_00011), Modifier.size(18.dp), tint = Color.White)
                }
            }
            // ── ② 实时预览：全屏铺满，不再有外边距与圆角 ──
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
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
                        fontSize = 13.sp,
                        maxLines = 4,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .background(Color(0xB3000000))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            // ── ③ 底部控制：麦克风 / 切换摄像头 / 挂断（全屏下加大间距与触控目标）──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 20.dp),
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

    /** 圆形通话控制键 + 底部小字标签（触控目标 56dp，满足无障碍下限）。 */
    @Composable
    private fun CallCircle(icon: ImageVector, label: String, background: Color, onClick: () -> Unit) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(background)
                    .clickable(onClick = onClick),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, label, Modifier.size(26.dp), tint = Color.White)
            }
            Spacer(Modifier.height(6.dp))
            Text(label, color = Color(0xFFB9BDC6), fontSize = 11.sp, maxLines = 1)
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
