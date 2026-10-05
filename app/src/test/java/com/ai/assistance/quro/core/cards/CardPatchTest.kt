package com.ai.assistance.quro.core.cards

import com.ai.assistance.quro.core.tools.CardPatchTool
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CardPatch] 增量更新引擎测试。
 *
 * 重点覆盖三类：**语义边界**（改错/改炸了会怎样）、**原子性**（半张卡比不改更糟）、
 * **失败回喂**（模型下一轮要能自己改对，所以失败信息里必须有合法路径清单）。
 */
class CardPatchTest {

    private fun card(type: String = "stat"): QuroChatCard {
        val s = JSONObject("""{"type":"$type","id":"c1","title":"T","label":"营收","value":"1.2M","trend":"up"}""")
        val c = CardSdk.parseObj(s)
        assertNotNull("样例卡应能构造", c)
        return c!!
    }

    private fun spec(vararg patches: String): JSONObject =
        JSONObject().put("patches", JSONArray().apply { patches.forEach { put(JSONObject(it)) } })

    // ═══════════════ set ═══════════════

    @Test
    fun `set 改顶层标量并往返`() {
        val c = card()
        val r = CardPatch.apply(c, spec("""{"op":"set","path":"/value","value":"9.9M"}"""))
        assertTrue("应成功：${r.errors}", r.ok)
        assertTrue(r.changed)
        val again = CardPatch.describe(r.card!!).firstOrNull { it.startsWith("/value") }
        assertNotNull("describe 应能列出 /value，实际：${CardPatch.describe(r.card!!)}", again)
        assertTrue("值应已更新，实际：$again", again!!.contains("9.9M"))
    }

    @Test
    fun `set 缺 value 报错且不生效`() {
        val c = card()
        val r = CardPatch.apply(c, spec("""{"op":"set","path":"/value"}"""))
        assertFalse(r.ok)
        assertTrue("应提到缺 value：${r.errors}", r.errors.any { it.contains("value") })
        assertFalse("失败时 changed 必须为 false", r.changed)
    }

    // ═══════════════ 路径写法宽松 ═══════════════

    @Test
    fun `三种路径写法等价`() {
        // 模型会混用这几种，宽松解析是刻意设计
        listOf("/value", "value", "\$.value").forEach { p ->
            val r = CardPatch.apply(card(), spec("""{"op":"set","path":"$p","value":"X"}"""))
            assertTrue("路径 $p 应可用：${r.errors}", r.ok)
        }
    }

    @Test
    fun `数组下标三种写法等价`() {
        val c = Card()
        listOf("/items/0/done", "items.0.done", "\$.items[0].done").forEach { p ->
            val r = CardPatch.apply(c, spec("""{"op":"set","path":"$p","value":true}"""))
            assertTrue("路径 $p 应可用：${r.errors}", r.ok)
        }
    }

    @Test
    fun `空段路径判为非法`() {
        val r = CardPatch.apply(card(), spec("""{"op":"set","path":"/a//b","value":1}"""))
        assertFalse("空段通常是拼错，应报错而不是猜", r.ok)
    }

    // 🔴 下面 4 条守的是本引擎真实踩过的 4 个 bug（不是设计用例，是回归锁）：
    @Test
    fun `回归 括号与美元号路径能解析`() {
        // 曾在「去 $ → 去括号 → 切分」顺序里把 `$.items[0].done` 拆出空首段
        assertEquals(listOf("items", "0", "done"), CardPatch.parsePath("$.items[0].done"))
        assertEquals(listOf("value"), CardPatch.parsePath("$.value"))
        assertEquals(listOf("a", "b"), CardPatch.parsePath("/a/b"))
        assertEquals(listOf("a", "b"), CardPatch.parsePath("a.b"))
    }

    @Test
    fun `回归 数组父路径不被误判为不存在`() {
        // resolveForWrite 曾拿「父路径的末段」当叶子段校验，
        // 于是 /items/0/done、/items/- 全被误判成路径不存在
        val c = Card()
        assertTrue(CardPatch.apply(c, spec("""{"op":"set","path":"/items/0/done","value":true}""")).ok)
        assertTrue(CardPatch.apply(c, spec("""{"op":"remove","path":"/items/1"}""")).ok)
        assertTrue(CardPatch.apply(c, spec("""{"op":"append","path":"/items","value":{"text":"C"}}""")).ok)
    }

    @Test
    fun `回归 merge 与 replace 不抛 ConcurrentModification`() {
        // 曾在 `root.keys().forEach { root.remove(it) }` 里边遍历边删
        val r = CardPatch.apply(card(), spec("""{"op":"replace","value":{"label":"新标签"}}"""))
        assertTrue("replace 应成功：${r.errors}", r.ok)
        val d = CardPatch.describe(r.card!!).joinToString("\n")
        assertTrue("新字段应在：$d", d.contains("新标签"))
        assertTrue("旧字段应被清掉：$d", !d.contains("1.2M"))
    }

    @Test
    fun `空路径 set inc remove 被拒`() {
        // 这三个作用在根上等于「清掉/覆盖整张卡」，语义危险且模型几乎不会想这么用
        assertFalse(CardPatch.apply(card(), spec("""{"op":"set","path":"","value":"x"}""")).ok)
        assertFalse(CardPatch.apply(card(), spec("""{"op":"inc","path":"","value":1}""")).ok)
        assertFalse(CardPatch.apply(card(), spec("""{"op":"remove","path":""}""")).ok)
    }

    // ═══════════════ 身份保护 ═══════════════

    @Test
    fun `禁止改 id 与 cardType`() {
        listOf("id", "cardType").forEach { k ->
            val r = CardPatch.apply(card(), spec("""{"op":"set","path":"/$k","value":"zzz"}"""))
            assertFalse("改 $k 必须被拦", r.ok)
            assertTrue("原因应提到受保护：${r.errors}", r.errors.any { it.contains("受保护") || it.contains(k) })
        }
    }

    @Test
    fun `id 挂在更深层也拦`() {
        val r = CardPatch.apply(card(), spec("""{"op":"set","path":"/x/id","value":"zzz"}"""))
        assertFalse(r.ok)
    }

    // ═══════════════ append / remove / merge / inc ═══════════════

    @Test
    fun `append 数组末尾`() {
        val c = Card()
        val r = CardPatch.apply(c, spec("""{"op":"append","path":"/items/-","value":{"text":"C","done":false}}"""))
        assertTrue("应成功：${r.errors}", r.ok)
        val d = CardPatch.describe(r.card!!).joinToString("\n")
        assertTrue("应多出第 3 项：$d", d.contains("/items/2/text = C"))
        assertTrue("原两项应仍在：$d", d.contains("/items/0/text = A") && d.contains("/items/1/text = B"))
    }

    @Test
    fun `append 给根补键`() {
        val r = CardPatch.apply(card(), spec("""{"op":"append","key":"unit","value":"元"}"""))
        assertTrue("空路径 + key 应给根补键：${r.errors}", r.ok)
        assertTrue(CardPatch.describe(r.card!!).any { it.startsWith("/unit") })
    }

    @Test
    fun `remove 删数组元素`() {
        val c = Card()
        val r = CardPatch.apply(c, spec("""{"op":"remove","path":"/items/0"}"""))
        assertTrue("应成功：${r.errors}", r.ok)
        val d = CardPatch.describe(r.card!!).joinToString("\n")
        assertFalse("应只剩 1 项：$d", d.contains("/items/1/"))
        assertTrue("剩下的是原来第二项：$d", d.contains("/items/0/text = B"))
    }

    @Test
    fun `remove 不存在的键报错`() {
        val r = CardPatch.apply(card(), spec("""{"op":"remove","path":"/nope"}"""))
        assertFalse(r.ok)
    }

    @Test
    fun `merge 改根时只动指定字段`() {
        // 🔴 语义澄清（本条曾写错过一次测试）：merge 一律是**深合并**，path 为空即合并到根。
        //    早期把「空路径 merge」实现成整体替换，于是模型不写 path 时会静默清空整卡 ——
        //    卡片照样渲染成功，只是内容没了。默认必须是安全的那一种。
        val r = CardPatch.apply(card(), spec("""{"op":"merge","value":{"value":"7.7M"}}"""))
        assertTrue("应成功：${r.errors}", r.ok)
        val d = CardPatch.describe(r.card!!).joinToString("\n")
        assertTrue("value 应已改：$d", d.contains("7.7M"))
        assertTrue("label 不应被动：$d", d.contains("营收"))
        assertTrue("id 不应出现在 describe（受保护）：$d", !d.lineSequence().any { it.trimStart().startsWith("/id") })
    }

    @Test
    fun `replace 才是整体替换`() {
        val r = CardPatch.apply(card(), spec("""{"op":"replace","value":{"label":"只剩我"}}"""))
        assertTrue(r.ok)
        val d = CardPatch.describe(r.card!!).joinToString("\n")
        assertTrue("新字段应在：$d", d.contains("只剩我"))
        assertFalse("未提及的字段应被清掉：$d", d.contains("1.2M"))
    }

    @Test
    fun `replace 拦受保护键`() {
        val r = CardPatch.apply(card(), spec("""{"op":"replace","value":{"id":"newid"}}"""))
        assertFalse("replace 也不许改 id：${r.errors}", r.ok)
    }

    @Test
    fun `inc 数值增减`() {
        val c = QuroChatCard.ProgressCard("c2", "T", "加载", 10f, 100f, "%")
        val r = CardPatch.apply(c, spec("""{"op":"inc","path":"/value","value":25}"""))
        assertTrue("应成功：${r.errors}", r.ok)
        assertTrue(CardPatch.describe(r.card!!).any { it.contains("35") })
    }

    @Test
    fun `inc 目标非数值报错`() {
        val r = CardPatch.apply(card(), spec("""{"op":"inc","path":"/value","value":1}"""))
        assertFalse("stat.value 是字符串，inc 应报错而不是静默写 0", r.ok)
    }

    @Test
    fun `inc 接受字符串数字`() {
        // 补丁常常来自模型的 JSON，数值写成 "5" 很常见
        val c = QuroChatCard.ProgressCard("c2", "T", "加载", 10f, 100f, "%")
        val r = CardPatch.apply(c, spec("""{"op":"inc","path":"/value","value":"5"}"""))
        assertTrue("字符串数字应被收下：${r.errors}", r.ok)
        assertTrue(CardPatch.describe(r.card!!).any { it.contains("15") })
    }

    // ═══════════════ 原子性 ═══════════════

    @Test
    fun `一批补丁任一失败则整批放弃`() {
        val c = card()
        val before = CardPatch.describe(c)
        val r = CardPatch.apply(c, spec(
            """{"op":"set","path":"/value","value":"改了"}""",
            """{"op":"set","path":"/不存在的父/x","value":1}"""
        ))
        assertFalse(r.ok)
        assertFalse(r.changed)
        assertEquals("卡片必须原样未动", before, CardPatch.describe(c))
    }

    @Test
    fun `批次里受保护键失败也整批放弃`() {
        val c = card()
        val r = CardPatch.apply(c, spec(
            """{"op":"set","path":"/value","value":"改了"}""",
            """{"op":"set","path":"/id","value":"newid"}"""
        ))
        assertFalse(r.ok)
        assertTrue("原卡的 id 不能变：${CardPatch.describe(c)}", CardPatch.describe(c).none { it.startsWith("/id") })
    }

    // ═══════════════ 路径探索 ═══════════════

    @Test
    fun `补丁不会凭空创建中间层`() {
        val r = CardPatch.apply(card(), spec("""{"op":"set","path":"/a/b/c","value":1}"""))
        assertFalse("凭空建树会得到语义不明的怪卡，必须报错", r.ok)
        assertTrue("原因应说明不创建中间层：${r.errors}", r.errors.any { it.contains("中间层") })
    }

    @Test
    fun `数组下标越界报错`() {
        val r = CardPatch.apply(Card(), spec("""{"op":"set","path":"/items/9/done","value":true}"""))
        assertFalse(r.ok)
        assertTrue("应提到越界：${r.errors}", r.errors.any { it.contains("越界") || it.contains("不存在") })
    }

    @Test
    fun `未知操作报错并列出可用操作`() {
        val r = CardPatch.apply(card(), spec("""{"op":"upsert","path":"/value","value":1}"""))
        assertFalse(r.ok)
        assertTrue("应列出可用操作：${r.errors}", r.errors.any { it.contains("set") })
    }

    // ═══════════════ describe ═══════════════

    @Test
    fun `describe 不列受保护键也不列中间节点`() {
        val d = CardPatch.describe(card()).joinToString("\n")
        assertFalse("不该列 id：$d", d.lineSequence().any { it.trimStart().startsWith("/id") })
        assertFalse("不该列 cardType：$d", d.lineSequence().any { it.trimStart().startsWith("/cardType") })
        assertTrue("该列标量：$d", d.contains("/value"))
    }

    @Test
    fun `describe 对全部名册组件都不抛`() {
        // 自定义卡 payload 是任意 JSON，畸形结构不能把 describe 撑爆
        CardSdk.all.forEach { spec2 ->
            val c = spec2.builder?.let { runCatching { it(JSONObject(spec2.sample)) }.getOrNull() } ?: return@forEach
            runCatching { CardPatch.describe(c) }
                .onFailure { throw AssertionError("${spec2.type} 的 describe 抛异常：${it.message}", it) }
        }
    }

    // ═══════════════ 全名册往返：补丁链路本身不能弄坏任何卡 ═══════════════

    @Test
    fun `全名册组件经补丁往返后类型不变`() {
        val bad = ArrayList<String>()
        CardSdk.all.forEach { spec2 ->
            val c = spec2.builder?.let { runCatching { it(JSONObject(spec2.sample)) }.getOrNull() } ?: return@forEach
            val before = serializeCard(c).toString()
            // 空补丁：不应改变任何东西
            val r = CardPatch.apply(c, JSONObject())
            if (r.changed) bad += "${spec2.type}：空补丁却报告 changed"
            if (r.card !== c) bad += "${spec2.type}：空补丁却换了实例"
            if (serializeCard(c).toString() != before) bad += "${spec2.type}：空补丁却改了内容"
        }
        assertTrue("空补丁必须是无操作：\n" + bad.joinToString("\n"), bad.isEmpty())
    }

    @Test
    fun `改 title 这类通用字段对全部名册组件都成立`() {
        val bad = ArrayList<String>()
        CardSdk.all.forEach { spec2 ->
            val c = spec2.builder?.let { runCatching { it(JSONObject(spec2.sample)) }.getOrNull() } ?: return@forEach
            val r = CardPatch.apply(c, spec("""{"op":"set","path":"/title","value":"新标题"}"""))
            if (!r.ok) {
                bad += "${spec2.type}：${r.errors.joinToString("; ")}"
            } else if (r.card?.title != "新标题") {
                bad += "${spec2.type}：title 未生效"
            }
        }
        assertTrue("改 title 应对全部组件生效：\n" + bad.joinToString("\n"), bad.isEmpty())
    }

    // ═══════════════ 失败回喂 ═══════════════

    @Test
    fun `失败回喂带合法路径清单`() {
        val r = CardPatch.apply(card(), spec("""{"op":"set","path":"/nope/x","value":1}"""))
        val fb = r.feedback("c1")
        assertTrue("应含失败标题：$fb", fb.contains("card_patch 失败"))
        assertTrue("必须附合法路径，否则模型无从改对：$fb", fb.contains("JSON Pointer"))
        assertTrue("清单里应有真实字段：$fb", fb.contains("/value"))
    }

    @Test
    fun `成功回喂报条数`() {
        val r = CardPatch.apply(card(), spec("""{"op":"set","path":"/value","value":"1"}"""))
        assertTrue(r.feedback("c1").contains("已应用"))
    }

    // ═══════════════ 工具层 ═══════════════

    @Test
    fun `工具语法模式返回操作清单`() {
        val s = JSONObject(CardPatchTool.handle("""{"syntax":true}"""))
        assertEquals(CardPatch.OPS.size, s.getJSONArray("ops").length())
        assertTrue(s.has("pathForms"))
    }

    @Test
    fun `工具缺 cardId 时说清怎么办`() {
        val out = CardPatchTool.handle("""{"patches":[{"op":"set","path":"/value","value":1}]}""")
        assertTrue("应提示补 cardId：$out", out.contains("cardId"))
    }

    @Test
    fun `工具拦下受保护键且不提交`() {
        var called = false
        val out = CardPatchTool.handle(
            """{"cardId":"c1","patches":[{"op":"set","path":"/id","value":"x"}]}""",
            host = { _, _ -> called = true; null },
        )
        assertFalse("不该提交到宿主", called)
        assertTrue("应指出受保护键：$out", out.contains("受保护"))
    }

    @Test
    fun `工具拦下未知操作且不提交`() {
        var called = false
        val out = CardPatchTool.handle(
            """{"cardId":"c1","patches":[{"op":"upsert","path":"/value","value":1}]}""",
            host = { _, _ -> called = true; null },
        )
        assertFalse(called)
        assertTrue(out.contains("未知操作"))
    }

    @Test
    fun `无宿主时不谎报成功`() {
        // 🔴 这条是本工具最重要的行为约定：谎报成功会让模型转去回复用户
        // 「已更新」，而屏幕上什么都没变 —— 静默失效的经典形态。
        val out = CardPatchTool.handle("""{"cardId":"c1","patches":[{"op":"set","path":"/value","value":"1"}]}""")
        assertTrue("应明确说没有宿主：$out", out.contains("没有界面宿主"))
        assertFalse("绝不能说已应用：$out", out.contains("已应用"))
    }

    @Test
    fun `工具同步拿到宿主回执`() {
        val c = card()
        val host = CardPatchBridge.Host { id, patchJson ->
            CardPatch.apply(c, JSONObject(patchJson))
        }
        val out = CardPatchTool.handle(
            """{"cardId":"c1","patches":[{"op":"set","path":"/value","value":"42M"}]}""",
            host = host,
        )
        assertTrue("应回成功回执：$out", out.contains("已应用"))
    }

    @Test
    fun `工具探路模式返回路径`() {
        val c = card()
        val out = JSONObject(
            CardPatchTool.handle(
                """{"cardId":"c1","describe":true}""",
                describeOf = { if (it == "c1") CardPatch.describe(c) else emptyList() },
            )
        )
        assertEquals("c1", out.getString("cardId"))
        assertTrue(CardPatch.describe(c).all { out.getJSONArray("paths").toString().contains(it.substringBefore(" ")) })
    }

    @Test
    fun `工具探路找不到卡时说清`() {
        val out = CardPatchTool.handle("""{"cardId":"nope","describe":true}""", describeOf = { emptyList() })
        assertTrue("应说找不到：$out", out.contains("找不到"))
    }

    @Test
    fun `工具接受单条补丁摊平`() {
        val c = card()
        val host = CardPatchBridge.Host { _, pj -> CardPatch.apply(c, JSONObject(pj)) }
        val out = CardPatchTool.handle("""{"cardId":"c1","op":"set","path":"/value","value":"5M"}""", host = host)
        assertTrue("单条应被接受：$out", out.contains("已应用"))
    }

    private fun Card() = QuroChatCard.TodoCard(
        "c3", "待办",
        listOf(
            QuroChatCard.TodoCard.TodoItem("A", false),
            QuroChatCard.TodoCard.TodoItem("B", true)
        )
    )
}
