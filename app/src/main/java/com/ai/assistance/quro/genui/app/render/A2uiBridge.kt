package com.ai.assistance.quro.genui.app.render

import org.json.JSONArray
import org.json.JSONObject

/**
 * A2uiBridge —— 原生组件树的统一路由：
 *
 * 树里全部类型都在 GenUI 传统 16 种内 → 走本地 ComposeDescRenderer（快）；
 * 出现任何官方 A2UI-Android 引擎支持的类型（图表/播放器/Tabs/表单…）→
 * 整树转成 A2UI updateComponents 消息，交给官方引擎渲染（全类型覆盖）。
 *
 * SDK 解析配置 ignoreUnknownKeys=true，ComposeDesc 风格的附加属性可安全透传。
 */
object A2uiBridge {

    private val LEGACY_TYPES = setOf(
        "column", "row", "box", "text", "button", "card", "badge", "chip",
        "divider", "icon", "lazycolumn", "progressbar", "slider", "spacer",
        "switch", "textfield",
    )

    private var seq = 0

    /** 路由：需要官方引擎时返回 updateComponents 消息，否则 null（走本地渲染） */
    fun route(desc: String): String? {
        val root = runCatching { JSONObject(desc) }.getOrNull() ?: return null
        if (!needsSdk(root)) return null
        val comps = JSONArray()
        convert(root, comps)
        return JSONObject()
            .put("version", "v0.10")
            .put(
                "updateComponents",
                JSONObject().put("surfaceId", "gen").put("components", comps)
            )
            .toString()
    }

    private fun needsSdk(node: JSONObject): Boolean {
        val type = (node.optString("type", node.optString("component"))).lowercase()
        if (type.isNotBlank() && type !in LEGACY_TYPES) return true
        val kids = node.optJSONArray("children") ?: node.optJSONArray("items")
        if (kids != null) {
            for (i in 0 until kids.length()) {
                val c = kids.optJSONObject(i)
                if (c != null && needsSdk(c)) return true
            }
        }
        return false
    }

    /** ComposeDesc/A2UI 混合树 → A2UI 组件数组（type/component 双键兼容，自动配 id） */
    private fun convert(node: JSONObject, out: JSONArray): JSONObject {
        val type = node.optString("type", node.optString("component"))
        val o = JSONObject()
            .put("id", "c${++seq}")
            .put("component", type)
        for (k in node.keys()) {
            if (k == "type" || k == "component" || k == "id") continue
            o.put(k, node.get(k))
        }
        val kids = node.optJSONArray("children") ?: node.optJSONArray("items")
        if (kids != null) {
            val mapped = JSONArray()
            for (i in 0 until kids.length()) {
                val c = kids.optJSONObject(i)
                if (c != null) mapped.put(convert(c, out))
                else mapped.put(kids.get(i))
            }
            o.put("childList", mapped)
            o.remove("children")
            o.remove("items")
        }
        out.put(o)
        return o
    }
}
