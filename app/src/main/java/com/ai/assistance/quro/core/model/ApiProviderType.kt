package com.ai.assistance.quro.core.model

/**
 * API 提供商类型枚举。
 *
 * 枚举值顺序与名称不得修改，否则与 [ApiProviderConfigs] 的映射会错位。
 */
enum class ApiProviderType {
    OPENAI, // OpenAI (GPT系列)
    OPENAI_RESPONSES, // OpenAI Responses API
    OPENAI_RESPONSES_GENERIC, // OpenAI Responses通用（自定义端点）
    OPENAI_GENERIC, // OpenAI通用（自定义端点）
    ANTHROPIC, // Anthropic (Claude系列)
    ANTHROPIC_GENERIC, // Anthropic通用（自定义端点）
    GOOGLE, // Google (Gemini系列)
    GEMINI_GENERIC, // Gemini通用（自定义端点）
    BAIDU, // 百度 (文心一言系列)
    ALIYUN, // 阿里云 (通义千问系列)
    XUNFEI, // 讯飞 (星火认知系列)
    ZHIPU, // 智谱AI (ChatGLM系列)
    BAICHUAN, // 百川大模型
    MOONSHOT, // 月之暗面大模型
    MIMO, // Xiaomi MiMo
    DEEPSEEK, // Deepseek大模型
    MISTRAL, // Mistral AI (Codestral等)
    SILICONFLOW, // 硅基流动
    IFLOW, // iFlow
    OPENROUTER, // OpenRouter (多模型聚合)
    FOUR_ROUTER, // 4Router
    NOUS_PORTAL, // Nous Portal / Inference API
    INFINIAI, // 无问芯穹
    ALIPAY_BAILING, // 支付宝百灵大模型
    DOUBAO, // 豆包（火山模型）
    NVIDIA, // NVIDIA API Catalog / NIM
    LMSTUDIO, // LM Studio本地模型服务
    OLLAMA, // Ollama 本地/私有部署服务（OpenAI兼容）
    OPENAI_LOCAL, // OpenAI兼容本地模型服务
    MNN, // MNN本地推理引擎
    LLAMA_CPP, // llama.cpp 本地推理引擎
    PPINFRA, // 派欧云
    NOVITA, // Novita AI
    OTHER; // 其他提供商（自定义端点）

    companion object {
        /**
         * 根据 providerTypeId（枚举 name，大小写不敏感）解析对应的枚举值。
         * 空串返回 null。
         */
        fun fromProviderTypeId(providerTypeId: String): ApiProviderType? {
            val normalized = providerTypeId.trim()
            if (normalized.isEmpty()) {
                return null
            }
            return values().firstOrNull {
                it.name.equals(normalized, ignoreCase = true)
            }
        }
    }
}
