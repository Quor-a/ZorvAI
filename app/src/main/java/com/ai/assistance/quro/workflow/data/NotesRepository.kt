package com.ai.assistance.quro.workflow.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * 备注 / 笔记 单一数据源。
 * 本地 UI 与 ACI 能力层（BusinessCapability / wf_note_*）共用。
 * 持久化在 filesDir/notes.json。
 */
object NotesRepository {

    data class Note(
        val id: String,
        val text: String,
        val createdAt: Long = System.currentTimeMillis()
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("id", id)
            put("text", text)
            put("createdAt", createdAt)
        }

        companion object {
            fun fromJson(o: JSONObject): Note = Note(
                id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
                text = o.optString("text"),
                createdAt = o.optLong("createdAt", System.currentTimeMillis())
            )
        }
    }

    private lateinit var file: File
    private val list = mutableListOf<Note>()
    private val lock = Any()

    val changeSignal = MutableStateFlow(0L)

    fun init(ctx: Context) {
        file = File(ctx.filesDir, "notes.json")
        load()
    }

    private fun load() {
        synchronized(lock) {
            if (!file.exists()) {
                list.clear()
                return
            }
            runCatching {
                val arr = JSONArray(file.readText())
                list.clear()
                for (i in 0 until arr.length()) list.add(Note.fromJson(arr.getJSONObject(i)))
            }.onFailure { list.clear() }
        }
    }

    private fun save() = synchronized(lock) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        file.writeText(arr.toString())
        changeSignal.value += 1
    }

    fun getAll(): List<Note> = synchronized(lock) { list.toList() }

    fun add(text: String): String {
        val note = Note(id = UUID.randomUUID().toString(), text = text)
        synchronized(lock) {
            list.add(0, note)
            save()
        }
        return note.id
    }

    fun delete(id: String): Boolean = synchronized(lock) {
        val before = list.size
        list.removeAll { it.id == id }
        val ok = list.size < before
        if (ok) save()
        ok
    }
}
