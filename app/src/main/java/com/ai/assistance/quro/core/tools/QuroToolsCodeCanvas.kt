package com.ai.assistance.quro.core.tools

import android.content.Context
import android.util.Log
import com.ai.assistance.quro.core.QuroAttachmentKit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * CodeCanvas 出图后端（脚本驱动 / 多引擎 / 多后端）的 AI 工具封装。
 *
 * ## 它是什么
 *
 * 对接外部 `CodeCanvasServer`（FastAPI，默认 `http://127.0.0.1:8000`），
 * 把「画一张图」这件事从「模型自己写 SVG 字符串」升级为「真的渲染出 PNG/JPEG/WebP」：
 *
 * ```
 * JS / Lua / Python / Kotlin-DSL 脚本 ─┐
 * 代码卡片（语法高亮）                 ├─► ScriptEngine ─► DrawList ─► Renderer ─► PNG/JPEG/WebP/SVG
 * HTML（Markup）                     ─┘   （4 引擎）      （DrawCommand）（4 后端）
 * ```
 *
 * 4 引擎 × 4 后端 = 16 种组合，宿主只暴露 4 个**语义清晰**的工具，
 * 而不是把 engine/renderer 组合塞进一个大枚举里让模型瞎猜。
 *
 * ## 服务地址（server_url）的三级来源
 *
 * 1. 工具参数 `server_url`（最高优先，AI 可临时指向局域网另一台机器）
 * 2. SharedPreferences `quro_codecanvas`（由 [CodeCanvasProbeTool] 持久化）
 * 3. [CodeCanvasConfig.DEFAULT_SERVER_URL]（`http://127.0.0.1:8000`）
 *
 * 🔴 参数名**刻意不叫 `base_url`**：`/api/llm/code` 的 `base_url` 指的是
 * 「让服务器去调的那个 LLM 的地址」，与「CodeCanvas 服务自己的地址」是两个东西。
 * 混用会导致模型把服务器地址填进 LLM 的 api_key 旁边，结果必然连不上。
 * 故本工具族一律用 `server_url` 表达自身地址。
 *
 * ## 为什么图片以「路径 + 提示」返回而不是自动挂气泡
 *
 * 与 [ImageGenTool] / [VideoGenTool] 保持一致：工具返回**文本**，
 * 其中带绝对路径 + 一句可直接照抄的 `attach_file` 调用样例。
 * 自动挂附件那条路（`QuroAssistant` 里的 `aiAttPairs`）只对 `attach_file` 生效，
 * 放宽它会波及 sandbox / private_db / terminal 等同样返回 `{"ok":true,...}` 的工具 ——
 * 宁可让模型多调一次 `attach_file`，也不改公共咽喉点的判定。
 *
 * 落盘目录直接用 [QuroAttachmentKit.uploadsDir]：`attach_file` 见到文件已在该目录会
 * **直接复用不复制**（见 QuroToolsAttachFile 的 canonicalPath 快路径）。
 */
private const val TAG = "QuroCodeCanvas"

/** CodeCanvas 服务地址的持久化与校验。 */
object CodeCanvasConfig {
    private const val PREFS = "quro_codecanvas"
    private const val KEY_SERVER_URL = "server_url"

    /** 未配置时的兜底：与 `uvicorn codecanvas.server.main:app --port 8000` 的默认端口一致。 */
    const val DEFAULT_SERVER_URL = "http://127.0.0.1:8000"

    fun serverUrl(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SERVER_URL, "")?.trim().orEmpty()
            .ifEmpty { DEFAULT_SERVER_URL }

    /**
     * 校验并归一化一个服务地址。返回 null 表示非法。
     *
     * 校验只做「像不像 http(s) 网址」，不探测可达性 —— 探测是网络行为，
     * 而 SharedPreferences 写入可能发生在主线程。
     */
    fun normalizeServerUrl(raw: String): String? {
        val t = raw.trim().trimEnd('/')
        if (t.isEmpty()) return null
        if (!t.startsWith("http://") && !t.startsWith("https://")) return null
        val afterScheme = t.substringAfter("://", "")
        if (afterScheme.isEmpty() || afterScheme.startsWith("/")) return null
        if (afterScheme.any { it.isWhitespace() }) return null
        return t
    }

    /** 持久化服务地址。返回归一化后的值；非法返回 null 且**不写**任何东西。 */
    fun setServerUrl(context: Context, raw: String): String? {
        val ok = normalizeServerUrl(raw) ?: return null
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_SERVER_URL, ok).apply()
        return ok
    }
}

/**
 * 四个出图工具的公共骨架：地址解析 / HTTP 发送 / 图片落盘 / 错误翻译。
 *
 * 抽出来是因为四份重复的 OkHttp + 落盘 + 错误分支一定会漂移，
 * 而漂移的代价是「同一个故障在四个工具里表现成四种文案」，用户无从判断。
 */
abstract class CodeCanvasToolBase : QuroTool {

    /** 有副作用（写文件 / 改配置），故不声明 readOnly。 */
    override val readOnly: Boolean get() = false

    /**
     * 服务地址三级来源：参数 > 已持久化值 > 默认值。
     *
     * `context` 显式透传而**不缓存成字段**：同一工具实例会被并发调用（QuroToolEngine 的
     * 只读并发分支），把 context 存进可变字段等于让并发调用互相覆盖。
     */
    protected fun resolveServerUrl(context: Context, args: JSONObject): String =
        CodeCanvasConfig.normalizeServerUrl(args.optString("server_url", ""))
            ?: CodeCanvasConfig.serverUrl(context)

    /**
     * 发 POST 并把响应落盘 / 转文本。
     *
     * @param endpoint 例如 `/api/script`
     * @param body 已构造好的请求体（`return_file` 由本函数强制为 false）
     * @param timeoutMs 服务端渲染超时
     * @param wantImage true=期望二进制图片；false=期望文本/JSON（如 /api/llm/code 返回代码）
     */
    protected fun call(
        context: Context,
        args: JSONObject,
        endpoint: String,
        body: JSONObject,
        timeoutMs: Int,
        wantImage: Boolean,
    ): String {
        val serverUrl = resolveServerUrl(context, args)
        // 🔴 永远 false：return_file=true 返回的是**服务端**路径（App 读不到），
        //    还得多发一次 GET 才能拿到字节；直接收二进制一次到位。
        body.put("return_file", false)
        val url = serverUrl.trimEnd('/') + endpoint
        return runBlocking(Dispatchers.IO) {
            try {
                val req = Request.Builder()
                    .url(url)
                    .post(body.toString().toRequestBody(JSON_MEDIA))
                    .build()
                CLIENT.newCall(req).execute().use { resp ->
                    val code = resp.code
                    val ctype = resp.header("Content-Type").orEmpty().lowercase()
                    val bytes = resp.body?.bytes() ?: ByteArray(0)
                    if (code !in 200..299) {
                        return@use describeHttpError(code, bytes, url)
                    }
                    if (wantImage && ctype.startsWith("image/")) {
                        return@use onImage(context, bytes, ctype, url)
                    }
                    if (wantImage) {
                        // 服务端 200 但不是图片：多半是它自己内部出错后返回了文本。
                        Log.w(TAG, "expect image but got $ctype from $url")
                        return@use "⚠️ CodeCanvas 返回了非图片内容（$ctype）：\n" + clip(String(bytes))
                    }
                    onText(bytes, ctype)
                }
            } catch (e: java.net.ConnectException) {
                "❌ 连不上 CodeCanvasServer（$serverUrl）：${e.message}\n" +
                    "请先在服务器上启动：uvicorn codecanvas.server.main:app --host 0.0.0.0 --port 8000\n" +
                    "若服务在另一台机器，请让服务监听 0.0.0.0 并把 server_url 设成它的局域网地址。"
            } catch (e: java.net.SocketTimeoutException) {
                "❌ CodeCanvas 请求超时（>55s）。渲染引擎可能过重（chromium 后端首次启动尤其慢），" +
                    "或 timeout_ms 设得太小。超时上限受宿主 60s 工具硬超时约束，不能再调大。"
            } catch (e: Exception) {
                Log.w(TAG, "codecanvas call failed: $url", e)
                "❌ CodeCanvas 请求失败：${e.message}"
            }
        }
    }

    /** 二进制图片 → 落盘 → 返回路径 + 可照抄的 attach_file 调用。 */
    private fun onImage(context: Context, bytes: ByteArray, contentType: String, url: String): String {
        if (bytes.isEmpty()) return "❌ CodeCanvas 返回了空图片（$url）"
        val ext = extOf(contentType)
        val dir = QuroAttachmentKit.uploadsDir(context)
        val file = File(dir, "cc_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(4)}.$ext")
        file.outputStream().use { it.write(bytes) }
        val kb = file.length() / 1024
        return buildString {
            appendLine("✅ CodeCanvas 出图成功（$ext，${kb} KB）")
            appendLine("本地路径：${file.absolutePath}")
            appendLine("来源端点：$url")
            if (ext == "svg") {
                // 工具结果是纯文本（模型看，不进 Markdown 渲染），故不用 ** 强调。
                appendLine("⚠️ SVG 是矢量文本，对话框气泡用位图解码器渲染，不会显示。" +
                    "要在气泡里看到请改用 format=png 重新调用一次。")
            }
            append("如需在对话框显示这张图，调用：attach_file {\"path\":\"${file.absolutePath}\",\"caption\":\"$captionHint\"}")
        }
    }

    /** 文本 / JSON 响应：尽力美化 JSON，超长截断。 */
    private fun onText(bytes: ByteArray, contentType: String): String {
        val raw = String(bytes)
        if (contentType.contains("json")) {
            val pretty = runCatching { JSONObject(raw).toString(2) }.getOrNull()
            if (pretty != null) return clip(pretty)
        }
        return clip(raw)
    }

    /** 把 FastAPI 的错误体翻译成人能看懂的一句话。 */
    private fun describeHttpError(code: Int, bytes: ByteArray, url: String): String {
        val raw = runCatching { String(bytes) }.getOrDefault("")
        val detail = runCatching {
            val o = JSONObject(raw)
            when (val d = o.opt("detail")) {
                is String -> d
                is JSONArray -> (0 until d.length()).joinToString("；") { i ->
                    val e = d.optJSONObject(i) ?: return@joinToString ""
                    val loc = e.optJSONArray("loc")?.let { l ->
                        (0 until l.length()).joinToString(".") { l.optString(it) }
                    }.orEmpty()
                    val msg = e.optString("msg")
                    if (loc.isEmpty()) msg else "$loc: $msg"
                }
                else -> null
            }
        }.getOrNull()
        val head = "❌ CodeCanvas HTTP $code"
        val tail = when (code) {
            404 -> "\n端点不存在（$url）—— 服务端版本可能不含该能力，先用 codecanvas_probe 看能力清单。"
            422 -> "\n参数不被服务端接受（通常是字段名写错或类型不对）。"
            503 -> "\n服务端依赖缺失或后端不可用（例如 chromium 后端需要 playwright）。"
            in 500..599 -> "\n服务端内部错误。"
            else -> ""
        }
        return head + tail + (detail?.let { "\n详情：$it" } ?: raw.takeIf { it.isNotBlank() }?.let { "\n\n" + clip(it) } ?: "")
    }

    /** 服务端默认 1080×1440 / padding 48 / scale 1.0，与 Pydantic 模型保持一致。 */
    protected fun canvasOf(args: JSONObject): JSONObject {
        val c = JSONObject()
        c.put("width", num(args, "width", 1080.0))
        c.put("height", num(args, "height", 1440.0))
        c.put("scale", num(args, "scale", 1.0))
        args.optString("background").trim().takeIf { it.isNotEmpty() }?.let { c.put("background", it) }
        gradientOf(args)?.let { c.put("gradient", it) }
        c.put("padding", num(args, "padding", 48.0))
        return c
    }

    /**
     * 取一个数值参数，**容忍模型把它写成字符串**。
     *
     * `JSONObject.optDouble` 只在值确实是数字时才可靠；LLM 把 `1080` 写成 `"1080"`
     * 是高频现象（尤其 JSON 生成不稳的模型）。静默取默认值会让模型以为自己设了 1080，
     * 实际出的却是 1440 —— 一张尺寸完全不对的图，比直接报错更难排查。
     */
    private fun num(args: JSONObject, key: String, default: Double): Double {
        args.opt(key)?.let { v ->
            when (v) {
                is Number -> return v.toDouble()
                is String -> v.trim().toDoubleOrNull()?.let { return it }
            }
        }
        return default
    }

    /** `gradient` 既接受 JSON 数组，也接受逗号分隔字符串（模型常写后者）。 */
    private fun gradientOf(args: JSONObject): JSONArray? {
        args.optJSONArray("gradient")?.let { return it }
        val s = args.optString("gradient").trim()
        if (s.isEmpty()) return null
        if (!s.startsWith("[")) {
            val arr = JSONArray()
            s.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { arr.put(it) }
            return if (arr.length() > 0) arr else null
        }
        return runCatching { JSONArray(s) }.getOrNull()
    }

    private fun extOf(contentType: String): String = when {
        contentType.contains("svg") -> "svg"
        contentType.contains("jpeg") || contentType.contains("jpg") -> "jpg"
        contentType.contains("webp") -> "webp"
        else -> "png"
    }

    private fun clip(s: String, max: Int = 4000): String =
        if (s.length <= max) s else s.take(max) + "\n...[截断 ${s.length - max} 字符]"

    protected companion object {
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        /**
         * readTimeout 55s：必须**小于**宿主工具硬超时 60s（QuroToolEngine 的
         * `TOOL_EXEC_TIMEOUT_MS`），否则 OkHttp 先超时，闭环会误判成可重试的 TRANSPORT 失败，
         * 而真实原因是渲染太慢 —— 重试只会再烧一次同样的时间。
         */
        val CLIENT: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(55, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}

/** `attach_file` caption 的统一建议文案。 */
private const val captionHint = "CodeCanvas 出图"

/**
 * 脚本驱动出图（POST /api/script）：写一段脚本描述画面，由引擎转成绘图指令再渲染。
 *
 * 适用：数据驱动的图表、规则网格、需要按参数批量出图。
 * 静态排版请改用 `codecanvas_code_card`（代码高亮卡）或 `codecanvas_markup`（HTML）。
 */
class CodeCanvasScriptTool : CodeCanvasToolBase() {
    override val name = "codecanvas_script"
    override val description =
        "CodeCanvas 脚本出图：写一段脚本描述画面，由脚本引擎转成绘图指令再渲染成图片。" +
        "参数 {\"script\":\"脚本源码\",\"engine\":\"javascript|lua|python|kotlin-dsl\"," +
        "\"renderer\":\"pillow|chromium|cairo|svg\",\"args\":{给脚本的变量}," +
        "\"format\":\"png|jpeg|webp|svg\",\"width\":1080,\"height\":1440,\"background\":\"#ffffff\"}。" +
        "engine 默认 javascript，renderer 默认 pillow（最快，无需额外依赖）；" +
        "renderer=chromium 排版最精细但需要服务端装 playwright 且首次启动慢。" +
        "返回本地图片绝对路径，可再调 attach_file 让图片显示在对话框里。"

    override val parametersJson = """
        {"type":"object","properties":{
          "script":{"type":"string","description":"绘制脚本源码。javascript 引擎可用 ctx 画布 API；python 用 draw 对象。"},
          "engine":{"type":"string","description":"脚本引擎：javascript | lua | python | kotlin-dsl","enum":["javascript","lua","python","kotlin-dsl"]},
          "renderer":{"type":"string","description":"渲染后端：pillow | chromium | cairo | svg","enum":["pillow","chromium","cairo","svg"]},
          "args":{"type":"object","description":"传给脚本的变量（对应各语言里的 globals / _G / 全局字典 / 顶层属性）"},
          "format":{"type":"string","description":"输出格式：png | jpeg | webp | svg","enum":["png","jpeg","webp","svg"]},
          "width":{"type":"number","description":"画布宽，默认 1080"},
          "height":{"type":"number","description":"画布高，默认 1440"},
          "scale":{"type":"number","description":"缩放倍数，默认 1.0；2 表示 2 倍图"},
          "background":{"type":"string","description":"背景色，如 #ffffff 或 transparent"},
          "gradient":{"type":"array","items":{"type":"string"},"description":"渐变色数组，如 [\"#ff8a00\",\"#ff2d78\"]"},
          "timeout_ms":{"type":"integer","description":"服务端渲染超时，默认 8000，最大 45000"},
          "server_url":{"type":"string","description":"CodeCanvas 服务地址，默认 http://127.0.0.1:8000"}
        },"required":["script"]}
    """.trimIndent()

    override fun run(context: Context, arguments: String): String {
        val args = runCatching { JSONObject(arguments) }.getOrElse { JSONObject() }
        val script = args.optString("script").trim()
        if (script.isEmpty()) return "❌ 缺少 script（脚本源码）"
        val body = JSONObject()
            .put("script", script)
            .put("engine", enumOf(args, "engine", "javascript", setOf("javascript", "lua", "python", "kotlin-dsl")))
            .put("renderer", enumOf(args, "renderer", "pillow", setOf("pillow", "chromium", "cairo", "svg")))
            .put("format", enumOf(args, "format", "png", FORMATS))
            .put("canvas", canvasOf(args))
            .put("args", args.optJSONObject("args") ?: JSONObject())
            .put("timeout_ms", args.optInt("timeout_ms", 8000).coerceIn(500, 45000))
        return call(context, args, "/api/script", body, body.optInt("timeout_ms"), wantImage = true)
    }
}

/** 代码卡片出图（POST /api/code-card）：把一段代码渲染成带语法高亮的图片。 */
class CodeCanvasCodeCardTool : CodeCanvasToolBase() {
    override val name = "codecanvas_code_card"
    override val description =
        "CodeCanvas 代码卡片：把一段代码渲染成带语法高亮的图片卡片。" +
        "参数 {\"code\":\"源码\",\"language\":\"kotlin|python|javascript|java|go|rust|c\"|cpp|swift...\"," +
        "\"title\":\"卡片标题（可选）\",\"renderer\":\"chromium|cairo|pillow|svg\"}。" +
        "适合把长代码贴进对话框时用图片展示（气泡里长代码会破坏排版）。" +
        "返回本地图片绝对路径，可再调 attach_file 显示。"

    override val parametersJson = """
        {"type":"object","properties":{
          "code":{"type":"string","description":"要渲染的源码"},
          "language":{"type":"string","description":"语言，默认 kotlin","enum":["kotlin","python","javascript","typescript","java","go","rust","c","cpp","swift","ruby","php","shell","sql","json","yaml","html","css"]},
          "title":{"type":"string","description":"卡片顶部标题（可选）"},
          "renderer":{"type":"string","description":"渲染后端：chromium | cairo | pillow | svg","enum":["chromium","cairo","pillow","svg"]},
          "format":{"type":"string","description":"输出格式：png | jpeg | webp | svg","enum":["png","jpeg","webp","svg"]},
          "width":{"type":"number","description":"卡片宽，默认 1080"},
          "height":{"type":"number","description":"卡片高，默认 1440（内容不足时留白）"},
          "padding":{"type":"number","description":"内边距，默认 48"},
          "timeout_ms":{"type":"integer","description":"服务端渲染超时，默认 15000，最大 45000"},
          "server_url":{"type":"string","description":"CodeCanvas 服务地址，默认 http://127.0.0.1:8000"}
        },"required":["code"]}
    """.trimIndent()

    override fun run(context: Context, arguments: String): String {
        val args = runCatching { JSONObject(arguments) }.getOrElse { JSONObject() }
        val code = args.optString("code")
        if (code.isBlank()) return "❌ 缺少 code（源码）"
        val body = JSONObject()
            .put("code", code)
            .put("language", args.optString("language").trim().ifEmpty { "kotlin" })
            .put("renderer", enumOf(args, "renderer", "chromium", setOf("chromium", "cairo", "pillow", "svg")))
            .put("format", enumOf(args, "format", "png", FORMATS))
            .put("canvas", canvasOf(args))
            .put("timeout_ms", args.optInt("timeout_ms", 15000).coerceIn(500, 45000))
        args.optString("title").trim().takeIf { it.isNotEmpty() }?.let { body.put("title", it) }
        return call(context, args, "/api/code-card", body, body.optInt("timeout_ms"), wantImage = true)
    }
}

/** HTML 出图（POST /api/markup）：把一段 HTML 渲染成图片。 */
class CodeCanvasMarkupTool : CodeCanvasToolBase() {
    override val name = "codecanvas_markup"
    override val description =
        "CodeCanvas HTML 出图：把一段 HTML/CSS 渲染成图片。" +
        "参数 {\"html\":\"HTML 源码\",\"format\":\"png|jpeg|webp|svg\"}。" +
        "适合海报、信息图、报表这类有精确排版需求的内容 —— 比脚本更好控制版面。" +
        "不支持 JS 执行。需要真的能点的界面请用 miniapp / ui_widget 工具，不要用出图。" +
        "返回本地图片绝对路径，可再调 attach_file 显示。"

    override val parametersJson = """
        {"type":"object","properties":{
          "html":{"type":"string","description":"HTML 源码（可含内联 <style>）"},
          "format":{"type":"string","description":"输出格式：png | jpeg | webp | svg","enum":["png","jpeg","webp","svg"]},
          "width":{"type":"number","description":"画布宽，默认 1080"},
          "height":{"type":"number","description":"画布高，默认 1440"},
          "background":{"type":"string","description":"背景色，默认透明（PNG 支持透明底）"},
          "padding":{"type":"number","description":"内边距，默认 48"},
          "timeout_ms":{"type":"integer","description":"服务端渲染超时，默认 15000，最大 45000"},
          "server_url":{"type":"string","description":"CodeCanvas 服务地址，默认 http://127.0.0.1:8000"}
        },"required":["html"]}
    """.trimIndent()

    override fun run(context: Context, arguments: String): String {
        val args = runCatching { JSONObject(arguments) }.getOrElse { JSONObject() }
        val html = args.optString("html")
        if (html.isBlank()) return "❌ 缺少 html（HTML 源码）"
        val body = JSONObject()
            .put("html", html)
            .put("format", enumOf(args, "format", "png", FORMATS))
            .put("canvas", canvasOf(args))
            .put("timeout_ms", args.optInt("timeout_ms", 15000).coerceIn(500, 45000))
        return call(context, args, "/api/markup", body, body.optInt("timeout_ms"), wantImage = true)
    }
}

/**
 * 需求 → LLM 写代码（POST /api/llm/code）。
 *
 * 这是 6 个端点里**唯一不返回图片**的：它让服务端调 LLM 产出代码文本。
 * 故 `wantImage = false`，按文本/JSON 返回。
 */
class CodeCanvasLlmCodeTool : CodeCanvasToolBase() {
    override val name = "codecanvas_llm_code"
    override val description =
        "CodeCanvas 需求转代码：把一段自然语言需求交给服务端配置的 LLM，产出对应语言的代码。" +
        "参数 {\"requirement\":\"需求描述\",\"language\":\"kotlin|python|...\"}。" +
        "用途是「先要代码再看要不要出图」—— 拿到代码后可再调 codecanvas_markup 或 codecanvas_code_card 渲染。" +
        "⚠️ 参数 base_url / api_key 指的是「要让服务端去调的那个 LLM」的地址与密钥，" +
        "与 CodeCanvas 服务自身地址（server_url）完全是两回事，别填混。" +
        "不传 base_url/api_key 时服务端用环境变量 CODECANVAS_LLM_BASE。" +
        "本工具返回代码文本，不返回图片。"

    override val parametersJson = """
        {"type":"object","properties":{
          "requirement":{"type":"string","description":"自然语言需求描述，写得越具体产出越准"},
          "language":{"type":"string","description":"目标语言，默认 kotlin"},
          "model":{"type":"string","description":"LLM 模型名，默认 gpt-4o-mini"},
          "base_url":{"type":"string","description":"让服务端去调的 LLM 的 API 地址（不是 CodeCanvas 自己的地址）"},
          "api_key":{"type":"string","description":"上面那个 LLM 的 API Key"},
          "server_url":{"type":"string","description":"CodeCanvas 服务地址，默认 http://127.0.0.1:8000"}
        },"required":["requirement"]}
    """.trimIndent()

    override fun run(context: Context, arguments: String): String {
        val args = runCatching { JSONObject(arguments) }.getOrElse { JSONObject() }
        val req = args.optString("requirement").trim()
        if (req.isEmpty()) return "❌ 缺少 requirement（需求描述）"
        val body = JSONObject()
            .put("requirement", req)
            .put("language", args.optString("language").trim().ifEmpty { "kotlin" })
            .put("model", args.optString("model").trim().ifEmpty { "gpt-4o-mini" })
        args.optString("base_url").trim().takeIf { it.isNotEmpty() }?.let { body.put("base_url", it) }
        args.optString("api_key").trim().takeIf { it.isNotEmpty() }?.let { body.put("api_key", it) }
        return call(context, args, "/api/llm/code", body, timeoutMs = 45_000, wantImage = false)
    }
}

/**
 * CodeCanvas 探活 / 配置（GET /health + GET /api/capabilities）。
 *
 * 存在的意义：出图失败时，模型第一件事应该是「查服务在不在 / 有哪些能力」，
 * 而不是把 timeout_ms 越调越大反复重试。
 * 同时承担服务地址的持久化（`set_server_url`），省掉一个设置页。
 */
class CodeCanvasProbeTool : CodeCanvasToolBase() {
    override val name = "codecanvas_probe"
    override val description =
        "CodeCanvas 探活与配置：检查出图服务是否在线、列出它支持的引擎/后端/端点，" +
        "以及读取或设置服务地址。" +
        "参数 {\"set_server_url\":\"要保存的服务地址（可选，合法格式 http(s)://host:port）\"}。" +
        "出图失败先调它：能立刻区分「服务没起」「地址填错」「缺后端依赖」三类原因。"

    override val parametersJson = """
        {"type":"object","properties":{
          "set_server_url":{"type":"string","description":"可选：把 CodeCanvas 服务地址持久化保存，如 http://192.168.1.5:8000"},
          "server_url":{"type":"string","description":"可选：本次探测用这个地址探测而不读已保存的值"}
        }}
    """.trimIndent()

    override fun run(context: Context, arguments: String): String {
        val args = runCatching { JSONObject(arguments) }.getOrElse { JSONObject() }

        args.optString("set_server_url").trim().takeIf { it.isNotEmpty() }?.let { raw ->
            val saved = CodeCanvasConfig.setServerUrl(context, raw)
            return if (saved == null) {
                "❌ 服务地址非法：$raw\n必须形如 http://192.168.1.5:8000 或 https://canvas.example.com" +
                    "（不能带路径尾斜杠以外的路径、不能有空格）"
            } else {
                "✅ 已保存 CodeCanvas 服务地址：$saved\n" + probe(context, saved)
            }
        }

        val target = CodeCanvasConfig.normalizeServerUrl(args.optString("server_url", ""))
            ?: CodeCanvasConfig.serverUrl(context)
        return "当前服务地址：$target\n" + probe(context, target)
    }

    private fun probe(context: Context, serverUrl: String): String = runBlocking(Dispatchers.IO) {
        val sb = StringBuilder()
        var reachable = false
        for (path in listOf("/health", "/api/capabilities")) {
            val url = serverUrl.trimEnd('/') + path
            try {
                CLIENT.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    val ok = resp.code in 200..299
                    if (ok) reachable = true
                    sb.appendLine(if (ok) "✅ $path → HTTP ${resp.code}" else "⚠️ $path → HTTP ${resp.code}")
                    if (body.isNotBlank()) {
                        val pretty = runCatching { JSONObject(body).toString(2) }.getOrNull()
                            ?: body
                        sb.appendLine("   " + pretty.replace("\n", "\n   ").take(1500))
                    }
                }
            } catch (e: Exception) {
                sb.appendLine("❌ $path → ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        if (!reachable) {
            sb.appendLine()
            sb.append("服务不可达。启动方式：uvicorn codecanvas.server.main:app --host 0.0.0.0 --port 8000")
            sb.append("\n依赖：pip install lupa fastapi uvicorn httpx pillow cairosvg pygments numpy" +
                "（renderer=chromium 还需 playwright）")
            sb.append("\n跨设备访问时服务必须监听 0.0.0.0，并把地址设成它的局域网 IP。")
        }
        sb.toString().trimEnd()
    }
}

/** 合法取值集合：命中则用命中值，否则回落到 default（不报错，静默纠正模型的小错）。 */
private val FORMATS = setOf("png", "jpeg", "webp", "svg")

private fun enumOf(args: JSONObject, key: String, default: String, allowed: Set<String>): String {
    val raw = args.optString(key).trim().lowercase()
    return if (raw in allowed) raw else default
}