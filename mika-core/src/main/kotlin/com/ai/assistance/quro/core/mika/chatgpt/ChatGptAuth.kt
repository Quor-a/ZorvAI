package com.ai.assistance.quro.core.mika.chatgpt

import com.ai.assistance.quro.core.mika.model.HttpSupport
import com.ai.assistance.quro.core.mika.model.ModelError
import com.ai.assistance.quro.core.mika.oauth.Pkce
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.math.BigInteger
import java.security.KeyFactory
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.util.Base64

/** 一次登录尝试的随机值:每次都新生成,回调和换令牌时核对。[redirectUri] 是这次监听的回调地址。 */
data class SignInAttempt(val state: String, val nonce: String, val verifier: String, val redirectUri: String) {
    /** PKCE S256(见 [Pkce.challenge])。 */
    val challenge: String get() = Pkce.challenge(verifier)
}

/** 换令牌(用授权码或刷新令牌)得到的一组令牌;[scopes] 是实际授予的权限。 */
data class TokenSet(
    val accessToken: String,
    val refreshToken: String?,
    val idToken: String?,
    val expiresInSec: Long,
    val scopes: List<String>,
)

/** 换令牌的结果:拿到了、被拒(带服务端的错误代码;[terminal] 为真表示这个刷新令牌不能再用)、或一时失败(网络、5xx)。 */
sealed interface TokenResult {
    data class Ok(val tokens: TokenSet) : TokenResult
    /**
     * [detail] 是服务端给的错误说明(error_description 或 error.message),只用于给人看,不含令牌。
     * [nonJson] 为真表示返回的不是 JSON(多半是防护页面的 HTML),这时 [code] 是 `http_<状态码>`。
     */
    data class Rejected(val code: String, val terminal: Boolean, val detail: String? = null, val nonJson: Boolean = false) : TokenResult
    data class Failed(val error: ModelError) : TokenResult
}

/** ID 令牌里核对过的身份:[sub] 是账号标识,[email] 只用来给人看(可能为空)。 */
data class IdClaims(val sub: String, val email: String)

/**
 * 登录为什么没成;界面按它说原因(见 [ChatGptAuth.failureOf])。[CodeExpired] 是授权码过期、用过或对不上;
 * [Region] 是所在地区不被支持;[Blocked] 是请求被防护拦下(返回的是网页不是 JSON);[NoPlanScope] 只在服务端
 * 说了套餐、权限,或授予的权限里没有套餐那一项时用;别的被拒都是 [Exchange](带上错误代码)。
 */
enum class SignInFailure { Denied, StateMismatch, ClientMismatch, NoClientId, TimedOut, Exchange, CodeExpired, Region, Blocked, IdToken, NoPlanScope, Network }

/** [code] 是服务端给的错误代码(不是机密,可以给人看、写进诊断信息);没有为 null。 */
class SignInException(val reason: SignInFailure, detail: String = "", cause: Throwable? = null, val code: String? = null) :
    Exception(if (detail.isEmpty()) reason.name else "${reason.name}: $detail", cause)

/**
 * 「用 ChatGPT 登录」并用用户的 ChatGPT 套餐额度调用模型(OpenAI 为开源、本机运行的 App 提供的这条路):
 * 公开客户端、没有密钥,授权码 + PKCE(S256)。第一次用 `dynamic_agent_client` 注册,回调里给出这个账号专属的
 * client_id,之后换令牌、刷新、再登录都用它。本机标识([hostId],`urn:uuid:…`)每台设备生成一次、一直用。
 * 回调地址只能是 `http://127.0.0.1:<端口>/auth/callback`(端口可变)。
 *
 * ID 令牌按发布的 JWKS 验签(RS256),核对签发方、受众(= client_id)、过期时间和这次的 nonce;
 * 授予的权限里要有 [PLAN_SCOPE] 才能用套餐额度调用。
 */
class ChatGptAuth(
    private val http: OkHttpClient,
    private val issuer: String = ISSUER,
    private val now: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val tokenUrl = "$issuer/api/accounts/oauth/token"
    private val revokeUrl = "$issuer/api/accounts/oauth/revoke"
    private val jwksUrl = "$issuer/.well-known/jwks.json"

    /** 最近一次取到的 JWKS(kid → 公钥参数);验签时找不到 kid 再取一次(服务端换了密钥)。 */
    @Volatile private var jwks: Map<String, JsonObject> = emptyMap()

    fun newAttempt(redirectUri: String) = SignInAttempt(randomString(), randomString(), randomString(48), redirectUri)

    /**
     * 打开授权页的地址。[clientId] 为 null 表示第一次,用 `dynamic_agent_client` 注册并带上 App 名;
     * 再次登录带上次的 ID 令牌([idTokenHint],可以已过期;退出登录后不带)和邮箱([loginHint])。
     * 地址里可能有 ID 令牌,不要写进日志。
     */
    fun authorizeUrl(attempt: SignInAttempt, clientId: String?, hostId: String, idTokenHint: String? = null, loginHint: String? = null): String =
        "$issuer/api/accounts/authorize".toHttpUrl().newBuilder()
            .addQueryParameter("client_id", clientId ?: REGISTER_CLIENT_ID)
            .apply { if (clientId == null) addQueryParameter("agent_name_hint", APP_NAME) }
            .addQueryParameter("ext_agent_host_id", hostId)
            .apply {
                if (clientId != null && !idTokenHint.isNullOrEmpty()) addQueryParameter("id_token_hint", idTokenHint)
                if (clientId != null && !loginHint.isNullOrEmpty()) addQueryParameter("login_hint", loginHint)
            }
            .addQueryParameter("response_type", "code")
            .addQueryParameter("redirect_uri", attempt.redirectUri)
            .addQueryParameter("scope", SCOPES)
            .addQueryParameter("resource", RESOURCE)
            .addQueryParameter("state", attempt.state)
            .addQueryParameter("nonce", attempt.nonce)
            .addQueryParameter("code_challenge_method", "S256")
            .addQueryParameter("code_challenge", attempt.challenge)
            .build().toString()

    /**
     * 读回调参数,得到(授权码, 这次用的 client_id)。先核对 state;用户拒绝、state 不对、client_id 和这次请求的不一致、
     * 第一次注册却没给出 client_id,都抛 [SignInException]。
     */
    fun readCallback(params: Map<String, String>, attempt: SignInAttempt, clientId: String?): Pair<String, String> {
        if (params["state"] != attempt.state) throw SignInException(SignInFailure.StateMismatch)
        params["error"]?.let { err ->
            // 授权页带回的错误:地区、套餐说得出来的照说,其余都当用户没同意
            val f = failureOf(err, params["error_description"])
            throw SignInException(if (f == SignInFailure.Region || f == SignInFailure.NoPlanScope) f else SignInFailure.Denied, err, code = err)
        }
        val code = params["code"]?.takeIf { it.isNotEmpty() } ?: throw SignInException(SignInFailure.Exchange, "no code", code = "no_code")
        val issued = params["client_id"]?.takeIf { it.isNotEmpty() }
        if (clientId != null && issued != null && issued != clientId) throw SignInException(SignInFailure.ClientMismatch)
        val id = issued ?: clientId ?: throw SignInException(SignInFailure.NoClientId)
        return code to id
    }

    suspend fun exchange(clientId: String, code: String, attempt: SignInAttempt): TokenResult = token(
        FormBody.Builder().add("grant_type", "authorization_code").add("client_id", clientId).add("code", code)
            .add("code_verifier", attempt.verifier).add("redirect_uri", attempt.redirectUri).add("resource", RESOURCE).build(),
    )

    /** 不带 scope:保留原来授予的权限。 */
    suspend fun refresh(clientId: String, refreshToken: String): TokenResult = token(
        FormBody.Builder().add("grant_type", "refresh_token").add("client_id", clientId).add("refresh_token", refreshToken)
            .add("resource", RESOURCE).build(),
    )

    /** 注销刷新令牌;服务端确认(HTTP 200,令牌本来就失效了也是 200)为真。注销不删注册的 client。 */
    suspend fun revoke(clientId: String, refreshToken: String): Boolean = try {
        val form = FormBody.Builder().add("token", refreshToken).add("token_type_hint", "refresh_token").add("client_id", clientId).build()
        HttpSupport.execute(http, Request.Builder().url(revokeUrl).post(form).build()).status == 200
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }

    private suspend fun token(form: FormBody): TokenResult {
        val raw = try {
            HttpSupport.execute(http, Request.Builder().url(tokenUrl).post(form).build())
        } catch (e: CancellationException) {
            throw e
        } catch (e: ModelError) {
            return TokenResult.Failed(e)
        }
        val root = runCatching { json.parseToJsonElement(raw.body) as? JsonObject }.getOrNull()
        if (raw.status in 500..599) return TokenResult.Failed(ModelError.Server(raw.status))
        if (raw.status != 200) {
            val code = root?.let { (it["error"] as? JsonObject)?.str("code") ?: it.str("error") ?: it.str("code") } ?: "http_${raw.status}"
            val detail = root?.let { it.str("error_description") ?: (it["error"] as? JsonObject)?.str("message") }
            return TokenResult.Rejected(code, code in TERMINAL_REFRESH_ERRORS, detail, nonJson = root == null)
        }
        val access = root?.str("access_token") ?: return TokenResult.Failed(ModelError.BadResponse("换令牌的返回里没有 access_token"))
        return TokenResult.Ok(
            TokenSet(
                accessToken = access,
                refreshToken = root.str("refresh_token"),
                idToken = root.str("id_token"),
                expiresInSec = (root["expires_in"] as? JsonPrimitive)?.longOrNull ?: DEFAULT_EXPIRES_SEC,
                scopes = root.str("scope")?.split(' ')?.filter { it.isNotEmpty() }.orEmpty(),
            ),
        )
    }

    /** 验 ID 令牌:签名(JWKS,RS256)、签发方、受众、过期、nonce;都对返回身份,否则抛 [SignInException]。 */
    suspend fun verifyIdToken(idToken: String, clientId: String, nonce: String): IdClaims {
        fun fail(why: String): Nothing = throw SignInException(SignInFailure.IdToken, why)
        val pieces = idToken.split('.')
        if (pieces.size != 3) fail("not a JWT")
        val header = decodeJson(pieces[0]) ?: fail("bad header")
        val claims = decodeJson(pieces[1]) ?: fail("bad payload")
        if (header.str("alg") != "RS256") fail("alg ${header.str("alg")}")
        val kid = header.str("kid")
        val key = jwk(kid, refresh = false) ?: jwk(kid, refresh = true) ?: fail("unknown key")
        val verified = runCatching {
            val n = BigInteger(1, b64urlDecode(key.str("n")!!))
            val e = BigInteger(1, b64urlDecode(key.str("e")!!))
            val pub = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(n, e))
            Signature.getInstance("SHA256withRSA").run {
                initVerify(pub)
                update("${pieces[0]}.${pieces[1]}".toByteArray(Charsets.US_ASCII))
                verify(b64urlDecode(pieces[2]))
            }
        }.getOrDefault(false)
        if (!verified) fail("signature")
        if (claims.str("iss") != issuer) fail("issuer")
        val aud = claims["aud"]
        val audOk = when (aud) {
            is JsonPrimitive -> aud.contentOrNull == clientId
            is JsonArray -> aud.any { (it as? JsonPrimitive)?.contentOrNull == clientId }
            else -> false
        }
        if (!audOk) fail("audience")
        val exp = (claims["exp"] as? JsonPrimitive)?.longOrNull ?: fail("no exp")
        if (exp * 1000 + CLOCK_SKEW_MS < now()) fail("expired")
        if (claims.str("nonce") != nonce) fail("nonce")
        val sub = claims.str("sub")?.takeIf { it.isNotEmpty() } ?: fail("no sub")
        val email = claims.str("email") ?: (claims[PROFILE_CLAIM] as? JsonObject)?.str("email") ?: ""
        return IdClaims(sub, email)
    }

    private suspend fun jwk(kid: String?, refresh: Boolean): JsonObject? {
        if (refresh || jwks.isEmpty()) {
            val raw = try { HttpSupport.execute(http, Request.Builder().url(jwksUrl).get().build()) } catch (e: CancellationException) { throw e } catch (e: Exception) {
                throw SignInException(SignInFailure.Network, "jwks", e)
            }
            val keys = runCatching { (json.parseToJsonElement(raw.body) as JsonObject)["keys"] as JsonArray }.getOrNull().orEmpty()
            jwks = keys.filterIsInstance<JsonObject>().filter { it.str("kty") == "RSA" }.associateBy { it.str("kid").orEmpty() }
        }
        return if (kid == null) jwks.values.singleOrNull() else jwks[kid]
    }

    private fun decodeJson(part: String): JsonObject? = runCatching { json.parseToJsonElement(String(b64urlDecode(part))) as? JsonObject }.getOrNull()

    private fun randomString(bytes: Int = 32): String = ByteArray(bytes).also(random::nextBytes).let(::b64url)

    companion object {
        const val ISSUER = "https://auth.openai.com"
        const val REGISTER_CLIENT_ID = "dynamic_agent_client"
        const val APP_NAME = "ZorvAI"
        const val PLAN_SCOPE = "chatgpt.tokens.use.direct"
        const val SCOPES = "openid profile email offline_access resource.invoke $PLAN_SCOPE"
        const val RESOURCE = "https://api.openai.com/v1"
        /** 用套餐额度调用的接口根(列模型、Responses)。 */
        const val API_BASE = "https://api.openai.com/v1"
        /** 用户在 ChatGPT 里查看、管理用量的页面。 */
        const val USAGE_URL = "https://chatgpt.com/settings/usage"
        private const val PROFILE_CLAIM = "https://api.openai.com/profile"
        private const val DEFAULT_EXPIRES_SEC = 3600L
        private const val CLOCK_SKEW_MS = 120_000L

        /** 刷新令牌已不能再用:清掉令牌,用原来的 client_id 重新登录。 */
        val TERMINAL_REFRESH_ERRORS = setOf(
            "invalid_grant", "invalid_refresh_token", "token_expired", "refresh_token_expired",
            "refresh_token_invalidated", "refresh_token_reused",
        )

        /** 用户没同意、没登录完一类的错误代码。 */
        private val DENIED_ERRORS = setOf("access_denied", "consent_required", "login_required", "interaction_required")
        /** 说的是套餐或权限的错误代码。 */
        private val PLAN_ERRORS = setOf("invalid_scope", "insufficient_scope", "subscription_sharing_user_not_eligible")
        private val REGION = Regex("""unsupported_country|region|territor""", RegexOption.IGNORE_CASE)
        private val PLAN = Regex("""(?<![a-z])(plan|subscription|scope)s?(?![a-z])""", RegexOption.IGNORE_CASE)

        /**
         * 被拒的原因归到哪一类,界面照它说人话:被防护拦下(403、429 且返回的不是 JSON)、地区不支持、
         * 授权码过期或用过(`invalid_grant`)、用户没同意、套餐或权限(代码或说明里提到),其余是 [SignInFailure.Exchange]。
         */
        fun failureOf(code: String, detail: String?, nonJson: Boolean = false): SignInFailure {
            val text = "$code ${detail.orEmpty()}"
            return when {
                nonJson && (code == "http_403" || code == "http_429") -> SignInFailure.Blocked
                REGION.containsMatchIn(text) -> SignInFailure.Region
                code == "invalid_grant" -> SignInFailure.CodeExpired
                code in DENIED_ERRORS -> SignInFailure.Denied
                code in PLAN_ERRORS || PLAN.containsMatchIn(text) -> SignInFailure.NoPlanScope
                else -> SignInFailure.Exchange
            }
        }

        /** 换令牌被拒时抛的异常:原因按 [failureOf] 归类,带上错误代码和说明。 */
        fun rejected(r: TokenResult.Rejected): SignInException = SignInException(
            failureOf(r.code, r.detail, r.nonJson), listOfNotNull(r.code, r.detail).joinToString(" — "), code = r.code,
        )

        fun b64url(b: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(b)
        fun b64urlDecode(s: String): ByteArray = Base64.getUrlDecoder().decode(s.trimEnd('='))

        /** 本机标识:UUIDv4 的 URN,每台设备生成一次后一直用。 */
        fun newHostId(): String = "urn:uuid:${java.util.UUID.randomUUID()}"
    }
}

private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
