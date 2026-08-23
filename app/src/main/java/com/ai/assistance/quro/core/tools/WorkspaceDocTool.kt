package com.ai.assistance.quro.core.tools

import android.content.Context
import java.io.File

/**
 * 工作区文档创建工具：在工作区中创建各类文档
 * 支持：HTML、Markdown、TXT、JSON、代码文件等
 */
class WorkspaceDocTool : QuroTool {
    override val name = "workspace_doc"
    override val description = """工作区文档创建工具：在工作区中创建各类文档。
支持格式：HTML、Markdown、TXT、JSON、代码文件等
参数：{"path":"相对路径","content":"内容","type":"文档类型"}
创建的文档会自动保存到工作区，并可在对话框中渲染预览。"""
    override val parametersJson = """{
        "type":"object",
        "properties":{
            "path":{"type":"string","description":"工作区内的相对文件路径"},
            "content":{"type":"string","description":"文档内容"},
            "type":{"type":"string","description":"文档类型：html|md|txt|json|js|py|java|kt|css","enum":["html","md","txt","json","js","py","java","kt","css"]}
        },
        "required":["path","content"]
    }"""

    override fun run(context: Context, arguments: String): String {
        val args = org.json.JSONObject(arguments)
        val path = args.optString("path", "").trim()
        if (path.isBlank()) return "workspace_doc 需要 path（文件路径）"

        val content = args.optString("content", "")
        val type = args.optString("type", "").trim()

        val root = workspaceRoot(context)
        val file = resolveInWorkspace(root, path) ?: return "⚠️ path 越界：$path"

        // 根据类型或扩展名确定文件类型
        val fileType = type.ifBlank {
            when {
                path.endsWith(".html") || path.endsWith(".htm") -> "html"
                path.endsWith(".md") || path.endsWith(".markdown") -> "md"
                path.endsWith(".txt") -> "txt"
                path.endsWith(".json") -> "json"
                path.endsWith(".js") -> "js"
                path.endsWith(".py") -> "py"
                path.endsWith(".java") -> "java"
                path.endsWith(".kt") -> "kotlin"
                path.endsWith(".css") -> "css"
                else -> "txt"
            }
        }

        // 如果没有指定扩展名，根据类型添加
        val actualPath = if (!path.contains(".")) {
            when (fileType) {
                "html" -> "$path.html"
                "md" -> "$path.md"
                "txt" -> "$path.txt"
                "json" -> "$path.json"
                "js" -> "$path.js"
                "py" -> "$path.py"
                "java" -> "$path.java"
                "kotlin" -> "$path.kt"
                "css" -> "$path.css"
                else -> "$path.txt"
            }
        } else path

        val actualFile = resolveInWorkspace(root, actualPath) ?: return "⚠️ path 越界：$actualPath"

        return try {
            actualFile.parentFile?.mkdirs()
            actualFile.writeText(content, Charsets.UTF_8)

            // 返回成功信息和渲染指令
            val renderType = when (fileType) {
                "html" -> "HTML"
                "md" -> "Markdown"
                "json", "js", "py", "java", "kotlin", "css" -> "代码"
                else -> "文本"
            }

            """
✅ 文档已创建：$actualPath
大小：${actualFile.length()} 字节
类型：$renderType

[渲染卡片]
类型：$renderType
标题：${actualPath.substringAfterLast("/")}
路径：${actualFile.absolutePath}
内容：
$content
[/渲染卡片]
            """.trimIndent()
        } catch (e: Exception) {
            "⚠️ 创建失败：${e.message}"
        }
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
