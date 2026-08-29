package com.ai.assistance.quro.core.tools

import com.ai.assistance.quro.core.QuroToolSpec

/**
 * 渐进式工具披露（progressive tool disclosure / tool router）。
 *
 * 解决痛点：此前每次请求都把【全部工具】的完整 JSON-Schema 塞进 `tools` 字段
 * （164 个 ~19.8K tokens），模型每轮都要扫全部；同时系统提示词又把同样的全量
 * 工具清单逐条列一遍（双份开销）。
 *
 * 新架构（对齐用户诉求「先路由、再看该看的、用时再加载、每个工具有自己的提示词」）：
 * 1. 每轮只下发一个紧凑【工具路由目录】(tool_router) + 常驻核心集(alwaysOn) + 已加载集(loaded)。
 * 2. 模型先用 tool_router 检索：list_categories / match_intent / get_schema。
 * 3. 调 get_schema(name) 时，返回该工具【完整参数 Schema + 专属使用提示词】（每个工具自己的
 *    useCases/examples/tips/relatedTools，分类好的），并把它标记为「已加载」——
 *    下一轮起该工具的【真实可执行 schema】进入 `tools` 字段，模型即可直接调用。
 * 4. 已加载集跨轮次保留（对话级），新会话清空；常驻核心集每轮都在，免去高频工具的 discovery 往返。
 */
class QuroToolRouter(allSpecs: List<QuroToolSpec>) {

    companion object {
        /** 渐进式披露总开关。false=退回旧行为（全量下发，对齐 v1.0.64 已知良好基线）。 */
        @Volatile var PROGRESSIVE: Boolean = false

        /**
         * 常驻核心集：高频、用户口语最常触发、依赖链最短的工具每轮都直接下发，
         * 模型无需先 discovery 即可调用（对应「AI 百分百先看哪些」）。
         * 其余工具经 tool_router 按需加载。
         */
        val ALWAYS_ON: Set<String> = setOf(
            "get_current_time", "get_device_info", "calculate", "get_battery",
            "get_wifi_info", "get_network_info", "get_sensors", "get_clipboard", "set_clipboard",
            "vibrate",
            "list_installed_apps", "launch_app", "search_and_launch_app", "get_package_name",
            "get_active_notifications", "get_bluetooth_status", "toggle_flashlight",
            "read_sms", "send_sms", "read_contacts",
            "execute_intent", "send_broadcast",
            "read_calendar", "write_calendar", "get_location", "geocode",
            "list_files", "read_text_file", "browse_files", "file_read",
            "write_file", "delete_file", "make_directory", "move_file", "copy_file", "find_files", "file_info",
            "http_request", "open_web", "ai_browser",
            "run_code", "creative_studio",
            "terminal_run", "terminal_exec", "terminal_write", "terminal_kill", "terminal_status", "quroterm_exec",
            "speak", "stop_speak",
            "set_alarm", "schedule_task", "list_scheduled_tasks", "delete_scheduled_task",
            "memory_save", "memory_list", "memory_search", "memory_delete",
            "experience_log", "experience_query", "experience_correct", "experience_version_check",
            "knowledge_search", "knowledge_add", "knowledge_manage", "knowledge_rag_search",
            "aiwps_create", "chat_doc", "enhanced_doc_create",
            "ui_card", "ui_widget", "ui_control",
            "mcp_servers", "mcp_list_tools", "mcp_call", "mcp_deploy", "mcp_undeploy", "mcp_list_local",
            "mcp_aci_list", "mcp_aci_call", "mcp_aci_bridge",
            "auth_service_add", "auth_service_list", "auth_service_remove",
            "cms_list", "cms_call", "cms_status", "cms_logs", "cms_result", "cms_run_dag",
            "cms_deploy_terminal", "cms_undeploy_terminal", "priv_status", "cms_engine_status",
            "aci_list", "aci_call",
            "workspace_write", "workspace_read", "workspace_list",
            "read_screen", "get_foreground_app", "get_screen_state",
            "tap_screen", "swipe_screen", "long_press_screen", "input_text",
            "scroll_screen", "global_action",
            "ai_type_text", "ai_press_enter", "ai_press_send",
            "local_music_player", "local_video_player", "list_media", "music_play",
            "shizuku_exec", "shizuku_root_exec", "freeze_app", "install_app", "shizuku_status",
            "lock_screen", "device_admin_status", "set_camera_disabled",
            "root_exec", "root_status",
            "linux_run", "linux_install", "linux_start", "linux_stop", "linux_status",
            "dev_env",
            "fluid_cloud_notify",
        )

        private val CATALOG_PARAMS_JSON = """{
  "type": "object",
  "properties": {
    "action": {
      "type": "string",
      "description": "操作类型",
      "enum": ["list_categories", "list_tools", "match_intent", "get_schema", "get_best_practices", "get_directory_summary"]
    },
    "category": { "type": "string", "description": "分类名（list_tools 使用）" },
    "intent": { "type": "string", "description": "用户意图描述（match_intent 使用）" },
    "name": { "type": "string", "description": "工具名称（get_schema 使用）" }
  },
  "required": ["action"]
}"""
    }

    /** 全部可用工具规格（每次 ask 刷新，保留 loaded 状态）。 */
    private var allSpecs: List<QuroToolSpec> = allSpecs
    private var specByName: Map<String, QuroToolSpec> = allSpecs.associateBy { it.name }

    init {
        // 工具能力目录以本 router 的真实 specs 为单一真相源，确保 match_intent / get_schema 查到的目录
        // 与模型实际能调的工具集严格一致（修复「目录残缺→很多功能得提醒才用」）。
        ToolCapabilityDirectory.install(allSpecs)
    }

    /** 对话级已加载工具（跨轮次保留，新会话清空）。 */
    private val loaded = LinkedHashSet<String>()

    fun setSpecs(specs: List<QuroToolSpec>) {
        allSpecs = specs
        specByName = specs.associateBy { it.name }
        ToolCapabilityDirectory.install(specs)
    }

    fun reset() = loaded.clear()

    /** 当前下发给 LLM 的 tools：路由目录 + 常驻核心 + 已加载（排除 tool_discovery 避免重复）。 */
    fun activeSpecs(): List<QuroToolSpec> {
        val out = ArrayList<QuroToolSpec>(allSpecs.size + 1)
        out += catalogSpec()
        for (s in allSpecs) {
            if (s.name == "tool_discovery") continue // 由 tool_router 接管发现能力，避免两个目录工具
            if (s.name in ALWAYS_ON || s.name in loaded) out += s
        }
        return out
    }

    /** 处理模型对 tool_router 的调用，返回结果文本（get_schema 会标记工具为已加载）。 */
    fun handle(name: String, arguments: String): String {
        val args = runCatching { org.json.JSONObject(arguments) }.getOrElse { org.json.JSONObject() }
        val action = args.optString("action", "list_categories")
        return when (action) {
            "list_categories" -> listCategories()
            "list_tools" -> listTools(args.optString("category"))
            "match_intent" -> matchIntent(args.optString("intent"))
            "get_schema" -> getSchema(args.optString("name"))
            "get_best_practices" -> ToolCapabilityDirectory.buildBestPractices()
            "get_directory_summary" -> buildCompactIndex()
            else -> "未知操作：$action（支持 list_categories / list_tools / match_intent / get_schema / get_best_practices / get_directory_summary）"
        }
    }

    // ───────────────────────── tool_router 目录工具 ─────────────────────────

    private fun catalogSpec(): QuroToolSpec {
        val index = buildCompactIndex()
        val desc = buildString {
            appendLine("# 工具路由目录（按需加载，禁止瞎猜工具名）")
            appendLine("你拥有大量工具，但每轮只下发【已加载】工具的【真实可执行 schema】。先用本工具检索，再调用。")
            appendLine()
            appendLine("## 操作")
            appendLine("- `list_categories()`：列出所有工具分类。")
            appendLine("- `match_intent(intent=\"用户需求描述\")`：按意图匹配工具（最常用）。")
            appendLine("- `get_schema(name=\"工具名\")`：返回该工具【完整参数 JSON Schema + 专属使用提示词】，并加载它（下一轮起即可直接调用）。")
            appendLine("- `list_tools(category=\"分类名\")`：查看某分类下的工具。")
            appendLine("- `get_directory_summary()`：全部工具速查。")
            appendLine()
            appendLine("## 当前已加载（可直接调用，无需再查）：${if (loaded.isEmpty()) "（暂无可调，先调 get_schema 加载你需要的）" else loaded.joinToString(", ")}")
            appendLine()
            appendLine("## 全部工具分类索引（name：一句话说明）—— 找工具先从这里看：")
            appendLine(index)
        }
        return QuroToolSpec("tool_router", desc, CATALOG_PARAMS_JSON)
    }

    private fun getSchema(toolName: String): String {
        if (toolName.isBlank()) return "请提供工具名称，例如：get_schema(name=\"send_sms\")"
        val spec = specByName[toolName]
        if (spec == null) {
            return "未找到工具：$toolName\n可用工具见 list_categories() / match_intent(intent=...)。" +
                "已加载的可直接调用：${if (loaded.isEmpty()) "（无）" else loaded.joinToString(", ")}"
        }
        loaded.add(toolName) // 标记加载：下一轮真实 schema 进入 tools，模型即可直接调用
        val info = ToolCapabilityDirectory.getToolInfo(toolName)
        return buildString {
            appendLine("## ✅ 已加载工具：$toolName（下一轮起可直接调用，无需再查）")
            appendLine()
            appendLine("**分类**：${info?.category?.displayName ?: "通用"}")
            appendLine("**说明**：${spec.description}")
            appendLine()
            appendLine("**完整参数 JSON Schema（调用时严格按此填写）**：")
            appendLine("```json")
            appendLine(spec.parametersJson)
            appendLine("```")
            if (info != null) {
                if (info.useCases.isNotEmpty()) {
                    appendLine(); appendLine("**使用场景**："); info.useCases.forEach { appendLine("- $it") }
                }
                if (info.examples.isNotEmpty()) {
                    appendLine(); appendLine("**调用示例**："); info.examples.forEach { appendLine("- `$it`") }
                }
                if (info.tips.isNotEmpty()) {
                    appendLine(); appendLine("**使用技巧**："); info.tips.forEach { appendLine("- $it") }
                }
                if (info.relatedTools.isNotEmpty()) {
                    appendLine(); appendLine("**相关工具**：${info.relatedTools.joinToString("、")}")
                }
            } else {
                appendLine(); appendLine("（该工具暂无详细使用指南，请按上方参数 Schema 调用。）")
            }
        }
    }

    private fun listCategories(): String {
        val cats = allSpecs.mapNotNull { categorize(it.name)?.displayName }.toSet().sorted()
        return buildString {
            appendLine("## 工具分类（共 ${cats.size} 类）")
            cats.forEach { appendLine("- $it") }
            appendLine()
            appendLine("使用 list_tools(category=XXX) 查看具体工具；或 match_intent(intent=...) 按意图匹配；或 get_schema(name=...) 加载并查看某工具完整说明。")
        }
    }

    private fun listTools(category: String): String {
        val tools = if (category.isBlank()) allSpecs else allSpecs.filter { categorize(it.name)?.displayName == category }
        if (tools.isEmpty()) return "未找到分类：$category\n可用分类见 list_categories()。或 match_intent(intent=...) 按意图匹配。"
        return buildString {
            appendLine("## ${if (category.isBlank()) "全部工具" else category}（${tools.size} 个）")
            tools.sortedBy { it.name }.forEach { appendLine("- ${it.name}：${it.description}") }
            appendLine()
            appendLine("需要某工具的完整参数与用法，调 get_schema(name=工具名) 加载。")
        }
    }

    private fun matchIntent(intent: String): String {
        if (intent.isBlank()) return "请提供用户意图，例如：match_intent(intent=\"打开网页并搜索资料\")"
        val names = allSpecs.map { it.name }.toSet()
        val matched = ToolCapabilityDirectory.matchToolsByIntent(intent).filter { it.name in names }
        if (matched.isEmpty()) {
            return "未找到匹配「$intent」的工具。可用分类见 list_categories()；或 get_schema(name=...) 直接加载你知道名字的工具。"
        }
        return buildString {
            appendLine("## 意图匹配：「$intent」")
            matched.sortedByDescending { it.priority }.forEachIndexed { i, t ->
                appendLine("${i + 1}. **${t.name}**（${(t.category?.displayName ?: "通用")}）：${t.description}")
            }
            appendLine()
            appendLine("调用 get_schema(name=工具名) 加载并查看完整参数后使用。")
        }
    }

    /** 紧凑分类索引：按分类聚合全部工具的 name+一句话说明（替代旧的全量 tools 字段）。 */
    private fun buildCompactIndex(): String {
        val byCat = allSpecs.groupBy { categorize(it.name)?.displayName ?: "其他工具" }
        return buildString {
            byCat.toSortedMap().forEach { (cat, tools) ->
                appendLine("## $cat")
                tools.sortedBy { it.name }.forEach { appendLine("- ${it.name}：${it.description}") }
            }
        }
    }

    // ───────────────────────── 分类推断（目录里没有的工具按命名归类） ─────────────────────────

    private fun categorize(name: String): ToolCapabilityDirectory.ToolCategory? {
        ToolCapabilityDirectory.getToolInfo(name)?.category?.let { return it }
        return when {
            name.startsWith("workspace_") -> ToolCapabilityDirectory.ToolCategory.WORKSPACE
            name.startsWith("aci_") -> ToolCapabilityDirectory.ToolCategory.APP_MANAGEMENT
            name.startsWith("mcp_") -> ToolCapabilityDirectory.ToolCategory.NETWORK_WEB
            name.startsWith("cms_") -> ToolCapabilityDirectory.ToolCategory.AI_CAPABILITIES
            name.startsWith("memory_") || name.startsWith("experience_") || name.startsWith("knowledge_") ->
                ToolCapabilityDirectory.ToolCategory.KNOWLEDGE_MEMORY
            name.startsWith("terminal_") || name.startsWith("linux_") || name.startsWith("quroterm_") ->
                ToolCapabilityDirectory.ToolCategory.TERMINAL_LINUX
            name.startsWith("ui_") -> ToolCapabilityDirectory.ToolCategory.UI_CARDS
            name.startsWith("skill__") -> ToolCapabilityDirectory.ToolCategory.AI_CAPABILITIES
            name in setOf("read_screen", "tap_screen", "swipe_screen", "long_press_screen", "scroll_screen",
                "input_text", "get_foreground_app", "get_screen_state", "screenshot", "screenshot_base64",
                "visual_analysis", "visual_question", "visual_action", "visual_popup", "visual_custom_popup") ->
                ToolCapabilityDirectory.ToolCategory.ACCESSIBILITY
            name.startsWith("send_sms") || name.startsWith("read_contacts") || name.startsWith("read_sms") ||
                name.contains("calendar") -> ToolCapabilityDirectory.ToolCategory.COMMUNICATION
            name.startsWith("launch_app") || name.startsWith("open_app") || name.startsWith("list_installed") ||
                name.startsWith("search_and_launch") || name.startsWith("install_app") || name.startsWith("freeze_app") ||
                name.startsWith("get_package_name") -> ToolCapabilityDirectory.ToolCategory.APP_MANAGEMENT
            name.startsWith("volume") || name.startsWith("brightness") || name.startsWith("wifi") ||
                name.startsWith("bluetooth") || name.startsWith("notification") || name.startsWith("airplane") ||
                name.startsWith("screen_rotation") || name.startsWith("set_timer") ->
                ToolCapabilityDirectory.ToolCategory.SYSTEM_CONTROL
            name.startsWith("image") || name.startsWith("video") || name.startsWith("audio") ||
                name.startsWith("music") || name.startsWith("local_") || name.startsWith("media") ||
                name.startsWith("list_media") -> ToolCapabilityDirectory.ToolCategory.MEDIA
            name.startsWith("shizuku") || name.startsWith("root_") || name.startsWith("lock_screen") ||
                name.startsWith("device_admin") || name.startsWith("set_camera") || name.startsWith("priv_") ->
                ToolCapabilityDirectory.ToolCategory.SECURITY
            name.startsWith("get_") || name in setOf("calculate", "vibrate", "set_clipboard", "get_clipboard") ->
                ToolCapabilityDirectory.ToolCategory.BASIC
            else -> null
        }
    }
}
