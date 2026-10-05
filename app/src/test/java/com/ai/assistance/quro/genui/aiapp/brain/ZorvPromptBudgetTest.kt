package com.ai.assistance.quro.genui.aiapp.brain

import com.ai.assistance.quro.core.QuroToolSpec
import com.ai.assistance.quro.core.memory.QuroMemoryEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ZorvPromptBudget] 的回归测试（纯逻辑，无需 Android Context）。
 *
 * 背景：这层预算曾是「写了但从没生效过」的摆设 ——
 * 旧算法 `allowedChars = (windowTokens * 0.25) * 1.7` 在默认 `contextWindow = 1048576` 下
 * 允许 445,644 字符，而 design-studio 全量只有 12,917 → **永远裁不到**；
 * 而且它从零开始算，把已用的 `GenUiRules.RULES` 38,604 字符排除在预算外。
 * 下面每条测试都是钉住这个事实，防止再退化回摆设。
 */
class ZorvPromptBudgetTest {

    // ── 工具层 ────────────────────────────────────────────────────────────

    @Test
    fun `预算未知时全量下发不收敛`() {
        val all = List(300) { spec("t$it", 500) }
        val fit = ZorvPromptBudget.fitTools(all, 0)
        assertFalse(fit.truncated)
        assertEquals(300, fit.specs.size)
    }

    @Test
    fun `大窗口模型预算够时一份不动`() {
        val all = List(258) { spec("t$it", 500) }   // 129,000 字符，实测真实量级
        // 1M 窗口 → 工具层份额 0.35 × 1.7 = 623,900 字符，够装
        val fit = ZorvPromptBudget.fitTools(all, 1_048_576)
        assertFalse(fit.truncated)
        assertEquals(258, fit.specs.size)
    }

    @Test
    fun `保守回落 32K 时必须收敛且不超预算`() {
        val all = List(258) { spec("t$it", 500) }
        val fit = ZorvPromptBudget.fitTools(all, QuroModelContextBudgetTokens.CONSERVATIVE_32K)
        assertTrue("32K 窗口装不下 129K 字符的工具层，必须收敛", fit.truncated)
        val keptChars = fit.specs.sumOf { it.description.length + it.parametersJson.length }
        assertTrue(
            "保留的工具字符 $keptChars 必须 ≤ 允许 ${fit.allowedChars}",
            keptChars <= fit.allowedChars,
        )
        assertTrue("至少要留下工具", fit.specs.isNotEmpty())
    }

    @Test
    fun `优先工具在预算不足时仍被保留`() {
        val all = buildList {
            // 先塞 300 个无关工具把预算吃光
            repeat(300) { add(spec("noise_$it", 500)) }
            add(spec("web_search", 400))
            add(spec("read_url", 400))
        }
        val fit = ZorvPromptBudget.fitTools(all, QuroModelContextBudgetTokens.SMALL_16K)
        assertTrue(fit.truncated)
        assertTrue(
            "web_search 属于 GenUI 实际依赖，不该被丢掉：${fit.specs.map { it.name }}",
            fit.specs.any { it.name == "web_search" },
        )
        assertTrue(
            "read_url 同理：${fit.specs.map { it.name }}",
            fit.specs.any { it.name == "read_url" },
        )
    }

    @Test
    fun `工具被丢弃时是整份丢弃而非裁剪描述`() {
        val all = List(258) { spec("t$it", 500, desc = "原始描述".repeat(20)) }
        val fit = ZorvPromptBudget.fitTools(all, QuroModelContextBudgetTokens.CONSERVATIVE_32K)
        assertTrue(fit.truncated)
        // 留下来的每一份 description 都必须与原始完全一致（没被压短）
        fit.specs.forEach { s ->
            val orig = all.first { it.name == s.name }
            assertEquals("description 被裁剪了", orig.description, s.description)
            assertEquals("parametersJson 被裁剪了", orig.parametersJson, s.parametersJson)
        }
    }

    @Test
    fun `预算小到一份都放不下时至少发一份`() {
        // 允许字符数被 MIN_TOOL_CHARS 兜底到 4,000，单个工具 50,000 字符 → 一份都装不下
        val huge = listOf(spec("huge", 50_000))
        val fit = ZorvPromptBudget.fitTools(huge, 1_024)
        assertTrue(fit.truncated)
        assertEquals(1, fit.specs.size)
    }

    // ── 设计技能层 ────────────────────────────────────────────────────────

    @Test
    fun `设计层预算必须扣掉 system 已用字符`() {
        // 1M 窗口：旧算法给 445,644 字符（design-studio 全量 12,917 永远裁不到）
        // 新算法要先扣掉已用的 38,604 字符组件清单
        val old = (1_048_576 * 0.25 * 1.7).toInt()
        val room = ZorvPromptBudget.designRoom(1_048_576, alreadyUsedChars = 38_604)
        assertTrue("新算法给出的额度应小于旧算法", room.allowedChars < old)
        assertEquals((1_048_576 * 0.15 * 1.7).toInt() - 38_604, room.allowedChars)
    }

    @Test
    fun `32K 窗口下设计层整层跳过而不是硬塞`() {
        // 32K → 份额 0.15 × 1.7 = 8,354 字符，system 已用 43,000 → 负数
        val room = ZorvPromptBudget.designRoom(QuroModelContextBudgetTokens.CONSERVATIVE_32K, 43_000)
        assertTrue(room.allowedChars < 0)
        assertTrue(room.fitsNothing)
    }

    @Test
    fun `预算未知时设计层不设限`() {
        val room = ZorvPromptBudget.designRoom(0, 999_999)
        assertEquals(Int.MAX_VALUE, room.allowedChars)
        assertFalse(room.fitsNothing)
    }

    @Test
    fun `剩余预算刚好够一层时不误判为放不下`() {
        val tokens = QuroModelContextBudgetTokens.CONSERVATIVE_32K
        val total = (tokens * 0.15 * 1.7).toInt()
        val room = ZorvPromptBudget.designRoom(tokens, total - ZorvPromptBudget.MIN_DESIGN_CHARS)
        assertFalse(room.fitsNothing)
        assertTrue(room.allowedChars >= ZorvPromptBudget.MIN_DESIGN_CHARS)
    }

    // ── 长期记忆 ─────────────────────────────────────────────────────────

    @Test
    fun `记忆预算内全量保留`() {
        val all = List(20) { mem("m$it", "内容".repeat(10), updatedAt = it.toLong()) }
        val fit = ZorvPromptBudget.fitMemories(all, 1_048_576)
        assertFalse(fit.truncated)
        assertEquals(20, fit.entries.size)
    }

    @Test
    fun `记忆过多时按更新时间倒序取整条`() {
        // 每条约 100 字符，预算很小 → 只能装下少数几条
        val all = List(200) { mem("m$it", "内容".repeat(48), updatedAt = it.toLong()) }
        val fit = ZorvPromptBudget.fitMemories(all, QuroModelContextBudgetTokens.SMALL_16K)
        assertTrue(fit.truncated)
        assertTrue(fit.entries.isNotEmpty())
        assertTrue(
            "保留条数必须远少于 200，实际 ${fit.entries.size}",
            fit.entries.size < 200,
        )
        // 最近更新的那条必须在（updatedAt 最大 = m199）
        assertTrue(
            "最近更新的记忆应被保留：${fit.entries.map { it.id }}",
            fit.entries.any { it.id == "m199" },
        )
        // 最旧的那条应被丢掉
        assertFalse(
            "最旧的记忆应被丢掉：${fit.entries.map { it.id }}",
            fit.entries.any { it.id == "m0" },
        )
    }

    @Test
    fun `记忆保留后仍按原始顺序输出而不是按时间倒排`() {
        // 16K 窗口的记忆层额度 = 16384×0.10×1.7 ≈ 2,785 字符，
        // 每条给 1,200 字符 → 3 条 3,600 字符必然收敛到 2 条，才会真正走到排序分支
        val all = listOf(
            mem("old", "旧内容".repeat(600), updatedAt = 100),
            mem("new", "新内容".repeat(600), updatedAt = 900),
            mem("mid", "中内容".repeat(600), updatedAt = 500),
        )
        val fit = ZorvPromptBudget.fitMemories(all, QuroModelContextBudgetTokens.SMALL_16K)
        assertTrue("3×1,200 字符应超出 2,785 额度", fit.truncated)
        assertTrue(fit.entries.size < 3)
        val ids = fit.entries.map { it.id }
        assertTrue("必须留下最近更新的那条：$ids", ids.contains("new"))
        // 断言「输出顺序 == 原文件顺序」的子序列，而不是「时间倒序」
        val sub = ids.map { all.indexOfFirst { e -> e.id == it } }
        assertEquals("必须保持原顺序，实际顺序=$ids", sub.sorted(), sub)
    }

    @Test
    fun `记忆内容绝不裁半条`() {
        val all = List(200) { mem("m$it", "内容".repeat(48), updatedAt = it.toLong()) }
        val fit = ZorvPromptBudget.fitMemories(all, QuroModelContextBudgetTokens.SMALL_16K)
        // 留下来的每一条，content 必须与原始逐字相同
        fit.entries.forEach { e ->
            assertEquals(all.first { it.id == e.id }.content, e.content)
        }
    }

    @Test
    fun `记忆预算小到一条都放不下时至少保最近一条`() {
        val all = listOf(
            mem("a", "内容".repeat(5000), updatedAt = 1),
            mem("b", "内容".repeat(5000), updatedAt = 2),
        )
        val fit = ZorvPromptBudget.fitMemories(all, 1_024)
        assertTrue(fit.truncated)
        assertEquals(1, fit.entries.size)
        assertEquals("b", fit.entries.first().id)
    }

    @Test
    fun `空记忆不收敛`() {
        val fit = ZorvPromptBudget.fitMemories(emptyList(), 1_024)
        assertFalse(fit.truncated)
        assertTrue(fit.entries.isEmpty())
    }

    // ── 「必须传已用字符」这条教训 ──────────────────────────────────────

    /**
     * 🔴 本轮真实踩过的坑：工具层第一版按**总窗口**算份额，结果 32K 下收敛后
     * 单轮输入仍是 64,068 字符 > 55,705 字符的整窗 —— 预算根本没兜住。
     * 根因：system 固定部分（44,589 字符 ≈ 26K token）本身已占整窗 80%，
     * 工具层再拿「总窗口 × 35%」就必然超限。
     *
     * 这条测试钉死「传入 alreadyUsedChars 后额度必须显著变小」。
     */
    @Test
    fun `回归_传入system已用字符后工具额度必须显著变小`() {
        val all = List(258) { spec("t$it", 500) }
        val tokens = QuroModelContextBudgetTokens.CONSERVATIVE_32K
        val withoutSystem = ZorvPromptBudget.fitTools(all, tokens, alreadyUsedChars = 0)
        val withSystem = ZorvPromptBudget.fitTools(all, tokens, alreadyUsedChars = 44_589)
        assertTrue(
            "传了 system 已用 44,589 字符后，额度必须比不传时更小",
            withSystem.allowedChars < withoutSystem.allowedChars,
        )
        // 实测值：不传 = 19,496（总窗口×0.35×1.7）；传了 = 5,002（剩余×0.45）
        assertEquals(5_002, withSystem.allowedChars)
    }

    @Test
    fun `回归_记忆层同样必须扣掉system已用`() {
        val all = List(200) { mem("m$it", "内容".repeat(48), updatedAt = it.toLong()) }
        val tokens = QuroModelContextBudgetTokens.CONSERVATIVE_32K
        val withoutSystem = ZorvPromptBudget.fitMemories(all, tokens, alreadyUsedChars = 0)
        val withSystem = ZorvPromptBudget.fitMemories(all, tokens, alreadyUsedChars = 44_589)
        assertTrue(withSystem.allowedChars < withoutSystem.allowedChars)
        assertTrue(
            "扣掉 system 后保留的记忆必须更少：${withoutSystem.entries.size} → ${withSystem.entries.size}",
            withSystem.entries.size <= withoutSystem.entries.size,
        )
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private fun spec(name: String, chars: Int, desc: String = "说明".repeat(chars / 2)) =
        QuroToolSpec(
            name = name,
            description = desc,
            parametersJson = """{"type":"object","x":"$name"}""",
        )

    private fun mem(id: String, content: String, updatedAt: Long) =
        QuroMemoryEntry(id = id, content = content, updatedAt = updatedAt, createdAt = updatedAt)

    /** 预算 token 的具名常量（避免测试里出现裸数字看不出意图）。 */
    private object QuroModelContextBudgetTokens {
        /** `QuroModelContextBudget.CONSERVATIVE_INPUT_TOKENS`：元数据缺失时的保守回落。 */
        const val CONSERVATIVE_32K = 32_768

        /** 更小的窗口，用来逼出「连优先工具都要取舍」的极端路径。 */
        const val SMALL_16K = 16_384
    }
}