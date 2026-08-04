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
 * 端侧（手机本地）语音转文本门面，基于 Sherpa-NCNN 的**流式 transducer**（离线、不连云）。
 *
 * 真正的引擎跑在独立进程 `:asr`（`QuroAsrService`），本对象只是主进程里的 IPC 门面：
 *  - 加载/识别通过 Messenger 发往 :asr 进程，结果异步回传；
 *  - 若 :asr 进程因 Sherpa 原生 SIGSEGV 崩溃，IBinder.DeathRecipient 触发，主进程
 *    优雅降级（标记不可用、返回空），**App 不会闪退**。
 *
 * 模型由 QuroOnDeviceModelManager 在运行期下载解压到应用私有目录后自动部署。
 *
 * 所有失败路径都会写入 [lastError]（人类可读），UI 直接展示，不再只是「识别失败」四个字。
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

    /** 最近一次失败的人类可读原因；成功时清空。供设置页 / 自检页展示。 */
    @Volatile
    var lastError: String = ""
        private set

    private fun fail(reason: String): Boolean {
        lastError = reason
        Log.e(TAG, reason)
        return false
    }

    /** 按机型 CPU 核数推荐的解码线程数（手机端 2~3 最优，过高会因大小核调度反而变慢）。 */
    private fun recommendedThreads(): Int =
        Runtime.getRuntime().availableProcessors().coerceIn(1, 8).let { cores ->
            when {
                cores >= 8 -> 3
                cores >= 4 -> 2
                else -> 1
            }
        }

    private val deathRecipient = object : IBinder.DeathRecipient {
        override fun binderDied() {
            Log.e(TAG, "⚠️ 端侧 ASR 进程(:asr) 崩溃（疑似 Sherpa 原生 SIGSEGV），主进程不受影响")
            bound = false
            messenger = null
            ready = false
            lastError = "端侧识别引擎进程崩溃（模型与引擎不兼容的可能性最大），已自动降级。建议删除模型后重新下载推荐模型。"
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
        val appCtx = ctx.applicationContext

        // ── 设备前置校验：非 arm64 / F-Droid 无 .so 时直接给结论，别让用户白下 22MB ──
        if (!AsrDeviceCompat.isSupported(appCtx)) {
            return fail(AsrDeviceCompat.unsupportedReason(appCtx).ifEmpty { "本机不支持端侧离线识别。" })
        }

        // ── 主进程快速预检：拒绝把假模型/损坏模型发给 :asr 进程（否则会 60s 超时卡死） ──
        val dir = QuroOnDeviceModelPrefs.getDeployedDir(appCtx)
        if (dir.isNullOrEmpty()) {
            return fail("还没有下载语音识别模型。请到「语音服务 → 语音识别」下载推荐模型（约 22MB）。")
        }
        // 大小兜底：无论文件名/已部署类型是什么，目录内最大文件 < 1MB 直接判坏、0 等待（保留部署记录，待用户重新下载）
        if (deployedDirMaxFileBytes(dir) < MIN_VALID_MODEL_BYTES) {
            return fail("模型文件已损坏（目录内最大文件不足 1MB，多半是下载中断或返回了错误页）。请删除后重新下载。")
        }
        val layout = detectAsrLayout(File(dir))
        when (layout) {
            AsrModelLayout.TRANSDUCER -> { /* 布局合法，继续加载 */ }
            AsrModelLayout.ONNX_LEGACY ->
                // 旧 ONNX 部署目录与 NCNN 引擎不兼容：拒绝加载（保留部署记录，待用户重新下载）
                return fail("已部署的是 ONNX 格式模型，与本机 NCNN 引擎不兼容。请删除后重新下载推荐的流式模型。")
            AsrModelLayout.SENSE_VOICE_LEGACY ->
                return fail("已部署的是旧版 SenseVoice 模型，当前引擎不支持（引擎内无对应实现），这也是此前识别一直没反应的原因。请删除后重新下载推荐的流式模型（约 22MB）。")
            AsrModelLayout.NONE ->
                return fail("模型目录里没有可用的模型文件。请删除后重新下载。")
        }

        if (!bindAndWait(ctx)) {
            return fail("端侧识别服务启动失败（:asr 进程未能绑定）。请重启 App 后重试。")
        }
        val result = CompletableDeferred<LoadReply>()
        val msg = Message.obtain(null, QuroAsrService.MSG_LOAD)
        msg.data.putString(QuroAsrService.KEY_DIR, dir)
        msg.data.putString(QuroAsrService.KEY_TYPE, AsrModelType.STREAMING_TRANSDUCER.name)
        msg.data.putInt(QuroAsrService.KEY_THREADS, recommendedThreads())
        msg.replyTo = Messenger(LoadReplyHandler(result))
        return try {
            messenger?.send(msg)
            val reply = withTimeoutOrNull(LOAD_TIMEOUT_MS) { result.await() }
            when {
                reply == null -> {
                    // 已真正尝试加载却超时（含原生挂死）：不清除部署记录（避免把合法部署误判丢失），
                    // 本次会话标记不可用，用户可重试或重新下载
                    ready = false
                    fail("模型加载超时（超过 ${LOAD_TIMEOUT_MS / 1000} 秒）。模型可能与引擎不匹配，建议删除后重新下载推荐模型。")
                }
                !reply.ok -> {
                    ready = false
                    fail(reply.err.ifEmpty { "模型加载失败（引擎未给出原因）。" })
                }
                else -> {
                    ready = bound
                    lastError = ""
                    true
                }
            }
        } catch (e: Throwable) {
            fail("与识别引擎通信失败：${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** :asr 进程回传的加载结果。 */
    private data class LoadReply(val ok: Boolean, val err: String)

    private class LoadReplyHandler(private val deferred: CompletableDeferred<LoadReply>) : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            if (msg.what == QuroAsrService.MSG_LOAD_RESULT) {
                deferred.complete(
                    LoadReply(
                        ok = msg.data.getBoolean(QuroAsrService.KEY_OK, false),
                        err = msg.data.getString(QuroAsrService.KEY_ERR).orEmpty(),
                    )
                )
            }
        }
    }

    /**
     * 识别一段 16-bit PCM（little-endian, 16kHz, 单声道）音频。须在后台协程调用。
     * @return 识别文本（空串表示未识别到或引擎不可用；不会因原生崩溃而闪退）
     */
    suspend fun recognize(pcm: ByteArray): String {
        if (!bound || messenger == null) {
            fail("端侧识别引擎未就绪。")
            return ""
        }
        if (pcm.size < 2) {
            fail("没有采集到音频数据（可能是麦克风被其他应用占用）。")
            return ""
        }
        val result = CompletableDeferred<RecognizeReply>()
        val msg = Message.obtain(null, QuroAsrService.MSG_RECOGNIZE)
        msg.data.putByteArray(QuroAsrService.KEY_PCM, pcm)
        msg.replyTo = Messenger(RecReplyHandler(result))
        return try {
            messenger?.send(msg)
            val reply = withTimeoutOrNull(RECOGNIZE_TIMEOUT_MS) { result.await() }
            when {
                reply == null -> {
                    fail("识别超时（超过 ${RECOGNIZE_TIMEOUT_MS / 1000} 秒）。录音过长或机型性能不足，建议缩短单次说话时长。")
                    ""
                }
                reply.text.isNotEmpty() -> {
                    lastError = ""
                    reply.text
                }
                else -> {
                    fail(reply.err.ifEmpty { "没有识别到有效语音。" })
                    ""
                }
            }
        } catch (e: Throwable) {
            fail("与识别引擎通信失败：${e.message ?: e.javaClass.simpleName}")
            ""
        }
    }

    /** :asr 进程回传的识别结果。 */
    private data class RecognizeReply(val text: String, val err: String)

    private class RecReplyHandler(private val deferred: CompletableDeferred<RecognizeReply>) : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            if (msg.what == QuroAsrService.MSG_RECOGNIZE_RESULT) {
                deferred.complete(
                    RecognizeReply(
                        text = msg.data.getString(QuroAsrService.KEY_TEXT).orEmpty(),
                        err = msg.data.getString(QuroAsrService.KEY_ERR).orEmpty(),
                    )
                )
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
