package com.ai.assistance.quro.core.tools

import android.content.Context
import com.ai.assistance.quro.core.mcp.QuroLocalMcpManager
import com.ai.assistance.quro.core.mcp.QuroMcpClientPrefs
import org.json.JSONArray
import org.json.JSONObject

/**
 * 本地 MCP 部署工具集（#Task8）：让 AI 创作并部署 MCP 服务器到本应用内。
 *
 * - mcp_deploy     ：提交一组工具定义，部署为本地 MCP Server（监听 127.0.0.1），
 *                    部署后 mcp_call 可按别名直接调用，界面也会自动列出。
 * - mcp_undeploy   ：注销已部署的本地 MCP。
 * - mcp_list_local ：列出当前已部署的本地 MCP 及其工具。
 *
 * 工具定义格式（toolDefs 为 JSON 数组，每个工具）：
 * {
 *   "name": "get_quote",
 *   "description": "获取一句话名言",
 *   "parameters": {"type":"object","properties":{...},"required":[...]},
 *   "handler_type": "http_get",
 *   "handler_config": {"url": "https://api.xxx.com/quote?lang=${lang}"}
 * }
 * 支持的 handler_type：echo / http_get / time / file_read（详见 QuroLocalMcpDispatcher）。
 */
class McpDeployTool : QuroTool {
    override val name = "mcp_deploy"
    override val description = "部署一个本地 MCP 服务器：提交一组工具定义（JSON 数组），应用在本地启动一个 MCP 端点，" +
            "之后即可用 mcp_call 按别名调用这些工具，MCP 设置界面也会自动列出。参数 " +
            "{\"name\":\"服务器别名\",\"tools\":[...工具定义...]}。" +
            "每个工具定义含 name/description/parameters/handler_type/handler_config。" +
            "handler_type 支持：echo（原样返回参数）、http_get（GET 指定 URL，支持 " + "\${" + "param} 模板）、time（当前时间）、file_read（读文本文件）。"
    override val parametersJson = """{
        "type":"object",
        "properties":{
            "name":{"type":"string","description":"本地 MCP 服务器的别名（唯一标识，mcp_call 用它寻址）"},
            "tools":{"type":"array","description":"工具定义数组，每个含 name/description/parameters/handler_type/handler_config"}
        },
        "required":["name","tools"]
    }"""
    override fun run(context: Context, arguments: String): String {
        val jo = runCatching { JSONObject(arguments) }.getOrElse { return "参数不是合法 JSON" }
        val name = jo.optString("name", "").trim()
        if (name.isEmpty()) return "缺少 name 参数"
        val toolsArr = jo.optJSONArray("tools")
        if (toolsArr == null || toolsArr.length() == 0) return "tools 不能为空"
        // 规整为带默认 handler_type 的数组
        val normalized = JSONArray()
        for (i in 0 until toolsArr.length()) {
            val t = toolsArr.optJSONObject(i) ?: continue
            if (t.optString("name", "").isEmpty()) return "第 ${i + 1} 个工具缺少 name"
            if (t.optString("handler_type", "").isEmpty()) t.put("handler_type", "echo")
            if (!t.has("parameters")) t.put("parameters", JSONObject())
            normalized.put(t)
        }
        return QuroLocalMcpManager.deploy(context, name, normalized.toString())
    }
}

/** 注销本地 MCP 服务器。 */
class McpUndeployTool : QuroTool {
    override val name = "mcp_undeploy"
    override val description = "注销一个已部署的本地 MCP 服务器（停止其本地端点并删除配置）。参数 {\"name\":\"别名\"}。"
    override val parametersJson = """{
        "type":"object",
        "properties":{"name":{"type":"string","description":"要注销的本地 MCP 服务器别名"}},
        "required":["name"]
    }"""
    override fun run(context: Context, arguments: String): String {
        val jo = runCatching { JSONObject(arguments) }.getOrElse { return "参数不是合法 JSON" }
        val name = jo.optString("name", "").trim()
        if (name.isEmpty()) return "缺少 name 参数"
        if (QuroMcpClientPrefs.loadLocal(context).none { it.alias == name }) {
            return "未找到本地 MCP: $name（先用 mcp_list_local 查看已部署的本地 MCP）"
        }
        return QuroLocalMcpManager.undeploy(context, name)
    }
}

/** 列出已部署的本地 MCP 服务器。 */
class McpListLocalTool : QuroTool {
    override val name = "mcp_list_local"
    override val description = "列出当前已部署的本地 MCP 服务器（AI 通过 mcp_deploy 创作并部署的），含别名、工具数与连接地址。"
    override val parametersJson = """{"type":"object","properties":{}}"""
    override fun run(context: Context, arguments: String): String {
        val locals = QuroMcpClientPrefs.loadLocal(context)
        if (locals.isEmpty()) {
            return "尚未部署任何本地 MCP。使用 mcp_deploy 提交工具定义即可部署一个本地 MCP 服务器。"
        }
        return locals.joinToString("\n\n") { cfg ->
            val n = runCatching { JSONArray(cfg.toolDefs).length() }.getOrDefault(0)
            "▸ ${cfg.alias}\n  地址: ${cfg.url}\n  工具数: $n"
        }
    }
}
