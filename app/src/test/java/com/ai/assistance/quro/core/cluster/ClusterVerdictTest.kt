package com.ai.assistance.quro.core.cluster

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #205 回归锁：验收/裁决的**三态判定**与**宽松 JSON 修复**。
 *
 * ## 这条线为什么是最高优先级
 *
 * 用户实测：集群跑 14 轮 / 20 万 tokens 后熔断，对话框里同时出现两���
 * ```
 * ⚠ 验收未通过：验收方未给出有效结论（输出无法解析），按不通过处理
 * ❌ 裁决不通过：验收方未给出有效结论（输出无法解析），按不通过处理
 * ```
 * 同一句话在 ARBITRATING 与 VERIFYING **各触发一次**。
 *
 * 根因：旧码 `val pass = o?.optBoolean("pass", false) ?: false` 把两件完全不同的事
 * 压成同一个结果 ——
 * 1. 验收方真的判定**不通过**（产物不合格）→ 该重做；
 * 2. 验收方输出**解析失败**（格式没对齐）→ 重做**毫无意义**，
 *    因为待验收产物一个字节都没变，重做 100 次还是同样的解析失败。
 *
 * 两者都进 REPLANNING → 空转 → 撞 maxReplan 熔断。
 */
class ClusterVerdictTest {

    // ═══════════════ 宽松 JSON 修复：只修格式，不改语义 ═══════════════

    @Test
    fun 单引号裸键能被修好() {
        val o = ClusterVerdictProtocol.loosenJson("{'pass':true,'reason':'ok'}")
        assertNotNull("单引号 JSON 修不好", o)
        val j = org.json.JSONObject(o)
        assertTrue(j.optBoolean("pass", false))
        assertEquals("ok", j.optString("reason", ""))
    }

    @Test
    fun 裸键名能被修好() {
        // Python 风格 {pass:true}
        val j = org.json.JSONObject(ClusterVerdictProtocol.loosenJson("{pass:true,reason:\"ok\"}"))
        assertTrue("裸键名修不好", j.optBoolean("pass", false))
        assertEquals("ok", j.optString("reason", ""))
    }

    @Test
    fun 尾随逗号能被修好() {
        val j = org.json.JSONObject(ClusterVerdictProtocol.loosenJson("""{"pass":true,"failed":[],"checks":[]}"""))
        assertTrue(j.optBoolean("pass", false))
        assertEquals(0, j.getJSONArray("failed").length())
    }

    @Test
    fun 全角标点能被修好() {
        val j = org.json.JSONObject(
            ClusterVerdictProtocol.loosenJson("｛\"pass\":true,\"reason\":\"全角输入\"｝")
        )
        assertTrue("全角标点修不好", j.optBoolean("pass", false))
        assertEquals("全角输入", j.optString("reason", ""))
    }

    @Test
    fun 中文引号能被修好() {
        val j = org.json.JSONObject(
            ClusterVerdictProtocol.loosenJson("{“pass”:true,“reason”:“中文引号”}")
        )
        assertTrue("中文引号修不好", j.optBoolean("pass", false))
        assertEquals("中文引号", j.optString("reason", ""))
    }

    @Test
    fun 被截断的结尾能被补回() {
        // max_tokens 截断：缺闭合括号
        val j = org.json.JSONObject(
            ClusterVerdictProtocol.loosenJson("""{"pass":true,"reason":"写了一半就断了","failed":[""")
        )
        assertTrue("截断补回失败", j.optBoolean("pass", false))
    }

    /**
     * 🔴 字符串内容**一个字都不能动**。
     *
     * 修 JSON 最大的风险是「修着修着把内容也改了」——
     * 那等于伪造验收方的原话。本仓对幻觉零容忍，所以钉死这条。
     */
    @Test
    fun 不修改字符串内容() {
        // 单引号在字符串**内部**时必须原样保留（英文缩写 don't）
        val j = org.json.JSONObject(
            ClusterVerdictProtocol.loosenJson("""{"pass":false,"reason":"don't change this"}""")
        )
        assertFalse(j.optBoolean("pass", true))
        assertEquals("don't change this", j.optString("reason", ""))
    }

    /**
     * 🔴 我们的修复层**不碰值**，只碰结构。
     *
     * `"reason": abc` 若被**我们的代码**补成 `"abc"` 就是编造验收方没说过的话。
     *
     * ⚠️ 但注意判据边界：`org.json.JSONObject` 自己就接受裸标量值
     * （`{"reason": abc}` 直接解析成 reason="abc"），这是**库既有行为**，
     * 不是本次修复引入的。所以本条断言的是**我们的输出没有额外加引号**：
     * `loosenJson` 返回的字符串里 `abc` 仍然是**裸的**（没有变成 `"abc"`）。
     */
    @Test
    fun 修复层不碰值只碰结构() {
        val fixed = ClusterVerdictProtocol.loosenJson("""{"reason": abc}""")
        assertTrue(
            "我们的修复层不该给裸值补引号（补了就是编造内容），实际输出：$fixed",
            fixed.contains(":abc") || fixed.contains(": abc")
        )
        assertFalse("不该把裸值 abc 改成带引号的 \"abc\"：$fixed", fixed.contains("\"abc\""))
    }

    @Test
    fun 已经合法的json不被破坏() {
        val good = """{"pass":true,"reason":"正常","checks":[{"index":1,"pass":true,"note":"n"}]}"""
        val j = org.json.JSONObject(ClusterVerdictProtocol.loosenJson(good))
        assertTrue(j.optBoolean("pass", false))
        assertEquals(1, j.getJSONArray("checks").length())
    }

    @Test
    fun 嵌套括号层级被正确保留() {
        val j = org.json.JSONObject(
            ClusterVerdictProtocol.loosenJson("""{"pass":true,"failed":["a","b"],"checks":[{"index":2,"pass":false}]}""")
        )
        assertEquals(2, j.getJSONArray("failed").length())
        assertFalse(j.getJSONArray("checks").getJSONObject(0).optBoolean("pass", true))
    }

    // ═══════════════ 三态判定：解析失败 ≠ 不通过 ═══════════════

    @Test
    fun 明确通过判为PASS() {
        assertEquals(
            ClusterVerdictProtocol.ClusterVerdict.PASS,
            ClusterVerdictProtocol.verdictOf("""{"pass":true,"reason":"ok"}""")
        )
    }

    @Test
    fun 明确不通过判为FAIL() {
        assertEquals(
            ClusterVerdictProtocol.ClusterVerdict.FAIL,
            ClusterVerdictProtocol.verdictOf("""{"pass":false,"reason":"缺第三条"}""")
        )
    }

    /** 🔴 核心回归：解析失败**不能**被判成 FAIL。 */
    @Test
    fun 解析失败判为UNPARSABLE而不是FAIL() {
        val v = ClusterVerdictProtocol.verdictOf("我觉得这个做得还行，应该可以通过吧")
        assertEquals(
            "解析失败被当成了业务不通过 —— 这就是 14 轮空转的直接原因",
            ClusterVerdictProtocol.ClusterVerdict.UNPARSABLE,
            v
        )
    }

    @Test
    fun 空输出判为UNPARSABLE() {
        assertEquals(ClusterVerdictProtocol.ClusterVerdict.UNPARSABLE, ClusterVerdictProtocol.verdictOf(null))
        assertEquals(ClusterVerdictProtocol.ClusterVerdict.UNPARSABLE, ClusterVerdictProtocol.verdictOf(""))
        assertEquals(ClusterVerdictProtocol.ClusterVerdict.UNPARSABLE, ClusterVerdictProtocol.verdictOf("   "))
    }

    /**
     * 🔴 有 reason 但**没有 pass** 字段 → UNPARSABLE，不能猜。
     *
     * 猜 `pass=true` 就是本仓明令禁止的「用 0 冒充 -1」式编造。
     */
    @Test
    fun 只有reason没有pass时判为UNPARSABLE() {
        val v = ClusterVerdictProtocol.verdictOf("""{"reason":"我觉得还行"}""")
        assertEquals(ClusterVerdictProtocol.ClusterVerdict.UNPARSABLE, v)
    }

    /**
     * 🔴 端侧最常见的形态：JSON 前后带废话（"这是我的验收结论：…以上。"）+ 裸键名。
     *
     * 这是真实缺口：`org.json.JSONObject` 要求整串就是 JSON，
     * 前导一句中文就整串解析失败 —— 而模型几乎总会在 JSON 外面加话。
     * 引擎内的 `firstJson` 有「按花括号配平截取」这一步所以能救，
     * 但协议层原先只做了围栏剥离，漏了这层。
     */
    @Test
    fun 格式没对齐但语义清楚时能救回来() {
        val messy = """
            这是我的验收结论：
            {pass:true, reason:"三条标准都满足了", failed:[], checks:[]}
            以上。
        """.trimIndent()
        assertEquals(
            "带前后废话 + 裸键名的输出修复后仍判不出结论",
            ClusterVerdictProtocol.ClusterVerdict.PASS,
            ClusterVerdictProtocol.verdictOf(messy)
        )
    }

    /** 同上形态但不通过时也要能判出来（别只救通过那一侧）。 */
    @Test
    fun 带废话的不通过也能救回来() {
        val messy = """我的判断如下：{pass:false, reason:"第三条缺证据", failed:["3"]} 就这样。"""
        assertEquals(
            ClusterVerdictProtocol.ClusterVerdict.FAIL,
            ClusterVerdictProtocol.verdictOf(messy)
        )
    }

    @Test
    fun 围栏包裹的json能解析() {
        val fenced = "```json\n{\"pass\":false,\"reason\":\"证据不足\"}\n```"
        assertEquals(ClusterVerdictProtocol.ClusterVerdict.FAIL, ClusterVerdictProtocol.verdictOf(fenced))
    }

    @Test
    fun 三态枚举名不与业务结论混淆() {
        // PASS/FAIL 是业务结论；UNPARSABLE 是协议失败。名字必须能一眼分开。
        assertEquals(3, ClusterVerdictProtocol.ClusterVerdict.entries.size)
        assertNotNull(ClusterVerdictProtocol.ClusterVerdict.valueOf("UNPARSABLE"))
    }
}