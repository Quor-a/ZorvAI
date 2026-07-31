package com.ai.assistance.quro.lanui

import org.json.JSONArray
import org.json.JSONObject

/**
 * 后端下发的 UI 描述模型。前端解析后渲染，**前端免发版**：改界面只需改后端 JSON。
 */
data class LanScreen(
    val title: String,
    val subtitle: String,
    val updatedAt: Long,
    val components: List<LanComponent>
)

sealed interface LanComponent {
    val type: String

    data class Heading(val text: String) : LanComponent { override val type = "heading" }
    data class Text(val text: String) : LanComponent { override val type = "text" }
    data class Button(val action: String, val label: String) : LanComponent { override val type = "button" }
    data class Card(val title: String, val body: String) : LanComponent { override val type = "card" }
    object Divider : LanComponent { override val type = "divider" }
    object Spacer : LanComponent { override val type = "spacer" }
    data class ListItem(val text: String) : LanComponent { override val type = "listitem" }
    data class Input(
        val key: String,
        val label: String,
        val placeholder: String,
        val value: String,
        val action: String
    ) : LanComponent { override val type = "input" }
}

/** 将后端下发的 JSON 解析为 [LanScreen]。 */
object LanUiModel {
    fun parse(json: JSONObject): LanScreen {
        val components = mutableListOf<LanComponent>()
        val arr = json.optJSONArray("components") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            components += when (o.optString("type", "")) {
                "heading" -> LanComponent.Heading(o.optString("text", ""))
                "text" -> LanComponent.Text(o.optString("text", ""))
                "button" -> LanComponent.Button(o.optString("action", ""), o.optString("label", "按钮"))
                "card" -> LanComponent.Card(o.optString("title", ""), o.optString("body", ""))
                "divider" -> LanComponent.Divider
                "spacer" -> LanComponent.Spacer
                "listitem" -> LanComponent.ListItem(o.optString("text", ""))
                "input" -> LanComponent.Input(
                    key = o.optString("key", ""),
                    label = o.optString("label", "输入"),
                    placeholder = o.optString("placeholder", ""),
                    value = o.optString("value", ""),
                    action = o.optString("action", "")
                )
                else -> LanComponent.Text("[未知组件: ${o.optString("type", "?")}]")
            }
        }
        return LanScreen(
            title = json.optString("title", "LAN 控制台"),
            subtitle = json.optString("subtitle", ""),
            updatedAt = json.optLong("updatedAt", 0),
            components = components
        )
    }
}
