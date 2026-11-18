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
        // 🔴 2026-11-12：set_alarm 此前只出现在 factsFor/labelFor，从未进审批清单 ——
        // 设闹钟/定时任务是真实副作用（HEAD 起就漏，ApprovalPolicyTest 直接红）。
        "set_alarm", "alarm_clock", "task_schedule", "write_calendar",
        "lock_screen", "global_action", "send_broadcast", "screen_record",
        "set_camera_disabled", "device_control", "clipboard", "vibrate",
        "take_photo", "execute_intent",
        // 终端执行
        "linux_run", "linux_install", "linux_pkg", "linux_start", "linux_stop",
        "linux_process",
        // 工作区写入/预览（合体后由 workspace 的 action 展开到这些真实目标）
        "workspace_render", "workspace_media", "workspace_doc",
        // 网络副作用
        "send_", "post_",
    )

    private val exact = setOf(
        "terminal", "terminal_exec", "terminal_run", "terminal_write",
        // 2026-11-11 第二批 8 族合体：无参数入口（集群等）拿不到 action，一律按高风险兜底；
        // 带参数入口走下面的合体分支，按 action 精细放行只读动作。
        "shizuku", "keyboard", "aci", "root", "screen", "permissions", "memory",
        "screenshot",
        "git", // git commit/push 有副作用，但 read 也有；由 args 再判
        // 🔴 2026-11-12：第一批合体退役的**写类旧名**补进审批（legacy 名按原名执行，
        // targetName 就是旧名——漏列等于历史会话/工作流按旧名写文件直接绕过审批）。
        // 只补「有真实副作用」的：读类旧名（read_text_file/list_files/...）本就免审，不补。
        "write_file", "workspace_write",
        // 🔴 2026-11-12：合体后 ACI / MCP 的真实执行名（主链路按这些名字判定，
        // 见 needsApproval 带参数版的首段说明）。
        "aci_call", "aci_http_server",
        "mcp_call", "mcp_deploy", "mcp_undeploy", "mcp_aci_call", "mcp_aci_bridge",
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
        // 🔴 2026-11-12：合体工具的**真实子调用名**补审。
        // 主链路（QuroTool.kt）是按 governedCalls 展开出的旧实现名判审批的 ——
        // 也就是说 "aci"/"mcp" 这两个外层名的 action 分支在主链路上永远走不到，
        // 真正被判定的是 aci_call / aci_http_server / mcp_call 这些名字。
        // 它们既不在 exact 也不匹配任何前缀 → 从 HEAD 起就是「调用第三方 App 能力/
        // 外部 MCP 工具完全不弹审批」的洞。这里按子调用名补回来：
        // 读类（aci_list / server status）免问，执行类必问。
        if (toolName == "aci_list") return false
        if (toolName == "aci_call") return true
        if (toolName == "aci_http_server") {
            val a = runCatching { JSONObject(arguments).optString("action").lowercase() }.getOrElse { "" }
            return a != "status"
        }
        if (toolName in setOf(
                "mcp_call", "mcp_deploy", "mcp_undeploy", "mcp_aci_call", "mcp_aci_bridge"
            )
        ) return true
        // 合体工具按 action 精细判定：查询类免问，执行/部署/写入类必问。
        // 主链路还会按真实子调用名再判一次；这里是集群等只拿得到外层名的入口的兜底。
        if (toolName == "web" || toolName == "mcp" || toolName == "linux_env" ||
            toolName == "wf" || toolName == "cms" || toolName == "auth_service" ||
            toolName == "workspace" || toolName == "shizuku" || toolName == "keyboard" ||
            toolName == "aci" || toolName == "root" || toolName == "screen" ||
            toolName == "permissions" || toolName == "memory"
        ) {
            val a = runCatching { JSONObject(arguments).optString("action").lowercase() }.getOrElse { "" }
            return when (toolName) {
                "web" -> false
                "linux_env" -> a in setOf("run", "install", "pkg", "stop", "process", "start")
                "mcp" -> a in setOf("call", "deploy", "undeploy", "aci_call", "aci_bridge")
                "cms" -> a !in setOf("list", "status", "logs", "result", "engine_status", "priv_status")
                "wf" -> a != "status"
                "auth_service" -> a != "list"
                "workspace" -> a in setOf("write", "doc", "write_and_render", "media")
                // Shizuku / ROOT：只有查状态免问，执行与包操作必问
                "shizuku" -> a !in setOf("status", "grant")
                "root" -> a != "status"
                // 权限通道：check / status / run_status 是自查，其余都是真实系统变更
                "permissions" -> a !in setOf("check", "status", "run_status")
                // 屏幕：纯读控件树/前台/状态免问；截图、拍照、录屏、授权、点按都问
                "screen" -> a !in setOf("read", "foreground", "state")
                // 键盘：打字与发送都是真实副作用，全部问
                "keyboard" -> true
                // ACI：列能力与查服务状态免问；调用与服务端点变更必问
                "aci" -> a !in setOf("list", "server_status")
                // 记忆：查询类免问，写入/删除/纠正/索引重建必问
                "memory" -> a in setOf(
                    "save", "delete", "log", "correct", "kb_add", "kb_delete", "rag_index"
                )
                else -> true
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
            // 8 族合体：按 action 把真实参数摊开给用户看（命令 / 坐标 / 文本 / 能力）
            toolName in setOf("shizuku", "root", "permissions", "terminal") -> {
                arg("command", "cmd", "shell")?.let { facts += ApprovalFact("命令", it, emphasis = true) }
                arg("apk_path", "package_name")?.let { facts += ApprovalFact("目标", it, emphasis = true) }
                arg("setting", "op")?.let { facts += ApprovalFact("设置项", it) }
            }
            toolName == "screen" -> {
                arg("text", "description", "target_text")?.let { facts += ApprovalFact("目标/输入", it, emphasis = true) }
                val xy = listOfNotNull(json?.opt("x"), json?.opt("y")).joinToString(", ")
                if (xy.isNotBlank()) facts += ApprovalFact("坐标", xy)
                arg("op", "direction", "action")?.let { facts += ApprovalFact("动作", it) }
            }
            toolName == "keyboard" -> {
                arg("text")?.let { facts += ApprovalFact("输入内容", it.take(200), emphasis = true) }
            }
            toolName == "aci" || toolName == "aci_call" || toolName == "mcp_call" -> {
                arg("capability", "tool")?.let { facts += ApprovalFact("能力", it, emphasis = true) }
                arg("target_package", "server", "package")?.let { facts += ApprovalFact("目标应用", it) }
            }
            toolName == "memory" -> {
                arg("content", "query", "sql", "path", "title")?.let {
                    facts += ApprovalFact("内容", it.take(200), emphasis = true)
                }
            }
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
        toolName == "shizuku" -> "Shizuku 特权操作"
        toolName == "root" -> "ROOT 命令执行"
        toolName == "permissions" -> "权限与系统设置"
        toolName == "screen" -> "屏幕感知与操控"
        toolName == "keyboard" -> "键盘输入与发送"
        toolName == "aci" -> "调用第三方 App 能力"
        toolName == "aci_call" -> "调用第三方 App 能力"
        toolName == "aci_http_server" -> "ACI 本地端点变更"
        toolName in setOf(
            "mcp_call", "mcp_deploy", "mcp_undeploy", "mcp_aci_call", "mcp_aci_bridge"
        ) -> "调用外部 MCP 工具"
        toolName == "memory" -> "记忆与知识库写入"
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
                toolName == "cms" ||
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

