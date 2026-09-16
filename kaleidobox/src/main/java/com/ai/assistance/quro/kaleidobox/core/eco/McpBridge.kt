package com.ai.assistance.quro.kaleidobox.core.eco

import com.ai.assistance.quro.kaleidobox.core.KaleidoException
import com.ai.assistance.quro.kaleidobox.core.engine.*
import com.ai.assistance.quro.kaleidobox.core.model.KValue
import com.ai.assistance.quro.kaleidobox.core.model.UnitSpec
import com.ai.assistance.quro.kaleidobox.core.util.Json
import java.io.File

/**
 * MCP（Model Context Protocol）双向桥。
 *
 * ToolPkg 完全没有这一层 —— 它的插件只能在 Operit 内部用。
 * Kaleido 把 MCP 做成一等公民，两个方向都能走：
 *
 *   1) [McpClient]：宿主作为 **客户端** 接入外部 MCP Server（stdio / http），
 *      把对端的 tools 注册成宿主里的普通 unit —— 于是 AI 调 MCP 工具和调本地插件**没有任何区别**。
 *
 *   2) [McpServer]：宿主作为 **服务端**，把已安装的 Kaleido 包暴露出去，
 *      让 Claude Desktop / Cursor / 任何 MCP 客户端都能直接用你的插件。
 *
 * 关键在于：两者共用 core/engine/RpcTransport.kt 里的 [JsonRpcPeer]，
 * 因为 MCP 本质上就是 JSON-RPC 2.0 —— 协议栈零重复。
 */

// ============================ 客户端 ============================

data class McpToolSpec(
    val name: String,
    val description: String,
    val inputSchema: Map<String, Any?> = emptyMap(),
    val server: String,
)

class McpClient(
    val name: String,
    private val peer: JsonRpcPeer,
) : AutoCloseable {

    @Volatile
    private var initialized = false
    private var serverInfo: Map<String, Any?> = emptyMap()
    private var cachedTools: List<McpToolSpec> = emptyList()

    fun initialize(clientName: String = "kaleidobox-host", clientVersion: String = "1.0.0"): Map<String, Any?> {
        val resp = peer.request(
            "initialize", mapOf(
                "protocolVersion" to "2024-11-05",
                "capabilities" to mapOf("tools" to emptyMap<String, Any?>()),
                "clientInfo" to mapOf("name" to clientName, "version" to clientVersion),
            ), 15_000
        )
        if (resp.error != null) throw KaleidoException.Resolve("MCP $name 初始化失败: ${resp.error.message}")
        @Suppress("UNCHECKED_CAST")
        serverInfo = resp.result as? Map<String, Any?> ?: emptyMap()
        peer.notify("notifications/initialized", emptyMap())
        initialized = true
        return serverInfo
    }

    fun listTools(force: Boolean = false): List<McpToolSpec> {
        if (!initialized) initialize()
        if (!force && cachedTools.isNotEmpty()) return cachedTools
        val resp = peer.request("tools/list", emptyMap(), 15_000)
        if (resp.error != null) throw KaleidoException.Resolve("tools/list 失败: ${resp.error.message}")
        @Suppress("UNCHECKED_CAST")
        val result = resp.result as? Map<String, Any?> ?: return emptyList()
        @Suppress("UNCHECKED_CAST")
        val tools = result["tools"] as? List<Map<String, Any?>> ?: emptyList()
        cachedTools = tools.map { t ->
            @Suppress("UNCHECKED_CAST")
            McpToolSpec(
                name = t["name"] as? String ?: "",
                description = t["description"] as? String ?: "",
                inputSchema = t["inputSchema"] as? Map<String, Any?> ?: emptyMap(),
                server = name,
            )
        }
        return cachedTools
    }

    /** 调用 MCP 工具，统一转成 KValue，调用方无需感知 MCP 的 content 数组结构。 */
    fun callTool(tool: String, args: Map<String, Any?> = emptyMap(), timeoutMs: Int = 60_000): KValue {
        if (!initialized) initialize()
        val resp = peer.request("tools/call", mapOf("name" to tool, "arguments" to args), timeoutMs)
        if (resp.error != null) return KValue.Err("E_MCP", resp.error.message ?: "mcp error")
        @Suppress("UNCHECKED_CAST")
        val result = resp.result as? Map<String, Any?> ?: return KValue.Null
        val isError = result["isError"] as? Boolean ?: false
        @Suppress("UNCHECKED_CAST")
        val content = result["content"] as? List<Map<String, Any?>> ?: emptyList()
        val text = content.joinToString("\n") { it["text"] as? String ?: "" }
        val structured = result["structuredContent"]
        return when {
            isError -> KValue.Err("E_MCP_TOOL", text.ifBlank { "mcp tool error" })
            structured != null -> Json.toK(structured)
            content.size == 1 && (content[0]["type"] == "text") -> KValue.Str(text)
            else -> KValue.obj("text" to text, "content" to content)
        }
    }

    /** 把远端工具转成宿主可直接注册的 unit 定义。 */
    fun toUnitSpec(t: McpToolSpec) = UnitSpec(
        name = sanitizeToolName(t.name),
        runtime = "mcp",
        target = "mcp:${t.name}",
        description = "[MCP:${t.server}] ${t.description}",
        params = t.inputSchema,
        capabilities = listOf("net.mcp"),
    )

    override fun close() = peer.close()

    companion object {
        /** MCP 工具名可能含非法字符，转成 host 能索引的 unit 名。 */
        fun sanitizeToolName(raw: String): String {
            val s = raw.lowercase().replace(Regex("[^a-z0-9_]"), "_").trim('_')
            return if (s.firstOrNull()?.isLetter() == true) s.take(48) else "mcp_${s.take(44)}"
        }

        /** 启动一个 stdio MCP server 并建立连接。 */
        fun spawnStdio(name: String, command: List<String>, workDir: File? = null, env: Map<String, String> = emptyMap()): McpClient {
            val pb = ProcessBuilder(command).apply {
                workDir?.let { directory(it) }
                environment().putAll(env)
                redirectErrorStream(false)
            }
            val transport = StdioTransport(pb.start())
            val client = McpClient(name, JsonRpcPeer(transport, handler = { _ ->
                // MCP stdio server 一般不会反向调用客户端，除 sampling/roots；此处返回空实现
                mapOf<String, Any?>()
            }))
            client.initialize()
            return client
        }
    }
}

// ============================ 服务端 ============================

/**
 * 把宿主里已注册的 unit 暴露成 MCP Server。
 * 传输无关：只要能拿到 [JsonRpcPeer]（stdio / socket / 任意管道）就能服务。
 */
class McpServer(
    private val serverName: String = "kaleidobox-host",
    private val version: String = "1.0.0",
    private val listUnits: () -> List<Triple<String, String, Map<String, Any?>>>, // name, description, schema
    private val invokeUnit: (String, Map<String, Any?>) -> KValue,
) {
    fun handle(req: RpcRequest): Any? = when (req.method) {
        "initialize" -> mapOf(
            "protocolVersion" to "2024-11-05",
            "capabilities" to mapOf("tools" to mapOf("listChanged" to true)),
            "serverInfo" to mapOf("name" to serverName, "version" to version),
        )

        "tools/list" -> mapOf("tools" to listUnits().map { (n, d, s) ->
            mapOf("name" to n, "description" to d, "inputSchema" to s.ifEmpty {
                mapOf("type" to "object", "properties" to emptyMap<String, Any?>())
            })
        })

        "tools/call" -> {
            val name = req.params["name"] as? String
                ?: throw KaleidoException.Resolve("tools/call 缺少 name")
            @Suppress("UNCHECKED_CAST")
            val args = req.params["arguments"] as? Map<String, Any?> ?: emptyMap()
            val out = invokeUnit(name, args)
            val text = if (out is KValue.Str) out.value else Json.write(Json.fromK(out))
            mapOf(
                "content" to listOf(mapOf("type" to "text", "text" to text)),
                "isError" to (out is KValue.Err),
            )
        }

        "ping" -> emptyMap<String, Any?>()
        else -> throw KaleidoException.Resolve("MCP 方法未实现: ${req.method}")
    }
}

// ============================ 注册表 ============================

/**
 * MCP 集成点：宿主持有一个即可。
 * 同时管理"接入的外部 server"和"对外暴露的 server"。
 */
class McpBridge(
    private val onRegisterUnit: (UnitSpec) -> Unit,
    private val onUnregisterUnit: (String) -> Unit,
) : AutoCloseable {

    private val clients = java.util.concurrent.ConcurrentHashMap<String, McpClient>()
    private val registered = java.util.concurrent.ConcurrentHashMap<String, UnitSpec>()

    fun connectStdio(name: String, command: List<String>, workDir: File? = null): List<McpToolSpec> {
        val c = McpClient.spawnStdio(name, command, workDir)
        clients[name] = c
        return adoptTools(name, c)
    }

    fun connect(name: String, client: McpClient): List<McpToolSpec> {
        clients[name] = client
        return adoptTools(name, client)
    }

    private fun adoptTools(name: String, c: McpClient): List<McpToolSpec> {
        val specs = c.listTools(true)
        specs.forEach { t ->
            val u = c.toUnitSpec(t)
            registered[u.name] = u
            onRegisterUnit(u)
        }
        return specs
    }

    /** 调用一个来自 MCP 的 unit。target 形如 "mcp:<原始工具名>"。 */
    fun invoke(target: String, args: Map<String, Any?>, timeoutMs: Int = 60_000): KValue {
        val raw = target.removePrefix("mcp:")
        val entry = registered.entries.firstOrNull { it.value.target.removePrefix("mcp:") == raw }
            ?: return KValue.Err("E_MCP", "未注册的 MCP 工具: $raw")
        val client = clients[entry.value.description.substringAfter("[MCP:").substringBefore("]")]
            ?: return KValue.Err("E_MCP", "MCP server 未连接: $raw")
        return client.callTool(raw, args, timeoutMs)
    }

    fun disconnect(name: String) {
        clients.remove(name)?.let { c ->
            registered.filter { it.value.description.contains("[MCP:$name]") }.forEach { (unitName, _) ->
                registered.remove(unitName)
                onUnregisterUnit(unitName)
            }
            c.close()
        }
    }

    fun connectedServers(): Set<String> = clients.keys.toSet()
    fun adoptedTools(): Map<String, UnitSpec> = registered.toMap()

    override fun close() = clients.keys.toList().forEach { disconnect(it) }
}
