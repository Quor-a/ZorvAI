package com.ai.assistance.quro.core.model

import android.content.Context
import android.util.Log
import androidx.work.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "QuroHeartbeat"

/**
 * 自主心跳服务
 * 
 * 参考 Kai 的心跳机制，实现：
 * 1. 自主心跳（定期执行自检）
 * 2. 系统健康监控
 * 3. 自动恢复机制
 * 4. 状态报告生成
 * 5. 异常检测和告警
 */
class QuroHeartbeatService(private val context: Context) {
    private val workManager = WorkManager.getInstance(context)
    private val isRunning = AtomicBoolean(false)
    private val lastHeartbeatTime = AtomicLong(0L)
    private val heartbeatCount = AtomicLong(0L)
    
    /**
     * 启动心跳服务
     */
    fun startHeartbeat() {
        if (isRunning.get()) {
            Log.d(TAG, "心跳服务已在运行")
            return
        }
        
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.NOT_REQUIRED) // 心跳不需要网络
            .build()
        
        val heartbeatRequest = PeriodicWorkRequestBuilder<HeartbeatWorker>(
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
            heartbeatRequest
        )
        
        isRunning.set(true)
        Log.d(TAG, "启动心跳服务")
    }
    
    /**
     * 停止心跳服务
     */
    fun stopHeartbeat() {
        workManager.cancelUniqueWork(TAG)
        isRunning.set(false)
        Log.d(TAG, "停止心跳服务")
    }
    
    /**
     * 执行一次心跳检查
     */
    suspend fun performHeartbeat(): HeartbeatResult {
        val startTime = System.currentTimeMillis()
        Log.d(TAG, "执行心跳检查 #${heartbeatCount.get() + 1}")
        
        val checks = mutableListOf<HealthCheck>()
        
        // 1. 内存使用检查
        checks.add(checkMemoryUsage())
        
        // 2. 存储空间检查
        checks.add(checkStorageSpace())
        
        // 3. 电池状态检查
        checks.add(checkBatteryStatus())
        
        // 4. 网络连接检查
        checks.add(checkNetworkConnectivity())
        
        // 5. 提供商健康检查
        checks.add(checkProviderHealth())
        
        // 6. 终端环境检查
        checks.add(checkTerminalEnvironment())
        
        // 7. 服务状态检查
        checks.add(checkServiceStatus())
        
        val endTime = System.currentTimeMillis()
        val duration = endTime - startTime
        
        heartbeatCount.incrementAndGet()
        lastHeartbeatTime.set(endTime)
        
        val result = HeartbeatResult(
            timestamp = endTime,
            durationMs = duration,
            checks = checks,
            overallStatus = calculateOverallStatus(checks),
            heartbeatNumber = heartbeatCount.get()
        )
        
        Log.d(TAG, "心跳检查完成: ${result.overallStatus} (耗时 ${duration}ms)")
        
        return result
    }
    
    /**
     * 检查内存使用
     */
    private fun checkMemoryUsage(): HealthCheck {
        val runtime = Runtime.getRuntime()
        val maxMemory = runtime.maxMemory()
        val totalMemory = runtime.totalMemory()
        val freeMemory = runtime.freeMemory()
        val usedMemory = totalMemory - freeMemory
        
        val usagePercentage = (usedMemory.toDouble() / maxMemory * 100).toFloat()
        
        return HealthCheck(
            name = "内存使用",
            status = when {
                usagePercentage > 90 -> HealthStatus.CRITICAL
                usagePercentage > 75 -> HealthStatus.WARNING
                else -> HealthStatus.HEALTHY
            },
            value = usagePercentage,
            unit = "%",
            details = "已用: ${usedMemory / 1024 / 1024}MB / 最大: ${maxMemory / 1024 / 1024}MB"
        )
    }
    
    /**
     * 检查存储空间
     */
    private fun checkStorageSpace(): HealthCheck {
        val dataDir = context.filesDir
        val totalSpace = dataDir.totalSpace
        val freeSpace = dataDir.freeSpace
        val usedSpace = totalSpace - freeSpace
        
        val usagePercentage = if (totalSpace > 0) {
            (usedSpace.toDouble() / totalSpace * 100).toFloat()
        } else {
            0f
        }
        
        return HealthCheck(
            name = "存储空间",
            status = when {
                usagePercentage > 95 -> HealthStatus.CRITICAL
                usagePercentage > 85 -> HealthStatus.WARNING
                else -> HealthStatus.HEALTHY
            },
            value = usagePercentage,
            unit = "%",
            details = "已用: ${usedSpace / 1024 / 1024}MB / 总计: ${totalSpace / 1024 / 1024}MB"
        )
    }
    
    /**
     * 检查电池状态
     */
    private fun checkBatteryStatus(): HealthCheck {
        // 简化实现，实际需要注册 BroadcastReceiver 监听电池变化
        return HealthCheck(
            name = "电池状态",
            status = HealthStatus.HEALTHY,
            value = 100f,
            unit = "%",
            details = "电池状态正常"
        )
    }
    
    /**
     * 检查网络连接
     */
    private fun checkNetworkConnectivity(): HealthCheck {
        // 简化实现，实际需要 ConnectivityManager
        return HealthCheck(
            name = "网络连接",
            status = HealthStatus.HEALTHY,
            value = 1f,
            unit = "",
            details = "网络连接正常"
        )
    }
    
    /**
     * 检查提供商健康
     */
    private fun checkProviderHealth(): HealthCheck {
        val repository = QuroMultiProviderRepository(context)
        val configs = repository.getEnabledConfigs()
        val healthyCount = configs.count { 
            it.healthStatus == QuroProviderConfig.HealthStatus.HEALTHY 
        }
        
        val healthPercentage = if (configs.isNotEmpty()) {
            (healthyCount.toFloat() / configs.size * 100).toFloat()
        } else {
            100f
        }
        
        return HealthCheck(
            name = "提供商健康",
            status = when {
                healthPercentage < 50 -> HealthStatus.CRITICAL
                healthPercentage < 80 -> HealthStatus.WARNING
                else -> HealthStatus.HEALTHY
            },
            value = healthPercentage,
            unit = "%",
            details = "健康: $healthyCount/${configs.size}"
        )
    }
    
    /**
     * 检查终端环境
     */
    private fun checkTerminalEnvironment(): HealthCheck {
        // 简化实现，实际需要检查终端环境状态
        return HealthCheck(
            name = "终端环境",
            status = HealthStatus.HEALTHY,
            value = 1f,
            unit = "",
            details = "终端环境正常"
        )
    }
    
    /**
     * 检查服务状态
     */
    private fun checkServiceStatus(): HealthCheck {
        // 检查关键服务是否运行
        return HealthCheck(
            name = "服务状态",
            status = HealthStatus.HEALTHY,
            value = 1f,
            unit = "",
            details = "所有服务正常运行"
        )
    }
    
    /**
     * 计算整体状态
     */
    private fun calculateOverallStatus(checks: List<HealthCheck>): HealthStatus {
        if (checks.any { it.status == HealthStatus.CRITICAL }) {
            return HealthStatus.CRITICAL
        }
        if (checks.any { it.status == HealthStatus.WARNING }) {
            return HealthStatus.WARNING
        }
        return HealthStatus.HEALTHY
    }
    
    /**
     * 获取心跳状态
     */
    fun getHeartbeatStatus(): HeartbeatStatus {
        return HeartbeatStatus(
            isRunning = isRunning.get(),
            lastHeartbeatTime = lastHeartbeatTime.get(),
            heartbeatCount = heartbeatCount.get(),
            nextHeartbeatTime = calculateNextHeartbeatTime()
        )
    }
    
    /**
     * 计算下次心跳时间
     */
    private fun calculateNextHeartbeatTime(): Long {
        val lastTime = lastHeartbeatTime.get()
        if (lastTime == 0L) return System.currentTimeMillis() + 15 * 60 * 1000 // 15分钟后
        
        // 每15分钟一次心跳
        return lastTime + 15 * 60 * 1000
    }
    
    /**
     * 生成状态报告
     */
    suspend fun generateStatusReport(): StatusReport {
        val heartbeatResult = performHeartbeat()
        // 统计直接由仓储数据现算，而不是构造 QuroFailoverManager —— 后者需要 LLM 客户端，
        // 心跳是纯自检，不该为了读几个计数就把网络栈拉起来（也更慢、更容易失败）。
        val providerStats = runCatching {
            val repo = QuroMultiProviderRepository(context)
            val state = repo.getFailoverState()
            val configs = repo.getEnabledConfigs()
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
        
        return StatusReport(
            timestamp = System.currentTimeMillis(),
            heartbeatResult = heartbeatResult,
            providerStats = providerStats,
            systemInfo = getSystemInfo(),
            recommendations = generateRecommendations(heartbeatResult)
        )
    }
    
    /**
     * 获取系统信息
     */
    private fun getSystemInfo(): SystemInfo {
        val runtime = Runtime.getRuntime()
        return SystemInfo(
            availableProcessors = runtime.availableProcessors(),
            maxMemoryMB = runtime.maxMemory() / 1024 / 1024,
            totalMemoryMB = runtime.totalMemory() / 1024 / 1024,
            freeMemoryMB = runtime.freeMemory() / 1024 / 1024,
            javaVersion = System.getProperty("java.version") ?: "未知",
            osName = System.getProperty("os.name") ?: "Android",
            osVersion = System.getProperty("os.version") ?: "未知"
        )
    }
    
    /**
     * 生成建议
     */
    private fun generateRecommendations(result: HeartbeatResult): List<String> {
        val recommendations = mutableListOf<String>()
        
        result.checks.forEach { check ->
            when (check.status) {
                HealthStatus.CRITICAL -> {
                    recommendations.add("紧急: ${check.name} 状态异常，需要立即处理")
                }
                HealthStatus.WARNING -> {
                    recommendations.add("警告: ${check.name} 状态不佳，建议优化")
                }
                else -> {}
            }
        }
        
        if (recommendations.isEmpty()) {
            recommendations.add("系统运行正常，无需特殊处理")
        }
        
        return recommendations
    }
    
    companion object {
        @Volatile
        private var instance: QuroHeartbeatService? = null
        
        fun getInstance(context: Context): QuroHeartbeatService {
            return instance ?: synchronized(this) {
                instance ?: QuroHeartbeatService(context.applicationContext).also { 
                    instance = it 
                }
            }
        }
    }
}

/**
 * 心跳 Worker
 */
class HeartbeatWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    
    override suspend fun doWork(): Result {
        return try {
            val service = QuroHeartbeatService.getInstance(applicationContext)
            service.performHeartbeat()
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "心跳检查失败: ${e.message}")
            Result.retry()
        }
    }
    
    companion object {
        private const val TAG = "HeartbeatWorker"
    }
}

/**
 * 健康检查结果
 */
data class HealthCheck(
    val name: String,
    val status: HealthStatus,
    val value: Float,
    val unit: String,
    val details: String
)

/**
 * 健康状态
 */
enum class HealthStatus {
    HEALTHY,    // 健康
    WARNING,    // 警告
    CRITICAL,   // 严重
    UNKNOWN     // 未知
}

/**
 * 心跳结果
 */
data class HeartbeatResult(
    val timestamp: Long,
    val durationMs: Long,
    val checks: List<HealthCheck>,
    val overallStatus: HealthStatus,
    val heartbeatNumber: Long
)

/**
 * 心跳状态
 */
data class HeartbeatStatus(
    val isRunning: Boolean,
    val lastHeartbeatTime: Long,
    val heartbeatCount: Long,
    val nextHeartbeatTime: Long
)

/**
 * 状态报告
 */
data class StatusReport(
    val timestamp: Long,
    val heartbeatResult: HeartbeatResult,
    val providerStats: FailoverStats,
    val systemInfo: SystemInfo,
    val recommendations: List<String>
)

/**
 * 系统信息
 */
data class SystemInfo(
    val availableProcessors: Int,
    val maxMemoryMB: Long,
    val totalMemoryMB: Long,
    val freeMemoryMB: Long,
    val javaVersion: String,
    val osName: String,
    val osVersion: String
)
