package com.ai.assistance.quro.core.aci

import org.json.JSONArray
import org.json.JSONObject

/**
 * 本地 ACI 控制台 SDUI 模型（从 lanui 包解耦，纯本地、零网络依赖）。
 *
 * ACI（Agent Capability Interface）是同设备无 Root 的 App 间 Binder 调用框架，
 * 其控制台 UI 经受控端 console_ui 能力返回的 JSON 快照驱动前端渲染。
 * 组件词汇与被控浏览器 ConsoleBackend.buildUiSnapshot() 保持一致，
 * 以便任何通用前端都能直接渲染。
 *
 * ⚠️ 与 lanui 的区别：lanui 是「LAN/WiFi 远程控制台」范式（自带 ServerSocket/HTTP 客户端），
 *    而本模型只描述 UI 数据、不持任何网络能力 —— ACI 控制台从构造上就是本地、离线渲染。
 */
data class AciScreen(
    val title: String,
    val subtitle: String,
    val updatedAt: Long,
    val components: List<AciComponent>
)

sealed interface AciComponent {
    val type: String

    data class Heading(val text: String) : AciComponent { override val type = "heading" }
    data class Text(val text: String) : AciComponent { override val type = "text" }
    data class Button(val action: String, val label: String) : AciComponent { override val type = "button" }
    data class Card(val title: String, val body: String) : AciComponent { override val type = "card" }
    object Divider : AciComponent { override val type = "divider" }
    object Spacer : AciComponent { override val type = "spacer" }
    data class ListItem(val text: String) : AciComponent { override val type = "listitem" }
    data class Input(
        val key: String,
        val label: String,
        val placeholder: String,
        val value: String,
        val action: String
    ) : AciComponent { override val type = "input" }
}

/** 将受控端 console_ui 下发的 JSON 解析为 [AciScreen]。 */
object AciConsoleModel {
    fun parse(json: JSONObject): AciScreen {
        val components = mutableListOf<AciComponent>()
        val arr = json.optJSONArray("components") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            components += when (o.optString("type", "")) {
                "heading" -> AciComponent.Heading(o.optString("text", ""))
                "text" -> AciComponent.Text(o.optString("text", ""))
                "button" -> AciComponent.Button(o.optString("action", ""), o.optString("label", "按钮"))
                "card" -> AciComponent.Card(o.optString("title", ""), o.optString("body", ""))
                "divider" -> AciComponent.Divider
                "spacer" -> AciComponent.Spacer
                "listitem" -> AciComponent.ListItem(o.optString("text", ""))
                "input" -> AciComponent.Input(
                    key = o.optString("key", ""),
                    label = o.optString("label", "输入"),
                    placeholder = o.optString("placeholder", ""),
                    value = o.optString("value", ""),
                    action = o.optString("action", "")
                )
                else -> AciComponent.Text("[未知组件: ${o.optString("type", "?")}]")
            }
        }
        return AciScreen(
            title = json.optString("title", "ACI 控制台"),
            subtitle = json.optString("subtitle", ""),
            updatedAt = json.optLong("updatedAt", 0),
            components = components
        )
    }
}
