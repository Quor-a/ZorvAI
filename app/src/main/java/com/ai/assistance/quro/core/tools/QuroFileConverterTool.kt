package com.ai.assistance.quro.core.tools

import android.content.Context
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File

/**
 * file_converter：在常见文本格式间互转（对应「其他 AI」扩展生态里点名的 file_converter 能力）。
 *
 * 支持组合：csv↔json、markdown→html、json→json(美化)。
 * 纯 Kotlin 实现，无外部二进制依赖（设备侧无 ffmpeg 也不影响本工具）。
 *
 * input 可以是文本内容，也可以是文件路径（自动读取文件内容后再转换）。
 * 可选 output_path：填写则把结果写入该文件并返回路径，否则直接返回转换后文本。
 */
class QuroFileConverterTool : QuroTool {
    override val name: String = "file_converter"

    override val description: String =
        "格式转换：在 csv/json/markdown/html 之间互转（csv↔json、markdown→html、json 美化）。" +
            "input 可以是待转换的文本，也可以是文件路径（自动读取）。" +
            "from/to 取值：csv|json|markdown|html。" +
            "可选 output_path：填写则把结果写入该文件并返回路径，否则直接返回转换后文本。"

    override val parametersJson: String = """{
      "type": "object",
      "properties": {
        "input": {"type":"string","description":"待转换的文本或文件路径"},
        "from": {"type":"string","enum":["csv","json","markdown","html"],"description":"源格式"},
        "to": {"type":"string","enum":["csv","json","markdown","html"],"description":"目标格式"},
        "output_path": {"type":"string","description":"可选：结果写入此路径而非返回文本"}
      },
      "required": ["input","from","to"]
    }"""

    override fun run(context: Context, arguments: String): String {
        return try {
            val args = JSONObject(arguments)
            val input = args.optString("input", "")
            val from = args.optString("from", "").lowercase()
            val to = args.optString("to", "").lowercase()
            val outPath = args.optString("output_path", "").takeIf { it.isNotBlank() }

            if (input.isBlank()) return "参数 input 为空"
            if (from !in SUPPORTED || to !in SUPPORTED) {
                return "不支持的格式：from=$from to=$to（支持 $SUPPORTED）"
            }

            val raw = if (looksLikePath(input)) File(input).readText() else input
            val result = convert(raw, from, to)
                ?: return "暂不支持的转换组合：$from → $to（当前支持 csv↔json、markdown→html、json 美化）"

            if (outPath != null) {
                val f = File(outPath)
                f.parentFile?.mkdirs()
                f.writeText(result)
                return "已写入：${f.absolutePath}（${result.length} 字符）"
            }
            result
        } catch (e: JSONException) {
            "参数解析失败：${e.message}"
        } catch (e: Exception) {
            "转换失败：${e.message}"
        }
    }

    private fun looksLikePath(s: String): Boolean {
        if (s.length > 4096) return false
        if (!(s.startsWith("/") || Regex("^[a-zA-Z]:[/\\\\]").containsMatchIn(s) ||
                    s.startsWith("./") || s.startsWith("../"))
        ) return false
        val f = File(s)
        return f.canRead() && f.isFile
    }

    private fun convert(raw: String, from: String, to: String): String? {
        val key = "$from->$to"
        return when (key) {
            "csv->json" -> csvToJson(raw)
            "json->csv" -> jsonToCsv(raw)
            "markdown->html" -> markdownToHtml(raw)
            "json->json" -> prettyJson(raw)
            else -> null
        }
    }

    // ───────────── csv ↔ json ─────────────

    private fun csvToJson(csv: String): String {
        val lines = csv.lines().map { it.trim() }.filter { it.isNotBlank() }
        if (lines.isEmpty()) return "[]"
        val headers = lines[0].split(",").map { it.trim() }
        val arr = JSONArray()
        for (i in 1 until lines.size) {
            val vals = lines[i].split(",").map { it.trim() }
            val obj = JSONObject()
            headers.forEachIndexed { idx, h ->
                obj.put(h, inferType(vals.getOrNull(idx) ?: ""))
            }
            arr.put(obj)
        }
        return arr.toString(2)
    }

    private fun jsonToCsv(json: String): String {
        val arr = JSONArray(json)
        if (arr.length() == 0) return ""
        val headers = LinkedHashSet<String>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            o.keys().forEach { headers.add(it) }
        }
        val sb = StringBuilder()
        sb.append(headers.joinToString(",")).append("\n")
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            sb.append(headers.joinToString(",") { k -> o.optString(k, "") }).append("\n")
        }
        return sb.toString()
    }

    private fun inferType(v: String): Any = when {
        v.equals("true", true) -> true
        v.equals("false", true) -> false
        v.toLongOrNull() != null -> v.toLong()
        v.toDoubleOrNull() != null -> v.toDouble()
        else -> v
    }

    // ───────────── json 美化 ─────────────

    private fun prettyJson(s: String): String {
        return try {
            JSONObject(s).toString(2)
        } catch (_: Exception) {
            try {
                JSONArray(s).toString(2)
            } catch (_: Exception) {
                throw JSONException("不是合法 JSON")
            }
        }
    }

    // ───────────── markdown → html ─────────────

    private fun markdownToHtml(md: String): String {
        val out = StringBuilder()
        val lines = md.split("\n")
        var inCode = false
        var codeBuf = StringBuilder()
        var inList = false
        for (line in lines) {
            if (line.trim().startsWith("```")) {
                if (!inCode) {
                    inCode = true
                    codeBuf = StringBuilder()
                } else {
                    inCode = false
                    if (inList) { out.append("</ul>\n"); inList = false }
                    out.append("<pre><code>").append(escapeHtml(codeBuf.toString())).append("</code></pre>\n")
                }
                continue
            }
            if (inCode) { codeBuf.append(line).append("\n"); continue }
            when {
                line.startsWith("# ") -> {
                    if (inList) { out.append("</ul>\n"); inList = false }
                    out.append("<h1>").append(inline(line.substring(2))).append("</h1>\n")
                }
                line.startsWith("## ") -> {
                    if (inList) { out.append("</ul>\n"); inList = false }
                    out.append("<h2>").append(inline(line.substring(3))).append("</h2>\n")
                }
                line.startsWith("### ") -> {
                    if (inList) { out.append("</ul>\n"); inList = false }
                    out.append("<h3>").append(inline(line.substring(4))).append("</h3>\n")
                }
                line.startsWith("- ") || line.startsWith("* ") -> {
                    if (!inList) { out.append("<ul>\n"); inList = true }
                    out.append("  <li>").append(inline(line.substring(2))).append("</li>\n")
                }
                line.isBlank() -> {
                    if (inList) { out.append("</ul>\n"); inList = false }
                }
                else -> {
                    if (inList) { out.append("</ul>\n"); inList = false }
                    out.append("<p>").append(inline(line)).append("</p>\n")
                }
            }
        }
        if (inCode) out.append("<pre><code>").append(escapeHtml(codeBuf.toString())).append("</code></pre>\n")
        if (inList) out.append("</ul>\n")
        return out.toString()
    }

    private fun inline(s: String): String {
        return escapeHtml(s)
            .replace(Regex("`([^`]+)`")) { m -> "<code>${m.groupValues[1]}</code>" }
            .replace(Regex("\\*\\*([^*]+)\\*\\*")) { m -> "<strong>${m.groupValues[1]}</strong>" }
            .replace(Regex("\\*([^*]+)\\*")) { m -> "<em>${m.groupValues[1]}</em>" }
    }

    private fun escapeHtml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    companion object {
        private val SUPPORTED = setOf("csv", "json", "markdown", "html")
    }
}
