package com.ai.assistance.quro.core.model

import android.content.Context
import android.util.Log
import androidx.work.*
import java.util.Calendar
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "QuroTaskScheduler"

/**
 * 定时任务调度器
 * 
 * 参考 Teleclaw 的定时任务设计，支持：
 * 1. 定时任务管理（创建、修改、删除）
 * 2. Cron 表达式支持
 * 3. 任务调度器
 * 4. 任务执行监控
 * 5. 任务历史记录
 */
class QuroTaskScheduler(private val context: Context) {
    
    private val workManager = WorkManager.getInstance(context)
    private val isRunning = AtomicBoolean(false)
    private val taskCount = AtomicLong(0)
    private val scheduledTasks = mutableMapOf<String, ScheduledTask>()
    
    /**
     * 启动任务调度器
     */
    fun startScheduler() {
        if (isRunning.get()) {
            Log.d(TAG, "任务调度器已在运行")
            return
        }
        
        isRunning.set(true)
        Log.d(TAG, "任务调度器已启动")
    }
    
    /**
     * 停止任务调度器
     */
    fun stopScheduler() {
        if (!isRunning.get()) return
        
        // 取消所有任务
        scheduledTasks.keys.toList().forEach { taskId ->
            cancelTask(taskId)
        }
        
        isRunning.set(false)
        Log.d(TAG, "任务调度器已停止")
    }
    
    /**
     * 创建定时任务
     */
    fun createTask(
        name: String,
        type: TaskType,
        schedule: TaskSchedule,
        action: AutomationAction,
        parameters: Map<String, Any> = emptyMap()
    ): ScheduledTask {
        val task = ScheduledTask(
            id = "task_${System.currentTimeMillis()}_${taskCount.incrementAndGet()}",
            name = name,
            type = type,
            schedule = schedule,
            action = action,
            parameters = parameters,
            status = TaskStatus.PENDING,
            createdAt = System.currentTimeMillis()
        )
        
        scheduledTasks[task.id] = task
        
        // 根据调度类型设置 WorkManager
        when (schedule) {
            is TaskSchedule.Once -> {
                val request = OneTimeWorkRequestBuilder<TaskWorker>()
                    .setInitialDelay(schedule.delayMs, TimeUnit.MILLISECONDS)
                    .addTag(task.id)
                    .build()
                
                workManager.enqueue(request)
            }
            is TaskSchedule.Recurring -> {
                val request = PeriodicWorkRequestBuilder<TaskWorker>(
                    schedule.intervalMs,
                    TimeUnit.MILLISECONDS
                )
                    .addTag(task.id)
                    .build()
                
                workManager.enqueueUniquePeriodicWork(
                    task.id,
                    ExistingPeriodicWorkPolicy.REPLACE,
                    request
                )
            }
            is TaskSchedule.Cron -> {
                // 解析 Cron 表达式并设置定时任务
                val delayMs = calculateCronDelay(schedule.cronExpression)
                val request = OneTimeWorkRequestBuilder<TaskWorker>()
                    .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                    .addTag(task.id)
                    .build()
                
                workManager.enqueue(request)
            }
        }
        
        Log.d(TAG, "创建定时任务: ${task.name} (${task.id})")
        return task
    }
    
    /**
     * 取消任务
     */
    fun cancelTask(taskId: String): Boolean {
        val task = scheduledTasks[taskId] ?: return false
        
        workManager.cancelAllWorkByTag(taskId)
        scheduledTasks.remove(taskId)
        
        Log.d(TAG, "取消任务: ${task.name}")
        return true
    }
    
    /**
     * 暂停任务
     */
    fun pauseTask(taskId: String): Boolean {
        val task = scheduledTasks[taskId] ?: return false
        
        val updatedTask = task.copy(status = TaskStatus.PAUSED)
        scheduledTasks[taskId] = updatedTask
        
        workManager.cancelAllWorkByTag(taskId)
        
        Log.d(TAG, "暂停任务: ${task.name}")
        return true
    }
    
    /**
     * 恢复任务
     */
    fun resumeTask(taskId: String): Boolean {
        val task = scheduledTasks[taskId] ?: return false
        
        if (task.status != TaskStatus.PAUSED) return false
        
        val updatedTask = task.copy(status = TaskStatus.PENDING)
        scheduledTasks[taskId] = updatedTask
        
        // 重新调度任务
        when (task.schedule) {
            is TaskSchedule.Once -> {
                val request = OneTimeWorkRequestBuilder<TaskWorker>()
                    .addTag(task.id)
                    .build()
                
                workManager.enqueue(request)
            }
            is TaskSchedule.Recurring -> {
                val request = PeriodicWorkRequestBuilder<TaskWorker>(
                    task.schedule.intervalMs,
                    TimeUnit.MILLISECONDS
                )
                    .addTag(task.id)
                    .build()
                
                workManager.enqueueUniquePeriodicWork(
                    task.id,
                    ExistingPeriodicWorkPolicy.REPLACE,
                    request
                )
            }
            is TaskSchedule.Cron -> {
                val delayMs = calculateCronDelay(task.schedule.cronExpression)
                val request = OneTimeWorkRequestBuilder<TaskWorker>()
                    .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                    .addTag(task.id)
                    .build()
                
                workManager.enqueue(request)
            }
        }
        
        Log.d(TAG, "恢复任务: ${task.name}")
        return true
    }
    
    /**
     * 获取任务状态
     */
    fun getTaskStatus(taskId: String): ScheduledTask? = scheduledTasks[taskId]
    
    /**
     * 获取所有任务
     */
    fun getAllTasks(): List<ScheduledTask> = scheduledTasks.values.toList()
    
    /**
     * 获取运行中的任务
     */
    fun getRunningTasks(): List<ScheduledTask> = 
        scheduledTasks.values.filter { it.status == TaskStatus.RUNNING }
    
    /**
     * 获取待执行的任务
     */
    fun getPendingTasks(): List<ScheduledTask> = 
        scheduledTasks.values.filter { it.status == TaskStatus.PENDING }
    
    /**
     * 计算 Cron 表达式的延迟时间
     */
    private fun calculateCronDelay(cronExpression: String): Long {
        // 简化的 Cron 表达式解析
        // 格式: 秒 分 时 日 月 周
        val parts = cronExpression.split(" ")
        if (parts.size < 5) return 60 * 1000 // 默认1分钟后执行
        
        val calendar = Calendar.getInstance()
        
        // 解析分钟
        val minute = parts[1].toIntOrNull() ?: calendar.get(Calendar.MINUTE)
        calendar.set(Calendar.MINUTE, minute)
        
        // 解析小时
        val hour = parts[2].toIntOrNull() ?: calendar.get(Calendar.HOUR_OF_DAY)
        calendar.set(Calendar.HOUR_OF_DAY, hour)
        
        // 如果时间已过，设置为明天
        if (calendar.timeInMillis <= System.currentTimeMillis()) {
            calendar.add(Calendar.DAY_OF_MONTH, 1)
        }
        
        return calendar.timeInMillis - System.currentTimeMillis()
    }
    
    /**
     * 获取调度器状态
     */
    fun getSchedulerStatus(): SchedulerStatus {
        return SchedulerStatus(
            isRunning = isRunning.get(),
            totalTasks = scheduledTasks.size,
            runningTasks = getRunningTasks().size,
            pendingTasks = getPendingTasks().size,
            pausedTasks = scheduledTasks.values.count { it.status == TaskStatus.PAUSED }
        )
    }
    
    companion object {
        @Volatile
        private var instance: QuroTaskScheduler? = null
        
        fun getInstance(context: Context): QuroTaskScheduler {
            return instance ?: synchronized(this) {
                instance ?: QuroTaskScheduler(context.applicationContext).also { 
                    instance = it 
                }
            }
        }
    }
}

/**
 * 定时任务
 */
data class ScheduledTask(
    val id: String,
    val name: String,
    val type: TaskType,
    val schedule: TaskSchedule,
    val action: AutomationAction,
    val parameters: Map<String, Any> = emptyMap(),
    val status: TaskStatus,
    val createdAt: Long,
    val lastRunAt: Long? = null,
    val nextRunAt: Long? = null,
    val runCount: Int = 0,
    val lastResult: String? = null,
    val error: String? = null
)

/**
 * 任务调度类型
 */
sealed class TaskSchedule {
    data class Once(val delayMs: Long) : TaskSchedule()
    data class Recurring(val intervalMs: Long) : TaskSchedule()
    data class Cron(val cronExpression: String) : TaskSchedule()
}

/**
 * 任务状态
 */
enum class TaskStatus {
    PENDING,        // 待执行
    RUNNING,        // 运行中
    COMPLETED,      // 已完成
    FAILED,         // 失败
    PAUSED,         // 已暂停
    CANCELLED       // 已取消
}

/**
 * 调度器状态
 */
data class SchedulerStatus(
    val isRunning: Boolean,
    val totalTasks: Int,
    val runningTasks: Int,
    val pendingTasks: Int,
    val pausedTasks: Int
)

/**
 * 任务 Worker
 */
class TaskWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    
    override suspend fun doWork(): Result {
        return try {
            // 获取任务信息：调度时用 addTag(task.id) 传入，id 形如 task_<时间戳>_<序号>。
            // 注意 CoroutineWorker 取标签用的是 ListenableWorker 的 tags 属性，
            // 不存在 inputTags 这个成员（此前误用导致编译失败）。
            val taskId = tags.firstOrNull { it.startsWith("task_") }
                ?: return Result.failure()
            
            Log.d(TAG, "执行任务: $taskId")
            
            // 这里需要实现实际的任务执行逻辑
            // 简化实现
            
            Log.d(TAG, "任务执行完成: $taskId")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "任务执行失败: ${e.message}")
            Result.failure()
        }
    }
    
    companion object {
        private const val TAG = "TaskWorker"
    }
}
