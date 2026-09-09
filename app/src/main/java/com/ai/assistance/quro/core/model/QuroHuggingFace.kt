package com.ai.assistance.quro.core.model

import android.util.Log
import com.ai.assistance.quro.core.tools.QuroDownloadUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
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

    private var cachedFileOrder: List<String>? = null
    private var cachedApiBase: String? = null
    private var probeTs = 0L

    /** 一个可下载的模型文件（GGUF）或模型包（MNN，fileName 为空表示整包）。 */
    data class HfModelFile(
        val repoId: String,
        val fileName: String,        // GGUF 为具体文件名；MNN 为空（整包下载）
        val sizeBytes: Long,
        val type: String,            // "GGUF" / "MNN"
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

    /** 列出仓库 main 分支下的 (路径, 大小) 列表（走 API 镜像）。 */
    private fun listTree(repoId: String): List<Pair<String, Long>> {
        val (code, body) = httpGet("/api/models/$repoId/tree/main")
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
     * 搜索 MNN 模型仓库：以「仓库」为粒度返回（一个仓库一条），fileName 留空表示整包下载。
     * 判定标准：仓库根含 llm_config.json（或 config.json）且至少一个 .mnn 文件。
     */
    suspend fun searchMnn(query: String, limit: Int = 20): List<HfModelFile> = withContext(Dispatchers.IO) {
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
            out.add(HfModelFile(repoId, "", total, "MNN"))
        }
        out.sortedBy { it.sizeBytes }
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
     * 下载完整 MNN 模型包：llm_config.json（必要时用 config.json 改名）+ 仓库内全部 .mnn 权重文件，
     * 落进 destDir。解决此前「只下一个 .mnn」导致 llm_config.json 引用的权重缺失、加载失败的问题。
     */
    suspend fun downloadMnnModel(
        repoId: String,
        destDir: File,
        onProgress: (Float) -> Unit,
    ): String = withContext(Dispatchers.IO) {
        destDir.mkdirs()
        val tree = listTree(repoId)
        val mnns = tree.filter { it.first.endsWith(".mnn", ignoreCase = true) }
        if (mnns.isEmpty()) return@withContext "该仓库没有 .mnn 权重文件，无法作为 MNN 模型下载"

        // 配置文件：优先 llm_config.json，否则把 config.json 复制为 llm_config.json 以兼容加载器
        val cfg = File(destDir, "llm_config.json")
        var r1 = downloadFile(repoId, "llm_config.json", cfg) {}
        if (!r1.startsWith("OK")) {
            val alt = File(destDir, "config.json")
            r1 = downloadFile(repoId, "config.json", alt) {}
            if (r1.startsWith("OK") && !cfg.exists()) alt.copyTo(cfg, overwrite = true)
        }
        if (!cfg.isFile || cfg.length() <= 0L) return@withContext "下载 llm_config.json 失败：$r1"

        var done = 0
        val total = mnns.size
        for ((fn) in mnns) {
            val w = File(destDir, fn)
            val r = downloadFile(repoId, fn, w) {}
            if (!r.startsWith("OK")) return@withContext "下载权重失败：$r"
            done++
            onProgress(done.toFloat() / total)
        }
        "OK:已下载 MNN 模型包（${total} 个权重文件 + 配置）"
    }
}
