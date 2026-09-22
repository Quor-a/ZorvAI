package com.ai.assistance.quro.genui.app.render

import org.json.JSONArray
import org.json.JSONObject

/**
 * A2UI 协议适配层 —— 把 Google A2UI 风格的组件树映射为端上 ComposeDesc 原生组件。
 *
 * 背景：A2UI（Agent-to-UI）正成为 Agent 生成界面的协议标准（AGenUI / flutter_genui /
 * a2ui-swift 等生态实现都以它为基准）。GenUI 的原生通道本来就用「组件树 + 属性」JSON，
 * 与 A2UI 同构 —— 这里做一层宽容映射，让按 A2UI 协议写的组件树零转换上屏。
 *
 * 映射规则（A2UI → ComposeDesc）：
 *   heading → Text(style 按级别)   image → Text("🖼 …")（原生 Image 组件待扩展）
 *   checkbox → Switch(checked)     list → LazyColumn(items)
 *   select → Chip                  其余同名组件直通，属性名宽容解析
 */
object A2uiCatalog {

    /** 仅存在于 A2UI 侧的组件名（命中才触发转换，避免误伤原生树） */
    private val A2UI_ONLY = setOf("heading", "image", "checkbox", "list", "select")

    /** 尝试转换：检测到 A2UI 专属组件才动手，否则原样返回 */
    fun maybeConvert(desc: String): String = runCatching {
        val root = JSONObject(desc)
        if (usesA2ui(root)) normalize(root).toString() else desc
    }.getOrDefault(desc)

    private fun usesA2ui(node: JSONObject): Boolean {
        val type = node.optString("type", "").lowercase()
        if (type in A2UI_ONLY) return true
        for (key in arrayOf("children", "items")) {
            val arr = node.optJSONArray(key) ?: continue
            for (i in 0 until arr.length()) {
                val c = arr.optJSONObject(i) ?: continue
                if (usesA2ui(c)) return true
            }
        }
        return false
    }

    /** 递归规范化：A2UI 命名 → 端上组件；属性名宽容解析 */
    fun normalize(node: JSONObject): JSONObject {
        val out = JSONObject()
        val type = node.optString("type", "").lowercase()
        // 透传所有原始属性，再按目标组件语义覆写
        val keys = node.keys()
        while (keys.hasNext()) out.put(keys.next(), node.get(keys.next()))
        out.put("type", type)

        val text = firstText(node, "text", "label", "content", "value", "alt", "title")
        val action = firstText(node, "action", "onTap", "onPress", "event", "actionId", "id")

        when (type) {
            "heading" -> {
                out.put("type", "Text")
                out.put("text", text)
                val level = node.optInt("level", 2)
                out.put("style", if (level <= 1) "headlineLarge" else "titleLarge")
                out.put("weight", "bold")
            }
            "image" -> {
                // 端上暂无原生 Image 组件：以占位文本呈现（后续可扩展 AsyncImage）
                out.put("type", "Text")
                out.put("text", "🖼 " + (text.ifBlank { "图片" }))
            }
            "checkbox" -> {
                out.put("type", "Switch")
                out.put("checked", node.optBoolean("checked", node.optBoolean("value", false)))
                if (text.isNotBlank()) out.put("label", text)
                if (action.isNotBlank()) out.put("action", action)
            }
            "list" -> {
                out.put("type", "LazyColumn")
                val items = node.optJSONArray("items") ?: node.optJSONArray("children") ?: JSONArray()
                out.put("items", items)
            }
            "select" -> {
                out.put("type", "Chip")
                out.put("text", text)
                if (action.isNotBlank()) out.put("action", action)
            }
            "button" -> {
                out.put("type", "Button")
                out.put("text", text)
                if (action.isNotBlank()) out.put("action", action)
            }
            "textfield" -> {
                out.put("type", "TextField")
                if (action.isNotBlank()) out.put("action", action)
            }
            "row", "column", "card", "text", "divider", "slider", "chip",
            "box", "lazycolumn", "icon", "badge", "progressbar", "spacer", "switch" -> { /* 同名直通 */ }
            else -> { /* 未知类型：保留原样，渲染层自己兜底 */ }
        }

        // children 递归（items 属于列表语义，仅 list 处理）
        val children = node.optJSONArray("children")
        if (children != null) {
            val mapped = JSONArray()
            for (i in 0 until children.length()) {
                val c = children.optJSONObject(i)
                mapped.put(if (c != null) normalize(c) else children.get(i))
            }
            out.put("children", mapped)
        }
        return out
    }

    /** 依次取第一个非空字符串属性（宽容 A2UI 属性命名差异） */
    private fun firstText(node: JSONObject, vararg keys: String): String {
        for (k in keys) {
            val v = node.optString(k, "")
            if (v.isNotBlank()) return v
        }
        return ""
    }
}
