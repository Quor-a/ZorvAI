package com.ai.assistance.quro.terminal.utils

/**
 * 命令粘贴净化器。
 *
 * 场景：用户从聊天/文档里复制命令时，常夹带说明文字、Markdown 代码块围栏、
 * shell 提示符（`$`/`#`/`>`、`user@host:~$`）、`sudo`、注释行等。直接粘贴会把这些
 * 非命令内容一起发进 PTY 导致执行失败。
 *
 * 本工具把「粘贴文本 → 纯命令列表」这一判定收敛到一处，供：
 *  - 非全屏 InputBar 的「粘贴」按钮（合并为单行 `&&` 连接）
 *  - 全屏 CanvasTerminalView 的 commitText（多行逐行发送）
 */
object CommandSanitizer {

    /**
     * 把粘贴文本净化为命令列表。
     *  - 丢弃 Markdown 代码块围栏（```）、注释行（# 开头）
     *  - 剥离行首 `sudo`
     *  - 剥离 shell 提示符：`$`、`#`、`>` 以及 `user@host:~$` / `root@host:~#`
     *  - 丢弃空行
     *
     * 返回的每条命令已 trim。若全部被过滤则返回空列表。
     */
    fun sanitizeToCommands(raw: String): List<String> {
        return raw.lines()
            .map { strip(it) }
            .filter { it.isNotBlank() }
    }

    /**
     * 净化为单行命令（多条命令用 `&&` 连接），用于 singleLine 输入框。
     */
    fun sanitizeSingleLine(raw: String): String =
        sanitizeToCommands(raw).joinToString(" && ")

    /**
     * 净化为多行文本（每条命令一行），用于直接逐行发进 PTY 的全屏粘贴。
     */
    fun sanitizeMultiline(raw: String): String =
        sanitizeToCommands(raw).joinToString("\n")

    private fun strip(line: String): String {
        var l = line.trimEnd()

        // Markdown 代码块围栏（``` 或 ~~~）
        if (l.trimStart().startsWith("```") || l.trimStart().startsWith("~~~")) return ""
        if (l.trim().startsWith("#")) return ""   // 注释行

        l = l.trim()

        // 去掉行首 sudo（proot 内本即 root，sudo 多余且可能不存在该命令）
        l = l.replace(Regex("^sudo\\s+"), "")

        // 去掉 shell 提示符：`$`、`#`、`>` 单独成前缀
        l = l.replace(Regex("^[$#>]\\s*"), "")

        // 去掉完整提示符：`user@host:~$ `、`root@host:/path# `、`(venv) user@host:~$ ` 等
        l = l.replace(Regex("^\\(?[A-Za-z0-9_.-]+\\)?\\s*[A-Za-z0-9_.-]+@[A-Za-z0-9_.-]+[:~][^$#]*[$#>]\\s*"), "")

        return l
    }
}
