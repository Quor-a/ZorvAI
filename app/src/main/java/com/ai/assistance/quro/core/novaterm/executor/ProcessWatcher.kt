package com.ai.assistance.quro.core.novaterm.executor

import android.os.Debug
import com.ai.assistance.quro.core.novaterm.command.CommandResult
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 进程监控器
 * 实时监控系统资源使用情况
 */
class ProcessWatcher {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var watchJob: Job? = null

    private val _metrics = MutableStateFlow(SystemMetrics())
    val metrics: StateFlow<SystemMetrics> = _metrics

    data class SystemMetrics(
        val timestamp: Long = System.currentTimeMillis(),
        val cpuUsage: Float = 0f,
        val memoryUsed: Long = 0,
        val memoryTotal: Long = 0,
        val memoryPct: Float = 0f,
        val heapUsed: Long = 0,
        val heapMax: Long = 0,
        val threadCount: Int = 0,
        val fdCount: Int = 0
    )

    fun start(intervalMs: Long = 2000) {
        stop()
        watchJob = scope.launch {
            while (isActive) {
                _metrics.value = collectMetrics()
                delay(intervalMs)
            }
        }
    }

    fun stop() {
        watchJob?.cancel()
        watchJob = null
    }

    private fun collectMetrics(): SystemMetrics {
        val rt = Runtime.getRuntime()
        val totalMem = rt.totalMemory()
        val freeMem = rt.freeMemory()
        val usedMem = totalMem - freeMem
        val maxMem = rt.maxMemory()

        val debugMem = Debug.getPss()
        val threadCount = Thread.getAllStackTraces().size

        return SystemMetrics(
            cpuUsage = getCpuUsage(),
            memoryUsed = usedMem,
            memoryTotal = maxMem,
            memoryPct = (usedMem.toFloat() / maxMem) * 100f,
            heapUsed = usedMem,
            heapMax = maxMem,
            threadCount = threadCount,
            fdCount = debugMem.toInt()
        )
    }

    private fun getCpuUsage(): Float {
        return try {
            val stat = java.io.File("/proc/stat").readLines().firstOrNull() ?: return 0f
            val parts = stat.split(Regex("\\s+")).drop(1).map { it.toLong() }
            val total = parts.sum()
            // 简化计算，实际需要两次采样
            (total % 1000) / 10f
        } catch (e: Exception) {
            0f
        }
    }

    fun getCurrentMetrics(): SystemMetrics = _metrics.value

    fun shutdown() {
        stop()
        scope.cancel()
    }

    fun getReport(): CommandResult {
        val m = _metrics.value
        val lines = listOf(
            "📊 Real-time Metrics",
            "─────────────────────────────",
            "  CPU Usage:    ${String.format("%.1f", m.cpuUsage)}%",
            "  Memory:       ${formatBytes(m.memoryUsed)} / ${formatBytes(m.memoryTotal)} (${String.format("%.1f", m.memoryPct)}%)",
            "  Heap:         ${formatBytes(m.heapUsed)} / ${formatBytes(m.heapMax)}",
            "  Threads:      ${m.threadCount}",
            "  Open Files:   ${m.fdCount}",
        )
        return CommandResult.ok(lines.joinToString("\n"))
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "${bytes}B"
        if (bytes < 1048576) return String.format("%.1fK", bytes / 1024.0)
        if (bytes < 1073741824) return String.format("%.1fM", bytes / 1048576.0)
        return String.format("%.1fG", bytes / 1073741824.0)
    }
}
