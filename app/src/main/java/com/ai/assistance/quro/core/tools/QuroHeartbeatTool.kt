package com.ai.assistance.quro.core.tools

import android.content.Context
import com.ai.assistance.quro.core.QuroToolResult
import com.ai.assistance.quro.core.QuroToolSpec
import com.ai.assistance.quro.core.model.QuroHeartbeatService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.json.JSONArray

/**
 * 心跳管理工具
 * 
 * 提供心跳服务的控制和状态查询功能。
 */
class QuroHeartbeatTool(private val context: Context) : QuroTool {
    
    private val heartbeatService = QuroHeartbeatService.getInstance(context)
    
    /**
     * 工具规格定义
     */
    // ===== QuroTool 契约实现 =====
    // 本类原先只是「独立组件」（只有 getToolSpec + suspend execute），既未实现 QuroTool，
    // 也未注册进工具注册表 —— AI 根本调不到，属于死代码。补上契约后由
    // buildQuroRegistry 注册，才真正进入模型的 function calling 工具集。
    override val name: String get() = getToolSpec().name
    override val description: String get() = getToolSpec().description
    override val parametersJson: String get() = getToolSpec().parametersJson

    override fun run(context: Context, arguments: String): String {
        val args = runCatching { JSONObject(arguments) }.getOrElse { JSONObject() }
        // QuroTool.run 是同步契约，而内部实现是 suspend：这里用 runBlocking 桥接。
        // 工具本身都在 IO/Default 线程执行，不会阻塞主线程。
        return runBlocking {
            runCatching { execute(args) }
                .getOrElse { e -> QuroToolResult.Error("执行失败：${e.message ?: e::class.simpleName}") }
                .let { r -> if (r.name == "error") "❌ ${r.result}" else r.result }
        }
    }

    fun getToolSpec(): QuroToolSpec {
        return QuroToolSpec(
            name = "heartbeat",
            description = "管理自主心跳服务，支持启动/停止心跳、查看心跳状态、执行健康检查、生成状态报告。",
            // QuroToolSpec 第三个参数是 parametersJson（JSON Schema 字符串）。项目只依赖 org.json，
            // 没有 parameters=mapOf(...) + QuroToolSpec.Parameter(...) 这套 DSL，必须手写 schema。
            parametersJson = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("action", JSONObject().apply {
                        put("type", "string")
                        put("description", "操作类型：start（启动心跳）、stop（停止心跳）、status（查看状态）、check（执行健康检查）、report（生成状态报告）、info（查看系统信息）")
                    })
                })
                put("required", JSONArray().apply { put("action") })
            }.toString()
        )
    }
    
    /**
     * 执行工具操作
     */
    suspend fun execute(args: JSONObject): QuroToolResult {
        return withContext(Dispatchers.IO) {
            try {
                val action = args.optString("action", "status")
                
                when (action) {
                    "start" -> startHeartbeat()
                    "stop" -> stopHeartbeat()
                    "status" -> getStatus()
                    "check" -> performCheck()
                    "report" -> generateReport()
                    "info" -> getSystemInfo()
                    else -> QuroToolResult.Error("未知操作: $action")
                }
            } catch (e: Exception) {
                QuroToolResult.Error("执行操作失败: ${e.message}")
            }
        }
    }
    
    /**
     * 启动心跳服务
     */
    private fun startHeartbeat(): QuroToolResult {
        heartbeatService.startHeartbeat()
        return QuroToolResult.Success("心跳服务已启动")
    }
    
    /**
     * 停止心跳服务
     */
    private fun stopHeartbeat(): QuroToolResult {
        heartbeatService.stopHeartbeat()
        return QuroToolResult.Success("心跳服务已停止")
    }
    
    /**
     * 获取心跳状态
     */
    private fun getStatus(): QuroToolResult {
        val status = heartbeatService.getHeartbeatStatus()
        
        val result = StringBuilder("心跳服务状态:\n")
        result.append("运行状态: ${if (status.isRunning) "运行中" else "已停止"}\n")
        result.append("心跳次数: ${status.heartbeatCount}\n")
        
        if (status.lastHeartbeatTime > 0) {
            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            result.append("上次心跳: ${dateFormat.format(Date(status.lastHeartbeatTime))}\n")
        } else {
            result.append("上次心跳: 从未执行\n")
        }
        
        if (status.nextHeartbeatTime > 0) {
            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            result.append("下次心跳: ${dateFormat.format(Date(status.nextHeartbeatTime))}\n")
        }
        
        return QuroToolResult.Success(result.toString())
    }
    
    /**
     * 执行健康检查
     */
    private suspend fun performCheck(): QuroToolResult {
        val result = heartbeatService.performHeartbeat()
        
        val output = StringBuilder("健康检查结果:\n")
        output.append("心跳编号: #${result.heartbeatNumber}\n")
        output.append("检查耗时: ${result.durationMs}ms\n")
        output.append("整体状态: ${result.overallStatus}\n\n")
        
        output.append("详细检查:\n")
        result.checks.forEach { check ->
            output.append("- ${check.name}: ${check.status} (${check.value}${check.unit})\n")
            output.append("  ${check.details}\n")
        }
        
        return QuroToolResult.Success(output.toString())
    }
    
    /**
     * 生成状态报告
     */
    private suspend fun generateReport(): QuroToolResult {
        val report = heartbeatService.generateStatusReport()
        
        val output = StringBuilder("系统状态报告\n")
        output.append("生成时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(report.timestamp))}\n\n")
        
        // 心跳结果
        output.append("【心跳检查】\n")
        output.append("状态: ${report.heartbeatResult.overallStatus}\n")
        output.append("耗时: ${report.heartbeatResult.durationMs}ms\n\n")
        
        // 提供商统计
        output.append("【提供商统计】\n")
        output.append("总提供商: ${report.providerStats.totalProviders}\n")
        output.append("健康提供商: ${report.providerStats.healthyProviders}\n")
        output.append("故障转移次数: ${report.providerStats.totalFailovers}\n\n")
        
        // 系统信息
        output.append("【系统信息】\n")
        output.append("处理器: ${report.systemInfo.availableProcessors} 核\n")
        output.append("内存: ${report.systemInfo.freeMemoryMB}MB 可用 / ${report.systemInfo.maxMemoryMB}MB 最大\n")
        output.append("Java版本: ${report.systemInfo.javaVersion}\n\n")
        
        // 建议
        output.append("【建议】\n")
        report.recommendations.forEach { recommendation ->
            output.append("- $recommendation\n")
        }
        
        return QuroToolResult.Success(output.toString())
    }
    
    /**
     * 获取系统信息
     */
    private fun getSystemInfo(): QuroToolResult {
        val runtime = Runtime.getRuntime()
        
        val result = StringBuilder("系统信息:\n")
        result.append("可用处理器: ${runtime.availableProcessors()} 核\n")
        result.append("最大内存: ${runtime.maxMemory() / 1024 / 1024}MB\n")
        result.append("总内存: ${runtime.totalMemory() / 1024 / 1024}MB\n")
        result.append("空闲内存: ${runtime.freeMemory() / 1024 / 1024}MB\n")
        result.append("已用内存: ${(runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024}MB\n")
        result.append("Java版本: ${System.getProperty("java.version")}\n")
        result.append("操作系统: ${System.getProperty("os.name")}\n")
        result.append("OS版本: ${System.getProperty("os.version")}\n")
        
        // 存储信息
        val dataDir = context.filesDir
        val totalSpace = dataDir.totalSpace / 1024 / 1024
        val freeSpace = dataDir.freeSpace / 1024 / 1024
        result.append("存储总计: ${totalSpace}MB\n")
        result.append("存储可用: ${freeSpace}MB\n")
        result.append("存储已用: ${totalSpace - freeSpace}MB\n")
        
        return QuroToolResult.Success(result.toString())
    }
}
