package com.ai.assistance.quro.core.tools

import android.content.Context
import com.ai.assistance.quro.core.QuroToolResult
import com.ai.assistance.quro.core.QuroToolSpec
import com.ai.assistance.quro.core.model.QuroDataManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.json.JSONArray

/**
 * 数据管理工具
 * 
 * 提供数据导出、导入、备份、恢复和加密存储功能。
 */
class QuroDataManagerTool(private val context: Context) : QuroTool {
    
    private val dataManager = QuroDataManager.getInstance(context)
    
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
        // 注意：QuroToolSpec 的第三个参数是 parametersJson（JSON Schema 字符串），
        // 不是 mapOf(...) + QuroToolSpec.Parameter(...) 那种 DSL —— 项目只依赖 org.json，
        // 没有参数 DSL，必须直接构造 schema 字符串。
        return QuroToolSpec(
            name = "data_manager",
            description = "管理数据导出、导入、备份、恢复和加密存储。支持 ZIP 格式导出、AES 加密、完整备份和选择性恢复。",
            parametersJson = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("action", JSONObject().apply {
                        put("type", "string")
                        put("description", "操作类型：export=导出数据、import=导入数据、backup=创建备份、restore=从备份恢复、list_backups=列出备份、delete_backup=删除备份")
                        put("enum", JSONArray().apply {
                            put("export"); put("import"); put("backup")
                            put("restore"); put("list_backups"); put("delete_backup")
                        })
                    })
                    put("export_path", JSONObject().apply {
                        put("type", "string"); put("description", "导出文件路径（export 操作可选）")
                    })
                    put("import_path", JSONObject().apply {
                        put("type", "string"); put("description", "导入文件路径（import 操作必需）")
                    })
                    put("backup_name", JSONObject().apply {
                        put("type", "string"); put("description", "备份名称（backup 操作可选）")
                    })
                    put("backup_path", JSONObject().apply {
                        put("type", "string"); put("description", "备份文件路径（restore / delete_backup 操作需要）")
                    })
                    put("include_conversations", JSONObject().apply {
                        put("type", "boolean"); put("description", "是否包含对话数据")
                    })
                    put("include_settings", JSONObject().apply {
                        put("type", "boolean"); put("description", "是否包含设置")
                    })
                    put("include_models", JSONObject().apply {
                        put("type", "boolean"); put("description", "是否包含模型配置")
                    })
                    put("include_tools", JSONObject().apply {
                        put("type", "boolean"); put("description", "是否包含工具配置")
                    })
                    put("encrypt", JSONObject().apply {
                        put("type", "boolean"); put("description", "是否加密导出/备份文件")
                    })
                    put("password", JSONObject().apply {
                        put("type", "string"); put("description", "加密/解密密码（encrypt=true 时必需）")
                    })
                    put("overwrite", JSONObject().apply {
                        put("type", "boolean"); put("description", "导入时是否覆盖已有数据")
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
                val action = args.optString("action", "list_backups")
                
                when (action) {
                    "export" -> exportData(args)
                    "import" -> importData(args)
                    "backup" -> createBackup(args)
                    "restore" -> restoreFromBackup(args)
                    "list_backups" -> listBackups()
                    "delete_backup" -> deleteBackup(args)
                    else -> QuroToolResult.Error("未知操作: $action")
                }
            } catch (e: Exception) {
                QuroToolResult.Error("执行操作失败: ${e.message}")
            }
        }
    }
    
    /**
     * 导出数据
     */
    private suspend fun exportData(args: JSONObject): QuroToolResult {
        val exportPath = args.optString("export_path", null)
        val includeConversations = args.optBoolean("include_conversations", true)
        val includeSettings = args.optBoolean("include_settings", true)
        val includeModels = args.optBoolean("include_models", true)
        val includeTools = args.optBoolean("include_tools", true)
        val encrypt = args.optBoolean("encrypt", false)
        val password = args.optString("password", null)
        
        val result = dataManager.exportData(
            exportPath = exportPath,
            includeConversations = includeConversations,
            includeSettings = includeSettings,
            includeModels = includeModels,
            includeTools = includeTools,
            encrypt = encrypt,
            password = password
        )
        
        return when (result) {
            is com.ai.assistance.quro.core.model.ExportResult.Success -> {
                val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                QuroToolResult.Success(
                    "数据导出成功!\n" +
                    "文件路径: ${result.filePath}\n" +
                    "文件大小: ${result.fileSize / 1024}KB\n" +
                    "已加密: ${result.encrypted}\n" +
                    "导出时间: ${dateFormat.format(Date(result.timestamp))}"
                )
            }
            is com.ai.assistance.quro.core.model.ExportResult.Error -> {
                QuroToolResult.Error(result.message)
            }
        }
    }
    
    /**
     * 导入数据
     */
    private suspend fun importData(args: JSONObject): QuroToolResult {
        val importPath = args.optString("import_path", "")
        if (importPath.isBlank()) {
            return QuroToolResult.Error("导入文件路径不能为空")
        }
        
        val password = args.optString("password", null)
        val overwrite = args.optBoolean("overwrite", false)
        
        val result = dataManager.importData(importPath, password, overwrite)
        
        return when (result) {
            is com.ai.assistance.quro.core.model.ImportResult.Success -> {
                val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                QuroToolResult.Success(
                    "数据导入成功!\n" +
                    "导入时间: ${dateFormat.format(Date(result.importedAt))}\n" +
                    "覆盖模式: ${result.overwrite}"
                )
            }
            is com.ai.assistance.quro.core.model.ImportResult.Error -> {
                QuroToolResult.Error(result.message)
            }
        }
    }
    
    /**
     * 创建备份
     */
    private suspend fun createBackup(args: JSONObject): QuroToolResult {
        val backupName = args.optString("backup_name", null)
        val encrypt = args.optBoolean("encrypt", false)
        val password = args.optString("password", null)
        
        val result = dataManager.createBackup(backupName, encrypt, password)
        
        return when (result) {
            is com.ai.assistance.quro.core.model.BackupResult.Success -> {
                val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                QuroToolResult.Success(
                    "备份创建成功!\n" +
                    "备份路径: ${result.backupPath}\n" +
                    "备份大小: ${result.backupSize / 1024}KB\n" +
                    "创建时间: ${dateFormat.format(Date(result.createdAt))}\n" +
                    "已加密: ${result.encrypted}"
                )
            }
            is com.ai.assistance.quro.core.model.BackupResult.Error -> {
                QuroToolResult.Error(result.message)
            }
        }
    }
    
    /**
     * 从备份恢复
     */
    private suspend fun restoreFromBackup(args: JSONObject): QuroToolResult {
        val backupPath = args.optString("backup_path", "")
        if (backupPath.isBlank()) {
            return QuroToolResult.Error("备份文件路径不能为空")
        }
        
        val password = args.optString("password", null)
        val overwrite = args.optBoolean("overwrite", true)
        
        val result = dataManager.restoreFromBackup(backupPath, password, overwrite)
        
        return when (result) {
            is com.ai.assistance.quro.core.model.RestoreResult.Success -> {
                val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                QuroToolResult.Success(
                    "备份恢复成功!\n" +
                    "恢复时间: ${dateFormat.format(Date(result.restoredAt))}\n" +
                    "覆盖模式: ${result.overwrite}"
                )
            }
            is com.ai.assistance.quro.core.model.RestoreResult.Error -> {
                QuroToolResult.Error(result.message)
            }
        }
    }
    
    /**
     * 列出备份
     */
    private fun listBackups(): QuroToolResult {
        val backups = dataManager.getBackupList()
        
        if (backups.isEmpty()) {
            return QuroToolResult.Success("没有备份文件")
        }
        
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        
        val result = StringBuilder("备份列表:\n")
        backups.forEachIndexed { index, backup ->
            result.append("${index + 1}. ${backup.name}\n")
            result.append("   路径: ${backup.path}\n")
            result.append("   大小: ${backup.size / 1024}KB\n")
            result.append("   创建时间: ${dateFormat.format(Date(backup.createdAt))}\n")
            result.append("   已加密: ${backup.encrypted}\n")
            result.append("\n")
        }
        
        return QuroToolResult.Success(result.toString())
    }
    
    /**
     * 删除备份
     */
    private fun deleteBackup(args: JSONObject): QuroToolResult {
        val backupPath = args.optString("backup_path", "")
        if (backupPath.isBlank()) {
            return QuroToolResult.Error("备份文件路径不能为空")
        }
        
        val success = dataManager.deleteBackup(backupPath)
        
        return if (success) {
            QuroToolResult.Success("备份已删除: $backupPath")
        } else {
            QuroToolResult.Error("删除备份失败，文件可能不存在或路径无效")
        }
    }
}
