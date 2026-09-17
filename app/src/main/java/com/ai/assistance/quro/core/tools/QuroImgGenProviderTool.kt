package com.ai.assistance.quro.core.tools

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 多厂商文生图（统一入口）。
 * 各厂商走 OpenAI 兼容 /images/generations 接口，密钥从 auth_service 读取（name=厂商名，type=apikey/bearer）。
 * 生成后自动下载到系统下载目录（或应用图片目录）并返回路径。
 */
class QuroImgGenProviderTool : QuroTool {
    override val name = "img_gen_provider"
    override val description = "多厂商文生图（统一入口）：支持 openai / qwen(通义万相) / minimax / siliconflow(硅基流动) / xai / nanobanana / zhipu(智谱)。" +
        "各厂商走 OpenAI 兼容 /images/generations 接口，密钥从 auth_service 读取（name=厂商名，type=apikey/bearer）。" +
        "参数 {\"provider\":\"厂商（必填）\",\"prompt\":\"提示词（必填）\",\"model\":\"可选覆盖默认模型\",\"size\":\"1024x1024（可选）\",\"n\":1（最大4）}。" +
        "生成后自动下载到下载目录并返回文件路径。"
    override val parametersJson = """{
        "type":"object",
        "properties":{
            "provider":{"type":"string","description":"厂商：openai | qwen | minimax | siliconflow | xai | nanobanana | zhipu"},
            "prompt":{"type":"string","description":"图像生成提示词（必填）"},
            "model":{"type":"string","description":"模型名（可选，覆盖默认）"},
            "size":{"type":"string","description":"图像尺寸，默认 1024x1024"},
            "n":{"type":"integer","description":"生成数量，默认 1，最大 4"}
        },
        "required":["provider","prompt"]
    }"""

    private val baseUrls = mapOf(
        "openai" to "https://api.openai.com/v1/images/generations",
        "qwen" to "https://dashscope.aliyuncs.com/compatible-mode/v1/images/generations",
        "minimax" to "https://api.minimax.io/v1/images/generations",
        "siliconflow" to "https://api.siliconflow.cn/v1/images/generations",
        "xai" to "https://api.x.ai/v1/images/generations",
        "nanobanana" to "https://api.nanobanana.ai/v1/images/generations",
        "zhipu" to "https://open.bigmodel.cn/api/paas/v4/images/generations"
    )
    private val defaultModels = mapOf(
        "openai" to "gpt-image-1",
        "qwen" to "wanx2.1-t2i-plus",
        "minimax" to "image-01",
        "siliconflow" to "Kwai-Kolors/Kolors",
        "xai" to "grok-2-image",
        "nanobanana" to "nanobanana-1",
        "zhipu" to "cogview-3-plus"
    )

    override fun run(context: Context, arguments: String): String {
        val jo = runCatching { JSONObject(arguments) }.getOrElse { JSONObject() }
        val provider = jo.optString("provider", "").lowercase()
        val prompt = jo.optString("prompt", "").trim()
        if (provider.isEmpty()) return "❌ 缺少 provider 参数（可选：openai/qwen/minimax/siliconflow/xai/nanobanana/zhipu）"
        if (prompt.isEmpty()) return "❌ 缺少 prompt 参数"
        val endpoint = baseUrls[provider] ?: return "❌ 不支持的 provider：$provider"
        val model = jo.optString("model", "").ifBlank { defaultModels[provider] ?: "default" }
        val size = jo.optString("size", "1024x1024")
        val n = jo.optInt("n", 1).coerceIn(1, 4)
        val svc = QuroAuthStore.get(context, provider)
        if (svc == null || svc.token.isBlank()) {
            return "❌ 未配置 $provider 密钥。请先用 auth_service_add 添加：name=$provider, type=apikey, token=<你的API Key>。"
        }
        return runBlocking(Dispatchers.IO) {
            try {
                genAndSave(context, provider, endpoint, svc.token, prompt, model, size, n)
            } catch (e: Exception) {
                "❌ 生图失败：${e.message}"
            }
        }
    }

    private fun genAndSave(
        context: Context, provider: String, endpoint: String, token: String,
        prompt: String, model: String, size: String, n: Int,
    ): String {
        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $token")
            connectTimeout = 20000
            readTimeout = 120000
            doOutput = true
        }
        val body = JSONObject().apply {
            put("model", model)
            put("prompt", prompt)
            put("n", n)
            put("size", size)
        }.toString()
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText().orEmpty()
        conn.disconnect()
        if (code !in 200..299) return "❌ $provider 生图请求失败 HTTP $code: ${text.take(400)}"
        val root = JSONObject(text)
        val data = root.optJSONArray("data") ?: return "❌ 响应缺少 data 字段：${text.take(300)}"
        val sb = StringBuilder("🖼️ $provider 生图成功（model=$model）：\n")
        for (i in 0 until data.length()) {
            val item = data.optJSONObject(i) ?: continue
            val url = item.optString("url", "")
            val b64 = item.optString("b64_json", "")
            when {
                url.isNotBlank() -> {
                    val dl = QuroDownloadUtil.download(context, url, "ZorvAI/1.0", null, "image/png")
                    sb.append("第${i + 1}张: $dl\n   $url\n")
                }
                b64.isNotBlank() -> {
                    val bytes = Base64.decode(b64, Base64.DEFAULT)
                    val name = "quro_img_${System.currentTimeMillis()}_$i.png"
                    val dir = context.getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES) ?: context.filesDir
                    val f = java.io.File(dir, name)
                    f.writeBytes(bytes)
                    val saved = QuroDownloadUtil.saveFileToDownloads(context, f, name, "image/png") ?: f.absolutePath
                    sb.append("第${i + 1}张已保存: $saved\n")
                }
                else -> sb.append("第${i + 1}张: 无可用图像数据\n")
            }
        }
        return sb.toString().trim()
    }
}
