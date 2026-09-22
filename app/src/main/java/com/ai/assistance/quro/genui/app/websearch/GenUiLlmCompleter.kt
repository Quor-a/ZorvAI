package com.ai.assistance.quro.genui.app.websearch

import com.ai.assistance.quro.genui.app.llm.FastClient
import com.ai.assistance.quro.genui.app.llm.LLMClient
import com.ai.assistance.quro.genui.app.store.GenStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * [LlmCompleter] 的 GenUI 实现 —— 把查询改写接到「专项模型分派」的快速模型通道。
 *
 * 设计与 [com.ai.assistance.quro.genui.app.llm.FastClient] 一致：
 * - 优先用 fastProviderId；未配置时回退主脑，再不行返回 null；
 * - 返回 null 时 Orchestrator 自动退化为规则式改写（联网能力永不因模型故障而整体失效）；
 * - 查询改写属于"快、短、便宜"任务，与 FastClient.suggestPrompts / refineTitle / preflight 同通道。
 *
 * 注意：LLMClient.chatOnce 本身已切 IO 线程，这里不做多余包装；任何异常一律吞掉返回 null，
 * 因为查询改写是增强项而不是必经路径 —— 失败只会让搜索词略差，不应该让搜索整体失败。
 */
class GenUiLlmCompleter(private val store: GenStore) : LlmCompleter {

    override suspend fun complete(system: String, user: String, maxTokens: Int): String? {
        val provider = runCatching { FastClient(store).provider() }.getOrNull() ?: return null
        return runCatching {
            val msg = LLMClient().chatOnce(
                provider,
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", system))
                    .put(JSONObject().put("role", "user").put("content", user)),
                null
            )
            msg.optString("content").trim().ifBlank { null }
        }.getOrNull()
    }
}
