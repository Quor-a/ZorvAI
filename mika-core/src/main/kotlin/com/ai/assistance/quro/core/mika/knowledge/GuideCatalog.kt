package com.ai.assistance.quro.core.mika.knowledge

import com.ai.assistance.quro.core.mika.skills.PersonalSkills
import com.ai.assistance.quro.core.mika.skills.SkillStatus

/** 内置的 App 知识(kind `app`)、站点知识(`site`)、技能(`skill`),或用户的个人技能(`personal`);[files] 是可读的文件(主文件在前,其余是附件)。 */
data class GuideInfo(
    val kind: String,
    val name: String,
    val display: String,
    val description: String,
    val aliases: List<String>,
    val terms: List<String>,
    val files: List<String>,
    /** 关联的 App / 站点名。 */
    val apps: List<String> = emptyList(),
    val sites: List<String> = emptyList(),
)

sealed interface GuideView {
    data class Found(
        val info: GuideInfo, val file: String, val content: String, val truncated: Boolean, val totalChars: Int,
    ) : GuideView

    data class Failed(val error: String, val message: String) : GuideView
}

private const val SKILL_FILE = "SKILL.md"
/** 文档可能提到本机没有的工具;在注入和 knowledge_read / skill_read 的边界处提醒。 */
const val UNAVAILABLE_TOOLS_NOTE = "(Note: scripts mentioned in the text and tools this phone does not have are not available; use only the tools in your tool list.)"
/** 设备支持沙箱时的附注:技能脚本按清单可以跑。 */
const val SANDBOX_TOOLS_NOTE = "(Note: scripts marked runnable can be run with run_bundled_script; other scripts and tools this phone does not have are not available.)"

/** 注入参考资料、knowledge_read / skill_read 时的附注,按沙箱工具是否提供。 */
fun toolsNote(sandboxAvailable: Boolean): String = if (sandboxAvailable) SANDBOX_TOOLS_NOTE else UNAVAILABLE_TOOLS_NOTE
const val VIEW_LIMIT = 12_000
const val FIND_LIMIT = 10
/** 任务开始按任务文字给 App/站点知识索引的得分门槛:名称/显示名/别名至少部分对上。 */
const val RECOMMEND_MIN_SCORE = 60
const val PERSONAL_KIND = "personal"
/** App 与网站知识(knowledge_search / knowledge_read 管的);技能是另一组(内置技能与个人技能)。 */
val KNOWLEDGE_KINDS = setOf(KnowledgeIndex.APP, KnowledgeIndex.SITE)
val SKILL_KINDS = setOf(KnowledgeIndex.SKILL, PERSONAL_KIND)

/**
 * 只读的目录:按名称/别名/描述查找、读取文本。内置部分来自构建时生成的 [KnowledgeIndex.RESOURCE] 与各自的目录,
 * 再合并用户的个人技能(kind = `personal`,在用的才算;同名整体覆盖内置)。
 */
class GuideCatalog private constructor(
    private val loader: ClassLoader,
    internal val rows: List<IndexEntry>,
    private val personal: PersonalSkills? = null,
    /** 个人技能正文载入时做的改写(改名前的工具名换成现在的);默认原样。 */
    private val rewrite: (String) -> String = { it },
) {
    private val bundledItems: List<GuideInfo> = rows.map(::info)

    /** 个人技能每次现读:收下、停用随时发生。 */
    val items: List<GuideInfo>
        get() {
            val mine = personalEntries().map { it.info }
            val names = mine.map { it.name }.toSet()
            return mine + bundledItems.filter { it.name !in names }
        }

    /**
     * [route] 为 `android`:有名为 [name] 的 App 知识;为 `browser`:有名为 [name] 的站点知识,或名为 [name] 的 App 关联了站点。
     */
    fun hasRoute(name: String, route: String): Boolean = when (route) {
        "android" -> bundledItems.any { it.kind == KnowledgeIndex.APP && it.name == name }
        "browser" -> bundledItems.any { it.name == name && (it.kind == KnowledgeIndex.SITE || (it.kind == KnowledgeIndex.APP && it.sites.isNotEmpty())) }
        else -> false
    }

    /**
     * 评分:名称/显示名/别名整词相同 > 包含(或整个出现在查询词里) > 检索词 > 描述;每个空白分隔的词分别计分,
     * 至少一个词命中、且总分不低于 [minScore] 才返回。[among] 给了时只查这几种(见 [KNOWLEDGE_KINDS]、[SKILL_KINDS])。
     */
    fun find(query: String, kind: String? = null, limit: Int = FIND_LIMIT, minScore: Int = 0, among: Set<String>? = null): List<GuideInfo> {
        val tokens = query.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return emptyList()
        return items.filter { (kind == null || it.kind == kind) && (among == null || it.kind in among) }
            .mapNotNull { s -> score(s, tokens).takeIf { it > 0 && it >= minScore }?.let { s to it } }
            .sortedByDescending { it.second }
            .take(limit).map { it.first }
    }

    private fun score(s: GuideInfo, tokens: List<String>): Int {
        val names = (listOf(s.name, s.display) + s.aliases).map { it.lowercase() }
        val terms = s.terms.map { it.lowercase() }
        val desc = s.description.lowercase()
        return tokens.map { t ->
            when {
                names.any { it == t } -> 100
                // 中文目标通常不带空格,整句是一个词:名字出现在句子里也算部分对上
                names.any { it.contains(t) || (it.length >= 2 && t.contains(it)) } -> 60
                terms.any { it == t } -> 50
                terms.any { it.contains(t) } -> 40
                desc.contains(t) -> 20
                else -> 0
            }
        }.sum()
    }

    /** [name] 可写 `app:meituan` / `site:jd.com` / `skill:documents` / `personal:xxx`,也可只写名字(重名时报歧义)。 */
    /** [note] 是放在正文前的附注(见 [toolsNote])。[among] 给了时只读这几种,名字是另一组的时说清该用哪个工具读。 */
    fun view(name: String, file: String? = null, note: String = UNAVAILABLE_TOOLS_NOTE, among: Set<String>? = null): GuideView {
        val n = name.trim()
        val prefixed = n.substringBefore(':', "").takeIf { it in KnowledgeIndex.KINDS || it == PERSONAL_KIND }
        val id = if (prefixed != null) n.substringAfter(':') else n
        val named = items.filter { it.name == id && (prefixed == null || it.kind == prefixed) }
        val hits = named.filter { among == null || it.kind in among }
        val knowledge = among == KNOWLEDGE_KINDS
        val info = when (hits.size) {
            0 -> return GuideView.Failed(if (knowledge) "knowledge_not_found" else "skill_not_found", when {
                named.isNotEmpty() && knowledge -> "'$n' is a skill, not App or website knowledge; read it with skill_read."
                named.isNotEmpty() -> "'$n' is App or website knowledge, not a skill; read it with knowledge_read."
                knowledge -> "No App or website knowledge named '$n'. Use knowledge_search to find it."
                else -> "No skill named '$n'. Use skill_search to find it."
            })
            1 -> hits.single()
            else -> return GuideView.Failed(if (knowledge) "ambiguous_name" else "ambiguous_skill",
                "'$n' exists as ${hits.joinToString { it.kind }}; use ${hits.joinToString(" or ") { "${it.kind}:${it.name}" }}.")
        }
        val main = info.files.first()
        val path = (file?.trim()?.takeIf { it.isNotEmpty() } ?: main).removePrefix("./")
        if (info.kind == PERSONAL_KIND) return viewPersonal(info, path)
        if (path !in info.files)
            return GuideView.Failed("file_not_found", "No file '$path' in ${info.kind}:${info.name}. Available: ${info.files.joinToString()}.")
        val text = (if (path == main) fullText(info) else read(info, path))
            ?: return GuideView.Failed("file_not_found", "File '$path' could not be read.")
        val cut = text.length > VIEW_LIMIT
        return GuideView.Found(info, path, note + "\n" + (if (cut) text.take(VIEW_LIMIT) + "\n…(truncated; use file_path to view the other files)" else text), cut, text.length)
    }

    // 坏掉的个人技能明确报错,不回退到同名内置技能
    private fun viewPersonal(info: GuideInfo, path: String): GuideView {
        if (path != SKILL_FILE) return GuideView.Failed("file_not_found", "Personal skill ${info.name} has only $SKILL_FILE.")
        val body = personalEntries().find { it.info.name == info.name }?.body
            ?: return GuideView.Failed("personal_skill_invalid",
                "The user's personal skill '${info.name}' is damaged and cannot be read; do not rely on it. The user can fix or delete it in Memory & skills.")
        val cut = body.length > VIEW_LIMIT
        return GuideView.Found(info, SKILL_FILE, if (cut) body.take(VIEW_LIMIT) + "\n…(truncated)" else body, cut, body.length)
    }

    /** 主文件(APP.md / SITE.md / SKILL.md)的正文,去掉头部。坏掉的个人技能为 null。 */
    fun fullText(info: GuideInfo): String? {
        if (info.kind == PERSONAL_KIND) return personalEntries().find { it.info.name == info.name }?.body
        return read(info, KnowledgeIndex.fileOf(info.kind))
    }

    private fun read(info: GuideInfo, file: String): String? =
        resource("${KnowledgeIndex.dirOf(info.kind)}/${info.name}/$file")?.let { FrontMatter.parse(it.toString(Charsets.UTF_8)).second }

    /** 在沙箱里的目录(`/opt/quro/skills` 之下),也是 run_bundled_script 的 source 参数,如 `skills/documents`、`skills/research`。 */
    fun scriptSource(info: GuideInfo): String = "${KnowledgeIndex.dirOf(info.kind).substringAfterLast('/')}/${info.name}"

    /**
     * 要同步进沙箱的脚本:每个带 `scripts/manifest.json` 的条目,清单本身加上清单里列出的文件。
     * 键是 `/opt/quro/skills` 之下的相对路径。
     */
    fun scriptFiles(): Map<String, ByteArray> {
        val out = sortedMapOf<String, ByteArray>()
        for (info in bundledItems) {
            val src = "${KnowledgeIndex.dirOf(info.kind)}/${info.name}"
            val manifest = resource("$src/scripts/manifest.json") ?: continue
            val dir = scriptSource(info)
            out["$dir/scripts/manifest.json"] = manifest
            for (e in ScriptManifest.parse(manifest.toString(Charsets.UTF_8))) resource("$src/${e.file}")?.let { out["$dir/${e.file}"] = it }
        }
        return out
    }

    /** 清单里标为可运行(且是能跑的脚本类型)的脚本,`目录/文件`,如 `skills/documents/scripts/extract_pdf.py`。 */
    fun runnableScripts(): Set<String> {
        val out = sortedSetOf<String>()
        for (info in bundledItems) {
            val manifest = resource("${KnowledgeIndex.dirOf(info.kind)}/${info.name}/scripts/manifest.json") ?: continue
            for (e in ScriptManifest.parse(manifest.toString(Charsets.UTF_8))) {
                if (e.runnable && ScriptManifest.interpreterFor(e.file) != null) out += "${scriptSource(info)}/${e.file}"
            }
        }
        return out
    }

    /** 内置站点知识里有没有声明站点命令(没有就不提供运行站点命令的工具)。 */
    fun hasSiteCommands(): Boolean = rows.any { it.kind == KnowledgeIndex.SITE && it.commands.isNotEmpty() }

    /**
     * 主机 [host] 所属站点声明的站点命令,带脚本正文;没有为空。站点和注入站点知识时按同一规则认([SiteMatch],最具体的域名优先),
     * 认出的站点没有命令就是没有,不退到别的站点。
     */
    fun siteCommands(host: String): List<SiteCommand> {
        val site = SiteMatch.best(host, rows.filter { it.kind == KnowledgeIndex.SITE }) { it.domains }.firstOrNull()
            ?: return emptyList()
        val dir = "${KnowledgeIndex.dirOf(KnowledgeIndex.SITE)}/${site.name}"
        return site.commands.mapNotNull { d ->
            resource("$dir/${d.file}")?.toString(Charsets.UTF_8)?.let { SiteCommand(site.name, d, SiteCommandFile.parse(d.file, it).body) }
        }
    }

    /** 主机 [host] 所属站点知识写的 pace(认站点的规则同 [siteCommands]);没有为 null。 */
    fun sitePace(host: String): String? =
        SiteMatch.best(host, rows.filter { it.kind == KnowledgeIndex.SITE }) { it.domains }.firstOrNull()?.pace

    /** 包名 [packageName] 所属 App 知识写的 pace(包名在它的 packages 里);没有为 null。 */
    fun appPace(packageName: String): String? =
        rows.firstOrNull { it.kind == KnowledgeIndex.APP && packageName in it.packages }?.pace

    private fun resource(rel: String): ByteArray? = loader.getResourceAsStream(rel)?.use { it.readBytes() }

    /** [body] 为 null:SKILL.md 头部缺失、名字对不上或正文为空。 */
    private class PersonalEntry(val info: GuideInfo, val body: String?)

    private fun personalEntries(): List<PersonalEntry> =
        personal?.list().orEmpty().filter { it.status == SkillStatus.active || it.status == SkillStatus.trial }.map { s ->
            // 改名前写下的工具名换成现在的,模型读到的都是能用的名字
            val (meta, body) = FrontMatter.parse(s.markdown).let { (m, b) -> m to rewrite(b) }
            fun one(k: String) = meta[k] as? String
            fun many(k: String) = (meta[k] as? List<*>)?.filterIsInstance<String>() ?: listOfNotNull(one(k)?.takeIf { it.isNotEmpty() })
            val valid = one("name") == s.name && body.isNotBlank()
            PersonalEntry(
                GuideInfo(
                    kind = PERSONAL_KIND, name = s.name, display = one("display")?.takeIf { it.isNotEmpty() } ?: s.name,
                    description = (if (s.status == SkillStatus.trial) "[unverified: check the result when trying it] " else "") + (one("description") ?: ""), aliases = many("aliases"),
                    terms = many("apps") + many("domains"), files = listOf(SKILL_FILE),
                    apps = many("apps"), sites = many("sites") + many("domains"),
                ),
                body.takeIf { valid }?.let { if (s.status == SkillStatus.trial) "[Unverified personal skill draft: use it only as a trial; the result must be checked.]\n$it" else it },
            )
        }

    companion object {
        fun loadBundled(loader: ClassLoader = GuideCatalog::class.java.classLoader): GuideCatalog {
            val rows = loader.getResourceAsStream(KnowledgeIndex.RESOURCE)?.use { KnowledgeIndex.decode(it.readBytes().toString(Charsets.UTF_8)) }
            return GuideCatalog(loader, rows.orEmpty())
        }

        /** 内置目录 + 个人技能;[rewrite] 是个人技能正文载入时的改写(改名前的工具名换成现在的)。 */
        fun withPersonal(bundled: GuideCatalog, personal: PersonalSkills, rewrite: (String) -> String = { it }): GuideCatalog =
            GuideCatalog(bundled.loader, bundled.rows, personal, rewrite)

        private fun info(e: IndexEntry) = GuideInfo(
            kind = e.kind, name = e.name, display = e.display, description = e.description,
            aliases = e.aliases, terms = e.terms, files = listOf(KnowledgeIndex.fileOf(e.kind)) + e.files,
            apps = e.apps, sites = e.sites,
        )
    }
}
