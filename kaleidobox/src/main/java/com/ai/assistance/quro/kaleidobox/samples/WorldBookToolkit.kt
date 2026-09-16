package com.ai.assistance.quro.kaleidobox.samples

import com.ai.assistance.quro.kaleidobox.core.engine.InvokeContext
import com.ai.assistance.quro.kaleidobox.core.engine.KaleidoToolkit
import com.ai.assistance.quro.kaleidobox.core.engine.ToolkitHost
import com.ai.assistance.quro.kaleidobox.core.model.KValue
import com.ai.assistance.quro.kaleidobox.core.ui.*
import com.ai.assistance.quro.kaleidobox.core.util.Json

/**
 * 真能力插件：世界书（WorldBook）。
 *
 * 设定/知识卡片的持久化仓库：每条 = {id, category, front, back}。
 * 落地到宿主私有 KV（[data.kv]，随 App 持久化），支持搜索、新增、编辑、删除。
 * 适合做角色设定集、世界观词条、速查卡等"长期记忆"。
 */
class WorldBookToolkit : KaleidoToolkit {

    private var host: ToolkitHost? = null

    override fun attach(host: ToolkitHost) { this.host = host }

    override fun invoke(fn: String, args: KValue, ctx: InvokeContext): KValue = when (fn) {
        "about_worldbook" -> KValue.Str("世界书：持久化的设定/知识卡片仓库（分类 · 正面 · 背面），随 App 长期保存。")
        "render" -> KValue.Str(Json.write(UiCodec.encode(buildUi(args))))
        "onAction" -> handleAction(args)
        else -> KValue.fail("E_NO_FN", "未知函数: $fn")
    }

    private fun loadEntries(): List<Map<String, String>> {
        val r = host?.call("data.kv", KValue.obj("op" to "get", "key" to "worldbook_entries")) ?: KValue.Null
        val str = (r as? KValue.Str)?.value ?: return emptyList()
        val parsed = runCatching { Json.parse(str) }.getOrNull()
        @Suppress("UNCHECKED_CAST")
        return (parsed as? List<*>)?.mapNotNull { it as? Map<*, *> }
            ?.map { m -> m.mapKeys { it.key.toString() }.mapValues { it.value?.toString() ?: "" } }
            ?: emptyList()
    }

    private fun saveEntries(list: List<Map<String, String>>) {
        host?.call(
            "data.kv",
            KValue.obj("op" to "set", "key" to "worldbook_entries", "value" to Json.write(KValue.of(list))),
        )
    }

    private fun buildUi(args: KValue): UiNode {
        val state = SamplesUi.readState(args)
        val query = (state["query"] as? String) ?: ""
        val cat = (state["draftCat"] as? String) ?: ""
        val front = (state["draftFront"] as? String) ?: ""
        val back = (state["draftBack"] as? String) ?: ""
        val editingId = (state["editingId"] as? String) ?: ""
        val catFilter = (state["catFilter"] as? String) ?: ""
        var all = strMaps(state, "entries")
        if (all.isEmpty()) all = loadEntries()
        val cats = all.mapNotNull { it["category"]?.takeIf { c -> c.isNotBlank() } }.distinct().take(12)
        val shown = all.filter { catFilter.isBlank() || (it["category"] ?: "") == catFilter }
            .filter {
                query.isBlank() ||
                    (it["front"] ?: "").contains(query, true) || (it["back"] ?: "").contains(query, true) ||
                    (it["category"] ?: "").contains(query, true)
            }

        val content = buildList<UiNode> {
            add(SamplesUi.section("sec", "世界书 · 设定/知识卡片"))
            add(SamplesUi.field("query", "搜索（正面/背面/分类）", Bound.Ref("query"), Action.of("query"), singleLine = true, minH = 52))
            if (cats.isNotEmpty()) add(catChips(cats, catFilter))
            add(SamplesUi.hint("c", "共 ${all.size} 条，显示 ${shown.size} 条"))
            // 新增 / 编辑表单
            add(SamplesUi.label("fl_cat", "分类"))
            add(SamplesUi.field("draftCat", "分类（如 角色/世界观）", Bound.Ref("draftCat"), Action.of("draftCat"), singleLine = true, minH = 48))
            add(SamplesUi.label("fl_front", "正面（问题/词条）"))
            add(SamplesUi.field("draftFront", "正面", Bound.Ref("draftFront"), Action.of("draftFront"), singleLine = false, minH = 64))
            add(SamplesUi.label("fl_back", "背面（答案/内容）"))
            add(SamplesUi.field("draftBack", "背面", Bound.Ref("draftBack"), Action.of("draftBack"), singleLine = false, minH = 80))
            if (editingId.isNotEmpty()) {
                add(SamplesUi.primaryAction("save", "保存修改"))
                add(SamplesUi.secondaryAction("cancel", "取消编辑"))
            } else {
                add(SamplesUi.primaryAction("add", "添加条目"))
            }
            // 列表
            if (shown.isEmpty()) {
                add(SamplesUi.hint("empty", "（还没有条目，上面填写后点添加）"))
            }
            shown.take(80).forEach { e ->
                val id = e["id"] ?: return@forEach
                val lines = listOf(
                    "【${e["category"] ?: "未分类"}】${e["front"] ?: ""}",
                    e["back"] ?: "",
                )
                add(
                    UiNode.Column(
                        "card_$id",
                        modifier = Mod(padding = Edges(0, 0, 0, 8)),
                        children = listOf(
                            SamplesUi.codeBlock("blk_$id", lines, "", minH = 96),
                            UiNode.Row(
                                "ra_$id",
                                modifier = Mod(padding = Edges(0, 0, 0, 6)),
                                children = listOf(
                                    UiNode.Button(
                                        "edit_$id", Bound.Lit("编辑"),
                                        Action.of("edit", "id" to id),
                                        variant = UiNode.Button.Variant.TONAL,
                                        modifier = Mod(weight = 1f),
                                    ),
                                    UiNode.Button(
                                        "del_$id", Bound.Lit("删除"),
                                        Action.of("delete", "id" to id),
                                        variant = UiNode.Button.Variant.TONAL,
                                        modifier = Mod(weight = 1f, padding = Edges(0, 0, 0, 8)),
                                    ),
                                ),
                            ),
                        ),
                    )
                )
            }
        }
        return SamplesUi.scrollPage(
            "root",
            content = content,
            actions = listOf(
                SamplesUi.secondaryAction("exportAll", "导出到剪贴板"),
                SamplesUi.secondaryAction("importClip", "从剪贴板导入"),
            ),
        )
    }

    /** 分类筛选：横向胶囊，"全部" + 各分类。 */
    private fun catChips(cats: List<String>, selected: String): UiNode = UiNode.Scroll(
        "cats", vertical = false,
        modifier = Mod(width = Size.Fill, padding = Edges(0, 0, 0, 6)),
        child = UiNode.Row(
            "cats_r",
            children = buildList {
                add(
                    UiNode.Button(
                        "ct_all", Bound.Lit("全部"), Action.of("catFilter", "value" to ""),
                        variant = if (selected.isBlank()) UiNode.Button.Variant.FILLED else UiNode.Button.Variant.TONAL,
                        modifier = Mod(padding = Edges(0, 0, 0, 8)),
                    )
                )
                cats.forEach { c ->
                    add(
                        UiNode.Button(
                            "ct_$c", Bound.Lit(c), Action.of("catFilter", "value" to c),
                            variant = if (selected == c) UiNode.Button.Variant.FILLED else UiNode.Button.Variant.TONAL,
                            modifier = Mod(padding = Edges(0, 0, 0, 8)),
                        )
                    )
                }
            },
        ),
    )

    private fun handleAction(args: KValue): KValue {
        val obj = (args as? KValue.Obj)?.value ?: emptyMap()
        val state = SamplesUi.readState(args)
        val actionId = obj["actionId"]?.asString() ?: ""
        val payload = (obj["payload"] as? KValue.Obj)?.value ?: emptyMap()
        var entries = strMaps(state, "entries").toMutableList()
        if (entries.isEmpty()) entries = loadEntries().toMutableList()
        var query = (state["query"] as? String) ?: ""
        var draftCat = (state["draftCat"] as? String) ?: ""
        var draftFront = (state["draftFront"] as? String) ?: ""
        var draftBack = (state["draftBack"] as? String) ?: ""
        var editingId = (state["editingId"] as? String) ?: ""
        var catFilter = (state["catFilter"] as? String) ?: ""

        when (actionId) {
            "catFilter" -> catFilter = payload["value"]?.asString() ?: ""
            "query" -> query = payload["value"]?.asString() ?: ""
            "draftCat" -> draftCat = payload["value"]?.asString() ?: ""
            "draftFront" -> draftFront = payload["value"]?.asString() ?: ""
            "draftBack" -> draftBack = payload["value"]?.asString() ?: ""
            "add" -> {
                if (draftFront.isNotBlank() || draftBack.isNotBlank()) {
                    val id = "wb_${System.currentTimeMillis()}"
                    entries.add(0, mapOf("id" to id, "category" to draftCat, "front" to draftFront, "back" to draftBack))
                    saveEntries(entries)
                    draftCat = ""; draftFront = ""; draftBack = ""
                }
            }
            "edit" -> {
                editingId = payload["id"]?.asString() ?: ""
                entries.firstOrNull { it["id"] == editingId }?.let {
                    draftCat = it["category"] ?: ""; draftFront = it["front"] ?: ""; draftBack = it["back"] ?: ""
                }
            }
            "save" -> {
                val idx = entries.indexOfFirst { it["id"] == editingId }
                if (idx >= 0) {
                    entries[idx] = mapOf("id" to editingId, "category" to draftCat, "front" to draftFront, "back" to draftBack)
                    saveEntries(entries)
                }
                editingId = ""; draftCat = ""; draftFront = ""; draftBack = ""
            }
            "cancel" -> { editingId = ""; draftCat = ""; draftFront = ""; draftBack = "" }
            "delete" -> {
                val id = payload["id"]?.asString() ?: ""
                entries.removeAll { it["id"] == id }
                saveEntries(entries)
                if (editingId == id) editingId = ""
            }
            "exportAll" -> {
                host?.call("ui.clipboard", KValue.obj("text" to Json.write(KValue.of(entries))))
                host?.call("ui.toast", KValue.obj("text" to "已导出 ${entries.size} 条到剪贴板"))
            }
            "importClip" -> {
                val r = host?.call("ui.clipboard", KValue.obj()) ?: KValue.Null
                val str = when (r) {
                    is KValue.Str -> r.value
                    is KValue.Obj -> r.value["text"]?.asString() ?: ""
                    else -> ""
                }
                val parsed = runCatching { Json.parse(str) }.getOrNull()
                val incoming = (parsed as? List<*>)?.mapNotNull { it as? Map<*, *> }
                    ?.map { m -> m.mapKeys { it.key.toString() }.mapValues { it.value?.toString() ?: "" } }
                    ?: emptyList()
                if (incoming.isEmpty()) {
                    host?.call("ui.toast", KValue.obj("text" to "剪贴板里没有可导入的世界书 JSON"))
                } else {
                    val existingIds = entries.mapNotNull { it["id"] }.toSet()
                    var added = 0
                    incoming.forEach { e ->
                        val id = e["id"]?.takeIf { it.isNotBlank() && it !in existingIds }
                            ?: "wb_${System.currentTimeMillis()}_$added"
                        entries.add(0, mapOf(
                            "id" to id,
                            "category" to (e["category"] ?: ""),
                            "front" to (e["front"] ?: ""),
                            "back" to (e["back"] ?: ""),
                        ))
                        added++
                    }
                    saveEntries(entries)
                    host?.call("ui.toast", KValue.obj("text" to "已导入 $added 条"))
                }
            }
        }
        return KValue.obj(
            "entries" to entries, "query" to query, "catFilter" to catFilter,
            "draftCat" to draftCat, "draftFront" to draftFront, "draftBack" to draftBack,
            "editingId" to editingId,
        )
    }

    private fun strMaps(state: Map<String, Any?>, key: String): List<Map<String, String>> {
        val v = state[key]
        return (v as? List<*>)?.mapNotNull { it as? Map<*, *> }
            ?.map { m -> m.mapKeys { it.key.toString() }.mapValues { it.value?.toString() ?: "" } }
            ?: emptyList()
    }
}
