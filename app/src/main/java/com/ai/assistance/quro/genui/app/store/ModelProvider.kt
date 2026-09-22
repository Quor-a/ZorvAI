package com.ai.assistance.quro.genui.app.store

import org.json.JSONObject

/**
 * 模型供应商协议类型。
 * 与 ZorvAI 的 ApiProviderType 思路一致：协议只有三种，
 * 任何厂商都能映射到其中之一（端点 + 协议 + Key 三元组即可接入）。
 */
enum class Protocol(val label: String) {
    OPENAI("OpenAI 兼容"),      // /chat/completions —— OpenAI / DeepSeek / Moonshot / GLM / Ollama / vLLM …
    ANTHROPIC("Anthropic"),     // /v1/messages
    GEMINI("Gemini");           // :generateContent
}

/**
 * 一个可用的模型服务（供应商）配置。
 *
 * 采样参数（[temperature] / [topP] / [maxTokens]）与能力标记（[supportsTools] /
 * [supportsVision]）都是可选的：旧配置读进来会落到默认值，不需要迁移脚本。
 */
data class ModelProvider(
    val id: String,
    var name: String,          // 展示名，如 "DeepSeek"
    var protocol: Protocol,    // 协议
    var baseUrl: String,       // 如 https://api.deepseek.com/v1
    var apiKey: String,        // 本地明文存储于应用私有目录
    var model: String,         // 模型 ID，如 deepseek-chat
    var enabled: Boolean = true,
    // —— 采样参数 ——
    var temperature: Float = 0.7f,
    var topP: Float = 1.0f,
    var maxTokens: Int = 8192,       // 0 = 不限制（交给服务端默认）
    // —— Agent 行为参数（参考 ZorvAI 的模型配置：工具轮次 + 上下文窗口）——
    var maxToolRounds: Int = 0,      // 单轮生成中决策阶段工具轮数（0 = 不限制，模型自定；正数仅作软提醒）
    var contextChars: Int = 4000,    // 续写时喂给模型的尾部字符数（上下文窗口）
    // —— 能力标记（影响 UI 提示与降级策略）——
    var supportsTools: Boolean = true,
    var supportsVision: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("protocol", protocol.name)
        .put("baseUrl", baseUrl).put("apiKey", apiKey)
        .put("model", model).put("enabled", enabled)
        .put("temperature", temperature.toDouble())
        .put("topP", topP.toDouble())
        .put("maxTokens", maxTokens)
        .put("maxToolRounds", maxToolRounds)
        .put("contextChars", contextChars)
        .put("supportsTools", supportsTools)
        .put("supportsVision", supportsVision)

    companion object {
        fun fromJson(o: JSONObject) = ModelProvider(
            id = o.optString("id"),
            name = o.optString("name", "未命名"),
            protocol = runCatching { Protocol.valueOf(o.optString("protocol", "OPENAI")) }.getOrDefault(Protocol.OPENAI),
            baseUrl = o.optString("baseUrl"),
            apiKey = o.optString("apiKey"),
            model = o.optString("model"),
            enabled = o.optBoolean("enabled", true),
            temperature = o.optDouble("temperature", 0.7).toFloat(),
            topP = o.optDouble("topP", 1.0).toFloat(),
            maxTokens = o.optInt("maxTokens", 8192),
            maxToolRounds = o.optInt("maxToolRounds", 0),
            contextChars = o.optInt("contextChars", 3000),
            supportsTools = o.optBoolean("supportsTools", true),
            supportsVision = o.optBoolean("supportsVision", false)
        )
    }
}

/**
 * 专项模型分派（对应设计稿屏 5 顶部的"专项模型"）：
 * 不同任务可指定不同供应商。
 */
data class FeatureRouting(
    var mainProviderId: String = "",    // 主脑：写 UI 的模型
    var fastProviderId: String = ""     // 快速：标题生成 / 意图预处理
)

/**
 * 一次 A2UI 生成的产物 = 界面栈里的一张"纸"。
 */
data class GeneratedPage(
    val id: String,
    var title: String,      // AI 在 HTML <title> 里写的，或截取首屏文本
    val html: String,       // 完整 HTML 文档
    val model: String,
    val ts: Long
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("title", title).put("html", html)
        .put("model", model).put("ts", ts)

    companion object {
        fun fromJson(o: JSONObject) = GeneratedPage(
            id = o.optString("id"), title = o.optString("title", "未命名界面"),
            html = o.optString("html"), model = o.optString("model"), ts = o.optLong("ts")
        )
    }
}
