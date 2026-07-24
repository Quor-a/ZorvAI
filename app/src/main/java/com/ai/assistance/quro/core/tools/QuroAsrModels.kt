package com.ai.assistance.quro.core.tools

import com.k2fsa.sherpa.ncnn.FeatureConfig
import com.k2fsa.sherpa.ncnn.OfflineModelConfig
import com.k2fsa.sherpa.ncnn.OfflineRecognizerConfig
import com.k2fsa.sherpa.ncnn.OfflineSenseVoiceModelConfig
import java.io.File

/**
 * 端侧 ASR（离线语音识别）通用模型配置系统 —— 基于 Sherpa-NCNN（全程本地、不连云）。
 *
 * 设计目标（来自用户需求）：
 *  - 引擎从 Sherpa-ONNX 迁移到 Sherpa-NCNN（更轻、规避 ONNX 在部分设备上的原生 SIGSEGV）；
 *  - 模型为 NCNN 格式：.ncnn.param + .ncnn.bin（+ tokens.txt），与 ONNX 的 model.onnx 不同；
 *  - 部署后按目录「自动识别布局」（是否 NCNN），再构建对应 OfflineRecognizerConfig；
 *  - 下载时校验文件大小，拒绝错误页（链接失效 / 返回 HTML 错误页）。
 *
 * 注意：当前 Sherpa-NCNN 的预置 Kotlin 封装（com.k2fsa.sherpa.ncnn，已随源码打入本工程
 * core/tools 之外的 com/k2fsa/sherpa/ncnn 包）只暴露 SenseVoice 一种离线模型配置
 * （OfflineSenseVoiceModelConfig），其余类型（transducer / paraformer 的 NCNN 导出）需自行扩展
 * 封装或自编译 native。SenseVoice 本身支持 中英日韩粤，满足离线中文识别需求。
 */

/** 统一下限：真实 Sherpa-NCNN 模型压缩包均 ≥100MB，任何 <1MB 的「模型目录」必为坏文件/错误页。 */
const val MIN_VALID_MODEL_BYTES = 1_000_000L

/**
 * 已部署目录内最大文件字节数；无目录/非目录返回 0。
 * 用于「加载路径」兜底：无论文件名/已部署类型是什么，最大文件 < [MIN_VALID_MODEL_BYTES]
 * 直接判为坏模型，避免把 3452 字节错误页丢进 :asr 进程卡 60s。
 */
fun deployedDirMaxFileBytes(dir: String?): Long {
    val d = dir?.let { File(it) } ?: return 0L
    if (!d.isDirectory) return 0L
    return d.walkTopDown().filter { it.isFile }.maxOfOrNull { it.length() } ?: 0L
}

enum class AsrModelType(val label: String) {
    SENSE_VOICE("SenseVoice · 中英日韩粤 · 离线"),
    UNKNOWN("未知");

    /** 是否为 NCNN 模型（当前封装仅 SenseVoice 可构造离线配置）。 */
    val isNcnn: Boolean get() = this == SENSE_VOICE
}

/** 端侧 ASR 模型在磁盘上的实际文件（已定位路径）。 */
data class AsrModelFiles(
    val type: AsrModelType,
    /** NCNN 模型目录（绝对路径），内含 .ncnn.param/.ncnn.bin + tokens.txt */
    val modelDir: String,
    val tokensPath: String? = null,
)

/** 目录「布局」识别结果（与具体类型无关，只看文件形态）。 */
enum class AsrModelLayout { NCNN, ONNX_LEGACY, NONE }

/**
 * 目录布局识别：不依赖具体类型，只看文件形态。
 *  - NCNN：存在 .ncnn.param 或 .ncnn.bin（SenseVoice / transducer 等 NCNN 导出共用）
 *  - ONNX_LEGACY：存在 model.onnx / encoder*.onnx / decoder*.onnx（旧 Sherpa-ONNX 部署，与 NCNN 引擎不兼容）
 *  - NONE：两者皆无
 */
fun detectAsrLayout(dir: File): AsrModelLayout {
    if (!dir.exists() || !dir.isDirectory) return AsrModelLayout.NONE
    val files = dir.walkTopDown().filter { it.isFile && it.length() > 0 }.take(2000).toList()
    val hasNcnn = files.any {
        it.name.endsWith(".ncnn.param", true) || it.name.endsWith(".ncnn.bin", true)
    }
    val hasOnnx = files.any {
        it.name.equals("model.onnx", true) ||
            (it.name.contains("encoder", true) && it.name.endsWith(".onnx")) ||
            (it.name.contains("decoder", true) && it.name.endsWith(".onnx"))
    }
    return when {
        hasNcnn -> AsrModelLayout.NCNN
        hasOnnx -> AsrModelLayout.ONNX_LEGACY
        else -> AsrModelLayout.NONE
    }
}

/**
 * 按已知类型从目录定位所需文件。NCNN 模型以「目录」为单位加载（C++ 在目录内自动发现
 * .ncnn.param/.ncnn.bin），故返回真正包含 .ncnn 文件的绝对目录 + tokens.txt 路径。
 * 兼容压缩包把模型放进顶层子目录的情况（walkTopDown 会递归，再回退到 .ncnn 文件所在目录）。
 * 类型与布局不符返回 null。
 */
fun findAsrFiles(dir: File, type: AsrModelType): AsrModelFiles? {
    if (!dir.exists() || !dir.isDirectory) return null
    val files = dir.walkTopDown().filter { it.isFile && it.length() > 0 }.take(2000).toList()
    val ncnnFile = files.firstOrNull {
        it.name.endsWith(".ncnn.param", true) || it.name.endsWith(".ncnn.bin", true)
    } ?: return null
    // 取真正包含 .ncnn 文件的目录（压缩包可能把模型放在顶层子目录里）
    val modelDir = ncnnFile.parentFile?.absolutePath ?: dir.absolutePath
    val tokens = files.firstOrNull {
        it.name.endsWith("tokens.txt", true) || (it.name.contains("tokens", true) && it.name.endsWith(".txt"))
    }?.absolutePath
    return AsrModelFiles(
        type = if (type == AsrModelType.UNKNOWN) AsrModelType.SENSE_VOICE else type,
        modelDir = modelDir,
        tokensPath = tokens,
    )
}

/** 端侧 ASR 模型规格（内置预设 / 自定义链接通用）。 */
data class AsrModelSpec(
    val id: String,
    val displayName: String,
    val type: AsrModelType,
    val downloadUrl: String,
    /** 下载后最小字节数；小于此值视为链接失效 / 返回错误页，直接拒绝部署。 */
    val minSizeBytes: Long = 100_000_000,
    val language: String = "auto",
    val numThreads: Int = 4,
)

/**
 * 内置模型目录：覆盖 Sherpa-NCNN 的 SenseVoice 系列（支持 中英日韩粤，离线、非流式）。
 * 排序即推荐优先级（int8 体积小、首推；fp16 更准但更大）。
 *
 * 说明：Sherpa-NCNN 预置 Kotlin 封装仅暴露 SenseVoice，故此处所有可选模型均为 SenseVoice 变体。
 * 如需 lstm-transducer-zh / zipformer-zh 等纯中文 NCNN 模型，需扩展封装或自编译 native。
 */
object AsrModelCatalog {
    val BUILTIN: List<AsrModelSpec> = listOf(
        AsrModelSpec(
            id = "sense-voice-int8-2024", displayName = "SenseVoice int8 · 中英日韩粤 · 离线 · 推荐 · ~206MB",
            type = AsrModelType.SENSE_VOICE,
            downloadUrl = "https://github.com/k2-fsa/sherpa-ncnn/releases/download/asr-models/sherpa-ncnn-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2",
            minSizeBytes = 120_000_000,
            language = "auto",
        ),
        AsrModelSpec(
            id = "sense-voice-int8-2025", displayName = "SenseVoice int8 (2025-09) · 中英日韩粤 · 离线 · ~209MB",
            type = AsrModelType.SENSE_VOICE,
            downloadUrl = "https://github.com/k2-fsa/sherpa-ncnn/releases/download/asr-models/sherpa-ncnn-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09.tar.bz2",
            minSizeBytes = 120_000_000,
            language = "auto",
        ),
        AsrModelSpec(
            id = "sense-voice-fp16-2024", displayName = "SenseVoice fp16 · 更准更大 · 中英日韩粤 · ~417MB",
            type = AsrModelType.SENSE_VOICE,
            downloadUrl = "https://github.com/k2-fsa/sherpa-ncnn/releases/download/asr-models/sherpa-ncnn-sense-voice-zh-en-ja-ko-yue-2024-07-17.tar.bz2",
            minSizeBytes = 250_000_000,
            language = "auto",
        ),
        AsrModelSpec(
            id = "sense-voice-fp16-2025", displayName = "SenseVoice fp16 (2025-09) · 更准更大 · ~421MB",
            type = AsrModelType.SENSE_VOICE,
            downloadUrl = "https://github.com/k2-fsa/sherpa-ncnn/releases/download/asr-models/sherpa-ncnn-sense-voice-zh-en-ja-ko-yue-2025-09-09.tar.bz2",
            minSizeBytes = 250_000_000,
            language = "auto",
        ),
    )

    fun byId(id: String): AsrModelSpec? = BUILTIN.firstOrNull { it.id == id }
}

/**
 * 按类型构建 Sherpa-NCNN `OfflineRecognizerConfig`。
 * 关键：NCNN 以「绝对目录」为单位加载（assetManager 传 null），tokens 也必须是绝对路径。
 */
fun buildOfflineConfig(
    files: AsrModelFiles,
    language: String = "auto",
    numThreads: Int = 4,
): OfflineRecognizerConfig {
    val feat = FeatureConfig(sampleRate = 16000, featureDim = 80, dither = 0.0f)
    val senseVoice = OfflineSenseVoiceModelConfig(
        modelDir = files.modelDir,
        language = language,
        useInverseTextNormalization = false,
    )
    val modelConfig = OfflineModelConfig(
        senseVoice = senseVoice,
        numThreads = numThreads,
        tokens = files.tokensPath ?: "",
    )
    return OfflineRecognizerConfig(featConfig = feat, modelConfig = modelConfig, decodingMethod = "greedy_search")
}
