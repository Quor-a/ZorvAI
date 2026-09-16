package com.zorv.plugin.zorvweb

import com.ai.assistance.quro.plugin.contract.ParamType
import com.ai.assistance.quro.plugin.contract.PluginContext
import com.ai.assistance.quro.plugin.contract.PluginEntry
import com.ai.assistance.quro.plugin.contract.ToolResult
import com.ai.assistance.quro.plugin.dsl.plugin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * ZorvWeb 网页引擎插件入口。
 *
 * 由 ZorvBrowser（ACI 受控端浏览器 App，43 项 ACI 能力）改写为宿主内 APK 插件。
 *
 * ── 改了什么（对应要求）────────────────────────────────────────────
 * 1. **包名**：`com.ai.assistance.quro.browser` → `com.zorv.plugin.zorvweb`（与宿主命名空间解耦）。
 * 2. **签名**：改用宿主同一把 `zorvai_release.jks`（宿主 PluginInstaller 只放行同签名插件）。
 * 3. **软件名**：Zorv Browser / ZorvAI 浏览器 → **ZorvWeb 网页引擎**。
 * 4. **ACI 去留**：受控端那套 AIDL 基础设施整段删除——
 *      · `QuroControlledAciService`（2344 行 AIDL 服务）、`QuroAciWakeReceiver`、`aci-core-debug.aar`、
 *        `ai.aci.permission.*` 权限与 `<queries>` —— 那是「独立 App 被外部 ACI 控制端绑定」的形态，
 *        插件形态下宿主自己就是控制端，这套跨进程信令**纯属冗余**。
 *      · `UinputBridge` + NDK/C++（设备级触摸注入，需 root）—— 属受控端 L3 事件面，插件不需要。
 *      · `consolekit` 目录（SDUI 控制台）—— 那是给外部控制端渲染 UI 用的，宿主有自己的界面。
 *      · `BrowserActivity` —— 插件不是系统安装的应用，自己的 Activity 起不来。
 *    保留下来的是**能力本身**，按插件形态重新落地为：AI 工具（LLM 直接调）+ ACI 能力（对外 ACI 面）
 *    + 界面表面（宿主承载的浏览器窗口）。
 * ─────────────────────────────────────────────────────────────────
 */
class ZorvWebEntry : PluginEntry {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var surfaceView: BrowserSurfaceView? = null

    override fun onCreate(ctx: PluginContext) {
        // 先起引擎：宿主 Application Context 建 WebView，保证还没打开界面时 AI 工具就能用。
        runCatching { WebEngine.init(ctx.appContext) }
            .onFailure { ctx.log("ZorvWeb", "引擎初始化失败: ${it.message}") }

        plugin(ctx) {

            // ═══════════════════ 导航与读取 ═══════════════════

            aiTool(
                "web_open",
                "在设备端浏览器引擎里打开一个网页并等它加载完。★ 用户给了网址、或需要读某个具体页面时，先用它。" +
                    "打开后可以用 web_read / web_crawl / web_script 继续操作同一个页面。",
            ) {
                param("url", ParamType.STRING, "网址，可省 https://（如 example.com）")
                param("wait_ms", ParamType.INT, "最多等待加载完成的毫秒数，默认 15000", required = false, default = "15000")
                param("desktop", ParamType.BOOLEAN, "用桌面版 UA（部分网站桌面版内容更全），默认 false", required = false, default = "false")
                param("new_tab", ParamType.BOOLEAN, "在新标签打开（保留当前页），默认 false", required = false, default = "false")
                execute { a ->
                    ToolResult.text(
                        WebEngine.open(
                            a.string("url"),
                            a.int("wait_ms", 15000).toLong(),
                            a.boolean("desktop"),
                            a.boolean("new_tab"),
                        )
                    )
                }
            }

            aiTool(
                "web_read",
                "读取当前页内容。★ 需要页面的 HTML / 结构时用它；clean=true 返回清洗过的精简 DOM" +
                    "（去掉 script/style，给可交互元素标了 id），更省 token，适合让模型理解页面结构。",
            ) {
                param("clean", ParamType.BOOLEAN, "true=返回精简 DOM，false=返回完整 HTML，默认 false", required = false, default = "false")
                execute { a -> ToolResult.text(WebEngine.readHtml(a.boolean("clean"))) }
            }

            aiTool(
                "web_script",
                "在当前页面上下文执行任意 JavaScript 并拿到返回值。★ 页面结构复杂、需要精确取数据或触发交互时用它" +
                    "（如 document.querySelector('.price').innerText）。",
            ) {
                param("code", ParamType.STRING, "要执行的 JS 表达式或语句，返回值会被带回")
                execute { a -> ToolResult.text(WebEngine.eval(a.string("code"))) }
            }

            aiTool(
                "web_crawl",
                "抓取当前页的结构化内容：标题 + 正文文本 + 出站链接。★ 需要「这篇网页讲了什么」时优先用它，" +
                    "比读原始 HTML 省大量 token。",
            ) {
                execute { ToolResult.text(WebEngine.crawl()) }
            }

            aiTool(
                "web_search_page",
                "用搜索引擎检索关键词，返回结果页的标题/摘要/链接。★ 用户要查资料、但没给具体网址时用它；" +
                    "返回的是浏览器真实打开的搜索结果页，不是搜索 API。",
            ) {
                param("q", ParamType.STRING, "搜索关键词")
                param(
                    "engine", ParamType.STRING, "搜索引擎，默认 bing",
                    required = false,
                    enum = listOf("bing", "google", "baidu", "duckduckgo"),
                    default = "bing",
                )
                execute { a -> ToolResult.text(WebEngine.searchOnEngine(a.string("q"), a.string("engine"))) }
            }

            // ═══════════════════ 元素定位与操作 ═══════════════════

            aiTool(
                "web_elements",
                "列出当前页可交互元素（按钮/输入框/链接…）并给每个元素分配稳定 ID。★ 要点击或填写某个元素但不知道怎么写选择器时，" +
                    "先调它拿元素列表和 ID，再用同样的工具传 id + op 操作（op=click/type/scroll_to/select）。",
            ) {
                param("id", ParamType.STRING, "可选。要操作的元素 ID（来自本工具上次返回）；不传则只列元素", required = false, default = "")
                param(
                    "op", ParamType.STRING, "可选。操作类型", required = false,
                    enum = listOf("click", "type", "scroll_to", "select"), default = "",
                )
                param("arg", ParamType.STRING, "可选。op=type/select 时要写入的内容", required = false, default = "")
                execute { a ->
                    val id = a.string("id")
                    val op = a.string("op")
                    ToolResult.text(
                        if (id.isBlank()) WebEngine.elements()
                        else WebEngine.actById(id, op.ifBlank { "click" }, a.string("arg"))
                    )
                }
            }

            aiTool(
                "web_query",
                "按 CSS 选择器查询元素，或直接用选择器操作元素。★ 已知页面结构（如 .btn-primary、#submit）时更精确；" +
                    "不传 op 只返回匹配结果，传 op 则操作第一个匹配元素。",
            ) {
                param("selector", ParamType.STRING, "CSS 选择器")
                param(
                    "op", ParamType.STRING, "可选。操作类型，不传=只查询", required = false,
                    enum = listOf("click", "type", "scroll_to", "select"), default = "",
                )
                param("arg", ParamType.STRING, "可选。op=type/select 时要写入的内容", required = false, default = "")
                execute { a ->
                    val op = a.string("op")
                    ToolResult.text(
                        if (op.isBlank()) WebEngine.query(a.string("selector"))
                        else WebEngine.actBySelector(a.string("selector"), op, a.string("arg"))
                    )
                }
            }

            aiTool(
                "web_wait",
                "等页面达到某个状态再继续。★ 点击后内容异步加载、或要等某个元素出现时用它，避免读到半成品页面。",
            ) {
                param("cond", ParamType.STRING, "等待条件", enum = listOf("visible", "hidden", "text_contains", "network_idle"))
                param("target_id", ParamType.STRING, "可选。元素 ID（来自 web_elements）；network_idle 不需要", required = false, default = "")
                param("arg", ParamType.STRING, "可选。cond=text_contains 时要匹配的文本", required = false, default = "")
                param("timeout_ms", ParamType.INT, "超时毫秒，默认 10000", required = false, default = "10000")
                execute { a ->
                    ToolResult.text(
                        WebEngine.waitFor(
                            a.string("cond"), a.string("target_id"), a.string("arg"), a.int("timeout_ms", 10000).toLong()
                        )
                    )
                }
            }

            aiTool(
                "web_find",
                "在当前页面里查找文本，返回命中数量并高亮。★ 页面很长、要确认某段内容在不在时用它，比读全文快。",
            ) {
                param("text", ParamType.STRING, "要查找的文本")
                execute { a -> ToolResult.text(WebEngine.findInPage(a.string("text"))) }
            }

            // ═══════════════════ 媒体 / 导航 / 状态 ═══════════════════

            aiTool(
                "web_media",
                "扫描当前页的视频/音频/图片/下载链接，返回可直接使用的绝对地址。★ 用户想下载页面上的视频或文件时用它。",
            ) {
                execute { ToolResult.text(WebEngine.media()) }
            }

            aiTool(
                "web_nav",
                "浏览器导航：后退 / 前进 / 刷新 / 停止加载。★ 用户说「返回上一页」「刷新一下」时用它。",
            ) {
                param("cmd", ParamType.STRING, "导航指令", enum = listOf("back", "forward", "reload", "stop"))
                execute { a -> ToolResult.text(WebEngine.nav(a.string("cmd"))) }
            }

            aiTool(
                "web_info",
                "查看网页引擎当前状态：内核版本、UA、当前网址与标题、标签数量、是否桌面模式。" +
                    "★ 不确定「现在打开的是哪个页面」时先看它。",
            ) {
                execute { ToolResult.text(WebEngine.info()) }
            }

            aiTool(
                "web_tabs",
                "多标签管理：列出 / 新建 / 切换 / 关闭标签页。★ 需要同时对照多个页面时用它。",
            ) {
                param("action", ParamType.STRING, "操作", enum = listOf("list", "new", "switch", "close"), default = "list")
                param("index", ParamType.INT, "可选。switch/close 的标签序号（从 0 开始）", required = false, default = "-1")
                param("url", ParamType.STRING, "可选。action=new 时要打开的网址", required = false, default = "")
                execute { a ->
                    val action = a.string("action").ifBlank { "list" }
                    ToolResult.text(
                        if (action == "list") WebEngine.tabsList()
                        else WebEngine.tabsAction(action, a.int("index", -1), a.string("url"))
                    )
                }
            }

            aiTool(
                "web_console",
                "读取页面 console.log/warn/error 输出。★ 页面行为不对、需要看前端报错时用它；" +
                    "action=clear 可清空，filter 按关键字过滤。",
            ) {
                param("action", ParamType.STRING, "snapshot=读取（默认） / clear=清空", enum = listOf("snapshot", "clear"), default = "snapshot")
                param("filter", ParamType.STRING, "可选。关键字过滤", required = false, default = "")
                param("limit", ParamType.INT, "最多返回条数，默认 100", required = false, default = "100")
                execute { a -> ToolResult.text(WebEngine.consoleLog(a.string("action"), a.string("filter"), a.int("limit", 100))) }
            }

            aiTool(
                "web_http",
                "直接发 HTTP 请求（不经页面），支持自定义方法/请求头/请求体，可访问同网段局域网服务" +
                    "（路由器后台、NAS、HomeAssistant 等 http://192.168.x.x）。★ 需要原始 API 调用或抓接口时用它，" +
                    "拿结构化响应比用浏览器渲染更省事。",
            ) {
                param("url", ParamType.STRING, "请求地址（http/https）")
                param("method", ParamType.STRING, "HTTP 方法，默认 GET", required = false, default = "GET")
                param("headers", ParamType.STRING, "可选。请求头 JSON，如 {\"Content-Type\":\"application/json\"}", required = false, default = "")
                param("body", ParamType.STRING, "可选。请求体原文", required = false, default = "")
                param("max_chars", ParamType.INT, "响应体最大字符数，默认 200000", required = false, default = "200000")
                execute { a ->
                    ToolResult.text(
                        WebHttp.request(
                            a.string("url"), a.string("method"), a.string("headers"),
                            a.string("body"), a.int("max_chars", 200000),
                        )
                    )
                }
            }

            // ═══════════════════ ACI 能力（对外 ACI 面）═══════════════════
            // 注意：统一加 zorvweb_ 前缀。宿主自身的 ACI 面已经有 browser_open / browser_read / http_request
            // （指向外部受控端那条路径），不加前缀会在合并能力清单时撞名。

            aciCapability("zorvweb_open", "打开网页并等待加载完成，返回最终 URL 与标题") {
                param("url", ParamType.STRING, "网址")
                param("wait_ms", ParamType.INT, "等待毫秒，默认 15000", required = false, default = "15000")
                execute { a -> ToolResult.text(WebEngine.open(a.string("url"), a.int("wait_ms", 15000).toLong(), false, false)) }
            }

            aciCapability("zorvweb_read", "读取当前页 HTML（clean=true 返回精简 DOM）") {
                param("clean", ParamType.BOOLEAN, "是否返回精简 DOM", required = false, default = "false")
                execute { a -> ToolResult.text(WebEngine.readHtml(a.boolean("clean"))) }
            }

            aciCapability("zorvweb_script", "在当前页面执行 JavaScript 并返回结果") {
                param("code", ParamType.STRING, "JS 代码")
                execute { a -> ToolResult.text(WebEngine.eval(a.string("code"))) }
            }

            aciCapability("zorvweb_crawl", "抓取当前页正文与出站链接（结构化）") {
                execute { ToolResult.text(WebEngine.crawl()) }
            }

            aciCapability("zorvweb_find", "页面内查找文本，返回命中数量") {
                param("text", ParamType.STRING, "要查找的文本")
                execute { a -> ToolResult.text(WebEngine.findInPage(a.string("text"))) }
            }

            aciCapability("zorvweb_query", "按 CSS 选择器查询或操作元素（op=click/type/scroll_to/select）") {
                param("selector", ParamType.STRING, "CSS 选择器")
                param("op", ParamType.STRING, "可选操作", required = false, default = "")
                param("arg", ParamType.STRING, "可选参数", required = false, default = "")
                execute { a ->
                    val op = a.string("op")
                    ToolResult.text(
                        if (op.isBlank()) WebEngine.query(a.string("selector"))
                        else WebEngine.actBySelector(a.string("selector"), op, a.string("arg"))
                    )
                }
            }

            aciCapability("zorvweb_elements", "列出可交互元素（含稳定 ID）；传 id+op 则操作该元素") {
                param("id", ParamType.STRING, "可选元素 ID", required = false, default = "")
                param("op", ParamType.STRING, "可选操作", required = false, default = "")
                param("arg", ParamType.STRING, "可选参数", required = false, default = "")
                execute { a ->
                    val id = a.string("id")
                    ToolResult.text(
                        if (id.isBlank()) WebEngine.elements()
                        else WebEngine.actById(id, a.string("op").ifBlank { "click" }, a.string("arg"))
                    )
                }
            }

            aciCapability("zorvweb_nav", "浏览器导航：back/forward/reload/stop") {
                param("cmd", ParamType.STRING, "导航指令", enum = listOf("back", "forward", "reload", "stop"))
                execute { a -> ToolResult.text(WebEngine.nav(a.string("cmd"))) }
            }

            aciCapability("zorvweb_media", "扫描当前页媒体/下载资源，返回绝对地址") {
                execute { ToolResult.text(WebEngine.media()) }
            }

            aciCapability("zorvweb_http", "直接发起 HTTP 请求（支持自定义方法/头/体，可访问局域网明文服务）") {
                param("url", ParamType.STRING, "请求地址")
                param("method", ParamType.STRING, "方法，默认 GET", required = false, default = "GET")
                param("headers", ParamType.STRING, "请求头 JSON", required = false, default = "")
                param("body", ParamType.STRING, "请求体", required = false, default = "")
                execute { a ->
                    ToolResult.text(
                        WebHttp.request(a.string("url"), a.string("method"), a.string("headers"), a.string("body"), 200000)
                    )
                }
            }

            // ═══════════════════ 界面表面（宿主承载的浏览器窗口）═══════════════════

            uiSurface(
                id = BrowserSurfaceView.SURFACE_ID,
                label = "浏览器",
                title = "ZorvWeb 浏览器",
                build = { actCtx, host ->
                    BrowserSurfaceView(actCtx, host).also { surfaceView = it }
                },
                onRelease = {
                    surfaceView?.release()
                    surfaceView = null
                },
            )
        }
    }

    override fun onDestroy(ctx: PluginContext) {
        surfaceView?.release()
        surfaceView = null
        ctx.unregisterAll()
        // 销毁 WebView 必须在主线程；插件卸载不能卡住宿主
        scope.launch { runCatching { WebEngine.shutdown() } }
    }
}

/** `web_search_page` 用到的搜索引擎地址拼装 + 结果页抓取。 */
internal suspend fun WebEngine.searchOnEngine(q: String, engine: String): String {
    val query = q.trim()
    if (query.isEmpty()) return JSONObject().put("error", "搜索关键词不能为空").toString()
    val encoded = java.net.URLEncoder.encode(query, "UTF-8")
    val url = when (engine.lowercase()) {
        "google" -> "https://www.google.com/search?q=$encoded"
        "baidu" -> "https://www.baidu.com/s?wd=$encoded"
        "duckduckgo" -> "https://duckduckgo.com/?q=$encoded"
        else -> "https://www.bing.com/search?q=$encoded"
    }
    val opened = WebEngine.open(url, 15000, false, false)
    if (runCatching { JSONObject(opened).optString("error", "") }.getOrDefault("").isNotEmpty()) return opened
    return WebEngine.crawl()
}
