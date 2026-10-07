package com.ai.assistance.quro.cluster.model

import kotlinx.serialization.Serializable

/** 请求参数。null 表示"沿用宿主模型配置里的默认值"，不覆盖。 */
@Serializable
data class ModelParams(
    val temperature: Double? = null,
    val topP: Double? = null,
    val maxOutputTokens: Int? = null,
    val presencePenalty: Double? = null,
    val frequencyPenalty: Double? = null,
    val stop: List<String> = emptyList()
)

/**
 * 输出协议 —— 宿主模型能力不同，SDK 用不同方式从模型输出里抽取结构化信息。
 *
 * NATIVE_TOOL：模型原生支持 function calling（最优）
 * JSON_MODE  ：模型支持 JSON 输出约束，但没有 function calling
 * TEXT_REACT ：模型只会说自然语言，用 "行动: xxx\n参数: yyy" 文本协议模拟工具调用
 *
 * SDK 会根据宿主上报的 HostModelInfo.capabilities 自动选择，用户也可在角色设置里强制指定。
 */
enum class OutputProtocol { AUTO, NATIVE_TOOL, JSON_MODE, TEXT_REACT }

/**
 * 模型绑定 —— 每个角色一条独立的模型链路。
 *
 * 关键：`hostModelId` 是**宿主 App 模型配置界面里那条配置的 ID**。
 * SDK 不认识任何厂商、不存 Key、不发请求，只在运行时拿着这个 ID 问宿主要一个会话通道。
 */
@Serializable
data class ModelBinding(
    /** 宿主模型配置 ID（宿主 listModels() 返回的 HostModelInfo.id） */
    var hostModelId: String = "",
    /** 为空则用宿主模型 ID 作为显示名 */
    var label: String? = null,
    var params: ModelParams = ModelParams(),
    /** 降级链，也是宿主模型 ID：失败/超时/断网/超预算时依次切换 */
    var fallbackChain: List<String> = emptyList(),
    /** 并发上限。null = 用宿主声明的 maxConcurrency；显式覆盖时取两者较小值 */
    var maxConcurrency: Int? = null,
    /** 单任务 token 上限，超了只熔断这一个角色 */
    var maxTokensPerTask: Long = 300_000L,
    /** 每日 token 上限 */
    var maxTokensPerDay: Long = 2_000_000L,
    /** 该角色能看到哪些上下文（请求隔离的关键维度） */
    var context: ContextStrategy = ContextStrategy(),
    /** 强制指定输出协议，AUTO 时按宿主上报能力自动选 */
    var protocol: OutputProtocol = OutputProtocol.AUTO
) {
    fun displayName(): String = label?.takeIf { it.isNotBlank() } ?: hostModelId
}

/** 上下文裁剪策略 */
@Serializable
data class ContextStrategy(
    var historyWindowTurns: Int = 12,
    /** null=全部黑板；否则只看白名单键。这是防止上下文串味的主要手段 */
    var blackboardKeys: List<String>? = null,
    var includeOtherAgentsUtterances: Boolean = true,
    var maxSemanticHits: Int = 4,
    var includeArtifacts: Boolean = true,
    var maxContextTokens: Int = 32_000
)

/** 宿主上报的模型能力（由 bridge 填充，SDK 只读） */
@Serializable
data class ModelCapabilities(
    val supportsTools: Boolean = false,
    val supportsVision: Boolean = false,
    val supportsJsonMode: Boolean = false,
    val supportsStreaming: Boolean = true,
    val contextWindowTokens: Int = 8_000,
    val maxConcurrency: Int = 1
) {
    /** 自动挑选输出协议：有 function calling 就用，否则退 JSON，再退文本 */
    fun pickProtocol(prefer: OutputProtocol): OutputProtocol = when (prefer) {
        OutputProtocol.AUTO -> when {
            supportsTools -> OutputProtocol.NATIVE_TOOL
            supportsJsonMode -> OutputProtocol.JSON_MODE
            else -> OutputProtocol.TEXT_REACT
        }
        else -> prefer
    }
}
