package com.ai.assistance.quro.terminal.utils

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import kotlin.math.log10
import kotlin.math.pow
import kotlinx.coroutines.ensureActive

class CacheManager(private val context: Context) {

    private val filesDir: File = context.filesDir
    private val usrDir: File = File(filesDir, "usr")
    private val tmpDir: File = File(filesDir, "tmp")

    suspend fun getCacheSize(onProgress: (bytes: Long) -> Unit): Long = withContext(Dispatchers.IO) {
        var totalSize = 0L
        var filesProcessed = 0
        val seenFiles = mutableSetOf<Any>()
        onProgress(0L) // Initial progress

        listOf(usrDir, tmpDir).forEach { dir ->
            if (dir.exists()) {
                dir.walkTopDown().onEnter {
                    // To prevent visiting directories we don't have permission to read,
                    // which would cause the walk to fail.
                    it.canRead() 
                }.forEach { file ->
                    ensureActive() // 检查协程是否已被取消
                    if (file.isFile) {
                        try {
                            val path = file.toPath()
                            // On Linux, fileKey() returns an object containing inode and device ID.
                            // This allows us to correctly handle hard links and not double-count them.
                            val fileKey = try {
                                Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes::class.java).fileKey()
                            } catch (e: Exception) {
                                null // If we can't read attributes, assume no key.
                            }

                            if (fileKey != null) {
                                if (seenFiles.add(fileKey)) {
                                    totalSize += file.length()
                                }
                            } else {
                                // Fallback for filesystems without fileKey support or if attribute read fails.
                                // This might overcount hard links, but it's the best we can do.
                                totalSize += file.length()
                            }

                            filesProcessed++
                            // To avoid overwhelming the main thread, update progress periodically.
                            if (filesProcessed % 200 == 0) {
                                onProgress(totalSize)
                            }
                        } catch (e: Exception) {
                            // Ignore files that can't be accessed, e.g. broken symlinks
                        }
                    }
                }
            }
        }
        
        onProgress(totalSize) // Final update with the total size
        totalSize
    }

    suspend fun clearCache(terminalManager: com.ai.assistance.quro.terminal.TerminalManager? = null) = withContext(Dispatchers.IO) {
        // 首先停止所有终端会话
        terminalManager?.cleanup()
        
        // 等待一下确保进程完全停止
        kotlinx.coroutines.delay(1000)
        
        // 清理文件系统
        usrDir.deleteRecursively()
        tmpDir.deleteRecursively()
        
        // 清理其他相关文件
        val filesToClean = listOf(
            "common.sh",
            "proot-distro.zip", 
            "ubuntu-noble-aarch64-pd-v4.18.0.tar.xz"
        )
        
        filesToClean.forEach { fileName ->
            val file = File(filesDir, fileName)
            if (file.exists()) {
                file.delete()
            }
        }
    }

    fun formatSize(size: Long): String {
        if (size <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
        return String.format("%.1f %s", size / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
    }
} 