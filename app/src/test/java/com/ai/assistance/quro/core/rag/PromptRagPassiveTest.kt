package com.ai.assistance.quro.core.rag

import com.ai.assistance.quro.core.tools.QuroToolRouter
import com.ai.assistance.quro.core.tools.RagSearchTool
import com.ai.assistance.quro.core.tools.buildQuroRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * #202 回归锁：系统提示词 RAG 的 5 个实证缺陷。
 *
 * ## 每条判据都来自**探针实测的真实数字**，不是拍脑袋写的
 *
 * 修之前的探针输出（`TmpPromptRagProbeTest`，跑完即删）：
 * ```
 * blocks 总数=8      stickyBlocks=[]                    ← 病灶①
 * SELECTIVE 追加字符数=0                                ← 病灶① 直接后果
 * FULL_ALWAYS 追加字符数=3182
 * 「这些数据做成表格给我看」 prompts 域 → 零命中        ← 病灶②
 * 「帮我查一下明天的天气」   prompts 域 → 零命中        ← 病灶②
 * 「帮我把这个趋势用图表显示出来」 top1=0.900 < 1.0     ← 病灶②
 * prompts 域返回里 含完整正文块=[]                      ← 病灶③④
 * cluster_orchestration bodyLen=1416，200 字后不可见
 * ```
 *
 * ## 本类的意义
 *
 * 这 5 条全部是**不报错、不崩溃、只表现为「功能好像没生效」**的缺陷 ——
 * 和 [RagWiringTest] 同类。写死它们是为了让下一次改检索算法的人，
 * 能在改动前就看到「这一改会把提示词 RAG 打死」。
 */
class PromptRagPassiveTest {

    private val router = QuroToolRouter(buildQuroRegistry(null).specs())

    @Before
    fun setUp() {
        AgentRag.engine.clearDomain(PromptRagIndex.DOMAIN)
        AgentRag.refresh(router.allSpecsSnapshot())
    }

    // ═══════════════ 病灶①：stickyBlocks 恒为空 → SELECTIVE 注入 0 字符 ═══════════════

    /** 修前 `alwaysSticky = true` 出现 0 次，`stickyBlocks` 恒空 → SELECTIVE 等于什么都不注入。 */
    @Test
    fun 基础段不能是空集() {
        assertTrue(
            "stickyBlocks 是空的：SELECTIVE 模式下基础段一条都不会注入，" +
                "注释里写的「基础段无条件注入」是死代码",
            PromptRagIndex.stickyBlocks.isNotEmpty()
        )
    }

    @Test
    fun 工具纪律与输出格式是基础段() {
        val ids = PromptRagIndex.stickyBlocks.map { it.id }
        assertTrue(
            "工具调用纪律必须是基础段（它是整套 RAG 的入口纪律，" +
                "若它也变成按需检索，AI 根本不会去检索）：$ids",
            "tool_discipline" in ids
        )
        assertTrue(
            "输出格式纪律必须是基础段（用户报的两条硬伤都出在它上面）：$ids",
            "output_format" in ids
        )
    }

    /** SELECTIVE 必须真的注入基础段正文——修前追加字符数是 0。 */
    @Test
    fun 选择性模式真的注入基础段() {
        val out = PromptRagIndex.render(
            engine = AgentRag.engine,
            mode = PromptRagIndex.Mode.SELECTIVE,
            userQuery = "zzzzqqq完全不相关的东西xyzzy",
            base = "BASE",
        )
        assertTrue("基座必须原样保留", out.startsWith("BASE"))
        assertTrue(
            "噪声查询下基础段仍未注入，长度=${out.length}（修前是 4，即一个字都没加）",
            out.length > "BASE".length + 64
        )
        for (b in PromptRagIndex.stickyBlocks) {
            assertTrue(
                "基础段 ${b.id} 丢失：\n${out.take(300)}",
                out.contains(b.title)
            )
        }
    }

    // ═══════════════ 病灶②：prompts 域被 MIN_SCORE=0.62 系统性打死 ═══════════════

    /**
     * 真实中文口语必须召回得到。
     *
     * 🔴 修前这三条**全部零命中**：top1 分别只有 0.318 / 0.141 / 0.900，
     * 都低于引擎默认的 MIN_SCORE(0.62)。而 `output_format` 的 triggers 里明明有「表格」。
     */
    @Test
    fun 真实中文口语能召回规则段() {
        val cases = mapOf(
            "这些数据做成表格给我看" to "output_format",
            "帮我查一下明天的天气" to "tool_discipline",
            "帮我把这个趋势用图表显示出来" to "output_format",
            "出张海报" to "media_generation",
            "怎么让集群帮我干活" to "cluster_orchestration",
        )
        for ((q, expect) in cases) {
            val hits = PromptRagIndex.searchPrompts(AgentRag.engine, q, 5)
            assertTrue(
                "「$q」在 prompts 域零命中（修前就是这个问题）：",
                hits.isNotEmpty()
            )
            assertTrue(
                "「$q」应该召回到 `$expect`，实际=${hits.map { it.id }}",
                expect in hits.map { it.id }
            )
        }
    }

    /** 门槛必须与工具域脱钩：prompts 域只有 8 篇、每篇正文很长，照抄 0.62 就是全灭。 */
    @Test
    fun 提示词域门槛独立于工具域() {
        val belowEngineGate = listOf("这些数据做成表格给我看", "帮我查一下明天的天气")
        for (q in belowEngineGate) {
            val withEngineGate = AgentRag.engine
                .search(q, PromptRagIndex.DOMAIN, 8)
                .firstOrNull()?.score ?: 0.0
            val withPromptGate = PromptRagIndex.searchPrompts(AgentRag.engine, q, 5)
                .firstOrNull()?.score ?: 0.0
            assertTrue(
                "「$q」用引擎默认门槛是 ${"%.3f".format(withEngineGate)}（零命中），" +
                    "用 prompts 域门槛是 ${"%.3f".format(withPromptGate)}——两者必须真的不同，" +
                    "否则说明 prompts 域又退回工具域阈值了",
                withPromptGate > withEngineGate || withEngineGate > 0.0
            )
        }
        assertTrue(
            "PROMPT_MIN_SCORE 必须低于引擎 MIN_SCORE(0.62)，否则改了个寂寞",
            PromptRagIndex.PROMPT_MIN_SCORE < 0.62
        )
    }

    /** 每块用自己的触发词必须能探到自己——索引登记错位的自检。 */
    @Test
    fun 每块都能用自己的触发词探到自己() {
        for (b in PromptRagIndex.blocks) {
            val probe = b.triggers.firstOrNull() ?: continue
            val ids = PromptRagIndex.searchPrompts(AgentRag.engine, probe, 5).map { it.id }
            assertTrue(
                "块 ${b.id} 用自己的触发词「$probe」都探不到自己：$ids",
                b.id in ids
            )
        }
    }

    // ═══════════════ 病灶③④：200 字截断 + payload 正文从未回传 ═══════════════

    /** 200 字之后的内容必须对检索可见——修前 cluster_orchestration 有 86% 检索不到。 */
    @Test
    fun 长正文的后半段也参与检索() {
        val long = PromptRagIndex.blocks.first { it.body.length > 200 }
        assertTrue(
            "本测试需要一块超过 200 字的正文，实际都不到 200：${PromptRagIndex.blocks.map { it.body.length }}",
            long.body.length > 200
        )
        val tail = long.body.take(300).drop(200).trim()
        assertTrue("取不到 200 字后的尾部内容", tail.isNotEmpty())
        assertTrue(
            "description 必须用正文全文，否则后半段对 lexical/shape 完全不可见。" +
                "旧实现 body.take(200) 会把这段藏起来：${tail.take(60)}",
            long.toDoc().description.contains(tail.take(60))
        )
    }

    /** 🔴 AI 主动查规则时，必须拿到**完整正文**而不是 200 字碎片。 */
    @Test
    fun 主动检索规则能拿到完整正文() {
        val json = RagSearchTool.query(
            """{"query":"出张海报","domain":"prompts","limit":5}"""
        )
        val o = JSONObject(json)
        val full = o.optString("rules_full_text", "")
        assertTrue(
            "顶层必须有 rules_full_text（可直接照着执行的规则原文），实际返回前 300 字：\n${json.take(300)}",
            full.isNotBlank()
        )
        assertTrue(
            "rules_full_text 必须含 markdown 小标题（块标题），实际：${full.take(200)}",
            full.contains("### ")
        )
        // 至少有一条 hit 带 rule_body 原文，且长度明显超过 200 字截断线
        val hits = o.getJSONArray("hits")
        var maxBody = 0
        for (i in 0 until hits.length()) {
            val h = hits.getJSONObject(i)
            if (h.optString("domain") == PromptRagIndex.DOMAIN) {
                maxBody = maxOf(maxBody, h.optString("rule_body", "").length)
            }
        }
        assertTrue(
            "命中的规则段必须带 rule_body 原文（修前 toJson 只回 200 字 description）：maxBody=$maxBody",
            maxBody > 200
        )
    }

    /** 集群段有 1416 字，它的后半段（含 cluster_start/sync 参数说明）必须查得到。 */
    @Test
    fun 集群规则的后半段能查得到() {
        val block = PromptRagIndex.blocks.first { it.id == "cluster_orchestration" }
        assertTrue("前置条件：集群段应远长于 200 字，实际 ${block.body.length}", block.body.length > 800)
        val hits = PromptRagIndex.searchPrompts(AgentRag.engine, "集群怎么接任务", 5)
        assertTrue("集群规则零命中：${hits.map { it.id }}", hits.any { it.id == "cluster_orchestration" })
    }

    // ═══════════════ 病灶⑤：RAG 被当成主要方案 ═══════════════

    /**
     * RAG 是备用方案：默认模式必须是 SELECTIVE，且比全量省一大截。
     *
     * 修前默认 FULL_ALWAYS 每轮追加 3182 字符（≈2100 tokens）。
     */
    @Test
    fun 默认模式是选择性而不是全量() {
        // 枚举默认值必须让「未配置」落到 SELECTIVE：
        // entries 的第一个就是 runCatching 拿不到配置时的兜底。
        assertEquals(
            "默认模式必须是 SELECTIVE（RAG 是备用方案不是主要方案）",
            PromptRagIndex.Mode.SELECTIVE,
            PromptRagIndex.Mode.entries.first()
        )
        val base = "BASE"
        val query = "帮我把这段视频弄短一点"
        val full = PromptRagIndex.render(
            AgentRag.engine, PromptRagIndex.Mode.FULL_ALWAYS, query, base
        ).length - base.length
        val sel = PromptRagIndex.render(
            AgentRag.engine, PromptRagIndex.Mode.SELECTIVE, query, base
        ).length - base.length
        assertTrue(
            "SELECTIVE 应该明显比 FULL_ALWAYS 省：sel=$sel full=$full",
            sel < full
        )
    }

    /** FULL_ALWAYS 必须仍然可用（旧架构保留），且一条不少地注入全部块。 */
    @Test
    fun 全量模式仍然可用且一条不少() {
        val out = PromptRagIndex.render(
            engine = AgentRag.engine,
            mode = PromptRagIndex.Mode.FULL_ALWAYS,
            userQuery = "",
            base = "BASE",
        )
        assertTrue("基座必须原样保留", out.startsWith("BASE"))
        val missing = PromptRagIndex.blocks.filter { !out.contains(it.title) }.map { it.id }
        assertTrue("FULL_ALWAYS 下这些块没被注入：$missing", missing.isEmpty())
    }

    /** 噪声查询在 SELECTIVE 下不该额外拉进阶段（基础段仍会注入，那是设计如此）。 */
    @Test
    fun 噪声查询不额外拉进阶段() {
        val out = PromptRagIndex.render(
            engine = AgentRag.engine,
            mode = PromptRagIndex.Mode.SELECTIVE,
            userQuery = "zzzzqqq完全不相关的东西xyzzy",
            base = "BASE",
        )
        assertFalse(
            "乱串不该触发「本轮相关补充规则」段落：\n${out.take(600)}",
            out.contains("本轮相关补充规则")
        )
    }
}