package com.zorv.plugin.zorvweb

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.ai.assistance.quro.plugin.extension.SurfaceBackHandler
import com.ai.assistance.quro.plugin.extension.SurfaceHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * 浏览器界面（插件自带，由宿主 PluginSurfaceActivity 承载）。
 *
 * 做成**纯代码 View 树**而不是 XML/Compose：
 *  - 插件资源的 inflate 需要宿主配合（RenderContext.inflate 才有），代码构建零依赖、最稳；
 *  - 宿主是 Compose 应用，但 AndroidView 混排本来就允许传统 View，没必要为此引入 Compose 运行时。
 *
 * 与 AI 工具**共用同一个 [WebEngine]**：AI 用 web_open 打开的页面，用户点「打开浏览器」就能直接看到，
 * 手输的地址栏跳转 AI 也能立刻读到 —— 一个引擎两个入口，不是两套状态。
 */
internal class BrowserSurfaceView(
    private val actCtx: Context,
    private val host: SurfaceHost,
) : LinearLayout(actCtx), SurfaceBackHandler {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val urlBar = EditText(actCtx)
    private val progress = ProgressBar(actCtx, null, android.R.attr.progressBarStyleHorizontal)
    private val container = FrameLayout(actCtx)
    private val status = TextView(actCtx)
    private var polling: Job? = null

    /**
     * 当前标签能否后退 —— 由 [refresh] 轮询时写入，供 [onSurfaceBack] 同步读取。
     *
     * 为什么不在 onSurfaceBack 里直接查：它由宿主的返回键回调同步调用，不能是 suspend，
     * 而 WebEngine.tabsList() 是 suspend 函数（内部要切主线程问 WebView）。
     * 轮询本来每 900ms 就在取同一份状态，缓存下来零额外开销，返回键也永远即时响应。
     */
    private var canBack: Boolean = false

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.WHITE)

        addView(buildToolbar())
        addView(
            progress,
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 4)
        )
        addView(
            container,
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        addView(buildStatusBar())

        scope.launch {
            WebEngine.init(actCtx)
            WebEngine.attach(container)
            startPolling()
        }
    }

    @SuppressLint("SetTextI18n")
    private fun buildToolbar(): View {
        val bar = LinearLayout(actCtx).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8, 8, 8, 8)
            setBackgroundColor(Color.parseColor("#F2F3F5"))
        }

        fun navBtn(text: String, cmd: String) = Button(actCtx).apply {
            this.text = text
            textSize = 13f
            minWidth = 0
            minimumWidth = 0
            setPadding(18, 0, 18, 0)
            setOnClickListener { scope.launch { WebEngine.nav(cmd); delay(150); refresh() } }
        }

        bar.addView(navBtn("◀", "back"))
        bar.addView(navBtn("▶", "forward"))
        bar.addView(navBtn("⟳", "reload"))

        bar.addView(Button(actCtx).apply {
            text = "⌂"
            textSize = 13f
            minWidth = 0
            minimumWidth = 0
            setPadding(18, 0, 18, 0)
            setOnClickListener { scope.launch { WebEngine.open(HOME, 0, false, false) } }
        })

        urlBar.apply {
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            hint = "输入网址或搜索内容"
            textSize = 14f
            setSingleLine(true)
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_GO) {
                    go(urlBar.text.toString())
                    true
                } else false
            }
        }
        bar.addView(urlBar, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = 8
            marginEnd = 8
        })

        bar.addView(Button(actCtx).apply {
            text = "前往"
            textSize = 13f
            minWidth = 0
            minimumWidth = 0
            setPadding(20, 0, 20, 0)
            setOnClickListener { go(urlBar.text.toString()) }
        })

        bar.addView(Button(actCtx).apply {
            text = "＋"
            textSize = 13f
            minWidth = 0
            minimumWidth = 0
            setPadding(20, 0, 20, 0)
            setOnClickListener {
                scope.launch { WebEngine.tabsAction("new", -1, HOME); refresh() }
            }
        })
        return bar
    }

    @SuppressLint("SetTextI18n")
    private fun buildStatusBar(): View = LinearLayout(actCtx).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(12, 6, 12, 6)
        setBackgroundColor(Color.parseColor("#F2F3F5"))
        addView(
            status.apply {
                textSize = 11f
                setTextColor(Color.parseColor("#666666"))
                text = "引擎启动中…"
            },
            LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        addView(Button(actCtx).apply {
            text = "关标签"
            textSize = 12f
            minWidth = 0
            minimumWidth = 0
            setOnClickListener {
                scope.launch {
                    val cur = runCatching { JSONObject(WebEngine.tabsList()).optInt("active", 0) }.getOrDefault(0)
                    WebEngine.tabsAction("close", cur, "")
                    refresh()
                }
            }
        })
    }

    private fun go(input: String) {
        val q = input.trim()
        if (q.isEmpty()) return
        // 不是地址就当搜索词（与原版「地址栏兼搜索框」一致）
        val url = if (q.contains(" ") || !q.contains(".")) {
            "https://www.bing.com/search?q=" + java.net.URLEncoder.encode(q, "UTF-8")
        } else q
        urlBar.setText(url)
        scope.launch {
            WebEngine.open(url, 0, false, false)
            refresh()
        }
    }

    private fun startPolling() {
        polling?.cancel()
        polling = scope.launch {
            while (isActive) {
                refresh()
                delay(900)
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private suspend fun refresh() {
        val raw = runCatching { WebEngine.tabsList() }.getOrNull() ?: return
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return
        val tabs = o.optJSONArray("tabs")
        val activeIdx = o.optInt("active", 0)
        val cur = if (tabs != null && activeIdx in 0 until tabs.length()) tabs.optJSONObject(activeIdx) else null

        val url = cur?.optString("url").orEmpty()
        val title = cur?.optString("title").orEmpty()
        val loading = cur?.optBoolean("loading", false) ?: false
        canBack = cur?.optBoolean("can_back", false) ?: false

        if (!urlBar.hasFocus() && url.isNotEmpty() && urlBar.text.toString() != url) {
            urlBar.setText(url)
            urlBar.setSelection(url.length)
        }
        progress.visibility = if (loading) View.VISIBLE else View.GONE
        status.text = buildString {
            append("标签 ${o.optInt("count", 0)}")
            if (title.isNotBlank()) append(" · $title")
            append(if (loading) " · 加载中…" else " · 就绪")
        }
    }

    /** 系统返回键：先回退网页，退无可退才关界面。读 [refresh] 缓存的 canBack，保持同步。 */
    override fun onSurfaceBack(): Boolean {
        if (!canBack) return false
        // 乐观置位：立刻允许下一次返回键继续回退，不必等 900ms 轮询刷新
        canBack = false
        scope.launch { WebEngine.nav("back") }
        return true
    }

    /** 界面关闭：摘除 WebView（不销毁、不 onPause），引擎继续为 AI 工具服务。 */
    fun release() {
        polling?.cancel()
        scope.launch {
            runCatching { WebEngine.detach() }
            scope.cancel()
        }
    }

    companion object {
        const val SURFACE_ID = "zorvweb_browser"
        private const val HOME = "https://www.bing.com"
    }
}
