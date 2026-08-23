package com.ai.assistance.quro.core.tools

import android.content.Context
import android.util.Log
import com.ai.assistance.quro.core.attachment.AttachmentManager
import com.ai.assistance.quro.core.model.QuroModelConfigRepository
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * AI 图像识别工具：LLM 直接调用图像识别 API，分析图片内容
 */
class ImageRecognitionTool : QuroTool {
    override val name = "image_recognition"
    override val description = """AI 图像识别工具：分析图片内容，返回详细描述。
参数：{"image_path":"图片路径","question":"可选问题"}
支持识别：物体、场景、文字、人脸、图标等
返回：图片内容的详细描述和分析。"""
    override val parametersJson = """{
        "type":"object",
        "properties":{
            "image_path":{"type":"string","description":"图片文件路径"},
            "question":{"type":"string","description":"关于图片的具体问题（可选）"}
        },
        "required":["image_path"]
    }"""

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private var context: Context? = null

    override fun run(context: Context, arguments: String): String {
        this.context = context
        val args = JSONObject(arguments)
        val imagePath = args.optString("image_path", "").trim()
        if (imagePath.isBlank()) return "image_recognition 需要 image_path（图片路径）"

        val question = args.optString("question", "").trim()

        return try {
            val result = recognizeImage(imagePath, question)
            result ?: "图像识别失败，请检查图片路径或稍后重试"
        } catch (e: Exception) {
            Log.e("ImageRecognitionTool", "图像识别异常", e)
            "图像识别异常：${e.message}"
        }
    }

    private fun recognizeImage(imagePath: String, question: String): String? {
        return try {
            // 读取图片并转为 Base64
            val imageFile = java.io.File(imagePath)
            if (!imageFile.exists()) return "图片文件不存在：$imagePath"

            val imageBytes = imageFile.readBytes()
            val base64Image = Base64.getEncoder().encodeToString(imageBytes)

            // 构建提示词
            val prompt = if (question.isNotBlank()) {
                "请分析这张图片并回答：$question"
            } else {
                "请详细描述这张图片的内容，包括：1) 主要对象和场景 2) 文字内容（如有）3) 整体氛围和用途"
            }

            // 使用 OpenAI Vision API
            val jsonBody = JSONObject().apply {
                put("model", "gpt-4-vision-preview")
                put("messages", org.json.JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", org.json.JSONArray().apply {
                            put(JSONObject().apply {
                                put("type", "text")
                                put("text", prompt)
                            })
                            put(JSONObject().apply {
                                put("type", "image_url")
                                put("image_url", JSONObject().apply {
                                    put("url", "data:image/jpeg;base64,$base64Image")
                                    put("detail", "high")
                                })
                            })
                        })
                    })
                })
                put("max_tokens", 1000)
            }

            val requestBody = jsonBody.toString()
                .toRequestBody("application/json".toMediaType())

            val baseUrl = getBaseUrl()
            val apiUrl = if (baseUrl.isNotBlank()) {
                "${baseUrl.trimEnd('/')}/chat/completions"
            } else {
                "https://api.openai.com/v1/chat/completions"
            }
            
            val request = Request.Builder()
                .url(apiUrl)
                .addHeader("Authorization", "Bearer ${getApiKey()}")
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val responseBody = response.body?.string()
                val json = JSONObject(responseBody ?: "")
                val choices = json.getJSONArray("choices")
                if (choices.length() > 0) {
                    choices.getJSONObject(0)
                        .getJSONObject("message")
                        .optString("content")
                } else null
            } else {
                Log.e("ImageRecognitionTool", "API 错误: ${response.code}")
                null
            }
        } catch (e: Exception) {
            Log.e("ImageRecognitionTool", "识别失败", e)
            null
        }
    }

    private fun getApiKey(): String {
        // 从配置中获取 API Key
        val ctx = context ?: return ""
        return try {
            val config = QuroModelConfigRepository(ctx).load()
            config.apiKey
        } catch (e: Exception) {
            Log.e("ImageRecognitionTool", "获取API密钥失败", e)
            ""
        }
    }

    private fun getBaseUrl(): String {
        // 从配置中获取基础URL
        val ctx = context ?: return ""
        return try {
            val config = QuroModelConfigRepository(ctx).load()
            config.baseUrl
        } catch (e: Exception) {
            Log.e("ImageRecognitionTool", "获取基础URL失败", e)
            ""
        }
    }
}
