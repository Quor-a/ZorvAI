package com.ai.assistance.quro.core.model

import android.content.Context
import android.util.Log
import androidx.work.*
import java.util.concurrent.TimeUnit

private const val TAG = "QuroHealthCheck"

/**
 * 提供商健康检查服务
 * 
 * 定期检查所有启用的提供商的健康状态，更新健康状态。
 * 使用 WorkManager 实现后台定期任务。
 */
class QuroHealthCheckService(private val context: Context) {
    private val workManager = WorkManager.getInstance(context)
    private val repository = QuroMultiProviderRepository(context)
    
    /**
     * 启动定期健康检查
     */
    fun startPeriodicHealthCheck() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        
        val healthCheckRequest = PeriodicWorkRequestBuilder<HealthCheckWorker>(
            repeatInterval = 15,
            repeatIntervalTimeUnit = TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                WorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS
            )
            .addTag(TAG)
            .build()
        
        workManager.enqueueUniquePeriodicWork(
            TAG,
            ExistingPeriodicWorkPolicy.KEEP,
            healthCheckRequest
        )
        
        Log.d(TAG, "启动定期健康检查")
    }
    
    /**
     * 停止定期健康检查
     */
    fun stopPeriodicHealthCheck() {
        workManager.cancelUniqueWork(TAG)
        Log.d(TAG, "停止定期健康检查")
    }
    
    /**
     * 立即执行一次健康检查
     */
    suspend fun performImmediateHealthCheck() {
        val configs = repository.getEnabledConfigs()
        Log.d(TAG, "执行即时健康检查，${configs.size} 个提供商")
        
        for (config in configs) {
            try {
                // 这里可以添加实际的健康检查逻辑
                // 例如：尝试获取模型列表或发送简单的测试请求
                val isHealthy = checkProviderHealth(config)
                
                if (isHealthy) {
                    repository.updateHealthStatus(
                        config.id, 
                        QuroProviderConfig.HealthStatus.HEALTHY
                    )
                } else {
                    repository.updateHealthStatus(
                        config.id, 
                        QuroProviderConfig.HealthStatus.UNHEALTHY,
                        "健康检查失败"
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "检查提供商 ${config.name} 健康状态失败: ${e.message}")
                repository.updateHealthStatus(
                    config.id, 
                    QuroProviderConfig.HealthStatus.FAILED,
                    e.message ?: "未知错误"
                )
            }
        }
    }
    
    /**
     * 检查单个提供商的健康状态
     */
    private suspend fun checkProviderHealth(config: QuroProviderConfig): Boolean {
        // 实现实际的健康检查逻辑
        // 这里可以：
        // 1. 尝试获取模型列表
        // 2. 发送简单的测试请求
        // 3. 检查API密钥有效性
        
        // 简单实现：检查配置是否完整
        return config.baseUrl.isNotBlank() && 
               (config.apiKey.isNotBlank() || !config.requiresApiKey) &&
               config.defaultModel.isNotBlank()
    }
    
    /**
     * 获取健康检查历史
     */
    fun getHealthCheckHistory(providerId: String): List<ProviderHealthCheckResult> {
        return repository.getHealthHistory(providerId)
    }
    
    /**
     * 获取所有提供商的健康状态摘要
     */
    fun getHealthSummary(): HealthSummary {
        val configs = repository.getAllConfigs()
        val enabledConfigs = configs.filter { it.enabled }
        
        return HealthSummary(
            totalProviders = configs.size,
            enabledProviders = enabledConfigs.size,
            healthyProviders = enabledConfigs.count { 
                it.healthStatus == QuroProviderConfig.HealthStatus.HEALTHY 
            },
            degradedProviders = enabledConfigs.count { 
                it.healthStatus == QuroProviderConfig.HealthStatus.DEGRADED 
            },
            unhealthyProviders = enabledConfigs.count { 
                it.healthStatus == QuroProviderConfig.HealthStatus.UNHEALTHY 
            },
            failedProviders = enabledConfigs.count { 
                it.healthStatus == QuroProviderConfig.HealthStatus.FAILED 
            },
            unknownProviders = enabledConfigs.count { 
                it.healthStatus == QuroProviderConfig.HealthStatus.UNKNOWN 
            },
            lastHealthCheck = enabledConfigs.maxOfOrNull { it.lastHealthCheck } ?: 0L
        )
    }
}

/**
 * WorkManager Worker for health checks
 */
class HealthCheckWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    
    override suspend fun doWork(): Result {
        return try {
            val service = QuroHealthCheckService(applicationContext)
            service.performImmediateHealthCheck()
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "健康检查失败: ${e.message}")
            Result.retry()
        }
    }
    
    companion object {
        private const val TAG = "HealthCheckWorker"
    }
}

/**
 * 健康检查摘要
 */
data class HealthSummary(
    val totalProviders: Int,
    val enabledProviders: Int,
    val healthyProviders: Int,
    val degradedProviders: Int,
    val unhealthyProviders: Int,
    val failedProviders: Int,
    val unknownProviders: Int,
    val lastHealthCheck: Long
) {
    fun getHealthPercentage(): Float {
        if (enabledProviders == 0) return 0f
        return (healthyProviders.toFloat() / enabledProviders) * 100f
    }
    
    fun getStatusDescription(): String {
        return buildString {
            append("提供商健康状态: ")
            append("$healthyProviders/$enabledProviders 健康")
            if (degradedProviders > 0) append(", $degradedProviders 降级")
            if (unhealthyProviders > 0) append(", $unhealthyProviders 不健康")
            if (failedProviders > 0) append(", $failedProviders 失败")
            if (unknownProviders > 0) append(", $unknownProviders 未知")
        }
    }
}
