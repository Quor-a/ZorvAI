# -*- coding: utf-8 -*-
import io, sys

path = r"D:\Calw OS-project\QuroAI\app\src\main\java\com\ai\assistance\quro\core\tools\QuroScreenshotTools.kt"

with io.open(path, "r", encoding="utf-8") as f:
    content = f.read()

# ---- 1) add imports (after the last import line) ----
new_imports = (
    "import com.ai.assistance.quro.core.model.QuroModelConfigRepository\n"
    "import okhttp3.MediaType.Companion.toMediaType\n"
    "import okhttp3.OkHttpClient\n"
    "import okhttp3.Request\n"
    "import okhttp3.RequestBody.Companion.toRequestBody\n"
)
anchor = "import java.util.concurrent.TimeUnit\n"
assert anchor in content, "import anchor not found"
content = content.replace(anchor, anchor + new_imports, 1)

# ---- 2) replace the VisualAnalysisTool block ----
new_class = '''// ──────────────────── 视觉分析工具 ────────────────────

/**
 * 屏幕视觉分析：截图后用视觉大模型（用户已配置的模型，需支持图像输入）真实分析屏幕内容。
 * 支持多种分析模式，可在任何场景下调用——只要「看见屏幕」有助于回答问题即可使用。
 */
class VisualAnalysisTool : QuroTool {
    override val name = "visual_analysis"
    override val description = "👁️ 屏幕视觉分析（真实视觉理解）：截取当前屏幕并用视觉大模型分析。" +
        "与 read_screen 的区别：read_screen 读无障碍节点树（快、结构化），visual_analysis 用视觉模型「看」截图（慢但全面，能识别游戏/WebView/Flutter/自绘 UI、图标、文字、布局）。" +
        "与 image_recognition 的区别：visual_analysis 分析「当前屏幕截图」，image_recognition 分析「用户提供的图片文件」。" +
        "可在任何场景调用：看屏幕内容、识别按钮/文字/图标、理解游戏或 App 界面、找某个元素、OCR 提取文字等。" +
        "参数：question（可选，你想知道的内容）；mode（可选：general=综合描述 / ui=UI元素识别 / ocr=文字提取 / game=游戏界面理解 / find=定位目标元素）。"
    override val parametersJson = """{
        "type":"object",
        "properties":{
            "question":{"type":"string","description":"你想了解屏幕上的什么内容（例如：这个按钮是做什么的 / 屏幕上有哪些文字）"},
            "mode":{"type":"string","description":"分析模式：general(综合描述,默认) / ui(识别按钮·输入框·图标等UI元素) / ocr(提取截图中所有文字) / game(理解游戏/App界面与操作) / find(定位你描述的目标元素并给出操作建议)"}
        },
        "required":[]
    }"""

    private val client = okhttp3.OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private var contextRef: Context? = null

    override fun run(context: Context, arguments: String): String {
        this.contextRef = context
        val args = try { org.json.JSONObject(arguments) } catch (e: Exception) { org.json.JSONObject() }
        val question = args.optString("question", "").trim()
        val mode = args.optString("mode", "general").trim().lowercase().ifBlank { "general" }

        // 1. 截图
        val screenshotTool = ScreenshotTool()
        val result = screenshotTool.run(context, arguments)
        if (!result.startsWith("✅")) {
            return "❌ 无法截图，视觉分析终止: " + result
        }
        val path = result.removePrefix("✅ 截图成功: ")

        // 2. 节点树辅助信息（上限 4000 字符）
        val nodeTreeInfo = try {
            ReadScreenTool().run(context, "{}").take(4000)
        } catch (e: Exception) { "" }

        // 3. 截图转 base64
        val imageFile = java.io.File(path)
        if (!imageFile.exists()) return "❌ 截图文件不存在: " + path
        val base64Image = try {
            Base64.encodeToString(imageFile.readBytes(), Base64.NO_WRAP)
        } catch (e: Exception) { return "❌ 读取截图为 Base64 失败: ${e.message}" }

        // 4. 针对模式的提示词
        val prompt = buildPrompt(mode, question)

        // 5. 调用视觉模型
        val analysis = try {
            callVisionModel(prompt, base64Image)
        } catch (e: Exception) {
            Log.e("VisualAnalysisTool", "视觉分析异常", e)
            null
        }

        return if (analysis != null) {
            buildString {
                appendLine("## 屏幕视觉分析结果")
                appendLine()
                appendLine(analysis.trim())
                appendLine()
                appendLine("---")
                appendLine("截图路径: " + path)
                if (nodeTreeInfo.isNotBlank()) {
                    appendLine()
                    appendLine("## 辅助节点树（无障碍结构，供参考）")
                    appendLine(nodeTreeInfo)
                }
            }
        } else {
            buildString {
                appendLine("## 视觉模型调用失败（已降级返回截图与节点树）")
                appendLine()
                appendLine("截图已保存: " + path)
                appendLine()
                appendLine("可能原因：未配置 API Key、所配置的模型不支持图像输入、或网络不可达。")
                appendLine("可在「设置 → 模型配置」中配置一个支持视觉的模型（如 gpt-4o 或兼容 OpenAI 的视觉端点）。")
                appendLine()
                if (nodeTreeInfo.isNotBlank()) {
                    appendLine("## 节点树信息（辅助）")
                    appendLine(nodeTreeInfo)
                }
                appendLine()
                appendLine("## 你的提问")
                appendLine(question.ifBlank { "（无）" })
            }
        }
    }

    private fun buildPrompt(mode: String, question: String): String {
        val base = when (mode) {
            "ui" -> "请识别这张屏幕截图中的 UI 元素：按钮、输入框、图标、菜单、标签、可点击区域等，说明它们的位置、文字和可能的作用。"
            "ocr" -> "请提取这张截图中的全部文字（OCR），尽量保持原有阅读顺序和排版布局。"
            "game" -> "这是一张游戏或应用界面截图。请描述当前画面、关键元素（血量/分数/角色/道具等）、界面布局，并推测可以如何操作。"
            "find" -> "请在截图中定位用户描述的目标元素，给出它的大致位置（屏幕区域）以及点击/操作建议。"
            else -> "请详细描述这张屏幕截图的内容，包括：1) 整体场景与当前所在界面；2) 主要对象与文字内容；3) 可交互的元素（按钮、输入框等）；4) 整体用途。"
        }
        return if (question.isNotBlank()) {
            base + "\\n\\n用户的具体问题：" + question + "\\n请结合截图回答该问题。"
        } else base
    }

    private fun callVisionModel(prompt: String, base64Image: String): String? {
        val ctx = contextRef ?: return null
        val cfg = try { QuroModelConfigRepository(ctx).load() } catch (e: Exception) { return null }
        if (cfg.apiKey.isBlank()) return null
        val model = if (cfg.model.isNotBlank()) cfg.model else "gpt-4o"
        val baseUrl = if (cfg.baseUrl.isNotBlank()) cfg.baseUrl.trimEnd('/') else "https://api.openai.com/v1"
        val apiUrl = baseUrl + "/chat/completions"

        val jsonBody = org.json.JSONObject().apply {
            put("model", model)
            put("messages", org.json.JSONArray().apply {
                put(org.json.JSONObject().apply {
                    put("role", "user")
                    put("content", org.json.JSONArray().apply {
                        put(org.json.JSONObject().apply {
                            put("type", "text")
                            put("text", prompt)
                        })
                        put(org.json.JSONObject().apply {
                            put("type", "image_url")
                            put("image_url", org.json.JSONObject().apply {
                                put("url", "data:image/jpeg;base64," + base64Image)
                                put("detail", "high")
                            })
                        })
                    })
                })
            })
            put("max_tokens", 1500)
        }

        return try {
            val requestBody = jsonBody.toString().toRequestBody("application/json".toMediaType())
            val request = okhttp3.Request.Builder()
                .url(apiUrl)
                .addHeader("Authorization", "Bearer " + cfg.apiKey)
                .post(requestBody)
                .build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: ""
                val json = org.json.JSONObject(body)
                val choices = json.getJSONArray("choices")
                if (choices.length() > 0) choices.getJSONObject(0).getJSONObject("message").optString("content") else null
            } else {
                Log.e("VisualAnalysisTool", "API 错误 " + response.code + ": " + (response.body?.string()?.take(300) ?: ""))
                null
            }
        } catch (e: Exception) {
            Log.e("VisualAnalysisTool", "调用视觉模型失败", e)
            null
        }
    }
}
'''

lines = content.split("\n")
start = None
end = None
for i, l in enumerate(lines):
    if "视觉分析工具" in l and l.strip().startswith("//"):
        start = i
        break
for i, l in enumerate(lines):
    if "系统级动作工具" in l and l.strip().startswith("//"):
        end = i
        break

assert start is not None and end is not None, "markers not found"
assert end > start, "marker order wrong"
new_lines = new_class.split("\n")
content = "\n".join(lines[:start] + new_lines + lines[end:])

with io.open(path, "w", encoding="utf-8") as f:
    f.write(content)

# verify
with io.open(path, "r", encoding="utf-8") as f:
    v = f.read()
print("ok_import_okhttp3:", "import okhttp3.OkHttpClient" in v)
print("ok_new_class:", "private fun callVisionModel" in v)
print("ok_old_removed:", "请使用 http_request 工具调用视觉API" not in v)
print("start,end:", start, end)
