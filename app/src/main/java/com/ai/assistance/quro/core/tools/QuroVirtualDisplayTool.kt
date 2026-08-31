package com.ai.assistance.quro.core.tools

import android.content.Context
import com.ai.assistance.quro.core.QuroToolResult
import com.ai.assistance.quro.core.QuroToolSpec
import com.ai.assistance.quro.core.model.QuroVirtualDisplay
import com.ai.assistance.quro.core.model.QuroBackgroundAutomation
import com.ai.assistance.quro.core.model.TaskType
import com.ai.assistance.quro.core.model.AutomationAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlinx.coroutines.runBlocking
import org.json.JSONArray

/**
 * 虚拟显示器与后台自动化工具
 * 
 * 提供虚拟显示器管理和后台自动化功能。
 */
class QuroVirtualDisplayTool(private val context: Context) : QuroTool {
    
    private val virtualDisplay = QuroVirtualDisplay.getInstance(context)
    private val automation = QuroBackgroundAutomation.getInstance(context)
    
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
            name = "virtual_display",
            description = "管理虚拟显示器和后台自动化，支持启动/停止虚拟显示器、创建和执行自动化任务、截图等。",
            // QuroToolSpec 第三个参数是 parametersJson（JSON Schema 字符串）。项目只依赖 org.json，
            // 没有 parameters=mapOf(...) + QuroToolSpec.Parameter(...) 这套 DSL，必须手写 schema。
            parametersJson = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("action", JSONObject().apply {
                        put("type", "string")
                        put("description", "操作类型：start_display（启动虚拟显示器）、stop_display（停止虚拟显示器）、display_status（显示器状态）、screenshot（截图）、start_automation（启动自动化）、stop_automation（停止自动化）、create_task（创建任务）、execute_task（执行任务）、list_tasks（列出任务）、check_support（检查支持）")
                    })
                    put("display_name", JSONObject().apply {
                        put("type", "string")
                        put("description", "虚拟显示器名称")
                    })
                    put("width", JSONObject().apply {
                        put("type", "integer")
                        put("description", "显示器宽度")
                    })
                    put("height", JSONObject().apply {
                        put("type", "integer")
                        put("description", "显示器高度")
                    })
                    put("task_name", JSONObject().apply {
                        put("type", "string")
                        put("description", "任务名称")
                    })
                    put("task_type", JSONObject().apply {
                        put("type", "string")
                        put("description", "任务类型：SCREENSHOT、UI_AUTOMATION、APP_LAUNCH、APP_CONTROL、DATA_EXTRACTION、CUSTOM")
                    })
                    put("task_id", JSONObject().apply {
                        put("type", "string")
                        put("description", "任务ID")
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
                val action = args.optString("action", "display_status")
                
                when (action) {
                    "start_display" -> startDisplay(args)
                    "stop_display" -> stopDisplay()
                    "display_status" -> getDisplayStatus()
                    "screenshot" -> takeScreenshot()
                    "start_automation" -> startAutomation()
                    "stop_automation" -> stopAutomation()
                    "create_task" -> createTask(args)
                    "execute_task" -> executeTask(args)
                    "cancel_task" -> cancelTask(args)
                    "list_tasks" -> listTasks()
                    "check_support" -> checkSupport()
                    else -> QuroToolResult.Error("未知操作: $action")
                }
            } catch (e: Exception) {
                QuroToolResult.Error("执行操作失败: ${e.message}")
            }
        }
    }
    
    /**
     * 启动虚拟显示器
     */
    private fun startDisplay(args: JSONObject): QuroToolResult {
        val displayName = args.optString("display_name", "ZorvAI虚拟显示器")
        val width = args.optInt("width", 1080)
        val height = args.optInt("height", 1920)
        
        virtualDisplay.setDisplaySize(width, height)
        val success = virtualDisplay.start(displayName)
        
        return if (success) {
            QuroToolResult.Success(
                "虚拟显示器启动成功!\n" +
                "名称: $displayName\n" +
                "尺寸: ${width}x${height}"
            )
        } else {
            QuroToolResult.Error("虚拟显示器启动失败")
        }
    }
    
    /**
     * 停止虚拟显示器
     */
    private fun stopDisplay(): QuroToolResult {
        virtualDisplay.stop()
        return QuroToolResult.Success("虚拟显示器已停止")
    }
    
    /**
     * 获取显示器状态
     */
    private fun getDisplayStatus(): QuroToolResult {
        val status = virtualDisplay.getDisplayStatus()
        
        val result = StringBuilder("虚拟显示器状态:\n")
        result.append("运行状态: ${if (status.isRunning) "运行中" else "已停止"}\n")
        result.append("显示器ID: ${status.displayId}\n")
        result.append("屏幕尺寸: ${status.screenWidth}x${status.screenHeight}\n")
        result.append("DPI: ${status.screenDpi}\n")
        result.append("Surface: ${if (status.surface != null) "已创建" else "未创建"}\n")
        
        return QuroToolResult.Success(result.toString())
    }
    
    /**
     * 截取屏幕
     */
    private fun takeScreenshot(): QuroToolResult {
        val bitmap = virtualDisplay.takeScreenshot()
        
        return if (bitmap != null) {
            // 这里需要实际保存截图并返回路径
            QuroToolResult.Success(
                "屏幕截图成功!\n" +
                "尺寸: ${bitmap.width}x${bitmap.height}\n" +
                "格式: ARGB_8888"
            )
        } else {
            QuroToolResult.Error("屏幕截图失败")
        }
    }
    
    /**
     * 启动后台自动化
     */
    private fun startAutomation(): QuroToolResult {
        val success = automation.start()
        
        return if (success) {
            QuroToolResult.Success("后台自动化已启动")
        } else {
            QuroToolResult.Error("后台自动化启动失败，可能设备不支持虚拟显示器")
        }
    }
    
    /**
     * 停止后台自动化
     */
    private fun stopAutomation(): QuroToolResult {
        automation.stop()
        return QuroToolResult.Success("后台自动化已停止")
    }
    
    /**
     * 创建任务
     */
    private fun createTask(args: JSONObject): QuroToolResult {
        val taskName = args.optString("task_name", "新任务")
        val taskTypeStr = args.optString("task_type", "SCREENSHOT")
        
        val taskType = try {
            TaskType.valueOf(taskTypeStr.uppercase())
        } catch (e: IllegalArgumentException) {
            TaskType.CUSTOM
        }
        
        // 根据任务类型创建相应的操作
        val action = when (taskType) {
            TaskType.SCREENSHOT -> AutomationAction.TakeScreenshot("screenshot_${System.currentTimeMillis()}.png")
            TaskType.UI_AUTOMATION -> AutomationAction.Click(500, 500) // 示例点击
            TaskType.APP_LAUNCH -> AutomationAction.LaunchApp("com.example.app")
            else -> AutomationAction.Custom("custom_action", emptyMap())
        }
        
        val task = automation.createTask(taskName, taskType, action)
        
        return QuroToolResult.Success(
            "任务创建成功!\n" +
            "任务ID: ${task.id}\n" +
            "任务名称: ${task.name}\n" +
            "任务类型: ${task.type.name}"
        )
    }
    
    /**
     * 执行任务
     */
    private suspend fun executeTask(args: JSONObject): QuroToolResult {
        val taskId = args.optString("task_id", "")
        if (taskId.isBlank()) {
            return QuroToolResult.Error("任务ID不能为空")
        }
        
        val result = automation.executeTask(taskId)
        
        return when (result) {
            is com.ai.assistance.quro.core.model.AutomationResult.Success -> {
                QuroToolResult.Success("任务执行成功: ${result.data}")
            }
            is com.ai.assistance.quro.core.model.AutomationResult.Error -> {
                QuroToolResult.Error(result.message)
            }
            is com.ai.assistance.quro.core.model.AutomationResult.Timeout -> {
                QuroToolResult.Error(result.message)
            }
        }
    }
    
    /**
     * 取消任务
     */
    private fun cancelTask(args: JSONObject): QuroToolResult {
        val taskId = args.optString("task_id", "")
        if (taskId.isBlank()) {
            return QuroToolResult.Error("任务ID不能为空")
        }
        
        val success = automation.cancelTask(taskId)
        
        return if (success) {
            QuroToolResult.Success("任务已取消: $taskId")
        } else {
            QuroToolResult.Error("取消任务失败，任务可能不存在或未在运行")
        }
    }
    
    /**
     * 列出所有任务
     */
    private fun listTasks(): QuroToolResult {
        val tasks = automation.getAllTasks()
        
        if (tasks.isEmpty()) {
            return QuroToolResult.Success("没有创建的任务")
        }
        
        val result = StringBuilder("任务列表:\n")
        tasks.forEachIndexed { index, task ->
            result.append("${index + 1}. ${task.name}\n")
            result.append("   ID: ${task.id}\n")
            result.append("   类型: ${task.type.name}\n")
            result.append("   状态: ${task.status.name}\n")
            if (task.error != null) {
                result.append("   错误: ${task.error}\n")
            }
            result.append("\n")
        }
        
        return QuroToolResult.Success(result.toString())
    }
    
    /**
     * 检查支持
     */
    private fun checkSupport(): QuroToolResult {
        val isSupported = virtualDisplay.isSupported()
        val displays = virtualDisplay.getAvailableDisplays()
        
        val result = StringBuilder("虚拟显示器支持检查:\n")
        result.append("设备支持: ${if (isSupported) "是" else "否"}\n")
        result.append("可用显示器: ${displays.size}\n")
        
        displays.forEach { display ->
            result.append("- ${display.name} (${display.width}x${display.height} @ ${display.dpi}dpi)\n")
            if (display.isDefault) {
                result.append("  [默认显示器]\n")
            }
        }
        
        return QuroToolResult.Success(result.toString())
    }
}
