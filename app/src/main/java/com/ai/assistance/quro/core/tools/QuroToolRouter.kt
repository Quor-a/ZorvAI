package com.ai.assistance.quro.core.tools

import android.content.Context
import com.ai.assistance.quro.core.QuroToolSpec
import com.ai.assistance.quro.core.rag.AgentRag
import com.ai.assistance.quro.core.rag.PromptRagIndex
import com.ai.assistance.quro.core.rag.ToolRagIndex

/**
     * 渐进式工具披露（progressive tool disclosure / tool router / RAG 式按需检索）。
 *
 * 解决痛点：此前每次请求都把【全部工具】的完整 JSON-Schema 塞进 `tools` 字段
 * （265 个 / 数十万字符），模型每轮都要扫全部；系统提示词又把同样的全量
 * 工具清单逐条列一遍（双份开销）。
 *
 * 新架构（对齐用户诉求「先路由、再看该看的、用时再加载、每个工具有自己的提示词」）：
 * 1. 每轮只下发一个紧凑【工具路由目录】(tool_router) + 常驻核心集(alwaysOn) + 已加载集(loaded)。
 * 2. 模型先用 tool_router 检索：match_intent / list_categories / list_tools / get_schema。
 *    检索质量由 [ToolCapabilityDirectory.matchToolsByIntent] 负责（中英分词 + 多字段加权评分）。
 * 3. 调 get_schema(name) 时，返回该工具【完整参数 Schema + 专属使用提示词】（每个工具自己的
 *    useCases/examples/tips/relatedTools，分类好的），并把它标记为「已加载」——
 *    下一轮起该工具的【真实可执行 schema】进入 `tools` 字段，模型即可直接调用。
 * 4. 已加载集跨轮次保留，并**落盘持久化**（见 [persist]），进程被杀/重启不丢。
 *
 * ## 为什么现在默认开启
 * 旧注释写过「默认 false，因为小模型不查就直接说不会」——那个顾虑的方向是对的，
 * 但当时检索层本身是坏的（实测召回 2/14=14%，且排第一的恒是噪声条目 `cms_toolbox`），
 * 模型就算主动查 `match_intent` 也拿不到正确工具，于是「查不到」被误判成「模型不查」。
 * 检索层重写后召回 14/14=100%，前提才成立：
 *  - 模型查得到 → 弱模型也能靠 `match_intent` 走通；
 *  - `loaded` 落盘 → 重启后不必重新学一遍；
 *  - [ALWAYS_ON] 43 个核心工具每轮直接下发；其余约 255 个全部走 [tool_router] 分类检索。
 *
 * ## 🔴 常驻集为什么必须小（2026-11-02 重构）
 *
 * 常驻集的大小直接等于**每轮请求的固定输入开销**：每个常驻工具都要把
 * name + description + parametersJson 全量塞进 API 的 tools 字段。
 * 它一度膨胀到 154 个 —— 那不是因为「这 154 个都高频」，而是因为**分类架构没做好**：
 * 四处规则手写、两份实现靠注释人工同步、谁也没有全量覆盖约定，
 * 工具分不出类 → `tool_router` 按分类列不出来 → 只好靠常驻硬塞来保证「至少能看见」。
 *
 * 现在的判据只有两条，缺一即进核心集：
 *   ① 用户最高频的口语直接触发（现在几点 / 打开微信 / 记一下 / 搜一下）；
 *   ② 缺了它整条链路就断（没有 read_screen 就没法操作屏幕，没有 bg_screen 就没法用后台屏）。
 * 其余一律交给分类检索 —— 检索层已重写为分词 + 多字段加权，实测召回 100%，
 * 且 [catalogSpec] 里明确写了「认为自己没有某个能力时，必须先查一遍再说」。
 *
 * ⚠️ 新增工具时**不要往这里加**，先去 [ToolTaxonomy] 给它一条正确的分类规则；
 *    只有确实满足上面 ① 或 ② 才进核心集。
 * 若将来观察到弱模型「明明有工具却说不会」，**先查检索质量与 ALWAYS_ON 覆盖**，
 * 不要一上来就关掉这个开关——那会退回全量下发，也就退回了本轮要解决的问题。
 */
class QuroToolRouter(
    allSpecs: List<QuroToolSpec>,
    /**
     * 仅用于 [persist] / [restore] 读写 SharedPreferences。
     * 走构造参数而非可变字段：同一 router 会被多轮/多线程复用，
     * 可变 Context 字段在并发下会被后到的调用覆盖。
     * 纯 JVM 单测可传 null——此时不落盘，检索与下发逻辑不受影响。
     */
    private val appContextRef: Context? = null,
) {

    companion object {
        /**
         * 渐进式披露总开关。**默认 true（RAG 式按需检索）**：只下发
         * 【路由目录 + 常驻核心 + 已加载】，其余工具经 `tool_router` 检索后加载。
         *
         * 🔴 为什么默认可以开（旧注释说 false，理由已不成立）：
         * 当时检索层本身是坏的——[matchToolsByIntent] 只做整句包含，14 条普通中文口语
         * 只召回 2 条且榜首恒为噪声 `cms_toolbox`，模型主动查也查不到，于是表现为
         * 「小模型不查就说自己不会」。检索层重写后召回 100%，前提才成立。
         * 若将来又观察到「明明装了却说自己不会」，**先查检索召回与 [ALWAYS_ON] 覆盖**，
         * 别直接关这个开关（那会退回每轮全量下发，正是本轮要消除的开销）。
         *
         * 剩余已知代价：[loaded] 集合挂在 [com.ai.assistance.quro.core.QuroAssistant] 的
         * toolRouters 上；现已通过 [persist] 落盘，进程被杀不再丢（见 [persist] / [restore]）。
         */
        @Volatile var PROGRESSIVE: Boolean = true

        /**
         * 常驻核心集：高频、用户口语最常触发、依赖链最短的工具每轮都直接下发，
         * 模型无需先 discovery 即可调用（对应「AI 百分百先看哪些」）。
         * 其余工具经 tool_router 按需加载。
         */
        val ALWAYS_ON: Set<String> = setOf(
            "get_current_time", "get_device_info", "calculate",
            "get_battery", "list_installed_apps", "launch_app",
            "search_and_launch_app", "list_files", "read_text_file",
            "write_file", "find_files", "file_info",
            "browse_files", "http_request", "open_web",
            "web_search", "read_url", "read_sms",
            "send_sms", "read_contacts", "read_calendar",
            "write_calendar", "set_alarm", "terminal",
            "termux", "quroterm_exec", "shizuku_exec",
            "memory_save", "memory_search", "rag_search",
            "speak", "stop_speak", "read_screen",
            "tap_screen", "input_text", "get_foreground_app",
            "bg_screen", "ui_card", "ui_widget",
            "ui_control", "zx_web_card", "visual_question",
            // 确定性出图三件套：用户历史上明确反馈「新加的代码生图 AI 根本查不到」，
            // 保留在常驻里（不靠检索），避免同类问题复发。
            "codecanvas_probe", "codecanvas_script", "codecanvas_markup",
            "spawn_subagent",
        )

        private val CATALOG_PARAMS_JSON = """{
  "type": "object",
  "properties": {
    "action": {
      "type": "string",
      "description": "操作类型",
      "enum": ["list_categories", "list_tools", "match_intent", "match_everything", "get_schema", "get_best_practices", "get_directory_summary"]
    },
    "category": { "type": "string", "description": "分类名（list_tools 使用）" },
    "intent": { "type": "string", "description": "用户意图描述（match_intent 使用）" },
    "name": { "type": "string", "description": "工具名称（get_schema 使用）" }
  },
  "required": ["action"]
}"""

        /** [persist] / [restore] 的 SharedPreferences 键。 */
        private const val PREFS = "quro_tool_router"
        private const val KEY_LOADED = "loaded_tool_names"
    }

    /** 全部可用工具规格（每次 ask 刷新，保留 loaded 状态）。 */
    private var allSpecs: List<QuroToolSpec> = allSpecs
    private var specByName: Map<String, QuroToolSpec> = allSpecs.associateBy { it.name }

    init {
        // 工具能力目录以本 router 的真实 specs 为单一真相源，确保 match_intent / get_schema 查到的目录
        // 与模型实际能调的工具集严格一致（修复「目录残缺→很多功能得提醒才用」）。
        ToolCapabilityDirectory.install(allSpecs)
        // RAG 索引与目录同源刷新：install 内部会先 clearDomain，不清会累积同名旧文档。
        AgentRag.refresh(allSpecs)
        // 恢复上次进程留下的「已加载」集合：用户不必重启后重新学一遍工具参数。
        restore()
    }

    /** 对话级已加载工具（跨轮次保留，并落盘持久化）。 */
    private val loaded = LinkedHashSet<String>()

    fun setSpecs(specs: List<QuroToolSpec>) {
        allSpecs = specs
        specByName = specs.associateBy { it.name }
        ToolCapabilityDirectory.install(specs)
        // 注册表动态变化（插件装卸、技能增删）后必须同步刷新 RAG 索引，
        // 否则会检索到已卸载的工具——模型照着调必然报未知工具，且比「查不到」更难排查。
        AgentRag.refresh(specs)
        // 注册表刷新后，剔除已不存在的工具名（插件卸载 / 技能删除会导致集合缩小），
        // 否则 activeSpecs 会把 specByName 里查不到的名字也算进「已加载」，
        // 而 catalogSpec 又会把它们列进「当前已加载」，模型照着调却必然报未知工具。
        loaded.retainAll(specByName.keys)
    }

    /**
     * 当前真实可调用工具全集的只读快照。
     *
     * 供 RAG 索引构建与覆盖率核对使用。**只读**：返回的是不可变 List 的新引用，
     * 外部改不动 router 内部状态。
     */
    fun allSpecsSnapshot(): List<QuroToolSpec> = allSpecs

    fun reset() {
        loaded.clear()
        persist()
    }

    /**
     * 把已加载集合落盘（SharedPreferences）。
     *
     * ## 为什么必须落盘
     * 旧实现里 `loaded` 只在内存，而 router 实例挂在
     * [com.ai.assistance.quro.core.QuroAssistant] 的 `toolRouters` map 上。
     * 进程被杀 / App 被系统回收后 map 清空，用户感知是「同样的问题这次会了、下次又不会了」，
     * 要重新走一遍 `tool_router.get_schema` 才知道怎么调——这正是渐进式披露最大的体验风险。
     *
     * ## 为什么不做「会话级隔离」
     * 已加载的是**工具使用知识**（这个工具的参数长什么样），不是对话状态。
     * 跨会话复用是安全的，也更符合用户预期。真正需要清空的是 [reset]（用户显式清上下文）。
     */
    private fun persist() {
        runCatching {
            val ctx = appContextRef ?: return
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_LOADED, loaded.joinToString(","))
                .apply()
        }
    }

    /** 从落盘状态恢复已加载集合（工具名已不存在的会被丢弃）。 */
    fun restore() {
        runCatching {
            val ctx = appContextRef ?: return
            val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_LOADED, "").orEmpty()
            if (raw.isBlank()) return
            val names = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            val valid = names.filter { it in specByName }
            if (valid.size != names.size) {
                // 有名字对不上当前注册表（工具被移除/改名），落盘回写清理后的结果，
                // 避免每次启动都做一遍无用功，也避免 catalogSpec 展示过期名单。
                loaded.clear(); loaded.addAll(valid); persist()
            } else {
                loaded.addAll(valid)
            }
        }
    }

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
            // 跨域检索：工具 + 系统提示词规则 + UI 组件 + 界面交付路径一次给。
            // 与 match_intent 的分工：那个只答「哪个工具」，这个答「我该走哪条路」。
            "match_everything" -> matchEverything(args.optString("intent"))
            "get_schema" -> getSchema(args.optString("name"))
            "get_best_practices" -> ToolCapabilityDirectory.buildBestPractices()
            "get_directory_summary" -> buildCompactIndex()
            else -> "未知操作：$action（支持 list_categories / list_tools / match_intent / match_everything / get_schema / get_best_practices / get_directory_summary）"
        }
    }

    // ───────────────────────── tool_router 目录工具 ─────────────────────────

    private fun catalogSpec(): QuroToolSpec {
        val desc = buildString {
            appendLine("# 工具路由目录（RAG 式按需检索，禁止瞎猜工具名）")
            appendLine("你拥有大量工具，但每轮只下发【已加载】工具的【真实可执行 schema】。先用本工具检索，再调用。")
            appendLine()
            appendLine("## 🔴 强制工作流（不确定就查，不要凭猜）")
            appendLine("1. 收到用户需求 → 先 `match_intent(intent=...)` 用**用户的原话**检索（最常用，一步到位）。")
            appendLine("2. 结果里挑最合适的 → `get_schema(name=...)` 拿到完整参数与用法，**下一轮起即可直接调用**。")
            appendLine("3. `match_intent` 没给出结果 → `list_categories()` 看有哪些类，再 `list_tools(category=...)`。")
            appendLine("4. 你已知道工具名 → 直接 `get_schema(name=...)`，不必先 match_intent。")
            appendLine("⚠️ 只要你认为自己没有某个能力（哪怕从没听说过这个工具），**必须先查一遍再说**。")
            appendLine("   下面的分类清单里可能就有它；绝大多数工具不会自动出现在你的 tools 字段里。")
            appendLine()
            appendLine("## 可用操作")
            appendLine("- `match_intent(intent=\"用户需求描述\")`：按意图匹配工具。**首选**。")
            appendLine("- `get_schema(name=\"工具名\")`：返回完整参数 JSON Schema + 专属用法，并加载它。")
            appendLine("- `list_categories()`：列出所有工具分类。")
            appendLine("- `list_tools(category=\"分类名\")`：查看某分类下的工具及其说明。")
            appendLine("- `get_directory_summary()`：全部工具 name + 说明（较长，只在确实需要时调用）。")
            appendLine()
            appendLine("## 当前已加载（可直接调用，无需再查）：${if (loaded.isEmpty()) "（暂无可调，先用 match_intent 检索你需要的）" else loaded.joinToString(", ")}")
            appendLine()
            appendLine("## 工具分类清单（共 ${allSpecs.size} 个工具）—— 只有名字，用 match_intent / list_tools 取说明：")
            appendLine(buildCatalogBrief())
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
        persist() // 立即落盘：进程被杀后不必重新 get_schema 一次
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

        // 旧路径保持原样：仍是目录自带的检索，接住新同义表未覆盖的长尾说法。
        val legacy = ToolCapabilityDirectory.matchToolsByIntent(intent).filter { it.name in names }
        // 新路径：RagEngine 多路融合，接住错别字 / 拼音缩写 / 换一种说法。
        // 两路合并去重，**新引擎结果在前**——旧引擎零命中时是按 priority 瞎猜前 12 个，
        // 那种结果排在新引擎真命中前面会直接把对的答案挤下去。
        val hits = ToolRagIndex.search(AgentRag.engine, intent, legacy)
            .filter { it.id in names }

        if (hits.isEmpty()) {
            return "未找到匹配「$intent」的工具。可用分类见 list_categories()；" +
                "或 get_schema(name=...) 直接加载你知道名字的工具。"
        }
        return buildString {
            appendLine("## 意图匹配：「$intent」")
            hits.forEachIndexed { i, h ->
                val desc = h.doc.description.ifBlank { h.doc.title }
                appendLine("${i + 1}. **${h.id}**（${h.doc.title.ifBlank { "通用" }}）：$desc")
            }
            appendLine()
            appendLine("调用 get_schema(name=工具名) 加载并查看完整参数后使用。")
        }
    }

    /**
     * 跨域检索（工具 / 规则段 / 动态UI组件 / 界面交付路径）。
     *
     * ## 为什么 tool_router 也要有这条路（而不是只让模型去调独立的 rag_search）
     * 模型遇到「这个趋势用什么画」「出海报该用哪个」时，问的**不是工具名**，
     * 而是一条决策。旧 [matchIntent] 只能返回工具，于是这类问题恒定零命中，
     * 模型只能凭记忆瞎猜——用户看到的现象就是「明明有这功能，它却说不会」。
     *
     * 旧行为一字未改：[matchIntent] 仍是原实现，本函数是**新增的并列分支**。
     */
    private fun matchEverything(intent: String): String {
        if (intent.isBlank()) {
            return "请提供用户意图，例如：match_everything(intent=\"把这个趋势画出来\")"
        }
        if (AgentRag.total() <= 0) AgentRag.refresh(allSpecs)
        val names = allSpecs.map { it.name }.toSet()
        val hits = AgentRag.everything(intent, limit = 12)
            .filter { h -> h.domain != ToolRagIndex.DOMAIN || h.id in names }
        if (hits.isEmpty()) {
            return "跨域检索「$intent」无结果。工具域可试 match_intent，界面组件可试 rag_search(domain=\"components\")。"
        }
        return buildString {
            appendLine("## 跨域检索：「$intent」（工具 / 规则 / 组件 / 交付路径，共 ${hits.size} 条）")
            hits.forEachIndexed { i, h ->
                val kind = when (h.domain) {
                    ToolRagIndex.DOMAIN -> "工具"
                    PromptRagIndex.DOMAIN -> "规则"
                    else -> h.domain
                }
                val desc = h.doc.description.ifBlank { h.doc.title }
                appendLine("${i + 1}. [$kind] **${h.nameOrId()}**：$desc")
            }
            appendLine()
            appendLine("工具类结果用 get_schema(name=...) 加载完整参数；组件类结果用 ui_dsl_spec / card_catalog 取规范与样例。")
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

    /**
     * 目录工具的**短描述**（真正每轮下发给模型的那份）。
     *
     * ## 🔴 为什么不能把 [buildCompactIndex] 塞进 description
     * 实测（265 个工具时）：完整索引 = **66,838 字符**，占整个 tools 字段 145,472 字符的
     * 46%。而 tools 字段其余部分是 ALWAYS_ON 的 146 个工具。
     * 结果：RAG 模式 140,130 字符 vs 全量 145,472 —— **只省 3.7%**，等于白改。
     * 把全量清单换个地方塞进来，并没有让它「变短」，只是从 `tools[]` 挪到了某一个 description 里。
     *
     * ## 现在的形态
     * 只给「分类清单 + 检索入口」，让模型知道**有什么类别**、以及**怎么查**；
     * 具体有哪些工具由 `match_intent` / `list_tools` / `get_directory_summary` 按需取回。
     * 这样每轮固定开销是常数级（几十行），与工具总数无关。
     */
    private fun buildCatalogBrief(): String {
        val byCat = allSpecs.groupBy { categorize(it.name)?.displayName ?: "其他工具" }
        return buildString {
            byCat.toSortedMap().forEach { (cat, tools) ->
                appendLine("- **$cat**（${tools.size} 个）：${tools.sortedBy { it.name }.joinToString("、") { it.name }}")
            }
        }
    }

    // ───────────────────────── 分类推断（目录里没有的工具按命名归类） ─────────────────────────

    private fun categorize(name: String): ToolCapabilityDirectory.ToolCategory? {
        // 🔴 2026-11-02 重构：改调 ToolTaxonomy（单一真相源）。
        // 原来这里是与 ToolCapabilityDirectory.inferCategory **几乎逐行重复**的手写 when，
        // 两边靠注释「必须同源」人工同步，已经漂移（cms_* 一边 CMS_DEVELOPMENT 一边 AI_CAPABILITIES）。
        // 更要命的是本函数末尾是 `else -> null`：没被覆盖的工具**直接从路由索引消失**，
        // tool_router 的 list_tools / match_intent 永远查不到它们（实测 100/239 落这个坑）。
        // 现在由 ToolTaxonomy 兜底保证一定有分类，不再有「工具存在但查不到」的情况。
        return ToolTaxonomy.categoryOf(name)
    }
}
