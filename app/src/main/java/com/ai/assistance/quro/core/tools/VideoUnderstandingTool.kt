package com.ai.assistance.quro.core.tools

import android.content.Context
import android.media.MediaMetadataRetriever
import android.util.Log
import com.ai.assistance.quro.core.model.QuroModelConfigRepository
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * AI 视频理解工具：LLM 直接调用视频理解 API，分析视频内容
 * 提取关键帧并使用视觉模型进行理解
 */
class VideoUnderstandingTool : QuroTool {
    override val name = "video_understanding"
    override val description = """AI 视频理解工具：分析视频内容，返回详细描述。
参数：{"video_path":"视频路径","question":"可选问题","max_frames":3}
支持理解：视频内容、场景、动作、文字、人物等
返回：视频内容的详细描述和分析。"""
    override val parametersJson = """{
        "type":"object",
        "properties":{
            "video_path":{"type":"string","description":"视频文件路径"},
            "question":{"type":"string","description":"关于视频的具体问题（可选）"},
            "max_frames":{"type":"integer","description":"提取的最大帧数（默认3）"}
        },
        "required":["video_path"]
    }"""

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private var context: Context? = null

    override fun run(context: Context, arguments: String): String {
        this.context = context
        val args = JSONObject(arguments)
        val videoPath = args.optString("video_path", "").trim()
        if (videoPath.isBlank()) return "video_understanding 需要 video_path（视频路径）"

        val question = args.optString("question", "").trim()
        val maxFrames = args.optInt("max_frames", 3)

        return try {
            val result = understandVideo(videoPath, question, maxFrames)
            result ?: "视频理解失败，请检查视频路径或稍后重试"
        } catch (e: Exception) {
            Log.e("VideoUnderstandingTool", "视频理解异常", e)
            "视频理解异常：${e.message}"
        }
    }

    private fun understandVideo(videoPath: String, question: String, maxFrames: Int): String? {
        return try {
            val videoFile = File(videoPath)
            if (!videoFile.exists()) return "视频文件不存在：$videoPath"

            // 提取视频关键帧
            val frames = extractVideoFrames(videoPath, maxFrames)
            if (frames.isEmpty()) return "无法提取视频帧，视频可能已损坏或格式不支持"

            // 构建提示词
            val prompt = if (question.isNotBlank()) {
                "请分析这个视频的内容并回答：$question\n\n视频共提取了${frames.size}个关键帧："
            } else {
                "请详细描述这个视频的内容，包括：1) 主要场景和动作 2) 文字内容（如有）3) 人物或物体 4) 整体情节和用途\n\n视频共提取了${frames.size}个关键帧："
            }

            // 构建多帧图像请求
            val jsonBody = JSONObject().apply {
                put("model", "gpt-4-vision-preview")
                put("messages", org.json.JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", org.json.JSONArray().apply {
                            // 文本提示
                            put(JSONObject().apply {
                                put("type", "text")
                                put("text", prompt)
                            })
                            
                            // 添加每个关键帧
                            frames.forEachIndexed { index, frameBase64 ->
                                put(JSONObject().apply {
                                    put("type", "image_url")
                                    put("image_url", JSONObject().apply {
                                        put("url", "data:image/jpeg;base64,$frameBase64")
                                        put("detail", "high")
                                    })
                                })
                            }
                        })
                    })
                })
                put("max_tokens", 1500)
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
                Log.e("VideoUnderstandingTool", "API 错误: ${response.code}")
                null
            }
        } catch (e: Exception) {
            Log.e("VideoUnderstandingTool", "理解失败", e)
            null
        }
    }

    private fun extractVideoFrames(videoPath: String, maxFrames: Int): List<String> {
        val frames = mutableListOf<String>()
        val retriever = MediaMetadataRetriever()
        
        try {
            retriever.setDataSource(videoPath)
            
            // 获取视频时长（微秒）
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
            if (duration <= 0) return frames
            
            // 计算帧间隔
            val interval = duration / (maxFrames + 1)
            
            // 提取关键帧
            for (i in 1..maxFrames) {
                val timeUs = interval * i
                val bitmap = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                if (bitmap != null) {
                    // 转换为Base64
                    val outputStream = java.io.ByteArrayOutputStream()
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, outputStream)
                    val imageBytes = outputStream.toByteArray()
                    val base64Image = Base64.getEncoder().encodeToString(imageBytes)
                    frames.add(base64Image)
                    bitmap.recycle()
                }
            }
        } catch (e: Exception) {
            Log.e("VideoUnderstandingTool", "提取视频帧失败", e)
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
                // 忽略释放异常
            }
        }
        
        return frames
    }

    private fun getApiKey(): String {
        // 从配置中获取 API Key
        val ctx = context ?: return ""
        return try {
            val config = QuroModelConfigRepository(ctx).load()
            config.apiKey
        } catch (e: Exception) {
            Log.e("VideoUnderstandingTool", "获取API密钥失败", e)
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
            Log.e("VideoUnderstandingTool", "获取基础URL失败", e)
            ""
        }
    }
}