package com.ai.assistance.quro.core.tools

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.util.Log
import com.k2fsa.sherpa.ncnn.SherpaNcnn
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
 * ## 本次改造（用户问题 4）
 * 旧实现用 `OfflineRecognizer`（SenseVoice 非流式）。随包 .so **不含该类的任何 JNI 符号**，
 * 调用必抛 `UnsatisfiedLinkError`，端侧识别从未跑通。现改用 .so 真实导出的流式
 * [SherpaNcnn] API，配 22MB 起的流式 zipformer 模型（原 SenseVoice 下载 215MB）。
 *
 * 另外两点工程修正：
 *  1. 引擎调用从主线程搬到专用 [HandlerThread]。原实现把加载/识别跑在 :asr 进程主线程，
 *     一次几秒的原生解码会阻塞 Binder 回复线程，主进程只能等到 60s 超时。
 *  2. 失败原因不再吞掉，通过 [KEY_ERR] 原样回传主进程 → UI 直接展示。
 *
 * 通过 Messenger 与主进程（QuroOnDeviceAsr 门面）通信：
 *  - MSG_LOAD(dir,type,threads) → 加载模型，回复 MSG_LOAD_RESULT(ok, err)
 *  - MSG_RECOGNIZE(pcm)         → 识别 16-bit PCM，回复 MSG_RECOGNIZE_RESULT(text, err)
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

        /** 失败原因（人类可读，直接展示给用户）。成功时为空串。 */
        const val KEY_ERR = "err"

        /** 解码线程数，主进程按机型下发；缺省 2。 */
        const val KEY_THREADS = "threads"

        private const val TAG = "QuroAsrSvc"

        /** 录音采样率，与 QuroSttRecorder.REC_SAMPLE_RATE 一致，改动需同步。 */
        private const val SAMPLE_RATE = 16000f

        /** 每次喂给引擎的样本数（100ms @16kHz）。流式引擎按块吃，块太大反而增加首字延迟。 */
        private const val CHUNK_SAMPLES = 1600

        /** 单次解码步数上限，防原生层异常导致 isReady 永真而死循环。 */
        private const val MAX_DECODE_STEPS = 200_000
    }

    @Volatile private var recognizer: SherpaNcnn? = null

    /** 引擎专用线程：原生解码耗时，绝不能占用 Binder 回复所在的主线程。 */
    private lateinit var engineThread: HandlerThread
    private lateinit var engineHandler: Handler

    private val messenger by lazy { Messenger(IncomingHandler()) }

    override fun onCreate() {
        super.onCreate()
        engineThread = HandlerThread("quro-asr-engine").apply { start() }
        engineHandler = Handler(engineThread.looper)
    }

    private inner class IncomingHandler : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                MSG_LOAD -> {
                    val dir = msg.data.getString(KEY_DIR).orEmpty()
                    val typeName = msg.data.getString(KEY_TYPE)
                    val threads = msg.data.getInt(KEY_THREADS, 2)
                    val replyTo = msg.replyTo
                    engineHandler.post {
                        val result = loadModel(dir, typeName, threads)
                        val reply = Message.obtain(null, MSG_LOAD_RESULT)
                        reply.data.putBoolean(KEY_OK, result.second.isEmpty())
                        reply.data.putString(KEY_ERR, result.second)
                        try { replyTo?.send(reply) } catch (_: Throwable) {}
                    }
                }
                MSG_RECOGNIZE -> {
                    val pcm = msg.data.getByteArray(KEY_PCM) ?: byteArrayOf()
                    val replyTo = msg.replyTo
                    engineHandler.post {
                        val (text, err) = doRecognize(pcm)
                        val reply = Message.obtain(null, MSG_RECOGNIZE_RESULT)
                        reply.data.putString(KEY_TEXT, text)
                        reply.data.putString(KEY_ERR, err)
                        try { replyTo?.send(reply) } catch (_: Throwable) {}
                    }
                }
                else -> super.handleMessage(msg)
            }
        }
    }

    /**
     * 加载模型。
     *
     * @return first = 是否成功，second = 失败原因（成功为空串）
     */
    private fun loadModel(dir: String, typeName: String?, threads: Int): Pair<Boolean, String> {
        // 先释放旧实例，避免切换模型时两份 ncnn 权重同时常驻（手机内存敏感）
        releaseRecognizer()

        if (!SherpaNcnn.nativeLoaded) {
            val err = SherpaNcnn.nativeLoadError.ifEmpty { "端侧识别原生库未加载" }
            Log.e(TAG, "原生库不可用：$err")
            return false to err
        }
        if (dir.isEmpty()) return false to "未指定模型目录"

        val modelDir = File(dir)
        if (!modelDir.isDirectory) return false to "模型目录不存在：$dir"

        val declared = typeName?.let { runCatching { AsrModelType.valueOf(it) }.getOrNull() }
        if (declared == AsrModelType.SENSE_VOICE_LEGACY) {
            return false to "已部署的是旧版 SenseVoice 模型，当前引擎不支持（引擎内无对应实现）。请到「语音识别」设置里重新下载推荐的流式模型。"
        }

        when (detectAsrLayout(modelDir)) {
            AsrModelLayout.TRANSDUCER -> Unit
            AsrModelLayout.ONNX_LEGACY ->
                return false to "模型目录里是 ONNX 格式模型，与本机 NCNN 引擎不兼容，请重新下载 Sherpa-NCNN 流式模型。"
            AsrModelLayout.SENSE_VOICE_LEGACY ->
                return false to "模型目录是旧版 SenseVoice 布局，缺少 encoder/decoder/joiner 三件套，引擎无法加载。请重新下载推荐模型。"
            AsrModelLayout.NONE ->
                return false to "模型目录里没有可用的模型文件（需 encoder/decoder/joiner 的 .ncnn.param + .ncnn.bin 与 tokens.txt）。"
        }

        val files = findAsrFiles(modelDir, AsrModelType.STREAMING_TRANSDUCER)
            ?: return false to "模型三件套不完整：encoder / decoder / joiner 的 .param 与 .bin 必须成对存在，且需要 tokens.txt。"

        return try {
            Log.i(TAG, "加载端侧流式模型: dir=$dir int8=${files.int8} threads=$threads")
            val config = buildRecognizerConfig(files, numThreads = threads)
            // 从绝对路径加载：assetManager 传 null，config 内六个路径均为绝对路径
            val rec = SherpaNcnn(null, config)
            if (!rec.isValid) {
                rec.release()
                return false to "引擎初始化返回空句柄，模型文件可能损坏或与引擎版本不匹配，请删除后重新下载。"
            }
            recognizer = rec
            Log.i(TAG, "端侧模型加载成功 ✅ int8=${files.int8}")
            true to ""
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "JNI 符号缺失: ${e.message}", e)
            false to "原生库缺少所需接口（${e.message}），当前安装包的引擎与代码不匹配。"
        } catch (e: Throwable) {
            Log.e(TAG, "端侧模型加载失败: ${e.message}", e)
            false to "模型加载失败：${e.message ?: e.javaClass.simpleName}"
        }
    }

    /**
     * 流式识别一整段 16-bit PCM（16kHz 单声道 little-endian）。
     *
     * 流程严格遵循 Sherpa-NCNN 流式契约：
     * `acceptWaveform → while(isReady) decode → 命中端点则取文本并 reset(recreate=true)`，
     * 末尾 `inputFinished` 后再排空一次，确保尾字不丢。
     *
     * @return first = 识别文本，second = 失败原因（成功为空串）
     */
    private fun doRecognize(pcm: ByteArray): Pair<String, String> {
        val rec = recognizer ?: return "" to "端侧引擎未就绪，请先在设置里下载并部署模型。"
        if (pcm.size < 2) return "" to "没有采集到音频数据。"

        return try {
            // 每次识别前彻底重置，避免上一段的残留特征污染本次结果
            rec.reset(true)

            val shortCount = pcm.size / 2
            val shorts = ShortArray(shortCount)
            ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)

            val segments = ArrayList<String>(4)
            var steps = 0
            var offset = 0
            while (offset < shortCount) {
                val n = minOf(CHUNK_SAMPLES, shortCount - offset)
                val chunk = FloatArray(n) { shorts[offset + it] / 32768.0f }
                offset += n

                rec.acceptWaveform(chunk, SAMPLE_RATE)
                while (rec.isReady()) {
                    rec.decode()
                    if (++steps > MAX_DECODE_STEPS) {
                        Log.e(TAG, "解码步数超限，强制中断（疑似原生层异常）")
                        return "" to "识别超时：引擎解码异常中断，请重试或更换模型。"
                    }
                }
                // 端点命中 = 一句说完：落袋为安，然后开新句
                if (rec.isEndpoint()) {
                    rec.getText().trim().takeIf { it.isNotEmpty() }?.let { segments.add(it) }
                    rec.reset(true)
                }
            }

            // 冲刷尾部特征，取最后一句
            rec.inputFinished()
            while (rec.isReady()) {
                rec.decode()
                if (++steps > MAX_DECODE_STEPS) break
            }
            rec.getText().trim().takeIf { it.isNotEmpty() }?.let { segments.add(it) }

            // 复位以便下次识别（也顺带释放本次的 stream 内存）
            rec.reset(true)

            val text = normalizeSegments(segments)
            if (text.isEmpty()) {
                "" to "没有识别到有效语音（可能是没说话、离麦太远或环境太吵）。"
            } else {
                text to ""
            }
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "JNI 符号缺失: ${e.message}", e)
            "" to "原生库缺少所需接口（${e.message}）。"
        } catch (e: Throwable) {
            Log.e(TAG, "识别失败: ${e.message}", e)
            "" to "识别失败：${e.message ?: e.javaClass.simpleName}"
        }
    }

    /**
     * 拼接分句结果：段间默认用空格分隔（保护英文单词边界），
     * 但两侧都是中日韩文字时去掉空格，避免出现「今天 天气 不错」这种断裂感。
     */
    private fun normalizeSegments(segments: List<String>): String {
        if (segments.isEmpty()) return ""
        val joined = segments.joinToString(" ").trim()
        return joined.replace(CJK_SPACE_REGEX, "")
    }

    private fun releaseRecognizer() {
        val old = recognizer
        recognizer = null
        try { old?.release() } catch (_: Throwable) {}
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onDestroy() {
        // 引擎释放必须发生在引擎线程，避免与正在进行的原生解码并发
        if (this::engineHandler.isInitialized) {
            engineHandler.post { releaseRecognizer() }
            engineThread.quitSafely()
        } else {
            releaseRecognizer()
        }
        super.onDestroy()
    }
}

/** 匹配「中日韩字符 + 空白 + 中日韩字符」中间的空白。 */
private val CJK_SPACE_REGEX =
    Regex("(?<=[\\u3040-\\u30ff\\u3400-\\u4dbf\\u4e00-\\u9fff\\uf900-\\ufaff])\\s+(?=[\\u3040-\\u30ff\\u3400-\\u4dbf\\u4e00-\\u9fff\\uf900-\\ufaff])")
