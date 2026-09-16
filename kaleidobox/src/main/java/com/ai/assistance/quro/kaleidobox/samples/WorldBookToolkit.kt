package com.ai.assistance.quro.kaleidobox.samples

import com.ai.assistance.quro.kaleidobox.core.engine.InvokeContext
import com.ai.assistance.quro.kaleidobox.core.engine.KaleidoToolkit
import com.ai.assistance.quro.kaleidobox.core.engine.ToolkitHost
import com.ai.assistance.quro.kaleidobox.core.model.KValue
import com.ai.assistance.quro.kaleidobox.core.ui.*
import com.ai.assistance.quro.kaleidobox.core.util.Json

/**
 * 真能力插件：世界书（World Info / Lorebook，完整 app）。
 *
 * 世界书的本质是**按关键词把条目内容动态注入 AI 提示词**，不是"卡片仓库"。
 * 本插件提供：
 *   - 条目列表（搜索、启用开关、删除）；
 *   - 完整条目编辑：主/次关键词、触发逻辑(AND_ANY/AND_ALL/NOT_ANY/NOT_ALL)、正文、
 *     插入顺序、插入深度、权重、触发概率、包含组、常驻、区分大小写、正则关键词；
 *   - 「测试扫描」：输入一段文本，实时看到哪些条目被激活、以及最终注入的上下文；
 *   - 导入 / 导出（走剪贴板）。
 *
 * 条目持久化在包私有 KV（[data.kv]）；AI 助手插件经宿主 [worldbook.entries] 读取后注入对话。
 */
class WorldBookToolkit : KaleidoToolkit {

    private var host: ToolkitHost? = null

    override fun attach(host: ToolkitHost) { this.host = host }

    private val kvKey = "worldbook_entries"
    private val logics = listOf("AND_ANY", "AND_ALL", "NOT_ANY", "NOT_ALL")

    override fun invoke(fn: String, args: KValue, ctx: InvokeContext): KValue = when (fn) {
        "about_worldbook" -> KValue.Str("世界书：按关键词把条目内容动态注入 AI 提示词（Lorebook），含测试扫描与注入预览。")
        "render" -> KValue.Str(Json.write(UiCodec.encode(buildUi(args))))
        "onAction" -> handleAction(args)
        else -> KValue.fail("E_NO_FN", "未知函数: $fn")
    }

    // ---------------------------------------------------------------- 持久化

    private fun loadFromKv(): List<LoreEntry> {
        val r = host?.call("data.kv", KValue.obj("op" to "get", "key" to kvKey)) ?: KValue.Null
        return LorebookEngine.parse((r as? KValue.Str)?.value)
    }

    private fun persist(list: List<LoreEntry>) {
        host?.call(
            "data.kv",
            KValue.obj("op" to "set", "key" to kvKey, "value" to LorebookEngine.serialize(list)),
        )
    }

    // ---------------------------------------------------------------- 状态工具

    private fun entriesOf(state: Map<String, Any?>): List<LoreEntry> {
        val raw = state["entries"] as? List<*> ?: return loadFromKv()
        return raw.mapNotNull { it as? Map<*, *> }.map { LorebookEngine.fromMap(it) }
    }

    private fun toState(list: List<LoreEntry>): List<Map<String, Any?>> = list.map { it.toMap() }

    private fun s(state: Map<String, Any?>, k: String) = (state[k] as? String) ?: ""
    private fun b(state: Map<String, Any?>, k: String) = state[k] == true

    private fun splitKeys(v: String): List<String> =
        v.split(",", "，").map { it.trim() }.filter { it.isNotEmpty() }

    // ---------------------------------------------------------------- UI

    private fun buildUi(args: KValue): UiNode {
        val state = SamplesUi.readState(args)
        val tab = s(state, "tab").ifBlank { "list" }
        val query = s(state, "query")
        val entries = entriesOf(state)
        val shown = entries.filter {
            query.isBlank() || it.comment.contains(query, true) ||
                it.content.contains(query, true) || it.keys.any { k -> k.contains(query, true) }
        }
        val enabledCount = entries.count { it.enabled }

        val content = buildList<UiNode> {
            add(SamplesUi.section("sec", "世界书 · 关键词动态注入（Lorebook）"))
            add(tabRow(tab))
            when (tab) {
                "edit" -> addAll(editTab(state))
                "test" -> addAll(testTab(state, entries))
                else -> addAll(listTab(state, entries, shown, enabledCount, query))
            }
        }
        return SamplesUi.scrollPage("root", content = content)
    }

    private fun tabRow(tab: String): UiNode = UiNode.Row(
        "tabRow",
        modifier = Mod(padding = Edges(0, 0, 0, 8)),
        children = listOf(
            SamplesUi.button("tab_list", "条目列表",
                variant = if (tab == "list") UiNode.Button.Variant.FILLED else UiNode.Button.Variant.TONAL,
                modifier = Mod(weight = 1f)),
            SamplesUi.button("tab_edit", "编辑",
                variant = if (tab == "edit") UiNode.Button.Variant.FILLED else UiNode.Button.Variant.TONAL,
                modifier = Mod(weight = 1f, padding = Edges(0, 0, 0, 6))),
            SamplesUi.button("tab_test", "测试扫描",
                variant = if (tab == "test") UiNode.Button.Variant.FILLED else UiNode.Button.Variant.TONAL,
                modifier = Mod(weight = 1f, padding = Edges(0, 0, 0, 8))),
        ),
    )

    // ---- 列表 ----

    private fun listTab(
        state: Map<String, Any?>,
        all: List<LoreEntry>,
        shown: List<LoreEntry>,
        enabledCount: Int,
        query: String,
    ): List<UiNode> = buildList {
        add(SamplesUi.field("query", "搜索（备注 / 关键词 / 正文）", Bound.Ref("query"), Action.of("query"), minH = 52))
        add(SamplesUi.hint("c", "共 ${all.size} 条 · 启用 $enabledCount 条 · 显示 ${shown.size} 条"))
        add(SamplesUi.primaryAction("new", "新建条目"))
        add(SamplesUi.secondaryAction("export", "导出到剪贴板"))
        add(SamplesUi.secondaryAction("importClip", "从剪贴板导入"))
        if (shown.isEmpty()) add(SamplesUi.hint("empty", "（还没有条目，点「新建条目」开始）"))
        shown.forEach { e ->
            val head = "【${if (e.constant) "常驻" else e.keys.joinToString("/").ifBlank { "无关键词" }}】" +
                (e.comment.ifBlank { "(无备注)" })
            val meta = buildString {
                append("逻辑 ${e.logic} · 顺序 ${e.order} · 权重 ${e.weight} · 概率 ${e.probability}%")
                if (e.group.isNotBlank()) append(" · 组 ${e.group}")
                append(if (e.enabled) " · 已启用" else " · 已停用")
            }
            add(
                UiNode.Column(
                    "e_${e.id}",
                    modifier = Mod(padding = Edges(0, 0, 0, 10)),
                    children = listOf(
                        SamplesUi.codeBlock("blk_${e.id}", listOf(head, meta, e.content), "", minH = 92),
                        UiNode.Row(
                            "r_${e.id}",
                            children = listOf(
                                UiNode.Button("ed_${e.id}", Bound.Lit("编辑"), Action.of("edit", "id" to e.id),
                                    variant = UiNode.Button.Variant.TONAL, modifier = Mod(weight = 1f)),
                                UiNode.Button("tg_${e.id}", Bound.Lit(if (e.enabled) "停用" else "启用"),
                                    Action.of("toggle", "id" to e.id),
                                    variant = UiNode.Button.Variant.TONAL,
                                    modifier = Mod(weight = 1f, padding = Edges(0, 0, 0, 6))),
                                UiNode.Button("dl_${e.id}", Bound.Lit("删除"), Action.of("del", "id" to e.id),
                                    variant = UiNode.Button.Variant.TONAL,
                                    modifier = Mod(weight = 1f, padding = Edges(0, 0, 0, 8))),
                            ),
                        ),
                    ),
                )
            )
        }
    }

    // ---- 编辑 ----

    private fun editTab(state: Map<String, Any?>): List<UiNode> = buildList {
        val editing = s(state, "editingId")
        add(
            UiNode.Text(
                "eh", Bound.Lit(if (editing.isBlank()) "新建条目" else "编辑条目"),
                TypeStyle.LABEL, color = SamplesUi.C.primary,
                modifier = Mod(padding = Edges(0, 0, 0, 8)),
            )
        )
        add(SamplesUi.field("dComment", "备注 / 标题（不参与触发）", Bound.Ref("dComment"), Action.of("dComment"), minH = 48))
        add(SamplesUi.field("dKeys", "主关键词（逗号分隔，命中任一即触发）", Bound.Ref("dKeys"), Action.of("dKeys"), minH = 48))
        add(SamplesUi.field("dSec", "次关键词（配合逻辑，可留空）", Bound.Ref("dSec"), Action.of("dSec"), minH = 48))
        add(SamplesUi.label("lg", "触发逻辑"))
        add(logicRow(s(state, "dLogic")))
        add(SamplesUi.field("dContent", "正文（命中后注入的内容）", Bound.Ref("dContent"), Action.of("dContent"), singleLine = false, minH = 96))
        add(
            UiNode.Row(
                "nums",
                children = listOf(
                    SamplesUi.field("dOrder", "顺序", Bound.Ref("dOrder"), Action.of("dOrder"), minH = 48),
                    SamplesUi.field("dWeight", "权重", Bound.Ref("dWeight"), Action.of("dWeight"), minH = 48),
                ),
            )
        )
        add(
            UiNode.Row(
                "nums2",
                children = listOf(
                    SamplesUi.field("dDepth", "深度", Bound.Ref("dDepth"), Action.of("dDepth"), minH = 48),
                    SamplesUi.field("dProb", "概率%", Bound.Ref("dProb"), Action.of("dProb"), minH = 48),
                ),
            )
        )
        add(SamplesUi.field("dGroup", "包含组（同组互斥，可留空）", Bound.Ref("dGroup"), Action.of("dGroup"), minH = 48))
        add(toggleRow("dConstant", "常驻", b(state, "dConstant")))
        add(toggleRow("dRegex", "正则关键词", b(state, "dRegex")))
        add(toggleRow("dCase", "区分大小写", b(state, "dCase")))
        add(toggleRow("dEnabled", "启用", b(state, "dEnabled")))
        add(SamplesUi.primaryAction("save", if (editing.isBlank()) "创建条目" else "保存修改"))
        if (editing.isNotBlank()) add(SamplesUi.secondaryAction("cancel", "取消编辑"))
    }

    private fun logicRow(selected: String): UiNode = UiNode.Scroll(
        "logic", vertical = false,
        modifier = Mod(width = Size.Fill, padding = Edges(0, 0, 0, 6)),
        child = UiNode.Row(
            "logic_r",
            children = logics.map { lg ->
                UiNode.Button(
                    "lg_$lg", Bound.Lit(lg), Action.of("setLogic", "value" to lg),
                    variant = if (lg == selected) UiNode.Button.Variant.FILLED else UiNode.Button.Variant.TONAL,
                    modifier = Mod(padding = Edges(0, 0, 0, 8)),
                )
            },
        ),
    )

    private fun toggleRow(id: String, label: String, on: Boolean): UiNode = UiNode.Button(
        id, Bound.Lit("$label：${if (on) "开" else "关"}"), Action.of(id),
        variant = UiNode.Button.Variant.TONAL,
        modifier = Mod(width = Size.Fill, padding = Edges(0, 0, 0, 8)),
    )

    // ---- 测试 ----

    private fun testTab(state: Map<String, Any?>, entries: List<LoreEntry>): List<UiNode> = buildList {
        val testText = s(state, "testText")
        val hits = (state["hits"] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()
        val injection = s(state, "injection")
        add(SamplesUi.hint("th", "输入一段文本（比如对话内容），看哪些条目会被激活。"))
        add(SamplesUi.field("testText", "测试文本", Bound.Ref("testText"), Action.of("testText"), singleLine = false, minH = 80))
        add(SamplesUi.primaryAction("test", "扫描"))
        add(SamplesUi.hint("c", "启用条目 ${entries.count { it.enabled }} / ${entries.size}"))
        if (hits.isEmpty()) {
            add(SamplesUi.hint("nh", "（还没有扫描，或没有条目被激活）"))
        } else {
            add(SamplesUi.codeBlock("hits", hits, "", label = "激活条目（按顺序）", minH = 110))
            add(SamplesUi.codeBlock("inj", injection.split("\n"), "（无注入内容）", label = "注入到 AI 的上下文预览", minH = 180))
        }
    }

    // ---------------------------------------------------------------- 行为

    private fun handleAction(args: KValue): KValue {
        val obj = (args as? KValue.Obj)?.value ?: emptyMap()
        val state = SamplesUi.readState(args)
        val actionId = obj["actionId"]?.asString() ?: ""
        val payload = (obj["payload"] as? KValue.Obj)?.value ?: emptyMap()
        var tab = s(state, "tab").ifBlank { "list" }
        var query = s(state, "query")
        var entries = entriesOf(state).toMutableList()
        var editingId = s(state, "editingId")
        var hits = (state["hits"] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList<String>()
        var injection = s(state, "injection")

        // 草稿字段
        fun draft(k: String) = s(state, k)
        var dComment = draft("dComment")
        var dKeys = draft("dKeys")
        var dSec = draft("dSec")
        var dLogic = draft("dLogic").ifBlank { "AND_ANY" }
        var dContent = draft("dContent")
        var dOrder = draft("dOrder").ifBlank { "100" }
        var dDepth = draft("dDepth").ifBlank { "4" }
        var dWeight = draft("dWeight").ifBlank { "100" }
        var dProb = draft("dProb").ifBlank { "100" }
        var dGroup = draft("dGroup")
        var dConstant = b(state, "dConstant")
        var dRegex = b(state, "dRegex")
        var dCase = b(state, "dCase")
        var dEnabled = if (state.containsKey("dEnabled")) b(state, "dEnabled") else true
        var testText = draft("testText")

        fun clearDraft() {
            editingId = ""; dComment = ""; dKeys = ""; dSec = ""; dLogic = "AND_ANY"
            dContent = ""; dOrder = "100"; dDepth = "4"; dWeight = "100"; dProb = "100"
            dGroup = ""; dConstant = false; dRegex = false; dCase = false; dEnabled = true
        }

        when (actionId) {
            "tab_list" -> tab = "list"
            "tab_edit" -> tab = "edit"
            "tab_test" -> tab = "test"
            "query" -> query = payload["value"]?.asString() ?: ""
            "testText" -> testText = payload["value"]?.asString() ?: ""

            "dComment" -> dComment = payload["value"]?.asString() ?: ""
            "dKeys" -> dKeys = payload["value"]?.asString() ?: ""
            "dSec" -> dSec = payload["value"]?.asString() ?: ""
            "dContent" -> dContent = payload["value"]?.asString() ?: ""
            "dOrder" -> dOrder = payload["value"]?.asString() ?: ""
            "dDepth" -> dDepth = payload["value"]?.asString() ?: ""
            "dWeight" -> dWeight = payload["value"]?.asString() ?: ""
            "dProb" -> dProb = payload["value"]?.asString() ?: ""
            "dGroup" -> dGroup = payload["value"]?.asString() ?: ""
            "setLogic" -> dLogic = payload["value"]?.asString() ?: "AND_ANY"
            "dConstant" -> dConstant = !dConstant
            "dRegex" -> dRegex = !dRegex
            "dCase" -> dCase = !dCase
            "dEnabled" -> dEnabled = !dEnabled

            "new" -> { clearDraft(); tab = "edit" }
            "edit" -> {
                val id = payload["id"]?.asString() ?: ""
                entries.firstOrNull { it.id == id }?.let { e ->
                    editingId = e.id; dComment = e.comment; dKeys = e.keys.joinToString(", ")
                    dSec = e.secondaryKeys.joinToString(", "); dLogic = e.logic; dContent = e.content
                    dOrder = e.order.toString(); dDepth = e.depth.toString(); dWeight = e.weight.toString()
                    dProb = e.probability.toString(); dGroup = e.group
                    dConstant = e.constant; dRegex = e.regex; dCase = e.caseSensitive; dEnabled = e.enabled
                    tab = "edit"
                }
            }
            "cancel" -> { clearDraft(); tab = "list" }
            "save" -> {
                val entry = LoreEntry(
                    id = editingId.ifBlank { "wb_${System.currentTimeMillis()}" },
                    comment = dComment, keys = splitKeys(dKeys), secondaryKeys = splitKeys(dSec),
                    logic = dLogic, content = dContent,
                    order = dOrder.toIntOrNull() ?: 100, depth = dDepth.toIntOrNull() ?: 4,
                    weight = dWeight.toIntOrNull() ?: 100,
                    probability = (dProb.toIntOrNull() ?: 100).coerceIn(0, 100),
                    group = dGroup, constant = dConstant, regex = dRegex,
                    caseSensitive = dCase, enabled = dEnabled,
                )
                val idx = entries.indexOfFirst { it.id == entry.id }
                if (idx >= 0) entries[idx] = entry else entries.add(0, entry)
                persist(entries)
                host?.call("ui.toast", KValue.obj("text" to "已保存"))
                clearDraft(); tab = "list"
            }
            "toggle" -> {
                val id = payload["id"]?.asString() ?: ""
                val idx = entries.indexOfFirst { it.id == id }
                if (idx >= 0) entries[idx] = entries[idx].copy(enabled = !entries[idx].enabled)
                persist(entries)
            }
            "del" -> {
                val id = payload["id"]?.asString() ?: ""
                entries.removeAll { it.id == id }
                persist(entries)
                if (editingId == id) clearDraft()
            }
            "test" -> {
                val sc = LorebookEngine.scan(entries, testText, recursion = true)
                hits = sc.map { h ->
                    val label = h.entry.comment.ifBlank { h.entry.keys.joinToString("/") }
                    "顺序 ${h.entry.order} · ${h.entry.logic} · $label　命中: ${h.matched.joinToString(", ")}"
                }
                injection = LorebookEngine.buildInjection(sc)
            }
            "export" -> {
                host?.call("ui.clipboard", KValue.obj("text" to LorebookEngine.serialize(entries)))
                host?.call("ui.toast", KValue.obj("text" to "已导出 ${entries.size} 条"))
            }
            "importClip" -> {
                val r = host?.call("ui.clipboard", KValue.obj()) ?: KValue.Null
                val raw = when (r) {
                    is KValue.Str -> r.value
                    is KValue.Obj -> r.value["text"]?.asString() ?: ""
                    else -> ""
                }
                val incoming = LorebookEngine.parse(raw)
                if (incoming.isEmpty()) {
                    host?.call("ui.toast", KValue.obj("text" to "剪贴板里没有可导入的世界书 JSON"))
                } else {
                    val ids = entries.map { it.id }.toSet()
                    incoming.forEach { e ->
                        entries.add(if (e.id in ids) e.copy(id = "wb_${System.currentTimeMillis()}_${entries.size}") else e)
                    }
                    persist(entries)
                    host?.call("ui.toast", KValue.obj("text" to "已导入 ${incoming.size} 条"))
                }
            }
        }

        return KValue.obj(
            "tab" to tab, "query" to query,
            "entries" to toState(entries),
            "editingId" to editingId,
            "dComment" to dComment, "dKeys" to dKeys, "dSec" to dSec, "dLogic" to dLogic,
            "dContent" to dContent, "dOrder" to dOrder, "dDepth" to dDepth,
            "dWeight" to dWeight, "dProb" to dProb, "dGroup" to dGroup,
            "dConstant" to dConstant, "dRegex" to dRegex, "dCase" to dCase, "dEnabled" to dEnabled,
            "testText" to testText, "hits" to hits, "injection" to injection,
        )
    }
}
