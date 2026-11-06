package com.ai.assistance.quro.core.chat

import org.json.JSONObject

/**
 * 审批策略：判定「哪些工具调用需要用户审批」，并把它拆成可核对的事实项。
 *
 * ## 为什么用名字清单而不是每个工具 override [QuroTool.requiresApproval]
 * 工具 200 个，逐个改实现风险高、易漏。这里用**集中清单**：所有高副作用工具
 * 收在一处，一眼看清审批覆盖面，也方便以后调紧/调松。工具实现者仍可 override
 * [QuroTool.requiresApproval] 单独声明（两者取或）。
 *
 * ## 判定原则（宁可多问，不可漏问）
 * 凡是「执行即产生不可撤回副作用」的都要问：
 *  - 执行 shell / 系统命令（termux、terminal、root、adb、shizuku、priv_exec）
 *  - 文件/数据写入与删除（write/delete/move/copy/install/uninstall）
 *  - 手机控制与系统设置（截屏、录屏、输入、锁屏、发短信、打电话、开关蓝牙/WiFi/飞行模式）
 *  - 应用装卸、APK 安装、包管理
 *  - 支付 / 定时任务 / 短信 / 通讯录 / 日历写入
 *
 * 纯读（read/list/get/search/status/query/计算）一律不问。
 */
object ApprovalPolicy {

    /**
     * 需要审批的工具名前缀 / 精确名。
     *
     * 采用「前缀匹配 + 精确名兜底」两层：
     *  - [prefixes] 覆盖一类工具（terminal*、file_write、file_delete…）；
     *  - [exact] 覆盖不归类的单点（send_sms、install_app…）。
     */
    private val prefixes = listOf(
        // 执行类：shell / 命令
        "termux", "terminal_", "quroterm_", "root_", "shizuku_", "adb_", "priv_exec",
        "python_run", "run_code", "code_runner", "polyglot_run", "sandbox",
        "harness",
        // 文件写入/删除/移动/复制（🔴 2026-11-06 合体：write_file/workspace_write → file_write）
        "file_write", "delete_file", "move_file", "copy_file", "make_directory",
        // 应用/包管理
        "install_app", "uninstall", "apk_", "freeze_app", "export_apk",
        // 手机控制
        "tap_screen", "swipe_screen", "long_press_screen", "scroll_screen", "input_text",
        "ai_type_text", "ai_press_enter", "ai_press_send", "send_sms",
        "alarm_clock", "task_schedule", "write_calendar",
        "lock_screen", "global_action", "send_broadcast", "screen_record",
        "set_camera_disabled", "device_control", "clipboard", "vibrate",
        "take_photo", "execute_intent",
        // 终端执行
        "linux_run", "linux_install", "linux_pkg", "linux_start", "linux_stop",
        "linux_process",
        // 工作区写入
        "workspace_render", "workspace_media",
        // 网络副作用
        "send_", "post_",
    )

    private val exact = setOf(
        "terminal", "terminal_exec", "terminal_run", "terminal_write",
        "screenshot",
        "git", // git commit/push 有副作用，但 read 也有；由 args 再判
    )

    /**
     * 该调用是否需要审批。
     *
     * @param toolName  工具名
     * @param declared  工具实现是否显式声明 [QuroTool.requiresApproval]
     */
    fun needsApproval(toolName: String, declared: Boolean = false): Boolean {
        if (declared) return true
        if (toolName in exact) return true
        return prefixes.any { toolName.startsWith(it) }
    }

    /** 该调用是否需要审批（带参数二次判定，例如 git 只有 push/commit 才问）。 */
    fun needsApproval(toolName: String, arguments: String, declared: Boolean = false): Boolean {
        if (declared) return true
        // 🔴 合体工具（2026-11-06）：按 action 精细判定 —— 读动作不问，写动作问
        if (toolName == "clipboard" || toolName == "device_control" ||
            toolName == "alarm_clock" || toolName == "task_schedule"
        ) {
            val a = runCatching { JSONObject(arguments).optString("action").lowercase() }.getOrElse { "" }
            return when (toolName) {
                "clipboard" -> a == "set"
                "device_control" -> true   // 全部是真实系统设置变更
                else -> a != "list"        // alarm/task：set/create/cancel/delete 问，list 不问
            }
        }
        // git 只读操作（status/log/diff/remote -v）不问，写操作问
        if (toolName == "git") {
            val lower = arguments.lowercase()
            val writeOps = listOf("commit", "push", "merge", "checkout", "reset", "rebase", "tag", "branch -d", "rm", "add ")
            return writeOps.any { lower.contains(it) }
        }
        return needsApproval(toolName, declared = declared)
    }

    /**
     * 把一次调用拆成可核对的事实项（用于审批卡）。
     *
     * 这里只做**参数的可读化拆解**，不信任模型的 summary/reason——
     * 那些由模型自述，UI 会标注「以上是 AI 的自述」。
     */
    fun factsFor(toolName: String, arguments: String): List<ApprovalFact> {
        val facts = mutableListOf<ApprovalFact>()
        val json = runCatching { JSONObject(arguments) }.getOrNull()

        fun arg(vararg keys: String): String? {
            if (json == null) return null
            for (k in keys) {
                val v = json.opt(k)
                if (v != null && v.toString().isNotBlank() && v.toString() != "null") {
                    return v.toString().take(200)
                }
            }
            return null
        }

        when {
            toolName.contains("termux") || toolName.startsWith("terminal_") ||
                toolName.startsWith("quroterm_") || toolName.startsWith("root_") ||
                toolName.startsWith("shizuku_") || toolName.startsWith("adb_") ||
                toolName == "priv_exec" || toolName.startsWith("linux_run") -> {
                arg("command", "cmd", "shell", "code")?.let {
                    facts += ApprovalFact("命令", it, emphasis = true)
                }
            }
            toolName.startsWith("write_file") || toolName.startsWith("workspace_write") ||
                toolName.startsWith("file_write") -> {
                arg("path", "file_path", "filename", "name")?.let {
                    facts += ApprovalFact("目标文件", it, emphasis = true)
                }
                arg("content", "text", "data")?.let {
                    facts += ApprovalFact("写入内容", it.take(120))
                }
            }
            toolName.startsWith("delete_file") -> {
                arg("path", "file_path", "filename")?.let {
                    facts += ApprovalFact("删除文件", it, emphasis = true)
                }
            }
            toolName.startsWith("move_file") || toolName.startsWith("copy_file") -> {
                arg("from", "src", "source")?.let { facts += ApprovalFact("源", it) }
                arg("to", "dst", "dest", "destination")?.let {
                    facts += ApprovalFact("目标", it, emphasis = true)
                }
            }
            toolName.startsWith("install_app") || toolName.startsWith("apk_") ||
                toolName.startsWith("uninstall") || toolName == "export_apk" -> {
                arg("package", "pkg", "package_name", "apk_path", "path")?.let {
                    facts += ApprovalFact("应用/包", it, emphasis = true)
                }
            }
            toolName.startsWith("send_sms") || toolName.startsWith("send_") -> {
                arg("phone", "number", "to", "recipient")?.let {
                    facts += ApprovalFact("收件人", it, emphasis = true)
                }
                arg("message", "content", "text", "body")?.let {
                    facts += ApprovalFact("内容", it.take(120))
                }
            }
            toolName.startsWith("tap_screen") || toolName.startsWith("swipe_screen") ||
                toolName.startsWith("long_press_screen") || toolName.startsWith("scroll_screen") ||
                toolName.startsWith("input_text") || toolName.startsWith("ai_type_text") -> {
                arg("x")?.let { x -> arg("y")?.let { y -> facts += ApprovalFact("坐标", "($x, $y)") } }
                arg("text", "content")?.let { facts += ApprovalFact("输入内容", it.take(120), emphasis = true) }
            }
            toolName == "screenshot" || toolName == "screenshot_base64" ||
                toolName == "screen_record" -> {
                facts += ApprovalFact("操作", "截取/录制屏幕", emphasis = true)
            }
            toolName.startsWith("set_alarm") || toolName.startsWith("schedule_task") ||
                toolName == "alarm_clock" || toolName == "task_schedule" -> {
                arg("scheduledAt", "hour", "minutes", "time", "at", "cron", "schedule")?.let {
                    facts += ApprovalFact("时间", it)
                }
                arg("label", "title", "prompt", "task", "command")?.let {
                    facts += ApprovalFact("内容", it.take(120))
                }
            }
            toolName == "device_control" -> {
                arg("setting")?.let { facts += ApprovalFact("设置项", it, emphasis = true) }
                arg("action")?.let { facts += ApprovalFact("动作", it) }
                arg("level")?.let { facts += ApprovalFact("级别", it) }
            }
            toolName == "clipboard" -> {
                arg("text")?.let { facts += ApprovalFact("写入内容", it.take(120), emphasis = true) }
            }
            toolName.startsWith("write_calendar") -> {
                arg("title", "summary")?.let { facts += ApprovalFact("事件", it) }
                arg("start", "start_time", "startTime")?.let { facts += ApprovalFact("开始", it) }
            }
            toolName == "git" -> {
                arg("command", "cmd", "args")?.let {
                    facts += ApprovalFact("Git 命令", it, emphasis = true)
                }
            }
            else -> {
                // 兜底：展示原始参数（截断），让用户至少能看到要执行的东西。
                if (arguments.isNotBlank()) {
                    facts += ApprovalFact("参数", arguments.take(160), emphasis = true)
                }
            }
        }
        return facts
    }

    /** 工具名 -> 人类可读标签。 */
    fun labelFor(toolName: String): String = when {
        toolName.contains("termux") || toolName.startsWith("terminal_") ||
            toolName.startsWith("quroterm_") || toolName.startsWith("root_") ||
            toolName.startsWith("shizuku_") || toolName.startsWith("adb_") ||
            toolName == "priv_exec" -> "执行终端命令"
        toolName.startsWith("write_file") || toolName.startsWith("workspace_write") ||
            toolName.startsWith("file_write") -> "写入文件"
        toolName.startsWith("delete_file") -> "删除文件"
        toolName.startsWith("move_file") -> "移动文件"
        toolName.startsWith("copy_file") -> "复制文件"
        toolName.startsWith("install_app") || toolName.startsWith("apk_") -> "安装应用"
        toolName.startsWith("uninstall") -> "卸载应用"
        toolName.startsWith("send_sms") -> "发送短信"
        toolName.startsWith("tap_screen") || toolName.startsWith("swipe_screen") ||
            toolName.startsWith("long_press_screen") || toolName.startsWith("scroll_screen") -> "控制手机屏幕"
        toolName.startsWith("input_text") || toolName.startsWith("ai_type_text") -> "输入文字"
        toolName == "screenshot" || toolName == "screenshot_base64" -> "截屏"
        toolName == "screen_record" -> "录制屏幕"
        toolName.startsWith("set_alarm") || toolName.startsWith("schedule_task") ||
            toolName == "alarm_clock" || toolName == "task_schedule" -> "设置闹钟/定时任务"
        toolName == "device_control" -> "修改系统设置"
        toolName == "clipboard" -> "写剪贴板"
        toolName.startsWith("write_calendar") -> "写入日历"
        toolName == "git" -> "执行 Git 操作"
        toolName.startsWith("linux_run") -> "运行 Linux 命令"
        toolName.startsWith("python_run") || toolName == "run_code" -> "运行代码"
        else -> toolName
    }

    /**
     * 工具所属的**通道策略开关**（权限面板的 CMS / 特权两行三态开关）。
     *
     * 🔴 对接缺口修复（2026-11-04）：审批卡的「总是允许」此前只记进 [ApprovalMemory]
     * （而且没持久化），而 CMS broker / 特权管理器是**另一道关卡**，照样按
     * [com.ai.assistance.quro.core.policy.QuroPolicy] 的 ASK 弹确认 —— 用户刚点了
     * 「总是允许」，下一个工具调用又被另一个确认框拦住。现在拍板「总是允许」时：
     *   1. 工具名持久化（[ApprovalPersist]）；
     *   2. 该工具所属通道的开关**联动置为允许**（见 QuroChatViewModel.resolveApproval）。
     * 返回 null = 不属于任何通道开关（文件写入等），仅走 1。
     */
    fun channelFor(toolName: String): ApprovalChannel? = when {
        // 特权通道：终端 / root / Shizuku / ADB / priv_exec / harness / 代码执行
        toolName.contains("termux") || toolName.startsWith("terminal") ||
            toolName.startsWith("quroterm_") || toolName.startsWith("root_") ||
            toolName.startsWith("shizuku_") || toolName.startsWith("adb_") ||
            toolName == "priv_exec" || toolName.startsWith("linux_") ||
            toolName.startsWith("harness") || toolName.startsWith("python_run") ||
            toolName == "run_code" || toolName.startsWith("polyglot_run") ||
            toolName.startsWith("sandbox") || toolName == "git" -> ApprovalChannel.PRIV
        // CMS v2 能力模块：手机控制 / 系统设置 / 应用管理 / 短信日历 / 剪贴板…
        toolName.startsWith("tap_screen") || toolName.startsWith("swipe_screen") ||
            toolName.startsWith("long_press_screen") || toolName.startsWith("scroll_screen") ||
            toolName.startsWith("input_text") || toolName.startsWith("ai_type_text") ||
            toolName.startsWith("ai_press") || toolName.startsWith("global_action") ||
            toolName == "screenshot" || toolName == "screenshot_base64" ||
            toolName.startsWith("screen_record") || toolName.startsWith("send_sms") ||
            toolName.startsWith("set_alarm") || toolName.startsWith("schedule_task") ||
            toolName.startsWith("delete_scheduled_task") || toolName.startsWith("write_calendar") ||
            toolName.startsWith("install_app") || toolName.startsWith("uninstall") ||
            toolName.startsWith("apk_") || toolName.startsWith("freeze_app") ||
            toolName.startsWith("export_apk") || toolName.startsWith("set_clipboard") ||
                toolName == "device_control" || toolName == "clipboard" ||
                toolName == "alarm_clock" || toolName == "task_schedule" ||
            toolName.startsWith("volume_control") || toolName.startsWith("brightness_control") ||
            toolName.startsWith("bluetooth_control") || toolName.startsWith("wifi_control") ||
            toolName.startsWith("airplane_mode") || toolName.startsWith("toggle_flashlight") ||
            toolName.startsWith("notification_control") || toolName.startsWith("lock_screen") ||
            toolName.startsWith("take_photo") || toolName.startsWith("execute_intent") ||
            toolName.startsWith("send_") || toolName.startsWith("post_") -> ApprovalChannel.CMS
        else -> null
    }
}

/** 审批「总是允许」联动的通道开关（对应权限面板 CMS / 特权两行）。 */
enum class ApprovalChannel { CMS, PRIV }

