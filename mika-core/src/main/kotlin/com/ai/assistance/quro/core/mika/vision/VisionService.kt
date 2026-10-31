package com.ai.assistance.quro.core.mika.vision

import com.ai.assistance.quro.core.mika.model.Author
import com.ai.assistance.quro.core.mika.model.ChatModel
import com.ai.assistance.quro.core.mika.model.Message
import com.ai.assistance.quro.core.mika.model.ModelError
import com.ai.assistance.quro.core.mika.model.Part
import com.ai.assistance.quro.core.mika.model.Role

/**
 * 让视觉模型看图回答一个问题。只在设置里有能看图的模型时才建;
 * 调用方负责不把受保护页面、付款页的画面传进来。
 */
class VisionService(private val model: ChatModel, val ops: ImageOps) {
    /** 这个模型的看图定位准度验证过;没验证时 find_visually 对只靠看图框出来的点要求另有佐证。 */
    val groundingVerified: Boolean get() = model.capabilities.groundingVerified

    /** 不带工具的一次调用;失败抛 [ModelError]。 */
    suspend fun ask(system: String, text: String, images: List<ByteArray>): String {
        val parts = listOf(Part.Text(text)) + images.map { ModelImage.part(it, ops) }
        val reply = model.complete(system, listOf(Message(Role.User, parts, Author.ZorvAI)), emptyList())
        val answer = reply.parts.filterIsInstance<Part.Text>().joinToString("") { it.text }
        if (answer.isBlank()) throw ModelError.BadResponse("视觉模型没有给出回答")
        return answer
    }
}
