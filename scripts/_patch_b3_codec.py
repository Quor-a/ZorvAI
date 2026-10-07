# -*- coding: utf-8 -*-
"""第三批 9 种组件的 6 处接线（本次脚本负责其中 2 处：cardType 穷尽表 + encode）。

接线纪律：每个新组件必须同步 6 处，漏一处不会编译报错（除两个穷尽 when），
只会静默失效 —— 能解析、能渲染，但存档读回来变另一张卡。
"""
import io, os, sys

ROOT = "app/src/main/java/com/ai/assistance/quro/core/cards/"


def patch(path, anchor, insert, tag):
    p = ROOT + path
    src = io.open(p, encoding="utf-8").read()
    if insert.strip().splitlines()[1].strip() in src and False:
        print("SKIP %s: 已存在" % tag)
        return
    if src.count(anchor) != 1:
        print("ABORT %s: 锚点命中 %d 次" % (tag, src.count(anchor)))
        sys.exit(1)
    src = src.replace(anchor, anchor + insert, 1)
    tmp = p + ".tmp"
    with io.open(tmp, "w", encoding="utf-8", newline="\n") as f:
        f.write(src)
    os.replace(tmp, p)
    print("OK %s" % tag)


# ---- 1) serializeCard 的 cardType 穷尽表 ----
patch(
    "QuroChatCard.kt",
    '        is BarcodeCard -> "barcode"\n',
    '\n'
    '        // ── 第三批（v1400-b3）：AI 征询决策 + 可视化进阶 ──\n'
    '        is DecisionCard -> "decision"\n'
    '        is ConfirmCard -> "confirm"\n'
    '        is SankeyCard -> "sankey"\n'
    '        is FunnelCard -> "funnel"\n'
    '        is WaterfallCard -> "waterfall"\n'
    '        is QuadrantCard -> "quadrant"\n'
    '        is MatrixCard -> "matrix"\n'
    '        is FeedCard -> "feed"\n'
    '        is GraphCard -> "graph"\n'
    '        is SectionCard -> "section"\n',
    "cardType 穷尽表",
)

# ---- 2) CardCodec.encode 显式分支 ----
patch(
    "CardCodec.kt",
    """            is BarcodeCard -> {
                o.put("code", card.code); o.put("format", card.format); o.put("caption", card.caption)
            }
""",
    """            is DecisionCard -> {
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
                        put("label", st.label); put("value", st.value); put("hint", st.hint)
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
""",
    "CardCodec.encode",
)