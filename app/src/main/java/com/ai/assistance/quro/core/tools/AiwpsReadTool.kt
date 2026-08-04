package com.ai.assistance.quro.core.tools

import android.content.Context
import org.json.JSONObject
import java.io.File
import com.ai.assistance.quro.ui.extractOfficeText

/**
 * aiWPS 文档读取工具（与 [AiwpsCreateTool] 配套）。
 *
 * 之前 AI 只能「新建」文档（aiwps_create），无法读取已有文档，导致对话里
 * 想「重写 / 修改 / 总结」一个现成 docx/xlsx/pptx 时完全没有抓手——这正是
 * 用户反馈「WPS 文档是玩具、重写功能几乎没有」的根因之一。本工具补全
 * 「读」这一环：把任意本地文档（docx/xlsx/pptx/txt/md/csv/json/xml/代码）
 * 抽取为纯文本返回，供 AI 理解内容后再调用 aiwps_edit 改写。
 *
 * PDF 为二进制排版格式，进程内无内置文本提取器，明确返回不支持提示，
 * 不假装可读（避免返回乱码误导模型）。
 */
class AiwpsReadTool : QuroTool {
    override val name = "aiwps_read"
    override val description = "读取本地已有文档并抽取纯文本，供 AI 理解内容后改写/总结。" +
        "参数 {\"path\":\"文档绝对路径\",\"limit\":可选最大字符数(默认 20000)}。" +
        "支持 docx/xlsx/pptx/txt/md/csv/json/xml/代码；PDF 暂不支持进程内文本提取（请用 aiwps_edit 整篇重写）。" +
        "常用于：先 aiwps_read 读取用户文档，再 aiwps_edit 按指令重写并生成新文件。"
    override val parametersJson = """{
        "type":"object",
        "properties":{
            "path":{"type":"string","description":"文档绝对路径，如 /storage/emulated/0/Android/data/.../files/Documents/QuroDocs/xxx.docx"},
            "limit":{"type":"integer","description":"返回最大字符数，默认 20000，超出截断（避免超长淹没上下文）"}
        },
        "required":["path"]
    }"""

    override fun run(context: Context, arguments: String): String {
        val jo = JSONObject(arguments)
        val path = jo.optString("path", "").trim()
        val limit = jo.optInt("limit", 20_000).coerceAtLeast(1)
        if (path.isBlank()) return "aiwps_read 需要 path（文档绝对路径）"
        val file = File(path)
        if (!file.exists() || !file.isFile) return "aiwps_read 文件不存在：$path"
        if (!file.canRead()) return "aiwps_read 无读取权限：$path"

        val ext = file.extension.lowercase()
        val text = when (ext) {
            "pdf" -> return "aiwps_read 暂不支持 PDF 进程内文本提取（二进制排版）。如需改写 PDF 内容，请用 aiwps_edit 提供完整新正文直接重生成。"
            "docx", "xlsx", "pptx" -> {
                val t = runCatching { extractOfficeText(file) }.getOrElse { return "aiwps_read 解析失败：${it.message}" }
                if (t.isBlank()) return "aiwps_read 未提取到文本（可能为空文档或损坏）：$path" else t
            }
            else -> runCatching { file.readText(Charsets.UTF_8) }.getOrElse { return "aiwps_read 读取失败：${it.message}" }
        }
        val truncated = if (text.length > limit) text.take(limit) + "\n…（已截断，原文 ${text.length} 字）" else text
        return "文档路径：$path\n类型：$ext\n内容：\n$truncated"
    }
}
