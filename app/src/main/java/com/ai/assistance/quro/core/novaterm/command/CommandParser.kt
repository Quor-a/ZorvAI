package com.ai.assistance.quro.core.novaterm.command

import com.ai.assistance.quro.core.novaterm.core.*
/**
 * 命令解析器
 * 支持：引号、转义、管道、重定向、后台执行、命令序列（; && ||）
 */
object CommandParser {

    /**
     * 解析用户输入为命令树
     */
    fun parse(input: String): ParsedCommand {
        val trimmedAll = input.trim()
        // 处理后台执行。注意 `&&` 结尾不算后台（`echo a &&` 是序列不是 sleep&）：
        // 旧实现无脑 removeSuffix("&") 会把 `a && b` 剥成 `a & b`，直接改义。
        val bg = trimmedAll.endsWith("&") && !trimmedAll.endsWith("&&")
        val cleanInput = if (bg) trimmedAll.removeSuffix("&").trim() else trimmedAll

        // 处理命令序列（; && ||）——必须在管道之前：整条输入先按顶层分隔符切段，
        // 每段再各自按管道/重定向解析。旧实现完全没有这一层，
        // `echo hi; ls` 会被当成 echo 的参数（"hi;" "ls"）整串回显、
        // 后半段从不执行且不报错（issue #11 ④「静默错误」实景）。
        val seq = splitSequence(cleanInput)
        if (seq.size > 1) {
            return ParsedCommand(
                type = CommandType.SEQUENCE,
                sequence = seq.map { parseSegment(it.second) },
                operators = seq.map { it.first },
                background = bg
            )
        }

        return parseSegment(cleanInput).copy(background = bg)
    }

    /** 解析单段（不含顶层 ; && ||）：管道 / 重定向 / 简单命令。 */
    private fun parseSegment(segment: String): ParsedCommand {
        val cleanInput = segment.trim()

        // 处理管道（引号内的 | 不切：`echo "a|b"` 曾被切成三段，存量 bug）
        val stages = splitTopLevel(cleanInput, '|')
        if (stages.size > 1) {
            val commands = stages.map { parseSingle(it.trim()) }
            return ParsedCommand(
                type = CommandType.PIPELINE,
                pipeline = commands,
                background = false
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
                background = false
            )
        } else {
            ParsedCommand(
                type = CommandType.SIMPLE,
                mainCommand = cmd,
                background = false
            )
        }
    }

    /** 按单个分隔符做**引号感知**的顶层切分（转义 / 单引号 / 双引号内的分隔符不切）。 */
    private fun splitTopLevel(input: String, sep: Char): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var inSingle = false
        var inDouble = false
        var escaped = false
        for (ch in input) {
            when {
                escaped -> { cur.append(ch); escaped = false }
                ch == '\\' && !inSingle -> { cur.append(ch); escaped = true }
                ch == '\'' && !inDouble -> { inSingle = !inSingle; cur.append(ch) }
                ch == '"' && !inSingle -> { inDouble = !inDouble; cur.append(ch) }
                ch == sep && !inSingle && !inDouble -> { out += cur.toString(); cur.clear() }
                else -> cur.append(ch)
            }
        }
        out += cur.toString()
        return out
    }

    /**
     * 按顶层分隔符切出命令序列（**引号内不切**）。
     *
     * @return [(该段之前的运算符, 段文本)]；第一段运算符为 ""。
     *   单 `|` 不在此处理（留给管道逻辑），只有 `||` 才是序列运算符。
     */
    private fun splitSequence(input: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        val cur = StringBuilder()
        var inSingle = false
        var inDouble = false
        var escaped = false
        var pendingOp = ""
        var i = 0
        while (i < input.length) {
            val ch = input[i]
            when {
                escaped -> { cur.append(ch); escaped = false; i++ }
                ch == '\\' && !inSingle -> { cur.append(ch); escaped = true; i++ }
                ch == '\'' && !inDouble -> { inSingle = !inSingle; cur.append(ch); i++ }
                ch == '"' && !inSingle -> { inDouble = !inDouble; cur.append(ch); i++ }
                !inSingle && !inDouble && ch == '&' && i + 1 < input.length && input[i + 1] == '&' -> {
                    out += pendingOp to cur.toString(); cur.clear(); pendingOp = "&&"; i += 2
                }
                !inSingle && !inDouble && ch == '|' && i + 1 < input.length && input[i + 1] == '|' -> {
                    out += pendingOp to cur.toString(); cur.clear(); pendingOp = "||"; i += 2
                }
                !inSingle && !inDouble && ch == ';' -> {
                    out += pendingOp to cur.toString(); cur.clear(); pendingOp = ";"; i++
                }
                else -> { cur.append(ch); i++ }
            }
        }
        out += pendingOp to cur.toString()
        return out
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
    val background: Boolean = false,
    /** 序列（; && ||）的每一段（每段可再是管道/重定向/简单命令）。 */
    val sequence: List<ParsedCommand> = emptyList(),
    /** sequence[i] 之前的运算符：""（首段）/ ";" / "&&" / "||"。与 sequence 等长。 */
    val operators: List<String> = emptyList()
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
    SIMPLE, PIPELINE, REDIRECTION, SEQUENCE
}

enum class RedirectType {
    NONE, OVERWRITE, APPEND, STDERR
}

data class RedirectInfo(
    val command: String,
    val type: RedirectType,
    val target: String
)
