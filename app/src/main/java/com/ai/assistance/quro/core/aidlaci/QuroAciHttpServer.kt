package com.ai.assistance.quro.core.aidlaci

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap

/**
 * ACI HTTP API 服务器（假接口/模拟实现）。
 *
 * 当 ACI 真实 API 尚未完成时，提供 HTTP RESTful 接口供前端/测试使用。
 * 基于 ServerSocket 零依赖实现，仅监听 127.0.0.1，外部不可达。
 *
 * 端点：
 * - GET  /aci/capabilities          → 列出所有能力
 * - POST /aci/call                  → 调用能力
 * - GET  /aci/apps                  → 列出已发现的 ACI App
 * - GET  /aci/health                → 健康检查
 * - POST /aci/discover              → 触发服务发现
 * - GET  /aci/audit                 → 获取调用审计日志
 *
 * 响应格式：JSON { "success": true/false, "data": ..., "error": "..." }
 */
class QuroAciHttpServer(private val appContext: Context) {

    companion object {
        private const val TAG = "AciHttpServer"
    }

    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null

    @Volatile var port: Int = 0
        private set
    @Volatile var running: Boolean = false
        private set

    val endpoint: String get() = "http://127.0.0.1:$port"

    // 模拟的能力数据
    private val mockCapabilities = ConcurrentHashMap<String, JSONObject>()

    // 模拟的 ACI App 列表
    private val mockApps = mutableListOf<JSONObject>()

    // 调用审计日志
    private val auditLog = mutableListOf<JSONObject>()

    init {
        initMockData()
    }

    /**
     * 初始化模拟数据
     */
    private fun initMockData() {
        // 模拟能力
        val capabilities = listOf(
            createMockCapability(
                id = "browser_open",
                description = "打开网页浏览器并导航到指定URL",
                params = listOf("url" to "string"),
                packageName = "com.ai.assistance.quro.browser"
            ),
            createMockCapability(
                id = "browser_read",
                description = "读取当前网页内容",
                params = listOf("selector" to "string"),
                packageName = "com.ai.assistance.quro.browser"
            ),
            createMockCapability(
                id = "http_request",
                description = "发起HTTP请求并返回响应",
                params = listOf(
                    "url" to "string",
                    "method" to "string",
                    "headers" to "string",
                    "body" to "string"
                ),
                packageName = "com.ai.assistance.quro"
            ),
            createMockCapability(
                id = "file_read",
                description = "读取文件内容",
                params = listOf("path" to "string"),
                packageName = "com.ai.assistance.quro"
            ),
            createMockCapability(
                id = "file_write",
                description = "写入文件内容",
                params = listOf(
                    "path" to "string",
                    "content" to "string"
                ),
                packageName = "com.ai.assistance.quro"
            ),
            createMockCapability(
                id = "shell_exec",
                description = "执行Shell命令",
                params = listOf("command" to "string"),
                packageName = "com.ai.assistance.quro"
            )
        )

        capabilities.forEach { mockCapabilities[it.getString("id")] = it }

        // 模拟ACI App
        mockApps.addAll(listOf(
            createMockApp(
                packageName = "com.ai.assistance.quro",
                appName = "ZorvAI",
                status = "bound",
                capabilities = listOf("http_request", "file_read", "file_write", "shell_exec")
            ),
            createMockApp(
                packageName = "com.ai.assistance.quro.browser",
                appName = "ZorvAI Browser",
                status = "bound",
                capabilities = listOf("browser_open", "browser_read", "browser_elements", "browser_action")
            ),
            createMockApp(
                packageName = "com.example.weather",
                appName = "天气助手",
                status = "discovered",
                capabilities = listOf("get_weather", "get_forecast")
            )
        ))
    }

    private fun createMockCapability(
        id: String,
        description: String,
        params: List<Pair<String, String>>,
        packageName: String
    ): JSONObject {
        val paramsArray = JSONArray()
        params.forEach { (name, type) ->
            paramsArray.put(JSONObject().apply {
                put("name", name)
                put("type", type)
                put("required", true)
            })
        }

        return JSONObject().apply {
            put("id", id)
            put("description", description)
            put("params", paramsArray)
            put("package", packageName)
            put("version", "1.0.0")
            put("flags", JSONArray())
        }
    }

    private fun createMockApp(
        packageName: String,
        appName: String,
        status: String,
        capabilities: List<String>
    ): JSONObject {
        return JSONObject().apply {
            put("package", packageName)
            put("name", appName)
            put("status", status)
            put("capabilities", JSONArray(capabilities))
            put("version", "1.0.0")
            put("last_seen", System.currentTimeMillis())
        }
    }

    /**
     * 启动HTTP服务器
     */
    fun start(): Int {
        if (running) return port
        val ss = ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))
        port = ss.localPort
        serverSocket = ss
        running = true
        acceptThread = Thread({ acceptLoop(ss) }, "AciHttpServer").also {
            it.isDaemon = true
            it.start()
        }
        Log.i(TAG, "ACI HTTP Server started on port $port")
        return port
    }

    /**
     * 停止服务器
     */
    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Throwable) {}
        serverSocket = null
        acceptThread = null
        Log.i(TAG, "ACI HTTP Server stopped")
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (running && !ss.isClosed) {
            try {
                val socket = ss.accept()
                Thread({ handleRequest(socket) }, "AciHttpReq").start()
            } catch (e: SocketException) {
                if (running) Log.e(TAG, "Accept error: ${e.message}")
            } catch (e: Exception) {
                Log.e(TAG, "Accept error: ${e.message}")
            }
        }
    }

    private fun handleRequest(socket: Socket) {
        try {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val writer = socket.getOutputStream()

            // 读取HTTP请求
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return

            val method = parts[0]
            val path = parts[1]

            // 读取请求头
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                val colonIndex = line.indexOf(':')
                if (colonIndex > 0) {
                    val key = line.substring(0, colonIndex).trim()
                    val value = line.substring(colonIndex + 1).trim()
                    headers[key.lowercase()] = value
                }
            }

            // 读取请求体（如果有）
            val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
            val body = if (contentLength > 0) {
                val buffer = CharArray(contentLength)
                reader.read(buffer, 0, contentLength)
                String(buffer)
            } else ""

            // 处理请求
            val response = when {
                path == "/aci/health" && method == "GET" -> handleHealth()
                path == "/aci/capabilities" && method == "GET" -> handleGetCapabilities()
                path == "/aci/call" && method == "POST" -> handleCall(body)
                path == "/aci/apps" && method == "GET" -> handleGetApps()
                path == "/aci/discover" && method == "POST" -> handleDiscover()
                path == "/aci/audit" && method == "GET" -> handleGetAudit()
                path == "/aci/echo" && method == "POST" -> handleEcho(body)
                else -> createErrorResponse(404, "Not Found")
            }

            // 发送响应
            val responseBytes = response.toString().toByteArray()
            val httpResponse = buildString {
                append("HTTP/1.1 200 OK\r\n")
                append("Content-Type: application/json; charset=utf-8\r\n")
                append("Content-Length: ${responseBytes.size}\r\n")
                append("Connection: close\r\n")
                append("Access-Control-Allow-Origin: *\r\n")
                append("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
                append("Access-Control-Allow-Headers: Content-Type\r\n")
                append("\r\n")
            }

            writer.write(httpResponse.toByteArray())
            writer.write(responseBytes)
            writer.flush()

            // 记录审计日志
            if (path != "/aci/health") {
                auditLog.add(JSONObject().apply {
                    put("timestamp", System.currentTimeMillis())
                    put("method", method)
                    put("path", path)
                    put("success", response.optBoolean("success", false))
                })
                // 保持审计日志在100条以内
                if (auditLog.size > 100) {
                    auditLog.removeAt(0)
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Handle request error: ${e.message}")
        } finally {
            try { socket.close() } catch (_: Throwable) {}
        }
    }

    private fun handleHealth(): JSONObject {
        return JSONObject().apply {
            put("success", true)
            put("data", JSONObject().apply {
                put("status", "running")
                put("port", port)
                put("timestamp", System.currentTimeMillis())
                put("capabilities_count", mockCapabilities.size)
                put("apps_count", mockApps.size)
                put("version", "1.0.0-mock")
            })
        }
    }

    private fun handleGetCapabilities(): JSONObject {
        val capsArray = JSONArray()
        mockCapabilities.values.forEach { capsArray.put(it) }

        return JSONObject().apply {
            put("success", true)
            put("data", JSONObject().apply {
                put("capabilities", capsArray)
                put("total", capsArray.length())
            })
        }
    }

    private fun handleCall(body: String): JSONObject {
        return try {
            val request = JSONObject(body)
            val capabilityId = request.optString("capability", "")
            val args = request.optJSONObject("args") ?: JSONObject()
            val callerPkg = request.optString("caller_package", "unknown")

            val capability = mockCapabilities[capabilityId]
            if (capability == null) {
                return createErrorResponse(404, "Capability not found: $capabilityId")
            }

            // 模拟调用结果
            val result = simulateCapabilityCall(capabilityId, args)

            JSONObject().apply {
                put("success", true)
                put("data", JSONObject().apply {
                    put("call_id", "mock_${System.currentTimeMillis()}")
                    put("capability", capabilityId)
                    put("result", result)
                    put("execution_time_ms", (Math.random() * 1000).toLong())
                    put("caller_package", callerPkg)
                })
            }
        } catch (e: Exception) {
            createErrorResponse(400, "Invalid request: ${e.message}")
        }
    }

    private fun simulateCapabilityCall(capabilityId: String, args: JSONObject): JSONObject {
        return when (capabilityId) {
            "browser_open" -> JSONObject().apply {
                put("success", true)
                put("url", args.optString("url", "https://example.com"))
                put("title", "模拟浏览器页面")
                put("message", "浏览器已打开指定URL（模拟）")
            }
            "browser_read" -> JSONObject().apply {
                put("content", "<html><body><h1>模拟网页内容</h1><p>这是从模拟ACI服务器返回的内容。</p></body></html>")
                put("title", "模拟页面标题")
                put("url", "https://example.com")
            }
            "http_request" -> JSONObject().apply {
                put("status_code", 200)
                put("response_body", "{\"message\": \"模拟HTTP响应\", \"timestamp\": ${System.currentTimeMillis()}}")
                put("response_headers", JSONObject().apply {
                    put("Content-Type", "application/json")
                })
            }
            "file_read" -> JSONObject().apply {
                put("content", "这是模拟的文件内容。\n路径: ${args.optString("path", "unknown")}")
                put("size", 1024)
                put("modified", System.currentTimeMillis())
            }
            "file_write" -> JSONObject().apply {
                put("success", true)
                put("bytes_written", args.optString("content", "").length)
                put("path", args.optString("path", "unknown"))
                put("message", "文件写入成功（模拟）")
            }
            "shell_exec" -> JSONObject().apply {
                put("exit_code", 0)
                put("stdout", "模拟命令输出: ${args.optString("command", "echo hello")}")
                put("stderr", "")
                put("execution_time_ms", 100)
            }
            else -> JSONObject().apply {
                put("message", "模拟响应")
                put("capability", capabilityId)
            }
        }
    }

    private fun handleGetApps(): JSONObject {
        val appsArray = JSONArray()
        mockApps.forEach { appsArray.put(it) }

        return JSONObject().apply {
            put("success", true)
            put("data", JSONObject().apply {
                put("apps", appsArray)
                put("total", appsArray.length())
                put("bound_count", mockApps.count { it.getString("status") == "bound" })
            })
        }
    }

    private fun handleDiscover(): JSONObject {
        // 模拟服务发现
        return JSONObject().apply {
            put("success", true)
            put("data", JSONObject().apply {
                put("message", "服务发现已触发（模拟）")
                put("found_apps", mockApps.size)
                put("timestamp", System.currentTimeMillis())
                put("discovery_time_ms", (Math.random() * 2000).toLong())
            })
        }
    }

    private fun handleGetAudit(): JSONObject {
        val auditArray = JSONArray()
        auditLog.takeLast(50).forEach { auditArray.put(it) }

        return JSONObject().apply {
            put("success", true)
            put("data", JSONObject().apply {
                put("audit_log", auditArray)
                put("total_records", auditLog.size)
                put("showing_last", auditArray.length())
            })
        }
    }

    private fun handleEcho(body: String): JSONObject {
        return try {
            val request = JSONObject(body)
            JSONObject().apply {
                put("success", true)
                put("data", JSONObject().apply {
                    put("echo", request)
                    put("timestamp", System.currentTimeMillis())
                    put("server", "ACI Mock Server")
                    put("version", "1.0.0")
                })
            }
        } catch (e: Exception) {
            createErrorResponse(400, "Invalid JSON: ${e.message}")
        }
    }

    private fun createErrorResponse(code: Int, message: String): JSONObject {
        return JSONObject().apply {
            put("success", false)
            put("error", JSONObject().apply {
                put("code", code)
                put("message", message)
            })
        }
    }

    /**
     * 添加自定义模拟能力（供测试使用）
     */
    fun addMockCapability(capability: JSONObject) {
        val id = capability.optString("id")
        if (id.isNotEmpty()) {
            mockCapabilities[id] = capability
            Log.i(TAG, "Added mock capability: $id")
        }
    }

    /**
     * 移除模拟能力
     */
    fun removeMockCapability(capabilityId: String) {
        mockCapabilities.remove(capabilityId)
        Log.i(TAG, "Removed mock capability: $capabilityId")
    }

    /**
     * 获取服务器状态
     */
    fun getStatus(): JSONObject {
        return JSONObject().apply {
            put("running", running)
            put("port", port)
            put("endpoint", endpoint)
            put("capabilities_count", mockCapabilities.size)
            put("apps_count", mockApps.size)
            put("audit_log_size", auditLog.size)
        }
    }
}