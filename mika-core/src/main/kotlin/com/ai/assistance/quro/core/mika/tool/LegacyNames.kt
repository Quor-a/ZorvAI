package com.ai.assistance.quro.core.mika.tool

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 改名前的工具名 → 现在的名字,按规则从登记的工具生成(不手写对照表):
 * 能力的工具名是 `<能力>_<其余>`;几块屏都有的动词以前只有一个不带前缀的名字(`tap`),现在每块屏一个;
 * 只有一块屏有的,以前的名字是 [formerPrefix] 里那个能力的旧前缀加其余(手机没有前缀,`launch_app`;网页是 `browser_`);
 * 其余部分也改过的写在 [renamed] 里(现在的名字 → 以前的名字);
 * [retired] 是去掉了、没有替代工具的旧名(旧名 → 现在该怎么做的说法),改写成那句说法。
 * [moved] 是不按上面规则、整个换了名字的(旧名 → 现在的名字;一个拆成几个的有几个)。
 *
 * 只用于读旧东西:存下的经验路线、运行记录的显示、载入上下文的个人技能与做法、模型调用旧名时的提示。
 * 调用旧名的工具不会被悄悄换成新名执行。
 */
class LegacyNames(
    private val index: ToolIndex,
    formerPrefix: Map<String, String>,
    renamed: Map<String, String> = emptyMap(),
    private val retired: Map<String, String> = emptyMap(),
    moved: Map<String, List<String>> = emptyMap(),
) {
    private val table: Map<String, List<String>>

    init {
        val infos = index.names.mapNotNull { index[it] }
        val out = LinkedHashMap<String, MutableList<String>>()
        for (t in infos) {
            // 新增的手机命令没有无前缀的历史名称。
            if (t.verb in setOf(Verb.Query, Verb.Collect, Verb.PhoneRun, Verb.PhoneFind, Verb.PhoneExtract,
                    Verb.PhoneDialog, Verb.PhoneEvent, Verb.PhoneGesture, Verb.PhoneCommand)) continue
            val cap = t.capability ?: continue
            val prefix = formerPrefix[cap] ?: continue
            if (!t.name.startsWith("${cap}_")) continue
            val rest = t.name.removePrefix("${cap}_")
            // 别的屏也有同一个动词、同样的其余部分:以前是一个不带前缀的共用名字
            val shared = infos.any { o -> o !== t && o.capability != cap && o.capability in formerPrefix && o.verb == t.verb && o.name == "${o.capability}_$rest" }
            val old = renamed[t.name] ?: if (shared) rest else prefix + rest
            if (old != t.name && old !in index.names) out.getOrPut(old) { mutableListOf() } += t.name
        }
        for ((old, news) in moved) news.filter { it in index.names }.takeIf { it.isNotEmpty() }?.let { out.getOrPut(old) { mutableListOf() } += it }
        table = out
    }

    /** 旧名对应的现在的名字(几块屏都有的动词有几个);不是旧名为空。 */
    fun replacements(old: String): List<String> =
        table[old].orEmpty()

    /**
     * 模型调用旧名 [old] 时该提示的新名:一个拆成知识、技能两个的,按参数里的 kind(或 name 的 `kind:` 前缀)挑出对应的那个,
     * 看不出来时两个都给。
     */
    fun replacements(old: String, args: JsonObject): List<String> {
        val all = replacements(old)
        if (all.size < 2) return all
        val kind = args.text("kind") ?: args.text("name")?.substringBefore(':', "")
        val verbs = when (kind) {
            "app", "site" -> setOf(Verb.KnowledgeSearch, Verb.KnowledgeRead)
            "skill", "personal" -> setOf(Verb.SkillSearch, Verb.SkillRead)
            else -> return all
        }
        return all.filter { index.verb(it) in verbs }.ifEmpty { all }
    }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** 去掉了、没有替代工具的旧名现在该怎么做;不是这种旧名为 null。 */
    fun retiredAs(old: String): String? = retired[old]

    /** 按名字一一登记的旧名。 */
    val names: Set<String> get() = table.keys

    /** 旧名在 [accept] 里的那个新名(例如只认能记进路线的);不是旧名、或没有合适的为 null。 */
    fun resolve(old: String, accept: (String) -> Boolean = { true }): String? = replacements(old).firstOrNull(accept)

    /**
     * 文字里提到的旧工具名换成现在的:带下划线的旧名(`launch_app`、`browser_navigate`)在哪都换;
     * 本身是英文词的(`tap`、`back`)只换反引号里的。几块屏都有的写成「`phone_tap` / `web_tap`」。
     */
    fun rewrite(text: String): String = renamedIn(retiredIn(text))

    /**
     * 去掉了的旧名换成现在该怎么做的那句话,连同前面「调用 / call」这个动作一起换:反引号里的在哪都换;
     * 不带反引号的(本身可能是英文词)只在它是工具名的位置换:紧跟在「调用 / call」后面,或夹在中文里。
     */
    private fun retiredIn(text: String): String {
        var out = text
        for ((old, instead) in retired) {
            val name = Regex.escape(old)
            out = Regex("(?:(?i:call)\\s+|调用\\s*)?`$name`").replace(out, instead)
            out = Regex("(?:(?i:call)\\s+|调用\\s*)(?<![A-Za-z0-9_])$name(?![A-Za-z0-9_])").replace(out, instead)
            out = Regex("(?<=[\\u4e00-\\u9fff])\\s*$name(?![A-Za-z0-9_])|(?<![A-Za-z0-9_])$name\\s*(?=[\\u4e00-\\u9fff])").replace(out) { " $instead " }
        }
        return out
    }

    private fun renamedIn(text: String): String {
        if (table.isEmpty()) return text
        fun joined(news: List<String>, tick: Boolean): String {
            fun t(s: String) = if (tick) "`$s`" else s
            return news.joinToString(" / ") { t(it) }
        }
        val ticked = TICKED.replace(text) { m -> replacements(m.groupValues[1]).takeIf { it.isNotEmpty() }?.let { joined(it, true) } ?: m.value }
        return WORD.replace(ticked) { m ->
            val w = m.value
            if ('_' !in w) w else replacements(w).takeIf { it.isNotEmpty() }?.let { joined(it, false) } ?: w
        }
    }

    private companion object {
        val TICKED = Regex("`([a-z][a-z0-9_]*)`")
        val WORD = Regex("(?<![A-Za-z0-9_`])[a-z][a-z0-9]*(?:_[a-z0-9]+)+(?![A-Za-z0-9_`])")
    }
}
