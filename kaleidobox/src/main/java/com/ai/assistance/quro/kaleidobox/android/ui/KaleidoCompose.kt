package com.ai.assistance.quro.kaleidobox.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Arrangement as ComposeArrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as ComposeSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path as ComposePath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import android.app.DownloadManager
import android.net.Uri
import android.os.Environment
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import com.ai.assistance.quro.kaleidobox.core.ui.*

/** 原生 webview 组件的指令去重缓存（放在重组之外，避免写 state 引发额外重组）。 */
private class WvMemo {
    var nav: String? = null
    var find: String? = null
    var clear: String? = null

    /**
     * 上一次"已请求加载"的 URL。
     *
     * 存在的意义（曾是一个真 bug）：不能用 `props.url != webView.url` 来决定是否加载 ——
     * 服务端 301 到带斜杠的地址后，`webView.url` 永远不等于 props.url，
     * 于是每次重组都再 loadUrl 一次 → 无限重载。
     * 正确语义：**只有请求的 URL 发生变化时才加载**；"想重新加载同一个地址"由 nav=load 显式表达。
     */
    var loadedUrl: String? = null
}

/** 桌面版 UA（WebUI 的"桌面版网站"开关使用）。 */
private const val DESKTOP_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

/**
 * UiNode 树 → Jetpack Compose 渲染器。
 *
 * 这是"UI 与宿主深度融合"的落点，也是与 ToolPkg 的 Compose DSL 方案的本质差异：
 *
 *   ToolPkg：插件用 JS 写 Compose DSL，宿主解释执行 → 只能用 JS 写，且绑死宿主的 DSL 版本。
 *   Kaleido：插件（任何语言）返回**纯数据树**，宿主用原生 Compose 渲染 →
 *            语言无关、可静态校验、可 diff、可热重载，宿主换渲染器插件零改动。
 *
 * 渲染器只认识 tag 字符串，遇到不认识的 tag 降级成占位块 ——
 * 所以宿主升级新增组件后，老插件不会崩。
 */
object KaleidoCompose {

    /** 渲染一棵 UiNode 树。state 是宿主持有的可变状态，供 Bound.Ref 读取。 */
    @Composable
    fun Render(
        node: UiNode?,
        state: Map<String, Any?> = emptyMap(),
        onAction: (UiAction) -> Unit = {},
        surfaceId: String = "",
    ) {
        if (node == null) {
            Text("（空界面）", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            return
        }
        RenderNode(node, state, onAction, surfaceId)
    }

    init {
        // 原生 WebView 组件：WebUI 浏览器插件的引擎。
        //
        // 为什么不是"插件自己 new 一个 WebView"：插件是纯数据树，拿不到 View 引用。
        // 所以这里的契约是「双向」的 —— 插件下发 props，组件通过 onAction 回传页面状态。
        //
        // props:
        //   url(String)           要加载的地址（变化即导航）
        //   nav(String)           导航指令，形如 "back@12"（nonce 去重，变化即执行一次）：
        //                         back / forward / reload / stop / zoomIn / zoomOut / top / bottom
        //   desktop(Boolean)      桌面版 UA
        //   find(String)          页内查找关键字（变化即查找并高亮）
        //   clearData(String)     隐私清理 nonce（清历史/缓存/表单/Cookie）
        //   showToolbar(Boolean)  是否渲染组件内置工具条（默认 false，由插件自绘）
        //
        // 事件 onAction：
        //   wvPage     {url,title,canBack,canFwd,loading,progress}
        //   wvError    {code,desc,url}
        //   wvDownload {url,mime,ok}
        //   wvFind     {matches}
        //   wvCleared  {ok}
        NativeComponentRegistry.register("webview") { props, _, onAction ->
            val ctx = LocalContext.current
            val url = (props["url"] as? String)?.takeIf { it.isNotEmpty() } ?: "about:blank"
            val showToolbar = props["showToolbar"] as? Boolean ?: false

            var webView by remember { mutableStateOf<WebView?>(null) }
            var progress by remember { mutableStateOf(0) }
            val seen = remember { WvMemo() }

            // 页面状态回传：把 WebView 内部状态"翻译"成插件能读的数据。
            fun report(wv: WebView, loading: Boolean) {
                onAction(
                    "wvPage",
                    mapOf(
                        "url" to (wv.url ?: ""),
                        "title" to (wv.title ?: ""),
                        "canBack" to wv.canGoBack(),
                        "canFwd" to wv.canGoForward(),
                        "loading" to loading,
                        "progress" to progress,
                    ),
                )
            }

            Column(Modifier.fillMaxSize()) {
                AndroidView(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    factory = { c ->
                        WebView(c).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.loadWithOverviewMode = true
                            settings.useWideViewPort = true
                            settings.setSupportZoom(true)
                            settings.builtInZoomControls = true
                            settings.displayZoomControls = false
                            webViewClient = object : WebViewClient() {
                                override fun onPageStarted(
                                    view: WebView?, u: String?, favicon: android.graphics.Bitmap?,
                                ) {
                                    super.onPageStarted(view, u, favicon)
                                    view?.let { report(it, loading = true) }
                                }

                                override fun onPageFinished(view: WebView?, u: String?) {
                                    super.onPageFinished(view, u)
                                    view?.let { report(it, loading = false) }
                                }

                                override fun onReceivedError(
                                    view: WebView?, request: android.webkit.WebResourceRequest?,
                                    error: android.webkit.WebResourceError?,
                                ) {
                                    super.onReceivedError(view, request, error)
                                    if (request?.isForMainFrame == true) {
                                        onAction(
                                            "wvError",
                                            mapOf(
                                                "code" to (error?.errorCode ?: -1),
                                                "desc" to (error?.description?.toString() ?: ""),
                                                "url" to (request.url?.toString() ?: ""),
                                            ),
                                        )
                                    }
                                }
                            }
                            webChromeClient = object : WebChromeClient() {
                                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                    super.onProgressChanged(view, newProgress)
                                    progress = newProgress
                                }

                                override fun onReceivedTitle(view: WebView?, title: String?) {
                                    super.onReceivedTitle(view, title)
                                    view?.let { report(it, loading = progress in 1..99) }
                                }
                            }
                            @Suppress("DEPRECATION")
                            setFindListener { _, numberOfMatches, _ ->
                                onAction("wvFind", mapOf("matches" to numberOfMatches))
                            }
                            setDownloadListener { dlUrl, _, _, mime, _ ->
                                // 浏览器该有的下载：交给系统下载器，落到「下载」目录并出通知。
                                val ok = runCatching {
                                    val req = DownloadManager.Request(Uri.parse(dlUrl))
                                    req.setNotificationVisibility(
                                        DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                                    )
                                    req.setDestinationInExternalPublicDir(
                                        Environment.DIRECTORY_DOWNLOADS,
                                        Uri.parse(dlUrl).lastPathSegment ?: "download",
                                    )
                                    val dm = c.getSystemService(android.content.Context.DOWNLOAD_SERVICE) as DownloadManager
                                    dm.enqueue(req)
                                    true
                                }.getOrDefault(false)
                                onAction("wvDownload", mapOf("url" to dlUrl, "mime" to (mime ?: ""), "ok" to ok))
                            }
                            loadUrl(url)
                        }.also { webView = it }
                    },
                    update = { wv ->
                        // 1) 目标地址变化 → 导航（只在 props.url 变化时加载，见 WvMemo.loadedUrl 的说明）
                        val want = (props["url"] as? String)?.takeIf { it.isNotEmpty() }
                        if (want != null && want != seen.loadedUrl) {
                            seen.loadedUrl = want
                            wv.loadUrl(want)
                        }

                        // 2) 桌面版 UA 开关
                        val wantDesktop = props["desktop"] as? Boolean ?: false
                        val isDesktop = wv.settings.userAgentString?.contains("X11") == true
                        if (wantDesktop != isDesktop) {
                            wv.settings.userAgentString = if (wantDesktop) DESKTOP_UA else null
                            wv.reload()
                        }

                        // 3) 导航指令（nonce 去重，保证"按一次走一次"）
                        val nv = props["nav"] as? String
                        if (!nv.isNullOrEmpty() && nv != seen.nav) {
                            seen.nav = nv
                            when (nv.substringBefore('@')) {
                                // load：把 props.url 作为"显式导航目标"强制加载一次。
                                // 必要性：同 URL 时上面那条"仅变化才加载"的规则判不出
                                // "用户就是想重新进这个页"（例如地址栏又输了一遍当前域名）。
                                "load" -> want?.let {
                                    seen.loadedUrl = it
                                    wv.loadUrl(it)
                                }
                                "back" -> if (wv.canGoBack()) wv.goBack()
                                "forward" -> if (wv.canGoForward()) wv.goForward()
                                "reload" -> wv.reload()
                                "stop" -> wv.stopLoading()
                                "zoomIn" -> wv.zoomIn()
                                "zoomOut" -> wv.zoomOut()
                                // 用 JS 滚动，避免 WebView.scale（已在 API 中废弃）
                                "top" -> wv.evaluateJavascript("window.scrollTo(0,0)", null)
                                "bottom" -> wv.evaluateJavascript(
                                    "window.scrollTo(0, document.body ? document.body.scrollHeight : 0)", null
                                )
                            }
                        }

                        // 4) 页内查找
                        val fd = props["find"] as? String
                        if (!fd.isNullOrEmpty() && fd != seen.find) {
                            seen.find = fd
                            wv.findAllAsync(fd)
                        }

                        // 5) 隐私清理
                        val cd = props["clearData"] as? String
                        if (!cd.isNullOrEmpty() && cd != seen.clear) {
                            seen.clear = cd
                            val ok = runCatching {
                                wv.clearHistory()
                                wv.clearCache(true)
                                wv.clearFormData()
                                CookieManager.getInstance().removeAllCookies(null)
                                CookieManager.getInstance().flush()
                                true
                            }.getOrDefault(false)
                            onAction("wvCleared", mapOf("ok" to ok))
                        }
                    },
                )
                if (showToolbar) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Button(onClick = { webView?.goBack() }) { Text("←") }
                        Button(onClick = { webView?.goForward() }) { Text("→") }
                        Button(onClick = { webView?.reload() }) { Text("⟳") }
                        Text(
                            (props["url"] as? String) ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f).padding(start = 8.dp),
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun RenderNode(
        node: UiNode,
        state: Map<String, Any?>,
        onAction: (UiAction) -> Unit,
        surfaceId: String,
    ) {
        when (node) {
            is UiNode.Column -> Column(modifier = node.modifier.toModifier(state) { act ->
                onAction(act.toUiAction(surfaceId, node.id, state))
            }, verticalArrangement = node.arrangement.toVertical()) {
                node.children.forEach { child ->
                    val w = child.modifier.weight
                    if (w != null) Box(Modifier.weight(w)) { RenderNode(child, state, onAction, surfaceId) }
                    else RenderNode(child, state, onAction, surfaceId)
                }
            }

            is UiNode.Row -> Row(modifier = node.modifier.toModifier(state) { act ->
                onAction(act.toUiAction(surfaceId, node.id, state))
            }, horizontalArrangement = node.arrangement.toHorizontal()) {
                node.children.forEach { child ->
                    val w = child.modifier.weight
                    if (w != null) Box(Modifier.weight(w)) { RenderNode(child, state, onAction, surfaceId) }
                    else RenderNode(child, state, onAction, surfaceId)
                }
            }

            is UiNode.Box -> Box(modifier = node.modifier.toModifier(state) { act ->
                onAction(act.toUiAction(surfaceId, node.id, state))
            }) {
                node.children.forEach { RenderNode(it, state, onAction, surfaceId) }
            }

            is UiNode.Card -> Card(
                modifier = node.modifier.toModifier(state) { act ->
                    onAction(act.toUiAction(surfaceId, node.id, state))
                }.padding(4.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = node.elevation.dp)
            ) {
                Column(Modifier.padding(4.dp)) {
                    node.title?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
                    node.child?.let { RenderNode(it, state, onAction, surfaceId) }
                }
            }

            is UiNode.Scroll -> {
                if (node.vertical) {
                    val s = rememberScrollState()
                    Column(
                        modifier = node.modifier.toModifier(state) { }.verticalScroll(s)
                    ) { node.child?.let { RenderNode(it, state, onAction, surfaceId) } }
                } else {
                    val s = rememberScrollState()
                    Row(
                        modifier = node.modifier.toModifier(state) { }.horizontalScroll(s),
                        horizontalArrangement = ComposeArrangement.Start
                    ) { node.child?.let { RenderNode(it, state, onAction, surfaceId) } }
                }
            }

            is UiNode.Text -> {
                val text = node.text.resolve(state)
                val style = when (node.style) {
                    TypeStyle.DISPLAY -> MaterialTheme.typography.displaySmall
                    TypeStyle.HEADLINE -> MaterialTheme.typography.headlineSmall
                    TypeStyle.TITLE -> MaterialTheme.typography.titleMedium
                    TypeStyle.LABEL -> MaterialTheme.typography.labelLarge
                    TypeStyle.CAPTION -> MaterialTheme.typography.bodySmall
                    TypeStyle.MONO -> MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
                    TypeStyle.BODY -> MaterialTheme.typography.bodyMedium
                }
                Text(
                    text = text,
                    style = style,
                    maxLines = node.maxLines ?: Int.MAX_VALUE,
                    overflow = TextOverflow.Ellipsis,
                    color = node.color?.parseColor() ?: Color.Unspecified,
                    modifier = node.modifier.toModifier(state) { act ->
                        onAction(act.toUiAction(surfaceId, node.id, state))
                    }
                )
            }

            is UiNode.Button -> {
                val enabled = node.enabled.resolve(state)
                when (node.variant) {
                    UiNode.Button.Variant.FILLED -> Button(
                        onClick = { onAction(node.action.toUiAction(surfaceId, node.id, state)) },
                        enabled = enabled,
                        modifier = node.modifier.toModifier(state) {}
                    ) { Text(node.label.resolve(state)) }
                    UiNode.Button.Variant.OUTLINED -> OutlinedButton(
                        onClick = { onAction(node.action.toUiAction(surfaceId, node.id, state)) },
                        enabled = enabled,
                        modifier = node.modifier.toModifier(state) {}
                    ) { Text(node.label.resolve(state)) }
                    UiNode.Button.Variant.TEXT -> TextButton(
                        onClick = { onAction(node.action.toUiAction(surfaceId, node.id, state)) },
                        enabled = enabled,
                        modifier = node.modifier.toModifier(state) {}
                    ) { Text(node.label.resolve(state)) }
                    UiNode.Button.Variant.TONAL -> FilledTonalButton(
                        onClick = { onAction(node.action.toUiAction(surfaceId, node.id, state)) },
                        enabled = enabled,
                        modifier = node.modifier.toModifier(state) {}
                    ) { Text(node.label.resolve(state)) }
                }
            }

            is UiNode.Icon -> {
                // 图标名映射到宿主图标注册表由宿主侧负责；渲染器无图标资源时降级为文字占位，避免编译期依赖全套图标。
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(4.dp),
                    modifier = node.modifier.toModifier(state) {}
                ) {
                    Box(Modifier.padding(6.dp), contentAlignment = Alignment.Center) {
                        Text(
                            node.name, style = MaterialTheme.typography.labelSmall,
                            color = node.tint?.parseColor() ?: MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                }
            }

            is UiNode.Image -> AsyncImageSafe(
                url = node.src.resolve(state),
                modifier = node.modifier.toModifier(state) {},
                blurhash = node.blurhash
            )

            is UiNode.TextField -> {
                val bound = node.value
                // 输入框值不能为 null，且必须随 state 单一来源回灌。
                // Bound.Ref 解析缺失 state 键时返回 null → 用 ?: "" 兜底。
                var value by remember(node.id) { mutableStateOf(bound.resolve(state) ?: "") }
                val external = bound.resolve(state) ?: ""
                LaunchedEffect(external) { value = external }
                OutlinedTextField(
                    value = value,
                    onValueChange = {
                        value = it
                        onAction(node.onValueChange.toUiAction(surfaceId, node.id, state, mapOf("value" to it)))
                    },
                    label = node.label?.let { { Text(it) } },
                    singleLine = node.singleLine,
                    modifier = node.modifier.toModifier(state) {}
                )
            }

            is UiNode.Switch -> {
                var checked by remember(node.id) { mutableStateOf(node.checked.resolve(state)) }
                Row(verticalAlignment = Alignment.CenterVertically,
                    modifier = node.modifier.toModifier(state) {}) {
                    Switch(checked = checked, onCheckedChange = {
                        checked = it
                        onAction(node.onToggle.toUiAction(surfaceId, node.id, state, mapOf("value" to it)))
                    })
                    node.label?.let { Spacer(Modifier.width(8.dp)); Text(it) }
                }
            }

            is UiNode.Slider -> {
                var v by remember(node.id) { mutableStateOf(node.value.resolve(state).toFloat()) }
                Slider(
                    value = v,
                    onValueChange = {
                        v = it
                        onAction(node.onValueChange.toUiAction(surfaceId, node.id, state, mapOf("value" to it)))
                    },
                    valueRange = node.range.start.toFloat()..node.range.endInclusive.toFloat(),
                    modifier = node.modifier.toModifier(state) {}
                )
            }

            is UiNode.Progress -> {
                val v = node.value?.resolve(state)
                if (v == null) CircularProgressIndicator(modifier = node.modifier.toModifier(state) {})
                else LinearProgressIndicator(progress = { v.toFloat().coerceIn(0f, 1f) },
                    modifier = node.modifier.toModifier(state) {})
            }

            is UiNode.Divider -> HorizontalDivider(modifier = node.modifier.toModifier(state) {})
            is UiNode.Spacer -> Spacer(Modifier.size(node.size.dp))

            is UiNode.Lazy -> {
                // 列表：items 是数据，itemTemplate 是每项的模板
                Column(modifier = node.modifier.toModifier(state) {}) {
                    node.items.forEach { row ->
                        val merged = state + row
                        node.itemTemplate?.let { RenderNode(it, merged, onAction, surfaceId) }
                    }
                }
            }

            is UiNode.RichBlock -> RichBlockView(node, state, surfaceId, onAction)

            is UiNode.Canvas -> CanvasView(node, state)

            is UiNode.Native -> {
                // 逃生舱：宿主注册的原生组件
                NativeComponentSlot(node, state, surfaceId, onAction)
            }
        }
    }

    // ---------------------------------------------------------------- 子组件

    @Composable
    private fun RichBlockView(
        node: UiNode.RichBlock,
        state: Map<String, Any?>,
        surfaceId: String,
        onAction: (UiAction) -> Unit,
    ) {
        when (node.kind) {
            UiNode.RichBlock.RichKind.MARKDOWN ->
                // 宿主应接入自己的 Markdown 渲染器；这里用等宽文本保证可用性
                Text(node.content, style = MaterialTheme.typography.bodyMedium,
                    modifier = node.modifier.toModifier(state) {})
            UiNode.RichBlock.RichKind.CODE -> Surface(
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = node.modifier.toModifier(state) {}
            ) {
                Text(node.content, fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(8.dp))
            }
            else -> Text(node.content, modifier = node.modifier.toModifier(state) {})
        }
    }

    @Composable
    private fun CanvasView(node: UiNode.Canvas, state: Map<String, Any?>) {
        Canvas(
            modifier = node.modifier.toModifier(state) {}.height(120.dp).fillMaxWidth()
        ) {
            node.ops.forEach { op ->
                val c = when (op) {
                    is DrawOp.Line -> op.color
                    is DrawOp.Rect -> op.color
                    is DrawOp.Circle -> op.color
                    is DrawOp.Path -> op.color
                    is DrawOp.TextAt -> op.color
                }.parseColor()
                when (op) {
                    is DrawOp.Line -> drawLine(c, Offset(op.x1, op.y1), Offset(op.x2, op.y2), op.width)
                    is DrawOp.Rect -> if (op.filled)
                        drawRect(c, Offset(op.x, op.y), ComposeSize(op.w, op.h))
                    else
                        drawRect(c, Offset(op.x, op.y), ComposeSize(op.w, op.h), style = androidx.compose.ui.graphics.drawscope.Stroke(op.width))
                    is DrawOp.Circle -> if (op.filled)
                        drawCircle(c, op.r, Offset(op.cx, op.cy))
                    else
                        drawCircle(c, op.r, Offset(op.cx, op.cy), style = androidx.compose.ui.graphics.drawscope.Stroke(op.width))
                    is DrawOp.Path -> if (op.points.size >= 2) {
                        val p = ComposePath().apply {
                            moveTo(op.points.first().first, op.points.first().second)
                            op.points.drop(1).forEach { lineTo(it.first, it.second) }
                            if (op.close) close()
                        }
                        drawPath(p, c, style = androidx.compose.ui.graphics.drawscope.Stroke(op.width))
                    }
                    is DrawOp.TextAt -> drawContext.canvas.nativeCanvas.drawText(
                        op.text, op.x, op.y,
                        android.graphics.Paint().apply {
                            color = c.toArgbCompat()
                            textSize = op.size
                        }
                    )
                }
            }
        }
    }

    @Composable
    private fun NativeComponentSlot(
        node: UiNode.Native,
        state: Map<String, Any?>,
        surfaceId: String,
        onAction: (UiAction) -> Unit,
    ) {
        val renderer = NativeComponentRegistry.rendererFor(node.component)
        if (renderer != null) {
            renderer(node.props, state) { actionId, payload ->
                onAction(UiAction(surfaceId, node.id, actionId, payload, state))
            }
        } else {
            // 未知组件 → 可见的占位，而不是静默空白（便于插件作者排查）
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(4.dp),
                modifier = node.modifier.toModifier(state) {}
            ) {
                Text("未注册的原生组件: ${node.component}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(8.dp))
            }
        }
    }

    @Composable
    private fun AsyncImageSafe(url: String, modifier: Modifier, blurhash: String?) {
        // 生产实现建议接 coil / glide；此处给出无依赖的安全占位实现
        Surface(
            modifier = modifier.height(120.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(6.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    blurhash?.let { "🖼 $url" } ?: url,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1
                )
            }
        }
    }

    // ---------------------------------------------------------------- 转换

    private fun Mod.toModifier(state: Map<String, Any?>, onClick: (Action) -> Unit): Modifier {
        var m: Modifier = when (val w = width) {
            Size.Fill -> Modifier.fillMaxWidth()
            is Size.Dp -> Modifier.width(w.v.dp)
            is Size.Fraction -> Modifier.fillMaxWidth(w.v)
            Size.Wrap -> Modifier
        }
        m = when (val h = height) {
            Size.Fill -> m.fillMaxHeight()
            is Size.Dp -> m.height(h.v.dp)
            is Size.Fraction -> m.fillMaxHeight(h.v)
            Size.Wrap -> m
        }
        if (padding != Edges()) m = m.padding(
            start = padding.start.dp, top = padding.top.dp,
            end = padding.end.dp, bottom = padding.bottom.dp
        )
        if (margin != Edges()) m = m.padding(
            start = margin.start.dp, top = margin.top.dp,
            end = margin.end.dp, bottom = margin.bottom.dp
        )
        background?.let { m = m.background(it.parseColor(), RoundedCornerShape(cornerRadius.dp)) }
        border?.let { m = m.border(it.width.dp, it.color.parseColor(), RoundedCornerShape(cornerRadius.dp)) }
        if (alpha < 1f) m = m.alpha(alpha)
        minWidth?.let { m = m.defaultMinSize(minWidth = it.dp) }
        minHeight?.let { m = m.defaultMinSize(minHeight = it.dp) }
        clickable?.let { m = m.clickable { onClick(it) } }
        return m
    }

    private fun Action.toUiAction(
        surfaceId: String, nodeId: String, state: Map<String, Any?>,
        extra: Map<String, Any?> = emptyMap()
    ) = UiAction(surfaceId, nodeId, id, payload + extra, state)

    private fun Arrangement.toVertical(): ComposeArrangement.Vertical = when (this) {
        Arrangement.TOP -> ComposeArrangement.Top
        Arrangement.BOTTOM -> ComposeArrangement.Bottom
        Arrangement.CENTER -> ComposeArrangement.Center
        Arrangement.SPACE_BETWEEN -> ComposeArrangement.SpaceBetween
        Arrangement.SPACE_AROUND -> ComposeArrangement.SpaceAround
        Arrangement.SPACE_EVENLY -> ComposeArrangement.SpaceEvenly
        else -> ComposeArrangement.Top
    }

    private fun Arrangement.toHorizontal(): ComposeArrangement.Horizontal = when (this) {
        Arrangement.START -> ComposeArrangement.Start
        Arrangement.END -> ComposeArrangement.End
        Arrangement.CENTER -> ComposeArrangement.Center
        Arrangement.SPACE_BETWEEN -> ComposeArrangement.SpaceBetween
        Arrangement.SPACE_AROUND -> ComposeArrangement.SpaceAround
        Arrangement.SPACE_EVENLY -> ComposeArrangement.SpaceEvenly
        else -> ComposeArrangement.Start
    }

    private fun <T> Bound<T>.resolve(state: Map<String, Any?>): T = when (this) {
        is Bound.Lit -> value
        is Bound.Ref -> {
            @Suppress("UNCHECKED_CAST")
            (state[path] as? T) ?: (fallback as T)
        }
        is Bound.Computed -> fallback as T  // computed 需宿主在渲染前回调，此处用 fallback
    }

    /** 支持 #RRGGBB / #AARRGGBB / 常用颜色名 */
    internal fun String.parseColor(): Color = try {
        when {
            startsWith("#") && length == 7 -> Color(android.graphics.Color.parseColor(this))
            startsWith("#") && length == 9 -> Color(android.graphics.Color.parseColor(this))
            else -> when (lowercase()) {
                "red" -> Color.Red; "green" -> Color.Green; "blue" -> Color.Blue
                "black" -> Color.Black; "white" -> Color.White
                "gray", "grey" -> Color.Gray; "transparent" -> Color.Transparent
                else -> Color.Unspecified
            }
        }
    } catch (_: Throwable) {
        Color.Unspecified
    }

    private fun Color.toArgbCompat(): Int =
        android.graphics.Color.argb(
            (alpha * 255).toInt(), (red * 255).toInt(),
            (green * 255).toInt(), (blue * 255).toInt()
        )
}

/**
 * 宿主原生组件注册表（UiNode.Native 的逃生舱）。
 * 宿主把自有组件注册进来，插件就能在 UI 树里直接引用，而不必等 Kaleido 增加新节点类型。
 */
object NativeComponentRegistry {

    typealias Renderer = @Composable (props: Map<String, Any?>, state: Map<String, Any?>,
                                      onAction: (String, Map<String, Any?>) -> Unit) -> Unit

    private val registry = mutableMapOf<String, Renderer>()

    fun register(name: String, renderer: Renderer) {
        registry[name] = renderer
    }

    fun rendererFor(name: String): Renderer? = registry[name]
    fun names(): Set<String> = registry.keys.toSet()
}
