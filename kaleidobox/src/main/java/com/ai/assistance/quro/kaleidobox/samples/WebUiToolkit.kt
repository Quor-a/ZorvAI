package com.ai.assistance.quro.kaleidobox.samples

import com.ai.assistance.quro.kaleidobox.core.engine.InvokeContext
import com.ai.assistance.quro.kaleidobox.core.engine.KaleidoToolkit
import com.ai.assistance.quro.kaleidobox.core.engine.ToolkitHost
import com.ai.assistance.quro.kaleidobox.core.model.KValue
import com.ai.assistance.quro.kaleidobox.core.ui.*
import com.ai.assistance.quro.kaleidobox.core.util.Json

/**
 * 真能力插件：WebUI 浏览器（完整 app）。
 *
 * 对标一个真实移动浏览器该有的能力，而不是"一个能显示网页的框"：
 *   - 地址栏（同时是搜索框：不是网址就按搜索词处理）；
 *   - 前进 / 后退 / 刷新 / 停止 / 主页 / 缩放 / 回到顶部·底部；
 *   - **多标签页**（新建、切换、关闭，会话持久化，下次打开还在）；
 *   - 书签（收藏当前页 / 打开 / 删除，[data.kv] 持久化）；
 *   - 历史记录（自动记录、去重、打开、清空，持久化）；
 *   - 常用站点一键直达；
 *   - 页内查找并高亮；
 *   - 桌面版网站开关（切换 User-Agent）；
 *   - 清隐私数据（缓存 / Cookie / 表单）；
 *   - 复制链接 / 复制标题+链接；
 *   - 系统下载（页面内下载交给系统下载器，落到「下载」目录）。
 *
 * 引擎是宿主注册的原生组件 `webview`：它把 WebView 的页面状态（URL/标题/可否前进后退/进度）
 * 通过 onAction 回传到这里，本插件再下发 `url` / `nav` 指令驱动它 —— 插件不持有任何 View。
 */
class WebUiToolkit : KaleidoToolkit {

    private var host: ToolkitHost? = null

    override fun attach(host: ToolkitHost) { this.host = host }

    private val bmKey = "web_bookmarks"
    private val histKey = "web_history"
    private val tabKey = "web_tabs"

    /** 主页。 */
    private val home = "https://www.baidu.com"

    /** 常用站点。 */
    private val quickSites = listOf(
        "ZorvAI" to "https://www.zorvai.com",
        "百度" to "https://www.baidu.com",
        "必应" to "https://www.bing.com",
        "GitHub" to "https://github.com",
        "知乎" to "https://www.zhihu.com",
        "哔哩哔哩" to "https://m.bilibili.com",
    )

    private val maxTabs = 8
    private val maxHistory = 200

    override fun invoke(fn: String, args: KValue, ctx: InvokeContext): KValue = when (fn) {
        "about_webui" -> KValue.Str(
            "WebUI 浏览器：应用内多标签浏览器，含前进/后退/刷新/停止/主页、" +
                "书签、历史记录、页内查找、桌面版 UA、隐私清理、系统下载。"
        )
        "render" -> KValue.Str(Json.write(UiCodec.encode(buildUi(args))))
        "onAction" -> handleAction(args)
        else -> KValue.fail("E_NO_FN", "未知函数: $fn")
    }

    // ---------------------------------------------------------------- 持久化

    private fun kvGet(key: String): String {
        val r = host?.call("data.kv", KValue.obj("op" to "get", "key" to key)) ?: KValue.Null
        return (r as? KValue.Str)?.value ?: ""
    }

    private fun kvSet(key: String, value: String) {
        host?.call("data.kv", KValue.obj("op" to "set", "key" to key, "value" to value))
    }

    private fun loadStrList(key: String): MutableList<String> {
        val parsed = runCatching { Json.parse(kvGet(key)) }.getOrNull()
        return ((parsed as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()).toMutableList()
    }

    private fun saveStrList(key: String, list: List<String>) = kvSet(key, Json.write(KValue.of(list)))

    /** 恢复上次的标签页与会话（真浏览器都会恢复会话）。 */
    private fun loadTabs(): Pair<MutableList<MutableMap<String, Any?>>, Int> {
        val parsed = runCatching { Json.parse(kvGet(tabKey)) }.getOrNull() as? Map<*, *>
        val arr = parsed?.get("tabs") as? List<*>
        val tabs = (arr ?: emptyList<Any?>())
            .mapNotNull { it as? Map<*, *> }
            .map { row -> row.entries.associate { e -> e.key.toString() to e.value }.toMutableMap() }
            .toMutableList()
        val active = (parsed?.get("active") as? Number)?.toInt() ?: 0
        return tabs to active
    }

    private fun saveTabs(tabs: List<Map<String, Any?>>, active: Int) =
        kvSet(tabKey, Json.write(KValue.of(mapOf("tabs" to tabs, "active" to active))))

    // ---------------------------------------------------------------- UI

    private fun buildUi(args: KValue): UiNode {
        val st = SamplesUi.readState(args)

        val pageUrl = SamplesUi.strOf(st, "pageUrl")
        val pageTitle = SamplesUi.strOf(st, "pageTitle")
        val url = SamplesUi.strOf(st, "url", home)
        val addr = SamplesUi.strOf(st, "addr", url)
        val nav = SamplesUi.strOf(st, "nav")
        val find = SamplesUi.strOf(st, "find")
        val clearData = SamplesUi.strOf(st, "clearData")
        val canBack = SamplesUi.boolOf(st, "canBack")
        val canFwd = SamplesUi.boolOf(st, "canFwd")
        val loading = SamplesUi.boolOf(st, "loading")
        val progress = SamplesUi.intOf(st, "progress")
        val desktop = SamplesUi.boolOf(st, "desktop")
        val panel = SamplesUi.strOf(st, "panel")
        val status = SamplesUi.strOf(st, "status")
        val findQuery = SamplesUi.strOf(st, "findQuery")
        val findMatches = SamplesUi.intOf(st, "findMatches", -1)

        var bookmarks = SamplesUi.strList(st, "bookmarks")
        if (bookmarks.isEmpty()) bookmarks = loadStrList(bmKey)
        var history = SamplesUi.strList(st, "history")
        if (history.isEmpty()) history = loadStrList(histKey)

        var tabs = SamplesUi.mapList(st, "tabs")
        var active = SamplesUi.intOf(st, "activeTab", 0)
        if (tabs.isEmpty()) {
            val restored = loadTabs()
            tabs = restored.first
            active = restored.second
        }
        if (tabs.isEmpty()) tabs.add(mutableMapOf("url" to home, "title" to ""))
        if (active !in tabs.indices) active = 0

        // 根节点子项【固定 6 个、顺序不变】——这是硬约束：
        // 宿主按位置复用 Compose 节点，一旦根层子项数量变化，webview 的槽位就会移位，
        // WebView 实例被重建 → 前进/后退栈丢失（表现为"点后退回到空白页"）。
        // 所以所有可变内容都收进"本身固定存在"的子容器里（面板 / 标签条内部变化不影响根层）。
        return UiNode.Column(
            "root",
            modifier = Mod(width = Size.Fill, height = Size.Fill),
            children = listOf(
                toolbar(canBack, canFwd, loading),
                addressRow(),
                tabStrip(tabs, active),
                panelBox(panel, tabs, active, bookmarks, history, desktop, findQuery, findMatches),
                webBox(url, nav, find, clearData, desktop),
                statusLine(pageTitle, pageUrl, loading, progress, status),
            ),
        )
    }

    /** 前进 / 后退 / 刷新·停止 / 主页 / 缩放 / 跳顶·跳底。 */
    private fun toolbar(canBack: Boolean, canFwd: Boolean, loading: Boolean): UiNode =
        UiNode.Scroll(
            "toolbar", vertical = false,
            modifier = Mod(width = Size.Fill, padding = Edges(0, 0, 0, 6)),
            child = UiNode.Row(
                "toolbar_r",
                children = listOf(
                    navBtn("back", "◀", canBack),
                    navBtn("forward", "▶", canFwd),
                    navBtn(if (loading) "stop" else "reload", if (loading) "✕" else "⟳", true),
                    navBtn("home", "首页", true),
                    navBtn("zoomOut", "－", true),
                    navBtn("zoomIn", "＋", true),
                    navBtn("top", "↑", true),
                    navBtn("bottom", "↓", true),
                    pill("star", "☆ 收藏", "star"),
                    pill("copyLink2", "复制链接", "copyLink"),
                ),
            ),
        )

    private fun navBtn(cmd: String, label: String, enabled: Boolean): UiNode =
        UiNode.Button(
            "nav_$cmd", Bound.Lit(label), Action.of("nav", "cmd" to cmd),
            variant = UiNode.Button.Variant.TONAL,
            enabled = Bound.Lit(enabled),
            modifier = Mod(padding = Edges(0, 0, 6, 8)),
        )

    private fun addressRow(): UiNode =
        UiNode.Row(
            "addrRow",
            modifier = Mod(width = Size.Fill, padding = Edges(0, 0, 0, 6)),
            children = listOf(
                UiNode.TextField(
                    "addr", Bound.Ref("addr"), Action.of("addr"),
                    label = "网址或搜索词", singleLine = true,
                    modifier = Mod(weight = 1f, minHeight = 52),
                ),
                UiNode.Button(
                    "go", Bound.Lit("前往"), Action.of("go"),
                    variant = UiNode.Button.Variant.FILLED,
                    modifier = Mod(padding = Edges(6, 0, 0, 10), minHeight = 52),
                ),
            ),
        )

    /** 标签条 + 面板入口。内部按钮数量可变，但它本身在根层占一个固定槽位。 */
    private fun tabStrip(tabs: List<Map<String, Any?>>, active: Int): UiNode =
        UiNode.Scroll(
            "tabStrip", vertical = false,
            modifier = Mod(width = Size.Fill, padding = Edges(0, 0, 0, 6)),
            child = UiNode.Row(
                "tabStrip_r",
                children = buildList {
                    tabs.forEachIndexed { i, t ->
                        add(
                            UiNode.Button(
                                "tab_$i", Bound.Lit(tabLabel(t)),
                                Action.of("switchTab", "idx" to i.toString()),
                                variant = if (i == active) UiNode.Button.Variant.FILLED
                                else UiNode.Button.Variant.OUTLINED,
                                modifier = Mod(padding = Edges(0, 0, 6, 8)),
                            )
                        )
                    }
                    add(pill("newTab", "＋ 新标签", "newTab"))
                    add(pill("closeTab", "✕ 关闭", "closeTab"))
                    add(pill("p_bm", "☆ 书签", "panel", "p" to "bookmarks"))
                    add(pill("p_hs", "🕘 历史", "panel", "p" to "history"))
                    add(pill("p_mn", "☰ 菜单", "panel", "p" to "menu"))
                },
            ),
        )

    /** 折叠面板：收起时高度 0（节点本身始终在根层，保证 webview 槽位不移位）。 */
    private fun panelBox(
        panel: String,
        tabs: List<Map<String, Any?>>,
        active: Int,
        bookmarks: List<String>,
        history: List<String>,
        desktop: Boolean,
        findQuery: String,
        findMatches: Int,
    ): UiNode {
        val open = panel.isNotEmpty()
        return UiNode.Box(
            "panelBox",
            modifier = Mod(
                width = Size.Fill,
                height = Size.Dp(if (open) 238 else 0),
                background = if (open) SamplesUi.C.surface else null,
                cornerRadius = 14,
                border = if (open) Border(1, SamplesUi.C.line) else null,
                padding = if (open) Edges.all(12) else Edges(),
                margin = Edges(0, 0, 0, 8),
            ),
            children = if (!open) emptyList() else listOf(
                UiNode.Scroll(
                    "panelScroll", vertical = true,
                    modifier = Mod(width = Size.Fill, height = Size.Fill),
                    child = UiNode.Column(
                        "panelCol",
                        children = when (panel) {
                            "bookmarks" -> panelBookmarks(bookmarks)
                            "history" -> panelHistory(history)
                            "menu" -> panelMenu(desktop, findQuery, findMatches)
                            else -> panelTabs(tabs, active)
                        },
                    ),
                ),
            ),
        )
    }

    private fun panelTabs(tabs: List<Map<String, Any?>>, active: Int): List<UiNode> = buildList {
        add(SamplesUi.section("pt_t", "快捷站点 · 当前第 ${active + 1}/${tabs.size} 个标签"))
        add(quickSitesRow("pt_q"))
        add(SamplesUi.hint("pt_h", "标签最多 $maxTabs 个；会话会自动保存，下次打开恢复。"))
    }

    private fun panelBookmarks(bookmarks: List<String>): List<UiNode> = buildList {
        add(SamplesUi.section("pb_t", "书签（${bookmarks.size}）"))
        if (bookmarks.isEmpty()) {
            add(SamplesUi.hint("pb_e", "还没有书签。浏览时点上方「☆ 收藏」即可加入。"))
        } else {
            bookmarks.forEachIndexed { i, b ->
                add(listRow("b", i, b, "openBm", "delBm"))
            }
        }
    }

    private fun panelHistory(history: List<String>): List<UiNode> = buildList {
        add(SamplesUi.section("ph_t", "历史记录（${history.size}）"))
        if (history.isEmpty()) {
            add(SamplesUi.hint("ph_e", "还没有历史记录。"))
        } else {
            history.take(60).forEachIndexed { i, u -> add(listRow("h", i, u, "openHist", null)) }
            add(SamplesUi.secondaryAction("clearHist", "清空历史"))
        }
    }

    private fun panelMenu(desktop: Boolean, findQuery: String, findMatches: Int): List<UiNode> = buildList {
        add(SamplesUi.section("pm_t", "菜单"))
        add(
            UiNode.Switch(
                "desktopSw", Bound.Lit(desktop), Action.of("desktop"),
                label = "桌面版网站（User-Agent）",
                modifier = Mod(width = Size.Fill, padding = Edges(0, 0, 0, 8)),
            )
        )
        add(
            UiNode.Row(
                "pm_find",
                modifier = Mod(width = Size.Fill, padding = Edges(0, 0, 0, 4)),
                children = listOf(
                    UiNode.TextField(
                        "findQuery", Bound.Ref("findQuery"), Action.of("findQuery"),
                        label = "页内查找", singleLine = true,
                        modifier = Mod(weight = 1f, minHeight = 50),
                    ),
                    UiNode.Button(
                        "findGo", Bound.Lit("查找"), Action.of("findGo"),
                        variant = UiNode.Button.Variant.FILLED,
                        modifier = Mod(padding = Edges(6, 0, 0, 10), minHeight = 50),
                    ),
                ),
            )
        )
        if (findMatches >= 0) add(SamplesUi.hint("pm_m", "页内匹配 $findMatches 处"))
        add(UiNode.Divider("pm_d", Mod(padding = Edges(0, 8, 0, 8))))
        add(
            UiNode.Row(
                "pm_row",
                children = listOf(
                    pill("mCopy", "复制链接", "copyLink"),
                    pill("mCopyT", "复制标题+链接", "copyTitle"),
                    pill("mClear", "清隐私数据", "clearData"),
                ),
            )
        )
        add(SamplesUi.hint("pm_h", "清隐私数据会清空缓存、Cookie、表单与后退历史。"))
    }

    /** 引擎槽位：永远在根层同一位置。 */
    private fun webBox(
        url: String, nav: String, find: String, clearData: String, desktop: Boolean,
    ): UiNode =
        UiNode.Box(
            "webBox",
            modifier = Mod(
                width = Size.Fill, height = Size.Fill, weight = 1f,
                background = "#000000", cornerRadius = 12, margin = Edges(0, 0, 0, 6),
            ),
            children = listOf(
                UiNode.Native(
                    "wv", "webview",
                    mapOf(
                        "url" to url,
                        "nav" to nav,
                        "find" to find,
                        "clearData" to clearData,
                        "desktop" to desktop,
                        "showToolbar" to false,
                    ),
                ),
            ),
        )

    private fun statusLine(
        title: String, pageUrl: String, loading: Boolean, progress: Int, status: String,
    ): UiNode {
        val line = buildString {
            if (loading) append("加载中 $progress%") else if (title.isNotBlank()) append(title)
            if (status.isNotBlank()) {
                if (isNotEmpty()) append(" · ")
                append(status)
            }
            if (isEmpty()) append(pageUrl)
        }
        return UiNode.Text(
            "status", Bound.Lit(line.take(160)), TypeStyle.CAPTION,
            color = SamplesUi.C.muted,
        )
    }

    private fun quickSitesRow(id: String): UiNode =
        UiNode.Scroll(
            id, vertical = false,
            modifier = Mod(width = Size.Fill, padding = Edges(0, 0, 0, 6)),
            child = UiNode.Row(
                "${id}_r",
                children = quickSites.map { (name, u) -> pill("${id}_$name", name, "quick", "url" to u) },
            ),
        )

    /** 书签 / 历史的一行：左侧点开，右侧可选删除。 */
    private fun listRow(
        key: String, idx: Int, url: String, openAction: String, delAction: String?,
    ): UiNode =
        UiNode.Row(
            "${key}_r$idx",
            modifier = Mod(width = Size.Fill, padding = Edges(0, 2, 0, 2)),
            children = buildList {
                add(
                    UiNode.Button(
                        "${key}_o$idx", Bound.Lit(shortLabel(url)),
                        Action.of(openAction, "url" to url),
                        variant = UiNode.Button.Variant.OUTLINED,
                        modifier = Mod(weight = 1f, padding = Edges(0, 0, 6, 6)),
                    )
                )
                if (delAction != null) {
                    add(
                        UiNode.Button(
                            "${key}_d$idx", Bound.Lit("✕"),
                            Action.of(delAction, "idx" to idx.toString()),
                            variant = UiNode.Button.Variant.TEXT,
                            modifier = Mod(padding = Edges(0, 0, 0, 6)),
                        )
                    )
                }
            },
        )

    private fun pill(
        id: String, label: String, actionId: String,
        payloadPair: Pair<String, Any?>? = null, filled: Boolean = false,
    ): UiNode =
        UiNode.Button(
            id, Bound.Lit(label),
            if (payloadPair == null) Action.of(actionId) else Action.of(actionId, payloadPair),
            variant = if (filled) UiNode.Button.Variant.FILLED else UiNode.Button.Variant.TONAL,
            modifier = Mod(padding = Edges(0, 0, 6, 8)),
        )

    // ---------------------------------------------------------------- 行为

    private fun handleAction(args: KValue): KValue {
        val obj = (args as? KValue.Obj)?.value ?: emptyMap()
        val st = SamplesUi.readState(args)
        val actionId = obj["actionId"]?.asString() ?: ""
        val payload = (obj["payload"] as? KValue.Obj)?.value ?: emptyMap()

        fun p(k: String): String = payload[k]?.asString() ?: ""

        var url = SamplesUi.strOf(st, "url", home)
        var addr = SamplesUi.strOf(st, "addr", url)
        var nav = SamplesUi.strOf(st, "nav")
        var seq = SamplesUi.intOf(st, "navSeq", 0)
        var desktop = SamplesUi.boolOf(st, "desktop")
        var panel = SamplesUi.strOf(st, "panel")
        var findQuery = SamplesUi.strOf(st, "findQuery")
        var find = SamplesUi.strOf(st, "find")
        var clearData = SamplesUi.strOf(st, "clearData")
        var status = ""

        var pageUrl = SamplesUi.strOf(st, "pageUrl")
        var pageTitle = SamplesUi.strOf(st, "pageTitle")
        var canBack = SamplesUi.boolOf(st, "canBack")
        var canFwd = SamplesUi.boolOf(st, "canFwd")
        var loading = SamplesUi.boolOf(st, "loading")
        var progress = SamplesUi.intOf(st, "progress")
        var findMatches = SamplesUi.intOf(st, "findMatches", -1)

        var tabs = SamplesUi.mapList(st, "tabs")
        var active = SamplesUi.intOf(st, "activeTab", 0)
        if (tabs.isEmpty()) {
            val restored = loadTabs()
            tabs = restored.first
            active = restored.second
        }
        if (tabs.isEmpty()) tabs.add(mutableMapOf("url" to home, "title" to ""))
        if (active !in tabs.indices) active = 0

        var bookmarks = SamplesUi.strList(st, "bookmarks").toMutableList()
        if (bookmarks.isEmpty()) bookmarks = loadStrList(bmKey)
        var history = SamplesUi.strList(st, "history").toMutableList()
        if (history.isEmpty()) history = loadStrList(histKey)

        fun setTab(i: Int, u: String, title: String? = null) {
            val t = tabs.getOrNull(i) ?: return
            t["url"] = u
            if (title != null) t["title"] = title
        }

        fun goCmd(cmd: String) {
            seq += 1
            nav = "$cmd@$seq"
        }

        /** 显式导航：同时更新地址栏、当前标签、并强制引擎加载一次。 */
        fun navigate(target: String) {
            val t = normalize(target)
            url = t
            addr = t
            setTab(active, t)
            goCmd("load")
        }

        fun recordHistory(u: String) {
            if (u.isBlank() || u.startsWith("about:") || u.startsWith("data:")) return
            history.remove(u)
            history.add(0, u)
            if (history.size > maxHistory) history = history.take(maxHistory).toMutableList()
            saveStrList(histKey, history)
        }

        when (actionId) {
            "addr" -> addr = p("value")

            "go" -> {
                val t = normalize(addr)
                if (t == pageUrl) goCmd("reload") else navigate(t)
            }

            "nav" -> {
                val cmd = p("cmd")
                if (cmd == "home") navigate(home) else goCmd(cmd)
            }

            "open", "quick", "openBm", "openHist" -> navigate(p("url"))

            "newTab" -> {
                if (tabs.size < maxTabs) {
                    tabs.add(mutableMapOf("url" to home, "title" to ""))
                    active = tabs.size - 1
                    navigate(home)
                } else {
                    status = "标签页最多 $maxTabs 个"
                }
                panel = ""
            }

            "closeTab" -> {
                if (tabs.size > 1) {
                    tabs.removeAt(active)
                    if (active >= tabs.size) active = tabs.size - 1
                    navigate((tabs.getOrNull(active)?.get("url") as? String) ?: home)
                } else {
                    status = "至少保留一个标签"
                }
            }

            "switchTab" -> {
                val i = p("idx").toIntOrNull() ?: -1
                if (i in tabs.indices && i != active) {
                    active = i
                    navigate((tabs[i]["url"] as? String) ?: home)
                }
                panel = ""
            }

            "panel" -> {
                val pp = p("p")
                panel = if (panel == pp) "" else pp
            }

            "star" -> {
                val target = pageUrl.ifBlank { addr }
                when {
                    target.isBlank() -> status = "没有可收藏的页面"
                    target in bookmarks -> status = "已在书签中"
                    else -> {
                        bookmarks.add(0, target)
                        saveStrList(bmKey, bookmarks)
                        status = "已收藏"
                    }
                }
            }

            "delBm" -> {
                val i = p("idx").toIntOrNull() ?: -1
                if (i in bookmarks.indices) {
                    bookmarks.removeAt(i)
                    saveStrList(bmKey, bookmarks)
                }
            }

            "clearHist" -> {
                history.clear()
                saveStrList(histKey, history)
                status = "历史已清空"
            }

            "copyLink" -> {
                host?.call("ui.clipboard", KValue.obj("text" to pageUrl.ifBlank { addr }))
                status = "链接已复制"
            }

            "copyTitle" -> {
                val t = if (pageTitle.isBlank()) pageUrl else "$pageTitle\n$pageUrl"
                host?.call("ui.clipboard", KValue.obj("text" to t))
                status = "标题与链接已复制"
            }

            "desktop" -> {
                desktop = !desktop
                // UA 切换由引擎组件自己处理（换 UA 后自动重载），这里不再下发 reload，
                // 否则会与组件内的 reload 叠加成"连点两次刷新"。
                status = if (desktop) "已切换桌面版" else "已切换移动版"
            }

            "findQuery" -> findQuery = p("value")

            "findGo" -> if (findQuery.isNotBlank()) {
                seq += 1
                find = "$findQuery@$seq"
            }

            "clearData" -> {
                seq += 1
                clearData = "clr@$seq"
                status = "正在清理…"
            }

            // ---- 引擎回传事件 ----

            "wvPage" -> {
                pageUrl = p("url")
                pageTitle = p("title")
                canBack = payload["canBack"]?.asBoolOr() ?: false
                canFwd = payload["canFwd"]?.asBoolOr() ?: false
                loading = payload["loading"]?.asBoolOr() ?: false
                progress = payload["progress"]?.asLongOr()?.toInt() ?: 0
                if (pageUrl.isNotBlank()) {
                    addr = pageUrl
                    setTab(active, pageUrl, pageTitle)
                    if (!loading) recordHistory(pageUrl)
                }
            }

            "wvError" -> {
                loading = false
                status = "加载失败：${p("desc")}"
            }

            "wvDownload" -> status =
                if (payload["ok"]?.asBoolOr() == true) "已交给系统下载" else "下载失败，可用「复制链接」"

            "wvFind" -> findMatches = payload["matches"]?.asLongOr()?.toInt() ?: 0

            "wvCleared" -> status =
                if (payload["ok"]?.asBoolOr() == true) "缓存/Cookie/表单已清理" else "清理失败"
        }

        saveTabs(tabs, active)

        return KValue.obj(
            "url" to url, "addr" to addr, "nav" to nav, "navSeq" to seq,
            "desktop" to desktop, "panel" to panel,
            "findQuery" to findQuery, "find" to find, "clearData" to clearData,
            "pageUrl" to pageUrl, "pageTitle" to pageTitle,
            "canBack" to canBack, "canFwd" to canFwd,
            "loading" to loading, "progress" to progress, "findMatches" to findMatches,
            "tabs" to tabs, "activeTab" to active,
            "bookmarks" to bookmarks, "history" to history,
            "status" to status,
        )
    }

    /**
     * 地址栏归一化 —— 像真浏览器一样，地址栏兼作搜索框：
     * 像域名就补 https，否则走搜索引擎。
     */
    private fun normalize(raw: String): String {
        val u = raw.trim()
        if (u.isEmpty()) return home
        val lower = u.lowercase()
        if (lower.startsWith("http://") || lower.startsWith("https://") ||
            lower.startsWith("about:") || lower.startsWith("data:") || lower.startsWith("file:")
        ) return u
        val looksLikeHost = !u.contains(' ') &&
            (u.contains('.') || u.startsWith("localhost") || u.startsWith("127.0.0.1"))
        return if (looksLikeHost) "https://$u"
        else "https://www.baidu.com/s?wd=" + java.net.URLEncoder.encode(u, "UTF-8")
    }

    private fun tabLabel(t: Map<String, Any?>): String {
        val title = (t["title"] as? String)?.takeIf { it.isNotBlank() }
        val base = title ?: shortLabel((t["url"] as? String).orEmpty())
        return if (base.length > 12) base.take(11) + "…" else base
    }

    private fun shortLabel(u: String): String {
        val s = u.removePrefix("https://").removePrefix("http://").removePrefix("www.")
        return if (s.length > 46) s.take(44) + "…" else s
    }
}
