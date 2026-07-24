package com.ai.assistance.quro.core.model

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

/**
 * 单条已保存的模型配置预设（原创）。
 *
 * 用户可在「模型配置」页将当前配置保存为一个命名预设，
 * 之后在模型选择中快速切换，无需每次手动填 BaseURL / API Key。
 */
data class QuroSavedProfile(
    val id: String = "",                    // UUID，唯一标识
    val name: String = "",                   // 用户给的名字，如 "我的 DeepSeek"
    val provider: String = "OPENAI",         // 厂商枚举名 / 自定义名称
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
    val temperature: Float = 0.7f,
    val maxTokens: Int = 2048,
    val enableTools: Boolean = true,
    val isCustomProvider: Boolean = false,   // 是否为用户自建厂商
    val customProviderName: String = "",     // 自定义厂商展示名
    val createdAt: Long = 0L,
)

/** 将当前活跃配置转为可保存的 Profile。 */
fun QuroModelConfig.toProfile(name: String): QuroSavedProfile {
    return QuroSavedProfile(
        id = java.util.UUID.randomUUID().toString(),
        name = name,
        provider = this.provider,
        baseUrl = this.baseUrl,
        apiKey = this.apiKey,
        model = this.model,
        temperature = this.temperature,
        maxTokens = this.maxTokens,
        enableTools = this.enableTools,
        isCustomProvider = false,
        createdAt = System.currentTimeMillis(),
    )
}

/**
 * 已保存预设仓库（原创）：用 SharedPreferences 存储多条 JSON 序列化的预设。
 *
 * 存储键：`quro_saved_profiles` → JSONArray 字符串。
 */
class QuroSavedProfileRepository(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("quro_saved_profiles", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_PROFILES = "profiles_json"
    }

    fun loadAll(): List<QuroSavedProfile> {
        val raw = prefs.getString(KEY_PROFILES, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                jsonToProfile(arr.getJSONObject(i))
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun save(profile: QuroSavedProfile) {
        val list = loadAll().toMutableList()
        // 如果已存在同 id 的，替换；否则追加
        val idx = list.indexOfFirst { it.id == profile.id }
        if (idx >= 0) list[idx] = profile else list.add(profile)
        persist(list)
    }

    fun delete(profileId: String) {
        val list = loadAll().toMutableList().filter { it.id != profileId }.toMutableList()
        persist(list)
    }

    /** 用一个 Profile 覆写当前活跃配置（调用 repo.save(cfg)）。 */
    fun applyToConfig(profile: QuroSavedProfile, configRepo: QuroModelConfigRepository) {
        val cfg = QuroModelConfig(
            provider = profile.provider,
            baseUrl = profile.baseUrl,
            apiKey = profile.apiKey,
            model = profile.model,
            temperature = profile.temperature,
            maxTokens = profile.maxTokens,
            enableTools = profile.enableTools,
        )
        configRepo.save(cfg)
    }

    private fun persist(list: List<QuroSavedProfile>) {
        prefs.edit {
            putString(KEY_PROFILES, JSONArray().also { arr ->
                list.forEach { p -> arr.put(profileToJson(p)) }
            }.toString())
        }
    }

    private fun profileToJson(p: QuroSavedProfile): JSONObject = JSONObject().apply {
        put("id", p.id)
        put("name", p.name)
        put("provider", p.provider)
        put("baseUrl", p.baseUrl)
        put("apiKey", p.apiKey)
        put("model", p.model)
        put("temperature", p.temperature.toDouble())
        put("maxTokens", p.maxTokens)
        put("enableTools", p.enableTools)
        put("isCustomProvider", p.isCustomProvider)
        put("customProviderName", p.customProviderName)
        put("createdAt", p.createdAt)
    }

    private fun jsonToProfile(o: JSONObject): QuroSavedProfile = QuroSavedProfile(
        id = o.optString("id", ""),
        name = o.optString("name", ""),
        provider = o.optString("provider", "OPENAI"),
        baseUrl = o.optString("baseUrl", ""),
        apiKey = o.optString("apiKey", ""),
        model = o.optString("model", ""),
        temperature = o.optDouble("temperature", 0.7).toFloat(),
        maxTokens = o.optInt("maxTokens", 2048),
        enableTools = o.optBoolean("enableTools", true),
        isCustomProvider = o.optBoolean("isCustomProvider", false),
        customProviderName = o.optString("customProviderName", ""),
        createdAt = o.optLong("createdAt", 0L),
    )
}
