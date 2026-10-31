package com.ai.assistance.quro.core.mika.cap.phone

import com.ai.assistance.quro.core.mika.uistate.ScreenSnapshot
import com.ai.assistance.quro.core.mika.uistate.UiTreeNode
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 手机界面上的结构化查询和跨屏文本合并。工具和后续本地调用共用这套语义。 */
internal object PhoneAutomationEngine {
    data class Query(val text: String? = null, val resourceId: String? = null, val role: String? = null)

    fun find(screen: ScreenSnapshot, query: Query, limit: Int): JsonObject {
        val matches = mutableListOf<JsonObject>()
        var total = 0
        fun visit(node: UiTreeNode, parents: List<String>, clickableParent: String?, editableParent: String?) {
            val clickRef = if (node.clickable) node.ref ?: clickableParent else clickableParent
            val editRef = if (node.editable) node.ref ?: editableParent else editableParent
            val label = listOfNotNull(node.text.takeUnless { node.secret }, node.description, node.hintText).filter(String::isNotBlank).joinToString(" ")
            val hit = (query.text == null || label.contains(query.text, ignoreCase = true)) &&
                (query.resourceId == null || node.resourceId?.contains(query.resourceId, ignoreCase = true) == true) &&
                (query.role == null || node.role.contains(query.role, ignoreCase = true))
            if (hit) {
                total++
                if (matches.size < limit) matches += buildJsonObject {
                    node.ref?.let { put("ref", it) }
                    clickRef?.let { put("actionRef", it) }
                    editRef?.let { put("editRef", it) }
                    put("role", node.role)
                    if (label.isNotBlank() && !node.secret) put("name", label)
                    node.resourceId?.let { put("resourceId", it) }
                    put("bounds", JsonArray(node.bounds.map(::JsonPrimitive)))
                    put("parents", JsonArray(parents.map(::JsonPrimitive)))
                    if (node.clickable) put("clickable", true)
                    if (node.editable) put("editable", true)
                    if (node.scrollable) put("scrollable", true)
                    if (!node.enabled) put("enabled", false)
                }
            }
            val next = (parents + listOfNotNull(node.text.takeUnless { node.secret }, node.description, node.resourceId)
                .firstOrNull { it.isNotBlank() }?.take(100).orEmpty()).filter(String::isNotBlank).takeLast(8)
            node.children.forEach { visit(it, next, clickRef, editRef) }
        }
        screen.windows.forEach { window -> window.nodes.forEach { visit(it, listOfNotNull(window.title, window.packageName), null, null) } }
        if (screen.windows.isEmpty()) {
            screen.elements.forEach { e ->
                if ((query.text == null || (!e.secret && e.name.contains(query.text, true))) &&
                    (query.resourceId == null || e.resourceId?.contains(query.resourceId, true) == true) &&
                    (query.role == null || e.role.contains(query.role, true))) {
                    total++
                    if (matches.size < limit) matches += buildJsonObject {
                        put("ref", e.ref); put("role", e.role)
                        if (e.clickable) put("actionRef", e.ref)
                        if (e.editable) put("editRef", e.ref)
                        if (!e.secret) put("name", e.name)
                        e.resourceId?.let { put("resourceId", it) }
                        put("bounds", JsonArray(e.bounds.map(::JsonPrimitive)))
                        put("parents", JsonArray(emptyList()))
                        if (e.clickable) put("clickable", true)
                        if (e.editable) put("editable", true)
                    }
                }
            }
        }
        return buildJsonObject {
            put("snapshotId", screen.snapshotId)
            put("currentApp", screen.packageName ?: screen.unreadableApp)
            put("total", total)
            put("truncated", total > matches.size)
            put("matches", JsonArray(matches))
        }
    }

    /** 重叠区域按整段顺序合并,同文的两条独立消息仍可保留。 */
    fun appendPage(accumulated: List<String>, page: List<String>): List<String> {
        if (page.isEmpty()) return accumulated
        val overlap = (minOf(accumulated.size, page.size) downTo 1).firstOrNull { n -> accumulated.takeLast(n) == page.take(n) } ?: 0
        return accumulated + page.drop(overlap)
    }

    fun prependPage(accumulated: List<String>, page: List<String>): List<String> {
        if (page.isEmpty()) return accumulated
        val overlap = (minOf(accumulated.size, page.size) downTo 1).firstOrNull { n -> page.takeLast(n) == accumulated.take(n) } ?: 0
        return page.dropLast(overlap) + accumulated
    }
}
