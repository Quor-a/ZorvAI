package com.ai.assistance.quro.ui.genui

import android.annotation.SuppressLint
import com.zorv.genui.runtime.GenUiRuntimes
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONTokener

/**
 * GenUI 渲染引擎 —— 端上唯一的"渲染责任"（忠实移植自 GenUI A2UIRenderer）。
 *
 * 机制：
 * 1. WebView 开启 WebMessageListener（__moHost），把 MoBridge 暴露给 AI 页面；
 * 2. 生成开始：loadDataWithBaseURL("https://genui.local/", ...) → onPageFinished → document.open()
 * 3. 每个流式 chunk：document.write(chunk) —— 浏览器原生流式 HTML 解析，截断在标签中间也安全；
 * 4. 生成完成：document.close()，页面可交互。
 *
 * 安全：禁文件访问、禁 content 访问；网络仅走 bridge 的 net.proxy；外部链接交给系统浏览器。
 */
class GenUiCanvas(
    context: Context,
    private val webView: WebView,
    private val dark: Boolean = false,
    private val onFirstPaint: () -> Unit = {},
    private val onPageTitle: (String) -> Unit = {},
    private val onBridgeCall: () -> Unit = {},
    private val onWidget: (kind: String, payload: String) -> Unit = { _, _ -> },
    private val onOpenLink: (String) -> Unit = {}
) {
    private val main = Handler(Looper.getMainLooper())
    private val bridgeDispatcher = GenUiMoBridgeDispatcher(context, webView, onWidget)
    private val assetLoader = WebViewAssetLoader.Builder()
        .setDomain("genui.local")
        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
        .build()
    var writing = false
        private set
    private var pageReady = false
    /** end() 在页面未就绪时被调用：记下"想关"，等 onPageFinished 写完正文后再真正 close */
    private var pendingEnd = false
    private val pendingChunks = ArrayDeque<String>()
    private var seedHtml: String? = null
    @Volatile var writtenBytes: Long = 0
        private set

    init {
        webView.configure()
        installWebMessageListener()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun WebView.configure() {
        with(settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mediaPlaybackRequiresUserGesture = true
            loadsImagesAutomatically = true
        }
        setBackgroundColor(Color.parseColor(if (dark) "#0F1115" else "#FCFAF5"))
        isVerticalScrollBarEnabled = false
    }

    private fun installWebMessageListener() {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(
                webView, "__moHost", setOf("*"),
                object : WebViewCompat.WebMessageListener {
                    override fun onPostMessage(
                        view: WebView,
                        message: WebMessageCompat,
                        sourceOrigin: android.net.Uri,
                        isMainFrame: Boolean,
                        replyProxy: JavaScriptReplyProxy
                    ) {
                        val raw = if (message.type == WebMessageCompat.TYPE_STRING) message.data ?: "{}" else "{}"
                        onBridgeCall()
                        bridgeDispatcher.dispatch(raw, replyProxy)
                    }
                }
            )
        }
    }

    fun begin(seedHtml: String? = null) {
        main.post {
            writing = true
            pageReady = false
            pendingEnd = false
            writtenBytes = 0
            pendingChunks.clear()
            this@GenUiCanvas.seedHtml = seedHtml
            webView.stopLoading()
            webView.webViewClient = object : android.webkit.WebViewClient() {
                private var opened = false
                override fun shouldInterceptRequest(
                    view: WebView?, request: WebResourceRequest?
                ): WebResourceResponse? = request?.url?.let { assetLoader.shouldInterceptRequest(it) }

                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val url = request?.url?.toString().orEmpty()
                    if (url.startsWith("https://genui.local/")) return false
                    if (url.startsWith("http://") || url.startsWith("https://")) {
                        onOpenLink(url); return true
                    }
                    return false
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    if (opened || !writing) return
                    opened = true
                    evalJs(
                        "document.open();" +
                        "document.write(" + jsString(
                            "<!DOCTYPE html><html><head>" +
                            "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no\">" +
                            "<meta charset=\"utf-8\">" +
                            "<meta name=\"color-scheme\" content=\"light dark\">"
                        ) + ");" +
                        "document.write(" + jsString(GenUiMoBridge.JS_WRAPPER) + ");" +
                        "document.write(" + jsString(GenUiRuntimes.fontFaceCss()) + ");"
                    )
                    this@GenUiCanvas.seedHtml?.let { seed ->
                        evalJs("document.write(${jsString(seed)});")
                        writtenBytes = seed.length.toLong()
                    }
                    pageReady = true
                    while (pendingChunks.isNotEmpty()) {
                        val c = pendingChunks.removeFirstOrNull()
                        if (c != null) evalJs("document.write(${jsString(c)});")
                    }
                    if (pendingEnd) {
                        pendingEnd = false
                        doClose()
                    } else {
                        onFirstPaint()
                    }
                }
            }
            webView.loadDataWithBaseURL(
                "https://genui.local/",
                "<!DOCTYPE html><html><head></head><body></body></html>",
                "text/html", "utf-8", null
            )
        }
    }

    fun writeChunk(chunk: String) {
        if (!writing) return
        main.post {
            writtenBytes += chunk.length
            if (pageReady) evalJs("document.write(${jsString(chunk)});")
            else pendingChunks.addLast(chunk)
        }
    }

    fun end() {
        main.post {
            if (!writing) return@post
            if (pageReady) {
                doClose()
            } else {
                // 页面还没加载完（surface 屏一次性把完整 HTML 全灌进来时必然走到这里）。
                // 不能把 writing 置 false，否则 onPageFinished 会判 !writing 直接 return，正文永远不写 → 白屏。
                // 记下"想关"，等 onPageFinished 写完正文再真正 close。
                pendingEnd = true
            }
        }
    }

    /** 闭合文档、清理残留围栏、回读 <title>。仅在 pageReady 后调用。 */
    private fun doClose() {
        evalJs("document.close();")
        evalJs("try{var t=document.body.innerHTML;document.body.innerHTML=t.replace(/\\n?```\\s*$/,'');}catch(e){}")
        evalJs("document.title") { v ->
            val t = v?.trim('"').orEmpty().ifBlank { "未命名界面" }
            onPageTitle(t)
        }
        writing = false
    }

    fun replay(html: String) {
        main.post {
            writing = false
            webView.loadDataWithBaseURL("https://genui.local/", html, "text/html", "utf-8", null)
        }
    }

    fun stop() {
        writing = false
        pendingEnd = false
        main.post {
            webView.stopLoading()
            if (pageReady) {
                evalJs("try{document.write('</body></html>');document.close();}catch(e){}")
            }
        }
    }

    fun readHtml(callback: (String) -> Unit) {
        main.post {
            evalJs("document.documentElement.outerHTML") { v -> callback(decodeJsString(v)) }
        }
    }

    /**
     * 把原生组件（form/slider/list）产生的用户输入回传给 AI 页面。
     * 页面侧监听 window.addEventListener('mo:widget', e => e.data)。
     */
    fun dispatchWidgetEvent(json: String) {
        main.post {
            evalJs(
                "try{var ev=new Event('mo:widget'); ev.data=" + json + "; window.dispatchEvent(ev);}catch(e){}"
            )
        }
    }

    /**
     * 原生 Compose 层的交互事件回传 AI 页面（页面侧监听 window 的 'mo:compose' 事件）。
     */
    fun dispatchComposeAction(action: String) {
        main.post {
            evalJs(
                "try{var ev=new Event('mo:compose'); ev.data=" + action + "; window.dispatchEvent(ev);}catch(e){}"
            )
        }
    }

    /**
     * GenCanvas 原生画布的交互事件回传 AI 页面（页面侧监听 window 的 'mo:canvas' 事件）。
     */
    fun dispatchCanvasAction(action: String) {
        main.post {
            evalJs(
                "try{var ev=new Event('mo:canvas'); ev.data=" + action + "; window.dispatchEvent(ev);}catch(e){}"
            )
        }
    }

    private fun decodeJsString(v: String?): String {
        if (v == null || v == "null") return ""
        return runCatching { JSONTokener(v).nextValue() as String }.getOrDefault(v)
    }

    private fun evalJs(js: String, callback: ((String?) -> Unit)? = null) {
        webView.evaluateJavascript(js) { callback?.invoke(it) }
    }

    private fun jsString(s: String): String {
        val sb = StringBuilder(s.length + 16).append('"')
        for (c in s) when (c) {
            '"'  -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            '<'  -> sb.append("\\u003c")
            '>'  -> sb.append("\\u003e")
            else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
        }
        return sb.append('"').toString()
    }
}
