package com.ai.assistance.quro.core.mika.model

import com.ai.assistance.quro.core.mika.trace.TraceCall
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

internal enum class InferenceProtocol(val provider: String, val instruction: String?, val messages: String) {
    Chat("openai_compatible", null, "messages"),
    Responses("openai_compatible", "instructions", "input"),
    Anthropic("anthropic", "system", "messages"),
    Gemini("gcp.gemini", "systemInstruction", "contents");

    fun usageIn(packet: JsonObject): ReportedTokens? {
        val envelope = when (this) {
            Responses -> (packet["response"] as? JsonObject)?.get("usage") ?: packet["usage"]
            Anthropic -> (packet["message"] as? JsonObject)?.get("usage") ?: packet["usage"]
            Gemini -> packet["usageMetadata"]
            Chat -> packet["usage"]
        }
        val u = envelope as? JsonObject ?: return null
        fun n(k: String) = (u[k] as? JsonPrimitive)?.intOrNull
        fun detail(key: String, field: String) = ((u[key] as? JsonObject)?.get(field) as? JsonPrimitive)?.intOrNull
        return when (this) {
            Chat -> {
                val hit = n("prompt_cache_hit_tokens")
                val miss = n("prompt_cache_miss_tokens")
                val input = n("prompt_tokens") ?: if (hit != null && miss != null) hit + miss else null
                val cache = listOfNotNull(hit, detail("prompt_tokens_details", "cached_tokens")).maxOrNull()
                ReportedTokens(input, n("completion_tokens"), cache)
            }
            Responses -> ReportedTokens(n("input_tokens"), n("output_tokens"), detail("input_tokens_details", "cached_tokens"))
            Anthropic -> {
                val cache = n("cache_read_input_tokens")
                ReportedTokens(n("input_tokens")?.plus((cache ?: 0) + (n("cache_creation_input_tokens") ?: 0)), n("output_tokens"), cache)
            }
            Gemini -> {
                val text = n("candidatesTokenCount")
                val thoughts = n("thoughtsTokenCount")
                ReportedTokens(n("promptTokenCount"), if (text == null && thoughts == null) null else (text ?: 0) + (thoughts ?: 0),
                    n("cachedContentTokenCount"))
            }
        }
    }
}

// A failed stream may have reported only some counters. Normal model replies use each adapter's own Usage parser.
internal data class ReportedTokens(val input: Int?, val output: Int?, val cached: Int?)

/** Only inference requests carry this tag. Token refresh and catalog HTTP calls can run in the same coroutine. */
internal data class InferenceRequest(val protocol: InferenceProtocol, val payload: JsonObject, val provider: String)

internal suspend inline fun Request.Builder.postInference(
    protocol: InferenceProtocol, payload: JsonObject, provider: () -> String = { protocol.provider },
): Request.Builder {
    if (currentCoroutineContext()[TraceCall] != null) tag(InferenceRequest::class.java, InferenceRequest(protocol, payload, provider()))
    return post(payload.toString().toRequestBody("application/json".toMediaType()))
}
