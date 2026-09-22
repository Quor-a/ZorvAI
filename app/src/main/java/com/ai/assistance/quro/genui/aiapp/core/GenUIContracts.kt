package com.ai.assistance.quro.genui.aiapp.core

/**
 * 聊天消息数据模型
 *
 * @property role 消息角色 (system/user/assistant/tool)
 * @property content 消息文本内容
 * @property toolCalls 工具调用列表（assistant消息）
 * @property toolCallId 工具调用ID（tool消息）
 * @property attachments 附件列表
 * @property toolName 工具名称（tool消息）
 * @property reasoning 推理过程内容
 */
data class GenUIChatMessage(
    val role: String,
    val content: String,
    val toolCalls: List<GenUIToolCall>? = null,
    val toolCallId: String? = null,
    val attachments: List<GenUIAttachment>? = null,
    val toolName: String? = null,
    val reasoning: String? = null
)

/**
 * LLM 调用结果密封类
 */
sealed interface GenUILlmResult {
    /**
     * 文本结果
     *
     * @property content 文本内容
     * @property reasoning 推理过程
     */
    data class Text(
        val content: String,
        val reasoning: String? = null
    ) : GenUILlmResult

    /**
     * 工具调用结果
     *
     * @property calls 工具调用列表
     * @property reasoning 推理过程
     * @property content 文本内容
     */
    data class ToolCalls(
        val calls: List<GenUIToolCall>,
        val reasoning: String? = null,
        val content: String? = null
    ) : GenUILlmResult

    /**
     * 错误结果
     *
     * @property message 错误信息
     */
    data class Error(
        val message: String
    ) : GenUILlmResult
}

/**
 * 工具调用数据模型
 *
 * @property id 工具调用ID
 * @property name 工具名称
 * @property arguments 工具参数（JSON字符串）
 * @property result 工具执行结果
 * @property durationMs 执行耗时（毫秒）
 */
data class GenUIToolCall(
    val id: String = "",
    val name: String,
    val arguments: String,
    val result: String? = null,
    val durationMs: Long = 0L
)

/**
 * 工具执行结果
 *
 * @property name 结果名称（success/error）
 * @property result 结果内容
 */
data class GenUIToolResult(
    val name: String,
    val result: String
) {
    companion object {
        /** 创建成功结果 */
        fun Success(result: String): GenUIToolResult = GenUIToolResult("success", result)

        /** 创建错误结果 */
        fun Error(result: String): GenUIToolResult = GenUIToolResult("error", result)
    }
}

/**
 * 工具规格定义
 *
 * @property name 工具名称
 * @property description 工具描述
 * @property parametersJson 参数JSON Schema（字符串形式）
 */
data class GenUIToolSpec(
    val name: String,
    val description: String,
    val parametersJson: String
)

/**
 * 附件数据模型
 *
 * @property type 附件类型（image/file等）
 * @property uri 附件URI
 * @property name 附件名称
 * @property size 附件大小（字节）
 * @property mime MIME类型
 */
data class GenUIAttachment(
    val type: String = "image",
    val uri: String = "",
    val name: String = "",
    val size: Long = 0L,
    val mime: String = ""
)

/**
 * 附件工具类
 * 提供附件相关的辅助方法
 */
object GenUIAttachmentKit {
    /**
     * 将附件URI转换为视觉模型可用的data URI
     *
     * @param uri 附件URI
     * @return base64编码的data URI，失败返回null
     */
    fun toVisionDataUri(uri: String): String? {
        // TODO: 实现从content URI读取并编码为base64 data URI
        return null
    }
}
