package com.ai.assistance.quro.core.network

import com.ai.assistance.quro.core.QuroChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Method

/**
 * #1116 多轮正确性回归测试（full 风味）。
 *
 * 直接验证 llama 路径的 messages→(roles, contents) 映射函数
 * [QuroLocalEngineNative.buildLlamaChatInputs]：它必须是**纯函数 + 全量映射**——
 * 把整段多轮历史原样投影，不截断、不"只取最新一条"。
 *
 * 这是本次 Bug（现象 B：llama 永远答第一条）的核心防线：只要映射全量，下游
 * applyChatTemplate / nativeApplyChatTemplate 才会把完整上下文喂给模型，多轮才能正确推进。
 * 历史上若有人把这里改成"只取最后一条 user"，多轮会瞬间退化，故用断言锁死。
 */
class LlamaChatInputTest {

    private val engine = QuroLocalEngineNative()

    private val method: Method = QuroLocalEngineNative::class.java
        .getDeclaredMethod("buildLlamaChatInputs", List::class.java)
        .apply { isAccessible = true }

    @Suppress("UNCHECKED_CAST")
    private fun build(messages: List<QuroChatMessage>): Pair<List<String>, List<String>> =
        method.invoke(engine, messages) as Pair<List<String>, List<String>>

    private fun msg(role: String, content: String) = QuroChatMessage(role, content)

    /** 单轮：system + user → 映射后两者都在。 */
    @Test
    fun singleTurn_mapsSystemAndUser() {
        val (roles, contents) = build(listOf(msg("system", "你是A"), msg("user", "你好")))
        assertEquals(listOf("system", "user"), roles)
        assertEquals(listOf("你是A", "你好"), contents)
    }

    /** 多轮：映射必须包含【全部】历史，且不被截断为仅第一条。 */
    @Test
    fun multiTurn_containsAllHistoryNotTruncatedToFirst() {
        val messages = listOf(
            msg("system", "你是A"),
            msg("user", "第一轮问题"),
            msg("assistant", "第一轮回复"),
            msg("user", "第二轮问题"),
            msg("assistant", "第二轮回复"),
            msg("user", "第三轮问题"),
        )
        val (roles, contents) = build(messages)

        // 1) 条数守恒：输入 6 条，输出 6 条，绝不被砍。
        assertEquals("映射必须 1:1 保留全部消息", 6, roles.size)
        assertEquals(6, contents.size)

        // 2) 首轮与末轮 user 都在 → 没有被"只留第一条"。
        assertTrue("漏了首轮 user", contents.contains("第一轮问题"))
        assertTrue("漏了末轮 user", contents.contains("第三轮问题"))
        assertTrue("漏了上一轮 assistant 回复（多轮推进的关键）", contents.contains("第二轮回复"))

        // 3) 角色归一化正确：user 仍是 user，assistant 仍是 assistant。
        assertEquals(
            listOf("system", "user", "assistant", "user", "assistant", "user"),
            roles
        )
    }

    /** 轮次推进：首条 user ≠ 末条 user，证明 prompt 随轮次增长而非卡在第一条。 */
    @Test
    fun roundsAdvance_firstAndLastUserDiffer() {
        val messages = listOf(
            msg("user", "问题-第1轮"),
            msg("assistant", "答-第1轮"),
            msg("user", "问题-第2轮"),
        )
        val (roles, contents) = build(messages)

        // ⚠️ QA 回归修正：user 列表必须从**映射产物**(roles/contents) 反推，绝不能从入参 messages 反推。
        // 旧写法 `messages.filter{...}` 断言的是测试自己的字面量 → 三条断言恒真，
        // 即便 buildLlamaChatInputs 退化成「只取最后一条」也照样绿灯，等于完全没锁住 #1116。
        val users = roles.indices.filter { roles[it] == "user" }.map { contents[it] }

        assertEquals("映射后应保留全部 2 条 user（被截断则说明多轮已退化）", 2, users.size)
        assertEquals("问题-第1轮", users.first())
        assertEquals("问题-第2轮", users.last())
        assertFalse("首条与末条 user 不应相同（否则模型永远答第一条）", users.first() == users.last())
        assertTrue(contents.contains("问题-第2轮"))
    }

    /** 角色归一化：未知角色（如 tool）回落为 user。 */
    @Test
    fun normalizeRole_fallsBackToUser() {
        val (roles, _) = build(listOf(msg("tool", "工具结果"), msg("assistant", "回复")))
        assertEquals(listOf("user", "assistant"), roles)
    }
}
