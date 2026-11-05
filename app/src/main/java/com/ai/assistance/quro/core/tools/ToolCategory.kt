package com.ai.assistance.quro.core.tools

/**
 * 工具能力类别。
 *
 * 用于「按 Bot 隔离工具」：人格卡 [com.ai.assistance.quro.core.QuroPersona.enabledToolCategories]
 * 声明启用的类别白名单，[ToolCategoryRegistry.categoryOf] 把工具名映射到类别，
 * 下发 tools 字段时只保留白名单内的类别。
 *
 * 白名单为空 = 全部启用（默认，保持旧行为零迁移）。
 */
object ToolCategory {
    /** 通用/基础（默认类别，未显式分类的工具归此）。 */
    const val GENERAL = "general"

    /** 系统/设备状态（电量、网络、传感器、剪贴板、通知、蓝牙、手电等）。 */
    const val SYSTEM = "system"

    /** 应用管理（列应用、启动应用、取包名、调应用函数）。 */
    const val APPS = "apps"

    /** 通信（短信、联系人、拨号）。 */
    const val COMMUNICATION = "communication"

    /** 日历 / 位置 / 提醒 / 闹钟。 */
    const val CALENDAR_LOCATION = "calendar_location"

    /** 文件读写（list/read/write/delete/move/copy/find/转换）。 */
    const val FILES = "files"

    /** 网络 / Web / 检索（http_request、open_web、web_search、read_url、ai_browser）。 */
    const val WEB = "web"

    /** 终端 / 沙箱 / 代码执行 / 构建（terminal、termux、run_code、code_runner、build_apk、creative_studio）。 */
    const val CODE = "code"

    /** 记忆 / 经验 / 技能（memory_*、experience_*、skill 管理）。 */
    const val MEMORY = "memory"

    /** 多媒体（图片/视频/音频识别、生成、翻译、TTS）。 */
    const val MEDIA = "media"

    /** 文档生成 / 排版（aiwps_*、enhanced_doc、aip_compose、chat_doc）。 */
    const val DOCUMENT = "document"

    /** 无障碍控屏 / 系统控制 / 特权（read_screen、tap、adb、shizuku、root 等）。 */
    const val CONTROL = "control"

    /** 动态 UI / 可视化（ui_card、ui_widget、ui_dsl、visual_*、fluid_cloud）。 */
    const val UI = "ui"

    /** 对外桥接 / ACI / MCP / 子智能体。 */
    const val BRIDGE = "bridge"

    /** 全部类别（供 UI 展示清单）。 */
    val ALL: List<String> = listOf(
        GENERAL, SYSTEM, APPS, COMMUNICATION, CALENDAR_LOCATION, FILES, WEB, CODE,
        MEMORY, MEDIA, DOCUMENT, CONTROL, UI, BRIDGE,
    )

    /** 类别中文标签（UI 展示用）。 */
    fun label(key: String): String = when (key) {
        GENERAL -> "通用"
        SYSTEM -> "系统"
        APPS -> "应用"
        COMMUNICATION -> "通信"
        CALENDAR_LOCATION -> "日历/位置"
        FILES -> "文件"
        WEB -> "网络"
        CODE -> "代码"
        MEMORY -> "记忆"
        MEDIA -> "多媒体"
        DOCUMENT -> "文档"
        CONTROL -> "控制"
        UI -> "界面"
        BRIDGE -> "桥接"
        else -> key
    }
}

/**
 * 工具名 → 类别的集中映射表。
 *
 * 为什么不逐个 override [QuroTool.category]：200+ 个工具分散在几十个文件，
 * 逐个改注册/override 易漏且难审。集中一张映射表（按工具名前缀/精确名匹配），
 * 一处维护、一处可审计，且默认回落 [ToolCategory.GENERAL] 保证白名单为空时零影响。
 */
object ToolCategoryRegistry {

    /** 精确名 → 类别。 */
    private val exact: Map<String, String> = mapOf(
        // 技能（渐进式披露）：`skill` 是读全文的唯一出口，必须与 skill__ 同属 MEMORY，
        // 否则按人格白名单过滤时会因落到 GENERAL 而被误裁掉。
        "skill" to ToolCategory.MEMORY,
        "skill_list" to ToolCategory.MEMORY,
        // 系统/设备
        "get_current_time" to ToolCategory.GENERAL,
        "get_device_info" to ToolCategory.SYSTEM,
        "calculate" to ToolCategory.GENERAL,
        "get_battery" to ToolCategory.SYSTEM,
        "get_wifi_info" to ToolCategory.SYSTEM,
        "get_network_info" to ToolCategory.SYSTEM,
        "get_sensors" to ToolCategory.SYSTEM,
        "vibrate" to ToolCategory.SYSTEM,
        "get_clipboard" to ToolCategory.SYSTEM,
        "set_clipboard" to ToolCategory.SYSTEM,
        "get_active_notifications" to ToolCategory.SYSTEM,
        "get_bluetooth_status" to ToolCategory.SYSTEM,
        "toggle_flashlight" to ToolCategory.SYSTEM,
        "enable_screen_capture" to ToolCategory.SYSTEM,
        // 应用
        "list_installed_apps" to ToolCategory.APPS,
        "launch_app" to ToolCategory.APPS,
        "search_and_launch_app" to ToolCategory.APPS,
        "get_package_name" to ToolCategory.APPS,
        "list_app_functions" to ToolCategory.APPS,
        "invoke_app_function" to ToolCategory.APPS,
        // 通信
        "read_sms" to ToolCategory.COMMUNICATION,
        "send_sms" to ToolCategory.COMMUNICATION,
        "read_contacts" to ToolCategory.COMMUNICATION,
        // 日历/位置
        "read_calendar" to ToolCategory.CALENDAR_LOCATION,
        "write_calendar" to ToolCategory.CALENDAR_LOCATION,
        "get_location" to ToolCategory.CALENDAR_LOCATION,
        "geocode" to ToolCategory.CALENDAR_LOCATION,
        "set_alarm" to ToolCategory.CALENDAR_LOCATION,
        "cancel_alarm" to ToolCategory.CALENDAR_LOCATION,
        "list_alarms" to ToolCategory.CALENDAR_LOCATION,
        "schedule_task" to ToolCategory.CALENDAR_LOCATION,
        "list_scheduled_tasks" to ToolCategory.CALENDAR_LOCATION,
        "delete_scheduled_task" to ToolCategory.CALENDAR_LOCATION,
        // 文件
        "list_files" to ToolCategory.FILES,
        "read_text_file" to ToolCategory.FILES,
        "browse_files" to ToolCategory.FILES,
        "file_read" to ToolCategory.FILES,
        "write_file" to ToolCategory.FILES,
        "delete_file" to ToolCategory.FILES,
        "make_directory" to ToolCategory.FILES,
        "move_file" to ToolCategory.FILES,
        "copy_file" to ToolCategory.FILES,
        "find_files" to ToolCategory.FILES,
        "file_info" to ToolCategory.FILES,
        "file_converter" to ToolCategory.FILES,
        "writer" to ToolCategory.FILES,
        "apk_editor" to ToolCategory.FILES,
        "exe_editor" to ToolCategory.FILES,
        "attach_file" to ToolCategory.FILES,
        // 网络/Web
        "http_request" to ToolCategory.WEB,
        "open_web" to ToolCategory.WEB,
        "ai_browser" to ToolCategory.WEB,
        "web_search" to ToolCategory.WEB,
        "read_url" to ToolCategory.WEB,
        "github_search" to ToolCategory.WEB,
        // 代码
        "run_code" to ToolCategory.CODE,
        "code_runner" to ToolCategory.CODE,
        "toolpkg_list" to ToolCategory.CODE,
        "toolpkg_call" to ToolCategory.CODE,
        "project_create" to ToolCategory.CODE,
        "creative_studio" to ToolCategory.CODE,
        "terminal" to ToolCategory.CODE,
        "termux" to ToolCategory.CODE,
        "quroterm_exec" to ToolCategory.CODE,
        "build_apk" to ToolCategory.CODE,
        "export_apk" to ToolCategory.CODE,
        // 记忆/经验/技能
        "memory_save" to ToolCategory.MEMORY,
        "memory_list" to ToolCategory.MEMORY,
        "memory_search" to ToolCategory.MEMORY,
        "memory_delete" to ToolCategory.MEMORY,
        "memory_space" to ToolCategory.MEMORY,
        "memory_rebuild" to ToolCategory.MEMORY,
        "memory_autosave" to ToolCategory.MEMORY,
        "memory_window_plan" to ToolCategory.MEMORY,
        "memory_export" to ToolCategory.MEMORY,
        "experience_log" to ToolCategory.MEMORY,
        "experience_query" to ToolCategory.MEMORY,
        "experience_correct" to ToolCategory.MEMORY,
        "experience_version_check" to ToolCategory.MEMORY,
        "knowledge_search" to ToolCategory.MEMORY,
        "knowledge_add" to ToolCategory.MEMORY,
        "knowledge_manage" to ToolCategory.MEMORY,
        "knowledge_rag_search" to ToolCategory.MEMORY,
        // 多媒体
        "speak" to ToolCategory.MEDIA,
        "stop_speak" to ToolCategory.MEDIA,
        "image_gen" to ToolCategory.MEDIA,
        "video_gen" to ToolCategory.MEDIA,
        "translate" to ToolCategory.MEDIA,
        "image_recognition" to ToolCategory.MEDIA,
        "audio_recognition" to ToolCategory.MEDIA,
        "video_recognition" to ToolCategory.MEDIA,
        "list_media" to ToolCategory.MEDIA,
        // 文档
        "aiwps_create" to ToolCategory.DOCUMENT,
        "aiwps_read" to ToolCategory.DOCUMENT,
        "aiwps_edit" to ToolCategory.DOCUMENT,
        "enhanced_doc_create" to ToolCategory.DOCUMENT,
        "aip_compose" to ToolCategory.DOCUMENT,
        "chat_doc" to ToolCategory.DOCUMENT,
        // UI/可视化
        "ui_card" to ToolCategory.UI,
        "ui_widget" to ToolCategory.UI,
        "ui_dsl_spec" to ToolCategory.UI,
        "ui_validate" to ToolCategory.UI,
        "fluid_cloud" to ToolCategory.UI,
        "node_editor" to ToolCategory.UI,
        "visual_studio" to ToolCategory.UI,
        "codecanvas_probe" to ToolCategory.UI,
        "codecanvas_script" to ToolCategory.UI,
        "codecanvas_code_card" to ToolCategory.UI,
        "codecanvas_markup" to ToolCategory.UI,
        "codecanvas_llm_code" to ToolCategory.UI,
    )

    /** 前缀 → 类别（覆盖 skill__、aci_、mcp_、ui_、visual_、shizuku_、root_、adb_ 等族）。 */
    private val prefixes: List<Pair<String, String>> = listOf(
        "memory_" to ToolCategory.MEMORY,
        "experience_" to ToolCategory.MEMORY,
        "knowledge_" to ToolCategory.MEMORY,
        "skill__" to ToolCategory.MEMORY,
        "mcp__" to ToolCategory.BRIDGE,
        "aci_" to ToolCategory.BRIDGE,
        "aidl_aci_" to ToolCategory.BRIDGE,
        "mcp_aci_" to ToolCategory.BRIDGE,
        "spawn_subagent" to ToolCategory.BRIDGE,
        "adb_" to ToolCategory.CONTROL,
        "shizuku_" to ToolCategory.CONTROL,
        "root_" to ToolCategory.CONTROL,
        "priv_" to ToolCategory.CONTROL,
        "device_admin_" to ToolCategory.CONTROL,
        "read_screen" to ToolCategory.CONTROL,
        "screenshot" to ToolCategory.CONTROL,
        "visual_" to ToolCategory.UI,
        "ui_" to ToolCategory.UI,
        "ai_keyboard_" to ToolCategory.CONTROL,
        "browser_act" to ToolCategory.WEB,
        "packet_capture" to ToolCategory.WEB,
    )

    fun categoryOf(toolName: String): String {
        exact[toolName]?.let { return it }
        for ((prefix, cat) in prefixes) {
            if (toolName.startsWith(prefix)) return cat
        }
        return ToolCategory.GENERAL
    }

    /** 按白名单过滤工具名：白名单为空=全部通过；否则仅保留类别命中白名单的。 */
    fun filter(names: List<String>, enabledCategories: List<String>): List<String> {
        if (enabledCategories.isEmpty()) return names
        val allowed = enabledCategories.toSet()
        return names.filter { categoryOf(it) in allowed }
    }
}

/** 工具类别 → 中文标签清单（Bot 设置页白名单多选用）。 */
fun allToolCategoryLabels(): List<Pair<String, String>> = listOf(
    ToolCategory.GENERAL to "通用/基础",
    ToolCategory.SYSTEM to "系统与设备状态",
    ToolCategory.APPS to "应用管理",
    ToolCategory.COMMUNICATION to "通信（短信/联系人/拨号）",
    ToolCategory.CALENDAR_LOCATION to "日历/位置/提醒",
    ToolCategory.FILES to "文件读写",
    ToolCategory.WEB to "网络/检索/浏览器",
    ToolCategory.CODE to "终端/代码执行",
    ToolCategory.MEMORY to "记忆/经验/技能",
    ToolCategory.MEDIA to "多媒体",
    ToolCategory.DOCUMENT to "文档",
    ToolCategory.CONTROL to "设备控制",
    ToolCategory.UI to "动态 UI",
    ToolCategory.BRIDGE to "桥接",
)
