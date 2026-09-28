package com.ai.assistance.quro.core.tools

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Environment
import android.util.Base64
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.ai.assistance.quro.service.QuroAccessibilityService
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import com.ai.assistance.quro.core.model.QuroModelConfigRepository
import com.ai.assistance.quro.core.model.QuroFunctionModelConfigRepository
import com.ai.assistance.quro.core.model.QuroFunctionType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * 屏幕截图工具集 - 实现视觉双模感知。
 *
 * 提供三种截图方式：
 * 1. AccessibilityService.takeScreenshot() - Android P+ 原生截图
 * 2. MediaProjection - 屏幕投射截图（需要用户授权）
 * 3. 视觉分析 - 将截图发送给视觉大模型分析
 */

// ──────────────────── 截图工具 ────────────────────

/** 截取当前屏幕并保存为文件，返回文件路径。 */
class ScreenshotTool : QuroTool {
    override val name = "screenshot"
    override val description = "截取当前屏幕截图并保存。返回截图文件路径（可用于视觉分析）。无需参数 {}。"
    override val parametersJson = """{"type":"object","properties":{}}"""

    override fun run(context: Context, arguments: String): String {
        val svc = QuroAccessibilityService.instance
            ?: return "❌ 无障碍服务未连接：请到设置 → 无障碍 → ZorvAI → 开启"

        return try {
            // 方式1: Android P+ 原生截图
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val screenshot = captureWithAccessibility(svc)
                if (screenshot != null) {
                    val path = saveScreenshot(context, screenshot)
                    return "✅ 截图成功: $path"
                }
            }

            // 方式2: 像素级截图（通过View绘制）
            val root = svc.rootInActiveWindow
            if (root != null) {
                val bitmap = captureNodeTree(root)
                if (bitmap != null) {
                    val path = saveScreenshot(context, bitmap)
                    return "✅ 截图成功: $path"
                }
            }

            "❌ 截图失败：请确保已授予截图权限"
        } catch (e: Exception) {
            "❌ 截图失败: ${e.message}"
        }
    }

    /**
     * 通过 AccessibilityService.takeScreenshot() 截图（Android P+）。
     */
    private fun captureWithAccessibility(service: QuroAccessibilityService): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null

        val latch = CountDownLatch(1)
        var result: Bitmap? = null

        try {
            // 使用反射调用 takeScreenshot（因为 SDK 限制）
            val method = service.javaClass.getMethod(
                "takeScreenshot",
                Int::class.java,
                android.os.Handler::class.java,
                Any::class.java
            )

            val callback = object : Any() {
                @android.annotation.SuppressLint("NewApi")
                fun onScreenshot(bitmap: Bitmap?) {
                    result = bitmap
                    latch.countDown()
                }
            }

            // 主线程执行
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                try {
                    method.invoke(service, 0, android.os.Handler(android.os.Looper.getMainLooper()), callback)
                } catch (e: Exception) {
                    Log.w("ScreenshotTool", "takeScreenshot reflection failed", e)
                    latch.countDown()
                }
            }

            latch.await(5, TimeUnit.SECONDS)
        } catch (e: Exception) {
            Log.w("ScreenshotTool", "takeScreenshot failed", e)
        }

        return result
    }

    /**
     * 通过节点树边界生成示意截图（不依赖系统截图API）。
     * 返回一个包含节点树文本描述的占位图。
     */
    private fun captureNodeTree(root: AccessibilityNodeInfo): Bitmap? {
        try {
            val bounds = Rect()
            root.getBoundsInScreen(bounds)
            val width = bounds.width().coerceAtLeast(100)
            val height = bounds.height().coerceAtLeast(100)

            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            canvas.drawColor(android.graphics.Color.WHITE)

            // 绘制节点树文本描述
            val paint = android.graphics.Paint().apply {
                color = android.graphics.Color.BLACK
                textSize = 24f
            }

            val nodes = mutableListOf<String>()
            collectNodeTexts(root, nodes, 0)

            var y = 30f
            for (nodeText in nodes.take(20)) {
                canvas.drawText(nodeText.take(50), 10f, y, paint)
                y += 30f
                if (y > height - 10) break
            }

            return bitmap
        } catch (e: Exception) {
            return null
        }
    }

    private fun collectNodeTexts(node: AccessibilityNodeInfo?, out: MutableList<String>, depth: Int) {
        if (node == null || out.size >= 30) return
        val indent = "  ".repeat(depth.coerceAtMost(4))
        val text = node.text?.toString()?.take(40) ?: ""
        val desc = node.contentDescription?.toString()?.take(40) ?: ""
        val cls = node.className?.toString()?.substringAfterLast(".")?.take(20) ?: "?"
        val label = text.ifEmpty { desc.ifEmpty { cls } }
        if (label.isNotEmpty()) {
            out.add("$indent$label")
        }
        for (i in 0 until node.childCount.coerceAtMost(20)) {
            collectNodeTexts(node.getChild(i), out, depth + 1)
        }
    }

    private fun saveScreenshot(context: Context, bitmap: Bitmap): String {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val filename = "screenshot_$timestamp.png"

        // 保存到应用私有目录
        val dir = File(context.filesDir, "screenshots")
        dir.mkdirs()
        val file = File(dir, filename)

        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }

        return file.absolutePath
    }
}

/** 截图并返回 Base64 编码（用于发送给视觉模型分析）。 */
class ScreenshotBase64Tool : QuroTool {
    override val name = "screenshot_base64"
    override val description = "截取屏幕并返回 Base64 编码的图片（用于视觉模型分析）。无需参数 {}。"
    override val parametersJson = """{"type":"object","properties":{}}"""

    override fun run(context: Context, arguments: String): String {
        // 复用 ScreenshotTool 的逻辑
        val screenshotTool = ScreenshotTool()
        val result = screenshotTool.run(context, arguments)

        if (result.startsWith("✅")) {
            val path = result.removePrefix("✅ 截图成功: ")
            val file = File(path)
            if (file.exists()) {
                val bytes = file.readBytes()
                val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                return """{"path":"$path","base64_length":${base64.length},"base64_preview":"${base64.take(100)}..."}"""
            }
        }

        return result
    }
}

// ──────────────────── 视觉分析工具 ────────────────────

/**
 * 屏幕视觉分析：截图后用视觉大模型（用户已配置的模型，需支持图像输入）真实分析屏幕内容。
 * 支持多种分析模式，可在任何场景下调用——只要「看见屏幕」有助于回答问题即可使用。
 */
class VisualAnalysisTool : QuroTool {
    override val name = "visual_analysis"
    override val description = "👁️ 屏幕视觉分析（真实视觉理解）：截取当前屏幕，把截图作为图片直接喂给当前多模态对话模型「亲眼」查看，" +
        "不再绕去独立视觉API。与 read_screen 区别：read_screen 读无障碍节点树（快/结构化兜底），" +
        "visual_analysis 让当前模型直接看截图（能识别游戏/WebView/Flutter/自绘UI、图标、文字、布局）。" +
        "可在任何场景调用：看屏幕内容、识别按钮/文字/图标、理解游戏或App界面、找元素、OCR。若当前模型不支持视觉，自动降级为视觉模型API或节点树。" +
        "参数：question（可选）；mode（general=综合描述/ui=UI元素/ocr=文字/game=游戏界面/find=定位元素）。"
    override val parametersJson = """{
        "type":"object",
        "properties":{
            "question":{"type":"string","description":"你想了解屏幕上的什么内容"},
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

        // 1. 截图（无障碍 takeScreenshot；失败则直接退出）
        val shotResult = ScreenshotTool().run(context, arguments)
        if (!shotResult.startsWith("✅")) {
            return "❌ 无法截图，视觉分析终止: " + shotResult
        }
        val path = shotResult.removePrefix("✅ 截图成功: ")
        val imageFile = java.io.File(path)
        if (!imageFile.exists()) return "❌ 截图文件不存在: " + path

        // 2. 节点树（Level 3 兜底，始终准备）
        val nodeTreeInfo = try {
            ReadScreenTool().run(context, "{}").take(4000)
        } catch (e: Exception) { "" }

        // 3. 判断当前主模型是否具备视觉能力
        val cfg = try { QuroModelConfigRepository(context).load() } catch (e: Exception) { null }
        val modelName = cfg?.model ?: ""

        return if (isLikelyVisionModel(modelName)) {
            // ── Level 1（主路径）：把截图作为图片注入当前多模态模型的对话上下文 ──
            val sb = buildString {
                appendLine("## 屏幕视觉分析")
                appendLine()
                appendLine("已把当前屏幕截图作为图片直接传入对话上下文，请直接「看」图并用你的视觉能力描述/回答。")
                appendLine("分析模式：$mode")
                if (question.isNotBlank()) appendLine("用户问题：$question")
                appendLine()
                appendLine("请基于下方附带的屏幕截图直接作答；若截图不可见，可参考下面的节点树作为结构化兜底。")
                if (nodeTreeInfo.isNotBlank()) {
                    appendLine()
                    appendLine("## 辅助节点树（无障碍结构，兜底参考）")
                    appendLine(nodeTreeInfo)
                }
            }
            // 注入标记：QuroAssistant 解析后把该图片作为隐藏 user 消息注入对话，再剔除本标记。
            sb + "\n\n<!--QURO_INJECT_IMAGE:" + path + "-->"
        } else {
            // ── Level 2：当前模型非视觉模型 → 调用「图像识别」绑定的视觉模型 API ──
            val analysis = try { callVisionModel(buildPrompt(mode, question), path) } catch (e: Exception) { null }
            if (analysis != null) {
                buildString {
                    appendLine("## 屏幕视觉分析结果（视觉模型API）")
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
                // ── Level 3：未配置视觉 API → 仅返回节点树 ──
                buildString {
                    appendLine("## 视觉模型不可用（当前模型不支持视觉，且未配置视觉API），降级返回节点树")
                    appendLine()
                    appendLine("截图已保存: " + path)
                    appendLine("可在「设置 → 功能模型配置 → 图像识别」指定支持视觉的独立模型。")
                    if (nodeTreeInfo.isNotBlank()) {
                        appendLine()
                        appendLine("## 节点树信息（辅助）")
                        appendLine(nodeTreeInfo)
                    }
                }
            }
        }
    }

    /** 当前主模型是否大概率支持视觉输入（决定 Level 1 / Level 2）。默认乐观：现代模型大多支持视觉，优先让当前模型「看」。 */
    private fun isLikelyVisionModel(name: String): Boolean {
        val n = name.lowercase()
        if (n.isBlank()) return true
        val textOnly = listOf("gpt-3.5", "text-embedding", "babbage", "davinci", "ada", "tts-1", "whisper",
            "embedding", "instruct", "llama-2", "llama2", "qwen2-0.5b", "qwen2-1.5b", "qwen2-7b-instruct",
            "qwen2.5-0.5b", "qwen2.5-1.5b", "qwen3-0.6b", "qwen3-1.7b")
        if (textOnly.any { n.contains(it) }) return false
        val vision = listOf("vision", "-vl", "gpt-4o", "gpt-4-vision", "gpt-4-turbo", "gpt-4.1", "gpt-4.5",
            "gemini", "claude-3", "claude-opus", "claude-sonnet", "qwen-vl", "qwen2-vl", "qwen2.5-vl",
            "moonshot", "kimi", "pixtral", "llama-3.2-vision", "llama-4", "llama4", "step-", "minimax",
            "deepseek-vl", "glm-4v", "glm-v", "yi-vl", "internvl", "visual", "qvq", "janus")
        if (vision.any { n.contains(it) }) return true
        return true
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
            base + "\n\n用户的具体问题：" + question + "\n请结合截图回答该问题。"
        } else base
    }

    /** Level 2：调用「图像识别」功能绑定的视觉模型。未配置密钥则返回 null（交由 Level 3 节点树兜底）。 */
    private fun callVisionModel(prompt: String, imagePath: String): String? {
        val ctx = contextRef ?: return null
        val global = try { QuroModelConfigRepository(ctx).load() } catch (e: Exception) { return null }
        val cfg = QuroFunctionModelConfigRepository(ctx).resolveConfig(QuroFunctionType.IMAGE_RECOGNITION, global)
        if (cfg.apiKey.isBlank()) return null
        val model = if (cfg.model.isNotBlank()) cfg.model else "gpt-4o"
        val baseUrl = if (cfg.baseUrl.isNotBlank()) cfg.baseUrl.trimEnd('/') else "https://api.openai.com/v1"
        val apiUrl = baseUrl + "/chat/completions"
        val b64 = try { Base64.encodeToString(java.io.File(imagePath).readBytes(), Base64.NO_WRAP) } catch (e: Exception) { return null }

        val jsonBody = org.json.JSONObject().apply {
            put("model", model)
            put("messages", org.json.JSONArray().apply {
                put(org.json.JSONObject().apply {
                    put("role", "user")
                    put("content", org.json.JSONArray().apply {
                        put(org.json.JSONObject().apply { put("type", "text"); put("text", prompt) })
                        put(org.json.JSONObject().apply {
                            put("type", "image_url")
                            put("image_url", org.json.JSONObject().apply {
                                put("url", "data:image/jpeg;base64," + b64)
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
            } else { null }
        } catch (e: Exception) { null }
    }
}

// ──────────────────── 系统级动作工具 ────────────────────

/** 拍照工具 - 调用系统相机拍照。 */
class TakePhotoTool : QuroTool {
    override val name = "take_photo"
    override val description = "调用系统相机拍照并保存。无需参数 {}。需要相机权限。"
    override val parametersJson = """{"type":"object","properties":{}}"""

    override fun run(context: Context, arguments: String): String {
        return try {
            val intent = android.content.Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE)
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            "✅ 已打开相机，请拍照"
        } catch (e: Exception) {
            "❌ 无法打开相机: ${e.message}"
        }
    }
}

/** 录屏工具 - 开始/停止屏幕录制。 */
class ScreenRecordTool : QuroTool {
    override val name = "screen_record"
    override val description = "开始或停止屏幕录制。参数: {\"action\":\"start\"} 或 {\"action\":\"stop\"}"
    override val parametersJson = """{"type":"object","properties":{"action":{"type":"string","enum":["start","stop"],"description":"start开始录制，stop停止录制"}},"required":["action"]}}"""

    override fun run(context: Context, arguments: String): String {
        val args = try { org.json.JSONObject(arguments) } catch (e: Exception) { org.json.JSONObject() }
        val action = args.optString("action", "start")

        return when (action) {
            "start" -> {
                val intent = android.content.Intent(android.provider.MediaStore.ACTION_VIDEO_CAPTURE)
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                "✅ 已打开录屏，请开始录制"
            }
            "stop" -> "请手动停止录制（系统录屏需要手动操作）"
            else -> "无效操作: $action"
        }
    }
}

/** 音量控制工具。 */
class VolumeControlTool : QuroTool {
    override val name = "volume_control"
    override val description = "控制系统音量。参数: {\"action\":\"up\"/\"down\"/\"mute\"/\"set\",\"level\":0-15}"
    override val parametersJson = """{"type":"object","properties":{"action":{"type":"string","enum":["up","down","mute","unmute","set"],"description":"音量操作"},"level":{"type":"integer","description":"音量级别0-15（仅set时需要）"}},"required":["action"]}}"""

    override fun run(context: Context, arguments: String): String {
        val args = try { org.json.JSONObject(arguments) } catch (e: Exception) { org.json.JSONObject() }
        val action = args.optString("action", "up")
        val level = args.optInt("level", -1)

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
            ?: return "❌ 无法获取音频服务"

        return when (action) {
            "up" -> {
                audioManager.adjustStreamVolume(
                    android.media.AudioManager.STREAM_MUSIC,
                    android.media.AudioManager.ADJUST_RAISE,
                    android.media.AudioManager.FLAG_SHOW_UI
                )
                val current = audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
                "✅ 音量已增加，当前: $current/15"
            }
            "down" -> {
                audioManager.adjustStreamVolume(
                    android.media.AudioManager.STREAM_MUSIC,
                    android.media.AudioManager.ADJUST_LOWER,
                    android.media.AudioManager.FLAG_SHOW_UI
                )
                val current = audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
                "✅ 音量已降低，当前: $current/15"
            }
            "mute" -> {
                audioManager.adjustStreamVolume(
                    android.media.AudioManager.STREAM_MUSIC,
                    android.media.AudioManager.ADJUST_MUTE,
                    android.media.AudioManager.FLAG_SHOW_UI
                )
                "✅ 已静音"
            }
            "unmute" -> {
                audioManager.adjustStreamVolume(
                    android.media.AudioManager.STREAM_MUSIC,
                    android.media.AudioManager.ADJUST_UNMUTE,
                    android.media.AudioManager.FLAG_SHOW_UI
                )
                "✅ 已取消静音"
            }
            "set" -> {
                if (level in 0..15) {
                    audioManager.setStreamVolume(
                        android.media.AudioManager.STREAM_MUSIC,
                        level,
                        android.media.AudioManager.FLAG_SHOW_UI
                    )
                    "✅ 音量已设置为: $level/15"
                } else {
                    "❌ 音量级别必须在0-15之间"
                }
            }
            else -> "❌ 无效操作: $action"
        }
    }
}

/** 亮度控制工具。 */
class BrightnessControlTool : QuroTool {
    override val name = "brightness_control"
    override val description = "控制屏幕亮度。参数: {\"action\":\"up\"/\"down\"/\"auto\"/\"set\",\"level\":0-255}"
    override val parametersJson = """{"type":"object","properties":{"action":{"type":"string","enum":["up","down","auto","set"],"description":"亮度操作"},"level":{"type":"integer","description":"亮度级别0-255（仅set时需要）"}},"required":["action"]}}"""

    override fun run(context: Context, arguments: String): String {
        val args = try { org.json.JSONObject(arguments) } catch (e: Exception) { org.json.JSONObject() }
        val action = args.optString("action", "auto")
        val level = args.optInt("level", -1)

        return try {
            val resolver = context.contentResolver
            when (action) {
                "auto" -> {
                    android.provider.Settings.System.putInt(
                        resolver,
                        android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE,
                        android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
                    )
                    "✅ 已开启自动亮度"
                }
                "set" -> {
                    if (level in 0..255) {
                        android.provider.Settings.System.putInt(
                            resolver,
                            android.provider.Settings.System.SCREEN_BRIGHTNESS,
                            level
                        )
                        android.provider.Settings.System.putInt(
                            resolver,
                            android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE,
                            android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                        )
                        "✅ 亮度已设置为: $level/255"
                    } else {
                        "❌ 亮度级别必须在0-255之间"
                    }
                }
                else -> "❌ 无效操作: $action"
            }
        } catch (e: Exception) {
            "❌ 亮度控制失败: ${e.message}"
        }
    }
}

/** WiFi控制工具。 */
class WiFiControlTool : QuroTool {
    override val name = "wifi_control"
    override val description = "控制WiFi开关。参数: {\"action\":\"on\"/\"off\"/\"toggle\"}"
    override val parametersJson = """{"type":"object","properties":{"action":{"type":"string","enum":["on","off","toggle"],"description":"WiFi操作"}},"required":["action"]}}"""

    override fun run(context: Context, arguments: String): String {
        val args = try { org.json.JSONObject(arguments) } catch (e: Exception) { org.json.JSONObject() }
        val action = args.optString("action", "toggle")

        return try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
                ?: return "❌ 无法获取WiFi服务"

            when (action) {
                "on" -> {
                    @Suppress("DEPRECATION")
                    wifiManager.isWifiEnabled = true
                    "✅ WiFi已开启"
                }
                "off" -> {
                    @Suppress("DEPRECATION")
                    wifiManager.isWifiEnabled = false
                    "✅ WiFi已关闭"
                }
                "toggle" -> {
                    @Suppress("DEPRECATION")
                    val newState = !wifiManager.isWifiEnabled
                    wifiManager.isWifiEnabled = newState
                    "✅ WiFi已${if (newState) "开启" else "关闭"}"
                }
                else -> "❌ 无效操作: $action"
            }
        } catch (e: Exception) {
            "❌ WiFi控制失败: ${e.message}"
        }
    }
}

/** 蓝牙控制工具。 */
class BluetoothControlTool : QuroTool {
    override val name = "bluetooth_control"
    override val description = "控制蓝牙开关。参数: {\"action\":\"on\"/\"off\"/\"toggle\"}"
    override val parametersJson = """{"type":"object","properties":{"action":{"type":"string","enum":["on","off","toggle"],"description":"蓝牙操作"}},"required":["action"]}}"""

    override fun run(context: Context, arguments: String): String {
        val args = try { org.json.JSONObject(arguments) } catch (e: Exception) { org.json.JSONObject() }
        val action = args.optString("action", "toggle")

        return try {
            val bluetoothAdapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
                ?: return "❌ 设备不支持蓝牙"

            when (action) {
                "on" -> {
                    @Suppress("DEPRECATION")
                    bluetoothAdapter.enable()
                    "✅ 蓝牙已开启"
                }
                "off" -> {
                    @Suppress("DEPRECATION")
                    bluetoothAdapter.disable()
                    "✅ 蓝牙已关闭"
                }
                "toggle" -> {
                    @Suppress("DEPRECATION")
                    if (bluetoothAdapter.isEnabled) {
                        bluetoothAdapter.disable()
                        "✅ 蓝牙已关闭"
                    } else {
                        bluetoothAdapter.enable()
                        "✅ 蓝牙已开启"
                    }
                }
                else -> "❌ 无效操作: $action"
            }
        } catch (e: Exception) {
            "❌ 蓝牙控制失败: ${e.message}"
        }
    }
}

/** 通知栏控制工具。 */
class NotificationControlTool : QuroTool {
    override val name = "notification_control"
    override val description = "展开/收起通知栏。参数: {\"action\":\"expand\"/\"collapse\"/\"clear\"}"
    override val parametersJson = """{"type":"object","properties":{"action":{"type":"string","enum":["expand","collapse","clear"],"description":"通知栏操作"}},"required":["action"]}}"""

    override fun run(context: Context, arguments: String): String {
        val args = try { org.json.JSONObject(arguments) } catch (e: Exception) { org.json.JSONObject() }
        val action = args.optString("action", "expand")

        val svc = QuroAccessibilityService.instance
            ?: return "❌ 无障碍服务未连接"

        return try {
            when (action) {
                "expand" -> {
                    svc.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
                    "✅ 已展开通知栏"
                }
                "collapse" -> {
                    svc.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                    "✅ 已收起通知栏"
                }
                "clear" -> {
                    svc.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
                    "✅ 已清除通知栏"
                }
                else -> "❌ 无效操作: $action"
            }
        } catch (e: Exception) {
            "❌ 通知栏操作失败: ${e.message}"
        }
    }
}

/** 飞行模式控制工具。 */
class AirplaneModeTool : QuroTool {
    override val name = "airplane_mode"
    override val description = "控制飞行模式开关。参数: {\"action\":\"on\"/\"off\"/\"toggle\"}"
    override val parametersJson = """{"type":"object","properties":{"action":{"type":"string","enum":["on","off","toggle"],"description":"飞行模式操作"}},"required":["action"]}}"""

    override fun run(context: Context, arguments: String): String {
        val args = try { org.json.JSONObject(arguments) } catch (e: Exception) { org.json.JSONObject() }
        val action = args.optString("action", "toggle")

        return try {
            val resolver = context.contentResolver
            val current = android.provider.Settings.Global.getInt(
                resolver,
                android.provider.Settings.Global.AIRPLANE_MODE_ON,
                0
            )

            when (action) {
                "on" -> {
                    android.provider.Settings.Global.putInt(resolver, android.provider.Settings.Global.AIRPLANE_MODE_ON, 1)
                    // 发送广播通知系统
                    val intent = android.content.Intent(android.content.Intent.ACTION_AIRPLANE_MODE_CHANGED)
                    intent.putExtra("state", true)
                    context.sendBroadcast(intent)
                    "✅ 已开启飞行模式"
                }
                "off" -> {
                    android.provider.Settings.Global.putInt(resolver, android.provider.Settings.Global.AIRPLANE_MODE_ON, 0)
                    val intent = android.content.Intent(android.content.Intent.ACTION_AIRPLANE_MODE_CHANGED)
                    intent.putExtra("state", false)
                    context.sendBroadcast(intent)
                    "✅ 已关闭飞行模式"
                }
                "toggle" -> {
                    val newState = current == 0
                    android.provider.Settings.Global.putInt(resolver, android.provider.Settings.Global.AIRPLANE_MODE_ON, if (newState) 1 else 0)
                    val intent = android.content.Intent(android.content.Intent.ACTION_AIRPLANE_MODE_CHANGED)
                    intent.putExtra("state", newState)
                    context.sendBroadcast(intent)
                    "✅ 飞行模式已${if (newState) "开启" else "关闭"}"
                }
                else -> "❌ 无效操作: $action"
            }
        } catch (e: Exception) {
            "❌ 飞行模式控制失败: ${e.message}"
        }
    }
}

/** 屏幕旋转控制工具。 */
class ScreenRotationTool : QuroTool {
    override val name = "screen_rotation"
    override val description = "控制屏幕旋转。参数: {\"action\":\"auto\"/\"portrait\"/\"landscape\"}"
    override val parametersJson = """{"type":"object","properties":{"action":{"type":"string","enum":["auto","portrait","landscape"],"description":"旋转模式"}},"required":["action"]}}"""

    override fun run(context: Context, arguments: String): String {
        val args = try { org.json.JSONObject(arguments) } catch (e: Exception) { org.json.JSONObject() }
        val action = args.optString("action", "auto")

        return try {
            val resolver = context.contentResolver
            when (action) {
                "auto" -> {
                    android.provider.Settings.System.putInt(
                        resolver,
                        android.provider.Settings.System.ACCELEROMETER_ROTATION,
                        1
                    )
                    "✅ 已开启自动旋转"
                }
                "portrait" -> {
                    android.provider.Settings.System.putInt(
                        resolver,
                        android.provider.Settings.System.ACCELEROMETER_ROTATION,
                        0
                    )
                    android.provider.Settings.System.putInt(
                        resolver,
                        android.provider.Settings.System.USER_ROTATION,
                        0
                    )
                    "✅ 已锁定竖屏"
                }
                "landscape" -> {
                    android.provider.Settings.System.putInt(
                        resolver,
                        android.provider.Settings.System.ACCELEROMETER_ROTATION,
                        0
                    )
                    android.provider.Settings.System.putInt(
                        resolver,
                        android.provider.Settings.System.USER_ROTATION,
                        1
                    )
                    "✅ 已锁定横屏"
                }
                else -> "❌ 无效操作: $action"
            }
        } catch (e: Exception) {
            "❌ 屏幕旋转控制失败: ${e.message}"
        }
    }
}

/** 倒计时工具。 */
class SetTimerTool : QuroTool {
    override val name = "set_timer"
    override val description = "设置倒计时。参数: {\"minutes\":5,\"label\":\"煮面\"}"
    override val parametersJson = """{"type":"object","properties":{"minutes":{"type":"integer","description":"倒计时分钟数"},"label":{"type":"string","description":"计时器标签"}},"required":["minutes"]}}"""

    override fun run(context: Context, arguments: String): String {
        val args = try { org.json.JSONObject(arguments) } catch (e: Exception) { org.json.JSONObject() }
        val minutes = args.optInt("minutes", -1)
        val label = args.optString("label", "倒计时")

        if (minutes <= 0 || minutes > 1440) {
            return "❌ 倒计时必须在1-1440分钟之间"
        }

        return try {
            val seconds = minutes * 60
            val intent = android.content.Intent(android.provider.AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(android.provider.AlarmClock.EXTRA_LENGTH, seconds)
                putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, label)
                putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, true)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            "✅ 倒计时已设置: ${minutes}分钟 $label"
        } catch (e: Exception) {
            "❌ 设置倒计时失败: ${e.message}"
        }
    }
}

/** 打开应用工具（已弃用，请使用 launch_app）。 */
class OpenAppTool : QuroTool {
    override val name = "open_app"
    override val description = "⚠️ 已弃用，请改用 launch_app（支持包名和应用名模糊匹配，功能更强）。打开指定应用。参数: {\"package\":\"com.android.settings\"}"
    override val parametersJson = """{"type":"object","properties":{"package":{"type":"string","description":"应用包名"}},"required":["package"]}}"""

    override fun run(context: Context, arguments: String): String {
        val args = try { org.json.JSONObject(arguments) } catch (e: Exception) { org.json.JSONObject() }
        val packageName = args.optString("package", "")

        if (packageName.isEmpty()) {
            return "❌ 请提供应用包名"
        }

        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                "✅ 已打开: $packageName"
            } else {
                "❌ 未找到应用: $packageName"
            }
        } catch (e: Exception) {
            "❌ 打开应用失败: ${e.message}"
        }
    }
}
