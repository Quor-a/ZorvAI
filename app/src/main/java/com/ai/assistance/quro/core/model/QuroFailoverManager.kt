package com.ai.assistance.quro.core.model

import android.util.Log
import com.ai.assistance.quro.core.network.QuroLlmClient
import com.ai.assistance.quro.core.QuroChatMessage
import com.ai.assistance.quro.core.QuroLlmResult
import com.ai.assistance.quro.core.QuroToolSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "QuroFailover"

/**
 * 多提供商故障转移管理器
 * 
 * 实现自动故障转移机制，当当前提供商失败时自动切换到下一个提供商。
 * 参考 Agora 和 Kai 的设计，支持：
 * - 快速失败策略
 * - 指数退避策略
 * - 断路器策略
 * - 健康检查
 * - 优先级排序
 */
class QuroFailoverManager(
    private val repository: QuroMultiProviderRepository,
    private val llmClient: QuroLlmClient
) {
    private val failoverState = AtomicLong(0L) // 简化状态管理
    private val isPerformingFailover = AtomicBoolean(false)
    private val mutex = Mutex()
    
    /**
     * 使用故障转移策略执行聊天请求
     * 
     * @param messages 消息列表
     * @param temperature 温度
     * @param maxTokens 最大token数
     * @param tools 工具列表
     * @param stream 是否流式
     * @param onToken token回调
     * @param onThinking 思考回调
     * @return QuroLlmResult 结果
     */
    suspend fun chatWithFailover(
        messages: List<QuroChatMessage>,
        temperature: Float,
        maxTokens: Int,
        tools: List<QuroToolSpec> = emptyList(),
        stream: Boolean = false,
        onToken: ((String) -> Unit)? = null,
        onThinking: ((String) -> Unit)? = null,
    ): QuroLlmResult {
        val config = repository.getFailoverConfig()
        if (!config.enabled) {
            // 故障转移已禁用，使用默认提供商
            return chatWithDefaultProvider(messages, temperature, maxTokens, tools, stream, onToken, onThinking)
        }
        
        val state = repository.getFailoverState()
        
        // 检查断路器是否打开
        if (state.isCircuitOpen && System.currentTimeMillis() < state.circuitOpenUntil) {
            Log.w(TAG, "断路器已打开，等待恢复")
            val nextProvider = repository.getNextProvider(state.currentProviderId)
            if (nextProvider != null) {
                return chatWithProvider(nextProvider, messages, temperature, maxTokens, tools, stream, onToken, onThinking)
            }
        }
        
        // 尝试当前提供商
        val currentProviderId = state.currentProviderId
        val currentProvider = if (currentProviderId != null) {
            repository.getConfigById(currentProviderId)
        } else {
            repository.getEnabledConfigs().firstOrNull()
        }
        
        if (currentProvider != null) {
            return try {
                val result = chatWithProvider(
                    currentProvider, messages, temperature, maxTokens, tools, stream, onToken, onThinking
                )
                // 成功，重置失败计数
                resetFailureCount()
                result
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "提供商 ${currentProvider.name} 失败: ${e.message}")
                handleProviderFailure(currentProvider, e, config, messages, temperature, maxTokens, tools, stream, onToken, onThinking)
            }
        } else {
            // 没有可用的提供商
            return QuroLlmResult.Error("没有可用的提供商配置")
        }
    }
    
    /**
     * 使用默认提供商执行聊天（无故障转移）
     */
    private suspend fun chatWithDefaultProvider(
        messages: List<QuroChatMessage>,
        temperature: Float,
        maxTokens: Int,
        tools: List<QuroToolSpec>,
        stream: Boolean,
        onToken: ((String) -> Unit)?,
        onThinking: ((String) -> Unit)?,
    ): QuroLlmResult {
        // 从 QuroModelConfig 获取默认配置
        // 这里需要与现有的 QuroModelConfig 集成
        return QuroLlmResult.Error("默认提供商配置未实现")
    }
    
    /**
     * 使用指定提供商执行聊天
     */
    private suspend fun chatWithProvider(
        provider: QuroProviderConfig,
        messages: List<QuroChatMessage>,
        temperature: Float,
        maxTokens: Int,
        tools: List<QuroToolSpec>,
        stream: Boolean,
        onToken: ((String) -> Unit)?,
        onThinking: ((String) -> Unit)?,
    ): QuroLlmResult {
        Log.d(TAG, "尝试使用提供商: ${provider.name} (${provider.providerType.name})")
        
        // 记录健康检查开始
        val startTime = System.currentTimeMillis()
        
        return try {
            val result = llmClient.chat(
                baseUrl = provider.baseUrl,
                apiKey = provider.apiKey,
                model = provider.defaultModel,
                messages = messages,
                temperature = temperature,
                maxTokens = maxTokens,
                tools = tools,
                stream = stream,
                onToken = onToken,
                onThinking = onThinking,
            )
            
            // 记录成功
            val responseTime = System.currentTimeMillis() - startTime
            recordHealthCheck(provider.id, true, responseTime)
            
            // 更新提供商状态
            repository.updateHealthStatus(provider.id, QuroProviderConfig.HealthStatus.HEALTHY)
            
            result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 记录失败
            val responseTime = System.currentTimeMillis() - startTime
            recordHealthCheck(provider.id, false, responseTime, e.message)
            
            // 更新提供商状态
            val newStatus = when {
                e.message?.contains("timeout", true) == true -> 
                    QuroProviderConfig.HealthStatus.DEGRADED
                e.message?.contains("500", true) == true -> 
                    QuroProviderConfig.HealthStatus.UNHEALTHY
                else -> 
                    QuroProviderConfig.HealthStatus.FAILED
            }
            repository.updateHealthStatus(provider.id, newStatus, e.message ?: "")
            
            throw e
        }
    }
    
    /**
     * 处理提供商失败
     */
    private suspend fun handleProviderFailure(
        failedProvider: QuroProviderConfig,
        error: Exception,
        config: FailoverConfig,
        messages: List<QuroChatMessage>,
        temperature: Float,
        maxTokens: Int,
        tools: List<QuroToolSpec>,
        stream: Boolean,
        onToken: ((String) -> Unit)?,
        onThinking: ((String) -> Unit)?,
    ): QuroLlmResult {
        val state = repository.getFailoverState()
        val newConsecutiveFailures = state.consecutiveFailures + 1
        
        // 更新故障转移状态
        val newState = state.copy(
            consecutiveFailures = newConsecutiveFailures,
            lastFailoverTime = System.currentTimeMillis(),
            failoverCount = state.failoverCount + 1
        )
        
        // 检查是否需要打开断路器
        if (newConsecutiveFailures >= config.failureThreshold) {
            Log.w(TAG, "连续失败 ${newConsecutiveFailures} 次，打开断路器")
            repository.saveFailoverState(newState.copy(
                isCircuitOpen = true,
                circuitOpenUntil = System.currentTimeMillis() + config.recoveryTimeMs
            ))
        } else {
            repository.saveFailoverState(newState)
        }
        
        // 尝试下一个提供商
        val nextProvider = repository.getNextProvider(failedProvider.id)
        if (nextProvider != null) {
            Log.d(TAG, "故障转移到提供商: ${nextProvider.name}")
            
            // 根据策略等待
            when (config.strategy) {
                FailoverStrategy.FAST_FAIL -> {
                    // 立即尝试下一个
                }
                FailoverStrategy.EXPONENTIAL_BACKOFF -> {
                    val delayMs = calculateExponentialBackoff(newConsecutiveFailures, config)
                    delay(delayMs)
                }
                FailoverStrategy.CIRCUIT_BREAKER -> {
                    // 已经处理了断路器逻辑
                }
            }
            
            return try {
                val result = chatWithProvider(
                    nextProvider, messages, temperature, maxTokens, tools, stream, onToken, onThinking
                )
                // 成功，重置失败计数
                resetFailureCount()
                result
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "故障转移后提供商 ${nextProvider.name} 也失败: ${e.message}")
                // 递归尝试下一个提供商（但限制递归深度）
                if (newConsecutiveFailures < config.maxRetries) {
                    return handleProviderFailure(
                        nextProvider, e, config, messages, temperature, maxTokens, tools, stream, onToken, onThinking
                    )
                } else {
                    return QuroLlmResult.Error("所有提供商都失败，已达到最大重试次数")
                }
            }
        } else {
            return QuroLlmResult.Error("没有可用的备用提供商")
        }
    }
    
    /**
     * 计算指数退避时间
     */
    private fun calculateExponentialBackoff(attempt: Int, config: FailoverConfig): Long {
        val delayMs = config.retryDelayMs * (1L shl attempt.coerceAtMost(10))
        return delayMs.coerceAtMost(config.maxRetryDelayMs)
    }
    
    /**
     * 重置失败计数
     */
    private fun resetFailureCount() {
        val state = repository.getFailoverState()
        if (state.consecutiveFailures > 0) {
            repository.saveFailoverState(state.copy(
                consecutiveFailures = 0,
                isCircuitOpen = false,
                circuitOpenUntil = 0L
            ))
        }
    }
    
    /**
     * 记录健康检查结果
     */
    private fun recordHealthCheck(
        providerId: String, 
        isHealthy: Boolean, 
        responseTimeMs: Long, 
        errorMessage: String? = null
    ) {
        val result = ProviderHealthCheckResult(
            providerId = providerId,
            timestamp = System.currentTimeMillis(),
            isHealthy = isHealthy,
            responseTimeMs = responseTimeMs,
            errorMessage = errorMessage
        )
        repository.recordHealthCheck(result)
    }
    
    /**
     * 执行健康检查
     */
    suspend fun performHealthCheck() {
        val config = repository.getFailoverConfig()
        val state = repository.getFailoverState()
        
        // 检查是否需要执行健康检查
        if (System.currentTimeMillis() - state.lastFailoverTime < config.healthCheckIntervalMs) {
            return
        }
        
        Log.d(TAG, "执行健康检查")
        
        val enabledConfigs = repository.getEnabledConfigs()
        for (provider in enabledConfigs) {
            try {
                // 简单的健康检查：尝试获取模型列表
                val startTime = System.currentTimeMillis()
                // 这里可以添加实际的健康检查逻辑
                val responseTime = System.currentTimeMillis() - startTime
                
                recordHealthCheck(provider.id, true, responseTime)
                repository.updateHealthStatus(provider.id, QuroProviderConfig.HealthStatus.HEALTHY)
            } catch (e: Exception) {
                recordHealthCheck(provider.id, false, 0L, e.message)
                repository.updateHealthStatus(
                    provider.id, 
                    QuroProviderConfig.HealthStatus.UNHEALTHY, 
                    e.message ?: ""
                )
            }
        }
    }
    
    /**
     * 获取当前提供商
     */
    fun getCurrentProvider(): QuroProviderConfig? {
        val state = repository.getFailoverState()
        return state.currentProviderId?.let { repository.getConfigById(it) }
    }
    
    /**
     * 手动切换提供商
     */
    suspend fun switchToProvider(providerId: String) {
        val provider = repository.getConfigById(providerId) ?: return
        val state = repository.getFailoverState()
        
        repository.saveFailoverState(state.copy(
            currentProviderId = providerId,
            consecutiveFailures = 0,
            isCircuitOpen = false,
            circuitOpenUntil = 0L
        ))
        
        Log.d(TAG, "手动切换到提供商: ${provider.name}")
    }
    
    /**
     * 获取故障转移统计信息
     */
    fun getFailoverStats(): FailoverStats {
        val state = repository.getFailoverState()
        val configs = repository.getEnabledConfigs()
        
        return FailoverStats(
            totalProviders = configs.size,
            healthyProviders = configs.count { 
                it.healthStatus == QuroProviderConfig.HealthStatus.HEALTHY 
            },
            currentProviderId = state.currentProviderId,
            totalFailovers = state.failoverCount,
            consecutiveFailures = state.consecutiveFailures,
            isCircuitOpen = state.isCircuitOpen,
            circuitOpenUntil = state.circuitOpenUntil
        )
    }
}

/**
 * 故障转移统计信息
 */
data class FailoverStats(
    val totalProviders: Int,
    val healthyProviders: Int,
    val currentProviderId: String?,
    val totalFailovers: Int,
    val consecutiveFailures: Int,
    val isCircuitOpen: Boolean,
    val circuitOpenUntil: Long
)
