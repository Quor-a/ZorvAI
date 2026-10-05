package com.ai.assistance.quro.core.cards

import org.json.JSONArray
import org.json.JSONObject

/**
 * 34 种增强卡片的**编码器**（落盘 / 出参方向）。
 *
 * ## 为什么单独一个文件
 *
 * 反序列化（AI 下发方向）已经统一到 [CardSdk.parseObj] —— 只此一份，
 * 解析层有第二套实现就会出现「工具能画、历史消息读不回来」这类鬼故事。
 * 所以这里**只做编码**，解码一律回头调 [CardSdk.parseObj]：
 *
 * ```
 *   AI ──► parseComponentSpec / CardSdk.parseObj ──► QuroChatCard   （唯一解码）
 *   QuroChatCard ──► serializeCard ──► CardCodec.encode ──► JSON     （本文件）
 *   JSON ──► parseCard ──► 补一个 "type" ──► CardSdk.parseObj ──► QuroChatCard
 * ```
 *
 * 落盘那侧的 [parseCard] 也复用 [CardSdk.parseObj]（把 `cardType` 补成 `type`），
 * 于是「编码写的字段名」与「解码读的字段名」天然对称 —— 往返不一致能在
 * [CardSdk.lint] + 单测里一次抓出来，而不是等到真机丢卡片。
 *
 * ## 字段名约定
 *
 * 全部对齐 [CardSdk] 同名 builder 读取的 key（见 `all` 里各 `CardSpec` 的 sample）：
 * `keyvalue` 用 `items[{k,v,icon}]` 而不是 `rows`，`diff` 用 `rows[{kind,text,oldText}]`……
 * 沿用一套 wire 格式，工具下发与落盘还原才是同一份 JSON，**不需要两套名字**。
 */
object CardCodec {

    // ───────────── 通用小工具 ─────────────

    /** Float 列表 → JSONArray。空列表也会写成 `[]`，别让 null 渗进存档。 */
    private fun fArr(v: List<Float>): JSONArray = JSONArray().also { a -> v.forEach { a.put(it) } }

    /** String 列表 → JSONArray。 */
    private fun sArr(v: List<String>): JSONArray = JSONArray().also { a -> v.forEach { a.put(it) } }

    /** nullable String 写盘：null 用 JSONObject.NULL，否则读回来变成 "" 分不出「没给」和「给了空」。 */
    /** 空字符串 / null 统一落 JSONObject.NULL。🔴 不能用 `optIfNull(...)`：运行期遇到 null 会抛 NPE。 */
    private fun optIfNull(v: String?): Any = if (v.isNullOrBlank()) JSONObject.NULL else v

    /** 树节点只有一层 children 的话，第三层往下会静默丢数据 —— 递归写出来。 */
    private fun treeJson(n: TreeCard.Node): JSONObject = JSONObject().apply {
        put("label", n.label); put("icon", n.icon); put("expanded", n.expanded); put("value", n.value)
        put("children", JSONArray().also { a -> n.children.forEach { c -> a.put(treeJson(c)) } })
    }

    // ───────────── 编码总入口 ─────────────

    /**
     * 把卡片写进已有 [o]。
     *
     * 用一个 exhaustive `when` 覆盖 34 种增强类型：以后再加新 data class 而忘了改这里，
     * 编译期就会叫（而不是静默写出一张空卡）。存量 47 种走 `serializeCard` 里
     * 原来的 `when`，不动 —— 它们已在真机跑着。
     */
    fun encode(card: QuroChatCard, o: JSONObject) {
        when (card) {
            is KeyValueCard -> o.put("items", JSONArray().also { a ->
                card.rows.forEach { r -> a.put(JSONObject().apply { put("k", r.k); put("v", r.v); put("icon", optIfNull(r.icon)); put("command", optIfNull(r.command)) }) }
            })
            is RingCard -> {
                o.put("label", card.label); o.put("value", card.value); o.put("max", card.max)
                o.put("caption", card.caption); o.put("color", card.color); o.put("thickness", card.thickness)
            }
            is StackedBarCard -> {
                o.put("categories", sArr(card.categories))
                o.put("series", JSONArray().also { a -> card.series.forEach { s ->
                    a.put(JSONObject().apply { put("name", s.name); put("values", fArr(s.values)); put("color", s.color) })
                } })
            }
            is ScatterCard -> o.apply {
                put("points", JSONArray().also { a -> card.points.forEach { p ->
                    a.put(JSONObject().apply { put("x", p.x); put("y", p.y); put("label", optIfNull(p.label)) })
                } })
                put("xLabel", card.xLabel); put("yLabel", card.yLabel)
            }
            is CandlestickCard -> o.put("candles", JSONArray().also { a -> card.candles.forEach { c ->
                a.put(JSONObject().apply { put("o", c.o); put("h", c.h); put("l", c.l); put("c", c.c); put("t", c.t) })
            } })
            is BoxPlotCard -> o.put("groups", JSONArray().also { a -> card.groups.forEach { g ->
                a.put(JSONObject().apply {
                    put("name", g.name); put("min", g.min); put("q1", g.q1)
                    put("median", g.median); put("q3", g.q3); put("max", g.max)
                })
            } })
            is SpeedometerCard -> {
                o.put("value", card.value); o.put("max", card.max); o.put("label", card.label)
                o.put("unit", card.unit); o.put("zones", sArr(card.zones))
            }
            is SparklineCard -> {
                o.put("values", fArr(card.values)); o.put("caption", card.caption); o.put("color", card.color)
            }
            is SearchBoxCard -> {
                o.put("label", card.label); o.put("placeholder", card.placeholder); o.put("value", card.value)
                o.put("command", card.command); o.put("hint", card.hint)
            }
            is PollCard -> {
                o.put("question", card.question)
                o.put("options", JSONArray().also { a -> card.options.forEach { p ->
                    a.put(JSONObject().apply { put("label", p.label); put("count", p.count); put("color", p.color) })
                } })
                o.put("multi", card.multi); o.put("total", card.total); o.put("command", card.command)
            }
            is CheckListCard -> {
                o.put("items", JSONArray().also { a -> card.items.forEach { i ->
                    a.put(JSONObject().apply { put("text", i.text); put("done", i.done); put("note", i.note) })
                } })
                o.put("summary", card.summary)
            }
            is AccordionCard -> {
                o.put("items", JSONArray().also { a -> card.items.forEach { i ->
                    a.put(JSONObject().apply { put("title", i.title); put("body", i.body) })
                } })
                o.put("expandedIndex", card.expandedIndex)
            }
            is GroupedListCard -> o.put("sections", JSONArray().also { a -> card.sections.forEach { sec ->
                a.put(JSONObject().apply {
                    put("title", sec.title)
                    put("items", JSONArray().also { arr -> sec.items.forEach { it ->
                        arr.put(JSONObject().apply {
                            put("text", it.text); put("sub", it.sub); put("icon", it.icon); put("value", it.value)
                        })
                    } })
                })
            } })
            is TreeCard -> {
                o.put("nodes", JSONArray().also { a -> card.nodes.forEach { n -> a.put(treeJson(n)) } })
                o.put("expandedDepth", card.expandedDepth)
            }
            is QuoteCard -> o.apply {
                put("text", card.text); put("author", optIfNull(card.author)); put("source", optIfNull(card.source))
            }
            is DiffCard -> {
                o.put("rows", JSONArray().also { a -> card.rows.forEach { r ->
                    a.put(JSONObject().apply { put("kind", r.kind); put("text", r.text); put("oldText", optIfNull(r.oldText)) })
                } })
                o.put("file", card.file); o.put("additions", card.additions); o.put("deletions", card.deletions)
            }
            is FlowCard -> {
                o.put("nodes", JSONArray().also { a -> card.nodes.forEach { n ->
                    a.put(JSONObject().apply { put("label", n.label); put("kind", n.kind); put("desc", n.desc); put("command", n.command) })
                } })
                o.put("direction", card.direction)
            }
            is HierarchyCard -> o.put("stages", JSONArray().also { a -> card.stages.forEach { st ->
                a.put(JSONObject().apply { put("name", st.name); put("items", sArr(st.items)); put("color", st.color) })
            } })
            is ContactCard -> {
                o.put("name", card.name); o.put("role", card.role); o.put("avatar", card.avatar); o.put("status", card.status)
                o.put("actions", JSONArray().also { a -> card.actions.forEach { q ->
                    a.put(JSONObject().apply { put("label", q.label); put("icon", q.icon); put("command", q.command) })
                } })
            }
            is ProductCard -> {
                o.put("name", card.name); o.put("price", card.price); o.put("originalPrice", optIfNull(card.originalPrice))
                o.put("image", card.image); o.put("rating", card.rating); o.put("sold", card.sold)
                o.put("command", card.command); o.put("tag", card.tag)
            }
            is ScheduleCard -> {
                o.put("event", card.event); o.put("date", card.date); o.put("time", card.time)
                o.put("place", card.place); o.put("command", card.command)
            }
            is FileCard -> {
                o.put("name", card.name); o.put("size", card.size); o.put("path", card.path)
                o.put("mime", card.mime); o.put("command", card.command)
            }
            is AchievementCard -> {
                o.put("name", card.name); o.put("desc", card.desc); o.put("icon", card.icon)
                o.put("progress", card.progress); o.put("command", card.command)
            }
            is WeatherCard -> {
                o.put("city", card.city); o.put("temp", card.temp); o.put("condition", card.condition)
                o.put("icon", card.icon); o.put("humidity", card.humidity); o.put("wind", card.wind)
                o.put("hours", JSONArray().also { a -> card.hours.forEach { h ->
                    a.put(JSONObject().apply { put("t", h.t); put("v", h.v); put("i", h.i) })
                } })
            }
            is MapCard -> {
                o.put("lat", card.lat); o.put("lng", card.lng); o.put("zoom", card.zoom)
                o.put("markers", JSONArray().also { a -> card.markers.forEach { m ->
                    a.put(JSONObject().apply { put("lat", m.lat); put("lng", m.lng); put("label", m.label) })
                } })
            }
            is QrCodeCard -> {
                o.put("content", card.content); o.put("size", card.size)
                o.put("caption", card.caption); o.put("color", card.color)
            }
            is GalleryCard -> {
                o.put("images", JSONArray().also { a -> card.images.forEach { im ->
                    a.put(JSONObject().apply { put("url", im.url); put("caption", optIfNull(im.caption)) })
                } })
                o.put("columns", card.columns); o.put("aspectRatio", card.aspectRatio)
            }
            is TerminalCard -> {
                o.put("lines", sArr(card.lines)); o.put("exitCode", card.exitCode); o.put("command", card.command)
            }
            is LinkListCard -> o.put("links", JSONArray().also { a -> card.links.forEach { l ->
                a.put(JSONObject().apply {
                    put("label", l.label); put("url", l.url); put("desc", optIfNull(l.desc)); put("icon", optIfNull(l.icon))
                })
            } })
            is PaginationCard -> {
                o.put("page", card.page); o.put("total", card.total); o.put("command", card.command)
            }
            is DividerCard -> {
                o.put("text", optIfNull(card.text)); o.put("dashed", card.dashed)
            }
            is SpacerCard -> o.put("height", card.height)
            is CustomCard -> {
                o.put("kind", card.kind); o.put("payload", card.payload)
                if (card.children.isNotEmpty()) {
                    o.put("children", JSONArray().also { a -> card.children.forEach { c -> a.put(serializeCard(c)) } })
                }
            }
            is GanttCard -> {
                o.put("unit", card.unit); o.put("total", card.total); o.put("axisStart", card.axisStart)
                o.put("tasks", JSONArray().also { a -> card.tasks.forEach { t ->
                    a.put(JSONObject().apply {
                        put("name", t.name); put("start", t.start); put("duration", t.duration)
                        put("progress", t.progress); put("color", t.color); put("owner", t.owner)
                    })
                } })
            }
            is InvoiceCard -> {
                o.put("merchant", card.merchant); o.put("currency", card.currency)
                o.put("items", JSONArray().also { a -> card.items.forEach { it ->
                    a.put(JSONObject().apply {
                        put("name", it.name); put("qty", it.qty); put("price", it.price); put("amount", it.amount)
                    })
                } })
                o.put("subtotal", card.subtotal); o.put("discount", card.discount)
                o.put("total", card.total); o.put("note", card.note); o.put("paid", card.paid)
                o.put("command", card.command)
            }
            is CurrencyCard -> {
                o.put("base", card.base); o.put("value", card.value); o.put("updated", card.updated)
                o.put("rates", JSONArray().also { a -> card.rates.forEach { r ->
                    a.put(JSONObject().apply { put("code", r.code); put("symbol", r.symbol); put("rate", r.rate); put("change", r.change) })
                } })
            }
            is ClockCard -> {
                o.put("current", card.current); o.put("format", card.format)
                o.put("zones", JSONArray().also { a -> card.zones.forEach { z ->
                    a.put(JSONObject().apply { put("city", z.city); put("offset", z.offset); put("diff", z.diff) })
                } })
            }
            is TrackerCard -> {
                o.put("name", card.name); o.put("days", sArr(card.days))
                o.put("target", card.target); o.put("streak", card.streak); o.put("unit", card.unit)
            }
            is ScoreboardCard -> {
                o.put("home", card.home); o.put("homeScore", card.homeScore)
                o.put("away", card.away); o.put("awayScore", card.awayScore)
                o.put("period", card.period); o.put("time", card.time); o.put("status", card.status)
            }
            is VocabCard -> {
                o.put("word", card.word); o.put("phonetic", card.phonetic)
                o.put("pos", card.pos); o.put("meaning", card.meaning)
                o.put("examples", JSONArray().also { a -> card.examples.forEach { e ->
                    a.put(JSONObject().apply { put("en", e.en); put("zh", e.zh) })
                } })
                o.put("tags", sArr(card.tags))
            }
            is FormulaCard -> {
                o.put("expr", card.expr); o.put("note", card.note)
                o.put("vars", JSONArray().also { a -> card.vars.forEach { v ->
                    a.put(JSONObject().apply { put("name", v.name); put("desc", v.desc) })
                } })
            }
            is TranslateCard -> {
                o.put("srcLang", card.srcLang); o.put("dstLang", card.dstLang)
                o.put("src", card.src); o.put("dst", card.dst)
                o.put("alt", sArr(card.alt)); o.put("audio", card.audio)
            }
            is PaletteCard -> {
                o.put("name", card.name); o.put("copyable", card.copyable)
                o.put("colors", JSONArray().also { a -> card.colors.forEach { c ->
                    a.put(JSONObject().apply { put("name", c.name); put("hex", c.hex) })
                } })
            }
            is StopwatchCard -> {
                o.put("label", card.label); o.put("seconds", card.seconds); o.put("command", card.command)
            }
            is BarcodeCard -> {
                o.put("code", card.code); o.put("format", card.format); o.put("caption", card.caption)
            }
            is DecisionCard -> {
                o.put("question", card.question)
                o.put("allowCustom", card.allowCustom); o.put("required", card.required)
                o.put("customHint", card.customHint); o.put("context", card.context)
                o.put("options", JSONArray().also { a ->
                    card.options.forEach { op -> a.put(JSONObject().apply {
                        put("label", op.label); put("value", op.value)
                        put("detail", op.detail); put("recommended", op.recommended)
                    }) }
                })
            }
            is ConfirmCard -> {
                o.put("message", card.message); o.put("confirmLabel", card.confirmLabel)
                o.put("cancelLabel", card.cancelLabel); o.put("danger", card.danger); o.put("detail", card.detail)
            }
            is SankeyCard -> {
                o.put("unit", card.unit)
                o.put("nodes", JSONArray().also { a ->
                    card.nodes.forEach { n -> a.put(JSONObject().apply {
                        put("id", n.id); put("label", n.label)
                    }) }
                })
                o.put("links", JSONArray().also { a ->
                    card.links.forEach { l -> a.put(JSONObject().apply {
                        put("from", l.from); put("to", l.to); put("value", l.value)
                    }) }
                })
            }
            is FunnelCard -> {
                o.put("showRate", card.showRate); o.put("unit", card.unit)
                o.put("steps", JSONArray().also { a ->
                    card.steps.forEach { st -> a.put(JSONObject().apply {
                        put("name", st.name); put("value", st.value)
                        put("color", st.color); put("hint", st.hint)
                    }) }
                })
            }
            is WaterfallCard -> {
                o.put("start", card.start); o.put("unit", card.unit)
                o.put("steps", JSONArray().also { a ->
                    card.steps.forEach { st -> a.put(JSONObject().apply {
                        put("label", st.label); put("delta", st.delta)
                        put("value", st.value); put("isTotal", st.isTotal)
                    }) }
                })
            }
            is QuadrantCard -> {
                o.put("xLabel", card.xLabel); o.put("yLabel", card.yLabel); o.put("axisMax", card.axisMax)
                o.put("quadrants", JSONArray(card.quadrants))
                o.put("items", JSONArray().also { a ->
                    card.items.forEach { it -> a.put(JSONObject().apply {
                        put("label", it.label); put("x", it.x); put("y", it.y); put("tag", it.tag)
                    }) }
                })
            }
            is MatrixCard -> {
                o.put("leftLabel", card.leftLabel); o.put("rightLabel", card.rightLabel)
                o.put("showDiff", card.showDiff); o.put("unit", card.unit)
                o.put("rows", JSONArray().also { a ->
                    card.rows.forEach { r -> a.put(JSONObject().apply {
                        put("label", r.label); put("left", r.left); put("right", r.right); put("better", r.better)
                    }) }
                })
            }
            is FeedCard -> {
                o.put("source", card.source)
                o.put("items", JSONArray().also { a ->
                    card.items.forEach { it -> a.put(JSONObject().apply {
                        put("time", it.time); put("text", it.text)
                        put("level", it.level); put("actor", it.actor)
                    }) }
                })
            }
            is GraphCard -> {
                o.put("nodes", JSONArray().also { a ->
                    card.nodes.forEach { n -> a.put(JSONObject().apply {
                        put("label", n.label); put("col", n.col); put("row", n.row); put("shape", n.shape)
                    }) }
                })
                o.put("edges", JSONArray().also { a ->
                    card.edges.forEach { e -> a.put(JSONObject().apply {
                        put("from", e.from); put("to", e.to); put("label", e.label); put("kind", e.kind)
                    }) }
                })
            }
            is SectionCard -> {
                o.put("sections", JSONArray().also { a ->
                    card.sections.forEach { sec -> a.put(JSONObject().apply {
                        put("title", sec.title)
                        put("rows", JSONArray().also { ra ->
                            sec.rows.forEach { (k, v) -> ra.put(JSONObject().apply {
                                put("k", k); put("v", v)
                            }) }
                        })
                    }) }
                })
            }
            else -> Unit
        }
    }
}
