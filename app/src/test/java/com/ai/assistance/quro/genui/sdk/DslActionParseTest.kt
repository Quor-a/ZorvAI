package com.ai.assistance.quro.genui.sdk

import com.ai.assistance.quro.genui.sdk.dsl.DslParser
import com.ai.assistance.quro.genui.sdk.dsl.GenUIAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 动作解析回归测试。
 *
 * 为什么单独钉这一层：`DslParser` 用的是**显式**多态注册表（`polymorphic(GenUIAction) { subclass(...) }`），
 * 而动作藏在 `events['onClick'].actions[0]` 这种深层路径上。漏注册一个子类的后果不是
 * 「少一个动作」，而是**整份 UI 解析失败**（`unknown subclass`）→ 界面直接变成
 * 「⚠️ UI 解析失败」卡片。真机上用户只会说「AI 画不出界面 / 按钮点不了」，
 * 完全看不出是这里的问题。
 *
 * 本轮就踩到过：`call_tool`、`open_app`、`play_media`、`send_message` 等一律没注册。
 */
class DslActionParseTest {

    /** 造一份「一个按钮挂一个动作」的最小 UISpec。 */
    private fun specWithAction(actionJson: String): String = """
        {
          "id": "t1",
          "title": "动作测试",
          "root": {
            "type": "button",
            "properties": { "text": "点我" },
            "events": { "onClick": { "actions": [ $actionJson ] } }
          }
        }
    """.trimIndent()

    private fun firstAction(json: String): GenUIAction {
        val spec = DslParser.parse(json)
        val event = spec.root.events["onClick"]
        assertNotNull("事件没解析出来", event)
        assertTrue("动作列表为空", !event!!.actions.isNullOrEmpty())
        return event.actions.first()
    }

    @Test
    fun `call_tool 能被解析且参数完整`() {
        val action = firstAction(
            specWithAction("""{"action":"call_tool","name":"web_search","arguments":{"q":"今天汇率"}}"""),
        )
        assertTrue("应解析成 CallTool，实际 ${action::class.simpleName}", action is GenUIAction.CallTool)
        action as GenUIAction.CallTool
        assertEquals("web_search", action.name)
        assertTrue("参数里的 q 丢了：${action.argumentsJson}", action.argumentsJson.contains("今天汇率"))
        // 宿主工具层吃的就是这段 JSON 文本
        assertTrue(action.argumentsJson.trimStart().startsWith("{"))
    }

    @Test
    fun `call_tool 省略 arguments 时给出空对象而不是解析失败`() {
        val action = firstAction(specWithAction("""{"action":"call_tool","name":"get_battery"}"""))
        assertTrue(action is GenUIAction.CallTool)
        assertEquals("{}", (action as GenUIAction.CallTool).argumentsJson)
    }

    @Test
    fun `v1_9 的真实设备动作全部能解析`() {
        // 这一批此前全都没进注册表
        val actions = listOf(
            """{"action":"open_app","packageName":"com.tencent.mm"}""",
            """{"action":"play_media","url":"https://a/b.mp3","mediaType":"audio"}""",
            """{"action":"stop_media"}""",
            """{"action":"open_html","html":"<b>hi</b>","title":"详情"}""",
            """{"action":"execute","task":"dial","params":{"tel":"10086"}}""",
            """{"action":"send_message","text":"帮我算一下"}""",
            """{"action":"go_back"}""",
            """{"action":"toast","message":"ok"}""",
        )
        actions.forEach { raw ->
            val action = runCatching { firstAction(specWithAction(raw)) }.getOrElse { e ->
                throw AssertionError("动作 $raw 解析失败：${e.message}", e)
            }
            assertNotNull(action)
        }
    }

    @Test
    fun `未知动作走 safeParse 兜底而不是抛异常崩界面`() {
        val bad = specWithAction("""{"action":"definitely_not_a_real_action","x":1}""")
        // parse 应当失败（这正是要暴露给开发者的），但 safeParse 必须给出可显示的卡片
        assertTrue(runCatching { DslParser.parse(bad) }.isFailure)
        val spec = DslParser.safeParse(bad)
        assertEquals("error", spec.schemaVersion)
        assertTrue(spec.root.properties.toString().contains("解析失败"))
    }
}
