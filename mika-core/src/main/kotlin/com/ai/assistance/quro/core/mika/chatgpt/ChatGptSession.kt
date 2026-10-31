package com.ai.assistance.quro.core.mika.chatgpt

import com.ai.assistance.quro.core.mika.model.BearerToken
import com.ai.assistance.quro.core.mika.model.ModelError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 一个 ChatGPT 账号的登录:注册给出的 [clientId]、核对过的身份([sub]、[email])和令牌。
 * 整份按 API Key 的方式加密保存。退出登录后令牌清空([signedIn] 为假),注册和身份留着,再登录沿用同一个 client_id。
 */
@Serializable
data class ChatGptSession(
    val clientId: String,
    val sub: String,
    val email: String = "",
    val accessToken: String = "",
    val refreshToken: String = "",
    val idToken: String = "",
    val expiresAtMs: Long = 0,
    val scopes: List<String> = emptyList(),
) {
    val signedIn: Boolean get() = accessToken.isNotEmpty() || refreshToken.isNotEmpty()

    /** 用户同意了用套餐额度调用。 */
    val planUsage: Boolean get() = ChatGptAuth.PLAN_SCOPE in scopes

    fun signedOut() = copy(accessToken = "", refreshToken = "", idToken = "", expiresAtMs = 0)

    fun toJson(): String = JSON.encodeToString(serializer(), this)

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true }

        fun fromJson(text: String?): ChatGptSession? =
            text?.takeIf { it.isNotBlank() }?.let { runCatching { JSON.decodeFromString(serializer(), it) }.getOrNull() }
    }
}

/**
 * 调用时用的访问令牌:快过期(不到 [refreshAheadMs])或被拒了([BearerToken.get] 的 fresh)就用刷新令牌换一组新的,
 * 新的刷新令牌和访问令牌一起存下([save])。同一时刻只有一个在换(刷新令牌换一次就作废,并发换会互相顶掉)。
 * 刷新令牌已不能用:清掉令牌、抛 [ModelError.SignInExpired](要重新登录);网络或服务端一时出错照常抛,令牌留着。
 */
class ChatGptTokens(
    private val auth: ChatGptAuth,
    private val load: () -> ChatGptSession?,
    private val save: (ChatGptSession) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
    private val refreshAheadMs: Long = 5 * 60_000L,
) : BearerToken {
    private val lock = Mutex()
    override val refreshable: Boolean get() = true

    override suspend fun get(fresh: Boolean): String = lock.withLock {
        val s = load()?.takeIf { it.signedIn } ?: throw ModelError.SignInExpired()
        if (!fresh && s.accessToken.isNotEmpty() && now() < s.expiresAtMs - refreshAheadMs) return@withLock s.accessToken
        if (s.refreshToken.isEmpty()) {
            save(s.signedOut())
            throw ModelError.SignInExpired()
        }
        when (val r = auth.refresh(s.clientId, s.refreshToken)) {
            is TokenResult.Ok -> {
                val t = r.tokens
                val next = s.copy(
                    accessToken = t.accessToken,
                    refreshToken = t.refreshToken ?: s.refreshToken,
                    idToken = t.idToken ?: s.idToken,
                    expiresAtMs = now() + t.expiresInSec * 1000,
                    scopes = t.scopes.ifEmpty { s.scopes },
                )
                save(next)
                if (!next.planUsage) throw ModelError.ChatGptNotEligible()
                next.accessToken
            }
            is TokenResult.Rejected -> {
                if (r.terminal) {
                    save(s.signedOut())
                    throw ModelError.SignInExpired()
                }
                // 客户端配置不对等:重试没用,但也不是令牌失效,不清
                throw ModelError.Auth()
            }
            is TokenResult.Failed -> throw r.error
        }
    }
}

/**
 * 走一遍登录:开本机回调 → [openBrowser] 打开授权页 → 等回调 → 核对 → 换令牌 → 验 ID 令牌 → 看有没有授予套餐额度。
 * 回调到了以后的这几步(至多 [finishTimeoutMs])做完,才把结果网页回给浏览器:[page] 收到 null 是成功,否则是失败的原因。
 * [clientId] 是以前注册过的(第一次为 null,注册成功后经 [onRegistered] 交出,换令牌前就该存下);
 * [previous] 是上次的登录(用来带上 ID 令牌和邮箱,省掉选账号)。失败抛 [SignInException]。
 */
suspend fun signInWithChatGpt(
    auth: ChatGptAuth,
    hostId: String,
    clientId: String?,
    previous: ChatGptSession?,
    openBrowser: (String) -> Unit,
    onRegistered: (String) -> Unit,
    page: (SignInException?) -> String,
    timeoutMs: Long = 5 * 60_000L,
    finishTimeoutMs: Long = 20_000L,
    now: () -> Long = System::currentTimeMillis,
): ChatGptSession {
    var outcome: Result<ChatGptSession>? = null
    LoopbackCallback.open().use { cb ->
        val attempt = auth.newAttempt(cb.redirectUri)
        val hint = previous?.takeIf { it.signedIn && it.clientId == clientId }
        openBrowser(auth.authorizeUrl(attempt, clientId, hostId, hint?.idToken, previous?.email?.takeIf { clientId != null }))
        cb.await(timeoutMs, { it["state"] == attempt.state }) { params ->
            val r = finishSignIn(finishTimeoutMs) { completeSignIn(auth, params, attempt, clientId, onRegistered, now) }
            outcome = r
            page(r.exceptionOrNull() as SignInException?)
        } ?: throw SignInException(SignInFailure.TimedOut)
    }
    return outcome!!.getOrThrow()
}

/** 做完回调之后的几步,至多 [timeoutMs]:超时算连不上;别的异常都归成 [SignInException];取消照常往外抛。 */
private suspend fun finishSignIn(timeoutMs: Long, block: suspend () -> ChatGptSession): Result<ChatGptSession> = try {
    Result.success(withTimeout(timeoutMs) { block() })
} catch (e: TimeoutCancellationException) {
    Result.failure(SignInException(SignInFailure.Network, "no answer in ${timeoutMs / 1000}s", e))
} catch (e: CancellationException) {
    throw e
} catch (e: SignInException) {
    Result.failure(e)
} catch (e: Exception) {
    Result.failure(SignInException(SignInFailure.Network, e.message.orEmpty(), e))
}

private suspend fun completeSignIn(
    auth: ChatGptAuth,
    params: Map<String, String>,
    attempt: SignInAttempt,
    clientId: String?,
    onRegistered: (String) -> Unit,
    now: () -> Long,
): ChatGptSession {
    val (code, issued) = auth.readCallback(params, attempt, clientId)
    if (issued != clientId) onRegistered(issued)
    val tokens = when (val r = auth.exchange(issued, code, attempt)) {
        is TokenResult.Ok -> r.tokens
        is TokenResult.Rejected -> throw ChatGptAuth.rejected(r)
        is TokenResult.Failed -> throw SignInException(SignInFailure.Network, r.error.message.orEmpty(), r.error)
    }
    val idToken = tokens.idToken ?: throw SignInException(SignInFailure.IdToken, "no id_token")
    val who = auth.verifyIdToken(idToken, issued, attempt.nonce)
    if (ChatGptAuth.PLAN_SCOPE !in tokens.scopes) throw SignInException(SignInFailure.NoPlanScope, "scope", code = "no_plan_scope")
    return ChatGptSession(
        clientId = issued, sub = who.sub, email = who.email,
        accessToken = tokens.accessToken, refreshToken = tokens.refreshToken.orEmpty(), idToken = idToken,
        expiresAtMs = now() + tokens.expiresInSec * 1000, scopes = tokens.scopes,
    )
}
