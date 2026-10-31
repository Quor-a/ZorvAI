package com.ai.assistance.quro.core.mika.knowledge

enum class Surface { Android, Web, Desktop }

/** 一份 App(APP.md,[Surface.Android])或站点(SITE.md,[Surface.Web])知识。 */
data class KnowledgeEntry(
    val name: String,
    val display: String,
    val surface: Surface,
    val packages: List<String>,
    val domains: List<String>,
    val aliases: List<String>,
    val open: String?,
    val description: String,
    val body: String?,
    /** 同一 App 有多个包名时,别名到各自包名的对应(如“美团外卖”→外卖独立包)。 */
    val packageAliases: Map<String, String> = emptyMap(),
    /** 真机验证过、可用 open_link 直达的页面;头部写作 `links: ["名称 | uri", "名称 | uri | 包名", …]`。 */
    val links: List<Link> = emptyList(),
    /** 同一服务另一种运行面的条目名:App 条目写它的站点,站点条目写它的 App。 */
    val related: List<String> = emptyList(),
    /** 站点命令的声明(只有站点有);脚本正文由 [GuideCatalog.siteCommands] 取。 */
    val commands: List<SiteCommandDecl> = emptyList(),
    /** 正文附了 ZorvAI 自学、还没核实的 App 知识试用稿。 */
    val trial: Boolean = false,
) {
    /** 同一服务的 App 与站点共用的名字:站点关联了 App 时取 App 的名字。 */
    val service: String get() = if (surface == Surface.Web) related.firstOrNull() ?: name else name
}

/** 一条可直达的链接;网页链接要带 [packageName](由哪个 App 打开)。 */
data class Link(val name: String, val uri: String, val packageName: String? = null)
