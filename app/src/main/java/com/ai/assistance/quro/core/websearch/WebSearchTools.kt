package com.ai.assistance.quro.core.websearch

import android.content.Context
import com.ai.assistance.quro.core.QuroChatMessage
import com.ai.assistance.quro.core.QuroLlmResult
import com.ai.assistance.quro.core.model.QuroModelConfigRepository
import com.ai.assistance.quro.core.network.QuroLlmClient
import com.ai.assistance.quro.core.tools.QuroTool
import com.ai.assistance.quro.core.websearch.model.SearchBundle
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/**
 * 把自研端侧联网能力注册为 Agent 可自主调用的工具（宿主 QuroTool 契约）。
 *
 * 设计要点（与「浏览器套壳」本质不同）：
 * 1. 注册成"工具"而不是"功能开关"——模型在行动，自己决定要不要联网、读哪几条；
 * 2. description 把"什么情况下该调用"写清楚，直接决定模型会不会主动用它；
 * 3. 拆成 web_search 与 read_url 两个工具，模型可自行编排：先搜 → 挑有价值的 → 精读 → 作答。
 *
 * 宿主类型说明：原方案依赖 core.mcp.McpTool/ToolRegistry，但本工程工具系统用的是 QuroTool 契约，
 * 因此本文件把联网能力适配到 QuroTool，业务逻辑（Orchestrator / 引擎 / 抽取 / 重排）零改动复用。
 */
class WebSearchTool(private val toolName: String) : QuroTool {

    override val name: String get() = toolName

    override val description: String
        get() = if (toolName == "web_search") {
            """联网搜索实时信息，返回带编号 [n] 的资料片段与可溯源引用。
当问题涉及以下内容时必须调用：当前时间 / 天气 / 股价 / 价格、最新新闻与公告、软件版本与 API 变更、你训练数据截止之后发生的事件、需要核实的事实。
不要用于：常识、数学计算、代码语法、纯观点讨论。
参数：{"query":"用户的原始问题（自然语言，内部会自动改写为搜索词）","recency":"时效偏好(day/week/month/any，可选)"}"""
        } else {
            """读取指定网页的正文内容，返回清理后的 markdown。
在 web_search 之后需要了解细节时使用，或用户直接给出链接时使用。
参数：{"url":"完整网页地址（需以 http 或 https 开头）"}"""
        }

    override val parametersJson: String
        get() = if (toolName == "web_search") {
            """{
  "type":"object",
  "properties":{
    "query":{"type":"string","description":"用户的原始问题，用自然语言表达即可，内部会自动改写为搜索词"},
    "recency":{"type":"string","description":"时效偏好","enum":["day","week","month","any"]}
  },
  "required":["query"]
}"""
        } else {
            """{
  "type":"object",
  "properties":{
    "url":{"type":"string","description":"完整网页地址，需以 http 或 https 开头"}
  },
  "required":["url"]
}"""
        }

    override fun run(context: Context, arguments: String): String {
        val orchestrator = WebSearchProvider.build(buildCompleter(context))
        return runBlocking {
            try {
                when (toolName) {
                    "web_search" -> {
                        val q = runCatching { JSONObject(arguments) }.getOrNull()
                            ?.optString("query", "")?.trim().orEmpty()
                        if (q.isBlank()) return@runBlocking "错误：query 不能为空"
                        render(orchestrator.search(q))
                    }
                    "read_url" -> {
                        val u = runCatching { JSONObject(arguments) }.getOrNull()
                            ?.optString("url", "")?.trim().orEmpty()
                        if (!u.startsWith("http", ignoreCase = true)) return@runBlocking "错误：url 需以 http(s) 开头"
                        val a = orchestrator.readUrl(u)
                        if (!a.ok) "无法提取该页面正文（可能是动态渲染页或需要登录），标题：${a.title}"
                        else "标题：${a.title}\n来源：${a.sourceDomain}\n\n${a.markdown}"
                    }
                    else -> "未知工具：$toolName"
                }
            } catch (e: Throwable) {
                "联网检索异常：${e.message}"
            }
        }
    }

    /** 用宿主模型做查询改写；API Key 缺失或失败则退化为规则改写（联网能力仍可用）。 */
    private fun buildCompleter(context: Context) = object : LlmCompleter {
        override suspend fun complete(system: String, user: String, maxTokens: Int): String? {
            val cfg = runCatching { QuroModelConfigRepository(context).load() }.getOrNull() ?: return null
            if (cfg.apiKey.isBlank()) return null
            val messages = listOf(
                QuroChatMessage(role = "system", content = system),
                QuroChatMessage(role = "user", content = user),
            )
            return runCatching {
                val res = QuroLlmClient().chat(cfg.baseUrl, cfg.apiKey, cfg.model, messages, 0.2f, maxTokens)
                when (res) {
                    is QuroLlmResult.Text -> res.content
                    is QuroLlmResult.ToolCalls -> res.content ?: ""
                    is QuroLlmResult.Error -> null
                }
            }.getOrNull()
        }
    }

    companion object {
        /** 渲染给模型看的文本：上下文 + 引用清单。 */
        fun render(b: SearchBundle): String {
            if (b.citations.isEmpty()) {
                return "联网检索未获得可用结果。（可能原因：网络不可用、所有检索源被限制、查询过于宽泛）" +
                    "请告知用户当前无法联网核实，并基于已有知识谨慎作答。"
            }
            val sb = StringBuilder()
            sb.append(b.context).append("\n\n")
            sb.append("引用来源：\n")
            for (c in b.citations) {
                sb.append("[").append(c.index).append("] ").append(c.title)
                    .append(" — ").append(c.url).append('\n')
            }
            return sb.toString()
        }

        /** 供 UI 层渲染引用卡片（可点击跳转）。 */
        fun citationsJson(b: SearchBundle): String {
            val arr = JSONArray()
            for (c in b.citations) {
                arr.put(
                    JSONObject().apply {
                        put("index", c.index)
                        put("title", c.title)
                        put("url", c.url)
                        put("domain", c.domain)
                        put("truncated", c.truncated)
                    }
                )
            }
            return arr.toString()
        }
    }
}
