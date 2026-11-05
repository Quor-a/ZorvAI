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
    
    /** ⚠️ 惰性获取（#9）：构造期取 WorkManager 会让未初始化 WorkManager 的副进程崩溃，见 QuroHealthCheckService 注释。 */
    private val workManager by lazy { WorkManager.getInstance(context.applicationContext) }
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
        
        // 把动作序列化进 inputData，让 TaskWorker（可能在不同进程/重启后恢复）能真正执行，
        // 而非只能拿到一个 task_ 前缀的 tag 干瞪眼。
        val actionData = serializeAction(action)
        
        // 根据调度类型设置 WorkManager
        when (schedule) {
            is TaskSchedule.Once -> {
                val request = OneTimeWorkRequestBuilder<TaskWorker>()
                    .setInitialDelay(schedule.delayMs, TimeUnit.MILLISECONDS)
                    .setInputData(actionData)
                    .addTag(task.id)
                    .build()
                
                workManager.enqueue(request)
            }
            is TaskSchedule.Recurring -> {
                val request = PeriodicWorkRequestBuilder<TaskWorker>(
                    schedule.intervalMs,
                    TimeUnit.MILLISECONDS
                )
                    .setInputData(actionData)
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
                    .setInputData(actionData)
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
        val actionData = serializeAction(task.action)
        when (task.schedule) {
            is TaskSchedule.Once -> {
                val request = OneTimeWorkRequestBuilder<TaskWorker>()
                    .setInputData(actionData)
                    .addTag(task.id)
                    .build()
                
                workManager.enqueue(request)
            }
            is TaskSchedule.Recurring -> {
                val request = PeriodicWorkRequestBuilder<TaskWorker>(
                    task.schedule.intervalMs,
                    TimeUnit.MILLISECONDS
                )
                    .setInputData(actionData)
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
                    .setInputData(actionData)
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
            val taskId = tags.firstOrNull { it.startsWith("task_") }
            Log.d(TAG, "执行任务: $taskId")
            
            // 从 inputData 反序列化动作并真正执行（此前是空壳，动作从未被消费）。
            val action = deserializeAction(inputData)
            val outcome = if (action != null) {
                executeAction(applicationContext, action)
            } else {
                "（未携带动作信息，跳过执行）"
            }
            Log.d(TAG, "任务执行完成: $taskId → $outcome")
            Result.success(workDataOf("outcome" to outcome))
        } catch (e: Exception) {
            Log.e(TAG, "任务执行失败: ${e.message}")
            Result.failure()
        }
    }
    
    companion object {
        private const val TAG = "TaskWorker"
    }
}

/** 把 [AutomationAction] 序列化为 WorkManager 的 [Data]（跨进程可恢复）。 */
internal fun serializeAction(action: AutomationAction): Data {
    val b = Data.Builder()
        .putString("action_type", action.javaClass.simpleName)
    when (action) {
        is AutomationAction.Click -> {
            b.putInt("x", action.x).putInt("y", action.y)
        }
        is AutomationAction.LongClick -> {
            b.putInt("x", action.x).putInt("y", action.y)
        }
        is AutomationAction.Swipe -> {
            b.putInt("sx", action.startX).putInt("sy", action.startY)
                .putInt("ex", action.endX).putInt("ey", action.endY)
                .putLong("dur", action.durationMs)
        }
        is AutomationAction.InputText -> b.putString("text", action.text)
        is AutomationAction.KeyEvent -> b.putInt("keycode", action.keyCode)
        is AutomationAction.LaunchApp -> b.putString("pkg", action.packageName)
        is AutomationAction.WaitForElement -> b.putLong("timeout", action.timeoutMs)
        is AutomationAction.TakeScreenshot -> b.putString("path", action.savePath)
        is AutomationAction.Custom -> {
            b.putString("custom", action.action)
            action.parameters.forEach { (k, v) -> b.putString("p_$k", v.toString()) }
        }
    }
    return b.build()
}

/** 从 [Data] 反序列化回 [AutomationAction]。 */
internal fun deserializeAction(data: Data): AutomationAction? {
    val type = data.getString("action_type") ?: return null
    return when (type) {
        "Click" -> AutomationAction.Click(data.getInt("x", 0), data.getInt("y", 0))
        "LongClick" -> AutomationAction.LongClick(data.getInt("x", 0), data.getInt("y", 0))
        "Swipe" -> AutomationAction.Swipe(
            data.getInt("sx", 0), data.getInt("sy", 0),
            data.getInt("ex", 0), data.getInt("ey", 0), data.getLong("dur", 300),
        )
        "InputText" -> AutomationAction.InputText(data.getString("text") ?: "")
        "KeyEvent" -> AutomationAction.KeyEvent(data.getInt("keycode", 0))
        "LaunchApp" -> AutomationAction.LaunchApp(data.getString("pkg") ?: "")
        "WaitForElement" -> AutomationAction.WaitForElement(data.getLong("timeout", 5000))
        "TakeScreenshot" -> AutomationAction.TakeScreenshot(data.getString("path") ?: "")
        "Custom" -> AutomationAction.Custom(data.getString("custom") ?: "custom_action", emptyMap())
        else -> null
    }
}

/**
 * 在 Worker 里真正执行一个动作。
 *
 * 覆盖「无复杂环境依赖、可直接落地」的类型（LaunchApp / TakeScreenshot），
 * 其余需虚拟屏/无障碍的类型给出明确反馈，而非静默吞掉。
 */
internal suspend fun executeAction(context: Context, action: AutomationAction): String {
    return when (action) {
        is AutomationAction.LaunchApp -> {
            runCatching {
                val intent = context.packageManager.getLaunchIntentForPackage(action.packageName)
                if (intent == null) {
                    "应用未安装或无启动入口: ${action.packageName}"
                } else {
                    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    "已启动应用 ${action.packageName}"
                }
            }.getOrElse { "启动应用失败: ${it.message}" }
        }
        is AutomationAction.TakeScreenshot -> {
            // 截图依赖 MediaProjection 授权 + 虚拟屏，后台 Worker 无前台 UI 无法弹授权，
            // 尝试走虚拟屏截图，失败则明确反馈。
            runCatching {
                val bmp = QuroVirtualDisplay.getInstance(context).takeScreenshot()
                if (bmp == null) "虚拟屏未就绪，无法截图（需先在前台启动虚拟屏）"
                else "截图成功（${bmp.width}x${bmp.height}）"
            }.getOrElse { "截图失败: ${it.message}" }
        }
        is AutomationAction.Click -> "该动作（点击 ${action.x},${action.y}）需虚拟屏/无障碍环境，后台定时暂不支持，请在前台执行"
        is AutomationAction.LongClick -> "该动作（长按）需虚拟屏/无障碍环境，后台定时暂不支持"
        is AutomationAction.Swipe -> "该动作（滑动）需虚拟屏/无障碍环境，后台定时暂不支持"
        is AutomationAction.InputText -> "该动作（输入文本）需无障碍/键盘环境，后台定时暂不支持"
        is AutomationAction.KeyEvent -> "该动作（按键）需无障碍/虚拟屏环境，后台定时暂不支持"
        is AutomationAction.WaitForElement -> "该动作（等待元素）需虚拟屏环境，后台定时暂不支持"
        is AutomationAction.Custom -> "自定义动作「${action.action}」后台定时暂不支持"
    }
}
