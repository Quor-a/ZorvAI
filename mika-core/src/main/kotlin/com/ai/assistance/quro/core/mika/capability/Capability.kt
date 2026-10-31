package com.ai.assistance.quro.core.mika.capability

import com.ai.assistance.quro.core.mika.uistate.ScreenFamily
import com.ai.assistance.quro.core.mika.uistate.Surface
import com.ai.assistance.quro.core.mika.guard.OffScreenGuard
import com.ai.assistance.quro.core.mika.knowledge.BundledScripts
import com.ai.assistance.quro.core.mika.observation.Look
import com.ai.assistance.quro.core.mika.tool.AgentTool
import com.ai.assistance.quro.core.mika.tool.ToolContext
import com.ai.assistance.quro.core.mika.tool.ToolExecution
import com.ai.assistance.quro.core.mika.tool.Verb
import kotlinx.serialization.json.JsonObject

/** 能力此刻能不能用。 */
sealed interface CapabilityStatus {
    data object Available : CapabilityStatus
    /** 能用,但还缺点什么(运行环境在准备、一部分功能没有)。 */
    data class Degraded(val reason: String) : CapabilityStatus
    data class Unavailable(val reason: String) : CapabilityStatus
}

/**
 * 拼系统提示词时各组段落看得到的:本任务发给模型的工具名与开关;
 * [screens] 是本任务发给模型的工具里有几种屏幕(手机、网页……,收着的只给了入口也算)。
 */
class PromptEnv(
    val offered: Set<String>, val inlineScreen: Boolean, val screens: Int = 1,
    /** 展开了的能力(不收的也在内);null 是当全都展开了。收着的能力不给自己的段落。 */
    val open: Set<String>? = null,
    /** [screens] 里已展开的那几块屏。 */
    val openScreens: Int = screens,
) {
    /** 能力 [id] 已展开(或不收)。 */
    fun isOpen(id: String): Boolean = open == null || id in open

    /**
     * 段落里只在某些工具提供时才成立的一行,行首写 `<!-- needs: 工具名, … -->`:那些工具都在本任务的工具列表里才留下(去掉标记),
     * 否则整行不要。没有标记的行原样留着。
     */
    fun gate(text: String): String = text.lines().mapNotNull { line ->
        val m = NEEDS.find(line) ?: return@mapNotNull line
        val tools = m.groupValues[2].split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (tools.all { it in offered }) m.groupValues[1] + m.groupValues[3] else null
    }.joinToString("\n")

    private companion object {
        val NEEDS = Regex("^(\\s*)<!-- needs: ([a-z0-9_, ]+) -->\\s?(.*)$")
    }
}

/** 系统提示词里的一组段落;能力各给一组,核心自己也有。 */
internal fun interface PromptSections {
    fun sections(env: PromptEnv): List<String>
}

/**
 * 一种能力(手机、网页、沙箱……):自己的工具、系统提示词里的段落、屏幕与守卫。
 * 核心只经这个接口认识能力;一个任务一份实例,端口在构造时给。
 */
internal interface Capability : PromptSections {
    val id: String

    fun status(): CapabilityStatus

    /** 这一组工具用到才展开(见 [com.ai.assistance.quro.core.mika.tool.ToolGroups]):开局只有入口工具;不收的一直全在。 */
    val folds: Boolean get() = false

    /** 按这次的话([task])开局就展开这一组(一定用得上时);默认不展开。 */
    fun preload(task: String): Boolean = false

    /** 本能力可能提供的全部工具;本任务能不能用由各工具自己看上下文。 */
    fun tools(): List<AgentTool>

    /** 有屏幕的能力给出它的一套屏幕工具的样子(没接端口时也给)。 */
    val family: ScreenFamily? get() = null

    /** 接上了端口的屏幕;没有为 null。本能力那一套屏幕工具只作用在它上面。 */
    val surface: Surface? get() = null

    /** 能跑技能脚本时给出。 */
    val scripts: BundledScripts? get() = null

    /** 本任务开始:把工具要用的端口与状态放进工具上下文。 */
    fun onTaskStart(ctx: ToolContext) {}

    /** 任务里用户答过话(回答了追问、交回了接管)。 */
    fun onUserAnswered(ctx: ToolContext) {}

    /**
     * 在本能力的屏上要做一次对外动作(发送、提交,见 [com.ai.assistance.quro.core.mika.action.OutwardPacing])了;[site] 是它所在的网站或 App。
     * 做完后一定接着 [onOutwardEnd]([done]:做成了)。
     */
    fun onOutwardStart(ctx: ToolContext, site: String) {}

    fun onOutwardEnd(ctx: ToolContext, site: String, done: Boolean) {}

    /**
     * 本能力的屏上要做一个改动屏幕的动作([verb])了:按本能力的节奏先停一会儿(见 [com.ai.assistance.quro.core.mika.action.ActionExecutor],
     * 单步、一批里的每一步都经这里)。[outward]:这一步是对外动作(发送、提交)。默认不停。
     */
    suspend fun beforeWrite(ctx: ToolContext, verb: Verb?, outward: Boolean): Paced = Paced.NONE

    /** 那个动作做完了([result] 是它的结果);[beforeWrite] 停下没做的不调。 */
    fun afterWrite(ctx: ToolContext, verb: Verb?, result: JsonObject) {}

    /** 不动屏幕的工具的守卫,每个任务一份;没有为 null。 */
    fun guard(): OffScreenGuard? = null

    /**
     * 核查前再看一眼这个能力此刻的样子(不改模型手里的任何东西);[withImage] 时连同一帧画面。没有可看的为 null。
     */
    suspend fun freshLook(ctx: ToolContext, withImage: Boolean): Look? = null
}

/**
 * 改动屏幕的动作之前按能力的节奏停的结果:停了 [waitMs](算进这次调用的等待),停留里轻轻滚了 [idleScrolls] 下(只进运行记录);
 * [stop] 不为 null 时这个动作不做,它就是这次调用的结果(到了屏数上限、停留中用户插了话)。
 */
internal class Paced(val waitMs: Long = 0, val idleScrolls: Int = 0, val stop: ToolExecution? = null) {
    companion object {
        val NONE = Paced()
    }
}

/** 能力放进工具上下文的东西的键;核心不认识里面是什么。 */
class CapabilityKey<T : Any>(val name: String)
