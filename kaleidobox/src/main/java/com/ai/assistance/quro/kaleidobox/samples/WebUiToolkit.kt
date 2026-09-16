package com.ai.assistance.quro.kaleidobox.samples

import com.ai.assistance.quro.kaleidobox.core.engine.InvokeContext
import com.ai.assistance.quro.kaleidobox.core.engine.KaleidoToolkit
import com.ai.assistance.quro.kaleidobox.core.engine.ToolkitHost
import com.ai.assistance.quro.kaleidobox.core.model.KValue
import com.ai.assistance.quro.kaleidobox.core.ui.*
import com.ai.assistance.quro.kaleidobox.core.util.Json

/**
 * 真能力插件：WebUI 应用内浏览器（完整 app）。
 *
 * 用宿主注册的 [com.ai.assistance.quro.kaleidobox.android.ui.NativeComponentRegistry] 的
 * "webview" 原生组件（内嵌 Android WebView，自带后退/前进/刷新），在插件面板里直接渲染网页，
 * 不跳出 App、不走外部浏览器。并补齐浏览器该有的能力：
 *   - 常用站点快捷入口；
 *   - 书签（[data.kv] 持久化）：收藏当前 / 一键打开 / 删除；
 *   - 复制当前链接。
 */
class WebUiToolkit : KaleidoToolkit {

    private var host: ToolkitHost? = null

    override fun attach(host: ToolkitHost) { this.host = host }

    private val kvKey = "web_bookmarks"

    /** 常用站点。 */
    private val quickSites = listOf(
        "ZorvAI" to "https://www.zorvai.com",
        "百度" to "https://www.baidu.com",
        "必应" to "https://www.bing.com",
        "GitHub" to "https://github.com",
        "知乎" to "https://www.zhihu.com",
    )

    override fun invoke(fn: String, args: KValue, ctx: InvokeContext): KValue = when (fn) {
        "about_webui" -> KValue.Str("WebUI：应用内浏览器，直接渲染网页，含常用站点与书签。")
        "render" -> KValue.Str(Json.write(UiCodec.encode(buildUi(args))))
        "onAction" -> handleAction(args)
        else -> KValue.fail("E_NO_FN", "未知函数: $fn")
    }

    // ---------------------------------------------------------------- 书签

    private fun loadBookmarks(): List<String> {
        val r = host?.call("data.kv", KValue.obj("op" to "get", "key" to kvKey)) ?: KValue.Null
        val str = (r as? KValue.Str)?.value ?: return emptyList()
        val parsed = runCatching { Json.parse(str) }.getOrNull()
        return (parsed as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()
    }

    private fun saveBookmarks(list: List<String>) {
        host?.call(
            "data.kv",
            KValue.obj("op" to "set", "key" to kvKey, "value" to Json.write(KValue.of(list))),
        )
    }

    // ---------------------------------------------------------------- UI

    private fun buildUi(args: KValue): UiNode {
        val state = SamplesUi.readState(args)
        val url = (state["url"] as? String) ?: "https://www.zorvai.com"
        var bookmarks = SamplesUi.strList(state, "bookmarks")
        if (bookmarks.isEmpty()) bookmarks = loadBookmarks()

        return UiNode.Column(
            "root",
            modifier = Mod(width = Size.Fill, height = Size.Fill),
            children = buildList {
                // 常用站点
                add(
                    UiNode.Scroll(
                        "quick", vertical = false,
                        modifier = Mod(width = Size.Fill, padding = Edges(0, 0, 0, 6)),
                        child = UiNode.Row(
                            "quick_r",
                            children = quickSites.map { (name, u) ->
                                UiNode.Button(
                                    "qs_$name", Bound.Lit(name), Action.of("open", "url" to u),
                                    variant = UiNode.Button.Variant.TONAL,
                                    modifier = Mod(padding = Edges(0, 0, 0, 8)),
                                )
                            },
                        ),
                    )
                )
                // 地址栏
                add(
                    UiNode.Row(
                        "bar",
                        modifier = Mod(padding = Edges(0, 0, 0, 8)),
                        children = listOf(
                            UiNode.TextField(
                                "url", Bound.Ref("url"), Action.of("url"),
                                label = "网址 https://…", singleLine = true,
                                modifier = Mod(weight = 1f, minHeight = 52),
                            ),
                            UiNode.Button(
                                "go", Bound.Lit("前往"), Action.of("go"),
                                variant = UiNode.Button.Variant.FILLED,
                                modifier = Mod(padding = Edges(0, 0, 0, 8), minHeight = 52),
                            ),
                        ),
                    )
                )
                // 书签条
                if (bookmarks.isNotEmpty()) {
                    add(
                        UiNode.Scroll(
                            "bms", vertical = false,
                            modifier = Mod(width = Size.Fill, padding = Edges(0, 0, 0, 6)),
                            child = UiNode.Row(
                                "bms_r",
                                children = bookmarks.mapIndexed { i, b ->
                                    UiNode.Button(
                                        "bm_$i", Bound.Lit(shortHost(b)),
                                        Action.of("openBm", "idx" to i.toString()),
                                        variant = UiNode.Button.Variant.OUTLINED,
                                        modifier = Mod(padding = Edges(0, 0, 0, 8)),
                                    )
                                },
                            ),
                        )
                    )
                }
                // 网页主体
                add(
                    UiNode.Box(
                        "web",
                        modifier = Mod(width = Size.Fill, height = Size.Fill, weight = 1f,
                            background = "#000000", cornerRadius = 12),
                        children = listOf(UiNode.Native("wv", "webview", mapOf("url" to url))),
                    )
                )
                // 操作
                add(
                    UiNode.Row(
                        "ops",
                        modifier = Mod(padding = Edges(0, 8, 0, 0)),
                        children = listOf(
                            SamplesUi.button("star", "收藏当前", variant = UiNode.Button.Variant.TONAL, modifier = Mod(weight = 1f)),
                            SamplesUi.button("copyLink", "复制链接", variant = UiNode.Button.Variant.TONAL,
                                modifier = Mod(weight = 1f, padding = Edges(0, 0, 0, 8))),
                        ),
                    )
                )
            },
        )
    }

    // ---------------------------------------------------------------- 行为

    private fun handleAction(args: KValue): KValue {
        val obj = (args as? KValue.Obj)?.value ?: emptyMap()
        val state = SamplesUi.readState(args)
        val actionId = obj["actionId"]?.asString() ?: ""
        val payload = (obj["payload"] as? KValue.Obj)?.value ?: emptyMap()
        var url = (state["url"] as? String) ?: "https://www.zorvai.com"
        var bookmarks = SamplesUi.strList(state, "bookmarks").toMutableList()
        if (bookmarks.isEmpty()) bookmarks = loadBookmarks().toMutableList()

        fun normalize(u0: String): String {
            val u = u0.trim()
            return when {
                u.isEmpty() -> url
                u.startsWith("http://") || u.startsWith("https://") -> u
                else -> "https://$u"
            }
        }

        when (actionId) {
            "url" -> url = payload["value"]?.asString() ?: url
            "go" -> url = normalize(url)
            "open" -> url = payload["url"]?.asString() ?: url
            "openBm" -> {
                val idx = payload["idx"]?.asString()?.toIntOrNull() ?: -1
                bookmarks.getOrNull(idx)?.let { url = it }
            }
            "star" -> {
                if (url.isNotBlank() && url !in bookmarks) {
                    bookmarks.add(0, url)
                    saveBookmarks(bookmarks)
                    host?.call("ui.toast", KValue.obj("text" to "已收藏"))
                } else {
                    host?.call("ui.toast", KValue.obj("text" to "已在书签中"))
                }
            }
            "copyLink" -> {
                host?.call("ui.clipboard", KValue.obj("text" to url))
                host?.call("ui.toast", KValue.obj("text" to "链接已复制"))
            }
        }
        return KValue.obj("url" to url, "bookmarks" to bookmarks)
    }

    private fun shortHost(u: String): String =
        runCatching { java.net.URI(u).host?.removePrefix("www.") ?: u }.getOrDefault(u).take(18)
}
