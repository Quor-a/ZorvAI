package com.ai.assistance.quro.core.tools

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SignatureException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 各云端 TTS 服务商的合成客户端。
 * 统一入口：[QuroTtsClients.get(kind).synth] → 返回 (音频字节, 格式)。
 * 格式取值：mp3 / wav / pcm16。播放层据此选择 MediaPlayer / AudioTrack。
 */
data class QuroTtsSynthRequest(
    val ctx: Context,
    val text: String,
    val voice: String,
    val styleTags: List<String>,
    val customStyleTags: List<String>,
    val styleNL: String,           // 来自 QuroSpeechStyleDeriver 的自然语言风格指令
    val format: String,
    val model: String,
    val fields: Map<String, String>,
    val baseUrl: String,
    val def: QuroTtsProviderDef,
    val customVoices: List<CloudCustomVoice> = emptyList(),
    val speed: Float = 1.0f,       // 语速倍率（1.0=默认；0.5–2.0），由人格语音组合驱动；各客户端按自身参数映射
)

interface QuroTtsClient {
    suspend fun synth(req: QuroTtsSynthRequest): Pair<ByteArray, String>
}

object QuroTtsClients {
    fun get(kind: QuroTtsProviderKind): QuroTtsClient = when (kind) {
        QuroTtsProviderKind.OPENAI_COMPAT -> OpenAiCompatClient
        QuroTtsProviderKind.EDGE_TTS -> EdgeTtsClient
        QuroTtsProviderKind.MIMO -> MimoClient
        QuroTtsProviderKind.VOLCENGINE -> VolcengineClient
        QuroTtsProviderKind.IFLYTEK -> IflytekClient
        QuroTtsProviderKind.TENCENT -> TencentClient
        QuroTtsProviderKind.MINIMAX -> MiniMaxClient
    }
}

private val httpClient = OkHttpClient.Builder()
    .connectTimeout(30, TimeUnit.SECONDS)
    .readTimeout(120, TimeUnit.SECONDS)
    .writeTimeout(30, TimeUnit.SECONDS)
    .build()

// =====================================================================================
// 1) OpenAI 兼容（OpenAI / 硅基流动 / TTS302 / CozeCn / Gizwits / ACGN / 阿里百炼CosyVoice）
// =====================================================================================
object OpenAiCompatClient : QuroTtsClient {
    private const val TAG = "TtsOpenAi"
    override suspend fun synth(req: QuroTtsSynthRequest): Pair<ByteArray, String> = withContext(Dispatchers.IO) {
        val apiKey = req.fields["api_key"] ?: ""
        val base = req.baseUrl.ifBlank { req.def.defaultBaseUrl }.trimEnd('/')
        val url = if (base.endsWith("/audio/speech")) base else "$base/audio/speech"
        val voice = req.voice.ifBlank { "alloy" }
        val fmt = req.format.ifBlank { "mp3" }
        val model = req.model.ifBlank { req.def.defaultModel.ifBlank { "tts-1" } }
        // gpt-4o-mini-tts 等支持 instructions 注入风格；其余模型忽略
        val supportsInstructions = model.contains("4o", ignoreCase = true) || model.contains("mini-tts", ignoreCase = true)
        val body = JSONObject().apply {
            put("model", model)
            put("input", req.text)
            put("voice", voice)
            put("response_format", fmt)
            if (supportsInstructions && req.styleNL.isNotBlank()) put("instructions", req.styleNL)
        }
        Log.i(TAG, ">>> ${req.def.id} model=$model voice=$voice fmt=$fmt")
        val r = Request.Builder().url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val resp = httpClient.newCall(r).execute()
        val raw = resp.body?.bytes()
        if (!resp.isSuccessful || raw == null) {
            val msg = raw?.toString(Charsets.UTF_8) ?: ""
            throw Exception("${req.def.name} 合成失败 HTTP ${resp.code}：${msg.take(200)}")
        }
        Pair(raw, fmt)
    }
}

// =====================================================================================
// 2) Edge TTS（免费，WebSocket + SSML express-as）
// =====================================================================================
object EdgeTtsClient : QuroTtsClient {
    private const val TAG = "TtsEdge"
    // 中文情绪标签 → Edge express-as 风格（仅映射已知安全风格，避免语音不支持报错）
    private val STYLE_MAP = mapOf(
        "开心" to "cheerful", "兴奋" to "cheerful", "俏皮" to "cheerful",
        "悲伤" to "sad", "委屈" to "sad", "动情" to "sad",
        "愤怒" to "angry", "严肃" to "serious", "高冷" to "serious",
        "恐惧" to "fearful", "害怕" to "fearful",
        "平静" to "calm", "冷漠" to "calm", "深沉" to "calm",
        "温柔" to "gentle", "甜美" to "gentle", "清亮" to "gentle",
        "活泼" to "cheerful",
    )

    override suspend fun synth(req: QuroTtsSynthRequest): Pair<ByteArray, String> = withContext(Dispatchers.IO) {
        val token = fetchToken()
        val voice = req.voice.ifBlank { "zh-CN-XiaoxiaoNeural" }
        val style = req.styleTags.firstOrNull { it in STYLE_MAP }?.let { STYLE_MAP[it] }
        val ssml = buildSsml(req.text, voice, style, req.speed)
        Log.i(TAG, ">>> voice=$voice style=$style speed=${req.speed}")
        val audio = connectWs(token, ssml)
        Pair(audio, "mp3")
    }

    private fun fetchToken(): String {
        val url = "https://eastus.api.speech.microsoft.com/cognitiveservices/avatar/relay/token"
        // 该中继令牌端点仅接受 GET（POST 会返回 HTTP 405），无需请求体。
        val r = Request.Builder().url(url)
            .get()
            .build()
        val resp = httpClient.newCall(r).execute()
        if (!resp.isSuccessful) throw Exception("获取 Edge 令牌失败 HTTP ${resp.code}")
        return resp.body?.string()?.trim() ?: throw Exception("获取 Edge 令牌返回为空")
    }

    private fun buildSsml(text: String, voice: String, style: String?, speed: Float): String {
        val safe = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        // Edge 语速：相对百分比（1.0=0%，1.1=+10%，0.9=-10%）；裁剪到 ±50% 安全范围
        val clamped = speed.coerceIn(0.5f, 2.0f)
        val rate = if (kotlin.math.abs(clamped - 1.0f) < 0.01f) "0%" else {
            val pct = ((clamped - 1.0f) * 100).toInt()
            "${if (pct > 0) "+" else ""}$pct%"
        }
        val inner = if (style != null) {
            "<mstts:express-as style='$style'>$safe</mstts:express-as>"
        } else {
            safe
        }
        return "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' " +
            "xmlns:mstts='https://www.w3.org/2001/mstts' xml:lang='zh-CN'>" +
            "<voice name='$voice'><prosody rate='$rate'>$inner</prosody></voice></speak>"
    }

    private fun connectWs(token: String, ssml: String): ByteArray {
        val connId = UUID.randomUUID().toString().uppercase()
        val wsUrl = "wss://eastus.api.speech.microsoft.com/cognitiveservices/websocket/v1" +
            "?Authorization=Bearer%20${URLEncoder.encode(token, "UTF-8")}&X-ConnectionId=$connId"
        val out = ByteArrayOutputStream()
        val done = CountDownLatch(1)
        var error: Exception? = null
        val ws = httpClient.newWebSocket(
            Request.Builder().url(wsUrl).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    val config = JSONObject().put(
                        "context",
                        JSONObject().put(
                            "synthesis",
                            JSONObject().put(
                                "audio",
                                JSONObject().put(
                                    "metadataoptions",
                                    JSONObject().put("sentenceBoundaryEnabled", "false").put("wordBoundaryEnabled", "false"),
                                ).put("outputFormat", "audio-16khz-32kbitrate-mono-mp3"),
                            ),
                        ),
                    ).toString()
                    webSocket.send(config)
                    val ssmlBytes = ssml.toByteArray(Charsets.UTF_8)
                    val frame = ByteArray(ssmlBytes.size + 4)
                    frame[0] = 0x00; frame[1] = 0x01; frame[2] = 0x00; frame[3] = 0x00
                    System.arraycopy(ssmlBytes, 0, frame, 4, ssmlBytes.size)
                    webSocket.send(ByteString.of(*frame))
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    val b = bytes.toByteArray()
                    if (b.size >= 2 && b[0] == 0x00.toByte() && b[1] == 0x02.toByte()) {
                        out.write(b, 2, b.size - 2)
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (text.contains("\"Path\":\"turn.end\"") || text.contains("\"Path\":\"synthesis.complete\"")) {
                        // 数据已收齐，等 onClosing/onClosed 释放锁
                    }
                    if (text.contains("\"error\"", ignoreCase = true)) {
                        error = Exception("Edge TTS 错误：${text.take(200)}")
                    }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    done.countDown()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    error = Exception("Edge TTS 连接失败：${t.message}")
                    done.countDown()
                }
            },
        )
        val ok = runCatching { done.await(120, TimeUnit.SECONDS) }.getOrDefault(false)
        if (error != null) throw error!!
        if (!ok) throw Exception("Edge TTS 超时")
        val result = out.toByteArray()
        if (result.isEmpty()) throw Exception("Edge TTS 返回音频为空")
        return result
    }
}

// =====================================================================================
// 3) 小米 MiMo（/chat/completions + audio，支持 (风格) 分段 + 设计/复刻音色）
// =====================================================================================
object MimoClient : QuroTtsClient {
    private const val TAG = "TtsMimo"

    override suspend fun synth(req: QuroTtsSynthRequest): Pair<ByteArray, String> = withContext(Dispatchers.IO) {
        val apiKey = req.fields["api_key"] ?: ""
        val base = req.baseUrl.ifBlank { req.def.defaultBaseUrl }.trimEnd('/')
        val format = req.format.ifBlank { "wav" }
        val model = req.model.ifBlank { "mimo-v2.5-tts" }
        val style = req.styleNL
        val availableTags = QuroCloudTtsCatalog.EMOTION_TAGS
        val segs = QuroVoiceStyle.segment(req.text, availableTags)
        val synthOne: suspend (String, List<String>) -> Pair<ByteArray, String> = { text, tags ->
            val (b, isWav) = mimoSynthOne(req, apiKey, base, format, model, style, text, tags)
            b to (if (isWav) "wav" else "pcm16")
        }
        if (segs.isEmpty() || !QuroVoiceStyle.hasMarkers(req.text, availableTags)) {
            val whole = if (segs.isEmpty()) req.text else QuroVoiceStyle.strip(req.text)
            return@withContext synthOne(whole, emptyList())
        }
        val out = ByteArrayOutputStream()
        for (seg in segs) {
            if (seg.text.isBlank()) continue
            val (b, fmt) = synthOne(seg.text, seg.tags)
            if (fmt == "wav") out.write(QuroCloudTts.parseWav(b).first) else out.write(b)
        }
        if (out.size() == 0) return@withContext synthOne(QuroVoiceStyle.strip(req.text), emptyList())
        Pair(out.toByteArray(), "pcm16")
    }

    private suspend fun mimoSynthOne(
        req: QuroTtsSynthRequest, apiKey: String, baseUrl: String, format: String,
        model: String, style: String, segText: String, segTags: List<String>,
    ): Pair<ByteArray, Boolean> = withContext(Dispatchers.IO) {
        var modelId = model
        val audioJson = JSONObject()
        var userContent = ""
        val assistantContent = if (segTags.isNotEmpty()) "(${segTags.joinToString(" ")}) $segText" else segText
        when {
            model == "mimo-v2.5-tts" -> {
                modelId = "mimo-v2.5-tts"
                userContent = style
                audioJson.put("format", format).put("voice", resolvePresetVoice(req))
            }
            model == "mimo-v2.5-tts-voicedesign" -> {
                modelId = "mimo-v2.5-tts-voicedesign"
                val custom = resolveCustom(req, "design")
                userContent = (custom?.designText ?: "").trim()
                audioJson.put("format", format).put("optimize_text_preview", true)
            }
            model == "mimo-v2.5-tts-voiceclone" -> {
                modelId = "mimo-v2.5-tts-voiceclone"
                val custom = resolveCustom(req, "clone")
                val dataUri = readCloneDataUri(req.ctx, custom?.cloneUri ?: "")
                userContent = style
                audioJson.put("format", format).put("voice", dataUri)
            }
            else -> {
                modelId = "mimo-v2.5-tts"
                userContent = style
                audioJson.put("format", format).put("voice", resolvePresetVoice(req))
            }
        }
        val messages = JSONArray().apply {
            put(JSONObject().put("role", "user").put("content", userContent))
            put(JSONObject().put("role", "assistant").put("content", assistantContent))
        }
        val body = JSONObject().apply {
            put("model", modelId); put("stream", false); put("messages", messages); put("audio", audioJson)
        }
        val url = if (baseUrl.endsWith("/chat/completions")) baseUrl else "$baseUrl/chat/completions"
        Log.i(TAG, ">>> seg model=$modelId tags=[${segTags.joinToString("+")}] len=${segText.length}")
        val r = Request.Builder().url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val resp = httpClient.newCall(r).execute()
        val respText = resp.body?.string().orEmpty()
        if (!resp.isSuccessful) throw Exception("小米合成失败 HTTP ${resp.code}：${respText.take(200)}")
        val root = JSONObject(respText)
        val audio = root.getJSONArray("choices").getJSONObject(0).getJSONObject("message").optJSONObject("audio")
            ?: throw Exception("响应缺少 audio 字段")
        val b64 = audio.optString("data", "")
        if (b64.isBlank()) throw Exception("audio.data 为空")
        val bytes = Base64.decode(b64, Base64.DEFAULT)
        val isWav = format == "wav" || bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "RIFF"
        Pair(bytes, isWav)
    }

    private fun resolvePresetVoice(req: QuroTtsSynthRequest): String {
        val v = req.voice
        return if (v.startsWith("custom::")) "mimo_default" else v.ifBlank { "mimo_default" }
    }

    private fun resolveCustom(req: QuroTtsSynthRequest, type: String): CloudCustomVoice? {
        val v = req.voice
        if (!v.startsWith("custom::")) return null
        val name = v.removePrefix("custom::")
        return req.customVoices.firstOrNull { it.name == name && it.type == type }
    }

    private fun readCloneDataUri(ctx: Context, cloneUri: String): String {
        if (cloneUri.isBlank()) throw Exception("复刻音色缺少音频样本")
        val uri = Uri.parse(cloneUri)
        val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw Exception("无法读取复刻音频样本")
        if (bytes.size > 10 * 1024 * 1024) throw Exception("复刻音频超过 10MB 限制")
        val mime = ctx.contentResolver.getType(uri) ?: "audio/mpeg"
        val safeMime = if (mime == "audio/mp3") "audio/mpeg" else mime
        return "data:$safeMime;base64,${Base64.encodeToString(bytes, Base64.NO_WRAP)}"
    }
}

// =====================================================================================
// 4) 火山引擎 豆包 / 灵犀（REST）
// =====================================================================================
object VolcengineClient : QuroTtsClient {
    private const val TAG = "TtsVolc"
    override suspend fun synth(req: QuroTtsSynthRequest): Pair<ByteArray, String> = withContext(Dispatchers.IO) {
        val token = req.fields["token"] ?: ""
        val appId = req.fields["app_id"] ?: ""
        val cluster = req.fields["cluster"] ?: "volcabcluster"
        val voiceType = req.voice.ifBlank { "zh_female_qingxin" }
        val format = req.format.ifBlank { "mp3" }
        val url = req.baseUrl.ifBlank { req.def.defaultBaseUrl }.trimEnd('/')
        val body = JSONObject().apply {
            put("app", JSONObject().put("appid", appId).put("cluster", cluster))
            put("user", JSONObject().put("uid", "quro_user"))
            put("audio", JSONObject().put("voice_type", voiceType).put("encoding", format).put("speed_ratio", req.speed.toDouble()))
            put(
                "request",
                JSONObject().put("reqid", UUID.randomUUID().toString()).put("text", req.text).put("operation", "query"),
            )
        }
        Log.i(TAG, ">>> voiceType=$voiceType fmt=$format")
        val r = Request.Builder().url(url)
            .addHeader("Authorization", "Bearer; $token")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val resp = httpClient.newCall(r).execute()
        val respText = resp.body?.string().orEmpty()
        if (!resp.isSuccessful) throw Exception("火山合成失败 HTTP ${resp.code}：${respText.take(200)}")
        val root = JSONObject(respText)
        val code = root.optString("code", "")
        if (code != "3000") throw Exception("火山合成失败：${root.optString("message", respText.take(120))}")
        val b64 = root.optString("data", "")
        if (b64.isBlank()) throw Exception("火山合成返回为空")
        Pair(Base64.decode(b64, Base64.DEFAULT), format)
    }
}

// =====================================================================================
// 5) 科大讯飞（WebSocket + HMAC-SHA256）
// =====================================================================================
object IflytekClient : QuroTtsClient {
    private const val TAG = "TtsIflytek"
    override suspend fun synth(req: QuroTtsSynthRequest): Pair<ByteArray, String> = withContext(Dispatchers.IO) {
        val appId = req.fields["app_id"] ?: ""
        val apiKey = req.fields["api_key"] ?: ""
        val apiSecret = req.fields["api_secret"] ?: ""
        val voice = req.voice.ifBlank { "xiaoyan" }
        val fmt = req.format.ifBlank { "mp3" }
        val aue = if (fmt == "pcm16") "raw" else "lame" // raw → pcm16, lame → mp3
        val out = ByteArrayOutputStream()
        val done = CountDownLatch(1)
        var error: Exception? = null

        val date = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US)
            .also { it.timeZone = TimeZone.getTimeZone("GMT") }
            .format(java.util.Date())
        val host = "iat-api.xfyun.cn"
        val requestLine = "GET /v2/tts HTTP/1.1"
        val signatureOrigin = "host: $host\ndate: $date\n$requestLine"
        val signature = base64(hmacSha256(apiSecret, signatureOrigin))
        val authorization = """api_key="$apiKey", algorithm="hmac-sha256", headers="host date request-line", signature="$signature""""
        val wsUrl = "wss://$host/v2/tts?authorization=${URLEncoder.encode(authorization, "UTF-8")}&date=${URLEncoder.encode(date, "UTF-8")}&host=${URLEncoder.encode(host, "UTF-8")}"

        val textB64 = Base64.encodeToString(req.text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val frame = JSONObject().apply {
            put("common", JSONObject().put("app_id", appId))
            put(
                "business",
                JSONObject().put("aue", aue).put("auf", "audio/L16;rate=16000")
                    .put("vcn", voice).put("speed", ((req.speed * 50).toInt()).coerceIn(0, 100)).put("volume", 50).put("pitch", 50).put("bgs", 0),
            )
            put("data", JSONObject().put("status", 2).put("text", textB64).put("encoding", "utf8"))
        }.toString()

        Log.i(TAG, ">>> voice=$voice fmt=$fmt")
        val ws = httpClient.newWebSocket(
            Request.Builder().url(wsUrl).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send(frame)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    runCatching {
                        val jo = JSONObject(text)
                        if (jo.optInt("code", 0) != 0) {
                            error = Exception("讯飞合成失败：${jo.optString("message", text.take(120))}")
                            webSocket.close(1000, null); return
                        }
                        val data = jo.optJSONObject("data")
                        val audio = data?.optString("audio", "") ?: ""
                        if (audio.isNotBlank()) out.write(Base64.decode(audio, Base64.DEFAULT))
                        if (data?.optInt("status", 0) == 2) {
                            webSocket.close(1000, null)
                        }
                    }.onFailure { e -> error = Exception("讯飞响应解析失败：${e.message}") }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { done.countDown() }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    error = Exception("讯飞连接失败：${t.message}"); done.countDown()
                }
            },
        )
        val ok = runCatching { done.await(120, TimeUnit.SECONDS) }.getOrDefault(false)
        if (error != null) throw error!!
        if (!ok) throw Exception("讯飞 TTS 超时")
        val result = out.toByteArray()
        if (result.isEmpty()) throw Exception("讯飞 TTS 返回音频为空")
        Pair(result, if (aue == "raw") "pcm16" else "mp3")
    }
}

// =====================================================================================
// 6) 腾讯云（REST + TC3-HMAC-SHA256）
// =====================================================================================
object TencentClient : QuroTtsClient {
    private const val TAG = "TtsTencent"
    override suspend fun synth(req: QuroTtsSynthRequest): Pair<ByteArray, String> = withContext(Dispatchers.IO) {
        val secretId = req.fields["secret_id"] ?: ""
        val secretKey = req.fields["secret_key"] ?: ""
        val voiceType = req.voice.ifBlank { "1001" }.toIntOrNull() ?: 1001
        val codec = if (req.format == "wav") "wav" else if (req.format == "pcm16") "pcm" else "mp3"
        val timestamp = (System.currentTimeMillis() / 1000).toString()
        val payload = JSONObject().apply {
            put("Action", "TextToVoice")
            put("Version", "2019-08-23")
            put("Text", req.text)
            put("SessionId", UUID.randomUUID().toString())
            put("VoiceType", voiceType)
            put("Codec", codec)
            put("ProjectId", 0)
        }.toString()
        val (authorization, _) = tencentSign(secretId, secretKey, payload, timestamp)
        val url = "https://tts.tencentcloudapi.com/"
        Log.i(TAG, ">>> voiceType=$voiceType codec=$codec")
        val r = Request.Builder().url(url)
            .addHeader("Authorization", authorization)
            .addHeader("Content-Type", "application/json; charset=utf-8")
            .addHeader("X-TC-Action", "TextToVoice")
            .addHeader("X-TC-Version", "2019-08-23")
            .addHeader("X-TC-Timestamp", timestamp)
            .addHeader("X-TC-Region", "ap-guangzhou")
            .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        val resp = httpClient.newCall(r).execute()
        val respText = resp.body?.string().orEmpty()
        if (!resp.isSuccessful) throw Exception("腾讯云合成失败 HTTP ${resp.code}：${respText.take(200)}")
        val root = JSONObject(respText)
        val errorObj = root.optJSONObject("Response")?.optJSONObject("Error")
        if (errorObj != null) throw Exception("腾讯云合成失败：${errorObj.optString("Message", respText.take(120))}")
        val audioB64 = root.optJSONObject("Response")?.optString("Audio", "") ?: ""
        if (audioB64.isBlank()) throw Exception("腾讯云合成返回为空")
        val fmt = if (codec == "wav") "wav" else if (codec == "pcm") "pcm16" else "mp3"
        Pair(Base64.decode(audioB64, Base64.DEFAULT), fmt)
    }

    private fun tencentSign(secretId: String, secretKey: String, payload: String, timestamp: String): Pair<String, String> {
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).also { it.timeZone = TimeZone.getTimeZone("GMT") }
            .format(java.util.Date(timestamp.toLong() * 1000))
        val hashedPayload = sha256Hex(payload)
        val canonicalHeaders = "content-type:application/json; charset=utf-8\nhost:tts.tencentcloudapi.com\n"
        val signedHeaders = "content-type;host"
        val canonicalRequest = "POST\n/\n\n$canonicalHeaders\n$signedHeaders\n$hashedPayload"
        val credentialScope = "$date/tts/tc3_request"
        val stringToSign = "TC3-HMAC-SHA256\n$timestamp\n$credentialScope\n" + sha256Hex(canonicalRequest)
        val secretDate = hmacSha256(secretKey, date)
        val secretService = hmacSha256(secretDate, "tts")
        val secretSigning = hmacSha256(secretService, "tc3_request")
        val signature = hmacSha256Hex(secretSigning, stringToSign)
        val authorization = "TC3-HMAC-SHA256 Credential=$secretId/$credentialScope, SignedHeaders=$signedHeaders, Signature=$signature"
        return Pair(authorization, date)
    }
}

// =====================================================================================
// 7) MiniMax t2a_v2（REST）
// =====================================================================================
object MiniMaxClient : QuroTtsClient {
    private const val TAG = "TtsMiniMax"
    override suspend fun synth(req: QuroTtsSynthRequest): Pair<ByteArray, String> = withContext(Dispatchers.IO) {
        val groupId = req.fields["group_id"] ?: ""
        val apiKey = req.fields["api_key"] ?: ""
        val model = req.model.ifBlank { "speech-01-turbo" }
        val voiceId = req.voice.ifBlank { "male-qn-qingse" }
        val fmt = req.format.ifBlank { "mp3" }
        val base = req.baseUrl.ifBlank { req.def.defaultBaseUrl }.trimEnd('/')
        val url = "$base/t2a_v2?GroupId=$groupId"
        val body = JSONObject().apply {
            put("model", model)
            put("text", req.text)
            put(
                "voice_setting",
                JSONObject().put("voice_id", voiceId).put("speed", req.speed.toDouble()).put("vol", 1.0).put("pitch", 0),
            )
            put(
                "audio_setting",
                JSONObject().put("sample_rate", 32000).put("bitrate", 128000).put("format", fmt).put("channel", 1),
            )
        }
        Log.i(TAG, ">>> voiceId=$voiceId fmt=$fmt")
        val r = Request.Builder().url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val resp = httpClient.newCall(r).execute()
        val respText = resp.body?.string().orEmpty()
        if (!resp.isSuccessful) throw Exception("MiniMax 合成失败 HTTP ${resp.code}：${respText.take(200)}")
        val root = JSONObject(respText)
        val status = root.optJSONObject("base_resp")?.optInt("status_code", -1) ?: -1
        if (status != 0) throw Exception("MiniMax 合成失败：${root.optJSONObject("base_resp")?.optString("message", respText.take(120))}")
        val audioB64 = root.optJSONObject("data")?.optString("audio", "") ?: ""
        if (audioB64.isBlank()) throw Exception("MiniMax 合成返回为空")
        Pair(Base64.decode(audioB64, Base64.DEFAULT), fmt)
    }
}

// ── 通用签名/哈希工具 ──
private fun sha256Hex(input: String): String {
    val md = MessageDigest.getInstance("SHA-256")
    val d = md.digest(input.toByteArray(Charsets.UTF_8))
    return d.joinToString("") { "%02x".format(it) }
}

private fun hmacSha256(key: String, data: String): ByteArray = hmacSha256(key.toByteArray(Charsets.UTF_8), data)
private fun hmacSha256(key: ByteArray, data: String): ByteArray {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key, "HmacSHA256"))
    return mac.doFinal(data.toByteArray(Charsets.UTF_8))
}

private fun hmacSha256Hex(key: ByteArray, data: String): String {
    return hmacSha256(key, data).joinToString("") { "%02x".format(it) }
}

private fun base64(data: ByteArray): String = Base64.encodeToString(data, Base64.NO_WRAP)
