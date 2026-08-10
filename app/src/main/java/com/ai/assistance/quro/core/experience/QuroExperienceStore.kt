package com.ai.assistance.quro.core.experience

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * AI 经验笔记 & 自我进化系统（原创，App 本地沙盒，零隐私风险）。
 *
 * 让 AI 在后台自动沉淀「报错 → 解决方案 → 工具模式 → 版本差异」，并在下次相关对话里
 * 自动复用 / 修正（OODA 闭环的 Feedback）。不打扰用户，纯本地持久化于
 * filesDir/ai_experience.json。
 *
 * 数据模型（经验沉淀方案，但收敛为单文件 + 进程级写锁，复用 [com.ai.assistance.quro.core.memory.QuroMemoryRepository]
 * 的稳健范式，规避并发写互相覆盖）：
 * - experiences[]：经验条目（type ∈ error / solution / pattern / compatibility）
 * - compatibility[]：版本兼容标记（valid_in / broken_since / workaround）
 * - corrections[]：自我纠错日志（date | experienceId | was | reason | fix）
 */
enum class ExperienceType(val key: String) {
    ERROR("error"),
    SOLUTION("solution"),
    PATTERN("pattern"),
    COMPAT("compatibility");

    companion object {
        fun from(s: String?): ExperienceType = when (s?.lowercase()?.trim()) {
            "error", "errors", "bug", "报错", "失败" -> ERROR
            "solution", "solutions", "fix", "方案", "解决" -> SOLUTION
            "pattern", "patterns", "tool", "模式", "工具" -> PATTERN
            "compatibility", "compat", "version", "兼容", "版本" -> COMPAT
            else -> ERROR
        }
    }
}

data class QuroExperienceEntry(
    val id: String = UUID.randomUUID().toString(),
    val type: ExperienceType = ExperienceType.ERROR,
    val title: String = "",
    val content: String = "",
    val tags: List<String> = emptyList(),
    val platform: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val correctedAt: Long = 0,
    val correctionCount: Int = 0,
    val reuseCount: Int = 0,
)

data class QuroCompatMarker(
    val subject: String = "",        // 受影响的对象，如 "Android 14" / "Kotlin 2.0" / "MIUI"
    val validIn: List<String> = emptyList(),   // 正常工作的版本范围（如 ["1.0","1.2"]）
    val brokenSince: String = "",    // 从哪个版本开始失效（空 = 仍有效）
    val workaround: String = "",
    val note: String = "",
)

data class QuroCorrectionLog(
    val id: String = UUID.randomUUID().toString(),
    val date: String = "",           // yyyy-MM-dd
    val experienceId: String = "",
    val was: String = "",
    val reason: String = "",
    val fix: String = "",
)

/**
 * 经验仓库：单 JSON 文件 + 进程级写锁，三段式（experiences / compatibility / corrections）。
 */
class QuroExperienceRepository(context: Context) {
    companion object {
        // 进程级写锁：多处（ViewModel / 工具）各自 new 实例，但都指向同一 ai_experience.json。
        private val writeLock = Any()
    }

    private val file = File(context.filesDir, "ai_experience.json")

    fun loadAll(): Triple<List<QuroExperienceEntry>, List<QuroCompatMarker>, List<QuroCorrectionLog>> {
        if (!file.exists()) return Triple(emptyList(), emptyList(), emptyList())
        val text = runCatching { file.readText() }.getOrElse { return Triple(emptyList(), emptyList(), emptyList()) }
        if (text.isBlank()) return Triple(emptyList(), emptyList(), emptyList())
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return Triple(emptyList(), emptyList(), emptyList())
        val exps = mutableListOf<QuroExperienceEntry>()
        root.optJSONArray("experiences")?.let { a ->
            for (i in 0 until a.length()) runCatching { parseExp(a.getJSONObject(i)) }.getOrNull()?.let { exps.add(it) }
        }
        val comps = mutableListOf<QuroCompatMarker>()
        root.optJSONArray("compatibility")?.let { a ->
            for (i in 0 until a.length()) runCatching { parseCompat(a.getJSONObject(i)) }.getOrNull()?.let { comps.add(it) }
        }
        val cors = mutableListOf<QuroCorrectionLog>()
        root.optJSONArray("corrections")?.let { a ->
            for (i in 0 until a.length()) runCatching { parseCor(a.getJSONObject(i)) }.getOrNull()?.let { cors.add(it) }
        }
        return Triple(exps, comps, cors)
    }

    fun saveAll(triple: Triple<List<QuroExperienceEntry>, List<QuroCompatMarker>, List<QuroCorrectionLog>>) {
        runCatching {
            val root = JSONObject()
            val ea = JSONArray(); triple.first.forEach { ea.put(serializeExp(it)) }
            val ca = JSONArray(); triple.second.forEach { ca.put(serializeCompat(it)) }
            val oa = JSONArray(); triple.third.forEach { oa.put(serializeCor(it)) }
            root.put("experiences", ea).put("compatibility", ca).put("corrections", oa)
            file.writeText(root.toString(2))
        }
    }

    fun addExperience(e: QuroExperienceEntry) = synchronized(writeLock) {
        val (e1, c, o) = loadAll()
        saveAll(Triple(e1 + e, c, o))
    }

    fun updateExperience(e: QuroExperienceEntry) = synchronized(writeLock) {
        val (e1, c, o) = loadAll()
        saveAll(Triple(e1.map { if (it.id == e.id) e else it }, c, o))
    }

    fun addCompat(m: QuroCompatMarker) = synchronized(writeLock) {
        val (e1, c, o) = loadAll()
        // 同 subject 覆盖（最新结论优先）
        saveAll(Triple(e1, (c.filter { it.subject != m.subject } + m), o))
    }

    fun addCorrection(l: QuroCorrectionLog) = synchronized(writeLock) {
        val (e1, c, o) = loadAll()
        saveAll(Triple(e1, c, o + l))
    }

    fun bumpReuse(ids: List<String>) = synchronized(writeLock) {
        if (ids.isEmpty()) return@synchronized
        val (e1, c, o) = loadAll()
        val set = ids.toSet()
        saveAll(Triple(e1.map { if (it.id in set) it.copy(reuseCount = it.reuseCount + 1) else it }, c, o))
    }

    // ---- parse / serialize ----

    private fun parseExp(o: JSONObject): QuroExperienceEntry {
        val tagsArr = o.optJSONArray("tags")
        val tags = if (tagsArr != null) {
            val list = mutableListOf<String>()
            for (i in 0 until tagsArr.length()) list.add(tagsArr.optString(i, ""))
            list.filter { it.isNotBlank() }
        } else emptyList()
        val now = System.currentTimeMillis()
        return QuroExperienceEntry(
            id = o.optString("id", UUID.randomUUID().toString()),
            type = ExperienceType.from(o.optString("type", "error")),
            title = o.optString("title", ""),
            content = o.optString("content", ""),
            tags = tags,
            platform = o.optString("platform", ""),
            createdAt = o.optLong("createdAt", now),
            correctedAt = o.optLong("correctedAt", 0),
            correctionCount = o.optInt("correctionCount", 0),
            reuseCount = o.optInt("reuseCount", 0),
        )
    }

    private fun serializeExp(m: QuroExperienceEntry): JSONObject {
        val tagsArr = JSONArray()
        m.tags.forEach { tagsArr.put(it) }
        return JSONObject().apply {
            put("id", m.id)
            put("type", m.type.key)
            put("title", m.title)
            put("content", m.content)
            put("tags", tagsArr)
            put("platform", m.platform)
            put("createdAt", m.createdAt)
            put("correctedAt", m.correctedAt)
            put("correctionCount", m.correctionCount)
            put("reuseCount", m.reuseCount)
        }
    }

    private fun parseCompat(o: JSONObject): QuroCompatMarker {
        val vArr = o.optJSONArray("valid_in")
        val valid = if (vArr != null) {
            val list = mutableListOf<String>()
            for (i in 0 until vArr.length()) list.add(vArr.optString(i, ""))
            list.filter { it.isNotBlank() }
        } else emptyList()
        return QuroCompatMarker(
            subject = o.optString("subject", ""),
            validIn = valid,
            brokenSince = o.optString("broken_since", ""),
            workaround = o.optString("workaround", ""),
            note = o.optString("note", ""),
        )
    }

    private fun serializeCompat(m: QuroCompatMarker): JSONObject {
        val vArr = JSONArray()
        m.validIn.forEach { vArr.put(it) }
        return JSONObject().apply {
            put("subject", m.subject)
            put("valid_in", vArr)
            put("broken_since", m.brokenSince)
            put("workaround", m.workaround)
            put("note", m.note)
        }
    }

    private fun parseCor(o: JSONObject): QuroCorrectionLog {
        return QuroCorrectionLog(
            id = o.optString("id", UUID.randomUUID().toString()),
            date = o.optString("date", ""),
            experienceId = o.optString("experienceId", ""),
            was = o.optString("was", ""),
            reason = o.optString("reason", ""),
            fix = o.optString("fix", ""),
        )
    }

    private fun serializeCor(m: QuroCorrectionLog): JSONObject {
        return JSONObject().apply {
            put("id", m.id)
            put("date", m.date)
            put("experienceId", m.experienceId)
            put("was", m.was)
            put("reason", m.reason)
            put("fix", m.fix)
        }
    }
}
