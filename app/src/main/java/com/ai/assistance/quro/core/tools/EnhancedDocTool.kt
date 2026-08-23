package com.ai.assistance.quro.core.tools

import android.content.Context
import android.os.Environment
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 增强版文档创建工具：支持更多文档类型和更好的渲染
 * 支持：docx, xlsx, pptx, pdf, md, txt, csv, html, rtf, odt, epub, json, xml, yaml, svg
 */
class EnhancedDocTool : QuroTool {
    override val name = "enhanced_doc_create"
    override val description = """增强版文档创建工具：创建各种类型的文档，支持更好的渲染和排版。
支持格式：
- 办公文档：docx, xlsx, pptx, pdf, rtf, odt
- 文本格式：md, txt, csv, json, xml, yaml
- 网页格式：html, htm, css, js
- 其他：epub, svg
参数：{"type":"格式","title":"标题","content":"内容","filename":"文件名"}
创建后自动渲染预览。"""
    override val parametersJson = """{
        "type":"object",
        "properties":{
            "type":{"type":"string","description":"文档类型","enum":["docx","xlsx","pptx","pdf","md","txt","csv","html","rtf","odt","epub","json","xml","yaml","css","js","svg"]},
            "title":{"type":"string","description":"文档标题"},
            "content":{"type":"string","description":"文档内容"},
            "filename":{"type":"string","description":"文件名（不含扩展名）"}
        },
        "required":["type","content"]
    }"""

    override fun run(context: Context, arguments: String): String {
        val args = JSONObject(arguments)
        val type = args.optString("type", "").trim().lowercase()
        val content = args.optString("content", "")
        val title = args.optString("title", "").ifBlank { "${type}_${System.currentTimeMillis()}" }
        val filename = args.optString("filename", "").ifBlank { "${type}_${System.currentTimeMillis()}" }

        val supportedTypes = setOf(
            "docx", "xlsx", "pptx", "pdf", "md", "txt", "csv", "html",
            "rtf", "odt", "epub", "json", "xml", "yaml", "css", "js", "svg"
        )

        if (type !in supportedTypes) {
            return "不支持的类型：$type\n支持的类型：${supportedTypes.joinToString(", ")}"
        }

        if (content.isBlank()) return "内容不能为空"

        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "QuroDocs")
        if (!dir.exists()) dir.mkdirs()

        val safeName = filename.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(80)

        return try {
            when (type) {
                "docx" -> createDocx(dir, safeName, content, title)
                "xlsx" -> createXlsx(dir, safeName, content, title)
                "pptx" -> createPptx(dir, safeName, content, title)
                "pdf" -> createPdf(dir, safeName, content, title)
                "html", "htm" -> createHtml(dir, safeName, content, title)
                "md", "markdown" -> createMarkdown(dir, safeName, content, title)
                "txt" -> createPlainText(dir, safeName, content, title)
                "csv" -> createCsv(dir, safeName, content, title)
                "json" -> createJson(dir, safeName, content, title)
                "xml" -> createXml(dir, safeName, content, title)
                "yaml", "yml" -> createYaml(dir, safeName, content, title)
                "css" -> createCss(dir, safeName, content, title)
                "js" -> createJs(dir, safeName, content, title)
                "svg" -> createSvg(dir, safeName, content, title)
                "rtf" -> createRtf(dir, safeName, content, title)
                "odt" -> createOdt(dir, safeName, content, title)
                "epub" -> createEpub(dir, safeName, content, title)
                else -> "不支持的类型：$type"
            }
        } catch (e: Exception) {
            "创建失败：${e.message}"
        }
    }

    private fun createDocx(dir: File, name: String, content: String, title: String): String {
        val file = File(dir, "$name.docx")
        // 直接调用 AiwpsCreateTool 的静态方法
        val result = AiwpsCreateTool.createDocument("docx", content, title, name)
        return "✅ $result"
    }

    private fun createXlsx(dir: File, name: String, content: String, title: String): String {
        val file = File(dir, "$name.xlsx")
        val result = AiwpsCreateTool.createDocument("xlsx", content, title, name)
        return "✅ $result"
    }

    private fun createPptx(dir: File, name: String, content: String, title: String): String {
        val file = File(dir, "$name.pptx")
        val result = AiwpsCreateTool.createDocument("pptx", content, title, name)
        return "✅ $result"
    }

    private fun createPdf(dir: File, name: String, content: String, title: String): String {
        val file = File(dir, "$name.pdf")
        val result = AiwpsCreateTool.createDocument("pdf", content, title, name)
        return "✅ $result"
    }

    private fun createHtml(dir: File, name: String, content: String, title: String): String {
        val file = File(dir, "$name.html")
        val htmlContent = buildHtmlDocument(content, title)
        file.writeText(htmlContent, Charsets.UTF_8)
        return """
✅ HTML 文档已创建
文件：${file.absolutePath}
大小：${formatFileSize(file.length())}

[渲染卡片]
类型：HTML
标题：$title
内容：
$htmlContent
[/渲染卡片]
        """.trimIndent()
    }

    private fun createMarkdown(dir: File, name: String, content: String, title: String): String {
        val file = File(dir, "$name.md")
        val mdContent = "# $title\n\n$content"
        file.writeText(mdContent, Charsets.UTF_8)
        return """
✅ Markdown 文档已创建
文件：${file.absolutePath}
大小：${formatFileSize(file.length())}

[渲染卡片]
类型：Markdown
标题：$title
内容：
$mdContent
[/渲染卡片]
        """.trimIndent()
    }

    private fun createPlainText(dir: File, name: String, content: String, title: String): String {
        val file = File(dir, "$name.txt")
        val txtContent = "$title\n${"=".repeat(50)}\n\n$content"
        file.writeText(txtContent, Charsets.UTF_8)
        return """
✅ 文本文档已创建
文件：${file.absolutePath}
大小：${formatFileSize(file.length())}

[渲染卡片]
类型：文本
标题：$title
内容：
$txtContent
[/渲染卡片]
        """.trimIndent()
    }

    private fun createCsv(dir: File, name: String, content: String, title: String): String {
        val file = File(dir, "$name.csv")
        file.writeText(content, Charsets.UTF_8)
        return "✅ CSV 文件已创建：${file.absolutePath}"
    }

    private fun createJson(dir: File, name: String, content: String, title: String): String {
        val file = File(dir, "$name.json")
        file.writeText(content, Charsets.UTF_8)
        return """
✅ JSON 文件已创建
文件：${file.absolutePath}

[渲染卡片]
类型：代码
标题：$title
语言：JSON
内容：
```json
$content
```
[/渲染卡片]
        """.trimIndent()
    }

    private fun createXml(dir: File, name: String, content: String, title: String): String {
        val file = File(dir, "$name.xml")
        file.writeText(content, Charsets.UTF_8)
        return """
✅ XML 文件已创建
文件：${file.absolutePath}

[渲染卡片]
类型：代码
标题：$title
语言：XML
内容：
```xml
$content
```
[/渲染卡片]
        """.trimIndent()
    }

    private fun createYaml(dir: File, name: String, content: String, title: String): String {
        val file = File(dir, "$name.yaml")
        file.writeText(content, Charsets.UTF_8)
        return """
✅ YAML 文件已创建
文件：${file.absolutePath}

[渲染卡片]
类型：代码
标题：$title
语言：YAML
内容：
```yaml
$content
```
[/渲染卡片]
        """.trimIndent()
    }

    private fun createCss(dir: File, name: String, content: String, title: String): String {
        val file = File(dir, "$name.css")
        file.writeText(content, Charsets.UTF_8)
        return """
✅ CSS 文件已创建
文件：${file.absolutePath}

[渲染卡片]
类型：代码
标题：$title
语言：CSS
内容：
```css
$content
```
[/渲染卡片]
        """.trimIndent()
    }

    private fun createJs(dir: File, name: String, content: String, title: String): String {
        val file = File(dir, "$name.js")
        file.writeText(content, Charsets.UTF_8)
        return """
✅ JavaScript 文件已创建
文件：${file.absolutePath}

[渲染卡片]
类型：代码
标题：$title
语言：JavaScript
内容：
```javascript
$content
```
[/渲染卡片]
        """.trimIndent()
    }

    private fun createSvg(dir: File, name: String, content: String, title: String): String {
        val file = File(dir, "$name.svg")
        file.writeText(content, Charsets.UTF_8)
        return """
✅ SVG 文件已创建
文件：${file.absolutePath}

[渲染卡片]
类型：SVG
标题：$title
内容：
$content
[/渲染卡片]
        """.trimIndent()
    }

    private fun createRtf(dir: File, name: String, content: String, title: String): String {
        val file = File(dir, "$name.rtf")
        val rtfContent = buildRtfDocument(content, title)
        file.writeText(rtfContent, Charsets.UTF_8)
        return "✅ RTF 文档已创建：${file.absolutePath}"
    }

    private fun createOdt(dir: File, name: String, content: String, title: String): String {
        val file = File(dir, "$name.odt")
        // ODT 是 ZIP 格式，简化处理
        val odtContent = buildOdtDocument(content, title)
        writeZipFile(file, odtContent)
        return "✅ ODT 文档已创建：${file.absolutePath}"
    }

    private fun createEpub(dir: File, name: String, content: String, title: String): String {
        val file = File(dir, "$name.epub")
        // EPUB 是 ZIP 格式，简化处理
        val epubContent = buildEpubDocument(content, title)
        writeZipFile(file, epubContent)
        return "✅ EPUB 电子书已创建：${file.absolutePath}"
    }

    private fun buildHtmlDocument(content: String, title: String): String {
        val formattedContent = content
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\n", "<br>\n")

        return """
<!DOCTYPE html>
<html lang="zh-CN">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>${escapeHtml(title)}</title>
    <style>
        body {
            font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif;
            line-height: 1.6;
            max-width: 800px;
            margin: 0 auto;
            padding: 20px;
            color: #333;
        }
        h1 { color: #2c3e50; border-bottom: 2px solid #3498db; padding-bottom: 10px; }
        h2 { color: #34495e; }
        h3 { color: #7f8c8d; }
        p { margin: 10px 0; }
        code {
            background-color: #f4f4f4;
            padding: 2px 6px;
            border-radius: 3px;
            font-family: "Courier New", monospace;
        }
        pre {
            background-color: #f8f8f8;
            padding: 15px;
            border-radius: 5px;
            overflow-x: auto;
            border: 1px solid #ddd;
        }
        blockquote {
            border-left: 4px solid #3498db;
            margin: 10px 0;
            padding: 10px 20px;
            background-color: #f9f9f9;
        }
        table {
            border-collapse: collapse;
            width: 100%;
            margin: 10px 0;
        }
        th, td {
            border: 1px solid #ddd;
            padding: 8px;
            text-align: left;
        }
        th {
            background-color: #f2f2f2;
        }
    </style>
</head>
<body>
    <h1>${escapeHtml(title)}</h1>
    $formattedContent
</body>
</html>
        """.trimIndent()
    }

    private fun buildRtfDocument(content: String, title: String): String {
        return """{\rtf1\ansi\deff0
{\fonttbl{\f0 Times New Roman;}}
{\colortbl;\red0\green0\blue0;}
\pard\qc\fs32\b ${escapeRtf(title)}\b0\par
\pard\fs24 ${escapeRtf(content)}\par
}"""
    }

    private fun buildOdtDocument(content: String, title: String): Map<String, String> {
        // 简化的 ODT 结构
        val contentXml = """<?xml version="1.0" encoding="UTF-8"?>
<office:document-content
    xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0"
    xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0">
  <office:body>
    <office:text>
      <text:h text:style-name="Heading1">${escapeXml(title)}</text:h>
      <text:p>${escapeXml(content)}</text:p>
    </office:text>
  </office:body>
</office:document-content>"""

        return mapOf(
            "content.xml" to contentXml,
            "META-INF/manifest.xml" to """<?xml version="1.0" encoding="UTF-8"?>
<manifest:manifest xmlns:manifest="urn:oasis:names:tc:opendocument:xmlns:manifest:1.0">
  <manifest:file-entry manifest:media-type="application/vnd.oasis.opendocument.text" manifest:full-path="/"/>
  <manifest:file-entry manifest:media-type="text/xml" manifest:full-path="content.xml"/>
</manifest:manifest>"""
        )
    }

    private fun buildEpubDocument(content: String, title: String): Map<String, String> {
        val contentHtml = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml">
<head>
  <title>${escapeXml(title)}</title>
</head>
<body>
  <h1>${escapeXml(title)}</h1>
  <p>${escapeXml(content)}</p>
</body>
</html>"""

        return mapOf(
            "content.opf" to """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="2.0">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:title>${escapeXml(title)}</dc:title>
    <dc:language>zh-CN</dc:language>
  </metadata>
  <manifest>
    <item id="content" href="content.xhtml" media-type="application/xhtml+xml"/>
  </manifest>
  <spine>
    <itemref idref="content"/>
  </spine>
</package>""",
            "content.xhtml" to contentHtml,
            "mimetype" to "application/epub+zip"
        )
    }

    private fun writeZipFile(file: File, parts: Map<String, String>) {
        FileOutputStream(file).use { fos ->
            ZipOutputStream(fos).use { zos ->
                parts.forEach { (path, content) ->
                    zos.putNextEntry(ZipEntry(path))
                    zos.write(content.toByteArray(Charsets.UTF_8))
                    zos.closeEntry()
                }
            }
        }
    }

    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> "${bytes / (1024 * 1024)} MB"
        }
    }

    private fun escapeHtml(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private fun escapeXml(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    private fun escapeRtf(s: String): String = s
        .replace("\\", "\\\\")
        .replace("{", "\\{")
        .replace("}", "\\}")
}
