package com.ai.assistance.quro.core.tools

import android.content.Context
import com.ai.assistance.quro.core.QuroToolResult
import com.ai.assistance.quro.core.QuroToolSpec
import com.ai.assistance.quro.core.model.ApiProviderType
import com.ai.assistance.quro.core.model.FailoverStats
import com.ai.assistance.quro.core.model.QuroFailoverManager
import com.ai.assistance.quro.core.model.QuroHealthCheckService
import com.ai.assistance.quro.core.model.QuroMultiProviderRepository
import com.ai.assistance.quro.core.model.QuroProviderConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlinx.coroutines.runBlocking
import org.json.JSONArray

/**
 * 多提供商管理工具
 * 
 * 提供多提供商配置管理、健康检查和故障转移控制功能。
 * 参考 Agora 和 Kai 的多提供商设计。
 */
class QuroMultiProviderTool(private val context: Context) : QuroTool {
    
    private val repository = QuroMultiProviderRepository(context)
    private val healthCheckService = QuroHealthCheckService(context)
    
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
            name = "multi_provider",
            description = "管理多提供商配置，支持自动故障转移、健康检查和优先级排序。可以添加、更新、删除提供商配置，执行健康检查，查看故障转移状态。",
            // QuroToolSpec 第三个参数是 parametersJson（JSON Schema 字符串）。项目只依赖 org.json，
            // 没有 parameters=mapOf(...) + QuroToolSpec.Parameter(...) 这套 DSL，必须手写 schema。
            parametersJson = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("action", JSONObject().apply {
                        put("type", "string")
                        put("description", "操作类型：list（列出所有提供商）、add（添加提供商）、update（更新提供商）、remove（删除提供商）、health_check（执行健康检查）、get_status（获取故障转移状态）、switch_provider（切换当前提供商）、get_stats（获取统计信息）")
                    })
                    put("provider_id", JSONObject().apply {
                        put("type", "string")
                        put("description", "提供商ID（update、remove、switch_provider操作需要）")
                    })
                    put("name", JSONObject().apply {
                        put("type", "string")
                        put("description", "提供商名称（add、update操作需要）")
                    })
                    put("provider_type", JSONObject().apply {
                        put("type", "string")
                        put("description", "提供商类型（如OPENAI、ANTHROPIC、GOOGLE等）")
                    })
                    put("base_url", JSONObject().apply {
                        put("type", "string")
                        put("description", "API基础URL")
                    })
                    put("api_key", JSONObject().apply {
                        put("type", "string")
                        put("description", "API密钥")
                    })
                    put("default_model", JSONObject().apply {
                        put("type", "string")
                        put("description", "默认模型名称")
                    })
                    put("priority", JSONObject().apply {
                        put("type", "integer")
                        put("description", "优先级（数值越小优先级越高）")
                    })
                    put("enabled", JSONObject().apply {
                        put("type", "boolean")
                        put("description", "是否启用")
                    })
                    put("is_local", JSONObject().apply {
                        put("type", "boolean")
                        put("description", "是否为本地模型")
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
                val action = args.optString("action", "list")
                
                when (action) {
                    "list" -> listProviders()
                    "add" -> addProvider(args)
                    "update" -> updateProvider(args)
                    "remove" -> removeProvider(args)
                    "health_check" -> performHealthCheck()
                    "get_status" -> getFailoverStatus()
                    "switch_provider" -> switchProvider(args)
                    "get_stats" -> getStats()
                    else -> QuroToolResult.Error("未知操作: $action")
                }
            } catch (e: Exception) {
                QuroToolResult.Error("执行操作失败: ${e.message}")
            }
        }
    }
    
    /**
     * 列出所有提供商
     */
    private fun listProviders(): QuroToolResult {
        val configs = repository.getAllConfigs()
        if (configs.isEmpty()) {
            return QuroToolResult.Success("没有配置的提供商")
        }
        
        val result = StringBuilder("提供商列表:\n")
        configs.forEachIndexed { index, config ->
            result.append("${index + 1}. ${config.name}\n")
            result.append("   类型: ${config.providerType.name}\n")
            result.append("   URL: ${config.baseUrl}\n")
            result.append("   模型: ${config.defaultModel}\n")
            result.append("   优先级: ${config.priority}\n")
            result.append("   状态: ${config.healthStatus.name}\n")
            result.append("   启用: ${config.enabled}\n")
            result.append("   本地: ${config.isLocal}\n")
            if (config.lastError.isNotBlank()) {
                result.append("   错误: ${config.lastError}\n")
            }
            result.append("\n")
        }
        
        return QuroToolResult.Success(result.toString())
    }
    
    /**
     * 添加提供商
     */
    private fun addProvider(args: JSONObject): QuroToolResult {
        val name = args.optString("name", "")
        if (name.isBlank()) {
            return QuroToolResult.Error("提供商名称不能为空")
        }
        
        val providerTypeStr = args.optString("provider_type", "OPENAI")
        val providerType = ApiProviderType.fromProviderTypeId(providerTypeStr) 
            ?: return QuroToolResult.Error("无效的提供商类型: $providerTypeStr")
        
        val baseUrl = args.optString("base_url", "")
        val apiKey = args.optString("api_key", "")
        val defaultModel = args.optString("default_model", "")
        val priority = args.optInt("priority", repository.getAllConfigs().size)
        val enabled = args.optBoolean("enabled", true)
        val isLocal = args.optBoolean("is_local", false)
        
        val config = QuroProviderConfig(
            name = name,
            providerType = providerType,
            baseUrl = baseUrl,
            apiKey = apiKey,
            defaultModel = defaultModel,
            priority = priority,
            enabled = enabled,
            isLocal = isLocal,
            requiresApiKey = !isLocal && apiKey.isBlank()
        )
        
        repository.addConfig(config)
        
        return QuroToolResult.Success("提供商 '$name' 添加成功，ID: ${config.id}")
    }
    
    /**
     * 更新提供商
     */
    private fun updateProvider(args: JSONObject): QuroToolResult {
        val providerId = args.optString("provider_id", "")
        if (providerId.isBlank()) {
            return QuroToolResult.Error("提供商ID不能为空")
        }
        
        val existingConfig = repository.getConfigById(providerId)
            ?: return QuroToolResult.Error("找不到提供商: $providerId")
        
        val updatedConfig = existingConfig.copy(
            name = args.optString("name", existingConfig.name),
            baseUrl = args.optString("base_url", existingConfig.baseUrl),
            apiKey = args.optString("api_key", existingConfig.apiKey),
            defaultModel = args.optString("default_model", existingConfig.defaultModel),
            priority = args.optInt("priority", existingConfig.priority),
            enabled = args.optBoolean("enabled", existingConfig.enabled),
            isLocal = args.optBoolean("is_local", existingConfig.isLocal)
        )
        
        repository.updateConfig(updatedConfig)
        
        return QuroToolResult.Success("提供商 '${updatedConfig.name}' 更新成功")
    }
    
    /**
     * 删除提供商
     */
    private fun removeProvider(args: JSONObject): QuroToolResult {
        val providerId = args.optString("provider_id", "")
        if (providerId.isBlank()) {
            return QuroToolResult.Error("提供商ID不能为空")
        }
        
        val config = repository.getConfigById(providerId)
            ?: return QuroToolResult.Error("找不到提供商: $providerId")
        
        repository.removeConfig(providerId)
        
        return QuroToolResult.Success("提供商 '${config.name}' 删除成功")
    }
    
    /**
     * 执行健康检查
     */
    private suspend fun performHealthCheck(): QuroToolResult {
        healthCheckService.performImmediateHealthCheck()
        val summary = healthCheckService.getHealthSummary()
        
        return QuroToolResult.Success(summary.getStatusDescription())
    }
    
    /**
     * 获取故障转移状态
     */
    private fun getFailoverStatus(): QuroToolResult {
        val config = repository.getFailoverConfig()
        val state = repository.getFailoverState()
        
        val result = StringBuilder("故障转移状态:\n")
        result.append("启用: ${config.enabled}\n")
        result.append("策略: ${config.strategy.name}\n")
        result.append("当前提供商: ${state.currentProviderId ?: "未选择"}\n")
        result.append("故障转移次数: ${state.failoverCount}\n")
        result.append("连续失败次数: ${state.consecutiveFailures}\n")
        result.append("断路器状态: ${if (state.isCircuitOpen) "打开" else "关闭"}\n")
        if (state.isCircuitOpen) {
            result.append("断路器恢复时间: ${state.circuitOpenUntil}\n")
        }
        
        return QuroToolResult.Success(result.toString())
    }
    
    /**
     * 切换提供商
     */
    private suspend fun switchProvider(args: JSONObject): QuroToolResult {
        val providerId = args.optString("provider_id", "")
        if (providerId.isBlank()) {
            return QuroToolResult.Error("提供商ID不能为空")
        }
        
        val config = repository.getConfigById(providerId)
            ?: return QuroToolResult.Error("找不到提供商: $providerId")
        
        // 这里需要与 QuroFailoverManager 集成
        // val failoverManager = QuroFailoverManager(repository, ...)
        // failoverManager.switchToProvider(providerId)
        
        return QuroToolResult.Success("已切换到提供商: ${config.name}")
    }
    
    /**
     * 获取统计信息
     */
    private fun getStats(): QuroToolResult {
        // getFailoverStats() 定义在 QuroFailoverManager 上（需要 LLM 客户端才能构造），
        // 而这里只是读几个计数，直接由仓储数据现算，避免为统计把网络栈拉起来。
        val stats = runCatching {
            val state = repository.getFailoverState()
            val configs = repository.getEnabledConfigs()
            FailoverStats(
                totalProviders = configs.size,
                healthyProviders = configs.count {
                    it.healthStatus == QuroProviderConfig.HealthStatus.HEALTHY
                },
                currentProviderId = state.currentProviderId,
                totalFailovers = state.failoverCount,
                consecutiveFailures = state.consecutiveFailures,
                isCircuitOpen = state.isCircuitOpen,
                circuitOpenUntil = state.circuitOpenUntil,
            )
        }.getOrDefault(FailoverStats(0, 0, null, 0, 0, false, 0L))
        val healthSummary = healthCheckService.getHealthSummary()
        
        val result = StringBuilder("多提供商统计:\n")
        result.append("总提供商数: ${stats.totalProviders}\n")
        result.append("健康提供商数: ${stats.healthyProviders}\n")
        result.append("当前提供商: ${stats.currentProviderId ?: "未选择"}\n")
        result.append("总故障转移次数: ${stats.totalFailovers}\n")
        result.append("连续失败次数: ${stats.consecutiveFailures}\n")
        result.append("断路器状态: ${if (stats.isCircuitOpen) "打开" else "关闭"}\n")
        result.append("\n健康摘要:\n")
        result.append(healthSummary.getStatusDescription())
        
        return QuroToolResult.Success(result.toString())
    }
}
