package com.ai.assistance.quro.core.miniapp

import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebView
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