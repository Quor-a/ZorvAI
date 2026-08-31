package com.ai.assistance.quro.core.model

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 多提供商配置：支持同时配置多个提供商，实现自动故障转移。
 * 
 * 参考 Agora（9个提供商）和 Kai（29个提供商+自动故障转移）的设计，
 * 为 ZorvAI 添加多提供商切换与自动故障转移能力。
 */
data class QuroProviderConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val providerType: ApiProviderType = ApiProviderType.OPENAI,
    val baseUrl: String = "",
    val apiKey: String = "",
    val defaultModel: String = "",
    val priority: Int = 0, // 优先级，数值越小优先级越高
    val enabled: Boolean = true,
    val healthStatus: HealthStatus = HealthStatus.UNKNOWN,
    val lastHealthCheck: Long = 0L,
    val consecutiveFailures: Int = 0,
    val lastError: String = "",
    val requiresApiKey: Boolean = true,
    val customProviderName: String = "",
    val avatar: String? = null,
    val isLocal: Boolean = false, // 是否为本地模型
    val localModelPath: String = "",
    val supportsStreaming: Boolean = true,
    val supportsTools: Boolean = true,
    val maxTokens: Int = 65536,
    val contextWindow: Int = 262144,
) {
    enum class HealthStatus {
        UNKNOWN,    // 未检测
        HEALTHY,    // 健康
        DEGRADED,   // 降级（响应慢或部分失败）
        UNHEALTHY,  // 不健康
        FAILED      // 失败
    }
    
    fun toDisplayString(): String {
        return buildString {
            append(name)
            if (customProviderName.isNotBlank()) append(" ($customProviderName)")
            append(" - ${providerType.name}")
            if (isLocal) append(" [本地]")
            append(" [优先级: $priority]")
            append(" [${healthStatus.name}]")
        }
    }
}

/**
 * 故障转移策略
 */
enum class FailoverStrategy {
    FAST_FAIL,          // 快速失败：立即切换到下一个提供商
    EXPONENTIAL_BACKOFF, // 指数退避：等待一段时间后重试当前提供商
    CIRCUIT_BREAKER     // 断路器：连续失败达到阈值后暂停该提供商
}

/**
 * 故障转移配置
 */
data class FailoverConfig(
    val enabled: Boolean = true,
    val strategy: FailoverStrategy = FailoverStrategy.FAST_FAIL,
    val maxRetries: Int = 3,           // 最大重试次数
    val retryDelayMs: Long = 1000L,    // 重试延迟（毫秒）
    val maxRetryDelayMs: Long = 30000L, // 最大重试延迟（毫秒）
    val healthCheckIntervalMs: Long = 300000L, // 健康检查间隔（5分钟）
    val failureThreshold: Int = 3,      // 连续失败阈值（触发断路器）
    val recoveryTimeMs: Long = 60000L,  // 恢复时间（断路器恢复时间）
    val timeoutMs: Long = 30000L,       // 请求超时（毫秒）
)

/**
 * 提供商健康检查结果
 */
data class ProviderHealthCheckResult(
    val providerId: String,
    val timestamp: Long,
    val isHealthy: Boolean,
    val responseTimeMs: Long,
    val errorMessage: String? = null,
    val modelCount: Int = 0,
)

/**
 * 故障转移状态
 */
data class FailoverState(
    val currentProviderId: String? = null,
    val failoverCount: Int = 0,
    val lastFailoverTime: Long = 0L,
    val consecutiveFailures: Int = 0,
    val isCircuitOpen: Boolean = false,
    val circuitOpenUntil: Long = 0L,
)

/**
 * 多提供商配置仓库
 * 
 * 管理多个提供商配置，支持优先级排序、健康检查和故障转移。
 */
class QuroMultiProviderRepository(context: Context) {
    private val prefs: SharedPreferences = 
        context.getSharedPreferences("quro_multi_provider_config", Context.MODE_PRIVATE)
    
    private val configs = mutableListOf<QuroProviderConfig>()
    private val healthHistory = mutableListOf<ProviderHealthCheckResult>()
    
    init {
        loadConfigs()
    }
    
    fun getAllConfigs(): List<QuroProviderConfig> = configs.toList()
    
    fun getEnabledConfigs(): List<QuroProviderConfig> = 
        configs.filter { it.enabled }.sortedBy { it.priority }
    
    fun getConfigById(id: String): QuroProviderConfig? = 
        configs.firstOrNull { it.id == id }
    
    fun addConfig(config: QuroProviderConfig) {
        val existing = configs.indexOfFirst { it.id == config.id }
        if (existing >= 0) {
            configs[existing] = config
        } else {
            configs.add(config)
        }
        saveConfigs()
    }
    
    fun updateConfig(config: QuroProviderConfig) {
        val index = configs.indexOfFirst { it.id == config.id }
        if (index >= 0) {
            configs[index] = config
            saveConfigs()
        }
    }
    
    fun removeConfig(id: String) {
        configs.removeAll { it.id == id }
        saveConfigs()
    }
    
    fun updateHealthStatus(id: String, status: QuroProviderConfig.HealthStatus, error: String = "") {
        val index = configs.indexOfFirst { it.id == id }
        if (index >= 0) {
            val config = configs[index]
            configs[index] = config.copy(
                healthStatus = status,
                lastHealthCheck = System.currentTimeMillis(),
                consecutiveFailures = if (status == QuroProviderConfig.HealthStatus.FAILED) 
                    config.consecutiveFailures + 1 else 0,
                lastError = error
            )
            saveConfigs()
        }
    }
    
    fun recordHealthCheck(result: ProviderHealthCheckResult) {
        healthHistory.add(result)
        // 保留最近100条记录
        if (healthHistory.size > 100) {
            healthHistory.removeAt(0)
        }
    }
    
    fun getHealthHistory(providerId: String): List<ProviderHealthCheckResult> {
        return healthHistory.filter { it.providerId == providerId }
    }
    
    fun getNextProvider(currentId: String?): QuroProviderConfig? {
        val enabledConfigs = getEnabledConfigs()
        if (enabledConfigs.isEmpty()) return null
        
        if (currentId == null) {
            return enabledConfigs.firstOrNull()
        }
        
        val currentIndex = enabledConfigs.indexOfFirst { it.id == currentId }
        if (currentIndex < 0) {
            return enabledConfigs.firstOrNull()
        }
        
        // 尝试下一个提供商
        for (i in 1..enabledConfigs.size) {
            val nextIndex = (currentIndex + i) % enabledConfigs.size
            val nextConfig = enabledConfigs[nextIndex]
            
            // 跳过不健康的提供商（除非没有其他选择）
            if (nextConfig.healthStatus == QuroProviderConfig.HealthStatus.FAILED) {
                continue
            }
            
            return nextConfig
        }
        
        // 所有提供商都不健康，返回第一个
        return enabledConfigs.firstOrNull()
    }
    
    fun getFailoverConfig(): FailoverConfig {
        val json = prefs.getString(KEY_FAILOVER_CONFIG, null) ?: return FailoverConfig()
        return runCatching {
            val obj = JSONObject(json)
            FailoverConfig(
                enabled = obj.optBoolean("enabled", true),
                strategy = FailoverStrategy.valueOf(obj.optString("strategy", "FAST_FAIL")),
                maxRetries = obj.optInt("maxRetries", 3),
                retryDelayMs = obj.optLong("retryDelayMs", 1000L),
                maxRetryDelayMs = obj.optLong("maxRetryDelayMs", 30000L),
                healthCheckIntervalMs = obj.optLong("healthCheckIntervalMs", 300000L),
                failureThreshold = obj.optInt("failureThreshold", 3),
                recoveryTimeMs = obj.optLong("recoveryTimeMs", 60000L),
                timeoutMs = obj.optLong("timeoutMs", 30000L),
            )
        }.getOrNull() ?: FailoverConfig()
    }
    
    fun saveFailoverConfig(config: FailoverConfig) {
        prefs.edit {
            val obj = JSONObject().apply {
                put("enabled", config.enabled)
                put("strategy", config.strategy.name)
                put("maxRetries", config.maxRetries)
                put("retryDelayMs", config.retryDelayMs)
                put("maxRetryDelayMs", config.maxRetryDelayMs)
                put("healthCheckIntervalMs", config.healthCheckIntervalMs)
                put("failureThreshold", config.failureThreshold)
                put("recoveryTimeMs", config.recoveryTimeMs)
                put("timeoutMs", config.timeoutMs)
            }
            putString(KEY_FAILOVER_CONFIG, obj.toString())
        }
    }
    
    fun getFailoverState(): FailoverState {
        val json = prefs.getString(KEY_FAILOVER_STATE, null) ?: return FailoverState()
        return runCatching {
            val obj = JSONObject(json)
            FailoverState(
                currentProviderId = obj.optString("currentProviderId", null),
                failoverCount = obj.optInt("failoverCount", 0),
                lastFailoverTime = obj.optLong("lastFailoverTime", 0L),
                consecutiveFailures = obj.optInt("consecutiveFailures", 0),
                isCircuitOpen = obj.optBoolean("isCircuitOpen", false),
                circuitOpenUntil = obj.optLong("circuitOpenUntil", 0L),
            )
        }.getOrNull() ?: FailoverState()
    }
    
    fun saveFailoverState(state: FailoverState) {
        prefs.edit {
            val obj = JSONObject().apply {
                put("currentProviderId", state.currentProviderId ?: "")
                put("failoverCount", state.failoverCount)
                put("lastFailoverTime", state.lastFailoverTime)
                put("consecutiveFailures", state.consecutiveFailures)
                put("isCircuitOpen", state.isCircuitOpen)
                put("circuitOpenUntil", state.circuitOpenUntil)
            }
            putString(KEY_FAILOVER_STATE, obj.toString())
        }
    }
    
    private fun loadConfigs() {
        configs.clear()
        val json = prefs.getString(KEY_PROVIDER_CONFIGS, null) ?: return
        
        runCatching {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                configs.add(parseConfig(obj))
            }
        }
    }
    
    private fun saveConfigs() {
        prefs.edit {
            val arr = JSONArray()
            configs.forEach { arr.put(serializeConfig(it)) }
            putString(KEY_PROVIDER_CONFIGS, arr.toString())
        }
    }
    
    private fun parseConfig(obj: JSONObject): QuroProviderConfig {
        return QuroProviderConfig(
            id = obj.optString("id", ""),
            name = obj.optString("name", ""),
            providerType = ApiProviderType.fromProviderTypeId(obj.optString("providerType", "OPENAI")) 
                ?: ApiProviderType.OPENAI,
            baseUrl = obj.optString("baseUrl", ""),
            apiKey = obj.optString("apiKey", ""),
            defaultModel = obj.optString("defaultModel", ""),
            priority = obj.optInt("priority", 0),
            enabled = obj.optBoolean("enabled", true),
            healthStatus = QuroProviderConfig.HealthStatus.valueOf(
                obj.optString("healthStatus", "UNKNOWN")
            ),
            lastHealthCheck = obj.optLong("lastHealthCheck", 0L),
            consecutiveFailures = obj.optInt("consecutiveFailures", 0),
            lastError = obj.optString("lastError", ""),
            requiresApiKey = obj.optBoolean("requiresApiKey", true),
            customProviderName = obj.optString("customProviderName", ""),
            avatar = obj.optString("avatar", null).ifBlank { null },
            isLocal = obj.optBoolean("isLocal", false),
            localModelPath = obj.optString("localModelPath", ""),
            supportsStreaming = obj.optBoolean("supportsStreaming", true),
            supportsTools = obj.optBoolean("supportsTools", true),
            maxTokens = obj.optInt("maxTokens", 65536),
            contextWindow = obj.optInt("contextWindow", 262144),
        )
    }
    
    private fun serializeConfig(config: QuroProviderConfig): JSONObject {
        return JSONObject().apply {
            put("id", config.id)
            put("name", config.name)
            put("providerType", config.providerType.name)
            put("baseUrl", config.baseUrl)
            put("apiKey", config.apiKey)
            put("defaultModel", config.defaultModel)
            put("priority", config.priority)
            put("enabled", config.enabled)
            put("healthStatus", config.healthStatus.name)
            put("lastHealthCheck", config.lastHealthCheck)
            put("consecutiveFailures", config.consecutiveFailures)
            put("lastError", config.lastError)
            put("requiresApiKey", config.requiresApiKey)
            put("customProviderName", config.customProviderName)
            if (config.avatar != null) put("avatar", config.avatar)
            put("isLocal", config.isLocal)
            put("localModelPath", config.localModelPath)
            put("supportsStreaming", config.supportsStreaming)
            put("supportsTools", config.supportsTools)
            put("maxTokens", config.maxTokens)
            put("contextWindow", config.contextWindow)
        }
    }
    
    companion object {
        private const val KEY_PROVIDER_CONFIGS = "provider_configs"
        private const val KEY_FAILOVER_CONFIG = "failover_config"
        private const val KEY_FAILOVER_STATE = "failover_state"
    }
}
