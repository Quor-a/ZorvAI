package com.ai.assistance.quro.terminal.domain

/**
 * 提示符识别器。
 *
 * 职责单一：用纯正则匹配 shell 提示符与当前工作目录（CWD）标记。
 * 不含任何会话状态，仅做文本判定。OutputProcessor 的状态机据此判断
 * 一条命令何时结束、下一次提示符何时出现。
 */
object PromptDetector {

    // <cwd>/path</cwd># 形式的主提示符（由 shell 脚本注入，路径最可靠）
    private val CWD_PROMPT_REGEX = Regex("<cwd>(.*)</cwd>.*[#$]")

    // 形如 "root@host:~/path#" 的兜底提示符
    private val FALLBACK_PATH_REGEX = Regex(""".*:\s*(~?/?.*)\s*[#$]$""")

    // 形如 "user@host:~$" 的通用提示符
    private val GENERIC_USER_PROMPT_REGEX = Regex(".*@[a-zA-Z0-9.\\-]+\\s?:\\s?~?/?.*[#$]\\s*$")

    // root 专用提示符
    private val ROOT_PROMPT_REGEX = Regex("root@[a-zA-Z0-9.\\-]+:\\s?~?/?.*#\\s*$")

    /**
     * 在行内查找 CWD 主提示符标记，命中则返回匹配结果（含捕获的工作目录路径）。
     */
    fun findCwdPrompt(line: String): MatchResult? = CWD_PROMPT_REGEX.find(line)

    /**
     * 判断去除首尾空白后的文本是否为兜底提示符（以 $/# 结尾或匹配常见 user@host 形态）。
     */
    fun isFallbackPrompt(trimmed: String): Boolean =
        trimmed.endsWith("$") ||
            trimmed.endsWith("#") ||
            trimmed.endsWith("$ ") ||
            trimmed.endsWith("# ") ||
            GENERIC_USER_PROMPT_REGEX.matches(trimmed) ||
            ROOT_PROMPT_REGEX.matches(trimmed)

    /**
     * 从兜底提示符中提取工作目录路径；无法解析时原样返回。
     */
    fun extractFallbackPath(trimmed: String): String =
        FALLBACK_PATH_REGEX.find(trimmed)?.groups?.get(1)?.value?.trim() ?: trimmed
}
