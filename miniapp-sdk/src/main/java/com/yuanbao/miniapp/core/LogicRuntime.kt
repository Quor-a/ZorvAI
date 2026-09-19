package com.yuanbao.miniapp.core

import android.os.Handler
import android.os.HandlerThread
import com.yuanbao.miniapp.js.JsBridge
import com.yuanbao.miniapp.js.JsEngine
import com.yuanbao.miniapp.nativeapi.WxApi
import com.yuanbao.miniapp.pack.MiniPackage
import com.yuanbao.miniapp.util.Json
import com.yuanbao.miniapp.util.parseJson
import com.yuanbao.miniapp.util.writeJson

/**
 * The logic layer: owns the JS engine and the mini program's App/Page objects.
 * Runs on its own thread; talks to the render layer through [RenderHost].
 */
class LogicRuntime(
    private val pkg: MiniPackage,
    private val wxApi: WxApi,
    private val renderHost: RenderHost,
    private val listener: EngineListener?
) {

    interface RenderHost {
        /** New page data arrived (setData / initial render). */
        fun onDataChanged(dataJson: String)
        /** Page template + styles to render. */
        fun onPageLoaded(pagePath: String, wxml: String, wxss: String, pageJson: String?)
        fun onPageClosed()
    }

    interface EngineListener {
        fun onLog(level: String, message: String)
        fun onError(message: String)
        fun onLifecycle(event: String, page: String)
    }

    private val thread = HandlerThread("miniapp-logic").apply { start() }
    val handler = Handler(thread.looper)
    val engine = JsEngine()
    private val timers = HashMap<Int, Runnable>()
    private var nextTimerId = 1

    @Volatile
    private var currentPage: String = ""

    @Volatile
    private var destroyed = false

    init {
        wxApi.attach(engine, handler)
        JsBridge.hostInvoker = { name, argsJson -> handleNative(name, argsJson) }
        JsBridge.logger = { level, msg -> listener?.onLog(level, msg) }
        JsBridge.timerScheduler = { delay, repeat, fnId -> scheduleTimer(delay, repeat, fnId) }
        JsBridge.timerCanceller = { cancelTimer(it) }

        handler.post {
            setupRuntime()
            runApp()
        }
    }

    // ------------------------------------------------------------ runtime glue
    private fun setupRuntime() {
        // host bridge: __native(name, args...)
        engine.registerHostFunction("__native")
        WxApi.API_NAMES.forEach { engine.registerHostFunction("__wx_$it") }

        engine.evaluate(
            """
            var __handlers = {};
            var __page = null;
            var __app = null;
            var __pageData = {};
            var __pageOptions = {};
            var __launchOptions = {};
            var __currentPagePath = '';

            function App(options) {
              __app = options || {};
              globalThis.__appRef = __app;
              if (typeof __app.onLaunch === 'function') { __app.onLaunch(__launchOptions); }
            }

            function Page(options) {
              __page = options || {};
              __pageData = {};
              var src = (__page.data || {});
              for (var k in src) { __pageData[k] = src[k]; }
              __page.data = __pageData;
              __page.setData = function(patch) {
                for (var k in patch) { __pageData[k] = patch[k]; }
                __native('setData', JSON.stringify(__pageData));
              };
              if (typeof __page.onLoad === 'function') { __page.onLoad(__pageOptions); }
            }

            function getApp() { return __app; }
            function getCurrentPages() { return [__currentPagePath]; }

            function __setDataJson(jsonText) {
              var patch = JSON.parse(jsonText);
              for (var k in patch) { __pageData[k] = patch[k]; }
              __native('setData', JSON.stringify(__pageData));
              return true;
            }

            function __dispatch(name, argsJson) {
              if (!__page) { return null; }
              var fn = __page[name];
              if (typeof fn !== 'function') { return null; }
              var args = [];
              try { args = JSON.parse(argsJson || '[]'); } catch (e) { args = []; }
              return fn.apply(__page, args);
            }

            function __lifecycle(name, argsJson) {
              if (!__page) { return null; }
              var fn = __page[name];
              if (typeof fn !== 'function') { return null; }
              var args = [];
              try { args = JSON.parse(argsJson || '[]'); } catch (e) { args = []; }
              return fn.apply(__page, args);
            }

            function __appLifecycle(name, argsJson) {
              if (!__app) { return null; }
              var fn = __app[name];
              if (typeof fn !== 'function') { return null; }
              var args = [];
              try { args = JSON.parse(argsJson || '[]'); } catch (e) { args = []; }
              return fn.apply(__app, args);
            }
            """.trimIndent()
        )

        // wx namespace: each method forwards to a host function
        val wxGlue = buildString {
            append("var wx = {};\n")
            for (name in WxApi.API_NAMES) {
                append("wx.$name = function(){ return __wx_$name.apply(null, arguments); };\n")
            }
            append("globalThis.wx = wx;\n")
        }
        engine.evaluate(wxGlue)

        // ★ wx 兜底：AI 写的页面常调我们没实现的 wx 接口，原来它是 undefined →
        // 一调就抛 TypeError，把整段 onLoad/onReady 打断 → setData 永远到不了渲染层，
        // 界面就只剩一块白。这里把"常见但未实现"的接口补成有空实现的占位，
        // 保证「一个不支持的能力」不会把整页拖死；调用会记一条日志供诊断。
        val fallbacks = WX_UNSUPPORTED_FALLBACKS.filter { it !in WxApi.API_NAMES }
        if (fallbacks.isNotEmpty()) {
            engine.evaluate(fallbacks.joinToString("\n") { n ->
                "wx.$n = function(){ __native('log', 'wx.$n 未实现，已忽略'); return undefined; };"
            })
        }
    }

    /** 跑一段脚本并把失败原因报给宿主（此前多处 evaluate 的结果被直接丢弃，错误静默消失）。 */
    private fun evalReported(what: String, source: String): Boolean {
        val r = engine.evaluate(source)
        if (r.isError()) {
            listener?.onError("$what: ${r.errorMessage()}")
            return false
        }
        return true
    }

    private fun runApp() {
        val appJs = pkg.appJs
        if (!appJs.isNullOrBlank()) {
            val res = engine.evaluate(appJs)
            if (res.isError()) listener?.onError("app.js: ${res.errorMessage()}")
        }
        listener?.onLifecycle("onLaunch", "")
    }

    /** Called by the host when a page should be created. */
    fun loadPage(pagePath: String, paramsJson: String) {
        handler.post {
            // 整段包住：JS 引擎（native）或页面脚本抛异常时，若任其逃出这个 Runnable，
            // onPageLoaded 就永远不会被回调 → 页面 root 恒为 null → 卡片纯白且无任何提示。
            // 现在至少把失败原因交回宿主显示出来。
            try {
                currentPage = pagePath
                engine.evaluate("__pageOptions = $paramsJson;")
                evalReported("$pagePath 参数", "__currentPagePath = ${q(pagePath)};")
                val js = pkg.pageJs(pagePath)
                if (!js.isNullOrBlank()) {
                    val res = engine.evaluate(js)
                    if (res.isError()) listener?.onError("$pagePath.js: ${res.errorMessage()}")
                }
                val data = engine.evaluate("JSON.stringify(__pageData)")
                val dataJson = if (data.isError()) "{}" else data.asString()
                renderHost.onPageLoaded(
                    pagePath,
                    pkg.pageWxml(pagePath) ?: "",
                    listOfNotNull(pkg.appWxss, pkg.pageWxss(pagePath)).joinToString("\n"),
                    pkg.pageJson(pagePath)
                )
                renderHost.onDataChanged(dataJson)
                // onShow 里出错以前被完全丢弃 → 页面数据可能就停在半路，界面只剩白底。
                evalReported("$pagePath onShow", "__lifecycle('onShow', '[]')")
                listener?.onLifecycle("onLoad", pagePath)
            } catch (t: Throwable) {
                listener?.onError("页面 $pagePath 加载失败：${t.javaClass.simpleName}: ${t.message}")
                // 兜底：仍然把模板交给渲染层，保证页面至少能出静态骨架
                runCatching {
                    renderHost.onPageLoaded(
                        pagePath,
                        pkg.pageWxml(pagePath) ?: "",
                        listOfNotNull(pkg.appWxss, pkg.pageWxss(pagePath)).joinToString("\n"),
                        pkg.pageJson(pagePath)
                    )
                }
            }
        }
    }

    fun notifyReady() {
        handler.post {
            evalReported("onReady", "__lifecycle('onReady', '[]')")
            listener?.onLifecycle("onReady", currentPage)
        }
    }

    fun notifyShow() {
        handler.post {
            evalReported("onShow", "__lifecycle('onShow', '[]')")
            evalReported("app.onShow", "__appLifecycle('onShow', '[]')")
        }
    }

    fun notifyHide() {
        handler.post {
            engine.evaluate("__lifecycle('onHide', '[]')")
            engine.evaluate("__appLifecycle('onHide', '[]')")
        }
    }

    fun unloadCurrentPage() {
        handler.post {
            engine.evaluate("__lifecycle('onUnload', '[]')")
            renderHost.onPageClosed()
        }
    }

    /** Event from the render layer: call the page method [handlerName]. */
    fun dispatchEvent(handlerName: String, argsJson: String) {
        handler.post {
            val res = engine.evaluate("__dispatch(${q(handlerName)}, ${q(argsJson)})")
            if (res.isError()) listener?.onError("event $handlerName: ${res.errorMessage()}")
        }
    }

    /** Sends a setData patch (produced by JS) to the render layer. */
    private fun handleNative(name: String, argsJson: String): String {
        val args = runCatching { parseJson(argsJson) }.getOrNull()
        val list = if (args is Json.Arr) args.items else listOf(args ?: Json.Null)
        when (name) {
            "__native" -> {
                val cmd = list.getOrNull(0)?.asString() ?: return "null"
                val payload = list.getOrNull(1)?.asString() ?: ""
                when (cmd) {
                    "setData" -> renderHost.onDataChanged(payload)
                    "log" -> listener?.onLog("info", payload)
                    else -> Unit
                }
                return "null"
            }
            else -> {
                if (name.startsWith("__wx_")) {
                    val api = name.removePrefix("__wx_")
                    return wxApi.dispatch(api, argsJson)
                }
                return "null"
            }
        }
    }

    /** Schedules a JS timer; [fnId] is the engine-side handle of the callback. */
    private fun scheduleTimer(delayMs: Int, repeat: Boolean, fnId: Int): Int {
        val id = nextTimerId++
        val task = object : Runnable {
            override fun run() {
                if (destroyed) return
                engine.invokeFunction(fnId, "[]")
                if (repeat) handler.postDelayed(this, delayMs.toLong())
            }
        }
        timers[id] = task
        handler.postDelayed(task, delayMs.toLong())
        return id
    }

    private fun cancelTimer(id: Int) {
        timers.remove(id)?.let { handler.removeCallbacks(it) }
    }

    fun evaluate(source: String): String {
        val res = engine.evaluate(source)
        return if (res.isError()) res.errorMessage() else res.asString()
    }

    fun destroy() {
        destroyed = true
        handler.post {
            runCatching { engine.close() }
            thread.quitSafely()
        }
    }

    private fun q(s: String): String = "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'"
}

/**
 * 常见但当前未实现的 wx 接口 → 在 JS 侧补成"记日志 + 返回 undefined"的空实现。
 * 原因：这些名字一旦是 undefined，AI 页面里的 `wx.xxx(...)` 会抛 TypeError 并中断
 * 整个 onLoad/onReady，setData 到不了渲染层 → 页面永久白屏且毫无提示。
 * 与 [WxApi.API_NAMES] 取差集后使用，绝不覆盖已实现的接口。
 */
private val WX_UNSUPPORTED_FALLBACKS = listOf(
    "setStorage", "getStorage", "removeStorage", "clearStorage", "getStorageInfo", "getStorageInfoSync",
    "pageScrollTo", "createSelectorQuery", "createAnimation", "createIntersectionObserver",
    "getMenuButtonBoundingClientRect", "setNavigationBarColor", "hideHomeButton",
    "showTabBar", "hideTabBar", "setTabBarBadge", "removeTabBarBadge",
    "createInnerAudioContext", "createVideoContext", "createCanvasContext", "canvasToTempFilePath",
    "getUserInfo", "getUserProfile", "login", "checkSession", "getSetting", "authorize", "openSetting",
    "uploadFile", "downloadFile", "getImageInfo", "saveImageToPhotosAlbum", "previewImage",
    "getBatteryInfoSync", "getLaunchOptionsSync", "getEnterOptionsSync", "getRealtimeLogManager",
    "onNetworkStatusChange", "onAppShow", "onAppHide", "offAppShow", "offAppHide",
    "onError", "onPageNotFound", "offError", "startPullDownRefresh", "updateManager"
)
