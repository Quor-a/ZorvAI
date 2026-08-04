package com.k2fsa.sherpa.ncnn

import android.content.res.AssetManager

/**
 * Sherpa-NCNN **流式（在线）** 语音识别封装。
 *
 * ⚠️ 为什么存在这个文件（重要背景，勿轻易改动字段名）：
 *
 * 本工程此前的端侧 STT 走 `OfflineRecognizer`（SenseVoice 非流式），但对随包预编译的
 * `app/src/full/jniLibs/arm64-v8a/libsherpa-ncnn-jni.so` 做符号提取后确认：
 *
 * ```
 * $ grep -a -o "Java_com_k2fsa_sherpa_ncnn_[A-Za-z0-9_]*" libsherpa-ncnn-jni.so | sort -u
 * Java_com_k2fsa_sherpa_ncnn_SherpaNcnn_acceptWaveform
 * Java_com_k2fsa_sherpa_ncnn_SherpaNcnn_decode
 * Java_com_k2fsa_sherpa_ncnn_SherpaNcnn_delete
 * Java_com_k2fsa_sherpa_ncnn_SherpaNcnn_getText
 * Java_com_k2fsa_sherpa_ncnn_SherpaNcnn_inputFinished
 * Java_com_k2fsa_sherpa_ncnn_SherpaNcnn_isEndpoint
 * Java_com_k2fsa_sherpa_ncnn_SherpaNcnn_isReady
 * Java_com_k2fsa_sherpa_ncnn_SherpaNcnn_newFromAsset
 * Java_com_k2fsa_sherpa_ncnn_SherpaNcnn_newFromFile
 * Java_com_k2fsa_sherpa_ncnn_SherpaNcnn_reset
 * Java_com_k2fsa_sherpa_ncnn_WaveReader_00024Companion_readWave
 * ```
 *
 * 该 .so **不含任何 `OfflineRecognizer` / `OfflineStream` 符号**（grep 计数为 0）。
 * 也就是说旧路径 `OfflineRecognizer.newFromFile()` 在任何设备上都必然抛
 * `UnsatisfiedLinkError: No implementation found for ...`，端侧识别从未成功过一次。
 * 这正是用户反馈「端侧模型不适合手机」的真实根因——不是慢，是根本跑不起来。
 *
 * 本文件改用 .so 里**真实存在**的流式 `SherpaNcnn` API，并配手机级流式 zipformer 模型
 * （22MB 起，对比原 SenseVoice 的 215MB 下载 / 222MB 落盘）。
 *
 * ⚠️ 字段契约同样来自对 .so 字符串表的提取，**不是照搬文档、不是猜的**：
 * `featConfig / modelConfig / decoderConfig / enableEndpoint / rule1MinTrailingSilence /
 *  rule2MinTrailingSilence / rule3MinUtteranceLength / hotwordsFile / hotwordsScore /
 *  encoderParam / encoderBin / decoderParam / decoderBin / joinerParam / joinerBin /
 *  tokens / numThreads / useGPU / sampleRate / featureDim / method / numActivePaths`
 * 逐个 grep 命中。JNI 通过 `GetFieldID(名称, 类型签名)` 反射读取这些字段，
 * **改名或改类型 = 原生层直接崩溃**，改动前务必重新核对 .so。
 */

/** 声学特征配置。`sampleRate` 必须是 Float（JNI 按 "F" 读取），不可写成 Int。 */
data class FeatureExtractorConfig(
    var sampleRate: Float = 16000f,
    var featureDim: Int = 80,
)

/**
 * Transducer（encoder/decoder/joiner 三件套）模型配置。
 * 六个路径均为**绝对路径**（从文件加载时 assetManager 传 null）。
 */
data class ModelConfig(
    var encoderParam: String = "",
    var encoderBin: String = "",
    var decoderParam: String = "",
    var decoderBin: String = "",
    var joinerParam: String = "",
    var joinerBin: String = "",
    var tokens: String = "",
    var numThreads: Int = 2,
    /** 手机端一律 false：ncnn Vulkan 后端在国产 GPU 驱动上兼容性差，且本 .so 未必编入 Vulkan。 */
    var useGPU: Boolean = false,
)

/** 解码配置。modified_beam_search 更准但更慢；手机端默认 greedy_search。 */
data class DecoderConfig(
    var method: String = "greedy_search",
    var numActivePaths: Int = 4,
)

/**
 * 识别器总配置。
 *
 * 端点检测（endpointing）三条规则由原生层实现，正是「说完自动停」的能力来源：
 *  - rule1：识别到任何文字**之前**允许的静音时长（用户迟迟不开口）
 *  - rule2：识别到文字**之后**的尾部静音时长（说完了）
 *  - rule3：单句最长时长（兜底强制断句）
 */
data class RecognizerConfig(
    var featConfig: FeatureExtractorConfig = FeatureExtractorConfig(),
    var modelConfig: ModelConfig = ModelConfig(),
    var decoderConfig: DecoderConfig = DecoderConfig(),
    var enableEndpoint: Boolean = true,
    var rule1MinTrailingSilence: Float = 2.4f,
    var rule2MinTrailingSilence: Float = 1.0f,
    var rule3MinUtteranceLength: Float = 30.0f,
    var hotwordsFile: String = "",
    var hotwordsScore: Float = 1.5f,
)

/**
 * 流式识别器。**只能在独立 `:asr` 进程中构造**（见 QuroAsrService）：
 * ncnn 原生层遇到不兼容模型会 SIGSEGV，Java 层 try/catch 拦不住，隔离进程后主进程不受影响。
 *
 * 生命周期：newFromFile → (acceptWaveform → while(isReady) decode)* → inputFinished → decode → getText → release
 */
class SherpaNcnn(
    assetManager: AssetManager? = null,
    val config: RecognizerConfig,
) {
    private var ptr: Long = 0L

    init {
        ptr = if (assetManager != null) {
            newFromAsset(assetManager, config)
        } else {
            newFromFile(config)
        }
    }

    /** 是否构造成功（原生返回 0 指针表示模型加载失败）。 */
    val isValid: Boolean get() = ptr != 0L

    /** 送入 [-1,1] 归一化的单声道 float 采样。 */
    fun acceptWaveform(samples: FloatArray, sampleRate: Float) {
        if (ptr == 0L) return
        acceptWaveform(ptr, samples, sampleRate)
    }

    /** 告知原生层音频已结束，尾部特征需要 flush。 */
    fun inputFinished() {
        if (ptr == 0L) return
        inputFinished(ptr)
    }

    /** 是否已积累足够特征可以解码一步。 */
    fun isReady(): Boolean = if (ptr == 0L) false else isReady(ptr)

    /** 解码一步。调用前必须 [isReady] 为 true。 */
    fun decode() {
        if (ptr == 0L) return
        decode(ptr)
    }

    /** 是否检测到句子端点（说完了）。 */
    fun isEndpoint(): Boolean = if (ptr == 0L) false else isEndpoint(ptr)

    /**
     * 重置解码状态，开始新一句。
     * @param recreate true 时重建内部 stream（跨句必须 true，否则上一句残留特征会污染下一句）。
     */
    fun reset(recreate: Boolean = true) {
        if (ptr == 0L) return
        reset(ptr, recreate)
    }

    /** 当前已解码文本。 */
    fun getText(): String = if (ptr == 0L) "" else getText(ptr)

    /** 释放原生资源。可重复调用。 */
    fun release() {
        if (ptr != 0L) {
            delete(ptr)
            ptr = 0L
        }
    }

    protected fun finalize() {
        release()
    }

    private external fun delete(ptr: Long)
    private external fun newFromAsset(assetManager: AssetManager, config: RecognizerConfig): Long
    private external fun newFromFile(config: RecognizerConfig): Long
    private external fun acceptWaveform(ptr: Long, samples: FloatArray, sampleRate: Float)
    private external fun inputFinished(ptr: Long)
    private external fun isReady(ptr: Long): Boolean
    private external fun decode(ptr: Long)
    private external fun isEndpoint(ptr: Long): Boolean
    private external fun reset(ptr: Long, recreate: Boolean)
    private external fun getText(ptr: Long): String

    companion object {
        /** 原生库是否已成功加载。false 时禁止构造 [SherpaNcnn]。 */
        @Volatile
        var nativeLoaded: Boolean = false
            private set

        /** 原生库加载失败原因（供 UI 如实展示，不再静默）。 */
        @Volatile
        var nativeLoadError: String = ""
            private set

        init {
            // F-Droid 风味不打包预编译 .so，跳过加载避免 UnsatisfiedLinkError 崩溃。
            if (com.ai.assistance.quro.BuildConfig.FLAVOR == "fdroid") {
                nativeLoaded = false
                nativeLoadError = "F-Droid 版本不含预编译原生库，端侧识别不可用"
            } else {
                try {
                    System.loadLibrary("sherpa-ncnn-jni")
                    nativeLoaded = true
                } catch (e: Throwable) {
                    nativeLoaded = false
                    nativeLoadError = "原生库加载失败：${e.message}"
                }
            }
        }
    }
}
