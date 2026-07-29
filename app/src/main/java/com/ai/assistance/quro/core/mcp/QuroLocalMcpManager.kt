package com.ai.assistance.quro.core.mcp

import android.content.Context
import android.util.Log

/**
 * 本地 MCP 部署管理器。
 *
 * 职责：
 * 1. `deploy`   —— AI 通过 `mcp_deploy` 提交工具定义后，落地持久化并启动一个本地 MCP
 *                  HTTP Server（监听 127.0.0.1），其 url 写为 `http://127.0.0.1:<port>/mcp`，
 *                  从而被现有 [QuroMcpClient]（mcp_call）按别名直接发现与调用。
 * 2. `undeploy` —— 停止 server 并删除持久化配置。
 * 3. `startAll` —— 应用启动时自动拉起所有已部署的本地 MCP，实现「界面自动拉取并注册」。
 *
 * 注意：本地 server 仅在应用进程存活期间运行（守护线程）。应用退出后下次启动由 startAll 重建。
 * 这与「本地 MCP」的定位一致——是 AI 在会话内创作的、随应用运行的能力扩展。
 */
object QuroLocalMcpManager {
    private const val TAG = "QuroLocalMcpManager"
    private val servers = mutableMapOf<String, QuroLocalMcpServer>() // alias -> server

    /** 部署（或更新）一个本地 MCP 服务器。返回启动后的连接端点，失败返回错误信息。 */
    @Synchronized
    fun deploy(context: Context, alias: String, toolDefs: String): String {
        val a = alias.trim()
        if (a.isEmpty()) return "别名不能为空"
        // 校验工具定义是否为合法 JSON 数组
        val validated = runCatching {
            val arr = org.json.JSONArray(toolDefs)
            val list = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
            if (list.isEmpty()) return "工具定义不能为空（至少包含一个工具）"
            list.forEach { if (it.optString("name", "").isEmpty()) throw IllegalArgumentException("存在缺少 name 的工具") }
            arr.toString()
        }.getOrElse { return "工具定义不是合法 JSON 数组: ${it.message}" }

        // 若已存在同名 server，先停掉
        servers[a]?.stop()
        servers.remove(a)

        val server = QuroLocalMcpServer(validated)
        val port = runCatching { server.start() }.getOrElse { return "本地 MCP 启动失败: ${it.message}" }
        servers[a] = server

        // 持久化为 local 配置，url 指向本机端点（mcp_call 可直接用）
        val cfg = QuroMcpClient.McpServerConfig(
            alias = a,
            url = "http://127.0.0.1:$port/mcp",
            kind = "local",
            toolDefs = validated,
        )
        val list = QuroMcpClientPrefs.load(context).toMutableList()
        list.removeIf { it.alias == a }
        list.add(cfg)
        QuroMcpClientPrefs.save(context, list)
        Log.i(TAG, "已部署本地 MCP: $a @ $port (${server.toolCount} 个工具)")
        return "已部署本地 MCP「$a」，监听 $port，共 ${server.toolCount} 个工具。连接地址: http://127.0.0.1:$port/mcp"
    }

    /** 注销本地 MCP 服务器（停 server + 删配置）。 */
    @Synchronized
    fun undeploy(context: Context, alias: String): String {
        val a = alias.trim()
        servers[a]?.stop()
        servers.remove(a)
        val list = QuroMcpClientPrefs.load(context).toMutableList()
        list.removeIf { it.alias == a && it.kind == "local" }
        QuroMcpClientPrefs.save(context, list)
        return "已注销本地 MCP「$a」"
    }

    /** 应用启动时调用：自动拉起所有已持久化的本地 MCP，使其恢复可用（界面自动拉取注册）。 */
    @Synchronized
    fun startAll(context: Context) {
        val locals = QuroMcpClientPrefs.loadLocal(context)
        locals.forEach { cfg ->
            if (cfg.toolDefs.isBlank()) return@forEach
            if (servers[cfg.alias]?.running == true) return@forEach
            runCatching {
                val s = QuroLocalMcpServer(cfg.toolDefs)
                val p = s.start()
                servers[cfg.alias] = s
                // 刷新端口（每次随机分配）
                val list = QuroMcpClientPrefs.load(context).toMutableList()
                val idx = list.indexOfFirst { it.alias == cfg.alias && it.kind == "local" }
                if (idx >= 0) list[idx] = list[idx].copy(url = "http://127.0.0.1:$p/mcp")
                QuroMcpClientPrefs.save(context, list)
                Log.i(TAG, "已恢复本地 MCP: ${cfg.alias} @ $p")
            }.onFailure { Log.w(TAG, "恢复本地 MCP 失败 ${cfg.alias}: ${it.message}") }
        }
    }

    /** 当前运行的本地 server 数（供界面展示）。 */
    fun runningCount(): Int = servers.count { it.value.running }
}
