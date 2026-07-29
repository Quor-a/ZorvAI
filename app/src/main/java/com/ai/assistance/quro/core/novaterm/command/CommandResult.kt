package com.ai.assistance.quro.core.novaterm.command

import com.ai.assistance.quro.core.novaterm.core.*
/**
 * 命令执行结果
 * 支持文本、结构化数据、Composable 输出
 */
sealed class CommandResult {
    data class Text(
        val output: String,
        val exitCode: Int = 0,
        val isError: Boolean = false
    ) : CommandResult()

    data class RichText(
        val lines: List<OutputLine>
    ) : CommandResult()

    data class Structured(
        val data: List<Map<String, String>>
    ) : CommandResult()

    data class Interactive(
        val prompt: String,
        val callbackId: String
    ) : CommandResult()

    data class Binary(
        val bytes: ByteArray,
        val mimeType: String = "application/octet-stream"
    ) : CommandResult()

    companion object {
        fun ok(text: String) = Text(text, 0, false)
        fun err(text: String) = Text(text, 1, true)
        fun empty() = Text("", 0, false)
    }
}

data class OutputLine(
    val text: String,
    val style: OutputStyle = OutputStyle.NORMAL,
    val color: Int? = null
)

enum class OutputStyle {
    NORMAL,
    BOLD,
    DIM,
    ITALIC,
    UNDERLINE,
    HEADER,
    SUCCESS,
    WARNING,
    ERROR,
    INFO,
    DEBUG,
    PROMPT,
    CYAN,
    MAGENTA,
    YELLOW,
    GREEN,
    RED,
    BLUE
}
