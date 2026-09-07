package com.ai.assistance.quro.core.tools

import android.content.Context
import java.io.File

/**
 * 工作区文档创建工具：在工作区中创建各类文档
 * 支持：HTML、Markdown、TXT、JSON、代码文件等
 */
class WorkspaceDocTool : QuroTool {
    override val name = "workspace_doc"
    override val description = """📁 工作区文档创建：在工作区中创建文件（HTML/MD/TXT/JSON/JS/PY/Java/KT/CSS）。
与 workspace_write 的区别：workspace_doc 自动根据类型添加扩展名、自动渲染预览卡片；workspace_write 是通用文件写入。
与 aiwps_create 的区别：workspace_doc 写入工作区（用户可在工具箱-工作区查看），aiwps_create 生成到 Downloads 目录（可分享）。
参数：{"path":"相对路径","content":"内容","type":"文档类型"}"""
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

            // 返回成功信息 + AIP 信封（kind=doc），对话框用 Canvas 引擎渲染成完整 AIP 文档，
            // 不再走 [渲染卡片] 极简卡（那张卡不是 AIP 文档）。
            val renderType = when (fileType) {
                "html" -> "HTML"
                "md" -> "Markdown"
                "json", "js", "py", "java", "kotlin", "css" -> "代码"
                else -> "文本"
            }
            val (aipFormat, aipLang) = when (fileType) {
                "html" -> "html" to ""
                "md" -> "md" to ""
                "json" -> "json" to "json"
                "js" -> "js" to "javascript"
                "py" -> "py" to "python"
                "java" -> "java" to "java"
                "kotlin" -> "kotlin" to "kotlin"
                "css" -> "css" to "css"
                else -> "text" to ""
            }
            val note = "✅ 文档已创建：$actualPath\n大小：${actualFile.length()} 字节\n类型：$renderType"
            com.ai.assistance.quro.core.canvas.Aip.docEnvelope(
                title = actualPath.substringAfterLast("/"),
                content = content,
                format = aipFormat,
                language = aipLang,
                note = note,
            )
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
