package com.ai.assistance.quro.core.mika.model

import com.ai.assistance.quro.core.mika.observation.ReadTag
import kotlinx.serialization.Serializable

@Serializable
sealed interface Part {
    @Serializable
    data class Text(val text: String) : Part
    /**
     * [width]/[height]:编码后的像素尺寸,0 为不知道(只用于估算 token)。
     * [screenshotId]:这张图是哪次读屏的画面(可用 view_past_screens 回看);不是屏幕画面(读到的图片文件)为 null。
     */
    @Serializable data class Image(
        val pngBase64: String,
        val mime: String = "image/png",
        val width: Int = 0,
        val height: Int = 0,
        val screenshotId: Int? = null,
    ) : Part
    /**
     * [opaque]: adapter-private data that the SAME adapter must send back unchanged when this call is
     * replayed; other adapters ignore it.
     */
    @Serializable data class ToolCall(val id: String, val name: String, val argsJson: String, val opaque: String? = null) : Part
    /**
     * [reads]:这次结果里读到、记进证据账本的观察(编号与形态);只在 ZorvAI 内部用(裁剪按观察的形态定怎么缩短,缩短后指回编号),不发给服务商。
     */
    @Serializable data class ToolResult(val callId: String, val content: String, val isError: Boolean, val reads: List<ReadTag> = emptyList()) : Part
    /**
     * [opaque]:和 [ToolCall.opaque] 一样是适配器私有的,记下这段思考从哪来(例如兼容接口里收到它的地址和字段),
     * 回放时只有认得它的那个适配器用;别的适配器不看。旧的历史和不需要回放的为 null。
     */
    @Serializable data class Thinking(val text: String, val opaque: String? = null) : Part
}

@Serializable
enum class Role { System, User, Assistant, Tool }

/** 消息是谁写的:[User] 是用户亲口说的(任务原话、任务中途补充的话与照片),[ZorvAI] 是 ZorvAI 生成的(附注、上下文、工具往返)。 */
@Serializable
enum class Author { User, ZorvAI }

/**
 * [author] 必须写明(没有默认值):新加的地方要是放进了用户的话或照片,不会被当成 ZorvAI 的附注裁掉。
 * 只在 ZorvAI 内部用(压缩时保住用户的话),不发给服务商。
 */
@Serializable
data class Message(val role: Role, val parts: List<Part>, val author: Author)

data class ToolSpec(val name: String, val description: String, val parametersJsonSchema: String)

enum class StopReason { EndTurn, ToolUse, MaxTokens, Other }

/** [inputTokens] 是总输入 token(含缓存命中部分,OpenAI prompt_tokens 口径)。 */
data class Usage(val inputTokens: Int, val outputTokens: Int, val cachedInputTokens: Int = 0)

data class ModelReply(val parts: List<Part>, val stopReason: StopReason, val usage: Usage?)
