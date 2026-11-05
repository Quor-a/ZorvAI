package com.ai.assistance.quro.core.websearch.net

import com.ai.assistance.quro.core.websearch.model.SearchHit
import org.json.JSONArray
import org.json.JSONObject

/**
 * BotHubEngine —— BotHub 网关（OpenAI 兼容）驱动的 LLM 搜索引擎。
 *
 * BotHub（openai.bothub.chat/v1）是 OpenAI 兼容聚合网关，
 * 配置 API Key 后可调用其带联网检索能力的对话模型（gpt-4o-search-preview 系列）——
 * 模型自行搜索并返回带来源的答案。本引擎把答案按 URL 拆成 [SearchHit] 条目，
 * 与其余引擎的命中合并进统一召回管线。
 *
 * 凭据由 [WebSearchKeys] 持有（仅存本机）；未配置 key 时 [search] 返回空（自动跳过），
 * 与 BraveEngine / SearxngEngine 的跳过语义一致。
 */
class BotHubEngine(
    override val enabled: Boolean = true,
    /** 搜索模型：BotHub 网关上带内置联网检索的对话模型。 */
    private val model: String = "gpt-4o-search-preview",
) : SearchEngine {

    override val id = "bothub"

    override suspend fun search(query: String, limit: Int): List<SearchHit> {
        val key = WebSearchKeys.bothubKey
        if (key.isBlank()) return emptyList()

        val body = JSONObject().apply {
            put("model", model)
            put(
                "messages",
                JSONArray().put(
                    JSONObject().put("role", "user")
                        .put("content", "请联网检索并回答：「$query」。只输出 Markdown 列表，每行一条来源，格式：`- [标题](URL)：一句话要点`，最多 $limit 条。不要输出其它解释。")
                ),
            )
            put("temperature", 0.2)
        }
        val resp = HttpStack.postJson(
            url = "https://openai.bothub.chat/v1/chat/completions",
            jsonBody = body.toString(),
            headers = mapOf(
                "Authorization" to "Bearer $key",
                "Content-Type" to "application/json",
            ),
            timeoutMs = 20_000,
        ) ?: return emptyList()

        return runCatching {
            val content = JSONObject(resp)
                .optJSONArray("choices")?.optJSONObject(0)
                ?.optJSONObject("message")?.optString("content").orEmpty()
            parseMarkdownSources(content, limit)
        }.getOrDefault(emptyList())
    }

    /** 把 `- [标题](URL)：要点` 形式的 Markdown 列表拆成 SearchHit。 */
    private fun parseMarkdownSources(md: String, limit: Int): List<SearchHit> {
        val out = ArrayList<SearchHit>()
        val regex = Regex("""\[(.+?)]\((https?://[^)\s]+)\)\s*[:：]?\s*(.*)""")
        md.lineSequence().forEach { line ->
            if (out.size >= limit) return out
            val m = regex.find(line.trim()) ?: return@forEach
            val (title, url, snippet) = m.destructured
            if (title.isBlank() || url.isBlank()) return@forEach
            out.add(
                SearchHit(
                    title = title.trim(),
                    url = url.trim(),
                    snippet = snippet.trim(),
                    engine = id,
                    position = out.size + 1,
                ),
            )
        }
        // 兜底：正文里没有任何 Markdown 链接时，整段答案作为单条结果（仍可被引用溯源）
        if (out.isEmpty() && md.isNotBlank()) {
            out.add(
                SearchHit(
                    title = "BotHub 搜索",
                    url = "https://bothub.chat",
                    snippet = md.take(600),
                    engine = id,
                    position = 1,
                ),
            )
        }
        return out
    }
}
