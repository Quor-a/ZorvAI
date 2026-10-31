package com.ai.assistance.quro.core.mika.uistate

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 差异文字超过全量的这个比例就发全量:差异省不了多少,还更难读。 */
private const val MAX_DIFF_RATIO = 0.5
private const val DEFAULT_MAX_ELEMENTS = 200

/**
 * 两屏之间的差异(按稳定编号对齐),给写操作结果的 `screen` 字段:
 * `{"snapshotId","base","currentApp","full":false,"added":[元素],"removed":[{ref,name,role}],"changed":[元素+"was"],"unchanged":N}`。
 * 没有 base、[baseVisible] 为假(模型已看不到差异要依赖的读屏)、前台 App 变了、或差异文字超过全量的一半时发全量:
 * `{"snapshotId","currentApp","full":true,"elements":[…],"truncated"}`。
 * 差异按两屏的完整元素列表算,输出时 added/changed 合计不超过 [maxElements] 个,超出的标 truncated。
 * 手机快照有窗口树时,全量结果带 tree,差异结果带 treeChanges;调用方给手机传完整元素数。
 */
object InterfaceUpdate {
    fun between(base: ModelSnapshot?, current: ModelSnapshot, baseVisible: Boolean, maxElements: Int = DEFAULT_MAX_ELEMENTS): JsonObject {
        val (all, _) = ScreenJson.elements(current.screen, Int.MAX_VALUE)
        fun full() = buildJsonObject {
            put("snapshotId", current.snapshotId)
            put("currentApp", current.port.packageName)
            put("full", true)
            flags(current)
            put("elements", JsonArray(all.take(maxElements)))
            put("truncated", all.size > maxElements)
            if (current.screen.windows.isNotEmpty()) put("tree", UiTreeJson.windows(current.screen))
        }
        if (base == null || !baseVisible || base.port.packageName != current.port.packageName) return full()
        if (current.screen.windows.isNotEmpty() && !UiTreeJson.sameWindows(base.screen, current.screen)) return full()
        val before = ScreenJson.elements(base.screen, Int.MAX_VALUE).first.associateBy(::ref)
        val now = all.associateBy(::ref)
        val added = all.filter { ref(it) !in before }
        val removed = before.filter { it.key !in now }.values.map { e ->
            val o = e as JsonObject
            buildJsonObject { put("ref", ref(e)); o["name"]?.let { put("name", it) }; o["role"]?.let { put("role", it) } }
        }
        val changed = all.mapNotNull { e ->
            val old = before[ref(e)] ?: return@mapNotNull null
            if (old == e) return@mapNotNull null
            val was = (old as JsonObject)["name"]
            if (was != (e as JsonObject)["name"]) JsonObject(e + ("was" to (was ?: JsonPrimitive("")))) else e
        }
        val diffChars = JsonArray(added).toString().length + JsonArray(removed).toString().length + JsonArray(changed).toString().length
        if (diffChars > all.toString().length * MAX_DIFF_RATIO) return full()
        val treeChanges = if (current.screen.windows.isEmpty()) null else UiTreeJson.changes(base.screen, current.screen)
        if (treeChanges != null && treeChanges.toString().length > UiTreeJson.windows(current.screen).toString().length * MAX_DIFF_RATIO) return full()
        val shownAdded = added.take(maxElements)
        val shownChanged = changed.take((maxElements - shownAdded.size).coerceAtLeast(0))
        return buildJsonObject {
            put("snapshotId", current.snapshotId)
            put("base", base.snapshotId)
            put("currentApp", current.port.packageName)
            put("full", false)
            flags(current)
            put("added", JsonArray(shownAdded))
            put("removed", JsonArray(removed))
            put("changed", JsonArray(shownChanged))
            put("unchanged", all.size - added.size - changed.size)
            if (shownAdded.size + shownChanged.size < added.size + changed.size) put("truncated", true)
            treeChanges?.let { put("treeChanges", it) }
        }
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.flags(s: ModelSnapshot) {
        if (s.port.changedDuringRead) put("screenChangedDuringRead", true)
    }

    private fun ref(e: JsonElement) = ((e as JsonObject)["ref"] as JsonPrimitive).content
}
