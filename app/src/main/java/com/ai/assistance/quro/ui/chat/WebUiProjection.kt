package com.ai.assistance.quro.ui.chat

import com.ai.assistance.quro.core.QuroMessage
import com.ai.assistance.quro.core.canvas.Aip
import com.ai.assistance.quro.core.cards.CardFence
import com.ai.assistance.quro.core.cards.serializeCard
import com.ai.assistance.quro.core.policy.QuroPolicy
import com.ai.assistance.quro.core.tools.QuroVoiceStyle
import com.ai.assistance.quro.core.ui.card.spec.A11y
import com.ai.assistance.quro.core.ui.card.spec.Action
import com.ai.assistance.quro.core.ui.card.spec.CardData
import com.ai.assistance.quro.core.ui.card.spec.CardSpec
import com.ai.assistance.quro.core.ui.card.spec.LayoutNode
import com.ai.assistance.quro.core.ui.card.spec.StyleToken
import com.ai.assistance.quro.core.ui.card.spec.parseCardSpec
import com.ai.assistance.quro.core.ui.dynamicui.*
import com.ai.assistance.quro.ui.MsgBlock
import com.ai.assistance.quro.ui.extractInlineComponents
import com.ai.assistance.quro.ui.parseBlocks
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Web UI 投影层 —— 把 ZorvAI 对话框**该被看见的东西**序列化出去。
 *
 * ── 为什么要有这一层（用户 2026-10-13 纠偏）───────────────────────────
 * 用户原话：「web ui 是 zorvAI 的投影」，不是「另外设计 UI 界面」。
 * 前几轮我做错的地方是：把网页写成了**一个独立设计的聊天应用**——自造顶栏、
 * 自造空状态、自造页脚、自造一套 Markdown 渲染器。那不是投影，那是第二个产品。
 *
 * 投影的正确定义（沿用本仓既有词汇，见 `docs/features/cluster/README.md`：
 * 「集群事件会投影成当前对话框的气泡」）：
 * **宿主把「该被看见的块」投出去，接收端只负责呈现，不负责设计。**
 *
 * ── 实现要点：块真相源只有一份 ───────────────────────────────────────
 * 本文件**不自己解析** Markdown／围栏／表格。对话正文一律走 [parseBlocks]
 * ——正是原生对话框 `MessageRow` 渲染用的同一个入口
 * （它已从 `private` 提为 `internal`，就是为了这里能复用）。
 * 于是「网页看到的块」与「原生看到的块」在**结构上不可能漂移**：
 * 它们本来就是同一个函数的输出，差别只在谁来画。
 *
 * 🔴 因此本文件里若出现任何 ``` 围栏嗅探、Markdown 分词、HTML 探测，
 *    都是**投影退化成自造实现**的信号，必须打回。
 */
object WebUiProjection {

    /** 与原生一致：互动UI界面挂在 `zx_web_card` 这个工具调用上。 */
    private const val ZX_TOOL = "zx_web_card"

    /**
     * 顶栏 = 原生 `ChatTopBar` 的内容（模型名 / 人格 / 标题）。
     * 只投字段，不投「怎么画」。
     */
    fun bar(title: String, modelName: String, personaName: String): JSONObject =
        JSONObject()
            .put("title", title)
            .put("model", modelName)
            .put("persona", personaName)

    /**
     * 输入区 = 原生 `ChatPermissionModeBar` + 输入行。
     * 这些开关在原生侧是胶囊按钮，投影只投**状态**，画法由接收端与原生保持一致。
     *
     * 字段与原生摘要行**逐项对应**，一个都不许少：
     *   deepThink / autoSaveMemory / subAgent / autoRead / vision（五个布尔开关）
     *   + [cms] / [priv]（两个三态策略，见下） + [workspace]。
     *
     * 🔴 [cms] / [priv] 不是可选项。原生摘要行把它们**常驻**显示，理由写在
     *    `ChatPermissionModeBar` 里：「模式开关开没开直接改变消息发去哪，用户看不见
     *    就等于消息莫名其妙不回」。既然如此，投影少了这两个标签，网页那边看到的
     *    权限状态就是**残缺的**——而权限状态残缺正是「AI 明明能动手却报没权限」这类
     *    困惑的源头。宁可多两个 chip，不可少。
     */
    fun composer(
        busy: Boolean,
        deepThink: Boolean,
        autoSaveMemory: Boolean,
        subAgent: Boolean,
        autoRead: Boolean,
        vision: Boolean,
        workspace: String,
        cms: QuroPolicy,
        priv: QuroPolicy,
    ): JSONObject = JSONObject()
        .put("busy", busy)
        .put("deepThink", deepThink)
        .put("autoSaveMemory", autoSaveMemory)
        .put("subAgent", subAgent)
        .put("autoRead", autoRead)
        .put("vision", vision)
        .put("workspace", workspace)
        .put("cms", policyName(cms))
        .put("priv", policyName(priv))

    /**
     * 三态策略 → 规范化小写名（`allow` / `deny` / `ask`）。
     *
     * 投**状态**不投**文案**：原生那三个标签用的是 i18n 字符串
     * （`qk_03235` 允许 / `qk_03236` 禁止 / `qk_03166` 询问），
     * 投影层一旦把中文写死，接收端换语言就跟着错。
     * 与顶栏只投 `model`/`persona` 而不投「怎么画」是同一条规矩。
     */
    private fun policyName(p: QuroPolicy): String = when (p) {
        QuroPolicy.ALLOW -> "allow"
        QuroPolicy.DENY -> "deny"
        QuroPolicy.ASK -> "ask"
    }

    /**
     * 一条消息 → 该被看见的块。
     *
     * 块顺序与原生 `MessageRow` 一致：**思考 → 正文块序列 → 工具 → 互动UI界面**。
     * 顺序在这里定死、不接受接收端重排：顺序就是原生对话的语义。
     *
     * 名字行兜底三件套（[authorFallback] / [avatarFallback] / [avatarUrl]）由调用方
     * 一次性解析好再传进来 —— 人格/用户资料在仓库里，投影层**不自己翻**：
     * 快照最频 1s 一次，每帧翻仓库会把投影变成磁盘扫描。规则见
     * [com.ai.assistance.quro.ui.chat.QuroWebUiServer] 的 NameFallbacks 缓存。
     */
    fun message(
        m: QuroMessage,
        mine: Boolean,
        authorFallback: String = "",
        avatarFallback: String = "",
        avatarUrl: String = "",
    ): JSONObject {
        val o = JSONObject()
            .put("id", m.id)
            .put("role", m.role)
            .put("mine", mine)
            .put("senderName", m.senderName ?: "")
            .put("time", m.createdAt)

        // ── 名字行（对齐原生 MessageRow 的 `作者 · 时间`）──
        // 原生规则（ChatScreen.kt:538 / 8366-8368 / QuroSoulUi.AvatarContent）：
        //   作者 = senderName（集群/子智能体角色名）?: 人格名 / 用户名；
        //   头像 = 角色名首字母 / 人格 ava；有图才给 avatarUrl（无图退化成首字母圆标）。
        val speaker = m.senderName?.takeIf { it.isNotBlank() }
        o.put("author", speaker ?: authorFallback)
            .put("avatar", speaker?.take(1) ?: avatarFallback)
            .put("avatarUrl", if (speaker == null) avatarUrl else "")
            .put("timeText", chatTimeText(m.createdAt))

        // ── 思考 ──
        m.reasoning?.takeIf { it.isNotBlank() }?.let { o.put("reasoning", it) }

        // ── 正文块 + 富组件卡片：与原生同一个解析入口，不重写 ──
        val raw = m.content.takeIf { it.isNotBlank() }
        val clean = raw?.let { runCatching { QuroVoiceStyle.strip(it) }.getOrDefault(it) }
        // extractInlineComponents 的第二个返回值就是「文本内联下发的富组件卡片」——
        // 原生在消息底部全宽内联渲染它们（ChatScreen.kt:4091 inlineCards）。
        // 旧投影只取 .first（剥完的文本），卡片**整条通道被丢弃**，那正是
        // 「web ui 根本没有对话框渲染能力」的一处实锤：AI 下发了卡片，网页上看不见。
        val extracted = clean?.let { runCatching { extractInlineComponents(it) }.getOrNull() }
        blockList(extracted?.first ?: clean).let { if (it.length() > 0) o.put("blocks", it) }
        val inlineCards = extracted?.second.orEmpty()
        val allCards = m.cards + inlineCards
        if (allCards.isNotEmpty()) {
            val arr = JSONArray()
            allCards.forEach { c -> runCatching { arr.put(serializeCard(c)) } }
            if (arr.length() > 0) o.put("cards", arr)
        }

        // ── 工具调用（原生是「N 工具」胶囊，投影投同样的条目）──
        m.toolCalls?.takeIf { it.isNotEmpty() }?.let { calls ->
            val arr = JSONArray()
            calls.forEach { tc ->
                // 🔴 互动UI界面不是「一次工具调用」：原生侧把它抽成界面块内联渲染，
                //   投影必须同样处理，否则网页会多出一行原始 JSON 参数
                //   —— 那正是「卡片方式」的残留观感，用户已明确否定。
                if (tc.name == ZX_TOOL) return@forEach
                arr.put(
                    JSONObject()
                        .put("name", tc.name)
                        .put("arguments", tc.arguments)
                        .put("result", tc.result ?: JSONObject.NULL)
                        .put("durationMs", tc.durationMs)
                )
            }
            if (arr.length() > 0) o.put("tools", arr)
        }

        // ── 互动UI界面（zxwidget）──
        // 🔴 只投「这里有一块互动UI界面 + 它的组件名」，**不投实现、不做编译**。
        //   zxwidget 是独立模块（`assets/zx/`），Web UI 不拥有它、也不负责渲染它；
        //   用户原话：「你怎么老是把 zxwidget 和 webui 混合在一起」。
        uiBlocks(m)?.let { o.put("ui", it) }

        // ── 附件 ──
        m.attachments?.takeIf { it.isNotEmpty() }?.let { atts ->
            val arr = JSONArray()
            atts.forEach { a ->
                arr.put(
                    JSONObject()
                        .put("type", a.type)
                        .put("uri", "file://" + a.uri)
                        .put("name", a.name)
                )
            }
            o.put("attachments", arr)
        }
        return o
    }

    /**
     * 正文 → 块 JSON。
     *
     * 入参是**已经走过** [QuroVoiceStyle.strip] + [extractInlineComponents] 的文本
     * （调用方 [message] 做，两个通道只解析一次）；这里只负责 [parseBlocks] 分派。
     * 这三步一步都不能省，否则网页会比原生多出/少掉块
     * （内联组件 JSON 会裸露成正文文本，页面上就是一坨 JSON）。
     */
    internal fun blockList(text: String?): JSONArray {
        val arr = JSONArray()
        val t = text?.takeIf { it.isNotBlank() } ?: return arr
        runCatching { parseBlocks(t) }.getOrDefault(emptyList()).forEach { b ->
            blockJson(b).let { arr.put(it) }
        }
        return arr
    }

    /**
     * 消息时间 → 名字行显示文本，与原生 `formatChatTime`（ChatScreen.kt:8422）同规则：
     * 当天 `HH:mm`，跨天 `MM/dd`。
     */
    private fun chatTimeText(ts: Long): String {
        val now = Calendar.getInstance()
        val c = Calendar.getInstance().also { it.timeInMillis = ts }
        val sameDay = c.get(Calendar.YEAR) == now.get(Calendar.YEAR) &&
            c.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)
        val pattern = if (sameDay) "HH:mm" else "MM/dd"
        return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(ts))
    }

    /**
     * 流式期间的**原生专属块**快照（任务 #255「边写边出界面」的服务端一半）。
     *
     * 与 [message] 共用同一条解析链（strip → extractInlineComponents → parseBlocks），
     * 但只挑原生渲染的块（Mermaid / Aip / DynamicUi / SelfCard / MiniApp / Card）——
     * 流式正文由前端 mini-markdown 画，这里补的正是它画不了的那部分。
     *
     * `sig` = 各块 JSON 长度串：围栏从「没闭合」到「闭合」、JSON 从半截到完整都会变，
     * 纯文本增长不会变（调用方据此去重，不重复推）。
     */
    fun streamNativeBlocks(m: QuroMessage): JSONObject {
        val arr = JSONArray()
        val sig = StringBuilder()
        val clean = m.content.takeIf { it.isNotBlank() }
            ?.let { runCatching { QuroVoiceStyle.strip(it) }.getOrDefault(it) }
        val stripped = clean?.let { runCatching { extractInlineComponents(it).first }.getOrDefault(it) }
        runCatching { parseBlocks(stripped ?: "") }.getOrDefault(emptyList()).forEach { b ->
            if (b is MsgBlock.Mermaid || b is MsgBlock.Aip || b is MsgBlock.DynamicUi ||
                b is MsgBlock.SelfCard || b is MsgBlock.MiniApp || b is MsgBlock.Card
            ) {
                val j = blockJson(b)
                arr.put(j)
                sig.append(j.toString().length).append('|')
            }
        }
        return JSONObject().put("sig", sig.toString()).put("blocks", arr)
    }

    /** 一种块 → 一个 JSON 对象。`t` 是块类型，接收端按它分派。 */
    private fun blockJson(b: MsgBlock): JSONObject = when (b) {
        is MsgBlock.Text -> JSONObject().put("t", "text").put("text", b.text)

        is MsgBlock.Heading ->
            JSONObject().put("t", "heading").put("level", b.level).put("text", b.text)

        is MsgBlock.Quote -> JSONObject().put("t", "quote").put("text", b.text)

        is MsgBlock.Rule -> JSONObject().put("t", "rule")

        is MsgBlock.Code ->
            JSONObject().put("t", "code").put("lang", b.lang).put("code", b.code)

        is MsgBlock.Table -> {
            val header = JSONArray()
            b.header.forEach { header.put(it) }
            val rows = JSONArray()
            b.rows.forEach { r ->
                val rr = JSONArray()
                r.forEach { rr.put(it) }
                rows.put(rr)
            }
            JSONObject().put("t", "table").put("header", header).put("rows", rows)
        }

        // 原生专属渲染的块（Mermaid 图 / AIP 排版 / 动态 UI / 自研卡片 / 组件围栏 / MiniApp）：
        // 它们不再是「原生渲染+源码」占位 —— Web UI 是 ZorvAI 对话框的**对等客户端**，
        // 必须真正渲染出来。这里投出「块类型 + 渲染数据」，前端按 kind 真渲染。
        // 数据由宿主侧**同一批解析器**产出（CardFence.toCards / Aip.parse / A2uiInterpreter /
        // parseCardSpec），与原生看到的是同一份结构，不会漂移。
        is MsgBlock.Mermaid ->
            JSONObject().put("t", "native").put("kind", "mermaid").put("data", mermaidJson(b))

        is MsgBlock.Aip ->
            JSONObject().put("t", "native").put("kind", "aip").put("data", aipJson(b))

        is MsgBlock.DynamicUi ->
            JSONObject().put("t", "native").put("kind", "dynamic_ui").put("data", dynamicUiJson(b))

        is MsgBlock.SelfCard ->
            JSONObject().put("t", "native").put("kind", "self_card").put("data", selfCardJson(b))

        is MsgBlock.MiniApp ->
            JSONObject().put("t", "native").put("kind", "miniapp").put("data", miniappJson(b))

        is MsgBlock.Card -> JSONObject()
            .put("t", "native")
            .put("kind", "card")
            .put("data", cardGroupJson(b))
    }

    /**
     * Mermaid 渲染数据：source + 主题（跟随系统暗色，由前端决定）。
     */
    private fun mermaidJson(b: MsgBlock.Mermaid): JSONObject =
        JSONObject().put("source", b.source)

    /**
     * AIP 渲染数据：信封结构（blocks 序列化），前端按块类型排版。
     * 复用 [Aip.parse] —— 与原生 AipCanvas 同一解析入口。
     */
    private fun aipJson(b: MsgBlock.Aip): JSONObject {
        val r = runCatching { Aip.parse(b.source) }.getOrNull() ?: return JSONObject().put("ok", false)
        val env = r.envelope ?: return JSONObject()
            .put("ok", false)
            .put("degradation", r.degradation.name)
            .put("raw", b.source)
        val blocks = JSONArray()
        env.blocks.forEach { blocks.put(aipBlockJson(it)) }
        return JSONObject()
            .put("ok", true)
            .put("v", env.v)
            .put("kind", env.kind)
            .put("title", env.title)
            .put("subtitle", env.subtitle)
            .put("author", env.author)
            .put("accent", env.accent)
            .put("themeName", env.themeName)
            .put("blocks", blocks)
    }

    /** 一个 AIP 块 → JSON。字段名与 Aip.Block 数据类一一对应。 */
    private fun aipBlockJson(b: Aip.Block): JSONObject = when (b) {
        is Aip.Block.Heading -> JSONObject().put("type", "heading").put("level", b.level).put("text", b.text)
        is Aip.Block.Paragraph -> JSONObject().put("type", "paragraph").put("text", b.text)
        is Aip.Block.ListBlock -> JSONObject()
            .put("type", "list").put("ordered", b.ordered)
            .put("items", strArr(b.items))
        is Aip.Block.Table -> JSONObject()
            .put("type", "table")
            .put("headers", strArr(b.headers))
            .put("rows", strArrArr(b.rows))
        is Aip.Block.Code -> JSONObject().put("type", "code").put("lang", b.lang).put("code", b.code)
        is Aip.Block.Quote -> JSONObject().put("type", "quote").put("text", b.text).put("cite", b.cite)
        is Aip.Block.Callout -> JSONObject().put("type", "callout").put("tone", b.tone).put("title", b.title).put("text", b.text)
        is Aip.Block.Divider -> JSONObject().put("type", "divider")
        is Aip.Block.Image -> JSONObject().put("type", "image").put("ref", b.ref).put("caption", b.caption).put("ratio", b.ratio)
        is Aip.Block.Chart -> JSONObject()
            .put("type", "chart")
            .put("chartType", b.chartType)
            .put("title", b.title)
            .put("labels", strArr(b.labels))
            .put("series", JSONArray().also { a ->
                b.series.forEach { s -> a.put(JSONObject().put("name", s.name).put("values", dblArr(s.values))) }
            })
        is Aip.Block.Columns -> JSONObject()
            .put("type", "columns")
            .put("ratio", intArr(b.ratio))
            .put("children", JSONArray().also { a -> b.children.forEach { col -> a.put(strArr(col.map { aipBlockJson(it).toString() })) } })
        is Aip.Block.Steps -> JSONObject().put("type", "steps").put("items", strArr(b.items)).put("direction", b.direction)
        is Aip.Block.Timeline -> JSONObject().put("type", "timeline").put("items", JSONArray().also { a ->
            b.items.forEach { it -> a.put(JSONObject().put("time", it.time).put("title", it.title).put("text", it.text)) }
        })
        is Aip.Block.Mindmap -> JSONObject().put("type", "mindmap").put("layout", b.layout).put("root", mindmapNodeJson(b.root))
        is Aip.Block.Slide -> JSONObject()
            .put("type", "slide")
            .put("layout", b.layout)
            .put("title", b.title)
            .put("subtitle", b.subtitle)
            .put("bullets", strArr(b.bullets))
            .put("columns", JSONArray().also { a -> b.columns.forEach { (t, txt) -> a.put(JSONObject().put("title", t).put("text", txt)) } })
            .put("stats", JSONArray().also { a -> b.stats.forEach { (v, l) -> a.put(JSONObject().put("value", v).put("label", l)) } })
            .put("chart", b.chart?.let { aipBlockJson(it) } ?: JSONObject.NULL)
            .put("table", b.table?.let { aipBlockJson(it) } ?: JSONObject.NULL)
            .put("imageRef", b.imageRef)
            .put("quote", b.quote)
            .put("quoteAuthor", b.quoteAuthor)
            .put("notes", b.notes)
        is Aip.Block.Section -> JSONObject().put("type", "section").put("level", b.level).put("title", b.title)
        is Aip.Block.Html -> JSONObject().put("type", "html").put("html", b.html)
        is Aip.Block.Fallback -> JSONObject().put("type", b.type).put("text", b.text)
    }

    private fun mindmapNodeJson(n: Aip.Block.Mindmap.Node): JSONObject = JSONObject()
        .put("id", n.id)
        .put("text", n.text)
        .put("tone", n.tone)
        .put("children", JSONArray().also { a -> n.children.forEach { a.put(mindmapNodeJson(it)) } })

    /**
     * 组件围栏渲染数据：组属性 + 卡片 JSON 数组。
     * 复用 [CardFence.toCards] + [serializeCard] —— 与原生同一解码层、同一序列化。
     * 流式未闭合/无数据时返回 ok=false，前端不闪空卡。
     */
    private fun cardGroupJson(b: MsgBlock.Card): JSONObject {
        val attrs = runCatching { CardFence.parseValueAttrs(b.attrs) }.getOrDefault(emptyMap())
        val title = attrs["title"].orEmpty()
        val theme = attrs["theme"]?.lowercase()?.takeIf { it in CardFence.THEME_PRESETS } ?: "accent"
        val compact = runCatching { CardFence.parseAttrs(b.attrs).contains("compact") }.getOrDefault(false)
        val cards = runCatching { CardFence.toCards(b.fence, b.source, title) }
            .getOrNull()
            ?.filter { c -> !serializedCardHasNoData(serializeCard(c)) }
            ?.takeIf { it.isNotEmpty() }
            ?: return JSONObject()
                .put("ok", false)
                .put("fence", b.fence)
                .put("closed", b.closed)
                .put("attrs", b.attrs)
        val arr = JSONArray()
        cards.forEach { arr.put(serializeCard(it)) }
        return JSONObject()
            .put("ok", true)
            .put("fence", b.fence)
            .put("closed", b.closed)
            .put("title", title)
            .put("theme", theme)
            .put("compact", compact)
            .put("showTitle", b.fence != CardFence.FENCE_CARDJSON || b.closed)
            .put("cards", arr)
    }

    /** 动态 UI 渲染数据：节点树序列化。复用 [A2uiInterpreter.interpret]。 */
    private fun dynamicUiJson(b: MsgBlock.DynamicUi): JSONObject {
        val r = runCatching { A2uiInterpreter.interpret(b.source) }.getOrNull()
            ?: return JSONObject().put("ok", false).put("reason", "interpret 异常")
        return when (r) {
            is QuroUiParseResult.Success -> JSONObject()
                .put("ok", true)
                .put("root", uiNodeJson(r.root))
            is QuroUiParseResult.Failure -> JSONObject()
                .put("ok", false)
                .put("reason", r.reason)
                .put("raw", r.rawJson.take(600))
        }
    }

    /** 一个动态 UI 节点 → JSON。字段名与 QuroUiNode 数据类一一对应。 */
    private fun uiNodeJson(n: QuroUiNode): JSONObject = when (n) {
        is QuroColumnNode -> baseUiNode(n, "column")
            .put("children", uiChildren(n.children))
            .put("spacing", n.spacing).put("padding", n.padding)
            .put("horizontalAlign", n.horizontalAlign).put("scrollable", n.scrollable).put("weight", n.weight)
        is QuroRowNode -> baseUiNode(n, "row")
            .put("children", uiChildren(n.children))
            .put("spacing", n.spacing).put("padding", n.padding)
            .put("verticalAlign", n.verticalAlign).put("scrollable", n.scrollable).put("weight", n.weight)
        is QuroBoxNode -> baseUiNode(n, "box")
            .put("children", uiChildren(n.children)).put("weight", n.weight)
        is QuroCardNode -> baseUiNode(n, "card")
            .put("children", uiChildren(n.children))
            .put("title", n.title).put("padding", n.padding).put("cornerRadius", n.cornerRadius)
            .put("onClick", n.onClick?.let { uiActionJson(it) } ?: JSONObject.NULL).put("weight", n.weight)
        is QuroPaneNode -> baseUiNode(n, "pane")
            .put("children", uiChildren(n.children))
            .put("direction", n.direction).put("spacing", n.spacing).put("padding", n.padding)
        is QuroTextNode -> baseUiNode(n, "text")
            .put("value", n.value).put("typography", n.typography).put("bold", n.bold).put("italic", n.italic)
            .put("color", n.color).put("size", n.size).put("maxLines", n.maxLines).put("align", n.align)
        is QuroImageNode -> baseUiNode(n, "image")
            .put("url", n.url).put("alt", n.alt).put("height", n.height).put("aspectRatio", n.aspectRatio).put("cornerRadius", n.cornerRadius)
        is QuroIconNode -> baseUiNode(n, "icon").put("name", n.name).put("size", n.size).put("tint", n.tint).put("description", n.description)
        is QuroBadgeNode -> baseUiNode(n, "badge").put("text", n.text).put("color", n.color).put("background", n.background)
        is QuroProgressNode -> baseUiNode(n, "progress").put("progress", n.progress).put("label", n.label)
        is QuroDividerNode -> baseUiNode(n, "divider").put("thickness", n.thickness).put("padding", n.padding)
        is QuroSpacerNode -> baseUiNode(n, "spacer").put("height", n.height).put("width", n.width)
        is QuroMarkdownNode -> baseUiNode(n, "markdown").put("value", n.value)
        is QuroVideoNode -> baseUiNode(n, "video").put("url", n.url).put("title", n.title)
        is QuroAudioNode -> baseUiNode(n, "audio").put("url", n.url).put("title", n.title)
        is QuroBrowserNode -> baseUiNode(n, "browser").put("url", n.url).put("height", n.height)
        is QuroHtmlNode -> baseUiNode(n, "html").put("html", n.html).put("height", n.height)
        is QuroCodeNode -> baseUiNode(n, "code").put("code", n.code).put("lang", n.lang).put("title", n.title).put("runnable", n.runnable)
        is QuroButtonNode -> baseUiNode(n, "button")
            .put("label", n.label).put("action", n.action?.let { uiActionJson(it) } ?: JSONObject.NULL)
            .put("variant", n.variant).put("enabled", n.enabled).put("icon", n.icon)
        is QuroTextInputNode -> baseUiNode(n, "text_input")
            .put("label", n.label).put("placeholder", n.placeholder).put("value", n.value)
            .put("multiline", n.multiline).put("lines", n.lines).put("inputType", n.inputType)
        is QuroCheckboxNode -> baseUiNode(n, "checkbox").put("label", n.label).put("checked", n.checked)
        is QuroSwitchNode -> baseUiNode(n, "switch").put("label", n.label).put("checked", n.checked)
        is QuroSelectNode -> baseUiNode(n, "select")
            .put("label", n.label).put("options", strArr(n.options)).put("selected", n.selected)
        is QuroSliderNode -> baseUiNode(n, "slider")
            .put("label", n.label).put("value", n.value).put("min", n.min).put("max", n.max).put("step", n.step)
        is QuroListNode -> baseUiNode(n, "list")
            .put("items", strArr(n.items))
            .put("itemTemplate", n.itemTemplate?.let { uiNodeJson(it) } ?: JSONObject.NULL)
            .put("maxHeight", n.maxHeight)
        is QuroTabsNode -> baseUiNode(n, "tabs").put("tabs", JSONArray().also { a ->
            n.tabs.forEach { t -> a.put(JSONObject().put("title", t.title).put("node", t.node?.let { uiNodeJson(it) } ?: JSONObject.NULL)) }
        })
        is QuroChipsNode -> baseUiNode(n, "chips")
            .put("items", strArr(n.items)).put("selected", n.selected)
            .put("onSelect", n.onSelect?.let { uiActionJson(it) } ?: JSONObject.NULL)
        is QuroMermaidNode -> baseUiNode(n, "mermaid").put("source", n.source).put("theme", n.theme)
        is QuroStatNode -> baseUiNode(n, "stat")
            .put("label", n.label).put("value", n.value).put("unit", n.unit).put("delta", n.delta).put("trend", n.trend)
        is QuroTableNode -> baseUiNode(n, "table")
            .put("headers", strArr(n.headers)).put("rows", strArrArr(n.rows)).put("caption", n.caption)
        is QuroAlertNode -> baseUiNode(n, "alert").put("severity", n.severity).put("title", n.title).put("text", n.text)
        is QuroRatingNode -> baseUiNode(n, "rating").put("label", n.label).put("value", n.value).put("max", n.max)
        is QuroGaugeNode -> baseUiNode(n, "gauge").put("label", n.label).put("progress", n.progress).put("color", n.color)
        is QuroCountdownNode -> baseUiNode(n, "countdown").put("label", n.label).put("targetEpochMs", n.targetEpochMs)
        is QuroStepsNode -> baseUiNode(n, "steps").put("steps", strArr(n.steps)).put("current", n.current)
        is QuroTimelineNode -> baseUiNode(n, "timeline").put("events", strArr(n.events))
        is QuroTodoNode -> baseUiNode(n, "todo").put("items", strArr(n.items))
        is QuroExpandableNode -> baseUiNode(n, "expandable")
            .put("title", n.title).put("body", n.body).put("expanded", n.expanded)
            .put("node", n.node?.let { uiNodeJson(it) } ?: JSONObject.NULL)
        is QuroPieNode -> baseUiNode(n, "pie").put("segments", strArr(n.segments)).put("title", n.title)
        is QuroCompareNode -> baseUiNode(n, "compare").put("items", strArr(n.items))
        is QuroRadarNode -> baseUiNode(n, "radar").put("axes", strArr(n.axes))
        is QuroHeatmapNode -> baseUiNode(n, "heatmap").put("cells", strArr(n.cells)).put("columns", n.columns)
        is QuroKanbanNode -> baseUiNode(n, "kanban").put("columns", strArr(n.columns))
        is QuroCarouselNode -> baseUiNode(n, "carousel").put("slides", JSONArray().also { a ->
            n.slides.forEach { s -> a.put(JSONObject().put("title", s.title).put("body", s.body).put("color", s.color).put("node", s.node?.let { uiNodeJson(it) } ?: JSONObject.NULL)) }
        })
        is QuroTimerNode -> baseUiNode(n, "timer").put("label", n.label).put("seconds", n.seconds)
        is QuroTagCloudNode -> baseUiNode(n, "tagcloud").put("tags", strArr(n.tags))
        is QuroAvatarGroupNode -> baseUiNode(n, "avatargroup").put("avatars", strArr(n.avatars))
        is QuroCounterNode -> baseUiNode(n, "counter").put("label", n.label).put("value", n.value).put("target", n.target).put("unit", n.unit)
        is QuroBreadcrumbNode -> baseUiNode(n, "breadcrumb").put("crumbs", strArr(n.crumbs))
        is QuroColorNode -> baseUiNode(n, "color").put("color", n.color).put("name", n.name)
        is QuroMediaNode -> baseUiNode(n, "media").put("url", n.url).put("mediaType", n.mediaType).put("title", n.title).put("height", n.height)
        is QuroFormNode -> baseUiNode(n, "form").put("fields", strArr(n.fields)).put("title", n.title)
        is QuroUnknownNode -> baseUiNode(n, "unknown")
            .put("unknownType", n.type)
            .put("action", n.action?.let { uiActionJson(it) } ?: JSONObject.NULL)
            .put("fields", JSONObject(n.fields))
            .put("children", uiChildren(n.children))
        is QuroGameBoardNode -> baseUiNode(n, "gameboard")
            .put("rows", n.rows).put("cols", n.cols)
            .put("cells", strArr(n.cells)).put("values", strArr(n.values))
            .put("cellColors", strArr(n.cellColors))
            .put("cellAction", n.cellAction?.let { uiActionJson(it) } ?: JSONObject.NULL)
            .put("clickable", n.clickable).put("cellSize", n.cellSize)
        is QuroGenUiNode -> baseUiNode(n, "genui").put("content", n.content).put("theme", n.theme)
    }

    private fun baseUiNode(n: QuroUiNode, type: String): JSONObject = JSONObject()
        .put("type", type)
        .put("id", n.id ?: JSONObject.NULL)
        .put("style", n.style?.let { uiStyleJson(it) } ?: JSONObject.NULL)

    private fun uiChildren(list: List<QuroUiNode>): JSONArray = JSONArray().also { a ->
        list.forEach { a.put(uiNodeJson(it)) }
    }

    private fun uiStyleJson(s: QuroUiStyle): JSONObject = JSONObject()
        .put("background", s.background?.let { bg ->
            when (bg) {
                is QuroUiBackground.Solid -> JSONObject().put("kind", "solid").put("color", bg.color)
                is QuroUiBackground.Gradient -> JSONObject()
                    .put("kind", "gradient")
                    .put("colors", strArr(bg.colors))
                    .put("direction", bg.direction)
                    .put("angle", bg.angle)
            }
        } ?: JSONObject.NULL)
        .put("borderColor", s.borderColor)
        .put("borderWidth", s.borderWidth)
        .put("borderRadius", s.borderRadius)
        .put("shadowElevation", s.shadowElevation)
        .put("shadowColor", s.shadowColor)
        .put("padding", s.padding?.let { uiEdgesJson(it) } ?: JSONObject.NULL)
        .put("margin", s.margin?.let { uiEdgesJson(it) } ?: JSONObject.NULL)
        .put("width", s.width?.let { uiSizeJson(it) } ?: JSONObject.NULL)
        .put("height", s.height?.let { uiSizeJson(it) } ?: JSONObject.NULL)
        .put("maxWidth", s.maxWidth)
        .put("maxHeight", s.maxHeight)
        .put("opacity", s.opacity)
        .put("align", s.align)
        .put("visible", s.visible)

    private fun uiEdgesJson(e: QuroUiEdges): JSONObject = JSONObject()
        .put("all", e.all).put("horizontal", e.horizontal).put("vertical", e.vertical)
        .put("top", e.top).put("bottom", e.bottom).put("start", e.start).put("end", e.end)

    private fun uiSizeJson(s: QuroUiSize): Any = when (s) {
        is QuroUiSize.Fixed -> JSONObject().put("kind", "fixed").put("dp", s.dp)
        is QuroUiSize.Fill -> JSONObject().put("kind", "fill").put("weight", s.weight)
        QuroUiSize.Wrap -> JSONObject().put("kind", "wrap")
    }

    /** 动态 UI 动作 → JSON。 */
    private fun uiActionJson(a: QuroUiAction): JSONObject = when (a) {
        is QuroCallbackAction -> JSONObject().put("type", "callback").put("event", a.event)
            .put("data", JSONObject(a.data)).put("collectFrom", strArr(a.collectFrom))
        is QuroToggleAction -> JSONObject().put("type", "toggle").put("targetId", a.targetId)
        is QuroOpenUrlAction -> JSONObject().put("type", "open_url").put("url", a.url)
        is QuroCopyAction -> JSONObject().put("type", "copy").put("text", a.text).put("label", a.label)
        is QuroToolCallAction -> JSONObject().put("type", "tool_call").put("tool", a.tool)
            .put("arguments", JSONObject(a.arguments)).put("collectFrom", strArr(a.collectFrom))
        is QuroSkillAction -> JSONObject().put("type", "skill").put("skill", a.skill).put("input", a.input)
            .put("collectFrom", strArr(a.collectFrom))
        is QuroOpenAppAction -> JSONObject().put("type", "open_app").put("packageName", a.packageName)
        is QuroOpenScreenAction -> JSONObject().put("type", "open_screen").put("target", a.target)
        is QuroRenderHtmlAction -> JSONObject().put("type", "render_html").put("html", a.html)
        is QuroRenderVisproAction -> JSONObject().put("type", "render_vispro").put("source", a.source)
        is QuroVisualPopupAction -> JSONObject().put("type", "visual_popup").put("title", a.title).put("content", a.content)
        is QuroVisualAskAction -> JSONObject().put("type", "visual_ask").put("prompt", a.prompt).put("options", strArr(a.options))
        is QuroZxWebCardAction -> JSONObject().put("type", "zx_web_card").put("component", a.component).put("title", a.title).put("dsl", a.dsl)
    }

    /**
     * 自研卡片渲染数据：把 ```quro-card 围栏 JSON 解析成 [CardSpec] 再序列化。
     * 复用 [parseCardSpec] —— 与原生 CardSurface 同一解析入口。
     * 解析失败返回 ok=false，前端显示结构化降级。
     */
    private fun selfCardJson(b: MsgBlock.SelfCard): JSONObject {
        val spec = runCatching { parseCardSpec(b.source) }.getOrNull()
            ?: return JSONObject().put("ok", false).put("raw", b.source.take(600))
        return JSONObject()
            .put("ok", true)
            .put("id", spec.id)
            .put("type", spec.type)
            .put("version", spec.version)
            .put("renderHint", spec.renderHint)
            .put("layout", spec.layout?.let { layoutNodeJson(it) } ?: JSONObject.NULL)
            .put("data", cardDataJson(spec.data))
            .put("actions", JSONArray().also { a -> spec.actions.forEach { a.put(actionJson(it)) } })
            .put("style", styleTokenJson(spec.style))
            .put("a11y", a11yJson(spec.a11y))
    }

    private fun layoutNodeJson(n: LayoutNode): JSONObject = JSONObject()
        .put("type", n.type)
        .put("id", n.id)
        .put("weight", n.weight)
        .put("widthDp", n.widthDp)
        .put("heightDp", n.heightDp)
        .put("flex", n.flex)
        .put("style", styleTokenJson(n.style))
        .put("children", JSONArray().also { a -> n.children.forEach { a.put(layoutNodeJson(it)) } })
        .put("props", JSONObject(n.props))

    private fun cardDataJson(d: CardData): JSONObject = when (d) {
        is CardData.Empty -> JSONObject().put("kind", "empty")
        is CardData.Chart -> JSONObject()
            .put("kind", "chart")
            .put("chartType", d.chartType)
            .put("series", JSONArray().also { a ->
                d.series.forEach { s ->
                    a.put(JSONObject()
                        .put("name", s.name)
                        .put("color", s.color.name)
                        .put("points", fArr(s.points))
                        .put("labels", strArr(s.labels)))
                }
            })
            .put("axis", JSONObject()
                .put("showX", d.axis.showX).put("showY", d.axis.showY)
                .put("yMin", d.axis.yMin).put("yMax", d.axis.yMax))
        is CardData.Media -> JSONObject()
            .put("kind", "media")
            .put("mediaType", d.mediaType)
            .put("rows", strArrArr(d.rows))
            .put("headers", strArr(d.headers))
            .put("code", d.code)
            .put("lang", d.lang)
            .put("images", strArr(d.images))
            .put("items", JSONArray().also { a ->
                d.items.forEach { it -> a.put(JSONObject().put("time", it.time).put("text", it.text).put("done", it.done)) }
            })
        is CardData.Form -> JSONObject()
            .put("kind", "form")
            .put("formType", d.formType)
            .put("buttons", JSONArray().also { a ->
                d.buttons.forEach { b -> a.put(JSONObject().put("label", b.label).put("variant", b.variant).put("action", actionJson(b.action))) }
            })
            .put("fields", JSONArray().also { a ->
                d.fields.forEach { f -> a.put(JSONObject().put("key", f.key).put("label", f.label).put("placeholder", f.placeholder).put("value", f.value)) }
            })
            .put("min", d.min).put("max", d.max).put("value", d.value)
            .put("options", strArr(d.options)).put("selected", strArr(d.selected))
        is CardData.Status -> JSONObject()
            .put("kind", "status")
            .put("statusType", d.statusType)
            .put("text", d.text)
            .put("progress", d.progress)
            .put("retryable", d.retryable)
            .put("reason", d.reason)
    }

    private fun actionJson(a: Action): JSONObject = JSONObject()
        .put("type", a.type)
        .put("name", a.name)
        .put("url", a.url)
        .put("appName", a.appName)
        .put("payload", JSONObject(a.payload))

    private fun styleTokenJson(s: StyleToken): JSONObject = JSONObject()
        .put("bg", s.bg.name)
        .put("fg", s.fg.name)
        .put("accent", s.accent.name)
        .put("cornerDp", s.cornerDp)
        .put("paddingDp", s.paddingDp)
        .put("fontSizeSp", s.fontSizeSp)
        .put("fontWeight", s.fontWeight)

    private fun a11yJson(a: A11y): JSONObject = JSONObject()
        .put("role", a.role)
        .put("label", a.label)
        .put("hint", a.hint)
        .put("state", a.state)

    /** MiniApp 渲染数据：完整 HTML。 */
    private fun miniappJson(b: MsgBlock.MiniApp): JSONObject =
        JSONObject().put("html", b.html)

    /**
     * 数据型卡片（表格/饼图/图表/热力图/雷达）序列化后为空时返回 true。
     * 与原生 `cardHasNoData` 等价：避免流式未写完/空模板的组件 JSON 闪「（无数据）」。
     * 判断基于 [serializeCard] 的 JSON 字段，不依赖 ChatScreen 的 private 函数。
     */
    private fun serializedCardHasNoData(o: JSONObject): Boolean = when (o.optString("cardType")) {
        "table" -> o.optJSONArray("headers")?.length() == 0 && o.optJSONArray("rows")?.length() == 0
        "pie" -> o.optJSONArray("segments")?.length() == 0
        "ranking" -> o.optJSONArray("items")?.length() == 0
        "chart" -> o.optJSONArray("series")?.length() == 0
        "heatmap" -> o.optJSONArray("values")?.length() == 0
        "radar" -> o.optJSONArray("axes")?.length() == 0
        else -> false
    }

    /* ── 数组小工具 ── */
    private fun strArr(list: List<String>): JSONArray = JSONArray().also { a -> list.forEach { a.put(it) } }
    private fun strArrArr(list: List<List<String>>): JSONArray = JSONArray().also { a ->
        list.forEach { row -> a.put(strArr(row)) }
    }
    private fun dblArr(list: List<Double>): JSONArray = JSONArray().also { a -> list.forEach { a.put(it) } }
    private fun intArr(list: List<Int>): JSONArray = JSONArray().also { a -> list.forEach { a.put(it) } }
    private fun fArr(list: List<Float>): JSONArray = JSONArray().also { a -> list.forEach { a.put(it) } }

    private fun uiBlocks(m: QuroMessage): JSONArray? {
        val calls = m.toolCalls?.takeIf { it.isNotEmpty() } ?: return null
        val arr = JSONArray()
        calls.forEach { tc ->
            if (tc.name != ZX_TOOL) return@forEach
            val spec = runCatching { zxSpecOf(tc.arguments) }.getOrNull() ?: return@forEach
            arr.put(
                JSONObject()
                    .put("component", spec.component)
                    .put("dsl", spec.dsl)
            )
        }
        return if (arr.length() > 0) arr else null
    }
}
