package com.ai.assistance.quro.core.bot.adapters

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

private const val MAX_CONSECUTIVE_FAILURES = 3
private const val BACKOFF_DELAY_MS = 30_000L
private const val RETRY_DELAY_MS = 2_000L
private const val SESSION_PAUSE_MS = 300_000L
private const val DEFAULT_LONG_POLL_MS = 38_000L
private const val TAG = "ClawBotPoller"

/**
 * iLink getupdates 长轮询循环。
 * 纯 OkHttp + org.json，由 QuroWechatIlinkBotAdapter 在 IO 协程中驱动。
 */
class ClawBotPoller(
    private val api: ClawBotApiClient,
    private val getAuthToken: () -> String?,
    private val getBaseUrl: () -> String,
    private val loadSyncBuf: () -> String,
    private val saveSyncBuf: (String) -> Unit,
) {
    data class InboundMessage(
        val fromUserId: String,
        val text: String,
        val contextToken: String,
        val messageId: String,
    )

    suspend fun runLoop(
        onInbound: suspend (InboundMessage) -> Unit,
        onConnected: () -> Unit,
        onDisconnected: () -> Unit,
        shouldStop: () -> Boolean,
    ) {
        var nextTimeoutMs = DEFAULT_LONG_POLL_MS
        var consecutiveFailures = 0
        var getUpdatesBuf = loadSyncBuf()

        Log.d(TAG, "轮询循环启动")

        while (!shouldStop()) {
            val token = getAuthToken()
            val baseUrl = getBaseUrl()
            if (token.isNullOrBlank() || baseUrl.isBlank()) {
                onDisconnected()
                kotlinx.coroutines.delay(RETRY_DELAY_MS)
                continue
            }

            try {
                val raw = api.postGetUpdates(baseUrl, getUpdatesBuf, token, nextTimeoutMs)
                val env = parseEnvelope(raw)

                if (env.longPollingTimeoutMs != null && env.longPollingTimeoutMs > 0) {
                    nextTimeoutMs = env.longPollingTimeoutMs
                }

                val isApiError = (env.ret != 0) || (env.errCode != 0)
                if (isApiError) {
                    val sessionExpired = env.errCode == SESSION_EXPIRED_ERRCODE || env.ret == SESSION_EXPIRED_ERRCODE
                    if (sessionExpired) {
                        consecutiveFailures = 0
                        onDisconnected()
                        Log.w(TAG, "会话过期 (errcode=-14)，暂停 ${SESSION_PAUSE_MS / 1000}s")
                        kotlinx.coroutines.delay(SESSION_PAUSE_MS)
                        continue
                    }
                    consecutiveFailures++
                    onDisconnected()
                    Log.w(TAG, "API 错误: ret=${env.ret}, errcode=${env.errCode}")
                    if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                        consecutiveFailures = 0
                        kotlinx.coroutines.delay(BACKOFF_DELAY_MS)
                    } else {
                        kotlinx.coroutines.delay(RETRY_DELAY_MS)
                    }
                    continue
                }

                consecutiveFailures = 0
                onConnected()

                if (!env.getUpdatesBuf.isNullOrEmpty()) {
                    saveSyncBuf(env.getUpdatesBuf)
                    getUpdatesBuf = env.getUpdatesBuf
                }

                for (msg in env.msgs) {
                    val incoming = mapToIncoming(msg) ?: continue
                    onInbound(incoming)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                consecutiveFailures++
                onDisconnected()
                Log.e(TAG, "轮询异常: ${e.message}")
                if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                    consecutiveFailures = 0
                    kotlinx.coroutines.delay(BACKOFF_DELAY_MS)
                } else {
                    kotlinx.coroutines.delay(RETRY_DELAY_MS)
                }
            }
        }
        Log.d(TAG, "轮询循环结束")
    }

    private fun mapToIncoming(msg: JSONObject): InboundMessage? {
        val from = msg.optString("from_user_id", "").trim()
        if (from.isEmpty()) return null
        val text = extractText(msg).trim()
        if (text.isEmpty()) return null
        val mid = if (msg.has("message_id") && !msg.isNull("message_id")) {
            msg.optInt("message_id", 0).toString()
        } else {
            msg.optString("client_id", msg.optInt("seq", 0).toString())
        }
        val ctx = msg.optString("context_token", "").trim()
        if (ctx.isEmpty()) {
            Log.d(TAG, "丢弃消息: 缺少 context_token (from=$from)")
            return null
        }
        return InboundMessage(
            fromUserId = from,
            text = text,
            contextToken = ctx,
            messageId = mid,
        )
    }

    private fun extractText(msg: JSONObject): String {
        val items = msg.optJSONArray("item_list") ?: return ""
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val type = item.optInt("type", 0)
            if (type == 1) {
                val ti = item.optJSONObject("text_item") ?: continue
                return ti.optString("text", "")
            }
            if (type == 3) {
                val vi = item.optJSONObject("voice_item") ?: continue
                val vt = vi.optString("text", "")
                if (vt.isNotBlank()) return vt
            }
        }
        return ""
    }

    private data class Envelope(
        val ret: Int,
        val errCode: Int,
        val getUpdatesBuf: String?,
        val longPollingTimeoutMs: Long?,
        val msgs: List<JSONObject>
    )

    private fun parseEnvelope(json: String): Envelope {
        val o = JSONObject(json)
        val ret = o.optInt("ret", 0)
        val errCode = o.optInt("errcode", 0)
        val buf = o.optString("get_updates_buf", "").takeIf { it.isNotEmpty() }
        val lp = if (o.has("longpolling_timeout_ms") && !o.isNull("longpolling_timeout_ms")) {
            o.optLong("longpolling_timeout_ms", 0)
        } else null
        val msgs = ArrayList<JSONObject>()
        val arr = o.optJSONArray("msgs") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val el = arr.opt(i)
            if (el is JSONObject) msgs.add(el)
        }
        return Envelope(ret, errCode, buf, lp, msgs)
    }

    companion object {
        const val SESSION_EXPIRED_ERRCODE = -14
    }
}
