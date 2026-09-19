package com.ai.assistance.quro.genui.app.agent

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 代码运行时 —— 离屏 WebView JS 引擎（Chromium 真实执行）。
 *
 *  - ES2020+ / async / await / fetch 网络请求全支持
 *  - console.log/warn/error 捕获回传（logs 数组）
 *  - 返回值 JSON 序列化回传（__result 轮询协议）
 *  - 超时保护（默认 40s，防死循环拖死生成）
 *
 * 执行协议：evalAsync 把异步表达式写入页面，脚本完成后设 window.__result +
 * window.__token；native 侧轮询 token 命中即取 JSON。
 */
object CodeRuntime {

    private var wv: WebView? = null
    private var booted = false
    private val main = Handler(Looper.getMainLooper())
    private const val TIMEOUT_MS = 40_000L

    private val BOOT_JS = """
        (function(){
            if (window.__booted) return; window.__booted = true;
            window.__result = null; window.__token = null; window.__logs = []; window.__plugins = {};
            const fmt = (x) => { try { return (typeof x === 'object' && x !== null) ? JSON.stringify(x) : String(x); } catch (e) { return String(x); } };
            ['log','info','warn','error'].forEach((lvl) => {
                const orig = console[lvl] ? console[lvl].bind(console) : function(){};
                console[lvl] = function() {
                    const a = Array.prototype.slice.call(arguments);
                    try { window.__logs.push(lvl + ': ' + a.map(fmt).join(' ')); if (window.__logs.length > 200) window.__logs.shift(); } catch (e) {}
                    try { orig.apply(null, a); } catch (e) {}
                };
            });
            window.__pluginExec = async function(pid, tool, args) {
                const m = (window.__plugins || {})[pid];
                if (!m || typeof m[tool] !== 'function') return { ok: false, error: '插件函数不存在：' + pid + '.' + tool };
                try { const r = await m[tool](args); return { ok: true, result: (r === undefined ? null : r) }; }
                catch (e) { return { ok: false, error: String((e && e.message) || e) }; }
            };
        })()
    """.trimIndent()

    @Synchronized
    private fun engine(ctx: Context, onReady: () -> Unit) {
        if (wv != null && booted) { onReady(); return }
        main.post {
            if (wv == null) {
                wv = WebView(ctx.applicationContext).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    setBackgroundColor(android.graphics.Color.WHITE)
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(v: WebView, url: String?) {
                            v.evaluateJavascript(BOOT_JS, null)
                            booted = true
                            onReady()
                        }
                    }
                    loadDataWithBaseURL(
                        "https://genui.local/", "<html><body>GenUI runtime</body></html>",
                        "text/html", "utf-8", null
                    )
                }
            } else {
                booted = true
                onReady()
            }
        }
    }

    private fun evalOnce(js: String, cb: (String?) -> Unit) {
        main.post { wv?.evaluateJavascript(js) { r -> cb(r) } }
    }

    /** 引擎就绪后执行一段无返回需求脚本（如插件定义） */
    fun evalDefine(ctx: Context, js: String) {
        val latch = CountDownLatch(1)
        engine(ctx) {
            evalOnce(js) { latch.countDown() }
        }
        latch.await(5, TimeUnit.SECONDS)
    }

    /**
     * 执行一段异步 JS 表达式（返回 Promise 或值），轮询取回 JSON 结果。
     * 返回 {ok, result?, error?, logs?}。
     */
    fun evalAsync(ctx: Context, jsExpr: String, timeoutMs: Long = TIMEOUT_MS): JSONObject {
        val token = "t" + java.lang.Long.toUnsignedString(System.nanoTime())
        val boot = CountDownLatch(1)
        engine(ctx) { boot.countDown() }
        if (!boot.await(15, TimeUnit.SECONDS)) {
            return JSONObject().put("ok", false).put("error", "JS 引擎启动超时")
        }
        val script = "(function(){ window.__result = null; window.__token = null;" +
            " (async () => { try { const r = await (async () => (" + jsExpr + "))();" +
            " return { ok: true, result: (r === undefined ? null : r), logs: (window.__logs || []).slice(-100) }; }" +
            " catch (e) { return { ok: false, error: String((e && e.message) || e), logs: (window.__logs || []).slice(-100) }; } })()" +
            ".then(function(v){ window.__result = v; window.__token = '$token'; }); })()"
        evalOnce(script) { }
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(150)
            val latch = CountDownLatch(1)
            val got = AtomicReference<String?>(null)
            evalOnce("(window.__token === '$token') ? JSON.stringify(window.__result) : null") {
                r -> got.set(r); latch.countDown()
            }
            latch.await(3, TimeUnit.SECONDS)
            val raw = got.get()?.trim()
            if (!raw.isNullOrBlank() && raw != "null") {
                return try {
                    // evaluateJavascript 返回 JSON 字符串字面量，JSONArray 一层解码
                    val decoded = JSONArray("[$raw]").optString(0)
                    JSONObject(decoded)
                } catch (e: Exception) {
                    JSONObject().put("ok", false).put("error", "结果解析失败：" + (e.message ?: "?"))
                }
            }
        }
        return JSONObject().put("ok", false)
            .put("error", "执行超时（${timeoutMs / 1000}s）——检查是否有死循环或阻塞调用")
    }

    /** 运行用户 JS 代码（函数体，支持 return；自动包 async IIFE） */
    fun runJs(context: Context, code: String, timeoutMs: Long = TIMEOUT_MS): JSONObject {
        if (code.isBlank()) return JSONObject().put("ok", false).put("error", "代码为空")
        return evalAsync(context, "(async () => { " + code + " })()", timeoutMs)
    }
}

/**
 * 插件运行时 —— 插件 = JS 代码 + 工具清单。
 *
 *  - AI 通过 install_plugin 安装插件（声明工具 + 实现代码，代码 return {工具名: async fn}）
 *  - 插件函数注册到引擎 window.__plugins.<id>.<tool>，可 async（fetch 等）
 *  - 工具并入 function calling（plugin_<id>_<tool>），调用路由回插件函数
 *  - 存储files/gen/plugins.json（单文件清单，含代码）
 */
object PluginRuntime {

    data class ToolSpec(val name: String, val description: String, val parameters: JSONObject)
    data class Plugin(
        val id: String, val name: String, val version: String,
        val tools: List<ToolSpec>, val code: String, val installedAt: Long,
    )

    private const val MAX_PLUGINS = 20
    private var fqnMap: Map<String, Pair<String, String>> = emptyMap()

    private fun file(ctx: Context) = File(ctx.filesDir, "gen/plugins.json").apply { parentFile?.mkdirs() }

    fun list(ctx: Context): List<Plugin> = runCatching {
        val raw = file(ctx).takeIf { it.exists() }?.readText() ?: return emptyList()
        val arr = JSONObject(raw).optJSONArray("plugins") ?: return emptyList()
        (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { o ->
                val tools = mutableListOf<ToolSpec>()
                o.optJSONArray("tools")?.let { ta ->
                    for (j in 0 until ta.length()) ta.optJSONObject(j)?.let { t ->
                        tools.add(ToolSpec(
                            t.optString("name"),
                            t.optString("description").ifBlank { t.optString("name") },
                            t.optJSONObject("parameters") ?: JSONObject().put("type", "object").put("properties", JSONObject()),
                        ))
                    }
                }
                Plugin(o.optString("id"), o.optString("name"), o.optString("version", "1.0"),
                    tools, o.optString("code"), o.optLong("installedAt"))
            }
        }
    }.getOrDefault(emptyList())

    private fun save(ctx: Context, list: List<Plugin>) {
        val arr = JSONArray()
        list.forEach { p ->
            val ta = JSONArray()
            p.tools.forEach { t ->
                ta.put(JSONObject().put("name", t.name).put("description", t.description).put("parameters", t.parameters))
            }
            arr.put(JSONObject()
                .put("id", p.id).put("name", p.name).put("version", p.version)
                .put("tools", ta).put("code", p.code).put("installedAt", p.installedAt))
        }
        file(ctx).writeText(JSONObject().put("plugins", arr).toString())
    }

    fun install(ctx: Context, name: String, version: String, toolsJson: String, code: String): JSONObject {
        if (name.isBlank() || code.isBlank()) {
            return JSONObject().put("ok", false).put("error", "插件名或代码为空")
        }
        val tools = mutableListOf<ToolSpec>()
        runCatching {
            val ta = JSONArray(toolsJson)
            for (i in 0 until ta.length()) ta.optJSONObject(i)?.let { t ->
                val tn = t.optString("name").trim()
                if (tn.isNotBlank()) tools.add(ToolSpec(
                    tn,
                    t.optString("description").ifBlank { tn },
                    t.optJSONObject("parameters") ?: JSONObject().put("type", "object").put("properties", JSONObject()),
                ))
            }
        }
        if (tools.isEmpty()) {
            return JSONObject().put("ok", false).put("error", "工具清单解析失败或为空（tools_json 需为 JSON 数组字符串）")
        }
        val all = list(ctx)
        if (all.size >= MAX_PLUGINS) {
            return JSONObject().put("ok", false).put("error", "插件数已达上限（$MAX_PLUGINS），请先卸载部分插件")
        }
        val kept = all.filterNot { it.name == name }
        val id = "p" + java.lang.Long.toString(System.currentTimeMillis() % 100000, 36) +
            ('a' + (0..25).random()) + ('a' + (0..25).random())
        val p = Plugin(id, name, version.ifBlank { "1.0" }, tools, code, System.currentTimeMillis())
        save(ctx, kept + p)
        return JSONObject().put("ok", true).put("id", id).put("name", name)
            .put("tools", JSONArray(tools.map { it.name }))
            .put("note", "已安装并注册 ${tools.size} 个工具，下轮对话即可直接调用")
    }

    fun uninstall(ctx: Context, idOrName: String): JSONObject {
        val all = list(ctx)
        val target = all.firstOrNull { it.id == idOrName || it.name == idOrName }
            ?: return JSONObject().put("ok", false).put("error", "未找到插件：$idOrName")
        save(ctx, all - target)
        return JSONObject().put("ok", true).put("removed", target.name)
    }

    /** 插件工具声明（fqn = plugin_<id>_<tool>），并缓存路由映射 */
    fun declarations(ctx: Context): JSONArray {
        val out = JSONArray()
        val map = mutableMapOf<String, Pair<String, String>>()
        list(ctx).forEach { p ->
            p.tools.forEach { t ->
                val fqn = "plugin_" + p.id + "_" + sanitize(t.name)
                map[fqn] = p.id to t.name
                out.put(JSONObject().put("type", "function").put("function", JSONObject()
                    .put("name", fqn)
                    .put("description", "[插件·" + p.name + "] " + t.description)
                    .put("parameters", t.parameters)))
            }
        }
        fqnMap = map
        return out
    }

    /** 调用插件工具：定义插件代码（幂等）→ __pluginExec 异步执行 → 结果解包 */
    fun callTool(ctx: Context, fqn: String, args: JSONObject): JSONObject {
        if (fqnMap.isEmpty()) declarations(ctx)
        val hit = fqnMap[fqn]
            ?: return JSONObject().put("ok", false).put("error", "未注册的插件工具（插件可能已卸载）")
        val (pid, tool) = hit
        val p = list(ctx).firstOrNull { it.id == pid }
            ?: return JSONObject().put("ok", false).put("error", "插件不存在（可能已卸载）")
        val defineJs = "(function(){ window.__plugins = window.__plugins || {};" +
            " window.__plugins['" + pid + "'] = (function(){ " + p.code + " })(); })()"
        CodeRuntime.evalDefine(ctx, defineJs)
        val callJs = "(async () => window.__pluginExec('" + pid + "', '" + tool + "', " + args.toString() + "))()"
        val r = CodeRuntime.evalAsync(ctx, callJs, 90_000L)
        return when {
            r.optBoolean("ok") && r.opt("result") is JSONObject -> r.getJSONObject("result")
            r.optBoolean("ok") && r.opt("result") is String -> runCatching {
                JSONObject(r.optString("result"))
            }.getOrDefault(JSONObject().put("ok", true).put("result", r.optString("result")))
            r.optBoolean("ok") -> r
            else -> JSONObject().put("ok", false).put("error", r.optString("error", "插件执行失败"))
        }
    }

    private fun sanitize(raw: String): String {
        val sb = StringBuilder()
        raw.forEach { ch -> sb.append(if (ch.isLetterOrDigit() || ch == '_' || ch == '-') ch else '_') }
        var s = sb.toString()
        if (s.length > 56) s = s.take(28) + "_" + s.takeLast(20)
        return s
    }
}
