package com.ai.assistance.quro.core.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CodeCanvas 工具的**可见性**契约。
 *
 * 🔴 这组测试对应用户反馈：「新加的代码生图 AI 根本查不到」。
 *
 * ## 当时的真实状态（工具注册了，但三条路全断）
 * 1. **注册 ✅**：`QuroBuiltInTools` 里 `r.register(CodeCanvas*Tool())` 五处齐全，
 *    `fullSpecs()` 能下发（默认 `useFullTools = true`）。
 * 2. **分档 ❌**：`QuroTool.coreNames` 与 `QuroToolRouter.ALWAYS_ON` 都没有 codecanvas
 *    → 关掉「完整工具集」时模型看不到；渐进式披露模式下要绕道 tool_router。
 * 3. **分类 ❌**：`ToolCapabilityDirectory.inferCategory` 结尾是 `else -> BASIC`，
 *    `QuroToolRouter.categorize` 结尾是 `else -> null`，
 *    两者都没匹配 `codecanvas_` → **按分类列举 / match_intent 一律查不到**（最致命）。
 * 4. **提示词 ❌**：系统提示词「本版重点能力」硬编码段没有 codecanvas，
 *    也没专项指引 → 模型没有任何动机去调。
 *
 * 结果：工具躺在 `tools` 字段里，AI 却「查不到」。
 * 本测试把 2/3/4 三处钉死，避免下次新增工具又只做注册。
 */
class CodeCanvasVisibilityTest {

    private val tools = listOf(
        "codecanvas_probe",
        "codecanvas_script",
        "codecanvas_code_card",
        "codecanvas_markup",
        "codecanvas_llm_code",
    )

    private fun source(relative: String): String {
        val cands = listOf(
            java.io.File("../$relative"),
            java.io.File(relative),
            java.io.File("D:/Calw OS-project/QuroAI/$relative"),
        )
        val f = cands.firstOrNull { it.isFile }
            ?: error("找不到源文件 $relative，候选：${cands.map { it.path }}")
        return f.readText()
    }

    private val toolKt get() = source("app/src/main/java/com/ai/assistance/quro/core/tools/QuroTool.kt")
    private val routerKt get() = source("app/src/main/java/com/ai/assistance/quro/core/tools/QuroToolRouter.kt")
    private val dirKt get() = source("app/src/main/java/com/ai/assistance/quro/core/tools/ToolCapabilityDirectory.kt")
    private val vmKt get() = source("app/src/main/java/com/ai/assistance/quro/ui/QuroChatViewModel.kt")

    @Test
    fun `五个工具都已注册到内置注册表`() {
        val s = source("app/src/main/java/com/ai/assistance/quro/core/tools/QuroBuiltInTools.kt")
        // 类名不是工具名的机械驼峰化（codecanvas_code_card → CodeCanvasCodeCardTool，
        // 单词是 CodeCard 不是 Codecard），故显式列出，避免测试自己推导错。
        mapOf(
            "codecanvas_probe" to "CodeCanvasProbeTool",
            "codecanvas_script" to "CodeCanvasScriptTool",
            "codecanvas_code_card" to "CodeCanvasCodeCardTool",
            "codecanvas_markup" to "CodeCanvasMarkupTool",
            "codecanvas_llm_code" to "CodeCanvasLlmCodeTool",
        ).forEach { (toolName, className) ->
            assertTrue("$toolName（$className）未在 QuroBuiltInTools 注册",
                s.contains("register($className())"))
        }
    }

    @Test
    fun `全部进入 coreNames 核心集`() {
        val s = toolKt
        val i = s.indexOf("val coreNames = setOf(")
        val j = s.indexOf(") + uiActionToolNames()")
        val block = s.substring(i, j)
        tools.forEach {
            assertTrue("$it 不在 coreNames 里 —— 关掉「完整工具集」时模型将看不到它", block.contains("\"$it\""))
        }
    }

    @Test
    fun `出图三件套进入 ALWAYS_ON 常驻集`() {
        // probe（探活配地址）/ markup（HTML→图）/ script（脚本→图）是出图必经链路，
        // 进常驻集才能免去 tool_router 往返；code_card / llm_code 次要，可经路由按需加载。
        val s = routerKt
        listOf("codecanvas_probe", "codecanvas_markup", "codecanvas_script").forEach {
            assertTrue("$it 不在 ALWAYS_ON 里 —— 渐进式披露下模型得先 tool_router 才知道", s.contains("\"$it\""))
        }
    }

    @Test
    fun `分类规则必须命中 不能落到 BASIC 或 null`() {
        // 🔴 2026-11-02：分类逻辑已从「目录侧 + 路由侧各写一份 when」重构为单一真相源
        // ToolTaxonomy（两份手写实现会漂移，且都靠逐条打补丁，谁也没有全量覆盖约定）。
        // 因此这里从「在源码文本里找某段 when」改成**行为断言**：
        // 直接问分类器「这个工具归哪」，比匹配源码字符串更强，也不会再被重构打穿。
        //
        // 原契约不变：codecanvas_* 必须明确归到 AI_CAPABILITIES，
        // 不能落到兜底（旧 inferCategory 的 else -> BASIC 查得到但分类错，
        // 旧 categorize 的 else -> null 则是从路由索引彻底消失）。
        tools.forEach { name ->
            assertTrue("$name 没有被任何分类规则覆盖（会从分类检索里消失）", ToolTaxonomy.isClassified(name))
            assertEquals(
                "$name 应归 AI_CAPABILITIES（与 image_gen/video_gen 同为「产出图像」），实际 ${ToolTaxonomy.categoryOf(name)}",
                ToolCapabilityDirectory.ToolCategory.AI_CAPABILITIES,
                ToolTaxonomy.categoryOf(name),
            )
        }
    }

    @Test
    fun `分类规则不能被 get_ 或兜底规则抢走`() {
        // 旧实现在 when 里靠书写顺序保证「codecanvas_ 规则排在 get_* 和 else 兜底之前」。
        // 新架构里顺序由 ToolTaxonomy.RULES 的排列保证，这里直接断言结果：
        // 若有人把宽前缀（get_ / 通用兜底）排到前面，这几个工具会被抢先归到别处，测试立刻红。
        val expected = ToolCapabilityDirectory.ToolCategory.AI_CAPABILITIES
        tools.forEach { name ->
            assertEquals(
                "$name 被更宽的规则抢走了分类（检查 ToolTaxonomy.RULES 的顺序：精确名单要排在宽前缀之前）",
                expected,
                ToolTaxonomy.categoryOf(name),
            )
        }
        // 同类回归：ui_open_* 必须落在界面导航，不能被 ui_ 宽前缀吞进 UI_CARDS
        assertEquals(
            "ui_open_build 被 ui_ 前缀抢进了 UI_CARDS",
            ToolCapabilityDirectory.ToolCategory.APP_NAVIGATION,
            ToolTaxonomy.categoryOf("ui_open_build"),
        )
    }

    @Test
    fun `系统提示词的重点能力段提到 codecanvas`() {
        val s = vmKt
        val i = s.indexOf("本版重点能力")
        assertTrue("未找到「本版重点能力」段", i > 0)
        val block = s.substring(i, i + 1200)
        assertTrue(
            "「本版重点能力」硬编码段没有 codecanvas —— 模型没有任何动机去调它",
            block.contains("codecanvas_"),
        )
    }

    @Test
    fun `系统提示词有 codecanvas 专项指引章节`() {
        val s = vmKt
        assertTrue("缺少 codecanvas 专项指引章节", s.contains("确定性出图（codecanvas_* 工具）"))
        // 五个工具都要在提示词里点名，AI 才知道每个干什么
        tools.forEach {
            assertTrue("专项指引未提到 $it", s.contains(it))
        }
        // 🔴 出图后必须 attach_file，否则用户只看到路径文字——这条是实测踩过的坑
        assertTrue("专项指引必须写明出图后要调 attach_file 挂进气泡", s.contains("attach_file"))
    }

    @Test
    fun `提示词必须说清与 image_gen 的分工`() {
        val s = vmKt
        val i = s.indexOf("确定性出图（codecanvas_* 工具）")
        assertTrue("缺少专项指引章节", i > 0)
        val block = s.substring(i, i + 1500)
        assertTrue("必须提到 image_gen，否则模型会混用两条出图通道", block.contains("image_gen"))
        assertTrue("必须讲清「确定性渲染」这一差异点", block.contains("确定性渲染"))
    }

    @Test
    fun `提示词必须警告 SVG 在气泡里不显示`() {
        // 实测坑：对话框气泡用 BitmapFactory 位图解码，SVG 渲染不出来
        val s = vmKt
        assertTrue("必须提示 SVG 不显示、建议用 png", s.contains("SVG 不会显示"))
    }
}