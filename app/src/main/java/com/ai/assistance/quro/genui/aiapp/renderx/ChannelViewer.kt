package com.ai.assistance.quro.genui.aiapp.renderx

import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import com.ai.assistance.quro.core.miniapp.MiniAppBridgeInterface
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * 往 html 通道页面注入 bridge.js 运行时（与「小程序」同源）。
 *
 * 注入后页面里就能用 `native.storage.getItem(...)`、`native.db.query(...)` 等原生能力，
 * 否则 html 通道产出的页面只是个没法持久化的沙盒。
 * 读不到 bridge.js 时原样返回，页面照常渲染，不会白屏。
 */
private fun injectBridgeRuntime(ctx: android.content.Context, html: String): String {
    val js = runCatching {
        ctx.assets.open("bridge/bridge.js").bufferedReader(java.nio.charset.StandardCharsets.UTF_8).use { it.readText() }
    }.getOrNull()
    if (js.isNullOrEmpty()) return html
    val script = "<script>\n$js\n</script>\n"
    val idx = html.indexOf("<head", ignoreCase = true)
    if (idx < 0) return script + html
    val end = html.indexOf(">", idx)
    if (end < 0) return html
    return html.substring(0, end + 1) + script + html.substring(end + 1)
}

/** 通道页面模型 */
sealed class ChannelPage {
    abstract val title: String

    data class MarkdownPage(override val title: String, val content: String) : ChannelPage()
    data class FlatPage(override val title: String, val doc: FlatDoc) : ChannelPage()
    data class HtmlPage(override val title: String, val html: String) : ChannelPage()
}

/**
 * 新架构通道查看器：Markdown（Markwon 原生渲染）/ 扁平邻接表 / 内联 HTML
 */
@Composable
fun ChannelViewer(
    page: ChannelPage,
    modifier: Modifier = Modifier,
    embedded: Boolean = false,
    onBack: () -> Unit = {},
    onAction: (String) -> Unit = {},
    /** html 通道「保存为小程序」回调：(标题, 完整HTML)。不传则在 html 通道隐藏该按钮。 */
    onSaveAsMiniApp: ((title: String, html: String) -> Unit)? = null,
) {
    if (!embedded) BackHandler { onBack() }
    Surface(
        modifier = modifier.fillMaxSize(),
        color = if (embedded) androidx.compose.ui.graphics.Color.Transparent else MaterialTheme.colorScheme.background
    ) {
        // 嵌入模式：顶部避让状态栏与悬浮按钮（返回胶囊/右上按钮悬浮其上）
        Column(
            Modifier.fillMaxSize().let {
                // 只避让通知栏（状态栏）；底部无遮挡，画布全屏
                if (embedded) it.statusBarsPadding() else it
            }
        ) {
            if (!embedded) {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("← 返回", color = MaterialTheme.colorScheme.primary, fontSize = 13.sp,
                    modifier = Modifier.clickable { onBack() }.padding(horizontal = 8.dp, vertical = 4.dp))
                Spacer(Modifier.weight(1f))
                Text(page.title.ifBlank { "内容" }, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Spacer(Modifier.width(64.dp))
            }
            }

            when (page) {
                is ChannelPage.MarkdownPage -> {
                    // Markwon：Markdown → 原生 Spannable → TextView（TextView 自滚，触摸直达，长文可看全）
                    val txtColor = MaterialTheme.colorScheme.onSurface
                    val linkColor = MaterialTheme.colorScheme.primary
                    AndroidView(
                        factory = { ctx ->
                            // 原生 ScrollView 包裹：触摸完全走原生 View 层（与 WebView 同级可靠性）
                            val scroll = android.widget.ScrollView(ctx)
                            scroll.isVerticalScrollBarEnabled = true
                            val tv = android.widget.TextView(ctx)
                            tv.setPadding(48, 32, 48, 160)
                            tv.textSize = 15f
                            tv.isVerticalScrollBarEnabled = true
                            tv.setTextColor(txtColor.toArgb())
                            tv.setLinkTextColor(linkColor.toArgb())
                            val markwon = io.noties.markwon.Markwon.builder(ctx)
                                .usePlugin(io.noties.markwon.core.CorePlugin.create())
                                .usePlugin(io.noties.markwon.ext.tables.TablePlugin.create(ctx))
                                .usePlugin(io.noties.markwon.ext.strikethrough.StrikethroughPlugin.create())
                                .build()
                            markwon.setMarkdown(tv, page.content)
                            scroll.addView(tv)
                            scroll
                        },
                        update = { sv ->
                            val tv = sv.getChildAt(0) as? android.widget.TextView ?: return@AndroidView
                            tv.setTextColor(txtColor.toArgb())
                            if (tv.text.isNullOrBlank()) {
                                val markwon = io.noties.markwon.Markwon.builder(tv.context)
                                    .usePlugin(io.noties.markwon.core.CorePlugin.create())
                                    .usePlugin(io.noties.markwon.ext.tables.TablePlugin.create(tv.context))
                                    .usePlugin(io.noties.markwon.ext.strikethrough.StrikethroughPlugin.create())
                                    .build()
                                markwon.setMarkdown(tv, page.content)
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
                is ChannelPage.FlatPage -> {
                    // A2UI → GenUI 对接：优先转 UISpec 用 GenUI SDK 渲染（获得 300+ 组件与效果引擎）
                    val converted = remember(page.doc) {
                        runCatching {
                            com.ai.assistance.quro.genui.sdk.interop.FlatDocToGenUI.convert(
                                root = page.doc.root,
                                nodes = page.doc.nodes.mapValues { (_, n) ->
                                    com.ai.assistance.quro.genui.sdk.interop.FlatDocToGenUI.Node(n.id, n.type, n.text, n.kids, n.props)
                                }
                            )
                        }.getOrNull()
                    }
                    if (converted != null) {
                        val host = remember {
                            object : com.ai.assistance.quro.genui.sdk.interaction.DefaultActionHost() {
                                override fun handleCustom(handlerId: String, payload: kotlinx.serialization.json.JsonElement): Any? {
                                    onAction(handlerId)
                                    return null
                                }
                            }
                        }
                        com.ai.assistance.quro.genui.sdk.GenUI.Screen(
                            spec = converted,
                            host = host,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        // 转换失败回退原生 FlatDocRenderer（根是 scroll → 内层自滚，防双重滚动）
                        val rootType = page.doc.nodes[page.doc.root]?.type
                        if (rootType == "scroll") {
                            FlatDocRenderer(page.doc, onAction = onAction, modifier = Modifier.fillMaxSize())
                        } else {
                            FlatDocRenderer(page.doc, onAction = onAction, modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()))
                        }
                    }
                }
                is ChannelPage.HtmlPage -> {
                    Column(Modifier.fillMaxSize()) {
                        // 操作条：把这一屏 HTML 固化成「小程序」工程（与工具中心「小程序」同源，
                        // 存完就能在工具中心里打开、也能被 AI 用 miniapp 工具继续改）
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                page.title.ifBlank { "HTML 应用" },
                                Modifier.weight(1f).padding(start = 4.dp),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            TextButton(onClick = { onSaveAsMiniApp?.invoke(page.title, page.html) }) {
                                Text("保存为小程序", style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    AndroidView(
                        modifier = Modifier.fillMaxSize().weight(1f),
                        factory = { ctx ->
                            WebView(ctx).apply {
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.mediaPlaybackRequiresUserGesture = false
                                settings.allowContentAccess = true
                                // 与「小程序」同源的 native.* 原生桥：
                                // AI 在 html 通道里也能用 storage/device/network/db/location/crypto 等能力
                                setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
                                addJavascriptInterface(MiniAppBridgeInterface(ctx, this), "native")
                                settings.builtInZoomControls = false
                                settings.loadWithOverviewMode = true
                                settings.useWideViewPort = true
                                webViewClient = object : android.webkit.WebViewClient() {
                                    override fun onPageFinished(view: android.webkit.WebView, url: String?) {
                                        // 探测页面实际底色（body 或首个带背景的子元素）→ 铺满 WebView 视口
                                        view.evaluateJavascript(
                                            "(function(){var c=getComputedStyle(document.body).backgroundColor;" +
                                            "if(c==='rgba(0, 0, 0, 0)'||c==='transparent'){var e=document.body.firstElementChild;" +
                                            "while(e){var s=getComputedStyle(e).backgroundColor;" +
                                            "if(s&&s!=='rgba(0, 0, 0, 0)'&&s!=='transparent'){c=s;break;}e=e.nextElementSibling;}}" +
                                            "return c;})()"
                                        ) { result ->
                                            val m = Regex("rgba?\\((\\d+),\\s*(\\d+),\\s*(\\d+)").find(result)
                                            if (m != null) {
                                                val (r, g, b) = m.destructured
                                                view.setBackgroundColor(android.graphics.Color.rgb(r.toInt(), g.toInt(), b.toInt()))
                                            }
                                        }
                                    }
                                }
                                webChromeClient = android.webkit.WebChromeClient()
                                // 混合内容与缓存
                                settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                                settings.cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
                                settings.databaseEnabled = true
                                settings.allowFileAccess = false      // 安全：禁文件访问
                                settings.allowContentAccess = false  // 安全：禁内容访问
                                // 兜底：body 至少撑满视口（模型没写 min-height 时深色页不再半截）
                                val css = "<style>html,body{min-height:100vh;margin:0;box-sizing:border-box;padding-bottom:130px}</style>"
                                val patched0 = if (page.html.contains("min-height", ignoreCase = true)) page.html
                                    else if (page.html.contains("</head>", ignoreCase = true))
                                        page.html.replace("</head>", css + "</head>", ignoreCase = true)
                                    else css + page.html
                                // 注入 bridge.js 运行时，让页面能用 native.* 调原生能力
                                val patched = injectBridgeRuntime(ctx, patched0)
                                // 外部依赖基址：https 域，CDN 相对/绝对引用均可解析
                                loadDataWithBaseURL("https://localhost/", patched, "text/html", "utf-8", null)
                            }
                        }
                    )
                    }
                }
            }
        }
    }
}
