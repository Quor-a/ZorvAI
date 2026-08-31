package com.ai.assistance.quro.core.tools

import android.content.Context
import com.ai.assistance.quro.core.QuroToolResult
import com.ai.assistance.quro.core.QuroToolSpec
import com.ai.assistance.quro.core.model.QuroTaskScheduler
import com.ai.assistance.quro.core.model.TaskType
import com.ai.assistance.quro.core.model.TaskSchedule
import com.ai.assistance.quro.core.model.AutomationAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.json.JSONArray

/**
 * 定时任务调度工具
 * 
 * 提供定时任务的管理功能，包括创建、修改、删除、暂停、恢复等。
 */
class QuroTaskSchedulerTool(private val context: Context) : QuroTool {
    
    private val scheduler = QuroTaskScheduler.getInstance(context)
    
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
            name = "task_scheduler",
            description = "管理定时任务调度，支持创建、修改、删除、暂停、恢复定时任务。支持 Cron 表达式和多种调度方式。",
            // QuroToolSpec 第三个参数是 parametersJson（JSON Schema 字符串）。项目只依赖 org.json，
            // 没有 parameters=mapOf(...) + QuroToolSpec.Parameter(...) 这套 DSL，必须手写 schema。
            parametersJson = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("action", JSONObject().apply {
                        put("type", "string")
                        put("description", "操作类型：start（启动调度器）、stop（停止调度器）、status（查看状态）、create（创建任务）、cancel（取消任务）、pause（暂停任务）、resume（恢复任务）、list（列出任务）、list_running（列出运行中任务）、list_pending（列出待执行任务）")
                    })
                    put("task_name", JSONObject().apply {
                        put("type", "string")
                        put("description", "任务名称（create操作需要）")
                    })
                    put("task_type", JSONObject().apply {
                        put("type", "string")
                        put("description", "任务类型：SCREENSHOT、UI_AUTOMATION、APP_LAUNCH、APP_CONTROL、DATA_EXTRACTION、CUSTOM")
                    })
                    put("schedule_type", JSONObject().apply {
                        put("type", "string")
                        put("description", "调度类型：once（一次性）、recurring（周期性）、cron（Cron表达式）")
                    })
                    put("delay_ms", JSONObject().apply {
                        put("type", "integer")
                        put("description", "延迟时间（毫秒）（once调度类型需要）")
                    })
                    put("interval_ms", JSONObject().apply {
                        put("type", "integer")
                        put("description", "间隔时间（毫秒）（recurring调度类型需要）")
                    })
                    put("cron_expression", JSONObject().apply {
                        put("type", "string")
                        put("description", "Cron表达式（cron调度类型需要）")
                    })
                    put("task_id", JSONObject().apply {
                        put("type", "string")
                        put("description", "任务ID（cancel、pause、resume操作需要）")
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
                    "start" -> startScheduler()
                    "stop" -> stopScheduler()
                    "status" -> getSchedulerStatus()
                    "create" -> createTask(args)
                    "cancel" -> cancelTask(args)
                    "pause" -> pauseTask(args)
                    "resume" -> resumeTask(args)
                    "list" -> listTasks()
                    "list_running" -> listRunningTasks()
                    "list_pending" -> listPendingTasks()
                    else -> QuroToolResult.Error("未知操作: $action")
                }
            } catch (e: Exception) {
                QuroToolResult.Error("执行操作失败: ${e.message}")
            }
        }
    }
    
    /**
     * 启动调度器
     */
    private fun startScheduler(): QuroToolResult {
        scheduler.startScheduler()
        return QuroToolResult.Success("任务调度器已启动")
    }
    
    /**
     * 停止调度器
     */
    private fun stopScheduler(): QuroToolResult {
        scheduler.stopScheduler()
        return QuroToolResult.Success("任务调度器已停止")
    }
    
    /**
     * 获取调度器状态
     */
    private fun getSchedulerStatus(): QuroToolResult {
        val status = scheduler.getSchedulerStatus()
        
        val result = StringBuilder("任务调度器状态:\n")
        result.append("运行状态: ${if (status.isRunning) "运行中" else "已停止"}\n")
        result.append("总任务数: ${status.totalTasks}\n")
        result.append("运行中任务: ${status.runningTasks}\n")
        result.append("待执行任务: ${status.pendingTasks}\n")
        result.append("已暂停任务: ${status.pausedTasks}\n")
        
        return QuroToolResult.Success(result.toString())
    }
    
    /**
     * 创建任务
     */
    private fun createTask(args: JSONObject): QuroToolResult {
        val taskName = args.optString("task_name", "新任务")
        val taskTypeStr = args.optString("task_type", "SCREENSHOT")
        val scheduleTypeStr = args.optString("schedule_type", "once")
        
        val taskType = try {
            TaskType.valueOf(taskTypeStr.uppercase())
        } catch (e: IllegalArgumentException) {
            TaskType.CUSTOM
        }
        
        val schedule = when (scheduleTypeStr.lowercase()) {
            "once" -> {
                val delayMs = args.optLong("delay_ms", 60000) // 默认1分钟后
                TaskSchedule.Once(delayMs)
            }
            "recurring" -> {
                val intervalMs = args.optLong("intervalMs", 3600000) // 默认1小时
                TaskSchedule.Recurring(intervalMs)
            }
            "cron" -> {
                val cronExpression = args.optString("cron_expression", "0 * * * *") // 默认每小时
                TaskSchedule.Cron(cronExpression)
            }
            else -> TaskSchedule.Once(60000)
        }
        
        // 根据任务类型创建相应的操作
        val action = when (taskType) {
            TaskType.SCREENSHOT -> AutomationAction.TakeScreenshot("screenshot_${System.currentTimeMillis()}.png")
            TaskType.UI_AUTOMATION -> AutomationAction.Click(500, 500)
            TaskType.APP_LAUNCH -> AutomationAction.LaunchApp("com.example.app")
            else -> AutomationAction.Custom("custom_action", emptyMap())
        }
        
        val task = scheduler.createTask(taskName, taskType, schedule, action)
        
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        
        val result = StringBuilder("任务创建成功!\n")
        result.append("任务ID: ${task.id}\n")
        result.append("任务名称: ${task.name}\n")
        result.append("任务类型: ${task.type.name}\n")
        result.append("调度类型: ${scheduleTypeStr}\n")
        
        when (schedule) {
            is TaskSchedule.Once -> {
                result.append("执行时间: ${dateFormat.format(Date(System.currentTimeMillis() + schedule.delayMs))}\n")
            }
            is TaskSchedule.Recurring -> {
                result.append("间隔时间: ${schedule.intervalMs / 1000 / 60} 分钟\n")
            }
            is TaskSchedule.Cron -> {
                result.append("Cron表达式: ${schedule.cronExpression}\n")
            }
        }
        
        return QuroToolResult.Success(result.toString())
    }
    
    /**
     * 取消任务
     */
    private fun cancelTask(args: JSONObject): QuroToolResult {
        val taskId = args.optString("task_id", "")
        if (taskId.isBlank()) {
            return QuroToolResult.Error("任务ID不能为空")
        }
        
        val success = scheduler.cancelTask(taskId)
        
        return if (success) {
            QuroToolResult.Success("任务已取消: $taskId")
        } else {
            QuroToolResult.Error("取消任务失败，任务可能不存在")
        }
    }
    
    /**
     * 暂停任务
     */
    private fun pauseTask(args: JSONObject): QuroToolResult {
        val taskId = args.optString("task_id", "")
        if (taskId.isBlank()) {
            return QuroToolResult.Error("任务ID不能为空")
        }
        
        val success = scheduler.pauseTask(taskId)
        
        return if (success) {
            QuroToolResult.Success("任务已暂停: $taskId")
        } else {
            QuroToolResult.Error("暂停任务失败，任务可能不存在或未在运行")
        }
    }
    
    /**
     * 恢复任务
     */
    private fun resumeTask(args: JSONObject): QuroToolResult {
        val taskId = args.optString("task_id", "")
        if (taskId.isBlank()) {
            return QuroToolResult.Error("任务ID不能为空")
        }
        
        val success = scheduler.resumeTask(taskId)
        
        return if (success) {
            QuroToolResult.Success("任务已恢复: $taskId")
        } else {
            QuroToolResult.Error("恢复任务失败，任务可能不存在或未暂停")
        }
    }
    
    /**
     * 列出所有任务
     */
    private fun listTasks(): QuroToolResult {
        val tasks = scheduler.getAllTasks()
        
        if (tasks.isEmpty()) {
            return QuroToolResult.Success("没有创建的任务")
        }
        
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        
        val result = StringBuilder("任务列表:\n")
        tasks.forEachIndexed { index, task ->
            result.append("${index + 1}. ${task.name}\n")
            result.append("   ID: ${task.id}\n")
            result.append("   类型: ${task.type.name}\n")
            result.append("   状态: ${task.status.name}\n")
            result.append("   创建时间: ${dateFormat.format(Date(task.createdAt))}\n")
            if (task.lastRunAt != null) {
                result.append("   上次运行: ${dateFormat.format(Date(task.lastRunAt))}\n")
            }
            if (task.runCount > 0) {
                result.append("   运行次数: ${task.runCount}\n")
            }
            if (task.error != null) {
                result.append("   错误: ${task.error}\n")
            }
            result.append("\n")
        }
        
        return QuroToolResult.Success(result.toString())
    }
    
    /**
     * 列出运行中的任务
     */
    private fun listRunningTasks(): QuroToolResult {
        val tasks = scheduler.getRunningTasks()
        
        if (tasks.isEmpty()) {
            return QuroToolResult.Success("没有运行中的任务")
        }
        
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        
        val result = StringBuilder("运行中的任务:\n")
        tasks.forEachIndexed { index, task ->
            result.append("${index + 1}. ${task.name}\n")
            result.append("   ID: ${task.id}\n")
            result.append("   类型: ${task.type.name}\n")
            if (task.lastRunAt != null) {
                result.append("   上次运行: ${dateFormat.format(Date(task.lastRunAt))}\n")
            }
            result.append("\n")
        }
        
        return QuroToolResult.Success(result.toString())
    }
    
    /**
     * 列出待执行的任务
     */
    private fun listPendingTasks(): QuroToolResult {
        val tasks = scheduler.getPendingTasks()
        
        if (tasks.isEmpty()) {
            return QuroToolResult.Success("没有待执行的任务")
        }
        
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        
        val result = StringBuilder("待执行的任务:\n")
        tasks.forEachIndexed { index, task ->
            result.append("${index + 1}. ${task.name}\n")
            result.append("   ID: ${task.id}\n")
            result.append("   类型: ${task.type.name}\n")
            result.append("   创建时间: ${dateFormat.format(Date(task.createdAt))}\n")
            if (task.nextRunAt != null) {
                result.append("   下次运行: ${dateFormat.format(Date(task.nextRunAt))}\n")
            }
            result.append("\n")
        }
        
        return QuroToolResult.Success(result.toString())
    }
}
