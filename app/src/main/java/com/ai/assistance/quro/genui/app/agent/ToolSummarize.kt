package com.ai.assistance.quro.genui.app.agent

import org.json.JSONObject

/**
 * 工具结果的人类可读化 —— AgentLoop 时间线与 ChatSession 对话气泡共用。
 *
 * 此前 ChatSession 直接把 res.toString() 截 400 字怼进气泡：一坨 JSON 用户根本读不了，
 * 模型侧拿全量 JSON 没问题，但给人看的必须是摘要。
 */
object ToolSummarize {

    /** 把工具返回压缩成一行人类可读摘要 */
    fun summarize(name: String, r: JSONObject): String = runCatching {
        when {
            // 权限类失败附带的「去哪个屏开哪个开关」指引必须完整保留——那是用户唯一能自救的线索
            r.has("error") -> "失败：${r.optString("error").take(200)}"
            r.has("denied") -> "已拒绝：${r.optString("denied").take(60)}"
            name == "web_search" -> buildString {
                // 管线成功返回的是 citations（无 results 字段）——只读 results 会把成功误报成"0 条结果"
                val n = maxOf(
                    r.optJSONArray("results")?.length() ?: 0,
                    r.optJSONArray("citations")?.length() ?: 0
                )
                append(n).append(" 条结果")
                val eng = r.optString("engines")
                if (eng.isNotBlank()) append(" · ").append(eng)
                if (r.optString("mode") == "full_pipeline") append(" · 管线")
                val pf = r.optJSONArray("partial_failures")
                if (pf != null && pf.length() > 0) append("（部分引擎失败）")
            }
            name == "web_fetch" -> buildString {
                append("读到 ").append(r.optInt("chars")).append(" 字")
                if (r.optBoolean("truncated")) append("（已截断）")
                val ct = r.optString("content_type")
                if (ct.isNotBlank()) append(" · ").append(ct)
            }
            name == "news_search" -> {
                val n = r.optJSONArray("citations")?.length() ?: 0
                val n2 = r.optJSONArray("results")?.length() ?: 0
                val n3 = r.optJSONArray("items")?.length() ?: 0
                "${maxOf(n, n2, n3)} 条新闻" + (if (r.optString("engines").isNotBlank())
                    " · ${r.optString("engines")}" else "")
            }
            name == "file_list" -> "共 ${r.optInt("count")} 个文件"
            name == "contacts_search" -> "找到 ${r.optInt("count")} 位联系人"
            r.has("items") -> "${r.optJSONArray("items")?.length() ?: 0} 条结果"
            r.has("results") -> "${r.optJSONArray("results")?.length() ?: 0} 条结果"
            r.has("count") -> "共 ${r.optInt("count")} 条"
            r.has("content") -> "读到 ${r.optString("content").length} 字"
            name == "file_save" -> "已保存 ${r.optString("path").substringAfterLast('/').ifBlank { "文件" }}" +
                "（${r.optInt("bytes")} 字节）"
            name.startsWith("time") -> r.optString("text").take(48).ifBlank { "已获取时间" }
            name.startsWith("notify") -> "通知已发出：${r.optString("title", r.optString("body")).take(30)}"
            r.has("text") -> r.optString("text").take(48)
            r.has("value") -> "已取回数据"
            r.has("ok") -> "完成"
            else -> r.toString().take(56)
        }
    }.getOrDefault("完成")

    /** 毫秒人性化：823ms / 1.2s / 75s */
    fun fmtMs(ms: Long): String = when {
        ms < 1000 -> "${ms}ms"
        ms < 60_000 -> String.format(java.util.Locale.US, "%.1fs", ms / 1000.0)
        else -> "${ms / 60_000}m${(ms % 60_000) / 1000}s"
    }
}
