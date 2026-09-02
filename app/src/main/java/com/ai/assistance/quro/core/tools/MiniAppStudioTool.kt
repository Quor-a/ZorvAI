package com.ai.assistance.quro.core.tools

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * 小程序工作台工具：AI 可以直接创建 / 写入 / 读取 / 运行小程序工程。
 *
 * 工程存储在 filesDir/studio/miniapp/<name>/（app.json + pages 下各页面 .html + 组件），
 * 与工具中心「小程序工作室」面板共享同一份文件，AI 写入后 UI 立即可见、可运行。
 *
 * 用法：
 * - miniapp(action="create", name="todo", files=[{path:"app.json", content:"..."}, {path:"pages/index/index.html", content:"..."}])
 * - miniapp(action="write", name="todo", path="pages/index/index.html", content="...")
 * - miniapp(action="read", name="todo", path="pages/index/index.html")
 * - miniapp(action="list")
 * - miniapp(action="delete", name="todo")  // 或 delete(name="todo", path="pages/about/about.html")
 * - miniapp(action="run", name="todo", entry="pages/index/index.html")  // 返回可直接在对话框渲染的自包含 HTML
 */
class MiniAppStudioTool : QuroTool {
    override val name = "miniapp"
    override val description = """小程序工作台：AI 直接创建/写入/读取/运行小程序工程（完整移植自 MiniAppFramework）。

工程结构（存储在手机私有目录 filesDir/studio/miniapp/<name>/）：
- app.json：全局配置（appId/version/name/pages 路由表/window 样式）
- pages/<page>/<page>.html：页面（用 Page() 运行时 + native.* SDK 调用原生能力）
- components/<name>/<name>.js：可复用组件

原生能力（native.* SDK，由桥接注入）：
- storage：setItem/getItem/removeItem/clear
- ui：toast/setNavigationBarTitle
- device：getSystemInfo/vibrate
- network：request
- router：navigateTo/navigateBack
- kotlin：getAppInfo/copyText/getClipboard/shareText/openUrl/openApp/notify/speak
- aci：launchApp/launchComponent/canLaunch（关联启动第三方 App）
- crypto：md5/sha1/sha256/hmacSha256
- db：execSql/query/insert/update/delete（SQLite 结构化存储）
- location：getLocation

操作：
- create：创建工程，一次性写入多个文件（files 数组含 path+content）；不传 files 则写入一个示例小程序
- write：写入单个文件（path + content）
- read：读取文件内容（默认 app.json）
- list：列出所有小程序工程
- delete：删除整个工程，或删除某个文件（传 path）
- run：返回可直接在对话框渲染的自包含 HTML（自动内联同目录的 .js/.css）

示例：让 AI「用 miniapp 工具创建一个待办小程序，命名为 todo」，然后用 run 在对话框预览。"""
    override val parametersJson = """{
        "type":"object",
        "properties":{
            "action":{"type":"string","description":"操作：create|write|read|list|delete|run"},
            "name":{"type":"string","description":"工程名（create/write/read/delete/run 时必填）"},
            "files":{"type":"array","description":"文件数组（仅 create 时需要），每项含 path 和 content","items":{"type":"object","properties":{"path":{"type":"string"},"content":{"type":"string"}}}},
            "path":{"type":"string","description":"文件路径，相对工程根（write/read/delete 单个文件时需要）"},
            "content":{"type":"string","description":"文件内容（write 时需要）"},
            "entry":{"type":"string","description":"入口页面路径（run 时需要，默认 pages/index/index.html）"}
        },
        "required":["action"]
    }"""

    companion object {
        private const val STUDIO = "studio/miniapp"

        fun getRoot(context: Context): File {
            val dir = File(context.filesDir, STUDIO)
            if (!dir.exists()) dir.mkdirs()
            return dir
        }

        fun getProjectDir(context: Context, name: String): File = File(getRoot(context), name)
    }

    override fun run(context: Context, arguments: String): String {
        val json = JSONObject(arguments)
        val action = json.optString("action", "").lowercase()
        return when (action) {
            "create" -> createProject(context, json)
            "write" -> writeFile(context, json)
            "read" -> readFile(context, json)
            "list" -> listProjects(context)
            "delete" -> deleteProject(context, json)
            "run" -> runProject(context, json)
            else -> "未知操作：$action。支持：create/write/read/list/delete/run"
        }
    }

    private fun createProject(context: Context, json: JSONObject): String {
        val name = json.optString("name", "").ifBlank { return "缺少 name 参数" }
        val projectDir = getProjectDir(context, name)
        if (projectDir.exists() && projectDir.listFiles()?.isNotEmpty() == true) {
            return "工程已存在：$name（用 write 增量写入，或先 delete 再 create）"
        }
        projectDir.mkdirs()
        val files = json.optJSONArray("files")
        return if (files == null || files.length() == 0) {
            seedDemo(projectDir)
            "✅ 已创建示例小程序「$name」（app.json + pages/index + pages/about），用 miniapp(action=\"run\", name=\"$name\") 预览"
        } else {
            var created = 0
            val errors = mutableListOf<String>()
            for (i in 0 until files.length()) {
                val o = files.optJSONObject(i) ?: continue
                val p = o.optString("path", "").ifBlank { continue }
                val c = o.optString("content", "")
                runCatching {
                    val f = File(projectDir, p); f.parentFile?.mkdirs(); f.writeText(c, StandardCharsets.UTF_8)
                    created++
                }.onFailure { errors.add("写入 $p 失败: ${it.message}") }
            }
            // 若未提供 app.json，生成一个最小可用配置
            if (File(projectDir, "app.json").exists().not()) {
                File(projectDir, "app.json").writeText(
                    JSONObject().apply {
                        put("appId", "com.ai.assistance.quro.miniapp.$name")
                        put("version", "1.0.0")
                        put("name", name)
                        put("pages", JSONArray().apply { put("index") })
                        put("window", JSONObject().apply {
                            put("navigationBarTitle", name)
                            put("navigationBarColor", "#1A73E8")
                            put("backgroundColor", "#FFFFFF")
                        })
                    }.toString(2),
                    StandardCharsets.UTF_8,
                )
            }
            buildString {
                appendLine("✅ 小程序「$name」已创建，$created 个文件")
                appendLine("📁 路径：${projectDir.absolutePath}")
                if (errors.isNotEmpty()) appendLine("⚠ 错误：${errors.joinToString("; ")}")
                appendLine("用 miniapp(action=\"run\", name=\"$name\") 预览")
            }
        }
    }

    private fun writeFile(context: Context, json: JSONObject): String {
        val name = json.optString("name", "").ifBlank { return "缺少 name 参数" }
        val path = json.optString("path", "").ifBlank { return "缺少 path 参数" }
        val content = json.optString("content", null) ?: return "缺少 content 参数"
        val projectDir = getProjectDir(context, name)
        if (!projectDir.exists()) projectDir.mkdirs()
        return runCatching {
            val f = File(projectDir, path); f.parentFile?.mkdirs(); f.writeText(content, StandardCharsets.UTF_8)
            "✅ 已写入 $path (${content.length} 字符) 到工程「$name」"
        }.getOrElse { "❌ 写入失败：${it.message}" }
    }

    private fun readFile(context: Context, json: JSONObject): String {
        val name = json.optString("name", "").ifBlank { return "缺少 name 参数" }
        val path = json.optString("path", "app.json").ifBlank { "app.json" }
        val f = File(getProjectDir(context, name), path)
        if (!f.exists()) return "❌ 文件不存在：$name/$path"
        return runCatching {
            val c = f.readText(StandardCharsets.UTF_8)
            "📄 $name/$path (${c.length} 字符)\n\n```\n$c\n```"
        }.getOrElse { "❌ 读取失败：${it.message}" }
    }

    private fun listProjects(context: Context): String {
        val root = getRoot(context)
        val names = root.listFiles()?.filter { it.isDirectory }?.map { it.name } ?: emptyList()
        if (names.isEmpty()) return "小程序工作台为空（filesDir/studio/miniapp 下还没有工程）。用 miniapp(action=\"create\", name=\"demo\") 创建一个示例。"
        return buildString {
            appendLine("📱 小程序工程（${names.size}）：")
            names.forEach { appendLine("  • $it") }
            appendLine("\n用 miniapp(action=\"run\", name=\"<工程名>\") 预览")
        }
    }

    private fun deleteProject(context: Context, json: JSONObject): String {
        val name = json.optString("name", "").ifBlank { return "缺少 name 参数" }
        val path = json.optString("path", "")
        val projectDir = getProjectDir(context, name)
        return if (path.isNotBlank()) {
            val f = File(projectDir, path)
            if (f.exists() && f.delete()) "✅ 已删除 $name/$path" else "❌ 删除失败：$name/$path"
        } else {
            if (projectDir.exists() && projectDir.deleteRecursively()) "✅ 已删除工程「$name」" else "❌ 工程不存在：$name"
        }
    }

    private fun runProject(context: Context, json: JSONObject): String {
        val name = json.optString("name", "").ifBlank { return "缺少 name 参数" }
        val projectDir = getProjectDir(context, name)
        if (!projectDir.exists()) return "❌ 工程不存在：$name"
        val entry = json.optString("entry", "pages/index/index.html").ifBlank { "pages/index/index.html" }
        val entryFile = File(projectDir, entry)
        if (!entryFile.exists()) {
            // 回退到 app.json 的第一个 page
            val appJson = File(projectDir, "app.json")
            val first = if (appJson.exists()) {
                runCatching {
                    val pages = JSONObject(appJson.readText(StandardCharsets.UTF_8)).optJSONArray("pages")
                    pages?.optString(0) ?: "index"
                }.getOrDefault("index")
            } else "index"
            val candidate = File(projectDir, "$first.html")
            if (candidate.exists()) return renderEntry(projectDir, candidate)
            return "❌ 入口页面不存在：$entry"
        }
        return renderEntry(projectDir, entryFile)
    }

    /** 读取入口页并内联同目录的 .js/.css，返回自包含 HTML 供对话框 WebView 渲染（桥接由 MiniAppWebView 注入）。 */
    private fun renderEntry(projectDir: File, entryFile: File): String {
        val html = entryFile.readText(StandardCharsets.UTF_8)
        val dir = entryFile.parentFile ?: projectDir
        return inlineAssets(html, dir)
    }

    private fun inlineAssets(html: String, dir: File): String {
        var result = html
        val cssRegex = Regex("""<link\s+[^>]*href=["']([^"']+\.css)["']\s*[^>]*>""", RegexOption.IGNORE_CASE)
        result = cssRegex.replace(result) { m ->
            val f = File(dir, m.groupValues[1])
            if (f.exists()) "<style>\n${f.readText(StandardCharsets.UTF_8)}\n</style>" else m.value
        }
        val jsRegex = Regex("""<script\s+[^>]*src=["']([^"']+\.js)["']\s*[^>]*>\s*</script>""", RegexOption.IGNORE_CASE)
        result = jsRegex.replace(result) { m ->
            val f = File(dir, m.groupValues[1])
            if (f.exists()) "<script>\n${f.readText(StandardCharsets.UTF_8)}\n</script>" else m.value
        }
        return result
    }

    /** 写入一个最小可运行的示例小程序。 */
    private fun seedDemo(projectDir: File) {
        projectDir.mkdirs()
        File(projectDir, "app.json").writeText(
            JSONObject().apply {
                put("appId", "com.ai.assistance.quro.miniapp.demo")
                put("version", "1.0.0")
                put("name", projectDir.name)
                put("pages", JSONArray().apply { put("pages/index/index"); put("pages/about/about") })
                put("window", JSONObject().apply {
                    put("navigationBarTitle", "示例小程序")
                    put("navigationBarColor", "#1A73E8")
                    put("backgroundColor", "#FFFFFF")
                })
            }.toString(2),
            StandardCharsets.UTF_8,
        )
        File(projectDir, "pages/index").mkdirs()
        File(projectDir, "pages/index/index.html").writeText(
            """<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<style>
*{margin:0;padding:0;box-sizing:border-box}
body{font-family:system-ui,sans-serif;padding:24px;background:linear-gradient(135deg,#667eea,#764ba2);min-height:100vh;color:#fff}
h1{margin-bottom:12px}
button{margin-top:16px;background:#fff;color:#5a6fd6;border:none;padding:12px 20px;border-radius:8px;font-size:15px}
</style>
</head>
<body>
<h1 data-bind="title">Hello MiniApp</h1>
<p data-bind="tip">这是用 MiniAppFramework 运行时渲染的小程序。</p>
<button data-action="onTap">点我调用原生</button>
<script>
Page({
  data: { title: "Hello MiniApp", tip: "这是用 MiniAppFramework 运行时渲染的小程序。" },
  onTap: function () {
    this.setData({ title: "你点了一下！" });
    native.kotlin.toast({ text: "来自小程序的问候" });
  }
});
</script>
</body>
</html>""",
            StandardCharsets.UTF_8,
        )
        File(projectDir, "pages/about").mkdirs()
        File(projectDir, "pages/about/about.html").writeText(
            """<!DOCTYPE html>
<html lang="zh-CN">
<head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<style>body{font-family:system-ui,sans-serif;padding:24px}</style></head>
<body>
<h2>关于</h2>
<p>QuroAI 小程序工作台 · 完整移植自 MiniAppFramework。</p>
<button onclick="native.router.navigateBack()">返回</button>
</body>
</html>""",
            StandardCharsets.UTF_8,
        )
    }
}
