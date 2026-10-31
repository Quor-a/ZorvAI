package com.ai.assistance.quro.core.mika.agent

import com.ai.assistance.quro.core.mika.model.Author
import com.ai.assistance.quro.core.mika.model.ChatModel
import com.ai.assistance.quro.core.mika.model.Message
import com.ai.assistance.quro.core.mika.model.Part
import com.ai.assistance.quro.core.mika.model.Role
import com.ai.assistance.quro.core.mika.prompt.Prompts
import kotlinx.coroutines.CancellationException

/** 只在准备调用本轮第一个工具时，独立判断用户是在讨论，还是要读取或操作。 */
internal class IntentGate(private val judge: ChatModel, private val fallback: ChatModel? = null) {
    enum class Intent { Discuss, Read, Act }

    suspend fun assess(task: String, priorTurns: List<Turn>): Intent? {
        val prior = Conversation.context(priorTurns, maxTurns = 3, maxChars = 1600)
            ?.parts?.filterIsInstance<Part.Text>()?.joinToString("\n") { it.text }.orEmpty()
        val input = buildString {
            if (prior.isNotEmpty()) appendLine(prior).appendLine()
            append("The user's current message:\n").append(task)
        }
        val messages = listOf(Message(Role.User, listOf(Part.Text(input)), Author.User))
        for (candidate in listOfNotNull(judge, fallback)) {
            val result = try {
                val reply = candidate.complete(Prompts.role("intent_gate"), messages, emptyList())
                parse(reply.parts.filterIsInstance<Part.Text>().joinToString("") { it.text })
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (result != null) return result
        }
        return null
    }

    companion object {
        fun parse(raw: String): Intent? = when (raw.trim().removePrefix("`").removeSuffix("`").trim().uppercase()) {
            "DISCUSS" -> Intent.Discuss
            "READ" -> Intent.Read
            "ACT" -> Intent.Act
            else -> null
        }
    }
}
