package com.ai.assistance.quro.core.novaterm.command

import com.ai.assistance.quro.core.novaterm.core.*
import com.ai.assistance.quro.core.novaterm.core.FileSystem

/**
 * 文本处理命令集
 */
object EchoCommand : BuiltinCommand {
    override val name = "echo"
    override fun execute(sessionId: String, cmd: Command): CommandResult {
        val text = cmd.args.joinToString(" ")
        // 支持 $ENV 变量替换
        val expanded = text.replace(Regex("""\$\{?(\w+)\}?""")) { match ->
            val varName = match.groupValues[1]
            SessionManager.getEnv(sessionId, varName) ?: match.value
        }
        return CommandResult.ok(expanded)
    }
    override fun help() = "echo <text>  - 输出文本"
}

object GrepCommand : BuiltinCommand {
    override val name = "grep"
    override fun execute(sessionId: String, cmd: Command): CommandResult {
        val pattern = cmd.getArg(0)
        val filePath = cmd.getArg(1)
        if (pattern.isEmpty()) return CommandResult.err("grep: missing pattern")

        val content = if (filePath.isNotEmpty()) {
            FileSystem.readFile(sessionId, filePath).getOrNull() ?: ""
        } else {
            // 从管道输入（这里简化处理）
            ""
        }

        val regex = Regex(pattern)
        val matches = content.lines().filter { regex.containsMatchIn(it) }
        val ignoreCase = cmd.hasFlag("-i")
        val lineNumbers = cmd.hasFlag("-n")

        val results = if (filePath.isNotEmpty()) {
            val regex = if (ignoreCase) Regex(pattern, RegexOption.IGNORE_CASE) else Regex(pattern)
            content.lines().mapIndexedNotNull { idx, line ->
                if (regex.containsMatchIn(line)) {
                    if (lineNumbers) "${idx + 1}: $line" else line
                } else null
            }
        } else emptyList()

        return CommandResult.ok(results.joinToString("\n"))
    }
    override fun help() = "grep [-i] [-n] <pattern> [file]  - 搜索文本"
}

object HeadCommand : BuiltinCommand {
    override val name = "head"
    override fun execute(sessionId: String, cmd: Command): CommandResult {
        val lines = cmd.getOption("-n", "10").toIntOrNull() ?: 10
        val filePath = cmd.args.lastOrNull { !it.startsWith("-") } ?: ""

        val content = if (filePath.isNotEmpty()) {
            FileSystem.readFile(sessionId, filePath).getOrNull() ?: ""
        } else ""
        return CommandResult.ok(content.lines().take(lines).joinToString("\n"))
    }
    override fun help() = "head [-n <num>] <file>  - 显示文件前N行"
}

object TailCommand : BuiltinCommand {
    override val name = "tail"
    override fun execute(sessionId: String, cmd: Command): CommandResult {
        val lines = cmd.getOption("-n", "10").toIntOrNull() ?: 10
        val filePath = cmd.args.lastOrNull { !it.startsWith("-") } ?: ""

        val content = if (filePath.isNotEmpty()) {
            FileSystem.readFile(sessionId, filePath).getOrNull() ?: ""
        } else ""
        return CommandResult.ok(content.lines().takeLast(lines).joinToString("\n"))
    }
    override fun help() = "tail [-n <num>] <file>  - 显示文件后N行"
}

object WcCommand : BuiltinCommand {
    override val name = "wc"
    override fun execute(sessionId: String, cmd: Command): CommandResult {
        val filePath = cmd.getArg(0)
        if (filePath.isEmpty()) return CommandResult.err("wc: missing file")

        val content = FileSystem.readFile(sessionId, filePath).getOrNull() ?: ""
        val lines = content.lines().size
        val words = content.split(Regex("\\s+")).filter { it.isNotEmpty() }.size
        val chars = content.length

        val showLines = cmd.hasFlag("-l") || cmd.args.isEmpty()
        val showWords = cmd.hasFlag("-w")
        val showChars = cmd.hasFlag("-c")

        val parts = mutableListOf<String>()
        if (showLines || (!showWords && !showChars)) parts.add("$lines")
        if (showWords) parts.add("$words")
        if (showChars) parts.add("$chars")

        return CommandResult.ok(parts.joinToString(" ") + " $filePath")
    }
    override fun help() = "wc [-l|-w|-c] <file>  - 统计行数/词数/字符数"
}

object SortCommand : BuiltinCommand {
    override val name = "sort"
    override fun execute(sessionId: String, cmd: Command): CommandResult {
        val filePath = cmd.getArg(0)
        val reverse = cmd.hasFlag("-r") || cmd.hasFlag("--reverse")
        val numeric = cmd.hasFlag("-n") || cmd.hasFlag("--numeric")

        val content = if (filePath.isNotEmpty()) {
            FileSystem.readFile(sessionId, filePath).getOrNull() ?: ""
        } else ""

        val sorted = if (numeric) {
            content.lines().sortedBy { it.toIntOrNull() ?: 0 }
        } else {
            content.lines().sorted()
        }

        return CommandResult.ok(if (reverse) sorted.reversed().joinToString("\n") else sorted.joinToString("\n"))
    }
    override fun help() = "sort [-r] [-n] <file>  - 排序文本"
}

object UniqCommand : BuiltinCommand {
    override val name = "uniq"
    override fun execute(sessionId: String, cmd: Command): CommandResult {
        val filePath = cmd.getArg(0)
        val content = if (filePath.isNotEmpty()) {
            FileSystem.readFile(sessionId, filePath).getOrNull() ?: ""
        } else ""
        val lines = content.lines()
        val unique = lines.fold(mutableListOf<String>()) { acc, line ->
            if (acc.isEmpty() || acc.last() != line) acc.add(line)
            acc
        }
        return CommandResult.ok(unique.joinToString("\n"))
    }
    override fun help() = "uniq <file>  - 去除重复行"
}
