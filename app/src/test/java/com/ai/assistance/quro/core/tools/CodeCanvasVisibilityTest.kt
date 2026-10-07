package com.ai.assistance.quro.core.tools

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
        // inferCategory 结尾是 else -> BASIC（查得到但分类错），categorize 结尾是 else -> null（彻底查不到）
        assertTrue(
            "ToolCapabilityDirectory.inferCategory 缺 codecanvas_ 规则，会落到 else -> BASIC",
            dirKt.contains("""name.startsWith("codecanvas_") -> ToolCategory.AI_CAPABILITIES"""),
        )
        assertTrue(
            "QuroToolRouter.categorize 缺 codecanvas_ 规则，会落到 else -> null（tool_router 完全查不到）",
            routerKt.contains("""name.startsWith("codecanvas_") -> ToolCapabilityDirectory.ToolCategory.AI_CAPABILITIES"""),
        )
    }

    @Test
    fun `分类规则必须排在 get_ 与 else 兜底之前`() {
        // Kotlin when 是顺序匹配：`get_* -> BASIC` 在前会吃掉一切；codecanvas 不含 "get_"，
        // 但仍要求规则存在且位置在 else 兜底之前，防止后续有人把它加到 when 末尾。
        val s = dirKt
        val rule = s.indexOf("""name.startsWith("codecanvas_")""")
        val fallback = s.indexOf("else -> ToolCategory.BASIC", rule)
        assertTrue("codecanvas_ 规则未找到", rule > 0)
        assertTrue(
            "codecanvas_ 规则必须排在 `else -> ToolCategory.BASIC` 之前（when 顺序匹配，放末尾等于失效）",
            fallback > rule,
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