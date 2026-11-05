package com.ai.assistance.quro.core.mika.oauth

import com.ai.assistance.quro.core.mika.model.HttpSupport
import com.ai.assistance.quro.core.mika.model.ModelError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 给用户看的设备码:用户码、验证页、有效期和轮询间隔。设备码本身不在这里,不进界面、不进日志。
 * [verificationUriComplete] 已经带上用户码,主机必须在允许名单里才会用;否则用 [verificationUri]。
 */
data class DeviceChallenge(
    val userCode: String,
    val verificationUri: String,
    val verificationUriComplete: String?,
    val expiresInSec: Long,
    val intervalSec: Long,
)

/** 一次设备码授权。设备码只留给轮询,不提供给界面。 */
class DeviceGrant internal constructor(
    val challenge: DeviceChallenge,
    internal val deviceCode: String,
)

/** 轮询一次的结果。错误里只有代码,没有响应正文(正文里可能夹着令牌)。 */
sealed interface DevicePoll {
    data object Pending : DevicePoll
    data object SlowDown : DevicePoll
    data class Tokens(val tokens: OauthTokens) : DevicePoll
    data class Rejected(val code: String, val terminal: Boolean) : DevicePoll
    data class Failed(val error: ModelError) : DevicePoll
}

/**
 * RFC 8628 设备码:向 [deviceUrl] 要一组码,再向 [tokenUrl] 用设备码换令牌。
 * 两个地址的主机都必须在 [allowedHosts] 里,而且除了本机测试以外必须是 https;对不上就抛 [AccountSignInException],请求不发出去。
 */
class DeviceCodeClient(
    private val http: OkHttpClient,
    private val deviceUrl: String,
    private val tokenUrl: String,
    private val clientId: String,
    private val scope: String,
    private val allowedHosts: Set<String>,
    private val extraDeviceForm: Map<String, String> = emptyMap(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun start(): DeviceGrant {
        val url = OauthUrls.check(deviceUrl, allowedHosts)
        val form = FormBody.Builder().add("client_id", clientId).add("scope", scope).apply {
            extraDeviceForm.forEach { (k, v) -> add(k, v) }
        }.build()
        val raw = postForm(url, form)
        val root = jsonObject(raw.body) ?: throw AccountSignInException(AccountSignInFailure.Rejected, "http_${raw.status}")
        if (raw.status != 200) {
            val code = root.str("error") ?: "http_${raw.status}"
            throw AccountSignInException(failureOf(raw.status, code), code)
        }
        val device = root.str("device_code")?.takeIf { it.isNotEmpty() }
            ?: throw AccountSignInException(AccountSignInFailure.Rejected, "no_device_code")
        val user = root.str("user_code")?.takeIf { it.isNotEmpty() }
            ?: throw AccountSignInException(AccountSignInFailure.Rejected, "no_user_code")
        val verify = root.str("verification_uri")?.takeIf { it.isNotEmpty() }
            ?: throw AccountSignInException(AccountSignInFailure.Rejected, "no_verification_uri")
        // 验证页交给调用方按自己的主机名单核对后再打开,这里不发出去
        val complete = root.str("verification_uri_complete")?.takeIf { it.isNotEmpty() }
        val expires = (root["expires_in"] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 } ?: DEFAULT_EXPIRES_SEC
        val interval = (root["interval"] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 } ?: DEFAULT_INTERVAL_SEC
        return DeviceGrant(
            DeviceChallenge(user, verify, complete, expires, interval),
            device,
        )
    }

    /** 问一次。设备码只放在表单里,不出现在返回的错误里。 */
    suspend fun poll(deviceCode: String): DevicePoll {
        val url = OauthUrls.check(tokenUrl, allowedHosts)
        val form = FormBody.Builder()
            .add("client_id", clientId)
            .add("device_code", deviceCode)
            .add("grant_type", DEVICE_GRANT)
            .build()
        val raw = try {
            postForm(url, form)
        } catch (e: CancellationException) {
            throw e
        } catch (e: AccountSignInException) {
            return DevicePoll.Failed((e.cause as? ModelError) ?: ModelError.Network(e))
        }
        return interpret(raw.status, raw.body)
    }

    private fun interpret(status: Int, body: String): DevicePoll {
        if (status in 500..599) return DevicePoll.Failed(ModelError.Server(status))
        val root = jsonObject(body)
        val err = root?.str("error")
        if (err != null) return when (err) {
            "authorization_pending" -> DevicePoll.Pending
            "slow_down" -> DevicePoll.SlowDown
            "access_denied", "authorization_denied" -> DevicePoll.Rejected(err, terminal = true)
            "expired_token" -> DevicePoll.Rejected(err, terminal = true)
            else -> DevicePoll.Rejected(err, terminal = status != 429)
        }
        if (status == 429) return DevicePoll.SlowDown
        if (status != 200 || root == null) return DevicePoll.Rejected("http_$status", terminal = false)
        val access = root.str("access_token") ?: return DevicePoll.Rejected("no_access_token", terminal = true)
        return DevicePoll.Tokens(
            OauthTokens(
                accessToken = access,
                refreshToken = root.str("refresh_token"),
                idToken = root.str("id_token"),
                expiresInSec = (root["expires_in"] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 } ?: DEFAULT_EXPIRES_SEC,
                scopes = root.str("scope")?.split(' ')?.filter { it.isNotEmpty() }.orEmpty(),
            ),
        )
    }

    private suspend fun postForm(url: HttpUrl, form: FormBody) = try {
        HttpSupport.execute(http, Request.Builder().url(url).header("Accept", "application/json").post(form).build())
    } catch (e: CancellationException) {
        throw e
    } catch (e: ModelError) {
        throw AccountSignInException(AccountSignInFailure.Network, e.message?.take(40), e)
    }

    private fun jsonObject(body: String): JsonObject? = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()

    private fun failureOf(status: Int, code: String): AccountSignInFailure = when {
        status in 500..599 || status == 429 -> AccountSignInFailure.Network
        code == "access_denied" -> AccountSignInFailure.Denied
        else -> AccountSignInFailure.Rejected
    }

    companion object {
        const val DEVICE_GRANT = "urn:ietf:params:oauth:grant-type:device_code"
        private const val DEFAULT_EXPIRES_SEC = 900L
        private const val DEFAULT_INTERVAL_SEC = 5L
    }
}

/**
 * 等到用户在浏览器里点完,或超时、拒绝、取消。轮询间隔按服务端说的来;[slow_down] 再加 5 秒(RFC 8628)。
 * 用户在浏览器里时 ZorvAI 在后台,网络可能一时不通:连着 [MAX_POLL_FAILURES] 次连不上才算失败,中间照常等下一轮。
 * [sleep] 测试里换成空等;真机用 [delay]。
 */
suspend fun awaitDeviceGrant(
    grant: DeviceGrant,
    now: () -> Long,
    sleep: suspend (Long) -> Unit = ::oauthSleep,
    poll: suspend (String) -> DevicePoll,
): OauthTokens {
    val deadline = now() + grant.challenge.expiresInSec * 1000
    var intervalMs = grant.challenge.intervalSec.coerceAtLeast(1) * 1000
    var failures = 0
    while (now() < deadline) {
        when (val r = poll(grant.deviceCode)) {
            DevicePoll.Pending -> sleep(intervalMs.coerceAtMost((deadline - now()).coerceAtLeast(0)))
            DevicePoll.SlowDown -> {
                intervalMs += SLOW_DOWN_MS
                sleep(intervalMs.coerceAtMost((deadline - now()).coerceAtLeast(0)))
            }
            is DevicePoll.Tokens -> return r.tokens
            is DevicePoll.Rejected -> throw AccountSignInException(
                when {
                    r.code == "expired_token" -> AccountSignInFailure.Expired
                    r.code == "access_denied" || r.code == "authorization_denied" -> AccountSignInFailure.Denied
                    else -> AccountSignInFailure.Rejected
                },
                r.code,
            )
            is DevicePoll.Failed -> {
                if (++failures >= MAX_POLL_FAILURES) throw AccountSignInException(AccountSignInFailure.Network, cause = r.error)
                sleep(intervalMs.coerceAtMost((deadline - now()).coerceAtLeast(0)))
                continue
            }
        }
        failures = 0
    }
    throw AccountSignInException(AccountSignInFailure.TimedOut, "expired")
}

/** 只接受 https(本机测试除外)且主机在名单里的地址。用户信息(userinfo)一律拒绝。 */
object OauthUrls {
    fun check(url: String, allowedHosts: Set<String>): HttpUrl {
        if (!allowed(url, allowedHosts)) throw AccountSignInException(AccountSignInFailure.BadIssuer, "host")
        return url.toHttpUrlOrNull()!!
    }

    fun allowed(url: String, allowedHosts: Set<String>): Boolean {
        val u = url.toHttpUrlOrNull() ?: return false
        if (u.username.isNotEmpty()) return false
        val local = u.host == "127.0.0.1" || u.host == "localhost"
        if (!local && u.scheme != "https") return false
        return u.host in allowedHosts
    }
}

/** 刷新令牌已不能再用。 */
internal val TERMINAL_REFRESH = setOf(
    "invalid_grant", "invalid_refresh_token", "token_expired", "refresh_token_expired",
    "refresh_token_invalidated", "refresh_token_reused",
)

private const val SLOW_DOWN_MS = 5_000L
private const val MAX_POLL_FAILURES = 3

/** 真机上等这么久再问下一次。默认参数用函数引用,避免编译器处理不了 lambda 里的挂起调用。 */
internal suspend fun oauthSleep(ms: Long) = delay(ms)

private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
