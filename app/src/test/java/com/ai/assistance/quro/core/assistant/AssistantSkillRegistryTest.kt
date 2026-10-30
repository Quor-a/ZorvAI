package com.ai.assistance.quro.core.assistant

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 能力表的路由契约测试。
 *
 * ## 为什么必须有
 * 能力表是「外部指令 / 快捷磁贴 / 分享 / 选中文本 / 能力广场 / AI 工具」**六面共用**的唯一事实源。
 * 它的匹配逻辑一旦走偏，症状是「用户说了一句话，落到了错误的能力上」——
 * 这种错不会崩、不会报错，只会静默干错事，靠人肉测极难发现。
 *
 * 这里钉死三件事：
 * 1. 显式调用（`/id`、`skill:id`）优先级最高；
 * 2. 关键词命中多个时取**最长**那个（长词更具体，防止「终端」抢走「终端环境」）；
 * 3. 停用的能力不参与匹配，且 [AssistantSkillRegistry.run] 会明确返回 disabled 而不是静默执行。
 */
class AssistantSkillRegistryTest {

    private fun skill(
        id: String,
        title: String = id,
        summary: String = "",
        keywords: List<String> = emptyList(),
        category: AssistantCategory = AssistantCategory.SYSTEM,
        body: (suspend (AssistantRequest) -> AssistantResult)? = null,
    ) = AssistantSkill(
        id = id,
        title = title,
        summary = summary,
        category = category,
        keywords = keywords,
        run = body ?: { AssistantResult.ok(it.source.wire) },
    )

    @Test
    fun explicitInvocationBeatsKeywords() {
        AssistantSkillRegistry.registerAll(
            listOf(
                // 关键词故意与本测试的显式 id 无关：显式调用不该被任何关键词抢走
                skill("t.kw", keywords = listOf("explicitkw")),
                skill("t.explicit", keywords = listOf("完全无关的词")),
            )
        )
        assertEquals("t.explicit", AssistantSkillRegistry.match("/t.explicit")?.id)
        assertEquals("t.explicit", AssistantSkillRegistry.match("skill:t.explicit 随便什么")?.id)
    }

    @Test
    fun longestKeywordWins() {
        // 🔴 关键词必须用本测试独有的字串：注册表是**进程级全局单例**，
        // 用「终端」这类通用词会与其他用例注册的技能等长打平（本测试第一版就栽在这里：
        // 期望 t.shell 实得 t.short，因为 t.short 也有关键词「终端」且先注册）。
        AssistantSkillRegistry.registerAll(
            listOf(
                skill("t.shell", keywords = listOf("shellcheck")),
                skill("t.env", keywords = listOf("shellcheck deep")),
            )
        )
        // 同时包含两者 → 取更长的（更具体）
        assertEquals("t.env", AssistantSkillRegistry.match("帮我跑 shellcheck deep")?.id)
        // 只含短的 → 只能命中短的
        assertEquals("t.shell", AssistantSkillRegistry.match("跑一下 shellcheck")?.id)
    }

    @Test
    fun disabledSkillIsNotMatchedAndReportsDisabled() = runBlocking {
        AssistantSkillRegistry.register(skill("t.dis", keywords = listOf("停用测试词")))
        AssistantSkillRegistry.setEnabled("t.dis", false)
        assertFalse(AssistantSkillRegistry.isEnabled("t.dis"))
        // 停用后不再参与关键词匹配
        assertTrue(AssistantSkillRegistry.match("停用测试词")?.id != "t.dis")

        // 但直接 run 必须明确告诉你「已停用」，而不是静默成功
        val r = AssistantSkillRegistry.run("t.dis", AssistantRequest())
        assertFalse(r.ok)
        assertEquals(AssistantCodes.SKILL_DISABLED, r.code)

        AssistantSkillRegistry.setEnabled("t.dis", true)
    }

    @Test
    fun searchRanksTitleOverSummary() {
        AssistantSkillRegistry.registerAll(
            listOf(
                skill("t.a", title = "找手机", summary = "最大声闹铃"),
                skill("t.b", title = "设备信息", summary = "这里也提到找手机但不是标题"),
            )
        )
        val hit = AssistantSkillRegistry.search("找手机")
        assertTrue(hit.isNotEmpty())
        assertEquals("t.a", hit.first().id)
    }

    @Test
    fun unknownSkillReturnsStableCode() = runBlocking {
        val r = AssistantSkillRegistry.run("t.not-exist-at-all", AssistantRequest())
        assertFalse(r.ok)
        assertEquals(AssistantCodes.SKILL_NOT_FOUND, r.code)
    }

    @Test
    fun exceptionInsideSkillBecomesInternalCode() = runBlocking {
        AssistantSkillRegistry.register(
            skill("t.boom") { throw IllegalStateException("炸了") }
        )
        val r = AssistantSkillRegistry.run("t.boom", AssistantRequest())
        assertFalse(r.ok)
        assertEquals(AssistantCodes.INTERNAL, r.code)
        assertTrue(r.message.contains("炸了"))
    }

    @Test
    fun resultWireContainsCodeAndSkill() {
        val r = AssistantResult.ok("t.wire", "好了", mapOf("k" to 1), elapsedMs = 12)
        val w = r.wire()
        assertTrue(w.contains("code=ok"))
        assertTrue(w.contains("skill=t.wire"))
        assertTrue(w.contains("ms=12"))
        assertNotNull(r.data["k"])
    }
}
