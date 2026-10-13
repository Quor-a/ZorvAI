package com.ai.assistance.quro.ui.chat

import android.graphics.Color as AndroidColor
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ai.assistance.quro.R
import com.ai.assistance.quro.util.qstr
import org.json.JSONObject

/**
 * zxwidget 单卡片对话框 —— 「只装一张卡」的宿主。
 *
 * 术语先说清（这三个词在本仓经常被混着用）：
 *   · **zxwidget** = 动态交互界面。它是一套独立的组件编译器（477 个 zx 组件），
 *     既不是 ZorvAI 也不是 Web UI，只是**被这两个对话框一起支持**。
 *     代码在 `assets/zx/zxwidget.js`。
 *   · **ZorvAI 原生对话框** = 聊天主界面。模型调 `zx_web_card` 时由本类弹一张卡。
 *   · **Web UI 对话框** = ZorvAI 对话框的 Web 复制品，卡片嵌在消息流里渲染。
 *   所以卡片只有一个来源（zxwidget），区别只在「单独弹一张」还是「嵌在流里」。
 *
 * 触发路径：模型回复里调用 `zx_web_card` 工具 → `QuroZxWebCardAction`
 *   → `UiNavigationEvent.ZxWebCard` → 本对话框。
 *   它**不在**输入框「工具」菜单里（菜单里那几项是 AIP 文档 / 可视化弹窗 /
 *   可视化询问 / Web UI 对话框 / zxwidget 能力开关）。
 *
 * 为什么走 WebView 而不是原生 Compose：
 *   zx 组件族有 477 个，原生逐个实现要写几十万行；而它们的 DSL 是同构 JSON。
 *   Web 侧一个运行时 + 一层形状推断就能全覆盖，改样式也不用发版。
 *   宿主只保留三件事：把 DSL 送进去、把交互接出来、决定交互之后干什么。
 *
 * 注意：本页（`zx_card_runtime.html`）走 `loadDataWithBaseURL` + 构建期内联，
 *   `file://` 下加载不到外链脚本，所以它内联了一份编译器拷贝。
 *   那份拷贝与新模块 `zx/zxwidget.js` **目前并不完全同源**——见
 *   `scripts/zx/zxwidget_parity_check.js` 的 KNOWN-DRIFT 清单。
 */
object ZxWebCard {

    const val ASSET_NAME = "zx_card_runtime.html"

    /** zx 组件字段契约（从 zxwidget 的 ZxDsl* 提取），Web 运行时按它编译布局。 */
    const val CONTRACTS_NAME = "zx_contracts.json"

    /**
     * 把运行时 HTML 与初始 payload 合成一份可直接加载的文档。
     *
     * payload 随 HTML 一起下发（而不是等 WebView 起来再 `evaluateJavascript`），
     * 少一次异步握手——卡片打开即渲染，不会闪一下空白。
     *
     * 组件契约走 `<script type="application/json">` 注入：JSON 原文只做
     * `</script` 一类序列结束符转义，不必做整段字符串字面量转义
     * （190 KB 的契约用字符串字面量注进来，转义漏一个就整段白屏）。
     */
    fun buildHtml(
        assetHtml: String,
        contractsJson: String,
        component: String,
        title: String,
        dsl: String,
        dark: Boolean,
    ): String {
        val payload = JSONObject().apply {
            put("component", component)
            put("title", title)
            put("dark", dark)
            put("dsl", runCatching { JSONObject(dsl) }.getOrElse { JSONObject().put("_raw", dsl) })
        }.toString()
        // 只做 JSON 字符串字面量级别的转义：把 payload 安全嵌进一段 <script> 里。
        val safe = payload
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("'", "\\'")
            .replace("\n", "\\n")
            .replace("\r", "")
            .replace("<", "\\u003c")
            .replace(">", "\\u003e")
            .replace("&", "\\u0026")
        val inject = """
            <script>
            (function(){
              try{
                var p = JSON.parse("$safe");
                if (window.ZorvAI && window.ZorvAI.render) window.ZorvAI.render(p);
              }catch(e){
                if (window.ZorvAI && window.ZorvAI.render)
                  window.ZorvAI.render({component:"__error__",title:"卡片数据无法解析",
                                        dsl:{message:String(e)},dark:$dark});
              }
            })();
            </script>
        """.trimIndent()

        // 契约注入：只挡序列结束符，其余原文直出
        val contractsSafe = contractsJson
            .replace("</", "<\\/")
            .replace("<!--", "<\\!--")
        val withContracts = assetHtml.replace(
            "/*__ZX_CONTRACTS__*/",
            contractsSafe.ifBlank { "{}" },
        )

        val marker = "</body>"
        val at = withContracts.lastIndexOf(marker)
        return if (at >= 0) {
            withContracts.substring(0, at) + inject + "\n" + withContracts.substring(at)
        } else {
            withContracts + inject
        }
    }

    /**
     * 把一次 Web 交互整理成给模型看的一行结构化描述。
     *
     * 关键：**真实交互内容必须进正文**。只写「用户点了一下」模型无法接着推理，
     * 必须带上组件名、动作、以及被点的对象是什么。
     */
    fun summarize(component: String, json: JSONObject): String {
        val action = json.optString("action").ifBlank { "tap" }
        val summary = json.optString("summary").ifBlank { "$component · $action" }
        val rest = JSONObject()
        json.keys().forEach { k ->
            if (k !in setOf("action", "summary", "component")) rest.put(k, json.opt(k))
        }
        return buildString {
            append("【zxwidget 交互】组件 ")
            append(component)
            append("，动作 ")
            append(action)
            append("。")
            append(summary)
            if (rest.length() > 0) {
                append("\n交互明细：")
                append(rest.toString())
            }
        }
    }
}

/**
 * zxwidget 单卡片对话框本体。
 *
 * @param component  zx 组件名（zx_compare / zx_table / …）
 * @param title      对话框标题，空则用组件名
 * @param dsl        卡片 DSL JSON 原文
 * @param dark       是否深色（跟随宿主主题）
 * @param onInteract 用户在卡片上交互，宿主拿结构化 JSON 自行决定后续
 * @param onDismiss  关闭
 */
@Composable
fun ZxWebCardDialog(
    component: String,
    title: String,
    dsl: String,
    dark: Boolean,
    onInteract: (JSONObject) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var reloadKey by remember { mutableIntStateOf(0) }

    val assetHtml = remember(context) {
        runCatching {
            context.assets.open(ZxWebCard.ASSET_NAME).bufferedReader().use { it.readText() }
        }.getOrElse { "" }
    }

    // 组件契约单独读：190 KB，与运行时 HTML 分离，注入时只做序列结束符转义
    val contractsJson = remember(context) {
        runCatching {
            context.assets.open(ZxWebCard.CONTRACTS_NAME).bufferedReader().use { it.readText() }
        }.getOrElse { "" }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .fillMaxSize(0.86f),
        ) {
            Column(Modifier.fillMaxSize()) {
                // ── 标题栏：标题 + 重载 + 复制 DSL + 关闭 ──
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = title.ifBlank { component },
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { reloadKey++ }, modifier = Modifier.size(36.dp)) {
                        Icon(
                            Icons.Filled.Refresh,
                            qstr(R.string.qk_04066),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    IconButton(
                        onClick = { clipboard.setText(AnnotatedString(dsl)) },
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            Icons.Filled.ContentCopy,
                            qstr(R.string.qk_04067),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
                        Icon(
                            Icons.Filled.Close,
                            qstr(R.string.qk_00065),
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // ── Web UI 主体 ──
                if (assetHtml.isBlank()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            qstr(R.string.qk_04068, ZxWebCard.ASSET_NAME),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                } else {
                    // reloadKey 变化即整块重建 WebView（标题栏刷新按钮）——
                    // 比在 update 里判断「是否真的变了」更直白，也不会漏掉初次加载。
                    key(reloadKey) {
                        ZxWebCardView(
                            html = ZxWebCard.buildHtml(
                                assetHtml = assetHtml,
                                contractsJson = contractsJson,
                                component = component,
                                title = title,
                                dsl = dsl,
                                dark = dark,
                            ),
                            dark = dark,
                            onInteract = onInteract,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(8.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (dark) Color(0xFF101216) else Color(0xFFF6F7F9)),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ZxWebCardView(
    html: String,
    dark: Boolean,
    onInteract: (JSONObject) -> Unit,
    modifier: Modifier = Modifier,
) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    /**
     * 页面是否就绪。loadDataWithBaseURL 是异步的，webView 赋值 ≠ 页面脚本已定义；
     * 不等 onPageFinished 就下发，首次深色主题会打在还没有 window.ZorvAI 的页面上而失效。
     */
    var wvReady by remember { mutableStateOf(false) }

    // 主题切换不重建 WebView，只切根节点 class —— 省一次整页重载，也不丢表单状态
    LaunchedEffect(dark, wvReady) {
        if (!wvReady) return@LaunchedEffect
        webView?.evaluateJavascript(
            "if(window.ZorvAI&&window.ZorvAI.setDark)window.ZorvAI.setDark($dark);",
            null,
        )
    }

    Box(modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    webView = this
                    setBackgroundColor(AndroidColor.TRANSPARENT)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.loadWithOverviewMode = true
                    settings.useWideViewPort = true
                    settings.cacheMode = WebSettings.LOAD_NO_CACHE
                    settings.textZoom = 100
                    isVerticalScrollBarEnabled = true
                    setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
                    addJavascriptInterface(
                        object {
                            @JavascriptInterface
                            fun interact(payload: String) {
                                val json = runCatching { JSONObject(payload) }.getOrElse {
                                    JSONObject().put("action", "raw").put("summary", payload)
                                }
                                onInteract(json)
                            }
                        },
                        "ZorvAIBridge",
                    )
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            wvReady = true
                        }
                    }
                    loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
                }
            },
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            webView?.removeJavascriptInterface("ZorvAIBridge")
            webView?.destroy()
            webView = null
        }
    }
}
