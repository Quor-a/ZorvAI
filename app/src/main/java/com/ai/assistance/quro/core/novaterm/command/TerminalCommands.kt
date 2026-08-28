package com.ai.assistance.quro.core.novaterm.command

import com.ai.assistance.quro.core.novaterm.core.*
import com.ai.assistance.quro.core.novaterm.core.SessionManager
import com.ai.assistance.quro.core.novaterm.core.PermissionController

/**
 * 终端控制命令集
 */
object ClearCommand : BuiltinCommand {
    override val name = "clear"
    override fun execute(sessionId: String, cmd: Command): CommandResult {
        return CommandResult.Text("", 0, false) // 特殊处理：UI 层清空
    }
    override fun help() = "clear  - 清空终端"
}

object HistoryCommand : BuiltinCommand {
    override val name = "history"
    override fun execute(sessionId: String, cmd: Command): CommandResult {
        val hist = SessionManager.getHistory(sessionId)
        val lines = hist.mapIndexed { i, cmd -> "${i + 1}  $cmd" }
        return CommandResult.ok(lines.joinToString("\n"))
    }
    override fun help() = "history  - 显示命令历史"
}

object HelpCommand : BuiltinCommand {
    override val name = "help"
    override fun execute(sessionId: String, cmd: Command): CommandResult {
        val lines = listOf(
            "╔══════════════════════════════════════════════╗",
            "║        QuroTerm v1.0 — Command List         ║",
            "╠══════════════════════════════════════════════╣",
            "║  📁 File System                             ║",
            "║    ls cd pwd cat mkdir rm cp mv touch       ║",
            "║    find tree                                ║",
            "║  📝 Text                                    ║",
            "║    echo grep head tail wc sort uniq         ║",
            "║  ⚙️  System                                 ║",
            "║    ps top mem cpuinfo battery netstat      ║",
            "║  🌐 Network                                 ║",
            "║    ping curl dns                            ║",
            "║  🔧 Utility                                 ║",
            "║    run alias export encrypt base64          ║",
            "║    compress                                 ║",
            "║  🎛  Terminal                               ║",
            "║    clear history help man theme exit        ║",
            "║    su sandbox                               ║",
            "╚══════════════════════════════════════════════╝",
            "",
            "Type 'man <command>' for detailed help."
        )
        return CommandResult.ok(lines.joinToString("\n"))
    }
    override fun help() = "help  - 显示帮助信息"
}

object ManCommand : BuiltinCommand {
    override val name = "man"
    override fun execute(sessionId: String, cmd: Command): CommandResult {
        val target = cmd.getArg(0)
        if (target.isEmpty()) return CommandResult.err("man: missing command name")

        val help = CommandDispatcher.getHelp(target)
        return CommandResult.ok("📖 $target\n$help")
    }
    override fun help() = "man <command>  - 显示命令帮助"
}

object ThemeCommand : BuiltinCommand {
    override val name = "theme"
    override fun execute(sessionId: String, cmd: Command): CommandResult {
        val themeName = cmd.getArg(0)
        if (themeName.isEmpty()) {
            return CommandResult.ok("Available themes: matrix, cyberpunk, dracula, nord, solarized\nCurrent: ${SessionManager.getEnv(sessionId, "THEME") ?: "matrix"}")
        }
        SessionManager.setEnv(sessionId, "THEME", themeName)
        return CommandResult.ok("Theme set to: $themeName")
    }
    override fun help() = "theme [name]  - 切换终端主题"
}

object AliasCommand : BuiltinCommand {
    override val name = "alias"
    override fun execute(sessionId: String, cmd: Command): CommandResult {
        if (cmd.args.isEmpty()) {
            val aliases = SessionManager.getSession(sessionId)?.aliases ?: emptyMap()
            if (aliases.isEmpty()) return CommandResult.ok("No aliases defined")
            return CommandResult.ok(aliases.entries.joinToString("\n") { "${it.key}='${it.value}'" })
        }

        val definition = cmd.args.joinToString(" ")
        val parts = definition.split("=")
        if (parts.size >= 2) {
            val name = parts[0].trim()
            val value = parts[1].trim().removeSurrounding("'").removeSurrounding("\"")
            SessionManager.addAlias(sessionId, name, value)
            return CommandResult.ok("alias $name='$value'")
        }
        return CommandResult.err("alias: invalid syntax. Use: alias name='command'")
    }
    override fun help() = "alias [name='command']  - 创建命令别名"
}

object ExportCommand : BuiltinCommand {
    override val name = "export"
    override fun execute(sessionId: String, cmd: Command): CommandResult {
        if (cmd.args.isEmpty()) {
            val env = SessionManager.getAllEnv(sessionId)
            return CommandResult.ok(env.entries.joinToString("\n") { "${it.key}=${it.value}" })
        }

        val definition = cmd.args.joinToString("=")
        val parts = definition.split("=")
        if (parts.size >= 2) {
            val key = parts[0].trim()
            val value = parts.drop(1).joinToString("=").trim()
            SessionManager.setEnv(sessionId, key, value)
            return CommandResult.ok("export $key=$value")
        }
        return CommandResult.err("export: invalid syntax. Use: export KEY=value")
    }
    override fun help() = "export [KEY=value]  - 设置环境变量"
}

object ExitCommand : BuiltinCommand {
    override val name = "exit"
    override fun execute(sessionId: String, cmd: Command): CommandResult {
        SessionManager.destroySession(sessionId)
        return CommandResult.ok("Session closed.")
    }
    override fun help() = "exit  - 关闭当前会话"
}

object SuCommand : BuiltinCommand {
    override val name = "su"
    override fun execute(sessionId: String, cmd: Command): CommandResult {
        val target = cmd.getArg(0, "root")
        val level = when (target.lowercase()) {
            "root" -> PermissionController.PermissionLevel.ROOT
            "dev", "developer" -> PermissionController.PermissionLevel.DEVELOPER
            "user" -> PermissionController.PermissionLevel.USER
            "guest" -> {
                // GUEST 已被 PermissionController.getLevel() 强制提升为 USER，
                // 降级到 GUEST 无意义且会锁死命令，直接拒绝。
                return CommandResult.err("su: 降级到 GUEST 已被禁止（GUEST 无任何命令权限，会锁死终端）")
            }
            else -> null
        }
        if (level == null) return CommandResult.err("su: unknown level '$target'")

        // 模拟提权验证
        val ok = PermissionController.elevate(sessionId, level)
        return if (ok) {
            CommandResult.ok("Permission elevated to: $target")
        } else {
            CommandResult.err("su: permission denied")
        }
    }
    override fun help() = "su [root|dev|user]  - 切换权限等级（guest 已禁用）"
}
