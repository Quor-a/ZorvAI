package com.ai.assistance.quro.core.cards

import com.ai.assistance.quro.core.cards.CardSdk.arrLen
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「模型漏写 `type` 就静默丢卡」的回归防线。
 *
 * ## 真机故障原型
 *
 * 截图里是一屏这样的东西：
 * ```
 * {"label":"继续测试","command":"ai:继续下一类组件"}
 * {"label":"查看报告","command":"ai:查看汇总报告"}
 * {"label":"重新测试","command":"ai:重新测试"}
 * ```
 * 三行原始 JSON 躺在卡片框里 —— 模型明明想画三个可点的按钮。
 * 实测 `CardFence.toCards("cards", …)` 返回 **0 张卡**：整组凭空消失，
 * 界面上「什么都没发生」，日志里一行都没有。
 *
 * 根因是 [CardSdk.parseObj] 的 `if (type.isEmpty()) return null`。
 *
 * ## 这组测试要同时锁住两件事
 *
 * 上半部分：**该救的必须救回来**（漏 type 不再丢卡）。
 * 下半部分：**不该救的绝不能救**—— 推断层最大的风险是把用户的 schema、
 * 配置文件、代码块里的普通 JSON 变成一张莫名其妙的卡片，而且用户无从察觉
 * （他看到的是自己的代码被改样了）。所以「宁可不猜」必须有断言，
 * 否则下一个改这里的人会顺手放宽判据。
 */
class CardShapeFallbackTest {

    // ───────────────────────── 该救的 ─────────────────────────

    @Test
    fun `真机截图那一组裸数组项救回成按钮组`() {
        val body = """
            [{"label":"继续测试","command":"ai:继续下一类组件"},
             {"label":"查看报告","command":"ai:查看汇总报告"},
             {"label":"重新测试","command":"ai:重新测试"}]
        """.trimIndent()
        val cards = CardFence.toCards("cards", body)
        assertEquals("截图这一组必须产出卡片，原实现是 0 张", 1, cards.size)
        val act = cards[0]
        assertTrue("应合成为一张动作组（三个按钮），实际 ${act::class.simpleName}", act is QuroChatCard.ActionCard)
        val items = (act as QuroChatCard.ActionCard).actions
        assertEquals("三个按钮都要在", 3, items.size)
        assertEquals("继续测试", items[0].label)
        assertEquals("ai:继续下一类组件", items[0].command)
    }

    @Test
    fun `单个漏 type 的 action 对象也能救`() {
        val c = CardSdk.parseObj(JSONObject("""{"label":"继续测试","command":"ai:继续下一类组件"}"""))
        assertNotNull("单对象也必须救回来", c)
        assertTrue("应是动作组，实际 ${c!!::class.simpleName}", c is QuroChatCard.ActionCard)
    }

    @Test
    fun `card 围栏里的裸数组项也能救`() {
        val body = """[{"label":"A","command":"ai:1"},{"label":"B","command":"ai:2"}]"""
        assertEquals(1, CardFence.toCards("card", body).size)
    }

    @Test
    fun `裸数组项的组级标题能落到卡上`() {
        val body = """[{"label":"A","command":"ai:1"}]"""
        val cards = CardFence.toCards("cards", body, hintTitle = "下一步")
        assertEquals(1, cards.size)
        assertEquals("组级 title= 应成为推断出的卡的标题", "下一步", cards[0].title)
    }

    @Test
    fun `漏 type 的分段选择与标签组`() {
        val seg = CardSdk.parseObj(JSONObject("""{"label":"主题","options":["浅","深"],"selectedIndex":1}"""))
        assertTrue("options+selectedIndex 应识别为分段器，实际 ${seg!!::class.simpleName}", seg is QuroChatCard.SegmentedCard)
        val chips = CardSdk.parseObj(JSONObject("""{"label":"标签","chips":["红","绿"],"multi":true}"""))
        assertTrue("chips 应识别为标签组，实际 ${chips!!::class.simpleName}", chips is QuroChatCard.ChipsCard)
    }

    @Test
    fun `漏 type 的可选列表`() {
        val c = CardSdk.parseObj(JSONObject("""{"label":"选一个","items":[{"text":"A"},{"text":"B"}],"selectable":true}"""))
        assertTrue("items+selectable 应识别为列表，实际 ${c!!::class.simpleName}", c is QuroChatCard.ListCard)
        assertEquals("两个选项都要在", 2, (c as QuroChatCard.ListCard).items.size)
    }

    @Test
    fun `漏 type 的统计卡`() {
        val c = CardSdk.parseObj(JSONObject("""{"label":"营收","value":"1.2M","trend":"up"}"""))
        assertTrue("label+字符串value 应识别为统计卡，实际 ${c!!::class.simpleName}", c is QuroChatCard.StatCard)
        assertEquals("1.2M", (c as QuroChatCard.StatCard).value)
    }

    /**
     * 第二张真机截图那一组：`{"level":"info","text":"…"}` 三行。
     * 与按钮组同族（裸数组项 + 漏 type），但容器是**字符串**数组 ——
     * 这里回归的是「不转就会渲染出一串 `{`」。
     */
    @Test
    fun `日志行裸数组项救回成流式卡且不是一串花括号`() {
        val body = """
            [{"level":"info","text":"开始测试 media 类组件"},
             {"level":"success","text":"stream 组件下发成功"},
             {"level":"warning","text":"即将测试二维码"}]
        """.trimIndent()
        val cards = CardFence.toCards("cards", body)
        assertEquals("应合成一张流式卡，实际 ${cards.size} 张", 1, cards.size)
        val st = cards[0]
        assertTrue("应是 StreamCard，实际 ${st::class.simpleName}", st is QuroChatCard.StreamCard)
        val lines = (st as QuroChatCard.StreamCard).lines
        assertEquals("三行都要在", 3, lines.size)
        assertTrue("行内容不该是花括号：$lines", lines.none { it.contains("{") })
        // level 是三行唯一的区分线索，丢了用户就分不出哪行是警告
        assertTrue("level 要保留成行内标记：$lines", lines.any { it.contains("warning") && it.contains("二维码") })
    }

    @Test
    fun `时间线事件裸数组项救回`() {
        val body = """[{"time":"09:00","title":"起床","status":"done"},{"time":"12:00","title":"午饭","status":"active"}]"""
        val cards = CardFence.toCards("cards", body)
        assertEquals(1, cards.size)
        assertTrue("应是 TimelineCard，实际 ${cards[0]::class.simpleName}", cards[0] is QuroChatCard.TimelineCard)
        assertEquals("两个事件", 2, (cards[0] as QuroChatCard.TimelineCard).events.size)
    }

    /** 混合数组：只有**每一项**都命中特异形状才整组推断，避免误伤正常混合内容。 */
    @Test
    fun `混合数组不被整组推断`() {
        val body = """[{"label":"A","command":"ai:1"},{"name":"普通对象","version":2}]"""
        val cards = CardFence.toCards("cards", body)
        // 第二项不该被猜成卡；第一项仍应救回来（逐项safeParse 现在会走 parseObj 的形状兜底）
        assertTrue("至少应救回第一个按钮，实际 ${cards.size}", cards.isNotEmpty())
    }

    // ───────────────────── 该救的：数组键名容错 ─────────────────────

    @Test
    fun `数组键名写错时不再产出空壳卡`() {
        // 原行为：arrLen("actions")=0 → 成功构造一张 actions 为空的 ActionCard，
        // 用户看到空按钮组且无任何报错 —— 比丢卡更难查。
        val c = CardSdk.parseObj(JSONObject("""{"type":"actions","action":[{"label":"复制","command":"copy:x"}]}"""))
        assertTrue("应仍构造出 ActionCard", c is QuroChatCard.ActionCard)
        assertEquals("少写的 s 要被容错救回，按钮不该是空的", 1, (c as QuroChatCard.ActionCard).actions.size)
        assertEquals("复制", c.actions[0].label)
    }

    @Test
    fun `数组键名容错覆盖单复数与近义词`() {
        val cases = listOf(
            """{"type":"quickaction","action":[{"label":"搜索","icon":"search","command":"ai:搜"}]}""" to QuroChatCard.QuickActionCard::class,
            """{"type":"badge","badge":[{"label":"新人","color":"#4CAF50"}]}""" to QuroChatCard.BadgeCard::class,
            """{"type":"breadcrumb","crumb":[{"label":"首页","command":"screen:home"}]}""" to QuroChatCard.BreadcrumbCard::class,
            """{"type":"tagcloud","tag":[{"label":"AI","weight":5}]}""" to QuroChatCard.TagCloudCard::class,
            """{"type":"avatargroup","member":[{"name":"小明"}]}""" to QuroChatCard.AvatarGroupCard::class,
        )
        for ((json, k) in cases) {
            val c = CardSdk.parseObj(JSONObject(json))
            assertNotNull("应能解析：$json", c)
            assertTrue("类型应为 ${k.simpleName}，实际 ${c!!::class.simpleName}", k.isInstance(c))
            // 走 serializeCard 而非 CardCodec.encode：后者是「写出型」(card, o) 无返回值。
            val ser = serializeCard(c).toString()
            assertTrue(
                "容错后不该还是空数组（$json → $ser）",
                ser.contains("\"command\"") || ser.contains("\"label\"") || ser.contains("\"name\"") || ser.contains("\"color\"") || ser.contains("\"weight\"")
            )
        }
    }

    @Test
    fun `单对象当数组用也能读出来`() {
        val c = CardSdk.parseObj(JSONObject("""{"type":"actions","actions":{"label":"单个","command":"ai:1"}}"""))
        assertTrue("单对象应被当成单项数组", c is QuroChatCard.ActionCard)
        assertEquals(1, (c as QuroChatCard.ActionCard).actions.size)
        assertEquals("单个", c.actions[0].label)
    }

    @Test
    fun `键名容错不会波及无别名表的键`() {
        // 未登记别名的键仍应严格取原键，查不到就是查不到（不做「乱猜一个键」）
        assertEquals(0, JSONObject("""{"type":"chart"}""").arrLen("series"))
        assertEquals(0, JSONObject("""{"a":1}""").arrLen("a"))
    }

    // ───────────────────────── 不该救的 ─────────────────────────

    @Test
    fun `有 type 的一律不猜`() {
        // type 写错（大小写/别名）是名册该处理的事；插手会把「已知 type 的拼写错误」
        // 降级成「无 type 的形状猜测」，反而丢信息。
        assertNull(CardShapeFallback.guess(JSONObject("""{"type":"whatever","command":"ai:1"}""")))
        assertNull(CardShapeFallback.guess(JSONObject("""{"type":"actions","command":"ai:1"}""")))
    }

    @Test
    fun `没有协议前缀的 command 不猜`() {
        // 光有 label+command 不足以定性 —— 大量后端接口报文就长这样。
        assertNull(
            "无协议前缀的 command 不该被当成动作",
            CardShapeFallback.guess(JSONObject("""{"label":"x","command":"getUserInfo"}"""))
        )
        assertNull(
            "纯业务报文不该被猜成卡",
            CardShapeFallback.guess(JSONObject("""{"id":1,"command":"create","params":{}}"""))
        )
    }

    @Test
    fun `普通 schema 与配置片段不被吞掉`() {
        // 这是最关键的一条：用户贴的 JSON、代码块里的 schema 必须原样留在正文里。
        val schemas = listOf(
            """{"name":"widget","properties":{"label":{"type":"string"}}}""",
            """{"dependencies":{"react":"^18.0.0","vue":"^3.4.0"}}""",
            """{"error":{"code":4001,"message":"invalid","retryable":false}}""",
            """{"labels":["A","B","C"]}""",
            """{"value":123,"unit":"ms"}""",
            """{"id":42,"name":"张三","email":"a@b.c"}""",
        )
        for (s in schemas) {
            assertNull("不该被猜成卡：$s", CardShapeFallback.guess(JSONObject(s)))
        }
    }

    @Test
    fun `数字 value 不会被猜成统计卡`() {
        // 本SDK 的 stat.value 约定是字符串；数字 value 在业务 JSON 里太常见（耗时/计数/金额）。
        assertNull(CardShapeFallback.guess(JSONObject("""{"label":"耗时","value":123}""")))
    }

    @Test
    fun `业务报文里的 level+text 不被猜成日志`() {
        // level 取值不在日志语义域内 → 一律不猜。
        // 「用户等级 3 + 备注」这种业务结构非常常见，吞成日志卡就是篡改用户内容。
        assertNull(CardShapeFallback.guess(JSONObject("""{"level":"gold","text":"高级用户"}""")))
        assertNull(CardShapeFallback.guess(JSONObject("""{"level":3,"text":"普通"}""")))
        // 带明确结构键的即便 level 合法也不走日志（说明它是别的卡）
        assertNull(CardShapeFallback.guess(JSONObject("""{"level":"info","text":"x","items":[{"text":"y"}]}""")))
    }

    @Test
    fun `带结构键的对象不走统计卡猜测`() {
        // 有 items/chips 这类结构键说明它是别的卡，value 恰好也是字符串不算数
        assertNull(CardShapeFallback.guess(JSONObject("""{"label":"A","value":"B","items":[{"text":"C"}]}""")))
    }

    @Test
    fun `推断不会影响正常有type 的卡片`() {
        val specs = listOf(
            """{"type":"actions","actions":[{"label":"复制","command":"copy:x"}]}""",
            """{"type":"stat","label":"用户","value":"1.2k","trend":"up"}""",
            """{"type":"list","items":[{"text":"A"}],"selectable":true}""",
        )
        for (s in specs) {
            assertNotNull("有 type 的必须照常解析：$s", CardSdk.parseObj(JSONObject(s)))
        }
    }

    /** 空补丁/空卡往返不能被这次改动破坏（[CardPatch] 依赖 serializeCard 往返）。 */
    @Test
    fun `容错不破坏往返链路`() {
        val bad = CardSdk.lint()
        assertTrue("名册 lint 变红：\n" + bad.joinToString("\n"), bad.isEmpty())
    }
}
