package com.ai.assistance.quro.genui.app.render

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.ai.assistance.quro.genui.app.bridge.MoBridgeDispatcher
import com.ai.assistance.quro.genui.app.bridge.MoBridgeHost
/**
 * A2UI 渲染引擎 —— 端上唯一的"渲染责任"。
 *
 * 机制：
 * 1. WebView 开启 WebMessageListener（__moHost），把 MoBridge 暴露给 AI 的页面；
 * 2. 生成开始：loadUrl("about:blank") → onPageFinished → document.open()
 * 3. 每个流式 chunk：document.write(chunk) —— 浏览器原生流式 HTML 解析，
 *    截断在标签中间也安全（解析器自动缓冲等待后续数据）。
 * 4. 生成完成：document.close()，页面可交互。
 *
 * 安全：禁文件访问、禁 content 访问；网络仅走 bridge 的 net.proxy（AI 页面自身
 * 处于 about:blank origin，fetch 受 CORS 限制，形成天然第二道闸）。
 */
class A2UIRenderer(
    context: Context,
    private val webView: WebView,
    private val onFirstPaint: () -> Unit = {},
    private val onPageTitle: (String) -> Unit = {},
    private val onBridgeCall: (String) -> Unit = {},
    private val onWidget: (kind: String, payload: String) -> Unit = { _, _ -> }
) {
    private val main = Handler(Looper.getMainLooper())
    private var bridgeDispatcher: MoBridgeDispatcher? = null
    private val assetLoader = androidx.webkit.WebViewAssetLoader.Builder()
        .setDomain("genui.local")
        .addPathHandler(
            "/assets/",
            androidx.webkit.WebViewAssetLoader.AssetsPathHandler(context)
        )
        .build()
    var writing = false
        private set

    /** 页面是否已就绪（onPageFinished 后才能 document.write） */
    private var pageReady = false
    private val pendingChunks = java.util.ArrayDeque<String>()

    /** 续写种子：非空时，begin() 先把它写进文档，随后 chunk 直接追加在它后面 */
    private var seedHtml: String? = null

    /** 已写入的全部字节数（供壳显示与「继续写」判断） */
    @Volatile var writtenBytes: Long = 0
        private set

    init {
        webView.configure()
        bridgeDispatcher = MoBridgeDispatcher(context, webView, onBridgeCall, onWidget)
        installWebMessageListener()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun WebView.configure() {
        with(settings) {
            javaScriptEnabled = true
            domStorageEnabled = true              // AI 页面可用 localStorage（随沙箱销毁）
            allowFileAccess = false
            allowContentAccess = false
            mediaPlaybackRequiresUserGesture = true
            loadsImagesAutomatically = true
        }
        setBackgroundColor(Color.parseColor("#FCFAF5"))
        isVerticalScrollBarEnabled = false
    }

    /** 与 AI 页面的通信端口：接收 {id, api, args}，回写 {id, ok, val} */
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
                        replyProxy: androidx.webkit.JavaScriptReplyProxy
                    ) {
                        val raw = if (message.type == WebMessageCompat.TYPE_STRING) message.data ?: "{}" else "{}"
                        bridgeDispatcher?.dispatch(raw, replyProxy)
                    }
                }
            )
        }
    }

    // ---------- 生命周期：open → write×N → close ----------

    /**
     * 开始一次新的生成：重置文档进入"写入中"状态。
     * @param seedHtml 续写模式：把已产出的部分 HTML 先写回文档，chunk 追加其后。
     *                 传 null 表示全新生成。
     */
    fun begin(seedHtml: String? = null) {
        main.post {
            writing = true
            pageReady = false
            writtenBytes = 0
            pendingChunks.clear()
            this@A2UIRenderer.seedHtml = seedHtml
            webView.stopLoading()
            webView.webViewClient = object : android.webkit.WebViewClient() {
                private var opened = false

                // 本地运行时（vue/react/htm/mermaid）经 /assets/runtimes/ 提供给 AI 的页面，
                // 不走外网、不破坏 genui.local origin
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: android.webkit.WebResourceRequest?
                ): android.webkit.WebResourceResponse? =
                    request?.url?.let { assetLoader.shouldInterceptRequest(it) }

                override fun onPageFinished(view: WebView?, url: String?) {
                    if (opened || !writing) return
                    opened = true
                    // 在 genui.local 文档上打开流式通道（document.open 清空并重开）：
                    // 先写文档头 + MoBridge 声明 —— 必须先于 AI 的任何 <script>，
                    // 因为流式期间 AI 的脚本是边写边执行的。
                    // 注意：不预闭合 </head>，AI 写来的 <style>/<title>/<script src>/<body> 自然归位。
                    evalJs(
                        "document.open();" +
                        "document.write(" + jsString(
                            "<!DOCTYPE html><html><head>" +
                            "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no\">" +
                            "<meta charset=\"utf-8\">" +
                            "<meta name=\"color-scheme\" content=\"light dark\">"
                        ) + ");" +
                        "document.write(" + jsString(MoBridgeHost.JS_WRAPPER) + ");" +
                        // 离线字体先注册，AI 的 CSS 才能直接用 'Inter' / 'JetBrains Mono' / 'Space Grotesk'
                        "document.write(" + jsString(RuntimeRegistry.fontFaceCss()) + ");"
                    )
                    // 续写：把已有 HTML 写回，后续 chunk 自然接在后面
                    this@A2UIRenderer.seedHtml?.let { seed ->
                        evalJs("document.write(${jsString(seed)});")
                        writtenBytes = seed.length.toLong()
                    }
                    pageReady = true
                    // 冲掉页面加载期间到达的 chunk（保持顺序）
                    while (pendingChunks.isNotEmpty()) {
                        val c = pendingChunks.poll()
                        if (c != null) evalJs("document.write(${jsString(c)});")
                    }
                    onFirstPaint()
                }
            }
            // 空文档打底，确立 https://genui.local origin（__moHost 在此 origin 上注入）
            webView.loadDataWithBaseURL(
                "https://genui.local/",
                "<!DOCTYPE html><html><head></head><body></body></html>",
                "text/html", "utf-8", null
            )
        }
    }

    /** 注入一个流式 chunk（原始 HTML 片段，任意截断均安全） */
    fun writeChunk(chunk: String) {
        if (!writing) return
        main.post {
            writtenBytes += chunk.length
            if (pageReady) evalJs("document.write(${jsString(chunk)});")
            else pendingChunks.addLast(chunk)
        }
    }

    /** 结束生成：闭合文档，清理未闭合标签，读取 <title> 给界面栈 */
    fun end() {
        main.post {
            if (!pageReady) { writing = false; return@post }
            // 容错闭合：模型偶尔漏掉 </html> / </body>，浏览器对 document.close() 已能自愈，
            // 但显式补齐可保证「回放」时 HTML 结构完整可独立打开
            evalJs("document.close();")
            // 就地清掉模型偶尔顺手包在结尾的 Markdown 闭合围栏（```），避免它作为文本残留在画布上
            evalJs("try{var t=document.body.innerHTML;document.body.innerHTML=t.replace(/\\n?```\\s*$/,'');}catch(e){}")
            evalJs("document.title") { v ->
                val t = v?.trim('"').orEmpty().ifBlank { "未命名界面" }
                onPageTitle(t)
            }
            writing = false
        }
    }

    /** 回放栈里的历史界面（非流式，一次性加载） */
    fun replay(html: String) {
        main.post {
            writing = false
            webView.loadDataWithBaseURL("https://genui.local/", html, "text/html", "utf-8", null)
        }
    }

    /**
     * 中断当前生成，但保留已写入内容（可交互、可续写）。
     * 关键：必须 document.close()，否则浏览器认为文档仍在加载，已写部分不可交互。
     */
    fun stop() {
        writing = false
        main.post {
            webView.stopLoading()
            if (pageReady) {
                // 补齐可能被截断的闭合标签，让中断的半成品至少可用
                evalJs(
                    "try{" +
                    "document.write('</body></html>');" +
                    "document.close();" +
                    "}catch(e){}"
                )
            }
        }
    }

    /**
     * 把原生渲染层产生的交互回传给 AI 页面。
     *
     * 场景：AI 用 compose 通道画了界面，用户在端上渲染出的**真实 Compose 组件**
     * 里点了按钮 / 拖了滑杆 —— 这个事件必须回到 AI 的页面逻辑里，
     * 否则"界面活了但逻辑是死的"。派发 mo:compose 事件，AI 在页面里监听即可。
     */
    fun dispatchComposeAction(action: String) {
        main.post {
            evalJs(
                "try{window.dispatchEvent(new CustomEvent('mo:compose',{detail:" +
                    jsString(action) + "}))}catch(e){}"
            )
        }
    }

    /**
     * 把 GenCanvas 原生画布里的交互事件回传给 AI 页面。
     * 画布层是纯 Compose 渲染、不跑在 WebView 里，但它身上也可能挂着"点了这个要通知 AI"的
     * 意图（例如画布里的按钮想触发页面里的某个动作）。统一派发 mo:canvas 事件，
     * 与 mo:compose 对称，AI 在页面里 `addEventListener('mo:canvas', ...)` 即可。
     */
    fun dispatchCanvasAction(action: String) {
        main.post {
            evalJs(
                "try{window.dispatchEvent(new CustomEvent('mo:canvas',{detail:" +
                    jsString(action) + "}))}catch(e){}"
            )
        }
    }

    /**
     * 读取当前画布里的完整 HTML（供「中断后另存为一张纸」「导出」使用）。
     * 注意：必须在主线程回调，且回调参数是 JS 值的 JSON 编码字符串。
     */
    fun readHtml(callback: (String) -> Unit) {
        main.post {
            evalJs("document.documentElement.outerHTML") { v ->
                callback(decodeJsString(v))
            }
        }
    }

    /** evaluateJavascript 返回的是 JSON 编码的字符串字面量，这里解码回原始文本 */
    private fun decodeJsString(v: String?): String {
        if (v == null || v == "null") return ""
        return runCatching { org.json.JSONTokener(v).nextValue() as String }.getOrDefault(v)
    }

    // ---------- utils ----------

    private fun evalJs(js: String, callback: ((String?) -> Unit)? = null) {
        webView.evaluateJavascript(js) { callback?.invoke(it) }
    }

    /** 把任意字符串编码为安全的 JS 字符串字面量 */
    private fun jsString(s: String): String {
        val sb = StringBuilder(s.length + 16).append('"')
        for (c in s) when (c) {
            '"'  -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            '<'  -> sb.append("\\u003c")   // 防 </script> 提前闭合
            '>'  -> sb.append("\\u003e")
            else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
        }
        return sb.append('"').toString()
    }
}
