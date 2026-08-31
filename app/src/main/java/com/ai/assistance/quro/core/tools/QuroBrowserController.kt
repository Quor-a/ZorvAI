package com.ai.assistance.quro.core.tools

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/**
 * 应用内置 WebView 控制器：让 AI 工具能直接操控当前显示的网页（点击、填表、取 DOM、导航）。
 *
 * 设计：
 * - 当前活跃的 QuroBrowserScreen WebView 在 onAttached 时把自己挂进来，onDestroy 时摘掉。
 * - 所有操控走 [eval]，内部用 evaluateJavascript 异步回主线程，结果通过 token 回调拿到。
 * - AI 工具可调 snapshot() 拿带稳定 ID 的 DOM（DOM 中每个可点击/可输入元素被自动注入 data-quro-id），
 *   再用 clickById / fillById / eval 直接操作元素。
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

    fun attach(wv: WebView) {
        active = wv
        runOnMain { wv.addJavascriptInterface(bridge, "QuroBridge") }
    }

    fun detach(wv: WebView) {
        if (active === wv) active = null
        runOnMain {
            runCatching { wv.removeJavascriptInterface("QuroBridge") }
        }
    }

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

    /** 通用：切到主线程同步执行并取结果；非主线程时阻塞当前线程直到主线程跑完（仅供 QuroBrowserController 内部使用，调用方应在协程内）。 */
    private inline fun <T> runOnMainSync(crossinline block: () -> T): T {
        return if (Looper.myLooper() === Looper.getMainLooper()) block() else {
            kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { block() }
            }
        }
    }

    /** 异步执行 JS；返回 JS 表达式的字符串结果（null = 没有活跃 WebView）。 */
    suspend fun eval(js: String, timeoutMs: Long = 8000): String? {
        val wv = active ?: return null
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<String> { cont ->
                runOnMain {
                    val token = nextToken++
                    val cd = CompletableDeferred<String>()
                    pending[token] = cd
                    cont.invokeOnCancellation { pending.remove(token); cd.cancel() }
                    val safe = "(()=>{try{var __r=eval(${JSONObject.quote(js)});return __r===undefined?'undefined':(__r&&__r.toString?__r.toString():String(__r));}catch(e){return '__ERR__:'+e;}})()"
                    wv.evaluateJavascript(safe) { v ->
                        // evaluateJavascript 本身也会异步回调一次；JS 桥走另一通道。
                        // 这里不直接 complete，把控制权交给 JS 桥。
                    }
                    // 真正结果通过 JS 桥回传（兼容 evaluateJavascript 不给值的页面 CSP 场景）
                    val bridgeJs = "QuroBridge.onEvalResult($token, $safe);"
                    wv.evaluateJavascript(bridgeJs, null)
                }
            }
        }
    }

    /** 导航到 URL；返回是否成功。 */
    suspend fun navigate(url: String): Boolean {
        val wv = active ?: return false
        runOnMain { wv.loadUrl(url) }
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
        // 等待 readyState === 'complete'
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

    /** 等待页面 readyState === 'complete'。 */
    suspend fun waitReady(timeoutMs: Long): Boolean {
        val js = "document.readyState"
        repeat((timeoutMs / 200).toInt().coerceAtLeast(1)) {
            val r = eval(js, 500)
            if (r == "complete") return true
            kotlinx.coroutines.delay(200)
        }
        return false
    }

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

    /** 按 CSS 选择器读 outerHTML。 */
    suspend fun readBySelector(sel: String): String? {
        val s = JSONObject.quote(sel)
        val js = "(function(){var el=document.querySelector($s); return el ? el.outerHTML : '';})()"
        return eval(js)
    }

    /** 收集当前页所有外链（绝对化后的 href 列表），过滤 js:/#/mailto:/tel: 等无意义锚点。供爬虫使用。 */
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

    /** 取当前页正文（body.innerText），截断到 maxChars，供爬虫做正文抽取。 */
    suspend fun collectText(maxChars: Int = 4000): String? {
        val js = "document.body ? document.body.innerText : ''"
        val r = eval(js, 8000) ?: return null
        return r.take(maxChars).trim()
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() === Looper.getMainLooper()) block() else main.post(block)
    }
}