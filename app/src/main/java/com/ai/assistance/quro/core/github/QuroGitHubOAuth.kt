package com.ai.assistance.quro.core.github

import android.content.Context
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * GitHub OAuth —— 应用内 WebView 授权码流程（PKCE，public client，无需 client_secret）。
 *
 * 流程：
 *  1. 登录屏用烤进 BuildConfig 的 client_id 拼出授权页 URL，在 WebView 内打开；
 *  2. 用户用 GitHub 账号授权；
 *  3. GitHub 重定向到 [REDIRECT_URI]（zorv://github-oauth-callback?code=...&state=...）；
 *  4. WebView 拦截该跳转，取出 code，用 PKCE code_verifier 换 access_token；
 *  5. 令牌写入私有保险库（QuroAuthStore "github"），登录完成。
 *
 * client_id 由开发者在 gradle.properties 的 GITHUB_CLIENT_ID 烤进 BuildConfig，
 * 普通用户全程只看到「用 GitHub 登录」按钮，无需填写任何 Token / Client ID / Secret。
 */
object QuroGitHubOAuth {
    const val REDIRECT_URI = "zorv://github-oauth-callback"
    private const val AUTH_URL = "https://github.com/login/oauth/authorize"
    private const val TOKEN_URL = "https://github.com/login/oauth/access_token"
    // 仓库读写 / 用户 / 邮箱 / 通知 / 组织 / Gist
    const val SCOPE = "repo read:user user:email notifications read:org gist"

    /** 生成随机 state（防 CSRF）。 */
    fun randomState(): String =
        ByteArray(16).also { SecureRandom().nextBytes(it) }
            .let { Base64.encodeToString(it, Base64.NO_WRAP or Base64.URL_SAFE).trimEnd('=') }

    /** 生成 PKCE code_verifier。 */
    fun randomCodeVerifier(): String =
        ByteArray(32).also { SecureRandom().nextBytes(it) }
            .let { Base64.encodeToString(it, Base64.NO_WRAP or Base64.URL_SAFE).trimEnd('=') }

    /** 由 code_verifier 计算 S256 code_challenge。 */
    fun codeChallengeS256(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(digest, Base64.NO_WRAP or Base64.URL_SAFE).trimEnd('=')
    }

    /** 拼出 GitHub 授权页 URL（在 WebView 中加载）。 */
    fun buildAuthUrl(clientId: String, state: String, codeChallenge: String): String =
        buildString {
            append(AUTH_URL)
            append("?client_id=").append(URLEncoder.encode(clientId, "UTF-8"))
            append("&redirect_uri=").append(URLEncoder.encode(REDIRECT_URI, "UTF-8"))
            append("&scope=").append(URLEncoder.encode(SCOPE, "UTF-8"))
            append("&state=").append(URLEncoder.encode(state, "UTF-8"))
            append("&code_challenge=").append(URLEncoder.encode(codeChallenge, "UTF-8"))
            append("&code_challenge_method=S256")
            append("&allow_signup=true")
        }

    /** 是否为 OAuth 回调重定向。 */
    fun isOAuthRedirect(url: String): Boolean = url.startsWith(REDIRECT_URI)

    /** 从回调 URL 取出授权码（带 state 校验，不匹配返回 null）。 */
    fun parseCode(url: String, expectedState: String): String? {
        val uri = Uri.parse(url)
        if (uri.getQueryParameter("state") != expectedState) return null
        return uri.getQueryParameter("code")
    }

    /**
     * 用授权码 + PKCE 换 access_token（public client，无需 client_secret）。
     * 成功返回令牌字符串，失败（client 无效 / 用户拒绝 / 网络异常）返回 null。
     */
    suspend fun exchangeCodeForToken(
        clientId: String,
        code: String,
        codeVerifier: String,
    ): String? = withContext(Dispatchers.IO) {
        val body = buildString {
            append("client_id=").append(URLEncoder.encode(clientId, "UTF-8"))
            append("&code=").append(URLEncoder.encode(code, "UTF-8"))
            append("&redirect_uri=").append(URLEncoder.encode(REDIRECT_URI, "UTF-8"))
            append("&code_verifier=").append(URLEncoder.encode(codeVerifier, "UTF-8"))
            append("&grant_type=authorization_code")
        }
        val conn = (URL(TOKEN_URL).openConnection() as HttpURLConnection)
        conn.requestMethod = "POST"
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        conn.setRequestProperty("User-Agent", "ZorvAI")
        conn.connectTimeout = 15000
        conn.readTimeout = 20000
        conn.doOutput = true
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.readText() ?: ""
        conn.disconnect()
        if (code !in 200..299) return@withContext null
        val jo = runCatching { JSONObject(text) }.getOrNull() ?: return@withContext null
        jo.optString("access_token").takeIf { it.isNotBlank() }
    }

    /** 校验令牌并写入私有保险库，完成登录。成功返回 true。 */
    suspend fun applyLogin(ctx: Context, token: String): Boolean {
        if (QuroGitHubClient.validateToken(token) == null) return false
        return QuroGitHubClient.login(ctx, token)
    }
}
