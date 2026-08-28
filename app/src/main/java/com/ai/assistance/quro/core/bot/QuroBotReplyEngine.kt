package com.ai.assistance.quro.core.bot

import android.content.Context
import com.ai.assistance.quro.core.QuroAssistant
import com.ai.assistance.quro.core.QuroConversationStore
import com.ai.assistance.quro.core.QuroMessage
import com.ai.assistance.quro.core.model.QuroFunctionModelConfigRepository
import com.ai.assistance.quro.core.model.QuroFunctionType
import com.ai.assistance.quro.core.model.QuroModelConfigRepository
import com.ai.assistance.quro.core.network.QuroLlmClient
import com.ai.assistance.quro.core.tools.buildQuroRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * 机器人回复引擎（复用既有对话内核，不依赖 UI）。
 *
 * 每个「平台:用户」维护一条独立会话（[QuroConversationStore] + 一个 [QuroAssistant]），
 * 支持多轮上下文。复用 [buildQuroRegistry] 拿到与 App 完全一致的真实工具集
 * （含知识库工具），因此机器人也能调用设备能力与 RAG 知识库。
 *
 * 接入点：core/QuroAssistant.kt:27（构造器）、core/tools/QuroBuiltInTools.kt:149（buildQuroRegistry）、
 *        core/model/QuroModelConfig.kt:28（模型配置，含 apiKey/baseUrl）。
 */

/**
 * 机器人一次回复：以文本为主，可附带一张要发送的图片（字节 + 文件名）。
 * imageBytes 非空时，支持图片的平台（飞书）优先发图，文字作为附言补发。
 */
@Suppress("ArrayInDataClass")
data class QuroReply(
    val text: String,
    val imageBytes: ByteArray? = null,
    val imageFileName: String? = null,
)

class QuroBotReplyEngine(private val appContext: Context) {
    private val registry = buildQuroRegistry(appContext.applicationContext)
    private val client = QuroLlmClient()
    /** key = "${platform}:${userId}" → 该用户的会话内核。 */
    private val sessions = ConcurrentHashMap<String, QuroAssistant>()
    private val stores = ConcurrentHashMap<String, QuroConversationStore>()

    /** 机器人系统提示词（精简身份 + 平台语境；工具由 QuroAssistant 按 registry 下发）。 */
    private fun systemPrompt(platform: QuroBotPlatform, displayName: String): String = buildString {
        append("你是**运行在 Zorv AI 这个端侧运行环境里的 AI 助手**，正在通过「${platform.label}」与用户「$displayName」对话。\n")
        append("用简洁、自然的中文回答；遇到需要查资料或调用能力时直接做，不要复述工具名。\n")
        append("这是一个聊天机器人场景，不要主动操控用户设备做危险动作，除非用户明确要求。\n")
        append("知识库检索可用 knowledge_rag_search（语义检索）与 knowledge_search（关键词）。\n")
        append("不要自称「AI 语言模型 / 大语言模型 / 聊天机器人」；Zorv AI 是你运行的端侧环境，不是你的名字。")
    }

    /** 取得/创建某用户的会话内核。 */
    private fun sessionOf(platform: QuroBotPlatform, userId: String): QuroAssistant {
        val key = "${platform.name}:$userId"
        var assistant = sessions[key]
        if (assistant == null) {
            val store = QuroConversationStore()
            stores[key] = store
            assistant = QuroAssistant(client, registry, store)
            sessions[key] = assistant
        }
        return assistant
    }

    /**
     * 给定一条用户消息，返回 AI 回复文本。
     * 异常向上抛，由 [QuroBotManager.handleInbound] 兜底成友好报错。
     */
    suspend fun reply(platform: QuroBotPlatform, userId: String, text: String): QuroReply =
        withContext(Dispatchers.IO) {
            val t0 = System.currentTimeMillis()
            val baseCfg = QuroModelConfigRepository(appContext).load()
            if (baseCfg.apiKey.isBlank()) {
                return@withContext QuroReply("（未配置模型 API Key，机器人无法回复；请到「模型设置」填写 baseUrl / apiKey / model。）")
            }
            // 机器人回复属于 CHAT 调用：接入「功能模型配置」的 CHAT 独立模型绑定，让开关真正生效
            val cfg = QuroFunctionModelConfigRepository(appContext).resolveConfig(QuroFunctionType.CHAT, baseCfg)
            val assistant = sessionOf(platform, userId)
            val store = stores["${platform.name}:$userId"] ?: QuroConversationStore().also {
                stores["${platform.name}:$userId"] = it
            }
            store.add(QuroMessage(role = "user", content = text))
            Log.i("BotReply", "开始回复 platform=${platform.name} user=$userId model=${cfg.model} text=${text.take(50)}...")
            val result = assistant.ask(
                context = appContext,
                cfg = cfg,
                systemPrompt = systemPrompt(platform, userId),
                autoSaveMemory = false,
            )
            val dur = System.currentTimeMillis() - t0
            Log.i("BotReply", "回复完成 platform=${platform.name} user=$userId dur=${dur}ms replyLen=${result.length}")
            QuroReply(result)
        }

    /** 清空某用户的多轮上下文（切换/重置时用）。 */
    fun reset(platform: QuroBotPlatform, userId: String) {
        stores["${platform.name}:$userId"]?.clear()
    }
}
