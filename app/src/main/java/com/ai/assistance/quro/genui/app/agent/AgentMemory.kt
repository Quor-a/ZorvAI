package com.ai.assistance.quro.genui.app.agent

import android.content.Context
import com.ai.assistance.quro.core.memory.QuroMemoryEntry
import com.ai.assistance.quro.core.memory.QuroMemoryRepository
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Agent 记忆库 —— 委托给 QuroAI 统一记忆系统（QuroMemoryRepository，
 * 持久化于 filesDir/quro_memory.json），与文本对话 / 人格卡共享同一份记忆。
 *
 * 映射：GenUI 的 key→content 模型 对齐 QuroAI 的 id/title/content/group 模型：
 *  - 统一以 group="genui" 入库，与系统其它记忆隔离、不互相污染；
 *  - 用确定性 id = "genui:<key>" 存储，使 read/delete 能精确定位同一键；
 *  - title 存为 key，content 原样保存。
 * 对外 API（write / read / list / delete / clear / indexForPrompt）与旧版一致，
 * 因此 AgentLoop、BuiltinTools、MemoryScreen 等上层无需改动。
 */
class AgentMemory(context: Context) {

    private val repo = QuroMemoryRepository(context)

    fun write(key: String, content: String): JSONObject {
        require(key.isNotBlank()) { "记忆键不能为空" }
        require(content.isNotBlank()) { "记忆内容不能为空" }
        val k = key.trim()
        val trimmedContent = if (content.length > 2000) content.take(2000) + "…" else content
        val id = "genui:$k"
        val now = System.currentTimeMillis()
        val existing = repo.loadAll().firstOrNull { it.id == id }
        val entry = QuroMemoryEntry(
            id = id,
            group = GROUP,
            title = k,
            content = trimmedContent,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now
        )
        if (existing != null) repo.update(entry) else repo.add(entry)
        return JSONObject().put("ok", true).put("key", k)
    }

    fun read(key: String): JSONObject {
        val k = key.trim()
        val id = "genui:$k"
        val e = repo.loadAll().firstOrNull { it.id == id }
        return if (e != null) {
            JSONObject().put("key", k).put("content", e.content)
        } else {
            JSONObject().put("key", k).put("content", JSONObject.NULL)
                .put("hint", "没有这条记忆，可先用 memory.list 查看全部键")
        }
    }

    fun list(): JSONObject {
        val items = JSONArray()
        repo.loadAll().filter { it.group == GROUP }
            .sortedByDescending { it.updatedAt }
            .forEach { e ->
                val c = e.content
                items.put(JSONObject()
                    .put("key", e.title)
                    .put("brief", if (c.length > 60) c.take(60) + "…" else c)
                    .put("time", SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(e.updatedAt))))
            }
        return JSONObject().put("count", items.length()).put("items", items)
    }

    fun delete(key: String): JSONObject {
        val k = key.trim()
        val id = "genui:$k"
        val existed = repo.loadAll().any { it.id == id }
        if (existed) repo.delete(id)
        return JSONObject().put("ok", existed)
    }

    /** 记忆屏"清空"入口：删除本分组下的全部记忆（不影响其它分组 / 全局记忆） */
    @Synchronized
    fun clear() {
        repo.loadAll().filter { it.group == GROUP }.forEach { repo.delete(it.id) }
    }

    /** 注入系统提示的索引文本（只有键+摘要，省 token；条数设上限防上下文膨胀） */
    fun indexForPrompt(): String {
        val r = list()
        val items = r.optJSONArray("items") ?: return "（记忆库为空）"
        if (items.length() == 0) return "（记忆库为空，可主动用 memory_write 记住用户的重要偏好与事实）"
        val sb = StringBuilder()
        var shown = 0
        for (i in 0 until items.length()) {
            if (shown >= MAX_INDEX_ENTRIES) {
                // 记忆会越攒越多，全量注入会逐渐吃掉上下文预算、推高成本，
                // 还会稀释真正重要的信息。超出部分只报数量，模型需要时可 memory_list 查。
                sb.append("… 另有 ").append(items.length() - shown)
                    .append(" 条未列出（用 memory_list 查看全部）\n")
                break
            }
            val o = items.getJSONObject(i)
            sb.append("· ").append(o.optString("key")).append("：").append(o.optString("brief")).append('\n')
            shown++
        }
        return sb.toString()
    }

    private companion object {
        /** 所有 GenUI 记忆在统一记忆库中的分组标识 */
        const val GROUP = "genui"
        /** 提示词里最多列出的记忆条数 */
        const val MAX_INDEX_ENTRIES = 40
    }
}
