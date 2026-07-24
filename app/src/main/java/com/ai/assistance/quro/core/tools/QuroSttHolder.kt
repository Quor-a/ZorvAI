package com.ai.assistance.quro.core.tools

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * QuroAI STT 语音识别工具（Phase 1，v41）。
 *
 * 镜像 QuroTtsHolder 的「注入式日志」模式：Holder 不持有内存日志列表，
 * 只通过 setLogCallback 把日志推给调用方（设置页的 sttLogs 状态），由 UI 负责展示。
 *
 * Phase 1 行为：
 *  - startListening() 封装原生 SpeechRecognizer，保证开箱即用（默认引擎）。
 *  - 仅支持「本地识别」引擎；若用户选择了「AI 模型」但 Phase 2 未落地，
 *    语音球会回退到原生识别并在日志中提示。
 *  - transcribe() 为 Phase 2 占位（音频采集→multipart POST 到 /audio/transcriptions），
 *    本阶段不实现，避免引入未完成的音频上传链路。
 */
object QuroSttHolder {
    private const val TAG = "QuroStt"

    // ── 日志回调（注入式） ──
    private var logCallback: ((String) -> Unit)? = null
    fun setLogCallback(callback: ((String) -> Unit)?) { logCallback = callback }
    private fun log(msg: String) { Log.d(TAG, msg); logCallback?.invoke(msg) }

    /** 供外部（如语音球）向同一条日志流推送提示。 */
    fun pushLog(msg: String) = log(msg)

    // ── 原生识别器实例 ──
    @Volatile private var recognizer: SpeechRecognizer? = null
    @Volatile private var listening = false

    /**
     * 支持音频转写（OpenAI /audio/transcriptions 兼容）的厂商子集。
     * 用于「拉取模型」下拉里的「🎙 支持语音转写」徽标。
     * 注：这是依据各厂商 API 形态做的启发式判断（多为 OpenAI 兼容网关），
     * 不保证每个模型都含 whisper/语音端点；Phase 2 落地时以真实接口为准。
     */
    val STT_AUDIO_CAPABLE_PROVIDERS: Set<String> = setOf(
        "OPENAI",
        "OPENAI_GENERIC",
        "OPENAI_LOCAL",
        "OPENAI_RESPONSES",
        "OPENAI_RESPONSES_GENERIC",
        "SILICONFLOW",
        "OPENROUTER",
        "DEEPSEEK",
        "ALIYUN",
        "ZHIPU",
        "BAICHUAN",
        "MOONSHOT",
        "DOUBAO",
        "MISTRAL",
        "NOVITA",
        "PPINFRA",
        "INFINIAI",
        "IFLOW",
        "FOUR_ROUTER",
        "NOUS_PORTAL",
        "OLLAMA",
        "LMSTUDIO",
    )

    /** 给定 provider（枚举名或自定义名）是否支持音频转写。 */
    fun providerSupportsAudio(p: String?): Boolean {
        if (p.isNullOrBlank()) return false
        val norm = p.trim().uppercase()
        if (norm in STT_AUDIO_CAPABLE_PROVIDERS) return true
        // 兼容自定义端点命名：含 openai / whisper / silicon / ollama / lmstudio 关键字也视为支持
        return norm.contains("OPENAI") || norm.contains("WHISPER") ||
               norm.contains("SILICON") || norm.contains("OLLAMA") || norm.contains("LMSTUDIO")
    }

    fun isListening(): Boolean = listening

    /**
     * 封装原生 SpeechRecognizer，开始一次识别。
     *
     * @param context        调用方上下文（Activity/Service 均可，必须在主线程）
     * @param language       BCP-47 语言标签，如 zh-CN
     * @param partialResults 是否回传部分结果
     * @param onPartial      部分结果回调（实时回显）
     * @param onFinal        最终结果回调（返回识别文本；空串表示未识别到）
     * @param onError        错误回调（code=SpeechRecognizer.ERROR_*，msg=中文说明）
     */
    fun startListening(
        context: Context,
        language: String,
        partialResults: Boolean,
        onPartial: (String) -> Unit,
        onFinal: (String) -> Unit,
        onError: (Int, String) -> Unit,
    ) {
        try {
            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                val m = "设备不支持语音识别 ❌"
                log(m); onError(SpeechRecognizer.ERROR_CLIENT, m); return
            }
            // 先停掉上一次，避免 RECOGNIZER_BUSY
            stopListening()

            log("startListening: lang=$language partial=$partialResults")
            val ctx = context.applicationContext
            val rec = SpeechRecognizer.createSpeechRecognizer(ctx)
            recognizer = rec
            listening = true
            rec.setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle?) {
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull().orEmpty()
                    listening = false
                    log("onResults: \"${text.take(40)}\"")
                    onFinal(text)
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val t = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull().orEmpty()
                    if (t.isNotBlank()) { log("onPartial: \"${t.take(40)}\""); onPartial(t) }
                }

                override fun onError(error: Int) {
                    listening = false
                    val msg = errorText(error)
                    log("onError($error): $msg")
                    onError(error, msg)
                }

                override fun onReadyForSpeech(params: Bundle?) { log("onReadyForSpeech") }
                override fun onBeginningOfSpeech() { log("onBeginningOfSpeech") }
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() { log("onEndOfSpeech") }
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, partialResults)
            }
            try {
                rec.startListening(intent)
                log("startListening 已调用 ✅")
            } catch (e: Exception) {
                listening = false
                val m = "无法启动识别：${e.message}"
                log("❌ $m"); onError(SpeechRecognizer.ERROR_CLIENT, m)
            }
        } catch (e: Throwable) {
            listening = false
            val m = "无法启动识别：${e.message}"
            log("❌ $m"); onError(SpeechRecognizer.ERROR_CLIENT, m)
        }
    }

    /** 停止并解绑当前识别。DisposableEffect 退出或页面销毁时调用。 */
    fun stopListening() {
        listening = false
        try { recognizer?.stopListening() } catch (_: Throwable) {}
        try { recognizer?.destroy() } catch (_: Throwable) {}
        recognizer = null
        log("stopListening")
    }

    /** 中文错误映射。 */
    private fun errorText(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "音频录制错误"
        SpeechRecognizer.ERROR_CLIENT -> "客户端错误"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "录音权限不足"
        SpeechRecognizer.ERROR_NETWORK -> "网络错误"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "网络超时"
        SpeechRecognizer.ERROR_NO_MATCH -> "未匹配到结果"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "识别器忙"
        SpeechRecognizer.ERROR_SERVER -> "服务器错误"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "未检测到语音"
        else -> "未知错误($error)"
    }

    /**
     * Phase 2：用所选模型的 /audio/transcriptions 转写音频（OpenAI 兼容）。
     * 不依赖原生 SpeechRecognizer，适合无原生识别的手机。
     * 仅用 OkHttp + org.json，与 QuroLlmClient 同栈。
     */
    fun transcribe(
        ctx: Context,
        audioFile: File,
        baseUrl: String,
        apiKey: String,
        model: String,
        language: String = "zh",
        onFinal: (String) -> Unit,
        onError: (Int, String) -> Unit,
    ) {
        // 模式分支：开启 chat/completions 时走多模态消息，否则走标准 /audio/transcriptions
        if (QuroSttPrefs.getUseChatCompletions(ctx)) {
            transcribeViaChatCompletions(ctx, audioFile, baseUrl, apiKey, model, language, onFinal, onError)
            return
        }
        val provider = QuroSttPrefs.getModelProvider(ctx)
        if (!providerSupportsAudio(provider)) {
            log("ℹ️ provider($provider) 不在已知音频转写白名单，仍尝试请求（OpenAI 兼容端点通常可用）")
        }
        if (apiKey.isBlank()) {
            val m = "未配置 API Key，无法调用云端转写"
            log("⚠️ $m"); onError(-1, m); return
        }
        val normalized = baseUrl.trim().trimEnd('/')
        val url = if (normalized.endsWith("/audio/transcriptions")) normalized
                  else "$normalized/audio/transcriptions"
        log("transcribe → $url model=$model lang=$language")
        try {
            val client = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .build()
            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart(
                    "file", audioFile.name,
                    RequestBody.create("audio/wav".toMediaType(), audioFile)
                )
                .addFormDataPart("model", model)
                .addFormDataPart("language", language)
                .addFormDataPart("response_format", "json")
                .build()
            val req = Request.Builder().url(url)
                .addHeader("Authorization", "Bearer $apiKey")
                .post(body)
                .build()
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                log("transcribe ← HTTP ${resp.code} len=${text.length}")
                if (!resp.isSuccessful) { onError(resp.code, "HTTP ${resp.code}: ${text.take(200)}"); return@use }
                val result = runCatching { JSONObject(text).optString("text", "").trim() }.getOrDefault("")
                if (result.isBlank()) { onError(-2, "转写结果为空"); return@use }
                onFinal(result)
            }
        } catch (e: Throwable) {
            val m = "转写请求失败：${e.message}"
            log("❌ $m"); onError(-3, m)
        }
    }

    /**
     * chat/completions 模式：把音频 base64 作为 input_audio 多模态消息发给模型。
     * 适用场景：端点无 /audio/transcriptions（如 MIMO 返回 404），但支持在 chat 消息里带音频。
     */
    private fun transcribeViaChatCompletions(
        ctx: Context,
        audioFile: File,
        baseUrl: String,
        apiKey: String,
        model: String,
        language: String,
        onFinal: (String) -> Unit,
        onError: (Int, String) -> Unit,
    ) {
        if (apiKey.isBlank()) {
            val m = "未配置 API Key，无法调用云端转写"
            log("⚠️ $m"); onError(-1, m); return
        }
        val b64 = runCatching {
            val bytes = FileInputStream(audioFile).use { it.readBytes() }
            Base64.getEncoder().encodeToString(bytes)
        }.getOrElse { e ->
            val m = "读取音频失败：${e.message}"
            log("❌ $m"); onError(-4, m); return
        }
        if (b64.isBlank()) { onError(-4, "音频为空"); return }

        val normalized = baseUrl.trim().trimEnd('/')
        val url = if (normalized.endsWith("/chat/completions")) normalized
                  else "$normalized/chat/completions"
        val sysPrompt = if (language.startsWith("zh")) {
            "你是一个语音转文字助手。请将用户提供的音频准确转录为文字，只输出转录内容，不要添加任何解释、前缀或多余标点。"
        } else {
            "You are a speech-to-text assistant. Transcribe the user's audio into text. Output only the transcript, with no extra commentary."
        }
        val json = JSONObject().apply {
            put("model", model)
            put("temperature", 0)
            put("messages", org.json.JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", sysPrompt)
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", org.json.JSONArray().apply {
                        put(JSONObject().apply {
                            put("type", "input_audio")
                            put("input_audio", JSONObject().apply {
                                put("data", b64)
                                put("format", "wav")
                            })
                        })
                    })
                })
            })
        }.toString()

        log("transcribe(chat) → $url model=$model lang=$language")
        try {
            val client = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .build()
            val body = RequestBody.create("application/json".toMediaType(), json)
            val req = Request.Builder().url(url)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(body)
                .build()
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                log("transcribe(chat) ← HTTP ${resp.code} len=${text.length}")
                if (!resp.isSuccessful) { onError(resp.code, "HTTP ${resp.code}: ${text.take(200)}"); return@use }
                val result = runCatching {
                    val root = JSONObject(text)
                    // OpenAI 兼容：choices[0].message.content；部分实现用 choices[0].text
                    val choices = root.optJSONArray("choices")
                    var out = ""
                    if (choices != null && choices.length() > 0) {
                        val c = choices.optJSONObject(0) ?: JSONObject()
                        val msg = c.optJSONObject("message")
                        out = msg?.optString("content", "")?.trim().orEmpty()
                        if (out.isBlank()) out = c.optString("text", "").trim()
                    }
                    out
                }.getOrDefault("")
                if (result.isBlank()) { onError(-2, "转写结果为空"); return@use }
                onFinal(result)
            }
        } catch (e: Throwable) {
            val m = "转写请求失败：${e.message}"
            log("❌ $m"); onError(-3, m)
        }
    }
}
