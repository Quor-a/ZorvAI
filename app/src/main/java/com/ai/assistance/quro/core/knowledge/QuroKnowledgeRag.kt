package com.ai.assistance.quro.core.knowledge

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.ai.assistance.quro.core.tools.QuroTool
import com.ai.assistance.quro.core.model.QuroModelConfigRepository
import com.ai.assistance.quro.core.tools.QuroKnowledgeFiles
import com.ai.assistance.quro.ui.extractOfficeText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** FloatArray ↔ ByteArray（SQLite BLOB 存向量用，Kotlin 无内建转换）。 */
fun FloatArray.toByteArray(): ByteArray {
    val buf = ByteBuffer.allocate(size * 4).order(ByteOrder.BIG_ENDIAN)
    for (f in this) buf.putFloat(f)
    return buf.array()
}

fun ByteArray.toFloatArray(): FloatArray {
    val n = size / 4
    val out = FloatArray(n)
    val buf = ByteBuffer.wrap(this).order(ByteOrder.BIG_ENDIAN)
    for (i in 0 until n) out[i] = buf.getFloat()
    return out
}

/** 索引覆盖的文档扩展名。 */
private val KB_INDEX_EXTS = setOf("md", "txt", "json", "docx", "xlsx", "pptx")

/**
 * Quro 知识库 RAG（C3 重做 + #589 增强）。
 *
 * 检索双模式：
 *  - 语义模式（SEMANTIC）：用户配置了可用的 Embedding Key，走远程 /v1/embeddings → 向量余弦召回（真语义）。
 *  - 词法模式（LEXICAL）：无 Key 或远程 Embedding 报错时，自动降级为本地「CJK 二元分词 + 词频余弦」混合检索，
 *    零网络、零依赖也能给出可用的相关性排序，彻底告别旧版弱哈希向量（无 Key 时几乎随机命中）。
 *
 * 索引同步（#589）：pipeline 记录「文件指纹 manifest」（路径+修改时间+大小），每次检索/同步时增量比对——
 * 新增/变更文件自动重索引，删除文件自动清库，UI 增删文档与 AI 检索看到的是同一份索引，消除「知识库不对」的体感。
 */
interface QuroEmbedder {
    /** 是否为词法降级嵌入（向量恒为零，检索改走文本打分）。 */
    val isLexical: Boolean get() = false
    /** 批量把文本转成向量。 */
    suspend fun embed(texts: List<String>): List<FloatArray>
}

/** 远程 Embedding（OpenAI 兼容 /v1/embeddings）。真语义，零新增依赖（复用 OkHttp）。 */
class QuroRemoteEmbedder(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String = "text-embedding-3-small",
) : QuroEmbedder {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    override suspend fun embed(texts: List<String>): List<FloatArray> = withContext(Dispatchers.IO) {
        val url = baseUrl.trim().trimEnd('/') + "/embeddings"
        val body = JSONObject().put("model", model).put("input", JSONArray(texts)).toString()
        val req = Request.Builder().url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) error("embedding HTTP ${resp.code}: ${raw.take(200)}")
            val root = JSONObject(raw)
            val arr = root.getJSONArray("data")
            val out = ArrayList<FloatArray>(arr.length())
            for (i in 0 until arr.length()) {
                val emb = arr.getJSONObject(i).getJSONArray("embedding")
                val f = FloatArray(emb.length()) { j -> emb.getDouble(j).toFloat() }
                out.add(f)
            }
            out
        }
    }
}

/** 词法降级嵌入：向量恒为零（不参与余弦），仅作为「无 Key」标记；检索改走文本打分。 */
class QuroLexicalEmbedder : QuroEmbedder {
    override val isLexical: Boolean get() = true
    override suspend fun embed(texts: List<String>): List<FloatArray> =
        texts.map { FloatArray(1) }
}

/** 解析当前应使用的嵌入器：有 Key 且配了端点走远程（语义），否则词法降级。 */
fun resolveEmbedder(apiKey: String, baseUrl: String): QuroEmbedder =
    if (apiKey.isNotBlank() && baseUrl.isNotBlank()) QuroRemoteEmbedder(baseUrl = baseUrl, apiKey = apiKey) else QuroLexicalEmbedder()

/** 构造一个绑定当前上下文的 RAG 管线（统一供工具/UI 调用）。 */
fun buildRagPipeline(context: Context): QuroRagPipeline {
    val cfg = QuroModelConfigRepository(context).load()
    val embedder = resolveEmbedder(cfg.apiKey, cfg.baseUrl)
    val store = QuroSqliteVectorStore(context, "quro_rag.db")
    return QuroRagPipeline(context, embedder, store)
}

/** 向量库契约。 */
interface QuroVectorStore {
    fun upsert(chunk: QuroChunk)
    fun query(vector: FloatArray, topK: Int): List<ScoredChunk>
    fun deleteByDoc(docId: String)
    fun isEmpty(): Boolean
    fun count(): Int
    /** 返回全部分块（词法打分用，含文本）。 */
    fun allChunks(): List<QuroChunk>
    /** 清空索引（分块 + manifest）。 */
    fun clear()
}

data class QuroChunk(
    val id: String,
    val docId: String,
    val text: String,
    val embedding: FloatArray,
    val meta: Map<String, String> = emptyMap(),
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as QuroChunk
        return id == other.id
    }
    override fun hashCode(): Int = id.hashCode()
}

data class ScoredChunk(val chunk: QuroChunk, val score: Float)

/** 基于 Android 内置 SQLite 的向量库（无 Room 依赖）。小库暴力余弦 + manifest 指纹同步。 */
class QuroSqliteVectorStore(context: Context, dbName: String) :
    SQLiteOpenHelper(context.applicationContext, dbName, null, 2), QuroVectorStore {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE rag_chunks (" +
                "id TEXT PRIMARY KEY, doc_id TEXT, text TEXT, embedding BLOB, ctime INTEGER)",
        )
        db.execSQL("CREATE TABLE rag_manifest (path TEXT PRIMARY KEY, sig TEXT)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldV: Int, newV: Int) {
        if (oldV < 2) db.execSQL("CREATE TABLE IF NOT EXISTS rag_manifest (path TEXT PRIMARY KEY, sig TEXT)")
    }

    override fun upsert(chunk: QuroChunk) {
        val cv = ContentValues().apply {
            put("id", chunk.id)
            put("doc_id", chunk.docId)
            put("text", chunk.text)
            put("embedding", chunk.embedding.toByteArray())
            put("ctime", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("rag_chunks", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    override fun deleteByDoc(docId: String) {
        writableDatabase.delete("rag_chunks", "doc_id=?", arrayOf(docId))
    }

    override fun query(vector: FloatArray, topK: Int): List<ScoredChunk> {
        val cur = readableDatabase.rawQuery("SELECT id,doc_id,text,embedding FROM rag_chunks", null)
        val out = ArrayList<ScoredChunk>()
        cur.use {
            while (it.moveToNext()) {
                val emb = it.getBlob(it.getColumnIndexOrThrow("embedding")).toFloatArray()
                val score = cosine(vector, emb)
                out.add(
                    ScoredChunk(
                        QuroChunk(
                            id = it.getString(0),
                            docId = it.getString(1),
                            text = it.getString(2),
                            embedding = emb,
                        ),
                        score,
                    ),
                )
            }
        }
        out.sortByDescending { it.score }
        return out.take(topK)
    }

    override fun allChunks(): List<QuroChunk> {
        val cur = readableDatabase.rawQuery("SELECT id,doc_id,text,embedding FROM rag_chunks", null)
        val out = ArrayList<QuroChunk>()
        cur.use {
            while (it.moveToNext()) {
                out.add(
                    QuroChunk(
                        id = it.getString(0),
                        docId = it.getString(1),
                        text = it.getString(2),
                        embedding = it.getBlob(it.getColumnIndexOrThrow("embedding")).toFloatArray(),
                    ),
                )
            }
        }
        return out
    }

    override fun isEmpty(): Boolean =
        readableDatabase.query("rag_chunks", arrayOf("id"), null, null, null, null, null, "1")
            .use { !it.moveToFirst() }

    override fun count(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM rag_chunks", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    override fun clear() {
        writableDatabase.execSQL("DELETE FROM rag_chunks")
        writableDatabase.execSQL("DELETE FROM rag_manifest")
    }

    fun loadManifest(): Map<String, String> {
        val cur = readableDatabase.rawQuery("SELECT path,sig FROM rag_manifest", null)
        val out = LinkedHashMap<String, String>()
        cur.use {
            while (it.moveToNext()) out[it.getString(0)] = it.getString(1)
        }
        return out
    }

    fun saveManifest(map: Map<String, String>) {
        writableDatabase.execSQL("DELETE FROM rag_manifest")
        writableDatabase.beginTransaction()
        try {
            for ((p, s) in map) {
                val cv = ContentValues().apply { put("path", p); put("sig", s) }
                writableDatabase.insertWithOnConflict("rag_manifest", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            }
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        var dot = 0f; var na = 0f; var nb = 0f
        for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        val denom = sqrt(na) * sqrt(nb)
        return if (denom == 0f) 0f else dot / denom
    }
}

/** RAG 管线：索引（文档→分块→向量→入库）与检索（混合：语义优先，词法降级）。 */
class QuroRagPipeline(
    private val context: Context,
    private val embedder: QuroEmbedder,
    private val store: QuroVectorStore,
    private val chunkSize: Int = 800,
    private val chunkOverlap: Int = 80,
) {
    /** 增量同步：比对文件指纹，新增/变更重索引、删除清库，并写回 manifest。 */
    suspend fun syncDirectory(root: File) {
        ensureSeeded()
        val files = if (root.exists()) {
            root.walkTopDown()
                .filter { it.isFile && it.extension.lowercase() in KB_INDEX_EXTS }
                .toList()
        } else {
            emptyList()
        }
        val current = files.associate { it.absolutePath to sigOf(it) }
        val prev = (store as? QuroSqliteVectorStore)?.loadManifest().orEmpty()
        for (p in prev.keys) if (p !in current) store.deleteByDoc(p)
        for (f in files) {
            val s = current[f.absolutePath]!!
            if (prev[f.absolutePath] != s) indexFile(f)
        }
        (store as? QuroSqliteVectorStore)?.saveManifest(current)
    }

    /** 全量重建索引（清库后重扫 + 重写 manifest）。 */
    suspend fun reindex(root: File) {
        (store as? QuroSqliteVectorStore)?.clear()
        indexDirectory(root)
        val files = if (root.exists()) {
            root.walkTopDown()
                .filter { it.isFile && it.extension.lowercase() in KB_INDEX_EXTS }
                .toList()
        } else {
            emptyList()
        }
        (store as? QuroSqliteVectorStore)?.saveManifest(files.associate { it.absolutePath to sigOf(it) })
    }

    /** 确保索引与当前文件一致（增量同步，替代旧版「仅空才建一次」）。 */
    suspend fun ensureIndexed(root: File) = syncDirectory(root)

    suspend fun indexDirectory(root: File) {
        ensureSeeded()
        if (!root.exists()) return
        root.walkTopDown()
            .filter { it.isFile && it.extension.lowercase() in KB_INDEX_EXTS }
            .forEach { indexFile(it) }
    }

    /** 首次若私有知识库目录为空，从内置 assets/knowledge_base 播种示例文档，开箱即用。 */
    private fun ensureSeeded() {
        val dest = QuroKnowledgeFiles.dir(context)
        val assetDir = "knowledge_base"
        val names = runCatching { context.assets.list(assetDir) }.getOrNull()
        if (names.isNullOrEmpty()) return
        if (dest.exists() && dest.listFiles()?.any { it.isFile } == true) return
        dest.mkdirs()
        for (n in names) {
            runCatching {
                context.assets.open("$assetDir/$n").use { ins ->
                    File(dest, n).outputStream().use { outs -> ins.copyTo(outs) }
                }
            }
        }
    }

    suspend fun indexFile(file: File) {
        val text = readText(file)
        if (text.isBlank()) return
        val docId = file.absolutePath
        store.deleteByDoc(docId)
        val chunks = chunkText(text)
        if (chunks.isEmpty()) return
        val embs = embedder.embed(chunks)
        chunks.zip(embs).forEachIndexed { i, (c, e) ->
            store.upsert(
                QuroChunk(
                    id = "$docId#$i",
                    docId = docId,
                    text = c,
                    embedding = e,
                    meta = mapOf("name" to file.name),
                ),
            )
        }
    }

    private fun readText(file: File): String {
        val ext = file.extension.lowercase()
        return if (ext in setOf("docx", "xlsx", "pptx")) extractOfficeText(file)
        else runCatching { file.readText() }.getOrDefault("")
    }

    private fun chunkText(text: String): List<String> {
        val paras = text.split(Regex("\\n+")).map { it.trim() }.filter { it.isNotBlank() }
        val chunks = ArrayList<String>()
        val sb = StringBuilder()
        for (p in paras) {
            if (sb.length + p.length > chunkSize && sb.isNotEmpty()) {
                chunks.add(sb.toString().trim())
                sb.clear()
                sb.append(p.take(chunkOverlap))
            }
            sb.append(p).append("\n")
        }
        if (sb.isNotBlank()) chunks.add(sb.toString().trim())
        return if (chunks.isEmpty()) listOf(text.take(chunkSize)) else chunks
    }

    private fun sigOf(f: File): String = "${f.lastModified()}:${f.length()}"

    suspend fun retrieve(query: String, topK: Int = 4): String {
        val root = QuroKnowledgeFiles.dir(context)
        syncDirectory(root)
        if (store.isEmpty()) {
            return "（知识库尚未建立索引：请先调用 action=reindex，或放入文档到 knowledge_base 目录后再次检索。）"
        }
        val hits = if (embedder.isLexical) {
            lexicalRetrieve(query, topK)
        } else {
            try {
                val q = embedder.embed(listOf(query)).firstOrNull() ?: return "（无法生成查询向量）"
                // 维度不一致（如从「无 Key 词法」切到「有 Key 语义」，旧索引是 1 维零向量）→ 强制重建后再查
                val dimMismatch = store.allChunks().firstOrNull()?.embedding?.size != q.size
                if (dimMismatch) reindex(root)
                store.query(q, topK)
            } catch (e: Exception) {
                // 远程 Embedding 失败（网关不支持 / 限流 / Key 失效）→ 优雅降级词法
                lexicalRetrieve(query, topK)
            }
        }
        if (hits.isEmpty()) return "（未在知识库中找到相关内容）"
        val sb = StringBuilder()
        val mode = if (embedder.isLexical) "词法" else "语义"
        sb.append("以下是与「$query」最相关的知识库片段（${mode}检索，共 ${hits.size} 条）：\n")
        hits.forEachIndexed { i, h ->
            sb.append("\n[${i + 1}] 来源：${h.chunk.meta["name"] ?: h.chunk.docId}\n")
            sb.append(h.chunk.text.take(600)).append("\n")
        }
        return sb.toString()
    }

    /** 词法混合检索：CJK 二元分词 + 拉丁词，词频余弦打分（TF 余弦）。零依赖、无 Key 可用。 */
    private fun lexicalRetrieve(query: String, topK: Int): List<ScoredChunk> {
        val qTf = tokenizeTf(query)
        if (qTf.isEmpty()) return emptyList()
        val qNorm = sqrt(qTf.values.sumOf { it.toDouble() * it }.toFloat())
        val scored = ArrayList<ScoredChunk>()
        for (c in store.allChunks()) {
            val cTf = tokenizeTf(c.text)
            var dot = 0f
            for ((t, qf) in qTf) {
                val cf = cTf[t] ?: 0
                if (cf > 0) dot += qf * cf
            }
            if (dot <= 0f) continue
            var cNorm = 0f
            for (v in cTf.values) cNorm += v * v
            val denom = qNorm * sqrt(cNorm)
            val score = if (denom == 0f) 0f else dot / denom
            if (score > 0f) scored.add(ScoredChunk(c, score))
        }
        scored.sortByDescending { it.score }
        return scored.take(topK)
    }

    companion object {
        /** 文本 → 词频表：拉丁词（≥2 字母）直接取词；CJK 取二元组 + 单字兜底。 */
        fun tokenizeTf(text: String): Map<String, Int> {
            val tf = LinkedHashMap<String, Int>()
            val lower = text.lowercase()
            Regex("[a-z0-9]{2,}").findAll(lower).forEach { tf[it.value] = (tf[it.value] ?: 0) + 1 }
            val sb = StringBuilder()
            for (ch in text) {
                val isCjk = ch.code in 0x4E00..0x9FFF || ch.code in 0x3400..0x4DBF || ch.code in 0x3000..0x303F
                if (isCjk) sb.append(ch) else {
                    if (sb.length >= 2) addCjkGrams(tf, sb.toString())
                    sb.clear()
                }
            }
            if (sb.length >= 2) addCjkGrams(tf, sb.toString())
            return tf
        }

        private fun addCjkGrams(tf: LinkedHashMap<String, Int>, s: String) {
            for (i in 0 until s.length - 1) {
                val bg = s.substring(i, i + 2)
                tf[bg] = (tf[bg] ?: 0) + 1
            }
            for (ch in s) tf[ch.toString()] = (tf[ch.toString()] ?: 0) + 1
        }
    }
}

/** RAG 语义/词法混合检索工具（替代/增强 knowledge_search）。 */
class QuroRagKnowledgeTool : QuroTool {
    override val name = "knowledge_rag_search"
    override val description =
        "（RAG 混合检索）在本地知识库做检索并返回最相关的文档片段。优先用语义（若已配置可用 Embedding Key），" +
        "否则自动降级为本地「CJK 二元分词 + 词频余弦」词法检索（零依赖、无 Key 也能用）。" +
        "参数 {\"query\":\"问题\",\"limit\":4}；或 {\"action\":\"reindex\"} 全量重建索引、{\"action\":\"count\"} 查看索引量。" +
        "找不到时再考虑 knowledge_search（关键词）。"
    override val parametersJson = """{
        "type":"object",
        "properties":{
            "action":{"type":"string","description":"可选：reindex=全量重建索引 / count=查看索引量，缺省为检索"},
            "query":{"type":"string","description":"检索问题（action 缺省时必填）"},
            "limit":{"type":"integer","description":"返回条数，默认 4"}
        }
    }"""

    override fun run(context: Context, arguments: String): String = runBlocking {
        runCatching {
            val jo = JSONObject(arguments)
            val action = jo.optString("action", "").trim().lowercase()
            val pipeline = buildRagPipeline(context)
            val store = QuroSqliteVectorStore(context, "quro_rag.db")
            when (action) {
                "reindex" -> {
                    pipeline.reindex(QuroKnowledgeFiles.dir(context))
                    "已全量重建知识库索引，共 ${store.count()} 个片段。"
                }
                "count" -> {
                    val cfg = QuroModelConfigRepository(context).load()
                    val mode = if (cfg.apiKey.isNotBlank()) "语义（远程 Embedding）优先，失败降级词法" else "词法（无 Key，本地 CJK 分词）"
                    "知识库向量索引共 ${store.count()} 个片段；当前模式：$mode。"
                }
                else -> {
                    val query = jo.optString("query", "").trim()
                    if (query.isEmpty()) return@runCatching "缺少 query 参数（或 action=reindex 重建索引）。"
                    val limit = jo.optInt("limit", 4).coerceIn(1, 20)
                    pipeline.retrieve(query, limit)
                }
            }
        }.getOrElse { e -> "知识库 RAG 检索失败：${e.message}" }
    }
}
