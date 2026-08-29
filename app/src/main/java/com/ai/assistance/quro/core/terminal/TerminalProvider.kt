package com.ai.assistance.quro.core.terminal

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.util.Log

/**
 * 终端 ContentProvider - 暴露终端会话数据。
 *
 * Authority: com.ai.assistance.quro.terminal
 */
class TerminalProvider : ContentProvider() {

    companion object {
        private const val TAG = "TerminalProvider"
        const val AUTHORITY = "com.ai.assistance.quro.terminal"

        private const val SESSIONS = 1
        private const val SESSION_ID = 2
        private const val EXEC = 3

        private val uriMatcher = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(AUTHORITY, "sessions", SESSIONS)
            addURI(AUTHORITY, "sessions/*", SESSION_ID)
            addURI(AUTHORITY, "exec", EXEC)
        }
    }

    override fun onCreate(): Boolean {
        Log.d(TAG, "TerminalProvider 创建")
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        return when (uriMatcher.match(uri)) {
            SESSIONS -> querySessions()
            SESSION_ID -> {
                val sessionId = uri.lastPathSegment ?: return null
                querySession(sessionId)
            }
            else -> null
        }
    }

    override fun getType(uri: Uri): String {
        return when (uriMatcher.match(uri)) {
            SESSIONS -> "vnd.android.cursor.dir/vnd.$AUTHORITY.sessions"
            SESSION_ID -> "vnd.android.cursor.item/vnd.$AUTHORITY.sessions"
            EXEC -> "vnd.android.cursor.item/vnd.$AUTHORITY.exec"
            else -> "application/octet-stream"
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        if (uriMatcher.match(uri) != EXEC) {
            throw UnsupportedOperationException("不支持的插入操作: $uri")
        }
        val command = values?.getAsString("command") ?: throw IllegalArgumentException("缺少 command")
        val timeout = values.getAsLong("timeout") ?: 14L
        // 执行命令（在调用方线程同步执行）
        val result = try {
            val r = QuroTerminalController.runCommand(command, timeout * 1000, context)
            "${r.exitCode}\n${r.output}\n${r.error ?: ""}"
        } catch (e: Exception) {
            "-1\n\n${e.message}"
        }
        Log.d(TAG, "exec result: $result")
        return Uri.parse("content://$AUTHORITY/exec/result")
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        throw UnsupportedOperationException("不支持的删除操作")
    }

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
        throw UnsupportedOperationException("不支持的更新操作")
    }

    private fun querySessions(): Cursor {
        val cursor = MatrixCursor(arrayOf("session_id", "session_name", "session_mode", "session_alive", "is_default"))
        val sessions = QuroTerminalSessionManager.listSessions()
        for (s in sessions) {
            cursor.addRow(arrayOf<Any>(s.id, s.name, s.backend.name, s.alive, s.isDefault))
        }
        return cursor
    }

    private fun querySession(sessionId: String): Cursor {
        val cursor = MatrixCursor(arrayOf("session_id", "session_name", "session_mode", "session_alive", "is_default"))
        val sessions = QuroTerminalSessionManager.listSessions()
        val session = sessions.find { it.id == sessionId }
        if (session != null) {
            cursor.addRow(arrayOf<Any>(session.id, session.name, session.backend.name, session.alive, session.isDefault))
        }
        return cursor
    }
}
