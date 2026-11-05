package com.ai.assistance.quro.core.mika.oauth

import com.ai.assistance.quro.core.mika.model.HttpSupport
import com.ai.assistance.quro.core.mika.model.ModelError
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 用 xAI / Grok 订阅登录,再直接调 xAI 自己的 Responses 接口(`https://api.x.ai/v1/responses`)。
 *
 * 客户端身份复用 xAI 官方命令行工具的公开登录入口(公开 client_id,不是 xAI 给 ZorvAI 注册的应用)。xAI 可能不支持这种用法,
 * 有账号被限制的风险;界面在登录前写明,不点就不发生。发现文档里的地址必须是 https 且主机是 [HOST],
 * 对不上就拒绝,不去连。刷新令牌走同一家的令牌端点。
 */
class XaiAuth(
    private val http: OkHttpClient,
    private val issuer: String = ISSUER,
    private val clientId: String = CLIENT_ID,
    /** 令牌、发现、设备码端点允许的主机。默认只有 auth.x.ai。测试里换成本机。 */
    private val issuerHosts: Set<String> = setOf(HOST),
    /** 给用户打开的验证页允许的主机。 */
    private val verifyHosts: Set<String> = setOf(HOST, "accounts.x.ai"),
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** 读 OIDC 发现文档,只留下主机核对过的设备码和令牌地址。 */
    suspend fun discover(): XaiEndpoints {
        val docUrl = OauthUrls.check(issuer.trimEnd('/') + "/.well-known/openid-configuration", issuerHosts)
        val raw = get(docUrl.toString())
        if (raw.status != 200) throw AccountSignInException(AccountSignInFailure.Network, "http_${raw.status}")
        val root = jsonObject(raw.body) ?: throw AccountSignInException(AccountSignInFailure.BadIssuer, "discovery")
        val found = root.str("issuer")?.trimEnd('/')
        if (found != issuer.trimEnd('/')) throw AccountSignInException(AccountSignInFailure.BadIssuer, "issuer")
        val device = root.str("device_authorization_endpoint")
            ?: throw AccountSignInException(AccountSignInFailure.BadIssuer, "no_device")
        val token = root.str("token_endpoint") ?: throw AccountSignInException(AccountSignInFailure.BadIssuer, "no_token")
        OauthUrls.check(device, issuerHosts)
        OauthUrls.check(token, issuerHosts)
        val userinfo = root.str("userinfo_endpoint")?.takeIf { OauthUrls.allowed(it, issuerHosts) }
        return XaiEndpoints(device, token, userinfo)
    }

    suspend fun signIn(
        openBrowser: (String) -> Unit,
        onChallenge: (DeviceChallenge) -> Unit,
        sleep: suspend (Long) -> Unit = ::oauthSleep,
        clock: () -> Long = now,
    ): AccountSession {
        val endpoints = discover()
        val client = DeviceCodeClient(http, endpoints.deviceUrl, endpoints.tokenUrl, clientId, SCOPES, issuerHosts)
        val grant = client.start().also { published ->
            val challenge = published.challenge.withVerifiedHosts()
            onChallenge(challenge)
            openBrowser(challenge.verificationUriComplete ?: challenge.verificationUri)
        }
        val tokens = awaitDeviceGrant(grant, clock, sleep) { client.poll(it) }
        if (tokens.scopes.isNotEmpty() && tokens.scopes.none { it == SCOPE_CLI || it == SCOPE_API }) {
            throw AccountSignInException(AccountSignInFailure.NoSubscription, "scope")
        }
        val who = identity(endpoints.userinfoUrl, tokens)
        return AccountSession(
            provider = PROVIDER,
            accountId = who.first.ifBlank { "xai" },
            label = who.second,
            accessToken = tokens.accessToken,
            refreshToken = tokens.refreshToken.orEmpty(),
            expiresAtMs = clock() + tokens.expiresInSec * 1000,
            scopes = tokens.scopes,
            apiBase = API_BASE,
            clientId = clientId,
            issuer = issuer.trimEnd('/'),
            tokenUrl = endpoints.tokenUrl,
        )
    }

    /** 用刷新令牌换一组新的。地址按登录时记下的签发方,主机仍要在允许名单里。 */
    suspend fun refresh(session: AccountSession): OauthTokenResult {
        if (session.refreshToken.isEmpty()) return OauthTokenResult.Rejected("no_refresh_token", terminal = true)
        val issuer = session.issuer.ifBlank { this.issuer }.trimEnd('/')
        val tokenUrl = session.tokenUrl.ifBlank { "$issuer/oauth2/token" }
        val url = try {
            OauthUrls.check(tokenUrl, issuerHosts)
        } catch (e: AccountSignInException) {
            return OauthTokenResult.Rejected(e.code ?: "host", terminal = true)
        }
        val form = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", session.refreshToken)
            .add("client_id", session.clientId.ifBlank { clientId })
            .build()
        val raw = try {
            HttpSupport.execute(http, Request.Builder().url(url).header("Accept", "application/json").post(form).build())
        } catch (e: CancellationException) {
            throw e
        } catch (e: ModelError) {
            return OauthTokenResult.Failed(e)
        }
        if (raw.status in 500..599) return OauthTokenResult.Failed(ModelError.Server(raw.status))
        val root = jsonObject(raw.body)
        val err = root?.str("error")
        if (raw.status != 200 || err != null || root == null) {
            val code = err ?: "http_${raw.status}"
            return OauthTokenResult.Rejected(code, terminal = code in TERMINAL_REFRESH || raw.status == 401)
        }
        val access = root.str("access_token") ?: return OauthTokenResult.Rejected("no_access_token", terminal = false)
        return OauthTokenResult.Ok(
            OauthTokens(
                accessToken = access,
                refreshToken = root.str("refresh_token"),
                idToken = root.str("id_token"),
                expiresInSec = (root["expires_in"] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 } ?: 3600,
                scopes = root.str("scope")?.split(' ')?.filter { it.isNotEmpty() }.orEmpty(),
                apiBase = API_BASE,
            ),
        )
    }

    private fun DeviceChallenge.withVerifiedHosts(): DeviceChallenge {
        val verify = if (OauthUrls.allowed(verificationUri, verifyHosts)) verificationUri else FALLBACK_VERIFY
        if (!OauthUrls.allowed(verify, verifyHosts)) throw AccountSignInException(AccountSignInFailure.BadIssuer, "verify")
        val complete = verificationUriComplete?.takeIf { OauthUrls.allowed(it, verifyHosts) }
        return copy(verificationUri = verify, verificationUriComplete = complete)
    }

    /** 给人看的身份:优先用 userinfo;失败就从 ID 令牌的声明里读(只用于显示,不拿它做授权)。读不到就留空。 */
    private suspend fun identity(userinfoUrl: String?, tokens: OauthTokens): Pair<String, String> {
        if (userinfoUrl != null) {
            val raw = try {
                HttpSupport.execute(
                    http,
                    Request.Builder().url(userinfoUrl).header("Accept", "application/json")
                        .header("Authorization", "Bearer ${tokens.accessToken}").get().build(),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            val root = raw?.takeIf { it.status == 200 }?.let { jsonObject(it.body) }
            if (root != null) {
                val sub = root.str("sub").orEmpty()
                val email = root.str("email") ?: root.str("preferred_username") ?: ""
                if (sub.isNotEmpty() || email.isNotEmpty()) return sub to email
            }
        }
        return idClaims(tokens.idToken)
    }

    private suspend fun get(url: String) = try {
        HttpSupport.execute(http, Request.Builder().url(url).header("Accept", "application/json").get().build())
    } catch (e: CancellationException) {
        throw e
    } catch (e: ModelError) {
        throw AccountSignInException(AccountSignInFailure.Network, "http", e)
    }

    private fun jsonObject(body: String): JsonObject? = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()

    companion object {
        const val PROVIDER = "xai"
        const val HOST = "auth.x.ai"
        const val ISSUER = "https://auth.x.ai"
        /** xAI 官方命令行工具的公开客户端。不是给 ZorvAI 注册的,复用有风险,见界面上的说明。 */
        const val CLIENT_ID = "b1a00492-073a-47ea-816f-4c329264a828"
        const val SCOPE_CLI = "grok-cli:access"
        const val SCOPE_API = "api:access"
        const val SCOPES = "openid profile email offline_access $SCOPE_CLI $SCOPE_API"
        /** 订阅登录调用的接口根:xAI 自己的 Responses,不转成别的协议。 */
        const val API_BASE = "https://api.x.ai/v1"
        private const val FALLBACK_VERIFY = "https://accounts.x.ai/oauth2/device"

        /** ID 令牌中间一段的声明,解不开就空。不验签:只用来显示邮箱。 */
        fun idClaims(idToken: String?): Pair<String, String> {
            val payload = idToken?.split('.')?.getOrNull(1) ?: return "" to ""
            val bytes = runCatching {
                java.util.Base64.getUrlDecoder().decode(payload.trimEnd('='))
            }.getOrNull() ?: return "" to ""
            val obj = runCatching {
                Json { ignoreUnknownKeys = true }.parseToJsonElement(String(bytes, Charsets.UTF_8)) as? JsonObject
            }.getOrNull() ?: return "" to ""
            val email = obj.str("email") ?: (obj["https://api.x.ai/profile"] as? JsonObject)?.str("email") ?: ""
            return obj.str("sub").orEmpty() to email
        }
    }
}

data class XaiEndpoints(val deviceUrl: String, val tokenUrl: String, val userinfoUrl: String?)

private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
