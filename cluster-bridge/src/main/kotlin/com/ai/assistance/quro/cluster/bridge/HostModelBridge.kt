package com.ai.assistance.quro.cluster.bridge

import kotlinx.coroutines.flow.Flow

enum class HostRole { SYSTEM, USER, ASSISTANT, TOOL }

data class HostToolCall(val id: String, val name: String, val argsJson: String)

data class HostMessage(
    val role: HostRole,
    val content: String,
    /** tool 角色时填工具名；assistant 带 toolCalls 时可不填 content */
    val name: String? = null,
    val toolCallId: String? = null,
    val toolCalls: List<HostToolCall> = emptyList()
)

data class HostToolSchema(
    val name: String,
    val description: String,
    /** JSON Schema 字符串（不是 JsonElement，避免与宿主的序列化库耦合） */
    val parametersJson: String
)

data class HostUsage(val promptTokens: Int = 0, val completionTokens: Int = 0) {
    val total: Int get() = promptTokens + completionTokens
}

/**
 * SDK 向宿主发起的一次对话请求。
 * 宿主负责把它翻译成自己那套协议（OpenAI 兼容 / 自定义 HTTP / 本地推理）。
 */
data class HostChatRequest(
    val modelId: String,
    val messages: List<HostMessage>,
    val temperature: Double? = null,
    val topP: Double? = null,
    val maxOutputTokens: Int? = null,
    val stop: List<String> = emptyList(),
    /** 需要模型原生 function calling 时才非空；宿主若不支持可忽略，SDK 会自动降级 */
    val tools: List<HostToolSchema> = emptyList(),
    val jsonMode: Boolean = false,
    /** 透传给宿主的追踪 ID，便于宿主侧日志串联 */
    val traceId: String? = null
)

sealed interface HostChatChunk {
    data class Delta(val text: String) : HostChatChunk
    data class ToolCall(val call: HostToolCall) : HostChatChunk
    data class Usage(val usage: HostUsage) : HostChatChunk
    data class Done(val finishReason: String?) : HostChatChunk
    /** retriable=true 时 SDK 会走降级链重试；false 表示参数错误之类，重试无意义 */
    data class Error(val message: String, val retriable: Boolean) : HostChatChunk
}

/**
 * ★ 宿主需要实现的核心接口 ★
 *
 * 三个方法，把宿主已有的模型能力暴露给集群：
 *  - listModels()：报出配置界面里有哪些模型
 *  - chat()：发一次流式对话
 *  - countTokens()：算 token（用于上下文裁剪与预算记账；估不准可按 字符数/4 兜底）
 *
 * 实现要求：
 *  1. chat() 必须是**冷的**：被调用时才发请求，不得在构造时预热连接；
 *  2. 流结束后必须发一个 Done，出错必须发 Error，否则 SDK 会一直挂到超时；
 *  3. 同一个 modelId 的并发由 SDK 侧闸门控制，宿主内部即使线程安全也建议老实排队；
 *  4. 不需要做重试 —— 重试与降级链在 SDK 侧统一处理，避免两层重试放大流量。
 */
interface HostModelBridge {

    /** 列出宿主当前可用的模型配置。SDK 会缓存，宿主配置变更时调 onModelsChanged() 通知。 */
    suspend fun listModels(): List<HostModelInfo>

    /** 发起流式对话。返回的 Flow 在调用者的协程上下文中收集。 */
    fun chat(request: HostChatRequest): Flow<HostChatChunk>

    /** token 计数，用于上下文裁剪与预算。 */
    suspend fun countTokens(modelId: String, text: String): Int = text.length / 4

    /** 可选：向量化。返回 null 表示不支持，SDK 自动退化为关键词检索。 */
    suspend fun embed(texts: List<String>): List<FloatArray>? = null

    /** SDK 订阅模型变更通知（宿主实现保存此回调，配置改动后调用它） */
    fun setModelChangeListener(listener: HostModelChangeListener?) {}

    /** 由宿主主动调用：模型配置变了 */
    fun notifyModelsChanged()
}

/**
 * 宿主实现的推荐基类：帮你保管变更回调。
 * 宿主在「模型配置界面」保存/删除/启用禁用模型后，调一次 notifyModelsChanged()，
 * 集群里的模型下拉与降级链就会立刻同步，不需要重启。
 */
abstract class BaseHostModelBridge : HostModelBridge {
    @Volatile private var listener: HostModelChangeListener? = null

    override fun setModelChangeListener(listener: HostModelChangeListener?) {
        this.listener = listener
    }

    override fun notifyModelsChanged() {
        listener?.onChanged()
    }
}
