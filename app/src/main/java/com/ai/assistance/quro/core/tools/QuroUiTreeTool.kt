package com.ai.assistance.quro.core.tools

import android.content.Context
import com.ai.assistance.quro.core.adb.QuroAdbDebug
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/**
 * 获取当前界面 UI 控件树（UI Tree）用于精准自动化。
 * 通过 QuroAdbDebug.shell 调用 uiautomator dump 抓取当前界面层级，解析为 JSON 节点数组。
 */
class QuroUiTreeTool : QuroTool {
    override val name = "ui_tree"
    override val description = "获取当前界面 UI 控件树（UI Tree），用于精准自动化。" +
        "参数 {\"action\":\"dump|tap_text|tap_id|tap_desc|tap_bounds\",\"text\":..,\"id\":..,\"desc\":..,\"bounds\":\"x1,y1,x2,y2\",\"display\":可选虚拟显示器编号}。" +
        "dump 返回 JSON 数组（每节点含 class/text/id/desc/bounds/clickable）；tap_* 自动定位并调用无障碍点击。需已授权 ADB/ROOT 或 Shizuku 通道。"
    override val parametersJson = """{
        "type":"object",
        "properties":{
            "action":{"type":"string","description":"dump=导出控件树 | tap_text/tap_id/tap_desc/tap_bounds=定位并点击"},
            "text":{"type":"string","description":"匹配节点文本（tap_text 用）"},
            "id":{"type":"string","description":"匹配节点 resource-id（tap_id 用）"},
            "desc":{"type":"string","description":"匹配节点 content-desc（tap_desc 用）"},
            "bounds":{"type":"string","description":"x1,y1,x2,y2（tap_bounds 用）"},
            "display":{"type":"string","description":"虚拟显示器编号（可选）"}
        }
    }"""

    override fun run(context: Context, arguments: String): String {
        val jo = runCatching { JSONObject(arguments) }.getOrElse { JSONObject() }
        val action = jo.optString("action", "dump").trim().lowercase()
        val display = jo.optString("display", "")
        return runBlocking {
            when (action) {
                "tap_text" -> tap(context, jo.optString("text", ""), null, null)
                "tap_id" -> tap(context, null, jo.optString("id", ""), null)
                "tap_desc" -> tap(context, null, null, jo.optString("desc", ""))
                "tap_bounds" -> tapBounds(context, jo.optString("bounds", ""))
                "autoglm" -> autoGlm(context, jo.optString("task", ""))
                else -> dump(context, display)
            }
        }
    }

    private fun dump(context: Context, display: String): String {
        val dispArg = if (display.isNotBlank()) " --display $display" else ""
        val r1 = QuroAdbDebug.shell(context, "uiautomator dump$dispArg /sdcard/quro_ui.xml")
        if (!r1.success) return "❌ uiautomator dump 失败：${r1.render()}"
        val r2 = QuroAdbDebug.shell(context, "cat /sdcard/quro_ui.xml")
        if (!r2.success) return "❌ 读取 UI 树失败：${r2.render()}"
        return parseUiXml(r2.output)
    }

    private fun parseUiXml(xml: String): String {
        val arr = JSONArray()
        val nodeRegex = Regex("""<node\b([^>]*)/>""")
        val boundsRegex = Regex("""bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"""")
        val attr = { s: String, key: String ->
            Regex("""$key="([^"]*)"""").find(s)?.groupValues?.get(1) ?: ""
        }
        var idx = 0
        nodeRegex.findAll(xml).forEach { m ->
            val attrs = m.value
            val text = attr(attrs, "text")
            val id = attr(attrs, "resource-id")
            val desc = attr(attrs, "content-desc")
            val cls = attr(attrs, "class")
            val clickable = attr(attrs, "clickable") == "true"
            val bm = boundsRegex.find(attrs) ?: return@forEach
            val (x1, y1, x2, y2) = bm.destructured
            val node = JSONObject().apply {
                put("index", ++idx)
                put("class", cls)
                put("text", text)
                put("id", id)
                put("desc", desc)
                put("clickable", clickable)
                put("bounds", JSONArray().apply { put(x1.toInt()); put(y1.toInt()); put(x2.toInt()); put(y2.toInt()) })
            }
            arr.put(node)
        }
        if (arr.length() == 0) return "⚠️ 未解析到任何节点（可能界面为空或 dump 失败）：\n${xml.take(500)}"
        val sb = StringBuilder("[\n")
        for (i in 0 until arr.length().coerceAtMost(200)) {
            sb.append(arr.getJSONObject(i).toString()).append(if (i < arr.length().coerceAtMost(200) - 1) ",\n" else "\n")
        }
        sb.append("]")
        return sb.toString()
    }

    private fun tap(context: Context, text: String?, id: String?, desc: String?): String {
        val tree = dump(context, "")
        if (!tree.startsWith("[")) return tree
        val node = findNode(tree, text, id, desc) ?: return "❌ 未找到匹配节点（text=$text id=$id desc=$desc）"
        val (cx, cy) = node
        val res = QuroToolRegistry.active?.get("tap_screen")?.run(
            context, JSONObject().apply { put("x", cx); put("y", cy) }.toString()
        ) ?: "❌ tap_screen 不可用"
        return "✅ 已点击节点（中心 $cx,$cy）text=$text id=$id desc=$desc → $res"
    }

    private fun tapBounds(context: Context, bounds: String): String {
        val m = Regex("(\\d+),(\\d+),(\\d+),(\\d+)").find(bounds) ?: return "❌ bounds 格式应为 x1,y1,x2,y2"
        val (x1, y1, x2, y2) = m.destructured
        val cx = (x1.toInt() + x2.toInt()) / 2.0
        val cy = (y1.toInt() + y2.toInt()) / 2.0
        val res = QuroToolRegistry.active?.get("tap_screen")?.run(
            context, JSONObject().apply { put("x", cx); put("y", cy) }.toString()
        ) ?: return "❌ tap_screen 不可用"
        return "✅ 已点击坐标 ($cx,$cy) → $res"
    }

    private fun findNode(tree: String, text: String?, id: String?, desc: String?): Pair<Double, Double>? {
        val arr = runCatching { JSONArray(tree) }.getOrNull() ?: return null
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

    /**
     * 云端 AutoGLM 视觉方案：截图 → base64（供视觉/AutoGLM 模型理解界面并规划操作）。
     * 与本地 dump（uiautomator 无障碍树）形成双方案：dump 快且结构化、autoglm 适合游戏/WebView/Flutter 等控件树不可见场景。
     * 若提供 task，会尝试调用 visual_analysis 取得即时结构化理解，并给出「将 base64 发给云端模型」的下一步指引。
     */
    private fun autoGlm(context: Context, task: String): String {
        val shot = QuroToolRegistry.active?.get("screenshot_base64")?.run(context, "{}")
            ?: return "❌ screenshot_base64 不可用（截图能力未就绪）"
        val shotJson = runCatching { JSONObject(shot) }.getOrNull()
        if (shotJson == null || !shot.startsWith("{")) return "❌ 截图失败：${shot.take(300)}"
        val base64Len = shotJson.optInt("base64_length", 0)
        val preview = shotJson.optString("base64_preview", "")
        val path = shotJson.optString("path", "")
        val sb = StringBuilder("🤖 AutoGLM 云端视觉方案（截图已就绪）：\n")
        sb.append("截图路径: $path\n")
        sb.append("base64 长度: $base64Len\n")
        if (preview.isNotEmpty()) sb.append("base64 预览(前100字符): ${preview.take(100)}\n")
        if (task.isNotBlank()) {
            sb.append("\n🎯 任务: $task\n")
            val va = QuroToolRegistry.active?.get("visual_analysis")?.run(
                context, JSONObject().apply {
                    put("question", "请基于截图描述当前屏幕，并指出为完成「$task」应点击哪个元素（给出其中心坐标或文本）")
                }.toString()
            )
            if (va != null && va.startsWith("##")) sb.append("\n📝 视觉模型理解：\n${va.take(1500)}\n")
            sb.append("\n💡 下一步：将 base64 截图与任务「$task」发送给视觉/AutoGLM 模型，按其返回的元素坐标用 ui_tree tap_bounds 或 tap_screen 执行点击。\n")
        } else {
            sb.append("\n💡 下一步：将 base64 截图发送给视觉/AutoGLM 模型，按其返回的元素坐标用 ui_tree tap_bounds 或 tap_screen 执行点击。\n")
        }
        return sb.toString().trim()
    }
}
