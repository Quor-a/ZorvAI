package com.ai.assistance.quro.core.tools

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.view.ViewGroup
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 浏览器 WebView 共享宿主（跨全屏/浮窗复用单个 WebView）。
 *
 * 问题背景：此前「系统级化小窗」的浏览器浮窗会 new 一个 WebView 并 loadUrl 重载，
 * 导致「化小窗」瞬间卡顿（新建 WebView + 整页重载）。正确的做法是**整个 App 只有这一个
 * 浏览器 WebView**，全屏与浮窗之间只是把它在不同容器间「重挂」（reattach），不重建、不重载。
 *
 * 职责：
 * - 持有唯一的浏览器 WebView（懒创建、统一配置、注册 AI 操控桥 QuroBrowserController.attach）。
 * - 全屏容器（QuroBrowserScreen 的 FrameLayout）通过 bindMain 登记；WebView 在容器间移动时不重建、不重载。
 * - 化小窗（最小化）≠ 跨窗口搬 WebView：直接把 WebView 从窗口摘下、保留在内存并返回对话界面
 *   （零重载、零卡顿，彻底消除「化小窗卡顿」）。重开全屏时 bindMain 把它重新挂入原 Activity 容器即可。
 * - 仅当真正关闭浏览器（QuroBrowserScreen 的退出/返回）时才由调用方显式 destroy()，绝不因最小化/离场误销毁。
 * - 单一通用 WebViewClient/WebChromeClient：全屏与浮窗的 UI 都从 [uiState] 读同一份状态，
 *   不再各自持有 client（避免重挂后 client 被覆盖导致某端 UI 不刷新）。
 */
object QuroBrowserViewHost {
    private var webView: WebView? = null
    private var mainContainer: ViewGroup? = null
    private var floatContainer: ViewGroup? = null

    /** 共享浏览器 UI 状态（全屏/浮窗共用同一 WebView，状态统一在此，两端都读它）。 */
    private val _uiState = MutableStateFlow(BrowserUiState())
    val uiState: StateFlow<BrowserUiState> = _uiState

    /** 取得当前共享 WebView（可能为 null，尚未创建）。 */
    fun get(): WebView? = webView

    /** 懒创建唯一浏览器 WebView（已存在则直接返回）。统一基础配置 + 注册 AI 操控桥 + 单一通用 client。 */
    fun getOrCreate(context: Context): WebView {
        val existing = webView
        if (existing != null) return existing
        val wv = WebView(context.applicationContext).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                loadsImagesAutomatically = true
                // 标准 overview 模式（缩放显示整页）。化小窗时页面随窗口缩放而非整页重载。
                loadWithOverviewMode = true
                useWideViewPort = true
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                allowFileAccess = true
                javaScriptCanOpenWindowsAutomatically = true
                defaultTextEncodingName = "utf-8"

                // —— 补齐「成熟浏览器」引擎配置（对齐 ZorvBrowser BrowserCore，解决「部分网页打不开」）——
                // 定位：地图/本地类站点需要；否则静默失败导致页面不可用。
                setGeolocationEnabled(true)
                // 移动端适配：页面缩放消除横向溢出。
                layoutAlgorithm = WebSettings.LayoutAlgorithm.NARROW_COLUMNS
                setSupportZoom(true)
                builtInZoomControls = true
                displayZoomControls = false
                // 缓存：默认走 HTTP 缓存，加速二次打开（AppCache 已废弃，不再启用）。
                cacheMode = WebSettings.LOAD_DEFAULT
                // 本地资源 / file:// 跨域（部分 SPA、本地工具页需要）。
                allowContentAccess = true
                allowFileAccessFromFileURLs = true
                allowUniversalAccessFromFileURLs = true
                // 媒体自动播放（视频/语音类站点）。
                mediaPlaybackRequiresUserGesture = false
            }
            // 注册 AI 操控桥：browser_act 始终操控这一个 WebView（无论在全屏还是浮窗）。
            QuroBrowserController.attach(this)
            attachUniversalClient(this)
            // 下载监听：此前全工程未挂 DownloadListener，点下载链接完全无反应。
            // 统一走 QuroDownloadUtil（MediaStore 写公共 Download/Quro 目录，失败回退应用私有目录）。
            setDownloadListener { dlUrl, userAgent, contentDisposition, mimeType, _ ->
                val appCtx = context.applicationContext
                val name = QuroDownloadUtil.deriveFileName(dlUrl, contentDisposition) ?: dlUrl
                Toast.makeText(appCtx, "开始下载：$name", Toast.LENGTH_SHORT).show()
                Thread {
                    val result = QuroDownloadUtil.download(appCtx, dlUrl, userAgent, contentDisposition, mimeType)
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        val msg = when {
                            result.startsWith("OK:") -> "✅ 下载完成：${result.removePrefix("OK:")}\n已保存到 Download/Quro"
                            result.startsWith("FALLBACK:") -> "已保存到应用目录：${result.removePrefix("FALLBACK:")}"
                            else -> "⚠ $result"
                        }
                        Toast.makeText(appCtx, msg, Toast.LENGTH_LONG).show()
                    }
                }.start()
            }
        }
        webView = wv
        reattach()
        return wv
    }

    /**
     * 打开/重开浏览器时确保地址正确：
     * - 尚未加载（cur 空）→ 直接加载 [url]；
     * - 已加载且与 [url] 同一页（归一化比较，忽略尾斜杠/www/协议/fragment）→ 零重载、零卡顿，
     *   化小窗后重开同一地址即保留浏览位置；
     * - 已加载但地址不同（打开新链接/AI 新地址）→ 正常导航加载。
     * 这样既消除「化小窗卡顿」（不跨窗口搬 WebView），又修正此前「仅空才加载」导致新地址不刷新的回归。
     */
    fun loadIfNeeded(url: String) {
        val wv = webView ?: return
        val cur = wv.url
        if (cur.isNullOrEmpty() || cur == "about:blank") {
            wv.loadUrl(url)
            return
        }
        if (normalizeUrl(cur) != normalizeUrl(url)) {
            wv.loadUrl(url)
        }
    }

    /** 归一化 URL 用于「是否同一页」判断：小写协议/主机、去 www.、去尾斜杠、忽略 fragment。 */
    private fun normalizeUrl(u: String?): String {
        if (u.isNullOrEmpty()) return ""
        return runCatching {
            val uri = android.net.Uri.parse(u)
            val scheme = (uri.scheme ?: "https").lowercase()
            val host = (uri.host ?: "").lowercase().removePrefix("www.")
            val path = (uri.path ?: "").trimEnd('/')
            val query = uri.query ?: ""
            "$scheme://$host$path${if (query.isNotEmpty()) "?$query" else ""}"
        }.getOrDefault(u)
    }

    /** 登记全屏容器（QuroBrowserScreen）。若当前没有浮窗占用，则把 WebView 挂回全屏。 */
    fun bindMain(container: ViewGroup) {
        mainContainer = container
        if (webView != null && floatContainer == null) reattach()
    }

    /** 登记浮窗容器（QuroMiniWindowManager / 应用内降级浮层）。WebView 立即移入浮窗（化小窗，不重建/重载）。 */
    fun bindFloat(container: ViewGroup) {
        floatContainer = container
        if (webView != null) reattach()
    }

    /** 浮窗移除：解绑浮窗容器，WebView 移回全屏容器（若仍在）。 */
    fun unbindFloat(container: ViewGroup) {
        if (floatContainer === container) floatContainer = null
        if (webView != null) reattach()
    }

    /** 全屏容器移除（QuroBrowserScreen 离场）：解绑主容器，但【不销毁】WebView。
     *  化小窗/返回对话界面只是把 WebView 从窗口摘下保留在内存（已加载页面与导航栈仍在），
     *  重开全屏时重新挂入容器即可零重载、零卡顿。真正关闭浏览器由 destroy() 显式调用。 */
    fun unbindMain(container: ViewGroup) {
        if (mainContainer === container) mainContainer = null
        if (webView != null) reattach()
    }

    /** 在目标容器间移动 WebView（不重建、不重载），即 WebView 重挂（reattach）模式。
     *  若没有任何容器（化小窗返回对话界面），仅从当前父移除、WebView 保留在内存，
     *  重开全屏时再挂入——全程不跨 WindowManager 窗口搬动，故零卡顿。 */
    private fun reattach() {
        val wv = webView ?: return
        val target = floatContainer ?: mainContainer
        val parent = wv.parent
        if (target == null) {
            // 无容器：从原父摘下，WebView 留在内存（不销毁），等重开再挂入。
            if (parent is ViewGroup) parent.removeView(wv)
            return
        }
        if (parent is ViewGroup && parent !== target) {
            parent.removeView(wv)
        }
        if (wv.parent == null) {
            target.removeAllViews()
            target.addView(
                wv,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
        }
    }

    /** 真正销毁唯一 WebView（仅在浏览器彻底关闭时调用）。 */
    fun destroy() {
        webView?.let { wv ->
            runCatching { QuroBrowserController.detach(wv) }
            runCatching { wv.destroy() }
        }
        webView = null
        mainContainer = null
        floatContainer = null
        _uiState.value = BrowserUiState()
    }

    // ───────────────────────── 单一通用 client ─────────────────────────

    /**
     * 挂到共享 WebView 上唯一的 WebViewClient/WebChromeClient。
     * 全屏与浮窗共用同一 WebView，由一个 client 统一维护 [uiState] 与 AI 桥标记，
     * 避免「重挂后 client 被某一端覆盖、另一端 UI 不再刷新」的隐患。
     */
    private fun attachUniversalClient(wv: WebView) {
        wv.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, u: String?, favicon: Bitmap?) {
                QuroBrowserController.markPageStarted(u)
                _uiState.value = _uiState.value.copy(
                    url = u ?: _uiState.value.url,
                    isLoading = true,
                    loadError = null,
                )
            }

            override fun onPageFinished(view: WebView?, u: String?) {
                QuroBrowserController.markPageFinished(u)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    canGoBack = view?.canGoBack() ?: false,
                    canGoForward = view?.canGoForward() ?: false,
                )
                // 历史记录：页面加载完成写入（全屏/浮窗共用此 client，都会记录）
                if (view != null && !u.isNullOrEmpty()) recordHistory(view.context, u, view.title)
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true) {
                    _uiState.value = _uiState.value.copy(
                        loadError = "页面加载失败：${error?.description ?: "未知错误"} (code=${error?.errorCode})",
                    )
                }
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: WebResourceResponse?,
            ) {
                if (request?.isForMainFrame == true) {
                    val code = errorResponse?.statusCode ?: 0
                    _uiState.value = _uiState.value.copy(
                        loadError = "页面加载失败：HTTP $code (${request.url})",
                    )
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val u = request?.url ?: return false
                val scheme = u.scheme?.lowercase() ?: return false
                if (scheme in WEB_SCHEMES) return false
                return launchExternalScheme(view?.context ?: return false, u)
            }

            @Suppress("DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView?, u: String?): Boolean {
                if (u.isNullOrEmpty()) return false
                val parsed = runCatching { Uri.parse(u) }.getOrNull() ?: return false
                val scheme = parsed.scheme?.lowercase() ?: return false
                if (scheme in WEB_SCHEMES) return false
                return launchExternalScheme(view?.context ?: return false, parsed)
            }
        }
        wv.webChromeClient = object : WebChromeClient() {
            // 站点请求定位 → 自动授予（地图/本地类站点；对齐 ZorvBrowser，避免静默失败导致页面不可用）。
            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: GeolocationPermissions.Callback?,
            ) {
                callback?.invoke(origin, true, false)
            }

            // 站点请求相机/麦克风 → 自动授予（视频通话/录音类站点）。
            override fun onPermissionRequest(request: PermissionRequest?) {
                if (request == null) return
                try {
                    request.grant(request.resources)
                } catch (_: Throwable) {
                    try { request.deny() } catch (_: Throwable) {}
                }
            }

            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                _uiState.value = _uiState.value.copy(
                    progress = newProgress,
                    isLoading = newProgress < 100,
                )
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                if (!title.isNullOrEmpty()) {
                    QuroBrowserController.markTitle(title)
                    _uiState.value = _uiState.value.copy(title = title)
                }
            }
        }
    }

    /** Compose 便捷：收集共享浏览器状态。 */
    @Composable
    fun collectUiState(): State<BrowserUiState> = uiState.collectAsState()

    // ───────────────────────── 历史记录（持久化） ─────────────────────────

    /** 历史记录条目（title + url + 时间戳）。 */
    data class HistoryEntry(val title: String, val url: String, val ts: Long)

    /** 读取浏览历史（最新在前）。 */
    fun loadHistory(context: Context): List<HistoryEntry> {
        val sp = context.getSharedPreferences(HISTORY_PREFS, Context.MODE_PRIVATE)
        val raw = sp.getString(HISTORY_KEY, null) ?: return emptyList()
        return runCatching {
            val arr = org.json.JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                HistoryEntry(o.optString("title"), o.getString("url"), o.optLong("ts"))
            }
        }.getOrDefault(emptyList())
    }

    /** 清空浏览历史。 */
    fun clearHistory(context: Context) {
        context.getSharedPreferences(HISTORY_PREFS, Context.MODE_PRIVATE)
            .edit().remove(HISTORY_KEY).apply()
    }

    /** 写入一条历史：同 URL 去重并提到最前，最多保留 [HISTORY_MAX] 条。只记 http/https 页面。 */
    private fun recordHistory(context: Context, url: String, title: String?) {
        if (!url.startsWith("http")) return
        try {
            val sp = context.getSharedPreferences(HISTORY_PREFS, Context.MODE_PRIVATE)
            val raw = sp.getString(HISTORY_KEY, null)
            val arr = if (raw != null) org.json.JSONArray(raw) else org.json.JSONArray()
            val list = mutableListOf<org.json.JSONObject>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optString("url") != url) list.add(o)
            }
            list.add(
                0,
                org.json.JSONObject().apply {
                    put("title", title?.takeIf { it.isNotBlank() } ?: url)
                    put("url", url)
                    put("ts", System.currentTimeMillis())
                },
            )
            if (list.size > HISTORY_MAX) list.subList(HISTORY_MAX, list.size).clear()
            val out = org.json.JSONArray()
            list.forEach { out.put(it) }
            sp.edit().putString(HISTORY_KEY, out.toString()).apply()
        } catch (_: Exception) {
        }
    }

    private const val HISTORY_PREFS = "quro_browser"
    private const val HISTORY_KEY = "history"
    private const val HISTORY_MAX = 500

    /** 共享浏览器 UI 状态快照（全屏/浮窗共用同一 WebView，状态统一）。 */
    data class BrowserUiState(
        val url: String = "",
        val title: String = "",
        val isLoading: Boolean = false,
        val progress: Int = 0,
        val canGoBack: Boolean = false,
        val canGoForward: Boolean = false,
        val loadError: String? = null,
    )
}

// WebView 自己能处理的 scheme：交给 WebView 加载；其它自定义 scheme 调系统 Intent 跳对应 APP。
private val WEB_SCHEMES = setOf("http", "https", "file", "about", "data", "javascript", "blob", "content")

private fun launchExternalScheme(context: Context, u: Uri): Boolean {
    return try {
        // intent:// 协议拆包：Android 要求用 Intent.parseUri 拿到真实 intent
        val intent: Intent? = if (u.scheme?.lowercase() == "intent") {
            runCatching { Intent.parseUri(u.toString(), Intent.URI_INTENT_SCHEME) }.getOrNull()
        } else {
            Intent(Intent.ACTION_VIEW, u)
        }
        if (intent == null) {
            Toast.makeText(context, "无法解析链接：$u", Toast.LENGTH_SHORT).show()
            true
        } else {
            intent.flags = intent.flags or Intent.FLAG_ACTIVITY_NEW_TASK
            intent.addCategory(Intent.CATEGORY_BROWSABLE)
            context.startActivity(intent)
            true
        }
    } catch (e: android.content.ActivityNotFoundException) {
        Toast.makeText(context, "未安装可处理 ${u.scheme}:// 的应用", Toast.LENGTH_SHORT).show()
        true
    } catch (e: Exception) {
        Toast.makeText(context, "跳转失败：${e.message}", Toast.LENGTH_SHORT).show()
        true
    }
}
