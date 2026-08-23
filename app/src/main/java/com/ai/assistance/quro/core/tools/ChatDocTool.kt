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

        return when (format) {
            "md" -> renderMarkdown(title, content)
            "html" -> renderHtml(title, content)
            "code" -> renderCode(title, content, language)
            "text" -> renderText(title, content)
            else -> renderText(title, content)
        }
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

    private fun renderHtml(title: String, content: String): String {
        // 包装成完整 HTML
        val fullHtml = """
<!DOCTYPE html>
<html lang="zh-CN">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>$title</title>
    <style>
        body { font-family: -apple-system, sans-serif; line-height: 1.6; padding: 16px; color: #333; }
        h1 { color: #2c3e50; border-bottom: 2px solid #3498db; padding-bottom: 8px; }
        h2 { color: #34495e; }
        code { background: #f4f4f4; padding: 2px 6px; border-radius: 3px; }
        pre { background: #f8f8f8; padding: 12px; border-radius: 6px; overflow-x: auto; }
        table { border-collapse: collapse; width: 100%; }
        th, td { border: 1px solid #ddd; padding: 8px; text-align: left; }
        th { background: #f2f2f2; }
        blockquote { border-left: 4px solid #3498db; margin: 8px 0; padding: 8px 16px; background: #f9f9f9; }
    </style>
</head>
<body>
    <h1>$title</h1>
    $content
</body>
</html>
        """.trimIndent()

        return """
[渲染卡片]
类型：HTML
标题：$title
内容：
$fullHtml
[/渲染卡片]
        """.trimIndent()
    }

    private fun renderCode(title: String, content: String, language: String): String {
        val lang = language.ifBlank { "text" }
        return """
[渲染卡片]
类型：代码
标题：$title
语言：$lang
内容：
```$lang
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
}
