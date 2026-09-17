package com.ai.assistance.quro.core.tools

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 脚本化 UI 自动操作：按顺序执行一组界面动作。
 * 复用 ui_tree（dump + 节点定位）与无障碍工具 tap_screen / input_text / swipe_screen / scroll_screen。
 */
class QuroAutomaticUiTool : QuroTool {
    override val name = "automatic_ui"
    override val description = "脚本化 UI 自动操作：按顺序执行一组界面动作（dump 当前界面 / 按文本·id·描述·坐标点击 / 输入文本 / 滑动 / 滚动 / 等待）。" +
        "可结合 ui_tree 的节点信息精准点击。参数 {\"actions\":[ {\"action\":\"dump|tap_text|tap_id|tap_desc|tap_bounds|input_text|swipe|scroll|wait\",\"text\":..,\"id\":..,\"desc\":..,\"bounds\":\"x1,y1,x2,y2\",\"value\":..,\"direction\":..,\"ms\":..} ]}。"
    override val parametersJson = """{
        "type":"object",
        "properties":{
            "actions":{"type":"array","description":"动作序列，依次执行"}
        },
        "required":["actions"]
    }"""

    override fun run(context: Context, arguments: String): String {
        val jo = runCatching { JSONObject(arguments) }.getOrElse { JSONObject() }
        val actions = jo.optJSONArray("actions") ?: return "❌ 缺少 actions 数组"
        val reg = QuroToolRegistry.active ?: return "❌ 工具注册表不可用"
        val sb = StringBuilder()
        for (i in 0 until actions.length()) {
            val a = actions.optJSONObject(i) ?: continue
            val act = a.optString("action", "").trim().lowercase()
            val out = try {
                when (act) {
                    "dump" -> reg.get("ui_tree")?.run(context, "{\"action\":\"dump\"}") ?: "❌ ui_tree 不可用"
                    "tap_text" -> tapBy(reg, context, a.optString("text", ""), null, null)
                    "tap_id" -> tapBy(reg, context, null, a.optString("id", ""), null)
                    "tap_desc" -> tapBy(reg, context, null, null, a.optString("desc", ""))
                    "tap_bounds" -> tapBounds(reg, context, a.optString("bounds", ""))
                    "input_text" -> reg.get("input_text")?.run(
                        context, JSONObject().apply { put("text", a.optString("value", a.optString("text", ""))) }.toString()
                    ) ?: "❌ input_text 不可用"
                    "swipe" -> {
                        val dir = a.optString("direction", "up")
                        val args = JSONObject().apply {
                            if (a.has("start_x")) {
                                put("start_x", a.optDouble("start_x"))
                                put("start_y", a.optDouble("start_y"))
                                put("end_x", a.optDouble("end_x"))
                                put("end_y", a.optDouble("end_y"))
                            } else put("direction", dir)
                        }
                        reg.get("swipe_screen")?.run(context, args.toString()) ?: "❌ swipe_screen 不可用"
                    }
                    "scroll" -> reg.get("scroll_screen")?.run(
                        context, JSONObject().apply { put("direction", a.optString("direction", "forward")) }.toString()
                    ) ?: "❌ scroll_screen 不可用"
                    "wait" -> {
                        Thread.sleep(a.optLong("ms", 1000).coerceIn(0, 10000))
                        "⏱ 等待 ${a.optLong("ms", 1000)}ms"
                    }
                    else -> "❌ 未知动作：$act"
                }
            } catch (e: Exception) {
                "❌ 执行异常：${e.message}"
            }
            sb.append("${i + 1}. [$act] $out\n")
        }
        return sb.toString().trim()
    }

    private fun tapBy(reg: QuroToolRegistry, ctx: Context, text: String?, id: String?, desc: String?): String {
        val dump = reg.get("ui_tree")?.run(ctx, "{\"action\":\"dump\"}") ?: return "❌ ui_tree 不可用"
        val node = findNode(dump, text, id, desc) ?: return "❌ 未在当前界面找到匹配节点（text=$text id=$id desc=$desc）"
        val (cx, cy) = node
        val res = reg.get("tap_screen")?.run(ctx, JSONObject().apply { put("x", cx); put("y", cy) }.toString()) ?: "❌ tap_screen 不可用"
        return "✅ 已点击节点（中心 $cx,$cy）→ $res"
    }

    private fun tapBounds(reg: QuroToolRegistry, ctx: Context, bounds: String): String {
        val m = Regex("(\\d+),(\\d+),(\\d+),(\\d+)").find(bounds) ?: return "❌ bounds 格式应为 x1,y1,x2,y2"
        val (x1, y1, x2, y2) = m.destructured
        val cx = (x1.toInt() + x2.toInt()) / 2.0
        val cy = (y1.toInt() + y2.toInt()) / 2.0
        val res = reg.get("tap_screen")?.run(ctx, JSONObject().apply { put("x", cx); put("y", cy) }.toString()) ?: "❌ tap_screen 不可用"
        return "✅ 已点击坐标 ($cx,$cy) → $res"
    }

    /** 从 ui_tree 的 JSON 数组 dump 中匹配节点，返回中心点坐标。 */
    private fun findNode(dump: String, text: String?, id: String?, desc: String?): Pair<Double, Double>? {
        if (!dump.startsWith("[")) return null
        val arr = runCatching { JSONArray(dump) }.getOrNull() ?: return null
        for (i in 0 until arr.length()) {
            val n = arr.optJSONObject(i) ?: continue
            val t = n.optString("text", "")
            val d = n.optString("desc", "")
            val idd = n.optString("id", "")
            val okText = text == null || t.contains(text, ignoreCase = true)
            val okId = id == null || idd.contains(id, ignoreCase = true)
            val okDesc = desc == null || d.contains(desc, ignoreCase = true)
            if (okText && okId && okDesc) {
                val b = n.optJSONArray("bounds") ?: continue
                if (b.length() == 4) {
                    return ((b.getInt(0) + b.getInt(2)) / 2.0) to ((b.getInt(1) + b.getInt(3)) / 2.0)
                }
            }
        }
        return null
    }
}
