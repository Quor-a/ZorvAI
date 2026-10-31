package com.ai.assistance.quro.core.mika.knowledge

import com.ai.assistance.quro.core.mika.prompt.Feedback
import java.io.File

private val GENERIC = setOf("app", "apps", "web", "www", "site", "browser")

// 太泛的中文词:只有 “打开/进入/去”+词 这种明确点名时才算命中(“设置一个闹钟”不算)
private val GENERIC_ZH = setOf(
    "设置", "浏览器", "搜索", "地图", "相机", "相册", "日历", "时钟", "天气", "音乐",
    "视频", "文件", "短信", "电话", "邮件", "商城", "应用", "手机",
)
private val NAMING_PREFIXES = listOf("打开", "进入", "去")

// 常见的系统类 App 名。品牌词后面紧跟着它(「某牌应用市场」)时,说的是这个 App,不是品牌自己的商城等条目
private val APP_KINDS = listOf(
    "应用市场", "应用商店", "浏览器", "钱包", "日历", "时钟", "相机", "图库", "文件管理", "主题", "云空间",
    "运动健康", "计算器", "便签", "备忘录", "录音机", "游戏中心", "手机管家", "天气",
)

/**
 * 内置知识 + 用户补充:[notes] 按条目名(App 名或站点域名),附在对应条目正文后面并标明是用户自己写的;
 * [learnedNotes] 是 ZorvAI 自学的 App 知识补充,附在最后并标明试用中还是已核实。
 * 匹配是确定性的字符串匹配,不调用模型。
 */
class KnowledgeStore(private val bundled: List<KnowledgeEntry>, notes: Map<String, String> = emptyMap(),
    learnedNotes: Map<String, LearnedNote> = emptyMap()) {
    private val entries: List<KnowledgeEntry> = bundled.map { e ->
        val personal = notes[e.name]
        val learned = learnedNotes[e.name]
        if (personal == null && learned == null) return@map e
        e.copy(body = listOfNotNull(e.body, personal?.let { "$NOTES_HEADER\n$it" },
            learned?.let { Feedback.format(if (it.trial) "knowledge.learned_trial" else "knowledge.learned", "notes" to it.text) }).joinToString("\n\n"),
            trial = learned?.trial == true)
    }

    /** 条目 [name] 的内置正文本身(不带用户补充和自学补充);起草自学补充时拿它当「现有的说明」。 */
    fun builtIn(name: String): String? = bundled.firstOrNull { it.name == name && it.body != null }?.body

    private fun pick(c: List<KnowledgeEntry>): KnowledgeEntry? = c.firstOrNull { it.body != null } ?: c.firstOrNull()

    fun byPackage(pkg: String): KnowledgeEntry? = pick(entries.filter { pkg in it.packages })

    /** 主机名对上的站点知识(最具体的域名优先,见 [SiteMatch])。 */
    fun byDomain(host: String): KnowledgeEntry? = pick(SiteMatch.best(host, entries) { it.domains })

    /**
     * 命中任务文字的条目。已安装 App 的条目排最前,其次有正文的,再按命中词长度(越长越具体)。
     * [installed] 是已安装 App(包名, 显示名):命中词后面紧跟着某个已安装 App 的名字时,
     * 说的是那个 App(如「某牌」+「应用市场」),不算命中。
     */
    fun matchTask(text: String, limit: Int = 2, installed: List<Pair<String, String>> = emptyList()): List<KnowledgeEntry> {
        val t = text.lowercase()
        val packages = installed.map { it.first }.toSet()
        val followers = (APP_KINDS + installed.map { it.second.trim().lowercase() }.filter { it.length >= 2 }).distinct()
        return entries.mapNotNull { e -> score(e, t, followers)?.let { e to it } }
            .sortedWith(compareBy({ it.first.packages.none(packages::contains) }, { it.first.body == null }, { -it.second }))
            .map { it.first }
            .distinctBy { it.service } // 同一服务的 App 与站点只给排序靠前的那份;另一份进入该 App/站点时再按包名/域名给
            .take(limit)
    }

    /** “小红书”→ com.xingin.xhs;按名称/显示名/别名精确匹配(忽略大小写),只看安卓条目。 */
    fun packageFor(nameOrAlias: String): String? {
        val q = nameOrAlias.trim().lowercase()
        if (q.isEmpty()) return null
        return entries.filter { it.packages.isNotEmpty() }
            .filter { e -> (listOf(e.name, e.display) + e.aliases).any { it.lowercase() == q } }
            .let { c -> c.firstOrNull { it.body != null } ?: c.firstOrNull() }
            ?.let { e -> e.packageAliases.entries.firstOrNull { it.key.lowercase() == q }?.value ?: e.packages.first() }
    }

    private fun score(e: KnowledgeEntry, text: String, followers: List<String>): Int? {
        var best = -1
        val terms = (listOf(e.display, e.name) + e.aliases).map { it.trim().lowercase() }
        // 条目自己的名字(如「某牌商城」)不算「另一个 App」
        val others = followers.filter { f -> terms.none { it.endsWith(f) } }
        for (x in terms) {
            if (x.length < 2 || x in GENERIC || (x.all { it.isDigit() } && x.length < 3)) continue
            val hit = if (x in GENERIC_ZH) NAMING_PREFIXES.any { text.contains(it + x) } else containsTerm(text, x, others)
            if (hit) best = maxOf(best, x.length)
        }
        for (d in e.domains) {
            val x = d.trim().lowercase()
            if (x.length >= 3 && text.contains(x)) best = maxOf(best, x.length)
        }
        return best.takeIf { it >= 0 }
    }

    // 英文/数字词要求单词边界,免得 "mm" 命中 "command";中文直接子串
    // [others] 是别的 App 的名字:命中词后面紧跟着它时,命中词只是那个 App 名字的一部分
    private fun containsTerm(text: String, term: String, others: List<String> = emptyList()): Boolean {
        var from = 0
        while (true) {
            val i = text.indexOf(term, from)
            if (i < 0) return false
            val before = text.getOrNull(i - 1)
            val after = text.getOrNull(i + term.length)
            val leftOk = !term.first().isAsciiAlnum() || before == null || !before.isAsciiAlnum()
            val rightOk = !term.last().isAsciiAlnum() || after == null || !after.isAsciiAlnum()
            val namesOther = others.any { text.startsWith(it, i + term.length) }
            if (leftOk && rightOk && !namesOther) return true
            from = i + 1
        }
    }

    private fun Char.isAsciiAlnum() = this in 'a'..'z' || this in '0'..'9'

    companion object {
        /** 附在 App/站点知识后面的用户补充的标题。 */
        const val NOTES_HEADER = "## The user's own notes (written by the user; when they differ from the text above or the current screen, the current screen takes precedence)"

        /**
         * 读构建时生成的索引里的 App(APP.md,安卓)与站点(SITE.md,网页)知识;正文取主文件去掉头部,
         * App 与站点通过 [KnowledgeEntry.related] 互相关联。
         */
        fun loadBundled(loader: ClassLoader = KnowledgeStore::class.java.classLoader): List<KnowledgeEntry> {
            val catalog = GuideCatalog.loadBundled(loader)
            val infos = catalog.items.associateBy { it.kind to it.name }
            return catalog.rows.filter { it.kind == KnowledgeIndex.APP || it.kind == KnowledgeIndex.SITE }.map { e ->
                val app = e.kind == KnowledgeIndex.APP
                KnowledgeEntry(
                    name = e.name, display = e.display, surface = if (app) Surface.Android else Surface.Web,
                    packages = e.packages, domains = e.domains, aliases = e.aliases,
                    open = e.open, description = e.description,
                    body = catalog.fullText(infos.getValue(e.kind to e.name))?.trim()?.ifEmpty { null },
                    packageAliases = e.packageAliases,
                    links = e.links.map { raw -> raw.split('|').map(String::trim).let { Link(it[0], it[1], it.getOrNull(2)) } },
                    related = if (app) e.sites else e.apps,
                    commands = e.commands,
                )
            }
        }

        /** 用户对某个 App/站点的补充:`<dir>/apps/<id>/NOTES.md`、`<dir>/sites/<id>/NOTES.md`,按条目名。 */
        fun loadNotes(dir: File): Map<String, String> =
            listOf("apps", "sites").flatMap { sub ->
                File(dir, sub).listFiles { f -> f.isDirectory }.orEmpty().sortedBy { it.name }.mapNotNull { d ->
                    File(d, "NOTES.md").takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() }?.let { d.name to it }
                }
            }.toMap()
    }
}
