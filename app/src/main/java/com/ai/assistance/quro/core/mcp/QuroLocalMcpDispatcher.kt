package com.ai.assistance.quro.core.mcp

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 本地 MCP 工具派发器。
 *
 * AI 通过 `mcp_deploy` 提交的工具定义（`toolDefs`）里，每个工具带一个 `handler_type`
 * 与可选的 `handler_config`。本派发器按类型执行**已知动作**——全部基于应用内已有能力
 * （OkHttp / 文件 / 时间），**不依赖 python / node / proot**，在任意安卓设备上 100% 可用。
 *
 * 支持的 handler_type：
 * - `echo`      ：原样返回调用参数（调试 / 占位）
 * - `http_get`  ：GET handler_config.url（支持 `${param}` 模板替换），返回响应体
 * - `time`      ：返回当前毫秒时间戳 + ISO 时间
 * - `file_read` ：读取 handler_config.path 或参数 path 指向的文本文件
 */
object QuroLocalMcpDispatcher {
    private const val TAG = "QuroLocalMcp"
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /** 执行某个本地 MCP 工具，返回文本内容（将由 server 层包成 MCP text content）。 */
    fun dispatch(toolDef: JSONObject, arguments: JSONObject): String {
        val handlerType = toolDef.optString("handler_type", "echo")
        val cfg = toolDef.optJSONObject("handler_config") ?: JSONObject()
        return try {
            when (handlerType) {
                "http_get" -> httpGet(cfg, arguments)
                "time" -> {
                    val now = System.currentTimeMillis()
                    "{\"time_ms\":$now,\"iso\":\"${java.time.Instant.ofEpochMilli(now)}\"}"
                }
                "file_read" -> fileRead(cfg, arguments)
                else -> arguments.toString()
            }
        } catch (e: Exception) {
            Log.w(TAG, "本地 MCP 工具执行失败($handlerType): ${e.message}")
            "本地 MCP 工具执行失败: ${e.message}"
        }
    }

    /** 把 `${name}` 占位符按 arguments 替换。 */
    private fun renderTemplate(tpl: String, args: JSONObject): String =
        tpl.replace(Regex("\\$\\{([^}]+)\\}")) { m ->
            val key = m.groupValues[1].trim()
            args.optString(key, "")
        }

    private fun httpGet(cfg: JSONObject, args: JSONObject): String {
        val raw = cfg.optString("url", "")
        if (raw.isEmpty()) return "handler_config.url 为空"
        val url = renderTemplate(raw, args)
        val req = Request.Builder().url(url).get().build()
        return try {
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: ""
                if (!resp.isSuccessful) return "HTTP ${resp.code}: ${body.take(300)}"
                if (body.length > 8000) body.take(8000) + "\n...[结果已截断]" else body
            }
        } catch (e: Exception) {
            "请求失败: ${e.message}"
        }
    }

    private fun fileRead(cfg: JSONObject, args: JSONObject): String {
        val path = (cfg.optString("path", "")).ifEmpty { args.optString("path", "") }
        if (path.isEmpty()) return "未指定 path"
        return try {
            val text = java.io.File(path).readText(Charsets.UTF_8)
            if (text.length > 8000) text.take(8000) + "\n...[结果已截断]" else text
        } catch (e: Exception) {
            "读取失败: ${e.message}"
        }
    }
}
