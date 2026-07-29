package com.ai.assistance.quro.core.mcp

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * MCP 服务器配置持久化（SharedPreferences）。
 * - `remote`：用户在「设置 → MCP 服务 → MCP 客户端」中添加的外部服务器。
 * - `local` ：AI 通过 `mcp_deploy` 创作并部署到本应用内的 MCP 服务器（启动后监听 127.0.0.1）。
 * 两类服务器都供 mcp_call 等工具按别名解析。
 */
object QuroMcpClientPrefs {
    private const val PREFS = "quro_mcp_client"
    private const val KEY = "servers"

    fun load(context: Context): List<QuroMcpClient.McpServerConfig> {
        val out = mutableListOf<QuroMcpClient.McpServerConfig>()
        runCatching {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val arr = JSONArray(prefs.getString(KEY, "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out.add(
                    QuroMcpClient.McpServerConfig(
                        alias = o.optString("alias", ""),
                        url = o.optString("url", ""),
                        token = o.optString("token", ""),
                        kind = o.optString("kind", "remote"),
                        toolDefs = o.optString("toolDefs", ""),
                    )
                )
            }
        }
        return out
    }

    fun save(context: Context, list: List<QuroMcpClient.McpServerConfig>) {
        runCatching {
            val arr = JSONArray()
            list.forEach {
                arr.put(
                    JSONObject().apply {
                        put("alias", it.alias)
                        put("url", it.url)
                        put("token", it.token)
                        put("kind", it.kind)
                        put("toolDefs", it.toolDefs)
                    }
                )
            }
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY, arr.toString()).apply()
        }
    }

    /** 仅返回 AI 部署的本地 MCP 服务器（kind == "local"）。 */
    fun loadLocal(context: Context): List<QuroMcpClient.McpServerConfig> =
        load(context).filter { it.kind == "local" }

    fun add(context: Context, cfg: QuroMcpClient.McpServerConfig) {
        val list = load(context).toMutableList()
        list.removeIf { it.alias == cfg.alias }
        list.add(cfg)
        save(context, list)
    }

    fun remove(context: Context, alias: String) {
        val list = load(context).toMutableList()
        list.removeIf { it.alias == alias }
        save(context, list)
    }

    /** 按别名精确匹配，失败再按 url 匹配。 */
    fun find(context: Context, ref: String): QuroMcpClient.McpServerConfig? {
        val list = load(context)
        return list.firstOrNull { it.alias == ref } ?: list.firstOrNull { it.url == ref }
    }
}
