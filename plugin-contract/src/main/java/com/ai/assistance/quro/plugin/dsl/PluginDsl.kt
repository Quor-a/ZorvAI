package com.ai.assistance.quro.plugin.dsl

import com.ai.assistance.quro.plugin.contract.*
import com.ai.assistance.quro.plugin.extension.*

/**
 * 插件声明 DSL。AI 生成插件时只需写这一段，其余由模板补全。
 *
 * 用法（插件 Entry.onCreate 里）：
 * <pre>
 * override fun onCreate(ctx: PluginContext) {
 *     plugin(ctx) {
 *         aiTool("express_query", "查询快递物流") {
 *             param("no", ParamType.STRING, "快递单号")
 *             execute { args -> ToolResult.text(ExpressApi.query(args.string("no"))) }
 *         }
 *         aciCapability("query_express", "查询快递") {
 *             param("no", ParamType.STRING, "快递单号")
 *             execute { args -> ToolResult.text(...) }
 *         }
 *     }
 * }
 * </pre>
 */
fun plugin(ctx: PluginContext, block: PluginBuilder.() -> Unit) {
    PluginBuilder(ctx).apply(block).commit()
}

class PluginBuilder(private val ctx: PluginContext) {

    private val extensions = mutableListOf<PluginExtension>()

    // ---------- AI 工具（会被 LLM 自动发现调用） ----------
    fun aiTool(name: String, description: String, block: ToolBuilder.() -> Unit) {
        val b = ToolBuilder(name, description).apply(block)
        extensions += AiToolExtension(name, description, b.build(), b.executor
            ?: error("aiTool [$name] 缺少 execute {}"))
    }

    // ---------- ACI 能力（暴露给其他 App / Agent） ----------
    fun aciCapability(id: String, description: String, block: ToolBuilder.() -> Unit) {
        val b = ToolBuilder(id, description).apply(block)
        // ★ ToolExecutor 是「含 suspend execute 的 fun interface」，不是 (ToolArgs)->ToolResult 函数类型，
        //   必须显式 execute(a)；写成 it(a) 会 Unresolved reference（参考实现此处的原始 bug）。
        val exec = b.executor ?: error("aciCapability [$id] 缺少 execute {}")
        extensions += AciCapabilityExtension(
            id = id, label = id, description = description,
            parameters = b.params, handler = { a -> exec.execute(a) }
        )
    }

    // ---------- 斜杠指令 ----------
    fun command(name: String, usage: String, handler: (String) -> Boolean) {
        extensions += CommandExtension(name, name, usage, handler)
    }

    // ---------- 对话卡片 ----------
    fun chatCard(id: String, label: String, render: (String, RenderContext) -> RenderedCard) {
        extensions += ChatCardExtension(id, label, render)
    }

    // ---------- 界面表面（插件自带的完整界面） ----------
    /**
     * 注册一个可在宿主里打开的界面。用户从「插件管理」点开，或由 AI 通过
     * plugin_surface_open 工具打开；宿主用通用承载 Activity 显示插件返回的 View。
     */
    fun uiSurface(
        id: String,
        label: String,
        title: String = label,
        build: (android.content.Context, SurfaceHost) -> android.view.View?,
        onRelease: (() -> Unit)? = null,
    ) {
        extensions += UiSurfaceExtension(id, label, title, build, onRelease)
    }

    // ---------- 设置项 ----------
    fun setting(id: String, label: String, kind: SettingKind,
                default: String = "", options: List<String> = emptyList()) {
        extensions += SettingExtension(id, label, kind, default, options)
    }

    // ---------- 模型 Provider ----------
    fun modelProvider(id: String, label: String, models: List<String>,
                      chat: suspend (String, String) -> String) {
        extensions += ModelProviderExtension(id, label, models, chat)
    }

    // ---------- RAG 数据源 ----------
    fun ragSource(id: String, label: String, retrieve: suspend (String, Int) -> List<String>) {
        extensions += RagSourceExtension(id, label, retrieve)
    }

    // ---------- 定时任务 ----------
    fun scheduleTask(id: String, label: String, run: suspend (String) -> String) {
        extensions += ScheduleTaskExtension(id, label, run)
    }

    // ---------- 文件处理器 ----------
    fun fileHandler(id: String, label: String, vararg ext: String,
                    open: suspend (String) -> ToolResult) {
        extensions += FileHandlerExtension(id, label, ext.toSet(), open)
    }

    // ---------- 代码运行时 ----------
    fun codeRuntime(id: String, label: String, language: String, eval: suspend (String) -> String) {
        extensions += CodeRuntimeExtension(id, label, language, eval)
    }

    internal fun commit() {
        extensions.forEach { ctx.register(it) }
    }
}

class ToolBuilder(private val name: String, private val description: String) {

    internal val params = mutableListOf<ToolParamSpec>()
    internal var executor: ToolExecutor? = null
    internal var requiresConfirm = false
    internal val capabilities = mutableSetOf<String>()

    fun param(pname: String, type: ParamType, desc: String,
              required: Boolean = true, enum: List<String> = emptyList(),
              default: String? = null) {
        params += ToolParamSpec(pname, type, desc, required, enum, default)
    }

    fun requireConfirm(caps: Set<String> = emptySet()) {
        requiresConfirm = true
        capabilities += caps
    }

    fun execute(block: suspend (ToolArgs) -> ToolResult) {
        executor = ToolExecutor { block(it) }
    }

    internal fun build() = ToolSpec(
        name = name, description = description, parameters = params,
        requiresConfirm = requiresConfirm, capabilities = capabilities
    )
}
