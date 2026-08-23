package com.ai.assistance.quro.core.tools

import android.content.Context
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * AI 音频识别工具：LLM 直接调用语音识别 API，转录音频内容
 */
class AudioRecognitionTool : QuroTool {
    override val name = "audio_recognition"
    override val description = """AI 音频识别工具：将音频文件转录为文字。
参数：{"audio_path":"音频路径","language":"可选语言"}
支持格式：mp3, wav, m4a, ogg, flac 等
返回：音频的文字转录内容。"""
    override val parametersJson = """{
        "type":"object",
        "properties":{
            "audio_path":{"type":"string","description":"音频文件路径"},
            "language":{"type":"string","description":"音频语言（可选，如 zh, en）"}
        },
        "required":["audio_path"]
    }"""

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)  // 音频转录可能需要较长时间
        .build()

    override fun run(context: Context, arguments: String): String {
        val args = JSONObject(arguments)
        val audioPath = args.optString("audio_path", "").trim()
        if (audioPath.isBlank()) return "audio_recognition 需要 audio_path（音频路径）"

        val language = args.optString("language", "").trim()

        return try {
            val result = recognizeAudio(audioPath, language)
            result ?: "音频识别失败，请检查音频路径或稍后重试"
        } catch (e: Exception) {
            Log.e("AudioRecognitionTool", "音频识别异常", e)
            "音频识别异常：${e.message}"
        }
    }

    private fun recognizeAudio(audioPath: String, language: String): String? {
        return try {
            val audioFile = File(audioPath)
            if (!audioFile.exists()) return "音频文件不存在：$audioPath"

            // 使用 OpenAI Whisper API
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "file",
                    audioFile.name,
                    audioFile.asRequestBody("audio/*".toMediaType())
                )
                .addFormDataPart("model", "whisper-1")
                .apply {
                    if (language.isNotBlank()) {
                        addFormDataPart("language", language)
                    }
                }
                .addFormDataPart("response_format", "verbose_json")
                .build()

            val request = Request.Builder()
                .url("https://api.openai.com/v1/audio/transcriptions")
                .addHeader("Authorization", "Bearer ${getApiKey()}")
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val responseBody = response.body?.string()
                val json = JSONObject(responseBody ?: "")
                val text = json.optString("text", "")
                if (text.isNotBlank()) {
                    val duration = json.optDouble("duration", 0.0)
                    "转录完成（时长：${String.format("%.1f", duration)}秒）：\n$text"
                } else {
                    "未识别到语音内容"
                }
            } else {
                Log.e("AudioRecognitionTool", "API 错误: ${response.code}")
                null
            }
        } catch (e: Exception) {
            Log.e("AudioRecognitionTool", "识别失败", e)
            null
        }
    }

    private fun getApiKey(): String {
        // 从配置中获取 API Key
        return ""
    }
}
