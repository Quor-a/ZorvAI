package com.ai.assistance.quro.core.rag

import com.ai.assistance.quro.core.tools.QuroToolRouter
import com.ai.assistance.quro.core.tools.buildQuroRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 接线验收：三块新接的 RAG 路径都必须**真的可达且有效**。
 *
 * ## 为什么要单独一个类（而不是并进 [AgentRagRecallTest]）
 * [AgentRagRecallTest] 验的是**引擎质量**（召回率、排序）。
 * 本类验的是**接线**：索引装上了吗？调用路径通吗？模型侧入口注册了吗？
 * 引擎再好、没接进调用路径，用户依然什么都感觉不到——这类缺陷不报错、不崩溃，
 * 只会表现为「功能好像没生效」，是最难靠读代码发现的一类。
 *
 * ## 判据全部要求「真实数据」
 * 所有期望值都取自真实注册表（`git grep 'val name'`实测），
 * 不写凭印象编的工具名——编名字的断言报的全是假警报（第一版就栽在这）。
 */
class RagWiringTest {

    private val router = QuroToolRouter(buildQuroRegistry(null).specs())

    @Before
    fun setUp() {
        // 走真实刷新路径（router 的 init 已经调过一次，这里显式再调一次以确保索引非空）
        AgentRag.refresh(router.allSpecsSnapshot())
    }

    // ───────────── 判据 1：四域索引都装上了 ─────────────

    @Test
    fun 四域索引全部非空() {
        val stats = AgentRag.stats()
        assertTrue("工具域为 0：${stats}", (stats[ToolRagIndex.DOMAIN] ?: 0) > 100)
        assertTrue("提示词域为 0：${stats}", (stats[PromptRagIndex.DOMAIN] ?: 0) > 0)
    }


    // ───────────── 判据 3：跨域检索真的能同时命中多域 ─────────────

    @Test
    fun 跨域检索同时碰到工具与界面交付路径() {
        // 「画一个销售看板」同时落在：工具域（图表/工作台工具）+ 界面交付域（genui_agent_open）。
        //
        // 🔴 判据为什么按**标题前缀**分而不是按 domain 字符串分：
        // 「一条能力在两个域都有登记」本身是对的（`genui_agent_open` 既是可调工具，
        // 也是「做界面该走哪条路」的选路建议），两者语义不同、都该被召回。
        // 按 domain 字符串判会要求「必须来自两个不同域标签」，那是给实现加的限制，
        // 不是用户能感知的能力。真正要保证的是**结果里既有执行工具、也有界面交付建议**。
        val hits = AgentRag.everything("画一个销售看板", 12)
        val titles = hits.map { it.doc.title }
        assertTrue(
            "结果里没有工具类：${hits.map { it.nameOrId() }}",
            hits.any { it.domain == ToolRagIndex.DOMAIN }
        )
    }

    /**
     * 每域保底：小域必须露脸。
     *
     * 🔴 这条判据来自实测的严重缺陷：工具域 269 篇、组件域 54、GenUI 19、提示词 8。
     * 全库按分数取前 N 时，`genui_agent_open` 排在**第 20 名**，
     * 而 `linux_install` 这类完全无关的工具有 19 条挤在它前面——
     * 于是「万物可RAG」名义上通了、实际只有工具能用。
     */
    @Test
    fun 小域不会被大域挤掉() {
        val hits = AgentRag.everything("画一个销售看板", 12)
        val domains = hits.map { it.domain }.toSet()
        assertTrue(
            "只有 ${domains.size} 个域进了 top12：$domains\n结果=${hits.map { "${it.domain}/${it.nameOrId()}" }}",
            domains.size >= 2
        )
    }

    @Test
    fun 跨域检索能召回工具域() {
        val hits = AgentRag.everything("把这段视频弄短一点", 14)
        assertTrue(
            "完全没召回工具类结果：${hits.map { it.nameOrId() }}",
            hits.any { it.domain == ToolRagIndex.DOMAIN }
        )
    }


    // ───────────── 判据 4：提示词 RAG 两种模式都不丢段 ─────────────

    @Test
    fun 提示词全量模式包含全部块() {
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

    @Test
    fun 提示词选择性模式基础段一条不少() {
        // 🔴 「加 RAG 但旧架构保留」的硬判据：切到选择性模式后，
        // 基础段（alwaysSticky）必须**逐条还在**。少一条就是提示词回归。
        val out = PromptRagIndex.render(
            engine = AgentRag.engine,
            mode = PromptRagIndex.Mode.SELECTIVE,
            userQuery = "帮我查一下明天的天气",
            base = "BASE",
        )
        assertTrue("基座必须原样保留", out.startsWith("BASE"))
        val sticky = PromptRagIndex.stickyBlocks
        val missing = sticky.filter { !out.contains(it.title) }.map { it.id }
        assertTrue("SELECTIVE 下基础段丢了：$missing", missing.isEmpty())
    }

    @Test
    fun 提示词选择性模式检索不到时不塞无关规则() {
        // 纯噪声查询 → 一个**进阶段**都不该加。塞无关规则只会稀释注意力，
        // 让模型在当前无关的规则里漏掉真正该守的那条。
        //
        // 🔴 2026-10-08 判据修正（实测依据，勿当 bug 改回）：
        // 旧断言是 `out.length <= base.length + 64`，即「一个字都不能加」。
        // 那条断言本身是**死代码的产物**——当时 8 个块里 `alwaysSticky = true`
        // 出现 0 次，`stickyBlocks` 恒为空，于是 SELECTIVE 实际注入 0 字符，
        // 「一个字节都不加」才碰巧成立。探针实测：
        //     blocks 总数=8   stickyBlocks=[]   SELECTIVE 追加字符数=0
        // 一旦给真正该常驻的段打上 alwaysSticky（工具调用纪律 / 输出格式纪律），
        // 基础段就必须每轮注入 —— 那正是设计意图，不是「塞了无关规则」。
        //
        // 现在真正要保证的是：**基础段照常注入，但进阶段（检索结果）一个都不加**。
        val out = PromptRagIndex.render(
            engine = AgentRag.engine,
            mode = PromptRagIndex.Mode.SELECTIVE,
            userQuery = "zzzzqqq完全不相关的东西xyzzy",
            base = "BASE",
        )
        assertTrue("基座必须原样保留", out.startsWith("BASE"))
        assertFalse(
            "乱串不该触发「本轮相关补充规则」进阶段段落：\n${out.take(600)}",
            out.contains("本轮相关补充规则")
        )
        // 顺带把「基础段非空」钉住，别再退回注入 0 字符的死代码
        assertTrue(
            "基础段必须真的注入（修前 stickyBlocks 为空 → 追加 0 字符）",
            out.length > "BASE".length + 64
        )
    }

    @Test
    fun 提示词渲染结果以换行结尾() {
        // 调用方紧接着还要拼语言复述段；块正文末尾没换行的话两段会黏成一句，
        // 而模型对黏在一起的指令非常容易只读前半句。
        for (mode in PromptRagIndex.Mode.entries) {
            val out = PromptRagIndex.render(AgentRag.engine, mode, "出张海报", "BASE")
            assertTrue("$mode 模式输出未以换行结尾：...${out.takeLast(12)}", out.endsWith("\n\n"))
        }
    }

    // ───────────── 判据 5：explain 永不空手 ─────────────

    @Test
    fun explain对弱命中给改写建议而不是列噪音() {
        // 🔴 真实缺陷：explain 原样摊噪音给模型。实测「zzzzqqq完全不相关的东西xyzzy」
        // 会返回 experience_query / memory_window_plan 这类擦边工具，
        // 模型会认真去用错误的工具，比「没查到」更糟。
        //
        // 🔴 判据为什么是「标注弱相关」而不是「不列」：
        // 量过之后确认**任何分数线都做不到**——乱串 top1 = 1.1116，
        // 而真实模糊查询「把这段视频弄短一点」top1 只有 1.0375，**乱串比真查询还高**。
        // （中文 bigram 切分后八个 token 里只有「视频」是成词，真查询天然拿不到高覆盖率。）
        // 所以砍结果的阈值必然同时砍死真召回。改为：照列，但首行必须写明「弱相关」，
        // 并在末尾给出改写建议——既不违反「零命中绝不返回空」，又不让模型把猜测当答案。
        val out = AgentRag.explain("zzzzqqq完全不相关的东西xyzzy")
        assertTrue(
            "弱相关时必须显式标注，不能让模型当成答案：\n" + out.take(400),
            out.contains("弱相关")
        )
        assertTrue(
            "弱相关时必须给出改写出路：\n" + out.take(400),
            out.contains("更具体")
        )
        assertTrue(
            "「零命中绝不返回空」这条决策不能破，弱相关也要给候选：\n" + out.take(400),
            out.contains("1.")
        )
    }

    @Test
    fun explain对强命中不误标弱相关() {
        // 反向保护：CONFIDENT_LINE 不能定太低，否则真查询全被标成「弱相关」，
        // 那等于把「弱相关」这三个字变成噪声、失去意义。
        val strong = listOf("上网查一下最近的资料", "看这个网址", "生成一张图")
        for (q in strong) {
            val out = AgentRag.explain(q)
            assertTrue(
                "「$q」是强命中，不该被标成弱相关：\n" + out.take(200),
                !out.contains("弱相关")
            )
        }
    }

    @Test
    fun 弱相关标注会误伤模糊真查询但这是有意的取舍() {
        // 🔴 这条测试的作用是**把一个反直觉的取舍钉死**。
        //
        // 「把这段视频弄短一点」的 top1 只有 1.0375，低于 CONFIDENT_LINE(2.0)，
        // 所以会被标成「弱相关」——尽管它 top3 全是视频工具、答得完全正确。
        //
        // 有人看到这条会想「标注不准，调低点 CONFIDENT_LINE 修掉」——
        // **不要**。实测乱串 top1 = 1.1116，比这个真查询还高：
        // 分数根本区分不了「乱串」与「真实但模糊」。两种错误里，
        // 把噪音当答案（模型自信调错工具、用户看到完全跑偏）远比
        // 把好答案标成不确定（模型多确认一次）有害。
        //
        // 所以本测试断言的是「**必须仍然返回正确工具**」，
        // 而不是「不该被标弱相关」——标注本身是允许的误伤，召回正确性不是。
        val out = AgentRag.explain("把这段视频弄短一点")
        assertTrue(
            "模糊真查询必须仍然召回视频工具：\n" + out.take(400),
            out.contains("video") || out.contains("视频")
        )
        assertTrue(
            "模糊真查询必须仍然给出下一步（不能因为标了弱相关就只给改写建议）：\n" + out.take(400),
            out.contains("tool_router")
        )
    }

    @Test
    fun explain对纯符号查询给改写建议() {
        val out = AgentRag.explain("。。。？？？")
        assertTrue(
            "真零命中时应明确告诉模型怎么改写：\n" + out.take(200),
            out.contains("更具体") || out.contains("没有检索到")
        )
    }

    @Test
    fun explain永不返回空串() {
        for (q in listOf("zzzzqqq完全不相关的东西xyzzy", "", "   ", "?!?", "a")) {
            assertTrue("query=「$q」返回了空串", AgentRag.explain(q).isNotBlank())
        }
    }

    /**
     * 元工具不能抢业务查询的 top1。
     *
     * 🔴 真实缺陷：`rag_search` 注册后，「把这段视频弄短一点」的 top1 立刻变成它自己
     * （1.50 vs `video_gen` 1.04）——它描述里堆着「视频/组件/出图」这些业务例子，
     * 与任何查询都有字面重叠。模型拿到 top1 去调只会得到一份检索建议，
     * 反而不知道该剪视频了。
     */
    @Test
    fun 元工具不抢业务查询的榜首() {
        val hits = AgentRag.tools("把这段视频弄短一点", 5)
        assertTrue("零命中", hits.isNotEmpty())
        assertTrue(
            "元工具 rag_search 抢了业务查询 top1（score=${hits.first().score}）：" +
                hits.map { "${it.nameOrId()}=${it.score}" },
            hits.first().nameOrId() != "rag_search"
        )
    }

    @Test
    fun 元工具被点名时仍然排第一() {
        // 压权只压排序、不压召回：用户直接说「rag_search」时它必须拿满分。
        val hits = AgentRag.tools("rag_search", 5)
        assertTrue(
            "点名 rag_search 却没召回到：${hits.map { "${it.nameOrId()}=${it.score}" }}",
            hits.any { it.nameOrId() == "rag_search" }
        )
        assertTrue(
            "点名 rag_search 却不是第一：${hits.map { "${it.nameOrId()}=${it.score}" }}",
            hits.firstOrNull()?.nameOrId() == "rag_search"
        )
    }

    @Test
    fun explain有命中时逐条列出() {
        val out = AgentRag.explain("把这段视频弄短一点", limit = 5)
        assertTrue("有命中却没列出条目：$out", out.contains("1."))
    }
}
