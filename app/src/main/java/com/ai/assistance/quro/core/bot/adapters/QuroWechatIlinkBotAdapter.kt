package com.ai.assistance.quro.core.bot.adapters

import android.content.Context
import com.ai.assistance.quro.core.bot.QuroBotPlatform
import com.ai.assistance.quro.core.bot.QuroOutboundMessage
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * 微信 iLink 个人号 Bot 适配器（直连官方域名，零公网端点、连 Webhook 都不用）。
 *
 * 接入形态（元宝核实，2026 官方新通道，最像 Telegram）：
 *  - 端点：官方固定域名 ilinkai.weixin.qq.com，路径 /ilink/bot/getupdates、/ilink/bot/sendmessage 写死即可
 *  - 鉴权：手机微信扫二维码 → 拿 bot_token；请求头必须同时带 Authorization: Bearer xxx 与 AuthorizationType: ilink_bot_token
 *  - 收消息：HTTP 长轮询 POST /ilink/bot/getupdates（35s 超时），不需要 WebSocket / 公网 IP / Webhook
 *  - 发消息：POST /ilink/bot/sendmessage，必须回带 inbound 的 context_token（按 chat_id 缓存）
 *  - 结论：完全不用申请端点，后端纯出网请求即可
 *
 * 本适配器实现「稳态」：持 bot_token 做长轮询 + 发消息。
 * 登录（扫码拿 token）一步：iLink 的扫码登录官方端点未在本环境确认，故设置页提供
 * 「bot_token 粘贴」入口；待用户提供确切的 /ilink/bot/login QR 端点后补扫码流程。
 */
class QuroWechatIlinkBotAdapter(context: Context) : QuroDirectBotAdapter(context) {
    override val platform = QuroBotPlatform.WECHAT

    private val botToken get() = pref("wechat_token")

    private var offset = 0L

    /** 按 chat_id 缓存 inbound 的 context_token，sendmessage 必须回带。 */
    private val contextTokens = ConcurrentHashMap<String, String>()

    override fun isConfigured(): Boolean = botToken.isNotBlank()

    override suspend fun runConnection() {
        var retries = 0
        while (!stopped.get()) {
            if (botToken.isBlank()) { delay(2000); continue }
            try {
                val headers = mapOf(
                    "Authorization" to "Bearer $botToken",
                    "AuthorizationType" to "ilink_bot_token",
                )
                val body = JSONObject().apply { put("timeout", 35); put("offset", offset) }.toString()
                val root = httpPostJson(
                    "https://ilinkai.weixin.qq.com/ilink/bot/getupdates",
                    headers = headers,
                    json = body,
                ) ?: run { backoff(retries++); continue }
                retries = 0
                val result = root.optJSONArray("result") ?: JSONArray()
                for (i in 0 until result.length()) {
                    val upd = result.optJSONObject(i) ?: continue
                    val msg = upd.optJSONObject("message") ?: continue
                    val from = msg.optJSONObject("from")
                    val chat = msg.optJSONObject("chat")
                    val uid = from?.optString("id").orEmpty().ifBlank { chat?.optString("id").orEmpty() }
                    val cid = chat?.optString("id").orEmpty().ifBlank { uid }
                    val text = msg.optString("text", "").trim()
                    // 缓存 inbound context_token（按 update / message / chat 三级兜底取）
                    val ctxToken = msg.optString("context_token").ifBlank {
                        upd.optString("context_token").ifBlank { chat?.optString("context_token").orEmpty() }
                    }
                    if (cid.isNotBlank() && ctxToken.isNotBlank()) contextTokens[cid] = ctxToken
                    if (cid.isNotBlank() && text.isNotBlank()) {
                        onInbound(cid, uid.ifBlank { cid }, text)
                    }
                    val u = upd.optLong("update_id", 0)
                    if (u > offset) offset = u + 1
                }
                val next = root.optLong("next_offset", 0)
                if (next > offset) offset = next
            } catch (e: Exception) {
                Log_e("长轮询异常: ${e.message}")
                backoff(retries++)
            }
            // 无消息时也短暂让出，避免空转（有消息时 resp 很快返回）
            if (!stopped.get()) delay(500)
        }
    }

    override suspend fun deliver(reply: QuroOutboundMessage) {
        if (botToken.isBlank()) return
        val ctxToken = contextTokens[reply.userId].orEmpty()
        val body = JSONObject().apply {
            put("chat_id", reply.userId)
            put("text", reply.text)
            if (ctxToken.isNotBlank()) put("context_token", ctxToken)
        }.toString()
        val json = httpPostJson(
            "https://ilinkai.weixin.qq.com/ilink/bot/sendmessage",
            headers = mapOf(
                "Authorization" to "Bearer $botToken",
                "AuthorizationType" to "ilink_bot_token",
            ),
            json = body,
        )
        if (json == null) Log_e("deliver 失败 chat=${reply.userId}")
        else Log_i("deliver 已发往微信会话 ${reply.userId}")
    }

    private fun Log_i(s: String) = android.util.Log.i(TAG, "[WeChat] $s")
    private fun Log_w(s: String) = android.util.Log.w(TAG, "[WeChat] $s")
    private fun Log_e(s: String) = android.util.Log.e(TAG, "[WeChat] $s")
}
