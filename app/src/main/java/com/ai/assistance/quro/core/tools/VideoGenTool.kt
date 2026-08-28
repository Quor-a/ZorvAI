package com.ai.assistance.quro.core.tools

import android.content.Context
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import com.ai.assistance.quro.core.model.QuroFunctionModelConfigRepository
import com.ai.assistance.quro.core.model.QuroFunctionType
import com.ai.assistance.quro.core.model.QuroModelConfigRepository
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * AI 视频生成工具：LLM 直接调用生视频 API，结果返回对话框
 */
class VideoGenTool : QuroTool {
    override val name = "video_gen"
    override val description = """AI 视频生成工具：根据文本描述生成视频，结果直接返回到对话框。
参数：{"prompt":"视频描述","duration":5,"model":"可选模型名"}
支持的模型：runway-gen-3, pika, stable-video 等
生成的视频会自动添加到对话附件中。"""
    override val parametersJson = """{
        "type":"object",
        "properties":{
            "prompt":{"type":"string","description":"视频描述文本"},
            "duration":{"type":"integer","description":"视频时长（秒），默认5"},
            "model":{"type":"string","description":"生视频模型名（可选）"}
        },
        "required":["prompt"]
    }"""

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)  // 视频生成需要更长时间
        .build()

    override fun run(context: Context, arguments: String): String {
        val args = JSONObject(arguments)
        val prompt = args.optString("prompt", "").trim()
        if (prompt.isBlank()) return "video_gen 需要 prompt（视频描述）"

        val duration = args.optInt("duration", 5)
        val model = args.optString("model", "runway-gen-3").trim()

        return try {
            val videoUrl = generateVideo(context, prompt, duration, model)
            if (videoUrl != null) {
                // 下载视频并保存
                val videoPath = downloadVideo(context, videoUrl)
                if (videoPath != null) {
                    "视频生成成功！\n路径：$videoPath\n描述：$prompt\n模型：$model\n时长：${duration}秒"
                } else {
                    "视频生成成功但下载失败，请稍后重试"
                }
            } else {
                "视频生成失败，请检查 API 配置或稍后重试"
            }
        } catch (e: Exception) {
            Log.e("VideoGenTool", "视频生成异常", e)
            "视频生成异常：${e.message}"
        }
    }

    private fun generateVideo(context: Context, prompt: String, duration: Int, model: String): String? {
        return try {
            val (apiKey, baseUrl) = resolveCreds(context)
            if (apiKey.isBlank()) {
                Log.e("VideoGenTool", "未配置 API Key，无法调用生视频接口")
                return null
            }
            val binding = QuroFunctionModelConfigRepository(context).getBinding(QuroFunctionType.VIDEO_GEN)
            val effectiveModel = binding.model.ifBlank { model }
            val endpoint = baseUrl.removeSuffix("/") + "/video/generations"
            val jsonBody = JSONObject().apply {
                put("prompt", prompt)
                put("duration", duration)
                put("model", effectiveModel)
            }

            val requestBody = jsonBody.toString()
                .toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url(endpoint)
                .addHeader("Authorization", "Bearer $apiKey")
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val responseBody = response.body?.string()
                val json = JSONObject(responseBody ?: "")
                // 根据实际API响应解析视频URL
                json.optString("video_url") ?: json.optString("url")
            } else {
                Log.e("VideoGenTool", "API 错误: ${response.code}")
                null
            }
        } catch (e: Exception) {
            Log.e("VideoGenTool", "生成失败", e)
            null
        }
    }

    private fun downloadVideo(context: Context, videoUrl: String): String? {
        return try {
            val request = Request.Builder().url(videoUrl).build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val fileName = "video_gen_${System.currentTimeMillis()}.mp4"
                val uploadsDir = File(context.filesDir, "quro_uploads")
                if (!uploadsDir.exists()) uploadsDir.mkdirs()
                val file = File(uploadsDir, fileName)
                response.body?.byteStream()?.use { input ->
                    file.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                file.absolutePath
            } else null
        } catch (e: Exception) {
            Log.e("VideoGenTool", "下载失败", e)
            null
        }
    }

    /** 解析生视频接口的鉴权与接入点：功能级绑定(apiKey/baseUrl) > 全局模型配置。 */
    private fun resolveCreds(context: Context): Pair<String, String> {
        val global = QuroModelConfigRepository(context).load()
        val binding = QuroFunctionModelConfigRepository(context).getBinding(QuroFunctionType.VIDEO_GEN)
        val apiKey = binding.apiKey.ifBlank { global.apiKey }
        val baseUrl = binding.baseUrl.ifBlank { global.baseUrl }
        return apiKey to baseUrl
    }
}
