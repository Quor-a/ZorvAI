package com.ai.assistance.quro.core.tools

import android.content.Context
import android.os.Environment
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * aiWPS 文档生成工具（v128 新增，v135 增强为「完整 WPS」生成能力）。
 * 生成 WPS / Microsoft Office 兼容的真实文档（.docx / .xlsx / .pptx），
 * 完全基于 OOXML 规范自研构造 ZIP 包，不引入任何第三方库（Apache-2.0 自有实现）。
 *
 * 增强（v135）：
 *  - docx：支持 `**加粗**` 与表格（`| 单元格 | 单元格 |` 连续行即表格）。
 *  - xlsx：支持多工作表（以 `### 表名` 分页）。
 *  - pptx：支持多页（以 `---` 分页，每页首行作标题、其余作要点）。
 *
 * AI 可在对话框中直接调用，用户也可通过对话框「+工具 → 文档生成」按钮使用（不打开前台）。
 */
class AiwpsCreateTool : QuroTool {
    override val name = "aiwps_create"
    override val description = "生成文档（.docx/.xlsx/.pptx/.pdf/.md/.txt/.csv/.html），自研构造、无需外部依赖，后台生成真实可打开的文件。" +
        "参数 {\"type\":\"docx|xlsx|pptx|pdf|md|txt|csv|html\",\"title\":\"标题(可选)\",\"content\":\"正文\"," +
        "\"filename\":\"可选文件名(不含扩展名)\"}。" +
        "docx：按换行分段；`**加粗**` 渲染为粗体；连续 `| a | b |` 行渲染为表格。" +
        "xlsx：按换行分行、制表/逗号分列；`### 表名` 起新工作表。" +
        "pptx：每页首行作标题、其余作要点；`---` 分页生成多页。" +
        "pdf：自研最小文本 PDF（逐行排版，零依赖）；md/txt/csv：纯文本直写；html：包最简 HTML 文档。"
    override val parametersJson = """{
        "type":"object",
        "properties":{
            "type":{"type":"string","description":"文档类型：docx / xlsx / pptx / pdf / md / txt / csv / html"},
            "title":{"type":"string","description":"标题（pptx 用作首页标题；docx/xlsx 写入文档属性）"},
            "content":{"type":"string","description":"正文：docx 支持 `**加粗**` 与 `| 表 |`；xlsx 支持 `### 表名` 多表；pptx 支持 `---` 多页"},
            "filename":{"type":"string","description":"可选文件名（不含扩展名），默认按类型+时间戳"}
        },
        "required":["type","content"]
    }"""

    override fun run(context: Context, arguments: String): String {
        val jo = JSONObject(arguments)
        val type = jo.optString("type", "").trim().lowercase()
        val content = jo.optString("content", "")
        val title = jo.optString("title", "").ifBlank { "${type}_${System.currentTimeMillis()}" }
        val baseName = (jo.optString("filename", "").ifBlank { null })
            ?: "${type}_${System.currentTimeMillis()}"

        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "QuroDocs")
        if (!dir.exists()) dir.mkdirs()
        val safeName = baseName.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(80)

        val supported = setOf("docx", "xlsx", "pptx", "pdf", "md", "txt", "csv", "html")
        if (type !in supported) {
            return "aiwps_create 的 type 必须是 docx / xlsx / pptx / pdf / md / txt / csv / html"
        }
        if (content.isBlank()) return "aiwps_create 缺少 content 正文"

        return try {
            when (type) {
                "docx" -> {
                    val file = File(dir, "$safeName.docx")
                    writeZip(file, buildDocx(content, title))
                    okMsg("docx", file)
                }
                "xlsx" -> {
                    val file = File(dir, "$safeName.xlsx")
                    writeZip(file, buildXlsx(content, title))
                    okMsg("xlsx", file)
                }
                "pptx" -> {
                    val file = File(dir, "$safeName.pptx")
                    writeZip(file, buildPptx(content, title))
                    okMsg("pptx", file)
                }
                "pdf" -> {
                    val file = File(dir, "$safeName.pdf")
                    file.writeBytes(buildPdf(content, title))
                    okMsg("pdf", file)
                }
                "html" -> {
                    val file = File(dir, "$safeName.html")
                    val body = content.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                        .replace("\n", "<br>\n")
                    file.writeText("<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>${xmlEsc(title)}</title></head><body>\n$body\n</body></html>")
                    okMsg("html", file)
                }
                else -> { // md / txt / csv：纯文本直写
                    val file = File(dir, "$safeName.$type")
                    file.writeText(content)
                    okMsg(type, file)
                }
            }
        } catch (e: Exception) {
            "生成失败：${e.message}"
        }
    }

    private fun okMsg(type: String, file: File): String {
        val kb = file.length() / 1024.0
        return "已生成 $type 文档：${file.absolutePath}（${"%.1f".format(kb)} KB），可在应用内文档查看器或 WPS / Office 直接打开。"
    }

    /** 自研最小文本 PDF（逐行排版，零依赖）。每行独立 Tj，换行下移 16pt。 */
    private fun buildPdf(content: String, title: String): ByteArray {
        val lines = content.lines().filter { it.isNotBlank() }
        val pageContent = buildString {
            append("BT\n/F1 12 Tf\n50 800 Td\n")
            lines.forEach { line ->
                append("(").append(pdfEsc(line.take(95))).append(") Tj\n0 -16 Td\n")
            }
            append("ET")
        }
        val objects = listOf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>",
            "<< /Length ${pageContent.toByteArray(Charsets.ISO_8859_1).size} >>\nstream\n$pageContent\nendstream",
            "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"
        )
        val out = StringBuilder("%PDF-1.4\n")
        val offsets = mutableListOf<Int>()
        objects.forEachIndexed { i, obj ->
            offsets.add(out.length)
            out.append("${i + 1} 0 obj\n").append(obj).append("\nendobj\n")
        }
        val xrefStart = out.length
        out.append("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
        offsets.forEach { off -> out.append(String.format("%010d 00000 n \n", off)) }
        out.append("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xrefStart\n%%EOF")
        return out.toString().toByteArray(Charsets.ISO_8859_1)
    }

    private fun pdfEsc(s: String): String = s
        .replace("\\", "\\\\")
        .replace("(", "\\(")
        .replace(")", "\\)")

    // ───────────────────────────── docx ─────────────────────────────
    private fun buildDocx(content: String, title: String): Map<String, String> {
        val sb = StringBuilder()
        var tableRows: MutableList<List<String>>? = null
        for (line in content.lines()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("|") && trimmed.endsWith("|") && trimmed.count { it == '|' } >= 2) {
                val cells = trimmed.split("|").let { it.subList(1, it.size - 1) }.map { it.trim() }
                if (tableRows == null) tableRows = mutableListOf()
                tableRows!!.add(cells)
            } else {
                if (tableRows != null) { sb.append(tableXml(tableRows)); tableRows = null }
                if (line.isBlank()) continue
                sb.append(paragraphXml(line))
            }
        }
        if (tableRows != null) sb.append(tableXml(tableRows))

        val document = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
  <w:body>${sb}<w:sectPr/></w:body>
</w:document>"""
        return baseParts(
            overrides = listOf(
                "/word/document.xml" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml",
                "/word/_rels/document.xml.rels" to null,
            ),
            body = mapOf(
                "/word/document.xml" to document,
                "/word/_rels/document.xml.rels" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="../docProps/core.xml"/>
</Relationships>""",
            ),
            title = title,
        )
    }

    private fun paragraphXml(line: String): String {
        val parts = line.split("**")
        val runs = parts.mapIndexed { idx, p ->
            val bold = idx % 2 == 1
            val rPr = if (bold) "<w:rPr><w:b/></w:rPr>" else ""
            "<w:r>$rPr<w:t xml:space=\"preserve\">${xmlEsc(p)}</w:t></w:r>"
        }.joinToString("")
        return "<w:p>$runs</w:p>"
    }

    private fun tableXml(rows: List<List<String>>): String {
        val rs = rows.joinToString("") { row ->
            val cells = row.joinToString("") { c ->
                "<w:tc><w:tcPr/><w:p><w:r><w:t xml:space=\"preserve\">${xmlEsc(c)}</w:t></w:r></w:p></w:tc>"
            }
            "<w:tr>$cells</w:tr>"
        }
        return "<w:tbl><w:tblPr/><w:tblGrid/><w:tblLook/>$rs</w:tbl>"
    }

    // ───────────────────────────── xlsx ─────────────────────────────
    private fun buildXlsx(content: String, title: String): Map<String, String> {
        val rawLines = content.lines().filter { it.isNotBlank() }
        val sheets = mutableListOf<Pair<String, List<List<String>>>>()
        var curName = "Sheet1"
        var curRows = mutableListOf<List<String>>()
        for (line in rawLines) {
            if (line.startsWith("###")) {
                if (curRows.isNotEmpty() || sheets.isEmpty()) sheets.add(curName to curRows)
                curName = line.removePrefix("###").trim().ifBlank { "Sheet${sheets.size + 1}" }
                curRows = mutableListOf()
            } else {
                curRows.add(line.split('\t', ',').map { it.trim() })
            }
        }
        if (curRows.isNotEmpty() || sheets.isEmpty()) sheets.add(curName to curRows)
        if (sheets.isEmpty()) sheets.add("Sheet1" to emptyList())

        val overrides = mutableListOf<Pair<String, String?>>(
            "/xl/workbook.xml" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml",
            "/xl/_rels/workbook.xml.rels" to null,
        )
        val body = mutableMapOf<String, String>()
        val sheetXmls = sheets.mapIndexed { i, (name, rows) ->
            val sid = i + 1
            overrides.add("/xl/worksheets/sheet$sid.xml" to "application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml")
            val rowsXml = rows.mapIndexed { ri, row ->
                val cells = row.mapIndexed { ci, c ->
                    val col = colName(ci)
                    """<c r="$col${ri + 1}" t="inlineStr"><is><t xml:space="preserve">${xmlEsc(c)}</t></is></c>"""
                }.joinToString("")
                "<row r=\"${ri + 1}\">$cells</row>"
            }.joinToString("")
            val sheetXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>$rowsXml</sheetData></worksheet>"""
            sid to sheetXml
        }
        val workbook = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
  <sheets>${sheets.mapIndexed { i, (n, _) -> "<sheet name=\"${xmlEsc(n.take(31))}\" sheetId=\"${i + 1}\" r:id=\"rId${i + 1}\"/>" }.joinToString("")}</sheets>
</workbook>"""
        val wbRels = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
${sheets.mapIndexed { i, _ -> "<Relationship Id=\"rId${i + 1}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet${i + 1}.xml\"/>" }.joinToString("\n  ")}
  <Relationship Id="rId${sheets.size + 1}" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="../docProps/core.xml"/>
</Relationships>"""
        body["/xl/workbook.xml"] = workbook
        body["/xl/_rels/workbook.xml.rels"] = wbRels
        sheetXmls.forEach { (sid, xml) -> body["/xl/worksheets/sheet$sid.xml"] = xml }
        return baseParts(overrides = overrides, body = body, title = title)
    }

    // ───────────────────────────── pptx ─────────────────────────────
    private fun buildPptx(content: String, title: String): Map<String, String> {
        val chunks = content.split("---").map { it.trim() }.filter { it.isNotBlank() }
            .ifEmpty { listOf(title) }
        val slides = chunks.map { chunk ->
            val lines = chunk.lines().filter { it.isNotBlank() }
            val t = lines.firstOrNull() ?: title
            val body = if (lines.size > 1) lines.drop(1) else emptyList()
            t to body
        }

        val NS = "xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\""
        fun slideXml(t: String, body: List<String>): String {
            val titleXml = """<p:sp><p:nvSpPr><p:cNvPr id="2" name="Title"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr><p:spPr/><p:txBody><a:bodyPr/><a:lstStyle/><a:p><a:r><a:rPr lang="zh-CN"/><a:t>${xmlEsc(t.ifBlank { "Quro AI" })}</a:t></a:r></a:p></p:txBody></p:sp>"""
            val bodyXml = body.joinToString("") { b -> "<a:p><a:r><a:rPr lang=\"zh-CN\"/><a:t>${xmlEsc(b)}</a:t></a:r></a:p>" }
            return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<p:sld $NS>
  <p:cSld><p:spTree>
    <p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>
    <p:grpSpPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="0" cy="0"/><a:chOff x="0" y="0"/><a:chExt cx="0" cy="0"/></a:xfrm></p:grpSpPr>
    $titleXml
    <p:sp><p:nvSpPr><p:cNvPr id="3" name="Body"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr><p:spPr/><p:txBody><a:bodyPr/><a:lstStyle/>$bodyXml</p:txBody></p:sp>
  </p:spTree></p:cSld>
  <p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr>
</p:sld>"""
        }
        val slideLayout = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<p:sldLayout $NS type="title" preserve="1"><p:cSld name="Title Slide"><p:spTree>
  <p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>
  <p:grpSpPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="0" cy="0"/><a:chOff x="0" y="0"/><a:chExt cx="0" cy="0"/></a:xfrm></p:grpSpPr>
</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sldLayout>"""
        val slideMaster = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<p:sldMaster $NS><p:cSld><p:bg/><p:spTree>
  <p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>
  <p:grpSpPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="0" cy="0"/><a:chOff x="0" y="0"/><a:chExt cx="0" cy="0"/></a:xfrm></p:grpSpPr>
</p:spTree></p:cSld>
  <p:clrMap bg1="lt1" tx1="dk1" bg2="lt2" tx2="dk2" accent1="accent1" accent2="accent2" accent3="accent3" accent4="accent4" accent5="accent5" accent6="accent6" hlink="hlink" folHlink="folHlink"/>
</p:sldMaster>"""
        val presentation = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<p:presentation $NS>
  <p:sldMasterIdLst><p:sldMasterId id="2147483648" r:id="rId${slides.size + 1}"/></p:sldMasterIdLst>
  <p:sldIdLst>${slides.mapIndexed { i, _ -> "<p:sldId id=\"${256 + i}\" r:id=\"rId${i + 1}\"/>" }.joinToString("")}</p:sldIdLst>
  <p:sldSz cx="9144000" cy="6858000"/>
  <p:notesSz cx="6858000" cy="9144000"/>
</p:presentation>"""
        val theme = themeXml()
        val overrides = mutableListOf<Pair<String, String?>>(
            "/ppt/presentation.xml" to "application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml",
            "/ppt/slides/slide1.xml" to "application/vnd.openxmlformats-officedocument.presentationml.slide+xml",
            "/ppt/slideLayouts/slideLayout1.xml" to "application/vnd.openxmlformats-officedocument.presentationml.slideLayout+xml",
            "/ppt/slideMasters/slideMaster1.xml" to "application/vnd.openxmlformats-officedocument.presentationml.slideMaster+xml",
            "/ppt/theme/theme1.xml" to "application/vnd.openxmlformats-officedocument.theme+xml",
            "/ppt/_rels/presentation.xml.rels" to null,
            "/ppt/slides/_rels/slide1.xml.rels" to null,
            "/ppt/slideLayouts/_rels/slideLayout1.xml.rels" to null,
            "/ppt/slideMasters/_rels/slideMaster1.xml.rels" to null,
        )
        val body = mutableMapOf<String, String>(
            "/ppt/presentation.xml" to presentation,
            "/ppt/slides/slide1.xml" to slideXml(slides[0].first, slides[0].second),
            "/ppt/slideLayouts/slideLayout1.xml" to slideLayout,
            "/ppt/slideMasters/slideMaster1.xml" to slideMaster,
            "/ppt/theme/theme1.xml" to theme,
            "/ppt/_rels/presentation.xml.rels" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide" Target="slides/slide1.xml"/>
  <Relationship Id="rId${slides.size + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster" Target="slideMasters/slideMaster1.xml"/>
</Relationships>""",
            "/ppt/slides/_rels/slide1.xml.rels" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout" Target="../slideLayouts/slideLayout1.xml"/>
</Relationships>""",
            "/ppt/slideLayouts/_rels/slideLayout1.xml.rels" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster" Target="../slideMasters/slideMaster1.xml"/>
</Relationships>""",
            "/ppt/slideMasters/_rels/slideMaster1.xml.rels" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/theme" Target="../theme/theme1.xml"/>
</Relationships>""",
        )
        // 多页：追加 slide2..N 及对应 rels
        for (i in 1 until slides.size) {
            val sid = i + 1
            overrides.add("/ppt/slides/slide$sid.xml" to "application/vnd.openxmlformats-officedocument.presentationml.slide+xml")
            overrides.add("/ppt/slides/_rels/slide$sid.xml.rels" to null)
            body["/ppt/slides/slide$sid.xml"] = slideXml(slides[i].first, slides[i].second)
            body["/ppt/slides/_rels/slide$sid.xml.rels"] = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout" Target="../slideLayouts/slideLayout1.xml"/>
</Relationships>"""
        }
        return baseParts(overrides = overrides, body = body, title = title)
    }

    private fun themeXml(): String {
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<a:theme xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" name="Quro Theme">
  <a:themeElements>
    <a:clrScheme name="Quro">
      <a:dk1><a:sysClr val="windowText" lastClr="000000"/></a:dk1>
      <a:lt1><a:sysClr val="window" lastClr="FFFFFF"/></a:lt1>
      <a:dk2><a:srgbClr val="0B1020"/></a:dk2>
      <a:lt2><a:srgbClr val="E7E6E6"/></a:lt2>
      <a:accent1><a:srgbClr val="22D3EE"/></a:accent1>
      <a:accent2><a:srgbClr val="ED7D31"/></a:accent2>
      <a:accent3><a:srgbClr val="A855F7"/></a:accent3>
      <a:accent4><a:srgbClr val="FFC000"/></a:accent4>
      <a:accent5><a:srgbClr val="5B9BD5"/></a:accent5>
      <a:accent6><a:srgbClr val="70AD47"/></a:accent6>
      <a:hlink><a:srgbClr val="0563C1"/></a:hlink>
      <a:folHlink><a:srgbClr val="954F72"/></a:folHlink>
    </a:clrScheme>
    <a:fontScheme name="Quro">
      <a:majorFont><a:latin typeface="Calibri Light"/><a:ea typeface=""/><a:cs typeface=""/></a:majorFont>
      <a:minorFont><a:latin typeface="Calibri"/><a:ea typeface=""/><a:cs typeface=""/></a:minorFont>
    </a:fontScheme>
    <a:fmtScheme name="Quro">
      <a:fillStyleLst><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:fillStyleLst>
      <a:lnStyleLst><a:ln w="6350" cap="flat" cmpd="sng" algn="ctr"><a:solidFill><a:schemeClr val="phClr"/></a:solidFill><a:prstDash val="solid"/></a:ln></a:lnStyleLst>
      <a:effectStyleLst><a:effectStyle><a:effectLst/></a:effectStyle></a:effectStyleLst>
      <a:bgFillStyleLst><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:bgFillStyleLst>
    </a:fmtScheme>
  </a:themeElements>
</a:theme>"""
    }

    private fun baseParts(
        overrides: List<Pair<String, String?>>,
        body: Map<String, String>,
        title: String,
    ): Map<String, String> {
        val ct = buildString {
            append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
            append("\n<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">")
            append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>")
            append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>")
            overrides.forEach { (part, ctType) ->
                if (ctType != null) append("<Override PartName=\"$part\" ContentType=\"$ctType\"/>")
            }
            append("<Override PartName=\"/docProps/core.xml\" ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\"/>")
            append("<Override PartName=\"/docProps/app.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.extended-properties+xml\"/>")
            append("</Types>")
        }
        val rels = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="${officeTarget(overrides)}"/>
  <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/>
  <Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/extended-properties" Target="docProps/app.xml"/>
</Relationships>"""
        val core = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:dcterms="http://purl.org/dc/terms/" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
  <dc:title>${xmlEsc(title)}</dc:title>
  <dc:creator>Quro AI</dc:creator>
  <cp:lastModifiedBy>Quro AI</cp:lastModifiedBy>
</cp:coreProperties>"""
        val app = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Properties xmlns="http://schemas.openxmlformats.org/officeDocument/2006/extended-properties">
  <Application>Quro AI</Application>
</Properties>"""
        return body + mapOf(
            "/[Content_Types].xml" to ct,
            "/_rels/.rels" to rels,
            "/docProps/core.xml" to core,
            "/docProps/app.xml" to app,
        )
    }

    private fun officeTarget(overrides: List<Pair<String, String?>>): String {
        return overrides.firstNotNullOfOrNull { (part, _) ->
            if (!part.startsWith("/docProps")) part else null
        } ?: "/word/document.xml"
    }

    private fun writeZip(file: File, parts: Map<String, String>) {
        FileOutputStream(file).use { fos ->
            ZipOutputStream(fos).use { zos ->
                parts.forEach { (path, xml) ->
                    zos.putNextEntry(ZipEntry(path.substring(1)))
                    zos.write(xml.toByteArray(Charsets.UTF_8))
                    zos.closeEntry()
                }
            }
        }
    }

    private fun xmlEsc(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    private fun colName(idx: Int): String {
        var n = idx
        var s = ""
        do {
            s = ('A' + (n % 26)) + s
            n = n / 26 - 1
        } while (n >= 0)
        return s
    }
}
