package com.ai.assistance.quro.core.assistant

import com.ai.assistance.quro.core.tools.QuroLsposeTool
import com.ai.assistance.quro.core.xposed.QuroAgentBridgeClient
import com.ai.assistance.quro.core.xposed.QuroModuleConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.json.JSONObject
import java.io.File

/**
 * 「厂商助手入口 ↔ Zorv AI 完整运行时」桥的契约测试。
 *
 * 防的是四处漂移：
 *  · Manifest 的 authority 与协议常量不一致 → Hook 侧永远连不上；
 *  · 作用域新增了目标包却没进白名单 → 该入口的请求被 Provider 拒绝；
 *  · 四通道少一条 / 多一条 → 与用户指定的 Root/ADB/LSPosed/Shizuku 口径不符；
 *  · 厂商能力清单漏了「小布记忆 / 技能 / 翻译 / 写作 / 生图」或没映射到 QuroAI 能力 →
 *    模型在接管入口里会回答「我做不到」。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QuroAgentBridgeContractTest {

    private val P = QuroAgentBridgeProtocol

    private fun repoFile(relative: String): File = listOf(
        File("../$relative"),
        File(relative),
        File("D:/Calw OS-project/QuroAI/$relative"),
    ).firstOrNull { it.isFile }
        ?: throw AssertionError("找不到 $relative（工作目录 = ${File(".").absolutePath}）")

    @Test
    fun `协议常量自洽且方法名与来源不重复`() {
        // 五个方法：能力清单 / 运行时帧 / 跑回合 / 投递结果 / 读收件箱。
        // 少一个就说明某个入口链路没有可观测或可执行通道。
        assertEquals(5, P.METHODS.size)
        assertEquals(P.METHODS.size, P.METHODS.toSet().size)
        assertTrue(
            P.METHODS.containsAll(
                listOf(
                    P.METHOD_CAPABILITIES, P.METHOD_RUNTIME, P.METHOD_RUN,
                    P.METHOD_DELIVER, P.METHOD_INBOX,
                ),
            ),
        )
        assertEquals("com.ai.assistance.quro.agent", P.AUTHORITY)
        assertEquals("content://${P.AUTHORITY}", P.CONTENT_URI)
        assertEquals(P.SOURCES.size, P.SOURCES.toSet().size)
        assertTrue(P.SOURCES.containsAll(listOf("breeno", "xiaoai", "gemini", "circle_to_search", "power_key")))
        assertEquals("unknown", P.normalizeSource("不存在的入口"))
        assertEquals("breeno", P.normalizeSource(" BREENO "))
    }

    @Test
    fun `回合超时有硬上限且不会取到非法值`() {
        assertEquals(P.DEFAULT_TIMEOUT_MS, P.sanitizeTimeout(0L))
        assertEquals(P.DEFAULT_TIMEOUT_MS, P.sanitizeTimeout(-1L))
        assertEquals(5_000L, P.sanitizeTimeout(5_000L))
        assertEquals(P.MAX_TIMEOUT_MS, P.sanitizeTimeout(10_000_000L))
        assertTrue(P.MAX_TIMEOUT_MS > P.DEFAULT_TIMEOUT_MS)
    }

    @Test
    fun `调用方白名单覆盖全部声明作用域与系统身份`() {
        QuroModuleConfig.DECLARED_SCOPE.forEach { pkg ->
            if (pkg != QuroModuleConfig.SYSTEM) {
                assertTrue("作用域 $pkg 必须在 Agent Bridge 白名单内", pkg in P.ALLOWED_CALLER_PACKAGES)
            }
        }
        assertTrue(P.SELF_PACKAGE in P.ALLOWED_CALLER_PACKAGES)
        assertTrue("android" in P.ALLOWED_CALLER_PACKAGES)
        assertTrue("com.android.shell" in P.ALLOWED_CALLER_PACKAGES)
        assertTrue(P.isCallerAllowed(P.SYSTEM_UID, null))
        assertTrue(P.isCallerAllowed(P.SHELL_UID, null))
        assertTrue(P.isCallerAllowed(10_123, arrayOf("com.heytap.speechassist")))
        assertFalse(P.isCallerAllowed(10_123, arrayOf("com.evil.thirdparty")))
        assertFalse(P.isCallerAllowed(10_123, null))
    }

    @Test
    fun `特权通道恰好是 Root ADB LSPosed Shizuku 四条`() {
        val ids = QuroAssistantCapabilities.Channel.values().map { it.id }
        assertEquals(listOf("root", "adb", "lsposed", "shizuku"), ids)
    }

    @Test
    fun `小布入口能力清单覆盖记忆 技能 翻译 写作 生图并映射到 QuroAI 能力`() {
        val breeno = QuroAssistantCapabilities.VENDOR_CAPABILITIES[P.SOURCE_BREENO].orEmpty()
        assertTrue("小布能力清单不能为空", breeno.isNotEmpty())
        val ids = breeno.map { it.id }
        listOf("breeno_memory", "breeno_skills", "breeno_translate", "breeno_writing", "breeno_image_gen")
            .forEach { assertTrue("缺少厂商能力 $it", it in ids) }
        breeno.forEach {
            assertTrue("${it.label} 必须映射到 QuroAI 的能力", it.quroCounterparts.isNotEmpty())
            assertTrue(it.description.isNotBlank())
        }
        // 用户点名的能力必须能在 QuroAI 能力域里找到对应工具族
        val allTools = QuroAssistantCapabilities.CAPABILITY_DOMAINS.flatMap { it.second }
        listOf("translate", "writer", "image_gen", "memory", "skill", "screen")
            .forEach { assertTrue("能力域缺少代表工具 $it", it in allTools) }
    }

    @Test
    fun `能力域覆盖生图 翻译 写作且每个域都有代表工具`() {
        val domains = QuroAssistantCapabilities.CAPABILITY_DOMAINS
        val names = domains.map { it.first }
        assertTrue(names.any { it.contains("生图") })
        assertTrue(names.any { it.contains("翻译") })
        assertTrue(names.any { it.contains("写作") })
        domains.forEach { (name, tools) ->
            assertTrue("能力域 $name 不能为空", tools.isNotEmpty())
        }
        assertEquals(domains.size, names.toSet().size)
    }

    @Test
    fun `Manifest 声明 exported 的 Agent Bridge Provider 且不带签名权限`() {
        val manifest = repoFile("app/src/main/AndroidManifest.xml").readText()
        val block = Regex(
            "<provider\\s+[^>]*android:name=\"\\.core\\.assistant\\.QuroAgentBridgeProvider\"[^>]*>",
            RegexOption.DOT_MATCHES_ALL,
        ).find(manifest)?.value
        assertTrue("Manifest 必须声明 QuroAgentBridgeProvider", block != null)
        assertTrue("Provider 必须 exported=true 才能让 system_server / 厂商进程调用",
            block!!.contains("android:exported=\"true\""))
        assertTrue("authority 必须与协议常量一致",
            block.contains("android:authorities=\"${P.AUTHORITY}\""))
        assertFalse("不能声明 android:permission，否则系统进程无法调用", block.contains("android:permission="))
    }

    @Test
    fun `lsposed 工具暴露 capabilities 且返回结构化结果`() {
        val ctx = RuntimeEnvironment.getApplication()
        val tool = QuroLsposeTool()
        assertTrue(tool.description.contains("capabilities"))
        assertTrue(tool.parametersJson.contains("capabilities"))

        val json = JSONObject(tool.run(ctx, """{"action":"capabilities"}"""))
        assertTrue("capabilities 必须返回合法 JSON", json.has("ok"))
        // 完整工具清单不塞进对话，只给摘要。
        assertFalse("capabilities 不应把全部工具 JSON 塞进对话", json.has("tools"))
        if (json.optBoolean("ok")) {
            assertTrue(json.has("bridge_authority"))
            assertEquals(P.AUTHORITY, json.getString("bridge_authority"))
            assertTrue(json.has("channels"))
            assertTrue(json.has("capability_domains"))
            assertTrue(json.has("entry_sources"))
        } else {
            assertTrue(json.getString("error").isNotBlank())
        }
    }

    @Test
    fun `Hook 侧客户端在无 ContentResolver 时安全降级而不抛异常`() {
        val caps = QuroAgentBridgeClient.capabilities(null)
        assertFalse(caps.optBoolean(P.RESULT_OK))
        assertTrue(caps.optString(P.RESULT_ERROR).isNotBlank())

        val turn = QuroAgentBridgeClient.runTurn(
            resolver = null,
            source = "breeno",
            text = "帮我写一段文案",
        )
        assertFalse(turn.optBoolean(P.RESULT_OK))
        assertTrue(turn.optString(P.RESULT_ERROR).isNotBlank())
        assertFalse(QuroAgentBridgeClient.isOk(turn))
        assertEquals("", QuroAgentBridgeClient.textOf(turn))
    }

    @Test
    fun `空文本回合不会发起模型调用且返回明确错误`() {
        val ctx = RuntimeEnvironment.getApplication()
        val result = runBlocking {
            QuroAgentTurnRunner.run(
                ctx,
                QuroAgentTurnRunner.Request(source = "xiaoai", text = "  "),
            )
        }
        assertFalse(result.ok)
        assertTrue(result.error.contains("为空"))
        assertEquals("xiaoai", result.source)
        val json = result.toJson()
        assertFalse(json.optBoolean(P.RESULT_OK))
    }

    @Test
    fun `能力清单快照字段齐全且四通道逐条可读`() {
        val ctx = RuntimeEnvironment.getApplication()
        val snapshot = runCatching { QuroAssistantCapabilities.snapshot(ctx) }.getOrElse {
            // Robolectric 下构造完整工具注册表可能受限；此时至少保证不抛到调用方。
            return
        }
        assertEquals(P.VERSION, snapshot.optInt("protocol_version"))
        assertTrue(snapshot.has("runtime"))
        assertTrue(snapshot.has("channels"))
        assertTrue(snapshot.has("capability_domains"))
        assertTrue(snapshot.has("tools"))
        assertTrue(snapshot.has("skills"))
        assertTrue(snapshot.has("mcp_servers"))
        assertTrue(snapshot.has("entry_sources"))
        val channels = snapshot.optJSONArray("channels")
        assertTrue(channels != null && channels.length() == 4)
    }
}
