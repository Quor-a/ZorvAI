package com.ai.assistance.quro.ui.chat

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.ai.assistance.quro.ui.data.ZxInlineSpec
import com.ai.assistance.quro.util.qstr
import com.ai.assistance.quro.R
import org.json.JSONObject

/**
 * zxwidget 动态交互界面 —— **内联**在对话框消息流里的那一块。
 *
 * 🔴 用户 2026-10-13 拍板：zxwidget「不是卡片方式，是直接渲染在对话框的」。
 *   所以它既不是整框弹窗，也不进底部卡片栏、不进气泡内小卡片槽位，
 *   而是像 AIP 画布那样撑满宽度、长在消息流里。
 *
 * 它和 Web UI 对话框共用同一份运行时（`assets/zx/zxwidget.js`）；
 * 区别只是谁把它画出来：这里用宿主内嵌 WebView，Web UI 那边用网页自己的 DOM。
 * 交互回路也一致：用户在界面上操作 → 结构化描述 → 作为**工具结果**回传模型。
 *
 * 高度：内联块在 LazyColumn 里没有固定高度，靠页面回报的真实内容高度撑开
 * （见 [ZxWebCard.HEIGHT_REPORTER]），并夹在 [MIN_H]…[MAX_H] 之间。
 * 超过上限就在块内滚动——**不裁掉内容**。
 *
 * @param component  zx 组件名（zx_compare / zx_table / …）
 * @param dsl        组件数据 JSON 原文
 * @param dark       深色主题
 * @param onInteract 用户在界面上操作，宿主拿结构化 JSON 自行决定后续
 */
@Composable
fun ZxWidgetInline(
    component: String,
    dsl: String,
    dark: Boolean,
    modifier: Modifier = Modifier,
    onInteract: (JSONObject) -> Unit,
) {
    val context = LocalContext.current

    // 运行时资源进程级缓存：一次会话里可能有多块 zxwidget，契约 195 KB，别每块都读一遍
    val (assetHtml, contractsJson) = remember(context) { ZxWebCard.assets(context) }

    val html = remember(assetHtml, contractsJson, component, dsl, dark) {
        if (assetHtml.isBlank()) ""
        else ZxWebCard.buildHtml(
            assetHtml = assetHtml,
            contractsJson = contractsJson,
            component = component,
            title = "",
            dsl = dsl,
            dark = dark,
            inline = true,
        )
    }

    if (html.isBlank()) {
        Box(modifier.fillMaxWidth().heightIn(min = MIN_H)) {
            androidx.compose.material3.Text(
                qstr(R.string.qk_04068, ZxWebCard.ASSET_NAME),
                color = androidx.compose.material3.MaterialTheme.colorScheme.error,
                modifier = Modifier.align(androidx.compose.ui.Alignment.Center),
            )
        }
        return
    }

    var height by remember(component, dsl) { mutableIntStateOf(MIN_H_DP) }

    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (dark) Color(0xFF101216) else Color(0xFFF6F7F9)),
    ) {
        InlineZxWebView(
            html = html,
            dark = dark,
            heightDp = height.coerceIn(MIN_H_DP, MAX_H_DP),
            onHeight = { h -> if (h != height) height = h },
            onInteract = onInteract,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 内联块高度上下限（dp）。上限之内块内滚动，不裁内容。 */
private const val MIN_H_DP = 140
private const val MAX_H_DP = 620

private val MIN_H get() = MIN_H_DP.dp

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun InlineZxWebView(
    html: String,
    dark: Boolean,
    heightDp: Int,
    onHeight: (Int) -> Unit,
    onInteract: (JSONObject) -> Unit,
    modifier: Modifier = Modifier,
) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    var wvReady by remember { mutableStateOf(false) }

    // 主题切换不重建 WebView，只切根节点 class —— 省一次整页重载，也不丢界面上的输入状态
    LaunchedEffect(dark, wvReady) {
        if (!wvReady) return@LaunchedEffect
        webView?.evaluateJavascript(
            "if(window.ZorvAI&&window.ZorvAI.setDark)window.ZorvAI.setDark($dark);",
            null,
        )
    }

    // 🔴 关键修复：dsl 随流式补全会变化（模型边写边生成 zx_web_card 参数），
    // `html` 随之重建。但 AndroidView 的 factory 只在 WebView 首次创建时执行一次，
    // 若不在这里随 html 重载，组件会永远停在「首次渲染时的半截 dsl」上 ——
    // 表现为「能渲染但渲染不完整 / AI 写完了界面却卡在半成品」。
    // 因此把加载放进 LaunchedEffect(html)：html 一变就重新 loadDataWithBaseURL。
    LaunchedEffect(html) {
        webView?.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
    }

    AndroidView(
        modifier = modifier.height(heightDp.dp),
        factory = { ctx ->
            WebView(ctx).apply {
                webView = this
                setBackgroundColor(AndroidColor.TRANSPARENT)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.cacheMode = WebSettings.LOAD_NO_CACHE
                settings.defaultTextEncodingName = "UTF-8"
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                settings.textZoom = 100
                // 内容超过高度上限时块内滚动；不超过则不出现滚动条
                isVerticalScrollBarEnabled = true
                setLayerType(View.LAYER_TYPE_HARDWARE, null)

                addJavascriptInterface(
                    object {
                        /** 用户操作：原样交给宿主（可能落到某个在等的工具，也可能作为用户消息发出） */
                        @JavascriptInterface
                        fun interact(payload: String) {
                            val json = runCatching { JSONObject(payload) }.getOrElse {
                                JSONObject().put("action", "raw").put("summary", payload)
                            }
                            onInteract(json)
                        }

                        /** 页面真实内容高度，宿主据此设内联块高度 */
                        @JavascriptInterface
                        fun height(px: Int) {
                            onHeight(px)
                        }
                    },
                    BRIDGE,
                )

                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        wvReady = true
                    }
                }
            }
        },
    )

    DisposableEffect(Unit) {
        onDispose {
            webView?.removeJavascriptInterface(BRIDGE)
            webView?.destroy()
            webView = null
        }
    }
}

/**
 * 页面里已经写着 `window.ZorvAIBridge.interact(...)`（单卡片页壳的历史命名），
 * 所以桥名不能改——改了页面就调不到，表现为「点了没反应」且毫无报错。
 */
private const val BRIDGE = "ZorvAIBridge"

/**
 * 从 `zx_web_card` 的工具参数里抽出内联渲染规格。
 *
 * 参数不合法就返回 null：**宁可不画，不要画错**——组件名缺失时随便猜一个
 * 会落到通用渲染，用户看到的是一块似是而非的界面，比不显示更难排查。
 * dsl 允许对象或字符串两种写法（模型两种都写过）。
 */
fun zxSpecOf(arguments: String?): ZxInlineSpec? {
    val raw = arguments?.trim().orEmpty()
    if (raw.isEmpty()) return null
    val o = runCatching { JSONObject(raw) }.getOrNull() ?: return null
    val comp = o.optString("component").trim()
    if (comp.isEmpty()) return null
    val dsl = when (val v = o.opt("dsl")) {
        is JSONObject -> v.toString()
        is String -> v.trim()
        null, JSONObject.NULL -> ""
        else -> v.toString()
    }
    if (dsl.isBlank() || dsl == "{}" || dsl == "null") return null
    return ZxInlineSpec(comp, dsl)
}
