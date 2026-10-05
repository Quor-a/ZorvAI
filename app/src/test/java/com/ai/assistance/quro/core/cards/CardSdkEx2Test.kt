package com.ai.assistance.quro.core.cards

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject

/**
 * 名册自检 + 第二批 12 种增强组件。
 *
 * 这一组测试的核心目的是把「改六处漏一处」钉死：新增卡片时如果漏了 CardCodec.encode
 * 的分支、漏了 serializeCard 的 cardType 分支、或样例里 type 写错，
 * [CardSdk.lint] 会报出来，而不是等到真机上「卡片能出但刷新就变空白」。
 */
class CardSdkEx2Test {

    private val batch2 = listOf(
        "gantt", "invoice", "currency", "clock", "tracker", "scoreboard",
        "vocab", "formula", "translate", "palette", "stopwatch", "barcode",
    )

    @Test
    fun `名册自检必须干净`() {
        val issues = CardSdk.lint()
        assertTrue("lint 应为空，实际报出：\n" + issues.joinToString("\n"), issues.isEmpty())
    }

    @Test
    fun `别名表自检必须干净`() {
        val issues = CardFence.lintAliases()
        assertTrue("别名指向了名册里没有的 type：\n" + issues.joinToString("\n"), issues.isEmpty())
    }

    @Test
    fun `第二批 12 种都能从样例构造`() {
        batch2.forEach { t ->
            val spec = CardSdk.byType[t] ?: throw AssertionError("名册里没有 $t")
            val built = spec.builder?.invoke(JSONObject(spec.sample))
            assertNotNull("$t 构造失败", built)
        }
    }

    @Test
    fun `第二批 12 种往返不变形`() {
        batch2.forEach { t ->
            val spec = CardSdk.byType[t] ?: throw AssertionError("名册里没有 $t")
            val built = spec.builder!!(JSONObject(spec.sample))
            val round = parseCard(serializeCard(built!!))
            assertNotNull("$t 往返后丢了", round)
            assertEquals("$t 往返后类型变了", built::class, round!!::class)
        }
    }

    @Test
    fun `样例能过整条解码链`() {
        batch2.forEach { t ->
            val spec = CardSdk.byType[t] ?: throw AssertionError("名册里没有 $t")
            val parsed = CardSdk.parse(spec.sample)
            assertNotNull("$t 样例解析失败", parsed)
            assertEquals("$t 样例解析后类型变了", spec.builder!!(JSONObject(spec.sample))!!::class, parsed!!::class)
        }
    }

    @Test
    fun `名册规模与去重结果一致`() {
        assertEquals(CardSdk.all.size, CardSdk.typeCount)
        assertTrue("第二批之后名册应 ≥ 92，实际 ${CardSdk.typeCount}", CardSdk.typeCount >= 92)
    }

    @Test
    fun `目录导出与名册同源`() {
        assertEquals(CardSdk.all.size, CardSdk.catalog().size)
        val arr = JSONArray(CardSdk.catalogJson())
        assertEquals(CardSdk.all.size, arr.length())
        batch2.forEach { t ->
            var found = false
            for (i in 0 until arr.length()) {
                if (arr.getJSONObject(i).optString("type", "") == t) found = true
            }
            assertTrue("目录导出里没有 $t", found)
        }
    }

    @Test
    fun `每个类目都被目录页认得`() {
        CardSdk.lint()
        val known = setOf("input", "data", "layout", "action", "media", "nav", "flow", "decoration", "aiwrite")
        CardSdk.all.forEach { spec ->
            assertTrue("${spec.type} 类目 ${spec.category} 不在白名单", spec.category in known)
        }
    }
}
