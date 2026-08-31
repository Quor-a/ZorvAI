package com.ai.assistance.quro.core.memory

import android.content.Context
import com.ai.assistance.quro.core.search.bm25Search
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * 记忆库：
 * - 每条记忆可绑定某张人格卡（personaId，空字符串=全局记忆）或归入分组（group）
 * - 支持标题（title，便于检索）、标签（tags，命名关联）、内容（content）
 * - 提供 search() 全文/标签/分组检索
 * 仅用 Android 自带 org.json，无第三方依赖；JSON 文件持久化于 filesDir/quro_memory.json。
 * 向后兼容旧格式（仅含 id/personaId/content/tags/createdAt 的历史文件可正常加载）。
 */
data class QuroMemoryEntry(
    val id: String = UUID.randomUUID().toString(),
    val personaId: String = "",   // 空 = 全局记忆
    val group: String = "",       // 分组 / 文件夹（如「偏好」「工作」「项目」）
    val title: String = "",       // 标题（便于检索与展示）
    val content: String = "",
    val tags: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

class QuroMemoryRepository(context: Context) {
    companion object {
        // C1 修复：进程级写锁。QuroMemoryRepository 在多处（ViewModel / 语音球服务 / 记忆工具）各自 new 实例，
        // 但都指向同一个 quro_memory.json。用 companion 锁保证跨实例的 loadAll()→saveAll() 临界区原子，
        // 杜绝并发/连续写互相覆盖（表现为「有时保存有时不保存」）。
        private val writeLock = Any()
    }

    private val file = File(context.filesDir, "quro_memory.json")

    fun loadAll(): List<QuroMemoryEntry> {
        if (!file.exists()) return emptyList()
        val text = runCatching { file.readText() }.getOrElse { return emptyList() }
        if (text.isBlank()) return emptyList()
        val arr = runCatching { JSONObject(text).optJSONArray("memories") }.getOrNull() ?: return emptyList()
        val out = mutableListOf<QuroMemoryEntry>()
        for (i in 0 until arr.length()) {
            runCatching { parse(arr.getJSONObject(i)) }.getOrNull()?.let { out.add(it) }
        }
        return out
    }

    /** 取某人格卡的记忆；若 personaId 非空，同时并入全局记忆（personaId 为空）。 */
    fun loadForPersona(personaId: String): List<QuroMemoryEntry> {
        val all = loadAll()
        return if (personaId.isBlank()) {
            all.filter { it.personaId.isBlank() }
        } else {
            all.filter { it.personaId == personaId || it.personaId.isBlank() }
        }
    }

    fun add(entry: QuroMemoryEntry) {
        synchronized(writeLock) {
            // 临界区内重新 loadAll()，拿最新全量再合并新增项，避免覆盖同窗口内的其它写。
            saveAll(loadAll() + entry)
        }
    }

    fun update(entry: QuroMemoryEntry) {
        synchronized(writeLock) {
            saveAll(loadAll().map { if (it.id == entry.id) entry.copy(updatedAt = System.currentTimeMillis()) else it })
        }
    }

    fun delete(id: String) {
        synchronized(writeLock) {
            saveAll(loadAll().filter { it.id != id })
        }
    }

    /** 检索：匹配内容 / 标题 / 标签 / 分组（不区分大小写）。空查询返回全部。 */
    /**
     * 检索记忆。
     *
     * 检索策略（BM25 优先 + 子串包含兜底）：
     *  1. **BM25 打分排序**——先按相关性降序返回真正「语义相关」的结果。
     *     相比旧的「子串包含」，BM25 考虑了词频饱和与长度归一化，
     *     能让「短而切题」的记忆排在「长而泛泛」的记忆之前，
     *     且支持中英混排与多词查询（无需词典，中文走 bigram）。
     *     索引文本 = 标题 + 内容 + 标签 + 分组，任一字段命中即得分。
     *  2. **子串包含兜底**——BM25 依赖分词，对纯符号、单字符、或分词边界特殊
     *     的查询可能零命中。为不丢失旧行为（如精确搜某个编号/英文片段），
     *     把「BM25 未命中但子串包含」的记忆追加在后，保证召回只增不减。
     */
    fun search(query: String): List<QuroMemoryEntry> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return loadAll()

        val all = loadAll()
        if (all.isEmpty()) return emptyList()

        // 1) BM25 排序
        val docs = all.map { e -> e.id to indexTextOf(e) }
        val hits = bm25Search(documents = docs, query = query, topK = all.size)
        val hitIds = hits.mapTo(HashSet()) { it.id }
        val byId = all.associateBy { it.id }
        val ranked = hits.mapNotNull { byId[it.id] }

        // 2) 子串包含兜底（仅在 BM25 零命中该条时补入，避免重复）
        if (ranked.size == all.size) return ranked
        val fallback = all.filter { e ->
            e.id !in hitIds && (
                e.content.lowercase().contains(q) ||
                    e.title.lowercase().contains(q) ||
                    e.tags.any { it.lowercase().contains(q) } ||
                    e.group.lowercase().contains(q)
                )
        }
        return ranked + fallback
    }

    /** 参与检索的合并文本：标题 + 内容 + 标签 + 分组。 */
    private fun indexTextOf(e: QuroMemoryEntry): String = buildString {
        if (e.title.isNotBlank()) append(e.title).append(' ')
        if (e.content.isNotBlank()) append(e.content).append(' ')
        if (e.tags.isNotEmpty()) append(e.tags.joinToString(" ")).append(' ')
        if (e.group.isNotBlank()) append(e.group)
    }.toString()

    fun saveAll(list: List<QuroMemoryEntry>) {
        runCatching {
            val arr = JSONArray()
            list.forEach { arr.put(serialize(it)) }
            file.writeText(JSONObject().put("memories", arr).toString())
        }
    }

    /** 从导出格式 JSON 文本解析记忆条目（兼容 {"memories":[...]} 与纯数组两种格式）。供导入功能使用。 */
    fun parseJson(text: String): List<QuroMemoryEntry> {
        val out = mutableListOf<QuroMemoryEntry>()
        runCatching {
            val root = runCatching { JSONObject(text) }.getOrNull()
            val arr = if (root != null && root.has("memories")) root.optJSONArray("memories") else JSONArray(text)
            for (i in 0 until (arr?.length() ?: 0)) {
                val o = arr?.optJSONObject(i) ?: continue
                out.add(parse(o))
            }
        }
        return out
    }

    /** 导入：与现有记忆按 id 合并（相同 id 以导入内容覆盖，否则追加）。返回导入条数。 */
    fun mergeImport(entries: List<QuroMemoryEntry>): Int {
        return synchronized(writeLock) {
            // 临界区内重新 loadAll()，保证与并发写不互相覆盖
            val existing = loadAll().associateBy { it.id }
            val merged = (entries + existing.values).distinctBy { it.id }
            saveAll(merged)
            entries.size
        }
    }

    /** 导出为 {"memories":[...]} 格式文本。 */
    fun exportJson(): String {
        val arr = JSONArray()
        loadAll().forEach { arr.put(serialize(it)) }
        return JSONObject().put("memories", arr).toString(2)
    }

    private fun parse(o: JSONObject): QuroMemoryEntry {
        val tagsArr = o.optJSONArray("tags")
        val tags = if (tagsArr != null) {
            val list = mutableListOf<String>()
            for (i in 0 until tagsArr.length()) list.add(tagsArr.optString(i, ""))
            list.filter { it.isNotBlank() }
        } else {
            emptyList()
        }
        val now = System.currentTimeMillis()
        return QuroMemoryEntry(
            id = o.optString("id", UUID.randomUUID().toString()),
            personaId = o.optString("personaId", ""),
            group = o.optString("group", ""),
            title = o.optString("title", ""),
            content = o.optString("content", ""),
            tags = tags,
            createdAt = o.optLong("createdAt", now),
            updatedAt = o.optLong("updatedAt", now),
        )
    }

    private fun serialize(m: QuroMemoryEntry): JSONObject {
        val tagsArr = JSONArray()
        m.tags.forEach { tagsArr.put(it) }
        return JSONObject().apply {
            put("id", m.id)
            put("personaId", m.personaId)
            put("group", m.group)
            put("title", m.title)
            put("content", m.content)
            put("tags", tagsArr)
            put("createdAt", m.createdAt)
            put("updatedAt", m.updatedAt)
        }
    }
}
