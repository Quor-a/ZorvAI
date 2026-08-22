package com.ai.assistance.quro.core.tools

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 文件写/改/删工具。仅作用于应用专属外部存储（getExternalFilesDir），
 * 无需任何危险权限，避免触及用户媒体/下载等共享目录。
 * 功能：write_file / delete_file / make_directory / move_file / copy_file / find_files / file_info。
 */
private fun resolveAppFile(context: Context, rel: String): File? {
    val root = context.getExternalFilesDir(null) ?: return null
    if (rel.isEmpty()) return root
    return File(root, rel.trimStart('/'))
}

class WriteFileTool : QuroTool {
    override val name = "write_file"
    override val description = "写入文本到应用专属目录文件，参数 {\"path\":\"sub/a.txt\",\"content\":\"文本\",\"append\":false}。"
    override val parametersJson = """{"type":"object","properties":{"path":{"type":"string","description":"相对 getExternalFilesDir 的路径"},"content":{"type":"string","description":"要写入的内容"},"append":{"type":"boolean","description":"是否追加，默认 false"}},"required":["path","content"]}"""
    override fun run(context: Context, arguments: String): String {
        val jo = JSONObject(arguments)
        val rel = jo.optString("path", "")
        val content = jo.optString("content", "")
        if (rel.isEmpty()) return "缺少 path 参数"
        val f = resolveAppFile(context, rel) ?: return "无法访问外部存储"
        return try {
            f.parentFile?.mkdirs()
            if (jo.optBoolean("append", false)) f.appendText(content) else f.writeText(content)
            "已写入 ${f.length()} 字节: $rel"
        } catch (e: Exception) { "写入失败: ${e.message}" }
    }
}

class DeleteFileTool : QuroTool {
    override val name = "delete_file"
    override val description = "删除应用专属目录下的文件或文件夹，参数 {\"path\":\"sub/a.txt\"}。"
    override val parametersJson = """{"type":"object","properties":{"path":{"type":"string","description":"相对路径"}},"required":["path"]}"""
    override fun run(context: Context, arguments: String): String {
        val rel = JSONObject(arguments).optString("path", "")
        if (rel.isEmpty()) return "缺少 path 参数"
        val f = resolveAppFile(context, rel) ?: return "无法访问外部存储"
        if (!f.exists()) return "不存在: $rel"
        val ok = if (f.isDirectory) f.deleteRecursively() else f.delete()
        return if (ok) "已删除: $rel" else "删除失败: $rel"
    }
}

class MakeDirectoryTool : QuroTool {
    override val name = "make_directory"
    override val description = "在应用专属目录创建文件夹，参数 {\"path\":\"sub/newdir\"}。"
    override val parametersJson = """{"type":"object","properties":{"path":{"type":"string","description":"相对路径"}},"required":["path"]}"""
    override fun run(context: Context, arguments: String): String {
        val rel = JSONObject(arguments).optString("path", "")
        if (rel.isEmpty()) return "缺少 path 参数"
        val f = resolveAppFile(context, rel) ?: return "无法访问外部存储"
        return if (f.mkdirs()) "已创建: $rel" else if (f.exists()) "已存在: $rel" else "创建失败: $rel"
    }
}

class MoveFileTool : QuroTool {
    override val name = "move_file"
    override val description = "在应用专属目录内移动/重命名文件，参数 {\"from\":\"a.txt\",\"to\":\"sub/b.txt\"}。"
    override val parametersJson = """{"type":"object","properties":{"from":{"type":"string","description":"源相对路径"},"to":{"type":"string","description":"目标相对路径"}},"required":["from","to"]}"""
    override fun run(context: Context, arguments: String): String {
        val jo = JSONObject(arguments)
        val fromRel = jo.optString("from", "")
        val toRel = jo.optString("to", "")
        if (fromRel.isEmpty() || toRel.isEmpty()) return "缺少 from / to 参数"
        val from = resolveAppFile(context, fromRel) ?: return "无法访问外部存储"
        val to = resolveAppFile(context, toRel) ?: return "无法访问外部存储"
        if (!from.exists()) return "源不存在: $fromRel"
        to.parentFile?.mkdirs()
        return if (from.renameTo(to)) "已移动: $fromRel -> $toRel" else "移动失败"
    }
}

class CopyFileTool : QuroTool {
    override val name = "copy_file"
    override val description = "在应用专属目录内复制文件，参数 {\"from\":\"a.txt\",\"to\":\"sub/b.txt\"}。"
    override val parametersJson = """{"type":"object","properties":{"from":{"type":"string","description":"源相对路径"},"to":{"type":"string","description":"目标相对路径"}},"required":["from","to"]}"""
    override fun run(context: Context, arguments: String): String {
        val jo = JSONObject(arguments)
        val fromRel = jo.optString("from", "")
        val toRel = jo.optString("to", "")
        if (fromRel.isEmpty() || toRel.isEmpty()) return "缺少 from / to 参数"
        val from = resolveAppFile(context, fromRel) ?: return "无法访问外部存储"
        val to = resolveAppFile(context, toRel) ?: return "无法访问外部存储"
        if (!from.exists()) return "源不存在: $fromRel"
        if (from.isDirectory) return "copy_file 仅支持文件"
        to.parentFile?.mkdirs()
        return try { from.copyTo(to, overwrite = true); "已复制: $toRel" }
        catch (e: Exception) { "复制失败: ${e.message}" }
    }
}

class FindFilesTool : QuroTool {
    override val name = "find_files"
    override val description = "在应用专属目录按名称子串搜索文件，参数 {\"query\":\"log\",\"limit\":50}。"
    override val parametersJson = """{"type":"object","properties":{"query":{"type":"string","description":"名称包含的子串"},"limit":{"type":"integer","description":"返回条数默认50"}},"required":["query"]}"""
    override fun run(context: Context, arguments: String): String {
        val jo = JSONObject(arguments)
        val q = jo.optString("query", "").lowercase()
        if (q.isEmpty()) return "缺少 query 参数"
        val limit = jo.optInt("limit", 50).coerceIn(1, 200)
        val root = resolveAppFile(context, "") ?: return "无法访问外部存储"
        val hits = mutableListOf<String>()
        root.walkTopDown().forEach { f ->
            if (f.name.lowercase().contains(q)) hits.add("${if (f.isDirectory) "[D]" else "[F]"} ${f.relativeTo(root).path}")
            if (hits.size >= limit) return@forEach
        }
        return if (hits.isEmpty()) "未找到包含 '$q' 的文件" else hits.joinToString("\n")
    }
}

class FileInfoTool : QuroTool {
    override val name = "file_info"
    override val description = "查看应用专属目录文件信息（大小/修改时间/类型），参数 {\"path\":\"sub/a.txt\"}。"
    override val parametersJson = """{"type":"object","properties":{"path":{"type":"string","description":"相对路径"}},"required":["path"]}"""
    override fun run(context: Context, arguments: String): String {
        val rel = JSONObject(arguments).optString("path", "")
        if (rel.isEmpty()) return "缺少 path 参数"
        val f = resolveAppFile(context, rel) ?: return "无法访问外部存储"
        if (!f.exists()) return "不存在: $rel"
        val kind = if (f.isDirectory) "目录" else "文件"
        val size = if (f.isFile) "${f.length()} 字节" else "-"
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val mt = fmt.format(Date(f.lastModified()))
        return "路径=$rel\n类型=$kind\n大小=$size\n修改时间=$mt"
    }
}
