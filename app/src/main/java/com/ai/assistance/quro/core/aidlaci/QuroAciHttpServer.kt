package com.ai.assistance.quro.core.aidlaci
import com.ai.assistance.quro.R
import com.ai.assistance.quro.util.qstr

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
 * ACI HTTP API 服务器。
 *
 * 基于 ServerSocket 零依赖实现，仅监听 127.0.0.1，外部不可达。
 *
 * 🔴 2026-11-12 从 mock 桥接为真实实现：/aci/call 此前走 simulateCapabilityCall
 * 返回模拟数据（版本 1.0.0-mock、只有 6 个假能力），外部进程拿到的全是假结果。
 * 现在直接路由到 QuroAidlAciManager.call —— 与 aci_call 工具同一条 AIDL 链路、
 * 同一套 Token 鉴权与审计；能力清单也改从 getCapabilityIndex() 实时取（不再用假数据）。
 * 仅当真实管理器不可用时，才回落到内置模拟能力（并如实标注 mock）。
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

    // 模拟的能力数据（仅真实管理器不可用时的兜底）
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
                description = qstr(R.string.qk_03700),
                params = listOf("url" to "string"),
                packageName = "com.ai.assistance.quro.browser"
            ),
            createMockCapability(
                id = "browser_read",
                description = qstr(R.string.qk_03555),
                params = listOf("selector" to "string"),
                packageName = "com.ai.assistance.quro.browser"
            ),
            createMockCapability(
                id = "http_request",
                description = qstr(R.string.qk_03654),
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
                description = qstr(R.string.qk_03636),
                params = listOf("path" to "string"),
                packageName = "com.ai.assistance.quro"
            ),
            createMockCapability(
                id = "file_write",
                description = qstr(R.string.qk_03419),
                params = listOf(
                    "path" to "string",
                    "content" to "string"
                ),
                packageName = "com.ai.assistance.quro"
            ),
            createMockCapability(
                id = "shell_exec",
                description = qstr(R.string.qk_03634),
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
     * @param requestedPort 请求端口；🔴 此前写死 ServerSocket(0) 忽略该参数，
     *   调用方要 8848 却每次拿到随机端口（实测 38457）。现在优先绑定请求端口，
     *   被占用时才回落随机端口（并在日志说明）。
     */
    fun start(requestedPort: Int = 0): Int {
        if (running) return port
        val ss = runCatching {
            ServerSocket(requestedPort, 0, InetAddress.getByName("127.0.0.1"))
        }.getOrElse { e ->
            if (requestedPort > 0) {
                Log.w(TAG, "端口 $requestedPort 绑定失败（${e.message}），回落随机端口")
                ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))
            } else throw e
        }
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
        val real = runCatching { QuroAidlAciManager.getInstance() }.getOrNull()
        val capCount = real?.getCapabilityIndex()?.values?.sumOf { it.size } ?: mockCapabilities.size
        return JSONObject().apply {
            put("success", true)
            put("data", JSONObject().apply {
                put("status", "running")
                put("port", port)
                put("timestamp", System.currentTimeMillis())
                put("capabilities_count", capCount)
                put("mock_fallback", real == null)
                put("version", if (real == null) "1.0.0-mock" else "1.1.0-real")
            })
        }
    }

    private fun handleGetCapabilities(): JSONObject {
        val capsArray = JSONArray()
        val real = runCatching { QuroAidlAciManager.getInstance() }.getOrNull()
        if (real != null) {
            // 真实能力清单：按 App 分组展开，附包名（HTTP 调用方要用 target_package）
            real.getCapabilityIndex().forEach { (pkg, caps) ->
                caps.forEach { c ->
                    capsArray.put(JSONObject().apply {
                        put("id", c.id)
                        put("package", pkg)
                        runCatching { put("description", c.description) }
                    })
                }
            }
        } else {
            mockCapabilities.values.forEach { capsArray.put(it) }
        }

        return JSONObject().apply {
            put("success", true)
            put("data", JSONObject().apply {
                put("capabilities", capsArray)
                put("total", capsArray.length())
                put("mock_fallback", real == null)
            })
        }
    }

    private fun handleCall(body: String): JSONObject {
        return try {
            val request = JSONObject(body)
            val capabilityId = request.optString("capability", "")
            val args = request.optJSONObject("args") ?: JSONObject()
            val callerPkg = request.optString("caller_package", "unknown")
            val target = request.optString("target_package", request.optString("package", ""))
                .ifBlank { "com.ai.assistance.quro" }

            val mgr = runCatching { QuroAidlAciManager.getInstance() }.getOrNull()
            if (mgr == null) {
                // 真实管理器不可用 → 老行为兜底（如实标注 mock）
                val capability = mockCapabilities[capabilityId]
                    ?: return createErrorResponse(404, "Capability not found: $capabilityId（ACI 管理器未初始化，mock 兜底也无此能力）")
                val result = simulateCapabilityCall(capabilityId, args)
                return JSONObject().apply {
                    put("success", true)
                    put("data", JSONObject().apply {
                        put("call_id", "mock_${System.currentTimeMillis()}")
                        put("capability", capabilityId)
                        put("mock", true)
                        put("result", result)
                        put("caller_package", callerPkg)
                    })
                }
            }

            // 🔴 真实链路：与 aci_call 工具同一条 AIDL/LocalSocket 通道、同一套 Token 与审计。
            // 危险能力（setUserConfirm）必须与工具侧同一门禁：调用方要在 args 带 confirm:true，
            // 否则外部进程可绕过控制方的确认策略。
            val capDef = mgr.getCapabilityIndex()[target]?.firstOrNull { it.id == capabilityId }
            val confirm = args.optBoolean("confirm", false)
            if (capDef?.isRequireUserConfirm == true && !confirm) {
                return createErrorResponse(
                    403,
                    "该能力（$capabilityId @ $target）需要用户确认：请在 args 中带 confirm:true 后重试。",
                )
            }
            val bundle = android.os.Bundle()
            args.keys().forEach { k ->
                if (k == "confirm") return@forEach
                when (val v = args.opt(k)) {
                    is Boolean -> bundle.putBoolean(k, v)
                    is Int -> bundle.putInt(k, v)
                    is Long -> bundle.putLong(k, v)
                    is Double -> bundle.putDouble(k, v)
                    else -> bundle.putString(k, args.optString(k, ""))
                }
            }
            if (confirm) bundle.putBoolean("user_confirmed", true)
            val resp = mgr.call(target, capabilityId, bundle)
            if (resp.isSuccess) {
                val result = JSONObject()
                resp.result?.keySet()?.forEach { key ->
                    val raw = resp.result?.get(key)
                    when (raw) {
                        null -> {}
                        is ByteArray -> result.put(key, java.util.Base64.getEncoder().encodeToString(raw))
                        else -> result.put(key, raw.toString())
                    }
                }
                JSONObject().apply {
                    put("success", true)
                    put("data", JSONObject().apply {
                        put("call_id", resp.getCallId() ?: "real_${System.currentTimeMillis()}")
                        put("capability", capabilityId)
                        put("target_package", target)
                        put("mock", false)
                        put("result", result)
                        put("caller_package", callerPkg)
                    })
                }
            } else {
                createErrorResponse(resp.getErrorCode(), resp.getErrorMessage() ?: "ACI 调用失败")
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
        val real = runCatching { QuroAidlAciManager.getInstance() }.getOrNull()
        var boundCount = 0
        if (real != null) {
            real.getCapabilityIndex().forEach { (pkg, caps) ->
                boundCount++
                appsArray.put(JSONObject().apply {
                    put("package", pkg)
                    put("capabilities", JSONArray(caps.map { it.id }))
                    put("total", caps.size)
                    put("status", "bound")
                })
            }
        } else {
            mockApps.forEach { appsArray.put(it) }
            boundCount = mockApps.count { it.getString("status") == "bound" }
        }

        return JSONObject().apply {
            put("success", true)
            put("data", JSONObject().apply {
                put("apps", appsArray)
                put("total", appsArray.length())
                put("bound_count", boundCount)
                put("mock_fallback", real == null)
            })
        }
    }

    private fun handleDiscover(): JSONObject {
        val real = runCatching { QuroAidlAciManager.getInstance() }.getOrNull()
        if (real != null) {
            real.refresh()
            return JSONObject().apply {
                put("success", true)
                put("data", JSONObject().apply {
                    put("message", "服务发现已触发（真实链路）")
                    put("found_apps", real.getCapabilityIndex().size)
                    put("timestamp", System.currentTimeMillis())
                })
            }
        }
        // 模拟服务发现（兜底）
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