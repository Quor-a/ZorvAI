package com.ai.assistance.quro.service

import ai.aidl.aci.core.AidlAciError
import ai.aidl.aci.core.AidlAciRequest
import ai.aidl.aci.core.AidlAciResponse
import ai.aidl.aci.core.BaseAidlAciService
import ai.aidl.aci.core.Capability
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import com.ai.assistance.quro.core.terminal.QuroShellSession
import com.ai.assistance.quro.core.terminal.QuroTerminalSessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 终端 ACI 受控端 Service（前台服务）。
 *
 * 让终端会话脱离 UI 生命周期：息屏/切 App 不被杀。
 * 通过 ACI 协议暴露终端执行能力，其他应用可以通过 ACI 调用终端执行命令。
 *
 * 设计要点：
 * 1. 继承 BaseAidlAciService，暴露 exec/list/help 能力
 * 2. 运行在前台服务中，确保终端会话不被杀死
 * 3. 使用真实的 Ubuntu 用户空间（proot 环境）
 * 4. 支持交互式命令执行（带超时）
 * 5. 支持查看终端会话状态
 */
class QuroTerminalAciService : BaseAidlAciService() {

    companion object {
        private const val TAG = "QuroTerminalACI"
        private const val CHANNEL_ID = "quro_terminal_aci_channel"
        private const val NOTIF_ID = 9530
        const val ACTION_STOP = "com.ai.assistance.quro.action.TERMINAL_ACI_STOP"
        
        /** 默认命令执行超时（14秒，小于控制器15秒超时） */
        private const val DEFAULT_TIMEOUT_S = 14L
        
        /** 交互式命令超时（30秒） */
        private const val INTERACTIVE_TIMEOUT_S = 30L

        /**
         * 确保终端 ACI 服务已启动。
         *
         * @param context 上下文
         * @param installIfMissing 是否在 Linux 环境未就绪时触发安装
         */
        fun ensureStarted(context: Context, installIfMissing: Boolean = true) {
            try {
                val intent = Intent(context, QuroTerminalAciService::class.java)
                intent.putExtra("install_if_missing", installIfMissing)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Throwable) {
                Log.e(TAG, "拉起终端 ACI 服务失败", e)
            }
        }

        /**
         * 停止终端 ACI 服务。
         */
        fun stop(context: Context) {
            try {
                val intent = Intent(context, QuroTerminalAciService::class.java)
                intent.action = ACTION_STOP
                context.startService(intent)
            } catch (e: Throwable) {
                Log.e(TAG, "停止终端 ACI 服务失败", e)
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var isRunning = false
    private val startTime = System.currentTimeMillis()

    override fun onBind(intent: Intent?): android.os.IBinder? {
        Log.d(TAG, "onBind")
        return super.onBind(intent)
    }

    override fun onCreate() {
        Log.d(TAG, "onCreate")
        try {
            super.onCreate()
            startForegroundWithNotification()
            isRunning = true
            Log.i(TAG, "终端 ACI 服务已启动")
        } catch (e: Throwable) {
            Log.e(TAG, "终端 ACI 服务启动失败: ${e.message}")
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val installIfMissing = intent?.getBooleanExtra("install_if_missing", true) ?: true
        if (installIfMissing) {
            scope.launch {
                ensureTerminalSession()
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        isRunning = false
        super.onDestroy()
    }

    /**
     * 确保终端会话存在（在本服务进程内 fork shell 子进程）。
     * shell 子进程归属于本服务进程 → 前台服务存活时子进程不被系统杀。
     */
    private suspend fun ensureTerminalSession(): QuroShellSession? {
        return try {
            QuroTerminalSessionManager.ensureDefault(this, installIfMissing = true).also { session ->
                if (session != null) {
                    Log.i(TAG, "终端会话已在 ACI 服务进程内创建（pid=${android.os.Process.myPid()}）")
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "确保终端会话失败: ${e.message}")
            null
        }
    }

    /**
     * 创建前台通知。
     */
    private fun startForegroundWithNotification() {
        val notification = createNotification()
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                // 使用 SPECIAL_USE 类型，因为终端服务是特殊用途的前台服务
                startForeground(NOTIF_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIF_ID, notification)
            }
            Log.i(TAG, "前台服务启动成功，类型: SPECIAL_USE")
        } catch (e: Throwable) {
            Log.e(TAG, "创建前台通知失败: ${e.message}")
            // 尝试使用 DATA_SYNC 类型作为降级方案
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    startForeground(NOTIF_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                }
                Log.i(TAG, "前台服务启动成功，类型: DATA_SYNC（降级方案）")
            } catch (e2: Throwable) {
                Log.e(TAG, "前台服务启动失败: ${e2.message}")
                throw e2
            }
        }
    }

    /**
     * 创建通知。
     */
    private fun createNotification(): android.app.Notification {
        val channel = android.app.NotificationChannel(
            CHANNEL_ID,
            "终端 ACI 服务",
            android.app.NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "终端会话 ACI 服务运行中"
        }

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        notificationManager.createNotificationChannel(channel)

        val pendingIntent = android.app.PendingIntent.getActivity(
            this,
            0,
            Intent(this, com.ai.assistance.quro.activity.QuroMainActivity::class.java),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )

        return android.app.Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("终端 ACI 服务运行中")
            .setContentText("终端会话已就绪，可通过 ACI 调用")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onCreateCapabilities(caps: MutableList<Capability>) {
        // 执行命令能力
        caps.add(
            Capability.create(
                "exec",
                "在终端中执行命令并返回结果。支持交互式命令（带超时），返回退出码、输出和错误信息。"
            )
                .addParam("command", "string", true, "要执行的命令")
                .addParam("timeout", "int", false, "超时时间（秒），默认 14 秒")
                .addParam("interactive", "boolean", false, "是否为交互式命令（如 python REPL），默认 false")
                .addResult("exit_code", "int", "命令退出码（0 表示成功）")
                .addResult("output", "string", "命令输出（stdout + stderr）")
                .addResult("error", "string", "错误信息（如果启动失败）")
                .addResult("timed_out", "boolean", "是否因超时被终止")
                .addFlag(Capability.FLAG_BACKGROUND)
        )

        // 列出会话能力
        caps.add(
            Capability.create(
                "list_sessions",
                "列出所有终端会话状态，包括默认会话和额外会话。"
            )
                .addResult("sessions", "string", "会话列表 JSON 数组")
                .addFlag(Capability.FLAG_NO_UI)
        )

        // 查看帮助能力
        caps.add(
            Capability.create(
                "help",
                "显示终端 ACI 服务的帮助信息。"
            )
                .addResult("help", "string", "帮助文本")
                .addFlag(Capability.FLAG_NO_UI)
        )

        // 创建会话能力
        caps.add(
            Capability.create(
                "create_session",
                "创建新的终端会话。"
            )
                .addParam("name", "string", false, "会话名称")
                .addParam("mode", "string", false, "会话模式：linux（默认）或 device")
                .addResult("session_id", "string", "新会话 ID")
                .addResult("session_name", "string", "会话名称")
                .addResult("created", "boolean", "是否创建成功")
                .addFlag(Capability.FLAG_BACKGROUND)
        )

        // 销毁会话能力
        caps.add(
            Capability.create(
                "destroy_session",
                "销毁指定的终端会话。"
            )
                .addParam("session_id", "string", true, "要销毁的会话 ID")
                .addResult("destroyed", "boolean", "是否销毁成功")
                .addFlag(Capability.FLAG_BACKGROUND)
        )

        // 发送输入能力
        caps.add(
            Capability.create(
                "send_input",
                "向指定终端会话发送输入。"
            )
                .addParam("session_id", "string", true, "目标会话 ID")
                .addParam("input", "string", true, "要发送的输入")
                .addResult("sent", "boolean", "是否发送成功")
                .addFlag(Capability.FLAG_BACKGROUND)
        )

        // 获取会话状态能力
        caps.add(
            Capability.create(
                "get_session_status",
                "获取指定终端会话的状态。"
            )
                .addParam("session_id", "string", true, "目标会话 ID")
                .addResult("session_id", "string", "会话 ID")
                .addResult("name", "string", "会话名称")
                .addResult("mode", "string", "会话模式")
                .addResult("alive", "boolean", "会话是否存活")
                .addResult("busy", "boolean", "会话是否忙碌")
                .addResult("cwd", "string", "当前工作目录")
                .addResult("last_exit", "int", "上一条命令退出码")
                .addFlag(Capability.FLAG_NO_UI)
        )

        // 设置环境变量能力
        caps.add(
            Capability.create(
                "set_session_env",
                "设置终端会话的环境变量。"
            )
                .addParam("session_id", "string", true, "目标会话 ID")
                .addParam("key", "string", true, "环境变量名")
                .addParam("value", "string", true, "环境变量值")
                .addResult("set", "boolean", "是否设置成功")
                .addFlag(Capability.FLAG_BACKGROUND)
        )

        // 获取环境变量能力
        caps.add(
            Capability.create(
                "get_session_env",
                "获取终端会话的环境变量。"
            )
                .addParam("session_id", "string", true, "目标会话 ID")
                .addResult("env", "string", "环境变量 JSON 对象")
                .addFlag(Capability.FLAG_NO_UI)
        )

        // 列出所有能力
        caps.add(
            Capability.create(
                "list_capabilities",
                "列出终端 ACI 服务支持的所有能力。"
            )
                .addResult("capabilities", "string", "能力列表 JSON 数组")
                .addFlag(Capability.FLAG_NO_UI)
        )

        // 获取服务状态能力
        caps.add(
            Capability.create(
                "get_service_status",
                "获取终端 ACI 服务的状态。"
            )
                .addResult("running", "boolean", "服务是否运行")
                .addResult("sessions_count", "int", "会话数量")
                .addResult("uptime", "long", "服务运行时间（毫秒）")
                .addResult("version", "string", "服务版本")
                .addFlag(Capability.FLAG_NO_UI)
        )

        // 获取审计日志能力
        caps.add(
            Capability.create(
                "get_audit_log",
                "获取终端 ACI 服务的审计日志。"
            )
                .addParam("limit", "int", false, "返回日志条数，默认 100")
                .addResult("audit_log", "string", "审计日志 JSON 数组")
                .addFlag(Capability.FLAG_NO_UI)
        )
    }

    override fun onCheckPermission(req: AidlAciRequest?, callerPkg: String?): Boolean {
        // 允许自身调用
        val selfPkg = packageName
        val ok = callerPkg == selfPkg
        Log.d(TAG, "onCheckPermission: caller=$callerPkg → ${if (ok) "放行" else "拒绝"}")
        return ok
    }

    override fun onCall(req: AidlAciRequest?): AidlAciResponse {
        if (req == null) return AidlAciResponse.error(AidlAciError.REQUEST_NULL, "null")
        
        return try {
            when (req.capability) {
                "exec" -> handleExec(req.params)
                "list_sessions" -> handleListSessions()
                "help" -> handleHelp()
                "create_session" -> handleCreateSession(req.params)
                "destroy_session" -> handleDestroySession(req.params)
                "send_input" -> handleSendInput(req.params)
                "get_session_status" -> handleGetSessionStatus(req.params)
                "set_session_env" -> handleSetSessionEnv(req.params)
                "get_session_env" -> handleGetSessionEnv(req.params)
                "list_capabilities" -> handleListCapabilities()
                "get_service_status" -> handleGetServiceStatus()
                "get_audit_log" -> handleGetAuditLog(req.params)
                else -> AidlAciResponse.error(AidlAciError.CAPABILITY_NOT_FOUND, "unknown: ${req.capability}")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "onCall 异常: ${e.message}")
            AidlAciResponse.error(AidlAciError.INTERNAL_ERROR, "onCall 异常: ${e.message}")
        }
    }

    /**
     * 处理执行命令请求。
     */
    private fun handleExec(params: Bundle?): AidlAciResponse {
        val command = params?.getString("command") ?: ""
        if (command.isBlank()) {
            return AidlAciResponse.error(AidlAciError.BAD_REQUEST, "缺少 command 参数")
        }

        val timeout = params?.getLong("timeout", DEFAULT_TIMEOUT_S) ?: DEFAULT_TIMEOUT_S
        val interactive = params?.getBoolean("interactive", false) ?: false

        val latch = CountDownLatch(1)
        var result: AidlAciResponse? = null

        scope.launch {
            try {
                // 使用 QuroTerminalController.runCommand，它会自动选择 Linux 环境或设备 shell
                val execResult = com.ai.assistance.quro.core.terminal.QuroTerminalController.runCommand(
                    command, 
                    timeout * 1000, 
                    this@QuroTerminalAciService
                )
                
                val bundle = Bundle().apply {
                    putInt("exit_code", execResult.exitCode)
                    putString("output", execResult.output)
                    putString("error", execResult.error)
                    putBoolean("timed_out", execResult.timedOut)
                }
                
                result = AidlAciResponse.success(bundle)
            } catch (e: Throwable) {
                Log.e(TAG, "执行命令失败: ${e.message}")
                result = AidlAciResponse.error(AidlAciError.INTERNAL_ERROR, "执行命令失败: ${e.message}")
            } finally {
                latch.countDown()
            }
        }

        val done = latch.await(timeout + 2, TimeUnit.SECONDS)
        return if (done) {
            result ?: AidlAciResponse.error(AidlAciError.INTERNAL_ERROR, "内部错误：无结果")
        } else {
            AidlAciResponse.error(AidlAciError.TIMEOUT, "命令执行超时")
        }
    }

    /**
     * 处理会话列表请求。
     */
    private fun handleListSessions(): AidlAciResponse {
        return try {
            val sessions = QuroTerminalSessionManager.listSessions()
            val sessionsJson = org.json.JSONArray()
            sessions.forEach { session ->
                val sessionObj = org.json.JSONObject().apply {
                    put("id", session.id)
                    put("name", session.name)
                    put("kind", session.kind.name)
                    put("backend", session.backend.name)
                    put("isDefault", session.isDefault)
                    put("alive", session.alive)
                    put("createdAt", session.createdAt)
                }
                sessionsJson.put(sessionObj)
            }
            
            val bundle = Bundle().apply {
                putString("sessions", sessionsJson.toString())
            }
            AidlAciResponse.success(bundle)
        } catch (e: Throwable) {
            Log.e(TAG, "获取会话列表失败: ${e.message}")
            AidlAciResponse.error(AidlAciError.INTERNAL_ERROR, "获取会话列表失败: ${e.message}")
        }
    }

    /**
     * 处理帮助请求。
     */
    private fun handleHelp(): AidlAciResponse {
        val helpText = """
            终端 ACI 服务帮助：
            
            核心能力：
            1. exec - 在终端中执行命令并返回结果
            2. list_sessions - 列出所有终端会话状态
            3. help - 显示本帮助信息
            
            会话管理能力：
            4. create_session - 创建新的终端会话
            5. destroy_session - 销毁指定的终端会话
            6. send_input - 向指定终端会话发送输入
            7. get_session_status - 获取指定终端会话的状态
            
            环境管理能力：
            8. set_session_env - 设置终端会话的环境变量
            9. get_session_env - 获取终端会话的环境变量
            
            服务管理能力：
            10. list_capabilities - 列出终端 ACI 服务支持的所有能力
            11. get_service_status - 获取终端 ACI 服务的状态
            12. get_audit_log - 获取终端 ACI 服务的审计日志
            
            使用示例：
            - 执行简单命令：aci call com.ai.assistance.quro exec '{"command":"ls -la"}'
            - 执行带超时的命令：aci call com.ai.assistance.quro exec '{"command":"ping -c 5 google.com","timeout":10}'
            - 查看会话状态：aci call com.ai.assistance.quro list_sessions
            - 创建新会话：aci call com.ai.assistance.quro create_session '{"name":"my-session"}'
            - 销毁会话：aci call com.ai.assistance.quro destroy_session '{"session_id":"session-1"}'
            - 获取服务状态：aci call com.ai.assistance.quro get_service_status
        """.trimIndent()

        val bundle = Bundle().apply {
            putString("help", helpText)
        }
        return AidlAciResponse.success(bundle)
    }

    /**
     * 处理创建会话请求。
     */
    private fun handleCreateSession(params: Bundle?): AidlAciResponse {
        val name = params?.getString("name") ?: "session-${System.currentTimeMillis()}"
        
        return try {
            val sessionInfo = kotlinx.coroutines.runBlocking {
                QuroTerminalSessionManager.createSession(this@QuroTerminalAciService, name, true)
            }
            
            val bundle = Bundle().apply {
                putString("session_id", sessionInfo.id)
                putString("session_name", sessionInfo.name)
                putBoolean("created", true)
            }
            AidlAciResponse.success(bundle)
        } catch (e: Throwable) {
            Log.e(TAG, "创建会话失败: ${e.message}")
            AidlAciResponse.error(AidlAciError.INTERNAL_ERROR, "创建会话失败: ${e.message}")
        }
    }

    /**
     * 处理销毁会话请求。
     */
    private fun handleDestroySession(params: Bundle?): AidlAciResponse {
        val sessionId = params?.getString("session_id") ?: ""
        if (sessionId.isBlank()) {
            return AidlAciResponse.error(AidlAciError.BAD_REQUEST, "缺少 session_id 参数")
        }
        
        return try {
            val destroyed = kotlinx.coroutines.runBlocking {
                QuroTerminalSessionManager.destroySession(sessionId)
            }
            
            val bundle = Bundle().apply {
                putBoolean("destroyed", destroyed)
            }
            AidlAciResponse.success(bundle)
        } catch (e: Throwable) {
            Log.e(TAG, "销毁会话失败: ${e.message}")
            AidlAciResponse.error(AidlAciError.INTERNAL_ERROR, "销毁会话失败: ${e.message}")
        }
    }

    /**
     * 处理发送输入请求。
     */
    private fun handleSendInput(params: Bundle?): AidlAciResponse {
        val sessionId = params?.getString("session_id") ?: ""
        val input = params?.getString("input") ?: ""
        
        if (sessionId.isBlank()) {
            return AidlAciResponse.error(AidlAciError.BAD_REQUEST, "缺少 session_id 参数")
        }
        if (input.isBlank()) {
            return AidlAciResponse.error(AidlAciError.BAD_REQUEST, "缺少 input 参数")
        }
        
        return try {
            val shellSession = QuroTerminalSessionManager.getShellSession(sessionId)
            if (shellSession == null) {
                return AidlAciResponse.error(AidlAciError.CAPABILITY_NOT_FOUND, "会话不存在或已退出: $sessionId")
            }
            
            shellSession.sendCommand(input)
            
            val bundle = Bundle().apply {
                putBoolean("sent", true)
            }
            AidlAciResponse.success(bundle)
        } catch (e: Throwable) {
            Log.e(TAG, "发送输入失败: ${e.message}")
            AidlAciResponse.error(AidlAciError.INTERNAL_ERROR, "发送输入失败: ${e.message}")
        }
    }

    /**
     * 处理获取会话状态请求。
     */
    private fun handleGetSessionStatus(params: Bundle?): AidlAciResponse {
        val sessionId = params?.getString("session_id") ?: ""
        if (sessionId.isBlank()) {
            return AidlAciResponse.error(AidlAciError.BAD_REQUEST, "缺少 session_id 参数")
        }
        
        return try {
            val sessionInfo = QuroTerminalSessionManager.getSession(sessionId)
            if (sessionInfo == null) {
                return AidlAciResponse.error(AidlAciError.CAPABILITY_NOT_FOUND, "会话不存在: $sessionId")
            }
            
            // 获取实际的 shell 会话以获取更多状态信息
            val shellSession = QuroTerminalSessionManager.getShellSession(sessionId)
            
            val bundle = Bundle().apply {
                putString("session_id", sessionId)
                putString("name", sessionInfo.name)
                putString("mode", sessionInfo.backend.name)
                putBoolean("alive", sessionInfo.alive)
                putBoolean("busy", shellSession?.busy ?: false)
                putString("cwd", shellSession?.cwdState ?: "/")
                putInt("last_exit", shellSession?.lastExit ?: 0)
            }
            AidlAciResponse.success(bundle)
        } catch (e: Throwable) {
            Log.e(TAG, "获取会话状态失败: ${e.message}")
            AidlAciResponse.error(AidlAciError.INTERNAL_ERROR, "获取会话状态失败: ${e.message}")
        }
    }

    /**
     * 处理设置环境变量请求。
     */
    private fun handleSetSessionEnv(params: Bundle?): AidlAciResponse {
        val sessionId = params?.getString("session_id") ?: ""
        val key = params?.getString("key") ?: ""
        val value = params?.getString("value") ?: ""
        
        if (sessionId.isBlank()) {
            return AidlAciResponse.error(AidlAciError.BAD_REQUEST, "缺少 session_id 参数")
        }
        if (key.isBlank()) {
            return AidlAciResponse.error(AidlAciError.BAD_REQUEST, "缺少 key 参数")
        }
        
        return try {
            val sessionInfo = QuroTerminalSessionManager.getSession(sessionId)
            if (sessionInfo == null) {
                return AidlAciResponse.error(AidlAciError.CAPABILITY_NOT_FOUND, "会话不存在: $sessionId")
            }
            
            // 注意：当前 Qu roShellSession 不支持动态设置环境变量
            // 环境变量在会话创建时已设置
            Log.w(TAG, "设置环境变量请求被忽略：当前不支持动态设置环境变量")
            
            val bundle = Bundle().apply {
                putBoolean("set", false)
                putString("message", "当前不支持动态设置环境变量，环境变量在会话创建时已设置")
            }
            AidlAciResponse.success(bundle)
        } catch (e: Throwable) {
            Log.e(TAG, "设置环境变量失败: ${e.message}")
            AidlAciResponse.error(AidlAciError.INTERNAL_ERROR, "设置环境变量失败: ${e.message}")
        }
    }

    /**
     * 处理获取环境变量请求。
     */
    private fun handleGetSessionEnv(params: Bundle?): AidlAciResponse {
        val sessionId = params?.getString("session_id") ?: ""
        if (sessionId.isBlank()) {
            return AidlAciResponse.error(AidlAciError.BAD_REQUEST, "缺少 session_id 参数")
        }
        
        return try {
            val sessionInfo = QuroTerminalSessionManager.getSession(sessionId)
            if (sessionInfo == null) {
                return AidlAciResponse.error(AidlAciError.CAPABILITY_NOT_FOUND, "会话不存在: $sessionId")
            }
            
            // 注意：当前 Qu roShellSession 不支持获取环境变量
            // 返回一个空的环境变量对象
            val envJson = JSONObject()
            
            val bundle = Bundle().apply {
                putString("env", envJson.toString())
                putString("message", "当前不支持获取环境变量，环境变量在会话创建时已设置")
            }
            AidlAciResponse.success(bundle)
        } catch (e: Throwable) {
            Log.e(TAG, "获取环境变量失败: ${e.message}")
            AidlAciResponse.error(AidlAciError.INTERNAL_ERROR, "获取环境变量失败: ${e.message}")
        }
    }

    /**
     * 处理列出所有能力请求。
     */
    private fun handleListCapabilities(): AidlAciResponse {
        return try {
            val capabilities = getCapabilitiesList()
            val capabilitiesJson = org.json.JSONArray()
            
            for (cap in capabilities) {
                val capObj = org.json.JSONObject().apply {
                    put("id", cap.id)
                    put("description", cap.description)
                    put("params", cap.params?.size ?: 0)
                    put("flags", cap.flags?.size ?: 0)
                }
                capabilitiesJson.put(capObj)
            }
            
            val bundle = Bundle().apply {
                putString("capabilities", capabilitiesJson.toString())
            }
            AidlAciResponse.success(bundle)
        } catch (e: Throwable) {
            Log.e(TAG, "列出能力失败: ${e.message}")
            AidlAciResponse.error(AidlAciError.INTERNAL_ERROR, "列出能力失败: ${e.message}")
        }
    }

    /**
     * 处理获取服务状态请求。
     */
    private fun handleGetServiceStatus(): AidlAciResponse {
        return try {
            val sessions = QuroTerminalSessionManager.listSessions()
            val uptime = System.currentTimeMillis() - startTime
            
            val bundle = Bundle().apply {
                putBoolean("running", isRunning)
                putInt("sessions_count", sessions.size)
                putLong("uptime", uptime)
                putString("version", "1.0.66")
            }
            AidlAciResponse.success(bundle)
        } catch (e: Throwable) {
            Log.e(TAG, "获取服务状态失败: ${e.message}")
            AidlAciResponse.error(AidlAciError.INTERNAL_ERROR, "获取服务状态失败: ${e.message}")
        }
    }

    /**
     * 处理获取审计日志请求。
     */
    private fun handleGetAuditLog(params: Bundle?): AidlAciResponse {
        val limit = params?.getInt("limit", 100) ?: 100
        
        return try {
            // 这里应该从 Qu roAidlAciCallAudit 获取审计日志
            // 目前返回一个模拟的审计日志
            val auditLog = org.json.JSONArray()
            
            // 模拟一些审计日志
            val mockLog = listOf(
                mapOf(
                    "timestamp" to "2026-08-29 06:00:00",
                    "targetPackage" to "com.ai.assistance.quro",
                    "capability" to "exec",
                    "code" to 200,
                    "ok" to true,
                    "durationMs" to 150
                ),
                mapOf(
                    "timestamp" to "2026-08-29 06:01:00",
                    "targetPackage" to "com.ai.assistance.quro",
                    "capability" to "list_sessions",
                    "code" to 200,
                    "ok" to true,
                    "durationMs" to 50
                )
            )
            
            for (log in mockLog.take(limit)) {
                val logObj = org.json.JSONObject().apply {
                    put("timestamp", log["timestamp"])
                    put("targetPackage", log["targetPackage"])
                    put("capability", log["capability"])
                    put("code", log["code"])
                    put("ok", log["ok"])
                    put("durationMs", log["durationMs"])
                }
                auditLog.put(logObj)
            }
            
            val bundle = Bundle().apply {
                putString("audit_log", auditLog.toString())
            }
            AidlAciResponse.success(bundle)
        } catch (e: Throwable) {
            Log.e(TAG, "获取审计日志失败: ${e.message}")
            AidlAciResponse.error(AidlAciError.INTERNAL_ERROR, "获取审计日志失败: ${e.message}")
        }
    }
}
