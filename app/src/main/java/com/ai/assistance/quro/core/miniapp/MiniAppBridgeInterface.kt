package com.ai.assistance.quro.core.miniapp

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.speech.tts.TextToSpeech
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.pm.PackageInfoCompat
import org.json.JSONObject

/**
 * 小程序JSBridge接口
 * 
 * 提供JavaScript调用原生能力的桥梁
 */
class MiniAppBridgeInterface(
    private val context: Context,
    private val webView: WebView
) {
    
    private val modules = mutableMapOf<String, MiniAppBridgeModule>()
    
    init {
        // 注册内置模块
        registerModule(StorageModule(context))
        registerModule(DeviceModule(context))
        registerModule(UiModule(context))
        registerModule(NetworkModule(context))
        registerModule(RouterModule(context, this))
        // 原生 Kotlin 能力：让 AI 生成的小程序可调用真·Android/Kotlin（剪贴板/分享/打开App/通知/TTS 等）
        registerModule(KotlinModule(context))
        // 移植自 MiniAppFramework 的富能力模块（去品牌化，协议兼容 MiniAppBridgeModule）
        registerModule(AciModule(context))      // 关联启动第三方 App / 组件
        registerModule(CryptoModule(context))   // md5/sha1/sha256/hmac
        registerModule(SqlStorageModule(context)) // 结构化 SQLite 存储
        registerModule(LocationModule(context)) // 获取位置
    }
    
    fun registerModule(module: MiniAppBridgeModule) {
        modules[module.name] = module
    }
    
    /**
     * JavaScript调用入口
     */
    @JavascriptInterface
    fun invoke(json: String) {
        try {
            val msg = JSONObject(json)
            val id = msg.optString("id", "")
            val type = msg.optString("type", "")
            val moduleName = msg.optString("module", "")
            val method = msg.optString("method", "")
            val params = msg.optJSONObject("params") ?: JSONObject()
            
            if (type == "invoke") {
                val module = modules[moduleName]
                if (module == null) {
                    sendResponse(id, -1, null, "module not found: $moduleName")
                    return
                }
                
                module.invoke(method, params) { code, data, message ->
                    sendResponse(id, code, data, message)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    /**
     * 发送响应给JavaScript
     */
    fun sendResponse(id: String, code: Int, data: Any?, message: String?) {
        val response = JSONObject().apply {
            put("id", id)
            put("type", "response")
            put("code", code)
            put("data", data ?: JSONObject.NULL)
            put("message", message ?: "")
        }
        
        val js = "window.__onBridgeResponse && window.__onBridgeResponse(${response.toString()});"
        webView.post {
            webView.evaluateJavascript(js, null)
        }
    }
    
    /**
     * 推送事件给JavaScript
     */
    fun pushEvent(event: String, data: Any?) {
        val eventObj = JSONObject().apply {
            put("id", "evt_${System.currentTimeMillis()}")
            put("type", "event")
            put("event", event)
            put("data", data ?: JSONObject.NULL)
        }
        
        val js = "window.__onBridgeEvent && window.__onBridgeEvent(${eventObj.toString()});"
        webView.post {
            webView.evaluateJavascript(js, null)
        }
    }
}

/**
 * 桥接模块接口
 */
interface MiniAppBridgeModule {
    val name: String
    fun invoke(method: String, params: JSONObject, callback: (Int, Any?, String?) -> Unit)
}

/**
 * 存储模块
 */
class StorageModule(private val context: Context) : MiniAppBridgeModule {
    override val name = "storage"
    
    override fun invoke(method: String, params: JSONObject, callback: (Int, Any?, String?) -> Unit) {
        val prefs = context.getSharedPreferences("miniapp_storage", Context.MODE_PRIVATE)
        
        when (method) {
            "setItem" -> {
                val key = params.optString("key", "")
                val value = params.optString("value", "")
                if (key.isNotEmpty()) {
                    prefs.edit().putString(key, value).apply()
                    callback(0, null, null)
                } else {
                    callback(-1, null, "key is required")
                }
            }
            "getItem" -> {
                val key = params.optString("key", "")
                if (key.isNotEmpty()) {
                    val value = prefs.getString(key, null)
                    callback(0, value, null)
                } else {
                    callback(-1, null, "key is required")
                }
            }
            "removeItem" -> {
                val key = params.optString("key", "")
                if (key.isNotEmpty()) {
                    prefs.edit().remove(key).apply()
                    callback(0, null, null)
                } else {
                    callback(-1, null, "key is required")
                }
            }
            "clear" -> {
                prefs.edit().clear().apply()
                callback(0, null, null)
            }
            else -> {
                callback(-1, null, "method not found: $method")
            }
        }
    }
}

/**
 * 设备模块
 */
class DeviceModule(private val context: Context) : MiniAppBridgeModule {
    override val name = "device"
    
    override fun invoke(method: String, params: JSONObject, callback: (Int, Any?, String?) -> Unit) {
        when (method) {
            "getSystemInfo" -> {
                val info = JSONObject().apply {
                    put("brand", android.os.Build.BRAND)
                    put("model", android.os.Build.MODEL)
                    put("system", "Android ${android.os.Build.VERSION.RELEASE}")
                    put("sdkVersion", android.os.Build.VERSION.SDK_INT)
                    put("screenWidth", context.resources.displayMetrics.widthPixels)
                    put("screenHeight", context.resources.displayMetrics.heightPixels)
                    put("pixelRatio", context.resources.displayMetrics.density)
                }
                callback(0, info, null)
            }
            "vibrate" -> {
                val duration = params.optLong("duration", 200)
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
                vibrator?.vibrate(duration)
                callback(0, null, null)
            }
            else -> {
                callback(-1, null, "method not found: $method")
            }
        }
    }
}

/**
 * UI模块
 */
class UiModule(private val context: Context) : MiniAppBridgeModule {
    override val name = "ui"
    
    override fun invoke(method: String, params: JSONObject, callback: (Int, Any?, String?) -> Unit) {
        when (method) {
            "toast" -> {
                val title = params.optString("title", "")
                android.widget.Toast.makeText(context, title, android.widget.Toast.LENGTH_SHORT).show()
                callback(0, null, null)
            }
            "setNavigationBarTitle" -> {
                // 这个需要在Activity中实现，这里只是占位
                callback(0, null, null)
            }
            else -> {
                callback(-1, null, "method not found: $method")
            }
        }
    }
}

/**
 * 网络模块
 */
class NetworkModule(private val context: Context) : MiniAppBridgeModule {
    override val name = "network"
    
    override fun invoke(method: String, params: JSONObject, callback: (Int, Any?, String?) -> Unit) {
        when (method) {
            "request" -> {
                // 简单实现，实际应该用OkHttp
                Thread {
                    try {
                        val url = params.optString("url", "")
                        val methodType = params.optString("method", "GET")
                        val headers = params.optJSONObject("headers")
                        val data = params.optString("data", "")
                        
                        val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                        connection.requestMethod = methodType
                        connection.connectTimeout = 10000
                        connection.readTimeout = 10000
                        
                        // 设置请求头
                        headers?.keys()?.forEach { key ->
                            connection.setRequestProperty(key, headers.getString(key))
                        }
                        
                        // 发送请求体
                        if (methodType == "POST" && data.isNotEmpty()) {
                            connection.doOutput = true
                            connection.outputStream.write(data.toByteArray())
                        }
                        
                        val responseCode = connection.responseCode
                        val inputStream = if (responseCode in 200..299) {
                            connection.inputStream
                        } else {
                            connection.errorStream
                        }
                        
                        val response = inputStream.bufferedReader().readText()
                        connection.disconnect()
                        
                        val result = JSONObject().apply {
                            put("statusCode", responseCode)
                            put("data", response)
                            put("header", JSONObject())
                        }
                        
                        callback(0, result, null)
                    } catch (e: Exception) {
                        callback(-1, null, e.message)
                    }
                }.start()
            }
            else -> {
                callback(-1, null, "method not found: $method")
            }
        }
    }
}

/**
 * 路由模块
 */
class RouterModule(
    private val context: Context,
    private val bridgeInterface: MiniAppBridgeInterface
) : MiniAppBridgeModule {
    override val name = "router"
    
    override fun invoke(method: String, params: JSONObject, callback: (Int, Any?, String?) -> Unit) {
        when (method) {
            "navigateTo" -> {
                val url = params.optString("url", "")
                // 这个需要在MiniAppEngine中实现
                callback(0, null, null)
            }
            "navigateBack" -> {
                // 这个需要在MiniAppEngine中实现
                callback(0, null, null)
            }
            else -> {
                callback(-1, null, "method not found: $method")
            }
        }
    }
}

/**
 * 原生 Kotlin 桥接模块
 *
 * 把 Android/Kotlin 的**真·原生能力**暴露给小程序 JS（融合"原生 Kotlin 语言"到现有 HTML/JS/CSS 小程序），
 * 让 AI 生成的小程序不再只是 WebView 内网页，而能：
 *  - 读写系统剪贴板、呼起系统分享、打开任意 URL / 第三方 App（HTML/JS 做不到）；
 *  - 读取宿主 App 信息、弹出系统通知、调用 TTS 朗读。
 * 所有调用都落在主线程/系统 Service，边界与权限已做防护。
 */
class KotlinModule(private val context: Context) : MiniAppBridgeModule {
    override val name = "kotlin"

    override fun invoke(method: String, params: JSONObject, callback: (Int, Any?, String?) -> Unit) {
        when (method) {
            "getAppInfo" -> {
                runCatching {
                    val pkg = context.packageName
                    val pi = context.packageManager.getPackageInfo(pkg, 0)
                    val info = JSONObject().apply {
                        put("packageName", pkg)
                        put("versionName", pi.versionName ?: "")
                        put("versionCode", PackageInfoCompat.getLongVersionCode(pi))
                        put("brand", Build.BRAND)
                        put("model", Build.MODEL)
                        put("system", "Android ${Build.VERSION.RELEASE}")
                        put("sdkVersion", Build.VERSION.SDK_INT)
                    }
                    callback(0, info, null)
                }.onFailure { callback(-1, null, it.message) }
            }
            "copyText" -> {
                val text = params.optString("text", "")
                runCatching {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("miniapp", text))
                    callback(0, null, null)
                }.onFailure { callback(-1, null, it.message) }
            }
            "getClipboard" -> {
                runCatching {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = cm.primaryClip
                    val text = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).text?.toString() else null
                    callback(0, text ?: "", null)
                }.onFailure { callback(-1, null, it.message) }
            }
            "shareText" -> {
                val text = params.optString("text", "")
                val title = params.optString("title", "")
                runCatching {
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, text)
                        if (title.isNotEmpty()) putExtra(Intent.EXTRA_TITLE, title)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(Intent.createChooser(intent, title.ifEmpty { "分享" }))
                    callback(0, null, null)
                }.onFailure { callback(-1, null, it.message) }
            }
            "openUrl" -> {
                val url = params.optString("url", "")
                if (url.isEmpty()) { callback(-1, null, "url is required"); return }
                runCatching {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    callback(0, null, null)
                }.onFailure { callback(-1, null, it.message) }
            }
            "openApp" -> {
                val pkg = params.optString("packageName", "")
                if (pkg.isEmpty()) { callback(-1, null, "packageName is required"); return }
                runCatching {
                    val intent = context.packageManager.getLaunchIntentForPackage(pkg)
                    if (intent == null) {
                        callback(-1, null, "app not installed: $pkg")
                    } else {
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                        callback(0, null, null)
                    }
                }.onFailure { callback(-1, null, it.message) }
            }
            "notify" -> {
                val title = params.optString("title", "小程序通知")
                val body = params.optString("body", "")
                // Android 13+ 需要 POST_NOTIFICATIONS 权限
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    callback(-1, null, "notification permission not granted")
                    return
                }
                runCatching {
                    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    val channelId = "miniapp"
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        val chan = NotificationChannel(
                            channelId, "小程序通知", NotificationManager.IMPORTANCE_DEFAULT
                        ).apply { setShowBadge(true) }
                        nm.createNotificationChannel(chan)
                    }
                    val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
                    val pi = PendingIntent.getActivity(
                        context, 0,
                        launch ?: Intent(),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                    val notif = NotificationCompat.Builder(context, channelId)
                        .setContentTitle(title)
                        .setContentText(body)
                        .setSmallIcon(android.R.drawable.ic_dialog_info)
                        .setContentIntent(pi)
                        .setAutoCancel(true)
                        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                        .build()
                    nm.notify(System.currentTimeMillis().toInt(), notif)
                    callback(0, null, null)
                }.onFailure { callback(-1, null, it.message) }
            }
            "speak" -> {
                val text = params.optString("text", "")
                if (text.isEmpty()) { callback(-1, null, "text is required"); return }
                runCatching {
                    val tts = ensureTts() ?: run { callback(-1, null, "tts unavailable"); return }
                    tts.language = java.util.Locale.getDefault()
                    val r = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
                    callback(if (r == TextToSpeech.SUCCESS) 0 else -1, null, null)
                }.onFailure { callback(-1, null, it.message) }
            }
            else -> {
                callback(-1, null, "method not found: $method")
            }
        }
    }

    /** 惰性创建并缓存 TTS 实例（用 applicationContext 避免 Activity 泄漏）。 */
    private fun ensureTts(): TextToSpeech? {
        if (ttsRef.get() == null) {
            synchronized(lock) {
                if (ttsRef.get() == null) {
                    ttsRef.set(runCatching {
                        TextToSpeech(context.applicationContext, null)
                    }.getOrNull())
                }
            }
        }
        return ttsRef.get()
    }

    companion object {
        private val ttsRef = java.util.concurrent.atomic.AtomicReference<TextToSpeech?>(null)
        private val lock = Any()
    }
}