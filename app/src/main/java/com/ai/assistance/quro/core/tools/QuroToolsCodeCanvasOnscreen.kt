package com.ai.assistance.quro.core.tools

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.compose.ui.graphics.asAndroidBitmap
import com.ai.assistance.quro.core.QuroAttachmentKit
import com.codecanvas.core.CodeCanvas
import com.codecanvas.core.export.ExportFormat
import com.codecanvas.core.model.CanvasSpec
import com.codecanvas.core.render.RenderKind
import com.codecanvas.core.render.RenderOutput
import com.codecanvas.core.render.RenderSource
import com.codecanvas.core.script.ScriptLanguage
import com.codecanvas.core.script.ScriptResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * CodeCanvas **端侧**出图工具（纯设备内渲染，不连任何服务器）。
 *
 * ## 与 [CodeCanvasToolBase] 服务端族的关系：并存，不是替代
 *
 * | | 服务端族（`codecanvas_script` / `markup` / `code_card` / `llm_code`） | 端侧族（本文件） |
 * |---|---|---|
 * | 依赖 | 需另跑 `CodeCanvasServer`（FastAPI + Python 依赖） | 无，SDK 直接跑在 App 里 |
 * | 网络 | 必须可达 | 完全离线 |
 * | 体积 | 无 | QuickJS ~900KB（kotlin-dsl 引擎 0 成本） |
 * | 适用 | chromium 后端的精细 HTML 排版 | 绝大多数场景，开箱即用 |
 *
 * 🔴 保留两套的理由：服务端 chromium 后端排版质量确实更高，但要求用户另装 Python 环境 + 起服务，
 * 对随手画一张图太重。端侧 SDK 覆盖绝大多数需求且开箱即用，服务端作为「要精细排版时的升级选项」留在原地。
 *
 * ## 为什么工具名带 `onscreen`
 * 避免与既有 `codecanvas_script` / `codecanvas_markup` 撞名。
 * 两个同名工具会让模型随机挑一个、行为不可预测——那比少一个工具更糟。
 *
 * ## 为什么参数用 [CodeCanvasArgs] 而不是直接传 `JSONObject`
 * 本工具族有几处**在 suspend 之外**读取参数（构造 [CanvasSpec] 时），而 `JSONObject`
 * 在 QuroToolEngine 的并发调用下不是线程安全的。一次解析成不可变 map，之后全程只读。
 */
private const val TAG = "QuroCodeCanvasOnscreen"

/**
 * 工具参数的只读快照。
 *
 * 🔴 `num()` 必须容忍模型把数字写成字符串：`JSONObject.optDouble` 只在值确实是数字时可靠，
 * 而 LLM 把 `1080` 写成 `"1080"` 是高频现象。静默取默认值会让模型以为设了 1080、实际出的却是 1440
 * —— 一张尺寸完全不对的图，比直接报错更难排查。
 */
class CodeCanvasArgs private constructor(private val m: Map<String, Any?>) {

    val pairs: List<Pair<String, Any?>> get() = m.entries.map { it.key to it.value }

    fun optString(key: String): String = when (val v = m[key]) {
        null -> ""
        is String -> v
        else -> v.toString()
    }

    fun num(key: String, default: Float): Float = when (val v = m[key]) {
        is Number -> v.toFloat()
        is String -> v.trim().toFloatOrNull() ?: default
        else -> default
    }

    fun flag(key: String, default: Boolean): Boolean = when (val v = m[key]) {
        is Boolean -> v
        is String -> when (v.trim().lowercase()) {
            "false", "0", "no" -> false
            "true", "1", "yes" -> true
            else -> default
        }
        else -> default
    }

    companion object {
        fun parse(json: String): CodeCanvasArgs {
            val out = LinkedHashMap<String, Any?>()
            runCatching {
                val o = org.json.JSONObject(json)
                for (k in o.keys()) {
                    val v = o.opt(k)
                    out[k] = if (v == org.json.JSONObject.NULL) null else v
                }
            }
            return CodeCanvasArgs(out)
        }
    }
}

/** 端侧出图工具公共骨架：SDK 初始化 / 参数解析 / 落盘 / 错误翻译。 */
abstract class CodeCanvasOnscreenTool : QuroTool {

    override val readOnly: Boolean get() = false

    /** 确保 SDK 已注册引擎与渲染器。`installDefaults()` 自带幂等守卫，可重复调。 */
    protected fun ensureInstalled(context: Context) {
        runCatching { CodeCanvas.installDefaults(context) }
            .onFailure { Log.w(TAG, "installDefaults failed", it) }
    }

    /** 构造 [CanvasSpec]。SDK 里它是 data class + 不可变字段，只能整体构造。 */
    protected fun canvasOf(args: CodeCanvasArgs): CanvasSpec {
        val bg = args.optString("background").trim()
        return CanvasSpec(
            width = args.num("width", 1080f),
            height = args.num("height", 1440f),
            scale = args.num("scale", 1f),
            background = parseBackground(bg),
            padding = CanvasSpec.Padding.all(args.num("padding", 48f)),
        )
    }

    /** 背景色：`transparent` → 透明，`#RRGGBB` → 实色，其余按不透明白。 */
    private fun parseBackground(raw: String): CanvasSpec.Background = when {
        raw.isEmpty() -> CanvasSpec.Background.Solid(0xFFFFFFFF.toInt())
        raw.equals("transparent", ignoreCase = true) -> CanvasSpec.Background.Transparent
        raw.startsWith("#") -> runCatching { CanvasSpec.Background.Solid(raw.toColorInt()) }
            .getOrElse { CanvasSpec.Background.Solid(0xFFFFFFFF.toInt()) }
        else -> CanvasSpec.Background.Solid(0xFFFFFFFF.toInt())
    }

    private fun String.toColorInt(): Int {
        val hex = removePrefix("#")
        return when (hex.length) {
            6 -> (0xFF000000.toInt() or hex.toLong(16).toInt())
            8 -> hex.toLong(16).toInt()
            else -> 0xFFFFFFFF.toInt()
        }
    }

    protected fun formatOf(args: CodeCanvasArgs): ExportFormat = when (args.optString("format").lowercase()) {
        "jpeg", "jpg" -> ExportFormat.JPEG
        "webp" -> ExportFormat.WEBP
        "svg" -> ExportFormat.SVG
        else -> ExportFormat.PNG
    }

    /**
     * 引擎名 → 枚举。
     *
     * 🔴 只认**本APK 实际注册了**的引擎：请求一个没打进包的引擎（lua/python）时
     * 静默回落 JavaScript，模型会以为跑了 lua 实则跑的是 JS——错误被掩盖比报错难查。
     * 故这里只接受 javascript / kotlin-dsl（这两个模块已在宿主依赖里）。
     */
    protected fun engineOf(args: CodeCanvasArgs): ScriptLanguage =
        when (args.optString("engine").trim().lowercase()) {
            "kotlin-dsl", "kts", "dsl", "kotlin" -> ScriptLanguage.KOTLIN_DSL
            else -> ScriptLanguage.JAVASCRIPT
        }

    /** 后端名 → 枚举；不可用时回落到 Canvas（零依赖、最快）。 */
    protected fun rendererOf(args: CodeCanvasArgs, available: Set<RenderKind>): RenderKind =
        when (args.optString("renderer").trim().lowercase()) {
            "compose" -> if (RenderKind.COMPOSE in available) RenderKind.COMPOSE else RenderKind.CANVAS
            "svg" -> if (RenderKind.SVG in available) RenderKind.SVG else RenderKind.CANVAS
            "webview" -> if (RenderKind.WEBVIEW in available) RenderKind.WEBVIEW else RenderKind.CANVAS
            else -> RenderKind.CANVAS
        }

    /** 渲染结果 → uploadsDir 落盘 → 返回给模型的文本（路径 + attach_file 样例）。 */
    protected fun persist(context: Context, args: CodeCanvasArgs, out: RenderOutput): String = when (out) {
        is RenderOutput.Bitmap -> saveBitmap(context, out.bitmap, formatOf(args))
        is RenderOutput.Vector -> saveVector(context, out.svg)
        is RenderOutput.ComposeBitmap -> saveBitmap(context, out.imageBitmap.toBitmap(), formatOf(args))
    }

    private fun saveBitmap(context: Context, bitmap: Bitmap, fmt: ExportFormat): String {
        val ext = if (fmt == ExportFormat.JPEG) "jpg" else fmt.ext
        val file = newFile(context, ext)
        FileOutputStream(file).use { bitmap.compress(compressFormat(fmt), 95, it) }
        bitmap.recycle()
        return "✅ 端侧出图成功（$ext，${file.length() / 1024} KB）\n" +
            "本地路径：${file.absolutePath}\n" +
            "如需在对话框显示，调用：attach_file {\"path\":\"${file.absolutePath}\",\"caption\":\"CodeCanvas 出图\"}"
    }

    private fun saveVector(context: Context, svg: String): String {
        val file = newFile(context, "svg")
        file.writeText(svg, Charsets.UTF_8)
        return "✅ 端侧出图成功（svg，${file.length() / 1024} KB）\n" +
            "本地路径：${file.absolutePath}\n" +
            "⚠️ SVG 是矢量文本，对话框气泡用位图解码器渲染，不会显示；要在气泡里看到请改用 format=png。\n" +
            "如需在对话框显示，调用：attach_file {\"path\":\"${file.absolutePath}\",\"caption\":\"CodeCanvas 出图\"}"
    }

    private fun newFile(context: Context, ext: String): File {
        val dir = QuroAttachmentKit.uploadsDir(context)
        return File(dir, "cc_local_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(4)}.$ext")
    }

    private fun compressFormat(fmt: ExportFormat): Bitmap.CompressFormat = when (fmt) {
        ExportFormat.JPEG -> Bitmap.CompressFormat.JPEG
        ExportFormat.WEBP -> Bitmap.CompressFormat.WEBP
        // SVG 是矢量文本，落盘走 saveVector；这里真被调到说明调用方逻辑有 bug，
        // 退到 PNG 至少能产出可看的位图，而不是崩掉。
        ExportFormat.PNG, ExportFormat.SVG -> Bitmap.CompressFormat.PNG
    }

    /** 统一的后端/引擎可用性提示，出错时直接告诉模型下一步该干什么。 */
    protected fun capabilityHint(context: Context): String = buildString {
        appendLine("可用引擎：${CodeCanvas.availableEngines().joinToString(", ") { it.id }}")
        appendLine("可用后端：${CodeCanvas.availableRenderers().joinToString(", ") { it.id }}")
        append("本机只含已打进APK 的模块；要更多引擎/后端需在宿主 build 里加对应模块依赖。")
    }
}

/** Compose ImageBitmap → Android Bitmap。 */
private fun androidx.compose.ui.graphics.ImageBitmap.toBitmap(): Bitmap =
    this.asAndroidBitmap()

/**
 * 端侧脚本出图：写一段脚本 → 绘图指令 → 位图。
 *
 * 与服务端 `codecanvas_script` 的差别只有「在哪执行」。语义、参数、返回格式刻意对齐，
 * 这样模型在两者之间迁移不需要改提示词。
 */
class CodeCanvasOnscreenScriptTool : CodeCanvasOnscreenTool() {
    override val name = "codecanvas_onscreen_script"
    override val description =
        "端侧脚本出图（纯手机本地渲染，不需要任何服务器）：写一段脚本描述画面，" +
        "由内置脚本引擎转成绘图指令再渲染成图片。" +
        "参数 {\"script\":\"脚本源码\",\"engine\":\"javascript|kotlin-dsl\"," +
        "\"renderer\":\"canvas|svg|compose\",\"args\":{给脚本的变量}," +
        "\"format\":\"png|jpeg|webp|svg\",\"width\":1080,\"height\":1440,\"background\":\"#ffffff\"}。" +
        "engine 默认 javascript（QuickJS），renderer 默认 canvas（纯原生最快）。" +
        "适合数据驱动的图表、规则网格、需要按参数批量出图。" +
        "静态排版请用 codecanvas_onscreen_markup，代码高亮卡请用 codecanvas_onscreen_code_card。" +
        "返回本地图片绝对路径，可再调 attach_file 让图片显示在对话框里。"

    override val parametersJson = """
        {"type":"object","properties":{
          "script":{"type":"string","description":"绘制脚本源码。javascript 引擎用 ctx 画布 API；kotlin-dsl 用本 SDK 的行式 DSL。"},
          "engine":{"type":"string","description":"脚本引擎：javascript | kotlin-dsl","enum":["javascript","kotlin-dsl"]},
          "renderer":{"type":"string","description":"渲染后端：canvas | svg | compose","enum":["canvas","svg","compose"]},
          "args":{"type":"object","description":"传给脚本的变量（对应 JS 的 globals）"},
          "format":{"type":"string","description":"输出格式：png | jpeg | webp | svg","enum":["png","jpeg","webp","svg"]},
          "width":{"type":"number","description":"画布宽，默认 1080"},
          "height":{"type":"number","description":"画布高，默认 1440"},
          "scale":{"type":"number","description":"缩放倍数，默认 1.0；2 表示 2 倍图"},
          "padding":{"type":"number","description":"内边距，默认 48"},
          "background":{"type":"string","description":"背景色，如 #ffffff 或 transparent"}
        },"required":["script"]}
    """.trimIndent()

    override fun run(context: Context, arguments: String): String {
        val args = CodeCanvasArgs.parse(arguments)
        val script = args.optString("script").trim()
        if (script.isEmpty()) return "❌ 缺少 script（脚本源码）"
        ensureInstalled(context)

        val engine = engineOf(args)
        if (engine !in CodeCanvas.availableEngines()) {
            return "❌ 本机未注册引擎 ${engine.id}。\n" + capabilityHint(context)
        }
        val kind = rendererOf(args, CodeCanvas.availableRenderers())

        return runBlocking(Dispatchers.IO) {
            try {
                val session = CodeCanvas.session(context)
                    .engine(engine)
                    .renderer(kind)
                    .canvas(canvasOf(args))
                // 只把 args 里明确标了类型的业务参数透传，避免把 width/format 这类
                // 渲染参数也塞进脚本 globals（脚本里拿到 format 只会困惑）。
                val scriptArgs = args.pairs.filter { (k, _) -> k !in RENDER_ONLY_KEYS }
                if (scriptArgs.isNotEmpty()) session.args(*scriptArgs.toTypedArray())

                val outcome = session.run(script)
                when (val r = outcome.scriptResult) {
                    is ScriptResult.Success -> persist(context, args, outcome.render())
                    is ScriptResult.Failure.Timeout ->
                        "❌ 脚本执行超时（${r.timeoutMs} ms）。\n" +
                            "可能是脚本里有死循环或超大循环；把循环次数调小，或改用 canvas 后端分段绘制。\n" +
                            capabilityHint(context)
                    // Compile / Runtime / Sandbox 三者共用 message + stack，
                    // 合并成一支：模型要的是「错在哪」，不是失败分类。
                    is ScriptResult.Failure ->
                        "❌ 脚本执行失败：${r.message}\n${r.stack ?: ""}\n" + capabilityHint(context)
                }
            } catch (e: Throwable) {
                Log.w(TAG, "onscreen script failed", e)
                "❌ 端侧出图失败：${e.message ?: e.javaClass.simpleName}\n" + capabilityHint(context)
            }
        }
    }

    /** 纯渲染参数，不透传给脚本 globals。 */
    private companion object {
        val RENDER_ONLY_KEYS = setOf(
            "script", "engine", "renderer", "format", "width", "height", "scale", "padding", "background",
        )
    }
}

/**
 * 端侧 HTML 出图：Markup 源只支持 WEBVIEW 后端。
 * 缺该模块时明确告知并给出替代路径，而不是含糊报「渲染失败」。
 */
class CodeCanvasOnscreenMarkupTool : CodeCanvasOnscreenTool() {
    override val name = "codecanvas_onscreen_markup"
    override val description =
        "端侧 HTML 出图（纯手机本地渲染）：把一段 HTML/CSS 渲染成图片。" +
        "参数 {\"html\":\"HTML 源码\",\"format\":\"png|jpeg|webp|svg\"}。" +
        "适合海报、信息图、报表这类有精确排版需求的内容 —— 比脚本更好控制版面。" +
        "🔴 需要 WebView 渲染后端，且不支持 JS 执行。" +
        "需要真的能点的界面请用 miniapp / ui_widget 工具，不要用出图。" +
        "返回本地图片绝对路径，可再调 attach_file 显示。"

    override val parametersJson = """
        {"type":"object","properties":{
          "html":{"type":"string","description":"HTML 源码（可含内联 <style>）"},
          "format":{"type":"string","description":"输出格式：png | jpeg | webp | svg","enum":["png","jpeg","webp","svg"]},
          "width":{"type":"number","description":"画布宽，默认 1080"},
          "height":{"type":"number","description":"画布高，默认 1440"},
          "background":{"type":"string","description":"背景色，默认透明"},
          "padding":{"type":"number","description":"内边距，默认 48"}
        },"required":["html"]}
    """.trimIndent()

    override fun run(context: Context, arguments: String): String {
        val args = CodeCanvasArgs.parse(arguments)
        val html = args.optString("html")
        if (html.isBlank()) return "❌ 缺少 html（HTML 源码）"
        ensureInstalled(context)

        if (RenderKind.WEBVIEW !in CodeCanvas.availableRenderers()) {
            return "❌ 本机未包含 WebView 渲染后端，无法渲染 HTML。\n" +
                "改用 codecanvas_onscreen_script（脚本绘图），" +
                "或需要精细 HTML 排版时用服务端 codecanvas_markup（需另起 CodeCanvasServer）。\n" +
                capabilityHint(context)
        }
        return runBlocking(Dispatchers.IO) {
            runCatching {
                val out = CodeCanvas.session(context)
                    .renderer(RenderKind.WEBVIEW)
                    .canvas(canvasOf(args))
                    .render(RenderSource.Markup(html))
                persist(context, args, out)
            }.getOrElse { e ->
                "❌ 端侧 HTML 出图失败：${e.message ?: e.javaClass.simpleName}\n" + capabilityHint(context)
            }
        }
    }
}

/** 端侧代码卡片：源码 → 语法高亮图。 */
class CodeCanvasOnscreenCodeCardTool : CodeCanvasOnscreenTool() {
    override val name = "codecanvas_onscreen_code_card"
    override val description =
        "端侧代码卡片（纯手机本地渲染）：把一段代码渲染成带语法高亮的图片卡片。" +
        "参数 {\"code\":\"源码\",\"language\":\"kotlin|python|javascript|java|go|rust|c|cpp|swift 等\"," +
        "\"title\":\"卡片标题（可选）\",\"renderer\":\"webview|canvas\"}。" +
        "适合把长代码贴进对话框时用图片展示（气泡里长代码会破坏排版）。" +
        "返回本地图片绝对路径，可再调 attach_file 显示。"

    override val parametersJson = """
        {"type":"object","properties":{
          "code":{"type":"string","description":"要渲染的源码"},
          "language":{"type":"string","description":"语言，默认 kotlin","enum":["kotlin","python","javascript","typescript","java","go","rust","c","cpp","swift","ruby","php","shell","sql","json","yaml","html","css"]},
          "title":{"type":"string","description":"卡片顶部标题（可选）"},
          "theme":{"type":"string","description":"配色主题，默认 atom-one-dark"},
          "show_line_numbers":{"type":"boolean","description":"是否显示行号，默认 true"},
          "renderer":{"type":"string","description":"渲染后端：webview | canvas","enum":["webview","canvas"]},
          "format":{"type":"string","description":"输出格式：png | jpeg | webp | svg","enum":["png","jpeg","webp","svg"]},
          "width":{"type":"number","description":"卡片宽，默认 1080"},
          "height":{"type":"number","description":"卡片高，默认 1440"}
        },"required":["code"]}
    """.trimIndent()

    override fun run(context: Context, arguments: String): String {
        val args = CodeCanvasArgs.parse(arguments)
        val code = args.optString("code")
        if (code.isBlank()) return "❌ 缺少 code（源码）"
        ensureInstalled(context)

        val available = CodeCanvas.availableRenderers()
        // 有 WebView 优先用（排版更好），没有则退回 Canvas——两条路都能出图，
        // 不该因为少一个模块就整个功能不可用。
        val kind = if (RenderKind.WEBVIEW in available) RenderKind.WEBVIEW else RenderKind.CANVAS
        val source = RenderSource.CodeCard(
            code = code,
            language = args.optString("language").trim().ifEmpty { "kotlin" },
            theme = args.optString("theme").trim().ifEmpty { "atom-one-dark" },
            showLineNumbers = args.flag("show_line_numbers", true),
            title = args.optString("title").trim().takeIf { it.isNotEmpty() },
        )
        return runBlocking(Dispatchers.IO) {
            runCatching {
                val out = CodeCanvas.session(context).renderer(kind).canvas(canvasOf(args)).render(source)
                persist(context, args, out)
            }.getOrElse { e ->
                "❌ 端侧代码卡片失败：${e.message ?: e.javaClass.simpleName}\n" + capabilityHint(context)
            }
        }
    }
}