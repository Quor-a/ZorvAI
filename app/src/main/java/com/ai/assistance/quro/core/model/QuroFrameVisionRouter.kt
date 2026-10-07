package com.ai.assistance.quro.core.model

/**
 * 视频通话「画面理解」的画面来源决策（原创，QuroVideoCallService 消费）。
 *
 * ## 为什么要独立成纯函数
 * 画面理解的降级优先级是**产品契约**，不是实现细节：一旦后续有人调整
 * 「先试视频通话模型还是先试图像识别」，会直接改变真机行为且没有任何测试报警。
 * 因此把整条优先级固化成 [QuroFrameVisionRouter.plan] 这一个**无 Android 依赖**的纯函数，
 * 由单元测试逐级钉死，服务侧只负责「按决策结果去执行」。
 *
 * ## 优先级契约（自上而下，命中即止）
 * 1. [QuroFrameVisionSource.MAIN_MODEL] —— **当前对话模型自己就有视觉识别能力** → 直接把画面喂给它，
 *    不额外发起任何远程调用（省一次 RTT，也避免画面被二次转述 loss）。
 * 2. [QuroFrameVisionSource.VIDEO_CALL_MODEL] —— 主模型没有视觉，但「功能模型配置 → **视频通话**」
 *    指定了独立模型 → **直接用视频通话模型做视觉识别**（用户已配置的模型，不浪费）。
 * 3. [QuroFrameVisionSource.IMAGE_RECOGNITION_MODEL] —— 上面都没有 → 用「功能模型配置 → **图像识别**」
 *    作为**保底**视觉模型。
 * 4. [QuroFrameVisionSource.NONE] —— 全都没有 → 本轮不注入任何画面上下文，退化为纯语音对话。
 *
 * ## 🔴 语义边界：「视频识别」≠ 本链路的画面理解
 * [QuroFunctionType.VIDEO_RECOGNITION]（视频识别）是**独立工具**（`video_understanding`，面向用户
 * 主动发起的整段视频文件分析），与视频通话的**实时单帧**画面理解是两条链路，本路由器**不消费它**。
 * 用户明确要求：功能模型配置里缺少图像识别配置时，目前只有「视频通话模型」可用于视频画面识别。
 */

/** 画面理解最终采用的画面来源。 */
enum class QuroFrameVisionSource {
    /** 当前对话模型自带视觉 → 画面直接喂给它自己看。 */
    MAIN_MODEL,

    /** 用「功能模型配置 → 视频通话」的独立模型做视觉识别。 */
    VIDEO_CALL_MODEL,

    /** 用「功能模型配置 → 图像识别」的模型做视觉识别（保底）。 */
    IMAGE_RECOGNITION_MODEL,

    /** 无可用视觉模型 → 本轮不注入画面，纯语音对话。 */
    NONE,
}

/**
 * 一个候选视觉模型的可用性。
 *
 * @param dedicatedModel 「功能模型配置」里是否**显式指定了独立模型**（未勾选「跟随主模型」且模型名非空）。
 *   这一点很关键：若只是「跟随主模型」，那拿到的仍是主模型 —— 主模型已被判定**没有**视觉能力，
 *   再拿它识别一次是纯粹的无用远程调用，必须判为不可用。
 * @param hasApiKey 该候选是否具备可用的 API 密钥（独立密钥或全局密钥）。
 */
data class QuroFrameVisionOption(
    val dedicatedModel: Boolean = false,
    val hasApiKey: Boolean = false,
) {
    /** 可用 = 确实是独立模型 且 有密钥。 */
    val usable: Boolean get() = dedicatedModel && hasApiKey

    companion object {
        /** 跟随主模型 / 未配置。 */
        val NONE = QuroFrameVisionOption()
    }
}

/**
 * 画面理解来源路由器（纯函数，无 Android / Compose 依赖，可直接单测）。
 */
object QuroFrameVisionRouter {

    /**
     * 按优先级选出画面理解来源，命中即止。
     *
     * @param mainModelHasVision 当前对话模型是否大概率支持视觉输入（见 [isLikelyVisionModel]）。
     * @param mainHasApiKey 当前对话模型是否具备可用密钥。
     * @param videoCall 「功能模型配置 → 视频通话」的候选状态。
     * @param imageRecognition 「功能模型配置 → 图像识别」的候选状态。
     */
    fun plan(
        mainModelHasVision: Boolean,
        mainHasApiKey: Boolean,
        videoCall: QuroFrameVisionOption = QuroFrameVisionOption.NONE,
        imageRecognition: QuroFrameVisionOption = QuroFrameVisionOption.NONE,
    ): QuroFrameVisionSource = when {
        // Level 1：主模型自己看得见 → 喂图，零额外远程调用。
        mainModelHasVision && mainHasApiKey -> QuroFrameVisionSource.MAIN_MODEL
        // Level 2：视频通话模型直接顶上来做视觉识别。
        videoCall.usable -> QuroFrameVisionSource.VIDEO_CALL_MODEL
        // Level 3：图像识别模型保底。
        imageRecognition.usable -> QuroFrameVisionSource.IMAGE_RECOGNITION_MODEL
        // Level 4：什么都没有 → 纯语音。
        else -> QuroFrameVisionSource.NONE
    }

    /**
     * 当前模型名是否**大概率**支持视觉输入。
     *
     * 与 [com.ai.assistance.quro.core.tools.QuroScreenshotTools] 里的同名判断同源（黑名单策略）：
     * 只把「明确已知是纯文本 / 嵌入 / 语音」的模型判为不支持，其余**默认乐观**认为支持 ——
     * 猜错的代价只是多发一次无用请求，而漏判的代价是画面根本进不来。
     */
    fun isLikelyVisionModel(name: String?): Boolean {
        val n = name?.trim()?.lowercase().orEmpty()
        if (n.isBlank()) return true
        return TEXT_ONLY_MARKERS.none { n.contains(it) }
    }

    /**
     * 纯文本 / 非视觉模型名标记（小写匹配）。
     *
     * 🔴 均为 `contains` 语义，短标记必须足够独特，否则会误伤视觉模型
     * （例如 `instruct` 会命中 `qwen2.5-vl-instruct` 这类实际支持视觉的模型）。
     */
    private val TEXT_ONLY_MARKERS = listOf(
        // OpenAI 纯文本 / 嵌入 / 语音家族
        "gpt-3.5", "text-embedding", "babbage", "davinci", "ada", "tts-1", "whisper", "embedding",
        // 明确标注纯指令微调、无视觉的常见小模型（🔴 只写完整型号，不写裸 "instruct"，
        // 否则会误伤 qwen2.5-vl-instruct 这类实际支持视觉的模型）
        "qwen2-0.5b", "qwen2-1.5b", "qwen2-7b-instruct",
        "qwen2.5-0.5b", "qwen2.5-1.5b",
        "qwen3-0.6b", "qwen3-1.7b",
        // LLM 指令模型里最保守的一档
        "llama-2", "llama2",
    )
}