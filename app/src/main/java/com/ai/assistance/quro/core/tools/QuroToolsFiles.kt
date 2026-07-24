package com.ai.assistance.quro.core.tools

import android.content.Context
import java.io.File
import kotlin.text.Charsets
import org.json.JSONObject

/**
 * 文件浏览/读取工具（原创）。出于隐私与权限简化，仅访问应用专属外部存储目录
 * （context.getExternalFilesDir），无需任何危险权限。
 */
class ListFilesTool : QuroTool {
    override val name = "list_files"
    override val description = "列出应用专属目录下的文件/文件夹，参数为 {\"path\":\"\",\"limit\":50}。path 为相对 getExternalFilesDir 的子路径，默认根。"
    override val parametersJson = """{"type":"object","properties":{"path":{"type":"string","description":"相对子路径(可选)"},"limit":{"type":"integer","description":"返回条数默认50"}}}"""
    override fun run(context: Context, arguments: String): String {
        val rel = JSONObject(arguments).optString("path", "").trim('/')
        val limit = JSONObject(arguments).optInt("limit", 50).coerceIn(1, 200)
        val root = context.getExternalFilesDir(null) ?: return "无法访问外部存储"
        val dir = if (rel.isEmpty()) root else File(root, rel)
        if (!dir.exists() || !dir.isDirectory) return "目录不存在: $rel"
        val items = dir.listFiles()?.sortedBy { !it.isDirectory }?.take(limit)
            ?: return "（空目录）"
        return items.joinToString("\n") { "${if (it.isDirectory) "[D]" else "[F]"} ${it.name} (${it.length()}B)" }
    }
}

class ReadTextFileTool : QuroTool {
    override val name = "read_text_file"
    override val description = "读取应用专属目录下的文本文件内容，参数为 {\"path\":\"sub/a.txt\",\"maxBytes\":4096}。"
    override val parametersJson = """{"type":"object","properties":{"path":{"type":"string","description":"相对 getExternalFilesDir 的路径"},"maxBytes":{"type":"integer","description":"最多读取字节数默认4096"}},"required":["path"]}"""
    override fun run(context: Context, arguments: String): String {
        val rel = JSONObject(arguments).optString("path", "")
        if (rel.isEmpty()) return "缺少 path 参数"
        val max = JSONObject(arguments).optInt("maxBytes", 4096).coerceIn(1, 200_000)
        val root = context.getExternalFilesDir(null) ?: return "无法访问外部存储"
        val f = File(root, rel)
        if (!f.exists() || !f.isFile) return "文件不存在: $rel"
        if (f.length() > 10_000_000) return "文件过大，拒绝读取"
        return try {
            val bytes = f.readBytes().take(max).toByteArray()
            String(bytes, Charsets.UTF_8)
        } catch (e: Exception) {
            "读取失败: ${e.message}"
        }
    }
}
