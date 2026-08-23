package com.ai.assistance.quro.core.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * 工作区文档查看工具：在工作区中打开和查看文档
 * 支持：PDF、DOCX、XLSX、PPTX、TXT、MD 等
 */
class WorkspaceDocViewTool : QuroTool {
    override val name = "workspace_doc_view"
    override val description = """工作区文档查看工具：在系统应用中打开工作区中的文档。
支持格式：PDF、DOCX、XLSX、PPTX、TXT、MD 等
参数：{"path":"工作区内相对路径"}
会调用系统默认应用打开文档（如 WPS、Office 等）。"""
    override val parametersJson = """{
        "type":"object",
        "properties":{
            "path":{"type":"string","description":"工作区内的相对文件路径"}
        },
        "required":["path"]
    }"""

    override fun run(context: Context, arguments: String): String {
        val args = org.json.JSONObject(arguments)
        val path = args.optString("path", "").trim()
        if (path.isBlank()) return "workspace_doc_view 需要 path（文件路径）"

        val root = workspaceRoot(context)
        val file = resolveInWorkspace(root, path) ?: return "⚠️ path 越界：$path"

        if (!file.exists()) return "⚠️ 文件不存在：${file.absolutePath}"
        if (!file.isFile) return "⚠️ 不是文件：${file.absolutePath}"

        val ext = file.extension.lowercase()
        val mimeType = when (ext) {
            "pdf" -> "application/pdf"
            "doc", "docx" -> "application/msword"
            "xls", "xlsx" -> "application/vnd.ms-excel"
            "ppt", "pptx" -> "application/vnd.ms-powerpoint"
            "txt", "text" -> "text/plain"
            "md", "markdown" -> "text/markdown"
            "html", "htm" -> "text/html"
            "json" -> "application/json"
            "xml" -> "application/xml"
            "csv" -> "text/csv"
            else -> "application/octet-stream"
        }

        return try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            context.startActivity(intent)

            """
✅ 正在打开文档
文件：${file.name}
类型：${ext.uppercase()}
大小：${formatFileSize(file.length())}
路径：${file.absolutePath}
            """.trimIndent()
        } catch (e: Exception) {
            "⚠️ 打开文档失败：${e.message}\n可能没有安装支持 $ext 格式的应用"
        }
    }

    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
            else -> "${bytes / (1024 * 1024 * 1024)} GB"
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
