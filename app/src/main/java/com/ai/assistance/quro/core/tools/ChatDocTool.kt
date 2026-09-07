package com.ai.assistance.quro.core.tools

import android.content.Context
import org.json.JSONObject

/**
 * 对话框文档工具：AI 在对话中直接写文档并渲染显示
 *
 * 与 aiwps_create / enhanced_doc_create / workspace_doc 的区别：
 * - aiwps_create：生成真实 Office 文件到 Downloads 目录（可分享）
 * - enhanced_doc_create：生成多种格式文件到 Documents 目录
 * - workspace_doc：写入工作区文件
 * - chat_doc：不生成文件，直接在对话框内渲染文档内容（Markdown/HTML/代码/文本）
 *
 * 使用场景：
 * - AI 写一篇文章，直接在对话框显示
 * - AI 生成代码示例，带语法高亮
 * - AI 写报告/方案，带格式排版
 * - AI 生成表格/列表，直接在对话框渲染
 */
class ChatDocTool : QuroTool {
    override val name = "chat_doc"
    override val description = "📝 对话框文档：在对话框内直接写文档并渲染显示（不生成文件）。" +
        "与 aiwps_create 的区别：chat_doc 不生成文件，内容直接在对话框内渲染；aiwps_create 生成可下载的 Office 文件。" +
        "与 run_code(lang=html) 的区别：chat_doc 专注文档排版（Markdown/文本/表格），run_code 专注代码执行。" +
        "参数：{\"title\":\"标题\",\"content\":\"内容\",\"format\":\"md|html|code|text\",\"language\":\"代码语言(可选)\"}。"
    override val parametersJson = """{
        "type":"object",
        "properties":{
            "title":{"type":"string","description":"文档标题"},
            "content":{"type":"string","description":"文档内容"},
            "format":{"type":"string","description":"格式：md(Markdown) | html(HTML) | code(代码) | text(纯文本)","enum":["md","html","code","text"]},
            "language":{"type":"string","description":"代码语言（format=code时必填）：python|javascript|java|kotlin|json|xml|yaml|css|sql|bash|go|rust|c|cpp|swift|typescript"}
        },
        "required":["title","content"]
    }"""

    override fun run(context: Context, arguments: String): String {
        val args = JSONObject(arguments)
        val title = args.optString("title", "").trim()
        if (title.isBlank()) return "chat_doc 需要 title（文档标题）"

        val content = args.optString("content", "").trim()
        if (content.isBlank()) return "chat_doc 需要 content（文档内容）"

        val format = args.optString("format", "").trim().ifBlank {
            // 自动检测格式
            when {
                content.contains("<html") || content.contains("<div") || content.contains("<p") -> "html"
                content.startsWith("# ") || content.contains("```") || content.contains("**") -> "md"
                else -> "text"
            }
        }

        val language = args.optString("language", "").trim()

        // 输出 AIP 信封（kind=doc），对话框用 Canvas 引擎渲染成完整 AIP 文档，
        // 不再走 [渲染卡片] 极简卡（那张卡不是 AIP 文档）。
        return com.ai.assistance.quro.core.canvas.Aip.docEnvelope(title, content, format, language)
    }
}
