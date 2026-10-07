package com.codecanvas.core

import android.content.Context
import com.codecanvas.core.export.Exporter
import com.codecanvas.core.export.ExportFormat
import com.codecanvas.core.export.MediaStoreExporter
import com.codecanvas.core.highlight.Highlighter
import com.codecanvas.core.highlight.PlainHighlighter
import com.codecanvas.core.model.CanvasSpec
import com.codecanvas.core.render.RenderKind
import com.codecanvas.core.render.RenderOutput
import com.codecanvas.core.render.RenderSource
import com.codecanvas.core.render.Renderer
import com.codecanvas.core.script.DrawList
import com.codecanvas.core.script.ScriptEngine
import com.codecanvas.core.script.ScriptLanguage
import com.codecanvas.core.script.ScriptResult
import com.codecanvas.core.script.ScriptSandbox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * SDK 门面 + 组件注册表。
 *
 * 设计要点：core 不依赖任何具体引擎/渲染器实现，全部通过注册注入。
 * 宿主只引入需要的 module（例如只要 JS + Canvas），未引入的模块不会被打进 APK。
 */
object CodeCanvas {

    private val engines = mutableMapOf<ScriptLanguage, () -> ScriptEngine>()
    private val renderers = mutableMapOf<RenderKind, (Context) -> Renderer>()
    private var highlighter: Highlighter = PlainHighlighter()
    private var initialized = false

    fun registerEngine(language: ScriptLanguage, factory: () -> ScriptEngine) {
        engines[language] = factory
    }

    fun registerRenderer(kind: RenderKind, factory: (Context) -> Renderer) {
        renderers[kind] = factory
    }

    fun registerHighlighter(h: Highlighter) { highlighter = h }

    /** 各 module 在自己的 init 块里调用，也可由宿主手动注册 */
    fun installDefaults(context: Context) {
        if (initialized) return
        // 反射探测已引入的 module，避免 core 反向依赖实现模块
        runCatching {
            val c = Class.forName("com.codecanvas.engine.quickjs.QuickJsEngine")
            registerEngine(ScriptLanguage.JAVASCRIPT) { c.newInstance() as ScriptEngine }
        }
        runCatching {
            val c = Class.forName("com.codecanvas.engine.lua.LuaEngine")
            registerEngine(ScriptLanguage.LUA) { c.newInstance() as ScriptEngine }
        }
        runCatching {
            val c = Class.forName("com.codecanvas.engine.kotlindsl.DslScriptEngine")
            registerEngine(ScriptLanguage.KOTLIN_DSL) { c.newInstance() as ScriptEngine }
        }
        runCatching {
            val c = Class.forName("com.codecanvas.engine.python.PythonEngine")
            c.getDeclaredConstructor(Context::class.java).newInstance(context.applicationContext)
                .let { e -> registerEngine(ScriptLanguage.PYTHON) { e as ScriptEngine } }
        }
        runCatching {
            val c = Class.forName("com.codecanvas.renderer.canvas.CanvasRenderer")
            registerRenderer(RenderKind.CANVAS) { c.newInstance() as Renderer }
        }
        runCatching {
            val c = Class.forName("com.codecanvas.renderer.webview.WebViewRenderer")
            registerRenderer(RenderKind.WEBVIEW) { ctx ->
                c.getDeclaredConstructor(Context::class.java).newInstance(ctx.applicationContext) as Renderer
            }
        }
        runCatching {
            val c = Class.forName("com.codecanvas.renderer.svg.SvgRenderer")
            registerRenderer(RenderKind.SVG) { c.newInstance() as Renderer }
        }
        runCatching {
            val c = Class.forName("com.codecanvas.renderer.compose.ComposeRenderer")
            registerRenderer(RenderKind.COMPOSE) { c.newInstance() as Renderer }
        }
        runCatching {
            val c = Class.forName("com.codecanvas.highlight.WebViewHighlighter")
            c.getDeclaredConstructor(Context::class.java).newInstance(context.applicationContext)
                .let { h -> registerHighlighter(h as Highlighter) }
        }
        initialized = true
    }

    fun availableEngines(): Set<ScriptLanguage> = engines.keys.toSet()
    fun availableRenderers(): Set<RenderKind> = renderers.keys.toSet()
    fun highlighter(): Highlighter = highlighter

    fun session(context: Context): Session = Session(context.applicationContext)

    class Session internal constructor(private val appContext: Context) {

        private var language: ScriptLanguage = ScriptLanguage.JAVASCRIPT
        private var kind: RenderKind = RenderKind.CANVAS
        private var spec: CanvasSpec = CanvasSpec()
        private var sandbox: ScriptSandbox = ScriptSandbox()
        private var exporter: Exporter = MediaStoreExporter(appContext)
        private var bindings: Map<String, Any?> = emptyMap()

        private var engine: ScriptEngine? = null
        private var renderer: Renderer? = null

        fun engine(language: ScriptLanguage) = apply { this.language = language }
        /** 按文件扩展名自动选引擎：run("a.py") 自动走 Python */
        fun engineByExtension(ext: String) = apply {
            language = ScriptLanguage.fromExtension(ext) ?: language
        }

        fun renderer(kind: RenderKind) = apply { this.kind = kind }
        fun canvas(block: CanvasSpec.() -> CanvasSpec) = apply { spec = spec.block() }
        fun canvas(spec: CanvasSpec) = apply { this.spec = spec }
        fun sandbox(block: ScriptSandbox.() -> ScriptSandbox) = apply { sandbox = sandbox.block() }
        fun sandbox(s: ScriptSandbox) = apply { sandbox = s }
        fun args(vararg pairs: Pair<String, Any?>) = apply { bindings = mapOf(*pairs) }
        fun exporter(e: Exporter) = apply { exporter = e }

        /** 执行脚本 —— 只产指令，不渲染；可继续改参数后复用 */
        suspend fun run(script: String): RunOutcome = withContext(Dispatchers.Default) {
            val eng = (engine ?: newEngine().also { engine = it })
            val out = DrawList(sandbox.maxCommands)
            val result = withTimeoutOrNull(sandbox.timeoutMs) {
                eng.execute(script, out, sandbox, bindings)
            } ?: run {
                eng.interrupt()
                ScriptResult.Failure.Timeout(sandbox.timeoutMs)
            }
            RunOutcome(result, out, this@Session)
        }

        suspend fun render(source: RenderSource): RenderOutput = withContext(Dispatchers.Default) {
            val r = renderer ?: newRenderer().also { renderer = it }
            require(r.supports(source)) { "${r.kind} 后端不支持该输入源: ${source::class.simpleName}" }
            r.render(spec, source)
        }

        suspend fun renderCommands(list: DrawList): RenderOutput = render(RenderSource.Commands(list))

        /** 一步到位：脚本 → 图片 */
        suspend fun runAndRender(script: String): RenderOutput =
            renderCommands(run(script).requireCommands())

        suspend fun export(output: RenderOutput, format: ExportFormat, name: String? = null) =
            exporter.export(output, format, name ?: defaultName(format))

        private fun defaultName(f: ExportFormat) = "codecanvas_${System.currentTimeMillis()}.${f.ext}"

        private fun newEngine(): ScriptEngine =
            engines[language]?.invoke() ?: error(
                "未注册引擎: $language。请引入对应 module（engine-quickjs / engine-lua / engine-python / engine-kotlindsl）"
            )

        private fun newRenderer(): Renderer =
            renderers[kind]?.invoke(appContext) ?: error(
                "未注册渲染后端: $kind。请引入对应 module（renderer-canvas / renderer-webview / renderer-compose / renderer-svg）"
            )

        fun release() {
            engine?.release(); engine = null
            renderer?.release(); renderer = null
        }
    }

    class RunOutcome internal constructor(
        private val result: ScriptResult,
        private val list: DrawList,
        private val session: Session,
    ) {
        val scriptResult: ScriptResult get() = result
        fun commands(): DrawList = list

        /** 成功返回指令列表，失败抛出带堆栈的异常 */
        fun requireCommands(): DrawList = when (result) {
            is ScriptResult.Success -> list
            is ScriptResult.Failure -> error("脚本执行失败: ${result.message}\n${result.stack ?: ""}")
        }

        suspend fun render(): RenderOutput = session.renderCommands(requireCommands())
    }
}
