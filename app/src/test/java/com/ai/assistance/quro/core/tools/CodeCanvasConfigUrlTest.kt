package com.ai.assistance.quro.core.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CodeCanvasConfig.normalizeServerUrl] 单元测试。
 *
 * ## 为什么这层值得钉死
 *
 * 服务地址是**用户手输**的，写错时唯一线索就是这条函数的返回值。若它放过非法值，
 * 错误会推迟到 OkHttp 建连时才炸（`IllegalArgumentException` 或一段天书般的栈），
 * 而那时机已经晚了 —— 用户看到的是「出图失败」而不是「地址写错了」。
 * 所以校验必须**在这里**就给出明确的 null。
 *
 * ## 为什么允许带路径
 *
 * 服务端可能被挂在反向代理子路径下（`https://host/codecanvas`），端点拼接是
 * `base.trimEnd('/') + "/api/script"`，所以路径是合法输入，只有**尾部斜杠**要剥掉。
 */
class CodeCanvasConfigUrlTest {

    @Test
    fun `接受合法地址`() {
        assertEquals("http://127.0.0.1:8000", CodeCanvasConfig.normalizeServerUrl("http://127.0.0.1:8000"))
        assertEquals("https://canvas.example.com", CodeCanvasConfig.normalizeServerUrl("https://canvas.example.com"))
    }

    @Test
    fun `剥掉所有尾部斜杠`() {
        // trimEnd 剥全部而非一层：端点拼接是 base + "/api/xxx"，
        // 多余的斜杠留着会在服务端变成 "path//api/xxx"（多数框架 404）。
        assertEquals("http://127.0.0.1:8000", CodeCanvasConfig.normalizeServerUrl("http://127.0.0.1:8000/"))
        assertEquals("https://host/cc", CodeCanvasConfig.normalizeServerUrl("https://host/cc/"))
        assertEquals("https://host/cc", CodeCanvasConfig.normalizeServerUrl("https://host/cc//"))
    }

    @Test
    fun `忽略首尾空白`() {
        assertEquals("http://10.0.2.2:8000", CodeCanvasConfig.normalizeServerUrl("  http://10.0.2.2:8000  "))
    }

    @Test
    fun `拒绝缺协议的地址`() {
        // 用户很容易只写 "192.168.1.5:8000" —— OkHttp 会直接抛 IllegalArgumentException
        assertNull(CodeCanvasConfig.normalizeServerUrl("192.168.1.5:8000"))
        assertNull(CodeCanvasConfig.normalizeServerUrl("localhost:8000"))
    }

    @Test
    fun `拒绝不支持的协议`() {
        assertNull(CodeCanvasConfig.normalizeServerUrl("ws://127.0.0.1:8000"))
        assertNull(CodeCanvasConfig.normalizeServerUrl("ftp://host/x"))
    }

    @Test
    fun `拒绝空地址与只有协议头`() {
        assertNull(CodeCanvasConfig.normalizeServerUrl(""))
        assertNull(CodeCanvasConfig.normalizeServerUrl("   "))
        assertNull(CodeCanvasConfig.normalizeServerUrl("http://"))
        assertNull(CodeCanvasConfig.normalizeServerUrl("http:///api"))
    }

    @Test
    fun `拒绝含空格的地址`() {
        // 空格在 URL 里必须是 %20；用户粘进来一个空格必然是笔误，不能静默通过
        assertNull(CodeCanvasConfig.normalizeServerUrl("http://192.168.1.5: 8000"))
        assertNull(CodeCanvasConfig.normalizeServerUrl("http://my host:8000"))
    }

    @Test
    fun `工具名必须全部合法`() {
        // 名字非法 → 上游整段 400 → 所有工具一起失效（QuroToolSpecGuard 的判定）
        val names = listOf(
            CodeCanvasProbeTool().name,
            CodeCanvasScriptTool().name,
            CodeCanvasCodeCardTool().name,
            CodeCanvasMarkupTool().name,
            CodeCanvasLlmCodeTool().name,
        )
        assertEquals(5, names.toSet().size) // 互不撞名
        names.forEach {
            assertTrue("工具名非法：$it", QuroToolSpecGuard.isLegalName(it))
        }
    }

    @Test
    fun `出图工具一律不声明只读`() {
        // 写文件 / 改配置 = 有副作用。误报 true 会让它们进入并发分支抢 IO。
        listOf(
            CodeCanvasProbeTool(),
            CodeCanvasScriptTool(),
            CodeCanvasCodeCardTool(),
            CodeCanvasMarkupTool(),
            CodeCanvasLlmCodeTool(),
        ).forEach {
            assertTrue("${it.name} 不应声明 readOnly", !it.readOnly)
        }
    }

    @Test
    fun `所有工具的 schema 与描述非空`() {
        // schema 为空会走 EMPTY_SCHEMA 退化，模型看不到任何参数
        listOf(
            CodeCanvasProbeTool(),
            CodeCanvasScriptTool(),
            CodeCanvasCodeCardTool(),
            CodeCanvasMarkupTool(),
            CodeCanvasLlmCodeTool(),
        ).forEach {
            assertTrue("${it.name} 描述为空", it.description.isNotBlank())
            assertTrue("${it.name} schema 非 JSON 对象", QuroToolSpecGuard.looksLikeJsonObject(it.parametersJson))
        }
    }

    @Test
    fun `必填字段与真实参数名一致`() {
        // 参数名拼错是最隐蔽的失败：模型照 schema 填，服务端 422，而模型看不到 schema 原文
        val requiredOf = mapOf(
            CodeCanvasScriptTool() to "script",
            CodeCanvasCodeCardTool() to "code",
            CodeCanvasMarkupTool() to "html",
            CodeCanvasLlmCodeTool() to "requirement",
        )
        requiredOf.forEach { (tool, required) ->
            val props = tool.parametersJson.substringAfter("\"properties\"")
            assertTrue(
                "${tool.name} 的必填 $required 未出现在 schema 里",
                props.contains("\"$required\""),
            )
            assertTrue(
                "${tool.name} 的 required 数组未包含 $required",
                tool.parametersJson.contains("\"required\":[\"$required\"]"),
            )
        }
    }
}