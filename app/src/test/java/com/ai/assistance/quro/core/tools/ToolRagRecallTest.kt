package com.ai.assistance.quro.core.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 回归测试：`match_intent`（RAG 检索层）的中文口语召回质量。
 *
 * ## 为什么必须是断言测试而不是 Probe
 * 改造前 [ToolCapabilityDirectory.matchToolsByIntent] 只做**整句包含**
 * （`intent.contains(useCase) || useCase.contains(intent)`），而没有手写条目的工具
 * 其 useCases 就是 [autoInfo] 填的**工具自己的 description** —— 于是「设个闹钟」永远匹配不上
 * 「设置/添加闹钟（支持重复）」。实测 14 条最普通的中文口语只召回 **2 条（14%）**，
 * 榜首恒为噪声 `cms_toolbox`。
 *
 * 这类退化**不会让任何编译或既有用例失败**，只会让用户在真机上得到「AI 查不到这个能力」。
 * 所以这里把它钉死成断言。
 *
 * ## 检索层现在的实现要点
 * 分词（CJK bigram + 英文 `[_-]+`）+ 同义词归一（[ToolTextMatcher]）+ 多字段加权评分，
 * 并有零命中兜底（返回 priority 前若干候选，**绝不返回空**——空列表会被渲染成
 * 「未找到匹配的工具」，正是用户报的「查不到」）。
 */
class ToolRagRecallTest {

    /**
     * 探针 = (用户口语原话, 必须被召回的工具)。
     * 全部是普通用户会说的话，不是工具名。
     */
    private val probes = listOf(
        "画一张海报" to "codecanvas_markup",
        "生成图片" to "image_gen",
        "做个APP打包成APK" to "build_apk",
        "搜一下最近的新闻" to "web_search",
        "打开微信" to "launch_app",
        "给我念一段文字" to "speak",
        "设个闹钟" to "set_alarm",
        "删掉这个文件" to "delete_file",
        "看看屏幕" to "read_screen",
        "导出APK" to "export_apk",
        "做个微信小程序" to "miniapp",
        "查一下设备信息" to "get_device_info",
        "念一首诗" to "speak",
        "把这段代码跑一下" to "run_code",
    )

    /**
     * 必须排进前三的关键查询。
     * 「召回」只保证模型能在候选里找到；「排前 3」才保证弱模型真会去用——
     * 候选排到第 20 位，弱模型往往当没查到。
     */
    private val mustRankTop3 = listOf(
        Triple("搜一下最近的新闻", "web_search", 1),
        Triple("念一首诗", "speak", 1),
        Triple("画一张海报", "codecanvas_markup", 3),
        Triple("设个闹钟", "set_alarm", 3),
    )

    @Before
    fun install() {
        ToolCapabilityDirectory.install(buildQuroRegistry(null).specs())
    }

    @Test
    fun 口语查询必须全部召回() {
        val missed = mutableListOf<String>()
        for ((query, expected) in probes) {
            val matched = ToolCapabilityDirectory.matchToolsByIntent(query).map { it.name }
            if (expected !in matched) missed += "「$query」未召回 $expected，实际前5=${matched.take(5)}"
        }
        assertTrue(
            "match_intent 召回回退：${missed.size}/${probes.size} 条未命中\n" + missed.joinToString("\n"),
            missed.isEmpty(),
        )
    }

    @Test
    fun 关键查询的目标工具必须排进前三() {
        for ((query, expected, top) in mustRankTop3) {
            val matched = ToolCapabilityDirectory.matchToolsByIntent(query).map { it.name }
            val rank = matched.indexOf(expected) + 1
            assertTrue(
                "「$query」期望 $expected 排进前 $top，实际第 $rank（${matched.take(5)}）",
                rank in 1..top,
            )
        }
    }

    /**
     * 「有内容但一个字段都没命中」必须有兜底候选，而不是空列表——
     * 空会被渲染成「未找到匹配的工具」，正是用户报的「AI 查不到」。
     *
     * 注意不含**全空白**输入：那种情况压根没有意图，返回空是正确的，
     * 由 [QuroToolRouter.matchIntent] 的 isBlank 分支给指引文案（见 [ToolRagCoverageTest]）。
     */
    @Test
    fun 零命中必须有兜底候选而不是空列表() {
        listOf("asdkjhqwezcxvbnm", "???", "①②③", "zzzz qqqq").forEach { q ->
            val r = ToolCapabilityDirectory.matchToolsByIntent(q)
            assertTrue("「$q」返回了空列表，模型会直接告诉用户「没找到」", r.isNotEmpty())
        }
    }

    /** 全空白/空串是「无意图」，返回空是对的，但要给上层留出兜底指引的空间。 */
    @Test
    fun 全空白输入返回空但上层必须有指引() {
        listOf("", "   ", "\n\t").forEach { q ->
            assertTrue("「$q」应当返回空（无意图）", ToolCapabilityDirectory.matchToolsByIntent(q).isEmpty())
        }
        val router = QuroToolRouter(buildQuroRegistry(null).specs())
        listOf("", "   ").forEach { q ->
            val out = router.handle(
                "tool_router",
                """{"action":"match_intent","intent":${org.json.JSONObject.quote(q)}}""",
            )
            assertTrue("空意图经 tool_router 必须给出示例指引，实际：$out", out.contains("match_intent"))
        }
    }

    /** 工具全名（英文）必须能直接命中自己，否则模型已知名字时也查不到。 */
    @Test
    fun 工具全名必须能召回自己() {
        listOf("web_search", "set_alarm", "codecanvas_markup", "export_apk").forEach { name ->
            val matched = ToolCapabilityDirectory.matchToolsByIntent(name).map { it.name }
            assertTrue("按工具全名 $name 检索未召回自己（${matched.take(3)}）", name in matched)
        }
    }

    /** 结果必须稳定可测：同输入两次调用顺序一致，否则无法写断言也难以排查。 */
    @Test
    fun 检索结果必须稳定可复现() {
        probes.forEach { (query, _) ->
            val a = ToolCapabilityDirectory.matchToolsByIntent(query).map { it.name }
            val b = ToolCapabilityDirectory.matchToolsByIntent(query).map { it.name }
            assertEquals("「$query」两次检索顺序不一致：$a vs $b", a, b)
        }
    }
}
