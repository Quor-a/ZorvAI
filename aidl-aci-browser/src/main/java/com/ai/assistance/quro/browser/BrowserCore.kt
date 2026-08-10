package com.ai.assistance.quro.browser

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.ConsoleMessage
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
 * WebView 生命周期（v1.0.12-read 修复）：为避免「Activity 被系统回收 → displayWv 置空 →
 * 所有 ACI 读能力(browser_read/crawl/script)全部 500 无活动页面」的硬伤，WebView 改为
 * 进程级常驻 —— 由 BrowserCore 以 Application context 创建并持有，BrowserActivity 只负责把
 * 同一个实例挂载/卸载到布局。Activity 销毁时仅 detach，不销毁、不注销，故 ACI 读取不再依赖
 * Activity 是否在前台。
 */
object BrowserCore {

    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var displayWv: WebView? = null   // 当前接入的显示 WebView（进程级常驻，Activity 销毁不清空）
    @Volatile private var heldWv: WebView? = null       // 进程级常驻 WebView（Activity 挂载/卸载同一实例）
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
        heldWv = wv
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

                // 页面 console.* 输出经原生 onConsoleMessage 钩取 → 写入 ConsoleBuffer（对应 browser_console 能力）
                override fun onConsoleMessage(message: ConsoleMessage?): Boolean {
                    if (message != null) {
                        reportConsole(
                            message.messageLevel().name.lowercase(),
                            message.message() ?: "",
                            message.sourceId() ?: "",
                            message.lineNumber()
                        )
                    }
                    return true
                }
            }
        } catch (e: Throwable) {
            DiagBuffer.append("Core", "⚠️ 挂 WebChromeClient 失败: ${e.message}")
        }
    }

    /**
     * Activity 销毁时调用。历史实现会清空 displayWv，导致 Activity 被系统回收后
     * 所有 ACI 读能力(browser_read/crawl/script)全部 500「无活动页面」。
     * 新设计 WebView 进程级常驻(heldWv)：Activity 销毁只 detach 不销毁，displayWv 保留，
     * 故此处不再清空。保留签名仅为兼容旧调用点；真正的释放随进程退出。
     */
    @Synchronized
    fun unregisterDisplayWebView() {
        if (heldWv == null) displayWv = null
    }

    /** 获取当前显示 WebView（可能为 null，如果 WebView 尚未创建）。 */
    fun getWebView(): WebView? = displayWv

    /**
     * 获取进程级常驻 WebView：不存在则用 Application context 创建（仅一次），并设为当前 displayWv。
     * 由 BrowserActivity 在 onCreate 调用以挂载到布局；Activity 销毁后仍常驻，保证 ACI 读能力可用。
     */
    @Synchronized
    fun getOrCreateWebView(context: Context): WebView {
        if (heldWv == null) {
            heldWv = WebView(context.applicationContext).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
            }
        }
        displayWv = heldWv
        return heldWv!!
    }

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

    // ── 页面就绪闸门（供 browser_open 等待 onPageFinished，杜绝「launched=true 但页面没加载」）──
    @Volatile private var lastFinishedUrl: String? = null
    @Volatile private var lastFinishedTime: Long = 0
    private val pageReadyLock = Any()
    @Volatile private var pendingLatch: CountDownLatch? = null

    /** WebView onPageFinished 回调：记录最近完成页，并解除 browser_open 的就绪等待。 */
    fun notifyPageFinished(url: String?) {
        lastFinishedUrl = url
        lastFinishedTime = System.currentTimeMillis()
        synchronized(pageReadyLock) {
            pendingLatch?.countDown()
            pendingLatch = null
        }
    }

    /**
     * 武装就绪闸门（browser_open 触发 loadUrl 前调用）。
     * 若目标页在 3s 内刚已完成加载（Activity 已自载，或重定向已完成），直接视为就绪，避免无谓的 15s 阻塞。
     */
    fun armPageReady(targetUrl: String?) {
        synchronized(pageReadyLock) {
            if (targetUrl != null && lastFinishedUrl == targetUrl && System.currentTimeMillis() - lastFinishedTime < 3000) {
                pendingLatch = null
            } else {
                pendingLatch = CountDownLatch(1)
            }
        }
    }

    /** 等待页面加载完成（onPageFinished），超时返回 false（不抛异常、不卡死 binder）。 */
    fun awaitPageReady(timeoutMs: Long): Boolean {
        val latch = synchronized(pageReadyLock) { pendingLatch } ?: return true
        return try { latch.await(timeoutMs, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { false }
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

    /**
     * 读取「精简 DOM」：清洗后的当前页结构（对应元宝建议的「HTML 回传·精简模式」）。
     * 去 script/style/link/meta/noscript，给可交互元素打 data-ai-id，标记 data-in-viewport，
     * 供 AI 控制方直接理解页面结构而非啃原始巨串。返回字符串上限切片到 ~1MB。
     */
    fun readCleanDom(): String {
        val ref = AtomicReference("")
        val latch = CountDownLatch(1)
        mainHandler.post {
            val wv = displayWv
            if (wv == null) { latch.countDown(); return@post }
            val js = """
(function(){
  try {
    var clone = document.documentElement.cloneNode(true);
    var junk = clone.querySelectorAll('script,style,link,meta,noscript');
    for (var k = 0; k < junk.length; k++) { junk[k].parentNode.removeChild(junk[k]); }
    var vw = window.innerWidth, vh = window.innerHeight;
    var els = clone.querySelectorAll('a,button,input,select,textarea,video,audio,img');
    for (var i = 0; i < els.length; i++) {
      var el = els[i];
      el.setAttribute('data-ai-id', 'el_' + i);
      var r = el.getBoundingClientRect();
      if (r.top >= 0 && r.left >= 0 && r.bottom <= vh && r.right <= vw) el.setAttribute('data-in-viewport', 'true');
    }
    var h = clone.outerHTML;
    if (h.length > 1000000) h = h.slice(0, 1000000);
    return h;
  } catch(e) { return ''; }
})()
"""
            wv.evaluateJavascript(js) { res ->
                val h = jsonUnescape(res ?: "")
                DiagBuffer.append("ReadClean", "len=${h.length} url=${wv.url}")
                ref.set(h); latch.countDown()
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

    /**
     * 扫描当前页媒体/文件资源（video/audio/source/a[download]/img），返回结构化列表：
     * {count, resources:[{tag,src,type,text,page_url, current_time?,duration?,paused?,poster?,download?}]}
     * src 已解析为绝对 URL —— 控制方可直接拿 video/audio 直链播放，或拿 a[download] 下载链接。
     * （对应元宝建议的「文件/视频回传」核心能力：页面资源变控制方可播放/可下载的具体资源）
     */
    fun scanResources(): String {
        val ref = AtomicReference("")
        val latch = CountDownLatch(1)
        mainHandler.post {
            val wv = displayWv
            if (wv == null) { latch.countDown(); return@post }
            val js = """
(function(){
  try {
    var out = [];
    var nodes = document.querySelectorAll('video,audio,source,a[download],img');
    for (var i = 0; i < nodes.length; i++) {
      var n = nodes[i];
      var tag = (n.tagName || '').toLowerCase();
      var src = n.currentSrc || n.src || n.href || n.getAttribute('src') || '';
      if (!src) { var p = n.parentNode; if (p) src = p.currentSrc || p.src || ''; }
      try { if (src) src = new URL(src, location.href).href; } catch(e) {}
      var type = n.getAttribute('type') || '';
      var text = ((n.innerText || n.getAttribute('title') || n.getAttribute('alt') || '').trim()).slice(0, 120);
      var o = {tag: tag, src: src, type: type, text: text, page_url: location.href};
      if (tag === 'video' || tag === 'audio') {
        o.current_time = (n.currentTime || 0);
        o.duration = (isFinite(n.duration) ? n.duration : 0) || 0;
        o.paused = n.paused;
        var poster = n.getAttribute('poster') || '';
        try { if (poster) poster = new URL(poster, location.href).href; } catch(e) {}
        o.poster = poster;
      }
      if (tag === 'a') o.download = n.getAttribute('download') || '';
      out.push(o);
    }
    return JSON.stringify({count: out.length, resources: out});
  } catch(e) { return JSON.stringify({error: String(e), count: 0, resources: []}); }
})()
"""
            wv.evaluateJavascript(js) { res ->
                val r = jsonUnescape(res ?: "")
                DiagBuffer.append("Media", "len=${r.length}")
                ref.set(r); latch.countDown()
            }
        }
        try { latch.await(8, TimeUnit.SECONDS) } catch (_: InterruptedException) {}
        return ref.get() ?: ""
    }

    // ── 元素树 / 操作 / 条件等待（agentic 浏览器：稳定ID标注 + 按ID操作 + wait_for）──

    /** 注入到 DOM 的稳定元素 ID 属性名（queryElements 写入，actionOnElement/waitFor 复用）。 */
    private const val EID_ATTR = "data-aci-eid"

    /**
     * 扫描当前页可交互元素，注入稳定 ID（data-aci-eid），返回元素树 JSON。
     * 覆盖：a / button / input / select / textarea / [role=button] / [tabindex] / [onclick]。
     * 每个元素返回：id / tag / type / text / value / href / placeholder / name / x / y / w / h / visible。
     * 注入的 data-aci-eid 会保留在 DOM 中，供后续 browser_action / browser_wait 按 id 命中。
     */
    fun queryElements(): String {
        val ref = AtomicReference("")
        val latch = CountDownLatch(1)
        mainHandler.post {
            val wv = displayWv
            if (wv == null) { latch.countDown(); return@post }
            val js = """
(function(){
  try {
    var SEL = 'a,button,input,select,textarea,[role=button],[tabindex],[onclick]';
    var els = document.querySelectorAll(SEL);
    var out = [];
    for (var i = 0; i < els.length; i++) {
      var el = els[i];
      var tag = (el.tagName || '').toLowerCase();
      var type = el.getAttribute('type') || tag;
      var id = 'e' + i;
      try { el.setAttribute('data-aci-eid', id); } catch(e) {}
      var r = el.getBoundingClientRect();
      var w = r.width, h = r.height;
      var visible = (w > 0 && h > 0 && el.offsetParent !== null);
      var text = (el.innerText || el.textContent || '').trim().slice(0, 200);
      var val = (tag === 'input' || tag === 'textarea') ? (el.value || '') : '';
      var href = (tag === 'a') ? (el.href || '') : '';
      var ph = el.getAttribute('placeholder') || '';
      var name = el.getAttribute('name') || el.getAttribute('id') || el.getAttribute('aria-label') || '';
      out.push({id:id, tag:tag, type:type, text:text, value:val, href:href, placeholder:ph, name:name, x:Math.round(r.left), y:Math.round(r.top), w:Math.round(w), h:Math.round(h), visible:visible});
    }
    return JSON.stringify({count: out.length, elements: out});
  } catch(e) { return JSON.stringify({error: String(e), count:0, elements: []}); }
})()
"""
            wv.evaluateJavascript(js) { res ->
                val r = jsonUnescape(res ?: "")
                DiagBuffer.append("Elements", "len=${r.length}")
                ref.set(r); latch.countDown()
            }
        }
        try { latch.await(8, TimeUnit.SECONDS) } catch (_: InterruptedException) {}
        return ref.get() ?: ""
    }

    /**
     * 按稳定 ID 执行操作原语：click / type / scroll_to / select。
     * - click：el.click()（链接会触发导航）
     * - type：用原生 value setter（兼容 React/Vue 受控输入）写入后派发 input/change
     * - scroll_to：scrollIntoView 居中
     * - select：设置 value 并派发 change
     * 返回 JSON {ok, op, error?}
     */
    fun actionOnElement(id: String, op: String, arg: String): String {
        val ref = AtomicReference("")
        val latch = CountDownLatch(1)
        mainHandler.post {
            val wv = displayWv
            if (wv == null) { latch.countDown(); return@post }
            val js = """
(function(){
  try {
    var el = document.querySelector('[data-aci-eid=' + __ID__ + ']');
    if (!el) return JSON.stringify({ok:false, error:'element not found'});
    var op = __OP__;
    var arg = __ARG__;
    if (op === 'click') {
      el.click();
      return JSON.stringify({ok:true, op:'click'});
    } else if (op === 'type') {
      var proto = Object.getPrototypeOf(el);
      var desc = Object.getOwnPropertyDescriptor(proto, 'value');
      if (desc && desc.set) { desc.set.call(el, arg); } else { el.value = arg; }
      el.dispatchEvent(new Event('input', {bubbles:true}));
      el.dispatchEvent(new Event('change', {bubbles:true}));
      return JSON.stringify({ok:true, op:'type', len:arg.length});
    } else if (op === 'scroll_to') {
      el.scrollIntoView({block:'center', inline:'center'});
      return JSON.stringify({ok:true, op:'scroll_to'});
    } else if (op === 'select') {
      el.value = arg;
      el.dispatchEvent(new Event('change', {bubbles:true}));
      return JSON.stringify({ok:true, op:'select', value:arg});
    } else {
      return JSON.stringify({ok:false, error:'unknown op: ' + op});
    }
  } catch(e) { return JSON.stringify({ok:false, error:String(e)}); }
})()
""".replace("__ID__", JSONObject.quote(id))
 .replace("__OP__", JSONObject.quote(op))
 .replace("__ARG__", JSONObject.quote(arg))
            wv.evaluateJavascript(js) { res ->
                val r = jsonUnescape(res ?: "")
                DiagBuffer.append("Action", "op=$op id=$id r=$r")
                ref.set(r); latch.countDown()
            }
        }
        try { latch.await(8, TimeUnit.SECONDS) } catch (_: InterruptedException) {}
        return ref.get() ?: ""
    }

    /**
     * ui_snapshot：当前可视区域内可见可交互元素的「屏幕坐标」快照。
     * 供控制端 clickText / clickResourceId 语义点击解析锚点坐标（与 tap 同一坐标空间：屏幕绝对像素）。
     *
     * 返回 JSON：{ ok:true, elements:[ {text, resId, left, top, right, bottom} ] }（屏幕 px 整数）。
     * 坐标系换算：getBoundingClientRect()（CSS px，视口相对）→ 乘 scale(=WebView宽/CSS视口宽) → 加 WebView 屏幕偏移。
     */
    fun uiSnapshot(): String {
        val ref = AtomicReference("{\"ok\":false,\"error\":\"no webview\"}")
        val latch = CountDownLatch(1)
        mainHandler.post {
            val wv = displayWv
            if (wv == null) { latch.countDown(); return@post }
            try {
                val loc = IntArray(2)
                wv.getLocationOnScreen(loc)
                val js = """
(function(){
  try {
    var SEL = 'a,button,input,select,textarea,label,img,[role=button],[role=link],[tabindex],[onclick],h1,h2,h3,h4,[data-aci-click]';
    var vw = window.innerWidth, vh = window.innerHeight;
    var els = document.querySelectorAll(SEL);
    var out = [];
    var cap = 400;
    for (var i = 0; i < els.length && out.length < cap; i++) {
      var el = els[i];
      var r = el.getBoundingClientRect();
      if (!(r.width > 0 && r.height > 0)) continue;
      if (r.bottom <= 0 || r.top >= vh || r.right <= 0 || r.left >= vw) continue; // 视口外跳过
      var text = ((el.innerText || el.textContent || '').trim()).slice(0, 200);
      var resId = el.id || '';
      out.push({text: text, resId: resId, left: Math.round(r.left), top: Math.round(r.top), right: Math.round(r.right), bottom: Math.round(r.bottom)});
    }
    return JSON.stringify({clientWidth: document.documentElement.clientWidth, vw: vw, vh: vh, elements: out});
  } catch(e) { return JSON.stringify({error: String(e), elements: []}); }
})()
"""
                wv.evaluateJavascript(js) { res ->
                    val raw = jsonUnescape(res ?: "")
                    try {
                        val jo = org.json.JSONObject(raw)
                        val cw = jo.optDouble("clientWidth", wv.width.toDouble())
                        val scale = if (cw > 0) wv.width.toDouble() / cw else 1.0
                        val arr = jo.optJSONArray("elements") ?: org.json.JSONArray()
                        val els = org.json.JSONArray()
                        for (k in 0 until arr.length()) {
                            val e = arr.getJSONObject(k)
                            val o = org.json.JSONObject()
                            o.put("text", e.optString("text", ""))
                            o.put("resId", e.optString("resId", ""))
                            o.put("left", (loc[0] + e.optDouble("left", 0.0) * scale).toInt())
                            o.put("top", (loc[1] + e.optDouble("top", 0.0) * scale).toInt())
                            o.put("right", (loc[0] + e.optDouble("right", 0.0) * scale).toInt())
                            o.put("bottom", (loc[1] + e.optDouble("bottom", 0.0) * scale).toInt())
                            els.put(o)
                        }
                        val out = org.json.JSONObject()
                        out.put("ok", true)
                        out.put("elements", els)
                        ref.set(out.toString())
                        DiagBuffer.append("UiSnap", "ok count=${els.length()}")
                    } catch (ex: Throwable) {
                        DiagBuffer.append("UiSnap", "parse fail: ${ex.message}")
                        ref.set("{\"ok\":false,\"error\":\"parse:${ex.message}\"}")
                    }
                    latch.countDown()
                }
            } catch (e: Throwable) {
                DiagBuffer.append("UiSnap", "fail: ${e.message}")
                ref.set("{\"ok\":false,\"error\":\"${e.message}\"}")
                latch.countDown()
            }
        }
        try { latch.await(8, TimeUnit.SECONDS) } catch (_: InterruptedException) {}
        return ref.get() ?: "{\"ok\":false}"
    }

    /**
     * 按 CSS 选择器查询 DOM 元素（对应元宝 TermBrowser 的 dom 命令）。
     * 返回匹配元素的索引/标签/文本/值/链接/id/class/位置/可见性，便于 AI 直接按选择器理解/定位元素。
     */
    fun queryBySelector(selector: String): String {
        val ref = AtomicReference("")
        val latch = CountDownLatch(1)
        mainHandler.post {
            val wv = displayWv
            if (wv == null) { latch.countDown(); return@post }
            val js = """
(function(){
  try {
    var SEL = __SEL__;
    var els = document.querySelectorAll(SEL);
    var out = [];
    for (var i = 0; i < els.length; i++) {
      var el = els[i];
      var r = el.getBoundingClientRect();
      var tag = (el.tagName || '').toLowerCase();
      var text = ((el.innerText || el.textContent || '').trim()).slice(0, 200);
      var val = (tag === 'input' || tag === 'textarea') ? (el.value || '') : '';
      var href = (tag === 'a') ? (el.href || '') : '';
      out.push({index:i, tag:tag, text:text, value:val, href:href, id:el.id||'', cls:(typeof el.className==='string'?el.className:''), x:Math.round(r.left), y:Math.round(r.top), w:Math.round(r.width), h:Math.round(r.height), visible:(r.width>0&&r.height>0&&el.offsetParent!==null)});
    }
    return JSON.stringify({count: out.length, matches: out});
  } catch(e) { return JSON.stringify({error: String(e), count:0, matches: []}); }
})()
""".replace("__SEL__", JSONObject.quote(selector))
            wv.evaluateJavascript(js) { res ->
                val r = jsonUnescape(res ?: "")
                DiagBuffer.append("QuerySel", "sel=$selector len=${r.length}")
                ref.set(r); latch.countDown()
            }
        }
        try { latch.await(8, TimeUnit.SECONDS) } catch (_: InterruptedException) {}
        return ref.get() ?: ""
    }

    /**
     * 按 CSS 选择器直接操作元素（对应元宝 dom+click/text；不依赖 browser_elements 注入的 data-aci-eid）。
     * 用于页面未注入稳定ID、或调用方更想用选择器精确定位的场景。op 同 actionOnElement。
     */
    fun actionBySelector(selector: String, op: String, arg: String): String {
        val ref = AtomicReference("")
        val latch = CountDownLatch(1)
        mainHandler.post {
            val wv = displayWv
            if (wv == null) { latch.countDown(); return@post }
            val js = """
(function(){
  try {
    var el = document.querySelector(__SEL__);
    if (!el) return JSON.stringify({ok:false, error:'element not found for selector'});
    var op = __OP__;
    var arg = __ARG__;
    if (op === 'click') {
      el.click();
      return JSON.stringify({ok:true, op:'click'});
    } else if (op === 'type') {
      var proto = Object.getPrototypeOf(el);
      var desc = Object.getOwnPropertyDescriptor(proto, 'value');
      if (desc && desc.set) { desc.set.call(el, arg); } else { el.value = arg; }
      el.dispatchEvent(new Event('input', {bubbles:true}));
      el.dispatchEvent(new Event('change', {bubbles:true}));
      return JSON.stringify({ok:true, op:'type', len:arg.length});
    } else if (op === 'scroll_to') {
      el.scrollIntoView({block:'center', inline:'center'});
      return JSON.stringify({ok:true, op:'scroll_to'});
    } else if (op === 'select') {
      el.value = arg;
      el.dispatchEvent(new Event('change', {bubbles:true}));
      return JSON.stringify({ok:true, op:'select', value:arg});
    } else {
      return JSON.stringify({ok:false, error:'unknown op: ' + op});
    }
  } catch(e) { return JSON.stringify({ok:false, error:String(e)}); }
})()
""".replace("__SEL__", JSONObject.quote(selector))
 .replace("__OP__", JSONObject.quote(op))
 .replace("__ARG__", JSONObject.quote(arg))
            wv.evaluateJavascript(js) { res ->
                val r = jsonUnescape(res ?: "")
                DiagBuffer.append("ActionSel", "op=$op sel=$selector r=$r")
                ref.set(r); latch.countDown()
            }
        }
        try { latch.await(8, TimeUnit.SECONDS) } catch (_: InterruptedException) {}
        return ref.get() ?: ""
    }

    /** 在主线程同步执行一段 JS（供 waitFor 轮询复用），返回 JS 求值结果字符串。 */
    private fun evalOnMain(js: String): String {
        val ref = AtomicReference("")
        val latch = CountDownLatch(1)
        mainHandler.post {
            val wv = displayWv
            if (wv == null) { latch.countDown(); return@post }
            wv.evaluateJavascript(js) { res -> ref.set(jsonUnescape(res ?: "")); latch.countDown() }
        }
        try { latch.await(8, TimeUnit.SECONDS) } catch (_: InterruptedException) {}
        return ref.get() ?: ""
    }

    /** 一次性给页面打桩 XHR/fetch 计数器，用于 network_idle 判定（在 main 线程执行）。 */
    private fun ensureNetInstrumentation() {
        val js = """
if (!window.__aciNetInstalled) {
  window.__aciNet = 0;
  window.__aciNetInstalled = true;
  var _open = XMLHttpRequest.prototype.open;
  XMLHttpRequest.prototype.open = function(){ window.__aciNet++; var x=this; this.addEventListener('loadend', function(){ window.__aciNet = Math.max(0, window.__aciNet-1); }); return _open.apply(this, arguments); };
  var _fetch = window.fetch ? window.fetch.bind(window) : null;
  if (_fetch) {
    window.fetch = function(){ window.__aciNet++; return _fetch.apply(window, arguments).then(function(r){ window.__aciNet = Math.max(0, window.__aciNet-1); return r; }, function(e){ window.__aciNet = Math.max(0, window.__aciNet-1); throw e; }); };
  }
}
"""
        evalOnMain(js)
    }

    /** 构造 wait_for 条件检查 JS（返回 {satisfied, error?}）。 */
    private fun buildWaitJs(cond: String, targetId: String, arg: String): String {
        return """
(function(){
  try {
    var cond = __COND__;
    if (cond === 'network_idle') {
      var ready = (document.readyState === 'complete');
      var active = (window.__aciNet || 0);
      return JSON.stringify({satisfied: ready && active === 0});
    }
    var el = document.querySelector('[data-aci-eid=' + __ID__ + ']');
    if (cond === 'hidden') {
      var hidden = !el || (el.getBoundingClientRect().width <= 0 && el.getBoundingClientRect().height <= 0) || el.offsetParent === null;
      return JSON.stringify({satisfied: hidden});
    }
    if (!el) return JSON.stringify({satisfied:false});
    if (cond === 'visible') {
      var vis = el.getBoundingClientRect().width > 0 && el.getBoundingClientRect().height > 0 && el.offsetParent !== null;
      return JSON.stringify({satisfied: vis});
    }
    if (cond === 'text_contains') {
      var t = (el.innerText || el.textContent || '').trim();
      return JSON.stringify({satisfied: t.indexOf(__ARG__) !== -1});
    }
    return JSON.stringify({satisfied:false});
  } catch(e){ return JSON.stringify({satisfied:false, error:String(e)}); }
})()
""".replace("__COND__", JSONObject.quote(cond))
 .replace("__ID__", JSONObject.quote(targetId))
 .replace("__ARG__", JSONObject.quote(arg))
    }

    /**
     * 条件等待引擎：轮询 DOM/网络状态直到条件达成或超时。
     * cond 支持：visible / hidden / text_contains（目标元素来自 browser_elements 的 id）/ network_idle。
     * 在调用线程（binder）轮询，每次检查 post 到主线程，间隔 250ms，避免主线程压力。
     * 返回 JSON {ok, cond, waited_ms, reason?}
     */
    fun waitFor(cond: String, targetId: String, arg: String, timeoutMs: Long): String {
        if (cond == "network_idle") ensureNetInstrumentation()
        val start = System.currentTimeMillis()
        val deadline = start + timeoutMs
        val checkJs = buildWaitJs(cond, targetId, arg)
        while (System.currentTimeMillis() < deadline) {
            val r = evalOnMain(checkJs)
            val sat = try { JSONObject(r).optBoolean("satisfied", false) } catch (_: Throwable) { false }
            if (sat) {
                return "{\"ok\":true,\"cond\":$cond,\"waited_ms\":${System.currentTimeMillis() - start}}"
            }
            try { Thread.sleep(250) } catch (_: InterruptedException) { break }
        }
        return "{\"ok\":false,\"cond\":$cond,\"timeout_ms\":$timeoutMs,\"reason\":\"condition not met within timeout\"}"
    }

    // ── 页面快照 / 回滚（agentic：状态快照与回滚）──

    data class PageSnapshot(val id: String, val url: String, val title: String, val html: String, val time: Long)

    /** 页面快照环形存储（按 label 覆盖；restore = 导航回快照记录的 URL）。 */
    object SnapshotStore {
        private val map = LinkedHashMap<String, PageSnapshot>()
        private val lock = Any()
        const val MAX = 20
        @Volatile var enabled = true

        fun save(label: String, url: String, title: String, html: String): String {
            val id = if (label.isNotEmpty()) label else "snap_${System.currentTimeMillis()}"
            synchronized(lock) {
                map[id] = PageSnapshot(id, url, title, html, System.currentTimeMillis())
                while (map.size > MAX) map.remove(map.keys.first())
            }
            return id
        }
        fun list(): List<PageSnapshot> = synchronized(lock) { map.values.toList() }
        fun get(id: String): PageSnapshot? = synchronized(lock) { map[id] }
        fun clear() = synchronized(lock) { map.clear() }
    }

    /** 采集当前页 url/title/html 并存入快照（在调用线程采集，内部各读取均主线程安全）。 */
    fun snapshotPage(label: String): String {
        val url = getUrl() ?: ""
        val title = getTitle() ?: ""
        val html = readHtml()
        return SnapshotStore.save(label, url, title, html)
    }
    fun listSnapshots(): List<PageSnapshot> = SnapshotStore.list()
    /** 回滚：导航回快照记录的 URL（DOM 状态无法逐字节恢复，回退到同 URL 重载）。 */
    fun restoreSnapshot(id: String): Boolean {
        val s = SnapshotStore.get(id) ?: return false
        loadUrl(s.url)
        return true
    }

    // ── 页面事件总线（agentic：页面变化主动推送 / 记录）──

    data class PageEvent(val type: String, val url: String, val time: Long)

    /** 页面事件环形缓冲（由 BrowserActivity 的 WebViewClient 生命周期回调写入）。 */
    object EventBuffer {
        private val list = mutableListOf<PageEvent>()
        private val lock = Any()
        const val MAX = 500
        @Volatile var enabled = true
        fun add(type: String, url: String) {
            if (!enabled) return
            synchronized(lock) {
                list.add(PageEvent(type, url, System.currentTimeMillis()))
                if (list.size > MAX) list.removeAt(0)
            }
        }
        fun snapshot(limit: Int = 100): List<PageEvent> = synchronized(lock) { list.takeLast(limit) }
        fun clear() = synchronized(lock) { list.clear() }
    }

    fun reportPageEvent(type: String, url: String) = EventBuffer.add(type, url)
    fun getPageEvents(limit: Int = 100): List<PageEvent> = EventBuffer.snapshot(limit)
    fun clearPageEvents() = EventBuffer.clear()

    // ── 操作审计日志（agentic：ACI 调用审计）──

    data class AuditEntry(val cap: String, val params: String, val ok: Boolean, val time: Long)

    /** ACI 调用审计环形缓冲（每次 onCall 入口写入一条）。 */
    object AuditBuffer {
        private val list = mutableListOf<AuditEntry>()
        private val lock = Any()
        const val MAX = 500
        @Volatile var enabled = true
        fun add(cap: String, params: String, ok: Boolean) {
            if (!enabled) return
            synchronized(lock) {
                list.add(AuditEntry(cap, params, ok, System.currentTimeMillis()))
                if (list.size > MAX) list.removeAt(0)
            }
        }
        fun snapshot(limit: Int = 100): List<AuditEntry> = synchronized(lock) { list.takeLast(limit) }
        fun clear() = synchronized(lock) { list.clear() }
    }

    fun audit(cap: String, params: String, ok: Boolean) = AuditBuffer.add(cap, params, ok)
    fun getAuditLog(limit: Int = 100): List<AuditEntry> = AuditBuffer.snapshot(limit)
    fun clearAudit() = AuditBuffer.clear()

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

    // ── 控制台日志（console.log 捕获：WebChromeClient.onConsoleMessage 原生钩取）──

    /** 单条页面 console 日志。 */
    data class ConsoleEntry(val level: String, val text: String, val source: String, val line: Int, val time: Long)

    /** 控制台日志环形缓冲（由 WebChromeClient.onConsoleMessage 写入，UI 线程回调）。 */
    object ConsoleBuffer {
        private val list = mutableListOf<ConsoleEntry>()
        private val lock = Any()
        const val MAX = 1000
        @Volatile var enabled = true

        fun add(level: String, text: String, source: String, line: Int) {
            if (!enabled) return
            synchronized(lock) {
                list.add(ConsoleEntry(level, text, source, line, System.currentTimeMillis()))
                if (list.size > MAX) list.removeAt(0)
            }
        }
        fun snapshot(limit: Int = 200, filter: String = ""): List<ConsoleEntry> = synchronized(lock) {
            val src = if (filter.isEmpty()) list else list.filter {
                it.text.contains(filter, true) || it.level.contains(filter, true) || it.source.contains(filter, true)
            }
            src.takeLast(limit)
        }
        fun clear() = synchronized(lock) { list.clear() }
        fun size(): Int = synchronized(lock) { list.size }
        fun setOn(on: Boolean) { enabled = on; if (!on) clear() }
    }

    /** WebChromeClient.onConsoleMessage 回调入口：捕获页面 console.* 输出。 */
    fun reportConsole(level: String, text: String, source: String, line: Int) = ConsoleBuffer.add(level, text, source, line)
    fun setConsoleEnabled(on: Boolean) = ConsoleBuffer.setOn(on)
    fun clearConsole() = ConsoleBuffer.clear()
    fun isConsoleEnabled(): Boolean = ConsoleBuffer.enabled
    fun getConsoleSnapshot(limit: Int = 200, filter: String = ""): List<ConsoleEntry> = ConsoleBuffer.snapshot(limit, filter)

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
    fun canGoBack(): Boolean {
        val ref = AtomicReference(false)
        val latch = CountDownLatch(1)
        mainHandler.post { ref.set(displayWv?.canGoBack() ?: false); latch.countDown() }
        try { latch.await() } catch (_: InterruptedException) {}
        return ref.get()
    }
    fun canGoForward(): Boolean {
        val ref = AtomicReference(false)
        val latch = CountDownLatch(1)
        mainHandler.post { ref.set(displayWv?.canGoForward() ?: false); latch.countDown() }
        try { latch.await() } catch (_: InterruptedException) {}
        return ref.get()
    }
    fun navBack() { mainHandler.post { try { if (displayWv?.canGoBack() == true) displayWv?.goBack() } catch (_: Throwable) {} } }
    fun navForward() { mainHandler.post { try { if (displayWv?.canGoForward() == true) displayWv?.goForward() } catch (_: Throwable) {} } }
    fun navReload() { mainHandler.post { try { displayWv?.reload() } catch (_: Throwable) {} } }

    // ── 虚拟鼠标（坐标级：tap/dblclick/right/down/up/drag/scroll/move，覆盖无稳定ID/无选择器的元素与画布）──

    /** 构造一次点按/按下/抬起事件（12 参 obtain 返回非 null；系统 WebView 将触摸事件按触摸处理，右键为尽力而为）。 */
    private fun obtainTap(downTime: Long, eventTime: Long, action: Int, x: Float, y: Float, buttonState: Int): MotionEvent {
        return MotionEvent.obtain(downTime, eventTime, action, x, y, 1.0f, 1.0f, 0, 0.0f, 0.0f, 0, 0)
    }

    /**
     * 在页面指定「屏幕绝对坐标」模拟鼠标动作。坐标会按 WebView 在屏幕上的位置换算成视图本地坐标后派发。
     * action: move(悬停) / click / dblclick / right / down / up / drag / scroll
     * 返回 JSON {ok, action, x, y, error?}
     */
    fun mouseAction(action: String, x: Int, y: Int, dx: Int, dy: Int, button: String): String {
        val ref = AtomicReference("{\"ok\":false,\"error\":\"no webview\"}")
        val latch = CountDownLatch(1)
        mainHandler.post {
            val wv = displayWv
            if (wv == null) { latch.countDown(); return@post }
            try {
                val loc = IntArray(2)
                wv.getLocationOnScreen(loc)
                val lx = (x - loc[0]).toFloat()
                val ly = (y - loc[1]).toFloat()
                val btnState = when (button) {
                    "right" -> MotionEvent.BUTTON_SECONDARY
                    "middle" -> MotionEvent.BUTTON_TERTIARY
                    else -> MotionEvent.BUTTON_PRIMARY
                }
                when (action) {
                    "move" -> {
                        // 悬停移动用 generic motion（无需按钮）
                        val e = MotionEvent.obtain(SystemClock.uptimeMillis(), SystemClock.uptimeMillis(), MotionEvent.ACTION_HOVER_MOVE, lx, ly, 0.0f, 0.0f, 0, 0.0f, 0.0f, 0, 0)
                        wv.dispatchGenericMotionEvent(e); e.recycle()
                    }
                    "scroll" -> {
                        wv.scrollBy(dx, dy)
                    }
                    "drag" -> {
                        var t = SystemClock.uptimeMillis()
                        var e = obtainTap(t, t, MotionEvent.ACTION_DOWN, lx, ly, btnState); wv.dispatchTouchEvent(e); e.recycle()
                        t += 16
                        e = obtainTap(t, t, MotionEvent.ACTION_MOVE, lx + dx, ly + dy, btnState); wv.dispatchTouchEvent(e); e.recycle()
                        t += 16
                        e = obtainTap(t, t, MotionEvent.ACTION_UP, lx + dx, ly + dy, btnState); wv.dispatchTouchEvent(e); e.recycle()
                    }
                    "down" -> { val t = SystemClock.uptimeMillis(); val e = obtainTap(t, t, MotionEvent.ACTION_DOWN, lx, ly, btnState); wv.dispatchTouchEvent(e); e.recycle() }
                    "up" -> { val t = SystemClock.uptimeMillis(); val e = obtainTap(t, t, MotionEvent.ACTION_UP, lx, ly, btnState); wv.dispatchTouchEvent(e); e.recycle() }
                    "right" -> {
                        var t = SystemClock.uptimeMillis()
                        var e = obtainTap(t, t, MotionEvent.ACTION_DOWN, lx, ly, btnState); wv.dispatchTouchEvent(e); e.recycle()
                        t += 16
                        e = obtainTap(t, t, MotionEvent.ACTION_UP, lx, ly, btnState); wv.dispatchTouchEvent(e); e.recycle()
                    }
                    "dblclick" -> {
                        for (i in 0..1) {
                            var t = SystemClock.uptimeMillis()
                            var e = obtainTap(t, t, MotionEvent.ACTION_DOWN, lx, ly, btnState); wv.dispatchTouchEvent(e); e.recycle()
                            t += 16
                            e = obtainTap(t, t, MotionEvent.ACTION_UP, lx, ly, btnState); wv.dispatchTouchEvent(e); e.recycle()
                        }
                    }
                    else -> { // click（默认）
                        var t = SystemClock.uptimeMillis()
                        var e = obtainTap(t, t, MotionEvent.ACTION_DOWN, lx, ly, btnState); wv.dispatchTouchEvent(e); e.recycle()
                        t += 16
                        e = obtainTap(t, t, MotionEvent.ACTION_UP, lx, ly, btnState); wv.dispatchTouchEvent(e); e.recycle()
                    }
                }
                DiagBuffer.append("Mouse", "action=$action x=$x y=$y lx=$lx ly=$ly btn=$button")
                ref.set("{\"ok\":true,\"action\":\"$action\",\"x\":$x,\"y\":$y}")
            } catch (e: Throwable) {
                DiagBuffer.append("Mouse", "action=$action 失败: ${e.message}")
                ref.set("{\"ok\":false,\"error\":\"${e.message}\"}")
            }
            latch.countDown()
        }
        try { latch.await(8, TimeUnit.SECONDS) } catch (_: InterruptedException) {}
        return ref.get() ?: "{\"ok\":false}"
    }

    // ── 轻量多标签（单引擎；标签 = URL 记录 + 切换重载，真·并行隔离属架构级改造 deferred）──
    data class Tab(val id: String, val url: String, var title: String)

    /** 轻量多标签管理：单 WebView，标签仅记录 URL + 当前激活态；切换 = 重新 loadUrl。 */
    object TabManager {
        private val map = LinkedHashMap<String, Tab>()
        private val lock = Any()
        const val MAX = 20
        @Volatile var activeId: String? = null

        fun open(url: String, title: String): Tab {
            val id = "tab_${System.currentTimeMillis()}"
            synchronized(lock) {
                map[id] = Tab(id, url, title)
                while (map.size > MAX) map.remove(map.keys.first())
                activeId = id
            }
            return map[id]!!
        }
        fun list(): List<Tab> = synchronized(lock) { map.values.toList() }
        fun active(): Tab? = synchronized(lock) { activeId?.let { map[it] } }
        fun switch(id: String): Tab? = synchronized(lock) {
            val t = map[id] ?: return null
            activeId = id
            t
        }
        fun close(id: String): Boolean = synchronized(lock) {
            val removed = map.remove(id) != null
            if (removed && activeId == id) activeId = map.keys.lastOrNull()
            removed
        }
        fun clear() = synchronized(lock) { map.clear(); activeId = null }
    }

    fun openTab(url: String, title: String = ""): Tab = TabManager.open(url, title)
    fun listTabs(): List<Tab> = TabManager.list()
    fun activeTab(): Tab? = TabManager.active()
    fun switchTab(id: String): Tab? = TabManager.switch(id)
    fun closeTab(id: String): Boolean = TabManager.close(id)
    fun clearTabs() = TabManager.clear()

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
