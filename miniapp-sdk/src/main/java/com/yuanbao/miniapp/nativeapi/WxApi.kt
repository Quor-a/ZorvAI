package com.yuanbao.miniapp.nativeapi

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import com.yuanbao.miniapp.js.JsEngine
import com.yuanbao.miniapp.util.Json
import com.yuanbao.miniapp.util.parseJson
import com.yuanbao.miniapp.util.writeJson
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * Host implementation of the `wx.*` API surface.
 * Everything is implemented with platform primitives only (no third-party SDK):
 * HttpURLConnection for networking, SharedPreferences for storage,
 * a plain FrameLayout overlay for toast, and our own page stack for navigation.
 */
class WxApi(
    private val context: Context,
    private val navigation: NavigationHost?,
    private val overlay: FrameLayout?
) {
    /** Bound by the logic runtime once the JS engine exists. */
    private lateinit var engine: JsEngine
    private lateinit var logicHandler: Handler

    fun attach(engine: JsEngine, logicHandler: Handler) {
        this.engine = engine
        this.logicHandler = logicHandler
    }

    interface NavigationHost {
        fun navigateTo(page: String, params: Map<String, String>)
        fun redirectTo(page: String, params: Map<String, String>)
        fun navigateBack(delta: Int)
        fun setNavigationBarTitle(title: String)
        fun currentPage(): String
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioExecutor = Executors.newCachedThreadPool()
    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences("miniapp_storage_${context.packageName}", Context.MODE_PRIVATE)
    }
    private var toastView: TextView? = null

    /** Entry point called from the JS engine (via JsBridge -> here). */
    fun dispatch(name: String, argsJson: String): String {
        val args = runCatching { parseJson(argsJson) }.getOrElse { Json.Arr() }
        val list = if (args is Json.Arr) args.items else listOf(args)
        return when (name) {
            "getSystemInfoSync", "getSystemInfo" -> systemInfo(list)
            "showToast" -> showToast(list)
            "hideToast" -> { hideToast(); "null" }
            "showLoading" -> showToast(list, loading = true)
            "hideLoading" -> { hideToast(); "null" }
            "setStorageSync" -> setStorage(list)
            "getStorageSync" -> getStorage(list)
            "removeStorageSync" -> removeStorage(list)
            "clearStorageSync" -> { prefs.edit().clear().apply(); "null" }
            "request" -> request(list)
            "navigateTo" -> navigate(list, false)
            "redirectTo" -> navigate(list, true)
            "navigateBack" -> navigateBack(list)
            "setNavigationBarTitle" -> {
                val title = list.getOrNull(0)?.asString() ?: ""
                mainHandler.post { navigation?.setNavigationBarTitle(title) }
                "null"
            }
            "nextTick" -> {
                val cb = list.getOrNull(0)
                logicHandler.post { callCallback(cb, "null") }
                "null"
            }
            "getCurrentPage" -> writeJson(Json.Str(navigation?.currentPage() ?: ""))
            "showModal" -> showModal(list)
            "showActionSheet" -> showActionSheet(list)
            "vibrateShort" -> vibrate(40)
            "vibrateLong" -> vibrate(320)
            "setClipboardData" -> setClipboard(list)
            "getClipboardData" -> getClipboard(list)
            "getNetworkType" -> networkType()
            "makePhoneCall" -> makePhoneCall(list)
            "stopPullDownRefresh" -> "null"
            "hideHomeButton" -> "null"
            else -> "null"
        }
    }

    // ------------------------------------------------------------ system
    private fun systemInfo(args: List<Json>): String {
        val metrics: DisplayMetrics = context.resources.displayMetrics
        val info = Json.obj(
            "platform" to Json.Str("android"),
            "system" to Json.Str("Android ${android.os.Build.VERSION.RELEASE}"),
            "version" to Json.Str(android.os.Build.VERSION.RELEASE),
            "brand" to Json.Str(android.os.Build.BRAND),
            "model" to Json.Str(android.os.Build.MODEL),
            "screenWidth" to Json.Num(metrics.widthPixels.toDouble()),
            "screenHeight" to Json.Num(metrics.heightPixels.toDouble()),
            "windowWidth" to Json.Num(metrics.widthPixels.toDouble()),
            "windowHeight" to Json.Num(metrics.heightPixels.toDouble()),
            "pixelRatio" to Json.Num(metrics.density.toDouble()),
            "language" to Json.Str(java.util.Locale.getDefault().language),
            "SDKVersion" to Json.Str("1.0.0")
        )
        val cb = args.firstOrNull { it is Json.Obj && it["success"] != null }
            ?.let { (it as Json.Obj)["success"] }
        if (cb != null) callCallback(cb, writeJson(info))
        return writeJson(info)
    }

    // ------------------------------------------------------------ toast
    private fun showToast(args: List<Json>, loading: Boolean = false): String {
        val opts = args.firstOrNull { it is Json.Obj } as? Json.Obj
        val title = opts?.get("title")?.asString() ?: ""
        val duration = (opts?.get("duration")?.asInt() ?: 1500).toLong()
        mainHandler.post {
            val host = overlay ?: return@post
            hideToast()
            val tv = TextView(context).apply {
                text = title
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 14f
                setPadding(32, 20, 32, 20)
                setBackgroundColor(0xCC000000.toInt())
            }
            val lp = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER or Gravity.BOTTOM; bottomMargin = 160 }
            host.addView(tv, lp)
            toastView = tv
            tv.postDelayed({ hideToast() }, if (loading) 60_000L else duration)
        }
        return "null"
    }

    private fun hideToast() {
        mainHandler.post {
            toastView?.let { (it.parent as? ViewGroup)?.removeView(it) }
            toastView = null
        }
    }

    // ------------------------------------------------------------ storage
    private fun setStorage(args: List<Json>): String {
        val key = args.getOrNull(0)?.asString() ?: return "null"
        val value = args.getOrNull(1)
        if (value != null) prefs.edit().putString(key, writeJson(value)).apply()
        return "null"
    }

    private fun getStorage(args: List<Json>): String {
        val key = args.getOrNull(0)?.asString() ?: return "null"
        val raw = prefs.getString(key, null) ?: return "null"
        return raw
    }

    private fun removeStorage(args: List<Json>): String {
        val key = args.getOrNull(0)?.asString() ?: return "null"
        prefs.edit().remove(key).apply()
        return "null"
    }

    // ------------------------------------------------------------ network
    private fun request(args: List<Json>): String {
        val opts = args.firstOrNull { it is Json.Obj } as? Json.Obj ?: return "null"
        val url = opts["url"]?.asString() ?: return "null"
        val method = (opts["method"]?.asString() ?: "GET").uppercase()
        val header = opts["header"]?.asMap() ?: emptyMap()
        val body = opts["data"]
        val success = opts["success"]
        val fail = opts["fail"]
        val complete = opts["complete"]
        val dataType = opts["dataType"]?.asString() ?: "json"

        ioExecutor.execute {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = method
                    connectTimeout = 10_000
                    readTimeout = 10_000
                    header.forEach { (k, v) -> setRequestProperty(k, v.asString()) }
                    if (method != "GET" && body != null) {
                        doOutput = true
                        setRequestProperty("Content-Type", "application/json;charset=utf-8")
                        outputStream.use { it.write(writeJson(body).toByteArray(Charsets.UTF_8)) }
                    }
                }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                val data: Json = if (dataType == "json" && text.isNotBlank()) {
                    runCatching { parseJson(text) }.getOrDefault(Json.Str(text))
                } else Json.Str(text)
                val result = Json.obj(
                    "statusCode" to Json.Num(code.toDouble()),
                    "data" to data,
                    "header" to Json.obj(),
                    "errMsg" to Json.Str("request:ok")
                )
                postCallback(success, writeJson(result))
                postCallback(complete, writeJson(result))
            } catch (t: Throwable) {
                val err = Json.obj("errMsg" to Json.Str("request:fail ${t.message ?: ""}"))
                postCallback(fail, writeJson(err))
                postCallback(complete, writeJson(err))
            } finally {
                conn?.disconnect()
            }
        }
        return "null"
    }

    // ------------------------------------------------------------ navigation
    private fun navigate(args: List<Json>, redirect: Boolean): String {
        val opts = args.firstOrNull { it is Json.Obj } as? Json.Obj
        val url = opts?.get("url")?.asString() ?: return "null"
        val (path, query) = splitUrl(url)
        mainHandler.post {
            if (redirect) navigation?.redirectTo(path, query) else navigation?.navigateTo(path, query)
        }
        return "null"
    }

    private fun navigateBack(args: List<Json>): String {
        val opts = args.firstOrNull { it is Json.Obj } as? Json.Obj
        val delta = opts?.get("delta")?.asInt() ?: 1
        mainHandler.post { navigation?.navigateBack(delta) }
        return "null"
    }

    private fun splitUrl(url: String): Pair<String, Map<String, String>> {
        val idx = url.indexOf('?')
        if (idx == -1) return url.trimStart('/') to emptyMap()
        val path = url.substring(0, idx).trimStart('/')
        val query = url.substring(idx + 1).split("&").mapNotNull {
            val p = it.split("=", limit = 2)
            if (p.size == 2) p[0] to p[1] else null
        }.toMap()
        return path to query
    }

    // ------------------------------------------------------------ callbacks
    private fun callCallback(cb: Json?, payload: String) {
        if (cb == null) return
        val id = when {
            cb is Json.Obj && cb["t"]?.asString() == "function" -> cb["v"]?.asInt() ?: -1
            cb is Json.Num -> cb.value.roundToInt()
            else -> -1
        }
        if (id >= 0) engine.invokeFunction(id, "[$payload]")
    }

    /**
     * JS callbacks must run on the logic thread that owns the engine.
     */
    // ------------------------------------------------------------ interaction / device 补齐

    /** wx.showModal：原生确认弹窗，success 回调 {confirm, cancel} */
    private fun showModal(args: List<Json>): String {
        val opts = args.firstOrNull { it is Json.Obj } as? Json.Obj
        val title = opts?.get("title")?.asString() ?: "提示"
        val content = opts?.get("content")?.asString() ?: ""
        val okText = opts?.get("confirmText")?.asString() ?: "确定"
        val cancelText = opts?.get("cancelText")?.asString() ?: "取消"
        val success = opts?.get("success")
        val complete = opts?.get("complete")
        mainHandler.post {
            runCatching {
                android.app.AlertDialog.Builder(context)
                    .setTitle(title)
                    .setMessage(content)
                    .setPositiveButton(okText) { _, _ ->
                        val r = Json.obj("confirm" to Json.Bool(true), "cancel" to Json.Bool(false),
                            "errMsg" to Json.Str("showModal:ok"))
                        postCallback(success, writeJson(r)); postCallback(complete, writeJson(r))
                    }
                    .setNegativeButton(cancelText) { _, _ ->
                        val r = Json.obj("confirm" to Json.Bool(false), "cancel" to Json.Bool(true),
                            "errMsg" to Json.Str("showModal:ok"))
                        postCallback(success, writeJson(r)); postCallback(complete, writeJson(r))
                    }
                    .setOnCancelListener {
                        val r = Json.obj("confirm" to Json.Bool(false), "cancel" to Json.Bool(true),
                            "errMsg" to Json.Str("showModal:ok"))
                        postCallback(success, writeJson(r)); postCallback(complete, writeJson(r))
                    }
                    .show()
            }
        }
        return "null"
    }

    /** wx.showActionSheet：底部选项列表，success 回调 {tapIndex} */
    private fun showActionSheet(args: List<Json>): String {
        val opts = args.firstOrNull { it is Json.Obj } as? Json.Obj
        val items = (opts?.get("itemList") as? Json.Arr)?.items?.map { it.asString() } ?: emptyList()
        val success = opts?.get("success")
        val complete = opts?.get("complete")
        mainHandler.post {
            runCatching {
                android.app.AlertDialog.Builder(context)
                    .setItems(items.toTypedArray()) { _, which ->
                        val r = Json.obj("tapIndex" to Json.Num(which.toDouble()),
                            "errMsg" to Json.Str("showActionSheet:ok"))
                        postCallback(success, writeJson(r)); postCallback(complete, writeJson(r))
                    }
                    .setOnCancelListener {
                        val r = Json.obj("errMsg" to Json.Str("showActionSheet:fail cancel"))
                        postCallback(complete, writeJson(r))
                    }
                    .show()
            }
        }
        return "null"
    }

    private fun vibrate(ms: Long): String {
        runCatching {
            val vib = context.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
            @Suppress("DEPRECATION")
            vib?.vibrate(ms)
        }
        return "null"
    }

    private fun setClipboard(args: List<Json>): String {
        val data = args.firstOrNull()?.asString() ?: return "null"
        mainHandler.post {
            runCatching {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                cm?.setPrimaryClip(android.content.ClipData.newPlainText("miniapp", data))
            }
        }
        return "null"
    }

    private fun getClipboard(args: List<Json>): String {
        val opts = args.firstOrNull { it is Json.Obj } as? Json.Obj
        val success = opts?.get("success")
        val complete = opts?.get("complete")
        mainHandler.post {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
            val text = cm?.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
            val r = Json.obj("data" to Json.Str(text), "errMsg" to Json.Str("getClipboardData:ok"))
            postCallback(success, writeJson(r)); postCallback(complete, writeJson(r))
        }
        return "null"
    }

    private fun networkType(): String {
        val type = runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
            val net = cm?.activeNetwork
            val caps = net?.let { cm.getNetworkCapabilities(it) }
            when {
                caps == null -> "none"
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "4g"
                else -> "unknown"
            }
        }.getOrDefault("unknown")
        return writeJson(Json.obj("networkType" to Json.Str(type), "errMsg" to Json.Str("getNetworkType:ok")))
    }

    private fun makePhoneCall(args: List<Json>): String {
        val opts = args.firstOrNull { it is Json.Obj } as? Json.Obj
        val number = opts?.get("phoneNumber")?.asString() ?: return "null"
        mainHandler.post {
            runCatching {
                context.startActivity(
                    android.content.Intent(android.content.Intent.ACTION_DIAL, android.net.Uri.parse("tel:$number"))
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        return "null"
    }

    private fun postCallback(cb: Json?, payload: String) {
        if (cb == null) return
        logicHandler.post { callCallback(cb, payload) }
    }

    /** Names exposed to JS as `wx.<name>(...)`. */
    companion object {
        val API_NAMES = listOf(
            "getSystemInfo", "getSystemInfoSync", "showToast", "hideToast", "showLoading", "hideLoading",
            "showModal", "showActionSheet", "vibrateShort", "vibrateLong", "setClipboardData", "getClipboardData",
            "getNetworkType", "makePhoneCall", "stopPullDownRefresh",
            "setStorageSync", "getStorageSync", "removeStorageSync", "clearStorageSync",
            "request", "navigateTo", "redirectTo", "navigateBack", "setNavigationBarTitle",
            "nextTick", "getCurrentPage"
        )
    }
}
