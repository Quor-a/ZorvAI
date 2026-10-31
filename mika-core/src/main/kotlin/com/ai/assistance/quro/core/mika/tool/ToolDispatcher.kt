package com.ai.assistance.quro.core.mika.tool

import com.ai.assistance.quro.core.mika.knowledge.Link
import com.ai.assistance.quro.core.mika.observation.CORE_SOURCE
import com.ai.assistance.quro.core.mika.observation.ObsKind
import com.ai.assistance.quro.core.mika.observation.Observation
import com.ai.assistance.quro.core.mika.knowledge.GuideCatalog
import com.ai.assistance.quro.core.mika.memory.Change
import com.ai.assistance.quro.core.mika.memory.UserUtterances
import com.ai.assistance.quro.core.mika.model.Part
import com.ai.assistance.quro.core.mika.model.ToolSpec
import com.ai.assistance.quro.core.mika.prompt.Feedback
import com.ai.assistance.quro.core.mika.risk.GateTarget
import com.ai.assistance.quro.core.mika.run.SKIPPED_FOR_NEW_MESSAGE
import com.ai.assistance.quro.core.mika.run.waitOrSteer
import com.ai.assistance.quro.core.mika.verify.Evidence
import com.ai.assistance.quro.core.mika.vision.ImageOps
import com.ai.assistance.quro.core.mika.vision.ModelImage
import com.ai.assistance.quro.core.mika.vision.VisionService
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.ai.assistance.quro.core.mika.visual.Shots
import com.ai.assistance.quro.core.mika.capability.Capability
import com.ai.assistance.quro.core.mika.capability.Paced
import com.ai.assistance.quro.core.mika.capability.PromptEnv
import com.ai.assistance.quro.core.mika.capability.PromptSections
import com.ai.assistance.quro.core.mika.guard.OffScreenGuard

/**
 * 工具调用的入口:解析参数,按名字在 [ToolRegistry] 里找到工具执行,再做所有工具共用的收尾
 * (动作之后附屏幕、清掉看图得来的点、动作清单、核查材料)。具体工具都在各自的能力里,这里不认识。
 * 由装配层按本任务的能力造出来:[tools] 是全部工具(按发给模型的顺序),[sections] 是系统提示词各组段落(按拼接顺序)。
 *
 * 有界面的能力(手机、网页)的界面状态不在这里:装配层给一个 [SurfaceLayer],分发器每次调用前后只经它做界面上的事。
 * 每次调用前按工具所属的屏记下在操作哪块屏([aim]),用错了屏的编号时不交给端口,直接说明该用哪一套工具。
 */
class ToolDispatcher internal constructor(
    private val capabilities: List<Capability>,
    tools: List<AgentTool>,
    private val sections: List<PromptSections>,
    config: ToolConfig,
    /** 有界面的能力共用的那一层;按本任务的工具上下文造(界面状态放进上下文,能力的工具从那里取)。 */
    surfaceLayer: (ToolContext) -> SurfaceLayer,
    /** 改名前的工具名怎么认(见 [LegacyNames])。 */
    formerPrefix: Map<String, String> = emptyMap(),
    renamed: Map<String, String> = emptyMap(),
    /** 去掉了、没有替代工具的旧名 → 现在该怎么做(见 [LegacyNames])。 */
    retired: Map<String, String> = emptyMap(),
    /** 整个换了名字的旧名 → 现在的名字(见 [LegacyNames])。 */
    moved: Map<String, List<String>> = emptyMap(),
) {
    private val evidenceLog = EvidenceLog()
    private val shots = Shots()
    internal val ctx = ToolContext(
        config, evidenceLog, ToolIndex(tools), capabilities.firstNotNullOfOrNull { it.scripts }, shots,
    )
    /** 有界面的能力(手机、网页)共用的那一层;界面上的事都经它。 */
    private val surfaces = surfaceLayer(ctx)

    init {
        capabilities.forEach { it.onTaskStart(ctx) }
    }

    /** 用到才展开的组:收着的能力各一组,有屏幕的能力共用的通用工具一组([ToolGroups.SCREENS];没有收着的屏时它们一直都在)。 */
    private val folding: List<String> = capabilities.filter { it.folds }.map { it.id }
    private val screenGroups: Set<String> = capabilities.filter { it.folds && it.family != null }.map { it.id }.toSet()

    private fun groupOf(t: AgentTool): String? = when {
        t.capability in folding -> t.capability
        t.withScreens && screenGroups.isNotEmpty() -> ToolGroups.SCREENS
        else -> null
    }

    private val registry = ToolRegistry(ctx, tools, ::groupOf)

    /** 本任务已展开的能力组(按展开的先后)。 */
    private val opened = linkedSetOf<String>()
    /** 本任务真的用过的能力组(调过组里的工具;单用就够的入口不算):下一轮开局据此预先展开。 */
    private val usedGroups = linkedSetOf<String>()

    /** 本任务给了工具、却一个入口都没给的组(只读的任务里跑命令不给):不收,一开始就展开,免得整组看不见。 */
    private val entryless: Set<String> by lazy { folding.filter { registry.members(it).isNotEmpty() && !registry.hasEntry(it) }.toSet() }

    /** 已展开的能力组(不含 [ToolGroups.SCREENS])。 */
    val expanded: Set<String> get() = entryless + opened

    /** 本任务真的用过的能力组。 */
    val used: Set<String> get() = usedGroups.toSet()

    /** 展开 [group](本任务提供了它的工具才展开);新展开的返回 true。 */
    fun expand(group: String): Boolean = group in folding && group !in entryless && registry.members(group).isNotEmpty() && opened.add(group)

    /**
     * 开局预先展开:上一轮用过的组([previous]),和能力按这次的话认为一定用得上的(带着工作区里的文件时的沙箱,见 [Capability.preload])。
     * 返回展开了的组。
     */
    fun preload(task: String, previous: Collection<String>): List<String> =
        capabilities.filter { it.folds && (it.id in previous || it.preload(task)) }.map { it.id }.filter(::expand)

    private fun openSet(): Set<String> = expanded.let { e -> if (e.any { it in screenGroups }) e + ToolGroups.SCREENS else e }

    /**
     * 此刻发给模型的工具:一直都在的、展开了的组(全文),收着的组只给入口(短说明),顺序固定(见 [ToolGroups])。
     * [screenExtra] 是循环自己另给的、随有屏幕的组一起出现的定义(一次多步)。[all] 时当每组都展开了。
     */
    fun offeredNow(screenExtra: List<ToolSpec> = emptyList(), all: Boolean = false): List<ToolSpec> =
        registry.layered(if (all) folding.toSet() + ToolGroups.SCREENS else openSet(), if (screenGroups.isEmpty()) emptyMap() else mapOf(ToolGroups.SCREENS to screenExtra)).let {
            if (screenGroups.isEmpty()) it + screenExtra else it
        }

    /** 改名前的工具名 → 现在的名字;读旧的个人技能、做法时用,调用旧名时提示新名。 */
    val legacy = LegacyNames(ctx.tools, formerPrefix, renamed, retired, moved)

    /** 看图模型;为空(设置里没有能看图的模型)时不提供视觉类工具。 */
    val vision: VisionService? get() = ctx.config.vision
    /** 主模型自己看得到图。 */
    val seesImages: Boolean get() = ctx.config.visionEnabled
    /** 写操作成功后等界面稳定再读一屏,把与上一屏的差异放进结果的 screen 字段。 */
    val inlineScreen: Boolean get() = ctx.config.inlineScreen
    /** 用户本次说过的话,memory_save 的引文据此校验;AgentLoop 往里加。 */
    val utterances: UserUtterances? get() = ctx.config.utterances
    private val imageOps: ImageOps? get() = ctx.config.imageOps

    /** 给循环拼「可用渠道」行用(看技能有没有网页做法)。 */
    internal val skillCatalog: GuideCatalog? get() = ctx.config.skills
    /** 最近在用的那个有界面的能力的 id;任务开始时是第一个(手机)。 */
    val surface: String get() = surfaces.current

    /** 运行状态里写的此刻那个地方的版本(网页的手机版、电脑版);没有或读不到为 null。 */
    suspend fun placeMode(): String? = surfaces.mode()

    /** 本任务提供跑技能脚本的工具(参考资料的附注按它写)。 */
    fun scriptsOffered(): Boolean = ctx.scripts?.offered() == true

    /**
     * 系统提示词里各能力与核心的段落,按发给模型的同一份工具列表 [specs] 判断有没有;收着的能力组只在展开后才有自己的段落。
     * [all] 时当每组都展开了(提示词版本按全部的样子算)。
     */
    fun promptSections(specs: List<ToolSpec>, all: Boolean = false): List<String> {
        val offered = specs.map { it.name }.toSet()
        val screens = capabilities.filter { it.family != null }.count { c -> offered.any { n -> ctx.tools[n]?.capability == c.id } }
        val open = if (all) null else capabilities.filter { !it.folds || it.id in expanded }.map { it.id }.toSet()
        val openScreens = capabilities.filter { it.family != null && (open == null || it.id in open) }
            .count { c -> offered.any { n -> ctx.tools[n]?.capability == c.id } }
        val env = PromptEnv(offered, inlineScreen, screens, open, openScreens)
        return sections.flatMap { it.sections(env) }
    }

    /** 不动屏幕的工具的守卫(本任务一份);没有为 null。 */
    fun offScreenGuard(): OffScreenGuard? = capabilities.firstNotNullOfOrNull { it.guard() }

    fun definitions(): List<ToolSpec> = registry.definitions()

    /** 会改变屏幕、页面状态(要过锁屏/暂停关卡):由工具声明的风险推出。 */
    fun writesScreen(name: String): Boolean = registry.declared(name)?.writesScreen == true

    /** 不动屏幕的工具(沙箱一类):不进屏幕守卫,由各自的守卫另管;结果里的图是文件。 */
    fun offScreen(name: String): Boolean = registry.declared(name)?.offScreen == true

    /** 模型给这次调用写的用意(附加的 reason 参数);工具自己有叫 reason 的字段、没写或写空时为 null。 */
    fun callReason(name: String, args: JsonObject): String? {
        if (registry.declared(name)?.def?.ownReason == true) return null
        return (args["reason"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.ifEmpty { null }
    }

    /** 按名字查工具的声明(动作、能否批量、界面上的说法……)。 */
    val index: ToolIndex get() = ctx.tools

    /** 能放进 do_steps 的工具名,按定义里列出的顺序。 */
    fun batchable(): List<String> = registry.batchable()

    /** 这个工具做的动作;不认识的名字为 null。 */
    fun verb(name: String?): Verb? = ctx.tools.verb(name)

    /** 这个工具作用在哪块屏上(屏幕工具的能力 id);不是屏幕工具为 null。 */
    fun surfaceOf(name: String): String? = surfaces.surfaceOf(registry.declared(name))

    /** 自己不动界面、里面的动作一个个经执行入口回来(页面脚本)。 */
    fun composite(name: String): Boolean = registry.declared(name)?.composite == true

    /** 不可逆判定按什么看这个工具的目标;只有声明为 External 的工具有。 */
    fun gateTarget(name: String): GateTarget? = registry.declared(name)?.takeIf { it.risk == Risk.External }?.gateTarget

    /** 循环每次裁剪、压缩历史后告诉分发器模型还看得到哪些读取(按编号);差异的 base 不在其中时发全量。 */
    fun setVisibleObservations(readIds: Set<String>) = surfaces.setVisible(readIds)

    /**
     * 一次调用开始:记下它在操作哪块屏(屏幕工具是它自己那块,带 surface 参数的通用工具按参数;其余沿用此刻那块),
     * 直到对应的 [release]。执行入口在判定之前进一次,分发时再进一次。
     */
    fun aim(name: String, args: JsonObject) {
        surfaces.enter(registry.declared(name)?.aimAt(args))
    }

    /** 那次调用结束;成功了它那块屏才算最近操作的屏(失败的调用不改默认值)。 */
    fun release(succeeded: Boolean) = surfaces.leave(succeeded)

    /**
     * 用另一块屏的编号操作这块屏的工具:不交给端口,结果是 wrong_surface,说明编号属于哪块屏、该用哪个工具。
     * 认不出编号是哪块屏的(过期太久、拼错)时为 null,照常交给工具(按过期处理)。
     */
    fun misaimed(name: String, args: JsonObject): ToolExecution? = registry.declared(name)?.let { surfaces.misaimed(it, args, ctx.tools) }

    /** 只读的调用(读屏、报失败一类):工具声明会改变屏幕、页面或沙箱的一律拒绝,那些只能经执行入口做。 */
    suspend fun executeRead(call: Part.ToolCall, step: Int? = null): ToolExecution {
        val tool = registry.declared(call.name)
        if (tool != null && !tool.readOnly) {
            return fail("not_a_read", Feedback.format("not_a_read", "tool" to call.name), recoverable = false)
        }
        return dispatch(call, step, null)
    }

    /**
     * [pass] 只有执行入口给得出(守卫、关卡、不可逆判定、记录都在那里)。
     * [step]:循环的轮次编号,和折叠摘要的「第 N 步」一致;不给时按已执行的调用数。
     * [sub]:web_script 里的动作经它回到循环(守卫、关卡、记录照常);不给时脚本里的动作一律不做。
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun execute(call: Part.ToolCall, pass: ActionPass, step: Int? = null, sub: SubActions? = null): ToolExecution = dispatch(call, step, sub)

    private var executed = 0

    private suspend fun dispatch(call: Part.ToolCall, step: Int?, sub: SubActions?): ToolExecution {
        surfaces.skip()
        val tool = registry[call.name] ?: return unknown(call.name, parseArgs(call.argsJson))
        // 收着的组里的工具(入口,或模型凭记忆调了没给它看的)照常执行;它那一组就此展开,结果里说一句,下一次调用起看得到整组
        val group = groupOf(tool)
        if (group != null && group != ToolGroups.SCREENS && tool.entry != Entry.Alone) usedGroups += group
        val opening = when {
            group == null || tool.entry == Entry.Alone -> null
            group == ToolGroups.SCREENS -> surfaces.current.takeIf { it in screenGroups }
            else -> group
        }?.takeIf(::expand)
        val result = dispatchTool(tool, call, step, sub)
        return if (opening == null) result else result.with(content = JsonObject(result.content + (MORE_TOOLS to JsonPrimitive(Feedback.format("group_opened",
            "group" to opening, "examples" to registry.members(opening).filter { registry[it]?.entry == null }.take(4).joinToString())))))
    }

    private suspend fun dispatchTool(tool: AgentTool, call: Part.ToolCall, step: Int?, sub: SubActions?): ToolExecution {
        val args = try {
            val el = if (call.argsJson.isBlank()) JsonObject(emptyMap()) else Json.parseToJsonElement(call.argsJson)
            Args(tool.def(ctx), el as? JsonObject ?: throw ArgError("arguments", "must be a JSON object"))
        } catch (e: ArgError) {
            return invalid(e)
        } catch (e: kotlinx.serialization.SerializationException) {
            return invalid(ArgError("arguments", "not valid JSON"))
        }
        shots.idAsSnapshot(args.o)?.let { return it }
        misaimed(call.name, args.o)?.let { return it }
        aim(call.name, args.o)
        var ok = false
        try {
            return run(tool, args, call, step, sub).also { ok = it.content["ok"] == JsonPrimitive(true) }
        } finally {
            release(ok)
        }
    }

    private suspend fun run(tool: AgentTool, args: Args, call: Part.ToolCall, step: Int?, sub: SubActions?): ToolExecution {
        executed++
        ctx.currentStep = step ?: executed
        evidenceLog.step = ctx.currentStep
        ctx.callId = call.id
        ctx.sub = sub
        // 页面脚本里的动作也经这里:各自记自己的停留,外层的照样含着里面的
        val outerWait = ctx.pacedWaitMs
        val outerIdle = ctx.idleScrolls
        ctx.pacedWaitMs = 0
        ctx.idleScrolls = 0
        // 界面上的准备(路线要的目标、改变界面的动作前先看一眼)与收尾都在界面一侧
        val result = surfaces.around(tool, args.o, inlineScreen) { reportsEffect ->
            ctx.reportsEffect = reportsEffect
            try {
                if (!tool.yieldsToSteer) tool.execute(args, ctx)
                else waitOrSteer { tool.execute(args, ctx) } ?: failCode(SKIPPED_FOR_NEW_MESSAGE)
            } catch (e: ArgError) {
                invalid(e)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail("operation_failed", Feedback.format("operation_failed", "tool" to tool.name, "error" to (e.message ?: e::class.simpleName)))
            } finally {
                ctx.reportsEffect = false
            }
        }
        val waited = ctx.pacedWaitMs
        ctx.pacedWaitMs = outerWait + waited
        val idled = ctx.idleScrolls
        ctx.idleScrolls = outerIdle + idled
        val ok = result.content["ok"] == JsonPrimitive(true)
        if (tool.logged) evidenceLog.logAction(stepLabel(), tool.name, tool.logTarget(args.o, result, ctx), tool.logTyped(args.o, result), result, tool.wideTarget)
        // 读到的(结果里能力给的,和工具按结果另给的)记进账本,结果里换成账本里那几条
        val seen = remember(result.observations + tool.observations(args.o, result, evidenceLog), tool.capability)
        // 打进输入框的字(端口明确说不是密码框):之后读屏里的输入框回显不给回答作证
        if (tool.verb == Verb.TypeText && ok && result.content["secret"] == JsonPrimitive(false)) args.o.argStr("text")?.let(evidenceLog::noteTyped)
        // 停留里轻轻滚过页面:只记进运行记录,不给模型
        val record = if (idled > 0) JsonObject(result.record + ("idleScrolls" to JsonPrimitive(idled))) else result.record
        return result.with(observations = seen, place = placeAfter(tool.verb, result.content), waitMs = waited, record = record)
    }

    /** 记进账本;没写出处的按 [capability](核心的工具为 core)。返回账本里记下的那几条,每条也交给 [observer]。 */
    private fun remember(observations: List<Observation>, capability: String?): List<Observation> =
        observations.mapNotNull { o -> evidenceLog.observe(if (o.source != null) o else o.copy(source = capability ?: CORE_SOURCE))?.also { observer?.invoke(it) } }

    /** 每条记进证据账本的观察(运行记录据此原样记下,评测能照样重建账本);由循环设置。 */
    var observer: ((Observation) -> Unit)? = null

    /** 不认识的工具;是改名前的名字时说清现在叫什么(不替模型换成新名执行)。 */
    private fun unknown(name: String, args: JsonObject) = ToolExecution(buildJsonObject {
        put("ok", false); put("error", "unknown_tool"); put("tool", name)
        val now = legacy.replacements(name, args).filter { registry[it] != null }
        val instead = legacy.retiredAs(name)
        put("message", if (instead != null) Feedback.format("unknown_tool_retired", "name" to name, "instead" to instead)
            else if (now.isNotEmpty()) Feedback.format("unknown_tool_renamed", "name" to name, "tools" to now.joinToString(" or "))
            else Feedback.format("unknown_tool", "name" to name, "tools" to (registry.listedNames().joinToString())))
        put("recoverable", true)
    })

    /** 证据账本此刻的位置(见 [EvidenceLedger.lastId]);动作之前记下,之后用 [readSince] 看动作之后有没有重新读过。 */
    fun ledgerMark(): Int = evidenceLog.ledger.lastId

    fun readSince(mark: Int): Boolean = evidenceLog.ledger.readSince(mark)

    /** 按名字找本任务登记的工具;不认识为 null。 */
    internal fun declared(name: String): AgentTool? = registry.declared(name)

    /** 工具声明的风险;不认识的名字为 null。 */
    fun declaredRisk(name: String): Risk? = registry.declared(name)?.risk
    fun approvalRisk(name: String): com.ai.assistance.quro.core.mika.action.Risk? = registry.declared(name)?.approvalRisk

    /** ask_user 拿到的用户回答;核查器按用户给的事实对照。 */
    /** 用户发来的一屏(屏上的字)也是依据:回答里说的屏上内容从这里来。 */
    fun noteUserScreen(screen: com.ai.assistance.quro.core.mika.userscreen.UserScreen) {
        if (screen.blocked || screen.lines.isEmpty()) return
        evidenceLog.observe(Observation(ObsKind.Read, screen.lines.joinToString("\n") { it.text }, source = CORE_SOURCE,
            place = screen.packageName?.let(com.ai.assistance.quro.core.mika.observation.Place::app),
            trust = com.ai.assistance.quro.core.mika.observation.Trust.Untrusted))
    }

    fun noteUserAnswer(prompt: String, answer: String) {
        evidenceLog.observe(Observation(ObsKind.UserSaid, "问用户「${prompt.take(200)}」,用户答:$answer", source = CORE_SOURCE))
        capabilities.forEach { it.onUserAnswered(ctx) }
    }

    /**
     * 给核查器的证据:最近在用的那个能力再看一眼([Capability.freshLook],不影响模型手里的东西),加上本次任务的证据账本和动作清单。
     * [withImage] 时连同一帧画面;防截屏页面不截。[fresh] 为 false 时不再看(只要账本做字面核对时)。
     */
    suspend fun evidence(withImage: Boolean = false, fresh: Boolean = true): Evidence {
        val e = evidenceLog
        // 看不到此刻的样子也照样交出之前读到的,由核查器判断
        val look = if (fresh) capabilities.firstOrNull { it.id == surface }?.freshLook(ctx, withImage) else null
        return Evidence(look?.observations.orEmpty(), look?.image, e.ledger.copy(), e.actionLog.toList(), look?.protected == true, ctx.appOpened,
            e.takeovers.toList(), userFacts = e.userFacts.toList())
    }

    /** 开着时写操作执行前取目标元素与锚点(回放开关打开时由循环设置)。 */
    var captureTargets: Boolean
        get() = surfaces.captureTargets
        set(v) { surfaces.captureTargets = v }

    /** 运行记录用:模型编号此刻对应的端口编号(界面一侧给的;没有为空)。 */
    fun portRecord(tool: String, args: JsonObject): JsonObject = surfaces.portRecord(registry.declared(tool), args)

    /** 等此刻在用的那个地方稳定下来,最多 [timeoutMs];读不到状态也照常返回。 */
    suspend fun settle(timeoutMs: Long) = surfaces.settle(timeoutMs)

    /** 此刻在用的那个地方看得到的字(每个元素一条,不登记编号、不改模型手里的东西);读不到为空。 */
    suspend fun visibleText(): List<String> = surfaces.visibleText()

    /** 在此刻在用的那个地方等到 [until] 成立(或看出意外、到时限、[interrupted]);见 [SurfaceLayer.waitUntil]。 */
    suspend fun waitUntil(until: Until, clock: () -> Long, interrupted: () -> Boolean): UntilOutcome = surfaces.waitUntil(until, clock, interrupted)

    /** 此刻这块屏上要做一次对外动作(发送、提交)了:交给这块屏的能力([Capability.onOutwardStart]),做完一定接着 [outwardEnd]。 */
    fun outwardStart(site: String) {
        capabilities.firstOrNull { it.id == surface }?.onOutwardStart(ctx, site)
    }

    /** 对外动作做完([done]:做成了)。 */
    fun outwardEnd(site: String, done: Boolean) {
        capabilities.firstOrNull { it.id == surface }?.onOutwardEnd(ctx, site, done)
    }

    /** 此刻这块屏上要做一个改动屏幕的动作了:按这块屏的能力的节奏先停一会儿([Capability.beforeWrite])。 */
    internal suspend fun beforeWrite(tool: String, outward: Boolean): Paced =
        capabilities.firstOrNull { it.id == surface }?.beforeWrite(ctx, verb(tool), outward) ?: Paced.NONE

    /** 那个动作做完了:交给这块屏的能力([Capability.afterWrite])。 */
    internal fun afterWrite(tool: String, result: JsonObject) {
        capabilities.firstOrNull { it.id == surface }?.afterWrite(ctx, verb(tool), result)
    }

    /** 接管的结局交回后才知道,由循环补记进动作清单(核查器据此知道接管有没有真的发生)。 */
    fun noteTakeover(reason: String, back: HandBack) {
        val effect = when (back.outcome) {
            HandBackOutcome.complete -> if (back.userFinished) "用户说已自己办完(未核实)" else "用户已交回(未核实)"
            HandBackOutcome.skipped -> "接管被跳过"
            HandBackOutcome.timeout -> "接管超时,无人处理"
            HandBackOutcome.aborted -> "任务被取消"
        }
        evidenceLog.noteTakeover("${stepLabel()} ${ctx.tools.nameOf(Verb.HandToUser)}($reason) $effect")
        if (back.outcome == HandBackOutcome.complete) capabilities.forEach { it.onUserAnswered(ctx) }
    }

    /** 动作清单里的「第 N 步」和循环的轮次一致(批里的几步同属一轮);回放没有轮次。 */
    private fun stepLabel() = if (ctx.currentStep > 0) "第${ctx.currentStep}步" else "回放"

    /** 发给主模型的一张图,按 [ModelImage] 统一编码;[screenshotId] 是屏幕画面的回看编号。 */
    fun modelImage(img: ByteArray, screenshotId: Int?): Part.Image = ModelImage.part(img, imageOps, screenshotId)

    /**
     * 主模型能看图时此刻在用的那个地方的一帧画面和它的编号(可回看);受保护页面、付款页不给,还什么都没打开、拿不到时为 null。
     * 回放交还时用它。
     */
    suspend fun imageNow(): Pair<ByteArray, Int>? = surfaces.imageNow()

    /** 此刻的样子:给模型的内容([content],和读取一样登记编号)连同同一时刻的画面([image],没有为 null)。 */
    class Glance(val content: JsonObject, val image: ByteArray?, val imageId: Int, val observations: List<Observation> = emptyList())

    /** 主模型能看图时动作之后再看一眼:内容和画面是同一时刻的;什么都没打开时为 null。读到的记进账本。 */
    suspend fun lookNow(): Glance? = surfaces.viewNow()?.let { v -> Glance(v.content, v.image, v.imageId, remember(v.observations, surface)) }

    /** 打开链接的工具此刻会不会放行这组参数(只看链接规则与参考资料,不执行)。 */
    fun linkAllowed(action: String?, uri: String?, pkg: String?): Boolean =
        LinkPolicy.check(action, uri, pkg) == null && (action != null || (uri to pkg) in ctx.allowedLinks)

    /** 循环把带链接的参考资料交给模型时登记这些链接。 */
    fun allowLinks(links: List<Link>) {
        links.forEach { ctx.allowedLinks += it.uri to it.packageName }
    }

    /** 本次任务里 memory_save 存下的(结果卡片显示「记住了」并可撤销)。 */
    val savedChanges: List<Change> get() = ctx.savedChanges

    private companion object {
        /** 展开了一组时结果里说明的那个字段。 */
        const val MORE_TOOLS = "moreTools"
    }

    /** 测试用:回看记录里有没有留着图。 */
    internal fun keepsRecallImages(): Boolean = shots.recalls.any { it.png != null }
}
