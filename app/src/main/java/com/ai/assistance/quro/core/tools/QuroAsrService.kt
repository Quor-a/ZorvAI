package com.ai.assistance.quro.core.tools

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.util.Log
import com.k2fsa.sherpa.ncnn.OfflineRecognizer
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 端侧 ASR 引擎服务，运行在独立进程 `:asr`（在 AndroidManifest 声明）。
 *
 * 关键目的：Sherpa-NCNN 是 C++ 原生库（基于 ncnn），加载/识别时若遇模型不兼容等会触发
 * **原生 SIGSEGV**，而 Java 的 try/catch 与 UncaughtExceptionHandler 都拦不住，
 * 在主进程里会直接整 App 闪退。把引擎放进独立进程后，原生崩溃只会杀死 :asr 进程，
 * 主进程通过 IBinder.DeathRecipient 感知到「引擎进程崩溃」并优雅降级，App 不再闪退。
 *
 * 通过 Messenger 与主进程（QuroOnDeviceAsr 门面）通信：
 *  - MSG_LOAD(dir)        → 加载指定目录的 Whisper 模型，回复 MSG_LOAD_RESULT(ok)
 *  - MSG_RECOGNIZE(pcm)   → 识别 16-bit PCM，回复 MSG_RECOGNIZE_RESULT(text)
 */
class QuroAsrService : Service() {

    companion object {
        const val MSG_LOAD = 1
        const val MSG_LOAD_RESULT = 2
        const val MSG_RECOGNIZE = 3
        const val MSG_RECOGNIZE_RESULT = 4
        const val KEY_DIR = "dir"
        const val KEY_TYPE = "type"
        const val KEY_PCM = "pcm"
        const val KEY_TEXT = "text"
        const val KEY_OK = "ok"
        private const val TAG = "QuroAsrSvc"
    }

    @Volatile private var recognizer: OfflineRecognizer? = null
    private val messenger = Messenger(IncomingHandler())

    private inner class IncomingHandler : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                MSG_LOAD -> {
                    val dir = msg.data.getString(KEY_DIR) ?: ""
                    // 类型优先级：消息传入 > 部署记录 > 按 NCNN 布局兜底（否则 UNKNOWN）
                    val typeName = msg.data.getString(KEY_TYPE)
                        ?: QuroOnDeviceModelPrefs.getDeployedType(applicationContext)
                        ?: run {
                            val layout = detectAsrLayout(File(dir))
                            if (layout == AsrModelLayout.NCNN) AsrModelType.SENSE_VOICE.name else AsrModelType.UNKNOWN.name
                        }
                    val ok = loadModel(dir, typeName)
                    val reply = Message.obtain(null, MSG_LOAD_RESULT)
                    reply.data.putBoolean(KEY_OK, ok)
                    try { msg.replyTo?.send(reply) } catch (_: Throwable) {}
                }
                MSG_RECOGNIZE -> {
                    val pcm = msg.data.getByteArray(KEY_PCM) ?: byteArrayOf()
                    val text = doRecognize(pcm)
                    val reply = Message.obtain(null, MSG_RECOGNIZE_RESULT)
                    reply.data.putString(KEY_TEXT, text)
                    try { msg.replyTo?.send(reply) } catch (_: Throwable) {}
                }
                else -> super.handleMessage(msg)
            }
        }
    }

    private fun loadModel(dir: String, typeName: String): Boolean {
        val type = runCatching { AsrModelType.valueOf(typeName) }.getOrDefault(AsrModelType.UNKNOWN)
        // 按已知类型定位文件；类型未知或该类型未定位到文件时，兜底 SENSE_VOICE（NCNN）布局
        val located = (if (type != AsrModelType.UNKNOWN) findAsrFiles(File(dir), type) else null)
            ?: findAsrFiles(File(dir), AsrModelType.SENSE_VOICE)
        val files = located ?: run {
            Log.e(TAG, "模型目录无可用文件（类型=$typeName）: $dir")
            return false
        }
        return try {
            Log.i(TAG, "加载端侧模型: type=${files.type} modelDir=${files.modelDir} tokens=${files.tokensPath}")
            val config = buildOfflineConfig(files)
            // 从绝对路径加载 NCNN 模型：assetManager 传 null，所有路径均使用绝对路径
            recognizer = OfflineRecognizer(null, config)
            Log.i(TAG, "端侧模型加载成功 ✅")
            true
        } catch (e: Throwable) {
            Log.e(TAG, "端侧模型加载失败: ${e.message}", e)
            false
        }
    }

    private fun doRecognize(pcm: ByteArray): String {
        val rec = recognizer ?: return ""
        if (pcm.size < 2) return ""
        return try {
            val stream = rec.createStream()
            val shortCount = pcm.size / 2
            val shorts = ShortArray(shortCount)
            val shortBuf = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            shortBuf.get(shorts)
            val floats = FloatArray(shortCount) { shorts[it] / 32768.0f }
            stream.acceptWaveform(floats, 16000)
            rec.decode(stream)
            val text = rec.getResult(stream).text.trim()
            stream.release()
            text
        } catch (e: Throwable) {
            Log.e(TAG, "识别失败: ${e.message}", e)
            ""
        }
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onDestroy() {
        try { recognizer?.release() } catch (_: Throwable) {}
        recognizer = null
        super.onDestroy()
    }
}
