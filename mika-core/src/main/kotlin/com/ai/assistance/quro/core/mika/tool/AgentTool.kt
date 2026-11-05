package com.ai.assistance.quro.core.mika.tool

import com.ai.assistance.quro.core.mika.observation.Observation
import com.ai.assistance.quro.core.mika.risk.GateTarget
import com.ai.assistance.quro.core.mika.action.Risk as ApprovalRisk
import kotlinx.serialization.json.JsonObject

/**
 * 工具自己声明会对外部世界做什么。
 * Read:只读(读屏、读网页、查记忆、报失败一类),只读调用可以直接做;
 * Write:改变屏幕、页面或沙箱,只能经执行入口;
 * External:除了改变界面,还可能按目标元素发出、付款、提交(不可逆判定要看它的目标)。
 */
enum class Risk { Read, Write, External }

/** 入口工具:[Opens] 调用后展开它那一组;[Alone] 单用就够(列出 App、读一个网址的正文),调用不展开。 */
enum class Entry { Opens, Alone }

/** 能不能和别的调用同时做;默认一次一个。 */
enum class Concurrency { Serial, ParallelSafe }

/**
 * 工具做的是什么动作。按动作做判断的地方(守卫、裁剪、路线、批量、模型分工……)都看它,不看工具名;
 * 屏幕上的动作与读屏在几种界面上各有一个同动词的工具。
 */
enum class Verb {
    Observe, ListElements, Query, Collect, PhoneRun, PhoneFind, PhoneExtract, PhoneDialog, PhoneEvent, PhoneGesture, PhoneCommand, Screenshot, ViewPastScreens, ViewUserScreen, Describe, FindVisually, Diagnose,
    Tap, TypeText, ClearText, Scroll, Swipe, Back, PressKey,
    ListApps, LaunchApp, OpenLink, ShareText, AppAfterTask,
    SetAlarm, SetTimer, ShowAlarms, AddCalendarEvent, SetWheel,
    Navigate, ReadPage, EvaluateJs, SiteCommand, Tabs, Downloads, Upload, SiteMode, Script, Interact, Capture, WaitFor, KeepOpen,
    RunCommand, RunBundledScript, ReadFile, WriteFile, SearchFiles, ShareFile,
    Wait, AskUser, RequestConfirmation, HandToUser, HandleObstacle, EndNotDone,
    KnowledgeSearch, KnowledgeRead, SkillSearch, SkillRead, SkillSave, MemorySearch, MemorySave, ProfileEdit, HistoryRead,
    ScheduleTask,
    SpawnAgent, SettingsList, SettingsSet,
}

/** 按名字查得到的工具声明。 */
interface ToolInfo {
    val name: String
    val verb: Verb
    /** 所属能力的 id;核心自己的工具为 null。 */
    val capability: String?
    /** 能放进一次多步(do_steps)。 */
    val batchable: Boolean
    /** 经验路线记它、回放它。 */
    val routable: Boolean
    /** 界面上的说法;没有时显示工具名。 */
    val label: String?
    /** 沙箱里的 `quro phone <操作>` 能调它(手机能力、结果是文字);其余一律拒绝。 */
    val sandboxCallable: Boolean get() = false
}

/**
 * 按名字查工具声明;不认识的名字为 null。[former] 把改名前的名字认成现在的(读旧的运行记录时用,见 LegacyNames);
 * [names] 只有现在登记的名字。
 */
class ToolIndex(tools: Collection<ToolInfo>, private val former: (String) -> String? = { null }) {
    private val byName: Map<String, ToolInfo> = tools.associateBy { it.name }

    val names: Set<String> get() = byName.keys

    operator fun get(name: String?): ToolInfo? = name?.let { byName[it] ?: former(it)?.let(byName::get) }

    fun verb(name: String?): Verb? = get(name)?.verb

    /** 做这个动作的工具名:[capability] 给了时只找那个能力的(核心的工具为 null 的那组),没给时取先登记的。 */
    fun nameOf(verb: Verb, capability: String? = null): String? =
        byName.values.firstOrNull { it.verb == verb && (capability == null || it.capability == capability) }?.name

    /** 屏幕能力 [from] 那一套里做同一动作的工具在 [to] 那一套里叫什么;没有为 null。 */
    fun counterpart(name: String, to: String): String? = get(name)?.let { nameOf(it.verb, to) }
}

/**
 * 一个工具:定义(名字、说明、参数)+ 声明的风险 + 执行。新工具 = 新增一个实现并在所属能力里登记,分发器不认识具体工具。
 * 状态都在 [ToolContext] 里,工具本身无状态。
 */
internal interface AgentTool : ToolInfo {
    val def: ToolDef
    override val capability: String? get() = null
    override val batchable: Boolean get() = false
    override val routable: Boolean get() = false
    override val label: String? get() = null
    /** 不动屏幕(沙箱一类):不过锁屏关卡、不进屏幕守卫,结果里的图是文件不是屏幕画面。 */
    val offScreen: Boolean get() = false
    val risk: Risk
    /** 非界面能力可直接声明审批风险；界面工具仍按目标和当前观察判。 */
    val approvalRisk: ApprovalRisk? get() = null
    val concurrency: Concurrency get() = Concurrency.Serial
    /** 不可逆判定按什么看这次调用的目标;External 的工具必须给,其余为 null(不判)。 */
    val gateTarget: GateTarget? get() = null
    /** 自己不动界面,里面的动作一个个经执行入口回来(页面脚本):不过锁屏关卡,也不能作为只读调用。 */
    val composite: Boolean get() = false
    /**
     * 能力那一组收着时也提供的入口(见 [ToolGroups]):收着时用短说明(prompts/entries/<工具名>.md),展开后换回全文。
     * 不是入口为 null。
     */
    val entry: Entry? get() = null
    /** 不属于哪个能力、只在有屏幕的那几组展开时才提供的通用工具(接管、报障碍、回看画面)。 */
    val withScreens: Boolean get() = false
    /**
     * 撤掉也什么都不丢的调用(只是等待,或只问看图模型、不动界面):用户插话一到就撤掉,
     * 结果报 [com.ai.assistance.quro.core.mika.run.SKIPPED_FOR_NEW_MESSAGE],模型先读这句话,还要的话再调一次。
     */
    val yieldsToSteer: Boolean get() = false

    override val name: String get() = def.name
    /** 发给模型的定义;按本任务有几块屏另加参数的工具(surface)覆盖它。 */
    fun def(ctx: ToolContext): ToolDef = def
    /** 说明出自哪个文件(prompts/tools/<file>.md);默认同名,几个工具共用一份模板时另写。 */
    val promptFile: String get() = name
    val description: String get() = def.description
    /** 参数的 JSON schema(发给模型的那份)。 */
    val params: String get() = def.spec().parametersJsonSchema

    /**
     * 这次调用作用在哪块屏上(屏幕能力的 id):屏幕工具是自己那块;说的是哪块屏由参数定的通用工具看参数;其余为 null(不变)。
     * 分发器据此在调用前记下在操作哪块屏。
     */
    fun aimAt(o: JsonObject): String? = capability

    /** 本任务能不能调用(看配置:有没有看图模型、技能目录、网页端口……)。 */
    fun available(ctx: ToolContext): Boolean = true

    /** 此刻放不放进发给模型的工具列表;默认同 [available]。 */
    fun offered(ctx: ToolContext): Boolean = available(ctx)

    suspend fun execute(a: Args, ctx: ToolContext): ToolExecution

    /** 成功后结果里带动作之后的屏幕(动作自带屏幕开着时),能看图的主模型另附一帧画面。 */
    val showsScreenAfter: Boolean get() = false

    /**
     * 改变界面的动作(点、输入、清空、按键、返回、滑动……):分发器在动作前后各看一眼屏幕(元素表 + 画面),
     * 把实际效果写进结果的 effect 字段(见界面状态一侧的 ActionEffect)。只读屏,不另做动作。
     */
    val reportsEffect: Boolean get() = false

    /** 进动作清单(手机、网页、沙箱里会改变什么或去到哪里的动作);[wideTarget] 时对象留长一点。 */
    val logged: Boolean get() = false
    val wideTarget: Boolean get() = false
    /** 动作清单里写的对象;要按编号写出元素的名字时从 [ctx] 取这块屏最近一次读屏。 */
    fun logTarget(o: JsonObject, r: ToolExecution, ctx: ToolContext): String? = null
    /** 动作清单里写出的输入文字(只有确定不是密码框时)。 */
    fun logTyped(o: JsonObject, r: ToolExecution): String? = null

    /**
     * 结果里模型可能在最终回答里引用、执行时没有另给的观察(打开了哪个网页、命令的输出……);分发器把它们和结果里的
     * [ToolExecution.observations] 一起记进核查材料。[log] 里是跨工具的少量状态(列出过的 App 名字、写过的文件)。
     */
    fun observations(o: JsonObject, r: ToolExecution, log: EvidenceLog): List<Observation> = emptyList()
}

/** 会改变屏幕、页面状态的工具:要过锁屏/暂停关卡。由声明的风险推出,不动屏幕的与页面脚本不在其中。 */
internal val AgentTool.writesScreen: Boolean get() = risk != Risk.Read && !offScreen && !composite

/** 只读调用能不能做:只读、动屏幕(不动屏幕的另有守卫)、不是页面脚本。 */
internal val AgentTool.readOnly: Boolean get() = risk == Risk.Read && !offScreen && !composite
