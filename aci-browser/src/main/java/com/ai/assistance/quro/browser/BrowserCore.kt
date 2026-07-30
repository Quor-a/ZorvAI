package com.ai.assistance.quro.browser

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject

/**
 * 浏览器内核（v7 · 权限自动授权 + 引擎信息 + AI 眼睛事件通道 + 读取时序/标题修正 + 爬虫/脚本）。
 *
 * 职责：
 * 1. 持有 Activity 传来的显示 WebView 引用（供 ACI readHtml / getUrl / getTitle 使用）。
 * 2. 后台 loadUrl 操作（经主线程 Handler 切到 UI 线程）。
 * 3. 【v4 新增】注册时为 WebView 挂 WebChromeClient，对站点请求的 定位 / 相机 / 麦克风
 *    权限统一「自动授予」并写入诊断日志 —— 对应「没有权限又先网站需要权限」的场景。
 * 4. 【v4 新增】暴露引擎信息（getUserAgent / getEngineInfo）。
 * 5. 【v4 新增】AI「眼睛」事件通道：Service 在 ACI onCall 时调用 reportAiActivity()，
 *    BrowserActivity 注册的 AiEyeListener 收到后点亮底部眼睛指示灯。
 *
 * 不再自己创建/管理 WebView 实例 —— 创建和生命周期全交给 BrowserActivity（XML 布局）。
 * 这消除了 v1/v2 中「applicationContext WebView → Activity 不渲染」的根因。
 */
object BrowserCore {

    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var displayWv: WebView? = null   // Activity 的显示 WebView
    @Volatile private var appContext: Context? = null

    // ── AI「眼睛」事件通道 ──
    interface AiEyeListener {
        /** active=true 表示 AI 正在控制；message 为可读的当前动作描述。 */
        fun onAiEyeChange(active: Boolean, message: String)
    }

    @Volatile var aiEyeListener: AiEyeListener? = null
    private val eyeIdleHandler = Handler(Looper.getMainLooper())
    private val eyeResetRunnable = Runnable { aiEyeListener?.onAiEyeChange(false, "") }

    /** Service 在收到 ACI 调用时调用，点亮眼睛；4 秒无新活动自动熄灭。 */
    fun reportAiActivity(message: String) {
        mainHandler.post {
            aiEyeListener?.onAiEyeChange(true, message)
            eyeIdleHandler.removeCallbacks(eyeResetRunnable)
            eyeIdleHandler.postDelayed(eyeResetRunnable, 4000)
        }
    }

    @Synchronized
    fun init(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    /** BrowserActivity 把 XML 里的 WebView 注册过来，并挂上权限自动授权的 WebChromeClient。 */
    @Synchronized
    fun registerDisplayWebView(wv: WebView) {
        displayWv = wv
        try {
            val s = wv.settings
            s.javaScriptEnabled = true
            s.domStorageEnabled = true
            // 定位权限需要 geolocation 数据库支持
            s.setGeolocationEnabled(true)
            s.loadsImagesAutomatically = true
            // 【v1.0.10 移动端适配】让页面缩放适配手机窄屏，消除横向溢出
            s.useWideViewPort = true
            s.loadWithOverviewMode = true
            s.layoutAlgorithm = WebSettings.LayoutAlgorithm.NARROW_COLUMNS
            s.setSupportZoom(true)
            s.builtInZoomControls = true
            s.displayZoomControls = false

            wv.webChromeClient = object : WebChromeClient() {
                // 站点请求定位 → 自动授予（不弹系统授权框）
                override fun onGeolocationPermissionsShowPrompt(
                    origin: String?,
                    callback: GeolocationPermissions.Callback?
                ) {
                    callback?.invoke(origin, true, false)
                    DiagBuffer.append("Perm", "🌐 自动授权定位: ${origin ?: "?"}")
                    reportAiActivity("站点请求定位，已自动授权")
                }

                // 站点请求相机 / 麦克风等 → 自动授予
                override fun onPermissionRequest(request: PermissionRequest?) {
                    if (request == null) return
                    try {
                        val res = request.resources
                        request.grant(res)
                        DiagBuffer.append("Perm", "🎥 自动授权媒体权限: ${res.joinToString()}")
                        reportAiActivity("站点请求媒体权限，已自动授权")
                    } catch (e: Throwable) {
                        DiagBuffer.append("Perm", "⚠️ 授权失败: ${e.message}")
                        try { request.deny() } catch (_: Throwable) {}
                    }
                }

                override fun onPermissionRequestCanceled(request: PermissionRequest?) {
                    DiagBuffer.append("Perm", "↩ 媒体权限请求已取消")
                }
            }
        } catch (e: Throwable) {
            DiagBuffer.append("Core", "⚠️ 挂 WebChromeClient 失败: ${e.message}")
        }
    }

    /** Activity 销毁时注销。 */
    @Synchronized
    fun unregisterDisplayWebView() {
        displayWv = null
    }

    /** 获取当前显示 WebView（可能为 null，如果 Activity 未创建/已销毁）。 */
    fun getWebView(): WebView? = displayWv

    // ── 引擎信息（对应「看看还有哪些引擎构架的适合浏览器的都用」）──

    /** 当前 UA 串。 */
    fun getUserAgent(): String {
        val ref = AtomicReference("")
        val latch = CountDownLatch(1)
        mainHandler.post { ref.set(displayWv?.settings?.userAgentString ?: ""); latch.countDown() }
        try { latch.await() } catch (_: InterruptedException) {}
        return ref.get() ?: ""
    }

    /** 引擎诊断：当前用的是系统 WebView 还是其它内核，含版本。 */
    fun getEngineInfo(): String {
        val ref = AtomicReference("")
        val latch = CountDownLatch(1)
        mainHandler.post {
            try {
                val pkg = WebView.getCurrentWebViewPackage()
                val ver = pkg?.versionName ?: "unknown"
                val pkgName = pkg?.packageName ?: "unknown"
                ref.set(
                    "engine=SystemWebView | package=$pkgName | version=$ver | " +
                    "androidApi=${Build.VERSION.SDK_INT} | ua=${displayWv?.settings?.userAgentString ?: ""}"
                )
            } catch (e: Throwable) {
                ref.set("engineErr=${e.message}")
            }
            latch.countDown()
        }
        try { latch.await() } catch (_: InterruptedException) {}
        return ref.get() ?: ""
    }

    // ── ACI 调用入口 ──

    fun loadUrl(url: String) {
        val latch = CountDownLatch(1)
        mainHandler.post {
            displayWv?.loadUrl(url)
            latch.countDown()
        }
        try { latch.await() } catch (_: InterruptedException) {}
    }

    fun getUrl(): String? {
        val ref = AtomicReference<String?>()
        val latch = CountDownLatch(1)
        mainHandler.post { ref.set(displayWv?.url); latch.countDown() }
        try { latch.await() } catch (_: InterruptedException) {}
        return ref.get()
    }

    fun getTitle(): String? {
        val ref = AtomicReference<String?>()
        val latch = CountDownLatch(1)
        mainHandler.post {
            val wv = displayWv
            if (wv == null) {
                latch.countDown()
            } else {
                // v6：改读 document.title，比 WebView.title 更新更及时，缓解标题延迟
                wv.evaluateJavascript("document.title") { t ->
                    ref.set(jsonUnescape(t ?: ""))
                    latch.countDown()
                }
            }
        }
        try { latch.await() } catch (_: InterruptedException) {}
        return ref.get()
    }

    /**
     * 读取当前页面完整 HTML（v6 修复 Binder 溢出根因）。
     * - 先轮询 document.readyState 等待加载完成（最多 3s），避免页未加载完返回空。
     * - 抓取 outerHTML 后经 jsonUnescape 还原 evaluateJavascript 返回的 JSON 转义串。
     */
    fun readHtml(): String {
        val ref = AtomicReference("")
        val latch = CountDownLatch(1)
        mainHandler.post {
            val wv = displayWv
            if (wv == null) {
                latch.countDown()
                return@post
            }
            val start = System.currentTimeMillis()
            val grab: () -> Unit = {
                wv.evaluateJavascript("document.documentElement.outerHTML") { html ->
                    ref.set(jsonUnescape(html ?: ""))
                    latch.countDown()
                }
            }
            val poll = object : Runnable {
                override fun run() {
                    wv.evaluateJavascript("(function(){return document.readyState;})()") { state ->
                        val s = jsonUnescape(state ?: "")
                        if (s == "complete" || s == "interactive" || System.currentTimeMillis() - start >= 3000) {
                            grab()
                        } else {
                            mainHandler.postDelayed(this, 150)
                        }
                    }
                }
            }
            poll.run()
        }
        try { latch.await() } catch (_: InterruptedException) {}
        return ref.get() ?: ""
    }

    /** 把 evaluateJavascript 回调返回的 JSON 字符串字面量（带引号/转义）还原为真实字符串。 */
    private fun jsonUnescape(s: String): String {
        if (s.isEmpty()) return s
        return try {
            JSONObject("{\"_v\":$s}").getString("_v")
        } catch (_: Throwable) { s }
    }

    /**
     * 爬虫核心（v7 新增）：抓取当前页结构化数据 —— 标题 + 可读正文 + 出站链接。
     * 在页内用 DOM 提取并 JSON.stringify 返回，正文超 200k 字符先在 JS 侧截断，
     * 避免 WebView evaluateJavascript 返回值上限；出站链接上限 500 条。
     */
    fun crawlPage(): String {
        val ref = AtomicReference("")
        val latch = CountDownLatch(1)
        mainHandler.post {
            val wv = displayWv
            if (wv == null) { latch.countDown(); return@post }
            val start = System.currentTimeMillis()
            val grab: () -> Unit = {
                val js = """
(function(){
  try {
    var title = document.title || '';
    var url = location.href || '';
    var root = document.querySelector('article') || document.querySelector('main') || document.body;
    var text = (root ? root.innerText : '') || '';
    text = text.replace(/\s+/g, ' ').trim();
    if (text.length > 200000) text = text.slice(0, 200000);
    var as = document.querySelectorAll('a');
    var max = Math.min(as.length, 500);
    var links = [];
    for (var i = 0; i < max; i++) {
      var a = as[i];
      var href = a.href || '';
      var t = (a.innerText || a.textContent || '').trim();
      if (href && t) links.push({text: t, href: href});
    }
    return JSON.stringify({url:url, title:title, text:text, links:links, linkCount:as.length});
  } catch(e) { return JSON.stringify({error:String(e)}); }
})()
"""
                wv.evaluateJavascript(js) { res -> ref.set(jsonUnescape(res ?: "")); latch.countDown() }
            }
            val poll = object : Runnable {
                override fun run() {
                    wv.evaluateJavascript("(function(){return document.readyState;})()") { state ->
                        val s = jsonUnescape(state ?: "")
                        if (s == "complete" || s == "interactive" || System.currentTimeMillis() - start >= 3000) grab()
                        else mainHandler.postDelayed(this, 150)
                    }
                }
            }
            poll.run()
        }
        try { latch.await() } catch (_: InterruptedException) {}
        return ref.get() ?: ""
    }

    /** 在当前页面执行任意 JavaScript 并返回结果（v7 脚本能力）。 */
    fun evalScript(code: String): String {
        val ref = AtomicReference("")
        val latch = CountDownLatch(1)
        mainHandler.post {
            val wv = displayWv
            if (wv == null) { latch.countDown(); return@post }
            wv.evaluateJavascript(code) { res -> ref.set(jsonUnescape(res ?: "")); latch.countDown() }
        }
        try { latch.await() } catch (_: InterruptedException) {}
        return ref.get() ?: ""
    }
}
