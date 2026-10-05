package com.ai.assistance.quro.genui.aiapp.viewmodel

import com.ai.assistance.quro.genui.aiapp.core.GenUIChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「所有类型界面都画不出来 / 有时模型不通」的回归测试。
 *
 * 对应线上事故（2026-10 真机）：四条渲染通道（GenUI / A2UI / Markdown / HTML）
 * **同时**显示成代码块源码或干脆空白。逐轮版本对照（初始 9fce902 → 当前）查出
 * 三个共因，全部落在**四条通道共用**的链路上，因此表现为「所有通道一起坏」：
 *
 *  ① system 消息位置/条数错乱（5021bbe 引入，1.1.3 修了一半修出新问题）
 *  ② 渲染通道提示词自相矛盾（9fce902 引入，四通道共用）
 *  ③ 未闭合思考标签的**破坏性截断**把围栏与 JSON 一起丢掉
 *
 * 下面每组用例都直接对应其中一条，并使用真机出现过的文本形态。
 */
class GenUiRenderPipelineTest {

    private fun sys(content: String) = GenUIChatMessage("system", content)
    private fun u(content: String) = GenUIChatMessage("user", content)
    private fun a(content: String) = GenUIChatMessage("assistant", content)

    // ═══════════════ ① system 消息：唯一 + 在开头 ═══════════════

    @Test
    fun `附加指令合并进已有 system 而不是新起一条`() {
        val history = listOf(
            sys("平台基座 + 人格灵魂 + GenUiRules"),
            u("今日运势"),
            a("好的")
        )
        val api = ChatHistory.toApiMessages(history)
        val merged = ChatHistory.mergeIntoSingleSystem(api, "# 渲染规则\n必须用 genui 围栏")

        // 🔴 核心断言：system 恒为 1 条
        assertEquals("system 只能有一条，重复会让网关 400 / 丢约束", 1, merged.count { it.role == "system" })
        // 且在列表最开头
        assertEquals("system", merged.first().role)
        // 原有内容没被覆盖
        assertTrue(merged.first().content.contains("平台基座"))
        // 附加段接在末尾（近因区）
        assertTrue(merged.first().content.contains("必须用 genui 围栏"))
        // 对话顺序不变
        assertEquals("今日运势", merged[1].content)
    }

    @Test
    fun `历史里没有 system 时新建一条放在开头`() {
        val api = listOf(u("你好"))
        val merged = ChatHistory.mergeIntoSingleSystem(api, "# 渲染规则")
        assertEquals(1, merged.count { it.role == "system" })
        assertEquals("system", merged.first().role)
        assertEquals("你好", merged[1].content)
    }

    @Test
    fun `附加指令为空时原样返回不改动`() {
        val api = listOf(sys("基座"), u("x"))
        assertEquals(api, ChatHistory.mergeIntoSingleSystem(api, "   "))
    }

    @Test
    fun `历史里混入多条 system 时收敛为一条`() {
        // 防御：老库里若存下多条 system，合并后必须只剩一条
        val api = listOf(sys("基座A"), sys("基座B"), u("x"))
        val merged = ChatHistory.mergeIntoSingleSystem(api, "# 规则")
        assertEquals(1, merged.count { it.role == "system" })
        assertTrue(merged.first().content.contains("基座A"))
        assertEquals("x", merged[1].content)
    }

    @Test
    fun `合并后 system 仍是第一条非 user 之前的唯一 system`() {
        // 覆盖 OpenAI 兼容硬要求：messages[0].role == "system"
        val history = listOf(sys("基座")) + (1..5).flatMap { listOf(u("指令$it"), a("回复$it")) }
        val api = ChatHistory.toApiMessages(history)
        val merged = ChatHistory.mergeIntoSingleSystem(api, "# 规则")
        assertEquals("system", merged.first().role)
        assertEquals(1, merged.count { it.role == "system" })
    }

    // ═══════════════ ② 通道提示词自相矛盾 ═══════════════

    /**
     * 真机「AI 思考面板」原文（2026-10-05 截图）：
     * 模型把系统提示读了两遍后自己指出矛盾 ——
     * 「这条说的是"三反引号html围栏"，但又说"绝对禁止输出任何其他围栏（markdown/a2ui/html/genui 都不行）"。
     *   这里有点矛盾」
     *
     * 旧 `renderRulesMessage` 写死「markdown/a2ui/html/genui 都不行」，
     * forced=html 时就变成「必须用 html 围栏」+「html 围栏不行」。
     * 模型为此反复自我辩论、烧掉思考预算、正文被挤没 → 围栏吐不完整 → 画不出来。
     */
    @Test
    fun `锁定通道时禁列不得包含本通道自身`() {
        // ⚠️ genui 走的是另一条分支（文案是「必须走 GenUI 流程」，
        // 没有「三反引号 genui 围栏」那句、也没有「都不行」那一行），
        // 所以这里只对**有禁列**的三个通道断言。
        for (forced in listOf("a2ui", "markdown", "html")) {
            val rules = GenUiPromptProbe.renderRules(forced)
            // 本通道必须出现在「必须使用三反引号 X 围栏」里
            assertTrue(
                "forced=$forced 应包含「必须使用三反引号${forced}围栏」",
                rules.contains("必须使用三反引号${forced}围栏")
            )
            // 禁列里绝不能出现本通道
            val forbiddenLine = rules.lineSequence()
                .firstOrNull { it.contains("都不行") }
                ?: throw AssertionError("forced=$forced 未找到禁列行")
            assertFalse(
                "forced=$forced 时禁列不得含本通道：$forbiddenLine",
                forbiddenLine.contains("```$forced")
            )
        }
    }

    @Test
    fun `genui 锁定分支不得出现自相矛盾的围栏禁列`() {
        val rules = GenUiPromptProbe.renderRules("genui")
        // genui 分支禁的是 markdown/a2ui/html，唯独不能出现禁 genui 自己的措辞
        assertFalse(
            "genui 分支不应出现「都不行」式的禁列",
            rules.lineSequence().any { it.contains("都不行") }
        )
        val bannedLine = rules.lineSequence().first { it.contains("绝对禁止输出其它通道") }
        assertTrue(bannedLine.contains("markdown"))
        assertTrue(bannedLine.contains("a2ui"))
        assertTrue(bannedLine.contains("html"))
        assertFalse("禁列不得含本通道 genui", bannedLine.contains("genui"))
    }

    @Test
    fun `未锁定通道时走自选分支`() {
        val rules = GenUiPromptProbe.renderRules(null)
        assertTrue(rules.contains("输出通道选择"))
        assertTrue(rules.contains("GenUI 流程输出格式"))
        assertFalse(rules.lineSequence().any { it.contains("都不行") })
    }

    @Test
    fun `锁定通道时禁列仍需列出其余三条通道`() {
        val rules = GenUiPromptProbe.renderRules("html")
        val forbiddenLine = rules.lineSequence().first { it.contains("都不行") }
        assertTrue(forbiddenLine.contains("```genui"))
        assertTrue(forbiddenLine.contains("```a2ui"))
        assertTrue(forbiddenLine.contains("```markdown"))
        assertFalse(forbiddenLine.contains("```html"))
    }

    // ═══════════════ ③ 未闭合思考标签的破坏性截断 ═══════════════

    /**
     * 真实场景：`renderRulesContent` 教模型输出
     * `<intent>…</intent> → <plan>…</plan> → <generate>```genui {…} ```</generate>`。
     * 模型少闭合一个 `<plan>`（流式被 max_tokens 截断时最常见）时，
     * 旧实现从 `<plan>` 处**无条件截断** → 后面的围栏与整份 JSON 一起消失 → 画不出来。
     */
    @Test
    fun `未闭合标签之后仍有围栏时不得判定为可截断`() {
        val text = "<intent>想一下</intent><plan>先算运势" +
            "\n```genui\n{\"root\":{\"type\":\"card\"}}\n```"
        val at = GenUiPromptProbe.findUnclosedThinkingTag(text)
        assertTrue("应识别出未闭合的 <plan>", at != null)
        assertTrue(
            "截断点之后还有围栏+JSON，绝不能截（否则界面整块消失）",
            GenUiPromptProbe.tailHasRenderablePayload(text, at!!)
        )
    }

    @Test
    fun `未闭合标签之后只剩思考文字时允许截断`() {
        val text = "前面正文。<plan>这里只有思考，没有界面"
        val at = GenUiPromptProbe.findUnclosedThinkingTag(text)
        assertTrue(at != null)
        assertFalse(
            "尾部无围栏无 JSON，应允许截断以免思考泄漏进画布",
            GenUiPromptProbe.tailHasRenderablePayload(text, at!!)
        )
    }

    @Test
    fun `标签全部闭合时不报未闭合`() {
        val text = "<intent>a</intent><plan>b</plan><generate>```genui\n{}\n```</generate>"
        assertEquals(null, GenUiPromptProbe.findUnclosedThinkingTag(text))
    }

    @Test
    fun `同标签多次出现时取首个无后继闭标签者而非最后一个`() {
        // <plan> 出现两次：第一次闭合、第二次未闭合 → 未闭合点是第二个
        val text = "<plan>一</plan><plan>二"
        val at = GenUiPromptProbe.findUnclosedThinkingTag(text)!!
        assertTrue("应命中第二个 <plan>", text.startsWith("<plan>一</plan>", 0))
        assertEquals(text.indexOf("<plan>二"), at)
    }

    @Test
    fun `未闭合裸 JSON 也算可渲染内容`() {
        // 模型有时不带围栏、直接吐 JSON（诊断里"含genui围栏=false"是常见形态）
        val text = "<plan>写界面" + "\n{\"root\":{\"type\":\"column\"},\"id\":\"x\"}"
        val at = GenUiPromptProbe.findUnclosedThinkingTag(text)!!
        assertTrue(
            "裸 JSON 有 root 键，同样不能被截断",
            GenUiPromptProbe.tailHasRenderablePayload(text, at)
        )
    }

    @Test
    fun `段首已有围栏时不得因孤立闭合标签而丢弃界面`() {
        val text = "```genui\n{\"root\":{}}\n```</plan>"
        val closeEnd = text.indexOf("</plan>") + "</plan>".length
        assertTrue(
            "正式输出在思考内容前面时，段首已有围栏，不能再截",
            GenUiPromptProbe.headHasRenderablePayload(text, closeEnd)
        )
    }

    @Test
    fun `段首无围栏时允许按孤立闭合标签截断`() {
        val text = "一些思考文字</plan>"
        val closeEnd = text.indexOf("</plan>") + "</plan>".length
        assertFalse(GenUiPromptProbe.headHasRenderablePayload(text, closeEnd))
    }
}
