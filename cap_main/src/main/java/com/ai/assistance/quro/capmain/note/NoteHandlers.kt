package com.ai.assistance.quro.capmain.note

import android.os.Bundle
import com.ai.assistance.quro.libaci.AciHandler
import com.ai.assistance.quro.libaci.CapabilitySpec
import org.json.JSONArray
import org.json.JSONObject

/**
 * 笔记域能力 Handler（main.* 命名空间）。
 *
 * 说明：当前为自包含内存实现（NoteRepo）。create_note 为写操作但非破坏性，
 * 故不标记 dangerous（危险能力闸门见 PermissionGuard，留给删除/清库等破坏性操作）。
 */

private object NoteRepo {
    private val notes = LinkedHashMap<String, JSONObject>()
    private var seq = 1

    @Synchronized
    fun add(title: String, content: String): JSONObject {
        val id = "note_${seq++}"
        val o = JSONObject().apply {
            put("note_id", id)
            put("title", title)
            put("content", content)
            put("created_at", System.currentTimeMillis())
        }
        notes[id] = o
        return o
    }

    @Synchronized
    fun search(keyword: String?): List<JSONObject> {
        if (keyword.isNullOrBlank()) return notes.values.toList()
        return notes.values.filter {
            it.optString("title").contains(keyword, true) ||
                    it.optString("content").contains(keyword, true)
        }
    }
}

/** main.search_note：按关键词检索笔记。 */
object SearchNoteHandler : AciHandler {
    override val spec: CapabilitySpec = CapabilitySpec(
        id = "main.search_note",
        desc = "检索用户笔记。参数 query(选填,string 关键词，缺省返回全部)。返回 notes(JSON 数组)。" +
                "只读，无副作用。当用户问'我之前记的 XX 在哪'时调用。"
    )

    override fun handle(params: Bundle): Bundle {
        val kw = params.getString("query")
        val list = NoteRepo.search(kw)
        val arr = JSONArray().apply { list.forEach { put(it) } }
        return Bundle().apply { putString("notes", arr.toString()) }
    }
}

/** main.create_note：新建一条笔记。 */
object CreateNoteHandler : AciHandler {
    override val spec: CapabilitySpec = CapabilitySpec(
        id = "main.create_note",
        desc = "新建一条笔记。参数 title(必填,string)、content(必填,string)。" +
                "返回新建 note 的 JSON（含 note_id）。写操作但非破坏性。"
    )

    override fun handle(params: Bundle): Bundle {
        val title = params.getString("title")
            ?: return Bundle().apply { putString("error", "missing required param: title") }
        val content = params.getString("content")
            ?: return Bundle().apply { putString("error", "missing required param: content") }
        val created = NoteRepo.add(title, content)
        return Bundle().apply { putString("note", created.toString()) }
    }
}
