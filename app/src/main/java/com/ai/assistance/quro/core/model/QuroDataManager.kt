package com.ai.assistance.quro.core.model

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.KeyStore
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import android.util.Base64
import java.security.SecureRandom

private const val TAG = "QuroDataManager"

/**
 * 数据管理器
 * 
 * 参考 Agora（.agora ZIP归档）和 Kai（加密存储）的设计，支持：
 * 1. 数据导出功能（对话、设置、模型等）
 * 2. 数据导入功能
 * 3. 加密存储
 * 4. 数据备份和恢复
 * 5. 数据格式转换
 */
class QuroDataManager(private val context: Context) {
    
    private val dataDir = File(context.filesDir, "quro_data")
    private val exportDir = File(context.getExternalFilesDir(null), "quro_exports")
    private val backupDir = File(context.getExternalFilesDir(null), "quro_backups")
    
    init {
        // 确保目录存在
        dataDir.mkdirs()
        exportDir.mkdirs()
        backupDir.mkdirs()
    }
    
    /**
     * 导出数据到 ZIP 文件
     */
    suspend fun exportData(
        exportPath: String? = null,
        includeConversations: Boolean = true,
        includeSettings: Boolean = true,
        includeModels: Boolean = true,
        includeTools: Boolean = true,
        encrypt: Boolean = false,
        password: String? = null
    ): ExportResult = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "开始导出数据")
            
            val timestamp = System.currentTimeMillis()
            val fileName = "zorvai_export_${timestamp}.zip"
            val targetFile = if (exportPath != null) {
                File(exportPath, fileName)
            } else {
                File(exportDir, fileName)
            }
            
            ZipOutputStream(FileOutputStream(targetFile)).use { zipOut ->
                // 导出对话数据
                if (includeConversations) {
                    exportConversations(zipOut)
                }
                
                // 导出设置
                if (includeSettings) {
                    exportSettings(zipOut)
                }
                
                // 导出模型配置
                if (includeModels) {
                    exportModels(zipOut)
                }
                
                // 导出工具配置
                if (includeTools) {
                    exportTools(zipOut)
                }
                
                // 导出元数据
                exportMetadata(zipOut, timestamp)
            }
            
            // 如果需要加密
            if (encrypt && password != null) {
                val encryptedFile = File(targetFile.parent, "${targetFile.nameWithoutExtension}.encrypted")
                encryptFile(targetFile, password, encryptedFile)
                targetFile.delete()
                
                Log.d(TAG, "数据导出并加密完成: ${encryptedFile.absolutePath}")
                return@withContext ExportResult.Success(
                    filePath = encryptedFile.absolutePath,
                    fileSize = encryptedFile.length(),
                    encrypted = true,
                    timestamp = timestamp
                )
            }
            
            Log.d(TAG, "数据导出完成: ${targetFile.absolutePath}")
            ExportResult.Success(
                filePath = targetFile.absolutePath,
                fileSize = targetFile.length(),
                encrypted = false,
                timestamp = timestamp
            )
        } catch (e: Exception) {
            Log.e(TAG, "数据导出失败", e)
            ExportResult.Error("数据导出失败: ${e.message}")
        }
    }
    
    /**
     * 从 ZIP 文件导入数据
     */
    suspend fun importData(
        importPath: String,
        password: String? = null,
        overwrite: Boolean = false
    ): ImportResult = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "开始导入数据: $importPath")
            
            val importFile = File(importPath)
            if (!importFile.exists()) {
                return@withContext ImportResult.Error("导入文件不存在: $importPath")
            }
            
            // 如果是加密文件，先解密
            val zipFile = if (importFile.name.endsWith(".encrypted")) {
                if (password == null) {
                    return@withContext ImportResult.Error("加密文件需要密码")
                }
                
                val decryptedFile = File(importFile.parent, "${importFile.nameWithoutExtension}.zip")
                decryptFile(importFile, password, decryptedFile)
                decryptedFile
            } else {
                importFile
            }
            
            // 解压并导入
            ZipInputStream(FileInputStream(zipFile)).use { zipIn ->
                var entry = zipIn.nextEntry
                while (entry != null) {
                    val entryName = entry.name
                    
                    // 根据文件名判断导入类型
                    when {
                        entryName.startsWith("conversations/") -> {
                            importConversation(zipIn, entryName, overwrite)
                        }
                        entryName.startsWith("settings/") -> {
                            importSetting(zipIn, entryName, overwrite)
                        }
                        entryName.startsWith("models/") -> {
                            importModel(zipIn, entryName, overwrite)
                        }
                        entryName.startsWith("tools/") -> {
                            importTool(zipIn, entryName, overwrite)
                        }
                        entryName == "metadata.json" -> {
                            // 元数据处理
                        }
                    }
                    
                    zipIn.closeEntry()
                    entry = zipIn.nextEntry
                }
            }
            
            // 清理临时文件
            if (zipFile != importFile) {
                zipFile.delete()
            }
            
            Log.d(TAG, "数据导入完成")
            ImportResult.Success(
                importedAt = System.currentTimeMillis(),
                overwrite = overwrite
            )
        } catch (e: Exception) {
            Log.e(TAG, "数据导入失败", e)
            ImportResult.Error("数据导入失败: ${e.message}")
        }
    }
    
    /**
     * 创建备份
     */
    suspend fun createBackup(
        backupName: String? = null,
        encrypt: Boolean = false,
        password: String? = null
    ): BackupResult = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "创建备份")
            
            val timestamp = System.currentTimeMillis()
            val name = backupName ?: "backup_${timestamp}"
            val backupFile = File(backupDir, "$name.zip")
            
            // 使用导出功能创建备份
            val exportResult = exportData(
                exportPath = backupDir.absolutePath,
                includeConversations = true,
                includeSettings = true,
                includeModels = true,
                includeTools = true,
                encrypt = encrypt,
                password = password
            )
            
            when (exportResult) {
                is ExportResult.Success -> {
                    Log.d(TAG, "备份创建成功: ${exportResult.filePath}")
                    BackupResult.Success(
                        backupPath = exportResult.filePath,
                        backupSize = exportResult.fileSize,
                        createdAt = timestamp,
                        encrypted = encrypt
                    )
                }
                is ExportResult.Error -> {
                    BackupResult.Error("备份创建失败: ${exportResult.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "创建备份失败", e)
            BackupResult.Error("创建备份失败: ${e.message}")
        }
    }
    
    /**
     * 从备份恢复
     */
    suspend fun restoreFromBackup(
        backupPath: String,
        password: String? = null,
        overwrite: Boolean = true
    ): RestoreResult = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "从备份恢复: $backupPath")
            
            val importResult = importData(backupPath, password, overwrite)
            
            when (importResult) {
                is ImportResult.Success -> {
                    Log.d(TAG, "备份恢复成功")
                    RestoreResult.Success(
                        restoredAt = importResult.importedAt,
                        overwrite = overwrite
                    )
                }
                is ImportResult.Error -> {
                    RestoreResult.Error("备份恢复失败: ${importResult.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "从备份恢复失败", e)
            RestoreResult.Error("从备份恢复失败: ${e.message}")
        }
    }
    
    /**
     * 获取备份列表
     */
    fun getBackupList(): List<BackupInfo> {
        val backups = mutableListOf<BackupInfo>()
        
        backupDir.listFiles()?.forEach { file ->
            if (file.isFile && (file.name.endsWith(".zip") || file.name.endsWith(".encrypted"))) {
                backups.add(
                    BackupInfo(
                        name = file.nameWithoutExtension,
                        path = file.absolutePath,
                        size = file.length(),
                        createdAt = file.lastModified(),
                        encrypted = file.name.endsWith(".encrypted")
                    )
                )
            }
        }
        
        return backups.sortedByDescending { it.createdAt }
    }
    
    /**
     * 删除备份
     */
    fun deleteBackup(backupPath: String): Boolean {
        val file = File(backupPath)
        return if (file.exists() && file.parent == backupDir.absolutePath) {
            file.delete()
        } else {
            false
        }
    }
    
    /**
     * 导出对话数据
     */
    private fun exportConversations(zipOut: ZipOutputStream) {
        val conversationsDir = File(dataDir, "conversations")
        if (!conversationsDir.exists()) return
        
        conversationsDir.listFiles()?.forEach { file ->
            if (file.isFile) {
                zipOut.putNextEntry(ZipEntry("conversations/${file.name}"))
                FileInputStream(file).use { fis ->
                    fis.copyTo(zipOut)
                }
                zipOut.closeEntry()
            }
        }
    }
    
    /**
     * 导出设置
     */
    private fun exportSettings(zipOut: ZipOutputStream) {
        val settingsDir = File(dataDir, "settings")
        if (!settingsDir.exists()) return
        
        settingsDir.listFiles()?.forEach { file ->
            if (file.isFile) {
                zipOut.putNextEntry(ZipEntry("settings/${file.name}"))
                FileInputStream(file).use { fis ->
                    fis.copyTo(zipOut)
                }
                zipOut.closeEntry()
            }
        }
    }
    
    /**
     * 导出模型配置
     */
    private fun exportModels(zipOut: ZipOutputStream) {
        val modelsDir = File(dataDir, "models")
        if (!modelsDir.exists()) return
        
        modelsDir.listFiles()?.forEach { file ->
            if (file.isFile) {
                zipOut.putNextEntry(ZipEntry("models/${file.name}"))
                FileInputStream(file).use { fis ->
                    fis.copyTo(zipOut)
                }
                zipOut.closeEntry()
            }
        }
    }
    
    /**
     * 导出工具配置
     */
    private fun exportTools(zipOut: ZipOutputStream) {
        val toolsDir = File(dataDir, "tools")
        if (!toolsDir.exists()) return
        
        toolsDir.listFiles()?.forEach { file ->
            if (file.isFile) {
                zipOut.putNextEntry(ZipEntry("tools/${file.name}"))
                FileInputStream(file).use { fis ->
                    fis.copyTo(zipOut)
                }
                zipOut.closeEntry()
            }
        }
    }
    
    /**
     * 导出元数据
     */
    private fun exportMetadata(zipOut: ZipOutputStream, timestamp: Long) {
        val metadata = """
        {
            "version": "1.0",
            "exportedAt": $timestamp,
            "appName": "ZorvAI",
            "appVersion": "1.0.71",
            "description": "ZorvAI data export"
        }
        """.trimIndent()
        
        zipOut.putNextEntry(ZipEntry("metadata.json"))
        zipOut.write(metadata.toByteArray())
        zipOut.closeEntry()
    }
    
    /**
     * 导入对话数据
     */
    private fun importConversation(zipIn: ZipInputStream, entryName: String, overwrite: Boolean) {
        val fileName = entryName.removePrefix("conversations/")
        val targetFile = File(dataDir, "conversations/$fileName")
        
        if (targetFile.exists() && !overwrite) {
            Log.d(TAG, "跳过已存在的对话文件: $fileName")
            return
        }
        
        targetFile.parentFile?.mkdirs()
        FileOutputStream(targetFile).use { fos ->
            zipIn.copyTo(fos)
        }
        
        Log.d(TAG, "导入对话文件: $fileName")
    }
    
    /**
     * 导入设置
     */
    private fun importSetting(zipIn: ZipInputStream, entryName: String, overwrite: Boolean) {
        val fileName = entryName.removePrefix("settings/")
        val targetFile = File(dataDir, "settings/$fileName")
        
        if (targetFile.exists() && !overwrite) {
            Log.d(TAG, "跳过已存在的设置文件: $fileName")
            return
        }
        
        targetFile.parentFile?.mkdirs()
        FileOutputStream(targetFile).use { fos ->
            zipIn.copyTo(fos)
        }
        
        Log.d(TAG, "导入设置文件: $fileName")
    }
    
    /**
     * 导入模型配置
     */
    private fun importModel(zipIn: ZipInputStream, entryName: String, overwrite: Boolean) {
        val fileName = entryName.removePrefix("models/")
        val targetFile = File(dataDir, "models/$fileName")
        
        if (targetFile.exists() && !overwrite) {
            Log.d(TAG, "跳过已存在的模型文件: $fileName")
            return
        }
        
        targetFile.parentFile?.mkdirs()
        FileOutputStream(targetFile).use { fos ->
            zipIn.copyTo(fos)
        }
        
        Log.d(TAG, "导入模型文件: $fileName")
    }
    
    /**
     * 导入工具配置
     */
    private fun importTool(zipIn: ZipInputStream, entryName: String, overwrite: Boolean) {
        val fileName = entryName.removePrefix("tools/")
        val targetFile = File(dataDir, "tools/$fileName")
        
        if (targetFile.exists() && !overwrite) {
            Log.d(TAG, "跳过已存在的工具文件: $fileName")
            return
        }
        
        targetFile.parentFile?.mkdirs()
        FileOutputStream(targetFile).use { fos ->
            zipIn.copyTo(fos)
        }
        
        Log.d(TAG, "导入工具文件: $fileName")
    }
    
    /**
     * 加密文件
     */
    private fun encryptFile(inputFile: File, password: String, outputFile: File) {
        val key = generateKey(password)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        
        val iv = cipher.iv
        val ivBytes = Base64.encode(iv, Base64.DEFAULT)
        
        // 写入 IV 到文件头
        FileOutputStream(outputFile).use { fos ->
            fos.write(ivBytes)
            fos.write("\n".toByteArray())
            
            // 加密并写入文件内容
            FileInputStream(inputFile).use { fis ->
                val buffer = ByteArray(1024)
                var bytesRead: Int
                while (fis.read(buffer).also { bytesRead = it } != -1) {
                    val encryptedBytes = cipher.update(buffer, 0, bytesRead)
                    fos.write(encryptedBytes)
                }
                val finalBytes = cipher.doFinal()
                fos.write(finalBytes)
            }
        }
    }
    
    /**
     * 解密文件
     */
    private fun decryptFile(inputFile: File, password: String, outputFile: File) {
        FileInputStream(inputFile).use { fis ->
            // 读取 IV
            val ivBytes = mutableListOf<Byte>()
            var byte = fis.read()
            while (byte != -1 && byte != '\n'.code) {
                ivBytes.add(byte.toByte())
                byte = fis.read()
            }
            
            val iv = Base64.decode(ivBytes.toByteArray(), Base64.DEFAULT)
            val key = generateKey(password)
            
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            val ivSpec = IvParameterSpec(iv)
            cipher.init(Cipher.DECRYPT_MODE, key, ivSpec)
            
            // 解密并写入文件
            FileOutputStream(outputFile).use { fos ->
                val buffer = ByteArray(1024)
                var bytesRead: Int
                while (fis.read(buffer).also { bytesRead = it } != -1) {
                    val decryptedBytes = cipher.update(buffer, 0, bytesRead)
                    fos.write(decryptedBytes)
                }
                val finalBytes = cipher.doFinal()
                fos.write(finalBytes)
            }
        }
    }
    
    /**
     * 生成加密密钥
     */
    private fun generateKey(password: String): SecretKeySpec {
        val keyBytes = password.toByteArray()
        val key = ByteArray(16) // AES-128
        System.arraycopy(keyBytes, 0, key, 0, minOf(keyBytes.size, key.size))
        return SecretKeySpec(key, "AES")
    }
    
    companion object {
        @Volatile
        private var instance: QuroDataManager? = null
        
        fun getInstance(context: Context): QuroDataManager {
            return instance ?: synchronized(this) {
                instance ?: QuroDataManager(context.applicationContext).also { 
                    instance = it 
                }
            }
        }
    }
}

/**
 * 导出结果
 */
sealed class ExportResult {
    data class Success(
        val filePath: String,
        val fileSize: Long,
        val encrypted: Boolean,
        val timestamp: Long
    ) : ExportResult()
    
    data class Error(val message: String) : ExportResult()
}

/**
 * 导入结果
 */
sealed class ImportResult {
    data class Success(
        val importedAt: Long,
        val overwrite: Boolean
    ) : ImportResult()
    
    data class Error(val message: String) : ImportResult()
}

/**
 * 备份结果
 */
sealed class BackupResult {
    data class Success(
        val backupPath: String,
        val backupSize: Long,
        val createdAt: Long,
        val encrypted: Boolean
    ) : BackupResult()
    
    data class Error(val message: String) : BackupResult()
}

/**
 * 恢复结果
 */
sealed class RestoreResult {
    data class Success(
        val restoredAt: Long,
        val overwrite: Boolean
    ) : RestoreResult()
    
    data class Error(val message: String) : RestoreResult()
}

/**
 * 备份信息
 */
data class BackupInfo(
    val name: String,
    val path: String,
    val size: Long,
    val createdAt: Long,
    val encrypted: Boolean
)
