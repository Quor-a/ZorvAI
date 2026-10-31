package com.ai.assistance.quro.core.mika.tool

import com.ai.assistance.quro.core.mika.model.ToolSpec
import com.ai.assistance.quro.core.mika.prompt.Prompts

/**
 * 本任务的工具:[all] 是各能力与核心可能提供的全部工具(按发给模型的顺序),本任务能调用的按配置筛过。
 * 每次调用按名字找到工具再执行,分发器不认识具体工具。[groupOf] 给出工具所属的组(见 [ToolGroups];一直都在为 null)。
 */
internal class ToolRegistry(private val ctx: ToolContext, all: List<AgentTool>, private val groupOf: (AgentTool) -> String? = { null }) {
    private val declared: Map<String, AgentTool> = all.associateBy { it.name }
    private val tools: Map<String, AgentTool> = all.filter { it.available(ctx) }.associateBy { it.name }

    init {
        check(declared.size == all.size) { "duplicate tool names" }
    }

    operator fun get(name: String): AgentTool? = tools[name]

    /** 按名字查工具的声明(风险、判定目标……),本任务没提供的也查得到;不认识的为 null。 */
    fun declared(name: String): AgentTool? = declared[name]

    /**
     * 第一次取目录时冻结本 Run 的工具定义(全文、入口的短说明、所属的组);后续端口状态变化只能影响执行,不能改提示词前缀。
     * 入口的短说明在 prompts/entries/<工具名>.md,参数和全文一样。
     */
    private val frozen: List<ToolGroups.Slot> by lazy {
        tools.values.filter { it.offered(ctx) }.map { t ->
            val d = t.def(ctx)
            ToolGroups.Slot(d.spec(), t.entry?.let { ToolDef(d.name, Prompts.entry(d.name), d.props, d.required).spec() }, groupOf(t))
        }
    }

    /** 本任务提供的全部工具(每组都展开、全文)。 */
    fun definitions(): List<ToolSpec> = frozen.map { it.full }

    /** 此刻发给模型的工具:只展开 [expanded] 里的组(见 [ToolGroups.layout])。 */
    fun layered(expanded: Set<String>, extra: Map<String, List<ToolSpec>>): List<ToolSpec> = ToolGroups.layout(frozen, expanded, extra)

    /** 组 [group] 里有发给模型的入口工具。 */
    fun hasEntry(group: String): Boolean = frozen.any { it.group == group && it.entry != null }

    /** 组 [group] 里发给模型的工具名(按登记的顺序)。 */
    fun members(group: String): List<String> = frozen.filter { it.group == group }.map { it.full.name }

    /** 能放进 do_steps 的工具名(本任务没提供的也列),按登记的顺序。 */
    fun batchable(): List<String> = declared.values.filter { it.batchable }.map { it.name }

    /** 「未知工具」的说明里列出的名字:随时可用的那些(不动屏幕的一组看运行环境就绪,不列)。 */
    fun listedNames(): List<String> = tools.values.filter { !it.offScreen }.map { it.name }
}
