package com.ai.assistance.quro.core.tools

import android.content.Context
import android.os.Build
import com.k2fsa.sherpa.ncnn.DecoderConfig
import com.k2fsa.sherpa.ncnn.FeatureExtractorConfig
import com.k2fsa.sherpa.ncnn.ModelConfig
import com.k2fsa.sherpa.ncnn.RecognizerConfig
import java.io.File

/**
 * 端侧 ASR（离线语音识别）模型配置系统 —— 基于 Sherpa-NCNN **流式 transducer**，全程本地不连云。
 *
 * ## 为什么从 SenseVoice 换成流式 zipformer（用户问题 4 的真实修复）
 *
 * 旧实现用 `OfflineRecognizer` + SenseVoice。对随包 .so 做符号提取后确认它
 * **不含任何 OfflineRecognizer 符号**（详见 [com.k2fsa.sherpa.ncnn.SherpaNcnn] 头部注释），
 * 端侧识别在任何设备上都是 `UnsatisfiedLinkError`，从未跑通过。
 * 叠加 SenseVoice 的体积问题（下载 215.8MB / 落盘 222MB+ / 全量常驻内存），
 * 对手机而言是双重不可用。
 *
 * 现方案：用 .so 真实导出的流式 `SherpaNcnn` API，配 transducer 三件套模型
 * （encoder/decoder/joiner 的 .ncnn.param + .ncnn.bin），最小 22MB 下载，
 * 流式解码内存占用低，且原生层自带端点检测（说完自动停）。
 */

/** 错误页下限：任何 <1MB 的「模型目录」必为坏文件/HTML 错误页。 */
const val MIN_VALID_MODEL_BYTES = 1_000_000L

/**
 * 已部署目录内最大文件字节数；无目录/非目录返回 0。
 * 用于兜底拒绝把几 KB 的错误页当模型丢给 :asr 进程。
 */
fun deployedDirMaxFileBytes(dir: String?): Long {
    val d = dir?.let { File(it) } ?: return 0L
    if (!d.isDirectory) return 0L
    return d.walkTopDown().filter { it.isFile }.maxOfOrNull { it.length() } ?: 0L
}

/** 目录占用总字节数（自检页展示「模型占用空间」用）。 */
fun deployedDirTotalBytes(dir: String?): Long {
    val d = dir?.let { File(it) } ?: return 0L
    if (!d.isDirectory) return 0L
    return d.walkTopDown().filter { it.isFile }.sumOf { it.length() }
}

/** 人类可读体积。 */
fun formatBytes(bytes: Long): String = when {
    bytes <= 0L -> "0 B"
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024 -> String.format("%.1f KB", bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024))
    else -> String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024))
}

/**
 * 端侧 ASR 引擎设备兼容性。
 *
 * 本工程 `abiFilters` 仅编入 `arm64-v8a`，非 arm64 设备没有对应 .so，
 * `System.loadLibrary` 必抛 `UnsatisfiedLinkError`。部署前先做 ABI 前置校验。
 */
object AsrDeviceCompat {
    /** 引擎原生库支持的设备 ABI 集合（与 abiFilters 保持一致）。 */
    val SUPPORTED_ABIS: Set<String> = setOf("arm64-v8a")

    /** 当前设备 ABI 是否命中引擎支持集合。 */
    fun isAbiSupported(): Boolean = Build.SUPPORTED_ABIS.any { it in SUPPORTED_ABIS }

    /** 引擎原生库是否已随安装包落地（非 arm64 设备不会被抽取到 nativeLibraryDir）。 */
    fun isNativeLibPresent(ctx: Context): Boolean {
        val dir = runCatching { ctx.applicationContext.applicationInfo.nativeLibraryDir }
            .getOrNull() ?: return false
        return File(dir, "libsherpa-ncnn-jni.so").exists()
    }

    /** 综合判定：当前设备能否运行端侧离线识别。 */
    fun isSupported(ctx: Context): Boolean = isAbiSupported() && isNativeLibPresent(ctx)

    /** 不支持时给 UI 的人类可读原因。 */
    fun unsupportedReason(ctx: Context): String {
        if (!isAbiSupported()) {
            val abis = Build.SUPPORTED_ABIS.joinToString(", ")
            return "本机 CPU 架构（$abis）不支持端侧离线识别引擎（需 arm64-v8a）。请改用「本地识别」或「AI 模型」引擎。"
        }
        if (!isNativeLibPresent(ctx)) {
            return "安装包内未找到 libsherpa-ncnn-jni.so（F-Droid 版本不含预编译原生库）。请改用「本地识别」或「AI 模型」引擎。"
        }
        return ""
    }
}

/**
 * 端侧 ASR 模型类型。
 *
 * 当前随包 .so 只实现了流式 transducer（`SherpaNcnn`），故只有一种可用类型。
 * [SENSE_VOICE_LEGACY] 仅用于**识别历史遗留部署**并提示用户迁移，不可加载。
 */
enum class AsrModelType(val label: String) {
    /** 流式 transducer（encoder/decoder/joiner 三件套），当前唯一可运行类型。 */
    STREAMING_TRANSDUCER("流式 Transducer · 实时 · 离线"),

    /** 历史遗留的 SenseVoice 非流式部署——引擎不含对应符号，无法加载，仅用于提示迁移。 */
    SENSE_VOICE_LEGACY("SenseVoice（旧版·引擎不支持）"),

    UNKNOWN("未知");
}

/** 端侧 ASR 模型在磁盘上的实际文件（已定位的绝对路径）。 */
data class AsrModelFiles(
    val type: AsrModelType,
    val encoderParam: String,
    val encoderBin: String,
    val decoderParam: String,
    val decoderBin: String,
    val joinerParam: String,
    val joinerBin: String,
    val tokens: String,
    /** 是否命中 int8 量化权重（体积/内存更小）。 */
    val int8: Boolean,
)

/** 目录「布局」识别结果。 */
enum class AsrModelLayout {
    /** 流式 transducer 三件套齐全，可加载。 */
    TRANSDUCER,

    /** 旧 SenseVoice NCNN 部署（model.ncnn.param 等），引擎无对应符号，不可加载。 */
    SENSE_VOICE_LEGACY,

    /** 旧 Sherpa-ONNX 部署，与 NCNN 引擎不兼容。 */
    ONNX_LEGACY,

    NONE,
}

/** 三件套角色。 */
private val TRANSDUCER_ROLES = listOf("encoder", "decoder", "joiner")

/**
 * 目录布局识别：只看文件形态，不依赖部署记录。
 *
 * 判定顺序有意为「先 transducer 后 legacy」：transducer 目录里也存在 .ncnn.param，
 * 若先判 SenseVoice 会把合法模型误判为不可用。
 */
fun detectAsrLayout(dir: File): AsrModelLayout {
    if (!dir.exists() || !dir.isDirectory) return AsrModelLayout.NONE
    val files = dir.walkTopDown().filter { it.isFile && it.length() > 0 }.take(4000).toList()
    if (files.isEmpty()) return AsrModelLayout.NONE

    // 三件套齐全 → transducer
    val hasAllRoles = TRANSDUCER_ROLES.all { role ->
        files.any { it.name.contains(role, true) && it.name.endsWith(".param", true) }
    }
    if (hasAllRoles) return AsrModelLayout.TRANSDUCER

    val hasOnnx = files.any { it.name.endsWith(".onnx", true) }
    if (hasOnnx) return AsrModelLayout.ONNX_LEGACY

    val hasNcnn = files.any {
        it.name.endsWith(".ncnn.param", true) || it.name.endsWith(".ncnn.bin", true)
    }
    if (hasNcnn) return AsrModelLayout.SENSE_VOICE_LEGACY

    return AsrModelLayout.NONE
}

/**
 * 从目录定位流式 transducer 所需的 7 个文件（6 个模型 + tokens）。
 *
 * 选择策略：encoder/joiner **优先 int8 量化权重**（更小更快，手机端首选），
 * decoder 通常不提供 int8，回退 fp32。任一角色缺失返回 null。
 *
 * 兼容压缩包把模型放进顶层子目录的情况（walkTopDown 递归查找）。
 */
fun findAsrFiles(dir: File, type: AsrModelType = AsrModelType.STREAMING_TRANSDUCER): AsrModelFiles? {
    if (type == AsrModelType.SENSE_VOICE_LEGACY) return null
    if (!dir.exists() || !dir.isDirectory) return null
    val files = dir.walkTopDown().filter { it.isFile && it.length() > 0 }.take(4000).toList()
    if (files.isEmpty()) return null

    val tokens = files.firstOrNull { it.name.equals("tokens.txt", true) }
        ?: files.firstOrNull { it.name.endsWith("tokens.txt", true) }
        ?: return null

    var anyInt8 = false
    val picked = mutableMapOf<String, Pair<String, String>>()

    for (role in TRANSDUCER_ROLES) {
        // 该角色所有 .param 候选（排除 test_wavs 等噪声目录里的同名文件不影响，因为后缀限定 .param）
        val params = files.filter { it.name.contains(role, true) && it.name.endsWith(".param", true) }
        if (params.isEmpty()) return null
        // 优先 int8：要求同名 .bin 同时存在，否则不算数
        val int8Param = params.firstOrNull { it.name.contains(".int8.", true) && binOf(it).exists() }
        val plainParam = params.firstOrNull { !it.name.contains(".int8.", true) && binOf(it).exists() }
        val chosen = int8Param ?: plainParam ?: return null
        if (chosen === int8Param) anyInt8 = true
        picked[role] = chosen.absolutePath to binOf(chosen).absolutePath
    }

    val enc = picked["encoder"] ?: return null
    val dec = picked["decoder"] ?: return null
    val joi = picked["joiner"] ?: return null

    return AsrModelFiles(
        type = AsrModelType.STREAMING_TRANSDUCER,
        encoderParam = enc.first, encoderBin = enc.second,
        decoderParam = dec.first, decoderBin = dec.second,
        joinerParam = joi.first, joinerBin = joi.second,
        tokens = tokens.absolutePath,
        int8 = anyInt8,
    )
}

/** `xxx.ncnn.param` → `xxx.ncnn.bin`。 */
private fun binOf(param: File): File =
    File(param.parentFile, param.name.removeSuffix(".param").removeSuffix(".PARAM") + ".bin")

/** 端侧 ASR 模型规格（内置预设 / 自定义链接通用）。 */
data class AsrModelSpec(
    val id: String,
    val displayName: String,
    /** 一句话说明适用场景与限制，直接展示给用户，避免选错。 */
    val note: String,
    val type: AsrModelType,
    val downloadUrl: String,
    /** 官方 release asset 实测下载体积（字节），用于 UI 显示与下载前空间预检。 */
    val downloadBytes: Long,
    /**
     * 下载压缩包最小字节下限，**仅用于拒绝 HTML 错误页等明显坏文件**。
     *
     * ⚠️ 历史坑：此前误用「解压后体积」量级去卡「压缩包体积」，导致每个真实下载都被误判失败、
     * 文件被删、部署记录写不进去，表现为「部署后不能保存、返回又重新下载」的死循环。
     * 现统一用 1MB 错误页下限；模型可用性以解压后布局校验为准。
     */
    val minSizeBytes: Long = MIN_VALID_MODEL_BYTES,
    val numThreads: Int = 2,
)

/**
 * 内置模型目录 —— 全部为 Sherpa-NCNN **流式 transducer**，全部适配手机。
 *
 * 体积为官方 GitHub release asset 实测值（`api.github.com/.../releases/tags/models`）。
 * 排序即推荐优先级：默认首选中文 14M（22MB），这是对「原 215MB SenseVoice 不适合手机」的直接答复。
 */
object AsrModelCatalog {
    private const val BASE = "https://github.com/k2-fsa/sherpa-ncnn/releases/download/models"

    val BUILTIN: List<AsrModelSpec> = listOf(
        AsrModelSpec(
            id = "zipformer-zh-14M",
            displayName = "流式 Zipformer 中文 14M · 22MB · 推荐",
            note = "手机首选：体积最小、延迟最低、内存占用最省。仅中文（英文词会识别不准）。",
            type = AsrModelType.STREAMING_TRANSDUCER,
            downloadUrl = "$BASE/sherpa-ncnn-streaming-zipformer-zh-14M-2023-02-23.tar.bz2",
            downloadBytes = 23_247_105L,
            numThreads = 2,
        ),
        AsrModelSpec(
            id = "lstm-transducer-small",
            displayName = "流式 LSTM Transducer Small · 18MB",
            note = "体积最小的中英双语模型，准确率低于 Zipformer，适合极度在意空间的设备。",
            type = AsrModelType.STREAMING_TRANSDUCER,
            downloadUrl = "$BASE/sherpa-ncnn-lstm-transducer-small-2023-02-13.tar.bz2",
            downloadBytes = 19_105_573L,
            numThreads = 2,
        ),
        AsrModelSpec(
            id = "zipformer-en-20M",
            displayName = "流式 Zipformer 英文 20M · 37MB",
            note = "仅英文。中文内容不要选这个。",
            type = AsrModelType.STREAMING_TRANSDUCER,
            downloadUrl = "$BASE/sherpa-ncnn-streaming-zipformer-20M-2023-02-17.tar.bz2",
            downloadBytes = 38_802_599L,
            numThreads = 2,
        ),
        AsrModelSpec(
            id = "zipformer-small-bilingual-zh-en",
            displayName = "流式 Zipformer 中英双语 Small · 141MB",
            note = "中英混说场景选这个（如「帮我查一下 GPU 占用」）。体积较大，建议 WiFi 下载。",
            type = AsrModelType.STREAMING_TRANSDUCER,
            downloadUrl = "$BASE/sherpa-ncnn-streaming-zipformer-small-bilingual-zh-en-2023-02-16.tar.bz2",
            downloadBytes = 147_432_697L,
            numThreads = 3,
        ),
        AsrModelSpec(
            id = "conv-emformer-small",
            displayName = "流式 ConvEmformer Small · 27MB",
            note = "英文为主，低延迟流式结构，作为 Zipformer 的备选。",
            type = AsrModelType.STREAMING_TRANSDUCER,
            downloadUrl = "$BASE/sherpa-ncnn-conv-emformer-transducer-small-2023-01-09.tar.bz2",
            downloadBytes = 28_385_066L,
            numThreads = 2,
        ),
    )

    /** 默认推荐（手机最合适的一档）。 */
    val RECOMMENDED: AsrModelSpec get() = BUILTIN.first()

    fun byId(id: String): AsrModelSpec? = BUILTIN.firstOrNull { it.id == id }
}

/**
 * 按定位到的文件构建流式 [RecognizerConfig]。
 *
 * @param numThreads 解码线程数；手机端 2~3 即可，过高反而因大小核调度抖动。
 * @param endpointTailSilenceSec 说完后判定「这句结束」的尾部静音秒数。
 */
fun buildRecognizerConfig(
    files: AsrModelFiles,
    numThreads: Int = 2,
    endpointTailSilenceSec: Float = 1.0f,
    maxUtteranceSec: Float = 30.0f,
): RecognizerConfig = RecognizerConfig(
    featConfig = FeatureExtractorConfig(sampleRate = 16000f, featureDim = 80),
    modelConfig = ModelConfig(
        encoderParam = files.encoderParam,
        encoderBin = files.encoderBin,
        decoderParam = files.decoderParam,
        decoderBin = files.decoderBin,
        joinerParam = files.joinerParam,
        joinerBin = files.joinerBin,
        tokens = files.tokens,
        numThreads = numThreads.coerceIn(1, 4),
        // 手机端一律关 GPU：ncnn Vulkan 在国产 GPU 驱动上兼容性差，且本 .so 未必编入 Vulkan 后端
        useGPU = false,
    ),
    decoderConfig = DecoderConfig(method = "greedy_search", numActivePaths = 4),
    enableEndpoint = true,
    rule1MinTrailingSilence = 2.4f,
    rule2MinTrailingSilence = endpointTailSilenceSec,
    rule3MinUtteranceLength = maxUtteranceSec,
)
