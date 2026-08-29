package com.ai.assistance.quro.core.terminal

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.util.Log
import kotlinx.coroutines.runBlocking

/**
 * 终端 ContentProvider — 符合 Android 标准的跨应用数据共享接口。
 *
 * ContentProvider 是安卓四大组件之一，核心职责：
 * 1. 跨应用数据共享：安卓有进程隔离，应用数据默认私有。ContentProvider 是唯一标准化的跨应用数据访问机制。
 * 2. 数据抽象：无论背后是 SQLite、文件还是其他存储，外部都通过统一的 ContentResolver + URI 寻址访问。
 * 3. 标准化 CRUD 接口：query/insert/update/delete/getType。
 * 4. 细粒度权限控制：readPermission/writePermission + URI 临时权限授予。
 * 5. URI 寻址：content://authority/path/id 形式，系统根据 authority 路由到对应 Provider。
 *
 * Authority: com.ai.assistance.quro.terminal
 *
 * URI 格式：
 *   content://com.ai.assistance.quro.terminal/sessions              → 会话列表
 *   content://com.ai.assistance.quro.terminal/sessions/{id}         → 指定会话
 *   content://com.ai.assistance.quro.terminal/sessions/{id}/output   → 会话输出历史
 *   content://com.ai.assistance.quro.terminal/exec                   → 执行命令（通过 insert）
 *   content://com.ai.assistance.quro.terminal/status                 → 服务状态
 *   content://com.ai.assistance.quro.terminal/capabilities           → 能力列表
 *
 * 权限模型：
 * - readPermission: ai.aci.permission.READ_TERMINAL（读取会话数据）
 * - writePermission: ai.aci.permission.WRITE_TERMINAL（执行命令、创建/销毁会话）
 * - URI 临时权限：通过 Intent.FLAG_GRANT_READ_URI_PERMISSION 授予
 *
 * 使用示例：
 *   // 查询所有会话
 *   val cursor = contentResolver.query(
 *       Uri.parse("content://com.ai.assistance.quro.terminal/sessions"),
 *       null, null, null, null
 *   )
 *
 *   // 查询指定会话
 *   val cursor = contentResolver.query(
 *       Uri.parse("content://com.ai.assistance.quro.terminal/sessions/abc123"),
 *       null, null, null, null
 *   )
 *
 *   // 执行命令（通过 insert）
 *   val values = ContentValues().apply {
 *       put("command", "uname -a")
 *       put("timeout", 14L)
 *   }
 *   contentResolver.insert(
 *       Uri.parse("content://com.ai.assistance.quro.terminal/exec"),
 *       values
 *   )
 *
 *   // 获取服务状态
 *   val cursor = contentResolver.query(
 *       Uri.parse("content://com.ai.assistance.quro.terminal/status"),
 *       null, null, null, null
 *   )
 */
class TerminalProvider : ContentProvider() {

    companion object {
        private const val TAG = "TerminalProvider"
        const val AUTHORITY = "com.ai.assistance.quro.terminal"

        // URI 匹配码
        private const val SESSIONS = 1
        private const val SESSION_ID = 2
        private const val SESSION_OUTPUT = 3
        private const val EXEC = 4
        private const val STATUS = 5
        private const val CAPABILITIES = 6

        // 列定义
        val SESSION_COLUMNS = arrayOf(
            "session_id", "session_name", "session_alive", "is_default"
        )
        val OUTPUT_COLUMNS = arrayOf("line_index", "line_content")
        val STATUS_COLUMNS = arrayOf(
            "running", "session_count", "uptime", "version"
        )
        val CAPABILITY_COLUMNS = arrayOf("capability_name", "description")

        // URI 匹配器
        private val uriMatcher = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(AUTHORITY, "sessions", SESSIONS)
            addURI(AUTHORITY, "sessions/*", SESSION_ID)
            addURI(AUTHORITY, "sessions/*/output", SESSION_OUTPUT)
            addURI(AUTHORITY, "exec", EXEC)
            addURI(AUTHORITY, "status", STATUS)
            addURI(AUTHORITY, "capabilities", CAPABILITIES)
        }

        /**
         * 检查调用方是否有指定权限。
         *
         * 使用 Binder.getCallingUid() 获取调用方 UID，而不是 Process.myUid()。
         * 这是 ContentProvider 权限检查的标准做法。
         */
        fun checkPermission(ctx: android.content.Context, permission: String): Boolean {
            val callingUid = Binder.getCallingUid()
            val myUid = android.os.Process.myUid()
            if (callingUid == myUid) return true  // 自身调用无需检查
            return ctx.checkCallingPermission(permission) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    override fun onCreate(): Boolean {
        Log.d(TAG, "TerminalProvider 创建 (pid=${android.os.Process.myPid()})")
        return true
    }

    // ========== query — 标准化读取接口 ==========

    /**
     * 查询数据。
     *
     * 外部应用通过 ContentResolver.query() 调用此方法。
     * 系统根据 URI 的 authority 部分路由到本 Provider。
     *
     * @param uri Content URI
     * @param projection 需要的列（null = 所有列）
     * @param selection WHERE 条件
     * @param selectionArgs WHERE 条件参数
     * @param sortOrder 排序方式
     * @return Cursor 游标，外部通过 Cursor 遍历结果
     */
    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        Log.d(TAG, "query: $uri")

        val ctx = context ?: return null

        // 权限检查
        if (!checkPermission(ctx, "ai.aci.permission.READ_TERMINAL")) {
            throw SecurityException("缺少 ai.aci.permission.READ_TERMINAL 权限")
        }

        return when (uriMatcher.match(uri)) {
            SESSIONS -> querySessions()
            SESSION_ID -> {
                val sessionId = uri.lastPathSegment ?: return null
                querySession(sessionId)
            }
            SESSION_OUTPUT -> {
                val sessionId = uri.pathSegments[1]
                val limit = uri.getQueryParameter("limit")?.toIntOrNull() ?: 100
                querySessionOutput(sessionId, limit)
            }
            STATUS -> queryStatus()
            CAPABILITIES -> queryCapabilities()
            else -> {
                Log.w(TAG, "未知 URI: $uri")
                null
            }
        }
    }

    // ========== insert — 创建数据 ==========

    /**
     * 插入数据。
     *
     * 主要用于：
     * 1. 在 /exec URI 上执行命令
     * 2. 在 /sessions URI 上创建新会话
     *
     * @param uri Content URI
     * @param values 要插入的数据
     * @return 新创建的 URI（如有）
     */
    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        Log.d(TAG, "insert: $uri")

        val ctx = context ?: return null

        // 写入权限检查
        if (!checkPermission(ctx, "ai.aci.permission.WRITE_TERMINAL")) {
            throw SecurityException("缺少 ai.aci.permission.WRITE_TERMINAL 权限")
        }

        return when (uriMatcher.match(uri)) {
            EXEC -> insertExec(values, ctx)
            SESSIONS -> insertSession(values, ctx)
            else -> throw UnsupportedOperationException("不支持的插入操作: $uri")
        }
    }

    // ========== update — 更新数据 ==========

    /**
     * 更新数据。
     *
     * @param uri Content URI
     * @param values 要更新的数据
     * @param selection WHERE 条件
     * @param selectionArgs WHERE 条件参数
     * @return 受影响的行数
     */
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int {
        Log.d(TAG, "update: $uri")

        val ctx = context ?: return 0

        // 写入权限检查
        if (!checkPermission(ctx, "ai.aci.permission.WRITE_TERMINAL")) {
            throw SecurityException("缺少 ai.aci.permission.WRITE_TERMINAL 权限")
        }

        // 目前不支持 update 操作
        return 0
    }

    // ========== delete — 删除数据 ==========

    /**
     * 删除数据。
     *
     * 主要用于销毁终端会话。
     *
     * @param uri Content URI
     * @param selection WHERE 条件
     * @param selectionArgs WHERE 条件参数
     * @return 受影响的行数
     */
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        Log.d(TAG, "delete: $uri")

        val ctx = context ?: return 0

        // 写入权限检查
        if (!checkPermission(ctx, "ai.aci.permission.WRITE_TERMINAL")) {
            throw SecurityException("缺少 ai.aci.permission.WRITE_TERMINAL 权限")
        }

        return when (uriMatcher.match(uri)) {
            SESSION_ID -> {
                val sessionId = uri.lastPathSegment ?: return 0
                // 使用 runBlocking 调用 suspend 函数
                val destroyed = try {
                    runBlocking {
                        QuroTerminalSessionManager.destroySession(sessionId)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "销毁会话失败: ${e.message}", e)
                    false
                }
                if (destroyed) 1 else 0
            }
            else -> throw UnsupportedOperationException("不支持的删除操作: $uri")
        }
    }

    // ========== getType — MIME 类型 ==========

    /**
     * 返回 URI 对应的 MIME 类型。
     *
     * 外部应用通过 getType() 判断数据类型，用于：
     * - Intent 的 type 字段匹配
     * - ContentResolver 的类型检查
     */
    override fun getType(uri: Uri): String {
        return when (uriMatcher.match(uri)) {
            SESSIONS -> "vnd.android.cursor.dir/vnd.$AUTHORITY.sessions"
            SESSION_ID -> "vnd.android.cursor.item/vnd.$AUTHORITY.sessions"
            SESSION_OUTPUT -> "vnd.android.cursor.dir/vnd.$AUTHORITY.output"
            EXEC -> "vnd.android.cursor.item/vnd.$AUTHORITY.exec"
            STATUS -> "vnd.android.cursor.item/vnd.$AUTHORITY.status"
            CAPABILITIES -> "vnd.android.cursor.dir/vnd.$AUTHORITY.capabilities"
            else -> "application/octet-stream"
        }
    }

    // ========== 内部查询方法 ==========

    private fun querySessions(): Cursor {
        val cursor = MatrixCursor(SESSION_COLUMNS)
        val sessions = QuroTerminalSessionManager.listSessions()
        for (s in sessions) {
            cursor.addRow(arrayOf<Any>(
                s.id, s.name, s.alive, s.isDefault
            ))
        }
        return cursor
    }

    private fun querySession(sessionId: String): Cursor {
        val cursor = MatrixCursor(SESSION_COLUMNS)
        val session = QuroTerminalSessionManager.getSession(sessionId)
        if (session != null) {
            cursor.addRow(arrayOf<Any>(
                session.id, session.name, session.alive, session.isDefault
            ))
        }
        return cursor
    }

    private fun querySessionOutput(sessionId: String, limit: Int): Cursor {
        val cursor = MatrixCursor(OUTPUT_COLUMNS)
        val shell = QuroTerminalSessionManager.getShellSession(sessionId) ?: return cursor

        // 获取输出历史（取最后 limit 行）
        val allLines = shell.lines.toList()
        val output = if (allLines.size > limit) allLines.takeLast(limit) else allLines

        for ((index, line) in output.withIndex()) {
            cursor.addRow(arrayOf<Any>(index, line))
        }
        return cursor
    }

    private fun queryStatus(): Cursor {
        val cursor = MatrixCursor(STATUS_COLUMNS)
        val sessions = QuroTerminalSessionManager.listSessions()
        cursor.addRow(arrayOf<Any>(
            true,                    // running
            sessions.size,           // session_count
            System.currentTimeMillis(), // uptime
            "1.0.67"                 // version
        ))
        return cursor
    }

    private fun queryCapabilities(): Cursor {
        val cursor = MatrixCursor(CAPABILITY_COLUMNS)
        val capabilities = arrayOf(
            "exec" to "执行命令",
            "create_session" to "创建会话",
            "destroy_session" to "销毁会话",
            "send_input" to "发送输入",
            "get_session_status" to "获取会话状态",
            "list_sessions" to "列出所有会话",
            "set_session_env" to "设置环境变量",
            "get_session_env" to "获取环境变量",
            "list_capabilities" to "列出能力",
            "get_service_status" to "获取服务状态",
            "get_audit_log" to "获取审计日志",
            "help" to "帮助信息"
        )
        for ((name, desc) in capabilities) {
            cursor.addRow(arrayOf<Any>(name, desc))
        }
        return cursor
    }

    // ========== 内部写入方法 ==========

    private fun insertExec(values: ContentValues?, ctx: android.content.Context): Uri? {
        val command = values?.getAsString("command")
            ?: throw IllegalArgumentException("缺少 command")
        val timeout = values.getAsLong("timeout") ?: 14L

        val result = try {
            QuroTerminalController.runCommand(command, timeout * 1000, ctx)
        } catch (e: Exception) {
            ShellResult(output = "", exitCode = -1, error = e.message ?: "未知错误")
        }

        Log.d(TAG, "exec: exit=${result.exitCode}, output=${result.output.take(100)}")

        // 返回结果 URI，调用方可通过 ContentResolver 查询
        return Uri.parse("content://$AUTHORITY/exec/result?exit=${result.exitCode}")
    }

    private fun insertSession(values: ContentValues?, ctx: android.content.Context): Uri? {
        val name = values?.getAsString("session_name")
            ?: "session_${System.currentTimeMillis()}"

        // 使用 runBlocking 调用 suspend 函数
        val session = try {
            runBlocking {
                QuroTerminalSessionManager.createSession(ctx, name)
            }
        } catch (e: Exception) {
            Log.e(TAG, "创建会话失败: ${e.message}", e)
            null
        }

        return if (session != null) {
            Uri.parse("content://$AUTHORITY/sessions/${session.id}")
        } else {
            null
        }
    }
}
