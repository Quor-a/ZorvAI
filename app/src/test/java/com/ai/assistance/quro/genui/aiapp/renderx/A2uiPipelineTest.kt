package com.ai.assistance.quro.genui.aiapp.renderx

import com.ai.assistance.quro.genui.sdk.interop.FlatDocToGenUI
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A2UI 通道**端到端**回归：解析（[FlatDoc]）→ 桥接（[FlatDocToGenUI]）→ UISpec。
 *
 * 为什么要有这一层：单测过去只验到 FlatDoc 有节点，但用户看到的是**桥接后的 UISpec**。
 * 桥接层丢文本/丢样式，界面就是「有框架没内容」或「一片空白」，FlatDoc 层面测试照样全绿。
 * 这里把模型实际写过的 5 种形状各跑一遍全链路，锁死：
 *   · 每种形状都能解析（否则通道退化成原文画布）
 *   · 文字进到 properties（否则渲染出空框）
 *   · 样式进到 UIStyle（否则卡片没底色、没有层次）
 *   · 根节点被包成 scroll（否则长内容看不全）
 */
class A2uiPipelineTest {

    private fun convert(content: String, isYaml: Boolean = false) =
        FlatDoc.parse(content, isYaml)?.let { doc ->
            FlatDocToGenUI.convert(
                root = doc.root,
                nodes = doc.nodes.mapValues { (_, n) ->
                    FlatDocToGenUI.Node(n.id, n.type, n.text, n.kids, n.props)
                }
            )
        }

    /** 递归收集 UISpec 里所有组件的 properties.text */
    private fun texts(c: com.ai.assistance.quro.genui.sdk.dsl.UIComponent, out: MutableList<String> = mutableListOf()): List<String> {
        (c.properties as? JsonObject)?.get("text")?.let { t ->
            if (t is JsonPrimitive) out.add(t.content)
        }
        c.children.forEach { texts(it, out) }
        return out
    }

    private fun nodeById(c: com.ai.assistance.quro.genui.sdk.dsl.UIComponent, predicate: (com.ai.assistance.quro.genui.sdk.dsl.UIComponent) -> Boolean): com.ai.assistance.quro.genui.sdk.dsl.UIComponent? {
        if (predicate(c)) return c
        c.children.forEach { nodeById(it, predicate)?.let { hit -> return hit } }
        return null
    }

    // ① 上游本家写法：root + components 邻接表 + t（上游提示词 9.12 的推荐形状）
    private val adjacent = """
        {"title":"今日状态","root":"col","components":{
          "col":{"t":"column","children":["h","d"],"padding":16},
          "h":{"t":"heading","text":"今日状态"},
          "d":{"t":"card","children":["t1"],"bg":"#FFF6F1EC","radius":16},
          "t1":{"t":"text","text":"生命 72 / 金币 120","size":15}}}
    """.trimIndent()

    // ② components 为数组 + root 为完整节点对象
    private val arrayShape = """
        {"root":{"id":"r","t":"column","children":["a","b"]},
         "components":[
           {"id":"a","t":"heading","text":"标题"},
           {"id":"b","t":"text","text":"正文"}]}
    """.trimIndent()

    // ③ 裸嵌套节点树：无信封、子节点内联、文本写在 value、样式写在顶层
    private val bareTree = """
        {"type":"column","padding":16,"children":[
          {"type":"card","title":"卡片标题","corner_radius":18,"backgroundColor":"#FFEFE7E0","children":[
            {"type":"text","value":"卡片正文","textSize":14}
          ]}
        ]}
    """.trimIndent()

    // ④ 官方 A2UI 协议（JSONL 报文，PascalCase 组件名 + child/children）
    private val official = """
        {"version":"v0.9","createSurface":{"surfaceId":"main","catalogId":"zorv"}}
        {"version":"v0.9","updateComponents":{"surfaceId":"main","components":[
          {"id":"root","component":"Column","children":["h","c"]},
          {"id":"h","component":"Text","text":"官方协议标题","variant":"h2"},
          {"id":"c","component":"Card","children":["t"]},
          {"id":"t","component":"Text","text":"官方协议正文"}]}}
    """.trimIndent()

    // ⑤ YAML 变体
    private val yamlDoc = """
        title: 状态面板
        root: col
        components:
          col:
            t: column
            children: [h, t1]
          h:
            t: heading
            text: 状态面板
          t1:
            t: text
            text: 一切正常
    """.trimIndent()

    @Test
    fun `① 邻接表 全链路`() {
        val spec = convert(adjacent)
        assertNotNull("邻接表必须能解析并桥接", spec)
        assertEquals("scroll", spec!!.root.type)
        val all = texts(spec.root)
        assertTrue("标题要进 properties.text：$all", all.contains("今日状态"))
        assertTrue("正文要进 properties.text：$all", all.contains("生命 72 / 金币 120"))
        val card = nodeById(spec.root) { it.type == "card" }
        assertNotNull("card 节点必须在", card)
        assertEquals("#FFF6F1EC", card!!.style.backgroundColor)
        assertEquals(16f, card.style.cornerRadius!!, 0.01f)
    }

    @Test
    fun `② 数组形态 全链路`() {
        val spec = convert(arrayShape)
        assertNotNull(spec)
        val all = texts(spec!!.root)
        assertTrue(all.contains("标题"))
        assertTrue(all.contains("正文"))
    }

    @Test
    fun `③ 裸嵌套树 全链路 value 与顶层样式不丢`() {
        val spec = convert(bareTree)
        assertNotNull("裸嵌套树必须能解析并桥接", spec)
        val all = texts(spec!!.root)
        assertTrue("value 要当文本：$all", all.contains("卡片正文"))
        val card = nodeById(spec.root) { it.type == "card" }
        assertNotNull(card)
        assertEquals("#FFEFE7E0", card!!.style.backgroundColor)
        assertEquals(18f, card.style.cornerRadius!!, 0.01f)
    }

    @Test
    fun `④ 官方协议 JSONL 全链路`() {
        val spec = convert(official)
        assertNotNull("官方协议必须能解析并桥接", spec)
        val all = texts(spec!!.root)
        assertTrue("官方协议文本要取到：$all", all.any { it.contains("官方协议标题") })
        assertTrue("官方协议正文要取到：$all", all.any { it.contains("官方协议正文") })
    }

    @Test
    fun `⑤ YAML 变体 全链路`() {
        val spec = convert(yamlDoc, isYaml = true)
        assertNotNull("YAML 必须能解析并桥接", spec)
        val all = texts(spec!!.root)
        assertTrue(all.contains("状态面板"))
        assertTrue(all.contains("一切正常"))
    }

    @Test
    fun `解析失败的脏输入不能抛异常`() {
        listOf("", "   ", "not json at all", "{", "[1,2,3]", "{\"foo\":1}").forEach { bad ->
            val doc = FlatDoc.parse(bad, isYaml = false)
            if (doc != null) assertNotNull(convert(bad))
        }
    }

    @Test
    fun `扁平写法的样式不能被丢掉`() {
        // 这是本轮修掉的一个真问题：邻接表里样式平铺在节点上（bg/radius/size），
        // 旧实现只读 props 子对象 → 底色圆角全丢 → 卡片隐形、没有层次。
        val doc = FlatDoc.parse(adjacent, isYaml = false)!!
        val card = doc.nodes["d"]!!
        assertEquals("#FFF6F1EC", card.props["bg"])
        assertEquals("16", card.props["radius"])
        assertEquals("15", doc.nodes["t1"]!!.props["size"])
        assertEquals("16", doc.nodes["col"]!!.props["padding"])
        // 结构键不能混进 props（否则会被当样式喂给渲染器）
        assertTrue(!card.props.containsKey("children"))
        assertTrue(!card.props.containsKey("t"))
    }

    @Test
    fun `heading 级别不能丢`() {
        val doc = FlatDoc.parse(
            """{"root":"c","components":{"c":{"t":"column","children":["a","b"]},
               "a":{"t":"h1","text":"一级"},
               "b":{"t":"h3","text":"三级"}}}""".trimIndent(),
            isYaml = false
        )!!
        assertEquals("heading", doc.nodes["a"]!!.type)
        assertEquals("1", doc.nodes["a"]!!.props["level"])
        assertEquals("3", doc.nodes["b"]!!.props["level"])
    }

    @Test
    fun `props 子对象与平铺样式可以混用 平铺优先语义一致`() {
        val doc = FlatDoc.parse(
            """{"root":"c","components":{"c":{"t":"card","props":{"bg":"#FF000000"},"radius":8,"children":["x"]},
               "x":{"t":"text","props":{"text":"写在 props 里的文本"}}}}""".trimIndent(),
            isYaml = false
        )!!
        assertEquals("#FF000000", doc.nodes["c"]!!.props["bg"])
        assertEquals("8", doc.nodes["c"]!!.props["radius"])
        assertEquals("写在 props 里的文本", doc.nodes["x"]!!.text)
    }

    @Test
    fun `标题级别必须落到组件类型上 否则所有标题都掉进 14sp 正文档`() {
        // 这是本轮修掉的第二个真问题，也是最影响观感的一个：
        // SDK 的 HeadingRenderer 用 typographyConfig(component.type) 定字号，而它只认 heading1..6。
        // 早先 h1/h2/h3/heading/title 全被映射成裸 "heading" → typographyConfig 落到
        // else -> 14sp/Normal → 标题和正文一样大，整页没有层次（"看着很平"）。
        val spec = convert(
            """{"root":"c","components":{"c":{"t":"column","children":["a","b","d"]},
               "a":{"t":"h1","text":"一级标题"},
               "b":{"t":"h3","text":"三级标题"},
               "d":{"t":"heading","text":"裸heading"}}}""".trimIndent()
        )!!
        assertEquals("heading1", nodeById(spec.root) { it.properties.text() == "一级标题" }!!.type)
        assertEquals("heading3", nodeById(spec.root) { it.properties.text() == "三级标题" }!!.type)
        // 裸 heading 缺省按上游默认 level=2
        assertEquals("heading2", nodeById(spec.root) { it.properties.text() == "裸heading" }!!.type)
        // 三档必须是三个不同的类型名，否则字号还是一样的
        val seen = listOf("heading1", "heading2", "heading3")
        assertEquals(3, seen.toSet().size)
    }

    @Test
    fun `容器 gap 要传成 SDK 的 spacing 属性`() {
        // SDK 的 column/row 读的是组件属性 spacing（默认 8dp），不是样式；
        // 模型按上游 flat 习惯写 gap，此前一路丢到默认值 → 该松的地方没松开。
        val spec = convert(
            """{"root":"c","components":{"c":{"t":"column","gap":20,"children":["a"]},
               "a":{"t":"text","text":"x"}}}""".trimIndent()
        )!!
        val col = nodeById(spec.root) { it.type == "column" }!!
        assertEquals(20f, (col.properties["spacing"] as JsonPrimitive).content.toFloat(), 0.01f)
    }

    @Test
    fun `progress 比例与百分比两种写法都要画对`() {
        // 上游转换器一律 /100（写 0.6 的进度条几乎不动），上游 FlatRenderer 又一律当比例（写 60 会溢出）。
        // 这里按数值判断：>1 当百分比，<=1 当比例。
        fun pv(v: String): Float = convert(
            """{"root":"c","components":{"c":{"t":"column","children":["p"]},
               "p":{"t":"progress","value":$v}}}""".trimIndent()
        )!!.let { nodeById(it.root) { n -> n.type == "progress" }!! }
            .properties.let { (it["value"] as JsonPrimitive).content.toFloat() }

        assertEquals(0.6f, pv("0.6"), 0.001f)
        assertEquals(0.6f, pv("60"), 0.001f)
        assertEquals(1f, pv("150"), 0.001f)
    }

    @Test
    fun `A2UI 每种写法产出的组件类型都必须真的注册了渲染器`() {
        // 未注册的类型在渲染端只会打一条「未知组件」然后什么都不画 —— 节点消失、内容不见。
        // 这类问题在 FlatDoc 层测不出来，只有拿真注册表核对才拦得住。
        val registered = com.ai.assistance.quro.genui.sdk.components.BuiltinComponents
            .createRegistry().registeredTypes()

        // 白名单 13 个规范名 + 模型高频写的别名
        val spellings = listOf(
            "text", "heading", "h1", "h2", "h3", "h4", "title", "sub", "subtitle",
            "column", "row", "scroll", "card", "panel", "container", "button", "btn",
            "divider", "line", "spacer", "image", "progress", "chip", "input",
            "caption", "body", "paragraph", "label", "list"
        )

        val bad = LinkedHashMap<String, Set<String>>()
        spellings.forEach { t ->
            val spec = convert(
                """{"root":"c","components":{"c":{"t":"column","children":["n"]},
                   "n":{"t":"$t","text":"x"}}}""".trimIndent()
            ) ?: run { bad[t] = setOf("<整篇解析失败>"); return@forEach }
            // scroll 是 convert 给根自动包的外层，不算模型写的类型
            val produced = types(spec.root) - "scroll"
            val unregistered = produced.filter { it !in registered }
            if (unregistered.isNotEmpty()) bad[t] = unregistered.toSet()
        }

        assertTrue("这些 A2UI 写法产出了没有渲染器的组件类型：$bad", bad.isEmpty())
    }
}

/** 递归收集组件树里出现的所有 type */
private fun types(
    c: com.ai.assistance.quro.genui.sdk.dsl.UIComponent,
    out: MutableSet<String> = mutableSetOf()
): Set<String> {
    out.add(c.type)
    c.children.forEach { types(it, out) }
    return out
}

/** 测试用小工具：取组件的 properties.text */
private fun JsonObject?.text(): String? = (this?.get("text") as? JsonPrimitive)?.content
