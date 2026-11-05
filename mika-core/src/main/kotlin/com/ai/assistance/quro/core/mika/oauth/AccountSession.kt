package com.ai.assistance.quro.core.mika.oauth

import com.ai.assistance.quro.core.mika.model.BearerToken
import com.ai.assistance.quro.core.mika.model.ModelError
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 订阅账号登录没成的原因。界面按它说人话。异常文字里只有类别和错误代码,不放令牌、授权码、设备码。
 * [NoSubscription] 是账号没有这项订阅;[BadIssuer] 是发现到的地址不在允许的主机上,请求不会发出去。
 */
enum class AccountSignInFailure { Denied, Expired, TimedOut, Rejected, NoSubscription, Network, BadIssuer }

/** [code] 是服务端的错误代码(不是机密)。 */
class AccountSignInException(
    val reason: AccountSignInFailure,
    val code: String? = null,
    cause: Throwable? = null,
) : Exception(buildString {
    append(reason.name)
    if (!code.isNullOrBlank()) append(": ").append(code)
}, cause)

/** 换令牌得到的一组令牌。刷新没给的字段保持 null,由调用方沿用旧的。 */
data class OauthTokens(
    val accessToken: String,
    val refreshToken: String? = null,
    val idToken: String? = null,
    val expiresInSec: Long = 3600,
    val scopes: List<String> = emptyList(),
    /** 换到的接口根;没给为 null。 */
    val apiBase: String? = null,
)

/** 换令牌的结果。被拒时 [terminal] 为真表示这份授权不能再用,该清掉重新登录。 */
sealed interface OauthTokenResult {
    data class Ok(val tokens: OauthTokens) : OauthTokenResult
    data class Rejected(val code: String, val terminal: Boolean) : OauthTokenResult
    data class Failed(val error: ModelError) : OauthTokenResult
}

/**
 * 一个账号登录(目前是 xAI),整份按 API Key 的方式加密保存。
 * [accessToken] 是调用模型用的访问令牌,[refreshToken] 用来换新的。
 * 退出登录后令牌清空,账号名字留着给人看。
 */
@Serializable
data class AccountSession(
    val provider: String,
    val accountId: String = "",
    val label: String = "",
    val accessToken: String = "",
    val refreshToken: String = "",
    val expiresAtMs: Long = 0,
    val scopes: List<String> = emptyList(),
    val apiBase: String = "",
    val clientId: String = "",
    val issuer: String = "",
    /** 登录时从发现文档读到、核对过主机的令牌地址;刷新用它。旧版本存下的没有,刷新时按签发方推。 */
    val tokenUrl: String = "",
) {
    val signedIn: Boolean get() = accessToken.isNotEmpty() || refreshToken.isNotEmpty()

    fun signedOut() = copy(accessToken = "", refreshToken = "", expiresAtMs = 0)

    fun toJson(): String = JSON.encodeToString(serializer(), this)

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true }

        fun fromJson(text: String?): AccountSession? =
            text?.takeIf { it.isNotBlank() }?.let { runCatching { JSON.decodeFromString(serializer(), it) }.getOrNull() }
    }
}

/**
 * 调用时用的访问令牌。快过期或上一个被拒了就经 [refresh] 换新的,同一时刻只有一个在换。
 * 换到的令牌先 [save] 再拿去用;[save] 同时收到换之前的那份,存储据此判断这期间用户有没有退出或重新登录(那样就不该写回)。授权已不能用就清掉令牌、抛 [ModelError.SignInExpired];网络或服务端一时出错照常抛,令牌留着。
 */
class AccountTokens(
    /** 哪一家的登录(界面上的名字),登录失效时说给用户听。 */
    private val account: String,
    private val refresh: suspend (AccountSession) -> OauthTokenResult,
    private val load: () -> AccountSession?,
    private val save: (before: AccountSession, after: AccountSession) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
    private val refreshAheadMs: Long = 5 * 60_000L,
) : BearerToken {
    private val lock = Mutex()
    override val refreshable: Boolean get() = true

    override suspend fun get(fresh: Boolean): String = lock.withLock {
        val s = load()?.takeIf { it.signedIn } ?: throw ModelError.SignInExpired(account)
        if (!fresh && s.accessToken.isNotEmpty() && now() < s.expiresAtMs - refreshAheadMs) return@withLock s.accessToken
        when (val r = refresh(s)) {
            is OauthTokenResult.Ok -> {
                val t = r.tokens
                val next = s.copy(
                    accessToken = t.accessToken,
                    refreshToken = t.refreshToken ?: s.refreshToken,
                    expiresAtMs = now() + t.expiresInSec * 1000,
                    scopes = t.scopes.ifEmpty { s.scopes },
                    apiBase = t.apiBase ?: s.apiBase,
                )
                save(s, next)
                next.accessToken
            }
            is OauthTokenResult.Rejected -> {
                if (r.terminal) {
                    save(s, s.signedOut())
                    throw ModelError.SignInExpired(account)
                }
                throw ModelError.Auth()
            }
            is OauthTokenResult.Failed -> throw r.error
        }
    }
}
