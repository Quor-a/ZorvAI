package com.ai.assistance.quro.core.model

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * 功能级模型绑定配置（原创，参考 FunctionalConfigManager 设计、去品牌化移植）。
 *
 * 设计要点（与对齐）：
 * - 每个 [QuroFunctionType] 可「跟随主模型」(useGlobal=true) 或指定独立模型 (useGlobal=false, model=具体模型名)。
 * - QuroAI 当前为单接入点架构（全局只有一个 baseUrl/apiKey/provider），因此「功能级配置」在
 *   语义上等价于 FunctionType→(configId, modelIndex)：configId 退化为全局主配置，
 *   modelIndex 退化为 model 字符串覆写。该约束下这是与上游一致的忠实实现。
 * - 引擎消费入口统一为 [QuroFunctionModelConfigRepository.resolveConfig]：跟随主模型时原样返回
 *   全局 QuroModelConfig，否则返回替换了 model 字段的副本。主对话 (CHAT) 恒用主模型，行为不变。
 *
 * 功能类型覆盖 FunctionType 全集（CHAT/SUMMARY/MEMORY/UI_CONTROL/TRANSLATION/GREP/
 * PERSONA_INCUBATE/IMAGE_RECOGNITION/AUDIO_RECOGNITION/VIDEO_RECOGNITION/IMAGE_GEN/VIDEO_GEN）。
 */
enum class QuroFunctionType(val label: String, val desc: String) {
    CHAT("常规对话", "主对话使用的模型（恒为主模型）"),
    SUMMARY("上下文总结", "对话 / 历史压缩与总结所使用的模型"),
    MEMORY("记忆处理", "记忆库自动沉淀与检索所使用的模型"),
    UI_CONTROL("UI 控制", "UI 自动化控制 / 屏幕理解所使用的模型"),
    TRANSLATION("翻译", "文本翻译所使用的模型"),
    GREP("代码检索", "代码检索 / 检索规划所使用的模型"),
    PERSONA_INCUBATE("人格孵化", "灵魂卡自动孵化蒸馏所使用的模型"),
    IMAGE_RECOGNITION("图像识别", "图片内容理解所使用的模型"),
    AUDIO_RECOGNITION("音频识别", "音频内容理解所使用的模型"),
    VIDEO_RECOGNITION("视频识别", "视频内容理解所使用的模型"),
    IMAGE_GEN("图片生成", "AI 可直接调用的图片生成模型"),
    VIDEO_GEN("视频生成", "AI 可直接调用的视频生成模型"),
}

data class QuroFunctionModelBinding(
    val useGlobal: Boolean = true, // true=跟随主模型；false=使用下方独立模型
    val model: String = "",        // 独立模型名（useGlobal=false 时有效）
)

class QuroFunctionModelConfigRepository(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("quro_function_model_config", Context.MODE_PRIVATE)

    /** 读取全部功能的绑定（缺省即「跟随主模型」） */
    fun load(): Map<QuroFunctionType, QuroFunctionModelBinding> =
        QuroFunctionType.values().associateWith { key ->
            QuroFunctionModelBinding(
                useGlobal = prefs.getBoolean("${key.name}_use_global", true),
                model = prefs.getString("${key.name}_model", "") ?: "",
            )
        }

    fun save(map: Map<QuroFunctionType, QuroFunctionModelBinding>) = prefs.edit {
        map.forEach { (k, b) ->
            putBoolean("${k.name}_use_global", b.useGlobal)
            putString("${k.name}_model", b.model)
        }
    }

    fun getBinding(type: QuroFunctionType): QuroFunctionModelBinding =
        QuroFunctionModelBinding(
            useGlobal = prefs.getBoolean("${type.name}_use_global", true),
            model = prefs.getString("${type.name}_model", "") ?: "",
        )

    fun setBinding(type: QuroFunctionType, binding: QuroFunctionModelBinding) = prefs.edit {
        putBoolean("${type.name}_use_global", binding.useGlobal)
        putString("${type.name}_model", binding.model)
    }

    fun resetAll() = prefs.edit {
        QuroFunctionType.values().forEach { k ->
            putBoolean("${k.name}_use_global", true)
            putString("${k.name}_model", "")
        }
    }

    /**
     * 解析某功能最终使用的配置：
     * - 跟随主模型 / 未指定模型 → 原样返回全局配置（不影响现有行为）；
     * - 指定了独立模型 → 返回替换了 model 字段的全局配置副本。
     *
     * 调用方（如 QuroAssistant.ask）据此决定实际下发给 API 的模型名，
     * 从而在不引入多接入点体系的前提下实现「按功能路由模型」。
     */
    fun resolveConfig(type: QuroFunctionType, global: QuroModelConfig): QuroModelConfig {
        val b = getBinding(type)
        return if (b.useGlobal || b.model.isBlank()) global else global.copy(model = b.model)
    }
}
