package com.ai.assistance.quro.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.ai.assistance.quro.plugin.PluginSetDataCallback
import com.ai.assistance.quro.plugin.QuickJsEngine
import com.ai.assistance.quro.plugin.QuickJsLogicBackend
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * 插件运行时入口屏。
 *
 * 逻辑层 = QuickJS 原生沙箱（每插件一个 JSRuntime，内存上限 + 超时中断 + 关 eval），
 * 渲染层 = WebView DOM（评审结论：默认渲染层绕开 Cax 的 License:None）。
 *
 * 数据流向：
 *   逻辑层 setData -> hostSetData(path, value) -> Kotlin onSetData -> window.RenderRuntime.applyDiff -> patch DOM
 *   渲染层事件 -> NativeBridge.callEvent -> QuickJsEngine.invokeMethod -> globalThis.__page[method](value)
 *
 * 若 libquroplugin.so 未编入（理论上不会发生，因为已 externalNativeBuild 编入），
 * 自动回退到旧的同页自包含 plugin_runtime.html，保证可运行。
 */
@Composable
fun PluginsScreen(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val store = remember { mutableMapOf<String, String>() }
    // 探测 QuickJS 原生库是否可用（触发 System.loadLibrary）
    val useQuickJs = remember { QuickJsEngine.isAvailable() }
    val backend = remember { if (useQuickJs) QuickJsLogicBackend() else null }
    // QuickJS 引擎单线程串行，避免 JSRuntime 跨线程并发
    val engineThread = remember { Executors.newSingleThreadExecutor() }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    val callback = remember(store, ctx) {
        object : PluginSetDataCallback {
            override fun onSetData(path: String, valueJson: String) {
                val wv = webViewRef ?: return
                wv.post {
                    val p = path.replace("\\", "\\\\").replace("'", "\\'")
                    val v = valueJson.replace("\\", "\\\\").replace("'", "\\'")
                    wv.evaluateJavascript("window.RenderRuntime && window.RenderRuntime.applyDiff('$p','$v')") {}
                }
            }

            override fun onHostApi(api: String, paramsJson: String): String {
                return handleHostApi(ctx, store, api, paramsJson)
            }
        }
    }

    val eventSink: (String, String?, String?) -> Unit = { method, datasetJson, valueJson ->
        if (useQuickJs && backend != null) {
            engineThread.submit { backend.invokeMethod(method, datasetJson, valueJson) }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            backend?.destroy()
            engineThread.shutdownNow()
        }
    }

    Column(Modifier.fillMaxSize()) {
        // 顶部栏
        Surface(
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp,
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose) { Icon(Icons.Filled.ArrowBack, contentDescription = "返回") }
                Text("插件运行时", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 8.dp))
                Spacer(Modifier.weight(1f))
                if (useQuickJs) {
                    Text(
                        "QuickJS 沙箱",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                }
            }
        }

        // 插件 WebView
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = true
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            webViewRef = view
                            if (useQuickJs && backend != null) {
                                // 页面已就绪：把逻辑层注入 QuickJS 沙箱（引擎单线程）
                                val logic = readAsset(context, "plugin_runtime/plugin_logic.js")
                                engineThread.submit { backend.loadPlugin(logic, callback) }
                            }
                            // 回退模式：加载的是旧 plugin_runtime.html（自包含，无需注入）
                        }
                    }
                    addJavascriptInterface(NativeBridge(context, store, eventSink), "NativeBridge")
                    if (useQuickJs) {
                        loadUrl("file:///android_asset/plugin_runtime/plugin_render.html")
                    } else {
                        loadUrl("file:///android_asset/plugin_runtime/plugin_runtime.html")
                    }
                }
            },
            onRelease = { it.destroy() },
        )
    }
}

/** 读取 assets 下的文本（插件逻辑 / 规格）。 */
private fun readAsset(context: Context, path: String): String {
    return context.assets.open(path).bufferedReader().use { it.readText() }
}

/** 宿主能力实现（my.*）：storage.get/set + ui.toast。未实现的能力返回错误 JSON。 */
private fun handleHostApi(context: Context, store: MutableMap<String, String>, api: String, paramsJson: String): String {
    return try {
        val p = JSONObject(paramsJson)
        when (api) {
            "storage.get" -> JSONObject().put("value", store[p.optString("key")]).toString()
            "storage.set" -> {
                store[p.optString("key")] = p.optString("value")
                "{\"ok\":true}"
            }
            "ui.toast" -> {
                val msg = p.optString("msg", "")
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                }
                "{\"ok\":true}"
            }
            else -> JSONObject().put("error", "unsupported api: $api").toString()
        }
    } catch (e: Throwable) {
        JSONObject().put("error", e.message).toString()
    }
}

/** 宿主能力桥 + 事件桥：插件经 my.* 调 callApi；渲染层事件经 callEvent 回传逻辑层。 */
private class NativeBridge(
    private val context: Context,
    private val store: MutableMap<String, String>,
    private val eventSink: (method: String, datasetJson: String?, valueJson: String?) -> Unit,
) {
    @JavascriptInterface
    fun callApi(api: String, paramsJson: String): String {
        return handleHostApi(context, store, api, paramsJson)
    }

    @JavascriptInterface
    fun callEvent(method: String, datasetJson: String?, valueJson: String?) {
        eventSink(method, datasetJson ?: "{}", valueJson ?: "null")
    }
}
