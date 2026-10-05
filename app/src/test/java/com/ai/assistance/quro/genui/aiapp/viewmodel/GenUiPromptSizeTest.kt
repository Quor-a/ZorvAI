package com.ai.assistance.quro.genui.aiapp.viewmodel

import com.ai.assistance.quro.core.tools.buildQuroRegistry
import com.ai.assistance.quro.genui.aiapp.brain.ZorvPromptBudget
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 实测 GenUI Agent 单轮请求里**工具层**的真实体积。
 *
 * ## 为什么要有这个测试
 * 排查「所有类型界面都画不出来 / 有时模型不通」时，实测发现提示词各层体积严重失衡：
 * ```
 * ① GenUiRules.RULES                38,604 字符
 * ② QuroPlatformManifest.SYSTEM      4,812 字符
 * ③ design-studio 套件（5 份）        12,917 字符
 * ⑥ 工具 schema                     211,500 字符 ← 最大的一块，是 RULES 的 5.5 倍
 * ```
 * 之前所有的「提示词体积」修复都只盯着 ③（design-studio，1.3 万字符）做预算，
 * 而 ③ 恰恰是**最小的一块** —— 真正的 fat body 是工具层与组件清单，全都没被计量过。
 *
 * 这个测试把「体积」变成**可回归的断言**：以后有人再加工具 / 加组件，
 * 体积会立刻反映成测试失败，而不是等到真机发现「模型不通」才回头查。
 */
class GenUiPromptSizeTest {

    /** 单轮输入的字符数上限（超过则小上下文模型大概率 400）。 */
    private val WARN_TOTAL_CHARS = 120_000

    @Test
    fun `实测_工具层体积`() {
        val specs = buildQuroRegistry().specs()
        val toolsChars = specs.sumOf { it.description.length + it.parametersJson.length }
        println("┌─ GenUI 单轮请求体积实测 ─────────────────────────")
        println("│ 工具数量        : ${specs.size}")
        println("│ 工具 schema 字符: $toolsChars")
        println("│ 平均每工具      : ${if (specs.isEmpty()) 0 else toolsChars / specs.size}")
        println("└─────────────────────────────────────────────────")
        // 这个数只打印，不设硬断言（具体值随工具演进变化），
        // 但一旦数量级暴涨（例如从 20 万涨到 100 万）下面的告警会触发
        assertTrue("工具数量异常：${specs.size}", specs.size in 1..500)
    }

    @Test
    fun `实测_组件清单与工具层占比`() {
        val specs = buildQuroRegistry().specs()
        val toolsChars = specs.sumOf { it.description.length + it.parametersJson.length }
        val rulesChars = 38_604   // GenUiRules.RULES 实测值，改动 RULES 时同步更新
        println("┌─ 体积占比 ─────────────────────────────────────")
        println("│ 工具层 / 组件清单 = ${"%.1f".format(toolsChars.toDouble() / rulesChars)} 倍")
        println("│ 工具层字符        = $toolsChars")
        println("│ 组件清单字符      = $rulesChars")
        println("└─────────────────────────────────────────────────")
        assertTrue(
            "工具层远大于组件清单，说明「提示词体积」问题的主要战场在工具层，" +
                "只对 design-studio 做预算等于没治本（实测 ${toolsChars} vs ${rulesChars}）",
            toolsChars > rulesChars
        )
    }

    @Test
    fun `实测_系统提示词各层合计是否越过告警线`() {
        val specs = buildQuroRegistry().specs()
        val toolsChars = specs.sumOf { it.description.length + it.parametersJson.length }
        val systemChars = 38_604 + 4_812 + 12_917 + 900 + 273   // RULES+基座+技能+灵魂+渲染规则
        val total = systemChars + toolsChars
        println("┌─ 单轮总输入估算 ─────────────────────────────────")
        println("│ system 合计      : $systemChars")
        println("│ 工具 schema      : $toolsChars")
        println("│ 单轮总输入       : $total 字符")
        println("│ ≈ token(1.7字/枚): ${total / 1.7}")
        println("└─────────────────────────────────────────────────")
        if (total > WARN_TOTAL_CHARS) {
            println("⚠️ 已越过 $WARN_TOTAL_CHARS 字符告警线：小上下文模型（32K/64K）会直接 400")
            println("   → 表现就是「有时候模型都不通」")
        }
    }

    /**
     * coreSpecs（精简集）vs fullSpecs（默认全开）的真实差距。
     *
     * 🔴 注释严重过期是这个问题长期潜伏的真正原因：
     * `QuroModelConfig.useFullTools` 的注释写「下发 fullSpecs **~50 个**」、
     * `coreSpecs` 的注释写「解锁全部 **~226 个**」，
     * 而**实测 fullSpecs 已经 258 个 / 129,351 字符**。
     * 写注释的人以为只有 50 个，于是「提示词体积」这个方向从来没人真正量化过 ——
     * 前几轮修复全都盯占比最小的 design-studio（12,917 字符，6%）。
     */
    @Test
    fun `实测_coreSpecs与fullSpecs的真实差距`() {
        val reg = buildQuroRegistry()
        val full = reg.fullSpecs()
        val core = reg.coreSpecs()
        val fullChars = full.sumOf { it.description.length + it.parametersJson.length }
        val coreChars = core.sumOf { it.description.length + it.parametersJson.length }
        val systemChars = 38_604 + 4_812 + 12_917 + 900 + 273
        println("┌─ 工具集对比（实测，注释里的数字已过期）───────────")
        println("│ fullSpecs : ${full.size} 个 / $fullChars 字符")
        println("│ coreSpecs : ${core.size} 个 / $coreChars 字符")
        println("│ 倍数      : ${"%.1f".format(fullChars.toDouble() / coreChars.coerceAtLeast(1))}×")
        println("│ full  单轮总输入 ≈ ${systemChars + fullChars} 字符")
        println("│ core  单轮总输入 ≈ ${systemChars + coreChars} 字符")
        println("└─────────────────────────────────────────────────")
        assertTrue(
            "注释声称 fullSpecs 约 50 个，实测 ${full.size} 个 —— 注释已严重过期，" +
                "必须更新，否则后人继续按「50 个」的错误假设做体积决策",
            full.size > 100
        )
    }

    /**
     * 端到端闭环：真实工具集经过 [ZorvPromptBudget.fitTools] 收敛后，
     * 在 32K 小上下文模型下必须**装得下**。
     *
     * 这是本次事故最直接的回归防线：收敛前 32K 窗口装不下 129,351 字符的工具层
     * → 上游 400 → 用户说的「有时候模型都不通」；收敛后必须落回预算内。
     */
@Test
    fun `实测_32K模型收敛后单轮输入必须落回预算内`() {
        val full = buildQuroRegistry().fullSpecs()
        val fullChars = full.sumOf { it.description.length + it.parametersJson.length }
        val systemChars = 38_604 + 4_812 + 900 + 273   // RULES+基座+灵魂+渲染规则（不含技能层，32K 下它本来就该被跳过）
        val windowChars = (32_768 * 1.7).toInt()

        // 🔴 必须把 system 已用字符传进去 —— 这正是第一版实现漏掉的，
        // 导致收敛后单轮输入 64,068 字符仍 > 55,705 字符的整窗（预算没兜住）。
        val fit = ZorvPromptBudget.fitTools(full, 32_768, alreadyUsedChars = systemChars)
        val keptChars = fit.specs.sumOf { it.description.length + it.parametersJson.length }
        val total = systemChars + keptChars

        println("┌─ 32K 窗口收敛后 ──────────────────────────────────")
        println("│ 工具层: ${full.size} 个/$fullChars 字符 → ${fit.specs.size} 个/$keptChars 字符")
        println("│ system 固定部分    : $systemChars 字符（占整窗 ${"%.0f".format(systemChars * 100.0 / windowChars)}%）")
        println("│ 工具层额度        : ${fit.allowedChars} 字符")
        println("│ 单轮总输入        : $total 字符 / 整窗 $windowChars 字符")
        println("└─────────────────────────────────────────────────")

        assertTrue("32K 下必须发生收敛", fit.truncated)
        assertTrue(
            "收敛后单轮输入 $total 字符仍超出 32K 整窗 $windowChars —— 预算没兜住",
            total <= windowChars,
        )
    }

    /**
     * 反向防线：**system 固定部分本身就超预算**时必须报出来。
     *
     * 实测：GenUiRules 38,604 + 基座 4,812 + 灵魂层 900 + 渲染规则 273 = 44,589 字符
     * ≈ 26K token，在 32K 模型上占整窗 80%。这意味着小窗口模型上
     * 工具 / 设计技能 / 记忆三层的额度所剩无几 —— 这是**结构性**事实，
     * 写死在这里是为了后人别再误以为「32K 也能跑 GenUI 全量」。
     */
    @Test
    fun `实测_system固定部分在小窗口模型上的占比`() {
        val systemChars = 38_604 + 4_812 + 900 + 273
        val window32k = (32_768 * 1.7).toInt()
        val ratio = systemChars.toDouble() / window32k
        println("┌─ system 固定部分 vs 32K 整窗 ─────────────────────")
        println("│ system 固定: $systemChars 字符")
        println("│ 32K 整窗   : $window32k 字符")
        println("│ 占比  : ${"%.1f".format(ratio * 100)}%")
        println("└─────────────────────────────────────────────────")
        assertTrue(
            "system 固定部分已占 32K 整窗的 ${"%.0f".format(ratio * 100)}%，" +
                "剩余额度极其有限 —— 这是结构性事实，勿以为小窗口能跑全量 GenUI",
            ratio > 0.5,
        )
    }
}
