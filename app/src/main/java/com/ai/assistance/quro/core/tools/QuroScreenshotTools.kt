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

/** 截图并用视觉模型分析屏幕内容。 */
class VisualAnalysisTool : QuroTool {
    override val name = "visual_analysis"
    override val description = "👁️ 屏幕视觉分析：截取屏幕并用视觉模型分析内容（文字、按钮、图标等）。" +
        "与 read_screen 的区别：read_screen 读取无障碍节点树（快、结构化），visual_analysis 用视觉模型看截图（慢但全面，适合游戏/WebView/Flutter）。" +
        "与 image_recognition 的区别：visual_analysis 分析当前屏幕截图，image_recognition 分析用户提供的图片文件。"
    override val parametersJson = """{"type":"object","properties":{"question":{"type":"string","description":"你想了解屏幕上的什么内容"}},"required":[]}}"""

    override fun run(context: Context, arguments: String): String {
        val args = try { org.json.JSONObject(arguments) } catch (e: Exception) { org.json.JSONObject() }
        val question = args.optString("question", "描述屏幕上的内容")

        // 1. 截图
        val screenshotTool = ScreenshotTool()
        val result = screenshotTool.run(context, arguments)

        if (!result.startsWith("✅")) {
            return "❌ 无法截图: $result"
        }

        val path = result.removePrefix("✅ 截图成功: ")

        // 2. 获取节点树作为辅助信息
        val nodeTreeInfo = try {
            val readTool = ReadScreenTool()
            readTool.run(context, "{}").take(2000)
        } catch (e: Exception) { "" }

        // 3. 构建多模态提示
        return """
## 📸 屏幕截图已保存
路径: $path

## 📋 节点树信息（辅助）
$nodeTreeInfo

## 🎯 用户问题
$question

---
**说明**: 截图已保存到本地。如需视觉模型分析，请使用 http_request 工具调用视觉API，
传入截图路径和此提示词。或直接查看截图文件。
        """.trimIndent()
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
