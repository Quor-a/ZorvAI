package com.ai.assistance.quro.core.aidlaci

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ACI HTTP 服务器管理器。
 *
 * 管理 [QuroAciHttpServer] 的生命周期，提供统一的启停接口。
 * 当 ACI 真实 API 尚未完成时，可启用此模拟服务器供前端/测试使用。
 */
class AciHttpServerManager(private val context: Context) {

    companion object {
        private const val TAG = "AciHttpServerManager"
        private const val DEFAULT_PORT = 8848 // ACI 默认端口
    }

    private val server = QuroAciHttpServer(context)
    private val isRunning = AtomicBoolean(false)

    /**
     * 启动 ACI HTTP 服务器
     * @param port 监听端口，默认 8848
     * @return 实际监听端口，失败返回 -1
     */
    fun start(port: Int = DEFAULT_PORT): Int {
        if (isRunning.get()) {
            Log.w(TAG, "Server already running on port ${server.port}")
            return server.port
        }

        return try {
            val actualPort = server.start()
            isRunning.set(true)
            Log.i(TAG, "ACI HTTP Server started on port $actualPort")
            Log.i(TAG, "Endpoint: ${server.endpoint}")
            Log.i(TAG, "Mock capabilities: ${server.getStatus().optJSONObject("data")?.optInt("capabilities_count", 0)}")
            actualPort
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start ACI HTTP Server: ${e.message}")
            -1
        }
    }

    /**
     * 停止服务器
     */
    fun stop() {
        if (!isRunning.get()) return
        server.stop()
        isRunning.set(false)
        Log.i(TAG, "ACI HTTP Server stopped")
    }

    /**
     * 获取服务器状态
     */
    fun getStatus(): JSONObject {
        return server.getStatus()
    }

    /**
     * 获取服务器端点URL
     */
    fun getEndpoint(): String = server.endpoint

    /**
     * 服务器是否运行中
     */
    fun isRunning(): Boolean = isRunning.get()

    /**
     * 添加自定义模拟能力（供测试）
     */
    fun addMockCapability(capability: JSONObject) {
        server.addMockCapability(capability)
    }

    /**
     * 移除模拟能力
     */
    fun removeMockCapability(capabilityId: String) {
        server.removeMockCapability(capabilityId)
    }

    /**
     * 获取所有模拟能力
     */
    fun getMockCapabilities(): String {
        return server.getStatus().optJSONObject("data")?.optJSONArray("capabilities")?.toString() ?: "[]"
    }
}