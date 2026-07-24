package com.ai.assistance.quro.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM 单元测试，验证 ApiProviderConfigs（厂商预设）的关键逻辑：
 * 1. 基址适配：defaultApiEndpoint 不再带 `/chat/completions` 后缀。
 * 2. 回环地址免密钥：LMStudio / Ollama / OPENAI_LOCAL 等本地回环地址 requiresApiKey 为 false。
 * 3. 公网地址需密钥。
 * 4. fromProviderTypeId 大小写不敏感。
 */
class ApiProviderConfigsTest {

    @Test
    fun openAI_defaultEndpoint_isBaseUrlWithoutChatCompletions() {
        val endpoint = ApiProviderConfigs.getDefaultApiEndpoint(ApiProviderType.OPENAI)
        assertEquals("https://api.openai.com/v1", endpoint)
        assertFalse("OPENAI 预设不应以 /chat/completions 结尾（基址适配）", endpoint.endsWith("/chat/completions"))
    }

    @Test
    fun deepSeek_defaultEndpoint_doesNotEndWithChatCompletions() {
        val endpoint = ApiProviderConfigs.getDefaultApiEndpoint(ApiProviderType.DEEPSEEK)
        assertEquals("https://api.deepseek.com/v1", endpoint)
        assertFalse("DEEPSEEK 预设不应以 /chat/completions 结尾（基址适配）", endpoint.endsWith("/chat/completions"))
    }

    @Test
    fun lmStudio_loopbackEndpoint_requiresNoApiKey() {
        assertFalse(
            "回环本地地址 http://localhost:1234/v1 应免密钥",
            ApiProviderConfigs.requiresApiKey(ApiProviderType.LMSTUDIO, "http://localhost:1234/v1")
        )
    }

    @Test
    fun ollama_loopbackEndpoint_requiresNoApiKey() {
        assertFalse(
            "回环本地地址 http://localhost:11434/v1 应免密钥",
            ApiProviderConfigs.requiresApiKey(ApiProviderType.OLLAMA, "http://localhost:11434/v1")
        )
    }

    @Test
    fun openAI_publicEndpoint_requiresApiKey() {
        assertTrue(
            "公网地址 https://api.openai.com/v1 需要密钥",
            ApiProviderConfigs.requiresApiKey(ApiProviderType.OPENAI, "https://api.openai.com/v1")
        )
    }

    @Test
    fun deepSeek_defaultModelName_isNotEmpty() {
        val model = ApiProviderConfigs.getDefaultModelName(ApiProviderType.DEEPSEEK)
        assertNotNull(model)
        assertEquals("deepseek-v4-flash", model)
    }

    @Test
    fun openAI_defaultModelName_isNotEmpty() {
        val model = ApiProviderConfigs.getDefaultModelName(ApiProviderType.OPENAI)
        assertNotNull(model)
        assertEquals("gpt-4o", model)
    }

    @Test
    fun fromProviderTypeId_isCaseInsensitive() {
        assertEquals(ApiProviderType.OPENAI, ApiProviderType.fromProviderTypeId("openai"))
        assertEquals(ApiProviderType.OPENAI, ApiProviderType.fromProviderTypeId("OPENAI"))
        assertEquals(ApiProviderType.OLLAMA, ApiProviderType.fromProviderTypeId("Ollama"))
    }

    @Test
    fun isDefaultApiEndpoint_recognizesOpenAiBaseUrl() {
        assertTrue(
            "https://api.openai.com/v1 应为已知默认基址",
            ApiProviderConfigs.isDefaultApiEndpoint("https://api.openai.com/v1")
        )
    }

    @Test
    fun openAIResponses_defaultEndpoint_isResponsesBaseUrl() {
        val endpoint = ApiProviderConfigs.getDefaultApiEndpoint(ApiProviderType.OPENAI_RESPONSES)
        assertEquals("https://api.openai.com/v1/responses", endpoint)
        assertFalse(
            "OPENAI_RESPONSES 预设不应以 /chat/completions 结尾",
            endpoint.endsWith("/chat/completions")
        )
    }

    @Test
    fun openAILocal_loopbackEndpoint_requiresNoApiKey() {
        assertFalse(
            "回环本地地址 http://localhost:8000/v1 应免密钥",
            ApiProviderConfigs.requiresApiKey(ApiProviderType.OPENAI_LOCAL, "http://localhost:8000/v1")
        )
    }

    @Test
    fun zhipu_defaultEndpoint_doesNotEndWithChatCompletions() {
        val endpoint = ApiProviderConfigs.getDefaultApiEndpoint(ApiProviderType.ZHIPU)
        assertEquals("https://open.bigmodel.cn/api/paas/v4", endpoint)
        assertFalse(
            "ZHIPU 预设不应以 /chat/completions 结尾",
            endpoint.endsWith("/chat/completions")
        )
    }
}
