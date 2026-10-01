package com.ai.assistance.quro.core.tools

import com.ai.assistance.quro.core.agent.loop.FailureType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [QuroToolFeedback] 单元测试（N4）。
 *
 * 这里钉死三类**错了就会放大成「AI 乱重试」**的逻辑：
 * 1. [ToolFailureKind] 与闭环的 [FailureType] 不许漂移（穷尽映射）；
 * 2. 关键词优先级顺序（顺序错了会把「参数缺失」判成「文件不存在」，把「超时」判成「可以重试」）；
 * 3. 幂等与成功结果不被污染（多层兜底会重复包装；成功文本里含 "not found" 也不能判失败）。
 */
class QuroToolFeedbackTest {

    // ─────────────── 1) 两套词汇不许漂移 ───────────────

    @Test
    fun `every FailureType maps to a kind`() {
        // FailureType 若新增枚举值，QuroToolFeedback.kindOf 的穷尽 when 会**编译失败**，
        // 本测试是那道编译保护运行期的补充：确保每种类型都真能映射出来。
        val mapped = FailureType.values().map { QuroToolFeedback.kindOf(it) }
        assertEquals("每种 FailureType 都必须映射到一个 kind", FailureType.values().size, mapped.size)
    }

    @Test
    fun `three kinds are text only because the closed loop cannot express them`() {
        // 记录「两套词汇为何必须分开」：NOT_FOUND / ENV_MISSING / CANCELLED 在闭环里
        // 没有对应类型（它们都被归到 BUSINESS/UNKNOWN），因为闭环的恢复策略与
        // 「路径写错了」「工具没装」「用户点了停止」这些区别无关；但**回喂给模型**时
        // 这三者的正确指令完全不同（别重试 / 换工具 / 先问用户）。
        // 若哪天有人给闭环补上这三类、并让映射覆盖它们，本测试会失败 —— 提醒同步维护两处。
        val mappedFromLoop = FailureType.values().map { QuroToolFeedback.kindOf(it) }.toSet()
        assertFalse("NOT_FOUND 目前只能由文本判定", ToolFailureKind.NOT_FOUND in mappedFromLoop)
        assertFalse("ENV_MISSING 目前只能由文本判定", ToolFailureKind.ENV_MISSING in mappedFromLoop)
        assertFalse("CANCELLED 目前只能由文本判定", ToolFailureKind.CANCELLED in mappedFromLoop)
    }

    @Test
    fun `FailureType mapping is semantically correct`() {
        assertEquals(ToolFailureKind.PERMISSION, QuroToolFeedback.kindOf(FailureType.PERMISSION))
        assertEquals(ToolFailureKind.TIMEOUT, QuroToolFeedback.kindOf(FailureType.TIMEOUT))
        assertEquals(ToolFailureKind.TRANSPORT, QuroToolFeedback.kindOf(FailureType.TRANSPORT))
        // 闭环叫「解析失败(PARSE)」，回喂视角要更直白：参数不合法，去改参数。
        assertEquals(ToolFailureKind.INVALID_ARGS, QuroToolFeedback.kindOf(FailureType.PARSE))
        assertEquals(ToolFailureKind.BUSINESS, QuroToolFeedback.kindOf(FailureType.BUSINESS))
        assertEquals(ToolFailureKind.UNKNOWN, QuroToolFeedback.kindOf(FailureType.UNKNOWN))
    }

    @Test
    fun `every kind has a non blank actionable directive`() {
        ToolFailureKind.values().forEach { kind ->
            val d = QuroToolFeedback.directiveOf(kind)
            assertTrue("$kind 的指令不能为空", d.isNotBlank())
            assertTrue("$kind 的指令必须提到「重试」，否则模型无从判断该不该重试", d.contains("重试"))
        }
    }

    // ─────────────── 2) 关键词分类：真实文案 ───────────────

    @Test
    fun `classifies real android and kotlin error texts`() {
        val cases = mapOf(
            // Android / Kotlin / 系统层（英文）
            "java.io.FileNotFoundException: /sdcard/a.txt: open failed: ENOENT (No such file or directory)" to ToolFailureKind.NOT_FOUND,
            "java.io.IOException: Permission denied" to ToolFailureKind.PERMISSION,
            "java.net.SocketTimeoutException: timeout" to ToolFailureKind.TIMEOUT,
            "java.net.ConnectException: Connection refused" to ToolFailureKind.TRANSPORT,
            "org.json.JSONException: Unterminated object at character 12" to ToolFailureKind.INVALID_ARGS,
            "java.lang.IllegalArgumentException: 参数 count 必须大于 0" to ToolFailureKind.INVALID_ARGS,
            "java.util.concurrent.CancellationException: canceled" to ToolFailureKind.CANCELLED,
            // 本仓工具自己的中文文案
            "操作失败：备份文件路径不能为空" to ToolFailureKind.INVALID_ARGS,
            "读取失败，文件不存在：/sdcard/note.md" to ToolFailureKind.NOT_FOUND,
            "需要权限：android.permission.CAMERA，请在「设置 → 权限」中授予后重试。" to ToolFailureKind.PERMISSION,
            "工具执行超时（超过60秒），已自动终止。如需执行长时间任务，请分步操作。" to ToolFailureKind.TIMEOUT,
            "当前设备不支持该能力" to ToolFailureKind.ENV_MISSING,
            "未知工具: fake_tool_xyz" to ToolFailureKind.ENV_MISSING,
            "执行失败：模型未加载" to ToolFailureKind.BUSINESS,
        )
        cases.forEach { (text, expected) ->
            assertEquals("分类错误：$text", expected, QuroToolFeedback.kindOfText(text))
        }
    }

    @Test
    fun `blank and null texts are unknown`() {
        assertEquals(ToolFailureKind.UNKNOWN, QuroToolFeedback.kindOfText(null))
        assertEquals(ToolFailureKind.UNKNOWN, QuroToolFeedback.kindOfText(""))
        assertEquals(ToolFailureKind.UNKNOWN, QuroToolFeedback.kindOfText("   "))
        // 完全没有失败语义的文本 → UNKNOWN（绝不会因为「看起来像失败」就随便归到某一类）
        assertEquals(ToolFailureKind.UNKNOWN, QuroToolFeedback.kindOfText("hello world"))
    }

    // ─────────────── 3) 优先级顺序（顺序错了结论就反了） ───────────────

    @Test
    fun `cancelled wins over transport so cancel is never auto retried`() {
        // 🔴 最危险的一条：取消信息常带 "connection closed"，若判成 TRANSPORT（可重试）
        //    就会出现「用户点了停止，AI 又自己跑了一遍」——严重违背用户意图。
        assertEquals(ToolFailureKind.CANCELLED, QuroToolFeedback.kindOfText("cancelled: connection closed"))
        assertEquals(ToolFailureKind.CANCELLED, QuroToolFeedback.kindOfText("已取消：连接中断"))
        assertFalse(QuroToolFeedback.retryableOf(ToolFailureKind.CANCELLED))
    }

    @Test
    fun `timeout wins over transport`() {
        // "connect timeout" 同时含 connection（TRANSPORT）与 timeout —— 必须是 TIMEOUT。
        // 两者虽都可重试，但 TIMEOUT 的指令是「缩小范围」，TRANSPORT 是「可直接原样重试」，
        // 判错会让模型对着大任务反复原样重试到超时。
        assertEquals(ToolFailureKind.TIMEOUT, QuroToolFeedback.kindOfText("java.net.SocketTimeoutException: connect timeout"))
    }

    @Test
    fun `permission wins over not_found`() {
        // "permission denied: no such file" —— 真正的堵点是权限（改参数/换路径都无用）。
        assertEquals(ToolFailureKind.PERMISSION, QuroToolFeedback.kindOfText("Permission denied: no such file or directory"))
    }

    @Test
    fun `invalid args wins over not_found`() {
        // "required parameter 'path' not found" —— 这是参数问题，不是资源不存在。
        // 判成 NOT_FOUND 会让模型跑去「搜索文件」，而正确动作是「补上 path 参数」。
        assertEquals(ToolFailureKind.INVALID_ARGS, QuroToolFeedback.kindOfText("required parameter 'path' not found"))
    }

    @Test
    fun `not_found wins over transport for http 404`() {
        // 404 是资源不存在（重试无用），不应被当成网络故障去狂重试。
        assertEquals(ToolFailureKind.NOT_FOUND, QuroToolFeedback.kindOfText("HTTP 404 Not Found"))
    }

    @Test
    fun `business keywords are matched last`() {
        // BUSINESS 含最宽泛的「失败 / error」，一旦排前面就会吞掉上面全部具体分类。
        // 这里用「顺序」本身作断言：BUSINESS 必须是关键词表里最后一个可匹配项。
        val table = QuroToolFeedback.keywordTableForTest()
        assertEquals(
            "BUSINESS 必须排在关键词表最末",
            ToolFailureKind.BUSINESS,
            table.last().first,
        )
        // 「操作失败：文件不存在」同时含 失败(BUSINESS) 与 不存在(NOT_FOUND) → 必须判 NOT_FOUND。
        assertEquals(ToolFailureKind.NOT_FOUND, QuroToolFeedback.kindOfText("操作失败：文件不存在"))
    }

    // ─────────────── 4) 可重试性语义 ───────────────

    @Test
    fun `non retryable kinds tell the model not to retry`() {
        // 这是 N4 的核心价值：把「重试没用」明确写出来。
        // 非可重试类的指令必须含一句**明确的否定**，否则模型仍会本能重试。
        listOf(
            ToolFailureKind.NOT_FOUND,
            ToolFailureKind.PERMISSION,
            ToolFailureKind.ENV_MISSING,
            ToolFailureKind.CANCELLED,
            ToolFailureKind.BUSINESS,
        ).forEach { kind ->
            assertFalse("$kind 不应被标为可重试", kind.retryable)
            val d = QuroToolFeedback.directiveOf(kind)
            assertTrue(
                "$kind 的指令必须明确劝阻重试（含「不要」/「无效」/「无法」其一）",
                d.contains("不要") || d.contains("无效") || d.contains("无法"),
            )
        }
    }

    @Test
    fun `retryable kinds explain how to retry usefully`() {
        listOf(
            ToolFailureKind.INVALID_ARGS,
            ToolFailureKind.TIMEOUT,
            ToolFailureKind.TRANSPORT,
            ToolFailureKind.UNKNOWN,
        ).forEach { kind ->
            assertTrue("$kind 应可重试", kind.retryable)
        }
        // 每种可重试的失败都要说清「怎么改」，而不是只说「重试」。
        assertTrue(QuroToolFeedback.directiveOf(ToolFailureKind.INVALID_ARGS).contains("修正"))
        assertTrue(QuroToolFeedback.directiveOf(ToolFailureKind.TIMEOUT).contains("缩小"))
        assertTrue(QuroToolFeedback.directiveOf(ToolFailureKind.UNKNOWN).contains("一次"))
    }

    // ─────────────── 5) 组装文本 ───────────────

    @Test
    fun `compose produces self contained block with all four parts`() {
        val out = QuroToolFeedback.compose("read_file", "java.io.FileNotFoundException: no such file", null)
        assertTrue(out.startsWith(QuroToolFeedback.MARKER))
        assertTrue(out.contains("tool=read_file"))
        assertTrue(out.contains("kind=NOT_FOUND"))
        assertTrue(out.contains("retryable=false"))
        assertTrue(out.contains("原因："))
        assertTrue(out.contains("下一步："))
        assertTrue("原因原文必须保留，否则用户与开发者都无从定位", out.contains("FileNotFoundException"))
    }

    @Test
    fun `compose honours known kind over text classification`() {
        // 引擎已经用 FailureType 给出确定分类时，不能被文本关键词覆盖
        // （例如 TIMEOUT 的消息里恰好提到「权限」，也不该变成 PERMISSION）。
        val out = QuroToolFeedback.compose(
            "camera_tool",
            "工具执行超时；若频繁出现请检查权限设置",
            ToolFailureKind.TIMEOUT,
        )
        assertTrue(out.contains("kind=TIMEOUT"))
    }

    @Test
    fun `compose is idempotent`() {
        // 多层兜底（引擎 -> Agent 循环 -> 子智能体）可能重复包装同一条失败。
        // 不加幂等判定，模型会看到套娃式的 [工具失败] 块，越套越长、挤占上下文。
        val once = QuroToolFeedback.compose("t", "boom", ToolFailureKind.BUSINESS)
        val twice = QuroToolFeedback.compose("t", once, ToolFailureKind.BUSINESS)
        assertEquals(once, twice)
        assertTrue(QuroToolFeedback.isWrapped(once))
        assertFalse(QuroToolFeedback.isWrapped("普通文本"))
        assertFalse(QuroToolFeedback.isWrapped(null))
    }

    @Test
    fun `compose omits tool segment when name is blank and never prints null`() {
        val out = QuroToolFeedback.compose("", "boom", ToolFailureKind.UNKNOWN)
        assertFalse("工具名未知时应省略该段，而不是拼出 tool=null", out.contains("tool="))
        assertFalse(out.contains("null"))
    }

    @Test
    fun `compose clips overlong reason to protect context`() {
        // 失败信息不该挤占模型的目标上下文（context rot / 缺口 C6）。
        val long = "x".repeat(5000)
        val out = QuroToolFeedback.compose("t", long, ToolFailureKind.UNKNOWN)
        assertTrue("超长原因必须被截断", out.length < 1200)
        assertTrue(out.contains("已截断"))
        // 截断后仍必须给出「下一步」，否则模型只剩一堆噪音
        assertTrue(out.contains("下一步："))
    }

    @Test
    fun `compose falls back when reason is blank`() {
        val out = QuroToolFeedback.compose("t", "   ", null)
        assertTrue(out.contains("未提供失败原因"))
        // 没有原因可判 → 归 UNKNOWN（允许重试一次），不得瞎猜成 NOT_FOUND 之类
        assertTrue(out.contains("kind=UNKNOWN"))
    }

    // ─────────────── 6) 铁律：只对失败调用 ───────────────

    @Test
    fun `successful texts would be misclassified which is why compose must only run on failures`() {
        // 🔴 这是一条「记录危险」的测试，不是在认可这个行为 ——
        //    搜索类工具**正常**输出「未找到匹配项 / 未找到相关文件」，
        //    在文本层面与 NOT_FOUND 完全无法区分。
        //    因此接线铁律是：compose 只在 ToolOutcome.Failure / catch 分支调用，
        //    **绝不**对 ToolOutcome.Success 调用（QuroToolEngine.toToolResult 已照此实现）。
        //    若哪天有人给成功结果也套 compose，本测试提醒你：那会把成功变成假故障。
        assertEquals(ToolFailureKind.NOT_FOUND, QuroToolFeedback.kindOfText("未找到匹配项"))
        assertEquals(ToolFailureKind.NOT_FOUND, QuroToolFeedback.kindOfText("搜索完成，未找到相关文件"))
    }
}
