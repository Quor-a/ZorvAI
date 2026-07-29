package com.ai.assistance.quro.core.tools

import android.content.Context
import com.ai.assistance.quro.core.memory.QuroMemoryEntry
import com.ai.assistance.quro.core.memory.QuroMemoryRepository
import org.json.JSONObject
import java.util.UUID

/**
 * 记忆库工具（原创）：让 AI 能「自动沉淀」长期记忆（用户偏好 / 事实 / 约定 / 项目背景），
 * 而不是依赖用户手动维护。配合系统在提示词中注入已有记忆，AI 跨会话「记得」用户。
 *
 * - [QuroMemorySaveTool]    保存一条记忆（AI 应在用户透露持久信息时主动调用）。
 * - [QuroMemoryListTool]   列出全部已保存记忆。
 * - [QuroMemorySearchTool] 按关键词检索记忆。
 * - [QuroMemoryDeleteTool] 删除某条记忆。
 */
class QuroMemorySaveTool : QuroTool {
    override val name = "memory_save"
    override val description =
        "保存一条长期记忆（用户偏好、事实、约定、项目背景等）。当用户透露了值得跨会话记住的信息时，应主动调用本工具「自动保存」。" +
            "参数：{\"title\":\"可选标题\",\"content\":\"记忆内容(必填)\",\"group\":\"分组如 偏好/工作/项目(可选)\",\"tags\":\"标签数组(可选)\"}。"
    override val parametersJson = """{
        "type":"object",
        "properties":{
            "title":{"type":"string","description":"可选标题"},
            "content":{"type":"string","description":"记忆内容，必填"},
            "group":{"type":"string","description":"分组，如 偏好/工作/项目"},
            "tags":{"type":"array","items":{"type":"string"},"description":"标签数组"}
        },
        "required":["content"]
    }"""

    override fun run(context: Context, arguments: String): String {
        val obj = runCatching { JSONObject(arguments) }.getOrElse { return "参数不是合法 JSON：$arguments" }
        val content = obj.optString("content", "").trim()
        if (content.isEmpty()) return "缺少 content 参数（记忆内容）。"
        val title = obj.optString("title", "").trim()
        val group = obj.optString("group", "").trim()
        val tags = mutableListOf<String>()
        obj.optJSONArray("tags")?.let { a -> for (i in 0 until a.length()) tags.add(a.optString(i, "")) }
        val entry = QuroMemoryEntry(
            id = UUID.randomUUID().toString(),
            title = title,
            content = content,
            group = group,
            tags = tags.filter { it.isNotBlank() },
        )
        QuroMemoryRepository(context).add(entry)
        return "已保存记忆${if (title.isNotBlank()) "（${title}）" else ""}。"
    }
}

class QuroMemoryListTool : QuroTool {
    override val name = "memory_list"
    override val description = "列出已保存的全部长期记忆。参数为空 {}。"
    override val parametersJson = """{"type":"object","properties":{}}"""

    override fun run(context: Context, arguments: String): String {
        val all = QuroMemoryRepository(context).loadAll()
        if (all.isEmpty()) return "当前没有已保存的记忆。"
        val sb = StringBuilder("已保存的记忆：\n")
        all.forEach { m ->
            sb.append("- ")
            if (m.group.isNotBlank()) sb.append("[${m.group}] ")
            if (m.tags.isNotEmpty()) sb.append("(${m.tags.joinToString(",")}) ")
            if (m.title.isNotBlank()) sb.append("${m.title}：")
            sb.append(m.content).append("\n")
        }
        return sb.toString().trim()
    }
}

class QuroMemorySearchTool : QuroTool {
    override val name = "memory_search"
    override val description = "按关键词检索已保存的记忆（匹配内容/标题/标签/分组）。参数：{\"query\":\"关键词\"}。"
    override val parametersJson = """{
        "type":"object",
        "properties":{"query":{"type":"string","description":"检索关键词"}},
        "required":["query"]
    }"""

    override fun run(context: Context, arguments: String): String {
        val q = runCatching { JSONObject(arguments) }.getOrElse { return "参数不是合法 JSON：$arguments" }
            .optString("query", "").trim()
        if (q.isEmpty()) return "缺少 query 参数。"
        val res = QuroMemoryRepository(context).search(q)
        if (res.isEmpty()) return "没有匹配「$q」的记忆。"
        val sb = StringBuilder("匹配「$q」的记忆：\n")
        res.forEach { sb.append("- ${it.content}\n") }
        return sb.toString().trim()
    }
}

class QuroMemoryDeleteTool : QuroTool {
    override val name = "memory_delete"
    override val description = "删除一条记忆（按内容关键词匹配第一条）。参数：{\"query\":\"要删除的记忆内容关键词\"}。"
    override val parametersJson = """{
        "type":"object",
        "properties":{"query":{"type":"string","description":"要删除的记忆内容关键词"}},
        "required":["query"]
    }"""

    override fun run(context: Context, arguments: String): String {
        val q = runCatching { JSONObject(arguments) }.getOrElse { return "参数不是合法 JSON：$arguments" }
            .optString("query", "").trim()
        if (q.isEmpty()) return "缺少 query 参数。"
        val repo = QuroMemoryRepository(context)
        val target = repo.search(q).firstOrNull() ?: return "没有匹配「$q」的记忆，无法删除。"
        repo.delete(target.id)
        return "已删除记忆：${target.content.take(40)}"
    }
}
