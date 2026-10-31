package com.ai.assistance.quro.core.mika.agent

import com.ai.assistance.quro.core.mika.knowledge.KNOWLEDGE_KINDS
import com.ai.assistance.quro.core.mika.knowledge.KnowledgeIndex
import com.ai.assistance.quro.core.mika.knowledge.PERSONAL_KIND
import com.ai.assistance.quro.core.mika.knowledge.RECOMMEND_MIN_SCORE
import com.ai.assistance.quro.core.mika.knowledge.SKILL_KINDS
import com.ai.assistance.quro.core.mika.knowledge.GuideCatalog
import com.ai.assistance.quro.core.mika.knowledge.GuideInfo
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import com.ai.assistance.quro.core.mika.tool.Verb

internal const val SKILL_INDEX_HEADER = "All skills you have, one line each (when one fits this task, read its full text with skill_read before acting on it):"
internal const val KNOWLEDGE_INDEX_HEADER = "App and website knowledge you can refer to (read the full text with knowledge_read when needed):"
private const val RECOMMEND_COUNT = 3

/**
 * 任务开始时的索引(开局上下文里写一次,中途不改):
 * App/站点知识按任务文字检索,沿用原来的取法(与技能一起取前 3 条,只留知识);
 * 技能不挑,全部列出——内置在前、个人技能在后,各按名字排,每份一行(id、显示名、描述),
 * 用不用、用哪份由模型按描述判断,要用时自己用 skill_read 读全文。
 */
internal object GuideRecommender {
    fun id(info: GuideInfo) = "${info.kind}:${info.name}"
    fun line(info: GuideInfo) = "- ${id(info)}(${info.display}):${info.description}"

    /** 知识与技能各列在自己的标题下;两样都没有为 null。个人技能只有在用和试用的(见 [GuideCatalog.items])。 */
    fun index(catalog: GuideCatalog, task: String): String? {
        val knowledge = catalog.find(task, limit = RECOMMEND_COUNT, minScore = RECOMMEND_MIN_SCORE).filter { it.kind in KNOWLEDGE_KINDS }
        val skills = catalog.items.filter { it.kind in SKILL_KINDS }.sortedWith(compareBy({ it.kind != KnowledgeIndex.SKILL }, { it.name }))
        return listOf(KNOWLEDGE_INDEX_HEADER to knowledge, SKILL_INDEX_HEADER to skills).filter { it.second.isNotEmpty() }
            .takeIf { it.isNotEmpty() }
            ?.joinToString("\n") { (header, items) -> header + "\n" + items.joinToString("\n", transform = ::line) }
    }

    /** skill_read 成功读了个人技能时给出技能名。 */
    fun viewedPersonal(verb: Verb?, content: JsonObject): String? {
        if (verb != Verb.SkillRead || content["ok"] != JsonPrimitive(true)) return null
        if ((content["kind"] as? JsonPrimitive)?.contentOrNull != PERSONAL_KIND) return null
        return (content["name"] as? JsonPrimitive)?.contentOrNull
    }
}
