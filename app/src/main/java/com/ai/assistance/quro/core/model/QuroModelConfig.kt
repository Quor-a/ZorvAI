package com.ai.assistance.quro.core.model

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.ai.assistance.quro.core.network.QuroModelListResult
import org.json.JSONArray
import org.json.JSONObject

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
    val maxTokens: Int = 65536,            // 输出 token 上限：对齐模型真实上限 65536（原 4096 过小，长工具编排/长文会被腰斩）
    val enableTools: Boolean = true,
    val maxToolRounds: Int = 0,           // 工具调用轮次上限：0=不限制（默认，工具调用不设次数上限，ReAct 循环持续到模型给出最终答复，内置 200 轮安全天花板防失控）；>0 时按该值封顶
    val contextWindow: Int = 262144,      // 上下文窗口（输入 token 预算）：0=不限制（按模型硬输入上限 262144 作为安全顶）；非 0 时按预算从最旧轮次裁剪历史，始终保留 system。默认对齐模型真实输入上限 262144：平时不裁你的长上下文，只在逼近 262144 才裁，避免「全量发送撑爆窗口 → 上游 500 context length exceeded」。
    val maxInputTokens: Int = 262144,      // 模型硬输入上限（token）：请求总输入（system + 历史）不得超过此值，否则上游直接 500。contextWindow=0 时本值作为安全硬顶生效；contextWindow>0 时取两者较小值。属模型固有能力，不作用户配置项。
    val customProviderName: String = "",   // 自定义厂商展示名（provider=="OTHER" 时有效）
    val localModelPath: String = "",       // 本地离线模型路径（provider 为 MNN/LLAMA_CPP 时有效）
    val useFullTools: Boolean = true,      // 完整工具集开关：默认开启（全面开放，下发 fullSpecs ~50 个）；设置入口已移除，由默认全开保证工具可用
    val skillToolsEnabled: Boolean = true, // 技能可调用（function calling）总开关：true=将用户技能注册为 AI 可调用工具；false=技能仅注入系统提示词、不可被调用
    val maxSkillTools: Int = 16,           // 最多下发的技能工具数量（避免工具集过大被 API 中转静默丢弃）

    // ---- 本地离线模型独立设置（与云端隔离，互不影响） ----
    // 用户反馈"设置应该和云模型分开避免影响云模型"：此前离线和云端共用 temperature / maxTokens /
    // enableTools，改了离线就把云端也带歪。现拆出独立字段，本地路径（routeLocal）优先读这些。
    val localTemperature: Float = 0.7f,
    val localMaxTokens: Int = 2048,        // 本地生成 token 上限默认 2048（原 512 太短，思考模型连思考都不够 → 回复被腰斩；引擎仍以 2048 为硬上限，不会无限跑）
    val localEnableTools: Boolean = true,  // 离线工具调用默认开启——但离线路径只下发 memory_* 工具（见 QuroAssistant.routeLocal），
                                            // 不会把 60+ 技能工具塞给 1.2B 模型（那是之前卡死/乱码的根因）。如需彻底关闭可在此关。
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
        useFullTools = prefs.getBoolean(KEY_FULL_TOOLS, true),
        skillToolsEnabled = prefs.getBoolean(KEY_SKILL_TOOLS, true),
        localTemperature = prefs.getFloat(KEY_LOCAL_TEMP, QuroModelConfig().localTemperature),
        localMaxTokens = prefs.getInt(KEY_LOCAL_MAX_TOKENS, QuroModelConfig().localMaxTokens),
        localEnableTools = prefs.getBoolean(KEY_LOCAL_TOOLS, QuroModelConfig().localEnableTools),
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
        putBoolean(KEY_SKILL_TOOLS, cfg.skillToolsEnabled)
        putFloat(KEY_LOCAL_TEMP, cfg.localTemperature)
        putInt(KEY_LOCAL_MAX_TOKENS, cfg.localMaxTokens)
        putBoolean(KEY_LOCAL_TOOLS, cfg.localEnableTools)
    }

    /**
     * 持久化最近一次从接口拉取到的模型列表（按 baseUrl 校验失效）。
     * 进入模型面板时读缓存即可展示，无需每次联网拉取。
     */
    fun saveModelListCache(baseUrl: String, result: QuroModelListResult) {
        prefs.edit {
            val obj = JSONObject().apply {
                put("baseUrl", baseUrl)
                when (result) {
                    is QuroModelListResult.Success -> {
                        put("ok", true)
                        val arr = JSONArray()
                        result.models.forEach { arr.put(it) }
                        put("models", arr)
                    }
                    is QuroModelListResult.Error -> {
                        put("ok", false)
                        put("error", result.message)
                    }
                }
            }
            putString(KEY_MODELS_CACHE, obj.toString())
        }
    }

    /**
     * 读取缓存的模型列表；若 baseUrl 与当前不符（用户改了接入点）则返回 null，视为缓存失效。
     */
    fun loadModelListCache(baseUrl: String): QuroModelListResult? {
        val raw = prefs.getString(KEY_MODELS_CACHE, null) ?: return null
        return runCatching {
            val obj = JSONObject(raw)
            if (obj.optString("baseUrl", "") != baseUrl) return null
            if (obj.optBoolean("ok", false)) {
                val arr = obj.optJSONArray("models") ?: JSONArray()
                val models = mutableListOf<String>()
                for (i in 0 until arr.length()) models.add(arr.optString(i))
                QuroModelListResult.Success(models)
            } else {
                QuroModelListResult.Error(obj.optString("error", "缓存的拉取结果无效"))
            }
        }.getOrNull()
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
        private const val KEY_SKILL_TOOLS = "skill_tools_enabled"
        private const val KEY_LOCAL_TEMP = "local_temperature"
        private const val KEY_LOCAL_MAX_TOKENS = "local_max_tokens"
        private const val KEY_LOCAL_TOOLS = "local_enable_tools"
        private const val KEY_MODELS_CACHE = "models_cache_json"
    }
}
