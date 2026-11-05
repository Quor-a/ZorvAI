package com.ai.assistance.quro.core.mika.assembly

import com.ai.assistance.quro.core.mika.agent.CoreSections
import com.ai.assistance.quro.core.mika.agent.DO_STEPS_TOOL
import com.ai.assistance.quro.core.mika.cap.phone.AppPacing
import com.ai.assistance.quro.core.mika.cap.phone.PHONE
import com.ai.assistance.quro.core.mika.cap.phone.PhoneCapability
import com.ai.assistance.quro.core.mika.cap.sandbox.SandboxCapability
import com.ai.assistance.quro.core.mika.cap.sandbox.SANDBOX
import com.ai.assistance.quro.core.mika.cap.sandbox.SandboxGroup
import com.ai.assistance.quro.core.mika.cap.sandbox.SandboxPort
import com.ai.assistance.quro.core.mika.cap.web.WEB
import com.ai.assistance.quro.core.mika.cap.web.WebCapability
import com.ai.assistance.quro.core.mika.cap.web.WebGroup
import com.ai.assistance.quro.core.mika.cap.web.WebPort
import com.ai.assistance.quro.core.mika.uistate.IdleScroll
import com.ai.assistance.quro.core.mika.cap.web.PagePacing
import com.ai.assistance.quro.core.mika.uistate.PlacePace
import com.ai.assistance.quro.core.mika.cap.mcp.McpConnection
import com.ai.assistance.quro.core.mika.general.GeneralGroup
import com.ai.assistance.quro.core.mika.knowledge.GuideCatalog
import com.ai.assistance.quro.core.mika.memory.MemoryStore
import com.ai.assistance.quro.core.mika.record.RunRecords
import com.ai.assistance.quro.core.mika.memory.ProfileStore
import com.ai.assistance.quro.core.mika.memory.UserUtterances
import com.ai.assistance.quro.core.mika.skills.PersonalSkills
import com.ai.assistance.quro.core.mika.uistate.ScreenPort
import com.ai.assistance.quro.core.mika.tool.AgentTool
import com.ai.assistance.quro.core.mika.tool.LegacyNames
import com.ai.assistance.quro.core.mika.tool.ToolConfig
import com.ai.assistance.quro.core.mika.tool.ToolDispatcher
import com.ai.assistance.quro.core.mika.tool.ToolIndex
import com.ai.assistance.quro.core.mika.vision.ImageOps
import com.ai.assistance.quro.core.mika.vision.VisionService
import com.ai.assistance.quro.core.mika.workspace.WorkspacePathCheck
import com.ai.assistance.quro.core.mika.run.RunId
import com.ai.assistance.quro.core.mika.run.RunSpec
import com.ai.assistance.quro.core.mika.trigger.Scheduled
import com.ai.assistance.quro.core.mika.trigger.TriggerDefinition
import com.ai.assistance.quro.core.mika.trigger.ScheduleAdmin
import com.ai.assistance.quro.core.mika.child.ChildRunHost
import com.ai.assistance.quro.core.mika.userscreen.UserScreenSource

/**
 * 一个任务的工具:装上手机、网页、沙箱三种能力和核心工具。这是唯一同时认识几种能力的地方。
 * 没给端口的能力照样装上(工具都不提供),按名字查声明时它们的工具也查得到。
 */
fun toolDispatcher(
    screen: ScreenPort,
    visionEnabled: Boolean = false,
    /** 只读的知识与技能目录;为空时不提供 knowledge_search / knowledge_read / skill_search / skill_read。 */
    skills: GuideCatalog? = null,
    /** 读屏时另截一张画面给运行记录(看不了图的模型也留画面)。 */
    recordFrames: Boolean = false,
    /** 看图模型;为空(设置里没有能看图的模型)时不提供视觉类工具。 */
    vision: VisionService? = null,
    /** 内置浏览器;为空时不提供任何网页工具,只有手机界面。 */
    web: WebPort? = null,
    /** 网页上传的工作区路径检查(规范化、符号链接解析由 app 侧做)。 */
    workspacePath: ((String) -> WorkspacePathCheck)? = null,
    /** 网页一页一页看的节奏(换页前补足停留,见 [PagePacing]);默认不等。 */
    webPacing: () -> PagePacing = { PagePacing.Off },
    /** 网站对浏览快慢的要求(按主机名);默认按 [skills] 里站点知识的 pace。 */
    webPlacePace: (String) -> PlacePace = { host -> PlacePace.of(skills?.sitePace(host)) },
    /** 网页停留时轻轻滚几下页面(见 [IdleScroll]);默认不滚。 */
    webIdleScroll: () -> IdleScroll? = { null },
    /** 在 App 里一步一步操作的节奏(改动屏幕的动作之前补足停留,见 [AppPacing]);默认不等。 */
    appPacing: () -> AppPacing = { AppPacing.Off },
    /** App 对浏览快慢的要求(按包名);默认按 [skills] 里 App 知识的 pace。 */
    appPace: (String) -> PlacePace = { pkg -> PlacePace.of(skills?.appPace(pkg)) },
    /** App 里新的一屏上的停留时轻轻滚几下(见 [IdleScroll]);默认不滚。 */
    appIdleScroll: () -> IdleScroll? = { null },
    /** 跨读屏的稳定编号:读屏给模型编号和 s<N>,动作前翻译回端口编号。 */
    stableRefs: Boolean = false,
    /** 写操作成功后等界面稳定再读一屏,把与上一屏的差异放进结果的 screen 字段;隐含 [stableRefs]。 */
    inlineScreen: Boolean = false,
    /** 先给模型元素表；只在元素信息明显不足时自动附图。动作仍带回元素变化。 */
    adaptiveVision: Boolean = false,
    /** 提速开关 deepLinks:提供打开链接的工具。 */
    deepLinks: Boolean = false,
    /** 记忆;和 [utterances] 都给了才提供 memory_search / memory_save。 */
    memory: MemoryStore? = null,
    profile: ProfileStore? = null,
    /** 用户本次说过的话,memory_save 的引文据此校验;AgentLoop 往里加。 */
    utterances: UserUtterances? = null,
    /** 本次任务所在的对话;记进记忆的出处。 */
    conversationId: String? = null,
    /** Linux 沙箱;设备支持时提供沙箱工具(没准备好时工具调用会等它)。 */
    sandbox: SandboxPort? = null,
    /** 本次任务的工作区(对话 ID)。 */
    workspace: () -> String = { "default" },
    /** 沙箱开始跑一条命令或脚本时(任务卡片显示「沙箱:…」)。 */
    onSandboxCommand: (String) -> Unit = {},
    /** 沙箱里 `quro progress` 报的进度。 */
    onSandboxProgress: (String) -> Unit = {},
    /** 沙箱工具在等运行环境准备好(任务卡片显示「正在准备」)。 */
    onSandboxPreparing: () -> Unit = {},
    /** 开始跑技能脚本时;不给就按 [onSandboxCommand] 报。 */
    onSandboxScript: ((String) -> Unit)? = null,
    /** 发给主模型的图统一编码用;为空时原样发。 */
    imageOps: ImageOps? = vision?.ops,
    /** 开局约束允许的能力；旧调用方不传时仍得到今天的工具。 */
    runSpec: RunSpec = RunSpec(RunId("legacy"), "legacy", "legacy"),
    mcpServers: List<McpConnection> = emptyList(),
    /** 存下一个定时任务,并说明能不能准时(见 [Scheduled])。 */
    scheduleTrigger: ((TriggerDefinition) -> Scheduled)? = null,
    scheduleAdmin: ScheduleAdmin? = null,
    childHost: ChildRunHost? = null,
    /** 这个对话里用户发来的屏幕;给了而这次的话或之前几轮里带着屏幕时提供 view_user_screen。 */
    userScreens: UserScreenSource? = null,
    /** 用户的个人技能;和 [skills]、[utterances] 都给了才提供 skill_save(用户要求时写下或改一份个人技能)。 */
    personalSkills: PersonalSkills? = null,
    /** 改 ZorvAI 自己设置的白名单入口;为空时不提供 settings_list、settings_set(见 [SelfConfig])。 */
    selfConfig: com.ai.assistance.quro.core.mika.settings.SelfConfig? = null,
    /** 存下的运行记录;给了、而这个对话之前有记过运行的轮次时提供 history_read(查之前几轮读到的东西)。 */
    runRecords: RunRecords? = null,
): ToolDispatcher {
    val config = ToolConfig(visionEnabled, skills, recordFrames, vision, inlineScreen, adaptiveVision, memory, profile, utterances, conversationId,
        imageOps, scheduleTrigger, scheduleAdmin, runSpec, childHost, userScreens, personalSkills, selfConfig, runRecords)
    val providers: List<CapabilityProvider> = listOf(
        object : CapabilityProvider {
            override val id = PHONE
            override fun open(spec: RunSpec) = PhoneCapability(screen, deepLinks, appPacing(), appPace, appIdleScroll())
        },
        object : CapabilityProvider {
            override val id = WEB
            override fun open(spec: RunSpec) = WebCapability(web, workspacePath, webPacing(), webPlacePace, webIdleScroll())
        },
        object : CapabilityProvider {
            override val id = SANDBOX
            override fun open(spec: RunSpec) = SandboxCapability(sandbox, workspace, visionEnabled,
                onSandboxCommand, onSandboxProgress, onSandboxPreparing, onSandboxScript)
        },
    ) + mcpServers.map(::McpClientProvider)
    return RunAssembler(providers).assemble(runSpec, config, stableRefs, FORMER_PREFIX, RENAMED, RETIRED, MOVED)
}

/** 改名前各能力工具名的前缀:手机的没有前缀,网页的是 browser_。 */
private val FORMER_PREFIX = mapOf(PHONE to "", WEB to "browser_")
/** 屏幕工具第二次改名时动词部分的变化(以前 → 现在),手机、网页两套一样。 */
private val SCREEN_VERBS_MOVED = mapOf(
    "dump_elements" to "list_elements", "locate_on_screen" to "find_visually",
    "screen_description" to "describe", "check_screen" to "diagnose",
)

/** 除了前缀,其余部分也改过的(现在的名字 → 最早的名字;第二次改名的屏幕工具最早是几块屏共用的那个名字)。 */
private val RENAMED = mapOf("web_wait_for" to "browser_wait") +
    SCREEN_VERBS_MOVED.flatMap { (old, now) -> listOf(PHONE, WEB).map { "${it}_$now" to old } }

/**
 * 整个换了名字的(以前的名字 → 现在的名字)。一个拆成知识、技能两个的列两个:模型调旧名时按参数挑(见 [LegacyNames.replacements]),
 * 文字里的旧名两个都写上。
 */
private val MOVED: Map<String, List<String>> = mapOf(
    "find_skills" to listOf("knowledge_search", "skill_search"),
    "skill_view" to listOf("knowledge_read", "skill_read"),
    "run_script" to listOf("run_bundled_script"),
    "report_barrier" to listOf("handle_obstacle"),
    "report_failure" to listOf("end_not_done"),
    "request_input" to listOf("ask_user"),
    "user_takeover" to listOf("hand_to_user"),
    "recall_screenshot" to listOf("view_past_screens"),
) + SCREEN_VERBS_MOVED.flatMap { (old, now) -> listOf(PHONE, WEB).map { "${it}_$old" to listOf("${it}_$now") } }

/** 去掉了、没有替代工具的(以前的名字 → 现在怎么做):以前用工具交结果,现在不带调用的回复就是回答。 */
private val RETIRED = mapOf(
    "finish" to "reply to the user with your answer as text, without calling any tool",
    "phone_ask_app_ai" to "read the app's own screens yourself (asking an app's built-in AI assistant is no longer available)",
)

/** 改名前的工具名 → 现在的名字(读旧的路线、运行记录、个人技能时用)。 */
fun legacyNames(): LegacyNames =
    LegacyNames(knownTools(), FORMER_PREFIX, RENAMED, retired = RETIRED, moved = MOVED)

/** 读运行记录用的工具声明:改名前记下的旧名也认成现在的工具(动作、能力相同),旧的记录和新的评分一样。 */
fun recordedTools(): ToolIndex = legacyNames().let { legacy -> ToolIndex(allTools()) { legacy.resolve(it) } }

/** ZorvAI 所有可能提供的工具,按发给模型的顺序。 */
internal fun allTools(): List<AgentTool> =
    GeneralGroup.tools + PhoneCapability.tools() + WebGroup.tools + SandboxGroup.tools

/** ZorvAI 所有可能提供的工具的声明。 */
fun knownTools(): ToolIndex = ToolIndex(allTools())

/** ZorvAI 所有可能提供的工具名(技能文档里提到的工具名据此校验)。 */
fun knownToolNames(): Set<String> = knownTools().names + DO_STEPS_TOOL
