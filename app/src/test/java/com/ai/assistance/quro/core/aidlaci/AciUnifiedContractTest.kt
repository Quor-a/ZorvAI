package com.ai.assistance.quro.core.aidlaci

import com.ai.assistance.quro.core.tools.buildQuroRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * ACI 合体契约的回归测试（2026-11-12 用户实测三连）。
 *
 * 钉死的三件事：
 *  1. **schema 不许再出现重复 capability 键** —— 合体早期 call 的 string 与 server_add
 *     的 object 同键，JSON 后者覆盖前者 → 模型看到 capability 是对象 → 传对象 →
 *     旧实现 optString 把整个对象序列化成字符串当能力 id 查 → 404。
 *  2. **capability 归一必须自愈** —— 纯字符串 / 对象（id|name）/ JSON 文本三种形态
 *     都要能挖出真正的能力 id，挖不出来返回空串由调用方给指引，不能拿 JSON 去撞 404。
 *  3. **list_and_call 必须真执行** —— 此前只是把同一参数串行灌给 list+call：
 *     不传 capability 时只有清单、传对象时 call 直接 404，表现为「只回了清单没执行」。
 *  4. **HTTP 端点必须监听请求端口** —— 此前写死 ServerSocket(0)，要 8848 拿到 38457。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AciUnifiedContractTest {

    private val ctx get() = RuntimeEnvironment.getApplication()

    @Test
    fun `aci schema 里 capability 必须是字符串且没有重复键`() {
        val aci = buildQuroRegistry().get("aci")
        assertNotNull("合体后必须有 aci 统一入口", aci)
        val raw = aci!!.parametersJson

        // 重复键回归：JSONObject 解析时后者覆盖前者，光解析发现不了，必须在原文上数。
        val occurrences = Regex("\"capability\"\\s*:").findAll(raw).count()
        assertEquals("schema 里只能有一个 capability 键（重复会让 object 覆盖 string）", 1, occurrences)

        val props = JSONObject(raw).getJSONObject("properties")
        assertEquals(
            "call 的 capability 必须是 string（模型照此传纯 id）",
            "string", props.getJSONObject("capability").optString("type"),
        )
        assertEquals(
            "server_add 的能力定义必须走 capability_def（object）桥接",
            "object", props.getJSONObject("capability_def").optString("type"),
        )
        assertTrue(
            "capability 描述必须写明「纯字符串/不要传对象」，否则模型还会试对象",
            props.getJSONObject("capability").optString("description").contains("字符串"),
        )
    }

    @Test
    fun `capability 归一容忍字符串 对象与 JSON 文本`() {
        assertEquals("browser_open", normalizeAciCapabilityId("browser_open"))
        assertEquals("browser_open", normalizeAciCapabilityId("  browser_open  "))
        assertEquals("browser_open", normalizeAciCapabilityId(JSONObject().put("id", "browser_open")))
        assertEquals("browser_open", normalizeAciCapabilityId(JSONObject().put("name", "browser_open")))
        assertEquals(
            "browser_open",
            normalizeAciCapabilityId("""{"name":"browser_open"}"""),
        )
        assertEquals("挖不出来应返回空串而不是整段 JSON", "", normalizeAciCapabilityId(null))
        assertEquals("", normalizeAciCapabilityId(""))
    }

    @Test
    fun `list_and_call 缺 capability 时给出下一步指引而不是静默只列清单`() {
        val aci = buildQuroRegistry().get("aci")!!
        val out = aci.run(ctx, """{"action":"list_and_call"}""")
        assertTrue(
            "必须明确告诉模型「只列了清单，下一步怎么调」，实际=$out",
            out.contains("未指定 capability"),
        )
    }

    @Test
    fun `list_and_call 收到对象形态的 capability 也能真正调进 call`() {
        val aci = buildQuroRegistry().get("aci")!!
        val out = aci.run(
            ctx,
            """{"action":"list_and_call","capability":{"name":"browser_open"},"args":{"url":"https://example.com"}}""",
        )
        assertTrue("应当真的进入 call 阶段（而不是把对象整体序列化去撞 404），实际=$out",
            out.contains("调用结果"))
        assertFalse("对象文本不能被当成能力 id 回显出来", out.contains("\"name\":\"browser_open\""))
    }

    @Test
    fun `HTTP 端点必须监听请求端口而不是随机端口`() {
        val probe = java.net.ServerSocket(0)
        val freePort = probe.localPort
        probe.close()

        val server = QuroAciHttpServer(ctx)
        try {
            val actual = server.start(freePort)
            assertEquals("请求端口必须生效（此前写死 ServerSocket(0) 拿到随机端口）", freePort, actual)
            assertEquals(freePort, server.port)
            assertTrue(server.running)
        } finally {
            server.stop()
        }
    }
}
