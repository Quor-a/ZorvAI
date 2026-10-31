package com.ai.assistance.quro.core.mika.uistate

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object ScreenText {
    /** 一行一个元素:`@e3 button "搜索" (500,50)`,中心点与点按坐标同为 0–999;超预算时截断并在末尾说明剩余数量。 */
    fun render(s: ScreenSnapshot, maxChars: Int = 6000): String {
        val lines = s.elements.filter { it.meaningful }.map { line(it, s) }
        val out = StringBuilder()
        var shown = 0
        for (l in lines) {
            val remaining = lines.size - shown - 1
            // 预留页脚空间,保证整体不超预算
            val reserve = if (remaining > 0) footer(remaining).length + 1 else 0
            if (out.length + l.length + 1 + reserve > maxChars) break
            out.append(l).append('\n')
            shown++
        }
        val hidden = lines.size - shown
        if (hidden > 0) out.append(footer(hidden))
        return out.toString().trimEnd('\n').let { if (it.length > maxChars) it.take(maxChars) else it }
    }

    private fun footer(n: Int) = "…$n more nodes not shown; scroll or narrow the range"

    private fun line(e: UiElement, s: ScreenSnapshot): String {
        val name = if (e.name.isBlank()) "" else " \"${e.name.replace('\n', ' ')}\""
        return "${e.ref} ${e.role}$name${if (e.hint) " hint" else ""} (${normalize(e.centerX, s.width)},${normalize(e.centerY, s.height)})"
    }
}

object ScreenJson {
    /** observe 的元素数组;center 换成与 tap 坐标相同的 0–999(像素只在端口内部用);返回数组与被截掉的元素数。 */
    fun elements(s: ScreenSnapshot, maxElements: Int): Pair<JsonArray, Int> {
        val all = s.elements.filter { it.meaningful }
        val arr = buildJsonArray {
            for (e in all.take(maxElements)) add(buildJsonObject {
                put("ref", e.ref)
                put("role", e.role)
                put("name", e.name)
                put("center", buildJsonObject { put("x", normalize(e.centerX, s.width)); put("y", normalize(e.centerY, s.height)) })
                put("bounds", JsonArray(e.bounds.map { JsonPrimitive(it) }))
                e.resourceId?.let { put("resourceId", it) }
                if (e.hint) put("hint", true)
            })
        }
        return arr to (all.size - arr.size).coerceAtLeast(0)
    }
}
