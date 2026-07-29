package com.ai.assistance.quro.core.model

import android.content.Context
import android.content.SharedPreferences

/**
 * 数字人配置（用户自行决定，而非写死）：
 * - llmMode：LLM 来源。「cloud」= 跟随全局模型配置（云端口）；「offline」= 用户自建本地端点
 *   （如 LM Studio / 端侧 LLM / Ollama），此时 baseUrl/apiKey/model 由用户填写。
 * - avatarSource：头像渲染来源。「builtin」= 内置 2.5D Canvas；「custom」= 用户自制 GLB 模型
 *   （路径存于 customModelPath，由 SAF 选取后拷贝到缓存目录）。
 */
data class QuroDigitalHumanConfig(
    val llmMode: String = "cloud", // "cloud" | "offline"
    val offlineBaseUrl: String = "",
    val offlineApiKey: String = "",
    val offlineModel: String = "",
    val avatarSource: String = "builtin", // "builtin" | "custom"
    val customModelPath: String = "",
) {
    companion object {
        const val PREFS = "quro_digital_human"
        const val KEY_LLM_MODE = "llm_mode"
        const val KEY_OFFLINE_BASE = "offline_base_url"
        const val KEY_OFFLINE_KEY = "offline_api_key"
        const val KEY_OFFLINE_MODEL = "offline_model"
        const val KEY_AVATAR = "avatar_source"
        const val KEY_CUSTOM_PATH = "custom_model_path"
    }

    fun isOfflineConfigured(): Boolean =
        llmMode == "offline" && offlineBaseUrl.isNotBlank() && offlineModel.isNotBlank()
}

class QuroDigitalHumanConfigRepository(private val ctx: Context) {
    private val sp: SharedPreferences by lazy { ctx.getSharedPreferences(QuroDigitalHumanConfig.PREFS, 0) }

    fun load(): QuroDigitalHumanConfig = QuroDigitalHumanConfig(
        llmMode = sp.getString(QuroDigitalHumanConfig.KEY_LLM_MODE, "cloud") ?: "cloud",
        offlineBaseUrl = sp.getString(QuroDigitalHumanConfig.KEY_OFFLINE_BASE, "") ?: "",
        offlineApiKey = sp.getString(QuroDigitalHumanConfig.KEY_OFFLINE_KEY, "") ?: "",
        offlineModel = sp.getString(QuroDigitalHumanConfig.KEY_OFFLINE_MODEL, "") ?: "",
        avatarSource = sp.getString(QuroDigitalHumanConfig.KEY_AVATAR, "builtin") ?: "builtin",
        customModelPath = sp.getString(QuroDigitalHumanConfig.KEY_CUSTOM_PATH, "") ?: "",
    )

    fun save(c: QuroDigitalHumanConfig) {
        sp.edit().apply {
            putString(QuroDigitalHumanConfig.KEY_LLM_MODE, c.llmMode)
            putString(QuroDigitalHumanConfig.KEY_OFFLINE_BASE, c.offlineBaseUrl)
            putString(QuroDigitalHumanConfig.KEY_OFFLINE_KEY, c.offlineApiKey)
            putString(QuroDigitalHumanConfig.KEY_OFFLINE_MODEL, c.offlineModel)
            putString(QuroDigitalHumanConfig.KEY_AVATAR, c.avatarSource)
            putString(QuroDigitalHumanConfig.KEY_CUSTOM_PATH, c.customModelPath)
        }.apply()
    }
}
