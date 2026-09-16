package com.zorv.plugin.todo

import com.ai.assistance.quro.plugin.contract.ParamType
import com.ai.assistance.quro.plugin.contract.PluginContext
import com.ai.assistance.quro.plugin.contract.PluginEntry
import com.ai.assistance.quro.plugin.contract.ToolResult
import com.ai.assistance.quro.plugin.dsl.plugin
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 示例插件：待办清单。
 *
 * 演示「插件私有存储」——用 ctx.getString / putString 按插件隔离持久化，
 * 关掉应用再打开数据仍在（宿主给它的是 `quro_plugin_<包名>` 这份 SharedPreferences）。
 *
 * 注册 5 个 AI 工具：todo_add / todo_list / todo_done / todo_remove / todo_clear
 */
class TodoEntry : PluginEntry {

    override fun onCreate(ctx: PluginContext) {
        ctx.log("TodoEntry", "待办清单插件启动 v${ctx.pluginVersion}")

        plugin(ctx) {

            aiTool(
                name = "todo_add",
                description = "往待办清单里新增一条待办事项。用户说「记一下…」「待办加上…」时调用。"
            ) {
                param("text", ParamType.STRING, "待办内容")
                execute { args ->
                    val t = args.string("text").trim()
                    if (t.isEmpty()) return@execute ToolResult.error("待办内容不能为空")
                    val arr = load(ctx)
                    val item = JSONObject().apply {
                        put("id", nextId(arr))
                        put("text", t)
                        put("done", false)
                        put("createdAt", now())
                    }
                    arr.put(item)
                    save(ctx, arr)
                    ToolResult.text("已添加：#${item.getInt("id")} $t\n当前共 ${arr.length()} 条，待完成 ${pendingCount(arr)} 条")
                }
            }

            aiTool(
                name = "todo_list",
                description = "列出待办清单。可按状态筛选：all（全部）/ pending（未完成）/ done（已完成）。"
            ) {
                param("filter", ParamType.STRING, "筛选", required = false,
                    enum = listOf("all", "pending", "done"), default = "all")
                execute { args ->
                    val arr = load(ctx)
                    if (arr.length() == 0) return@execute ToolResult.text("待办清单是空的。")
                    val filter = args.string("filter").ifBlank { "all" }
                    val rows = (0 until arr.length()).map { arr.getJSONObject(it) }.filter {
                        when (filter) {
                            "pending" -> !it.optBoolean("done")
                            "done" -> it.optBoolean("done")
                            else -> true
                        }
                    }
                    if (rows.isEmpty()) return@execute ToolResult.text("没有符合条件的待办（筛选：$filter）。")
                    ToolResult.text(
                        buildString {
                            appendLine("待办清单（筛选：$filter，共 ${rows.size} 条）")
                            rows.forEach {
                                appendLine("[${if (it.optBoolean("done")) "✓" else " "}] #${it.getInt("id")} ${it.getString("text")}")
                            }
                        }.trim()
                    )
                }
            }

            aiTool(
                name = "todo_done",
                description = "把某条待办标记为已完成。需要待办编号（先用 todo_list 查编号）。"
            ) {
                param("id", ParamType.INT, "待办编号")
                execute { args ->
                    val id = args.int("id", -1)
                    if (id <= 0) return@execute ToolResult.error("需要有效的 id（先用 todo_list 查）")
                    val arr = load(ctx)
                    val obj = (0 until arr.length()).map { arr.getJSONObject(it) }.firstOrNull { it.getInt("id") == id }
                        ?: return@execute ToolResult.error("没有编号为 $id 的待办")
                    obj.put("done", true)
                    save(ctx, arr)
                    ToolResult.text("已完成：#$id ${obj.getString("text")}\n剩余未完成 ${pendingCount(arr)} 条")
                }
            }

            aiTool(
                name = "todo_remove",
                description = "删除某条待办。需要待办编号。"
            ) {
                param("id", ParamType.INT, "待办编号")
                execute { args ->
                    val id = args.int("id", -1)
                    if (id <= 0) return@execute ToolResult.error("需要有效的 id")
                    val arr = load(ctx)
                    val kept = JSONArray()
                    var removed: String? = null
                    (0 until arr.length()).forEach { i ->
                        val o = arr.getJSONObject(i)
                        if (o.getInt("id") == id) removed = o.getString("text") else kept.put(o)
                    }
                    if (removed == null) return@execute ToolResult.error("没有编号为 $id 的待办")
                    save(ctx, kept)
                    ToolResult.text("已删除：#$id $removed\n当前共 ${kept.length()} 条")
                }
            }

            aiTool(
                name = "todo_clear",
                description = "清空整个待办清单（不可恢复）。仅当用户明确要求清空时调用。"
            ) {
                param("only_done", ParamType.BOOLEAN, "只清已完成的（true）还是全清（false）",
                    required = false, default = "false")
                execute { args ->
                    val arr = load(ctx)
                    if (args.boolean("only_done")) {
                        val kept = JSONArray()
                        (0 until arr.length()).forEach { i ->
                            val o = arr.getJSONObject(i)
                            if (!o.optBoolean("done")) kept.put(o)
                        }
                        val n = arr.length() - kept.length()
                        save(ctx, kept)
                        ToolResult.text("已清除 $n 条已完成待办，剩余 ${kept.length()} 条。")
                    } else {
                        val n = arr.length()
                        save(ctx, JSONArray())
                        ToolResult.text("已清空待办清单（原有 $n 条）。")
                    }
                }
            }
        }
    }

    override fun onDestroy(ctx: PluginContext) {
        ctx.unregisterAll()
    }

    // ==================== 私有存储 ====================

    private fun load(ctx: PluginContext): JSONArray =
        runCatching { JSONArray(ctx.getString(KEY, "[]")) }.getOrElse { JSONArray() }

    private fun save(ctx: PluginContext, arr: JSONArray) {
        ctx.putString(KEY, arr.toString())
    }

    private fun nextId(arr: JSONArray): Int =
        (0 until arr.length()).maxOfOrNull { arr.getJSONObject(it).optInt("id") }?.plus(1) ?: 1

    private fun pendingCount(arr: JSONArray): Int =
        (0 until arr.length()).count { !arr.getJSONObject(it).optBoolean("done") }

    private fun now(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())

    private companion object {
        const val KEY = "todos"
    }
}
