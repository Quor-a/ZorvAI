package com.ai.assistance.quro.core.chat

import java.util.Locale
import org.json.JSONObject

/**
 * 活动流（Activity Stream）—— 一轮回复里「思考 + 工具调用」的**逐条明细**。
 *
 * ## 为什么要有它
 *
 * 早期实现把一轮里的所有工具折叠成「· N 工具」一个胶囊，展开是一坨 JSON。
 * 用户看不到**AI 到底做了什么**：跑了几条命令、写了哪个文件、失败了哪一步。
 *
 * 现在的形态：
 * - 顶部一行**汇总**：「思考、运行了 6 条命令、操作了 1 次浏览器、…」
 * - 下面**逐条**列出，每条 = 图标 + 一句话描述（成功黑字 / 失败红字）
 * - 点任意一条 → 底部弹层看该次的完整细节（参数 / 结果 / 错误 / 命令输出 / diff）
 *
 * ## 类别（[ActivityCategory]）与图标、汇总文案严格一一对应
 *
 * 汇总行的措辞由 [ActivityCategory] 决定，不是自由文本 —— 这样「运行了 N 条命令」
 * 这类计数才能与实际执行数对齐，不会出现「说是 6 条、实际只有 4 条」。
 */
enum class ActivityCategory(
    /** 汇总行里的动词短语，如「运行了 N 条命令」里的「运行了…条命令」。 */
    val unitLabel: String,
    /** 详情弹层的标题（如「运行命令」）。 */
    val detailTitle: String,
) {
    THINK("思考", "思考"),
    RUN_COMMAND("条命令", "运行命令"),
    READ_FILE("个文件", "读取文件"),
    WRITE_FILE("个文件", "写入文件"),
    EDIT_FILE("个文件", "编辑文件"),
    SEARCH_FILE("次文件", "搜索文件"),
    WEB_SEARCH("次网页", "搜索网页"),
    WEB_FETCH("个网页", "读取网页"),
    BROWSER("次浏览器", "操作浏览器"),
    MEMORY("次记忆", "读写记忆"),
    COMPUTER("次电脑", "操作电脑"),
    PHONE("次手机", "操作手机"),
    SCHEDULE("次日程", "管理日程"),
    CALENDAR("次日历", "读写日历"),
    AGENT("次智能体", "调用智能体"),
    TOOL("个工具", "调用工具"),
    ;

    companion object {
        /**
         * 工具名 → 类别。
         *
         * 归一化规则：先小写，再过一张**别名表**
         * （`read_file`→`read`、`write_file`→`write`、`edit_file`→`edit`…），
         * 最后按前缀/包含关系落到某个类别。
         *
         * 别名表存在的意义：同一个动作在不同厂商的 MCP 工具集里叫法不同，
         * 但它们在汇总行里应该算作**同一类**，否则「运行了 N 条命令」会随工具名漂移。
         */
        private val ALIASES: Map<String, String> = mapOf(
            "read_file" to "read",
            "file_read" to "read",
            "read_text_file" to "read",
            "write_file" to "write",
            "file_write" to "write",
            "edit_file" to "edit",
            "file_edit" to "edit",
            "str_replace_based_edit" to "edit",
            "search_knowledge_base" to "search",
            "grep" to "search",
            "glob" to "search",
            "find_files" to "search",
            "shell" to "command",
            "shell_output" to "command",
            "shell_stop" to "command",
            "exec_command" to "command",
            "command" to "command",
            "terminal" to "command",
            "termux" to "command",
            "quroterm_exec" to "command",
            "run_code" to "command",
            "code_runner" to "command",
            "web_search" to "web_search",
            "websearch" to "web_search",
            "read_url" to "web_fetch",
            "web_fetch" to "web_fetch",
            "open_web" to "web_fetch",
            "http_request" to "web_fetch",
            "ai_browser" to "browser",
            "browser" to "browser",
            "memory_save" to "memory",
            "memory_list" to "memory",
            "memory_search" to "memory",
            "memory_delete" to "memory",
            "memory_space" to "memory",
            "schedule_task" to "schedule",
            "list_scheduled_tasks" to "schedule",
            "delete_scheduled_task" to "schedule",
            "read_calendar" to "calendar",
            "write_calendar" to "calendar",
            "agent" to "agent",
            "mcp_tool" to "tool",
        )

        /** 工具名归一：小写 + 别名映射（未命中别名则原样返回归一小写名）。 */
        fun normalizeToolName(raw: String?): String {
            val n = raw?.trim()?.lowercase(Locale.ROOT).orEmpty()
            if (n.isEmpty()) return ""
            return ALIASES[n] ?: n
        }

        /** 工具名 → 活动类别。未知工具一律归 [TOOL]（「调用了 N 个工具」）。 */
        fun ofTool(toolName: String?): ActivityCategory {
            val n = normalizeToolName(toolName)
            return when {
                n.isEmpty() -> TOOL
                n == "command" -> RUN_COMMAND
                n == "read" -> READ_FILE
                n == "write" -> WRITE_FILE
                n == "edit" -> EDIT_FILE
                n == "search" -> SEARCH_FILE
                n == "web_search" -> WEB_SEARCH
                n == "web_fetch" -> WEB_FETCH
                n == "browser" -> BROWSER
                n == "memory" -> MEMORY
                n == "agent" -> AGENT
                n == "schedule" -> SCHEDULE
                n == "calendar" -> CALENDAR
                n == "phone" -> PHONE
                n == "computer" -> COMPUTER
                n.startsWith("computer_") -> COMPUTER
                n.startsWith("browser_") -> BROWSER
                n.startsWith("mcp__") -> TOOL
                else -> TOOL
            }
        }
    }
}

/**
 * 活动流里的一条明细。
 *
 * @param category 这条属于哪一类（决定图标与汇总计数）。
 * @param title 一句话描述（用户可读）。来源优先级：工具的 `display_intent` 参数
 *   → 按类别从参数里回退提取（命令/路径/关键词）→ 工具名本身。
 * @param toolName 原始工具名（详情弹层按它分派渲染分支）。
 * @param args 工具参数 JSON 原文。
 * @param result 工具返回原文。
 * @param durationMs 本次耗时（毫秒）。
 * @param failed 是否失败（失败行在活动流里显示红色）。
 */
data class ActivityEntry(
    val category: ActivityCategory,
    val title: String,
    val toolName: String,
    val args: String = "",
    val result: String? = null,
    val durationMs: Long = 0L,
    val failed: Boolean = false,
    /** 思考条目专用：这一段思考的耗时（毫秒）。 */
    val thinkDurationMs: Long = 0L,
) {
    /** 是否是「思考」条目（渲染成大脑图标 + 「思考了 N秒」）。 */
    val isThink: Boolean get() = category == ActivityCategory.THINK
}

/**
 * 工具名 → 用户可读描述的提取器（`display_intent` 机制）。
 *
 * ## 机制
 *
 * 给模型下发工具 schema 时，**额外注入一个 `display_intent` 参数**，
 * 参数说明写明：「一句面向用户的简短描述（6–30 字），说明这次调用的**意图**，
 * 不要复述原始参数、不要用『正在/完成』这类状态词」。
 * 模型每次调用工具时**自己写一句话**，UI 直接显示这句话。
 *
 * 显示文案与工具内部实现解耦：工具叫 `quroterm_exec` 也能显示
 * 「探测本地 MCP 端点」而不是 `quroterm_exec`。
 *
 * ## 实现
 *
 * 1. **优先**取参数里的 `display_intent`（模型写了就用模型的）。
 * 2. 没有则**按类别回退**：从参数里提取最有信息量的那个值
 *    （命令 → 命令行；写/读文件 → 路径；搜索 → 关键词），拼成一句中文。
 * 3. 再没有就用工具名。
 *
 * 这样即使模型没写 `display_intent`，活动流也不会退化成一堆英文工具名。
 */
object ActivityTitleResolver {

    /** 参数里可能的「意图」键名（大小写不敏感）。 */
    private val INTENT_KEYS = listOf("display_intent", "intent", "description", "summary", "purpose")

    /** 从参数 JSON 里取 `display_intent`（或同义键）。取不到返回 null。 */
    fun extractIntent(args: String?): String? {
        val raw = args?.trim().orEmpty()
        if (raw.isEmpty() || !raw.startsWith("{")) return null
        return try {
            val o = JSONObject(raw)
            for (k in INTENT_KEYS) {
                val v = o.optString(k, "").trim()
                if (v.isNotEmpty() && v.length <= 200) return v
            }
            null
        } catch (_: Throwable) {
            null
        }
    }

    /** 按类别从参数里回退提取一个「最有信息量的值」。 */
    private fun fallbackValue(category: ActivityCategory, args: String?): String? {
        val raw = args?.trim().orEmpty()
        if (raw.isEmpty() || !raw.startsWith("{")) return null
        val o = try { JSONObject(raw) } catch (_: Throwable) { return null }
        fun s(vararg keys: String): String? {
            for (k in keys) {
                val v = o.optString(k, "").trim()
                if (v.isNotEmpty()) return v
            }
            return null
        }
        return when (category) {
            ActivityCategory.RUN_COMMAND ->
                s("command", "cmd", "script", "commandLine", "code", "input")
            ActivityCategory.WRITE_FILE, ActivityCategory.EDIT_FILE, ActivityCategory.READ_FILE ->
                s("path", "file_path", "filePath", "filename", "file")
            ActivityCategory.SEARCH_FILE ->
                s("pattern", "query", "keyword", "q", "glob")
            ActivityCategory.WEB_SEARCH ->
                s("query", "keyword", "q")
            ActivityCategory.WEB_FETCH ->
                s("url", "link")
            ActivityCategory.MEMORY ->
                s("content", "text", "note")
            else -> null
        }
    }

    /**
     * 生成这条活动流条目的标题。
     *
     * @param toolName 工具名（未命中任何已知类别时也会作为最终回退）。
     */
    fun resolve(category: ActivityCategory, toolName: String, args: String?): String {
        extractIntent(args)?.let { return it }
        fallbackValue(category, args)?.let { v ->
            val short = if (v.length > 120) v.take(117) + "…" else v
            return when (category) {
                ActivityCategory.RUN_COMMAND -> "运行命令：$short"
                ActivityCategory.WRITE_FILE -> "写入 $short"
                ActivityCategory.EDIT_FILE -> "编辑 $short"
                ActivityCategory.READ_FILE -> "读取 $short"
                ActivityCategory.SEARCH_FILE -> "搜索 $short"
                ActivityCategory.WEB_SEARCH -> "搜索 $short"
                ActivityCategory.WEB_FETCH -> "读取 $short"
                else -> short
            }
        }
        return category.detailTitle
    }
}
