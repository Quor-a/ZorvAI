package com.ai.assistance.quro.core.tools

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * 节点编辑器工具：AI 直接读写节点流工程（无需打开界面即可操控画布）。
 *
 * 工程以快照 JSON 形式存储在 filesDir/studio/flow/<name>.qne，
 * 与工具中心「节点编辑器」面板共享同一份文件：AI 写入后，面板打开时会自动恢复该工程；
 * 面板保存时也会写回这里。实现「AI 直接操控」而非「必须先打开界面才能编辑」。
 *
 * 用法：
 * - node_editor(action="write", name="pipeline", content="{...快照JSON...}")
 * - node_editor(action="read", name="pipeline")
 * - node_editor(action="list")
 * - node_editor(action="delete", name="pipeline")
 */
class NodeEditorTool : QuroTool {
    override val name = "node_editor"
    override val description = """节点编辑器工具：AI 直接创建/读取/删除节点流工程（.qne 快照 JSON）。

工程存储在 filesDir/studio/flow/<name>.qne，与工具中心「节点编辑器」面板共享同一文件：
- AI 用 write 写入后，面板打开即自动恢复该工程（无需人工操作画布）；
- 面板手动编辑后保存，也写回同一文件，AI 随时可 read 取回最新快照。

快照 JSON 结构（与节点编辑器 node_editor.html 的 __snapshot()/__restore() 一致）：
{ "nodes":[ { "id","type","x","y","label" } ], "edges":[ { "id","from","to" } ] }

操作：
- write：写入/覆盖工程（name + content 快照 JSON）
- read：读取工程快照 JSON
- list：列出所有节点流工程
- delete：删除工程

示例：让 AI「用 node_editor 写一个「数据抓取→清洗→入库」的流程图」，面板打开即可见、可继续编辑。"""
    override val parametersJson = """{
        "type":"object",
        "properties":{
            "action":{"type":"string","description":"操作：write|read|list|delete"},
            "name":{"type":"string","description":"工程名（write/read/delete 时必填）"},
            "content":{"type":"string","description":"节点流快照 JSON（write 时需要）"}
        },
        "required":["action"]
    }"""

    companion object {
        private const val STUDIO = "studio/flow"
        fun getRoot(context: Context): File {
            val dir = File(context.filesDir, STUDIO)
            if (!dir.exists()) dir.mkdirs()
            return dir
        }
        fun fileFor(context: Context, name: String): File {
            val safe = name.replace(Regex("[^A-Za-z0-9_.\\-]"), "_").replace("..", "_")
            return File(getRoot(context), "$safe.qne")
        }
    }

    override fun run(context: Context, arguments: String): String {
        val json = JSONObject(arguments)
        val action = json.optString("action", "").lowercase()
        return when (action) {
            "write" -> writeProject(context, json)
            "read" -> readProject(context, json)
            "list" -> listProjects(context)
            "delete" -> deleteProject(context, json)
            else -> "未知操作：$action。支持：write/read/list/delete"
        }
    }

    private fun writeProject(context: Context, json: JSONObject): String {
        val name = json.optString("name", "").ifBlank { return "缺少 name 参数" }
        val content = json.optString("content", null) ?: return "缺少 content 参数"
        // 校验是否为合法 JSON（快照必须是 JSON 对象）
        if (runCatching { JSONObject(content) }.getOrNull() == null && runCatching { org.json.JSONArray(content) }.getOrNull() == null) {
            return "❌ content 不是合法 JSON，无法写入节点流工程"
        }
        return runCatching {
            fileFor(context, name).writeText(content, StandardCharsets.UTF_8)
            "✅ 已写入节点流工程「$name」（${content.length} 字符）。工具中心「节点编辑器」打开即自动恢复。"
        }.getOrElse { "❌ 写入失败：${it.message}" }
    }

    private fun readProject(context: Context, json: JSONObject): String {
        val name = json.optString("name", "").ifBlank { return "缺少 name 参数" }
        val f = fileFor(context, name)
        if (!f.exists()) return "❌ 工程不存在：$name"
        return runCatching {
            val c = f.readText(StandardCharsets.UTF_8)
            "🧩 $name (${c.length} 字符)\n\n```\n$c\n```"
        }.getOrElse { "❌ 读取失败：${it.message}" }
    }

    private fun listProjects(context: Context): String {
        val root = getRoot(context)
        val names = root.listFiles()?.filter { it.extension == "qne" }?.map { it.nameWithoutExtension } ?: emptyList()
        if (names.isEmpty()) return "节点流工程为空（filesDir/studio/flow 下还没有 .qne）。用 node_editor(action=\"write\", name=\"x\", content=\"{...}\") 创建一个。"
        return buildString {
            appendLine("🧩 节点流工程（${names.size}）：")
            names.forEach { appendLine("  • $it") }
        }
    }

    private fun deleteProject(context: Context, json: JSONObject): String {
        val name = json.optString("name", "").ifBlank { return "缺少 name 参数" }
        val f = fileFor(context, name)
        return if (f.exists() && f.delete()) "✅ 已删除节点流工程「$name」" else "❌ 工程不存在：$name"
    }
}
