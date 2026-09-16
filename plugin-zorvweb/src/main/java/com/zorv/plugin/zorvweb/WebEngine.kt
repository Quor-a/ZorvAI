package com.zorv.plugin.zorvweb

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

/**
 * ZorvWeb 网页引擎（无头 + 可选挂载）。
 *
 * 从 ZorvBrowser 的 `BrowserCore` 改写而来，三处关键差异：
 *
 * 1. **不再依赖 Activity**：原实现等 Activity 把 XML 里的 WebView 注册进来（`registerDisplayWebView`），
 *    Activity 被回收就读不到页面。现在引擎自己用 Application Context 建 WebView 并常驻，
 *    默认无头可用；插件界面打开时把同一个 WebView **挂载**到界面容器，关闭时只摘除不销毁
 *    （所以关掉窗口后 AI 仍能继续读这个页面）。
 * 2. **不再阻塞线程**：原实现用 `CountDownLatch` 在 binder 线程上等主线程回调。插件跑在宿主
 *    协程里，全部改为挂起函数 —— 主线程只做 WebView 操作，等待交给协程，不会卡 UI。
 * 3. **每个 tab 一个 WebView**：多标签是真的多实例，不是复用同一个页面。
 *
 * 原版那些用血换来的 JS 修复全部保留：大页 outerHTML 切片防 evaluateJavascript 静默丢包、
 * SPA 兜底链、爬虫 article/main → body 回退、React/Vue 受控输入的原生 value setter 写值等。
 */
internal object WebEngine {

    private val main = Handler(Looper.getMainLooper())

    @Volatile private var appCtx: Context? = null
    /** 插件界面容器（打开界面时非空）。为 null = 纯无头运行。 */
    @Volatile private var host: ViewGroup? = null
    private var desktopMode = false
    private var defaultUa: String = ""

    /** 只在主线程访问。 */
    private val tabs = mutableListOf<Tab>()
    private var active = 0
    private val seqGen = AtomicInteger(0)

    private class Tab(val seq: Int, val wv: WebView) {
        @Volatile var awaiting = false
        @Volatile var lastUrl: String? = null
        @Volatile var lastError: String? = null
        @Volatile var lastTitle: String = ""
    }

    fun init(ctx: Context) {
        if (appCtx == null) appCtx = ctx.applicationContext
    }

    // ══════════════════════════ 线程与等待基建 ══════════════════════════

    private suspend fun <T> onMain(block: () -> T): T = suspendCancellableCoroutine<T> { cont ->
        main.post {
            try {
                cont.resumeWith(Result.success(block()))
            } catch (t: Throwable) {
                cont.resumeWith(Result.failure(t))
            }
        }
    }

    /** 主线程执行 JS 并等回调（超时返回空串，绝不永久挂住调用方）。 */
    private suspend fun evalJs(tab: Tab, js: String, timeoutMs: Long = 8000): String =
        withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<String> { cont ->
                main.post {
                    val ok = runCatching {
                        tab.wv.evaluateJavascript(js) { res ->
                            cont.resumeWith(Result.success(unescape(res)))
                        }
                    }
                    if (ok.isFailure) cont.resumeWith(Result.success(""))
                }
            }
        } ?: ""

    private fun unescape(s: String?): String {
        if (s.isNullOrEmpty()) return ""
        return runCatching { JSONObject("{\"_v\":$s}").getString("_v") }.getOrDefault(s)
    }

    // ══════════════════════════ 建页与挂载 ══════════════════════════

    private fun configure(wv: WebView) {
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadsImagesAutomatically = true
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            setGeolocationEnabled(true)
            // 无头也必须能跑 JS 定时器：关窗口时不会调用 onPause（见 detach）
        }
        defaultUa = runCatching { wv.settings.userAgentString }.getOrDefault("")

        wv.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                Buffers.Events.add("started", url ?: "")
            }

            override fun onPageFinished(view: WebView, url: String?) {
                tabOf(view)?.apply { awaiting = false; lastUrl = url }
                Buffers.Events.add("finished", url ?: "")
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true) {
                    tabOf(view)?.apply {
                        awaiting = false
                        lastError = error?.description?.toString() ?: "unknown"
                    }
                    Buffers.Events.add("error", request.url?.toString() ?: "")
                }
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest?): WebResourceResponse? {
                if (request != null) {
                    Buffers.Capture.add(
                        Buffers.CapturedRequest(
                            url = request.url?.toString() ?: "",
                            method = request.method ?: "",
                            headers = request.requestHeaders.entries.joinToString("; ") { "${it.key}: ${it.value}" },
                            isMainFrame = request.isForMainFrame,
                            time = System.currentTimeMillis(),
                        )
                    )
                }
                return null
            }
        }

        wv.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage?): Boolean {
                if (message != null) {
                    Buffers.Console.add(
                        message.messageLevel().name.lowercase(),
                        message.message() ?: "",
                        message.sourceId() ?: "",
                        message.lineNumber(),
                    )
                }
                return true
            }

            override fun onGeolocationPermissionsShowPrompt(
                origin: String?, callback: GeolocationPermissions.Callback?,
            ) {
                callback?.invoke(origin, true, false)
            }

            override fun onPermissionRequest(request: PermissionRequest?) {
                runCatching { request?.grant(request.resources) }
            }

            override fun onReceivedTitle(view: WebView, title: String?) {
                tabOf(view)?.lastTitle = title ?: ""
            }
        }
    }

    private fun tabOf(wv: WebView): Tab? = tabs.firstOrNull { it.wv === wv }

    /** 主线程：新建 tab 并设为当前。 */
    private fun newTab(url: String?): Tab {
        val ctx = appCtx ?: throw IllegalStateException("网页引擎未初始化")
        val wv = WebView(ctx)
        configure(wv)
        val t = Tab(seqGen.incrementAndGet(), wv)
        tabs.add(t)
        active = tabs.size - 1
        mountActive()
        if (url != null) {
            t.awaiting = true
            t.wv.loadUrl(url)
        }
        return t
    }

    /** 主线程：把当前 tab 的 WebView 挂到界面容器（无头时不做事）。 */
    private fun mountActive() {
        val h = host ?: return
        val t = tabs.getOrNull(active) ?: return
        if (t.wv.parent === h) return
        h.removeAllViews()
        (t.wv.parent as? ViewGroup)?.removeView(t.wv)
        runCatching { h.addView(t.wv) }
    }

    /** 界面打开：挂载当前页（没有就建一个）。 */
    suspend fun attach(container: ViewGroup) = onMain {
        host = container
        if (tabs.isEmpty()) newTab(null) else mountActive()
        Unit
    }

    /**
     * 界面关闭：只把 WebView 从窗口摘下来，**不调用 onPause、不销毁**。
     * 否则 JS 定时器会被暂停、页面状态丢失，关掉窗口后 AI 就读不到这个页面了。
     */
    suspend fun detach() = onMain {
        host = null
        tabs.forEach { t -> (t.wv.parent as? ViewGroup)?.removeView(t.wv) }
        Unit
    }

    // ══════════════════════════ 基础操作 ══════════════════════════

    /** 规范化用户输入：裸域名补 https，非 http(s) 一律拒绝（file:// 等不安全）。 */
    private fun normalizeUrl(raw: String): String? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        val lowered = s.lowercase()
        return when {
            lowered.startsWith("http://") || lowered.startsWith("https://") -> s
            lowered.startsWith("about:") -> s
            "://" in s -> null
            else -> "https://$s"
        }
    }

    private suspend fun activeTabOrNull(): Tab? = onMain { tabs.getOrNull(active) }

    suspend fun open(rawUrl: String, waitMs: Long, desktop: Boolean, newTabFlag: Boolean): String {
        val url = normalizeUrl(rawUrl) ?: return errJson("只支持 http/https 地址：$rawUrl")
        onMain {
            val t = if (newTabFlag || tabs.isEmpty()) newTab(null) else tabs[active]
            if (desktop != desktopMode) {
                desktopMode = desktop
                applyUa()
            }
            t.awaiting = true
            t.lastError = null
            t.wv.loadUrl(url)
            Unit
        }

        val t0 = System.currentTimeMillis()
        while (System.currentTimeMillis() - t0 < waitMs) {
            val done = onMain { tabs.getOrNull(active)?.awaiting != true }
            if (done) break
            delay(120)
        }
        return onMain {
            val t = tabs.getOrNull(active) ?: return@onMain errJson("没有活动页面")
            JSONObject().apply {
                put("ok", true)
                put("url", t.wv.url ?: url)
                put("title", t.lastTitle)
                put("waited_ms", System.currentTimeMillis() - t0)
                put("loading", t.awaiting)
                t.lastError?.let { put("error", it) }
            }.toString()
        }
    }

    private fun applyUa() {
        val ua = if (desktopMode) {
            defaultUa.replace("Mobile", "").replace("Android", "X11; Linux x86_64").replace("wv", "")
        } else defaultUa
        tabs.forEach { runCatching { it.wv.settings.userAgentString = ua } }
    }

    suspend fun eval(code: String): String {
        val t = activeTabOrNull() ?: return "ERROR: 还没有打开任何页面，请先 web_open"
        return evalJs(t, code)
    }

    /** 当前页完整 HTML（大页在 JS 侧切片到 1MB，避免 evaluateJavascript 静默丢包）。 */
    suspend fun readHtml(clean: Boolean): String {
        val t = activeTabOrNull() ?: return "ERROR: 还没有打开任何页面，请先 web_open"
        if (clean) return readClean(t)

        val js = "(function(){try{var h=document.documentElement?document.documentElement.outerHTML:'';" +
            "if(h.length>1000000)h=h.slice(0,1000000);return h;}catch(e){return '';}})()"
        val html = evalJs(t, js)
        if (html.isNotBlank()) return html

        // 兜底：大页 outerHTML 有时会失败，改抓 body.innerHTML
        val inner = evalJs(t, "(function(){try{var b=document.body?document.body.innerHTML:'';" +
            "if(b.length>1000000)b=b.slice(0,1000000);return b;}catch(e){return '';}})()")
        return if (inner.isNotBlank()) "<!DOCTYPE html><html><head></head><body>$inner</body></html>" else ""
    }

    /** 精简 DOM：去 script/style/link/meta/noscript，给可交互元素打 data-ai-id 与 data-in-viewport。 */
    private suspend fun readClean(t: Tab): String {
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
        return evalJs(t, js)
    }

    /** 结构化抓取：标题 + 可读正文 + 出站链接（article/main 为空时回退 body）。 */
    suspend fun crawl(): String {
        val t = activeTabOrNull() ?: return errJson("还没有打开任何页面，请先 web_open")
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
        val r = evalJs(t, js)
        if (r.isNotBlank() && !r.contains("\"error\"")) return r
        // 兜底：主抓取报错时至少给 url/标题/正文
        val fb = evalJs(
            t,
            "(function(){try{return JSON.stringify({url:location.href,title:document.title," +
                "text:((document.body?document.body.innerText:'').replace(/\\s+/g,' ').trim()),links:[],linkCount:0});}" +
                "catch(e){return JSON.stringify({url:'',title:'',text:'',links:[],linkCount:0});}})()"
        )
        return fb.ifBlank { errJson("抓取失败") }
    }

    /** 可交互元素树 + 稳定 ID 注入（供 web_elements 的 act 复用）。 */
    suspend fun elements(): String {
        val t = activeTabOrNull() ?: return errJson("还没有打开任何页面，请先 web_open")
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
        return evalJs(t, js)
    }

    /** 按稳定 ID 操作：click / type / scroll_to / select。 */
    suspend fun actById(id: String, op: String, arg: String): String {
        val t = activeTabOrNull() ?: return errJson("还没有打开任何页面，请先 web_open")
        return actionJs(t, "[data-aci-eid=" + JSONObject.quote(id) + "]", op, arg)
    }

    /** 按 CSS 选择器查询。 */
    suspend fun query(selector: String): String {
        val t = activeTabOrNull() ?: return errJson("还没有打开任何页面，请先 web_open")
        val js = """
(function(){
  try {
    var els = document.querySelectorAll(__SEL__);
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
        return evalJs(t, js)
    }

    suspend fun actBySelector(selector: String, op: String, arg: String): String {
        val t = activeTabOrNull() ?: return errJson("还没有打开任何页面，请先 web_open")
        return actionJs(t, JSONObject.quote(selector), op, arg)
    }

    /** click/type/select/scroll_to 的共用实现。React/Vue 受控输入必须走原生 value setter。 */
    private suspend fun actionJs(t: Tab, selectorJs: String, op: String, arg: String): String {
        val js = """
(function(){
  try {
    var el = document.querySelector($selectorJs);
    if (!el) return JSON.stringify({ok:false, error:'element not found'});
    var op = __OP__;
    var arg = __ARG__;
    if (op === 'click') { el.click(); return JSON.stringify({ok:true, op:'click'}); }
    else if (op === 'type') {
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
    }
    return JSON.stringify({ok:false, error:'unknown op: ' + op});
  } catch(e) { return JSON.stringify({ok:false, error:String(e)}); }
})()
""".replace("__OP__", JSONObject.quote(op)).replace("__ARG__", JSONObject.quote(arg))
        return evalJs(t, js)
    }

    /** 条件等待：visible / hidden / text_contains（按 elements 的 id）/ network_idle。 */
    suspend fun waitFor(cond: String, targetId: String, arg: String, timeoutMs: Long): String {
        val t = activeTabOrNull() ?: return errJson("还没有打开任何页面，请先 web_open")
        if (cond == "network_idle") {
            // 给页面打桩 XHR/fetch 计数器
            evalJs(
                t,
                "if(!window.__zNetInst){window.__zNet=0;window.__zNetInst=true;" +
                    "var _o=XMLHttpRequest.prototype.open;XMLHttpRequest.prototype.open=function(){window.__zNet++;" +
                    "var x=this;this.addEventListener('loadend',function(){window.__zNet=Math.max(0,window.__zNet-1);});return _o.apply(this,arguments);};" +
                    "var _f=window.fetch?window.fetch.bind(window):null;if(_f){window.fetch=function(){window.__zNet++;" +
                    "return _f.apply(window,arguments).then(function(r){window.__zNet=Math.max(0,window.__zNet-1);return r;}," +
                    "function(e){window.__zNet=Math.max(0,window.__zNet-1);throw e;});};}}"
            )
        }
        val checkJs = """
(function(){
  try {
    var cond = __COND__;
    if (cond === 'network_idle') {
      return JSON.stringify({satisfied: (document.readyState === 'complete') && ((window.__zNet || 0) === 0)});
    }
    var el = document.querySelector('[data-aci-eid=' + __ID__ + ']');
    if (cond === 'hidden') {
      var hidden = !el || (el.getBoundingClientRect().width <= 0 && el.getBoundingClientRect().height <= 0) || el.offsetParent === null;
      return JSON.stringify({satisfied: hidden});
    }
    if (!el) return JSON.stringify({satisfied:false});
    if (cond === 'visible') {
      return JSON.stringify({satisfied: (el.getBoundingClientRect().width > 0 && el.getBoundingClientRect().height > 0 && el.offsetParent !== null)});
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

        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            val r = evalJs(t, checkJs, 3000)
            val sat = runCatching { JSONObject(r).optBoolean("satisfied", false) }.getOrDefault(false)
            if (sat) {
                return JSONObject().put("ok", true).put("cond", cond)
                    .put("waited_ms", System.currentTimeMillis() - start).toString()
            }
            delay(250)
        }
        return JSONObject().put("ok", false).put("cond", cond)
            .put("timeout_ms", timeoutMs).put("reason", "condition not met within timeout").toString()
    }

    /** 页面内查找：返回命中数量（同时触发 WebView 原生的高亮）。 */
    suspend fun findInPage(text: String): String {
        val t = activeTabOrNull() ?: return errJson("还没有打开任何页面，请先 web_open")
        onMain { runCatching { t.wv.findAllAsync(text) }; Unit }
        val js = "(function(){try{var t=" + JSONObject.quote(text) +
            ";var s=(document.body?document.body.innerText:'');if(!t)return '0';" +
            "var i=0,idx=0;while((idx=s.toUpperCase().indexOf(t.toUpperCase(),idx))!==-1){i++;idx+=t.length;}" +
            "return String(i);}catch(e){return '0';}})()"
        val n = evalJs(t, js).toIntOrNull() ?: 0
        return JSONObject().put("ok", true).put("text", text).put("matches", n).toString()
    }

    /** 媒体 / 下载资源扫描：video/audio/source/a[download]/img，src 已转绝对地址。 */
    suspend fun media(): String {
        val t = activeTabOrNull() ?: return errJson("还没有打开任何页面，请先 web_open")
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
        return evalJs(t, js)
    }

    /** 导航控制：back / forward / reload / stop。 */
    suspend fun nav(cmd: String): String = onMain {
        val t = tabs.getOrNull(active) ?: return@onMain errJson("没有活动页面")
        when (cmd.lowercase()) {
            "back" -> if (t.wv.canGoBack()) { t.awaiting = true; t.wv.goBack() }
            "forward" -> if (t.wv.canGoForward()) { t.awaiting = true; t.wv.goForward() }
            "reload" -> { t.awaiting = true; t.wv.reload() }
            "stop" -> { t.awaiting = false; t.wv.stopLoading() }
            else -> return@onMain errJson("未知导航指令: $cmd（可用 back/forward/reload/stop）")
        }
        JSONObject().put("ok", true).put("cmd", cmd)
            .put("url", t.wv.url ?: "").put("can_back", t.wv.canGoBack()).put("can_forward", t.wv.canGoForward())
            .toString()
    }

    // ══════════════════════════ 控制台日志 ══════════════════════════

    /**
     * 页面 console.* 输出。原版 browser_console 能力。
     * action=snapshot 读取（可按 filter 过滤）/ clear 清空。
     */
    suspend fun consoleLog(action: String, filter: String, limit: Int): String {
        if (action.equals("clear", true)) {
            Buffers.Console.clear()
            return JSONObject().put("ok", true).put("cleared", true).toString()
        }
        val n = if (limit > 0) limit else 100
        val arr = JSONArray()
        Buffers.Console.snapshot(n, filter).forEach { e ->
            arr.put(
                JSONObject().put("level", e.level).put("text", e.text)
                    .put("source", e.source).put("line", e.line).put("time", e.time)
            )
        }
        return JSONObject().put("count", arr.length()).put("filter", filter)
            .put("entries", arr).toString()
    }

    /** 引擎信息：内核包名/版本/UA/API。 */
    suspend fun info(): String = onMain {
        val base = JSONObject()
        runCatching {
            val pkg = WebView.getCurrentWebViewPackage()
            base.put("engine", "SystemWebView")
            base.put("package", pkg?.packageName ?: "unknown")
            base.put("version", pkg?.versionName ?: "unknown")
        }
        val t = tabs.getOrNull(active)
        base.put("android_api", android.os.Build.VERSION.SDK_INT)
        base.put("ua", runCatching { t?.wv?.settings?.userAgentString ?: defaultUa }.getOrDefault(""))
        base.put("desktop_mode", desktopMode)
        base.put("url", t?.wv?.url ?: "")
        base.put("title", t?.lastTitle ?: "")
        base.put("js_enabled", t?.wv?.settings?.javaScriptEnabled ?: true)
        base.put("open_tabs", tabs.size)
        base.toString()
    }

    // ══════════════════════════ 多标签 ══════════════════════════

    suspend fun tabsList(): String = onMain {
        val arr = JSONArray()
        tabs.forEachIndexed { i, t ->
            arr.put(
                JSONObject()
                    .put("index", i)
                    .put("seq", t.seq)
                    .put("active", i == active)
                    .put("url", t.wv.url ?: "")
                    .put("title", t.lastTitle)
                    .put("loading", t.awaiting)
                    .put("can_back", t.wv.canGoBack())
            )
        }
        JSONObject().put("count", tabs.size).put("active", active).put("tabs", arr).toString()
    }

    /** 新建 / 切换 / 关闭标签。index 为负数或越界时不动作，返回当前状态。 */
    suspend fun tabsAction(action: String, index: Int, url: String): String {
        onMain {
            when (action.lowercase()) {
                "new" -> {
                    val nu = normalizeUrl(url)
                    val t = newTab(null)
                    if (nu != null) {
                        t.awaiting = true
                        t.wv.loadUrl(nu)
                    }
                }
                "switch" -> if (index in tabs.indices) {
                    active = index
                    mountActive()
                }
                "close" -> if (index in tabs.indices) {
                    val t = tabs.removeAt(index)
                    runCatching { (t.wv.parent as? ViewGroup)?.removeView(t.wv); t.wv.destroy() }
                    if (tabs.isEmpty()) active = 0 else active = active.coerceIn(0, tabs.size - 1)
                    mountActive()
                }
                else -> {
                }
            }
            Unit
        }
        return tabsList()
    }

    // ══════════════════════════ 快照 ══════════════════════════

    suspend fun snapshot(label: String): String {
        val t = activeTabOrNull() ?: return errJson("还没有打开任何页面，请先 web_open")
        val url = onMain { t.wv.url ?: "" }
        val title = onMain { t.lastTitle }
        val html = readHtml(false)
        val id = Buffers.Snapshots.save(label, url, title, html)
        return JSONObject().put("ok", true).put("id", id).put("url", url)
            .put("title", title).put("html_size", html.length).toString()
    }

    suspend fun snapshotsList(): String {
        val arr = JSONArray()
        Buffers.Snapshots.list().forEach { s ->
            arr.put(
                JSONObject().put("id", s.id).put("url", s.url).put("title", s.title)
                    .put("html_size", s.html.length).put("time", s.time)
            )
        }
        return JSONObject().put("count", arr.length()).put("snapshots", arr).toString()
    }

    suspend fun snapshotRestore(id: String): String {
        val s = Buffers.Snapshots.get(id) ?: return errJson("没有这个快照：$id")
        onMain {
            val t = if (tabs.isEmpty()) newTab(null) else tabs[active]
            t.awaiting = true
            t.wv.loadUrl(s.url)
            Unit
        }
        return JSONObject().put("ok", true).put("id", id).put("url", s.url).toString()
    }

    // ══════════════════════════ 资源释放 ══════════════════════════

    /** 关闭全部页面并销毁 WebView（插件卸载时调用）。 */
    suspend fun shutdown() = onMain {
        tabs.forEach { runCatching { (it.wv.parent as? ViewGroup)?.removeView(it.wv); it.wv.destroy() } }
        tabs.clear()
        active = 0
        host = null
        Unit
    }

    private fun errJson(msg: String) = JSONObject().put("error", msg).toString()
}
