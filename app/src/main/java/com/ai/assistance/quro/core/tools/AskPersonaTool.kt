package com.ai.assistance.quro.core.tools

import android.content.Context
import com.ai.assistance.quro.core.QuroPersonaRepository
import com.ai.assistance.quro.core.QuroPlatformManifest
import com.ai.assistance.quro.core.QuroReplyLanguage
import com.ai.assistance.quro.core.model.QuroModelConfigRepository
import com.ai.assistance.quro.core.QuroChatMessage
import com.ai.assistance.quro.core.QuroLlmResult
import com.ai.assistance.quro.core.network.QuroLlmClient
import com.ai.assistance.quro.core.soul.QuroSoulPromptEngine
import com.ai.assistance.quro.core.soul.SoulContext

/**
 * 🔴 `ask_persona` 工具（2026-11-04）：Bot 目录互问。
 * 当前 AI 可以**向本机的其它人格卡提问**——把目标人格卡（身份/roleSetting/Soul）
 * 组装成独立系统提示词，跑一次单轮 LLM 拿回回答。
 *
 * 本仓为**同步单轮**（同一台设备、同一份工具内核），更简单直接。Bot 目录注入见
 * [QuroPersonaRepository.personaDirectorySection]（其它人格的 name+description）。
 */
class AskPersonaTool(private val excludePersonaId: String = "") : QuroTool {
    override val name = "ask_persona"

    override val description =
        "ask_persona：向本机的其它人格卡提问并等它的回答（Bot 互问）。\n" +
            "参数 persona 取「人格目录」段里其它人格的 id；question 是你要问它的一条完整问题。\n" +
            "它有自己的身份与专长，回答不代表你——把它的回答当作参考信息整合进你的答复，并注明是谁说的。"

    override val parametersJson = """{
        "type": "object",
        "properties": {
            "persona": {"type": "string", "description": "目标人格卡 id（见系统提示词「人格目录」段）"},
            "question": {"type": "string", "description": "要问它的一条完整问题（把上下文说清楚，它看不到你们的对话）"}
        },
        "required": ["persona", "question"]
    }"""

    override fun run(context: Context, arguments: String): String {
        val o = runCatching { org.json.JSONObject(arguments) }.getOrNull()
            ?: return "❌ 参数不是合法 JSON"
        val targetId = o.optString("persona", "").trim()
        val question = o.optString("question", "").trim()
        if (targetId.isBlank() || question.isBlank()) return "❌ persona 与 question 都必填。"

        val repo = QuroPersonaRepository(context.applicationContext)
        val target = repo.loadAll().firstOrNull { it.id == targetId }
            ?: return "❌ 没有找到人格卡「$targetId」。请对照系统提示词「人格目录」段的 id 重试。"

        // 目标人格的完整灵魂层提示词（身份/roleSetting/Soul/聊天设定/标签）
        val soul = QuroSoulPromptEngine.build(
            SoulContext(
                persona = target,
                tags = runCatching { com.ai.assistance.quro.core.QuroTagRepository(context.applicationContext).resolve(target.tags) }.getOrDefault(emptyList()),
                memories = emptyList(),
                autoSaveMemory = false,
            ),
        )
        val system = buildString {
            append(com.ai.assistance.quro.core.QuroPlatformManifest.SYSTEM).append("\n\n")
            if (soul.isNotBlank()) append(soul).append("\n\n")
            append("## 场景\n另一个 AI 助手（同为 ZorvAI 内的人格卡）向你提问。")
            append("直接以你的身份回答它的 question，简洁、只输出回答正文，不要寒暄。")
        }
        val langTail = QuroReplyLanguage.tailReminder(context.applicationContext)

        val cfg = QuroModelConfigRepository(context.applicationContext).load()
        if (cfg.apiKey.isBlank()) return "❌ 未配置模型 API Key，无法向其它人格提问。"

        val res = kotlinx.coroutines.runBlocking {
            QuroLlmClient().chat(
                cfg.baseUrl, cfg.apiKey, cfg.model,
                listOf(QuroChatMessage("system", system + langTail), QuroChatMessage("user", question)),
                cfg.temperature, 1200, emptyList(),
            )
        }
        return when (res) {
            is QuroLlmResult.Text -> "【${target.name} 的回答】\n${res.content}"
            is QuroLlmResult.Error -> "❌ ${target.name} 没能回答：${res.message}"
            is QuroLlmResult.ToolCalls -> "❌ ${target.name} 的回答通道不支持工具调用。"
        }
    }
}
