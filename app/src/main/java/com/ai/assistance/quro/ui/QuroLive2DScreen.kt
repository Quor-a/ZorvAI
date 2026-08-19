package com.ai.assistance.quro.ui

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONArray
import org.json.JSONObject

/**
 * Live2D 伙伴（工具箱子页面）。
 *
 * 移植自开源项目 DesktopFriends（Tosuke-sama，MIT）的 Live2D 渲染部分：
 *   - 渲染引擎沿用其 PixiJS + pixi-live2d-display 方案（WebView 内运行，与 DesktopFriends 在 Android 端用 Capacitor 一致）；
 *   - 情绪 / 说话逻辑为本项目基于标准 Cubism 参数重新实现（详见 assets/live2d/index.html）。
 * 模型默认搭载 Live2D 官方免费示例模型 Hiyori（Live2D Open Software License）。
 * 全程在设备本地运行，无需联网。
 */
@Composable
fun QuroLive2DScreen(onExitToHome: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    var status by remember { mutableStateOf("正在加载 Live2D 模型…") }
    var modelReady by remember { mutableStateOf(false) }
    var emotions by remember { mutableStateOf<List<String>>(emptyList()) }
    var debugLog by remember { mutableStateOf<String?>(null) }

    val webViewRef = remember { mutableStateOf<WebView?>(null) }
    val main = remember { Handler(Looper.getMainLooper()) }

    val bridge = remember {
        Live2dBridge(
            onReady = { emo, _ ->
                main.post {
                    modelReady = true
                    emotions = emo
                    status = "就绪 · 模型已加载"
                }
            },
            onEmotion = { name -> main.post { status = "情绪：$name" } },
            onErrorCb = { msg -> main.post { status = "加载失败：$msg" } },
            onLogCb = { msg -> main.post { debugLog = (debugLog ?: "") + msg + "\n" } },
        )
    }

    fun callJs(js: String) {
        webViewRef.value?.evaluateJavascript(js, null)
    }

    BackHandler { onExitToHome() }

    Column(Modifier.fillMaxSize().background(cs.background)) {
        SmallTopBar(
            title = "Live2D 伙伴",
            onBack = onExitToHome,
            trailing = {
                IconButton(onClick = { callJs("window.ZorvLive2D.loadModel(window.ZorvLive2D.defaultModel())") }) {
                    Icon(Icons.Filled.Refresh, "重载模型", tint = cs.onSurfaceVariant)
                }
            },
        )

        // 控制栏：情绪 chips + 说话
        Surface(color = cs.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
            LazyRow(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                items(emotions) { e ->
                    FilterChip(
                        selected = false,
                        onClick = { callJs("window.ZorvLive2D.setEmotion('$e')") },
                        label = { Text(emotionLabel(e), fontSize = 13.sp) },
                        leadingIcon = { Icon(Icons.Filled.Face, null, Modifier.size(16.dp)) },
                    )
                }
                item {
                    Button(onClick = { callJs("window.ZorvLive2D.speak('你好，我是 Zorv AI 的 Live2D 伙伴，我可以在本地陪你聊天。')") }) {
                        Text("说话", fontSize = 13.sp)
                    }
                }
            }
        }

        // Live2D 画布
        Box(Modifier.fillMaxWidth().weight(1f)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { c ->
                    createLive2DWebView(c, bridge).also { webViewRef.value = it }
                },
                onRelease = { it.destroy() },
            )
        }

        // 状态行
        Surface(color = cs.surface, modifier = Modifier.fillMaxWidth()) {
            Text(
                status,
                fontSize = 12.sp,
                color = cs.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        debugLog?.let {
            if (it.isNotBlank()) {
                Box(
                    Modifier.fillMaxWidth().heightIn(max = 96.dp).background(cs.surfaceVariant)
                        .clickable { debugLog = null },
                ) {
                    androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize().padding(8.dp)) {
                        item { Text(it, fontSize = 11.sp, color = cs.onSurfaceVariant, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace) }
                    }
                }
            }
        }
    }
}

/** 情绪英文名 -> 中文显示名（仅用于按钮文案）。 */
private fun emotionLabel(e: String): String = when (e) {
    "neutral" -> "平静"
    "happy" -> "开心"
    "angry" -> "生气"
    "sad" -> "难过"
    "surprised" -> "惊讶"
    "relaxed" -> "放松"
    "thinking" -> "思考"
    else -> e
}

// 用虚拟 https 源加载 Live2D 页面：规避 file:// 页面内 fetch 模型/纹理时的跨域(CORS)限制
//（file:// 源为 null，fetch 会被浏览器拦截）。所有资源经 shouldInterceptRequest 从 APK assets
// 提供，并带 Access-Control-Allow-Origin 头，使页面内 fetch 走同源 https，模型得以加载。
private const val LIVE2D_BASE = "https://live2d.local/live2d/"

@SuppressLint("SetJavaScriptEnabled")
private fun createLive2DWebView(context: Context, bridge: Live2dBridge): WebView {
    val wv = WebView(context.applicationContext)
    wv.settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        allowFileAccess = false
        allowContentAccess = false
        allowFileAccessFromFileURLs = false
        allowUniversalAccessFromFileURLs = false
        cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
        setRenderPriority(android.webkit.WebSettings.RenderPriority.HIGH)
    }
    wv.addJavascriptInterface(bridge, "ZorvBridge")
    wv.webViewClient = object : WebViewClient() {
        // 把虚拟 https 源的请求映射到 APK assets，并附 CORS 头，使页面内 fetch 模型/纹理可用
        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
            val url = request?.url?.toString() ?: return null
            if (url.startsWith(LIVE2D_BASE)) {
                val assetPath = url.removePrefix(LIVE2D_BASE)
                return try {
                    val `is` = context.assets.open(assetPath)
                    WebResourceResponse(
                        mimeFor(assetPath), "utf-8", 200, "OK",
                        mapOf("Access-Control-Allow-Origin" to "*"), `is`,
                    )
                } catch (_: Exception) {
                    null
                }
            }
            return null
        }

        override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
            bridge.reportError("${description ?: "加载错误"} ($errorCode)")
        }
    }
    wv.webChromeClient = object : WebChromeClient() {
        override fun onConsoleMessage(m: ConsoleMessage?): Boolean {
            m?.let { bridge.reportLog("[${it.lineNumber()}] ${it.message()}") }
            return true
        }
    }
    // 读取 index.html 并以虚拟 https 源加载；相对脚本/模型路径均解析到该源，由上面的拦截器提供
    val html = context.assets.open("live2d/index.html").bufferedReader(Charsets.UTF_8).use { it.readText() }
    wv.loadDataWithBaseURL(LIVE2D_BASE, html, "text/html", "utf-8", null)
    return wv
}

private fun mimeFor(path: String): String = when {
    path.endsWith(".html", true) -> "text/html"
    path.endsWith(".js", true) -> "application/javascript"
    path.endsWith(".json", true) -> "application/json"
    path.endsWith(".png", true) -> "image/png"
    path.endsWith(".jpg", true) || path.endsWith(".jpeg", true) -> "image/jpeg"
    path.endsWith(".moc3", true) -> "application/octet-stream"
    path.endsWith(".exp3.json", true) -> "application/json"
    path.endsWith(".motion3.json", true) -> "application/json"
    else -> "application/octet-stream"
}

/**
 * JS → 原生 桥（WebView 内 window.ZorvBridge.postMessage(json) 调用）。
 * 收到 {event, data} 消息后回调到 Compose 状态。
 */
class Live2dBridge(
    private val onReady: (emotions: List<String>, motions: Map<String, Int>) -> Unit,
    private val onEmotion: (name: String) -> Unit,
    // 用 onErrorCb/onLogCb 命名，避免与对外成员函数 reportError/reportLog 同名导致 Kotlin 类型检查递归（属性/函数同名歧义）。
    private val onErrorCb: (message: String) -> Unit,
    private val onLogCb: (message: String) -> Unit,
) {
    // 供顶层 createLive2DWebView 的 WebViewClient/WebChromeClient 回调调用
    fun reportError(message: String) = onErrorCb(message)
    fun reportLog(message: String) = onLogCb(message)
    @JavascriptInterface
    fun postMessage(json: String) {
        try {
            val o = JSONObject(json)
            val event = o.optString("event")
            val data = o.optJSONObject("data") ?: JSONObject()
            when (event) {
                "ready" -> {
                    val emo = mutableListOf<String>()
                    val arr = data.optJSONArray("emotions")
                    if (arr != null) for (i in 0 until arr.length()) emo.add(arr.getString(i))
                    val mot = mutableMapOf<String, Int>()
                    val mo = data.optJSONObject("motions")
                    if (mo != null) mo.keys().forEach { k -> mot[k] = mo.optInt(k, 0) }
                    onReady(emo, mot)
                }
                "emotion" -> onEmotion(data.optString("name", ""))
                "error" -> onErrorCb(data.optString("message", "未知错误"))
            }
        } catch (e: Exception) {
            onLogCb("bridge parse error: $e")
        }
    }
}

@Composable
private fun SmallTopBar(
    title: String,
    onBack: () -> Unit,
    trailing: @Composable () -> Unit = {},
) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().background(cs.surface).padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, Modifier.size(40.dp)) {
            Icon(Icons.Filled.ArrowBack, "返回", tint = cs.onSurface)
        }
        Text(
            title,
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
            modifier = Modifier.weight(1f).padding(start = 4.dp),
        )
        trailing()
    }
}
