package com.ai.assistance.quro.core.github

import android.content.Context
import com.ai.assistance.quro.BuildConfig
import com.ai.assistance.quro.core.tools.AuthService
import com.ai.assistance.quro.core.tools.QuroAuthStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * GitHub REST 客户端（自研，基于 HttpURLConnection + org.json，零额外依赖）。
 *
 * Token 复用既有私有保险库 [QuroAuthStore]（服务名固定为 "github"，type=bearer）。
 * 设置页登录即写入该服务；登录态由 [isLoggedIn] 判断。
 *
 * 覆盖能力：
 *  - 账户信息 / 仓库列表 / 我的 Issue / 我的 PR
 *  - Star / Unstar / 是否已 Star
 *  - 通知列表
 *  - 创建 Issue
 *  - 搜索：仓库 / 代码 / Issue / 用户
 */
object QuroGitHubClient {
    private const val API = "https://api.github.com"
    private const val OAUTH_DEVICE_CODE = "https://github.com/login/device/code"
    private const val OAUTH_TOKEN = "https://github.com/login/oauth/access_token"
    private const val PREF = "quro_github_oauth"
    // 设备流默认申请范围：仓库读写 / 用户资料 / 邮箱 / 通知 / 组织 / Gist
    private const val DEFAULT_SCOPE = "repo read:user user:email notifications read:org gist"

    // ───────── OAuth 设备流：Client ID 持久化（用户在登录屏填入，或构建时烤进 BuildConfig）─────────
    fun getClientId(ctx: Context): String {
        val saved = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString("client_id", "") ?: ""
        if (saved.isNotBlank()) return saved
        return BuildConfig.GITHUB_CLIENT_ID
    }

    fun setClientId(ctx: Context, id: String) =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString("client_id", id.trim()).apply()

    // ───────── 数据模型 ─────────
    data class Account(
        val login: String,
        val name: String,
        val htmlUrl: String,
        val bio: String,
        val publicRepos: Int,
        val followers: Int,
        val following: Int,
    )

    data class Repo(
        val id: Long,
        val name: String,
        val fullName: String,
        val owner: String,
        val htmlUrl: String,
        val description: String,
        val stars: Int,
        val language: String,
        val updatedAt: String,
    )

    data class Issue(
        val id: Long,
        val number: Int,
        val title: String,
        val state: String,
        val htmlUrl: String,
        val repo: String,
    )

    data class CodeHit(
        val name: String,
        val path: String,
        val htmlUrl: String,
        val repo: String,
    )

    data class User(
        val login: String,
        val htmlUrl: String,
        val type: String,
        val followers: Int,
    )

    data class Notification(
        val id: String,
        val title: String,
        val repo: String,
        val reason: String,
        val unread: Boolean,
        val htmlUrl: String,
    )

    // ───────── OAuth 设备流数据模型 ─────────
    data class DeviceCode(
        val deviceCode: String,
        val userCode: String,
        val verificationUri: String,
        val verificationUriComplete: String,
        val expiresIn: Int,
        val interval: Int,
    )

    sealed class DeviceLoginResult {
        data class Token(val value: String, val scope: String) : DeviceLoginResult()
        data class Error(val message: String) : DeviceLoginResult()
        object Cancelled : DeviceLoginResult()
    }

    // ───────── 鉴权 ─────────
    fun isLoggedIn(ctx: Context): Boolean =
        QuroAuthStore.get(ctx, "github")?.token?.isNotBlank() == true

    fun getToken(ctx: Context): String? =
        QuroAuthStore.get(ctx, "github")?.token?.takeIf { it.isNotBlank() }

    /** 保存 GitHub PAT（写入私有保险库，服务名 "github"）。 */
    fun login(ctx: Context, pat: String): Boolean {
        val t = pat.trim()
        if (t.isEmpty()) return false
        QuroAuthStore.save(ctx, AuthService("github", "bearer", t, "", ""))
        return true
    }

    /** 校验一个 GitHub Token 是否有效（直接带令牌请求 /user，不依赖保险库）。有效返回账户信息，否则 null。 */
    suspend fun validateToken(token: String): Account? = withContext(Dispatchers.IO) {
        val t = token.trim()
        if (t.isEmpty()) return@withContext null
        val conn = (URL("$API/user").openConnection() as HttpURLConnection)
        conn.requestMethod = "GET"
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("User-Agent", "ZorvAI")
        conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        conn.setRequestProperty("Authorization", "Bearer $t")
        conn.connectTimeout = 15000
        conn.readTimeout = 20000
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText() ?: ""
        conn.disconnect()
        if (code !in 200..299) return@withContext null
        val jo = runCatching { JSONObject(text) }.getOrNull() ?: return@withContext null
        Account(
            login = jo.optString("login"),
            name = jo.optString("name").ifBlank { jo.optString("login") },
            htmlUrl = jo.optString("html_url"),
            bio = jo.optString("bio"),
            publicRepos = jo.optInt("public_repos"),
            followers = jo.optInt("followers"),
            following = jo.optInt("following"),
        )
    }

    /** 校验并保存一个 GitHub Token（PAT 登录）：成功返回账户，失败返回 null。 */
    suspend fun loginWithToken(ctx: Context, token: String): Account? {
        val acc = validateToken(token) ?: return null
        if (!login(ctx, token)) return null
        return acc
    }

    fun logout(ctx: Context) = QuroAuthStore.remove(ctx, "github")

    // ───────── 底层 HTTP ─────────
    private fun request(
        ctx: Context,
        method: String,
        path: String,
        body: String? = null,
        auth: Boolean = true,
    ): Pair<Int, String> {
        val url = if (path.startsWith("http")) path else "$API$path"
        val conn = (URL(url).openConnection() as HttpURLConnection)
        conn.requestMethod = method
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("User-Agent", "ZorvAI")
        conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        if (auth) getToken(ctx)?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
        conn.connectTimeout = 15000
        conn.readTimeout = 20000
        if (body != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.readText() ?: ""
        conn.disconnect()
        return code to text
    }

    private fun get(ctx: Context, path: String, auth: Boolean = true) = request(ctx, "GET", path, auth = auth)

    // ───────── OAuth 设备流：表单提交（非 JSON）─────────
    private fun postForm(url: String, params: Map<String, String>): Pair<Int, String> {
        val body = params.entries.joinToString("&") {
            "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}"
        }
        val conn = (URL(url).openConnection() as HttpURLConnection)
        conn.requestMethod = "POST"
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        conn.setRequestProperty("User-Agent", "ZorvAI")
        conn.connectTimeout = 15000
        conn.readTimeout = 20000
        conn.doOutput = true
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.readText() ?: ""
        conn.disconnect()
        return code to text
    }

    /** 发起设备流，换取 device_code / user_code / 验证地址。失败返回 null。 */
    suspend fun startDeviceFlow(clientId: String, scope: String = DEFAULT_SCOPE): DeviceCode? = withContext(Dispatchers.IO) {
        val (code, body) = postForm(OAUTH_DEVICE_CODE, mapOf("client_id" to clientId, "scope" to scope))
        if (code !in 200..299) return@withContext null
        val jo = runCatching { JSONObject(body) }.getOrNull() ?: return@withContext null
        val deviceCode = jo.optString("device_code")
        val userCode = jo.optString("user_code")
        if (deviceCode.isBlank() || userCode.isBlank()) return@withContext null
        DeviceCode(
            deviceCode = deviceCode,
            userCode = userCode,
            verificationUri = jo.optString("verification_uri"),
            verificationUriComplete = jo.optString("verification_uri_complete").ifBlank { jo.optString("verification_uri") },
            expiresIn = jo.optInt("expires_in", 900),
            interval = jo.optInt("interval", 5),
        )
    }

    /**
     * 轮询换取 access_token（设备流标准做法）。
     * isCancelled 返回 true 时立即中止（用户取消登录）。
     */
    suspend fun pollForToken(
        clientId: String,
        deviceCode: String,
        interval: Int,
        isCancelled: () -> Boolean,
    ): DeviceLoginResult = withContext(Dispatchers.IO) {
        var wait = maxOf(interval, 1)
        var result: DeviceLoginResult? = null
        while (result == null) {
            if (isCancelled()) return@withContext DeviceLoginResult.Cancelled
            delay(wait * 1000L)
            if (isCancelled()) return@withContext DeviceLoginResult.Cancelled
            val (code, body) = postForm(
                OAUTH_TOKEN,
                mapOf(
                    "client_id" to clientId,
                    "device_code" to deviceCode,
                    "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
                ),
            )
            if (code !in 200..299) {
                val err = runCatching { JSONObject(body) }.getOrNull()?.optString("error") ?: "http_$code"
                return@withContext DeviceLoginResult.Error(describeDeviceError(err))
            }
            val jo = runCatching { JSONObject(body) }.getOrNull() ?: return@withContext DeviceLoginResult.Error("响应解析失败")
            val token = jo.optString("access_token")
            if (token.isNotBlank()) return@withContext DeviceLoginResult.Token(token, jo.optString("scope"))
            val err = jo.optString("error")
            if (err == "authorization_pending") {
                // 用户尚未在浏览器确认，继续轮询
            } else if (err == "slow_down") {
                wait += 5
            } else {
                return@withContext DeviceLoginResult.Error(describeDeviceError(err))
            }
        }
        result ?: DeviceLoginResult.Error("轮询异常中断")
    }

    private fun describeDeviceError(err: String): String = when (err) {
        "authorization_pending" -> "请在浏览器中完成授权…"
        "slow_down" -> "GitHub 请求放慢，请稍候…"
        "expired_token" -> "验证码已过期，请重新登录"
        "access_denied" -> "你已取消授权"
        "unsupported_grant_type", "incorrect_client_credentials", "incorrect_device_code" ->
            "Client ID 无效：请在 github.com/settings/developers 创建 OAuth App 并填入正确的 Client ID"
        else -> "登录失败：$err"
    }

    // ───────── 账户 / 仓库 / Issue / PR ─────────
    fun getAccount(ctx: Context): Account? {
        val (code, body) = get(ctx, "/user")
        if (code !in 200..299) return null
        val jo = runCatching { JSONObject(body) }.getOrNull() ?: return null
        return Account(
            login = jo.optString("login"),
            name = jo.optString("name").ifBlank { jo.optString("login") },
            htmlUrl = jo.optString("html_url"),
            bio = jo.optString("bio"),
            publicRepos = jo.optInt("public_repos"),
            followers = jo.optInt("followers"),
            following = jo.optInt("following"),
        )
    }

    fun listRepos(ctx: Context, sort: String = "updated"): List<Repo> {
        val (code, body) = get(ctx, "/user/repos?sort=$sort&per_page=50")
        if (code !in 200..299) return emptyList()
        return parseRepos(body)
    }

    fun listMyIssues(ctx: Context): List<Issue> {
        val (code, body) = get(ctx, "/user/issues?filter=all&state=all&per_page=50")
        if (code !in 200..299) return emptyList()
        return parseIssues(body)
    }

    fun listMyPullRequests(ctx: Context): List<Issue> {
        val me = getAccount(ctx)?.login ?: return emptyList()
        val q = URLEncoder.encode("is:pr author:$me", "UTF-8")
        val (code, body) = get(ctx, "/search/issues?q=$q&per_page=50")
        if (code !in 200..299) return emptyList()
        val jo = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
        return parseIssues(jo.optJSONArray("items"))
    }

    // ───────── Star ─────────
    fun isStarred(ctx: Context, owner: String, repo: String): Boolean {
        val (code, _) = get(ctx, "/user/starred/$owner/$repo")
        return code == 204
    }

    fun setStar(ctx: Context, owner: String, repo: String, starred: Boolean): Boolean {
        val (code, _) = request(ctx, if (starred) "PUT" else "DELETE", "/user/starred/$owner/$repo")
        return code in 200..299 || code == 204
    }

    // ───────── 通知 ─────────
    fun listNotifications(ctx: Context): List<Notification> {
        val (code, body) = get(ctx, "/notifications?per_page=50")
        if (code !in 200..299) return emptyList()
        val arr = runCatching { JSONArray(body) }.getOrNull() ?: return emptyList()
        val out = mutableListOf<Notification>()
        for (i in 0 until arr.length()) {
            val n = arr.optJSONObject(i) ?: continue
            val subj = n.optJSONObject("subject") ?: JSONObject()
            val repo = n.optJSONObject("repository")?.optString("full_name") ?: ""
            out.add(
                Notification(
                    id = n.optString("id"),
                    title = subj.optString("title"),
                    repo = repo,
                    reason = n.optString("reason"),
                    unread = n.optBoolean("unread"),
                    htmlUrl = n.optString("html_url"),
                )
            )
        }
        return out
    }

    // ───────── 创建 Issue ─────────
    fun createIssue(ctx: Context, owner: String, repo: String, title: String, bodyText: String): String {
        if (title.isBlank()) return "❌ 标题不能为空"
        val payload = JSONObject().apply {
            put("title", title)
            if (bodyText.isNotBlank()) put("body", bodyText)
        }.toString()
        val (code, resp) = request(ctx, "POST", "/repos/$owner/$repo/issues", payload)
        return if (code in 200..299) {
            val jo = runCatching { JSONObject(resp) }.getOrNull()
            "✅ 已创建 Issue #${jo?.optInt("number")}：${jo?.optString("html_url") ?: ""}"
        } else {
            "❌ 创建失败（HTTP $code）：${resp.take(300)}"
        }
    }

    // ───────── 搜索 ─────────
    fun searchRepositories(ctx: Context, query: String, perPage: Int = 20): List<Repo> {
        val q = URLEncoder.encode(query, "UTF-8")
        val (code, body) = get(ctx, "/search/repositories?q=$q&per_page=$perPage")
        if (code !in 200..299) return emptyList()
        val jo = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
        return parseRepos(jo.optJSONArray("items"))
    }

    fun searchCode(ctx: Context, query: String, perPage: Int = 20): List<CodeHit> {
        val q = URLEncoder.encode(query, "UTF-8")
        val (code, body) = get(ctx, "/search/code?q=$q&per_page=$perPage")
        if (code !in 200..299) return emptyList()
        val jo = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
        val arr = jo.optJSONArray("items") ?: return emptyList()
        val out = mutableListOf<CodeHit>()
        for (i in 0 until arr.length()) {
            val it = arr.optJSONObject(i) ?: continue
            val repo = it.optJSONObject("repository")?.optString("full_name") ?: ""
            out.add(
                CodeHit(
                    name = it.optString("name"),
                    path = it.optString("path"),
                    htmlUrl = it.optString("html_url"),
                    repo = repo,
                )
            )
        }
        return out
    }

    fun searchIssues(ctx: Context, query: String, perPage: Int = 20): List<Issue> {
        val q = URLEncoder.encode(query, "UTF-8")
        val (code, body) = get(ctx, "/search/issues?q=$q&per_page=$perPage")
        if (code !in 200..299) return emptyList()
        val jo = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
        return parseIssues(jo.optJSONArray("items"))
    }

    fun searchUsers(ctx: Context, query: String, perPage: Int = 20): List<User> {
        val q = URLEncoder.encode(query, "UTF-8")
        val (code, body) = get(ctx, "/search/users?q=$q&per_page=$perPage")
        if (code !in 200..299) return emptyList()
        val jo = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
        val arr = jo.optJSONArray("items") ?: return emptyList()
        val out = mutableListOf<User>()
        for (i in 0 until arr.length()) {
            val u = arr.optJSONObject(i) ?: continue
            out.add(
                User(
                    login = u.optString("login"),
                    htmlUrl = u.optString("html_url"),
                    type = u.optString("type"),
                    followers = u.optInt("followers"),
                )
            )
        }
        return out
    }

    // ───────── 解析工具 ─────────
    private fun parseRepos(arr: JSONArray?): List<Repo> {
        if (arr == null) return emptyList()
        val out = mutableListOf<Repo>()
        for (i in 0 until arr.length()) {
            val r = arr.optJSONObject(i) ?: continue
            val owner = r.optJSONObject("owner")?.optString("login") ?: ""
            out.add(
                Repo(
                    id = r.optLong("id"),
                    name = r.optString("name"),
                    fullName = r.optString("full_name"),
                    owner = owner,
                    htmlUrl = r.optString("html_url"),
                    description = r.optString("description"),
                    stars = r.optInt("stargazers_count"),
                    language = r.optString("language"),
                    updatedAt = r.optString("updated_at").take(10),
                )
            )
        }
        return out
    }

    private fun parseRepos(body: String): List<Repo> = parseRepos(runCatching { JSONArray(body) }.getOrNull())

    private fun parseIssues(arr: JSONArray?): List<Issue> {
        if (arr == null) return emptyList()
        val out = mutableListOf<Issue>()
        for (i in 0 until arr.length()) {
            val it = arr.optJSONObject(i) ?: continue
            val repo = it.optJSONObject("repository")?.optString("full_name")
                ?: it.optString("html_url").substringAfter("/github.com/").substringBefore("/issues")
            out.add(
                Issue(
                    id = it.optLong("id"),
                    number = it.optInt("number"),
                    title = it.optString("title"),
                    state = it.optString("state"),
                    htmlUrl = it.optString("html_url"),
                    repo = repo,
                )
            )
        }
        return out
    }

    private fun parseIssues(body: String): List<Issue> = parseIssues(runCatching { JSONArray(body) }.getOrNull())
}
