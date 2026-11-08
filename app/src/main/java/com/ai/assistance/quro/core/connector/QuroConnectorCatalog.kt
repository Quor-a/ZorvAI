package com.ai.assistance.quro.core.connector

import android.content.Context

/**
 * 连接器目录（2026-11-06 新增功能）。
 *
 * ## 这是什么
 *
 * 「连接器」是把外部服务的**官方 MCP 端点**做成商店式目录：用户浏览分类、
 * 点「连接」，完成 OAuth / API Key 授权后，该服务即刻注册为真实 MCP 服务器
 * （写入 [com.ai.assistance.quro.core.mcp.QuroMcpClientPrefs]），AI 通过
 * mcp_servers / mcp_list_tools / mcp_call 全链路直接调用。
 *
 * ## 与相邻功能的关系（分体不合并）
 *
 * - **设置 → MCP 服务**：手动添加任意 MCP 服务器的技术入口（两者共用同一份
 *   [com.ai.assistance.quro.core.mcp.QuroMcpClientPrefs]，连接器连上的服务
 *   在那边可见、可停用、可删）。
 * - **应用中心**：提示词级的外部资料目录（web_search/read_url 查公开文档），
 *   不做真实授权。连接器是真实授权的商店入口，两者互补。
 *
 * ## signIn 类型
 *
 * - [SignInType.OAUTH]：OAuth 2.0 + PKCE（QuroMcpOAuth 全流程：发现→动态注册→
 *   浏览器授权→粘回调→token 自动续期）。
 * - [SignInType.API_KEY]：连接时弹 Key 输入框，Key 以 Bearer 存入 MCP 配置。
 * - [SignInType.NONE]：免登录直连。
 *
 * 端点来源（2026-11-06 全量核实）：各厂商官方 MCP 文档
 * 交叉比对 + awesome-remote-mcp-servers 开源清单。仅收录 Streamable HTTP
 * （POST JSON-RPC）端点——旧式 /sse 纯推流端点本客户端不支持，一律不收。
 *
 * 纯 Kotlin + SharedPreferences，无 Android UI 依赖，可单测。
 */

/** 授权方式。 */
enum class SignInType {
    /** OAuth 2.0 + PKCE 浏览器授权。 */
    OAUTH,
    /** 用户粘贴 API Key（Bearer）。 */
    API_KEY,
    /** 免登录直连。 */
    NONE,
}

/** 目录里的一个连接器。 */
data class QuroConnector(
    val id: String,
    val name: String,
    /** 分类 id（见 [QuroConnectorCatalog.categories]）。 */
    val category: String,
    /** 一句话说明（目录页副标题）。 */
    val desc: String,
    /** 官方 MCP 端点（Streamable HTTP）。 */
    val mcpUrl: String,
    /** 授权方式。 */
    val signIn: SignInType,
    /** 该服务对 AI 的使用说明（连接后注入 system prompt 的连接器段落）。 */
    val aiNote: String,
    /** 该服务最权威的文档域名（供 AI read_url）。 */
    val docDomain: String = "",
) {
    /** UI 徽标文字。 */
    val signInLabel: String
        get() = when (signIn) {
            SignInType.OAUTH -> "OAuth"
            SignInType.API_KEY -> "API Key"
            SignInType.NONE -> "免登录"
        }
}

/** 分类定义（id + 展示名，顺序即展示顺序）。 */
data class ConnectorCategory(val id: String, val label: String)

object QuroConnectorCatalog {

    const val PREFS = "quro_connector_catalog"
    const val K_CONNECTED = "connected_ids"

    /** 分类（顺序固定）。 */
    val categories: List<ConnectorCategory> = listOf(
        ConnectorCategory("knowledge", "文档与知识"),
        ConnectorCategory("productivity", "效率"),
        ConnectorCategory("code", "代码"),
        ConnectorCategory("data", "数据"),
        ConnectorCategory("design", "设计"),
        ConnectorCategory("communication", "通信"),
        ConnectorCategory("automation", "自动化"),
        ConnectorCategory("payments", "支付"),
        ConnectorCategory("daily-life", "生活与出行"),
    )

    /** 连接器目录（官方端点，全部经核实）。 */
    val connectors: List<QuroConnector> = listOf(
        // ── 文档与知识（多数免登录）──
        QuroConnector("deepwiki", "DeepWiki", "knowledge",
            "GitHub 开源仓库的结构化深度 Wiki", "https://mcp.deepwiki.com/mcp", SignInType.NONE,
            "查询 GitHub 开源仓库的结构化深度 Wiki（架构、模块、流程图），适合「这个库怎么用/怎么设计的」类问题。",
            "deepwiki.com"),
        QuroConnector("context7", "Context7", "knowledge",
            "第三方库最新官方文档与代码示例", "https://mcp.context7.com/mcp", SignInType.NONE,
            "查询第三方库/框架的最新官方文档与代码示例。涉及库的具体 API 用法时优先查它，避免凭过时训练数据作答。",
            "context7.com"),
        QuroConnector("huggingface", "Hugging Face", "knowledge",
            "模型、数据集与 Spaces 资料", "https://huggingface.co/mcp", SignInType.NONE,
            "查询 Hugging Face 上的模型卡、数据集与 Spaces 资料，适合定位模型/组件的用法说明。",
            "huggingface.co"),
        QuroConnector("microsoft-learn", "Microsoft Learn", "knowledge",
            "微软官方技术文档", "https://learn.microsoft.com/api/mcp", SignInType.NONE,
            "查询 Microsoft 官方技术文档（Azure、.NET、MCP 协议等），内容权威且更新及时。",
            "learn.microsoft.com"),
        QuroConnector("aws-knowledge", "AWS Knowledge", "knowledge",
            "AWS 官方技术文档", "https://knowledge-mcp.global.api.aws", SignInType.NONE,
            "查询 AWS 官方技术文档、API 参考与最佳实践指南。",
            "docs.aws.amazon.com"),
        QuroConnector("gitmcp", "GitMCP", "knowledge",
            "任意 GitHub 仓库的即时文档问答", "https://gitmcp.io/docs", SignInType.NONE,
            "面向任意 GitHub 开源仓库的文档查询通道：不知道某开源项目怎么用、找它的官方文档时先走这里。",
            "gitmcp.io"),
        // ── 效率 ──
        QuroConnector("notion", "Notion", "productivity",
            "页面与数据库读写", "https://mcp.notion.com/mcp", SignInType.OAUTH,
            "搜索、阅读、编辑 Notion 页面与数据库：整理会议纪要、更新任务状态、按模板新建页面。",
            "developers.notion.com"),
        QuroConnector("linear", "Linear", "productivity",
            "问题、项目与排期管理", "https://mcp.linear.app/mcp", SignInType.OAUTH,
            "查询、创建、更新 Linear 的 issue / project / cycle，团队排期与工单跟踪。",
            "developers.linear.app"),
        QuroConnector("atlassian", "Atlassian", "productivity",
            "Jira 工单 + Confluence 页面", "https://mcp.atlassian.com/v2/mcp", SignInType.OAUTH,
            "查询、创建、更新 Jira 工单与 Confluence 页面（Rovo MCP v2，一个端点覆盖 Jira+Confluence）。",
            "developer.atlassian.com"),
        QuroConnector("monday", "monday.com", "productivity",
            "项目板与任务状态流转", "https://mcp.monday.com/mcp", SignInType.OAUTH,
            "查询、更新 monday.com 的 board / item / status，项目看板自动化。",
            "developer.monday.com"),
        QuroConnector("asana", "Asana", "productivity",
            "项目、任务与组合管理", "https://mcp.asana.com/v2/mcp", SignInType.OAUTH,
            "查询、创建、更新 Asana 的项目/任务/子任务与组合视图。",
            "developers.asana.com"),
        // ── 代码 ──
        QuroConnector("github", "GitHub", "code",
            "仓库、Issue/PR 与 Actions", "https://api.githubcopilot.com/mcp/", SignInType.OAUTH,
            "管理 GitHub 仓库：读写 issue / PR / 分支 / 文件、跑 Actions、查代码搜索。",
            "docs.github.com"),
        QuroConnector("gitlab", "GitLab", "code",
            "项目、MR 与流水线", "https://gitlab.com/api/v4/mcp", SignInType.OAUTH,
            "管理 GitLab 项目：issue / merge request / 分支 / CI 流水线操作与查询。",
            "docs.gitlab.com"),
        QuroConnector("sentry", "Sentry", "code",
            "错误监控与异常定位", "https://mcp.sentry.dev/mcp", SignInType.OAUTH,
            "查询 Sentry 的错误事件、issue 分组与性能 trace，定位线上异常来源。",
            "docs.sentry.io"),
        QuroConnector("vercel", "Vercel", "code",
            "部署、域名与边缘函数", "https://mcp.vercel.com", SignInType.OAUTH,
            "管理 Vercel 部署列表、域名 DNS、环境变量与边缘函数日志。",
            "vercel.com/docs"),
        QuroConnector("cloudflare", "Cloudflare Workers", "code",
            "Workers、KV、R2 与 DO", "https://bindings.mcp.cloudflare.com/mcp", SignInType.OAUTH,
            "管理 Cloudflare Workers/KV/R2/Durable Objects 的开发与部署。",
            "developers.cloudflare.com"),
        QuroConnector("semgrep", "Semgrep", "code",
            "代码安全扫描", "https://mcp.semgrep.ai/mcp", SignInType.NONE,
            "对代码做静态安全扫描、查漏洞规则与扫描结果，安全审计辅助。",
            "semgrep.dev"),
        // ── 数据 ──
        QuroConnector("supabase", "Supabase", "data",
            "Postgres 数据库、认证与存储", "https://mcp.supabase.com/mcp", SignInType.OAUTH,
            "查询/执行 Supabase（Postgres/认证/存储/RLS）操作，注意官方建议只读模式接非生产库。",
            "supabase.com/docs"),
        QuroConnector("neon", "Neon", "data",
            "Serverless Postgres 分支", "https://mcp.neon.tech/mcp", SignInType.OAUTH,
            "管理 Neon serverless Postgres 的项目、数据库分支与连接。",
            "neon.tech/docs"),
        QuroConnector("mongodb", "MongoDB", "data",
            "Atlas 集群与聚合查询", "https://mcp.mongodb.com/mcp", SignInType.OAUTH,
            "查询/操作 MongoDB Atlas：集合、文档、查询与聚合管道。",
            "mongodb.com/docs"),
        QuroConnector("airtable", "Airtable", "data",
            "Base、表格与记录", "https://mcp.airtable.com/mcp", SignInType.OAUTH,
            "管理 Airtable 的 Base/Table/Record：查记录、建条目、改字段。",
            "airtable.com/developers"),
        QuroConnector("posthog", "PostHog", "data",
            "产品分析与 Feature Flag", "https://mcp.posthog.com/mcp", SignInType.OAUTH,
            "查询 PostHog 产品分析数据、管理 feature flag / experiment、做 SQL 即席查询。",
            "posthog.com/docs"),
        QuroConnector("amplitude", "Amplitude", "data",
            "产品分析与漏斗", "https://mcp.amplitude.com/mcp", SignInType.OAUTH,
            "查询 Amplitude 的产品分析图表、事件与漏斗数据。",
            "docs.amplitude.com"),
        // ── 设计 ──
        QuroConnector("canva", "Canva", "design",
            "设计创建与导出", "https://mcp.canva.com/mcp", SignInType.OAUTH,
            "在 Canva 里创建、查找、编辑与导出设计（海报/文档/演示）。",
            "canva.dev/docs"),
        QuroConnector("figma", "Figma", "design",
            "设计稿与节点读取", "https://mcp.figma.com/mcp", SignInType.OAUTH,
            "读取 Figma 文件结构、节点信息与设计变量，设计稿转代码辅助。",
            "developers.figma.com"),
        QuroConnector("webflow", "Webflow", "design",
            "站点样式与 CMS", "https://mcp.webflow.com/mcp", SignInType.OAUTH,
            "管理 Webflow 的站点、页面样式与 CMS 集合内容。",
            "developers.webflow.com"),
        QuroConnector("wix", "Wix", "design",
            "站点内容与业务数据", "https://mcp.wix.com/mcp", SignInType.OAUTH,
            "管理 Wix 站点的 CMS 集合、页面内容与业务数据。",
            "dev.wix.com"),
        // ── 通信 ──
        QuroConnector("slack", "Slack", "communication",
            "频道、消息与搜索", "https://mcp.slack.com/mcp", SignInType.OAUTH,
            "读写 Slack：查频道历史、发消息、搜对话（需工作区管理员批准应用）。",
            "docs.slack.dev"),
        QuroConnector("intercom", "Intercom", "communication",
            "客服会话管理", "https://mcp.intercom.com/mcp", SignInType.OAUTH,
            "管理 Intercom 的联系人、会话与客户消息，客服流程自动化。",
            "developers.intercom.com"),
        // ── 自动化 ──
        QuroConnector("zapier", "Zapier", "automation",
            "8000+ 应用自动化", "https://mcp.zapier.com/api/mcp/mcp", SignInType.OAUTH,
            "调用 Zapier 的自动化工作流与跨应用动作（一个端点接通数千 App）。",
            "platform.zapier.com"),
        QuroConnector("apify", "Apify", "automation",
            "爬虫与网页数据提取", "https://mcp.apify.com", SignInType.API_KEY,
            "运行 Apify 平台的爬虫 Actor、抓取与提取网页数据。",
            "docs.apify.com"),
        // ── 支付 ──
        QuroConnector("stripe", "Stripe", "payments",
            "支付、订阅与账单", "https://mcp.stripe.com", SignInType.OAUTH,
            "查询/操作 Stripe 的支付、订阅、发票与客户（涉及资金，只开真正需要的时候）。",
            "docs.stripe.com"),
        QuroConnector("paypal", "PayPal", "payments",
            "交易与争议管理", "https://mcp.paypal.com/mcp", SignInType.OAUTH,
            "查询/操作 PayPal 的交易、支付、订阅与争议处理。",
            "developer.paypal.com"),
        QuroConnector("square", "Square", "payments",
            "付款与订单管理", "https://mcp.squareup.com/mcp", SignInType.OAUTH,
            "管理 Square 的付款、订单、目录与客户数据。",
            "developer.squareup.com"),
        // ── 生活与出行 ──
        QuroConnector("exa", "Exa 搜索", "daily-life",
            "面向 AI 的语义搜索", "https://mcp.exa.ai/mcp", SignInType.NONE,
            "面向 AI 的语义搜索引擎：比通用搜索更适合「找某类网站/论文/公司」类查询。",
            "docs.exa.ai"),
        QuroConnector("amap", "高德地图", "daily-life",
            "地点、路线与路况", "https://mcp.amap.com/mcp", SignInType.API_KEY,
            "查地点/POI、驾车·步行·公交路线规划、实时路况与周边搜索。",
            "lbs.amap.com"),
        QuroConnector("didi", "滴滴出行", "daily-life",
            "打车路线与预估价", "https://mcp.didichuxing.com/mcp-servers", SignInType.API_KEY,
            "查询滴滴打车路线与预估价格（滴滴开放 MCP）。",
            ""),
        QuroConnector("mcdonalds", "麦当劳", "daily-life",
            "门店、菜单与活动", "https://mcp.mcd.cn", SignInType.API_KEY,
            "查询麦当劳中国门店、菜单与优惠活动（官方 MCP）。",
            ""),
        QuroConnector("luckin", "瑞幸咖啡", "daily-life",
            "门店与点单优惠", "https://gwmcp.lkcoffee.com/order/user/mcp", SignInType.API_KEY,
            "查询瑞幸咖啡门店、菜单与优惠信息（官方 MCP）。",
            ""),
    )

    fun byId(id: String): QuroConnector? = connectors.find { it.id == id }

    fun inCategory(categoryId: String): List<QuroConnector> =
        connectors.filter { it.category == categoryId }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 已连接（真实授权过）的连接器 id 集合。 */
    fun connectedIds(ctx: Context): Set<String> =
        prefs(ctx).getStringSet(K_CONNECTED, emptySet()).orEmpty()

    fun isConnected(ctx: Context, id: String): Boolean = connectedIds(ctx).contains(id)

    /** 标记连接 / 断开（真实授权完成后由 UI 调用）。 */
    fun setConnected(ctx: Context, id: String, on: Boolean) {
        val cur = connectedIds(ctx).toMutableSet()
        if (on) cur.add(id) else cur.remove(id)
        prefs(ctx).edit().putStringSet(K_CONNECTED, cur).apply()
    }

    /**
     * 生成注入 system prompt 的「已连接连接器」段落。
     *
     * 与应用中心的提示词目录不同：这里列出的连接器都有**真实工具调用能力**
     * （mcp_call 已可直达），措辞据此引导 AI 优先用 mcp_list_tools / mcp_call。
     */
    fun promptSection(ctx: Context): String {
        val connected = connectedIds(ctx).mapNotNull { byId(it) }
        if (connected.isEmpty()) return ""
        return buildString {
            append("## 已连接的连接器（真实 MCP 工具）\n")
            append("用户已在「连接器」页授权以下外部服务，它们的能力经 mcp_list_tools / mcp_call ")
            append("直接可调（先 mcp_list_tools 看该连接器暴露的工具，再 mcp_call 调用）：\n")
            connected.forEach { c ->
                append("- ").append(c.name)
                if (c.docDomain.isNotBlank()) append("（文档域 ").append(c.docDomain).append("）")
                append("：").append(c.aiNote)
                append('\n')
            }
            append("注意：连接器以用户授权的身份运行，只能做该服务 MCP 暴露的操作；")
            append("涉及资金（Stripe/PayPal/Square）与对外发送消息（Slack/Intercom）时先向用户确认。\n")
        }
    }
}
