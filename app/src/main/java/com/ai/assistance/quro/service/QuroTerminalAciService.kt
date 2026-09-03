package com.ai.assistance.quro.service

import ai.aidl.aci.core.AidlAciError
import ai.aidl.aci.core.AidlAciRequest
import ai.aidl.aci.core.AidlAciResponse
import ai.aidl.aci.core.BaseAidlAciService
import ai.aidl.aci.core.Capability
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import com.ai.assistance.quro.core.terminal.QuroTerminalBridge
import com.ai.assistance.quro.core.terminal.QuroTerminalSessionManager
import com.ai.assistance.quro.core.terminal.ShellResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * 终端 ACI 受控端 Service（前台服务）。
 *
 * 严格遵循 ACI 开发者手册 §25：
 * - 继承 BaseAidlAciService，实现 onCreateCapabilities / onCall
 * - 14 个 ACI 能力：exec / create_session / destroy_session / send_input /
 *   switch_session / send_interrupt / get_session_status / list_sessions /
 *   set_session_env / get_session_env / list_capabilities / get_service_status /
 *   get_audit_log / help
 *   （switch_session / send_interrupt 补齐会话切换与命令中断能力，
 *   实现「接口对接口」用自己 ACI 架构覆盖全部终端操控能力）
 * - 前台服务保活（specialUse）
 * - 权限检查：允许自身 + ZorvAI 控制端
 * - Token 验证：应用层认证
 * - 审计日志：真实记录每次调用
 */
class QuroTerminalAciService : BaseAidlAciService() {

    companion object {
        private const val TAG = "QuroTerminalACI"
        private const val CHANNEL_ID = "quro_terminal_aci_channel"
        private const val NOTIF_ID = 9530
        const val ACTION_STOP = "com.ai.assistance.quro.action.TERMINAL_ACI_STOP"

        /** 默认命令执行超时（14秒） */
        private const val DEFAULT_TIMEOUT_S = 14L

        /** 交互式命令超时（30秒） */
        private const val INTERACTIVE_TIMEOUT_S = 30L

        /** ZorvAI 控制端包名 */
        private const val ZORVAI_PACKAGE = "com.ai.assistance.quro"

        /**
         * 确保终端 ACI 服务已启动。返回 true=成功发送启动 Intent，false=失败。
         */
        fun ensureStarted(context: Context, installIfMissing: Boolean = true): Boolean {
            return try {
                val intent = Intent(context, QuroTerminalAciService::class.java)
                intent.putExtra("install_if_missing", installIfMissing)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                Log.i(TAG, "ensureStarted: startForegroundService 成功")
                true
            } catch (e: Throwable) {
                Log.e(TAG, "拉起终端 ACI 服务失败: ${e.javaClass.simpleName}: ${e.message}")
                false
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

    /** 审计日志（线程安全） */
    private val auditLog = CopyOnWriteArrayList<AuditEntry>()
    private val auditCounter = AtomicLong(0)

    /** 版本号 */
    private val serviceVersion by lazy {
        try {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            pInfo.versionName ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }
    }

    override fun onCreate() {
        Log.d(TAG, "onCreate (pid=${android.os.Process.myPid()})")
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
     */
    private suspend fun ensureTerminalSession() {
        try {
            val session = QuroTerminalSessionManager.ensureDefault(this, installIfMissing = true)
            if (session != null) {
                Log.i(TAG, "终端会话已在 ACI 服务进程内创建（pid=${android.os.Process.myPid()}）")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "确保终端会话失败: ${e.message}")
        }
    }

    // ========== 前台通知 ==========

    private fun startForegroundWithNotification() {
        val notification = createNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIF_ID, notification)
            }
            Log.i(TAG, "前台服务启动成功，类型: SPECIAL_USE")
        } catch (e: Throwable) {
            Log.e(TAG, "SPECIAL_USE 启动失败，降级 DATA_SYNC: ${e.message}")
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(NOTIF_ID, notification,
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                }
                Log.i(TAG, "前台服务启动成功，类型: DATA_SYNC（降级方案）")
            } catch (e2: Throwable) {
                Log.e(TAG, "前台服务启动失败: ${e2.message}")
                throw e2
            }
        }
    }

    private fun createNotification(): android.app.Notification {
        val channel = android.app.NotificationChannel(
            CHANNEL_ID, "终端 ACI 服务",
            android.app.NotificationManager.IMPORTANCE_LOW
        ).apply { description = "终端会话 ACI 服务运行中" }

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        nm.createNotificationChannel(channel)

        val pendingIntent = android.app.PendingIntent.getActivity(
            this, 0,
            Intent(this, com.ai.assistance.quro.activity.QuroMainActivity::class.java),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )

        return android.app.Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Zorv AI 终端运行中")
            .setContentText("ACI 服务已就绪，可通过 AIDL / Provider / Broadcast / DeepLink 调用")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    // ========== ACI 能力注册（§25.2 + §4.4） ==========

    override fun onCreateCapabilities(caps: MutableList<Capability>) {
        // 1. exec — 执行命令
        caps.add(
            Capability.create("exec",
                "在终端中执行命令并返回结果。支持 proot Linux 环境和设备 shell。")
                .addParam("command", "string", true, "要执行的命令")
                .addParam("timeout", "int", false, "超时时间（秒），默认 14")
                .addParam("interactive", "boolean", false, "是否交互式，默认 false")
                .addResult("exit_code", "int", "退出码（0=成功）")
                .addResult("output", "string", "标准输出+错误输出")
                .addResult("error", "string", "启动失败时的错误信息")
                .addResult("timed_out", "boolean", "是否超时被终止")
                .addFlag(Capability.FLAG_BACKGROUND)
        )

        // 2. create_session — 创建会话
        caps.add(
            Capability.create("create_session", "创建新的终端会话。")
                .addParam("name", "string", false, "会话名称")
                .addParam("mode", "string", false, "模式：linux（默认）或 device")
                .addResult("session_id", "string", "新会话 ID")
                .addResult("session_name", "string", "会话名称")
                .addResult("created", "boolean", "是否创建成功")
                .addFlag(Capability.FLAG_BACKGROUND)
        )

        // 3. destroy_session — 销毁会话
        caps.add(
            Capability.create("destroy_session", "销毁指定的终端会话。")
                .addParam("session_id", "string", true, "目标会话 ID")
                .addResult("destroyed", "boolean", "是否销毁成功")
                .addFlag(Capability.FLAG_BACKGROUND)
        )

        // 4. send_input — 发送输入
        caps.add(
            Capability.create("send_input", "向指定终端会话发送原始输入（适用于交互式 shell）。")
                .addParam("session_id", "string", true, "目标会话 ID")
                .addParam("input", "string", true, "要发送的输入文本")
                .addResult("sent", "boolean", "是否发送成功")
                .addFlag(Capability.FLAG_BACKGROUND)
        )

        // 5. switch_session — 切换默认会话
        caps.add(
            Capability.create("switch_session", "切换默认终端会话，后续 AI/界面操作默认作用于该会话。")
                .addParam("session_id", "string", true, "要切换为默认的会话 ID")
                .addResult("switched", "boolean", "是否切换成功")
                .addResult("default_session", "string", "切换后的默认会话 ID")
                .addFlag(Capability.FLAG_BACKGROUND)
        )

        // 6. send_interrupt — 中断运行中的命令
        caps.add(
            Capability.create("send_interrupt", "向指定会话发送中断信号（^C），终止正在运行的命令。")
                .addParam("session_id", "string", true, "目标会话 ID")
                .addResult("interrupted", "boolean", "是否已发送中断")
                .addResult("message", "string", "中断结果说明")
                .addFlag(Capability.FLAG_BACKGROUND)
        )

        // 7. get_session_status — 获取会话状态
        caps.add(
            Capability.create("get_session_status", "获取指定终端会话的实时状态。")
                .addParam("session_id", "string", true, "目标会话 ID")
                .addResult("session_id", "string", "会话 ID")
                .addResult("name", "string", "会话名称")
                .addResult("mode", "string", "模式（linux/device）")
                .addResult("alive", "boolean", "是否存活")
                .addResult("busy", "boolean", "是否正在执行命令")
                .addResult("cwd", "string", "当前工作目录")
                .addResult("last_exit", "int", "上一条命令退出码")
                .addFlag(Capability.FLAG_NO_UI)
        )

        // 8. list_sessions — 列出所有会话
        caps.add(
            Capability.create("list_sessions", "列出所有终端会话，包括默认会话和额外会话。")
                .addResult("sessions", "string", "会话列表 JSON 数组")
                .addResult("count", "int", "会话总数")
                .addFlag(Capability.FLAG_NO_UI)
        )

        // 9. set_session_env — 设置环境变量（当前受限）
        caps.add(
            Capability.create("set_session_env",
                "设置终端会话的环境变量。注意：当前仅支持在会话创建时设置，运行时修改需新建会话。")
                .addParam("session_id", "string", true, "目标会话 ID")
                .addParam("key", "string", true, "环境变量名")
                .addParam("value", "string", true, "环境变量值")
                .addResult("set", "boolean", "是否设置成功")
                .addResult("message", "string", "操作说明")
                .addFlag(Capability.FLAG_BACKGROUND)
        )

        // 10. get_session_env — 获取环境变量
        caps.add(
            Capability.create("get_session_env", "获取终端会话的环境变量信息。")
                .addParam("session_id", "string", true, "目标会话 ID")
                .addResult("env", "string", "环境变量 JSON 对象")
                .addResult("message", "string", "说明")
                .addFlag(Capability.FLAG_NO_UI)
        )

        // 11. list_capabilities — 列出所有能力
        caps.add(
            Capability.create("list_capabilities", "列出本服务支持的所有 ACI 能力。")
                .addResult("capabilities", "string", "能力列表 JSON 数组")
                .addResult("count", "int", "能力总数")
                .addFlag(Capability.FLAG_NO_UI)
        )

        // 12. get_service_status — 获取服务状态
        caps.add(
            Capability.create("get_service_status", "获取终端 ACI 服务运行状态。")
                .addResult("running", "boolean", "服务是否运行")
                .addResult("pid", "int", "进程 ID")
                .addResult("sessions_count", "int", "会话数量")
                .addResult("uptime_ms", "long", "运行时长（毫秒）")
                .addResult("version", "string", "服务版本号")
                .addResult("android_version", "int", "Android API 级别")
                .addFlag(Capability.FLAG_NO_UI)
        )

        // 13. get_audit_log — 获取审计日志
        caps.add(
            Capability.create("get_audit_log", "获取终端 ACI 服务的调用审计日志。")
                .addParam("limit", "int", false, "返回条数，默认 50")
                .addResult("audit_log", "string", "审计日志 JSON 数组")
                .addResult("total", "long", "日志总数")
                .addFlag(Capability.FLAG_NO_UI)
        )

        // 14. help — 帮助信息
        caps.add(
            Capability.create("help", "显示终端 ACI 服务的完整帮助信息和使用示例。")
                .addResult("help", "string", "帮助文本")
                .addResult("version", "string", "服务版本")
                .addFlag(Capability.FLAG_NO_UI)
        )

        // ── 15-18. rootfs 文件能力（终端对接 ACI：文件管理经统一门面读写 rootfs）──

        // 15. file_read — 读取 rootfs 文件
        caps.add(
            Capability.create("file_read", "读取 rootfs 内文本文件内容（guest 路径，如 /root/foo.txt）。")
                .addParam("path", "string", true, "rootfs 内路径（/root、/etc、/sdcard 等）")
                .addParam("max_bytes", "int", false, "读取上限（字节），默认 262144")
                .addResult("output", "string", "文件内容")
                .addResult("exit_code", "int", "0=成功")
                .addResult("error", "string", "失败原因")
                .addFlag(Capability.FLAG_NO_UI)
        )

        // 16. file_write — 写入 rootfs 文件
        caps.add(
            Capability.create("file_write", "写入 rootfs 内文本文件（父目录不存在则自动创建）。")
                .addParam("path", "string", true, "rootfs 内路径")
                .addParam("content", "string", true, "要写入的文本内容")
                .addResult("exit_code", "int", "0=成功")
                .addResult("error", "string", "失败原因")
                .addFlag(Capability.FLAG_BACKGROUND)
        )

        // 17. file_delete — 删除 rootfs 文件/目录
        caps.add(
            Capability.create("file_delete", "删除 rootfs 内文件或目录（递归）。")
                .addParam("path", "string", true, "rootfs 内路径")
                .addResult("exit_code", "int", "0=成功")
                .addResult("error", "string", "失败原因")
                .addFlag(Capability.FLAG_BACKGROUND)
        )

        // 18. file_list — 列出 rootfs 目录
        caps.add(
            Capability.create("file_list", "列出 rootfs 内目录内容（目录名带 / 后缀）。")
                .addParam("path", "string", true, "rootfs 内路径")
                .addResult("output", "string", "文件名列表（换行分隔）")
                .addResult("exit_code", "int", "0=成功")
                .addResult("error", "string", "失败原因")
                .addFlag(Capability.FLAG_NO_UI)
        )

        // ── 19-26. 包管理能力（终端对接 ACI：包管理经统一门面 + 发行版探测）──

        // 19. pkg_install — 安装软件包
        caps.add(
            Capability.create("pkg_install", "在 Linux 环境安装软件包（自动探测发行版包管理器）。")
                .addParam("packages", "string", true, "软件包名（空格分隔多个）")
                .addParam("timeout", "int", false, "超时（秒），默认 300")
                .addResult("exit_code", "int", "退出码（0=成功）")
                .addResult("output", "string", "安装输出")
                .addResult("error", "string", "失败原因")
                .addResult("timed_out", "boolean", "是否超时")
                .addFlag(Capability.FLAG_BACKGROUND)
        )

        // 20. pkg_remove — 卸载软件包
        caps.add(
            Capability.create("pkg_remove", "卸载 Linux 环境中的软件包。")
                .addParam("packages", "string", true, "软件包名（空格分隔多个）")
                .addParam("timeout", "int", false, "超时（秒），默认 300")
                .addResult("exit_code", "int", "退出码（0=成功）")
                .addResult("output", "string", "卸载输出")
                .addResult("error", "string", "失败原因")
                .addFlag(Capability.FLAG_BACKGROUND)
        )

        // 21. pkg_update — 更新软件源索引
        caps.add(
            Capability.create("pkg_update", "更新 Linux 环境软件源索引（apt update / apk update 等）。")
                .addParam("timeout", "int", false, "超时（秒），默认 300")
                .addResult("exit_code", "int", "退出码（0=成功）")
                .addResult("output", "string", "更新输出")
                .addResult("error", "string", "失败原因")
                .addFlag(Capability.FLAG_BACKGROUND)
        )

        // 22. pkg_upgrade — 升级全部已装软件
        caps.add(
            Capability.create("pkg_upgrade", "升级 Linux 环境全部已装软件。")
                .addParam("timeout", "int", false, "超时（秒），默认 600")
                .addResult("exit_code", "int", "退出码（0=成功）")
                .addResult("output", "string", "升级输出")
                .addResult("error", "string", "失败原因")
                .addFlag(Capability.FLAG_BACKGROUND)
        )

        // 23. pkg_search — 搜索软件包
        caps.add(
            Capability.create("pkg_search", "搜索 Linux 环境可用软件包。")
                .addParam("query", "string", true, "搜索关键词")
                .addParam("timeout", "int", false, "超时（秒），默认 60")
                .addResult("exit_code", "int", "退出码（0=成功）")
                .addResult("output", "string", "搜索结果")
                .addResult("error", "string", "失败原因")
                .addFlag(Capability.FLAG_NO_UI)
        )

        // 24. pkg_list — 列出已装软件包
        caps.add(
            Capability.create("pkg_list", "列出 Linux 环境已安装的软件包。")
                .addParam("filter", "string", false, "过滤关键词（可选）")
                .addParam("timeout", "int", false, "超时（秒），默认 60")
                .addResult("exit_code", "int", "退出码（0=成功）")
                .addResult("output", "string", "已装软件包列表")
                .addResult("error", "string", "失败原因")
                .addFlag(Capability.FLAG_NO_UI)
        )

        // 25. pkg_info — 查看软件包详情
        caps.add(
            Capability.create("pkg_info", "查看 Linux 环境软件包详情。")
                .addParam("package", "string", true, "软件包名")
                .addParam("timeout", "int", false, "超时（秒），默认 60")
                .addResult("exit_code", "int", "退出码（0=成功）")
                .addResult("output", "string", "软件包详情")
                .addResult("error", "string", "失败原因")
                .addFlag(Capability.FLAG_NO_UI)
        )

        // 26. pkg_clean — 清理包缓存
        caps.add(
            Capability.create("pkg_clean", "清理 Linux 环境包缓存（释放空间）。")
                .addParam("timeout", "int", false, "超时（秒），默认 120")
                .addResult("exit_code", "int", "退出码（0=成功）")
                .addResult("output", "string", "清理输出")
                .addResult("error", "string", "失败原因")
                .addFlag(Capability.FLAG_BACKGROUND)
        )

        Log.i(TAG, "注册 ${caps.size} 个 ACI 能力")
    }

    // ========== 权限检查（§4.5） ==========

    override fun onCheckPermission(request: AidlAciRequest?, callerPkg: String?): Boolean {
        // 放行条件：
        // 1. 自身包名（内部调用）
        // 2. ZorvAI 主应用包名（控制端）
        val selfPkg = packageName
        val ok = callerPkg == selfPkg || callerPkg == ZORVAI_PACKAGE

        if (!ok) {
            Log.w(TAG, "权限拒绝: caller=$callerPkg (需要 $selfPkg 或 $ZORVAI_PACKAGE)")
        }
        return ok
    }

    // ========== Token 验证（§12） ==========

    override fun onVerifyToken(request: AidlAciRequest?): ai.aidl.aci.core.AciTokenVerifier.TokenResult {
        if (request == null) {
            return ai.aidl.aci.core.AciTokenVerifier.TokenResult.missing("request is null")
        }
        // 对自身调用跳过 Token 验证
        if (request.callerPkg == packageName) {
            return ai.aidl.aci.core.AciTokenVerifier.TokenResult.success("self-call")
        }
        // 使用默认验证逻辑
        return ai.aidl.aci.core.AciTokenVerifier.verify(this, request)
    }

    // ========== 调用分发 ==========

    override fun onCall(req: AidlAciRequest?): AidlAciResponse {
        if (req == null) return AidlAciResponse.error(AidlAciError.REQUEST_NULL, "request is null")

        val capName = req.capability ?: ""
        val startTime = System.currentTimeMillis()
        var response: AidlAciResponse

        try {
            response = when (capName) {
                "exec" -> handleExec(req.params)
                "create_session" -> handleCreateSession(req.params)
                "destroy_session" -> handleDestroySession(req.params)
                "send_input" -> handleSendInput(req.params)
                "switch_session" -> handleSwitchSession(req.params)
                "send_interrupt" -> handleSendInterrupt(req.params)
                "get_session_status" -> handleGetSessionStatus(req.params)
                "list_sessions" -> handleListSessions()
                "set_session_env" -> handleSetSessionEnv(req.params)
                "get_session_env" -> handleGetSessionEnv(req.params)
                "list_capabilities" -> handleListCapabilities()
                "get_service_status" -> handleGetServiceStatus()
                "get_audit_log" -> handleGetAuditLog(req.params)
                "help" -> handleHelp()
                "file_read" -> handleFileRead(req.params)
                "file_write" -> handleFileWrite(req.params)
                "file_delete" -> handleFileDelete(req.params)
                "file_list" -> handleFileList(req.params)
                "pkg_install" -> handlePkgInstall(req.params)
                "pkg_remove" -> handlePkgRemove(req.params)
                "pkg_update" -> handlePkgUpdate(req.params)
                "pkg_upgrade" -> handlePkgUpgrade(req.params)
                "pkg_search" -> handlePkgSearch(req.params)
                "pkg_list" -> handlePkgList(req.params)
                "pkg_info" -> handlePkgInfo(req.params)
                "pkg_clean" -> handlePkgClean(req.params)
                else -> AidlAciResponse.error(AidlAciError.CAPABILITY_NOT_FOUND,
                    "unknown capability: $capName")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "onCall 异常 [$capName]: ${e.message}")
            response = AidlAciResponse.error(AidlAciError.INTERNAL_ERROR,
                "onCall 异常: ${e.message}")
        }

        // 记录审计日志
        val durationMs = System.currentTimeMillis() - startTime
        recordAudit(req, response, durationMs)

        return response
    }

    // ========== 各能力实现 ==========

    private fun handleExec(params: Bundle?): AidlAciResponse {
        val command = params?.getString("command") ?: ""
        if (command.isBlank()) {
            return AidlAciResponse.error(AidlAciError.BAD_REQUEST, "缺少 command 参数")
        }

        val timeout = (params?.getLong("timeout", DEFAULT_TIMEOUT_S) ?: DEFAULT_TIMEOUT_S).coerceIn(1, 300)

        return try {
            val result = runBlocking(Dispatchers.IO) {
                QuroTerminalBridge.exec(command, timeout * 1000, this@QuroTerminalAciService)
            }
            AidlAciResponse.success(Bundle().apply {
                putInt("exit_code", result.exitCode)
                putString("output", result.output)
                putString("error", result.error ?: "")
                putBoolean("timed_out", result.timedOut)
            })
        } catch (e: Throwable) {
            Log.e(TAG, "exec 失败: ${e.message}")
            AidlAciResponse.error(AidlAciError.INTERNAL_ERROR, "exec 失败: ${e.message}")
        }
    }

    private fun handleCreateSession(params: Bundle?): AidlAciResponse {
        val name = params?.getString("name") ?: "session-${System.currentTimeMillis()}"
        return try {
            val session = runBlocking(Dispatchers.IO) {
                QuroTerminalSessionManager.createSession(this@QuroTerminalAciService, name)
            }
            AidlAciResponse.success(Bundle().apply {
                putString("session_id", session.id)
                putString("session_name", session.name)
                putBoolean("created", true)
            })
        } catch (e: Throwable) {
            AidlAciResponse.error(AidlAciError.INTERNAL_ERROR, "创建会话失败: ${e.message}")
        }
    }

    private fun handleDestroySession(params: Bundle?): AidlAciResponse {
        val sessionId = params?.getString("session_id") ?: ""
        if (sessionId.isBlank()) {
            return AidlAciResponse.error(AidlAciError.BAD_REQUEST, "缺少 session_id")
        }
        return try {
            val destroyed = runBlocking(Dispatchers.IO) {
                QuroTerminalSessionManager.destroySession(sessionId)
            }
            AidlAciResponse.success(Bundle().apply { putBoolean("destroyed", destroyed) })
        } catch (e: Throwable) {
            AidlAciResponse.error(AidlAciError.INTERNAL_ERROR, "销毁会话失败: ${e.message}")
        }
    }

    private fun handleSendInput(params: Bundle?): AidlAciResponse {
        val sessionId = params?.getString("session_id") ?: ""
        val input = params?.getString("input") ?: ""
        if (sessionId.isBlank()) return AidlAciResponse.error(AidlAciError.BAD_REQUEST, "缺少 session_id")
        if (input.isBlank()) return AidlAciResponse.error(AidlAciError.BAD_REQUEST, "缺少 input")

        val shell = QuroTerminalSessionManager.getShellSession(sessionId)
            ?: return AidlAciResponse.error(AidlAciError.CAPABILITY_NOT_FOUND,
                "会话不存在: $sessionId")
        return try {
            shell.sendCommand(input)
            AidlAciResponse.success(Bundle().apply { putBoolean("sent", true) })
        } catch (e: Throwable) {
            AidlAciResponse.error(AidlAciError.INTERNAL_ERROR, "发送输入失败: ${e.message}")
        }
    }

    private fun handleSwitchSession(params: Bundle?): AidlAciResponse {
        val sessionId = params?.getString("session_id") ?: ""
        if (sessionId.isBlank()) return AidlAciResponse.error(AidlAciError.BAD_REQUEST, "缺少 session_id")
        return try {
            val switched = runBlocking(Dispatchers.IO) {
                QuroTerminalSessionManager.switchDefault(sessionId)
            }
            val newDefault = QuroTerminalSessionManager.listSessions().find { it.isDefault }
            AidlAciResponse.success(Bundle().apply {
                putBoolean("switched", switched)
                putString("default_session", newDefault?.id ?: "")
            })
        } catch (e: Throwable) {
            AidlAciResponse.error(AidlAciError.INTERNAL_ERROR, "切换会话失败: ${e.message}")
        }
    }

    private fun handleSendInterrupt(params: Bundle?): AidlAciResponse {
        val sessionId = params?.getString("session_id") ?: ""
        if (sessionId.isBlank()) return AidlAciResponse.error(AidlAciError.BAD_REQUEST, "缺少 session_id")
        val shell = QuroTerminalSessionManager.getShellSession(sessionId)
            ?: return AidlAciResponse.error(AidlAciError.CAPABILITY_NOT_FOUND, "会话不存在: $sessionId")
        return try {
            val message = runBlocking(Dispatchers.IO) {
                when {
                    shell.exited -> "shell 已退出，无需中断"
                    !shell.busy -> "当前没有运行中的命令"
                    else -> {
                        val soft = shell.interrupt()
                        if (soft) {
                            "已中断当前命令"
                        } else {
                            val isDefault = QuroTerminalSessionManager.listSessions().any { it.isDefault && it.id == sessionId }
                            if (isDefault) {
                                com.ai.assistance.quro.core.terminal.QuroTerminalController.interrupt(this@QuroTerminalAciService)
                            } else {
                                shell.forceStop()
                                "命令未响应软中断，已强制终止"
                            }
                        }
                    }
                }
            }
            AidlAciResponse.success(Bundle().apply {
                putBoolean("interrupted", true)
                putString("message", message)
            })
        } catch (e: Throwable) {
            AidlAciResponse.error(AidlAciError.INTERNAL_ERROR, "发送中断失败: ${e.message}")
        }
    }

    private fun handleGetSessionStatus(params: Bundle?): AidlAciResponse {
        val sessionId = params?.getString("session_id") ?: ""
        if (sessionId.isBlank()) return AidlAciResponse.error(AidlAciError.BAD_REQUEST, "缺少 session_id")

        val info = QuroTerminalSessionManager.getSession(sessionId)
            ?: return AidlAciResponse.error(AidlAciError.CAPABILITY_NOT_FOUND,
                "会话不存在: $sessionId")
        val shell = QuroTerminalSessionManager.getShellSession(sessionId)

        return AidlAciResponse.success(Bundle().apply {
            putString("session_id", sessionId)
            putString("name", info.name)
            putString("mode", info.backend.name)
            putBoolean("alive", info.alive)
            putBoolean("busy", shell?.busy ?: false)
            putString("cwd", shell?.cwdState ?: "/")
            putInt("last_exit", shell?.lastExit ?: 0)
        })
    }

    private fun handleListSessions(): AidlAciResponse {
        return try {
            val sessions = QuroTerminalSessionManager.listSessions()
            val arr = JSONArray()
            sessions.forEach { s ->
                arr.put(JSONObject().apply {
                    put("id", s.id)
                    put("name", s.name)
                    put("kind", s.kind.name)
                    put("backend", s.backend.name)
                    put("isDefault", s.isDefault)
                    put("alive", s.alive)
                    put("createdAt", s.createdAt)
                })
            }
            AidlAciResponse.success(Bundle().apply {
                putString("sessions", arr.toString())
                putInt("count", sessions.size)
            })
        } catch (e: Throwable) {
            AidlAciResponse.error(AidlAciError.INTERNAL_ERROR, "获取会话列表失败: ${e.message}")
        }
    }

    private fun handleSetSessionEnv(params: Bundle?): AidlAciResponse {
        val sessionId = params?.getString("session_id") ?: ""
        val key = params?.getString("key") ?: ""
        if (sessionId.isBlank()) return AidlAciResponse.error(AidlAciError.BAD_REQUEST, "缺少 session_id")
        if (key.isBlank()) return AidlAciResponse.error(AidlAciError.BAD_REQUEST, "缺少 key")

        val info = QuroTerminalSessionManager.getSession(sessionId)
            ?: return AidlAciResponse.error(AidlAciError.CAPABILITY_NOT_FOUND,
                "会话不存在: $sessionId")

        // 当前实现：环境变量在会话创建时确定，运行时不支持动态修改
        Log.w(TAG, "set_session_env: 会话 $sessionId，key=$key（运行时暂不支持动态设置）")
        return AidlAciResponse.success(Bundle().apply {
            putBoolean("set", false)
            putString("message", "当前不支持运行时动态设置环境变量。环境变量在会话创建时已固定。如需不同环境变量，请 create_session 创建新会话。")
        })
    }

    private fun handleGetSessionEnv(params: Bundle?): AidlAciResponse {
        val sessionId = params?.getString("session_id") ?: ""
        if (sessionId.isBlank()) return AidlAciResponse.error(AidlAciError.BAD_REQUEST, "缺少 session_id")

        val info = QuroTerminalSessionManager.getSession(sessionId)
            ?: return AidlAciResponse.error(AidlAciError.CAPABILITY_NOT_FOUND,
                "会话不存在: $sessionId")

        // 返回当前会话可用的环境信息
        val envJson = JSONObject().apply {
            put("backend", info.backend.name)
            put("rootfs", "Ubuntu 24.04 ARM64 (proot)")
            put("shell", "/bin/sh")
            put("arch", "aarch64")
        }

        return AidlAciResponse.success(Bundle().apply {
            putString("env", envJson.toString())
            putString("message", "返回会话环境概要。完整环境变量在 shell 内通过 env 命令查看。")
        })
    }

    private fun handleListCapabilities(): AidlAciResponse {
        return try {
            val caps = getCapabilitiesList()
            val arr = JSONArray()
            caps.forEach { c ->
                arr.put(JSONObject().apply {
                    put("id", c.id)
                    put("description", c.description)
                    put("param_count", c.params?.size ?: 0)
                })
            }
            AidlAciResponse.success(Bundle().apply {
                putString("capabilities", arr.toString())
                putInt("count", caps.size)
            })
        } catch (e: Throwable) {
            AidlAciResponse.error(AidlAciError.INTERNAL_ERROR, "列出能力失败: ${e.message}")
        }
    }

    private fun handleGetServiceStatus(): AidlAciResponse {
        val sessions = QuroTerminalSessionManager.listSessions()
        val uptime = System.currentTimeMillis() - startTime

        return AidlAciResponse.success(Bundle().apply {
            putBoolean("running", isRunning)
            putInt("pid", android.os.Process.myPid())
            putInt("sessions_count", sessions.size)
            putLong("uptime_ms", uptime)
            putString("version", serviceVersion)
            putInt("android_version", Build.VERSION.SDK_INT)
        })
    }

    private fun handleGetAuditLog(params: Bundle?): AidlAciResponse {
        val limit = (params?.getInt("limit", 50) ?: 50).coerceIn(1, 500)
        val recent = auditLog.takeLast(limit)

        val arr = JSONArray()
        recent.forEach { entry ->
            arr.put(JSONObject().apply {
                put("seq", entry.seq)
                put("timestamp", entry.timestamp)
                put("caller", entry.callerPkg)
                put("capability", entry.capability)
                put("code", entry.responseCode)
                put("ok", entry.isOk)
                put("duration_ms", entry.durationMs)
                put("error", entry.error ?: "")
            })
        }

        return AidlAciResponse.success(Bundle().apply {
            putString("audit_log", arr.toString())
            putLong("total", auditCounter.get())
        })
    }

    private fun handleHelp(): AidlAciResponse {
        val help = """
Zorv AI 终端 ACI 服务 v$serviceVersion

支持的能力（26 个）：
  exec               — 执行命令
  create_session     — 创建会话
  destroy_session    — 销毁会话
  send_input         — 发送输入（交互式）
  switch_session     — 切换默认会话
  send_interrupt     — 发送中断信号（^C）
  get_session_status — 获取会话状态
  list_sessions      — 列出所有会话
  set_session_env    — 设置环境变量
  get_session_env    — 获取环境变量
  list_capabilities  — 列出所有能力
  get_service_status — 获取服务状态
  get_audit_log      — 获取审计日志
  help               — 本帮助
  ── 文件能力 ──
  file_read          — 读取 rootfs 文件
  file_write         — 写入 rootfs 文件
  file_delete        — 删除 rootfs 文件/目录
  file_list          — 列出 rootfs 目录
  ── 包管理能力 ──
  pkg_install        — 安装软件包
  pkg_remove         — 卸载软件包
  pkg_update         — 更新软件源索引
  pkg_upgrade        — 升级全部软件
  pkg_search         — 搜索软件包
  pkg_list           — 列出已装软件包
  pkg_info           — 查看软件包详情
  pkg_clean          — 清理包缓存

接入方式：
  AIDL Binder    — bindService + ACTION_BIND
  ContentProvider — content://com.ai.assistance.quro.terminal/...
  Deep Link      — quro://terminal/exec?cmd=...
  Broadcast      — com.ai.assistance.quro.action.TERMINAL_EXEC
  Intent Activity — TerminalIntentActivity（透明）

示例：
  aci call com.ai.assistance.quro exec '{"command":"ls -la"}'
  aci call com.ai.assistance.quro list_sessions
  aci call com.ai.assistance.quro get_service_status
  aci call com.ai.assistance.quro file_read '{"path":"/root/foo.txt"}'
  aci call com.ai.assistance.quro pkg_install '{"packages":"htop vim"}'
""".trimIndent()

        return AidlAciResponse.success(Bundle().apply {
            putString("help", help)
            putString("version", serviceVersion)
        })
    }

    // ========== 文件 / 包管理能力实现（终端对接 ACI · 统一门面） ==========

    /** 把 [ShellResult] 转成 ACI 结果 Bundle，避免各 handler 重复拼装。 */
    private fun shellBundle(r: ShellResult): Bundle = Bundle().apply {
        putInt("exit_code", r.exitCode)
        putString("output", r.output)
        putString("error", r.error)
        putBoolean("timed_out", r.timedOut)
    }

    private fun handleFileRead(params: Bundle?): AidlAciResponse {
        val path = params?.getString("path") ?: ""
        if (path.isBlank()) return AidlAciResponse.error(AidlAciError.BAD_REQUEST, "缺少 path 参数")
        val maxBytes = (params?.getInt("max_bytes", 262144) ?: 262144).coerceIn(1, 8 * 1024 * 1024)
        return try {
            val r = runBlocking(Dispatchers.IO) {
                QuroTerminalBridge.readFile(this@QuroTerminalAciService, path, maxBytes)
            }
            AidlAciResponse.success(shellBundle(r))
        } catch (e: Throwable) {
            AidlAciResponse.error(AidlAciError.INTERNAL_ERROR, "file_read 失败: ${e.message}")
        }
    }

    private fun handleFileWrite(params: Bundle?): AidlAciResponse {
        val path = params?.getString("path") ?: ""
        val content = params?.getString("content") ?: ""
        if (path.isBlank()) return AidlAciResponse.error(AidlAciError.BAD_REQUEST, "缺少 path 参数")
        return try {
            val r = runBlocking(Dispatchers.IO) {
                QuroTerminalBridge.writeFile(this@QuroTerminalAciService, path, content)
            }
            AidlAciResponse.success(shellBundle(r))
        } catch (e: Throwable) {
            AidlAciResponse.error(AidlAciError.INTERNAL_ERROR, "file_write 失败: ${e.message}")
        }
    }

    private fun handleFileDelete(params: Bundle?): AidlAciResponse {
        val path = params?.getString("path") ?: ""
        if (path.isBlank()) return AidlAciResponse.error(AidlAciError.BAD_REQUEST, "缺少 path 参数")
        return try {
            val r = runBlocking(Dispatchers.IO) {
                QuroTerminalBridge.deleteFile(this@QuroTerminalAciService, path)
            }
            AidlAciResponse.success(shellBundle(r))
        } catch (e: Throwable) {
            AidlAciResponse.error(AidlAciError.INTERNAL_ERROR, "file_delete 失败: ${e.message}")
        }
    }

    private fun handleFileList(params: Bundle?): AidlAciResponse {
        val path = params?.getString("path") ?: ""
        if (path.isBlank()) return AidlAciResponse.error(AidlAciError.BAD_REQUEST, "缺少 path 参数")
        return try {
            val r = runBlocking(Dispatchers.IO) {
                QuroTerminalBridge.listDir(this@QuroTerminalAciService, path)
            }
            AidlAciResponse.success(shellBundle(r))
        } catch (e: Throwable) {
            AidlAciResponse.error(AidlAciError.INTERNAL_ERROR, "file_list 失败: ${e.message}")
        }
    }

    private fun handlePkgInstall(params: Bundle?): AidlAciResponse {
        val packages = params?.getString("packages") ?: ""
        if (packages.isBlank()) return AidlAciResponse.error(AidlAciError.BAD_REQUEST, "缺少 packages 参数")
        val timeout = (params?.getLong("timeout", 300L) ?: 300L).coerceIn(1, 1800)
        val pkgs = packages.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        return try {
            val r = runBlocking(Dispatchers.IO) {
                QuroTerminalBridge.pkgInstall(this@QuroTerminalAciService, pkgs, timeout * 1000)
            }
            AidlAciResponse.success(shellBundle(r))
        } catch (e: Throwable) {
            AidlAciResponse.error(AidlAciError.INTERNAL_ERROR, "pkg_install 失败: ${e.message}")
        }
    }

    private fun handlePkgRemove(params: Bundle?): AidlAciResponse {
        val packages = params?.getString("packages") ?: ""
        if (packages.isBlank()) return AidlAciResponse.error(AidlAciError.BAD_REQUEST, "缺少 packages 参数")
        val timeout = (params?.getLong("timeout", 300L) ?: 300L).coerceIn(1, 1800)
        val pkgs = packages.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        return try {
            val r = runBlocking(Dispatchers.IO) {
                QuroTerminalBridge.pkgRemove(this@QuroTerminalAciService, pkgs, timeout * 1000)
            }
            AidlAciResponse.success(shellBundle(r))
        } catch (e: Throwable) {
            AidlAciResponse.error(AidlAciError.INTERNAL_ERROR, "pkg_remove 失败: ${e.message}")
        }
    }

    private fun handlePkgUpdate(params: Bundle?): AidlAciResponse {
        val timeout = (params?.getLong("timeout", 300L) ?: 300L).coerceIn(1, 1800)
        return try {
            val r = runBlocking(Dispatchers.IO) {
                QuroTerminalBridge.pkgUpdate(this@QuroTerminalAciService, timeout * 1000)
            }
            AidlAciResponse.success(shellBundle(r))
        } catch (e: Throwable) {
            AidlAciResponse.error(AidlAciError.INTERNAL_ERROR, "pkg_update 失败: ${e.message}")
        }
    }

    private fun handlePkgUpgrade(params: Bundle?): AidlAciResponse {
        val timeout = (params?.getLong("timeout", 600L) ?: 600L).coerceIn(1, 3600)
        return try {
            val r = runBlocking(Dispatchers.IO) {
                QuroTerminalBridge.pkgUpgrade(this@QuroTerminalAciService, timeout * 1000)
            }
            AidlAciResponse.success(shellBundle(r))
        } catch (e: Throwable) {
            AidlAciResponse.error(AidlAciError.INTERNAL_ERROR, "pkg_upgrade 失败: ${e.message}")
        }
    }

    private fun handlePkgSearch(params: Bundle?): AidlAciResponse {
        val query = params?.getString("query") ?: ""
        if (query.isBlank()) return AidlAciResponse.error(AidlAciError.BAD_REQUEST, "缺少 query 参数")
        val timeout = (params?.getLong("timeout", 60L) ?: 60L).coerceIn(1, 600)
        return try {
            val r = runBlocking(Dispatchers.IO) {
                QuroTerminalBridge.pkgSearch(this@QuroTerminalAciService, query.trim(), timeout * 1000)
            }
            AidlAciResponse.success(shellBundle(r))
        } catch (e: Throwable) {
            AidlAciResponse.error(AidlAciError.INTERNAL_ERROR, "pkg_search 失败: ${e.message}")
        }
    }

    private fun handlePkgList(params: Bundle?): AidlAciResponse {
        val filter = params?.getString("filter")?.takeIf { it.isNotBlank() }
        val timeout = (params?.getLong("timeout", 60L) ?: 60L).coerceIn(1, 600)
        return try {
            val r = runBlocking(Dispatchers.IO) {
                QuroTerminalBridge.pkgList(this@QuroTerminalAciService, filter, timeout * 1000)
            }
            AidlAciResponse.success(shellBundle(r))
        } catch (e: Throwable) {
            AidlAciResponse.error(AidlAciError.INTERNAL_ERROR, "pkg_list 失败: ${e.message}")
        }
    }

    private fun handlePkgInfo(params: Bundle?): AidlAciResponse {
        val pkg = params?.getString("package") ?: ""
        if (pkg.isBlank()) return AidlAciResponse.error(AidlAciError.BAD_REQUEST, "缺少 package 参数")
        val timeout = (params?.getLong("timeout", 60L) ?: 60L).coerceIn(1, 600)
        return try {
            val r = runBlocking(Dispatchers.IO) {
                QuroTerminalBridge.pkgInfo(this@QuroTerminalAciService, pkg.trim(), timeout * 1000)
            }
            AidlAciResponse.success(shellBundle(r))
        } catch (e: Throwable) {
            AidlAciResponse.error(AidlAciError.INTERNAL_ERROR, "pkg_info 失败: ${e.message}")
        }
    }

    private fun handlePkgClean(params: Bundle?): AidlAciResponse {
        val timeout = (params?.getLong("timeout", 120L) ?: 120L).coerceIn(1, 1800)
        return try {
            val r = runBlocking(Dispatchers.IO) {
                QuroTerminalBridge.pkgClean(this@QuroTerminalAciService, timeout * 1000)
            }
            AidlAciResponse.success(shellBundle(r))
        } catch (e: Throwable) {
            AidlAciResponse.error(AidlAciError.INTERNAL_ERROR, "pkg_clean 失败: ${e.message}")
        }
    }

    // ========== 审计日志 ==========

    private fun recordAudit(req: AidlAciRequest, resp: AidlAciResponse, durationMs: Long) {
        val seq = auditCounter.incrementAndGet()
        val isOk = resp.isSuccess
        val entry = AuditEntry(
            seq = seq,
            timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date()),
            callerPkg = req.callerPkg ?: "unknown",
            capability = req.capability ?: "unknown",
            responseCode = resp.errorCode,
            isOk = isOk,
            durationMs = durationMs,
            error = if (isOk) null else resp.errorMessage
        )
        auditLog.add(entry)

        // 保持最近 1000 条
        while (auditLog.size > 1000) {
            auditLog.removeAt(0)
        }

        Log.d(TAG, "[#$seq] ${req.callerPkg} → ${req.capability} " +
            "code=${resp.errorCode} ok=$isOk ${durationMs}ms" +
            if (isOk) "" else " err=${resp.errorMessage}")
    }

    /** 审计日志条目 */
    private data class AuditEntry(
        val seq: Long,
        val timestamp: String,
        val callerPkg: String,
        val capability: String,
        val responseCode: Int,
        val isOk: Boolean,
        val durationMs: Long,
        val error: String?
    )
}
