package com.ai.assistance.quro.core.model

import android.util.Log
import com.ai.assistance.quro.core.tools.QuroDownloadUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * HuggingFace 模型检索与下载（自研，HttpURLConnection + org.json）。
 *
 * 用途：离线模型下载中心「搜索官方模型 → 下载 → 真正动态加载进本地推理引擎」。
 * 两类模型分开：
 *  - GGUF：给 llama.cpp 用（手机端量化档 Q2~Q4 最合适），文件名即模型名；
 *  - MNN：给 MNN 用，仓库含 `llm_config.json` + 全部 `<模型>.mnn`（可能多文件）。
 *
 * 镜像策略（网络自适应）：
 *  - 不再固定顺序回退，而是启动时对每个镜像做一次轻量连通性探测，按延迟排序选出
 *    「当前网络下最快可达」的镜像优先使用；下载失败自动切下一个并重新探测。
 *  - API（搜索/列目录）与文件下载分别适配：ghproxy.net 仅代理文件，不参与 API。
 */
object QuroHuggingFace {
    private const val TAG = "QuroHuggingFace"

    // 文件下载镜像前缀（拼在 repoId/resolve/main/... 之前）
    private val FILE_MIRRORS = listOf(
        "https://huggingface.co/",
        "https://hf-mirror.com/",
        "https://ghproxy.net/https://huggingface.co/",
    )
    // 与上面一一对应的「探测用根地址」（用于连通性/延迟探测）
    private val MIRROR_HOSTS = listOf(
        "https://huggingface.co",
        "https://hf-mirror.com",
        "https://ghproxy.net",
    )
    // API 镜像（hf-mirror.com 代理完整站，含 /api；ghproxy 只代理文件，不在内）
    private val API_MIRRORS = listOf("https://huggingface.co", "https://hf-mirror.com")

    // ───────── ModelScope（魔搭社区）来源 ─────────
    // MNN 官方 Android App（alibaba/MNN 的 MnnLlmApp / MnnLlmChat）的模型就来自 ModelScope，
    // 经阿里 OSS CDN 分发，国内稳定可达。作为 MNN 的并行检索/下载源，
    // 解决 HuggingFace 上 MNN 权重不全 / 国内访问不稳的问题。
    private const val MS_BASE = "https://modelscope.cn"
    private const val MS_SEARCH_URL = "$MS_BASE/api/v1/models"          // PUT 检索
    private const val MS_FILES_TMPL = "$MS_BASE/api/v1/models/{id}/repo/files?Recursive=true" // GET 文件树
    private const val MS_RESOLVE_TMPL = "$MS_BASE/models/{id}/resolve/master/"                // GET 文件下载（302→OSS）

    private var cachedFileOrder: List<String>? = null
    private var cachedApiBase: String? = null
    private var probeTs = 0L

    /** 一个可下载的模型文件（GGUF）或模型包（MNN，fileName 为空表示整包）。 */
    data class HfModelFile(
        val repoId: String,
        val fileName: String,        // GGUF 为具体文件名；MNN 为空（整包下载）
        val sizeBytes: Long,
        val type: String,            // "GGUF" / "MNN"
        val source: String = "hf",   // "hf" = HuggingFace，"ms" = ModelScope（MNN 官方手机 App 的来源）
    ) {
        val sizeMB: Long get() = (sizeBytes / 1024 / 1024).coerceAtLeast(0)
    }

    // ───────── 网络自适应镜像选择 ─────────

    /** 探测某根地址的可达性与延迟（ms），不可达返回 null。 */
    private fun latencyOf(host: String): Long? = try {
        val t = System.nanoTime()
        val conn = (URL(host)).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 4000
        conn.readTimeout = 4000
        conn.setRequestProperty("User-Agent", "ZorvAI")
        conn.instanceFollowRedirects = true
        val code = conn.responseCode
        conn.disconnect()
        if (code in 100..599) (System.nanoTime() - t) / 1_000_000 else null
    } catch (e: Exception) {
        null
    }

    /** 当前网络下文件镜像的优先顺序（最快可达排前）。缓存 5 分钟。 */
    private fun fileMirrorOrder(): List<String> {
        val now = System.currentTimeMillis()
        if (cachedFileOrder != null && now - probeTs < 5 * 60_000) return cachedFileOrder!!
        val ranked = MIRROR_HOSTS.mapIndexedNotNull { i, h ->
            latencyOf(h)?.let { it to i }
        }.sortedBy { it.first }.map { FILE_MIRRORS[it.second] }
        val order = if (ranked.isNotEmpty()) ranked else FILE_MIRRORS
        cachedFileOrder = order
        probeTs = now
        Log.i(TAG, "文件镜像顺序(延迟升序): $order")
        return order
    }

    /** 当前网络下 API 基址（最快可达）。缓存 5 分钟。 */
    private fun apiBase(): String {
        val now = System.currentTimeMillis()
        if (cachedApiBase != null && now - probeTs < 5 * 60_000) return cachedApiBase!!
        val ranked = API_MIRRORS.mapNotNull { b -> latencyOf(b)?.let { it to b } }
            .sortedBy { it.first }.map { it.second }
        val base = if (ranked.isNotEmpty()) ranked.first() else API_MIRRORS.first()
        cachedApiBase = base
        probeTs = now
        Log.i(TAG, "API 基址: $base")
        return base
    }

    /** 强制下次重新探测（下载失败切换时调用）。 */
    private fun invalidateProbe() {
        cachedFileOrder = null
        cachedApiBase = null
    }

    // ───────── 底层 HTTP ─────────

    private fun httpGet(path: String, accept: String = "application/json"): Pair<Int, String> {
        val url = apiBase() + path
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection)
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", accept)
            conn.setRequestProperty("User-Agent", "ZorvAI")
            conn.connectTimeout = 15000
            conn.readTimeout = 25000
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.readText() ?: ""
            conn.disconnect()
            code to text
        } catch (e: Exception) {
            Log.e(TAG, "httpGet 失败: $url -> ${e.message}")
            -1 to ""
        }
    }

    /** 列出仓库 main 分支下的 (路径, 大小) 列表（走 API 镜像，递归以覆盖子目录内的权重/配置/tokenizer）。 */
    private fun listTree(repoId: String): List<Pair<String, Long>> {
        val (code, body) = httpGet("/api/models/$repoId/tree/main?recursive=true")
        if (code !in 200..299) return emptyList()
        val arr = runCatching { JSONArray(body) }.getOrNull() ?: return emptyList()
        val out = mutableListOf<Pair<String, Long>>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val path = o.optString("path")
            val size = o.optLong("size", -1L)
            if (path.isNotBlank()) out.add(path to size)
        }
        return out
    }

    /** 优先手机端友好的小量化档；没有时退回到非超大档。 */
    private fun isMobileGguf(name: String): Boolean {
        val n = name.lowercase()
        if (!n.endsWith(".gguf")) return false
        val preferred = listOf("q2_k", "q3_k", "q4_k", "q4_0", "q4_1", "iq2", "iq3", "iq4", "q2", "q3", "q4")
        if (preferred.any { n.contains(it) }) return true
        val big = listOf("q8", "q6", "f16", "f32", "q5_k", "q5_0", "q5_1")
        return !big.any { n.contains(it) }
    }

    /** 搜索含 GGUF 的仓库，返回可下载的 GGUF 文件（已按体积升序，优先小模型）。 */
    suspend fun searchGguf(query: String, limit: Int = 20): List<HfModelFile> = withContext(Dispatchers.IO) {
        val q = URLEncoder.encode(query, "UTF-8")
        val (code, body) = httpGet("/api/models?search=$q&limit=$limit&sort=downloads&direction=-1")
        if (code !in 200..299) return@withContext emptyList()
        val arr = runCatching { JSONArray(body) }.getOrNull() ?: return@withContext emptyList()
        val repoIds = (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.optString("id").takeIf { !it.isNullOrBlank() }
        }.take(8)
        val out = mutableListOf<HfModelFile>()
        for (repoId in repoIds) {
            listTree(repoId)
                .filter { isMobileGguf(it.first) }
                .forEach { (fn, sz) -> out.add(HfModelFile(repoId, fn, sz, "GGUF")) }
        }
        out.sortedBy { it.sizeBytes }
    }

    /**
     * MNN 官方模型清单（ModelScope 上 MNN 组织发布的、经核验含完整运行文件的仓库）。
     * 这是 MNN 搜索的「可靠来源」：实测这些仓库都含 llm.mnn + llm.mnn.weight + llm_config.json + tokenizer.txt，
     * 经阿里 OSS CDN 分发、国内可达。HuggingFace 上的 MNN 权重不全且国内不稳，故 MNN 默认走 ModelScope。
     *
     * 注意：MNN 模型的关键权重文件是 `llm.mnn.weight`（扩展名 .weight），下载白名单必须包含 "weight"，
     * 否则会被当成无关文件丢弃，只剩结构文件 llm.mnn 导致加载器报「目录不完整」。
     */
    private data class MnnPreset(val id: String, val label: String, val approxMB: Int)
    private val MNN_PRESETS = listOf(
        MnnPreset("MNN/Qwen2.5-0.5B-Instruct-MNN", "Qwen2.5 0.5B Instruct", 265),
        MnnPreset("MNN/Qwen2.5-1.5B-Instruct-MNN", "Qwen2.5 1.5B Instruct", 480),
        MnnPreset("MNN/Qwen2.5-3B-Instruct-MNN", "Qwen2.5 3B Instruct", 604),
        MnnPreset("MNN/Qwen2.5-7B-Instruct-MNN", "Qwen2.5 7B Instruct", 1051),
        MnnPreset("MNN/Qwen2-0.5B-Instruct-MNN", "Qwen2 0.5B Instruct", 267),
        MnnPreset("MNN/Qwen2-1.5B-Instruct-MNN", "Qwen2 1.5B Instruct", 455),
        MnnPreset("MNN/Qwen2-7B-Instruct-MNN", "Qwen2 7B Instruct", 1056),
        MnnPreset("MNN/Qwen1.5-0.5B-Chat-MNN", "Qwen1.5 0.5B Chat", 304),
        MnnPreset("MNN/Qwen1.5-1.8B-Chat-MNN", "Qwen1.5 1.8B Chat", 480),
        MnnPreset("MNN/Qwen1.5-4B-Chat-MNN", "Qwen1.5 4B Chat", 760),
        MnnPreset("MNN/Qwen1.5-7B-Chat-MNN", "Qwen1.5 7B Chat", 1208),
        MnnPreset("MNN/Llama-3.2-1B-Instruct-MNN", "Llama 3.2 1B Instruct", 507),
        MnnPreset("MNN/Llama-3.2-3B-Instruct-MNN", "Llama 3.2 3B Instruct", 765),
        MnnPreset("MNN/Phi-2-MNN", "Phi-2", 258),
        MnnPreset("MNN/Gemma-2-2B-it-MNN", "Gemma-2 2B IT", 1147),
        MnnPreset("MNN/Baichuan2-7B-Chat-MNN", "Baichuan2 7B Chat", 985),
        MnnPreset("MNN/ChatGLM2-6B-MNN", "ChatGLM2 6B", 527),
        MnnPreset("MNN/ChatGLM3-6B-MNN", "ChatGLM3 6B", 527),
        MnnPreset("MNN/Qwen2.5-Coder-1.5B-Instruct-MNN", "Qwen2.5-Coder 1.5B", 500),
    )

    /**
     * 搜索 MNN 模型仓库。
     * 优先用经核验的 MNN 官方清单（必定可下、国内可达）；关键词命中清单即返回对应条目。
     * 仅当关键词未命中清单时，才回退到在线检索（HuggingFace + ModelScope，仅保留真实含 .mnn 的仓库），
     * 避免把纯文本大模型误当 MNN、或搜到不可下/不可达的仓库。所有条目 source="ms"。
     */
    suspend fun searchMnn(query: String, limit: Int = 20): List<HfModelFile> = withContext(Dispatchers.IO) {
        val q = query.trim().lowercase()
        // 关键词拆成 token（长度≥2），要求全部命中 preset 的 id 或展示名；空查询返回全部。
        val tokens = q.split(Regex("[^a-z0-9]+")).filter { it.length >= 2 }
        val fromPresets = (if (tokens.isEmpty()) MNN_PRESETS else MNN_PRESETS.filter { p ->
            val hay = (p.id + " " + p.label).lowercase()
            tokens.all { hay.contains(it) }
        }).map { p ->
            HfModelFile(p.id, "", p.approxMB * 1024L * 1024L, "MNN", source = "ms")
        }
        if (fromPresets.isNotEmpty()) return@withContext fromPresets.take(limit)

        // 关键词未命中清单：回退在线检索（仅保留真实含 .mnn 的仓库）。
        val hf = runCatching { searchMnnHf(query, limit) }.getOrElse { emptyList() }
        val ms = runCatching { searchMnnModelScope(query, limit) }.getOrElse { emptyList() }
        val seen = mutableSetOf<String>()
        val merged = mutableListOf<HfModelFile>()
        for (f in hf + ms) {
            if (f.repoId !in seen) { seen.add(f.repoId); merged.add(f) }
        }
        merged
    }

    /** HuggingFace 上的 MNN 模型检索（原 searchMnn 逻辑）。 */
    private suspend fun searchMnnHf(query: String, limit: Int = 20): List<HfModelFile> = withContext(Dispatchers.IO) {
        val q = URLEncoder.encode(query, "UTF-8")
        val (code, body) = httpGet("/api/models?search=$q&limit=$limit&sort=downloads&direction=-1")
        if (code !in 200..299) return@withContext emptyList()
        val arr = runCatching { JSONArray(body) }.getOrNull() ?: return@withContext emptyList()
        val repoIds = (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.optString("id").takeIf { !it.isNullOrBlank() }
        }.take(10)
        val out = mutableListOf<HfModelFile>()
        for (repoId in repoIds) {
            val tree = listTree(repoId)
            val hasConfig = tree.any { it.first.equals("llm_config.json", ignoreCase = true) || it.first.equals("config.json", ignoreCase = true) }
            if (!hasConfig) continue
            val mnns = tree.filter { it.first.endsWith(".mnn", ignoreCase = true) }
            if (mnns.isEmpty()) continue
            val total = mnns.sumOf { it.second }.coerceAtLeast(0L)
            out.add(HfModelFile(repoId, "", total, "MNN", source = "hf"))
        }
        out.sortedBy { it.sizeBytes }
    }

    // ───────── ModelScope（魔搭）MNN 检索 / 下载 ─────────

    /** GET 请求 ModelScope（跟随重定向）。 */
    private fun msGet(url: String): Pair<Int, String> {
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection)
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("User-Agent", "ZorvAI")
            conn.connectTimeout = 15000
            conn.readTimeout = 25000
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText() ?: ""
            conn.disconnect()
            code to text
        } catch (e: Exception) {
            Log.e(TAG, "msGet 失败: $url -> ${e.message}")
            -1 to ""
        }
    }

    /** PUT JSON 到 ModelScope 检索接口。 */
    private fun msPostJson(url: String, jsonBody: String): Pair<Int, String> {
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection)
            conn.requestMethod = "PUT"
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("User-Agent", "ZorvAI")
            conn.connectTimeout = 15000
            conn.readTimeout = 25000
            conn.doOutput = true
            conn.outputStream.use { it.write(jsonBody.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText() ?: ""
            conn.disconnect()
            code to text
        } catch (e: Exception) {
            Log.e(TAG, "msPostJson 失败: $url -> ${e.message}")
            -1 to ""
        }
    }

    /** 取某 ModelScope 仓库的文件树（path, size）。 */
    private fun msListTree(repoId: String): List<Pair<String, Long>> {
        val url = MS_FILES_TMPL.replace("{id}", repoId)
        val (code, body) = msGet(url)
        if (code !in 200..299) return emptyList()
        val jo = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
        val files = jo.optJSONObject("Data")?.optJSONArray("Files") ?: return emptyList()
        val out = mutableListOf<Pair<String, Long>>()
        for (i in 0 until files.length()) {
            val o = files.optJSONObject(i) ?: continue
            val path = o.optString("Path").takeIf { it.isNotBlank() } ?: continue
            val size = o.optLong("Size", -1L)
            out.add(path to size)
        }
        return out
    }

    /** ModelScope 上的 MNN 模型检索（与 HuggingFace 并行来源，source="ms"）。 */
    private suspend fun searchMnnModelScope(query: String, limit: Int = 20): List<HfModelFile> = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("Search", query)
            put("PageSize", limit)
            put("PageNumber", 1)
        }.toString()
        val (code, raw) = msPostJson(MS_SEARCH_URL, body)
        if (code !in 200..299) return@withContext emptyList()
        val jo = runCatching { JSONObject(raw) }.getOrNull() ?: return@withContext emptyList()
        val models = jo.optJSONObject("Data")?.optJSONArray("Models") ?: return@withContext emptyList()
        val ids = (0 until models.length()).mapNotNull { i ->
            val m = models.optJSONObject(i) ?: return@mapNotNull null
            val path = m.optString("Path").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val name = m.optString("Name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            "$path/$name"
        }.take(10)
        val out = mutableListOf<HfModelFile>()
        for (id in ids) {
            val tree = msListTree(id)
            val hasConfig = tree.any { it.first.equals("llm_config.json", ignoreCase = true) || it.first.equals("config.json", ignoreCase = true) }
            if (!hasConfig) continue
            val mnns = tree.filter { it.first.endsWith(".mnn", ignoreCase = true) }
            if (mnns.isEmpty()) continue
            val total = mnns.sumOf { it.second }.coerceAtLeast(0L)
            out.add(HfModelFile(id, "", total, "MNN", source = "ms"))
        }
        out.sortedBy { it.sizeBytes }
    }

    /** 从 ModelScope 下载完整 MNN 模型目录（llm_config.json + .mnn + tokenizer 等）。 */
    private suspend fun downloadFromModelScope(
        repoId: String,
        destDir: File,
        onProgress: (Float) -> Unit,
    ): String = withContext(Dispatchers.IO) {
        destDir.mkdirs()
        val tree = msListTree(repoId)
        val keepExt = setOf("mnn", "json", "txt", "tiktoken", "model", "bin", "weight")
        val files = tree.filter { (p, _) ->
            val name = p.substringAfterLast('/')
            val ext = name.substringAfterLast('.', "").lowercase()
            !name.equals(".gitattributes", ignoreCase = true) &&
                !name.startsWith("README", ignoreCase = true) &&
                !name.startsWith("LICENSE", ignoreCase = true) &&
                !name.endsWith(".md", ignoreCase = true) &&
                ext in keepExt
        }
        if (files.isEmpty()) return@withContext "该 ModelScope 仓库没有任何可下载的 MNN 文件"
        val mnns = files.filter { it.first.endsWith(".mnn", ignoreCase = true) }
        if (mnns.isEmpty()) return@withContext "该仓库没有 .mnn 权重文件，无法作为 MNN 模型下载"

        var done = 0
        val total = files.size
        for ((rel, _) in files) {
            val url = MS_RESOLVE_TMPL.replace("{id}", repoId) + rel
            val target = File(destDir, rel)
            target.parentFile?.mkdirs()
            val r = QuroDownloadUtil.downloadToFile(url, target, "ZorvAI/1.0") { _, _ -> }
            if (!r.startsWith("OK")) return@withContext "下载失败：$rel -> $r"
            done++
            onProgress(done.toFloat() / total)
        }

        // 兼容加载器：保证 root 有 llm_config.json（部分仓库放在子目录或命名为 config.json）。
        val rootCfg = File(destDir, "llm_config.json")
        if (!rootCfg.isFile) {
            val found = files.firstOrNull { it.first.endsWith("llm_config.json", ignoreCase = true) }
                ?: files.firstOrNull { it.first.endsWith("config.json", ignoreCase = true) }
            if (found != null) {
                val src = File(destDir, found.first)
                if (src.isFile) src.copyTo(rootCfg, overwrite = true)
            }
        }
        if (!rootCfg.isFile || rootCfg.length() <= 0L) {
            val listing = destDir.listFiles()?.joinToString { it.name } ?: "(空)"
            return@withContext "下载完成但缺少 llm_config.json（需根目录或子目录含该配置）。目录内容：$listing"
        }
        "OK:已下载 MNN 模型目录（${total} 个文件：含 ${mnns.size} 个权重 + 配置/tokenizer，来源 ModelScope）"
    }

    /** 下载单个 HF 文件（网络自适应镜像顺序回退）。返回 "OK:..." 或错误文本。 */
    suspend fun downloadFile(
        repoId: String,
        fileName: String,
        target: File,
        onProgress: (Float) -> Unit,
    ): String = withContext(Dispatchers.IO) {
        val path = "$repoId/resolve/main/$fileName"
        var lastErr = "下载失败：$fileName"
        for (mirror in fileMirrorOrder()) {
            val url = mirror + path
            val r = QuroDownloadUtil.downloadToFile(url, target, "ZorvAI/1.0") { d, t ->
                onProgress(if (t > 0) (d.toFloat() / t).coerceIn(0f, 1f) else 0f)
            }
            if (r.startsWith("OK")) return@withContext r
            lastErr = r
        }
        invalidateProbe() // 全部失败，下次重新探测（可能网络切换了）
        lastErr
    }

    /**
     * 下载完整 MNN 模型目录：MNN 不是单个文件，而是一个目录（含 llm_config.json + 全部
     * `<模型>.mnn` 权重 + tokenizer 等运行必需文件）。此前只下 config + .mnn，导致 tokenizer
     * 缺失、加载器报「目录不完整」。这里递归拉取整个仓库目录（跳过 README/LICENSE/文档），
     * 保留相对路径原样落盘，确保加载器需要的一切都在。
     */
    suspend fun downloadMnnModel(
        repoId: String,
        destDir: File,
        onProgress: (Float) -> Unit,
        source: String = "hf",
    ): String = withContext(Dispatchers.IO) {
        // ModelScope 来源走专属下载链路（阿里 OSS CDN，国内稳定）。
        if (source == "ms") return@withContext downloadFromModelScope(repoId, destDir, onProgress)
        destDir.mkdirs()
        val tree = listTree(repoId)
        // 只保留 MNN 运行需要的文件：权重/配置/tokenizer 等；跳过文档与元数据。
        val keepExt = setOf("mnn", "json", "txt", "tiktoken", "model", "bin", "weight")
        val files = tree.filter { (p, _) ->
            val name = p.substringAfterLast('/')
            val ext = name.substringAfterLast('.', "").lowercase()
            !name.equals(".gitattributes", ignoreCase = true) &&
                !name.startsWith("README", ignoreCase = true) &&
                !name.startsWith("LICENSE", ignoreCase = true) &&
                !name.endsWith(".md", ignoreCase = true) &&
                ext in keepExt
        }
        if (files.isEmpty()) return@withContext "该仓库没有任何可下载的 MNN 文件"
        val mnns = files.filter { it.first.endsWith(".mnn", ignoreCase = true) }
        if (mnns.isEmpty()) return@withContext "该仓库没有 .mnn 权重文件，无法作为 MNN 模型下载"

        var done = 0
        val total = files.size
        for ((rel, _) in files) {
            val target = File(destDir, rel)
            target.parentFile?.mkdirs()
            val r = downloadFile(repoId, rel, target) {}
            if (!r.startsWith("OK")) return@withContext "下载失败：$rel -> $r"
            done++
            onProgress(done.toFloat() / total)
        }

        // 兼容加载器：保证 root 有 llm_config.json（部分仓库放在子目录或命名为 config.json）。
        val rootCfg = File(destDir, "llm_config.json")
        if (!rootCfg.isFile) {
            val found = files.firstOrNull { it.first.endsWith("llm_config.json", ignoreCase = true) }
                ?: files.firstOrNull { it.first.endsWith("config.json", ignoreCase = true) }
            if (found != null) {
                val src = File(destDir, found.first)
                if (src.isFile) src.copyTo(rootCfg, overwrite = true)
            }
        }
        if (!rootCfg.isFile || rootCfg.length() <= 0L) {
            val listing = destDir.listFiles()?.joinToString { it.name } ?: "(空)"
            return@withContext "下载完成但缺少 llm_config.json（需根目录或子目录含该配置）。目录内容：$listing"
        }
        "OK:已下载 MNN 模型目录（${total} 个文件：含 ${mnns.size} 个权重 + 配置/tokenizer）"
    }
}
