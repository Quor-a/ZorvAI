package com.ai.assistance.quro.core.novaterm.core

import java.util.UUID

/**
 * 会话管理器
 * 支持多终端会话，每个会话有独立 CWD、历史、环境变量
 */
object SessionManager {

    data class Session(
        val id: String,
        val name: String,
        val createdAt: Long = System.currentTimeMillis(),
        val env: MutableMap<String, String> = mutableMapOf(),
        val history: MutableList<String> = mutableListOf(),
        val aliases: MutableMap<String, String> = mutableMapOf()
    )

    private val sessions = mutableMapOf<String, Session>()
    private var activeSessionId: String? = null

    fun createSession(name: String = "main"): String {
        val id = UUID.randomUUID().toString().take(8)
        val session = Session(id = id, name = name)

        // 初始化
        FileSystem.initSession(id)
        PermissionController.initSession(id)

        // 默认环境变量
        session.env["HOME"] = "/"
        session.env["PATH"] = "/bin:/usr/bin"
        session.env["SHELL"] = "novaterm"
        session.env["USER"] = "user"
        session.env["PROMPT"] = "\$ "

        sessions[id] = session
        if (activeSessionId == null) activeSessionId = id
        return id
    }

    fun destroySession(id: String) {
        FileSystem.destroySession(id)
        PermissionController.destroySession(id)
        sessions.remove(id)
        if (activeSessionId == id) activeSessionId = sessions.keys.firstOrNull()
    }

    fun getActiveSession(): Session? =
        activeSessionId?.let { sessions[it] }

    fun getSession(id: String): Session? = sessions[id]

    fun setActive(id: String): Boolean {
        if (sessions.containsKey(id)) {
            activeSessionId = id
            return true
        }
        return false
    }

    fun listSessions(): List<Session> = sessions.values.toList()

    fun addHistory(id: String, command: String) {
        sessions[id]?.history?.add(command)
    }

    fun getHistory(id: String): List<String> =
        sessions[id]?.history?.toList() ?: emptyList()

    fun setEnv(id: String, key: String, value: String) {
        sessions[id]?.env?.set(key, value)
    }

    fun getEnv(id: String, key: String): String? =
        sessions[id]?.env?.get(key)

    fun getAllEnv(id: String): Map<String, String> =
        sessions[id]?.env?.toMap() ?: emptyMap()

    fun addAlias(id: String, alias: String, command: String) {
        sessions[id]?.aliases?.set(alias, command)
    }

    fun getAlias(id: String, alias: String): String? =
        sessions[id]?.aliases?.get(alias)

    fun resolveAlias(id: String, input: String): String {
        val parts = input.split(" ")
        val cmd = parts.first()
        val alias = sessions[id]?.aliases?.get(cmd)
        return if (alias != null) {
            alias + " " + parts.drop(1).joinToString(" ")
        } else {
            input
        }
    }
}
