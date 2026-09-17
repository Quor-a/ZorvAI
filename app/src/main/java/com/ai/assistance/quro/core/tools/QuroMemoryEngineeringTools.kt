package com.ai.assistance.quro.core.tools

import android.content.Context
import com.ai.assistance.quro.core.memory.QuroMemoryEntry
import com.ai.assistance.quro.core.memory.QuroMemoryRepository
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * 记忆系统工程化工具集（对应「其他 AI」记忆系统的分层架构）。
 * 补齐 ZorvAI 仅有 memory_* + RAG、缺乏的「多空间 + 重建 + 自动保存候选 + 窗口规划 + 导入导出」体系。
 * 全部基于现有 QuroMemoryRepository（filesDir/quro_memory.json），无第三方依赖。
 *
 * - memory_space      多空间（MemorySpace）：default + 自定义命名空间，各自独立 JSON 文件。
 * - memory_rebuild    重建（ChatMemoryRebuildManager + RebuildTimeScope）：校验/去重/重排/压缩，并给出时间范围统计。
 * - memory_autosave   自动保存候选（MemoryAutoSaveScheduler + MemoryAutoSaveCandidate）：候选入队→提交→落库，含总开关。
 * - memory_window_plan 窗口规划（ChatMemoryWindowPlanner）：按 group/persona/tag 分窗并估算 token，给出投放建议。
 * - memory_export     导入导出（MemoryExportModel）：导出 JSON / 从文本或文件导入合并。
 */
class QuroMemorySpaceTool : QuroTool {
    override val name: String = "memory_space"

    override val description: String =
        "记忆多空间管理（MemorySpace）：在 default 之外维护多个独立记忆命名空间，各自独立 JSON 文件。" +
            "action 取值：list(列出全部空间及条目数) / current(当前空间) / use(切换空间) / create(新建) / copy(复制条目) / delete(删除，default 不可删)。" +
            "name 为空间名（use/create/delete/copy 用）；from/to 为 copy 的源/目标空间。"

    override val parametersJson: String = """{
      "type":"object",
      "properties":{
        "action":{"type":"string","enum":["list","current","use","create","copy","delete"],"description":"动作"},
        "name":{"type":"string","description":"空间名（use/create/delete 用）"},
        "from":{"type":"string","description":"copy 的源空间"},
        "to":{"type":"string","description":"copy 的目标空间"}
      },
      "required":["action"]
    }"""

    override fun run(context: Context, arguments: String): String {
        return try {
            val args = JSONObject(arguments)
            val action = args.optString("action", "").lowercase()
            when (action) {
                "list" -> {
                    val sb = StringBuilder("记忆空间：\n")
                    listSpaces(context).forEach { s ->
                        val n = QuroMemoryRepository(context, spaceFileName(s)).loadAll().size
                        if (s == currentSpace(context)) sb.append("* ") else sb.append("  ")
                        sb.append("$s（$n 条）\n")
                    }
                    sb.toString().trim()
                }
                "current" -> "当前记忆空间：${currentSpace(context)}"
                "use" -> {
                    val n = args.optString("name", "").trim()
                    if (n.isBlank()) return "use 需要 name"
                    setCurrentSpace(context, n)
                    "已切换到空间：$n"
                }
                "create" -> {
                    val n = args.optString("name", "").trim()
                    if (n.isBlank() || n == "default") return "create 需要非 default 的空间名"
                    val f = spaceFile(context, n)
                    if (!f.exists()) f.writeText(JSONObject().put("memories", JSONArray()).toString())
                    "已创建空间：$n"
                }
                "copy" -> {
                    val from = args.optString("from", "").trim()
                    val to = args.optString("to", "").trim()
                    if (from.isBlank() || to.isBlank()) return "copy 需要 from 与 to"
                    val src = QuroMemoryRepository(context, spaceFileName(from)).loadAll()
                    val dst = QuroMemoryRepository(context, spaceFileName(to))
                    dst.saveAll(dst.loadAll() + src)
                    "已将空间 $from 的 ${src.size} 条记忆复制到 $to"
                }
                "delete" -> {
                    val n = args.optString("name", "").trim()
                    if (n.isBlank() || n == "default") return "default 空间不可删除"
                    val f = spaceFile(context, n)
                    if (f.exists()) f.delete()
                    if (currentSpace(context) == n) setCurrentSpace(context, "default")
                    "已删除空间：$n"
                }
                else -> "不支持的 action：$action（list/current/use/create/copy/delete）"
            }
        } catch (e: Exception) {
            "memory_space 执行失败：${e.message}"
        }
    }
}

class QuroMemoryRebuildTool : QuroTool {
    override val name: String = "memory_rebuild"

    override val description: String =
        "记忆重建（ChatMemoryRebuildManager + RebuildTimeScope）：校验全部条目、按 id 去重（保留最新 updatedAt）、" +
            "按 updatedAt 降序重排并压缩写回；同时给出近 1/7/30 天新增条目的时间范围统计。" +
            "参数 space 可选（默认当前空间）；返回重建前后条目数、丢弃/去重数、时间分布。"

    override val parametersJson: String = """{
      "type":"object",
      "properties":{"space":{"type":"string","description":"目标空间名（默认当前空间）"}},
      "required":[]
    }"""

    override fun run(context: Context, arguments: String): String {
        return try {
            val args = JSONObject(arguments)
            val space = args.optString("space", "").takeIf { it.isNotBlank() } ?: currentSpace(context)
            val repo = QuroMemoryRepository(context, spaceFileName(space))
            val all = repo.loadAll()
            val before = all.size
            // 去重：同 id 保留 updatedAt 最大者
            val byId = LinkedHashMap<String, QuroMemoryEntry>()
            all.forEach { e ->
                val prev = byId[e.id]
                if (prev == null || e.updatedAt > prev.updatedAt) byId[e.id] = e
            }
            val deduped = byId.values.sortedByDescending { it.updatedAt }
            val dropped = all.count { it.content.isBlank() && it.title.isBlank() }
            val genuinelyRemoved = before - deduped.size
            repo.saveAll(deduped)
            val now = System.currentTimeMillis()
            val day = all.count { now - it.createdAt <= 86_400_000L }
            val week = all.count { now - it.createdAt <= 7L * 86_400_000L }
            val month = all.count { now - it.createdAt <= 30L * 86_400_000L }
            val groups = deduped.groupBy { it.group.ifBlank { "(无分组)" } }.mapValues { it.value.size }
            val o = JSONObject()
            o.put("space", space)
            o.put("before", before)
            o.put("after", deduped.size)
            o.put("duplicated_removed", genuinelyRemoved)
            o.put("blank_dropped", dropped)
            o.put("added_last_1d", day)
            o.put("added_last_7d", week)
            o.put("added_last_30d", month)
            o.put("by_group", JSONObject(groups))
            o.toString(2)
        } catch (e: Exception) {
            "memory_rebuild 执行失败：${e.message}"
        }
    }
}

class QuroMemoryAutoSaveTool : QuroTool {
    override val name: String = "memory_autosave"

    override val description: String =
        "记忆自动保存候选（MemoryAutoSaveScheduler + MemoryAutoSaveCandidate）：把「待定记忆」先入候选队列，" +
            "经确认后再提交进正式记忆库，避免噪声污染；含总开关。" +
            "action 取值：queue(入队候选) / list(列出待定) / commit(提交单条) / commit_all(全部提交) / " +
            "drop(丢弃单条) / enable / disable / status。"

    override val parametersJson: String = """{
      "type":"object",
      "properties":{
        "action":{"type":"string","enum":["queue","list","commit","commit_all","drop","enable","disable","status"],"description":"动作"},
        "id":{"type":"string","description":"commit/drop 的目标候选 id"},
        "content":{"type":"string","description":"queue 的记忆内容"},
        "title":{"type":"string","description":"queue 的标题"},
        "group":{"type":"string","description":"queue 的分组"},
        "tags":{"type":"array","items":{"type":"string"},"description":"queue 的标签"}
      },
      "required":["action"]
    }"""

    override fun run(context: Context, arguments: String): String {
        return try {
            val args = JSONObject(arguments)
            val action = args.optString("action", "").lowercase()
            val repo = QuroMemoryRepository(context, spaceFileName(currentSpace(context)))
            when (action) {
                "queue" -> {
                    val content = args.optString("content", "").trim()
                    if (content.isBlank()) return "queue 需要 content"
                    val tags = mutableListOf<String>()
                    args.optJSONArray("tags")?.let { for (i in 0 until it.length()) tags.add(it.optString(i, "")) }
                    val cand = JSONObject()
                    cand.put("id", UUID.randomUUID().toString())
                    cand.put("title", args.optString("title", ""))
                    cand.put("content", content)
                    cand.put("group", args.optString("group", ""))
                    cand.put("tags", JSONArray(tags.filter { it.isNotBlank() }))
                    cand.put("createdAt", System.currentTimeMillis())
                    cand.put("status", "pending")
                    val q = loadQueue(context); q.add(cand); saveQueue(context, q)
                    "已入队自动保存候选（id=${cand.getString("id")}，当前待定 ${q.size} 条）。确认后调用 commit 落库。"
                }
                "list" -> {
                    val q = loadQueue(context)
                    if (q.isEmpty()) return "没有待定候选。"
                    val sb = StringBuilder("待定候选（${q.size} 条）：\n")
                    q.forEach { sb.append("- [${it.optString("id","")}] ${it.optString("title","").ifBlank{"(无标题)"} }：${it.optString("content","")}\n") }
                    sb.toString().trim()
                }
                "commit" -> {
                    val id = args.optString("id", "").trim()
                    if (id.isBlank()) return "commit 需要 id"
                    commitCandidate(context, repo, id) ?: return "未找到候选 id=$id"
                    "已提交候选 $id 到记忆库。"
                }
                "commit_all" -> {
                    val q = loadQueue(context)
                    if (q.isEmpty()) return "没有待定候选。"
                    var n = 0
                    q.toList().forEach { if (commitCandidate(context, repo, it.optString("id", "")) != null) n++ }
                    "已提交 $n / ${q.size} 条候选。"
                }
                "drop" -> {
                    val id = args.optString("id", "").trim()
                    if (id.isBlank()) return "drop 需要 id"
                    val q = loadQueue(context).toMutableList()
                    val removed = q.removeAll { it.optString("id", "") == id }
                    saveQueue(context, q)
                    if (removed) "已丢弃候选 $id。" else "未找到候选 id=$id"
                }
                "enable" -> { setAutoSaveEnabled(context, true); "自动保存已开启。" }
                "disable" -> { setAutoSaveEnabled(context, false); "自动保存已关闭（候选仍会入队，但不会自动落库）。" }
                "status" -> "自动保存：${if (autoSaveEnabled(context)) "开启" else "关闭"}；待定候选：${loadQueue(context).size} 条。"
                else -> "不支持的 action：$action"
            }
        } catch (e: Exception) {
            "memory_autosave 执行失败：${e.message}"
        }
    }

    private fun commitCandidate(context: Context, repo: QuroMemoryRepository, id: String): String? {
        val q = loadQueue(context).toMutableList()
        val cand = q.firstOrNull { it.optString("id", "") == id } ?: return null
        q.remove(cand)
        saveQueue(context, q)
        val tags = mutableListOf<String>()
        cand.optJSONArray("tags")?.let { for (i in 0 until it.length()) tags.add(it.optString(i, "")) }
        repo.add(QuroMemoryEntry(
            id = UUID.randomUUID().toString(),
            title = cand.optString("title", ""),
            content = cand.optString("content", ""),
            group = cand.optString("group", ""),
            tags = tags.filter { it.isNotBlank() },
        ))
        return id
    }
}

class QuroMemoryWindowPlanTool : QuroTool {
    override val name: String = "memory_window_plan"

    override val description: String =
        "记忆窗口规划（ChatMemoryWindowPlanner）：把当前空间记忆按 group / persona / tag 切分为多个「上下文窗口」，" +
            "估算每窗 token 占用并给出投放优先级建议，帮助长上下文场景只载入最相关的一窗。" +
            "action 取值：plan(生成分窗方案) / stats(整体统计)。by 取值：group|persona|tag（默认 group）。"

    override val parametersJson: String = """{
      "type":"object",
      "properties":{
        "action":{"type":"string","enum":["plan","stats"],"description":"动作"},
        "by":{"type":"string","enum":["group","persona","tag"],"description":"分窗维度（默认 group）"}
      },
      "required":["action"]
    }"""

    override fun run(context: Context, arguments: String): String {
        return try {
            val args = JSONObject(arguments)
            val action = args.optString("action", "").lowercase()
            val by = args.optString("by", "group").lowercase()
            val space = currentSpace(context)
            val all = QuroMemoryRepository(context, spaceFileName(space)).loadAll()
            if (action == "stats") {
                val groups = all.groupBy { it.group.ifBlank { "(无分组)" } }.mapValues { it.value.size }
                val tags = all.flatMap { it.tags }.distinct()
                val o = JSONObject()
                o.put("space", space); o.put("total", all.size)
                o.put("groups", JSONObject(groups)); o.put("distinct_tags", tags.size)
                o.put("personas", all.map { it.personaId.ifBlank { "(全局)" } }.distinct().size)
                o.put("approx_tokens", approxTokens(all.joinToString { it.content + it.title }))
                return o.toString(2)
            }
            // plan
            val buckets = LinkedHashMap<String, MutableList<QuroMemoryEntry>>()
            all.forEach { e ->
                val keys = when (by) {
                    "persona" -> listOf(e.personaId.ifBlank { "(全局)" })
                    "tag" -> if (e.tags.isEmpty()) listOf("(无标签)") else e.tags
                    else -> listOf(e.group.ifBlank { "(无分组)" })
                }
                keys.forEach { k -> buckets.getOrPut(k) { mutableListOf() }.add(e) }
            }
            val windows = JSONArray()
            val ranked = buckets.entries.sortedByDescending { it.value.sumOf { m -> approxTokens(m.content + m.title) } }
            ranked.forEach { (k, list) ->
                val tokens = list.sumOf { approxTokens(it.content + it.title) }
                val w = JSONObject()
                w.put("window", k)
                w.put("count", list.size)
                w.put("approx_tokens", tokens)
                w.put("titles", JSONArray(list.map { it.title.ifBlank { it.content.take(20) } }))
                windows.put(w)
            }
            val o = JSONObject()
            o.put("space", space)
            o.put("by", by)
            o.put("window_count", windows.length())
            o.put("recommend_first", if (windows.length() > 0) windows.getJSONObject(0).optString("window") else "")
            o.put("windows", windows)
            o.toString(2)
        } catch (e: Exception) {
            "memory_window_plan 执行失败：${e.message}"
        }
    }
}

class QuroMemoryExportTool : QuroTool {
    override val name: String = "memory_export"

    override val description: String =
        "记忆导入导出（MemoryExportModel）：导出当前空间为 {\"memories\":[...]} JSON（可写文件或返回文本），" +
            "或从文本 / 文件路径导入并合并（同 id 覆盖，否则追加）。" +
            "action 取值：export / import。export 可选 path 落盘；import 可选 json 文本或 path 文件。"

    override val parametersJson: String = """{
      "type":"object",
      "properties":{
        "action":{"type":"string","enum":["export","import"],"description":"动作"},
        "space":{"type":"string","description":"目标空间（默认当前空间）"},
        "path":{"type":"string","description":"export 的写出路径 / import 的源文件路径"},
        "json":{"type":"string","description":"import 的内联 JSON 文本"}
      },
      "required":["action"]
    }"""

    override fun run(context: Context, arguments: String): String {
        return try {
            val args = JSONObject(arguments)
            val action = args.optString("action", "").lowercase()
            val space = args.optString("space", "").takeIf { it.isNotBlank() } ?: currentSpace(context)
            val repo = QuroMemoryRepository(context, spaceFileName(space))
            when (action) {
                "export" -> {
                    val json = repo.exportJson()
                    val path = args.optString("path", "").takeIf { it.isNotBlank() }
                    if (path != null) {
                        val f = File(path); f.parentFile?.mkdirs(); f.writeText(json)
                        "已导出 ${repo.loadAll().size} 条记忆到：${f.absolutePath}"
                    } else {
                        val preview = if (json.length > 8000) json.take(8000) + "\n...(已截断，请用 path 参数落盘完整文件)" else json
                        preview
                    }
                }
                "import" -> {
                    val inline = args.optString("json", "").takeIf { it.isNotBlank() }
                    val path = args.optString("path", "").takeIf { it.isNotBlank() }
                    val text = when {
                        inline != null -> inline
                        path != null -> { if (!File(path).canRead()) return "import 源文件不可读：$path"; File(path).readText() }
                        else -> return "import 需要 json 或 path 参数"
                    }
                    val entries = repo.parseJson(text)
                    if (entries.isEmpty()) return "未解析到任何记忆条目。"
                    val n = repo.mergeImport(entries)
                    "已导入 $n 条记忆到空间 $space（按 id 合并）。"
                }
                else -> "不支持的 action：$action（export/import）"
            }
        } catch (e: Exception) {
            "memory_export 执行失败：${e.message}"
        }
    }
}

// ───────────── 多空间 / 队列 持久化辅助 ─────────────

private fun spaceFileName(name: String): String = if (name == "default") "quro_memory" else "quro_memory_$name"
private fun spaceFile(context: Context, name: String): File = File(context.filesDir, "${spaceFileName(name)}.json")
private fun currentSpaceFile(context: Context) = File(context.filesDir, "quro_memory_space.txt")
private fun currentSpace(context: Context): String {
    val f = currentSpaceFile(context)
    return if (f.exists()) f.readText().trim().ifBlank { "default" } else "default"
}
private fun setCurrentSpace(context: Context, name: String) { currentSpaceFile(context).writeText(name) }
private fun listSpaces(context: Context): List<String> {
    val names = mutableSetOf("default")
    context.filesDir.list()?.forEach { fn ->
        val m = Regex("^quro_memory_(.+)\\.json\$").find(fn)
        if (m != null) names.add(m.groupValues[1])
    }
    return names.sorted()
}
private fun autoSaveEnabledFile(context: Context) = File(context.filesDir, "quro_memory_autosave_enabled.txt")
private fun autoSaveEnabled(context: Context): Boolean {
    val f = autoSaveEnabledFile(context)
    return !f.exists() || f.readText().trim() == "true"
}
private fun setAutoSaveEnabled(context: Context, on: Boolean) { autoSaveEnabledFile(context).writeText(on.toString()) }
private fun loadQueue(context: Context): MutableList<JSONObject> {
    val f = File(context.filesDir, "quro_autosave_queue.json")
    if (!f.exists()) return mutableListOf()
    val arr = runCatching { JSONArray(f.readText()) }.getOrNull() ?: return mutableListOf()
    val out = mutableListOf<JSONObject>()
    for (i in 0 until arr.length()) runCatching { arr.getJSONObject(i) }.getOrNull()?.let { out.add(it) }
    return out
}
private fun saveQueue(context: Context, list: List<JSONObject>) {
    File(context.filesDir, "quro_autosave_queue.json").writeText(JSONArray(list).toString())
}
private fun approxTokens(s: String): Int =
    if (s.isBlank()) 0 else maxOf(s.length / 2, s.split(Regex("\\s+")).count { it.isNotBlank() })
