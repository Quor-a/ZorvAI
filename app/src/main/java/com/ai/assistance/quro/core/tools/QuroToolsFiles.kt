package com.ai.assistance.quro.core.tools
import com.ai.assistance.quro.R
import com.ai.assistance.quro.core.util.FileChunkReader
import com.ai.assistance.quro.util.qstr

import android.content.Context
import java.io.File
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
    override val description = "读取应用专属目录下的文本文件内容，支持 offset/maxBytes 分段读取大文件。参数为 {\"path\":\"sub/a.txt\",\"maxBytes\":4096,\"offset\":0}。大文件按返回里的 nextOffset 循环分段读。"
    override val parametersJson = """{"type":"object","properties":{"path":{"type":"string","description":"相对 getExternalFilesDir 的路径"},"maxBytes":{"type":"integer","description":"本段最多读取字节数默认4096，上限262144"},"offset":{"type":"integer","description":"（可选）起始字节偏移，默认 0；分段读取时传上一段返回的 nextOffset"}},"required":["path"]}"""
    override fun run(context: Context, arguments: String): String {
        val obj = JSONObject(arguments)
        val rel = obj.optString("path", "")
        if (rel.isEmpty()) return "缺少 path 参数"
        val max = obj.optInt("maxBytes", 4096).coerceIn(1, FileChunkReader.MAX_CHUNK_BYTES)
        val offset = obj.optLong("offset", 0L)
        val root = context.getExternalFilesDir(null) ?: return "无法访问外部存储"
        val f = File(root, rel)
        return try {
            val chunk = FileChunkReader.read(f, offset, max)
            FileChunkReader.pageHeader(f, chunk) + chunk.text
        } catch (e: FileChunkReader.ChunkError) {
            "⚠️ ${e.message}"
        } catch (e: Exception) {
            qstr(R.string.qk_03169, (e.message).toString())
        }
    }
}