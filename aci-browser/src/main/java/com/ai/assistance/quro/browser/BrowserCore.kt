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
import java.util.concurrent.TimeUnit
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

    /**
     * 等待显示 WebView 就绪（被 Activity 注册）。
     * 用于避免 browser_open 拉起 Activity 与后续读取之间的竞态：startActivity 是异步的，
     * 若读取跑得太快、Activity 的 onCreate 还没注册 WebView，displayWv 为 null → 读到空。
     * 这里在调用线程（ACI binder 线程）轮询最多 timeoutMs，返回非 null 表示就绪。
     */
    fun awaitWebView(timeoutMs: Long = 3000): WebView? {
        if (displayWv != null) return displayWv
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (displayWv != null) return displayWv
            try { Thread.sleep(80) } catch (_: InterruptedException) { break }
        }
        return displayWv
    }

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
     * 读取当前页面完整 HTML（v1.0.12 终极修复：移除 waitThenGrab，改用「单发 evaluateJavascript +
     * 兜底链 + 一次重试」，与 browser_script 完全同机制——后者已证明在 SPA 下稳定可读）。
     * 主抓 document.documentElement.outerHTML；若为空/白板（大页面 evaluateJavascript 对
     * outerHTML 偶发返回空），回退抓 document.body.innerHTML；仍空则等 700ms 重试一次。
     * 永不静默返回空：最终兜底返回 about:blank 占位说明，便于诊断。
     */
    /**
     * 读取当前页面完整 HTML（修复 SPA 大页返回空）。
     *
     * 根因：document.documentElement.outerHTML 在新闻类 SPA 上可达 1MB+，
     * WebView evaluateJavascript 对超大返回值会静默丢弃（回调拿到空串/null），
     * 旧逻辑据此落到「空占位」兜底 → 表现为 read 返回空。
     * 修复要点：
     * 1) 在 JS 侧先把 outerHTML 切片到安全上限(~1MB)再返回，确保回调必拿到非空串；
     *    控制端仍按 150k 内联 + html_gz 处理（gzip 后约数百 KB，远小于 Binder 1MB）。
     * 2) 单次 evaluateJavascript（与 browser_script 同机制，后者在 SPA 下已证明稳定可读），
     *    去掉会偶发穿透到空占位的重试/isBlankDoc 链。
     * 3) latch.await 加 8s 超时，避免超大返回被静默丢弃时线程永久阻塞。
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
            val js = "(function(){try{var h=document.documentElement?document.documentElement.outerHTML:'';if(h.length>1000000)h=h.slice(0,1000000);return h;}catch(e){return '';}})()"
            wv.evaluateJavascript(js) { html ->
                val h = jsonUnescape(html ?: "")
                DiagBuffer.append("Read", "outerHTML len=${h.length} url=${wv.url}")
                if (h.isNotBlank()) {
                    ref.set(h)
                    latch.countDown()
                } else {
                    // 兜底：抓 body.innerHTML（同样切片），避免大页 outerHTML 失败时彻底空
                    wv.evaluateJavascript("(function(){try{var b=document.body?document.body.innerHTML:'';if(b.length>1000000)b=b.slice(0,1000000);return b;}catch(e){return '';}})()") { inner ->
                        val b = jsonUnescape(inner ?: "")
                        DiagBuffer.append("Read", "fallback body.innerHTML len=${b.length}")
                        ref.set(if (b.isNotBlank()) "<!DOCTYPE html><html><head></head><body>$b</body></html>" else "")
                        latch.countDown()
                    }
                }
            }
        }
        try { latch.await(8, TimeUnit.SECONDS) } catch (_: InterruptedException) {}
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
     * 爬虫核心（v7 新增，SPA 大页修复）：抓取当前页结构化数据 —— 标题 + 可读正文 + 出站链接。
     * 修复要点：
     * 1) 提取正文时若 article/main 为空（SPA 常见：正文在 body 下），回退到 document.body.innerText，
     *    避免「优先 article/main 但拿到空节点」导致 text 恒为空。
     * 2) 正文超 200k 字符先在 JS 侧截断，避免 evaluateJavascript 返回值上限；出站链接上限降到 200 条。
     * 3) 单次 evaluateJavascript（与 browser_script 同机制）；latch.await 加 8s 超时防永久阻塞。
     * 4) 主抓取为空/报错时，兜底抓 body 文本 + 标题 + url，绝不返回纯空结果。
     */
    fun crawlPage(): String {
        val ref = AtomicReference("")
        val latch = CountDownLatch(1)
        mainHandler.post {
            val wv = displayWv
            if (wv == null) { latch.countDown(); return@post }
            val js = """
(function(){
  try {
    var title = document.title || '';
    var url = location.href || '';
    var root = document.querySelector('article') || document.querySelector('main') || document.body;
    var text = (root ? root.innerText : '') || '';
    if (!text && document.body) text = document.body.innerText || '';
    text = (text || '').replace(/\s+/g, ' ').trim();
    if (text.length > 200000) text = text.slice(0, 200000);
    var as = document.querySelectorAll('a');
    var max = Math.min(as.length, 200);
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
            wv.evaluateJavascript(js) { res ->
                val r = jsonUnescape(res ?: "")
                DiagBuffer.append("Crawl", "len=${r.length}, err=${r.contains("\"error\"")}")
                if (r.isNotBlank() && !r.contains("\"error\"")) {
                    ref.set(r)
                    latch.countDown()
                } else {
                    // 兜底：主抓取失败/报错时直接抓 body 文本 + 标题 + url，绝不返回纯空
                    wv.evaluateJavascript("(function(){try{return JSON.stringify({url:location.href,title:document.title,text:((document.body?document.body.innerText:'').replace(/\\s+/g,' ').trim()),links:'[]',linkCount:(document.links?document.links.length:0)});}catch(e){return JSON.stringify({url:location.href,title:document.title,text:'',links:'[]',linkCount:0});}})()") { fb ->
                        DiagBuffer.append("Crawl", "fallback: len=${(fb ?: "").length}")
                        ref.set(jsonUnescape(fb ?: "{\"url\":\"\",\"title\":\"\",\"text\":\"\",\"links\":\"[]\",\"linkCount\":0}"))
                        latch.countDown()
                    }
                }
            }
        }
        try { latch.await(8, TimeUnit.SECONDS) } catch (_: InterruptedException) {}
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

    // ── 抓包（packet capture）：请求侧拦截缓冲 ──

    /** 单条被拦截的请求记录。 */
    data class CapturedRequest(
        val url: String,
        val method: String,
        val headers: String,
        val isMainFrame: Boolean,
        val time: Long
    )

    /** 请求抓包环形缓冲（线程安全；shouldInterceptRequest 在后台线程调用，故全程 synchronized）。 */
    object CaptureBuffer {
        private val list = mutableListOf<CapturedRequest>()
        private val lock = Any()
        const val MAX = 800
        @Volatile var enabled = true

        fun add(r: CapturedRequest) {
            if (!enabled) return
            synchronized(lock) {
                list.add(r)
                if (list.size > MAX) list.removeAt(0)
            }
        }
        fun snapshot(limit: Int = 200, filter: String = ""): List<CapturedRequest> = synchronized(lock) {
            val src = if (filter.isEmpty()) list else list.filter {
                it.url.contains(filter, true) || it.method.contains(filter, true) || it.headers.contains(filter, true)
            }
            src.takeLast(limit)
        }
        fun clear() = synchronized(lock) { list.clear() }
        fun size(): Int = synchronized(lock) { list.size }
        fun setOn(on: Boolean) { enabled = on; if (!on) clear() }
    }

    /** WebViewClient.shouldInterceptRequest 回调时调用，记录请求元数据（不修改响应）。 */
    fun captureRequest(url: String, method: String, headers: String, isMainFrame: Boolean) {
        CaptureBuffer.add(CapturedRequest(url, method, headers, isMainFrame, System.currentTimeMillis()))
    }

    fun setCaptureEnabled(on: Boolean) = CaptureBuffer.setOn(on)
    fun clearCapture() = CaptureBuffer.clear()
    fun isCaptureEnabled(): Boolean = CaptureBuffer.enabled
    fun getCaptureSnapshot(limit: Int = 200, filter: String = ""): List<CapturedRequest> = CaptureBuffer.snapshot(limit, filter)

    // ── 页面内查找（对应「完整功能浏览器」的 Ctrl+F）──
    @Volatile private var lastFindText: String = ""

    /** 在页面内查找文本：触发 WebView 高亮，并返回匹配数量（JS 统计 innerText 命中次数）。 */
    fun findInPage(text: String): Int {
        val ref = AtomicReference(0)
        val latch = CountDownLatch(1)
        lastFindText = text
        mainHandler.post {
            val wv = displayWv
            if (wv == null) { latch.countDown(); return@post }
            try { wv.findAllAsync(text) } catch (_: Throwable) {}
            val countJs = "(function(){try{var t=" + JSONObject.quote(text) +
                ";var s=(document.body?document.body.innerText:'');if(!t)return '0';" +
                "var i=0,idx=0;while((idx=s.toUpperCase().indexOf(t.toUpperCase(),idx))!==-1){i++;idx+=t.length;}" +
                "return String(i);}catch(e){return '0';}})()"
            wv.evaluateJavascript(countJs) { r ->
                val n = try { jsonUnescape(r ?: "0").toIntOrNull() ?: 0 } catch (_: Throwable) { 0 }
                DiagBuffer.append("Find", "text=$text count=$n")
                ref.set(n); latch.countDown()
            }
        }
        try { latch.await() } catch (_: InterruptedException) {}
        return ref.get() ?: 0
    }

    /** 跳到下一个/上一个匹配（配合 findInPage 的高亮）。 */
    fun findNext(forward: Boolean) {
        mainHandler.post { try { displayWv?.findNext(forward) } catch (_: Throwable) {} }
    }

    /** 清除查找高亮。 */
    fun clearFind() {
        mainHandler.post { try { displayWv?.clearMatches() } catch (_: Throwable) {} }
    }

    // ── 导航控制（对应「完整功能浏览器」的前进/后退/刷新）──
    fun canGoBack(): Boolean = displayWv?.canGoBack() ?: false
    fun canGoForward(): Boolean = displayWv?.canGoForward() ?: false
    fun navBack() { mainHandler.post { try { if (displayWv?.canGoBack() == true) displayWv?.goBack() } catch (_: Throwable) {} } }
    fun navForward() { mainHandler.post { try { if (displayWv?.canGoForward() == true) displayWv?.goForward() } catch (_: Throwable) {} } }
    fun navReload() { mainHandler.post { try { displayWv?.reload() } catch (_: Throwable) {} } }

    // ── 截图（对应 AI「看见」页面；保存 PNG 到应用外部存储 Pictures，返回路径，避免 Binder 大对象）──
    fun screenshot(destPath: String): String {
        val ref = AtomicReference("")
        val latch = CountDownLatch(1)
        mainHandler.post {
            val wv = displayWv
            if (wv == null) { latch.countDown(); return@post }
            try {
                val w = wv.width
                val h = wv.height
                if (w > 0 && h > 0) {
                    val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
                    val canvas = android.graphics.Canvas(bmp)
                    wv.draw(canvas)
                    val f = java.io.File(destPath)
                    f.parentFile?.mkdirs()
                    java.io.FileOutputStream(f).use { out ->
                        bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, out)
                    }
                    bmp.recycle()
                    ref.set(destPath)
                    DiagBuffer.append("Shot", "截图成功 ${w}x$h -> $destPath")
                } else {
                    DiagBuffer.append("Shot", "截图跳过：WebView 尺寸 0（${w}x$h）")
                    ref.set("")
                }
            } catch (e: Throwable) {
                DiagBuffer.append("Shot", "截图失败: ${e.message}")
                ref.set("")
            }
            latch.countDown()
        }
        try { latch.await() } catch (_: InterruptedException) {}
        return ref.get() ?: ""
    }
}
