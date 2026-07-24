package com.ai.assistance.quro.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * QuroModelListFetcher 解析逻辑的 JVM 单测（原创）。
 * 仅验证 parseModels 对 OpenAI 格式模型列表 JSON 的解析，无网络/Android 依赖。
 */
class QuroModelListFetcherTest {

    private val fetcher = QuroModelListFetcher()

    @Test
    fun parseModels_standardOpenAIFormat_returnsIds() {
        val json = """
        {
          "object": "list",
          "data": [
            {"id": "gpt-4o", "object": "model", "owned_by": "openai"},
            {"id": "gpt-4o-mini", "object": "model", "owned_by": "openai"},
            {"id": "o3-mini", "object": "model", "owned_by": "openai"}
          ]
        }
        """.trimIndent()
        val models = fetcher.parseModels(json)
        assertEquals(listOf("gpt-4o", "gpt-4o-mini", "o3-mini"), models)
    }

    @Test
    fun parseModels_filtersBlankIds() {
        val json = """
        {
          "data": [
            {"id": "deepseek-chat"},
            {"id": "   "},
            {"id": "deepseek-reasoner"}
          ]
        }
        """.trimIndent()
        val models = fetcher.parseModels(json)
        assertEquals(listOf("deepseek-chat", "deepseek-reasoner"), models)
    }

    @Test
    fun parseModels_emptyData_returnsEmptyList() {
        val json = """{"object":"list","data":[]}"""
        val models = fetcher.parseModels(json)
        assertTrue(models.isEmpty())
    }

    @Test(expected = Exception::class)
    fun parseModels_missingData_throws() {
        fetcher.parseModels("""{"object":"list"}""")
    }

    @Test(expected = Exception::class)
    fun parseModels_invalidJson_throws() {
        fetcher.parseModels("not json at all")
    }
}
