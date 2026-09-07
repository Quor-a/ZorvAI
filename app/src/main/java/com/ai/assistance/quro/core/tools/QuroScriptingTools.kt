package com.ai.assistance.quro.core.tools

import android.content.Context
import com.ai.assistance.quro.core.scripting.SandboxRuntime
import org.json.JSONObject
import java.io.File

/**
 * SandboxPackage 工具组（原创）：
 *  - [CodeRunnerTool]      code_runner    ：跑工作区 .js/.ts 文件或内联 JS/TS 代码片段，
 *                                          带完整宿主 API（Tools.Files/Net/System/calc + _ + dataUtils + require）
 *  - [ToolPkgListTool]     toolpkg_list   ：扫描工作区 packages 目录下的 manifest.json，列出脚本包提供的 AI 工具
 *  - [ToolPkgCallTool]     toolpkg_call   ：调用某个脚本包工具（执行包入口模块的导出函数）
 *  - [ProjectCreateTool]   project_create ：从内置模板（Web/Android/Flutter/Node/TS/Python/Java/Go）创建项目到工作区
 *
 * ToolPkg 约定（脚本包 → AI 工具）：
 *   packages/<pkg>/manifest.json：
 *   {
 *     "name": "weather", "version": "1.0.0", "description": "天气工具包",
 *     "entry": "index.js",                     // 默认 index.js（.ts 也可）
 *     "tools": [
 *       { "name": "get_weather", "description": "查询城市天气",
 *         "parameters": { ...JSON Schema... },  // 可选
 *         "function": "getWeather" }            // 入口模块的导出函数名，缺省=工具名
 *     ]
 *   }
 *   工具函数签名：function(params: object) → 任意可 JSON 序列化结果。
 *   包内代码可用 require("./xxx") 相对引用、Tools.* 宿主 API、_ / dataUtils。
 */

/* ===================== ToolPkg 扫描 ===================== */

/** 工作区 packages/ 下解析出的脚本包工具。 */
data class ToolPkgEntry(
    val pkgDir: File,
    val pkgName: String,
    val version: String,
    val pkgDescription: String,
    val entry: String,
    val toolName: String,
    val toolDescription: String,
    val function: String,
)

object ToolPkgScanner {

    fun scan(root: File): List<ToolPkgEntry> {
        val packagesDir = File(root, "packages")
        if (!packagesDir.isDirectory) return emptyList()
        val out = ArrayList<ToolPkgEntry>()
        packagesDir.listFiles { f -> f.isDirectory }?.sortedBy { it.name }?.forEach { dir ->
            val manifest = File(dir, "manifest.json")
            if (!manifest.isFile) return@forEach
            val m = runCatching { JSONObject(manifest.readText()) }.getOrElse { return@forEach }
            val pkgName = m.optString("name", dir.name)
            val entry = m.optString("entry", "index.js")
            val tools = m.optJSONArray("tools") ?: return@forEach
            for (i in 0 until tools.length()) {
                val t = tools.optJSONObject(i) ?: continue
                val name = t.optString("name").trim()
                if (name.isEmpty()) continue
                out.add(
                    ToolPkgEntry(
                        pkgDir = dir,
                        pkgName = pkgName,
                        version = m.optString("version", "1.0.0"),
                        pkgDescription = m.optString("description", ""),
                        entry = entry,
                        toolName = name,
                        toolDescription = t.optString("description", ""),
                        function = t.optString("function", name),
                    )
                )
            }
        }
        return out
    }

    fun manifestParamsJson(e: ToolPkgEntry): String {
        val manifest = File(e.pkgDir, "manifest.json")
        val m = runCatching { JSONObject(manifest.readText()) }.getOrNull() ?: return "{}"
        val tools = m.optJSONArray("tools") ?: return "{}"
        for (i in 0 until tools.length()) {
            val t = tools.optJSONObject(i) ?: continue
            if (t.optString("name") == e.toolName) {
                val p = t.opt("parameters")
                return when (p) {
                    is JSONObject -> p.toString()
                    is String -> p
                    else -> "{}"
                }
            }
        }
        return "{}"
    }
}

/* ===================== code_runner ===================== */

class CodeRunnerTool : QuroTool {
    override val name = "code_runner"
    override val description =
        "▶️ 完整 JS/TS 脚本运行器（SandboxPackage）：在 QuickJS 沙箱里运行「工作区脚本文件」或「内联代码片段」，" +
            "带完整宿主 API 与 CommonJS 模块系统（与 run_code 的区别：run_code 只跑一次性片段、无宿主 API；" +
            "code_runner 带完整环境，适合多文件项目与真实脚本工程）。运行环境内置：\n" +
            "· Tools.Files：read/write/append/list/exists/remove/mkdir/stat（限制在工作区内）\n" +
            "· Tools.Net：fetch/get/post（http/https，15s 超时）\n" +
            "· Tools.System：info/env/clipboard/notify\n" +
            "· Tools.calc：eval（安全数学求值）\n" +
            "· Tools.Media：state()/screenshot()（MediaProjection 屏幕截图，需用户先在对话控制条长按「看懂屏幕」完成系统授权；" +
            "未授权时返回的 error 里带引导文案，转告用户即可）\n" +
            "· Tools.Git：init/status/add/commit/log/branchList/branchCreate/checkout（本地 Git 仓库，JGit 实现；" +
            "版本管理项目文件时用：先 init → 改文件 → add(path, [\".\"]) → commit(path, \"说明\")）\n" +
            "· _（Lodash-lite 80+ 函数）与 dataUtils（csv/stats/summarize），也可 require(\"lodash\")/require(\"datautils\")\n" +
            "· require(\"./相对路径\")：加载工作区内其他 .js/.ts 模块（.ts 自动转译）\n" +
            "· console.log 输出会收集回传；module.exports 非空则作为返回值回传\n" +
            "参数：{\"path\":\"工作区内相对路径（二选一）\",\"code\":\"内联代码（二选一）\",\"lang\":\"js|ts（内联代码语言，默认 js）\"}。"
    override val parametersJson = """{
        "type":"object",
        "properties":{
            "path":{"type":"string","description":"工作区内要运行的脚本文件相对路径（.js/.ts；.ts 自动转译），如 scripts/main.ts"},
            "code":{"type":"string","description":"内联要运行的 JS/TS 代码（与 path 二选一；适合短片段与带 require/Tools.* 的完整逻辑）"},
            "lang":{"type":"string","description":"内联代码语言：js（默认）| ts|typescript（自动转译）"}
        }
    }"""

    override fun run(context: Context, arguments: String): String {
        val obj = runCatching { JSONObject(arguments) }.getOrElse { return "参数不是合法 JSON：$arguments" }
        val path = obj.optString("path", "").trim()
        val code = obj.optString("code", "").trim()
        if (path.isEmpty() && code.isEmpty()) return "缺少参数：path（工作区脚本文件）与 code（内联代码）至少给一个。"
        val runtime = SandboxRuntime(context, workspaceRoot(context))
        val result = if (path.isNotEmpty()) {
            if (code.isNotEmpty()) return "path 与 code 只能二选一。"
            runtime.runFile(path)
        } else {
            runtime.runCode(code, obj.optString("lang", "js"))
        }
        return result.format()
    }
}

/* ===================== ToolPkg：脚本包 → AI 工具 ===================== */

class ToolPkgListTool : QuroTool {
    override val name = "toolpkg_list"
    override val description =
        "📦 列出工作区 packages/ 目录下所有「脚本包（ToolPkg）」提供的 AI 工具。" +
            "脚本包 = 工作区 packages/<包名>/ 目录（manifest.json + 入口 .js/.ts），" +
            "包内代码可用 Tools.* 宿主 API、require() 相对引用、_/dataUtils——写 .js 包即可扩展 AI 能力。" +
            "参数：{}（无参数，列出全部）。"
    override val parametersJson = """{"type":"object","properties":{}}"""

    override fun run(context: Context, arguments: String): String {
        val entries = ToolPkgScanner.scan(workspaceRoot(context))
        if (entries.isEmpty()) {
            return "工作区暂无脚本包。创建方法：packages/<包名>/manifest.json + 入口模块（index.js），" +
                "manifest 格式：{\"name\":\"...\",\"description\":\"...\",\"entry\":\"index.js\",\"tools\":[{\"name\":\"工具名\",\"description\":\"...\",\"function\":\"导出函数名\"}]}"
        }
        return buildString {
            appendLine("📦 工作区脚本包工具（${entries.size} 个）：")
            entries.forEach {
                appendLine("· ${it.pkgName}.${it.toolName}（v${it.version}）—— ${it.toolDescription.ifBlank { it.pkgDescription }}")
            }
            appendLine("调用方式：toolpkg_call {\"package\":\"包名\",\"tool\":\"工具名\",\"params\":{...}}")
        }.trim()
    }
}

class ToolPkgCallTool : QuroTool {
    override val name = "toolpkg_call"
    override val description =
        "📦 调用工作区脚本包（ToolPkg）里的某个工具：加载包入口模块并执行其导出函数。" +
            "先用 toolpkg_list 查看可用工具。参数：{\"package\":\"包名\",\"tool\":\"工具名\",\"params\":{...工具参数对象...}}。"
    override val parametersJson = """{
        "type":"object",
        "properties":{
            "package":{"type":"string","description":"脚本包名（packages/ 下的目录名或 manifest name）"},
            "tool":{"type":"string","description":"要调用的工具名（manifest tools[].name）"},
            "params":{"type":"object","description":"传给工具函数的参数对象（函数签名为 function(params)）"}
        },
        "required":["package","tool"]
    }"""

    override fun run(context: Context, arguments: String): String {
        val obj = runCatching { JSONObject(arguments) }.getOrElse { return "参数不是合法 JSON：$arguments" }
        val pkg = obj.optString("package", "").trim()
        val tool = obj.optString("tool", "").trim()
        if (pkg.isEmpty() || tool.isEmpty()) return "缺少 package / tool 参数。"
        val root = workspaceRoot(context)
        val entry = ToolPkgScanner.scan(root).firstOrNull { it.pkgName == pkg && it.toolName == tool }
            ?: return "未找到脚本包工具：$pkg.$tool（用 toolpkg_list 查看可用工具）"
        val entryFile = File(entry.pkgDir, entry.entry)
        val relEntry = entryFile.relativeToOrSelf(root).path.replace('\\', '/')
        val params = obj.opt("params")?.toString() ?: "{}"
        val result = SandboxRuntime(context, root)
            .callModuleFunction(relEntry, entry.function, params)
        return result.format()
    }
}

/* ===================== 项目模板 ===================== */

class ProjectCreateTool : QuroTool {
    override val name = "project_create"
    override val description =
        "🧰 从内置模板在工作区创建项目骨架（复制到 工作区/<项目名>/，已存在同名目录则报错）。" +
            "模板：web（网页：HTML/CSS/JS）、android（Android 工程）、flutter（Flutter 应用）、" +
            "node（Node.js 脚本项目）、typescript（TS 项目）、python（Python 项目）、java（Java 项目）、go（Go 项目）。" +
            "参数：{\"template\":\"web|android|flutter|node|typescript|python|java|go\",\"name\":\"项目名（英文/数字/横线）\"}。"
    override val parametersJson = """{
        "type":"object",
        "properties":{
            "template":{"type":"string","description":"模板 id：web|android|flutter|node|typescript|python|java|go"},
            "name":{"type":"string","description":"项目目录名（英文/数字/横线/下划线）"}
        },
        "required":["template","name"]
    }"""

    override fun run(context: Context, arguments: String): String {
        val obj = runCatching { JSONObject(arguments) }.getOrElse { return "参数不是合法 JSON：$arguments" }
        val template = obj.optString("template", "").trim().lowercase()
        val name = obj.optString("name", "").trim()
        if (template.isEmpty() || name.isEmpty()) return "缺少 template / name 参数。"
        if (!Regex("^[A-Za-z0-9][A-Za-z0-9._-]*$").matches(name)) return "项目名不合法：$name（仅英文/数字/横线/下划线/点）"
        val known = listOf("web", "android", "flutter", "node", "typescript", "python", "java", "go")
        if (template !in known) return "未知模板：$template（可选：${known.joinToString(" / ")}）"
        val dst = File(workspaceRoot(context), name)
        if (dst.exists()) return "⚠️ 目录已存在：${dst.absolutePath}（换个项目名，或先删除旧目录）"

        // 模板资产递归复制（assets/templates/<template>/**）
        val files = ArrayList<String>()
        runCatching { copyAssetDir(context, "templates/$template", dst, files) }
            .getOrElse { return "⚠️ 模板复制失败：${it.message}" }
        if (files.isEmpty()) return "⚠️ 模板为空或不存在：$template"

        // README 追加运行说明
        val readme = File(dst, "README.md")
        val runHint = "\n\n---\n> 由 ZorvAI 项目模板创建（模板：$template）。JS/TS 文件可用 code_runner 运行（带 Tools.* 宿主 API）。\n"
        runCatching { readme.appendText(runHint) }

        return buildString {
            append("✅ 项目已创建：").append(dst.absolutePath).append('\n')
            append("共 ").append(files.size).append(" 个文件：\n")
            files.take(20).forEach { append("  · ").append(it).append('\n') }
            if (files.size > 20) append("  …等共 ").append(files.size).append(" 个\n")
            append("下一步：workspace_write 修改文件 / code_runner 运行脚本 / toolpkg_list 查看脚本包工具。")
        }.trim()
    }

    /** 递归复制 assets 目录到目标；assets.list 为空数组说明该路径是文件。 */
    private fun copyAssetDir(context: Context, src: String, dst: File, out: ArrayList<String>) {
        val children = context.assets.list(src) ?: emptyArray()
        if (children.isEmpty()) {
            dst.parentFile?.mkdirs()
            context.assets.open(src).use { input ->
                dst.outputStream().use { input.copyTo(it) }
            }
            out.add(dst.name)
            return
        }
        dst.mkdirs()
        children.forEach { child -> copyAssetDir(context, "$src/$child", File(dst, child), out) }
    }
}
