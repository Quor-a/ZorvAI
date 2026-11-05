package com.ai.assistance.quro.core.mika.general

import com.ai.assistance.quro.core.mika.agent.DO_STEPS_TOOL
import com.ai.assistance.quro.core.mika.knowledge.FrontMatter
import com.ai.assistance.quro.core.mika.knowledge.PERSONAL_KIND
import com.ai.assistance.quro.core.mika.memory.SensitiveFilter
import com.ai.assistance.quro.core.mika.prompt.Feedback
import com.ai.assistance.quro.core.mika.prompt.Prompts
import com.ai.assistance.quro.core.mika.skills.PersonalSkill
import com.ai.assistance.quro.core.mika.skills.PersonalSkills
import com.ai.assistance.quro.core.mika.skills.SkillDrafter
import com.ai.assistance.quro.core.mika.skills.SkillStatus
import com.ai.assistance.quro.core.mika.tool.AgentTool
import com.ai.assistance.quro.core.mika.tool.Args
import com.ai.assistance.quro.core.mika.tool.Risk
import com.ai.assistance.quro.core.mika.tool.ToolContext
import com.ai.assistance.quro.core.mika.tool.ToolDef
import com.ai.assistance.quro.core.mika.tool.ToolExecution
import com.ai.assistance.quro.core.mika.tool.Verb
import com.ai.assistance.quro.core.mika.tool.fail
import com.ai.assistance.quro.core.mika.tool.ok
import com.ai.assistance.quro.core.mika.tool.str
import kotlinx.serialization.json.put

/**
 * 用户在任务里要求时,写下一份新的个人技能或改写一份已有的个人技能(只动 ZorvAI 自己的技能目录,不动屏幕和外部世界)。
 * 格式、大小、敏感内容和自学草稿过同一道校验([SkillDrafter.validate]);引文要出自用户本次说的话,屏幕和网页上的字不能让它写。
 * 存下即进试用(和收下的草稿一样),在「记忆与技能」里标明是用户要求写下的;内置技能的名字不能用,
 * 改已有的一份要带上读它时拿到的版本号(和收下更新草稿时核对原文指纹同一个道理)。删除和改名留在设置里由用户做。
 */
internal object SkillSave : AgentTool {
    override val verb = Verb.SkillSave
    override val label = "保存技能"
    override val risk = Risk.Read
    override val def = ToolDef("skill_save",
        Prompts.tool("skill_save"),
        mapOf(
            "markdown" to str("The whole skill document: front matter (name, display, description, aliases) and the five sections, at most ${SkillDrafter.MAX_CHARS} characters."),
            "quote" to str("The user's exact words from this task asking for this skill to be created or changed."),
            "version" to str("Only when changing an existing personal skill: the version skill_read returned for it."),
        ), listOf("markdown", "quote"))

    override fun available(ctx: ToolContext) =
        ctx.config.personalSkills != null && ctx.config.skills != null && ctx.config.utterances != null

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val raw = a.str("markdown", max = SkillDrafter.MAX_CHARS * 2)!!
        val quote = a.str("quote", max = 1000)!!
        val version = a.str("version", max = 64)?.trim()
        val store = ctx.config.personalSkills!!
        val catalog = ctx.config.skills!!
        // 只有用户本次说的话能让它写:页面、文件里写着「保存这个技能」不算
        if (!ctx.config.utterances!!.contains(quote)) return fail("quote_not_from_user", Feedback["skill.quote_not_from_user"])
        if (SensitiveFilter.hit(raw) != null) return fail("sensitive", Feedback["skill.sensitive"], recoverable = false)

        // 出处由代码写:自学用的几行去掉,标上是用户要求写下的
        val md = PersonalSkills.withField(
            listOf("updates", "learned_from").fold(SkillDrafter.stripFence(raw).replace("\r\n", "\n")) { m, k -> PersonalSkills.withoutField(m, k) },
            "source", PersonalSkill.SOURCE_USER)
        val scriptTool = ctx.tools.nameOf(Verb.RunBundledScript)
        SkillDrafter.validate(md, ctx.tools.names + DO_STEPS_TOOL, scriptTool, catalog.runnableScripts())?.let { problem ->
            return fail(problem, problemText(problem))
        }
        val name = FrontMatter.parse(md).first["name"] as String

        if (catalog.items.any { it.kind != PERSONAL_KIND && it.name == name }) return fail("builtin_name", Feedback.format("skill.builtin_name", "name" to name))
        if (store.pending().any { it.name == name }) return fail("draft_pending", Feedback.format("skill.draft_pending", "name" to name))

        val existing = store.get(name)
        if (existing != null) {
            val current = PersonalSkills.version(existing.markdown)
            if (version == null) return fail("exists", Feedback.format("skill.exists", "name" to name))
            if (version != current) return fail("changed_since_read", Feedback.format("skill.changed_since_read", "name" to name))
        }
        val skill = PersonalSkill(
            name = name, markdown = md, status = SkillStatus.trial,
            learnedFrom = existing?.learnedFrom, replacesDraftOf = null,
            createdAt = existing?.createdAt ?: System.currentTimeMillis(), outcomes = emptyList(),
            source = PersonalSkill.SOURCE_USER,
        )
        try {
            store.put(skill)
        } catch (e: Exception) {
            return fail("save_failed", Feedback["skill.save_failed"], recoverable = false)
        }
        // 读回来核对:写进去的就是这一份,才说存下了
        val saved = store.get(name)
        if (saved?.markdown != md) return fail("save_failed", Feedback["skill.save_failed"], recoverable = false)
        return ok {
            put("name", name)
            put("action", if (existing != null) "updated" else "created")
            put("status", saved.status.name)
            put("version", PersonalSkills.version(saved.markdown))
            put("note", Feedback["skill.saved"])
        }
    }

    /** 校验不过的原因([SkillDrafter.validate] 的码)给模型的说明。 */
    private fun problemText(problem: String): String = when (problem) {
        "invalid_name" -> Feedback["skill.invalid_name"]
        "missing_description" -> Feedback["skill.missing_description"]
        "missing_section" -> Feedback.format("skill.missing_section", "sections" to SkillDrafter.SECTIONS.joinToString(", ") { "## $it" })
        "too_long" -> Feedback.format("skill.too_long", "max" to SkillDrafter.MAX_CHARS)
        "unknown_tool" -> Feedback["skill.unknown_tool"]
        "unrunnable_script" -> Feedback["skill.unrunnable_script"]
        else -> Feedback["skill.sensitive"]
    }
}
