package com.ai.assistance.quro.core.services

import android.content.Context

/**
 * 应用中心（服务目录）数据层。
 *
 * ## 这是什么
 *
 * 「应用」页是一个**外部服务目录**：把 DeepWiki / Context7 / GitHub /
 * Notion / Supabase / Zapier 等外部资源按分类罗列，用户「连接」后 AI 知道
 * 这些资源可用，在回答技术问题时主动去查权威源，而不是凭训练记忆硬编。
 *
 * ## 接入方式（诚实边界）
 *
 * 我们没有各家服务的私有 API 授权，所以「连接」的语义是：
 * **把该服务写入 AI 的已连接服务目录（system prompt 注入）**，
 * AI 随后可以用 web_search / read_url 查询该服务的**公开文档、API 参考、状态页**。
 * 不冒充 OAuth 级别的深度集成——能做到什么、做不到什么，提示词里写清楚。
 *
 * 纯 Kotlin + SharedPreferences，无 Android UI 依赖，可单测。
 */

/** 目录里的一个外部服务。 */
data class QuroService(
    val id: String,
    val name: String,
    /** 一句话说明（目录页副标题，直接写给用户看）。 */
    val desc: String,
    /** 分类名（多想应用 / 效率 / 内容 / 数据 / 设计 / 通信 / 自动化 / 支付）。 */
    val category: String,
    /** 该服务对 AI 的使用说明（注入 system prompt，告诉 AI 何时、怎么用）。 */
    val aiNote: String,
    /**
     * 🔴 第三方 MCP 端点（2026-11-04，应用页一键连接）：
     * 非空 = 「连接」动作把本服务注册为**真实 MCP 服务器**（QuroMcpClientPrefs，
     * mcp_list_tools / mcp_call 即刻可调），不再只是提示词目录。
     * 端点为官方 Streamable HTTP（ZorvAI 的 QuroMcpClient 已兼容该传输）。
     */
    val mcpUrl: String = "",
    /** 连接该 MCP 是否需要用户填 API Key（Bearer）。true 时连接动作弹输入框。 */
    val needsApiKey: Boolean = false,
    /** 🔴 该服务走 OAuth 2.0 + PKCE 授权（2026-11-05）：连接=发现端点→浏览器授权→粘回调 URL。 */
    val oauth: Boolean = false,
) {
    /** 该服务最权威的文档域名（供 AI 直接 read_url）。 */
    val docDomain: String get() = DOC_DOMAINS[id] ?: ""
}

/** 服务 → 权威文档域名。 */
private val DOC_DOMAINS = mapOf(
    "deepwiki" to "deepwiki.com",
    "context7" to "context7.com",
    "huggingface" to "huggingface.co",
    "mslearn" to "learn.microsoft.com",
    "aws_knowledge" to "docs.aws.amazon.com",
    "notion" to "developers.notion.com",
    "linear" to "developers.linear.app",
    "atlassian" to "developer.atlassian.com",
    "mondry" to "mondry.com",
    "github" to "docs.github.com",
    "sentry" to "docs.sentry.io",
    "vercel" to "vercel.com/docs",
    "cloudflare" to "developers.cloudflare.com",
    "supabase" to "supabase.com/docs",
    "neon" to "neon.tech/docs",
    "airtable" to "airtable.com/developers",
    "pocketbase" to "pocketbase.io/docs",
    "canva" to "canva.dev/docs",
    "webflow" to "developers.webflow.com",
    "intercom" to "developers.intercom.com",
    "zapier" to "platform.zapier.com",
    "paypal" to "developer.paypal.com",
    "square" to "developer.squareup.com",
    "stripe" to "docs.stripe.com",
)

/** 服务目录（分组顺序即展示顺序）。 */
object QuroServiceCatalog {

    const val PREFS = "quro_service_catalog"
    const val K_ENABLED = "enabled_ids"

    /** 分类 → 服务列表（顺序固定）。 */
    val categories: List<Pair<String, List<QuroService>>> = listOf(
        "多想应用" to listOf(
            QuroService("deepwiki", "DeepWiki", "深度开源库结构、导航、深度问题解答", "多想应用",
                "查询 GitHub 开源仓库的结构化深度 Wiki（架构、模块、流程图），适合「这个库怎么用/怎么设计的」类问题。",
                mcpUrl = "https://mcp.deepwiki.com/mcp"),
            QuroService("context7", "Context7", "查询第三方库的最新文档和用法代码示例，避免模型基于过时训练数据生成错误代码", "多想应用",
                "查询第三方库/框架的最新官方文档与代码示例。当用户问题涉及库的具体 API 用法时优先查它，避免凭过时训练数据作答。",
                mcpUrl = "https://mcp.context7.com/mcp"),
            QuroService("huggingface", "Hugging Face", "查询模型、Spaces 与相关的资料，更适合定位某个组件与代码说明", "多想应用",
                "查询 Hugging Face 上的模型卡、数据集与 Spaces 资料，适合定位模型/组件的用法说明。"),
            QuroService("mslearn", "Microsoft Learn", "查询 Azure/应用/芯片与 MCP 相关技术文档", "多想应用",
                "查询 Microsoft 官方技术文档（Azure、.NET、MCP 协议等），内容权威且更新及时。"),
            QuroService("aws_knowledge", "AWS Knowledge", "查询 AWS 技术文档、API 参考与最佳实践", "多想应用",
                "查询 AWS 官方技术文档、API 参考与最佳实践指南。"),
        ),
        "效率" to listOf(
            QuroService("notion", "Notion", "轻松创建和管理 Notion 问题和数据", "效率",
                "查询 Notion API/集成开发文档。",
                mcpUrl = "https://mcp.notion.com/mcp", oauth = true),
            QuroService("linear", "Linear", "管理和跟踪 Linear 的问题、项目与排期", "效率",
                "查询 Linear API 与集成开发文档。",
                mcpUrl = "https://mcp.linear.app/mcp", oauth = true),
            QuroService("atlassian", "Atlassian", "无障碍使用 Jira 和 Confluence 实现更改", "效率",
                "查询 Jira/Confluence 的 REST API 与应用开发文档。"),
            QuroService("mondry", "mondry.com", "管理 mondry.com 自层服务，日常与更新事务", "效率",
                "查询 mondry.com 的服务与更新信息。"),
        ),
        "内容" to listOf(
            QuroService("github", "GitHub", "管理仓库、创建分支、接管发布与工单", "内容",
                "查询 GitHub 文档（Actions、REST API、权限模型等）。涉及 Git/GitHub 用法问题时优先查官方文档。"),
            QuroService("sentry", "Sentry", "检查性能、处理和解决问题、定位异常来源", "内容",
                "查询 Sentry 错误监控与性能分析的配置与 API 文档。",
                mcpUrl = "https://mcp.sentry.dev/mcp", oauth = true),
            QuroService("vercel", "Vercel", "管理 DNS、部署和核心的日常运维", "内容",
                "查询 Vercel 部署、域名与边缘函数文档。",
                mcpUrl = "https://mcp.vercel.com/mcp", oauth = true),
            QuroService("cloudflare", "Cloudflare Workers", "管理 Cloudflare、KV、R2 与 DO 日志", "内容",
                "查询 Cloudflare Workers/KV/R2/Durable Objects 的开发文档。"),
        ),
        "数据" to listOf(
            QuroService("supabase", "Supabase", "管理 Supabase 项目、数据库、认证与存储", "数据",
                "查询 Supabase（Postgres/认证/存储/RLS）官方文档。数据库设计问题时可参考其 Row Level Security 等实践。"),
            QuroService("neon", "Neon", "管理 Neon Postgres 数据库、分支与扩缩容", "数据",
                "查询 Neon serverless Postgres 的文档（分支、连接池等）。"),
            QuroService("airtable", "Airtable", "创建 Base、管理表格、字段与视图记录", "数据",
                "查询 Airtable API（Base/Table/Record）文档。"),
            QuroService("pocketbase", "PocketBase", "管理 PocketBase 集合、数据与连接段", "数据",
                "查询 PocketBase（集合/规则/实时订阅）文档。"),
        ),
        "设计" to listOf(
            QuroService("canva", "Canva", "创建、编辑、调式尺寸在相匹 Canva 内容", "设计",
                "查询 Canva Connect API（设计/素材）文档。"),
            QuroService("webflow", "Webflow", "管理 Webflow 站点、样式和 CMS 内容", "设计",
                "查询 Webflow Designer/API 与 CMS 文档。"),
        ),
        "通信" to listOf(
            QuroService("intercom", "Intercom", "联系人、会话与客户消息管理", "通信",
                "查询 Intercom 客户消息平台 API 文档。"),
        ),
        "自动化" to listOf(
            QuroService("zapier", "Zapier", "用 Zapier 连接应用与自动化工作流", "自动化",
                "查询 Zapier 平台（Zap/集成开发）文档。"),
        ),
        "支付" to listOf(
            QuroService("paypal", "PayPal", "销售交易、支付、订阅与争议", "支付",
                "查询 PayPal 开发者文档（支付/订阅/争议）。"),
            QuroService("square", "Square", "处理付款、订单管理与客户", "支付",
                "查询 Square 开发者文档（支付/订单/客户）。"),
            QuroService("stripe", "Stripe", "管理支付、订阅与客户", "支付",
                "查询 Stripe 官方文档（支付/订阅/账单）。"),
        ),
    )

    val all: List<QuroService> get() = categories.flatMap { it.second }

    fun byId(id: String): QuroService? = all.find { it.id == id }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun enabledIds(ctx: Context): Set<String> =
        prefs(ctx).getStringSet(K_ENABLED, emptySet()).orEmpty()

    fun isEnabled(ctx: Context, id: String): Boolean = enabledIds(ctx).contains(id)

    /** 连接 / 断开一个服务。 */
    fun setEnabled(ctx: Context, id: String, on: Boolean) {
        val cur = enabledIds(ctx).toMutableSet()
        if (on) cur.add(id) else cur.remove(id)
        prefs(ctx).edit().putStringSet(K_ENABLED, cur).apply()
    }

    /**
     * 生成注入 system prompt 的「已连接服务目录」段落；无已连接服务时返回空串（省 token）。
     *
     * 措辞明确告诉 AI：这些是**可查询的公开资料源**，用 web_search / read_url 访问其文档域名；
     * 不具备写入用户账户的私有授权，不要向用户暗示能直接操作其账号数据。
     */
    fun promptSection(ctx: Context): String {
        val enabled = enabledIds(ctx).mapNotNull { byId(it) }
        if (enabled.isEmpty()) return ""
        return buildString {
            append("## 已连接的外部服务目录\n")
            append("用户已在「应用中心」连接以下外部服务。回答相关问题时，优先通过 web_search / read_url ")
            append("查询其权威文档域，而不是凭训练记忆硬答：\n")
            enabled.forEach { s ->
                append("- ").append(s.name)
                if (s.docDomain.isNotBlank()) append("（").append(s.docDomain).append("）")
                append("：").append(s.aiNote)
                append('\n')
            }
            append("注意：你只有这些服务公开文档的查询能力，没有操作用户账户的私有授权；")
            append("如用户要求改数据，说明需要其自行到对应平台操作。\n")
        }
    }
}
