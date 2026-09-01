package com.ai.assistance.quro.core.tools

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.webkit.JavascriptInterface
import android.webkit.WebView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/**
 * 应用内置 WebView 控制器：让 AI 工具能直接操控当前显示的网页（点击、填表、取 DOM、导航、截图）。
 *
 * 设计：
 * - 当前活跃的 QuroBrowserScreen WebView 在 onAttached 时把自己挂进来，onDestroy 时摘掉。
 * - 所有操控走 [eval]，内部用 evaluateJavascript 异步回主线程，结果通过 token 回调拿到。
 * - 关键修复：eval 不再用 eval(字符串) 方式执行（严格 CSP 页面会禁 unsafe-eval 导致脚本永远返回 null、
 *   被 AI 误判为「没有活跃浏览器/页面未加载」），而是把表达式直接包进 IIFE 调桥，兼容 CSP。
 * - [markPageStarted]/[markPageFinished] 由 WebViewClient 回调驱动，给 AI 一个基于 onPageFinished 的
 *   可靠 loaded 状态（与 readyState 无关），彻底解决「网页明明加载了 AI 却说没加载」。
 */
object QuroBrowserController {
    data class PageSnapshot(
        val url: String,
        val title: String,
        val ready: Boolean,
        val dom: String,           // 带 data-quro-id 的简化 DOM（outerHTML + 截断）
        val elements: List<Map<String, String>>,  // [{quro_id, tag, type, text, name, href}]
    )

    @Volatile private var active: WebView? = null
    @Volatile private var pageLoaded = false
    private val main = Handler(Looper.getMainLooper())
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<String>>()
    @Volatile private var nextToken = 0L

    /** JS 桥：把 evaluateJavascript 的回传结果 resume 回协程。 */
    private val bridge = object {
        @JavascriptInterface
        fun onEvalResult(token: Long, payload: String?) {
            pending.remove(token)?.complete(payload ?: "")
        }
    }

    // ── 抓包（request body + 响应头/状态码/响应体，经 JS 钩子 fetch/xhr）──
    data class CapturedApi(
        val source: String,
        val url: String,
        val method: String,
        val reqHeaders: Map<String, String>,
        val reqBody: String,
        val respStatus: Int?,
        val respHeaders: Map<String, String>,
        val respBody: String,
        val error: String?,
        val time: Long
    )

    private object CaptureBuffer {
        private val list = mutableListOf<CapturedApi>()
        private val lock = Any()
        const val MAX = 500
        @Volatile var enabled = true
        fun add(r: CapturedApi) {
            if (!enabled) return
            synchronized(lock) {
                list.add(r)
                if (list.size > MAX) list.removeAt(0)
            }
        }
        fun snapshot(limit: Int = 200, filter: String = ""): List<CapturedApi> = synchronized(lock) {
            val src = if (filter.isEmpty()) list else list.filter {
                it.url.contains(filter, true) || it.method.contains(filter, true) ||
                it.reqBody.contains(filter, true) || it.respBody.contains(filter, true)
            }
            src.takeLast(limit)
        }
        fun clear() = synchronized(lock) { list.clear() }
        fun size(): Int = synchronized(lock) { list.size }
        fun setOn(on: Boolean) { enabled = on; if (!on) clear() }
    }

    private val captureBridge = object {
        @JavascriptInterface
        fun onCaptured(json: String?) {
            if (json.isNullOrEmpty()) return
            runCatching {
                val o = JSONObject(json)
                val reqH = mutableMapOf<String, String>()
                o.optJSONObject("reqHeaders")?.let { h -> h.keys().forEach { k -> reqH[k] = h.optString(k) } }
                val respH = mutableMapOf<String, String>()
                o.optJSONObject("respHeaders")?.let { h -> h.keys().forEach { k -> respH[k] = h.optString(k) } }
                val rawStatus = o.opt("respStatus")
                val status: Int? = if (rawStatus is Int && rawStatus >= 0) rawStatus else null
                val err = if (o.has("error") && !o.isNull("error")) o.optString("error") else null
                CaptureBuffer.add(
                    CapturedApi(
                        source = o.optString("source", "unknown"),
                        url = o.optString("url", ""),
                        method = o.optString("method", "GET"),
                        reqHeaders = reqH,
                        reqBody = o.optString("reqBody", ""),
                        respStatus = status,
                        respHeaders = respH,
                        respBody = o.optString("respBody", ""),
                        error = err,
                        time = if (o.has("time")) o.optLong("time") else System.currentTimeMillis()
                    )
                )
            }
        }
    }

    private val CAPTURE_HOOK_JS = """(function(){
  if (window.__quroCapInstalled) return;
  window.__quroCapInstalled = true;
  function clamp(s, n){ if(!s) return ''; s=String(s); return s.length>n? s.slice(0,n)+'\n…[truncated '+(s.length-n)+' chars]':s; }
  function post(o){ try{ QuroCapture.onCaptured(JSON.stringify(o)); }catch(e){} }
  function hdrObj(h){ var o={}; try{ if(h&&h.forEach){h.forEach(function(v,k){o[k]=v;});} else if(h){ for(var k in h){ if(h.hasOwnProperty(k)) o[k]=h[k]; } } }catch(e){} return o; }
  try{
    var _fetch = window.fetch ? window.fetch.bind(window) : null;
    if(_fetch){ window.fetch = function(input, init){
      var url = (typeof input==='string')? input : (input&&input.url? input.url : '');
      var method = (init&&init.method)? String(init.method).toUpperCase() : 'GET';
      var reqHeaders = hdrObj(init? init.headers : null);
      var rb = init? init.body : undefined;
      var reqBody=''; try{ if(typeof rb==='string') reqBody=rb; else if(rb&&rb.toString){var s=rb.toString(); if(s&&s.indexOf('[object')!==0) reqBody=s;} }catch(e){}
      var t0 = Date.now();
      return _fetch(input, init).then(function(resp){
        var respHeaders={}; try{ resp.headers.forEach(function(v,k){respHeaders[k]=v;}); }catch(e){}
        var status=resp.status;
        var bodyPromise; try{ bodyPromise = resp.clone().text(); }catch(e){ bodyPromise=Promise.resolve(''); }
        return bodyPromise.then(function(bt){
          post({source:'fetch',url:url,method:method,reqHeaders:reqHeaders,reqBody:clamp(reqBody,65536),respStatus:status,respHeaders:respHeaders,respBody:clamp(bt||'',262144),time:t0});
          return resp;
        }).catch(function(){ post({source:'fetch',url:url,method:method,reqHeaders:reqHeaders,reqBody:clamp(reqBody,65536),respStatus:status,time:t0}); return resp; });
      }).catch(function(err){ post({source:'fetch',url:url,method:method,reqHeaders:reqHeaders,reqBody:clamp(reqBody,65536),error:String(err&&err.message?err.message:err),time:t0}); throw err; });
    };}
  }catch(e){}
  try{
    var _xhrOpen = XMLHttpRequest.prototype.open;
    var _xhrSend = XMLHttpRequest.prototype.send;
    XMLHttpRequest.prototype.open = function(m,u){ this.__qm=m; this.__qu=u; this.__qh={}; this.__qb=''; return _xhrOpen.apply(this, arguments); };
    var _xhrSet = XMLHttpRequest.prototype.setRequestHeader;
    XMLHttpRequest.prototype.setRequestHeader = function(k,v){ try{ this.__qh[k]=v; }catch(e){} return _xhrSet.apply(this, arguments); };
    XMLHttpRequest.prototype.send = function(body){
      try{ if(typeof body==='string') this.__qb=body; else if(body&&body.toString){var s=body.toString(); if(s&&s.indexOf('[object')!==0) this.__qb=s;} }catch(e){}
      var self=this; var t0=Date.now();
      this.addEventListener('readystatechange', function(){
        if(self.readyState===4){
          var rh={}; try{ var th=self.getAllResponseHeaders(); if(th){ th.split(/\r?\n/).forEach(function(l){ var i=l.indexOf(':'); if(i>0){ var k=l.slice(0,i).trim(); var v=l.slice(i+1).trim(); if(k) rh[k]=v; } }); } }catch(e){}
          var rb=''; try{ rb=self.responseText||''; }catch(e){}
          post({source:'xhr',url:self.__qu,method:self.__qm,reqHeaders:self.__qh,reqBody:clamp(self.__qb,65536),respStatus:self.status,respHeaders:rh,respBody:clamp(rb,262144),time:t0});
        }
      });
      return _xhrSend.apply(this, arguments);
    };
  }catch(e){}
})();"""

    /** 注入 fetch/xhr 抓包钩子（幂等，window.__quroCapInstalled 防护）。 */
    fun injectCaptureHook(wv: WebView) {
        try { wv.evaluateJavascript(CAPTURE_HOOK_JS, null) } catch (_: Throwable) {}
    }

    /** 导出抓包快照为 JSON（含请求体 / 响应头 / 状态码 / 响应体）。供 browser_act capture / web_crawler include_captures 使用。 */
    fun getCaptureSnapshotJson(limit: Int = 200, filter: String = ""): String {
        val list = CaptureBuffer.snapshot(limit, filter)
        val arr = JSONArray()
        list.forEach { r ->
            arr.put(JSONObject().apply {
                put("source", r.source)
                put("url", r.url)
                put("method", r.method)
                put("req_headers", JSONObject(r.reqHeaders))
                put("req_body", r.reqBody)
                put("resp_status", r.respStatus ?: JSONObject.NULL)
                put("resp_headers", JSONObject(r.respHeaders))
                put("resp_body", r.respBody)
                if (r.error != null) put("error", r.error)
                put("time", r.time)
            })
        }
        return JSONObject().put("count", list.size).put("requests", arr).toString()
    }

    fun clearCapture() = CaptureBuffer.clear()
    fun isCaptureEnabled(): Boolean = CaptureBuffer.enabled
    fun setCaptureEnabled(on: Boolean) = CaptureBuffer.setOn(on)

    fun attach(wv: WebView) {
        active = wv
        runOnMain {
            wv.addJavascriptInterface(bridge, "QuroBridge")
            wv.addJavascriptInterface(captureBridge, "QuroCapture")
            QuroSessionBridge.register(wv)
        }
    }

    fun detach(wv: WebView) {
        if (active === wv) {
            active = null
            pageLoaded = false
        }
        runOnMain {
            runCatching { wv.removeJavascriptInterface("QuroBridge") }
        }
    }

    /** 页面开始加载（WebViewClient.onPageStarted 调用）。 */
    fun markPageStarted() {
        pageLoaded = false
        active?.let { injectCaptureHook(it) }
    }

    /** 页面加载完成（WebViewClient.onPageFinished 调用）。 */
    fun markPageFinished() {
        pageLoaded = true
    }

    /** 当前页面是否已加载完成（基于 onPageFinished，最可靠的判据）。 */
    fun isPageLoaded(): Boolean = pageLoaded

    fun isAttached(): Boolean = active != null

    /** 当前 URL（null = 没有挂载中的浏览器）。 */
    fun currentUrl(): String? {
        val wv = active ?: return null
        return runOnMainSync { wv.url }
    }

    /** 当前 title。 */
    fun currentTitle(): String? {
        val wv = active ?: return null
        return runOnMainSync { wv.title }
    }

    /** 通用：切到主线程同步执行并取结果；非主线程时阻塞当前线程直到主线程跑完（仅供内部使用，调用方应在协程内）。 */
    private inline fun <T> runOnMainSync(crossinline block: () -> T): T {
        return if (Looper.myLooper() === Looper.getMainLooper()) block() else {
            kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { block() }
            }
        }
    }

    /**
     * 异步执行 JS 表达式并把字符串化结果回传（null = 没有活跃 WebView 或超时）。
     * 修复点：直接把表达式包进 IIFE 调 QuroBridge.onEvalResult，**不使用 eval()**，从而兼容
     * Content-Security-Policy 禁止 unsafe-eval 的页面（否则这些页面上脚本永远执行失败、被 AI 误判未加载）。
     */
    suspend fun eval(js: String, timeoutMs: Long = 8000): String? {
        val wv = active ?: return null
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<String> { cont ->
                runOnMain {
                    val token = nextToken++
                    val cd = CompletableDeferred<String>()
                    pending[token] = cd
                    cont.invokeOnCancellation { pending.remove(token); cd.cancel() }
                    val wrapped = "QuroBridge.onEvalResult($token,(function(){try{var __r=($js);" +
                        "return __r===undefined?'undefined':(__r&&__r.toString?__r.toString():String(__r));" +
                        "}catch(e){return '__ERR__:'+(e&&e.message?e.message:e);}})());"
                    wv.evaluateJavascript(wrapped, null)
                }
            }
        }
    }

    /** 导航到 URL；返回是否成功。关键词会被 [resolveBrowserInput] 转成搜索链接。 */
    suspend fun navigate(url: String): Boolean {
        val wv = active ?: return false
        val target = resolveBrowserInput(url)
        runOnMain { wv.loadUrl(target) }
        return true
    }

    /**
     * 取页面快照：URL + title + 注入稳定 ID 后的 DOM 简化版 + 可交互元素列表。
     * 注入逻辑：遍历 a/button/input/textarea/select，[data-quro-id="qN"] 即可被 clickById/fillById 命中。
     */
    suspend fun snapshot(timeoutMs: Long = 10000): PageSnapshot? {
        val wv = active ?: return null
        val url = currentUrl() ?: ""
        val title = currentTitle() ?: ""
        // 等待 readyState === 'complete' 或 'interactive'（DOM 可用即视为就绪，避免 SPA/懒加载页永远卡在等待）
        val ready = waitReady(8000)
        val js = """
            (function(){
              try {
                var els = document.querySelectorAll('a,button,input,textarea,select,[role=button]');
                var list=[]; var i=0;
                els.forEach(function(el){
                  try {
                    if(!el.offsetParent && getComputedStyle(el).display==='none') return;
                    el.setAttribute('data-quro-id','q'+(i++));
                    var r=el.getBoundingClientRect();
                    if(r.width<=0||r.height<=0) return;
                    var text=(el.innerText||el.value||el.placeholder||el.getAttribute('aria-label')||'').trim().slice(0,80);
                    list.push({
                      quro_id: el.getAttribute('data-quro-id'),
                      tag: el.tagName.toLowerCase(),
                      type: el.getAttribute('type')||'',
                      text: text,
                      name: el.getAttribute('name')||'',
                      href: el.getAttribute('href')||'',
                      placeholder: el.getAttribute('placeholder')||''
                    });
                  } catch(e){}
                });
                var body = document.body ? document.body.outerHTML : '';
                return JSON.stringify({ready: document.readyState, count: list.length, list: list, dom: body.slice(0, 60000)});
              } catch(e){ return '__ERR__:'+e; }
            })()
        """.trimIndent()
        val raw = eval(js, timeoutMs) ?: return null
        if (raw.startsWith("__ERR__:")) return null
        val jo = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val dom = jo.optString("dom")
        val list = mutableListOf<Map<String, String>>()
        val ja = jo.optJSONArray("list")
        if (ja != null) for (i in 0 until ja.length()) {
            val o = ja.optJSONObject(i) ?: continue
            val m = mutableMapOf<String, String>()
            o.keys().forEach { k -> m[k] = o.optString(k) }
            list.add(m)
        }
        return PageSnapshot(url = url, title = title, ready = ready, dom = dom, elements = list)
    }

    /** 等待页面 readyState === 'complete' 或 'interactive'（DOM 可用）。 */
    suspend fun waitReady(timeoutMs: Long): Boolean {
        val js = "(document.readyState==='complete'||document.readyState==='interactive')"
        repeat((timeoutMs / 200).toInt().coerceAtLeast(1)) {
            if (eval(js, 500) == "true") return true
            kotlinx.coroutines.delay(200)
        }
        return false
    }

    /** 当前浏览器状态摘要（attached/loaded/url/title），供 AI 可靠判断页面是否就绪。 */
    fun status(): String {
        val wv = active
        return buildString {
            append("attached=").append(wv != null).append('\n')
            append("loaded=").append(pageLoaded).append('\n')
            append("url=").append(wv?.url ?: "").append('\n')
            append("title=").append(wv?.title ?: "").append('\n')
        }
    }

    // —— 导航 ——
    fun goBack(): Boolean {
        val wv = active ?: return false
        runOnMain { if (wv.canGoBack()) wv.goBack() }
        return true
    }

    fun goForward(): Boolean {
        val wv = active ?: return false
        runOnMain { if (wv.canGoForward()) wv.goForward() }
        return true
    }

    fun reload(): Boolean {
        val wv = active ?: return false
        runOnMain { wv.reload() }
        return true
    }

    fun stopLoading(): Boolean {
        val wv = active ?: return false
        runOnMain { wv.stopLoading() }
        return true
    }

    // —— 滚动 ——
    suspend fun scrollBy(dy: Int): Boolean = eval("window.scrollBy(0, $dy)") != null
    suspend fun scrollToTop(): Boolean = eval("window.scrollTo(0, 0)") != null
    suspend fun scrollToBottom(): Boolean = eval("window.scrollTo(0, document.body ? document.body.scrollHeight : 0)") != null

    /** 按 quro-id 派发 click 事件。 */
    suspend fun clickById(quroId: String): Boolean {
        val js = "(function(){var el=document.querySelector('[data-quro-id=${quroId.replace("'", "\\'")}]'); if(!el) return 'no'; el.scrollIntoView(); el.click(); return 'ok';})()"
        return eval(js) == "ok"
    }

    /** 按 quro-id 写入文本（input/textarea）。 */
    suspend fun fillById(quroId: String, value: String): Boolean {
        val v = JSONObject.quote(value)
        val id = quroId.replace("'", "\\'")
        val js = """
            (function(){
              var el=document.querySelector('[data-quro-id=$id]');
              if(!el) return 'no';
              el.focus();
              var proto = el.tagName==='INPUT' ? HTMLInputElement.prototype : HTMLTextAreaElement.prototype;
              var setter = Object.getOwnPropertyDescriptor(proto,'value').set;
              setter.call(el, $v);
              el.dispatchEvent(new Event('input',{bubbles:true}));
              el.dispatchEvent(new Event('change',{bubbles:true}));
              return 'ok';
            })()
        """.trimIndent()
        return eval(js) == "ok"
    }

    /** 按 CSS 选择器点（兜底，给熟悉 CSS 的 AI 用）。 */
    suspend fun clickBySelector(sel: String): Boolean {
        val s = JSONObject.quote(sel)
        val js = "(function(){var el=document.querySelector($s); if(!el) return 'no'; el.scrollIntoView(); el.click(); return 'ok';})()"
        return eval(js) == "ok"
    }

    /** 按 CSS 选择器写入文本（input/textarea）。 */
    suspend fun fillBySelector(sel: String, value: String): Boolean {
        val s = JSONObject.quote(sel)
        val v = JSONObject.quote(value)
        val js = """
            (function(){
              var el=document.querySelector($s);
              if(!el) return 'no';
              el.focus();
              var proto = el.tagName==='INPUT' ? HTMLInputElement.prototype : HTMLTextAreaElement.prototype;
              var setter = Object.getOwnPropertyDescriptor(proto,'value').set;
              setter.call(el, $v);
              el.dispatchEvent(new Event('input',{bubbles:true}));
              el.dispatchEvent(new Event('change',{bubbles:true}));
              return 'ok';
            })()
        """.trimIndent()
        return eval(js) == "ok"
    }

    /** 按 CSS 选择器读 outerHTML。 */
    suspend fun readBySelector(sel: String): String? {
        val s = JSONObject.quote(sel)
        val js = "(function(){var el=document.querySelector($s); return el ? el.outerHTML : '';})()"
        return eval(js)
    }

    /** 整页 HTML（document.documentElement.outerHTML）。 */
    suspend fun pageHtml(): String? = eval("document.documentElement ? document.documentElement.outerHTML : ''", 8000)

    /** 页面可见正文（document.body.innerText）。 */
    suspend fun pageText(): String? = eval("document.body ? document.body.innerText : ''", 8000)

    /** 页面内查找文本并高亮，返回是否命中（true/false；null=执行失败）。 */
    suspend fun find(text: String): Boolean? {
        val t = JSONObject.quote(text)
        val r = eval("(function(){try{return window.find($t, false, false, true, false) ? 'true' : 'false';}catch(e){return '__ERR__:'+e;}})()", 3000)
        return when (r) {
            "true" -> true
            "false" -> false
            else -> null
        }
    }

    /** 收集当前页所有外链（绝对化后的 href 列表），过滤 js:/#/mailto:/tel: 等无意义锚点。供爬虫/AI 使用。 */
    suspend fun collectLinks(): List<String>? {
        val js = """(function(){
          try {
            var out = [];
            var seen = {};
            document.querySelectorAll('a[href]').forEach(function(a){
              var h = a.href;
              if (!h) return;
              if (h.indexOf('javascript:')===0 || h.indexOf('#')===0 ||
                  h.indexOf('mailto:')===0 || h.indexOf('tel:')===0) return;
              if (seen[h]) return;
              seen[h] = 1;
              out.push(h);
            });
            return JSON.stringify(out);
          } catch(e){ return '__ERR__:'+e; }
        })()"""
        val raw = eval(js, 8000) ?: return null
        if (raw.startsWith("__ERR__:")) return null
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return null
        val list = mutableListOf<String>()
        for (i in 0 until arr.length()) list.add(arr.optString(i))
        return list
    }

    /** 取当前页正文（body.innerText），截断到 maxChars。 */
    suspend fun collectText(maxChars: Int = 4000): String? {
        val r = eval("document.body ? document.body.innerText : ''", 8000) ?: return null
        return r.take(maxChars).trim()
    }

    /** 截取当前窗口（含网页渲染像素）存 PNG，返回文件路径；失败返回 null。 */
    suspend fun screenshot(context: Context): String? {
        val wv = active ?: return null
        val activity = (context.findActivity() ?: wv.context.findActivity()) ?: return null
        val win = activity.window
        val decor = win.decorView
        if (decor.width <= 0 || decor.height <= 0) return null
        return suspendCancellableCoroutine { cont ->
            val bmp = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
            PixelCopy.request(win, bmp, { res ->
                if (res == PixelCopy.SUCCESS) {
                    val dir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: context.filesDir
                    val file = File(dir, "quro_browser_${System.currentTimeMillis()}.png")
                    runCatching {
                        FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    }.onSuccess { cont.resume(file.absolutePath) }
                        .onFailure { cont.resume(null) }
                } else cont.resume(null)
            }, Handler(Looper.getMainLooper()))
        }
    }

    private fun Context.findActivity(): Activity? {
        var c: Context? = this
        while (c != null) {
            if (c is Activity) return c
            c = if (c is ContextWrapper) c.baseContext else null
        }
        return null
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() === Looper.getMainLooper()) block() else main.post(block)
    }

    /**
     * 地址栏输入归一化：把用户输入解析成可加载的 URL。
     * - 带 scheme（http://、file://…）→ 原样；
     * - 无空格且含 '.'（如 example.com / 192.168.1.1 / localhost:8080）→ 补 https://；
     * - 含空格或纯词（如「kotlin 教程」）→ 当作搜索关键词，走百度检索。
     */
    fun resolveBrowserInput(raw: String): String {
        val s = raw.trim()
        if (s.isEmpty()) return s
        if (Regex("""^[a-zA-Z][a-zA-Z0-9+.\-]*://""").containsMatchIn(s)) return s
        if (!s.contains(' ') && s.contains('.')) return "https://$s"
        val enc = URLEncoder.encode(s, "UTF-8")
        return "https://www.baidu.com/s?wd=$enc"
    }
}
