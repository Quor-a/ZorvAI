package com.ai.assistance.quro.core.model

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * 模型配置：描述一个 OpenAI 兼容的聊天模型接入点。
 * 使用 SharedPreferences 持久化。
 */
data class QuroModelConfig(
    val provider: String = "OPENAI",
    val baseUrl: String = "https://api.openai.com/v1",
    val apiKey: String = "",
    val model: String = "gpt-4o-mini",
    val temperature: Float = 0.7f,
    val maxTokens: Int = 4096,
    val enableTools: Boolean = true,
    val maxToolRounds: Int = 0,           // 工具调用轮次上限：0=不限制（默认，工具调用不设次数上限，ReAct 循环持续到模型给出最终答复，内置 200 轮安全天花板防失控）；>0 时按该值封顶
    val contextWindow: Int = 16000,       // 上下文窗口（输入 token 预算）：0=不限制；非 0 时按预算从最旧轮次裁剪历史，始终保留 system（身份/人格/工具指引），避免长对话撑爆窗口被网关静默丢弃前部上下文或 tools 字段
    val customProviderName: String = "",   // 自定义厂商展示名（provider=="OTHER" 时有效）
    val localModelPath: String = "",       // 本地离线模型路径（provider 为 MNN/LLAMA_CPP 时有效）
    val useFullTools: Boolean = false,     // 完整工具集开关：false=只下发 coreSpecs（14 个，兼容多数 API 中转）；true=下发 fullSpecs（~50 个，需代理支持大 tools 负载）
)

class QuroModelConfigRepository(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("quro_model_config", Context.MODE_PRIVATE)

    fun load(): QuroModelConfig = QuroModelConfig(
        provider = prefs.getString(KEY_PROVIDER, null) ?: QuroModelConfig().provider,
        baseUrl = prefs.getString(KEY_BASE_URL, null) ?: QuroModelConfig().baseUrl,
        apiKey = prefs.getString(KEY_API_KEY, "") ?: "",
        model = prefs.getString(KEY_MODEL, null) ?: QuroModelConfig().model,
        temperature = prefs.getFloat(KEY_TEMP, QuroModelConfig().temperature),
        maxTokens = prefs.getInt(KEY_MAX_TOKENS, QuroModelConfig().maxTokens),
        enableTools = prefs.getBoolean(KEY_TOOLS, true),
        maxToolRounds = prefs.getInt(KEY_TOOL_ROUNDS, QuroModelConfig().maxToolRounds),
        contextWindow = prefs.getInt(KEY_CONTEXT_WINDOW, QuroModelConfig().contextWindow),
        customProviderName = prefs.getString(KEY_CUSTOM_PROVIDER, "") ?: "",
        localModelPath = prefs.getString(KEY_LOCAL_PATH, "") ?: "",
        useFullTools = prefs.getBoolean(KEY_FULL_TOOLS, false),
    )

    fun save(cfg: QuroModelConfig) = prefs.edit {
        putString(KEY_PROVIDER, cfg.provider)
        putString(KEY_BASE_URL, cfg.baseUrl)
        putString(KEY_API_KEY, cfg.apiKey)
        putString(KEY_MODEL, cfg.model)
        putFloat(KEY_TEMP, cfg.temperature)
        putInt(KEY_MAX_TOKENS, cfg.maxTokens)
        putBoolean(KEY_TOOLS, cfg.enableTools)
        putInt(KEY_TOOL_ROUNDS, cfg.maxToolRounds)
        putInt(KEY_CONTEXT_WINDOW, cfg.contextWindow)
        putString(KEY_CUSTOM_PROVIDER, cfg.customProviderName)
        putString(KEY_LOCAL_PATH, cfg.localModelPath)
        putBoolean(KEY_FULL_TOOLS, cfg.useFullTools)
    }

    companion object {
        private const val KEY_PROVIDER = "provider"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_MODEL = "model"
        private const val KEY_TEMP = "temperature"
        private const val KEY_MAX_TOKENS = "max_tokens"
        private const val KEY_TOOLS = "enable_tools"
        private const val KEY_TOOL_ROUNDS = "max_tool_rounds"
        private const val KEY_CONTEXT_WINDOW = "context_window"
        private const val KEY_CUSTOM_PROVIDER = "custom_provider_name"
        private const val KEY_LOCAL_PATH = "local_model_path"
        private const val KEY_FULL_TOOLS = "use_full_tools"
    }
}
