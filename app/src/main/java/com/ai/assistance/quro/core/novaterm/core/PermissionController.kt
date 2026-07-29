package com.ai.assistance.quro.core.novaterm.core

/**
 * 权限控制系统
 * 自研权限模型，不依赖 Linux UID/GID
 */
object PermissionController {

    enum class PermissionLevel {
        GUEST,    // 只能看
        USER,     // 普通操作
        DEVELOPER,// 系统信息、网络
        ROOT      // 全部权限
    }

    private val sessionPermissions = mutableMapOf<String, PermissionLevel>()

    fun initSession(sessionId: String, level: PermissionLevel = PermissionLevel.USER) {
        sessionPermissions[sessionId] = level
    }

    fun destroySession(sessionId: String) {
        sessionPermissions.remove(sessionId)
    }

    fun getLevel(sessionId: String): PermissionLevel =
        sessionPermissions[sessionId] ?: PermissionLevel.GUEST

    fun require(sessionId: String, required: PermissionLevel): Boolean {
        val current = getLevel(sessionId)
        return current.ordinal >= required.ordinal
    }

    fun elevate(sessionId: String, target: PermissionLevel): Boolean {
        // 模拟提权（实际可接入 su 验证）
        sessionPermissions[sessionId] = target
        return true
    }

    fun deescalate(sessionId: String) {
        sessionPermissions[sessionId] = PermissionLevel.USER
    }

    // 命令权限映射
    private val commandPermissions = mapOf(
        "ls" to PermissionLevel.USER,
        "cd" to PermissionLevel.USER,
        "pwd" to PermissionLevel.USER,
        "cat" to PermissionLevel.USER,
        "mkdir" to PermissionLevel.USER,
        "rm" to PermissionLevel.USER,
        "cp" to PermissionLevel.USER,
        "mv" to PermissionLevel.USER,
        "echo" to PermissionLevel.USER,
        "touch" to PermissionLevel.USER,
        "find" to PermissionLevel.USER,
        "tree" to PermissionLevel.USER,
        "head" to PermissionLevel.USER,
        "tail" to PermissionLevel.USER,
        "wc" to PermissionLevel.USER,
        "sort" to PermissionLevel.USER,
        "uniq" to PermissionLevel.USER,
        "grep" to PermissionLevel.USER,
        "clear" to PermissionLevel.USER,
        "history" to PermissionLevel.USER,
        "help" to PermissionLevel.USER,
        "man" to PermissionLevel.USER,
        "theme" to PermissionLevel.USER,
        "alias" to PermissionLevel.USER,
        "export" to PermissionLevel.USER,
        "run" to PermissionLevel.USER,
        "ps" to PermissionLevel.DEVELOPER,
        "top" to PermissionLevel.DEVELOPER,
        "mem" to PermissionLevel.DEVELOPER,
        "cpuinfo" to PermissionLevel.DEVELOPER,
        "battery" to PermissionLevel.DEVELOPER,
        "netstat" to PermissionLevel.DEVELOPER,
        "ping" to PermissionLevel.DEVELOPER,
        "curl" to PermissionLevel.DEVELOPER,
        "wget" to PermissionLevel.DEVELOPER,
        "dns" to PermissionLevel.DEVELOPER,
        "pkg" to PermissionLevel.DEVELOPER,
        "su" to PermissionLevel.DEVELOPER,
        "sandbox" to PermissionLevel.DEVELOPER,
        "encrypt" to PermissionLevel.DEVELOPER,
        "compress" to PermissionLevel.DEVELOPER,
        "base64" to PermissionLevel.DEVELOPER,
        "shutdown" to PermissionLevel.ROOT,
        "reboot" to PermissionLevel.ROOT,
        "mount" to PermissionLevel.ROOT
    )

    fun getRequiredLevel(command: String): PermissionLevel =
        commandPermissions[command] ?: PermissionLevel.USER

    fun checkCommand(sessionId: String, command: String): Pair<Boolean, String?> {
        val required = getRequiredLevel(command.split(" ").first())
        val current = getLevel(sessionId)
        return if (current.ordinal >= required.ordinal) {
            true to null
        } else {
            false to "Permission denied: '$command' requires ${required.name} (current: ${current.name})"
        }
    }
}
