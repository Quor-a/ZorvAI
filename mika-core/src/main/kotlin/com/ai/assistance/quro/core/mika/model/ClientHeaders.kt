package com.ai.assistance.quro.core.mika.model

import okhttp3.Request
import java.util.UUID

/**
 * 一段对话对外的会话号:同一段对话的每个请求(主模型、看图、轻量、起标题、核查、意图判断、停止后的交代)都用同一个,
 * 服务商据此把请求路由到一处、复用提示词缓存。由对话 id 推出(UUID v3,不可逆),不把对话 id 本身发出去。
 * 不属于任何对话的请求(连接测试、列模型)每次用 [fresh] 的新号。
 */
@JvmInline
value class ModelSession(val id: String) {
    companion object {
        fun of(conversationId: String): ModelSession =
            ModelSession(UUID.nameUUIDFromBytes("quro-conversation:$conversationId".toByteArray()).toString())

        fun fresh(): ModelSession = ModelSession(UUID.randomUUID().toString())
    }
}

/** 发给模型服务的每个请求都带的头;Key 的头由各适配器自己加。 */
object ClientHeaders {
    /**
     * [userAgent] 标明是 ZorvAI 哪个版本(由 App 给,core 不写版本号);[sessionHeader] 是服务商声明要放会话号的头名,
     * 没声明(null 或空)就不发会话号。
     */
    fun of(userAgent: String, sessionHeader: String?, session: ModelSession): Map<String, String> = buildMap {
        put("User-Agent", userAgent)
        if (!sessionHeader.isNullOrBlank()) put(sessionHeader.trim(), session.id)
    }
}

internal fun Request.Builder.withHeaders(headers: Map<String, String>): Request.Builder =
    apply { headers.forEach { (k, v) -> header(k, v) } }
