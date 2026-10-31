package com.ai.assistance.quro.core.mika.uistate

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * OCR 与无障碍树的空间关联。原始 tree / visibleText 仍是依据；这里不改节点，也不推断图标的含义。
 * 只关联确实落在可操作节点内的文字，重叠控件无法确定归属时不输出关联。
 */
internal object ScreenMap {
    private data class PositionedNode(val path: String, val node: UiTreeNode, val layer: Int) {
        val area: Long get() = area(node.bounds)
    }

    fun json(screen: ScreenSnapshot, ocr: List<VisibleTextLine>): JsonObject? {
        if (screen.web != null || screen.windows.isEmpty() || ocr.isEmpty()) return null
        val nodes = buildList<PositionedNode> {
            fun visit(path: String, node: UiTreeNode, layer: Int) {
                if (area(node.bounds) > 0) add(PositionedNode(path, node, layer))
                node.children.forEachIndexed { i, child -> visit("$path/$i", child, layer) }
            }
            screen.windows.forEach { window -> window.nodes.forEachIndexed { i, node -> visit("w${window.id}/$i", node, window.layer) } }
        }
        val controls = nodes.filter { !it.node.secret && it.node.enabled && (it.node.clickable || it.node.editable || it.node.checkable) }
        if (controls.isEmpty()) return null

        val linked = linkedMapOf<String, Pair<PositionedNode, MutableList<Int>>>()
        ocr.forEachIndexed { index, line ->
            // 上层窗口遮住的下层控件，即使坐标重合也不是这行可见文字的主人。
            val topLayer = nodes.asSequence().filter { containsMost(it.node.bounds, line.bounds) }.maxOfOrNull { it.layer }
                ?: return@forEachIndexed
            val candidates = controls.filter { it.layer == topLayer && containsMost(it.node.bounds, line.bounds) }
                .sortedWith(compareBy<PositionedNode> { it.area }.thenByDescending { it.path.count { c -> c == '/' } })
            val best = candidates.firstOrNull() ?: return@forEachIndexed
            // 不相关的两个控件覆盖同一行且大小相近时，不把文字武断地挂到其中一个上。
            if (candidates.drop(1).any { other -> other.area <= best.area * 12 / 10 &&
                    !isAncestor(other.path, best.path) && !isAncestor(best.path, other.path) }) return@forEachIndexed
            linked.getOrPut(best.path) { best to mutableListOf() }.second += index
        }
        if (linked.isEmpty()) return null
        return buildJsonObject {
            put("ocrToControl", buildJsonArray {
                linked.values.forEach { (control, indexes) ->
                    add(buildJsonObject {
                        put("treePath", control.path)
                        control.node.ref?.let { put("ref", it) }
                        put("bounds", JsonArray(control.node.bounds.map(::JsonPrimitive)))
                        put("ocrIndexes", JsonArray(indexes.map(::JsonPrimitive)))
                    })
                }
            })
        }
    }

    private fun containsMost(box: List<Int>, line: List<Int>): Boolean {
        if (area(box) == 0L || area(line) == 0L) return false
        val width = (minOf(box[2], line[2]) - maxOf(box[0], line[0])).coerceAtLeast(0)
        val height = (minOf(box[3], line[3]) - maxOf(box[1], line[1])).coerceAtLeast(0)
        return width.toLong() * height * 10 >= area(line) * 9
    }

    private fun area(bounds: List<Int>): Long = if (bounds.size != 4) 0 else
        (bounds[2] - bounds[0]).coerceAtLeast(0).toLong() * (bounds[3] - bounds[1]).coerceAtLeast(0)

    private fun isAncestor(parent: String, child: String) = child.startsWith("$parent/")
}
