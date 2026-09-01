package com.ai.assistance.quro.core.tools

import android.content.Context
import android.content.SharedPreferences
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Python↔浏览器会话桥（@JavascriptInterface 暴露为 window.QuroSession）。
 *
 * 作用：
 * 1) Cookie 双向传递 —— 基于应用全局 CookieManager（所有 WebView 共享同一 Cookie 罐），
 *    因此 Python（Brython network.py 的 XHR）与浏览器访问同一域名时自动带上相同会话 Cookie；
 *    Python 也可显式读/写 Cookie，实现「先浏览器登录 → Python 复用登录态」或反向。
 * 2) Storage 双向传递 —— App 级 SharedPreferences 镜像，Python 与浏览器页面都能读写，
 *    可在两个 WebView 之间同步 localStorage 风格的状态。
 * 3) Python 侧绑定 browser_act —— browserAct(action, jsonArgs) 复用现有 QuroBrowserController
 *    的挂起函数（navigate/click/fill/snapshot/html/text/links/...），让 Python 脚本像 AI 一样驱动浏览器。
 *
 * 线程安全：browserAct 从 @JavascriptInterface 回调线程（通常是 WebView 线程）进入，
 * 内部用独立单线程 Executor 跑 runBlocking 调用 QuroBrowserController（其 eval 走 runOnMain→主线程 evaluateJavascript），
 * 三层线程分离，绝不死锁。
 *
 * 注入点：QuroBrowserController.attach（浏览器 WebView）+ QuroBrowserScreen.openPythonConsole（Python 控制台 WebView）。
 */
object QuroSessionBridge {

    @Volatile private var appContext: Context? = null
    private val main = android.os.Handler(Looper.getMainLooper())
    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "QuroSessionBridge") }
    private val prefs: SharedPreferences?
        get() = appContext?.getSharedPreferences("quro_session_storage", Context.MODE_PRIVATE)

    private val cookieDateFmt = SimpleDateFormat("EEE, dd-MMM-yyyy HH:mm:ss 'GMT'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("GMT")
    }

    /** 暴露给 JS / Brython 的桥对象。 */
    private val sessionBridge = object {
        @JavascriptInterface
        fun getCookies(domain: String?): String {
            val d = domain ?: return "{}"
            return runCatching {
                val raw = CookieManager.getInstance().getCookie(d) ?: return@runCatching "{}"
                val map = JSONObject()
                raw.split(";").forEach { part ->
                    val idx = part.indexOf('=')
                    if (idx > 0) {
                        val k = part.substring(0, idx).trim()
                        val v = part.substring(idx + 1).trim()
                        if (k.isNotEmpty()) map.put(k, v)
                    }
                }
                map.toString()
            }.getOrDefault("{}")
        }

        @JavascriptInterface
        fun setCookie(domain: String?, cookie: String?): String {
            if (domain.isNullOrEmpty() || cookie.isNullOrEmpty()) return "error:empty"
            return runCatching {
                CookieManager.getInstance().setCookie(domain, cookie)
                CookieManager.getInstance().flush()
                "ok"
            }.getOrDefault("error")
        }

        @JavascriptInterface
        fun clearCookies(): String {
            return runCatching {
                val cm = CookieManager.getInstance()
                cm.removeAllCookies(null)
                cm.flush()
                "ok"
            }.getOrDefault("error")
        }

        @JavascriptInterface
        fun getStorage(key: String?): String {
            if (key.isNullOrEmpty()) return ""
            return prefs?.getString(key, "") ?: ""
        }

        @JavascriptInterface
        fun setStorage(key: String?, value: String?): String {
            if (key.isNullOrEmpty()) return "error:empty"
            return runCatching {
                prefs?.edit()?.putString(key, value ?: "")?.apply()
                "ok"
            }.getOrDefault("error")
        }

        @JavascriptInterface
        fun getAllStorage(): String {
            val p = prefs ?: return "{}"
            return runCatching {
                val map = JSONObject()
                p.all.forEach { (k, v) -> map.put(k, v) }
                map.toString()
            }.getOrDefault("{}")
        }

        @JavascriptInterface
        fun removeStorage(key: String?): String {
            if (key.isNullOrEmpty()) return "error:empty"
            return runCatching {
                prefs?.edit()?.remove(key)?.apply()
                "ok"
            }.getOrDefault("error")
        }

        @JavascriptInterface
        fun hasBrowser(): String = if (QuroBrowserController.isAttached()) "true" else "false"

        @JavascriptInterface
        fun browserAct(action: String?, argsJson: String?): String {
            val act = action ?: return err("no action")
            val args = runCatching { JSONObject(argsJson ?: "{}") }.getOrDefault(JSONObject())
            return try {
                exec.submit(Callable { runBlocking { dispatch(act, args) } }).get()
            } catch (e: Exception) {
                err("bridge: ${e.message}")
            }
        }
    }

    /** 把桥对象注册到指定 WebView（主线程执行 addJavascriptInterface）。 */
    fun register(wv: WebView?) {
        if (wv == null) return
        val ctx = wv.context?.applicationContext
        if (ctx != null) appContext = ctx
        runOnMain { runCatching { wv.addJavascriptInterface(sessionBridge, "QuroSession") } }
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() === Looper.getMainLooper()) block() else main.post(block)
    }

    private fun err(msg: String): String = JSONObject().apply {
        put("ok", false); put("error", msg)
    }.toString()

    private suspend fun dispatch(action: String, args: JSONObject): String {
        if (!QuroBrowserController.isAttached()) return err("no active browser (请先在内置浏览器打开网页)")
        return runCatching {
            when (action) {
                "navigate", "open" -> {
                    val url = args.optString("url", "")
                    ok(mapOf("ok" to QuroBrowserController.navigate(url)))
                }
                "status" -> ok(mapOf("status" to QuroBrowserController.status()))
                "url" -> ok(mapOf("url" to (QuroBrowserController.currentUrl() ?: "")))
                "title" -> ok(mapOf("title" to (QuroBrowserController.currentTitle() ?: "")))
                "snapshot" -> {
                    val snap = QuroBrowserController.snapshot(args.optLong("timeout", 10000L)) ?: return@runCatching err("snapshot failed")
                    val els = JSONArray()
                    snap.elements.forEach { m ->
                        val o = JSONObject()
                        m.forEach { (k, v) -> o.put(k, v) }
                        els.put(o)
                    }
                    ok(mapOf(
                        "url" to snap.url, "title" to snap.title, "ready" to snap.ready,
                        "dom_len" to snap.dom.length, "count" to snap.elements.size, "elements" to els
                    ))
                }
                "click" -> ok(mapOf("ok" to QuroBrowserController.clickById(args.optString("id", ""))))
                "fill" -> ok(mapOf("ok" to QuroBrowserController.fillById(args.optString("id", ""), args.optString("value", ""))))
                "click_selector" -> ok(mapOf("ok" to QuroBrowserController.clickBySelector(args.optString("selector", ""))))
                "fill_selector" -> ok(mapOf("ok" to QuroBrowserController.fillBySelector(args.optString("selector", ""), args.optString("value", ""))))
                "read" -> ok(mapOf("html" to (QuroBrowserController.readBySelector(args.optString("selector", "")) ?: "")))
                "html" -> ok(mapOf("html" to (QuroBrowserController.pageHtml() ?: "")))
                "text" -> ok(mapOf("text" to (QuroBrowserController.pageText() ?: "")))
                "links" -> {
                    val links = QuroBrowserController.collectLinks() ?: emptyList()
                    val arr = JSONArray()
                    links.forEach { arr.put(it) }
                    ok(mapOf("count" to links.size, "links" to arr))
                }
                "back" -> ok(mapOf("ok" to QuroBrowserController.goBack()))
                "forward" -> ok(mapOf("ok" to QuroBrowserController.goForward()))
                "reload" -> ok(mapOf("ok" to QuroBrowserController.reload()))
                "stop" -> ok(mapOf("ok" to QuroBrowserController.stopLoading()))
                "scroll" -> {
                    val to = args.optString("to", "")
                    val dy = args.optInt("dy", 0)
                    val r = when {
                        to == "top" -> QuroBrowserController.scrollToTop()
                        to == "bottom" -> QuroBrowserController.scrollToBottom()
                        else -> QuroBrowserController.scrollBy(dy)
                    }
                    ok(mapOf("ok" to r))
                }
                "eval" -> ok(mapOf("result" to (QuroBrowserController.eval(args.optString("code", ""), args.optLong("timeout", 8000L)) ?: "")))
                "wait", "wait_ready" -> ok(mapOf("ready" to QuroBrowserController.waitReady(args.optLong("ms", 8000L))))
                "find" -> {
                    val f = QuroBrowserController.find(args.optString("text", ""))
                    ok(mapOf("found" to (f ?: false)))
                }
                "capture" -> ok(mapOf("capture" to QuroBrowserController.getCaptureSnapshotJson(args.optInt("limit", 200), args.optString("filter", ""))))
                "screenshot" -> {
                    val ctx = appContext
                    if (ctx == null) err("no context") else ok(mapOf("path" to (QuroBrowserController.screenshot(ctx) ?: "")))
                }
                else -> err("unknown action: $action")
            }
        }.getOrDefault(err("exception"))
    }

    private fun ok(map: Map<String, Any>): String {
        val o = JSONObject()
        o.put("ok", true)
        map.forEach { (k, v) -> o.put(k, v) }
        return o.toString()
    }

    /** Python 侧 quro_session 包装（注入到 Brython 控制台的 text/python 块），集中维护单一来源。 */
    const val QURO_SESSION_PY = """
# ===== Quro 会话桥 (Python 侧绑定 browser_act / Cookie / Storage) =====
try:
    from browser import window
    _QS = window.QuroSession
    _QS_OK = True
except Exception as _qs_e:
    _QS_OK = False

import json as _json
import time as _time

def _qs_require():
    if not _QS_OK:
        raise Exception("QuroSession 桥未就绪：请在应用内置浏览器 / Python 控制台中使用（确保 WebView 已挂载 QuroSession）")
    return _QS

def _cookie_date(epoch):
    return _time.strftime("%a, %d-%b-%Y %H:%M:%S GMT", _time.gmtime(epoch))

def qs_get_cookies(domain):
    \"\"\"读取指定域名的 Cookie（来自 App 全局 CookieManager，与浏览器共享）。返回 dict。\"\"\"
    try:
        return _json.loads(_qs_require().getCookies(domain))
    except Exception:
        return {}

def qs_set_cookie(domain, name, value, path="/", expires_days=30):
    \"\"\"写入 Cookie 到 App 全局 CookieManager；浏览器后续同域请求会自动带上。\"\"\"
    exp = int(_time.time()) + expires_days * 86400
    cookie = "%s=%s; Domain=%s; Path=%s; Expires=%s" % (name, value, domain, path, _cookie_date(exp))
    return _qs_require().setCookie(domain, cookie)

def qs_clear_cookies():
    return _qs_require().clearCookies()

def qs_get_storage(key):
    \"\"\"读取 App 级会话存储（与浏览器 localStorage 桥接）。\"\"\"
    return _qs_require().getStorage(key)

def qs_set_storage(key, value):
    return _qs_require().setStorage(key, value)

def qs_get_all_storage():
    try:
        return _json.loads(_qs_require().getAllStorage())
    except Exception:
        return {}

def qs_remove_storage(key):
    return _qs_require().removeStorage(key)

def qs_has_browser():
    return _qs_require().hasBrowser() == "true"

def qs_browser_act(action, **kwargs):
    \"\"\"Python 侧驱动 browser_act：action 同 AI 的 browser_act；kwargs 即参数。返回解析后的 JSON 结果。\"\"\"
    args = _json.dumps(kwargs, ensure_ascii=False)
    j = _qs_require().browserAct(action, args)
    try:
        return _json.loads(j)
    except Exception:
        return {"raw": j}

class QuroSession:
    \"\"\"Python 侧 Quro 会话桥对象。\"\"\"
    get_cookies = staticmethod(qs_get_cookies)
    set_cookie = staticmethod(qs_set_cookie)
    clear_cookies = staticmethod(qs_clear_cookies)
    get_storage = staticmethod(qs_get_storage)
    set_storage = staticmethod(qs_set_storage)
    get_all_storage = staticmethod(qs_get_all_storage)
    remove_storage = staticmethod(qs_remove_storage)
    has_browser = staticmethod(qs_has_browser)
    browser_act = staticmethod(qs_browser_act)

quro_session = QuroSession()
print("🌉 Quro 会话桥已就绪：quro_session（Cookie/Storage/browser_act）")
"""
}
