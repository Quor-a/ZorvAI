package com.ai.assistance.quro.core.tools

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream

/**
 * aiWPS 文档改写工具（与 [AiwpsCreateTool] / [AiwpsReadTool] 配套）。
 *
 * 补全「改」这一环——此前 AI 只能新建文档，无法改写已有文档，这正是用户
 * 反馈「WPS 文档是玩具、重写功能几乎没有」的另一个根因。本工具接收一份已有的
 * 文档（仅取其类型与文件名基调）与 AI 提供的新正文，复用 [AiwpsCreateTool]
 * 的真实 OOXML 生成能力重生成同类型文档，输出新文件（默认）或覆盖原文件。
 *
 * 注意：这是「整篇重写」语义——AI 先 aiwps_read 读取、理解、生成完整新正文，
 * 再 aiwps_edit 落盘。不保留原文档的局部格式（如复杂样式/图片），与「文本级撰写」
 * 定位一致；若需保留精细排版，请用外部 WPS 编辑后重新导入。
 */
class AiwpsEditTool : QuroTool {
    override val name = "aiwps_edit"
    override val description = "改写/重写本地已有文档：读取其类型，用提供的新正文重生成同类型真实文档。" +
        "参数 {\"path\":\"原文档绝对路径(用于推断类型与文件名)\",\"content\":\"新正文(完整)\",\"title\":\"可选标题\"," +
        "\"overwrite\":false(默认生成 _edited 新文件)/true(覆盖原文件)}。" +
        "支持 docx/xlsx/pptx/pdf/md/txt/csv/html。典型流程：aiwps_read 读取 → 改写正文 → aiwps_edit 落盘。"
    override val parametersJson = """{
        "type":"object",
        "properties":{
            "path":{"type":"string","description":"原文档绝对路径，用于推断类型(docx/xlsx/...)与文件名（如 /.../QuroDocs/报告.docx）"},
            "content":{"type":"string","description":"新文档的完整正文（AI 已按类型语法组织：docx 行分段/`**加粗**`/表格；xlsx 行/列/`### 表名`；pptx 标题+要点/`---`分页）"},
            "title":{"type":"string","description":"可选标题"},
            "overwrite":{"type":"boolean","description":"true=覆盖原文件（同路径同扩展名）；false=生成 原名_edited_时间戳 新文件。默认 false"}
        },
        "required":["path","content"]
    }"""

    override fun run(context: Context, arguments: String): String {
        val jo = JSONObject(arguments)
        val path = jo.optString("path", "").trim()
        val content = jo.optString("content", "")
        val title = jo.optString("title", "").ifBlank { "" }
        val overwrite = jo.optBoolean("overwrite", false)
        if (path.isBlank()) return "aiwps_edit 需要 path（原文档绝对路径，用于推断类型）"
        if (content.isBlank()) return "aiwps_edit 需要 content（新正文）"

        val src = File(path)
        if (!src.exists() || !src.isFile) return "aiwps_edit 原文件不存在：$path"
        val ext = src.extension.lowercase()
        val supported = setOf("docx", "xlsx", "pptx", "pdf", "md", "txt", "csv", "html")
        if (ext !in supported) return "aiwps_edit 不支持的类型：$ext（仅 docx/xlsx/pptx/pdf/md/txt/csv/html）"

        // 用原文件名（去扩展名）作基调；覆盖模式沿用原名，否则加 _edited_时间戳
        val baseName = src.nameWithoutExtension
        val filename = if (overwrite) baseName else "${baseName}_edited_${System.currentTimeMillis()}"

        val json = JSONObject().apply {
            put("type", ext)
            put("title", title)
            put("content", content)
            put("filename", filename)
        }.toString()

        val genResult = runCatching { AiwpsCreateTool().run(context, json) }
            .getOrElse { return "aiwps_edit 生成失败：$it" }
        if (!genResult.startsWith("已生成")) return genResult // 透传工具自身错误（缺 content / 类型不支持等）

        val genPath = Regex("""文档：(.+?)（""").find(genResult)?.groupValues?.getOrNull(1)?.trim()
            ?: return "aiwps_edit 生成成功但无法解析路径：$genResult"
        val genFile = File(genPath)
        if (!genFile.exists()) return "aiwps_edit 生成文件不存在：$genPath"

        return if (overwrite) {
            // 覆盖原文件：把新文件字节写回原路径（同扩展名），再删临时生成文件。
            // 边界：若原文件本就在 QuroDocs 且同名，生成时已直接覆盖原路径，genFile==src，
            // 此时跳过自拷（避免同一文件读写互锁），直接返回原路径。
            if (src.absolutePath == genFile.absolutePath) {
                "已重写并覆盖原文档：${src.absolutePath}（${src.length() / 1024} KB）。"
            } else {
                runCatching {
                    FileInputStream(genFile).use { ins -> src.outputStream().use { outs -> ins.copyTo(outs) } }
                    genFile.delete()
                }.onFailure { return "aiwps_edit 覆盖写入失败：${it.message}" }
                "已重写并覆盖原文档：${src.absolutePath}（${src.length() / 1024} KB）。"
            }
        } else {
            "已生成改写后的新文档（原文件保留）：${genFile.absolutePath}（${genFile.length() / 1024} KB）。如需覆盖原文件，可再调用 aiwps_edit 并设 overwrite=true。"
        }
    }
}
