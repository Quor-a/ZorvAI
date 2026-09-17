package com.ai.assistance.quro.core.tools

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 读取 GitHub 仓库内容：文件源码 / 目录列表 / Issue / PR 文件变更 / PR diff。
 * 已登录 GitHub（auth_service 中 name=github）会带令牌提升限额。
 */
class QuroGithubReadTool : QuroTool {
    override val name = "github_read"
    override val description = "读取 GitHub 仓库内容：文件源码 / 目录列表 / Issue / PR 文件变更 / PR diff。" +
        "参数 {\"owner\":\"所有者（必填）\",\"repo\":\"仓库名（必填）\",\"action\":\"file|dir|issue|pr_files|pr_diff\",\"path\":\"文件路径（file/dir 用）\",\"number\":123（issue/pr 用）,\"ref\":\"分支或 SHA 可选\"}。" +
        "已登录 GitHub（auth_service 中 name=github）会带令牌提升限额。"
    override val parametersJson = """{
        "type":"object",
        "properties":{
            "owner":{"type":"string","description":"仓库所有者（必填）"},
            "repo":{"type":"string","description":"仓库名（必填）"},
            "action":{"type":"string","description":"file=读文件 | dir=列目录 | issue=读Issue | pr_files=PR改动文件 | pr_diff=PR完整diff"},
            "path":{"type":"string","description":"文件路径（file/dir 必填，如 README.md 或 src/）"},
            "number":{"type":"integer","description":"Issue/PR 编号（issue/pr_* 必填）"},
            "ref":{"type":"string","description":"分支/tag/SHA（可选）"}
        },
        "required":["owner","repo","action"]
    }"""

    override fun run(context: Context, arguments: String): String {
        val jo = runCatching { JSONObject(arguments) }.getOrElse { JSONObject() }
        val owner = jo.optString("owner", "").trim()
        val repo = jo.optString("repo", "").trim()
        val action = jo.optString("action", "file").trim()
        if (owner.isEmpty() || repo.isEmpty()) return "❌ 缺少 owner / repo"
        val apiBase = "https://api.github.com"
        val token = QuroAuthStore.get(context, "github")?.token?.takeIf { it.isNotBlank() }
        val headers = mutableMapOf(
            "Accept" to "application/vnd.github+json",
            "User-Agent" to "ZorvAI",
            "X-GitHub-Api-Version" to "2022-11-28"
        )
        token?.let { headers["Authorization"] = "Bearer $it" }
        return runBlocking(Dispatchers.IO) {
            try {
                when (action) {
                    "dir" -> {
                        val path = jo.optString("path", "").trim()
                        val url = "$apiBase/repos/$owner/$repo/contents/$path" + if (jo.has("ref")) "?ref=${jo.optString("ref")}" else ""
                        val json = getRaw(url, headers) ?: return@runBlocking "❌ 无法读取目录"
                        val arr = JSONArray(json)
                        val sb = StringBuilder("📁 $owner/$repo/${path}：\n")
                        for (i in 0 until arr.length()) {
                            val e = arr.optJSONObject(i) ?: continue
                            sb.append("- [${e.optString("type")}] ${e.optString("name")}  ${e.optString("size")}B\n")
                        }
                        sb.toString().trim()
                    }
                    "issue" -> {
                        val num = jo.optInt("number", -1)
                        if (num < 0) return@runBlocking "❌ 缺少 number"
                        val json = getRaw("$apiBase/repos/$owner/$repo/issues/$num", headers) ?: return@runBlocking "❌ 无法读取 Issue #$num"
                        val o = JSONObject(json)
                        val sb = StringBuilder("🐛 Issue #$num [${o.optString("state")}] ${o.optString("title")}\n作者: ${o.optJSONObject("user")?.optString("login")}\n\n")
                        sb.append(o.optString("body").take(4000))
                        sb.toString().trim()
                    }
                    "pr_files" -> {
                        val num = jo.optInt("number", -1)
                        if (num < 0) return@runBlocking "❌ 缺少 number"
                        val json = getRaw("$apiBase/repos/$owner/$repo/pulls/$num/files?per_page=30", headers) ?: return@runBlocking "❌ 无法读取 PR #$num 文件"
                        val arr = JSONArray(json)
                        val sb = StringBuilder("📦 PR #$num 改动文件（${arr.length()}）：\n")
                        for (i in 0 until arr.length()) {
                            val f = arr.optJSONObject(i) ?: continue
                            sb.append("- ${f.optString("filename")} (${f.optString("status")}, +${f.optInt("additions")}/-${f.optInt("deletions")})\n")
                            val patch = f.optString("patch")
                            if (patch.isNotBlank()) sb.append("   ```diff\n${patch.take(2000)}\n   ```\n")
                        }
                        sb.toString().trim()
                    }
                    "pr_diff" -> {
                        val num = jo.optInt("number", -1)
                        if (num < 0) return@runBlocking "❌ 缺少 number"
                        val h2 = headers.toMutableMap()
                        h2["Accept"] = "application/vnd.github.v3.diff"
                        val diff = getRaw("$apiBase/repos/$owner/$repo/pulls/$num", h2) ?: return@runBlocking "❌ 无法读取 PR #$num diff"
                        diff.take(8000)
                    }
                    else -> {
                        val path = jo.optString("path", "").trim()
                        if (path.isEmpty()) return@runBlocking "❌ 缺少 path"
                        val url = "$apiBase/repos/$owner/$repo/contents/$path" + if (jo.has("ref")) "?ref=${jo.optString("ref")}" else ""
                        val json = getRaw(url, headers) ?: return@runBlocking "❌ 无法读取文件"
                        val o = JSONObject(json)
                        if (o.has("content")) {
                            val content = o.optString("content", "")
                            val decoded = String(Base64.decode(content.replace("\n", ""), Base64.DEFAULT), Charsets.UTF_8)
                            val trunc = if (decoded.length > 12000) decoded.take(12000) + "\n…(已截断，共 ${decoded.length} 字符)" else decoded
                            "📄 $owner/$repo/$path（${o.optString("size")}B）：\n```\n$trunc\n```"
                        } else {
                            "⚠️ 该路径不是文件（可能是目录），请改用 action=dir。"
                        }
                    }
                }
            } catch (e: Exception) {
                "❌ GitHub 读取失败：${e.message}"
            }
        }
    }

    private fun getRaw(url: String, headers: Map<String, String>): String? = runCatching {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15000
            readTimeout = 20000
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        val code = conn.responseCode
        val out = if (code in 200..299) conn.inputStream?.bufferedReader()?.readText() else conn.errorStream?.bufferedReader()?.readText()
        conn.disconnect()
        out
    }.getOrNull()
}
