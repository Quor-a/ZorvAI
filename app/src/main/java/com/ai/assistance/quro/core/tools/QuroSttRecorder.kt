package com.ai.assistance.quro.core.tools

import android.Manifest
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.ai.assistance.quro.core.tools.QuroOnDeviceAsr
import com.ai.assistance.quro.core.model.QuroModelConfigRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

/**
 * STT 统一「录制 → 识别」入口（v273）。
 *
 * 按 [QuroSttPrefs] 已配置的引擎分流，使「对话框语音按钮」与「语音球」共用同一套 STT 选择：
 *  - [QuroSttPrefs.SOURCE_LOCAL]    → 原生 SpeechRecognizer（委托 [QuroSttHolder.startListening]）
 *  - [QuroSttPrefs.SOURCE_MODEL]    → AudioRecord 录音(VAD 静音断句) → 写 WAV → [QuroSttHolder.transcribe] 云端转写
 *  - [QuroSttPrefs.SOURCE_ONDEVICE] → AudioRecord 录音(VAD 静音断句) → [QuroOnDeviceAsr] 离线识别
 *
 * 修复此前「对话框语音按钮」只能走原生识别、无法使用已配置云端 / 端侧 STT 的问题。
 * 录音 + VAD + WAV 逻辑移植自语音球（已验证），独立成件以避免污染调用方。
 */
object QuroSttRecorder {
    private const val TAG = "QuroSttRecorder"
    private const val REC_SAMPLE_RATE = 16000
    private const val REC_CHANNELS = 1
    private const val REC_ENCODING_BITS = 16
    private const val REC_VAD_SILENCE_MS = 1200L   // 静音持续多久判定一句话结束
    private const val REC_VAD_THRESHOLD = 0.012f   // RMS 归一化振幅阈值（低于=静音）
    private const val REC_MIN_MS = 400L            // 最短有效录音时长，避免误触发
    private const val REC_MAX_MS = 30000L          // 单句最长录音保护

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 当前活动录音器（供手动停止时立即中断 AudioRecord）。 */
    private var activeRec: AudioRecord? = null
    /** 手动停止标志（长按说话"松开结束"）。 */
    private val stopFlag = AtomicBoolean(false)

    /**
     * 统一识别入口。
     * @param context  调用方上下文（建议在主线程调用；local 分支会直接创建 SpeechRecognizer）
     * @param onStatus 状态回调（如「聆听中…」「转写中…」），用于 UI 提示
     * @param onFinal  识别成功，返回文本
     * @param onError  (code, msg) 错误回调，code 同 SpeechRecognizer.ERROR_* 或 -1/-2/-3
     */
    fun recognize(
        context: Context,
        onStatus: (String) -> Unit = {},
        onFinal: (String) -> Unit,
        onError: (Int, String) -> Unit,
    ) {
        val ctx = context.applicationContext
        stopFlag.set(false)
        when (val source = QuroSttPrefs.getSource(ctx)) {
            QuroSttPrefs.SOURCE_ONDEVICE -> startOnDevice(ctx, onStatus, onFinal, onError)
            QuroSttPrefs.SOURCE_MODEL -> startCloud(ctx, onStatus, onFinal, onError)
            else -> QuroSttHolder.startListening(
                context = ctx,
                language = QuroSttPrefs.getLanguage(ctx),
                partialResults = QuroSttPrefs.getPartial(ctx),
                onPartial = { onStatus(it) },
                onFinal = onFinal,
                onError = onError,
            )
        }
    }

    /**
     * 手动停止当前识别（长按说话"松开结束"）。
     * 云端 / 端侧分支：置 stopFlag 并立即停止 AudioRecord，录音循环随即退出并转写已录部分；
     * 本地分支：委托 [QuroSttHolder.stopListening] 结束原生 SpeechRecognizer 会话（无活动会话时为安全空操作）。
     */
    fun stop() {
        stopFlag.set(true)
        runCatching { activeRec?.stop() }
        runCatching { QuroSttHolder.stopListening() }
    }

    private fun hasRecordPermission(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    // ── 云端模型分支 ──
    private fun startCloud(
        ctx: Context,
        onStatus: (String) -> Unit,
        onFinal: (String) -> Unit,
        onError: (Int, String) -> Unit,
    ) {
        if (!hasRecordPermission(ctx)) {
            main { onError(-1, "缺少录音权限（RECORD_AUDIO）") }
            return
        }
        scope.launch {
            val cfg = QuroModelConfigRepository(ctx).load()
            val provider = QuroSttPrefs.getModelProvider(ctx)
            val modelName = QuroSttPrefs.getModelName(ctx).ifBlank { QuroSttPrefs.getModelRef(ctx) }
            if (cfg.apiKey.isBlank()) {
                main { onError(-1, "未配置 API Key，无法云端转写") }
                return@launch
            }
            if (!QuroSttHolder.providerSupportsAudio(provider)) {
                QuroSttHolder.pushLog("ℹ️ provider($provider) 不在已知音频转写白名单，仍尝试请求")
            }
            onStatus("聆听中（云端）…")
            val pcm = recordUtterance(ctx, onStatus) ?: run {
                main { onError(-2, "未识别到语音") }
                return@launch
            }
            onStatus("转写中…")
            val wav = File(ctx.cacheDir, "stt_dialog_${System.currentTimeMillis()}.wav")
            writeWav(pcm, wav)
            QuroSttHolder.transcribe(
                ctx = ctx,
                audioFile = wav,
                baseUrl = cfg.baseUrl,
                apiKey = cfg.apiKey,
                model = modelName.ifBlank { "whisper-1" },
                language = "zh",
                onFinal = { text -> runCatching { wav.delete() }; main { onFinal(text) } },
                onError = { code, msg -> runCatching { wav.delete() }; main { onError(code, msg) } },
            )
        }
    }

    // ── 端侧模型分支 ──
    private fun startOnDevice(
        ctx: Context,
        onStatus: (String) -> Unit,
        onFinal: (String) -> Unit,
        onError: (Int, String) -> Unit,
    ) {
        if (!hasRecordPermission(ctx)) {
            main { onError(-1, "缺少录音权限（RECORD_AUDIO）") }
            return
        }
        scope.launch {
            // 设备前置校验：非 arm64 / 无预编译 .so 时直接给结论，别让用户在「聆听中」里空等
            if (!AsrDeviceCompat.isSupported(ctx)) {
                main { onError(-1, AsrDeviceCompat.unsupportedReason(ctx)) }
                return@launch
            }
            if (!QuroOnDeviceAsr.isModelAvailable(ctx)) {
                main { onError(-1, "还没有下载语音识别模型，请到「语音服务 → 语音识别」下载推荐模型（约 22MB）") }
                return@launch
            }
            if (!QuroOnDeviceAsr.isReady()) {
                onStatus("端侧模型加载中…")
                if (!QuroOnDeviceAsr.ensureLoaded(ctx)) {
                    // 把引擎给出的具体原因原样透出，不再只有「加载失败」四个字
                    main { onError(-1, QuroOnDeviceAsr.lastError.ifBlank { "端侧模型加载失败" }) }
                    return@launch
                }
            }
            onStatus("聆听中（端侧）…")
            val pcm = recordUtterance(ctx, onStatus) ?: run {
                main { onError(-2, "没有听到说话内容（可能离麦太远或环境太吵）") }
                return@launch
            }
            onStatus("识别中…")
            val text = QuroOnDeviceAsr.recognize(pcm)
            main {
                if (text.isNotBlank()) onFinal(text)
                else onError(-2, QuroOnDeviceAsr.lastError.ifBlank { "未识别到文字" })
            }
        }
    }

    /**
     * 录音一段语音（VAD 静音断句），返回 PCM 16bit little-endian 裸流字节；
     * 无录音权限 / 录音器不可用 / 录音过短均返回 null（原因经 onStatus 提示）。
     * 必须在 IO 线程调用（阻塞式 rec.read）。
     */
    private fun recordUtterance(ctx: Context, onStatus: (String) -> Unit): ByteArray? {
        val minBuf = AudioRecord.getMinBufferSize(
            REC_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) { onStatus("录音缓冲初始化失败"); return null }
        // VOICE_RECOGNITION 音源由厂商针对识别场景调过降噪/AGC，识别率明显优于裸 MIC；
        // 少数机型不提供该音源，回退 MIC。
        val rec = createRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, minBuf)
            ?: createRecord(MediaRecorder.AudioSource.MIC, minBuf)
        if (rec == null) { onStatus("无法创建录音器（麦克风可能被其他应用占用）"); return null }

        onStatus("聆听中…")
        val pcm = ByteArrayOutputStream()
        try {
            val frame = ShortArray(minBuf / 2)
            rec.startRecording()
            activeRec = rec
            val startMs = System.currentTimeMillis()
            var lastVoiceMs = startMs
            while (true) {
                if (stopFlag.get()) break
                val n = rec.read(frame, 0, frame.size)
                if (n <= 0) {
                    if (n == AudioRecord.ERROR_INVALID_OPERATION || n < 0) break
                    continue
                }
                val buf = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until n) buf.putShort(frame[i])
                pcm.write(buf.array())
                var sum = 0.0
                for (i in 0 until n) { val s = frame[i].toLong(); sum += s * s }
                val rms = sqrt(sum / n) / 32768.0
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
            onStatus("录音异常：${e.message}")
        } finally {
            activeRec = null
            try { rec.release() } catch (_: Throwable) {}
        }
        if (pcm.size() <= REC_SAMPLE_RATE * REC_ENCODING_BITS / 8 * 0.3f) {
            onStatus("没听清"); return null
        }
        return pcm.toByteArray()
    }

    /**
     * 创建 [AudioRecord]；音源不可用或初始化失败时返回 null（并确保不泄漏半初始化实例）。
     */
    private fun createRecord(audioSource: Int, minBuf: Int): AudioRecord? {
        val rec = try {
            AudioRecord(
                audioSource, REC_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 2
            )
        } catch (_: Throwable) {
            return null
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            try { rec.release() } catch (_: Throwable) {}
            return null
        }
        return rec
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

    private fun main(block: () -> Unit) = mainHandler.post(block)
}
