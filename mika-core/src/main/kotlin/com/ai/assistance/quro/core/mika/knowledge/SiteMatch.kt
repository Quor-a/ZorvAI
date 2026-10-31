package com.ai.assistance.quro.core.mika.knowledge

/**
 * 主机名属于哪份站点知识:按条目的 domains,主机名等于某个域名或是它的子域名就算对上;
 * 几份都对上时取对上的域名最长的(最具体的,如 shop.example.com 胜过 example.com)。
 * 注入站点知识(KnowledgeStore.byDomain)和找站点命令(GuideCatalog.siteCommands)都用它,两边认的是同一份。
 */
object SiteMatch {
    /** [host] 对上 [domains] 里最长的那个域名的长度;对不上为 null。 */
    fun score(host: String, domains: List<String>): Int? {
        val h = host.lowercase().removePrefix("www.")
        return domains.map { it.lowercase() }.filter { h == it || h.endsWith(".$it") }.maxOfOrNull { it.length }
    }

    /** [items] 里对上 [host] 最具体的那些(并列的都留,顺序不变);都对不上为空。 */
    fun <T> best(host: String, items: List<T>, domains: (T) -> List<String>): List<T> {
        val scored = items.mapNotNull { i -> score(host, domains(i))?.let { i to it } }
        val top = scored.maxOfOrNull { it.second } ?: return emptyList()
        return scored.filter { it.second == top }.map { it.first }
    }
}
