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
 *  - MNN：给 MNN 用，仓库必须含 `llm_config.json` + `<模型>.mnn`。
 *
 * 下载走 HF 官方 / hf-mirror / ghproxy 三镜像回退，流式进度。
 */
object QuroHuggingFace {
    private const val TAG = "QuroHuggingFace"
    private const val API = "https://huggingface.co/api"
    private val MIRRORS = listOf(
        "https://huggingface.co/",
        "https://hf-mirror.com/",
        "https://ghproxy.net/https://huggingface.co/",
    )

    /** 一个可下载的模型文件（GGUF 或 MNN）。 */
    data class HfModelFile(
        val repoId: String,
        val fileName: String,
        val sizeBytes: Long,
        val type: String, // "GGUF" / "MNN"
    ) {
        val sizeMB: Long get() = (sizeBytes / 1024 / 1024).coerceAtLeast(0)
    }

    private fun httpGet(url: String, accept: String = "application/json"): Pair<Int, String> {
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

    /** 列出仓库 main 分支下的 (路径, 大小) 列表。 */
    private fun listTree(repoId: String): List<Pair<String, Long>> {
        val (code, body) = httpGet("$API/models/$repoId/tree/main")
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
        val (code, body) = httpGet("$API/models?search=$q&limit=$limit&sort=downloads&direction=-1")
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

    /** 搜索含 MNN 模型（仓库须有 llm_config.json + .mnn）的仓库，返回 .mnn 文件。 */
    suspend fun searchMnn(query: String, limit: Int = 20): List<HfModelFile> = withContext(Dispatchers.IO) {
        val q = URLEncoder.encode(query, "UTF-8")
        val (code, body) = httpGet("$API/models?search=$q&limit=$limit&sort=downloads&direction=-1")
        if (code !in 200..299) return@withContext emptyList()
        val arr = runCatching { JSONArray(body) }.getOrNull() ?: return@withContext emptyList()
        val repoIds = (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.optString("id").takeIf { !it.isNullOrBlank() }
        }.take(10)
        val out = mutableListOf<HfModelFile>()
        for (repoId in repoIds) {
            val tree = listTree(repoId)
            val hasConfig = tree.any { it.first.equals("llm_config.json", ignoreCase = true) }
            if (!hasConfig) continue
            tree.filter { it.first.endsWith(".mnn", ignoreCase = true) }
                .forEach { (fn, sz) -> out.add(HfModelFile(repoId, fn, sz, "MNN")) }
        }
        out.sortedBy { it.sizeBytes }
    }

    /** 下载单个 HF 文件（多镜像回退）。返回 "OK:..." 或错误文本。 */
    suspend fun downloadFile(
        repoId: String,
        fileName: String,
        target: File,
        onProgress: (Float) -> Unit,
    ): String = withContext(Dispatchers.IO) {
        val path = "$repoId/resolve/main/$fileName"
        for (mirror in MIRRORS) {
            val url = mirror + path
            val r = QuroDownloadUtil.downloadToFile(url, target, "ZorvAI/1.0") { d, t ->
                onProgress(if (t > 0) (d.toFloat() / t).coerceIn(0f, 1f) else 0f)
            }
            if (r.startsWith("OK")) return@withContext r
        }
        "下载失败：$fileName（所有镜像均不可用）"
    }

    /** 下载 MNN 模型：llm_config.json（必要时用 config.json 改名）+ 权重文件，落进 destDir。 */
    suspend fun downloadMnnModel(
        repoId: String,
        mnnFileName: String,
        destDir: File,
        onProgress: (Float) -> Unit,
    ): String = withContext(Dispatchers.IO) {
        destDir.mkdirs()
        val cfg = File(destDir, "llm_config.json")
        var r1 = downloadFile(repoId, "llm_config.json", cfg) {}
        if (!r1.startsWith("OK")) {
            // 部分仓库用 config.json 命名，复制为 llm_config.json 以兼容加载器
            val alt = File(destDir, "config.json")
            r1 = downloadFile(repoId, "config.json", alt) {}
            if (r1.startsWith("OK") && !cfg.exists()) alt.copyTo(cfg, overwrite = true)
        }
        if (!cfg.isFile || cfg.length() <= 0L) return@withContext "下载 llm_config.json 失败：$r1"
        val w = File(destDir, mnnFileName)
        downloadFile(repoId, mnnFileName, w, onProgress)
    }
}
