package com.ai.assistance.quro.core.novaterm.core

import java.io.File
import java.io.IOException

/**
 * 虚拟文件系统抽象层
 * 提供统一的文件操作接口，支持沙盒隔离和权限检查
 */
object FileSystem {

    // 虚拟根目录（应用私有目录）
    private val virtualRoot: File by lazy {
        File("/data/local/tmp/quroterm/root").apply { mkdirs() }
    }

    // 当前工作目录（每个会话独立）
    private val cwdMap = mutableMapOf<String, File>()

    fun initSession(sessionId: String) {
        cwdMap[sessionId] = virtualRoot
    }

    fun destroySession(sessionId: String) {
        cwdMap.remove(sessionId)
    }

    fun getCwd(sessionId: String): String =
        cwdMap[sessionId]?.absolutePath ?: virtualRoot.absolutePath

    fun setCwd(sessionId: String, path: String): Result<Unit> {
        val target = resolvePath(sessionId, path)
        if (!target.exists()) return Result.failure(IOException("No such directory: $path"))
        if (!target.isDirectory) return Result.failure(IOException("Not a directory: $path"))
        cwdMap[sessionId] = target
        return Result.success(Unit)
    }

    fun resolvePath(sessionId: String, path: String): File {
        if (path == "~") return virtualRoot
        if (path.startsWith("/")) {
            // 虚拟绝对路径
            return File(virtualRoot, path.trimStart('/'))
        }
        // 相对路径
        return File(cwdMap[sessionId] ?: virtualRoot, path)
    }

    fun list(sessionId: String, path: String = "."): List<FileEntry> {
        val dir = resolvePath(sessionId, path)
        if (!dir.exists()) return emptyList()
        return dir.listFiles()?.sortedBy { it.name }?.map { file ->
            FileEntry(
                name = file.name,
                type = when {
                    file.isDirectory -> FileType.DIRECTORY
                    file.canExecute() -> FileType.EXECUTABLE
                    file.extension in setOf("txt", "md", "json", "xml", "nv") -> FileType.TEXT
                    else -> FileType.FILE
                },
                size = if (file.isFile) file.length() else 0,
                permissions = getPermissions(file),
                lastModified = file.lastModified()
            )
        } ?: emptyList()
    }

    fun readFile(sessionId: String, path: String): Result<String> {
        val file = resolvePath(sessionId, path)
        if (!file.exists()) return Result.failure(IOException("File not found: $path"))
        if (file.isDirectory) return Result.failure(IOException("Is a directory: $path"))
        return try {
            Result.success(file.readText())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun writeFile(sessionId: String, path: String, content: String, append: Boolean = false): Result<Unit> {
        val file = resolvePath(sessionId, path)
        return try {
            if (append) file.appendText(content) else file.writeText(content)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun createDir(sessionId: String, path: String): Result<Unit> {
        val dir = resolvePath(sessionId, path)
        if (dir.exists()) return Result.failure(IOException("Already exists: $path"))
        return if (dir.mkdirs()) Result.success(Unit) else Result.failure(IOException("Failed to create: $path"))
    }

    fun delete(sessionId: String, path: String): Result<Unit> {
        val file = resolvePath(sessionId, path)
        if (!file.exists()) return Result.failure(IOException("Not found: $path"))
        return if (file.deleteRecursively()) Result.success(Unit) else Result.failure(IOException("Delete failed"))
    }

    fun copy(sessionId: String, src: String, dst: String): Result<Unit> {
        val source = resolvePath(sessionId, src)
        val target = resolvePath(sessionId, dst)
        if (!source.exists()) return Result.failure(IOException("Source not found: $src"))
        return try {
            source.copyRecursively(target, overwrite = true)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun move(sessionId: String, src: String, dst: String): Result<Unit> {
        val source = resolvePath(sessionId, src)
        val target = resolvePath(sessionId, dst)
        if (!source.exists()) return Result.failure(IOException("Source not found: $src"))
        return if (source.renameTo(target)) Result.success(Unit) else Result.failure(IOException("Move failed"))
    }

    fun exists(sessionId: String, path: String): Boolean =
        resolvePath(sessionId, path).exists()

    fun isDirectory(sessionId: String, path: String): Boolean =
        resolvePath(sessionId, path).isDirectory

    private fun getPermissions(file: File): String {
        var perm = ""
        perm += if (file.canRead()) "r" else "-"
        perm += if (file.canWrite()) "w" else "-"
        perm += if (file.canExecute()) "x" else "-"
        return perm
    }
}

data class FileEntry(
    val name: String,
    val type: FileType,
    val size: Long,
    val permissions: String,
    val lastModified: Long
)

enum class FileType {
    FILE, DIRECTORY, EXECUTABLE, TEXT, SYMLINK
}
