package com.ai.assistance.quro.core.soul

import com.ai.assistance.quro.core.QuroPersona

/**
 * Bot 分享：把人格卡序列化为可传输 JSON。
 * 对方拿到文本后，用 JSON 导入（PersonaEditDialog 的导入通道 / 手动粘贴到 JSON 文件导入）
 * 即可恢复同名 Bot。Soul 一并带走——分享的是「长成了什么样」的完整画像。
 */
object QuroBotShare {
    fun export(p: QuroPersona): String = org.json.JSONObject().apply {
        put("quro_bot_share", 1)
        put("name", p.name)
        put("description", p.description)
        put("roleSetting", p.roleSetting)
        put("opening", p.opening)
        put("chatSetting", p.chatSetting)
        put("voiceSetting", p.voiceSetting)
        put("soul", p.soul)
        put("soulUpdatedAt", p.soulUpdatedAt)
        put("tags", org.json.JSONArray().apply { p.tags.forEach { put(it) } })
        put("temperatureEnabled", p.temperatureEnabled)
        put("temperature", p.temperature.toDouble())
        put("topPEnabled", p.topPEnabled)
        put("topP", p.topP.toDouble())
        put("reasoningLevel", p.reasoningLevel)
        put("evolutionEnabled", p.evolutionEnabled)
        put("memoryEnabled", p.memoryEnabled)
        put("proactiveMode", p.proactiveMode)
        put("enabledToolCategories", org.json.JSONArray().apply { p.enabledToolCategories.forEach { put(it) } })
    }.toString(2)

    /** 解析分享 JSON → 人格卡（用于导入通道）。失败返回 null。 */
    fun import(text: String): QuroPersona? = runCatching {
        val o = org.json.JSONObject(text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim())
        if (!o.has("quro_bot_share")) return@runCatching null
        QuroPersona(
            name = o.optString("name", ""),
            avatarEmoji = "🤖",
            description = o.optString("description", ""),
            roleSetting = o.optString("roleSetting", ""),
            opening = o.optString("opening", ""),
            chatSetting = o.optString("chatSetting", ""),
            voiceSetting = o.optString("voiceSetting", ""),
            soul = o.optString("soul", ""),
            soulUpdatedAt = o.optLong("soulUpdatedAt", 0),
            tags = (0 until (o.optJSONArray("tags")?.length() ?: 0)).mapNotNull { o.optJSONArray("tags")?.optString(it) },
            temperatureEnabled = o.optBoolean("temperatureEnabled", false),
            temperature = o.optDouble("temperature", 1.0).toFloat(),
            topPEnabled = o.optBoolean("topPEnabled", false),
            topP = o.optDouble("topP", 1.0).toFloat(),
            reasoningLevel = o.optString("reasoningLevel", ""),
            evolutionEnabled = o.optBoolean("evolutionEnabled", true),
            memoryEnabled = o.optBoolean("memoryEnabled", true),
            proactiveMode = o.optBoolean("proactiveMode", false),
            enabledToolCategories = (0 until (o.optJSONArray("enabledToolCategories")?.length() ?: 0))
                .mapNotNull { o.optJSONArray("enabledToolCategories")?.optString(it) },
        )
    }.getOrNull()
}
