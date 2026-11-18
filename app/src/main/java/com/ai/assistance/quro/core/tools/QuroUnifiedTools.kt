package com.ai.assistance.quro.core.tools

import android.content.Context
import org.json.JSONObject

/**
 * 合体后的工具名单，同时是 coreSpecs 与路由常驻集的白名单来源。
 * 名单只在这里写一次，避免「新增合体工具忘了补白名单 → 模型看不到」反复发生。
 */
object QuroUnifiedToolNames {
    val ALL: Set<String> = setOf(
        "web", "mcp", "linux_env", "wf", "cms", "auth_service", "workspace",
        // 2026-11-11 第二批：8 个「同一功能被拆成多个工具调用」的族收口。
        "shizuku", "terminal", "keyboard", "aci", "root", "screen", "permissions", "memory",
    )

    /**
     * `terminal` 本次从 10 个会话动作扩展到「会话 + 命令执行」全族后吸收的散装入口。
     * 它们由 QuroTerminalTool 内部直接持有实例转发，不需要分派器，但同样要降级不再下发。
     */
    val TERMINAL_ABSORBED: Set<String> = setOf(
        "quroterm_exec", "adb_term", "tmux", "terminal_keys", "term_monitor",
    )

    /**
     * 更早一批合体工具吸收掉的散装入口。
     *
     * 注册清单仍会注册它们（旧名要能按原名执行，历史会话与审批策略才不断裂），
     * 这里统一在收口阶段降级为内部实现，避免「注册顺序一变旧名又下发给模型」。
     * workspace_write/read/list 是 2026-11-06 被 file_* 吸收后**连实现都没注册**的三个名字，
     * 本次补回实现再降级：历史会话与旧提示词按旧名发起的调用不会再落到「未知工具」。
     */
    val RETIRED: Set<String> = setOf(
        "toggle_flashlight", "list_scheduled_tasks", "delete_scheduled_task",
        "workspace_write", "workspace_read", "workspace_list",
    )
}

/**
 * 通用合体分派器：一个工具名 + 一个 action 参数，内部按 action 转给原来的实现。
 *
 * 被合并的旧工具由 [QuroToolRegistry.installUnified] 降级为内部实现：不再下发模型，
 * 但按原名仍可执行、参数格式一字不变，历史会话与工作流内部调用不会断。
 * 一个 action 可以对应一串实现（按序执行、结果拼接），用于把「先探活再执行」
 * 这类原本要两次调用的流程压成一次调用。
 */
class QuroUnifiedDispatchTool(
    override val name: String,
    override val description: String,
    override val parametersJson: String,
    private val registry: QuroToolRegistry,
    /**
     * action → 依次执行的旧实现名。
     *
     * 用 `internal` 而不是 `private`：`UnifiedToolingContractTest` 要逐条核对
     * 「schema 里的每个 action 都有真实落点、每个落点都真的注册过」。
     * 这类契约只能靠测试守，靠人读代码守不住（aci 的 capability 重复键就是这么漏出去的）。
     */
    internal val routes: Map<String, List<String>>,
    /** 未显式给 action 时按参数推断。 */
    private val defaultAction: (JSONObject) -> String,
    /** 需要自行编排多个实现的 action；返回 null 表示交回 routes 处理。 */
    private val composite: ((String, JSONObject, (String, String) -> String) -> String?)? = null,
    /** 复合 action 额外会调用的旧实现名，供引擎按真实目标计算治理与超时预算。 */
    private val extraGovernance: (String, JSONObject) -> List<String> = { _, _ -> emptyList() },
    /**
     * 转发给旧实现前是否剥掉外层 action。
     * 部分旧实现自己也用 action 参数（录屏 start/stop、后台屏 open/read、知识库 add/search…），
     * 外层 action 不剥会被旧实现当成自己的动作，轻则走错分支、重则直接报错。
     */
    private val stripAction: Boolean = false,
    /** 外层 action → 转发给旧实现时要写入的 action 值；未列出则完全不带 action。 */
    internal val innerActions: Map<String, String> = emptyMap(),
    /** 转发前把外层参数名改写成旧实现消费的名字（同名不同型冲突时的桥接，如 aci 的 capability_def → capability）。 */
    internal val renameArgs: Map<String, String> = emptyMap(),
) : QuroTool, QuroToolDelegation {

    override fun governedCalls(arguments: String): List<Pair<String, String>> {
        val args = runCatching { JSONObject(arguments.ifBlank { "{}" }) }.getOrElse { JSONObject() }
        val action = args.optString("action").ifBlank { defaultAction(args) }.trim().lowercase()
        // 不去重：复合 action 会串行/并发读多条正文，预算要按真实次数累加。
        return (routes[action].orEmpty() + extraGovernance(action, args))
            .filter { it.isNotBlank() }
            .map { it to arguments }
    }

    override fun run(context: Context, arguments: String): String {
        val args = JSONObject(arguments.ifBlank { "{}" })
        val action = args.optString("action").ifBlank { defaultAction(args) }.trim().lowercase()
        // 失败必须真的失败：异常交给适配器转成 ToolResult.error，闭环才能重试/升级。
        // 早先把异常拼成一句「执行失败」文本返回，会被当成 status: ok 回灌给模型。
        val invoke: (String, String) -> String = { legacy, raw ->
            val tool = registry.legacy(legacy) ?: registry.get(legacy)
                ?: throw IllegalStateException("工具未就绪：$legacy")
            tool.run(context, raw)
        }
        composite?.invoke(action, args, invoke)?.let { return it }
        val chain = routes[action]
            ?: throw IllegalArgumentException(
                "未知操作「$action」。可用操作：${routes.keys.sorted().joinToString(" / ")}"
            )
        if (chain.isEmpty()) throw IllegalStateException("操作「$action」暂无可用实现")
        val forwarded = if (stripAction) {
            JSONObject(arguments.ifBlank { "{}" }).apply {
                remove("action")
                innerActions[action]?.let { v ->
                    // "@op" 表示内层动作由调用方通过 op 参数指定（如设备开关的 up/down/on/off）。
                    val value = if (v.startsWith("@")) args.optString(v.removePrefix("@")) else v
                    if (value.isNotBlank()) put("action", value)
                }
                // 同名不同型的参数桥接：外层 schema 与旧实现参数名不一致时按映射改名。
                renameArgs.forEach { (from, to) ->
                    if (has(from)) { put(to, get(from)); remove(from) }
                }
            }.toString()
        } else arguments
        return chain.map { invoke(it, forwarded) }.joinToString("\n\n")
    }
}

/**
 * 前置/后置包装器：把「查目录 / 拉规范 / 探活 / 导出产物」这类原本要单独调用一次的动作，
 * 降级为执行工具上的一个布尔参数。工具名保持不变，因此零兼容成本。
 *
 * 包装器与旧实现同名注册，其余接口成员委托给旧实现，只改写说明、schema 与执行入口。
 */
class QuroPreflightTool(
    private val delegate: QuroTool,
    private val registry: QuroToolRegistry,
    private val before: List<Preflight>,
    private val after: List<Preflight>,
) : QuroTool by delegate, QuroToolDelegation {

    data class Preflight(val param: String, val legacyName: String, val label: String)

    override fun governedCalls(arguments: String): List<Pair<String, String>> {
        val args = runCatching { JSONObject(arguments.ifBlank { "{}" }) }.getOrElse { JSONObject() }
        val enabled = (before + after).filter { args.optBoolean(it.param, false) }.map { it.legacyName }
        return (listOf(delegate.name) + enabled).map { it to arguments }
    }

    override val description: String
        get() = delegate.description +
            "\n\n可一次调用内自动完成（无需再单独调用其它工具）：" +
            (before + after).joinToString("；") { "${it.param}=true → ${it.label}" }

    override val parametersJson: String
        get() = runCatching {
            val schema = JSONObject(delegate.parametersJson)
            val props = schema.optJSONObject("properties") ?: JSONObject().also { schema.put("properties", it) }
            (before + after).forEach {
                props.put(
                    it.param,
                    JSONObject().put("type", "boolean").put("description", it.label),
                )
            }
            schema.toString()
        }.getOrElse { delegate.parametersJson }

    override fun run(context: Context, arguments: String): String {
        val args = runCatching { JSONObject(arguments) }.getOrElse { JSONObject() }
        val invoke: (Preflight) -> String = { p ->
            val tool = registry.legacy(p.legacyName) ?: registry.get(p.legacyName)
            runCatching { "【${p.label}】\n" + (tool?.run(context, arguments) ?: "工具未就绪：${p.legacyName}") }
                .getOrElse { "【${p.label}】执行失败：${it.message}" }
        }
        val parts = ArrayList<String>(before.size + after.size + 1)
        before.filter { args.optBoolean(it.param, false) }.forEach { parts += invoke(it) }
        parts += delegate.run(context, arguments)
        after.filter { args.optBoolean(it.param, false) }.forEach { parts += invoke(it) }
        return if (parts.size == 1) parts[0] else parts.joinToString("\n\n")
    }
}

/**
 * capability 归一：把「能力 id」从各种历史格式里挖出来（三种形态见
 * [com.ai.assistance.quro.core.aidlaci.normalizeAciCapabilityId]）。
 * 调用方集中在这里，保证统一分派器与 aci_call 旧实现用同一份解析逻辑。
 */
private fun normalizeAciCapability(args: JSONObject): String =
    com.ai.assistance.quro.core.aidlaci.normalizeAciCapabilityId(args.opt("capability"))

/**
 * 一次性把「本来是一个动作、却注册成多个工具」的入口收拢。
 *
 * 调用时机：注册清单全部 register 完毕、生成能力目录**之前**。
 * 顺序很重要——能力目录与 RAG 索引取的是调用瞬间的 fullSpecs 快照。
 */
fun QuroToolRegistry.applyUnifiedTools() {
    // 只保留指定键，避免合体层的 action/op/type 等参数污染旧实现。
    fun pick(src: JSONObject, vararg keys: String): JSONObject {
        val o = JSONObject()
        keys.forEach { k -> if (src.has(k)) src.opt(k)?.let { o.put(k, it) } }
        return o
    }

    // 早一批合体只换了名字、没保留实现：workspace_write/read/list 从未注册过，
    // 但系统提示词与 ACI / 构建台说明仍在要求模型调它们，结果必然落到「未知工具」。
    // 补回实现再降级到 legacy：按旧名仍能真实执行（含审批/权限/超时），但不下发给模型。
    register(WorkspaceWriteTool())
    register(WorkspaceReadTool())
    register(WorkspaceListTool())

    // 工作区：读写列目录、建文档、渲染预览、打开文档、播媒体，全部收成一个入口。
    // 旧的「写文件 → 再渲染预览」「读 → 改 → 写 → 预览」被拆成 2~4 次调用，
    // 现在 doc / write_and_render 一次调用就出预览卡。
    installUnified(
        QuroUnifiedDispatchTool(
            name = "workspace",
            description = """工作区（工具箱-工作区）的统一入口，一次调用完成一个工作区动作。
- action=write：写文件到工作区（path/content/append），等价于 file_write(root="workspace")。
- action=read：读工作区文件（path/offset/maxBytes），等价于 file_read(root="workspace")。
- action=list：列工作区目录（path 可选），等价于 list_dir(root="workspace")。
- action=doc：创建文档并**自带预览**（path/content/type；type 不填按扩展名推断，自动补扩展名），一次调用顶原来的「创建 + 渲染」两步。
- action=render：把工作区已有文件渲染成预览卡（path/title）。
- action=write_and_render：写文件后立刻渲染预览，一次调用完成原来「写入 + 渲染」两次调用。
- action=doc_view：用系统应用打开工作区文档（path，支持 PDF / Office / TXT / MD）。
- action=media：播放/控制工作区媒体（path/title/op=play_music|play_video|pause|stop）。
不带 action 时：有 op 视为 media、有 content 视为 write、有 path 视为 read，否则 list。""",
            parametersJson = """{
  "type":"object",
  "properties":{
    "action":{"type":"string","enum":["write","read","list","doc","render","write_and_render","doc_view","media"],"description":"工作区动作；不给时按参数推断"},
    "path":{"type":"string","description":"工作区内相对路径"},
    "content":{"type":"string","description":"write / doc / write_and_render 的文件内容"},
    "append":{"type":"boolean","description":"write 时是否追加，默认覆盖"},
    "type":{"type":"string","enum":["html","md","txt","json","js","py","java","kt","css"],"description":"doc 时的文档类型，不填按 path 扩展名推断"},
    "title":{"type":"string","description":"预览卡标题 / 媒体标题"},
    "op":{"type":"string","enum":["play_music","play_video","pause","stop"],"description":"media 的播放操作"},
    "offset":{"type":"integer","description":"read 的起始字节偏移"},
    "maxBytes":{"type":"integer","description":"read 的本段最多字节数"},
    "limit":{"type":"integer","description":"list 的返回条数"}
  }
}""",
            registry = this,
            routes = mapOf(
                "write" to listOf("file_write"),
                "read" to listOf("file_read"),
                "list" to listOf("list_dir"),
                "doc" to listOf("workspace_doc"),
                "render" to listOf("workspace_render"),
                "write_and_render" to listOf("file_write", "workspace_render"),
                "doc_view" to listOf("workspace_doc_view"),
                "media" to listOf("workspace_media"),
            ),
            defaultAction = { args ->
                when {
                    args.has("op") -> "media"
                    args.has("content") -> "write"
                    args.has("path") -> "read"
                    else -> "list"
                }
            },
            composite = { action, args, invoke ->
                when (action) {
                    "write" -> invoke(
                        "file_write",
                        pick(args, "path", "content", "append").put("root", "workspace").toString(),
                    )
                    "read" -> invoke(
                        "file_read",
                        pick(args, "path", "offset", "maxBytes").put("root", "workspace").toString(),
                    )
                    "list" -> invoke(
                        "list_dir",
                        pick(args, "path", "limit").put("root", "workspace").toString(),
                    )
                    "doc" -> invoke(
                        "workspace_doc",
                        pick(args, "path", "content", "type").toString(),
                    )
                    "render" -> invoke(
                        "workspace_render",
                        pick(args, "path", "title").toString(),
                    )
                    "doc_view" -> invoke(
                        "workspace_doc_view",
                        pick(args, "path").toString(),
                    )
                    "media" -> invoke(
                        "workspace_media",
                        pick(args, "path", "title")
                            .put("action", args.optString("op").ifBlank { "play_music" }).toString(),
                    )
                    "write_and_render" -> {
                        // 真合体：原来要「写一次 + 渲染一次」，这里一次调用串行完成，结果一起回灌。
                        val written = invoke(
                            "file_write",
                            pick(args, "path", "content", "append").put("root", "workspace").toString(),
                        )
                        val rendered = invoke(
                            "workspace_render",
                            pick(args, "path", "title").toString(),
                        )
                        "$written\n\n$rendered"
                    }
                    else -> null
                }
            },
        ),
        listOf("workspace_render", "workspace_doc", "workspace_media", "workspace_doc_view"),
    )

    // 联网检索：搜与精读是同一个编排器，search_and_read 一次调用完成「搜 + 读正文」。
    installUnified(
        QuroUnifiedDispatchTool(
            name = "web",
            description = """联网能力的统一入口。
- action=search：联网搜索，返回带编号 [n] 的资料片段与可溯源引用。涉及实时信息、新闻、版本、价格、需要核实的事实时必须调用。参数 query（自然语言原话）、可选 recency(day/week/month/any)。
- action=read：读取指定网页正文，返回清理后的 markdown。参数 url。
- action=search_and_read：**一次调用完成搜索 + 自动精读前 N 条结果**，无需再单独调一次读取。参数同 search，可选 top(1-8，默认 3)。不确定该读哪几条时优先用这个。
不带 action 时：有 url 且无 query 视为 read，其余视为 search。engine 可显式指定单一搜索引擎，默认 auto 走自研多引擎编排。""",
            parametersJson = """{
  "type":"object",
  "properties":{
    "action":{"type":"string","enum":["search","read","search_and_read"],"description":"操作类型；不给时按参数推断"},
    "query":{"type":"string","description":"用户的原始问题（search / search_and_read 必填）"},
    "recency":{"type":"string","enum":["day","week","month","any"],"description":"时效偏好"},
    "url":{"type":"string","description":"完整网页地址（read 必填，需 http(s) 开头）"},
    "top":{"type":"integer","description":"search_and_read 自动精读的结果条数，1-8，默认 3"},
    "engine":{"type":"string","description":"可选：显式指定单一搜索引擎；默认 auto"}
  }
}""",
            registry = this,
            routes = mapOf(
                "search" to listOf("web_search"),
                "read" to listOf("read_url"),
                "search_and_read" to listOf("web_search"),
            ),
            defaultAction = { if (it.has("url") && !it.has("query")) "read" else "search" },
            composite = { action, args, invoke ->
                if (action != "search_and_read") {
                    null
                } else {
                    val head = invoke("web_search", args.toString())
                    val top = args.optInt("top", 3).coerceIn(1, 8)
                    val urls = Regex("""—\s*(https?://\S+)""").findAll(head)
                        .map { it.groupValues[1].trim() }.distinct().take(top).toList()
                    if (urls.isEmpty()) head
                    else {
                        // 串行读 8 个网页必然撞 60s 硬超时；并发读让墙钟耗时约等于一次读取。
                        val pool = java.util.concurrent.Executors.newFixedThreadPool(top.coerceAtMost(4))
                        try {
                            val futures = urls.map { u ->
                                pool.submit(java.util.concurrent.Callable<String> {
                                    runCatching { invoke("read_url", JSONObject().put("url", u).toString()) }
                                        .getOrElse { "[正文读取失败：${it.message}]" }
                                })
                            }
                            buildString {
                                append(head).append("\n\n===== 已自动精读的正文 =====")
                                futures.forEachIndexed { i, f ->
                                    append("\n\n[正文 ").append(i + 1).append("] ").append(urls[i]).append('\n')
                                    append(
                                        runCatching { f.get(30, java.util.concurrent.TimeUnit.SECONDS) }
                                            .getOrElse { "[正文读取超时：${it.message}]" }
                                    )
                                }
                            }
                        } finally {
                            pool.shutdownNow()
                        }
                    }
                }
            },
            extraGovernance = { action, args ->
                if (action == "search_and_read") {
                    (1..args.optInt("top", 3).coerceIn(1, 8)).map { "read_url" }
                } else emptyList()
            },
        ),
        listOf("web_search", "read_url"),
    )

    // MCP：列服务器、列工具、调用、能力自查、本地部署、ACI 桥，都是「用一次 MCP」。
    installUnified(
        QuroUnifiedDispatchTool(
            name = "mcp",
            description = """外部 MCP 能力的统一入口，一次工具调用即可完成发现与执行。
- action=servers：列出已配置的外部服务器。
- action=list_tools：列出某服务器暴露的工具，参数 server。
- action=call：调用某服务器的某个工具，参数 server、tool、arguments。不知有哪些工具时可直接 call，错误信息会给出可用清单。
- action=capabilities：能力自查。
- action=deploy / undeploy / list_local：本地 MCP 端点的部署、卸载、列举。
- action=aci_list / aci_call / aci_bridge：经 ACI 通道调用外部 MCP。
不带 action 时：含 tool 或 arguments 视为 call，否则视为 list_tools。""",
            parametersJson = """{
  "type":"object",
  "properties":{
    "action":{"type":"string","enum":["servers","list_tools","call","capabilities","deploy","undeploy","list_local","aci_list","aci_call","aci_bridge"]},
    "server":{"type":"string","description":"服务器别名或地址"},
    "tool":{"type":"string","description":"要调用的外部工具名"},
    "arguments":{"type":"object","description":"传给外部工具的参数对象"},
    "name":{"type":"string","description":"部署/卸载时使用的端点名"}
  }
}""",
            registry = this,
            routes = mapOf(
                "servers" to listOf("mcp_servers"),
                "list_tools" to listOf("mcp_list_tools"),
                "call" to listOf("mcp_call"),
                "capabilities" to listOf("mcp_capabilities"),
                "deploy" to listOf("mcp_deploy"),
                "undeploy" to listOf("mcp_undeploy"),
                "list_local" to listOf("mcp_list_local"),
                "aci_list" to listOf("mcp_aci_list"),
                "aci_call" to listOf("mcp_aci_call"),
                "aci_bridge" to listOf("mcp_aci_bridge"),
            ),
            defaultAction = { if (it.has("tool") || it.has("arguments")) "call" else "list_tools" },
        ),
        listOf(
            "mcp_servers", "mcp_list_tools", "mcp_call", "mcp_capabilities",
            "mcp_deploy", "mcp_undeploy", "mcp_list_local",
            "mcp_aci_list", "mcp_aci_call", "mcp_aci_bridge",
        ),
    )

    // Linux 环境：start 与 status 原本就是同一份探测实现，全部收进一个 action 工具。
    installUnified(
        QuroUnifiedDispatchTool(
            name = "linux_env",
            description = """应用内 Linux 环境（proot + Ubuntu）的统一入口。
action=start 检查并初始化；status 返回就绪状态；stop 终止会话进程；run 执行 shell 命令；install 安装组件；pkg 统一包管理；process 查看/终止残留后台进程。
常用组合：start 确认就绪 → run 执行命令。不带 action 时默认 status。""",
            parametersJson = """{
  "type":"object",
  "properties":{
    "action":{"type":"string","enum":["start","stop","status","run","install","pkg","process"]},
    "command":{"type":"string","description":"run 使用的 shell 命令"},
    "package":{"type":"string","description":"install / pkg 使用的包名"}
  }
}""",
            registry = this,
            routes = mapOf(
                "start" to listOf("linux_start"),
                "stop" to listOf("linux_stop"),
                "status" to listOf("linux_status"),
                "run" to listOf("linux_run"),
                "install" to listOf("linux_install"),
                "pkg" to listOf("linux_pkg"),
                "process" to listOf("linux_process"),
            ),
            defaultAction = { "status" },
        ),
        listOf(
            "linux_run", "linux_install", "linux_pkg",
            "linux_start", "linux_stop", "linux_status", "linux_process",
        ),
    )

    // 工作流引擎：创建 / 点燃 / 查询运行是同一条生命周期。
    installUnified(
        QuroUnifiedDispatchTool(
            name = "wf",
            description = "工作流引擎统一入口。action=create 创建工作流、trigger 点燃运行、status 查询运行状态。不带 action 时默认 status。",
            parametersJson = """{"type":"object","properties":{"action":{"type":"string","enum":["create","trigger","status"]},"id":{"type":"string"},"spec":{"type":"object"}}}""",
            registry = this,
            routes = mapOf(
                "create" to listOf("wf_create"),
                "trigger" to listOf("wf_trigger"),
                "status" to listOf("wf_run_status"),
            ),
            defaultAction = { "status" },
        ),
        listOf("wf_create", "wf_trigger", "wf_run_status"),
    )

    // CMS 能力模块：列、调、状态、日志、结果、DAG、部署、卸载、工具箱、引擎状态。
    installUnified(
        QuroUnifiedDispatchTool(
            name = "cms",
            description = """能力模块（CMS）统一入口。
action=list 列模块、call 调用模块、status 状态、logs 日志、result 结果、run_dag 按依赖跑一组命令、deploy / undeploy 部署卸载终端模块、toolbox 工具箱、engine_status 引擎状态、priv_status 特权通道自查。
不带 action 时默认 list。""",
            parametersJson = """{"type":"object","properties":{"action":{"type":"string","enum":["list","call","status","logs","result","run_dag","deploy","undeploy","toolbox","engine_status","priv_status"]},"name":{"type":"string"},"arguments":{"type":"object"},"dag":{"type":"array","items":{"type":"object"}}}}""",
            registry = this,
            routes = mapOf(
                "list" to listOf("cms_list"),
                "call" to listOf("cms_call"),
                "status" to listOf("cms_status"),
                "logs" to listOf("cms_logs"),
                "result" to listOf("cms_result"),
                "run_dag" to listOf("cms_run_dag"),
                "deploy" to listOf("cms_deploy_terminal"),
                "undeploy" to listOf("cms_undeploy_terminal"),
                "toolbox" to listOf("cms_toolbox"),
                "engine_status" to listOf("cms_engine_status"),
                "priv_status" to listOf("priv_status"),
            ),
            defaultAction = { "list" },
        ),
        listOf(
            "cms_list", "cms_call", "cms_status", "cms_logs", "cms_result",
            "cms_run_dag", "cms_deploy_terminal", "cms_undeploy_terminal",
            "cms_toolbox", "cms_engine_status", "priv_status",
        ),
    )

    // 第三方服务授权保险库：增 / 列 / 删。
    installUnified(
        QuroUnifiedDispatchTool(
            name = "auth_service",
            description = "第三方服务授权保险库统一入口。action=add 新增、list 列举、remove 移除。不带 action 时默认 list。",
            parametersJson = """{"type":"object","properties":{"action":{"type":"string","enum":["add","list","remove"]},"name":{"type":"string"},"credential":{"type":"string"}}}""",
            registry = this,
            routes = mapOf(
                "add" to listOf("auth_service_add"),
                "list" to listOf("auth_service_list"),
                "remove" to listOf("auth_service_remove"),
            ),
            defaultAction = { "list" },
        ),
        listOf("auth_service_add", "auth_service_list", "auth_service_remove"),
    )

    // 前置动作降级为参数：执行工具名字不变，一次调用自动完成查目录/拉规范/探活/导出。
    attachPreflight(
        "ui_widget",
        before = listOf(
            QuroPreflightTool.Preflight("with_catalog", "card_catalog", "附带完整组件清单与样例"),
            QuroPreflightTool.Preflight("with_spec", "ui_dsl_spec", "附带 DSL 规范"),
        ),
        after = listOf(
            QuroPreflightTool.Preflight("with_validate", "ui_validate", "输出前自检"),
        ),
    )
    attachPreflight(
        "zx_web_card",
        before = listOf(QuroPreflightTool.Preflight("with_catalog", "zx_catalog", "附带组件名册与字段契约")),
    )
    attachPreflight(
        "codecanvas_script",
        before = listOf(QuroPreflightTool.Preflight("auto_probe", "codecanvas_probe", "先探活并配好渲染服务地址")),
    )
    attachPreflight(
        "build_apk",
        after = listOf(QuroPreflightTool.Preflight("auto_export", "export_apk", "构建后自动导出产物")),
    )
    attachPreflight(
        "aiwps_edit",
        before = listOf(QuroPreflightTool.Preflight("auto_read", "aiwps_read", "先读取原文档内容")),
    )
    attachPreflight(
        "screenshot",
        before = listOf(QuroPreflightTool.Preflight("auto_authorize", "enable_screen_capture", "先完成截图授权")),
    )

    // ═════════════ 第二批合体（2026-11-11）：8 族「一个动作被拆成多个工具」收口 ═════════════
    // 全部放在 attachPreflight 之后：screenshot 的授权前置包装器此时已经装好，
    // 降级后 screen(action=screenshot) 转发的仍是带 auto_authorize 的包装版本。

    // Shizuku 通道：探活 / 执行 / root 执行 / 冻结 / 解冻 / 静默安装 / 授权引导。
    installUnified(
        QuroUnifiedDispatchTool(
            name = "shizuku",
            description = """Shizuku 特权通道统一入口（ADB 级 shell，免 root；需用户手动授权）。
action=status 查服务状态与授权引导、grant 同 status（返回授权步骤）、exec 执行命令、root_exec 以 root 执行、freeze/unfreeze 冻结解冻应用、install 静默安装 APK。
组合动作：status_and_exec 一次完成「先探活再执行」，不用两次调用；exec 默认也无需先单独 status。
参数：command（exec/root_exec）、package_name（freeze/unfreeze）、apk_path（install）。不带 action 时默认 status。
红线：Shizuku 是真实系统权限，没有明确目的不要试探；授权必须用户手动点。""",
            parametersJson = """{"type":"object","properties":{"action":{"type":"string","enum":["status","grant","exec","root_exec","freeze","unfreeze","install","status_and_exec"]},"command":{"type":"string","description":"exec / root_exec 的 shell 命令"},"package_name":{"type":"string","description":"freeze / unfreeze 的目标包名"},"apk_path":{"type":"string","description":"install 的 APK 绝对路径"}}}""",
            registry = this,
            routes = mapOf(
                "status" to listOf("shizuku_status"),
                "grant" to listOf("shizuku_status"),
                "exec" to listOf("shizuku_exec"),
                "root_exec" to listOf("shizuku_root_exec"),
                "freeze" to listOf("freeze_app"),
                "unfreeze" to listOf("freeze_app"),
                "install" to listOf("install_app"),
                "status_and_exec" to listOf("shizuku_status", "shizuku_exec"),
            ),
            defaultAction = { if (it.has("command")) "exec" else "status" },
            stripAction = true,
            innerActions = mapOf("freeze" to "freeze", "unfreeze" to "unfreeze"),
        ),
        listOf("shizuku_status", "shizuku_exec", "shizuku_root_exec", "freeze_app", "install_app"),
    )

    // ZorvAI 键盘（Agent IME）：打字 / 回车 / 发送，以及最常用的一次调用组合。
    installUnified(
        QuroUnifiedDispatchTool(
            name = "keyboard",
            description = """ZorvAI 智能体键盘统一入口，走 IME 通道把文本打进当前聚焦输入框（需启用并切换到『Zorv AI 键盘』）。
action=type 输入文本（text 必填）、enter 按回车、send 按发送键、type_and_send 一次完成「打字并发送」、type_and_enter 一次完成「打字并回车」。
日常发消息请用 type_and_send，不要再拆成两次工具调用。""",
            parametersJson = """{"type":"object","properties":{"action":{"type":"string","enum":["type","enter","send","type_and_send","type_and_enter"]},"text":{"type":"string","description":"type 系列动作要输入的文本"}}}""",
            registry = this,
            routes = mapOf(
                "type" to listOf("ai_type_text"),
                "enter" to listOf("ai_press_enter"),
                "send" to listOf("ai_press_send"),
                "type_and_send" to listOf("ai_type_text", "ai_press_send"),
                "type_and_enter" to listOf("ai_type_text", "ai_press_enter"),
            ),
            defaultAction = { if (it.has("text")) "type_and_send" else "send" },
            stripAction = true,
        ),
        listOf("ai_type_text", "ai_press_enter", "ai_press_send"),
    )

    // ACI（跨应用 AIDL 能力）：发现 / 调用 / 本地 HTTP 端点管理。
    installUnified(
        QuroUnifiedDispatchTool(
            name = "aci",
            description = """ACI 跨应用能力统一入口（本地 AIDL，免 root、免 Shizuku、免无障碍）。
action=list 列出已发现的第三方 App 与其能力、call 调用某项能力、list_and_call 一次完成「先列能力再调用」。
server_status / server_start / server_stop / server_add / server_remove 管理本地 /aci/* HTTP 端点（默认 8848，真实桥接 AIDL，供外部进程互控）。
call 参数：capability（**能力 id 字符串**，如 "browser_open"——不是对象、不是 {"id":...}）、args（能力参数对象）、target_package（可选，缺省用 ACI 管理中心设的默认应用）、confirm。
aci_call 返回 503 时框架会自动重绑，直接重试一次即可，禁止改用系统权限工具去诊断。""",
            parametersJson = """{"type":"object","properties":{"action":{"type":"string","enum":["list","call","list_and_call","server_status","server_start","server_stop","server_add","server_remove"]},"capability":{"type":"string","description":"call/list_and_call 的能力 id，纯字符串（如 browser_open）。不要传对象"},"args":{"type":"object","description":"call 的能力参数对象"},"target_package":{"type":"string","description":"第三方 App 包名，可省略用默认应用"},"confirm":{"type":"boolean"},"port":{"type":"integer"},"capability_id":{"type":"string","description":"server_add/server_remove 的能力 ID"},"capability_def":{"type":"object","description":"server_add 的能力定义 JSON（转发为 aci_http_server 的 capability）"}}}""",
            registry = this,
            routes = mapOf(
                "list" to listOf("aci_list"),
                "call" to listOf("aci_call"),
                // 治理口径：list_and_call 会真的执行一次 call，审批与超时按两个真实目标算。
                "list_and_call" to listOf("aci_list", "aci_call"),
                "server_status" to listOf("aci_http_server"),
                "server_start" to listOf("aci_http_server"),
                "server_stop" to listOf("aci_http_server"),
                "server_add" to listOf("aci_http_server"),
                "server_remove" to listOf("aci_http_server"),
            ),
            defaultAction = { if (it.has("capability")) "call" else "list" },
            stripAction = true,
            innerActions = mapOf(
                "server_status" to "status", "server_start" to "start", "server_stop" to "stop",
                "server_add" to "add_capability", "server_remove" to "remove_capability",
            ),
            // schema 里 capability 已被 call 用作 string 能力 id，add_capability 的 object 定义改名桥接。
            renameArgs = mapOf("capability_def" to "capability"),
            // 🔴 2026-11-12：list_and_call 此前只是「同一个参数串行灌给两个旧实现」——
            // 模型不传 capability 时只看到清单，传对象时 call 直接 404，于是实测表现成
            // 「只回了清单没执行」。这里改成真正的复合动作：先列清单给模型看，
            // 再把 capability 归一成字符串（容忍 {"id":...}/{"name":...}）后真调一次。
            composite = { action, args, invoke ->
                if (action != "list_and_call") {
                    null
                } else {
                    val listed = invoke("aci_list", "{}")
                    val cap = normalizeAciCapability(args)
                    if (cap.isEmpty()) {
                        "$listed\n\n⚠️ 未指定 capability，因此只列出了清单。下一步：从上面选一个能力 id，" +
                            "调用 aci(action=\"call\", capability=\"<id>\", args={...})。"
                    } else {
                        val callArgs = JSONObject().apply {
                            args.optString("target_package").takeIf { it.isNotBlank() }
                                ?.let { put("target_package", it) }
                            put("capability", cap)
                            args.optJSONObject("args")?.let { put("args", it) }
                            if (args.has("confirm")) put("confirm", args.optBoolean("confirm"))
                        }
                        "$listed\n\n===== 调用结果 =====\n" + invoke("aci_call", callArgs.toString())
                    }
                }
            },
        ),
        listOf("aci_list", "aci_call", "aci_http_server"),
    )

    // ROOT 通道：状态自查与执行。
    installUnified(
        QuroUnifiedDispatchTool(
            name = "root",
            description = """ROOT 特权通道统一入口（最高风险，需设备已 Root）。
action=status 检测 ROOT 类型、exec 执行 shell 命令（command 必填）、status_and_exec 一次完成「先确认可用再执行」。不带 action 时默认 status。""",
            parametersJson = """{"type":"object","properties":{"action":{"type":"string","enum":["status","exec","status_and_exec"]},"command":{"type":"string","description":"exec 的 shell 命令"}}}""",
            registry = this,
            routes = mapOf(
                "status" to listOf("root_status"),
                "exec" to listOf("root_exec"),
                "status_and_exec" to listOf("root_status", "root_exec"),
            ),
            defaultAction = { if (it.has("command")) "exec" else "status" },
            stripAction = true,
        ),
        listOf("root_exec", "root_status"),
    )

    // 屏幕操作：感知（读控件树 / 截图 / 视觉分析）与操控（点 / 长按 / 滑 / 输入 / 滚 / 全局动作）一个入口。
    installUnified(
        QuroUnifiedDispatchTool(
            name = "screen",
            description = """屏幕感知与操控统一入口（操作用户正在看的这块屏，需无障碍服务已授权）。
感知：read 读控件树、foreground 查前台应用、state 查屏幕状态、screenshot 截图（可带 return_format）、analyze 用视觉模型看屏（question/mode）、photo 拍照、authorize 申请截图授权。
操控：tap（x/y 或 text/description）、long_press（+duration_ms）、swipe（direction 或起终点）、input（text/hint/target_text）、scroll（direction/count）、global（op=back/home/recents/...）。
媒体：record_start / record_stop 开始停止录屏。
组合动作（一次调用，别拆成多次）：read_and_tap 先读屏再按文字点、tap_and_read 点完立刻读新界面、capture_and_analyze 截图并交给视觉模型。
不带 action 时：有 text 视为 input，有 question 视为 analyze，否则视为 read。""",
            parametersJson = """{"type":"object","properties":{"action":{"type":"string","enum":["read","foreground","state","screenshot","analyze","photo","authorize","tap","long_press","swipe","input","scroll","global","record_start","record_stop","read_and_tap","tap_and_read","capture_and_analyze"]},"x":{"type":"number"},"y":{"type":"number"},"start_x":{"type":"number"},"start_y":{"type":"number"},"end_x":{"type":"number"},"end_y":{"type":"number"},"text":{"type":"string"},"description":{"type":"string"},"hint":{"type":"string"},"target_text":{"type":"string"},"target_desc":{"type":"string"},"duration_ms":{"type":"number"},"direction":{"type":"string"},"count":{"type":"integer"},"question":{"type":"string"},"mode":{"type":"string"},"return_format":{"type":"string"},"op":{"type":"string","description":"global 动作：back/home/recents/notifications/quick_settings/power_dialog/lock_screen/take_screenshot"}}}""",
            registry = this,
            routes = mapOf(
                "read" to listOf("read_screen"),
                "foreground" to listOf("get_foreground_app"),
                "state" to listOf("get_screen_state"),
                "screenshot" to listOf("screenshot"),
                "analyze" to listOf("visual_analysis"),
                "photo" to listOf("take_photo"),
                "authorize" to listOf("enable_screen_capture"),
                "tap" to listOf("tap_screen"),
                "long_press" to listOf("long_press_screen"),
                "swipe" to listOf("swipe_screen"),
                "input" to listOf("input_text"),
                "scroll" to listOf("scroll_screen"),
                "global" to listOf("global_action"),
                "record_start" to listOf("screen_record"),
                "record_stop" to listOf("screen_record"),
                "read_and_tap" to listOf("read_screen", "tap_screen"),
                "tap_and_read" to listOf("tap_screen", "read_screen"),
                "capture_and_analyze" to listOf("screenshot", "visual_analysis"),
            ),
            defaultAction = {
                when {
                    it.has("question") -> "analyze"
                    it.has("text") -> "input"
                    else -> "read"
                }
            },
            stripAction = true,
            innerActions = mapOf(
                "record_start" to "start", "record_stop" to "stop", "global" to "@op",
            ),
        ),
        listOf(
            "read_screen", "get_foreground_app", "get_screen_state", "tap_screen",
            "long_press_screen", "swipe_screen", "input_text", "scroll_screen",
            "global_action", "screenshot", "visual_analysis", "enable_screen_capture",
            "screen_record", "take_photo",
        ),
    )

    // 手机权限 / 特权通道：权限模式自查、特权执行、设备管理员策略、系统设置开关。
    installUnified(
        QuroUnifiedDispatchTool(
            name = "permissions",
            description = """手机权限与系统策略统一入口。
action=check 查 CMS 权限模式与已授权项（高风险动作前先自查）、status 查设备管理员激活状态、lock 锁屏、camera 禁用/恢复摄像头（disabled）、flashlight 开关手电（on）。
policy 调系统设置开关：setting=volume/brightness/wifi/bluetooth/notification/airplane/rotation/flashlight，op=该设置项的动作（如 up/on/set），level 可选。
exec 经特权通道执行命令（Shizuku→ROOT 自动降级，command + timeout_ms）、run_status 查该通道状态。
组合动作：check_and_exec 一次完成「先查权限模式再执行」，符合高风险动作的前置要求。
不带 action 时默认 check。""",
            parametersJson = """{"type":"object","properties":{"action":{"type":"string","enum":["check","status","lock","camera","flashlight","policy","exec","run_status","check_and_exec"]},"command":{"type":"string"},"timeout_ms":{"type":"integer"},"setting":{"type":"string","enum":["volume","brightness","wifi","bluetooth","notification","airplane","rotation","flashlight"]},"op":{"type":"string","description":"policy 的动作：up/down/on/off/toggle/set/expand/collapse/clear/auto/portrait/landscape"},"level":{"type":"integer"},"disabled":{"type":"boolean"},"on":{"type":"boolean"}}}""",
            registry = this,
            routes = mapOf(
                "check" to listOf("priv_status"),
                "status" to listOf("device_admin_status"),
                "lock" to listOf("lock_screen"),
                "camera" to listOf("set_camera_disabled"),
                "flashlight" to listOf("toggle_flashlight"),
                "policy" to listOf("device_control"),
                "exec" to listOf("priv_exec"),
                "run_status" to listOf("priv_exec"),
                "check_and_exec" to listOf("priv_status", "priv_exec"),
            ),
            defaultAction = { if (it.has("command")) "check_and_exec" else "check" },
            stripAction = true,
            innerActions = mapOf(
                "policy" to "@op", "exec" to "run", "run_status" to "status",
                "check_and_exec" to "run",
            ),
        ),
        listOf(
            "priv_status", "priv_exec", "device_admin_status", "lock_screen",
            "set_camera_disabled", "device_control", "toggle_flashlight",
        ),
    )

    // 记忆库：长期记忆、经验笔记、知识库、语义检索、私有库只读查询。
    installUnified(
        QuroUnifiedDispatchTool(
            name = "memory",
            description = """记忆与知识库统一入口。
长期记忆：save（content/title/group/tags）、list、search（query）、delete（query）。
经验笔记：log（type/title/content/tags/platform）、query（query/top_n）、correct（id/was/reason/fix）、version（platform/version）。
本地知识库：kb_add（path/content/append）、kb_search（query/limit）、kb_list、kb_delete。
语义检索：rag（query/limit，本地向量+词法降级）、rag_index、rag_count、discover（query，按原话找该用的工具/规则/组件）。
私有库只读：db_list / db_tables / db_schema / db_query（db/table/sql，仅 SELECT）。
组合动作：recall 一次同时查长期记忆与经验笔记，回答前不必分两次检索。
不带 action 时：有 content 视为 save，有 query 视为 recall。""",
            parametersJson = """{"type":"object","properties":{"action":{"type":"string","enum":["save","list","search","delete","log","query","correct","version","kb_add","kb_search","kb_list","kb_delete","rag","rag_index","rag_count","discover","db_list","db_tables","db_schema","db_query","recall"]},"content":{"type":"string"},"title":{"type":"string"},"group":{"type":"string"},"tags":{"type":"array","items":{"type":"string"}},"query":{"type":"string"},"id":{"type":"string"},"was":{"type":"string"},"reason":{"type":"string"},"fix":{"type":"string"},"type":{"type":"string"},"platform":{"type":"string"},"version":{"type":"string"},"top_n":{"type":"integer"},"path":{"type":"string"},"append":{"type":"boolean"},"limit":{"type":"integer"},"db":{"type":"string"},"table":{"type":"string"},"sql":{"type":"string"}}}""",
            registry = this,
            routes = mapOf(
                "save" to listOf("memory_save"),
                "list" to listOf("memory_list"),
                "search" to listOf("memory_search"),
                "delete" to listOf("memory_delete"),
                "log" to listOf("experience_log"),
                "query" to listOf("experience_query"),
                "correct" to listOf("experience_correct"),
                "version" to listOf("experience_version_check"),
                "kb_add" to listOf("knowledge_manage"),
                "kb_search" to listOf("knowledge_manage"),
                "kb_list" to listOf("knowledge_manage"),
                "kb_delete" to listOf("knowledge_manage"),
                "rag" to listOf("knowledge_rag_search"),
                "rag_index" to listOf("knowledge_rag_search"),
                "rag_count" to listOf("knowledge_rag_search"),
                "discover" to listOf("rag_search"),
                "db_list" to listOf("private_db"),
                "db_tables" to listOf("private_db"),
                "db_schema" to listOf("private_db"),
                "db_query" to listOf("private_db"),
                "recall" to listOf("memory_search", "experience_query"),
            ),
            defaultAction = {
                when {
                    it.has("content") -> "save"
                    it.has("query") -> "recall"
                    else -> "list"
                }
            },
            stripAction = true,
            innerActions = mapOf(
                "kb_add" to "add", "kb_search" to "search", "kb_list" to "list",
                "kb_delete" to "delete", "rag_index" to "reindex", "rag_count" to "count",
                "db_list" to "db_list", "db_tables" to "db_tables",
                "db_schema" to "db_schema", "db_query" to "db_query",
            ),
        ),
        listOf(
            "memory_save", "memory_list", "memory_search", "memory_delete",
            "experience_log", "experience_query", "experience_correct", "experience_version_check",
            "knowledge_manage", "knowledge_rag_search", "rag_search", "private_db",
        ),
    )

    // 更早一批合体工具吸收的散装入口统一降级；名单只在 QuroUnifiedToolNames.RETIRED 写一次。
    QuroUnifiedToolNames.RETIRED.forEach { demote(it) }
    QuroUnifiedToolNames.TERMINAL_ABSORBED.forEach { demote(it) }
}

/** 用包装器替换同名工具，并把被吸收的前置工具降级为内部实现。 */
private fun QuroToolRegistry.attachPreflight(
    target: String,
    before: List<QuroPreflightTool.Preflight> = emptyList(),
    after: List<QuroPreflightTool.Preflight> = emptyList(),
) {
    val delegate = get(target) ?: return
    register(QuroPreflightTool(delegate, this, before, after))
    // keepExposed：包装后仍要独立暴露的前置/后置工具不 demote。
    // 🔴 export_apk 不能 demote：「导出产物」是独立用户意图（用户可能不重新构建、
    // 只想导出已有 APK），demote 后它从 specs 消失 → 能力目录/RAG 索引不再包含它，
    // 口语「导出APK」就永远检索不到（实测 ToolRagRecallTest「导出APK」召回回退）。
    // auto_probe（codecanvas_probe）是纯探活，script 的 auto_probe=true 已完整覆盖，照旧 demote。
    val keep = after.filter { it.legacyName == "export_apk" }.map { it.legacyName }.toSet()
    (before + after).filter { it.legacyName !in keep }.forEach { demote(it.legacyName) }
}
