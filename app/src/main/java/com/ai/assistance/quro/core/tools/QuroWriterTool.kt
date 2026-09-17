package com.ai.assistance.quro.core.tools

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * writer：结构化文件编辑器（对应「其他 AI」文件与编辑维的 writer 能力）。
 * 与 write_file（整体覆盖）互补，支持在文件内做定点插入/替换/删除/追加。
 * 纯 Kotlin，无外部依赖。
 */
class QuroWriterTool : QuroTool {
    override val name: String = "writer"

    override val description: String =
        "结构化文件编辑（与 write_file 整体覆盖互补）：在文件内定点插入/替换/删除。" +
            "action 取值：append(末尾追加) / prepend(开头插入) / insert(在指定行或锚点之后插入) / " +
            "replace(首次替换) / replace_all(全部替换) / delete_range(删除行区间)。" +
            "path 为目标文件路径；content 为要写入的内容；at_line 为 1 基行号（insert 用）；" +
            "anchor 为插入参照字符串（insert 用，在包含该行之后插入）；find 为正则（replace/replace_all 用）；" +
            "old/new 为字面替换对（replace/replace_all 用）；start_line/end_line 为 delete_range 的行区间（含）。" +
            "返回变更摘要（影响行数/字节数）。"

    override val parametersJson: String = """{
      "type":"object",
      "properties":{
        "action":{"type":"string","enum":["append","prepend","insert","replace","replace_all","delete_range"],"description":"编辑动作"},
        "path":{"type":"string","description":"目标文件路径"},
        "content":{"type":"string","description":"要写入/插入的内容（append/prepend/insert/replace 适用）"},
        "at_line":{"type":"integer","description":"insert 动作：在第几行之后插入（1 基，0 表示文件开头）"},
        "anchor":{"type":"string","description":"insert 动作：在包含该字符串的行之后插入"},
        "find":{"type":"string","description":"replace/replace_all 动作：用于匹配的正则"},
        "old":{"type":"string","description":"replace/replace_all 动作：被替换的字面串"},
        "new":{"type":"string","description":"replace/replace_all 动作：替换成的字面串"},
        "start_line":{"type":"integer","description":"delete_range 起点行（1 基，含）"},
        "end_line":{"type":"integer","description":"delete_range 终点行（1 基，含）"},
        "encoding":{"type":"string","description":"文件编码（默认 UTF-8）"}
      },
      "required":["action","path"]
    }"""

    override fun run(context: Context, arguments: String): String {
        return try {
            val args = JSONObject(arguments)
            val action = args.optString("action", "").lowercase()
            val path = args.optString("path", "")
            if (path.isBlank()) return "参数 path 为空"
            val file = File(path)
            if (!file.exists()) return "文件不存在：$path"
            if (!file.isFile) return "不是普通文件：$path"

            val encoding = args.optString("encoding", "UTF-8").ifBlank { "UTF-8" }
            val text = file.readText(charset(encoding))
            val lines = text.split("\n")
            val content = args.optString("content", "")

            val (newLines, summary) = when (action) {
                "append" -> (lines + content.split("\n")) to "追加 ${content.lines().size} 行到末尾"
                "prepend" -> (content.split("\n") + lines) to "在开头插入 ${content.lines().size} 行"
                "insert" -> {
                    val atLine = args.optInt("at_line", -1)
                    val anchor = args.optString("anchor", "")
                    val toInsert = content.split("\n")
                    when {
                        atLine >= 0 -> {
                            val idx = atLine.coerceAtMost(lines.size)
                            (lines.take(idx) + toInsert + lines.drop(idx)) to "在第 $atLine 行后插入 ${toInsert.size} 行"
                        }
                        anchor.isNotBlank() -> {
                            val i = lines.indexOfFirst { it.contains(anchor) }
                            if (i < 0) return "未找到 anchor：$anchor"
                            (lines.take(i + 1) + toInsert + lines.drop(i + 1)) to "在 anchor 行后插入 ${toInsert.size} 行"
                        }
                        else -> return "insert 动作需要 at_line 或 anchor 参数"
                    }
                }
                "replace" -> {
                    val old = args.optString("old", "")
                    val find = args.optString("find", "")
                    val new = args.optString("new", "")
                    if (old.isNotBlank()) {
                        val joined = lines.joinToString("\n")
                        if (!joined.contains(old)) return "未找到 old：$old"
                        (joined.replaceFirst(old, new).split("\n")) to "替换首次出现的字面串"
                    } else if (find.isNotBlank()) {
                        var n = 0
                        val sb = StringBuilder()
                        val re = Regex(find)
                        lines.forEach { l ->
                            val m = re.find(l)
                            if (m != null) { sb.append(l.replaceRange(m.range, new)).append("\n"); n++ }
                            else sb.append(l).append("\n")
                        }
                        if (n == 0) return "正则未匹配：$find"
                        (sb.toString().removeSuffix("\n").split("\n")) to "按正则替换 $n 处"
                    } else return "replace 动作需要 old 或 find 参数"
                }
                "replace_all" -> {
                    val old = args.optString("old", "")
                    val find = args.optString("find", "")
                    val new = args.optString("new", "")
                    if (old.isNotBlank()) {
                        val n = lines.sumOf { it.split(old).size - 1 }
                        if (n == 0) return "未找到 old：$old"
                        (lines.joinToString("\n") { it.replace(old, new) }.split("\n")) to "替换全部 $n 处字面串"
                    } else if (find.isNotBlank()) {
                        val re = Regex(find)
                        var n = 0
                        val out = lines.map { l ->
                            val c = re.findAll(l).count(); n += c
                            re.replace(l, new)
                        }
                        if (n == 0) return "正则未匹配：$find"
                        out to "按正则替换全部 $n 处"
                    } else return "replace_all 动作需要 old 或 find 参数"
                }
                "delete_range" -> {
                    val s = args.optInt("start_line", -1)
                    val e = args.optInt("end_line", -1)
                    if (s < 1 || e < s) return "delete_range 需要合法的 start_line/end_line（1 基，start<=end）"
                    val si = (s - 1).coerceAtMost(lines.size)
                    val ei = e.coerceAtMost(lines.size)
                    (lines.take(si) + lines.drop(ei)) to "删除第 $s-$e 行（共 ${ei - si} 行）"
                }
                else -> return "不支持的 action：$action（支持 append/prepend/insert/replace/replace_all/delete_range）"
            }

            val newText = newLines.joinToString("\n")
            file.writeText(newText, charset(encoding))
            "${summary}；文件现 ${newLines.size} 行 / ${newText.toByteArray(charset(encoding)).size} 字节：$path"
        } catch (e: Exception) {
            "writer 执行失败：${e.message}"
        }
    }
}
