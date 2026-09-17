package com.ai.assistance.quro.core.tools

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 终端自定义按键绑定管理：保存/查询/删除/重置按键→动作映射。
 * 持久化于应用私有目录 terminal_keybindings.json，供终端加载实现「自定义按键」。
 */
class QuroKeybindingTool : QuroTool {
    override val name = "terminal_keys"
    override val description = "终端自定义按键绑定管理：保存/查询/删除/重置按键→动作映射，持久化于应用私有目录，供终端加载。" +
        "参数 {\"action\":\"save|list|get|delete|reset\",\"name\":\"绑定名（如 ctrl_k）\",\"key\":\"按键组合（如 Ctrl+K）\",\"command\":\"触发的命令/动作\",\"description\":\"说明\"}。"
    override val parametersJson = """{
        "type":"object",
        "properties":{
            "action":{"type":"string","description":"save | list | get | delete | reset"},
            "name":{"type":"string","description":"绑定名（save/get/delete 必填）"},
            "key":{"type":"string","description":"按键组合，如 Ctrl+K / Alt+T"},
            "command":{"type":"string","description":"触发执行的命令或动作"},
            "description":{"type":"string","description":"绑定说明"}
        },
        "required":["action"]
    }"""

    override fun run(context: Context, arguments: String): String {
        val jo = runCatching { JSONObject(arguments) }.getOrElse { JSONObject() }
        val action = jo.optString("action", "").trim().lowercase()
        val file = File(context.filesDir, "terminal_keybindings.json")
        val list = load(file)
        return when (action) {
            "save" -> {
                val name = jo.optString("name", "").trim()
                val key = jo.optString("key", "").trim()
                val command = jo.optString("command", "").trim()
                if (name.isEmpty()) return "❌ save 需要 name"
                val existing = (0 until list.length()).firstOrNull { list.optJSONObject(it)?.optString("name") == name }
                val entry = JSONObject().apply {
                    put("name", name); put("key", key); put("command", command)
                    put("description", jo.optString("description", "").trim())
                }
                if (existing != null) list.put(existing, entry) else list.put(entry)
                save(file, list)
                "✅ 已保存按键绑定 '$name'（key=$key → command=$command）"
            }
            "list" -> {
                if (list.length() == 0) return "（暂无按键绑定，用 action=save 添加）"
                val sb = StringBuilder("⌨️ 终端按键绑定（${list.length()}）：\n")
                for (i in 0 until list.length()) {
                    val e = list.optJSONObject(i) ?: continue
                    sb.append("- ${e.optString("name")}: ${e.optString("key")} → ${e.optString("command")}")
                    if (e.optString("description").isNotBlank()) sb.append("  (${e.optString("description")})")
                    sb.append('\n')
                }
                sb.toString().trim()
            }
            "get" -> {
                val name = jo.optString("name", "").trim()
                val e = (0 until list.length()).mapNotNull { list.optJSONObject(it) }.firstOrNull { it.optString("name") == name }
                    ?: return "❌ 未找到绑定 '$name'"
                e.toString(2)
            }
            "delete" -> {
                val name = jo.optString("name", "").trim()
                val idx = (0 until list.length()).firstOrNull { list.optJSONObject(it)?.optString("name") == name }
                    ?: return "❌ 未找到绑定 '$name'"
                list.remove(idx)
                save(file, list)
                "✅ 已删除绑定 '$name'"
            }
            "reset" -> {
                save(file, JSONArray())
                "✅ 已清空所有按键绑定"
            }
            else -> "❌ 未知 action：$action"
        }
    }

    private fun load(file: File): JSONArray =
        if (!file.exists()) JSONArray() else runCatching { JSONArray(file.readText()) }.getOrElse { JSONArray() }

    private fun save(file: File, arr: JSONArray) {
        runCatching { file.writeText(arr.toString(2)) }
    }
}
