package com.ai.assistance.quro.core.mika.knowledge

import com.ai.assistance.quro.core.mika.agent.FailKind
import com.ai.assistance.quro.core.mika.agent.LearnGate
import com.ai.assistance.quro.core.mika.agent.RunOutcome
import com.ai.assistance.quro.core.mika.agent.TaskState
import com.ai.assistance.quro.core.mika.memory.SensitiveFilter
import com.ai.assistance.quro.core.mika.model.Author
import com.ai.assistance.quro.core.mika.model.ChatModel
import com.ai.assistance.quro.core.mika.model.Message
import com.ai.assistance.quro.core.mika.model.Part
import com.ai.assistance.quro.core.mika.model.Role
import com.ai.assistance.quro.core.mika.prompt.Prompts
import com.ai.assistance.quro.core.mika.record.RunEvent
import com.ai.assistance.quro.core.mika.skills.ActionDigest
import com.ai.assistance.quro.core.mika.skills.ActionLine
import com.ai.assistance.quro.core.mika.skills.Eligibility
import com.ai.assistance.quro.core.mika.skills.SkillDrafter
import com.ai.assistance.quro.core.mika.tool.ToolIndex
import com.ai.assistance.quro.core.mika.tool.Verb
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable

@Serializable
enum class AppKnowledgeStage { Draft, Trial, Verified, Archived }

/**
 * 一个 App 的自学知识补充(只写事实的短清单,附在这个 App 的说明后面)的一个版本。
 * [lesson]:从没办成或磕磕绊绊的运行里学的;[revises]:起草时这个 App 在用的那份补充,这份是它的修订(第一次补充为 null)。
 */
@Serializable
data class AppKnowledgeDraft(val id: String, val appName: String, val packageName: String,
    val markdown: String, val sourceRun: String, val stage: AppKnowledgeStage, val createdAt: Long,
    val trialOutcomes: List<Boolean> = emptyList(), val lesson: Boolean = false, val revises: String? = null,
    /** 给用户看的 App 名字(如「设置」);旧记录没有,界面退回 [appName]。 */
    val display: String = "")

/** 注入时附在 App 说明后面的自学补充;[trial] 为 true 时还在试用,没被办成的任务用过。 */
data class LearnedNote(val text: String, val trial: Boolean)


/**
 * 自学的 App 知识。每个 App 至多一份待确认的([AppKnowledgeStage.Draft])、只注入一份([inUse])。
 * 收下的修订试用期间暂时顶替它修订的那份(那份不注入、也不删);修订转正式才把旧的归档,修订被归档就换回旧的。
 */
interface AppKnowledgeDraftStore {
    fun all(): List<AppKnowledgeDraft>
    /** 同一个 App 已有待确认的,或 [AppKnowledgeDraft.revises] 不是现在注入的那份(过时的修订),就不收,返回 false。 */
    fun putPending(draft: AppKnowledgeDraft): Boolean
    /**
     * 修订的不是现在注入的那份(过时了)就不收,当场归档,返回 false。收下、归档和试用改了在用的那份之后,
     * 同一个 App 过时的待确认修订一并归档,不让它挡住往后的草稿。
     */
    fun approve(id: String): Boolean
    fun reject(id: String): Boolean
    /** 用过试用中那份的运行结束了:[good] 是这次算办成([LearnGate]);按 [AppKnowledgeTrial.next] 转正式、留着或归档。 */
    fun recordTrial(appName: String, good: Boolean): AppKnowledgeDraft?
    fun notes(): Map<String, LearnedNote>

    /** 这个 App 现在注入的那份补充。 */
    fun inUse(appName: String): AppKnowledgeDraft? = AppKnowledgeTrial.inUse(all(), appName)
}

/** 试用结果怎么改阶段;和个人技能一样:顺利办成的转正式,从教训学来的要连着两次不好才归档,别的一次不好就归档。 */
object AppKnowledgeTrial {
    const val LESSON_BAD_STREAK = 2

    fun next(d: AppKnowledgeDraft, good: Boolean): AppKnowledgeDraft {
        val outcomes = d.trialOutcomes + good
        val stage = when {
            good -> AppKnowledgeStage.Verified
            !d.lesson -> AppKnowledgeStage.Archived
            outcomes.size >= LESSON_BAD_STREAK && outcomes.takeLast(LESSON_BAD_STREAK).none { it } -> AppKnowledgeStage.Archived
            else -> AppKnowledgeStage.Trial
        }
        return d.copy(stage = stage, trialOutcomes = outcomes)
    }

    /** [rows] 里 [appName] 注入的那份:试用或已核实,且没被一份试用中的修订暂时顶替。 */
    fun inUse(rows: List<AppKnowledgeDraft>, appName: String): AppKnowledgeDraft? {
        val app = rows.filter { it.appName == appName }
        val shadowed = app.filter { it.stage == AppKnowledgeStage.Trial }.mapNotNull { it.revises }.toSet()
        return app.firstOrNull { (it.stage == AppKnowledgeStage.Trial || it.stage == AppKnowledgeStage.Verified) && it.id !in shadowed }
    }
}

/** 起草要看的一次运行:[end] 和 [outcome] 是它的结局与交出回答后判的结局。 */
data class AppRunInput(
    val runId: String, val goal: String, val end: TaskState, val outcome: RunOutcome, val events: List<RunEvent>, val tools: ToolIndex,
)

/**
 * 一次运行在某个 App 里的经过属于哪种:[Done] 顺利办成;[Bumpy] 办成了但途中出错、界面没变或来回返回;
 * [NotDone] 没办成或只办成一部分。后两种是「下次能少走弯路」的教训。[wire] 是请求里写给模型的结局。
 */
enum class AppRunKind(val wire: String) { Done("done"), Bumpy("bumpy"), NotDone("not_done") }

/**
 * 把一次运行在某个 App 里的经过,写成对这个 App 知识补充的修订:请一次模型(不带工具),交给它这个 App 的内置说明、
 * 在用的补充、结局和精简的动作清单(带出错码、界面变没变),要回补充改完以后的全文;没有值得补的(最常见)什么也不产出。
 *
 * 交回的补充照原样收;写了密码、验证码这类东西的整份不收。
 */
class AppKnowledgeDrafter(private val model: ChatModel) {
    suspend fun draft(run: AppRunInput, app: KnowledgeEntry, builtIn: String?, current: AppKnowledgeDraft?, id: String, atMs: Long): AppKnowledgeDraft? {
        if (app.surface != Surface.Android || app.packages.isEmpty()) return null
        if (Eligibility.interrupted(run.events) != null || run.events.any { it is RunEvent.Replay && it.matched }) return null
        val lines = ActionDigest.of(run.events, run.tools).filter { it.where in app.packages }
        val kind = kind(run.end, run.outcome, lines, run.tools) ?: return null
        // 已经核实过的补充,一次顺利办成多半说明不了新东西(模型也只会回 NONE):不请
        if (kind == AppRunKind.Done && current?.stage == AppKnowledgeStage.Verified) return null
        val said = when (val end = run.end) {
            is TaskState.Succeeded -> end.answer
            is TaskState.Failed -> end.reason
            else -> return null
        }
        val existing = current?.let { facts(it.markdown) }
        val request = Prompts.fill(INPUT, mapOf(
            "app" to app.display + " (" + app.packages.joinToString(", ") + ")",
            "outcome" to kind.wire,
            "goal" to run.goal,
            "said" to said,
            "built_in" to builtIn.orEmpty(),
            "current" to existing.orEmpty(),
            "actions" to ActionDigest.render(lines),
        ))
        val reply = try {
            model.complete(PROMPT, listOf(Message(Role.User, listOf(Part.Text(request)), Author.ZorvAI)), emptyList())
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { return null }
        val raw = SkillDrafter.stripFence(reply.parts.filterIsInstance<Part.Text>().joinToString("") { it.text })
        if (raw.isEmpty() || raw.equals(NOTHING, ignoreCase = true)) return null
        if (!valid(raw, run.tools)) return null
        val facts = raw.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
        if (facts == existing) return null
        return AppKnowledgeDraft(id, app.name, app.packages.first(), facts, run.runId, AppKnowledgeStage.Draft, atMs,
            lesson = kind != AppRunKind.Done, revises = current?.id, display = app.display)
    }

    companion object {
        /** 顺利办成时,这个 App 里至少这么多步生效的动作才值得写(和个人技能起草同一个门槛)。 */
        const val MIN_DONE_ACTIONS = Eligibility.MIN_ACTIONS
        /** 教训至少要在这个 App 里动过这么多步:一两步就停下的多半卡在开头(没登录、打不开),说明不了界面。 */
        const val MIN_LESSON_ACTIONS = 3
        /** 出错或界面没变的动作到这么多就算磕绊:一次没点中常有,两次说明入口或界面和预想的不一样。 */
        const val BUMPY_MISSES = 2
        /** 返回到这么多次就算绕了路:看完详情退回一次是常事,两次多半是走错了入口又退回来。 */
        const val BUMPY_BACKS = 2
        /** 补充至多这么多条、这么多字:注入时附在说明后面,不该比说明本身还长。 */
        const val MAX_FACTS = 12
        const val MAX_CHARS = 1500
        /** 模型说「没有值得补的」时只回这个词。 */
        const val NOTHING = "NONE"

        val PROMPT: String by lazy { Prompts.role("app_knowledge") }
        private val INPUT: String by lazy { Prompts.role("app_knowledge_input") }

        private val TOOL_MENTION = Regex("`([a-z][a-z0-9_]*)`")
        /**
         * 一份补充的事实行:去掉头部、标题和空行,不是「- 」开头的补上。旧版本起草的是整份 APP.md,
         * 这样也能接着修订、比较改了哪些行。
         */
        fun facts(markdown: String): String = FrontMatter.parse(markdown).second.lines().map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .joinToString("\n") { if (it.startsWith("- ")) it else "- " + it.removePrefix("-").removePrefix("*").trim() }

        /** 这次运行在 [app] 里的经过算哪种;不值得学的为 null(取消的、判不成的、核查没过的、动作太少的)。 */
        fun kind(end: TaskState, outcome: RunOutcome, lines: List<ActionLine>, tools: ToolIndex): AppRunKind? {
            val misses = lines.count { it.error != null || it.changed == false }
            val backs = lines.count { tools.verb(it.tool) == Verb.Back }
            val bumpy = lines.size >= MIN_LESSON_ACTIONS && (misses >= BUMPY_MISSES || backs >= BUMPY_BACKS)
            val notDone = (end is TaskState.Succeeded && (outcome == RunOutcome.Partial || outcome == RunOutcome.NotPossible)) ||
                (end is TaskState.Failed && (end.kind == FailKind.GaveUp || end.kind == FailKind.StepLimit))
            return when {
                LearnGate.done(end, outcome) -> when {
                    bumpy -> AppRunKind.Bumpy
                    lines.count { it.error == null } >= MIN_DONE_ACTIONS -> AppRunKind.Done
                    else -> null
                }
                notDone && lines.size >= MIN_LESSON_ACTIONS -> AppRunKind.NotDone
                else -> null
            }
        }

        /** 这次在手机上动过手的 App 里,有知识条目的那几个(按先后,每个一次)。没有条目的 App 没处附补充,不学。 */
        fun touched(events: List<RunEvent>, tools: ToolIndex, knowledge: KnowledgeStore): List<KnowledgeEntry> =
            ActionDigest.of(events, tools).mapNotNull { it.where }.distinct()
                .mapNotNull { knowledge.byPackage(it)?.takeIf { e -> e.surface == Surface.Android } }.distinctBy { it.name }

        /** 只有以「- 」开头的事实行,条数、字数在上限内,不提没登记的工具名,敏感过滤不命中。 */
        private fun valid(facts: String, tools: ToolIndex): Boolean {
            val lines = facts.lines().filter { it.isNotBlank() }
            return lines.isNotEmpty() && lines.size <= MAX_FACTS && facts.length <= MAX_CHARS &&
                lines.all { it.startsWith("- ") && it.length > 4 } &&
                TOOL_MENTION.findAll(facts).all { it.groupValues[1] in tools.names } &&
                SensitiveFilter.hit(facts) == null
        }
    }
}
