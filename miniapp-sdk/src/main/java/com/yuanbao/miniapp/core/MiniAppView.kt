package com.yuanbao.miniapp.core

import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import com.yuanbao.miniapp.nativeapi.WxApi
import com.yuanbao.miniapp.pack.AppConfig
import com.yuanbao.miniapp.pack.MiniPackage
import com.yuanbao.miniapp.render.CanvasPainter
import com.yuanbao.miniapp.render.FlexLayout
import com.yuanbao.miniapp.render.NodeType
import com.yuanbao.miniapp.render.Overflow
import com.yuanbao.miniapp.render.RenderNode
import com.yuanbao.miniapp.render.TextMeasurer
import com.yuanbao.miniapp.util.Json
import com.yuanbao.miniapp.util.parseJson
import com.yuanbao.miniapp.view.TemplateNode
import com.yuanbao.miniapp.view.VDomBuilder
import com.yuanbao.miniapp.view.WxssParser
import com.yuanbao.miniapp.view.WxmlParser

/**
 * A running mini program instance.
 *
 * The view owns:
 *  - a plain View painted by our own renderer via onDraw（**不是 SurfaceView**，
 *    也不再是独立图层；见 renderView 注释）
 *  - the logic runtime (JS engine on its own thread)
 *  - the page stack and the navigation host implementation
 */
class MiniAppView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : FrameLayout(context, attrs, defStyle),
    LogicRuntime.RenderHost,
    WxApi.NavigationHost {

    /**
     * 画面承载层：普通 View + onDraw，**不用 SurfaceView**。
     *
     * 旧实现是 SurfaceView 且 setZOrderOnTop(true)：那是把画面提到"整个窗口之上"的独立图层。
     * 代价是
     *  1) 它不受父容器裁剪 —— 卡片内容会画到卡片外面，盖住顶栏/输入栏/相邻消息，即"一层盖一层"；
     *  2) 它和 Compose 的 UI 不在同一图层 —— 弹窗、遮罩盖不住它；
     *  3) 它是独立缓冲，一旦没成功出帧就是一块**全透明的空**，看起来正是"白屏"；
     *  4) 在 LazyColumn 这类滚动容器里该图层的位置/尺寸刷新时机不受控，会出现错位。
     *
     * 换成普通 View 后，绘制回归视图树：被卡片边界裁剪、随列表一起滚动、被弹窗正常覆盖、
     * 失效时自动重绘，上述四类问题一次性消失。绘制量（背景/圆角/文字/图片）都是硬件加速 Canvas 的常规操作，
     * 一次全页重绘对卡片级小程序完全在预算内。
     */
    private val renderView = object : View(context) {
        private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFD9A05B.toInt()
            textSize = 13f * resources.displayMetrics.density
        }

        /** 贪心折行（按字符，中文友好），最多 6 行。 */
        private fun wrapHint(s: String, maxW: Float): List<String> {
            val out = ArrayList<String>()
            var line = StringBuilder()
            for (ch in s) {
                line.append(ch)
                if (hintPaint.measureText(line.toString()) > maxW && line.length > 1) {
                    out.add(line.substring(0, line.length - 1))
                    line = StringBuilder().append(ch)
                }
            }
            if (line.isNotEmpty()) out.add(line.toString())
            return out.take(6)
        }

        /**
         * 把"为什么什么都没有"直接写在画面上。
         * 自研引擎出问题时以前只剩一块纯白（painter 先涂底色），用户和排查者都无从下手；
         * 这里在无法渲染时于画布上打出原因，**内嵌卡片与全屏 Activity 都生效**（不依赖宿主配合）。
         */
        private fun drawBlankHint(canvas: Canvas, msg: String) {
            val d = resources.displayMetrics.density
            val pad = 14f * d
            hintPaint.textSize = 13f * d
            var y = pad + hintPaint.textSize
            for (l in wrapHint(msg, width - pad * 2)) {
                canvas.drawText(l, pad, y, hintPaint)
                y += hintPaint.textSize * 1.45f
            }
        }

        override fun onDraw(canvas: Canvas) {
            val entry = pageStack.lastOrNull()
            if (width <= 0 || height <= 0) return
            val root = entry?.root
            if (root == null) {
                drawBlankHint(canvas, "页面未建立：${entry?.path ?: "无入口"}")
                return
            }
            painter.rpxRatio = layoutEngine.rpxRatio
            painter.draw(canvas, root, width.toFloat(), height.toFloat())
            // 只画到根节点 = 页面上没有任何可见内容（数据没到 / wx:if 全假 / 模板没解析出东西）。
            // 这是"一块白"的真正含义——把原因写出来，别再让人对着白猜。
            if (painter.lastDrawnNodes <= 1) {
                val wxmlChars = entry?.wxmlChars ?: 0
                drawBlankHint(
                    canvas,
                    if (wxmlChars == 0) "包内取不到页面模板：${entry?.path ?: "-"}（wxml 0 字）"
                    else "页面无可见内容：wxml ${wxmlChars} 字 / 数据 ${dataFieldCount} 键" +
                        (lastError?.let { " / ⚠$it" } ?: "")
                )
            }
            notifyFirstFrame()
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            // 视口尺寸变了 → 用它重新布局（viewportW/H 为 0 时布局会退化成 0x0 的空白页）
            viewportW = w.toFloat()
            viewportH = h.toFloat()
            layoutEngine = FlexLayout(viewportW, viewportH)
            relayout()
            invalidate()
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            surfaceReady = true
            invalidate()
        }

        override fun onDetachedFromWindow() {
            super.onDetachedFromWindow()
            surfaceReady = false
        }
    }
    private val overlay = FrameLayout(context)
    private val painter = CanvasPainter()
    private lateinit var logic: LogicRuntime
    private lateinit var wxApi: WxApi
    private var appConfig: AppConfig = AppConfig.empty()
    private var pkg: MiniPackage? = null

    @Volatile private var surfaceReady = false

    /** 最近一次引擎错误（诊断串回显用；页面全白时这是唯一线索）。 */
    @Volatile private var lastError: String? = null

    /** 最近一条引擎日志（wx 兜底调用等会走这里）。 */
    @Volatile private var lastLog: String? = null

    /** 逻辑层 setData 过来的顶层字段数（0 通常意味着页面数据没跑起来）。 */
    @Volatile private var dataFieldCount: Int = 0

    private val pageStack = ArrayList<PageEntry>()
    private var vdom = VDomBuilder()
    private var layoutEngine = FlexLayout(1f, 1f)

    private var titleBar: ((String) -> Unit)? = null
    private var logListener: ((String, String) -> Unit)? = null
    private var errorListener: ((String) -> Unit)? = null
    private var readyListener: ((String) -> Unit)? = null

    private var inputEditor: EditText? = null
    private var inputDialog: Dialog? = null
    private var activeInputNode: RenderNode? = null

    private var viewportW = 0f
    private var viewportH = 0f

    private data class PageEntry(
        val path: String,
        val params: Map<String, String>,
        var template: TemplateNode,
        var rules: List<WxssParser.Rule>,
        var data: Json = Json.Obj(),
        var root: RenderNode? = null,
        var pageStyle: com.yuanbao.miniapp.render.Style? = null,
        var hasShown: Boolean = false,
        /** 该页 wxml 原文长度（诊断用：0 = 包里有 app.json 但取不到页面文件）。 */
        var wxmlChars: Int = 0,
        /** 页面 js 长度（诊断用：0 = 逻辑层没拿到脚本）。 */
        var jsChars: Int = 0,
        val scrollState: HashMap<Int, Float> = HashMap()
    )

    init {
        addView(renderView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(overlay, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        // 宿主不强制白底：小程序页面自己的 wxss 背景色由 renderView 绘制覆盖；
        // 若页面未设背景，再透出其下窗口背景，而不是由 SDK 固定死白色。
        setBackgroundColor(Color.TRANSPARENT)
        // renderView 透明背景：onDraw 全部清屏动作由 painter 负责（它先涂页面底色）
        renderView.setBackgroundColor(Color.TRANSPARENT)
    }

    // ------------------------------------------------------------ lifecycle
    fun start(packageLoader: MiniPackage, config: AppConfig) {
        pkg = packageLoader
        appConfig = config
        wxApi = WxApi(context, this, overlay)
        logic = LogicRuntime(packageLoader, wxApi, this, object : LogicRuntime.EngineListener {
            // 记录与转发分开：即使宿主一个监听都没装（或装得比 start 晚），
            // 引擎内部也留得住"最后一次错/最后一条日志"，diagnose 时不会一问三不知。
            override fun onLog(level: String, message: String) {
                lastLog = message.replace('\n', ' ').trim().take(140)
                logListener?.invoke(level, message)
            }
            override fun onError(message: String) {
                lastError = message.replace('\n', ' ').trim().take(180)
                errorListener?.invoke(message)
            }
            override fun onLifecycle(event: String, page: String) {
                if (event == "onReady") readyListener?.invoke(page)
            }
        })
        var entry = config.entry
        // 入口兜底第二道：app.json 连宽松解析都给不出 pages（写歪了 / 根本没写 app.json 的 pages），
        // 就从包里直接挑一个页面当入口。少了这道兜底，start() 一个页面都不 push，
        // 卡片永远停在"页面未建立"，看起来就是"AI 什么也没写进对话框"。
        if (entry.isEmpty()) {
            val wxmls = pkg?.filePaths()?.filter { it.endsWith(".wxml") }.orEmpty()
            val first = wxmls.firstOrNull { it.equals("pages/index/index.wxml", ignoreCase = true) }
                ?: wxmls.minByOrNull { it.length }
            if (first != null) {
                entry = first.removeSuffix(".wxml")
                lastError = "app.json 无可用 pages，已改用包内页面 $entry"
            }
        }
        if (entry.isNotEmpty()) {
            pushPage(entry, emptyMap())
        }
    }

    fun stop() {
        if (::logic.isInitialized) logic.destroy()
    }

    fun setTitleListener(listener: (String) -> Unit) { titleBar = listener }
    fun setLogListener(listener: (String, String) -> Unit) { logListener = listener }
    fun setErrorListener(listener: (String) -> Unit) { errorListener = listener }
    fun setReadyListener(listener: (String) -> Unit) { readyListener = listener }

    fun currentPagePath(): String = pageStack.lastOrNull()?.path ?: ""

    /**
     * 诊断用状态串：页面路径 / 布局尺寸 / 是否已出首帧 / Surface 是否就绪。
     * 宿主把它显示在 Surface 之外，页面渲染异常时能直接看到卡在哪一步。
     */
    fun debugStatus(): String {
        val e = pageStack.lastOrNull()
        val r = e?.root
        val w = r?.width?.toInt() ?: 0
        val h = r?.height?.toInt() ?: 0
        val nodes = if (r == null) 0 else countNodes(r)
        return "pkg=${pkg?.appId ?: "-"} files=${pkg?.fileCount() ?: 0} " +
            "page=${e?.path ?: "-"} wxml=${e?.wxmlChars ?: 0}字 js=${e?.jsChars ?: 0}字 数据=${dataFieldCount}键 " +
            "tpl娃=${e?.template?.children?.size ?: 0} " +
            "vp=${viewportW.toInt()}x${viewportH.toInt()} root=${w}x${h} 树=${nodes} " +
            "画=${painter.lastDrawnNodes} 底=#${Integer.toHexString(painter.lastClearColor)} " +
            "帧=${if (e?.hasShown == true) "有" else "无"} 挂载=${if (surfaceReady) "ok" else "off"}" +
            (lastError?.let { " ⚠$it" } ?: "") +
            (if (lastError == null) lastLog?.let { " ·$it" } ?: "" else "")
    }

    private fun countNodes(n: RenderNode): Int {
        var c = 1
        for (k in n.children) c += countNodes(k)
        return c
    }

    // ------------------------------------------------------------ frame pump
    /**
     * 请求一帧。绘制在线程无关的 View 重绘机制上完成：
     * 谁都可以调，实际绘制由系统在 UI 线程的 draw pass 里执行 → 天然与视图树同步，
     * 不会出现"后台线程画到一块没人管的缓冲里"的情况。
     */
    private fun markDirty() {
        val v = renderView
        if (v.isAttachedToWindow) {
            v.invalidate()
        } else {
            // 还未挂载：挂载后 onAttachedToWindow 会自己失效一次，这里只兜底
            v.post { v.invalidate() }
        }
    }

    /** Fires the page's onReady once the first frame has actually been drawn. */
    private fun notifyFirstFrame() {
        val entry = pageStack.lastOrNull() ?: return
        if (entry.hasShown || entry.root == null) return
        entry.hasShown = true
        post { if (::logic.isInitialized) logic.notifyReady() }
    }

    // ------------------------------------------------------------ render host
    override fun onPageLoaded(pagePath: String, wxml: String, wxss: String, pageJson: String?) {
        val tpl = try {
            WxmlParser().parse(wxml)
        } catch (e: Throwable) {
            lastError = "WXML 解析失败：${e.message?.take(140)}"
            com.yuanbao.miniapp.view.TemplateNode("#document")
        }
        val rules = WxssParser().parse(wxss)
        val entry = pageStack.lastOrNull()
        if (entry != null) {
            entry.template = tpl
            entry.rules = rules
            entry.wxmlChars = wxml.length
        } else {
            val e2 = PageEntry(pagePath, emptyMap(), tpl, rules)
            e2.wxmlChars = wxml.length
            pageStack.add(e2)
        }
        applyWindowStyle(pageJson)
        post { relayout(); markDirty() }
    }

    override fun onDataChanged(dataJson: String) {
        val parsed = runCatching { parseJson(dataJson) }.getOrElse { Json.Obj() }
        val entry = pageStack.lastOrNull() ?: return
        entry.data = parsed
        dataFieldCount = (parsed as? Json.Obj)?.fields?.size ?: 0
        post { relayout(); markDirty() }
    }

    override fun onPageClosed() {
        pageStack.clear()
        post { markDirty() }
    }

    private fun applyWindowStyle(pageJson: String?) {
        val entry = pageStack.lastOrNull() ?: return
        val windowDecls = LinkedHashMap<String, String>()
        appConfig.window.forEach { (k, v) -> windowDecls[k] = v?.toString() ?: "" }
        if (!pageJson.isNullOrBlank()) {
            runCatching {
                val j = parseJson(pageJson) as? Json.Obj ?: return@runCatching
                j.fields.forEach { (k, v) -> windowDecls[k] = v.asString() }
            }
        }
        val pageStyle = WxssParser().styleFor(entry.template, entry.rules)
        pageStyle.merge(com.yuanbao.miniapp.render.Style.fromDeclarations(windowDecls))
        entry.pageStyle = pageStyle
        val title = windowDecls["navigationBarTitleText"]
        if (!title.isNullOrEmpty()) titleBar?.invoke(title)
    }

    private fun relayout() {
        val entry = pageStack.lastOrNull() ?: return
        val root = vdom.build(entry.template, entry.data, entry.rules)
        root.style = entry.pageStyle ?: root.style
        layoutEngine.textMeasurer = { text, fontSize, bold, maxWidth ->
            val lh = if (entry.pageStyle?.lineHeight?.isNaN() == false) entry.pageStyle!!.lineHeight else fontSize * 1.25f
            TextMeasurer.layout(text, fontSize, bold, maxWidth, lh)
        }
        layoutEngine.layout(root, viewportW, viewportH)
        restoreScroll(entry, root)
        entry.root = root
        markDirty()
    }

    private fun restoreScroll(entry: PageEntry, root: RenderNode) {
        fun walk(node: RenderNode) {
            val saved = entry.scrollState[node.id]
            if (saved != null) node.scrollTop = saved
            node.children.forEach { walk(it) }
        }
        walk(root)
    }

    private fun saveScroll(entry: PageEntry) {
        val root = entry.root ?: return
        fun walk(node: RenderNode) {
            if (node.style.overflow == Overflow.SCROLL) entry.scrollState[node.id] = node.scrollTop
            node.children.forEach { walk(it) }
        }
        walk(root)
    }

    // ------------------------------------------------------------ navigation
    override fun navigateTo(page: String, params: Map<String, String>) {
        post { pushPage(page, params) }
    }

    override fun redirectTo(page: String, params: Map<String, String>) {
        post {
            if (pageStack.isNotEmpty()) {
                logic.unloadCurrentPage()
                pageStack.removeAt(pageStack.lastIndex)
            }
            pushPage(page, params)
        }
    }

    override fun navigateBack(delta: Int) {
        post {
            repeat(delta.coerceAtMost(pageStack.size - 1)) {
                if (pageStack.size <= 1) return@repeat
                logic.unloadCurrentPage()
                pageStack.removeAt(pageStack.lastIndex)
            }
            val entry = pageStack.lastOrNull() ?: return@post
            logic.loadPage(entry.path, toJsonObject(entry.params))
            logic.notifyShow()
            relayout()
            markDirty()
        }
    }

    override fun setNavigationBarTitle(title: String) { post { titleBar?.invoke(title) } }
    override fun currentPage(): String = currentPagePath()

    private fun pushPage(page: String, params: Map<String, String>) {
        var wxml = pkg?.pageWxml(page) ?: ""
        var effective = page
        // 入口兜底：app.json 的 pages 与实际落盘路径不一致时（AI 把页面写歪了），
        // 结果是"树建起来了但一个内容节点都没有"——界面上一块纯白，毫无提示。
        // 这里退回包里第一个 .wxml，先让画面出来，同时在诊断里说明发生了回退。
        if (wxml.isEmpty()) {
            val alt = pkg?.filePaths()?.firstOrNull { it.endsWith(".wxml") }
            if (alt != null) {
                effective = alt.removeSuffix(".wxml")
                wxml = pkg?.pageWxml(effective) ?: ""
                if (wxml.isNotEmpty()) lastError = "入口 $page 无 wxml，已回退 $effective"
            }
        }
        val wxss = listOfNotNull(pkg?.appWxss, pkg?.pageWxss(effective)).joinToString("\n")
        // 解析失败不再让异常穿出去把宿主一起带走（create_miniapp 侧对 WXML 是软校验：
        // 误报不删包，可如果这里炸了，宿主 AndroidView.factory 会直接崩，
        // 用户看到的是"闪退"而不是"这页有问题"）。失败 → 空模板 + 一条可读错误。
        val tpl = try {
            WxmlParser().parse(wxml)
        } catch (e: Throwable) {
            lastError = "WXML 解析失败：${e.message?.take(140)}"
            com.yuanbao.miniapp.view.TemplateNode("#document")
        }
        val rules = WxssParser().parse(wxss)
        val entry = PageEntry(effective, params, tpl, rules)
        entry.wxmlChars = wxml.length
        entry.jsChars = (pkg?.pageJs(effective) ?: "").length
        pageStack.add(entry)
        applyWindowStyle(pkg?.pageJson(effective))
        // 先用磁盘上的 wxml/wxss 渲一版：**不把"能不能看见画面"绑死在 JS 引擎是否跑通上**。
        // 旧流程只在 onPageLoaded（由 JS 线程回调）里 relayout，JS 引擎一旦没跑起来
        // （native 库加载失败 / 脚本抛错），root 永远是 null，卡片就是纯白且毫无提示。
        // 这里先出静态骨架，随后 onDataChanged / onPageLoaded 会再补一版带数据的。
        relayout()
        logic.loadPage(effective, toJsonObject(params))
        logic.notifyShow()
    }

    private fun toJsonObject(params: Map<String, String>): String {
        val fields = LinkedHashMap<String, Json>()
        params.forEach { (k, v) -> fields[k] = Json.Str(v) }
        return com.yuanbao.miniapp.util.writeJson(Json.Obj(fields))
    }

    // ------------------------------------------------------------ input
    private var downX = 0f
    private var downY = 0f
    private var scrollNode: RenderNode? = null
    private var scrollStartY = 0f

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val entry = pageStack.lastOrNull() ?: return false
        val root = entry.root ?: return false
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                // 嵌在滚动容器（Compose LazyColumn / RecyclerView / ScrollView）里时，
                // 通知父级不要拦截触摸，保证小程序自己收得到完整的手势序列
                parent?.requestDisallowInterceptTouchEvent(true)
                downX = event.x
                downY = event.y
                scrollNode = findScrollable(root, event.x, event.y)
                scrollStartY = event.y
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val node = scrollNode
                if (node != null) {
                    node.scrollTop = (node.scrollTop - (event.y - scrollStartY))
                        .coerceIn(0f, maxOf(0f, node.contentHeight - node.height))
                    scrollStartY = event.y
                    markDirty()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val dx = event.x - downX
                val dy = event.y - downY
                scrollNode?.let { saveScroll(entry) }
                scrollNode = null
                if (Math.abs(dx) < 12 && Math.abs(dy) < 12) {
                    handleTap(root, event.x, event.y)
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun handleTap(root: RenderNode, x: Float, y: Float) {
        val target = hitTest(root, x, y) ?: run {
            hideInput()
            return
        }
        if (target.type == NodeType.INPUT) {
            showInput(target)
            return
        }
        // 微信标准事件冒泡：deepest 命中节点没有 tap 绑定时沿 parent 链上溯
        var bind: RenderNode? = target
        while (bind != null && bind.events["tap"] == null && bind.events["click"] == null) {
            bind = bind.parent
        }
        val bindNode = bind ?: return
        val handler = bindNode.events["tap"] ?: bindNode.events["click"] ?: return
        fun datasetOf(n: RenderNode): Json.Obj = Json.Obj(LinkedHashMap<String, Json>().apply {
            n.attributes.filterKeys { it.startsWith("data-") }.forEach { (k, v) ->
                put(k.removePrefix("data-"), Json.Str(v))
            }
        })
        val args = LinkedHashMap<String, Json>()
        args["type"] = Json.Str("tap")
        args["timeStamp"] = Json.Num(System.currentTimeMillis().toDouble())
        args["target"] = Json.obj(
            "id" to Json.Num(target.id.toDouble()),
            "dataset" to datasetOf(target))
        args["currentTarget"] = Json.obj(
            "id" to Json.Num(bindNode.id.toDouble()),
            "dataset" to datasetOf(bindNode))
        logic.dispatchEvent(handler, com.yuanbao.miniapp.util.writeJson(Json.Arr(mutableListOf<Json>(Json.Obj(args)))))
    }

    private fun hitTest(node: RenderNode, x: Float, y: Float): RenderNode? {
        // deepest match; absolutely positioned children win
        var found: RenderNode? = null
        for (child in node.children) {
            if (child.style.position == com.yuanbao.miniapp.render.PositionType.ABSOLUTE) {
                val hit = hitTest(child, x, y)
                if (hit != null) return hit
            }
        }
        for (child in node.children) {
            if (child.style.position == com.yuanbao.miniapp.render.PositionType.ABSOLUTE) continue
            val hit = hitTest(child, x, y)
            if (hit != null) found = hit
        }
        if (found == null && node.containsPoint(x, y) && node.hasEvents()) found = node
        return found
    }

    private fun findScrollable(node: RenderNode, x: Float, y: Float): RenderNode? {
        var found: RenderNode? = null
        for (child in node.children) {
            val hit = findScrollable(child, x, y)
            if (hit != null) found = hit
        }
        if (found == null && node.style.overflow == Overflow.SCROLL && node.containsPoint(x, y)) found = node
        return found
    }

    private fun showInput(node: RenderNode) {
        hideInput()
        val et = EditText(context).apply {
            setText(node.attributes["value"] ?: "")
            hint = node.attributes["placeholder"] ?: ""
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_DONE
            textSize = node.style.fontSize / resources.displayMetrics.density
            setPadding(24, 20, 24, 20)
        }
        // 输入框仍用独立窗口 Dialog 承载：小程序页面尺寸由引擎自己排版，
        // 在页面内插入真实 EditText 会与引擎的布局/滚动坐标系打架；
        // Dialog 走独立窗口，键盘弹出时不会被卡片裁剪，输入体验也稳定。
        val dialog = Dialog(context).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(et)
            window?.setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
            window?.setBackgroundDrawableResource(android.R.color.transparent)
        }
        et.setOnEditorActionListener { _, _, _ ->
            fireInput(node, et.text.toString())
            hideInput()
            true
        }
        dialog.setOnDismissListener {
            if (inputDialog === dialog) hideInput()
        }
        dialog.show()
        et.requestFocus()
        inputEditor = et
        activeInputNode = node
        inputDialog = dialog
    }

    private fun fireInput(node: RenderNode, value: String) {
        node.attributes["value"] = value
        val handler = node.events["input"] ?: node.events["blur"] ?: return
        val payload = Json.Arr(mutableListOf<Json>(Json.obj(
            "type" to Json.Str("input"),
            "detail" to Json.obj("value" to Json.Str(value))
        )))
        logic.dispatchEvent(handler, com.yuanbao.miniapp.util.writeJson(payload))
    }

    private fun fireBlur(node: RenderNode?, value: String) {
        node ?: return
        val handler = node.events["blur"] ?: return
        val payload = Json.Arr(mutableListOf<Json>(Json.obj(
            "type" to Json.Str("blur"),
            "detail" to Json.obj("value" to Json.Str(value))
        )))
        logic.dispatchEvent(handler, com.yuanbao.miniapp.util.writeJson(payload))
    }

    private fun hideInput() {
        val d = inputDialog
        inputDialog = null
        val node = activeInputNode
        val et = inputEditor
        inputEditor = null
        activeInputNode = null
        if (d != null) {
            d.setOnDismissListener(null)
            d.dismiss()
        }
        if (node != null && et != null) fireBlur(node, et.text.toString())
    }
}
