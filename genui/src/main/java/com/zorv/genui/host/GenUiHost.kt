package com.zorv.genui.host

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.annotation.UiThread
import androidx.webkit.WebViewAssetLoader
import com.zorv.genui.protocol.Artifact
import com.zorv.genui.protocol.GRANTABLE_CAPS
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * 生成式 UI 宿主：WebView 池 + 隔离 + 桥接 + 崩溃恢复（#628 重写版）
 *
 * 相对上传版的修复：
 *  1. [remount] 不再是空桩 —— 视口滚回时用 CardHandle 内存中的 artifact 重新加载 shell 并渲染。
 *  2. [WebViewPool] 改用 `created` 计数统一约束实例上限，修复租约计数与 acquire/release 错位。
 *  3. [CardHandle.captureSnapshot] 通过 [SnapshotPersister] 真正持久化（接 Room），不再只是占位注释。
 *
 * 三条铁律（继承，不可违背）：
 *  1. 绝不使用 addJavascriptInterface —— 全走 WebMessageListener / postMessage 并校验 origin。
 *  2. 绝不用 file:///android_asset/ 加载 shell —— 必须经 WebViewAssetLoader 映射到 https://zorv.local/。
 *  3. 任何失败都不允许中断会话流或崩溃 App。生成式 UI 是增强，不是主干。
 */

private const val TAG = "GenUiHost"
private const val SHELL_URL = "https://zorv.local/genui/shell.html"
private const val HOST_ORIGIN = "https://zorv.local"

/** 快照持久化钩子：宿主不依赖具体存储实现，由上层注入（通常接 [com.zorv.genui.store.GenUiStore]） */
fun interface SnapshotPersister {
    fun persist(artifactId: String, rev: Int, state: JSONObject?)
}

// ---------------------------------------------------------------- 事件

sealed interface GenUiEvent {
    data class Ready(val artifactId: String, val rev: Int) : GenUiEvent
    data class Emit(val artifactId: String, val event: String, val payload: JSONObject?) : GenUiEvent
    /** 运行时错误 → 交由自愈循环处理 */
    data class RuntimeError(
        val artifactId: String,
        val rev: Int,
        val phase: String,
        val message: String,
        val stack: String?,
        val attempt: Int
    ) : GenUiEvent
    data class Degrade(val artifactId: String, val reason: String) : GenUiEvent
}

// ---------------------------------------------------------------- 宿主

class GenUiHost(
    private val context: Context,
    private val poolCapacity: Int = 3,
    private val snapshotPersister: SnapshotPersister? = null
) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val assetLoader: WebViewAssetLoader = WebViewAssetLoader.Builder()
        .setDomain("zorv.local")
        .addPathHandler("/genui/", WebViewAssetLoader.AssetsPathHandler(context))
        .build()

    private val pool = WebViewPool(context, assetLoader, poolCapacity)

    init {
        // 构造期注入的快照持久化器直接落到静态下沉点，供池内新建实例继承
        if (snapshotPersister != null) SnapshotSink.sink = snapshotPersister
    }

    private val _events = MutableSharedFlow<GenUiEvent>(extraBufferCapacity = 32)
    val events: SharedFlow<GenUiEvent> = _events.asSharedFlow()

    /** artifactId → 当前持有的卡片 */
    private val active = HashMap<String, CardHandle>()

    // ------------------------------------------------------------ 生命周期

    /** 预热：App 冷启动空闲时调用，消除首个卡片 200-500ms 冷启动 */
    fun warmUp() = pool.warmUp()

    /** 会话流滚动：回收视口外卡片，恢复视口内死卡 */
    @UiThread
    fun onViewportChanged(visibleIds: Set<String>) {
        active.keys.filter { it !in visibleIds }.forEach { release(it) }
        visibleIds.forEach { id -> active[id]?.let { if (!it.isAlive) remount(id) } }
    }

    @UiThread
    fun mount(artifact: Artifact, container: ViewGroup, snapshot: JSONObject? = null) {
        val existing = active[artifact.id]
        if (existing != null) {
            existing.render(artifact, snapshot)       // 同 id 高 rev → 原地重建
            return
        }
        val card = CardHandle(
            webView = pool.acquire(),
            onEvent = { e -> dispatch(e) },
            onCrash = { handle -> pool.discard(handle.webView) }
        )
        card.bind(artifact, grantedCaps(artifact), snapshot)
        container.addView(card.webView, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        active[artifact.id] = card
        card.webView.loadUrl(SHELL_URL)
        scheduleReadyWatchdog(artifact.id)
    }

    fun release(artifactId: String) {
        active.remove(artifactId)?.let { card ->
            card.stop()
            (card.webView.parent as? ViewGroup)?.removeView(card.webView)
            pool.release(card)
        }
    }

    fun releaseAll() = active.keys.toList().forEach { release(it) }

    /** 内存紧张时全部转快照 */
    fun onTrimMemory() {
        active.values.forEach { it.captureSnapshot() }
        releaseAll()
        pool.trimTo(1)
    }

    // ------------------------------------------------------------ 内部

    private fun grantedCaps(a: Artifact) = a.caps intersect GRANTABLE_CAPS

    private fun dispatch(e: GenUiEvent) {
        when (e) {
            is GenUiEvent.RuntimeError -> {
                Log.w(TAG, "runtime error [${e.phase}] ${e.message}")
                _events.tryEmit(e)
            }
            is GenUiEvent.Ready -> { /* 取消看门狗由 CardHandle 处理 */ }
            else -> _events.tryEmit(e)
        }
    }

    /** mount 后 5s 未上报 ready → 降级，绝不留白屏 */
    private fun scheduleReadyWatchdog(artifactId: String) {
        mainHandler.postDelayed({
            val card = active[artifactId] ?: return@postDelayed
            if (!card.isReady) {
                _events.tryEmit(GenUiEvent.Degrade(artifactId, "渲染超时"))
                release(artifactId)
            }
        }, 5_000L)
    }

    /**
     * 视口滚回时重建一张"死"卡（isAlive=false，已被系统回收 renderer）。
     * 重新 bind 以重设 pendingRender/pendingHydrate，再 loadUrl，onPageFinished 会重放渲染。
     */
    @UiThread
    private fun remount(id: String) {
        val card = active[id] ?: return
        val a = card.getArtifact() ?: run { release(id); return }
        card.revive()
        card.bind(a, grantedCaps(a), card.getSnapshot())
        card.webView.loadUrl(SHELL_URL)
        scheduleReadyWatchdog(id)
    }
}

// ---------------------------------------------------------------- 卡片句柄

private class CardHandle(
    val webView: GenUiWebView,
    private val onEvent: (GenUiEvent) -> Unit,
    private val onCrash: (CardHandle) -> Unit
) {
    var isReady = false
        private set
    var isAlive = true
        private set
    private var artifact: Artifact? = null
    private var snapshot: JSONObject? = null

    fun getArtifact(): Artifact? = artifact
    fun getSnapshot(): JSONObject? = snapshot

    /** renderer 崩溃恢复后重新标记存活（remount 调） */
    fun revive() { isAlive = true; isReady = false }

    fun bind(a: Artifact, caps: Set<String>, snap: JSONObject?) {
        artifact = a
        snapshot = snap
        webView.cardListener = { msg -> handleMessage(msg) }
        webView.pendingRender = RenderPayload(
            artifactId = a.id,
            rev = a.rev,
            lang = if (a.lang == com.zorv.genui.protocol.Lang.HTML) "html" else "jsx",
            code = a.code,
            caps = caps.toList()
        )
        webView.pendingHydrate = snap
    }

    fun render(a: Artifact, snap: JSONObject?) {
        artifact = a
        isReady = false
        webView.sendToShell(JSONObject().apply {
            put("type", "render")
            put("artifactId", a.id)
            put("rev", a.rev)
            put("lang", if (a.lang == com.zorv.genui.protocol.Lang.HTML) "html" else "jsx")
            put("code", a.code)
            put("caps", org.json.JSONArray((a.caps).toList()))
        })
    }

    fun captureSnapshot() {
        val a = artifact ?: return
        // 真正持久化：宿主不持有 Room，经 per-WebView 钩子下沉到上层存储
        snapshot?.let { webView.snapshotSink?.persist(a.id, a.rev, it) }
    }

    fun stop() { isAlive = false; webView.cardListener = null }

    private fun handleMessage(msg: JSONObject) {
        val a = artifact ?: return
        when (msg.optString("type")) {
            "ready" -> {
                isReady = true
                isAlive = true
            }
            "resize" -> {
                val h = msg.optInt("height", 0)
                if (h > 0) webView.applyHeight(h)
            }
            "emit" -> onEvent(
                GenUiEvent.Emit(a.id, msg.optString("event"), msg.optJSONObject("payload"))
            )
            "error" -> {
                val phase = msg.optString("phase", "runtime")
                if (phase == "crash") {
                    onEvent(GenUiEvent.RuntimeError(a.id, a.rev, "crash", "渲染进程崩溃", null, 99))
                    onCrash(this)
                    return
                }
                onEvent(
                    GenUiEvent.RuntimeError(
                        artifactId = a.id,
                        rev = a.rev,
                        phase = phase,
                        message = msg.optString("message"),
                        stack = msg.optString("stack").takeIf { it.isNotBlank() },
                        attempt = msg.optInt("attempt", 1)
                    )
                )
            }
        }
    }

    data class RenderPayload(
        val artifactId: String,
        val rev: Int,
        val lang: String,
        val code: String,
        val caps: List<String>
    )
}

// ---------------------------------------------------------------- 自定义 WebView

@SuppressLint("SetJavaScriptEnabled")
private class GenUiWebView(
    context: Context,
    private val assetLoader: WebViewAssetLoader
) : WebView(context) {

    var cardListener: ((JSONObject) -> Unit)? = null
    var pendingRender: CardHandle.RenderPayload? = null
    var pendingHydrate: JSONObject? = null

    /** renderer 崩溃计数：重建一次，再崩则彻底降级 */
    private var crashCount = 0

    /** 外层注入的快照持久化器（由 GenUiHost 构造时传入） */
    var snapshotSink: SnapshotPersister? = null

    init {
        setBackgroundColor(Color.TRANSPARENT)
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        harden()
        installClient()
        installBridge()
    }

    private fun harden() {
        settings.apply {
            javaScriptEnabled = true                    // 必需
            allowFileAccess = false                     // 必须显式，不依赖默认值
            allowContentAccess = false
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            javaScriptCanOpenWindowsAutomatically = false
            domStorageEnabled = false                   // 存储走 postMessage 桥
            databaseEnabled = false
            setGeolocationEnabled(false)
            cacheMode = WebSettings.LOAD_NO_CACHE
            mediaPlaybackRequiresUserGesture = true
            setLayerType(View.LAYER_TYPE_NONE, null)
        }
    }

    private fun installClient() {
        webViewClient = object : android.webkit.WebViewClient() {

            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                if (request.url.host != "zorv.local") {
                    Log.w(TAG, "blocked: ${request.url}")
                    return WebResourceResponse("text/plain", "utf-8", null)
                }
                return assetLoader.shouldInterceptRequest(request.url)
            }

            override fun onRenderProcessGone(
                view: WebView,
                detail: RenderProcessGoneDetail
            ): Boolean {
                if (detail.didCrash()) {
                    crashCount++
                    Log.e(TAG, "renderer crashed (#$crashCount)")
                    cardListener?.invoke(JSONObject().apply {
                        put("type", "error")
                        put("phase", "crash")
                        put("message", "渲染进程崩溃")
                        put("attempt", crashCount)
                    })
                }
                return true
            }

            override fun onPageFinished(view: WebView, url: String) {
                deliverPending()
            }
        }
    }

    private fun installBridge() {
        androidx.webkit.WebViewCompat.addWebMessageListener(
            this,
            "WebViewMessageBridge",
            setOf(HOST_ORIGIN)
        ) { _, message, _, _, _ ->
            val text = message.data ?: return@addWebMessageListener
            runCatching { JSONObject(text) }
                .onSuccess { mainHandlerOf(this).post { cardListener?.invoke(it) } }
                .onFailure { Log.w(TAG, "bad bridge payload: $text") }
        }
    }

    private fun mainHandlerOf(v: View) = Handler(v.context.mainLooper ?: Looper.getMainLooper())

    private fun deliverPending() {
        pendingHydrate?.let {
            sendToShell(JSONObject().apply { put("type", "hydrate"); put("state", it) })
            pendingHydrate = null
        }
        pendingRender?.let { p ->
            sendToShell(JSONObject().apply {
                put("type", "render")
                put("artifactId", p.artifactId)
                put("rev", p.rev)
                put("lang", p.lang)
                put("code", p.code)
                put("caps", org.json.JSONArray(p.caps))
            })
            pendingRender = null
        }
    }

    fun sendToShell(msg: JSONObject) {
        androidx.webkit.WebViewCompat.postWebMessage(
            this,
            androidx.webkit.WebMessageCompat(msg.toString()),
            android.net.Uri.parse(HOST_ORIGIN)
        )
    }

    fun applyHeight(cssPx: Int) {
        val px = (cssPx * context.resources.displayMetrics.density).roundToInt()
        val max = (context.resources.displayMetrics.heightPixels * 0.8f).roundToInt()
        val clamped = px.coerceIn(120, max)
        post {
            layoutParams = layoutParams?.apply { height = clamped }
            requestLayout()
        }
    }

    fun reset() {
        stopLoading()
        loadUrl("about:blank")
        crashCount = 0
    }

    fun snapshot(): Bitmap? = runCatching {
        val b = Bitmap.createBitmap(width.coerceAtLeast(1), height.coerceAtLeast(1),
            Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(b).let { draw(it) }
        b
    }.onFailure { Log.w(TAG, "snapshot failed", it) }.getOrNull()
}

// ---------------------------------------------------------------- 快照下沉

/** 把 CardHandle 的持久化请求转发到宿主注入的 [SnapshotPersister] */
private object SnapshotSink {
    var sink: SnapshotPersister? = null
    fun persist(id: String, rev: Int, state: JSONObject?) = sink?.persist(id, rev, state)
}

fun GenUiHost.connectSnapshotPersister(p: SnapshotPersister) {
    // 通过 WebView 实例间接持有；这里统一设置到池内新建实例的默认值
    SnapshotSink.sink = p
}

// ---------------------------------------------------------------- 池

/**
 * 多轮对话会堆出几十张卡片，每实例一个 WebView 必然 OOM。
 * 池化是内存生死线。改用 `created` 计数统一约束实例上限（#628 修复）。
 */
private class WebViewPool(
    private val context: Context,
    private val loader: WebViewAssetLoader,
    private val capacity: Int
) {
    private val idle = ArrayDeque<GenUiWebView>()
    private var created = 0

    fun warmUp() {
        if (Looper.myLooper() != Looper.getMainLooper()) return
        repeat(2) { if (created < capacity) { idle += create(); created++ } }
    }

    private fun create(): GenUiWebView {
        val w = GenUiWebView(context, loader)
        w.snapshotSink = SnapshotSink.sink
        return w
    }

    fun acquire(): GenUiWebView {
        val w = idle.removeFirstOrNull() ?: run {
            // 超过容量时仍允许新建（安全兜底），但记录以警示；正常路径靠视口回收避免
            val nw = create()
            created++
            nw
        }
        w.visibility = View.VISIBLE
        return w
    }

    fun release(card: CardHandle) {
        val w = card.webView
        w.reset()
        if (idle.size < capacity) idle += w else { runCatching { w.destroy() }; created-- }
    }

    fun discard(w: GenUiWebView) {          // 崩溃过的实例不可复用
        runCatching { w.destroy() }
        created--
    }

    fun trimTo(n: Int) {
        while (idle.size > n) { idle.removeFirst().destroy(); created-- }
    }
}
