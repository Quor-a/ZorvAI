package com.ai.assistance.quro.core.tools

import android.content.Context
import com.ai.assistance.quro.core.skill.QuroSkillStore
import org.json.JSONObject
import com.ai.assistance.quro.core.QuroToolCall
import com.ai.assistance.quro.core.QuroToolResult
import com.ai.assistance.quro.core.QuroToolSpec
import com.ai.assistance.quro.core.mcp.DroidMcp
import com.ai.assistance.quro.core.mcp.McpTool

/**
 * 工具接口：名称 + 描述 + 参数 JSON-Schema + 执行。
 */
interface QuroTool {
    val name: String
    val description: String
    val parametersJson: String
    fun run(context: Context, arguments: String): String

    /** 该工具运行前需确保已授予的危险权限（运行时申请）。默认无。 */
    val requiredPermissions: List<String> get() = emptyList()
}

/** 工具注册表（持有全部原创工具）。 */
class QuroToolRegistry {
    companion object {
        /** 当前进程主注册表实例（由 QuroChatViewModel 在构建后写入，供技能管理等 UI 立即注销已注册工具）。 */
        @Volatile var active: QuroToolRegistry? = null
    }

    private val map = LinkedHashMap<String, QuroTool>()

    /** 技能可调用（function calling）总开关：false=技能仅注入系统提示词，不下发为工具函数。 */
    var skillToolsEnabled: Boolean = true
    /** 最多下发的技能工具数量（避免工具集过大被 API 中转静默丢弃）。 */
    var maxSkillTools: Int = 16

    fun register(tool: QuroTool) {
        map[tool.name] = tool
    }

    /**
     * 删除已注册工具（含技能工具 skill__<name>、导入工具等）。返回是否确有移除。
     * 反向级联：删除「技能工具」（skill__<name>）时，同步删除对应的用户技能定义，
     * 否则下次 mergeSkills 会把该技能重新注册为工具「复活」，造成「工具删了技能还在」的悬挂（#913）。
     */
    fun remove(name: String): Boolean {
        val removed = map.remove(name) != null
        if (removed && name.startsWith("skill__")) {
            val skillName = name.removePrefix("skill__")
            appContext?.let { ctx ->
                QuroSkillStore.load(ctx).firstOrNull { it.name == skillName }
                    ?.let { QuroSkillStore.remove(ctx, it.id) }
            }
        }
        return removed
    }

    fun get(name: String): QuroTool? = map[name]
    fun all(): List<QuroTool> = map.values.toList()
    fun specs(): List<QuroToolSpec> = map.values.map {
        QuroToolSpec(it.name, it.description, it.parametersJson)
    }

    /** 绑定 Application Context 并把已持久化的「导入工具」并入运行时注册表（执行 + 下发明细都生效）。 */
    fun attach(context: Context) {
        appContext = context
        mergeImported(context)
    }

    /** 将导入工具（AI 自写 / 用户粘贴 JSON 导入）注册进本注册表，使其可被 AI 调用。 */
    fun mergeImported(context: Context) {
        QuroImportedToolRegistry.all().forEach { register(QuroImportedTool(it)) }
    }

    private var appContext: Context? = null

    /**
     * 核心工具规格（标准集 ~23 个）。
     *
     * 为什么需要分档：47 个完整工具的 definitions 占 ~4,350 tokens，
     * 大量 API 中转/代理对请求总大小或工具有数限制（常见上限 20-30，
     * 或总 token 上限 6-8K），超限后可能**静默丢弃整个 tools 字段**，
     * 导致模型完全无法调用任何工具（表现为「纯问答、不执行动作」）。
     *
     * 标准集覆盖 95%+ 的日常口语指令（打开应用、设备状态、网络、剪贴板、
     * 短信/联系人、手电筒、闹钟、运行代码、屏幕控制、TTS 等），
     * 将 token 开销降到 ~2,100 以内，确保绝大多数 API 中转能正常透传。
     *
     * 若你的 API 代理支持完整工具（如直连 OpenAI / DeepSeek / SiliconFlow），
     * 可在 [com.ai.assistance.quro.core.QuroAssistant.ask] 中将
     * `registry.coreSpecs()` 改为 `registry.fullSpecs()` 解锁全部 ~47 个工具。
     */
    fun coreSpecs(): List<QuroToolSpec> {
        // 精简核心集：仅保留口语指令最高频、且依赖链最短的动作。
        // 目的——把工具 definitions 的 token 开销压到 ~1,200 以内，
        // 确保绝大多数 API 中转（含对「工具数 / 总 token」有上限的代理）能正常透传，
        // 不再静默丢弃整个 tools 字段（那是此前「纯问答、不执行动作」的根因）。
        // 若你的代理确认支持完整工具（直连 OpenAI / DeepSeek / SiliconFlow 等），
        // 可在 QuroAssistant.ask 中将 registry.coreSpecs() 改为 registry.fullSpecs() 解锁全部 ~47 个。
        // 扩展核心集：覆盖 95%+ 日常口语指令，让模型「知道有什么工具、该用哪个」。
        // 默认（useFullTools=false）即下发此集；fullSpecs 在其基础上再并入其余高级/小众工具。
        // 注意：菜单（appendCapabilityAwareness）与 tools 字段都由此集生成，二者严格一致，
        // 避免模型选了菜单里有、字段里没有的工具而报「未知工具」。
        val coreNames = setOf(
            // 基础
            "get_current_time", "get_device_info", "calculate",
            "get_battery", "get_wifi_info", "get_network_info", "get_sensors", "vibrate",
            "get_clipboard", "set_clipboard",
            // 应用管理
            "list_installed_apps", "launch_app", "search_and_launch_app", "get_package_name",
            "list_app_functions", "invoke_app_function",
            // 通知 / 蓝牙 / 手电（注意：真实注册名是 get_active_notifications，coreNames 必须与之一致，否则工具会被静默丢弃）
            "get_active_notifications", "get_bluetooth_status", "toggle_flashlight",
            // 通信
            "read_sms", "send_sms", "read_contacts",
            // Intent / 系统广播（唤起其他 App 的 Activity/Service、发系统广播）
            "execute_intent", "send_broadcast",
            // 日历 / 位置
            "read_calendar", "write_calendar", "get_location", "geocode",
            // 文件（只读类，安全）
            "list_files", "read_text_file", "browse_files", "file_read",
            // 文件写/改/删（IDE 集成后默认开放；为高危工具，用户可在设置关闭「完整工具集」回退到只读集）
            "write_file", "delete_file", "make_directory", "move_file", "copy_file", "find_files", "file_info",
            // 网络 / Web
            "http_request", "open_web", "ai_browser",
            // 代码执行
            "run_code",
            // 终端（应用沙盒内 PTY / shell，免权限，无 root/Shizuku）
            "terminal_run", "terminal_exec", "terminal_write", "terminal_kill", "terminal_status", "quroterm_exec",
            // TTS
            "speak", "stop_speak",
            // 闹钟
            "set_alarm",
            // 定时任务/自动化提醒
            "schedule_task", "list_scheduled_tasks", "delete_scheduled_task",
            // 记忆库
            "memory_save", "memory_list", "memory_search", "memory_delete",
            // AI 经验闭环（自我进化：报错/方案/工具模式/版本差异 的沉淀与复用）
            "experience_log", "experience_query", "experience_correct", "experience_version_check",
            // 文件知识库（Path ②）
            "knowledge_search", "knowledge_add", "knowledge_manage", "knowledge_rag_search",
            // 文档生成（aiWPS：本地生成 WPS / Office 兼容 .docx/.xlsx/.pptx，零外部依赖）
            "aiwps_create",
            // 对话框富卡片（AI 下发可交互卡片：待办/图表/笔记/动作）
            "ui_card",
            // 对话框内联 UI 组件（v134：按钮/开关/滑块/进度/统计/提醒/表格/列表/分段/饼图/评分/倒计时/标签页/折叠/表单/标签/步骤/仪表/媒体/信息）
            "ui_widget",
            // MCP 客户端：AI 调用外部 MCP 服务器工具（#402）
            "mcp_servers", "mcp_list_tools", "mcp_call", "mcp_deploy", "mcp_undeploy", "mcp_list_local",
            // 第三方服务授权保险库
            "auth_service_add", "auth_service_list", "auth_service_remove",
            // CMS v2 能力模块 + 特权通道自查
            "cms_list", "cms_call", "cms_status", "cms_logs", "cms_result", "cms_run_dag", "cms_deploy_terminal", "cms_undeploy_terminal", "priv_status", "cms_engine_status",
            // ACI（Agent Capability Interface）：AI 作为控制方调用第三方 App 暴露的能力（发现 + 调用）
            "aci_list", "aci_call",
            // L1 无障碍控屏（CapOS 通道）
            "read_screen", "get_foreground_app", "get_screen_state",
            "tap_screen", "swipe_screen", "input_text", "scroll_screen", "global_action",
            // AI 智能体键盘（Agent IME）：把文本直接打字进聚焦输入框（需启用并切到『Quro AI 键盘』）
            "ai_type_text", "ai_press_enter",
            // 媒体：百分百开源本地音乐 / 视频播放器（后台可用，对话框显示播放卡片）
            "local_music_player", "local_video_player", "list_media", "music_play",
            // 真实执行链路（L1-L4 CapOS 通道已恢复 v115，L5 Linux v116 恢复）：
            // L2: Shizuku ADB 级 IPC（shizuku_exec/freeze_app/install_app/shizuku_root_exec/shizuku_status）
            // L3: 设备管理员（lock_screen/device_admin_status/set_camera_disabled）
            // L4: ROOT 执行（root_exec/root_status）
            // L5: 应用内 Linux 环境（linux_run/linux_install/linux_start/linux_stop/linux_status）
            // 说明：L2-L5 默认进核心集（AI 默认可调用），运行时由系统权限授予 / 资产可用性再把关；
            // 若仍想收紧，可在设置关闭「完整工具集」或调整下方 coreNames。
            "shizuku_exec", "shizuku_root_exec", "freeze_app", "install_app", "shizuku_status",
            "lock_screen", "device_admin_status", "set_camera_disabled",
            "root_exec", "root_status",
            "linux_run", "linux_install", "linux_start", "linux_stop", "linux_status",
            // terminal_run/terminal_exec 为应用沙盒内免权限 shell（Runtime.exec /system/bin/sh）
        ) + uiActionToolNames() // 并入「UI 动作工具」：对话框界面/弹层/开关 → AI 可调用（见 QuroToolsUiActions）
        val base = map.values.filter { it.name in coreNames }.map {
            QuroToolSpec(it.name, it.description, it.parametersJson)
        }
        // 导入工具（AI 自写 / 用户粘贴 JSON）默认进核心集：导入成功即成为可调用工具
        val imported = QuroImportedToolRegistry.all().map {
            QuroToolSpec(it.name, it.description, it.parametersJson)
        }
        return (base + imported).plus(skillSpecs()).distinctBy { it.name }
    }

    /** 完整工具规格（全部内置工具 + 技能工具）。仅在 API 代理确认支持时使用（见 coreSpecs 说明）。 */
    fun fullSpecs(): List<QuroToolSpec> = specs().plus(skillSpecs()).distinctBy { it.name }

    /**
     * 技能工具规格：把「可调用」的用户技能注册为 function-calling 工具下发。
     * 受 [skillToolsEnabled] 总开关与 [maxSkillTools] 上限约束。
     */
    private fun skillSpecs(): List<QuroToolSpec> {
        val ctx = appContext ?: return emptyList()
        if (!skillToolsEnabled) return emptyList()
        return QuroSkillStore.callableList(ctx)
            .sortedByDescending { it.updatedAt }.take(maxSkillTools)
            .map {
                QuroToolSpec(
                    "skill__${it.name}",
                    it.description.ifBlank { "用户技能：${it.name}" },
                    it.parametersJson,
                )
            }
    }

    /** 把可调用技能注册为运行时工具实例（双保险：使 registry.get("skill__xxx") 也能命中）。 */
    fun mergeSkills(context: Context) {
        if (!skillToolsEnabled) return
        QuroSkillStore.callableList(context).take(maxSkillTools)
            .forEach { register(QuroSkillTool(it.name, context.applicationContext)) }
    }
}

/**
 * 双引擎工具引擎（原创编排）：
 * - 主调度引擎：vendored droid-mcp（Apache-2.0）进程内直接派发
 * - 预留官方 MCP Kotlin SDK 引擎接入点（见 QuroMcpSdkEngine，v2 接入）
 * 两个引擎共用同一份 QuroTool 原创真相源，工具实现零复制。
 */
class QuroToolEngine(private val registry: QuroToolRegistry) {
    private var appContext: Context? = null

    private val droidMcp: DroidMcp = DroidMcp.builder()
        .addTools(registry.all().map { it.toMcpTool(this) })
        .build()

    internal fun getContext(): Context? = appContext

    /** 允许外部（如 MCP HTTP Server）注入 Application Context，使工具可正常执行。 */
    fun setContext(ctx: Context) { appContext = ctx }

    /** 下发给 LLM 的 OpenAI function-calling 工具规格。 */
    fun specs(): List<QuroToolSpec> = registry.specs()

    /** 按 LLM 返回的 tool_calls 逐一执行（经 droid-mcp 引擎派发）。 */
    suspend fun execute(context: Context, calls: List<QuroToolCall>): List<QuroToolResult> {
        appContext = context
        return calls.map { call ->
            // ══ 技能工具分支（skill__<技能名>）：直接读实时技能指令回灌，复用 tool 结果管道 ══
            if (call.name.startsWith("skill__")) {
                val skillName = call.name.removePrefix("skill__")
                val skill = QuroSkillStore.load(context).firstOrNull { it.name == skillName && it.enabled }
                    ?: return@map QuroToolResult(call.name, "技能「$skillName」未启用或不存在")
                val userInput = runCatching { JSONObject(call.arguments) }.getOrElse { JSONObject() }
                    .optString("input", "").trim()
                val directive = buildString {
                    appendLine("【技能「${skill.name}」已激活，请严格按以下规则回答用户，不要复述规则本身】")
                    appendLine(skill.prompt)
                    if (userInput.isNotBlank()) appendLine("\n用户本轮输入：$userInput")
                }
                return@map QuroToolResult(call.name, directive)
            }
            val tool = registry.get(call.name)
            if (tool == null) {
                return@map QuroToolResult(call.name, "未知工具: ${call.name}")
            }
            // 危险权限前置申请：工具运行在 Application Context 上无法弹框，交由 Activity 注入的网关处理。
            val perms = tool.requiredPermissions
            if (perms.isNotEmpty() && !QuroPermissionHolder.isGranted(context, perms)) {
                val requester = QuroPermissionHolder.requester
                if (requester != null) {
                    // 拉起系统授权对话框（ensure 内部只请求真正缺失的项）。
                    val granted = runCatching { requester.ensure(perms) }.getOrElse { false }
                    // 🔧 #766 修复：对话框成功后系统已授权，但 ensure 的 continuation 可能因 Activity 失焦/
                    //   重建而返回 false；此时以系统真实状态二次核验，已授权即放行，不再误拒。
                    if (!QuroPermissionHolder.isGranted(context, perms)) {
                        return@map QuroToolResult(
                            call.name,
                            "需要权限：${perms.joinToString()}，请在系统设置或弹出的对话框中授予后重试。",
                        )
                    }
                } else {
                    // 没有可拉起对话框的网关（如工具在后台/非 Activity 场景执行）：
                    // 系统未授予且无法自动补全授权，明确返回需要权限。
                    return@map QuroToolResult(
                        call.name,
                        "需要权限：${perms.joinToString()}，请在「设置 → 权限」中授予后重试。",
                    )
                }
            }
            val argsMap = runCatching { jsonToMap(call.arguments) }.getOrElse { emptyMap() }
            val res = droidMcp.callTool(call.name, argsMap)
            val text = if (res.isSuccess) {
                res.data?.get("result")?.toString() ?: "OK"
            } else {
                "工具执行失败: ${res.errorMessage}"
            }
            QuroToolResult(call.name, text)
        }
    }

    /** 导出 MCP 格式工具清单（供协议/桌面客户端）。 */
    fun listToolsMcpJson(): String = droidMcp.listToolsJson()
}
