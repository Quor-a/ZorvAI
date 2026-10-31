package com.ai.assistance.quro.core.mika.record

import com.ai.assistance.quro.core.mika.replay.Anchors
import com.ai.assistance.quro.core.mika.replay.Selector
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import com.ai.assistance.quro.core.mika.observation.InputHints
import com.ai.assistance.quro.core.mika.observation.ObsKind
import com.ai.assistance.quro.core.mika.observation.Observation
import com.ai.assistance.quro.core.mika.observation.Place
import com.ai.assistance.quro.core.mika.observation.Trust

/** 运行记录里的一个事件;落盘为 events.jsonl 的一行,`type` 字段区分种类。 */
@Serializable
sealed interface RunEvent {
    val atMs: Long

    /** [origin] 是谁发起的这次运行(`RunOrigin` 的小写名);手机上发起的不写。 */
    @Serializable @SerialName("task_started")
    data class TaskStarted(override val atMs: Long, val goal: String, val model: String, val origin: String? = null) : RunEvent

    /** 本任务发给模型的系统提示词与工具定义的摘要([com.ai.assistance.quro.core.mika.prompt.PromptVersion])。 */
    @Serializable @SerialName("prompt_version")
    data class PromptVersion(override val atMs: Long, val hash: String) : RunEvent

    /** [fallback] 表示没能列出标准,[criteria] 只是用户原话。 */
    @Serializable @SerialName("criteria")
    data class Criteria(
        override val atMs: Long,
        val criteria: List<String>,
        val ambiguities: List<String>,
        val kind: String = "",
        val fallback: Boolean = false,
    ) : RunEvent

    /** [tool]/[argsJson] 是本轮实际执行的那一个调用;参数已脱敏。 */
    @Serializable @SerialName("model_reply")
    data class ModelReply(
        override val atMs: Long,
        val step: Int,
        val text: String,
        val tool: String?,
        val argsJson: String?,
        val inputTokens: Int?,
        val outputTokens: Int?,
        val cachedTokens: Int?,
        /** 最后一次发请求到收完回复;之前失败的请求和退避另记在 [retryWaitMs]。 */
        val latencyMs: Long = 0,
        /** 本次调用实际用的模型;null = 主模型。 */
        val model: String? = null,
        /** 大小模型分工起作用时:main / light / light_rejected(轻量模型的回复没执行,改问了主模型);否则 null。 */
        val role: String? = null,
        /** 重试前花掉的时间:失败的那几次请求加退避等待。 */
        val retryWaitMs: Long = 0,
        /**
         * 回复到手的时刻:界面上这一步的说明、要做的事或回答从这时显示出来(带调用的回复执行完才记,[atMs] 比它晚)。
         * 改派掉的轻量模型回复和旧记录里没有。
         */
        val repliedAtMs: Long? = null,
    ) : RunEvent {
        /** 这次回复真的驱动了这一步(改派掉的轻量模型回复不算)。 */
        val acted: Boolean get() = role != "light_rejected"
    }

    /** [resultJson] 含守卫结论,整条记下;只有超过 [MAX_RESULT] 字才截短并留标记。 */
    @Serializable @SerialName("tool_result")
    data class ToolResult(
        override val atMs: Long,
        val step: Int,
        val tool: String,
        val resultJson: String,
        val changed: Boolean?,
        val screenshotId: Int?,
        /** 工具执行耗时,含端口内等界面稳定;不含等用户(确认、追问、接管)。 */
        val durationMs: Long = 0,
        /** 其中等界面稳定的部分(端口能报时才有值),以及为节奏有意停下的时间(网页一页没看够就换页时补的停留)。 */
        val waitMs: Long = 0,
        /** model | batch | replay | script(web_script 里的动作) */
        val source: String = "model",
        /** 模型这次调用的 id;路线据此把动作和它的结果配对。 */
        val callId: String? = null,
    ) : RunEvent

    /** 回放路线走到第 [step]/[total] 步;[matched] 为 false 表示在此交还给模型。 */
    @Serializable @SerialName("replay")
    data class Replay(override val atMs: Long, val routeId: String, val step: Int, val total: Int, val matched: Boolean, val detail: String) : RunEvent

    /** 一批动作走到第 [step]/[total] 步;[stopped] 时 [reason] 说明为何停在这里。 */
    @Serializable @SerialName("batch")
    data class Batch(override val atMs: Long, val step: Int, val total: Int, val stopped: Boolean, val reason: String?) : RunEvent

    /**
     * 一次写操作当时的目标元素与前后锚点(工具结果截短后元素列表不全,路线靠它);[argsJson] 已脱敏。
     * [effective]:ok 且界面变了,或输入回读一致,或启动成功。[callId] 与同一调用的 [ToolResult.callId] 相同;
     * [protected]:动作前那一屏受系统防截屏保护。
     */
    @Serializable @SerialName("action_target")
    data class ActionTarget(
        override val atMs: Long,
        val step: Int,
        val tool: String,
        val argsJson: String,
        val target: Selector?,
        val before: Anchors,
        val after: Anchors,
        val effective: Boolean,
        val callId: String? = null,
        val protected: Boolean = false,
        /** model | batch */
        val source: String = "model",
        /** 取不到这一步的目标和锚点(批里的子步);路线不能缺步,整条不记。 */
        val incomplete: Boolean = false,
    ) : RunEvent

    /**
     * 存下了一帧画面(可视化用:回看、来源里的截图):第 [step] 步的第 [mediaId] 张,[place] 是那时在哪(包名或「网站 域名」)。
     * 旧记录里是 screen_saved(读取时换成这一种)。
     */
    @Serializable @SerialName("media")
    data class Media(override val atMs: Long, val mediaId: Int, val step: Int, val place: String?) : RunEvent

    /**
     * 记进证据账本的一条观察(见 [com.ai.assistance.quro.core.mika.observation.Observation]):原文已脱敏、截短,和运行时账本里那一条一样,
     * 评测据此原样重建账本。[id] 是账本里的编号(回答的来源按它指回);[kind] 是观察的种类,[source] 是给出它的能力。
     * [inputs]、[focused] 是那时输入框里的字(分辨输入框回显用;读页面内容时为 null)。
     * 旧记录里是 screen_text(只记读界面与整页正文,读取时换成这一种,[id] 为 0)。
     */
    @Serializable @SerialName("observed")
    data class Observed(
        override val atMs: Long, val step: Int, val text: String, val id: Int = 0, val kind: String = ObsKind.Read.wire,
        val source: String? = null, val place: Place? = null, val title: String? = null,
        val inputs: List<String>? = null, val focused: String? = null, val untrusted: Boolean = false,
    ) : RunEvent {
        /** 换回一条观察。 */
        fun observation() = Observation(ObsKind.ofWire(kind) ?: ObsKind.Read, text, source, place, title,
            trust = if (untrusted) Trust.Untrusted else Trust.Trusted, inputs = inputs?.let { InputHints(it, focused) }, step = step, atMs = atMs, id = id)

        companion object {
            /** 账本里记下的一条观察。 */
            fun of(o: Observation) = Observed(o.atMs, o.step, o.text, o.id, o.kind.wire, o.source, o.place, o.title,
                o.inputs?.names, o.inputs?.focused, o.trust == Trust.Untrusted)
        }
    }

    /** 一次实际工具调用的进度。读取类可合并展示,动作类逐条展示;[obsIds] 指向这一步读到的原文,[title] 是读到的那一页的标题。 */
    @Serializable @SerialName("activity")
    data class Activity(
        override val atMs: Long,
        val step: Int,
        val kind: String,
        val label: String,
        val target: String? = null,
        val effect: String? = null,
        val obsIds: List<Int> = emptyList(),
        val source: String = "model",
        val callId: String? = null,
        val title: String? = null,
        /** 这次调用没成功([effect] 是错误码,只给开发看)。 */
        val failed: Boolean = false,
        /** 不可逆判定认出这是一次对外动作(发送、提交);停止后交代做了什么时按它数发出了几次。 */
        val outward: Boolean = false,
    ) : RunEvent

    @Serializable @SerialName("state")
    data class State(override val atMs: Long, val name: String, val detail: String) : RunEvent

    @Serializable @SerialName("confirmation")
    data class Confirmation(
        override val atMs: Long,
        val action: String,
        val target: String?,
        val content: String?,
        val amount: String?,
        val decision: String?,
    ) : RunEvent

    /**
     * 一次对外、不可逆或要花钱的动作的审批结论(代码判的,不是模型自述)。[source] 是谁发起的(model / batch / script / replay);
     * [payment] 为 true 时这一步要花钱(询问时只有它要用户同意);[decision] allowed / required / mismatch / denied。
     */
    @Serializable @SerialName("approval")
    data class Approval(
        override val atMs: Long, val step: Int, val source: String, val tool: String, val action: String, val matched: String,
        val risk: String, val payment: Boolean = false, val decision: String,
    ) : RunEvent

    @Serializable @SerialName("takeover")
    data class Takeover(override val atMs: Long, val reason: String, val outcome: String?, val durationMs: Long?, val inputCount: Int?) : RunEvent

    @Serializable @SerialName("barrier")
    data class Barrier(override val atMs: Long, val kind: String) : RunEvent

    /**
     * 最终回答的字面核对(第 [step] 步的回答):[claims] 是回答里可核对的事实数,[unmatched] 是在读到的材料里找不到的那几处;
     * [outward] 表示本任务做过对外或不可逆的事(因此另请了核查模型)。
     */
    @Serializable @SerialName("answer_check")
    data class AnswerCheck(override val atMs: Long, val step: Int, val claims: Int, val unmatched: List<String>, val outward: Boolean = false) : RunEvent

    /** 交出的回答里退回一次后仍没对上、标成未确认的几处。 */
    @Serializable @SerialName("unconfirmed")
    data class Unconfirmed(override val atMs: Long, val step: Int, val items: List<String>) : RunEvent

    /** 代码从证据账本里找到的回答来源(App 或网页、第几步、那一步的截图);界面上的「来源」用它。 */
    @Serializable @SerialName("sources")
    data class Sources(override val atMs: Long, val items: List<com.ai.assistance.quro.core.mika.verify.AnswerSource>) : RunEvent

    /** [step]:核查的是哪一步的最终回答(旧记录里是 finish 调用;这条事件可能记在那次回复之前,也可能在之后);旧记录没有。 */
    @Serializable @SerialName("verifier")
    data class Verifier(override val atMs: Long, val verdict: String, val detailJson: String, val step: Int? = null) : RunEvent

    /** 本任务记忆块里实际列出的条目(代码记录,不是模型自述)。 */
    @Serializable @SerialName("memory_used")
    data class MemoryUsed(override val atMs: Long, val ids: List<String>) : RunEvent

    /** Agent 用 memory_save 存下了一条;[status] 为 active / pending。 */
    @Serializable @SerialName("memory_saved")
    data class MemorySaved(override val atMs: Long, val id: String, val kind: String, val status: String) : RunEvent

    /** 用到的个人技能:[how] = viewed(Agent 用 skill_read 看了它);旧记录里还有 injected(以前任务开始注入了全文),读时一样算用到。 */
    @Serializable @SerialName("skill_used")
    data class SkillUsed(override val atMs: Long, val name: String, val how: String) : RunEvent

    @Serializable @SerialName("compressed")
    data class Compressed(override val atMs: Long, val foldedSteps: Int) : RunEvent

    /** [outcome]:succeeded / unverified / failed / cancelled / error;[kind] 是失败的类别(FailKind 的小写名),其余为 null,旧记录没有。 */
    @Serializable @SerialName("task_ended")
    data class TaskEnded(override val atMs: Long, val outcome: String, val answerOrReason: String, val kind: String? = null) : RunEvent

    /**
     * 本次运行期间经计量的全部模型请求合计(见 [com.ai.assistance.quro.core.mika.usage.RunUsageTally]):主循环之外,意图判断、核查、
     * 看图、压缩等旁路调用和失败重试的每一次都算;[unreported] 是服务商没报用量(或调用失败)的次数。
     * 收尾时记一条,在 [TaskEnded] 之后;交出回答后的结局判定、记忆提取不在其中(Token 用量页都算)。旧记录没有。
     */
    @Serializable @SerialName("model_calls")
    data class ModelCalls(
        override val atMs: Long, val calls: Int, val unreported: Int, val inputTokens: Long, val outputTokens: Long, val cachedTokens: Long,
    ) : RunEvent

    /**
     * 回答交给用户之后,另请模型按用户要的结果给这次运行判的结局(见 [com.ai.assistance.quro.core.mika.agent.OutcomeJudge]):
     * [outcome] 为 done / partial / not_possible / unknown(没判成);[reason] 一句话说缺了什么。记在 [TaskEnded] 之后;旧记录没有。
     */
    @Serializable @SerialName("outcome")
    data class OutcomeJudged(override val atMs: Long, val outcome: String, val reason: String = "", val model: String? = null) : RunEvent

    companion object {
        const val MAX_FIELD = 2000

        /** 工具结果的上限:远大于一屏元素列表,只为挡住异常大的输出撑爆记录文件和内存。 */
        const val MAX_RESULT = 100_000

        val json = Json { classDiscriminator = "type"; ignoreUnknownKeys = true }

        fun encode(e: RunEvent): String = json.encodeToString(serializer(), e)

        /** 一行记录;改名前的事件(screen_text、screen_saved)先换成现在的写法再读。 */
        fun decode(line: String): RunEvent = json.decodeFromJsonElement(serializer(), LegacyEvents.upgrade(json.parseToJsonElement(line)))

        /** 运行记录里写的地方:App 是包名,站点是「网站 域名」(和以往的记录同一写法)。 */
        fun placeLabel(p: Place?): String? = p?.let { if (it.kind == com.ai.assistance.quro.core.mika.observation.PlaceKind.Site) "网站 ${it.name}" else it.name }

        fun clip(s: String): String = if (s.length <= MAX_FIELD) s else s.take(MAX_FIELD) + "…(已截短)"

        fun clipResult(s: String): String =
            if (s.length <= MAX_RESULT) s else s.take(MAX_RESULT) + "…(已截短,原长 ${s.length} 字)"
    }
}

/** run.json 的内容:一次运行的概要,由事件累计而来。 */
@Serializable
data class RunSummary(
    val id: String,
    val goal: String,
    val model: String = "",
    val startedAtMs: Long,
    val endedAtMs: Long? = null,
    val outcome: String? = null,
    val answerOrReason: String? = null,
    val criteria: List<String> = emptyList(),
    val verdict: String? = null,
    /** 交出回答后判的结局([RunEvent.OutcomeJudged.outcome]);没判过(失败、取消、旧记录)为 null。 */
    val judged: String? = null,
    val steps: Int = 0,
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val cachedTokens: Int = 0,
    /** 谁发起的(见 [RunEvent.TaskStarted.origin]);手机上发起的为空。 */
    val origin: String? = null,
) {
    /** 把一个事件计入概要。 */
    fun with(e: RunEvent): RunSummary = when (e) {
        is RunEvent.TaskStarted -> copy(model = e.model, origin = e.origin)
        is RunEvent.Criteria -> copy(criteria = e.criteria)
        is RunEvent.ModelReply -> copy(
            steps = maxOf(steps, e.step),
            inputTokens = inputTokens + (e.inputTokens ?: 0),
            outputTokens = outputTokens + (e.outputTokens ?: 0),
            cachedTokens = cachedTokens + (e.cachedTokens ?: 0),
        )
        is RunEvent.Verifier -> copy(verdict = e.verdict)
        is RunEvent.TaskEnded -> copy(endedAtMs = e.atMs, outcome = e.outcome, answerOrReason = e.answerOrReason)
        is RunEvent.OutcomeJudged -> copy(judged = e.outcome)
        else -> this
    }
}
