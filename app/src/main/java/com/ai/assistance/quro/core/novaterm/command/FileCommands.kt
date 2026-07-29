package com.ai.assistance.quro.core.novaterm.command

import com.ai.assistance.quro.core.novaterm.core.*
import com.ai.assistance.quro.core.novaterm.core.FileSystem
import com.ai.assistance.quro.core.novaterm.core.FileType
import com.ai.assistance.quro.core.novaterm.core.SessionManager

/**
 * 文件系统命令集
 */
object FileCommands : BuiltinCommand {
    override val name = "ls"
    override val aliases = listOf<String>()

    override fun execute(sessionId: String, cmd: Command): CommandResult {
        val path = cmd.getArg(0, ".")
        val longFormat = cmd.hasFlag("-l") || cmd.hasFlag("--long")
        val showHidden = cmd.hasFlag("-a") || cmd.hasFlag("--all")
        val humanReadable = cmd.hasFlag("-h") || cmd.hasFlag("--human")

        val entries = FileSystem.list(sessionId, path)
        val filtered = if (showHidden) entries else entries.filter { !it.name.startsWith(".") }

        if (filtered.isEmpty()) return CommandResult.ok("(empty)")

        if (longFormat) {
            val lines = filtered.map { entry ->
                val size = if (humanReadable) formatSize(entry.size) else entry.size.toString()
                val type = when (entry.type) {
                    FileType.DIRECTORY -> "d"
                    FileType.EXECUTABLE -> "x"
                    else -> "-"
                }
                val date = formatDate(entry.lastModified)
                "$type${entry.permissions}  ${pad(size, 10)}  $date  ${entry.name}"
            }
            return CommandResult.Text(lines.joinToString("\n"))
        }

        // 简单格式，带颜色标记
        val outputLines = filtered.map { entry ->
            val style = when (entry.type) {
                FileType.DIRECTORY -> OutputStyle.CYAN
                FileType.EXECUTABLE -> OutputStyle.GREEN
                else -> OutputStyle.NORMAL
            }
            OutputLine(
                text = if (entry.type == FileType.DIRECTORY) "${entry.name}/" else entry.name,
                style = style
            )
        }
        return CommandResult.RichText(outputLines)
    }

    override fun help() = "ls [-l] [-a] [-h] [path]  - 列出目录内容"
}

object CdCommand : BuiltinCommand {
    override val name = "cd"
    override fun execute(sessionId: String, cmd: Command): CommandResult {
        val path = cmd.getArg(0, "/")
        val result = FileSystem.setCwd(sessionId, path)
        return if (result.isSuccess) {
            CommandResult.empty()
        } else {
            CommandResult.err(result.exceptionOrNull()?.message ?: "cd failed")
        }
    }
    override fun help() = "cd <path>  - 切换目录"
}

object PwdCommand : BuiltinCommand {
    override val name = "pwd"
    override fun execute(sessionId: String, cmd: Command): CommandResult {
        return CommandResult.ok(FileSystem.getCwd(sessionId))
    }
    override fun help() = "pwd  - 显示当前目录"
}

object CatCommand : BuiltinCommand {
    override val name = "cat"
    override fun execute(sessionId: String, cmd: Command): CommandResult {
        val path = cmd.getArg(0)
        if (path.isEmpty()) return CommandResult.err("cat: missing file operand")

        val result = FileSystem.readFile(sessionId, path)
        return if (result.isSuccess) {
            CommandResult.ok(result.getOrNull() ?: "")
        } else {
            CommandResult.err(result.exceptionOrNull()?.message ?: "cat failed")
        }
    }
    override fun help() = "cat <file>  - 显示文件内容"
}

object MkdirCommand : BuiltinCommand {
    override val name = "mkdir"
    override fun execute(sessionId: String, cmd: Command): CommandResult {
        val path = cmd.getArg(0)
        if (path.isEmpty()) return CommandResult.err("mkdir: missing operand")

        val recursive = cmd.hasFlag("-p") || cmd.hasFlag("--parents")
        if (recursive) {
            // 逐级创建
            val parts = path.split("/").filter { it.isNotEmpty() }
            var current = ""
            for (part in parts) {
                current += "/$part"
                val r = FileSystem.createDir(sessionId, current)
                if (!r.isSuccess && !FileSystem.exists(sessionId, current)) {
                    return CommandResult.err("mkdir: failed at $current")
                }
            }
            return CommandResult.ok("")
        }

        val result = FileSystem.createDir(sessionId, path)
        return if (result.isSuccess) CommandResult.ok("") else CommandResult.err(result.exceptionOrNull()?.message ?: "mkdir failed")
    }
    override fun help() = "mkdir [-p] <dir>  - 创建目录"
}

object RmCommand : BuiltinCommand {
    override val name = "rm"
    override fun execute(sessionId: String, cmd: Command): CommandResult {
        val path = cmd.getArg(0)
        if (path.isEmpty()) return CommandResult.err("rm: missing operand")
        val recursive = cmd.hasFlag("-r") || cmd.hasFlag("-rf") || cmd.hasFlag("-fr")

        if (FileSystem.isDirectory(sessionId, path) && !recursive) {
            return CommandResult.err("rm: cannot remove '$path': Is a directory (use -r)")
        }

        val result = FileSystem.delete(sessionId, path)
        return if (result.isSuccess) CommandResult.ok("") else CommandResult.err(result.exceptionOrNull()?.message ?: "rm failed")
    }
    override fun help() = "rm [-r] <file/dir>  - 删除文件或目录"
}

object CpCommand : BuiltinCommand {
    override val name = "cp"
    override fun execute(sessionId: String, cmd: Command): CommandResult {
        val src = cmd.getArg(0)
        val dst = cmd.getArg(1)
        if (src.isEmpty() || dst.isEmpty()) return CommandResult.err("cp: missing operand")

        val result = FileSystem.copy(sessionId, src, dst)
        return if (result.isSuccess) CommandResult.ok("") else CommandResult.err(result.exceptionOrNull()?.message ?: "cp failed")
    }
    override fun help() = "cp <src> <dst>  - 复制文件"
}

object MvCommand : BuiltinCommand {
    override val name = "mv"
    override fun execute(sessionId: String, cmd: Command): CommandResult {
        val src = cmd.getArg(0)
        val dst = cmd.getArg(1)
        if (src.isEmpty() || dst.isEmpty()) return CommandResult.err("mv: missing operand")

        val result = FileSystem.move(sessionId, src, dst)
        return if (result.isSuccess) CommandResult.ok("") else CommandResult.err(result.exceptionOrNull()?.message ?: "mv failed")
    }
    override fun help() = "mv <src> <dst>  - 移动/重命名文件"
}

object TouchCommand : BuiltinCommand {
    override val name = "touch"
    override fun execute(sessionId: String, cmd: Command): CommandResult {
        val path = cmd.getArg(0)
        if (path.isEmpty()) return CommandResult.err("touch: missing operand")

        val result = FileSystem.writeFile(sessionId, path, "", append = false)
        return if (result.isSuccess) CommandResult.ok("") else CommandResult.err(result.exceptionOrNull()?.message ?: "touch failed")
    }
    override fun help() = "touch <file>  - 创建空文件"
}

object FindCommand : BuiltinCommand {
    override val name = "find"
    override fun execute(sessionId: String, cmd: Command): CommandResult {
        val startPath = cmd.getArg(0, ".")
        val namePattern = cmd.getOption("-name", cmd.getOption("--name", ""))
        val typeFilter = cmd.getOption("-type", "")

        val results = mutableListOf<String>()
        fun traverse(path: String, depth: Int) {
            if (depth > 20) return // 防止无限递归
            val entries = FileSystem.list(sessionId, path)
            for (entry in entries) {
                val fullPath = if (path == ".") entry.name else "$path/${entry.name}"
                val matches = when {
                    namePattern.isNotEmpty() -> entry.name.contains(namePattern, ignoreCase = true)
                    else -> true
                }
                val typeMatches = when (typeFilter) {
                    "f" -> entry.type != FileType.DIRECTORY
                    "d" -> entry.type == FileType.DIRECTORY
                    "" -> true
                    else -> true
                }
                if (matches && typeMatches) results.add(fullPath)

                if (entry.type == FileType.DIRECTORY) {
                    traverse(fullPath, depth + 1)
                }
            }
        }
        traverse(startPath, 0)
        return if (results.isEmpty()) CommandResult.ok("(no matches)") else CommandResult.ok(results.joinToString("\n"))
    }
    override fun help() = "find [path] [-name <pattern>] [-type f|d]  - 查找文件"
}

object TreeCommand : BuiltinCommand {
    override val name = "tree"
    override fun execute(sessionId: String, cmd: Command): CommandResult {
        val startPath = cmd.getArg(0, ".")
        val maxDepth = cmd.getOption("-d", "4").toIntOrNull() ?: 4

        val lines = mutableListOf<String>()
        fun traverse(path: String, prefix: String, depth: Int) {
            if (depth > maxDepth) return
            val entries = FileSystem.list(sessionId, path)
            val filtered = entries.filter { !it.name.startsWith(".") }
            filtered.forEachIndexed { index, entry ->
                val isLast = index == filtered.size - 1
                val connector = if (isLast) "└── " else "├── "
                val icon = when (entry.type) {
                    FileType.DIRECTORY -> "📁"
                    else -> "📄"
                }
                lines.add("$prefix$connector$icon ${entry.name}")

                if (entry.type == FileType.DIRECTORY) {
                    val newPath = if (path == ".") entry.name else "$path/${entry.name}"
                    val newPrefix = prefix + if (isLast) "    " else "│   "
                    traverse(newPath, newPrefix, depth + 1)
                }
            }
        }
        lines.add("📁 ${FileSystem.getCwd(sessionId)}")
        traverse(startPath, "", 1)
        return CommandResult.ok(lines.joinToString("\n"))
    }
    override fun help() = "tree [path] [-d <depth>]  - 树形显示目录结构"
}

// ========== 辅助函数 ==========
private fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "${bytes}B"
    if (bytes < 1048576) return String.format("%.1fK", bytes / 1024.0)
    if (bytes < 1073741824) return String.format("%.1fM", bytes / 1048576.0)
    return String.format("%.1fG", bytes / 1073741824.0)
}

private fun formatDate(timestamp: Long): String {
    val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
    return sdf.format(java.util.Date(timestamp))
}

private fun pad(text: String, length: Int): String {
    return text.padStart(length)
}
