package com.ai.assistance.quro.core.model

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * 功能级模型绑定配置（原创）：为 5 类 AI 子能力各自指定使用的模型。
 * - 上下文总结：对话/历史压缩与总结
 * - 记忆更新：记忆库自动沉淀与检索
 * - AI 人格孵化：灵魂卡自动孵化蒸馏
 * - 视频生成模型：AI 可直接调用的视频生成模型
 * - 图片生成模型：AI 可直接调用的图片生成模型
 *
 * 每项可「跟随主模型」（复用全局聊天配置 baseUrl/apiKey/model），或指定独立模型
 * （复用全局 baseUrl/apiKey，仅替换 model 名）。视频/图片生成默认独立指定模型。
 */
enum class FeatureModelKey(val label: String, val desc: String) {
    CONTEXT_SUMMARY("上下文总结", "对话 / 历史压缩与总结所使用的模型"),
    MEMORY_UPDATE("记忆更新", "记忆库自动沉淀与检索所使用的模型"),
    PERSONA_INCUBATE("AI 人格孵化", "灵魂卡自动孵化蒸馏所使用的模型"),
    VIDEO_GEN("视频生成模型", "AI 可直接调用的视频生成模型"),
    IMAGE_GEN("图片生成模型", "AI 可直接调用的图片生成模型"),
}

data class FeatureModelBinding(
    val useGlobal: Boolean = true, // true=跟随主模型；false=使用下方独立模型
    val model: String = "",        // 独立模型名（useGlobal=false 时有效）
)

data class QuroFeatureModelConfig(
    val bindings: Map<FeatureModelKey, FeatureModelBinding> =
        FeatureModelKey.values().associateWith { FeatureModelBinding() },
) {
    fun binding(key: FeatureModelKey): FeatureModelBinding =
        bindings[key] ?: FeatureModelBinding()
}

class QuroFeatureModelConfigRepository(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("quro_feature_model_config", Context.MODE_PRIVATE)

    fun load(): QuroFeatureModelConfig {
        val map = FeatureModelKey.values().associateWith { key ->
            val p = "${key.name}_"
            FeatureModelBinding(
                useGlobal = prefs.getBoolean("${p}use_global", true),
                model = prefs.getString("${p}model", "") ?: "",
            )
        }
        return QuroFeatureModelConfig(map)
    }

    fun save(cfg: QuroFeatureModelConfig) = prefs.edit {
        cfg.bindings.forEach { (key, b) ->
            val p = "${key.name}_"
            putBoolean("${p}use_global", b.useGlobal)
            putString("${p}model", b.model)
        }
    }

    fun update(key: FeatureModelKey, binding: FeatureModelBinding) {
        val cur = load()
        save(cur.copy(bindings = cur.bindings + (key to binding)))
    }
}
