package com.ai.assistance.quro.core.aci

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * 本地 ACI 控制台 SDUI 模型（纯本地、零网络依赖）。
 *
 * ACI（Agent Capability Interface）是同设备无 Root 的 App 间 Binder 调用框架，
 * 其控制台 UI 经受控端 console_ui 能力返回的 JSON 快照驱动前端渲染。
 * 组件词汇与被控浏览器 ConsoleBackend.buildUiSnapshot() 保持一致，
 * 以便任何通用前端都能直接渲染。
 *
 * 与早期「LAN/WiFi 远程控制台」范式（自带 ServerSocket/HTTP 客户端、自连本地环回）不同，
 * 本模型只描述 UI 数据、不持任何网络能力 —— ACI 控制台从构造上就是本地、离线渲染。
 */
/** 当前 SDUI 快照 JSON Schema 版本（向前/向后兼容基石；缺失视为 v1）。 */
const val ACI_SDUI_SCHEMA_VERSION = "aci-sdui-v1"

data class AciScreen(
    val title: String,
    val subtitle: String,
    val updatedAt: Long,
    val components: List<AciComponent>,
    /** SDUI 快照 schema 版本；受控端未下发时为空串（按 [ACI_SDUI_SCHEMA_VERSION] 兼容处理）。 */
    val schemaVersion: String = ""
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
        val schemaVersion = json.optString("schema_version", "")
        if (schemaVersion.isNotEmpty() && schemaVersion != ACI_SDUI_SCHEMA_VERSION) {
            // 版本不一致：向后兼容——仍解析，仅告警（未来可在此做字段映射/降级）
            Log.w("AciConsoleModel", "⚠️ SDUI schema 版本不一致：收到=$schemaVersion，本端=$ACI_SDUI_SCHEMA_VERSION，按兼容模式解析")
        }
        return AciScreen(
            title = json.optString("title", "ACI 控制台"),
            subtitle = json.optString("subtitle", ""),
            updatedAt = json.optLong("updatedAt", 0),
            components = components,
            schemaVersion = schemaVersion
        )
    }
}
