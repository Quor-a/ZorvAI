package com.ai.assistance.quro.core.novaterm.command

import com.ai.assistance.quro.core.novaterm.core.*
import com.ai.assistance.quro.core.novaterm.core.SessionManager

/**
 * 内置命令接口
 */
interface BuiltinCommand {
    val name: String
    val aliases: List<String> get() = emptyList()
    fun execute(sessionId: String, cmd: Command): CommandResult
    fun help(): String
}

// ========== 命令分类标记 ==========
annotation class FileCmd
annotation class SystemCmd
annotation class TextCmd
annotation class NetworkCmd
annotation class UtilCmd
