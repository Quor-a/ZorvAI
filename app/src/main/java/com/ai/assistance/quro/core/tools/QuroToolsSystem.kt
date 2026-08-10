package com.ai.assistance.quro.core.tools

import com.ai.assistance.quro.core.QuroBrowserBridge
import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.SensorManager
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraCharacteristics
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.app.NotificationManager
import android.content.ClipboardManager
import android.content.ClipData
import androidx.core.content.ContextCompat
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.os.Build
import org.json.JSONObject

/** 电量与充电状态（无权限）。 */
class GetBatteryTool : QuroTool {
    override val name = "get_battery"
    override val description = "获取设备电量百分比与充电状态，参数为空 {}。"
    override val parametersJson = """{"type":"object","properties":{}}"""
    override fun run(context: Context, arguments: String): String {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return "电量=${bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)}%, 充电中=${bm.isCharging}"
    }
}

/** 当前 Wi-Fi 信息（ACCESS_WIFI_STATE 为普通权限，安装即授予）。 */
class GetWifiTool : QuroTool {
    override val name = "get_wifi_info"
    override val description = "获取当前连接的 Wi-Fi 名称(SSID)与连接状态，参数为空 {}。"
    override val parametersJson = """{"type":"object","properties":{}}"""
    override fun run(context: Context, arguments: String): String {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val info = wm.connectionInfo
        val ssid = if (info.ssid == "<unknown ssid>") "未知" else (info.ssid ?: "未知").trim('"')
        return "SSID=$ssid, IP=${info.ipAddress}, 已连接=${info.networkId >= 0}"
    }
}

/** 网络连通性与类型（无权限）。 */
class GetNetworkTool : QuroTool {
    override val name = "get_network_info"
    override val description = "获取网络类型与是否联网，参数为空 {}。"
    override val parametersJson = """{"type":"object","properties":{}}"""
    override fun run(context: Context, arguments: String): String {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        val type = when {
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "WIFI"
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "CELLULAR"
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "ETHERNET"
            else -> "NONE"
        }
        return "已联网=${caps != null}, 类型=$type"
    }
}

/** 设备传感器列表（无权限）。 */
class GetSensorsTool : QuroTool {
    override val name = "get_sensors"
    override val description = "列出设备可用传感器名称与类型，参数为空 {}。"
    override val parametersJson = """{"type":"object","properties":{}}"""
    override fun run(context: Context, arguments: String): String {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val list = sm.getSensorList(android.hardware.Sensor.TYPE_ALL).map { "${it.name}(type=${it.type})" }
        return if (list.isEmpty()) "无传感器" else list.joinToString("\n")
    }
}

/** 振动（VIBRATE 为普通权限）。 */
class VibrateTool : QuroTool {
    override val name = "vibrate"
    override val description = "让设备振动指定毫秒，参数为 {\"ms\":300}。"
    override val parametersJson = """{"type":"object","properties":{"ms":{"type":"integer","description":"振动时长(毫秒)"}},"required":["ms"]}"""
    override fun run(context: Context, arguments: String): String {
        val ms = JSONObject(arguments).optLong("ms", 300)
        val v = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        return "已振动 ${ms}ms"
    }
}

/** 读取剪贴板文本。
 *  Android 10+ 后台读取返回 null；Android 12+ 前台读取会弹 toast 通知用户。
 *  本工具仅在用户主动触发（AI 对话中调用）时执行，此时 App 通常在前台。
 *  若仍读不到，提示用户在前台重试（这是 Android 隐私保护机制，非 bug）。
 */
class GetClipboardTool : QuroTool {
    override val name = "get_clipboard"
    override val description = "读取系统剪贴板文本，参数为空 {}。注意：Android 12+ 仅允许前台应用读取剪贴板，若返回空请用户在前台重新复制后重试。"
    override val parametersJson = """{"type":"object","properties":{}}"""
    override fun run(context: Context, arguments: String): String {
        // 前台检测：若 App 不在前台，明确告知而非静默返回空
        val pm = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        val isInteractive = pm?.isInteractive ?: true // 无法判断时默认允许尝试
        if (!isInteractive) {
            return "⚠️ 剪贴板读取失败：当前不在前台。Android 12+ 仅允许前台应用读取剪贴板，请在屏幕亮起且 App 在前台时重试。"
        }
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        if (!cm.hasPrimaryClip()) return "（剪贴板为空）"
        val item = cm.primaryClip?.getItemAt(0) ?: return "（剪贴板无内容）"
        val text = item.text?.toString()
        // Android 12+：即使在前台，某些 ROM/安全策略也可能拦截
        if (text.isNullOrBlank()) {
            return "⚠️ 剪贴板内容不可读（可能被系统隐私保护拦截）。建议：① 在前台重新复制一次文本 ② 复制后立即让 AI 读取 ③ 部分国产 ROM 需在「设置→隐私→剪贴板访问」中授权"
        }
        return text
    }
}

/** 写入剪贴板。 */
class SetClipboardTool : QuroTool {
    override val name = "set_clipboard"
    override val description = "写入系统剪贴板，参数为 {\"text\":\"要写入的内容\"}。"
    override val parametersJson = """{"type":"object","properties":{"text":{"type":"string","description":"要写入的文本"}},"required":["text"]}"""
    override fun run(context: Context, arguments: String): String {
        val text = JSONObject(arguments).optString("text", "")
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Quro", text))
        return "已写入剪贴板"
    }
}

/** 已安装应用列表（应用内 PackageManager 查询；本应用已声明 QUERY_ALL_PACKAGES，可见全部已装应用）。 */
class ListAppsTool : QuroTool {
    override val name = "list_installed_apps"
    override val description = "列出已安装应用(名称+包名)，参数为空或 {\"query\":\"名称片段\"}。"
    override val parametersJson = """{"type":"object","properties":{"query":{"type":"string","description":"按名称过滤(可选)"}}}"""
    override fun run(context: Context, arguments: String): String {
        val q = JSONObject(arguments).optString("query", "").lowercase()
        return queryViaPackageManager(context, q)
    }

    private fun queryViaPackageManager(ctx: Context, q: String): String {
        val pm = ctx.packageManager
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .map { it to pm.getApplicationLabel(it).toString() }
            .filter { q.isEmpty() || it.second.lowercase().contains(q) }
            .sortedBy { it.second }
            .map { "${it.second} (${it.first.packageName})" }
        return if (apps.isEmpty()) "未找到匹配应用（系统包可见性限制下仅返回部分应用）。" else apps.joinToString("\n")
    }
}

/** 启动应用（支持包名或应用名称模糊匹配，Shell 兜底解决 Android 11+ 包可见性）。 */
class LaunchAppTool : QuroTool {
    override val name = "launch_app"
    override val description = "启动指定应用。可通过 package（精确包名）或 name（应用显示名，模糊匹配）指定目标。优先使用 name 参数，无需知道精确包名。"
    override val parametersJson = """{
        "type":"object",
        "properties":{
            "package":{"type":"string","description":"应用包名（精确），如 com.kuaishou.nebula"},
            "name":{"type":"string","description":"应用显示名称（模糊匹配），如「快手」「微信」"}
        }
    }"""
    override fun run(context: Context, arguments: String): String {
        val args = JSONObject(arguments)
        val pkg = args.optString("package", "").trim()
        val name = args.optString("name", "").trim()
        if (pkg.isEmpty() && name.isEmpty()) return "缺少 package 或 name 参数"

        // 优先用包名精确启动
        if (pkg.isNotEmpty()) {
            val intent = context.packageManager.getLaunchIntentForPackage(pkg)
                ?: return "找不到可启动的入口：$pkg"
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            return "已启动 $pkg"
        }

        // 按名称搜索（混合：PackageManager + Shell 兜底）
        val target = findAppByName(context, name) ?: return "未找到匹配「$name」的应用"
        val intent = context.packageManager.getLaunchIntentForPackage(target.packageName)
            ?: return "找到 ${target.label} 但无法启动"
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return "已启动 ${target.label}（${target.packageName}）"
    }
}

/** 搜索并启动应用（一步完成：按名称查找 → 自动启动第一个匹配项，Shell 兜底）。 */
class SearchAndLaunchAppTool : QuroTool {
    override val name = "search_and_launch_app"
    override val description = "用户想要打开某个应用时的首选工具。输入应用名称（如「快手」「抖音」「微信」），自动搜索并启动最匹配的应用。比先 list_installed_apps 再 launch_app 更高效。"
    override val parametersJson = """{
        "type":"object",
        "properties":{
            "app_name":{"type":"string","description":"要打开的应用名称，如「快手」「微信」「淘宝」"}
        },
        "required":["app_name"]
    }"""
    override fun run(context: Context, arguments: String): String {
        val appName = JSONObject(arguments).optString("app_name", "").trim()
        if (appName.isEmpty()) return "缺少 app_name 参数"

        val target = findAppByName(context, appName) ?: return "未找到匹配「$appName」的应用，设备上可能未安装"

        val intent = context.packageManager.getLaunchIntentForPackage(target.packageName)
            ?: return "找到 ${target.label} 但无法启动"
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return "已为您打开 ${target.label}"
    }
}

/**
 * 按应用名查找已安装的应用（PackageManager 查询；本应用已声明 QUERY_ALL_PACKAGES，可见全部已装应用）。
 * 匹配优先级：精确匹配 > 首字匹配 > 包名包含 > 第一个结果
 */
private data class AppMatch(val packageName: String, val label: String)

private fun findAppByName(ctx: Context, name: String): AppMatch? {
    val q = name.lowercase()
    // 1) PackageManager（受包可见性限制但最快）
    val pmCandidates = ctx.packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
        .map { it to ctx.packageManager.getApplicationLabel(it).toString() }
        .filter { it.second.lowercase().contains(q) }
        .sortedBy { it.second }
    if (pmCandidates.isNotEmpty()) {
        val exact = pmCandidates.firstOrNull { it.second.equals(name, ignoreCase = true) }
        val startsWith = pmCandidates.firstOrNull { it.second.lowercase().startsWith(q) }
        val target = exact ?: startsWith ?: pmCandidates.first()
        return AppMatch(target.first.packageName, target.second)
    }
    // PackageManager 已声明 QUERY_ALL_PACKAGES，可见全部应用；无 shell 兜底
    return null
}

/** 查询应用的精确包名（通过应用显示名反查）。 */
class GetPackageNameTool : QuroTool {
    override val name = "get_package_name"
    override val description = "根据应用显示名查询其精确包名。参数 {\"app_name\":\"应用名\"}。当需要精确包名做高级操作时使用。"
    override val parametersJson = """{
        "type":"object",
        "properties":{
            "app_name":{"type":"string","description":"要查询的应用显示名称，如「快手」「微信」「网易云音乐\""}
        },
        "required":["app_name"]
    }"""
    override fun run(context: Context, arguments: String): String {
        val appName = JSONObject(arguments).optString("app_name", "").trim()
        if (appName.isEmpty()) return "缺少 app_name 参数"
        val target = findAppByName(context, appName) ?: return "未找到名为「$appName」的应用"
        return "${target.label} 的包名是：${target.packageName}"
    }
}

/** 活跃通知（无权限）。 */
class GetNotificationsTool : QuroTool {
    override val name = "get_active_notifications"
    override val description = "读取当前活跃通知(标题+文本)，参数为空 {}。"
    override val parametersJson = """{"type":"object","properties":{}}"""
    override fun run(context: Context, arguments: String): String {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val list = nm.activeNotifications.mapNotNull { n ->
            val e = n.notification.extras ?: return@mapNotNull null
            val title = e.getCharSequence("android.title")?.toString() ?: ""
            val text = e.getCharSequence("android.text")?.toString() ?: ""
            if (title.isEmpty() && text.isEmpty()) null else "$title: $text"
        }
        return if (list.isEmpty()) "（无活跃通知）" else list.joinToString("\n")
    }
}

    /** 蓝牙状态（API 31+ 用 BLUETOOTH_CONNECT；API 30- 用 legacy BLUETOOTH）。 */
    class GetBluetoothTool : QuroTool {
        override val name = "get_bluetooth_status"
        override val description = "获取蓝牙开关状态与已配对设备，参数为空 {}。"
        override val parametersJson = """{"type":"object","properties":{}}"""
        // 🔧 #768 修复：原 listOf(BLUETOOTH, BLUETOOTH_CONNECT) 在 API 31+ 上 BLUETOOTH 是 legacy 权限
        //   （Manifest 中 maxSdkVersion=30），checkSelfPermission 恒 DENIED → 既让门禁 isGranted 误判、
        //   又让 run() 内 needsPermission 直接短路返回「需要权限」，即便 BLUETOOTH_CONNECT 已授权也被拒。
        //   改为按 API 版本只声明真正需要的权限（与 #766 媒体库修复同源）。
        private val perms: List<String>
            get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                listOf(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                listOf(Manifest.permission.BLUETOOTH)
            }
        override val requiredPermissions get() = perms
        override fun run(context: Context, arguments: String): String {
            needsPermission(context, *perms.toTypedArray())?.let { return it }
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            ?: return "不支持蓝牙"
        val paired = try { adapter.bondedDevices.map { "${it.name}(${it.address})" } } catch (e: Exception) { emptyList() }
        return "已启用=${adapter.isEnabled}, 配对设备=${if (paired.isEmpty()) "无" else paired.joinToString("; ")}"
    }
}

/** 手电筒（CAMERA 权限，因闪光灯受相机服务管理）。 */
class ToggleFlashlightTool : QuroTool {
    override val name = "toggle_flashlight"
    override val description = "开关手电筒(闪光灯)，参数为 {\"on\":true}。"
    override val parametersJson = """{"type":"object","properties":{"on":{"type":"boolean","description":"true 开/false 关"}},"required":["on"]}"""
    override val requiredPermissions = listOf(Manifest.permission.CAMERA)
    override fun run(context: Context, arguments: String): String {
        needsPermission(context, Manifest.permission.CAMERA)?.let { return it }
        val on = JSONObject(arguments).optBoolean("on", true)
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return "该设备无可用闪光灯"
        cm.setTorchMode(id, on)
        return if (on) "手电筒已开" else "手电筒已关"
    }
}

// ==================== 文件管理工具（工具箱） ====================

/** 浏览文件目录：列出应用私有目录下的文件和子目录。 */
class BrowseFilesTool : QuroTool {
    override val name = "browse_files"
    override val description = "浏览文件目录，列出指定路径下的文件和子目录。参数 {\"path\":\"路径（默认应用私有根目录）\"}。用于工具箱的文件管理功能。"
    override val parametersJson = """{
        "type":"object",
        "properties":{
            "path":{"type":"string","description":"要浏览的目录路径（留空则列应用私有根目录）"}
        }
    }"""
    override fun run(context: Context, arguments: String): String {
        val path = JSONObject(arguments).optString("path", "").trim()
            .ifBlank { context.filesDir.absolutePath }
        val dir = java.io.File(path)
        if (!dir.exists()) return "目录不存在：$path"
        if (!dir.isDirectory) return "不是目录：$path"
        val items = dir.listFiles()?.sortedWith(compareBy<java.io.File> { !it.isDirectory }.thenBy { it.name.lowercase() })
            ?: return "无法读取目录（权限不足或 IO 错误）"
        if (items.isEmpty()) return "空目录"
        val sb = StringBuilder("📁 $path\n")
        items.forEach { item ->
            val icon = if (item.isDirectory) "📁" else "📄"
            val size = if (item.isFile) {
                val kb = item.length() / 1024
                if (kb > 1024) "${kb / 1024}MB" else "${kb}KB"
            } else ""
            sb.append("$icon ${item.name}${if (size.isNotBlank()) " ($size)" else ""}\n")
        }
        return sb.toString().trim()
    }
}

/** 读取文件内容（增强版：支持更大文件）。 */
class FileReadTool : QuroTool {
    override val name = "file_read"
    override val description = "读取文本文件完整内容。参数 {\"path\":\"文件路径\"}。支持读取应用私有目录内的任何文本文件。用于 IDE/代码查看场景。"
    override val parametersJson = """{
        "type":"object",
        "properties":{"path":{"type":"string","description":"要读取的文件完整路径"}},
        "required":["path"]
    }"""
    override fun run(context: Context, arguments: String): String {
        val path = JSONObject(arguments).optString("path", "").trim()
        if (path.isEmpty()) return "缺少 path 参数"
        val file = java.io.File(path)
        if (!file.exists()) return "文件不存在：$path"
        if (file.length() > 512 * 1024) return "文件过大（${file.length() / 1024}KB），建议分段读取或用其他方式查看"
        return try {
            file.readText(Charsets.UTF_8).takeIf { it.isNotBlank() } ?: "(空文件)"
        } catch (e: Exception) { "读取失败：${e.message}" }
    }
}

// ==================== 浏览器 / 网页工具 ====================

/** 打开网址（在应用内置浏览器中打开，不跳转系统浏览器）。 */
class OpenWebTool : QuroTool {
    override val name = "open_web"
    override val description = "在应用内置【被动】浏览器中打开指定 URL 供用户查看。参数 {\"url\":\"网址\"}。当用户需要浏览网页、查看网页内容时使用，会自动在应用内打开网页视图。注意：这是被动展示，AI 无法在其中点击链接/填表/翻页/进入子页面——若你(AI)要像人一样真正操作网页(点击进入、填表、读取子页面)，必须用 aci_call 调 ZorvAI 受控浏览器的 browser_open→browser_elements→browser_action→browser_read。"
    override val parametersJson = """{
        "type":"object",
        "properties":{"url":{"type":"string","description":"要打开的完整 URL，如 https://www.example.com"}},
        "required":["url"]
    }"""
    override fun run(context: Context, arguments: String): String {
        val url = JSONObject(arguments).optString("url", "").trim()
        if (url.isEmpty()) return "缺少 url 参数"
        return try {
            QuroBrowserBridge.open(url)
            "已在应用内置浏览器被动打开：$url（仅供查看，AI 无法点击/填表/进入子页面）。如需像人一样真正操作该网页，请用 aci_call 调 ZorvAI 受控浏览器的 browser_open→browser_elements→browser_action→browser_read。"
        } catch (e: Exception) { "打开失败：${e.message}" }
    }
}

// ==================== IDE / 代码执行工具 ====================

/** 执行 Python/Node 代码片段（IDE 能力）。 */
class RunCodeTool : QuroTool {
    override val name = "run_code"
    override val description = "执行一段代码并返回输出结果。参数 {\"code\":\"代码内容\",\"lang\":\"python|node|shell\"}。" +
        "node/js 走 App 内置 QuickJS 原生沙箱离线执行（无需 Termux）；python 优先用 Termux 自带 python，否则回退系统 python3。" +
        "适用于简单计算、数据处理、文本分析等轻量级任务。"
    override val parametersJson = """{
        "type":"object",
        "properties":{
            "code":{"type":"string","description":"要执行的代码"},
            "lang":{"type":"string","description":"语言：python（默认）、node、shell"}
        },
        "required":["code"]
    }"""
    override fun run(context: Context, arguments: String): String {
        val code = JSONObject(arguments).optString("code", "").trim()
        val lang = JSONObject(arguments).optString("lang", "python").trim().lowercase()
        if (code.isEmpty()) return "缺少 code 参数"
        return when (lang) {
            "node", "javascript", "js" -> QuroJsExecutor.eval(code)
            "shell", "sh", "bash" -> execShell(context, code)
            "python", "py" -> runPython(code, context)
            else -> runPython(code, context)
        }
    }

    private fun execShell(ctx: Context, cmd: String): String = try {
        val proc = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
        val out = proc.inputStream.bufferedReader().use { it.readText() }.trim()
        val err = proc.errorStream.bufferedReader().use { it.readText() }.trim()
        val code = proc.waitFor()
        val body = (out + "\n" + err).trim()
        if (body.isBlank()) "(退出码=$code，无输出)" else "退出码=$code\n$body"
    } catch (e: Exception) { "执行失败：${e.message}" }

    /** Python 执行：优先用 Termux 自带 python，否则回退系统 python3（缺失时报错）。 */
    private fun runPython(code: String, ctx: Context): String {
        val candidates = listOf(
            "/data/data/com.termux/files/usr/bin/python",
            "/data/data/com.termux/files/usr/bin/python3"
        )
        val py = candidates.firstOrNull { java.io.File(it).exists() }
        val cmd = if (py != null) "$py -c ${quoteShell(code)}" else "python3 -c ${quoteShell(code)}"
        return execShell(ctx, cmd)
    }

    private fun quoteShell(s: String): String = "'" + s.replace("'", "'\\''") + "'"
}

// ==================== 手势控制（无障碍服务）已按纯净架构移除 ====================
// swipe_screen / tap_screen 等无障碍屏幕控制工具已移除：AI 是纯应用内执行体，
// 不通过无障碍 / Shell / Root 控制系统（详见 QuroCmsExecutor 与 QuroPlatformManifest）。

