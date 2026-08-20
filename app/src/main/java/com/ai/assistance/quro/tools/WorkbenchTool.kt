package com.ai.assistance.quro.tools

import android.content.Context
import com.ai.assistance.quro.core.tools.QuroTool
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 后端工作区工具：AI可以用多种语言写多个文件，完成一个可以渲染的功能。
 *
 * 用法：
 * - workbench(action="create", name="myapp", files=[{path:"index.html", content:"..."}, {path:"app.js", content:"..."}])
 * - workbench(action="edit", file="index.html", content="新内容")
 * - workbench(action="run", entry="index.html")  // 运行/编译，返回可渲染的HTML
 * - workbench(action="list")  // 列出所有文件
 * - workbench(action="delete", file="app.js")
 * - workbench(action="get", file="index.html")  // 获取文件内容
 */
class WorkbenchTool : QuroTool {
    override val name = "workbench"
    override val description = """后端工作区工具：在手机端创建完整的多文件项目，AI可以用多种语言（HTML/JS/CSS/Python/C/Java等）编写多个文件，完成一个可交互的功能，渲染在对话框里。**Python 使用 Brython 引擎，无需 Termux 直接运行。**

支持的操作：
- create：创建工作区，一次性写入多个文件
- edit：编辑已有文件
- run：运行/编译工作区，返回可渲染的HTML页面（对话框自动预览）
- list：列出所有文件
- get：获取文件内容
- delete：删除文件
- clean：清空工作区

示例：
1. 创建计算器：
   workbench(action="create", name="calculator", files=[{path:"index.html", content:"<!DOCTYPE html>..."}])

2. 创建前后端分离项目：
   workbench(action="create", name="webapp", files=[
     {path:"index.html", content:"...HTML..."},
     {path:"style.css", content:"...CSS..."},
     {path:"app.js", content:"...JS..."}
   ])

3. 运行并渲染：
   workbench(action="run", entry="index.html")

4. 编辑后重新运行：
   workbench(action="edit", file="app.js", content="新代码")
   workbench(action="run", entry="index.html")

工作区文件存储在应用私有目录，会话期间持续存在。"""
    override val parametersJson = """{
        "type":"object",
        "properties":{
            "action":{"type":"string","description":"操作：create|edit|run|list|get|delete|clean"},
            "name":{"type":"string","description":"项目名称（仅create时需要）"},
            "files":{"type":"array","description":"文件数组（仅create时需要），每项含path和content","items":{"type":"object","properties":{"path":{"type":"string"},"content":{"type":"string"}}}},
            "file":{"type":"string","description":"文件路径（edit/get/delete时需要）"},
            "content":{"type":"string","description":"文件内容（edit时需要）"},
            "entry":{"type":"string","description":"入口文件路径（run时需要，默认index.html）"}
        },
        "required":["action"]
    }"""

    companion object {
        private const val WORKSPACE_DIR = "workbench"

        /** 获取工作区根目录 */
        fun getWorkspaceRoot(context: Context): File {
            val dir = File(context.filesDir, WORKSPACE_DIR)
            if (!dir.exists()) dir.mkdirs()
            return dir
        }

        /** 获取指定项目目录 */
        fun getProjectDir(context: Context, name: String): File {
            val dir = File(getWorkspaceRoot(context), name)
            if (!dir.exists()) dir.mkdirs()
            return dir
        }
    }

    override fun run(context: Context, arguments: String): String {
        val json = JSONObject(arguments)
        val action = json.optString("action", "").lowercase()

        return when (action) {
            "create" -> createProject(context, json)
            "edit" -> editFile(context, json)
            "run" -> runProject(context, json)
            "list" -> listFiles(context, json)
            "get" -> getFile(context, json)
            "delete" -> deleteFile(context, json)
            "clean" -> cleanWorkspace(context)
            else -> "未知操作：$action。支持：create/edit/run/list/get/delete/clean"
        }
    }

    /** 创建项目，写入多个文件 */
    private fun createProject(context: Context, json: JSONObject): String {
        val name = json.optString("name", "").ifBlank {
            return "缺少 name 参数"
        }
        val files = json.optJSONArray("files") ?: return "缺少 files 参数"

        val projectDir = getProjectDir(context, name)
        var created = 0
        val errors = mutableListOf<String>()

        for (i in 0 until files.length()) {
            val fileObj = files.optJSONObject(i) ?: continue
            val path = fileObj.optString("path", "").ifBlank { continue }
            val content = fileObj.optString("content", "")

            try {
                val file = File(projectDir, path)
                file.parentFile?.mkdirs()
                file.writeText(content)
                created++
            } catch (e: Exception) {
                errors.add("写入 $path 失败: ${e.message}")
            }
        }

        return buildString {
            appendLine("✅ 工作区「$name」已创建，$created 个文件")
            appendLine("📁 路径：${projectDir.absolutePath}")
            if (errors.isNotEmpty()) {
                appendLine("⚠ 错误：${errors.joinToString("; ")}")
            }
            appendLine("使用 workbench(action=\"run\", entry=\"index.html\") 运行项目")
        }
    }

    /** 编辑文件 */
    private fun editFile(context: Context, json: JSONObject): String {
        val file = json.optString("file", "").ifBlank { return "缺少 file 参数" }
        val content = json.optString("content", null) ?: return "缺少 content 参数"
        val projectName = json.optString("name", "")

        val projectDir = if (projectName.isNotBlank()) {
            getProjectDir(context, projectName)
        } else {
            findFile(context, file)?.parentFile ?: return "找不到文件：$file"
        }

        val targetFile = File(projectDir, file)
        return try {
            targetFile.parentFile?.mkdirs()
            targetFile.writeText(content)
            "✅ 已更新 $file (${content.length} 字符)"
        } catch (e: Exception) {
            "❌ 写入失败：${e.message}"
        }
    }

    /** 运行项目，返回可渲染的HTML */
    private fun runProject(context: Context, json: JSONObject): String {
        val entry = json.optString("entry", "index.html").ifBlank { "index.html" }
        val projectName = json.optString("name", "")

        // 尝试在指定项目或所有项目中查找入口文件
        val entryFile = if (projectName.isNotBlank()) {
            File(getProjectDir(context, projectName), entry)
        } else {
            findFile(context, entry) ?: File(getWorkspaceRoot(context), entry)
        }

        if (!entryFile.exists()) {
            return "❌ 入口文件不存在：$entry\n使用 workbench(action=\"list\") 查看可用文件"
        }

        val content = entryFile.readText()
        val ext = entryFile.extension.lowercase()

        return when (ext) {
            "html", "htm" -> {
                // HTML文件：合并关联的CSS/JS文件后返回完整HTML
                val projectDir = entryFile.parentFile
                val mergedHtml = mergeProjectFiles(content, projectDir)
                mergedHtml
            }
            "js", "javascript" -> {
                // JavaScript：包装成HTML页面执行
                wrapJsAsHtml(content)
            }
            "py", "python" -> {
                // Python：使用 Brython 包装为可渲染 HTML
                wrapPythonAsHtml(content)
            }
            "css" -> {
                // CSS：包装成HTML预览
                wrapCssAsHtml(content)
            }
            else -> {
                // 其他语言：返回语法高亮代码
                "📄 $entry (${content.length} 字符)\n\n```\n$content\n```"
            }
        }
    }

    /** 合并项目文件（将CSS/JS内联到HTML） */
    private fun mergeProjectFiles(html: String, projectDir: File?): String {
        if (projectDir == null || !projectDir.exists()) return html

        var result = html

        // 内联CSS文件：<link rel="stylesheet" href="style.css"> → <style>...</style>
        val cssLinkRegex = Regex("""<link\s+[^>]*href=["']([^"']+\.css)["']\s*[^>]*>""", RegexOption.IGNORE_CASE)
        result = cssLinkRegex.replace(result) { match ->
            val cssPath = match.groupValues[1]
            val cssFile = File(projectDir, cssPath)
            if (cssFile.exists()) {
                val cssContent = cssFile.readText()
                "<style>\n$cssContent\n</style>"
            } else {
                match.value
            }
        }

        // 内联JS文件：<script src="app.js"></script> → <script>...</script>
        val jsScriptRegex = Regex("""<script\s+[^>]*src=["']([^"']+\.js)["']\s*[^>]*>\s*</script>""", RegexOption.IGNORE_CASE)
        result = jsScriptRegex.replace(result) { match ->
            val jsPath = match.groupValues[1]
            val jsFile = File(projectDir, jsPath)
            if (jsFile.exists()) {
                val jsContent = jsFile.readText()
                "<script>\n$jsContent\n</script>"
            } else {
                match.value
            }
        }

        // 如果没有<!DOCTYPE>，添加基础HTML结构
        if (!result.trimStart().startsWith("<!DOCTYPE") && !result.trimStart().startsWith("<html")) {
            result = """<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <style>
        body { margin: 0; padding: 16px; font-family: system-ui, sans-serif; }
    </style>
</head>
<body>
$result
</body>
</html>"""
        }

        return result
    }

    /** 将JS包装成HTML页面 */
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

    /** 将CSS包装成HTML预览 */
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

    /** 将 Python 代码包装为 Brython 可执行的 HTML */
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

    /** 列出所有文件 */
    private fun listFiles(context: Context, json: JSONObject): String {
        val projectName = json.optString("name", "")
        val root = if (projectName.isNotBlank()) {
            getProjectDir(context, projectName)
        } else {
            getWorkspaceRoot(context)
        }

        if (!root.exists()) return "工作区为空"

        val files = mutableListOf<String>()
        root.walkTopDown()
            .filter { it.isFile }
            .forEach { file ->
                val relativePath = file.relativeTo(root).path
                val size = file.length()
                files.add("$relativePath (${formatSize(size)})")
            }

        if (files.isEmpty()) return "工作区为空"

        return buildString {
            appendLine("📁 工作区文件列表：")
            files.sorted().forEach { appendLine("  • $it") }
            appendLine("\n共 ${files.size} 个文件")
        }
    }

    /** 获取文件内容 */
    private fun getFile(context: Context, json: JSONObject): String {
        val file = json.optString("file", "").ifBlank { return "缺少 file 参数" }

        val targetFile = findFile(context, file) ?: return "找不到文件：$file"

        return try {
            val content = targetFile.readText()
            "📄 $file (${content.length} 字符)\n\n```\n$content\n```"
        } catch (e: Exception) {
            "❌ 读取失败：${e.message}"
        }
    }

    /** 删除文件 */
    private fun deleteFile(context: Context, json: JSONObject): String {
        val file = json.optString("file", "").ifBlank { return "缺少 file 参数" }

        val targetFile = findFile(context, file) ?: return "找不到文件：$file"

        return if (targetFile.delete()) {
            "✅ 已删除 $file"
        } else {
            "❌ 删除失败"
        }
    }

    /** 清空工作区 */
    private fun cleanWorkspace(context: Context): String {
        val root = getWorkspaceRoot(context)
        return if (root.deleteRecursively()) {
            root.mkdirs()
            "✅ 工作区已清空"
        } else {
            "❌ 清空失败"
        }
    }

    /** 在所有项目中查找文件 */
    private fun findFile(context: Context, path: String): File? {
        val root = getWorkspaceRoot(context)
        // 先尝试直接路径
        val direct = File(root, path)
        if (direct.exists()) return direct
        // 在所有项目中搜索
        root.listFiles()?.forEach { projectDir ->
            if (projectDir.isDirectory) {
                val file = File(projectDir, path)
                if (file.exists()) return file
            }
        }
        return null
    }

    /** 格式化文件大小 */
    private fun formatSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "${bytes}B"
            bytes < 1024 * 1024 -> "${bytes / 1024}KB"
            else -> "${bytes / (1024 * 1024)}MB"
        }
    }
}
