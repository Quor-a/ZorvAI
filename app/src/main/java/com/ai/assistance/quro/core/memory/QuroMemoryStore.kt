package com.ai.assistance.quro.core.memory

import android.content.Context
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
    fun search(query: String): List<QuroMemoryEntry> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return loadAll()
        return loadAll().filter { e ->
            e.content.lowercase().contains(q) ||
                e.title.lowercase().contains(q) ||
                e.tags.any { it.lowercase().contains(q) } ||
                e.group.lowercase().contains(q)
        }
    }

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
