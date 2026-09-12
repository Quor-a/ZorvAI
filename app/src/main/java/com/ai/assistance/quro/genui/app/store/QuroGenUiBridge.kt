package com.ai.assistance.quro.genui.app.store

import com.ai.assistance.quro.core.model.QuroModelConfig

/**
 * QuroAI 统一模型配置（QuroModelConfig）↔ GenUI 模型供应商（ModelProvider）桥接。
 *
 * QuroAI 仅维护【单一】活动模型配置（SharedPreferences quro_model_config），
 * 而 GenUI 原生支持多供应商。为避免两套配置 UI 互相打架、各写各的，强制 GenUI 侧
 * 只认一个固定 id="quro_main" 的供应商，与 QuroAI 的活动配置 1:1 映射。
 *
 * 这样 GenUI 的「模型服务」页就成了编辑 QuroAI 主模型配置的同一种 UI——
 * 在 GenUI 里改的 endpoint / key / 协议，会直接作用到文本对话等全应用模型。
 */
object QuroGenUiBridge {

    /** GenUI 侧固定供应商 id，与 QuroAI 单一配置 1:1 对应 */
    const val MAIN_ID = "quro_main"

    /** QuroAI 配置 → GenUI 供应商（单一、固定 id） */
    fun toModelProvider(cfg: QuroModelConfig): ModelProvider {
        val name = when {
            cfg.customProviderName.isNotBlank() -> cfg.customProviderName
            else -> cfg.provider
        }
        return ModelProvider(
            id = MAIN_ID,
            name = name,
            protocol = inferProtocol(cfg.provider),
            baseUrl = cfg.baseUrl,
            apiKey = cfg.apiKey,
            model = cfg.model,
            enabled = true,
            temperature = cfg.temperature,
            topP = 1.0f,
            maxTokens = cfg.maxTokens,
            maxToolRounds = cfg.maxToolRounds,
            // GenUI 的 contextChars 是“续写尾部字符数”，与 QuroAI 的 token 上下文窗口非同一量纲；
            // 这里做一个截顶映射，保证落在上游使用时的 clamp(500, 8000) 区间内，避免把 1M 当字符数。
            contextChars = if (cfg.contextWindow > 0) cfg.contextWindow.coerceAtMost(8000) else 4000,
            supportsTools = cfg.enableTools,
            supportsVision = false
        )
    }

    /**
     * GenUI 供应商 → 写回 QuroAI 配置。
     * 仅覆盖云端模型相关字段，保留本地离线引擎（local*）、上下文硬上限、技能开关等
     * QuroAI 独有字段，绝不互相覆盖。
     */
    fun toModelConfig(p: ModelProvider, existing: QuroModelConfig): QuroModelConfig {
        return existing.copy(
            provider = protocolToProvider(p.protocol),
            baseUrl = p.baseUrl,
            apiKey = p.apiKey,
            model = p.model,
            temperature = p.temperature,
            maxTokens = p.maxTokens,
            enableTools = p.supportsTools,
            maxToolRounds = p.maxToolRounds,
            customProviderName = if (p.protocol == Protocol.OPENAI && p.name.isNotBlank()) p.name else existing.customProviderName
        )
    }

    /** QuroAI provider 字符串 → GenUI 协议（非 OpenAI 兼容的全部回落 OpenAI 兼容） */
    fun inferProtocol(provider: String): Protocol = when (provider.uppercase()) {
        "ANTHROPIC" -> Protocol.ANTHROPIC
        "GEMINI" -> Protocol.GEMINI
        else -> Protocol.OPENAI   // OPENAI / OTHER / MNN / LLAMA_CPP / 自定义 一律按 OpenAI 兼容接入
    }

    /** GenUI 协议 → QuroAI provider 字符串 */
    fun protocolToProvider(p: Protocol): String = when (p) {
        Protocol.ANTHROPIC -> "ANTHROPIC"
        Protocol.GEMINI -> "GEMINI"
        Protocol.OPENAI -> "OPENAI"
    }
}
