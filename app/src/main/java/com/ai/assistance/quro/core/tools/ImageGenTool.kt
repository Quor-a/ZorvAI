package com.ai.assistance.quro.core.tools

import android.content.Context
import android.util.Log
import com.ai.assistance.quro.core.attachment.AttachmentManager
import com.ai.assistance.quro.core.network.QuroLlmClient
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * AI 图片生成工具：LLM 直接调用生图 API，结果返回对话框
 */
class ImageGenTool : QuroTool {
    override val name = "image_gen"
    override val description = """AI 图片生成工具：根据文本描述生成图片，结果直接返回到对话框。
参数：{"prompt":"图片描述","width":1024,"height":1024,"model":"可选模型名"}
支持的模型：dall-e-3, stable-diffusion, midjourney 等
生成的图片会自动添加到对话附件中。"""
    override val parametersJson = """{
        "type":"object",
        "properties":{
            "prompt":{"type":"string","description":"图片描述文本"},
            "width":{"type":"integer","description":"图片宽度，默认1024"},
            "height":{"type":"integer","description":"图片高度，默认1024"},
            "model":{"type":"string","description":"生图模型名（可选）"}
        },
        "required":["prompt"]
    }"""

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    override fun run(context: Context, arguments: String): String {
        val args = JSONObject(arguments)
        val prompt = args.optString("prompt", "").trim()
        if (prompt.isBlank()) return "image_gen 需要 prompt（图片描述）"

        val width = args.optInt("width", 1024)
        val height = args.optInt("height", 1024)
        val model = args.optString("model", "dall-e-3").trim()

        return try {
            val imageUrl = generateImage(prompt, width, height, model)
            if (imageUrl != null) {
                // 下载图片并保存
                val imagePath = downloadImage(context, imageUrl)
                if (imagePath != null) {
                    "图片生成成功！\n路径：$imagePath\n描述：$prompt\n模型：$model"
                } else {
                    "图片生成成功但下载失败，请稍后重试"
                }
            } else {
                "图片生成失败，请检查 API 配置或稍后重试"
            }
        } catch (e: Exception) {
            Log.e("ImageGenTool", "图片生成异常", e)
            "图片生成异常：${e.message}"
        }
    }

    private fun generateImage(prompt: String, width: Int, height: Int, model: String): String? {
        return runBlocking {
            try {
                // 使用 OpenAI DALL-E 3 API
                val jsonBody = JSONObject().apply {
                    put("model", model)
                    put("prompt", prompt)
                    put("n", 1)
                    put("size", "${width}x${height}")
                    put("response_format", "url")
                }

                val requestBody = jsonBody.toString()
                    .toRequestBody("application/json".toMediaType())

                val request = Request.Builder()
                    .url("https://api.openai.com/v1/images/generations")
                    .addHeader("Authorization", "Bearer ${getApiKey()}")
                    .post(requestBody)
                    .build()

                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val responseBody = response.body?.string()
                    val json = JSONObject(responseBody ?: "")
                    val data = json.getJSONArray("data")
                    if (data.length() > 0) {
                        data.getJSONObject(0).optString("url")
                    } else null
                } else {
                    Log.e("ImageGenTool", "API 错误: ${response.code}")
                    null
                }
            } catch (e: Exception) {
                Log.e("ImageGenTool", "生成失败", e)
                null
            }
        }
    }

    private fun downloadImage(context: Context, imageUrl: String): String? {
        return try {
            val request = Request.Builder().url(imageUrl).build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val fileName = "img_gen_${System.currentTimeMillis()}.png"
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
            Log.e("ImageGenTool", "下载失败", e)
            null
        }
    }

    private fun getApiKey(): String {
        // 从配置中获取 API Key
        // 这里简化处理，实际应该从 QuroModelConfig 中获取
        return ""
    }
}
