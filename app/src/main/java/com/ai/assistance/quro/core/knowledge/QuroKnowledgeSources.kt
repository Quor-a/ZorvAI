package com.ai.assistance.quro.core.knowledge

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.ai.assistance.quro.core.tools.QuroKnowledgeFiles
import com.ai.assistance.quro.core.tools.QuroTool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 第三方云盘知识源接入层（#590）。
 *
 * 让知识库不止本地文件：把腾讯文档 / ima 知识库 / 乐享（或任意 OpenAPI 兼容的文档接口）拉取为 Markdown，
 * 落盘到 knowledge_base/external/<id>/，并触发 RAG 增量重索引，使其与本地文档一起被检索。
 *
 * 设计取舍（诚实声明）：
 *  - 真实第三方鉴权（OAuth2 / 开放平台 token）由用户提供；本层只负责「Bearer 拉取 + 解析 + 落盘 + 重索引」的通用管线。
 *  - 解析兼容三种形态：① JSON 数组 [{title,content}]；② 单对象 {title,content} 或含 data 数组；③ 纯 Markdown 文本。
 *    腾讯文档/ima/乐享各自的导出端点与字段差异较大，预置项给出合理默认值，用户需在「知识库→云盘来源」里填入自己可用的
 *    endpoint 与 token；拉取失败会记录 lastError 而非崩溃。
 */

/** 一个第三方知识源配置。 */
data class QuroExternalSource(
    val id: String,
    val name: String,
    val type: String, // tencent_doc | ima | lexing | generic
    val baseUrl: String,
    val token: String,
    val enabled: Boolean,
    val lastSync: Long = 0L,
    val lastError: String = "",
)

/** 外部源在知识库里的落盘目录：knowledge_base/external/<id>/。 */
fun externalDir(context: Context, id: String): File =
    File(QuroKnowledgeFiles.dir(context), "external/$id")

/** 来源持久化（SharedPreferences，零新增依赖）。 */
class QuroExternalSourceStore(private val context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("quro_kb_sources", Context.MODE_PRIVATE)

    init { ensurePresets() }

    fun list(): List<QuroExternalSource> {
        val out = mutableListOf<QuroExternalSource>()
        val n = prefs.getInt(KEY_COUNT, 0)
        for (i in 0 until n) {
            val s = prefs.getString("src_$i", null) ?: continue
            runCatching { out.add(fromJson(JSONObject(s))) }
        }
        return out.sortedBy { it.name }
    }

    fun get(id: String): QuroExternalSource? = list().firstOrNull { it.id == id }

    fun upsert(src: QuroExternalSource) {
        val all = list().toMutableList()
        val idx = all.indexOfFirst { it.id == src.id }
        if (idx >= 0) all[idx] = src else all.add(src)
        save(all)
    }

    fun remove(id: String) {
        save(list().filter { it.id != id })
        runCatching { externalDir(context, id).deleteRecursively() }
    }

    private fun save(all: List<QuroExternalSource>) {
        val oldCount = prefs.getInt(KEY_COUNT, 0)
        prefs.edit {
            putInt(KEY_COUNT, all.size)
            all.forEachIndexed { i, s ->
                putString(
                    "src_$i",
                    JSONObject().apply {
                        put("id", s.id); put("name", s.name); put("type", s.type)
                        put("baseUrl", s.baseUrl); put("token", s.token)
                        put("enabled", s.enabled); put("lastSync", s.lastSync); put("lastError", s.lastError)
                    }.toString(),
                )
            }
            for (i in all.size until oldCount) remove("src_$i")
        }
    }

    private fun ensurePresets() {
        if (prefs.getBoolean(KEY_PRESETS, false)) return
        prefs.edit { putBoolean(KEY_PRESETS, true) }
        if (list().isNotEmpty()) return
        listOf(
            QuroExternalSource(UUID.randomUUID().toString(), "腾讯文档", "tencent_doc", "https://docs.qq.com/api/v2/explorer/community", "", false),
            QuroExternalSource(UUID.randomUUID().toString(), "ima 知识库", "ima", "https://ima.qq.com/openapi/v1", "", false),
            QuroExternalSource(UUID.randomUUID().toString(), "乐享知识库", "lexing", "https://lexiangla.com/api/v1", "", false),
        ).forEach { upsert(it) }
    }

    companion object {
        private const val KEY_COUNT = "count"
        private const val KEY_PRESETS = "presets_seeded"
        fun fromJson(j: JSONObject) = QuroExternalSource(
            j.getString("id"),
            j.getString("name"),
            j.getString("type"),
            j.optString("baseUrl", ""),
            j.optString("token", ""),
            j.optBoolean("enabled", false),
            j.optLong("lastSync", 0L),
            j.optString("lastError", ""),
        )
    }
}

/** 第三方知识同步器：拉取 → 解析 → 落盘 → 触发 RAG 重索引。 */
object QuroExternalKnowledgeSync {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    data class SyncResult(val sourceId: String, val files: List<File>, val error: String = "")

    /** 同步单个来源。无 token/baseUrl 优雅跳过；拉取失败记录 error 不崩溃。 */
    suspend fun syncOne(context: Context, src: QuroExternalSource): SyncResult = withContext(Dispatchers.IO) {
        if (src.token.isBlank() || src.baseUrl.isBlank()) {
            return@withContext SyncResult(src.id, emptyList(), "未配置 token 或 baseUrl，跳过（请在「知识库→云盘来源」中填写可用端点与令牌）")
        }
        runCatching {
            val req = Request.Builder().url(src.baseUrl.trim()).addHeader("Authorization", "Bearer ${src.token}").get().build()
            client.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) return@withContext SyncResult(src.id, emptyList(), "HTTP ${resp.code}: ${raw.take(160)}")
                val docs = parseDocs(raw)
                val dir = externalDir(context, src.id).apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                val files = docs.mapIndexed { i, d ->
                    val f = File(dir, "${i}_${sanitize(d.first)}.md")
                    f.writeText("# ${d.first}\n\n${d.second}")
                    f
                }
                SyncResult(src.id, files)
            }
        }.getOrElse { e -> SyncResult(src.id, emptyList(), "同步失败：${e.message}") }
    }

    /** 同步所有启用来源，并触发 RAG 增量重索引（外部目录变化会被自动纳入）。 */
    suspend fun syncAllEnabled(context: Context): List<SyncResult> {
        val store = QuroExternalSourceStore(context)
        val enabled = store.list().filter { it.enabled }
        val results = enabled.map { src ->
            val r = syncOne(context, src)
            store.upsert(
                src.copy(
                    lastSync = if (r.error.isBlank()) System.currentTimeMillis() else src.lastSync,
                    lastError = r.error,
                ),
            )
            r
        }
        runCatching { buildRagPipeline(context).syncDirectory(QuroKnowledgeFiles.dir(context)) }
        return results
    }

    /** 兼容三种返回形态。 */
    private fun parseDocs(raw: String): List<Pair<String, String>> {
        val t = raw.trim()
        if (t.startsWith("[")) {
            val arr = JSONArray(t)
            val out = mutableListOf<Pair<String, String>>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val title = o.optString("title", o.optString("name", "doc_$i"))
                val content = o.optString("content", o.optString("text", o.optString("body", "")))
                if (content.isNotBlank()) out.add(title to content)
            }
            return out
        }
        if (t.startsWith("{")) {
            val o = JSONObject(t)
            val content = o.optString("content", o.optString("text", o.optString("body", "")))
            if (content.isNotBlank()) {
                return listOf(o.optString("title", o.optString("name", "doc")) to content)
            }
            if (o.has("data") && o.get("data") is JSONArray) {
                return parseDocs(o.getJSONArray("data").toString())
            }
            return emptyList()
        }
        return if (t.isNotBlank()) listOf("document" to t) else emptyList()
    }

    private fun sanitize(s: String): String =
        s.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_").take(40)
}

/** AI 可调用：列出/同步第三方知识来源。 */
class QuroKnowledgeSyncTool : QuroTool {
    override val name = "knowledge_sync_sources"
    override val description =
        "管理第三方云盘知识源：list 查看已配置来源（腾讯文档/ima/乐享/自定义），sync 同步所有启用来源并触发重索引，" +
        "sync_one 只同步指定 id。参数 {\"action\":\"list|sync|sync_one\",\"id\":\"sync_one 时的来源 id\"}。" +
        "接入的云盘文档会与本地知识库一起被 knowledge_rag_search 检索。"
    override val parametersJson = """{
        "type":"object",
        "properties":{
            "action":{"type":"string","description":"list=罗列来源 / sync=同步所有启用 / sync_one=同步单个"},
            "id":{"type":"string","description":"sync_one 时的来源 id（list 给出的 id）"}
        },
        "required":["action"]
    }"""

    override fun run(context: Context, arguments: String): String = runBlocking {
        runCatching {
            val jo = JSONObject(arguments)
            val action = jo.optString("action", "").trim().lowercase()
            val store = QuroExternalSourceStore(context)
            when (action) {
                "list" -> {
                    val list = store.list()
                    if (list.isEmpty()) return@runCatching "尚未配置任何第三方知识来源。"
                    buildString {
                        append("共 ${list.size} 个来源：\n")
                        list.forEach {
                            val status = if (it.enabled) "启用" else "禁用"
                            val last = if (it.lastSync > 0) "上次同步 ${java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(it.lastSync))}" else "从未同步"
                            append("- [${it.id.take(8)}] ${it.name}（$status，${it.type}，$last）")
                            if (it.lastError.isNotBlank()) append(" 错误：${it.lastError}")
                            append("\n")
                        }
                    }
                }
                "sync" -> {
                    val results = QuroExternalKnowledgeSync.syncAllEnabled(context)
                    val ok = results.count { it.error.isBlank() }
                    val msg = results.filter { it.error.isNotBlank() }.joinToString("；") { it.error }
                    "已尝试同步 ${results.size} 个启用来源，成功 $ok 个。" + if (msg.isBlank()) "" else " 失败详情：$msg"
                }
                "sync_one" -> {
                    val id = jo.optString("id", "").trim()
                    val src = store.get(id)
                    if (src == null) return@runCatching "未找到来源 id=$id（先用 action=list 查看）。"
                    val r = QuroExternalKnowledgeSync.syncOne(context, src)
                    store.upsert(src.copy(lastSync = if (r.error.isBlank()) System.currentTimeMillis() else src.lastSync, lastError = r.error))
                    if (r.error.isBlank()) "已同步「${src.name}」，拉取 ${r.files.size} 个文档。" else "同步「${src.name}」失败：${r.error}"
                }
                else -> "未知 action: $action（支持 list / sync / sync_one）"
            }
        }.getOrElse { e -> "知识来源同步失败：${e.message}" }
    }
}
