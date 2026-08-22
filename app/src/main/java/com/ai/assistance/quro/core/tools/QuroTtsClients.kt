package com.ai.assistance.quro.core.tools

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
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
    val streaming: Boolean = false, // 增量流式播放开关（仅 Edge/讯飞 WS 在开启时生效）
)

interface QuroTtsClient {
    /** 合成并逐块回调音频。REST 客户端在合成完成后回调一次（完整字节）；WS 客户端（Edge/讯飞）在流式开启时逐消息回调。 */
    suspend fun synth(req: QuroTtsSynthRequest, onChunk: (ByteArray, String) -> Unit)
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
// 0) 复刻音色共享工具（MiMo / MiniMax / 硅基流动 共用）
// =====================================================================================
/** 会话内克隆 ID 缓存：避免重复注册（key = providerId:voiceName）。 */
private val cloneIdCache = mutableMapOf<String, String>()

/** 按 custom::<name> + type 匹配自定义音色。 */
private fun resolveCustom(req: QuroTtsSynthRequest, type: String): CloudCustomVoice? {
    val v = req.voice
    if (!v.startsWith("custom::")) return null
    val name = v.removePrefix("custom::")
    return req.customVoices.firstOrNull { it.name == name && it.type == type }
}

/** 读取复刻音频字节 + MIME。 */
private fun readCloneBytes(ctx: Context, cloneUri: String): Pair<ByteArray, String> {
    if (cloneUri.isBlank()) throw Exception("复刻音色缺少音频样本")
    val uri = Uri.parse(cloneUri)
    val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        ?: throw Exception("无法读取复刻音频样本")
    if (bytes.size > 10 * 1024 * 1024) throw Exception("复刻音频超过 10MB 限制")
    val mime = ctx.contentResolver.getType(uri) ?: "audio/mpeg"
    val safeMime = if (mime == "audio/mp3") "audio/mpeg" else mime
    return bytes to safeMime
}

/** 复刻音频 → data URI（MiMo 内联零样本用）。 */
private fun readCloneDataUri(ctx: Context, cloneUri: String): String {
    val (bytes, mime) = readCloneBytes(ctx, cloneUri)
    return "data:$mime;base64,${Base64.encodeToString(bytes, Base64.NO_WRAP)}"
}

/** 把注册得到的克隆音色 ID 持久化回自定义音色条目（避免每次重复注册）。 */
private fun persistRegisteredId(ctx: Context, providerId: String, name: String, registeredId: String) {
    runCatching {
        val cfg = QuroTtsProviderPrefs.getConfig(ctx, providerId)
        val updated = cfg.customVoices.map { if (it.name == name) it.copy(registeredId = registeredId) else it }
        QuroTtsProviderPrefs.saveConfig(ctx, providerId, cfg.copy(customVoices = updated))
    }
}

// =====================================================================================
// 1) OpenAI 兼容（OpenAI / 硅基流动 / TTS302 / CozeCn / Gizwits / ACGN / 阿里百炼CosyVoice）
// =====================================================================================
object OpenAiCompatClient : QuroTtsClient {
    private const val TAG = "TtsOpenAi"
    override suspend fun synth(req: QuroTtsSynthRequest, onChunk: (ByteArray, String) -> Unit) = withContext(Dispatchers.IO) {
        val apiKey = req.fields["api_key"] ?: ""
        val base = req.baseUrl.ifBlank { req.def.defaultBaseUrl }.trimEnd('/')
        val url = if (base.endsWith("/audio/speech")) base else "$base/audio/speech"
        val model = req.model.ifBlank { req.def.defaultModel.ifBlank { "tts-1" } }
        // 复刻音色：硅基流动走注册式（返回 speech: uri，强制非流式）；其余 cloneSupport 服务商需手动填 ID
        val (voice, forceNonStream) = resolveOpenAiVoice(req, apiKey, base, model)
        val fmt = req.format.ifBlank { "mp3" }
        // gpt-4o-mini-tts 等支持 instructions 注入风格；其余模型忽略
        val supportsInstructions = model.contains("4o", ignoreCase = true) || model.contains("mini-tts", ignoreCase = true)
        if (req.streaming && !forceNonStream) {
            openAiStream(req, apiKey, url, voice, model, supportsInstructions, onChunk)
            return@withContext
        }
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
        onChunk(raw, fmt)
    }

    private suspend fun openAiStream(
        req: QuroTtsSynthRequest, apiKey: String, url: String, voice: String, model: String,
        supportsInstructions: Boolean, onChunk: (ByteArray, String) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("model", model)
            put("input", req.text)
            put("voice", voice)
            put("response_format", "pcm") // 流式固定裸 pcm（24kHz）
            put("stream", true)
            put("stream_format", "pcm")   // 直接以裸音频字节分块返回（OpenAI / 硅基流动支持）
            if (supportsInstructions && req.styleNL.isNotBlank()) put("instructions", req.styleNL)
        }
        Log.i(TAG, ">>> stream model=$model voice=$voice")
        val r = Request.Builder().url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "application/octet-stream")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val resp = httpClient.newCall(r).execute()
        if (!resp.isSuccessful) {
            val msg = resp.body?.string().orEmpty()
            throw Exception("${req.def.name} 流式合成失败 HTTP ${resp.code}：${msg.take(200)}")
        }
        val source = resp.body?.source() ?: throw Exception("${req.def.name} 流式响应体为空")
        val tmp = ByteArray(8192)
        var gotAny = false
        while (!source.exhausted()) {
            val n = source.read(tmp, 0, tmp.size)
            if (n <= 0) break
            val chunk = if (n == tmp.size) tmp.copyOf() else tmp.copyOf(n)
            onChunk(chunk, "pcm16"); gotAny = true
        }
        if (!gotAny) throw Exception("${req.def.name} 流式未返回任何音频数据")
    }

    // ── 复刻音色解析（硅基流动注册式；其余 cloneSupport 服务商需手动填 ID） ──
    private suspend fun resolveOpenAiVoice(req: QuroTtsSynthRequest, apiKey: String, base: String, model: String): Pair<String, Boolean> {
        val raw = req.voice.ifBlank { "alloy" }
        if (!raw.startsWith("custom::")) return raw to false
        val cv = resolveCustom(req, "clone")
            ?: throw Exception("未找到自定义复刻音色「${raw.removePrefix("custom::")}」，请先在「自定义音色」中添加该克隆条目。")
        return when (req.def.id) {
            "siliconflow" -> {
                if (cv.registeredId.isNotBlank()) return cv.registeredId to true
                val cacheKey = "siliconflow:${cv.name}"
                cloneIdCache[cacheKey]?.let { return it to true }
                if (cv.cloneText.isBlank()) throw Exception("硅基流动复刻需先填写「参考音频旁白文本」（在克隆音色编辑中填写后保存）。")
                val uri = siliconflowRegisterVoice(req.ctx, base, apiKey, model, cv)
                persistRegisteredId(req.ctx, "siliconflow", cv.name, uri)
                cloneIdCache[cacheKey] = uri
                uri to true
            }
            else -> {
                if (cv.registeredId.isNotBlank()) return cv.registeredId to false
                throw Exception("「${req.def.name}」的语音克隆需先在官方平台创建克隆音色，再于「音色」字段填写克隆音色 ID；当前自定义复刻音色「${cv.name}」尚未注册。")
            }
        }
    }

    private suspend fun siliconflowRegisterVoice(ctx: Context, base: String, apiKey: String, model: String, cv: CloudCustomVoice): String {
        val url = "$base/uploads/audio/voice"
        val dataUri = readCloneDataUri(ctx, cv.cloneUri)
        val body = JSONObject().apply {
            put("model", model)
            put("customName", cv.name)
            put("audio", dataUri)
            put("text", cv.cloneText)
        }
        val r = Request.Builder().url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val resp = httpClient.newCall(r).execute()
        val txt = resp.body?.string().orEmpty()
        if (!resp.isSuccessful) throw Exception("硅基流动注册克隆音色失败 HTTP ${resp.code}：${txt.take(200)}")
        val jo = JSONObject(txt)
        val uri = jo.optJSONObject("data")?.optString("uri", "")?.ifBlank { jo.optString("uri", "") } ?: ""
        if (uri.isBlank()) throw Exception("硅基流动注册克隆音色未返回 uri：${txt.take(120)}")
        return uri
    }
}

// =====================================================================================
// 2) Edge TTS（免费，WebSocket + SSML express-as）
//    采用标准 edge-tts（Bing Speech 边缘端点）无密钥协议：Sec-MS-GEC 本地 SHA256 鉴权，
//    取代原先易失败的 Speech SDK avatar relay 令牌流程（旧流程 WSS 握手常返回 200 而非 101）。
// =====================================================================================
object EdgeTtsClient : QuroTtsClient {
    private const val TAG = "TtsEdge"
    private const val TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
    private const val WSS_HOST = "speech.platform.bing.com"
    private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

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

    override suspend fun synth(req: QuroTtsSynthRequest, onChunk: (ByteArray, String) -> Unit) = withContext(Dispatchers.IO) {
        val voice = req.voice.ifBlank { "zh-CN-XiaoxiaoNeural" }
        val style = req.styleTags.firstOrNull { it in STYLE_MAP }?.let { STYLE_MAP[it] }
        val ssml = buildSsml(req.text, voice, style, req.speed)
        Log.i(TAG, ">>> voice=$voice style=$style speed=${req.speed}")
        val gec = computeGec()
        connectWs(gec, ssml, req.streaming, onChunk)
    }

    /**
     * 计算 Sec-MS-GEC：SHA256(TrustedClientToken + GMT日期) 大写十六进制。
     * 这是 edge-tts 标准无密钥鉴权方式；日期必须与请求时刻一致（服务器按时间窗校验）。
     */
    private fun computeGec(): String {
        val date = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
            .also { it.timeZone = TimeZone.getTimeZone("GMT") }
            .format(java.util.Date())
        val input = "$TRUSTED_CLIENT_TOKEN$date"
        val sha = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return sha.joinToString("") { "%02X".format(it) }
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

    private fun connectWs(gec: String, ssml: String, streamWav: Boolean, onChunk: (ByteArray, String) -> Unit) {
        val connId = UUID.randomUUID().toString().uppercase()
        val wsUrl = "wss://$WSS_HOST/consumer/speech/synthesize/readaloud/edge/v1" +
            "?TrustedClientToken=$TRUSTED_CLIENT_TOKEN" +
            "&Sec-MS-GEC=$gec" +
            "&Sec-MS-GEC-Version=1" +
            "&ConnectionId=$connId"
        val out = ByteArrayOutputStream()
        val done = CountDownLatch(1)
        var error: Exception? = null
        val ws = httpClient.newWebSocket(
            Request.Builder().url(wsUrl)
                .addHeader("Origin", "https://$WSS_HOST")
                .addHeader("User-Agent", USER_AGENT)
                .build(),
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
                                ).put("outputFormat", if (streamWav) "riff-16khz-16bit-mono-pcm" else "audio-16khz-32kbitrate-mono-mp3"),
                            ),
                        ),
                    ).toString()
                    webSocket.send(config)
                    val ssmlBytes = ssml.toByteArray(Charsets.UTF_8)
                    // 边缘端点要求：2 字节大端长度前缀 + 0x00 0x01 0x00 0x00 + SSML
                    val frame = ByteArray(ssmlBytes.size + 6)
                    val len = ssmlBytes.size
                    frame[0] = ((len ushr 8) and 0xFF).toByte()
                    frame[1] = (len and 0xFF).toByte()
                    frame[2] = 0x00.toByte(); frame[3] = 0x01.toByte(); frame[4] = 0x00.toByte(); frame[5] = 0x00.toByte()
                    System.arraycopy(ssmlBytes, 0, frame, 6, len)
                    webSocket.send(ByteString.of(*frame))
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    val b = bytes.toByteArray()
                    if (b.size >= 2 && b[0] == 0x00.toByte() && b[1] == 0x02.toByte()) {
                        if (streamWav) onChunk(b.copyOfRange(2, b.size), "wav")
                        else out.write(b, 2, b.size - 2)
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (text.contains("\"Path\":\"turn.end\"") || text.contains("\"Path\":\"synthesis.complete\"")) {
                        webSocket.close(1000, null)
                    }
                    if (text.contains("\"error\"", ignoreCase = true)) {
                        error = Exception("Edge TTS 错误：${text.take(200)}")
                    }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (!streamWav) onChunk(out.toByteArray(), "mp3")
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
        if (!streamWav) {
            val result = out.toByteArray()
            if (result.isEmpty()) throw Exception("Edge TTS 返回音频为空")
        }
    }
}

// =====================================================================================
// 3) 小米 MiMo（/chat/completions + audio，支持 (风格) 分段 + 设计/复刻音色）
// =====================================================================================
object MimoClient : QuroTtsClient {
    private const val TAG = "TtsMimo"

    override suspend fun synth(req: QuroTtsSynthRequest, onChunk: (ByteArray, String) -> Unit) = withContext(Dispatchers.IO) {
        val apiKey = req.fields["api_key"] ?: ""
        val base = req.baseUrl.ifBlank { req.def.defaultBaseUrl }.trimEnd('/')
        val format = req.format.ifBlank { "wav" }
        val model = req.model.ifBlank { "mimo-v2.5-tts" }
        val style = req.styleNL
        // 流式：走 SSE 逐块返回 pcm16（24kHz），降低首字延迟；分段情绪标签在流式下不拆分。
        // ★ 零样本复刻（mimo-v2.5-tts-voiceclone）强制走非流式：mimoStream 写死预置音色会丢弃内联复刻音频，
        //   且官方 voiceclone 低延迟流式暂未上线（v308 修复「零样本没做对」的核心根因）。
        if (req.streaming && model != "mimo-v2.5-tts-voiceclone") {
            mimoStream(req, apiKey, base, model, style, onChunk)
            return@withContext
        }
        // 分段白名单：优先采用该服务商自有情绪/风格标签（providerTags），让 LLM 自由组合的标签在合成时不被剥离；
        // 若服务商无自有标签则回退到通用词库。
        val availableTags = req.def.providerTags.takeIf { it.isNotEmpty() } ?: QuroCloudTtsCatalog.EMOTION_TAGS
        val segs = QuroVoiceStyle.segment(req.text, availableTags)
        val synthOne: suspend (String, List<String>) -> Pair<ByteArray, String> = { text, tags ->
            val (b, isWav) = mimoSynthOne(req, apiKey, base, format, model, style, text, tags)
            b to (if (isWav) "wav" else "pcm16")
        }
        if (segs.isEmpty() || !QuroVoiceStyle.hasMarkers(req.text, availableTags)) {
            val whole = if (segs.isEmpty()) req.text else QuroVoiceStyle.strip(req.text)
            synthOne(whole, emptyList()).let { onChunk(it.first, it.second) }; return@withContext
        }
        val out = ByteArrayOutputStream()
        for (seg in segs) {
            if (seg.text.isBlank()) continue
            val (b, fmt) = synthOne(seg.text, seg.tags)
            if (fmt == "wav") out.write(QuroCloudTts.parseWav(b).first) else out.write(b)
        }
        if (out.size() == 0) { synthOne(QuroVoiceStyle.strip(req.text), emptyList()).let { onChunk(it.first, it.second) }; return@withContext }
        onChunk(out.toByteArray(), "pcm16")
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
                // 复刻固定请求 wav（非流式），避免 pcm16 在 voiceclone 下返回异常
                audioJson.put("format", "wav").put("voice", dataUri)
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

    private suspend fun mimoStream(
        req: QuroTtsSynthRequest, apiKey: String, base: String, model: String,
        style: String, onChunk: (ByteArray, String) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val audioJson = JSONObject().apply {
            put("format", "pcm16") // 流式固定 24kHz pcm16，复用 StreamingPcmPlayer
            put("voice", resolvePresetVoice(req))
        }
        val userContent = style
        // 流式同样要保留情绪标签：按 MiMo 标记格式重建（不 strip），否则所有 (标签) 被剥光、无情绪
        val availableTags = req.def.providerTags.takeIf { it.isNotEmpty() } ?: QuroCloudTtsCatalog.EMOTION_TAGS
        val assistantContent = QuroVoiceStyle.toMimoMarkup(req.text, availableTags)
        val messages = JSONArray().apply {
            put(JSONObject().put("role", "user").put("content", userContent))
            put(JSONObject().put("role", "assistant").put("content", assistantContent))
        }
        val body = JSONObject().apply {
            put("model", model); put("stream", true); put("messages", messages); put("audio", audioJson)
        }
        val url = if (base.endsWith("/chat/completions")) base else "$base/chat/completions"
        Log.i(TAG, ">>> stream model=$model len=${req.text.length}")
        val r = Request.Builder().url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "text/event-stream")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val resp = httpClient.newCall(r).execute()
        if (!resp.isSuccessful) {
            val msg = resp.body?.string().orEmpty()
            throw Exception("小米流式合成失败 HTTP ${resp.code}：${msg.take(200)}")
        }
        val source = resp.body?.source() ?: throw Exception("小米流式响应体为空")
        var gotAny = false
        while (!source.exhausted()) {
            val line = source.readUtf8Line() ?: break
            val payload = line.removePrefix("data:").trim()
            if (payload.isEmpty() || payload == "[DONE]") continue
            try {
                val obj = JSONObject(payload)
                val audio = obj.optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("delta")
                    ?.optJSONObject("audio")
                val b64 = audio?.optString("data", "") ?: ""
                if (b64.isNotBlank()) {
                    val bytes = Base64.decode(b64, Base64.DEFAULT)
                    if (bytes.isNotEmpty()) { onChunk(bytes, "pcm16"); gotAny = true }
                }
            } catch (e: Exception) {
                Log.w(TAG, "跳过无法解析的 SSE 行: ${payload.take(80)}")
            }
        }
        if (!gotAny) throw Exception("小米流式未返回任何音频数据")
    }

    private fun resolvePresetVoice(req: QuroTtsSynthRequest): String {
        val v = req.voice
        return if (v.startsWith("custom::")) "mimo_default" else v.ifBlank { "mimo_default" }
    }
}

// =====================================================================================
// 4) 火山引擎 豆包 / 灵犀（REST）
// =====================================================================================
object VolcengineClient : QuroTtsClient {
    private const val TAG = "TtsVolc"
    override suspend fun synth(req: QuroTtsSynthRequest, onChunk: (ByteArray, String) -> Unit) = withContext(Dispatchers.IO) {
        if (req.streaming) { volcStream(req, onChunk); return@withContext }
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
        onChunk(Base64.decode(b64, Base64.DEFAULT), format)
    }

    // ---- v304：火山 WebSocket 增量流式（二进制 TLV 协议，wss://openspeech.bytedance.com/api/v1/tts/ws/v2）----
    private suspend fun volcStream(req: QuroTtsSynthRequest, onChunk: (ByteArray, String) -> Unit) = withContext(Dispatchers.IO) {
        val token = req.fields["token"] ?: ""
        val appId = req.fields["app_id"] ?: ""
        if (token.isBlank() || appId.isBlank()) throw Exception("火山流式缺少 token 或 app_id，回退 REST")
        val cluster = req.fields["cluster"] ?: "volcabcluster"
        val voiceType = req.voice.ifBlank { "zh_female_qingxin" }
        val wsUrl = "wss://openspeech.bytedance.com/api/v1/tts/ws/v2"
        val requestId = UUID.randomUUID().toString()
        Log.i(TAG, ">>> volcStream 开始 requestId=$requestId")
        val headers = mapOf(
            "X-Api-App-Key" to appId,
            "X-Api-Access-Key" to token,
            "X-Api-Resource-Id" to "volatile_tts_post",
            "X-Api-Request-Id" to requestId,
        )
        val reqBody = JSONObject().apply {
            put("app", JSONObject().put("appid", appId).put("token", token).put("cluster", cluster))
            put("user", JSONObject().put("uid", "quro_user"))
            put("audio", JSONObject().put("voice_type", voiceType).put("encoding", "pcm").put("sample_rate", 24000).put("speed_ratio", req.speed.toDouble()))
            put("request", JSONObject().put("reqid", requestId).put("text", req.text).put("operation", "submit"))
        }.toString()
        val done = CountDownLatch(1)
        var error: Exception? = null
        var gotAny = false
        var wsRef: WebSocket? = null
        try {
            wsRef = httpClient.newWebSocket(
                Request.Builder().url(wsUrl).also { h -> headers.forEach { (k, v) -> h.addHeader(k, v) } }.build(),
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        Log.d(TAG, "volc WS onOpen")
                        runCatching { webSocket.send(buildVolcFrame(reqBody)) }.onFailure { e ->
                            error = Exception("火山发送请求失败：${e.message}"); done.countDown()
                        }
                    }
                    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                        runCatching {
                            val (msgType, payload) = parseVolcFrame(bytes.toByteArray())
                            when (msgType) {
                                0x0b -> { // JSON 响应（状态/错误）
                                    val jo = runCatching { JSONObject(payload.toString(Charsets.UTF_8)) }.getOrNull()
                                    val code = jo?.optInt("code", 3000) ?: 3000
                                    if (code != 3000) {
                                        error = Exception("火山流式失败：code=$code ${jo?.optString("message", "") ?: ""}")
                                        webSocket.close(1000, null)
                                    }
                                }
                                0x0f, 0x0e, 0x0d -> { // audio only / audio+frontend
                                    if (payload.isNotEmpty()) { onChunk(payload, "pcm16"); gotAny = true }
                                }
                                else -> { /* 0x0c frontend-only 等忽略 */ }
                            }
                        }.onFailure { e ->
                            error = Exception("火山帧解析失败：${e.message}"); done.countDown()
                        }
                    }
                    override fun onMessage(webSocket: WebSocket, text: String) { /* 不应出现文本帧，忽略 */ }
                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        Log.d(TAG, "volc WS onClosed")
                        done.countDown()
                    }
                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        Log.w(TAG, "volc WS onFailure: ${t.message}")
                        error = Exception("火山 WS 连接失败：${t.message}"); done.countDown()
                    }
                },
            )
            val ok = runCatching { done.await(30, TimeUnit.SECONDS) }.getOrDefault(false)
            if (error != null) throw error!!
            if (!ok) throw Exception("火山 WS TTS 超时(30s)")
            if (!gotAny) throw Exception("火山流式未返回任何音频数据")
        } catch (e: Exception) {
            // 确保 WS 关闭，避免泄漏
            runCatching { wsRef?.cancel() }
            throw e
        }
    }

    private fun buildVolcFrame(json: String): ByteString {
        val payload = json.toByteArray(Charsets.UTF_8)
        val frame = ByteArray(12 + payload.size)
        frame[0] = 0x11.toByte()   // protocol version 1, header size 1
        frame[1] = 0x0b.toByte()   // message_type: 全量 JSON 请求
        frame[2] = 0x00.toByte()   // message_type_specific_flags
        frame[3] = 0x10.toByte()   // serialization: JSON(0x1) + compression: none(0x0)
        // bytes[4..7] reserved = 0
        val size = payload.size
        frame[8] = ((size shr 24) and 0xFF).toByte()
        frame[9] = ((size shr 16) and 0xFF).toByte()
        frame[10] = ((size shr 8) and 0xFF).toByte()
        frame[11] = (size and 0xFF).toByte()
        System.arraycopy(payload, 0, frame, 12, payload.size)
        return ByteString.of(*frame)
    }

    private fun parseVolcFrame(bytes: ByteArray): Pair<Int, ByteArray> {
        if (bytes.size < 12) throw Exception("火山帧过短（${bytes.size}）")
        val msgType = bytes[1].toInt() and 0xFF
        val payloadSize = ((bytes[8].toInt() and 0xFF) shl 24) or
            ((bytes[9].toInt() and 0xFF) shl 16) or
            ((bytes[10].toInt() and 0xFF) shl 8) or
            (bytes[11].toInt() and 0xFF)
        val end = minOf(12 + payloadSize, bytes.size)
        return Pair(msgType, bytes.copyOfRange(12, end))
    }
}

// =====================================================================================
// 5) 科大讯飞（WebSocket + HMAC-SHA256）
// =====================================================================================
object IflytekClient : QuroTtsClient {
    private const val TAG = "TtsIflytek"
    override suspend fun synth(req: QuroTtsSynthRequest, onChunk: (ByteArray, String) -> Unit) = withContext(Dispatchers.IO) {
        val appId = req.fields["app_id"] ?: ""
        val apiKey = req.fields["api_key"] ?: ""
        val apiSecret = req.fields["api_secret"] ?: ""
        val voice = req.voice.ifBlank { "xiaoyan" }
        val fmt = req.format.ifBlank { "mp3" }
        val aue = if (req.streaming) "raw" else "lame" // raw → pcm16(流式), lame → mp3
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
                        if (audio.isNotBlank()) {
                            val b = Base64.decode(audio, Base64.DEFAULT)
                            if (req.streaming) onChunk(b, if (aue == "raw") "pcm16" else "mp3") else out.write(b)
                        }
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
        if (!req.streaming) {
            val result = out.toByteArray()
            if (result.isEmpty()) throw Exception("讯飞 TTS 返回音频为空")
            onChunk(result, if (aue == "raw") "pcm16" else "mp3")
        }
    }
}

// =====================================================================================
// 6) 腾讯云（REST + TC3-HMAC-SHA256）
// =====================================================================================
object TencentClient : QuroTtsClient {
    private const val TAG = "TtsTencent"
    override suspend fun synth(req: QuroTtsSynthRequest, onChunk: (ByteArray, String) -> Unit) = withContext(Dispatchers.IO) {
        if (req.streaming) { tencentStream(req, onChunk); return@withContext }
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
        onChunk(Base64.decode(audioB64, Base64.DEFAULT), fmt)
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

    // ---- v304：腾讯云 WebSocket 增量流式（wss://tts.cloud.tencent.com/stream_wsv2，TC3 签名置于 query）----
    private fun tencentWsUrl(secretId: String, secretKey: String, appId: String, timestamp: String, expired: String): String {
        val action = "TextToStreamAudioWSv2"
        val version = "2019-08-23"
        val host = "tts.cloud.tencent.com"
        val rawParams = sortedMapOf(
            "Action" to action,
            "AppId" to appId,
            "Expired" to expired,
            "SecretId" to secretId,
            "Timestamp" to timestamp,
            "Version" to version,
        )
        val canonicalQuery = rawParams.map { "${it.key}=${it.value}" }.joinToString("&")
        val hashedPayload = sha256Hex("")
        val canonicalHeaders = "content-type:application/json; charset=utf-8\nhost:$host\n"
        val signedHeaders = "content-type;host"
        val canonicalRequest = "GET\n/stream_wsv2\n$canonicalQuery\n$canonicalHeaders\n$signedHeaders\n$hashedPayload"
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).also { it.timeZone = TimeZone.getTimeZone("GMT") }
            .format(java.util.Date(timestamp.toLong() * 1000))
        val credentialScope = "$date/tts/tc3_request"
        val stringToSign = "TC3-HMAC-SHA256\n$timestamp\n$credentialScope\n" + sha256Hex(canonicalRequest)
        val secretDate = hmacSha256(secretKey, date)
        val secretService = hmacSha256(secretDate, "tts")
        val secretSigning = hmacSha256(secretService, "tc3_request")
        val signature = hmacSha256Hex(secretSigning, stringToSign)
        val finalQuery = "$canonicalQuery&Signature=${URLEncoder.encode(signature, "UTF-8")}"
        return "wss://$host/stream_wsv2?$finalQuery"
    }

    private suspend fun tencentStream(req: QuroTtsSynthRequest, onChunk: (ByteArray, String) -> Unit) = withContext(Dispatchers.IO) {
        val secretId = req.fields["secret_id"] ?: ""
        val secretKey = req.fields["secret_key"] ?: ""
        if (secretId.isBlank() || secretKey.isBlank()) throw Exception("腾讯流式缺少 secret_id 或 secret_key，回退 REST")
        val voiceType = req.voice.ifBlank { "1001" }.toIntOrNull() ?: 1001
        val appId = req.fields["app_id"] ?: ""
        val timestamp = (System.currentTimeMillis() / 1000).toString()
        val expired = ((System.currentTimeMillis() / 1000) + 24 * 3600).toString()
        Log.i(TAG, ">>> tencentStream 开始 appId=$appId")
        val wsUrl = tencentWsUrl(secretId, secretKey, appId, timestamp, expired)
        val firstFrame = JSONObject().apply {
            put("Action", "TextToStreamAudioWSv2")
            put("AppId", appId.toLongOrNull() ?: 0L)
            put("SessionId", UUID.randomUUID().toString())
            put("Codec", "pcm")
            put("VoiceType", voiceType)
            put("Speed", 0)
            put("Text", req.text)
            put("Volume", 0)
            put("EnableSubtitle", false)
        }.toString()
        val done = CountDownLatch(1)
        var error: Exception? = null
        var gotAny = false
        var wsRef: WebSocket? = null
        try {
            wsRef = httpClient.newWebSocket(
                Request.Builder().url(wsUrl).build(),
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        Log.d(TAG, "tencent WS onOpen")
                        runCatching { webSocket.send(firstFrame) }.onFailure { e ->
                            error = Exception("腾讯发送请求失败：${e.message}"); done.countDown()
                        }
                    }
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        runCatching {
                            val jo = JSONObject(text)
                            val code = jo.optInt("code", 0)
                            if (code != 0) {
                                error = Exception("腾讯流式失败：${jo.optString("message", text.take(120))}")
                                webSocket.close(1000, null); return@runCatching
                            }
                            val audio = jo.optString("audio", "")
                            if (audio.isNotBlank()) {
                                val b = Base64.decode(audio, Base64.DEFAULT)
                                if (b.isNotEmpty()) { onChunk(b, "pcm16"); gotAny = true }
                            }
                            if (jo.optBoolean("is_final", false)) webSocket.close(1000, null)
                        }.onFailure { e ->
                            error = Exception("腾讯响应解析失败：${e.message}"); done.countDown()
                        }
                    }
                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        Log.d(TAG, "tencent WS onClosed")
                        done.countDown()
                    }
                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        Log.w(TAG, "tencent WS onFailure: ${t.message}")
                        error = Exception("腾讯 WS 连接失败：${t.message}"); done.countDown()
                    }
                },
            )
            val ok = runCatching { done.await(30, TimeUnit.SECONDS) }.getOrDefault(false)
            if (error != null) throw error!!
            if (!ok) throw Exception("腾讯 WS TTS 超时(30s)")
            if (!gotAny) throw Exception("腾讯流式未返回任何音频数据")
        } catch (e: Exception) {
            runCatching { wsRef?.cancel() }
            throw e
        }
    }
}

// =====================================================================================
// 7) MiniMax t2a_v2（REST）
// =====================================================================================
object MiniMaxClient : QuroTtsClient {
    private const val TAG = "TtsMiniMax"
    override suspend fun synth(req: QuroTtsSynthRequest, onChunk: (ByteArray, String) -> Unit) = withContext(Dispatchers.IO) {
        val groupId = req.fields["group_id"] ?: ""
        val apiKey = req.fields["api_key"] ?: ""
        val model = req.model.ifBlank { "speech-01-turbo" }
        val base = req.baseUrl.ifBlank { req.def.defaultBaseUrl }.trimEnd('/')
        // 注册式复刻：custom:: + clone 时先上传音频→创建克隆音色，回填 voice_id
        val cloneVoiceId = resolveMiniMaxClone(req, apiKey, base, model)
        if (cloneVoiceId == null && req.voice.startsWith("custom::")) {
            throw Exception("未找到自定义复刻音色「${req.voice.removePrefix("custom::")}」，请先在「自定义音色」中添加该克隆条目。")
        }
        val voiceId = cloneVoiceId ?: req.voice.ifBlank { "male-qn-qingse" }
        val fmt = req.format.ifBlank { "mp3" }
        if (req.streaming) {
            miniMaxStream(req, groupId, apiKey, base, model, voiceId, onChunk)
            return@withContext
        }
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
        onChunk(Base64.decode(audioB64, Base64.DEFAULT), fmt)
    }

    private suspend fun miniMaxStream(
        req: QuroTtsSynthRequest, groupId: String, apiKey: String, base: String,
        model: String, voiceId: String, onChunk: (ByteArray, String) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val url = "$base/t2a_v2?GroupId=$groupId"
        val body = JSONObject().apply {
            put("model", model)
            put("text", req.text)
            put("stream", true)
            put(
                "voice_setting",
                JSONObject().put("voice_id", voiceId).put("speed", req.speed.toDouble()).put("vol", 1.0).put("pitch", 0),
            )
            // 流式固定 24kHz 裸 pcm，与 StreamingPcmPlayer 一致
            put(
                "audio_setting",
                JSONObject().put("sample_rate", 24000).put("bitrate", 128000).put("format", "pcm").put("channel", 1),
            )
        }
        Log.i(TAG, ">>> stream voiceId=$voiceId")
        val r = Request.Builder().url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val resp = httpClient.newCall(r).execute()
        if (!resp.isSuccessful) {
            val msg = resp.body?.string().orEmpty()
            throw Exception("MiniMax 流式合成失败 HTTP ${resp.code}：${msg.take(200)}")
        }
        val source = resp.body?.source() ?: throw Exception("MiniMax 流式响应体为空")
        var gotAny = false
        while (!source.exhausted()) {
            val line = source.readUtf8Line() ?: break
            val payload = line.removePrefix("data:").trim()
            if (payload.isEmpty()) continue
            try {
                val obj = JSONObject(payload)
                val audio = obj.optJSONObject("data")?.optString("audio", "") ?: ""
                if (audio.isNotBlank()) {
                    val bytes = Base64.decode(audio, Base64.DEFAULT)
                    if (bytes.isNotEmpty()) { onChunk(bytes, "pcm16"); gotAny = true }
                }
                if (obj.optJSONObject("data")?.optInt("status", 0) == 2) break
            } catch (e: Exception) {
                Log.w(TAG, "跳过无法解析的流式行: ${payload.take(80)}")
            }
        }
        if (!gotAny) throw Exception("MiniMax 流式未返回任何音频数据")
    }

    // ── 注册式语音复刻（两步：files/upload → voice_clone） ──
    private suspend fun resolveMiniMaxClone(req: QuroTtsSynthRequest, apiKey: String, base: String, model: String): String? {
        if (!req.voice.startsWith("custom::")) return null
        val cv = resolveCustom(req, "clone") ?: return null
        if (cv.registeredId.isNotBlank()) return cv.registeredId
        val cacheKey = "minimax:${cv.name}"
        cloneIdCache[cacheKey]?.let { return it }
        val (bytes, mime) = readCloneBytes(req.ctx, cv.cloneUri)
        val fileId = miniMaxUploadFile(base, apiKey, bytes, mime, cv.name)
        val vid = miniMaxCreateVoice(base, apiKey, fileId, cv.name, model)
        persistRegisteredId(req.ctx, "minimax", cv.name, vid)
        cloneIdCache[cacheKey] = vid
        return vid
    }

    private suspend fun miniMaxUploadFile(base: String, apiKey: String, bytes: ByteArray, mime: String, name: String): String {
        val url = "$base/files/upload"
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("purpose", "voice_clone")
            .addFormDataPart("file", "${name}.wav", bytes.toRequestBody(mime.toMediaType()))
            .build()
        val r = Request.Builder().url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .post(body)
            .build()
        val resp = httpClient.newCall(r).execute()
        val txt = resp.body?.string().orEmpty()
        if (!resp.isSuccessful) throw Exception("MiniMax 上传音频失败 HTTP ${resp.code}：${txt.take(200)}")
        val jo = JSONObject(txt)
        val st = jo.optJSONObject("base_resp")?.optInt("status_code", -1) ?: -1
        if (st != 0) throw Exception("MiniMax 上传音频失败：${jo.optJSONObject("base_resp")?.optString("message", txt.take(120))}")
        val fileId = jo.optString("file_id").ifBlank { jo.optJSONObject("file")?.optString("file_id", "") ?: "" }
        if (fileId.isBlank()) throw Exception("MiniMax 上传未返回 file_id")
        return fileId
    }

    private suspend fun miniMaxCreateVoice(base: String, apiKey: String, fileId: String, name: String, model: String): String {
        val url = "$base/voice_clone"
        val vid = sanitizeVoiceId(name)
        val body = JSONObject().apply {
            put("file_id", fileId)
            put("voice_id", vid)
            put("model", model)
            if (name.isNotBlank()) put("text", name)
        }
        val r = Request.Builder().url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val resp = httpClient.newCall(r).execute()
        val txt = resp.body?.string().orEmpty()
        if (!resp.isSuccessful) throw Exception("MiniMax 创建克隆音色失败 HTTP ${resp.code}：${txt.take(200)}")
        val jo = JSONObject(txt)
        val st = jo.optJSONObject("base_resp")?.optInt("status_code", -1) ?: -1
        if (st != 0) throw Exception("MiniMax 创建克隆音色失败：${jo.optJSONObject("base_resp")?.optString("message", txt.take(120))}")
        return jo.optString("voice_id").ifBlank { vid }
    }

    private fun sanitizeVoiceId(name: String): String {
        val cleaned = name.replace(Regex("[^A-Za-z0-9_]"), "_")
        val head = if (cleaned.firstOrNull()?.isLetter() == true) cleaned else "v$cleaned"
        val base = "quro_clone_$head"
        return if (base.length < 8) base.padEnd(8, 'x') else base.take(256)
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
