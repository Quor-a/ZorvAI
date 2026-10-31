package com.ai.assistance.quro.core.mika.knowledge

import com.ai.assistance.quro.core.mika.net.UrlHost

/**
 * 选渠道用的事实:已装的包名、在 ZorvAI 里登录着的站点主机名、内置浏览器能不能用、
 * 最近在 ZorvAI 里碰到过人机验证或访问拦截的站点主机名。
 */
data class ChannelFacts(
    val installed: Set<String>,
    val loggedInSites: Set<String>,
    val webAvailable: Boolean,
    val challengedSites: Set<String> = emptySet(),
)

/** 给任务开头的一行「可用渠道」:代码只给事实,选哪条由模型按网页规则决定。 */
object ChannelAdvisor {
    private const val HEADER = "Available channels: "
    /** 只列排在前面的几个,和每任务至多注入的参考资料份数相当。 */
    private const val MAX_CHANNELS = 3

    /**
     * 例:「可用渠道:小红书 — App:已安装(com.xingin.xhs),有 App 做法;网页:xiaohongshu.com,有网页做法,在 ZorvAI 里登录过(m.xiaohongshu.com)」。
     * [entries] 按匹配顺序,只取前 [MAX_CHANNELS] 个渠道。没有可说的为 null。
     */
    fun line(entries: List<KnowledgeEntry>, skills: GuideCatalog?, facts: ChannelFacts): String? {
        val parts = entries.groupBy { it.service }.mapNotNull { (name, same) -> channel(name, same, skills, facts) }.take(MAX_CHANNELS)
        if (parts.isEmpty()) return null
        return HEADER + parts.joinToString(" | ")
    }

    private fun channel(name: String, same: List<KnowledgeEntry>, skills: GuideCatalog?, facts: ChannelFacts): String? {
        val packages = same.flatMap { it.packages }.distinct()
        // App 条目关联的站点名就是站点的主域名
        val domains = same.flatMap { it.domains + if (it.surface == Surface.Android) it.related else emptyList() }.map { it.trim().lowercase().removePrefix("www.") }.filter { it.isNotEmpty() }.distinct()
        val app = if (packages.isEmpty()) null else {
            val state = packages.firstOrNull { it in facts.installed }?.let { "app: installed ($it)" } ?: "app: not installed"
            if (skills?.hasRoute(name, "android") == true) "$state, known way to do it in the app" else state
        }
        val web = domains.firstOrNull()?.takeIf { facts.webAvailable }?.let { d ->
            fun ofSite(hosts: Set<String>) = hosts.firstOrNull { s -> domains.any { dd -> UrlHost.sameSite("https://$dd", "https://$s") } }
            val loggedIn = ofSite(facts.loggedInSites)
            val challenged = ofSite(facts.challengedSites)
            val notes = listOfNotNull(
                "known way to do it on the web".takeIf { skills?.hasRoute(name, "browser") == true },
                loggedIn?.let { "signed in within ZorvAI ($it)" },
                challenged?.let { "recently hit a verification or block here ($it); the user can clear this site's data in settings" },
            )
            (listOf("web: $d") + notes).joinToString(", ")
        }
        if (app == null && web == null) return null
        return "${same.first().display} — " + listOfNotNull(app, web).joinToString("; ")
    }
}
