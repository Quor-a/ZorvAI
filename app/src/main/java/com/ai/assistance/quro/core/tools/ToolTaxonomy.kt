package com.ai.assistance.quro.core.tools

import com.ai.assistance.quro.core.tools.ToolCapabilityDirectory.ToolCategory

/**
 * 工具分类的**唯一真相源**（single source of truth）。
 *
 * ## 为什么要有这个文件
 *
 * 改造前分类逻辑有**两份独立实现**，靠注释互相提醒「必须同源」来人工同步：
 *  - `ToolCapabilityDirectory.inferCategory(name)` —— 目录侧，末尾 `else -> BASIC`
 *  - `QuroToolRouter.categorize(name)` —— 路由侧，末尾 `else -> null`
 *
 * 这种「两份手写 when + 靠人盯」的结构必然漂移，而且已经漂了：
 * `cms_*` 在目录侧归 `CMS_DEVELOPMENT`、在路由侧归 `AI_CAPABILITIES`。
 * 更严重的是两侧都是**逐条打补丁**式增长（每次发现某个工具归错就补一条规则），
 * 谁也没有全量覆盖约定 —— 实测 239 个工具里有 100 个（42%）落到兜底 `BASIC`。
 *
 * 落到兜底的后果不是「分类不精确」这么轻：`tool_router` 按分类列举时列不出它们，
 * 模型经分类检索**永远查不到** write_file / run_code / set_alarm / http_request 这些日常工具，
 * 于是只能把它们全部塞进 `ALWAYS_ON` 每轮全量下发 —— 常驻集因此膨胀到 154 个，
 * 每轮请求固定多带十几万字符的 schema，**这才是响应变慢的真正机制**。
 *
 * ## 现在的约定
 *
 * 1. 规则集中在 [RULES]，三层优先级：**精确名单 → 前缀 → 关键词**，先命中先返回；
 * 2. 两侧（目录 taxonomy 与路由）都只调 [categoryOf]，不再各写一份 when；
 * 3. [UNCLASSIFIED] 是显式兜底而不是「归 BASIC 了事」：含义是「这个工具名没人管」；
 * 4. 配 `ToolTaxonomyTest` 全量自检：**任何注册工具落到 UNCLASSIFIED，测试直接失败**。
 *    以后新增工具忘了归类，CI 立刻会报，而不是等用户投诉「AI 不会用」。
 */
object ToolTaxonomy {

    /**
     * 兜底分类。
     *
     * 用 BASIC 本身承载会掩盖问题（BASIC 是「时间/设备信息/计算」这类真实分类，
     * 和「没人归类的杂项」混在一起就再也发现不了漏网的）。这里显式区分。
     */
    val UNCLASSIFIED: ToolCategory = ToolCategory.BASIC

    /** 一条分类规则：命中 [matches] 即归入 [category]。 */
    private class Rule(
        val category: ToolCategory,
        val exact: Set<String> = emptySet(),
        val prefixes: List<String> = emptyList(),
        val containsAll: List<String> = emptyList(),
    ) {
        fun matches(name: String): Boolean {
            if (name in exact) return true
            for (p in prefixes) if (name.startsWith(p)) return true
            for (c in containsAll) if (!name.contains(c)) return false
            return containsAll.isNotEmpty()
        }
    }

    /**
     * 有序规则表。**顺序即优先级**，改顺序会改变归属，动之前先想清楚：
     * 精确名单永远放前面（`image_gen` 必须在 `image*` 前缀命中之前归到 AI_CAPABILITIES），
     * 宽前缀放后面，关键词兜在最末。
     */
    private val RULES: List<Rule> = listOf(

        // ════════ 顶层：这几类一旦混进别的分类就会导致「用错工具」，必须最优先 ════════
        Rule(ToolCategory.VIRTUAL_SCREEN, exact = setOf("bg_screen", "pet")),
        Rule(ToolCategory.ADB_DEBUG, exact = setOf("adb_dev", "adb_term")),
        Rule(ToolCategory.TERMUX, prefixes = listOf("termux")),
        // 生图/生视频/多模态识别必须在 MEDIA 的 image*/video* 之前，否则被当成媒体文件处理
        Rule(
            ToolCategory.AI_CAPABILITIES,
            exact = setOf(
                "image_gen", "img_gen_provider", "video_gen", "audio_recognition",
                "visual_analysis", "video_recognition",
                "creative_studio", "tool_discovery", "zorvai_capabilities",
                "kaleidobox", "visual",
            ),
        ),

        // ════════ 界面与产出通道 ════════
        Rule(ToolCategory.ZXWIDGET, prefixes = listOf("zx_")),
        Rule(ToolCategory.GENUI, prefixes = listOf("genui_")),
        Rule(ToolCategory.GENUI, exact = setOf("miniapp", "miniapp_sdk")),
        // 🔴 界面导航（2026-11-06 合体后）：37 个 ui_open_*/ui_toggle_* 散装工具已退役，
        // 唯一入口 = ui_control（open/toggle/sheet/chat + 历史动作名兼容直通）。
        Rule(
            ToolCategory.APP_NAVIGATION,
            exact = setOf("ui_control"),
        ),
        Rule(ToolCategory.DYNAMIC_UI, exact = setOf("ui_dsl_spec", "ui_validate", "game_ui", "automatic_ui")),
        Rule(ToolCategory.UI_CARDS, exact = setOf("card_patch", "card_catalog", "ui_card", "ui_widget", "ui_control")),
        Rule(ToolCategory.UI_CARDS, prefixes = listOf("ui_")),
        // ui_tree 是 dump 无障碍节点树（供自动点击定位），属于无障碍读屏，不是卡片工具
        Rule(ToolCategory.ACCESSIBILITY, exact = setOf("ui_tree")),
        // 🔴 可视化小卡片是**独立分类**，必须在 `visual_` 宽前缀之前精确命中，
        //    否则会被归进「可视化交互/弹窗」，模型检索「小卡片」时永远找不到它。
        Rule(
            ToolCategory.WIDGET_CARD,
            exact = setOf("visual_card", "visual_card_skeleton"),
        ),
        Rule(
            ToolCategory.VISUAL_INTERACT,
            exact = setOf(
                "visual_question", "visual_ask", "visual_action", "visual_popup",
                "visual_custom_popup", "visual_analysis",
            ),
            prefixes = listOf("visual_"),
        ),

        // ════════ 终端 / 执行 / 开发构建 ════════
        Rule(
            ToolCategory.TERMINAL_LINUX,
            exact = setOf(
                "terminal", "moyu", "harness", "tmux", "term_monitor",
                "run_code", "python_run", "code_runner", "polyglot_run", "syntax_check",
                "lsposed", "git",
            ),
            prefixes = listOf("terminal_", "linux_", "quroterm_", "moyu_", "harness_"),
        ),
        // 构建产物类（打 APK、导出、编辑包内文件）属于开发构建，不是普通文件操作
        Rule(
            ToolCategory.PLUGIN,
            exact = setOf(
                "apk_plugin", "apk_editor", "exe_editor", "build_apk", "export_apk",
                "node_editor", "project_create", "dev_env",
            ),
            prefixes = listOf("toolpkg_"),
        ),
        Rule(ToolCategory.CMS_DEVELOPMENT, prefixes = listOf("cms_")),

        // ════════ 文件 ════════
        Rule(
            ToolCategory.FILE_OPERATION,
            exact = setOf(
                "write_file", "read_text_file", "list_files", "copy_file", "move_file",
                "delete_file", "find_files", "file_info", "make_directory", "browse_files",
                "file_read", "attach_file", "file_converter", "ffmpeg", "search_files",
                "read_file", "share_file",
            ),
            prefixes = listOf("file_"),
        ),

        // ════════ 网络 / 检索 ════════
        Rule(
            ToolCategory.NETWORK_WEB,
            exact = setOf(
                "http_request", "open_web", "browser_act", "ai_browser", "web_crawler",
                "crossref_search", "github_read", "github_search", "search_provider",
                "web_search", "read_url",
            ),
            prefixes = listOf("mcp_", "web_", "browser_"),
        ),

        // ════════ 通讯 / 日程 ════════
        Rule(
            ToolCategory.COMMUNICATION,
            exact = setOf(
                "send_sms", "read_sms", "read_contacts", "set_alarm", "cancel_alarm",
                "list_alarms", "geocode", "get_location", "train_12306",
            ),
            prefixes = listOf("send_sms", "read_sms", "read_contacts"),
            containsAll = listOf("calendar"),
        ),

        // ════════ 工作流 / 定时自动化 ════════
        Rule(
            ToolCategory.WORKFLOW,
            exact = setOf(
                "wf_create", "wf_trigger", "wf_run_status", "workflow_run", "tasker",
                "schedule_task", "list_scheduled_tasks", "delete_scheduled_task",
            ),
        ),

        // ════════ 看屏 / 屏幕操作（用户这块屏 + 理解屏幕画面）════════
        // 🔴 把全部「看屏」能力聚到一类：read_screen/screenshot/tap_screen/ui_tree 这些原本散在
        // ACCESSIBILITY 的、visual_analysis 原本在 VISUAL_INTERACT 的、image_recognition 原本在
        // AI_CAPABILITIES 的，统一收进 SCREEN_PERCEPTION，让 AI 操控虚拟屏/读屏/截图时能一次列全。
        // bg_screen（后台虚拟屏）仍单列 VIRTUAL_SCREEN（见 ToolTaxonomyTest 硬断言），靠
        // ToolCapabilityDirectory.getScreenTools() 与它们互链，不重复进本规则。
        Rule(
            ToolCategory.SCREEN_PERCEPTION,
            exact = setOf(
                "read_screen", "tap_screen", "swipe_screen", "long_press_screen",
                "scroll_screen", "input_text", "get_foreground_app", "get_screen_state",
                "screenshot", "screenshot_base64", "global_action", "enable_screen_capture",
                "screen_record", "take_photo", "ai_type_text", "ai_press_enter", "ai_press_send",
                "ui_tree", "visual_analysis", "image_recognition",
            ),
        ),

        // ════════ 媒体 ════════
        Rule(
            ToolCategory.MEDIA,
            exact = setOf(
                "speak", "stop_speak", "translate", "list_media", "music_play",
                "local_music_player", "local_video_player",
            ),
            prefixes = listOf("audio_", "video_", "music_", "media_", "local_", "list_media"),
        ),

        // ════════ 知识 / 记忆 / 技能 ════════
        Rule(
            ToolCategory.KNOWLEDGE_MEMORY,
            exact = setOf("rag_search"),
            prefixes = listOf("memory_", "experience_", "knowledge_"),
        ),
        Rule(ToolCategory.AI_CAPABILITIES, prefixes = listOf("skill_", "cluster_", "codecanvas_")),
        Rule(ToolCategory.AI_CAPABILITIES, exact = setOf("assistant_skill")),

        // ════════ 文档（AIP 长文档 / Office）════════
        Rule(
            ToolCategory.AIP_DOC,
            exact = setOf("chat_doc", "enhanced_doc_create", "writer"),
            prefixes = listOf("aip_", "aiwps_"),
        ),

        // ════════ 工作区 ════════
        Rule(ToolCategory.WORKSPACE, prefixes = listOf("workspace_")),

        // ════════ 应用管理 ════════
        Rule(
            ToolCategory.APP_MANAGEMENT,
            exact = setOf(
                "install_app", "freeze_app", "get_package_name", "execute_intent",
                "send_broadcast", "list_app_functions", "invoke_app_function",
            ),
            prefixes = listOf(
                "aci_", "launch_app", "open_app", "list_installed", "search_and_launch",
            ),
        ),

        // ════════ 安全 / 特权 / 隐私 ════════
        Rule(
            ToolCategory.SECURITY,
            exact = setOf(
                "lock_screen", "private_db", "sandbox", "packet_capture", "fluid_cloud_notify",
            ),
            prefixes = listOf(
                "shizuku", "root_", "device_admin", "set_camera", "priv_", "auth_service_",
            ),
        ),

        // ════════ 系统控制 ════════
        Rule(
            ToolCategory.SYSTEM_CONTROL,
            exact = setOf(
                "toggle_flashlight", "set_timer", "show_alarms", "set_wheel",
                "wake_device", "dismiss_keyguard",
            ),
            prefixes = listOf(
                "volume", "brightness", "wifi", "bluetooth", "notification",
                "airplane", "screen_rotation", "set_timer",
            ),
        ),

        // ════════ 基础 ════════
        Rule(
            ToolCategory.BASIC,
            exact = setOf(
                "calculate", "vibrate", "set_clipboard", "get_clipboard",
                "get_current_time", "get_device_info", "get_battery",
            ),
            prefixes = listOf("get_"),
        ),
    )

    /**
     * 给工具名定分类。
     *
     * 先查手写目录（人在 handbook 里显式声明过的，永远最权威），再走规则表，
     * 都不命中返回 [UNCLASSIFIED]（由自检测试兜住，正常不该出现）。
     */
    fun categoryOf(name: String): ToolCategory {
        val n = name.trim()
        if (n.isEmpty()) return UNCLASSIFIED
        // 1) 手写条目优先：人显式写过的分类，不受规则调整影响
        runCatching { ToolCapabilityDirectory.getToolInfo(n)?.category }.getOrNull()?.let { return it }
        // 2) 规则表
        for (r in RULES) if (r.matches(n)) return r.category
        // 3) 显式兜底
        return UNCLASSIFIED
    }

    /** 该工具是否被明确分类（不是靠兜底）。自检测试用它判定「有没有人管」。 */
    fun isClassified(name: String): Boolean {
        val n = name.trim()
        if (n.isEmpty()) return false
        runCatching { ToolCapabilityDirectory.getToolInfo(n) }.getOrNull()?.let { return true }
        return RULES.any { it.matches(n) }
    }

    /** 全部规则覆盖到的分类（供自检/统计）。 */
    fun ruleCategories(): Set<ToolCategory> = RULES.map { it.category }.toSet()
}
