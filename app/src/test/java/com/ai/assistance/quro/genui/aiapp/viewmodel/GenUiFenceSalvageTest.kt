package com.ai.assistance.quro.genui.aiapp.viewmodel

import com.ai.assistance.quro.genui.sdk.dsl.StreamingParser
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #508 回归（2026-10-29 真机截图×2）：「两个通道仍然没有修复」。
 *
 * 失效面 A（95 字符无围栏）：模型只回短人话，五策略全败 → 兜底卡
 * 「（AI 未返回有效 UI，请重试）」把真人话吞掉。根因：#501 的气泡分支被
 * `forcedChannel != null && != "genui"` 挡住，forced=null/genui 态照样中招。
 *
 * 失效面 B（7088 字符有围栏有 root）：`extractFromGenuiFences` 三条死路 —
 * ① 围栏未闭合（正则要求结尾 ```）零匹配；② 围栏体不以 `{` 开头（语言提示行）
 * 被 startsWith("{") 整块滤掉；③ 截断 JSON 无任何 salvage。
 *
 * 本测试锁：抢救纯函数四种输入 + isPlainChatReply 判定 + 生产侧源码契约
 * （气泡分支不再看 forcedChannel；围栏提取容忍非 { 前缀并接 salvage）。
 */
class GenUiFenceSalvageTest {

    /** 真机形态的完整 genui 文档。 */
    private val fullJson =
        """{"id":"p1","title":"今日天气","root":{"type":"column","style":{"padding":{"all":16}},""" +
            """"children":[{"type":"text","properties":{"text":"晴，24℃"}},""" +
            """{"type":"button","properties":{"label":"刷新"}}]}}"""

    // ═══════════════ 失效面 B：未闭合围栏截断抢救 ═══════════════

    /** 7088 字符那次真机失败的复现：围栏未闭合 + JSON 被砍断。 */
    @Test
    fun salvagesTruncatedJsonInsideUnclosedFence() {
        val cut = fullJson.length - 40
        val text = "好的，这是界面：\n```genui\n" + fullJson.substring(0, cut)
        val salvaged = GenUiPromptProbe.salvageUnclosedGenuiFence(text)
        assertNotNull("未闭合围栏里的截断 JSON 必须能抢救出来", salvaged)
        // 抢救结果必须真能过 UISpec 解析（不是把半截源码扔出来）
        assertNotNull(StreamingParser.tryParsePartial(salvaged!!))
        // 围栏外的引导语不许混进来
        assertFalse(salvaged.startsWith("好的"))
        assertTrue(salvaged.startsWith("{"))
    }

    /** 围栏体首行是语言提示（json）时，从体内首个 { 起取。 */
    @Test
    fun salvagesPastLanguageHintLine() {
        val cut = fullJson.length - 40
        val text = "```genui\njson\n" + fullJson.substring(0, cut)
        val salvaged = GenUiPromptProbe.salvageUnclosedGenuiFence(text)
        assertNotNull(salvaged)
        assertTrue(salvaged!!.startsWith("{"))
        assertNotNull(StreamingParser.tryParsePartial(salvaged))
    }

    /** 反证：没有 genui 围栏的纯人话绝不抢救。 */
    @Test
    fun noFenceNoSalvage() {
        assertNull(GenUiPromptProbe.salvageUnclosedGenuiFence("好的，马上写"))
        assertNull(GenUiPromptProbe.salvageUnclosedGenuiFence("```json\n" + fullJson + "\n```"))
    }

    /** 反证：围栏后没有 `{`（模型只写了围栏标记）不抢救。 */
    @Test
    fun fenceWithoutBraceIsNotSalvaged() {
        assertNull(GenUiPromptProbe.salvageUnclosedGenuiFence("```genui\n我还没开始写"))
    }

    /** 反证：没有 root 键的截断 JSON 不抢救（防半截数据 JSON 进画布）。 */
    @Test
    fun truncatedJsonWithoutRootKeyIsNotSalvaged() {
        assertNull(
            GenUiPromptProbe.salvageUnclosedGenuiFence(
                "```genui\n" + """{"id":"x","data":{"a":1}}""".substring(0, 12)
            )
        )
    }

    /** 反证：`"root":` 后面还没开始有值（截断在冒号后）不抢救。 */
    @Test
    fun rootValueNotStartedIsNotSalvaged() {
        assertNull(
            GenUiPromptProbe.salvageUnclosedGenuiFence(
                "```genui\n" + fullJson.substring(0, fullJson.indexOf(":") + 30)
            )
        )
    }

    /** 闭合围栏被前面的提取器拒掉后，抢救按更宽口径再收一次（body 到闭合符为止）。 */
    @Test
    fun salvageHandlesClosedFenceBodyWithoutTrailingFenceMarks() {
        // 直接把完整 JSON 放围栏里但破坏一处（少个引号）→ 闭合提取失败 → 抢救上场
        val broken = fullJson.replace("\"title\":\"今日天气\"", "\"title\":今日天气")
        val text = "```genui\n$broken\n```"
        val salvaged = GenUiPromptProbe.salvageUnclosedGenuiFence(text)
        // 要么救出等价物，要么明确 null——但不许把结尾 ``` 混进返回里
        if (salvaged != null) {
            assertFalse("返回体不许带闭合围栏标记", salvaged.contains("```"))
            assertTrue(salvaged.startsWith("{"))
        }
    }

    // ═══════════════ 失效面 A：短人话判定 ═══════════════

    @Test
    fun plainChatReplyCoversAllForcedStates() {
        // #501 只覆盖 forced 非 genui；本轮截图实证 forced=null/genui 同样中招，
        // 判据本身与通道无关（isPlainChatReply 不看 forcedChannel）。
        assertTrue(GenUiPromptProbe.isPlainChatReply("好的，马上写"))
        assertTrue(GenUiPromptProbe.isPlainChatReply("这轮没有可显示的内容".repeat(2)))
        // 95 字符真机回复形态
        assertTrue(GenUiPromptProbe.isPlainChatReply("x".repeat(95)))
        // 反向：围栏 / JSON 根键 / 超长 都不算纯人话
        assertFalse(GenUiPromptProbe.isPlainChatReply("```genui\n{}\n```"))
        assertFalse(GenUiPromptProbe.isPlainChatReply("""{"root":{}}"""))
        assertFalse(GenUiPromptProbe.isPlainChatReply("x".repeat(120)))
        assertFalse(GenUiPromptProbe.isPlainChatReply("   "))
    }

    // ═══════════════ root 键判据（生产侧唯一实现）═══════════════

    @Test
    fun rootKeyPredicates() {
        assertTrue(GenUiPromptProbe.containsRootKey("""{"a":1,"root":{}}"""))
        assertFalse(GenUiPromptProbe.containsRootKey("""{"a":"root"}"""))
        assertTrue(GenUiPromptProbe.hasRootValueStarted("""{"root":{}}"""))
        assertTrue(GenUiPromptProbe.hasRootValueStarted(""""root" :   {"""))
        assertFalse(GenUiPromptProbe.hasRootValueStarted(""""root":"""))
        assertFalse(GenUiPromptProbe.hasRootValueStarted(""""root": """))
        assertFalse(GenUiPromptProbe.hasRootValueStarted("""{"a":1}"""))
    }

    // ═══════════════ 生产侧源码契约 ═══════════════

    private fun viewModelSource(): String {
        val f = File("src/main/java/com/ai/assistance/quro/genui/aiapp/viewmodel/ChatViewModel.kt")
        assertTrue("找不到 ChatViewModel.kt（工作目录应为 app/）", f.isFile)
        return f.readText()
    }

    /** 气泡分支不得再看 forcedChannel（#501 覆盖面缺口的修复锚点）。 */
    @Test
    fun plainChatBranchIsNotGatedOnForcedChannel() {
        val src = viewModelSource()
        val branch = src.substringAfter("isPlainChatReply(fullText)").substringBefore("策略 1")
        assertTrue(
            "气泡分支必须接在 isPlainChatReply 之后且不再带 forcedChannel 条件",
            branch.contains("_state.update") && branch.contains("persistSession()"),
        )
        assertFalse(
            "气泡分支不得再出现 forcedChannel 门槛（那正是 forced=null/genui 中招的原因）",
            branch.contains("forcedChannel"),
        )
    }

    /** 围栏提取必须容忍非 { 前缀并接上截断抢救。 */
    @Test
    fun fenceExtractionToleratesNonBracePrefixAndSalvages() {
        val src = viewModelSource()
        val fn = src.substringAfter("private fun extractFromGenuiFences(")
            .substringBefore("private fun extractFromJsonFences(")
        assertTrue("围栏体必须从首个 { 切片（容忍语言提示行）", fn.contains("indexOf('{')"))
        assertTrue("必须调用未闭合围栏抢救", fn.contains("salvageUnclosedGenuiFence("))
        assertTrue("strict 不得抢救截断 JSON", fn.contains("if (strict) return null"))
    }

    /** 生产侧 salvage 必须委托探针唯一实现（防两份逻辑漂移）。 */
    @Test
    fun viewModelSalvageDelegatesToProbe() {
        val src = viewModelSource()
        assertTrue(
            "ChatViewModel.salvageUnclosedGenuiFence 必须委托 GenUiPromptProbe",
            src.contains("private fun salvageUnclosedGenuiFence(source: String): String? =\n        GenUiPromptProbe.salvageUnclosedGenuiFence(source)"),
        )
    }
}
