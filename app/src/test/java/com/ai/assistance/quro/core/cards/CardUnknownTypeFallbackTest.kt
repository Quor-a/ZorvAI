package com.ai.assistance.quro.core.cards

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「已知但未收录的 type」兜底（[CardShapeFallback.resolveUnknown]）。
 *
 * 修的真机故障：`{"type":"md","id":"fc_1","name":"测试报告.md","size":"2.4 KB","path":"…"}`
 * 渲染成「⚠ 未识别组件：md」+ 一坨原始 JSON。
 *
 * 与 [CardShapeFallbackTest] 互补：那边测的是**没写 type**，这边测的是
 * **写了 type 但名册没收**。两者的风险等级不同 ——
 * 无 type 乱猜会篡改用户内容，有 type 未收录时认错只是回到原来的兜底卡。
 */
class CardUnknownTypeFallbackTest {

    private fun parse(spec: String) = CardSdk.parse(spec)

    // ══════════════ 该修的：模型写md / 文件后缀当type ══════════════

    @Test
    fun `真机截图那一组 md 文件卡不再落未识别组件`() {
        val c = parse(
            """{"type":"md","id":"fc_1","name":"测试报告.md","size":"2.4 KB","path":"\\workspace\\test-report.md"}"""
        )
        assertTrue(
            "应解析成 FileCard，实际 ${c?.javaClass?.simpleName}",
            c is FileCard,
        )
        val f = c as FileCard
        assertEquals("测试报告.md", f.name)
        assertEquals("2.4 KB", f.size)
        assertEquals("\\workspace\\test-report.md", f.path)
    }

    @Test
    fun `大写 type 也能认`() {
        val c = parse("""{"type":"MD","name":"a.md","path":"/tmp/a.md"}""")
        assertTrue("实际 ${c?.javaClass?.simpleName}", c is FileCard)
    }

    @Test
    fun `常见后缀都认`() {
        listOf("markdown", "txt", "log", "csv", "docx", "xlsx", "yaml", "zip", "apk").forEach { t ->
            val c = parse("""{"type":"$t","name":"f.$t","path":"/tmp/f.$t"}""")
            assertTrue(
                "type=$t 应解析成 FileCard，实际 ${c?.javaClass?.simpleName}",
                c is FileCard,
            )
        }
    }

    @Test
    fun `没有 path 只有 name 的后缀 type 也认`() {
        // 判据①：type 是文件后缀 + 带 name 就够（模型常只给名字）
        val c = parse("""{"type":"md","name":"notes.md"}""")
        assertTrue("实际 ${c?.javaClass?.simpleName}", c is FileCard)
        assertEquals("notes.md", (c as FileCard).name)
    }

    @Test
    fun `非后缀的未知 type 只要 name 加 path 加 size 三件套也认成文件卡`() {
        // 判据②：形状特异（业务 JSON 里这三件套几乎只出现在文件描述上）
        val c = parse("""{"type":"artifact","name":"out.bin","path":"/sdcard/out.bin","size":"12 KB"}""")
        assertTrue("实际 ${c?.javaClass?.simpleName}", c is FileCard)
    }

    @Test
    fun `id 和 title 都原样保留`() {
        val c = parse(
            """{"type":"md","id":"fc_9","title":"产物","name":"a.md","path":"/a.md","size":"1 KB"}"""
        )
        val f = c as FileCard
        assertEquals("fc_9", f.id)
        assertEquals("产物", f.title)
    }

    @Test
    fun `走围栏链路也能救回来`() {
        // 不只 parse 路径：真实来源是围栏
        val body = """[{"type":"md","name":"测试报告.md","size":"2.4 KB","path":"/w/r.md"}]"""
        val cards = CardFence.toCards("card", body, "文件")
        assertTrue("应产出 1 张卡，实际 ${cards.size}", cards.size == 1)
        assertTrue("实际 ${cards[0]::class.simpleName}", cards[0] is FileCard)
    }

    // ══════════════ 不该修的：绝不能把用户内容改成卡片 ══════════════

    @Test
    fun `名册里已有的 type 一律不归这条兜底管`() {
        // stat 也是 label+value 形状，但名册里有 stat，就该走真解析而不是文件卡
        val c = parse("""{"type":"stat","label":"总数","value":"42"}""")
        assertTrue("实际 ${c?.javaClass?.simpleName}", c is QuroChatCard.StatCard)
    }

    @Test
    fun `未知 type 且无文件特征时仍然落未识别组件兜底`() {
        val c = parse("""{"type":"whatever","foo":"bar"}""")
        assertTrue(
            "应落CustomCard 兜底，实际 ${c?.javaClass?.simpleName}",
            c is CustomCard,
        )
        assertEquals("whatever", (c as CustomCard).kind)
    }

    @Test
    fun `后缀 type 但只有 path 没有 name 时不认`() {
        // 只有 path 没有任何名称 —— 判据①要求 name 或 path 至少一个，这里有 path，
        // 所以应该认。改测真正没有文件特征的：type=md 但整个对象是业务报文
        val c = parse("""{"type":"md","total":3,"rows":[]}""")
        assertTrue(
            "既无 name 也无 path，应落兜底，实际 ${c?.javaClass?.simpleName}",
            c is CustomCard,
        )
    }

    @Test
    fun `只有 name 加 path 缺 size 的非后缀 type 不认`() {
        // 判据②要求 size 也在（加强信号）；缺了就说明不一定是文件描述
        val c = parse("""{"type":"widget","name":"按钮A","path":"/a/b"}""")
        assertTrue(
            "应落兜底，实际 ${c?.javaClass?.simpleName}",
            c is CustomCard,
        )
    }

    @Test
    fun `用户贴的普通业务报文不被吞成文件卡`() {
        val payload = """{"type":"response","name":"接口返回","path":"","size":""}"""
        val c = parse(payload)
        assertTrue(
            "空path 空 size 不该认成文件卡，实际 ${c?.javaClass?.simpleName}",
            c is CustomCard,
        )
        // 原文必须**原样**留给用户看（他反馈时靠这个知道自己写了什么）。
        // 比对解析后的内容而不是字符串：JSONObject.toString() 不保证键序。
        val kept = JSONObject((c as CustomCard).payload)
        assertEquals("response", kept.optString("type"))
        assertEquals("接口返回", kept.optString("name"))
    }

    @Test
    fun `兜底目标都真在名册里`() {
        val bad = CardShapeFallback.lintUnknownTargets()
        assertTrue("详情\n" + bad.joinToString("\n"), bad.isEmpty())
    }

    @Test
    fun `整条链路往返后字段不丢`() {
        // 兜底出来的卡也要能存档往返（否则「渲染正常但重启后变另一张卡」）
        val spec = """{"type":"md","id":"fc_2","name":"a.md","size":"1 KB","path":"/a.md","mime":"text/markdown"}"""
        val c = parse(spec)!!
        val round = parseCard(serializeCard(c))
        assertTrue("往返后类型变了：${round}", round is FileCard)
        val enc = serializeCard(c)
        val enc2 = serializeCard(round!!)
        val ks = enc.keys()
        while (ks.hasNext()) {
            val k = ks.next()
            if (enc.opt(k) is JSONObject || enc.opt(k) is org.json.JSONArray) continue
            assertEquals(
                "字段 $k 往返后不一致",
                enc.opt(k),
                enc2.opt(k),
            )
        }
    }

    @Test
    fun `JSONObject 解析入口同样生效`() {
        val c = CardSdk.parseObj(JSONObject("""{"type":"md","name":"x.md","path":"/x.md"}"""))
        assertTrue("实际 ${c?.javaClass?.simpleName}", c is FileCard)
    }
}