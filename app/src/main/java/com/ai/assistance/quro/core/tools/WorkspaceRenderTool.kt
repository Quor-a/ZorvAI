package com.ai.assistance.quro.core.tools

import android.content.Context
import java.io.File

/**
 * 工作区渲染工具：让 AI 把工作区中的文件渲染到对话框中预览
 * 支持：HTML、Markdown、代码高亮、图片、PDF 等
 */
class WorkspaceRenderTool : QuroTool {
    override val name = "workspace_render"
    override val description = """工作区文件渲染工具：把工作区中的文件渲染到对话框中预览。
支持渲染：HTML、Markdown、代码、图片等
参数：{"path":"工作区内相对路径","title":"可选标题"}
渲染结果会以卡片形式显示在对话框中。"""
    override val parametersJson = """{
        "type":"object",
        "properties":{
            "path":{"type":"string","description":"工作区内的相对文件路径"},
            "title":{"type":"string","description":"渲染卡片标题（可选）"}
        },
        "required":["path"]
    }"""

    override fun run(context: Context, arguments: String): String {
        val args = org.json.JSONObject(arguments)
        val path = args.optString("path", "").trim()
        if (path.isBlank()) return "workspace_render 需要 path（文件路径）"

        val title = args.optString("title", "").trim().ifBlank { path.substringAfterLast("/") }

        val root = workspaceRoot(context)
        val file = resolveInWorkspace(root, path) ?: return "⚠️ path 越界：$path"

        if (!file.exists()) return "⚠️ 文件不存在：$path"
        if (!file.isFile) return "⚠️ 不是文件：$path"

        val ext = file.extension.lowercase()
        val content = file.readText(Charsets.UTF_8)

        return when (ext) {
            "html", "htm" -> renderHtml(title, content, file.absolutePath)
            "md", "markdown" -> renderMarkdown(title, content)
            "json" -> renderCode(title, content, "json")
            "js", "javascript", "ts", "typescript" -> renderCode(title, content, ext)
            "py", "python" -> renderCode(title, content, "python")
            "java", "kt", "kotlin" -> renderCode(title, content, ext)
            "css" -> renderCode(title, content, "css")
            "xml", "svg" -> renderCode(title, content, ext)
            "txt", "text" -> renderText(title, content)
            "jpg", "jpeg", "png", "gif", "webp" -> renderImage(title, file.absolutePath)
            "pdf" -> renderPdf(title, file.absolutePath)
            else -> renderText(title, content)
        }
    }

    private fun renderHtml(title: String, content: String, path: String): String {
        // 返回 HTML 渲染指令
        return """
[渲染卡片]
类型：HTML
标题：$title
路径：$path
内容：
$content
[/渲染卡片]
        """.trimIndent()
    }

    private fun renderMarkdown(title: String, content: String): String {
        return """
[渲染卡片]
类型：Markdown
标题：$title
内容：
$content
[/渲染卡片]
        """.trimIndent()
    }

    private fun renderCode(title: String, content: String, language: String): String {
        return """
[渲染卡片]
类型：代码
标题：$title
语言：$language
内容：
$language
```
$content
```
[/渲染卡片]
        """.trimIndent()
    }

    private fun renderText(title: String, content: String): String {
        return """
[渲染卡片]
类型：文本
标题：$title
内容：
$content
[/渲染卡片]
        """.trimIndent()
    }

    private fun renderImage(title: String, path: String): String {
        return """
[渲染卡片]
类型：图片
标题：$title
路径：$path
[/渲染卡片]
        """.trimIndent()
    }

    private fun renderPdf(title: String, path: String): String {
        return """
[渲染卡片]
类型：PDF
标题：$title
路径：$path
说明：PDF 文件已保存到工作区，可在文件管理器中打开查看
[/渲染卡片]
        """.trimIndent()
    }

    private fun workspaceRoot(context: Context): File {
        val customPath = WorkspacePreferences.getCurrentWorkspace(context)
        if (customPath != null) {
            val customDir = File(customPath)
            if (customDir.exists() && customDir.isDirectory) {
                return customDir
            }
        }
        return File(context.getExternalFilesDir(null), "QuroWorkspace").apply { mkdirs() }
    }

    private fun resolveInWorkspace(root: File, relative: String): File? {
        val cleaned = relative.trim().trimStart('/').replace('\\', '/')
        if (cleaned.isEmpty()) return root
        val target = File(root, cleaned).canonicalFile
        val base = root.canonicalFile
        if (!target.path.startsWith(base.path + File.separator) && target != base) return null
        return target
    }
}
