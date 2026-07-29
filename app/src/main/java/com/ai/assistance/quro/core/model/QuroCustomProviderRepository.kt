package com.ai.assistance.quro.core.model

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 用户自建「其他供应商」（原创）：区别于内置 35 个厂商枚举，
 * 这里保存用户在「模型配置」里自己添加的 API 服务商。
 *
 * 用途：添加后会出现在服务商选择列表与「模型选择」中，选中即回填其 BaseURL / 默认模型，
 * 实现「在模型设置界面添加模型供应商，供选择模型使用」。
 *
 * 持久化：应用私有文件 `quro_custom_providers.json`。
 */
data class QuroCustomProvider(
    val id: String = "",            // UUID
    val name: String = "",          // 展示名，如 "我的私有服务"
    val baseUrl: String = "",       // API 基址（不含 /chat/completions）
    val defaultModel: String = "",  // 默认模型名（可空）
    val requiresApiKey: Boolean = true,
    val avatar: String? = null,     // 自定义头像 content uri（可选）
)

class QuroCustomProviderRepository(context: Context) {
    private val file = File(context.filesDir, "quro_custom_providers.json")

    fun loadAll(): List<QuroCustomProvider> {
        if (!file.exists()) return emptyList()
        val text = runCatching { file.readText() }.getOrElse { return emptyList() }
        if (text.isBlank()) return emptyList()
        val arr = runCatching { JSONArray(text) }.getOrNull() ?: return emptyList()
        val out = mutableListOf<QuroCustomProvider>()
        for (i in 0 until arr.length()) {
            runCatching { parse(arr.getJSONObject(i)) }.getOrNull()?.let { out.add(it) }
        }
        return out
    }

    /** 按名称查找（用于模型选择回填展示名）。 */
    fun findById(id: String): QuroCustomProvider? = loadAll().firstOrNull { it.id == id }

    fun upsert(p: QuroCustomProvider) {
        val all = loadAll().toMutableList()
        val idx = all.indexOfFirst { it.id == p.id }
        if (idx >= 0) all[idx] = p else all.add(p)
        saveAll(all)
    }

    fun delete(id: String) {
        saveAll(loadAll().filter { it.id != id })
    }

    private fun saveAll(list: List<QuroCustomProvider>) {
        runCatching {
            val arr = JSONArray()
            list.forEach { arr.put(serialize(it)) }
            file.writeText(arr.toString())
        }
    }

    private fun parse(o: JSONObject): QuroCustomProvider = QuroCustomProvider(
        id = o.optString("id", ""),
        name = o.optString("name", ""),
        baseUrl = o.optString("baseUrl", ""),
        defaultModel = o.optString("defaultModel", ""),
        requiresApiKey = o.optBoolean("requiresApiKey", true),
        avatar = o.optString("avatar", null).ifBlank { null },
    )

    private fun serialize(p: QuroCustomProvider): JSONObject = JSONObject().apply {
        put("id", p.id)
        put("name", p.name)
        put("baseUrl", p.baseUrl)
        put("defaultModel", p.defaultModel)
        put("requiresApiKey", p.requiresApiKey)
        if (p.avatar != null) put("avatar", p.avatar)
    }
}
