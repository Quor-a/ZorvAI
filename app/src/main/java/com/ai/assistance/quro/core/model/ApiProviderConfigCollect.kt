package com.ai.assistance.quro.core.model

import java.net.URI

/**
 * 单条「端点选项」：一个可选的预设 endpoint 与其展示标签。
 *
 * 注意：本工程（Zorv AI）的 [com.ai.assistance.quro.core.network.QuroLlmClient]
 * 会自行在 baseUrl 之后追加 `/chat/completions`，因此这里存储的 endpoint 一律只保留
 * **基址**（去掉尾部 `/chat/completions`）。例如 OPENAI 存 `https://api.openai.com/v1`，
 * 由 QuroLlmClient 追加后恰好得到 `https://api.openai.com/v1/chat/completions`。
 */
data class ProviderEndpointOption(
    val endpoint: String,
    val label: String,
)

/**
 * 单个服务商的内置默认配置。
 *
 * @param providerType 服务商枚举
 * @param defaultModelName 默认模型名（为空表示不预填）
 * @param defaultApiEndpoint 默认 API 基址（**不含** `/chat/completions`）
 * @param endpointOptions 可选的预设端点列表（基址，不含 `/chat/completions`）
 * @param requiresApiKey 是否默认需要 API Key（本地回环类默认 false）
 */
data class ProviderApiConfig(
    val providerType: ApiProviderType,
    val defaultModelName: String = "",
    val defaultApiEndpoint: String = "",
    val endpointOptions: List<ProviderEndpointOption> = emptyList(),
    val requiresApiKey: Boolean = true,
)

/**
 * 各服务商的默认配置集合。
 *
 * 所有 `defaultApiEndpoint` 与 `endpointOptions.endpoint` 仅保留基址
 * （不含尾部 `/chat/completions`），由 [QuroLlmClient] 追加。
 * `requiresApiKey` 对本地回环类端点返回 false。
 */
object ApiProviderConfigs {
    private val configs: Map<ApiProviderType, ProviderApiConfig> = listOf(
        ProviderApiConfig(
            providerType = ApiProviderType.OPENAI,
            defaultModelName = "gpt-4o",
            defaultApiEndpoint = "https://api.openai.com/v1"
        ),
        ProviderApiConfig(
            providerType = ApiProviderType.OPENAI_RESPONSES,
            defaultModelName = "gpt-4o",
            defaultApiEndpoint = "https://api.openai.com/v1/responses"
        ),
        ProviderApiConfig(
            providerType = ApiProviderType.OPENAI_RESPONSES_GENERIC,
            defaultModelName = "",
            defaultApiEndpoint = ""
        ),
        ProviderApiConfig(
            providerType = ApiProviderType.OPENAI_GENERIC,
            defaultModelName = "",
            defaultApiEndpoint = ""
        ),
        ProviderApiConfig(
            providerType = ApiProviderType.ANTHROPIC,
            defaultModelName = "claude-3-opus-20240229",
            defaultApiEndpoint = "https://api.anthropic.com/v1/messages"
        ),
        ProviderApiConfig(
            providerType = ApiProviderType.ANTHROPIC_GENERIC,
            defaultModelName = "",
            defaultApiEndpoint = ""
        ),
        ProviderApiConfig(
            providerType = ApiProviderType.GOOGLE,
            defaultModelName = "gemini-2.0-flash",
            defaultApiEndpoint = "https://generativelanguage.googleapis.com/v1beta/models"
        ),
        ProviderApiConfig(
            providerType = ApiProviderType.GEMINI_GENERIC,
            defaultModelName = "gemini-2.0-flash",
            defaultApiEndpoint = ""
        ),
        ProviderApiConfig(
            providerType = ApiProviderType.DEEPSEEK,
            defaultModelName = "deepseek-v4-flash",
            defaultApiEndpoint = "https://api.deepseek.com/v1"
        ),
        ProviderApiConfig(
            providerType = ApiProviderType.BAIDU,
            defaultModelName = "ernie-bot-4",
            defaultApiEndpoint = "https://aip.baidubce.com/rpc/2.0/ai_custom/v1/wenxinworkshop/chat"
        ),
        ProviderApiConfig(
            providerType = ApiProviderType.ALIYUN,
            defaultModelName = "qwen-max",
            defaultApiEndpoint = "https://dashscope.aliyuncs.com/compatible-mode/v1"
        ),
        ProviderApiConfig(
            providerType = ApiProviderType.XUNFEI,
            defaultModelName = "spark3.5",
            defaultApiEndpoint = "https://spark-api-open.xf-yun.com/v2"
        ),
        ProviderApiConfig(
            providerType = ApiProviderType.ZHIPU,
            defaultModelName = "glm-4.5",
            defaultApiEndpoint = "https://open.bigmodel.cn/api/paas/v4",
            endpointOptions = listOf(
                ProviderEndpointOption(
                    endpoint = "https://open.bigmodel.cn/api/paas/v4",
                    label = "CN standard"
                ),
                ProviderEndpointOption(
                    endpoint = "https://open.bigmodel.cn/api/coding/paas/v4",
                    label = "CN coding"
                ),
                ProviderEndpointOption(
                    endpoint = "https://api.z.ai/api/paas/v4",
                    label = "International standard"
                ),
                ProviderEndpointOption(
                    endpoint = "https://api.z.ai/api/coding/paas/v4",
                    label = "International coding"
                )
            )
        ),
        ProviderApiConfig(
            providerType = ApiProviderType.BAICHUAN,
            defaultModelName = "baichuan4",
            defaultApiEndpoint = "https://api.baichuan-ai.com/v1"
        ),
        ProviderApiConfig(
            providerType = ApiProviderType.MOONSHOT,
            defaultModelName = "moonshot-v1-128k",
            defaultApiEndpoint = "https://api.moonshot.cn/v1",
            endpointOptions = listOf(
                ProviderEndpointOption(
                    endpoint = "https://api.moonshot.cn/v1",
                    label = "China (moonshot.cn)"
                ),
                ProviderEndpointOption(
                    endpoint = "https://api.moonshot.ai/v1",
                    label = "International (moonshot.ai)"
                ),
                ProviderEndpointOption(
                    endpoint = "https://api.kimi.com/coding/v1",
                    label = "Kimi Code (api.kimi.com)"
                )
            )
        ),
        ProviderApiConfig(
            providerType = ApiProviderType.MIMO,
            defaultModelName = "mimo-v2.5-pro",
            defaultApiEndpoint = "https://api.xiaomimimo.com/v1"
        ),
        ProviderApiConfig(
            providerType = ApiProviderType.MISTRAL,
            defaultModelName = "codestral-latest",
            defaultApiEndpoint = "https://codestral.mistral.ai/v1"
        ),
        ProviderApiConfig(
            providerType = ApiProviderType.SILICONFLOW,
            defaultModelName = "yi-1.5-34b",
            defaultApiEndpoint = "https://api.siliconflow.cn/v1"
        ),
        ProviderApiConfig(
            providerType = ApiProviderType.IFLOW,
            defaultModelName = "TBStars2-200B-A13B",
            defaultApiEndpoint = "https://apis.iflow.cn/v1"
        ),
        ProviderApiConfig(
            providerType = ApiProviderType.OPENROUTER,
            defaultModelName = "google/gemini-pro",
            defaultApiEndpoint = "https://openrouter.ai/api/v1"
        ),
        ProviderApiConfig(
            providerType = ApiProviderType.FOUR_ROUTER,
            defaultModelName = "gpt-5.4-mini",
            defaultApiEndpoint = "https://4router.net/v1"
        ),
        ProviderApiConfig(
            providerType = ApiProviderType.NOUS_PORTAL,
            defaultModelName = "",
            defaultApiEndpoint = "https://inference-api.nousresearch.com/v1"
        ),
        ProviderApiConfig(
            providerType = ApiProviderType.INFINIAI,
            defaultModelName = "infini-mini",
            defaultApiEndpoint = "https://cloud.infini-ai.com/maas/v1"
        ),
        ProviderApiConfig(
            providerType = ApiProviderType.ALIPAY_BAILING,
            defaultModelName = "Ling-1T",
            defaultApiEndpoint = "https://api.tbox.cn/api/llm/v1"
        ),
        ProviderApiConfig(
            providerType = ApiProviderType.DOUBAO,
            defaultModelName = "Doubao-pro-4k",
            defaultApiEndpoint = "https://ark.cn-beijing.volces.com/api/v3",
            endpointOptions = listOf(
                ProviderEndpointOption(
                    endpoint = "https://ark.cn-beijing.volces.com/api/v3",
                    label = "CN standard"
                ),
                ProviderEndpointOption(
                    endpoint = "https://ark.cn-beijing.volces.com/api/coding/v3",
                    label = "CN coding"
                )
            )
        ),
        ProviderApiConfig(
            providerType = ApiProviderType.NVIDIA,
            defaultModelName = "nvidia/nemotron-3-nano-30b-a3b",
            defaultApiEndpoint = "https://integrate.api.nvidia.com/v1"
        ),
        ProviderApiConfig(
            providerType = ApiProviderType.LMSTUDIO,
            defaultModelName = "meta-llama-3.1-8b-instruct",
            defaultApiEndpoint = "http://localhost:1234/v1",
            requiresApiKey = false
        ),
        ProviderApiConfig(
            providerType = ApiProviderType.OLLAMA,
            defaultModelName = "",
            defaultApiEndpoint = "http://localhost:11434/v1",
            requiresApiKey = false
        ),
        ProviderApiConfig(
            providerType = ApiProviderType.OPENAI_LOCAL,
            defaultModelName = "",
            defaultApiEndpoint = "http://localhost:8000/v1",
            requiresApiKey = false
        ),
        ProviderApiConfig(
            providerType = ApiProviderType.MNN,
            defaultModelName = "",
            defaultApiEndpoint = "",
            requiresApiKey = false
        ),
        ProviderApiConfig(
            providerType = ApiProviderType.LLAMA_CPP,
            defaultModelName = "",
            defaultApiEndpoint = "",
            requiresApiKey = false
        ),
        ProviderApiConfig(
            providerType = ApiProviderType.PPINFRA,
            defaultModelName = "gpt-4o-mini",
            defaultApiEndpoint = "https://api.ppinfra.com/openai/v1"
        ),
        ProviderApiConfig(
            providerType = ApiProviderType.NOVITA,
            defaultModelName = "moonshotai/kimi-k2.5",
            defaultApiEndpoint = "https://api.novita.ai/openai/v1",
            endpointOptions = listOf(
                ProviderEndpointOption(
                    endpoint = "https://api.novita.ai/openai/v1",
                    label = "OpenAI-compatible"
                ),
                ProviderEndpointOption(
                    endpoint = "https://api.novita.ai/anthropic/v1/messages",
                    label = "Anthropic-compatible"
                )
            )
        ),
        ProviderApiConfig(
            providerType = ApiProviderType.OTHER,
            defaultModelName = "",
            defaultApiEndpoint = ""
        )
    ).associateBy(ProviderApiConfig::providerType)

    /** 获取某服务商的内置配置；未配置时返回该类型的空配置。 */
    fun get(providerType: ApiProviderType): ProviderApiConfig {
        return configs[providerType] ?: ProviderApiConfig(providerType = providerType)
    }

    /** 获取默认模型名（可能为空）。 */
    fun getDefaultModelName(providerType: ApiProviderType): String {
        return get(providerType).defaultModelName
    }

    /** 获取默认 API 基址（不含 /chat/completions，可能为空）。 */
    fun getDefaultApiEndpoint(providerType: ApiProviderType): String {
        return get(providerType).defaultApiEndpoint
    }

    /** 获取预设端点选项列表；若无则返回 null。 */
    fun getEndpointOptions(providerType: ApiProviderType): List<ProviderEndpointOption>? {
        return get(providerType).endpointOptions.takeIf { it.isNotEmpty() }
    }

    /** 判断给定服务商 + 端点是否需要 API Key（回环地址一律不需要）。 */
    fun requiresApiKey(providerType: ApiProviderType, apiEndpoint: String = ""): Boolean {
        if (!get(providerType).requiresApiKey) {
            return false
        }
        return !isLoopbackEndpoint(apiEndpoint)
    }

    /** 按 providerTypeId（枚举名）判断是否需要 API Key。 */
    fun requiresApiKey(providerTypeId: String, apiEndpoint: String = ""): Boolean {
        val providerType = ApiProviderType.fromProviderTypeId(providerTypeId)
            ?: return !isLoopbackEndpoint(apiEndpoint)
        return requiresApiKey(providerType, apiEndpoint)
    }

    /** 该模型名是否为某服务商的默认模型名。 */
    fun isDefaultModelName(modelName: String): Boolean {
        return configs.values.any { it.defaultModelName == modelName }
    }

    /** 该端点是否为某服务商的默认端点。 */
    fun isDefaultApiEndpoint(endpoint: String): Boolean {
        return configs.values.any { it.defaultApiEndpoint == endpoint }
    }

    /** 判断端点是否为回环地址（localhost / 127.0.0.1 / ::1 / 0.0.0.0 / 10.0.2.2）。 */
    fun isLoopbackEndpoint(apiEndpoint: String): Boolean {
        if (apiEndpoint.isBlank()) {
            return false
        }
        val normalizedEndpoint = apiEndpoint.trim().lowercase()
        return try {
            val host = URI(apiEndpoint).host
            if (host != null) {
                isLoopbackHost(host)
            } else {
                isLoopbackEndpointText(normalizedEndpoint)
            }
        } catch (_: Exception) {
            isLoopbackEndpointText(normalizedEndpoint)
        }
    }

    private fun isLoopbackHost(host: String): Boolean {
        return when (host.lowercase().trim('[', ']')) {
            "localhost",
            "127.0.0.1",
            "::1",
            "0.0.0.0",
            "10.0.2.2" -> true
            else -> false
        }
    }

    private fun isLoopbackEndpointText(apiEndpoint: String): Boolean {
        return apiEndpoint.startsWith("localhost:") ||
            apiEndpoint.startsWith("127.0.0.1:") ||
            apiEndpoint.startsWith("[::1]:") ||
            apiEndpoint.startsWith("::1:") ||
            apiEndpoint.startsWith("0.0.0.0:") ||
            apiEndpoint.startsWith("10.0.2.2:")
    }
}
