package com.zorv.plugin.zxwidget

import org.json.JSONArray
import org.json.JSONObject

/**
 * 卡片 → 工具：让用户填的卡自己长成一个 AI 可调用的工具。
 *
 * 这是整个插件里最"自我繁殖"的一环：
 *   用户在气泡里填一张表（函数名 / 描述 / 参数）
 *   → 立刻注册进宿主 QuroToolRegistry
 *   → 下一轮起，LLM 的工具集里就有这个工具了
 *
 * 意义在于：给 AI 加能力不再需要发版。用户自己填的表就是工具定义。
 * 官方那条「宿主不需要为任何插件改一行代码」在这里又往下走了一层——
 * 连插件都不用写，填张表就行。
 *
 * 安全边界：生成的代码只能调宿主已授权的宿主工具（白名单），
 * 不能凭空执行任意命令，否则一张表就等于一个 RCE。
 */
object ZxToolGen {

    /** 生成物允许调用的内置动作白名单 */
    private val ALLOWED_ACTIONS = setOf(
        "http_get", "http_post", "file_read", "file_write",
        "tts_speak", "notify", "clipboard", "open_url",
        "schedule_at", "memory_save", "run_code"
    )

    data class GenResult(val ok: Boolean, val toolName: String = "", val reason: String = "")

    /**
     * 从一张 zx_table / zx_form 生成工具定义。
     *
     * 表格约定（用户在气泡里填）：
     *   行 0：字段名 / 类型 / 说明
     *   行 n：具体参数定义
     */
    fun fromTable(cardId: String, meta: JSONObject): GenResult {
        val node = ZxRef.get(cardId) ?: return GenResult(false, reason = "卡片不存在：$cardId")
        val name = meta.optString("name").trim()
        val desc = meta.optString("description").trim()

        // 工具名必须带前缀，跟插件约定一致，避免撞宿主内置工具
        if (!name.startsWith("zx_") && !name.startsWith("u_"))
            return GenResult(false, reason = "工具名必须以 zx_ 或 u_ 开头，实际是 $name")
        if (!name.matches(Regex("[a-z0-9_]{3,32}")))
            return GenResult(false, reason = "工具名只能含小写字母/数字/下划线，3-32 字符：$name")
        if (desc.length < 8)
            return GenResult(false, reason = "描述太短（${desc.length} 字），模型会不知道什么时候该调它")

        val rows = node.values.filterKeys { it.startsWith("rows[") }
        val params = JSONArray()
        // 按行号聚合单元格
        val byRow = mutableMapOf<Int, MutableMap<Int, String>>()
        rows.forEach { (k, v) ->
            val m = Regex("rows\\[(\\d+)]\\[(\\d+)]").find(k) ?: return@forEach
            val r = m.groupValues[1].toInt(); val c = m.groupValues[2].toInt()
            byRow.getOrPut(r) { mutableMapOf() }[c] = v
        }
        for (r in byRow.keys.sorted()) {
            val cells = byRow[r] ?: continue
            val pname = cells[0]?.trim().orEmpty()
            if (pname.isEmpty()) continue                    // 空行跳过，用户可能留了空行
            val type = normalizeType(cells[1]?.trim().orEmpty())
            val pdesc = cells[2]?.trim().orEmpty().ifBlank { pname }
            params.put(JSONObject()
                .put("name", pname)
                .put("type", type)
                .put("description", pdesc)
                .put("required", cells[3]?.trim()?.equals("是", true) == true))
        }
        if (params.length() == 0)
            return GenResult(false, reason = "没解析到任何参数，检查表格第一列是不是字段名")

        val action = meta.optString("action")
        if (action !in ALLOWED_ACTIONS)
            return GenResult(false, reason = "action 不在白名单：$action，可选 ${ALLOWED_ACTIONS.joinToString()}")
        val template = meta.optString("template")
        if (template.isBlank())
            return GenResult(false, reason = "缺少 template（执行模板，用 {{参数名}} 占位）")

        val spec = JSONObject()
            .put("name", name)
            .put("description", "$desc（由气泡卡片生成）")
            .put("parameters", params)
            .put("action", action)
            .put("template", template)
            .put("_generated", true)
            .put("_from_card", cardId)

        return GenResult(true, toolName = name, reason = spec.toString(2))
    }

    private fun normalizeType(s: String): String = when (s.lowercase()) {
        "数字", "int", "number", "整数" -> "number"
        "布尔", "bool", "boolean", "是否" -> "boolean"
        "枚举", "enum", "选项" -> "enum"
        else -> "string"
    }

    /** 生成一张"工具定义表"卡片，让用户直接填 */
    fun tableTemplate(): String = JSONObject()
        .put("type", "zx_table")
        .put("title", "定义一个新工具")
        .put("columns", JSONArray().apply {
            put("参数名"); put("类型"); put("说明"); put("必填")
        })
        .put("rows", JSONArray().apply {
            put(JSONArray().apply { put("city"); put("字符串"); put("城市名，如汕头"); put("是") })
            put(JSONArray().apply { put("days"); put("数字"); put("天数"); put("否") })
        })
        .toString()

    /** 生成预览用终端卡，让用户看到将要注册的东西 */
    fun previewAsTerminal(spec: String): String {
        val lines = spec.lineSequence().map {
            """{"lv":"out","c":${JSONObject.quote(it)}}"""
        }.joinToString(",")
        return """{"type":"zx_terminal","cmd":"zx_gen_tool --dry-run","exit":0,"lines":[$lines]}"""
    }
}
