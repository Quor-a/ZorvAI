package com.ai.assistance.quro.core.tools

import android.content.Context
import android.util.Base64
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
    override val description = """小程序工作室：AI 直接创建/写入/读取/运行小程序工程（HTML + Page() 运行时 + native.* 原生桥）。

工程结构（存放在手机私有目录 filesDir/studio/miniapp/<name>/，与工具中心「小程序工作室」面板共享同一份文件）：
- app.json：全局配置（appId/version/name/pages 路由表/window 样式）
- pages/<page>/<page>.html：页面（完整 HTML，用 Page() 运行时组织状态，可调 native.*）
- components/<name>/<name>.js：可复用组件（可选）

原生能力（native.* 桥，由宿主注入；这是用本工具的唯一理由）：
- storage：setItem/getItem/removeItem/clear（跨启动持久化）
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
- run：返回可直接在对话框渲染的自包含 HTML（自动内联同目录的 .js/.css）——**create 之后必须 run，否则界面根本没交付**
- save：把一整段 HTML 直接存成标准工程（自动补 app.json + pages/index/index.html）。
  **GenUI 的 ```html 通道产出、或对话里生成好的单页应用，用这个固化成小程序。**
- wrap：把一段代码包装成可渲染 HTML（lang=js|python|css）。python 走 Brython，无需 Termux。
- clean：清空全部小程序工程
- manual：取回开发手册（参数 topic）：
  · 不传 / "studio" → 小程序工作室手册：工程结构 + Page() 运行时 + native.* 逐个函数 + 错误清单
  · 老 topic（"native" / "traps" / "errors" / "compare"）→ 返回「该能力已下线」说明
    （旧的 WXML/WXSS 原生 UI 引擎 genui_native_ui 已随旧 GenUI 画布删除，别再调它）

""" + MiniAppManual.STUDIO_BRIEF
    override val parametersJson = """{
        "type":"object",
        "properties":{
            "action":{"type":"string","description":"操作：create|write|read|list|delete|run|save|wrap|clean|manual"},
            "name":{"type":"string","description":"工程名（create/write/read/delete/run/save 时必填）"},
            "files":{"type":"array","description":"文件数组（仅 create 时需要），每项含 path 和 content","items":{"type":"object","properties":{"path":{"type":"string"},"content":{"type":"string"}}}},
            "path":{"type":"string","description":"文件路径，相对工程根（write/read/delete 单个文件时需要）"},
            "content":{"type":"string","description":"文件内容（write 时需要）"},
            "entry":{"type":"string","description":"入口页面路径（run 时需要，默认 pages/index/index.html）"},
            "html":{"type":"string","description":"完整 HTML 文本（save 时必填）"},
            "code":{"type":"string","description":"代码文本（wrap 时必填）"},
            "lang":{"type":"string","description":"wrap 的代码语言：js|python|css（省略时从 file 后缀推断）"},
            "file":{"type":"string","description":"文件名（wrap 时用于推断 lang，如 app.py）"},
            "topic":{"type":"string","description":"manual 的主题：studio（工作室手册，默认）；老 topic native/traps/errors/compare 已下线，会返回下线说明"}
        },
        "required":["action"]
    }"""

    companion object {
        /**
         * 统一后的小程序根目录：filesDir/miniapp。
         *
         * 历史上有两个并存的小程序体系：
         *  · 工具中心「小程序」   → filesDir/workbench（单入口多文件，无路由/无原生桥）
         *  · 工具中心「小程序工作室」→ filesDir/studio/miniapp（app.json 路由 + native.* 桥）
         * 现已合体为唯一的「小程序」，目录也归一到 filesDir/miniapp，
         * 两个旧目录在首次访问时一次性搬迁过来（幂等），旧工程不会丢。
         */
        private const val ROOT = "miniapp"
        private const val LEGACY_STUDIO = "studio/miniapp"
        private const val LEGACY_WORKBENCH = "workbench"
        private const val KEY_MIGRATED = "miniapp_unified_v1"

        fun getRoot(context: Context): File {
            val dir = File(context.filesDir, ROOT)
            if (!dir.exists()) dir.mkdirs()
            migrateLegacyOnce(context, dir)
            return dir
        }

        /** 把 workbench/ 与 studio/miniapp/ 下的旧工程搬进统一目录；幂等，搬过的不再动。 */
        private fun migrateLegacyOnce(context: Context, root: File) {
            val prefs = context.getSharedPreferences("quro_miniapp", Context.MODE_PRIVATE)
            if (prefs.getBoolean(KEY_MIGRATED, false)) return
            prefs.edit().putBoolean(KEY_MIGRATED, true).apply()
            listOf(LEGACY_STUDIO, LEGACY_WORKBENCH).forEach { legacy ->
                val src = File(context.filesDir, legacy)
                if (!src.exists() || !src.isDirectory) return@forEach
                src.listFiles()?.filter { it.isDirectory }?.forEach { proj ->
                    val dst = File(root, sanitize(proj.name))
                    if (dst.exists()) return@forEach
                    runCatching { proj.copyRecursively(dst, overwrite = false) }
                }
            }
        }

        private fun sanitize(name: String): String =
            name.replace(Regex("[^A-Za-z0-9_.\\-]"), "_").replace("..", "_")

        fun getProjectDir(context: Context, name: String): File =
            File(getRoot(context), sanitize(name))
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
            // save：把一整段 HTML 直接存成一个小程序工程（GenUI html 通道「保存为小程序」走这里，
            // 也让 AI 能把对话里生成好的单页应用固化下来，不用手工拆成 app.json + pages）。
            "save" -> saveHtml(context, json)
            // wrap：把一段 js / python / css 包装成可渲染的 HTML 页面（原「小程序」=workbench 的能力）。
            "wrap" -> wrapCode(context, json)
            "clean" -> cleanAll(context)
            // manual：把完整手册（函数级细节 + 可交互范式 + 错误清单）返给 AI。
            // 手册正文在 MiniAppManual 里（唯一真相源，GenUI 侧同源引用），此处只做分发。
            "manual" -> MiniAppManual.section(json.optString("topic", ""))
            else -> "未知操作：$action。支持：create/write/read/list/delete/run/save/wrap/clean/manual"
        }
    }

    /**
     * 把一整段 HTML 存成小程序工程。
     *
     * 生成的工程是标准形态（app.json + pages/index/index.html），所以存完就能用
     * `miniapp(action="run")` 预览、也能在工具中心「小程序」面板里看到。
     */
    private fun saveHtml(context: Context, json: JSONObject): String {
        val name = json.optString("name", "").ifBlank { return "缺少 name 参数" }
        val html = json.optString("html", "").ifBlank { return "缺少 html 参数" }
        val dir = getProjectDir(context, name)
        dir.mkdirs()
        val pageDir = File(dir, "pages/index")
        pageDir.mkdirs()
        return try {
            File(pageDir, "index.html").writeText(html, StandardCharsets.UTF_8)
            val appJsonFile = File(dir, "app.json")
            if (!appJsonFile.exists()) {
                appJsonFile.writeText(
                    JSONObject().apply {
                        put("appId", "com.ai.assistance.quro.miniapp.${sanitize(name)}")
                        put("version", "1.0.0")
                        put("name", name)
                        put("pages", JSONArray().apply { put("pages/index/index") })
                        put("window", JSONObject().apply { put("navigationBarTitle", name) })
                    }.toString(2),
                    StandardCharsets.UTF_8
                )
            }
            "✅ 已保存为小程序「$name」（pages/index/index.html，${html.length} 字符）\n" +
                "用 miniapp(action=\"run\", name=\"$name\") 预览；也可在工具中心「小程序」里打开。"
        } catch (e: Exception) {
            "❌ 保存失败：${e.message}"
        }
    }

    /**
     * 把 js / python / css 包装成可渲染的 HTML 页面（原 workbench 的能力，合体后归到本工具）。
     * - python 走 Brython（CDN 加载，无需 Termux）
     * - js 注入 console 面板
     * - css 套一个示例页面预览
     */
    private fun wrapCode(context: Context, json: JSONObject): String {
        val lang = json.optString("lang", "").lowercase().ifBlank {
            json.optString("file", "").substringAfterLast('.', "").lowercase()
        }
        val code = json.optString("code", "").ifBlank { return "缺少 code 参数" }
        return when (lang) {
            "js", "javascript" -> wrapJsAsHtml(code)
            "py", "python" -> wrapPythonAsHtml(code)
            "css" -> wrapCssAsHtml(code)
            else -> "不支持的 lang：$lang（支持 js / python / css）"
        }
    }

    private fun wrapJsAsHtml(jsCode: String): String {
        return """<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <style>
        body { margin: 0; padding: 16px; font-family: 'Fira Code', monospace; background: #1e1e1e; color: #d4d4d4; }
        #output { margin-top: 16px; padding: 12px; background: #2d2d2d; border-radius: 8px; white-space: pre-wrap; }
        .log { color: #d4d4d4; }
        .error { color: #f44747; }
        .warn { color: #cca700; }
        .info { color: #569cd6; }
    </style>
</head>
<body>
    <div id="output"></div>
    <script>
        const output = document.getElementById('output');
        const originalLog = console.log;
        const originalError = console.error;
        const originalWarn = console.warn;

        console.log = (...args) => {
            const div = document.createElement('div');
            div.className = 'log';
            div.textContent = args.map(a => typeof a === 'object' ? JSON.stringify(a) : String(a)).join(' ');
            output.appendChild(div);
            originalLog.apply(console, args);
        };
        console.error = (...args) => {
            const div = document.createElement('div');
            div.className = 'error';
            div.textContent = args.join(' ');
            output.appendChild(div);
            originalError.apply(console, args);
        };
        console.warn = (...args) => {
            const div = document.createElement('div');
            div.className = 'warn';
            div.textContent = args.join(' ');
            output.appendChild(div);
            originalWarn.apply(console, args);
        };

        try {
            $jsCode
        } catch(e) {
            console.error('Error: ' + e.message);
        }
    </script>
</body>
</html>"""
    }

    private fun wrapCssAsHtml(cssCode: String): String {
        return """<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <style>
$cssCode
    </style>
</head>
<body>
    <h1>CSS 预览</h1>
    <p>这是一个示例段落，用于预览CSS效果。</p>
    <button>示例按钮</button>
    <div class="card">
        <h2>卡片标题</h2>
        <p>卡片内容</p>
    </div>
    <ul>
        <li>列表项 1</li>
        <li>列表项 2</li>
        <li>列表项 3</li>
    </ul>
    <input type="text" placeholder="输入框">
    <a href="#">链接</a>
</body>
</html>"""
    }

    private fun wrapPythonAsHtml(pythonCode: String): String {
        val escaped = pythonCode
            .replace("\\", "\\\\")
            .replace("</script", "<\\/script")
            .replace("`", "\\`")
            .replace("\$", "\\$")
        return """<!DOCTYPE html>
<html>
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<style>
* { margin: 0; padding: 0; box-sizing: border-box; }
body { background: #1e1e1e; color: #d4d4d4; font-family: 'Fira Code', Consolas, monospace; font-size: 13px; }
#header { background: #252526; padding: 8px 12px; border-bottom: 1px solid #3c3c3c; display: flex; align-items: center; gap: 8px; }
#header .badge { background: #3b82f6; color: white; padding: 2px 8px; border-radius: 4px; font-size: 11px; font-weight: bold; }
#output { padding: 12px; white-space: pre-wrap; word-break: break-word; line-height: 1.5; }
.stdout { color: #d4d4d4; }
.stderr { color: #f44747; }
</style>
</head>
<body>
<div id="header"><span class="badge">Python</span><span>Workbench · Brython</span></div>
<div id="output"></div>
<script src="https://cdnjs.cloudflare.com/ajax/libs/brython/3.13.1/brython.min.js"></script>
<script src="https://cdnjs.cloudflare.com/ajax/libs/brython/3.13.1/brython_stdlib.js"></script>
<script id="python-code" type="text/python">$escaped</script>
<script>
var _out = document.getElementById('output');
function _print() {
    var args = Array.prototype.slice.call(arguments);
    var line = args.map(function(a) {
        if (a === undefined) return 'undefined';
        if (a === null) return 'None';
        if (typeof a === 'object') {
            try { return JSON.stringify(a); } catch(e) { return String(a); }
        }
        return String(a);
    }).join(' ');
    var div = document.createElement('div');
    div.className = 'stdout';
    div.textContent = line;
    _out.appendChild(div);
}
try {
    brython({stdout: _print, stderr: function(s) {
        var div = document.createElement('div');
        div.className = 'stderr';
        div.textContent = 'Error: ' + s;
        _out.appendChild(div);
    }});
} catch(e) {
    var div = document.createElement('div');
    div.className = 'stderr';
    div.textContent = 'Brython 加载失败: ' + e.message;
    _out.appendChild(div);
}
</script>
</body>
</html>"""
    }

    /** 清空全部小程序工程。 */
    private fun cleanAll(context: Context): String {
        val root = getRoot(context)
        val names = root.listFiles()?.filter { it.isDirectory }?.map { it.name } ?: emptyList()
        if (names.isEmpty()) return "小程序目录为空，无需清理"
        var ok = 0
        names.forEach { if (runCatching { File(root, it).deleteRecursively() }.getOrDefault(false)) ok++ }
        return "✅ 已清空 $ok/${names.size} 个小程序工程"
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
        // 清理相对路径里的 .. / 绝对段，确保只写在工程目录内，避免越界写入
        val safePath = path.split('/').filter { it.isNotBlank() && it != "." && it != ".." }.joinToString("/")
        return runCatching {
            val f = File(projectDir, safePath); f.parentFile?.mkdirs(); f.writeText(content, StandardCharsets.UTF_8)
            "✅ 已写入 $safePath (${content.length} 字符) 到工程「$name」"
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
        // 内联同目录图片为 data URI：对话框预览（无 app:// 拦截）才能和面板内渲染一致
        val imgRegex = Regex("""<img\s+[^>]*src=["']([^"']+\.(?:png|jpe?g|webp|gif|svg))["']""", RegexOption.IGNORE_CASE)
        result = imgRegex.replace(result) { m ->
            val f = File(dir, m.groupValues[1])
            if (f.exists()) {
                val mime = when (f.extension.lowercase()) {
                    "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"
                    "webp" -> "image/webp"; "gif" -> "image/gif"; "svg" -> "image/svg+xml"
                    else -> "application/octet-stream"
                }
                val b64 = Base64.encodeToString(f.readBytes(), Base64.NO_WRAP)
                m.value.replace(m.groupValues[1], "data:$mime;base64,$b64")
            } else m.value
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
<button data-action="onHash">算 SHA256（native.crypto）</button>
<button data-action="onDbWrite">写DB（native.db）</button>
<button data-action="onDbRead">读DB（native.db）</button>
<script>
Page({
  data: { title: "Hello MiniApp", tip: "这是用 MiniAppFramework 运行时渲染的小程序。" },
  onTap: function () {
    this.setData({ title: "你点了一下！" });
    native.kotlin.toast({ text: "来自小程序的问候" });
  },
  onHash: function () {
    native.crypto.sha256("Hello MiniApp").then(function (h) {
      native.kotlin.toast({ text: "sha256: " + h.slice(0, 16) + "…" });
    }).catch(function (e) {
      native.kotlin.toast({ text: "crypto 失败: " + (e && e.message ? e.message : e) });
    });
  },
  onDbWrite: function () {
    native.db.insert('app_data', { key: 'demo_' + Date.now(), value: 'hello' }).then(function (r) {
      native.kotlin.toast({ text: "db.insertId=" + (r && r.insertId) });
    }).catch(function (e) {
      native.kotlin.toast({ text: "db 写失败: " + (e && e.message ? e.message : e) });
    });
  },
  onDbRead: function () {
    native.db.query("SELECT * FROM app_data ORDER BY id DESC LIMIT 5").then(function (rows) {
      native.kotlin.toast({ text: "db 最新 " + (rows ? rows.length : 0) + " 条" });
    }).catch(function (e) {
      native.kotlin.toast({ text: "db 读失败: " + (e && e.message ? e.message : e) });
    });
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
