package com.ai.assistance.quro.core.mika.verify

import com.ai.assistance.quro.core.mika.model.Author
import com.ai.assistance.quro.core.mika.observation.ObsKind
import com.ai.assistance.quro.core.mika.observation.Observation
import com.ai.assistance.quro.core.mika.model.ChatModel
import com.ai.assistance.quro.core.mika.model.Message
import com.ai.assistance.quro.core.mika.model.ModelJson
import com.ai.assistance.quro.core.mika.model.ModelJson.field
import com.ai.assistance.quro.core.mika.model.Part
import com.ai.assistance.quro.core.mika.model.Role
import com.ai.assistance.quro.core.mika.prompt.Prompts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import com.ai.assistance.quro.core.mika.vision.ImageOps
import com.ai.assistance.quro.core.mika.vision.ModelImage

/**
 * 核查时对照的标准:[items] 是做成之后应当看得到的事实;[kind] 为 read / irreversible(做过发送、提交、下单、删除、付款一类收不回的事)。
 * [fallback] 表示没有另列标准,[items] 只是用户原话本身(任务路径上一向如此,不另花一次模型调用去列)。
 */
data class Criteria(val items: List<String>, val kind: String, val fallback: Boolean) {
    companion object {
        /** 任务路径用的标准:用户原话本身。 */
        fun of(goal: String, irreversible: Boolean) = Criteria(listOf(goal), if (irreversible) "irreversible" else "read", fallback = true)
    }
}

enum class Verdict(val wire: String) { Pass("pass"), Fail("fail"), CannotVerify("cannot_verify") }

/** 一条标准的核对结果;[status] 为 met / unmet / unknown,[quote] 是支持它的屏幕原文。 */
data class CriterionCheck(val criterion: String, val status: String, val quote: String?)

/**
 * [groundedBy] 为 "image":文字证据里找不到的只有画面上的状态(不带数字的引文),由看过截图的核查器判 pass;
 * 运行记录里与按文字核实的 pass 区分开。[notFound] 是兜底在任何证据来源里都没找到的回答事实。
 */
data class Verification(
    val verdict: Verdict,
    val checks: List<CriterionCheck>,
    val reason: String,
    val groundedBy: String? = null,
    val notFound: List<String> = emptyList(),
) {
    /** 没满足的标准;核查器判 fail 却没指出哪条时用它给的理由。 */
    val unmet: List<String>
        get() = checks.filter { it.status == "unmet" }.map { it.criterion }.ifEmpty { if (verdict == Verdict.Fail) listOf(reason) else emptyList() }
}

/**
 * 交给核查器的证据。[fresh] 是回答之后由最近在用的那个能力重新看的一眼([com.ai.assistance.quro.core.mika.capability.Capability.freshLook]:
 * 界面状态、网页正文的开头……),[freshImage] 是同一时刻的画面(只在要附图时有);
 * [ledger] 是本次任务读到的全部观察(证据账本,见 [EvidenceLedger];核查时按回答挑出相关的条目),
 * [actions] 是精简的动作清单(「第3步 tap(搜索) 生效」)。
 * [protected] 表示此刻的页面受防截屏保护。[appOpened] 为 false 表示本次任务没有成功打开过 App。
 */
class Evidence(
    val fresh: List<Observation>,
    val freshImage: ByteArray?,
    val ledger: EvidenceLedger,
    val actions: List<String>,
    val protected: Boolean,
    val appOpened: Boolean = true,
    /** 每次接管的结局,不随动作清单滚出(跳过、超时说明用户没有处理)。 */
    val takeovers: List<String> = emptyList(),
    /** 用户给的事实:记忆检索结果、本次任务里对提问的回答。 */
    val userFacts: List<String> = emptyList(),
    /** 运行时钟:核查当下的时刻和时区。回答正文里紧挨着「明天」「周日」的日期按它换算核对(见 [Grounding.check])。 */
    val nowMs: Long = System.currentTimeMillis(),
    val zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
) {
    /** 运行时钟的日期。 */
    val today: java.time.LocalDate get() = java.time.Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()

    /** 再看一眼读到的文字,一条观察一段。 */
    val freshText: String get() = fresh.joinToString("\n") { it.text }

    /** 没打开 App、也没读到任何界面、网页或命令输出:回答只能凭对话或常识,没有可对照的材料。 */
    val nothingRead: Boolean get() = !appOpened && !protected && freshText.isBlank() && ledger.isEmpty && userFacts.isEmpty()

    /** 账本里某几类观察的原文,按读到的先后(看账本记了什么用)。 */
    fun read(vararg kinds: ObsKind): List<String> = ledger.all.filter { it.kind in kinds }.map { it.text }

    /** 能对照回答的全部文字,一次读的一处:再看的一眼、账本里挑出的条目 [chosen](全文)、用户给的每条事实。 */
    fun sources(chosen: List<Observation>): List<String> =
        (listOf(freshText) + chosen.map { it.text } + userFacts).filter { it.isNotBlank() }
}

/**
 * 最终回答交出前的独立核查(只在做过不可逆、对外的事,或字面核对有对不上的时候才请它,见 AnswerReview):一次新的模型调用,不带工具、不带干活模型的历史,只看目标、标准、回答和证据。
 * 有截图且配了视觉模型时用 [visionModel] 并附图,否则用 [textModel]。
 * 分工:代码兜底([Grounding])只核字面——模型判 pass 但回答里的具体事实(引文、带单位的数、编号、日期)在证据文字里找不到
 * → cannot_verify;附了截图、找不到的只是画面上的状态 → pass,记为看图核实。一个值是不是属于回答说的那个东西
 * (哪个页面、哪个商品、哪一行)由核查模型判:交给它的账本条目是节选([EvidenceLedger.excerpt]),带着来处。
 * 出错、回复解析不了 → cannot_verify;超时按证据多少放宽([timeoutMs]),超时后精简证据再问一次,还超时才 cannot_verify。
 * 什么也没读过的任务([Evidence.nothingRead])没有材料可对照,不做这条兜底,只告诉核查器没有读到任何材料,由它判断回答是否切题。
 */
class Verifier(
    private val textModel: ChatModel,
    private val visionModel: ChatModel?,
    /** 截图按 [ModelImage] 的统一编码发出;为空时原样发。 */
    private val ops: ImageOps? = null,
) {
    /** 有视觉模型时才值得附画面。 */
    val wantsImage: Boolean get() = visionModel != null

    /**
     * [earlierGoals] 是本对话之前几轮用户说的话(只有用户原话,没有干活模型的任何东西),追问时用来理解指代。
     * 目标、回答、读屏、动作清单都是不可信的材料,编成一个 JSON 对象发出,里面的换行伪造不出新的段落。
     */
    suspend fun verify(goal: String, criteria: Criteria, answer: String, evidence: Evidence, earlierGoals: List<String> = emptyList()): Verification {
        val chosen = evidence.ledger.forAnswer(goal, answer)
        val reads = evidence.sources(chosen)
        if (evidence.protected && reads.isEmpty()) return cannot("结果页受系统防截屏保护,看不到画面,没法核实")
        val image = evidence.freshImage?.takeIf { visionModel != null && !evidence.protected }
        val model = if (image != null) visionModel!! else textModel
        val v = when (val first = ask(model, goal, criteria, answer, evidence, chosen, EvidenceLedger.EXCERPT_CHARS, earlierGoals, image)) {
            is Asked.Done -> first.v
            is Asked.Failed -> return cannot(first.reason)
            // 证据多的长任务容易超时:精简后再问一次
            Asked.TimedOut -> when (val second = ask(model, goal, criteria, answer, trimmed(evidence), chosen, TRIM_EXCERPT_CHARS, earlierGoals, image)) {
                is Asked.Done -> second.v
                is Asked.Failed -> return cannot(second.reason)
                Asked.TimedOut -> return cannot("核查超时")
            }
        }
        return when (v.verdict) {
            Verdict.Pass -> when {
                // 没有逐条核对每一条标准的 pass 不算数
                v.checks.size < criteria.items.size -> v.copy(verdict = Verdict.CannotVerify, reason = NOT_EVERY_CRITERION)
                evidence.nothingRead -> v
                else -> backstop(v, goal, answer, reads, imageSent = image != null, evidence.today)
            }
            // 接着上文的追问没列出标准,只能对照一句没头没尾的原话,判 fail 不可靠
            Verdict.Fail -> if (criteria.fallback && earlierGoals.isNotEmpty()) v.copy(verdict = Verdict.CannotVerify, reason = FOLLOW_UP_UNCLEAR)
                else if (v.reason.isBlank()) v.copy(reason = FAIL_WITHOUT_REASON) else v
            Verdict.CannotVerify -> v
        }
    }

    private fun cannot(reason: String) = Verification(Verdict.CannotVerify, emptyList(), reason)

    private sealed interface Asked {
        class Done(val v: Verification) : Asked
        class Failed(val reason: String) : Asked
        data object TimedOut : Asked
    }

    private suspend fun ask(
        model: ChatModel, goal: String, criteria: Criteria, answer: String, evidence: Evidence, chosen: List<Observation>, budget: Int,
        earlierGoals: List<String>, image: ByteArray?,
    ): Asked {
        val body = request(goal, criteria, answer, evidence, excerpts(goal, answer, chosen, budget, evidence.today), earlierGoals)
        val parts = listOfNotNull(Part.Text(body), image?.let { ModelImage.part(it, ops) })
        var unparsed = false
        return try {
            withTimeoutOrNull(timeoutMs(body.length, if (image != null) 1 else 0)) {
                // 回复走容错解析;读不出来时再问一次、只要 JSON;还不行才算解析不了
                ModelJson.completeParsed(model, PROMPT, listOf(Message(Role.User, parts, Author.ZorvAI)), ::parse).also { unparsed = it == null }
            }?.let { Asked.Done(it) } ?: if (unparsed) Asked.Failed("核查器的回复无法解析") else Asked.TimedOut
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Asked.Failed("核查器出错:${e.message ?: e::class.simpleName}")
        }
    }

    /** 超时那次重问用的精简证据:此刻一屏和截图保留,动作只留最近的,每段截短;账本条目的节选另按更小的字数上限([TRIM_EXCERPT_CHARS])。 */
    private fun trimmed(e: Evidence) = Evidence(
        trimmedFresh(e.fresh), e.freshImage, e.ledger,
        e.actions.takeLast(TRIM_ACTIONS), e.protected, e.appOpened, e.takeovers,
        e.userFacts.map { it.take(TRIM_CHARS) }, e.nowMs, e.zone,
    )

    /** 再看的一眼合起来至多 [TRIM_FRESH_CHARS] 字(按先后截)。 */
    private fun trimmedFresh(fresh: List<Observation>): List<Observation> {
        var room = TRIM_FRESH_CHARS
        return fresh.mapNotNull { o ->
            if (room <= 0) null else o.copy(text = o.text.take(room)).also { room -= it.text.length + 1 }
        }
    }

    private fun request(goal: String, c: Criteria, answer: String, e: Evidence, shown: List<Observation>, earlierGoals: List<String>): String {
        val material = buildJsonObject {
            put("goal", goal)
            if (earlierGoals.isNotEmpty()) put("earlierGoals", JsonArray(earlierGoals.map(::JsonPrimitive)))
            put("criteria", JsonArray(c.items.map(::JsonPrimitive)))
            if (c.fallback) put("criteriaNote", "没有另列标准,criteria 只是用户原话本身,请对照用户的目标核对")
            put("kind", c.kind)
            put("answer", answer)
            put("freshLook", if (e.nothingRead) NOTHING_READ else freshShown(goal, answer, e).ifBlank { if (e.protected) "(页面受防截屏保护,读不到)" else "(没读到文字)" })
            put("ledger", JsonArray(shown.map { r ->
                buildJsonObject {
                    if (r.step > 0) put("step", r.step)
                    put("kind", r.kind.wire)
                    r.source?.let { put("source", it) }
                    r.where?.let { put("where", it) }
                    r.title?.let { put("title", it) }
                    put("text", r.text)
                }
            }))
            put("actions", JsonArray(e.actions.map(::JsonPrimitive)))
            if (e.takeovers.isNotEmpty()) put("takeovers", JsonArray(e.takeovers.map(::JsonPrimitive)))
            if (e.userFacts.isNotEmpty()) put("userFacts", JsonArray(e.userFacts.map(::JsonPrimitive)))
        }
        return "下面是待核对的材料(JSON,字段说明见系统提示):\n$material"
    }

    companion object {
        /** 核查不该把任务拖住:基础等待时间;证据多时按 [timeoutMs] 放宽,最多 [MAX_TIMEOUT_MS]。 */
        const val TIMEOUT_MS = 60_000L
        const val MAX_TIMEOUT_MS = 150_000L
        private const val PER_IMAGE_MS = 20_000L
        private const val PER_KB_MS = 1_000L
        private const val TRIM_FRESH_CHARS = 6_000
        private const val TRIM_CHARS = 3_000
        private const val TRIM_ACTIONS = 10
        private const val TRIM_EXCERPT_CHARS = EvidenceLedger.EXCERPT_CHARS / 2
        /** 再看的一眼长过这么多字时只给节选(开头和对上回答事实的行)。 */
        private const val FRESH_CHARS = 4_000
        const val IMAGE = "image"

        /** 等多久与要读的东西成正比:基础 60 秒,每张图、每 KB 文字另加,封顶 150 秒。 */
        fun timeoutMs(textChars: Int, images: Int): Long =
            minOf(MAX_TIMEOUT_MS, TIMEOUT_MS + images * PER_IMAGE_MS + textChars / 1024 * PER_KB_MS)
        const val NOT_IN_READS = "回答的依据在读到的界面、网页或命令输出里都找不到"
        const val NOT_EVERY_CRITERION = "核查器没有逐条核对每一条标准"
        const val FAIL_WITHOUT_REASON = "核查器认为没有做成,但没有指出哪条标准没满足"
        const val FOLLOW_UP_UNCLEAR = "这是接着上文的追问,没能列出具体标准,核查判不准"
        const val NOTHING_READ = "本次任务没有打开 App,也没有读到任何材料"
        const val TRUNCATED = "回答依据的是屏上被截断的文字(以…结尾),只看到了一部分,没能完整核实"
        private val STATUSES = setOf("met", "unmet", "unknown")

        val PROMPT: String by lazy { Prompts.role("verifier") }

        /**
         * 交给核查模型的账本条目:每条换成节选([EvidenceLedger.excerpt]),对上回答事实的行一定留下,条目一条不丢。
         * 先按前后各 [EvidenceLedger.EXCERPT_CONTEXT] 行取,总字数超过 [budget] 就减少前后的行数,还超就把每条截到平均的份额(至少 [EvidenceLedger.MIN_EXCERPT] 字)。
         */
        internal fun excerpts(goal: String, answer: String, chosen: List<Observation>, budget: Int, today: java.time.LocalDate? = null): List<Observation> {
            val hits = chosen.map { Grounding.hitLines(goal, answer, it.text, today) }
            for (ctx in EvidenceLedger.EXCERPT_CONTEXT downTo 0) {
                val ex = chosen.mapIndexed { i, r -> EvidenceLedger.excerpt(r.text, hits[i], ctx) }
                if (ex.sumOf { it.length } <= budget) return chosen.mapIndexed { i, r -> r.copy(text = ex[i]) }
            }
            val cap = maxOf(EvidenceLedger.MIN_EXCERPT, budget / chosen.size.coerceAtLeast(1))
            return chosen.mapIndexed { i, r -> r.copy(text = EvidenceLedger.excerpt(r.text, hits[i], 0, cap)) }
        }

        /** 交给核查模型的再看一眼:不长时原样,长了给开头和对上回答事实的行。 */
        private fun freshShown(goal: String, answer: String, e: Evidence): String {
            val t = e.freshText
            if (t.length <= FRESH_CHARS) return t
            return EvidenceLedger.excerpt(t, Grounding.hitLines(goal, answer, t, e.today), 1, FRESH_CHARS, withHead = true)
        }

        /**
         * 模型判 pass 之后的兜底(规则见 [Grounding.check]):只核字面,不判断值属于哪个东西。文字对不上时:附了截图、没对上的只有画面上的状态(不带数字的引文;
         * 引文还要求回答别处已在文字里对上),看过图的 pass 算数,记为看图核实;数和日期能写成文字,对不上就否决。
         * [today] 是运行时钟的日期(相对日期按它换算,见 [Grounding.check])。离线重算录下来的运行也用它。
         */
        internal fun backstop(v: Verification, goal: String, answer: String, screens: List<String>, imageSent: Boolean, today: java.time.LocalDate? = null): Verification {
            val g = Grounding.check(goal, answer, screens, today)
            val ok = when (g.found) {
                true -> true
                false -> false
                // 回答里没有可核对的东西,或只有数对上、没有引文定位:至少要有一条 met 的引文真在屏上
                null -> v.checks.any { it.status == "met" && it.quote != null && Grounding.quoteInReads(it.quote, screens) }
            }
            val missing = g.unmatched.map { it.text }
            val result = when {
                ok -> v
                imageSent && g.unmatched.all { it.visualOnly } && (g.anchored || g.unmatched.none { it.kind == Grounding.Kind.Quote }) ->
                    v.copy(groundedBy = IMAGE, notFound = missing)
                else -> v.copy(verdict = Verdict.CannotVerify, reason = NOT_IN_READS, notFound = missing)
            }
            // 依据是屏上被截断的文字(以 … 结尾):只看到了一部分,核实不了全貌
            return if (result.verdict == Verdict.Pass && Grounding.builtOnTruncated(answer, screens)) result.copy(verdict = Verdict.CannotVerify, reason = TRUNCATED) else result
        }

        /**
         * 回复里第一个能读的 JSON 对象(见 [ModelJson]:容忍代码块、前后的话、键名写法);结论缺失或不认识时返回 null。
         * 各条结论和总结论矛盾时按各条算。
         */
        internal fun parse(text: String): Verification? {
            val obj = ModelJson.firstObject(text) ?: return null
            val verdict = when ((obj.field("verdict", "result", "conclusion") as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase()?.replace('-', '_')?.replace(' ', '_')) {
                "pass", "passed" -> Verdict.Pass
                "fail", "failed" -> Verdict.Fail
                "cannot_verify", "cannotverify", "unknown" -> Verdict.CannotVerify
                else -> return null
            }
            val checks = (obj.field("checks", "criteria_checks", "results") as? JsonArray).orEmpty().mapNotNull { c ->
                val o = c as? JsonObject ?: return@mapNotNull null
                val criterion = (o.field("criterion", "criteria", "item") as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty) ?: return@mapNotNull null
                val status = (o.field("status", "state") as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase()?.takeIf { it in STATUSES } ?: "unknown"
                val quote = (o.field("quote", "evidence") as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf(String::isNotBlank)
                CriterionCheck(criterion, status, quote)
            }
            val reason = (obj.field("reason", "explanation") as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            val effective = when {
                checks.any { it.status == "unmet" } -> Verdict.Fail
                verdict == Verdict.Pass && checks.any { it.status == "unknown" } -> Verdict.CannotVerify
                else -> verdict
            }
            return Verification(effective, checks, reason)
        }
    }
}

