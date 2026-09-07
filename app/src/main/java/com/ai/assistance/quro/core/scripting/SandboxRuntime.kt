package com.ai.assistance.quro.core.scripting

import android.content.Context
import com.ai.assistance.quro.plugin.PluginSetDataCallback
import com.ai.assistance.quro.plugin.QuickJsEngine
import java.io.File

/**
 * SandboxPackage 运行时（Kotlin 侧整合层）。
 *
 * 组装：assets/scripting/prelude.js（console + CommonJS + Tools.* + Lodash-lite + dataUtils）
 *  + 用户脚本（.ts 先经 [TsTranspiler] 转译）
 *  + 结果回传包装（module.exports / hostSetData("__result")）
 *  → QuickJS 脚本沙箱（64MB 内存上限 + 超时中断 + allowEval 供 CommonJS require 用）。
 *
 * 宿主 API（fs/net/system/calc）经 [HostApiDispatcher] 网关分发，全部限制在 [sandboxRoot] 内。
 *
 * 三种入口：
 *  - [runCode]   ：跑一段内联 JS/TS（code_runner / run_code 的 js·ts 分支）
 *  - [runFile]   ：跑工作区内某个 .js/.ts 文件（code_runner 的 path 模式）
 *  - [callModuleFunction]：加载 CommonJS 模块并调用其导出函数（ToolPkg 脚本包 → AI 工具）
 */
class SandboxRuntime(
    private val appContext: Context,
    private val sandboxRoot: File,
) {
    private val dispatcher = HostApiDispatcher(appContext.applicationContext, sandboxRoot)
    private val preludeJs: String get() = Companion.prelude(appContext)

    /** 单次执行结果：console 输出行 + 返回值 JSON + 错误串。 */
    data class Result(
        val logs: List<String>,
        /** 脚本回传的 __result（JSON 串）；null = 未回传。 */
        val resultJson: String?,
        /** 执行错误（QuickJS 异常）；null = 成功。 */
        val error: String?,
    ) {
        /** 拼成给 AI / 对话框看的文本。 */
        fun format(): String = buildString {
            if (error != null) append("⚠️ 执行错误：").append(error).append('\n')
            logs.forEach { append(it).append('\n') }
            val r = resultJson?.takeIf { it != "undefined" && it != "null" }
            if (r != null) append("➡️ 返回值：").append(prettyJson(r)).append('\n')
            if (isEmpty()) append("(无输出)")
        }.trim()

        private fun prettyJson(raw: String): String = try {
            val t = org.json.JSONTokener(raw).nextValue()
            when (t) {
                is org.json.JSONObject -> t.toString(2)
                is org.json.JSONArray -> t.toString(2)
                else -> t.toString()
            }
        } catch (e: Exception) { raw }
    }

    /* ===================== 入口 ===================== */

    /** 跑内联代码。lang：js（默认）/ ts / typescript（自动转译）。 */
    fun runCode(code: String, lang: String = "js", timeoutMs: Int = 15000): Result {
        val js = if (lang.equals("ts", true) || lang.equals("typescript", true)) TsTranspiler.transpile(code) else code
        return execute(preludeJs + wrapTopLevel(js, "", ""), timeoutMs)
    }

    /** 跑工作区内相对路径的脚本文件（.js 原样 / .ts 自动转译）。 */
    fun runFile(relPath: String, timeoutMs: Int = 15000): Result {
        val f = resolveInRoot(relPath) ?: return Result(emptyList(), null, "路径越界或非法：$relPath")
        if (!f.isFile) return Result(emptyList(), null, "文件不存在：$relPath")
        val src = runCatching { f.readText() }.getOrElse { return Result(emptyList(), null, "读取失败：${it.message}") }
        val js = if (f.extension.equals("ts", true)) TsTranspiler.transpile(src) else src
        val dir = relPath.replace('\\', '/').substringBeforeLast('/', "")
        return execute(preludeJs + wrapTopLevel(js, dir, relPath.replace('\\', '/')), timeoutMs)
    }

    /**
     * 加载 CommonJS 模块（工作区相对路径，.ts 自动转译）并调用其导出函数。
     * fnName 为空时直接返回 module.exports。argsJson 作为单个参数（工具 params 对象）传入。
     */
    fun callModuleFunction(
        modulePath: String,
        fnName: String,
        argsJson: String,
        timeoutMs: Int = 15000,
    ): Result {
        val f = resolveInRoot(modulePath) ?: return Result(emptyList(), null, "路径越界或非法：$modulePath")
        if (!f.isFile) return Result(emptyList(), null, "模块文件不存在：$modulePath")
        val src = runCatching { f.readText() }.getOrElse { return Result(emptyList(), null, "读取失败：${it.message}") }
        val js = if (f.extension.equals("ts", true)) TsTranspiler.transpile(src) else src
        val normPath = modulePath.replace('\\', '/')
        val dir = normPath.substringBeforeLast('/', "")
        return execute(preludeJs + wrapModuleCall(js, dir, normPath, fnName, argsJson), timeoutMs)
    }

    /* ===================== 包装器 ===================== */

    /** 顶层脚本：包进 IIFE（支持顶层 return），module.exports 非空则作为返回值回传。 */
    private fun wrapTopLevel(code: String, dir: String, file: String): String {
        val sb = StringBuilder()
        sb.append("\n;__dirname = ").append(q(dir)).append(";__filename = ").append(q(file)).append(";\n")
        sb.append("var __top = { exports: {} };\n")
        sb.append("(function(module, exports, require){\n")
        sb.append(code)
        sb.append("\n})(__top, __top.exports, require);\n")
        sb.append("var __r = (__top.exports && Object.keys(__top.exports).length) ? __top.exports : undefined;\n")
        sb.append("try { hostSetData(\"__result\", __r === undefined ? null : __r); } catch (e) { hostSetData(\"__result\", String(__r)); }\n")
        return sb.toString()
    }

    /** 模块调用：CommonJS 包装执行后，调 exports[fnName](args)（fnName 为空则取整个 exports）。 */
    private fun wrapModuleCall(code: String, dir: String, file: String, fnName: String, argsJson: String): String {
        val sb = StringBuilder()
        sb.append("\n;__dirname = ").append(q(dir)).append(";__filename = ").append(q(file)).append(";\n")
        sb.append("var __pkg = { exports: {} };\n")
        sb.append("(function(module, exports, require){\n")
        sb.append(code)
        sb.append("\n})(__pkg, __pkg.exports, require);\n")
        if (fnName.isBlank()) {
            sb.append("var __r = __pkg.exports;\n")
        } else {
            sb.append("var __fn = __pkg.exports[").append(q(fnName)).append("];\n")
            sb.append("if (typeof __fn !== \"function\") throw new Error(\"模块未导出函数 '").append(fnName)
                .append("'（现有导出：\" + Object.keys(__pkg.exports).join(\", \") + \"）\");\n")
            sb.append("var __r = __fn(").append(if (argsJson.isBlank()) "{}" else argsJson).append(");\n")
        }
        sb.append("try { hostSetData(\"__result\", __r === undefined ? null : __r); } catch (e) { hostSetData(\"__result\", String(__r)); }\n")
        return sb.toString()
    }

    /* ===================== 执行 ===================== */

    private fun execute(js: String, timeoutMs: Int): Result {
        if (!QuickJsEngine.isAvailable()) {
            return Result(emptyList(), null, "QuickJS 不可用（libquroplugin.so 未编入此构建）")
        }
        val engine = QuickJsEngine()
        if (!engine.createScriptRuntime(64 * 1024 * 1024, timeoutMs)) {
            return Result(emptyList(), null, "QuickJS 脚本运行时初始化失败")
        }
        val logs = ArrayList<String>()
        var resultJson: String? = null
        val callback = object : PluginSetDataCallback {
            override fun onSetData(path: String, valueJson: String) {
                when (path) {
                    "__log" -> logs.add(unquote(valueJson))
                    "__result" -> resultJson = valueJson
                }
            }

            override fun onHostApi(api: String, paramsJson: String): String =
                runCatching { dispatcher.dispatch(api, paramsJson) }
                    .getOrElse { """{"error":"${it.message}"}""" }
        }
        val err = engine.evalPlugin(js, callback, timeoutMs)
        engine.destroy()
        return Result(logs, resultJson, err)
    }

    /* ===================== 工具 ===================== */

    private fun resolveInRoot(rel: String): File? {
        val cleaned = rel.trim().trimStart('/').replace('\\', '/')
        if (cleaned.isEmpty()) return null
        val f = File(sandboxRoot, cleaned)
        val canonical = runCatching { f.canonicalFile }.getOrElse { return null }
        val root = runCatching { sandboxRoot.canonicalFile }.getOrElse { return null }
        return if (canonical.path.startsWith(root.path + File.separator)) canonical else null
    }

    private fun q(s: String): String = org.json.JSONObject.quote(s)

    /** hostSetData 回传的是 JSON 编码串（字符串会包一层双引号），剥壳还原。 */
    private fun unquote(json: String): String {
        if (json.length >= 2 && json.startsWith("\"") && json.endsWith("\"")) {
            return json.substring(1, json.length - 1)
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
                .replace("\\n", "\n")
                .replace("\\t", "\t")
        }
        return json
    }

    companion object {
        @Volatile private var cachedPrelude: String? = null

        /** prelude.js（运行时垫片）带进程级缓存。 */
        fun prelude(ctx: Context): String =
            cachedPrelude ?: runCatching {
                ctx.assets.open("scripting/prelude.js").bufferedReader().use { it.readText() }
            }.getOrElse { "" }.also { cachedPrelude = it }
    }
}
