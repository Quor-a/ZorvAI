package com.ai.assistance.quro.core.cards

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 名册对外暴露（紧凑清单 / 按需样例）的自检。
 *
 * 背景：名册扩到 92 种后，工具描述里那份手写清单还停在 v1068 的四十来种 ——
 * 新增的组件模型完全看不见。加组件而不接这一环，等于新组件只对「碰巧见过样例」的模型有效。
 * 这组测试守住「清单 = 名册真源」，防止再次漂移。
 */
class CardSdkCatalogTest {

    @Test
    fun `紧凑清单覆盖名册全部 type`() {
        val compact = CardSdk.compactCatalog()
        CardSdk.all.forEach { spec ->
            assertTrue(
                "紧凑清单漏了 ${spec.type}（类目 ${spec.category}）",
                compact.contains(spec.type),
            )
        }
    }

    @Test
    fun `紧凑清单每个 type 只出现一次`() {
        val compact = CardSdk.compactCatalog()
        // 去掉「类目(数量): 」前缀后逐 type 计数，防止同名重复登记
        val body = compact.substringAfter(": ")
        val tokens = body.split(",", ";", " ").map { it.trim() }.filter { it.isNotEmpty() }
        // tokens 里混着 data(27) 这类类目头，单独过滤
        val typeTokens = tokens.filter { CardSdk.types.contains(it) }
        assertEquals(CardSdk.typeCount, typeTokens.size)
        assertEquals(CardSdk.typeCount, typeTokens.toSet().size)
    }

    @Test
    fun `紧凑清单标出的数量与实际类目条数一致`() {
        val compact = CardSdk.compactCatalog()
        CardSdk.byCategory.forEach { (cat, list) ->
            val m = Regex("$cat\\((\\d+)\\)").find(compact)
            assertNotNull("紧凑清单缺少类目 $cat 的计数", m)
            assertEquals("$cat 计数与名册不符", list.size, m!!.groupValues[1].toInt())
        }
    }

    @Test
    fun `紧凑清单长度在预算内`() {
        // 常驻进系统提示词的东西必须有上限，否则组件一多就把上下文吃光
        val compact = CardSdk.compactCatalog()
        assertTrue("紧凑清单过长（${compact.length} 字符），建议只保留 type 不带类目头", compact.length <= 4000)
    }

    @Test
    fun `samples 全量返回合法样例`() {
        val arr = JSONArray(CardSdk.samples(null, null, maxChars = 100000))
        assertEquals(CardSdk.all.size, arr.length())
        for (i in 0 until arr.length()) {
            val jo = arr.getJSONObject(i)
            val type = jo.getString("type")
            assertTrue("样例里 type 与名册不符：$type", type in CardSdk.types)
            val sample = JSONObject(jo.getString("sample"))
            assertEquals("样例内 type 字段不自洽：$type", type, sample.optString("type"))
        }
    }

    @Test
    fun `samples 按类目筛选只回该类`() {
        val cat = CardSdk.all.first().category
        val arr = JSONArray(CardSdk.samples(cat, null, maxChars = 100000))
        assertEquals(CardSdk.byCategory[cat]!!.size, arr.length())
        for (i in 0 until arr.length()) {
            assertEquals(cat, arr.getJSONObject(i).getString("category"))
        }
    }

    @Test
    fun `samples 按 types 精确筛选`() {
        val want = listOf("stat", "table", "gantt")
        val arr = JSONArray(CardSdk.samples(null, want, maxChars = 100000))
        assertEquals(3, arr.length())
        assertEquals(want, (0 until arr.length()).map { arr.getJSONObject(it).getString("type") }.sortedBy { want.indexOf(it) })
    }

    @Test
    fun `samples 未知类目返回可纠正的错误而不是抛异常`() {
        val out = JSONArray(CardSdk.samples("nosuchcat", null))
        assertEquals(1, out.length())
        val o = out.getJSONObject(0)
        assertTrue(o.has("error"))
        assertTrue("应回一份合法类目表让模型自我纠正", o.getJSONArray("known").length() > 0)
    }

    @Test
    fun `samples 超预算时截断且给出提示`() {
        val out = JSONArray(CardSdk.samples("data", null, maxChars = 300))
        assertTrue("应被截断", out.length() < CardSdk.byCategory["data"]!!.size)
        val last = out.getJSONObject(out.length() - 1)
        assertTrue("截断时应说明如何继续取", last.has("note") && last.getString("note").contains("缩小"))
    }

    @Test
    fun `type 数量与名册规模一致`() {
        // 不硬编码具体数量：加组件时忘了改断言本身就是一种静默失效。
        // 这里只守「去重后无重复」与「分类求和 == 总数」两条不变量。
        assertEquals(CardSdk.all.size, CardSdk.typeCount)
        assertEquals(CardSdk.typeCount, CardSdk.byCategory.values.sumOf { it.size })
    }
}