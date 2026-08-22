package com.ai.assistance.quro.core.tools

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * 升级版知识库工具（统一 add / search / list / delete）。
 * 在原有 knowledge_add / knowledge_search 之上提供「list」和「delete」能力，并可作为后台可用工具被 AI 直接调用，
 * 实现「升级知识库支持」：AI 能在不打开前台的情况下写入、检索、罗列、删除本地知识库。
 */
class KnowledgeManageTool : QuroTool {
    override val name = "knowledge_manage"
    override val description = "升级版本地知识库：写入(add)/检索(search)/罗列(list)/导入(import)/删除(delete) 应用专属 knowledge_base 目录下的文档。" +
        "参数 {\"action\":\"add|search|list|import|delete\",\"path\":\"add 时的相对路径\",\"content\":\"add 时的内容\",\"append\":false,\"query\":\"search 时的关键词\",\"limit\":8," +
        "\"src\":\"import 时的源文件绝对路径\",\"name\":\"import 时的目标文件名(可选)\",\"target\":\"delete 时要删除的文件相对路径\"}。" +
        "AI 可在后台直接调用以沉淀与检索笔记、规范、领域资料；import 将源文件复制进知识库；delete 删除指定文档。"
    override val parametersJson = """{
        "type":"object",
        "properties":{
            "action":{"type":"string","description":"add=写入 / search=检索 / list=罗列文件 / delete=删除文件"},
            "path":{"type":"string","description":"add 时相对 knowledge_base 的路径，如 规范/编码风格.md"},
            "content":{"type":"string","description":"add 时的文档内容"},
            "append":{"type":"boolean","description":"add 时是否追加，默认 false"},
            "query":{"type":"string","description":"search 时的关键词"},
            "limit":{"type":"integer","description":"search 返回条数，默认 8"},
            "src":{"type":"string","description":"import 时的源文件绝对路径（应用可读，如 quro_uploads 下的文件）"},
            "name":{"type":"string","description":"import 时的目标文件名（可选，默认用源文件名）"},
            "target":{"type":"string","description":"delete 时要删除的文件相对路径（如 test.md 或 subfolder/doc.txt）"}
        },
        "required":["action"]
    }"""
    override fun run(context: Context, arguments: String): String {
        val jo = JSONObject(arguments)
        return when (jo.optString("action", "").trim().lowercase()) {
            "add" -> KnowledgeAddTool().run(context, arguments)
            "search" -> KnowledgeSearchTool().run(context, arguments)
            "list" -> listDocs(context)
            "import" -> importDoc(context, jo.optString("src", ""), jo.optString("name", ""))
            "delete" -> deleteDoc(context, jo.optString("target", ""))
            else -> "未知 action: ${jo.optString("action")}（支持 add / search / list / import / delete）"
        }
    }

    private fun listDocs(context: Context): String {
        val kb = QuroKnowledgeFiles.dir(context)
        if (!kb.exists()) kb.mkdirs()
        val files = kb.walkTopDown()
            .filter { it.isFile && it.extension.lowercase() in setOf("md", "txt", "json", "csv", "docx", "xlsx", "pptx") }
            .toList()
        if (files.isEmpty()) return "知识库为空（目录：${kb.absolutePath}）。可用 action=add 写入文档，或 action=import 导入 Markdown/CSV/Office(WPS) 文档。"
        return buildString {
            append("知识库共 ${files.size} 个文档：\n")
            files.sortedBy { it.relativeTo(kb).path }.forEach {
                append("- ${it.relativeTo(kb).path} (${it.length()}B)\n")
            }
        }
    }

    private fun importDoc(context: Context, src: String, name: String): String {
        if (src.isBlank()) return "import 需要提供 src（应用可读的源文件绝对路径，如 quro_uploads 下的文件），或直接在「知识库」界面点导入按钮选择文件。"
        val s = File(src)
        if (!s.exists() || !s.isFile) return "import 源文件不存在：$src"
        val kb = QuroKnowledgeFiles.dir(context)
        kb.mkdirs()
        val targetName = if (name.isBlank()) s.name else name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val target = File(kb, targetName)
        target.parentFile?.mkdirs()
        s.copyTo(target, overwrite = true)
        return "已导入文档：${target.absolutePath}（${target.length()}B）。可在「知识库」界面查看，或 action=search 检索。"
    }

    private fun deleteDoc(context: Context, target: String): String {
        if (target.isBlank()) return "delete 需要提供 target（要删除的文件相对路径，如 test.md 或 subfolder/doc.txt）。可先用 action=list 查看文件列表。"
        val kb = QuroKnowledgeFiles.dir(context)
        if (!kb.exists()) return "知识库目录不存在"
        val f = File(kb, target.trimStart('/'))
        if (!f.exists()) return "文件不存在：$target"
        if (!f.path.startsWith(kb.path)) return "安全限制：只能删除知识库目录内的文件"
        return if (f.delete()) {
            "已删除文档：$target"
        } else {
            "删除失败：$target（可能文件被占用或权限不足）"
        }
    }
}
