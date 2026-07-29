package com.ai.assistance.quro.core.tools

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.util.Log
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 端侧（手机本地）语音转文本门面，基于 Sherpa-NCNN 的 OfflineRecognizer（SenseVoice，离线、不连云）。
 *
 * 真正的引擎跑在独立进程 `:asr`（`QuroAsrService`），本对象只是主进程里的 IPC 门面：
 *  - 加载/识别通过 Messenger 发往 :asr 进程，结果异步回传；
 *  - 若 :asr 进程因 Sherpa 原生 SIGSEGV 崩溃，IBinder.DeathRecipient 触发，主进程
 *    优雅降级（标记不可用、返回空），**App 不会闪退**。
 *
 * 模型由 QuroOnDeviceModelManager 在运行期下载解压到应用私有目录后自动部署。
 */
object QuroOnDeviceAsr {

    private const val TAG = "QuroOnDevice"
    private const val BIND_TIMEOUT_MS = 8000L
    private const val LOAD_TIMEOUT_MS = 60000L
    private const val RECOGNIZE_TIMEOUT_MS = 60000L

    @Volatile private var messenger: Messenger? = null
    @Volatile private var bound = false
    @Volatile private var ready = false
    private val bindStarted = AtomicBoolean(false)
    @Volatile private var bindDeferred: CompletableDeferred<Boolean>? = null

    private val deathRecipient = object : IBinder.DeathRecipient {
        override fun binderDied() {
            Log.e(TAG, "⚠️ 端侧 ASR 进程(:asr) 崩溃（疑似 Sherpa 原生 SIGSEGV），主进程不受影响")
            bound = false
            messenger = null
            ready = false
        }
    }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            messenger = Messenger(service)
            bound = true
            try { service?.linkToDeath(deathRecipient, 0) } catch (_: Throwable) {}
            bindDeferred?.complete(true)
            bindDeferred = null
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bound = false
            messenger = null
            ready = false
            bindDeferred?.complete(false)
            bindDeferred = null
        }
    }

    /** 已部署模型的目录（若无则返回 null）。 */
    fun getDeployedDir(ctx: Context): String? = QuroOnDeviceModelPrefs.getDeployedDir(ctx)

    /** 是否已部署可用模型（三件套齐全）。 */
    fun isModelAvailable(ctx: Context): Boolean =
        QuroOnDeviceModelManager.getDeployedModelFiles(ctx.applicationContext) != null

    fun isReady(): Boolean = ready && bound

    private suspend fun bindAndWait(ctx: Context): Boolean {
        if (bound && messenger != null) return true
        val d = CompletableDeferred<Boolean>()
        bindDeferred = d
        val intent = Intent(ctx.applicationContext, QuroAsrService::class.java)
        val started = try {
            ctx.applicationContext.bindService(intent, conn, Context.BIND_AUTO_CREATE)
        } catch (e: Throwable) {
            Log.e(TAG, "绑定端侧 ASR 服务失败: ${e.message}")
            false
        }
        if (!started) return false
        return withTimeoutOrNull(BIND_TIMEOUT_MS) { d.await() } ?: false
    }

    /**
     * 确保端侧引擎已加载（绑定服务 + 在 :asr 进程加载模型）。幂等，须在后台协程调用。
     * @return 是否就绪（false = 绑定失败或引擎进程崩溃/加载失败，主进程安全）
     */
    suspend fun ensureLoaded(ctx: Context): Boolean {
        if (ready && bound) return true
        // ── 主进程快速预检：拒绝把假模型/损坏模型发给 :asr 进程（否则会 60s 超时卡死） ──
        val dir = QuroOnDeviceModelPrefs.getDeployedDir(ctx.applicationContext)
        if (dir.isNullOrEmpty()) {
            Log.e(TAG, "未找到已部署目录")
            return false
        }
        // 大小兜底：无论文件名/已部署类型是什么，目录内最大文件 < 1MB 直接判坏、0 等待（保留部署记录，待用户重新下载）
        if (deployedDirMaxFileBytes(dir) < MIN_VALID_MODEL_BYTES) {
            Log.e(TAG, "已部署目录最大文件 < ${MIN_VALID_MODEL_BYTES} 字节，判定坏模型，拒绝加载（保留部署记录）: $dir")
            return false
        }
        val layout = detectAsrLayout(File(dir))
        when (layout) {
            AsrModelLayout.ONNX_LEGACY -> {
                // 旧 ONNX 部署目录与 NCNN 引擎不兼容：识别为不兼容并拒绝加载（保留部署记录，待用户重新下载 NCNN 模型）
                Log.e(TAG, "已部署目录为旧 ONNX 模型，与 NCNN 引擎不兼容，拒绝加载（保留部署记录）: $dir")
                return false
            }
            AsrModelLayout.NONE -> {
                Log.e(TAG, "已部署目录无模型文件，拒绝加载: $dir")
                return false
            }
            AsrModelLayout.NCNN -> { /* 布局合法，继续加载 */ }
        }
        // 类型优先级：部署记录 > NCNN 布局兜底（SENSE_VOICE）
        val typeName = QuroOnDeviceModelPrefs.getDeployedType(ctx.applicationContext)
            ?: if (layout == AsrModelLayout.NCNN) AsrModelType.SENSE_VOICE.name else null
        if (typeName == null) {
            Log.e(TAG, "通用布局模型缺少类型声明，请重新添加并选择模型类型")
            return false
        }
        if (!bindAndWait(ctx)) {
            Log.e(TAG, "端侧 ASR 服务绑定失败")
            return false
        }
        val result = CompletableDeferred<Boolean>()
        val msg = Message.obtain(null, QuroAsrService.MSG_LOAD)
        msg.data.putString(QuroAsrService.KEY_DIR, dir)
        msg.data.putString(QuroAsrService.KEY_TYPE, typeName)
        msg.replyTo = Messenger(LoadReplyHandler(result))
        return try {
            messenger?.send(msg)
            val ok = withTimeoutOrNull(LOAD_TIMEOUT_MS) { result.await() } ?: false
            if (!ok) {
                // 已真正尝试加载却失败/超时（含 60s 原生挂死）：不清除部署记录（避免把合法部署误判丢失），
                // 本次会话标记不可用，用户可重试或重新下载
                Log.e(TAG, "端侧模型加载失败/超时（保留部署记录，可重试或重新下载）: $dir")
            }
            ready = ok && bound
            ok
        } catch (e: Throwable) {
            Log.e(TAG, "加载端侧模型 IPC 失败: ${e.message}")
            false
        }
    }

    private class LoadReplyHandler(private val deferred: CompletableDeferred<Boolean>) : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            if (msg.what == QuroAsrService.MSG_LOAD_RESULT) {
                deferred.complete(msg.data.getBoolean(QuroAsrService.KEY_OK, false))
            }
        }
    }

    /**
     * 识别一段 16-bit PCM（little-endian, 16kHz, 单声道）音频。须在后台协程调用。
     * @return 识别文本（空串表示未识别到或引擎不可用；不会因原生崩溃而闪退）
     */
    suspend fun recognize(pcm: ByteArray): String {
        if (!bound || messenger == null) return ""
        if (pcm.size < 2) return ""
        val result = CompletableDeferred<String>()
        val msg = Message.obtain(null, QuroAsrService.MSG_RECOGNIZE)
        msg.data.putByteArray(QuroAsrService.KEY_PCM, pcm)
        msg.replyTo = Messenger(RecReplyHandler(result))
        return try {
            messenger?.send(msg)
            withTimeoutOrNull(RECOGNIZE_TIMEOUT_MS) { result.await() } ?: ""
        } catch (e: Throwable) {
            Log.e(TAG, "端侧识别 IPC 失败: ${e.message}")
            ""
        }
    }

    private class RecReplyHandler(private val deferred: CompletableDeferred<String>) : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            if (msg.what == QuroAsrService.MSG_RECOGNIZE_RESULT) {
                deferred.complete(msg.data.getString(QuroAsrService.KEY_TEXT) ?: "")
            }
        }
    }

    /** 解除与 :asr 进程的绑定（释放引擎）。 */
    fun release(ctx: Context) {
        try {
            if (bound) ctx.applicationContext.unbindService(conn)
        } catch (_: Throwable) {}
        bound = false
        messenger = null
        ready = false
    }
}
