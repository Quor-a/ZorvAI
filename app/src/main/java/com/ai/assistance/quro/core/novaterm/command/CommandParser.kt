package com.ai.assistance.quro.core.novaterm.command

import com.ai.assistance.quro.core.novaterm.core.*
/**
 * 命令解析器
 * 支持：引号、转义、管道、重定向、后台执行
 */
object CommandParser {

    /**
     * 解析用户输入为命令树
     */
    fun parse(input: String): ParsedCommand {
        // 处理后台执行
        val bg = input.trim().endsWith("&")
        val cleanInput = if (bg) input.trim().removeSuffix("&").trim() else input.trim()

        // 处理管道
        if (cleanInput.contains("|")) {
            val stages = cleanInput.split("|").map { it.trim() }
            val commands = stages.map { parseSingle(it) }
            return ParsedCommand(
                type = CommandType.PIPELINE,
                pipeline = commands,
                background = bg
            )
        }

        // 处理重定向
        val redirect = parseRedirect(cleanInput)
        val cmd = parseSingle(redirect.command)

        return if (redirect.type != RedirectType.NONE) {
            ParsedCommand(
                type = CommandType.REDIRECTION,
                mainCommand = cmd,
                redirectType = redirect.type,
                redirectTarget = redirect.target,
                background = bg
            )
        } else {
            ParsedCommand(
                type = CommandType.SIMPLE,
                mainCommand = cmd,
                background = bg
            )
        }
    }

    private fun parseSingle(input: String): Command {
        val tokens = tokenize(input)
        if (tokens.isEmpty()) return Command.empty()
        return Command(
            name = tokens.first(),
            args = tokens.drop(1),
            raw = input
        )
    }

    /**
     * 词法分析器：处理引号、转义字符
     */
    fun tokenize(input: String): List<String> {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var inSingleQuote = false
        var inDoubleQuote = false
        var escaped = false

        for (ch in input) {
            when {
                escaped -> {
                    current.append(ch)
                    escaped = false
                }
                ch == '\\' && !inSingleQuote -> {
                    escaped = true
                }
                ch == '\'' && !inDoubleQuote -> {
                    inSingleQuote = !inSingleQuote
                }
                ch == '"' && !inSingleQuote -> {
                    inDoubleQuote = !inDoubleQuote
                }
                ch == ' ' && !inSingleQuote && !inDoubleQuote -> {
                    if (current.isNotEmpty()) {
                        tokens.add(current.toString())
                        current.clear()
                    }
                }
                else -> {
                    current.append(ch)
                }
            }
        }

        if (current.isNotEmpty()) {
            tokens.add(current.toString())
        }

        return tokens
    }

    private fun parseRedirect(input: String): RedirectInfo {
        // > file  (overwrite)
        // >> file (append)
        // 2> file (stderr)
        val overwriteRegex = Regex("""\s+>\s*(\S+)$""")
        val appendRegex = Regex("""\s+>>\s*(\S+)$""")
        val stderrRegex = Regex("""\s+2>\s*(\S+)$""")

        stderrRegex.find(input)?.let {
            return RedirectInfo(
                command = input.substring(0, it.range.first),
                type = RedirectType.STDERR,
                target = it.groupValues[1]
            )
        }
        appendRegex.find(input)?.let {
            return RedirectInfo(
                command = input.substring(0, it.range.first),
                type = RedirectType.APPEND,
                target = it.groupValues[1]
            )
        }
        overwriteRegex.find(input)?.let {
            return RedirectInfo(
                command = input.substring(0, it.range.first),
                type = RedirectType.OVERWRITE,
                target = it.groupValues[1]
            )
        }

        return RedirectInfo(input, RedirectType.NONE, "")
    }
}

// ========== 数据结构 ==========

data class ParsedCommand(
    val type: CommandType,
    val mainCommand: Command = Command.empty(),
    val pipeline: List<Command> = emptyList(),
    val redirectType: RedirectType = RedirectType.NONE,
    val redirectTarget: String = "",
    val background: Boolean = false
)

data class Command(
    val name: String,
    val args: List<String> = emptyList(),
    val flags: Set<String> = emptySet(),
    val options: Map<String, String> = emptyMap(),
    val raw: String = ""
) {
    companion object {
        fun empty() = Command("", emptyList(), emptySet(), emptyMap(), "")
    }

    fun getArg(index: Int, default: String = ""): String =
        args.getOrElse(index) { default }

    fun hasFlag(flag: String): Boolean = flags.contains(flag)

    fun getOption(key: String, default: String = ""): String =
        options[key] ?: default
}

enum class CommandType {
    SIMPLE, PIPELINE, REDIRECTION
}

enum class RedirectType {
    NONE, OVERWRITE, APPEND, STDERR
}

data class RedirectInfo(
    val command: String,
    val type: RedirectType,
    val target: String
)
