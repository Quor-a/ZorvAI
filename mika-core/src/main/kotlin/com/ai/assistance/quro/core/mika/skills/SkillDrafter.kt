package com.ai.assistance.quro.core.mika.skills

import com.ai.assistance.quro.core.mika.model.Author
import com.ai.assistance.quro.core.mika.knowledge.FrontMatter
import com.ai.assistance.quro.core.mika.knowledge.PERSONAL_KIND
import com.ai.assistance.quro.core.mika.knowledge.GuideCatalog
import com.ai.assistance.quro.core.mika.memory.SensitiveFilter
import com.ai.assistance.quro.core.mika.model.ChatModel
import com.ai.assistance.quro.core.mika.model.Message
import com.ai.assistance.quro.core.mika.model.Part
import com.ai.assistance.quro.core.mika.model.Role
import com.ai.assistance.quro.core.mika.prompt.Prompts
import com.ai.assistance.quro.core.mika.observation.ObsKind
import com.ai.assistance.quro.core.mika.observation.PlaceKind
import com.ai.assistance.quro.core.mika.record.RunEvent
import com.ai.assistance.quro.core.mika.verify.AnswerSource
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import com.ai.assistance.quro.core.mika.tool.ToolIndex
import com.ai.assistance.quro.core.mika.tool.Verb
import com.ai.assistance.quro.core.mika.net.UrlHost

/**
 * 起草技能的全部输入:[events] 是这次运行的事件,[toolNames] 是 ZorvAI 当前的工具名,
 * [tools] 按名字查工具做的动作。
 */
/** [answer] 是交给用户的最终回答,[sources] 是代码找到的回答来源(见 [AnswerSource])。 */
data class DraftInput(
    val runId: String, val goal: String, val answer: String, val sources: List<AnswerSource>, val events: List<RunEvent>,
    val toolNames: Set<String>, val tools: ToolIndex,
)

/** 做事的动作(手机、网页、沙箱);读屏、查技能、收尾这类不算步数。 */
private val ACTION_VERBS = setOf(Verb.Tap, Verb.Scroll, Verb.Swipe, Verb.TypeText, Verb.ClearText, Verb.Back, Verb.PressKey, Verb.LaunchApp, Verb.ShareText,
    Verb.Navigate, Verb.Upload, Verb.Tabs, Verb.RunCommand, Verb.RunBundledScript, Verb.WriteFile, Verb.ShareFile)

/** 什么时候值得起草;开关与评测模式由调用方判断。 */
object Eligibility {
    const val MIN_ACTIONS = 5

    /** null = 可以起草;否则是不起草的原因。[done]:这次运行算办成了([com.ai.assistance.quro.core.mika.agent.LearnGate])。 */
    fun check(events: List<RunEvent>, followedPersonalSkill: String?, tools: ToolIndex, done: Boolean): String? {
        val ended = events.filterIsInstance<RunEvent.TaskEnded>().lastOrNull()
        return when {
            ended?.outcome != "succeeded" -> "not_succeeded"
            !done -> "not_done"
            // 这次用户已经让 ZorvAI 照他的意思写下了技能,不再另起一份草稿
            events.any { it is RunEvent.ToolResult && tools.verb(it.tool) == Verb.SkillSave && it.resultJson.contains("\"ok\":true") } -> "skill_saved"
            // 出错的动作不算步数(结果可能被截短,按原文判)
            events.count { it is RunEvent.ToolResult && tools.verb(it.tool) in ACTION_VERBS && !it.resultJson.contains("\"ok\":false") } < MIN_ACTIONS -> "too_few_actions"
            else -> interrupted(events) ?: "followed_personal_skill".takeIf { followedPersonalSkill != null }
        }
    }

    /** 这次的经过有一段不是 ZorvAI 自己走的(跳过了接管、用户自己办完、不同意确认),从它学不到东西:返回原因,否则 null。 */
    fun interrupted(events: List<RunEvent>): String? = when {
        events.any { it is RunEvent.Takeover && it.outcome == "skipped" } -> "takeover_skipped"
        // 用户自己办完的:那段路线 ZorvAI 没走过
        events.any { it is RunEvent.Takeover && it.outcome == "user_finished" } -> "takeover_user_finished"
        events.any { it is RunEvent.Confirmation && it.decision in DECLINED } -> "confirmation_declined"
        else -> null
    }

    private val DECLINED = setOf("declined", "declined_before")
}

/**
 * 动作清单的一行:只有工具、目标元素名字、所在 App 或网站([where],网站写成「网站 主机名」)、是否生效、错误码。
 * [typed]:这一步是输入文字。
 */
data class ActionLine(
    val step: Int, val tool: String, val target: String?, val where: String?, val changed: Boolean?, val error: String?,
    val typed: Boolean = false,
)

/** 从运行事件里取精简的动作清单。不取屏幕全文;`type_text` 的文字不取(渲染成 `<输入内容>`)。 */
object ActionDigest {
    private val ELEMENT = Regex("\"ref\":\"(@e\\d+)\",\"role\":\"[^\"]*\",\"name\":(\"(?:[^\"\\\\]|\\\\.)*\")")
    private val CURRENT_APP = Regex("\"currentApp\":\"([^\"]+)\"")
    private val CURRENT_SITE = Regex("\"currentSite\":\"([^\"]+)\"")
    private val READS = setOf(Verb.Observe, Verb.ListElements)

    fun of(events: List<RunEvent>, tools: ToolIndex, maxLines: Int = 60): List<ActionLine> {
        val args = HashMap<Int, JsonObject>()
        var names = emptyMap<String, String>()
        // 手机上在哪个 App、网页上在哪个站点;onWeb 是此刻在哪一边
        var app: String? = null
        var site: String? = null
        var onWeb = false
        val out = mutableListOf<ActionLine>()
        for (e in events) {
            when (e) {
                is RunEvent.ModelReply -> e.argsJson?.let(::parse)?.let { args[e.step] = it }
                is RunEvent.Observed -> if (e.kind == ObsKind.Read.wire) when (e.place?.kind) {
                    PlaceKind.Site -> { site = e.place.name; onWeb = true }
                    PlaceKind.Page -> UrlHost.of(e.place.name)?.let { site = it; onWeb = true }
                    PlaceKind.App -> { app = e.place.name; onWeb = false }
                    else -> Unit
                }
                is RunEvent.ToolResult -> {
                    // 读屏结果可能被截短成不完整的 JSON,逐个元素按文本取 ref→名字,只用来给目标命名
                    val verb = tools.verb(e.tool)
                    if (verb in READS) {
                        names = ELEMENT.findAll(e.resultJson).associate { m -> m.groupValues[1] to unquote(m.groupValues[2]) }
                        CURRENT_APP.find(e.resultJson)?.let { app = it.groupValues[1]; onWeb = false }
                        CURRENT_SITE.find(e.resultJson)?.let { site = it.groupValues[1]; onWeb = true }
                    } else {
                        // 动作结果里带的 screen 也报在哪
                        CURRENT_SITE.find(e.resultJson)?.let { site = it.groupValues[1]; onWeb = true }
                            ?: CURRENT_APP.find(e.resultJson)?.let { app = it.groupValues[1]; onWeb = false }
                    }
                    if (verb !in ACTION_VERBS || out.size >= maxLines) continue
                    val a = args[e.step]
                    fun arg(k: String) = (a?.get(k) as? JsonPrimitive)?.contentOrNull
                    if (verb == Verb.LaunchApp || verb == Verb.ShareText) arg("package")?.let { app = it; onWeb = false }
                    if (verb == Verb.Navigate) arg("url")?.let(UrlHost::of)?.let { site = it; onWeb = true }
                    val target = when (verb) {
                        // 只记分享给了哪个 App,不记文字:可能带个人信息
                        Verb.LaunchApp, Verb.ShareText -> arg("package")
                        Verb.PressKey -> arg("key") ?: arg("keys")
                        // 只记站点,不记网址:查询串里可能有个人信息
                        Verb.Navigate -> arg("url")?.let(UrlHost::of)
                        Verb.Tabs -> arg("action")
                        // 命令、路径、上传的文件可能带个人信息:不记
                        Verb.RunCommand, Verb.RunBundledScript, Verb.WriteFile, Verb.ShareFile, Verb.Upload -> null
                        else -> arg("ref")?.let { names[it] }?.takeIf { it.isNotBlank() }
                    }
                    val result = parse(e.resultJson)
                    val error = (result?.get("error") as? JsonPrimitive)?.contentOrNull
                    val where = if (onWeb) site?.let { "网站 $it" } else app
                    out += ActionLine(e.step, e.tool, target, where, e.changed, error, typed = verb == Verb.TypeText)
                }
                else -> Unit
            }
        }
        return out
    }

    /** 目标不取自读屏的动作。 */

    fun render(lines: List<ActionLine>): String = lines.joinToString("\n") { l ->
        buildString {
            append(l.step).append(". ").append(l.tool)
            l.target?.let { append(" 「").append(it).append("」") }
            if (l.typed) append(" 输入 <输入内容>")
            l.where?.let { append(" 在 ").append(it) }
            when {
                l.error != null -> append(" 出错:").append(l.error)
                l.changed == true -> append(" 生效")
                l.changed == false -> append(" 画面没变")
            }
        }
    }

    private fun parse(s: String): JsonObject? = try {
        Json.parseToJsonElement(s) as? JsonObject
    } catch (e: Exception) {
        null
    }

    private fun unquote(s: String) = try {
        (Json.parseToJsonElement(s) as? JsonPrimitive)?.contentOrNull ?: ""
    } catch (e: Exception) {
        ""
    }
}

sealed interface DraftResult {
    /**
     * [isUpdateOf]:模型判为已有个人技能的同一类事,作为它的更新起草;
     * [replacesPending]:判为一份待确认草稿的同一类事,是那份草稿的编号——存回原处换掉它,不另起一份。
     */
    data class Draft(val skill: PersonalSkill, val isUpdateOf: String?, val replacesPending: String? = null) : DraftResult
    data class Dropped(val reason: String) : DraftResult
}

/**
 * 一次单独的模型调用(不带工具)把办成的任务写成技能草稿,再由代码校验;不过就丢弃,不打扰用户。
 * 发给模型的是目标、最终回答、回答的来源(哪一步在哪儿读的)和动作清单;目标里的值、输入的文字换成占位,不含读到的原文。
 * 另列出已有的个人技能(连同待确认的草稿):这次的事和哪份是同一类事,由模型在头部写 `updates: <名字>` 说明,代码只核对名字确有其事。
 */
class SkillDrafter(private val model: ChatModel) {
    suspend fun draft(input: DraftInput, existing: PersonalSkills, catalog: GuideCatalog): DraftResult {
        // 这一次的值(目标里引号内的、这次输入的文字、数字)换成占位,技能下次换个对象也用得上;
        // 回答和屏上的字照原样给、照原样收:里面的页面、按钮名字正是做法本身
        val literals = SkillRedactor.literals(input.goal, emptyList()) + SkillRedactor.textLiterals(typedTexts(input.events, input.tools))
        val keepDigits = catalogDigits(catalog)
        // 已有技能的名字照原样给(换了数字就对不上了);它们是用户自己的技能,不是这一次的值
        val request = SkillRedactor.scrubNumbers(SkillRedactor.redact(request(input), literals), keepDigits) + "\n" + existingSkills(existing)
        val reply = try {
            model.complete(PROMPT, listOf(Message(Role.User, listOf(Part.Text(request)), Author.ZorvAI)), emptyList())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return DraftResult.Dropped("model_error")
        }
        val redacted = SkillRedactor.redact(stripFence(reply.parts.filterIsInstance<Part.Text>().joinToString("") { it.text }), literals)
        // 先按原样查一遍:草稿里写了密码、卡号这类东西,说明起草走偏了,数字换掉也不收
        if (SensitiveFilter.hit(redacted) != null) return DraftResult.Dropped("sensitive")
        // 要更新哪份在换数字之前取:技能名里的数字是名字的一部分
        val updates = (FrontMatter.parse(redacted).first["updates"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
        val md = PersonalSkills.withoutField(SkillRedactor.scrubNumbers(redacted, keepDigits), "updates")

        validate(md, input.toolNames, input.tools.nameOf(Verb.RunBundledScript), catalog.runnableScripts())?.let { return DraftResult.Dropped(it) }
        val meta = FrontMatter.parse(md).first
        val name = meta["name"] as String

        // 模型指的是一份待确认的草稿:换掉那份(它若是某份已有技能的更新,新草稿接着更新那份);
        // 指的是已收下的(在用、试用或停用的):作为它的更新。名字对不上的(没有这份、是内置技能)不算,当作新的一份
        val pending = updates?.let { u -> existing.drafts().entries.firstOrNull { it.value.name == u } }
        val updateOf = if (pending != null) pending.value.replacesDraftOf else updates?.takeIf { existing.get(it) != null }
        // 新起草的不能顶掉内置技能、已有个人技能或草稿;更新草稿一律用被更新那份的名字(收下时覆盖它,头部名字要对得上)
        if (pending == null && updateOf == null &&
            (existing.get(name) != null || existing.pending().any { it.name == name } || catalog.items.any { it.name == name }))
            return DraftResult.Dropped("name_taken")
        val finalName = pending?.value?.name ?: updateOf ?: name
        // 出自哪次运行由代码写:请求里的数字都换成了占位,模型抄不出真的运行编号
        val finalMd = PersonalSkills.withField(PersonalSkills.withName(md, finalName), "learned_from", input.runId)

        val skill = PersonalSkill(
            name = finalName, markdown = finalMd, status = SkillStatus.pending, learnedFrom = input.runId, replacesDraftOf = updateOf,
            createdAt = input.events.lastOrNull()?.atMs ?: 0L, outcomes = emptyList(),
            baseHash = updateOf?.let { name -> existing.get(name)?.markdown?.let { PersonalSkills.fingerprint(it) } },
        )
        return DraftResult.Draft(skill, updateOf, pending?.key)
    }

    /** 已有的个人技能(已收下的在前,待确认的草稿在后,各按名字排),每份一行:名字、显示名、描述。 */
    private fun existingSkills(existing: PersonalSkills): String {
        val lines = (existing.list().sortedBy { it.name } + existing.pending().sortedBy { it.name }).map { s ->
            val meta = FrontMatter.parse(s.markdown).first
            "- ${s.name}(${meta["display"] as? String ?: s.name}):${meta["description"] as? String ?: ""}"
        }
        return "已有的个人技能(含待用户确认的草稿):" + if (lines.isEmpty()) "无" else "\n" + lines.joinToString("\n")
    }


    /** 这次 type_text 输入的文字(密码框的已在记录里换成 ***)。 */
    private fun typedTexts(events: List<RunEvent>, tools: ToolIndex): List<String> = events.filterIsInstance<RunEvent.ModelReply>()
        .filter { tools.verb(it.tool) == Verb.TypeText }
        .mapNotNull { e ->
            val o = try { Json.parseToJsonElement(e.argsJson ?: return@mapNotNull null) as? JsonObject } catch (x: Exception) { null }
            (o?.get("text") as? JsonPrimitive)?.contentOrNull?.takeIf { it != "***" }
        }

    /** 内置技能名字、显示名、别名里自带的数字(如铁路 App 的名字),草稿里保留。 */
    private fun catalogDigits(catalog: GuideCatalog): Set<String> =
        catalog.items.filter { it.kind != PERSONAL_KIND }.flatMap { listOf(it.name, it.display) + it.aliases }
            .flatMap { Regex("\\d{2,}").findAll(SkillRedactor.norm(it)).map { m -> m.value }.toList() }.toSet()

    private fun request(input: DraftInput): String = buildString {
        appendLine("运行编号:${input.runId}")
        appendLine("目标:${input.goal}")
        appendLine("最终回答:${input.answer}")
        if (input.sources.isNotEmpty()) {
            appendLine("回答的来源:")
            input.sources.forEach { s -> appendLine("- 第 ${s.step} 步 ${s.kind} ${s.place.orEmpty()}" + s.title?.let { "「$it」" }.orEmpty()) }
        }
        appendLine("动作清单:")
        appendLine(ActionDigest.render(ActionDigest.of(input.events, input.tools)))
        append("可用工具:").append(input.toolNames.sorted().joinToString(", "))
    }

    companion object {
        /** 模型回复外面包着的代码块围栏去掉。 */
        fun stripFence(text: String): String {
            val t = text.trim()
            if (!t.startsWith("```")) return t
            return t.substringAfter('\n').removeSuffix("```").trim()
        }

        const val MAX_CHARS = 4000

        /**
         * 技能文档的格式与安全校验(起草的草稿、用户改过再收下的全文都过这一关);通过返回 null,否则返回原因:
         * 名字合法、有描述、五个小节齐全、不超过 [MAX_CHARS] 字、反引号里的工具名都在 [toolNames] 里、敏感过滤不命中;
         * 提到跑技能脚本的工具([scriptTool])时,文中的脚本路径至少有一个、且都得是 [runnableScripts](清单里标为可运行的)里的。
         */
        fun validate(md: String, toolNames: Set<String>, scriptTool: String?, runnableScripts: Set<String> = emptySet()): String? {
            val (meta, body) = FrontMatter.parse(md)
            val name = meta["name"] as? String
            if (name == null || !PersonalSkills.NAME.matches(name)) return "invalid_name"
            if ((meta["description"] as? String).isNullOrBlank()) return "missing_description"
            val headings = body.lines().filter { it.startsWith("## ") }
            if (SECTIONS.any { s -> headings.none { it.removePrefix("## ").trim().startsWith(s) } }) return "missing_section"
            if (md.length > MAX_CHARS) return "too_long"
            if (TOOL_MENTION.findAll(md).any { it.groupValues[1] !in toolNames }) return "unknown_tool"
            if (scriptTool != null && TOOL_MENTION.findAll(md).any { it.groupValues[1] == scriptTool }) {
                val scripts = SCRIPT_PATH.findAll(md).map { it.value }.toList()
                fun runnable(path: String) = runnableScripts.any { it == path || it.endsWith("/$path") }
                if (scripts.isEmpty() || !scripts.all(::runnable)) return "unrunnable_script"
            }
            if (SensitiveFilter.hit(md) != null) return "sensitive"
            return null
        }
        val SECTIONS = listOf("入口", "步骤", "容易出错的地方", "怎样算办成", "什么时候停下交给用户")
        /** 反引号里形如工具名的标识符。 */
        private val TOOL_MENTION = Regex("`([a-z][a-z0-9_]*)`")
        /** 文中的技能脚本路径,如 `scripts/extract_pdf.py` 或 `skills/documents/scripts/extract_pdf.py`。 */
        private val SCRIPT_PATH = Regex("[A-Za-z0-9_./-]*scripts/[A-Za-z0-9_./-]+\\.(?:py|mjs|js|sh)(?![A-Za-z0-9_])")

        val PROMPT: String by lazy { Prompts.role("skill_draft") }
    }
}
