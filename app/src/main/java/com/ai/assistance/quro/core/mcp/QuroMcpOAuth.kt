package com.ai.assistance.quro.core.mcp

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 *
 * 流程（MCP 授权规范 2025-03-26 版）：
 *  1. 无凭据请求 MCP 端点 → 401 + WWW-Authenticate（或直接探 /.well-known/oauth-protected-resource）
 *     → 拿到 resource_metadata → authorization_servers 列表；
 *  2. 探 <server>/.well-known/oauth-authorization-server → authorization_endpoint /
 *     token_endpoint / registration_endpoint；
 *  3. 动态客户端注册（RFC 7591，registration_endpoint）→ client_id（公开客户端，PKCE 免密）；
 *  4. 浏览器打开 authorization_url（redirect_uri=http://localhost:41893/callback，
 *  5. 粘回的 code + verifier 换 access_token/refresh_token，按 alias 存储并自动续期。
 *
 * PORT_IN_USE 状态就是那条路的坑），功能完整、实现最小。
 */
object QuroMcpOAuth {

    private const val PREFS = "quro_mcp_oauth"
    private const val REDIRECT_URI = "http://localhost:41893/callback"
    private const val KEY_PENDING = "pending"       // alias → {verifier,state,client_id,token_endpoint}
    private const val KEY_TOKENS = "tokens"         // alias → {access_token,refresh_token,expires_at,token_endpoint,client_id}

    data class OAuthUrls(
        val authorizeUrl: String,
        val clientId: String,
        val tokenEndpoint: String,
        val verifier: String,
        val state: String,
    )

    /**
     * 落盘的 OAuth token。
     *
     * 🔴 `tokenEndpoint` / `clientId` 必须跟 token 一起存：旧实现刷新时去读 `KEY_PENDING`，
     *    但 [completeOAuth] 成功后立刻把 pending 删了；又去读一个从未写入过的
     *    `token_endpoint_$alias` 键 —— 两条路都断，token 过期后 MCP 永远 401。
     */
    data class StoredToken(
        val accessToken: String,
        val refreshToken: String,
        val expiresAt: Long,
        val tokenEndpoint: String = "",
        val clientId: String = "",
    )

    // ── 第 1~2 步：发现 ─────────────────────────────────────────────

    /** 从 MCP 端点发现授权元数据（受保护资源元数据 → 授权服务器元数据）。 */
    fun discover(mcpUrl: String): Triple<String, String, String> {
        // 先走 401 的 WWW-Authenticate（规范首选），失败退探 well-known 路径
        var resourceMetaUrl = ""
        runCatching {
            val c = open(mcpUrl)
            c.requestMethod = "GET"
            c.connectTimeout = 10_000; c.readTimeout = 10_000
            if (c.responseCode == 401) {
                val www = c.getHeaderField("WWW-Authenticate") ?: ""
                Regex("resource_metadata=\"([^\"]+)\"").find(www)?.let { resourceMetaUrl = it.groupValues[1] }
            }
        }
        if (resourceMetaUrl.isBlank()) {
            val u = URL(mcpUrl)
            val base = "${u.protocol}://${u.host}" + (if (u.port > 0) ":${u.port}" else "")
            resourceMetaUrl = "$base/.well-known/oauth-protected-resource" + u.path.trimEnd('/')
        }
        val authServers = runCatching { JSONObject(get(resourceMetaUrl)).optJSONArray("authorization_servers") }.getOrNull()
        val authServer = authServers?.optString(0)?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("该 MCP 端点未声明授权服务器（可能不支持 OAuth）")
        val serverMeta = JSONObject(get(trimSlash(authServer) + "/.well-known/oauth-authorization-server"))
        val authorize = serverMeta.optString("authorization_endpoint", "")
        val token = serverMeta.optString("token_endpoint", "")
        val registration = serverMeta.optString("registration_endpoint", "")
        if (authorize.isBlank() || token.isBlank()) throw IllegalStateException("授权服务器元数据缺少 endpoint")
        return Triple(authorize, token, registration)
    }

    // ── 第 3 步：动态注册 + 第 4 步：授权 URL ────────────────────────

    /** 动态注册公开客户端并生成授权 URL（PKCE S256）。pending 存 prefs 供粘回后兑换。 */
    fun startOAuth(ctx: Context, alias: String, mcpUrl: String): OAuthUrls {
        val (authorize, tokenEndpoint, registration) = discover(mcpUrl)
        var clientId = ""
        if (registration.isNotBlank()) {
            runCatching {
                val body = JSONObject()
                    .put("client_name", "Zorv AI")
                    .put("redirect_uris", JSONArray().put(REDIRECT_URI))
                    .put("grant_types", JSONArray().put("authorization_code").put("refresh_token"))
                    .put("response_types", JSONArray().put("code"))
                    .put("token_endpoint_auth_method", "none")
                val resp = JSONObject(post(registration, body.toString()))
                clientId = resp.optString("client_id", "")
            }
        }
        if (clientId.isBlank()) throw IllegalStateException("动态客户端注册失败（该服务器可能要求预注册 client_id）")
        val verifier = randomB64(48)
        val state = randomB64(16)
        val challenge = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        val authorizeUrl = authorize + (if (authorize.contains('?')) "&" else "?") +
            "response_type=code" +
            "&client_id=" + enc(clientId) +
            "&redirect_uri=" + enc(REDIRECT_URI) +
            "&scope=" + enc("openid profile email") +
            "&state=" + enc(state) +
            "&code_challenge=" + enc(challenge) +
            "&code_challenge_method=S256"
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString(KEY_PENDING, JSONObject()
                .put("alias", alias)
                .put("verifier", verifier)
                .put("state", state)
                .put("client_id", clientId)
                .put("token_endpoint", tokenEndpoint).toString())
        }
        return OAuthUrls(authorizeUrl, clientId, tokenEndpoint, verifier, state)
    }

    /** 用户粘回回调 URL → 提取 code（校验 state）→ 兑换 token → 按 alias 存储。 */
    fun completeOAuth(ctx: Context, callbackUrl: String): String {
        val pending = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PENDING, null)?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: throw IllegalStateException("没有进行中的授权")
        val u = URL(callbackUrl.trim())
        val params = u.query.split("&").mapNotNull {
            val i = it.indexOf('='); if (i <= 0) null
            else it.substring(0, i) to URLDecoder(it.substring(i + 1))
        }.toMap()
        val code = params["code"] ?: throw IllegalStateException("回调 URL 里没有 code")
        if (params["state"] != pending.optString("state")) throw IllegalStateException("state 校验失败（回调 URL 不是这次授权的）")
        val form = mapOf(
            "grant_type" to "authorization_code",
            "code" to code,
            "redirect_uri" to REDIRECT_URI,
            "client_id" to pending.optString("client_id"),
            "code_verifier" to pending.optString("verifier"),
        )
        val body = form.entries.joinToString("&") { "${it.key}=" + enc(it.value) }
        val resp = JSONObject(post(pending.optString("token_endpoint"), body, form = true))
        val access = resp.optString("access_token", "").ifBlank { throw IllegalStateException("token 响应缺少 access_token") }
        val refresh = resp.optString("refresh_token", "")
        val expiresIn = resp.optLong("expires_in", 3600)
        store(
            ctx, pending.optString("alias"), access, refresh,
            System.currentTimeMillis() + expiresIn * 1000,
            pending.optString("token_endpoint"), pending.optString("client_id"),
        )
        // 清 pending
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { remove(KEY_PENDING) }
        return access
    }

    // ── token 存取 ──────────────────────────────────────────────────

    fun tokenOf(ctx: Context, alias: String): StoredToken? =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_TOKENS, null)?.let { raw ->
                runCatching {
                    val arr = JSONArray(raw)
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        if (o.optString("alias") == alias) return StoredToken(
                            o.optString("access_token"), o.optString("refresh_token"), o.optLong("expires_at", 0),
                            o.optString("token_endpoint"), o.optString("client_id"),
                        )
                    }
                }.getOrNull()
                null
            }

    /** 取该 MCP 服务器的可用 Bearer：OAuth token 优先（过期自动刷新），否则回落静态 Key。 */
    fun bearerFor(ctx: Context, alias: String, cfg: QuroMcpClient.McpServerConfig): String? {
        tokenOf(ctx, alias)?.let { t ->
            if (t.expiresAt > System.currentTimeMillis() + 60_000) return t.accessToken
            // 刷新所需的 endpoint / client_id 现在随 token 一起落盘，不再依赖已被清掉的 pending。
            if (t.refreshToken.isNotBlank() && t.tokenEndpoint.isNotBlank()) {
                runCatching {
                    val body = mapOf(
                        "grant_type" to "refresh_token",
                        "refresh_token" to t.refreshToken,
                        "client_id" to t.clientId,
                    ).entries.joinToString("&") { "${it.key}=" + enc(it.value) }
                    val resp = JSONObject(post(t.tokenEndpoint, body, form = true))
                    val access = resp.optString("access_token", "")
                    if (access.isNotBlank()) {
                        store(
                            ctx, alias, access, resp.optString("refresh_token", t.refreshToken),
                            System.currentTimeMillis() + resp.optLong("expires_in", 3600) * 1000,
                        )
                        return access
                    }
                }.onFailure { Log.w("QuroMcpOAuth", "token 刷新失败，回落到旧 token：${it.message}") }
            }
            // 过期但没 refresh（或刷新失败），仍试最后一次；token 为空则回落静态 Key。
            return t.accessToken.ifBlank { cfg.token.ifBlank { null } }
        }
        return cfg.token.ifBlank { null }
    }

    private fun store(
        ctx: Context, alias: String, access: String, refresh: String, expiresAt: Long,
        tokenEndpoint: String = "", clientId: String = "",
    ) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val arr = prefs.getString(KEY_TOKENS, null)?.let { JSONArray(it) } ?: JSONArray()
        val out = JSONArray()
        var oldEndpoint = ""
        var oldClientId = ""
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("alias") == alias) {
                // 刷新时新 token 响应不带 endpoint/client_id，沿用上次落盘的值。
                oldEndpoint = o.optString("token_endpoint")
                oldClientId = o.optString("client_id")
            } else out.put(o)
        }
        out.put(JSONObject().put("alias", alias).put("access_token", access)
            .put("refresh_token", refresh).put("expires_at", expiresAt)
            .put("token_endpoint", tokenEndpoint.ifBlank { oldEndpoint })
            .put("client_id", clientId.ifBlank { oldClientId }))
        prefs.edit { putString(KEY_TOKENS, out.toString()) }
    }

    // ── HTTP 小工具 ─────────────────────────────────────────────────

    private fun trimSlash(s: String) = s.trimEnd('/')
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun randomB64(len: Int): String {
        val b = ByteArray(len)
        SecureRandom().nextBytes(b)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b).take(len)
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000; readTimeout = 15_000
            setRequestProperty("Accept", "application/json")
        }

    private fun get(url: String): String = open(url).let { c ->
        (if (c.responseCode in 200..299) c.inputStream else c.errorStream)
            ?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
    }

    private fun post(url: String, body: String, form: Boolean = false): String {
        val c = open(url).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", if (form) "application/x-www-form-urlencoded" else "application/json")
            if (!form) setRequestProperty("Accept", "application/json")
        }
        c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        return (if (c.responseCode in 200..299) c.inputStream else c.errorStream)
            ?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
    }

    /** URLDecoder 不在 java.net 里（叫 URLDecoder 在 java.net），包一层防混淆。 */
    private fun URLDecoder(s: String): String = java.net.URLDecoder.decode(s, "UTF-8")
}
